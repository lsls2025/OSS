package com.aurora.chat.ui.text

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.chat.ui.WebViewActivity

/** 需要从 URL 尾部剥离的字符（URL 中极少作为有效结尾的标点符号） */
private val URL_TRAILING_TRIM_CHARS = setOf(',', '.', '!', '?', ':', ';', ')', ']', '}', '>', '\'', '"', '*', '~')

/** 剥离 URL 尾部多余的标点字符 */
private fun String.trimUrlTrailing(): String {
    var url = this
    while (url.isNotEmpty() && url.last() in URL_TRAILING_TRIM_CHARS) {
        url = url.dropLast(1)
    }
    return url
}

/** Markdown 加粗标记（**...**，支持下划线 __ 变体），用于 AI 对话文本 */
private val BOLD_PATTERN = Regex("(?:\\*\\*(.+?)\\*\\*|__(.+?)__)", RegexOption.DOT_MATCHES_ALL)

/**
 * 检测文本中的网址，被空格或文本结尾截断的链接也能正确识别。
 * 同时智能剥离 URL 尾部多余的标点符号（如 ) 、]、！等），
 * 确保 "www.example.com)。" 中只有 www.example.com 被识别为 URL。
 *
 * 识别以下两类：
 * 1. 以 www. 开头的域名 (如 www.example.com、http://www.example.com)
 * 2. 裸域名 (如 example.com、https://example.com/path)
 *
 * https:// 前缀作为可选项合并到各分支中，确保 http://www.example.com
 * 作为一个整体被识别，不会被拆成 "http://" + "www.example.com"。
 *
 * 链接显示为蓝色带下划线，可点击跳转系统浏览器；其余文本保持原色。
 * 同时支持长按选中文本。
 */
private val URL_PATTERN = Regex(
    // www 域名（http:// 可选），支持端口和路径
    """(?:https?://)?www\.[a-zA-Z0-9-]+(?:\.[a-zA-Z]{2,})+(?::\d+)?(?:/[^\s]*)?""" +
    """|""" +
    // 裸域名（http:// 可选），支持端口和路径
    """(?:https?://)?[a-zA-Z0-9][a-zA-Z0-9.-]*\.(?:com|cn|net|org|edu|gov|io|me|top|xyz|app|dev|info|cc|tv|co|uk|jp|de|ru|fr|au|ca|in|biz|pro|mobi|name|club|shop|online|site|space|live|wiki|store|blog|vip|fun|cloud|digital|world|work)(?::\d+)?(?:/[^\s]*)?""",
    RegexOption.IGNORE_CASE
)

/**
 * 判断文本中是否包含 URL
 */
fun String.containsUrl(): Boolean = URL_PATTERN.containsMatchIn(this)

/**
 * 将原始文本转换为 AnnotatedString，自动高亮 URL 并附带点击注解。
 * 自动剥离 URL 尾部多余的标点符号，确保 "www.example.com)。" 中
 * 只有 www.example.com 被识别为可点击的 URL。
 * 
 * @param isGroup 是否为群聊
 * @param groupMemberNames 群成员用户名集合（仅群聊时使用）
 */
