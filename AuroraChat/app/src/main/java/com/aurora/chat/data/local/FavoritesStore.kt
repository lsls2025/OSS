package com.aurora.chat.data.local

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.aurora.chat.data.api.HttpClient
import com.aurora.chat.data.api.PostListItem
import com.aurora.chat.ui.chat.ChatMsg
import com.aurora.chat.ui.chat.IMessageRef
import com.aurora.chat.ui.chat.isVideoMediaUrl
import com.aurora.chat.ui.chat.resolveMediaUrl
import com.aurora.chat.ui.community.extractPostCards
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 收藏分类。
 *
 * 顺序即收藏页分类栏从左到右的顺序（「全部」由页面自己补在最前）：
 * 文本 → 图片 → 视频 → 社区 → 其他。
 *
 * 「其他」只是兜底：语音、文件等无法归类的才放这里；
 * 聊天文本进「文本」，社区帖子一律进「社区」（不管帖子带图还是带视频），短视频/视频消息进「视频」。
 */
enum class FavoriteCategory(val id: String, val label: String) {
    TEXT("text", "文本"),
    IMAGE("image", "图片"),
    VIDEO("video", "视频"),
    COMMUNITY("community", "社区"),
    OTHER("other", "其他");

    companion object {
        fun of(id: String): FavoriteCategory = entries.firstOrNull { it.id == id } ?: OTHER
    }
}

/**
 * 一条收藏记录。
 *
 * 关键设计：图片/视频/文件在收藏时**复制到本机**（[localFiles]），
 * 不依赖服务器原链接 —— 服务端聊天媒体/社区文件有 30 天清理机制，
 * 只存 URL 的收藏过一阵就会变成裂图/失效。
 */
data class FavoriteItem(
    /** 去重键：sv:<视频id> / post:<帖子id> / msg:<消息id> */
    val key: String,
    val category: String,
    val title: String = "",
    val subtitle: String = "",
    /** 文本正文（文本消息 / 帖子正文），媒体消息为空 */
    val text: String = "",
    /** 已复制到本机的媒体文件绝对路径（图片/视频/语音/文件） */
    val localFiles: List<String> = emptyList(),
    /** 远程兜底地址（本地复制失败时仍可展示/播放） */
    val remoteUrl: String = "",
    /** 视频封面（本机生成的 jpg 路径），图片类型可留空直接用原图 */
    val thumbnail: String = "",
    /** 来源对象 id：短视频 id / 帖子 id / 消息 id */
    val sourceId: Long = 0,
    /** shortvideo / post / chat */
    val sourceType: String = "",
    /** 聊天消息发送者用户 id（收藏页渲染头像用；帖子/短视频为 0） */
    val senderId: Long = 0,
    /** 聊天记录收藏（bundle）：整段对话消息快照 JSON（[{m,n,t,u,x,lf}]），普通收藏为空 */
    val bundleJson: String = "",
    val createdAt: Long = 0
) {
    val categoryEnum: FavoriteCategory get() = FavoriteCategory.of(category)

    /** 展示用主图/封面：本地缩略图 > 本地图片 > 远程地址 */
    fun coverModel(): Any? {
        if (thumbnail.isNotBlank() && File(thumbnail).exists()) return File(thumbnail)
        localFiles.firstOrNull {
            File(it).exists() && it.substringAfterLast('.', "").lowercase() in
                setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
        }?.let { return File(it) }
        if (remoteUrl.isNotBlank()) return remoteUrl
        return null
    }
}

enum class FavoriteAddResult { ADDED, EXISTS, FAILED }

/**
 * 统一收藏存储（全本机，落盘）。
 *
 * 存储位置：
 *  - 索引：filesDir/favorites/favorites.json（临时文件 + rename 原子写）
 *  - 媒体：filesDir/favorites/media/（收藏时从服务器/本地复制过来，永久保留）
 *
 * 短视频收藏仍由原有 SVFavoriteStore 维护（SharedPreferences），
 * 收藏页读取时与新收藏合并展示，避免迁移旧数据造成丢失。
 */
object FavoritesStore {
    private const val DIR = "favorites"
    private const val MEDIA = "media"
    private const val FILE = "favorites.json"
    /** 单个收藏媒体上限：200MB，避免误收藏超大文件把手机撑爆 */
    private const val MAX_MEDIA_BYTES = 200L * 1024 * 1024
    private val lock = Any()

