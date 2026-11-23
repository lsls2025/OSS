package com.aurora.chat.ui.profile
import com.aurora.chat.ui.theme.LocalAppColors

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import android.view.TextureView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Comment
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.graphics.vector.ImageVector
import com.aurora.chat.ui.community.SharePostDialog
import com.aurora.chat.ui.components.UserAvatar
import com.aurora.chat.ErrorReporter
import com.aurora.chat.ui.common.VideoEditorScreen
import com.aurora.chat.data.local.LocalStorage
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.aurora.chat.data.api.AuroraApi
import com.aurora.chat.data.api.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.min
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 短视频：独立全屏页面（与「小游戏」同级），从「我的」页入口进入。
 *
 * 加载架构（与后端 HLS 多码率对齐）：
 * - 竖向分页（TikTok 式），每屏一条视频铺满整屏、循环播放；
 * - 仅【当前页】创建 ExoPlayer 并播放；滑走的页立即 release（同一时刻最多 1 个解码器，
 *   2 核机不再被多路解码拖垮）。相邻页只渲染封面占位，不创建解码器；
 * - 播放地址为后端返回的 HLS master playlist（.m3u8）→ ExoPlayer 原生多码率自适应 +
 *   分片按需加载，不整片下载；本地上传预览则直接播 content:// Uri；
 * - Feed 预加载：只预取「下一两个视频」的 HLS 首分片（2s 切片）到 OkHttp 磁盘缓存，
 *   滑到即命中，不重复回源；已看分片缓存命中，减少回流；
 * - ExoPlayer 复用项目 OkHttp 单例（含 20MB 磁盘缓存），预取与播放共享缓存；
 * - 进入即上报播放量（POST /view）；发布者、点赞数、评论数、播放量由后端聚合返回。
 */

// ---------- 数据模型（与后端 JSON 一一对应，前端后续接入无需再改后端） ----------
data class SVPublisher(val id: Long, val username: String, val avatar: String)
data class SVRendition(
    val quality: Int, val codec: String, val bitrate: Int,
    val width: Int, val height: Int, val playlist: String
)
data class SVItem(
    val id: Long, val publisher: SVPublisher, val title: String, val description: String,
    val coverUrl: String, val playUrl: String, val isHls: Boolean, val duration: Int,
    val viewCount: Int, val likesCount: Int, val commentsCount: Int, val hasLiked: Boolean, val createdAt: Long,
    /**
     * 视频「转正后」的真实显示尺寸与宽高比，由后端在转码时写入（产物不补边，等于源画面比例）。
     * 这是容器排布的唯一权威值：首帧之前就能用正确比例布局，不会再「先竖后横」地跳一下。
     * 0 表示老数据缺少该字段，此时依次回退到「封面比例 → VideoSize」。
     */
    val width: Int = 0,
    val height: Int = 0,
    val aspectRatio: Float = 0f,
    /**
     * 原画直通地址：后端对 H.264 源做了 -c:v copy 的零重编码 remux，画质与源**完全一致**。
     * 空表示该条没有直通能力（源是 HEVC/AV1），高画质只能走 HLS 原画档。
     */
    val directUrl: String = "",
    // 降级原因（HLS 转码失败时由服务端推导）。空 = HLS 成功；非空 = 走了兜底档（如 480P 兼容档）
    val degradeReason: String = "",
    // 多码率档位（240/360/480/720/1080），供清晰度切换用
    val renditions: List<SVRendition> = emptyList()
)
// 播放源：remote（playUrl → 绝对 HLS/mp4 URL）或 local（content:// 本地预览）
data class SVSource(
    val uri: Uri,
    val isRemote: Boolean,
    val coverUrl: String,
    val item: SVItem? = null,
    /**
     * 稳定身份：同一个作品从「本地预览」换成「转码完成的远端流」时，uri 会变但 identity 不变。
     * 播放器状态（TextureView 引用 / 画面比例 / 暂停态 / 清晰度）按 identity 记忆，
     * 只有真正切到另一条视频时才重置。否则换源瞬间 TextureView 引用会丢，新播放器绑不上画面
     * → 出现「有声音但画面定格」。
     */
    val identity: String = item?.let { "remote:${it.id}" } ?: "local:$uri"
)

