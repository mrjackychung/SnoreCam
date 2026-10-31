package com.example.snorecam

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max

/** 回放:视频 + 声浪时间轴(带分贝、鼾声段、可拖动),下方标记姿势。 */
class PlaybackActivity : AppCompatActivity() {

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private lateinit var session: Session
    private lateinit var video: VideoView
    private lateinit var timeline: TimelineView
    private lateinit var tvPos: TextView
    private lateinit var tvNow: TextView
    private lateinit var tvPeak: TextView
    private lateinit var tvPosture: TextView
    private lateinit var btnPlay: Button
    private lateinit var btnSide: Button
    private lateinit var btnSupine: Button
    private var tl: Timeline? = null
    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { update(); ui.postDelayed(this, 50) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getLongExtra("id", 0L)
        val found = EventStore.load(this).find { it.id == id }
        if (found == null) { finish(); return }
        session = found
        tl = EventStore.loadTimeline(session.file)
        buildUi()
        refreshLabels()
        if (session.face.isEmpty()) runAnalysis()
    }

    private fun statCol(label: String, init: String, color: Int): Pair<LinearLayout, TextView> {
        val v = TextView(this).apply {
            text = init; textSize = 26f; setTextColor(color); gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        val l = TextView(this).apply {
            text = label; textSize = 12f; setTextColor(Theme.SUB); gravity = Gravity.CENTER
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(v)
        col.addView(l)
        return Pair(col, v)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.BG)
        }

        // 顶栏
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(16), dp(6))
        }
        top.addView(TextView(this).apply {
            text = "‹ 返回"; textSize = 16f; setTextColor(Theme.ACCENT)
            setPadding(dp(8), dp(8), dp(14), dp(8))
            setOnClickListener { finish() }
        })
        top.addView(TextView(this).apply {
            text = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(session.id))
            textSize = 16f; setTextColor(Color.WHITE)
        })
        root.addView(top)

        // 视频
        val vbox = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        video = VideoView(this)
        vbox.addView(video, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER))
        root.addView(vbox, LinearLayout.LayoutParams(MATCH, 0, 1f))

        // 数值行
        val stats = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(10), dp(16), dp(2))
        }
        val c1 = statCol("播放位置", "0:00", Color.WHITE)
        val c2 = statCol("此刻 dB", "--", Color.WHITE)
        val c3 = statCol("本段峰值 dB", "%.1f".format(session.peakDb), Loud.color(session.peakDb))
        tvPos = c1.second; tvNow = c2.second; tvPeak = c3.second
        for (c in listOf(c1.first, c2.first, c3.first)) stats.addView(c, LinearLayout.LayoutParams(0, WRAP, 1f))
        root.addView(stats)

        // 时间轴
        timeline = TimelineView(this)
        tl?.let { timeline.data = it.db; timeline.stepMs = it.stepMs; timeline.events = it.events }
        timeline.durationMs = max(60000L, (tl?.db?.size ?: 0) * (tl?.stepMs ?: 256).toLong())
        timeline.onSeek = { t -> video.seekTo(t.toInt()); timeline.positionMs = t; timeline.invalidate() }
        root.addView(timeline, LinearLayout.LayoutParams(MATCH, dp(170)).apply {
            setMargins(dp(12), dp(4), dp(12), 0)
        })

        // 图例
        val legend = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(2))
        }
        val items = listOf(
            Pair("安静 <50", Loud.color(0.0)), Pair("轻微 50-60", Loud.color(55.0)),
            Pair("响亮 60-70", Loud.color(65.0)), Pair("超响 ≥70", Loud.color(75.0))
        )
        for (item in items) legend.addView(TextView(this).apply {
            text = "● ${item.first}"; textSize = 11f; setTextColor(item.second); setPadding(dp(7), 0, dp(7), 0)
        })
        root.addView(legend)

        // 播放控制
        val ctrl = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val bBack = Button(this).apply { text = "⏪ 5秒"; setOnClickListener { seekBy(-5000) } }
        btnPlay = Button(this).apply { text = "▶ 播放"; setOnClickListener { togglePlay() } }
        val bFwd = Button(this).apply { text = "5秒 ⏩"; setOnClickListener { seekBy(5000) } }
        ctrl.addView(bBack); ctrl.addView(btnPlay); ctrl.addView(bFwd)
        root.addView(ctrl)

        // 姿势
        tvPosture = TextView(this).apply {
            textSize = 13f; setTextColor(Theme.SUB); gravity = Gravity.CENTER
            setPadding(dp(12), dp(4), dp(12), dp(2))
        }
        root.addView(tvPosture)
        val pos = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(10))
        }
        btnSide = Button(this).apply { text = "侧睡"; setOnClickListener { setLabel("side") } }
        btnSupine = Button(this).apply { text = "仰睡"; setOnClickListener { setLabel("supine") } }
        val btnAuto = Button(this).apply { text = "自动识别姿势"; setOnClickListener { runAnalysis() } }
        pos.addView(btnSide); pos.addView(btnSupine); pos.addView(btnAuto)
        root.addView(pos)

        setContentView(root)

        video.setOnPreparedListener { mp ->
            val dur = mp.duration.toLong()
            timeline.durationMs = max(dur, (tl?.db?.size ?: 0) * (tl?.stepMs ?: 256).toLong())
            timeline.invalidate()
            video.seekTo(1)
        }
        video.setOnCompletionListener { btnPlay.text = "▶ 播放" }
        video.setOnClickListener { togglePlay() }
        video.setVideoPath(session.file)
    }

    private fun togglePlay() { if (video.isPlaying) video.pause() else video.start() }

    private fun seekBy(deltaMs: Int) {
        val t = (video.currentPosition + deltaMs).coerceIn(0, max(1, video.duration))
        video.seekTo(t)
    }

    private fun update() {
        if (!::video.isInitialized) return
        val pos = video.currentPosition.toLong()
        timeline.positionMs = pos
        timeline.invalidate()
        tvPos.text = fmtTime(pos)
        tl?.let {
            val i = (pos / it.stepMs).toInt()
            if (i in it.db.indices) {
                val db = it.db[i]
                tvNow.text = "%.0f".format(db)
                tvNow.setTextColor(Loud.color(db.toDouble()))
            }
        }
        btnPlay.text = if (video.isPlaying) "⏸ 暂停" else "▶ 播放"
    }

    // ---------- 姿势 ----------

    private fun refreshLabels() {
        btnSide.alpha = if (session.label == "side") 1f else 0.45f
        btnSupine.alpha = if (session.label == "supine") 1f else 0.45f
        val faceT = when (session.face) {
            "frontal" -> "识别到正脸"
            "profile" -> "识别到侧脸"
            "none" -> "没识别到人脸(画面太暗或没拍到脸)"
            else -> "未识别"
        }
        val lbl = if (session.label.isEmpty()) "未标记"
        else Theme.labelName(session.label) + (if (session.autoLabel) "(自动)" else "")
        tvPosture.text = "$faceT · 当前:$lbl"
    }

    private fun setLabel(l: String) {
        val all = EventStore.load(this)
        val s = all.find { it.id == session.id } ?: return
        s.label = if (s.label == l) "" else l
        s.autoLabel = false
        EventStore.save(this, all)
        session = s
        refreshLabels()
    }

    private fun runAnalysis() {
        tvPosture.text = "姿势识别中…"
        Thread {
            val face = try { PostureAnalyzer.analyze(session.file) } catch (e: Throwable) { "none" }
            runOnUiThread { if (!isFinishing) applyFace(face) }
        }.start()
    }

    private fun applyFace(face: String) {
        val all = EventStore.load(this)
        val s = all.find { it.id == session.id } ?: return
        s.face = face
        val auto = Posture.labelFor(face, Prefs.camMode(this))
        if (auto.isNotEmpty() && (s.label.isEmpty() || s.autoLabel)) {
            s.label = auto
            s.autoLabel = true
        }
        EventStore.save(this, all)
        session = s
        refreshLabels()
    }

    override fun onResume() {
        super.onResume()
        if (::session.isInitialized) ui.post(tick)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick)
        if (::video.isInitialized) video.pause()
    }
}
