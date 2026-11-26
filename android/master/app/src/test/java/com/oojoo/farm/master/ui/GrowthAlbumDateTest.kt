package com.oojoo.farm.master.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class GrowthAlbumDateTest {
    @Test
    fun parsesFarmerTakenAtFormat() {
        // Farmer: SimpleDateFormat("yyyy-MM-dd HH:mm")
        assertEquals(LocalDateTime.of(2026, 11, 25, 14, 30), parsePhotoDateTime("2026-11-25 14:30"))
    }

    @Test
    fun parsesServerTimestampAndIso() {
        // SQLite datetime('now') / ISO
        assertEquals(LocalDateTime.of(2026, 11, 25, 5, 7), parsePhotoDateTime("2026-11-25 05:07:41"))
        assertEquals(LocalDateTime.of(2026, 11, 25, 5, 7), parsePhotoDateTime("2026-11-25T05:07:41Z"))
    }

    @Test
    fun parsesDateOnlyPlantedAt() {
        assertEquals(LocalDateTime.of(2026, 3, 5, 0, 0), parsePhotoDateTime("2026-3-5"))
    }

    @Test
    fun rejectsMissingOrInvalid() {
        assertNull(parsePhotoDateTime(null))
        assertNull(parsePhotoDateTime(""))
        assertNull(parsePhotoDateTime("어제"))
        assertNull(parsePhotoDateTime("2026-13-40 10:00"))
    }
}
