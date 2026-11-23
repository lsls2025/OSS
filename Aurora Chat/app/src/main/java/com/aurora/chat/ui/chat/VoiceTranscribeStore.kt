package com.aurora.chat.ui.chat

import android.content.Context
import com.aurora.chat.ui.chat.media.MediaStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.LinkedHashMap

/**
 * 语音「转文字」状态仓库：长按语音消息 → 菜单「转文字」→ 这里驱动识别，
 * 语音气泡下方按 [states] 渲染识别卡片（识别中 / 结果 / 失败重试）。
 *
 * 设计要点：
 *  · 以「媒体 URL」为键（与 MessageBubble 渲染层用的是同一个 mediaKey，两边必然对上）；
 *  · 内存 StateFlow 驱动 UI，成功结果同时落盘 filesDir/voice_transcribe.json（上限 300 条），
 *    重进会话/重启 App 后直接显示历史识别结果，不用再转一次；
 *  · 同一条语音同时只跑一个任务（jobs 去重），取消即中断识别并移除卡片；
 *  · 全程 Mutex 之外的轻量同步：短临界区用 synchronized，磁盘 IO 切 Dispatchers.IO。
 */
object VoiceTranscribeStore {

    enum class State { RUNNING, DONE, FAILED }

    data class Entry(
        val state: State,
        /** DONE：识别文本；RUNNING：局部识别文本（可能为空） */
        val text: String = "",
        /** FAILED：中文错误文案 */
        val error: String = "",
        val seconds: Int = 0,
        val updatedAt: Long = System.currentTimeMillis()
    )

    private val _states = MutableStateFlow<Map<String, Entry>>(emptyMap())
    val states: StateFlow<Map<String, Entry>> = _states.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val jobs = mutableMapOf<String, Job>()
    private val jobLock = Any()

    private const val DISK_FILE = "voice_transcribe.json"
    private const val DISK_CAP = 300
    private val diskLock = Any()
    private var diskLoaded = false
    /** key -> (text, seconds)，LinkedHashMap 保持写入序，超限时淘汰最旧 */
    private var diskMap: LinkedHashMap<String, Pair<String, Int>>? = null

    /**
     * 渲染层与菜单层共用的媒体键：优先取 MediaStore 固化的可达 URL，取不到退回原始 URL。
     * 两侧都走这个函数，键必然一致（不会出现「点了菜单但卡片找不到状态」）。
     */
    fun mediaKey(msgId: Long, rawUrl: String): String =
        if (msgId > 0) (MediaStore.get(msgId)?.url ?: rawUrl) else rawUrl

    /**
     * 开始（或重新开始）识别。已在识别中的同键调用会被忽略。
     *
     * @param key     [mediaKey] 算出的媒体键
     * @param seconds 语音时长（秒），用于超时预估
     */
    fun start(context: Context, key: String, seconds: Int) {
        val app = context.applicationContext
        synchronized(jobLock) { if (jobs.containsKey(key)) return }
        val job = scope.launch {
            try {
                withContext(Dispatchers.IO) { ensureLoaded(app) }
                _states.update { it + (key to Entry(State.RUNNING, seconds = seconds)) }
                val text = VoiceTranscribeEngine.transcribe(app, key, seconds) { partial ->
                    _states.update { cur ->
                        val e = cur[key]
                        // 只在仍是 RUNNING 时更新局部文字，避免把失败/完成态冲掉
                        if (e != null && e.state == State.RUNNING) {
                            cur + (key to e.copy(text = partial))
                        } else cur
                    }
                }
                _states.update { it + (key to Entry(State.DONE, text = text, seconds = seconds)) }
                withContext(Dispatchers.IO) { persistDone(app, key, text, seconds) }
            } catch (e: CancellationException) {
                _states.update { it - key }
                throw e
            } catch (e: Exception) {
                val msg = e.message?.takeIf { it.isNotBlank() } ?: "识别失败，请重试"
                _states.update { cur ->
                    val e0 = cur[key]
                    if (e0 != null && e0.state == State.RUNNING) {
                        cur + (key to e0.copy(state = State.FAILED, error = msg))
                    } else {
                        cur + (key to Entry(State.FAILED, error = msg, seconds = seconds))
                    }
                }
            } finally {
                synchronized(jobLock) { jobs.remove(key) }
            }
        }
        synchronized(jobLock) { jobs[key] = job }
    }

    /** 取消识别（识别中的卡片直接消失，不影响已落盘的历史结果）。 */
    fun cancel(key: String) {
        synchronized(jobLock) { jobs.remove(key) }?.cancel()
        _states.update { it - key }
    }

    /** 失败后重试。 */
    fun retry(context: Context, key: String, seconds: Int) = start(context, key, seconds)

    /**
     * 预热：把磁盘上的历史识别结果灌进内存态。
     * 由语音卡片首次组合时调用（IO 线程执行，不阻塞 UI）。
     */
    fun warmUp(context: Context) {
        if (diskLoaded) return
        val app = context.applicationContext
        scope.launch { withContext(Dispatchers.IO) { ensureLoaded(app) } }
    }

    // ---------- 磁盘缓存 ----------

    private fun ensureLoaded(app: Context) {
        synchronized(diskLock) {
            if (diskLoaded) return
            val map = LinkedHashMap<String, Pair<String, Int>>()
            runCatching {
                val f = File(app.filesDir, DISK_FILE)
                if (f.exists()) {
                    val obj = JSONObject(f.readText())
                    obj.keys().forEach { k ->
                        val o = obj.optJSONObject(k) ?: return@forEach
                        val t = o.optString("t")
                        if (t.isNotEmpty()) map[k] = t to o.optInt("s")
                    }
                }
            }
            diskMap = map
            diskLoaded = true
            if (map.isNotEmpty()) {
                val done = map.mapValues { Entry(State.DONE, text = it.value.first, seconds = it.value.second) }
                // 只补空缺键： RUNNING 的任务绝不被旧磁盘结果覆盖
                _states.update { cur -> cur + done.filterKeys { it !in cur } }
            }
        }
    }

    private fun persistDone(app: Context, key: String, text: String, seconds: Int) {
        synchronized(diskLock) {
            val map = diskMap ?: return
            map.remove(key)
            map[key] = text to seconds
            while (map.size > DISK_CAP) {
                val oldest = map.keys.firstOrNull() ?: break
                map.remove(oldest)
            }
            val obj = JSONObject()
            for ((k, v) in map) {
                val o = JSONObject()
                o.put("t", v.first)
                o.put("s", v.second)
                obj.put(k, o)
            }
            runCatching {
                val f = File(app.filesDir, DISK_FILE)
                val tmp = File(app.filesDir, "$DISK_FILE.tmp")
                tmp.writeText(obj.toString())
                if (f.exists()) f.delete()
                if (!tmp.renameTo(f)) {
                    tmp.copyTo(f, overwrite = true)
                    tmp.delete()
                }
            }
        }
    }
}