@Composable
fun ShortVideoScreen(
    onBack: () -> Unit,
    isDeveloper: Boolean = false,
    initialVideoId: Long = 0,
    /**
     * 从「我的发布 - 视频」点进来时直接注入的那一条。
     * 全局 feed 首屏只有 20 条，较早的作品不在其中，只靠 id 会定位失败（停在第一条上）。
     * 非空时并入播放队列，保证点哪条就播哪条。
     */
    initialItem: SVItem? = null,
    /** 从「动态」点进来时，需要自动打开评论区并定位高亮的那条评论（内容 + 作者匹配；服务端动态不带评论 id） */
    initialCommentText: String = "",
    initialCommentUserId: Long = 0L,
    /**
     * 当前是否处于前台可见状态。被用户资料页、帖子详情等全屏层盖住时为 false ——
     * 此时必须暂停播放。短视频页在导航上不会被销毁（只是被压住），
     * 光靠 DisposableEffect 收不到任何事件，音频会一直在后台响。
     */
    isForeground: Boolean = true,
    onOpenUserProfile: (Long, String) -> Unit = { _, _ -> }
    ) {
    val ctx = LocalContext.current
    var feed by remember { mutableStateOf<List<SVItem>>(emptyList()) }
    var feedError by remember { mutableStateOf(false) }
    // 首帧请求未完成前不显示"还没有短视频"，避免"先空一下再开始加载"
    var feedLoading by remember { mutableStateOf(true) }
    // 总开关：开发者关闭后，进入本页只显示其自定义提示，不加载任何视频
    var gateChecked by remember { mutableStateOf(false) }
    var gateDisabled by remember { mutableStateOf(false) }
    var gateMessage by remember { mutableStateOf("") }
    // 上传闸门：开发者关闭「允许上传」后，本地上传入口点击即提示，实际拦截在服务端
    var uploadDisabled by remember { mutableStateOf(false) }
    val localPreviews = remember { mutableStateListOf<SVSource>() }

    // ── 播放偏好（画质 / 码率上限 / 播放模式 / 单条循环）：全部永久保存 ──
    var prefQuality by remember { mutableStateOf(ShortVideoPrefs.quality(ctx)) }
    // 码率上限已并入画质三档，恒为 0（不设上限）。
    // 不读本地旧设置：老用户可能存过 3Mbps，继续生效会把画质压死。
    val prefMaxBitrate = 0
    var playMode by remember { mutableStateOf(ShortVideoPrefs.playMode(ctx)) }
    var autoNext by remember { mutableStateOf(ShortVideoPrefs.autoNext(ctx)) }
    var loopSingle by remember { mutableStateOf(ShortVideoPrefs.loopSingle(ctx)) }
    // 设置面板 / 开发者作品信息页
    var settingsOpen by remember { mutableStateOf(false) }
    var devInfoOpen by remember { mutableStateOf(false) }
    var devInfoItem by remember { mutableStateOf<SVItem?>(null) }
    // 「综合」排序依据：本地已看记录（进入时读一次，播放中只追加不重排）
    var watchedIds by remember { mutableStateOf(ShortVideoPrefs.watched(ctx)) }
    // 未经排序的原始 feed + 本次进入固定的随机种子（滚动/重组不会重新洗牌）
    var rawFeed by remember { mutableStateOf<List<SVItem>>(emptyList()) }
    var shuffleSeed by remember { mutableStateOf(System.nanoTime()) }

    // 沉浸式（不隐藏系统栏）：
    // 之前用 hide(systemBars()) 把状态栏/导航栏收起来，系统栏一收，窗口内容区高度就变了，
    // 整页会被"往上一拉"；退出时系统栏回来，又被"往下一压" —— 进出各抽一下，非常出戏。
    // 这里改成只改外观：黑底视频继续铺到屏幕边缘（edge-to-edge），系统栏透明、图标转白，
    // 视觉上就是全屏，但布局尺寸一个像素都不变，不会有拉伸感。退出时还原原来的图标颜色。
    val rootView = LocalView.current
    DisposableEffect(Unit) {
        val window = ctx.findActivity()?.window
        val controller = window?.let { WindowInsetsControllerCompat(it, rootView) }
        val prevStatusLight = controller?.isAppearanceLightStatusBars
        val prevNavLight = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            controller?.let { c ->
                if (prevStatusLight != null) c.isAppearanceLightStatusBars = prevStatusLight
                if (prevNavLight != null) c.isAppearanceLightNavigationBars = prevNavLight
            }
        }
    }


    /**
     * 统一播放队列：本地待上传预览在前，feed 在后（同 id 不重复）。
     *
     * 之前每处都用 `feed.getOrNull(currentPage - localPreviews.size)` 这种偏移推算，
     * 只要 localPreviews / feed 任意一侧数量变化，当前页指向的条目就整体平移一格：
     * 画面还停在刚删掉那条的最后一帧，用户以为没删掉、再点一次，实际删的就是下一条。
     * 改成一条队列后，第 N 页的数据就是队列第 N 项，删除永远只删当前页这一条。
     */
    val pagesState = remember {
        derivedStateOf {
            val localIds = localPreviews.mapNotNull { it.item?.id }
            buildList {
                addAll(localPreviews)
                for (it in feed) {
                    if (it.id in localIds) continue
                    add(
                        SVSource(
                            uri = Uri.parse(
                                if (it.playUrl.startsWith("http")) it.playUrl
                                else AuroraApi.serverUrl + it.playUrl
                            ),
                            isRemote = true, coverUrl = it.coverUrl, item = it
                        )
                    )
                }
            }
        }
    }
    val pages by pagesState
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    val viewedIds = remember { mutableStateListOf<Long>() }
    var uploading by remember { mutableStateOf(false) }
    var uploadProgress by remember { mutableStateOf(0f) }
    var transcoding by remember { mutableStateOf(false) }
    // 选中本地视频后先进入编辑器；非 null 时展示 VideoEditorScreen，确认后才上传
    var editingVideoUri by remember { mutableStateOf<Uri?>(null) }
    // 编辑完成、真正上传前的「发布信息」：标题 + 简介（写入后端 title / description）
    var publishDraftUri by remember { mutableStateOf<Uri?>(null) }
    // 右上角「⋮」菜单（贴住按钮的浮层，不是侧滑面板）
    var menuOpen by remember { mutableStateOf(false) }
    // 打开菜单时锁定的那一条：菜单开着时页面禁止滑动，删除永远作用于"你点开菜单时看的这条"
    var menuTarget by remember { mutableStateOf<SVItem?>(null) }
    var deleting by remember { mutableStateOf(false) }
    // 已删除的视频 id：立刻从队列里剔除，后续任何一次 feed 刷新都不会再让它回来
    val removedIds = remember { mutableStateListOf<Long>() }
    // 评论 / 转发 弹窗当前所针对的视频
    var commentTarget by remember { mutableStateOf<SVItem?>(null) }
    // 「动态」跳转过来时的一次性自动开评论：只触发一次，用户关掉后不再自己弹回来
    var commentAutoOpened by remember { mutableStateOf(false) }
    var forwardTarget by remember { mutableStateOf<SVItem?>(null) }
    // 每条视频的评论数（评论成功后 +1，立刻刷新动作栏上的计数，避免"发了却还显示 0"）
    val commentCounts = remember { mutableStateMapOf<Long, Int>() }
    // 短视频禁言状态（与社区禁言/对话禁言互相独立）：仅「短视频禁言(4) / 全部禁言(3)」在此生效
    var svMutedUntil by remember { mutableStateOf(0L) }
    val svMuteRemainingText = remember(svMutedUntil) {
        if (svMutedUntil <= 0L) ""
        else {
            val remainSec = (svMutedUntil - System.currentTimeMillis() / 1000).coerceAtLeast(0)
            if (remainSec <= 0) ""
            else {
                val days = remainSec / 86400; val hours = (remainSec % 86400) / 3600
                val mins = (remainSec % 3600) / 60
                when {
                    days > 0 -> "${days}天${hours}小时"
                    hours > 0 -> "${hours}小时${mins}分钟"
                    mins > 0 -> "${mins}分钟"
                    else -> "${remainSec}秒"
                }
            }
        }
    }
    val isSvMuted = svMutedUntil > 0L && svMuteRemainingText.isNotEmpty()
    // 进入即查一次禁言状态（自查接口，所有用户可用）
    LaunchedEffect(Unit) {
        try {
            val r = AuroraApi.checkMuteStatus()
            if (r.success && r.data != null) {
                val expiresAt = r.data.optLong("expires_at", 0L)
                val muteType = r.data.optInt("mute_type", 0)
                if (expiresAt > 0 && (muteType == 4 || muteType == 3)) {
                    svMutedUntil = expiresAt
                }
            }
        } catch (_: Exception) { }
    }
    // 当前页正在播放的那一条：删除就删它，不再做任何索引偏移推算
    val currentSource = pages.getOrNull(pagerState.currentPage)
    val currentItem = currentSource?.item

    fun closeMenu() {
        menuOpen = false
        menuTarget = null
    }

    // 菜单开着时，返回键先收菜单，不退出页面
    BackHandler(enabled = menuOpen) { closeMenu() }

    // 按当前播放模式重排（进入时快照 + 切换模式时重排，播放过程中绝不重排）
    fun rebuildFeed() {
        val ordered = svOrderFeed(rawFeed, playMode, watchedIds, shuffleSeed)
        feed = ordered.filterNot { removedIds.contains(it.id) }
    }

    // 切换播放模式：立即持久化，并按新模式重排、回到第一条开始看
    fun changePlayMode(mode: SVPlayMode) {
        playMode = mode
        ShortVideoPrefs.setPlayMode(ctx, mode)
        shuffleSeed = System.nanoTime()
        watchedIds = ShortVideoPrefs.watched(ctx)
        rebuildFeed()
        if (pages.isNotEmpty()) scope.launch { pagerState.scrollToPage(0) }
    }

    // 从播放队列里彻底剔除：feed、本地预览占位、页面索引一起对齐
    fun dropFromQueue(id: Long) {
        if (!removedIds.contains(id)) removedIds.add(id)
        feed = feed.filterNot { it.id == id }
        // 上传后"本地预览"会被就地替换成远端条目，这里一并清掉，避免残留一条可播的幽灵
        val ghost = localPreviews.indexOfFirst { it.item?.id == id }
        if (ghost >= 0) localPreviews.removeAt(ghost)
        // 删除后同位置会显示下一条；只有把末尾几页删光、当前索引越界时才回退
        val localIds = localPreviews.mapNotNull { it.item?.id }
        val count = localPreviews.size + feed.count { it.id !in localIds }
        val lastPage = (count - 1).coerceAtLeast(0)
        if (pagerState.currentPage > lastPage) {
            scope.launch { pagerState.scrollToPage(lastPage) }
        }
    }

    fun doDelete(item: SVItem) {
        if (deleting) return
        deleting = true
        // 先本地剔除：点下去立即消失，不等网络回来
        dropFromQueue(item.id)
        closeMenu()
        scope.launch {
            val ok = deleteShortVideo(item.id)
            deleting = false
            if (!ok) {
                // 服务器没删掉就回滚，避免"看着没了、其实还在"
                removedIds.remove(item.id)
                fetchShortVideoFeed()?.let {
                    rawFeed = it
                    rebuildFeed()
                    feedError = false
                }
                Toast.makeText(ctx, "删除失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 拉取 feed：null=请求失败，空列表=确实没有视频（两者不能混为一谈）
    suspend fun loadFeed() {
        val raw = fetchShortVideoFeed()
        feedLoading = false
        if (raw == null) {
            feedError = true
            return
        }
        feedError = false
        // 「我的发布 - 视频」点进来的那条：全局 feed 首屏（20 条）可能不包含它，
        // 直接并入队列（同 id 不重复），保证点哪条就播哪条。
        rawFeed = if (initialItem != null && raw.none { it.id == initialItem.id }) {
            listOf(initialItem) + raw
        } else {
            raw
        }
        rebuildFeed()
    }

    // 进页面先问总开关：关闭时只显示开发者配置的提示，不拉 feed、不建播放器。
    // 提示只在「进入本页之后」出现 —— 入口点击不做任何提示，这是产品规则。
    LaunchedEffect(Unit) {
        try {
            val r = AuroraApi.getShortVideoGatePublic()
            if (r.success && r.data != null) {
                gateDisabled = r.data!!.optBoolean("disabled", false)
                gateMessage = r.data!!.optString("message", "")
                uploadDisabled = r.data!!.optBoolean("upload_disabled", false)
            }
        } catch (_: Exception) { }
        gateChecked = true
        if (!gateDisabled) loadFeed()
    }

    // 从「收藏」页 / 「动态」点进来：定位并跳转到指定视频
    LaunchedEffect(pages.size, feed.size) {
        if (initialVideoId > 0 && pages.isNotEmpty()) {
            val idx = pages.indexOfFirst { it.item?.id == initialVideoId }
            if (idx >= 0) {
                pagerState.scrollToPage(idx)
                // 「动态」里点「评论/回复/评论点赞」类提醒：定位到视频后自动把评论区拉起来，
                // 交给评论面板自己滚动到目标评论并闪烁两下（只触发一次）
                if (!commentAutoOpened && initialCommentText.isNotBlank()) {
                    commentAutoOpened = true
                    commentTarget = pages[idx].item
                }
            }
        }
    }

    // 进入即上报播放量（每个视频只报一次）+ 记入本地已看 + 预加载相邻页 HLS 首分片
    LaunchedEffect(pagerState.currentPage, pages.size) {
        val page = pagerState.currentPage
        val cur = pages.getOrNull(page) ?: return@LaunchedEffect
        val item = cur.item
        if (item != null && item.id > 0 && !viewedIds.contains(item.id)) {
            viewedIds.add(item.id)
            // 「综合」模式的依据：只追加，不重排（重排会让当前这条瞬间被挪走 → 跳页）
            ShortVideoPrefs.markWatched(ctx, item.id)
            watchedIds = ShortVideoPrefs.watched(ctx)
            withContext(Dispatchers.IO) {
                try {
                    HttpClient.jsonRequest(
                        "POST", "${AuroraApi.serverUrl}/api/shortvideo/view",
                        JSONObject().put("id", item.id).toString(), AuroraApi.authToken
                    )
                } catch (_: Exception) { /* 上报失败不阻断播放 */ }
            }
        }
        // 预加载下一两个视频的首分片（HTTP 级，命中 OkHttp 缓存）。
        // 必须并发：串行 await 时第一个没下完第二个不会开始，滑得快就全落空。
        for (n in 1..2) {
            val next = pages.getOrNull(page + n) ?: continue
            // 有原画直通且当前选了「高」时，下一条播的是直通文件，预取 HLS 分片纯属浪费带宽
            val hasDirect = !next.item?.directUrl.isNullOrBlank()
            if (next.isRemote && next.uri.toString().contains(".m3u8") &&
                !(hasDirect && prefQuality >= 2160)
            ) {
                launch { prefetchHlsFirstSegment(next.uri.toString(), prefQuality) }
            }
        }
    }

    // 上传：本地预览 + 真实上传到服务器（带进度）。由编辑器确认后回调触发。
    fun startUpload(sourceUri: Uri, title: String = "", description: String = "") {
        try {
            ctx.contentResolver.takePersistableUriPermission(sourceUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            // 部分来源不支持持久授权（如编辑器产出的 file:// 缓存），不影响上传
            ErrorReporter.debug("VideoPublish", "持久化读权限失败（一般无碍）: ${e.message}")
        }
        val localSource = SVSource(uri = sourceUri, isRemote = false, coverUrl = "")
        localPreviews.add(0, localSource)
        // 插到首位后立刻跟随过去，否则用户停在原页，看不到自己刚选的视频
        scope.launch { pagerState.scrollToPage(0) }
        uploading = true
        uploadProgress = 0f
        scope.launch {
            val vid = uploadShortVideo(ctx, sourceUri, title, description) { sent, total ->
                uploadProgress = if (total > 0) sent.toFloat() / total else 0f
            }
            uploading = false
            if (vid == null || vid <= 0) {
                // 失败就别留一个只有缩略图、永远播不了的死页面
                localPreviews.removeAll { it.identity == localSource.identity }
                ErrorReporter.error("VideoPublish", "上传返回无效 id=$vid（具体原因见上传环节日志）")
                Toast.makeText(ctx, "上传失败，请重试", Toast.LENGTH_SHORT).show()
                return@launch
            }
            // 转码可视化：轮询状态（0=转码中，1=已发布），最多等 3 分钟
            transcoding = true
            var ok = false
            var lastSt = -2
            for (i in 0 until 90) {
                delay(2000)
                val st = fetchShortVideoStatus(vid)
                if (st != lastSt) {
                    ErrorReporter.debug("VideoPublish", "转码状态轮询#${i + 1}: id=$vid status=$st (1=已发布 0=转码中 -1=不存在)")
                    lastSt = st
                }
                if (st == 1) { ok = true; break }
                if (st == -1) break
            }
            transcoding = false
            if (ok) {
                val items = fetchShortVideoFeed()?.filterNot { removedIds.contains(it.id) } ?: emptyList()
                // 关键：把当前页的"本地预览"就地替换成刚发布的远端自适应流，
                // 这样转码完成即刻在同一页自动播放，且不会出现"同一作品两条"。
                val newItem = items.firstOrNull { it.id == vid }
                val pi = localPreviews.indexOfFirst { it.uri == sourceUri }
                if (newItem != null && pi >= 0) {
                    val abs = if (newItem.playUrl.startsWith("http")) newItem.playUrl
                    else AuroraApi.serverUrl + newItem.playUrl
                    // 沿用原来的 identity：uri 换成远端流，但播放器状态不重置
                    localPreviews[pi] = SVSource(
                        uri = Uri.parse(abs), isRemote = true,
                        coverUrl = newItem.coverUrl, item = newItem,
                        identity = localPreviews[pi].identity
                    )
                    // 这一页已由 localPreviews 承载，feed 里不能再留同一条，否则会重复出现
                    rawFeed = items.filterNot { it.id == vid }
                } else {
                    rawFeed = items
                }
                rebuildFeed()
                feedError = false
            } else {
                // 转码超时/失败：这条本地页永远变不成可播的远端流，移除，别留个死页面
                localPreviews.removeAll { it.identity == localSource.identity }
                ErrorReporter.warn("VideoPublish", "轮询结束仍未发布: id=$vid ok=false（3 分钟超时或后端标记不存在）")
                Toast.makeText(ctx, "转码超时或失败，请稍后刷新", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 选择本地视频 → 不直接上传，先打开视频编辑器；确认后 startUpload(编辑后的 Uri)
    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        ErrorReporter.debug("VideoPublish", "选中本地视频: $uri 进入编辑器")
        editingVideoUri = uri
    }

    // 总开关已关闭：整页只显示开发者配置的那句话 —— 没有视频、没有列表、没有任何入口按钮。
    // 入口点击时不提示，必须进到这一页才显示（产品规则）。
    if (gateChecked && gateDisabled) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = gateMessage.ifBlank { "短视频功能已关闭" },
                color = Color.White.copy(alpha = 0.92f),
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp,
                modifier = Modifier.padding(horizontal = 40.dp)
            )
        }
        return
    }

    // 根容器：消费所有 pointer 事件（修掉"点击穿透到背后主界面"），黑底不透明
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black)
            .clickable(
                // 只有菜单打开时才需要"点空白关闭菜单"。常开等于给整页挂一个消费按下的手势节点，
                // 会跟 Pager 的翻页手势抢事件（从黑边起手时尤其明显）。
                enabled = menuOpen,
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { menuOpen = false }
    ) {
        val total = pages.size
        if (total == 0) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 首帧请求还没回来时不要抢着说"没有视频"，先转圈（避免"先空一下再加载"的错觉）
                if (feedLoading) {
                    CircularProgressIndicator(
                        color = Color.White.copy(alpha = 0.8f), strokeWidth = 2.dp,
                        modifier = Modifier.size(28.dp)
                    )
                } else {
                    Icon(Icons.Filled.SmartDisplay, contentDescription = null,
                        tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(16.dp))
                    Text("还没有短视频", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (feedError) "网络异常，稍后重新进入重试" else "从右上角 ⋮ 菜单上传第一个作品",
                        color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp
                    )
                }
            }
        } else {
            // key = 该页数据的稳定身份：删除/插入后 Pager 按 key 追页，不会把上一页的画面错配给新数据
            VerticalPager(
                state = pagerState,
                key = { idx -> pages.getOrNull(idx)?.identity ?: "empty:$idx" },
                // 菜单/设置/作品信息开着时锁住翻页：删除目标就是"你点开菜单时看的这一条"，
                // 同时防止在设置面板上拖动时把底下的视频滑走
                userScrollEnabled = !menuOpen && !deleting && !settingsOpen && !devInfoOpen,
                // 前后各多组合一页：封面和容器提前建好，手指滑过去时不再是"先黑一屏再加载"。
                // 只组合 UI、不创建解码器（播放器仍只在当前页建），内存开销很小。
                beyondViewportPageCount = 1,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val effective = pages.getOrNull(page) ?: return@VerticalPager
                val item = effective.item

                // 浮层（发布者信息 + 右侧动作栏）属于「这一页/这条视频」，随视频一起翻动，
                // 而不是浮在 Pager 之上的一层。放在 Box 内、播放器之后 → 绘制在视频画面之上。
                Box(Modifier.fillMaxSize()) {
                    ShortVideoPlayer(
                        source = effective,
                        isActive = pagerState.currentPage == page,
                        quality = prefQuality,
                        maxBitrate = prefMaxBitrate,
                        loopSingle = loopSingle,
                        autoNext = autoNext,
                        // 播完自动下滑到下一条（已经是最后一条就停住，不回绕）
                        onEnded = {
                            if (pagerState.currentPage < pages.lastIndex) {
                                scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                            }
                        },
                        // 设置面板 / 作品信息页盖在上面时暂停播放，避免"人在看参数、声音还在响"；
                        // 视频编辑器打开时同样暂停（否则上一个视频一直在播，上传容易超时）
                        // 任何「盖住短视频页」的情况都要停：设置面板、作品信息、编辑器、
                        // 以及被用户资料/帖子详情等全屏层压住（isForeground=false）。
                        suspendPlayback = settingsOpen || devInfoOpen || editingVideoUri != null || !isForeground,
                        onQualityChange = { q ->
                            prefQuality = q
                            ShortVideoPrefs.setQuality(ctx, q)
                        },
                        // 服务端已 404（文件被清掉）→ 立刻从队列剔除并重新拉取干净列表
                        onGone = { goneId ->
                            if (goneId > 0) {
                                dropFromQueue(goneId)
                                scope.launch { loadFeed() }
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    item?.let { svItem ->
                        ShortVideoOverlay(svItem)
                        ShortVideoActions(
                            item = svItem,
                            commentCount = commentCounts[svItem.id] ?: svItem.commentsCount,
                            onComment = { commentTarget = svItem },
                            onForward = { forwardTarget = svItem },
                            onOpenUserProfile = onOpenUserProfile
                        )
                    }
                }
            }
        }

        // 顶部栏：左上返回、右上「⋮」（不显示标题，让画面尽量满屏）
        Box(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().height(52.dp).align(Alignment.TopCenter)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(44.dp).clickable(
                        indication = null, interactionSource = remember { MutableInteractionSource() }
                    ) { onBack() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = Color.White, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier.size(44.dp).clickable(
                        indication = null, interactionSource = remember { MutableInteractionSource() }
                    ) {
                        if (menuOpen) closeMenu() else { menuTarget = currentItem; menuOpen = true }
                    },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.MoreVert, contentDescription = "更多",
                        tint = Color.White, modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        // 上传 / 转码状态浮层（不遮挡中心，贴在顶部栏下方）
        if (uploading || transcoding) {
            Column(
                modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)
                    .statusBarsPadding().padding(top = 56.dp, start = 16.dp, end = 16.dp)
            ) {
                Text(
                    if (uploading) "上传中 ${(uploadProgress * 100).toInt()}%" else "转码中…",
                    color = Color.White, fontSize = 13.sp
                )
                Spacer(Modifier.height(6.dp))
                if (uploading) {
                    LinearProgressIndicator(
                        progress = { uploadProgress.coerceIn(0f, 1f) },
                        color = Color(0xFF1E40AF),
                        trackColor = Color.White.copy(alpha = 0.25f),
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(
                        color = Color(0xFF1E40AF),
                        trackColor = Color.White.copy(alpha = 0.25f),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // 「⋮」菜单：贴住按钮的小卡片，点画面任意处收起（没有遮罩、没有侧滑面板）
        AnimatedVisibility(
            visible = menuOpen,
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding()
                .padding(top = 52.dp, end = 10.dp),
            enter = fadeIn(tween(120)) + scaleIn(
                initialScale = 0.92f, transformOrigin = TransformOrigin(1f, 0f), animationSpec = tween(120)
            ),
            exit = fadeOut(tween(90)) + scaleOut(
                targetScale = 0.96f, transformOrigin = TransformOrigin(1f, 0f), animationSpec = tween(90)
            )
        ) {
            Column(
                modifier = Modifier
                    .width(132.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(Color(0xF0161A20))
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(13.dp))
                    .padding(vertical = 3.dp)
            ) {
                SVMenuRow(Icons.Filled.Upload, "上传") {
                    closeMenu()
                    // 上传闸门（开发者管理已关闭上传）：入口直接提示，服务端同样 403
                    if (uploadDisabled) {
                        Toast.makeText(ctx, "短视频上传已关闭", Toast.LENGTH_SHORT).show()
                    } else if (isSvMuted) {
                        // 短视频禁言（或全部禁言）：直接拦截发布入口，明确告知剩余时间
                        Toast.makeText(ctx, "你已被短视频禁言，$svMuteRemainingText", Toast.LENGTH_SHORT).show()
                    } else {
                        pickLauncher.launch("video/*")
                    }
                }
                // 本地预处理入口：打开同源托管的处理页，视频在用户设备本地转码后再上传。
                // 登录令牌通过 URL 片段（#t=...）自动带入，网页自行取用。
                SVMenuRow(Icons.Filled.SmartDisplay, "本地预处理") {
                    closeMenu()
                    if (uploadDisabled) {
                        // 该页面最终也是打上传接口，关掉上传时没必要放进去白转码一遍
                        Toast.makeText(ctx, "短视频上传已关闭", Toast.LENGTH_SHORT).show()
                        return@SVMenuRow
                    }
                    val base = com.aurora.chat.data.api.AuroraApi.serverUrl.trimEnd('/') + "/tools/transcode"
                    val token = com.aurora.chat.data.api.AuroraApi.authToken.orEmpty()
                    val url = if (token.isNotEmpty()) "$base#t=${android.net.Uri.encode(token)}" else base
                    try {
                        ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                    } catch (e: Exception) {
                        Toast.makeText(ctx, "打开失败，请手动访问：$base", Toast.LENGTH_LONG).show()
                    }
                }
                SVMenuRow(Icons.Filled.Settings, "设置") {
                    closeMenu()
                    settingsOpen = true
                }
                // 分享：直接复用已封装的社区分享面板（SharePostDialog），
                // 与右侧动作栏「转发」同一套选人/发送逻辑，不在这里另写分享实现。
                val shareTarget = menuTarget
                SVMenuRow(Icons.Filled.Share, "分享", enabled = shareTarget != null) {
                    if (shareTarget != null) {
                        closeMenu()
                        forwardTarget = shareTarget
                    }
                }
                val infoTarget = menuTarget
                val delTarget = menuTarget
                // 删除权限：开发者可删任意视频；普通用户只能删自己发布的那条。
                // 非本人（且非开发者）的菜单里不出现「删除」，从入口上杜绝误删他人作品。
                val canDeleteTarget = delTarget != null &&
                    (isDeveloper || delTarget.publisher.id == AuroraApi.currentUserId)
                if (isDeveloper || canDeleteTarget) {
                    HorizontalDivider(
                        color = Color.White.copy(alpha = 0.07f),
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
                if (isDeveloper) {
                    SVMenuRow(
                        Icons.Filled.Info, "作品信息",
                        enabled = infoTarget != null
                    ) {
                        if (infoTarget != null) {
                            closeMenu()
                            devInfoItem = infoTarget
                            devInfoOpen = true
                        }
                    }
                }
                if (canDeleteTarget && delTarget != null) {
                    SVMenuRow(
                        Icons.Filled.Delete,
                        if (deleting) "删除中" else "删除",
                        danger = true,
                        enabled = !deleting
                    ) { doDelete(delTarget) }
                }
            }
        }

        // ── 设置面板（所有人可见）：画质 / 码率上限 / 播放模式 / 单条循环，改完立刻生效并永久保存 ──
        AnimatedVisibility(
            visible = settingsOpen,
            enter = fadeIn(tween(140)),
            exit = fadeOut(tween(120)),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier.fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { settingsOpen = false }
            ) {
                SVSettingsPanel(
                    quality = prefQuality,
                    maxBitrate = prefMaxBitrate,
                    mode = playMode,
                    autoNext = autoNext,
                    loopSingle = loopSingle,
                    onQuality = { q ->
                        prefQuality = q
                        ShortVideoPrefs.setQuality(ctx, q)
                    },
                    // 码率已并入画质三档，这里保留签名但不再使用
                    onMaxBitrate = {},
                    onMode = { m -> changePlayMode(m) },
                    // 自动下滑与单条循环是互斥的两种结束行为：开一个必然关掉另一个
                    onAutoNext = { on ->
                        autoNext = on
                        ShortVideoPrefs.setAutoNext(ctx, on)
                        if (on && loopSingle) {
                            loopSingle = false
                            ShortVideoPrefs.setLoopSingle(ctx, false)
                        }
                    },
                    onLoop = { on ->
                        loopSingle = on
                        ShortVideoPrefs.setLoopSingle(ctx, on)
                        if (on && autoNext) {
                            autoNext = false
                            ShortVideoPrefs.setAutoNext(ctx, false)
                        }
                    },
                    onReset = {
                        ShortVideoPrefs.reset(ctx)
                        prefQuality = ShortVideoPrefs.quality(ctx)
                        autoNext = ShortVideoPrefs.autoNext(ctx)
                        loopSingle = ShortVideoPrefs.loopSingle(ctx)
                        changePlayMode(ShortVideoPrefs.playMode(ctx))
                    },
                    onClose = { settingsOpen = false },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }

        // ── 开发者专属：作品信息（独立全屏页，从右侧滑入）──
        AnimatedVisibility(
            visible = devInfoOpen && devInfoItem != null,
            enter = slideInHorizontally(tween(260)) { it },
            exit = slideOutHorizontally(tween(220)) { it },
            modifier = Modifier.fillMaxSize()
        ) {
            val target = devInfoItem
            if (target != null) {
                SVDevInfoPage(
                    item = target,
                    quality = prefQuality,
                    maxBitrate = prefMaxBitrate,
                    mode = playMode,
                    onClose = { devInfoOpen = false }
                )
            }
        }

        // 评论面板（底部上滑）
        // 记住最后一次的作品：关闭动画播放期间 item 不能变空，否则面板会先"啪"地消失再播动画。
        var commentSheetItem by remember { mutableStateOf<SVItem?>(null) }
        LaunchedEffect(commentTarget) {
            if (commentTarget != null) commentSheetItem = commentTarget
        }
        commentSheetItem?.let { ct ->
            ShortVideoCommentSheet(
                item = ct,
                visible = commentTarget != null,
                isDeveloper = isDeveloper,
                isMuted = isSvMuted,
                muteRemainingText = svMuteRemainingText,
                onDismiss = { commentTarget = null },
                // 点头像进主页：先收起评论面板（它是个 Dialog 窗口，不收会被资料页压住/盖住资料页）
                onOpenUserProfile = { uid, name ->
                    commentTarget = null
                    onOpenUserProfile(uid, name)
                },
                // 只有「动态」跳转命中的那条视频才带定位/高亮参数
                initialHighlightText = if (ct.id == initialVideoId) initialCommentText else "",
                initialHighlightUserId = if (ct.id == initialVideoId) initialCommentUserId else 0L,
                onCommentAdded = {
                    // 评论成功：本地计数 +1，动作栏上的评论数立刻刷新（不用退出重进）
                    commentCounts[ct.id] = (commentCounts[ct.id] ?: ct.commentsCount) + 1
                },
                onCommentsDeleted = { n ->
                    // 删除成功（含级联回复）：本地计数同步减下来，不低于 0
                    val cur = commentCounts[ct.id] ?: ct.commentsCount
                    commentCounts[ct.id] = (cur - n).coerceAtLeast(0)
                }
            )
        }
        // 转发：直接复用社区的分享面板 SharePostDialog（选人 + 官方群限制 + 发送逻辑全在里面）。
        // 消息体走社区「分享卡片协议」（见 ShareCard.kt 顶部规约），不自建卡片、不自写发送。
        if (forwardTarget != null) {
            val fwd = forwardTarget!!
            // 视频地址取 orig.mp4 兜底：HLS(m3u8) 地址 MMR 取不了帧，封面缺失时用原文件取首帧
            val videoUrlForShare = fwd.playUrl.let { if (it.endsWith(".m3u8", true) || it.contains(".m3u8?")) "${it.substringBeforeLast("/")}/orig.mp4" else it }
            SharePostDialog(
                postTitle = fwd.title.ifEmpty { "短视频" },
                postContent = fwd.description.ifEmpty { fwd.title }.ifEmpty { "一条短视频" },
                postId = fwd.id,
                postUserId = fwd.publisher.id,
                postUsername = fwd.publisher.username.ifEmpty { "用户${fwd.publisher.id}" },
                titlePrefix = "分享短视频",
                idKey = "shortvideo_id",
                videoUrl = videoUrlForShare,
                coverUrl = fwd.coverUrl,
                publisherName = fwd.publisher.username.ifEmpty { "用户${fwd.publisher.id}" },
                onDismiss = { forwardTarget = null }
            )
        }

        // ── 发布信息（标题 / 简介）：编辑器确认后、真正上传前填 ──
        // 放在最外层（最后绘制），不会被评论/设置/作品信息面板压住。
        publishDraftUri?.let {
            SVPublishSheet(
                onDismiss = { publishDraftUri = null },
                onPublish = { t, d ->
                    val u = publishDraftUri
                    publishDraftUri = null
                    if (u != null) startUpload(u, t, d)
                }
            )
        }
    }

    // 返回键顺序：作品信息 → 设置 → 菜单（都关着才走系统默认，即退出本页）
    BackHandler(enabled = devInfoOpen) { devInfoOpen = false }
    BackHandler(enabled = settingsOpen && !devInfoOpen) { settingsOpen = false }

    // 视频编辑器（选中本地视频后覆盖全屏）：裁剪/缩放/滤镜，确认后再上传（编辑后的）视频
    editingVideoUri?.let { editUri ->
        // 预览叠加层直接复用短视频页的真实组件（头像/点赞/评论/收藏/左下用户名），
        // 用当前登录用户构造占位数据；纯展示不交互（enabled=false，且不吞触摸事件）
        val login = remember { LocalStorage.loadLoginState(ctx) }
        val previewItem = remember(login.userId, login.username) {
            SVItem(
                id = 0L,
                publisher = SVPublisher(login.userId, login.username.ifEmpty { "我" }, ""),
                title = "", description = "",
                coverUrl = "", playUrl = "", isHls = false, duration = 0,
                viewCount = 0, likesCount = 0, commentsCount = 0,
                hasLiked = false, createdAt = 0L
            )
        }
        VideoEditorScreen(
            inputUri = editUri,
            previewOverlay = {
                ShortVideoOverlay(previewItem)
                ShortVideoActions(
                    item = previewItem,
                    commentCount = 0,
                    onComment = {},
                    onForward = {},
                    onOpenUserProfile = { _, _ -> },
                    enabled = false
                )
            },
            onCancel = { editingVideoUri = null },
            onConfirm = { editedUri, title, desc ->
                editingVideoUri = null
                // 编辑器已预留 title/desc 两个参数（社区发帖那条链路在用），短视频这里
                // 以前直接丢掉、上传时也只发文件，所以作品信息永远显示"未填写"。
                ErrorReporter.debug("VideoPublish", "编辑器确认，进入发布信息: $editedUri title=$title desc=$desc")
                if (title.isNotBlank() || desc.isNotBlank()) {
                    startUpload(editedUri, title, desc)
                } else {
                    publishDraftUri = editedUri // 交给下面的「发布信息」面板补填
                }
            }
        )
    }
}

/** 「⋮」菜单里的一行（图标 + 文字）。danger 为红色危险项。 */
@Composable
private fun SVMenuRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    danger: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val tint = when {
        !enabled -> Color.White.copy(alpha = 0.3f)
        danger -> Color(0xFFF05252)
        else -> Color.White.copy(alpha = 0.92f)
    }
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(
                enabled = enabled,
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() }
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, color = tint, fontSize = 14.sp)
    }
}


/**
 * 单条短视频播放器：ExoPlayer + TextureView（自适应 HLS 多码率，按需加载分片）。
 * - 仅 isActive 时创建 ExoPlayer 并播放；非 active 释放（同一时刻最多 1 个解码器，根治卡顿）；
 * - 远程 HLS 走 OkHttpDataSource 复用项目缓存；本地 content:// 走 DefaultDataSource 本地源；
 * - 封面作加载/占位，视频就绪后覆盖；错误时显示失败提示。
 */
@Composable
private fun ShortVideoPlayer(
    source: SVSource, isActive: Boolean, modifier: Modifier = Modifier,
    /** 全局画质上限（-1 自动）与码率上限（0 不限），来自「设置」，永久保存 */
    quality: Int = -1,
    maxBitrate: Int = 0,
    /** 单条循环（关掉后播完停在最后一帧，点一下从头再放） */
    loopSingle: Boolean = true,
    /** 自动下滑：播完自动切下一条（由父级决定是否还有下一条） */
    autoNext: Boolean = false,
    onEnded: () -> Unit = {},
    /** 设置面板/信息页盖在上面时暂停，关闭后恢复 */
    suspendPlayback: Boolean = false,
    onQualityChange: (Int) -> Unit = {},
    onGone: (Long) -> Unit = {}
) {
    val ctx = LocalContext.current
    // 播放器 Listener / 轮询都是建一次就长期存活的闭包：这里用 updatedState 保证
    // 「设置中途改了自动下滑」「pages 变了导致 onEnded 语义变化」都能立刻反映，不会读到旧值。
    val onEndedLatest = rememberUpdatedState(onEnded)
    val autoNextLatest = rememberUpdatedState(autoNext)
    // 状态按 identity 记忆：同一作品换源（本地预览 → 远端流）时保留 TextureView 引用与画面比例
    var isLoading by remember(source.identity) { mutableStateOf(true) }
    var hasError by remember(source.identity) { mutableStateOf(false) }
    var errMsg by remember(source.identity) { mutableStateOf("") }
    var firstFrameReady by remember(source.identity) { mutableStateOf(false) }
    // 权威比例：后端转码时按源画面写入的 aspect_ratio（产物不补边，等于真实画面比例）。
    // 有它就从第一帧之前开始用正确比例排容器 —— 不再「默认 9:16 → 封面 → 首帧」三段猜测，
    // 也就不会出现「先竖后横」或「播放瞬间突然变小」的跳变。
    // 没有（老数据）才退回封面比例、再退 VideoSize，保证任何情况下都有合理值。
    val apiAspect = source.item?.aspectRatio ?: 0f
    val hasApiAspect = apiAspect.isFinite() && apiAspect > 0.05f && apiAspect < 20f
    // 容器(TextureView/封面)按此比例排布 → surface 尺寸 = 视频比例，SCALE_TO_FIT 恰好填满、绝不变形。
    var videoAspectRatio by remember(source.identity) {
        mutableStateOf(if (hasApiAspect) apiAspect else 9f / 16f)
    }

    // 封面地址（供封面层渲染）
    val coverAbs = source.coverUrl.let {
        when {
            it.isEmpty() -> ""
            it.startsWith("http") -> it
            else -> AuroraApi.serverUrl + it
        }
    }
    val coverPainter = rememberAsyncImagePainter(
        model = ImageRequest.Builder(LocalContext.current).data(coverAbs.takeIf { it.isNotEmpty() })
            .crossfade(false).build()
    )
    // 封面比例兜底：仅在「没有后端权威比例」的老数据上才用——封面即视频第一帧，
    // 后端已按旋转角转正、等比缩放，其宽高比≈视频真实比例，拿来兜底不会跳变。
    val coverRatio = (coverPainter.state as? AsyncImagePainter.State.Success)
        ?.result?.drawable
        ?.takeIf { it.intrinsicWidth > 0 && it.intrinsicHeight > 0 }
        ?.let { it.intrinsicWidth.toFloat() / it.intrinsicHeight.toFloat() }
    LaunchedEffect(coverRatio, firstFrameReady) {
        // 后端已给权威比例时，封面只作加载态画面，不再反向影响容器比例
        // （封面是独立缩放的 JPEG，宽高取偶后与视频可能有零点几个百分点的差，拿它盖掉权威值只会引入抖动）。
        if (!hasApiAspect && !firstFrameReady && coverRatio != null && coverRatio > 0.05f && coverRatio < 20f) {
            videoAspectRatio = coverRatio
        }
    }
    // 同一条视频的 aspect_ratio 从「无」变成「有」（存量重转完成后 feed 刷新）时立刻接管，
    // 不用等出帧再纠正 —— 否则用户会先看到一阵 9:16 的猜测比例。
    LaunchedEffect(hasApiAspect, apiAspect) {
        if (hasApiAspect) videoAspectRatio = apiAspect
    }
    // 原画直通：选「高」且后端提供了直通文件时，直接播零重编码的原始码流。
    // HLS 原画档是 CRF18 重编码 —— 视觉上接近，但纹理/边缘仍有代际损失；
    // 直通是唯一能做到"发布后与源完全一致"的路径，而且用的是源码率，通常还更省带宽。
    val directPlayUrl = source.item?.directUrl
        ?.takeIf { it.isNotBlank() }
        ?.let { if (it.startsWith("http")) it else AuroraApi.serverUrl + it }
    val preferDirect = quality >= 2160 && directPlayUrl != null
    val preferredUrl = if (preferDirect && directPlayUrl != null) directPlayUrl else source.uri.toString()

    // 实际播放地址：HLS 失败时可回退到同目录 orig.mp4（后端保证存在）。这几项跟 URL 走。
    var currentUrl by remember(source.uri, preferredUrl) { mutableStateOf(preferredUrl) }
    var triedDirectFallback by remember(source.uri) { mutableStateOf(false) }
    var triedFallback by remember(source.uri) { mutableStateOf(false) }
    var triedFallback2 by remember(source.uri) { mutableStateOf(false) }
    var retryTick by remember(source.uri) { mutableStateOf(0) }
    val playerRef = remember(source.identity) { mutableStateOf<ExoPlayer?>(null) }
    // TextureView 引用必须跨换源保留，否则新播放器 setVideoTextureView 拿到 null，只出声不出画
    val tvRef = remember(source.identity) { mutableStateOf<TextureView?>(null) }
    // 已绑定的播放器：用于在 AndroidView.update 里判断是否需要补绑
    val boundPlayerRef = remember(source.identity) { mutableStateOf<ExoPlayer?>(null) }
    // 播放控制状态：暂停 / 进度 / 清晰度
    var isPlaying by remember(source.identity) { mutableStateOf(false) }
    var positionMs by remember(source.identity) { mutableStateOf(0L) }
    var durationMs by remember(source.identity) { mutableStateOf(0L) }
    var qualityMenu by remember(source.identity) { mutableStateOf(false) }
    // 实际生效的档位（解码尺寸短边 / 选中轨道码率），用于验证设置真的生效，而不是只显示"我设了多少"
    var actualHeight by remember(source.identity) { mutableStateOf(0) }
    var actualBitrate by remember(source.identity) { mutableStateOf(0) }
    // 自动下滑只触发一次（轮询提前触发 + ENDED 兜底，避免重复切页）
    var autoAdvanced by remember(source.identity) { mutableStateOf(false) }

    // 本地上传预览（还没转成远端流）。这类源不交给 ExoPlayer：
    // content:// 在部分 ROM 上解码/授权不稳定，会抛 onPlayerError，
    // 结果就是"上传中"和"视频加载失败"同时出现在一屏上（其实上传是成功的）。
    val isLocalPending = !source.isRemote && source.item == null

    // 本地首帧缩略图：上传/转码期间用它占位，比全黑好看，也不涉及解码播放
    val localThumb by produceState<android.graphics.Bitmap?>(null, source.uri, isLocalPending) {
        if (!isLocalPending) return@produceState
        value = withContext(Dispatchers.IO) {
            var mmr: android.media.MediaMetadataRetriever? = null
            try {
                mmr = android.media.MediaMetadataRetriever()
                mmr.setDataSource(ctx, source.uri)
                mmr.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } catch (_: Exception) {
                null
            } finally {
                try { mmr?.release() } catch (_: Exception) {}
            }
        }
    }
    // 本地预览（上传中 / 转码中）没有后端比例可用，用首帧缩略图的宽高比排布容器。
    // 否则会退回默认 9:16 —— 横屏视频在"上传中"被硬塞进竖容器，看着又小又变形，
    // 等转码完成换成远端流时再跳一次比例。
    LaunchedEffect(localThumb, isLocalPending) {
        val t = localThumb
        if (isLocalPending && t != null && t.width > 0 && t.height > 0) {
            val r = t.width.toFloat() / t.height
            if (r.isFinite() && r > 0.05f && r < 20f) videoAspectRatio = r
        }
    }

    fun attachVideoOutput() {
        val p = playerRef.value ?: return
        val tv = tvRef.value ?: return
        if (boundPlayerRef.value === p) return // 同一个播放器已绑定，重复设置会打断渲染
        try {
            p.setVideoTextureView(tv)
            boundPlayerRef.value = p
        } catch (_: Exception) {}
    }

    fun releasePlayer() {
        playerRef.value?.let { p ->
            try { p.playWhenReady = false; p.stop() } catch (_: Exception) {}
            try { tvRef.value?.let { tv -> p.clearVideoTextureView(tv) } } catch (_: Exception) {}
            try { p.release() } catch (_: Exception) {}
        }
        playerRef.value = null
        boundPlayerRef.value = null
    }

    fun buildPlayer(): ExoPlayer {
        // 网络(HLS)走 OkHttp 复用缓存与预取命中；本地 content:// 走默认本地源
        val upstream = OkHttpDataSource.Factory(HttpClient.client).setUserAgent("AuroraChat-Android")
        val dsFactory = DefaultDataSource.Factory(ctx, upstream)
        val msFactory = DefaultMediaSourceFactory(dsFactory)
        val p = ExoPlayer.Builder(ctx).setMediaSourceFactory(msFactory).build()
        p.repeatMode = if (loopSingle) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        p.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
        try { p.setHandleAudioBecomingNoisy(true) } catch (_: Exception) {}
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                isLoading = state == Player.STATE_BUFFERING
                // 兜底：真播到结尾（有些机器上轮询的 250ms 窗口跨过了尾帧）也要能自动下滑
                if (state == Player.STATE_ENDED && autoNextLatest.value && !autoAdvanced) {
                    autoAdvanced = true
                    onEndedLatest.value()
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                val code = error.errorCodeName
                Log.e("ShortVideo", "播放错误 code=$code url=$currentUrl", error)
                // 本地预览（content://）：个别 ROM 上授权被回收或解码器不支持。
                // 这不是"视频坏了"，只是预览不可用——静默退回缩略图，不要弹加载失败。
                if (!source.isRemote) {
                    ErrorReporter.debug(
                        "VideoPublish",
                        "本地预览播放失败，退回首帧占位: $code ${error.message}"
                    )
                    hasError = false
                    isLoading = false
                    releasePlayer()
                    return
                }
                // 远端播放错误：写入短视频诊断日志（不弹提示），含视频ID与出错地址便于排查
                ErrorReporter.error("ShortVideo", "播放错误 #${source.item?.id ?: 0} code=$code url=$currentUrl msg=${error.message}")

                // HLS 播放失败 → 自动回退：优先同目录 compat.mp4（H.264/AAC 全设备可解），
                // compat 也没有或也失败时再退回 orig.mp4（渐进式）。既避免"只有封面"，
                // 也避免 HEVC/AV1 源的 orig.mp4 在无对应解码器的机器上仍播不了。
                val url = currentUrl
                // 直通文件失败（源文件本身损坏、或服务端 remux 产物异常）→ 回退到 HLS 主地址。
                // 极少数情况，但必须留这条路，否则用户会直接看到"加载失败"。
                if (preferDirect && !triedDirectFallback) {
                    triedDirectFallback = true
                    currentUrl = source.uri.toString()
                    Log.w("ShortVideo", "原画直通播放失败，回退 HLS: $currentUrl")
                    retryTick += 1
                    return
                }
                if (url.contains(".m3u8")) {
                    val base = url.substringBeforeLast("/")
                    if (!triedFallback) {
                        triedFallback = true
                        currentUrl = "$base/compat.mp4?v=" + System.currentTimeMillis()
                        Log.w("ShortVideo", "HLS 失败，回退兼容档: $currentUrl")
                        retryTick += 1
                        return
                    }
                    if (!triedFallback2) {
                        triedFallback2 = true
                        currentUrl = "$base/orig.mp4?v=" + System.currentTimeMillis()
                        Log.w("ShortVideo", "兼容档失败，回退原文件: $currentUrl")
                        retryTick += 1
                        return
                    }
                }
                // 服务端 404：这条视频的文件已经没了，别留一个能滑出来却永远播不了的空壳
                val httpCode = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                if (httpCode == 404) {
                    source.item?.let { onGone(it.id) }
                }
                // 错误文案人话化：Raw 异常（如 HEVC 源触发的 IndexOutOfBounds）只进 Log，
                // 屏幕上不出现技术堆栈——用户看不懂，只会觉得"平台烂了"。
                errMsg = if (httpCode == 404) "视频不存在或已被删除"
                          else "该视频格式暂不支持播放，可点击重试"
                hasError = true
                isLoading = false
            }
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
            override fun onRenderedFirstFrame() {
                // 真正画出第一帧才撤掉封面（这是"有没有画面"的唯一可靠信号）
                firstFrameReady = true
                isLoading = false
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    // 按「显示宽高比」算：竖拍视频常被编码成横屏像素尺寸 + 旋转元数据(rotation=90/270)，
                    // 不交换会被当成横屏 → 播放瞬间被算成宽屏、画面跳变。乘 pixelWidthHeightRatio 兼容变宽像素。
                    val rot = videoSize.unappliedRotationDegrees
                    val dispW = if (rot == 90 || rot == 270) videoSize.height else videoSize.width
                    val dispH = if (rot == 90 || rot == 270) videoSize.width else videoSize.height
                    val dispAspect = (dispW.toFloat() * videoSize.pixelWidthHeightRatio) / dispH
                    if (dispAspect.isFinite() && dispAspect > 0f) {
                        // 有权威比例时只做「异常校正」：偏差超过 3% 才覆盖。
                        // HLS 换档时各档整数取偶会差零点几个百分点，不该让容器跟着抖；
                        // 但真出了问题（片子被补了黑边、或源带特殊元数据）又能立刻纠正回来。
                        val api = source.item?.aspectRatio ?: 0f
                        val authoritative = api.isFinite() && api > 0.05f && api < 20f
                        val cur = videoAspectRatio
                        val deviated = !cur.isFinite() || cur <= 0.05f ||
                            kotlin.math.abs(dispAspect - cur) / cur > 0.03f
                        if (!authoritative || deviated) videoAspectRatio = dispAspect
                    }
                    // 实际生效的档位（"720P"里的 720）：取「解码短边」与「库里记录的源短边」的较小者。
                    // 不能只用 min(width,height) 判档：产物若被补成 16:9 画布，竖屏源的 240 档是 426x240，
                    // min 出来还是 240，但真实画面只有 136x240，档位标签会骗人。
                    val metaShort = minOf(source.item?.width ?: 0, source.item?.height ?: 0)
                    val decodedShort = minOf(videoSize.width, videoSize.height)
                    actualHeight = if (metaShort > 0) minOf(metaShort, decodedShort) else decodedShort
                }
            }
            override fun onTracksChanged(tracks: Tracks) {
                // 实际码率：取当前被选中的视频轨道 format.bitrate（HLS 里等于 EXT-X-STREAM-INF 的 BANDWIDTH）
                for (g in tracks.groups) {
                    if (g.type != C.TRACK_TYPE_VIDEO) continue
                    for (i in 0 until g.length) {
                        if (!g.isTrackSelected(i)) continue
                        val br = g.getTrackFormat(i).bitrate
                        if (br > 0) actualBitrate = br
                    }
                }
            }
        })
        p.setMediaItem(MediaItem.fromUri(currentUrl))
        p.prepare()
        p.playWhenReady = true
        return p
    }

    // 画质/码率：不重拉播放列表，只给 HLS 设上限（maxVideoSize + maxVideoBitrate）。
    // 这样弱网仍能自适应降到更低档，但绝不会超过选的那一档。
    //  - quality = -1 → 自动（跟随带宽自适应）
    //  - quality > 0   → 取该视频不超过该档的最高一档（1080 源选 720 就落在 720）
    //  - maxBitrate    → 再叠一层码率天花板（0 = 不限）
    //
    // 关键坑（之前"设了没效果"的真正原因）：
    // ExoPlayer 对 HLS 变体取的是 master playlist 里的 BANDWIDTH，它是「视频+音频+封装」的峰值，
    // 而我们库里存的 1800k 是纯视频码率。直接用 1800k 当上限，720 档（BANDWIDTH≈1.93M）会被判超限，
    // 结果 720 设置掉到 480、更低档甚至一档都选不上而回退到最高档 —— 看起来就是"画质设置没用"。
    // 所以上限必须留出音频与封装余量（1.5 倍 + 200k），再叠加用户选的码率天花板。
    fun applyPrefs() {
        val p = playerRef.value ?: return
        var capW = 7680
        var capH = 4320
        var capBr = 100_000_000
        val list = source.item?.renditions ?: emptyList()
        var lockToTop = false
        if (quality > 0 && list.isNotEmpty()) {
            val target = list.filter { it.quality <= quality }.maxByOrNull { it.quality }
                ?: list.minByOrNull { it.quality }
            // 宽高必须为正：setMaxVideoSize(0,0) 会过滤掉所有视频轨（0 不是"不限"），
            // 旧数据万一缺尺寸就会直接选不出任何一档。
            if (target != null && target.width > 0 && target.height > 0) {
                capW = target.width
                capH = target.height
                // bitrate=0 表示该档是 CRF 变码率（原画档），没有固定上限可参考，就不设限。
                // 以前这里无条件 *3/2+200k，遇到 0 会算出 200k，把整组轨道全滤掉。
                if (target.bitrate > 0) capBr = target.bitrate * 3 / 2 + 200_000
                lockToTop = quality >= 2160
            }
        }
        // 码率选项语义是"视频码率上限"，同样要留音频余量，否则 2 Mbps 连 720 档都留不住
        if (maxBitrate > 0) capBr = minOf(capBr, maxBitrate + 200_000)
        try {
            val builder = p.trackSelectionParameters.buildUpon()
                .setMaxVideoSize(capW, capH)
                .setMaxVideoBitrate(capBr)
            // 选「原画」时锁死最高档：弱网宁可多缓冲几秒，也不让播放器自动降到低清。
            // 这是"有时候明明设了最高画质还是糊"的直接原因——上限只是上限，
            // 带宽一抖 ExoPlayer 就会往下选档。
            if (lockToTop) {
                builder.setMinVideoSize(capW, capH)
                // 同分辨率、不同 CRF 的多档（如 1080p 源的「中」CRF20 与「高」CRF18 都输出 1080p）
                // 仅靠分辨率 min==max 锁不住，ExoPlayer 仍会在两者间按带宽自适应、可能落到更软的中档。
                // 强制在允许分辨率内选最高码率变体，彻底钉死最清晰档（源分辨率 CRF18 那一档）。
                builder.setForceHighestSupportedBitrate(true)
                ErrorReporter.debug("ShortVideo", "画质[高]：锁定源分辨率($capW x $capH)并强制最高码率变体")
            }
            p.trackSelectionParameters = builder.build()
        } catch (_: Exception) {}
    }

    fun togglePlayPause() {
        val p = playerRef.value ?: return
        // 单条循环关掉后播到末尾会停在 ENDED：此时再点应当是"从头重播"，而不是毫无反应
        if (p.playbackState == Player.STATE_ENDED) {
            p.seekTo(0)
            p.play()
            return
        }
        if (p.isPlaying) p.pause() else p.play()
    }

    fun seekToFraction(f: Float) {
        val p = playerRef.value ?: return
        val d = p.duration
        if (d > 0) {
            p.seekTo((d * f.coerceIn(0f, 1f)).toLong())
            positionMs = (d * f.coerceIn(0f, 1f)).toLong()
        }
    }

    // 进度轮询（250ms 一次，比每秒刷新更跟手，又不至于太耗电）
    LaunchedEffect(isActive, source.uri, retryTick) {
        while (isActive) {
            playerRef.value?.let { p ->
                positionMs = p.currentPosition.coerceAtLeast(0)
                durationMs = p.duration.coerceAtLeast(0)
                // 自动下滑：提前 250ms 就切，等 ENDED 再滑会先静止半秒才有动画，观感是"卡一下"
                if (autoNextLatest.value && !autoAdvanced &&
                    durationMs > 0 && positionMs > 0 && positionMs >= durationMs - 250
                ) {
                    autoAdvanced = true
                    onEndedLatest.value()
                }
            }
            delay(250)
        }
    }

    // 应用级可见性：退到桌面 / 锁屏 / 任务切换时必须停。
    // 短视频页在导航里不会被销毁（只是被压住），没有 onDispose 可依赖，
    // 不接生命周期就永远收不到"离开软件"这个信号 —— 声音会一直在后台响。
    // 声明位置要早于播放器创建：刚建好的播放器也要用它判断是否立刻暂停。
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    var appVisible by remember { mutableStateOf(true) }
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE,
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> appVisible = false
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> appVisible = true
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 单条循环开关要实时生效（不能等重建播放器）
    LaunchedEffect(loopSingle) {
        playerRef.value?.repeatMode =
            if (loopSingle) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    // 降级诊断去重：每条视频只记录一次降级事件
    val loggedDegrade = remember { mutableSetOf<Long>() }

    // 关键顺序：必须【先】把 player 赋给 playerRef，【再】绑定 TextureView。
    // 之前 attachVideoOutput() 写在 buildPlayer() 内部，那时 playerRef 还是 null 直接 return，
    // 导致播放器能 READY、进度圈一闪就没，但一帧都画不出来（只剩封面）。
    LaunchedEffect(isActive, source.uri, preferredUrl, retryTick, isLocalPending) {
        // 本地上传预览也建播放器：content:// 直接本地解码，不走网络、不等转码，
        // 上传中和转码中都能正常看、能拖进度条。
        // 个别 ROM 上授权/解码可能失败，由 onPlayerError 静默退回首帧缩略图，
        // 绝不在这块屏幕上误报"视频加载失败"（上传其实是成功的）。
        if (isActive) {
            releasePlayer()
            isLoading = true
            hasError = false
            errMsg = ""
            firstFrameReady = false
            // 重建播放器 = 重新看这一条，自动下滑的"已触发"标记必须清空，
            // 否则滑走再滑回来时这条播完不会继续切下一条
            autoAdvanced = false
            val p = buildPlayer()
            playerRef.value = p
            attachVideoOutput()
            applyPrefs()
            // 降级诊断：HLS 转码失败的视频会走兜底档（highq/compat/orig），
            // 表现就是「高画质也糊」。把降级原因写进诊断日志（设置→其他→短视频诊断），
            // 不弹任何提示。每条视频只记录一次。
            val di = source.item
            if (di != null && source.isRemote && !di.isHls) {
                val reason = di.degradeReason.ifEmpty { "渐进式降级(无多码率)" }
                if (loggedDegrade.add(di.id)) {
                    ErrorReporter.warn("ShortVideo", "降级播放 #${di.id}: $reason")
                }
            }
            // 建好就播是默认行为；但如果此刻页面被盖住或应用在后台，必须立刻压住，
            // 否则会有一小段声音漏出来（从资料页返回时尤其明显）。
            if (suspendPlayback || !appVisible) p.pause()
        } else {
            releasePlayer()
            isLoading = false
        }
    }

    // 设置里改了画质/码率：对正在播的这条立即生效，不用退出重进
    LaunchedEffect(quality, maxBitrate) { applyPrefs() }

    // 页面被盖住（设置 / 作品信息 / 用户资料 / 帖子详情）或应用退到后台 → 暂停；
    // 恢复可见再接着放（已经播完的保持停在最后一帧，等用户点重播）。
    LaunchedEffect(suspendPlayback, appVisible) {
        val p = playerRef.value ?: return@LaunchedEffect
        if (suspendPlayback || !appVisible) {
            p.pause()
        } else if (p.playbackState != Player.STATE_ENDED && p.playbackState != Player.STATE_IDLE) {
            p.play()
        }
    }

    // 只在真正离开组合时释放；换源由上面的 LaunchedEffect 处理，
    // 用 source.uri 做 key 会在换源时把刚建好的新播放器一起 release 掉。
    DisposableEffect(Unit) { onDispose { releasePlayer() } }

    BoxWithConstraints(
        // 不强制：黑底容器，内容（视频 / 封面）按自身比例自然居中显示，不裁切、不铺满。
        modifier.background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        // 关键修复：不再强制全屏 / 不再强制固定高度 / 不再拉伸。
        // 让 TextureView（及封面）按视频自身宽高比(aspectRatio)自然显示：Compose 会在全屏约束盒子里
        // 挑"能放下的最大等比例尺寸"摆放 → 该多大就多大、永远等比例、永远不拉伸、也不铺满。
        // 这正是聊天里那个正常播放器(ChatConversationScreen)采用的思路。

        // 图片层（本地首帧 / 封面）与视频同策略：按视频比例自然显示(Fit)，不裁切、不铺满
        val mediaImageScale = ContentScale.Fit
        // 注意：这里不能 return —— 本地预览也要让下面的 TextureView 参与渲染，
        // 缩略图只是垫在底下等首帧。以前在这里 return，导致上传中/转码中永远只有一张静态图。

        AndroidView(
            factory = { c ->
                TextureView(c).apply {
                    tvRef.value = this
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(s: android.graphics.SurfaceTexture, w: Int, h: Int) {
                            attachVideoOutput()
                        }
                        override fun onSurfaceTextureSizeChanged(s: android.graphics.SurfaceTexture, w: Int, h: Int) {}
                        override fun onSurfaceTextureDestroyed(s: android.graphics.SurfaceTexture): Boolean {
                            tvRef.value = null
                            boundPlayerRef.value = null // surface 没了，下次 available 必须重新绑定
                            return true
                        }
                        override fun onSurfaceTextureUpdated(s: android.graphics.SurfaceTexture) {}
                    }
                }
            },
            // factory 只在首次组合时执行；换源（本地预览 → 远端流）不会重建 AndroidView，
            // 必须在这里把 TextureView 引用写回并补绑播放器，否则新播放器只出声不出画。
            update = { tv ->
                if (tvRef.value !== tv) tvRef.value = tv
                attachVideoOutput()
            },
            // 按视频自身比例自然显示：aspectRatio 让容器 = 视频比例，SCALE_TO_FIT 恰好填满不变形。
            modifier = Modifier.aspectRatio(videoAspectRatio)
        )

        // 还没画出新源的第一帧时，用不透明底盖住 TextureView。
        // TextureView 会保留上一个源的最后一张画面：删除一条后新视频还在缓冲，
        // 屏幕上仍是刚删掉那条的定格，用户会以为没删掉、再点一次（于是把下一条也删了）。
        if (!firstFrameReady) {
            Box(Modifier.fillMaxSize().background(Color.Black))
        }

        // 首帧占位：出帧即撤。
        //  - 本地预览（上传中/转码中）：用刚取的首帧缩略图垫着，视频解码出画后自动让位；
        //  - 远端：用后端封面，比例与视频一致，切换不跳变。
        if (!firstFrameReady) {
            if (isLocalPending) {
                localThumb?.let { thumb ->
                    Image(
                        bitmap = thumb.asImageBitmap(), contentDescription = null,
                        contentScale = mediaImageScale,
                        modifier = Modifier.aspectRatio(videoAspectRatio)
                    )
                }
            } else if (coverAbs.isNotEmpty()) {
                Image(
                    painter = coverPainter,
                    contentDescription = null, contentScale = mediaImageScale,
                    modifier = Modifier.aspectRatio(videoAspectRatio)
                )
            }
        }

        if (isActive && isLoading && !hasError) CircularProgressIndicator(color = Color.White)
        if (hasError) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 24.dp)
            ) {
                Icon(Icons.Filled.ErrorOutline, contentDescription = null,
                    tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(8.dp))
                Text("视频加载失败", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                if (errMsg.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        errMsg, color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
                Spacer(Modifier.height(14.dp))
                Box(
                    modifier = Modifier.clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.16f))
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { retryTick += 1 }
                        .padding(horizontal = 22.dp, vertical = 9.dp)
                ) {
                    Text("重试", color = Color.White, fontSize = 14.sp)
                }
            }
        }

        // ── 播放控制层（仅当前页）──
        if (isActive && !hasError) {
            val renditions = source.item?.renditions ?: emptyList()

            // 单击画面：暂停 / 继续（放在控制条下层，按钮优先）。
            //
            // 【为什么不用 detectTapGestures —— 滑动手感不稳的根因】
            // detectTapGestures 内部第一件事就是 down.consume()，把按下事件从手势链里标成"已消费"。
            // 这一层是 fillMaxSize 的，铺满整屏，于是父级 VerticalPager 能不能开始拖动就变成了
            // 看"这一下有没有先落到这一层上"：落在视频区/黑边/浮层缝隙结果都不一样，
            // 表现就是"有时候怎么划都不翻，换个位置划又能翻"。
            // 这里改成自己实现：按下只观察、绝不消费；只有确认"按下后没移动就抬起"才当作点击。
            Box(
                modifier = Modifier.fillMaxSize().pointerInput(source.uri) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var moved = false
                        var tapped = false
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) {
                                tapped = !moved
                                break
                            }
                            if (!moved) {
                                val d = ch.position - down.position
                                if (abs(d.x) > viewConfiguration.touchSlop ||
                                    abs(d.y) > viewConfiguration.touchSlop
                                ) moved = true
                            }
                        }
                        if (tapped) {
                            qualityMenu = false
                            togglePlayPause()
                        }
                    }
                }
            )

            // 暂停时中央播放图标（淡入+放大出现 / 淡出+缩小消失，不再生硬跳变）
            AnimatedVisibility(
                visible = !isPlaying,
                modifier = Modifier.align(Alignment.Center),
                enter = fadeIn(tween(160)) + scaleIn(initialScale = 0.6f, animationSpec = tween(200)),
                exit = fadeOut(tween(140)) + scaleOut(targetScale = 0.6f, animationSpec = tween(160))
            ) {
                Box(
                    modifier = Modifier.size(64.dp).clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "播放",
                        tint = Color.White, modifier = Modifier.size(36.dp))
                }
            }

            // 底部：时间 + 可拖动进度条 + 清晰度
            Row(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .padding(start = 14.dp, end = 12.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(svTime(positionMs), color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp)
                Spacer(Modifier.width(10.dp))
                SVProgressBar(
                    positionMs = positionMs,
                    durationMs = durationMs,
                    onSeek = { f -> seekToFraction(f) },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(10.dp))
                Text(svTime(durationMs), color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp)
                if (renditions.isNotEmpty()) {
                    Spacer(Modifier.width(12.dp))
                    // 显示的是「实际正在播的档位」（由解码尺寸/选中轨道反推），而不是"设置里选了多少"。
                    // 这样设置到底有没有生效，一眼就能看出来。
                    val actualLabel = when {
                        actualHeight > 0 -> "${actualHeight}P"
                        else -> svQualityLabel(quality)
                    }
                    Box(
                        modifier = Modifier.clip(RoundedCornerShape(7.dp))
                            .background(Color.Black.copy(alpha = 0.45f))
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) { qualityMenu = !qualityMenu }
                            .padding(horizontal = 9.dp, vertical = 5.dp)
                    ) {
                        Text(
                            actualLabel, color = Color.White, fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // 清晰度选择：贴在底部条上方的轻量小卡片
            AnimatedVisibility(
                visible = qualityMenu,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 46.dp),
                enter = fadeIn(tween(110)) + scaleIn(
                    initialScale = 0.94f, transformOrigin = TransformOrigin(1f, 1f), animationSpec = tween(110)
                ),
                exit = fadeOut(tween(80)) + scaleOut(
                    targetScale = 0.96f, transformOrigin = TransformOrigin(1f, 1f), animationSpec = tween(80)
                )
            ) {
                Column(
                    modifier = Modifier.width(132.dp).clip(RoundedCornerShape(11.dp))
                        .background(Color(0xF0161A20))
                        .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(11.dp))
                        .padding(vertical = 3.dp)
                ) {
                    SVRenditionRow("自动", quality <= 0) { onQualityChange(-1); qualityMenu = false }
                    renditions.forEach { q ->
                        SVRenditionRow(svQualityLabel(q.quality), quality == q.quality) {
                            onQualityChange(q.quality); qualityMenu = false
                        }
                    }
                }
            }
        }
    }
}

/** 毫秒 → m:ss */
private fun svTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val s = ms / 1000
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

/** 可拖动进度条：按下即定位、拖动实时跟手、松手才真正 seek（避免拖动过程中疯狂起播）。 */
@Composable
private fun SVProgressBar(
    positionMs: Long, durationMs: Long,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var dragging by remember { mutableStateOf(false) }
    var dragFrac by remember { mutableStateOf(0f) }
    val autoFrac = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val frac = if (dragging) dragFrac else autoFrac

    Box(
        modifier = modifier.height(24.dp).pointerInput(durationMs) {
            awaitEachGesture {
                val down = awaitFirstDown()
                dragging = true
                dragFrac = (down.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                var verticalSteal = false
                while (true) {
                    val ch = awaitPointerEvent().changes.firstOrNull() ?: break
                    if (ch.pressed) {
                        val dx = ch.position.x - ch.previousPosition.x
                        val dy = ch.position.y - ch.previousPosition.y
                        // 一旦判定为竖向滑动（翻页意图）：立刻松手、不消费该事件，交给 Pager 翻页，
                        // 避免"在底部黑边/进度条一带上滑"被 seek 吞掉导致翻不动。
                        if (!verticalSteal && abs(dy) > abs(dx) && abs(dy) > 8f) {
                            verticalSteal = true
                            break
                        }
                        dragFrac = (ch.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                        ch.consume()
                    } else {
                        break
                    }
                }
                val wasSeek = dragging && !verticalSteal
                dragging = false
                if (wasSeek) onSeek(dragFrac)
            }
        }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cy = size.height / 2f
            val h = 3.dp.toPx()
            drawRoundRect(
                color = Color.White.copy(alpha = 0.28f),
                topLeft = Offset(0f, cy - h / 2), size = Size(size.width, h),
                cornerRadius = CornerRadius(h / 2)
            )
            if (frac > 0f) {
                drawRoundRect(
                    color = Color.White,
                    topLeft = Offset(0f, cy - h / 2), size = Size(size.width * frac, h),
                    cornerRadius = CornerRadius(h / 2)
                )
            }
            drawCircle(
                color = Color.White, radius = (if (dragging) 7.dp else 4.5.dp).toPx(),
                center = Offset(size.width * frac, cy)
            )
        }
    }
}

/** 清晰度菜单的一行。 */
@Composable
private fun SVRenditionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label, fontSize = 13.sp,
            color = if (selected) Color(0xFF7EA6FF) else Color.White.copy(alpha = 0.85f)
        )
    }
}

