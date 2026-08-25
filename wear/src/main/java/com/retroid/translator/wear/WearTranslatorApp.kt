package com.retroid.translator.wear

import android.app.Application
import com.retroid.translator.wear.diagnostics.WearDiag

/**
 * Mirrors the phone app's [com.retroid.translator.TranslatorApp] role (a
 * plain Application subclass, no shared engines eagerly created here yet -
 * unlike the phone app, which lazily builds one shared EspeakEngine on
 * first access). Kept intentionally minimal for this pass: engines are
 * constructed directly by MainActivity/TranslateViewModel, not hung off
 * this class, since :wear has exactly one screen so far.
 */
class WearTranslatorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Installed first, before anything else gets a chance to throw -
        // see WearDiag's own doc comment for why this is a single combined
        // object rather than the phone app's CrashHandler+Diag split
        // (docs/specs/engineering-systems-pitch.md system #5).
        WearDiag.install(this)
    }
}
