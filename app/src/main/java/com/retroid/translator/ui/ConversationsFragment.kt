package com.retroid.translator.ui

import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.window.layout.FoldingFeature
import com.retroid.translator.MainActivity
import com.retroid.translator.R
import com.retroid.translator.databinding.FragmentConversationsBinding
import com.retroid.translator.databinding.FragmentConversationsLargeBinding
import com.retroid.translator.databinding.FragmentConversationsMirroredBinding
import com.retroid.translator.databinding.ItemRecordingBinding
import androidx.core.content.ContextCompat
import com.retroid.translator.audio.ContinuousListeningService
import com.retroid.translator.audio.MicPipeline
import com.retroid.translator.audio.RecordingsStore
import com.retroid.translator.conversation.ContinuousConversationController
import com.retroid.translator.conversation.TranscriptEntry
import com.retroid.translator.engine.LanguageCatalog
import com.retroid.translator.engine.TranslationEngine
import com.retroid.translator.engine.VoiceGender
import com.retroid.translator.engine.VoicePreferences
import com.retroid.translator.engine.VoskEngine
import com.retroid.translator.engine.VoskModelCatalog
import com.retroid.translator.fold.FoldPosture
import com.retroid.translator.fold.FoldPostureProvider
import com.retroid.translator.fold.FoldState
import com.retroid.translator.settings.LayoutPreferences
import com.google.mlkit.nl.translate.TranslateLanguage
import kotlinx.coroutines.launch
import java.io.File

/**
 * Conversations tab. Two-way turn-taking interpreter.
 *
 * §4 of docs/specs/fold5-adaptation.md (dual-recognizer auto-detect) IS now
 * wired in, as of the real-human-speech validation pass documented in that
 * spec's "§4 status update" - but as an opt-in "Continuous listening"
 * toggle ([continuousEnabled]) layered ALONGSIDE the original app-tracked
 * manual alternation ([turnIsA]) below, not a replacement of it. Manual
 * tap-to-talk remains the default and is untouched by the toggle being off;
 * both flows share the same transcript/state helpers
 * (setStatus/addTurn/addTranslation/addFailureNote - see "Tap-to-fix
 * reassign affordance" below) so either can drive any of the three layouts
 * below identically. See the "Continuous listening" section further down
 * this file for the toggle's own state and
 * [com.retroid.translator.conversation.ContinuousConversationController] for
 * the dual-recognizer streaming engine it drives.
 *
 * Renders into one of two mutually-exclusive layouts, swapped live as
 * [FoldPostureProvider] reports posture changes, without ever recreating
 * this Fragment or its Activity:
 *  - [FragmentConversationsBinding] (`fragment_conversations.xml`) - the
 *    original single-column layout, used for book-portrait (flat or
 *    angled) and any non-foldable/no-fold-feature device. Unchanged from
 *    before this pass.
 *  - [FragmentConversationsMirroredBinding] (`fragment_conversations_mirrored.xml`)
 *    - the new tabletop-landscape mirrored face-to-face layout (spec §2),
 *    used for [FoldPosture.TABLETOP_LANDSCAPE_FLAT] /
 *    [FoldPosture.TABLETOP_LANDSCAPE_ANGLED].
 *
 * Both layouts are pure presentations of the same underlying state
 * ([langACode]/[langBCode]/[turnIsA]/transcript) - there is exactly one copy
 * of that state, held as plain fields on this Fragment rather than read back
 * out of whichever View happens to be inflated right now, specifically so
 * switching layouts mid-conversation never loses or duplicates state.
 *
 * **Tap-to-fix reassign affordance (docs/specs/fold5-adaptation.md §4's
 * "Fallback UX for a wrong guess"), built in this pass.** The transcript
 * used to be three plain-string `StringBuilder`s
 * (`combinedTranscript`/`paneATranscript`/`paneBTranscript`) appended into
 * one shared `TextView` per pane - there was no per-entry view for a tap
 * listener to attach to, so the spec's described reassign affordance was
 * never actually buildable. [transcriptEntries] replaces all three with one
 * ordered list of [TranscriptEntry] rows, rendered by a
 * [com.retroid.translator.ui.TranscriptAdapter] `RecyclerView` per layout
 * ([fallbackAdapter] for the single-column fallback view, [paneAAdapter]/
 * [paneBAdapter] for whichever of the mirrored/large layouts is currently
 * active - see [refreshTranscriptViews]). Tapping any non-failed bubble
 * calls [reassignTurn], which flips [TranscriptEntry.speakerIsA] for every
 * entry sharing that turn's id - moving both the original-speech bubble and
 * its translation to the opposite pane at once, matching the spec's "tapping
 * it flips which side the utterance is attributed to and re-renders it
 * mirrored to the other pane" exactly. This is a pure presentation-layer
 * correction - it does not re-run speech recognition, dual-recognizer
 * picking, or translation (see [ContinuousConversationController], which
 * this class still drives completely unmodified for the language pick
 * itself). Works identically for manual tap-to-talk turns (added via
 * [addTurn]/[addTranslation] in `onMicTap`'s `onFinal`) and continuous
 * auto-detect turns (added the same way from [continuousListener]).
 */
class ConversationsFragment : Fragment() {

    // ---------------------------------------------------------------------
    // Session state - the single source of truth, independent of whichever
    // layout (fallback or mirrored) is currently inflated.
    // ---------------------------------------------------------------------

    private lateinit var languageCodes: List<String>
    private var langACode: String = TranslateLanguage.ENGLISH
    private var langBCode: String = TranslateLanguage.SPANISH
    private var turnIsA = true
    private var genderIsMale = false
    private var recordSessionEnabled = false
    private var statusText = ""

    // ---------------------------------------------------------------------
    // Continuous listening (docs/specs/fold5-adaptation.md §4, wired in for
    // real here): VAD-triggered, no tap, dual-recognizer auto-detect per
    // utterance. Fully additive alongside the manual turnIsA-driven tap flow
    // above, which this does NOT replace - see class doc's "Renders into one
    // of two..." note for why both need to keep working (regression safety +
    // the spec's own recommendation to keep a manual path as a fallback).
    // Uses two DEDICATED VoskEngine instances, never `app.vosk` (the single
    // shared, single-resident-model engine every other tab/flow uses) -
    // exactly the same reasoning as DualRecognizerPrototype.loadEngines:
    // getting two simultaneously-resident models "for free" by owning two
    // independent engine instances, without touching VoskEngine's own
    // one-model-at-a-time design or stealing the shared instance out from
    // under Translate/Practice/Learn/manual-Conversations mid-use.
    // ---------------------------------------------------------------------
    private var continuousEnabled = false
    private var continuousEngineA: VoskEngine? = null
    private var continuousEngineB: VoskEngine? = null
    private var continuousController: ContinuousConversationController? = null
    private var continuousLoading = false

