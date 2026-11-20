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
import kotlin.math.max

/**
 * 记录总览(重新设计,不再拥挤):
 *  - 顶部按"晚"选择日期(凌晨算前一天晚上),可选「全部」
 *  - 侧睡/仰睡对比卡片
 *  - 选了某一晚后:夜间图 + 时间滑块(拖动选择时间,自动选中最近的一段录像)+ 选中录像卡片
 *  - 下面是该晚的录像列表
 */
class ReviewActivity : AppCompatActivity() {

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private class Card(val root: LinearLayout, val big: TextView, val sub: TextView)

    private val fmtFull = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
    private val fmtTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val fmtHm = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val fmtChip by lazy { SimpleDateFormat("M/d E", Str.locale()) }

    private lateinit var list: ListView
    private lateinit var chipScroll: HorizontalScrollView
    private lateinit var chipRow: LinearLayout
    private lateinit var tvAnalyze: TextView
    private lateinit var tvScope: TextView
    private lateinit var cardSide: Card
    private lateinit var cardSupine: Card
    private lateinit var tvDiff: TextView
    private lateinit var chartBox: LinearLayout
    private lateinit var chart: NightChartView
    private lateinit var seek: SeekBar
    private lateinit var tvSeekTime: TextView
    private lateinit var selCard: LinearLayout
    private lateinit var tvSelTitle: TextView
    private lateinit var tvSelInfo: TextView
    private lateinit var pillSide: TextView
    private lateinit var pillSupine: TextView
    private lateinit var tvListTitle: TextView
    private lateinit var tvEmpty: TextView

    private var all: List<Session> = emptyList()
    private var nights: List<Long> = emptyList()
    private var curNight = -1L          // -1 = 全部
    private var initialized = false
    private var shown: List<Session> = emptyList()
    private var selected: Session? = null
    private var analyzing = false
    private val adapter = RowAdapter()

