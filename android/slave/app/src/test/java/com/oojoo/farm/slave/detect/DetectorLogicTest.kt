package com.oojoo.farm.slave.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectorLogicTest {
    private val outputs = listOf("healthy", "water_low", "water_high", "light_low", "pest", "heat")
    private val hour = 3_600_000L

    // ---------- 관수 라벨 ----------

    @Test
    fun waterLabelMapsSpeciesOutputs() {
        assertEquals("dry", waterLabelFromProbs(doubleArrayOf(0.1, 0.7, 0.1, 0.05, 0.03, 0.02), outputs).first)
        assertEquals("wet", waterLabelFromProbs(doubleArrayOf(0.1, 0.1, 0.6, 0.1, 0.05, 0.05), outputs).first)
        val (label, conf) = waterLabelFromProbs(doubleArrayOf(0.6, 0.1, 0.05, 0.15, 0.05, 0.05), outputs)
        assertEquals("ok", label)
        assertEquals(0.85, conf, 1e-9) // 1 − P(water_low) − P(water_high)
    }

    @Test
    fun softmaxSumsToOneAndKeepsOrder() {
        val p = softmax(floatArrayOf(1f, 3f, 2f))
        assertEquals(1.0, p.sum(), 1e-9)
        assertTrue(p[1] > p[2] && p[2] > p[0])
        // 큰 로짓에서도 overflow 없음
        assertEquals(1.0, softmax(floatArrayOf(1000f, 999f)).sum(), 1e-9)
    }

    // ---------- 판독 계획 ----------

    private val enabled = mapOf(
        "p1" to setOf(DetectorIds.HEALTH, DetectorIds.PEST),
        "p2" to setOf(DetectorIds.WATERING)
    )

    @Test
    fun periodicRunsEveryEnabledModule() {
        val plan = DetectorPlanner().plan(0, enabled, periodic = true)
        assertEquals(mapOf(DetectorIds.HEALTH to "periodic", DetectorIds.PEST to "periodic"), plan["p1"])
        assertEquals(mapOf(DetectorIds.WATERING to "periodic"), plan["p2"])
    }

    @Test
    fun eventRunsOnlyRequestedPlantAndEnabledModules() {
        val pl = DetectorPlanner()
        // p1 에는 관수 모듈이 꺼져 있다 → 해충만
        pl.request(Triggers.HEALTH_DROP, "p1", setOf(DetectorIds.PEST, DetectorIds.WATERING), 100)
        assertTrue("기한 전에는 아무것도 안 돈다", pl.plan(50, enabled, periodic = false).isEmpty())
        val plan = pl.plan(100, enabled, periodic = false)
        assertEquals(mapOf("p1" to mapOf(DetectorIds.PEST to Triggers.HEALTH_DROP)), plan)
        assertEquals(0, pl.pendingCount())
    }

    @Test
    fun eventTriggerOverridesPeriodicAndNullPlantMeansAll() {
        val pl = DetectorPlanner()
        pl.request(Triggers.RUN_NOW, null, null, 0)
        val plan = pl.plan(10, enabled, periodic = true)
        assertEquals(Triggers.RUN_NOW, plan["p1"]?.get(DetectorIds.HEALTH))
        assertEquals(Triggers.RUN_NOW, plan["p2"]?.get(DetectorIds.WATERING))
    }

    @Test
    fun requestsForDisabledOrUnknownPlantsAreDropped() {
        val pl = DetectorPlanner()
        pl.request(Triggers.MODULE_ADDED, "ghost", null, 0)
        assertTrue(pl.plan(0, enabled, periodic = false).isEmpty())
        assertEquals(0, pl.pendingCount())
    }

    @Test
    fun waitIsTheEarlierOfPeriodAndNextRequest() {
        val pl = DetectorPlanner()
        assertEquals(20_000L, pl.waitMs(now = 0, lastPeriodicAt = 0, periodMs = 20_000))
        pl.request(Triggers.WATERED_FOLLOWUP, "p2", null, 5_000)
        assertEquals(5_000L, pl.waitMs(now = 0, lastPeriodicAt = 0, periodMs = 20_000))
        assertEquals(0L, pl.waitMs(now = 30_000, lastPeriodicAt = 0, periodMs = 20_000))
    }

    @Test
    fun requeueKeepsOnlyEventTriggers() {
        val pl = DetectorPlanner()
        pl.requeue(mapOf("p1" to mapOf(DetectorIds.HEALTH to "periodic", DetectorIds.PEST to Triggers.RUN_NOW)), 1_000)
        assertEquals(1, pl.pendingCount())
        assertEquals(mapOf("p1" to mapOf(DetectorIds.PEST to Triggers.RUN_NOW)), pl.plan(1_000, enabled, periodic = false))
    }

    @Test
    fun duplicateRequestsCollapse() {
        val pl = DetectorPlanner()
        repeat(5) { pl.request(Triggers.RUN_NOW, "p1", null, 1_000L + it) }
        assertEquals(1, pl.pendingCount())
    }

    // ---------- 결과 업로드 스로틀 ----------

    @Test
    fun reportGateThrottlesSameLabelOnly() {
        val g = ReportGate(minIntervalMs = 300_000)
        assertTrue(g.shouldReport("p", "pest", "none", "periodic", 0))
        g.markReported("p", "pest", "none", 0)
        assertFalse(g.shouldReport("p", "pest", "none", "periodic", 60_000))
        assertTrue("라벨이 바뀌면 즉시", g.shouldReport("p", "pest", "light", "periodic", 60_000))
        assertTrue("이벤트 계기면 즉시", g.shouldReport("p", "pest", "none", Triggers.RUN_NOW, 60_000))
        assertTrue("5분 지나면 다시", g.shouldReport("p", "pest", "none", "periodic", 300_000))
    }

    // ---------- 관수 주기 ----------

    @Test
    fun cycleIsTimeFromWateringToConfirmedDry() {
        val t = WateringCycleTracker(confirmCycles = 2)
        t.onWatered("p", 0)
        t.observe("p", "ok", 2 * hour)
        t.observe("p", "ok", 3 * hour)
        val first = t.observe("p", "dry", 26 * hour)
        assertFalse("한 번 관측으로는 확정하지 않는다", first.becameDry)
        val second = t.observe("p", "dry", 26 * hour + 60_000)
        assertTrue(second.becameDry)
        assertEquals(1, second.cycles)
        assertEquals(26.0, second.avgDryHours!!, 0.05)

        // 두 번째 주기 30h → 평균 28h, 다음 관수까지 = 28 − 경과
        t.onWatered("p", 30 * hour)
        t.observe("p", "dry", 60 * hour)
        val s = t.observe("p", "dry", 60 * hour)
        assertEquals(2, s.cycles)
        assertEquals(28.0, s.avgDryHours!!, 0.05)
        val snap = t.snapshot("p", 40 * hour)!!
        assertEquals(10.0, snap.hoursSinceWater!!, 1e-9)
        assertEquals(18.0, snap.nextInHours!!, 0.05)
    }

    @Test
    fun dryRightAfterWateringIsNotACycle() {
        val t = WateringCycleTracker(confirmCycles = 2, graceMs = hour)
        t.onWatered("p", 0)
        // 관수 직후 잎이 아직 처져 있어도 주기로 잡지 않는다
        assertFalse(t.observe("p", "dry", 60_000).becameDry)
        assertFalse(t.observe("p", "dry", 120_000).becameDry)
        assertEquals("dry", t.observe("p", "dry", 180_000).label) // 관측 자체는 그대로 보고
        // 유예가 지난 뒤의 dry 확정은 주기로 기록
        t.observe("p", "dry", 5 * hour)
        val s = t.observe("p", "dry", 5 * hour + 60_000)
        assertTrue(s.becameDry)
        assertEquals(5.0, s.avgDryHours!!, 0.05)
    }

    @Test
    fun flickerDoesNotConfirm() {
        val t = WateringCycleTracker(confirmCycles = 2)
        t.onWatered("p", 0)
        t.observe("p", "dry", 10 * hour)
        t.observe("p", "ok", 10 * hour + 1)
        val s = t.observe("p", "dry", 10 * hour + 2)
        assertFalse(s.becameDry)
        assertEquals(0, s.cycles)
    }

    @Test
    fun withoutWateringRecordNoCycleButDryStillConfirms() {
        val t = WateringCycleTracker(confirmCycles = 2)
        t.observe("p", "dry", 0)
        val s = t.observe("p", "dry", 1)
        assertTrue(s.becameDry)
        assertEquals(0, s.cycles)
        assertNull(s.hoursSinceWater)
        assertNull(s.nextInHours)
    }

    @Test
    fun keepsOnlyRecentCyclesAndSurvivesRestore() {
        val t = WateringCycleTracker(confirmCycles = 1, keep = 3)
        var now = 0L
        for (h in listOf(10, 20, 30, 40)) {
            t.onWatered("p", now)
            now += h * hour
            t.observe("p", "dry", now)
        }
        val s = t.snapshot("p", now)!!
        assertEquals(3, s.cycles)
        assertEquals(30.0, s.avgDryHours!!, 0.05) // 20, 30, 40

        val restored = WateringCycleTracker(confirmCycles = 1, keep = 3)
        restored.restore(t.encode())
        assertEquals(30.0, restored.snapshot("p", now)!!.avgDryHours!!, 0.05)
        assertFalse(t.dirty)
    }
}
