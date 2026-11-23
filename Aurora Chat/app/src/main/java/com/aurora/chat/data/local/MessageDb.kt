package com.aurora.chat.data.local

import android.content.Context
import android.database.Cursor
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.aurora.chat.CryptoUtil
import com.aurora.chat.data.api.MessageInfo
import com.aurora.chat.ui.chat.isSystemNoticeMessage
import java.io.File

/**
 * ## 消息关系库（SQLite）
 *
 * 替代「一个会话一个 JSON 文件、每次改动整文件重写」的存储模型。
 *
 * ### 为什么要换
 * 旧模型（`local_messages/{friendId}.json`）每次 saveMessages 都要：
 * 整文件读入 → 全量 JSON 解析 → 逐条解密（取已有内容）→ 逐条重新加密 → 全量序列化 → 整文件写。
 * 成本是 O(会话总条数)，一万条要几百毫秒，五万条要秒级，且堆里会瞬时出现一个几十 MB 的字符串。
 * 表现就是「越聊越卡、进大群卡死、发消息顿一下」。
 *
 * 本实现的成本：
 *  - 写：只 INSERT 真正新出现的行，O(新增条数)，与历史条数无关；
 *  - 读：按 `(conv_key, msg_id)` 主键索引取最近 N 条 / 游标上翻，O(N)；
 *  - 内存：只有当前窗口，不再整段历史常驻。
 *
 * ### 表结构要点
 *  - 主键 `(conv_key, msg_id)`：既做去重，又天然是分页索引（`conv_key = ? AND msg_id < ? ORDER BY msg_id DESC`）；
 *  - `conv_key` 用字符串：私聊正数 id、群聊负数 id、本地/AI 会话的字符串 key 可以共用一张表，互不相撞；
 *  - 正文存 BLOB（[CryptoUtil.encryptLocalRaw]），不走 Base64，省掉两次编解码和约 1/3 体积；
 *  - `schema` 字段保留旧版语义：=2 时分类一律由结构化字段重算（不信任落盘布尔），
 *    <2 是旧 JSON 迁移过来的行，按「落盘值且重算值」双条件收紧（与旧 loadMessages 完全一致）。
 *
 * 所有方法都在调用方给定的线程上同步执行，调用方必须保证不在主线程（现有调用点全部在 IO/Default）。
 */
object MessageDb {

    private const val DB_NAME = "aurora_messages.db"
    private const val DB_VERSION = 2
    private const val T = "messages"

    // ==================== 表结构 ====================

    private const val COLUMNS =
        "conv_key,msg_id,from_user_id,to_user_id,from_user_name,content,created_at," +
            "is_revoked,is_system_notice,reply_to_id,reply_to_text,reply_to_sender," +
            "media_type,media_url,media_width,media_height,flash_duration,target_name," +
            "msg_type,broadcast_task_id,contact_source,local_time_ms,schema,mentions"

    /** 列序号，与 [COLUMNS] 顺序严格对应 */
    private const val I_MSG_ID = 1
    private const val I_FROM_UID = 2
    private const val I_TO_UID = 3
    private const val I_FROM_NAME = 4
    private const val I_CONTENT = 5
    private const val I_CREATED_AT = 6
    private const val I_REVOKED = 7
    private const val I_SYSTEM = 8
    private const val I_REPLY_ID = 9
    private const val I_REPLY_TEXT = 10
    private const val I_REPLY_SENDER = 11
    private const val I_MEDIA_TYPE = 12
    private const val I_MEDIA_URL = 13
    private const val I_MEDIA_W = 14
    private const val I_MEDIA_H = 15
    private const val I_FLASH = 16
    private const val I_TARGET = 17
    private const val I_MSG_TYPE = 18
    private const val I_BROADCAST = 19
    private const val I_CONTACT = 20
    private const val I_LOCAL_TIME = 21
    private const val I_SCHEMA = 22
    private const val I_MENTIONS = 23

    private val INSERT_SQL = "INSERT OR REPLACE INTO $T ($COLUMNS) VALUES (" +
        COLUMNS.split(",").joinToString(",") { "?" } + ")"

