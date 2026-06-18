package com.mardous.booming.separation.model

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.mardous.booming.separation.audio.AudioPcmDecoder
import com.mardous.booming.separation.audio.AudioSourceInfo
import com.mardous.booming.separation.audio.DecodedPcmAudio
import com.mardous.booming.separation.audio.WindowDecodedPcmAudio
import com.mardous.booming.separation.cache.SourceSeparationCache
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal interface MdxSourceInput {
    val diagnostics: MdxSourceDecodeDiagnostics
    val sourcePcmSha256: String
    val sourceFrameCount: Int
    val sourceSampleRate: Int
    val sourceChannelCount: Int
    val outputFrameCount: Int

    fun toStereoFloatContextWindow(
        windowStartFrame: Int,
        frames: Int,
        timing: MdxRangeTimingAccumulator,
        shouldCancel: () -> Boolean,
    ): Array<FloatArray>

    companion object {
        fun create(
            context: Context,
            config: MdxDspConfig,
            uri: Uri,
            displayName: String,
            timing: MdxRangeTimingAccumulator,
            onProgress: (MdxRangeProgress) -> Unit,
            shouldCancel: () -> Boolean,
        ): MdxSourceInput {
            val decoder = AudioPcmDecoder(context)
            onProgress(MdxRangeProgress.preparing("Inspecting source audio"))
            val sourceInfo = measureElapsed(timing, "Inspect source") {
                decoder.inspect(uri)
            }
            throwIfCanceled(shouldCancel)

            val windowProfile = MdxWindowDecodeProfile.forSource(sourceInfo, displayName, config)
            if (windowProfile != null && sourceInfo.frameCount != null) {
                val input = WindowDecodeMdxSourceInput(
                    decoder = decoder,
                    uri = uri,
                    config = config,
                    sourceInfo = sourceInfo,
                    profile = windowProfile,
                )
                onProgress(
                    MdxRangeProgress.preparing(
                        stage = "Using ${windowProfile.displayName} window decoding",
                        diagnostics = input.diagnostics,
                    )
                )
                return input
            }

            val fallbackReason = if (sourceInfo.frameCount == null) {
                "Source duration is unavailable."
            } else {
                MdxWindowDecodeProfile.fallbackReason(sourceInfo, displayName, config)
            }
            val fallbackDiagnostics = MdxSourceDecodeDiagnostics(
                mode = MdxSourceDecodeMode.FullSong,
                profile = null,
                mimeType = sourceInfo.mimeType,
                sampleRate = sourceInfo.sampleRate,
                channelCount = sourceInfo.channelCount,
                sourceFrameCount = sourceInfo.frameCount,
                outputFrameCount = null,
                fallbackReason = fallbackReason,
            )
            onProgress(
                MdxRangeProgress.preparing(
                    stage = "Decoding source audio",
                    diagnostics = fallbackDiagnostics,
                )
            )
            val source = measureElapsed(timing, "Decode") {
                decoder.decode(uri, shouldCancel = shouldCancel)
            }
            val sourceFrameCount = source.frameCount
            val sourceSampleRate = source.sampleRate
            val decodedOutputFrameCount = targetFrameCountFor(
                sourceFrameCount = sourceFrameCount,
                sourceSampleRate = sourceSampleRate,
                targetSampleRate = config.sampleRate,
            )
            val decodedFallbackDiagnostics = fallbackDiagnostics.copy(
                sampleRate = sourceSampleRate,
                channelCount = source.channelCount,
                sourceFrameCount = sourceFrameCount,
                outputFrameCount = decodedOutputFrameCount,
            )
            onProgress(
                MdxRangeProgress.preparing(
                    stage = "Resampling source audio",
                    diagnostics = decodedFallbackDiagnostics,
                )
            )
            val decoded = measureElapsed(timing, "Resample") {
                source.resampleTo(config.sampleRate, shouldCancel = shouldCancel)
            }
            throwIfCanceled(shouldCancel)
            return FullSongMdxSourceInput(
                source = source,
                decoded = decoded,
                diagnostics = decodedFallbackDiagnostics.copy(
                    outputFrameCount = decoded.frameCount,
                ),
            )
        }
    }
}

