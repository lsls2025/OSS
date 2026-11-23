package com.aurora.chat.core.domain

interface ChatEventSink {
    val openConversationId: Long
    fun addUnread(conversationId: Long, count: Int)
    fun notifyNewMessage()
    fun notifyFriendRequest()
    fun notifyPrivacyChanged()
    fun notifySelfChatRefresh()
    fun notifyPinChanged()
    fun loadConversations(force: Boolean)
    fun loadFriendRequests()
    fun removeUserData(userId: Long)
    fun refreshGroupAvatars()
}

object NoopChatEventSink : ChatEventSink {
    override val openConversationId: Long get() = 0L
    override fun addUnread(conversationId: Long, count: Int) = Unit
    override fun notifyNewMessage() = Unit
    override fun notifyFriendRequest() = Unit
    override fun notifyPrivacyChanged() = Unit
    override fun notifySelfChatRefresh() = Unit
    override fun notifyPinChanged() = Unit
    override fun loadConversations(force: Boolean) = Unit
    override fun loadFriendRequests() = Unit
    override fun removeUserData(userId: Long) = Unit
    override fun refreshGroupAvatars() = Unit
}

object ChatEvents {
    @Volatile
    var sink: ChatEventSink = NoopChatEventSink
}
