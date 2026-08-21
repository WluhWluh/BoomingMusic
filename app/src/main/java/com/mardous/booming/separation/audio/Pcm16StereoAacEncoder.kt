package com.mardous.booming.separation.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaExtractor
import android.media.MediaMuxer
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder

/**
 * Encodes one complete PCM16 stereo stem with the platform AAC-LC encoder.
 *
 * This is deliberately a post-processing artifact. The WAV remains the source
 * of truth until every stem has been encoded and verified by the promoter.
 */
internal object Pcm16StereoAacEncoder {
    const val DEFAULT_BIT_RATE = 160_000
    /** Portable default: preserve the non-negative encoder PTS timeline. */
    const val DEFAULT_TIMESTAMP_OFFSET_FRAMES = 0L
    /** Optional BandBuddy comparison profile; not the default device contract. */
    const val BAND_BUDDY_TIMESTAMP_OFFSET_FRAMES = 2_048L

    fun encodeWavToM4a(
        wavFile: File,
        m4aFile: File,
        expectedSampleRate: Int,
        expectedFrameCount: Int,
        bitRate: Int = DEFAULT_BIT_RATE,
        timestampOffsetFrames: Long = DEFAULT_TIMESTAMP_OFFSET_FRAMES,
        shouldCancel: () -> Boolean = { false },
    ): Pcm16StereoAacEncodeResult {
        val wav = Pcm16WavFileReader.read(wavFile)
        require(wav.channelCount == CHANNEL_COUNT_STEREO) {
            "Only stereo WAV input is supported for AAC promotion."
        }
        require(wav.sampleRate == expectedSampleRate) {
            "WAV sample rate does not match expected AAC output rate."
        }
        require(wav.frameCount == expectedFrameCount.toLong()) {
            "WAV frame count does not match expected AAC output length."
        }
        require(bitRate > 0) { "AAC bitrate must be positive." }
        require(timestampOffsetFrames >= 0L) {
            "AAC timestamp offset must not be negative."
        }

        val parent = m4aFile.parentFile
        parent?.mkdirs()
        val temporary = File.createTempFile(
            "${m4aFile.name}.",
            ".tmp",
            parent ?: File("."),
        )
        var published = false
        try {
            val writer = AacM4aWriter(
                file = temporary,
                sampleRate = wav.sampleRate,
                channelCount = wav.channelCount,
                bitRate = bitRate,
                timestampOffsetFrames = timestampOffsetFrames,
            )
            val codecName = writer.codecName
            writer.use {
                RandomAccessFile(wavFile, "r").use { input ->
                    input.seek(wav.dataOffset)
                    val buffer = ByteArray(INPUT_BUFFER_BYTES)
                    var remainingFrames = wav.frameCount
                    while (remainingFrames > 0L) {
                        throwIfCanceled(shouldCancel)
                        val requestedBytes = minOf(
                            buffer.size.toLong(),
                            remainingFrames * BYTES_PER_FRAME,
                        ).toInt()
                        input.readFully(buffer, 0, requestedBytes)
                        writer.writePcm16(buffer, requestedBytes)
                        remainingFrames -= requestedBytes / BYTES_PER_FRAME
                    }
                }
            }
            throwIfCanceled(shouldCancel)
            if (m4aFile.exists()) {
                check(m4aFile.delete()) { "Could not replace AAC output." }
            }
            check(temporary.renameTo(m4aFile)) {
                "Could not publish AAC output: ${m4aFile.absolutePath}"
            }
            published = true
            val verification = verifyM4aFile(
                file = m4aFile,
                expectedSampleRate = expectedSampleRate,
                expectedChannelCount = CHANNEL_COUNT_STEREO,
                expectedFrameCount = expectedFrameCount.toLong(),
            )
            return Pcm16StereoAacEncodeResult(
                sampleRate = wav.sampleRate,
                channelCount = wav.channelCount,
                frameCount = expectedFrameCount,
                bitRate = bitRate,
                encoderDelayFrames = verification.encoderDelayFrames,
                encoderPaddingFrames = verification.encoderPaddingFrames,
                timestampOffsetFrames = timestampOffsetFrames,
                outputBytes = m4aFile.length(),
                codecName = codecName,
                outputMimeType = verification.mimeType,
                outputBitRate = verification.bitRate,
                outputProfile = verification.aacProfile,
                encodedSampleCount = verification.sampleCount,
                decodedFrameCount = verification.decodedFrameCount,
                firstSampleTimeUs = verification.firstSampleTimeUs,
                lastSampleTimeUs = verification.lastSampleTimeUs,
            )
        } catch (error: Throwable) {
            temporary.delete()
            if (published) m4aFile.delete()
            throw error
        }
    }

