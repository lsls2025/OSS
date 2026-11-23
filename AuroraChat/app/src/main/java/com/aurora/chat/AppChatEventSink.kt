package com.aurora.chat

import com.aurora.chat.core.domain.ChatEventSink
import com.aurora.chat.ui.viewmodel.ChatViewModel

object AppChatEventSink : ChatEventSink {
    override val openConversationId: Long get() = ChatViewModel.openConversationId

    override fun addUnread(conversationId: Long, count: Int) =
        ChatViewModel.addUnread(AuroraChatApplication.instance, conversationId, count)

    override fun notifyNewMessage() = ChatViewModel.notifyNewMessage()
    override fun notifyFriendRequest() = ChatViewModel.notifyFriendRequest()
    override fun notifyPrivacyChanged() = ChatViewModel.notifyPrivacyChanged()
    override fun notifySelfChatRefresh() = ChatViewModel.notifySelfChatRefresh()
    override fun notifyPinChanged() = ChatViewModel.notifyPinChanged()
    override fun loadConversations(force: Boolean) = ChatViewModel.loadConversations(force = force)
    override fun loadFriendRequests() = ChatViewModel.loadFriendRequests()
    override fun removeUserData(userId: Long) = ChatViewModel.removeUserData(userId)
    override fun refreshGroupAvatars() = ChatViewModel.refreshGroupAvatars()
}
