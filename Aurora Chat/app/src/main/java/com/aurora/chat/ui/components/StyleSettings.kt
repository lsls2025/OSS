package com.aurora.chat.ui.components

import android.content.Context
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * 头像圆角风格（消息设置 →「头像显示」）。
 *
 * - [MODE_DEFAULT] 默认小圆角：`size / 8`（38dp 头像约 4.8dp），即当前这一套；
 * - [MODE_CLASSIC] 经典大圆角：`size / 4`（38dp 头像约 9.5dp），也就是这一大轮 UI 改动之前的样式；
 * - [MODE_CUSTOM]  自定义：用户自己拖圆角（dp），上限不超过头像边长的一半，避免拖成圆形后无法回退。
 *
 * 圆角一律按「头像边长」换算，所以聊天头像、列表头像、群头像、AI 头像虽然尺寸不同，
 * 观感比例仍保持一致 —— 不需要每个调用点写死一个 dp。
 */
object AvatarStyleStorage {
    const val MODE_DEFAULT = 0
    const val MODE_CLASSIC = 1
    const val MODE_CUSTOM = 2

    const val CUSTOM_MIN = 0f
    const val CUSTOM_MAX = 24f
    private const val CUSTOM_FALLBACK = 8f

    private const val PREFS = "aurora_avatar_style"

    /** 修改计数：写设置时自增，Compose 读到它就会重组，改完返回聊天页立即生效 */
    var version by mutableIntStateOf(0)
        private set

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun mode(context: Context): Int =
        prefs(context).getInt("mode", MODE_DEFAULT).coerceIn(MODE_DEFAULT, MODE_CUSTOM)

    fun setMode(context: Context, value: Int) {
        prefs(context).edit().putInt("mode", value.coerceIn(MODE_DEFAULT, MODE_CUSTOM)).apply()
        version++
    }

    fun customDp(context: Context): Float =
        prefs(context).getFloat("custom_dp", CUSTOM_FALLBACK).coerceIn(CUSTOM_MIN, CUSTOM_MAX)

    fun setCustomDp(context: Context, value: Float) {
        prefs(context).edit().putFloat("custom_dp", value.coerceIn(CUSTOM_MIN, CUSTOM_MAX)).apply()
        version++
    }

    /** 按头像边长算圆角：默认 size/8、经典 size/4、自定义固定 dp（封顶 size/2）。 */
    fun cornerFor(context: Context, size: Dp): Dp = when (mode(context)) {
        MODE_CLASSIC -> size / 4
        MODE_CUSTOM -> customDp(context).dp.coerceAtMost(size / 2)
        else -> size / 8
    }

    fun label(context: Context): String = when (mode(context)) {
        MODE_CLASSIC -> "经典大圆角"
        MODE_CUSTOM -> "自定义 ${customDp(context).roundToInt()}dp"
        else -> "默认小圆角"
    }

    /** 设置页里的示例值（38dp 聊天头像对应的实际圆角），用于展示当前数值。 */
    fun previewDp(context: Context): Float = cornerFor(context, 38.dp).value
}

/** 头像圆角（跟随设置即时重组）。调用方只需给头像边长。 */
@Composable
fun avatarCornerRadius(size: Dp): Dp {
    val context = LocalContext.current
    // 读一次版本号：设置页改完 → version++ → 这里重组 → 圆角立刻跟着变
    AvatarStyleStorage.version
    return AvatarStyleStorage.cornerFor(context, size)
}

/**
 * 头像统一裁圆角：`Modifier.size(40.dp).avatarClip(40.dp)`。
 *
 * 以后新加头像一律用这个，不要再写死 `RoundedCornerShape(10.dp)` ——
 * 否则「消息设置 → 头像显示」在那一处就会失效，又得回头补。
 */
@Composable
fun Modifier.avatarClip(size: Dp): Modifier = this.clip(RoundedCornerShape(avatarCornerRadius(size)))

/**
 * 气泡圆角（消息设置 →「气泡设置」→「圆角」）。
 *
 * - [MODE_DEFAULT] 默认小圆角 8dp（比原来的 12dp 收了一圈）；
 * - [MODE_CLASSIC] 经典 12dp，也就是原来的样子；
 * - [MODE_CUSTOM]  自定义 0~24dp。
 *
 * 只作用于文字气泡；图片 / 视频缩略图有自己的圆角（6dp），不跟这里联动。
 */
object BubbleRadiusStorage {
    const val MODE_DEFAULT = 0
    const val MODE_CLASSIC = 1
    const val MODE_CUSTOM = 2

    const val DEFAULT_DP = 8f
    const val CLASSIC_DP = 12f
    const val CUSTOM_MIN = 0f
    const val CUSTOM_MAX = 24f

    private const val PREFS = "aurora_bubble_radius"

    var version by mutableIntStateOf(0)
        private set

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun mode(context: Context): Int =
        prefs(context).getInt("mode", MODE_DEFAULT).coerceIn(MODE_DEFAULT, MODE_CUSTOM)

    fun setMode(context: Context, value: Int) {
        prefs(context).edit().putInt("mode", value.coerceIn(MODE_DEFAULT, MODE_CUSTOM)).apply()
        version++
    }

    fun customDp(context: Context): Float =
        prefs(context).getFloat("custom_dp", DEFAULT_DP).coerceIn(CUSTOM_MIN, CUSTOM_MAX)

    fun setCustomDp(context: Context, value: Float) {
        prefs(context).edit().putFloat("custom_dp", value.coerceIn(CUSTOM_MIN, CUSTOM_MAX)).apply()
        version++
    }

    fun cornerDp(context: Context): Dp = when (mode(context)) {
        MODE_CLASSIC -> CLASSIC_DP.dp
        MODE_CUSTOM -> customDp(context).dp
        else -> DEFAULT_DP.dp
    }

    fun cornerValue(context: Context): Float = cornerDp(context).value

    fun label(context: Context): String = when (mode(context)) {
        MODE_CLASSIC -> "经典圆角（12dp）"
        MODE_CUSTOM -> "自定义 ${customDp(context).roundToInt()}dp"
        else -> "默认圆角（8dp）"
    }
}

/** 当前气泡圆角（跟随设置即时重组）。 */
@Composable
fun bubbleCornerRadius(): Dp {
    val context = LocalContext.current
    BubbleRadiusStorage.version
    return BubbleRadiusStorage.cornerDp(context)
}

/**
 * 气泡尖角（消息设置 →「气泡设置」→「圆角」页底部的勾选框）。
 *
 * 勾了 → 文字气泡侧边长一个小尖角（自己发的朝右、对方发的朝左）；
 * 不勾 → 就是普通圆角矩形，默认**关闭**。
 *
 * 只作用于文字气泡：图片、视频、文件卡片、各种邀请卡片都不长尖角（微信也是这么做的）。
 * 「液态」玻璃气泡同样不加尖角——它的折射轮廓需要贴合整个边界，多个尖角会让折射取样出错。
 */
object BubbleTailStorage {
    private const val PREFS = "aurora_bubble_tail"
    private const val KEY_ENABLED = "enabled"

    /** 修改计数：写设置时自增，聊天页读到它就会重组，勾完立即生效 */
    var version by mutableIntStateOf(0)
        private set

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, value).apply()
        version++
    }

    fun label(context: Context): String = if (enabled(context)) "凸起" else "无凸起"
}

/** 当前是否开启气泡尖角（跟随设置即时重组）。 */
@Composable
fun bubbleTailEnabled(): Boolean {
    val context = LocalContext.current
    BubbleTailStorage.version
    return BubbleTailStorage.enabled(context)
}
