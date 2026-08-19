package com.oojoo.farm.slave.vision

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp

data class HealthDiagnosis(
    val condition: String,
    val confidence: Double,
    val modelId: String,
    val needWater: Boolean,
    val pestSuspected: Boolean,
    val labelKo: String
)

/**
 * 식물별 독립 tiny CNN (conv8-16-24 + GAP + dense6).
 * 가중치는 `assets/models/<species>.bin` (OJP1).
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
        "애호박" to "zucchini", "zucchini" to "zucchini", "squash" to "zucchini"
    )

    @Volatile private var appCtx: Context? = null
    @Volatile var speciesId: String? = null
        private set
    private val cache = mutableMapOf<String, PackedModel>()
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
        val rgb = FloatArray(SIZE * SIZE * 3)
        val scaled = Bitmap.createScaledBitmap(bitmap, SIZE, SIZE, true)
        val px = IntArray(SIZE * SIZE)
        scaled.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        if (scaled !== bitmap) scaled.recycle()
        for (i in px.indices) {
            rgb[i * 3] = ((px[i] shr 16) and 0xFF) / 255f
            rgb[i * 3 + 1] = ((px[i] shr 8) and 0xFF) / 255f
            rgb[i * 3 + 2] = (px[i] and 0xFF) / 255f
        }
        last = infer(id, rgb)
        return last
    }

    @Volatile private var last: HealthDiagnosis? = null

    fun infer(modelId: String, rgb: FloatArray): HealthDiagnosis? {
        val model = load(modelId) ?: return null
        val logits = model.forward(rgb)
        var maxI = 0
        var maxV = logits[0]
        var sum = 0.0
        val exps = DoubleArray(logits.size)
        for (i in logits.indices) {
            exps[i] = exp(logits[i].toDouble())
            sum += exps[i]
            if (logits[i] > maxV) {
                maxV = logits[i]
                maxI = i
            }
        }
        val conf = if (sum > 0) exps[maxI] / sum else 0.0
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

    private fun load(id: String): PackedModel? {
        cache[id]?.let { return it }
        val ctx = appCtx ?: return null
        return try {
            val bytes = ctx.assets.open("models/$id.bin").use { it.readBytes() }
            val m = PackedModel.parse(bytes)
            cache[id] = m
            m
        } catch (_: Exception) {
            null
        }
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

private class ConvLayer(val kh: Int, val kw: Int, val cin: Int, val cout: Int, val w: FloatArray, val b: FloatArray)
private class DenseLayer(val inn: Int, val out: Int, val w: FloatArray, val b: FloatArray)

private class PackedModel(val size: Int, val convs: List<ConvLayer>, val dense: DenseLayer) {
    fun forward(rgb: FloatArray): FloatArray {
        var maps = rgb
        var h = size
        var w = size
        var c = 3
        for (layer in convs) {
            maps = convRelu(maps, h, w, c, layer)
            c = layer.cout
            maps = maxPool2(maps, h, w, c)
            h /= 2
            w /= 2
        }
        val gap = FloatArray(c)
        val plane = h * w
        for (ch in 0 until c) {
            var s = 0f
            for (i in 0 until plane) s += maps[i * c + ch]
            gap[ch] = s / plane
        }
        val logits = FloatArray(dense.out)
        for (o in 0 until dense.out) {
            var s = dense.b[o]
            for (i in 0 until dense.inn) s += gap[i] * dense.w[i * dense.out + o]
            logits[o] = s
        }
        return logits
    }

    companion object {
        fun parse(bytes: ByteArray): PackedModel {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4)
            buf.get(magic)
            require(magic.decodeToString() == "OJP1") { "bad model magic" }
            val size = buf.int
            buf.int // nclass
            val nLayers = buf.int
            val convs = mutableListOf<ConvLayer>()
            var dense: DenseLayer? = null
            repeat(nLayers) {
                val type = buf.get().toInt() and 0xFF
                if (type == 1) {
                    val kh = buf.int
                    val kw = buf.int
                    val cin = buf.int
                    val cout = buf.int
                    val w = FloatArray(kh * kw * cin * cout)
                    for (i in w.indices) w[i] = buf.float
                    val b = FloatArray(cout)
                    for (i in b.indices) b[i] = buf.float
                    convs += ConvLayer(kh, kw, cin, cout, w, b)
                } else {
                    val inn = buf.int
                    val out = buf.int
                    val w = FloatArray(inn * out)
                    for (i in w.indices) w[i] = buf.float
                    val b = FloatArray(out)
                    for (i in b.indices) b[i] = buf.float
                    dense = DenseLayer(inn, out, w, b)
                }
            }
            return PackedModel(size, convs, dense ?: error("no dense"))
        }
    }
}

private fun convRelu(src: FloatArray, h: Int, w: Int, cin: Int, layer: ConvLayer): FloatArray {
    val out = FloatArray(h * w * layer.cout)
    val pad = layer.kh / 2
    for (y in 0 until h) {
        for (x in 0 until w) {
            for (oc in 0 until layer.cout) {
                var s = layer.b[oc]
                for (ky in 0 until layer.kh) {
                    val iy = y + ky - pad
                    if (iy < 0 || iy >= h) continue
                    for (kx in 0 until layer.kw) {
                        val ix = x + kx - pad
                        if (ix < 0 || ix >= w) continue
                        for (ic in 0 until cin) {
                            val wi = ((ky * layer.kw + kx) * cin + ic) * layer.cout + oc
                            val si = (iy * w + ix) * cin + ic
                            s += src[si] * layer.w[wi]
                        }
                    }
                }
                out[(y * w + x) * layer.cout + oc] = if (s > 0f) s else 0f
            }
        }
    }
    return out
}

private fun maxPool2(src: FloatArray, h: Int, w: Int, c: Int): FloatArray {
    val oh = h / 2
    val ow = w / 2
    val out = FloatArray(oh * ow * c)
    for (y in 0 until oh) {
        for (x in 0 until ow) {
            for (ch in 0 until c) {
                var m = Float.NEGATIVE_INFINITY
                for (dy in 0..1) for (dx in 0..1) {
                    val v = src[((y * 2 + dy) * w + (x * 2 + dx)) * c + ch]
                    if (v > m) m = v
                }
                out[(y * ow + x) * c + ch] = m
            }
        }
    }
    return out
}
