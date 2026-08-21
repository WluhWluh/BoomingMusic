package com.mardous.booming.playback

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToLong

data class SourceSeparationAacPlaybackProfile(
    val encoderDelayFrames: Long? = null,
    val encoderPaddingFrames: Long? = null,
    val seekQuantumFrames: Int? = null,
    val maxAnchorOffsetFrames: Int? = null,
    val timestampOffsetFrames: Long? = null,
    /** Independent AAC decoders used for the first post-seek block. */
    val fastSeekParallelism: Int? = null,
    /** Experimental first-resume block size in PCM frames. */
    val fastSeekBlockFrames: Int = 1_024,
    /** Optional early data-plane gate in PCM frames; null keeps the stable waterline. */
    val fastSeekReadyFrames: Int? = null,
    /** Sync-point direction used by the decoder after a seek. */
    val seekMode: SourceSeparationAacSeekMode = SourceSeparationAacSeekMode.PREVIOUS,
    /** Number of AAC access units seeded before the first PCM output poll. */
    val firstOutputInputSeedBurst: Int = 4,
)

/** Persistent platform AAC decoder used only by the experimental M4A cache profile. */
internal class SourceSeparationAacStemSourceFactory(
    private val file: File,
    stemId: String,
    expectedSampleRate: Int,
    expectedChannelCount: Int,
    expectedFrameCount: Long? = null,
    private val profile: SourceSeparationAacPlaybackProfile? = null,
    private val traceSink: ((String) -> Unit)? = null,
) : SourceSeparationPlaybackStemSourceFactory {
    private val track = inspectTrack(file)

    override val spec = SourceSeparationPlaybackStemSpec(
        stemId = stemId,
        geometry = SourceSeparationPlaybackGeometry(
            sampleRate = track.sampleRate,
            channelCount = track.channelCount,
            frameCount = expectedFrameCount ?: track.logicalFrameCount,
        ),
    )

    override val fastSeekParallelism: Int
        get() = (profile?.fastSeekParallelism ?: DEFAULT_FAST_SEEK_PARALLELISM)

    override val playbackBlockFrameCapacity: Int
        get() = DEFAULT_PLAYBACK_BLOCK_FRAMES

    override val seekResumeBlockFrameCapacity: Int
        get() = profile?.fastSeekBlockFrames ?: DEFAULT_FAST_SEEK_BLOCK_FRAMES

    override val seekResumeReadyFrameCapacity: Int?
        get() = profile?.fastSeekReadyFrames

    override val allowPartialSeekRead: Boolean
        get() = true

    private val firstOutputInputSeedBurst: Int
        get() = profile?.firstOutputInputSeedBurst ?: DEFAULT_FIRST_OUTPUT_INPUT_SEED_BURST

    init {
        require(track.sampleRate == expectedSampleRate) {
            "AAC stem sample rate does not match the separation contract."
        }
        require(track.channelCount == expectedChannelCount) {
            "AAC stem channel count does not match the separation contract."
        }
        require(spec.geometry.frameCount > 0L) {
            "AAC stem logical frame count is unavailable."
        }
        require((profile?.seekQuantumFrames ?: DEFAULT_SEEK_QUANTUM_FRAMES) > 0) {
            "AAC stem seek quantum is invalid."
        }
        require((profile?.maxAnchorOffsetFrames ?: DEFAULT_MAX_FAST_SEEK_OFFSET_FRAMES.toInt()) > 0) {
            "AAC stem anchor offset bound is invalid."
        }
        require(fastSeekParallelism in 1..MAX_FAST_SEEK_PARALLELISM) {
            "AAC stem fast-seek parallelism is invalid: $fastSeekParallelism"
        }
        require(seekResumeBlockFrameCapacity in MIN_FAST_SEEK_BLOCK_FRAMES..MAX_FAST_SEEK_BLOCK_FRAMES) {
            "AAC stem fast-seek block size is invalid: $seekResumeBlockFrameCapacity"
        }
        profile?.fastSeekReadyFrames?.let { value ->
            require(value in MIN_FAST_SEEK_BLOCK_FRAMES..playbackBlockFrameCapacity) {
                "AAC stem fast-seek ready threshold must fit the steady-state block: $value"
            }
        }
        require(firstOutputInputSeedBurst in 1..MAX_FIRST_OUTPUT_INPUT_SEED_BURST) {
            "AAC first-output input seed burst is invalid: $firstOutputInputSeedBurst"
        }
        profile?.encoderDelayFrames?.let {
            require(it >= 0L) { "AAC stem encoder delay is invalid." }
        }
        profile?.encoderPaddingFrames?.let {
            require(it >= 0L) { "AAC stem encoder padding is invalid." }
        }
        expectedFrameCount?.let { expected ->
            val durationDelta = track.logicalFrameCount - expected
            if (track.logicalFrameCount > 0L) require(
                kotlin.math.abs(durationDelta) <= MAX_DURATION_DELTA_FRAMES,
            ) {
                "AAC duration differs too much from the cache frame contract: $durationDelta"
            }
            traceSink?.invoke(
                "durationFrames=${track.logicalFrameCount} contractFrames=$expected " +
                        "deltaFrames=$durationDelta",
            )
        }
    }

    override fun open(): SourceSeparationPlaybackStemSource = AacStemSource(
        file = file,
        geometry = spec.geometry,
        encoderDelayFrames = profile?.encoderDelayFrames,
        encoderPaddingFrames = profile?.encoderPaddingFrames,
        seekQuantumFrames = profile?.seekQuantumFrames ?: DEFAULT_SEEK_QUANTUM_FRAMES,
        maxSeekOffsetFrames = (profile?.maxAnchorOffsetFrames ?:
                DEFAULT_MAX_FAST_SEEK_OFFSET_FRAMES.toInt()).toLong(),
        timestampOffsetFrames = profile?.timestampOffsetFrames,
        seekMode = profile?.seekMode ?: SourceSeparationAacSeekMode.PREVIOUS,
        firstOutputInputSeedBurst = firstOutputInputSeedBurst,
        traceSink = traceSink,
    )

    private data class TrackInfo(
        val sampleRate: Int,
        val channelCount: Int,
        val logicalFrameCount: Long,
    )

    private companion object {
        fun inspectTrack(file: File): TrackInfo {
            require(file.isFile) { "AAC stem does not exist: ${file.absolutePath}" }
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                val trackIndex = findAudioTrack(extractor)
                require(trackIndex >= 0) { "M4A stem has no audio track." }
                val format = extractor.getTrackFormat(trackIndex)
                val mime = format.getString(MediaFormat.KEY_MIME)
                require(mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                    "M4A stem is not AAC-LC: $mime"
                }
                val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val extractorDurationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    format.getLong(MediaFormat.KEY_DURATION)
                } else {
                    null
                }
                val durationUs = runCatching {
                    FileInputStream(file).channel.use { channel ->
                        com.mardous.booming.separation.audio.readMp4MovieDurationUs(channel)
                    }
                }.getOrNull() ?: extractorDurationUs
                val encodedFrames = durationUs?.toDouble()
                    ?.times(sampleRate.toDouble())
                    ?.div(MICROS_PER_SECOND.toDouble())
                    ?.roundToLong()
                    ?: 0L
                return TrackInfo(
                    sampleRate = sampleRate,
                    channelCount = channelCount,
                    logicalFrameCount = encodedFrames.coerceAtLeast(0L),
                )
            } finally {
                extractor.release()
            }
        }

        fun findAudioTrack(extractor: MediaExtractor): Int {
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) return index
            }
            return -1
        }

        const val MICROS_PER_SECOND = 1_000_000L
            const val MAX_DURATION_DELTA_FRAMES = 8_192L
            const val DEFAULT_SEEK_QUANTUM_FRAMES = 1_024
            const val DEFAULT_MAX_FAST_SEEK_OFFSET_FRAMES = 4_096L
            const val DEFAULT_FAST_SEEK_PARALLELISM = 6
            const val MAX_FAST_SEEK_PARALLELISM = 6
            const val DEFAULT_FAST_SEEK_BLOCK_FRAMES = 1_024
            const val DEFAULT_FIRST_OUTPUT_INPUT_SEED_BURST = 4
            const val MAX_FIRST_OUTPUT_INPUT_SEED_BURST = 4
            const val DEFAULT_PLAYBACK_BLOCK_FRAMES = 4_096
            const val MIN_FAST_SEEK_BLOCK_FRAMES = 512
            const val MAX_FAST_SEEK_BLOCK_FRAMES = 4_096
    }

    private class AacStemSource(
        file: File,
        override val geometry: SourceSeparationPlaybackGeometry,
        private val encoderDelayFrames: Long?,
        private val encoderPaddingFrames: Long?,
        private val seekQuantumFrames: Int,
        private val maxSeekOffsetFrames: Long,
        private val timestampOffsetFrames: Long?,
        private val seekMode: SourceSeparationAacSeekMode,
        private val firstOutputInputSeedBurst: Int,
        private val traceSink: ((String) -> Unit)?,
    ) : SourceSeparationPlaybackStemSource {
        private val extractor = MediaExtractor()
        private val codec: MediaCodec
        private val bufferInfo = MediaCodec.BufferInfo()
        private var outputFormat: MediaFormat
        private var inputEnded = false
        private var outputEnded = false
        private var queuedInputSinceReset = false
        private var outputFormatValidated = false
        private var outputEncoding = AudioFormat.ENCODING_PCM_16BIT
        private var firstOutputPtsUs: Long? = null
        private var lastOutputPtsUs: Long? = null
        private var seekStartedAtNs = 0L
        private var queuedInputCountSinceReset = 0
        private var nextLogicalFrame = -1L
        private var pending = ByteArray(INITIAL_PENDING_BYTES)
        private var pendingOffset = 0
        private var pendingSize = 0
        private var closed = false

        init {
            extractor.setDataSource(file.absolutePath)
            val trackIndex = findAudioTrack(extractor)
            require(trackIndex >= 0) { "M4A stem has no audio track." }
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: error("M4A stem has no MIME type.")
            codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                outputFormat = codec.outputFormat
                traceSink?.invoke("open codec=${codec.name} file=${file.name}")
            } catch (error: Throwable) {
                runCatching { codec.release() }
                extractor.release()
                throw error
            }
        }

        override fun seekToFrame(frame: Long) {
            check(!closed) { "AAC stem source is closed." }
            seekStartedAtNs = System.nanoTime()
            val requestedFrame = frame.coerceIn(0L, geometry.frameCount)
            val requestedUs = frameToUs(requestedFrame, geometry.sampleRate)
            var appliedSeekMode = seekMode
            val extractorSeekStartedAtNs = System.nanoTime()
            extractor.seekTo(requestedUs, appliedSeekMode.extractorMode)
            if (extractor.sampleTime < 0L && appliedSeekMode == SourceSeparationAacSeekMode.NEXT) {
                appliedSeekMode = SourceSeparationAacSeekMode.PREVIOUS
                extractor.seekTo(requestedUs, appliedSeekMode.extractorMode)
            }
            val extractorSeekUs = (System.nanoTime() - extractorSeekStartedAtNs) / 1_000L
            val anchorUs = extractor.sampleTime.takeIf { it >= 0L } ?: requestedUs
            val anchorFrame = usToFrame(anchorUs, geometry.sampleRate)
            val offsetFrames = anchorFrame - requestedFrame
            require(kotlin.math.abs(offsetFrames) <= maxSeekOffsetFrames) {
                "AAC seek anchor is outside the fast-seek offset bound: $offsetFrames frames."
            }
            // Do not flush a decoder before it has accepted its first input;
            // some platform codecs lose codec-specific data in that state.
            val codecFlushStartedAtNs = System.nanoTime()
            val flushed = queuedInputSinceReset
            if (flushed) codec.flush()
            val codecFlushUs = (System.nanoTime() - codecFlushStartedAtNs) / 1_000L
            inputEnded = false
            outputEnded = false
            queuedInputSinceReset = false
            queuedInputCountSinceReset = 0
            clearPending()
            firstOutputPtsUs = null
            lastOutputPtsUs = null
            nextLogicalFrame = requestedFrame
            traceSink?.invoke(
                "seek requestedFrame=$requestedFrame anchorFrame=$anchorFrame " +
                "offsetFrames=$offsetFrames " +
                        "seekMode=${appliedSeekMode.preferenceValue} " +
                        "requestedSeekMode=${seekMode.preferenceValue} " +
                        "seekQuantumFrames=$seekQuantumFrames " +
                        "firstOutputInputSeedBurst=$firstOutputInputSeedBurst " +
                        "extractorSeekUs=$extractorSeekUs " +
                        "codecFlushUs=$codecFlushUs flushed=$flushed " +
                        "delayFrames=${encoderDelayFrames ?: "unknown"} " +
                        "paddingFrames=${encoderPaddingFrames ?: "unknown"} " +
                        "timestampOffsetFrames=${timestampOffsetFrames ?: "unknown"}",
            )
        }

        override fun readFrames(
            startFrame: Long,
            destination: ByteArray,
            destinationOffsetBytes: Int,
            frameCount: Int,
        ): Int {
            check(!closed) { "AAC stem source is closed." }
            require(startFrame >= 0L) { "Playback source frame must not be negative." }
            require(frameCount >= 0) { "Playback source read frame count must not be negative." }
            if (frameCount == 0 || startFrame >= geometry.frameCount) return 0
            require(startFrame + frameCount <= geometry.frameCount) {
                "AAC playback source read exceeds the declared frame count."
            }
            val byteCount = frameCount * geometry.channelCount * BYTES_PER_SAMPLE
            require(destinationOffsetBytes >= 0 &&
                    destinationOffsetBytes + byteCount <= destination.size
            ) { "AAC playback destination is too small." }
            if (startFrame != nextLogicalFrame) {
                seekToFrame(startFrame)
            }
            decodeUntil(byteCount)
            val available = minOf(availableBytes(), byteCount)
            if (available > 0) {
                pending.copyInto(
                    destination = destination,
                    destinationOffset = destinationOffsetBytes,
                    startIndex = pendingOffset,
                    endIndex = pendingOffset + available,
                )
            }
            if (available < byteCount) {
                check(
                    outputEnded && startFrame + frameCount >= geometry.frameCount,
                ) { "AAC decoder returned an incomplete non-tail playback block." }
                destination.fill(
                    element = 0,
                    fromIndex = destinationOffsetBytes + available,
                    toIndex = destinationOffsetBytes + byteCount,
                )
                traceSink?.invoke("tailPad bytes=${byteCount - available}")
            }
            pendingOffset += available
            pendingSize -= available
            compactPending()
            nextLogicalFrame = startFrame + frameCount
            return frameCount
        }

        override fun close() {
            if (closed) return
            closed = true
            runCatching { codec.stop() }
            codec.release()
            extractor.release()
            pending = ByteArray(0)
            pendingOffset = 0
            pendingSize = 0
        }

        private fun decodeUntil(requiredBytes: Int) {
            var idlePolls = 0
            val deadlineNs = System.nanoTime() + MAX_DECODE_WALL_TIME_NS
            while (availableBytes() < requiredBytes && !outputEnded) {
                check(System.nanoTime() < deadlineNs) {
                    "AAC decoder exceeded its block decode deadline."
                }
                var progressed = false
                // Seed at most four access units per decoder reset. If a
                // vendor reports a format/config event before PCM, continue
                // one unit at a time so codecs with deeper priming cannot
                // starve while the seek burst remains strictly bounded.
                val remainingSeedInputs = if (firstOutputPtsUs == null) {
                    (firstOutputInputSeedBurst - queuedInputCountSinceReset)
                        .coerceAtLeast(0)
                } else {
                    0
                }
                val inputBurst = remainingSeedInputs.coerceAtLeast(STEADY_INPUT_BURST)
                for (ignored in 0 until inputBurst) {
                    if (!queueInput()) break
                    progressed = true
                }
                when (val outputIndex = codec.dequeueOutputBuffer(
                    bufferInfo,
                    STEADY_OUTPUT_POLL_TIMEOUT_US,
                )) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        outputFormat = codec.outputFormat
                        validateOutputFormat(outputFormat)
                        progressed = true
                    }
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> progressed = true
                    else -> if (outputIndex >= 0) {
                        val output = codec.getOutputBuffer(outputIndex)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            bufferInfo.size = 0
                        }
                        if (bufferInfo.size > 0 && output != null) {
                            if (!outputFormatValidated &&
                                outputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE) &&
                                outputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                            ) {
                                validateOutputFormat(outputFormat)
                            }
                            require(outputFormatValidated) {
                                "AAC decoder emitted PCM before its output format."
                            }
                            require(bufferInfo.size % outputBytesPerFrame() == 0) {
                                "AAC decoder emitted a partial PCM frame."
                            }
                            if (firstOutputPtsUs == null) {
                                firstOutputPtsUs = bufferInfo.presentationTimeUs
                                traceSink?.invoke(
                                    "firstOutputPtsUs=${bufferInfo.presentationTimeUs} " +
                                            "requestedFrame=$nextLogicalFrame " +
                                            "queuedInputs=$queuedInputCountSinceReset " +
                                            "seekToFirstOutputMs=" +
                                            ((System.nanoTime() - seekStartedAtNs) / 1_000_000L),
                                )
                            }
                            lastOutputPtsUs = bufferInfo.presentationTimeUs
                            appendOutputPcm(output, bufferInfo, outputFormat)
                        }
                        outputEnded = bufferInfo.flags and
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                        progressed = true
                    }
                }
                if (progressed) {
                    idlePolls = 0
                } else {
                    check(++idlePolls < MAX_IDLE_POLLS) { "AAC decoder stalled." }
                }
            }
        }

        private fun queueInput(): Boolean {
            if (inputEnded) return false
            val inputIndex = codec.dequeueInputBuffer(0L)
            if (inputIndex < 0) return false
            val input = codec.getInputBuffer(inputIndex)
                ?: error("AAC decoder input buffer is unavailable.")
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
                return true
            }
            val sampleSize = extractor.sampleSize
            require(sampleSize > 0L) { "AAC extractor returned an empty sample." }
            require(sampleSize <= input.remaining().toLong()) {
                "AAC sample exceeds the decoder input buffer: $sampleSize > ${input.remaining()}"
            }
            val readSize = extractor.readSampleData(input, 0)
            require(readSize == sampleSize.toInt()) {
                "AAC extractor sample size changed while reading."
            }
            codec.queueInputBuffer(inputIndex, 0, readSize, sampleTimeUs, 0)
            queuedInputSinceReset = true
            queuedInputCountSinceReset += 1
            if (queuedInputCountSinceReset == 1) {
                traceSink?.invoke(
                    "firstInputPtsUs=$sampleTimeUs seekToFirstInputUs=" +
                            ((System.nanoTime() - seekStartedAtNs) / 1_000L),
                )
            }
            extractor.advance()
            return true
        }

        private fun validateOutputFormat(format: MediaFormat) {
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            require(sampleRate == geometry.sampleRate) {
                "AAC decoder sample rate changed: $sampleRate"
            }
            require(channels == geometry.channelCount) {
                "AAC decoder channel count changed: $channels"
            }
            outputEncoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                format.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else {
                AudioFormat.ENCODING_PCM_16BIT
            }
            require(outputEncoding == AudioFormat.ENCODING_PCM_16BIT ||
                    outputEncoding == AudioFormat.ENCODING_PCM_FLOAT
            ) { "Unsupported AAC decoder PCM encoding: $outputEncoding" }
            outputFormatValidated = true
        }

        private fun outputBytesPerFrame(): Int = geometry.channelCount * when (outputEncoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> Float.SIZE_BYTES
            else -> Short.SIZE_BYTES
        }

        private fun appendOutputPcm(
            output: ByteBuffer,
            info: MediaCodec.BufferInfo,
            format: MediaFormat,
        ) {
            val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                format.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else {
                AudioFormat.ENCODING_PCM_16BIT
            }
            val source = output.duplicate().order(ByteOrder.LITTLE_ENDIAN).apply {
                position(info.offset)
                limit(info.offset + info.size)
            }
            val bytes = when (encoding) {
                AudioFormat.ENCODING_PCM_16BIT -> ByteArray(source.remaining()).also(source::get)
                AudioFormat.ENCODING_PCM_FLOAT -> pcmFloatTo16(source)
                else -> error("Unsupported AAC decoder PCM encoding: $encoding")
            }
            appendPending(bytes)
        }

        private fun pcmFloatTo16(source: ByteBuffer): ByteArray {
            require(source.remaining() % Float.SIZE_BYTES == 0) {
                "AAC float PCM output is misaligned."
            }
            val output = ByteBuffer.allocate(source.remaining() / 2)
                .order(ByteOrder.LITTLE_ENDIAN)
            while (source.remaining() >= Float.SIZE_BYTES) {
                val sample = source.float.coerceIn(-1f, 1f)
                val pcm = (sample * Short.MAX_VALUE).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                output.putShort(pcm.toShort())
            }
            return output.array()
        }

        private fun appendPending(bytes: ByteArray) {
            if (bytes.isEmpty()) return
            ensurePendingCapacity(bytes.size)
            bytes.copyInto(pending, pendingOffset + pendingSize)
            pendingSize += bytes.size
        }

        private fun compactPending() {
            if (pendingSize == 0) {
                pendingOffset = 0
                return
            }
            if (pendingOffset > 0 &&
                pendingOffset + pendingSize + MIN_COMPACT_REMAINING_BYTES > pending.size
            ) {
                pending.copyInto(
                    destination = pending,
                    destinationOffset = 0,
                    startIndex = pendingOffset,
                    endIndex = pendingOffset + pendingSize,
                )
                pendingOffset = 0
            }
        }

        private fun clearPending() {
            pendingOffset = 0
            pendingSize = 0
        }

        private fun ensurePendingCapacity(additionalBytes: Int) {
            require(additionalBytes >= 0) { "AAC pending byte count must not be negative." }
            if (pendingOffset + pendingSize + additionalBytes <= pending.size) return
            if (pendingSize + additionalBytes <= pending.size) {
                pending.copyInto(
                    destination = pending,
                    destinationOffset = 0,
                    startIndex = pendingOffset,
                    endIndex = pendingOffset + pendingSize,
                )
                pendingOffset = 0
                return
            }
            var capacity = pending.size.coerceAtLeast(INITIAL_PENDING_BYTES)
            while (capacity < pendingSize + additionalBytes) {
                capacity = (capacity * 2).coerceAtMost(MAX_PENDING_BYTES)
                check(capacity >= pendingSize + additionalBytes) {
                    "AAC decoder pending PCM exceeds its bounded capacity."
                }
            }
            val expanded = ByteArray(capacity)
            pending.copyInto(
                destination = expanded,
                destinationOffset = 0,
                startIndex = pendingOffset,
                endIndex = pendingOffset + pendingSize,
            )
            pending = expanded
            pendingOffset = 0
        }

        private fun availableBytes(): Int = pendingSize

        private companion object {
            const val BYTES_PER_SAMPLE = 2
            const val STEADY_OUTPUT_POLL_TIMEOUT_US = 10_000L
            const val STEADY_INPUT_BURST = 1
            const val MAX_IDLE_POLLS = 1_000
            const val MAX_DECODE_WALL_TIME_NS = 2_000_000_000L
            const val INITIAL_PENDING_BYTES = 64 * 1_024
            const val MAX_PENDING_BYTES = 512 * 1_024
            const val MIN_COMPACT_REMAINING_BYTES = 16 * 1_024

            fun frameToUs(frame: Long, sampleRate: Int): Long =
                frame * MICROS_PER_SECOND / sampleRate

            fun usToFrame(timeUs: Long, sampleRate: Int): Long =
                timeUs * sampleRate / MICROS_PER_SECOND
        }
    }
}
