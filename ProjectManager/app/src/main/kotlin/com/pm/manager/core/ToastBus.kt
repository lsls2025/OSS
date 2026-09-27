package com.pm.manager.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * 统一轻提示入口。
 *
 * 原来每个界面各写 `Toast.makeText(...)`，且大量在网络线程回调里直接调用（部分 ROM 上会抛
 * "Can't toast on a thread that has not called Looper.prepare()"）。这里统一收敛：
 * 非主线程自动切回主线程，长文本自动升级为 LONG，避免长错误信息一闪而过看不完。
 */
object ToastBus {
    private val mainHandler = Handler(Looper.getMainLooper())

    fun show(context: Context, message: String, long: Boolean = false) {
        val ctx = context.applicationContext
        val duration = if (long || message.length > 18) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Toast.makeText(ctx, message, duration).show()
        } else {
            mainHandler.post { Toast.makeText(ctx, message, duration).show() }
        }
    }
}
