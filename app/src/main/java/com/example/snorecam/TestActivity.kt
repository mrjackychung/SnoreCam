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
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.face.FaceDetector
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 测试页:
 *  ① 实时看摄像头画面,并实时检测有没有录到人脸(正脸/侧脸)、画面够不够亮,可模拟前置屏幕补光
 *  ② 灵敏度向导:测环境噪声 → 模仿打呼 → 推荐灵敏度 → 验证
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

    // ---- 声音 ----
    private val detector = SnoreDetector().apply { minDb = 0.0; marginDb = 3.0 }
    private var engine: AudioEngine? = null
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var page: ScrollView
    private lateinit var tvBack: TextView
    private lateinit var tvTitle: TextView
    private lateinit var tvSub: TextView
    private lateinit var liveCard: LinearLayout
    private lateinit var stepCard: LinearLayout
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

    // ---- 摄像头 / 人脸 ----
    private lateinit var previewView: PreviewView
    private lateinit var tvFaceOverlay: TextView
    private lateinit var tvFaceInfo: TextView
    private lateinit var tvFillInfo: TextView
    private lateinit var btnFill: Button
    private lateinit var segBack: TextView
    private lateinit var segFront: TextView
    private var cameraProvider: ProcessCameraProvider? = null
    private val camExec = Executors.newSingleThreadExecutor()
    private var faceDetector: FaceDetector? = null
    private var lastAnalyze = 0L

    // ---- 补光模拟 ----
    private var fillOn = false
    private var fillStart = 0L
    private val fillRunnable = object : Runnable {
        override fun run() {
            if (!fillOn) return
            val total = Prefs.rampSec(this@TestActivity) * 1000f
            val t = ((SystemClock.elapsedRealtime() - fillStart) / total).coerceIn(0f, 1f)
            val l = t * t
            page.setBackgroundColor(Color.rgb((255 * l).toInt(), (244 * l).toInt(), (224 * l).toInt()))
            val b = 0.01f + (Prefs.fillMax(this@TestActivity) - 0.01f) * l
            val lp = window.attributes; lp.screenBrightness = b; window.attributes = lp
            tvFillInfo.text = Str.fillInfo((l * 100).toInt(), (b * 100).toInt(), (Prefs.fillMax(this@TestActivity) * 100).toInt())
            ui.postDelayed(this, 100)
        }
    }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        startEngine()
        startCamera()
    }

    private fun hasPerm(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    private fun hasMic() = hasPerm(Manifest.permission.RECORD_AUDIO)
    private fun hasCam() = hasPerm(Manifest.permission.CAMERA)

    private val timer = object : Runnable {
        override fun run() {
            val dur = if (state == ST_QUIET) 6000L else 12000L
            val el = SystemClock.elapsedRealtime() - stateStart
            bar.progress = ((el * 100) / dur).toInt().coerceIn(0, 100)
            val left = ((dur - el) / 1000 + 1).coerceAtLeast(0)
            tvBody.text = if (state == ST_QUIET) Str.keepQuiet(left) else Str.mimicProgress(left, events.size)
            if (el >= dur) {
                if (state == ST_QUIET) finishQuiet() else finishSnore()
            } else ui.postDelayed(this, 100)
        }
    }

    // =====================================================================
    //  界面
    // =====================================================================

    private fun cardBox(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Theme.rounded(Theme.CARD, dpf(16f))
        setPadding(dp(16), dp(14), dp(16), dp(14))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Str.init(this)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(28), dp(18), dp(24))
        }
        page = ScrollView(this).apply { setBackgroundColor(Theme.BG); addView(col) }

        tvBack = TextView(this).apply {
            text = Str.back(); textSize = 16f; setTextColor(Theme.ACCENT)
            setPadding(0, dp(4), dp(14), dp(10)); setOnClickListener { finish() }
        }
        col.addView(tvBack)
        tvTitle = TextView(this).apply {
            text = Str.testTitle(); textSize = 28f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        }
        col.addView(tvTitle)
        tvSub = TextView(this).apply {
            text = Str.testSub()
            textSize = 14f; setTextColor(Theme.SUB); setPadding(0, dp(4), 0, dp(12))
        }
        col.addView(tvSub)

        // ---------- 实时声音 ----------
        liveCard = cardBox()
        liveCard.addView(TextView(this).apply { text = Str.liveSound(); textSize = 13f; setTextColor(Theme.SUB) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        tvDb = TextView(this).apply {
            text = "--"; textSize = 40f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        }
        row.addView(tvDb)
        row.addView(TextView(this).apply {
            text = " dB"; textSize = 16f; setTextColor(Theme.SUB); setPadding(dp(4), 0, 0, dp(8))
        })
        liveCard.addView(row)
        meter = MeterView(this)
        liveCard.addView(meter, LinearLayout.LayoutParams(MATCH, dp(40)))
        col.addView(liveCard)

        // ---------- 实时画面 + 人脸 ----------
        val camCard = cardBox()
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply { text = Str.camCardTitle(); textSize = 14f; setTextColor(Color.WHITE) },
            LinearLayout.LayoutParams(0, WRAP, 1f))
        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Theme.rounded(Theme.BG, dpf(10f))
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        segBack = TextView(this).apply {
            text = Str.segBack(); textSize = 13f; gravity = Gravity.CENTER
            setOnClickListener { Prefs.setFacing(this@TestActivity, "back"); updateSeg(); bindCamera() }
        }
        segFront = TextView(this).apply {
            text = Str.segFront(); textSize = 13f; gravity = Gravity.CENTER
            setOnClickListener { Prefs.setFacing(this@TestActivity, "front"); updateSeg(); bindCamera() }
        }
        seg.addView(segBack, LinearLayout.LayoutParams(dp(56), dp(32)))
        seg.addView(segFront, LinearLayout.LayoutParams(dp(56), dp(32)))
        head.addView(seg)
        camCard.addView(head)

        val box = FrameLayout(this).apply {
            background = Theme.rounded(Color.BLACK, dpf(12f))
            clipToOutline = true
        }
        previewView = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        box.addView(previewView, FrameLayout.LayoutParams(MATCH, MATCH))
        tvFaceOverlay = TextView(this).apply {
            text = Str.waitingCam(); textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#99000000")); setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        box.addView(tvFaceOverlay, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        camCard.addView(box, LinearLayout.LayoutParams(MATCH, dp(300)).apply { topMargin = dp(10) })

        tvFaceInfo = TextView(this).apply {
            textSize = 13f; setTextColor(Theme.SUB); setLineSpacing(0f, 1.2f); setPadding(0, dp(8), 0, 0)
        }
        camCard.addView(tvFaceInfo)
        tvFillInfo = TextView(this).apply {
            textSize = 13f; setTextColor(Theme.ACCENT); setPadding(0, dp(4), 0, 0); visibility = View.GONE
        }
        camCard.addView(tvFillInfo)
        btnFill = Button(this).apply {
            text = Str.simLight(); isAllCaps = false; textSize = 14f; setTextColor(Color.WHITE)
            background = Theme.rounded(Color.parseColor("#2A3F58"), dpf(14f))
            setOnClickListener { if (fillOn) stopFill() else startFill() }
        }
        camCard.addView(btnFill, LinearLayout.LayoutParams(MATCH, dp(46)).apply { topMargin = dp(10) })
        camCard.addView(TextView(this).apply {
            text = Str.simLightHint()
            textSize = 12f; setTextColor(Theme.SUB); setPadding(0, dp(6), 0, 0)
        })
        col.addView(camCard, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })

        // ---------- 灵敏度向导 ----------
        stepCard = cardBox().apply { setPadding(dp(16), dp(16), dp(16), dp(16)) }
        tvStep = TextView(this).apply {
            textSize = 18f; setTextColor(Theme.ACCENT); typeface = Typeface.DEFAULT_BOLD
        }
        stepCard.addView(tvStep)
        tvBody = TextView(this).apply {
            textSize = 15f; setTextColor(Color.WHITE); setLineSpacing(0f, 1.25f); setPadding(0, dp(8), 0, dp(8))
        }
        stepCard.addView(tvBody)
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE }
        stepCard.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))
        tvVerify = TextView(this).apply {
            textSize = 20f; setTextColor(Color.parseColor("#2FBFA5")); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(6)); visibility = View.GONE
        }
        stepCard.addView(tvVerify)
        btnApply = Button(this).apply {
            isAllCaps = false; textSize = 16f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#1A1100")); background = Theme.rounded(Theme.ACCENT, dpf(14f))
            visibility = View.GONE
        }
        stepCard.addView(btnApply, LinearLayout.LayoutParams(MATCH, dp(50)).apply { topMargin = dp(8) })
        btnMain = Button(this).apply {
            isAllCaps = false; textSize = 16f; setTextColor(Color.WHITE)
            background = Theme.rounded(Color.parseColor("#2A3F58"), dpf(14f))
        }
        stepCard.addView(btnMain, LinearLayout.LayoutParams(MATCH, dp(50)).apply { topMargin = dp(10) })
        col.addView(stepCard, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(14) })

        setContentView(page)
        updateSeg()
        showQuietIntro()

        if (!hasMic() || !hasCam()) {
            permLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        } else startCamera()
    }

    private fun updateSeg() {
        val front = Prefs.facing(this) == "front"
        fun style(v: TextView, sel: Boolean) {
            v.background = if (sel) Theme.rounded(Theme.ACCENT, dpf(8f)) else null
            v.setTextColor(if (sel) Color.parseColor("#1A1100") else Theme.SUB)
            v.typeface = if (sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        style(segBack, !front)
        style(segFront, front)
    }

    // =====================================================================
    //  摄像头 + 人脸
    // =====================================================================

    private fun startCamera() {
        if (!hasCam()) { tvFaceOverlay.text = Str.needCamPerm(); return }
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            cameraProvider = f.get()
            bindCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return
        val front = Prefs.facing(this) == "front"
        val preview = Preview.Builder().build()
        preview.setSurfaceProvider(previewView.surfaceProvider)
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(camExec) { proxy -> analyzeFrame(proxy) }
        try {
            provider.unbindAll()
            val cam = provider.bindToLifecycle(
                this,
                if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA,
                preview, analysis
            )
            try {   // 和真正录像时一样,把曝光补偿调高一些
                val es = cam.cameraInfo.exposureState
                if (es.isExposureCompensationSupported) {
                    cam.cameraControl.setExposureCompensationIndex(es.exposureCompensationRange.upper * 2 / 3)
                }
            } catch (_: Exception) {}
            tvFaceOverlay.text = Str.detectingFace()
        } catch (e: Exception) {
            tvFaceOverlay.text = Str.camStartFailed(e.message)
        }
    }

    private fun analyzeFrame(proxy: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastAnalyze < 700) return
            lastAnalyze = now
            val det = faceDetector ?: PostureAnalyzer.newDetector().also { faceDetector = it }
            val bmp = proxy.toBitmap()
            val rot = proxy.imageInfo.rotationDegrees
            val info = PostureAnalyzer.brightenInfo(bmp)
            val hit = PostureAnalyzer.detectFace(det, info.first, rot)
            val luma = info.second
            runOnUiThread { if (!isFinishing) showFace(hit, luma) }
        } catch (e: Throwable) {
            // 忽略单帧失败
        } finally {
            proxy.close()
        }
    }

    private fun showFace(hit: PostureAnalyzer.FaceHit?, luma: Double) {
        val lumaTxt = when {
            luma < 25 -> Str.lumaVeryDark()
            luma < 60 -> Str.lumaDark()
            else -> Str.lumaOk()
        }
        if (hit == null) {
            tvFaceOverlay.text = Str.noFace()
            tvFaceOverlay.setBackgroundColor(Color.parseColor("#B3B3261E"))
            tvFaceInfo.text = Str.brightnessLine(luma.roundToInt(), lumaTxt) + "\n" +
                if (luma < 60) Str.tooDarkAdvice()
                else Str.noFaceAdvice()
        } else {
            val frontal = abs(hit.yaw) < 30f
            tvFaceOverlay.text = Str.faceFound(frontal, hit.yaw.roundToInt())
            tvFaceOverlay.setBackgroundColor(Color.parseColor("#B31F7A4C"))
            val cam = Prefs.camMode(this)
            val label = Posture.labelFor(if (frontal) "frontal" else "profile", cam)
            tvFaceInfo.text = Str.brightnessLine(luma.roundToInt(), lumaTxt) + "\n" +
                Str.posePrediction(cam == "footend", Theme.labelName(label))
        }
    }

    // =====================================================================
    //  补光模拟
    // =====================================================================

    private fun startFill() {
        fillOn = true
        fillStart = SystemClock.elapsedRealtime()
        liveCard.visibility = View.GONE
        stepCard.visibility = View.GONE
        tvSub.visibility = View.GONE
        tvBack.setTextColor(Color.parseColor("#5A3A00"))
        tvTitle.setTextColor(Color.parseColor("#3A2A10"))
        tvFillInfo.visibility = View.VISIBLE
        btnFill.text = Str.stopSim()
        page.scrollTo(0, 0)
        ui.removeCallbacks(fillRunnable)
        ui.post(fillRunnable)
    }

    private fun stopFill() {
        fillOn = false
        ui.removeCallbacks(fillRunnable)
        page.setBackgroundColor(Theme.BG)
        val lp = window.attributes; lp.screenBrightness = -1f; window.attributes = lp
        liveCard.visibility = View.VISIBLE
        stepCard.visibility = View.VISIBLE
        tvSub.visibility = View.VISIBLE
        tvBack.setTextColor(Theme.ACCENT)
        tvTitle.setTextColor(Color.WHITE)
        tvFillInfo.visibility = View.GONE
        btnFill.text = Str.simLight()
    }

    // =====================================================================
    //  声音引擎
    // =====================================================================

    private fun startEngine() {
        if (!hasMic()) return
        if (engine == null) engine = AudioEngine(this, detector, { onFrame(it) }, {})
        engine?.start()
    }

    override fun onResume() { super.onResume(); startEngine() }

    override fun onPause() {
        super.onPause()
        engine?.stop()
        ui.removeCallbacks(timer)
        if (fillOn) stopFill()
        if (state == ST_QUIET || state == ST_SNORE) showQuietIntro()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { cameraProvider?.unbindAll() } catch (_: Exception) {}
        camExec.shutdown()
        try { faceDetector?.close() } catch (_: Exception) {}
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
                tvVerify.text = Str.verifyHit(it.peakDb, verifyCount)
            }
        }
    }

    // =====================================================================
    //  灵敏度向导步骤
    // =====================================================================

    private fun showQuietIntro() {
        state = ST_QUIET_INTRO
        tvStep.text = Str.step1Title()
        tvBody.text = Str.step1Body()
        bar.visibility = View.GONE; tvVerify.visibility = View.GONE; btnApply.visibility = View.GONE
        btnMain.visibility = View.VISIBLE
        btnMain.text = Str.startMeasure()
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
        tvStep.text = Str.step2Title()
        tvBody.text = Str.step2Body(noiseN.roundToInt())
        btnMain.visibility = View.VISIBLE
        btnMain.text = Str.startMimic()
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
        btnMain.text = Str.retest()
        btnMain.setOnClickListener { showQuietIntro() }
        tvStep.text = Str.resultTitle()

        val s: Double? = when {
            events.size >= 2 -> percentile(events.map { it.peakDb }, 0.25)
            candDb.size >= 6 -> percentile(candDb, 0.75)
            else -> null
        }
        if (s == null) {
            tvBody.text = Str.noSnoreHeard()
            btnApply.visibility = View.GONE
            return
        }
        val gap = s - noiseN
        recMargin = (gap * 0.5).roundToInt().coerceIn(4, 20)
        tvBody.text = Str.testResult(noiseN.roundToInt(), s.roundToInt(), gap.roundToInt(), events.size, recMargin, noiseN > 55, gap < 8)

        btnApply.visibility = View.VISIBLE
        btnApply.text = Str.applyRec(recMargin)
        btnApply.setOnClickListener { applyRecommended() }
    }

    private fun applyRecommended() {
        Prefs.setMargin(this, recMargin)
        detector.marginDb = recMargin.toDouble()
        detector.minDb = 35.0
        state = ST_LIVE
        verifyCount = 0
        tvStep.text = Str.savedVerify()
        tvBody.text = Str.verifyBody(recMargin)
        tvVerify.text = Str.waitSnore()
        tvVerify.visibility = View.VISIBLE
        btnApply.text = Str.doneBack()
        btnApply.setOnClickListener { finish() }
        btnMain.visibility = View.GONE
    }

    private fun percentile(list: List<Double>, p: Double): Double {
        if (list.isEmpty()) return 0.0
        val sorted = list.sorted()
        return sorted[((sorted.size - 1) * p).toInt()]
    }
}
