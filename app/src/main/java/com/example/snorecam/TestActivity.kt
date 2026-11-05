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
            tvFillInfo.text = "补光 ${(l * 100).toInt()}% · 屏幕亮度 ${(b * 100).toInt()}%(上限 ${(Prefs.fillMax(this@TestActivity) * 100).toInt()}%)"
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
            tvBody.text = if (state == ST_QUIET) "保持安静…还剩 $left 秒"
            else "请模仿打呼…还剩 $left 秒\n已识别到 ${events.size} 次类似鼾声"
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

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(28), dp(18), dp(24))
        }
        page = ScrollView(this).apply { setBackgroundColor(Theme.BG); addView(col) }

        tvBack = TextView(this).apply {
            text = "‹ 返回"; textSize = 16f; setTextColor(Theme.ACCENT)
            setPadding(0, dp(4), dp(14), dp(10)); setOnClickListener { finish() }
        }
        col.addView(tvBack)
        tvTitle = TextView(this).apply {
            text = "测试"; textSize = 28f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        }
        col.addView(tvTitle)
        tvSub = TextView(this).apply {
            text = "看看摄像头能不能拍到你的脸,再测声音灵敏度。"
            textSize = 14f; setTextColor(Theme.SUB); setPadding(0, dp(4), 0, dp(12))
        }
        col.addView(tvSub)

        // ---------- 实时声音 ----------
        liveCard = cardBox()
        liveCard.addView(TextView(this).apply { text = "实时声音"; textSize = 13f; setTextColor(Theme.SUB) })
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
        head.addView(TextView(this).apply { text = "📷 实时画面 · 人脸检测"; textSize = 14f; setTextColor(Color.WHITE) },
            LinearLayout.LayoutParams(0, WRAP, 1f))
        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Theme.rounded(Theme.BG, dpf(10f))
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        segBack = TextView(this).apply {
            text = "后置"; textSize = 13f; gravity = Gravity.CENTER
            setOnClickListener { Prefs.setFacing(this@TestActivity, "back"); updateSeg(); bindCamera() }
        }
        segFront = TextView(this).apply {
            text = "前置"; textSize = 13f; gravity = Gravity.CENTER
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
            text = "等待相机…"; textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
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
            text = "☀ 模拟屏幕补光(从黑渐亮)"; isAllCaps = false; textSize = 14f; setTextColor(Color.WHITE)
            background = Theme.rounded(Color.parseColor("#2A3F58"), dpf(14f))
            setOnClickListener { if (fillOn) stopFill() else startFill() }
        }
        camCard.addView(btnFill, LinearLayout.LayoutParams(MATCH, dp(46)).apply { topMargin = dp(10) })
        camCard.addView(TextView(this).apply {
            text = "前置摄像头时,屏幕就是补光灯。点上面按钮会按你设置的亮度上限和渐亮时长模拟一遍,看看脸什么时候能被检测到。"
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
        if (!hasCam()) { tvFaceOverlay.text = "需要相机权限"; return }
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
            tvFaceOverlay.text = "正在检测人脸…"
        } catch (e: Exception) {
            tvFaceOverlay.text = "相机启动失败:${e.message}"
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
            luma < 25 -> "画面很暗"
            luma < 60 -> "画面偏暗"
            else -> "亮度足够"
        }
        if (hit == null) {
            tvFaceOverlay.text = "✗ 没检测到人脸"
            tvFaceOverlay.setBackgroundColor(Color.parseColor("#B3B3261E"))
            tvFaceInfo.text = "画面亮度 ${luma.roundToInt()}/255($lumaTxt)\n" +
                if (luma < 60) "太暗了:点下面「模拟屏幕补光」,或把补光亮度上限调高;也可以把手机挪近一点。"
                else "亮度没问题但没找到脸:调整手机角度,让脸和上半身完整出现在画面里。"
        } else {
            val frontal = abs(hit.yaw) < 30f
            tvFaceOverlay.text = "✓ 检测到人脸 · ${if (frontal) "正脸" else "侧脸"}(转角 ${hit.yaw.roundToInt()}°)"
            tvFaceOverlay.setBackgroundColor(Color.parseColor("#B31F7A4C"))
            val cam = Prefs.camMode(this)
            val label = Posture.labelFor(if (frontal) "frontal" else "profile", cam)
            tvFaceInfo.text = "画面亮度 ${luma.roundToInt()}/255($lumaTxt)\n" +
                "按当前「镜头位置:${if (cam == "footend") "床尾" else "床边"}」设置,这个姿势会被自动判为:${Theme.labelName(label)}"
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
        btnFill.text = "■ 结束补光测试"
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
        btnFill.text = "☀ 模拟屏幕补光(从黑渐亮)"
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
                tvVerify.text = "✓ 检测到鼾声 %.0f dB(第 %d 次)".format(it.peakDb, verifyCount)
            }
        }
    }

    // =====================================================================
    //  灵敏度向导步骤
    // =====================================================================

    private fun showQuietIntro() {
        state = ST_QUIET_INTRO
        tvStep.text = "灵敏度测试 · 第 1 步:测量环境噪声"
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
        tvStep.text = "灵敏度测试 · 第 2 步:模仿打呼"
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
        tvStep.text = "灵敏度测试结果"

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
