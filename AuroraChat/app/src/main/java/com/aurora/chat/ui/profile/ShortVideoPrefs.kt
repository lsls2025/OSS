package com.aurora.chat.ui.profile

import android.content.Context

/**
 * 短视频播放偏好：全部永久保存在本机 SharedPreferences，进程重启/换页/重装前一直生效。
 *
 * 只存「用户的选择」，不存任何播放现场状态：
 *  - quality      画质上限（-1 自动，240/360/480/720/1080）
 *  - maxBitrate   码率上限（0 不限）
 *  - playMode     排序方式（看最新 / 综合 / 随机，默认随机）
 *  - autoNext     自动下滑（播完自动切下一条，默认开）
 *  - loopSingle   单条循环（与自动下滑互斥）
 *  - watchedIds   看过的作品（「综合」模式优先推没看过的）
 */
enum class SVPlayMode(val key: String, val label: String, val desc: String) {
    LATEST("latest", "看最新", "按发布时间从新到旧，一条接一条往下"),
    COMPREHENSIVE("comprehensive", "综合", "优先播放没看过的，看完再回看过的"),
    RANDOM("random", "随机", "每次进入重新打乱顺序");

    companion object {
        fun of(key: String?): SVPlayMode = values().firstOrNull { it.key == key } ?: RANDOM
    }
}

/** 码率上限候选（0 = 不限） */
data class SVBitrateOption(val bps: Int, val label: String)

val svBitrateOptions = listOf(
    SVBitrateOption(0, "不限"),
    SVBitrateOption(1_000_000, "1 Mbps"),
    SVBitrateOption(2_000_000, "2 Mbps"),
    SVBitrateOption(3_000_000, "3 Mbps"),
    SVBitrateOption(5_000_000, "5 Mbps"),
)

/**
 * 画质三档，与后端三档一一对应。码率不再单独暴露 —— 档位本身就决定了画质和体积。
 *
 *  480  = 低（省流量，画质让步）
 *  1080 = 中（肉眼接近源，体积可控）
 *  2160 = 高（原画：产物 = 源分辨率，CRF 18 视觉无损，不限码率）
 */
val svQualityOptions = listOf(480, 1080, 2160)

/** 档位展示名：低 / 中 / 高。 */
fun svQualityLabel(quality: Int): String = when {
    quality >= 2160 -> "高"
    quality >= 1080 -> "中"
    else -> "低"
}

object ShortVideoPrefs {
    private const val FILE = "short_video_prefs"
    private const val K_QUALITY = "pref_quality"
    private const val K_MAX_BITRATE = "pref_max_bitrate"
    private const val K_MODE = "play_mode"
    private const val K_AUTO_NEXT = "auto_next"
    private const val K_LOOP = "loop_single"
    private const val K_WATCHED = "watched_ids"
    private const val WATCHED_LIMIT = 800

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---------- 画质 / 码率 ----------
    // 默认「高」：平台定位是画质优先，宁可多耗带宽也不默认降清。
    // 想省流量的用户可以手动切到「低」。
    //
    // 同时做一次旧值迁移：老版本存的是 -1/240/360/480/720/1080/2160 七档，
    // 直接拿去三档里比对会匹配不到（画质选择会显示错乱）。
    fun quality(ctx: Context): Int {
        val v = sp(ctx).getInt(K_QUALITY, 2160)
        return when {
            v >= 2160 -> 2160
            v >= 1080 -> 1080
            v >= 480 -> 480
            v > 0 -> 480        // 240/360 归到低档
            else -> 2160        // 旧的「自动」统一按高档，不再让播放器偷偷降清
        }
    }
    fun setQuality(ctx: Context, q: Int) = sp(ctx).edit().putInt(K_QUALITY, q).apply()

    fun maxBitrate(ctx: Context): Int = sp(ctx).getInt(K_MAX_BITRATE, 0)
    fun setMaxBitrate(ctx: Context, bps: Int) = sp(ctx).edit().putInt(K_MAX_BITRATE, bps).apply()

    // ---------- 播放模式 ----------
    fun playMode(ctx: Context): SVPlayMode =
        SVPlayMode.of(sp(ctx).getString(K_MODE, null))

    fun setPlayMode(ctx: Context, mode: SVPlayMode) =
        sp(ctx).edit().putString(K_MODE, mode.key).apply()

    // ---------- 播放行为 ----------
    /** 自动下滑：一条播完自动切到下一条（短视频的默认习惯）。 */
    fun autoNext(ctx: Context): Boolean = sp(ctx).getBoolean(K_AUTO_NEXT, true)
    fun setAutoNext(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean(K_AUTO_NEXT, on).apply()

    /** 单条循环：播完重播本条。与自动下滑互斥，两个都关就是播完停住。 */
    fun loopSingle(ctx: Context): Boolean = sp(ctx).getBoolean(K_LOOP, false)
    fun setLoopSingle(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean(K_LOOP, on).apply()

    // ---------- 已看记录（「综合」排序依据） ----------
    fun watched(ctx: Context): Set<Long> {
        val raw = sp(ctx).getString(K_WATCHED, "") ?: ""
        if (raw.isEmpty()) return emptySet()
        return raw.split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()
    }

    fun markWatched(ctx: Context, id: Long) {
        if (id <= 0L) return
        val raw = sp(ctx).getString(K_WATCHED, "") ?: ""
        val list = raw.split(',').filter { it.isNotBlank() }.toMutableList()
        if (list.contains(id.toString())) return
        list.add(id.toString())
        val trimmed = if (list.size > WATCHED_LIMIT) list.subList(list.size - WATCHED_LIMIT, list.size) else list
        sp(ctx).edit().putString(K_WATCHED, trimmed.joinToString(",")).apply()
    }

    fun clearWatched(ctx: Context) = sp(ctx).edit().remove(K_WATCHED).apply()

    /** 恢复默认：自动画质 / 不限码率 / 随机排序 / 自动下滑 / 不循环。已看记录保留（那属于数据，不是设置）。 */
    fun reset(ctx: Context) {
        sp(ctx).edit()
            .remove(K_QUALITY).remove(K_MAX_BITRATE).remove(K_MODE)
            .remove(K_AUTO_NEXT).remove(K_LOOP)
            .apply()
    }
}

/**
 * 按播放模式排序 feed。
 * - 看最新：发布时间倒序（后端本来就是倒序，这里显式排一次，避免未来接口改动后顺序漂移）
 * - 综合：没看过的一律排在前面（各自仍按时间倒序），看过的沉到后面
 * - 随机：用本次进入时固定的种子打乱 —— 种子固定，滚动/重组都不会重新洗牌
 * 注意：这只是「进入时的快照排序」，播放过程中标记已看不重排，否则当前这条会被瞬间挪走导致跳页。
 */
fun svOrderFeed(
    list: List<SVItem>,
    mode: SVPlayMode,
    watched: Set<Long>,
    seed: Long
): List<SVItem> = when (mode) {
    SVPlayMode.LATEST -> list.sortedByDescending { it.createdAt }
    SVPlayMode.COMPREHENSIVE -> {
        val (seen, unseen) = list.partition { it.id in watched }
        unseen.sortedByDescending { it.createdAt } + seen.sortedByDescending { it.createdAt }
    }
    SVPlayMode.RANDOM -> list.shuffled(java.util.Random(seed))
}
