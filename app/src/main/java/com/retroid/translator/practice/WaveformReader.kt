package com.retroid.translator.practice

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.max

/**
 * Reads a REAL peak-amplitude envelope directly from a 16-bit PCM mono WAV
 * file written by [com.retroid.translator.audio.WavFileWriter] (44-byte
 * header, then raw little-endian PCM16 samples) - used to draw the
 * "drill_deck" cover variant's recent-attempts waveform-thumbnail strip and
 * the "waveform_wall" Flex variant's past-attempt mini-waveforms from the
 * user's own actual recorded audio, not a fabricated/placeholder shape.
 *
 * New, dedicated file - does not touch [com.retroid.translator.audio.WavFileWriter]
 * or any other shared audio file. Read-only; never modifies the file.
 *
 * Deliberately NOT used for a "reference" (TTS) waveform anywhere in this
 * tab's variants: `EspeakEngine`/`PiperTtsEngine` synthesize straight to a
 * live `AudioTrack`/`MediaPlayer`, with no capture-to-file path exposed
 * (see `docs/specs/fold5-adaptation.md` §4's own note that a *separate*
 * throwaway `SpeechSynthesis` instance was needed to capture eSpeak output
 * for the dual-recognizer prototype - the shipped `EspeakEngine` has no such
 * path). Rather than fabricate a reference waveform shape with no real audio
 * behind it, [com.retroid.translator.ui.PracticeFragment]'s "waveform_wall"
 * variant renders that row as a plainly-decorative static pattern (see its
 * doc comment) and reserves this real reader for actual recorded files only.
 */
object WaveformReader {
    private const val HEADER_BYTES = 44

    /**
     * Real clip duration in seconds, read directly from the WAV header this
     * project's own [com.retroid.translator.audio.WavFileWriter] writes (byte
     * offset 24: 4-byte little-endian sample rate; offset 40: 4-byte
     * little-endian data-chunk size; 16-bit mono, so 2 bytes/sample) - not
     * inferred from file length against an assumed constant sample rate,
     * since MicPipeline/Practice recordings and other capture paths are not
     * guaranteed to all share one rate. Returns 0f (never throws) on any
     * missing/short/unreadable file, same failure contract as [readPeaks].
     */
    fun durationSeconds(file: File): Float = try {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < HEADER_BYTES) return 0f
            val header = ByteArray(HEADER_BYTES)
            raf.seek(0)
            raf.readFully(header)
            fun le32(offset: Int): Long =
                (header[offset].toLong() and 0xFF) or
                    ((header[offset + 1].toLong() and 0xFF) shl 8) or
                    ((header[offset + 2].toLong() and 0xFF) shl 16) or
                    ((header[offset + 3].toLong() and 0xFF) shl 24)
            val sampleRate = le32(24)
            val dataBytes = le32(40)
            if (sampleRate <= 0) return 0f
            (dataBytes / 2f) / sampleRate.toFloat() // 16-bit mono: 2 bytes/sample
        }
    } catch (e: Exception) {
        0f
    }

    /**
     * [bucketCount] peak amplitudes, each normalized 0f..1f against the
     * loudest bucket in this same file (not a global scale - two different
     * files' bars are not directly comparable in absolute loudness, only in
     * shape, which is all a small thumbnail needs to show). Returns an
     * all-zero list (never throws) if the file is missing, empty, or
     * unreadable - callers render that as flat/silent bars.
     */
    fun readPeaks(file: File, bucketCount: Int = 20): List<Float> {
        if (bucketCount <= 0) return emptyList()
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val dataBytes = (raf.length() - HEADER_BYTES).coerceAtLeast(0)
                val sampleCount = (dataBytes / 2).toInt()
                if (sampleCount <= 0) return List(bucketCount) { 0f }
                val samplesPerBucket = max(1, sampleCount / bucketCount)
                raf.seek(HEADER_BYTES.toLong())
                val buf = ByteArray(samplesPerBucket * 2)
                val peaks = FloatArray(bucketCount)
                var maxPeak = 1
                for (bucket in 0 until bucketCount) {
                    val read = raf.read(buf)
                    if (read <= 0) break
                    var peak = 0
                    var i = 0
                    while (i + 1 < read) {
                        val lo = buf[i].toInt() and 0xFF
                        val hi = buf[i + 1].toInt() // signed high byte
                        val sample = (hi shl 8) or lo
                        val magnitude = abs(sample)
                        if (magnitude > peak) peak = magnitude
                        i += 2
                    }
                    peaks[bucket] = peak.toFloat()
                    if (peak > maxPeak) maxPeak = peak
                }
                peaks.map { (it / maxPeak.toFloat()).coerceIn(0.03f, 1f) }
            }
        } catch (e: Exception) {
            List(bucketCount) { 0f }
        }
    }
}
