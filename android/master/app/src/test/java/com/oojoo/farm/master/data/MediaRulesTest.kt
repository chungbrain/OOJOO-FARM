package com.oojoo.farm.master.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaRulesTest {

    @Test
    fun kindOf_photoAnyImage_videoOnlyServerFormats() {
        assertEquals(MediaKind.PHOTO, MediaRules.kindOf("image/jpeg"))
        assertEquals(MediaKind.PHOTO, MediaRules.kindOf("image/heic")) // JPEG 로 다시 인코딩해 올린다
        assertEquals(MediaKind.VIDEO, MediaRules.kindOf("video/mp4"))
        assertEquals(MediaKind.VIDEO, MediaRules.kindOf("video/quicktime"))
        assertNull(MediaRules.kindOf("video/x-msvideo")) // 서버가 받지 않는 형식
        assertNull(MediaRules.kindOf("application/pdf"))
        assertNull(MediaRules.kindOf(null))
    }

    @Test
    fun problemOf_flagsUnsupportedAndOversizedVideo() {
        assertEquals(MediaProblem.UNSUPPORTED, MediaRules.problemOf(null, 10))
        assertEquals(MediaProblem.TOO_LARGE, MediaRules.problemOf(MediaKind.VIDEO, MediaRules.MAX_VIDEO_BYTES + 1))
        assertNull(MediaRules.problemOf(MediaKind.VIDEO, MediaRules.MAX_VIDEO_BYTES))
        assertNull(MediaRules.problemOf(MediaKind.VIDEO, null)) // 크기를 모르면 서버가 판단
        assertNull(MediaRules.problemOf(MediaKind.PHOTO, 500L * 1024 * 1024)) // 사진은 줄여서 올린다
    }

    @Test
    fun sampleSize_keepsLongEdgeAtLeastTarget() {
        assertEquals(1, MediaRules.sampleSizeFor(1920, 1080, 1920))
        assertEquals(1, MediaRules.sampleSizeFor(3000, 2000, 1920))
        assertEquals(2, MediaRules.sampleSizeFor(4000, 3000, 1920))
        assertEquals(4, MediaRules.sampleSizeFor(3000, 8000, 1920))
        assertEquals(8, MediaRules.sampleSizeFor(4000, 3000, 480))
        assertEquals(1, MediaRules.sampleSizeFor(100, 50, 480))
    }

    @Test
    fun fitWithin_scalesLongEdgeKeepingRatio() {
        assertEquals(1920 to 1440, MediaRules.fitWithin(4000, 3000, 1920))
        assertEquals(1080 to 1920, MediaRules.fitWithin(2160, 3840, 1920))
        assertEquals(800 to 600, MediaRules.fitWithin(800, 600, 1920))
        assertEquals(480 to 1, MediaRules.fitWithin(10000, 10, 480))
    }

    @Test
    fun displaySize_swapsForQuarterTurns() {
        assertEquals(1080 to 1920, MediaRules.displaySize(1920, 1080, 90))
        assertEquals(1080 to 1920, MediaRules.displaySize(1920, 1080, 270))
        assertEquals(1920 to 1080, MediaRules.displaySize(1920, 1080, 180))
        assertEquals(1920 to 1080, MediaRules.displaySize(1920, 1080, 0))
    }

    @Test
    fun formatDuration_roundsToSeconds() {
        assertEquals("0:00", MediaRules.formatDuration(0))
        assertEquals("0:07", MediaRules.formatDuration(6_600))
        assertEquals("1:05", MediaRules.formatDuration(65_000))
        assertEquals("1:02:03", MediaRules.formatDuration(3_723_000))
        assertEquals("0:00", MediaRules.formatDuration(-5))
    }

    @Test
    fun uploadFileName_usesServerExtension() {
        assertEquals("photo_1.jpg", MediaRules.uploadFileName(MediaKind.PHOTO, "image/png", 0))
        assertEquals("video_3.mov", MediaRules.uploadFileName(MediaKind.VIDEO, "video/quicktime", 2))
        assertEquals("video_1.mp4", MediaRules.uploadFileName(MediaKind.VIDEO, "video/unknown", 0))
    }
}
