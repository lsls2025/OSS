package com.aurora.chat.ui.community
import com.aurora.chat.ui.theme.LocalAppColors

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.aurora.chat.PendingCommunityActivity
import kotlinx.coroutines.delay
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.chat.ActivityReadStateManager
import com.aurora.chat.data.api.CommunityActivity
import com.aurora.chat.data.repository.ChatRepository
import kotlinx.coroutines.launch

/**
 * 动态（谁点赞 / 评论 / 回复 / 点赞评论了我）。
 *
 * 数据来自 /api/community/activities（分页：page/limit，limit 上限 50）。
 * 覆盖社区帖子/资源的点赞/评论/回复，以及短视频的点赞/评论/回复/评论点赞。
 * 已读状态由 [ActivityReadStateManager] 本地维护——服务端不返回 is_read。
 * 未读数量给底部导航「我的」tab 红点用，见 [CommunityActivityBadge]。
 *
 * 加载策略：进页先读磁盘缓存（秒开，不发请求）→ 后台拉第 1 页刷新 → 滑到底部附近自动翻页追加；
 * 每次网络合并成功后把最新列表整体写回磁盘，下次进页直接呈现。
 */
@Composable
fun CommunityActivityScreen(
    onBack: () -> Unit,
    onOpenPost: (CommunityActivity) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<CommunityActivity>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(true) }
    var page by remember { mutableStateOf(1) }
    // 已读状态提升到父级：列表项据此显示红点，一键已读时统一刷新
    var readIds by remember { mutableStateOf(ActivityReadStateManager.getReadActivityIds(ctx)) }
    var showClearDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // ── 进页先落盘秒开：缓存非空则直接显示，不转圈 ──
    LaunchedEffect(Unit) {
        val cached = CommunityActivityCache.load(ctx)
        if (cached.isNotEmpty()) {
            items = cached
            loading = false
        }
    }

    fun append(newPage: List<CommunityActivity>, fetchedPage: Int) {
        // 服务端按时间倒序分页；按 id 去重后合并，避免期间新增动态导致两页重叠
        val seen = items.map { it.id }.toSet()
        val merged = if (fetchedPage == 1) {
            // 第一页整体替换（本地已读状态不受影响，readIds 独立存储）
            newPage
        } else {
            items + newPage.filterNot { seen.contains(it.id) }
        }
        items = merged
        page = fetchedPage
        hasMore = newPage.size >= COMMUNITY_ACTIVITY_PAGE_SIZE
        CommunityActivityCache.save(ctx, merged)
    }

    // ── 首页刷新：网络成功才覆盖缓存；失败时保留缓存内容（秒开的数据不被清掉） ──
    fun refresh() {
        scope.launch {
            // 刷新前先记下"是不是停在最顶部"和"原来第一条是谁"
            val wasAtTop = listState.firstVisibleItemIndex == 0 &&
                listState.firstVisibleItemScrollOffset == 0
            val oldFirstId = items.firstOrNull()?.id
            // 缓存为空才显示转圈；有缓存时静默刷新
            if (items.isEmpty()) loading = true
            try {
                val res = ChatRepository.getCommunityActivities(page = 1, limit = COMMUNITY_ACTIVITY_PAGE_SIZE)
                if (res.success && res.data != null) {
                    append(res.data!!.activities, 1)
                    // 新动态插在最前面时，LazyColumn 会按 key 保住"原来那条"的位置，
                    // 结果人明明在顶部却看不到新内容，还得手动往上滑。这里直接跟到最新。
                    // 只在原本就停在顶部时才跟，正在翻历史时不能把人拽走。
                    if (wasAtTop && oldFirstId != null && items.firstOrNull()?.id != oldFirstId) {
                        listState.scrollToItem(0)
                    }
                }
            } catch (_: Exception) {
            } finally {
                loading = false
            }
        }
    }
    LaunchedEffect(Unit) { refresh() }

    // 收到「社区动态」推送就刷新列表（不看推送时间戳，只看版本号变化），
    // 这样停在动态页时新内容会自己出现，不需要退出去再进来。
    LaunchedEffect(Unit) {
        var seenVersion = PendingCommunityActivity.version
        while (true) {
            delay(2_000L)
            if (PendingCommunityActivity.version != seenVersion) {
                seenVersion = PendingCommunityActivity.version
                refresh()
            }
        }
    }

    // ── 滑到倒数第 4 个条目附近自动翻页 ──
    val nearBottom by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = listState.layoutInfo.totalItemsCount
            total > 0 && last >= total - 4
        }
    }
    LaunchedEffect(nearBottom) {
        if (nearBottom && !loadingMore && hasMore && !loading) {
            loadingMore = true
            try {
                val res = ChatRepository.getCommunityActivities(page = page + 1, limit = COMMUNITY_ACTIVITY_PAGE_SIZE)
                if (res.success && res.data != null) append(res.data!!.activities, page + 1)
            } catch (_: Exception) {
            } finally {
                loadingMore = false
            }
        }
    }

    Column(Modifier.fillMaxSize().background(com.aurora.chat.ui.theme.appSurface()).statusBarsPadding()) {
        // 顶栏：返回 + 标题（与二级页样式一致）
        Row(
            Modifier.fillMaxWidth().height(52.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(44.dp).clickable { onBack() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = com.aurora.chat.ui.theme.appTextPrimary()
                )
            }
            Text(
                "动态",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = com.aurora.chat.ui.theme.appTextPrimary()
            )
            Spacer(Modifier.weight(1f))
            // 一键已读：仅在有未读时显示
            if (items.any { !readIds.contains(it.id) }) {
                Box(
                    Modifier.clickable { showClearDialog = true }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("消除", fontSize = 14.sp, color = Color(0xFF1E40AF))
                }
            }
        }
        HorizontalDivider(color = com.aurora.chat.ui.theme.appDivider(), thickness = 0.5.dp)

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFF1E40AF), strokeWidth = 2.dp)
            }
            items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("还没有人跟你互动", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint())
            }
            else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(items, key = { it.id }) { a ->
                    val isRead = readIds.contains(a.id)
                    val (icon, iconBg, iconFg) = activityVisual(a.action_type)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (!isRead) {
                                    readIds = readIds + a.id
                                    ActivityReadStateManager.markActivityRead(ctx, a.id)
                                    scope.launch { CommunityActivityBadge.refresh(ctx) }
                                }
                                onOpenPost(a)
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(40.dp).clip(RoundedCornerShape(50)).background(iconBg),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                icon, contentDescription = null, tint = iconFg,
                                // 人形图标本身留白多，20dp 在 40dp 圆底里显得偏小，单独放大一档
                                modifier = Modifier.size(if (a.action_type == "follow") 25.dp else 20.dp)
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${a.username.ifBlank { "用户${a.user_id}" }}${activityActionText(a)}",
                                fontSize = 15.sp,
                                color = com.aurora.chat.ui.theme.appTextPrimary(),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                formatActivityTimeAgo(a.created_at),
                                fontSize = 12.sp,
                                color = com.aurora.chat.ui.theme.appTextHint()
                            )
                        }
                        if (!isRead) {
                            Box(
                                Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFEF4444))
                            )
                        }
                    }
                    HorizontalDivider(color = com.aurora.chat.ui.theme.appDivider(), thickness = 0.5.dp)
                }
                // 底部加载态：翻页中 / 没有更多了
                if (loadingMore || !hasMore) {
                    item(key = "footer") {
                        Box(
                            Modifier.fillMaxWidth().padding(vertical = 14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (loadingMore) {
                                CircularProgressIndicator(
                                    color = Color(0xFF1E40AF), strokeWidth = 2.dp,
                                    modifier = Modifier.size(20.dp)
                                )
                            } else {
                                Text("没有更多了", fontSize = 12.sp, color = Color(0xFFC4C9D0))
                            }
                        }
                    }
                }
            }
        }

        // 一键已读确认弹窗
        if (showClearDialog) {
            AlertDialog(
                onDismissRequest = { showClearDialog = false },
                title = { Text("一键已读") },
                text = { Text("确定将全部动态标记为已读吗？此操作不可撤销。") },
                confirmButton = {
                    TextButton(onClick = {
                        showClearDialog = false
                        scope.launch {
                            // 关键：不能只标记"当前已加载"的这几十条。
                            // 动态列表每页 20 条，而「我的」红点统计的是服务端前 50 条；
                            // 未读超过一页时，清完已加载的那些仍会剩下未读 → 红点看着"消不掉"。
                            // 这里分页把服务端全部动态的 id 都标记为已读，再刷新红点。
                            val allIds = linkedSetOf<Long>()
                            items.forEach { allIds.add(it.id) }
                            var p = 1
                            while (p <= 40) {
                                val res = try {
                                    ChatRepository.getCommunityActivities(page = p, limit = 50)
                                } catch (_: Exception) {
                                    null
                                }
                                val list = res?.takeIf { it.success }?.data?.activities ?: break
                                list.forEach { allIds.add(it.id) }
                                if (list.size < 50) break
                                p++
                            }
                            ActivityReadStateManager.markAllRead(ctx, allIds.toList())
                            readIds = allIds.toSet()
                            CommunityActivityBadge.refresh(ctx)
                        }
                    }) { Text("确定", color = Color(0xFFDC2626)) }
                },
                dismissButton = {
                    TextButton(onClick = { showClearDialog = false }) { Text("取消") }
                }
            )
        }
    }
}