    /** 按账号隔离：同一台设备切换账号后不会看到别人的收藏。 */
    private fun dir(ctx: Context) = File(File(ctx.filesDir, DIR), com.aurora.chat.data.api.AuroraApi.currentUserId.toString())
        .apply { if (!exists()) mkdirs() }
    private fun mediaDir(ctx: Context) = File(dir(ctx), MEDIA).apply { if (!exists()) mkdirs() }
    private fun indexFile(ctx: Context) = File(dir(ctx), FILE)

    fun all(ctx: Context): List<FavoriteItem> = synchronized(lock) { read(ctx) }

    fun has(ctx: Context, key: String): Boolean = synchronized(lock) { read(ctx).any { it.key == key } }

    fun remove(ctx: Context, key: String) {
        synchronized(lock) {
            val list = read(ctx)
            val target = list.firstOrNull { it.key == key } ?: return
            target.localFiles.forEach { runCatching { File(it).delete() } }
            if (target.thumbnail.isNotBlank()) runCatching { File(target.thumbnail).delete() }
            write(ctx, list.filterNot { it.key == key })
        }
    }

    /** 切换短视频收藏（沿用原入口的语义），返回切换后是否已收藏。 */
    fun toggleShortVideo(
        ctx: Context,
        id: Long,
        title: String,
        publisherId: Long,
        publisherName: String
    ): Boolean = synchronized(lock) {
        val key = "sv:$id"
        val list = read(ctx).toMutableList()
        val existed = list.any { it.key == key }
        if (existed) {
            list.removeAll { it.key == key }
        } else {
            list.add(
                0,
                FavoriteItem(
                    key = key,
                    category = FavoriteCategory.VIDEO.id,
                    title = title.ifEmpty { "短视频" },
                    subtitle = publisherName,
                    sourceId = id,
                    sourceType = "shortvideo",
                    createdAt = System.currentTimeMillis()
                )
            )
        }
        write(ctx, list)
        !existed
    }

    fun isShortVideoFav(ctx: Context, id: Long): Boolean = has(ctx, "sv:$id")

    /**
     * 收藏一条聊天消息。
     *
     * 支持：文本、图片、视频、语音、文件；闪照 / 表情 / 系统通知 / 已撤回 / 上传中一律不收藏。
     * 图片/视频/语音/文件会在 IO 线程复制到本机，服务器清理后依然可见。
     */
    suspend fun addMessage(ctx: Context, msg: IMessageRef): FavoriteAddResult = withContext(Dispatchers.IO) {
        if (!canFavoriteMessage(msg)) return@withContext FavoriteAddResult.FAILED
        // 「分享聊天」卡片：收藏为整段聊天记录（bundle），而不是把卡片当文本
        if (com.aurora.chat.ui.chat.isShareCardMessage(msg.text)) {
            val card = com.aurora.chat.ui.chat.parseShareCard(msg.text)
            if (card?.targetType == "chat" && card.chatMessages.isNotEmpty()) {
                return@withContext addBundleFromChatCard(ctx, card)
            }
            return@withContext FavoriteAddResult.FAILED
        }
        val key = "msg:${if (msg.serverId > 0) msg.serverId else msg.id}"
        if (has(ctx, key)) return@withContext FavoriteAddResult.EXISTS

        val type = msg.mediaType.lowercase()
        val rawUrl = msg.mediaUrl
        val sender = if (msg.isMine) "我" else msg.senderName.ifBlank { "对方" }

        var category = FavoriteCategory.TEXT.id
        var title = "文本消息"
        var text = msg.text
        val local = mutableListOf<String>()
        var remote = ""
        var thumb = ""

        when {
            type.startsWith("image") -> {
                category = FavoriteCategory.IMAGE.id
                title = "图片消息"
                text = ""
                remote = resolveMediaUrl(rawUrl)
                copyMedia(ctx, remote)?.let { local.add(it); thumb = it }
            }
            type.startsWith("video") || type == "mp4" -> {
                category = FavoriteCategory.VIDEO.id
                title = "视频消息"
                text = ""
                remote = resolveMediaUrl(rawUrl)
                copyMedia(ctx, remote)?.let { f ->
                    local.add(f)
                    makeVideoThumb(ctx, f)?.let { thumb = it }
                }
            }
            type == "voice" -> {
                category = FavoriteCategory.OTHER.id
                title = "语音消息"
                text = ""
                remote = resolveMediaUrl(rawUrl)
                copyMedia(ctx, remote)?.let { local.add(it) }
            }
            type == "file" -> {
                category = FavoriteCategory.OTHER.id
                title = msg.text.ifBlank { "文件" }
                text = ""
                remote = resolveMediaUrl(rawUrl)
                copyMedia(ctx, remote)?.let { local.add(it) }
            }
            type == "sticker" || type == "emoji" -> return@withContext FavoriteAddResult.FAILED
            else -> {
                if (!hasReadableText(msg)) return@withContext FavoriteAddResult.FAILED
                category = FavoriteCategory.OTHER.id
                title = "文本消息"
            }
        }

        val item = FavoriteItem(
            key = key,
            category = category,
            title = title,
            subtitle = sender,
            text = text,
            localFiles = local,
            remoteUrl = remote,
            thumbnail = thumb,
            sourceId = if (msg.serverId > 0) msg.serverId else msg.id,
            sourceType = "chat",
            senderId = msg.fromUserId,
            createdAt = System.currentTimeMillis()
        )
        addItem(ctx, item)
    }