private class FullSongMdxSourceInput(
    private val source: DecodedPcmAudio,
    private val decoded: DecodedPcmAudio,
    override val diagnostics: MdxSourceDecodeDiagnostics,
) : MdxSourceInput {
    override val sourcePcmSha256: String = SourceSeparationCache.sha256Hex(source.pcm16)
    override val sourceFrameCount: Int = source.frameCount
    override val sourceSampleRate: Int = source.sampleRate
    override val sourceChannelCount: Int = source.channelCount
    override val outputFrameCount: Int = decoded.frameCount

    override fun toStereoFloatContextWindow(
        windowStartFrame: Int,
        frames: Int,
        timing: MdxRangeTimingAccumulator,
        shouldCancel: () -> Boolean,
    ): Array<FloatArray> {
        return measureElapsed(timing, "Window input") {
            decoded.toStereoFloatContextWindow(windowStartFrame, frames)
        }
    }
}

private class WindowDecodeMdxSourceInput(
    private val decoder: AudioPcmDecoder,
    private val uri: Uri,
    private val config: MdxDspConfig,
    private val sourceInfo: AudioSourceInfo,
    private val profile: MdxWindowDecodeProfile,
) : MdxSourceInput {
    private val safeSourceFrameCount: Int = sourceInfo.frameCount ?: 0

    override val diagnostics: MdxSourceDecodeDiagnostics = MdxSourceDecodeDiagnostics(
        mode = MdxSourceDecodeMode.Window,
        profile = profile.displayName,
        mimeType = sourceInfo.mimeType,
        sampleRate = sourceInfo.sampleRate,
        channelCount = sourceInfo.channelCount,
        sourceFrameCount = safeSourceFrameCount,
        outputFrameCount = targetFrameCountFor(
            sourceFrameCount = safeSourceFrameCount,
            sourceSampleRate = sourceInfo.sampleRate,
            targetSampleRate = config.sampleRate,
        ),
        fallbackReason = null,
        experimental = profile.experimental,
    )
    override val sourcePcmSha256: String = SourceSeparationCache.sha256Hex(
        buildString {
            append("window-decode-v1|")
            append(profile.name).append('|')
            append(sourceInfo.mimeType).append('|')
            append(sourceInfo.sampleRate).append('|')
            append(sourceInfo.channelCount).append('|')
            append(sourceInfo.durationUs).append('|')
            append(sourceInfo.trackMetadata.encoderDelayFrames).append('|')
            append(sourceInfo.trackMetadata.encoderPaddingFrames)
        }.encodeToByteArray()
    )
    override val sourceFrameCount: Int = safeSourceFrameCount
    override val sourceSampleRate: Int = sourceInfo.sampleRate
    override val sourceChannelCount: Int = sourceInfo.channelCount
    override val outputFrameCount: Int = targetFrameCountFor(
        sourceFrameCount = safeSourceFrameCount,
        sourceSampleRate = sourceInfo.sampleRate,
        targetSampleRate = config.sampleRate,
    )

    override fun toStereoFloatContextWindow(
        windowStartFrame: Int,
        frames: Int,
        timing: MdxRangeTimingAccumulator,
        shouldCancel: () -> Boolean,
    ): Array<FloatArray> {
        val sourceWindowStartFrame = targetFrameToSourceFrameFloor(
            targetFrame = windowStartFrame.coerceAtLeast(0),
            sourceSampleRate = sourceInfo.sampleRate,
            targetSampleRate = config.sampleRate,
        )
        val sourceWindowEndFrame = targetFrameToSourceFrameCeil(
            targetFrame = (windowStartFrame + frames).coerceAtLeast(0),
            sourceSampleRate = sourceInfo.sampleRate,
            targetSampleRate = config.sampleRate,
        ).coerceAtLeast(sourceWindowStartFrame + 1)
        val requestedStartUs = frameToUs(sourceWindowStartFrame, sourceInfo.sampleRate)
        val requestedEndUs = frameToUs(sourceWindowEndFrame, sourceInfo.sampleRate)

        val decodedWindow = measureElapsed(timing, "Window decode") {
            when (profile) {
                MdxWindowDecodeProfile.WavPrerollSongTimeline,
                MdxWindowDecodeProfile.OggVorbisPrerollSongTimeline -> {
                    decoder.decodeWindowWithPrerollCursor(
                        uri = uri,
                        startUs = requestedStartUs,
                        endUs = requestedEndUs,
                        prerollUs = frameToUs(config.trim, config.sampleRate),
                        shouldCancel = shouldCancel,
                    )
                }
                MdxWindowDecodeProfile.Mp3_44100_MetadataQuantized -> {
                    decoder.decodeWindow(
                        uri = uri,
                        startUs = requestedStartUs,
                        endUs = requestedEndUs,
                        shouldCancel = shouldCancel,
                    )
                }
            }
        }

        val placedSourceWindowStartFrame = when (profile) {
            MdxWindowDecodeProfile.WavPrerollSongTimeline,
            MdxWindowDecodeProfile.OggVorbisPrerollSongTimeline -> sourceWindowStartFrame
            MdxWindowDecodeProfile.Mp3_44100_MetadataQuantized -> {
                sourceWindowStartFrame + decodedWindow.mp3QuantizedPlacementOffsetFrames(
                    requestedSourceFrameCount = sourceWindowEndFrame - sourceWindowStartFrame,
                    sourceInfo = sourceInfo,
                )
            }
        }
        return measureElapsed(timing, "Window resample") {
            decodedWindow.audio.toStereoFloatTargetWindowOnSongTimeline(
                targetWindowStartFrame = windowStartFrame,
                frames = frames,
                targetSampleRate = config.sampleRate,
                sourceWindowStartFrame = placedSourceWindowStartFrame,
                shouldCancel = shouldCancel,
            )
        }
    }
}

