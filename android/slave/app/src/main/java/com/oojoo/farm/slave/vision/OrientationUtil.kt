package com.oojoo.farm.slave.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import java.io.File

/**
 * 가로/세로 촬영 방향 지원 유틸.
 *
 * CameraX 캡처 결과(프레임/사진)는 센서 좌표계로 나오므로 화면에 보이는
 * 프리뷰 방향과 일치시려면 rotationDegrees/EXIF 만큼 회전해야 한다.
 * ROI 정규화 좌표는 upright 프리뷰 기준으로 그려지므로, 분석·크롭용
 * 비트맵은 반드시 upright로 변환한다.
 */
object OrientationUtil {

    /** 비트맵을 시계 방향으로 degrees(0/90/180/270) 회전. 원본은 재활용. */
    fun rotateBitmap(bmp: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return bmp
        return try {
            val m = Matrix().apply { postRotate(degrees.toFloat()) }
            val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (out != bmp) bmp.recycle()
            out
        } catch (_: Exception) {
            bmp
        }
    }

    /** JPEG 파일을 EXIF 방향 태그에 맞춰 upright로 디코딩. */
    fun decodeUpright(path: String): Bitmap? {
        val bmp = BitmapFactory.decodeFile(path) ?: return null
        val deg = exifRotation(path)
        return if (deg == 0) bmp else rotateBitmap(bmp, deg)
    }

    private fun exifRotation(path: String): Int = try {
        val exif = android.media.ExifInterface(path)
        when (exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
            android.media.ExifInterface.ORIENTATION_TRANSPOSE -> 90
            android.media.ExifInterface.ORIENTATION_TRANSVERSE -> 270
            else -> 0
        }
    } catch (_: Exception) {
        0
    }

    /**
     * JPEG 파일을 upright로 디코딩해 최대 변 길이 maxDim(px)으로 축소 재압축한다.
     * 백엔드 업로드 제한(사진 8MB)을 고해상도 원본이 넘겨 413로 거부되는 것 방지.
     * 축소 결과물은 src와 같은 폴더의 임시 파일. 실패 시 원본 src를 그대로 반환.
     */
    fun downscaleJpeg(src: File, maxDim: Int = 1920, quality: Int = 85): File {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(src.absolutePath, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxDim || bounds.outHeight / (sample * 2) >= maxDim) sample *= 2
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            var bmp = BitmapFactory.decodeFile(src.absolutePath, decodeOpts) ?: return src
            bmp = rotateBitmap(bmp, exifRotation(src.absolutePath))
            val scale = minOf(maxDim.toFloat() / maxOf(bmp.width, bmp.height), 1f)
            if (scale < 1f) {
                val w = (bmp.width * scale).toInt().coerceAtLeast(1)
                val h = (bmp.height * scale).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(bmp, w, h, true)
                if (scaled != bmp) bmp.recycle()
                bmp = scaled
            }
            val out = File(src.parentFile ?: return src, "up_${System.currentTimeMillis()}_${src.name}")
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
            bmp.recycle()
            if (out.exists() && out.length() > 0) return out
            out.delete()
        } catch (_: Exception) {}
        return src
    }
}