    /** 收藏一条社区帖子：分类固定为「社区」，卡片按帖子（标题+正文）展示而非图片。 */
    suspend fun addPost(ctx: Context, post: PostListItem): FavoriteAddResult = withContext(Dispatchers.IO) {
        val key = "post:${post.id}"
        if (has(ctx, key)) return@withContext FavoriteAddResult.EXISTS

        val body = runCatching { extractPostCards(post.content).body }.getOrDefault(post.content)
        val local = mutableListOf<String>()
        var thumb = ""
        // 敏感帖只收藏标题/正文，不把图片视频复制到本机（避免收藏页绕过遮罩直接展示）
        val skipMedia = post.is_adult
        val category = FavoriteCategory.COMMUNITY.id

        // 帖子图集：按顺序复制（最多 9 张，与发布上限一致）
        if (!skipMedia) post.image_urls.take(9).forEach { raw ->
            copyMedia(ctx, resolveMediaUrl(raw))?.let { f ->
                local.add(f)
                if (thumb.isBlank()) thumb = f
            }
        }
        // 首资源：图片补一张；视频单独复制（图集为空时用它取封面）
        if (!skipMedia && post.first_resource_url.isNotBlank()) {
            val isResVideo = isVideoMediaUrl(post.first_resource_url)
            val already = local.isEmpty() || post.image_urls.isEmpty()
            if (isResVideo && local.none { isVideoFile(it) }) {
                copyMedia(ctx, resolveMediaUrl(post.first_resource_url))?.let { f ->
                    local.add(f)
                    if (thumb.isBlank()) makeVideoThumb(ctx, f)?.let { thumb = it }
                }
            } else if (!isResVideo && already && local.size < 9) {
                copyMedia(ctx, resolveMediaUrl(post.first_resource_url))?.let { f ->
                    if (!local.contains(f)) local.add(f)
                    if (thumb.isBlank()) thumb = f
                }
            }
        }

        val item = FavoriteItem(
            key = key,
            category = category,
            title = post.title,
            subtitle = post.username,
            text = body,
            localFiles = local,
            remoteUrl = post.first_resource_url.takeIf { it.isNotBlank() }?.let { resolveMediaUrl(it) } ?: "",
            thumbnail = thumb,
            sourceId = post.id,
            sourceType = "post",
            createdAt = System.currentTimeMillis()
        )
        addItem(ctx, item)
    }

    // ---------- 聊天记录收藏（bundle：多条消息整合成一条收藏） ----------

    /** bundle 内一条消息的快照。 */
    private data class BundleEntry(
        val mine: Boolean,
        val name: String,
        /** text / image / video / voice / file（与聊天 mediaType 小写同源） */
        val type: String,
        /** 远程兜底地址（本地复制失败时仍可展示） */
        val url: String,
        val text: String
    )

