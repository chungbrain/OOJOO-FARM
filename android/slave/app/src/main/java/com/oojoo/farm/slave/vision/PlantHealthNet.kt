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
 * ì‹ë¬¼ë³„ ë…ë¦½ tiny CNN (conv8-16-24 + GAP + dense6).
 * ê°€ì¤‘ì¹˜ëŠ” `assets/models/<species>.bin` (OJP1) â€” íŒŒì‹±/forward ëŠ” ê³µìœ  ëŸ°íƒ€ìž„
 * Ojp1.kt ë¥¼ ì‚¬ìš©í•œë‹¤ (ê±´ê°•ë“±ê¸‰/í•´ì¶© ëª¨ë¸ê³¼ ë™ì¼ ëŸ°íƒ€ìž„).
 */
object PlantHealthNet {
    const val SIZE = 48
    val LABELS = listOf("healthy", "water_low", "water_high", "light_low", "pest", "heat")
    private val LABEL_KO = mapOf(
        "healthy" to "ê±´ê°•",
        "water_low" to "ë¬¼ ë¶€ì¡±",
        "water_high" to "ë¬¼ ê³¼ë‹¤",
        "light_low" to "í–‡ë¹› ë¶€ì¡±",
        "pest" to "í•´ì¶© ë°œìƒ",
        "heat" to "ì˜¨ë„ ë”ì›€"
    )

    private val ALIAS = mapOf(
        "ë°©ìš¸í† ë§ˆí† " to "cherry_tomato", "í† ë§ˆí† " to "cherry_tomato", "tomato" to "cherry_tomato",
        "cherrytomato" to "cherry_tomato", "cherry tomato" to "cherry_tomato",
        "ë°”ì§ˆ" to "basil", "basil" to "basil",
        "ì„ ì¸ìž¥" to "cactus", "cactus" to "cactus",
        "í—ˆë¸Œ" to "herb", "herb" to "herb", "ë¡œì¦ˆë§ˆë¦¬" to "herb", "rosemary" to "herb",
        "ë”¸ê¸°" to "strawberry", "strawberry" to "strawberry",
        "ê³ ì¶”" to "pepper", "chili" to "pepper", "pepper" to "pepper",
        "í˜¸ë°•" to "pumpkin", "pumpkin" to "pumpkin",
        "ì• í˜¸ë°•" to "zucchini", "zucchini" to "zucchini", "squash" to "zucchini"
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
     * ROI ëª¨ë‹ˆí„°ë§ìš© â€” ëª¨ë¸ì„ ì§ì ‘ ì§€ì •í•´ ì§„ë‹¨.
     * ê°™ì€ í”„ë ˆìž„ì˜ ì„œë¡œ ë‹¤ë¥¸ ROIë¥¼ ê° ì‹ë¬¼ì˜ ëª¨ë¸ë¡œ íŒì •í•œë‹¤.
     * í”„ë ˆìž„ ìŠ¤í‚µ(last ìž¬ì‚¬ìš©) ì—†ì´ í•­ìƒ ì¶”ë¡ í•œë‹¤.
     */
    fun diagnoseWithModel(modelId: String, bitmap: Bitmap): HealthDiagnosis? {
        if (!available(modelId)) return null
        val model = load(modelId) ?: return null
        return infer(modelId, model.bitmapToRgb48(bitmap))
    }

    /** ì‹ë¬¼ ì¢…ëª… â†’ ëª¨ë¸ id í•´ì„ (ë§¤í•‘ ì—†ìœ¼ë©´ null). */
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
