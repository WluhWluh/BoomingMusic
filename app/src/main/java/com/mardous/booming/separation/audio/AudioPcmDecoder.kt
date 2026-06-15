package com.mardous.booming.separation.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

class AudioPcmDecoder(private val context: Context) {
    fun decode(
        uri: Uri,
        shouldCancel: () -> Boolean = { false },
    ): DecodedPcmAudio {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var codecStarted = false

        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = findAudioTrack(extractor)
            if (trackIndex < 0) error("No audio track was found.")

            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: error("Audio track has no MIME type.")

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()
            codecStarted = true

            val bufferInfo = MediaCodec.BufferInfo()
            val output = ByteArrayOutputStream()
            var inputEnded = false
            var outputEnded = false
            var outputFormat = codec.outputFormat
            var writerFormat: AudioOutputFormat? = null

            while (!outputEnded) {
                throwIfCanceled(shouldCancel)
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                            ?: error("Decoder returned a null input buffer.")
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                sampleSize,
                                extractor.sampleTime,
                                0,
                            )
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        outputFormat = codec.outputFormat
                        if (writerFormat != null) error("Decoder output format changed after writing began.")
                    }
                    else -> {
                        if (outputIndex >= 0) {
                            val outputBuffer = codec.getOutputBuffer(outputIndex)
                            if (bufferInfo.size > 0 && outputBuffer != null) {
                                val activeFormat = writerFormat ?: audioOutputFormat(outputFormat).also {
                                    writerFormat = it
                                }
                                writePcmAs16Bit(output, outputBuffer, bufferInfo, activeFormat.encoding)
                            }
                            outputEnded = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            codec.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
            }

            val format = writerFormat ?: error("Decoder produced no PCM output.")
            return DecodedPcmAudio(
                sampleRate = format.sampleRate,
                channelCount = format.channelCount,
                pcm16 = output.toByteArray(),
            )
        } finally {
            if (codecStarted) {
                codec?.stop()
            }
            codec?.release()
            extractor.release()
        }
    }

    private fun throwIfCanceled(shouldCancel: () -> Boolean) {
        if (shouldCancel()) {
            throw CancellationException("Source separation canceled.")
        }
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int {
        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return index
        }
        return -1
    }

    private fun audioOutputFormat(format: MediaFormat): AudioOutputFormat {
        val sampleRate = format.optionalInteger(MediaFormat.KEY_SAMPLE_RATE)
            ?: error("Decoder output has no sample rate.")
        val channelCount = format.optionalInteger(MediaFormat.KEY_CHANNEL_COUNT)
            ?: error("Decoder output has no channel count.")
        val encoding = format.optionalInteger(MediaFormat.KEY_PCM_ENCODING)
            ?: AudioFormat.ENCODING_PCM_16BIT
        return AudioOutputFormat(sampleRate, channelCount, encoding)
    }

    private fun writePcmAs16Bit(
        output: ByteArrayOutputStream,
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        encoding: Int,
    ) {
        val duplicate = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        duplicate.position(info.offset)
        duplicate.limit(info.offset + info.size)

        when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> {
                val bytes = ByteArray(duplicate.remaining())
                duplicate.get(bytes)
                output.write(bytes)
            }
            AudioFormat.ENCODING_PCM_FLOAT -> {
                while (duplicate.remaining() >= Float.SIZE_BYTES) {
                    val sample = duplicate.float.coerceIn(-1f, 1f)
                    val value = (sample * Short.MAX_VALUE).roundToInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    output.write(value and 0xFF)
                    output.write((value ushr 8) and 0xFF)
                }
            }
            AudioFormat.ENCODING_PCM_8BIT -> {
                while (duplicate.hasRemaining()) {
                    val unsigned = duplicate.get().toInt() and 0xFF
                    val value = (unsigned - 128) shl 8
                    output.write(value and 0xFF)
                    output.write((value ushr 8) and 0xFF)
                }
            }
            else -> error("Unsupported decoder PCM encoding: $encoding")
        }
    }

    private fun MediaFormat.optionalInteger(key: String): Int? {
        return if (containsKey(key)) getInteger(key) else null
    }

    private data class AudioOutputFormat(
        val sampleRate: Int,
        val channelCount: Int,
        val encoding: Int,
    )

    private companion object {
        const val TIMEOUT_US = 10_000L
    }
}
