package com.aurora.chat.ui.qr

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.chat.data.api.AuroraApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MyQrScreen(
    userId: Long,
    userName: String,
    onBack: () -> Unit,
    onScanClick: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshKey by remember { mutableStateOf(0) }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var saving by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }

    // 占位：读取本地已上传头像文件（与"我的"界面一致，始终是最新上传的图）
    fun loadQrLocalAvatar(context: android.content.Context, userId: Long): android.graphics.Bitmap? =
        runCatching {
            val f = com.aurora.chat.data.local.LocalStorage.getMyAvatarFile(context, userId)
            if (f.exists()) {
                android.graphics.BitmapFactory.decodeFile(f.absolutePath)
                    ?.takeIf { it.width >= 16 && it.height >= 16 }
            } else null
        }.getOrNull()

    // 硬拉取服务器头像（loadAvatarBytes 内部用缓存戳 URL，强制走服务器，绕开本地缓存）
    suspend fun loadQrServerAvatar(context: android.content.Context, userId: Long): android.graphics.Bitmap? =
        runCatching {
            val bytes = com.aurora.chat.data.api.AuroraApi.loadAvatarBytes(userId) ?: return null
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?.takeIf { it.width >= 16 && it.height >= 16 }
        }.getOrNull()

    // 兜底应用图标，避免中心空白
    fun qrAppIcon(context: android.content.Context): android.graphics.Bitmap? =
        runCatching {
            android.graphics.BitmapFactory.decodeResource(context.resources, com.aurora.chat.R.mipmap.ic_launcher)
        }.getOrNull()

    // 从服务器获取二维码内容并生成 Bitmap（中心叠加用户真实头像，无头像时用应用图标兜底）
    LaunchedEffect(refreshKey) {
        loading = true
        val result = AuroraApi.getMyQrCode()
        if (result.success && result.data != null) {
            val content = result.data!!
            // 1) 先用"当前头像"占位：优先本地已上传头像（与"我的"界面一致，避免闪回默认/旧头像），
            //    其次服务器，再兜底应用图标，保证中心不为空白。
            val placeholderIcon = withContext(Dispatchers.IO) {
                loadQrLocalAvatar(context, userId)
                    ?: loadQrServerAvatar(context, userId)
                    ?: qrAppIcon(context)
            }
            qrBitmap = withContext(Dispatchers.IO) { generateQrBitmap(content, 720, placeholderIcon) }
            loading = false
            // 2) 硬拉取服务器最新头像（缓存戳 URL 强制拉新），到达后立即覆盖之前的头像。
            val freshIcon = withContext(Dispatchers.IO) { loadQrServerAvatar(context, userId) }
            if (freshIcon != null) {
                qrBitmap = withContext(Dispatchers.IO) { generateQrBitmap(content, 720, freshIcon) }
            }
        } else {
            loading = false
            toast(context, result.message)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF5F6F8))
            .statusBarsPadding()
    ) {
        // 顶部栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clickable { onBack() },
                contentAlignment = Alignment.Center
            ) {
                Text("‹", fontSize = 28.sp, color = Color(0xFF1F2937))
            }
            Spacer(Modifier.width(8.dp))
            Text("我的二维码", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937))
        }

        Spacer(Modifier.height(24.dp))

        // 昵称
        Text(
            text = userName.ifEmpty { "Aurora 用户" },
            fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF111827),
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Text(
            text = "ID: $userId",
            fontSize = 13.sp, color = Color(0xFF9CA3AF),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )

        Spacer(Modifier.height(28.dp))

        // 大二维码（点击刷新）
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            if (qrBitmap != null) {
                Image(
                    bitmap = qrBitmap!!.asImageBitmap(),
                    contentDescription = "我的二维码",
                    modifier = Modifier
                        .size(260.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                        .clickable {
                            refreshKey++
                            toast(context, "刷新中…")
                        }
                        .padding(16.dp)
                )
            } else if (loading) {
                Text("加载中…", fontSize = 14.sp, color = Color(0xFF9CA3AF))
            } else {
                Text("加载失败，点击重试", fontSize = 14.sp, color = Color(0xFF9CA3AF),
                    modifier = Modifier.clickable { refreshKey++ })
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            text = "点击二维码可刷新",
            fontSize = 12.sp, color = Color(0xFF9CA3AF),
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )

        Spacer(Modifier.weight(1f))

        // 底部按钮：保存 / 扫一扫
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 24.dp)
                .navigationBarsPadding(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.White)
                    .clickable(enabled = !saving && qrBitmap != null) {
                        saving = true
                        scope.launch {
                            val ok = saveBitmapToGallery(context, qrBitmap!!, "aurora_qr_$userId.png")
                            withContext(Dispatchers.Main) {
                                toast(context, if (ok) "已保存到相册" else "保存失败")
                                saving = false
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(if (saving) "保存中…" else "保存", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color(0xFF2563EB))
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xFF2563EB))
                    .clickable { onScanClick() },
                contentAlignment = Alignment.Center
            ) {
                Text("扫一扫", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color.White)
            }
        }
    }
}