private enum class MdxWindowDecodeProfile(
    val displayName: String,
    val experimental: Boolean = false,
) {
    WavPrerollSongTimeline("WAV"),
    OggVorbisPrerollSongTimeline("Ogg Vorbis"),
    Mp3_44100_MetadataQuantized("MP3 44.1 kHz", experimental = true);

    companion object {
        fun forSource(
            sourceInfo: AudioSourceInfo,
            displayName: String,
            config: MdxDspConfig,
        ): MdxWindowDecodeProfile? {
            val lowerName = displayName.lowercase(Locale.US)
            return when {
                sourceInfo.mimeType == WAV_MIME_TYPE && lowerName.endsWith(".wav") ->
                    WavPrerollSongTimeline
                sourceInfo.mimeType == OGG_VORBIS_MIME_TYPE ->
                    OggVorbisPrerollSongTimeline
                sourceInfo.mimeType == MP3_MIME_TYPE &&
                        sourceInfo.sampleRate == config.sampleRate &&
                        sourceInfo.sampleRate == 44_100 &&
                        sourceInfo.trackMetadata.encoderDelayFrames != null &&
                        sourceInfo.trackMetadata.encoderPaddingFrames != null ->
                    Mp3_44100_MetadataQuantized
                else -> null
            }
        }

        private const val WAV_MIME_TYPE = "audio/raw"
        private const val OGG_VORBIS_MIME_TYPE = "audio/vorbis"
        private const val MP3_MIME_TYPE = "audio/mpeg"

        fun fallbackReason(
            sourceInfo: AudioSourceInfo,
            displayName: String,
            config: MdxDspConfig,
        ): String {
            val lowerName = displayName.lowercase(Locale.US)
            return when {
                sourceInfo.mimeType == WAV_MIME_TYPE && !lowerName.endsWith(".wav") ->
                    "WAV MIME was reported without a .wav file name."
                sourceInfo.mimeType == MP3_MIME_TYPE && sourceInfo.sampleRate != config.sampleRate ->
                    "MP3 window decode is currently enabled only for 44.1 kHz sources."
                sourceInfo.mimeType == MP3_MIME_TYPE &&
                        (sourceInfo.trackMetadata.encoderDelayFrames == null ||
                                sourceInfo.trackMetadata.encoderPaddingFrames == null) ->
                    "MP3 encoder delay or padding metadata is unavailable."
                else ->
                    "Window decode is not enabled for this MIME/sample-rate profile."
            }
        }
    }
}

