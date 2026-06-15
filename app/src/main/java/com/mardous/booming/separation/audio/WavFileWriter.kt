package com.mardous.booming.separation.audio

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

class WavFileWriter(
    file: File,
    private val sampleRate: Int,
    private val channelCount: Int,
) : Closeable {
    private val output = RandomAccessFile(file, "rw")
    private var dataSize = 0L

    init {
        require(sampleRate > 0) { "Sample rate must be positive." }
        require(channelCount > 0) { "Channel count must be positive." }
        output.setLength(0)
        writeHeader(0L)
    }

    fun writePcm16(bytes: ByteArray) {
        output.seek(HEADER_SIZE + dataSize)
        output.write(bytes)
        dataSize += bytes.size
    }

    override fun close() {
        output.seek(0)
        writeHeader(dataSize)
        output.close()
    }

    private fun writeHeader(pcmDataSize: Long) {
        require(pcmDataSize <= UInt.MAX_VALUE.toLong()) {
            "WAV output larger than 4 GiB is not supported yet."
        }

        val byteRate = sampleRate * channelCount * BYTES_PER_SAMPLE
        val blockAlign = channelCount * BYTES_PER_SAMPLE
        val riffSize = HEADER_SIZE - 8 + pcmDataSize

        output.writeAscii("RIFF")
        output.writeLittleEndianInt(riffSize.toInt())
        output.writeAscii("WAVE")
        output.writeAscii("fmt ")
        output.writeLittleEndianInt(16)
        output.writeLittleEndianShort(1)
        output.writeLittleEndianShort(channelCount)
        output.writeLittleEndianInt(sampleRate)
        output.writeLittleEndianInt(byteRate)
        output.writeLittleEndianShort(blockAlign)
        output.writeLittleEndianShort(16)
        output.writeAscii("data")
        output.writeLittleEndianInt(pcmDataSize.toInt())
    }

    private fun RandomAccessFile.writeAscii(value: String) {
        write(value.toByteArray(Charsets.US_ASCII))
    }

    private fun RandomAccessFile.writeLittleEndianInt(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 24) and 0xFF)
    }

    private fun RandomAccessFile.writeLittleEndianShort(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
    }

    private companion object {
        const val HEADER_SIZE = 44L
        const val BYTES_PER_SAMPLE = 2
    }
}
