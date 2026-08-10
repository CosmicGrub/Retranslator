package com.retroid.translator.ui

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.FoldingFeature
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.retroid.translator.MainActivity
import com.retroid.translator.R
import com.retroid.translator.audio.MicPipeline
import com.retroid.translator.databinding.FragmentTranslateBinding
import com.retroid.translator.databinding.FragmentTranslateCoverFaceToFaceBinding
import com.retroid.translator.databinding.FragmentTranslateCoverLiveTranscriptBinding
import com.retroid.translator.databinding.FragmentTranslateCoverSingleCircleBinding
import com.retroid.translator.databinding.FragmentTranslateFlexAcrossTableBinding
import com.retroid.translator.databinding.FragmentTranslateFlexDefaultBinding
import com.retroid.translator.databinding.FragmentTranslateFlexMirrorPanesBinding
import com.retroid.translator.databinding.FragmentTranslateFlexMultiBroadcastBinding
import com.retroid.translator.databinding.ViewTranslateBroadcastRowBinding
import com.retroid.translator.databinding.ViewTranslateTranscriptBubbleBinding
import com.retroid.translator.engine.DownloadManager
import com.retroid.translator.engine.LanguageCatalog
import com.retroid.translator.engine.TranslationEngine
import com.retroid.translator.engine.VoiceGender
import com.retroid.translator.engine.VoicePreferences
import com.retroid.translator.engine.VoskModelCatalog
import com.retroid.translator.fold.FoldPosture
import com.retroid.translator.fold.FoldPostureProvider
import com.retroid.translator.fold.FoldState
import com.retroid.translator.settings.FoldAwareLayoutHost
import com.retroid.translator.settings.LayoutPreferences
import com.retroid.translator.settings.ScreenMode
import com.retroid.translator.settings.SettingsTab
import kotlinx.coroutines.launch

/** One entry in the "live_transcript" cover variant's session-only, in-memory transcript. Stored newest-first (index 0). */
private data class TranscriptEntry(val sourceCode: String, val targetCode: String, val sourceText: String, val translatedText: String)

/** Which of this tab's 8 real layouts is currently inflated into [TranslateFragment]'s container - at most one at a time. */
private enum class ActiveLayout {
    DEFAULT, COVER_SINGLE_CIRCLE, COVER_LIVE_TRANSCRIPT, COVER_FACE_TO_FACE,
    FLEX_DEFAULT, FLEX_ACROSS_TABLE, FLEX_MULTI_BROADCAST, FLEX_MIRROR_PANES
}

/** Touch-state machine for the "single_circle" cover variant's one focal control. */
private enum class CircleState { PROMPT, RECORDING, TRANSLATING, RESULT }

/**
 * Translate tab. Implements the per-tab layout-variant system from the
 * settings foundation (docs/specs/fold5-adaptation.md's task-item-worth of
 * work covering ONLY this tab): 4 real cover-screen variants + 4 real
 * Flex-Mode (tabletop) variants, on top of the tab's pre-existing default
 * (full, book-portrait) layout - 8 real, functioning layouts total, all
 * reading and writing the exact same underlying translation/mic/TTS state.
 *
 * Deliberate deviation worth flagging up front: docs/specs/fold5-adaptation.md's
 * own Scope table (§ "Scope: which screens get what") lists Translate as
 * "Responsive scaling only" - no bespoke fold-aware layout, that treatment
 * reserved for Conversations alone. This file's existence is a direct
 * instruction from the task that commissioned it, which explicitly asks for
 * bespoke Translate cover/Flex layouts - it supersedes the spec's Scope
 * table for this tab. Book-portrait/non-fold behavior (the spec's actual
 * "responsive scaling only" case) is preserved unchanged as [ActiveLayout.DEFAULT].
 *
 * Rendering model (same dynamic-container technique as `ConversationsFragment`,
 * scaled from 2 layouts to 8): [onCreateView] returns a bare [FrameLayout]
 * mount point, not a static XML - which real layout is currently inflated
 * into it is decided live by [renderActiveLayout], driven by two independent
 * signals:
 *  - **Flex Mode**: this Fragment's own [FoldPostureProvider] subscription
 *    (same pattern `ConversationsFragment`/`FoldBehaviorFragment` already
 *    use) - [FoldPosture.isMirroredTabletop] selects the user's configured
 *    [ScreenMode.FLEX] variant.
 *  - **Cover screen**: [FoldAwareLayoutHost] ([applyCoverLayout]/[applyDefaultLayout]),
 *    pushed by `MainActivity`'s existing fold-close heuristic and the Fold
 *    behavior screen's manual force-compact toggle - both already built by
 *    the settings foundation, zero `MainActivity` changes needed here. This
 *    tab does NOT attempt to detect "running on the physical cover display"
 *    itself - per [FoldPosture.CLOSED_COVER]'s own doc comment, that's not
 *    observable via `FoldingFeature` at all (a window fully on the cover
 *    display reports no separating fold, identical to a plain non-foldable
 *    phone) - it relies entirely on the foundation's existing
 *    [LayoutPreferences.isForceCompactLayoutEnabled] / [FoldAwareLayoutHost]
 *    hooks, exactly as designed.
 *
 * All 8 layouts are pure presentations of one shared set of state fields
 * (language pair, last input/result, transcript, broadcast roster, ...) -
 * there is exactly one copy of that state, so switching layouts mid-flow
 * (e.g. folding the device mid-translation) never loses or duplicates it.
 */
class TranslateFragment : Fragment(), FoldAwareLayoutHost {

    override val settingsTab: SettingsTab = SettingsTab.TRANSLATE

    // ---------------------------------------------------------------------
    // Session state - the single source of truth, independent of whichever
    // of the 8 layouts is currently inflated.
    // ---------------------------------------------------------------------

    private lateinit var languageCodes: List<String>
    private var sourceCode: String = TranslateLanguage.ENGLISH
    private var targetCode: String = TranslateLanguage.SPANISH
    private var autoDetectEnabled = false
    private var genderMale = false

    private var lastInputText = ""
    private var lastResultText = ""
    private var detectedLanguageText = ""
    private var micStatusText = ""
    private var modelStatusText = "Checking translation pack status..."
    private var sttStatusText = "Checking voice-input pack status..."
    private var naturalVoiceStatusText = ""
    private var circleState = CircleState.PROMPT

    /** "live_transcript" session history, newest first. */
    private val transcript = mutableListOf<TranscriptEntry>()

    /** "multi_broadcast" target-language roster + latest result per target. */
    private val broadcastTargets = mutableListOf<String>()
    private val broadcastResults = mutableMapOf<String, String>()
    private var broadcastSourceText = ""
    private var broadcastStatusText = ""

    /** "face_to_face": each side shows the OTHER side's most recent translated utterance - see [translateDirectional]. */
    private var faceTopResult = ""
    private var faceBottomResult = ""
    private var faceTopStatus = ""
    private var faceBottomStatus = ""

    /** "across_table": same cross-routing as face_to_face, Flex-Mode version. */
    private var acrossTopResult = ""
    private var acrossBottomResult = ""
    private var acrossTopStatus = ""
    private var acrossBottomStatus = ""