// ============================================================
// 设置面板（所有人可见）
// ============================================================
/**
 * 半屏设置面板：画质 / 码率上限 / 播放模式 / 单条循环。
 * 每一项点下去立刻写 SharedPreferences 并即时作用于正在播的视频，不存在「点保存」这一步。
 */
@Composable
private fun SVSettingsPanel(
    quality: Int,
    maxBitrate: Int,
    mode: SVPlayMode,
    autoNext: Boolean,
    loopSingle: Boolean,
    onQuality: (Int) -> Unit,
    onMaxBitrate: (Int) -> Unit,
    onMode: (SVPlayMode) -> Unit,
    onAutoNext: (Boolean) -> Unit,
    onLoop: (Boolean) -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 640.dp)
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            // 纯黑：之前是带蓝调的深灰（0xFF10141B），在低亮度下发闷、看着像脏褐色
            .background(Color.Black)
            // 吃掉面板内的点击，避免"点设置项把面板关了"
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) {}
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 16.dp)
    ) {
        Box(
            Modifier.align(Alignment.CenterHorizontally).width(38.dp).height(4.dp)
                .clip(CircleShape).background(Color.White.copy(alpha = 0.18f))
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("播放设置", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier.size(32.dp).clip(CircleShape)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { onClose() },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Close, contentDescription = "关闭",
                    tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(19.dp))
            }
        }

        // 码率已并入画质三档：选「高」就是原画 + 不限码率，不再需要单独一个码率上限。
        SVSectionTitle("画质")
        SVChoiceGrid(
            labels = svQualityOptions.map { svQualityLabel(it) },
            selected = svQualityOptions.indexOf(quality).let { if (it < 0) svQualityOptions.lastIndex else it },
            onPick = { i -> onQuality(svQualityOptions[i]) }
        )

        SVSectionTitle("播放模式")
        Column(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp))
                .background(Color.White.copy(alpha = 0.04f))
        ) {
            SVPlayMode.values().forEachIndexed { i, m ->
                if (i > 0) {
                    HorizontalDivider(
                        color = Color.White.copy(alpha = 0.06f),
                        modifier = Modifier.padding(start = 12.dp)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { onMode(m) }
                        .padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            m.label, fontSize = 14.sp,
                            color = if (mode == m) Color.White else Color.White.copy(alpha = 0.85f),
                            fontWeight = if (mode == m) FontWeight.Medium else FontWeight.Normal
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(m.desc, fontSize = 11.sp, color = Color.White.copy(alpha = 0.45f))
                    }
                    if (mode == m) {
                        Icon(Icons.Filled.Check, contentDescription = null,
                            tint = Color(0xFF7EA6FF), modifier = Modifier.size(18.dp))
                    }
                }
            }
        }

        SVSectionTitle("播放")
        Column(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp))
                .background(Color.White.copy(alpha = 0.04f))
        ) {
            SVSwitchRow(
                title = "自动下滑",
                desc = if (autoNext) "播完自动切到下一条" else "播完停在最后一帧",
                checked = autoNext,
                onCheckedChange = onAutoNext
            )
            HorizontalDivider(
                color = Color.White.copy(alpha = 0.06f),
                modifier = Modifier.padding(start = 12.dp)
            )
            SVSwitchRow(
                title = "单条循环",
                desc = if (loopSingle) "同一条反复播放" else "不重复本条",
                checked = loopSingle,
                onCheckedChange = onLoop
            )
        }
        Text(
            "两项互斥：都关掉就是播完停住",
            color = Color.White.copy(alpha = 0.32f), fontSize = 10.sp,
            modifier = Modifier.padding(start = 4.dp, top = 7.dp)
        )

        Spacer(Modifier.height(18.dp))
        Box(
            modifier = Modifier.fillMaxWidth().height(42.dp).clip(RoundedCornerShape(11.dp))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onReset() },
            contentAlignment = Alignment.Center
        ) {
            Text("恢复默认", color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp)
        }
    }
}

