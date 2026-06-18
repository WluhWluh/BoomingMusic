package com.mardous.booming.separation.audio

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

class WavFileWriter(
    private val file: File,
    private val sampleRate: Int,
    private val channelCount: Int,
    private val declaredDataSizeBytes: Long? = null,
    private val preserveExistingData: Boolean = false,
) : Closeable {
    private val output = RandomAccessFile(file, "rw")
    private var dataSize = 0L

    init {
        require(sampleRate > 0) { "Sample rate must be positive." }
        require(channelCount > 0) { "Channel count must be positive." }
        val initialDataSize = declaredDataSizeBytes ?: 0L
        if (!preserveExistingData || !file.isFile) {
            output.setLength(0)
        }
        writeHeader(initialDataSize)
        if (declaredDataSizeBytes != null) {
            val declaredLength = HEADER_SIZE + initialDataSize
            if (!preserveExistingData || output.length() < declaredLength) {
                output.setLength(declaredLength)
            }
        }
    }

    fun writePcm16(bytes: ByteArray) {
        output.seek(HEADER_SIZE + dataSize)
        output.write(bytes)
        dataSize += bytes.size
    }

    fun writePcm16AtFrame(frameOffset: Int, bytes: ByteArray) {
        require(declaredDataSizeBytes != null) {
            "Random-access WAV writes require a declared output size."
        }
        require(frameOffset >= 0) { "Frame offset must be non-negative." }
        val byteOffset = frameOffset.toLong() * channelCount * BYTES_PER_SAMPLE
        val endOffset = byteOffset + bytes.size
        require(endOffset <= declaredDataSizeBytes) {
            "WAV data exceeds declared output size."
        }
        output.seek(HEADER_SIZE + byteOffset)
        output.write(bytes)
        dataSize = maxOf(dataSize, endOffset)
    }

    override fun close() {
        output.seek(0)
        val finalDataSize = declaredDataSizeBytes ?: dataSize
        require(dataSize <= finalDataSize) {
            "WAV data exceeds declared output size."
        }
        writeHeader(finalDataSize)
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