fun String.toUrlAnnotatedString(
    urlColor: Color = Color(0xFF1E40AF),
    defaultColor: Color = Color.Unspecified,
    isGroup: Boolean = false,
    groupMemberNames: Set<String> = emptySet(),
    markdown: Boolean = false
): AnnotatedString {
    return buildAnnotatedString {
        /** 仅处理普通文本 + @mentions（不含加粗），供 appendTextWithMentions 复用 */
        fun appendPlain(text: String) {
            var last = 0
            val mentionPattern = Regex("@\\S+")
            for (match in mentionPattern.findAll(text)) {
                // 普通文本（@ 之前）
                if (match.range.first > last) {
                    val seg = text.substring(last, match.range.first)
                    if (defaultColor != Color.Unspecified) {
                        withStyle(SpanStyle(color = defaultColor)) { append(seg) }
                    } else {
                        append(seg)
                    }
                }
                // @用户名   → 群聊中始终添加可点击注解（即使该用户不在当前成员列表）
                // 已知群成员显示蓝色粗体，非成员显示灰色粗体
                val mentionName = match.value.trimStart('@')
                val isValidMention = isGroup && groupMemberNames.contains(mentionName)
                if (isGroup) {
                    pushStringAnnotation("MENTION", mentionName)
                    if (isValidMention) {
                        pushStyle(SpanStyle(color = Color(0xFF1E40AF), fontWeight = FontWeight.Bold))
                    } else {
                        pushStyle(SpanStyle(color = Color(0xFF6B7280), fontWeight = FontWeight.Bold))
                    }
                    append(match.value)
                    pop()
                    pop()
                } else {
                    append(match.value)
                }
                last = match.range.last + 1
            }
            // 剩余的普通文本
            if (last < text.length) {
                val rem = text.substring(last)
                if (defaultColor != Color.Unspecified) {
                    withStyle(SpanStyle(color = defaultColor)) { append(rem) }
                } else {
                    append(rem)
                }
            }
        }

        /** 追加一段文本（普通颜色），并高亮其中的 @mentions 与 Markdown 加粗（仅 markdown=true 时） */
        fun appendTextWithMentions(text: String) {
            if (markdown) {
                // 先按 **加粗** / __加粗__ 拆分，普通段与加粗段交替
                var p = 0
                for (m in BOLD_PATTERN.findAll(text)) {
                    if (m.range.first > p) appendPlain(text.substring(p, m.range.first))
                    val inner = m.groupValues[1].ifEmpty { m.groupValues[2] }
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(inner) }
                    p = m.range.last + 1
                }
                if (p < text.length) appendPlain(text.substring(p))
            } else {
                appendPlain(text)
            }
        }

        var lastIndex = 0
        for (match in URL_PATTERN.findAll(this@toUrlAnnotatedString)) {
            val rawUrl = match.value
            val cleanUrl = rawUrl.trimUrlTrailing()
            val trimmedCount = rawUrl.length - cleanUrl.length

            // URL 之前的普通文本（含 @mentions / 加粗）
            if (match.range.first > lastIndex) {
                val segment = this@toUrlAnnotatedString.substring(lastIndex, match.range.first)
                appendTextWithMentions(segment)
            }

            // 高亮的干净 URL
            if (cleanUrl.isNotEmpty()) {
                pushStringAnnotation("URL", cleanUrl)
                pushStyle(
                    SpanStyle(
                        color = urlColor,
                        textDecoration = TextDecoration.Underline
                    )
                )
                append(cleanUrl)
                pop()
                pop()
            }

            // 被剥离的尾部标点
            if (trimmedCount > 0) {
                val trailing = rawUrl.substring(cleanUrl.length)
                if (defaultColor != Color.Unspecified) {
                    withStyle(SpanStyle(color = defaultColor)) { append(trailing) }
                } else {
                    append(trailing)
                }
            }

            lastIndex = match.range.last + 1
        }
        // 剩余文本（含 @mentions / 加粗）
        if (lastIndex < this@toUrlAnnotatedString.length) {
            appendTextWithMentions(this@toUrlAnnotatedString.substring(lastIndex))
        }
    }
}

/**
 * 支持 URL 高亮和点击跳转的文本组件。
 *
 * 关键设计：只有当文本中实际包含 URL 时才使用 ClickableText，
 * 否则使用普通 Text 以避免干扰父容器的点击事件。
 * 非 URL 区域的点击会调用 [onClick] 回调（如果有），
 * 供父容器（如消息气泡）处理自己的点击逻辑。
 *
 * @param text 原始文本
 * @param modifier 修饰符
 * @param urlColor URL 文本颜色
 * @param defaultColor 普通文本颜色（默认不覆盖，由 style 决定）
 * @param fontSize 字号（可透传）
 * @param fontWeight 字重
 * @param lineHeight 行高
 * @param selectable 是否可长按选择文本（默认 true）
 * @param maxLines 最大行数
 * @param clickable 是否让 URL 响应点击（true=使用 ClickableText；false=仅蓝色高亮，不拦截点击事件）
 * @param onClick 非 URL 区域点击时的回调（用于向父容器传递点击事件）
 * @param isGroup 是否为群聊（@mention仅在群聊中生效）
 * @param groupMemberNames 群成员用户名集合（仅群聊时使用）
 */
