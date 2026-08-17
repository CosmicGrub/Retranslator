package com.retroid.translator.conversation

/**
 * One bubble in Conversations' transcript - the per-entry replacement for
 * the old plain-string-append model (`combinedTranscript`/`paneATranscript`/
 * `paneBTranscript` `StringBuilder`s that used to live directly on
 * `ConversationsFragment`). This is what closes docs/specs/fold5-adaptation.md
 * §4's "Fallback UX for a wrong guess" gap: with one shared `TextView` per
 * pane there was no per-entry view to attach a tap listener to, so the
 * spec'd reassign affordance ("every translated bubble carries a small
 * reassign affordance - tapping it flips which side the utterance is
 * attributed to and re-renders it mirrored to the other pane") was never
 * actually buildable until transcript rendering moved to one row per turn.
 *
 * Every utterance produces exactly two entries sharing one [turnId]: the
 * original transcribed speech ([own] = true, rendered as "You" in the
 * speaker's own pane) and its translation ([own] = false, rendered as
 * "Them" in the other pane) - or, on a translation failure, a single
 * [failed] note instead of a translation entry. Reassigning a turn flips
 * [speakerIsA] to the SAME new value on every entry sharing that [turnId]
 * at once (see `ConversationsFragment.reassignTurn`), which is enough to
 * move every bubble of that turn to the opposite pane (see [paneIsA])
 * without re-decoding or re-translating anything. That is deliberate and
 * matches the spec precisely: "Commit to the best guess immediately...
 * correction happens after the fact, on the result itself, not through new
 * persistent UI" - this is a pure presentation-layer correction, not a
 * re-run of speech recognition or translation.
 *
 * [speakerIsA] is `val`, not `var`, on purpose: `reassignTurn` replaces
 * entries via `.copy(speakerIsA = ...)` rather than mutating a field in
 * place. A prior version made it `var` and mutated it directly, which
 * silently broke the reassign affordance's UI update - `TranscriptAdapter`'s
 * `DiffUtil.ItemCallback` compares `oldItem`/`newItem` by field equality,
 * but in-place mutation on the same object instances already held by the
 * adapter's current list meant `oldItem`/`newItem` were literally the same
 * object by the time the diff ran, so `areContentsTheSame` always returned
 * true and the RecyclerView never rebound the changed row - confirmed live
 * on real Fold 5 hardware (RFCW80CK2RW): logcat showed
 * `reassign: turnId=0 -> B` firing correctly on every tap, but the bubble's
 * displayed side never visually changed. Keeping this `val` makes that bug
 * class structurally impossible to reintroduce.
 */
data class TranscriptEntry(
    val id: Long,
    val turnId: Long,
    val speakerIsA: Boolean,
    val own: Boolean,
    val text: String,
    val langCode: String,
    val auto: Boolean,
    val basis: String? = null,
    val failed: Boolean = false
) {
    /**
     * Which pane/side this bubble currently renders in. An "own" bubble
     * (the speaker's original words) follows the speaker; a translation (or
     * failure note) bubble goes to the other side, since it's meant for the
     * listener. Both flip together when [speakerIsA] is reassigned, since
     * that's the only thing this getter reads.
     */
    val paneIsA: Boolean get() = if (own) speakerIsA else !speakerIsA
}
