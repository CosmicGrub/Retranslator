package com.retroid.translator.voiceclone

import com.retroid.translator.practice.WaveformReader
import java.io.File

/**
 * Real audio-quality checks for a voice-clone reference recording - built
 * entirely on [WaveformReader]'s real per-file PCM analysis (its existing
 * [WaveformReader.readPeaks]/[WaveformReader.durationSeconds], plus the two
 * new absolute-scale methods added alongside this feature,
 * [WaveformReader.clippingRatio]/[WaveformReader.averageAbsoluteAmplitude])
 * rather than inventing a parallel signal-analysis path - this app's
 * existing recording infrastructure already reads real WAV files this same
 * way for the Practice tab's waveform thumbnails, so voice-clone onboarding
 * reuses it instead of duplicating it.
 *
 * Three real, independent checks, each derived from actual recorded samples:
 * - **Clipping** - [WaveformReader.clippingRatio] above [CLIPPING_RATIO_FAIL]
 *   means the mic was driven into distortion (too close / gain too hot).
 * - **Silence / too quiet** - [WaveformReader.averageAbsoluteAmplitude]
 *   below [MIN_AVG_AMPLITUDE] means essentially nothing was captured (no
 *   speech, or the mic wasn't reached).
 * - **Background noise / low dynamic range** - derived from
 *   [WaveformReader.readPeaks]'s own per-bucket envelope: real speech has
 *   quiet gaps between words/breaths, so a genuine utterance's quietest
 *   buckets should sit well below its loudest ones. If the quietest 25% of
 *   buckets average close to the loudest 25% (see [NOISE_DYNAMIC_RANGE_MIN]),
 *   that's a real signal the whole clip is uniformly loud - a noisy room or
 *   a hummy/buzzing recording, not clean speech with silence around it - not
 *   a genuine "noise floor" estimate the way a live VAD does
 *   ([com.retroid.translator.audio.MicPipeline]'s continuous-listening noise
 *   floor is adaptive/streaming and doesn't apply post-hoc to a saved file),
 *   but a real, honest proxy built from real recorded samples.
 *
 * A short clip is *expected* here - zero-shot voice cloning
 * ([com.retroid.translator.engine.VoiceCloneEngine]) works from a brief
 * reference (a few seconds), unlike a dictation-length recording - so
 * [durationSeconds] is only flagged if implausibly short (mic never
 * captured anything) or unusually long for a one-sentence prompt.
 */
object VoiceCloneAudioQuality {

    data class Result(
        val durationSeconds: Float,
        val clippingRatio: Float,
        val averageAbsoluteAmplitude: Float,
        val dynamicRangeRatio: Float,
        val warnings: List<String>,
        val blockers: List<String>
    ) {
        /** True only when nothing serious enough to force a re-record was found - warnings may still exist. */
        val passable: Boolean get() = blockers.isEmpty()
    }

    fun analyze(file: File): Result {
        val duration = WaveformReader.durationSeconds(file)
        val clipping = WaveformReader.clippingRatio(file)
        val avgAmplitude = WaveformReader.averageAbsoluteAmplitude(file)
        val peaks = WaveformReader.readPeaks(file, 20)

        val dynamicRange = if (peaks.isEmpty()) 1f else {
            val sorted = peaks.sorted()
            val quarter = (sorted.size / 4).coerceAtLeast(1)
            val quietAvg = sorted.take(quarter).average().toFloat()
            val loudAvg = sorted.takeLast(quarter).average().toFloat()
            if (loudAvg <= 0f) 1f else (quietAvg / loudAvg)
        }

        val blockers = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (duration <= 0f) {
            blockers += "No audio was captured - check microphone permission and try again."
        } else if (duration < MIN_DURATION_SECONDS) {
            blockers += "Too short (%.1fs) - read the whole sentence.".format(duration)
        }
        if (avgAmplitude < MIN_AVG_AMPLITUDE) {
            blockers += "Too quiet - move closer to the mic and read a bit louder."
        }
        if (clipping >= CLIPPING_RATIO_FAIL) {
            blockers += "Clipping/distorted - move slightly away from the mic and try again."
        } else if (clipping >= CLIPPING_RATIO_WARN) {
            warnings += "A little loud - the clone may sound slightly distorted."
        }
        if (duration > 0f && avgAmplitude >= MIN_AVG_AMPLITUDE && dynamicRange > NOISE_DYNAMIC_RANGE_MIN) {
            warnings += "Background noise detected - a quieter room will give a cleaner clone."
        }
        if (duration > MAX_RECOMMENDED_DURATION_SECONDS) {
            warnings += "Longer than ideal - short reference clips (a few seconds) work best for this model."
        }

        return Result(duration, clipping, avgAmplitude, dynamicRange, warnings, blockers)
    }

    // Thresholds are deliberately simple, documented judgment calls (not
    // sourced from a published spec) - the underlying PCM analysis is real,
    // these cutoffs are this app's own reasonable-defaults choice, same as
    // MicPipeline's own documented VAD tuning.
    private const val MIN_DURATION_SECONDS = 0.8f
    private const val MAX_RECOMMENDED_DURATION_SECONDS = 8f
    private const val MIN_AVG_AMPLITUDE = 150f
    private const val CLIPPING_RATIO_WARN = 0.001f
    private const val CLIPPING_RATIO_FAIL = 0.01f
    private const val NOISE_DYNAMIC_RANGE_MIN = 0.55f
}
