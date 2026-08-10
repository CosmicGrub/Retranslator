package com.retroid.translator.learn

/**
 * Resolves [LearnProgressStore]'s raw due-exercise-key queries into real
 * [ResolvedExercise] objects against a live [LearnCourse] - the piece the
 * Learn tab's "progress_ring" and "course_dashboard" cover-screen layout
 * variants both need (docs/specs/fold5-adaptation.md scope table lists Learn
 * as "responsive scaling only"; the bespoke layout work here is a direct
 * instruction from the task that commissioned it, same deliberate deviation
 * [com.retroid.translator.ui.TranslateFragment] already documents for
 * Translate).
 *
 * Kept as a small standalone object (not methods on [LearnProgressStore] or
 * [LearnCourse]) since it needs both together and neither owns the other.
 */
object LearnReviewQueue {

    /** Every due-for-review exercise across the whole course, oldest-due first (lowest box, then earliest due day - see [LearnProgressStore.dueExerciseKeys]'s ORDER BY). Keys that no longer resolve (e.g. course content changed) are silently dropped. */
    fun dueNow(course: LearnCourse, store: LearnProgressStore): List<ResolvedExercise> {
        val due = store.dueExerciseKeys()
        return due.keys.mapNotNull { course.resolveExerciseKey(it) }
    }

    /** Due-for-review exercises restricted to one Leitner box (0..4), same ordering as [dueNow]. */
    fun dueInBox(course: LearnCourse, store: LearnProgressStore, box: Int): List<ResolvedExercise> =
        dueNow(course, store).filter { store.srsRecordFor(it.exerciseKey)?.box == box }

    /** (box, dueCount) for every box 0..4, box ascending - for course_dashboard's review-queue panel. */
    fun boxCounts(store: LearnProgressStore): List<Pair<Int, Int>> =
        store.dueCountsByBox().toSortedMap().map { (box, count) -> box to count }
}