@Composable
fun UrlText(
    text: String,
    modifier: Modifier = Modifier,
    urlColor: Color = Color(0xFF1E40AF),
    defaultColor: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    selectable: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    clickable: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    onAtMentionClick: ((String) -> Unit)? = null,
    isGroup: Boolean = false,
    groupMemberNames: Set<String> = emptySet(),
    markdown: Boolean = false
) {
    val context = LocalContext.current
    val hasUrl = remember(text) { text.containsUrl() }
    val groupMembersKey = groupMemberNames.toList().sorted().joinToString(",")
    val annotatedText = remember(text, defaultColor, urlColor, isGroup, groupMembersKey, markdown) {
        text.toUrlAnnotatedString(urlColor = urlColor, defaultColor = defaultColor, isGroup = isGroup, groupMemberNames = groupMemberNames, markdown = markdown)
    }

    // URL 选择弹窗状态
    var showUrlChooser by remember { mutableStateOf(false) }
    var pendingUrl by remember { mutableStateOf("") }

    /** 在应用内打开 URL */
    fun openInternal(finalUrl: String) {
        try {
            val intent = Intent(context, WebViewActivity::class.java).apply {
                putExtra(WebViewActivity.EXTRA_URL, finalUrl)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) { }
    }

    /** 在系统浏览器中打开 URL */
    fun openExternal(finalUrl: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(finalUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) { }
    }

    val textContent = @Composable {
        if (hasUrl && clickable) {
            // 使用 Text + pointerInput 替代 ClickableText（ClickableText 会吞掉长按事件，
            // 导致外层 combinedClickable.onLongClick 收不到回调，链接消息无法弹出菜单）
            val layoutResult = remember { mutableStateOf<TextLayoutResult?>(null) }
            Text(
                text = annotatedText,
                style = TextStyle(
                    color = defaultColor,
                    fontSize = fontSize,
                    fontWeight = fontWeight,
                    lineHeight = lineHeight
                ),
                maxLines = maxLines,
                onTextLayout = { layoutResult.value = it },
                modifier = modifier.pointerInput(onClick, onLongPress) {
                    detectTapGestures(
                        onTap = { offset ->
                            val charOffset = layoutResult.value?.getOffsetForPosition(offset)
                                ?: return@detectTapGestures
                            val urlAnnotations = annotatedText.getStringAnnotations("URL", charOffset, charOffset)
                            if (urlAnnotations.firstOrNull() != null) {
                                val url = urlAnnotations.first().item
                                pendingUrl = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
                                showUrlChooser = true
                                return@detectTapGestures
                            }
                            val mentionAnnotations = annotatedText.getStringAnnotations("MENTION", charOffset, charOffset)
                            if (mentionAnnotations.firstOrNull() != null) {
                                onAtMentionClick?.invoke(mentionAnnotations.first().item)
                                return@detectTapGestures
                            }
                            onClick?.invoke()
                        },
                        onLongPress = { onLongPress?.invoke() }
                    )
                }
            )
        } else {
            // 无 URL 或 clickable=false
            val textStyle = TextStyle(
                color = defaultColor,
                fontSize = fontSize,
                fontWeight = fontWeight,
                lineHeight = lineHeight
            )
            val baseText: @Composable () -> Unit = {
                if (hasUrl) {
                    // 有 URL 但不可点击 → 用 AnnotatedString 仅显示蓝色
                    Text(text = annotatedText, style = textStyle, maxLines = maxLines, modifier = modifier)
                } else {
                    // 无 URL → 仍用 annotatedText（含 @mention 高亮）
                    Text(text = annotatedText, style = textStyle, maxLines = maxLines, modifier = modifier)
                }
            }
            if (clickable) {
                // 没有 URL 但仍需将 tap/longpress 传给父容器
                val layoutResult = remember { mutableStateOf<TextLayoutResult?>(null) }
                Text(
                    text = annotatedText,
                    style = textStyle,
                    maxLines = maxLines,
                    onTextLayout = { layoutResult.value = it },
                    modifier = modifier.pointerInput(onClick, onLongPress) {
                        detectTapGestures(
                            onTap = { offset ->
                                val charOffset = layoutResult.value?.getOffsetForPosition(offset)
                                    ?: return@detectTapGestures
                                val mentionAnnotations = annotatedText.getStringAnnotations("MENTION", charOffset, charOffset)
                                if (mentionAnnotations.firstOrNull() != null) {
                                    onAtMentionClick?.invoke(mentionAnnotations.first().item)
                                } else {
                                    onClick?.invoke()
                                }
                            },
                            onLongPress = { onLongPress?.invoke() }
                        )
                    }
                )
            } else {
                baseText()
            }
        }
    }

    if (selectable) {
        SelectionContainer {
            textContent()
        }
    } else {
        textContent()
    }

    // ── URL 选择弹窗 ──
    if (showUrlChooser && pendingUrl.isNotEmpty()) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { showUrlChooser = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("打开链接", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F2937))
                Spacer(Modifier.height(12.dp))
                Text(
                    pendingUrl, fontSize = 13.sp, color = Color(0xFF6B7280),
                    maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(20.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // 在应用内打开
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF1E40AF))
                            .clickable {
                                openInternal(pendingUrl)
                                showUrlChooser = false
                            }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("在应用内打开", fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.Medium)
                    }
                    // 在浏览器中打开
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFFF3F4F6))
                            .clickable {
                                openExternal(pendingUrl)
                                showUrlChooser = false
                            }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("在浏览器中打开", fontSize = 14.sp, color = Color(0xFF374151), fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}
