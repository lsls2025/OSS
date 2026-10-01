package com.aurora.chat.data.local

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 服务器离线缓存：将服务器数据备份到本地文件
 * 无网络时自动使用缓存，联网后自动同步。
 * 文件读写之上加一层带 TTL 的 LRU 内存缓存，避免轮询时反复落盘/读盘造成主线程抖动。
 */
object ServerCache {

    private const val TTL = 30_000L
    private data class Entry(val data: String, val ts: Long)
    private val mem = LinkedHashMap<String, Entry>(64, 0.75f, true)

    private fun memGet(key: String): String? {
        val e = mem[key] ?: return null
        return if (System.currentTimeMillis() - e.ts < TTL) e.data else { mem.remove(key); null }
    }
    private fun memPut(key: String, data: String) { mem[key] = Entry(data, System.currentTimeMillis()) }

    private fun cacheDir(ctx: Context): File {
        val dir = File(ctx.filesDir, "server_cache")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun serverDir(ctx: Context, serverId: Long): File {
        val dir = File(cacheDir(ctx), serverId.toString())
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    // ── 网络检测 ──
    fun isOnline(ctx: Context): Boolean {
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val nw = cm.activeNetwork ?: return false
            val cap = cm.getNetworkCapabilities(nw) ?: return false
            return cap.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) { return false }
    }

    // ── 服务器信息缓存 ──
    fun saveMyServer(ctx: Context, serverId: Long, jsonData: String) {
        memPut("s:$serverId", jsonData)
        try { File(serverDir(ctx, serverId), "server_info.json").writeText(jsonData) } catch (_: Exception) {}
    }

    fun loadMyServer(ctx: Context, serverId: Long): String? {
        memGet("s:$serverId")?.let { return it }
        val f = File(serverDir(ctx, serverId), "server_info.json")
        if (!f.exists()) return null
        return try { f.readText().also { memPut("s:$serverId", it) } } catch (_: Exception) { null }
    }

    fun saveLoggedInServers(ctx: Context, jsonData: String) {
        memPut("logged", jsonData)
        try { File(cacheDir(ctx), "logged_in_servers.json").writeText(jsonData) } catch (_: Exception) {}
    }

    fun loadLoggedInServers(ctx: Context): String? {
        memGet("logged")?.let { return it }
        val f = File(cacheDir(ctx), "logged_in_servers.json")
        if (!f.exists()) return null
        return try { f.readText().also { memPut("logged", it) } } catch (_: Exception) { null }
    }

    // ── 文件列表缓存 ──
    fun saveFileList(ctx: Context, serverId: Long, folderId: Long, jsonData: String) {
        memPut("f:$serverId:$folderId", jsonData)
        try { File(serverDir(ctx, serverId), "files_${folderId}.json").writeText(jsonData) } catch (_: Exception) {}
    }

    fun loadFileList(ctx: Context, serverId: Long, folderId: Long): String? {
        memGet("f:$serverId:$folderId")?.let { return it }
        val f = File(serverDir(ctx, serverId), "files_${folderId}.json")
        if (!f.exists()) return null
        return try { f.readText().also { memPut("f:$serverId:$folderId", it) } } catch (_: Exception) { null }
    }

    // ── 清理 ──
    fun clearAll(ctx: Context) {
        mem.clear()
        try { cacheDir(ctx).deleteRecursively() } catch (_: Exception) {}
    }

    fun clearServer(ctx: Context, serverId: Long) {
        mem.remove("s:$serverId")
        try { serverDir(ctx, serverId).deleteRecursively() } catch (_: Exception) {}
    }
}
