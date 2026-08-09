package com.retroid.translator.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.vosk.Recognizer
import java.io.File

/**
 * A single, shared microphone pipeline used everywhere the app needs the mic:
 * the Translate tab's mic button, Conversations turn-taking, and Practice's
 * "record my attempt". There is exactly ONE [AudioRecord] instance in the
 * whole app, opened for the duration of a capture and closed right after —
 * this sidesteps any "microphone already in use" conflict that would come
 * from running Vosk's own mic handling and a separate MediaRecorder at the
 * same time (a real risk on Android 9 devices, which are stricter about
 * concurrent audio capture than later versions).
 *
 * Each read buffer is optionally (a) fed to a live Vosk [Recognizer] via
 * `acceptWaveForm`, and (b) written to a WAV file — independently, so the
 * same mic tap can simultaneously drive live speech recognition AND save a
 * recording, or do either alone.
 */
class MicPipeline {
    interface Listener {
        fun onPartial(text: String) {}
        fun onFinal(text: String) {}
        fun onRecordingSaved(file: File, bytes: Long) {}
        fun onError(message: String) {}
        fun onListeningStarted() {}
        fun onListeningStopped() {}
    }

    @Volatile private var running = false
    private var thread: Thread? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun isRunning(): Boolean = running

    /**
     * @param recognizer if non-null, live-transcribes and auto-stops on the first
     *   finalized utterance (or after [maxDurationMs] of silence/no speech).
     * @param recordToFile if non-null, also saves raw mic audio as a WAV file here.
     * @param recognizerOwned if true, this call closes [recognizer] when done.
     */
    fun start(
        recognizer: Recognizer?,
        recordToFile: File?,
        listener: Listener,
        sampleRate: Int = 16000,
        maxDurationMs: Long = 15_000L,
        recognizerOwned: Boolean = true
    ) {
        if (running) {
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

        running = true
        mainHandler.post { listener.onListeningStarted() }

        val wavWriter = try {
            recordToFile?.let { WavFileWriter(it, sampleRate) }
        } catch (e: Exception) {
            Log.e(TAG, "Could not open recording file", e)
            null
        }

        thread = Thread({
            val startTime = System.currentTimeMillis()
            var gotFinal = false
            try {
                record.startRecording()
                val buffer = ByteArray(bufSize)
                while (running) {
                    if (System.currentTimeMillis() - startTime > maxDurationMs) {
                        Log.i(TAG, "MicPipeline: max duration reached, stopping")
                        break
                    }
                    val read = record.read(buffer, 0, buffer.size)
                    if (read <= 0) continue

                    wavWriter?.write(buffer, read)

                    if (recognizer != null) {
                        val isFinal = try {
                            recognizer.acceptWaveForm(buffer, read)
                        } catch (e: Exception) {
                            Log.e(TAG, "acceptWaveForm failed", e)
                            false
                        }
                        if (isFinal) {
                            val text = extractField(safeResult { recognizer.result }, "text")
                            if (text.isNotBlank()) {
                                gotFinal = true
                                mainHandler.post { listener.onFinal(text) }
                                break
                            }
                            // Empty final (pure silence) - keep listening until timeout.
                        } else {
                            val partial = extractField(safeResult { recognizer.partialResult }, "partial")
                            if (partial.isNotBlank()) {
                                mainHandler.post { listener.onPartial(partial) }
                            }
                        }
                    }
                }

                if (!gotFinal && recognizer != null) {
                    val text = extractField(safeResult { recognizer.finalResult }, "text")
                    if (text.isNotBlank()) {
                        mainHandler.post { listener.onFinal(text) }
                    } else {
                        mainHandler.post { listener.onError("Didn't catch that — try again") }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "MicPipeline capture failed", e)
                mainHandler.post { listener.onError(e.message ?: "Recording error") }
            } finally {
                try { record.stop() } catch (e: Exception) { /* ignore */ }
                record.release()
                if (recognizer != null && recognizerOwned) {
                    try { recognizer.close() } catch (e: Exception) { /* ignore */ }
                }
                val savedBytes = wavWriter?.bytesWritten() ?: -1L
                try { wavWriter?.close() } catch (e: Exception) { /* ignore */ }
                running = false
                mainHandler.post {
                    listener.onListeningStopped()
                    if (recordToFile != null && savedBytes > 0) {
                        listener.onRecordingSaved(recordToFile, savedBytes)
                    }
                }
            }
        }, "MicPipeline")
        thread?.start()
    }

    fun stop() {
        running = false
    }

    private inline fun safeResult(block: () -> String?): String = try {
        block() ?: ""
    } catch (e: Exception) {
        ""
    }

    private fun extractField(json: String, field: String): String {
        if (json.isBlank()) return ""
        return try {
            JSONObject(json).optString(field, "")
        } catch (e: Exception) {
            ""
        }
    }

    companion object {
        private const val TAG = "MicPipeline"
    }
}