    /**
     * 批量收藏：把多选的多条消息整合成一条「聊天记录」收藏。
     * 文本按对话原样还原；图片/视频/语音/文件在收藏时复制到本机。
     */
    suspend fun addBundle(
        ctx: Context,
        msgs: List<IMessageRef>,
        title: String,
        subtitle: String
    ): FavoriteAddResult = withContext(Dispatchers.IO) {
        val usable = msgs.filter { canFavoriteMessage(it) }
        if (usable.isEmpty()) return@withContext FavoriteAddResult.FAILED
        // 确定性去重键：同一段消息重复收藏只算一条；多选范围不同则键不同
        val ids = usable.map { if (it.serverId > 0) it.serverId else it.id }.sorted().joinToString("-")
        val key = "bundle:" + md5Hex(ids)

        val entries = usable.map { m ->
            val type = m.mediaType.lowercase()
            BundleEntry(
                mine = m.isMine,
                name = if (m.isMine) "我" else m.senderName.ifBlank { "对方" },
                type = when {
                    type.startsWith("image") -> "image"
                    type.startsWith("video") || type == "mp4" -> "video"
                    type == "voice" -> "voice"
                    type == "file" -> "file"
                    else -> "text"
                },
                url = if (m.mediaUrl.isNotBlank()) resolveMediaUrl(m.mediaUrl) else "",
                text = m.text
            )
        }
        val item = buildBundleItem(ctx, key, title, subtitle, firstNonMineSenderId(usable), entries, copyMedia = true)
        addItem(ctx, item)
    }

    /** 把「分享聊天」卡片（接收到的卡片消息）收藏成整段聊天记录。媒体走远程地址，不复制。 */
    private fun addBundleFromChatCard(ctx: Context, card: com.aurora.chat.ui.chat.ShareCardData): FavoriteAddResult {
        val key = "chatcard:${card.postId}"
        if (has(ctx, key)) return FavoriteAddResult.EXISTS
        val entries = card.chatMessages.map { cm ->
            BundleEntry(
                mine = cm.isMine,
                name = if (cm.isMine) "我" else cm.senderName.ifBlank { "对方" },
                type = when {
                    cm.mediaType.startsWith("image") -> "image"
                    cm.mediaType.startsWith("video") || cm.mediaType == "mp4" -> "video"
                    cm.mediaType == "voice" -> "voice"
                    cm.mediaType == "file" -> "file"
                    else -> "text"
                },
                url = cm.mediaUrl,
                text = cm.text
            )
        }
        val item = buildBundleItem(
            ctx, key,
            title = card.title.ifBlank { "聊天记录" },
            subtitle = "来自 ${card.username}",
            senderId = card.chatMessages.firstOrNull { !it.isMine }?.fromUserId ?: 0L,
            entries = entries,
            copyMedia = false
        )
        return addItem(ctx, item)
    }

    /** 组装 bundle 收藏记录：entries 落成 JSON 快照，媒体按需复制到本机。 */
    private fun buildBundleItem(
        ctx: Context,
        key: String,
        title: String,
        subtitle: String,
        senderId: Long,
        entries: List<BundleEntry>,
        copyMedia: Boolean
    ): FavoriteItem {
        val arr = JSONArray()
        val local = mutableListOf<String>()
        entries.forEach { e ->
            val o = JSONObject()
            o.put("m", if (e.mine) 1 else 0)
            o.put("n", e.name)
            o.put("t", e.type)
            var lf = ""
            if (copyMedia && e.type != "text" && e.url.isNotBlank()) {
                copyMedia(ctx, e.url)?.let { f -> local.add(f); lf = f }
            }
            if (lf.isNotBlank()) o.put("lf", lf)
            if (e.url.isNotBlank()) o.put("u", e.url)
            if (e.text.isNotBlank()) o.put("x", e.text)
            arr.put(o)
        }
        return FavoriteItem(
            key = key,
            category = FavoriteCategory.TEXT.id,
            title = title,
            subtitle = subtitle,
            localFiles = local,
            sourceType = "chat",
            senderId = senderId,
            bundleJson = arr.toString(),
            createdAt = System.currentTimeMillis()
        )
    }

    private fun firstNonMineSenderId(msgs: List<IMessageRef>): Long =
        msgs.firstOrNull { !it.isMine }?.fromUserId ?: 0L

    private fun md5Hex(s: String): String = try {
        java.security.MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        s.hashCode().toUInt().toString()
    }

    private fun addItem(ctx: Context, item: FavoriteItem): FavoriteAddResult = synchronized(lock) {        val list = read(ctx)
        if (list.any { it.key == item.key }) return@synchronized FavoriteAddResult.EXISTS
        write(ctx, listOf(item) + list)
        FavoriteAddResult.ADDED
    }

    // ---------- 可收藏判定 ----------

