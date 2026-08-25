package com.retroid.translator.learn

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/**
 * This repo's first-ever Robolectric-backed test (docs/specs/
 * engineering-systems-pitch.md system #1 Part C) - [LearnProgressStore] is
 * a plain [android.database.sqlite.SQLiteOpenHelper] with no JNI/audio/
 * network dependency, the one exception among the untested `engine`/`learn`
 * classes (VoskEngine/TranslationEngine/PiperTtsEngine etc. all genuinely
 * need real hardware or native libs, out of scope for a JVM-only fix). This
 * is the real XP/streak/Leitner-box mechanic behind the Learn tab's "not
 * just a dictionary lookup" claim, and none of it had a test before this.
 *
 * Robolectric provides a real (not stubbed) SQLite implementation and a
 * real [android.content.Context] via [ApplicationProvider], so every
 * assertion below runs against the actual `CREATE TABLE`/query/insert SQL
 * in [LearnProgressStore], not a fake in-memory substitute.
 *
 * Disclosed, checked risk: adding Robolectric puts its `android-all` shadow
 * jar (which ships a real org.json.JSONObject) on the same :app test
 * classpath as the explicit `org.json:json:20240303` test dependency
 * `VoskResultParsingTest` depends on shadowing the SDK stub with - see
 * app/build.gradle.kts's comment. [LearnProgressStore] itself never touches
 * org.json, so this file can't directly exercise that risk; instead,
 * VoskResultParsingTest's own exact-value assertions were re-run after this
 * dependency was added and confirmed still passing unchanged - the real
 * check, not a read-through.
 */
@RunWith(RobolectricTestRunner::class)
class LearnProgressStoreTest {

    private lateinit var store: LearnProgressStore

    @Before
    fun setUp() {
        store = LearnProgressStore(ApplicationProvider.getApplicationContext())
    }

    // ---------------------------------------------------------------------
    // XP
    // ---------------------------------------------------------------------

    @Test
    fun `totalXp starts at zero with no activity`() {
        assertEquals(0, store.totalXp())
    }

    @Test
    fun `addXp accumulates and returns the running total`() {
        assertEquals(10, store.addXp(10))
        assertEquals(35, store.addXp(25))
        assertEquals(35, store.totalXp())
    }

    // ---------------------------------------------------------------------
    // Streak - the exact scaffold docs/specs/engineering-systems-pitch.md
    // system #1 proposed, plus the same-day and never-active edge cases it
    // didn't spell out.
    // ---------------------------------------------------------------------

    @Test
    fun `currentStreak is zero before any activity is ever recorded`() {
        assertEquals(0, store.currentStreak())
    }

    @Test
    fun `first-ever activity starts the streak at 1`() {
        assertEquals(1, store.recordActivityToday(LocalDate.of(2026, 8, 20)))
    }

    @Test
    fun `recording activity again the same day does not double-count the streak`() {
        val day = LocalDate.of(2026, 8, 20)
        assertEquals(1, store.recordActivityToday(day))
        assertEquals(1, store.recordActivityToday(day))
        assertEquals(1, store.currentStreak())
    }

    @Test
    fun `consecutive-day activity increments streak, a gap resets it to 1`() {
        val day1 = LocalDate.of(2026, 8, 20)
        assertEquals(1, store.recordActivityToday(day1))
        assertEquals(2, store.recordActivityToday(day1.plusDays(1))) // consecutive
        assertEquals(3, store.recordActivityToday(day1.plusDays(2))) // consecutive
        assertEquals(1, store.recordActivityToday(day1.plusDays(5))) // gap -> reset
        assertEquals(1, store.currentStreak())
    }

    // ---------------------------------------------------------------------
    // Lesson completion
    // ---------------------------------------------------------------------

    @Test
    fun `a lesson is not completed until recordLessonCompleted is called`() {
        assertFalse(store.isLessonCompleted("es_greetings_01"))
        store.recordLessonCompleted("es_greetings_01", xpEarned = 20)
        assertTrue(store.isLessonCompleted("es_greetings_01"))
    }

    @Test
    fun `recording the same lesson completion twice does not error or duplicate`() {
        store.recordLessonCompleted("es_greetings_01", xpEarned = 20)
        store.recordLessonCompleted("es_greetings_01", xpEarned = 20) // CONFLICT_REPLACE, not a crash
        assertTrue(store.isLessonCompleted("es_greetings_01"))
    }

    // ---------------------------------------------------------------------
    // Leitner-box SRS - boxIntervalDays = [0, 1, 2, 4, 7], box index 0..4.
    // ---------------------------------------------------------------------

    @Test
    fun `an exercise never answered has no srs record yet`() {
        assertNull(store.srsRecordFor("es_greetings_03"))
    }

    @Test
    fun `a correct answer moves a new exercise from box 0 to box 1`() {
        store.recordAnswer("es_greetings_03", correct = true)
        val record = store.srsRecordFor("es_greetings_03")
        assertEquals(1, record?.box)
        assertEquals(1, record?.correctCount)
        assertEquals(0, record?.incorrectCount)
    }

    @Test
    fun `repeated correct answers climb the box but cap at the last box`() {
        repeat(10) { store.recordAnswer("es_greetings_03", correct = true) }
        val record = store.srsRecordFor("es_greetings_03")
        assertEquals(4, record?.box) // boxIntervalDays.size - 1
        assertEquals(10, record?.correctCount)
    }

    @Test
    fun `an incorrect answer resets the box to 0 but keeps the counts moving`() {
        store.recordAnswer("es_greetings_03", correct = true)
        store.recordAnswer("es_greetings_03", correct = true)
        store.recordAnswer("es_greetings_03", correct = false)
        val record = store.srsRecordFor("es_greetings_03")
        assertEquals(0, record?.box)
        assertEquals(2, record?.correctCount)
        assertEquals(1, record?.incorrectCount)
    }

    // ---------------------------------------------------------------------
    // Due-for-review queries
    // ---------------------------------------------------------------------

    @Test
    fun `a freshly answered exercise is not due again until its box interval passes`() {
        val today = LocalDate.of(2026, 8, 20)
        store.recordAnswer("es_greetings_03", correct = true, today = today) // -> box 1, interval 1 day
        assertTrue(store.dueExerciseKeys(today).isEmpty())
        assertTrue(store.dueExerciseKeys(today.plusDays(1)).containsKey("es_greetings_03"))
    }

    @Test
    fun `an exercise never answered is never due - new, not due for review`() {
        assertTrue(store.dueExerciseKeys(LocalDate.of(2026, 8, 20)).isEmpty())
    }

    @Test
    fun `dueCountsByBox reports zero for every box when nothing is due, real counts once due`() {
        val today = LocalDate.of(2026, 8, 20)
        store.recordAnswer("es_greetings_03", correct = true, today = today) // box 1, due tomorrow

        val beforeDue = store.dueCountsByBox(today)
        assertEquals(0, beforeDue[1])

        val onceDue = store.dueCountsByBox(today.plusDays(1))
        assertEquals(1, onceDue[1])
        assertEquals(0, onceDue[0])
    }
}
