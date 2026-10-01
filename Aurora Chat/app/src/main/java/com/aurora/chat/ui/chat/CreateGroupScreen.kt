package com.aurora.chat.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.chat.data.api.AuroraApi
import com.aurora.chat.data.repository.ChatRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CreateGroupScreen(
    currentUserId: Long = 0,
    onBack: () -> Unit,
    onCreated: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var groupName by remember { mutableStateOf("") }
    var groupSignature by remember { mutableStateOf("") }
    var groupAnnouncement by remember { mutableStateOf("") }
    var avatarBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isCreating by remember { mutableStateOf(false) }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(it)?.use { input ->
                        BitmapFactory.decodeStream(input)
                    }
                }
                avatarBitmap = bmp
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        com.aurora.chat.ui.components.EventBlocker()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White)
        ) {
        // 顶部栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(28.dp).clickable { onBack() },
                contentAlignment = Alignment.Center
            ) {
                Text("←", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E40AF))
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = "创建群聊", fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                color = Color(0xFF1F2937)
            )
        }

        Spacer(Modifier.height(20.dp))

        // 群头像（正方圆角，与用户头像一致）
        Box(
            modifier = Modifier
                .size(80.dp)
                .align(Alignment.CenterHorizontally)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFFE5E7EB))
                .clickable { imagePicker.launch("image/*") },
            contentAlignment = Alignment.Center
        ) {
            if (avatarBitmap != null) {
                Image(
                    bitmap = avatarBitmap!!.asImageBitmap(),
                    contentDescription = "群头像",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Text(
                    text = "+", fontSize = 32.sp,
                    fontWeight = FontWeight.Light, color = Color(0xFF9CA3AF)
                )
            }
        }
        Text(
            text = "点击设置群头像",
            fontSize = 12.sp, color = Color(0xFF9CA3AF),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(24.dp))

        // 群名称
        InputField(
            label = "群名称",
            value = groupName,
            onValueChange = { groupName = it },
            placeholder = "请输入群聊名称"
        )

        Spacer(Modifier.height(16.dp))

        // 群签名
        InputField(
            label = "群签名",
            value = groupSignature,
            onValueChange = { groupSignature = it },
            placeholder = "请输入群签名（选填）"
        )

        Spacer(Modifier.height(16.dp))

        // 群公告
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            Text(
                text = "群公告",
                fontSize = 13.sp, fontWeight = FontWeight.Medium,
                color = Color(0xFF6B7280)
            )
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 100.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFFF9FAFB))
                    .padding(12.dp)
            ) {
                BasicTextField(
                    value = groupAnnouncement,
                    onValueChange = { groupAnnouncement = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 14.sp, color = Color(0xFF1F2937)
                    ),
                    singleLine = false,
                    minLines = 3, maxLines = 8,
                    decorationBox = { innerTextField ->
                        Box {
                            if (groupAnnouncement.isEmpty()) {
                                Text(
                                    "请输入群公告（选填）",
                                    fontSize = 14.sp, color = Color(0xFF9CA3AF)
                                )
                            }
                            innerTextField()
                        }
                    }
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // 群欢迎语开关
        var welcomeEnabled by remember { mutableStateOf(false) }
        var welcomeText by remember { mutableStateOf("") }
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "群欢迎语", fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    color = Color(0xFF6B7280)
                )
                Spacer(Modifier.weight(1f))
                androidx.compose.material3.Switch(
                    checked = welcomeEnabled,
                    onCheckedChange = { welcomeEnabled = it },
                    colors = androidx.compose.material3.SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFF1E40AF),
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = Color(0xFFD1D5DB)
                    )
                )
            }
            if (welcomeEnabled) {
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFFF9FAFB))
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    BasicTextField(
                        value = welcomeText,
                        onValueChange = { welcomeText = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 14.sp, color = Color(0xFF1F2937)
                        ),
                        singleLine = true,
                        decorationBox = { innerTextField ->
                            Box {
                                if (welcomeText.isEmpty()) {
                                    Text("输入新成员加入时的欢迎语", fontSize = 14.sp, color = Color(0xFF9CA3AF))
                                }
                                innerTextField()
                            }
                        }
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))

        // 创建按钮
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .height(48.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (isCreating) Color(0xFF93A8D0) else Color(0xFF1E40AF))
                .clickable(enabled = !isCreating && groupName.isNotBlank()) {
                    isCreating = true
                    scope.launch {
                        val result = ChatRepository.createGroup(
                            currentUserId = currentUserId,
                            name = groupName.trim(),
                            signature = groupSignature.trim(),
                            announcement = groupAnnouncement.trim(),
                            welcomeEnabled = welcomeEnabled,
                            welcomeText = welcomeText.trim()
                        )
                        isCreating = false
                        if (result.success) {
                            // 上传群头像（如果有）
                            if (avatarBitmap != null) {
                                val groupId = result.data?.optLong("id", 0) ?: 0L
                                if (groupId > 0) {
                                    ChatRepository.uploadGroupAvatarBitmap(groupId, avatarBitmap!!)
                                }
                            }
                            Toast.makeText(context, "创建成功", Toast.LENGTH_SHORT).show()
                            onCreated()
                        } else {
                            Toast.makeText(context, "创建失败: ${result.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (isCreating) "创建中..." else "创建",
                fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White
            )
        }
        }
    }
}

@Composable
private fun InputField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        Text(
            text = label,
            fontSize = 13.sp, fontWeight = FontWeight.Medium,
            color = Color(0xFF6B7280)
        )
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFFF9FAFB))
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 14.sp, color = Color(0xFF1F2937)
                ),
                singleLine = true,
                decorationBox = { innerTextField ->
                    Box {
                        if (value.isEmpty()) {
                            Text(placeholder, fontSize = 14.sp, color = Color(0xFF9CA3AF))
                        }
                        innerTextField()
                    }
                }
            )
        }
    }
}