data class MdxSourceDecodeDiagnostics(
    val mode: MdxSourceDecodeMode,
    val profile: String?,
    val mimeType: String,
    val sampleRate: Int,
    val channelCount: Int,
    val sourceFrameCount: Int?,
    val outputFrameCount: Int?,
    val fallbackReason: String?,
    val experimental: Boolean = false,
) {
    fun toDisplayText(): String {
        return buildString {
            append("Decode: ")
            append(
                when (mode) {
                    MdxSourceDecodeMode.Window -> "window"
                    MdxSourceDecodeMode.FullSong -> "full song"
                }
            )
            profile?.let { append(" ($it)") }
            if (experimental) append(" [experimental]")
            appendLine()
            append("Source: ")
            append(mimeType)
            append(", ")
            append(sampleRate)
            append(" Hz, ")
            append(channelCount)
            append(if (channelCount == 1) " channel" else " channels")
            if (sourceFrameCount != null || outputFrameCount != null) {
                appendLine()
                append("Frames: source=")
                append(sourceFrameCount ?: "unknown")
                append(", output=")
                append(outputFrameCount ?: "unknown")
            }
            fallbackReason?.let {
                appendLine()
                append("Fallback: ")
                append(it)
            }
        }
    }
}

enum class MdxSourceDecodeMode {
    FullSong,
    Window,
}

private fun WindowDecodedPcmAudio.mp3QuantizedPlacementOffsetFrames(
    requestedSourceFrameCount: Int,
    sourceInfo: AudioSourceInfo,
): Int {
    val frameDeficit = (requestedSourceFrameCount - audio.frameCount).coerceAtLeast(0)
    val correction = -(
            sourceInfo.trackMetadata.encoderDelayFrames.orZero() +
                    sourceInfo.trackMetadata.encoderPaddingFrames.orZero()
            )
    return ceilToMultiple(frameDeficit + correction, MP3_FINE_QUANTUM_FRAMES)
        .coerceAtLeast(0)
}

private fun Int?.orZero(): Int = this ?: 0

private fun DecodedPcmAudio.toStereoFloatContextWindow(
    windowStartFrame: Int,
    frames: Int,
): Array<FloatArray> {
    val windowEndFrame = windowStartFrame + frames
    val copyStartFrame = maxOf(0, windowStartFrame)
    val copyEndFrame = minOf(frameCount, windowEndFrame)
    val window = Array(MdxDspConfig.STEREO_CHANNELS) { FloatArray(frames) }
    if (copyEndFrame <= copyStartFrame) return window

    val source = toStereoFloat(
        startFrame = copyStartFrame,
        maxFrames = copyEndFrame - copyStartFrame,
    )
    val destinationOffset = copyStartFrame - windowStartFrame
    for (channel in 0 until MdxDspConfig.STEREO_CHANNELS) {
        source[channel].copyInto(
            destination = window[channel],
            destinationOffset = destinationOffset,
        )
    }
    return window
}

