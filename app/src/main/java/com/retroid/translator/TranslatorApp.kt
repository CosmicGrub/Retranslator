package com.retroid.translator

import android.app.Application
import com.retroid.translator.audio.MicPipeline
import com.retroid.translator.diagnostics.CrashHandler
import com.retroid.translator.diagnostics.Diag
import com.retroid.translator.diagnostics.DiagnosticsStore
import com.retroid.translator.engine.DownloadManager
import com.retroid.translator.engine.EspeakEngine
import com.retroid.translator.engine.PiperTtsEngine
import com.retroid.translator.engine.TtsRouter
import com.retroid.translator.engine.VoskEngine
import com.retroid.translator.learn.LearnProgressStore

/**
 * Holds the app-wide singletons for the offline engines. These wrap native
 * resources (loaded models, an AudioTrack, JNI state) that are expensive to
 * set up and must not be duplicated per-screen, so every Fragment reaches
 * them through this Application instance rather than constructing their own.
 */
class TranslatorApp : Application() {
    val espeak: EspeakEngine by lazy { EspeakEngine(this) }
    val piper: PiperTtsEngine by lazy { PiperTtsEngine(this) }
    val vosk: VoskEngine by lazy { VoskEngine(this) }
    val mic: MicPipeline by lazy { MicPipeline() }

    /** Every screen speaks through this - it picks Piper (natural) when downloaded, else eSpeak. */
    val tts: TtsRouter by lazy { TtsRouter(espeak, piper) }

    /** Local-only XP/streak/lesson-completion/SRS state for the Learn tab. */
    val learnProgress: LearnProgressStore by lazy { LearnProgressStore(this) }

    /** Local-only crash/error journal - see docs/specs/engineering-systems-pitch.md system #5. */
    val diagnostics: DiagnosticsStore by lazy { DiagnosticsStore(this) }

    override fun onCreate() {
        super.onCreate()
        // Installed first, before anything else gets a chance to throw -
        // wraps (doesn't replace) the platform's own uncaught-exception
        // handler, so crash/ANR reporting behavior is unchanged.
        CrashHandler.install(this)
        Diag.init(this)
        // Fold any crash record left by the *previous* process into the
        // real store now that we're back in a healthy, non-crashing state.
        CrashHandler.reconcile(this, diagnostics)
        // Kick off eSpeak init in the background right away so it's usually
        // ready before the user reaches for the speak button.
        espeak.initAsync { }
        // Storage hygiene, not a resume-related correctness step - abandoned
        // partial-download checkpoints older than a week get swept once per
        // process lifetime (docs/specs/engineering-systems-pitch.md system #4).
        DownloadManager.pruneStaleResumeCheckpoints(this)
    }
}
