package com.retroid.translator.engine

import android.content.Context

/**
 * The user's preferred spoken-voice gender, shared across Translate,
 * Conversations, and Practice (one global setting rather than three
 * independent ones - picking "Male" on one tab means every tab speaks in
 * a male voice). Persisted via plain SharedPreferences since it's a single
 * small value; no need for anything heavier.
 */
object VoicePreferences {
    private const val PREFS = "voice_prefs"
    private const val KEY_GENDER = "gender"

    fun getGender(context: Context): VoiceGender {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_GENDER, VoiceGender.FEMALE.name)
        return try { VoiceGender.valueOf(raw ?: VoiceGender.FEMALE.name) } catch (e: IllegalArgumentException) { VoiceGender.FEMALE }
    }

    fun setGender(context: Context, gender: VoiceGender) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_GENDER, gender.name)
            .apply()
    }
}
