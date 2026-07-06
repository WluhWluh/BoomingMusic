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
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlin.math.roundToLong

class AudioPcmDecoder(private val context: Context) {
    fun inspect(uri: Uri): AudioSourceInfo {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = findAudioTrack(extractor)
            if (trackIndex < 0) error("No audio track was found.")

            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: error("Audio track has no MIME type.")
            val sampleRate = format.optionalInteger(MediaFormat.KEY_SAMPLE_RATE)
                ?: error("Audio track has no sample rate.")
            val channelCount = format.optionalInteger(MediaFormat.KEY_CHANNEL_COUNT)
                ?: error("Audio track has no channel count.")
            val durationUs = format.optionalLong(MediaFormat.KEY_DURATION)
                ?.takeIf { it > 0L }
            return AudioSourceInfo(
                mimeType = mime,
                sampleRate = sampleRate,
                channelCount = channelCount,
                durationUs = durationUs,
                trackMetadata = audioTrackMetadata(format, mime),
            )
        } finally {
            extractor.release()
        }
    }

    fun hashEncodedAudioSamples(
        uri: Uri,
        shouldCancel: () -> Boolean = { false },
    ): EncodedAudioSamplesHash {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = findAudioTrack(extractor)
            if (trackIndex < 0) error("No audio track was found.")

            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: error("Audio track has no MIME type.")
            val sampleRate = format.optionalInteger(MediaFormat.KEY_SAMPLE_RATE)
                ?: error("Audio track has no sample rate.")
            val channelCount = format.optionalInteger(MediaFormat.KEY_CHANNEL_COUNT)
                ?: error("Audio track has no channel count.")
            val durationUs = format.optionalLong(MediaFormat.KEY_DURATION) ?: -1L
            val trackMetadata = audioTrackMetadata(format, mime)
            val digest = MessageDigest.getInstance("SHA-256")
            digest.updateString("booming-encoded-audio-samples-v1")
            digest.updateString(mime)
            digest.updateInt(sampleRate)
            digest.updateInt(channelCount)
            digest.updateLong(durationUs)
            digest.updateInt(trackMetadata.encoderDelayFrames ?: -1)
            digest.updateInt(trackMetadata.encoderPaddingFrames ?: -1)

            var buffer = ByteBuffer.allocateDirect(
                format.optionalInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                    ?.coerceAtLeast(DEFAULT_SAMPLE_HASH_BUFFER_BYTES)
                    ?.coerceAtMost(MAX_SAMPLE_HASH_BUFFER_BYTES)
                    ?: DEFAULT_SAMPLE_HASH_BUFFER_BYTES
            )
            val scratch = ByteArray(SAMPLE_HASH_CHUNK_BYTES)
            var sampleCount = 0L
            var byteCount = 0L

            while (true) {
                throwIfCanceled(shouldCancel)
                buffer.clear()
                var sampleSize = extractor.readSampleData(buffer, 0)
                while (sampleSize == buffer.capacity() && buffer.capacity() < MAX_SAMPLE_HASH_BUFFER_BYTES) {
                    buffer = ByteBuffer.allocateDirect(
                        (buffer.capacity() * 2).coerceAtMost(MAX_SAMPLE_HASH_BUFFER_BYTES)
                    )
                    buffer.clear()
                    sampleSize = extractor.readSampleData(buffer, 0)
                }
                if (sampleSize < 0) break
                require(sampleSize <= buffer.capacity()) {
                    "Encoded audio sample is larger than the hash buffer."
                }

                digest.updateLong(extractor.sampleTime)
                digest.updateInt(extractor.sampleFlags)
                digest.updateInt(sampleSize)
                buffer.position(0)
                buffer.limit(sampleSize)
                while (buffer.hasRemaining()) {
                    val length = minOf(buffer.remaining(), scratch.size)
                    buffer.get(scratch, 0, length)
                    digest.update(scratch, 0, length)
                }

                sampleCount += 1
                byteCount += sampleSize.toLong()
                val advanced = extractor.advance()
                if (!advanced && sampleSize == 0) break
            }

            require(sampleCount > 0L) { "No encoded audio samples were read." }
            return EncodedAudioSamplesHash(
                sha256 = digest.digest().toHexString(),
                sampleCount = sampleCount,
                byteCount = byteCount,
            )
        } finally {
            extractor.release()
        }
    }

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
            val trackMetadata = audioTrackMetadata(inputFormat, mime)

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

    fun decodeWindow(
        uri: Uri,
        startUs: Long,
        endUs: Long,
        shouldCancel: () -> Boolean = { false },
    ): WindowDecodedPcmAudio {
        require(startUs >= 0L) { "Window start must not be negative." }
        require(endUs > startUs) { "Window end must be after start." }

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var codecStarted = false

        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = findAudioTrack(extractor)
            if (trackIndex < 0) error("No audio track was found.")

            extractor.selectTrack(trackIndex)
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val actualStartUs = extractor.sampleTime.takeIf { it >= 0L } ?: startUs
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: error("Audio track has no MIME type.")
            val trackMetadata = audioTrackMetadata(inputFormat, mime)

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
            var firstOutputTimeUs: Long? = null
            var lastOutputTimeUs: Long? = null
            var decodedOutputBuffers = 0

            while (!outputEnded) {
                throwIfCanceled(shouldCancel)
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                            ?: error("Decoder returned a null input buffer.")
                        val sampleTimeUs = extractor.sampleTime
                        if (sampleTimeUs < 0 || sampleTimeUs >= endUs) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputEnded = true
                        } else {
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
                                    sampleTimeUs,
                                    0,
                                )
                                extractor.advance()
                            }
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
                                val inputBytesPerFrame = activeFormat.inputBytesPerFrame()
                                if (inputBytesPerFrame > 0) {
                                    val frameCount = bufferInfo.size / inputBytesPerFrame
                                    val frameDurationUs =
                                        frameCount.toDouble() * MICROS_PER_SECOND / activeFormat.sampleRate
                                    val bufferStartUs = bufferInfo.presentationTimeUs
                                    val bufferEndUs = bufferStartUs + frameDurationUs
                                    if (bufferEndUs > startUs && bufferStartUs < endUs) {
                                        val trimStartFrames = if (bufferStartUs < startUs) {
                                            (((startUs - bufferStartUs) * activeFormat.sampleRate) /
                                                    MICROS_PER_SECOND).toInt().coerceIn(0, frameCount)
                                        } else {
                                            0
                                        }
                                        val trimEndFrames = if (bufferEndUs > endUs) {
                                            (((bufferEndUs - endUs) * activeFormat.sampleRate) /
                                                    MICROS_PER_SECOND).toInt().coerceIn(0, frameCount)
                                        } else {
                                            0
                                        }
                                        val keptFrames = frameCount - trimStartFrames - trimEndFrames
                                        if (keptFrames > 0) {
                                            val trimmedInfo = MediaCodec.BufferInfo().apply {
                                                set(
                                                    bufferInfo.offset + trimStartFrames * inputBytesPerFrame,
                                                    keptFrames * inputBytesPerFrame,
                                                    bufferInfo.presentationTimeUs +
                                                            (trimStartFrames.toLong() * MICROS_PER_SECOND) /
                                                            activeFormat.sampleRate,
                                                    bufferInfo.flags,
                                                )
                                            }
                                            firstOutputTimeUs = firstOutputTimeUs ?: trimmedInfo.presentationTimeUs
                                            lastOutputTimeUs = trimmedInfo.presentationTimeUs
                                            decodedOutputBuffers += 1
                                            writePcmAs16Bit(output, outputBuffer, trimmedInfo, activeFormat.encoding)
                                        }
                                    }
                                }
                            }
                            outputEnded = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            codec.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
            }

            val format = writerFormat ?: error("Decoder produced no PCM output.")
            val audio = DecodedPcmAudio(
                sampleRate = format.sampleRate,
                channelCount = format.channelCount,
                pcm16 = output.toByteArray(),
            )
            return WindowDecodedPcmAudio(
                audio = audio,
                requestedStartUs = startUs,
                requestedEndUs = endUs,
                extractorStartUs = actualStartUs,
                firstOutputTimeUs = firstOutputTimeUs,
                lastOutputTimeUs = lastOutputTimeUs,
                outputBufferCount = decodedOutputBuffers,
                trackMetadata = trackMetadata,
            )
        } finally {
            if (codecStarted) {
                codec?.stop()
            }
            codec?.release()
            extractor.release()
        }
    }

    fun decodeWindowWithPrerollCursor(
        uri: Uri,
        startUs: Long,
        endUs: Long,
        prerollUs: Long,
        shouldCancel: () -> Boolean = { false },
    ): WindowDecodedPcmAudio {
        require(startUs >= 0L) { "Window start must not be negative." }
        require(endUs > startUs) { "Window end must be after start." }
        require(prerollUs >= 0L) { "Preroll must not be negative." }

        val decodeStartUs = (startUs - prerollUs).coerceAtLeast(0L)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var codecStarted = false

        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = findAudioTrack(extractor)
            if (trackIndex < 0) error("No audio track was found.")

            extractor.selectTrack(trackIndex)
            extractor.seekTo(decodeStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val actualStartUs = extractor.sampleTime.takeIf { it >= 0L } ?: decodeStartUs
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: error("Audio track has no MIME type.")
            val trackMetadata = audioTrackMetadata(inputFormat, mime)

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
            var firstOutputTimeUs: Long? = null
            var lastOutputTimeUs: Long? = null
            var decodedOutputBuffers = 0
            var cursorFrame: Long? = null
            var cursorAnchorTimeUs: Long? = null

            while (!outputEnded) {
                throwIfCanceled(shouldCancel)
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                            ?: error("Decoder returned a null input buffer.")
                        val sampleTimeUs = extractor.sampleTime
                        if (sampleTimeUs < 0 || sampleTimeUs >= endUs) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputEnded = true
                        } else {
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
                                    sampleTimeUs,
                                    0,
                                )
                                extractor.advance()
                            }
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
                                val inputBytesPerFrame = activeFormat.inputBytesPerFrame()
                                if (inputBytesPerFrame > 0) {
                                    val frameCount = bufferInfo.size / inputBytesPerFrame
                                    if (cursorFrame == null) {
                                        cursorAnchorTimeUs = bufferInfo.presentationTimeUs
                                        cursorFrame = usToFrame(bufferInfo.presentationTimeUs, activeFormat.sampleRate)
                                    }
                                    val bufferStartFrame = cursorFrame
                                    val bufferEndFrame = bufferStartFrame + frameCount
                                    val requestedStartFrame = usToFrame(startUs, activeFormat.sampleRate)
                                    val requestedEndFrame = usToFrame(endUs, activeFormat.sampleRate)
                                    if (bufferEndFrame > requestedStartFrame && bufferStartFrame < requestedEndFrame) {
                                        val trimStartFrames = (requestedStartFrame - bufferStartFrame)
                                            .coerceIn(0L, frameCount.toLong())
                                            .toInt()
                                        val trimEndFrames = (bufferEndFrame - requestedEndFrame)
                                            .coerceIn(0L, frameCount.toLong())
                                            .toInt()
                                        val keptFrames = frameCount - trimStartFrames - trimEndFrames
                                        if (keptFrames > 0) {
                                            val keptStartFrame = bufferStartFrame + trimStartFrames
                                            val trimmedInfo = MediaCodec.BufferInfo().apply {
                                                set(
                                                    bufferInfo.offset + trimStartFrames * inputBytesPerFrame,
                                                    keptFrames * inputBytesPerFrame,
                                                    frameToUs(keptStartFrame, activeFormat.sampleRate),
                                                    bufferInfo.flags,
                                                )
                                            }
                                            firstOutputTimeUs = firstOutputTimeUs ?: trimmedInfo.presentationTimeUs
                                            lastOutputTimeUs = trimmedInfo.presentationTimeUs
                                            decodedOutputBuffers += 1
                                            writePcmAs16Bit(output, outputBuffer, trimmedInfo, activeFormat.encoding)
                                        }
                                    }
                                    cursorFrame = bufferEndFrame
                                }
                            }
                            outputEnded = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            codec.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
            }

            val format = writerFormat ?: error("Decoder produced no PCM output.")
            val audio = DecodedPcmAudio(
                sampleRate = format.sampleRate,
                channelCount = format.channelCount,
                pcm16 = output.toByteArray(),
            )
            return WindowDecodedPcmAudio(
                audio = audio,
                requestedStartUs = startUs,
                requestedEndUs = endUs,
                extractorStartUs = actualStartUs,
                firstOutputTimeUs = firstOutputTimeUs,
                lastOutputTimeUs = lastOutputTimeUs,
                outputBufferCount = decodedOutputBuffers,
                cursorAnchorTimeUs = cursorAnchorTimeUs,
                trackMetadata = trackMetadata,
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

    private fun audioTrackMetadata(
        format: MediaFormat,
        mimeType: String,
    ): AudioDecodeTrackMetadata {
        return AudioDecodeTrackMetadata(
            mimeType = mimeType,
            encoderDelayFrames = format.optionalInteger(MediaFormat.KEY_ENCODER_DELAY),
            encoderPaddingFrames = format.optionalInteger(MediaFormat.KEY_ENCODER_PADDING),
        )
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

    private fun MediaFormat.optionalLong(key: String): Long? {
        return if (containsKey(key)) getLong(key) else null
    }

    private fun usToFrame(timeUs: Long, sampleRate: Int): Long {
        return (timeUs.toDouble() * sampleRate / MICROS_PER_SECOND).roundToLong()
    }

    private fun frameToUs(frame: Long, sampleRate: Int): Long {
        return (frame * MICROS_PER_SECOND) / sampleRate.toLong()
    }

    private fun MessageDigest.updateString(value: String) {
        update(value.encodeToByteArray())
        updateInt(0)
    }

    private fun MessageDigest.updateInt(value: Int) {
        update(byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte(),
        ))
    }

    private fun MessageDigest.updateLong(value: Long) {
        update(byteArrayOf(
            (value ushr 56).toByte(),
            (value ushr 48).toByte(),
            (value ushr 40).toByte(),
            (value ushr 32).toByte(),
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte(),
        ))
    }

    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02x".format(it) }
    }

    private data class AudioOutputFormat(
        val sampleRate: Int,
        val channelCount: Int,
        val encoding: Int,
    ) {
        fun inputBytesPerFrame(): Int {
            val bytesPerSample = when (encoding) {
                AudioFormat.ENCODING_PCM_8BIT -> Byte.SIZE_BYTES
                AudioFormat.ENCODING_PCM_FLOAT -> Float.SIZE_BYTES
                else -> Short.SIZE_BYTES
            }
            return channelCount * bytesPerSample
        }
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
        const val MICROS_PER_SECOND = 1_000_000L
        const val DEFAULT_SAMPLE_HASH_BUFFER_BYTES = 1024 * 1024
        const val MAX_SAMPLE_HASH_BUFFER_BYTES = 16 * 1024 * 1024
        const val SAMPLE_HASH_CHUNK_BYTES = 64 * 1024
    }
}