/** 动态分页大小（服务端 limit 上限 50） */
private const val COMMUNITY_ACTIVITY_PAGE_SIZE = 20

/** 动态类型 → 图标与配色 */
private fun activityVisual(type: String): Triple<ImageVector, Color, Color> = when (type) {
    "like" -> Triple(Icons.Filled.ThumbUp, Color(0xFFFEE2E2), Color(0xFFEF4444))
    "comment" -> Triple(Icons.Filled.ChatBubble, Color(0xFFDBEAFE), Color(0xFF3B82F6))
    "reply" -> Triple(Icons.Filled.Forum, Color(0xFFD1FAE5), Color(0xFF10B981))
    "comment_like" -> Triple(Icons.Filled.ThumbUp, Color(0xFFFEE2E2), Color(0xFFEF4444))
    // 关注：红色人形，和短视频动作栏的加号同色
    "follow" -> Triple(Icons.Filled.Person, Color(0xFFFEE2E2), Color(0xFFEF4444))
    else -> Triple(Icons.Filled.ChatBubble, com.aurora.chat.ui.theme.appCardBg(), com.aurora.chat.ui.theme.appTextSecondary())
}

/** 动态类型 → 文案（覆盖社区帖子/资源 + 短视频的点赞/评论/回复/评论点赞） */
private fun activityActionText(a: CommunityActivity): String {
    val targetName = when (a.target_type) {
        "resource" -> "资源"
        // 用户发布的不一定是短视频，统一叫「视频」，不再强调「短」
        "shortvideo" -> "视频"
        else -> "帖子"
    }
    return when (a.action_type) {
        "like" -> "点赞了你发布的$targetName"
        "comment" -> "评论了你发布的$targetName"
        "reply" -> if (a.target_type == "shortvideo") "回复了你的视频评论" else "回复了你的评论"
        "comment_like" -> "点赞了你的评论"
        "follow" -> "关注了你"
        else -> "互动了你的$targetName"
    }
}

