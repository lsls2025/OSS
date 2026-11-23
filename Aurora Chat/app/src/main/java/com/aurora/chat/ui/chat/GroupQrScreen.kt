package com.aurora.chat.ui.chat
import com.aurora.chat.ui.theme.LocalAppColors

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.widget.Toast
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.chat.ui.qr.generateQrBitmap
import com.aurora.chat.ui.qr.saveBitmapToGallery
import com.aurora.chat.ui.qr.toCircleBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 群二维码全屏界面：
 * 左返回箭头 + 标题「群二维码」（样式与群聊信息一致），
 * 中部为 QQ 风格二维码卡片（群头像/群名/群号 + 大二维码 + 加群提示 + 品牌标识），
 * 底部保存/分享按钮，三处共用同一张卡片图。
 */
@Composable
fun GroupQrScreen(
    groupId: Long,
    groupName: String,
    displayId: Long,
    groupSignature: String,
    avatarBitmap: Bitmap?,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var qrFallback by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var showShareDialog by remember { mutableStateOf(false) }

    // 生成群二维码分享卡片：内容指向官网群落地页 /g.html?g=<群ID>（站点根目录下的静态页，见 site/g.html）。
    // 这个页面以前根本没被创建过，扫码只会 404；现在补齐了静态页，并保留后端 /api/qr/g 作为兜底
    // （静态页缺失时 nginx try_files 可转过去；两者取不到群信息都会 302 回官网首页，不会停在 404）。
    LaunchedEffect(groupId, avatarBitmap, groupName) {
        // 首帧先用缓存：离线/编码前立刻有一张看得见的、群号正确的二维码（用户要求「优先用上一次生成的」）
        val cached = com.aurora.chat.data.local.GroupQrStore.load(context, groupId)
        if (cached != null) qrBitmap = cached
        // 群号都没拿到（从未联网拉过且离线）→ 没有缓存也无法生成正确卡面，进兜底提示
        if (displayId == 0L && cached == null) {
            qrFallback = true
            return@LaunchedEffect
        }
        qrFallback = false
        qrBitmap = withContext(Dispatchers.Default) {
            composeGroupQrCard(
                groupName = groupName,
                displayId = displayId,
                avatar = avatarBitmap,
                qrContent = "https://example.com/g.html?g=$groupId"
            )
        }
        // 合成成功即落盘，下次离线直接复用
        qrBitmap?.let { com.aurora.chat.data.local.GroupQrStore.save(context, groupId, it) }
    }

    Box(Modifier.fillMaxSize().background(com.aurora.chat.ui.theme.appCardBg()).statusBarsPadding()) {
        // 事件穿透防御：全屏覆盖层必须先吃掉触摸，避免点击穿透到下层群设置列表
        com.aurora.chat.ui.components.EventBlocker(backgroundColor = com.aurora.chat.ui.theme.appCardBg())
        Column(Modifier.fillMaxSize()) {
            // ── 顶部栏（与群聊信息标题一致） ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(28.dp).clickable { onBack() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("←", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E40AF))
                }
                Spacer(Modifier.width(12.dp))
                Text("群二维码", fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    color = com.aurora.chat.ui.theme.appTextPrimary(), modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                // 右侧占位保持标题居中
                Spacer(Modifier.width(12.dp))
                Box(Modifier.size(28.dp))
            }

            Spacer(Modifier.weight(1f))

            // ── 中部：QQ 风格二维码卡片（群头像/群名/群号/二维码/提示全部在卡片图内） ──
            Box(Modifier.fillMaxWidth().padding(horizontal = 32.dp), contentAlignment = Alignment.Center) {
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .background(com.aurora.chat.ui.theme.appCardBg(), RoundedCornerShape(16.dp))
                        .padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (qrBitmap != null) {
                        val bmp = qrBitmap!!
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "群二维码",
                            modifier = Modifier.fillMaxWidth()
                                .aspectRatio(bmp.width.toFloat() / bmp.height.toFloat())
                        )
                    } else if (qrFallback) {
                        // 兜底：群号未知且无缓存，确实生成不出来，给出明确提示而不是空白/错码
                        Box(
                            Modifier.fillMaxWidth().aspectRatio(840f / 1150f)
                                .background(com.aurora.chat.ui.theme.appCardBg(), RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "暂无法生成群二维码\n请联网后重试",
                                fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextSecondary(),
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        Box(
                            Modifier.fillMaxWidth().aspectRatio(840f / 1150f)
                                .background(com.aurora.chat.ui.theme.appCardBg(), RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("二维码生成中...", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint())
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // ── 底部按钮：保存 / 分享 ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 保存到相册
                Box(
                    modifier = Modifier.weight(1f).height(46.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (saving) Color(0xFF93A5CF) else Color(0xFF1E40AF))
                        .clickable(enabled = qrBitmap != null && !saving) {
                            scope.launch {
                                saving = true
                                val ok = saveBitmapToGallery(context, qrBitmap!!, "aurora_group_qr_$displayId.png")
                                saving = false
                                Toast.makeText(context, if (ok) "已保存到相册" else "保存失败，请检查相册权限", Toast.LENGTH_SHORT).show()
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(if (saving) "保存中..." else "保存", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White)
                }
                // 分享给好友/群聊（不落相册）
                Box(
                    modifier = Modifier.weight(1f).height(46.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(com.aurora.chat.ui.theme.appSurface())
                        .clickable(enabled = qrBitmap != null) {
                            if (qrBitmap != null) showShareDialog = true
                            else Toast.makeText(context, "二维码生成中，请稍候", Toast.LENGTH_SHORT).show()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text("分享", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1E40AF))
                }
            }
        }

        // ── 分享抽屉：复用现有 ShareGroupDialog，以二维码图片消息发出 ──
        if (showShareDialog) {
            ShareGroupDialog(
                groupId = groupId,
                groupName = groupName,
                groupSignature = groupSignature,
                qrBitmap = qrBitmap,
                onDismiss = { showShareDialog = false }
            )
        }
    }
}

/**
 * 合成 QQ 风格的群二维码分享卡片（预览 / 保存 / 分享三处共用同一张图）：
 *
 *   ┌──────────────────────┐
 *   │       (群头像)        │
 *   │        群名称         │
 *   │      群号：123456      │
 *   │     ┌──────────┐     │
 *   │     │  二维码   │     │
 *   │     └──────────┘     │
 *   │  扫一扫二维码，加入群聊   │
 *   │     Aurora Chat      │
 *   └──────────────────────┘
 *
 * 白底圆角卡、四角透明；二维码不带中心图标（群头像已置于卡片顶部），
 * 内容为官网群落地页 /g.html?g=<群ID>，应用外扫码将打开独立的群邀请页。
 */
private fun composeGroupQrCard(
    groupName: String,
    displayId: Long,
    avatar: Bitmap?,
    qrContent: String
): Bitmap {
    val W = 840
    val pad = 64f
    val avatarSize = 128f
    val qrSize = 560

    val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1F2937.toInt()
        textSize = 46f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9CA3AF.toInt()
        textSize = 32f
        textAlign = Paint.Align.CENTER
    }
    val tipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF6B7280.toInt()
        textSize = 32f
        textAlign = Paint.Align.CENTER
    }
    val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1E40AF.toInt()
        textSize = 40f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    // 群名过长按卡片宽度截断（先测量再截，避免超出卡片）
    var name = if (groupName.isNotBlank()) groupName else "未命名群聊"
    val maxTextW = W - pad * 2
    if (namePaint.measureText(name) > maxTextW) {
        var s = name
        while (s.isNotEmpty() && namePaint.measureText("$s...") > maxTextW) s = s.dropLast(1)
        name = "$s..."
    }

    // 竖向排版高度：顶部留白 + 头像块 + 群名 + 群号 + 二维码 + 提示 + 品牌 + 底部留白
    val avatarBlock = if (avatar != null) avatarSize + 32f else 20f
    val H = (72f + avatarBlock + namePaint.textSize + 14f + subPaint.textSize + 44f +
        qrSize + 48f + tipPaint.textSize + 30f + brandPaint.textSize + 72f).toInt()

    val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)

    // 白底圆角卡（四角透明）
    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
    canvas.drawRoundRect(RectF(0f, 0f, W.toFloat(), H.toFloat()), 48f, 48f, bgPaint)

    val cx = W / 2f
    var y = 72f

    // 群头像（圆形）
    if (avatar != null) {
        val scaled = Bitmap.createScaledBitmap(avatar, avatarSize.toInt(), avatarSize.toInt(), true)
        val circle = toCircleBitmap(scaled)
        canvas.drawBitmap(circle, cx - avatarSize / 2f, y, null)
        y += avatarBlock
    } else {
        y += avatarBlock
    }

    // 群名 + 群号
    canvas.drawText(name, cx, y + namePaint.textSize, namePaint)
    y += namePaint.textSize + 14f
    canvas.drawText("群号：$displayId", cx, y + subPaint.textSize, subPaint)
    y += subPaint.textSize + 44f

    // 二维码 + 浅灰描边
    val qr = generateQrBitmap(qrContent, qrSize)
    val qrLeft = cx - qrSize / 2f
    canvas.drawBitmap(qr, qrLeft, y, null)
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE5E7EB.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    canvas.drawRoundRect(RectF(qrLeft, y, qrLeft + qrSize, y + qrSize), 16f, 16f, borderPaint)
    y += qrSize + 48f

    // 加群提示 + 品牌标识
    canvas.drawText("扫一扫二维码，加入群聊", cx, y + tipPaint.textSize, tipPaint)
    y += tipPaint.textSize + 30f
    canvas.drawText("Aurora Chat", cx, y + brandPaint.textSize, brandPaint)

    return out
}
