package com.retroid.translator

import android.app.Application
import com.retroid.translator.audio.MicPipeline
import com.retroid.translator.engine.EspeakEngine
import com.retroid.translator.engine.VoskEngine

/**
 * Holds the app-wide singletons for the offline engines. These wrap native
 * resources (loaded models, an AudioTrack, JNI state) that are expensive to
 * set up and must not be duplicated per-screen, so every Fragment reaches
 * them through this Application instance rather than constructing their own.
 */
class TranslatorApp : Application() {
    val espeak: EspeakEngine by lazy { EspeakEngine(this) }
    val vosk: VoskEngine by lazy { VoskEngine(this) }
    val mic: MicPipeline by lazy { MicPipeline() }

    override fun onCreate() {
        super.onCreate()
        // Kick off eSpeak init in the background right away so it's usually
        // ready before the user reaches for the speak button.
        espeak.initAsync { }
    }
}
