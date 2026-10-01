package com.aurora.chat.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import com.caverock.androidsvg.SVG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

/**
 * 验证码数据
 * @param captchaId 服务端分配的 captcha_id
 * @param bitmap    渲染好的验证码 SVG 图片（140x50）
 */
data class CaptchaData(
    val captchaId: String,
    val bitmap: Bitmap
)

/**
 * 服务端 SVG 验证码渲染器
 *
 * 验证码图片由后端生成（5x7 点阵字体 + 随机颜色 + 噪点 + 干扰线），
 * 客户端仅负责将 base64 SVG 解码为 Bitmap 并显示，不参与出题和校验。
 */
object CaptchaGenerator {

    const val WIDTH = 140
    const val HEIGHT = 50

    /**
     * 将服务端返回的 base64 SVG 数据渲染为 Bitmap
     * @param imageBase64 格式为 "data:image/svg+xml;base64,xxx"
     * @return 渲染好的 Bitmap（140x50），失败时返回 null
     */
    suspend fun renderImage(imageBase64: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            // 提取纯 base64 部分
            val b64 = if (imageBase64.contains(",")) {
                imageBase64.substringAfter(",")
            } else {
                imageBase64
            }
            val svgBytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            val svg = SVG.getFromInputStream(ByteArrayInputStream(svgBytes))

            val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            // 先渲染到 Picture，再以显式矩形绘制到 Bitmap，确保缩放正确
            svg.setDocumentWidth(WIDTH.toFloat())
            svg.setDocumentHeight(HEIGHT.toFloat())
            svg.renderToCanvas(canvas)
            bitmap
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * 验证码图片组件
 * @param captchaData 当前验证码数据
 * @param onRefresh 点击刷新回调
 * @param modifier 修饰符
 */
@Composable
fun CaptchaView(
    captchaData: CaptchaData,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    Image(
        bitmap = captchaData.bitmap.asImageBitmap(),
        contentDescription = "验证码",
        modifier = modifier.clickable { onRefresh() }
    )
}
