package com.example.snorecam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.max

/** 滚动声轨:每帧一根竖条,红色=疑似鼾声帧,蓝色=普通声音。约显示最近 19 秒。 */
class WaveformView(ctx: Context) : View(ctx) {
    private val n = 300
    private val vals = FloatArray(n)
    private val hot = BooleanArray(n)
    private var pos = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cBlue = Color.parseColor("#4FC3F7")
    private val cRed = Color.parseColor("#FF5252")

    fun push(db: Double, isHot: Boolean) {
        vals[pos] = ((db - 30.0) / 50.0).coerceIn(0.0, 1.0).toFloat()
        hot[pos] = isHot
        pos = (pos + 1) % n
        if (visibility == VISIBLE) invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val step = w / n
        paint.strokeWidth = step * 0.7f
        for (i in 0 until n) {
            val idx = (pos + i) % n
            val half = max(2f, vals[idx] * h / 2f * 0.9f)
            paint.color = if (hot[idx]) cRed else cBlue
            val x = i * step + step / 2
            c.drawLine(x, h / 2 - half, x, h / 2 + half, paint)
        }
    }
}