    /** 睡眠"夜晚"的归属日期:中午 12 点之前算前一天晚上,这样凌晨 1 点的鼾声归到前一晚。 */
    private fun nightKey(id: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = id - 12 * 3600_000L
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Str.init(this)
        list = ListView(this).apply {
            divider = null
            dividerHeight = 0
            setBackgroundColor(Theme.BG)
        }
        list.addHeaderView(buildHeader(), null, false)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            val i = position - list.headerViewsCount
            if (i in shown.indices) openPlayback(shown[i])
        }
        list.setOnItemLongClickListener { _, _, position, _ ->
            val i = position - list.headerViewsCount
            if (i in shown.indices) confirmDelete(shown[i])
            true
        }
        setContentView(list)
    }

    override fun onResume() {
        super.onResume()
        reload(selected?.id)
    }

    private fun openPlayback(s: Session) {
        startActivity(Intent(this, PlaybackActivity::class.java).putExtra("id", s.id))
    }

    // =====================================================================
    //  界面构建
    // =====================================================================

    private fun pill(t: String, onClick: () -> Unit) = TextView(this).apply {
        text = t; textSize = 13f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        background = Theme.rounded(Color.parseColor("#22344A"), dpf(14f))
        setPadding(dp(14), dp(8), dp(14), dp(8))
        setOnClickListener { onClick() }
    }

    private fun makeCard(name: String, color: Int): Card {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.rounded(Theme.CARD, dpf(14f))
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        root.addView(TextView(this).apply { text = "● $name"; setTextColor(color); textSize = 12f })
        val big = TextView(this).apply {
            text = "--"; setTextColor(Color.WHITE); textSize = 26f; typeface = Typeface.DEFAULT_BOLD
        }
        root.addView(big)
        val sub = TextView(this).apply { text = Str.noData(); setTextColor(Theme.SUB); textSize = 11f }
        root.addView(sub)
        return Card(root, big, sub)
    }

    private fun buildHeader(): View {
        val h = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.BG)
            setPadding(dp(16), dp(30), dp(16), dp(6))
        }

        // 标题行
        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(TextView(this).apply {
            text = Str.recordsTitle(); textSize = 24f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        titleRow.addView(TextView(this).apply {
            text = Str.camPosBtn(); textSize = 13f; setTextColor(Theme.ACCENT)
            setPadding(dp(8), dp(8), dp(4), dp(8)); setOnClickListener { showCamDialog() }
        })
        h.addView(titleRow)

        tvAnalyze = TextView(this).apply {
            textSize = 12f; setTextColor(Theme.ACCENT); visibility = View.GONE; setPadding(0, 0, 0, dp(4))
        }
        h.addView(tvAnalyze)

        // 日期选择
        chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        chipScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chipRow)
        }
        h.addView(chipScroll, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })

        // 对比卡片
        tvScope = TextView(this).apply { textSize = 12f; setTextColor(Theme.SUB); setPadding(0, dp(12), 0, dp(6)) }
        h.addView(tvScope)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        cardSide = makeCard(Str.labelName("side"), Theme.SIDE)
        cardSupine = makeCard(Str.labelName("supine"), Theme.SUPINE)
        row.addView(cardSide.root, LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(0, 0, dp(5), 0) })
        row.addView(cardSupine.root, LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(dp(5), 0, 0, 0) })
        h.addView(row)
        tvDiff = TextView(this).apply {
            textSize = 13f; setTextColor(Theme.ACCENT); gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(2))
        }
        h.addView(tvDiff)

        // 夜间图 + 时间滑块 + 选中录像
        chartBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(10), 0, 0) }
        chart = NightChartView(this)
        chartBox.addView(chart, LinearLayout.LayoutParams(MATCH, dp(130)))

        val sliderRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        sliderRow.addView(TextView(this).apply { text = "🕑"; textSize = 14f; setPadding(dp(2), 0, dp(4), 0) })
        seek = SeekBar(this).apply {
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val (t0, t1) = chart.range()
                    onTime(t0 + ((t1 - t0) * (p / 1000.0)).toLong())
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        sliderRow.addView(seek, LinearLayout.LayoutParams(0, WRAP, 1f))
        tvSeekTime = TextView(this).apply {
            textSize = 15f; setTextColor(Theme.ACCENT); typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), 0, dp(2), 0); minWidth = dp(52)
        }
        sliderRow.addView(tvSeekTime)
        chartBox.addView(sliderRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
        chartBox.addView(TextView(this).apply {
            text = Str.sliderHint()
            textSize = 11f; setTextColor(Theme.SUB); setPadding(dp(2), 0, 0, dp(8))
        })

        selCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.rounded(Theme.CARD, dpf(14f))
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        tvSelTitle = TextView(this).apply {
            textSize = 17f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        }
        selCard.addView(tvSelTitle)
        tvSelInfo = TextView(this).apply { textSize = 12f; setTextColor(Theme.SUB); setPadding(0, dp(3), 0, dp(10)) }
        selCard.addView(tvSelInfo)
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val play = pill(Str.playBtn()) { selected?.let { openPlayback(it) } }.apply {
            background = Theme.rounded(Theme.ACCENT, dpf(14f)); setTextColor(Color.parseColor("#1A1100"))
        }
        pillSide = pill(Str.labelName("side")) { setSelectedLabel("side") }
        pillSupine = pill(Str.labelName("supine")) { setSelectedLabel("supine") }
        btns.addView(play)
        btns.addView(pillSide, LinearLayout.LayoutParams(WRAP, WRAP).apply { setMargins(dp(8), 0, 0, 0) })
        btns.addView(pillSupine, LinearLayout.LayoutParams(WRAP, WRAP).apply { setMargins(dp(8), 0, 0, 0) })
        selCard.addView(btns)
        chartBox.addView(selCard)
        h.addView(chartBox)

        tvListTitle = TextView(this).apply {
            textSize = 13f; setTextColor(Theme.SUB); setPadding(dp(2), dp(16), 0, dp(6))
        }
        h.addView(tvListTitle)
        tvEmpty = TextView(this).apply {
            text = Str.noRecords()
            textSize = 14f; setTextColor(Theme.SUB); gravity = Gravity.CENTER
            setPadding(0, dp(40), 0, dp(40)); visibility = View.GONE
        }
        h.addView(tvEmpty)

        chart.onPick = { t ->
            val (t0, t1) = chart.range()
            seek.progress = (((t - t0).toDouble() / (t1 - t0)) * 1000).toInt().coerceIn(0, 1000)
            onTime(t)
        }
        return h
    }

    // =====================================================================
    //  数据 / 选择
    // =====================================================================

    private fun reload(keepId: Long?) {
        all = EventStore.load(this).sortedByDescending { it.id }
        nights = all.map { nightKey(it.id) }.distinct().sorted()
        if (!initialized) {
            curNight = nights.lastOrNull() ?: -1L
            initialized = true
        } else if (curNight != -1L && curNight !in nights) {
            curNight = nights.lastOrNull() ?: -1L
        }
        buildChips()
        applyScope(keepId)
        startAnalysis()
    }

    private fun buildChips() {
        chipRow.removeAllViews()
        var selView: View? = null
        fun addChip(text: String, key: Long) {
            val sel = key == curNight
            val v = TextView(this).apply {
                this.text = text; textSize = 13f
                setTextColor(if (sel) Color.parseColor("#1A1100") else Color.WHITE)
                typeface = if (sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                background = Theme.rounded(if (sel) Theme.ACCENT else Theme.CARD, dpf(16f))
                setPadding(dp(14), dp(8), dp(14), dp(8))
                setOnClickListener { curNight = key; buildChips(); applyScope(null) }
            }
            if (sel) selView = v
            chipRow.addView(v, LinearLayout.LayoutParams(WRAP, WRAP).apply { setMargins(0, 0, dp(8), 0) })
        }
        addChip(Str.all(), -1L)
        for (k in nights) {
            val n = all.count { nightKey(it.id) == k }
            addChip("${fmtChip.format(Date(k))} · $n", k)
        }
        val sv = selView
        chipScroll.post { if (sv != null) chipScroll.scrollTo(max(0, sv.left - dp(48)), 0) }
    }

    private fun fillCard(card: Card, scope: List<Session>, label: String) {
        val s = scope.filter { it.label == label }
        if (s.isEmpty()) {
            card.big.text = "--"; card.sub.text = Str.noData()
        } else {
            card.big.text = "%.1f".format(s.map { it.avgPeakDb }.average())
            card.sub.text = Str.cardSub(s.size, s.maxOf { it.peakDb })
        }
    }

    private fun applyScope(keepId: Long?) {
        val scope = if (curNight == -1L) all else all.filter { nightKey(it.id) == curNight }
        shown = scope
        tvScope.text = if (curNight == -1L) Str.scopeAll() else Str.scopeNight(fmtChip.format(Date(curNight)))
        fillCard(cardSide, scope, "side")
        fillCard(cardSupine, scope, "supine")
        val side = scope.filter { it.label == "side" }
        val sup = scope.filter { it.label == "supine" }
        tvDiff.text = if (side.isNotEmpty() && sup.isNotEmpty()) {
            val d = sup.map { it.avgPeakDb }.average() - side.map { it.avgPeakDb }.average()
            Str.diffText(d)
        } else Str.diffNone()

        val empty = all.isEmpty()
        tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        tvListTitle.visibility = if (empty) View.GONE else View.VISIBLE
        tvListTitle.text = Str.listTitle(curNight == -1L, scope.size)

        if (curNight == -1L || scope.isEmpty()) {
            chartBox.visibility = View.GONE
            selected = null
        } else {
            chartBox.visibility = View.VISIBLE
            chart.sessions = scope.sortedBy { it.id }
            val keep = keepId?.let { id -> scope.find { it.id == id } }
            val sel = keep ?: scope.maxByOrNull { it.peakDb }
            if (sel != null) selectSession(sel, keep == null)
        }
        adapter.notifyDataSetChanged()
    }

    private fun selectSession(s: Session, moveSlider: Boolean) {
        selected = s
        chart.selectedId = s.id
        if (moveSlider) {
            val (t0, t1) = chart.range()
            seek.progress = (((s.id - t0).toDouble() / (t1 - t0)) * 1000).toInt().coerceIn(0, 1000)
            chart.cursorMs = s.id
            tvSeekTime.text = fmtHm.format(Date(s.id))
        }
        updateSelCard()
        adapter.notifyDataSetChanged()
    }

    /** 滑块/图表拖动到某个时刻 */
    private fun onTime(t: Long) {
        chart.cursorMs = t
        tvSeekTime.text = fmtHm.format(Date(t))
        val nearest = shown.minByOrNull { abs(it.id - t) } ?: return
        if (nearest.id != selected?.id) {
            selected = nearest
            chart.selectedId = nearest.id
            updateSelCard()
            adapter.notifyDataSetChanged()
        }
    }

    private fun updateSelCard() {
        val s = selected
        if (s == null) { selCard.visibility = View.GONE; return }
        selCard.visibility = View.VISIBLE
        tvSelTitle.text = fmtTime.format(Date(s.id)) + "  ·  " + Theme.labelName(s.label) +
            (if (s.autoLabel) Str.autoParen() else "")
        tvSelInfo.text = Str.selInfo(s.peakDb, s.avgPeakDb, s.count)
        fun style(v: TextView, sel: Boolean, color: Int) {
            v.background = Theme.rounded(if (sel) color else Color.parseColor("#22344A"), dpf(14f))
        }
        style(pillSide, s.label == "side", Theme.SIDE)
        style(pillSupine, s.label == "supine", Theme.SUPINE)
    }

    private fun setSelectedLabel(l: String) {
        val s = selected ?: return
        EventStore.update(this, s.id) {
            it.label = if (it.label == l) "" else l
            it.autoLabel = false
        }
        reload(s.id)
    }

    // =====================================================================
    //  自动识别还没识别过的录像 / 镜头位置 / 删除
    // =====================================================================

    private fun startAnalysis() {
        if (analyzing) return
        val todo = all.filter { it.face.isEmpty() && File(it.file).exists() }
        if (todo.isEmpty()) { tvAnalyze.visibility = View.GONE; return }
        analyzing = true
        tvAnalyze.visibility = View.VISIBLE
        val appCtx = applicationContext
        Thread {
            for ((i, s) in todo.withIndex()) {
                runOnUiThread { tvAnalyze.text = Str.analyzingProgress(i + 1, todo.size) }
                PostureAnalyzer.analyzeSession(appCtx, s.id, s.file)
                runOnUiThread { if (!isFinishing) reload(selected?.id) }
            }
            runOnUiThread {
                analyzing = false
                tvAnalyze.visibility = View.GONE
                if (!isFinishing) reload(selected?.id)
            }
        }.start()
    }

    private fun showCamDialog() {
        val cur = if (Prefs.camMode(this) == "footend") 1 else 0
        AlertDialog.Builder(this)
            .setTitle(Str.camDialogTitle())
            .setSingleChoiceItems(arrayOf(
                Str.camBedside(),
                Str.camFootend()
            ), cur) { d, which ->
                val mode = if (which == 1) "footend" else "bedside"
                Prefs.setCamMode(this, mode)
                for (s in all) {
                    if (s.autoLabel && s.face.isNotEmpty()) {
                        EventStore.update(this, s.id) {
                            it.label = Posture.labelFor(it.face, mode)
                            if (it.label.isEmpty()) it.autoLabel = false
                        }
                    }
                }
                d.dismiss()
                reload(selected?.id)
            }
            .setNegativeButton(Str.close(), null)
            .show()
    }

    private fun confirmDelete(s: Session) {
        AlertDialog.Builder(this)
            .setMessage(Str.confirmDelete(fmtFull.format(Date(s.id))))
            .setPositiveButton(Str.delete()) { _, _ ->
                File(s.file).delete()
                File(s.file + ".json").delete()
                EventStore.save(this, EventStore.load(this).filter { it.id != s.id })
                reload(null)
            }
            .setNegativeButton(Str.cancel(), null)
            .show()
    }

    // =====================================================================
    //  列表行(紧凑)
    // =====================================================================

    private inner class RowAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int): Any = shown[position]
        override fun getItemId(position: Int): Long = shown[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val s = shown[position]
            val ctx = this@ReviewActivity
            val isSel = curNight != -1L && s.id == selected?.id
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = Theme.rounded(if (isSel) Color.parseColor("#1F3047") else Theme.CARD, ctx.dpf(12f))
                setPadding(ctx.dp(12), ctx.dp(9), ctx.dp(12), ctx.dp(9))
            }
            row.addView(View(ctx).apply {
                background = Theme.rounded(Loud.color(s.peakDb), ctx.dpf(3f))
            }, LinearLayout.LayoutParams(ctx.dp(4), ctx.dp(34)).apply { setMargins(0, 0, ctx.dp(10), 0) })

            val mid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            val line1 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            line1.addView(TextView(ctx).apply {
                text = (if (curNight == -1L) fmtFull else fmtTime).format(Date(s.id))
                textSize = 15f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            })
            line1.addView(TextView(ctx).apply {
                text = Theme.labelName(s.label) + (if (s.autoLabel) Str.autoShort() else "")
                textSize = 10f; setTextColor(Color.WHITE)
                background = Theme.rounded(Theme.labelColor(s.label), ctx.dpf(8f))
                setPadding(ctx.dp(7), ctx.dp(1), ctx.dp(7), ctx.dp(1))
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { setMargins(ctx.dp(8), 0, 0, 0) })
            mid.addView(line1)
            mid.addView(TextView(ctx).apply {
                text = Str.rowInfo(s.count, s.avgPeakDb)
                textSize = 11f; setTextColor(Theme.SUB)
            })
            row.addView(mid, LinearLayout.LayoutParams(0, WRAP, 1f))

            row.addView(TextView(ctx).apply {
                text = "%.0f".format(s.peakDb); textSize = 22f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(Loud.color(s.peakDb)); gravity = Gravity.END
            })
            row.addView(TextView(ctx).apply {
                text = " dB"; textSize = 11f; setTextColor(Theme.SUB); setPadding(0, ctx.dp(8), 0, 0)
            })

            val wrap = FrameLayout(ctx).apply {
                setPadding(ctx.dp(16), ctx.dp(4), ctx.dp(16), ctx.dp(4)); setBackgroundColor(Theme.BG)
            }
            wrap.addView(row, FrameLayout.LayoutParams(MATCH, WRAP))
            return wrap
        }
    }
}
