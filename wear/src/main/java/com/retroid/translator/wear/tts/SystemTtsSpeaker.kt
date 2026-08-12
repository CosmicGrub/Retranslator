package com.retroid.translator.wear.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.UUID

/**
 * Speech output for :wear, NOT a port of the phone app's [TtsRouter]/
 * [EspeakEngine]/[PiperTtsEngine] stack. Those are vendored as prebuilt
 * arm64-v8a-only `.so` files with no in-repo build recipe - and this pass's
 * real-device finding (docs/specs/watch6-classic-adaptation.md) is that the
 * real Watch6 Classic is 32-bit-ARM-only, so those exact binaries could
 * never load on it regardless of how they were wired in. Building
 * armeabi-v7a versions of eSpeak-ng/sherpa-onnx/onnxruntime from source is a
 * real, scoped-out-of-this-pass undertaking (see spec's recommended
 * follow-up work).
 *
 * This wraps the platform [TextToSpeech] API instead - confirmed present and
 * usable on the real device via `com.google.android.tts` being installed
 * (`adb shell pm list packages`, see spec) - as an honest, disclosed stand-in
 * for the phone app's own higher-quality offline engines, not a silent
 * feature downgrade.
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
