package com.example.snorecam

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    companion object {
        const val CLIP_MS = 60_000L           // 每次录像时长
        const val TRIGGER_WINDOW_MS = 45_000L // 45 秒内出现 >=2 次鼾声事件才触发
        const val IDLE_AFTER_MS = 30_000L     // 最后一次鼾声后 30 秒回到黑屏
        const val VIDEO_WITH_AUDIO = true     // 若发现录像没声音,改成 false
        const val BRIGHT_IDLE = 0.01f
        const val BRIGHT_ACTIVE = 0.12f
    }

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    // ---- 视图 ----
    private lateinit var wave: WaveformView
    private lateinit var tvDb: TextView
    private lateinit var tvStatus: TextView
    private lateinit var lightView: View
    private lateinit var home: ScrollView
    private lateinit var panel: LinearLayout
    private lateinit var tvPanelTitle: TextView
    private lateinit var tvPanelDb: TextView
    private lateinit var tvPanelInfo: TextView
    private lateinit var tvLiveDb: TextView
    private lateinit var tvLiveState: TextView
    private lateinit var tvThresh: TextView
    private lateinit var meter: MeterView
    private lateinit var btnGrant: Button
    private lateinit var segBack: TextView
    private lateinit var segFront: TextView
    private lateinit var tvSens: TextView
    private lateinit var seekSens: SeekBar
    private lateinit var fillBox: LinearLayout
    private lateinit var tvFill: TextView
    private lateinit var tvRamp: TextView
    private lateinit var seekFill: SeekBar
    private lateinit var seekRamp: SeekBar
    private lateinit var btnStart: Button
    private lateinit var tvDelay: TextView
    private lateinit var seekDelay: SeekBar
    private lateinit var btnArmNow: Button

    private val ui = Handler(Looper.getMainLooper())
    private val detector = SnoreDetector()
    private val engine by lazy {
        AudioEngine(this, detector, { fr -> onFrame(fr) }, { msg -> MonLog.log(this, "⚠ $msg") })
    }

    // ---- 状态 ----
    private var monitoring = false
    private var activeUi = false
    private var busy = false
    private var busySince = 0L
    private var lastEventAt = 0L
    private var pendingStart = false
    private var previewTick = 0
    private var countdown = 0
    private var clipsSaved = 0
    private var eventsTotal = 0

    // ---- 延迟开始检测(睡前听音乐)----
    private var armed = true
    private var armAt = 0L
    private val armRunnable = object : Runnable {
        override fun run() {
            if (!monitoring || armed) return
            if (armAt - SystemClock.elapsedRealtime() <= 0L) arm()
            else {
                if (panel.visibility == View.VISIBLE && countdown <= 0) updatePanelTitle()
                ui.postDelayed(this, 1000)
            }
        }
    }

    // ---- 每分钟统计(写日志用)----
    private var minStartAt = 0L
    private var mSum = 0.0
    private var mN = 0
    private var mMax = -200.0
    private var mMin = 200.0
    private var mCand = 0
    private var mEvents = 0

    // ---- 屏幕补光 ----
    private var fillActive = false
    private var fadingOut = false
    private var lightLevel = 0f
    private var lightStart = 0L
    private val lightRunnable = object : Runnable { override fun run() { stepLight() } }

    // ---- 录像 ----
    private var cameraProvider: ProcessCameraProvider? = null
    private var recording: Recording? = null
    private var sessionStart = 0L
    private var sessionEvents = ArrayList<SnoreDetector.Event>()
    private val recentEvents = ArrayList<SnoreDetector.Event>()
    private var videoStartMs = 0L
    private val timelineDb = ArrayList<Float>()
    private var bucketMax = -200.0
    private var bucketN = 0

    private val stopClipRunnable = Runnable { recording?.stop() }
    private val hidePanelRunnable = Runnable { if (monitoring) hidePanel() }
    private val countdownRunnable = object : Runnable {
        override fun run() {
            if (!monitoring) return
            if (countdown > 0) {
                tvPanelTitle.text = Str.countdown(countdown)
                countdown--
                ui.postDelayed(this, 1000)
            } else {
                updatePanelTitle()
                hidePanel()
                enterIdleUi()
            }
        }
    }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        val allOk = r.values.all { it }
        if (pendingStart && allOk) startMonitoring()
        else if (pendingStart) Toast.makeText(this, Str.needPerms(), Toast.LENGTH_LONG).show()
        pendingStart = false
        refreshPreview()
    }

    // =====================================================================
    //  界面
    // =====================================================================

    private fun hasPerm(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    private fun hasMic() = hasPerm(Manifest.permission.RECORD_AUDIO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Str.init(this)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        wave = WaveformView(this).apply { visibility = View.GONE }
        root.addView(wave, FrameLayout.LayoutParams(MATCH, MATCH))
        tvDb = TextView(this).apply {
            textSize = 44f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; visibility = View.GONE
        }
        root.addView(tvDb, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP).apply { topMargin = dp(60) })
        tvStatus = TextView(this).apply {
            textSize = 16f; setTextColor(Color.LTGRAY); gravity = Gravity.CENTER; visibility = View.GONE
        }
        root.addView(tvStatus, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { bottomMargin = dp(60) })
        lightView = View(this).apply { visibility = View.GONE }
        root.addView(lightView, FrameLayout.LayoutParams(MATCH, MATCH))

        buildPanel()
        root.addView(panel, FrameLayout.LayoutParams(dp(300), WRAP, Gravity.CENTER))
        buildHome()
        root.addView(home, FrameLayout.LayoutParams(MATCH, MATCH))

        root.setOnClickListener {
            if (monitoring) { if (panel.visibility == View.VISIBLE) hidePanel() else showPanel(true) }
        }
        setContentView(root)

        if (!hasMic() || !hasPerm(Manifest.permission.CAMERA)) {
            permLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        }
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Theme.rounded(Theme.CARD, dpf(16f))
        setPadding(dp(16), dp(14), dp(16), dp(14))
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(top) }

    private fun label(t: String, size: Float = 13f, color: Int = Theme.SUB) =
        TextView(this).apply { text = t; textSize = size; setTextColor(color) }

    private fun softButton(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 14f; setTextColor(Color.WHITE)
        background = Theme.rounded(Color.parseColor("#22344A"), dpf(14f))
        setOnClickListener { onClick() }
    }

    private fun segItem(t: String, onClick: () -> Unit) = TextView(this).apply {
        text = t; textSize = 14f; gravity = Gravity.CENTER; setOnClickListener { onClick() }
    }

    private fun seekListener(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { if (u) onChange(p) }
        override fun onStartTrackingTouch(s: SeekBar?) {}
        override fun onStopTrackingTouch(s: SeekBar?) {}
    }

    private fun buildHome() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(40), dp(18), dp(28))
        }
        home = ScrollView(this).apply { setBackgroundColor(Theme.BG); addView(col) }

        col.addView(label("SnoreCam", 30f, Color.WHITE).apply { typeface = Typeface.DEFAULT_BOLD })
        col.addView(label(Str.homeSubtitle(), 14f), lp(4))

        // --- 实时环境声音 ---
        val live = card()
        live.addView(label(Str.liveTitle()))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        tvLiveDb = TextView(this).apply {
            text = "--"; textSize = 44f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        }
        row.addView(tvLiveDb)
        row.addView(label(" dB", 16f).apply { setPadding(dp(4), 0, 0, dp(8)) })
        tvLiveState = label("", 14f, Theme.ACCENT).apply { gravity = Gravity.END }
        row.addView(tvLiveState, LinearLayout.LayoutParams(0, WRAP, 1f))
        live.addView(row)
        meter = MeterView(this)
        live.addView(meter, LinearLayout.LayoutParams(MATCH, dp(40)))
        tvThresh = label("", 12f)
        live.addView(tvThresh, lp(2))
        btnGrant = softButton(Str.grantMic()) {
            permLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        }.apply { visibility = View.GONE }
        live.addView(btnGrant, lp(8))
        col.addView(live, lp(16))

        // --- 设置 ---
        val set = card()
        set.addView(label(Str.camera()))
        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Theme.rounded(Theme.BG, dpf(12f))
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        segBack = segItem(Str.backCam()) { Prefs.setFacing(this, "back"); updateFacingUi() }
        segFront = segItem(Str.frontCamLight()) { Prefs.setFacing(this, "front"); updateFacingUi() }
        seg.addView(segBack, LinearLayout.LayoutParams(0, dp(40), 1f))
        seg.addView(segFront, LinearLayout.LayoutParams(0, dp(40), 1f))
        set.addView(seg, lp(6))

        tvSens = label("", 14f, Color.WHITE)
        set.addView(tvSens, lp(16))
        seekSens = SeekBar(this).apply {
            max = 16
            setOnSeekBarChangeListener(seekListener { p ->
                Prefs.setMargin(this@MainActivity, 4 + p)
                detector.marginDb = (4 + p).toDouble()
                updateTexts()
            })
        }
        set.addView(seekSens)
        set.addView(label(Str.sensHint(), 12f), lp(2))
        set.addView(softButton(Str.testBtn()) {
            startActivity(Intent(this, TestActivity::class.java))
        }, lp(8))

        tvDelay = label("", 14f, Color.WHITE)
        set.addView(tvDelay, lp(18))
        seekDelay = SeekBar(this).apply {
            max = 24
            setOnSeekBarChangeListener(seekListener { p ->
                Prefs.setDelayMin(this@MainActivity, p * 5); updateTexts()
            })
        }
        set.addView(seekDelay)
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((txt, mins) in listOf(Str.noDelay() to 0, Str.minutes(30) to 30, Str.minutes(45) to 45, Str.minutes(60) to 60)) {
            chips.addView(softButton(txt) {
                Prefs.setDelayMin(this, mins)
                seekDelay.progress = mins / 5
                updateTexts()
            }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
        set.addView(chips, lp(8))
        set.addView(label(Str.delayHint(), 12f), lp(4))

        fillBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tvFill = label("", 13f, Color.WHITE)
        fillBox.addView(tvFill, lp(14))
        seekFill = SeekBar(this).apply {
            max = 95
            setOnSeekBarChangeListener(seekListener { p ->
                Prefs.setFillMax(this@MainActivity, 0.05f + p / 100f); updateFacingUi()
            })
        }
        fillBox.addView(seekFill)
        tvRamp = label("", 13f, Color.WHITE)
        fillBox.addView(tvRamp, lp(8))
        seekRamp = SeekBar(this).apply {
            max = 50
            setOnSeekBarChangeListener(seekListener { p ->
                Prefs.setRampSec(this@MainActivity, 10 + p); updateFacingUi()
            })
        }
        fillBox.addView(seekRamp)
        fillBox.addView(label(Str.fillHint(), 12f), lp(4))
        set.addView(fillBox, lp(0))
        col.addView(set, lp(12))

        // --- 开始 ---
        btnStart = Button(this).apply {
            text = Str.startMonitoring(); textSize = 18f; isAllCaps = false; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#1A1100"))
            background = Theme.rounded(Theme.ACCENT, dpf(18f))
            setOnClickListener { onStartClicked() }
        }
        col.addView(btnStart, LinearLayout.LayoutParams(MATCH, dp(58)).apply { topMargin = dp(18) })

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(softButton(Str.viewRecords()) {
            startActivity(Intent(this, ReviewActivity::class.java))
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(0, 0, dp(6), 0) })
        row2.addView(softButton(Str.viewLog()) {
            startActivity(Intent(this, LogActivity::class.java))
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(6), 0, 0, 0) })
        col.addView(row2, lp(12))

        // --- 清单 ---
        val tips = card()
        tips.addView(label(Str.checklistTitle()))
        for (t in listOf(
            Str.tip1(),
            Str.tip2(),
            Str.tip3(),
            Str.tip4()
        )) tips.addView(label(t, 14f, Color.WHITE), lp(8))
        col.addView(tips, lp(16))
        val langCard = card()
        langCard.addView(label(Str.langTitle()))
        val langRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val curLang = Prefs.lang(this)
        for ((txt, code) in listOf(Str.langAuto() to "auto", "简体中文" to "zh-Hans", "繁體中文" to "zh-Hant", "English" to "en")) {
            val sel = code == curLang
            langRow.addView(TextView(this).apply {
                text = txt; textSize = 13f; gravity = Gravity.CENTER
                setTextColor(if (sel) Color.parseColor("#1A1100") else Color.WHITE)
                background = Theme.rounded(if (sel) Theme.ACCENT else Color.parseColor("#22344A"), dpf(12f))
                setPadding(dp(4), dp(9), dp(4), dp(9))
                setOnClickListener {
                    Prefs.setLang(this@MainActivity, code)
                    Str.init(this@MainActivity)
                    recreate()
                }
            }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
        langCard.addView(langRow, lp(8))
        col.addView(langCard, lp(16))
        val vc = try {
            androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(packageManager.getPackageInfo(packageName, 0))
        } catch (e: Exception) { 0L }
        col.addView(label(Str.version(vc), 11f).apply { gravity = Gravity.CENTER }, lp(14))
    }

    private fun buildPanel() {
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = Theme.rounded(Color.parseColor("#F2121B26"), dpf(20f))
            setPadding(dp(20), dp(18), dp(20), dp(18))
            visibility = View.GONE
        }
        tvPanelTitle = label(Str.monitoring(), 14f, Theme.ACCENT).apply { gravity = Gravity.CENTER }
        panel.addView(tvPanelTitle)
        tvPanelDb = TextView(this).apply {
            text = "-- dB"; textSize = 40f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        panel.addView(tvPanelDb)
        tvPanelInfo = label("", 13f).apply { gravity = Gravity.CENTER }
        panel.addView(tvPanelInfo)
        btnArmNow = Button(this).apply {
            text = Str.armNow(); isAllCaps = false; textSize = 14f; setTextColor(Color.WHITE)
            background = Theme.rounded(Color.parseColor("#2A3F58"), dpf(14f))
            visibility = View.GONE
            setOnClickListener { arm() }
        }
        panel.addView(btnArmNow, LinearLayout.LayoutParams(MATCH, dp(44)).apply { topMargin = dp(10) })
        panel.addView(Button(this).apply {
            text = Str.stopMonitoring(); isAllCaps = false; textSize = 16f; setTextColor(Color.WHITE)
            background = Theme.rounded(Color.parseColor("#D9534F"), dpf(14f))
            setOnClickListener { stopMonitoring() }
        }, LinearLayout.LayoutParams(MATCH, dp(48)).apply { topMargin = dp(14) })
        panel.addView(label(Str.panelHint(), 11f).apply { gravity = Gravity.CENTER }, lp(8))
    }

    private fun loadSettings() {
        val m = Prefs.margin(this)
        seekSens.progress = (m - 4).coerceIn(0, 16)
        detector.marginDb = m.toDouble()
        seekFill.progress = ((Prefs.fillMax(this) - 0.05f) * 100).toInt().coerceIn(0, 95)
        seekRamp.progress = (Prefs.rampSec(this) - 10).coerceIn(0, 50)
        seekDelay.progress = (Prefs.delayMin(this) / 5).coerceIn(0, 24)
        updateTexts()
        updateFacingUi()
    }

    private fun updateTexts() {
        val m = Prefs.margin(this)
        tvSens.text = Str.sensLabel(m)
        val d = Prefs.delayMin(this)
        tvDelay.text = if (d > 0) Str.delayOn(d) else Str.delayOff()
        btnStart.text = if (d > 0) Str.startWithDelay(d) else Str.startMonitoring()
    }

    private fun updateFacingUi() {
        val front = Prefs.facing(this) == "front"
        fun style(v: TextView, sel: Boolean) {
            v.background = if (sel) Theme.rounded(Theme.ACCENT, dpf(10f)) else null
            v.setTextColor(if (sel) Color.parseColor("#1A1100") else Theme.SUB)
            v.typeface = if (sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        style(segBack, !front)
        style(segFront, front)
        fillBox.visibility = if (front) View.VISIBLE else View.GONE
        tvFill.text = Str.fillMaxLabel((Prefs.fillMax(this) * 100).toInt())
        tvRamp.text = Str.rampLabel(Prefs.rampSec(this))
    }

    // =====================================================================
    //  生命周期 / 实时预览
    // =====================================================================

    override fun onResume() {
        super.onResume()
        if (monitoring) MonLog.log(this, "App back in foreground")
        else { loadSettings(); refreshPreview() }
    }

    override fun onPause() {
        super.onPause()
        if (monitoring) MonLog.log(this, "⚠ App went to background (screen may have turned off or app switched; monitoring may be interrupted)")
        else engine.stop()
    }

    private fun refreshPreview() {
        if (monitoring) return
        if (hasMic()) { btnGrant.visibility = View.GONE; engine.start() }
        else {
            btnGrant.visibility = View.VISIBLE
            tvLiveState.text = Str.needMicShort()
        }
    }

    private fun previewFrame(fr: SnoreDetector.Frame) {
        tvLiveDb.text = "%.0f".format(fr.db)
        meter.set(fr.db, fr.noise, fr.threshold)
        tvLiveState.text = when {
            fr.candidate -> Str.stateSnore()
            fr.db >= fr.threshold -> Str.stateLoud()
            fr.db >= fr.noise + 3 -> Str.stateSound()
            else -> Str.stateQuiet()
        }
        if (previewTick++ % 8 == 0) {
            tvThresh.text = Str.threshInfo(fr.noise.toInt(), Prefs.margin(this), fr.threshold.toInt())
        }
    }

    // =====================================================================
    //  开始 / 停止监测
    // =====================================================================

    private fun onStartClicked() {
        val need = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (need.all { hasPerm(it) }) startMonitoring()
        else { pendingStart = true; permLauncher.launch(need) }
    }

    private fun startMonitoring() {
        monitoring = true
        clipsSaved = 0; eventsTotal = 0
        resetMinute(SystemClock.elapsedRealtime())
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ProcessCameraProvider.getInstance(this).also { f ->
            f.addListener({ cameraProvider = f.get() }, ContextCompat.getMainExecutor(this))
        }
        recentEvents.clear()
        home.visibility = View.GONE
        val delay = Prefs.delayMin(this)
        MonLog.log(this, "=== Monitoring started === camera=${Prefs.facing(this)} sensitivity=${Prefs.margin(this)} delay=${delay}min")
        if (delay > 0) {
            armed = false
            armAt = SystemClock.elapsedRealtime() + delay * 60_000L
            engine.stop()                    // 延迟期间不开麦克风
            btnArmNow.visibility = View.VISIBLE
            ui.removeCallbacks(armRunnable)
            ui.postDelayed(armRunnable, 1000)
        } else {
            armed = true
            btnArmNow.visibility = View.GONE
            engine.start()
        }

        // 5 秒倒计时,留时间放好手机
        countdown = 5
        panel.visibility = View.VISIBLE
        setBrightness(0.4f)
        tvPanelInfo.text = Str.placePhone()
        ui.removeCallbacks(countdownRunnable)
        ui.post(countdownRunnable)
    }

    private fun stopMonitoring() {
        MonLog.log(this, "=== Monitoring stopped === $eventsTotal snore events, $clipsSaved clips saved")
        monitoring = false
        ui.removeCallbacks(hidePanelRunnable)
        ui.removeCallbacks(countdownRunnable)
        ui.removeCallbacks(armRunnable)
        armed = true
        btnArmNow.visibility = View.GONE
        killFillLight()
        waitingLight = false
        if (busy && recording == null) { busy = false; cameraProvider?.unbindAll() }
        recording?.stop()   // Finalize 回调里会保存记录
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setBrightness(-1f)
        wave.visibility = View.GONE; tvDb.visibility = View.GONE; tvStatus.visibility = View.GONE
        panel.visibility = View.GONE
        home.visibility = View.VISIBLE
        activeUi = false
        loadSettings()
        refreshPreview()
        Toast.makeText(this, Str.stoppedToast(), Toast.LENGTH_LONG).show()
    }

    // =====================================================================
    //  屏幕:黑屏省电 / 有鼾声时亮起声轨 / 前置补光
    // =====================================================================

    private fun setBrightness(v: Float) {
        val lp = window.attributes; lp.screenBrightness = v; window.attributes = lp
    }

    private fun enterIdleUi() {
        activeUi = false
        if (fillActive) return
        wave.visibility = View.GONE; tvDb.visibility = View.GONE; tvStatus.visibility = View.GONE
        if (panel.visibility != View.VISIBLE) setBrightness(BRIGHT_IDLE)
    }

    private fun enterActiveUi() {
        activeUi = true
        if (fillActive) return
        wave.visibility = View.VISIBLE; tvDb.visibility = View.VISIBLE; tvStatus.visibility = View.VISIBLE
        if (panel.visibility != View.VISIBLE) setBrightness(BRIGHT_ACTIVE)
    }

    private fun updatePanelTitle() {
        if (!armed) {
            val left = max(0L, armAt - SystemClock.elapsedRealtime()) / 1000
            tvPanelTitle.text = Str.waitingTitle("%d:%02d".format(left / 60, left % 60))
            tvPanelDb.text = "🎵"
            tvPanelInfo.text = Str.waitingInfo()
        } else {
            tvPanelTitle.text = Str.monitoring()
        }
    }

    /** 延迟结束(或点了"立即开始检测"):打开麦克风,正式开始检测 */
    private fun arm() {
        if (!monitoring || armed) return
        armed = true
        ui.removeCallbacks(armRunnable)
        btnArmNow.visibility = View.GONE
        detector.reset()
        recentEvents.clear()
        lastEventAt = 0L
        resetMinute(SystemClock.elapsedRealtime())
        engine.start()
        MonLog.log(this, "Delay finished, detection started")
        if (panel.visibility == View.VISIBLE) updatePanelTitle()
    }

    private fun showPanel(autoHide: Boolean) {
        panel.visibility = View.VISIBLE
        if (countdown <= 0) updatePanelTitle()
        setBrightness(0.3f)
        ui.removeCallbacks(hidePanelRunnable)
        if (autoHide) ui.postDelayed(hidePanelRunnable, 8000)
    }

    private fun hidePanel() {
        panel.visibility = View.GONE
        if (fillActive) applyLight() else setBrightness(if (activeUi) BRIGHT_ACTIVE else BRIGHT_IDLE)
    }

    private fun applyLight() {
        val l = lightLevel
        lightView.setBackgroundColor(Color.rgb((255 * l).toInt(), (244 * l).toInt(), (224 * l).toInt()))
        if (panel.visibility != View.VISIBLE) setBrightness(0.01f + (Prefs.fillMax(this) - 0.01f) * l)
    }

    private fun stepLight() {
        if (!fillActive) return
        if (fadingOut) {
            lightLevel -= 0.05f
            if (lightLevel <= 0f) {
                lightLevel = 0f
                fillActive = false
                fadingOut = false
                lightView.visibility = View.GONE
                enterIdleUi()
                return
            }
        } else {
            val total = Prefs.rampSec(this) * 1000f
            val t = ((SystemClock.elapsedRealtime() - lightStart) / total).coerceIn(0f, 1f)
            lightLevel = t * t          // 平方曲线:开头很缓,后面才明显变亮
            if (waitingLight && t >= 1f) beginRecordingNow()   // 屏幕亮到设定亮度后才开始录像
        }
        applyLight()
        ui.postDelayed(lightRunnable, 100)
    }

    private fun startFillLight() {
        if (Prefs.facing(this) != "front") return
        ui.removeCallbacks(lightRunnable)
        fillActive = true
        fadingOut = false
        lightLevel = 0f
        lightStart = SystemClock.elapsedRealtime()
        wave.visibility = View.GONE; tvDb.visibility = View.GONE; tvStatus.visibility = View.GONE
        lightView.visibility = View.VISIBLE
        applyLight()
        ui.postDelayed(lightRunnable, 100)
    }

    /** 录完后约 2 秒内慢慢熄灭,回到黑屏 */
    private fun stopFillLight() {
        if (!fillActive) return
        fadingOut = true
        ui.removeCallbacks(lightRunnable)
        ui.post(lightRunnable)
    }

    private fun killFillLight() {
        ui.removeCallbacks(lightRunnable)
        fillActive = false
        fadingOut = false
        lightLevel = 0f
        lightView.visibility = View.GONE
    }

    // =====================================================================
    //  每一帧音频
    // =====================================================================

    private fun onFrame(fr: SnoreDetector.Frame) {
        if (monitoring) { if (armed) monitorFrame(fr) } else previewFrame(fr)
    }

    private fun resetMinute(now: Long) {
        minStartAt = now; mSum = 0.0; mN = 0; mMax = -200.0; mMin = 200.0; mCand = 0; mEvents = 0
    }

    private fun monitorFrame(fr: SnoreDetector.Frame) {
        val now = SystemClock.elapsedRealtime()
        wave.push(fr.db, fr.candidate)
        tvDb.text = "%.0f dB".format(fr.db)
        tvDb.setTextColor(if (fr.candidate) Color.parseColor("#FF5252") else Color.WHITE)
        if (panel.visibility == View.VISIBLE) {
            tvPanelDb.text = "%.0f dB".format(fr.db)
            if (countdown <= 0) tvPanelInfo.text = Str.panelInfo(eventsTotal, clipsSaved)
        }

        // 录像期间的 dB 时间序列
        if (videoStartMs > 0 && recording != null) {
            bucketMax = max(bucketMax, fr.db)
            bucketN++
            if (bucketN >= 4) {
                timelineDb.add(bucketMax.toFloat())
                bucketMax = -200.0; bucketN = 0
            }
        }

        // 每分钟统计
        mSum += fr.db; mN++; mMax = max(mMax, fr.db); mMin = min(mMin, fr.db)
        if (fr.candidate) mCand++
        if (now - minStartAt >= 60_000L && mN > 0) {
            MonLog.log(this, "1 min: sound avg %.0f min %.0f max %.0f dB | noise floor %.0f trigger %.0f | snore frames %d events %d | clips %d%s".format(
                mSum / mN, mMin, mMax, fr.noise, fr.threshold, mCand, mEvents, clipsSaved,
                if (mMax - mMin < 0.5) "  ⚠ sound almost constant, microphone may be muted" else ""))
            resetMinute(now)
        }

        fr.event?.let { ev ->
            lastEventAt = now
            eventsTotal++; mEvents++
            onSnore(ev)
        }

        // 录像卡死保护:超过 clip+30 秒还没结束就强制复位,保证后面还能继续录
        if (busy && now - busySince > CLIP_MS + Prefs.rampSec(this) * 1000L + 30_000L) {
            MonLog.log(this, "⚠ Recording did not finish in time, forced reset")
            ui.removeCallbacks(stopClipRunnable)
            try { recording?.stop() } catch (_: Exception) {}
            recording = null
            busy = false
            waitingLight = false
            cameraProvider?.unbindAll()
            stopFillLight()
        }

        val recNow = recording != null || busy
        if (recNow || (lastEventAt != 0L && now - lastEventAt < IDLE_AFTER_MS)) {
            if (!activeUi && (fr.event != null || recNow)) enterActiveUi()
        } else if (activeUi) enterIdleUi()
    }

    private fun onSnore(ev: SnoreDetector.Event) {
        MonLog.log(this, "Snore peak %.1f dB avg %.1f dB duration %d ms".format(ev.peakDb, ev.avgDb, ev.durMs))
        if (recording != null || busy) {
            sessionEvents.add(ev)
            tvStatus.text = Str.recStatus(sessionEvents.size, ev.peakDb)
            return
        }
        recentEvents.add(ev)
        val cutoff = System.currentTimeMillis() - TRIGGER_WINDOW_MS
        recentEvents.removeAll { it.timeMs < cutoff }
        tvStatus.text = Str.snoreDetected(ev.peakDb)
        if (recentEvents.size >= 2) {
            MonLog.log(this, "Recording triggered (${recentEvents.size} snores within ${TRIGGER_WINDOW_MS / 1000} s)")
            startRecording()
        }
    }

    // =====================================================================
    //  1 分钟录像
    // =====================================================================

    private var pendingRecorder: Recorder? = null
    private var pendingFile: File? = null
    private var waitingLight = false

    /** 触发:前置摄像头时先让屏幕渐亮,亮到设定亮度后再开始录像;后置摄像头直接录 */
    private fun startRecording() {
        val provider = cameraProvider
        if (provider == null) { MonLog.log(this, "⚠ Camera not ready, clip skipped"); return }
        if (busy) return
        busy = true
        busySince = SystemClock.elapsedRealtime()
        sessionEvents = ArrayList(recentEvents)
        sessionStart = System.currentTimeMillis()
        videoStartMs = 0L
        timelineDb.clear(); bucketMax = -200.0; bucketN = 0
        val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
        dir.mkdirs()
        val file = File(dir, "snore_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4")
        try {
            val recorder = Recorder.Builder().setQualitySelector(
                QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))
            ).build()
            val vc = VideoCapture.withOutput(recorder)
            provider.unbindAll()
            val front = Prefs.facing(this) == "front"
            val cam = provider.bindToLifecycle(
                this,
                if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA,
                vc
            )
            try {   // 夜间:把曝光补偿调高一些,画面更亮
                val es = cam.cameraInfo.exposureState
                if (es.isExposureCompensationSupported) {
                    cam.cameraControl.setExposureCompensationIndex(es.exposureCompensationRange.upper * 2 / 3)
                }
            } catch (_: Exception) {}
            pendingRecorder = recorder
            pendingFile = file
        } catch (e: Exception) {
            busy = false
            MonLog.log(this, "⚠ Camera failed to start: ${e.message}")
            return
        }
        if (Prefs.facing(this) == "front") {
            waitingLight = true
            MonLog.log(this, "Front light: recording starts after the screen reaches the set brightness (~${Prefs.rampSec(this)} s)")
            startFillLight()
        } else {
            beginRecordingNow()
        }
    }

    /** 真正开始录像(前置时在屏幕渐亮完成后调用) */
    private fun beginRecordingNow() {
        waitingLight = false
        val recorder = pendingRecorder
        val file = pendingFile
        if (recorder == null || file == null) {
            busy = false
            stopFillLight()
            return
        }
        try {
            var pending = recorder.prepareRecording(this, FileOutputOptions.Builder(file).build())
            if (VIDEO_WITH_AUDIO) pending = pending.withAudioEnabled()
            recording = pending.start(ContextCompat.getMainExecutor(this)) { e ->
                if (e is VideoRecordEvent.Start) {
                    videoStartMs = System.currentTimeMillis()
                    timelineDb.clear(); bucketMax = -200.0; bucketN = 0
                } else if (e is VideoRecordEvent.Finalize) onFinalized(file, e)
            }
            tvStatus.text = Str.recording1min()
            ui.postDelayed(stopClipRunnable, CLIP_MS)
            MonLog.log(this, "Recording started")
        } catch (e: Exception) {
            busy = false
            recording = null
            cameraProvider?.unbindAll()
            stopFillLight()
            MonLog.log(this, "⚠ Recording failed to start: ${e.message}")
        }
    }

    private fun onFinalized(file: File, e: VideoRecordEvent.Finalize) {
        ui.removeCallbacks(stopClipRunnable)
        recording = null
        busy = false
        val startedAt = videoStartMs
        videoStartMs = 0L
        stopFillLight()
        cameraProvider?.unbindAll()         // 录完立即关相机省电
        recentEvents.clear()
        if (e.hasError()) MonLog.log(this, "⚠ Recording ended with error code=${e.error} ${e.cause?.message ?: ""}")
        if (file.exists() && file.length() > 0 && sessionEvents.isNotEmpty()) {
            val peaks = sessionEvents.map { it.peakDb }
            if (startedAt > 0) {
                val evs = sessionEvents.map {
                    longArrayOf(it.timeMs - it.durMs - 320 - startedAt, it.durMs, Math.round(it.peakDb * 10))
                }
                EventStore.saveTimeline(file.absolutePath, timelineDb.toFloatArray(), evs)
            }
            EventStore.add(this, Session(sessionStart, file.absolutePath, sessionEvents.size,
                peaks.max(), peaks.average(), ""))
            clipsSaved++
            // 录完马上在后台识别姿势(正脸/侧脸 → 侧睡/仰睡),不用等到点开录像
            val sid = sessionStart
            val vpath = file.absolutePath
            val appCtx = applicationContext
            Thread {
                val face = PostureAnalyzer.analyzeSession(appCtx, sid, vpath)
                MonLog.log(appCtx, "Posture analysis: " + when (face) { "frontal" -> "frontal"; "profile" -> "profile"; else -> "no face" })
            }.start()
            MonLog.log(this, "Saved clip ${file.name} snores ${sessionEvents.size} avg peak %.1f dB size ${file.length() / 1024}KB".format(peaks.average()))
            tvStatus.text = Str.savedStatus(sessionEvents.size, peaks.average())
        } else {
            MonLog.log(this, "⚠ Clip not saved (empty file or no snore events)")
        }
        // 录像用过麦克风:结束后重开一次采集,避免被静音后不恢复
        if (monitoring) engine.restart()
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacks(lightRunnable)
        engine.stop()
        recording?.stop()
        cameraProvider?.unbindAll()
    }
}
