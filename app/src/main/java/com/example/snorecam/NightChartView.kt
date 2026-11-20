package com.example.snorecam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max

/**
 * 一晚概览:横轴是钟点,每根柱子 = 一段录像的鼾声峰值,柱顶圆点颜色 = 姿势标记。
 * 可以在图上按住拖动来选择时间(橙色游标),选中的那段录像会被框出来。
 */
class NightChartView(ctx: Context) : View(ctx) {
    var sessions: List<Session> = emptyList()
        set(v) { field = v; invalidate() }
    var cursorMs: Long = -1L
        set(v) { field = v; invalidate() }
    var selectedId: Long = -1L
        set(v) { field = v; invalidate() }
    var onPick: ((Long) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hm = SimpleDateFormat("HH:mm", Locale.getDefault())

    /** 图表覆盖的时间范围(滑块也用同一个范围) */
    fun range(): Pair<Long, Long> {
        if (sessions.isEmpty()) return Pair(0L, 1L)
        val t0 = sessions.minOf { it.id } - 10 * 60000L
        var t1 = sessions.maxOf { it.id } + 10 * 60000L
        if (t1 - t0 < 3600000L) t1 = t0 + 3600000L
        return Pair(t0, t1)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        p.style = Paint.Style.FILL
        p.color = Color.parseColor("#121B26")
        c.drawRect(0f, 0f, w, h, p)

        if (sessions.isEmpty()) {
            p.color = Theme.SUB; p.textSize = 13 * d; p.textAlign = Paint.Align.CENTER
            c.drawText(Str.chartEmpty(), w / 2, h / 2, p)
            return
        }
        val left = 30 * d
        val right = w - 10 * d
        val top = 14 * d
        val bottom = h - 20 * d
        val cw = right - left
        val chh = bottom - top
        val (t0, t1) = range()
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
            c.drawText(hm.format(Date(tick)), x, h - 5 * d, p)
            tick += 3600000L
        }

        // 柱子
        val bw = max(5 * d, 60000f / span * cw)
        for (s in sessions) {
            val x = left + (s.id - t0) / span * cw
            val bh = max(2 * d, Loud.frac(s.peakDb).toFloat() * chh)
            p.style = Paint.Style.FILL
            p.color = Loud.color(s.peakDb)
            c.drawRect(x - bw / 2, bottom - bh, x + bw / 2, bottom, p)
            p.color = Theme.labelColor(s.label)
            c.drawCircle(x, bottom - bh - 6 * d, 3.5f * d, p)
            if (s.id == selectedId) {
                p.style = Paint.Style.STROKE
                p.color = Color.WHITE
                p.strokeWidth = 1.5f * d
                c.drawRect(x - bw / 2 - 2 * d, bottom - bh - 2 * d, x + bw / 2 + 2 * d, bottom, p)
                p.style = Paint.Style.FILL
            }
        }

        // 游标
        if (cursorMs in t0..t1) {
            val cx = left + (cursorMs - t0) / span * cw
            p.color = Theme.ACCENT
            p.strokeWidth = 2 * d
            c.drawLine(cx, top - 6 * d, cx, bottom, p)
            c.drawCircle(cx, top - 6 * d, 4 * d, p)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (sessions.isEmpty()) return false
        if (e.action == MotionEvent.ACTION_DOWN || e.action == MotionEvent.ACTION_MOVE) {
            parent?.requestDisallowInterceptTouchEvent(true)
            val (t0, t1) = range()
            val left = 30 * d
            val cw = max(1f, width - 40 * d)
            val f = ((e.x - left) / cw).coerceIn(0f, 1f)
            onPick?.invoke(t0 + (f * (t1 - t0)).toLong())
        }
        return true
    }
}