/** 设置面板里的开关行（标题 + 说明 + Switch）。 */
@Composable
private fun SVSwitchRow(
    title: String,
    desc: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onCheckedChange(!checked) }
            .padding(start = 12.dp, end = 8.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 14.sp)
            Spacer(Modifier.height(2.dp))
            Text(desc, color = Color.White.copy(alpha = 0.45f), fontSize = 11.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFF1E40AF),
                uncheckedThumbColor = Color.White.copy(alpha = 0.8f),
                uncheckedTrackColor = Color.White.copy(alpha = 0.16f),
                uncheckedBorderColor = Color.Transparent
            )
        )
    }
}

@Composable
private fun SVSectionTitle(text: String) {    Text(
        text, color = Color.White.copy(alpha = 0.45f), fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(top = 18.dp, bottom = 8.dp, start = 2.dp)
    )
}

/** 等宽选项网格（每行 3 个），选中项用品牌深蓝填充。 */
@Composable
private fun SVChoiceGrid(labels: List<String>, selected: Int, onPick: (Int) -> Unit) {
    val columns = 3
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.chunked(columns).forEachIndexed { rowIdx, row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEachIndexed { colIdx, label ->
                    val idx = rowIdx * columns + colIdx
                    val on = idx == selected
                    Box(
                        modifier = Modifier.weight(1f).height(38.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (on) Color(0xFF1E40AF) else Color.White.copy(alpha = 0.06f))
                            .border(
                                1.dp,
                                if (on) Color(0xFF3B6BE8) else Color.Transparent,
                                RoundedCornerShape(9.dp)
                            )
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) { onPick(idx) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label, fontSize = 13.sp,
                            color = if (on) Color.White else Color.White.copy(alpha = 0.72f),
                            fontWeight = if (on) FontWeight.Medium else FontWeight.Normal
                        )
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

// ============================================================
// 开发者专属：作品信息（全屏页）
// ============================================================
@Composable
private fun SVDevInfoPage(
    item: SVItem,
    quality: Int,
    maxBitrate: Int,
    mode: SVPlayMode,
    onClose: () -> Unit
) {
    val ctx = LocalContext.current
    var info by remember(item.id) { mutableStateOf<SVDevInfo?>(null) }
    var failed by remember(item.id) { mutableStateOf(false) }

    LaunchedEffect(item.id) {
        val d = fetchShortVideoInfo(item.id)
        if (d == null) failed = true else info = d
    }

    val appVer = remember {
        try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "—"
        } catch (_: Exception) {
            "—"
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().height(52.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(44.dp).clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onClose() },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回",
                    tint = Color.White, modifier = Modifier.size(24.dp))
            }
            Text("作品信息", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(
                "仅开发者可见", color = Color(0xFF7EA6FF), fontSize = 11.sp,
                modifier = Modifier.padding(end = 14.dp)
            )
        }

        val d = info
        if (d == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (failed) {
                    Text("信息加载失败", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                } else {
                    CircularProgressIndicator(
                        color = Color.White.copy(alpha = 0.8f), strokeWidth = 2.dp,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
            return@Column
        }

        Column(
            modifier = Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 14.dp)
                .padding(bottom = 24.dp)
        ) {
            SVInfoCard("作品") {
                SVInfoRow("视频 ID", "#${d.id}")
                SVInfoRow("标题", d.title.ifBlank { "未填写" })
                SVInfoRow("描述", d.description.ifBlank { "未填写" })
                SVInfoRow("时长", svDuration(d.duration))
                SVInfoRow("发布时间", svDateTime(d.createdAt))
                SVInfoRow("发布至今", svAgo(d.createdAt))
                SVInfoRow("状态", d.statusText, valueColor = Color(0xFF6EE7A8))
                SVInfoRow("播放形式", if (d.isHls) "HLS 多码率切片" else "渐进式 MP4（降级）")
                if (d.degradeReason.isNotEmpty()) {
                    SVInfoRow("降级原因", d.degradeReason, valueColor = Color(0xFFF59E0B))
                }
                SVInfoRow("码率档位", if (d.renditions.isEmpty()) "无" else "${d.renditions.size} 档")
            }

            SVInfoCard("发布者") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(
                        model = ImageRequest.Builder(ctx).data(
                            if (d.publisherAvatar.startsWith("http")) d.publisherAvatar
                            else AuroraApi.serverUrl + d.publisherAvatar
                        ).crossfade(false).build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(42.dp).clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.08f))
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                d.publisherName.ifBlank { "用户${d.publisherId}" },
                                color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold
                            )
                            if (d.publisherIsDev) {
                                Spacer(Modifier.width(6.dp))
                                Box(
                                    Modifier.clip(RoundedCornerShape(4.dp))
                                        .background(Color(0xFF1E40AF))
                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                ) {
                                    Text("开发者", color = Color.White, fontSize = 9.sp)
                                }
                            }
                        }
                        Text("用户 ID ${d.publisherId}",
                            color = Color.White.copy(alpha = 0.45f), fontSize = 11.sp)
                    }
                }
                Spacer(Modifier.height(10.dp))
                SVInfoRow("邮箱", d.publisherEmail.ifBlank { "—" })
                SVInfoRow("注册时间", svDateTime(d.publisherCreatedAt))
            }

            SVInfoCard("数据") {
                SVInfoRow("播放量", d.viewCount.toString())
                SVInfoRow("点赞", d.likesCount.toString())
                SVInfoRow("评论", d.commentsCount.toString())
                SVInfoRow("最近点赞", svDateTime(d.lastLikeAt))
                SVInfoRow("最近评论", svDateTime(d.lastCommentAt))
            }

            SVInfoCard("媒体文件") {
                SVInfoRow("原始文件", d.origName.ifBlank { "—" }, mono = true)
                SVInfoRow("原始大小", if (d.origExists) svBytes(d.origSize) else "文件已不存在",
                    valueColor = if (d.origExists) Color.White else Color(0xFFF05252))
                SVInfoRow("HLS 目录", d.hlsDir.ifBlank { "—" }, mono = true)
                SVInfoRow("目录占用", svBytes(d.hlsSize))
                SVInfoRow("文件 / 分片", "${d.hlsFiles} / ${d.hlsSegments}")
                SVInfoRow("转码服务", if (d.ffmpegOk) "ffmpeg 可用" else "ffmpeg 缺失（会降级）",
                    valueColor = if (d.ffmpegOk) Color(0xFF6EE7A8) else Color(0xFFF0B052))
            }

            SVInfoCard("码率档位") {
                if (d.renditions.isEmpty()) {
                    Text("没有多码率档位（渐进式播放）",
                        color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
                } else {
                    d.renditions.forEach { r ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${r.quality}P", color = Color.White, fontSize = 12.sp,
                                fontWeight = FontWeight.Medium, modifier = Modifier.width(48.dp)
                            )
                            Text(
                                "${r.width}×${r.height} · ${svBitrateText(r.bitrate)} · ${r.codec}",
                                color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp
                            )
                        }
                    }
                }
            }

            SVInfoCard("播放地址") {
                SelectionContainer {
                    Column {
                        Text(
                            d.playUrl.ifBlank { "—" }, color = Color(0xFF9DC1FF),
                            fontSize = 11.sp, fontFamily = FontFamily.Monospace
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            d.coverUrl.ifBlank { "—" }, color = Color.White.copy(alpha = 0.55f),
                            fontSize = 11.sp, fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            SVInfoCard("客户端（当前）") {
                SVInfoRow("设备", "${Build.MANUFACTURER} ${Build.MODEL}")
                SVInfoRow("系统", "Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）")
                SVInfoRow("App 版本", appVer)
                SVInfoRow("画质上限", if (quality > 0) "${quality}P" else "自动")
                SVInfoRow(
                    "码率上限",
                    svBitrateOptions.firstOrNull { it.bps == maxBitrate }?.label ?: "不限"
                )
                SVInfoRow("播放模式", mode.label)
            }
        }
    }
}

@Composable
private fun SVInfoCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            .clip(RoundedCornerShape(14.dp))
            // 中性深灰：纯黑背景上的卡片要有边界，但不能再带蓝/暖色调
            .background(Color(0xFF141414))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(
            title, color = Color(0xFF7EA6FF), fontSize = 11.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        content()
    }
}