    private val mainActivity get() = activity as? MainActivity
    private val languageIdentifier by lazy { LanguageIdentification.getClient() }
    private lateinit var foldPostureProvider: FoldPostureProvider
    private var layoutPrefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    private var currentPosture: FoldPosture = FoldPosture.NO_FOLDING_FEATURE
    private var currentFeature: FoldingFeature? = null
    private var coverForced = false
    private var currentActive: ActiveLayout? = null

    // ---------------------------------------------------------------------
    // View plumbing - exactly one of these 8 is non-null at a time.
    // ---------------------------------------------------------------------

    private var contentContainer: FrameLayout? = null
    private var defaultBinding: FragmentTranslateBinding? = null
    private var coverSingleCircleBinding: FragmentTranslateCoverSingleCircleBinding? = null
    private var coverLiveTranscriptBinding: FragmentTranslateCoverLiveTranscriptBinding? = null
    private var coverFaceToFaceBinding: FragmentTranslateCoverFaceToFaceBinding? = null
    private var flexDefaultBinding: FragmentTranslateFlexDefaultBinding? = null
    private var flexAcrossTableBinding: FragmentTranslateFlexAcrossTableBinding? = null
    private var flexMultiBroadcastBinding: FragmentTranslateFlexMultiBroadcastBinding? = null
    private var flexMirrorPanesBinding: FragmentTranslateFlexMirrorPanesBinding? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = FrameLayout(requireContext())
        root.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        contentContainer = root
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        languageCodes = LanguageCatalog.codes
        sourceCode = languageCodes.indexOf(TranslateLanguage.ENGLISH).let { if (it >= 0) languageCodes[it] else languageCodes[0] }
        targetCode = languageCodes.indexOf(TranslateLanguage.SPANISH).let { idx ->
            if (idx >= 0) languageCodes[idx] else languageCodes[1.coerceAtMost(languageCodes.size - 1)]
        }
        genderMale = VoicePreferences.getGender(requireContext()) == VoiceGender.MALE
        broadcastTargets.add(targetCode)
        coverForced = LayoutPreferences.isForceCompactLayoutEnabled(requireContext())

