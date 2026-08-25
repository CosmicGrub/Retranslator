package com.retroid.translator.wear.diagnostics

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Wear OS's own local-only diagnostics journal - deliberately much simpler
 * than the phone app's com.retroid.translator.diagnostics.DiagnosticsStore:
 * :wear has no dependency on :app or :core (it's a fully separate Android
 * application module - see wear/build.gradle.kts), and there's no planned
 * in-watch viewer UI for this (docs/specs/engineering-systems-pitch.md
 * system #5 scopes the queryable-viewer/share-flow work to the phone app
 * only). A flat, size-capped append-only text file stands in for SQLite
 * here, so no new Gradle dependency (Room or otherwise) is needed just for
 * this.
 *
 * Combines both roles the phone side splits across two classes
 * ([com.retroid.translator.diagnostics.CrashHandler] +
 * [com.retroid.translator.diagnostics.Diag]) into one object, since there's
 * no SQLite reconcile step here to keep separate - a crash record is just
 * another appended line.
 */
object WearDiag {
    private const val TAG = "WearDiag"
    private const val LOG_FILE_NAME = "wear_diagnostics.log"

    // A flat rolling text log with no viewer UI only needs to cover "the
    // last little while" for someone pulling it via adb - 64KB is plenty
    // and keeps the worst-case append/truncate cost trivial.
    private const val MAX_LOG_BYTES = 64 * 1024L

    private lateinit var appContext: Context
    private val lock = Any()

    /** Wraps (never replaces) the existing default uncaught-exception handler. */
    fun install(context: Context) {
        appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                append('F', TAG, "Uncaught exception on ${thread.name}: ${throwable.message}", throwable)
            } catch (writeFailure: Throwable) {
                // Never let diagnostics capture itself mask or worsen the
                // real crash - swallow unconditionally, including Errors.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Drop-in replacement for `Log.e(tag, msg[, tr])` - see [append] for what changes. */
    fun e(tag: String, msg: String, tr: Throwable? = null): Int {
        val result = if (tr != null) Log.e(tag, msg, tr) else Log.e(tag, msg)
        append('E', tag, msg, tr)
        return result
    }

    /** Drop-in replacement for `Log.w(tag, msg[, tr])` - see [append] for what changes. */
    fun w(tag: String, msg: String, tr: Throwable? = null): Int {
        val result = if (tr != null) Log.w(tag, msg, tr) else Log.w(tag, msg)
        append('W', tag, msg, tr)
        return result
    }

    private fun append(level: Char, tag: String, msg: String, tr: Throwable?) {
        val ctx = if (::appContext.isInitialized) appContext else return
        synchronized(lock) {
            try {
                val file = logFile(ctx)
                file.parentFile?.mkdirs()
                file.appendText(
                    buildString {
                        append(System.currentTimeMillis())
                        append(' ').append(level).append('/').append(tag).append(": ").append(msg)
                        if (tr != null) {
                            append('\n').append(Log.getStackTraceString(tr))
                        }
                        append('\n')
                    }
                )
                if (file.length() > MAX_LOG_BYTES) {
                    // Cheap cap: keep only the tail half once the file gets
                    // big, rather than a crash-time-unsafe parse-and-
                    // truncate-by-line.
                    val tail = file.readText().takeLast((MAX_LOG_BYTES / 2).toInt())
                    file.writeText(tail)
                }
            } catch (e: Exception) {
                // Swallowed deliberately - see DiagnosticsStore.append's
                // identical reasoning: a persistence failure here must
                // never throw into a caller that is very often already
                // inside its own catch block logging a real, unrelated
                // error.
            }
        }
    }

    private fun logFile(context: Context) = File(File(context.filesDir, "diagnostics"), LOG_FILE_NAME)
}
