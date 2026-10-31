package com.example.snorecam

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

/** 记录总览:两种姿势的分贝对比 + 最近一晚的鼾声图 + 每段录像列表。 */
class ReviewActivity : AppCompatActivity() {

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private class Card(val root: LinearLayout, val big: TextView, val sub: TextView)

    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
    private lateinit var list: ListView
    private lateinit var btnCam: Button
    private lateinit var tvDiff: TextView
    private lateinit var chart: NightChartView
    private lateinit var cardSide: Card
    private lateinit var cardSupine: Card
    private var sessions: List<Session> = emptyList()
    private val adapter = RowAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        list = ListView(this).apply {
            divider = null
            dividerHeight = 0
            setBackgroundColor(Theme.BG)
        }
        list.addHeaderView(buildHeader(), null, false)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            val i = position - list.headerViewsCount
            if (i in sessions.indices) {
                startActivity(Intent(this, PlaybackActivity::class.java).putExtra("id", sessions[i].id))
            }
        }
        list.setOnItemLongClickListener { _, _, position, _ ->
            val i = position - list.headerViewsCount
            if (i in sessions.indices) confirmDelete(sessions[i])
            true
        }
        setContentView(list)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun makeCard(name: String, color: Int): Card {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.rounded(Theme.CARD, dpf(14f))
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        root.addView(TextView(this).apply { text = "● $name"; setTextColor(color); textSize = 13f })
        val big = TextView(this).apply {
            text = "--"; setTextColor(Color.WHITE); textSize = 30f; typeface = Typeface.DEFAULT_BOLD
        }
        root.addView(big)
        root.addView(TextView(this).apply { text = "平均鼾声峰值 dB"; setTextColor(Theme.SUB); textSize = 11f })
        val sub = TextView(this).apply { text = "暂无数据"; setTextColor(Theme.SUB); textSize = 12f; setPadding(0, dp(4), 0, 0) }
        root.addView(sub)
        return Card(root, big, sub)
    }

    private fun buildHeader(): View {
        val h = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.BG)
            setPadding(dp(16), dp(28), dp(16), dp(8))
        }
        h.addView(TextView(this).apply {
            text = "鼾声记录"; textSize = 26f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        })

        // 姿势对比
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14), 0, 0)
        }
        cardSide = makeCard("侧睡", Theme.SIDE)
        cardSupine = makeCard("仰睡", Theme.SUPINE)
        row.addView(cardSide.root, LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(0, 0, dp(6), 0) })
        row.addView(cardSupine.root, LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(dp(6), 0, 0, 0) })
        h.addView(row)
        tvDiff = TextView(this).apply {
            textSize = 14f; setTextColor(Theme.ACCENT); gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(4))
        }
        h.addView(tvDiff)

        // 最近一晚
        h.addView(TextView(this).apply {
            text = "最近一晚(柱高=鼾声峰值,圆点=姿势)"; textSize = 13f; setTextColor(Theme.SUB)
            setPadding(0, dp(10), 0, dp(6))
        })
        chart = NightChartView(this)
        h.addView(chart, LinearLayout.LayoutParams(MATCH, dp(150)))
        h.addView(TextView(this).apply {
            text = "● 侧睡   ● 仰睡   ● 未标记"; textSize = 11f; setTextColor(Theme.SUB)
            gravity = Gravity.CENTER; setPadding(0, dp(6), 0, dp(10))
            val sp = android.text.SpannableString(text)
            val t = text.toString()
            fun paint(word: String, color: Int) {
                val i = t.indexOf(word)
                if (i >= 0) sp.setSpan(android.text.style.ForegroundColorSpan(color), i, i + 1, 0)
            }
            paint("● 侧睡", Theme.SIDE)
            paint("● 仰睡", Theme.SUPINE)
            paint("● 未标记", Theme.NONE)
            setText(sp)
        })

        // 镜头位置(影响自动识别姿势的解读)
        btnCam = Button(this).apply { setOnClickListener { toggleCam() } }
        h.addView(btnCam)
        h.addView(TextView(this).apply {
            text = "床边:从床侧拍,正脸朝镜头=侧睡、侧脸=仰睡。\n床尾:从脚边拍,正脸=仰睡、侧脸=侧睡。\n自动识别只是参考,夜里太暗时可能认不出脸,可在回放页手动标记。"
            textSize = 11f; setTextColor(Theme.SUB); setPadding(0, dp(4), 0, dp(14))
        })
        h.addView(TextView(this).apply {
            text = "全部录像(点击回放,长按删除)"; textSize = 13f; setTextColor(Theme.SUB)
            setPadding(0, 0, 0, dp(4))
        })
        return h
    }

    private fun toggleCam() {
        val mode = if (Prefs.camMode(this) == "bedside") "footend" else "bedside"
        Prefs.setCamMode(this, mode)
        val all = EventStore.load(this)
        for (s in all) {
            if (s.autoLabel && s.face.isNotEmpty()) {
                s.label = Posture.labelFor(s.face, mode)
                if (s.label.isEmpty()) s.autoLabel = false
            }
        }
        EventStore.save(this, all)
        refresh()
    }

    private fun refresh() {
        sessions = EventStore.load(this).sortedByDescending { it.id }
        btnCam.text = if (Prefs.camMode(this) == "bedside") "镜头位置:床边(点击切换)" else "镜头位置:床尾(点击切换)"

        fun fill(card: Card, label: String) {
            val s = sessions.filter { it.label == label }
            if (s.isEmpty()) {
                card.big.text = "--"; card.sub.text = "暂无数据"
            } else {
                card.big.text = "%.1f".format(s.map { it.avgPeakDb }.average())
                card.sub.text = "${s.size} 段 · 最大 %.1f dB".format(s.maxOf { it.peakDb })
            }
        }
        fill(cardSide, "side")
        fill(cardSupine, "supine")

        val side = sessions.filter { it.label == "side" }
        val sup = sessions.filter { it.label == "supine" }
        tvDiff.text = if (side.isNotEmpty() && sup.isNotEmpty()) {
            val d = sup.map { it.avgPeakDb }.average() - side.map { it.avgPeakDb }.average()
            "仰睡比侧睡${if (d >= 0) "高" else "低"} %.1f dB".format(abs(d))
        } else "标记两种姿势后,这里显示分贝差"

        val newest = sessions.firstOrNull()?.id
        chart.sessions = if (newest == null) emptyList() else sessions.filter { it.id >= newest - 12 * 3600000L }
        adapter.notifyDataSetChanged()
    }

    private fun confirmDelete(s: Session) {
        AlertDialog.Builder(this)
            .setMessage("删除 ${fmt.format(Date(s.id))} 这段录像?")
            .setPositiveButton("删除") { _, _ ->
                File(s.file).delete()
                File(s.file + ".json").delete()
                EventStore.save(this, EventStore.load(this).filter { it.id != s.id })
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private inner class RowAdapter : BaseAdapter() {
        override fun getCount() = sessions.size
        override fun getItem(position: Int): Any = sessions[position]
        override fun getItemId(position: Int): Long = sessions[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val s = sessions[position]
            val ctx = this@ReviewActivity
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = Theme.rounded(Theme.CARD, ctx.dpf(12f))
                setPadding(ctx.dp(14), ctx.dp(12), ctx.dp(14), ctx.dp(12))
            }
            row.addView(View(ctx).apply {
                background = Theme.rounded(Loud.color(s.peakDb), ctx.dpf(3f))
            }, LinearLayout.LayoutParams(ctx.dp(5), ctx.dp(44)).apply { setMargins(0, 0, ctx.dp(12), 0) })

            val mid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            val line1 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            line1.addView(TextView(ctx).apply {
                text = fmt.format(Date(s.id)); textSize = 16f; setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
            })
            line1.addView(TextView(ctx).apply {
                text = Theme.labelName(s.label) + (if (s.autoLabel) " · 自动" else "")
                textSize = 11f; setTextColor(Color.WHITE)
                background = Theme.rounded(Theme.labelColor(s.label), ctx.dpf(8f))
                setPadding(ctx.dp(8), ctx.dp(2), ctx.dp(8), ctx.dp(2))
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { setMargins(ctx.dp(8), 0, 0, 0) })
            mid.addView(line1)
            mid.addView(TextView(ctx).apply {
                text = "鼾声 ${s.count} 次 · 平均峰值 %.1f dB".format(s.avgPeakDb)
                textSize = 12f; setTextColor(Theme.SUB); setPadding(0, ctx.dp(3), 0, 0)
            })
            row.addView(mid, LinearLayout.LayoutParams(0, WRAP, 1f))

            val right = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
            right.addView(TextView(ctx).apply {
                text = "%.0f".format(s.peakDb); textSize = 26f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(Loud.color(s.peakDb)); gravity = Gravity.END
            })
            right.addView(TextView(ctx).apply { text = "峰值 dB"; textSize = 10f; setTextColor(Theme.SUB); gravity = Gravity.END })
            row.addView(right)

            val wrap = FrameLayout(ctx).apply { setPadding(ctx.dp(16), ctx.dp(5), ctx.dp(16), ctx.dp(5)); setBackgroundColor(Theme.BG) }
            wrap.addView(row, FrameLayout.LayoutParams(MATCH, WRAP))
            return wrap
        }
    }
}
