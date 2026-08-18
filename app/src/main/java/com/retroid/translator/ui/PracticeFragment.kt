package com.retroid.translator.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.SharedPreferences
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.cardview.widget.CardView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.FoldingFeature
import com.google.android.material.textfield.TextInputEditText
import com.google.mlkit.nl.translate.TranslateLanguage
import com.retroid.translator.MainActivity
import com.retroid.translator.R
import com.retroid.translator.audio.MicPipeline
import com.retroid.translator.audio.RecordingsStore
import com.retroid.translator.databinding.FragmentPracticeBinding
import com.retroid.translator.databinding.FragmentPracticeCoverDrillCarouselBinding
import com.retroid.translator.databinding.FragmentPracticeCoverDrillDeckBinding
import com.retroid.translator.databinding.FragmentPracticeCoverEchoDuetBinding
import com.retroid.translator.databinding.FragmentPracticeFlexDefaultBinding
import com.retroid.translator.databinding.FragmentPracticeFlexLoopCompareBinding
import com.retroid.translator.databinding.FragmentPracticeFlexPhraseFeedBinding
import com.retroid.translator.databinding.FragmentPracticeFlexWaveformWallBinding
import com.retroid.translator.databinding.ItemPracticeFeedCardBinding
import com.retroid.translator.databinding.ItemPracticePhraseRowBinding
import com.retroid.translator.databinding.ItemPracticeRailPhraseBinding
import com.retroid.translator.databinding.ItemPracticeWaveformThumbBinding
import com.retroid.translator.databinding.ItemRecordingBinding
import com.retroid.translator.engine.LanguageCatalog
import com.retroid.translator.engine.VoiceGender
import com.retroid.translator.engine.VoicePreferences
import com.retroid.translator.fold.FoldPosture
import com.retroid.translator.fold.FoldPostureProvider
import com.retroid.translator.fold.FoldState
import com.retroid.translator.practice.PracticePhrase
import com.retroid.translator.practice.WaveformReader
import com.retroid.translator.settings.FoldAwareLayoutHost
import com.retroid.translator.settings.LayoutPreferences
import com.retroid.translator.settings.ScreenMode
import com.retroid.translator.settings.SettingsTab
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Which of this tab's 8 real layouts is currently inflated into [PracticeFragment]'s container - at most one at a time. */
private enum class PracticeActiveLayout {
    DEFAULT, COVER_DRILL_DECK, COVER_ECHO_DUET, COVER_DRILL_CAROUSEL,
    FLEX_DEFAULT, FLEX_WAVEFORM_WALL, FLEX_LOOP_COMPARE, FLEX_PHRASE_FEED
}

/**
 * "drill_carousel"'s per-card action-button state. NOT a literal one-tap-per-
 * state cycle: recording genuinely needs a start-tap and a separate stop-tap
 * (MicPipeline has no auto-stop when no recognizer is attached), so the real
 * tap sequence on one card is [hear reference] -> [start recording] ->
 * [stop recording, auto-advances] -> [hear mine, wraps back to
 * HEAR_REFERENCE]. See [PracticeFragment.performCarouselAction].
 */
private enum class CarouselStep { HEAR_REFERENCE, RECORD, HEAR_MINE }

/** "loop_compare"'s hands-free alternation state. */
private enum class LoopStep { REFERENCE, MINE }

/**
 * Practice tab. Implements the per-tab layout-variant system from the
 * settings foundation (docs/specs/fold5-adaptation.md's task-item-worth of
 * work covering ONLY this tab, following the precedent `TranslateFragment`
 * already established for the Translate tab): 4 real cover-screen variants +
 * 4 real Flex-Mode (tabletop) variants, on top of the tab's pre-existing
 * default (full, book-portrait) layout - 8 real, functioning layouts total,
 * all reading and writing the exact same underlying practice state (current
 * phrase, gender, saved recordings, session attempt counts).
 *
 * Deliberate deviation worth flagging up front, identical to
 * `TranslateFragment`'s own doc comment: docs/specs/fold5-adaptation.md's
 * Scope table lists Practice as "Responsive scaling only" - no bespoke
 * fold-aware layout, that treatment reserved for Conversations alone. This
 * file's existence is a direct instruction from the task that commissioned
 * it, which explicitly asks for bespoke Practice cover/Flex layouts - it
 * supersedes the spec's Scope table for this tab. Book-portrait/non-fold
 * behavior (the spec's actual "responsive scaling only" case) is preserved
 * unchanged as [PracticeActiveLayout.DEFAULT] (the original `fragment_practice.xml`,
 * untouched).
 *
 * Rendering model - same dynamic-container technique `TranslateFragment` and
 * `ConversationsFragment` already use: [onCreateView] returns a bare
 * [FrameLayout] mount point, not a static XML; which real layout is
 * currently inflated into it is decided live by [renderPracticeActiveLayout], driven
 * by two independent signals:
 *  - **Flex Mode**: this Fragment's own [FoldPostureProvider] subscription -
 *    [FoldPosture.isMirroredTabletop] selects the user's configured
 *    [ScreenMode.FLEX] variant.
 *  - **Cover screen**: [FoldAwareLayoutHost] ([applyCoverLayout]/
 *    [applyDefaultLayout]), pushed by `MainActivity`'s existing fold-close
 *    heuristic and the Fold behavior screen's manual force-compact toggle -
 *    both already built by the settings foundation, zero `MainActivity`
 *    changes needed here.
 *
 * All 8 layouts are pure presentations of one shared set of state fields
 * (selected language/gender, the current phrase, the session drill queue,
 * real recorded-attempt files, real per-phrase session attempt counts) -
 * there is exactly one copy of that state, so switching layouts mid-flow
 * (e.g. folding the device mid-recording) never loses or duplicates it.
 * [MicPipeline] allows exactly one capture app-wide, so [switchTo] always
 * force-stops it before swapping views out from under it, same as
 * `TranslateFragment.switchTo`.
 */
class PracticeFragment : Fragment(), FoldAwareLayoutHost {

    override val settingsTab: SettingsTab = SettingsTab.PRACTICE

    // ---------------------------------------------------------------------
    // Session state - the single source of truth, independent of whichever
    // of the 8 layouts is currently inflated.
    // ---------------------------------------------------------------------

    private lateinit var languageCodes: List<String>
    private var selectedLanguageCode: String = TranslateLanguage.ENGLISH
    private var genderMale = false

    /** The one "current phrase" every single-phrase layout (default/echo_duet/flex_default/loop_compare) shares, kept in sync live via [watchPhraseText]. */
    private var currentPhraseText = ""
    private var lastAttempt: File? = null
    private var micStatusText = ""
    private var naturalVoiceStatusText = "eSpeak (built-in, robotic, always available) is used unless a natural voice is downloaded."
    private var recordingTargetPhrase = ""

    /** Session-only drill queue backing "drill_deck", "drill_carousel" and "waveform_wall"'s rail - see [PracticePhrase]'s doc comment. */
    private val phraseQueue = mutableListOf<PracticePhrase>()
    private var carouselIndex = 0
    private var carouselStep = CarouselStep.HEAR_REFERENCE
    private var waveformActiveIndex = 0

    /** Real, session-only per-phrase attempt data - shared by drill_deck's marker, echo_duet's rep counter, waveform_wall's badges/replay and phrase_feed's per-card caption. Keyed by trimmed phrase text. */
    private val sessionAttemptCounts = mutableMapOf<String, Int>()
    private val sessionLastAttemptByPhrase = mutableMapOf<String, File>()

    private var recentRecordings: List<File> = emptyList()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    // loop_compare
    private var loopActive = false
    private var loopStep = LoopStep.REFERENCE
    private val loopHandler = Handler(Looper.getMainLooper())

    // echo_duet ring-pulse animators (an animated pulse, not a literal live-
    // amplitude trace - see fragment_practice_cover_echo_duet.xml's comment)
    private var echoReferenceAnimator: ValueAnimator? = null
    private var echoMineAnimator: ValueAnimator? = null

    // phrase_feed scroll-snap
    private val feedCardViews = mutableListOf<View>()
    private var feedActiveIndex = 0
    private var feedProgrammaticScroll = false
    private val feedSettleRunnable = Runnable { snapPhraseFeedToNearestCard() }

    private lateinit var recordingsStore: RecordingsStore
    private var player: MediaPlayer? = null

