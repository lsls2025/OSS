package com.aurora.chat.ui.chat
import com.aurora.chat.ui.theme.LocalAppColors

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.chat.data.api.AuroraApi
import com.aurora.chat.data.api.HttpClient
import com.aurora.chat.data.local.FavoriteAddResult
import com.aurora.chat.data.local.FavoritesStore
import com.aurora.chat.data.local.RecentShareStore
import com.aurora.chat.data.local.RecentShareTarget
import com.aurora.chat.data.repository.ChatRepository
import com.aurora.chat.data.upload.MediaUploadManager
import com.aurora.chat.ui.components.GroupAvatar
import com.aurora.chat.ui.components.UserAvatar
import com.aurora.chat.util.GallerySaver
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 抽屉里一个可投送的会话目标。 */
private data class ShareTargetUi(
    val id: Long,
    val name: String,
    val isGroup: Boolean,
    val fromRecent: Boolean
)

/**
 * 抽屉里的一枚动作。
 * [appPkg] 非空时用设备上该应用的真实图标（微信 / QQ），拿不到才退回中性系统图标；
 * 其余站内动作用统一的 Material 图标，不再自绘彩色字母圆。
 */
private data class ShareActionUi(
    val key: String,
    val label: String,
    val appPkg: String? = null,
    val icon: ImageVector? = null,
    val onClick: () -> Unit
)

/**
 * 全屏看图时长按呼出的「分享至」抽屉。
 *
 * 高度约屏幕的 40%（比 1/3 多一点）。进出动画用一条 [Animatable] 同时驱动
 * 「遮罩透明度」和「面板位移」，并且只在 graphicsLayer 的绘制阶段读值：
 * 每帧不触发重组，长图滑动时也不会被面板动画拖慢。
 *
 * 三排内容：
 *  1. 最近分享过的人（没有则退回最近聊过天的会话），从最近到最早横向排列；
 *  2. 发到微信 / 发到 QQ / 用其他应用打开；
 *  3. 保存到手机 / 保存到工作区 / 收藏 / 定位聊天（图中识别到二维码时，最右侧再多一项「扫描二维码」）。
 */
