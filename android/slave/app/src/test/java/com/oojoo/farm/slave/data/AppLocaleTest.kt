package com.oojoo.farm.slave.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLocaleTest {
    @Test
    fun normalizeLegacyLanguage_defaultsUnsupportedValuesToKorean() {
        assertEquals(AppLocale.KOREAN, AppLocale.normalizeLegacyLanguage("system"))
        assertEquals(AppLocale.KOREAN, AppLocale.normalizeLegacyLanguage(""))
        assertEquals(AppLocale.KOREAN, AppLocale.normalizeLegacyLanguage("fr"))
        assertEquals(AppLocale.KOREAN, AppLocale.normalizeLegacyLanguage(null))
    }

    @Test
    fun normalizeLegacyLanguage_preservesSupportedLanguages() {
        assertEquals(AppLocale.KOREAN, AppLocale.normalizeLegacyLanguage("ko"))
        assertEquals(AppLocale.ENGLISH, AppLocale.normalizeLegacyLanguage("en"))
    }

    @Test
    fun resolveLanguageMigration_persistsFreshAndLegacyValuesExactlyOnce() {
        val fresh = AppLocale.resolveLanguageMigration(null, alreadyMigrated = false)
        assertEquals(AppLocale.KOREAN, fresh.language)
        assertTrue(fresh.shouldPersist)

        val legacySystem = AppLocale.resolveLanguageMigration("system", alreadyMigrated = false)
        assertEquals(AppLocale.KOREAN, legacySystem.language)
        assertTrue(legacySystem.shouldPersist)

        val migratedEnglish = AppLocale.resolveLanguageMigration("en", alreadyMigrated = true)
        assertEquals(AppLocale.ENGLISH, migratedEnglish.language)
        assertFalse(migratedEnglish.shouldPersist)
    }
}
