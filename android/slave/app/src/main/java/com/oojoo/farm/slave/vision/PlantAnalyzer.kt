package com.oojoo.farm.slave.vision

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import kotlin.math.abs

/** 3단계 침대라 분석 결과. */
data class AnalysisResult(
    val greenness: Double,
    val brightness: Double,
    val healthStatus: String,
    val needWater: Boolean,
    val confidence: Double,
    val fruitRipeness: Double = 0.0,
    val pestSuspected: Boolean = false,
    val wideShot: WideAnalysis? = null,
    val normalShot: NormalAnalysis? = null,
    val zoomShot: ZoomAnalysis? = null
)

data class WideAnalysis(
    val plantCount: Int,
    val distribution: String,
    val overallHealth: String
)

data class NormalAnalysis(
    val plantHealth: String,
    val healthScore: Int,
    val growthStage: String
)

data class ZoomAnalysis(
    val fruitDetected: Boolean,
    val fruitCount: Int,
    val pestDetail: String,
    val leafCondition: String
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
        var redCount = 0
        var darkCount = 0
        var sampleCount = 0

        val step = 8
        for (row in 0 until height step step) {
            for (col in 0 until width step step) {
                val yVal = yPlane.buffer.get(row * yPlane.rowStride + col * yPlane.pixelStride).toInt() and 0xFF
                val uvRow = row / 2
                val uvCol = col / 2
                val uVal = uPlane.buffer.get(uvRow * uPlane.rowStride + uvCol * uPlane.pixelStride).toInt() and 0xFF
                val vVal = vPlane.buffer.get(uvRow * vPlane.rowStride + uvCol * vPlane.pixelStride).toInt() and 0xFF

                totalBrightness += yVal
                val u = uVal - 128
                val v = vVal - 128
                val g = yVal - ((u * 39192) shr 8) - ((v * 32800) shr 8)
                totalGreen += g.coerceIn(0, 255)
                if (vVal > 150 && uVal < 118 && yVal > 40) redCount++
                if (yVal < 45) darkCount++
                sampleCount++
            }
        }

        val avgBrightness = if (sampleCount > 0) totalBrightness.toDouble() / sampleCount else 0.0
        val avgGreen = if (sampleCount > 0) totalGreen.toDouble() / sampleCount else 0.0
        val greenness = avgGreen / 255.0
        val brightnessNorm = avgBrightness / 255.0
        val redRatio = if (sampleCount > 0) redCount.toDouble() / sampleCount else 0.0
        val darkRatio = if (sampleCount > 0) darkCount.toDouble() / sampleCount else 0.0

        val healthStatus = when {
            brightnessNorm < 0.1 -> "너무 어두움 (침대라 위치 확인 필요)"
            greenness > 0.45 -> "건강 (녹색 충분)"
            greenness > 0.25 -> "보통 (녹색 약간 부족)"
            greenness > 0.1 -> "주의 (황변 의심)"
            else -> "이상 (색상 심각)"
        }

        val needWater = brightnessNorm > 0.15 && greenness < 0.3
        val confidence = (0.5 + greenness * 0.3 + (1.0 - abs(brightnessNorm - 0.5)) * 0.2).coerceIn(0.0, 1.0)
        val ripeness = (redRatio * 3.0).coerceIn(0.0, 1.0)
        val pest = brightnessNorm > 0.15 && darkRatio in 0.06..0.45

        // 3단계 분석 시뮬레이션
        val plantCount = when {
            greenness > 0.4 -> (1 + (greenness * 10).toInt()).coerceAtMost(8)
            greenness > 0.25 -> (1 + (greenness * 8).toInt()).coerceAtMost(5)
            else -> 1
        }
        val distribution = when { greenness > 0.35 -> "고름"; greenness > 0.2 -> "편중"; else -> "불량" }
        val overallHealth = when { greenness > 0.4 -> "양호"; greenness > 0.25 -> "보통"; else -> "주의" }
        val healthScore = (greenness * 60 + (1 - abs(brightnessNorm - 0.5)) * 40).toInt().coerceIn(0, 100)
        val growthStage = when { ripeness > 0.3 -> "결실"; redRatio > 0.08 -> "개화"; greenness > 0.35 -> "영양생장"; else -> "묘목" }
        val fruitDetected = redRatio > 0.03
        val fruitCount = (redRatio * 20).toInt().coerceAtMost(15)
        val pestDetail = when { darkRatio > 0.25 -> "확인"; darkRatio > 0.08 -> "의심"; else -> "없음" }
        val leafCondition = when { greenness > 0.4 -> "건강"; greenness > 0.2 -> "황변"; brightnessNorm > 0.8 -> "마름"; else -> "반점" }

        return AnalysisResult(
            greenness, brightnessNorm, healthStatus, needWater, confidence, ripeness, pest,
            wideShot = WideAnalysis(plantCount, distribution, overallHealth),
            normalShot = NormalAnalysis(healthStatus, healthScore, growthStage),
            zoomShot = ZoomAnalysis(fruitDetected, fruitCount, pestDetail, leafCondition)
        )
    }

    fun analyzeBitmap(bitmap: Bitmap): AnalysisResult {
        val width = bitmap.width
        val height = bitmap.height
        var totalR = 0
        var totalG = 0
        var totalB = 0
        var redCount = 0
        var darkCount = 0
        var sampleCount = 0

        val step = 16
        for (y in 0 until height step step) {
            for (x in 0 until width step step) {
                val pixel = bitmap.getPixel(x, y)
                val rr = (pixel shr 16) and 0xFF
                val gg = (pixel shr 8) and 0xFF
                val bb = pixel and 0xFF
                totalR += rr; totalG += gg; totalB += bb
                if (rr > 140 && rr > gg * 1.4 && rr > bb * 1.4) redCount++
                if (rr + gg + bb < 90) darkCount++
                sampleCount++
            }
        }

        val avgR = if (sampleCount > 0) totalR.toDouble() / sampleCount else 0.0
        val avgG = if (sampleCount > 0) totalG.toDouble() / sampleCount else 0.0
        val avgB = if (sampleCount > 0) totalB.toDouble() / sampleCount else 0.0
        val total = avgR + avgG + avgB
        val greenness = if (total > 0) avgG / total else 0.0
        val brightnessNorm = total / (3.0 * 255.0)
        val redRatio = if (sampleCount > 0) redCount.toDouble() / sampleCount else 0.0
        val darkRatio = if (sampleCount > 0) darkCount.toDouble() / sampleCount else 0.0

        val healthStatus = when {
            brightnessNorm < 0.1 -> "너무 어두움 (침대라 위치 확인 필요)"
            greenness > 0.4 -> "건강 (녹색 충분)"
            greenness > 0.25 -> "보통 (녹색 약간 부족)"
            greenness > 0.1 -> "주의 (황변 의심)"
            else -> "이상 (색상 심각)"
        }

        val needWater = brightnessNorm > 0.15 && greenness < 0.28
        val confidence = (0.5 + greenness * 0.3 + (1.0 - abs(brightnessNorm - 0.5)) * 0.2).coerceIn(0.0, 1.0)
        val ripeness = (redRatio * 3.0).coerceIn(0.0, 1.0)
        val pest = brightnessNorm > 0.15 && darkRatio in 0.06..0.45

        val plantCount = when { greenness > 0.4 -> (1 + (greenness * 10).toInt()).coerceAtMost(8); greenness > 0.25 -> (1 + (greenness * 8).toInt()).coerceAtMost(5); else -> 1 }
        val distribution = when { greenness > 0.35 -> "고름"; greenness > 0.2 -> "편중"; else -> "불량" }
        val overallHealth = when { greenness > 0.4 -> "양호"; greenness > 0.25 -> "보통"; else -> "주의" }
        val healthScore = (greenness * 60 + (1 - abs(brightnessNorm - 0.5)) * 40).toInt().coerceIn(0, 100)
        val growthStage = when { ripeness > 0.3 -> "결실"; redRatio > 0.08 -> "개화"; greenness > 0.35 -> "영양생장"; else -> "묘목" }
        val fruitDetected = redRatio > 0.03
        val fruitCount = (redRatio * 20).toInt().coerceAtMost(15)
        val pestDetail = when { darkRatio > 0.25 -> "확인"; darkRatio > 0.08 -> "의심"; else -> "없음" }
        val leafCondition = when { greenness > 0.4 -> "건강"; greenness > 0.2 -> "황변"; brightnessNorm > 0.8 -> "마름"; else -> "반점" }

        return AnalysisResult(
            greenness, brightnessNorm, healthStatus, needWater, confidence, ripeness, pest,
            wideShot = WideAnalysis(plantCount, distribution, overallHealth),
            normalShot = NormalAnalysis(healthStatus, healthScore, growthStage),
            zoomShot = ZoomAnalysis(fruitDetected, fruitCount, pestDetail, leafCondition)
        )
    }
}
