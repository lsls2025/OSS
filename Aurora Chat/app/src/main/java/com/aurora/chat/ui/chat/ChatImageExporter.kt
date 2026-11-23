package com.aurora.chat.ui.chat

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import coil.Coil
import coil.request.ImageRequest
import com.aurora.chat.ErrorReporter
import com.aurora.chat.data.local.AvatarCache
import com.aurora.chat.data.local.LocalStorage
import com.aurora.chat.data.repository.ChatRepository
import com.aurora.chat.ui.chat.media.chatMediaThumbUrl
import com.aurora.chat.ui.components.AnimationMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * 「多选消息 → 虚拟截图」导出器。做法与 QQ 一致。
 *
 * **不使用"测量无限高度"**——那是移动端不可行的（消息列表是视口驱动的容器，
 * 强行求它的完整高度只会得到近似值，导致底部被切）。而是：
 *
 *  1. 把内容放进一个**真实存在于窗口内、尺寸 = 屏幕宽 × 一屏高**的容器（由系统正常布局）；
 *  2. 等图片/头像加载完成（此时它渲染出来的就是一张完美截图）；
 *  3. 把这一屏 `draw()` 到 Bitmap 上，绘制时向下平移"已滚动的像素数"直接落到大图对应位置；
 *  4. 向下滚动一屏（留少量重叠），重复第 3 步，直到滚到底。
 *
 * 总高度由框架直接给出：`ScrollState.maxValue + 一屏高`，是精确值，无需任何猜测或探测。
 * 相邻两屏有重叠，后一屏会覆盖重叠区，每个像素都来自"不在自己底边"的帧，因此没有接缝。
 */
object ChatImageExporter {

    private const val TAG = "ChatShot"

    /** 聊天背景色。与对话页底色严格一致：所有会话统一浅灰。
     *  注意：**不要**再用 #F9FAFB，那是顶栏的颜色，用错会让整张图看起来"不像截图"。 */
    private fun chatBgOf(friendId: Long): Color = Color(0xFFEDEDED)

    /** 可渲染的图片消息类型 */
    private val IMAGE_TYPES = setOf("image", "jpg", "jpeg", "png", "gif", "webp")

    /** 背景图中心裁剪铺满画布（与 Compose 侧 drawChatBackgroundCover 同规则） */
    private fun drawCover(canvas: Canvas, bmp: Bitmap, w: Int, h: Int) {
        if (bmp.width <= 0 || bmp.height <= 0 || w <= 0 || h <= 0) return
        val scale = max(w.toFloat() / bmp.width, h.toFloat() / bmp.height)
        val dw = bmp.width * scale
        val dh = bmp.height * scale
        val dx = (w - dw) / 2f
        val dy = (h - dh) / 2f
        canvas.drawBitmap(bmp, android.graphics.Rect(0, 0, bmp.width, bmp.height),
            android.graphics.RectF(dx, dy, dx + dw, dy + dh), null)
    }

    /** Bitmap 像素预算（约 48MB），超出则整体等比缩小 */
    private const val MAX_PIXELS = 12_000_000L

    /** 缩放下限 */
    private const val MIN_SCALE = 0.34f

    /** 组合稳定所需帧数 */
    private const val SETTLE_FRAMES = 8

    /** 相邻两屏的重叠像素，避免气泡被切成两半 */
    private const val OVERLAP = 220

    /** 最大屏数兜底 */
    private const val MAX_FRAMES = 60

    /** 阶段①探测真实内容高度时容器使用的高度：远大于任何真实内容，保证内容测量不受视口约束 */
    private const val PROBE_H = 100_000

    /** 一条待导出的消息 */
    class Item(val msg: IMessageRef)