@Composable
private fun SVInfoRow(
    label: String,
    value: String,
    mono: Boolean = false,
    valueColor: Color = Color.White
) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            label, color = Color.White.copy(alpha = 0.42f), fontSize = 12.sp,
            modifier = Modifier.width(78.dp)
        )
        Text(
            value, color = valueColor, fontSize = 12.sp,
            fontFamily = if (mono) FontFamily.Monospace else null,
            modifier = Modifier.weight(1f)
        )
    }
}

// ---------- 格式化工具 ----------
private fun svBytes(b: Long): String = when {
    b <= 0 -> "0 B"
    b < 1024 -> "$b B"
    b < 1024L * 1024 -> String.format(Locale.US, "%.1f KB", b / 1024.0)
    b < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", b / 1024.0 / 1024.0)
    else -> String.format(Locale.US, "%.2f GB", b / 1024.0 / 1024.0 / 1024.0)
}

private fun svBitrateText(bps: Int): String = when {
    bps <= 0 -> "—"
    bps < 1000 -> "$bps bps"
    bps < 1_000_000 -> String.format(Locale.US, "%.0f kbps", bps / 1000.0)
    else -> String.format(Locale.US, "%.2f Mbps", bps / 1_000_000.0)
}

private fun svDuration(sec: Int): String {
    if (sec <= 0) return "—"
    return "${sec / 60}:${(sec % 60).toString().padStart(2, '0')}"
}

/** 秒级时间戳 → 本地时间（后端存的是 Unix 秒） */
private fun svDateTime(ts: Long): String {
    if (ts <= 0) return "—"
    val ms = if (ts < 1_000_000_000_000L) ts * 1000 else ts
    return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(ms))
}

private fun svAgo(ts: Long): String {
    if (ts <= 0) return "—"
    val sec = if (ts > 1_000_000_000_000L) ts / 1000 else ts
    val diff = System.currentTimeMillis() / 1000 - sec
    if (diff < 0) return "刚刚"
    return when {
        diff < 60 -> "${diff} 秒前"
        diff < 3600 -> "${diff / 60} 分钟前"
        diff < 86400 -> "${diff / 3600} 小时前"
        diff < 86400 * 30 -> "${diff / 86400} 天前"
        else -> "${diff / 86400 / 30} 个月前"
    }
}

/** 从任意 Context 找宿主 Activity（拿 window 隐藏系统栏用）。 */
private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/**
 * 发布信息面板（标题 / 简介）：编辑器确认后、真正上传前填。
 *
 * 对应后端 shortvideo_videos.title / description。这两个字段以前前端从来没传过
 * （编辑器回调里塞的是空串、上传表单也没带），所以作品信息页永远显示"未填写"。
 */
@Composable
private fun SVPublishSheet(
    onDismiss: () -> Unit,
    onPublish: (title: String, description: String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))
            // 点遮罩关闭；面板内部另有一层吞点击，避免点输入框缝隙把面板关掉
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onDismiss() },
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .background(Color.Black)
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) {}
        ) {
            Text("发布作品", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            SVTextBox(
                value = title, onChange = { title = it.take(100) },
                hint = "标题（选填，最多 100 字）", maxLines = 1
            )
            Spacer(Modifier.height(10.dp))
            SVTextBox(
                value = desc, onChange = { desc = it.take(1000) },
                hint = "简介（选填，最多 1000 字）", maxLines = 4
            )
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                        .background(Color.White.copy(alpha = 0.08f))
                        .clickable { onDismiss() }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) { Text("取消", color = Color.White.copy(alpha = 0.82f), fontSize = 15.sp) }
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF1E40AF))
                        .clickable { onPublish(title.trim(), desc.trim()) }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) { Text("发布", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium) }
            }
        }
    }
}

