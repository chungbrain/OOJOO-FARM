package com.oojoo.farm.master.ui

import com.oojoo.farm.master.model.FarmEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class EventStatsTest {
    private val seoul = ZoneId.of("Asia/Seoul")
    private val today = LocalDate.of(2026, 11, 26)
    private var seq = 0
    private fun ev(type: String, utc: String) = FarmEvent(id = "e${seq++}", slave_id = "s", type = type, created_at = utc)

    @Test
    fun bucketsByLocalDayNotUtcDay() {
        // 2026-11-25 16:30 UTC = 2026-11-26 01:30 KST → '오늘' 칸
        val s = computeEventStats(listOf(ev("auto_water", "2026-11-25 16:30:00")), today, 14, true, seoul)
        assertEquals(14, s.days.size)
        assertEquals(today, s.days.last().date)
        assertEquals(1, s.days.last().total)
        assertEquals(0, s.days[12].total)
    }

    @Test
    fun groupsAndRanksTypes() {
        val events = listOf(
            ev("pest_detected", "2026-11-26 00:00:00"),
            ev("pest_detected", "2026-11-25 03:00:00"),
            ev("pest_control", "2026-11-25 03:05:00"),
            ev("water_low", "2026-11-24 03:00:00"),
            ev("battery_low", "2026-11-20 03:00:00"),
            ev("unknown_new_type", "2026-11-20 04:00:00"),
        )
        val s = computeEventStats(events, today, 14, true, seoul)
        assertEquals(listOf(1, 3, 2), s.byGroup) // WATER, PEST, GROWTH(+알 수 없는 유형)
        assertEquals(6, s.total)
        assertEquals("pest_detected" to 2, s.byType.first())
        assertFalse(s.partial)
    }

    @Test
    fun dropsEventsOutsideWindow() {
        val events = listOf(
            ev("auto_water", "2026-11-26 00:00:00"),
            ev("auto_water", "2026-11-01 00:00:00"), // 14일 창 밖
        )
        val s = computeEventStats(events, today, 14, true, seoul)
        assertEquals(1, s.total)
        assertEquals(LocalDate.of(2026, 11, 13), s.days.first().date)
    }

    @Test
    fun incompleteDataShrinksWindowToOldestEvent() {
        // 구버전 서버(최신 50건만): 가장 오래된 이벤트가 11-24 → 그 앞 날짜는 '0건'으로 그리지 않음
        val events = listOf(
            ev("auto_water", "2026-11-26 00:00:00"),
            ev("auto_water", "2026-11-24 05:00:00"),
        )
        val s = computeEventStats(events, today, 14, false, seoul)
        assertEquals(3, s.days.size)
        assertEquals(LocalDate.of(2026, 11, 24), s.days.first().date)
        assertTrue(s.partial)
    }

    @Test
    fun incompleteButCoveringWholeWindowIsNotPartial() {
        val events = listOf(ev("auto_water", "2026-11-26 00:00:00"), ev("auto_water", "2026-10-01 00:00:00"))
        val s = computeEventStats(events, today, 14, false, seoul)
        assertEquals(14, s.days.size)
        assertFalse(s.partial)
    }

    @Test
    fun axisMaxIsEvenAndCoversMax() {
        assertEquals(2, niceAxisMax(0))
        assertEquals(2, niceAxisMax(1))
        assertEquals(4, niceAxisMax(3))
        assertEquals(16, niceAxisMax(13))
        assertEquals(50, niceAxisMax(45))
        assertEquals(1200, niceAxisMax(1001))
    }
}
