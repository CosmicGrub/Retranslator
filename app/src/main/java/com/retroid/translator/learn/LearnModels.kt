package com.retroid.translator.learn

import android.content.Context
import org.json.JSONObject

/**
 * Data model for the "Learn" tab's course content, loaded from a bundled
 * JSON asset (`assets/learn/<langCode>_course.json`) — no live network
 * dependency for content, consistent with the rest of the app's
 * offline-first design. Content itself is pre-curated at authoring time
 * (real sentences from the Tatoeba Project, https://tatoeba.org, CC-BY 2.0
 * FR — see each JSON file's `sourceNote` and the README license table),
 * not scraped or generated at runtime.
 */
enum class ExerciseType { MULTIPLE_CHOICE, WORD_BANK, LISTENING, SPEAKING }

data class LearnExercise(
    val type: ExerciseType,
    val tatoebaId: Long,
    val text: String,
    val gloss: String?,
) {
    /** Stable key used for SRS/progress tracking: "<lessonId>#<index>". */
    fun key(lessonId: String, index: Int): String = "$lessonId#$index"
}

data class LearnLesson(
    val id: String,
    val title: String,
    val exercises: List<LearnExercise>,
)

data class LearnUnit(
    val id: String,
    val title: String,
    val lessons: List<LearnLesson>,
)

data class LearnCourse(
    val language: String,
    val units: List<LearnUnit>,
)

object LearnCourseLoader {
    private val cache = HashMap<String, LearnCourse?>()

    /** Null if no course asset exists for this ML Kit language code yet. */
    fun load(context: Context, langCode: String): LearnCourse? {
        cache[langCode]?.let { return it }
        if (cache.containsKey(langCode)) return null // cached "no course" miss
        val course = try {
            context.assets.open("learn/${langCode}_course.json").use { input ->
                parse(input.readBytes().toString(Charsets.UTF_8))
            }
        } catch (e: Exception) {
            null
        }
        cache[langCode] = course
        return course
    }

    /** Which of ML Kit's language codes currently have a bundled course. */
    fun availableCourseCodes(context: Context): List<String> {
        val assets = try { context.assets.list("learn") } catch (e: Exception) { null } ?: return emptyList()
        return assets.filter { it.endsWith("_course.json") }.map { it.removeSuffix("_course.json") }
    }

    private fun parse(json: String): LearnCourse {
        val root = JSONObject(json)
        val language = root.getString("language")
        val unitsArr = root.getJSONArray("units")
        val units = (0 until unitsArr.length()).map { ui ->
            val u = unitsArr.getJSONObject(ui)
            val lessonsArr = u.getJSONArray("lessons")
            val lessons = (0 until lessonsArr.length()).map { li ->
                val l = lessonsArr.getJSONObject(li)
                val exArr = l.getJSONArray("exercises")
                val exercises = (0 until exArr.length()).map { ei ->
                    val e = exArr.getJSONObject(ei)
                    LearnExercise(
                        type = ExerciseType.valueOf(e.getString("type").uppercase()),
                        tatoebaId = e.getLong("tatoebaId"),
                        text = e.getString("text"),
                        gloss = if (e.has("gloss")) e.getString("gloss") else null,
                    )
                }
                LearnLesson(id = l.getString("id"), title = l.getString("title"), exercises = exercises)
            }
            LearnUnit(id = u.getString("id"), title = u.getString("title"), lessons = lessons)
        }
        return LearnCourse(language, units)
    }
}