/** 深色输入框（BasicTextField 自绘，与面板风格一致；空值时显示灰色提示语）。 */
@Composable
private fun SVTextBox(
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    maxLines: Int
) {
    Box(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        if (value.isEmpty()) {
            Text(hint, color = Color.White.copy(alpha = 0.35f), fontSize = 14.sp)
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
            cursorBrush = SolidColor(Color.White),
            maxLines = maxLines,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * 左下角信息浮层：发布者 + 标题 + 简介 + 播放量。
 * 点赞 / 评论 / 收藏 / 转发 已统一移到右侧竖排动作栏（ShortVideoActions）。
 */
@Composable
private fun ShortVideoOverlay(item: SVItem) {
    Column(
        modifier = Modifier.fillMaxSize().padding(start = 14.dp, bottom = 46.dp, end = 80.dp),
        verticalArrangement = Arrangement.Bottom
    ) {
        // 一律不加底衬：纯文字压在画面上。底衬在浅色画面里会变成一块灰斑，很难看。
        // 可读性靠内容本身（白字 + 轻微阴影由系统字体渲染保证）。
        Text("@${item.publisher.username.ifEmpty { "用户${item.publisher.id}" }}",
            color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        // 标题（发布时填写，名字下方单独一行）
        if (item.title.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(item.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        if (item.description.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(item.description, color = Color.White.copy(alpha = 0.92f), fontSize = 13.sp,
                maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(10.dp))
        // 播放量（点赞 / 评论 / 收藏 / 转发见右侧动作栏）
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Visibility, contentDescription = "播放量", tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(item.viewCount.toString(), color = Color.White, fontSize = 13.sp)
        }
    }
}

/**
 * 右侧竖排动作栏（从上到下）：发布者头像 → 点赞 → 评论 → 收藏 → 转发。
 *
 * - 头像：发布者圆形头像（UserAvatar，按发布者 id 取服务端头像）。
 * - 点赞：POST /api/shortvideo/like/toggle，本地即时回显（与后端聚合字段对应）。
 * - 评论：打开评论面板（ShortVideoCommentSheet），后端 GET/POST 评论已接入。
 * - 收藏：本机收藏（后端暂无收藏接口，先用本地存储；FavoritesScreen 读取展示）。
 * - 转发：直接复用社区分享面板 [SharePostDialog]（不另写选人/发送），发出的是一条
 *   「分享短视频」卡片消息（分享卡片协议见 ShareCard.kt），对方收到卡片，
 *   点开直接切入 ShortVideoScreen 定位到该视频（带完整动作栏），而非视频文件播放。
 */
/**
 * 关注状态内存缓存：滑回同一条视频不重复查接口；点关注后乐观写入，请求失败再回滚。
 * 用 internal 是为了让资料页（UserProfileCard）在取消关注后能同步清掉，
 * 否则从资料页返回短视频，加号还停在"已关注"的隐藏状态。
 */
internal val svFollowState = mutableStateMapOf<Long, Boolean>()

/**
 * 头像下方的关注按钮（红色小胶囊 +）。
 *
 * 单独抽成 Composable 有两个原因：
 *  1. 退场动画需要 AnimatedVisibility；写在 Box 里会被解析成 BoxScope 的扩展，align 失效；
 *  2. 关注成功后按钮不是「啪」地消失，而是缩小 + 淡出（scaleOut + fadeOut），观感更自然。
 */
@Composable
private fun SVFollowBadge(
    visible: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(160)) + scaleIn(tween(160), initialScale = 0.5f),
        exit = fadeOut(tween(220)) + scaleOut(tween(220), targetScale = 0.4f)
    ) {
        Box(
            modifier = Modifier
                .size(width = 26.dp, height = 15.dp)
                .clip(RoundedCornerShape(50))
                .background(Color(0xFFEF4444))
                .clickable(
                    enabled = enabled,
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                "+", color = Color.White, fontSize = 11.sp,
                fontWeight = FontWeight.Bold, lineHeight = 11.sp
            )
        }
    }
}

@Composable
private fun ShortVideoActions(
    item: SVItem,
    commentCount: Int,
    onComment: () -> Unit,
    onForward: () -> Unit,
    onOpenUserProfile: (Long, String) -> Unit,
    enabled: Boolean = true
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var liked by remember(item.id) { mutableStateOf(item.hasLiked) }
    var likeCount by remember(item.id) { mutableStateOf(item.likesCount) }
    var favorited by remember(item.id) { mutableStateOf(SVFavoriteStore.isFav(ctx, item.id)) }

    // 关注状态：优先读缓存（滑回同一条不必再查），没缓存才请求一次。
    // 自己的视频不查、也不显示加号。
    val publisherId = item.publisher.id
    val isSelf = publisherId == AuroraApi.currentUserId
    // 直接读缓存（snapshot state）：资料页那边取消关注后，这里会自动跟着变，
    // 用本地 var 做快照的话，返回视频时加号还停在隐藏状态。
    val following = svFollowState[publisherId] ?: false
    LaunchedEffect(publisherId) {
        if (publisherId <= 0 || isSelf) return@LaunchedEffect
        if (svFollowState.containsKey(publisherId)) return@LaunchedEffect
        val s = withContext(Dispatchers.IO) { AuroraApi.getFollowStats(publisherId) }
        if (s != null) svFollowState[publisherId] = s.isFollowing
    }

    fun onFollow() {
        if (isSelf || svFollowState[publisherId] == true) return
        // 乐观更新：先让按钮退场（有动画），失败再回滚让它重新出现
        svFollowState[publisherId] = true
        scope.launch {
            val s = withContext(Dispatchers.IO) { AuroraApi.followUser(publisherId) }
            if (s == null) {
                svFollowState[publisherId] = false
                Toast.makeText(ctx, "关注失败，请稍后重试", Toast.LENGTH_SHORT).show()
            } else {
                svFollowState[publisherId] = s.isFollowing
            }
        }
    }

    fun onLike() {
        val willLike = !liked
        liked = willLike
        likeCount += if (willLike) 1 else -1
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val raw = HttpClient.jsonRequest(
                        "POST", "${AuroraApi.serverUrl}/api/shortvideo/like/toggle",
                        JSONObject().put("id", item.id).toString(), AuroraApi.authToken
                    )
                    val o = JSONObject(raw)
                    liked = o.optBoolean("liked", liked)
                    likeCount = o.optInt("likes_count", likeCount)
                } catch (_: Exception) { /* 失败回退由下次进入刷新 */ }
            }
        }
    }

    fun onFav() {
        // 静默切换收藏：不弹「已收藏/已取消」提示
        favorited = SVFavoriteStore.toggle(
            ctx,
            SVFav(item.id, item.title, item.publisher.id, item.publisher.username)
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(end = 12.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.Bottom,
        horizontalAlignment = Alignment.End
    ) {
        // 1) 发布者头像（圆形，点击进入该用户资料；右移 8dp 使中心与下方 30dp 图标对齐）
        Box(
            modifier = Modifier
                .offset(x = 8.dp)
                .size(46.dp)
        ) {
            Box(
                modifier = Modifier.fillMaxSize()
                    .clip(CircleShape)
                    .border(1.5.dp, Color.White, CircleShape)
                    .clickable(
                        enabled = enabled,
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { onOpenUserProfile(item.publisher.id, item.publisher.username) }
            ) {
                UserAvatar(
                    userId = item.publisher.id,
                    userName = item.publisher.username,
                    size = 46.dp,
                    modifier = Modifier.fillMaxSize()
                )
            }
            // 头像下缘的红色小胶囊「+」：关注入口。
            // 用 align+offset 叠在头像底部，不占额外布局高度；已关注时带缩放淡出动画消失。
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                SVFollowBadge(
                    visible = !following && !isSelf,
                    enabled = enabled,
                    onClick = { onFollow() }
                )
            }
        }
        Spacer(Modifier.height(26.dp))
        // 2) 点赞（红色；点击：图标轻回弹 + 红色爱心迸发）
        SVActionButton(
            onClick = { onLike() },
            label = likeCount.toString(),
            popIcon = Icons.Filled.Favorite,
            popTint = Color(0xFFEF4444),
            enabled = enabled
        ) {
            Icon(
                if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = null,
                tint = if (liked) Color(0xFFEF4444) else Color.White,
                modifier = Modifier.size(30.dp)
            )
        }
        Spacer(Modifier.height(22.dp))
        // 3) 评论（计数用 commentCount，评论成功后即时刷新）
        SVActionButton(onClick = { onComment() }, label = commentCount.toString()) {
            Icon(Icons.Filled.Comment, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(22.dp))
        // 4) 收藏（自绘柔和圆角五角星，已收藏偏金黄；无提示；点击：轻回弹 + 金色星迸发）
        SVActionButton(
            onClick = { onFav() },
            label = "",
            popIcon = Icons.Filled.Star,
            popTint = Color(0xFFFACC15),
            enabled = enabled
        ) {
            SoftStarIcon(tint = if (favorited) Color(0xFFFACC15) else Color.White, filled = favorited, size = 30.dp)
        }
        Spacer(Modifier.height(22.dp))
        // 5) 转发
        SVActionButton(onClick = { onForward() }, label = "", enabled = enabled) {
            Icon(Icons.Filled.Share, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
        }
    }
}

/**
 * 右侧动作栏的单个按钮：图标插槽 + 下方小字计数（无底色，仿抖音竖排风格）。
 * 图标插槽让收藏可以用自绘的柔和圆角五角星（SoftStarIcon）。
 * popIcon 非空时，点击附带克制的「膨胀」反馈：图标本体 0.85→1.08→1 轻回弹，
 * 中心迸发一颗同色大图标并飞散 5 颗小图标（400ms 内结束）。
 */
@Composable
private fun SVActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "",
    popIcon: ImageVector? = null,
    popTint: Color = Color(0xFFEF4444),
    enabled: Boolean = true,
    icon: @Composable () -> Unit
) {
    val scope = rememberCoroutineScope()
    val iconScale = remember { Animatable(1f) }
    val burst = remember { Animatable(0f) }
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier.size(30.dp).clickable(
                enabled = enabled,
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) {
                onClick()
                scope.launch {
                    iconScale.snapTo(0.85f)
                    iconScale.animateTo(1.08f, tween(110))
                    iconScale.animateTo(1f, tween(140))
                }
                if (popIcon != null) {
                    scope.launch {
                        burst.snapTo(0f)
                        burst.animateTo(1f, tween(400))
                    }
                }
            },
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.scale(iconScale.value)) { icon() }
            if (popIcon != null && burst.value > 0.001f) {
                val p = burst.value
                // 中心迸发的大图标：0.5 → 1.05 后淡出
                Icon(
                    popIcon, contentDescription = null, tint = popTint,
                    modifier = Modifier.size(40.dp)
                        .scale((0.5f + p * 0.55f).coerceAtMost(1.05f))
                        .alpha((1f - p).coerceIn(0f, 1f))
                )
                // 飞散 5 颗小图标（偏上扇形）
                repeat(5) { i ->
                    val angle = PI * (0.15 + 0.7 * i / 4)
                    val dist = p * 42f
                    val tx = (cos(angle) * dist).toFloat()
                    val ty = -(abs(sin(angle)) * dist) - p * 10f
                    Icon(
                        popIcon, contentDescription = null, tint = popTint,
                        modifier = Modifier.size(11.dp)
                            .offset(x = tx.dp, y = ty.dp)
                            .scale((0.75f - p * 0.4f).coerceIn(0.1f, 1f))
                            .alpha((1f - p).coerceIn(0f, 1f))
                    )
                }
            }
        }
        if (label.isNotEmpty()) {
            Spacer(Modifier.height(3.dp))
            Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/**
 * 自绘柔和圆角五角星：顶点用二次贝塞尔圆角，避免系统 Star 图标那种尖锐感。
 * filled=true 实心（已收藏），false 描边（未收藏）。
 */
@Composable
private fun SoftStarIcon(tint: Color, filled: Boolean, size: Dp = 30.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val cx = w / 2f
        val cy = h / 2f
        val rOuter = min(w, h) / 2f * 0.94f
        val rInner = rOuter * 0.46f
        val n = 5
        val pts = ArrayList<Pair<Float, Float>>(n * 2)
        for (i in 0 until n) {
            val ao = Math.toRadians(-90.0 + i * 72.0)
            pts.add(Pair(cx + (rOuter * cos(ao)).toFloat(), cy + (rOuter * sin(ao)).toFloat()))
            val ai = Math.toRadians(-90.0 + i * 72.0 + 36.0)
            pts.add(Pair(cx + (rInner * cos(ai)).toFloat(), cy + (rInner * sin(ai)).toFloat()))
        }
        fun mid(a: Pair<Float, Float>, b: Pair<Float, Float>) =
            Pair((a.first + b.first) / 2f, (a.second + b.second) / 2f)
        val count = pts.size
        val path = Path()
        path.moveTo(mid(pts[count - 1], pts[0]).first, mid(pts[count - 1], pts[0]).second)
        for (i in 0 until count) {
            val v = pts[i]
            val mOut = mid(v, pts[(i + 1) % count])
            path.quadraticBezierTo(v.first, v.second, mOut.first, mOut.second)
        }
        path.close()
        if (filled) {
            drawPath(path, color = tint)
        } else {
            drawPath(
                path, color = tint,
                style = Stroke(width = w * 0.075f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
    }
}

/**
 * 评论面板（底部上滑）：拉取评论列表（GET /api/shortvideo/comments）+ 发布评论/回复（POST /api/shortvideo/comment）。
 * - 回复：点任意评论（或其"回复"按钮）→ 输入框进入"回复 @xx"态，parent_id 随评论上送，对方在「动态」收到"回复了你的评论"；
 * - 点赞：每条评论行内点赞按钮，toggle 接入 /api/shortvideo/comment/like，对方在「动态」收到"点赞了你的评论"；
 * - 删除：长按自己的评论（或开发者/平台管理员长按任意评论）→ 确认删除，后端级联删除其下所有回复，不留幽灵数据；
 * - 禁言：被「短视频禁言/全部禁言」时显示禁言横幅，输入框只读、发送禁用（后端同样会拦截）；
 * - 面板固定高度（约 62% 屏高）：评论多少都是固定距离，输入框恒定在最底部；
 *   键盘弹起时 imePadding 把输入框顶到键盘之上，不用手动拖面板。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShortVideoCommentSheet(
    item: SVItem,
    /** 显示/隐藏：改为自绘面板后，进出场动画要在组件内部驱动（遮罩淡入淡出、面板上下滑动） */
    visible: Boolean = true,
    isDeveloper: Boolean = false,
    isMuted: Boolean = false,
    muteRemainingText: String = "",
    onDismiss: () -> Unit,
    /** 点头像/昵称 → 进个人资料（与社区评论区一致；回复只能点「回复」两个字） */
    onOpenUserProfile: (Long, String) -> Unit = { _, _ -> },
    /** 从「动态」点进来时要定位并闪烁高亮的评论（内容 + 作者匹配，服务端动态不带评论 id） */
    initialHighlightText: String = "",
    initialHighlightUserId: Long = 0L,
    onCommentAdded: () -> Unit = {},
    /** 删除了 n 条评论（含级联回复）：父级据此把动作栏上的评论计数同步减下来 */
    onCommentsDeleted: (Int) -> Unit = {}
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var comments by remember { mutableStateOf<List<SVComment>?>(null) }
    // 手动展开的顶层评论 id：默认收起，只露前 2 条回复，超出显示「展开 N 条回复」。
    var expandedRoots by remember { mutableStateOf(setOf<Long>()) }
    var input by remember { mutableStateOf("") }
    var posting by remember { mutableStateOf(false) }
    // 回复目标（null = 发表顶层评论）
    var replyingTo by remember { mutableStateOf<SVComment?>(null) }
    // 平台管理员身份（可删他人评论）：进面板查一次
    var isPlatformAdmin by remember { mutableStateOf(false) }
    // 待确认删除的评论（长按/点"删除"触发）
    var pendingDelete by remember { mutableStateOf<SVComment?>(null) }

    // 面板高度（占屏高比例）：上下都能拖。
    // 不用 ModalBottomSheet —— 它只有「半展开 / 全展开」两个固定锚点，展开到顶后再往上拖毫无反应，
    // 往下拖只能收起，就是用户反馈的"能往下拖、不能往上拖"。这里按比例直接控制高度。
    var dragFraction by remember { mutableStateOf(0.62f) }
    val listState = rememberLazyListState()
    // 「动态」定位命中的评论 id 与闪烁动画（闪两下就停，不常亮也不无限闪）
    var highlightId by remember { mutableStateOf(0L) }
    val flashAlpha = remember { Animatable(0f) }

    // 加载失败单独标记：null（加载中）→ 列表；null 结果 → 失败态，可点击重试。
    // 以前失败会退化成空列表，看起来就是"评论全没了、数字变 0"。
    var loadFailed by remember { mutableStateOf(false) }
    var reloadTick by remember { mutableStateOf(0) }
    LaunchedEffect(visible, reloadTick) {
        if (!visible) return@LaunchedEffect
        loadFailed = false
        val r = withContext(Dispatchers.IO) { fetchShortVideoComments(item.id) }
        comments = r ?: emptyList()
        loadFailed = r == null
        isPlatformAdmin = runCatching { AuroraApi.isPlatformAdmin().data == true }.getOrDefault(false)
    }

    // 点赞 / 置顶：顶层评论和回复共用同一套逻辑，避免两处复制粘贴走样
    fun toggleLike(target: SVComment) {
        scope.launch {
            val r = withContext(Dispatchers.IO) { likeShortVideoComment(target.id) }
            if (r != null) {
                comments = comments?.map {
                    if (it.id == target.id) it.copy(liked = r.first, likeCount = r.second) else it
                }
            } else {
                Toast.makeText(ctx, "操作失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun togglePin(target: SVComment) {
        val wantPin = !target.isPinned
        scope.launch {
            val ok = withContext(Dispatchers.IO) { pinShortVideoComment(target.id, wantPin) }
            if (ok) {
                // 重新拉列表：后端按「置顶优先 + 时间正序」重排，本地手工挪容易和后端不一致
                withContext(Dispatchers.IO) { fetchShortVideoComments(item.id) }?.let { comments = it }
            } else {
                Toast.makeText(ctx, "操作失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 顶层评论（结构与社区评论区一致：LazyColumn 里每条顶层评论是一个 item，
    // 回复挂在它自己的 item 内部展开/收起，而不是拍平成独立 item）。
    // 父评论不在列表里的"孤儿回复"当顶层显示：宁可层级不对，也不能丢内容。
    val topLevelComments = remember(comments) {
        val list = comments.orEmpty()
        val ids = list.mapTo(HashSet()) { it.id }
        list.filter { it.parentId == 0L || it.parentId !in ids }
    }
    // 根评论 id → 其下所有回复（保持后端顺序）
    val repliesByRoot = remember(comments) {
        val list = comments.orEmpty()
        if (list.isEmpty()) emptyMap() else {
            val byId = list.associateBy { it.id }
            fun rootIdOf(id: Long): Long {
                var curId = id
                var guard = 0
                while (guard < 50) {
                    val c = byId[curId] ?: break
                    if (c.parentId <= 0L) break
                    curId = c.parentId
                    guard++
                }
                return curId
            }
            list.filter { it.parentId > 0L && byId[it.parentId] != null }
                .groupBy { rootIdOf(it.id) }
        }
    }

    // 「动态」跳转第一步：在完整评论里定位目标；如果它被折叠在某个顶层下面，先展开那一串
    LaunchedEffect(comments) {
        if (initialHighlightText.isBlank() || comments.isNullOrEmpty()) return@LaunchedEffect
        val text = initialHighlightText.trim()
        val list = comments!!
        val target = list.firstOrNull {
            it.content.trim() == text && (initialHighlightUserId <= 0L || it.userId == initialHighlightUserId)
        } ?: list.firstOrNull { it.content.trim() == text } ?: return@LaunchedEffect
        highlightId = target.id
        if (target.parentId > 0L) {
            var cur = target.parentId
            var guard = 0
            while (guard < 50) {
                val p = list.firstOrNull { it.id == cur } ?: break
                if (p.parentId <= 0L) {
                    expandedRoots = expandedRoots + p.id
                    break
                }
                cur = p.parentId
                guard++
            }
        }
    }

    // 第二步：滚到目标所在的顶层评论（回复挂在顶层 item 内部，滚父级就会一起露出来）并闪两下
    LaunchedEffect(topLevelComments) {
        val id = highlightId
        if (id <= 0L) return@LaunchedEffect
        val byId = comments.orEmpty().associateBy { it.id }
        var rootId = id
        var guard = 0
        while (guard < 50) {
            val c = byId[rootId] ?: break
            if (c.parentId <= 0L) break
            rootId = c.parentId
            guard++
        }
        val idx = topLevelComments.indexOfFirst { it.id == rootId }
        if (idx < 0) return@LaunchedEffect
        listState.scrollToItem(idx)
        repeat(2) {
            flashAlpha.animateTo(1f, tween(240))
            flashAlpha.animateTo(0f, tween(340))
        }
        highlightId = 0L
    }

    // 返回键关闭（原来由 ModalBottomSheet 自带，自绘后要自己接）
    BackHandler { onDismiss() }
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    Box(
        // 键盘弹起时整个面板上移，高度保持不变（不会被压扁）
        Modifier.fillMaxSize().offset { IntOffset(0, -imeBottomPx) },
        contentAlignment = Alignment.BottomCenter
    ) {
            // 遮罩：淡入淡出 + 点空白关闭
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(200))
            ) {
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f))
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { onDismiss() }
                )
            }
            // 面板：从底部滑入 / 滑出（与原来的 ModalBottomSheet 观感一致）
            AnimatedVisibility(
                visible = visible,
                enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(300)),
                exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(300)),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
            Column(
                Modifier.fillMaxWidth()
                    .fillMaxHeight(dragFraction)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .background(com.aurora.chat.ui.theme.appSurface())
                    // 吞掉面板内的点击，别穿透到遮罩上把自己关掉
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) {}
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp)
            ) {
                // 拖拽条：上下都能拖，高度实时跟手（0.25 ~ 0.92 屏高）
                Box(
                    Modifier.fillMaxWidth().padding(top = 6.dp).height(20.dp)
                        .pointerInput(Unit) {
                            detectVerticalDragGestures { _, dragAmount ->
                                // dragAmount < 0（往上拖）→ 变大；> 0（往下拖）→ 变小
                                dragFraction = (dragFraction - dragAmount / 3000f)
                                    .coerceIn(0.25f, 0.92f)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier.width(40.dp).height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color(0xFFD1D5DB).copy(alpha = 0.5f))
                    )
                }
                // 标题 + 关闭（对齐社区评论）
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "评论 (${comments?.size ?: 0})",
                    fontSize = 16.sp, fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary()
                )
                Text(
                    "关闭", fontSize = 14.sp, color = Color(0xFF1E40AF),
                    modifier = Modifier.clickable { onDismiss() }
                )
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appDivider()))

            // 评论列表（固定高度面板内 weight 占满：可上滑看更多、下拉回顶部）
            val list = comments
            Box(
                Modifier.fillMaxWidth().weight(1f)
            ) {
                when {
                    list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 3.dp, color = Color(0xFF1E40AF))
                    }
                    loadFailed && list.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "加载失败，点击重试",
                            fontSize = 13.sp, color = Color(0xFF1E40AF),
                            modifier = Modifier
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() }
                                ) { reloadTick++ }
                                .padding(12.dp)
                        )
                    }
                    list.isEmpty() && item.description.isBlank() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("还没有评论，来抢沙发", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                    }
                    else -> {
                        LazyColumn(
                            Modifier.fillMaxSize().padding(horizontal = 16.dp),
                            state = listState
                        ) {
                            // 作品描述固定第一条：它是作品内容的一部分而不是评论，
                            // 所以不参与排序、没有点赞/回复、也没有删除入口（作者也删不掉）。
                            // 置顶评论永远排在它下面，不会跑到内容上方。
                            if (item.description.isNotBlank()) {
                                item(key = "works-description") { SVWorkDescriptionRow(item) }
                            }
                            items(topLevelComments, key = { it.id }) { c ->
                                val replies = repliesByRoot[c.id].orEmpty()
                                val expanded = c.id in expandedRoots
                                SVCommentRow(
                                    c = c,
                                    isReply = false,
                                    repliedName = "",
                                    canDelete = c.userId == AuroraApi.currentUserId || isDeveloper || isPlatformAdmin,
                                    // 置顶权：开发者 / 平台管理员 / 视频发布者
                                    canPin = isDeveloper || isPlatformAdmin ||
                                        item.publisher.id == AuroraApi.currentUserId,
                                    flashAlpha = if (c.id == highlightId) flashAlpha.value else 0f,
                                    onAvatarClick = { onOpenUserProfile(c.userId, c.username) },
                                    onReply = { replyingTo = c },
                                    onLike = { toggleLike(it) },
                                    onPin = { togglePin(it) },
                                    onLongPress = { pendingDelete = c }
                                )
                                // 回复只在展开时渲染，收起态一条都不露（与社区评论区一致）。
                                SVReplyThread(
                                    visible = expanded,
                                    replies = replies,
                                    rootUsername = c.username,
                                    highlightId = highlightId,
                                    flashAlpha = flashAlpha.value,
                                    canDeleteFor = { r ->
                                        r.userId == AuroraApi.currentUserId || isDeveloper || isPlatformAdmin
                                    },
                                    onAvatarClick = { r -> onOpenUserProfile(r.userId, r.username) },
                                    onReply = { r -> replyingTo = r },
                                    onLike = { r -> toggleLike(r) },
                                    onLongPress = { r -> pendingDelete = r },
                                    onCollapse = { expandedRoots = expandedRoots - c.id }
                                )
                                if (!expanded && replies.isNotEmpty()) {
                                    SVReplyToggleRow(expanded = false, count = replies.size) {
                                        expandedRoots = expandedRoots + c.id
                                    }
                                }
                                Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appDivider().copy(alpha = 0.4f)))
                            }
                            // 只有作品描述、还没有人评论时，给一句提示，避免看起来像"列表坏了"
                            if (topLevelComments.isEmpty()) {
                                item(key = "empty-tip") {
                                    Text(
                                        "还没有评论，来抢沙发",
                                        fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary(),
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appDivider()))

            // 回复态提示条（点了某条评论的"回复"后出现，可一键取消）
            val replyTarget = replyingTo
            if (replyTarget != null) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "回复 @${replyTarget.username}",
                        fontSize = 12.sp, color = Color(0xFF1E40AF),
                        modifier = Modifier.weight(1f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "取消",
                        fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextHint(),
                        modifier = Modifier
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) { replyingTo = null }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            // 禁言横幅（短视频禁言/全部禁言）：与社区评论区一致
            if (isMuted) {
                Box(
                    modifier = Modifier.fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, top = 6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFFFEF3C7))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "你已被短视频禁言，$muteRemainingText",
                        fontSize = 13.sp, color = Color(0xFF92400E), fontWeight = FontWeight.Medium
                    )
                }
            }

            // 输入框（圆角浅灰底，对齐社区）
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.weight(1f)
                        .clip(RoundedCornerShape(20.dp))
                        .background(com.aurora.chat.ui.theme.appCardBg())
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    BasicTextField(
                        value = input,
                        onValueChange = { if (!isMuted) input = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        readOnly = isMuted,
                        cursorBrush = SolidColor(if (isMuted) Color.Transparent else Color(0xFF1E40AF)),
                        textStyle = TextStyle(fontSize = 14.sp, color = if (isMuted) com.aurora.chat.ui.theme.appTextHint() else com.aurora.chat.ui.theme.appTextSecondary()),
                        decorationBox = { inner ->
                            // 与社区一致：裸 Box 包裹（不用 fillMaxSize，否则会撑满弹窗剩余高度变成一大块）
                            Box(contentAlignment = Alignment.CenterStart) {
                                val placeholder = when {
                                    isMuted -> "你已被禁言"
                                    replyTarget != null -> "回复 @${replyTarget.username}:"
                                    else -> "说点什么…"
                                }
                                if (input.isEmpty()) Text(placeholder, fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint())
                                inner()
                            }
                        }
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    if (isMuted) "禁言中" else "发送",
                    fontSize = 14.sp,
                    color = if (isMuted || posting || input.isBlank()) com.aurora.chat.ui.theme.appTextHint() else Color(0xFF1E40AF),
                    modifier = Modifier
                        .clickable(
                            enabled = !isMuted && !posting && input.isNotBlank(),
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) {
                            val text = input.trim()
                            val parentId = replyingTo?.id ?: 0L
                            posting = true
                            scope.launch {
                                val err = withContext(Dispatchers.IO) { postShortVideoComment(item.id, text, parentId) }
                                posting = false
                                if (err == null) {
                                    input = ""
                                    replyingTo = null
                                    // 刷新失败就保留原列表（不能因为一次请求失败把刚发的评论"弄丢"）
                                    withContext(Dispatchers.IO) { fetchShortVideoComments(item.id) }
                                        ?.let { comments = it }
                                    // 回复成功后把所属线程展开：否则自己刚发的回复会被折叠藏起来，
                                    // 观感就是"回复完就消失了"（社区评论区也是这么处理的）。
                                    if (parentId > 0L) {
                                        val list = comments.orEmpty()
                                        var cur = parentId
                                        var guard = 0
                                        while (guard < 50) {
                                            val p = list.firstOrNull { it.id == cur } ?: break
                                            if (p.parentId <= 0L) {
                                                expandedRoots = expandedRoots + p.id
                                                break
                                            }
                                            cur = p.parentId
                                            guard++
                                        }
                                    }
                                    onCommentAdded()
                                } else {
                                    Toast.makeText(ctx, err, Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                )
            }
        }
            }   // AnimatedVisibility（面板滑动层）
    }

    // 删除确认（自己/管理员）：后端会级联删除该评论下的所有回复
    val delTarget = pendingDelete
    if (delTarget != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除评论", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = { Text("确定删除这条评论吗？删除后其下的所有回复也会一并删除。", fontSize = 14.sp) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    val delId = delTarget.id
                    scope.launch {
                        val n = withContext(Dispatchers.IO) { deleteShortVideoComment(delId) }
                        if (n != null) {
                            withContext(Dispatchers.IO) { fetchShortVideoComments(item.id) }
                                ?.let { comments = it }
                            if (n > 0) onCommentsDeleted(n)
                            Toast.makeText(ctx, if (n > 1) "已删除 $n 条评论" else "已删除", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(ctx, "删除失败", Toast.LENGTH_SHORT).show()
                        }
                    }
                }) { Text("删除", color = Color(0xFFDC2626)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
}

/**
 * 评论区第一条：作品描述（简介）。
 *
 * 它不是评论，所以：
 *  - 不参与排序、不受置顶影响 —— 永远压在置顶评论之上，不可能被挤到下面；
 *  - 没有点赞 / 回复 / 删除入口，作者也删不掉（要改只能重新发布作品）；
 *  - 用浅灰底和普通评论区分开，一眼能看出"这是作品本身的一部分"。
 */
@Composable
private fun SVWorkDescriptionRow(item: SVItem) {
    Column(
        Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(com.aurora.chat.ui.theme.appCardBg())
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(22.dp).clip(RoundedCornerShape(6.dp))) {
                UserAvatar(
                    userId = item.publisher.id,
                    userName = item.publisher.username,
                    size = 22.dp,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                item.publisher.username.ifEmpty { "作者" },
                fontSize = 13.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextPrimary()
            )
            Spacer(Modifier.width(5.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(3.dp))
                    .background(com.aurora.chat.ui.theme.appTextSecondary().copy(alpha = 0.12f))
                    .padding(horizontal = 5.dp, vertical = 1.dp)
            ) {
                Text("作品描述", fontSize = 10.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(item.description, fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
    }
}

/**
 * 某条顶层评论下的回复线程：展开时有高度渐变动画，展开后底部带「收起」。
 *
 * 单独抽成 Composable 而不是直接写在 LazyColumn 的 item 里：那一层仍能看到外层 Column 的
 * 隐式 receiver，`AnimatedVisibility` 会被解析成 ColumnScope 的扩展函数而报作用域错误。
 */
@Composable
private fun SVReplyThread(
    visible: Boolean,
    replies: List<SVComment>,
    rootUsername: String,
    highlightId: Long,
    flashAlpha: Float,
    canDeleteFor: (SVComment) -> Boolean,
    onAvatarClick: (SVComment) -> Unit,
    onReply: (SVComment) -> Unit,
    onLike: (SVComment) -> Unit,
    onLongPress: (SVComment) -> Unit,
    onCollapse: () -> Unit
) {
    AnimatedVisibility(visible = visible) {
        Column {
            replies.forEach { r ->
                SVCommentRow(
                    c = r,
                    isReply = true,
                    repliedName = replies.firstOrNull { it.id == r.parentId }?.username ?: rootUsername,
                    canDelete = canDeleteFor(r),
                    canPin = false,
                    flashAlpha = if (r.id == highlightId) flashAlpha else 0f,
                    onAvatarClick = { onAvatarClick(r) },
                    onReply = { onReply(r) },
                    onLike = { onLike(r) },
                    onLongPress = { onLongPress(r) }
                )
            }
            SVReplyToggleRow(expanded = true, count = replies.size, onClick = onCollapse)
        }
    }
}

/**
 * 某串回复的「展开 N 条回复 / 收起」行。
 * 与回复同级缩进（start 28dp），视觉上属于上一层的评论串，不会和顶层评论混淆。
 */
@Composable
private fun SVReplyToggleRow(expanded: Boolean, count: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() }
            .padding(start = 28.dp, top = 2.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (expanded) "收起" else "展开 $count 条回复",
            fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1E40AF)
        )
        Spacer(Modifier.width(3.dp))
        Text(
            if (expanded) "▴" else "▾",
            fontSize = 10.sp, color = Color(0xFF1E40AF)
        )
    }
}

/**
 * 单条评论（对齐社区 CommentRow 视觉）：圆角方头像 + 用户名/时间 + 内容 + 操作行（点赞/回复/删除）。
 * - isReply=true 时为回复：缩进 + "回复 @xx" 前缀；
 * - 点头像/昵称 = 进个人资料；点「回复」两个字 = 回复；长按 = 删除（无权时由调用方 Toast 提示）；
 * - flashAlpha > 0 时整行铺一层淡黄底，用于「动态」跳转后的两次闪烁高亮。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SVCommentRow(
    c: SVComment,
    isReply: Boolean = false,
    repliedName: String = "",
    canDelete: Boolean = false,
    /** 是否显示「置顶 / 取消置顶」：仅发布者、开发者、平台管理员，且只对顶层评论生效 */
    canPin: Boolean = false,
    flashAlpha: Float = 0f,
    onAvatarClick: () -> Unit = {},
    onReply: (SVComment) -> Unit = {},
    onLike: (SVComment) -> Unit = {},
    onPin: (SVComment) -> Unit = {},
    onLongPress: (SVComment) -> Unit = {}
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFFFFE9A3).copy(alpha = flashAlpha.coerceIn(0f, 1f)))
            .padding(start = if (isReply) 28.dp else 0.dp, top = 10.dp, bottom = 4.dp)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                // 整行点击不再默认回复（用户明确要求：只有点「回复」两个字才回复，点头像进主页）
                onClick = {},
                onLongClick = { onLongPress(c) }
            ),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            Modifier.size(if (isReply) 22.dp else 28.dp)
                .clip(RoundedCornerShape(6.dp))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onAvatarClick() }
        ) {
            UserAvatar(
                userId = c.userId, userName = c.username,
                size = if (isReply) 22.dp else 28.dp, modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    c.username, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextPrimary(),
                    modifier = Modifier.clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { onAvatarClick() }
                )
                if (isReply && repliedName.isNotEmpty()) {
                    Text(" 回复 ", fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextHint())
                    Text(repliedName, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1E40AF))
                }
                // 置顶标识：浅蓝底小胶囊，克制不抢眼，也不会让人误以为是系统公告
                if (c.isPinned) {
                    Spacer(Modifier.width(5.dp))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color(0xFF1E40AF).copy(alpha = 0.10f))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            "置顶", fontSize = 10.sp, fontWeight = FontWeight.Medium,
                            color = Color(0xFF1E40AF)
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(svAgo(c.createdAt), fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextHint())
            }
            Spacer(Modifier.height(4.dp))
            Text(c.content, fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
            Spacer(Modifier.height(2.dp))
            // 操作行：点赞（toggle）/ 回复 / 删除（仅有权时显示）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { onLike(c) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.ThumbUp,
                        contentDescription = "点赞",
                        tint = if (c.liked) Color(0xFF1E40AF) else com.aurora.chat.ui.theme.appTextHint(),
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        if (c.likeCount > 0) "${c.likeCount}" else "点赞",
                        fontSize = 11.sp,
                        color = if (c.liked) Color(0xFF1E40AF) else com.aurora.chat.ui.theme.appTextHint()
                    )
                }
                Spacer(Modifier.width(14.dp))
                Text(
                    "回复",
                    fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextHint(),
                    modifier = Modifier
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { onReply(c) }
                        .padding(vertical = 4.dp)
                )
                if (canPin) {
                    Spacer(Modifier.width(14.dp))
                    Text(
                        if (c.isPinned) "取消置顶" else "置顶",
                        fontSize = 11.sp,
                        color = if (c.isPinned) com.aurora.chat.ui.theme.appTextSecondary() else Color(0xFF1E40AF),
                        modifier = Modifier
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) { onPin(c) }
                            .padding(vertical = 4.dp)
                    )
                }
                if (canDelete) {
                    Spacer(Modifier.width(14.dp))
                    Text(
                        "删除",
                        fontSize = 11.sp, color = Color(0xFFEF4444),
                        modifier = Modifier
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) { onLongPress(c) }
                            .padding(vertical = 4.dp)
                    )
                }
            }
        }
    }
}

