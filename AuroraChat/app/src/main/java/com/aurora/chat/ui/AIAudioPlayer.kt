package com.aurora.chat.ui

import android.media.MediaPlayer

/**
 * AI 音频播放的单例持有者：让「播放音频」工具能在多次调用之间持续播放，并可随时停止。
 */
object AIAudioPlayer {

    @Volatile
    var player: MediaPlayer? = null

    @Synchronized
    fun stop() {
        val p = player ?: return
        player = null
        runCatching { if (p.isPlaying) p.stop() }
        runCatching { p.release() }
    }
}
