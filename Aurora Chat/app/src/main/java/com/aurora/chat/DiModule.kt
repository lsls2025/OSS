package com.aurora.chat

import com.aurora.chat.core.domain.ChatEventSink
import org.koin.dsl.module

val appModule = module {
    single<ChatEventSink> { AppChatEventSink }
}
