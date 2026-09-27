package com.pm.manager.terminal

import android.net.Uri
import com.pm.manager.core.joinPath
import com.pm.manager.model.SiteBackup
import com.pm.manager.model.SiteFile
import com.pm.manager.model.kindFromName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** 账户信息：卡密权限、绑定域名、真实文件管理根目录。 */
data class AccountInfo(
    val perm: String = "B",
    val domain: String = "",
    val root: String = ""
)

/** 启动引导数据：账户信息 + 服务器保存的默认启动目录。 */
data class Bootstrap(
    val account: AccountInfo,
    /** null 表示服务器没返回（网络失败），调用方应保留本地缓存。 */
    val defaultPath: String?
)

/** 批量删除结果。 */
data class BatchDeleteResult(
    val success: Int,
    val failed: List<String>
)

/** 备份列表结果：总额度 + 现有备份。 */
data class BackupInfo(
    val balance: Int,
    val backups: List<SiteBackup>
)

/**
 * 站点文件操作客户端：对接 server/site_server.py。
 * 每一次操作都实时读取/写入服务器上站点的真实文件，不含任何演示数据。
 */
object SiteFiles {

    // ==================== 列表 ====================

    /** 实时列出当前目录下的服务器真实文件。 */
    suspend fun list(path: List<String>): List<SiteFile> = io {
        val res = SiteApi.postJson("/api/list", mapOf("path" to joinPath(path)))
        if (!res.optBoolean("ok", false)) throw ApiException(res.optString("error", "加载失败"))
        parseItems(res.optJSONArray("items"))
    }

