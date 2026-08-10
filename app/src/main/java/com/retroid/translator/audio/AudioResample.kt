package com.retroid.translator.audio

/**
 * Simple linear-interpolation resampler for mono 16-bit PCM.
 *
 * Extracted from [com.retroid.translator.prototype.TestAudioSynth] (which
 * originally used this only to bring eSpeak's native sample rate down to
 * Vosk's expected 16kHz) so the same resampling code path is shared by real
 * recorded human-speech corpus clips too
 * ([com.retroid.translator.prototype.RealSpeechCorpus] downsamples 48kHz
 * source WAVs the same way) — one resampler implementation, same rounding/
 * interpolation behavior, regardless of whether the source audio is
 * synthetic TTS or a real recording.
 *
 * Adequate for offline test-audio prep, not a mastering-grade resampler (no
 * anti-aliasing filter) — fine for feeding Vosk, which itself expects noisy
 * real-world input.
 */
object AudioResample {
    fun resampleS16Mono(src: ByteArray, srcRate: Int, dstRate: Int): ByteArray {
        if (srcRate == dstRate) return src
        val srcSamples = src.size / 2
        if (srcSamples < 2) return ByteArray(0)
        val dstSamples = ((srcSamples.toLong() * dstRate) / srcRate).toInt()
        val out = ByteArray(dstSamples * 2)
        val ratio = srcRate.toDouble() / dstRate.toDouble()
        for (i in 0 until dstSamples) {
            val srcPos = i * ratio
            val idx0 = srcPos.toInt().coerceIn(0, srcSamples - 1)
            val idx1 = (idx0 + 1).coerceAtMost(srcSamples - 1)
            val frac = srcPos - idx0
            val s0 = readS16LE(src, idx0)
            val s1 = readS16LE(src, idx1)
            val interpolated = (s0 + (s1 - s0) * frac).toInt().coerceIn(-32768, 32767)
            writeS16LE(out, i, interpolated)
        }
        return out
    }

    private fun readS16LE(buf: ByteArray, idx: Int): Int {
        val lo = buf[idx * 2].toInt() and 0xFF
        val hi = buf[idx * 2 + 1].toInt() // sign-extended
        return (hi shl 8) or lo
    }

    private fun writeS16LE(buf: ByteArray, idx: Int, value: Int) {
        buf[idx * 2] = value.toByte()
        buf[idx * 2 + 1] = (value shr 8).toByte()
    }
}
