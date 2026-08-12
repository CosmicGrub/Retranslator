package com.retroid.translator.wear.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.math.sqrt

/**
 * Wear port of the phone app's [com.retroid.translator.audio.MicPipeline] -
 * **continuous/VAD-triggered listening only**. This is a deliberate,
 * disclosed narrowing, not an oversight: the phone class also has a
 * separate tap-to-talk [android.media.AudioRecord] capture path
 * (`start()`), used by Translate's mic button and Practice's recordings.
 * This pass's task brief specifically calls for "auto-listening like
 * Shazam/AI assistants - continuous/ambient listening UX, not tap-to-talk"
 * for the watch, so only that half was ported. Porting tap-to-talk too
 * would be a small, mechanical follow-up if a future pass wants it (e.g.
 * for a Practice-equivalent watch feature).
 *
 * The VAD algorithm itself (adaptive-noise-floor RMS gate, pre-roll buffer,
 * silence-timeout endpointing) is copied verbatim from the phone version,
 * including its tuning constants - those were derived from real recorded
 * human speech (docs/specs/fold5-adaptation.md §4's real_speech_corpus
 * measurements), which is hardware-independent (an RMS envelope shape
 * doesn't change with the chip decoding it), so re-deriving them for the
 * watch was not necessary. What IS watch-specific and UNVERIFIED: the
 * watch's own mic hardware/gain characteristics and real ambient noise in
 * an actual worn/outdoor context - this was not (and could not be, without
 * a live speaker) tested this pass; see spec's honest-gaps section.
 */
class MicPipeline {
    interface ContinuousListener {
        fun onListeningStarted() {}
        fun onListeningStopped() {}
        fun onSpeechStart() {}
        fun onAudioChunk(buffer: ByteArray, length: Int) {}
        fun onSpeechEnd() {}
        fun onError(message: String) {}
    }

    @Volatile private var continuousRunning = false
    private var continuousThread: Thread? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun isRunning(): Boolean = continuousRunning

    fun startContinuousListening(
        listener: ContinuousListener,
        sampleRate: Int = 16000,
        silenceTimeoutMs: Long = 900L,
        maxUtteranceMs: Long = 12_000L,
        speechTriggerChunks: Int = 2
    ) {
        if (continuousRunning) {
            listener.onError("Already listening")
            return
        }
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            listener.onError("Microphone is not available on this device")
            return
        }
        val bufSize = minBuf.coerceAtLeast(4096)

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize * 2
            )
        } catch (e: SecurityException) {
            listener.onError("Microphone permission not granted")
            return
        } catch (e: Exception) {
            listener.onError("Could not open microphone: ${e.message}")
            return
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            listener.onError("Microphone failed to initialize")
            record.release()
            return
        }

        continuousRunning = true
        mainHandler.post { listener.onListeningStarted() }

        continuousThread = Thread({
            var state = VadState.IDLE
            var noiseFloorRms = -1.0
            var aboveThresholdStreak = 0
            var silenceStartMs = 0L
            var utteranceStartMs = 0L
            val preRoll = ArrayDeque<Pair<ByteArray, Int>>()
            val preRollCapacity = VAD_PREROLL_CHUNKS + speechTriggerChunks

            try {
                record.startRecording()
                val buffer = ByteArray(bufSize)
                while (continuousRunning) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read <= 0) continue
                    val now = System.currentTimeMillis()
                    val rms = computeRms(buffer, read)

                    when (state) {
                        VadState.IDLE -> {
                            if (noiseFloorRms < 0.0) noiseFloorRms = rms
                            val threshold = maxOf(MIN_ABSOLUTE_FLOOR, noiseFloorRms * TRIGGER_MULTIPLIER)

                            if (preRoll.size >= preRollCapacity) preRoll.removeFirst()
                            preRoll.addLast(buffer.copyOf(read) to read)

                            if (rms > threshold) {
                                aboveThresholdStreak++
                            } else {
                                aboveThresholdStreak = 0
                                noiseFloorRms = noiseFloorRms * (1 - NOISE_FLOOR_EMA_ALPHA) + rms * NOISE_FLOOR_EMA_ALPHA
                            }
                            if (aboveThresholdStreak >= speechTriggerChunks) {
                                state = VadState.SPEECH
                                utteranceStartMs = now
                                silenceStartMs = 0L
                                aboveThresholdStreak = 0
                                listener.onSpeechStart()
                                for ((chunk, len) in preRoll) listener.onAudioChunk(chunk, len)
                                preRoll.clear()
                            }
                        }
                        VadState.SPEECH -> {
                            listener.onAudioChunk(buffer, read)
                            val threshold = maxOf(MIN_ABSOLUTE_FLOOR, noiseFloorRms * TRIGGER_MULTIPLIER)
                            if (rms > threshold) {
                                silenceStartMs = 0L
                            } else if (silenceStartMs == 0L) {
                                silenceStartMs = now
                            }
                            val silenceElapsed = if (silenceStartMs > 0L) now - silenceStartMs else 0L
                            val utteranceElapsed = now - utteranceStartMs
                            if (silenceElapsed >= silenceTimeoutMs || utteranceElapsed >= maxUtteranceMs) {
                                state = VadState.IDLE
                                silenceStartMs = 0L
                                listener.onSpeechEnd()
                            }
                        }
                    }
                }
                if (state == VadState.SPEECH) {
                    listener.onSpeechEnd()
                }
            } catch (e: Exception) {
                Log.e(TAG, "MicPipeline continuous capture failed", e)
                mainHandler.post { listener.onError(e.message ?: "Recording error") }
            } finally {
                try { record.stop() } catch (e: Exception) { /* ignore */ }
                record.release()
                continuousRunning = false
                mainHandler.post { listener.onListeningStopped() }
            }
        }, "WearMicPipeline-Continuous")
        continuousThread?.start()
    }

    fun stop() {
        continuousRunning = false
    }

    private enum class VadState { IDLE, SPEECH }

    private fun computeRms(buffer: ByteArray, length: Int): Double {
        if (length < 2) return 0.0
        var sum = 0.0
        var i = 0
        var n = 0
        while (i + 1 < length) {
            val lo = buffer[i].toInt() and 0xFF
            val hi = buffer[i + 1].toInt()
            val sample = (hi shl 8) or lo
            sum += (sample * sample).toDouble()
            i += 2
            n++
        }
        return if (n > 0) sqrt(sum / n) else 0.0
    }

    companion object {
        private const val TAG = "WearMicPipeline"
        private const val MIN_ABSOLUTE_FLOOR = 80.0
        private const val TRIGGER_MULTIPLIER = 4.0
        private const val NOISE_FLOOR_EMA_ALPHA = 0.05
        private const val VAD_PREROLL_CHUNKS = 2
    }
}
