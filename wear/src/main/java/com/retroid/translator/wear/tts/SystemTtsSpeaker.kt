package com.retroid.translator.wear.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.UUID

/**
 * Fallback speech output for :wear, used when [com.retroid.translator.wear.tts.WearEspeakEngine]
 * (the app's own offline eSpeak NG voice, preferred - see that class for why
 * an armeabi-v7a eSpeak build turned out to be tractable after all) isn't
 * ready yet or doesn't cover a given language. Historically this was
 * `:wear`'s *only* TTS path - see the now-superseded reasoning in
 * `WearEspeakEngine`'s own doc comment for what changed and why. This class
 * itself is unmodified: wraps the platform [TextToSpeech] API, confirmed
 * present and usable on the real device via `com.google.android.tts` being
 * installed (`adb shell pm list packages`, see spec), as an honest,
 * disclosed fallback rather than a silent feature downgrade.
 */
class SystemTtsSpeaker(context: Context) {
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private var pendingInitError: String? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (!ready) pendingInitError = "TextToSpeech init failed (status=$status)"
        }
    }

    fun speak(text: String, langCode: String, onDone: () -> Unit, onError: (String) -> Unit) {
        val engine = tts
        if (engine == null || !ready) {
            onError(pendingInitError ?: "TextToSpeech not ready yet")
            return
        }
        val locale = Locale.forLanguageTag(langCode)
        val result = engine.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "TTS locale $langCode not directly supported (result=$result), speaking anyway with engine default")
        }
        val utteranceId = UUID.randomUUID().toString()
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { onDone() }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { onError("TTS playback error") }
            override fun onError(utteranceId: String?, errorCode: Int) { onError("TTS playback error (code=$errorCode)") }
        })
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }

    companion object {
        private const val TAG = "SystemTtsSpeaker"
    }
}