    private val mainActivity get() = activity as? MainActivity
    private lateinit var foldPostureProvider: FoldPostureProvider
    private var layoutPrefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    private var currentPosture: FoldPosture = FoldPosture.NO_FOLDING_FEATURE
    private var currentFeature: FoldingFeature? = null
    private var coverForced = false
    private var currentActive: PracticeActiveLayout? = null

    // ---------------------------------------------------------------------
    // View plumbing - exactly one of these 8 is non-null at a time.
    // ---------------------------------------------------------------------

    private var contentContainer: FrameLayout? = null
    private var defaultBinding: FragmentPracticeBinding? = null
    private var drillDeckBinding: FragmentPracticeCoverDrillDeckBinding? = null
    private var echoDuetBinding: FragmentPracticeCoverEchoDuetBinding? = null
    private var drillCarouselBinding: FragmentPracticeCoverDrillCarouselBinding? = null
    private var flexDefaultBinding: FragmentPracticeFlexDefaultBinding? = null
    private var waveformWallBinding: FragmentPracticeFlexWaveformWallBinding? = null
    private var loopCompareBinding: FragmentPracticeFlexLoopCompareBinding? = null
    private var phraseFeedBinding: FragmentPracticeFlexPhraseFeedBinding? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = FrameLayout(requireContext())
        root.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        contentContainer = root
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recordingsStore = RecordingsStore(requireContext(), "practice")
        languageCodes = LanguageCatalog.codes
        selectedLanguageCode = languageCodes.indexOf(TranslateLanguage.ENGLISH).let { if (it >= 0) languageCodes[it] else languageCodes[0] }
        genderMale = VoicePreferences.getGender(requireContext()) == VoiceGender.MALE
        coverForced = LayoutPreferences.isForceCompactLayoutEnabled(requireContext())
        refreshRecordingsCache()

