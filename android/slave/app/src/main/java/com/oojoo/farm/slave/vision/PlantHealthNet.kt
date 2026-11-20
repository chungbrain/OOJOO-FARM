package com.oojoo.farm.slave.vision

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import java.nio.ByteBuffer

data class HealthDiagnosis(
    val condition: String,
    val confidence: Double,
    val modelId: String,
    val needWater: Boolean,
    val pestSuspected: Boolean,
    val labelKo: String
)

/**
 * 종별 6클래스 tiny CNN (conv8-16-24 + GAP + dense6).
 * 가중치는 `assets/models/<species>.bin` (OJP1) — 파싱/forward 는 공용 런타임
 * Ojp1.kt 를 사용한다 (건강등급/해충 모델과 동일 포맷).
 */
object PlantHealthNet {
    const val SIZE = 48
    val LABELS = listOf("healthy", "water_low", "water_high", "light_low", "pest", "heat")
    private val LABEL_KO = mapOf(
        "healthy" to "건강",
        "water_low" to "물 부족",
        "water_high" to "물 과다",
        "light_low" to "햇빛 부족",
        "pest" to "해충 발생",
        "heat" to "온도 더움"
    )

    private val ALIAS = mapOf(
        "방울토마토" to "cherry_tomato", "토마토" to "cherry_tomato", "tomato" to "cherry_tomato",
        "cherrytomato" to "cherry_tomato", "cherry tomato" to "cherry_tomato",
        "바질" to "basil", "basil" to "basil",
        "선인장" to "cactus", "cactus" to "cactus",
        "허브" to "herb", "herb" to "herb", "로즈마리" to "herb", "rosemary" to "herb",
        "딸기" to "strawberry", "strawberry" to "strawberry",
        "고추" to "pepper", "chili" to "pepper", "pepper" to "pepper",
        "호박" to "pumpkin", "pumpkin" to "pumpkin",
        "애호박" to "zucchini", "zucchini" to "zucchini", "squash" to "zucchini",
        "상추" to "lettuce", "lettuce" to "lettuce",
        "깻잎" to "perilla", "perilla" to "perilla", "shiso" to "perilla",
        "대파" to "green_onion", "파" to "green_onion", "greenonion" to "green_onion", "green onion" to "green_onion",
        "고구마" to "sweet_potato", "sweetpotato" to "sweet_potato", "sweet potato" to "sweet_potato",
        "감자" to "potato", "potato" to "potato",
        "양파" to "onion", "onion" to "onion"
    )

    @Volatile private var appCtx: Context? = null
    @Volatile var speciesId: String? = null
        private set
    private var frame = 0

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    fun setSpecies(raw: String?) {
        speciesId = resolve(raw)
    }

    fun resolve(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val key = raw.trim().lowercase().replace(" ", "")
        return ALIAS[raw.trim()] ?: ALIAS[key] ?: ALIAS[raw.trim().lowercase()]
    }

    fun available(id: String): Boolean {
        val ctx = appCtx ?: return false
        return try {
            ctx.assets.open("models/$id.bin").close()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun maybeDiagnose(image: Image): HealthDiagnosis? {
        val id = speciesId ?: return null
        if (!available(id)) return null
        frame++
        if (frame % 6 != 0) return last
        val y = image.planes[0]
        val u = image.planes[1]
        val v = image.planes[2]
        val rgb = yuvToRgb48(
            y.buffer, u.buffer, v.buffer,
            image.width, image.height,
            y.rowStride, y.pixelStride, u.rowStride, u.pixelStride, v.rowStride, v.pixelStride
        )
        last = infer(id, rgb)
        return last
    }

    fun maybeDiagnose(bitmap: Bitmap): HealthDiagnosis? {
        val id = speciesId ?: return null
        if (!available(id)) return null
        val model = load(id) ?: return null
        last = infer(id, model.bitmapToRgb48(bitmap))
        return last
    }

    /**
     * ROI 모니터링용 — 모델을 직접 지정해 진단.
     * 같은 프레임의 서로 다른 ROI를 각 식물의 모델로 판정한다.
     * 프레임 스킵(last 저장용) 없이 항상 실행한다.
     */
    fun diagnoseWithModel(modelId: String, bitmap: Bitmap): HealthDiagnosis? {
        if (!available(modelId)) return null
        val model = load(modelId) ?: return null
        return infer(modelId, model.bitmapToRgb48(bitmap))
    }

    /** 식물 이름 → 모델 id 해석 (모델 없으면 null). */
    fun modelIdFor(raw: String?): String? {
        val id = resolve(raw) ?: return null
        return if (available(id)) id else null
    }

    @Volatile private var last: HealthDiagnosis? = null

    fun infer(modelId: String, rgb: FloatArray): HealthDiagnosis? {
        val model = load(modelId) ?: return null
        val logits = model.forward(rgb)
        val (maxI, conf) = model.argmaxSoftmax(logits)
        val cond = LABELS.getOrElse(maxI) { "healthy" }
        return HealthDiagnosis(
            condition = cond,
            confidence = conf,
            modelId = modelId,
            needWater = cond == "water_low",
            pestSuspected = cond == "pest",
            labelKo = LABEL_KO[cond] ?: cond
        )
    }

    private fun load(id: String): Ojp1Model? {
        val ctx = appCtx ?: return null
        return Ojp1.load(ctx, id)
    }

    private fun yuvToRgb48(
        yb: ByteBuffer, ub: ByteBuffer, vb: ByteBuffer,
        width: Int, height: Int,
        yRow: Int, yPix: Int, uRow: Int, uPix: Int, vRow: Int, vPix: Int
    ): FloatArray {
        val out = FloatArray(SIZE * SIZE * 3)
        for (row in 0 until SIZE) {
            val sy = (row * height) / SIZE
            for (col in 0 until SIZE) {
                val sx = (col * width) / SIZE
                val y = yb.get(sy * yRow + sx * yPix).toInt() and 0xFF
                val uvR = sy / 2
                val uvC = sx / 2
                val u = (ub.get(uvR * uRow + uvC * uPix).toInt() and 0xFF) - 128
                val v = (vb.get(uvR * vRow + uvC * vPix).toInt() and 0xFF) - 128
                val r = (y + ((v * 359) shr 8)).coerceIn(0, 255)
                val g = (y - ((u * 88) shr 8) - ((v * 183) shr 8)).coerceIn(0, 255)
                val b = (y + ((u * 454) shr 8)).coerceIn(0, 255)
                val o = (row * SIZE + col) * 3
                out[o] = r / 255f
                out[o + 1] = g / 255f
                out[o + 2] = b / 255f
            }
        }
        return out
    }
}
