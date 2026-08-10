package com.retroid.translator.ui

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.cardview.widget.CardView
import androidx.fragment.app.Fragment
import com.retroid.translator.MainActivity
import com.retroid.translator.audio.MicPipeline
import com.retroid.translator.databinding.FragmentLearnBinding
import com.retroid.translator.engine.VoicePreferences
import com.retroid.translator.learn.AnswerChecker
import com.retroid.translator.learn.ExerciseType
import com.retroid.translator.learn.LearnCourse
import com.retroid.translator.learn.LearnCourseLoader
import com.retroid.translator.learn.LearnExercise
import com.retroid.translator.learn.LearnLesson
import com.retroid.translator.learn.LearnUnit

/**
 * "Learn" tab: a small, gamified course (units -> lessons -> exercises) in
 * the style of Duolingo, built entirely on top of the app's existing shared
 * engines (`TtsRouter` for Listening audio, `VoskEngine`/`MicPipeline` for
 * Speaking recognition) - no duplicate speech/translation stack. Content is
 * a bundled JSON asset per language (see [LearnCourseLoader]); progress
 * (XP, streak, per-lesson completion, per-exercise Leitner-box SRS state)
 * is local-only SQLite via [com.retroid.translator.learn.LearnProgressStore].
 *
 * One fragment, one layout, four "screens" toggled by visibility (unit list
 * -> lesson list -> one exercise at a time -> lesson-complete summary) -
 * matches this app's existing preference for plain imperative Android
 * views over a navigation/fragment-per-screen setup.
 */
class LearnFragment : Fragment() {

    private var _binding: FragmentLearnBinding? = null
    private val binding get() = _binding!!
    private val mainActivity get() = activity as? MainActivity

    private var course: LearnCourse? = null
    private var currentUnit: LearnUnit? = null
    private var currentLesson: LearnLesson? = null
    private var currentExerciseIndex: Int = 0
    private var xpEarnedThisLesson: Int = 0
    private var currentExerciseAnswered: Boolean = false