        registerLayoutPrefsListener()
        // Render once immediately with what we know so far (book-portrait/
        // default, or cover if force-compact is already on) so the screen is
        // never blank waiting on the first FoldingFeature emission below.
        renderPracticeActiveLayout()
        observeFoldPosture()
    }

    // ---------------------------------------------------------------------
    // FoldAwareLayoutHost - pushed by MainActivity's existing fold-close
    // heuristic and the Fold behavior screen's manual force-compact toggle.
    // ---------------------------------------------------------------------

    override fun applyCoverLayout(variantId: String) {
        coverForced = true
        if (contentContainer != null) renderPracticeActiveLayout()
    }

    override fun applyDefaultLayout() {
        coverForced = false
        if (contentContainer != null) renderPracticeActiveLayout()
    }

    // ---------------------------------------------------------------------
    // Flex Mode - this Fragment's own FoldPostureProvider subscription,
    // same pattern as ConversationsFragment/TranslateFragment.
    // ---------------------------------------------------------------------

    private fun observeFoldPosture() {
        foldPostureProvider = FoldPostureProvider(requireActivity())
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                foldPostureProvider.postureFlow().collect { state -> onPostureChanged(state) }
            }
        }
    }

    private fun onPostureChanged(state: FoldState) {
        Log.d(TAG, "posture=${state.posture} coverForced=$coverForced currentActive=$currentActive")
        currentPosture = state.posture
        currentFeature = state.feature
        renderPracticeActiveLayout()
    }

    // ---------------------------------------------------------------------
    // Live settings change - reads the SAME SharedPreferences file
    // LayoutPreferences uses (by name, not by touching that foundation
    // file), same technique TranslateFragment already established.
    // ---------------------------------------------------------------------

    private fun registerLayoutPrefsListener() {
        val prefs = requireContext().applicationContext.getSharedPreferences(LAYOUT_PREFS_FILE_NAME, Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == LAYOUT_KEY_COVER || key == LAYOUT_KEY_FLEX) {
                if (contentContainer != null) renderPracticeActiveLayout()
            }
        }
        layoutPrefsListener = listener
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    private fun unregisterLayoutPrefsListener() {
        val listener = layoutPrefsListener ?: return
        requireContext().applicationContext.getSharedPreferences(LAYOUT_PREFS_FILE_NAME, Context.MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(listener)
        layoutPrefsListener = null
    }

    // ---------------------------------------------------------------------
    // Layout selection / switching
    // ---------------------------------------------------------------------

    private fun coverPracticeActiveLayout(variantId: String): PracticeActiveLayout = when (variantId) {
        PracticeCoverVariant.DRILL_DECK -> PracticeActiveLayout.COVER_DRILL_DECK
        PracticeCoverVariant.ECHO_DUET -> PracticeActiveLayout.COVER_ECHO_DUET
        PracticeCoverVariant.DRILL_CAROUSEL -> PracticeActiveLayout.COVER_DRILL_CAROUSEL
        else -> PracticeActiveLayout.DEFAULT
    }

    private fun flexPracticeActiveLayout(variantId: String): PracticeActiveLayout = when (variantId) {
        PracticeFlexVariant.WAVEFORM_WALL -> PracticeActiveLayout.FLEX_WAVEFORM_WALL
        PracticeFlexVariant.LOOP_COMPARE -> PracticeActiveLayout.FLEX_LOOP_COMPARE
        PracticeFlexVariant.PHRASE_FEED -> PracticeActiveLayout.FLEX_PHRASE_FEED
        else -> PracticeActiveLayout.FLEX_DEFAULT
    }

    private fun renderPracticeActiveLayout() {
        val target = when {
            coverForced -> coverPracticeActiveLayout(LayoutPreferences.getVariant(requireContext(), SettingsTab.PRACTICE, ScreenMode.COVER))
            currentPosture.isMirroredTabletop -> flexPracticeActiveLayout(LayoutPreferences.getVariant(requireContext(), SettingsTab.PRACTICE, ScreenMode.FLEX))
            else -> PracticeActiveLayout.DEFAULT
        }
        if (target == currentActive) {
            refreshAllContent()
            currentFeature?.let { applyFlexGeometryIfNeeded(it) }
            return
        }
        switchTo(target)
    }

    private fun switchTo(target: PracticeActiveLayout) {
        val container = contentContainer ?: return
        // Stop any in-flight mic session / hands-free loop before swapping
        // views out from under them - MicPipeline allows exactly one
        // concurrent capture app-wide.
        mainActivity?.app?.mic?.stop()
        stopLoopCompare()
        stopEchoAnimators()
        phraseFeedBinding?.scrollPhraseFeed?.removeCallbacks(feedSettleRunnable)
        container.removeAllViews()
        defaultBinding = null
        drillDeckBinding = null
        echoDuetBinding = null
        drillCarouselBinding = null
        flexDefaultBinding = null
        waveformWallBinding = null
        loopCompareBinding = null
        phraseFeedBinding = null

        when (target) {
            PracticeActiveLayout.DEFAULT -> {
                val b = FragmentPracticeBinding.inflate(layoutInflater, container, false)
                defaultBinding = b; container.addView(b.root); bindDefault(b)
            }
            PracticeActiveLayout.COVER_DRILL_DECK -> {
                val b = FragmentPracticeCoverDrillDeckBinding.inflate(layoutInflater, container, false)
                drillDeckBinding = b; container.addView(b.root); bindDrillDeck(b)
            }
            PracticeActiveLayout.COVER_ECHO_DUET -> {
                val b = FragmentPracticeCoverEchoDuetBinding.inflate(layoutInflater, container, false)
                echoDuetBinding = b; container.addView(b.root); bindEchoDuet(b)
            }
            PracticeActiveLayout.COVER_DRILL_CAROUSEL -> {
                val b = FragmentPracticeCoverDrillCarouselBinding.inflate(layoutInflater, container, false)
                drillCarouselBinding = b; container.addView(b.root); bindDrillCarousel(b)
            }
            PracticeActiveLayout.FLEX_DEFAULT -> {
                val b = FragmentPracticeFlexDefaultBinding.inflate(layoutInflater, container, false)
                flexDefaultBinding = b; container.addView(b.root); bindFlexDefault(b)
            }
            PracticeActiveLayout.FLEX_WAVEFORM_WALL -> {
                val b = FragmentPracticeFlexWaveformWallBinding.inflate(layoutInflater, container, false)
                waveformWallBinding = b; container.addView(b.root); bindWaveformWall(b)
            }
            PracticeActiveLayout.FLEX_LOOP_COMPARE -> {
                val b = FragmentPracticeFlexLoopCompareBinding.inflate(layoutInflater, container, false)
                loopCompareBinding = b; container.addView(b.root); bindLoopCompare(b)
            }
            PracticeActiveLayout.FLEX_PHRASE_FEED -> {
                val b = FragmentPracticeFlexPhraseFeedBinding.inflate(layoutInflater, container, false)
                phraseFeedBinding = b; container.addView(b.root); bindPhraseFeed(b)
            }
        }

        // Choreographer-driven crossfade (ViewPropertyAnimator, not a fixed
        // postDelayed loop - spec §5's 120Hz-display note), same technique
        // ConversationsFragment/TranslateFragment already use.
        container.alpha = 0f
        container.animate().alpha(1f).setDuration(200L).start()

        currentActive = target
        refreshAllContent()
        currentFeature?.let { applyFlexGeometryIfNeeded(it) }
    }

    // ---------------------------------------------------------------------
    // Flex-Mode (tabletop) pane geometry - shared by flex_default,
    // waveform_wall and loop_compare (all three split top/bottom at the
    // hinge). phrase_feed deliberately does NOT split - see its own
    // positionPhraseFeedPinnedButton below. Same technique as
    // TranslateFragment.positionFlexPanes: never a static 50/50 split,
    // always derived from the live FoldingFeature.bounds, with an extra
    // inset when occlusionType is FULL to keep content off the physically-
    // occluded crease (task item 5 / spec §2). No pane rotation anywhere in
    // this tab - unlike Conversations/Translate's two-person variants,
    // every Practice layout is single-user.
    // ---------------------------------------------------------------------

    private fun applyFlexGeometryIfNeeded(feature: FoldingFeature) {
        when (currentActive) {
            PracticeActiveLayout.FLEX_DEFAULT -> flexDefaultBinding?.let {
                positionFlexPanes(feature, it.paneViewing.root, it.paneControl.root)
            }
            PracticeActiveLayout.FLEX_WAVEFORM_WALL -> waveformWallBinding?.let {
                positionFlexPanes(feature, it.paneWaveformViewing, it.paneWaveformControl)
            }
            PracticeActiveLayout.FLEX_LOOP_COMPARE -> loopCompareBinding?.let {
                positionFlexPanes(feature, it.paneLoopViewing, it.paneLoopControl)
            }
            PracticeActiveLayout.FLEX_PHRASE_FEED -> phraseFeedBinding?.let {
                positionPhraseFeedPinnedButton(feature, it)
            }
            else -> {}
        }
    }

    private fun positionFlexPanes(feature: FoldingFeature, topView: View, bottomView: View) {
        val container = contentContainer ?: return
        if (container.height == 0) {
            // Not laid out yet (first frame) - defer one pass, same
            // technique as TranslateFragment.positionFlexPanes.
            container.post { if (isAdded) currentFeature?.let { applyFlexGeometryIfNeeded(it) } }
            return
        }
        val loc = IntArray(2)
        container.getLocationInWindow(loc)
        val containerTopInWindow = loc[1]
        val extraInsetPx = if (feature.occlusionType == FoldingFeature.OcclusionType.FULL) {
            (8 * resources.displayMetrics.density).toInt()
        } else 0
        val hingeTopLocal = feature.bounds.top - containerTopInWindow
        val hingeBottomLocal = feature.bounds.bottom - containerTopInWindow
        val topHeight = (hingeTopLocal - extraInsetPx).coerceAtLeast(0)
        val bottomTop = (hingeBottomLocal + extraInsetPx).coerceAtLeast(topHeight)
        val bottomHeight = (container.height - bottomTop).coerceAtLeast(0)

        topView.layoutParams = (topView.layoutParams as FrameLayout.LayoutParams).apply {
            width = FrameLayout.LayoutParams.MATCH_PARENT
            height = topHeight
            topMargin = 0
        }
        bottomView.layoutParams = (bottomView.layoutParams as FrameLayout.LayoutParams).apply {
            width = FrameLayout.LayoutParams.MATCH_PARENT
            height = bottomHeight
            topMargin = bottomTop
        }
    }

    /**
     * "phrase_feed" keeps its pinned record button OFF the physically-
     * occluded hinge crease (task item 5) by anchoring it just below the
     * occlusion-free zone - same reconciliation
     * TranslateFragment.positionMirrorPanesFab already established for a
     * hinge-straddling control. Also re-runs the scroll-snap once real
     * geometry is known, so the very first render reflects the actual hinge
     * position rather than the layout's static fallback.
     */
    private fun positionPhraseFeedPinnedButton(feature: FoldingFeature, b: FragmentPracticeFlexPhraseFeedBinding) {
        val container = contentContainer ?: return
        if (container.height == 0) {
            container.post { if (isAdded) currentFeature?.let { applyFlexGeometryIfNeeded(it) } }
            return
        }
        val loc = IntArray(2)
        container.getLocationInWindow(loc)
        val containerTopInWindow = loc[1]
        val density = resources.displayMetrics.density
        val extraInsetPx = if (feature.occlusionType == FoldingFeature.OcclusionType.FULL) (8 * density).toInt() else 0
        val hingeBottomLocal = feature.bounds.bottom - containerTopInWindow

        (b.cardFeedRecordPinned.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = (hingeBottomLocal + extraInsetPx).coerceAtLeast(0)
        }
        b.cardFeedRecordPinned.requestLayout()

        (b.textFeedPinnedStatus.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = (hingeBottomLocal + extraInsetPx + (64 * density)).toInt()
        }
        b.textFeedPinnedStatus.requestLayout()

        snapPhraseFeedToNearestCard()
    }

    // ---------------------------------------------------------------------
    // Shared phrase / gender / hear / record / play primitives - reused
    // across every one of the 8 layouts (task item 5: these are alternate
    // presentations of existing behavior, not new mock features).
    // ---------------------------------------------------------------------

    private fun selectedGender(): VoiceGender = if (genderMale) VoiceGender.MALE else VoiceGender.FEMALE

    private fun toast(msg: String, long: Boolean = false) {
        if (isAdded) Toast.makeText(requireContext(), msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    private fun refreshRecordingsCache() {
        recentRecordings = recordingsStore.list()
    }

    /** Keeps [currentPhraseText] live-synced from whichever single-phrase field is currently on screen. */
    private fun watchPhraseText(editText: EditText) {
        editText.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                currentPhraseText = s?.toString().orEmpty()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun setupLanguageSpinner(spinner: Spinner) {
        val names = languageCodes.map { LanguageCatalog.displayNameFor(it) }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.setSelection(languageCodes.indexOf(selectedLanguageCode).coerceAtLeast(0))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                selectedLanguageCode = languageCodes[spinner.selectedItemPosition]
                refreshNaturalVoiceStatus()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    /** Hear the reference pronunciation for [text] via the shared TtsRouter - the one TTS entry point every layout in this tab uses. */
    private fun hearReference(text: String, onDone: (() -> Unit)? = null) {
        val app = mainActivity?.app
        if (app == null) { onDone?.invoke(); return }
        if (text.isBlank()) { toast("Type a word or phrase first"); onDone?.invoke(); return }
        app.tts.speak(text, selectedLanguageCode, selectedGender(),
            onDone = { if (contentContainer != null) onDone?.invoke() },
            onError = { err -> if (contentContainer != null) { toast(err, long = true); onDone?.invoke() } }
        )
    }

    /** Plays [file] via the tab's one shared MediaPlayer pipeline; optional [onDone] for chaining (loop_compare). */
    private fun playFile(file: File, onDone: (() -> Unit)? = null) {
        try {
            player?.release()
            val mp = MediaPlayer()
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener { it.release(); if (contentContainer != null) onDone?.invoke() }
            mp.prepare()
            mp.start()
            player = mp
        } catch (e: Exception) {
            toast("Playback failed: ${e.message}")
            onDone?.invoke()
        }
    }

    /**
     * Starts/stops a recording attempt for [phraseText] - the ONE recording
     * pipeline every layout in this tab shares (MicPipeline allows exactly
     * one capture at a time app-wide, same constraint the original
     * single-layout PracticeFragment already worked within).
     */
    private fun toggleRecordAttempt(phraseText: String, statusSetter: (String) -> Unit, onSaved: (() -> Unit)? = null) {
        val activity = mainActivity ?: return
        val app = activity.app
        if (app.mic.isRunning()) {
            app.mic.stop()
            return
        }
        if (!activity.hasMicPermission()) {
            activity.requestMicPermissionIfNeeded()
            toast("Grant microphone permission, then tap again", long = true)
            return
        }
        val label = phraseText.ifBlank { "phrase" }
        val file = recordingsStore.newFile(label)
        recordingTargetPhrase = phraseText.trim()
        micStatusText = "Recording… tap Stop when done"
        statusSetter(micStatusText)
        refreshAllContent()
        app.mic.start(
            recognizer = null,
            recordToFile = file,
            listener = object : MicPipeline.Listener {
                override fun onListeningStopped() {
                    if (contentContainer == null) return
                    micStatusText = ""
                    statusSetter("")
                    refreshAllContent()
                }
                override fun onRecordingSaved(file: File, bytes: Long) {
                    if (contentContainer == null) return
                    lastAttempt = file
                    val key = recordingTargetPhrase
                    if (key.isNotEmpty()) {
                        sessionAttemptCounts[key] = (sessionAttemptCounts[key] ?: 0) + 1
                        sessionLastAttemptByPhrase[key] = file
                    }
                    refreshRecordingsCache()
                    onSaved?.invoke()
                    refreshAllContent()
                }
                override fun onError(message: String) {
                    if (contentContainer == null) return
                    micStatusText = ""
                    statusSetter("")
                    toast(message)
                    refreshAllContent()
                }
            }
        )
    }

    private fun addPhraseToQueue(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        phraseQueue.add(PracticePhrase(trimmed))
        refreshAllContent()
    }

    /** Matches RecordingsStore.newFile's own sanitization exactly, so filenames can be matched back to the phrase that produced them. */
    private fun sanitizedLabel(text: String): String =
        text.ifBlank { "phrase" }.replace(Regex("[^A-Za-z0-9_-]"), "_").take(24)

    private fun recordingsForPhrase(text: String): List<File> {
        val suffix = "_${sanitizedLabel(text)}.wav"
        return recentRecordings.filter { it.name.endsWith(suffix) }
    }

    private fun timeLabelFor(file: File): String = try { timeFormat.format(Date(file.lastModified())) } catch (e: Exception) { "" }

    /** A small horizontal bar-chart View built from a REAL amplitude envelope ([WaveformReader]) - shared by drill_deck's recent strip and waveform_wall's past-attempt row. Tapping it replays [file]. */
    private fun buildWaveformThumb(file: File, parent: ViewGroup): View {
        val thumb = ItemPracticeWaveformThumbBinding.inflate(layoutInflater, parent, false)
        val peaks = WaveformReader.readPeaks(file, 20)
        val density = resources.displayMetrics.density
        thumb.barsWaveformThumb.removeAllViews()
        for (peak in peaks) {
            val bar = View(requireContext())
            val heightPx = (peak * 28 * density).toInt().coerceAtLeast((2 * density).toInt())
            val lp = LinearLayout.LayoutParams((2 * density).toInt(), heightPx)
            lp.marginEnd = (1 * density).toInt()
            bar.layoutParams = lp
            bar.setBackgroundColor(resources.getColor(R.color.colorPrimary, null))
            thumb.barsWaveformThumb.addView(bar)
        }
        thumb.textWaveformThumbLabel.text = timeLabelFor(file)
        thumb.root.setOnClickListener { playFile(file) }
        thumb.root.contentDescription = waveformThumbDescription(file, peaks)
        return thumb.root
    }

    /**
     * Real content description for a waveform-thumbnail card: states the
     * action (plays the recorded attempt, not just "button"), the real clip
     * duration ([WaveformReader.durationSeconds], read from this exact
     * file's own WAV header - not the shared decorative-bars estimate), and
     * a coarse real-amplitude-shape hint derived from the same [peaks] the
     * visible bars are drawn from, so a TalkBack user gets a sense of the
     * actual envelope shape a sighted user sees at a glance, not just a
     * bare timestamp.
     */
    private fun waveformThumbDescription(file: File, peaks: List<Float>): String {
        val timeLabel = timeLabelFor(file)
        val duration = WaveformReader.durationSeconds(file)
        val durationText = if (duration > 0f) String.format(Locale.US, "%.1f second recording", duration) else "recording"
        val shape = if (peaks.isEmpty()) {
            ""
        } else {
            val midpoint = peaks.size / 2
            val firstHalfAvg = peaks.take(midpoint.coerceAtLeast(1)).average()
            val secondHalfAvg = peaks.drop(midpoint).average()
            when {
                firstHalfAvg > secondHalfAvg * 1.3 -> ", louder at the start"
                secondHalfAvg > firstHalfAvg * 1.3 -> ", louder at the end"
                else -> ", steady volume throughout"
            }
        }
        return "Play recorded attempt from $timeLabel, $durationText$shape."
    }

    /**
     * A plainly-DECORATIVE static bar pattern, deterministic per phrase text
     * (not random each render) but NOT derived from real audio samples - see
     * [WaveformReader]'s doc comment for why no real reference waveform is
     * possible here (TTS has no capture-to-file path in this codebase).
     * Rendered with a lower-alpha color specifically so it doesn't visually
     * read as equivalent to the real waveform thumbnails alongside it.
     */
    private fun buildDecorativeBars(container: LinearLayout, seedText: String, barCount: Int = 20) {
        container.removeAllViews()
        val density = resources.displayMetrics.density
        val rnd = kotlin.random.Random(seedText.hashCode().toLong())
        for (i in 0 until barCount) {
            val heightFrac = 0.25f + rnd.nextFloat() * 0.7f
            val bar = View(requireContext())
            val lp = LinearLayout.LayoutParams((2 * density).toInt(), (heightFrac * 36 * density).toInt())
            lp.marginEnd = (1 * density).toInt()
            bar.layoutParams = lp
            bar.setBackgroundColor(0x552196F3)
            container.addView(bar)
        }
    }

    // ---------------------------------------------------------------------
    // Central refresh dispatcher - each refreshXxxContent below is a no-op
    // unless its binding is the one currently inflated.
    // ---------------------------------------------------------------------

    private fun refreshAllContent() {
        defaultBinding?.let { refreshDefaultContent(it) }
        drillDeckBinding?.let { refreshDrillDeckContent(it) }
        echoDuetBinding?.let { refreshEchoDuetContent(it) }
        drillCarouselBinding?.let { refreshDrillCarouselContent(it) }
        flexDefaultBinding?.let { refreshFlexDefaultContent(it) }
        waveformWallBinding?.let { refreshWaveformWallContent(it) }
        loopCompareBinding?.let { refreshLoopCompareContent(it) }
        phraseFeedBinding?.let { refreshPhraseFeedContent(it) }
    }

    // =======================================================================
    // "default" - the tab's original full layout, reused unchanged for both
    // book-portrait/non-fold AND ScreenMode.COVER's "default" variant.
    // =======================================================================

    private fun bindDefault(b: FragmentPracticeBinding) {
        setupLanguageSpinner(b.spinnerPracticeLang)

        b.radioMalePractice.isChecked = genderMale
        b.radioFemalePractice.isChecked = !genderMale
        b.radioGroupGenderPractice.setOnCheckedChangeListener { _, _ ->
            genderMale = b.radioMalePractice.isChecked
            VoicePreferences.setGender(requireContext(), selectedGender())
            refreshNaturalVoiceStatus()
        }

        watchPhraseText(b.editPhrase)
        if (currentPhraseText.isNotEmpty()) b.editPhrase.setText(currentPhraseText)

        b.btnHearReference.setOnClickListener { hearReference(currentPhraseText) }
        b.btnDownloadNaturalVoicePractice.setOnClickListener { downloadNaturalVoice() }
        b.btnRecordAttempt.setOnClickListener {
            toggleRecordAttempt(currentPhraseText, { s ->
                if (defaultBinding == null) return@toggleRecordAttempt
                b.btnRecordAttempt.text = if (s.isNotEmpty()) "⏹ Stop recording" else "🎙 Record my attempt"
                b.textPracticeStatus.text = s
            })
        }
        b.btnPlayAttempt.setOnClickListener { lastAttempt?.let { playFile(it) } }

        refreshDefaultContent(b)
        refreshNaturalVoiceStatus()
    }

    private fun refreshDefaultContent(b: FragmentPracticeBinding) {
        b.btnPlayAttempt.isEnabled = lastAttempt != null
        b.textNaturalVoiceStatusPractice.text = naturalVoiceStatusText
        refreshRecordingsListView(b.practiceRecordingsList)
    }

    private fun refreshRecordingsListView(container: LinearLayout) {
        container.removeAllViews()
        if (recentRecordings.isEmpty()) {
            val tv = TextView(requireContext())
            tv.text = "No practice attempts yet."
            tv.textSize = 12f
            container.addView(tv)
            return
        }
        for (f in recentRecordings) {
            val row = ItemRecordingBinding.inflate(layoutInflater, container, false)
            row.textRecordingName.text = f.name
            row.btnPlay.setOnClickListener { playFile(f) }
            row.btnDelete.setOnClickListener {
                recordingsStore.delete(f)
                if (lastAttempt == f) lastAttempt = null
                refreshRecordingsCache()
                refreshAllContent()
            }
            container.addView(row.root)
        }
    }

    // ---------------------------------------------------------------------
    // Natural-voice (Piper via sherpa-onnx) pack management - only ever
    // rendered on the "default" layout, same as Translate's Flex control
    // pane deliberately leaving pack downloads off the compact layouts.
    // ---------------------------------------------------------------------

    private fun refreshNaturalVoiceStatus() {
        val app = mainActivity?.app ?: return
        val code = selectedLanguageCode
        val gender = selectedGender()
        val info = app.tts.naturalVoiceInfo(code, gender)
        if (info == null) {
            val genderLabel = if (gender == VoiceGender.MALE) "male" else "female"
            naturalVoiceStatusText = "No natural $genderLabel voice available yet for ${LanguageCatalog.displayNameFor(code)} - using eSpeak (built-in, robotic)."
            defaultBinding?.btnDownloadNaturalVoicePractice?.visibility = View.GONE
            defaultBinding?.textNaturalVoiceStatusPractice?.text = naturalVoiceStatusText
            return
        }
        defaultBinding?.btnDownloadNaturalVoicePractice?.visibility = View.VISIBLE
        if (app.tts.isNaturalVoiceDownloaded(code, gender)) {
            naturalVoiceStatusText = "Natural voice (${info.displayName}) downloaded — reference pronunciation uses it automatically."
            defaultBinding?.btnDownloadNaturalVoicePractice?.text = "Re-download natural voice"
        } else {
            naturalVoiceStatusText = "Natural voice available: ${info.displayName} (~${info.approxSizeMiB}MB, ${info.license}). Using eSpeak (robotic) until downloaded."
            defaultBinding?.btnDownloadNaturalVoicePractice?.text = "Download natural voice"
        }
        defaultBinding?.textNaturalVoiceStatusPractice?.text = naturalVoiceStatusText
    }

    private fun downloadNaturalVoice() {
        val app = mainActivity?.app ?: return
        val code = selectedLanguageCode
        val gender = selectedGender()
        naturalVoiceStatusText = "Downloading natural voice…"
        defaultBinding?.textNaturalVoiceStatusPractice?.text = naturalVoiceStatusText
        app.tts.downloadNaturalVoice(
            requireContext(), code, gender,
            onProgress = { pct ->
                if (contentContainer != null) {
                    naturalVoiceStatusText = "Downloading natural voice… $pct%"
                    defaultBinding?.textNaturalVoiceStatusPractice?.text = naturalVoiceStatusText
                }
            }
        ) onDownloadDone@{ success, error ->
            if (contentContainer == null) return@onDownloadDone
            if (success) toast("Natural voice downloaded.") else toast("Download failed: $error", long = true)
            refreshNaturalVoiceStatus()
        }
    }

    // =======================================================================
    // Cover: "drill_deck" - real-waveform recent-attempts strip, scrollable
    // phrase queue with inline hear/record + attempted marker.
    // =======================================================================

    private fun bindDrillDeck(b: FragmentPracticeCoverDrillDeckBinding) {
        b.btnDrillAddPhrase.setOnClickListener {
            addPhraseToQueue(b.editDrillAddPhrase.text?.toString().orEmpty())
            b.editDrillAddPhrase.setText("")
        }
        b.editDrillAddPhrase.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addPhraseToQueue(b.editDrillAddPhrase.text?.toString().orEmpty())
                b.editDrillAddPhrase.setText("")
                true
            } else false
        }
        refreshDrillDeckContent(b)
    }

    private fun refreshDrillDeckContent(b: FragmentPracticeCoverDrillDeckBinding) {
        b.rowDrillRecentThumbs.removeAllViews()
        if (recentRecordings.isEmpty()) {
            val tv = TextView(requireContext())
            tv.text = "No attempts recorded yet this session."
            tv.textSize = 11f
            tv.alpha = 0.7f
            b.rowDrillRecentThumbs.addView(tv)
        } else {
            for (f in recentRecordings.take(8)) {
                b.rowDrillRecentThumbs.addView(buildWaveformThumb(f, b.rowDrillRecentThumbs))
            }
        }

        b.listDrillPhrases.removeAllViews()
        if (phraseQueue.isEmpty()) {
            val tv = TextView(requireContext())
            tv.text = "No phrases queued yet — add one below."
            tv.textSize = 13f
            b.listDrillPhrases.addView(tv)
        } else {
            for (phrase in phraseQueue) {
                val row = ItemPracticePhraseRowBinding.inflate(layoutInflater, b.listDrillPhrases, false)
                row.textPhraseRowText.text = phrase.text
                val attempted = (sessionAttemptCounts[phrase.text.trim()] ?: 0) > 0
                row.textPhraseRowAttemptedDot.visibility = if (attempted) View.VISIBLE else View.INVISIBLE
                row.btnPhraseRowHear.setOnClickListener { hearReference(phrase.text) }
                row.btnPhraseRowRecord.setOnClickListener {
                    toggleRecordAttempt(phrase.text, { s -> if (contentContainer != null) b.textDrillStatus.text = s })
                }
                b.listDrillPhrases.addView(row.root)
            }
        }
    }

    // =======================================================================
    // Cover: "echo_duet" - two voice orbs, no score ever, a rep-count dot
    // row + streak flame chip built from real session attempt counts.
    // =======================================================================

    private fun bindEchoDuet(b: FragmentPracticeCoverEchoDuetBinding) {
        b.ringOrbReference.alpha = 0f
        b.ringOrbMine.alpha = 0f
        watchPhraseText(b.editEchoPhrase)
        if (currentPhraseText.isNotEmpty()) b.editEchoPhrase.setText(currentPhraseText)

        b.cardOrbReference.setOnClickListener {
            if (currentPhraseText.isBlank()) { toast("Type a phrase first"); return@setOnClickListener }
            echoReferenceAnimator = startPulse(b.ringOrbReference)
            hearReference(currentPhraseText) {
                stopPulse(echoReferenceAnimator, b.ringOrbReference)
                echoReferenceAnimator = null
            }
        }

        b.cardOrbMine.setOnClickListener {
            if (currentPhraseText.isBlank()) { toast("Type a phrase first"); return@setOnClickListener }
            val app = mainActivity?.app
            if (app?.mic?.isRunning() == true) {
                app.mic.stop()
                return@setOnClickListener
            }
            val key = currentPhraseText.trim()
            val existing = sessionLastAttemptByPhrase[key]
            if (existing != null) {
                echoMineAnimator = startPulse(b.ringOrbMine)
                playFile(existing) {
                    stopPulse(echoMineAnimator, b.ringOrbMine)
                    echoMineAnimator = null
                }
                return@setOnClickListener
            }
            echoMineAnimator = startPulse(b.ringOrbMine)
            toggleRecordAttempt(currentPhraseText, { s ->
                if (contentContainer == null) return@toggleRecordAttempt
                if (s.isEmpty()) { stopPulse(echoMineAnimator, b.ringOrbMine); echoMineAnimator = null }
            })
        }

        refreshEchoDuetContent(b)
    }

    private fun startPulse(target: View): ValueAnimator {
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = 650L
        animator.repeatMode = ValueAnimator.REVERSE
        animator.repeatCount = ValueAnimator.INFINITE
        animator.addUpdateListener { anim ->
            val t = anim.animatedValue as Float
            target.alpha = t * 0.8f
            val scale = 1f + t * 0.15f
            target.scaleX = scale; target.scaleY = scale
        }
        animator.start()
        return animator
    }

    private fun stopPulse(animator: ValueAnimator?, target: View) {
        animator?.cancel()
        target.alpha = 0f
        target.scaleX = 1f; target.scaleY = 1f
    }

    private fun stopEchoAnimators() {
        echoReferenceAnimator?.cancel(); echoReferenceAnimator = null
        echoMineAnimator?.cancel(); echoMineAnimator = null
    }

    private fun refreshEchoDuetContent(b: FragmentPracticeCoverEchoDuetBinding) {
        val key = currentPhraseText.trim()
        val count = sessionAttemptCounts[key] ?: 0
        val density = resources.displayMetrics.density

        b.rowEchoRepDots.removeAllViews()
        val dotsFilled = count.coerceAtMost(ECHO_MAX_DOTS)
        for (i in 0 until ECHO_MAX_DOTS) {
            val dot = TextView(requireContext())
            dot.text = "●"
            dot.textSize = 14f
            dot.alpha = if (i < dotsFilled) 1f else 0.25f
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = (4 * density).toInt()
            dot.layoutParams = lp
            b.rowEchoRepDots.addView(dot)
        }

        if (count >= ECHO_STREAK_THRESHOLD) {
            b.chipEchoStreak.visibility = View.VISIBLE
            b.chipEchoStreak.text = "🔥 $count"
        } else {
            b.chipEchoStreak.visibility = View.INVISIBLE
        }

        b.textOrbMineLabel.text = when {
            mainActivity?.app?.mic?.isRunning() == true -> "Recording…\ntap to stop"
            sessionLastAttemptByPhrase.containsKey(key) -> "You\ntap to hear yours"
            else -> "You\nTap to record"
        }
    }

    // =======================================================================
    // Cover: "drill_carousel" - one phrase card at a time, swipe to move,
    // one center button cycles hear-reference -> record -> hear-mine.
    // =======================================================================

    private fun bindDrillCarousel(b: FragmentPracticeCoverDrillCarouselBinding) {
        b.btnCarouselAddPhrase.setOnClickListener {
            val hadNone = phraseQueue.isEmpty()
            addPhraseToQueue(b.editCarouselAddPhrase.text?.toString().orEmpty())
            b.editCarouselAddPhrase.setText("")
            if (hadNone) { carouselIndex = 0; carouselStep = CarouselStep.HEAR_REFERENCE; refreshAllContent() }
        }

        var downX = 0f
        b.cardCarouselPhrase.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; true }
                MotionEvent.ACTION_UP -> {
                    val dx = event.rawX - downX
                    if (Math.abs(dx) > CAROUSEL_SWIPE_THRESHOLD_PX && phraseQueue.isNotEmpty()) {
                        moveCarousel(if (dx < 0) 1 else -1)
                    } else {
                        v.performClick()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }

        b.btnCarouselAction.setOnClickListener { performCarouselAction(b) }
        refreshDrillCarouselContent(b)
    }

    private fun currentCarouselPhrase(): String? = phraseQueue.getOrNull(carouselIndex)?.text

    private fun moveCarousel(delta: Int) {
        if (phraseQueue.isEmpty()) return
        carouselIndex = (carouselIndex + delta).mod(phraseQueue.size)
        carouselStep = CarouselStep.HEAR_REFERENCE
        val container = contentContainer
        container?.animate()?.alpha(0.2f)?.setDuration(90L)?.withEndAction {
            if (contentContainer == null) return@withEndAction
            refreshAllContent()
            container.animate().alpha(1f).setDuration(140L).start()
        }?.start()
    }

    private fun performCarouselAction(b: FragmentPracticeCoverDrillCarouselBinding) {
        val phrase = currentCarouselPhrase()
        if (phrase == null) { toast("Add a phrase first"); return }
        when (carouselStep) {
            CarouselStep.HEAR_REFERENCE -> {
                hearReference(phrase) {
                    carouselStep = CarouselStep.RECORD
                    refreshAllContent()
                }
            }
            CarouselStep.RECORD -> {
                // Only push the "Recording…" status text explicitly here;
                // the empty (stopped) case is left to refreshAllContent's
                // own refreshDrillCarouselContent, which toggleRecordAttempt
                // already calls right after both onListeningStopped and
                // onRecordingSaved - it recomputes the hint from carouselStep
                // + live mic state, so nothing is lost by not handling "" here.
                toggleRecordAttempt(phrase, { s -> if (contentContainer != null && s.isNotEmpty()) b.textCarouselStepHint.text = s }) {
                    carouselStep = CarouselStep.HEAR_MINE
                    refreshAllContent()
                }
            }
            CarouselStep.HEAR_MINE -> {
                val file = sessionLastAttemptByPhrase[phrase.trim()]
                if (file == null) { toast("No recording yet for this phrase"); return }
                playFile(file) {
                    carouselStep = CarouselStep.HEAR_REFERENCE
                    refreshAllContent()
                }
            }
        }
    }

    private fun refreshDrillCarouselContent(b: FragmentPracticeCoverDrillCarouselBinding) {
        val phrase = currentCarouselPhrase()
        b.textCarouselPhrase.text = phrase ?: "No phrases yet - add one above"
        b.btnCarouselAction.isEnabled = phrase != null

        val recording = mainActivity?.app?.mic?.isRunning() == true
        when {
            recording -> { b.textCarouselActionIcon.text = "⏹"; b.textCarouselStepHint.text = "Recording… tap to stop" }
            carouselStep == CarouselStep.HEAR_REFERENCE -> { b.textCarouselActionIcon.text = "🔊"; b.textCarouselStepHint.text = "Tap to hear the reference" }
            carouselStep == CarouselStep.RECORD -> { b.textCarouselActionIcon.text = "🎙"; b.textCarouselStepHint.text = "Tap to record your attempt" }
            carouselStep == CarouselStep.HEAR_MINE -> { b.textCarouselActionIcon.text = "▶"; b.textCarouselStepHint.text = "Tap to hear yours" }
        }

        b.rowCarouselDots.removeAllViews()
        val density = resources.displayMetrics.density
        for (i in phraseQueue.indices) {
            val dot = TextView(requireContext())
            dot.text = "●"
            dot.textSize = if (i == carouselIndex) 14f else 10f
            dot.alpha = if (i == carouselIndex) 1f else 0.35f
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = (4 * density).toInt()
            dot.layoutParams = lp
            b.rowCarouselDots.addView(dot)
        }
    }

    // =======================================================================
    // Flex: "flex_default" - viewing pane (phrase + hear/play) above the
    // hinge, control pane (picker/gender/phrase/record) below - this
    // project's established Flex Mode split, new baseline for this tab (it
    // had none before this pass; only fragment_practice.xml existed).
    // =======================================================================

    private fun bindFlexDefault(b: FragmentPracticeFlexDefaultBinding) {
        setupLanguageSpinner(b.paneControl.spinnerFlexPracticeLang)

        b.paneControl.radioFlexPracticeMale.isChecked = genderMale
        b.paneControl.radioFlexPracticeFemale.isChecked = !genderMale
        b.paneControl.radioGroupFlexPracticeGender.setOnCheckedChangeListener { _, _ ->
            genderMale = b.paneControl.radioFlexPracticeMale.isChecked
            VoicePreferences.setGender(requireContext(), selectedGender())
        }

        watchPhraseText(b.paneControl.editFlexPracticePhrase)
        if (currentPhraseText.isNotEmpty()) b.paneControl.editFlexPracticePhrase.setText(currentPhraseText)

        b.paneControl.btnFlexPracticeRecord.setOnClickListener {
            toggleRecordAttempt(currentPhraseText, { s -> if (contentContainer != null) b.paneControl.textFlexPracticeMicStatus.text = s })
        }
        b.paneViewing.btnFlexPracticeHear.setOnClickListener { hearReference(currentPhraseText) }
        b.paneViewing.btnFlexPracticePlay.setOnClickListener {
            val key = currentPhraseText.trim()
            val file = sessionLastAttemptByPhrase[key] ?: lastAttempt
            if (file == null) toast("Record an attempt first") else playFile(file)
        }

        refreshFlexDefaultContent(b)
    }

    private fun refreshFlexDefaultContent(b: FragmentPracticeFlexDefaultBinding) {
        b.paneViewing.textFlexPracticePhrase.text = currentPhraseText.ifBlank { "Type a phrase below to get started" }
        val key = currentPhraseText.trim()
        b.paneViewing.btnFlexPracticePlay.isEnabled = sessionLastAttemptByPhrase.containsKey(key) || lastAttempt != null
    }

    // =======================================================================
    // Flex: "waveform_wall" - above hinge: reference row + real past-
    // attempt mini-waveforms (left 70%) + phrase-set rail (right 30%);
    // below hinge: record/play/gender/prev-next.
    // =======================================================================

    private fun bindWaveformWall(b: FragmentPracticeFlexWaveformWallBinding) {
        b.btnWaveformHearReference.setOnClickListener { hearReference(activePhraseTextForWall()) }
        b.btnWaveformRecord.setOnClickListener {
            toggleRecordAttempt(activePhraseTextForWall(), { s -> if (contentContainer != null) b.textWaveformStatus.text = s })
        }
        b.btnWaveformPlayMine.setOnClickListener {
            val key = activePhraseTextForWall().trim()
            val file = sessionLastAttemptByPhrase[key] ?: recordingsForPhrase(activePhraseTextForWall()).firstOrNull()
            if (file == null) toast("Record an attempt first") else playFile(file)
        }
        b.btnWaveformGenderToggle.setOnClickListener {
            genderMale = !genderMale
            VoicePreferences.setGender(requireContext(), selectedGender())
            refreshAllContent()
        }
        b.btnWaveformPrev.setOnClickListener { movePhraseRail(-1) }
        b.btnWaveformNext.setOnClickListener { movePhraseRail(1) }
        b.editWaveformAddPhrase.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                val hadNone = phraseQueue.isEmpty()
                addPhraseToQueue(b.editWaveformAddPhrase.text?.toString().orEmpty())
                b.editWaveformAddPhrase.setText("")
                if (hadNone) { waveformActiveIndex = 0; refreshAllContent() }
                true
            } else false
        }
        refreshWaveformWallContent(b)
    }

    private fun activePhraseTextForWall(): String =
        phraseQueue.getOrNull(waveformActiveIndex)?.text ?: currentPhraseText

    private fun movePhraseRail(delta: Int) {
        if (phraseQueue.isEmpty()) { toast("Add a phrase first"); return }
        waveformActiveIndex = (waveformActiveIndex + delta).mod(phraseQueue.size)
        refreshAllContent()
    }

    private fun refreshWaveformWallContent(b: FragmentPracticeFlexWaveformWallBinding) {
        val activeText = activePhraseTextForWall()
        b.textWaveformActivePhrase.text = activeText.ifBlank { "Add a phrase to the rail, or type one above" }
        buildDecorativeBars(b.barsWaveformReference, activeText)

        b.rowWaveformPastAttempts.removeAllViews()
        val pastForPhrase = recordingsForPhrase(activeText)
        if (pastForPhrase.isEmpty()) {
            val tv = TextView(requireContext())
            tv.text = "No attempts yet for this phrase."
            tv.textSize = 11f
            tv.alpha = 0.7f
            b.rowWaveformPastAttempts.addView(tv)
        } else {
            for (f in pastForPhrase.take(6)) {
                b.rowWaveformPastAttempts.addView(buildWaveformThumb(f, b.rowWaveformPastAttempts))
            }
        }
        b.btnWaveformPlayMine.isEnabled = pastForPhrase.isNotEmpty() || sessionLastAttemptByPhrase.containsKey(activeText.trim())

        b.listWaveformRail.removeAllViews()
        if (phraseQueue.isEmpty()) {
            val tv = TextView(requireContext())
            tv.text = "No phrases yet."
            tv.textSize = 11f
            tv.alpha = 0.7f
            b.listWaveformRail.addView(tv)
        } else {
            for ((index, phrase) in phraseQueue.withIndex()) {
                val row = ItemPracticeRailPhraseBinding.inflate(layoutInflater, b.listWaveformRail, false)
                row.textRailPhraseText.text = phrase.text
                val count = sessionAttemptCounts[phrase.text.trim()] ?: 0
                if (count > 0) {
                    row.textRailAttemptBadge.visibility = View.VISIBLE
                    row.textRailAttemptBadge.text = count.toString()
                } else {
                    row.textRailAttemptBadge.visibility = View.INVISIBLE
                }
                row.root.alpha = if (index == waveformActiveIndex) 1f else 0.6f
                row.root.setOnClickListener {
                    waveformActiveIndex = index
                    currentPhraseText = phrase.text
                    refreshAllContent()
                }
                b.listWaveformRail.addView(row.root)
            }
        }
    }

    // =======================================================================
    // Flex: "loop_compare" - Start/Stop toggle resequences the same
    // hearReference()/playFile() calls every other layout uses, chained
    // back-to-back via their existing onDone callbacks.
    // =======================================================================

    private fun bindLoopCompare(b: FragmentPracticeFlexLoopCompareBinding) {
        setupLanguageSpinner(b.spinnerLoopLang)
        b.radioLoopMale.isChecked = genderMale
        b.radioLoopFemale.isChecked = !genderMale
        b.radioGroupLoopGender.setOnCheckedChangeListener { _, _ ->
            genderMale = b.radioLoopMale.isChecked
            VoicePreferences.setGender(requireContext(), selectedGender())
        }

        watchPhraseText(b.editLoopPhrase)
        if (currentPhraseText.isNotEmpty()) b.editLoopPhrase.setText(currentPhraseText)

        b.btnLoopRecord.setOnClickListener {
            toggleRecordAttempt(currentPhraseText, { s -> if (contentContainer != null) b.textLoopMicStatus.text = s })
        }
        b.btnLoopToggle.setOnClickListener { toggleLoopCompare(b) }

        refreshLoopCompareContent(b)
    }

    private fun toggleLoopCompare(b: FragmentPracticeFlexLoopCompareBinding) {
        if (loopActive) {
            stopLoopCompare()
            return
        }
        if (currentPhraseText.isBlank()) { toast("Type a phrase first"); return }
        loopActive = true
        loopStep = LoopStep.REFERENCE
        refreshLoopCompareContent(b)
        runLoopStep()
    }

    private fun runLoopStep() {
        if (!loopActive || currentActive != PracticeActiveLayout.FLEX_LOOP_COMPARE) return
        val b = loopCompareBinding ?: return
        when (loopStep) {
            LoopStep.REFERENCE -> {
                b.textLoopStatus.text = "Playing reference…"
                refreshLoopIcons(b)
                hearReference(currentPhraseText) {
                    if (!loopActive || currentActive != PracticeActiveLayout.FLEX_LOOP_COMPARE) return@hearReference
                    loopHandler.postDelayed({
                        if (loopActive && currentActive == PracticeActiveLayout.FLEX_LOOP_COMPARE) { loopStep = LoopStep.MINE; runLoopStep() }
                    }, LOOP_PAUSE_MS)
                }
            }
            LoopStep.MINE -> {
                val key = currentPhraseText.trim()
                val file = sessionLastAttemptByPhrase[key] ?: lastAttempt
                if (file == null) {
                    b.textLoopStatus.text = "Record an attempt to complete the loop - looping the reference alone for now."
                    refreshLoopIcons(b)
                    loopHandler.postDelayed({
                        if (loopActive && currentActive == PracticeActiveLayout.FLEX_LOOP_COMPARE) { loopStep = LoopStep.REFERENCE; runLoopStep() }
                    }, LOOP_PAUSE_MS)
                } else {
                    b.textLoopStatus.text = "Playing your recording…"
                    refreshLoopIcons(b)
                    playFile(file) {
                        if (!loopActive || currentActive != PracticeActiveLayout.FLEX_LOOP_COMPARE) return@playFile
                        loopHandler.postDelayed({
                            if (loopActive && currentActive == PracticeActiveLayout.FLEX_LOOP_COMPARE) { loopStep = LoopStep.REFERENCE; runLoopStep() }
                        }, LOOP_PAUSE_MS)
                    }
                }
            }
        }
    }

    private fun stopLoopCompare() {
        if (!loopActive) return
        loopActive = false
        loopHandler.removeCallbacksAndMessages(null)
        loopCompareBinding?.let { b ->
            b.textLoopStatus.text = ""
            refreshLoopIcons(b)
        }
    }

    private fun refreshLoopIcons(b: FragmentPracticeFlexLoopCompareBinding) {
        b.textLoopToggleLabel.text = if (loopActive) "■ Stop loop" else "▶ Start loop"
        b.iconLoopReference.setBackgroundResource(if (loopActive && loopStep == LoopStep.REFERENCE) R.drawable.bg_chip_filled else R.drawable.bg_chip_outline)
        b.iconLoopMine.setBackgroundResource(if (loopActive && loopStep == LoopStep.MINE) R.drawable.bg_chip_filled else R.drawable.bg_chip_outline)
    }

    private fun refreshLoopCompareContent(b: FragmentPracticeFlexLoopCompareBinding) {
        b.textLoopPhrase.text = currentPhraseText.ifBlank { "Type a phrase below" }
        refreshLoopIcons(b)
    }

    // =======================================================================
    // Flex: "phrase_feed" - one continuous vertical feed crossing the hinge
    // uninterrupted; scroll-snap centers the active card on the hinge; a
    // pinned record button always acts on whichever card is active.
    // =======================================================================

    private fun bindPhraseFeed(b: FragmentPracticeFlexPhraseFeedBinding) {
        feedActiveIndex = if (phraseQueue.isEmpty()) 0 else feedActiveIndex.coerceIn(0, phraseQueue.size - 1)
        b.btnFeedRecordPinned.setOnClickListener {
            val phrase = phraseQueue.getOrNull(feedActiveIndex)?.text
            if (phrase == null) { toast("Add a phrase to the feed first"); return@setOnClickListener }
            toggleRecordAttempt(phrase, { s ->
                phraseFeedBinding?.textFeedPinnedStatus?.let { tv ->
                    tv.visibility = if (s.isNotEmpty()) View.VISIBLE else View.INVISIBLE
                }
            }) { refreshAllContent() }
        }
        b.scrollPhraseFeed.setOnScrollChangeListener { _, _, _, _, _ ->
            if (feedProgrammaticScroll) return@setOnScrollChangeListener
            b.scrollPhraseFeed.removeCallbacks(feedSettleRunnable)
            b.scrollPhraseFeed.postDelayed(feedSettleRunnable, FEED_SETTLE_DELAY_MS)
        }
        refreshPhraseFeedContent(b)
    }

    private fun refreshPhraseFeedContent(b: FragmentPracticeFlexPhraseFeedBinding) {
        feedActiveIndex = if (phraseQueue.isEmpty()) 0 else feedActiveIndex.coerceIn(0, phraseQueue.size - 1)
        b.listPhraseFeedCards.removeAllViews()
        feedCardViews.clear()

        if (phraseQueue.isEmpty()) {
            val tv = TextView(requireContext())
            tv.text = "No phrases yet - add one below."
            tv.textSize = 13f
            tv.gravity = Gravity.CENTER
            val pad = (32 * resources.displayMetrics.density).toInt()
            tv.setPadding(0, pad, 0, pad)
            b.listPhraseFeedCards.addView(tv)
        }
        for ((index, phrase) in phraseQueue.withIndex()) {
            val card = ItemPracticeFeedCardBinding.inflate(layoutInflater, b.listPhraseFeedCards, false)
            card.textFeedCardPhrase.text = phrase.text
            val count = sessionAttemptCounts[phrase.text.trim()] ?: 0
            card.textFeedCardAttempts.text = if (count > 0) "Attempted $count time${if (count == 1) "" else "s"} this session" else "Not attempted yet"
            card.btnFeedCardHear.setOnClickListener { hearReference(phrase.text) }
            card.viewFeedActiveAccent.visibility = if (index == feedActiveIndex) View.VISIBLE else View.INVISIBLE
            b.listPhraseFeedCards.addView(card.root)
            feedCardViews.add(card.root)
        }
        b.listPhraseFeedCards.addView(buildFeedAddCard())

        val recording = mainActivity?.app?.mic?.isRunning() == true
        b.textFeedPinnedStatus.text = "Recording…"
        b.textFeedPinnedStatus.visibility = if (recording) View.VISIBLE else View.INVISIBLE
        b.btnFeedRecordPinned.alpha = if (phraseQueue.isNotEmpty()) 1f else 0.4f
    }

    private fun buildFeedAddCard(): View {
        val density = resources.displayMetrics.density
        val card = CardView(requireContext())
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.setMargins((14 * density).toInt(), (10 * density).toInt(), (14 * density).toInt(), (10 * density).toInt())
        card.layoutParams = lp
        card.radius = 10 * density
        card.cardElevation = 1 * density

        val row = LinearLayout(requireContext())
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val pad = (14 * density).toInt()
        row.setPadding(pad, pad, pad, pad)

        val edit = TextInputEditText(requireContext())
        edit.hint = "Add another phrase to the feed…"
        edit.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

        val addBtn = ImageButton(requireContext())
        addBtn.setImageResource(android.R.drawable.ic_input_add)
        addBtn.setBackgroundColor(0x00000000)
        addBtn.contentDescription = "Add phrase to the feed"
        addBtn.layoutParams = LinearLayout.LayoutParams((40 * density).toInt(), (40 * density).toInt())
        addBtn.setOnClickListener {
            addPhraseToQueue(edit.text?.toString().orEmpty())
            edit.setText("")
        }

        row.addView(edit)
        row.addView(addBtn)
        card.addView(row)
        return card
    }

    /**
     * Finds whichever real phrase card (excluding the trailing add-card) is
     * currently nearest the physical hinge midpoint and smooth-scrolls it
     * into alignment - same [FoldingFeature.bounds] geometry source every
     * Flex variant in this codebase uses. Defers one frame (same technique
     * as [positionFlexPanes]) if the freshly-rebuilt card views haven't been
     * measured/laid-out yet, which is always true immediately after
     * [refreshPhraseFeedContent] rebuilds the list.
     */
    private fun snapPhraseFeedToNearestCard() {
        val b = phraseFeedBinding ?: return
        if (currentActive != PracticeActiveLayout.FLEX_PHRASE_FEED || feedCardViews.isEmpty()) return
        val feature = currentFeature ?: return
        if (feedCardViews.any { it.height == 0 }) {
            b.scrollPhraseFeed.post { if (isAdded && currentActive == PracticeActiveLayout.FLEX_PHRASE_FEED) snapPhraseFeedToNearestCard() }
            return
        }
        val scrollLoc = IntArray(2)
        b.scrollPhraseFeed.getLocationInWindow(scrollLoc)
        val hingeMidWindowY = (feature.bounds.top + feature.bounds.bottom) / 2
        val hingeMidInScroll = hingeMidWindowY - scrollLoc[1] + b.scrollPhraseFeed.scrollY

        var bestIndex = 0
        var bestDist = Float.MAX_VALUE
        for ((i, card) in feedCardViews.withIndex()) {
            val cardMid = card.top + card.height / 2f
            val dist = Math.abs(cardMid - hingeMidInScroll)
            if (dist < bestDist) { bestDist = dist; bestIndex = i }
        }
        if (bestIndex != feedActiveIndex) {
            feedActiveIndex = bestIndex
            refreshPhraseFeedContent(b)
            // The freshly-rebuilt card views have height==0 until the next
            // layout pass, so re-enter rather than return outright - the
            // height==0 guard above defers via post{} until they're valid,
            // then this same call actually performs the aligning scroll
            // below. Without this, only the highlight would update on this
            // pass and the physical snap would silently wait for some
            // unrelated future event to happen to re-trigger it.
            snapPhraseFeedToNearestCard()
            return
        }

        val target = feedCardViews[bestIndex]
        val targetMid = target.top + target.height / 2f
        val delta = (targetMid - hingeMidInScroll).toInt()
        if (Math.abs(delta) > 2) {
            feedProgrammaticScroll = true
            b.scrollPhraseFeed.smoothScrollBy(0, delta)
            b.scrollPhraseFeed.postDelayed({ feedProgrammaticScroll = false }, 400L)
        }
    }

    // ---------------------------------------------------------------------

    override fun onPause() {
        super.onPause()
        mainActivity?.app?.mic?.stop()
        stopLoopCompare()
        player?.release()
        player = null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mainActivity?.app?.mic?.stop()
        stopLoopCompare()
        stopEchoAnimators()
        phraseFeedBinding?.scrollPhraseFeed?.removeCallbacks(feedSettleRunnable)
        player?.release()
        player = null
        unregisterLayoutPrefsListener()
        contentContainer = null
        defaultBinding = null
        drillDeckBinding = null
        echoDuetBinding = null
        drillCarouselBinding = null
        flexDefaultBinding = null
        waveformWallBinding = null
        loopCompareBinding = null
        phraseFeedBinding = null
        currentActive = null
    }

    companion object {
        private const val TAG = "PracticeFragment"
        private const val CAROUSEL_SWIPE_THRESHOLD_PX = 90f
        private const val LOOP_PAUSE_MS = 900L
        private const val FEED_SETTLE_DELAY_MS = 150L
        private const val ECHO_MAX_DOTS = 5
        private const val ECHO_STREAK_THRESHOLD = 3

        // Mirrors LayoutPreferences' own private PREFS_NAME / variantKey()
        // format exactly (see registerLayoutPrefsListener's doc comment) -
        // duplicated here deliberately rather than exposing it on that
        // foundation file, which this tab's work is scoped not to touch.
        // Same technique TranslateFragment already uses.
        private const val LAYOUT_PREFS_FILE_NAME = "layout_prefs"
        private const val LAYOUT_KEY_COVER = "variant_practice_cover"
        private const val LAYOUT_KEY_FLEX = "variant_practice_flex"
    }
}
