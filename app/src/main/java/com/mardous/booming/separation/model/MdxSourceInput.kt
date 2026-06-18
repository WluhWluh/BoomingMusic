package com.mardous.booming.separation.model

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.mardous.booming.separation.audio.AudioPcmDecoder
import com.mardous.booming.separation.audio.AudioSourceInfo
import com.mardous.booming.separation.audio.DecodedPcmAudio
import com.mardous.booming.separation.audio.WindowDecodedPcmAudio
import java.io.File
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal interface MdxSourceInput {
    val diagnostics: MdxSourceDecodeDiagnostics
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

    fun sourceAudioFingerprint(
        timing: MdxRangeTimingAccumulator,
        shouldCancel: () -> Boolean,
    ): String

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
            var fallbackReason: String? = if (sourceInfo.frameCount == null) {
                "Source duration is unavailable."
            } else {
                MdxWindowDecodeProfile.fallbackReason(sourceInfo, displayName, config)
            }
            if (windowProfile != null && sourceInfo.frameCount != null) {
                val input = WindowDecodeMdxSourceInput(
                    context = context,
                    decoder = decoder,
                    uri = uri,
                    config = config,
                    sourceInfo = sourceInfo,
                    profile = windowProfile,
                )
                try {
                    onProgress(
                        MdxRangeProgress.preparing(
                            stage = "Checking ${windowProfile.displayName} window decoding",
                            diagnostics = input.diagnostics,
                        )
                    )
                    input.preflight(timing, shouldCancel)
                    throwIfCanceled(shouldCancel)
                    onProgress(
                        MdxRangeProgress.preparing(
                            stage = "Using ${windowProfile.displayName} window decoding",
                            diagnostics = input.diagnostics,
                        )
                    )
                    return input
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    fallbackReason = "Window decode preflight failed for " +
                            "${windowProfile.displayName}: ${error.fallbackMessage()}"
                }
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
                decoder = decoder,
                uri = uri,
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
    private val decoder: AudioPcmDecoder,
    private val uri: Uri,
    private val source: DecodedPcmAudio,
    private val decoded: DecodedPcmAudio,
    override val diagnostics: MdxSourceDecodeDiagnostics,
) : MdxSourceInput {
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

    override fun sourceAudioFingerprint(
        timing: MdxRangeTimingAccumulator,
        shouldCancel: () -> Boolean,
    ): String {
        return encodedAudioFingerprint(decoder, uri, timing, shouldCancel)
    }
}

private class WindowDecodeMdxSourceInput(
    private val context: Context,
    private val decoder: AudioPcmDecoder,
    private val uri: Uri,
    private val config: MdxDspConfig,
    private val sourceInfo: AudioSourceInfo,
    private val profile: MdxWindowDecodeProfile,
) : MdxSourceInput {
    private val safeSourceFrameCount: Int = sourceInfo.frameCount ?: 0
    private var mp3NoGaplessCalibration: Mp3NoGaplessCalibration? = null

    override val diagnostics: MdxSourceDecodeDiagnostics
        get() = baseDiagnostics.copy(
            calibration = mp3NoGaplessCalibration?.toDisplayText(),
        )

    private val baseDiagnostics: MdxSourceDecodeDiagnostics = MdxSourceDecodeDiagnostics(
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
    override val sourceFrameCount: Int = safeSourceFrameCount
    override val sourceSampleRate: Int = sourceInfo.sampleRate
    override val sourceChannelCount: Int = sourceInfo.channelCount
    override val outputFrameCount: Int = targetFrameCountFor(
        sourceFrameCount = safeSourceFrameCount,
        sourceSampleRate = sourceInfo.sampleRate,
        targetSampleRate = config.sampleRate,
    )

    fun preflight(
        timing: MdxRangeTimingAccumulator,
        shouldCancel: () -> Boolean,
    ) {
        if (profile == MdxWindowDecodeProfile.Mp3_44100_NoGaplessQuantized) {
            mp3NoGaplessCalibration = measureElapsed(timing, "Window calibration") {
                Mp3NoGaplessCalibrationGate(
                    context = context,
                    decoder = decoder,
                    uri = uri,
                    sourceInfo = sourceInfo,
                    config = config,
                ).loadOrCalibrate(shouldCancel)
            }
        }
        decodeStereoFloatContextWindow(
            windowStartFrame = 0,
            frames = minOf(PREFLIGHT_TARGET_FRAMES, outputFrameCount).coerceAtLeast(1),
            timing = timing,
            shouldCancel = shouldCancel,
            decodeStage = "Window preflight decode",
            resampleStage = "Window preflight resample",
        )
    }

    override fun sourceAudioFingerprint(
        timing: MdxRangeTimingAccumulator,
        shouldCancel: () -> Boolean,
    ): String {
        return encodedAudioFingerprint(decoder, uri, timing, shouldCancel)
    }

    override fun toStereoFloatContextWindow(
        windowStartFrame: Int,
        frames: Int,
        timing: MdxRangeTimingAccumulator,
        shouldCancel: () -> Boolean,
    ): Array<FloatArray> {
        return decodeStereoFloatContextWindow(
            windowStartFrame = windowStartFrame,
            frames = frames,
            timing = timing,
            shouldCancel = shouldCancel,
            decodeStage = "Window decode",
            resampleStage = "Window resample",
        )
    }

    private fun decodeStereoFloatContextWindow(
        windowStartFrame: Int,
        frames: Int,
        timing: MdxRangeTimingAccumulator,
        shouldCancel: () -> Boolean,
        decodeStage: String,
        resampleStage: String,
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

        val decodedWindow = measureElapsed(timing, decodeStage) {
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
                MdxWindowDecodeProfile.Flac_44100_TimestampSongTimeline,
                MdxWindowDecodeProfile.Mp3_44100_MetadataQuantized,
                MdxWindowDecodeProfile.Mp3_44100_NoGaplessQuantized -> {
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
            MdxWindowDecodeProfile.Flac_44100_TimestampSongTimeline -> {
                sourceWindowStartFrame + decodedWindow.timestampPlacementOffsetFrames(
                    requestedStartUs = requestedStartUs,
                    sampleRate = sourceInfo.sampleRate,
                )
            }
            MdxWindowDecodeProfile.Mp3_44100_MetadataQuantized,
            MdxWindowDecodeProfile.Mp3_44100_NoGaplessQuantized -> {
                sourceWindowStartFrame + decodedWindow.mp3QuantizedPlacementOffsetFrames(
                    requestedSourceFrameCount = sourceWindowEndFrame - sourceWindowStartFrame,
                    sourceInfo = sourceInfo,
                )
            }
        }
        return measureElapsed(timing, resampleStage) {
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
    Flac_44100_TimestampSongTimeline("FLAC 44.1 kHz", experimental = true),
    Mp3_44100_MetadataQuantized("MP3 44.1 kHz", experimental = true),
    Mp3_44100_NoGaplessQuantized("MP3 44.1 kHz no-gapless calibrated", experimental = true);

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
                sourceInfo.mimeType == FLAC_MIME_TYPE &&
                        sourceInfo.sampleRate == config.sampleRate &&
                        sourceInfo.sampleRate == 44_100 ->
                    Flac_44100_TimestampSongTimeline
                sourceInfo.mimeType == MP3_MIME_TYPE &&
                        sourceInfo.sampleRate == config.sampleRate &&
                        sourceInfo.sampleRate == 44_100 &&
                        sourceInfo.trackMetadata.encoderDelayFrames != null &&
                        sourceInfo.trackMetadata.encoderPaddingFrames != null ->
                    Mp3_44100_MetadataQuantized
                sourceInfo.mimeType == MP3_MIME_TYPE &&
                        sourceInfo.sampleRate == config.sampleRate &&
                        sourceInfo.sampleRate == 44_100 &&
                        sourceInfo.trackMetadata.encoderDelayFrames == null &&
                        sourceInfo.trackMetadata.encoderPaddingFrames == null ->
                    Mp3_44100_NoGaplessQuantized
                else -> null
            }
        }

        private const val WAV_MIME_TYPE = "audio/raw"
        private const val OGG_VORBIS_MIME_TYPE = "audio/vorbis"
        private const val FLAC_MIME_TYPE = "audio/flac"
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
                sourceInfo.mimeType == FLAC_MIME_TYPE && sourceInfo.sampleRate != config.sampleRate ->
                    "FLAC window decode is currently enabled only for 44.1 kHz sources."
                sourceInfo.mimeType == MP3_MIME_TYPE && sourceInfo.sampleRate != config.sampleRate ->
                    "MP3 window decode is currently enabled only for 44.1 kHz sources."
                sourceInfo.mimeType == MP3_MIME_TYPE &&
                        (sourceInfo.trackMetadata.encoderDelayFrames == null ||
                                sourceInfo.trackMetadata.encoderPaddingFrames == null) ->
                    "MP3 no-gapless calibration has not passed yet."
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
    val calibration: String? = null,
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
            calibration?.let {
                appendLine()
                append("Calibration: ")
                append(it)
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

private fun WindowDecodedPcmAudio.timestampPlacementOffsetFrames(
    requestedStartUs: Long,
    sampleRate: Int,
): Int {
    val firstOutputUs = firstOutputTimeUs
        ?: error("Window decoder produced no output timestamp.")
    return floor(
        (firstOutputUs - requestedStartUs).toDouble() *
                sampleRate.toDouble() /
                MICROS_PER_SECOND.toDouble()
    ).toInt().coerceAtLeast(0)
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

private fun encodedAudioFingerprint(
    decoder: AudioPcmDecoder,
    uri: Uri,
    timing: MdxRangeTimingAccumulator,
    shouldCancel: () -> Boolean,
): String {
    val hash = measureElapsed(timing, "Source audio fingerprint") {
        decoder.hashEncodedAudioSamples(uri, shouldCancel)
    }
    return "encoded-samples-v1:${hash.sha256}"
}

private fun Throwable.fallbackMessage(): String {
    return message
        ?.lineSequence()
        ?.firstOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: this::class.java.simpleName
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

private class Mp3NoGaplessCalibrationGate(
    private val context: Context,
    private val decoder: AudioPcmDecoder,
    private val uri: Uri,
    private val sourceInfo: AudioSourceInfo,
    private val config: MdxDspConfig,
) {
    fun loadOrCalibrate(shouldCancel: () -> Boolean): Mp3NoGaplessCalibration {
        val fingerprint = decoder.hashEncodedAudioSamples(uri, shouldCancel).sha256
        val cacheFile = File(cacheDir(), "$fingerprint.properties")
        readCache(cacheFile)?.let { return it }
        val calibration = calibrate(fingerprint, shouldCancel)
        writeCache(cacheFile, calibration)
        return calibration
    }

    private fun calibrate(
        fingerprint: String,
        shouldCancel: () -> Boolean,
    ): Mp3NoGaplessCalibration {
        val frameCount = sourceInfo.frameCount
            ?: error("Source frame count is unavailable for MP3 no-gapless calibration.")
        val plans = buildCalibrationPlans(frameCount)
        require(plans.isNotEmpty()) { "No MP3 no-gapless calibration windows were available." }

        val observations = plans.map { plan ->
            throwIfCanceled(shouldCancel)
            decodeObservation(
                sourceStartFrame = plan.startFrame,
                requestedFrames = plan.requestedFrames,
                shouldCancel = shouldCancel,
            )
        }

        val rawDeficits = observations.map { it.frameDeficitFrames }
        val quantizedOffsets = observations.map { it.quantizedOffsetFrames }
        val rawMedian = medianRounded(rawDeficits)
        val rawSpread = spreadFrames(rawDeficits)
        val quantizedMedian = medianRounded(quantizedOffsets)
        val quantizedSpread = spreadFrames(quantizedOffsets)
        return Mp3NoGaplessCalibration(
            fingerprint = fingerprint,
            version = MP3_NO_GAPLESS_CALIBRATION_VERSION,
            probeCount = observations.size,
            rawMedianFrameDeficitFrames = rawMedian,
            rawSpreadFrames = rawSpread,
            quantizedMedianPlacementFrames = quantizedMedian,
            quantizedSpreadFrames = quantizedSpread,
            quantumFrames = MP3_FINE_QUANTUM_FRAMES,
        )
    }

    private fun decodeObservation(
        sourceStartFrame: Int,
        requestedFrames: Int,
        shouldCancel: () -> Boolean,
    ): Mp3NoGaplessCalibrationObservation {
        val sourceEndFrame = sourceStartFrame + requestedFrames
        val decoded = decoder.decodeWindow(
            uri = uri,
            startUs = frameToUs(sourceStartFrame, sourceInfo.sampleRate),
            endUs = frameToUs(sourceEndFrame, sourceInfo.sampleRate),
            shouldCancel = shouldCancel,
        )
        require(decoded.audio.frameCount > 0) {
            "MP3 no-gapless calibration decoded no PCM frames."
        }
        val frameDeficit = (requestedFrames - decoded.audio.frameCount).coerceAtLeast(0)
        val placementOffset = decoded.mp3QuantizedPlacementOffsetFrames(
            requestedSourceFrameCount = requestedFrames,
            sourceInfo = sourceInfo,
        )
        return Mp3NoGaplessCalibrationObservation(
            startFrame = sourceStartFrame,
            requestedFrames = requestedFrames,
            decodedFrames = decoded.audio.frameCount,
            frameDeficitFrames = frameDeficit,
            quantizedOffsetFrames = placementOffset,
        )
    }

    private fun buildCalibrationPlans(sourceFrameCount: Int): List<Mp3NoGaplessCalibrationPlan> {
        val requestedFrames = minOf(PREFLIGHT_TARGET_FRAMES, sourceFrameCount).coerceAtLeast(1)
        val candidateStarts = listOf(
            0,
            config.generationSize,
            config.generationSize * 2,
            sourceFrameCount / 2,
            (sourceFrameCount - requestedFrames).coerceAtLeast(0),
        )
        return candidateStarts.map { rawStart ->
            val startFrame = rawStart.coerceIn(0, (sourceFrameCount - requestedFrames).coerceAtLeast(0))
            Mp3NoGaplessCalibrationPlan(
                startFrame = startFrame,
                requestedFrames = minOf(requestedFrames, sourceFrameCount - startFrame).coerceAtLeast(1),
            )
        }.distinctBy { it.startFrame to it.requestedFrames }
    }

    private fun cacheDir(): File {
        return File(context.cacheDir, "source-separation/mp3-no-gapless-calibration").also {
            it.mkdirs()
        }
    }

    private fun readCache(file: File): Mp3NoGaplessCalibration? {
        if (!file.isFile) return null
        val expectedFingerprint = file.nameWithoutExtension
        val values = file.readLines(Charsets.UTF_8)
            .mapNotNull { line ->
                val index = line.indexOf('=')
                if (index <= 0) null else line.substring(0, index) to line.substring(index + 1)
            }
            .toMap()
        if (values["version"] != MP3_NO_GAPLESS_CALIBRATION_VERSION.toString()) return null
        if (values["fingerprint"] != expectedFingerprint) return null
        return Mp3NoGaplessCalibration(
            fingerprint = values["fingerprint"] ?: return null,
            version = values["version"]?.toIntOrNull() ?: return null,
            probeCount = values["probeCount"]?.toIntOrNull() ?: return null,
            rawMedianFrameDeficitFrames = values["rawMedianFrameDeficitFrames"]?.toIntOrNull() ?: return null,
            rawSpreadFrames = values["rawSpreadFrames"]?.toIntOrNull() ?: return null,
            quantizedMedianPlacementFrames = values["quantizedMedianPlacementFrames"]?.toIntOrNull()
                ?: return null,
            quantizedSpreadFrames = values["quantizedSpreadFrames"]?.toIntOrNull() ?: return null,
            quantumFrames = values["quantumFrames"]?.toIntOrNull() ?: return null,
        )
    }

    private fun writeCache(file: File, calibration: Mp3NoGaplessCalibration) {
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(
            buildString {
                appendLine("version=${calibration.version}")
                appendLine("fingerprint=${calibration.fingerprint}")
                appendLine("probeCount=${calibration.probeCount}")
                appendLine("rawMedianFrameDeficitFrames=${calibration.rawMedianFrameDeficitFrames}")
                appendLine("rawSpreadFrames=${calibration.rawSpreadFrames}")
                appendLine("quantizedMedianPlacementFrames=${calibration.quantizedMedianPlacementFrames}")
                appendLine("quantizedSpreadFrames=${calibration.quantizedSpreadFrames}")
                appendLine("quantumFrames=${calibration.quantumFrames}")
            },
            Charsets.UTF_8,
        )
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }
}

private data class Mp3NoGaplessCalibration(
    val fingerprint: String,
    val version: Int,
    val probeCount: Int,
    val rawMedianFrameDeficitFrames: Int,
    val rawSpreadFrames: Int,
    val quantizedMedianPlacementFrames: Int,
    val quantizedSpreadFrames: Int,
    val quantumFrames: Int,
) {
    fun toDisplayText(): String {
        return "MP3 no-gapless pass, probes=$probeCount, rawSpread=$rawSpreadFrames, " +
                "quantizedSpread=$quantizedSpreadFrames"
    }
}

private data class Mp3NoGaplessCalibrationPlan(
    val startFrame: Int,
    val requestedFrames: Int,
)

private data class Mp3NoGaplessCalibrationObservation(
    val startFrame: Int,
    val requestedFrames: Int,
    val decodedFrames: Int,
    val frameDeficitFrames: Int,
    val quantizedOffsetFrames: Int,
)

private fun medianRounded(values: List<Int>): Int {
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) {
        sorted[middle]
    } else {
        ((sorted[middle - 1] + sorted[middle]).toDouble() / 2.0).roundToInt()
    }
}

private fun spreadFrames(values: List<Int>): Int {
    if (values.isEmpty()) return 0
    return values.maxOrNull()!! - values.minOrNull()!!
}

private const val PREFLIGHT_TARGET_FRAMES = 44_100
private const val MICROS_PER_SECOND = 1_000_000L
private const val CANCEL_CHECK_INTERVAL_FRAMES = 16_384
private const val MP3_FINE_QUANTUM_FRAMES = 384
private const val MP3_NO_GAPLESS_CALIBRATION_VERSION = 1
private const val PCM_FLOAT_SCALE = 32768f
