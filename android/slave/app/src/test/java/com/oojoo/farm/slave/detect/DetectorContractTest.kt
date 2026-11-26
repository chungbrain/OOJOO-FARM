package com.oojoo.farm.slave.detect

import com.oojoo.farm.slave.model.DetectorCatalogResponse
import com.oojoo.farm.slave.model.DetectorResultItem
import com.oojoo.farm.slave.model.DetectorResultsRequest
import com.oojoo.farm.slave.model.PlantsResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 서버 응답 ↔ 앱 DTO 계약. fixture 는 실제 백엔드(backend/src)로 만든 응답이다
 * (src/test/resources 의 *_sample.json 파일 — 백엔드 계약이 바뀌면 다시 생성).
 */
class DetectorContractTest {
    // ApiClient 와 같은 설정
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }

    private fun resource(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    @Test
    fun catalogDecodes() {
        val c = json.decodeFromString<DetectorCatalogResponse>(resource("detector_catalog_sample.json"))
        assertEquals(listOf(DetectorIds.HEALTH, DetectorIds.PEST, DetectorIds.WATERING), c.modules.map { it.id })
        assertEquals(DetectorIds.DEFAULTS, c.defaults.toSet())

        val hg = c.modules.first { it.id == DetectorIds.HEALTH }
        assertEquals(5, hg.labels.size)
        assertTrue(Regex("^[0-9a-f]{64}$").matches(hg.model!!.sha256))
        assertEquals("/api/detectors/models/health_grade.bin", hg.model!!.url)
        assertTrue(hg.model!!.size > 0)

        val w = c.modules.first { it.id == DetectorIds.WATERING }
        assertTrue(w.perSpecies)
        assertEquals(listOf("dry", "ok", "wet"), w.labels)
        assertEquals("basil.bin", w.speciesModels!!.getValue("basil").file)
        assertEquals(listOf("healthy", "water_low", "water_high", "light_low", "pest", "heat"), w.speciesOutputs)
    }

    @Test
    fun slavePlantsCarryDetectors() {
        val p = json.decodeFromString<PlantsResponse>(resource("slave_plants_sample.json")).plants.single()
        assertEquals(listOf(DetectorIds.HEALTH, DetectorIds.PEST, DetectorIds.WATERING), p.detectors)
        assertEquals("basil", p.species)
    }

    @Test
    fun resultRequestUsesServerFieldNames() {
        val body = DetectorResultsRequest(
            "s1", "p1",
            listOf(DetectorResultItem(DetectorIds.WATERING, "dry", 0.71, Triggers.WATERED, "abc", buildJsonObject { put("hoursSinceWater", 3.5) }))
        )
        val o = Json.parseToJsonElement(json.encodeToString(body)).jsonObject
        assertEquals(setOf("slaveId", "plantId", "results"), o.keys)
        val r = (o["results"] as kotlinx.serialization.json.JsonArray)[0].jsonObject
        // backend/src/routes/detectors.js cleanResult 가 읽는 키
        assertEquals(setOf("moduleId", "label", "confidence", "trigger", "modelSha", "detail"), r.keys)
        assertNotNull(r["detail"]!!.jsonObject["hoursSinceWater"])
    }
}
