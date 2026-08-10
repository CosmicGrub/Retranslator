package com.retroid.translator.learn

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.LocalDate
import java.time.ZoneId

/**
 * Local-only persistence for the Learn tab: XP total, daily streak,
 * per-lesson completion, and a simple Leitner-box spaced-repetition state
 * per exercise. Plain SQLiteOpenHelper (built into Android, no new
 * dependency) rather than Room, matching this project's existing "reach
 * for the simplest built-in tool" pattern (e.g. [com.retroid.translator.audio.RecordingsStore]
 * uses plain files, not a database, for its simpler needs).
 *
 * Everything here is local and offline - no account, no cloud sync,
 * consistent with the rest of the app.
 */
class LearnProgressStore(context: Context) : SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE app_state (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE lesson_completion (
                lesson_key TEXT PRIMARY KEY,
                completed_at_epoch_ms INTEGER NOT NULL,
                xp_earned INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE srs_state (
                exercise_key TEXT PRIMARY KEY,
                box INTEGER NOT NULL DEFAULT 0,
                next_review_epoch_day INTEGER NOT NULL DEFAULT 0,
                correct_count INTEGER NOT NULL DEFAULT 0,
                incorrect_count INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS app_state")
        db.execSQL("DROP TABLE IF EXISTS lesson_completion")
        db.execSQL("DROP TABLE IF EXISTS srs_state")
        onCreate(db)
    }

    // ---------------------------------------------------------------------
    // XP
    // ---------------------------------------------------------------------

    fun totalXp(): Int = getState(KEY_XP)?.toIntOrNull() ?: 0

    fun addXp(amount: Int): Int {
        val newTotal = totalXp() + amount
        setState(KEY_XP, newTotal.toString())
        return newTotal
    }

    // ---------------------------------------------------------------------
    // Streak - date-based, no cloud/account needed
    // ---------------------------------------------------------------------

    /** Call once when the user completes at least one exercise "today". Returns the new streak count. */
    fun recordActivityToday(): Int {
        val today = LocalDate.now(ZoneId.systemDefault())
        val lastActive = getState(KEY_LAST_ACTIVE_DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val currentStreak = getState(KEY_STREAK)?.toIntOrNull() ?: 0
        val newStreak = when {
            lastActive == today -> currentStreak.coerceAtLeast(1) // already counted today
            lastActive == today.minusDays(1) -> currentStreak + 1 // consecutive day
            else -> 1 // streak broken (or first ever activity) - restart at 1
        }
        setState(KEY_STREAK, newStreak.toString())
        setState(KEY_LAST_ACTIVE_DATE, today.toString())
        return newStreak
    }

    fun currentStreak(): Int = getState(KEY_STREAK)?.toIntOrNull() ?: 0

    // ---------------------------------------------------------------------
    // Lesson completion
    // ---------------------------------------------------------------------

    fun isLessonCompleted(lessonKey: String): Boolean {
        readableDatabase.rawQuery("SELECT 1 FROM lesson_completion WHERE lesson_key = ?", arrayOf(lessonKey)).use {
            return it.moveToFirst()
        }
    }

    fun recordLessonCompleted(lessonKey: String, xpEarned: Int) {
        val values = ContentValues().apply {
            put("lesson_key", lessonKey)
            put("completed_at_epoch_ms", System.currentTimeMillis())
            put("xp_earned", xpEarned)
        }
        writableDatabase.insertWithOnConflict("lesson_completion", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // ---------------------------------------------------------------------
    // Leitner-box spaced repetition (5 boxes, 0..4; correct -> box+1, wrong -> box 0)
    // ---------------------------------------------------------------------

    private val boxIntervalDays = intArrayOf(0, 1, 2, 4, 7)

    fun recordAnswer(exerciseKey: String, correct: Boolean) {
        val db = writableDatabase
        var box = 0
        var correctCount = 0
        var incorrectCount = 0
        db.rawQuery(
            "SELECT box, correct_count, incorrect_count FROM srs_state WHERE exercise_key = ?",
            arrayOf(exerciseKey)
        ).use { c ->
            if (c.moveToFirst()) {
                box = c.getInt(0)
                correctCount = c.getInt(1)
                incorrectCount = c.getInt(2)
            }
        }
        box = if (correct) (box + 1).coerceAtMost(boxIntervalDays.size - 1) else 0
        if (correct) correctCount++ else incorrectCount++
        val nextReviewDay = LocalDate.now(ZoneId.systemDefault()).toEpochDay() + boxIntervalDays[box]
        val values = ContentValues().apply {
            put("exercise_key", exerciseKey)
            put("box", box)
            put("next_review_epoch_day", nextReviewDay)
            put("correct_count", correctCount)
            put("incorrect_count", incorrectCount)
        }
        db.insertWithOnConflict("srs_state", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // ---------------------------------------------------------------------
    // Due-for-review queries - added for the Learn tab's fold-aware layout
    // variants (progress_ring / course_dashboard cover screens): both need
    // to know which already-seen exercises are due today without the caller
    // re-deriving Leitner-box math from raw rows itself. Read-only queries
    // over the same `srs_state` table [recordAnswer] already writes - no
    // schema change, no new table.
    // ---------------------------------------------------------------------

    data class SrsRecord(val box: Int, val nextReviewEpochDay: Long, val correctCount: Int, val incorrectCount: Int)

    fun srsRecordFor(exerciseKey: String): SrsRecord? {
        readableDatabase.rawQuery(
            "SELECT box, next_review_epoch_day, correct_count, incorrect_count FROM srs_state WHERE exercise_key = ?",
            arrayOf(exerciseKey)
        ).use { c ->
            if (!c.moveToFirst()) return null
            return SrsRecord(c.getInt(0), c.getLong(1), c.getInt(2), c.getInt(3))
        }
    }

    /**
     * Every `exercise_key` whose `next_review_epoch_day` has arrived (i.e.
     * previously answered at least once, per [recordAnswer], and due today
     * or earlier) - exercises never yet answered are "new", not "due for
     * review", and deliberately excluded. Maps key -> its current box, so
     * callers (e.g. [com.retroid.translator.learn.LearnReviewQueue]) can
     * group/sort without a second query.
     */
    fun dueExerciseKeys(today: LocalDate = LocalDate.now(ZoneId.systemDefault())): Map<String, Int> {
        val todayEpochDay = today.toEpochDay()
        val out = LinkedHashMap<String, Int>()
        readableDatabase.rawQuery(
            "SELECT exercise_key, box FROM srs_state WHERE next_review_epoch_day <= ? ORDER BY next_review_epoch_day ASC",
            arrayOf(todayEpochDay.toString())
        ).use { c ->
            while (c.moveToNext()) out[c.getString(0)] = c.getInt(1)
        }
        return out
    }

    /** Due-today count per Leitner box (0..4), for the course_dashboard review-queue panel. Boxes with zero due items are still present, with count 0. */
    fun dueCountsByBox(today: LocalDate = LocalDate.now(ZoneId.systemDefault())): Map<Int, Int> {
        val counts = (0 until boxIntervalDays.size).associateWith { 0 }.toMutableMap()
        for (box in dueExerciseKeys(today).values) {
            counts[box] = (counts[box] ?: 0) + 1
        }
        return counts
    }

    // ---------------------------------------------------------------------
    // Internal key/value helpers
    // ---------------------------------------------------------------------

    private fun getState(key: String): String? {
        readableDatabase.rawQuery("SELECT value FROM app_state WHERE key = ?", arrayOf(key)).use {
            return if (it.moveToFirst()) it.getString(0) else null
        }
    }

    private fun setState(key: String, value: String) {
        val values = ContentValues().apply {
            put("key", key)
            put("value", value)
        }
        writableDatabase.insertWithOnConflict("app_state", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    companion object {
        private const val DB_NAME = "learn_progress.db"
        private const val DB_VERSION = 1
        private const val KEY_XP = "xp_total"
        private const val KEY_STREAK = "streak_count"
        private const val KEY_LAST_ACTIVE_DATE = "last_active_date"
    }
}
