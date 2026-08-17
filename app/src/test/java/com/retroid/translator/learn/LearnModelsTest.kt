package com.retroid.translator.learn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LearnCourse.findLessonById] / [LearnCourse.resolveExerciseKey] are pure
 * lookups over the in-memory course tree - no Android/asset dependency, so
 * they're tested here against a small hand-built course. See
 * [LearnCourseLoaderTest] for the same lookups exercised against the real
 * shipped `en_course.json`.
 */
class LearnModelsTest {

    private val greetLesson = LearnLesson(
        id = "greetings_1",
        title = "Basic Greetings",
        exercises = listOf(
            LearnExercise(ExerciseType.MULTIPLE_CHOICE, tatoebaId = 2258234L, text = "Good morning.", gloss = "greeting"),
            LearnExercise(ExerciseType.WORD_BANK, tatoebaId = 1858850L, text = "Hello.", gloss = null),
        ),
    )
    private val numbersLesson = LearnLesson(
        id = "numbers_1",
        title = "Counting",
        exercises = listOf(
            LearnExercise(ExerciseType.LISTENING, tatoebaId = 10796427L, text = "We have three cats.", gloss = null),
        ),
    )
    private val course = LearnCourse(
        language = "en",
        units = listOf(
            LearnUnit("greetings", "Greetings", listOf(greetLesson)),
            LearnUnit("numbers", "Numbers", listOf(numbersLesson)),
        ),
    )

    @Test
    fun `findLessonById finds a lesson in any unit, not just the first`() {
        val foundInFirstUnit = course.findLessonById("greetings_1")
        assertEquals("greetings", foundInFirstUnit?.first?.id)
        assertEquals("greetings_1", foundInFirstUnit?.second?.id)

        val foundInSecondUnit = course.findLessonById("numbers_1")
        assertEquals("numbers", foundInSecondUnit?.first?.id)
        assertEquals("numbers_1", foundInSecondUnit?.second?.id)
    }

    @Test
    fun `findLessonById returns null for an unknown lesson id`() {
        assertNull(course.findLessonById("does_not_exist"))
    }

    @Test
    fun `LearnExercise key format matches what resolveExerciseKey expects`() {
        assertEquals("greetings_1#0", greetLesson.exercises[0].key("greetings_1", 0))
        assertEquals("numbers_1#12", numbersLesson.exercises[0].key("numbers_1", 12))
    }

    @Test
    fun `resolveExerciseKey round-trips a real key back to the right exercise`() {
        val resolved = course.resolveExerciseKey("greetings_1#1")
        assertTrue(resolved != null)
        assertEquals("greetings", resolved!!.unit.id)
        assertEquals("greetings_1", resolved.lesson.id)
        assertEquals(1, resolved.exerciseIndex)
        assertEquals("Hello.", resolved.exercise.text)
        assertEquals("greetings_1#1", resolved.exerciseKey)
    }

    @Test
    fun `resolveExerciseKey handles a lesson id containing a literal hash`() {
        val trickyLesson = LearnLesson(
            id = "weird#lesson",
            title = "Tricky",
            exercises = listOf(LearnExercise(ExerciseType.SPEAKING, tatoebaId = 1L, text = "Hi.", gloss = null)),
        )
        val trickyCourse = LearnCourse("en", listOf(LearnUnit("u", "U", listOf(trickyLesson))))
        // substringBeforeLast/substringAfterLast (not split("#")) is exactly
        // what makes this resolve correctly despite the embedded '#'.
        val resolved = trickyCourse.resolveExerciseKey("weird#lesson#0")
        assertTrue(resolved != null)
        assertEquals("weird#lesson", resolved!!.lesson.id)
        assertEquals(0, resolved.exerciseIndex)
    }

    @Test
    fun `resolveExerciseKey returns null for a malformed or unresolvable key`() {
        assertNull(course.resolveExerciseKey("no-hash-here"))
        assertNull(course.resolveExerciseKey("unknown_lesson#0"))
        assertNull(course.resolveExerciseKey("greetings_1#99")) // index out of range
        assertNull(course.resolveExerciseKey("greetings_1#not_a_number"))
    }
}
