package com.aurora.chat.ui.common

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.aurora.chat.data.api.HttpClient

/**
 * 通用 ExoPlayer 创建：复用项目 OkHttp 磁盘缓存（HttpClient.client）。
 *
 * 这是「流式分片 / 可拖动 / 弱网不卡」的关键 —— 与短视频（ShortVideoScreen）播放内核完全一致：
 * 远程 http(s) 走 OkHttpDataSource，命中项目级缓存，分片按需下载、可任意 seek，不会像 MediaPlayer
 * 那样依赖 moov 在头部、也不会被聊天老的「整文件下载」逻辑拖成「必须下完才播」。
 *
 * 本地文件（file:// / content:// / 绝对路径）和远程地址走同一个 DefaultDataSource 即可，
 * 它内部会自动分流到本地源，无需像 MediaPlayer 那样手动分本地/网络分支。
 *
 * 约定（与 MediaPlayer 不同，ExoPlayer 用 TextureView 而非 Surface）：
 *  - 调用方负责在 TextureView 可用后 player.setVideoTextureView(tv)；
 *  - 调用方负责在生命周期结束时 release()。
 *
 * @param url    播放地址。本地文件请传入带 scheme 的合法 Uri 字符串（file://… / content://…），
 *              绝对路径需补 "file://" 前缀，ExoPlayer 才能正确解析。
 * @param isLocal 仅用于语义标记，本函数对本地/远程使用同一套 DataSource 工厂。
 */
fun createExoPlayer(ctx: Context, url: String, isLocal: Boolean): ExoPlayer {
    // 网络（含 HLS/渐进式 mp4）走 OkHttp 复用项目缓存；DefaultDataSource 对本地 file:// 自动分流。
    val upstream = OkHttpDataSource.Factory(HttpClient.client).setUserAgent("AuroraChat-Android")
    val dsFactory = DefaultDataSource.Factory(ctx, upstream)
    val msFactory = DefaultMediaSourceFactory(dsFactory)
    val p = ExoPlayer.Builder(ctx).setMediaSourceFactory(msFactory).build()
    p.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
    // 全屏单视频场景默认循环播放（与旧 MediaPlayer isLooping=true 行为一致）；
    // 调用方如需单次播放可覆盖为 REPEAT_MODE_OFF。
    p.repeatMode = Player.REPEAT_MODE_ONE
    try { p.setHandleAudioBecomingNoisy(true) } catch (_: Exception) {}
    p.setMediaItem(MediaItem.fromUri(url))
    p.prepare()
    p.playWhenReady = true
    return p
}