    /**
     * The single source of truth for the transcript, replacing the old
     * `combinedTranscript`/`paneATranscript`/`paneBTranscript` `StringBuilder`s
     * - see class doc's "Tap-to-fix reassign affordance" section.
     * Chronological (oldest first); each layout's adapter derives its own
     * view of this (combined in order, or filtered+newest-first per pane) in
     * [refreshTranscriptViews].
     */
    private val transcriptEntries = mutableListOf<TranscriptEntry>()
    private var nextEntryId = 0L
    private var nextTurnId = 0L

    private var fallbackAdapter: TranscriptAdapter? = null
    /** Bound to whichever layout's "A slot" RecyclerView is currently inflated - mirrored's paneTop or large's paneLeft. */
    private var paneAAdapter: TranscriptAdapter? = null
    /** Bound to whichever layout's "B slot" RecyclerView is currently inflated - mirrored's paneBottom or large's paneRight. */
    private var paneBAdapter: TranscriptAdapter? = null

    private val mainActivity get() = activity as? MainActivity
    private lateinit var recordingsStore: RecordingsStore
    private lateinit var foldPostureProvider: FoldPostureProvider
    private var player: MediaPlayer? = null

    // ---------------------------------------------------------------------
    // View plumbing - exactly one of these is non-null at a time.
    //
    // docs/specs/galaxy-tab-s9fe-adaptation.md added a THIRD layout kind,
    // LARGE (fragment_conversations_large.xml - two static side-by-side
    // panes, no fold hardware involved) alongside the two fold-posture-
    // selected kinds below (FALLBACK/MIRRORED, both unchanged from before
    // that pass). [largeScreenSideBySide] is read once from
    // R.bool.conversations_side_by_side (true only on sw720dp+ devices) and
    // consulted only as a tie-breaker when the device is NOT in a genuine
    // fold-mirrored posture - see [applyPosture]. A real fold-mirrored
    // posture always wins over LARGE.
    // ---------------------------------------------------------------------

    private enum class LayoutKind { FALLBACK, MIRRORED, LARGE }