    /** 已有行只补空字段，绝不覆盖已有值（媒体 URL / 分类字段「非空优先」的落库版本） */
    private val FIXUP_SQL = """
        UPDATE $T SET
            media_url = CASE WHEN media_url = '' THEN ? ELSE media_url END,
            media_type = CASE WHEN media_type = '' THEN ? ELSE media_type END,
            media_width = CASE WHEN media_width = 0 THEN ? ELSE media_width END,
            media_height = CASE WHEN media_height = 0 THEN ? ELSE media_height END,
            msg_type = CASE WHEN msg_type = '' THEN ? ELSE msg_type END,
            broadcast_task_id = CASE WHEN broadcast_task_id = 0 THEN ? ELSE broadcast_task_id END,
            contact_source = CASE WHEN contact_source = '' THEN ? ELSE contact_source END,
            mentions = CASE WHEN mentions = '' THEN ? ELSE mentions END
        WHERE conv_key = ? AND msg_id = ?
    """.trimIndent()

    // ==================== 打开 ====================

    @Volatile
    private var helper: SQLiteOpenHelper? = null

    private fun helper(ctx: Context): SQLiteOpenHelper {
        helper?.let { return it }
        synchronized(this) {
            helper?.let { return it }
            val app = ctx.applicationContext
            val h = object : SQLiteOpenHelper(app, DB_NAME, null, DB_VERSION) {
                override fun onCreate(db: SQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS $T (
                            conv_key TEXT NOT NULL,
                            msg_id INTEGER NOT NULL,
                            from_user_id INTEGER NOT NULL DEFAULT 0,
                            to_user_id INTEGER NOT NULL DEFAULT 0,
                            from_user_name TEXT NOT NULL DEFAULT '',
                            content BLOB,
                            created_at INTEGER NOT NULL DEFAULT 0,
                            is_revoked INTEGER NOT NULL DEFAULT 0,
                            is_system_notice INTEGER NOT NULL DEFAULT 0,
                            reply_to_id INTEGER NOT NULL DEFAULT 0,
                            reply_to_text TEXT NOT NULL DEFAULT '',
                            reply_to_sender TEXT NOT NULL DEFAULT '',
                            media_type TEXT NOT NULL DEFAULT '',
                            media_url TEXT NOT NULL DEFAULT '',
                            media_width INTEGER NOT NULL DEFAULT 0,
                            media_height INTEGER NOT NULL DEFAULT 0,
                            flash_duration INTEGER NOT NULL DEFAULT 0,
                            target_name TEXT NOT NULL DEFAULT '',
                            msg_type TEXT NOT NULL DEFAULT '',
                            broadcast_task_id INTEGER NOT NULL DEFAULT 0,
                            contact_source TEXT NOT NULL DEFAULT '',
                            local_time_ms INTEGER NOT NULL DEFAULT 0,
                            schema INTEGER NOT NULL DEFAULT 2,
                            mentions TEXT NOT NULL DEFAULT '',
                            PRIMARY KEY (conv_key, msg_id)
                        )
                        """.trimIndent()
                    )
                }

                override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    // v1 → v2：补 @ 提及映射列（老数据的该列为空串，走「按昵称匹配」的旧逻辑）
                    if (oldVersion < 2) {
                        try {
                            db.execSQL("ALTER TABLE $T ADD COLUMN mentions TEXT NOT NULL DEFAULT ''")
                        } catch (_: Exception) {
                            // 列已存在（重复升级/手工建库）时忽略
                        }
                    }
                }
            }
            // WAL：单行写入只追加日志，不会触发整库重写；读不阻塞写。
            h.setWriteAheadLoggingEnabled(true)
            helper = h
            return h
        }
    }

    private fun db(ctx: Context): SQLiteDatabase = helper(ctx).writableDatabase

    // ==================== 会话内 id 集合缓存 ====================
    // 目的：saveMessages 常被「整段历史全量回写」的方式调用（进会话一次、网络合并一次）。
    // 有了 id 集合，这些调用只需 O(n) 次哈希判断，不再产生任何写放大。

    private val idCache = object : LinkedHashMap<String, MutableSet<Long>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MutableSet<Long>>): Boolean =
            size > 8
    }

    private fun loadIdSet(ctx: Context, convKey: String): MutableSet<Long> {
        val out = HashSet<Long>(256)
        val c = db(ctx).query(T, arrayOf("msg_id"), "conv_key = ?", arrayOf(convKey), null, null, null)
        c.use {
            while (it.moveToNext()) out.add(it.getLong(0))
        }
        return out
    }

    private fun idsOf(ctx: Context, convKey: String): MutableSet<Long> = synchronized(idCache) {
        idCache[convKey] ?: loadIdSet(ctx, convKey).also { idCache[convKey] = it }
    }

    // ==================== 写 ====================

    /** 待写入行：info 提供除正文外的字段，blob 是已编码好的正文（避免迁移时二次加解密）。 */
    private class Row(val info: MessageInfo, val blob: ByteArray, val schema: Int)

    /**
     * 批量写入（新增 + 补空字段），返回真正新插入的行数。
     *
     * 语义对齐旧 `saveMessages`：
     *  - 新 id → 插入；
     *  - 已存在的 id → **不覆盖**已有内容，只把库里为空的媒体/分类字段补上
     *    （旧实现是「整体覆盖写盘 + 逐条保留非空 mediaUrl」，结论一致，成本从 O(n) 降到 O(新增)）。
     */
    fun upsert(ctx: Context, convKey: String, messages: List<MessageInfo>): Int {
        if (messages.isEmpty()) return 0
        // 关键：先按「库里是否已有」分流，只对真正新增的行做加密。
        // 调用方在进会话时会传整段历史，若先加密再判断，等于把「全量重新加密」的 O(n) 成本原样做一遍
        // —— 那正是旧实现最贵的一步，不能换个存储又搬回来。
        val known = idsOf(ctx, convKey)
        val newRows = ArrayList<Row>()
        val fixups = ArrayList<MessageInfo>(0)
        for (m in messages) {
            if (known.contains(m.id)) {
                if (needsFixup(m)) fixups.add(m)
            } else {
                newRows.add(Row(m, CryptoUtil.encryptLocalRaw(m.content), 2))
            }
        }
        return writeRows(ctx, convKey, newRows, fixups)
    }

    /** 已有行只有在「入参带了值」时才可能需要补写空字段（配合 [FIXUP_SQL] 的 CASE 判断） */
    private fun needsFixup(m: MessageInfo): Boolean =
        m.mediaUrl.isNotEmpty() || m.mediaType.isNotEmpty() || m.mediaWidth != 0 ||
            m.mediaHeight != 0 || m.msgType.isNotEmpty() || m.broadcastTaskId != 0L ||
            m.contactSource.isNotEmpty() || m.mentions.isNotEmpty()

    private fun writeRows(
        ctx: Context,
        convKey: String,
        newRows: List<Row>,
        fixups: List<MessageInfo>
    ): Int {
        if (newRows.isEmpty() && fixups.isEmpty()) return 0

        val wdb = db(ctx)
        wdb.beginTransaction()
        try {
            if (newRows.isNotEmpty()) {
                val st = wdb.compileStatement(INSERT_SQL)
                for (r in newRows) {
                    st.clearBindings()
                    bindRow(st, convKey, r)
                    st.executeInsert()
                }
            }
            if (fixups.isNotEmpty()) {
                val st = wdb.compileStatement(FIXUP_SQL)
                for (m in fixups) {
                    st.clearBindings()
                    st.bindString(1, m.mediaUrl)
                    st.bindString(2, m.mediaType)
                    st.bindLong(3, m.mediaWidth.toLong())
                    st.bindLong(4, m.mediaHeight.toLong())
                    st.bindString(5, m.msgType)
                    st.bindLong(6, m.broadcastTaskId)
                    st.bindString(7, m.contactSource)
                    st.bindString(8, com.aurora.chat.data.store.MentionStore.encode(m.mentions))
                    st.bindString(9, convKey)
                    st.bindLong(10, m.id)
                    st.executeUpdateDelete()
                }
            }
            wdb.setTransactionSuccessful()
        } finally {
            wdb.endTransaction()
        }
        if (newRows.isNotEmpty()) {
            synchronized(idCache) { idCache[convKey]?.addAll(newRows.map { it.info.id }) }
        }
        return newRows.size
    }

    private fun bindRow(st: android.database.sqlite.SQLiteStatement, convKey: String, r: Row) {
        val m = r.info
        st.bindString(1, convKey)
        st.bindLong(2, m.id)
        st.bindLong(3, m.fromUserId)
        st.bindLong(4, m.toUserId)
        st.bindString(5, m.fromUserName)
        if (r.blob.isEmpty()) st.bindNull(6) else st.bindBlob(6, r.blob)
        st.bindLong(7, m.createdAt)
        st.bindLong(8, m.isRevoked.toLong())
        st.bindLong(9, if (m.isSystemNotice) 1L else 0L)
        st.bindLong(10, m.replyToId)
        st.bindString(11, m.replyToText)
        st.bindString(12, m.replyToSender)
        st.bindString(13, m.mediaType)
        st.bindString(14, m.mediaUrl)
        st.bindLong(15, m.mediaWidth.toLong())
        st.bindLong(16, m.mediaHeight.toLong())
        st.bindLong(17, m.flashDuration.toLong())
        st.bindString(18, m.targetName)
        st.bindString(19, m.msgType)
        st.bindLong(20, m.broadcastTaskId)
        st.bindString(21, m.contactSource)
        st.bindLong(22, m.localTimeMs)
        st.bindLong(23, r.schema.toLong())
        st.bindString(24, com.aurora.chat.data.store.MentionStore.encode(m.mentions))
    }

    /** 整体覆盖某个会话（备份重置用）：删 + 插包在**同一个事务**里，中途失败整体回滚，不会留下「删干净了没写回去」。 */
    fun overwrite(ctx: Context, convKey: String, messages: List<MessageInfo>) {
        val wdb = db(ctx)
        wdb.beginTransaction()
        try {
            wdb.delete(T, "conv_key = ?", arrayOf(convKey))
            synchronized(idCache) { idCache.remove(convKey) }
            // upsert 内部会开嵌套事务，Android 的 SQLiteDatabase 支持嵌套计数：
            // 只有最外层 setTransactionSuccessful 才真正提交。
            upsert(ctx, convKey, messages)
            wdb.setTransactionSuccessful()
        } finally {
            wdb.endTransaction()
        }
    }

    /** 标记撤回（单行 UPDATE，不再整文件重写）。 */
    fun markRevoked(ctx: Context, convKey: String, msgId: Long) {
        if (msgId == 0L) return
        // 必须显式写 arrayOf<Any>：String 与 Long 一起推断会得到 Comparable<*> & Serializable 交叉类型，
        // reified 的 arrayOf 无法具体化交叉类型，Kotlin 会直接报错。
        db(ctx).execSQL("UPDATE $T SET is_revoked = 1 WHERE conv_key = ? AND msg_id = ?", arrayOf<Any>(convKey, msgId))
    }

    /** 删除整个会话。 */
    fun deleteConversation(ctx: Context, convKey: String) {
        db(ctx).delete(T, "conv_key = ?", arrayOf(convKey))
        synchronized(idCache) { idCache.remove(convKey) }
    }

    /**
     * 广播被服务端删除/撤回：按 task_id 清掉本地缓存。
     * 不做这一步时，内存里删掉了，但退出重进会话会从本地库重新加载，表现为「撤回后仍有残留」。
     */
    fun deleteByBroadcastTask(ctx: Context, taskId: Long) {
        if (taskId <= 0L) return
        db(ctx).delete(T, "broadcast_task_id = ?", arrayOf(taskId.toString()))
        synchronized(idCache) { idCache.clear() }
    }

    /** 清空全部。 */
    fun clearAll(ctx: Context) {
        db(ctx).delete(T, null, null)
        synchronized(idCache) { idCache.clear() }
    }

    // ==================== 读 ====================

    /**
     * 最近 [limit] 条（升序返回）。
     * 走主键索引，只解 [limit] 条密文 —— 不再「整段历史全解析 + 逐条解密」。
     */
    fun loadRecent(ctx: Context, convKey: String, limit: Int): List<MessageInfo> {
        if (limit <= 0) return emptyList()
        val out = ArrayList<MessageInfo>(limit)
        db(ctx).query(T, null, "conv_key = ?", arrayOf(convKey), null, null, "msg_id DESC", limit.toString())
            .use { c -> while (c.moveToNext()) out.add(readRow(c)) }
        out.reverse()
        return out
    }

    /**
     * 比 [beforeMsgId] 更旧的一页（升序返回）。
     * 游标分页：不依赖 offset，插入新消息也不会导致翻页错位或重复。
     */
    fun loadOlder(ctx: Context, convKey: String, beforeMsgId: Long, limit: Int): List<MessageInfo> {
        if (limit <= 0) return emptyList()
        val out = ArrayList<MessageInfo>(limit)
        db(ctx).query(
            T, null, "conv_key = ? AND msg_id < ?", arrayOf(convKey, beforeMsgId.toString()),
            null, null, "msg_id DESC", limit.toString()
        ).use { c -> while (c.moveToNext()) out.add(readRow(c)) }
        out.reverse()
        return out
    }

    /** 全量（历史搜索 / 备份导出用）。会解密全部，调用方需接受 O(n)。 */
    fun loadAll(ctx: Context, convKey: String): List<MessageInfo> {
        val out = ArrayList<MessageInfo>(64)
        db(ctx).query(T, null, "conv_key = ?", arrayOf(convKey), null, null, "msg_id ASC")
            .use { c -> while (c.moveToNext()) out.add(readRow(c)) }
        return out
    }

    /** 是否还有比 [beforeMsgId] 更旧的行（EXISTS 查询，常数级）。 */
    fun hasOlder(ctx: Context, convKey: String, beforeMsgId: Long): Boolean {
        db(ctx).rawQuery(
            "SELECT 1 FROM $T WHERE conv_key = ? AND msg_id < ? LIMIT 1",
            arrayOf(convKey, beforeMsgId.toString())
        ).use { return it.moveToFirst() }
    }

    fun count(ctx: Context, convKey: String): Long =
        DatabaseUtils.queryNumEntries(db(ctx), T, "conv_key = ?", arrayOf(convKey))

    // ==================== 行 → 模型 ====================

    private fun readRow(c: Cursor): MessageInfo {
        val content = CryptoUtil.decryptLocalRaw(c.getBlob(I_CONTENT) ?: ByteArray(0))
        val isRevoked = c.getInt(I_REVOKED)
        val msgType = c.getString(I_MSG_TYPE) ?: ""
        val broadcastTaskId = c.getLong(I_BROADCAST)
        val flashDuration = c.getInt(I_FLASH)
        val schema = c.getInt(I_SCHEMA)
        // 分类只认结构化字段（与网络路径、渲染路径同一个函数）
        val derived = isSystemNoticeMessage(
            isRevoked = isRevoked != 0,
            msgType = msgType,
            broadcastTaskId = broadcastTaskId,
            flashDuration = flashDuration,
            content = content
        )
        val storedSystem = c.getInt(I_SYSTEM) != 0
        // schema>=2：结构化字段优先，但这里必须 OR 上落盘布尔 ——
        // msg_type 在「adapter 引用回写」的历史版本里被整列弄丢过（回写时只带了
        // is_system_notice 布尔、没带 msg_type，群系统广播落库后 msg_type 全是空串），
        // 而进会话的增量拉取又只取 id > 磁盘最大值，老消息永远等不到服务端矫正。
        // 那批行里落盘布尔是唯一幸存的真相（它来自插入时按服务端数据算好的分类），
        // derived || storedSystem 才能把「重进对话后广播掉成气泡」自愈回来。
        // schema<2（旧 JSON 迁移）：布尔曾被正文猜测污染过，维持「双条件收紧」不放宽。
        val isSystemNotice = if (schema >= 2) (derived || storedSystem) else (storedSystem && derived)

        val info = MessageInfo(
            id = c.getLong(I_MSG_ID),
            fromUserId = c.getLong(I_FROM_UID),
            toUserId = c.getLong(I_TO_UID),
            content = content,
            createdAt = c.getLong(I_CREATED_AT),
            fromUserName = c.getString(I_FROM_NAME) ?: "",
            isRevoked = isRevoked,
            isSystemNotice = isSystemNotice,
            replyToText = c.getString(I_REPLY_TEXT) ?: "",
            replyToSender = c.getString(I_REPLY_SENDER) ?: "",
            replyToId = c.getLong(I_REPLY_ID),
            mediaType = c.getString(I_MEDIA_TYPE) ?: "",
            mediaUrl = c.getString(I_MEDIA_URL) ?: "",
            flashDuration = flashDuration,
            targetName = c.getString(I_TARGET) ?: "",
            msgType = msgType,
            broadcastTaskId = broadcastTaskId,
            contactSource = c.getString(I_CONTACT) ?: "",
            mediaWidth = c.getInt(I_MEDIA_W),
            mediaHeight = c.getInt(I_MEDIA_H),
            localTimeMs = c.getLong(I_LOCAL_TIME),
            mentions = com.aurora.chat.data.store.MentionStore.parse(c.getString(I_MENTIONS) ?: "")
        )
        // 读盘即登记，重进会话/断网时渲染层也能按消息 id 拿到 @ 目标
        com.aurora.chat.data.store.MentionStore.put(info.id, info.mentions)
        return info
    }

    // ==================== 旧 JSON 迁移 ====================

    private const val MIGRATION_SP = "message_db_migration"
    private const val MIGRATION_FLAG = "legacy_json_imported_v1"

    /** 旧 JSON 目录：local_messages = 聊天页实际在用的；messages = 更早版本的目录，一并兼容 */
    private val LEGACY_DIRS = arrayOf("local_messages", "messages")

    @Volatile
    private var migrated = false

    private val migrateLock = Any()

    /**
     * 按需迁移「单个会话」的旧 JSON。
     *
     * 为什么不做成「首次启动全量迁移」：老用户可能有上百个会话、几十万条消息，
     * 一次全量导入要在进第一个会话前阻塞好几秒，反而比原来更卡。
     * 这里改成**谁被访问就迁谁**：只有 `{convKey}.json` 真的存在时才解析它，
     * 迁完立刻改名成 `.imported`，下次是 O(1) 的 exists 判断。
     * 用户不打开的会话就一直留在旧文件里，不占启动时间、也不丢数据。
     */
    fun ensureMigratedFor(ctx: Context, convKey: String) {
        for (dirName in LEGACY_DIRS) {
            val f = File(File(ctx.filesDir, dirName), "$convKey.json")
            if (f.exists()) importFile(ctx, convKey, f)
        }
    }

    /**
     * 全量迁移（历史搜索 / 备份这类「确实要看全部会话」的入口才调）。
     * 幂等：成功的文件改名成 `.json.imported`，中途被杀下次只补剩余文件；
     * 失败的文件保持原名，下次重试，**不会删任何数据**。
     */
    fun ensureMigrated(ctx: Context) {
        if (migrated) return
        synchronized(migrateLock) {
            if (migrated) return
            val sp = ctx.applicationContext.getSharedPreferences(MIGRATION_SP, Context.MODE_PRIVATE)
            if (sp.getBoolean(MIGRATION_FLAG, false)) {
                migrated = true
                return
            }
            try {
                for (dirName in LEGACY_DIRS) {
                    val dir = File(ctx.filesDir, dirName)
                    val files = dir.listFiles() ?: continue
                    for (f in files) {
                        if (!f.isFile || !f.name.endsWith(".json")) continue
                        val convKey = f.name.removeSuffix(".json")
                        if (convKey.toLongOrNull() == null) {
                            // 不是数值会话 key（异常文件），改名跳过，下次不再尝试
                            runCatching { f.renameTo(File(dir, f.name + ".skip")) }
                            continue
                        }
                        importFile(ctx, convKey, f)
                    }
                }
                sp.edit().putBoolean(MIGRATION_FLAG, true).apply()
                migrated = true
            } catch (e: Exception) {
                // 迁移失败不清标记，下次进入自动重试；旧文件全部原样保留
                com.aurora.chat.ErrorReporter.debug(
                    "MESSAGE_DB_MIGRATE",
                    "ensureMigrated 失败: ${e.javaClass.simpleName}: ${e.message}"
                )
            }
        }
    }

    private fun importFile(ctx: Context, convKey: String, file: File) {
        // 解析失败（文件损坏 / 写一半被截断）单独处理：改名 .json.failed，
        // 否则每次打开会话都会把同一个坏文件重新解析一遍，等于每次进会话卡一下。
        // 只在「解析」失败时改名；写库失败保持原名，下次自动重试，绝不把数据搁浅。
        val arr = try {
            org.json.JSONArray(file.readText())
        } catch (e: Exception) {
            com.aurora.chat.ErrorReporter.debug(
                "MESSAGE_DB_MIGRATE",
                "import 解析失败 file=${file.name}: ${e.javaClass.simpleName}"
            )
            runCatching { file.renameTo(File(file.parentFile, file.name + ".failed")) }
            return
        }
        try {
            val rows = ArrayList<Row>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optLong("id", 0)
                val msgType = o.optString("msgType", "")
                val broadcastTaskId = o.optLong("broadcastTaskId", 0)
                val flashDuration = o.optInt("flashDuration", 0)
                val schema = o.optInt("schema", 1)
                val info = MessageInfo(
                    id = id,
                    fromUserId = o.optLong("fromUserId", 0),
                    toUserId = o.optLong("toUserId", 0),
                    // 迁移阶段不解密，正文只走 blob；content 留空避免误导
                    content = "",
                    createdAt = o.optLong("createdAt", 0),
                    fromUserName = o.optString("fromUserName", ""),
                    isRevoked = o.optInt("isRevoked", 0),
                    isSystemNotice = o.optBoolean("isSystemNotice", false),
                    replyToText = o.optString("replyToText", ""),
                    replyToSender = o.optString("replyToSender", ""),
                    replyToId = o.optLong("replyToId", 0),
                    mediaType = o.optString("mediaType", ""),
                    mediaUrl = o.optString("mediaUrl", ""),
                    flashDuration = flashDuration,
                    targetName = o.optString("targetName", ""),
                    msgType = msgType,
                    broadcastTaskId = broadcastTaskId,
                    contactSource = o.optString("contactSource", ""),
                    mediaWidth = o.optInt("mediaWidth", 0),
                    mediaHeight = o.optInt("mediaHeight", 0),
                    localTimeMs = o.optLong("localTimeMs", 0)
                )
                rows.add(Row(info, CryptoUtil.legacyLocalToRaw(o.optString("content", "")), schema))
            }
            // 迁移时该会话库内可能已有新数据（用户在迁移前就聊过），只补不覆盖
            val known = idsOf(ctx, convKey)
            val newRows = ArrayList<Row>(rows.size)
            val fixups = ArrayList<MessageInfo>(0)
            for (r in rows) {
                if (known.contains(r.info.id)) {
                    if (needsFixup(r.info)) fixups.add(r.info)
                } else {
                    newRows.add(r)
                }
            }
            writeRows(ctx, convKey, newRows, fixups)
            val ok = file.renameTo(File(file.parentFile, file.name + ".imported"))
            com.aurora.chat.ErrorReporter.debug(
                "MESSAGE_DB_MIGRATE",
                "import conv=$convKey rows=${rows.size} renamed=$ok"
            )
        } catch (e: Exception) {
            // 写库失败：保持原文件名不动，下次进入自动重试
            com.aurora.chat.ErrorReporter.debug(
                "MESSAGE_DB_MIGRATE",
                "import 失败 file=${file.name}: ${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }
}
