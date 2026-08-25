package com.retroid.translator.diagnostics

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * Local-only, queryable store for diagnostic events (warnings, errors, and
 * folded-in crash records - see [CrashHandler]) - plain SQLiteOpenHelper,
 * matching [com.retroid.translator.learn.LearnProgressStore]'s exact
 * pattern (no new dependency, no Room, `writableDatabase`/`readableDatabase`
 * never wrapped in `.use {}` themselves - only the `Cursor` results are -
 * since `SQLiteOpenHelper` caches and owns that connection for the helper's
 * whole lifetime; closing it early would break every later call).
 *
 * Capped at [MAX_EVENTS] most-recent rows on every insert, so a
 * crash-looping device can't grow this file unbounded
 * (docs/specs/engineering-systems-pitch.md system #5).
 */
class DiagnosticsStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE diagnostic_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts_epoch_ms INTEGER NOT NULL,
                level TEXT NOT NULL,
                tag TEXT NOT NULL,
                message TEXT NOT NULL,
                stack_trace TEXT
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS diagnostic_events")
        onCreate(db)
    }

    /**
     * Wrapped in its own try/catch, deliberately: this is called from
     * inside [Diag]'s facade, which mirrors ordinary `Log.e`/`Log.w` call
     * sites - a persistence failure here must never throw into a caller
     * that is very often already inside its own catch block logging a
     * real, unrelated error.
     */
    fun append(level: Char, tag: String, message: String, throwable: Throwable?) {
        try {
            val db = writableDatabase
            db.insert("diagnostic_events", null, ContentValues().apply {
                put("ts_epoch_ms", System.currentTimeMillis())
                put("level", level.toString())
                put("tag", tag)
                put("message", message)
                put("stack_trace", throwable?.let { Log.getStackTraceString(it) })
            })
            db.execSQL(
                "DELETE FROM diagnostic_events WHERE id NOT IN " +
                    "(SELECT id FROM diagnostic_events ORDER BY id DESC LIMIT $MAX_EVENTS)"
            )
        } catch (e: Exception) {
            Log.w(TAG, "DiagnosticsStore.append failed (non-fatal, not re-logged via Diag to avoid recursion)", e)
        }
    }

    data class Event(
        val id: Long,
        val tsEpochMs: Long,
        val level: String,
        val tag: String,
        val message: String,
        val stackTrace: String?
    )

    /** Newest first, capped at [limit]. Empty list (not a thrown exception) on any read failure. */
    fun recent(limit: Int = MAX_EVENTS): List<Event> {
        val out = mutableListOf<Event>()
        try {
            readableDatabase.rawQuery(
                "SELECT id, ts_epoch_ms, level, tag, message, stack_trace FROM diagnostic_events ORDER BY id DESC LIMIT ?",
                arrayOf(limit.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    out.add(
                        Event(
                            id = c.getLong(0),
                            tsEpochMs = c.getLong(1),
                            level = c.getString(2),
                            tag = c.getString(3),
                            message = c.getString(4),
                            stackTrace = c.getString(5)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "DiagnosticsStore.recent failed (non-fatal)", e)
        }
        return out
    }

    /** Real count for the viewer's "0 events" vs. a real list distinction, without loading every row. */
    fun count(): Int {
        return try {
            readableDatabase.rawQuery("SELECT COUNT(*) FROM diagnostic_events", null).use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0
            }
        } catch (e: Exception) {
            0
        }
    }

    /** User-initiated wipe (DiagnosticsFragment's "Clear log" button) - not called from anywhere else. */
    fun clear() {
        try {
            writableDatabase.execSQL("DELETE FROM diagnostic_events")
        } catch (e: Exception) {
            Log.w(TAG, "DiagnosticsStore.clear failed (non-fatal)", e)
        }
    }

    companion object {
        private const val TAG = "DiagnosticsStore"
        private const val DB_NAME = "diagnostics.db"
        private const val DB_VERSION = 1
        const val MAX_EVENTS = 200
    }
}
