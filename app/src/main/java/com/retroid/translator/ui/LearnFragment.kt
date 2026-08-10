package com.retroid.translator.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.FoldingFeature
import com.retroid.translator.MainActivity
import com.retroid.translator.R
import com.retroid.translator.audio.MicPipeline
import com.retroid.translator.databinding.FragmentLearnBinding
import com.retroid.translator.databinding.FragmentLearnCoverCourseDashboardBinding
import com.retroid.translator.databinding.FragmentLearnCoverListenChooseBinding
import com.retroid.translator.databinding.FragmentLearnCoverProgressRingBinding
import com.retroid.translator.databinding.FragmentLearnFlexDefaultBinding
import com.retroid.translator.databinding.FragmentLearnFlexElasticSplitBinding
import com.retroid.translator.databinding.FragmentLearnFlexFlipSortBinding
import com.retroid.translator.databinding.FragmentLearnFlexSpeakingArcBinding
import com.retroid.translator.engine.VoicePreferences
import com.retroid.translator.fold.FoldPosture
import com.retroid.translator.fold.FoldPostureProvider
import com.retroid.translator.fold.FoldState
import com.retroid.translator.learn.AnswerChecker
import com.retroid.translator.learn.ExerciseType
import com.retroid.translator.learn.LearnCourse
import com.retroid.translator.learn.LearnCourseLoader
import com.retroid.translator.learn.LearnExercise
import com.retroid.translator.learn.LearnLesson
import com.retroid.translator.learn.LearnReviewQueue
import com.retroid.translator.learn.LearnUnit
import com.retroid.translator.settings.FoldAwareLayoutHost
import com.retroid.translator.settings.LayoutPreferences
import com.retroid.translator.settings.ScreenMode
import com.retroid.translator.settings.SettingsTab
import kotlinx.coroutines.launch
import kotlin.math.min

/** Which of this tab's 8 real layouts is currently inflated into [LearnFragment]'s container - at most one at a time. */
private enum class LearnActiveLayout {
    DEFAULT, COVER_PROGRESS_RING, COVER_COURSE_DASHBOARD, COVER_LISTEN_CHOOSE,
    FLEX_DEFAULT, FLEX_FLIP_SORT, FLEX_ELASTIC_SPLIT, FLEX_SPEAKING_ARC
}

/** Which of the "unit list -> lesson list -> exercise -> summary" screens the "default" layout is currently showing. */
private enum class LearnDefaultScreen { UNITS, LESSONS, EXERCISE, SUMMARY }

/**
 * Learn tab. Implements the per-tab layout-variant system from the settings
 * foundation (docs/specs/fold5-adaptation.md's task-item-worth of work
 * covering ONLY this tab): 4 real cover-screen variants + 4 real Flex-Mode
 * (tabletop) variants, on top of the tab's pre-existing default (full,
 * book-portrait) layout - 8 real, functioning layouts total, all reading
 * and writing the exact same underlying course/progress state
 * ([LearnCourseLoader], [com.retroid.translator.learn.LearnProgressStore],
 * [AnswerChecker], `TtsRouter`, `VoskEngine`/`MicPipeline`).
 *
 * Deliberate deviation worth flagging up front: docs/specs/fold5-adaptation.md's
 * own Scope table (§ "Scope: which screens get what") lists Learn as
 * "Responsive scaling only" - no bespoke fold-aware layout, that treatment
 * reserved for Conversations alone. This file's existence is a direct
 * instruction from the task that commissioned it, which explicitly asks for
 * bespoke Learn cover/Flex layouts - it supersedes the spec's Scope table
 * for this tab, exactly as `TranslateFragment`/`PracticeFragment` already
 * document for themselves. Book-portrait/non-fold behavior (the spec's
 * actual "responsive scaling only" case) is preserved unchanged as
 * [LearnActiveLayout.DEFAULT].
 *
 * Rendering model: same dynamic-container technique as
 * `ConversationsFragment`/`TranslateFragment`/`PracticeFragment`. [onCreateView]
 * returns a bare [FrameLayout] mount point, not a static XML - which real
 * layout is currently inflated into it is decided live by [renderActiveLayout],
 * driven by the same two independent signals every other tab in this pass
 * uses: this Fragment's own [FoldPostureProvider] subscription for Flex Mode,
 * and [FoldAwareLayoutHost] (pushed by `MainActivity`'s existing fold-close
 * heuristic / the Fold behavior screen's force-compact toggle) for the cover
 * screen.
 *
 * Session state that used to live as `currentLesson`/`currentExerciseIndex`
 * fields is now [lessonCursor] - a single mutable pointer into "the lesson
 * currently being taken", shared by every layout that walks a lesson in
 * order ([LearnActiveLayout.DEFAULT]'s exercise screen and all 4 Flex variants),
 * so folding mid-lesson never loses or duplicates progress. The
 * "progress_ring" and "course_dashboard" cover variants additionally read
 * (but do not write into) a separate, review-specific queue
 * ([LearnReviewQueue]) - reviewing due items is deliberately independent of
 * "the lesson in progress" so folding into a quick review and back out
 * doesn't disturb it.
 */
class LearnFragment : Fragment(), FoldAwareLayoutHost {

    override val settingsTab: SettingsTab = SettingsTab.LEARN

    // ---------------------------------------------------------------------
    // Session state - the single source of truth, independent of whichever
    // of the 8 layouts is currently inflated.
    // ---------------------------------------------------------------------

    private var course: LearnCourse? = null
    private var currentUnit: LearnUnit? = null

    private data class LessonCursor(val unit: LearnUnit?, val lesson: LearnLesson, var index: Int)
    private var lessonCursor: LessonCursor? = null
    private var xpEarnedThisLesson = 0
    private var currentExerciseAnswered = false
    private val wordBankAnswerTiles = mutableListOf<String>()

    private sealed class LessonStep {
        data class Next(val exercise: LearnExercise) : LessonStep()
        data class Done(val xpEarned: Int) : LessonStep()
    }

    // "progress_ring" cover state
    private var ringQueue: List<com.retroid.translator.learn.ResolvedExercise> = emptyList()
    private var ringQueuePos = 0
    private var ringDueTotal = 1
    private var ringDueDone = 0
    private var ringCardOpen = false

    // "course_dashboard" cover state
    private var dashReviewQueue: List<com.retroid.translator.learn.ResolvedExercise> = emptyList()
    private var dashReviewPos = 0
    private var dashSlotIsReview = false

    // "flip_sort" Flex state
    private var flipRevealed = false

    private val mainActivity get() = activity as? MainActivity
    private lateinit var foldPostureProvider: FoldPostureProvider
    private var layoutPrefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    private var currentPosture: FoldPosture = FoldPosture.NO_FOLDING_FEATURE
    private var currentFeature: FoldingFeature? = null
    private var coverForced = false
    private var currentActive: LearnActiveLayout? = null

    // ---------------------------------------------------------------------
    // View plumbing - exactly one of these 8 is non-null at a time.
    // ---------------------------------------------------------------------