    // Word-bank exercise scratch state
    private val wordBankAnswerTiles = mutableListOf<String>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLearnBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnBackToUnits.setOnClickListener { showUnitsScreen() }
        binding.btnSummaryContinue.setOnClickListener { showLessonsScreen() }
        loadCourseAndShowUnits()
    }

    override fun onResume() {
        super.onResume()
        // Stats can change from progress made earlier, e.g. after a restart.
        if (_binding != null && binding.screenUnits.visibility == View.VISIBLE) refreshStatsHeader()
    }

    // ---------------------------------------------------------------------
    // Screen 1: units
    // ---------------------------------------------------------------------

    private fun loadCourseAndShowUnits() {
        val app = mainActivity?.app ?: return
        // Only "en" has a verified, complete course right now (see README) -
        // pick it if present, otherwise fall back to whatever's bundled so a
        // future added course still surfaces without a UI change here.
        val codes = LearnCourseLoader.availableCourseCodes(requireContext())
        val code = if ("en" in codes) "en" else codes.firstOrNull()
        course = code?.let { LearnCourseLoader.load(requireContext(), it) }
        showUnitsScreen()
        if (course == null) {
            binding.textLearnNoCourse.visibility = View.VISIBLE
        }
        refreshStatsHeader(app)
    }

    private fun refreshStatsHeader(app: com.retroid.translator.TranslatorApp? = mainActivity?.app) {
        val a = app ?: return
        if (_binding == null) return
        val streak = a.learnProgress.currentStreak()
        val xp = a.learnProgress.totalXp()
        binding.textLearnStats.text = "🔥 $streak day streak   ⭐ $xp XP"
    }

    private fun showUnitsScreen() {
        if (_binding == null) return
        setScreen(Screen.UNITS)
        refreshStatsHeader()
        binding.unitsListContainer.removeAllViews()
        val app = mainActivity?.app ?: return
        val c = course ?: return
        for (unit in c.units) {
            val completedLessons = unit.lessons.count { app.learnProgress.isLessonCompleted(it.id) }
            val row = buildListRow(
                title = unit.title,
                subtitle = "$completedLessons/${unit.lessons.size} lessons complete",
                buttonText = if (completedLessons == unit.lessons.size) "Review" else "Open"
            ) {
                currentUnit = unit
                showLessonsScreen()
            }
            binding.unitsListContainer.addView(row)
        }
    }

    // ---------------------------------------------------------------------
    // Screen 2: lessons within a unit
    // ---------------------------------------------------------------------

    private fun showLessonsScreen() {
        if (_binding == null) return
        val unit = currentUnit
        if (unit == null) {
            showUnitsScreen()
            return
        }
        setScreen(Screen.LESSONS)
        binding.textLessonsUnitTitle.text = unit.title
        binding.lessonsListContainer.removeAllViews()
        val app = mainActivity?.app ?: return
        for (lesson in unit.lessons) {
            val done = app.learnProgress.isLessonCompleted(lesson.id)
            val row = buildListRow(
                title = lesson.title,
                subtitle = if (done) "✓ Completed - ${lesson.exercises.size} exercises" else "${lesson.exercises.size} exercises",
                buttonText = if (done) "Practice again" else "Start"
            ) {
                startLesson(lesson)
            }
            binding.lessonsListContainer.addView(row)
        }
    }

    /** Small reusable "title / subtitle / action button" card, built in code rather than a new layout file. */
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

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------------
    // Screen 3: exercises
    // ---------------------------------------------------------------------

    private fun startLesson(lesson: LearnLesson) {
        currentLesson = lesson
        currentExerciseIndex = 0
        xpEarnedThisLesson = 0
        showExercise()
    }

    private fun showExercise() {
        if (_binding == null) return
        val lesson = currentLesson ?: return
        if (currentExerciseIndex >= lesson.exercises.size) {
            finishLesson()
            return
        }
        setScreen(Screen.EXERCISE)
        currentExerciseAnswered = false
        binding.textExerciseFeedback.text = ""
        binding.btnExerciseContinue.visibility = View.GONE
        binding.exerciseDynamicContainer.removeAllViews()

        val exercise = lesson.exercises[currentExerciseIndex]
        binding.textExerciseProgress.text = "Exercise ${currentExerciseIndex + 1}/${lesson.exercises.size}"

        when (exercise.type) {
            ExerciseType.MULTIPLE_CHOICE -> renderMultipleChoice(exercise, lesson)
            ExerciseType.WORD_BANK -> renderWordBank(exercise)
            ExerciseType.LISTENING -> renderListening(exercise, lesson)
            ExerciseType.SPEAKING -> renderSpeaking(exercise)
        }
    }

    private fun distractorsFor(exercise: LearnExercise, lesson: LearnLesson, count: Int): List<String> =
        lesson.exercises.map { it.text }.filter { it != exercise.text }.shuffled().take(count)

    private fun renderMultipleChoice(exercise: LearnExercise, lesson: LearnLesson) {
        binding.textExercisePrompt.text = exercise.gloss ?: "Which one is correct?"
        val options = (distractorsFor(exercise, lesson, 3) + exercise.text).shuffled()
        val container = binding.exerciseDynamicContainer
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
                onAnswered(exercise, correct)
            }
            container.addView(btn)
        }
    }

    private fun renderListening(exercise: LearnExercise, lesson: LearnLesson) {
        binding.textExercisePrompt.text = "Listen, then pick what you heard"
        val container = binding.exerciseDynamicContainer

        val playBtn = Button(requireContext()).apply {
            text = "🔊 Play phrase"
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        playBtn.setOnClickListener { speakCourseText(exercise.text) }
        container.addView(playBtn)
        // Play once automatically so the exercise doesn't feel broken if the
        // learner doesn't notice the button.
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
                onAnswered(exercise, correct)
            }
            container.addView(btn)
        }
    }

    private fun renderWordBank(exercise: LearnExercise) {
        binding.textExercisePrompt.text = "Put the words in the right order"
        val container = binding.exerciseDynamicContainer
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
        clearBtn.setOnClickListener { renderWordBank(exercise) }
        checkBtn.setOnClickListener {
            val given = wordBankAnswerTiles.joinToString(" ")
            val correct = normalizeWords(given) == normalizeWords(exercise.text)
            clearBtn.isEnabled = false
            checkBtn.isEnabled = false
            onAnswered(exercise, correct)
        }
        actionsRow.addView(clearBtn)
        actionsRow.addView(checkBtn)
        container.addView(actionsRow)
    }

    private fun normalizeWords(s: String): List<String> =
        s.lowercase().replace(Regex("[^a-z0-9\\s]"), "").split(Regex("\\s+")).filter { it.isNotBlank() }

    private fun renderSpeaking(exercise: LearnExercise) {
        binding.textExercisePrompt.text = "Say this out loud:\n“${exercise.text}”"
        val container = binding.exerciseDynamicContainer

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
            binding.textExerciseFeedback.text = "Skipped - not scored."
            binding.btnExerciseContinue.visibility = View.VISIBLE
            currentExerciseAnswered = true
        }

        recordBtn.setOnClickListener {
            val activity = mainActivity ?: return@setOnClickListener
            val app = activity.app
            if (!activity.hasMicPermission()) {
                activity.requestMicPermissionIfNeeded()
                Toast.makeText(requireContext(), "Grant microphone permission, then tap Record again", Toast.LENGTH_LONG).show()
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
                if (_binding == null) return@loadModelAsync
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
                        if (_binding == null) return
                        val correct = AnswerChecker.matches(exercise.text, text)
                        val ratioPct = (AnswerChecker.overlapRatio(exercise.text, text) * 100).toInt()
                        statusText.text = "Heard: “$text” ($ratioPct% word match)"
                        skipBtn.isEnabled = false
                        onAnswered(exercise, correct)
                    }
                    override fun onError(message: String) {
                        if (_binding == null) return
                        statusText.text = message
                        recordBtn.isEnabled = true
                    }
                    override fun onListeningStopped() {}
                })
            }
        }
    }

    private fun speakCourseText(text: String) {
        val app = mainActivity?.app ?: return
        val langCode = course?.language ?: "en"
        val gender = VoicePreferences.getGender(requireContext())
        app.tts.speak(text, langCode, gender, onDone = {}, onError = { })
    }

    private fun onAnswered(exercise: LearnExercise, correct: Boolean) {
        if (currentExerciseAnswered) return
        currentExerciseAnswered = true
        val app = mainActivity?.app ?: return
        val lesson = currentLesson ?: return
        app.learnProgress.recordAnswer(exercise.key(lesson.id, currentExerciseIndex), correct)
        if (correct) {
            xpEarnedThisLesson += 10
            binding.textExerciseFeedback.text = "✓ Correct! +10 XP"
            binding.textExerciseFeedback.setTextColor(Color.parseColor("#2E7D32"))
        } else {
            binding.textExerciseFeedback.text = "✗ Not quite. Correct answer: “${exercise.text}”"
            binding.textExerciseFeedback.setTextColor(Color.parseColor("#C62828"))
        }
        binding.btnExerciseContinue.visibility = View.VISIBLE
        binding.btnExerciseContinue.setOnClickListener {
            currentExerciseIndex++
            showExercise()
        }
    }

    private fun finishLesson() {
        val app = mainActivity?.app ?: return
        val lesson = currentLesson ?: return
        app.learnProgress.addXp(xpEarnedThisLesson)
        app.learnProgress.recordLessonCompleted(lesson.id, xpEarnedThisLesson)
        val newStreak = app.learnProgress.recordActivityToday()
        setScreen(Screen.SUMMARY)
        binding.textSummaryXp.text = "+$xpEarnedThisLesson XP earned this lesson (total: ${app.learnProgress.totalXp()} XP)"
        binding.textSummaryStreak.text = "🔥 $newStreak day streak"
    }

    // ---------------------------------------------------------------------
    // Screen management
    // ---------------------------------------------------------------------

    private enum class Screen { UNITS, LESSONS, EXERCISE, SUMMARY }

    private fun setScreen(screen: Screen) {
        if (_binding == null) return
        binding.screenUnits.visibility = if (screen == Screen.UNITS) View.VISIBLE else View.GONE
        binding.screenLessons.visibility = if (screen == Screen.LESSONS) View.VISIBLE else View.GONE
        binding.screenExercise.visibility = if (screen == Screen.EXERCISE) View.VISIBLE else View.GONE
        binding.screenSummary.visibility = if (screen == Screen.SUMMARY) View.VISIBLE else View.GONE
    }

    override fun onPause() {
        super.onPause()
        mainActivity?.app?.mic?.stop()
        mainActivity?.app?.tts?.stop()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mainActivity?.app?.mic?.stop()
        _binding = null
    }
}