    /**
     * 生成一张长图。需在主线程调用（内部切 IO 预热缓存）。返回 null 表示生成失败。
     */
    suspend fun render(
        context: Context,
        currentUserId: Long,
        currentUserName: String,
        friendId: Long,
        friendName: String,
        isGroup: Boolean,
        groupMemberNames: Set<String>,
        senderRoles: Map<Long, String> = emptyMap(),
        items: List<Item>,
        /** 会话全量消息：用于解析被引用消息的发送者与撤回态（选中集合里通常没有被引用的那条） */
        allMessages: List<IMessageRef> = emptyList(),
    ): Bitmap? {
        if (items.isEmpty()) return null
        val activity = context.findActivity() ?: return null
        val dm = context.resources.displayMetrics
        val widthPx = dm.widthPixels
        if (widthPx <= 0) return null
        val viewportH = (activity.window.decorView.height.takeIf { it > 0 } ?: dm.heightPixels)
            .coerceIn(1200, 4000)

        // ① 预热头像与聊天图片缓存，否则会画到骨架屏/默认头像；
        //    同时把选中消息里的图片同步解码成 Bitmap，导出渲染时直接绘制，
        //    不依赖 Coil 异步加载时序（图片在离屏 View 里异步加载容易画空/抖动）
        val bg = chatBgOf(friendId)
        val myAvatar = withContext(Dispatchers.IO) { loadMyAvatar(context, currentUserId) }
        val exportBitmaps = withContext(Dispatchers.IO) {
            warmUp(context, items, currentUserId)
            preloadExportBitmaps(context, items)
        }
        // 预热自定义聊天背景进内存缓存：Compose 侧 chatBackground 首帧 peek 命中，截图里即是背景图
        withContext(Dispatchers.IO) { ChatBackgroundStorage.load(context, friendId) }

        val animMode = AnimationMode.entries[
            LocalStorage.getChatPrefs(context).getInt("animation_mode", 0).coerceIn(0, 2)
        ]

        // ② 放进窗口内一个"真实尺寸"的容器：宽=屏宽，高=一屏。INVISIBLE 只影响系统绘制，
        //    我们自己 draw() 到 Bitmap 不受影响，因此用户看不到闪烁。
        val compose = ComposeView(activity)
        compose.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        val host = FrameLayout(activity)
        host.addView(
            compose,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        val decor = activity.window.decorView as? ViewGroup
        // 阶段①：容器高度给 PROBE_H（远超内容），内容测量时不受视口约束，
        // 这样 onSizeChanged 量到的是**内容真实高度**。
        // 若沿用 viewportH，verticalScroll 会把内容的测量约束压到一屏高，
        // 量出来永远是视口高 → maxOffset=0 → 长图只剩一屏（这正是旧版本只导出一屏的原因）。
        decor?.addView(
            host,
            0,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, PROBE_H)
        )
        host.visibility = android.view.View.INVISIBLE

        val scroll = ScrollState(0)
        var contentHeight = 0
        compose.setContent {
            ExportContent(
                scroll = scroll,
                viewportH = viewportH,
                onContentHeight = { contentHeight = it },
                currentUserId = currentUserId,
                currentUserName = currentUserName,
                friendId = friendId,
                friendName = friendName,
                isGroup = isGroup,
                groupMemberNames = groupMemberNames,
                senderRoles = senderRoles,
                animMode = animMode,
                myAvatar = myAvatar,
                allMessages = allMessages,
                items = items,
                exportMediaBitmaps = exportBitmaps
            )
        }
        settle()
        settle()

        if (contentHeight !in 1..100000) {
           decor?.removeView(host)
               ErrorReporter.warn(TAG, "render 失败：未能取得内容高度 contentH=$contentHeight")
                    return null
                }

             // 阶段②：把容器缩回一屏高，让 verticalScroll 恢复滚动能力。
                // 高度已经在阶段①量定（outH 用的是这个真实值），这里只影响"怎么看"，不影响"多高"。
             // 不做这一步的话滚动最大值会按超高视口计算，逐屏绘制会重复画同一段。
                host.layoutParams = host.layoutParams?.apply { height = viewportH }
                settle()
                settle()

        // ③ 逐屏截图并直接画到大图的对应位置（后一屏覆盖重叠区，无接缝）
        val outH = contentHeight
        val out = try {
            Bitmap.createBitmap(widthPx, outH, Bitmap.Config.ARGB_8888)
        } catch (t: Throwable) {
            null
        }
        if (out == null) {
            decor?.removeView(host)
            ErrorReporter.warn(TAG, "render 失败：无法创建 ${widthPx}x$outH 的 Bitmap")
            return null
        }
        val canvas = Canvas(out)
        // 自定义聊天背景存在时，大图底也铺该图（中心裁剪），与对话页一致；否则默认底色
        val bgBitmap = ChatBackgroundStorage.peek(friendId)?.asAndroidBitmap()
        if (bgBitmap != null) {
            drawCover(canvas, bgBitmap, out.width, out.height)
        } else {
            canvas.drawColor(bg.toArgb())
        }

        val maxOffset = max(0, outH - viewportH)
        val step = max(1, viewportH - OVERLAP)
        var offset = 0
        var frames = 0
        while (true) {
            scroll.scrollTo(offset)
            settle()
            canvas.save()
            canvas.translate(0f, offset.toFloat())
            host.draw(canvas)
            canvas.restore()
            frames++
            if (offset >= maxOffset || frames >= MAX_FRAMES) break
            offset = min(offset + step, maxOffset)
        }
        decor?.removeView(host)
        ErrorReporter.debug(TAG, "render: w=$widthPx vp=$viewportH outH=$outH frames=$frames")

        if (!hasContent(out, bg)) {
            out.recycle()
            ErrorReporter.warn(TAG, "render 失败：画面无内容")
            return null
        }

        val cropped = trimEdges(out, bg)
        if (cropped !== out) out.recycle()

        // ④ 像素超预算 → 整体等比缩小
        val pixels = cropped.width.toLong() * cropped.height.toLong()
        if (pixels <= MAX_PIXELS) return cropped
        val s = max(MIN_SCALE, Math.sqrt(MAX_PIXELS.toDouble() / pixels).toFloat())
        val scaled = try {
            Bitmap.createScaledBitmap(
                cropped,
                max(1, (cropped.width * s).toInt()),
                max(1, (cropped.height * s).toInt()),
                true
            )
        } catch (t: Throwable) {
            ErrorReporter.warn(
                TAG,
                "缩放失败 pixels=$pixels scale=$s src=${cropped.width}x${cropped.height} err=${t.javaClass.simpleName}: ${t.message}"
            )
            null
        }
        if (scaled != null && scaled !== cropped) cropped.recycle()
        ErrorReporter.debug(
            TAG,
            "render 完成 out=${cropped.width}x${cropped.height} pixels=$pixels scale=$s scaled=${scaled?.width}x${scaled?.height}"
        )
        return scaled
    }

