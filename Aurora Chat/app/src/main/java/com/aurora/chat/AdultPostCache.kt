package com.aurora.chat

/**
 * 本地标记为成人内容的帖子 ID 缓存。
 *
 * 现在 is_adult 已由后端持久化（community_posts.is_adult），跨设备、跨用户生效。
 * 此缓存仅作为兜底：发帖成功后到列表刷新前、以及连接旧版后端时，
 * 保证本机详情页能立即拦截。
 */
object AdultPostCache {
    private val adultPostIds = mutableSetOf<Long>()
    private val agreedPostIds = mutableSetOf<Long>()

    fun markAdult(postId: Long) { adultPostIds.add(postId) }
    fun isAdult(postId: Long): Boolean = adultPostIds.contains(postId)
    fun hasAgreed(postId: Long): Boolean = agreedPostIds.contains(postId)
    fun agree(postId: Long) { agreedPostIds.add(postId) }
}
