package com.aurora.chat.ui.chat
import com.aurora.chat.ui.theme.LocalAppColors

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 单个表情项：path = assets/stickers/ 下的相对路径；anim = 动图（走 Coil 播放）。 */
data class StickerItem(val path: String, val anim: Boolean)

/** 一个资源分组（id 与 assets 子目录同名，如 image / coder / bear）。 */
data class StickerPack(val id: String, val items: List<StickerItem>)

/**
 * 表情资源：清单来自 assets/stickers/index.json（v2：packs[].items[]{f,anim}），
 * 图片本体在 assets/stickers/<分组>/ 下。换图只替换同名文件，清单顺序即面板顺序。
 */
object StickerAssets {
    // 位图缓存：静态图 decode 一次全场复用；动图交给 Coil（带内存/磁盘缓存）
    private val cache = LinkedHashMap<String, ImageBitmap>()

    fun loadPacks(context: Context): List<StickerPack> = runCatching {
        val text = context.assets.open("stickers/index.json").bufferedReader().use { it.readText() }
        val packs = JSONObject(text).optJSONArray("packs") ?: return emptyList()
        (0 until packs.length()).mapNotNull { pi ->
            val p = packs.optJSONObject(pi) ?: return@mapNotNull null
            val arr = p.optJSONArray("items") ?: return@mapNotNull null
            val items = (0 until arr.length()).mapNotNull { ii ->
                val o = arr.optJSONObject(ii) ?: return@mapNotNull null
                val f = o.optString("f").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                StickerItem(f, o.optBoolean("anim", false))
            }
            StickerPack(p.optString("id", "pack$pi"), items)
        }
    }.getOrDefault(emptyList())

    fun bitmap(context: Context, path: String): ImageBitmap? = synchronized(cache) {
        cache[path] ?: runCatching {
            context.assets.open("stickers/$path").use {
                BitmapFactory.decodeStream(it).asImageBitmap()
            }
        }.getOrNull()?.also { cache[path] = it }
    }
}

/**
 * 表情面板：与「+」附件面板同一槽位（Column 内 AnimatedVisibility），互斥展示。
 * 结构（自上而下）：tab 图标栏 → 分割线 → 表情区。
 * 面板高度固定 268dp，不随 tab 切换变化——高度一变，消息列表每帧重测，就会复现
 * 附件面板当年的「一卡一卡」。
 *
 * tab 顺序：emoji → 资源分组（顺序由 index.json 的 packs 决定） → 自定义收藏（爱心）。
 * 资源分组的图标按 pack.id 映射，不按下标：这样删掉某个板块后，后面的板块不会
 * 顺位占用错误的图标（例如 GIF 板块下线后，程序员不会被画成「播放三角」）。
 */
