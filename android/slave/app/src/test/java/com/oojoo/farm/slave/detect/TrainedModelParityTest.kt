package com.oojoo.farm.slave.detect

import com.oojoo.farm.slave.vision.Ojp1
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.math.abs
import kotlin.math.max

/**
 * model-manager(서버 JS)가 학습해 배포한 모델을 farmer 의 Ojp1 런타임이 같은 값으로 계산하는지.
 * fixture: scripts/make-ojp1-parity-fixture.mjs 가 만든 trained_parity.bin/json
 * (해충 모델에 새 라벨 aphid 를 추가해 미세조정한 5출력 모델).
 */
class TrainedModelParityTest {
    private fun resource(name: String) = javaClass.classLoader!!.getResource(name)!!

    @Test
    fun kotlinForwardMatchesServerTrainedLogits() {
        val model = Ojp1.parse(resource("trained_parity.bin").readBytes())
        val spec = Json.parseToJsonElement(resource("trained_parity.json").readText()).jsonObject
        val labels = spec["labels"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("none", "light", "moderate", "severe", "aphid"), labels)
        assertEquals(labels.size, model.dense.out)
        val tol = spec["tolerance"]!!.jsonPrimitive.double
        val cases = spec["cases"]!!.jsonArray
        assertTrue(cases.size >= 3)
        for (c in cases) {
            val o = c.jsonObject
            val bytes = Base64.getDecoder().decode(o["input"]!!.jsonPrimitive.content)
            val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val x = FloatArray(fb.remaining()).also { fb.get(it) }
            val want = o["logits"]!!.jsonArray.map { it.jsonPrimitive.double }
            val got = model.forward(x)
            for (i in want.indices) {
                assertTrue("logit[$i] ${got[i]} vs ${want[i]}", abs(got[i] - want[i]) <= tol * max(1.0, abs(want[i])))
            }
        }
    }

    @Test
    fun speciesNamesNormalizeLikeServer() {
        // 서버 backend/src/ml/registry.js normAlias 와 같은 규칙
        assertEquals("monsteradeliciosa", normalizeSpecies("  Monstera Deliciosa "))
        assertEquals("몬스테라", normalizeSpecies("몬스테라"))
    }
}