    // ══════════════════ 组合内容：一屏高的可滚动列 ══════════════════

    @Composable
    private fun ExportContent(
        scroll: ScrollState,
        viewportH: Int,
        onContentHeight: (Int) -> Unit,
        currentUserId: Long,
        currentUserName: String,
        friendId: Long,
        friendName: String,
        isGroup: Boolean,
        groupMemberNames: Set<String>,
        senderRoles: Map<Long, String>,
        animMode: AnimationMode,
        myAvatar: Bitmap?,
        allMessages: List<IMessageRef>,
        items: List<Item>,
        /** 已同步解码的图片 Bitmap（key=resolveMediaUrl 后的 URL），图片消息直接绘制 */
        exportMediaBitmaps: Map<String, ImageBitmap>
    ) {
        // 引用解析表：与对话页一致，用全量消息才能查被引用者的名字与撤回态
        val allById = remember(allMessages) { allMessages.associateBy { it.id } }

        Column(
            Modifier.fillMaxSize()
                // 与对话页同一背景：默认灰/白，好友设置过背景图片时为该图（已在 render 前预热）
                .chatBackground(friendId)
                .verticalScroll(scroll)
        ) {
            // 版式严格对齐 ChatConversationScreen.kt:4830-4836 的 contentPadding + spacedBy
            Column(
                Modifier.fillMaxWidth()
                    // 内容真实高度：必须用测量值，不能用 scroll.maxValue。
                    // ScrollState.maxValue 在被 verticalScroll 的 sideEffect 赋值前是
                    // Float.POSITIVE_INFINITY，Infinity + viewportH 落到 Int 会溢出成负数，
                    // 直接撞上「contentHeight !in 1..100000」判失败（旧版本一直生成失败的原因）。
                    // 内容比一屏短时补足到一屏高，保证绘制循环至少有一屏。
                    .onSizeChanged { onContentHeight(maxOf(it.height, viewportH)) }
                    .padding(
                        start = 12.dp,
                        end = 12.dp,
                        top = 4.dp,
                        // AI 对话底部留 32.dp，其余4.dp（与列表完全一致）
                        bottom = if (friendId == AI_CHAT_ID) 32.dp else 4.dp
                    )
            ) {
                items.forEachIndexed { index, item ->
                    val m = item.msg

                    // 间距 6.dp，等价真实列表的 Arrangement.spacedBy(6.dp)
                    if (index > 0) Spacer(Modifier.height(6.dp))

                    // 发送者切换时额外 10.dp（对应 ChatConversationScreen.kt:5039-5042）
                    val prev = items.getOrNull(index - 1)?.msg
                    if (prev != null && !prev.isSystemNotice && !m.isSystemNotice &&
                        prev.isMine != m.isMine
                    ) {
                        Spacer(Modifier.height(10.dp))
                    }

                    // 被引用消息的展示状态，逐项对齐 ChatConversationScreen.kt:5050-5077
                    val replyMsg = if (m.replyToId > 0L) allById[m.replyToId] else null
                    val replyIsRevoked = if (m.replyToId <= 0L) false
                    else if (replyMsg != null) replyMsg.isRevoked != 0
                    else isLocalChat(friendId)
                    val replyIsMine = replyMsg?.isMine == true
                    val replySenderName = when {
                        replyMsg != null -> replyMsg.senderName
                        replyIsMine -> currentUserName.ifEmpty { m.replyToSender }
                        else -> m.replyToSender
                    }

                    // 用列表真正使用的 MessageBubbleItem，观感与聊天页一致；不加任何标题栏/页脚
                    MessageBubbleItem(
                        msg = m,
                        // 与对话页一致：mediaUrl 为空（上传中/加载失败）时画灰色占位块，
                        // 不能恒为 true，否则导出图会走出真实加载分支，与屏幕不同
                        loadMedia = m.mediaUrl.isNotEmpty(),
                        myAvatar = myAvatar,
                        currentUserId = currentUserId,
                        currentUserName = currentUserName,
                        friendId = friendId,
                        friendName = friendName,
                        animMode = animMode,
                        isGroup = isGroup,
                        groupMemberNames = groupMemberNames,
                        senderRole = senderRoles[m.fromUserId].orEmpty(),
                        multiSelectActive = false,
                        isMenuOpen = false,
                        replyIsRevoked = replyIsRevoked,
                        replyIsMine = replyIsMine,
                        replySenderName = replySenderName,
                        // 传非空回调，否则 AI 消息的「查看所有改动」「调用 N 个工具」两行按钮整行消失
                        onViewChanges = { },
                        onToolCalls = { },
                        onAvatarClick = { _, _ -> },
                        onDeleteMessage = { },
                        exportMediaBitmaps = exportMediaBitmaps
                    )
                }
            }
        }
        // 高度由内容 Column 的 onSizeChanged 上报（见上方注释），这里不再用
        // scroll.maxValue 推导——它在被赋值前是 Infinity，会溢出成负数。
    }

