package com.retroid.translator.diagnostics

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Crash-safe capture: wraps (never replaces) the existing default
 * uncaught-exception handler - the OS's own "App has stopped" dialog and
 * process-death semantics are completely unchanged by this class, it only
 * adds one capture step before the chain continues exactly as it already
 * did (docs/specs/engineering-systems-pitch.md system #5).
 *
 * Deliberately does NOT touch [DiagnosticsStore]/SQLite here - a crashing
 * JVM is the worst possible place to run a database transaction (real
 * re-entrancy risk if the crash itself involves storage/IO exhaustion).
 * Instead this writes one minimal record via a plain, swallowed-on-failure
 * [FileOutputStream] append, and [reconcile] folds it into the real store
 * on the *next* clean launch - see that function's own doc comment.
 */
object CrashHandler {
    private const val TAG = "CrashHandler"
    private const val CRASH_FILE_NAME = "pending_crash.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrashRecord(appContext, thread, throwable)
            } catch (writeFailure: Throwable) {
                // Never let diagnostics capture itself mask or worsen the
                // real crash - swallow unconditionally, including Errors.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun crashFile(context: Context): File = File(diagnosticsDir(context), CRASH_FILE_NAME)

    internal fun diagnosticsDir(context: Context): File = File(context.filesDir, "diagnostics")

    private fun writeCrashRecord(context: Context, thread: Thread, t: Throwable) {
        val dir = diagnosticsDir(context)
        dir.mkdirs()
        FileOutputStream(crashFile(context)).use { out ->
            out.write(
                buildString {
                    appendLine("ts_epoch_ms=${System.currentTimeMillis()}")
                    appendLine("thread=${thread.name}")
                    appendLine("device=${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT})")
                    appendLine("stack_trace=")
                    append(Log.getStackTraceString(t))
                }.toByteArray()
            )
        }
    }

    /**
     * Called once from [com.retroid.translator.TranslatorApp.onCreate] on
     * every launch: if last run left a pending crash record, fold it into
     * [store] as one FATAL-level event and delete the flat file. This is
     * the only bridge between the crash-time flat-file tier and the normal-
     * runtime SQLite tier - by the time this runs, the process is healthy
     * again, so it's safe to touch the database here even though it wasn't
     * safe to at the moment of the actual crash.
     */
    fun reconcile(context: Context, store: DiagnosticsStore) {
        val file = crashFile(context)
        if (!file.exists()) return
        try {
            val text = file.readText()
            store.append('F', TAG, "App crashed last run - see stack trace", null)
            // The full record (device info + stack trace) is the message
            // body itself, not squeezed into DiagnosticsStore's stack_trace
            // column alone - append re-parses nothing, it's stored as one
            // FATAL row with this raw text as its message.
            store.append('F', "$TAG.crash", text, null)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to reconcile pending crash record (non-fatal)", e)
        } finally {
            file.delete()
        }
    }
}
