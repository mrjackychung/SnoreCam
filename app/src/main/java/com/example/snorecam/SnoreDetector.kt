package com.example.snorecam

import kotlin.math.*

/**
 * 鼾声检测(纯规则,无模型):
 * 1. 每帧(1024 采样 ≈ 64ms @16kHz)算 dB
 * 2. 帧能量高于「自适应噪声底 + margin」且低频(80~1000Hz)能量占比高 → 候选帧
 * 3. 连续候选帧 0.3~5 秒(允许 <0.32 秒的间隙)→ 一次鼾声事件
 */
class SnoreDetector {
    companion object {
        const val SAMPLE_RATE = 16000
        const val FRAME = 1024

        /**
         * dBFS → 近似 dB(SPL) 的偏移。手机麦克风没校准,数值只能做"同一台手机、同一位置"的相对比较。
         * 如果有分贝计 App,可对着同一声源对比后改这个值。
         */
        const val DB_OFFSET = 90.0
    }

    data class Event(val timeMs: Long, val peakDb: Double, val avgDb: Double, val durMs: Long)
    data class Frame(val db: Double, val candidate: Boolean, val event: Event?)

    @Volatile var marginDb = 10.0   // 高于噪声底多少 dB 才算候选
    @Volatile var minDb = 40.0      // 绝对下限
    private val lowRatioMin = 0.55

    private var noise = 30.0
    private var run = 0
    private var gap = 0
    private var peak = -200.0
    private var sumDb = 0.0

    private val re = DoubleArray(FRAME)
    private val im = DoubleArray(FRAME)
    private val win = DoubleArray(FRAME) { 0.5 - 0.5 * cos(2 * PI * it / (FRAME - 1)) }

    fun process(buf: ShortArray): Frame {
        var sum = 0.0
        for (i in 0 until FRAME) {
            val s = buf[i].toDouble()
            sum += s * s
            re[i] = s * win[i]
            im[i] = 0.0
        }
        val rms = sqrt(sum / FRAME).coerceAtLeast(1.0)
        val db = 20 * log10(rms / 32768.0) + DB_OFFSET

        fft()
        var low = 0.0
        var tot = 0.0
        for (k in 5 until 256) {            // 78Hz ~ 4kHz
            val e = re[k] * re[k] + im[k] * im[k]
            tot += e
            if (k <= 64) low += e           // 78Hz ~ 1kHz
        }
        val ratio = if (tot > 0) low / tot else 0.0

        val threshold = max(minDb, noise + marginDb)
        val cand = db > threshold && ratio > lowRatioMin
        if (!cand) {
            noise = if (db < noise) 0.9 * noise + 0.1 * db else noise + 0.0002 * (db - noise)
        }

        var event: Event? = null
        if (cand) {
            run++; gap = 0; peak = max(peak, db); sumDb += db
        } else if (run > 0) {
            gap++
            if (gap > 5) {
                if (run in 5..80) {
                    event = Event(System.currentTimeMillis(), peak, sumDb / run, run * 64L)
                }
                run = 0; gap = 0; peak = -200.0; sumDb = 0.0
            }
        }
        return Frame(db, cand, event)
    }

    private fun fft() {
        val n = FRAME
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k; val b = i + k + len / 2
                    val vr = re[b] * cr - im[b] * ci
                    val vi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - vr; im[b] = im[a] - vi
                    re[a] += vr; im[a] += vi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