    // ══════════════════ 预热 ══════════════════

    private suspend fun loadMyAvatar(context: Context, currentUserId: Long): Bitmap? {
        if (currentUserId <= 0) return null
        AvatarCache.getCachedUserAvatarSync(currentUserId)?.let { return it }
        runCatching { ChatRepository.loadAvatar(context, currentUserId) }
        return AvatarCache.getCachedUserAvatarSync(currentUserId)
    }

    private suspend fun warmUp(context: Context, items: List<Item>, currentUserId: Long) {
        // UserAvatar 组合期同步读 AvatarCache，先热起来才是真头像
        val ids = items.map { it.msg.fromUserId }
            .filter { it > 0L && it != currentUserId }
            .distinct()
        for (id in ids) {
            if (AvatarCache.getCachedUserAvatarSync(id) != null) continue
            runCatching { ChatRepository.loadAvatar(context, id) }
        }
        // 图片按气泡实际使用的缩略图 URL 预热 Coil 内存缓存
        val loader = Coil.imageLoader(context)
        for (item in items) {
            val m = item.msg
            if (m.mediaType !in IMAGE_TYPES || m.mediaUrl.isEmpty()) continue
            runCatching {
                val url = chatMediaThumbUrl(resolveMediaUrl(m.mediaUrl))
                loader.execute(ImageRequest.Builder(context).data(url).build())
            }
        }
    }

