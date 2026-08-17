package com.retroid.translator.learn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Exercises [LearnCourseLoader.parse] against the real, shipped
 * `app/src/main/assets/learn/en_course.json` asset - not an invented
 * fixture - reading it straight off disk rather than going through
 * [LearnCourseLoader.load] (which needs a real Android `Context`, out of
 * scope for a plain-JVM test). `parse` was changed from `private` to
 * `internal` (see its doc comment) specifically to unlock this; the module
 * boundary Kotlin's `internal` enforces is the whole `:app` Gradle module,
 * which `src/test` compiles as a friend source set of, so this is a
 * visibility widening only, not a behavior change.
 *
 * Real content assertions below match README.md's "Currently shipped: 2
 * units (Greetings, Numbers), 1 lesson each, 8 exercises per lesson" and
 * the specific tatoebaId spot-checks docs/ENGINES.md's "Content sourcing"
 * section already verified (2258234 = "Good morning.", 1037732 = "My very
 * educated mother just showed us nine planets.").
 */
class LearnCourseLoaderTest {

    private fun loadRealCourseJson(): String {
        // Gradle's Test task runs with the module directory (`app/`) as the
        // working directory, so this path is relative to that, not the repo root.
        val candidates = listOf(
            File("src/main/assets/learn/en_course.json"),
            File("app/src/main/assets/learn/en_course.json"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error(
                "Could not find the real en_course.json asset from working dir " +
                    "${File(".").absolutePath} - tried ${candidates.map { it.path }}"
            )
        return file.readText(Charsets.UTF_8)
    }

    @Test
    fun `parses the real shipped en_course json into 2 units, 2 lessons, 16 exercises`() {
        val course = LearnCourseLoader.parse(loadRealCourseJson())

        assertEquals("en", course.language)
        assertEquals(2, course.units.size)
        assertEquals(listOf("greetings", "numbers"), course.units.map { it.id })

        val allLessons = course.units.flatMap { it.lessons }
        assertEquals(2, allLessons.size)
        assertEquals(listOf("greetings_1", "numbers_1"), allLessons.map { it.id })

        val totalExercises = allLessons.sumOf { it.exercises.size }
        assertEquals(16, totalExercises)
        allLessons.forEach { assertEquals(8, it.exercises.size) }
    }

    @Test
    fun `real tatoebaId spot-checks resolve to the right sentence text`() {
        val course = LearnCourseLoader.parse(loadRealCourseJson())
        val allExercises = course.units.flatMap { it.lessons }.flatMap { it.exercises }

        val goodMorning = allExercises.find { it.tatoebaId == 2258234L }
        assertTrue(goodMorning != null)
        assertEquals("Good morning.", goodMorning!!.text)

        val ninePlanets = allExercises.find { it.tatoebaId == 1037732L }
        assertTrue(ninePlanets != null)
        assertEquals("My very educated mother just showed us nine planets.", ninePlanets!!.text)
    }

    @Test
    fun `every exercise type in the real course parses to a known ExerciseType`() {
        val course = LearnCourseLoader.parse(loadRealCourseJson())
        val types = course.units.flatMap { it.lessons }.flatMap { it.exercises }.map { it.type }.toSet()
        assertEquals(setOf(ExerciseType.MULTIPLE_CHOICE, ExerciseType.WORD_BANK, ExerciseType.LISTENING, ExerciseType.SPEAKING), types)
    }

    @Test
    fun `resolveExerciseKey works end-to-end against the real parsed course`() {
        val course = LearnCourseLoader.parse(loadRealCourseJson())
        // greetings_1#0 is the real "Good morning." multiple-choice exercise.
        val resolved = course.resolveExerciseKey("greetings_1#0")
        assertTrue(resolved != null)
        assertEquals("Good morning.", resolved!!.exercise.text)
        assertEquals(2258234L, resolved.exercise.tatoebaId)
    }
}
