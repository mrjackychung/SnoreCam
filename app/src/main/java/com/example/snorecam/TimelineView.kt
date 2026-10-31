package com.example.snorecam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.max

/** 回放用的声浪时间轴:镜像条形图,按响度着色,橙色播放头可拖动跳转。 */
class TimelineView(ctx: Context) : View(ctx) {
    var data = FloatArray(0)
    var stepMs = 256
    var events: List<LongArray> = emptyList()
    var durationMs = 60000L
    var positionMs = 0L
    var onSeek: ((Long) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bg = Color.parseColor("#121B26")

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val top = 6 * d
        val ch = h - 24 * d - top
        val cy = top + ch / 2
        val dur = max(1L, durationMs)

        p.style = Paint.Style.FILL
        p.color = bg
        c.drawRect(0f, top, w, top + ch, p)

        // 鼾声事件底色
        p.color = Color.argb(45, 255, 255, 255)
        for (e in events) {
            val x1 = (e[0] * w / dur).coerceIn(0f, w)
            val x2 = ((e[0] + e[1]) * w / dur).coerceIn(0f, w)
            if (x2 > x1) c.drawRect(x1, top, x2, top + ch, p)
        }

        // 分贝参考线
        p.strokeWidth = 1 * d
        p.textSize = 10 * d
        p.textAlign = Paint.Align.LEFT
        for (db in doubleArrayOf(Loud.LIGHT, Loud.LOUD, Loud.EPIC)) {
            val off = Loud.frac(db).toFloat() * ch / 2 * 0.92f
            p.color = Color.argb(60, 255, 255, 255)
            c.drawLine(0f, cy - off, w, cy - off, p)
            c.drawLine(0f, cy + off, w, cy + off, p)
            p.color = Color.argb(170, 255, 255, 255)
            c.drawText("${db.toInt()}", 4 * d, cy - off - 2 * d, p)
        }

        // 声浪条
        if (data.isEmpty()) {
            p.color = Color.parseColor("#8A97A6")
            p.textAlign = Paint.Align.CENTER
            p.textSize = 13 * d
            c.drawText("没有声轨数据", w / 2, cy, p)
        } else {
            val n = max(1, (dur / stepMs).toInt())
            val bw = max(1.5f * d, w / n * 0.75f)
            for (i in data.indices) {
                val db = data[i].toDouble()
                val x = (i * stepMs + stepMs / 2f) * w / dur
                val half = max(1.5f * d, Loud.frac(db).toFloat() * ch / 2 * 0.92f)
                p.color = Loud.color(db)
                c.drawRect(x - bw / 2, cy - half, x + bw / 2, cy + half, p)
            }
        }

        // 时间刻度
        p.textSize = 10 * d
        p.color = Color.parseColor("#8A97A6")
        var t = 0L
        while (t <= dur) {
            val x = t * w / dur
            p.textAlign = when {
                x < 14 * d -> Paint.Align.LEFT
                x > w - 14 * d -> Paint.Align.RIGHT
                else -> Paint.Align.CENTER
            }
            c.drawText(fmtTime(t), x, h - 6 * d, p)
            t += 10000
        }

        // 播放头
        val px = positionMs.coerceIn(0L, dur) * w / dur
        p.color = Theme.ACCENT
        p.strokeWidth = 2 * d
        c.drawLine(px, top, px, top + ch, p)
        c.drawCircle(px, top, 4 * d, p)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_DOWN || e.action == MotionEvent.ACTION_MOVE) {
            parent?.requestDisallowInterceptTouchEvent(true)
            val t = (e.x / max(1, width) * durationMs).toLong().coerceIn(0L, durationMs)
            onSeek?.invoke(t)
        }
        return true
    }
}