    private var contentContainer: FrameLayout? = null
    private var defaultBinding: FragmentLearnBinding? = null
    private var ringBinding: FragmentLearnCoverProgressRingBinding? = null
    private var dashboardBinding: FragmentLearnCoverCourseDashboardBinding? = null
    private var listenChooseBinding: FragmentLearnCoverListenChooseBinding? = null
    private var flexDefaultBinding: FragmentLearnFlexDefaultBinding? = null
    private var flipSortBinding: FragmentLearnFlexFlipSortBinding? = null
    private var elasticSplitBinding: FragmentLearnFlexElasticSplitBinding? = null
    private var speakingArcBinding: FragmentLearnFlexSpeakingArcBinding? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = FrameLayout(requireContext())
        root.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        contentContainer = root
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadCourse()
        coverForced = LayoutPreferences.isForceCompactLayoutEnabled(requireContext())
        registerLayoutPrefsListener()
        renderActiveLayout()
        observeFoldPosture()
    }

    override fun onResume() {
        super.onResume()
        defaultBinding?.let { if (it.screenUnits.visibility == View.VISIBLE) refreshStatsHeader(it) }
    }

    private fun loadCourse() {
        val codes = LearnCourseLoader.availableCourseCodes(requireContext())
        val code = if ("en" in codes) "en" else codes.firstOrNull()
        course = code?.let { LearnCourseLoader.load(requireContext(), it) }
    }

    // ---------------------------------------------------------------------
    // FoldAwareLayoutHost - pushed by MainActivity's existing fold-close
    // heuristic and the Fold behavior screen's manual force-compact toggle.
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
    // same pattern as ConversationsFragment/TranslateFragment/PracticeFragment.
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
    // Live settings change - reads the SAME SharedPreferences file
    // LayoutPreferences uses (by name, not by touching that foundation
    // file), same technique TranslateFragment/PracticeFragment use.
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

    private fun coverActiveLayout(variantId: String): LearnActiveLayout = when (variantId) {
        LearnCoverVariant.PROGRESS_RING -> LearnActiveLayout.COVER_PROGRESS_RING
        LearnCoverVariant.COURSE_DASHBOARD -> LearnActiveLayout.COVER_COURSE_DASHBOARD
        LearnCoverVariant.LISTEN_CHOOSE -> LearnActiveLayout.COVER_LISTEN_CHOOSE
        else -> LearnActiveLayout.DEFAULT
    }

    private fun flexActiveLayout(variantId: String): LearnActiveLayout = when (variantId) {
        LearnFlexVariant.FLIP_SORT -> LearnActiveLayout.FLEX_FLIP_SORT
        LearnFlexVariant.ELASTIC_SPLIT -> LearnActiveLayout.FLEX_ELASTIC_SPLIT
        LearnFlexVariant.SPEAKING_ARC -> LearnActiveLayout.FLEX_SPEAKING_ARC
        else -> LearnActiveLayout.FLEX_DEFAULT
    }

    private fun renderActiveLayout() {
        val target = when {
            coverForced -> coverActiveLayout(LayoutPreferences.getVariant(requireContext(), SettingsTab.LEARN, ScreenMode.COVER))
            currentPosture.isMirroredTabletop -> flexActiveLayout(LayoutPreferences.getVariant(requireContext(), SettingsTab.LEARN, ScreenMode.FLEX))
            else -> LearnActiveLayout.DEFAULT
        }
        if (target == currentActive) {
            refreshAllContent()
            currentFeature?.let { applyFlexGeometryIfNeeded(it) }
            return
        }
        switchTo(target)
    }

    private fun switchTo(target: LearnActiveLayout) {
        val container = contentContainer ?: return
        mainActivity?.app?.mic?.stop()
        container.removeAllViews()
        defaultBinding = null
        ringBinding = null
        dashboardBinding = null
        listenChooseBinding = null
        flexDefaultBinding = null
        flipSortBinding = null
        elasticSplitBinding = null
        speakingArcBinding = null

        when (target) {
            LearnActiveLayout.DEFAULT -> {
                val b = FragmentLearnBinding.inflate(layoutInflater, container, false)
                defaultBinding = b; container.addView(b.root); bindDefault(b)
            }
            LearnActiveLayout.COVER_PROGRESS_RING -> {
                val b = FragmentLearnCoverProgressRingBinding.inflate(layoutInflater, container, false)
                ringBinding = b; container.addView(b.root); bindProgressRing(b)
            }
            LearnActiveLayout.COVER_COURSE_DASHBOARD -> {
                val b = FragmentLearnCoverCourseDashboardBinding.inflate(layoutInflater, container, false)
                dashboardBinding = b; container.addView(b.root); bindCourseDashboard(b)
            }
            LearnActiveLayout.COVER_LISTEN_CHOOSE -> {
                val b = FragmentLearnCoverListenChooseBinding.inflate(layoutInflater, container, false)
                listenChooseBinding = b; container.addView(b.root); bindListenChoose(b)
            }
            LearnActiveLayout.FLEX_DEFAULT -> {
                val b = FragmentLearnFlexDefaultBinding.inflate(layoutInflater, container, false)
                flexDefaultBinding = b; container.addView(b.root); bindFlexDefault(b)
            }
            LearnActiveLayout.FLEX_FLIP_SORT -> {
                val b = FragmentLearnFlexFlipSortBinding.inflate(layoutInflater, container, false)
                flipSortBinding = b; container.addView(b.root); bindFlipSort(b)
            }
            LearnActiveLayout.FLEX_ELASTIC_SPLIT -> {
                val b = FragmentLearnFlexElasticSplitBinding.inflate(layoutInflater, container, false)
                elasticSplitBinding = b; container.addView(b.root); bindElasticSplit(b)
            }
            LearnActiveLayout.FLEX_SPEAKING_ARC -> {
                val b = FragmentLearnFlexSpeakingArcBinding.inflate(layoutInflater, container, false)
                speakingArcBinding = b; container.addView(b.root); bindSpeakingArc(b)
            }
        }

        // Choreographer-driven crossfade (ViewPropertyAnimator, not a fixed
        // postDelayed loop - spec §5's 120Hz-display note), same technique
        // every other tab's dynamic-layout switch already uses.
        container.alpha = 0f
        container.animate().alpha(1f).setDuration(200L).start()

        currentActive = target
        refreshAllContent()
        currentFeature?.let { applyFlexGeometryIfNeeded(it) }
    }

    private fun refreshAllContent() {
        defaultBinding?.let { if (it.screenUnits.visibility == View.VISIBLE) refreshStatsHeader(it) }
        dashboardBinding?.let { refreshDashboardContent(it) }
    }

    // ---------------------------------------------------------------------
    // Flex-Mode (tabletop) pane geometry - shared by all 4 Flex variants.
    // Same technique as ConversationsFragment.applyMirroredGeometry /
    // TranslateFragment.positionFlexPanes: never a static 50/50 split,
    // always derived from the live FoldingFeature.bounds, with an extra
    // inset when occlusionType is FULL to keep content off the
    // physically-occluded crease (task item 5 / spec §2). This is also why
    // "elastic_split" (see applyElasticEmphasis) deliberately does NOT move
    // the pane boundary itself off the hinge to realize its per-exercise-type
    // ratio - only this one shared, hinge-anchored geometry function ever
    // sets pane height/position, so touch targets can never end up on the
    // physically occluded crease regardless of which Flex variant is active.
    // ---------------------------------------------------------------------

    private fun applyFlexGeometryIfNeeded(feature: FoldingFeature) {
        when (currentActive) {
            LearnActiveLayout.FLEX_DEFAULT -> flexDefaultBinding?.let {
                positionFlexPanes(feature, it.paneViewing.root, it.paneControl.root, rotateTop = false)
            }
            LearnActiveLayout.FLEX_ELASTIC_SPLIT -> elasticSplitBinding?.let {
                positionFlexPanes(feature, it.paneElasticViewing.root, it.paneElasticControl.root, rotateTop = false)
            }
            LearnActiveLayout.FLEX_FLIP_SORT -> flipSortBinding?.let {
                positionFlexPanes(feature, it.paneFlipTop, it.paneFlipBottom, rotateTop = false)
            }
            LearnActiveLayout.FLEX_SPEAKING_ARC -> speakingArcBinding?.let {
                positionFlexPanes(feature, it.paneArcTop, it.paneArcBottom, rotateTop = false)
            }
            else -> {}
        }
    }

    private fun positionFlexPanes(feature: FoldingFeature, topView: View, bottomView: View, rotateTop: Boolean) {
        val container = contentContainer ?: return
        if (container.height == 0) {
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

    // ---------------------------------------------------------------------
    // Lesson cursor - shared "which exercise, in which lesson, are we on"
    // pointer for every layout that walks a lesson in order (DEFAULT's
    // exercise screen + all 4 Flex variants). Review-queue-driven variants
    // (progress_ring, course_dashboard's box rows) deliberately keep their
    // own separate, smaller pointers instead (ringQueue*/dashReviewQueue*)
    // rather than overloading this one - see class doc comment.
    // ---------------------------------------------------------------------

    private fun startLessonCursor(unit: LearnUnit, lesson: LearnLesson) {
        currentUnit = unit
        lessonCursor = LessonCursor(unit, lesson, 0)
        xpEarnedThisLesson = 0
        currentExerciseAnswered = false
        wordBankAnswerTiles.clear()
    }

    /** If no lesson is currently in progress, picks the first not-yet-completed lesson (or failing that, the very first lesson) so Flex/listen_choose - which have no unit/lesson picker chrome at all - always have something real to show. */
    private fun ensureLessonSelected() {
        if (lessonCursor != null) return
        val c = course ?: return
        val app = mainActivity?.app
        val pick = c.units.flatMap { u -> u.lessons.map { u to it } }
            .firstOrNull { (_, l) -> app == null || !app.learnProgress.isLessonCompleted(l.id) }
            ?: c.units.firstOrNull()?.let { u -> u.lessons.firstOrNull()?.let { u to it } }
        pick?.let { (u, l) -> startLessonCursor(u, l) }
    }

    private fun stepLessonForward(): LessonStep {
        val c = lessonCursor ?: return LessonStep.Done(0)
        c.index++
        currentExerciseAnswered = false
        return if (c.index < c.lesson.exercises.size) {
            LessonStep.Next(c.lesson.exercises[c.index])
        } else {
            LessonStep.Done(finishLessonCursor())
        }
    }

    private fun finishLessonCursor(): Int {
        val app = mainActivity?.app ?: return 0
        val lesson = lessonCursor?.lesson ?: return 0
        val earned = xpEarnedThisLesson
        app.learnProgress.addXp(earned)
        app.learnProgress.recordLessonCompleted(lesson.id, earned)
        app.learnProgress.recordActivityToday()
        lessonCursor = null
        return earned
    }

    /**
     * Records an answer's SRS/XP bookkeeping exactly once
     * ([currentExerciseAnswered] guards double-taps), then hands control
     * back to the caller to update whichever variant's UI is showing. XP
     * accounting deliberately differs by source, matching this tab's
     * original design: a normal lesson-flow answer ([isReview] false) only
     * accrues into [xpEarnedThisLesson], committed in one batch by
     * [finishLessonCursor] at lesson end (unchanged from before this pass);
     * a review-queue answer ([isReview] true) has no "lesson end" event to
     * batch into, so its XP posts immediately.
     */
    private fun handleAnswer(exerciseKey: String, correct: Boolean, isReview: Boolean, after: (Boolean) -> Unit) {
        if (currentExerciseAnswered) return
        currentExerciseAnswered = true
        val app = mainActivity?.app ?: return
        app.learnProgress.recordAnswer(exerciseKey, correct)
        if (isReview) {
            if (correct) app.learnProgress.addXp(10)
            app.learnProgress.recordActivityToday()
        } else if (correct) {
            xpEarnedThisLesson += 10
        }
        after(correct)
    }

    private fun renderAnswerFeedback(view: TextView, correct: Boolean, exercise: LearnExercise) {
        if (correct) {
            view.text = "✓ Correct! +10 XP"
            view.setTextColor(Color.parseColor("#2E7D32"))
        } else {
            view.text = "✗ Not quite. Correct answer: “${exercise.text}”"
            view.setTextColor(Color.parseColor("#C62828"))
        }
    }

    private fun hapticFeedback(view: View, positive: Boolean) {
        val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (positive) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.REJECT
        } else {
            if (positive) HapticFeedbackConstants.VIRTUAL_KEY else HapticFeedbackConstants.LONG_PRESS
        }
        view.isHapticFeedbackEnabled = true
        view.performHapticFeedback(constant, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String, long: Boolean = false) {
        if (isAdded) Toast.makeText(requireContext(), msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    private fun speakCourseText(text: String) {
        val app = mainActivity?.app ?: return
        val langCode = course?.language ?: "en"
        val gender = VoicePreferences.getGender(requireContext())
        app.tts.speak(text, langCode, gender, onDone = {}, onError = {})
    }

    // =======================================================================
    // Shared per-exercise-type body renderers - reused across every layout
    // that walks a lesson in order (task item 5: alternate presentations of
    // existing behavior, not new mock features). Each renders straight into
    // whichever ViewGroup the caller passes and reports the answer via
    // [onAnswered] rather than doing its own bookkeeping, so callers wire it
    // through [handleAnswer] themselves however that layout needs to.
    // =======================================================================

    private fun distractorsFor(exercise: LearnExercise, lesson: LearnLesson, count: Int): List<String> =
        lesson.exercises.map { it.text }.filter { it != exercise.text }.shuffled().take(count)

    private fun renderExerciseBody(
        container: ViewGroup,
        exercise: LearnExercise,
        distractorLesson: LearnLesson,
        setPrompt: (String) -> Unit,
        onAnswered: (Boolean) -> Unit
    ) {
        container.removeAllViews()
        when (exercise.type) {
            ExerciseType.MULTIPLE_CHOICE -> {
                setPrompt(exercise.gloss ?: "Which one is correct?")
                renderMultipleChoiceInto(container, exercise, distractorLesson, onAnswered)
            }
            ExerciseType.WORD_BANK -> {
                setPrompt("Put the words in the right order")
                renderWordBankInto(container, exercise, onAnswered)
            }
            ExerciseType.LISTENING -> {
                setPrompt("Listen, then pick what you heard")
                renderListeningInto(container, exercise, distractorLesson, onAnswered)
            }
            ExerciseType.SPEAKING -> {
                setPrompt("Say this out loud:\n“${exercise.text}”")
                renderSpeakingInto(container, exercise, onAnswered)
            }
        }
    }

    private fun renderMultipleChoiceInto(container: ViewGroup, exercise: LearnExercise, lesson: LearnLesson, onAnswered: (Boolean) -> Unit) {
        val options = (distractorsFor(exercise, lesson, 3) + exercise.text).shuffled()
        val buttons = options.map { optionText ->
            Button(requireContext()).apply {
                text = optionText
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
            }
        }
        buttons.forEach { btn ->
            btn.setOnClickListener {
                val correct = btn.text.toString() == exercise.text
                buttons.forEach { it.isEnabled = false }
                btn.setBackgroundColor(if (correct) Color.parseColor("#4CAF50") else Color.parseColor("#F44336"))
                onAnswered(correct)
            }
            container.addView(btn)
        }
    }

    private fun renderListeningInto(container: ViewGroup, exercise: LearnExercise, lesson: LearnLesson, onAnswered: (Boolean) -> Unit) {
        val playBtn = Button(requireContext()).apply {
            text = "🔊 Play phrase"
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        playBtn.setOnClickListener { speakCourseText(exercise.text) }
        container.addView(playBtn)
        speakCourseText(exercise.text)

        val options = (distractorsFor(exercise, lesson, 3) + exercise.text).shuffled()
        val buttons = options.map { optionText ->
            Button(requireContext()).apply {
                text = optionText
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
            }
        }
        buttons.forEach { btn ->
            btn.setOnClickListener {
                val correct = btn.text.toString() == exercise.text
                buttons.forEach { it.isEnabled = false }
                btn.setBackgroundColor(if (correct) Color.parseColor("#4CAF50") else Color.parseColor("#F44336"))
                onAnswered(correct)
            }
            container.addView(btn)
        }
    }

    private fun renderWordBankInto(container: ViewGroup, exercise: LearnExercise, onAnswered: (Boolean) -> Unit) {
        wordBankAnswerTiles.clear()
        val playBtn = Button(requireContext()).apply {
            text = "🔊 Hear it first"
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        playBtn.setOnClickListener { speakCourseText(exercise.text) }
        container.addView(playBtn)

        val answerRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
            minimumHeight = dp(48)
            setBackgroundColor(Color.parseColor("#11000000"))
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        val answerScroll = HorizontalScrollView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(answerRow)
        }
        container.addView(answerScroll)

        val bankRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
        }
        val bankScroll = HorizontalScrollView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(bankRow)
        }
        container.addView(bankScroll)

        val words = exercise.text.trim().split(Regex("\\s+")).shuffled()
        fun addTileToBank(word: String) {
            val tile = Button(requireContext()).apply {
                text = word
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(4) }
            }
            tile.setOnClickListener {
                bankRow.removeView(tile)
                wordBankAnswerTiles.add(word)
                val answerTile = TextView(requireContext()).apply {
                    text = word
                    textSize = 16f
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                }
                answerRow.addView(answerTile)
            }
            bankRow.addView(tile)
        }
        words.forEach { addTileToBank(it) }

        val actionsRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
        }
        val clearBtn = Button(requireContext()).apply {
            text = "Clear"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val checkBtn = Button(requireContext()).apply {
            text = "Check"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) }
        }
        clearBtn.setOnClickListener {
            container.removeAllViews()
            renderWordBankInto(container, exercise, onAnswered)
        }
        checkBtn.setOnClickListener {
            val given = wordBankAnswerTiles.joinToString(" ")
            val correct = normalizeWords(given) == normalizeWords(exercise.text)
            clearBtn.isEnabled = false
            checkBtn.isEnabled = false
            onAnswered(correct)
        }
        actionsRow.addView(clearBtn)
        actionsRow.addView(checkBtn)
        container.addView(actionsRow)
    }

    private fun normalizeWords(s: String): List<String> =
        s.lowercase().replace(Regex("[^a-z0-9\\s]"), "").split(Regex("\\s+")).filter { it.isNotBlank() }

    private fun renderSpeakingInto(container: ViewGroup, exercise: LearnExercise, onAnswered: (Boolean) -> Unit) {
        val recordBtn = Button(requireContext()).apply {
            text = "🎙 Record"
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val statusText = TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
            textSize = 12f
        }
        val skipBtn = Button(requireContext()).apply {
            text = "Skip (can't use mic right now)"
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
        }
        container.addView(recordBtn)
        container.addView(statusText)
        container.addView(skipBtn)

        skipBtn.setOnClickListener {
            recordBtn.isEnabled = false
            skipBtn.isEnabled = false
            statusText.text = "Skipped - not scored."
        }

        recordBtn.setOnClickListener {
            val activity = mainActivity ?: return@setOnClickListener
            val app = activity.app
            if (!activity.hasMicPermission()) {
                activity.requestMicPermissionIfNeeded()
                toast("Grant microphone permission, then tap Record again", long = true)
                return@setOnClickListener
            }
            val langCode = course?.language ?: "en"
            if (!app.vosk.isModelDownloaded(langCode)) {
                statusText.text = "No offline voice-input pack downloaded for this language yet - download it on the Translate tab, or tap Skip."
                return@setOnClickListener
            }
            recordBtn.isEnabled = false
            statusText.text = "Loading voice recognizer…"
            app.vosk.loadModelAsync(langCode) { ok, err ->
                if (contentContainer == null) return@loadModelAsync
                if (!ok) {
                    statusText.text = "Couldn't load voice recognizer: $err"
                    recordBtn.isEnabled = true
                    return@loadModelAsync
                }
                val recognizer = app.vosk.newRecognizer()
                if (recognizer == null) {
                    statusText.text = "Couldn't start recognizer"
                    recordBtn.isEnabled = true
                    return@loadModelAsync
                }
                statusText.text = "Listening… say the phrase now"
                app.mic.start(recognizer, recordToFile = null, listener = object : MicPipeline.Listener {
                    override fun onFinal(text: String) {
                        if (contentContainer == null) return
                        val correct = AnswerChecker.matches(exercise.text, text)
                        val ratioPct = (AnswerChecker.overlapRatio(exercise.text, text) * 100).toInt()
                        statusText.text = "Heard: “$text” ($ratioPct% word match)"
                        skipBtn.isEnabled = false
                        onAnswered(correct)
                    }
                    override fun onError(message: String) {
                        if (contentContainer != null) { statusText.text = message; recordBtn.isEnabled = true }
                    }
                    override fun onListeningStopped() {}
                })
            }
        }
    }

    // =======================================================================
    // "default" - the tab's original 4-screen layout (unit list -> lesson
    // list -> one exercise at a time -> summary), reused unchanged for both
    // book-portrait/non-fold AND ScreenMode.COVER's "default" variant.
    // =======================================================================

    private fun bindDefault(b: FragmentLearnBinding) {
        b.btnBackToUnits.setOnClickListener { renderDefaultUnits(b) }
        b.btnSummaryContinue.setOnClickListener { renderDefaultLessons(b) }
        if (lessonCursor != null) renderDefaultExercise(b) else renderDefaultUnits(b)
    }

    private fun setDefaultScreen(b: FragmentLearnBinding, screen: LearnDefaultScreen) {
        b.screenUnits.visibility = if (screen == LearnDefaultScreen.UNITS) View.VISIBLE else View.GONE
        b.screenLessons.visibility = if (screen == LearnDefaultScreen.LESSONS) View.VISIBLE else View.GONE
        b.screenExercise.visibility = if (screen == LearnDefaultScreen.EXERCISE) View.VISIBLE else View.GONE
        b.screenSummary.visibility = if (screen == LearnDefaultScreen.SUMMARY) View.VISIBLE else View.GONE
    }

    private fun renderDefaultUnits(b: FragmentLearnBinding) {
        setDefaultScreen(b, LearnDefaultScreen.UNITS)
        refreshStatsHeader(b)
        b.unitsListContainer.removeAllViews()
        val app = mainActivity?.app ?: return
        val c = course
        if (c == null) {
            b.textLearnNoCourse.visibility = View.VISIBLE
            return
        }
        for (unit in c.units) {
            val completedLessons = unit.lessons.count { app.learnProgress.isLessonCompleted(it.id) }
            val row = buildListRow(
                title = unit.title,
                subtitle = "$completedLessons/${unit.lessons.size} lessons complete",
                buttonText = if (completedLessons == unit.lessons.size) "Review" else "Open"
            ) {
                currentUnit = unit
                renderDefaultLessonsFor(b, unit)
            }
            b.unitsListContainer.addView(row)
        }
    }

    private fun renderDefaultLessons(b: FragmentLearnBinding) {
        val unit = currentUnit
        if (unit == null) { renderDefaultUnits(b); return }
        renderDefaultLessonsFor(b, unit)
    }

    private fun renderDefaultLessonsFor(b: FragmentLearnBinding, unit: LearnUnit) {
        setDefaultScreen(b, LearnDefaultScreen.LESSONS)
        b.textLessonsUnitTitle.text = unit.title
        b.lessonsListContainer.removeAllViews()
        val app = mainActivity?.app ?: return
        for (lesson in unit.lessons) {
            val done = app.learnProgress.isLessonCompleted(lesson.id)
            val row = buildListRow(
                title = lesson.title,
                subtitle = if (done) "✓ Completed - ${lesson.exercises.size} exercises" else "${lesson.exercises.size} exercises",
                buttonText = if (done) "Practice again" else "Start"
            ) {
                startLessonCursor(unit, lesson)
                renderDefaultExercise(b)
            }
            b.lessonsListContainer.addView(row)
        }
    }

    private fun renderDefaultExercise(b: FragmentLearnBinding) {
        val c = lessonCursor ?: run { renderDefaultUnits(b); return }
        setDefaultScreen(b, LearnDefaultScreen.EXERCISE)
        b.textExerciseFeedback.text = ""
        b.btnExerciseContinue.visibility = View.GONE
        b.textExerciseProgress.text = "Exercise ${c.index + 1}/${c.lesson.exercises.size}"
        val exercise = c.lesson.exercises[c.index]
        renderExerciseBody(b.exerciseDynamicContainer, exercise, c.lesson, setPrompt = { b.textExercisePrompt.text = it }) { correct ->
            handleAnswer(exercise.key(c.lesson.id, c.index), correct, isReview = false) {
                renderAnswerFeedback(b.textExerciseFeedback, correct, exercise)
                b.btnExerciseContinue.visibility = View.VISIBLE
                b.btnExerciseContinue.setOnClickListener {
                    when (val step = stepLessonForward()) {
                        is LessonStep.Next -> renderDefaultExercise(b)
                        is LessonStep.Done -> renderDefaultSummary(b, step.xpEarned)
                    }
                }
            }
        }
    }

    private fun renderDefaultSummary(b: FragmentLearnBinding, xpEarned: Int) {
        setDefaultScreen(b, LearnDefaultScreen.SUMMARY)
        val app = mainActivity?.app ?: return
        b.textSummaryXp.text = "+$xpEarned XP earned this lesson (total: ${app.learnProgress.totalXp()} XP)"
        b.textSummaryStreak.text = "🔥 ${app.learnProgress.currentStreak()} day streak"
    }

    private fun refreshStatsHeader(b: FragmentLearnBinding) {
        val app = mainActivity?.app ?: return
        if (contentContainer == null) return
        val streak = app.learnProgress.currentStreak()
        val xp = app.learnProgress.totalXp()
        b.textLearnStats.text = "🔥 $streak day streak   ⭐ $xp XP"
    }

    /** Small reusable "title / subtitle / action button" card, built in code rather than a new layout file - same technique the original single-layout LearnFragment already used. */
    private fun buildListRow(title: String, subtitle: String, buttonText: String, onClick: () -> Unit): View {
        val card = CardView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
            radius = dp(8).toFloat()
            cardElevation = dp(2).toFloat()
        }
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val textCol = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(TextView(requireContext()).apply { text = title; textSize = 16f; setTypeface(typeface, android.graphics.Typeface.BOLD) })
        textCol.addView(TextView(requireContext()).apply { text = subtitle; textSize = 12f })
        val btn = Button(requireContext()).apply { text = buttonText; setOnClickListener { onClick() } }
        row.addView(textCol)
        row.addView(btn)
        card.addView(row)
        return card
    }

    // =======================================================================
    // Cover: "progress_ring" - one large ring (com.retroid.translator.ui.widget.ProgressRingView),
    // no streak digit or due-card text anywhere. Tapping it answers the next
    // due-for-review exercise (LearnReviewQueue.dueNow) inline in a card
    // sized/positioned over the ring itself, which closes back up (and the
    // ring animates fuller) once answered - see [onRingTapped]/[closeRingCard].
    // =======================================================================

    private fun bindProgressRing(b: FragmentLearnCoverProgressRingBinding) {
        val app = mainActivity?.app
        val c = course
        ringQueue = if (c != null && app != null) LearnReviewQueue.dueNow(c, app.learnProgress) else emptyList()
        ringQueuePos = 0
        ringDueDone = 0
        ringDueTotal = ringQueue.size.coerceAtLeast(1)
        ringCardOpen = false
        b.cardExerciseOverlay.visibility = View.GONE
        b.ringView.progress = if (ringQueue.isEmpty()) 1f else 0f
        b.ringView.setOnClickListener { onRingTapped(b) }
        sizeRingViews(b)
    }

    private fun sizeRingViews(b: FragmentLearnCoverProgressRingBinding) {
        b.rootProgressRing.post {
            if (contentContainer == null || ringBinding !== b) return@post
            val side = (min(b.rootProgressRing.width, b.rootProgressRing.height) * 0.72f).toInt()
            if (side <= 0) {
                // Not laid out yet (first frame) - defer one more pass, same
                // retry-if-zero technique positionFlexPanes uses elsewhere in
                // this file, so the ring never gets stuck at its 0x0 XML default.
                sizeRingViews(b)
                return@post
            }
            b.ringView.layoutParams = (b.ringView.layoutParams as FrameLayout.LayoutParams).apply { width = side; height = side }
            val cardSide = (side * 0.8f).toInt()
            b.cardExerciseOverlay.layoutParams = (b.cardExerciseOverlay.layoutParams as FrameLayout.LayoutParams).apply { width = cardSide; height = cardSide }
            b.ringView.requestLayout()
            b.cardExerciseOverlay.requestLayout()
        }
    }

    private fun onRingTapped(b: FragmentLearnCoverProgressRingBinding) {
        if (ringCardOpen) return
        val ref = ringQueue.getOrNull(ringQueuePos) ?: return
        ringCardOpen = true
        currentExerciseAnswered = false
        renderExerciseBody(b.containerRingDynamic, ref.exercise, ref.lesson, setPrompt = { b.textRingPrompt.text = it }) { correct ->
            handleAnswer(ref.exerciseKey, correct, isReview = true) {
                ringDueDone++
                b.ringView.animateProgressTo(ringDueDone.toFloat() / ringDueTotal.toFloat())
                hapticFeedback(b.cardExerciseOverlay, correct)
                b.cardExerciseOverlay.postDelayed({ closeRingCard(b) }, RING_CLOSE_DELAY_MS)
            }
        }
        b.cardExerciseOverlay.alpha = 0f
        b.cardExerciseOverlay.scaleX = 0.6f
        b.cardExerciseOverlay.scaleY = 0.6f
        b.cardExerciseOverlay.visibility = View.VISIBLE
        b.cardExerciseOverlay.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220L).start()
    }

    private fun closeRingCard(b: FragmentLearnCoverProgressRingBinding) {
        if (contentContainer == null || ringBinding !== b) return
        b.cardExerciseOverlay.animate().alpha(0f).scaleX(0.6f).scaleY(0.6f).setDuration(200L)
            .withEndAction {
                if (contentContainer == null || ringBinding !== b) return@withEndAction
                b.cardExerciseOverlay.visibility = View.GONE
                ringQueuePos++
                ringCardOpen = false
            }.start()
    }

    // =======================================================================
    // Cover: "course_dashboard" - three stacked bands (stats, lesson
    // mini-map for the current unit, review queue by Leitner box), plus a
    // 4th slot band that opens inline (never navigates away from this
    // screen) when a mini-map segment or review-box row is tapped.
    // =======================================================================

    private fun bindCourseDashboard(b: FragmentLearnCoverCourseDashboardBinding) {
        b.btnDashSlotClose.setOnClickListener { closeDashboardSlot(b) }
        refreshDashboardContent(b)
    }

    private fun refreshDashboardContent(b: FragmentLearnCoverCourseDashboardBinding) {
        val app = mainActivity?.app ?: return
        if (contentContainer == null) return
        val store = app.learnProgress
        b.textDashStreak.text = "🔥 ${store.currentStreak()}"
        b.textDashXp.text = "${store.totalXp()}"
        b.textDashLevel.text = "${store.totalXp() / 100 + 1}"

        val c = course
        val unit = currentUnit ?: c?.units?.firstOrNull { u -> u.lessons.any { !store.isLessonCompleted(it.id) } } ?: c?.units?.firstOrNull()
        b.textDashUnitTitle.text = unit?.title ?: "No course available for this language yet."
        b.containerLessonMiniMap.removeAllViews()
        if (unit != null) {
            for (lesson in unit.lessons) {
                val completed = store.isLessonCompleted(lesson.id)
                val inProgress = !completed && lessonCursor?.lesson?.id == lesson.id
                val seg = View(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(6) }
                    setBackgroundResource(
                        when {
                            completed -> R.drawable.bg_learn_segment_filled
                            inProgress -> R.drawable.bg_learn_segment_half
                            else -> R.drawable.bg_learn_segment_outline
                        }
                    )
                    setOnClickListener { openDashboardLesson(b, unit, lesson) }
                }
                b.containerLessonMiniMap.addView(seg)
            }
        }

        b.containerReviewBoxes.removeAllViews()
        for ((box, dueCount) in LearnReviewQueue.boxCounts(store)) {
            b.containerReviewBoxes.addView(buildBoxRow(box, dueCount) { openDashboardReviewBox(b, box) })
        }
    }

    private fun buildBoxRow(box: Int, dueCount: Int, onClick: () -> Unit): View {
        val card = CardView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
            radius = dp(8).toFloat()
            cardElevation = dp(1).toFloat()
        }
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val label = TextView(requireContext()).apply {
            text = "Box $box"
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val countText = TextView(requireContext()).apply {
            text = if (dueCount == 0) "up to date" else "$dueCount due"
            textSize = 13f
            alpha = if (dueCount == 0) 0.5f else 1f
        }
        row.addView(label)
        row.addView(countText)
        if (dueCount > 0) {
            row.isClickable = true
            row.isFocusable = true
            row.foreground = ContextCompat.getDrawable(requireContext(), android.R.drawable.list_selector_background)
            row.setOnClickListener { onClick() }
        }
        card.addView(row)
        return card
    }

    private fun openDashboardLesson(b: FragmentLearnCoverCourseDashboardBinding, unit: LearnUnit, lesson: LearnLesson) {
        dashSlotIsReview = false
        startLessonCursor(unit, lesson)
        b.textDashSlotHeader.text = lesson.title
        renderDashboardSlotExercise(b)
        showDashboardSlot(b)
    }

    private fun openDashboardReviewBox(b: FragmentLearnCoverCourseDashboardBinding, box: Int) {
        val c = course ?: return
        val app = mainActivity?.app ?: return
        dashReviewQueue = LearnReviewQueue.dueInBox(c, app.learnProgress, box)
        dashReviewPos = 0
        dashSlotIsReview = true
        if (dashReviewQueue.isEmpty()) { toast("Nothing due in Box $box right now"); return }
        b.textDashSlotHeader.text = "Review — Box $box"
        renderDashboardSlotExercise(b)
        showDashboardSlot(b)
    }

    private fun renderDashboardSlotExercise(b: FragmentLearnCoverCourseDashboardBinding) {
        currentExerciseAnswered = false
        b.textDashSlotFeedback.text = ""
        b.btnDashSlotContinue.visibility = View.GONE
        val (exercise, lesson, key) = if (dashSlotIsReview) {
            val ref = dashReviewQueue.getOrNull(dashReviewPos) ?: return
            Triple(ref.exercise, ref.lesson, ref.exerciseKey)
        } else {
            val c = lessonCursor ?: return
            val ex = c.lesson.exercises[c.index]
            Triple(ex, c.lesson, ex.key(c.lesson.id, c.index))
        }
        renderExerciseBody(b.containerDashSlotDynamic, exercise, lesson, setPrompt = { b.textDashSlotPrompt.text = it }) { correct ->
            handleAnswer(key, correct, isReview = dashSlotIsReview) {
                renderAnswerFeedback(b.textDashSlotFeedback, correct, exercise)
                b.btnDashSlotContinue.visibility = View.VISIBLE
                b.btnDashSlotContinue.setOnClickListener { advanceDashboardSlot(b) }
            }
        }
    }

    private fun advanceDashboardSlot(b: FragmentLearnCoverCourseDashboardBinding) {
        if (dashSlotIsReview) {
            dashReviewPos++
            if (dashReviewPos < dashReviewQueue.size) renderDashboardSlotExercise(b) else closeDashboardSlot(b)
            return
        }
        when (val step = stepLessonForward()) {
            is LessonStep.Next -> renderDashboardSlotExercise(b)
            is LessonStep.Done -> {
                b.textDashSlotFeedback.text = "Lesson complete! +${step.xpEarned} XP"
                b.btnDashSlotContinue.visibility = View.GONE
                b.containerDashSlotDynamic.removeAllViews()
                b.textDashSlotPrompt.text = ""
                Handler(Looper.getMainLooper()).postDelayed({ if (contentContainer != null && dashboardBinding === b) closeDashboardSlot(b) }, LESSON_DONE_PAUSE_MS)
            }
        }
    }

    private fun showDashboardSlot(b: FragmentLearnCoverCourseDashboardBinding) {
        b.cardDashboardSlot.visibility = View.VISIBLE
    }

    private fun closeDashboardSlot(b: FragmentLearnCoverCourseDashboardBinding) {
        b.cardDashboardSlot.visibility = View.GONE
        refreshDashboardContent(b)
    }

    // =======================================================================
    // Cover: "listen_choose" - built for the listening exercise type. Audio
    // auto-plays on open, repeatable via the speaker button; exactly two
    // oversized answer buttons; right/wrong shown by color + haptics, never
    // text. multiple_choice reuses the same 2-button shape directly;
    // word_bank/speaking don't fit a 2-button interaction at all, so they
    // degrade to a plain skip affordance rather than a broken layout - see
    // class doc's deviation note in the final report for why.
    // =======================================================================

    private fun bindListenChoose(b: FragmentLearnCoverListenChooseBinding) {
        ensureLessonSelected()
        b.btnListenSpeaker.setOnClickListener {
            lessonCursor?.let { c -> speakCourseText(c.lesson.exercises[c.index].text) }
        }
        renderListenChooseExercise(b)
    }

    private fun renderListenChooseExercise(b: FragmentLearnCoverListenChooseBinding) {
        val c = lessonCursor ?: return
        currentExerciseAnswered = false
        b.dotListenFeedback.visibility = View.INVISIBLE
        val exercise = c.lesson.exercises[c.index]
        resetListenButton(b.btnListenOptionA)
        resetListenButton(b.btnListenOptionB)
        b.btnListenOptionA.isEnabled = true
        b.btnListenOptionB.isEnabled = true

        when (exercise.type) {
            ExerciseType.LISTENING, ExerciseType.MULTIPLE_CHOICE -> {
                b.textListenStatus.text = if (exercise.type == ExerciseType.MULTIPLE_CHOICE) (exercise.gloss ?: "Which one is correct?") else "Listen, then pick what you heard"
                var options = (distractorsFor(exercise, c.lesson, 1) + exercise.text).shuffled()
                if (options.size < 2) options = listOf(exercise.text, exercise.text)
                b.btnListenOptionA.text = options[0]
                b.btnListenOptionB.text = options.getOrElse(1) { options[0] }
                val onPick: (Button) -> Unit = { pickedBtn ->
                    val correct = pickedBtn.text.toString() == exercise.text
                    b.btnListenOptionA.isEnabled = false
                    b.btnListenOptionB.isEnabled = false
                    handleAnswer(exercise.key(c.lesson.id, c.index), correct, isReview = false) {
                        val color = if (correct) "#4CAF50" else "#F44336"
                        pickedBtn.setBackgroundColor(Color.parseColor(color))
                        b.dotListenFeedback.setBackgroundColor(Color.parseColor(color))
                        b.dotListenFeedback.visibility = View.VISIBLE
                        hapticFeedback(pickedBtn, correct)
                        Handler(Looper.getMainLooper()).postDelayed({ if (contentContainer != null && listenChooseBinding === b) advanceListenChoose(b) }, LISTEN_ADVANCE_DELAY_MS)
                    }
                }
                b.btnListenOptionA.setOnClickListener { onPick(b.btnListenOptionA) }
                b.btnListenOptionB.setOnClickListener { onPick(b.btnListenOptionB) }
                if (exercise.type == ExerciseType.LISTENING) speakCourseText(exercise.text)
            }
            ExerciseType.WORD_BANK, ExerciseType.SPEAKING -> {
                b.textListenStatus.text = "This exercise type needs the Default or Flex layout - tap either button to skip it here."
                b.btnListenOptionA.text = "Skip to next"
                b.btnListenOptionB.text = "Skip to next"
                b.btnListenOptionA.setOnClickListener { advanceListenChoose(b) }
                b.btnListenOptionB.setOnClickListener { advanceListenChoose(b) }
            }
        }
    }

    private fun resetListenButton(btn: Button) {
        btn.setBackgroundColor(Color.parseColor("#1565C0"))
        btn.setTextColor(Color.WHITE)
    }

    private fun advanceListenChoose(b: FragmentLearnCoverListenChooseBinding) {
        when (val step = stepLessonForward()) {
            is LessonStep.Next -> renderListenChooseExercise(b)
            is LessonStep.Done -> {
                toast("Lesson complete! +${step.xpEarned} XP")
                ensureLessonSelected()
                renderListenChooseExercise(b)
            }
        }
    }

    // =======================================================================
    // Flex: "flex_default" - viewing pane (progress/prompt/feedback) above
    // the hinge, control pane (this exercise's interactive body + Continue)
    // below - this project's established Flex Mode split, new baseline for
    // this tab (it had no existing Flex layout before this pass - checked
    // first, per instructions).
    // =======================================================================

    private fun bindFlexDefault(b: FragmentLearnFlexDefaultBinding) {
        ensureLessonSelected()
        b.paneControl.btnContinueFlex.setOnClickListener { advanceFlexDefault(b) }
        renderFlexDefaultExercise(b)
    }

    private fun renderFlexDefaultExercise(b: FragmentLearnFlexDefaultBinding) {
        val c = lessonCursor ?: return
        currentExerciseAnswered = false
        b.paneViewing.textFeedbackFlex.text = ""
        b.paneControl.btnContinueFlex.visibility = View.GONE
        b.paneViewing.textProgressFlex.text = "Exercise ${c.index + 1}/${c.lesson.exercises.size}"
        val exercise = c.lesson.exercises[c.index]
        renderExerciseBody(b.paneControl.containerDynamicFlex, exercise, c.lesson, setPrompt = { b.paneViewing.textPromptFlex.text = it }) { correct ->
            handleAnswer(exercise.key(c.lesson.id, c.index), correct, isReview = false) {
                renderAnswerFeedback(b.paneViewing.textFeedbackFlex, correct, exercise)
                b.paneControl.btnContinueFlex.visibility = View.VISIBLE
            }
        }
    }

    private fun advanceFlexDefault(b: FragmentLearnFlexDefaultBinding) {
        when (val step = stepLessonForward()) {
            is LessonStep.Next -> renderFlexDefaultExercise(b)
            is LessonStep.Done -> {
                b.paneViewing.textFeedbackFlex.text = "Lesson complete! +${step.xpEarned} XP"
                b.paneControl.btnContinueFlex.visibility = View.GONE
                b.paneControl.containerDynamicFlex.removeAllViews()
                ensureLessonSelected()
                Handler(Looper.getMainLooper()).postDelayed({
                    if (contentContainer != null && currentActive == LearnActiveLayout.FLEX_DEFAULT) renderFlexDefaultExercise(b)
                }, LESSON_DONE_PAUSE_MS)
            }
        }
    }

    // =======================================================================
    // Flex: "elastic_split" - structurally identical at rest to
    // flex_default (same viewing/control chrome); the prompt/answer-area
    // balance shifts per exercise type and animates on every exercise
    // transition. Deliberately does NOT move the pane boundary off the live
    // hinge (that stays governed solely by positionFlexPanes, same as every
    // other Flex variant, to guarantee touch targets never land on the
    // physically occluded crease per task item 5) - "elastic" is realized
    // as an animated prompt text size + interactive-area padding shift
    // instead. See applyElasticEmphasis.
    // =======================================================================

    private fun bindElasticSplit(b: FragmentLearnFlexElasticSplitBinding) {
        ensureLessonSelected()
        b.paneElasticControl.btnContinueFlex.setOnClickListener { advanceElasticSplit(b) }
        renderElasticSplitExercise(b)
    }

    private fun renderElasticSplitExercise(b: FragmentLearnFlexElasticSplitBinding) {
        val c = lessonCursor ?: return
        currentExerciseAnswered = false
        b.paneElasticViewing.textFeedbackFlex.text = ""
        b.paneElasticControl.btnContinueFlex.visibility = View.GONE
        b.paneElasticViewing.textProgressFlex.text = "Exercise ${c.index + 1}/${c.lesson.exercises.size}"
        val exercise = c.lesson.exercises[c.index]
        renderExerciseBody(b.paneElasticControl.containerDynamicFlex, exercise, c.lesson, setPrompt = { b.paneElasticViewing.textPromptFlex.text = it }) { correct ->
            handleAnswer(exercise.key(c.lesson.id, c.index), correct, isReview = false) {
                renderAnswerFeedback(b.paneElasticViewing.textFeedbackFlex, correct, exercise)
                b.paneElasticControl.btnContinueFlex.visibility = View.VISIBLE
            }
        }
        applyElasticEmphasis(b, exercise.type)
    }

    private fun advanceElasticSplit(b: FragmentLearnFlexElasticSplitBinding) {
        when (val step = stepLessonForward()) {
            is LessonStep.Next -> renderElasticSplitExercise(b)
            is LessonStep.Done -> {
                b.paneElasticViewing.textFeedbackFlex.text = "Lesson complete! +${step.xpEarned} XP"
                b.paneElasticControl.btnContinueFlex.visibility = View.GONE
                b.paneElasticControl.containerDynamicFlex.removeAllViews()
                ensureLessonSelected()
                Handler(Looper.getMainLooper()).postDelayed({
                    if (contentContainer != null && currentActive == LearnActiveLayout.FLEX_ELASTIC_SPLIT) renderElasticSplitExercise(b)
                }, LESSON_DONE_PAUSE_MS)
            }
        }
    }

    private fun applyElasticEmphasis(b: FragmentLearnFlexElasticSplitBinding, type: ExerciseType) {
        val (promptSp, interactivePaddingDp) = when (type) {
            ExerciseType.MULTIPLE_CHOICE, ExerciseType.LISTENING -> 24f to 6f   // "tall prompt"
            ExerciseType.WORD_BANK -> 16f to 16f                                // compact prompt, roomy tile area
            ExerciseType.SPEAKING -> 19f to 10f                                 // balanced 50/50
        }
        animateTextSizeSp(b.paneElasticViewing.textPromptFlex, promptSp)
        val container = b.paneElasticControl.containerDynamicFlex
        val targetPaddingPx = dp(interactivePaddingDp.toInt())
        ValueAnimator.ofInt(container.paddingTop, targetPaddingPx).apply {
            duration = 260L
            addUpdateListener { container.setPadding(container.paddingLeft, it.animatedValue as Int, container.paddingRight, container.paddingBottom) }
            start()
        }
    }

    private fun animateTextSizeSp(view: TextView, targetSp: Float) {
        val startSp = view.textSize / resources.displayMetrics.scaledDensity
        ValueAnimator.ofFloat(startSp, targetSp).apply {
            duration = 260L
            addUpdateListener { view.textSize = it.animatedValue as Float }
            start()
        }
    }

    // =======================================================================
    // Flex: "flip_sort" - top pane: bare prompt only, tap to flip and
    // reveal the answer like a physical flashcard. Bottom pane: two silent
    // (icon/color only) full-width tap zones that log the Leitner-box
    // recall grade directly (self-graded, Anki-style - "did you know it?").
    // ALL 4 exercise types funnel through this one gesture, per spec - see
    // [flipFrontText] for how each type's front-of-card text is derived.
    // =======================================================================

    private fun bindFlipSort(b: FragmentLearnFlexFlipSortBinding) {
        ensureLessonSelected()
        b.paneFlipTop.setOnClickListener { flipCurrentCard(b) }
        b.zoneFlipWrong.setOnClickListener { gradeFlipCard(b, correct = false) }
        b.zoneFlipRight.setOnClickListener { gradeFlipCard(b, correct = true) }
        renderFlipCard(b)
    }

    private fun renderFlipCard(b: FragmentLearnFlexFlipSortBinding) {
        val c = lessonCursor ?: return
        currentExerciseAnswered = false
        flipRevealed = false
        val exercise = c.lesson.exercises[c.index]
        b.textFlipCard.scaleX = 1f
        b.textFlipCard.text = flipFrontText(exercise)
        b.textFlipHint.text = "Tap to flip"
        if (exercise.type == ExerciseType.LISTENING) speakCourseText(exercise.text)
    }

    private fun flipFrontText(exercise: LearnExercise): String = when (exercise.type) {
        ExerciseType.MULTIPLE_CHOICE -> exercise.gloss ?: "?"
        ExerciseType.WORD_BANK -> exercise.text.trim().split(Regex("\\s+")).shuffled().joinToString(" / ")
        ExerciseType.LISTENING -> "🔊 Tap to hear again, then flip"
        ExerciseType.SPEAKING -> "Say it - ready?"
    }

    private fun flipCurrentCard(b: FragmentLearnFlexFlipSortBinding) {
        val c = lessonCursor ?: return
        val exercise = c.lesson.exercises[c.index]
        flipRevealed = !flipRevealed
        b.textFlipCard.animate().scaleX(0f).setDuration(110L).withEndAction {
            if (contentContainer == null || flipSortBinding !== b) return@withEndAction
            b.textFlipCard.text = if (flipRevealed) exercise.text else flipFrontText(exercise)
            b.textFlipHint.text = if (flipRevealed) "Tap X or check below" else "Tap to flip"
            if (flipRevealed && exercise.type == ExerciseType.LISTENING) speakCourseText(exercise.text)
            b.textFlipCard.animate().scaleX(1f).setDuration(110L).start()
        }.start()
    }

    private fun gradeFlipCard(b: FragmentLearnFlexFlipSortBinding, correct: Boolean) {
        val c = lessonCursor ?: return
        if (!flipRevealed) { flipCurrentCard(b); return }
        val exercise = c.lesson.exercises[c.index]
        handleAnswer(exercise.key(c.lesson.id, c.index), correct, isReview = false) {
            hapticFeedback(if (correct) b.zoneFlipRight else b.zoneFlipWrong, correct)
            when (stepLessonForward()) {
                is LessonStep.Next -> renderFlipCard(b)
                is LessonStep.Done -> { ensureLessonSelected(); renderFlipCard(b) }
            }
        }
    }

    // =======================================================================
    // Flex: "speaking_arc" - only the speaking exercise gets the bespoke
    // arc control row (replay-target/record/replay-mine/skip along a
    // shallow upward arc, see [layoutArcRow]); every other exercise type
    // falls back to the same standard dynamic-container + Continue chrome
    // flex_default uses. Top pane feedback is icon-only, no score.
    // =======================================================================

    private fun bindSpeakingArc(b: FragmentLearnFlexSpeakingArcBinding) {
        ensureLessonSelected()
        b.btnArcStandardContinue.setOnClickListener { advanceArc(b) }
        renderArcExercise(b)
    }

    private fun renderArcExercise(b: FragmentLearnFlexSpeakingArcBinding) {
        val c = lessonCursor ?: return
        currentExerciseAnswered = false
        b.imageArcFeedback.visibility = View.INVISIBLE
        b.textArcStatus.text = ""
        val exercise = c.lesson.exercises[c.index]
        val isSpeaking = exercise.type == ExerciseType.SPEAKING
        b.containerArcSpeaking.visibility = if (isSpeaking) View.VISIBLE else View.GONE
        b.scrollArcStandard.visibility = if (isSpeaking) View.GONE else View.VISIBLE
        if (isSpeaking) {
            b.textArcPrompt.text = "Say this out loud:\n“${exercise.text}”"
            bindArcSpeakingControls(b, exercise, c)
            layoutArcRow(b)
        } else {
            b.btnArcStandardContinue.visibility = View.GONE
            renderExerciseBody(b.containerArcStandardDynamic, exercise, c.lesson, setPrompt = { b.textArcPrompt.text = it }) { correct ->
                handleAnswer(exercise.key(c.lesson.id, c.index), correct, isReview = false) {
                    showArcFeedbackIcon(b, correct)
                    b.btnArcStandardContinue.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun layoutArcRow(b: FragmentLearnFlexSpeakingArcBinding) {
        b.containerArcSpeaking.post {
            if (contentContainer == null || speakingArcBinding !== b) return@post
            val buttons = listOf(b.btnArcReplayTarget, b.btnArcRecord, b.btnArcReplayMine, b.btnArcSkip)
            val n = buttons.size
            val maxLiftPx = 22 * resources.displayMetrics.density
            buttons.forEachIndexed { i, view ->
                val t = (i - (n - 1) / 2f) / ((n - 1) / 2f)
                view.translationY = -(maxLiftPx * (1f - t * t))
            }
        }
    }

    private fun bindArcSpeakingControls(b: FragmentLearnFlexSpeakingArcBinding, exercise: LearnExercise, c: LessonCursor) {
        var lastRecordingText: String? = null
        b.btnArcReplayTarget.setOnClickListener { speakCourseText(exercise.text) }
        b.btnArcReplayMine.isEnabled = false
        b.btnArcReplayMine.setOnClickListener { lastRecordingText?.let { toast("You said: “$it”") } }
        b.btnArcSkip.setOnClickListener {
            if (currentExerciseAnswered) return@setOnClickListener
            currentExerciseAnswered = true
            b.textArcStatus.text = "Skipped - not scored."
            advanceArc(b)
        }
        b.btnArcRecord.setOnClickListener {
            val activity = mainActivity ?: return@setOnClickListener
            val app = activity.app
            if (!activity.hasMicPermission()) {
                activity.requestMicPermissionIfNeeded()
                toast("Grant microphone permission, then tap Record again", long = true)
                return@setOnClickListener
            }
            val langCode = course?.language ?: "en"
            if (!app.vosk.isModelDownloaded(langCode)) {
                b.textArcStatus.text = "No offline voice-input pack downloaded - download it on Translate, or tap skip."
                return@setOnClickListener
            }
            b.btnArcRecord.isEnabled = false
            b.textArcStatus.text = "Loading voice recognizer…"
            app.vosk.loadModelAsync(langCode) { ok, err ->
                if (contentContainer == null || speakingArcBinding !== b) return@loadModelAsync
                if (!ok) { b.textArcStatus.text = "Couldn't load recognizer: $err"; b.btnArcRecord.isEnabled = true; return@loadModelAsync }
                val recognizer = app.vosk.newRecognizer()
                if (recognizer == null) { b.textArcStatus.text = "Couldn't start recognizer"; b.btnArcRecord.isEnabled = true; return@loadModelAsync }
                b.textArcStatus.text = "Listening…"
                app.mic.start(recognizer, recordToFile = null, listener = object : MicPipeline.Listener {
                    override fun onFinal(text: String) {
                        if (contentContainer == null || speakingArcBinding !== b) return
                        lastRecordingText = text
                        b.btnArcReplayMine.isEnabled = true
                        val correct = AnswerChecker.matches(exercise.text, text)
                        b.textArcStatus.text = "Heard: “$text”"
                        handleAnswer(exercise.key(c.lesson.id, c.index), correct, isReview = false) {
                            showArcFeedbackIcon(b, correct)
                            b.btnArcRecord.isEnabled = true
                            Handler(Looper.getMainLooper()).postDelayed({
                                if (contentContainer != null && speakingArcBinding === b) advanceArc(b)
                            }, LESSON_DONE_PAUSE_MS)
                        }
                    }
                    override fun onError(message: String) {
                        if (contentContainer != null && speakingArcBinding === b) { b.textArcStatus.text = message; b.btnArcRecord.isEnabled = true }
                    }
                    override fun onListeningStopped() {}
                })
            }
        }
    }

    private fun showArcFeedbackIcon(b: FragmentLearnFlexSpeakingArcBinding, correct: Boolean) {
        b.imageArcFeedback.setImageResource(if (correct) android.R.drawable.checkbox_on_background else android.R.drawable.ic_delete)
        b.imageArcFeedback.visibility = View.VISIBLE
    }

    private fun advanceArc(b: FragmentLearnFlexSpeakingArcBinding) {
        when (stepLessonForward()) {
            is LessonStep.Next -> renderArcExercise(b)
            is LessonStep.Done -> { ensureLessonSelected(); renderArcExercise(b) }
        }
    }

    // ---------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------

    override fun onPause() {
        super.onPause()
        mainActivity?.app?.mic?.stop()
        mainActivity?.app?.tts?.stop()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        unregisterLayoutPrefsListener()
        mainActivity?.app?.mic?.stop()
        contentContainer = null
        defaultBinding = null
        ringBinding = null
        dashboardBinding = null
        listenChooseBinding = null
        flexDefaultBinding = null
        flipSortBinding = null
        elasticSplitBinding = null
        speakingArcBinding = null
        currentActive = null
    }

    companion object {
        private const val TAG = "LearnFragment"
        private const val LAYOUT_PREFS_FILE_NAME = "layout_prefs"
        private const val LAYOUT_KEY_COVER = "variant_learn_cover"
        private const val LAYOUT_KEY_FLEX = "variant_learn_flex"
        private const val RING_CLOSE_DELAY_MS = 900L
        private const val LISTEN_ADVANCE_DELAY_MS = 1100L
        private const val LESSON_DONE_PAUSE_MS = 1400L
    }
}
