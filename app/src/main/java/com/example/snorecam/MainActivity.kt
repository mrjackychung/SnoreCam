package com.example.snorecam

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
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

class MainActivity : AppCompatActivity() {

    companion object {
        const val CLIP_MS = 60_000L          // 每次录像时长
        const val TRIGGER_WINDOW_MS = 30_000L // 30 秒内出现 >=2 次鼾声事件才触发
        const val IDLE_AFTER_MS = 30_000L     // 最后一次鼾声后 30 秒回到黑屏
        const val VIDEO_WITH_AUDIO = true     // 若发现录像没声音,改成 false(与分贝检测抢麦克风时)
        const val BRIGHT_IDLE = 0.01f
        const val BRIGHT_ACTIVE = 0.12f
    }

    private val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
    private val WRAP = FrameLayout.LayoutParams.WRAP_CONTENT

    private lateinit var wave: WaveformView
    private lateinit var tvDb: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvSens: TextView
    private lateinit var controls: LinearLayout
    private lateinit var btnStart: Button

    private val ui = Handler(Looper.getMainLooper())
    private val detector = SnoreDetector()

    @Volatile private var running = false
    private var monitoring = false
    private var activeUi = false
    private var busy = false
    private var lastEventAt = 0L

    private var cameraProvider: ProcessCameraProvider? = null
    private var recording: Recording? = null
    private var sessionStart = 0L
    private var sessionEvents = ArrayList<SnoreDetector.Event>()
    private val recentEvents = ArrayList<SnoreDetector.Event>()

    // 录像期间的 dB 时间序列(每 4 帧 ≈ 256ms 一个点),从视频真正开始录时算起
    private var videoStartMs = 0L
    private val timelineDb = ArrayList<Float>()
    private var bucketMax = -200.0
    private var bucketN = 0

