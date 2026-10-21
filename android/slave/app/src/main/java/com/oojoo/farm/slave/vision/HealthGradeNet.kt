package com.oojoo.farm.slave.vision

import android.content.Context
import android.graphics.Bitmap

data class GradeResult(
    val grade: String,
    val gradeKo: String,
    val confidence: Double,
    val index: Int,
    val modelId: String
)

/**
 * F1 — JevHealthNet (assets/models/health_grade.bin).
 *
 * 식물 건강등급 5단계(매우건강/건강/보통/나쁨/매우나쁨) 초경량 판단 —
 * 종 무관 범용 모델(Jev 등급: ~20KB, 온디바이스 즉시 추론).
 * 기존 종별 6클래스(PlantHealthNet)는 "원인"을, 이 모델은 "등급"을 판단한다.
 * PC 시뮬레이션(simulation/src/models/jevHealth.js)과 동일 모델 파일 사용.
 * 스펙: docs/farmer-features.md §1
 */
object HealthGradeNet {
    const val MODEL_ID = "health_grade"
    val LABELS = listOf("very_healthy", "healthy", "fair", "poor", "very_poor")
    val LABEL_KO = mapOf(
        "very_healthy" to "매우건강",
        "healthy" to "건강",
        "fair" to "보통",
        "poor" to "나쁨",
        "very_poor" to "매우나쁨"
    )

    @Volatile private var appCtx: Context? = null

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    fun labelKo(grade: String): String = LABEL_KO[grade] ?: grade

    /** ROI crop 등 비트맵 → 등급 판정. 모델 부재 시 null. */
    fun infer(bitmap: Bitmap): GradeResult? {
        val model = appCtx?.let { Ojp1.load(it, MODEL_ID) } ?: return null
        return infer(model.bitmapToRgb48(bitmap), model)
    }

    private fun infer(rgb: FloatArray, model: Ojp1Model): GradeResult {
        val logits = model.forward(rgb)
        val (idx, conf) = model.argmaxSoftmax(logits)
        val grade = LABELS.getOrElse(idx) { "healthy" }
        return GradeResult(
            grade = grade,
            gradeKo = labelKo(grade),
            confidence = conf,
            index = idx,
            modelId = MODEL_ID
        )
    }
}
