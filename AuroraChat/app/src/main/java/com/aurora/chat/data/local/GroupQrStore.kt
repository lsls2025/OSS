package com.aurora.chat.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * 群二维码位图缓存：按 groupId 一张 PNG 落盘。
 *
 * 为什么要它：群二维码页面在无网时仍能「生成」（内容只是官网落地页 URL，本地编码即可），
 * 但落地页离线打不开 → 扫了也没用；更糟的是群号（display_id）以前不缓存，离线就是 0，卡面群号一栏空白。
 * 用户要求「优先用上一次生成的二维码，永远生成失败才进兜底」，于是把上次成功合成的卡面位图存下来，
 * 离线/生成受挫时直接复用，至少界面上永远有一张看得见的、群号正确的二维码。
 *
 * 写穿：每次成功合成即覆盖；读：打开页面首帧立即取，避免重新编码的短暂空白。
 * 不做 TTL：群号/群名变了由下次联网合成覆盖。
 */
object GroupQrStore {
    private const val DIR = "group_qr_cache"

    private fun dir(ctx: Context): File = File(ctx.applicationContext.filesDir, DIR)

    private fun file(ctx: Context, groupId: Long): File =
        File(dir(ctx), "$groupId.png")

    fun save(ctx: Context, groupId: Long, bmp: Bitmap) {
        try {
            val d = dir(ctx)
            if (!d.exists()) d.mkdirs()
            val f = file(ctx, groupId)
            val tmp = File(d, "${f.name}.tmp")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            tmp.renameTo(f)
        } catch (_: Exception) { }
    }

    fun load(ctx: Context, groupId: Long): Bitmap? = try {
        val f = file(ctx, groupId)
        if (!f.exists()) null else BitmapFactory.decodeFile(f.absolutePath)
    } catch (_: Exception) {
        null
    }

    fun clear(ctx: Context, groupId: Long) {
        try { file(ctx, groupId).delete() } catch (_: Exception) { }
    }
}