    private val stopClipRunnable = Runnable { recording?.stop() }
    private val hideControlsRunnable = Runnable { if (monitoring) hideControls() }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.all { it }) startMonitoring()
        else Toast.makeText(this, "需要相机和麦克风权限", Toast.LENGTH_LONG).show()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

        controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#F0000000"))
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }
        controls.addView(TextView(this).apply {
            text = "SnoreCam 鼾声录像"; textSize = 24f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        })
        controls.addView(TextView(this).apply {
            text = "\n睡前:接上充电器 → 手机立在床边,后摄对准自己 → 点开始。\n监测中点一下屏幕可唤出控制。\n"
            setTextColor(Color.LTGRAY); gravity = Gravity.CENTER
        })
        tvSens = TextView(this).apply { setTextColor(Color.WHITE); gravity = Gravity.CENTER }
        controls.addView(tvSens)
        val seek = SeekBar(this).apply {
            max = 16; progress = 6
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { applySens(p) }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        controls.addView(seek)
        applySens(6)
        btnStart = Button(this).apply { text = "开始监测"; setOnClickListener { onStartStopClicked() } }
        controls.addView(btnStart)
        controls.addView(Button(this).apply {
            text = "查看记录 / 标记姿势"
            setOnClickListener { startActivity(Intent(this@MainActivity, ReviewActivity::class.java)) }
        })
        root.addView(controls, FrameLayout.LayoutParams(MATCH, MATCH))
        controls.setOnClickListener { if (monitoring) hideControls() }
        root.setOnClickListener { if (monitoring) showControls() }

        setContentView(root)
    }

    private fun applySens(p: Int) {
        val margin = 4 + p
        detector.marginDb = margin.toDouble()
        tvSens.text = "\n灵敏度:高于环境噪声 $margin dB 才算鼾声(越小越灵敏)"
    }

    // ---------- 开始 / 停止 ----------

    private fun onStartStopClicked() {
        if (monitoring) { stopMonitoring(); return }
        val need = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (need.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) startMonitoring()
        else permLauncher.launch(need)
    }

    private fun startMonitoring() {
        monitoring = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ProcessCameraProvider.getInstance(this).also { f ->
            f.addListener({ cameraProvider = f.get() }, ContextCompat.getMainExecutor(this))
        }
        recentEvents.clear()
        startAudio()
        btnStart.text = "停止监测"
        enterIdleUi()
        hideControls()
        Toast.makeText(this, "开始监测,屏幕即将变黑", Toast.LENGTH_SHORT).show()
    }

    private fun stopMonitoring() {
        monitoring = false
        running = false
        ui.removeCallbacks(hideControlsRunnable)
        recording?.stop()   // Finalize 回调里会保存记录
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setBrightness(-1f)
        wave.visibility = View.GONE; tvDb.visibility = View.GONE; tvStatus.visibility = View.GONE
        controls.visibility = View.VISIBLE
        btnStart.text = "开始监测"
        activeUi = false
    }

    // ---------- 屏幕状态:黑屏省电 / 有鼾声时亮起声轨 ----------

    private fun setBrightness(v: Float) {
        val lp = window.attributes; lp.screenBrightness = v; window.attributes = lp
    }

    private fun enterIdleUi() {
        activeUi = false
        wave.visibility = View.GONE; tvDb.visibility = View.GONE; tvStatus.visibility = View.GONE
        if (controls.visibility != View.VISIBLE) setBrightness(BRIGHT_IDLE)
    }

    private fun enterActiveUi() {
        activeUi = true
        wave.visibility = View.VISIBLE; tvDb.visibility = View.VISIBLE; tvStatus.visibility = View.VISIBLE
        if (controls.visibility != View.VISIBLE) setBrightness(BRIGHT_ACTIVE)
    }

    private fun showControls() {
        controls.visibility = View.VISIBLE
        setBrightness(0.3f)
        ui.removeCallbacks(hideControlsRunnable)
        ui.postDelayed(hideControlsRunnable, 8000)
    }

    private fun hideControls() {
        controls.visibility = View.GONE
        setBrightness(if (activeUi) BRIGHT_ACTIVE else BRIGHT_IDLE)
    }

    // ---------- 音频采集 + 检测 ----------

    private fun startAudio() {
        val am = getSystemService(AudioManager::class.java)
        val src = if (am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true")
            MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.MIC
        val minBuf = AudioRecord.getMinBufferSize(SnoreDetector.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(src, SnoreDetector.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, max(minBuf, SnoreDetector.FRAME * 8))
        } catch (e: SecurityException) { return }
        rec.startRecording()
        running = true
        Thread {
            val buf = ShortArray(SnoreDetector.FRAME)
            while (running) {
                var n = 0
                while (n < buf.size && running) {
                    val r = rec.read(buf, n, buf.size - n)
                    if (r <= 0) { Thread.sleep(20); break }
                    n += r
                }
                if (n < buf.size) continue
                val fr = detector.process(buf)
                ui.post { onFrame(fr) }
            }
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
        }.start()
    }

    private fun onFrame(fr: SnoreDetector.Frame) {
        if (!monitoring) return
        val now = SystemClock.elapsedRealtime()
        wave.push(fr.db, fr.candidate)
        if (videoStartMs > 0 && recording != null) {
            bucketMax = max(bucketMax, fr.db)
            bucketN++
            if (bucketN >= 4) {
                timelineDb.add(bucketMax.toFloat())
                bucketMax = -200.0; bucketN = 0
            }
        }
        tvDb.text = "%.0f dB".format(fr.db)
        tvDb.setTextColor(if (fr.candidate) Color.parseColor("#FF5252") else Color.WHITE)

        fr.event?.let { ev ->
            lastEventAt = now
            onSnore(ev)
        }
        val recording = this.recording != null || busy
        if (recording || now - lastEventAt < IDLE_AFTER_MS && lastEventAt != 0L) {
            if (!activeUi && (fr.event != null || recording)) enterActiveUi()
        } else if (activeUi) enterIdleUi()
    }

    private fun onSnore(ev: SnoreDetector.Event) {
        if (recording != null || busy) {
            if (recording != null) sessionEvents.add(ev)
            tvStatus.text = "● 录像中  鼾声 ${sessionEvents.size} 次 · 本次峰值 %.1f dB".format(ev.peakDb)
            return
        }
        recentEvents.add(ev)
        val cutoff = System.currentTimeMillis() - TRIGGER_WINDOW_MS
        recentEvents.removeAll { it.timeMs < cutoff }
        tvStatus.text = "检测到鼾声 %.1f dB".format(ev.peakDb)
        if (recentEvents.size >= 2) startRecording()
    }

    // ---------- 1 分钟录像 ----------

    private fun startRecording() {
        val provider = cameraProvider ?: return
        if (busy) return
        busy = true
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
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, vc)
            var pending = recorder.prepareRecording(this, FileOutputOptions.Builder(file).build())
            if (VIDEO_WITH_AUDIO) pending = pending.withAudioEnabled()
            recording = pending.start(ContextCompat.getMainExecutor(this)) { e ->
                if (e is VideoRecordEvent.Start) {
                    videoStartMs = System.currentTimeMillis()
                    timelineDb.clear(); bucketMax = -200.0; bucketN = 0
                } else if (e is VideoRecordEvent.Finalize) onFinalized(file)
            }
            tvStatus.text = "● 录像中(1 分钟)"
            ui.postDelayed(stopClipRunnable, CLIP_MS)
        } catch (e: Exception) {
            busy = false
            recording = null
            tvStatus.text = "录像失败:${e.message}"
        }
    }

    private fun onFinalized(file: File) {
        ui.removeCallbacks(stopClipRunnable)
        recording = null
        busy = false
        val startedAt = videoStartMs
        videoStartMs = 0L
        cameraProvider?.unbindAll()         // 录完立即关相机省电
        recentEvents.clear()
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
            tvStatus.text = "已保存:鼾声 ${sessionEvents.size} 次 · 平均峰值 %.1f dB".format(peaks.average())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        recording?.stop()
        cameraProvider?.unbindAll()
    }
}
