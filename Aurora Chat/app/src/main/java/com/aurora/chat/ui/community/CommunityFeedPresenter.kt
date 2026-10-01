package com.aurora.chat.ui.community

import android.content.Context
import android.content.SharedPreferences
import androidx.collection.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import com.aurora.chat.data.api.PostListItem
import com.aurora.chat.data.repository.ChatRepository

/**
 * 社区信息流的状态持有者（State Holder）。
 *
 * 职责边界（关注点分离）：
 * - 本类：分页加载、关键字搜索、下拉刷新、删除、断网缓存兜底、并发安全。
 * - UI 层（Composable）：只负责渲染列表与转发滚动/下拉事件，不持有任何数据逻辑。
 *
 * 对外暴露 [uiState]（不可变 [StateFlow]），UI 用 collectAsState 收集即可。
 */
class CommunityFeedPresenter(
    private val scope: CoroutineScope,
    context: Context,
    private val mine: Boolean = false
) {
    private val cachePrefs: SharedPreferences =
        context.getSharedPreferences("community_feed_cache", Context.MODE_PRIVATE)
    // 缓存键区分「全部」与「我的」：避免两类列表互相覆盖，且进入「我的发布」时可秒显缓存
    private val cacheKey = if (mine) "post_list_mine" else "post_list_all"

    // 已浏览过的帖子 id，用于「综合」排序优先展示未看过的内容
    // 必须在 displayPosts 初始化（orderPosts）之前声明，否则构造器阶段访问到 null 导致 NPE
    private val seenIds: MutableSet<Long> = restoreSeen().toMutableSet()

    // 首屏即同步灌入磁盘缓存（SharedPreferences），进入信息流/我的发布第一帧就有内容，无需等待协程
    private val _initialCache = restoreCache()
    private var loadedPosts: List<PostListItem> = _initialCache ?: emptyList()
    private var displayPosts: List<PostListItem> =
        _initialCache?.let { orderPosts(it, loadSortMode()) } ?: emptyList()

    // 排序方式持久化：仅当用户从未设置过时才回落到默认「综合」
    private val _uiState = MutableStateFlow(
        CommunityFeedUiState(
            posts = displayPosts,
            hasMore = _initialCache != null,
            loadState = if (_initialCache != null) FeedLoadState.Content else FeedLoadState.Initial,
            sortMode = loadSortMode()
        )
    )
    val uiState: StateFlow<CommunityFeedUiState> = _uiState.asStateFlow()

    private var page = 0
    private var hasMore = true
    private var keyword = ""
    private var isLoading = false
    // 请求序号：每次发起新请求自增，只有最新序号的结果才生效，避免并发/快速切换时旧响应覆盖新数据。
    private var seq = 0

    /** 首屏 / 关键字变更后的整页加载 */
    fun loadFirst() {
        val mySeq = ++seq
        isLoading = true
        scope.launch(Dispatchers.Main.immediate) {
            val previous = _uiState.value
            // 性能优化：若本地有缓存，先瞬间展示（无白屏/转圈），随后后台静默刷新；
            // 仅在完全没有缓存时才显示首屏加载态。
            val cached = restoreCache()
            if (cached != null && previous.posts.isEmpty()) {
                loadedPosts = cached
                displayPosts = orderPosts(cached)
                _uiState.value = previous.copy(
                    posts = displayPosts,
                    hasMore = true,
                    loadState = FeedLoadState.Content,
                    error = null
                )
            } else {
                // 已有内容（含首帧缓存）→ 保持展示、仅后台刷新；完全无内容才显示首屏加载态
                _uiState.value = previous.copy(
                    loadState = if (previous.posts.isEmpty()) FeedLoadState.Initial else FeedLoadState.Content,
                    error = null
                )
            }
            runCatching {
                ChatRepository.getCommunityPosts(page = 1, limit = PAGE_SIZE, type = "", keyword = keyword, mine = mine)
            }.onSuccess { result ->
                if (mySeq != seq) return@onSuccess // 已有更新的请求，丢弃本次结果
                if (result.success && result.data != null) {
                    val data = result.data!!
                    val posts = dedupe(emptyList(), data.posts)
                    page = 1
                    hasMore = data.posts.size >= data.limit
                    loadedPosts = posts
                    displayPosts = orderPosts(posts)
                    _uiState.value = previous.copy(
                        posts = displayPosts,
                        hasMore = hasMore,
                        loadState = if (posts.isEmpty()) FeedLoadState.Empty else FeedLoadState.Content
                    )
                    cachePosts(posts)
                } else {
                    handleFailure(previous, result.message)
                }
            }.onFailure {
                if (mySeq != seq) return@onFailure
                handleFailure(previous, it.message)
            }
            if (mySeq == seq) isLoading = false
        }
    }

    /** 触底加载下一页（内部有防重入，可放心频繁调用） */
    fun loadMore() {
        if (isLoading || !hasMore) return
        val mySeq = ++seq
        isLoading = true
        scope.launch(Dispatchers.Main.immediate) {
            val previous = _uiState.value
            _uiState.value = previous.copy(loadState = FeedLoadState.LoadingMore)
            runCatching {
                ChatRepository.getCommunityPosts(page = page + 1, limit = PAGE_SIZE, type = "", keyword = keyword, mine = mine)
            }.onSuccess { result ->
                if (mySeq != seq) return@onSuccess
                if (result.success && result.data != null) {
                    val data = result.data!!
                    val merged = dedupe(loadedPosts, data.posts)
                    page += 1
                    hasMore = data.posts.size >= data.limit
                    loadedPosts = merged
                    // 「综合」下仅把新增内容打乱后追加到末尾，已展示内容保持原顺序不被打乱
                    displayPosts = if (_uiState.value.sortMode == FeedSortMode.MIXED) {
                        val known = displayPosts.mapTo(java.util.HashSet()) { it.id }
                        displayPosts + shuffleAvoidingSeen(merged.filter { it.id !in known })
                    } else {
                        orderPosts(merged)
                    }
                    _uiState.value = previous.copy(
                        posts = displayPosts,
                        hasMore = hasMore,
                        loadState = if (merged.isEmpty()) FeedLoadState.Empty else FeedLoadState.Content
                    )
                } else {
                    _uiState.value = previous.copy(loadState = FeedLoadState.Content)
                }
            }.onFailure {
                if (mySeq != seq) return@onFailure
                _uiState.value = previous.copy(loadState = FeedLoadState.Content)
            }
            if (mySeq == seq) isLoading = false
        }
    }

    /** 下拉刷新 */
    fun refresh() {
        val mySeq = ++seq
        isLoading = true
        scope.launch(Dispatchers.Main.immediate) {
            val previous = _uiState.value
            _uiState.value = previous.copy(loadState = FeedLoadState.Refreshing)
            runCatching {
                ChatRepository.getCommunityPosts(page = 1, limit = PAGE_SIZE, type = "", keyword = keyword, mine = mine)
            }.onSuccess { result ->
                if (mySeq != seq) return@onSuccess
                if (result.success && result.data != null) {
                    val data = result.data!!
                    val posts = dedupe(emptyList(), data.posts)
                    page = 1
                    hasMore = data.posts.size >= data.limit
                    loadedPosts = posts
                    displayPosts = orderPosts(posts)
                    _uiState.value = previous.copy(
                        posts = displayPosts,
                        hasMore = hasMore,
                        loadState = if (posts.isEmpty()) FeedLoadState.Empty else FeedLoadState.Content
                    )
                    cachePosts(posts)
                } else {
                    _uiState.value = previous.copy(loadState = FeedLoadState.Content)
                }
            }.onFailure {
                if (mySeq != seq) return@onFailure
                _uiState.value = previous.copy(loadState = FeedLoadState.Content)
            }
            if (mySeq == seq) isLoading = false
        }
    }

    /** 关键字搜索（重置并整页加载） */
    fun search(keyword: String) {
        this.keyword = keyword.trim()
        page = 0
        hasMore = true
        loadFirst()
    }

    /** 切换排序方式：立即按新规则重排已加载内容 */
    fun setSortMode(mode: FeedSortMode) {
        val previous = _uiState.value
        displayPosts = orderPosts(loadedPosts, mode)
        _uiState.value = previous.copy(sortMode = mode, posts = displayPosts)
        persistSortMode(mode)
        persistSeen()
    }

    /** 记录帖子已被浏览（供「综合」优先展示未看过的内容） */
    fun markSeen(id: Long) {
        if (seenIds.add(id)) persistSeen()
    }

    /** 按给定排序方式生成展示顺序（默认取当前状态中的排序方式） */
    private fun orderPosts(
        list: List<PostListItem>,
        mode: FeedSortMode = _uiState.value.sortMode
    ): List<PostListItem> = when (mode) {
        FeedSortMode.DEFAULT -> list
        FeedSortMode.LATEST -> list.sortedByDescending { it.created_at }
        FeedSortMode.MIXED -> shuffleAvoidingSeen(list)
    }

    /**
     * 「综合」算法：随机打乱，并把未浏览过的排到前面。
     * 当全部都已浏览过（unseen 为空）时退化为整体随机，保证始终有内容可看。
     */
    private fun shuffleAvoidingSeen(list: List<PostListItem>): List<PostListItem> {
        val unseen = ArrayList<PostListItem>()
        val seen = ArrayList<PostListItem>()
        for (p in list) {
            if (p.id in seenIds) seen.add(p) else unseen.add(p)
        }
        unseen.shuffle()
        seen.shuffle()
        return unseen + seen
    }

    /** 读取已保存的排序方式；从未设置过则返回默认「综合」 */
    private fun loadSortMode(): FeedSortMode {
        val name = cachePrefs.getString(KEY_SORT_MODE, null) ?: return FeedSortMode.MIXED
        return FeedSortMode.values().firstOrNull { it.name == name } ?: FeedSortMode.MIXED
    }

    private fun persistSortMode(mode: FeedSortMode) {
        cachePrefs.edit().putString(KEY_SORT_MODE, mode.name).apply()
    }

    private fun persistSeen() {
        try {
            cachePrefs.edit()
                .putStringSet("seen_ids", seenIds.map { it.toString() }.toSet())
                .apply()
        } catch (_: Exception) {
        }
    }

    private fun restoreSeen(): Set<Long> {
        return try {
            cachePrefs.getStringSet("seen_ids", null)
                ?.mapNotNull { it.toLongOrNull() }
                ?.toSet() ?: emptySet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    /** 发布/删除成功后由 UI 调用，立即从本地列表移除（后续刷新会与服务端同步） */
    fun removePost(id: Long) {
        loadedPosts = loadedPosts.filter { it.id != id }
        displayPosts = displayPosts.filter { it.id != id }
        val previous = _uiState.value
        val posts = displayPosts
        _uiState.value = previous.copy(
            posts = posts,
            loadState = if (posts.isEmpty()) FeedLoadState.Empty else FeedLoadState.Content
        )
    }

    private fun handleFailure(previous: CommunityFeedUiState, msg: String?) {
        val cached = restoreCache()
        if (cached != null) {
            loadedPosts = cached
            displayPosts = orderPosts(cached)
            _uiState.value = previous.copy(
                posts = displayPosts, hasMore = false,
                loadState = if (cached.isEmpty()) FeedLoadState.Empty else FeedLoadState.Content,
                error = msg
            )
        } else {
            _uiState.value = previous.copy(
                hasMore = false,
                loadState = if (previous.posts.isEmpty()) FeedLoadState.Empty else FeedLoadState.Content,
                error = msg
            )
        }
    }

    /** 列表追加前按 id 去重，避免分页边界重叠导致的重复 key（会触发 LazyColumn IllegalArgumentException） */
    private fun dedupe(existing: List<PostListItem>, incoming: List<PostListItem>): List<PostListItem> {
        if (existing.isEmpty()) return incoming
        val ids = LinkedHashSet(existing.map { it.id })
        val out = existing.toMutableList()
        for (p in incoming) {
            if (ids.add(p.id)) out.add(p)
        }
        return out
    }

    private fun cachePosts(posts: List<PostListItem>) {
        try {
            val arr = JSONArray()
            posts.forEach { p ->
                arr.put(JSONObject().apply {
                    put("id", p.id); put("user_id", p.user_id); put("username", p.username)
                    put("title", p.title); put("content", p.content); put("created_at", p.created_at)
                    put("post_type", p.post_type); put("res_types", p.res_types)
                    put("res_exts", p.res_exts); put("res_count", p.res_count)
                    put("first_resource_url", p.first_resource_url); put("is_adult", p.is_adult)
                })
            }
            cachePrefs.edit().putString(cacheKey, arr.toString()).apply()
        } catch (_: Exception) {
        }
    }

    private fun restoreCache(): List<PostListItem>? {
        return try {
            val cached = cachePrefs.getString(cacheKey, null) ?: return null
            val arr = JSONArray(cached)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                PostListItem(
                    id = o.getLong("id"), user_id = o.getLong("user_id"),
                    username = o.getString("username"), title = o.getString("title"),
                    content = o.getString("content"), created_at = o.getLong("created_at"),
                    post_type = o.optString("post_type", "post"), res_types = o.optString("res_types", ""),
                    res_exts = o.optString("res_exts", ""), res_count = o.optInt("res_count", 0),
                    first_resource_url = o.optString("first_resource_url", ""),
                    is_adult = o.optBoolean("is_adult", false)
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val PAGE_SIZE = 20
        private const val KEY_SORT_MODE = "sort_mode"
    }
}

/** 信息流排序方式 */
enum class FeedSortMode(val label: String) {
    DEFAULT("默认"),
    MIXED("综合"),
    LATEST("最新")
}

/** 信息流加载状态 */
sealed interface FeedLoadState {
    object Initial : FeedLoadState       // 首屏加载中（列表为空）
    object Content : FeedLoadState       // 正常展示
    object Refreshing : FeedLoadState    // 下拉刷新中
    object LoadingMore : FeedLoadState   // 触底加载中
    object Empty : FeedLoadState         // 无数据
}

/** 不可变的 UI 状态快照 */
data class CommunityFeedUiState(
    val posts: List<PostListItem> = emptyList(),
    val loadState: FeedLoadState = FeedLoadState.Initial,
    val hasMore: Boolean = true,
    val error: String? = null,
    val sortMode: FeedSortMode = FeedSortMode.MIXED
)
