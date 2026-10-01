package com.aurora.chat.host

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 站点文件操作客户端：对接 server/site_server.py。
 * 每次操作实时读写服务器上站点的真实文件（不含演示数据）。
 */
object HostFiles {

    private fun joinPath(path: List<String>): String = path.joinToString("/")

    /** 实时列出当前目录下的服务器真实文件。 */
    suspend fun list(path: List<String>): List<HostFile> = io {
        val res = HostApi.postJson("/api/list", mapOf("path" to joinPath(path)))
        if (!res.optBoolean("ok", false)) throw ApiException(res.optString("error", "加载失败"))
        parseItems(res.optJSONArray("items"))
    }

    fun parseItems(arr: JSONArray?): List<HostFile> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name")
                if (name.isNullOrEmpty()) continue
                val isDir = o.optBoolean("isDir", false)
                val epochSec = o.optDouble("modified", 0.0)
                add(
                    HostFile(
                        name = name,
                        isDir = isDir,
                        size = o.optLong("size", 0L),
                        modifiedMillis = (epochSec * 1000).toLong()
                    )
                )
            }
        }
    }

    suspend fun rename(path: List<String>, newName: String) = io {
        val res = HostApi.postJson("/api/rename", mapOf("path" to joinPath(path), "newName" to newName))
        checkOk(res, "重命名失败")
    }

    suspend fun delete(path: List<String>) = io {
        val res = HostApi.postJson("/api/delete", mapOf("path" to joinPath(path)))
        checkOk(res, "删除失败")
    }

    suspend fun mkdir(parent: List<String>, name: String) = io {
        val res = HostApi.postJson("/api/mkdir", mapOf("path" to joinPath(parent), "name" to name))
        checkOk(res, "创建文件夹失败")
    }

    suspend fun save(path: List<String>, content: String) = io {
        val res = HostApi.postJson("/api/save", mapOf("path" to joinPath(path), "content" to content))
        checkOk(res, "保存失败")
    }

    suspend fun read(path: List<String>): String = io {
        val res = HostApi.postJson("/api/read", mapOf("path" to joinPath(path)))
        checkOk(res, "读取失败")
        res.optString("content")
    }

    // ============ 远程终端（对接 site_server.py /api/exec，与「项目管理」终端一致）============
    /** 终端命令执行结果（含更新后的工作目录 cwd）。 */
    data class HostTermResult(val output: String, val cwd: String)

    /** 执行终端命令：POST /api/exec，body 为 {command, cwd}；服务端 run_command 回 {output, cwd}。 */
    suspend fun exec(command: String, cwd: String = ""): HostTermResult = io {
        val res = HostApi.postJson("/api/exec", mapOf("command" to command, "cwd" to cwd))
        if (!res.optBoolean("ok", false)) {
            val err = res.optString("error", "")
            if (err.isNotBlank()) throw ApiException(err)
            throw ApiException("终端接口未启用")
        }
        HostTermResult(
            res.optString("output", "").ifBlank { "(命令已执行，无输出)" },
            res.optString("cwd", cwd)
        )
    }

    // ============ 远程文件：复制 / 移动 / 压缩 / 解压 / 批量删除（与「项目管理」一致）============
    suspend fun copy(src: List<String>, dst: List<String>) = io {
        val res = HostApi.postJson("/api/copy", mapOf("src" to joinPath(src), "dst" to joinPath(dst)))
        checkOk(res, "复制失败")
    }

    suspend fun move(src: List<String>, dst: List<String>) = io {
        val res = HostApi.postJson("/api/move", mapOf("src" to joinPath(src), "dst" to joinPath(dst)))
        checkOk(res, "移动失败")
    }

    suspend fun zipItems(paths: List<List<String>>, name: String) = io {
        val res = HostApi.postJson("/api/zip", mapOf("paths" to paths.map { joinPath(it) }, "name" to name))
        checkOk(res, "压缩失败")
    }

    suspend fun unzip(path: List<String>, dest: String = "") = io {
        val res = HostApi.postJson("/api/unzip", mapOf("path" to joinPath(path), "dest" to dest))
        checkOk(res, "解压失败")
    }

    suspend fun batchDelete(paths: List<String>) = io {
        val res = HostApi.postJson("/api/batch-delete", mapOf("paths" to paths))
        checkOk(res, "删除失败")
    }

    /** 下载备份 zip 到本地文件（GET /api/backup-download?name=）。 */
    suspend fun downloadBackup(name: String, dest: File) = io {
        val conn = (URL(HostConfig.BASE_URL + "/api/backup-download?name=" + URLEncoder.encode(name, "UTF-8"))
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 300_000
            setRequestProperty("X-Auth-Token", HostConfig.TOKEN)
            if (HostConfig.hasCard) {
                setRequestProperty("X-Card-Key", HostConfig.cardKey)
                setRequestProperty("X-Card-Password", HostConfig.cardPassword)
            }
        }
        conn.inputStream.use { input -> dest.outputStream().use { out -> input.copyTo(out) } }
    }

    suspend fun uploadStream(
        path: List<String>, fileName: String,
        inputStream: InputStream, fileSize: Long, onProgress: (Float) -> Unit
    ) = io {
        HostApi.uploadStream(joinPath(path), fileName, inputStream, fileSize, onProgress)
    }

    suspend fun downloadToFile(path: List<String>, dest: File) = io {
        HostApi.downloadRaw(path, dest)
    }

    // ============ 备份 / 还原（自带回退能力）============

    suspend fun listBackups(): List<HostBackup> = io {
        val res = HostApi.postJson("/api/backup-list", emptyMap())
        checkOk(res, "获取备份列表失败")
        val arr = res.optJSONArray("backups")
        buildList {
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(
                        HostBackup(
                            id = o.optLong("id"),
                            name = o.optString("name"),
                            size = o.optLong("size", 0L),
                            time = o.optLong("time", 0L)
                        )
                    )
                }
            }
        }
    }

    suspend fun createBackup(): HostBackup = io {
        val res = HostApi.postJson("/api/backup-create", emptyMap())
        checkOk(res, "备份失败")
        HostBackup(
            id = res.optLong("id"),
            name = res.optString("name"),
            size = res.optLong("size", 0L),
            time = res.optLong("time", res.optLong("id", 0L))
        )
    }

    suspend fun restoreBackup(name: String): Int = io {
        val res = HostApi.postJson("/api/backup-restore", mapOf("name" to name))
        checkOk(res, "还原失败")
        res.optInt("restored", 0)
    }

    suspend fun deleteBackup(name: String) = io {
        val res = HostApi.postJson("/api/backup-delete", mapOf("name" to name))
        checkOk(res, "删除失败")
    }

    // ============ 激活（卡密登录）============

    /** 卡密激活（免鉴权）。成功后写入本地卡密并复位失效标志。domain 与生成卡密时绑定的域名对应。 */
    suspend fun activate(context: Context, domain: String, card: String, password: String): Result<String> = io {
        val res = HostApi.postJsonNoAuth("/api/activate",
            mapOf("domain" to domain, "card" to card, "password" to password))
        if (res.optBoolean("ok", false)) {
            HostConfig.saveCard(context, card, password)
            HostConfig.setPerm(context, res.optString("perm", "B").ifBlank { "B" })
            Result.success(res.optString("card", card))
        } else {
            Result.failure(ApiException(res.optString("error", "激活失败")))
        }
    }

    private fun checkOk(res: JSONObject, fallback: String) {
        if (!res.optBoolean("ok", false)) {
            throw ApiException(res.optString("error", "").ifBlank { fallback })
        }
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }
}
