package com.oojoo.farm.slave.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix

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
}
