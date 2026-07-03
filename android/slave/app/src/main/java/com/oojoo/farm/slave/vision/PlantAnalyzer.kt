package com.oojoo.farm.slave.vision

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.media.Image.Plane
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import kotlin.math.abs

data class AnalysisResult(
    val greenness: Double,
    val brightness: Double,
    val healthStatus: String,
    val needWater: Boolean,
    val confidence: Double
)

object PlantAnalyzer {

    @OptIn(ExperimentalGetImage::class)
    fun analyze(imageProxy: ImageProxy): AnalysisResult {
        val image = imageProxy.image
        val result = if (image != null && imageProxy.format == ImageFormat.YUV_420_888) {
            analyzeYUV(image)
        } else {
            AnalysisResult(0.0, 0.0, "분석 불가", false, 0.0)
        }
        imageProxy.close()
        return result
    }

    private fun analyzeYUV(image: Image): AnalysisResult {
        val width = image.width
        val height = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        var totalBrightness = 0
        var totalGreen = 0
        var sampleCount = 0

        val step = 8
        for (row in 0 until height step step) {
            for (col in 0 until width step step) {
                val yVal = yPlane.buffer.get(row * yPlane.rowStride + col * yPlane.pixelStride).toInt() and 0xFF
                val uvRow = (row / 2)
                val uvCol = (col / 2)
                val uVal = uPlane.buffer.get(uvRow * uPlane.rowStride + uvCol * uPlane.pixelStride).toInt() and 0xFF
                val vVal = vPlane.buffer.get(uvRow * vPlane.rowStride + uvCol * vPlane.pixelStride).toInt() and 0xFF

                totalBrightness += yVal

                val u = uVal - 128
                val v = vVal - 128
                val g = yVal - ((u * 39192) shr 8) - ((v * 32800) shr 8)
                totalGreen += g.coerceIn(0, 255)

                sampleCount++
            }
        }

        val avgBrightness = if (sampleCount > 0) totalBrightness.toDouble() / sampleCount else 0.0
        val avgGreen = if (sampleCount > 0) totalGreen.toDouble() / sampleCount else 0.0
        val greenness = avgGreen / 255.0
        val brightnessNorm = avgBrightness / 255.0

        val healthStatus = when {
            brightnessNorm < 0.1 -> "너무 어두움 (카메라 위치 확인 필요)"
            greenness > 0.45 -> "건강 (녹색 충분)"
            greenness > 0.25 -> "보통 (녹색 약간 부족)"
            greenness > 0.1 -> "주의 (황변 의심)"
            else -> "이상 (색상 심각)"
        }

        val needWater = brightnessNorm > 0.15 && greenness < 0.3
        val confidence = (0.5 + greenness * 0.3 + (1.0 - abs(brightnessNorm - 0.5)) * 0.2).coerceIn(0.0, 1.0)

        return AnalysisResult(greenness, brightnessNorm, healthStatus, needWater, confidence)
    }

    fun analyzeBitmap(bitmap: Bitmap): AnalysisResult {
        val width = bitmap.width
        val height = bitmap.height
        var totalR = 0
        var totalG = 0
        var totalB = 0
        var sampleCount = 0

        val step = 16
        for (y in 0 until height step step) {
            for (x in 0 until width step step) {
                val pixel = bitmap.getPixel(x, y)
                totalR += (pixel shr 16) and 0xFF
                totalG += (pixel shr 8) and 0xFF
                totalB += pixel and 0xFF
                sampleCount++
            }
        }

        val avgR = if (sampleCount > 0) totalR.toDouble() / sampleCount else 0.0
        val avgG = if (sampleCount > 0) totalG.toDouble() / sampleCount else 0.0
        val avgB = if (sampleCount > 0) totalB.toDouble() / sampleCount else 0.0
        val total = avgR + avgG + avgB
        val greenness = if (total > 0) avgG / total else 0.0
        val brightnessNorm = total / (3.0 * 255.0)

        val healthStatus = when {
            brightnessNorm < 0.1 -> "너무 어두움 (카메라 위치 확인 필요)"
            greenness > 0.4 -> "건강 (녹색 충분)"
            greenness > 0.25 -> "보통 (녹색 약간 부족)"
            greenness > 0.1 -> "주의 (황변 의심)"
            else -> "이상 (색상 심각)"
        }

        val needWater = brightnessNorm > 0.15 && greenness < 0.28
        val confidence = (0.5 + greenness * 0.3 + (1.0 - abs(brightnessNorm - 0.5)) * 0.2).coerceIn(0.0, 1.0)

        return AnalysisResult(greenness, brightnessNorm, healthStatus, needWater, confidence)
    }
}
