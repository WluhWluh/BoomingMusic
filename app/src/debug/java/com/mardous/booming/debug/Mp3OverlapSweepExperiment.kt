package com.mardous.booming.debug

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.mardous.booming.separation.audio.AudioPcmDecoder
import com.mardous.booming.separation.audio.AudioSourceInfo
import com.mardous.booming.separation.audio.DecodedPcmAudio
import com.mardous.booming.separation.audio.WindowDecodedPcmAudio
import com.mardous.booming.separation.cache.SourceSeparationSegmentPlan
import com.mardous.booming.separation.model.MdxDspConfig
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.math.sqrt

class Mp3OverlapSweepExperiment(
    private val context: Context,
    private val config: MdxDspConfig = MdxDspConfig(),
) {
    fun run(
        uri: Uri,
        displayName: String,
        reportDir: File,
        onProgress: (String) -> Unit = {},
    ): Mp3OverlapSweepResult {
        reportDir.mkdirs()
        val decoder = AudioPcmDecoder(context)
        onProgress("Inspecting source audio")
        val sourceInfo = decoder.inspect(uri)
        require(sourceInfo.mimeType == MP3_MIME_TYPE) {
            "Current source is not MP3: ${sourceInfo.mimeType}"
        }
        require(sourceInfo.sampleRate == config.sampleRate) {
            "Current MP3 sample rate is ${sourceInfo.sampleRate}, expected ${config.sampleRate}."
        }
        val sourceFrameCount = sourceInfo.frameCount
            ?: error("Source frame count is unavailable.")
        val outputFrameCount = targetFrameCountFor(
            sourceFrameCount = sourceFrameCount,
            sourceSampleRate = sourceInfo.sampleRate,
            targetSampleRate = config.sampleRate,
        )
        val segmentPlan = SourceSeparationSegmentPlan.build(
            rangeStartFrame = 0,
            rangeEndFrame = outputFrameCount,
            sampleRate = config.sampleRate,
            generationSize = config.generationSize,
            trim = config.trim,
            chunkSize = config.chunkSize,
        )

        var previousIndex: Int? = null
        var previousWindow: Array<FloatArray>? = null
        val comparisons = mutableListOf<Mp3OverlapSweepComparison>()
        val startedAt = SystemClock.elapsedRealtime()
        segmentPlan.segments.forEachIndexed { index, segment ->
            onProgress("Decoding MP3 overlap sweep window ${index + 1}/${segmentPlan.segmentCount}")
            val window = decodeMixWindow(
                decoder = decoder,
                uri = uri,
                sourceInfo = sourceInfo,
                targetWindowStartFrame = segment.windowStartFrame,
                frames = config.chunkSize,
            )
            val lowerIndex = previousIndex
            val lowerWindow = previousWindow
            if (lowerIndex != null && lowerWindow != null) {
                comparisons += compareAdjacent(
                    lowerWindow = lowerWindow,
                    upperWindow = window,
                    lowerSegmentIndex = lowerIndex,
                    upperSegmentIndex = index,
                )
            }
            previousIndex = index
            previousWindow = window
        }

        val result = Mp3OverlapSweepResult(
            displayName = displayName,
            sourceInfo = sourceInfo,
            segmentCount = segmentPlan.segmentCount,
            comparisons = comparisons,
            elapsedMs = SystemClock.elapsedRealtime() - startedAt,
            reportFile = uniqueFile(
                reportDir,
                "${displayName.toSafeFileStem()}_mp3_overlap_sweep.txt",
            ),
        )
        result.reportFile.writeText(result.toReportText(), Charsets.UTF_8)
        return result
    }

    private fun decodeMixWindow(
        decoder: AudioPcmDecoder,
        uri: Uri,
        sourceInfo: AudioSourceInfo,
        targetWindowStartFrame: Int,
        frames: Int,
    ): Array<FloatArray> {
        val sourceWindowStartFrame = targetFrameToSourceFrameFloor(
            targetFrame = targetWindowStartFrame.coerceAtLeast(0),
            sourceSampleRate = sourceInfo.sampleRate,
            targetSampleRate = config.sampleRate,
        )
        val sourceWindowEndFrame = targetFrameToSourceFrameCeil(
            targetFrame = (targetWindowStartFrame + frames).coerceAtLeast(0),
            sourceSampleRate = sourceInfo.sampleRate,
            targetSampleRate = config.sampleRate,
        ).coerceAtLeast(sourceWindowStartFrame + 1)
        val decodedWindow = decodeWindowWithFallback(
            decoder = decoder,
            uri = uri,
            requestedStartUs = frameToUs(sourceWindowStartFrame, sourceInfo.sampleRate),
            requestedEndUs = frameToUs(sourceWindowEndFrame, sourceInfo.sampleRate),
        )
        val placedSourceWindowStartFrame = sourceWindowStartFrame +
                decodedWindow.mp3QuantizedPlacementOffsetFrames(
                    requestedSourceFrameCount = mp3PlacementReferenceFrameCount(
                        sourceWindowStartFrame = sourceWindowStartFrame,
                        sourceWindowEndFrame = sourceWindowEndFrame,
                        sourceInfo = sourceInfo,
                    ),
                    sourceInfo = sourceInfo,
                )
        return decodedWindow.audio.toStereoFloatTargetWindowOnSongTimeline(
            targetWindowStartFrame = targetWindowStartFrame,
            frames = frames,
            targetSampleRate = config.sampleRate,
            sourceWindowStartFrame = placedSourceWindowStartFrame,
        )
    }

    private fun decodeWindowWithFallback(
        decoder: AudioPcmDecoder,
        uri: Uri,
        requestedStartUs: Long,
        requestedEndUs: Long,
    ): WindowDecodedPcmAudio {
        return try {
            decoder.decodeWindow(
                uri = uri,
                startUs = requestedStartUs,
                endUs = requestedEndUs,
            )
        } catch (error: IllegalStateException) {
            if (error.message != DECODER_NO_PCM_OUTPUT_MESSAGE) throw error
            decoder.decodeWindowWithPrerollCursor(
                uri = uri,
                startUs = requestedStartUs,
                endUs = requestedEndUs,
                prerollUs = WINDOW_DECODE_FALLBACK_PREROLL_US,
            )
        }
    }

    private fun mp3PlacementReferenceFrameCount(
        sourceWindowStartFrame: Int,
        sourceWindowEndFrame: Int,
        sourceInfo: AudioSourceInfo,
    ): Int {
        val boundedEndFrame = sourceWindowEndFrame
            .coerceAtMost(sourceInfo.frameCount ?: sourceWindowEndFrame)
            .coerceAtLeast(sourceWindowStartFrame + 1)
        return boundedEndFrame - sourceWindowStartFrame
    }

    private fun compareAdjacent(
        lowerWindow: Array<FloatArray>,
        upperWindow: Array<FloatArray>,
        lowerSegmentIndex: Int,
        upperSegmentIndex: Int,
    ): Mp3OverlapSweepComparison {
        val overlapFrames = config.chunkSize - config.generationSize
        val edgeGuardFrames = minOf(MP3_OVERLAP_EDGE_GUARD_FRAMES, overlapFrames / 4)
        val stableFrames = overlapFrames - edgeGuardFrames * 2
        if (stableFrames < MIN_MP3_OVERLAP_COMPARISON_FRAMES) {
            return Mp3OverlapSweepComparison.inconclusive(lowerSegmentIndex, upperSegmentIndex)
        }
        val signalRms = signalRms(
            lowerWindow = lowerWindow,
            upperWindow = upperWindow,
            edgeGuardFrames = edgeGuardFrames,
            frames = stableFrames,
        )
        if (signalRms < MP3_OVERLAP_MIN_SIGNAL_RMS) {
            return Mp3OverlapSweepComparison.inconclusive(
                lowerSegmentIndex = lowerSegmentIndex,
                upperSegmentIndex = upperSegmentIndex,
                signalRms = signalRms,
            )
        }
        val zeroMetrics = overlapMetricsAtOffset(
            lowerWindow = lowerWindow,
            upperWindow = upperWindow,
            edgeGuardFrames = edgeGuardFrames,
            offsetFrames = 0,
        ) ?: return Mp3OverlapSweepComparison.inconclusive(lowerSegmentIndex, upperSegmentIndex)
        val best = bestOffset(
            lowerWindow = lowerWindow,
            upperWindow = upperWindow,
            edgeGuardFrames = edgeGuardFrames,
            maxOffsetFrames = MP3_OVERLAP_MAX_SEARCH_OFFSET_FRAMES
                .coerceAtMost(stableFrames - MIN_MP3_OVERLAP_COMPARISON_FRAMES),
        ) ?: return Mp3OverlapSweepComparison.inconclusive(
            lowerSegmentIndex = lowerSegmentIndex,
            upperSegmentIndex = upperSegmentIndex,
            signalRms = signalRms,
            zeroErrorRms = zeroMetrics.errorRms,
        )
        val relative = best.metrics.errorRms / signalRms.coerceAtLeast(Double.MIN_VALUE)
        val improvement = 1.0 - (best.metrics.errorRms / zeroMetrics.errorRms.coerceAtLeast(Double.MIN_VALUE))
        return Mp3OverlapSweepComparison(
            lowerSegmentIndex = lowerSegmentIndex,
            upperSegmentIndex = upperSegmentIndex,
            bestOffsetFrames = best.offsetFrames,
            zeroErrorRms = zeroMetrics.errorRms,
            bestErrorRms = best.metrics.errorRms,
            signalRms = signalRms,
            bestLowerRms = best.metrics.lowerRms,
            bestUpperRms = best.metrics.upperRms,
            relativeError = relative,
            improvement = improvement,
            smallOffset = abs(best.offsetFrames) <= MP3_OVERLAP_PASS_OFFSET_FRAMES,
            largeOffset = abs(best.offsetFrames) >= MP3_OVERLAP_FAIL_OFFSET_FRAMES,
            valid = true,
        )
    }

    private fun signalRms(
        lowerWindow: Array<FloatArray>,
        upperWindow: Array<FloatArray>,
        edgeGuardFrames: Int,
        frames: Int,
    ): Double {
        var sumSquares = 0.0
        var count = 0
        val lowerStartFrame = config.generationSize + edgeGuardFrames
        val upperStartFrame = edgeGuardFrames
        for (channel in 0 until MdxDspConfig.STEREO_CHANNELS) {
            val lower = lowerWindow[channel]
            val upper = upperWindow[channel]
            for (frame in 0 until frames) {
                val lowerValue = lower[lowerStartFrame + frame].toDouble()
                val upperValue = upper[upperStartFrame + frame].toDouble()
                sumSquares += lowerValue * lowerValue + upperValue * upperValue
                count += 2
            }
        }
        return if (count > 0) sqrt(sumSquares / count.toDouble()) else 0.0
    }

    private fun bestOffset(
        lowerWindow: Array<FloatArray>,
        upperWindow: Array<FloatArray>,
        edgeGuardFrames: Int,
        maxOffsetFrames: Int,
    ): Mp3OverlapOffset? {
        if (maxOffsetFrames < 0) return null
        var best: Mp3OverlapOffset? = null
        var coarseOffset = -maxOffsetFrames
        while (coarseOffset <= maxOffsetFrames) {
            val metrics = validOffsetMetrics(
                lowerWindow = lowerWindow,
                upperWindow = upperWindow,
                edgeGuardFrames = edgeGuardFrames,
                offsetFrames = coarseOffset,
            )
            if (metrics != null && (best == null || metrics.errorRms < best.metrics.errorRms)) {
                best = Mp3OverlapOffset(coarseOffset, metrics)
            }
            coarseOffset += MP3_OVERLAP_COARSE_SEARCH_STEP_FRAMES
        }
        val coarseBest = best ?: return null
        val refineStart = (coarseBest.offsetFrames - MP3_OVERLAP_REFINE_RADIUS_FRAMES)
            .coerceAtLeast(-maxOffsetFrames)
        val refineEnd = (coarseBest.offsetFrames + MP3_OVERLAP_REFINE_RADIUS_FRAMES)
            .coerceAtMost(maxOffsetFrames)
        for (offset in refineStart..refineEnd) {
            val metrics = validOffsetMetrics(
                lowerWindow = lowerWindow,
                upperWindow = upperWindow,
                edgeGuardFrames = edgeGuardFrames,
                offsetFrames = offset,
            )
            if (metrics != null && metrics.errorRms < best!!.metrics.errorRms) {
                best = Mp3OverlapOffset(offset, metrics)
            }
        }
        return best
    }

    private fun validOffsetMetrics(
        lowerWindow: Array<FloatArray>,
        upperWindow: Array<FloatArray>,
        edgeGuardFrames: Int,
        offsetFrames: Int,
    ): Mp3OverlapMetrics? {
        val metrics = overlapMetricsAtOffset(
            lowerWindow = lowerWindow,
            upperWindow = upperWindow,
            edgeGuardFrames = edgeGuardFrames,
            offsetFrames = offsetFrames,
        ) ?: return null
        if (metrics.lowerRms < MP3_OVERLAP_MIN_CANDIDATE_SIDE_RMS ||
            metrics.upperRms < MP3_OVERLAP_MIN_CANDIDATE_SIDE_RMS
        ) {
            return null
        }
        return metrics
    }

    private fun overlapMetricsAtOffset(
        lowerWindow: Array<FloatArray>,
        upperWindow: Array<FloatArray>,
        edgeGuardFrames: Int,
        offsetFrames: Int,
    ): Mp3OverlapMetrics? {
        val overlapFrames = config.chunkSize - config.generationSize
        val localStartFrame = maxOf(edgeGuardFrames, edgeGuardFrames - offsetFrames)
        val localEndFrame = minOf(
            overlapFrames - edgeGuardFrames,
            overlapFrames - edgeGuardFrames - offsetFrames,
        )
        val frames = localEndFrame - localStartFrame
        if (frames < MIN_MP3_OVERLAP_COMPARISON_FRAMES) return null

        val lowerStartFrame = config.generationSize + localStartFrame
        val upperStartFrame = localStartFrame + offsetFrames
        var errorSumSquares = 0.0
        var lowerSumSquares = 0.0
        var upperSumSquares = 0.0
        var count = 0
        for (channel in 0 until MdxDspConfig.STEREO_CHANNELS) {
            val lower = lowerWindow[channel]
            val upper = upperWindow[channel]
            for (frame in 0 until frames) {
                val lowerValue = lower[lowerStartFrame + frame].toDouble()
                val upperValue = upper[upperStartFrame + frame].toDouble()
                val difference = lowerValue - upperValue
                errorSumSquares += difference * difference
                lowerSumSquares += lowerValue * lowerValue
                upperSumSquares += upperValue * upperValue
                count += 1
            }
        }
        if (count <= 0) return null
        return Mp3OverlapMetrics(
            errorRms = sqrt(errorSumSquares / count.toDouble()),
            lowerRms = sqrt(lowerSumSquares / count.toDouble()),
            upperRms = sqrt(upperSumSquares / count.toDouble()),
        )
    }

    private companion object {
        const val MP3_MIME_TYPE = "audio/mpeg"
        const val MICROS_PER_SECOND = 1_000_000L
        const val WINDOW_DECODE_FALLBACK_PREROLL_US = MICROS_PER_SECOND
        const val DECODER_NO_PCM_OUTPUT_MESSAGE = "Decoder produced no PCM output."
        const val PCM_FLOAT_SCALE = 32768f
        const val MP3_FINE_QUANTUM_FRAMES = 384
        const val MP3_OVERLAP_EDGE_GUARD_FRAMES = 512
        const val MIN_MP3_OVERLAP_COMPARISON_FRAMES = 1_024
        const val MP3_OVERLAP_MAX_SEARCH_OFFSET_FRAMES = 4_096
        const val MP3_OVERLAP_COARSE_SEARCH_STEP_FRAMES = 16
        const val MP3_OVERLAP_REFINE_RADIUS_FRAMES = 32
        const val MP3_OVERLAP_PASS_OFFSET_FRAMES = 96
        const val MP3_OVERLAP_FAIL_OFFSET_FRAMES = 384
        const val MP3_OVERLAP_MIN_SIGNAL_RMS = 0.001
        const val MP3_OVERLAP_MIN_CANDIDATE_SIDE_RMS = 0.001
    }
}

