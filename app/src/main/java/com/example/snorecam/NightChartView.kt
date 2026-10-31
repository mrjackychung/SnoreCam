package com.example.snorecam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max

/** 一晚概览:横轴是钟点,每根柱子 = 一段录像的鼾声峰值,柱顶小圆点颜色 = 姿势标记。 */
class NightChartView(ctx: Context) : View(ctx) {
    var sessions: List<Session> = emptyList()
        set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hm = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        p.style = Paint.Style.FILL
        p.color = Color.parseColor("#121B26")
        c.drawRect(0f, 0f, w, h, p)

        if (sessions.isEmpty()) {
            p.color = Theme.SUB; p.textSize = 13 * d; p.textAlign = Paint.Align.CENTER
            c.drawText("还没有记录", w / 2, h / 2, p)
            return
        }
        val left = 30 * d
        val right = w - 10 * d
        val top = 18 * d
        val bottom = h - 22 * d
        val cw = right - left
        val chh = bottom - top

        var t0 = sessions.minOf { it.id } - 10 * 60000L
        var t1 = sessions.maxOf { it.id } + 10 * 60000L
        if (t1 - t0 < 3600000L) t1 = t0 + 3600000L
        val span = (t1 - t0).toFloat()

        // 参考线
        p.strokeWidth = 1 * d
        p.textSize = 10 * d
        for (db in doubleArrayOf(Loud.LIGHT, Loud.LOUD, Loud.EPIC)) {
            val y = bottom - Loud.frac(db).toFloat() * chh
            p.color = Color.argb(55, 255, 255, 255)
            c.drawLine(left, y, right, y, p)
            p.color = Color.argb(170, 255, 255, 255)
            p.textAlign = Paint.Align.RIGHT
            c.drawText("${db.toInt()}", left - 4 * d, y + 3 * d, p)
        }

        // 钟点刻度
        p.color = Theme.SUB
        p.textAlign = Paint.Align.CENTER
        var tick = ((t0 / 3600000L) + 1) * 3600000L
        while (tick < t1) {
            val x = left + (tick - t0) / span * cw
            c.drawText(hm.format(Date(tick)), x, h - 6 * d, p)
            tick += 3600000L
        }

        // 柱子
        val bw = max(5 * d, 60000f / span * cw)
        for (s in sessions) {
            val x = left + (s.id - t0) / span * cw
            val bh = max(2 * d, Loud.frac(s.peakDb).toFloat() * chh)
            p.color = Loud.color(s.peakDb)
            c.drawRect(x - bw / 2, bottom - bh, x + bw / 2, bottom, p)
            p.color = Theme.labelColor(s.label)
            c.drawCircle(x, bottom - bh - 6 * d, 3.5f * d, p)
        }
    }
}
