package com.aurora.chat.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aurora.chat.R
import com.aurora.chat.data.api.AuroraApi
import com.aurora.chat.data.local.LocalStorage
import com.aurora.chat.data.repository.ChatRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 统一的用户头像组件
 */
@Composable
fun UserAvatar(
    userId: Long,
    userName: String,
    size: Dp = 48.dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // 组合期先同步读内存缓存，已缓存头像立即显示，无需协程跳转
    var bitmap by remember(userId) { mutableStateOf(com.aurora.chat.data.local.AvatarCache.getCachedUserAvatarSync(userId)) }

    LaunchedEffect(userId) {
        if (bitmap == null) {
            // 磁盘解码/网络加载全部放到 IO 线程，避免滚动时卡主线程
            bitmap = withContext(Dispatchers.IO) {
                var result: Bitmap? = null
                // 自己的头像优先从本地 avatar.png 文件读取（秒加载）
                if (userId == AuroraApi.currentUserId) {
                    val localFile = LocalStorage.getMyAvatarFile(context, userId)
                    if (localFile.exists()) {
                        try {
                            val bmp = decodeSampledFile(localFile.absolutePath)
                            if (bmp != null && bmp.width >= 16 && bmp.height >= 16) {
                                result = bmp
                            }
                        } catch (_: Exception) {}
                    }
                }
                // 其他用户或本地文件不存在时，走 AvatarCache（内存→磁盘→网络）
                result ?: ChatRepository.loadAvatar(context, userId)
            }
        }
    }

    if (bitmap != null) {
        val imageBitmap = remember(bitmap) { bitmap!!.asImageBitmap() }
        Image(
            bitmap = imageBitmap,
            contentDescription = userName,
            modifier = modifier
                .size(size)
                .clip(RoundedCornerShape(size / 4)),
            contentScale = ContentScale.Crop
        )
    } else {
        Image(
            painter = painterResource(R.drawable.ic_profile),
            contentDescription = userName,
            modifier = modifier
                .size(size)
                .clip(RoundedCornerShape(size / 4))
        )
    }
}

/**
 * 群头像组件
 * - 有自定义头像 → 显示上传的群头像（优先本地磁盘缓存，再走网络）
 * - 无自定义头像 → 显示默认群头像（ic_group_default.jpg，即桌面0.jpg）
 * - 缓存策略：首次加载保存到本地磁盘，后续直接读取本地，无需等待网络
 */
@Composable
fun GroupAvatar(
    internalGroupId: Long,
    groupName: String,
    size: Dp = 48.dp,
    modifier: Modifier = Modifier,
    refreshKey: Int = 0
) {
    val context = LocalContext.current
    // 官方群（内部群 id=1，对应会话 id=-1001）强制渲染应用图标，无论上传过什么头像、被谁篡改，都固定显示应用图标
    if (internalGroupId == 1L) {
        Image(
            painter = painterResource(R.drawable.ic_app_icon),
            contentDescription = groupName,
            modifier = modifier
                .size(size)
                .clip(RoundedCornerShape(size / 4)),
            contentScale = ContentScale.Crop
        )
        return
    }
    // 组合期先同步读内存缓存，已缓存头像立即显示，无需协程跳转
    var bitmap by remember(internalGroupId) { mutableStateOf(com.aurora.chat.data.local.AvatarCache.getCachedGroupAvatarSync(internalGroupId)) }
    var hasCustomAvatar by remember(internalGroupId) { mutableStateOf(bitmap != null) }
    // 记录已加载过的 refreshKey，仅在无缓存或上传新头像（refreshKey 变化）时重载，避免全局 Map 泄漏
    var seenRefreshKey by remember(internalGroupId) { mutableStateOf(-1) }

    LaunchedEffect(internalGroupId, refreshKey) {
        // 仅在无缓存，或 refreshKey 变化（上传新头像后需重载）时才重新加载；滚动重进直接复用缓存
        if (bitmap == null || seenRefreshKey != refreshKey) {
            seenRefreshKey = refreshKey
            // 磁盘解码/网络加载全部放到 IO 线程，避免滚动时卡主线程
            val loaded = withContext(Dispatchers.IO) {
                // 1. 优先内存缓存（上传后立即存入）
                val memCached = com.aurora.chat.data.local.AvatarCache.getCachedUploadedGroupAvatar(internalGroupId)
                if (memCached != null) return@withContext memCached

                // 2. 本地磁盘缓存（瞬间加载）
                val diskCached = com.aurora.chat.data.local.AvatarCache.getGroupAvatar(context, internalGroupId)
                if (diskCached != null) return@withContext diskCached

                // 3. 从网络下载并缓存到本地
                ChatRepository.loadGroupAvatar(context, internalGroupId)
            }
            if (loaded != null) {
                bitmap = loaded
                hasCustomAvatar = true
            }
        }
    }

    if (hasCustomAvatar && bitmap != null) {
        val imageBitmap = remember(bitmap) { bitmap!!.asImageBitmap() }
        Image(
            bitmap = imageBitmap,
            contentDescription = groupName,
            modifier = modifier
                .size(size)
                .clip(RoundedCornerShape(size / 4)),
            contentScale = ContentScale.Crop
        )
    } else {
        // 无自定义头像：官方群(内部群id=1,对应会话id=-1001)显示应用图标，普通群显示默认群头像图片
        Image(
            painter = painterResource(
                if (internalGroupId == 1L) R.drawable.ic_app_icon
                else R.drawable.ic_group_default
            ),
            contentDescription = groupName,
            modifier = modifier
                .size(size)
                .clip(RoundedCornerShape(size / 4)),
            contentScale = ContentScale.Crop
        )
    }
}

/** 头像解码目标尺寸上限（px）。为保证高清不模糊，提高采样上限（4K 原图按需采样，仍能保护内存峰值） */
private const val AVATAR_TARGET_PX = 1024

/** 带采样解码本地头像文件（inJustDecodeBounds 先读尺寸 → inSampleSize 采样解码） */
internal fun decodeSampledFile(absolutePath: String, target: Int = AVATAR_TARGET_PX): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var inSampleSize = 1
    while (bounds.outWidth / inSampleSize >= target || bounds.outHeight / inSampleSize >= target) {
        inSampleSize *= 2
    }
    val opts = BitmapFactory.Options().apply { inSampleSize = inSampleSize }
    return BitmapFactory.decodeFile(absolutePath, opts)
}