data class Mp3OverlapSweepResult(
    val displayName: String,
    val sourceInfo: AudioSourceInfo,
    val segmentCount: Int,
    val comparisons: List<Mp3OverlapSweepComparison>,
    val elapsedMs: Long,
    val reportFile: File,
) {
    fun toReportText(): String {
        val validLarge = comparisons.filter { it.valid && it.largeOffset && !it.smallOffset }
        val largeRelativeValues = validLarge.mapNotNull { it.relativeError }
        return buildString {
            appendLine("MP3 overlap sweep: $displayName")
            appendLine("MIME: ${sourceInfo.mimeType}")
            appendLine("Sample rate: ${sourceInfo.sampleRate}")
            appendLine("Channels: ${sourceInfo.channelCount}")
            appendLine("Source frames: ${sourceInfo.frameCount}")
            appendLine("Segments: $segmentCount")
            appendLine("Comparisons: ${comparisons.size}")
            appendLine("Elapsed: ${elapsedMs}ms")
            appendLine("Valid large-offset comparisons: ${validLarge.size}")
            appendLine("Large-offset relative percentiles:")
            appendLine("  p50=${validLarge.relativePercentile(0.50)}")
            appendLine("  p75=${validLarge.relativePercentile(0.75)}")
            appendLine("  p90=${validLarge.relativePercentile(0.90)}")
            appendLine("  p95=${validLarge.relativePercentile(0.95)}")
            appendLine("  min=${largeRelativeValues.minOrNull().formatNullable(6)}")
            appendLine("  max=${largeRelativeValues.maxOrNull().formatNullable(6)}")
            appendLine()
            appendLine(
                "lower,upper,valid,bestOffset,zeroError,bestError,signal,bestLower,bestUpper," +
                        "relative,improvement,smallOffset,largeOffset"
            )
            comparisons.forEach { comparison ->
                appendLine(comparison.toCsvLine())
            }
        }
    }
}

