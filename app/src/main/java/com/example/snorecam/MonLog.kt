package com.example.snorecam

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/** 监测日志:记录开始/停止、每个鼾声事件、触发/保存/失败、每分钟环境声统计,早上用来排查为什么没录。 */
object MonLog {
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, "monitor_log.txt")

    @Synchronized
    fun log(ctx: Context, msg: String) {
        try {
            val f = file(ctx)
            if (f.length() > 200_000) f.writeText(f.readText().takeLast(100_000))
            f.appendText(fmt.format(Date()) + "  " + msg + "\n")
        } catch (_: Exception) {}
    }

    fun read(ctx: Context): String = try { file(ctx).readText() } catch (_: Exception) { "" }

    fun clear(ctx: Context) { try { file(ctx).delete() } catch (_: Exception) {} }
}
