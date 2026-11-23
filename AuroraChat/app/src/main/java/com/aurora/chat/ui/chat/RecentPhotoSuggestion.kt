package com.aurora.chat.ui.chat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage

/** 一张「疑似想发」的新照片：相册里的真实 Uri + 展示名 + 加入相册时刻（秒）。 */
data class RecentPhoto(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val dateAdded: Long
)

/**
 * 新照片检测（微信 / QQ 同款：「+」面板展开时推荐最近新增的照片）。
 *
 * 规则：
 * 1. 只在已获得相册权限时工作；没权限就静默不推荐（不在这里弹权限框，避免打断发消息）。
 * 2. 只推荐「上次基线之后、24 小时内」新增的照片 —— 基线在用户把推荐发送/关掉时推进。
 * 3. 一天以前的旧照片不推荐，免得用户打开「+」就被推一张几个月前的截图。
 */
object RecentPhotoSuggestion {

    private const val PREFS = "aurora_recent_photo"
    private const val KEY_BASELINE = "baseline_sec"
    private const val KEY_LAST_ID = "last_prompted_id"

    /** 推荐的时间窗口：超出一整天就不再打扰。 */
    private const val MAX_AGE_SEC = 24 * 60 * 60L

    fun detect(ctx: Context): RecentPhoto? {
        if (!hasMediaPermission(ctx)) return null
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis() / 1000L
        val saved = prefs.getLong(KEY_BASELINE, 0L)
        if (saved <= 0L) {
            // 首次使用：以「此刻」为基线，只推荐以后新增的图片
            prefs.edit().putLong(KEY_BASELINE, now).apply()
            return null
        }
        val since = maxOf(saved, now - MAX_AGE_SEC) - 2 // 减 2 秒抹平 DATE_ADDED 的取整误差
        val lastId = prefs.getLong(KEY_LAST_ID, 0L)
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DISPLAY_NAME
        )
        return try {
            ctx.contentResolver.query(
                collection,
                projection,
                "${MediaStore.Images.Media.DATE_ADDED} >= ?",
                arrayOf(since.toString()),
                "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC"
            )?.use { c ->
                if (!c.moveToFirst()) return null
                val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                if (id <= 0L || id == lastId) return null
                val dateAdded = c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED))
                if (dateAdded <= 0L) return null
                val name = c.getString(c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)) ?: ""
                RecentPhoto(
                    id = id,
                    uri = android.content.ContentUris.withAppendedId(collection, id),
                    displayName = name,
                    dateAdded = dateAdded
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    /** 用户已经处理过这张照片（发送或手动关掉）：推进基线，之后不再重复推荐。 */
    fun markHandled(ctx: Context, photo: RecentPhoto) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // 基线推进到这张之后 1 秒，并记下 id 双重保险：同一条不会被「>= 基线 - 2」重新捞出来
        val baseline = maxOf(prefs.getLong(KEY_BASELINE, 0L), photo.dateAdded + 1)
        prefs.edit().putLong(KEY_BASELINE, baseline).putLong(KEY_LAST_ID, photo.id).apply()
    }

    private fun hasMediaPermission(ctx: Context): Boolean {
        val perms = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        return perms.any { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
    }
}

/**
 * 「你可能要发送的照片」悬浮提示：一张缩略图 + 一行文案，浮在「+」按钮上方，
 * 不参与输入栏布局（由调用方用 layout 偏移绘制）。点击进入全屏预览，不直接发送。
 */
@Composable
fun RecentPhotoSuggestCard(
    photo: RecentPhoto,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cardColor = MaterialTheme.colorScheme.surface
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End
    ) {
        Surface(
            modifier = Modifier
                .width(84.dp)
                .clickable { onClick() },
            shape = RoundedCornerShape(7.dp),
            color = cardColor,
            shadowElevation = 6.dp
        ) {
            Column(Modifier.padding(6.dp)) {
                Text(
                    text = "你可能要发送的照片",
                    fontSize = 8.sp,
                    lineHeight = 10.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Visible
                )
                Spacer(Modifier.height(3.dp))
                AsyncImage(
                    model = photo.uri,
                    contentDescription = "最近的照片",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(com.aurora.chat.ui.theme.appCardBg())
                )
            }
        }
        // 指向下方「+」按钮的小三角（与卡片同色，形成气泡）
        Canvas(
            modifier = Modifier
                .padding(end = 12.dp)
                .size(width = 8.dp, height = 4.dp)
        ) {
            val path = Path().apply {
                moveTo(0f, 0f)
                lineTo(size.width, 0f)
                lineTo(size.width / 2f, size.height)
                close()
            }
            drawPath(path, cardColor)
        }
    }
}
