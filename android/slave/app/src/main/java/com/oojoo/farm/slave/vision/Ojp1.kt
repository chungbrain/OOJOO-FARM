package com.oojoo.farm.slave.vision

import android.content.Context
import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp

/**
 * OJP1 초경량 모델 공유 런타임.
 *
 * 종별 6클래스(PlantHealthNet) / 건강등급 5단계(HealthGradeNet) /
 * 해충 4단계(PestNet) 가 동일한 .bin 포맷과 forward 를 사용한다.
 * PC 시뮬레이션(simulation/src/models/ojp1.js)과 동일 연산 —
 * Android assets 의 .bin 을 그대로 로드해 기기 == 시뮬레이터 추론을 보장한다.
 */
object Ojp1 {
    private val cache = mutableMapOf<String, Ojp1Model>()

    /** assets/models/{modelId}.bin 로드(캐시). 부재/손상 시 null. */
    fun load(context: Context, modelId: String): Ojp1Model? {
        cache[modelId]?.let { return it }
        return try {
            val bytes = context.assets.open("models/$modelId.bin").use { it.readBytes() }
            val m = parse(bytes)
            cache[modelId] = m
            m
        } catch (_: Exception) {
            null
        }
    }

    /** OJP1 파싱 (LITTLE_ENDIAN):
     *  "OJP1"(4B) | int size | int nclass | int nLayers
     *  per layer: byte type — 1=conv{int kh,kw,cin,cout; float w[]; float b[cout]}
     *                        — 2=dense{int inn,out; float w[inn*out]; float b[out]} */
    fun parse(bytes: ByteArray): Ojp1Model {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4)
        buf.get(magic)
        require(magic.decodeToString() == "OJP1") { "bad model magic" }
        val size = buf.int
        val nclass = buf.int
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
        return Ojp1Model(size, nclass, convs, dense ?: error("no dense"))
    }
}

class ConvLayer(val kh: Int, val kw: Int, val cin: Int, val cout: Int, val w: FloatArray, val b: FloatArray)
class DenseLayer(val inn: Int, val out: Int, val w: FloatArray, val b: FloatArray)

class Ojp1Model(val size: Int, val nclass: Int, val convs: List<ConvLayer>, val dense: DenseLayer) {

    /** 순수 forward — rgb: FloatArray(size*size*3), 0..1 (H,W,C 인터리브) → logits. */
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

    /** Bitmap → (size,size,3) 0..1 RGB (H,W,C 인터리브). */
    fun bitmapToRgb48(bitmap: Bitmap): FloatArray {
        val rgb = FloatArray(size * size * 3)
        val scaled = Bitmap.createScaledBitmap(bitmap, size, size, true)
        val px = IntArray(size * size)
        scaled.getPixels(px, 0, size, 0, 0, size, size)
        if (scaled !== bitmap) scaled.recycle()
        for (i in px.indices) {
            rgb[i * 3] = ((px[i] shr 16) and 0xFF) / 255f
            rgb[i * 3 + 1] = ((px[i] shr 8) and 0xFF) / 255f
            rgb[i * 3 + 2] = (px[i] and 0xFF) / 255f
        }
        return rgb
    }

    /** softmax + argmax — (index, confidence). */
    fun argmaxSoftmax(logits: FloatArray): Pair<Int, Double> {
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
        return maxI to (if (sum > 0) exps[maxI] / sum else 0.0)
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