data class Mp3OverlapSweepComparison(
    val lowerSegmentIndex: Int,
    val upperSegmentIndex: Int,
    val bestOffsetFrames: Int?,
    val zeroErrorRms: Double?,
    val bestErrorRms: Double?,
    val signalRms: Double?,
    val bestLowerRms: Double?,
    val bestUpperRms: Double?,
    val relativeError: Double?,
    val improvement: Double?,
    val smallOffset: Boolean,
    val largeOffset: Boolean,
    val valid: Boolean,
) {
    fun toCsvLine(): String {
        return listOf(
            lowerSegmentIndex.toString(),
            upperSegmentIndex.toString(),
            valid.toString(),
            bestOffsetFrames?.toString().orEmpty(),
            zeroErrorRms.formatNullable(6),
            bestErrorRms.formatNullable(6),
            signalRms.formatNullable(6),
            bestLowerRms.formatNullable(6),
            bestUpperRms.formatNullable(6),
            relativeError.formatNullable(6),
            improvement.formatNullable(6),
            smallOffset.toString(),
            largeOffset.toString(),
        ).joinToString(",")
    }

    companion object {
        fun inconclusive(
            lowerSegmentIndex: Int,
            upperSegmentIndex: Int,
            signalRms: Double? = null,
            zeroErrorRms: Double? = null,
        ): Mp3OverlapSweepComparison {
            return Mp3OverlapSweepComparison(
                lowerSegmentIndex = lowerSegmentIndex,
                upperSegmentIndex = upperSegmentIndex,
                bestOffsetFrames = null,
                zeroErrorRms = zeroErrorRms,
                bestErrorRms = null,
                signalRms = signalRms,
                bestLowerRms = null,
                bestUpperRms = null,
                relativeError = null,
                improvement = null,
                smallOffset = false,
                largeOffset = false,
                valid = false,
            )
        }
    }
}