/** 相对时间：刚刚 / n分钟前 / n小时前 / 昨天 / n天前 / MM-dd */
private fun formatActivityTimeAgo(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    val now = System.currentTimeMillis()
    val diff = now - timestamp * 1000
    val minute = 60 * 1000L
    val hour = 60 * minute
    val day = 24 * hour
    return when {
        diff < minute -> "刚刚"
        diff < hour -> "${diff / minute}分钟前"
        diff < day -> "${diff / hour}小时前"
        diff < 2 * day -> "昨天"
        diff < 7 * day -> "${diff / day}天前"
        else -> java.text.SimpleDateFormat("MM-dd", java.util.Locale.getDefault())
            .format(java.util.Date(timestamp * 1000))
    }
}

/**
 * 动态磁盘缓存：filesDir/cache/community_activities_<uid>.json（按账号隔离）。
 * 结构与 /api/community/activities 的 activities 数组一一对应；只做展示缓存，
 * 崩了/损坏直接当空处理，绝不影响网络刷新。
 */
object CommunityActivityCache {
    private const val CACHE_DIR = "cache"

    /** 按账号隔离：切换账号后不会先闪出上一个账号的动态。 */
    private fun file(context: Context): java.io.File {
        val uid = com.aurora.chat.data.api.AuroraApi.currentUserId
        return java.io.File(java.io.File(context.filesDir, CACHE_DIR), "community_activities_$uid.json")
    }

    fun load(context: Context): List<CommunityActivity> = try {
        val f = file(context)
        if (!f.exists()) emptyList() else {
            val arr = org.json.JSONArray(f.readText())
            val out = mutableListOf<CommunityActivity>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    CommunityActivity(
                        id = o.optLong("id"),
                        user_id = o.optLong("user_id"),
                        username = o.optString("username"),
                        action_type = o.optString("action_type"),
                        target_type = o.optString("target_type"),
                        target_id = o.optLong("target_id"),
                        target_title = o.optString("target_title"),
                        comment_content = o.optString("comment_content"),
                        created_at = o.optLong("created_at")
                    )
                )
            }
            out
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun save(context: Context, list: List<CommunityActivity>) {
        try {
            val dst = file(context)
            val dir = dst.parentFile!!
            if (!dir.exists()) dir.mkdirs()
            val arr = org.json.JSONArray()
            list.forEach { a ->
                arr.put(
                    org.json.JSONObject()
                        .put("id", a.id).put("user_id", a.user_id).put("username", a.username)
                        .put("action_type", a.action_type).put("target_type", a.target_type)
                        .put("target_id", a.target_id).put("target_title", a.target_title)
                        .put("comment_content", a.comment_content).put("created_at", a.created_at)
                )
            }
            val tmp = java.io.File(dir, "${dst.name}.tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(dst)) {
                dst.writeText(arr.toString())
                tmp.delete()
            }
        } catch (_: Exception) {
        }
    }
}

/**
 * 社区动态未读计数（底部导航「我的」tab 红点）。
 * 服务端不返回 is_read，已读完全由本地 [ActivityReadStateManager] 决定。
 */
object CommunityActivityBadge {
    val unreadCount = mutableStateOf(0)

    /** 拉取最近动态，统计本地未读数量 */
    suspend fun refresh(context: Context) {
        try {
            val res = ChatRepository.getCommunityActivities(page = 1, limit = 50)
            if (res.success && res.data != null) {
                val readIds = ActivityReadStateManager.getReadActivityIds(context)
                unreadCount.value = res.data!!.activities.count { it.id !in readIds }
            }
        } catch (_: Exception) {
        }
    }
}