    /** 长按菜单用它决定是否显示「收藏」入口 */
    fun canFavorite(msg: IMessageRef): Boolean = canFavoriteMessage(msg)

    private fun canFavoriteMessage(msg: IMessageRef): Boolean {
        if (msg.isSystemNotice || msg.isRevoked == 1 || msg.isUploading) return false
        if (msg.flashDuration > 0) return false
        if ((msg as? ChatMsg)?.isToolStatus == true) return false
        val t = msg.mediaType.lowercase()
        return when {
            t.startsWith("image") || t.startsWith("video") || t == "mp4" -> msg.mediaUrl.isNotBlank()
            t == "voice" || t == "file" -> msg.mediaUrl.isNotBlank()
            t == "sticker" || t == "emoji" -> false
            else -> hasReadableText(msg)
        }
    }

    /** 与长按菜单「复制」保持同一口径：只收藏真正可读的正文，协议卡片一律排除。 */
    private fun hasReadableText(msg: IMessageRef): Boolean {
        if (msg.mediaType.lowercase() !in setOf("", "text")) return false
        val t = msg.text
        if (t.isBlank()) return false
        if (com.aurora.chat.ui.chat.parseLocationCard(t) != null) return false
        if (com.aurora.chat.ui.chat.parseOrderNotice(t) != null) return false
        if (com.aurora.chat.ui.chat.isShareCardMessage(t)) {
            // 「分享聊天」卡片可收藏整段对话；其余分享卡片一律排除
            val sc = com.aurora.chat.ui.chat.parseShareCard(t)
            return sc?.targetType == "chat" && sc.chatMessages.isNotEmpty()
        }
        if (com.aurora.chat.ui.chat.isGomokuInviteCard(t)) return false
        if (com.aurora.chat.ui.chat.isPrivacyRequestCard(t) || com.aurora.chat.ui.chat.isPrivacyResponseCard(t)) return false
        if (com.aurora.chat.ui.chat.isInviteGroupCard(t)) return false
        if (com.aurora.chat.ui.chat.isClearChatRequestCard(t) || com.aurora.chat.ui.chat.isClearChatRejectCard(t)) return false
        if (com.aurora.chat.ui.chat.isTransferMessage(t) || com.aurora.chat.ui.chat.isRedPacketMessage(t)) return false
        if (com.aurora.chat.ui.chat.parseTransferNotice(t) != null) return false
        return true
    }

    // ---------- 媒体复制 ----------

