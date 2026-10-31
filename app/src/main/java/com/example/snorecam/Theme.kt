package com.example.snorecam

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable

object Theme {
    val BG = Color.parseColor("#0F1620")
    val CARD = Color.parseColor("#172230")
    val SUB = Color.parseColor("#8A97A6")
    val ACCENT = Color.parseColor("#FF9F43")
    val SIDE = Color.parseColor("#5AA9FF")
    val SUPINE = Color.parseColor("#FF7AB6")
    val NONE = Color.parseColor("#6B7888")

    fun labelColor(l: String): Int = when (l) { "side" -> SIDE; "supine" -> SUPINE; else -> NONE }
    fun labelName(l: String): String = when (l) { "side" -> "侧睡"; "supine" -> "仰睡"; else -> "未标记" }
    fun rounded(color: Int, radiusPx: Float): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = radiusPx }
}

/** 响度分级(相对值,手机麦克风未校准):安静 / 轻微 / 响亮 / 超响 */
object Loud {
    const val LIGHT = 50.0
    const val LOUD = 60.0
    const val EPIC = 70.0
    private val cQuiet = Color.parseColor("#4B7F86")
    private val cLight = Color.parseColor("#2FBFA5")
    private val cLoud = Color.parseColor("#E0B070")
    private val cEpic = Color.parseColor("#F0703C")

    fun color(db: Double): Int = when {
        db >= EPIC -> cEpic
        db >= LOUD -> cLoud
        db >= LIGHT -> cLight
        else -> cQuiet
    }

    /** dB → 0..1 的显示高度 */
    fun frac(db: Double): Double = ((db - 35.0) / 45.0).coerceIn(0.0, 1.0)
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density
fun fmtTime(ms: Long): String = "%d:%02d".format(ms / 60000, (ms / 1000) % 60)
