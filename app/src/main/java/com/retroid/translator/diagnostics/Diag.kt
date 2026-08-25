package com.retroid.translator.diagnostics

import android.content.Context
import android.util.Log

/**
 * Drop-in replacement for `Log.e(tag, msg[, tr])` / `Log.w(tag, msg[, tr])`
 * call sites: same signature shape, still writes to logcat exactly as
 * before, but additionally persists the event to [DiagnosticsStore] so it
 * survives past the current logcat ring buffer
 * (docs/specs/engineering-systems-pitch.md system #5).
 *
 * [init] must be called once, before any [e]/[w] call, from
 * [com.retroid.translator.TranslatorApp.onCreate] (or the wear module's
 * smaller equivalent). Until then [e]/[w] silently fall back to plain
 * `Log.e`/`Log.w` with no persistence - this keeps call sites simple (no
 * nullable-store plumbing) while still being safe to call from a class
 * whose singleton happens to construct before `Application.onCreate` runs.
 */
object Diag {
    @Volatile
    private var store: DiagnosticsStore? = null

    fun init(context: Context) {
        if (store != null) return
        store = DiagnosticsStore(context.applicationContext)
    }

    fun e(tag: String, msg: String, tr: Throwable? = null): Int {
        val result = if (tr != null) Log.e(tag, msg, tr) else Log.e(tag, msg)
        store?.append('E', tag, msg, tr)
        return result
    }

    fun w(tag: String, msg: String, tr: Throwable? = null): Int {
        val result = if (tr != null) Log.w(tag, msg, tr) else Log.w(tag, msg)
        store?.append('W', tag, msg, tr)
        return result
    }
}
