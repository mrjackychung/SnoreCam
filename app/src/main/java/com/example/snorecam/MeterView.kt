package com.example.snorecam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.max

/** 横向分贝条:当前 dB + 环境噪声刻度 + 触发线刻度。 */
class MeterView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lo = 20.0
    private val hi = 90.0
    private var db = 0.0
    private var noise = -1.0
    private var threshold = -1.0

    fun set(db: Double, noise: Double, threshold: Double) {
        this.db = db; this.noise = noise; this.threshold = threshold
        invalidate()
    }

    private fun x(v: Double): Float = (((v - lo) / (hi - lo)).coerceIn(0.0, 1.0) * width).toFloat()

    override fun onDraw(c: Canvas) {
        val bh = 12 * d
        val top = 6 * d
        p.style = Paint.Style.FILL
        p.color = Color.parseColor("#22344A")
        c.drawRoundRect(0f, top, width.toFloat(), top + bh, bh / 2, bh / 2, p)
        p.color = Loud.color(db)
        c.drawRoundRect(0f, top, max(bh, x(db)), top + bh, bh / 2, bh / 2, p)

        p.textSize = 10 * d
        p.strokeWidth = 2 * d
        if (noise >= 0) {
            val nx = x(noise)
            p.color = Color.parseColor("#9FB0C3")
            c.drawLine(nx, top - 3 * d, nx, top + bh + 3 * d, p)
            p.textAlign = Paint.Align.RIGHT
            c.drawText("环境 ${noise.toInt()}", nx - 3 * d, top + bh + 15 * d, p)
        }
        if (threshold >= 0) {
            val tx = x(threshold)
            p.color = Theme.ACCENT
            c.drawLine(tx, top - 3 * d, tx, top + bh + 3 * d, p)
            p.textAlign = Paint.Align.LEFT
            c.drawText("触发线 ${threshold.toInt()}", tx + 3 * d, top + bh + 15 * d, p)
        }
    }
}