    private fun copyMedia(ctx: Context, url: String): String? {
        if (url.isBlank()) return null
        return try {
            // content:// 往往没有扩展名，用 MIME 决定后缀，否则图片会被存成 .bin 导致收藏页识别不出
            val ext = if (url.startsWith("content://")) {
                mimeExt(ctx.contentResolver.getType(Uri.parse(url))) ?: "bin"
            } else {
                extOf(url)
            }
            val target = File(mediaDir(ctx), "fav_${UUID.randomUUID().toString().replace("-", "")}.$ext")
            val ok = when {
                url.startsWith("content://") -> {
                    ctx.contentResolver.openInputStream(Uri.parse(url))?.use { input ->
                        target.outputStream().use { input.copyTo(it) }
                    } != null
                }
                url.startsWith("file://") -> {
                    val src = File(Uri.parse(url).path ?: return null)
                    if (!src.exists()) false else { src.copyTo(target, overwrite = true); true }
                }
                !url.startsWith("http") -> {
                    val src = File(url)
                    if (!src.exists()) false else { src.copyTo(target, overwrite = true); true }
                }
                else -> {
                    val req = Request.Builder().url(url).build()
                    HttpClient.client.newCall(req).execute().use { resp ->
                        val body = resp.body
                        if (!resp.isSuccessful || body == null) return null
                        if (body.contentLength() > MAX_MEDIA_BYTES) return null
                        body.byteStream().use { input ->
                            target.outputStream().use { input.copyTo(it) }
                        }
                    }
                    true
                }
            }
            if (ok && target.exists() && target.length() > 0) target.absolutePath else {
                target.delete(); null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** 视频首帧存成 jpg，供收藏页封面使用（失败就留空，UI 走播放键占位）。 */
    private fun makeVideoThumb(ctx: Context, videoPath: String): String? = try {
        val mmr = MediaMetadataRetriever()
        mmr.setDataSource(videoPath)
        val frame = mmr.getFrameAtTime(0)
        mmr.release()
        if (frame == null) null else {
            val f = File(mediaDir(ctx), "thumb_${UUID.randomUUID().toString().replace("-", "")}.jpg")
            f.outputStream().use { out -> frame.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out) }
            frame.recycle()
            if (f.exists() && f.length() > 0) f.absolutePath else { f.delete(); null }
        }
    } catch (_: Exception) {
        null
    }

    private fun mimeExt(mime: String?): String? = when {
        mime == null -> null
        mime.startsWith("image/") -> "jpg"
        mime.startsWith("video/") -> "mp4"
        mime.startsWith("audio/") -> "mp3"
        else -> null
    }

    private fun extOf(url: String): String {
        val clean = url.substringBefore('?').substringBefore('#')
        val ext = clean.substringAfterLast('.', "").lowercase()
        return if (ext.length in 1..5 && ext.all { it.isLetterOrDigit() }) ext else "bin"
    }

    private fun isImageFile(path: String): Boolean = path.substringAfterLast('.', "").lowercase() in
        setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")

    private fun isVideoFile(path: String): Boolean = path.substringAfterLast('.', "").lowercase() in
        setOf("mp4", "mov", "avi", "mkv", "webm", "3gp", "3gpp", "mpg", "mpeg")

    // ---------- JSON 读写 ----------

    private fun read(ctx: Context): List<FavoriteItem> {
        val f = indexFile(ctx)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            val out = mutableListOf<FavoriteItem>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val files = o.optJSONArray("localFiles")?.let { a ->
                    (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
                } ?: emptyList()
                out.add(
                    FavoriteItem(
                        key = o.optString("key"),
                        category = normalizeLegacyCategory(
                            category = o.optString("category"),
                            sourceType = o.optString("sourceType"),
                            title = o.optString("title")
                        ),
                        title = o.optString("title"),
                        subtitle = o.optString("subtitle"),
                        text = o.optString("text"),
                        localFiles = files,
                        remoteUrl = o.optString("remoteUrl"),
                        thumbnail = o.optString("thumbnail"),
                        sourceId = o.optLong("sourceId"),
                        sourceType = o.optString("sourceType"),
                        senderId = o.optLong("senderId"),
                        bundleJson = o.optString("bundleJson"),
                        createdAt = o.optLong("createdAt")
                    )
                )
            }
            out.filter { it.key.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 旧数据归类迁移（读盘时兜底，不回写也能正确显示）：
     *  - 帖子 → 社区；短视频 → 视频
     *  - 旧分类 video_msg / video_community → 视频
     *  - 聊天文本（旧版被扔进「其他」）→ 文本
     */
    private fun normalizeLegacyCategory(category: String, sourceType: String, title: String): String = when {
        sourceType == "post" -> FavoriteCategory.COMMUNITY.id
        sourceType == "shortvideo" -> FavoriteCategory.VIDEO.id
        category == "video_msg" || category == "video_community" -> FavoriteCategory.VIDEO.id
        category == FavoriteCategory.TEXT.id || category == FavoriteCategory.IMAGE.id ||
            category == FavoriteCategory.VIDEO.id || category == FavoriteCategory.COMMUNITY.id -> category
        category == FavoriteCategory.OTHER.id && sourceType == "chat" && title == "文本消息" ->
            FavoriteCategory.TEXT.id
        else -> category
    }

    private fun write(ctx: Context, list: List<FavoriteItem>) {
        try {
            val arr = JSONArray()
            list.forEach { item ->
                arr.put(
                    JSONObject()
                        .put("key", item.key)
                        .put("category", item.category)
                        .put("title", item.title)
                        .put("subtitle", item.subtitle)
                        .put("text", item.text)
                        .put("localFiles", JSONArray(item.localFiles))
                        .put("remoteUrl", item.remoteUrl)
                        .put("thumbnail", item.thumbnail)
                        .put("sourceId", item.sourceId)
                        .put("sourceType", item.sourceType)
                        .put("senderId", item.senderId)
                        .put("bundleJson", item.bundleJson)
                        .put("createdAt", item.createdAt)
                )
            }
            val dst = indexFile(ctx)
            val tmp = File(dst.parentFile, "$FILE.tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(dst)) {
                dst.writeText(arr.toString())
                tmp.delete()
            }
        } catch (_: Exception) {
        }
    }
}
