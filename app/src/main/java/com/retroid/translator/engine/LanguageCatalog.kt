package com.retroid.translator.engine

import com.google.mlkit.nl.translate.TranslateLanguage
import java.util.Locale

/** Shared full ML Kit language list + display names, used by every screen's language pickers. */
object LanguageCatalog {
    val codes: List<String> by lazy {
        TranslateLanguage.getAllLanguages().sortedBy { displayNameFor(it) }
    }

    fun displayNameFor(code: String): String {
        val locale = Locale.forLanguageTag(code)
        val name = locale.getDisplayName(Locale.ENGLISH)
        return if (name.isNotBlank()) name.replaceFirstChar { it.uppercase() } else code
    }
}
