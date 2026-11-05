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
 * 人脸检测(ML Kit,离线):
 * - analyze(path):从录像里抽 6 帧判断 正脸 / 侧脸 / 没脸(回放页用,后台线程调用)
 * - detectFace / brightenInfo:测试页实时检测用
 */
object PostureAnalyzer {

    data class FaceHit(val yaw: Float, val rotation: Int)

    fun newDetector(): FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setMinFaceSize(0.05f)
            .build()
    )

    /** 返回 "frontal" / "profile" / "none" */
    fun analyze(path: String): String {
        val retr = MediaMetadataRetriever()
        val detector = newDetector()
        var frontal = 0
        var profile = 0
        try {
            retr.setDataSource(path)
            val durMs = retr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (durMs > 0) {
                val fracs = doubleArrayOf(0.1, 0.25, 0.4, 0.55, 0.7, 0.85)
                for (fr in fracs) {
                    val raw = retr.getFrameAtTime((durMs * fr * 1000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: continue
                    val bmp = brightenInfo(raw).first
                    val hit = detectFace(detector, bmp, 0)
                    if (hit != null) {
                        if (abs(hit.yaw) < 30f) frontal++ else profile++
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

    /** 人躺着拍时脸可能是横的,所以在基础旋转上依次再试 0/90/270/180 度。要在后台线程调用。 */
    fun detectFace(det: FaceDetector, bmp: Bitmap, baseRot: Int): FaceHit? {
        for (extra in intArrayOf(0, 90, 270, 180)) {
            val rot = (baseRot + extra) % 360
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
            if (found != null) return FaceHit(found.headEulerAngleY, rot)
        }
        return null
    }

    /** 返回 (提亮后的图, 原图平均亮度 0..255, 增益)。夜间画面很暗,按平均亮度放大增益后再检测。 */
    fun brightenInfo(src: Bitmap): Triple<Bitmap, Double, Float> {
        val small = Bitmap.createScaledBitmap(src, 32, 32, true)
        val px = IntArray(32 * 32)
        small.getPixels(px, 0, 32, 0, 0, 32, 32)
        var sum = 0.0
        for (p in px) sum += 0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p)
        val mean = sum / px.size
        val gain = (110.0 / mean.coerceAtLeast(1.0)).coerceIn(1.0, 12.0).toFloat()
        if (gain <= 1.05f) return Triple(src, mean, 1f)
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val paint = Paint()
        paint.colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            gain, 0f, 0f, 0f, 0f,
            0f, gain, 0f, 0f, 0f,
            0f, 0f, gain, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )))
        Canvas(out).drawBitmap(src, 0f, 0f, paint)
        return Triple(out, mean, gain)
    }
}