    /** 把服务器下发的 items 数组解析为列表（供列表接口与 TCP 推送共用）。 */
    fun parseItems(arr: JSONArray?): List<SiteFile> {
        if (arr == null) return emptyList()
        return buildList(arr.length()) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name")
                if (name.isNullOrEmpty()) continue
                val isDir = o.optBoolean("isDir", false)
                val epochSec = o.optDouble("modified", 0.0)
                add(
                    SiteFile(
                        name = name,
                        kind = if (isDir) com.pm.manager.model.FileKind.FOLDER else kindFromName(name, false),
                        size = o.optLong("size", 0L),
                        modifiedMillis = (epochSec * 1000).toLong()
                    )
                )
            }
        }
    }

    /**
     * 启动引导：一次性拿到权限 / 域名 / 根目录 / 默认启动目录。
     *
     * 原来启动要发 4 个请求：currentPerm()、currentDomain()、currentRoot()、getJson(default-path)，
     * 其中前三个打的是同一个 `/api/info`，纯粹浪费 2 次 RTT。现在合并成 2 个并发请求。
     */
    suspend fun bootstrap(): Bootstrap = io {
        coroutineScope {
            val accountDeferred = async {
                val res = SiteApi.postJson("/api/info", emptyMap())
                if (!res.optBoolean("ok", false)) throw ApiException("获取账户信息失败")
                AccountInfo(
                    perm = res.optString("perm", "B").ifBlank { "B" },
                    domain = res.optString("domain", ""),
                    root = res.optString("root", "")
                )
            }
            val defaultDeferred = async {
                runCatching { SiteApi.getJson("/api/default-path") }
                    .getOrNull()
                    ?.takeIf { it.optBoolean("ok", false) }
                    ?.optString("path", "")
            }
            Bootstrap(
                account = runCatching { accountDeferred.await() }.getOrElse { AccountInfo() },
                defaultPath = runCatching { defaultDeferred.await() }.getOrNull()
            )
        }
    }

    // ==================== 文件操作 ====================

    suspend fun rename(path: List<String>, newName: String) = io {
        val res = SiteApi.postJson("/api/rename", mapOf("path" to joinPath(path), "newName" to newName))
        checkOk(res, "重命名失败")
    }

    suspend fun delete(path: List<String>) = io {
        val res = SiteApi.postJson("/api/delete", mapOf("path" to joinPath(path)))
        checkOk(res, "删除失败")
    }

    /**
     * 批量删除：走服务端 /api/batch-delete 一次请求搞定。
     * 原来是在客户端 for 循环逐个调 /api/delete，N 个文件就是 N 次 RTT，删 50 个文件要半分钟。
     */
    suspend fun batchDelete(paths: List<String>): BatchDeleteResult = io {
        val res = SiteApi.postJson("/api/batch-delete", mapOf("paths" to paths))
        if (!res.optBoolean("ok", false)) throw ApiException(res.optString("error", "批量删除失败"))
        val failedArr = res.optJSONArray("failed")
        val failed = buildList {
            if (failedArr != null) {
                for (i in 0 until failedArr.length()) {
                    val o = failedArr.optJSONObject(i) ?: continue
                    val p = o.optString("path", "")
                    val err = o.optString("error", "未知错误")
                    add(if (p.isBlank()) err else "$p（$err）")
                }
            }
        }
        BatchDeleteResult(success = res.optInt("success", 0), failed = failed)
    }

    /** 压缩多个文件/文件夹为 zip。返回生成的 zip 文件名。 */
    suspend fun zipItems(dirPath: List<String>, itemPaths: List<List<String>>, zipName: String = ""): String = io {
        val res = SiteApi.postJson(
            "/api/zip", mapOf(
                "path" to joinPath(dirPath),
                "items" to itemPaths.map { joinPath(it) },
                "name" to zipName
            )
        )
        checkOk(res, "压缩失败")
        res.optString("name")
    }

    /** 解压 zip 文件到同级目录。返回解压目标目录相对路径。 */
    suspend fun unzip(path: List<String>, destRel: String = ""): String = io {
        val res = SiteApi.postJson("/api/unzip", mapOf("path" to joinPath(path), "dest" to destRel))
        checkOk(res, "解压失败")
        res.optString("dest")
    }

    suspend fun mkdir(parent: List<String>, name: String) = io {
        val res = SiteApi.postJson("/api/mkdir", mapOf("path" to joinPath(parent), "name" to name))
        checkOk(res, "创建文件夹失败")
    }

    suspend fun save(path: List<String>, content: String) = io {
        val res = SiteApi.postJson("/api/save", mapOf("path" to joinPath(path), "content" to content))
        checkOk(res, "保存失败")
    }

    suspend fun read(path: List<String>): String = io {
        val res = SiteApi.postJson("/api/read", mapOf("path" to joinPath(path)))
        checkOk(res, "读取失败")
        res.optString("content")
    }

    /** 上传小文件到服务器当前目录（base64 方式）。 */
    suspend fun upload(path: List<String>, fileName: String, bytes: ByteArray) = io {
        if (bytes.size > 50 * 1024 * 1024) throw ApiException("文件超过50MB，请使用流式上传")
        val b64 = java.util.Base64.getEncoder().encodeToString(bytes)
        val res = SiteApi.postJson(
            "/api/upload",
            mapOf("path" to joinPath(path), "file" to fileName, "data" to b64)
        )
        checkOk(res, "上传失败")
    }

    /** 流式上传大文件：直接发送原始字节流，支持进度回调。 */
    suspend fun uploadStream(
        path: List<String>,
        fileName: String,
        inputStream: InputStream,
        fileSize: Long,
        onProgress: (Float) -> Unit
    ) = io {
        SiteApi.uploadStream(joinPath(path), fileName, inputStream, fileSize, onProgress)
    }

    /** 下载服务器文件原始字节（小文件用，如文本、小图标）。 */
    suspend fun download(path: List<String>): ByteArray = io {
        val res = SiteApi.postJson("/api/download", mapOf("path" to joinPath(path)))
        checkOk(res, "下载失败")
        java.util.Base64.getDecoder().decode(res.optString("data"))
    }

    /** 流式下载原始文件到本地文件（音频/视频大文件，避免 base64 OOM）。 */
    suspend fun downloadToFile(path: List<String>, dest: File) = io {
        SiteApi.downloadRaw(path, dest)
    }

    /** 流式下载文件到 OutputStream，支持实时进度回调。 */
    suspend fun downloadToStream(path: List<String>, out: OutputStream, onProgress: (Float) -> Unit) = io {
        SiteApi.downloadRawToStream(path, out, onProgress)
    }

    /** 复制文件/文件夹。 */
    suspend fun copy(src: List<String>, dst: List<String>) = io {
        val res = SiteApi.postJson("/api/copy", mapOf("src" to joinPath(src), "dst" to joinPath(dst)))
        checkOk(res, "复制失败")
    }

    /** 移动(剪切)文件/文件夹。 */
    suspend fun move(src: List<String>, dst: List<String>) = io {
        val res = SiteApi.postJson("/api/move", mapOf("src" to joinPath(src), "dst" to joinPath(dst)))
        checkOk(res, "剪切失败")
    }

    /** 上传默认启动目录（按当前卡存储）。relPath 为相对根目录的路径串，空串表示根目录。 */
    suspend fun pushDefaultPath(relPath: String): Boolean = io {
        SiteApi.postJson("/api/default-path", mapOf("path" to relPath)).optBoolean("ok", false)
    }

    // ==================== 站点备份 ====================

    suspend fun listBackups(): BackupInfo = io {
        val res = SiteApi.postJson("/api/backup-list", emptyMap())
        checkOk(res, "获取备份列表失败")
        val arr = res.optJSONArray("backups")
        val list = buildList {
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(
                        SiteBackup(
                            id = o.optLong("id"),
                            name = o.optString("name"),
                            size = o.optLong("size", 0L),
                            time = o.optLong("time", 0L)
                        )
                    )
                }
            }
        }
        BackupInfo(balance = res.optInt("backup_balance", 0), backups = list)
    }

    suspend fun createBackup(): SiteBackup = io {
        val res = SiteApi.postJson("/api/backup-create", emptyMap())
        checkOk(res, "备份失败")
        SiteBackup(
            id = res.optLong("id"),
            name = res.optString("name"),
            size = res.optLong("size", 0L),
            time = res.optLong("time", res.optLong("id", 0L))
        )
    }

    /** 用指定备份覆盖还原站点（不可撤销，会清空现有站点内容）。 */
    suspend fun restoreBackup(name: String): Int = io {
        val res = SiteApi.postJson("/api/backup-restore", mapOf("name" to name))
        checkOk(res, "还原失败")
        res.optInt("restored", 0)
    }

    suspend fun deleteBackup(name: String) = io {
        val res = SiteApi.postJson("/api/backup-delete", mapOf("name" to name))
        checkOk(res, "删除失败")
    }

    /** 执行后端命令（重启/启动后端等），仅 A 级可用。返回命令输出文本。 */
    suspend fun runBackendCommand(cmd: String): String = io {
        val res = SiteApi.postJson("/api/backend-cmd", mapOf("cmd" to cmd))
        checkOk(res, "命令执行失败")
        res.optString("output", "").ifBlank { "（命令执行完成，无输出）" }
    }

    /** 把一份备份 zip 下载到系统文件管理器选中的 uri。 */
    suspend fun downloadBackupToUri(
        name: String,
        uri: Uri,
        resolver: android.content.ContentResolver,
        onProgress: (Float) -> Unit
    ) = io {
        val out = resolver.openOutputStream(uri, "w") ?: throw ApiException("无法写入所选位置")
        out.use { SiteApi.downloadBackupToStream(name, it, onProgress) }
    }

    // ==================== 激活 ====================

    /** 卡密激活（免鉴权）。成功后写入本地卡密并复位失效标志。 */
    suspend fun activate(context: android.content.Context, card: String, password: String): Result<String> = io {
        val res = SiteApi.postJsonNoAuth("/api/activate", mapOf("card" to card, "password" to password))
        if (res.optBoolean("ok", false)) {
            SiteConfig.saveCard(context, card, password)
            SiteConfig.setPerm(context, res.optString("perm", "B").ifBlank { "B" })
            // 落盘激活标志：否则每次冷启动 AppGate 读 "activated" 仍为 false，又被踢回激活页
            SiteConfig.setActivated(context, true)
            // 新卡激活成功：清掉上次残留的失效标志，避免一进主界面就又被踢回激活页
            SessionState.reset()
            Result.success(res.optString("card", card))
        } else {
            Result.failure(ApiException(res.optString("error", "激活失败")))
        }
    }

    // ==================== 内部 ====================

    private fun checkOk(res: JSONObject, fallback: String) {
        if (!res.optBoolean("ok", false)) {
            throw ApiException(res.optString("error", "").ifBlank { fallback })
        }
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }
}
