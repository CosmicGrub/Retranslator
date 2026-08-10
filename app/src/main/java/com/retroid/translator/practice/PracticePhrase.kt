package com.retroid.translator.practice

/**
 * One phrase in the Practice tab's session-only drill queue - the shared
 * backing list behind the "drill_deck" (cover) and "drill_carousel" (cover)
 * layout variants (see [com.retroid.translator.ui.PracticeCoverVariant]).
 * Session-only, in-memory, not persisted: this is deliberately a thin
 * ordering/selection layer on top of the tab's REAL durable state, which is
 * [com.retroid.translator.audio.RecordingsStore] (actual saved .wav attempts
 * on disk) - the same durability model the tab's original single-phrase
 * layout already used (a phrase was never saved anywhere either; only the
 * recorded attempt was). Adding a phrase here does not create any new kind
 * of fake data - "attempted" state and rep counts are derived from real
 * session activity in [com.retroid.translator.ui.PracticeFragment]'s
 * `sessionAttemptCounts` map, not stored on this class.
 */
data class PracticePhrase(val text: String)
