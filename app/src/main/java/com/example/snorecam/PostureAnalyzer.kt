package com.example.snorecam

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * 从录像里抽 6 帧,先提亮,再用 ML Kit(离线)找人脸:
 * 正脸(转角 <30°)→ "frontal",侧脸 → "profile",都没找到 → "none"。
 * 要在后台线程调用。
 */
object PostureAnalyzer {

    fun analyze(path: String): String {
        val retr = MediaMetadataRetriever()
        val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setMinFaceSize(0.05f)
                .build()
        )
        var frontal = 0
        var profile = 0
        try {
            retr.setDataSource(path)
            val durMs = retr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (durMs > 0) {
                val fracs = doubleArrayOf(0.1, 0.25, 0.4, 0.55, 0.7, 0.85)
                for (fr in fracs) {
                    val raw = retr.getFrameAtTime((durMs * fr * 1000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: continue
                    val bmp = brighten(raw)
                    val yaw = detectYaw(detector, bmp)
                    if (yaw != null) {
                        if (abs(yaw) < 30f) frontal++ else profile++
                    }
                }
            }
        } catch (e: Exception) {
        } finally {
            try { retr.release() } catch (_: Exception) {}
            try { detector.close() } catch (_: Exception) {}
        }
        return when {
            frontal == 0 && profile == 0 -> "none"
            frontal >= profile -> "frontal"
            else -> "profile"
        }
    }

    /** 人躺着拍时脸可能是横的,所以依次尝试 0/90/270/180 度旋转 */
    private fun detectYaw(det: FaceDetector, bmp: Bitmap): Float? {
        for (rot in intArrayOf(0, 90, 270, 180)) {
            val latch = CountDownLatch(1)
            var best: Face? = null
            det.process(InputImage.fromBitmap(bmp, rot))
                .addOnSuccessListener { faces ->
                    best = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                    latch.countDown()
                }
                .addOnFailureListener { latch.countDown() }
            latch.await(8, TimeUnit.SECONDS)
            val found = best
            if (found != null) return found.headEulerAngleY
        }
        return null
    }

    /** 夜间画面很暗,按平均亮度放大增益后再检测 */
    private fun brighten(src: Bitmap): Bitmap {
        val small = Bitmap.createScaledBitmap(src, 32, 32, true)
        val px = IntArray(32 * 32)
        small.getPixels(px, 0, 32, 0, 0, 32, 32)
        var sum = 0.0
        for (p in px) sum += 0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p)
        val mean = (sum / px.size).coerceAtLeast(1.0)
        val gain = (110.0 / mean).coerceIn(1.0, 12.0).toFloat()
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val paint = Paint()
        paint.colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            gain, 0f, 0f, 0f, 0f,
            0f, gain, 0f, 0f, 0f,
            0f, 0f, gain, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )))
        Canvas(out).drawBitmap(src, 0f, 0f, paint)
        return out
    }
}
