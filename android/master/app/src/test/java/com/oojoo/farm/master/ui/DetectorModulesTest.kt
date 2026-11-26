package com.oojoo.farm.master.ui

import com.oojoo.farm.master.model.PlantDetectorsResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GET /api/detectors/plant/:plantId 응답 ↔ master DTO 계약.
 * fixture 는 실제 백엔드로 만든 응답 (src/test/resources/plant_detectors_sample.json).
 */
class DetectorModulesTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }
    private val sample by lazy {
        json.decodeFromString<PlantDetectorsResponse>(javaClass.classLoader!!.getResource("plant_detectors_sample.json")!!.readText())
    }

    @Test
    fun decodesAllModulesWithStateResultAndCycle() {
        assertEquals(listOf("health_grade", "pest", "watering"), sample.modules.map { it.id })
        assertTrue(sample.modules.all { it.enabled })

        val hg = sample.modules[0]
        assertNull(hg.latest)
        assertTrue((hg.modelSize ?: 0) > 0)

        val pest = sample.modules[1]
        assertEquals("downloading", pest.state?.state)

        val w = sample.modules[2]
        assertTrue(w.perSpecies)
        assertEquals("dry", w.latest?.label)
        assertEquals("watered_followup", w.latest?.trigger)
        assertEquals(26.5, (w.latest?.detail?.get("hoursSinceWater") as JsonPrimitive).content.toDouble(), 1e-9)
        assertEquals(1, w.cycle?.intervals)
        assertTrue(w.cycle?.lastWateredAt != null)
    }

    @Test
    fun labelsAndNamesFollowLanguage() {
        val w = sample.modules.first { it.id == "watering" }
        assertEquals("물 부족", w.labelText("dry", en = false))
        assertEquals("Dry", w.labelText("dry", en = true))
        assertEquals("mystery", w.labelText("mystery", en = false))
        assertEquals("Watering cycle", w.displayName(en = true))
        assertEquals("관수 주기 모니터링", w.displayName(en = false))
    }

    @Test
    fun severityColorsSeparateGoodFromBad() {
        assertEquals(OojooTheme.Teal, detectorLabelColor("pest", "none"))
        assertEquals(OojooTheme.Red, detectorLabelColor("pest", "severe"))
        assertEquals(OojooTheme.Red, detectorLabelColor("health_grade", "very_poor"))
        assertEquals(OojooTheme.Orange, detectorLabelColor("watering", "dry"))
        assertEquals(OojooTheme.Muted, detectorLabelColor("watering", null))
        assertFalse(detectorLabelColor("future_module", "x") == OojooTheme.Red)
    }

    @Test
    fun modelSizeIsHumanReadable() {
        assertEquals("20KB", formatModelSize(20_164))
        assertEquals("512B", formatModelSize(512))
        assertEquals("1.5MB", formatModelSize(1_572_864))
    }
}