// ---------- 本地收藏（后端暂无收藏接口，先存本机；FavoritesScreen 读取展示） ----------
data class SVFav(val id: Long, val title: String, val publisherId: Long, val publisherName: String)

object SVFavoriteStore {
    private const val FILE = "short_video_fav"
    private const val K = "favs"
    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isFav(ctx: Context, id: Long): Boolean = all(ctx).any { it.id == id }

    /** 切换收藏状态，返回切换后的「是否已收藏」。 */
    fun toggle(ctx: Context, fav: SVFav): Boolean {
        val list = all(ctx).toMutableList()
        val existed = list.any { it.id == fav.id }
        if (existed) list.removeAll { it.id == fav.id } else list.add(0, fav)
        val arr = org.json.JSONArray()
        list.forEach { f ->
            arr.put(
                org.json.JSONObject()
                    .put("id", f.id).put("title", f.title)
                    .put("publisherId", f.publisherId).put("publisherName", f.publisherName)
            )
        }
        sp(ctx).edit().putString(K, arr.toString()).apply()
        return !existed
    }

    /** 从收藏中移除（收藏页长按取消）。 */
    fun remove(ctx: Context, id: Long) {
        val list = all(ctx).filterNot { it.id == id }
        val arr = org.json.JSONArray()
        list.forEach { f ->
            arr.put(
                org.json.JSONObject()
                    .put("id", f.id).put("title", f.title)
                    .put("publisherId", f.publisherId).put("publisherName", f.publisherName)
            )
        }
        sp(ctx).edit().putString(K, arr.toString()).apply()
    }

