package com.aurora.chat.ui.community
import com.aurora.chat.ui.theme.LocalAppColors

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.chat.ui.chat.ShareCard
import com.aurora.chat.ui.chat.ShareCardData
import com.aurora.chat.ui.chat.isShareCardMessage
import com.aurora.chat.ui.chat.parseShareCard
import com.aurora.chat.ui.text.UrlText

/** 帖子正文中挂载的一张卡片：解析结果 + 原始文本块（用于复用 ShareCard 渲染）。 */
data class PostAttachedCard(val data: ShareCardData, val rawText: String)

/** 帖子正文拆分结果：普通文本部分 + 末尾挂载的卡片列表。 */
data class PostContentParts(val body: String, val cards: List<PostAttachedCard>)

/** 卡片文本块末尾的 id 行，如 group_id=12 / user_id=34。 */
private val CARD_ID_LINE = Regex("^(post_id|group_id|file_id|user_id)=\\d+$")

/**
 * 把帖子 content 拆成「正文文本」与「末尾挂载卡片」两部分。
 *
 * 序列化时卡片块统一追加在正文之后，每块以「分享群聊/分享用户…」开头、以 `xxx_id=` 行结束。
 * 先把第一段可解析的卡片块作为分界：其之前是正文，之后依次解析所有卡片块。
 * 解析失败（非结构化文本）的块会被忽略，保证异常文本不会导致卡片误渲染或崩溃。
 */
fun extractPostCards(content: String): PostContentParts {
    val lines = content.lines()
    var firstCard = -1
    for (i in lines.indices) {
        if (isShareCardMessage(lines[i]) && tryBuildCardBlock(lines, i) != null) {
            firstCard = i
            break
        }
    }
    if (firstCard < 0) return PostContentParts(content, emptyList())

    val body = lines.subList(0, firstCard).joinToString("\n")
    val cards = mutableListOf<PostAttachedCard>()
    var i = firstCard
    while (i < lines.size) {
        val built = tryBuildCardBlock(lines, i)
        if (built != null) {
            cards.add(built.first)
            i = built.second + 1
        } else {
            i++
        }
    }
    return PostContentParts(body, cards)
}

/**
 * 尝试从 [start] 行起解析一个卡片块。
 * @return 解析成功返回 (卡片, 结束行下标)；否则返回 null。
 */
private fun tryBuildCardBlock(lines: List<String>, start: Int): Pair<PostAttachedCard, Int>? {
    var end = -1
    for (j in start until lines.size) {
        if (lines[j].trim().matches(CARD_ID_LINE)) {
            end = j
            break
        }
    }
    if (end < 0) return null
    val raw = lines.subList(start, end + 1).joinToString("\n")
    val data = parseShareCard(raw) ?: return null
    return PostAttachedCard(data, raw) to end
}

/**
 * 帖子正文渲染：正文（自动高亮 URL）+ 挂载卡片（复用 ShareCard，白底加边框、隐藏"来自"）。
 *
 * @param onUserCardClick 点击用户卡 → (userId, 用户名)
 * @param onGroupCardClick 点击群卡 → (内部群 id, 群名)
 * @param compactCards 卡片紧凑尺寸（信息流预览用）
 * @param horizontalCards 卡片横向排列（像图片画廊一样，用于信息流预览，避免多张卡片竖向堆叠把卡片撑得很长）
 */
@Composable
fun PostContentWithCards(
    parts: PostContentParts,
    onUserCardClick: (Long, String) -> Unit,
    onGroupCardClick: (Long, String) -> Unit,
    modifier: Modifier = Modifier,
    bodyFontSize: TextUnit = 15.sp,
    bodyLineHeight: TextUnit = 22.sp,
    bodyMaxLines: Int = Int.MAX_VALUE,
    bodyColor: Color = com.aurora.chat.ui.theme.appTextSecondary(),
    linkColor: Color = Color(0xFF1E40AF),
    bodySelectable: Boolean = false,
    bodyClickable: Boolean = true,
    compactCards: Boolean = false,
    horizontalCards: Boolean = false
) {
    Column(modifier = modifier) {
        if (parts.body.isNotBlank()) {
            UrlText(
                text = parts.body,
                urlColor = linkColor,
                defaultColor = bodyColor,
                fontSize = bodyFontSize,
                lineHeight = bodyLineHeight,
                maxLines = bodyMaxLines,
                selectable = bodySelectable,
                clickable = bodyClickable
            )
        }

        if (parts.cards.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            if (horizontalCards) {
                // 横向排列，像图片一样可左右滑动；避免多张卡片竖向堆叠
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(parts.cards) { attached ->
                        AttachedCardItem(attached, onUserCardClick, onGroupCardClick, compactCards)
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    parts.cards.forEach { attached ->
                        AttachedCardItem(attached, onUserCardClick, onGroupCardClick, compactCards)
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachedCardItem(
    attached: PostAttachedCard,
    onUserCardClick: (Long, String) -> Unit,
    onGroupCardClick: (Long, String) -> Unit,
    compact: Boolean
) {
    ShareCard(
        text = attached.rawText,
        isMine = false,
        onUserClick = { id -> onUserCardClick(id, attached.data.title) },
        onGroupClick = { id -> onGroupCardClick(id, attached.data.title) },
        framed = true,
        showFrom = false,
        compact = compact
    )
}