@Composable
fun StickerPanel(
    modifier: Modifier = Modifier,
    // panelHeight 传 null = 高度完全交给外部 modifier（统一底部槽位用），
    // 传具体值则保持自身真实高度、由外层裁切（兼容旧的独立调用）。
    panelHeight: androidx.compose.ui.unit.Dp? = 268.dp,
    // 点击某个表情后回调（id = 清单里的 assets 相对路径去扩展名，如 "image/image001"）
    onSendSticker: (String) -> Unit = {},
    // emoji tab：点击某个 emoji 字符后回调（直接发送，不插入输入框）
    onSendEmoji: (String) -> Unit = {},
    // 自定义收藏 tab：从相册选图后加入；面板内点自定义表情直接发图片消息
    onPickFromAlbum: () -> Unit = {},
    customStickers: List<String> = emptyList(),
    onSendCustomSticker: (String) -> Unit = {},
    onDeleteCustomSticker: (String) -> Unit = {}
) {
    val context = LocalContext.current
    var packs by remember { mutableStateOf<List<StickerPack>>(emptyList()) }
    var tab by remember { mutableStateOf(0) } // 0..packs.size-1 = 资源分组，packs.size = 自定义

    LaunchedEffect(Unit) {
        packs = withContext(Dispatchers.IO) { StickerAssets.loadPacks(context) }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            // 传了 panelHeight 就保持自身真实高度、由外层裁切（旧调用）；
            // 传 null 时高度来自外部 modifier（统一槽位里的 weight），不做任何硬编码。
            .then(
                if (panelHeight != null) Modifier.requiredHeight(panelHeight) else Modifier
            )
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .background(com.aurora.chat.ui.theme.appSurface())
    ) {
        // ── tab 图标栏：全图标无文字 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(Modifier.width(10.dp))
            // emoji tab（最左侧）：字符 😀 图标，内置 emoji 字符网格
            EmojiTab(index = 0, current = tab, onClick = { tab = 0 })
            // 资源分组 tab：图标按 pack.id 固定映射（相框/播放/</>/熊头），
            // 与板块在清单中的位置无关，删板块不会让后面的图标串位。
            packs.forEachIndexed { i, pack ->
                val kind = when (pack.id) {
                    "image" -> 0
                    "coder" -> 2
                    "bear" -> 3
                    else -> i.coerceAtMost(3)
                }
                StickerTab(index = i + 1, current = tab, kind = kind, onClick = { tab = i + 1 })
            }
            // 自定义收藏（爱心），固定收尾
            StickerTab(index = packs.size + 1, current = tab, kind = 4, onClick = { tab = packs.size + 1 })
            Spacer(Modifier.weight(1f))
            // tab 栏右侧不再放「+」按钮：从相册添加统一走收藏区的按钮 / 空态入口
        }
        // ── 分割线：tab 栏与表情区的硬边界 ──
        Box(
            Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(com.aurora.chat.ui.theme.appDivider())
        )
        // ── 表情区 ──
        // tab 索引：0 = emoji，1..packs.size = 资源分组（对应 packs[tab-1]），packs.size+1 = 自定义收藏
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when {
                // emoji tab（最左侧）
                tab == 0 -> EmojiGrid(onSendEmoji)
                // 自定义收藏 tab：空态引导添加，有内容直接显示网格
                tab >= packs.size + 1 -> CustomStickerSection(
                    paths = customStickers,
                    onPick = onPickFromAlbum,
                    onSend = onSendCustomSticker,
                    onDelete = onDeleteCustomSticker
                )
                // 资源分组 tab
                else -> {
                    val pack = packs.getOrNull(tab - 1)
                    when {
                        pack == null -> StickerEmptyHint("表情加载中…")
                        pack.items.isEmpty() -> StickerEmptyHint("暂无表情")
                        else -> LazyVerticalGrid(
                            columns = GridCells.Fixed(4),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(pack.items) { item ->
                                StickerCell(item) {
                                    onSendSticker(item.path.removeSuffix(".webp"))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** tab 项：图标 + 选中指示条（蓝色 16×2.5dp 圆角条，alpha 渐变）。 */
@Composable
private fun StickerTab(index: Int, current: Int, kind: Int, onClick: () -> Unit) {
    val selected = index == current
    val tint by animateColorAsState(
        if (selected) Color(0xFF1E40AF) else com.aurora.chat.ui.theme.appTextHint(),
        animationSpec = tween(180), label = "stickerTabTint"
    )
    val indicator by animateFloatAsState(
        if (selected) 1f else 0f,
        animationSpec = tween(180), label = "stickerTabIndicator"
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(56.dp)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() }
    ) {
        Spacer(Modifier.height(6.dp))
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            StickerTabIcon(kind = kind, tint = tint, selected = selected)
        }
        Box(
            Modifier
                .padding(top = 2.dp)
                .size(width = 16.dp, height = 2.5.dp)
                .alpha(indicator)
                .clip(RoundedCornerShape(1.5.dp))
                .background(Color(0xFF1E40AF))
        )
        Spacer(Modifier.height(3.dp))
    }
}

/**
 * tab 图标（Canvas 线稿，与输入栏圆钮同一套语言）：
 * kind 0=图片（相框+山+太阳） 1=动态（相框+播放三角） 2=程序员（</>） 3=熊出没（熊头） 4=自定义（爱心）
 */
@Composable
private fun StickerTabIcon(kind: Int, tint: Color, iconSize: Dp = 22.dp, selected: Boolean = false) {
    Canvas(modifier = Modifier.size(iconSize)) {
        val w = size.width
        val h = size.height
        val sw = 1.6.dp.toPx()
        when (kind) {
            0 -> {
                // 相框
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(sw / 2f, sw / 2f),
                    size = Size(w - sw, h - sw),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                    style = Stroke(width = sw)
                )
                // 山：两峰折线
                val mountain = Path().apply {
                    moveTo(w * 0.14f, h * 0.70f)
                    lineTo(w * 0.36f, h * 0.44f)
                    lineTo(w * 0.54f, h * 0.60f)
                    lineTo(w * 0.68f, h * 0.48f)
                    lineTo(w * 0.86f, h * 0.70f)
                }
                drawPath(
                    mountain, tint,
                    style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
                // 太阳
                drawCircle(tint, radius = w * 0.07f, center = Offset(w * 0.67f, h * 0.28f))
            }
            1 -> {
                // 相框
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(sw / 2f, sw / 2f),
                    size = Size(w - sw, h - sw),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                    style = Stroke(width = sw)
                )
                // 播放三角（实心 = 有动态内容的隐喻）
                val tri = Path().apply {
                    moveTo(w * 0.40f, h * 0.30f)
                    lineTo(w * 0.72f, h * 0.50f)
                    lineTo(w * 0.40f, h * 0.70f)
                    close()
                }
                drawPath(tri, tint)
            }
            2 -> {
                // 程序员：</>
                val left = Path().apply {
                    moveTo(w * 0.30f, h * 0.32f)
                    lineTo(w * 0.13f, h * 0.50f)
                    lineTo(w * 0.30f, h * 0.68f)
                }
                drawPath(
                    left, tint,
                    style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
                val right = Path().apply {
                    moveTo(w * 0.70f, h * 0.32f)
                    lineTo(w * 0.87f, h * 0.50f)
                    lineTo(w * 0.70f, h * 0.68f)
                }
                drawPath(
                    right, tint,
                    style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
                drawLine(
                    tint,
                    Offset(w * 0.58f, h * 0.28f),
                    Offset(w * 0.42f, h * 0.72f),
                    strokeWidth = sw, cap = StrokeCap.Round
                )
            }
            3 -> {
                // 熊出没：熊头线稿（两耳 + 头 + 双眼 + 吻部）
                val earR = w * 0.115f
                drawCircle(tint, earR, Offset(w * 0.27f, h * 0.26f), style = Stroke(width = sw))
                drawCircle(tint, earR, Offset(w * 0.73f, h * 0.26f), style = Stroke(width = sw))
                drawCircle(
                    tint, w * 0.315f,
                    Offset(w * 0.5f, h * 0.58f), style = Stroke(width = sw)
                )
                // 眼：实心两点
                drawCircle(tint, w * 0.045f, Offset(w * 0.40f, h * 0.52f))
                drawCircle(tint, w * 0.045f, Offset(w * 0.60f, h * 0.52f))
                // 吻部：鼻尖小圆 + 嘴弧
                drawCircle(tint, w * 0.05f, Offset(w * 0.5f, h * 0.63f))
                drawArc(
                    color = tint,
                    startAngle = 25f,
                    sweepAngle = 130f,
                    useCenter = false,
                    topLeft = Offset(w * 0.42f, h * 0.58f),
                    size = Size(w * 0.16f, h * 0.13f),
                    style = Stroke(width = sw, cap = StrokeCap.Round)
                )
            }
            else -> {
                // 爱心（自定义收藏）：标准心形。旧版控制点甩到画布边缘，画出来像个尖角水滴，
                // 这里改成左右两瓣对称的贝塞尔；选中实心、未选中描边，一眼能认出。
                val heart = Path().apply {
                    moveTo(w * 0.5f, h * 0.82f)
                    cubicTo(w * 0.08f, h * 0.52f, w * 0.10f, h * 0.16f, w * 0.34f, h * 0.16f)
                    cubicTo(w * 0.44f, h * 0.16f, w * 0.50f, h * 0.27f, w * 0.50f, h * 0.34f)
                    cubicTo(w * 0.50f, h * 0.27f, w * 0.56f, h * 0.16f, w * 0.66f, h * 0.16f)
                    cubicTo(w * 0.90f, h * 0.16f, w * 0.92f, h * 0.52f, w * 0.5f, h * 0.82f)
                    close()
                }
                if (selected) {
                    drawPath(heart, tint)
                } else {
                    drawPath(heart, tint, style = Stroke(width = sw, join = StrokeJoin.Round))
                }
            }
        }
    }
}

/**
 * 单格表情：正方形格子，图按 ContentScale.Fit 居中（不裁不变形）。
 * 静态图走位图缓存；动图走 Coil（Android 9+ 系统 ImageDecoder 自动逐帧播放）。
 */
@Composable
private fun StickerCell(item: StickerItem, onClick: () -> Unit) {
    val context = LocalContext.current
    val bmp by produceState<ImageBitmap?>(initialValue = null, item.path) {
        if (!item.anim) {
            value = withContext(Dispatchers.IO) { StickerAssets.bitmap(context, item.path) }
        }
    }
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (item.anim) {
            // 动图必须显式指定 ImageDecoderDecoder：裸字符串 model 时 Coil 对 asset 路径
            // 拿不到 MIME，会退回 BitmapFactory 只解第一帧 → 动图变静图。
            // ImageDecoderDecoder（API 28+）支持 GIF/动图 WebP 逐帧播放。
            val animReq = remember(item.path) {
                coil.request.ImageRequest.Builder(context)
                    .data("file:///android_asset/stickers/${item.path}")
                    .decoderFactory(
                        if (android.os.Build.VERSION.SDK_INT >= 28)
                            coil.decode.ImageDecoderDecoder.Factory()
                        else
                            coil.decode.BitmapFactoryDecoder.Factory()
                    )
                    .build()
            }
            AsyncImage(
                model = animReq,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(0.92f)
            )
        } else {
            val b = bmp
            if (b != null) {
                Image(
                    bitmap = b,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(0.92f)
                )
            }
        }
    }
}

@Composable
private fun StickerEmptyHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        androidx.compose.material3.Text(
            text = text,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = com.aurora.chat.ui.theme.appTextHint()
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CustomStickerSection(
    paths: List<String>,
    onPick: () -> Unit,
    onSend: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    if (paths.isEmpty()) {
        CustomStickerEmpty(onPick)
        return
    }
    Column(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(paths) { path ->
                Box(
                    modifier = Modifier
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(com.aurora.chat.ui.theme.appCardBg())
                        .combinedClickable(
                            onClick = { onSend(path) },
                            onLongClick = { onDelete(path) }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = java.io.File(path),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().padding(4.dp),
                        contentScale = ContentScale.Fit
                    )
                }
            }
        }
        // 继续添加：文字入口，不再是 tab 栏右侧那个「+」圆钮
        Box(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "从相册添加",
                fontSize = 12.sp,
                color = Color(0xFF1E40AF),
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { onPick() }
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun CustomStickerEmpty(onPick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        StickerTabIcon(kind = 4, tint = Color(0xFFD1D5DB), iconSize = 40.dp)
        Spacer(Modifier.height(10.dp))
        androidx.compose.material3.Text("还没有收藏的表情", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
        Spacer(Modifier.height(3.dp))
        androidx.compose.material3.Text("从相册添加你喜欢的图片和动图", fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextHint())
        Spacer(Modifier.height(16.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF1E40AF))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onPick() }
                .padding(horizontal = 18.dp, vertical = 7.dp)
        ) {
            androidx.compose.material3.Text(
                "从相册添加",
                fontSize = 12.sp,
                color = Color.White
            )
        }
    }
}

/** 内置 emoji 集合：常用 emoji 字符（约 60 个），4 列网格大字号渲染，点击直接发送。 */
private val BUILTIN_EMOJIS = listOf(
    "😀", "😁", "😂", "🤣", "😊", "😍", "🥰", "😘",
    "😎", "🤩", "🥳", "😉", "🙂", "😅", "😢", "😭",
    "😡", "🤬", "😱", "😨", "😰", "😥", "🤔", "🙄",
    "😴", "🤤", "😋", "🤗", "🤭", "😏", "😌", "😔",
    "👍", "👎", "👏", "🙏", "🤝", "💪", "✌️", "🤞",
    "👌", "🫰", "🤙", "🖐️", "👋", "🤲", "🙌", "👀",
    "❤️", "💔", "💯", "🔥", "✨", "⭐", "🎉", "🎊",
    "🍺", "🍻", "☕", "🌹", "💐", "🎁"
)

/**
 * emoji tab 项：字符 😀 作为图标（与其它 tab 的 Canvas 线稿视觉同构），选中指示条同款。
 * 固定在 tab 栏最左侧（index = 0）。
 */
@Composable
private fun EmojiTab(index: Int, current: Int, onClick: () -> Unit) {
    val selected = index == current
    val indicator by animateFloatAsState(
        if (selected) 1f else 0f,
        animationSpec = tween(180), label = "emojiTabIndicator"
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(56.dp)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() }
    ) {
        Spacer(Modifier.height(6.dp))
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            Text("😀", fontSize = 18.sp)
        }
        Box(
            Modifier
                .padding(top = 2.dp)
                .size(width = 16.dp, height = 2.5.dp)
                .alpha(indicator)
                .clip(RoundedCornerShape(1.5.dp))
                .background(Color(0xFF1E40AF))
        )
        Spacer(Modifier.height(3.dp))
    }
}

/**
 * 内置 emoji 网格：5 列，格子内 34sp 大字号 Text 渲染 emoji 字符（视觉为图片形态），
 * 点击直接回调 onSendEmoji（不插入输入框）。
 */
@Composable
private fun EmojiGrid(onSendEmoji: (String) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(5),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(BUILTIN_EMOJIS) { emoji ->
            Box(
                modifier = Modifier
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onSendEmoji(emoji) },
                contentAlignment = Alignment.Center
            ) {
                Text(emoji, fontSize = 34.sp)
            }
        }
    }
}