    fun all(ctx: Context): List<SVFav> {
        val raw = sp(ctx).getString(K, "") ?: return emptyList()
        if (raw.isEmpty()) return emptyList()
        val arr = org.json.JSONArray(raw)
        val out = mutableListOf<SVFav>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                SVFav(
                    o.optLong("id"), o.optString("title"),
                    o.optLong("publisherId"), o.optString("publisherName")
                )
            )
        }
        return out
    }
}

// ---------- 评论（后端已接入） ----------
data class SVComment(
    val id: Long,
    val userId: Long,
    val username: String,
    val content: String,
    val createdAt: Long,
    /** 被回复的评论 id（0=顶层评论） */
    val parentId: Long = 0,
    /** 被点赞数 */
    val likeCount: Int = 0,
    /** 当前用户是否已赞 */
    val liked: Boolean = false,
    /** 是否被发布者/开发者置顶（同一个视频最多一条） */
    val isPinned: Boolean = false
)

/**
 * 拉评论列表。**返回 null 表示请求失败**，空列表表示确实没有评论。
 *
 * 这个区分很重要：以前失败也返回 emptyList，界面就显示"评论 (0)"、"还没有评论"，
 * 于是刚发出去的评论看起来"消失了"、数字变 0，其实只是这一次请求没成功。
 * 调用方拿到 null 时应该保留旧列表，而不是把界面清空。
 */
suspend fun fetchShortVideoComments(id: Long): List<SVComment>? = withContext(Dispatchers.IO) {
    try {
        val raw = HttpClient.jsonRequest(
            "GET", "${AuroraApi.serverUrl}/api/shortvideo/comments?id=$id", null, AuroraApi.authToken
        )
        val obj = JSONObject(raw)
        if (obj.optInt("code", 0) != 200) {
            ErrorReporter.error(
                "ShortVideo",
                "评论列表加载失败 id=$id code=${obj.optInt("code")} msg=${obj.optString("message")}"
            )
            return@withContext null
        }
        val arr = obj.optJSONObject("data")?.optJSONArray("comments") ?: return@withContext emptyList()
        val out = mutableListOf<SVComment>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                SVComment(
                    o.optLong("id"), o.optLong("user_id"), o.optString("username"),
                    o.optString("content"), o.optLong("created_at"),
                    parentId = o.optLong("parent_id", 0),
                    likeCount = o.optInt("like_count", 0),
                    liked = o.optBoolean("liked", false),
                    isPinned = o.optBoolean("is_pinned", false)
                )
            )
        }
        out
    } catch (e: Exception) {
        ErrorReporter.error("ShortVideo", "评论列表请求异常 id=$id: ${e.message}", e)
        null
    }
}

/** 发布评论 / 回复（parentId>0 时为回复某条评论）。返回 null=成功，否则为失败原因（如「你已被短视频禁言」） */
suspend fun postShortVideoComment(id: Long, content: String, parentId: Long = 0): String? = withContext(Dispatchers.IO) {
    try {
        val raw = HttpClient.jsonRequest(
            "POST", "${AuroraApi.serverUrl}/api/shortvideo/comment",
            JSONObject().put("id", id).put("content", content).put("parent_id", parentId).toString(), AuroraApi.authToken
        )
        val obj = JSONObject(raw)
        if (obj.optInt("code", 0) == 200) null
        else obj.optString("message", "").ifBlank { "发送失败" }
    } catch (e: HttpClient.HttpStatusException) {
        // 403 等：从响应体里取服务端原因（如禁言拦截）
        runCatching { JSONObject(e.body).optString("message", "") }.getOrNull()?.ifBlank { null } ?: "发送失败"
    } catch (_: Exception) {
        "发送失败"
    }
}

/**
 * 置顶 / 取消置顶评论（只有发布者、开发者、平台管理员能调）。
 * 返回 true=成功；false=失败（无权限/评论不存在/只允许顶层评论）。
 */
suspend fun pinShortVideoComment(commentId: Long, pinned: Boolean): Boolean = withContext(Dispatchers.IO) {
    try {
        val raw = HttpClient.jsonRequest(
            "POST", "${AuroraApi.serverUrl}/api/shortvideo/comment/pin",
            JSONObject().put("comment_id", commentId).put("pinned", pinned).toString(), AuroraApi.authToken
        )
        JSONObject(raw).optInt("code", 0) == 200
    } catch (_: Exception) {
        false
    }
}

/** 点赞 / 取消点赞评论（toggle）。返回 null=失败，否则 Pair(最新 liked, 最新 like_count) */
suspend fun likeShortVideoComment(commentId: Long): Pair<Boolean, Int>? = withContext(Dispatchers.IO) {
    try {
        val raw = HttpClient.jsonRequest(
            "POST", "${AuroraApi.serverUrl}/api/shortvideo/comment/like",
            JSONObject().put("comment_id", commentId).toString(), AuroraApi.authToken
        )
        val obj = JSONObject(raw)
        if (obj.optInt("code", 0) != 200) return@withContext null
        val d = obj.optJSONObject("data") ?: return@withContext null
        Pair(d.optBoolean("liked", false), d.optInt("like_count", 0))
    } catch (_: Exception) {
        null
    }
}

/** 删除评论（自己 / 开发者 / 平台管理员；后端级联删后代回复）。返回删除条数，null=失败 */
suspend fun deleteShortVideoComment(commentId: Long): Int? = withContext(Dispatchers.IO) {
    try {
        val raw = HttpClient.jsonRequest(
            "POST", "${AuroraApi.serverUrl}/api/shortvideo/comment/delete",
            JSONObject().put("comment_id", commentId).toString(), AuroraApi.authToken
        )
        val obj = JSONObject(raw)
        if (obj.optInt("code", 0) != 200) return@withContext null
        obj.optJSONObject("data")?.optInt("deleted", 0) ?: 0
    } catch (_: Exception) {
        null
    }
}

// ---------- 网络层 ----------

/** 开发者作品信息（/api/shortvideo/info 全量字段） */
data class SVDevInfo(
    val id: Long,
    val title: String,
    val description: String,
    val createdAt: Long,
    val duration: Int,
    val statusText: String,
    val isHls: Boolean,
    val viewCount: Int,
    val likesCount: Int,
    val commentsCount: Int,
    val lastLikeAt: Long,
    val lastCommentAt: Long,
    val playUrl: String,
    val coverUrl: String,
    val origName: String,
    val origSize: Long,
    val origExists: Boolean,
    val hlsDir: String,
    val hlsSize: Long,
    val hlsFiles: Int,
    val hlsSegments: Int,
    val ffmpegOk: Boolean,
    // 降级原因（服务端推导）。空 = HLS 成功；非空 = 走了兜底档（如 480P 兼容档）
    val degradeReason: String = "",
    val renditions: List<SVRendition>,
    val publisherId: Long,
    val publisherName: String,
    val publisherEmail: String,
    val publisherAvatar: String,
    val publisherCreatedAt: Long,
    val publisherIsDev: Boolean
)

/** 拉取作品全量信息（仅开发者有权限；失败返回 null）。 */
suspend fun fetchShortVideoInfo(id: Long): SVDevInfo? = withContext(Dispatchers.IO) {
    try {
        val raw = HttpClient.jsonRequest(
            "GET", "${AuroraApi.serverUrl}/api/shortvideo/info?id=$id", null, AuroraApi.authToken
        )
        val obj = JSONObject(raw)
        if (obj.optInt("code", 0) != 200) return@withContext null
        val d = obj.optJSONObject("data") ?: return@withContext null
        val pub = d.optJSONObject("publisher")
        val rends = mutableListOf<SVRendition>()
        d.optJSONArray("renditions")?.let { ra ->
            for (i in 0 until ra.length()) {
                val r = ra.optJSONObject(i) ?: continue
                rends.add(
                    SVRendition(
                        quality = r.optInt("quality"), codec = r.optString("codec"),
                        bitrate = r.optInt("bitrate"), width = r.optInt("width"),
                        height = r.optInt("height"), playlist = r.optString("playlist")
                    )
                )
            }
        }
        SVDevInfo(
            id = d.optLong("id"),
            title = d.optString("title"),
            description = d.optString("description"),
            createdAt = d.optLong("created_at"),
            duration = d.optInt("duration"),
            statusText = d.optString("status_text"),
            isHls = d.optBoolean("is_hls"),
            viewCount = d.optInt("view_count"),
            likesCount = d.optInt("likes_count"),
            commentsCount = d.optInt("comments_count"),
            lastLikeAt = d.optLong("last_like_at"),
            lastCommentAt = d.optLong("last_comment_at"),
            playUrl = d.optString("play_url"),
            coverUrl = d.optString("cover_url"),
            origName = d.optString("orig_name"),
            origSize = d.optLong("orig_size"),
            origExists = d.optBoolean("orig_exists"),
            hlsDir = d.optString("hls_dir"),
            hlsSize = d.optLong("hls_size"),
            hlsFiles = d.optInt("hls_files"),
            hlsSegments = d.optInt("hls_segments"),
            ffmpegOk = d.optBoolean("ffmpeg_ok"),
            degradeReason = d.optString("degrade_reason"),
            renditions = rends.sortedBy { it.quality },
            publisherId = pub?.optLong("id") ?: 0L,
            publisherName = pub?.optString("username") ?: "",
            publisherEmail = pub?.optString("email") ?: "",
            publisherAvatar = pub?.optString("avatar") ?: "",
            publisherCreatedAt = pub?.optLong("created_at") ?: 0L,
            publisherIsDev = pub?.optBoolean("is_developer") ?: false
        )
    } catch (e: Exception) {
        Log.e("ShortVideo", "info 拉取失败", e)
        null
    }
}

/** 拉取 feed：返回 null 表示请求失败；返回空列表表示「确实一条都没有」。 */
suspend fun fetchShortVideoFeed(
    page: Int = 1,
    limit: Int = 20,
    /** true = 只拉当前登录用户自己发布的视频（「我的发布 - 视频」用，服务端按 user_id 过滤） */
    mine: Boolean = false
): List<SVItem>? = withContext(Dispatchers.IO) {
    try {
        val raw = HttpClient.jsonRequest(
            "GET", "${AuroraApi.serverUrl}/api/shortvideo/feed?page=$page&limit=$limit" + if (mine) "&mine=1" else "",
            null, AuroraApi.authToken
        )
        val obj = JSONObject(raw)
        val data = obj.optJSONObject("data") ?: return@withContext emptyList()
        val arr = data.optJSONArray("videos") ?: return@withContext emptyList()
        val list = mutableListOf<SVItem>()
        for (i in 0 until arr.length()) {
            val v = arr.optJSONObject(i) ?: continue
            val pub = v.optJSONObject("publisher")
            val publisher = SVPublisher(
                id = pub?.optLong("id") ?: 0L,
                username = pub?.optString("username") ?: "",
                avatar = pub?.optString("avatar") ?: ""
            )
            list.add(
                SVItem(
                    id = v.optLong("id"), publisher = publisher,
                    title = v.optString("title"), description = v.optString("description"),
                    coverUrl = v.optString("cover_url"),
                    playUrl = v.optString("play_url"), isHls = v.optBoolean("is_hls"),
                    duration = v.optInt("duration"), viewCount = v.optInt("view_count"),
                    likesCount = v.optInt("likes_count"), commentsCount = v.optInt("comments_count"),
                    hasLiked = v.optBoolean("has_liked"), createdAt = v.optLong("created_at"),
                    // 后端转码时写入的源显示尺寸/比例（老数据可能为 0，播放器会自动回退）
                    width = v.optInt("width"),
                    height = v.optInt("height"),
                    aspectRatio = v.optDouble("aspect_ratio", 0.0).toFloat(),
                    directUrl = v.optString("direct_url"),
                    degradeReason = v.optString("degrade_reason"),
                    renditions = run {
                        val ra = v.optJSONArray("renditions") ?: return@run emptyList()
                        val out = mutableListOf<SVRendition>()
                        for (k in 0 until ra.length()) {
                            val r = ra.optJSONObject(k) ?: continue
                            out.add(
                                SVRendition(
                                    quality = r.optInt("quality"), codec = r.optString("codec"),
                                    bitrate = r.optInt("bitrate"), width = r.optInt("width"),
                                    height = r.optInt("height"), playlist = r.optString("playlist")
                                )
                            )
                        }
                        out.sortedBy { it.quality }
                    }
                )
            )
        }
        list
    } catch (e: Exception) {
        Log.e("ShortVideo", "feed 拉取失败", e)
        null
    }
}

suspend fun uploadShortVideo(
    ctx: android.content.Context,
    uri: Uri,
    title: String = "",
    description: String = "",
    onProgress: ((sent: Long, total: Long) -> Unit)? = null
): Long? =
    withContext(Dispatchers.IO) {
        try {
            val cr: ContentResolver = ctx.contentResolver
            val mime = cr.getType(uri) ?: "video/mp4"
            val ext = mime.substringAfterLast('/').substringAfterLast('.', "mp4")
            val fileName = "video_${System.currentTimeMillis()}.${if (ext.length <= 4) ext else "mp4"}"
            var total: Long = -1
            cr.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) total = c.getLong(0)
            }
            val input = cr.openInputStream(uri)
            if (input == null) {
                ErrorReporter.error("VideoPublish", "上传中断: openInputStream 返回 null uri=$uri mime=$mime size=$total")
                return@withContext null
            }
            ErrorReporter.debug("VideoPublish", "开始上传: $fileName mime=$mime 大小=${total}B uri=$uri")
            val resp = HttpClient.uploadStreamWithProgress(
                url = "${AuroraApi.serverUrl}/api/shortvideo/upload",
                // 标题/简介走表单字段，后端 handleShortVideoUpload 直接读 FormValue。
                // 以前这里传的是 emptyMap()，所以就算上面填了也到不了服务器。
                fields = buildMap {
                    if (title.isNotBlank()) put("title", title)
                    if (description.isNotBlank()) put("description", description)
                },
                input = input, partName = "file",
                fileName = fileName, mimeType = mime, totalBytes = total, authToken = AuroraApi.authToken,
                onProgress = onProgress
            )
            val id = runCatching {
                JSONObject(resp).optJSONObject("data")?.optLong("id") ?: 0L
            }.getOrDefault(0L)
            Log.i("ShortVideo", "上传成功 id=$id，后台转码中")
            ErrorReporter.debug("VideoPublish", "上传请求返回: id=$id 响应前200字符=${resp.take(200)}")
            if (id > 0) id else null
        } catch (e: Exception) {
            Log.e("ShortVideo", "上传失败", e)
            ErrorReporter.error("VideoPublish", "上传异常（${e.javaClass.simpleName}: ${e.message}）", e)
            null
        }
    }

/** 删除短视频（后端会真删 DB 记录 + HLS 目录 + 原始文件）。成功返回 true。 */
suspend fun deleteShortVideo(id: Long): Boolean = withContext(Dispatchers.IO) {
    try {
        val raw = HttpClient.jsonRequest(
            "POST", "${AuroraApi.serverUrl}/api/shortvideo/delete",
            JSONObject().put("id", id).toString(), AuroraApi.authToken
        )
        val o = JSONObject(raw)
        val ok = o.optInt("code", 0) == 200
        if (!ok) Log.e("ShortVideo", "删除失败: ${o.optString("message")}")
        ok
    } catch (e: Exception) {
        Log.e("ShortVideo", "删除失败", e)
        false
    }
}

/** 查询转码状态：1=已发布，0=转码中，-1=不存在。网络抖动返回 0（视为仍在转码，继续轮询）。 */
suspend fun fetchShortVideoStatus(id: Long): Int = withContext(Dispatchers.IO) {
    try {
        val raw = HttpClient.jsonRequest(
            "GET", "${AuroraApi.serverUrl}/api/shortvideo/status?id=$id", null, AuroraApi.authToken
        )
        JSONObject(raw).optJSONObject("data")?.optInt("status", -1) ?: -1
    } catch (e: Exception) {
        Log.e("ShortVideo", "状态查询失败", e)
        0
    }
}

/**
 * 预加载 HLS 相邻视频的首分片：解析 master → 选首个变体 → 取第一个 .ts，
 * 用项目 OkHttp 单例 GET（命中 20MB 磁盘缓存），滑到即直接命中，不重复回源。
 * 仅预取字节，不创建解码器（不占 CPU/解码器，防卡顿）。
 */
suspend fun prefetchHlsFirstSegment(absMasterUrl: String, quality: Int = -1) = withContext(Dispatchers.IO) {
    try {
        val client = HttpClient.client
        val req = { u: String -> Request.Builder().url(u).header("User-Agent", "AuroraChat-Android").build() }
        val master = client.newCall(req(absMasterUrl)).execute().use { it.body?.string() } ?: return@withContext
        // 收集所有变体（ffmpeg 按码率从低到高排列），再按用户选的画质取对应那一档。
        // 以前固定取第一个 = 永远预取最低清的一档，而播放器选的是最高档，预取等于白做；
        // 现在多了一个 CRF 原画档，如果无脑取最后一个，又会预下载 4K 分片烧流量。
        val variants = mutableListOf<String>()
        val ml = master.split("\n")
        for (i in ml.indices) {
            if (ml[i].startsWith("#EXT-X-STREAM-INF")) {
                for (j in i + 1 until ml.size) {
                    val t = ml[j].trim()
                    if (t.isNotEmpty() && !t.startsWith("#")) { variants.add(t); break }
                }
            }
        }
        if (variants.isEmpty()) return@withContext
        val idx = when {
            quality >= 2160 -> variants.lastIndex                      // 高 = 原画
            quality >= 1080 -> (variants.size - 2).coerceAtLeast(0)    // 中
            else -> 0                                                  // 低
        }
        val variantUri: String? = variants[idx]
        val variantAbs = resolveHlsUrl(absMasterUrl, variantUri!!)
        val variant = client.newCall(req(variantAbs)).execute().use { it.body?.string() } ?: return@withContext
        // 取第一个 .ts 分片
        val seg = variant.split("\n").firstOrNull {
            val t = it.trim()
            t.isNotEmpty() && !t.startsWith("#") && t.endsWith(".ts")
        }?.trim() ?: return@withContext
        val segAbs = resolveHlsUrl(variantAbs, seg)
        // 预取（进 OkHttp 缓存；.ts 有 max-age=86400）
        client.newCall(req(segAbs)).execute().use { it.body?.bytes() }
    } catch (_: Exception) { /* 预取失败不阻断 */ }
}

private fun resolveHlsUrl(base: String, rel: String): String {
    if (rel.startsWith("http")) return rel
    val u = runCatching { java.net.URI(base) }.getOrNull() ?: return rel
    return if (rel.startsWith("/")) "${u.scheme}://${u.host}$rel"
    else {
        val baseDir = if (base.endsWith("/")) base else base.substring(0, base.lastIndexOf('/') + 1)
        baseDir + rel
    }
}
