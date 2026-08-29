package com.dshpet.android.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用内置日志系统：按日期落盘（filesDir/logs/yyyy-MM-dd.log）。
 * 应用内「关于 → 查看日志」可浏览/复制/分享；未捕获异常自动写入。
 */
object AppLog {

    private var dir: File? = null

    fun init(ctx: Context) {
        dir = File(ctx.filesDir, "logs").apply { mkdirs() }
    }

    @Synchronized
    fun log(tag: String, msg: String) {
        val d = dir ?: return
        val now = Date()
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(now)
        val f = File(d, "$date.log")
        try {
            f.appendText("$time [$tag] $msg\n")
        } catch (e: Exception) {
            Log.w("AppLog", "写入日志失败", e)
        }
        // 日志保留最近 14 天，防膨胀
        try {
            val cutoff = System.currentTimeMillis() - 14L * 24 * 3600 * 1000
            d.listFiles { it.extension == "log" }?.forEach {
                if (it.lastModified() < cutoff) it.delete()
            }
        } catch (e: Exception) {
        }
    }

    /** 按日期倒序的日志文件列表 */
    fun files(): List<File> =
        dir?.listFiles { it.extension == "log" }
            ?.sortedByDescending { it.name }
            ?: emptyList()

    fun read(f: File): String = runCatching { f.readText() }.getOrDefault("(读取失败)")

    fun crashLog(): String = files().joinToString("\n\n==== ${'='} ${'='} ====\n\n") { f ->
        "== ${f.name} ==\n${read(f)}"
    }
}
