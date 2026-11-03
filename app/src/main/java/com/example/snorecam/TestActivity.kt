package com.example.snorecam

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

/**
 * 灵敏度测试向导:
 * 第 1 步测 6 秒环境噪声 → 第 2 步模仿打呼 12 秒 → 给出推荐灵敏度,一键采用 → 再模仿几次验证。
 */
class TestActivity : AppCompatActivity() {

    companion object {
        const val ST_QUIET_INTRO = 0
        const val ST_QUIET = 1
        const val ST_SNORE_INTRO = 2
        const val ST_SNORE = 3
        const val ST_RESULT = 4
        const val ST_LIVE = 5
    }

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private val detector = SnoreDetector().apply { minDb = 0.0; marginDb = 3.0 }
    private var engine: AudioEngine? = null
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var tvDb: TextView
    private lateinit var meter: MeterView
    private lateinit var tvStep: TextView
    private lateinit var tvBody: TextView
    private lateinit var tvVerify: TextView
    private lateinit var bar: ProgressBar
    private lateinit var btnMain: Button
    private lateinit var btnApply: Button

    private var state = ST_QUIET_INTRO
    private var stateStart = 0L
    private val quiet = ArrayList<Double>()
    private val events = ArrayList<SnoreDetector.Event>()
    private val candDb = ArrayList<Double>()
    private var noiseN = 30.0
    private var recMargin = 10
    private var verifyCount = 0

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startEngine() else tvBody.text = "需要麦克风权限才能测试。请到系统设置里允许。"
    }

    private fun hasMic() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private val timer = object : Runnable {
        override fun run() {
            val dur = if (state == ST_QUIET) 6000L else 12000L
            val el = SystemClock.elapsedRealtime() - stateStart
            bar.progress = ((el * 100) / dur).toInt().coerceIn(0, 100)
            val left = ((dur - el) / 1000 + 1).coerceAtLeast(0)
            tvBody.text = if (state == ST_QUIET) "保持安静…还剩 $left 秒"
            else "请模仿打呼…还剩 $left 秒\n已识别到 ${events.size} 次类似鼾声"
            if (el >= dur) {
                if (state == ST_QUIET) finishQuiet() else finishSnore()
            } else ui.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(28), dp(18), dp(24))
        }
        val root = ScrollView(this).apply { setBackgroundColor(Theme.BG); addView(col) }

        col.addView(TextView(this).apply {
            text = "‹ 返回"; textSize = 16f; setTextColor(Theme.ACCENT)
            setPadding(0, dp(4), dp(14), dp(10)); setOnClickListener { finish() }
        })
        col.addView(TextView(this).apply {
            text = "灵敏度测试"; textSize = 28f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        })
        col.addView(TextView(this).apply {
            text = "跟着两步走,我根据你的环境和打呼声推荐灵敏度。"
            textSize = 14f; setTextColor(Theme.SUB); setPadding(0, dp(4), 0, dp(12))
        })

        // 实时分贝卡片
        val live = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.rounded(Theme.CARD, dpf(16f))
            setPadding(dp(16), dp(12), dp(16), dp(10))
        }
        live.addView(TextView(this).apply { text = "实时声音"; textSize = 13f; setTextColor(Theme.SUB) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        tvDb = TextView(this).apply {
            text = "--"; textSize = 44f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        }
        row.addView(tvDb)
        row.addView(TextView(this).apply {
            text = " dB"; textSize = 16f; setTextColor(Theme.SUB); setPadding(dp(4), 0, 0, dp(8))
        })
        live.addView(row)
        meter = MeterView(this)
        live.addView(meter, LinearLayout.LayoutParams(MATCH, dp(40)))
        col.addView(live)

        // 步骤卡片
        val step = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.rounded(Theme.CARD, dpf(16f))
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        tvStep = TextView(this).apply {
            textSize = 18f; setTextColor(Theme.ACCENT); typeface = Typeface.DEFAULT_BOLD
        }
        step.addView(tvStep)
        tvBody = TextView(this).apply {
            textSize = 15f; setTextColor(Color.WHITE); setLineSpacing(0f, 1.25f); setPadding(0, dp(8), 0, dp(8))
        }
        step.addView(tvBody)
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE }
        step.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))
        tvVerify = TextView(this).apply {
            textSize = 20f; setTextColor(Color.parseColor("#2FBFA5")); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(6)); visibility = View.GONE
        }
        step.addView(tvVerify)
        btnApply = Button(this).apply {
            isAllCaps = false; textSize = 16f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#1A1100")); background = Theme.rounded(Theme.ACCENT, dpf(14f))
            visibility = View.GONE
        }
        step.addView(btnApply, LinearLayout.LayoutParams(MATCH, dp(50)).apply { topMargin = dp(8) })
        btnMain = Button(this).apply {
            isAllCaps = false; textSize = 16f; setTextColor(Color.WHITE)
            background = Theme.rounded(Color.parseColor("#2A3F58"), dpf(14f))
        }
        step.addView(btnMain, LinearLayout.LayoutParams(MATCH, dp(50)).apply { topMargin = dp(10) })
        col.addView(step, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(14) })

        setContentView(root)
        showQuietIntro()
    }

    // ---------- 引擎 ----------

    private fun startEngine() {
        if (!hasMic()) { permLauncher.launch(Manifest.permission.RECORD_AUDIO); return }
        if (engine == null) engine = AudioEngine(this, detector, { onFrame(it) }, {})
        engine?.start()
    }

    override fun onResume() { super.onResume(); startEngine() }

    override fun onPause() {
        super.onPause()
        engine?.stop()
        ui.removeCallbacks(timer)
        if (state == ST_QUIET || state == ST_SNORE) showQuietIntro()
    }

    private fun onFrame(fr: SnoreDetector.Frame) {
        tvDb.text = "%.0f".format(fr.db)
        meter.set(fr.db, fr.noise, if (state == ST_LIVE) fr.threshold else -1.0)
        when (state) {
            ST_QUIET -> quiet.add(fr.db)
            ST_SNORE -> {
                fr.event?.let { events.add(it) }
                if (fr.candidate) candDb.add(fr.db)
            }
            ST_LIVE -> fr.event?.let {
                verifyCount++
                tvVerify.text = "✓ 检测到鼾声 %.0f dB(第 %d 次)".format(it.peakDb, verifyCount)
            }
        }
    }

    // ---------- 步骤 ----------

    private fun showQuietIntro() {
        state = ST_QUIET_INTRO
        tvStep.text = "第 1 步 · 测量环境噪声"
        tvBody.text = "把手机放到你睡觉时放的位置(床头/枕边),保持安静 6 秒。\n空调、风扇保持你平时睡觉时的状态。"
        bar.visibility = View.GONE; tvVerify.visibility = View.GONE; btnApply.visibility = View.GONE
        btnMain.visibility = View.VISIBLE
        btnMain.text = "开始测量"
        btnMain.setOnClickListener { startQuiet() }
    }

    private fun startQuiet() {
        state = ST_QUIET
        quiet.clear()
        stateStart = SystemClock.elapsedRealtime()
        bar.progress = 0; bar.visibility = View.VISIBLE
        btnMain.visibility = View.GONE
        ui.post(timer)
    }

    private fun finishQuiet() {
        noiseN = if (quiet.isEmpty()) 30.0 else percentile(quiet, 0.9)
        detector.noiseFloor = noiseN
        detector.minDb = 0.0; detector.marginDb = 3.0
        state = ST_SNORE_INTRO
        bar.visibility = View.GONE
        tvStep.text = "第 2 步 · 模仿打呼"
        tvBody.text = "环境噪声约 ${noiseN.roundToInt()} dB ✓\n\n" +
            "现在躺到平时睡觉的姿势,用喉咙发出低沉的「呼噜」声,一吸一呼,重复约 12 秒。\n" +
            "声音大小尽量接近你真实打呼的程度。"
        btnMain.visibility = View.VISIBLE
        btnMain.text = "开始模仿"
        btnMain.setOnClickListener { startSnore() }
    }

    private fun startSnore() {
        state = ST_SNORE
        events.clear(); candDb.clear()
        stateStart = SystemClock.elapsedRealtime()
        bar.progress = 0; bar.visibility = View.VISIBLE
        btnMain.visibility = View.GONE
        ui.post(timer)
    }

    private fun finishSnore() {
        state = ST_RESULT
        bar.visibility = View.GONE
        btnMain.visibility = View.VISIBLE
        btnMain.text = "重新测试"
        btnMain.setOnClickListener { showQuietIntro() }
        tvStep.text = "测试结果"

        val s: Double? = when {
            events.size >= 2 -> percentile(events.map { it.peakDb }, 0.25)
            candDb.size >= 6 -> percentile(candDb, 0.75)
            else -> null
        }
        if (s == null) {
            tvBody.text = "没有识别到像鼾声的声音 😕\n\n试试:\n① 手机放近一点\n② 声音再低沉、再响一点(像「呼——噜」)\n③ 用喉咙而不是哼歌"
            btnApply.visibility = View.GONE
            return
        }
        val gap = s - noiseN
        recMargin = (gap * 0.5).roundToInt().coerceIn(4, 20)
        val sb = StringBuilder()
        sb.append("环境噪声:约 ${noiseN.roundToInt()} dB\n")
        sb.append("你的打呼声:约 ${s.roundToInt()} dB(比环境高 ${gap.roundToInt()} dB)\n")
        sb.append("识别到 ${events.size} 次类似鼾声\n\n")
        sb.append("⭐ 推荐灵敏度:$recMargin\n")
        sb.append("意思是:声音比环境噪声高出 $recMargin dB 以上、并且像鼾声,才会触发录像。\n\n")
        sb.append("真正睡着后的鼾声通常比模仿时轻,所以我按你模仿声的一半左右来设,宁可灵敏一点。")
        if (noiseN > 55) sb.append("\n\n⚠ 环境噪声偏大,可能影响检测。若可以,关掉风扇/空调后再测一次。")
        if (gap < 8) sb.append("\n\n⚠ 你的模仿声和环境差别不大,手机再靠近一点会更准。")
        tvBody.text = sb.toString()

        btnApply.visibility = View.VISIBLE
        btnApply.text = "采用推荐值 $recMargin"
        btnApply.setOnClickListener { applyRecommended() }
    }

    private fun applyRecommended() {
        Prefs.setMargin(this, recMargin)
        detector.marginDb = recMargin.toDouble()
        detector.minDb = 35.0
        state = ST_LIVE
        verifyCount = 0
        tvStep.text = "已保存 ✓ 再验证一下"
        tvBody.text = "灵敏度已设为 $recMargin。\n现在再模仿几次打呼:橙色触发线是触发门槛,检测到鼾声会在下面显示 ✓。\n如果老是不触发,回首页把灵敏度数值调小一点。"
        tvVerify.text = "等待鼾声…"
        tvVerify.visibility = View.VISIBLE
        btnApply.text = "完成,返回首页"
        btnApply.setOnClickListener { finish() }
        btnMain.visibility = View.GONE
    }

    private fun percentile(list: List<Double>, p: Double): Double {
        if (list.isEmpty()) return 0.0
        val sorted = list.sorted()
        return sorted[((sorted.size - 1) * p).toInt()]
    }
}
