package com.example.snorecam

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 一次 1 分钟录像 = 一条记录。label: "" 未标记 / "side" 侧睡 / "supine" 仰睡 */
data class Session(
    val id: Long,
    val file: String,
    val count: Int,
    val peakDb: Double,
    val avgPeakDb: Double,
    var label: String
)

object EventStore {
    private fun f(ctx: Context) = File(ctx.filesDir, "sessions.json")

    fun load(ctx: Context): MutableList<Session> {
        val out = mutableListOf<Session>()
        try {
            if (!f(ctx).exists()) return out
            val arr = JSONArray(f(ctx).readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Session(o.getLong("id"), o.getString("file"), o.getInt("count"),
                    o.getDouble("peak"), o.getDouble("avgPeak"), o.optString("label", "")))
            }
        } catch (_: Exception) {}
        return out
    }

    fun save(ctx: Context, list: List<Session>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("file", it.file).put("count", it.count)
                .put("peak", it.peakDb).put("avgPeak", it.avgPeakDb).put("label", it.label))
        }
        f(ctx).writeText(arr.toString())
    }

    fun add(ctx: Context, s: Session) {
        val l = load(ctx); l.add(s); save(ctx, l)
    }
}