        registerLayoutPrefsListener()
        // Render once immediately with what we know so far (book-portrait/
        // default, or cover if force-compact is already on) so the screen is
        // never blank waiting on the first FoldingFeature emission below.
        renderActiveLayout()
        observeFoldPosture()
    }

    // ---------------------------------------------------------------------
    // FoldAwareLayoutHost - pushed by MainActivity's existing fold-close
    // heuristic and the Fold behavior screen's manual force-compact toggle.
    // See class doc comment for why this is the only "am I on the cover
    // screen" signal this tab relies on.
    // ---------------------------------------------------------------------

    override fun applyCoverLayout(variantId: String) {
        coverForced = true
        if (contentContainer != null) renderActiveLayout()
    }

    override fun applyDefaultLayout() {
        coverForced = false
        if (contentContainer != null) renderActiveLayout()
    }

    // ---------------------------------------------------------------------
    // Flex Mode - this Fragment's own FoldPostureProvider subscription,
    // same pattern as ConversationsFragment/FoldBehaviorFragment.
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
        renderActiveLayout()
    }

    // ---------------------------------------------------------------------
    // Live settings change - see TranslateLayoutSettingsFragment's doc
    // comment for why this reads the SAME SharedPreferences file
    // LayoutPreferences uses (by name, not by touching that foundation
    // file) rather than exposing a new API on it.
    // ---------------------------------------------------------------------

    private fun registerLayoutPrefsListener() {
        val prefs = requireContext().applicationContext.getSharedPreferences(LAYOUT_PREFS_FILE_NAME, Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == LAYOUT_KEY_COVER || key == LAYOUT_KEY_FLEX) {
                if (contentContainer != null) renderActiveLayout()
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

    private fun coverActiveLayout(variantId: String): ActiveLayout = when (variantId) {
        TranslateCoverVariant.SINGLE_CIRCLE -> ActiveLayout.COVER_SINGLE_CIRCLE
        TranslateCoverVariant.LIVE_TRANSCRIPT -> ActiveLayout.COVER_LIVE_TRANSCRIPT
        TranslateCoverVariant.FACE_TO_FACE -> ActiveLayout.COVER_FACE_TO_FACE
        else -> ActiveLayout.DEFAULT
    }

    private fun flexActiveLayout(variantId: String): ActiveLayout = when (variantId) {
        TranslateFlexVariant.ACROSS_TABLE -> ActiveLayout.FLEX_ACROSS_TABLE
        TranslateFlexVariant.MULTI_BROADCAST -> ActiveLayout.FLEX_MULTI_BROADCAST
        TranslateFlexVariant.MIRROR_PANES -> ActiveLayout.FLEX_MIRROR_PANES
        else -> ActiveLayout.FLEX_DEFAULT
    }

    private fun renderActiveLayout() {
        val target = when {
            coverForced -> coverActiveLayout(LayoutPreferences.getVariant(requireContext(), SettingsTab.TRANSLATE, ScreenMode.COVER))
            currentPosture.isMirroredTabletop -> flexActiveLayout(LayoutPreferences.getVariant(requireContext(), SettingsTab.TRANSLATE, ScreenMode.FLEX))
            else -> ActiveLayout.DEFAULT
        }
        if (target == currentActive) {
            refreshAllContent()
            currentFeature?.let { applyFlexGeometryIfNeeded(it) }
            return
        }
        switchTo(target)
    }

    private fun switchTo(target: ActiveLayout) {
        val container = contentContainer ?: return
        // Stop any in-flight mic session before swapping views out from under it.
        mainActivity?.app?.mic?.stop()
        container.removeAllViews()
        defaultBinding = null
        coverSingleCircleBinding = null
        coverLiveTranscriptBinding = null
        coverFaceToFaceBinding = null
        flexDefaultBinding = null
        flexAcrossTableBinding = null
        flexMultiBroadcastBinding = null
        flexMirrorPanesBinding = null

        when (target) {
            ActiveLayout.DEFAULT -> {
                val b = FragmentTranslateBinding.inflate(layoutInflater, container, false)
                defaultBinding = b; container.addView(b.root); bindDefault(b)
            }
            ActiveLayout.COVER_SINGLE_CIRCLE -> {
                val b = FragmentTranslateCoverSingleCircleBinding.inflate(layoutInflater, container, false)
                coverSingleCircleBinding = b; container.addView(b.root); bindSingleCircle(b)
            }
            ActiveLayout.COVER_LIVE_TRANSCRIPT -> {
                val b = FragmentTranslateCoverLiveTranscriptBinding.inflate(layoutInflater, container, false)
                coverLiveTranscriptBinding = b; container.addView(b.root); bindLiveTranscript(b)
            }
            ActiveLayout.COVER_FACE_TO_FACE -> {
                val b = FragmentTranslateCoverFaceToFaceBinding.inflate(layoutInflater, container, false)
                coverFaceToFaceBinding = b; container.addView(b.root); bindFaceToFace(b)
            }
            ActiveLayout.FLEX_DEFAULT -> {
                val b = FragmentTranslateFlexDefaultBinding.inflate(layoutInflater, container, false)
                flexDefaultBinding = b; container.addView(b.root); bindFlexDefault(b)
            }
            ActiveLayout.FLEX_ACROSS_TABLE -> {
                val b = FragmentTranslateFlexAcrossTableBinding.inflate(layoutInflater, container, false)
                flexAcrossTableBinding = b; container.addView(b.root); bindAcrossTable(b)
            }
            ActiveLayout.FLEX_MULTI_BROADCAST -> {
                val b = FragmentTranslateFlexMultiBroadcastBinding.inflate(layoutInflater, container, false)
                flexMultiBroadcastBinding = b; container.addView(b.root); bindMultiBroadcast(b)
            }
            ActiveLayout.FLEX_MIRROR_PANES -> {
                val b = FragmentTranslateFlexMirrorPanesBinding.inflate(layoutInflater, container, false)
                flexMirrorPanesBinding = b; container.addView(b.root); bindMirrorPanes(b)
            }
        }

        // Choreographer-driven crossfade (ViewPropertyAnimator, not a fixed
        // postDelayed loop - spec §5's 120Hz-display note), same technique
        // ConversationsFragment.switchLayout already uses.
        container.alpha = 0f
        container.animate().alpha(1f).setDuration(200L).start()

        currentActive = target
        refreshAllContent()
        currentFeature?.let { applyFlexGeometryIfNeeded(it) }
    }

    // ---------------------------------------------------------------------
    // Flex-Mode (tabletop) pane geometry - shared by all 4 Flex variants.
    // Same technique as ConversationsFragment.applyMirroredGeometry: never a
    // static 50/50 split, always derived from the live FoldingFeature.bounds,
    // with an extra inset when occlusionType is FULL to keep content off the
    // physically-occluded crease (task item 5 / spec §2).
    // ---------------------------------------------------------------------

    private fun applyFlexGeometryIfNeeded(feature: FoldingFeature) {
        when (currentActive) {
            ActiveLayout.FLEX_DEFAULT -> flexDefaultBinding?.let {
                positionFlexPanes(feature, it.paneViewing.root, it.paneControl.root, rotateTop = false)
            }
            ActiveLayout.FLEX_ACROSS_TABLE -> flexAcrossTableBinding?.let {
                positionFlexPanes(feature, it.paneAcrossTop.root, it.paneAcrossBottom.root, rotateTop = true)
            }
            ActiveLayout.FLEX_MULTI_BROADCAST -> flexMultiBroadcastBinding?.let {
                positionFlexPanes(feature, it.paneBroadcastViewing, it.paneBroadcastControl, rotateTop = false)
            }
            ActiveLayout.FLEX_MIRROR_PANES -> flexMirrorPanesBinding?.let { b ->
                positionFlexPanes(feature, b.cardMirrorSource, b.cardMirrorTarget, rotateTop = false)
                positionMirrorPanesFab(feature, b)
            }
            else -> {}
        }
    }

    private fun positionFlexPanes(feature: FoldingFeature, topView: View, bottomView: View, rotateTop: Boolean) {
        val container = contentContainer ?: return
        if (container.height == 0) {
            // Not laid out yet (first frame) - defer one pass, same
            // technique as ConversationsFragment.applyMirroredGeometry.
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
        topView.rotation = if (rotateTop) 180f else 0f

        bottomView.layoutParams = (bottomView.layoutParams as FrameLayout.LayoutParams).apply {
            width = FrameLayout.LayoutParams.MATCH_PARENT
            height = bottomHeight
            topMargin = bottomTop
        }
        bottomView.rotation = 0f
    }

    /**
     * "mirror_panes"' swap FAB reconciliation between its own brief
     * ("straddles the hinge seam") and task item 5's harder requirement
     * (keep touchable elements off the occluded crease): anchors the FAB's
     * position to the boundary of the occlusion-free zone (just below the
     * hinge), so the majority of its touchable bounds sit in the clear
     * bottom pane, while letting it visually overlap upward over the seam by
     * [FAB_OVERLAP_DP] so it still reads as straddling the hinge.
     */
    private fun positionMirrorPanesFab(feature: FoldingFeature, b: FragmentTranslateFlexMirrorPanesBinding) {
        val container = contentContainer ?: return
        if (container.height == 0) return // positionFlexPanes' own re-post already covers "not laid out yet"
        val fab = b.cardFabSwap
        val loc = IntArray(2)
        container.getLocationInWindow(loc)
        val containerTopInWindow = loc[1]
        val hingeBottomLocal = feature.bounds.bottom - containerTopInWindow
        val density = resources.displayMetrics.density
        val fabSizePx = (52 * density).toInt()
        val overlapPx = (FAB_OVERLAP_DP * density).toInt()
        fab.layoutParams = (fab.layoutParams as FrameLayout.LayoutParams).apply {
            width = fabSizePx
            height = fabSizePx
            gravity = Gravity.TOP or Gravity.START
            topMargin = (hingeBottomLocal - overlapPx).coerceAtLeast(0)
            leftMargin = (container.width - fabSizePx) / 2
        }
    }

    // ---------------------------------------------------------------------
    // Shared language-pair / translate / mic / speak primitives - reused
    // across every one of the 8 layouts (task item 5: these are alternate
    // presentations of the real underlying behavior, not new mock features).
    // ---------------------------------------------------------------------

    private fun selectedGender(): VoiceGender = if (genderMale) VoiceGender.MALE else VoiceGender.FEMALE

    private fun swapLanguages() {
        val s = sourceCode
        sourceCode = targetCode
        targetCode = s
        onLanguagePairChanged()
    }

    private fun setSourceTarget(src: String, tgt: String) {
        sourceCode = src
        targetCode = tgt
        onLanguagePairChanged()
    }

    private fun onLanguagePairChanged() {
        refreshAllContent()
        refreshModelStatus()
        refreshSttStatus()
        refreshNaturalVoiceStatus()
    }

    private fun toast(msg: String, long: Boolean = false) {
        if (isAdded) Toast.makeText(requireContext(), msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    /** Same auto-detect-or-not flow as the tab's original single layout, generalized to write into shared state instead of one fixed binding. */
    private fun performTranslate(rawText: String) {
        val text = rawText.trim()
        if (text.isEmpty()) { toast("Type or speak something first"); return }
        if (autoDetectEnabled) {
            languageIdentifier.identifyLanguage(text)
                .addOnSuccessListener { code ->
                    if (contentContainer == null) return@addOnSuccessListener
                    if (code == "und") {
                        toast("Couldn't detect the language, please pick one manually", long = true)
                    } else {
                        detectedLanguageText = "Detected source language: ${LanguageCatalog.displayNameFor(code)}"
                        translateWith(code, targetCode, text)
                    }
                }
                .addOnFailureListener { e -> toast("Language detection failed: ${e.message}", long = true) }
        } else {
            detectedLanguageText = ""
            translateWith(sourceCode, targetCode, text)
        }
    }

    private fun translateWith(srcCode: String, tgtCode: String, text: String) {
        lastResultText = "Translating..."
        refreshAllContent()
        TranslationEngine.translate(srcCode, tgtCode, text,
            onResult = onResult@{ translated ->
                if (contentContainer == null) return@onResult
                lastResultText = translated
                circleState = CircleState.RESULT
                refreshAllContent()
                refreshModelStatus()
            },
            onError = onError@{ err ->
                if (contentContainer == null) return@onError
                lastResultText = ""
                circleState = CircleState.PROMPT
                toast("Translation failed: $err", long = true)
                refreshAllContent()
            }
        )
    }

    /** Cross-pane translate used by face_to_face/across_table: translates srcCode->tgtCode and hands the result to the OTHER pane via [resultSetter]. */
    private fun translateDirectional(
        srcCode: String, tgtCode: String, text: String,
        resultSetter: (String) -> Unit,
        statusSetter: ((String) -> Unit)? = null,
        speak: Boolean = false
    ) {
        statusSetter?.invoke("Translating...")
        refreshAllContent()
        TranslationEngine.translate(srcCode, tgtCode, text,
            onResult = onResult@{ translated ->
                if (contentContainer == null) return@onResult
                resultSetter(translated)
                statusSetter?.invoke("")
                refreshAllContent()
                if (speak) {
                    mainActivity?.app?.tts?.speak(translated, tgtCode, selectedGender(), onDone = {}, onError = {})
                }
            },
            onError = onError@{ err ->
                if (contentContainer == null) return@onError
                statusSetter?.invoke("")
                refreshAllContent()
                toast("Translation failed: $err", long = true)
            }
        )
    }

    private fun speakLastResult() {
        val app = mainActivity?.app ?: return
        if (lastResultText.isBlank() || lastResultText == "Translating...") { toast("Nothing to speak yet"); return }
        app.tts.speak(lastResultText, targetCode, selectedGender(), onDone = {}, onError = { err -> if (isAdded) toast(err) })
    }

    /** Starts a mic capture unconditionally (caller decides start/stop semantics) - used directly by "single_circle"'s hold-to-talk gesture. */
    private fun beginMicCapture(speakCode: String, statusSetter: ((String) -> Unit)?, onTranscribed: (String) -> Unit) {
        val activity = mainActivity ?: return
        if (!activity.hasMicPermission()) {
            activity.requestMicPermissionIfNeeded()
            toast("Grant microphone permission, then try again", long = true)
            return
        }
        val app = activity.app
        val info = VoskModelCatalog.forLanguage(speakCode)
        if (info == null) {
            toast("No offline voice-input model for ${LanguageCatalog.displayNameFor(speakCode)}", long = true)
            return
        }
        if (!app.vosk.isModelDownloaded(speakCode)) {
            toast("Download the voice-input pack for ${LanguageCatalog.displayNameFor(speakCode)} on the main Translate screen first", long = true)
            return
        }
        statusSetter?.invoke("Loading voice-input model...")
        refreshAllContent()
        app.vosk.loadModelAsync(speakCode) { success, error ->
            if (contentContainer == null) return@loadModelAsync
            if (!success) {
                statusSetter?.invoke("")
                refreshAllContent()
                toast("Couldn't load voice-input model: $error", long = true)
                return@loadModelAsync
            }
            val recognizer = app.vosk.newRecognizer()
            if (recognizer == null) {
                statusSetter?.invoke("")
                refreshAllContent()
                toast("Couldn't start recognizer", long = true)
                return@loadModelAsync
            }
            statusSetter?.invoke("Listening...")
            refreshAllContent()
            app.mic.start(recognizer, recordToFile = null, listener = object : MicPipeline.Listener {
                override fun onFinal(text: String) {
                    if (contentContainer == null) return
                    statusSetter?.invoke("")
                    onTranscribed(text)
                }
                override fun onError(message: String) {
                    if (contentContainer != null) { statusSetter?.invoke(""); refreshAllContent(); toast(message) }
                }
                override fun onListeningStopped() {
                    if (contentContainer != null) { statusSetter?.invoke(""); refreshAllContent() }
                }
            })
        }
    }

    /** Tap-to-start/tap-to-stop mic, used by every layout except "single_circle" (which holds/releases directly via [beginMicCapture]). */
    private fun runMicToggle(speakCode: String, statusSetter: ((String) -> Unit)?, onTranscribed: (String) -> Unit) {
        val app = mainActivity?.app ?: return
        if (app.mic.isRunning()) { app.mic.stop(); return }
        beginMicCapture(speakCode, statusSetter, onTranscribed)
    }

    private fun recentPairs(): List<Pair<String, String>> {
        val seen = LinkedHashSet<Pair<String, String>>()
        for (e in transcript) {
            seen.add(e.sourceCode to e.targetCode)
            if (seen.size >= 6) break
        }
        if (seen.isEmpty()) seen.add(sourceCode to targetCode)
        return seen.toList()
    }

    private fun shortCode(code: String) = code.uppercase()

    private fun setupSpinnerPair(sourceSpinner: Spinner, targetSpinner: Spinner) {
        val names = languageCodes.map { LanguageCatalog.displayNameFor(it) }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        sourceSpinner.adapter = adapter
        targetSpinner.adapter = adapter
        sourceSpinner.setSelection(languageCodes.indexOf(sourceCode).coerceAtLeast(0))
        targetSpinner.setSelection(languageCodes.indexOf(targetCode).coerceAtLeast(0))
        val listener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                sourceCode = languageCodes[sourceSpinner.selectedItemPosition]
                targetCode = languageCodes[targetSpinner.selectedItemPosition]
                onLanguagePairChanged()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        sourceSpinner.onItemSelectedListener = listener
        targetSpinner.onItemSelectedListener = listener
    }

    private fun syncSpinnerSelections(sourceSpinner: Spinner, targetSpinner: Spinner) {
        val srcIdx = languageCodes.indexOf(sourceCode)
        val tgtIdx = languageCodes.indexOf(targetCode)
        if (srcIdx >= 0 && sourceSpinner.selectedItemPosition != srcIdx) sourceSpinner.setSelection(srcIdx)
        if (tgtIdx >= 0 && targetSpinner.selectedItemPosition != tgtIdx) targetSpinner.setSelection(tgtIdx)
    }

    private fun setupSpinnerSingle(spinner: Spinner) {
        val names = languageCodes.map { LanguageCatalog.displayNameFor(it) }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.setSelection(languageCodes.indexOf(sourceCode).coerceAtLeast(0))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                sourceCode = languageCodes[spinner.selectedItemPosition]
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    // ---------------------------------------------------------------------
    // Central refresh dispatcher - each refreshXxxContent below is a no-op
    // unless its binding is the one currently inflated.
    // ---------------------------------------------------------------------

    private fun refreshAllContent() {
        defaultBinding?.let { refreshDefaultContent(it) }
        coverSingleCircleBinding?.let { refreshSingleCircleContent(it) }
        coverLiveTranscriptBinding?.let { refreshLiveTranscriptContent(it) }
        coverFaceToFaceBinding?.let { refreshFaceToFaceContent(it) }
        flexDefaultBinding?.let { refreshFlexDefaultContent(it) }
        flexAcrossTableBinding?.let { refreshAcrossTableContent(it) }
        flexMultiBroadcastBinding?.let { refreshMultiBroadcastContent(it) }
        flexMirrorPanesBinding?.let { refreshMirrorPanesContent(it) }
    }

    // =======================================================================
    // "default" - the tab's original full layout, reused unchanged for both
    // book-portrait/non-fold AND ScreenMode.COVER's "default" variant.
    // =======================================================================

    private fun bindDefault(b: FragmentTranslateBinding) {
        setupSpinnerPair(b.spinnerSource, b.spinnerTarget)

        b.radioMale.isChecked = genderMale
        b.radioFemale.isChecked = !genderMale
        b.radioGroupGender.setOnCheckedChangeListener { _, _ ->
            genderMale = b.radioMale.isChecked
            VoicePreferences.setGender(requireContext(), selectedGender())
            refreshNaturalVoiceStatus()
        }

        b.checkboxAutoDetect.isChecked = autoDetectEnabled
        b.spinnerSource.isEnabled = !autoDetectEnabled
        b.spinnerSource.alpha = if (autoDetectEnabled) 0.5f else 1f
        b.checkboxAutoDetect.setOnCheckedChangeListener { _, checked ->
            autoDetectEnabled = checked
            b.spinnerSource.isEnabled = !checked
            b.spinnerSource.alpha = if (checked) 0.5f else 1f
        }

        b.btnSwapLanguages.setOnClickListener {
            if (autoDetectEnabled) return@setOnClickListener
            swapLanguages()
        }
        b.btnDownloadModels.setOnClickListener { downloadTranslateModels() }
        b.btnDownloadStt.setOnClickListener { downloadSttModel() }
        b.btnDownloadNaturalVoice.setOnClickListener { downloadNaturalVoice() }
        b.btnTranslate.setOnClickListener {
            lastInputText = b.editInput.text?.toString().orEmpty()
            performTranslate(lastInputText)
        }
        b.btnMic.setOnClickListener {
            runMicToggle(sourceCode, { s -> micStatusText = s; refreshAllContent() }) { text ->
                b.editInput.setText(text)
                b.editInput.setSelection(text.length)
                lastInputText = text
                performTranslate(text)
            }
        }
        b.btnSpeak.setOnClickListener { speakLastResult() }
        if (lastInputText.isNotEmpty()) b.editInput.setText(lastInputText)

        refreshModelStatus()
        refreshSttStatus()
        refreshNaturalVoiceStatus()
    }

    private fun refreshDefaultContent(b: FragmentTranslateBinding) {
        syncSpinnerSelections(b.spinnerSource, b.spinnerTarget)
        b.textResult.text = lastResultText
        b.textDetected.text = detectedLanguageText
        b.textMicStatus.text = micStatusText
        b.textModelStatus.text = modelStatusText
        b.textSttStatus.text = sttStatusText
        b.textNaturalVoiceStatus.text = naturalVoiceStatusText
    }

    private fun refreshModelStatus() {
        RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java)
            .addOnSuccessListener { models ->
                if (contentContainer == null) return@addOnSuccessListener
                val downloaded = models.map { it.language }.toSet()
                val srcOk = downloaded.contains(sourceCode)
                val tgtOk = downloaded.contains(targetCode)
                modelStatusText = when {
                    srcOk && tgtOk -> "Both translation packs downloaded — works fully offline, no network needed."
                    srcOk || tgtOk -> "One translation pack downloaded, one still needed — tap Download (needs Wi-Fi)."
                    else -> "Translation packs not downloaded yet — tap Download once on Wi-Fi, then it's offline."
                }
                defaultBinding?.textModelStatus?.text = modelStatusText
            }
            .addOnFailureListener {
                if (contentContainer == null) return@addOnFailureListener
                modelStatusText = "Could not check translation pack status."
                defaultBinding?.textModelStatus?.text = modelStatusText
            }
    }

    private fun downloadTranslateModels() {
        val src = sourceCode
        val tgt = targetCode
        modelStatusText = "Downloading translation packs (Wi-Fi required)..."
        defaultBinding?.textModelStatus?.text = modelStatusText
        TranslationEngine.downloadModel(src, requireWifi = true) { okSrc, errSrc ->
            if (contentContainer == null) return@downloadModel
            if (!okSrc) {
                toast("Download failed: $errSrc", long = true)
                refreshModelStatus()
                return@downloadModel
            }
            TranslationEngine.downloadModel(tgt, requireWifi = true) { okTgt, errTgt ->
                if (contentContainer == null) return@downloadModel
                if (okTgt) toast("Translation packs downloaded. Offline from now on.")
                else toast("Download failed: $errTgt", long = true)
                refreshModelStatus()
            }
        }
    }

    private fun refreshSttStatus() {
        val app = mainActivity?.app ?: return
        val code = sourceCode
        val info = VoskModelCatalog.forLanguage(code)
        if (info == null) {
            sttStatusText = "No offline voice-input model available for ${LanguageCatalog.displayNameFor(code)}."
            defaultBinding?.textSttStatus?.text = sttStatusText
            defaultBinding?.btnDownloadStt?.visibility = View.GONE
            return
        }
        defaultBinding?.btnDownloadStt?.visibility = View.VISIBLE
        sttStatusText = if (app.vosk.isModelDownloaded(code)) {
            "Voice-input pack for ${info.displayName} downloaded — mic works fully offline."
        } else {
            "Voice-input pack for ${info.displayName} not downloaded (~${info.approxSizeMiB}MB, Wi-Fi)."
        }
        defaultBinding?.textSttStatus?.text = sttStatusText
    }

    private fun downloadSttModel() {
        val app = mainActivity?.app ?: return
        val code = sourceCode
        val info = VoskModelCatalog.forLanguage(code) ?: return
        sttStatusText = "Downloading voice-input pack (Wi-Fi required)..."
        defaultBinding?.textSttStatus?.text = sttStatusText
        DownloadManager.downloadAndUnzip(
            requireContext(), info.url, app.vosk.modelRootDir(code), requireWifi = true,
            onProgress = { pct ->
                if (contentContainer != null) {
                    sttStatusText = "Downloading voice-input pack... $pct%"
                    defaultBinding?.textSttStatus?.text = sttStatusText
                }
            }
        ) { success, error ->
            if (contentContainer == null) return@downloadAndUnzip
            if (success) toast("Voice-input pack downloaded. Mic works offline now.")
            else toast("Download failed: $error", long = true)
            refreshSttStatus()
        }
    }

    private fun refreshNaturalVoiceStatus() {
        val app = mainActivity?.app ?: return
        val code = targetCode
        val gender = selectedGender()
        val info = app.tts.naturalVoiceInfo(code, gender)
        if (info == null) {
            val genderLabel = if (gender == VoiceGender.MALE) "male" else "female"
            naturalVoiceStatusText = "No natural $genderLabel voice available yet for ${LanguageCatalog.displayNameFor(code)} - eSpeak (built-in, robotic) will be used."
            defaultBinding?.textNaturalVoiceStatus?.text = naturalVoiceStatusText
            defaultBinding?.btnDownloadNaturalVoice?.visibility = View.GONE
            return
        }
        defaultBinding?.btnDownloadNaturalVoice?.visibility = View.VISIBLE
        if (app.tts.isNaturalVoiceDownloaded(code, gender)) {
            naturalVoiceStatusText = "Natural voice (${info.displayName}) downloaded — used automatically instead of eSpeak."
            defaultBinding?.textNaturalVoiceStatus?.text = naturalVoiceStatusText
            defaultBinding?.btnDownloadNaturalVoice?.text = "Re-download natural voice"
        } else {
            naturalVoiceStatusText = "Natural voice available: ${info.displayName} (~${info.approxSizeMiB}MB, Wi-Fi, ${info.license}). Falls back to eSpeak (robotic) until downloaded."
            defaultBinding?.textNaturalVoiceStatus?.text = naturalVoiceStatusText
            defaultBinding?.btnDownloadNaturalVoice?.text = "Download natural voice (Wi-Fi)"
        }
    }

    private fun downloadNaturalVoice() {
        val app = mainActivity?.app ?: return
        val code = targetCode
        val gender = selectedGender()
        naturalVoiceStatusText = "Downloading natural voice (Wi-Fi required)..."
        defaultBinding?.textNaturalVoiceStatus?.text = naturalVoiceStatusText
        app.tts.downloadNaturalVoice(
            requireContext(), code, gender,
            onProgress = { pct ->
                if (contentContainer != null) {
                    naturalVoiceStatusText = "Downloading natural voice... $pct%"
                    defaultBinding?.textNaturalVoiceStatus?.text = naturalVoiceStatusText
                }
            }
        ) onDownloadDone@{ success, error ->
            if (contentContainer == null) return@onDownloadDone
            if (success) toast("Natural voice downloaded. Used automatically from now on.")
            else toast("Download failed: $error", long = true)
            refreshNaturalVoiceStatus()
        }
    }

    // =======================================================================
    // Cover: "single_circle" - hold to speak, release to see the translation,
    // tap the result to hear it, swipe to flip direction.
    // =======================================================================

    private fun bindSingleCircle(b: FragmentTranslateCoverSingleCircleBinding) {
        var downX = 0f
        var downTime = 0L
        var swipeHandled = false
        val holdRunnable = Runnable {
            circleState = CircleState.RECORDING
            refreshSingleCircleContent(b)
            beginMicCapture(
                sourceCode,
                { s -> if (contentContainer != null && s.isNotEmpty()) coverSingleCircleBinding?.textCircleContent?.text = s }
            ) { text ->
                circleState = CircleState.TRANSLATING
                refreshAllContent()
                translateWith(sourceCode, targetCode, text)
            }
        }
        b.cardCircle.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downTime = System.currentTimeMillis()
                    swipeHandled = false
                    v.postDelayed(holdRunnable, HOLD_THRESHOLD_MS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - downX
                    if (!swipeHandled && circleState != CircleState.RECORDING && Math.abs(deltaX) > SWIPE_THRESHOLD_PX) {
                        v.removeCallbacks(holdRunnable)
                        swipeHandled = true
                        swapLanguages()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(holdRunnable)
                    if (circleState == CircleState.RECORDING) {
                        mainActivity?.app?.mic?.stop()
                    } else if (!swipeHandled) {
                        v.performClick()
                        val held = System.currentTimeMillis() - downTime
                        if (held < HOLD_THRESHOLD_MS && circleState == CircleState.RESULT) {
                            speakLastResult()
                        }
                    }
                    true
                }
                else -> false
            }
        }
        refreshSingleCircleContent(b)
    }

    private fun refreshSingleCircleContent(b: FragmentTranslateCoverSingleCircleBinding) {
        b.textCircleLangPair.text = "${LanguageCatalog.displayNameFor(sourceCode)} → ${LanguageCatalog.displayNameFor(targetCode)}"
        val ctx = requireContext()
        when (circleState) {
            CircleState.PROMPT -> {
                b.textCircleContent.text = "Hold to speak"
                b.cardCircle.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.colorPrimary))
            }
            CircleState.RECORDING -> {
                b.textCircleContent.text = "Listening..."
                b.cardCircle.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.colorAccent))
            }
            CircleState.TRANSLATING -> {
                b.textCircleContent.text = "Translating..."
                b.cardCircle.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.colorAccent))
            }
            CircleState.RESULT -> {
                b.textCircleContent.text = lastResultText.ifBlank { "Hold to speak" }
                b.cardCircle.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.colorPrimaryDark))
            }
        }
    }

    // =======================================================================
    // Cover: "live_transcript" - chat-style session transcript, recent-pair
    // chips, pinned input row.
    // =======================================================================

    private fun bindLiveTranscript(b: FragmentTranslateCoverLiveTranscriptBinding) {
        if (lastInputText.isNotEmpty()) b.editLiveInput.setText(lastInputText)
        b.btnLiveSend.setOnClickListener {
            val text = b.editLiveInput.text?.toString().orEmpty()
            sendLiveTranscriptEntry(text)
            b.editLiveInput.setText("")
        }
        b.btnLiveMic.setOnClickListener {
            runMicToggle(sourceCode, { s -> micStatusText = s; refreshAllContent() }) { text ->
                sendLiveTranscriptEntry(text)
                b.editLiveInput.setText("")
            }
        }
        refreshLiveTranscriptContent(b)
    }

    private fun refreshLiveTranscriptContent(b: FragmentTranslateCoverLiveTranscriptBinding) {
        b.textLiveStatus.text = micStatusText

        b.chipRowRecentPairs.removeAllViews()
        for ((src, tgt) in recentPairs()) {
            val chip = TextView(requireContext())
            chip.text = "${shortCode(src)} → ${shortCode(tgt)}"
            chip.textSize = 12f
            val isActive = src == sourceCode && tgt == targetCode
            chip.setBackgroundResource(if (isActive) R.drawable.bg_chip_filled else R.drawable.bg_chip_outline)
            chip.setTextColor(if (isActive) 0xFFFFFFFF.toInt() else ContextCompat.getColor(requireContext(), R.color.colorPrimary))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = (6 * resources.displayMetrics.density).toInt()
            chip.layoutParams = lp
            chip.setOnClickListener { setSourceTarget(src, tgt) }
            b.chipRowRecentPairs.addView(chip)
        }

        b.transcriptList.removeAllViews()
        for (entry in transcript.asReversed()) {
            val bubble = ViewTranslateTranscriptBubbleBinding.inflate(layoutInflater, b.transcriptList, false)
            bubble.textBubblePairLabel.text = "${shortCode(entry.sourceCode)} → ${shortCode(entry.targetCode)}"
            bubble.textBubbleSource.text = entry.sourceText
            bubble.textBubbleTranslated.text = entry.translatedText
            b.transcriptList.addView(bubble.root)
        }
        b.scrollLiveTranscript.post { if (contentContainer != null) b.scrollLiveTranscript.fullScroll(View.FOCUS_DOWN) }
    }

    private fun sendLiveTranscriptEntry(text: String) {
        if (text.isBlank()) { toast("Type or speak something first"); return }
        val srcForThis = sourceCode
        val tgtForThis = targetCode
        if (autoDetectEnabled) {
            languageIdentifier.identifyLanguage(text)
                .addOnSuccessListener { code ->
                    if (contentContainer == null) return@addOnSuccessListener
                    translateForTranscript(if (code != "und") code else srcForThis, tgtForThis, text)
                }
                .addOnFailureListener { if (contentContainer != null) translateForTranscript(srcForThis, tgtForThis, text) }
        } else {
            translateForTranscript(srcForThis, tgtForThis, text)
        }
    }

    private fun translateForTranscript(src: String, tgt: String, text: String) {
        TranslationEngine.translate(src, tgt, text,
            onResult = onResult@{ translated ->
                if (contentContainer == null) return@onResult
                transcript.add(0, TranscriptEntry(src, tgt, text, translated))
                coverLiveTranscriptBinding?.let { refreshLiveTranscriptContent(it) }
            },
            onError = onError@{ err -> if (contentContainer == null) return@onError; toast("Translation failed: $err", long = true) }
        )
    }

    // =======================================================================
    // Cover: "face_to_face" - top/bottom midpoint split, top rendered
    // upside-down, each half's mic result routed to the OTHER half.
    // =======================================================================

    private fun bindFaceToFace(b: FragmentTranslateCoverFaceToFaceBinding) {
        b.paneFaceTop.btnPaneMic.setOnClickListener {
            runMicToggle(sourceCode, { s -> faceTopStatus = s; refreshAllContent() }) { text ->
                translateDirectional(
                    sourceCode, targetCode, text,
                    resultSetter = { faceBottomResult = it },
                    statusSetter = { faceTopStatus = it },
                    speak = true
                )
            }
        }
        b.paneFaceBottom.btnPaneMic.setOnClickListener {
            runMicToggle(targetCode, { s -> faceBottomStatus = s; refreshAllContent() }) { text ->
                translateDirectional(
                    targetCode, sourceCode, text,
                    resultSetter = { faceTopResult = it },
                    statusSetter = { faceBottomStatus = it },
                    speak = true
                )
            }
        }
        refreshFaceToFaceContent(b)
    }

    private fun refreshFaceToFaceContent(b: FragmentTranslateCoverFaceToFaceBinding) {
        b.paneFaceTop.textPaneLang.text = LanguageCatalog.displayNameFor(sourceCode)
        b.paneFaceTop.textPaneResult.text = faceTopResult
        b.paneFaceTop.textPaneStatus.text = faceTopStatus
        b.paneFaceBottom.textPaneLang.text = LanguageCatalog.displayNameFor(targetCode)
        b.paneFaceBottom.textPaneResult.text = faceBottomResult
        b.paneFaceBottom.textPaneStatus.text = faceBottomStatus
    }

    // =======================================================================
    // Flex: "flex_default" - viewing pane (result) above the hinge, control
    // pane (pickers/input/mic/translate) below - this project's established
    // Flex Mode split, new baseline for this tab (it had none before).
    // =======================================================================

    private fun bindFlexDefault(b: FragmentTranslateFlexDefaultBinding) {
        setupSpinnerPair(b.paneControl.spinnerFlexSource, b.paneControl.spinnerFlexTarget)

        b.paneControl.checkboxFlexAutoDetect.isChecked = autoDetectEnabled
        b.paneControl.spinnerFlexSource.isEnabled = !autoDetectEnabled
        b.paneControl.checkboxFlexAutoDetect.setOnCheckedChangeListener { _, checked ->
            autoDetectEnabled = checked
            b.paneControl.spinnerFlexSource.isEnabled = !checked
        }

        if (lastInputText.isNotEmpty()) b.paneControl.editFlexInput.setText(lastInputText)
        b.paneControl.btnFlexSwap.setOnClickListener { if (!autoDetectEnabled) swapLanguages() }
        b.paneControl.btnFlexTranslate.setOnClickListener {
            lastInputText = b.paneControl.editFlexInput.text?.toString().orEmpty()
            performTranslate(lastInputText)
        }
        b.paneControl.btnFlexMic.setOnClickListener {
            runMicToggle(sourceCode, { s -> micStatusText = s; refreshAllContent() }) { text ->
                b.paneControl.editFlexInput.setText(text)
                lastInputText = text
                performTranslate(text)
            }
        }
        b.paneViewing.btnFlexSpeak.setOnClickListener { speakLastResult() }
        refreshFlexDefaultContent(b)
    }

    private fun refreshFlexDefaultContent(b: FragmentTranslateFlexDefaultBinding) {
        b.paneViewing.textFlexResult.text = lastResultText.ifBlank { "Your translation will appear here." }
        b.paneViewing.textFlexDetected.text = detectedLanguageText
        b.paneControl.textFlexMicStatus.text = micStatusText
        syncSpinnerSelections(b.paneControl.spinnerFlexSource, b.paneControl.spinnerFlexTarget)
    }

    // =======================================================================
    // Flex: "across_table" - hinge separates two people; each half has its
    // own mic + translated-text zone near the hinge, cross-routed like
    // face_to_face.
    // =======================================================================

    private fun bindAcrossTable(b: FragmentTranslateFlexAcrossTableBinding) {
        b.paneAcrossTop.btnAcrossMic.setOnClickListener {
            runMicToggle(sourceCode, { s -> acrossTopStatus = s; refreshAllContent() }) { text ->
                translateDirectional(
                    sourceCode, targetCode, text,
                    resultSetter = { acrossBottomResult = it },
                    statusSetter = { acrossTopStatus = it },
                    speak = true
                )
            }
        }
        b.paneAcrossBottom.btnAcrossMic.setOnClickListener {
            runMicToggle(targetCode, { s -> acrossBottomStatus = s; refreshAllContent() }) { text ->
                translateDirectional(
                    targetCode, sourceCode, text,
                    resultSetter = { acrossTopResult = it },
                    statusSetter = { acrossBottomStatus = it },
                    speak = true
                )
            }
        }
        refreshAcrossTableContent(b)
    }

    private fun refreshAcrossTableContent(b: FragmentTranslateFlexAcrossTableBinding) {
        b.paneAcrossTop.textAcrossLang.text = LanguageCatalog.displayNameFor(sourceCode)
        b.paneAcrossTop.textAcrossResult.text = acrossTopResult
        b.paneAcrossTop.textAcrossStatus.text = acrossTopStatus
        b.paneAcrossBottom.textAcrossLang.text = LanguageCatalog.displayNameFor(targetCode)
        b.paneAcrossBottom.textAcrossResult.text = acrossBottomResult
        b.paneAcrossBottom.textAcrossStatus.text = acrossBottomStatus
    }

    // =======================================================================
    // Flex: "multi_broadcast" - viewing pane is a scrollable stack of
    // results, one row per roster target language; control pane manages the
    // roster + the phrase to broadcast.
    // =======================================================================

    private fun bindMultiBroadcast(b: FragmentTranslateFlexMultiBroadcastBinding) {
        setupSpinnerSingle(b.spinnerBroadcastSource)
        if (broadcastSourceText.isNotEmpty()) b.editBroadcastInput.setText(broadcastSourceText)
        b.btnAddBroadcastTarget.setOnClickListener { showAddBroadcastTargetDialog() }
        b.btnBroadcastSend.setOnClickListener {
            broadcastSourceText = b.editBroadcastInput.text?.toString().orEmpty()
            broadcastToRoster(broadcastSourceText)
        }
        b.btnBroadcastMic.setOnClickListener {
            runMicToggle(sourceCode, { s -> broadcastStatusText = s; refreshAllContent() }) { text ->
                b.editBroadcastInput.setText(text)
                broadcastSourceText = text
                broadcastToRoster(text)
            }
        }
        refreshMultiBroadcastContent(b)
    }

    private fun refreshMultiBroadcastContent(b: FragmentTranslateFlexMultiBroadcastBinding) {
        val srcIdx = languageCodes.indexOf(sourceCode)
        if (srcIdx >= 0 && b.spinnerBroadcastSource.selectedItemPosition != srcIdx) b.spinnerBroadcastSource.setSelection(srcIdx)
        b.textBroadcastStatus.text = broadcastStatusText
        b.textBroadcastSourcePhrase.text = if (broadcastSourceText.isBlank()) {
            "No phrase broadcast yet."
        } else {
            "“$broadcastSourceText” (${LanguageCatalog.displayNameFor(sourceCode)})"
        }

        b.chipRowRoster.removeAllViews()
        for (code in broadcastTargets) {
            val chip = TextView(requireContext())
            chip.text = "${LanguageCatalog.displayNameFor(code)}  ✕"
            chip.textSize = 12f
            chip.setBackgroundResource(R.drawable.bg_chip_filled)
            chip.setTextColor(0xFFFFFFFF.toInt())
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = (6 * resources.displayMetrics.density).toInt()
            chip.layoutParams = lp
            chip.setOnClickListener {
                broadcastTargets.remove(code)
                broadcastResults.remove(code)
                refreshAllContent()
            }
            b.chipRowRoster.addView(chip)
        }

        b.broadcastRowList.removeAllViews()
        if (broadcastTargets.isEmpty()) {
            val tv = TextView(requireContext())
            tv.text = "No target languages yet - add one below."
            tv.textSize = 13f
            b.broadcastRowList.addView(tv)
        } else {
            for (code in broadcastTargets) {
                val row = ViewTranslateBroadcastRowBinding.inflate(layoutInflater, b.broadcastRowList, false)
                row.textBroadcastLang.text = LanguageCatalog.displayNameFor(code)
                row.textBroadcastResult.text = broadcastResults[code] ?: "—"
                row.btnBroadcastPlay.setOnClickListener {
                    val text = broadcastResults[code]
                    if (text.isNullOrBlank()) {
                        toast("Nothing to play yet for ${LanguageCatalog.displayNameFor(code)}")
                    } else {
                        mainActivity?.app?.tts?.speak(text, code, selectedGender(), onDone = {}, onError = { err -> toast(err) })
                    }
                }
                row.btnBroadcastRemove.setOnClickListener {
                    broadcastTargets.remove(code)
                    broadcastResults.remove(code)
                    refreshAllContent()
                }
                b.broadcastRowList.addView(row.root)
            }
        }
    }

    private fun showAddBroadcastTargetDialog() {
        val candidates = languageCodes.filter { it != sourceCode && it !in broadcastTargets }
        if (candidates.isEmpty()) { toast("No more languages to add"); return }
        val names = candidates.map { LanguageCatalog.displayNameFor(it) }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle("Add target language")
            .setItems(names) { _, which ->
                broadcastTargets.add(candidates[which])
                refreshAllContent()
            }
            .show()
    }

    private fun broadcastToRoster(text: String) {
        if (text.isBlank()) { toast("Type or speak something first"); return }
        if (broadcastTargets.isEmpty()) { toast("Add at least one target language first"); return }
        broadcastStatusText = "Translating to ${broadcastTargets.size} language(s)..."
        refreshAllContent()
        var remaining = broadcastTargets.size
        broadcastTargets.toList().forEach { tgt ->
            TranslationEngine.translate(sourceCode, tgt, text,
                onResult = onResult@{ translated ->
                    if (contentContainer == null) return@onResult
                    broadcastResults[tgt] = translated
                    remaining--
                    if (remaining <= 0) broadcastStatusText = ""
                    refreshAllContent()
                },
                onError = onError@{ err ->
                    if (contentContainer == null) return@onError
                    broadcastResults[tgt] = "(failed: $err)"
                    remaining--
                    if (remaining <= 0) broadcastStatusText = ""
                    refreshAllContent()
                }
            )
        }
    }

    // =======================================================================
    // Flex: "mirror_panes" - two identical-styled cards (source above the
    // hinge, target below), a floating swap FAB straddling the seam.
    // =======================================================================

    private fun bindMirrorPanes(b: FragmentTranslateFlexMirrorPanesBinding) {
        if (lastInputText.isNotEmpty()) b.editMirrorSource.setText(lastInputText)
        b.btnMirrorMic.setOnClickListener {
            runMicToggle(sourceCode, { s -> micStatusText = s }) { text ->
                b.editMirrorSource.setText(text)
                lastInputText = text
                performTranslate(text)
            }
        }
        b.editMirrorSource.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                lastInputText = b.editMirrorSource.text?.toString().orEmpty()
                performTranslate(lastInputText)
                true
            } else false
        }
        b.btnMirrorSpeak.setOnClickListener { speakLastResult() }
        b.cardFabSwap.setOnClickListener { animateMirrorSwap(b) }
        refreshMirrorPanesContent(b)
    }

    private fun refreshMirrorPanesContent(b: FragmentTranslateFlexMirrorPanesBinding) {
        b.textMirrorSourceLang.text = "${LanguageCatalog.displayNameFor(sourceCode)} (source)"
        b.textMirrorTargetLang.text = "${LanguageCatalog.displayNameFor(targetCode)} (target)"
        if (b.editMirrorSource.text.isNullOrEmpty() && lastInputText.isNotEmpty()) b.editMirrorSource.setText(lastInputText)
        b.textMirrorTargetResult.text = lastResultText
    }

    /**
     * Visibly trades the two panes' contents (this variant's brief) via a
     * real Choreographer-driven fade (ViewPropertyAnimator, spec §5's 120Hz
     * note - not a postDelayed loop): fades both cards down, swaps the
     * language pair and moves the last result into the source card as the
     * new input, then fades back in.
     */
    private fun animateMirrorSwap(b: FragmentTranslateFlexMirrorPanesBinding) {
        b.cardMirrorSource.animate().alpha(0.25f).setDuration(110L).start()
        b.cardMirrorTarget.animate().alpha(0.25f).setDuration(110L).withEndAction {
            if (contentContainer == null) return@withEndAction
            swapLanguages()
            lastInputText = lastResultText
            lastResultText = ""
            b.editMirrorSource.setText(lastInputText)
            refreshMirrorPanesContent(b)
            b.cardMirrorSource.animate().alpha(1f).setDuration(150L).start()
            b.cardMirrorTarget.animate().alpha(1f).setDuration(150L).start()
        }.start()
    }

    // ---------------------------------------------------------------------

    override fun onPause() {
        super.onPause()
        mainActivity?.app?.mic?.stop()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mainActivity?.app?.mic?.stop()
        unregisterLayoutPrefsListener()
        contentContainer = null
        defaultBinding = null
        coverSingleCircleBinding = null
        coverLiveTranscriptBinding = null
        coverFaceToFaceBinding = null
        flexDefaultBinding = null
        flexAcrossTableBinding = null
        flexMultiBroadcastBinding = null
        flexMirrorPanesBinding = null
        currentActive = null
    }

    companion object {
        private const val TAG = "TranslateFragment"
        private const val HOLD_THRESHOLD_MS = 350L
        private const val SWIPE_THRESHOLD_PX = 90f
        private const val FAB_OVERLAP_DP = 16f

        // Mirrors LayoutPreferences' own private PREFS_NAME / variantKey()
        // format exactly (see registerLayoutPrefsListener's doc comment) -
        // duplicated here deliberately rather than exposing it on that
        // foundation file, which this tab's work is scoped not to touch.
        private const val LAYOUT_PREFS_FILE_NAME = "layout_prefs"
        private const val LAYOUT_KEY_COVER = "variant_translate_cover"
        private const val LAYOUT_KEY_FLEX = "variant_translate_flex"
    }
}
