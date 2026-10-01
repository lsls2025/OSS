package com.aurora.chat.ui.components

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aurora.chat.R
import com.aurora.chat.data.repository.ChatRepository
import com.aurora.chat.ui.profile.CropPreviewDialog
import com.aurora.chat.ui.profile.createTempImageUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

@Composable
fun ForceAvatarDialog(
    onAvatarUploaded: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var avatarBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var showSourceDialog by remember { mutableStateOf(false) }
    var cropImageUri by remember { mutableStateOf<Uri?>(null) }
    var showCrop by remember { mutableStateOf(false) }
    var cameraPhotoUri by remember { mutableStateOf<Uri?>(null) }
    var isUploading by remember { mutableStateOf(false) }
    var uploadError by remember { mutableStateOf(false) }

    // 加载本地已有头像（如果存在）
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val uid = com.aurora.chat.data.api.AuroraApi.currentUserId
            val file = if (uid > 0) {
                com.aurora.chat.data.local.LocalStorage.getMyAvatarFile(context, uid)
            } else {
                File(context.filesDir, "avatar.png")
            }
            if (file.exists()) {
                avatarBitmap = BitmapFactory.decodeFile(file.absolutePath)
            }
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { cropImageUri = it; showCrop = true }
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success: Boolean ->
        if (success && cameraPhotoUri != null) { cropImageUri = cameraPhotoUri; showCrop = true }
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { val uri = createTempImageUri(context); cameraPhotoUri = uri; cameraLauncher.launch(uri) }
        else { Toast.makeText(context, "需要相机权限才能拍照", Toast.LENGTH_SHORT).show() }
    }
    val storagePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { galleryLauncher.launch("image/*") }
        else { Toast.makeText(context, "需要存储权限才能选择图片", Toast.LENGTH_SHORT).show() }
    }

    // 选择来源弹窗
    if (showSourceDialog) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { showSourceDialog = false }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Color.White, RoundedCornerShape(16.dp))
                    .padding(24.dp)
            ) {
                Text("选择头像", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937))
                Spacer(Modifier.height(20.dp))
                Text("相册上传", fontSize = 16.sp, color = Color(0xFF1F2937),
                    modifier = Modifier.fillMaxWidth().clickable {
                        showSourceDialog = false
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            if (context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                                galleryLauncher.launch("image/*")
                            else storagePermissionLauncher.launch(Manifest.permission.READ_MEDIA_IMAGES)
                        } else { galleryLauncher.launch("image/*") }
                    }.padding(vertical = 12.dp))
                Spacer(Modifier.height(8.dp))
                Text("拍照上传", fontSize = 16.sp, color = Color(0xFF1F2937),
                    modifier = Modifier.fillMaxWidth().clickable {
                        showSourceDialog = false
                        if (context.checkSelfPermission(Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            val uri = createTempImageUri(context); cameraPhotoUri = uri; cameraLauncher.launch(uri)
                        } else { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) }
                    }.padding(vertical = 12.dp))
            }
        }
    }

    // 裁剪界面
    if (showCrop && cropImageUri != null) {
        CropPreviewDialog(
            imageUri = cropImageUri!!, context = context,
            onCropConfirm = { cropped ->
                avatarBitmap = cropped; showCrop = false; cropImageUri = null; uploadError = false
            },
            onDismiss = { showCrop = false; cropImageUri = null }
        )
    }

    // 头像上传推荐弹窗（可关闭）
    Dialog(
        onDismissRequest = { onAvatarUploaded() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xCC000000)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 40.dp)
                    .background(Color.White, RoundedCornerShape(24.dp))
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 标题
                Text(
                    "设置头像",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1F2937)
                )
                Text(
                    "上传头像可以让好友更容易认出你",
                    fontSize = 14.sp,
                    color = Color(0xFF6B7280),
                    modifier = Modifier.padding(top = 4.dp)
                )

                Spacer(Modifier.height(28.dp))

                // 头像占位（正方圆角，可点击选择）
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xFFD1D5DB))
                        .clickable { showSourceDialog = true },
                    contentAlignment = Alignment.Center
                ) {
                    if (avatarBitmap != null) {
                        Image(
                            bitmap = avatarBitmap!!.asImageBitmap(),
                            contentDescription = "头像预览",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Image(
                            painter = painterResource(R.drawable.ic_profile),
                            contentDescription = "默认头像",
                            modifier = Modifier.fillMaxSize().padding(28.dp),
                            contentScale = ContentScale.Fit
                        )
                    }
                }

                // 点击上传提示
                Text(
                    "点击上传",
                    fontSize = 13.sp,
                    color = Color(0xFF9CA3AF),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 10.dp)
                )

                Spacer(Modifier.height(28.dp))

                // 错误提示
                if (uploadError) {
                    Text(
                        "上传失败，请重试",
                        fontSize = 13.sp,
                        color = Color(0xFFDC2626),
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                }

                // 保存按钮
                Button(
                    onClick = {
                        avatarBitmap?.let { bmp ->
                            isUploading = true
                            uploadError = false
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    try {
                                        val uid = com.aurora.chat.data.api.AuroraApi.currentUserId
                                        val file = if (uid > 0) {
                                            com.aurora.chat.data.local.LocalStorage.getMyAvatarFile(context, uid)
                                        } else {
                                            File(context.filesDir, "avatar.png")
                                        }
                                        FileOutputStream(file).use { out ->
                                            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                                        }
                                        ChatRepository.uploadAvatarBitmap(context, bmp)
                                    } catch (e: Exception) {
                                        false
                                    }
                                }
                                isUploading = false
                                if (result) {
                                    onAvatarUploaded()
                                } else {
                                    uploadError = true
                                }
                            }
                        }
                    },
                    enabled = avatarBitmap != null && !isUploading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1E40AF),
                        disabledContainerColor = Color(0xFFD1D5DB)
                    )
                ) {
                    Text(
                        if (isUploading) "上传中…" else "保存",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White
                    )
                }
                Spacer(Modifier.height(8.dp))
                // 跳过按钮（不强制）
                TextButton(
                    onClick = { onAvatarUploaded() },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("暂不设置", fontSize = 15.sp, color = Color(0xFF6B7280))
                }
            }
        }
    }
}