private fun DecodedPcmAudio.toStereoFloatTargetWindowOnSongTimeline(
    targetWindowStartFrame: Int,
    frames: Int,
    targetSampleRate: Int,
    sourceWindowStartFrame: Int,
    shouldCancel: () -> Boolean,
): Array<FloatArray> {
    val window = Array(MdxDspConfig.STEREO_CHANNELS) { FloatArray(frames) }
    if (frameCount == 0) return window

    for (targetFrameInWindow in 0 until frames) {
        if (targetFrameInWindow % CANCEL_CHECK_INTERVAL_FRAMES == 0) {
            throwIfCanceled(shouldCancel)
        }
        val globalTargetFrame = targetWindowStartFrame + targetFrameInWindow
        if (globalTargetFrame < 0) continue

        val globalSourcePosition = globalTargetFrame.toDouble() *
                sampleRate.toDouble() / targetSampleRate.toDouble()
        val localSourcePosition = globalSourcePosition - sourceWindowStartFrame.toDouble()
        if (localSourcePosition < 0.0 || localSourcePosition > (frameCount - 1).toDouble()) {
            continue
        }

        val sourceFrame = floor(localSourcePosition).toInt().coerceIn(0, frameCount - 1)
        val nextFrame = (sourceFrame + 1).coerceAtMost(frameCount - 1)
        val fraction = (localSourcePosition - sourceFrame).toFloat()
        for (channel in 0 until MdxDspConfig.STEREO_CHANNELS) {
            val sourceChannel = channel.coerceAtMost(channelCount - 1)
            val current = readLittleEndianShort(frameByteIndex(sourceFrame, sourceChannel)).toFloat()
            val next = readLittleEndianShort(frameByteIndex(nextFrame, sourceChannel)).toFloat()
            window[channel][targetFrameInWindow] =
                (current + (next - current) * fraction) / PCM_FLOAT_SCALE
        }
    }
    return window
}

private fun DecodedPcmAudio.frameByteIndex(frame: Int, channel: Int): Int {
    return (frame * channelCount + channel) * Short.SIZE_BYTES
}

private fun DecodedPcmAudio.readLittleEndianShort(byteIndex: Int): Short {
    val low = pcm16[byteIndex].toInt() and 0xFF
    val high = pcm16[byteIndex + 1].toInt()
    return ((high shl 8) or low).toShort()
}

private inline fun <T> measureElapsed(
    timing: MdxRangeTimingAccumulator,
    stage: String,
    block: () -> T,
): T {
    val startedAt = SystemClock.elapsedRealtime()
    return try {
        block()
    } finally {
        timing.add(stage, SystemClock.elapsedRealtime() - startedAt)
    }
}

private fun throwIfCanceled(shouldCancel: () -> Boolean) {
    if (shouldCancel()) {
        throw CancellationException("Source separation canceled.")
    }
}

private fun frameToUs(frame: Int, sampleRate: Int): Long {
    return (frame.toLong() * MICROS_PER_SECOND) / sampleRate.toLong()
}

private fun targetFrameToSourceFrameFloor(
    targetFrame: Int,
    sourceSampleRate: Int,
    targetSampleRate: Int,
): Int {
    return floor(targetFrame.toDouble() * sourceSampleRate.toDouble() / targetSampleRate.toDouble())
        .toInt()
        .coerceAtLeast(0)
}

private fun targetFrameToSourceFrameCeil(
    targetFrame: Int,
    sourceSampleRate: Int,
    targetSampleRate: Int,
): Int {
    return ceil(targetFrame.toDouble() * sourceSampleRate.toDouble() / targetSampleRate.toDouble())
        .toInt()
        .coerceAtLeast(0)
}

private fun targetFrameCountFor(
    sourceFrameCount: Int,
    sourceSampleRate: Int,
    targetSampleRate: Int,
): Int {
    return (sourceFrameCount.toDouble() * targetSampleRate.toDouble() / sourceSampleRate.toDouble())
        .roundToLong()
        .coerceIn(0L, Int.MAX_VALUE.toLong())
        .toInt()
}

private fun ceilToMultiple(value: Int, quantum: Int): Int {
    if (quantum <= 0 || value <= 0) return value
    return ((value + quantum - 1) / quantum) * quantum
}

private const val MICROS_PER_SECOND = 1_000_000L
private const val CANCEL_CHECK_INTERVAL_FRAMES = 16_384
private const val MP3_FINE_QUANTUM_FRAMES = 384
private const val PCM_FLOAT_SCALE = 32768f
