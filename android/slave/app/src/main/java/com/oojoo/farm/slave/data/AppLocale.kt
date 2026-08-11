package com.oojoo.farm.slave.data

import android.content.Context
import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

object AppLocale {
    const val KOREAN = "ko"
    const val ENGLISH = "en"

    data class LanguageMigration(
        val language: String,
        val shouldPersist: Boolean,
    )

    fun normalizeLegacyLanguage(value: String?): String =
        if (value == ENGLISH) ENGLISH else KOREAN

    fun resolveLanguageMigration(value: String?, alreadyMigrated: Boolean): LanguageMigration =
        LanguageMigration(
            language = normalizeLegacyLanguage(value),
            shouldPersist = !alreadyMigrated,
        )

    fun initialize(context: Context) {
        apply(context, Prefs.language(context))
    }

    fun setLanguage(context: Context, language: String) {
        val normalized = normalizeLegacyLanguage(language)
        Prefs.setLanguage(context, normalized)
        apply(context, normalized)
    }

    private fun apply(context: Context, language: String) {
        val normalized = normalizeLegacyLanguage(language)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java).applicationLocales =
                LocaleList.forLanguageTags(normalized)
        } else {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(normalized))
        }
    }
}