@Composable
fun ImageShareSheet(
    imageUrl: String,
    favoriteRef: IMessageRef?,
    onLocate: () -> Unit,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }

    // 图中二维码：识别到才在最后一排「定位聊天」右侧追加「扫描二维码」，没有码/识别失败就整项不出现。
    // 识别放在 IO 线程，抽屉照常展开，稍后才把这一项补进菜单。
    var qrText by remember(imageUrl) { mutableStateOf<String?>(null) }
    var showQrResult by remember(imageUrl) { mutableStateOf(false) }
    LaunchedEffect(imageUrl) {
        qrText = if (imageUrl.isBlank()) null else detectQrText(ctx, imageUrl)
    }

    // 位移距离按屏幕高度算，首帧就能把面板完整藏到屏幕外，不需要等 onSizeChanged 回传。
    val screenHeightPx = with(LocalDensity.current) {
        LocalConfiguration.current.screenHeightDp.dp.toPx()
    }

    fun close() {
        if (closing) return
        closing = true
        scope.launch {
            progress.animateTo(0f, tween(220, easing = FastOutSlowInEasing))
            onDismiss()
        }
    }

    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(300, easing = FastOutSlowInEasing))
    }
    // 抽屉在内嵌覆盖层里，系统返回不会自动关掉它，必须显式消费，否则会穿透去关掉整个图片预览。
    BackHandler { close() }

    var targets by remember { mutableStateOf<List<ShareTargetUi>>(emptyList()) }
    LaunchedEffect(Unit) {
        // 第一排只留少量常用目标，别堆成一长串；剩下的靠横向拖动看。
        val limit = 6
        val recent = withContext(Dispatchers.IO) {
            runCatching { RecentShareStore.all(ctx) }.getOrDefault(emptyList())
        }
        if (recent.isNotEmpty()) {
            targets = recent.take(limit).map {
                ShareTargetUi(it.id, it.name, it.isGroup, fromRecent = true)
            }
        } else {
            // 还没有任何分享记录 → 退回「最近聊过天的人」，按最后一条消息时间倒序。
            val r = ChatRepository.getConversations()
            val list = (r.data ?: emptyList())
                .asSequence()
                .filter {
                    it.id != 0L && it.id != AI_CHAT_ID && it.id != NOTIFICATION_CHAT_ID &&
                        it.username.isNotBlank()
                }
                .sortedByDescending { it.lastTime }
                .take(limit)
                .map { ShareTargetUi(it.id, it.username, it.id < 0, fromRecent = false) }
                .toList()
            targets = list
        }
    }

    /** 把预览用的完整 URL 还原成服务端认可的相对路径；本地图返回 null，需要重新上传。 */
    fun serverMediaPath(): String? {
        val idx = imageUrl.indexOf("/chat-media/")
        return if (idx >= 0) imageUrl.substring(idx) else null
    }

    /** 外部分享（微信/QQ/系统 chooser）只能收 content://，先落一份到 FileProvider 白名单目录。 */
    suspend fun prepareShareFile(): File? = withContext(Dispatchers.IO) {
        try {
            val dir = File(ctx.cacheDir, "aurora_share").apply { mkdirs() }
            val rawExt = imageUrl.substringAfterLast('.').substringBefore('?').lowercase()
            val ext = if (rawExt in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")) rawExt else "jpg"
            val out = File(dir, "img_share_${System.currentTimeMillis()}.$ext")
            val ok = when {
                imageUrl.startsWith("file://") -> {
                    val src = File(imageUrl.removePrefix("file://"))
                    if (src.exists()) { src.copyTo(out, overwrite = true); out.length() > 0 } else false
                }
                imageUrl.startsWith("content://") -> {
                    val ins = ctx.contentResolver.openInputStream(Uri.parse(imageUrl))
                    if (ins != null) {
                        ins.use { input -> out.outputStream().use { output -> input.copyTo(output) } }
                        out.length() > 0
                    } else false
                }
                else -> HttpClient.downloadToFile(imageUrl, out, AuroraApi.authToken) > 0
            }
            if (ok) out else null
        } catch (_: Exception) {
            null
        }
    }

    fun launchExternalApp(kind: Int) {
        scope.launch {
            val file = prepareShareFile()
            if (file == null) {
                Toast.makeText(ctx, "图片读取失败", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val uri = try {
                androidx.core.content.FileProvider.getUriForFile(
                    ctx, "${ctx.packageName}.fileprovider", file
                )
            } catch (_: Exception) {
                Toast.makeText(ctx, "无法分享该文件", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val base = Intent(Intent.ACTION_SEND).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                // LocalContext 未必是 Activity（部分页面包了一层 ContextThemeWrapper），
                // 不带 NEW_TASK 会直接抛 "Calling startActivity() from outside of an Activity context"。
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                when (kind) {
                    0 -> {
                        // 微信：直接落到「选择联系人/朋友圈」的图片分享入口，跳一次就到位。
                        val pkg = "com.tencent.mm"
                        if (!isAppInstalled(ctx, pkg)) {
                            Toast.makeText(ctx, "未安装微信", Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        ctx.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        val explicit = Intent(base).apply {
                            setPackage(pkg)
                            setComponent(ComponentName(pkg, "com.tencent.mm.ui.tools.ShareImgUI"))
                        }
                        try {
                            ctx.startActivity(explicit)
                        } catch (_: Exception) {
                            // 微信版本改了入口 Activity：退回只按包名分发，让微信自己选处理页。
                            ctx.startActivity(Intent(base).apply { setPackage(pkg) })
                        }
                    }
                    1 -> {
                        // QQ：JumpActivity 是 QQ 对外分享的统一入口。
                        val pkg = "com.tencent.mobileqq"
                        if (!isAppInstalled(ctx, pkg)) {
                            Toast.makeText(ctx, "未安装 QQ", Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        ctx.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        val explicit = Intent(base).apply {
                            setPackage(pkg)
                            setComponent(ComponentName(pkg, "com.tencent.mobileqq.activity.JumpActivity"))
                        }
                        try {
                            ctx.startActivity(explicit)
                        } catch (_: Exception) {
                            ctx.startActivity(Intent(base).apply { setPackage(pkg) })
                        }
                    }
                    3 -> {
                        // 用 Aurora Agent 打开：直接调起 Agent 应用接收这张图（Agent 独立包名）
                        val pkg = "asia.aurora.agent"
                        if (!isAppInstalled(ctx, pkg)) {
                            Toast.makeText(ctx, "未安装 Aurora Agent", Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        ctx.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        try {
                            ctx.startActivity(Intent(base).apply { setPackage(pkg) })
                        } catch (_: Exception) {
                            Toast.makeText(ctx, "无法打开 Aurora Agent", Toast.LENGTH_SHORT).show()
                        }
                    }
                    else -> {
                        ctx.startActivity(
                            Intent.createChooser(base, "用其他应用打开")
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
            } catch (_: Exception) {
                Toast.makeText(ctx, "没有可接收图片的应用", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun sendTo(target: ShareTargetUi) {
        scope.launch {
            val rel = serverMediaPath()
            val ok = if (rel != null) {
                // 服务端已有这张图，直接复用同一地址，不重复上传。
                val r = if (target.isGroup) {
                    ChatRepository.sendGroupMessage(target.id, "", mediaType = "image", mediaUrl = rel)
                } else {
                    ChatRepository.sendMessage(target.id, "", mediaType = "image", mediaUrl = rel)
                }
                r.success
            } else {
                // 本地个人对话里的 file:// 图片对方读不到，走与「相册发图」相同的上传队列，
                // 目标会话直接传这次选中的 convId。
                val local = prepareShareFile()
                if (local != null) {
                    MediaUploadManager.enqueueFromUri(
                        ctx, target.id, target.isGroup, Uri.fromFile(local), "image/jpeg", "", 0
                    ) >= 0
                } else false
            }
            if (ok) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        RecentShareStore.record(
                            ctx,
                            RecentShareTarget(
                                id = target.id,
                                name = target.name,
                                isGroup = target.isGroup,
                                time = System.currentTimeMillis() / 1000
                            )
                        )
                    }
                }
                Toast.makeText(ctx, "已发送给 ${target.name}", Toast.LENGTH_SHORT).show()
                com.aurora.chat.ui.viewmodel.ChatViewModel.notifyNewMessage()
                close()
            } else {
                Toast.makeText(ctx, "发送失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 两排动作都做成横向可拖动列表：以后要加「朋友圈 / 复制链接 / 举报」之类，
    // 直接往列表里塞一项即可，不用重排版式，也不会因为数量变多把按钮挤扁。
    val externalActions = listOf(
        ShareActionUi(
            key = "wechat", label = "发到微信",
            appPkg = "com.tencent.mm", icon = Icons.Filled.Forum
        ) { launchExternalApp(0) },
        ShareActionUi(
            key = "qq", label = "发到 QQ",
            appPkg = "com.tencent.mobileqq", icon = Icons.Filled.ChatBubble
        ) { launchExternalApp(1) },
        ShareActionUi(
            key = "aurora_agent", label = "用 Aurora Agent 打开",
            appPkg = "asia.aurora.agent"
        ) { launchExternalApp(3) },
        ShareActionUi(key = "other", label = "用其他应用打开", icon = Icons.Filled.OpenInNew) {
            launchExternalApp(2)
        }
    )
    val bottomActions: List<ShareActionUi> = buildList {
        add(
            ShareActionUi(key = "save", label = "保存到手机", icon = Icons.Filled.Download) {
                scope.launch {
                    val ok = GallerySaver.saveImage(ctx, imageUrl)
                    Toast.makeText(
                        ctx,
                        if (ok) "已保存到相册" else "保存失败",
                        Toast.LENGTH_SHORT
                    ).show()
                    close()
                }
            }
        )
        // 保存到工作区：把当前图片原图落到 AI 工作区 chat_images/，AI 之后可直接用工作区工具读取/处理。
        // 紧挨在「保存到手机」右侧（同一排），两者语义相邻，用户不用在两排里找。
        add(
            ShareActionUi(key = "workspace", label = "保存到工作区", icon = Icons.Filled.Save) {
                scope.launch {
                    val f = saveImageToWorkspace(ctx, imageUrl)
                    Toast.makeText(
                        ctx,
                        if (f != null) "已保存到工作区：${f.name}" else "保存到工作区失败",
                        Toast.LENGTH_SHORT
                    ).show()
                    close()
                }
            }
        )
        add(
            ShareActionUi(key = "favorite", label = "收藏", icon = Icons.Filled.StarBorder) {
                if (favoriteRef == null) {
                    Toast.makeText(ctx, "该图片无法收藏", Toast.LENGTH_SHORT).show()
                } else {
                    scope.launch {
                        val r = runCatching { FavoritesStore.addMessage(ctx, favoriteRef) }
                            .getOrDefault(FavoriteAddResult.FAILED)
                        Toast.makeText(
                            ctx,
                            when (r) {
                                FavoriteAddResult.ADDED -> "已收藏"
                                FavoriteAddResult.EXISTS -> "该消息已在收藏中"
                                FavoriteAddResult.FAILED -> "收藏失败"
                            },
                            Toast.LENGTH_SHORT
                        ).show()
                        close()
                    }
                }
            }
        )
        // 定位聊天 = 跳回这条消息在会话里的位置；用轻量导航箭头，比十字准星在小尺寸下更干净。
        add(
            ShareActionUi(key = "locate", label = "定位聊天", icon = Icons.Filled.NearMe) {
                onLocate()
                close()
            }
        )
        // 只有识别到二维码才追加，位置就在「定位聊天」右侧
        if (qrText != null) {
            add(
                ShareActionUi(key = "qr", label = "扫描二维码", icon = Icons.Filled.QrCodeScanner) {
                    showQrResult = true
                }
            )
        }
    }

    Box(Modifier.fillMaxSize().zIndex(999f)) {
        // 遮罩：alpha 只在绘制阶段随动画变化，不参与重组。
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = progress.value * 0.42f }
                .background(Color.Black)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { close() }
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.40f)
                .graphicsLayer {
                    translationY = (1f - progress.value) * screenHeightPx * 0.42f
                }
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(com.aurora.chat.ui.theme.appCardBg())
                .padding(top = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "分享至",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = com.aurora.chat.ui.theme.appTextPrimary(),
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // ── 第一排：最近分享过的人（没有则最近聊过天的人），头像缩小后可横拖 ──
            if (targets.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    contentPadding = PaddingValues(horizontal = 14.dp)
                ) {
                    items(targets, key = { it.id }) { t ->
                        Column(
                            modifier = Modifier
                                .width(50.dp)
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() }
                                ) { sendTo(t) },
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            if (t.isGroup) {
                                GroupAvatar(
                                    internalGroupId = -t.id - 1000L,
                                    groupName = t.name,
                                    size = 40.dp
                                )
                            } else {
                                UserAvatar(userId = t.id, userName = t.name, size = 40.dp)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                t.name,
                                fontSize = 10.sp,
                                color = Color(0xFF4B5563),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                        .height(0.5.dp)
                        .background(com.aurora.chat.ui.theme.appDivider())
                )
            }

            // ── 第二排：发到微信 / 发到 QQ / 用其他应用打开（横向可拖动）──
            ActionRow(externalActions)

            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .height(0.5.dp)
                    .background(com.aurora.chat.ui.theme.appDivider())
            )

            // ── 最后一排：保存到手机 / 收藏 / 定位聊天（横向可拖动）
            //    图中含二维码时，最右侧再多一项「扫描二维码」──
            ActionRow(bottomActions)
            Spacer(Modifier.height(10.dp))
        }

        // 识别结果浮层：盖在抽屉之上，关掉后仍回到抽屉，不丢已选状态
        val qrForResult = qrText
        if (showQrResult && qrForResult != null) {
            QrResultOverlay(text = qrForResult, onClose = { showQrResult = false })
        }
    }
}

/**
 * 识别图片中的二维码：有码返回内容，无码/失败返回 null。
 * 一律走 IO：网络图先落缓存再解码，本地图直接采样解码，最大边压到 [maxDim] 以内防 OOM。
 */
private suspend fun detectQrText(ctx: Context, url: String): String? = withContext(Dispatchers.IO) {
    runCatching {
        val bmp = loadSampledBitmap(ctx, url, maxDim = 1600) ?: return@runCatching null
        try {
            decodeQrOnce(bmp)
        } finally {
            if (!bmp.isRecycled) bmp.recycle()
        }
    }.getOrNull()
}

/** 按最大边采样读取 Bitmap（file:// / content:// / 网络 URL 都支持） */
private suspend fun loadSampledBitmap(ctx: Context, url: String, maxDim: Int): Bitmap? =
    withContext(Dispatchers.IO) {
        fun sampleSize(w: Int, h: Int): Int {
            var s = 1
            while (maxOf(w, h) / s > maxDim) s *= 2
            return s
        }
        runCatching {
            when {
                url.startsWith("file://") -> {
                    val path = Uri.parse(url).path ?: return@runCatching null
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(path, bounds)
                    if (bounds.outWidth <= 0) return@runCatching null
                    BitmapFactory.decodeFile(
                        path,
                        BitmapFactory.Options().apply {
                            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
                        }
                    )
                }
                url.startsWith("content://") -> {
                    val uri = Uri.parse(url)
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    ctx.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, bounds)
                    }
                    if (bounds.outWidth <= 0) return@runCatching null
                    ctx.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(
                            it,
                            null,
                            BitmapFactory.Options().apply {
                                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
                            }
                        )
                    }
                }
                else -> {
                    val dir = File(ctx.cacheDir, "aurora_qr_probe").apply { mkdirs() }
                    val out = File(dir, "probe_${System.currentTimeMillis()}.img")
                    if (HttpClient.downloadToFile(url, out, AuroraApi.authToken) <= 0) {
                        null
                    } else {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(out.absolutePath, bounds)
                        val bmp = if (bounds.outWidth > 0) {
                            BitmapFactory.decodeFile(
                                out.absolutePath,
                                BitmapFactory.Options().apply {
                                    inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
                                }
                            )
                        } else null
                        out.delete()
                        bmp
                    }
                }
            }
        }.getOrNull()
    }

/**
 * 把当前预览的图片写入 AI 工作区（filesDir/ai_files/chat_images/），文件名带时间戳、重名自动加序号。
 * 存的是原图字节（不是浏览器里那张缩略图），AI 之后可用 read_file / 图像工具直接读。
 * 只落盘，不改动会话：返回写好的文件；失败返回 null。
 */
private suspend fun saveImageToWorkspace(ctx: Context, url: String): File? =
    withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        runCatching {
            val dir = File(AiChatManager.agentRoot(ctx), "chat_images")
                .apply { if (!exists()) mkdirs() }
            // 扩展名沿用 URL 里的真实后缀（已去掉 ?w=&q= 之类的查询串），认不出就按 jpg 存。
            val rawExt = url.substringBefore('?').substringAfterLast('.', "").lowercase()
            val ext = if (rawExt in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")) rawExt else "jpg"
            val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            var out = File(dir, "聊天图片_$ts.$ext")
            var i = 1
            while (out.exists()) {
                out = File(dir, "聊天图片_${ts}_$i.$ext")
                i++
            }
            val ok = when {
                // 本地图（content://）直接读流；file:// 直接拷贝；其余走项目统一的下载器（带鉴权头）
                url.startsWith("content://") -> ctx.contentResolver.openInputStream(Uri.parse(url))
                    ?.use { ins -> out.outputStream().use { ous -> ins.copyTo(ous) } } != null

                url.startsWith("file://") -> {
                    val src = File(Uri.parse(url).path ?: "")
                    if (src.isFile) { src.copyTo(out, overwrite = true); true } else false
                }

                else -> HttpClient.downloadToFile(url, out, AuroraApi.authToken) > 0
            }
            if (!ok || out.length() <= 0L) {
                out.delete()
                null
            } else {
                out
            }
        }.getOrNull()
    }

/** 单次 ZXing 解码（TRY_HARDER 提高照片里小码的命中率） */
private fun decodeQrOnce(bitmap: Bitmap): String? = runCatching {
    val w = bitmap.width
    val h = bitmap.height
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
    val reader = MultiFormatReader()
    val hints = HashMap<DecodeHintType, Any>()
    hints[DecodeHintType.TRY_HARDER] = true
    reader.setHints(hints)
    reader.decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(w, h, pixels))))
        .text?.trim()?.takeIf { it.isNotEmpty() }
}.getOrNull()

/** 二维码内容浮层：白底圆角卡，可复制；内容是网址时可直接打开。 */
@Composable
private fun QrResultOverlay(text: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val isLink = text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true)
    // 「打开」先问：应用内 / 浏览器（复用聊天链接的同一个弹窗，不另写一份）
    var showLinkChooser by remember { mutableStateOf(false) }
    // 浮层注册在抽屉的 BackHandler 之后 → 返回键先关浮层
    BackHandler { onClose() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClose() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.86f)
                .clip(RoundedCornerShape(16.dp))
                .background(com.aurora.chat.ui.theme.appSurface())
                // 消费点击，避免点到卡片内部也关掉
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) {}
                .padding(horizontal = 18.dp, vertical = 16.dp)
        ) {
            Text("二维码", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = com.aurora.chat.ui.theme.appTextPrimary())
            Spacer(Modifier.height(10.dp))
            Text(
                text,
                fontSize = 13.sp,
                color = Color(0xFF4B5563),
                maxLines = 6,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                // 复制 / 打开：用应用主色深蓝，和链接选择弹窗里的主按钮一致
                Text(
                    "复制",
                    fontSize = 14.sp,
                    color = Color(0xFF1E40AF),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("qr", text))
                            Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show()
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
                if (isLink) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "打开",
                        fontSize = 14.sp,
                        color = Color(0xFF1E40AF),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                showLinkChooser = true
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
            // 链接打开方式：应用内 / 浏览器（聊天里同一个弹窗）
            if (showLinkChooser) {
                com.aurora.chat.ui.text.LinkOpenChooserDialog(
                    url = text,
                    onDismiss = { showLinkChooser = false }
                )
            }
        }
    }
}

/** 一排可横向拖动的动作：条目少时静止即可，以后加多了自然能滑。 */
@Composable
private fun ColumnScope.ActionRow(actions: List<ShareActionUi>) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        contentPadding = PaddingValues(horizontal = 10.dp)
    ) {
        items(actions, key = { it.key }) { action -> ShareAction(action) }
    }
}

/** 单枚动作：微信 / QQ 用设备上的真实应用图标，其余用统一的中性系统图标。 */
@Composable
private fun ShareAction(item: ShareActionUi) {
    val ctx = LocalContext.current
    // 直接向 PackageManager 要微信 / QQ 自己的 launcher 图标，不自己画 logo；
    // 未安装或取不到时退回中性图标，按钮位置与尺寸保持不变。
    val appIcon = remember(item.appPkg) {
        val pkg = item.appPkg ?: return@remember null
        try {
            val drawable = ctx.packageManager.getApplicationIcon(pkg)
            val px = 128
            val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, px, px)
            drawable.draw(Canvas(bmp))
            bmp.asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }
    Column(
        modifier = Modifier
            .width(72.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { item.onClick() }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (appIcon != null) {
            Image(
                bitmap = appIcon,
                contentDescription = item.label,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(11.dp))
            )
        } else {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(com.aurora.chat.ui.theme.appCardBg()),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = item.icon ?: Icons.Filled.OpenInNew,
                    contentDescription = item.label,
                    tint = Color(0xFF4B5563),
                    modifier = Modifier.size(23.dp)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            item.label,
            fontSize = 11.sp,
            color = Color(0xFF4B5563),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

/** 供外部判断某个包是否已安装（预留给调用方，避免重复造轮子）。 */
private fun isAppInstalled(ctx: Context, pkg: String): Boolean =
    try { ctx.packageManager.getLaunchIntentForPackage(pkg) != null } catch (_: Exception) { false }
