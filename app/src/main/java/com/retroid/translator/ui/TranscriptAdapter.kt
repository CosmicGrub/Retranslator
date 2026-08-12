package com.retroid.translator.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.retroid.translator.R
import com.retroid.translator.conversation.TranscriptEntry
import com.retroid.translator.databinding.ItemTranscriptBubbleBinding
import com.retroid.translator.engine.LanguageCatalog

/**
 * One `RecyclerView.Adapter` shared by all three Conversations layouts
 * (`fragment_conversations.xml`'s fallback single column,
 * `fragment_conversations_mirrored.xml`'s two panes, and
 * `fragment_conversations_large.xml`'s two panes - the latter two both via
 * `view_conversation_pane.xml`) - see docs/specs/fold5-adaptation.md §4's
 * tap-to-fix reassign affordance. This replaces the old model where
 * `ConversationsFragment` rendered every transcript entry by appending plain
 * strings into one shared `TextView` per pane
 * (`appendCombinedTranscript`/`appendPaneEntry`) - there was no per-entry
 * view for a tap listener to attach to. Now each turn is a real row.
 *
 * [mode] only changes how a row's label text reads (combined view shows
 * "A"/"B"/"→ A"/"→ B" since there's no physical pane to move to; pane view
 * shows "You"/"Them" since the pane placement itself already encodes the
 * side) - the reassign tap behavior and bubble visual are identical
 * everywhere, deliberately, so this is one adapter class, not three.
 */
class TranscriptAdapter(
    private val mode: Mode,
    private val onReassign: (TranscriptEntry) -> Unit
) : ListAdapter<TranscriptEntry, TranscriptAdapter.VH>(DIFF) {

    enum class Mode { COMBINED, PANE }

    class VH(val binding: ItemTranscriptBubbleBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemTranscriptBubbleBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = getItem(position)
        val b = holder.binding
        val langName = LanguageCatalog.displayNameFor(entry.langCode)
        val sideLabel = if (entry.paneIsA) "A" else "B"

        b.textBubbleLabel.text = when (mode) {
            Mode.COMBINED -> {
                val prefix = if (entry.failed) "!" else if (entry.own) sideLabel else "→ $sideLabel"
                val autoTag = if (entry.auto) " · auto" + (entry.basis?.let { ", $it" } ?: "") else ""
                "$prefix ($langName)$autoTag"
            }
            Mode.PANE -> {
                val who = if (entry.failed) "!" else if (entry.own) "You" else "Them"
                val autoTag = if (entry.auto) " · auto" else ""
                "$who ($langName)$autoTag"
            }
        }
        b.textBubbleContent.text = entry.text

        b.bubbleRoot.setBackgroundResource(
            when {
                entry.failed -> R.drawable.bg_bubble_failed
                entry.own -> R.drawable.bg_bubble_own
                else -> R.drawable.bg_bubble_them
            }
        )

        if (entry.failed) {
            // Nothing to reassign for an error note - no other side for it to
            // move to. Hide the affordance and drop the click behavior
            // entirely rather than wiring a no-op listener.
            b.textReassignIcon.visibility = View.GONE
            b.bubbleRoot.isClickable = false
            b.bubbleRoot.setOnClickListener(null)
            b.bubbleRoot.contentDescription = "${b.textBubbleLabel.text}: ${entry.text}"
        } else {
            b.textReassignIcon.visibility = View.VISIBLE
            b.bubbleRoot.isClickable = true
            val reassign = { onReassign(entry) }
            b.bubbleRoot.setOnClickListener { reassign() }
            b.textReassignIcon.setOnClickListener { reassign() }
            b.bubbleRoot.contentDescription =
                "${b.textBubbleLabel.text}: ${entry.text}. Tap to reassign to the other side."
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<TranscriptEntry>() {
            override fun areItemsTheSame(oldItem: TranscriptEntry, newItem: TranscriptEntry) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: TranscriptEntry, newItem: TranscriptEntry) =
                oldItem.speakerIsA == newItem.speakerIsA &&
                    oldItem.text == newItem.text &&
                    oldItem.langCode == newItem.langCode &&
                    oldItem.auto == newItem.auto &&
                    oldItem.basis == newItem.basis &&
                    oldItem.failed == newItem.failed
        }
    }
}
