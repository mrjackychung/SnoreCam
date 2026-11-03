package com.example.snorecam

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 一次 1 分钟录像 = 一条记录。
 * label: "" 未标记 / "side" 侧睡 / "supine" 仰睡
 * face : "" 未分析 / "frontal" 正脸 / "profile" 侧脸 / "none" 没识别到人脸
 */
data class Session(
    val id: Long,
    val file: String,
    val count: Int,
    val peakDb: Double,
    val avgPeakDb: Double,
    var label: String,
    var face: String = "",
    var autoLabel: Boolean = false
)

/** 录像期间的 dB 时间序列(每 stepMs 一个点)和鼾声事件 [起点ms, 时长ms, 峰值dB*10] */
class Timeline(val stepMs: Int, val db: FloatArray, val events: List<LongArray>)

object Prefs {
    fun camMode(c: Context): String =
        c.getSharedPreferences("prefs", Context.MODE_PRIVATE).getString("camMode", "bedside") ?: "bedside"
    fun setCamMode(c: Context, m: String) {
        c.getSharedPreferences("prefs", Context.MODE_PRIVATE).edit().putString("camMode", m).apply()
    }

    /** 灵敏度:高于环境噪声多少 dB 才算鼾声(4~20) */
    fun margin(c: Context): Int =
        c.getSharedPreferences("prefs", Context.MODE_PRIVATE).getInt("margin", 10)
    fun setMargin(c: Context, v: Int) {
        c.getSharedPreferences("prefs", Context.MODE_PRIVATE).edit().putInt("margin", v).apply()
    }

    /** 点开始后延迟多少分钟才开始检测(睡前听音乐用),0 = 不延迟 */
    fun delayMin(c: Context): Int =
        c.getSharedPreferences("prefs", Context.MODE_PRIVATE).getInt("delayMin", 0)
    fun setDelayMin(c: Context, v: Int) {
        c.getSharedPreferences("prefs", Context.MODE_PRIVATE).edit().putInt("delayMin", v).apply()
    }

    /** 录像用哪个摄像头:"back" 后置 / "front" 前置(前置时屏幕当补光灯) */
    fun facing(c: Context): String =
        c.getSharedPreferences("prefs", Context.MODE_PRIVATE).getString("facing", "back") ?: "back"
    fun setFacing(c: Context, v: String) {
        c.getSharedPreferences("prefs", Context.MODE_PRIVATE).edit().putString("facing", v).apply()
    }

    /** 补光时屏幕亮度上限 0.05~1.0 */
    fun fillMax(c: Context): Float =
        c.getSharedPreferences("prefs", Context.MODE_PRIVATE).getFloat("fillMax", 0.30f)
    fun setFillMax(c: Context, v: Float) {
        c.getSharedPreferences("prefs", Context.MODE_PRIVATE).edit().putFloat("fillMax", v).apply()
    }

    /** 从黑到最亮用多少秒 */
    fun rampSec(c: Context): Int =
        c.getSharedPreferences("prefs", Context.MODE_PRIVATE).getInt("rampSec", 25)
    fun setRampSec(c: Context, v: Int) {
        c.getSharedPreferences("prefs", Context.MODE_PRIVATE).edit().putInt("rampSec", v).apply()
    }
}

object Posture {
    /**
     * bedside 床边拍:正脸朝镜头 = 侧睡,侧脸 = 仰睡
     * footend 床尾拍:正脸 = 仰睡,侧脸 = 侧睡
     */
    fun labelFor(face: String, camMode: String): String = when (face) {
        "frontal" -> if (camMode == "footend") "supine" else "side"
        "profile" -> if (camMode == "footend") "side" else "supine"
        else -> ""
    }
}

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
                    o.getDouble("peak"), o.getDouble("avgPeak"), o.optString("label", ""),
                    o.optString("face", ""), o.optBoolean("auto", false)))
            }
        } catch (_: Exception) {}
        return out
    }

    fun save(ctx: Context, list: List<Session>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("file", it.file).put("count", it.count)
                .put("peak", it.peakDb).put("avgPeak", it.avgPeakDb).put("label", it.label)
                .put("face", it.face).put("auto", it.autoLabel))
        }
        f(ctx).writeText(arr.toString())
    }

    fun add(ctx: Context, s: Session) {
        val l = load(ctx); l.add(s); save(ctx, l)
    }

    fun saveTimeline(videoPath: String, db: FloatArray, events: List<LongArray>) {
        try {
            val o = JSONObject()
            o.put("step", 256)
            val a = JSONArray()
            db.forEach { a.put(Math.round(it * 10) / 10.0) }
            o.put("db", a)
            val e = JSONArray()
            events.forEach { e.put(JSONArray().put(it[0]).put(it[1]).put(it[2])) }
            o.put("ev", e)
            File("$videoPath.json").writeText(o.toString())
        } catch (_: Exception) {}
    }

    fun loadTimeline(videoPath: String): Timeline? {
        return try {
            val file = File("$videoPath.json")
            if (!file.exists()) return null
            val o = JSONObject(file.readText())
            val a = o.getJSONArray("db")
            val db = FloatArray(a.length()) { a.getDouble(it).toFloat() }
            val ev = ArrayList<LongArray>()
            val e = o.optJSONArray("ev")
            if (e != null) {
                for (i in 0 until e.length()) {
                    val x = e.getJSONArray(i)
                    ev.add(longArrayOf(x.getLong(0), x.getLong(1), x.getLong(2)))
                }
            }
            Timeline(o.optInt("step", 256), db, ev)
        } catch (e: Exception) { null }
    }
}
