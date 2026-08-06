package com.mardous.booming.separation.audio

import java.io.File
import java.io.RandomAccessFile

internal data class Pcm16WavFileInfo(
    val dataOffset: Long,
    val dataSize: Long,
    val sampleRate: Int,
    val channelCount: Int,
    val frameCount: Long,
)

/** Parses the PCM geometry used by application-generated separated-stem WAV files. */
internal object Pcm16WavFileReader {
    fun read(file: File): Pcm16WavFileInfo {
        require(file.isFile) { "WAV file does not exist: ${file.absolutePath}" }
        RandomAccessFile(file, "r").use { input ->
            require(input.length() >= RIFF_HEADER_BYTES) { "WAV file is too short." }
            require(input.readAscii(4) == "RIFF") { "WAV file has no RIFF header." }
            val riffSize = input.readLittleEndianUInt()
            val riffEnd = checkedAdd(8L, riffSize, "WAV RIFF size overflows.")
            require(riffEnd >= RIFF_HEADER_BYTES && riffEnd <= input.length()) {
                "WAV RIFF chunk exceeds the file boundary."
            }
            require(input.readAscii(4) == "WAVE") { "WAV file has no WAVE header." }

            var format: FormatChunk? = null
            var dataOffset: Long? = null
            var dataSize: Long? = null
            while (input.filePointer + CHUNK_HEADER_BYTES <= riffEnd) {
                val chunkId = input.readAscii(4)
                val chunkSize = input.readLittleEndianUInt()
                val chunkDataStart = input.filePointer
                val chunkDataEnd = checkedAdd(
                    chunkDataStart,
                    chunkSize,
                    "WAV chunk size overflows.",
                )
                val paddedChunkEnd = checkedAdd(
                    chunkDataEnd,
                    chunkSize and 1L,
                    "WAV padded chunk size overflows.",
                )
                require(paddedChunkEnd <= riffEnd && chunkDataEnd <= input.length()) {
                    "WAV chunk exceeds the RIFF boundary."
                }
                when (chunkId) {
                    "fmt " -> {
                        require(format == null) { "WAV file has duplicate fmt chunks." }
                        require(chunkSize >= PCM_FMT_CHUNK_MIN_BYTES) {
                            "WAV fmt chunk is too small."
                        }
                        format = FormatChunk(
                            audioFormat = input.readLittleEndianUShort(),
                            channelCount = input.readLittleEndianUShort(),
                            sampleRate = input.readLittleEndianUInt(),
                            byteRate = input.readLittleEndianUInt(),
                            blockAlign = input.readLittleEndianUShort(),
                            bitsPerSample = input.readLittleEndianUShort(),
                        )
                    }

                    "data" -> {
                        require(dataOffset == null) { "WAV file has duplicate data chunks." }
                        dataOffset = chunkDataStart
                        dataSize = chunkSize
                    }
                }
                input.seek(paddedChunkEnd)
            }

            val resolvedFormat = requireNotNull(format) { "WAV file has no fmt chunk." }
            val resolvedDataOffset = requireNotNull(dataOffset) {
                "WAV file has no data chunk."
            }
            val resolvedDataSize = requireNotNull(dataSize) { "WAV file has no data size." }
            require(resolvedFormat.audioFormat == WAV_FORMAT_PCM) {
                "Only PCM WAV input is supported."
            }
            require(resolvedFormat.channelCount > 0) { "WAV channel count is invalid." }
            require(resolvedFormat.sampleRate in 1L..Int.MAX_VALUE.toLong()) {
                "WAV sample rate is invalid."
            }
            require(resolvedFormat.bitsPerSample == BITS_PER_SAMPLE) {
                "Only 16-bit WAV input is supported."
            }
            val bytesPerFrame = resolvedFormat.channelCount * BYTES_PER_SAMPLE
            require(resolvedFormat.blockAlign == bytesPerFrame) {
                "WAV block alignment is inconsistent."
            }
            require(
                resolvedFormat.byteRate == resolvedFormat.sampleRate * bytesPerFrame,
            ) { "WAV byte rate is inconsistent." }
            require(resolvedDataSize % bytesPerFrame == 0L) {
                "WAV data size does not align to complete PCM16 frames."
            }
            require(resolvedDataOffset + resolvedDataSize <= input.length()) {
                "WAV data exceeds the file boundary."
            }
            return Pcm16WavFileInfo(
                dataOffset = resolvedDataOffset,
                dataSize = resolvedDataSize,
                sampleRate = resolvedFormat.sampleRate.toInt(),
                channelCount = resolvedFormat.channelCount,
                frameCount = resolvedDataSize / bytesPerFrame,
            )
        }
    }

    private fun checkedAdd(first: Long, second: Long, message: String): Long {
        require(first >= 0L && second >= 0L && first <= Long.MAX_VALUE - second) { message }
        return first + second
    }

    private fun RandomAccessFile.readAscii(length: Int): String {
        val bytes = ByteArray(length)
        readFully(bytes)
        return bytes.toString(Charsets.US_ASCII)
    }

    private fun RandomAccessFile.readLittleEndianUShort(): Int {
        val low = read()
        val high = read()
        require(low >= 0 && high >= 0) { "Unexpected end of WAV file." }
        return low or (high shl 8)
    }

    private fun RandomAccessFile.readLittleEndianUInt(): Long {
        val first = read()
        val second = read()
        val third = read()
        val fourth = read()
        require(first >= 0 && second >= 0 && third >= 0 && fourth >= 0) {
            "Unexpected end of WAV file."
        }
        return first.toLong() or
                (second.toLong() shl 8) or
                (third.toLong() shl 16) or
                (fourth.toLong() shl 24)
    }

    private data class FormatChunk(
        val audioFormat: Int,
        val channelCount: Int,
        val sampleRate: Long,
        val byteRate: Long,
        val blockAlign: Int,
        val bitsPerSample: Int,
    )

    private const val RIFF_HEADER_BYTES = 12L
    private const val CHUNK_HEADER_BYTES = 8L
    private const val PCM_FMT_CHUNK_MIN_BYTES = 16L
    private const val WAV_FORMAT_PCM = 1
    private const val BITS_PER_SAMPLE = 16
    private const val BYTES_PER_SAMPLE = 2
}
