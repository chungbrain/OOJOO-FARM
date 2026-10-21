package com.oojoo.farm.slave.vision

import android.content.Context
import android.graphics.Bitmap

data class PestResult(
    val level: String,
    val levelKo: String,
    val confidence: Double,
    val index: Int,
    val modelId: String
)

/**
 * F2 — PestNet (assets/models/pest.bin).
 *
 * 해충발생 전용 초경량 CNN — 건강등급 판단(HealthGradeNet)과 완전히 별도 모델.
 * 4단계(해충없음/경미/보통/심각) 평가. 다양한 건강등급 배경 위에 해충 증상을
 * 학습해 등급 신호(황변/시듦)와 해충 증상(식해 구멍/벌레)을 독립적으로 구분한다.
 * PC 시뮬레이션(simulation/src/models/pestNet.js)과 동일 모델 파일 사용.
 * 스펙: docs/farmer-features.md §2
 */
object PestNet {
    const val MODEL_ID = "pest"
    val LABELS = listOf("none", "light", "moderate", "severe")
    val LABEL_KO = mapOf(
        "none" to "해충없음",
        "light" to "경미발생",
        "moderate" to "보통발생",
        "severe" to "심각발생"
    )

    @Volatile private var appCtx: Context? = null

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    fun labelKo(level: String): String = LABEL_KO[level] ?: level

    /** 해충 발생 여부 — light 이상이면 퇴치 대응(Fan/Laser) 트리거. */
    fun isDetected(level: String): Boolean = level != "none"

    /** ROI crop 등 비트맵 → 해충 단계 판정. 모델 부재 시 null. */
    fun infer(bitmap: Bitmap): PestResult? {
        val model = appCtx?.let { Ojp1.load(it, MODEL_ID) } ?: return null
        return infer(model.bitmapToRgb48(bitmap), model)
    }

    private fun infer(rgb: FloatArray, model: Ojp1Model): PestResult {
        val logits = model.forward(rgb)
        val (idx, conf) = model.argmaxSoftmax(logits)
        val level = LABELS.getOrElse(idx) { "none" }
        return PestResult(
            level = level,
            levelKo = labelKo(level),
            confidence = conf,
            index = idx,
            modelId = MODEL_ID
        )
    }
}