private data class Mp3OverlapOffset(
    val offsetFrames: Int,
    val metrics: Mp3OverlapMetrics,
)

private data class Mp3OverlapMetrics(
    val errorRms: Double,
    val lowerRms: Double,
    val upperRms: Double,
)

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

private fun DecodedPcmAudio.toStereoFloatTargetWindowOnSongTimeline(
    targetWindowStartFrame: Int,
    frames: Int,
    targetSampleRate: Int,
    sourceWindowStartFrame: Int,
): Array<FloatArray> {
    val window = Array(MdxDspConfig.STEREO_CHANNELS) { FloatArray(frames) }
    if (frameCount == 0) return window
    for (targetFrameInWindow in 0 until frames) {
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

private fun List<Mp3OverlapSweepComparison>.relativePercentile(percentile: Double): String {
    val values = mapNotNull { it.relativeError }.sorted()
    if (values.isEmpty()) return ""
    val index = ((values.size - 1) * percentile)
        .roundToLong()
        .coerceIn(0L, (values.size - 1).toLong())
        .toInt()
    return values[index].format(6)
}

private fun Double?.formatNullable(decimals: Int): String {
    return this?.format(decimals).orEmpty()
}

private fun Double.format(decimals: Int): String {
    return "%.${decimals}f".format(Locale.US, this)
}

private fun uniqueFile(directory: File, fileName: String): File {
    var candidate = File(directory, fileName)
    if (!candidate.exists()) return candidate
    val extensionIndex = fileName.lastIndexOf('.')
    val base = if (extensionIndex > 0) fileName.substring(0, extensionIndex) else fileName
    val extension = if (extensionIndex > 0) fileName.substring(extensionIndex) else ""
    var suffix = 2
    while (candidate.exists()) {
        candidate = File(directory, "${base}_$suffix$extension")
        suffix += 1
    }
    return candidate
}

private fun String.toSafeFileStem(): String {
    return trim()
        .replace(Regex("[^A-Za-z0-9._-]+"), "_")
        .trim('_')
        .ifBlank { "current-song" }
}

private const val MICROS_PER_SECOND = 1_000_000L
private const val WINDOW_DECODE_FALLBACK_PREROLL_US = MICROS_PER_SECOND
private const val DECODER_NO_PCM_OUTPUT_MESSAGE = "Decoder produced no PCM output."
private const val PCM_FLOAT_SCALE = 32768f
private const val MP3_FINE_QUANTUM_FRAMES = 384
