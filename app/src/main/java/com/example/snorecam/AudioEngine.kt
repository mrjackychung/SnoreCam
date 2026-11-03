package com.example.snorecam

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import kotlin.math.max

/**
 * 麦克风采集线程。和以前不同的是它会"自愈":
 * 读取出错、或连续约 5 秒全是 0(被系统/录像抢占而静音)时,自动重开麦克风。
 * 回调都在主线程。
 */
class AudioEngine(
    private val ctx: Context,
    private val detector: SnoreDetector,
    private val onFrame: (SnoreDetector.Frame) -> Unit,
    private val onNote: (String) -> Unit
) {
    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var gen = 0
    @Volatile var isRunning = false
        private set

    fun start() {
        if (isRunning) return
        isRunning = true
        gen++
        val my = gen
        Thread { loop(my) }.start()
    }

    fun stop() {
        isRunning = false
        gen++
    }

    /** 强制重开麦克风(例如每段录像结束后) */
    fun restart() {
        if (!isRunning) return
        gen++
        val my = gen
        Thread { loop(my) }.start()
    }

    private fun note(msg: String) { ui.post { onNote(msg) } }

    private fun open(): AudioRecord? {
        return try {
            val am = ctx.getSystemService(AudioManager::class.java)
            val src = if (am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true")
                MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.MIC
            val minBuf = AudioRecord.getMinBufferSize(SnoreDetector.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val r = AudioRecord(src, SnoreDetector.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, max(minBuf, SnoreDetector.FRAME * 8))
            if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); null }
            else { r.startRecording(); r }
        } catch (e: Exception) { null }
    }

    private fun loop(my: Int) {
        val buf = ShortArray(SnoreDetector.FRAME)
        var rec: AudioRecord? = null
        var zeroFrames = 0
        while (gen == my) {
            if (rec == null) {
                rec = open()
                if (rec == null) {
                    note("麦克风打开失败,2 秒后重试")
                    try { Thread.sleep(2000) } catch (_: InterruptedException) {}
                    continue
                }
            }
            val cur: AudioRecord = rec ?: continue
            var n = 0
            var err = false
            while (n < buf.size && gen == my) {
                val r = cur.read(buf, n, buf.size - n)
                if (r <= 0) { err = true; break }
                n += r
            }
            if (gen != my) break
            if (err) {
                note("麦克风读取出错,已重新打开")
                try { cur.stop() } catch (_: Exception) {}
                cur.release()
                rec = null
                try { Thread.sleep(500) } catch (_: InterruptedException) {}
                continue
            }
            var allZero = true
            for (v in buf) { if (v.toInt() != 0) { allZero = false; break } }
            zeroFrames = if (allZero) zeroFrames + 1 else 0
            if (zeroFrames >= 80) {
                note("麦克风持续静音(可能被系统或录像占用),已重新打开")
                try { cur.stop() } catch (_: Exception) {}
                cur.release()
                rec = null
                zeroFrames = 0
                continue
            }
            val fr = detector.process(buf)
            ui.post { if (gen == my) onFrame(fr) }
        }
        rec?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
    }
}