data class EncodedAudioSamplesHash(
    val sha256: String,
    val sampleCount: Long,
    val byteCount: Long,
)

data class AudioSourceInfo(
    val mimeType: String,
    val sampleRate: Int,
    val channelCount: Int,
    val durationUs: Long?,
    val trackMetadata: AudioDecodeTrackMetadata,
) {
    val frameCount: Int?
        get() = durationUs
            ?.let { (it.toDouble() * sampleRate.toDouble() / MICROS_PER_SECOND).roundToLong() }
            ?.coerceIn(0L, Int.MAX_VALUE.toLong())
            ?.toInt()

    private companion object {
        const val MICROS_PER_SECOND = 1_000_000L
    }
}

data class WindowDecodedPcmAudio(
    val audio: DecodedPcmAudio,
    val requestedStartUs: Long,
    val requestedEndUs: Long,
    val extractorStartUs: Long,
    val firstOutputTimeUs: Long?,
    val lastOutputTimeUs: Long?,
    val outputBufferCount: Int,
    val cursorAnchorTimeUs: Long? = null,
    val trackMetadata: AudioDecodeTrackMetadata? = null,
)

data class AudioDecodeTrackMetadata(
    val mimeType: String,
    val encoderDelayFrames: Int?,
    val encoderPaddingFrames: Int?,
)
