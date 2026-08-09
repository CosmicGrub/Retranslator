package com.retroid.translator.audio

import java.io.File
import java.io.RandomAccessFile

/**
 * Minimal streaming writer for 16-bit PCM mono WAV files. Writes a
 * placeholder 44-byte header, appends raw PCM as it arrives, and patches the
 * header's size fields on [close].
 */
class WavFileWriter(private val file: File, private val sampleRate: Int) {
    private val raf = RandomAccessFile(file, "rw")
    private var dataBytesWritten = 0L

    init {
        raf.setLength(0)
        raf.seek(0)
        raf.write(ByteArray(44))
    }

    @Synchronized
    fun write(buffer: ByteArray, length: Int) {
        raf.seek(44L + dataBytesWritten)
        raf.write(buffer, 0, length)
        dataBytesWritten += length
    }

    @Synchronized
    fun close() {
        patchHeader()
        raf.close()
    }

    fun bytesWritten(): Long = dataBytesWritten

    private fun patchHeader() {
        val byteRate = sampleRate * 2 // mono, 16-bit
        val blockAlign = 2
        val chunkSize = 36 + dataBytesWritten
        raf.seek(0)
        raf.write("RIFF".toByteArray(Charsets.US_ASCII))
        raf.write(le32(chunkSize.toInt()))
        raf.write("WAVE".toByteArray(Charsets.US_ASCII))
        raf.write("fmt ".toByteArray(Charsets.US_ASCII))
        raf.write(le32(16))
        raf.write(le16(1))              // PCM
        raf.write(le16(1))              // mono
        raf.write(le32(sampleRate))
        raf.write(le32(byteRate))
        raf.write(le16(blockAlign))
        raf.write(le16(16))             // bits per sample
        raf.write("data".toByteArray(Charsets.US_ASCII))
        raf.write(le32(dataBytesWritten.toInt()))
    }

    private fun le32(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(),
        ((v shr 24) and 0xFF).toByte()
    )

    private fun le16(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte()
    )
}