    /**
     * 同步解码选中消息里的图片为 Bitmap（key=resolveMediaUrl 后的 URL）。
     *
     * 导出渲染时由 ChatMediaImage 直接绘制这些 Bitmap，彻底绕开 Coil 异步管线：
     * 离屏 View 中 AsyncImage 的加载完成时机不可控，settle 几帧后可能仍在 Loading
     * 或才刚完成重组，导致导出图里图片画空/抖动。同步解码 + 直接绘制则必然命中。
     * 解码使用气泡实际加载的压缩缩略图（chatMediaThumbUrl），控制内存占用。
     */
    private suspend fun preloadExportBitmaps(context: Context, items: List<Item>): Map<String, ImageBitmap> {
        val out = mutableMapOf<String, ImageBitmap>()
        val loader = Coil.imageLoader(context)
        for (item in items) {
            val m = item.msg
            if (m.mediaType !in IMAGE_TYPES || m.mediaUrl.isEmpty()) continue
            val resolved = resolveMediaUrl(m.mediaUrl)
            if (resolved.isEmpty() || out.containsKey(resolved)) continue
            runCatching {
                val url = chatMediaThumbUrl(resolved)
                // 目标容器不超过 150×200dp，1x 屏幕下 300×400px 即够；给 1200 上限避免超大图撑爆内存
                val res = loader.execute(
                    ImageRequest.Builder(context).data(url).size(1200).build()
                )
                val drawable = res.drawable ?: return@runCatching
                val bmp = drawable.toExportBitmap() ?: return@runCatching
                out[resolved] = bmp.asImageBitmap()
            }
        }
        return out
    }

    /** 把任意 Drawable 转成 Bitmap（GIF 等取第一帧；BitmapDrawable 直接复用底层 Bitmap） */
    private fun Drawable.toExportBitmap(): Bitmap? {
        if (this is BitmapDrawable) {
            val bmp = bitmap
            if (bmp != null && !bmp.isRecycled) return bmp
        }
        val w = intrinsicWidth.takeIf { it > 0 } ?: 300
        val h = intrinsicHeight.takeIf { it > 0 } ?: 300
        return runCatching {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            setBounds(0, 0, w, h)
            draw(c)
            bmp
        }.getOrNull()
    }

    // ══════════════════ 工具 ══════════════════

    private suspend fun settle() {
        repeat(SETTLE_FRAMES) { androidx.compose.runtime.withFrameNanos { } }
    }

    /** 画面里是否存在非背景内容（识别"什么都没画出来"） */
    private fun hasContent(bmp: Bitmap, bg: Color): Boolean {
        val bgInt = bg.toArgb()
        val xs = intArrayOf(bmp.width / 8, bmp.width / 2, (bmp.width * 7) / 8)
        var y = 0
        while (y < bmp.height) {
            for (x in xs) if (bmp.getPixel(x, y) != bgInt) return true
            y += 2
        }
        return false
    }

    /** 裁掉整图上下/左右多余空白（只裁外边缘，不影响中间的气泡间距） */
    private fun trimEdges(src: Bitmap, bg: Color): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return src
        val bgInt = bg.toArgb()
        val xs = intArrayOf(w / 8, w / 2, (w * 7) / 8)
        fun blankRow(y: Int): Boolean {
            for (x in xs) if (src.getPixel(x, y) != bgInt) return false
            return true
        }

        var first = -1
        for (y in 0 until h) { if (!blankRow(y)) { first = y; break } }
        if (first < 0) return src
        var last = h - 1
        for (y in h - 1 downTo first) { if (!blankRow(y)) { last = y; break } }
        if (first == 0 && last == h - 1) return src
        return try {
            Bitmap.createBitmap(src, 0, first, w, last - first + 1)
        } catch (_: Throwable) {
            src
        }
    }

    private fun Context.findActivity(): Activity? {
        var ctx: Context? = this
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }
}