    private var contentContainer: FrameLayout? = null
    private var fallbackBinding: FragmentConversationsBinding? = null
    private var mirroredBinding: FragmentConversationsMirroredBinding? = null
    private var largeBinding: FragmentConversationsLargeBinding? = null
    private var activeKind: LayoutKind = LayoutKind.FALLBACK
    private var largeScreenSideBySide = false
    private var layoutInitialized = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        // A plain mount point, not XML - which of the two real layouts gets
        // inflated into it is decided live by fold posture (see
        // observeFoldPosture), and can change repeatedly across this
        // Fragment's single lifetime without ever calling onCreateView again.
        val root = FrameLayout(requireContext())
        root.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        )
        contentContainer = root
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recordingsStore = RecordingsStore(requireContext(), "conversations")
        languageCodes = LanguageCatalog.codes
        langACode = languageCodes.indexOf(TranslateLanguage.ENGLISH).let { if (it >= 0) languageCodes[it] else languageCodes[0] }
        langBCode = languageCodes.indexOf(TranslateLanguage.SPANISH).let { idx ->
            if (idx >= 0) languageCodes[idx] else languageCodes[1.coerceAtMost(languageCodes.size - 1)]
        }
        genderIsMale = VoicePreferences.getGender(requireContext()) == VoiceGender.MALE
        largeScreenSideBySide = resources.getBoolean(R.bool.conversations_side_by_side)
        observeFoldPosture()
        maybeApplyFold5ContinuousDefault()
    }

    /**
     * Fold5 edition cold-launch default (docs/specs/fold5-adaptation.md's
     * dated Fold5-edition section, [LayoutPreferences.CONVERSATIONS_CONTINUOUS_DEFAULT_ON]'s
     * doc) - "dual-recognizer auto-detect ON by default". Attempts
     * [startContinuousMode] automatically the first time this tab is shown,
     * so a first-time cold launch on this device lands directly in
     * continuous auto-detect rather than requiring the user to discover and
     * flip the toggle manually. This calls the exact same function the
     * toggle's own tap handler calls - every real check inside it (mic
     * permission, Vosk-model presence for [langACode]/[langBCode], mic
     * already busy) and its existing graceful Toast-and-revert-to-off
     * behavior on any of them failing are completely unmodified, so this
     * adds no new failure path, only a new automatic trigger for the
     * existing one. It is safe to call before any of the three layouts have
     * bound ([applyContinuousUiState]/[setStatus] both null-check every
     * binding already).
     *
     * Gated solely on [LayoutPreferences.hasUserSetConversationsContinuous]
     * - once the user explicitly taps the toggle themselves, on this or any
     * later visit, that preference is recorded (see
     * [onContinuousToggleRequested]) and this function no-ops forever after
     * for this install, so the user's own choice - including explicitly
     * turning it back off - always wins and is never silently re-applied.
     */
    private fun maybeApplyFold5ContinuousDefault() {
        if (!LayoutPreferences.CONVERSATIONS_CONTINUOUS_DEFAULT_ON) return
        if (LayoutPreferences.hasUserSetConversationsContinuous(requireContext())) return
        if (continuousEnabled || continuousLoading) return
        Log.i(TAG, "fold5 edition: attempting cold-launch default-on for continuous listening (dual-recognizer auto-detect)")
        startContinuousMode()
    }

    // ---------------------------------------------------------------------
    // Fold posture -> layout switching
    // ---------------------------------------------------------------------

    private fun observeFoldPosture() {
        foldPostureProvider = FoldPostureProvider(requireActivity())
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                foldPostureProvider.postureFlow().collect { state -> applyPosture(state) }
            }
        }
        // Progressive-enhancement continuous hinge-angle signal (spec §2
        // "Transition polish"). Not used to decide layout (postureFlow alone
        // is authoritative) - collected here purely so a real angle stream
        // is observable in logcat as on-device verification that this
        // device takes the live-sensor path rather than the no-sensor
        // fallback (see HingeAngleSensor's doc for what "fallback" means).
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                foldPostureProvider.hingeAngleFlow().collect { degrees ->
                    android.util.Log.d(TAG, "hinge angle: ${degrees}°")
                }
            }
        }
    }

    private fun applyPosture(state: FoldState) {
        android.util.Log.d(
            TAG,
            "posture=${state.posture} wantMirrored=${state.posture.isMirroredTabletop} " +
                "feature.state=${state.feature?.state} feature.orientation=${state.feature?.orientation} " +
                "feature.isSeparating=${state.feature?.isSeparating} feature.occlusionType=${state.feature?.occlusionType} " +
                "feature.bounds=${state.feature?.bounds}"
        )
        val wantMirrored = state.posture.isMirroredTabletop
        // A genuine fold-mirrored posture always wins over the large-screen
        // side-by-side mode - see the "View plumbing" field doc comment.
        val desiredKind = when {
            wantMirrored -> LayoutKind.MIRRORED
            largeScreenSideBySide -> LayoutKind.LARGE
            else -> LayoutKind.FALLBACK
        }
        if (layoutInitialized && desiredKind == activeKind) {
            // Same layout family - if we're mirrored, the split geometry can
            // still have moved (FLAT <-> HALF_OPENED reports slightly
            // different FoldingFeature.bounds on some devices), so re-apply it.
            if (desiredKind == LayoutKind.MIRRORED) state.feature?.let { applyMirroredGeometry(it) }
            return
        }
        switchLayout(desiredKind)
        if (desiredKind == LayoutKind.MIRRORED) state.feature?.let { applyMirroredGeometry(it) }
    }

    private fun switchLayout(kind: LayoutKind) {
        val container = contentContainer ?: return
        container.removeAllViews()
        fallbackBinding = null
        mirroredBinding = null
        largeBinding = null

        when (kind) {
            LayoutKind.MIRRORED -> {
                val mb = FragmentConversationsMirroredBinding.inflate(layoutInflater, container, false)
                mirroredBinding = mb
                container.addView(mb.root)
                bindMirroredView(mb)
            }
            LayoutKind.LARGE -> {
                val lb = FragmentConversationsLargeBinding.inflate(layoutInflater, container, false)
                largeBinding = lb
                container.addView(lb.root)
                bindLargeView(lb)
            }
            LayoutKind.FALLBACK -> {
                val fb = FragmentConversationsBinding.inflate(layoutInflater, container, false)
                fallbackBinding = fb
                container.addView(fb.root)
                bindFallbackView(fb)
            }
        }

        // Real Choreographer-driven crossfade (ViewPropertyAnimator, not a
        // fixed-rate postDelayed loop - spec §5's 120Hz-display note) instead
        // of an abrupt cut when switching between layout kinds.
        container.alpha = 0f
        container.animate().alpha(1f).setDuration(200L).start()

        activeKind = kind
        layoutInitialized = true
    }

    /**
     * Sizes and positions the two mirrored panes from the live
     * [FoldingFeature.bounds] (never a static 50/50 split) and applies the
     * 180° rotation to the top pane. Uses [FoldingFeature.occlusionType] to
     * add a small extra safety margin around the hinge on top of its exact
     * bounds when the hinge fully occludes content underneath it.
     */
    private fun applyMirroredGeometry(feature: FoldingFeature) {
        val mb = mirroredBinding ?: return
        val container = contentContainer ?: return
        if (container.height == 0) {
            // Not laid out yet (first frame) - defer one pass.
            container.post { if (mirroredBinding === mb) applyMirroredGeometry(feature) }
            return
        }

        val loc = IntArray(2)
        container.getLocationInWindow(loc)
        val containerTopInWindow = loc[1]

        val extraInsetPx = if (feature.occlusionType == FoldingFeature.OcclusionType.FULL) {
            (8 * resources.displayMetrics.density).toInt()
        } else {
            0
        }

        val hingeTopLocal = feature.bounds.top - containerTopInWindow
        val hingeBottomLocal = feature.bounds.bottom - containerTopInWindow

        val topPaneHeight = (hingeTopLocal - extraInsetPx).coerceAtLeast(0)
        val bottomPaneTop = (hingeBottomLocal + extraInsetPx).coerceAtLeast(topPaneHeight)
        val bottomPaneHeight = (container.height - bottomPaneTop).coerceAtLeast(0)

        // Deliberately NOT setting pivotX/pivotY explicitly: doing so opts a
        // View out of Android's automatic "recenter on every layout pass"
        // pivot behavior, and at this exact point (right after a
        // layoutParams change, before the next layout pass runs) .width is
        // still the OLD measured width, so computing "width / 2f" here would
        // pivot around a stale, usually-wrong center - e.g. always the
        // previous frame's center. Leaving pivot untouched keeps the default
        // width/2,height/2 behavior, which Android recomputes correctly on
        // every subsequent layout pass as topPaneHeight changes.
        mb.paneTop.root.layoutParams = (mb.paneTop.root.layoutParams as FrameLayout.LayoutParams).apply {
            width = FrameLayout.LayoutParams.MATCH_PARENT
            height = topPaneHeight
            topMargin = 0
        }
        mb.paneTop.root.rotation = 180f

        mb.paneBottom.root.layoutParams = (mb.paneBottom.root.layoutParams as FrameLayout.LayoutParams).apply {
            width = FrameLayout.LayoutParams.MATCH_PARENT
            height = bottomPaneHeight
            topMargin = bottomPaneTop
        }
        mb.paneBottom.root.rotation = 0f

        android.util.Log.d(
            TAG,
            "mirrored geometry: hinge=${feature.bounds} occlusion=${feature.occlusionType} " +
                "state=${feature.state} orientation=${feature.orientation} " +
                "topPaneHeight=$topPaneHeight bottomPaneTop=$bottomPaneTop bottomPaneHeight=$bottomPaneHeight"
        )
    }

    // ---------------------------------------------------------------------
    // Fallback (single-column, book-portrait) view binding - behavior is
    // unchanged from before this pass.
    // ---------------------------------------------------------------------

    private fun bindFallbackView(fb: FragmentConversationsBinding) {
        setupSpinners(fb)
        setupGenderToggle(fb)
        fb.toggleRecordSession.isChecked = recordSessionEnabled
        fb.toggleRecordSession.setOnCheckedChangeListener { _, checked -> recordSessionEnabled = checked }
        fb.btnConversationMic.setOnClickListener { onMicTap() }
        fb.toggleContinuousListening.isChecked = continuousEnabled
        // setOnClickListener + read .isChecked afterward (not
        // setOnCheckedChangeListener) deliberately - turning this ON needs an
        // async model-load that can fail and revert the toggle's checked
        // state programmatically; a checked-change listener would re-fire on
        // that revert too, which a plain click listener avoids.
        fb.toggleContinuousListening.setOnClickListener { onContinuousToggleRequested(fb.toggleContinuousListening.isChecked) }
        applyContinuousUiState()
        fallbackAdapter = TranscriptAdapter(TranscriptAdapter.Mode.COMBINED) { entry -> reassignTurn(entry) }
        fb.recyclerTranscript.layoutManager = LinearLayoutManager(requireContext())
        fb.recyclerTranscript.adapter = fallbackAdapter
        // This RecyclerView lives inside fragment_conversations.xml's own
        // outer ScrollView (see that file's comment) - disabling nested
        // scrolling keeps the whole screen scrolling as one region, exactly
        // like the plain TextView it replaces did, instead of fighting the
        // outer ScrollView for scroll events.
        fb.recyclerTranscript.isNestedScrollingEnabled = false
        fb.textConversationStatus.text = statusText
        updateTurnIndicator()
        refreshRecordingsList()
        refreshTranscriptViews()
    }

    private fun setupGenderToggle(fb: FragmentConversationsBinding) =
        setupGenderToggleGeneric(fb.radioGroupGenderConv, fb.radioFemaleConv, fb.radioMaleConv)

    private fun setupGenderToggleGeneric(group: RadioGroup, radioFemale: RadioButton, radioMale: RadioButton) {
        radioMale.isChecked = genderIsMale
        radioFemale.isChecked = !genderIsMale
        group.setOnCheckedChangeListener { _, _ ->
            genderIsMale = radioMale.isChecked
            VoicePreferences.setGender(requireContext(), selectedGender())
        }
    }

    private fun setupSpinners(fb: FragmentConversationsBinding) =
        setupSpinnersGeneric(fb.spinnerLangA, fb.spinnerLangB)

    /**
     * Shared by [bindFallbackView] and [bindLargeView] (docs/specs/galaxy-tab-s9fe-adaptation.md's
     * large-screen side-by-side layout reuses the same langA/langB spinner
     * pair, just laid out differently) - extracted rather than duplicated so
     * there's exactly one place that owns "how picking a language updates
     * langACode/langBCode".
     */
    private fun setupSpinnersGeneric(spinnerA: Spinner, spinnerB: Spinner) {
        val names = languageCodes.map { LanguageCatalog.displayNameFor(it) }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerA.adapter = adapter
        spinnerB.adapter = adapter
        spinnerA.setSelection(languageCodes.indexOf(langACode).coerceAtLeast(0))
        spinnerB.setSelection(languageCodes.indexOf(langBCode).coerceAtLeast(0))

        val listener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                langACode = languageCodes[spinnerA.selectedItemPosition]
                langBCode = languageCodes[spinnerB.selectedItemPosition]
                updateTurnIndicator()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        spinnerA.onItemSelectedListener = listener
        spinnerB.onItemSelectedListener = listener
    }

    // ---------------------------------------------------------------------
    // Mirrored (tabletop-landscape) view binding
    // ---------------------------------------------------------------------

    private fun bindMirroredView(mb: FragmentConversationsMirroredBinding) {
        mb.paneTop.btnPaneMic.setOnClickListener { onMicTap() }
        mb.paneBottom.btnPaneMic.setOnClickListener { onMicTap() }
        mb.paneTop.togglePaneContinuous.isChecked = continuousEnabled
        mb.paneBottom.togglePaneContinuous.isChecked = continuousEnabled
        mb.paneTop.togglePaneContinuous.setOnClickListener { onContinuousToggleRequested(mb.paneTop.togglePaneContinuous.isChecked) }
        mb.paneBottom.togglePaneContinuous.setOnClickListener { onContinuousToggleRequested(mb.paneBottom.togglePaneContinuous.isChecked) }
        applyContinuousUiState()
        mb.paneTop.textPaneStatus.text = statusText
        mb.paneBottom.textPaneStatus.text = statusText
        paneAAdapter = TranscriptAdapter(TranscriptAdapter.Mode.PANE) { entry -> reassignTurn(entry) }
        paneBAdapter = TranscriptAdapter(TranscriptAdapter.Mode.PANE) { entry -> reassignTurn(entry) }
        mb.paneTop.recyclerPaneTranscript.layoutManager = LinearLayoutManager(requireContext())
        mb.paneTop.recyclerPaneTranscript.adapter = paneAAdapter
        mb.paneBottom.recyclerPaneTranscript.layoutManager = LinearLayoutManager(requireContext())
        mb.paneBottom.recyclerPaneTranscript.adapter = paneBAdapter
        updateTurnIndicator()
        refreshTranscriptViews()
    }

    // ---------------------------------------------------------------------
    // Large-screen side-by-side view binding (docs/specs/galaxy-tab-s9fe-adaptation.md).
    // paneLeft == "A"'s pane, paneRight == "B"'s pane - reuses the exact same
    // view_conversation_pane.xml include and transcriptEntries state as
    // bindMirroredView above, just with no rotation and no runtime
    // hinge-driven geometry (a static 50/50 XML weight is enough - there's
    // no hinge to size around).
    // ---------------------------------------------------------------------

    private fun bindLargeView(lb: FragmentConversationsLargeBinding) {
        setupSpinnersGeneric(lb.spinnerLangA, lb.spinnerLangB)
        setupGenderToggleGeneric(lb.radioGroupGenderConv, lb.radioFemaleConv, lb.radioMaleConv)
        lb.toggleRecordSession.isChecked = recordSessionEnabled
        lb.toggleRecordSession.setOnCheckedChangeListener { _, checked -> recordSessionEnabled = checked }
        lb.paneLeft.btnPaneMic.setOnClickListener { onMicTap() }
        lb.paneRight.btnPaneMic.setOnClickListener { onMicTap() }
        lb.paneLeft.togglePaneContinuous.isChecked = continuousEnabled
        lb.paneRight.togglePaneContinuous.isChecked = continuousEnabled
        lb.paneLeft.togglePaneContinuous.setOnClickListener { onContinuousToggleRequested(lb.paneLeft.togglePaneContinuous.isChecked) }
        lb.paneRight.togglePaneContinuous.setOnClickListener { onContinuousToggleRequested(lb.paneRight.togglePaneContinuous.isChecked) }
        applyContinuousUiState()
        lb.paneLeft.textPaneStatus.text = statusText
        lb.paneRight.textPaneStatus.text = statusText
        paneAAdapter = TranscriptAdapter(TranscriptAdapter.Mode.PANE) { entry -> reassignTurn(entry) }
        paneBAdapter = TranscriptAdapter(TranscriptAdapter.Mode.PANE) { entry -> reassignTurn(entry) }
        lb.paneLeft.recyclerPaneTranscript.layoutManager = LinearLayoutManager(requireContext())
        lb.paneLeft.recyclerPaneTranscript.adapter = paneAAdapter
        lb.paneRight.recyclerPaneTranscript.layoutManager = LinearLayoutManager(requireContext())
        lb.paneRight.recyclerPaneTranscript.adapter = paneBAdapter
        updateTurnIndicator()
        refreshRecordingsList()
        refreshTranscriptViews()
    }

    // ---------------------------------------------------------------------
    // Language / turn helpers - identical semantics to before this pass,
    // just reading fields instead of a live Spinner selection.
    // ---------------------------------------------------------------------

    private fun langA() = langACode
    private fun langB() = langBCode
    private fun speakerLang() = if (turnIsA) langA() else langB()
    private fun listenerLang() = if (turnIsA) langB() else langA()
    private fun selectedGender(): VoiceGender = if (genderIsMale) VoiceGender.MALE else VoiceGender.FEMALE

    private fun updateTurnIndicator() {
        val name = if (turnIsA) LanguageCatalog.displayNameFor(langA()) else LanguageCatalog.displayNameFor(langB())
        val who = if (turnIsA) "Person A" else "Person B"
        val text = "$who's turn — speak $name"
        fallbackBinding?.textTurnIndicator?.text = text
        mirroredBinding?.paneTop?.textPaneTurnIndicator?.text = text
        mirroredBinding?.paneBottom?.textPaneTurnIndicator?.text = text
        largeBinding?.paneLeft?.textPaneTurnIndicator?.text = text
        largeBinding?.paneRight?.textPaneTurnIndicator?.text = text
    }

    private fun setStatus(text: String) {
        statusText = text
        fallbackBinding?.textConversationStatus?.text = text
        mirroredBinding?.paneTop?.textPaneStatus?.text = text
        mirroredBinding?.paneBottom?.textPaneStatus?.text = text
        largeBinding?.paneLeft?.textPaneStatus?.text = text
        largeBinding?.paneRight?.textPaneStatus?.text = text
    }

    // ---------------------------------------------------------------------
    // Transcript - see class doc's "Tap-to-fix reassign affordance" section.
    // addTurn/addTranslation/addFailureNote are the only ways entries get
    // added (called from onMicTap's onFinal for manual turns and from
    // continuousListener.onUtteranceFinal for auto-detected turns);
    // reassignTurn is the only way an entry's side ever changes after the
    // fact. Every mutation ends in refreshTranscriptViews(), which is the
    // only place that talks to the actual RecyclerView adapters - so there's
    // exactly one path from "the data changed" to "the UI reflects it",
    // regardless of which of the three layouts is currently inflated.
    // ---------------------------------------------------------------------

    /** Starts a new turn: the speaker's original transcribed words. Returns the turnId its translation (or failure note) should share. */
    private fun addTurn(speakerIsA: Boolean, text: String, langCode: String, auto: Boolean, basis: String? = null): Long {
        val turnId = nextTurnId++
        transcriptEntries.add(
            TranscriptEntry(
                id = nextEntryId++, turnId = turnId, speakerIsA = speakerIsA, own = true,
                text = text, langCode = langCode, auto = auto, basis = basis
            )
        )
        refreshTranscriptViews()
        return turnId
    }

    /** Adds the translation half of a turn started by [addTurn]. Looks up the turn's CURRENT speakerIsA rather than trusting a captured value, in case a reassign tap landed in the gap while translation was in flight. */
    private fun addTranslation(turnId: Long, text: String, langCode: String, auto: Boolean) {
        transcriptEntries.add(
            TranscriptEntry(
                id = nextEntryId++, turnId = turnId, speakerIsA = currentSpeakerIsA(turnId), own = false,
                text = text, langCode = langCode, auto = auto
            )
        )
        refreshTranscriptViews()
    }

    /** Adds a non-reassignable failure note in place of a translation - same format the old appendCombinedTranscript("   (translation failed: ...)") line used, now shown in every layout instead of just the fallback one. */
    private fun addFailureNote(turnId: Long, text: String, langCode: String) {
        transcriptEntries.add(
            TranscriptEntry(
                id = nextEntryId++, turnId = turnId, speakerIsA = currentSpeakerIsA(turnId), own = false,
                text = text, langCode = langCode, auto = false, failed = true
            )
        )
        refreshTranscriptViews()
    }

    private fun currentSpeakerIsA(turnId: Long): Boolean =
        transcriptEntries.firstOrNull { it.turnId == turnId }?.speakerIsA ?: true

    /**
     * The reassign affordance itself (docs/specs/fold5-adaptation.md §4):
     * flips [TranscriptEntry.speakerIsA] to the SAME new value on every
     * entry sharing [entry]'s turnId (never toggled independently per
     * entry), so the original-speech bubble and its translation always move
     * together to the opposite pane. Called from a tapped bubble in any of
     * the three layouts' RecyclerViews (see TranscriptAdapter's onReassign).
     *
     * Replaces matching entries with `.copy(speakerIsA = ...)` at their
     * existing index rather than mutating a field in place - see
     * [TranscriptEntry]'s doc comment for why in-place mutation silently
     * broke this exact feature (DiffUtil couldn't tell the "old" and "new"
     * item apart when both were the same object instance).
     */
    private fun reassignTurn(entry: TranscriptEntry) {
        val newSpeakerIsA = !entry.speakerIsA
        var changed = false
        for (i in transcriptEntries.indices) {
            val e = transcriptEntries[i]
            if (e.turnId == entry.turnId) {
                transcriptEntries[i] = e.copy(speakerIsA = newSpeakerIsA)
                changed = true
            }
        }
        if (changed) {
            Log.i(TAG, "reassign: turnId=${entry.turnId} -> ${if (newSpeakerIsA) "A" else "B"}")
            refreshTranscriptViews()
        }
    }

    /** The one place that pushes [transcriptEntries] out to whichever layout's adapter(s) are currently live. */
    private fun refreshTranscriptViews() {
        if (fallbackBinding != null) {
            fallbackAdapter?.submitList(transcriptEntries.toList())
        }
        // Newest first, per pane - matches the pre-RecyclerView behavior
        // (view_conversation_pane.xml's comment on inserting newest at the
        // top so it lands nearest the hinge).
        val paneAList = transcriptEntries.filter { it.paneIsA }.asReversed()
        val paneBList = transcriptEntries.filterNot { it.paneIsA }.asReversed()
        mirroredBinding?.let { mb ->
            paneAAdapter?.submitList(paneAList) { mb.paneTop.recyclerPaneTranscript.scrollToPosition(0) }
            paneBAdapter?.submitList(paneBList) { mb.paneBottom.recyclerPaneTranscript.scrollToPosition(0) }
        }
        largeBinding?.let { lb ->
            paneAAdapter?.submitList(paneAList) { lb.paneLeft.recyclerPaneTranscript.scrollToPosition(0) }
            paneBAdapter?.submitList(paneBList) { lb.paneRight.recyclerPaneTranscript.scrollToPosition(0) }
        }
    }

    // ---------------------------------------------------------------------
    // Mic / recognition / translation flow - unchanged logic from before
    // this pass, just reading state from fields and writing through the
    // setStatus/addTurn/addTranslation/addFailureNote helpers above so it
    // works identically regardless of which layout is currently inflated.
    // ---------------------------------------------------------------------

    private fun onMicTap() {
        val activity = mainActivity ?: return
        val app = activity.app
        if (app.mic.isRunning()) {
            app.mic.stop()
            return
        }
        if (!activity.hasMicPermission()) {
            activity.requestMicPermissionIfNeeded()
            Toast.makeText(requireContext(), "Grant microphone permission, then tap again", Toast.LENGTH_LONG).show()
            return
        }
        val code = speakerLang()
        if (VoskModelCatalog.forLanguage(code) == null) {
            Toast.makeText(requireContext(), "No offline voice-input model for ${LanguageCatalog.displayNameFor(code)}", Toast.LENGTH_LONG).show()
            return
        }
        if (!app.vosk.isModelDownloaded(code)) {
            Toast.makeText(requireContext(), "Download the voice-input pack for ${LanguageCatalog.displayNameFor(code)} on the Translate tab first", Toast.LENGTH_LONG).show()
            return
        }

        setStatus("Loading model…")
        app.vosk.loadModelAsync(code) { success, error ->
            if (contentContainer == null) return@loadModelAsync
            if (!success) {
                setStatus("")
                Toast.makeText(requireContext(), "Couldn't load model: $error", Toast.LENGTH_LONG).show()
                return@loadModelAsync
            }
            val recognizer = app.vosk.newRecognizer()
            if (recognizer == null) {
                setStatus("")
                Toast.makeText(requireContext(), "Couldn't start recognizer", Toast.LENGTH_LONG).show()
                return@loadModelAsync
            }
            val recordFile: File? = if (recordSessionEnabled) {
                recordingsStore.newFile(if (turnIsA) "A" else "B")
            } else null

            setStatus("Listening…")
            app.mic.start(recognizer, recordFile, object : MicPipeline.Listener {
                override fun onFinal(text: String) {
                    if (contentContainer == null) return
                    setStatus("")
                    val speakerIsA = turnIsA
                    val srcCode = speakerLang()
                    val dstCode = listenerLang()
                    val turnId = addTurn(speakerIsA, text, srcCode, auto = false)
                    TranslationEngine.translate(requireContext(), srcCode, dstCode, text,
                        onResult = onResult@{ translated ->
                            if (contentContainer == null) return@onResult
                            addTranslation(turnId, translated, dstCode, auto = false)
                            val router = mainActivity?.app?.tts
                            router?.speak(translated, dstCode, selectedGender(), onDone = { switchTurn() }, onError = { switchTurn() })
                                ?: switchTurn()
                        },
                        onError = onError@{ err ->
                            if (contentContainer == null) return@onError
                            addFailureNote(turnId, "(translation failed: $err)", dstCode)
                            switchTurn()
                        }
                    )
                }
                override fun onError(message: String) {
                    if (contentContainer != null) {
                        setStatus("")
                        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                    }
                }
                override fun onRecordingSaved(file: File, bytes: Long) {
                    refreshRecordingsList()
                }
            })
        }
    }

    private fun switchTurn() {
        turnIsA = !turnIsA
        if (contentContainer != null) updateTurnIndicator()
    }

    // ---------------------------------------------------------------------
    // Continuous listening (see field doc comment above). Fully separate
    // entry point from onMicTap()/switchTurn() above - manual tap-to-talk
    // keeps working exactly as before when this is off.
    // ---------------------------------------------------------------------

    private fun applyContinuousUiState() {
        val fb = fallbackBinding
        if (fb != null) {
            fb.spinnerLangA.isEnabled = !continuousEnabled
            fb.spinnerLangB.isEnabled = !continuousEnabled
            fb.btnConversationMic.isEnabled = !continuousEnabled
            fb.btnConversationMic.alpha = if (continuousEnabled) 0.4f else 1f
            if (fb.toggleContinuousListening.isChecked != continuousEnabled) fb.toggleContinuousListening.isChecked = continuousEnabled
        }
        val mb = mirroredBinding
        if (mb != null) {
            mb.paneTop.btnPaneMic.isEnabled = !continuousEnabled
            mb.paneBottom.btnPaneMic.isEnabled = !continuousEnabled
            mb.paneTop.btnPaneMic.alpha = if (continuousEnabled) 0.4f else 1f
            mb.paneBottom.btnPaneMic.alpha = if (continuousEnabled) 0.4f else 1f
            if (mb.paneTop.togglePaneContinuous.isChecked != continuousEnabled) mb.paneTop.togglePaneContinuous.isChecked = continuousEnabled
            if (mb.paneBottom.togglePaneContinuous.isChecked != continuousEnabled) mb.paneBottom.togglePaneContinuous.isChecked = continuousEnabled
        }
        val lb = largeBinding
        if (lb != null) {
            lb.paneLeft.btnPaneMic.isEnabled = !continuousEnabled
            lb.paneRight.btnPaneMic.isEnabled = !continuousEnabled
            lb.paneLeft.btnPaneMic.alpha = if (continuousEnabled) 0.4f else 1f
            lb.paneRight.btnPaneMic.alpha = if (continuousEnabled) 0.4f else 1f
            if (lb.paneLeft.togglePaneContinuous.isChecked != continuousEnabled) lb.paneLeft.togglePaneContinuous.isChecked = continuousEnabled
            if (lb.paneRight.togglePaneContinuous.isChecked != continuousEnabled) lb.paneRight.togglePaneContinuous.isChecked = continuousEnabled
        }
    }

    private fun onContinuousToggleRequested(wantOn: Boolean) {
        // Fold5 edition (see maybeApplyFold5ContinuousDefault's doc): a real
        // explicit tap on the toggle, regardless of outcome below, is what
        // "the user has set this themselves" means - recorded unconditionally
        // here, the single choke point all five real toggle click listeners
        // (fallback/mirrored-top/mirrored-bottom/large-left/large-right) go
        // through, so the cold-launch default is never re-applied after this.
        LayoutPreferences.markConversationsContinuousUserSet(requireContext())
        if (wantOn == continuousEnabled || continuousLoading) {
            applyContinuousUiState() // snap any stray toggle tap back in sync
            return
        }
        if (wantOn) startContinuousMode() else stopContinuousMode()
    }

    private fun startContinuousMode() {
        val activity = mainActivity ?: return
        if (!activity.hasMicPermission()) {
            activity.requestMicPermissionIfNeeded()
            Toast.makeText(requireContext(), "Grant microphone permission, then try again", Toast.LENGTH_LONG).show()
            applyContinuousUiState()
            return
        }
        val a = langACode
        val b = langBCode
        if (VoskModelCatalog.forLanguage(a) == null || VoskModelCatalog.forLanguage(b) == null) {
            Toast.makeText(requireContext(), "No offline voice-input model for one of these languages", Toast.LENGTH_LONG).show()
            applyContinuousUiState()
            return
        }
        if (mainActivity?.app?.mic?.isRunning() == true) {
            Toast.makeText(requireContext(), "Stop the current recording first", Toast.LENGTH_SHORT).show()
            applyContinuousUiState()
            return
        }

        continuousLoading = true
        setStatus("Loading models for continuous listening…")
        val engineA = continuousEngineA ?: VoskEngine(requireContext()).also { continuousEngineA = it }
        val engineB = continuousEngineB ?: VoskEngine(requireContext()).also { continuousEngineB = it }
        engineA.loadModelAsync(a) { okA, errA ->
            if (contentContainer == null) return@loadModelAsync
            if (!okA) {
                continuousLoading = false
                setStatus("")
                Toast.makeText(requireContext(), "Couldn't load ${LanguageCatalog.displayNameFor(a)} model: $errA", Toast.LENGTH_LONG).show()
                applyContinuousUiState()
                return@loadModelAsync
            }
            engineB.loadModelAsync(b) innerLoad@{ okB, errB ->
                if (contentContainer == null) return@innerLoad
                continuousLoading = false
                if (!okB) {
                    setStatus("")
                    Toast.makeText(requireContext(), "Couldn't load ${LanguageCatalog.displayNameFor(b)} model: $errB", Toast.LENGTH_LONG).show()
                    applyContinuousUiState()
                    return@innerLoad
                }
                val controller = ContinuousConversationController(engineA, a, engineB, b, continuousListener)
                continuousController = controller
                continuousEnabled = true
                applyContinuousUiState()
                // Start the wake-lock/foreground-service backing (see
                // ContinuousListeningService's class doc) BEFORE the mic
                // pipeline itself, so the CPU-wake guarantee and Android
                // 14's mic-typed foreground-service requirement are both in
                // place before the first audio chunk can arrive, not raced
                // against it.
                startContinuousListeningService()
                mainActivity?.app?.mic?.startContinuousListening(controller.micListener)
                setStatus("Listening… (auto-detects ${LanguageCatalog.displayNameFor(a)} / ${LanguageCatalog.displayNameFor(b)})")
            }
        }
    }

    private fun stopContinuousMode() {
        mainActivity?.app?.mic?.stop()
        continuousController?.reset()
        continuousEnabled = false
        stopContinuousListeningService()
        setStatus("")
        applyContinuousUiState()
    }

    /** Frees the two dedicated continuous-mode models. Called on tab switch/destroy, NOT on a plain toggle-off (so flipping the toggle back and forth doesn't reload ~80MB of model every time). */
    private fun releaseContinuousEngines() {
        mainActivity?.app?.mic?.stop()
        continuousController?.reset()
        continuousController = null
        continuousEnabled = false
        stopContinuousListeningService()
        continuousEngineA?.release()
        continuousEngineB?.release()
        continuousEngineA = null
        continuousEngineB = null
    }

    /** See ContinuousListeningService's class doc for the full lifecycle contract this pairs with. */
    private fun startContinuousListeningService() {
        val ctx = context ?: return
        ContextCompat.startForegroundService(ctx, Intent(ctx, ContinuousListeningService::class.java))
    }

    /** Safe to call even if the service was never started (no-op) - every stop path below calls this unconditionally rather than tracking "did we start it" separately. */
    private fun stopContinuousListeningService() {
        val ctx = context ?: return
        ctx.stopService(Intent(ctx, ContinuousListeningService::class.java))
    }

    private val continuousListener = object : ContinuousConversationController.Listener {
        override fun onListeningStateChanged(listening: Boolean) {
            if (contentContainer == null) return
            Log.d(TAG, "continuous: listening=$listening")
        }

        override fun onSpeechStart() {
            if (contentContainer == null) return
            setStatus("Listening…")
        }

        override fun onEarlyLanguageGuess(guessedLang: String, partialText: String, elapsedSinceSpeechStartMs: Long) {
            if (contentContainer == null) return
            setStatus("Hearing ${LanguageCatalog.displayNameFor(guessedLang)}: \"$partialText\"…")
        }

        override fun onEmptyUtterance() {
            if (contentContainer == null) return
            setStatus("Listening… (auto-detects ${LanguageCatalog.displayNameFor(langACode)} / ${LanguageCatalog.displayNameFor(langBCode)})")
        }

        override fun onError(message: String) {
            if (contentContainer == null) return
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }

        override fun onUtteranceFinal(result: ContinuousConversationController.UtteranceResult) {
            if (contentContainer == null) return
            setStatus("Translating…")
            val speakerIsA = result.pickedLang == langACode
            val turnId = addTurn(speakerIsA, result.text, result.pickedLang, auto = true, basis = result.decisionBasis)

            val dstCode = result.otherLang
            TranslationEngine.translate(requireContext(), result.pickedLang, dstCode, result.text,
                onResult = onResult@{ translated ->
                    if (contentContainer == null) return@onResult
                    val translateDoneNanos = System.nanoTime()
                    setStatus("Listening… (auto-detects ${LanguageCatalog.displayNameFor(langACode)} / ${LanguageCatalog.displayNameFor(langBCode)})")
                    addTranslation(turnId, translated, dstCode, auto = true)
                    val router = mainActivity?.app?.tts ?: return@onResult
                    router.speak(
                        translated, dstCode, selectedGender(),
                        onDone = {},
                        onError = { err ->
                            if (contentContainer != null) Toast.makeText(requireContext(), "Speech failed: $err", Toast.LENGTH_SHORT).show()
                        },
                        onAudioStart = {
                            // Real, measured end-to-end latency (task item 3):
                            // speech-end (VAD boundary, result.speechEndNanos)
                            // -> the first genuine TTS PCM byte reaching the
                            // speaker (see TtsRouter.speak's onAudioStart doc).
                            // Logged, not just asserted - see this project's
                            // task report for the actual numbers this produced
                            // on-device.
                            val audioStartNanos = System.nanoTime()
                            val speechEndToAudioStartMs = (audioStartNanos - result.speechEndNanos) / 1_000_000
                            val speechEndToTranslateDoneMs = (translateDoneNanos - result.speechEndNanos) / 1_000_000
                            Log.i(
                                TAG,
                                "CONTINUOUS_LATENCY speechEndToTtsAudioStartMs=$speechEndToAudioStartMs " +
                                    "speechEndToTranslateDoneMs=$speechEndToTranslateDoneMs " +
                                    "sttDecodeWallTimeMs=${result.decodeWallTimeMs} " +
                                    "pickedLang=${result.pickedLang} basis=\"${result.decisionBasis}\" " +
                                    "earlyGuess=${result.earlyGuessLang} earlyGuessElapsedMs=${result.earlyGuessElapsedMs} " +
                                    "earlyGuessMatchedFinal=${result.earlyGuessLang == result.pickedLang}"
                            )
                        }
                    )
                },
                onError = onError@{ err ->
                    if (contentContainer == null) return@onError
                    setStatus("Listening… (auto-detects ${LanguageCatalog.displayNameFor(langACode)} / ${LanguageCatalog.displayNameFor(langBCode)})")
                    addFailureNote(turnId, "(translation failed: $err)", dstCode)
                }
            )
        }
    }

    // ---------------------------------------------------------------------
    // Saved recordings list - fallback and large-screen layouts only
    // (session file management, not part of the in-the-moment mirrored
    // conversing view - the mirrored fold layout never showed this either).
    // ---------------------------------------------------------------------

    private fun refreshRecordingsList() {
        val list = fallbackBinding?.recordingsList ?: largeBinding?.recordingsList ?: return
        list.removeAllViews()
        val files = recordingsStore.list()
        if (files.isEmpty()) {
            val tv = TextView(requireContext())
            tv.text = "No recordings yet."
            tv.textSize = 12f
            list.addView(tv)
            return
        }
        for (f in files) {
            val row = ItemRecordingBinding.inflate(layoutInflater, list, false)
            row.textRecordingName.text = f.name
            row.btnPlay.setOnClickListener { playRecording(f) }
            row.btnDelete.setOnClickListener {
                recordingsStore.delete(f)
                refreshRecordingsList()
            }
            list.addView(row.root)
        }
    }

    private fun playRecording(file: File) {
        try {
            player?.release()
            val mp = MediaPlayer()
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener { it.release() }
            mp.prepare()
            mp.start()
            player = mp
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Playback failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPause() {
        super.onPause()
        // Deliberate lifecycle divergence, disclosed here (wake-lock
        // reliability fix, docs/specs/fold5-adaptation.md §4): this used to
        // unconditionally call mic.stop() + stopContinuousMode() here,
        // which also fires on a mere screen lock (Fragment.onPause() runs
        // whenever the hosting Activity pauses, including screen-off via
        // the power button, not just real navigation away) - meaning
        // continuous listening was being silently torn down by this exact
        // code the moment the screen locked, which is the precise failure
        // mode ContinuousListeningService now exists to survive. Tearing it
        // down here would make that fix a no-op, since onPause would always
        // win the race before the service/wake lock ever got a chance to
        // matter.
        //
        // Tap-to-talk (non-continuous) capture has no such requirement -
        // there is no expectation a one-shot recording should keep running
        // through a screen lock, so it still stops here exactly as before.
        // Continuous listening now only stops via: explicit toggle-off
        // (stopContinuousMode, still calls mic.stop() itself), an
        // unrecoverable error (startContinuousMode's own revert paths,
        // which never reach continuousEnabled = true in the first place),
        // the Fragment's view actually going away (onDestroyView ->
        // releaseContinuousEngines, e.g. switching tabs or the Activity
        // being torn down for real), or the app being swiped away from
        // Recents entirely (ContinuousListeningService.onTaskRemoved).
        if (!continuousEnabled) {
            mainActivity?.app?.mic?.stop()
        }
        player?.release()
        player = null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        releaseContinuousEngines()
        player?.release()
        player = null
        contentContainer = null
        fallbackBinding = null
        mirroredBinding = null
        largeBinding = null
        fallbackAdapter = null
        paneAAdapter = null
        paneBAdapter = null
        layoutInitialized = false
    }

    companion object {
        private const val TAG = "ConversationsFragment"
    }
}