    private fun throwIfCanceled(shouldCancel: () -> Boolean) {
        if (shouldCancel()) {
            throw java.util.concurrent.CancellationException("AAC promotion canceled.")
        }
    }

    private fun verifyM4aFile(
        file: File,
        expectedSampleRate: Int,
        expectedChannelCount: Int,
        expectedFrameCount: Long,
    ): Pcm16StereoAacVerification {
        require(file.isFile && file.length() > 0L) { "AAC output is empty." }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            var trackIndex = -1
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = index
                    break
                }
            }
            require(trackIndex >= 0) { "AAC output has no audio track." }
            val format = extractor.getTrackFormat(trackIndex)
            val mimeType = format.getString(MediaFormat.KEY_MIME)
            require(mimeType == MediaFormat.MIMETYPE_AUDIO_AAC) {
                "AAC output has an unexpected MIME type."
            }
            require(format.getInteger(MediaFormat.KEY_SAMPLE_RATE) == expectedSampleRate) {
                "AAC output sample rate does not match its contract."
            }
            require(format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == expectedChannelCount) {
                "AAC output channel count does not match its contract."
            }
            val aacProfile = format.optionalInteger(MediaFormat.KEY_AAC_PROFILE)
            require(aacProfile == null ||
                    aacProfile == MediaCodecInfo.CodecProfileLevel.AACObjectLC
            ) { "AAC output is not AAC-LC: $aacProfile" }
            val bitRate = format.optionalInteger(MediaFormat.KEY_BIT_RATE)
                ?.takeIf { it > 0 }
            extractor.selectTrack(trackIndex)
            var sampleCount = 0L
            var firstSampleTimeUs: Long? = null
            var lastSampleTimeUs: Long? = null
            var previousSampleTimeUs: Long? = null
            while (true) {
                val sampleTimeUs = extractor.sampleTime
                if (sampleTimeUs < 0L) break
                val sampleSize = extractor.sampleSize
                require(sampleSize > 0L) { "AAC output contains an empty sample." }
                previousSampleTimeUs?.let { previous ->
                    require(sampleTimeUs >= previous) {
                        "AAC sample timestamps are not monotonic."
                    }
                }
                if (firstSampleTimeUs == null) firstSampleTimeUs = sampleTimeUs
                lastSampleTimeUs = sampleTimeUs
                previousSampleTimeUs = sampleTimeUs
                sampleCount += 1L
                if (!extractor.advance()) break
            }
            require(sampleCount > 0L) { "AAC output contains no samples." }
            val decodedFrameCount = decodeFrameCount(
                file = file,
                trackIndex = trackIndex,
                expectedSampleRate = expectedSampleRate,
                expectedChannelCount = expectedChannelCount,
                expectedFrameCount = expectedFrameCount,
            )
            return Pcm16StereoAacVerification(
                mimeType = mimeType,
                bitRate = bitRate,
                aacProfile = aacProfile,
                encoderDelayFrames = format.optionalInteger(MediaFormat.KEY_ENCODER_DELAY)
                    ?.takeIf { it >= 0 }
                    ?.toLong(),
                encoderPaddingFrames = format.optionalInteger(MediaFormat.KEY_ENCODER_PADDING)
                    ?.takeIf { it >= 0 }
                    ?.toLong(),
                sampleCount = sampleCount,
                decodedFrameCount = decodedFrameCount,
                firstSampleTimeUs = firstSampleTimeUs,
                lastSampleTimeUs = lastSampleTimeUs,
            )
        } finally {
            extractor.release()
        }
    }

    private fun decodeFrameCount(
        file: File,
        trackIndex: Int,
        expectedSampleRate: Int,
        expectedChannelCount: Int,
        expectedFrameCount: Long,
    ): Long {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var outputFormat: MediaFormat? = null
            var decodedFrames = 0L
            var idlePolls = 0
            while (!outputEnded) {
                var progressed = false
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(10_000L)
                    if (inputIndex >= 0) {
                        val input = codec.getInputBuffer(inputIndex)
                            ?: error("AAC validation input buffer is unavailable.")
                        input.clear()
                        val sampleTimeUs = extractor.sampleTime
                        if (sampleTimeUs < 0L) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputEnded = true
                        } else {
                            val sampleSize = extractor.sampleSize
                            require(sampleSize > 0L && sampleSize <= input.remaining()) {
                                "AAC validation sample does not fit decoder input."
                            }
                            val readSize = extractor.readSampleData(input, 0)
                            require(readSize == sampleSize.toInt()) {
                                "AAC validation sample size changed."
                            }
                            codec.queueInputBuffer(inputIndex, 0, readSize, sampleTimeUs, 0)
                            extractor.advance()
                        }
                        progressed = true
                    }
                }
                when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000L)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        outputFormat = codec.outputFormat
                        val rate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        val channels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        require(rate == expectedSampleRate && channels == expectedChannelCount) {
                            "AAC validation decoder geometry changed: $rate/$channels"
                        }
                        progressed = true
                    }
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> progressed = true
                    else -> if (outputIndex >= 0) {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 &&
                            info.size > 0
                        ) {
                            val formatOut = requireNotNull(outputFormat) {
                                "AAC validation output format is unavailable."
                            }
                            val encoding = if (formatOut.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                formatOut.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            } else {
                                AudioFormat.ENCODING_PCM_16BIT
                            }
                            val bytesPerSample = when (encoding) {
                                AudioFormat.ENCODING_PCM_FLOAT -> Float.SIZE_BYTES
                                AudioFormat.ENCODING_PCM_16BIT -> Short.SIZE_BYTES
                                else -> error("Unsupported AAC validation PCM encoding: $encoding")
                            }
                            val bytesPerFrame = expectedChannelCount * bytesPerSample
                            require(info.size % bytesPerFrame == 0) {
                                "AAC validation output is not frame aligned."
                            }
                            val output = codec.getOutputBuffer(outputIndex)
                            require(output != null) { "AAC validation output buffer is unavailable." }
                            val duplicate = output.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                            duplicate.position(info.offset)
                            duplicate.limit(info.offset + info.size)
                            if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                while (duplicate.remaining() >= Float.SIZE_BYTES) {
                                    require(duplicate.float.isFinite()) {
                                        "AAC validation output contains non-finite PCM."
                                    }
                                }
                            }
                            decodedFrames += info.size.toLong() / bytesPerFrame
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                        progressed = true
                    }
                }
                idlePolls = if (progressed) 0 else idlePolls + 1
                check(idlePolls < MAX_VALIDATION_POLLS) {
                    "AAC validation decoder stalled."
                }
            }
            require(kotlin.math.abs(decodedFrames - expectedFrameCount) <= MAX_DECODED_LENGTH_DELTA_FRAMES) {
                "AAC decoded frame count differs too much: $decodedFrames vs $expectedFrameCount"
            }
            return decodedFrames
        } finally {
            runCatching { codec?.stop() }
            codec?.release()
            extractor.release()
        }
    }

    private class AacM4aWriter(
        private val file: File,
        private val sampleRate: Int,
        private val channelCount: Int,
        private val bitRate: Int,
        private val timestampOffsetFrames: Long,
    ) : Closeable {
        private val codec: MediaCodec
        private val muxer: MediaMuxer
        private val bufferInfo = MediaCodec.BufferInfo()
        private var trackIndex = -1
        private var muxerStarted = false
        private var submittedFrames = 0L
        private var closed = false
        private var outputFormat: MediaFormat? = null
        private var encodedSampleCount = 0L

        val codecName: String
            get() = codec.name

        init {
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                sampleRate,
                channelCount,
            ).apply {
                setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC,
                )
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, INPUT_BUFFER_BYTES)
            }
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            try {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                muxer = MediaMuxer(
                    file.absolutePath,
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
                )
                codec.start()
            } catch (error: Throwable) {
                runCatching { codec.release() }
                file.delete()
                throw error
            }
        }

        fun writePcm16(bytes: ByteArray, byteCount: Int) {
            check(!closed) { "AAC encoder is closed." }
            require(byteCount >= 0 && byteCount <= bytes.size) {
                "Invalid PCM16 byte count."
            }
            require(byteCount % BYTES_PER_FRAME == 0) {
                "PCM16 input is not aligned to stereo frames."
            }
            var offset = 0
            var stalledPolls = 0
            while (offset < byteCount) {
                val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                if (inputIndex < 0) {
                    drain(endOfStream = false)
                    check(++stalledPolls < MAX_END_OF_STREAM_POLLS) {
                        "AAC encoder did not expose an input buffer."
                    }
                    continue
                }
                stalledPolls = 0
                val input = codec.getInputBuffer(inputIndex)
                    ?: error("AAC encoder input buffer is unavailable.")
                input.clear()
                input.order(ByteOrder.LITTLE_ENDIAN)
                val count = minOf(byteCount - offset, input.remaining())
                val alignedCount = count - count % BYTES_PER_FRAME
                check(alignedCount > 0) { "AAC encoder input buffer is too small." }
                input.put(bytes, offset, alignedCount)
                codec.queueInputBuffer(
                    inputIndex,
                    0,
                    alignedCount,
                    framesToUs(submittedFrames),
                    0,
                )
                submittedFrames += alignedCount / BYTES_PER_FRAME
                offset += alignedCount
                drain(endOfStream = false)
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            var failure: Throwable? = null
            try {
                queueEndOfStream()
                drain(endOfStream = true)
            } catch (error: Throwable) {
                failure = error
            }
            runCatching { codec.stop() }.onFailure { if (failure == null) failure = it }
            runCatching { codec.release() }.onFailure { if (failure == null) failure = it }
            if (muxerStarted) {
                runCatching { muxer.stop() }.onFailure { if (failure == null) failure = it }
            }
            runCatching { muxer.release() }.onFailure { if (failure == null) failure = it }
            failure?.let { throw it }
        }

        private fun queueEndOfStream() {
            var polls = 0
            while (true) {
                val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                if (inputIndex >= 0) {
                    codec.queueInputBuffer(
                        inputIndex,
                        0,
                        0,
                        framesToUs(submittedFrames),
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                    )
                    return
                }
                drain(endOfStream = false)
                check(++polls < MAX_END_OF_STREAM_POLLS) {
                    "AAC encoder could not receive end-of-stream."
                }
            }
        }

        private fun drain(endOfStream: Boolean) {
            var emptyPolls = 0
            while (true) {
                when (val outputIndex = codec.dequeueOutputBuffer(
                    bufferInfo,
                    if (endOfStream) CODEC_TIMEOUT_US else 0L,
                )) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!endOfStream) return
                        check(++emptyPolls < MAX_END_OF_STREAM_POLLS) {
                            "AAC encoder did not finish."
                        }
                    }
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(!muxerStarted) { "AAC output format changed more than once." }
                        outputFormat = codec.outputFormat
                        trackIndex = muxer.addTrack(requireNotNull(outputFormat))
                        muxer.start()
                        muxerStarted = true
                    }
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> if (outputIndex >= 0) {
                        emptyPolls = 0
                        val encoded = codec.getOutputBuffer(outputIndex)
                            ?: error("AAC encoder output buffer is unavailable.")
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            bufferInfo.size = 0
                        }
                        if (bufferInfo.size > 0) {
                            check(muxerStarted && trackIndex >= 0) {
                                "AAC muxer is not ready."
                            }
                            encoded.position(bufferInfo.offset)
                            encoded.limit(bufferInfo.offset + bufferInfo.size)
                            val codecPresentationTimeUs = bufferInfo.presentationTimeUs
                            bufferInfo.presentationTimeUs = codecPresentationTimeUs -
                                    framesToUs(timestampOffsetFrames)
                            try {
                                muxer.writeSampleData(trackIndex, encoded, bufferInfo)
                                encodedSampleCount += 1L
                            } finally {
                                bufferInfo.presentationTimeUs = codecPresentationTimeUs
                            }
                        }
                        val ended = bufferInfo.flags and
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (ended) return
                    }
                }
            }
        }

        private fun framesToUs(frames: Long): Long =
            frames * 1_000_000L / sampleRate

        private companion object {
            const val CODEC_TIMEOUT_US = 10_000L
            const val MAX_END_OF_STREAM_POLLS = 1_000
        }
    }

    private const val CHANNEL_COUNT_STEREO = 2
    private const val BYTES_PER_SAMPLE = 2
    private const val BYTES_PER_FRAME = CHANNEL_COUNT_STEREO * BYTES_PER_SAMPLE
    private const val INPUT_BUFFER_BYTES = 32 * 1_024
}

data class Pcm16StereoAacEncodeResult(
    val sampleRate: Int,
    val channelCount: Int,
    val frameCount: Int,
    val bitRate: Int,
    val encoderDelayFrames: Long?,
    val encoderPaddingFrames: Long?,
    val timestampOffsetFrames: Long,
    val outputBytes: Long,
    val codecName: String,
    val outputMimeType: String,
    val outputBitRate: Int?,
    val outputProfile: Int?,
    val encodedSampleCount: Long,
    val decodedFrameCount: Long,
    val firstSampleTimeUs: Long?,
    val lastSampleTimeUs: Long?,
)

private data class Pcm16StereoAacVerification(
    val mimeType: String,
    val bitRate: Int?,
    val aacProfile: Int?,
    val encoderDelayFrames: Long?,
    val encoderPaddingFrames: Long?,
    val sampleCount: Long,
    val decodedFrameCount: Long,
    val firstSampleTimeUs: Long?,
    val lastSampleTimeUs: Long?,
)

private fun MediaFormat.optionalInteger(key: String): Int? =
    if (containsKey(key)) getInteger(key) else null

private const val MAX_DECODED_LENGTH_DELTA_FRAMES = 8_192L
private const val MAX_VALIDATION_POLLS = 1_000
