package com.mardous.booming.separation.audio

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.mardous.booming.separation.model.MdxDspConfig
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToLong

class AudioWindowDecodeExperiment(
    private val context: Context,
    private val config: MdxDspConfig = MdxDspConfig(),
) {
    fun run(
        uri: Uri,
        displayName: String,
        playbackPositionMs: Long,
        reportDir: File,
        shouldCancel: () -> Boolean = { false },
    ): AudioWindowDecodeExperimentResult {
        reportDir.mkdirs()
        val fullDecode = timed {
            AudioPcmDecoder(context).decode(uri, shouldCancel = shouldCancel)
        }
        val reference = timed {
            fullDecode.value.resampleTo(config.sampleRate, shouldCancel = shouldCancel)
        }

        val targetPlaybackPositionMs = playbackPositionMs.coerceAtLeast(0L)
        val probePositionsMs = buildProbePositionsMs(
            requestedPositionMs = targetPlaybackPositionMs,
            totalFrames = reference.value.frameCount,
        )
        val probes = probePositionsMs.mapIndexed { index, positionMs ->
            runProbe(
                uri = uri,
                reference = reference.value,
                requestedLabel = if (positionMs == targetPlaybackPositionMs) {
                    "Current playback"
                } else {
                    "Probe ${index + 1}"
                },
                playbackPositionMs = positionMs,
                shouldCancel = shouldCancel,
            )
        }

        val safeName = displayName.substringBeforeLast('.')
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .ifBlank { "audio" }
        val reportFile = uniqueFile(reportDir, "${safeName}_window_decode_experiment.txt")
        val result = AudioWindowDecodeExperimentResult(
            reportFile = reportFile,
            displayName = displayName,
            requestedPlaybackPositionMs = targetPlaybackPositionMs,
            referenceFrameCount = reference.value.frameCount,
            referenceSampleRate = reference.value.sampleRate,
            referenceChannelCount = reference.value.channelCount,
            fullDecodeMs = fullDecode.elapsedMs,
            fullResampleMs = reference.elapsedMs,
            probes = probes,
        )
        reportFile.writeText(result.toReportText(), Charsets.UTF_8)
        return result
    }

    private fun runProbe(
        uri: Uri,
        reference: DecodedPcmAudio,
        requestedLabel: String,
        playbackPositionMs: Long,
        shouldCancel: () -> Boolean,
    ): AudioWindowDecodeProbeResult {
        val targetFrame = ((playbackPositionMs.coerceAtLeast(0L) * config.sampleRate) / MILLIS_PER_SECOND)
            .coerceIn(0L, reference.frameCount.toLong())
            .toInt()
        val segmentIndex = (targetFrame / config.generationSize).coerceAtLeast(0)
        val playbackStartFrame = segmentIndex * config.generationSize
        val windowStartFrame = playbackStartFrame - config.trim
        val windowEndFrame = windowStartFrame + config.chunkSize
        val requestedStartUs = frameToUs(windowStartFrame.coerceAtLeast(0), config.sampleRate)
        val requestedEndUs = frameToUs(windowEndFrame.coerceAtLeast(0), config.sampleRate)

        val localWindow = timed {
            AudioPcmDecoder(context).decodeWindow(
                uri = uri,
                startUs = requestedStartUs,
                endUs = requestedEndUs,
                shouldCancel = shouldCancel,
            )
        }
        val localResampled = timed {
            localWindow.value.audio.resampleTo(config.sampleRate, shouldCancel = shouldCancel)
        }

        val expected = reference.slicePcm16(
            startFrame = windowStartFrame,
            frames = config.chunkSize,
            outputChannelCount = MdxDspConfig.STEREO_CHANNELS,
        )
        val requestAlignedCandidate = localResampled.value.slicePcm16IntoWindow(
            frames = config.chunkSize,
            outputChannelCount = MdxDspConfig.STEREO_CHANNELS,
            destinationStartFrame = maxOf(0, -windowStartFrame),
        )
        val firstOutputAlignedCandidate = localResampled.value.slicePcm16IntoWindow(
            frames = config.chunkSize,
            outputChannelCount = MdxDspConfig.STEREO_CHANNELS,
            destinationStartFrame = firstOutputDestinationFrame(
                windowStartFrame = windowStartFrame,
                firstOutputTimeUs = localWindow.value.firstOutputTimeUs,
            ),
        )

        val requestAligned = compareCandidate(expected, requestAlignedCandidate)
        val firstOutputAligned = compareCandidate(expected, firstOutputAlignedCandidate)

        return AudioWindowDecodeProbeResult(
            label = requestedLabel,
            playbackPositionMs = playbackPositionMs,
            segmentIndex = segmentIndex,
            targetFrame = targetFrame,
            requestedStartUs = requestedStartUs,
            requestedEndUs = requestedEndUs,
            requestedWindowStartFrame = windowStartFrame,
            requestedWindowEndFrame = windowEndFrame,
            windowDecodeMs = localWindow.elapsedMs,
            windowResampleMs = localResampled.elapsedMs,
            extractorStartUs = localWindow.value.extractorStartUs,
            firstOutputTimeUs = localWindow.value.firstOutputTimeUs,
            lastOutputTimeUs = localWindow.value.lastOutputTimeUs,
            outputBufferCount = localWindow.value.outputBufferCount,
            windowFrameCount = localResampled.value.frameCount,
            requestAligned = requestAligned,
            firstOutputAligned = firstOutputAligned,
        )
    }

    private fun compareCandidate(
        expected: ShortArray,
        candidate: ShortArray,
    ): PcmWindowAlignmentComparison {
        return PcmWindowAlignmentComparison(
            direct = comparePcm16(expected, candidate),
            bestOffset = bestOffsetComparison(
                reference = expected,
                candidate = candidate,
                maxOffsetFrames = MAX_OFFSET_SEARCH_FRAMES,
                channelCount = MdxDspConfig.STEREO_CHANNELS,
            ),
        )
    }

    private fun buildProbePositionsMs(
        requestedPositionMs: Long,
        totalFrames: Int,
    ): List<Long> {
        val durationMs = frameToMs(totalFrames, config.sampleRate)
        val nearEndMs = (durationMs - PROBE_WINDOW_MARGIN_MS).coerceAtLeast(0L)
        return listOf(
            requestedPositionMs,
            0L,
            30_000L,
            60_000L,
            durationMs / 2L,
            nearEndMs,
        )
            .map { it.coerceIn(0L, nearEndMs) }
            .distinct()
    }

    private fun DecodedPcmAudio.slicePcm16(
        startFrame: Int,
        frames: Int,
        outputChannelCount: Int,
    ): ShortArray {
        val output = ShortArray(frames * outputChannelCount)
        val copyStartFrame = startFrame.coerceIn(0, frameCount)
        val copyEndFrame = (startFrame + frames).coerceIn(copyStartFrame, frameCount)
        var outputOffset = (copyStartFrame - startFrame) * outputChannelCount
        for (frame in copyStartFrame until copyEndFrame) {
            val sourceFrameOffset = frame * channelCount
            for (channel in 0 until outputChannelCount) {
                val sourceChannel = channel.coerceAtMost(channelCount - 1)
                output[outputOffset++] = readLittleEndianShort(
                    byteIndex = (sourceFrameOffset + sourceChannel) * Short.SIZE_BYTES,
                )
            }
        }
        return output
    }

    private fun comparePcm16(
        reference: ShortArray,
        candidate: ShortArray,
    ): PcmWindowComparison {
        return comparePcm16(
            reference = reference,
            candidate = candidate,
            referenceStart = 0,
            candidateStart = 0,
            count = minOf(reference.size, candidate.size),
            offsetFrames = 0,
        )
    }

    private fun comparePcm16(
        reference: ShortArray,
        candidate: ShortArray,
        referenceStart: Int,
        candidateStart: Int,
        count: Int,
        offsetFrames: Int,
    ): PcmWindowComparison {
        if (count == 0) {
            return PcmWindowComparison(
                offsetFrames = offsetFrames,
                comparedSamples = 0,
                equalSamples = 0,
                meanAbsoluteError = 0.0,
                maxAbsoluteError = 0,
            )
        }
        var equal = 0
        var absoluteErrorTotal = 0L
        var maxAbsoluteError = 0
        for (index in 0 until count) {
            val error = abs(reference[referenceStart + index].toInt() - candidate[candidateStart + index].toInt())
            if (error == 0) equal += 1
            absoluteErrorTotal += error.toLong()
            if (error > maxAbsoluteError) maxAbsoluteError = error
        }
        return PcmWindowComparison(
            offsetFrames = offsetFrames,
            comparedSamples = count,
            equalSamples = equal,
            meanAbsoluteError = absoluteErrorTotal.toDouble() / count.toDouble(),
            maxAbsoluteError = maxAbsoluteError,
        )
    }

    private fun bestOffsetComparison(
        reference: ShortArray,
        candidate: ShortArray,
        maxOffsetFrames: Int,
        channelCount: Int,
    ): PcmWindowComparison {
        var best = comparePcm16(reference, candidate)
        for (offsetFrames in -maxOffsetFrames..maxOffsetFrames) {
            if (offsetFrames == 0) continue
            val offsetSamples = offsetFrames * channelCount
            val referenceStart = maxOf(0, offsetSamples)
            val candidateStart = maxOf(0, -offsetSamples)
            val count = minOf(reference.size - referenceStart, candidate.size - candidateStart)
            if (count <= 0) continue
            val comparison = comparePcm16(
                reference = reference,
                candidate = candidate,
                referenceStart = referenceStart,
                candidateStart = candidateStart,
                count = count,
                offsetFrames = offsetFrames,
            )
            if (comparison.meanAbsoluteError < best.meanAbsoluteError) {
                best = comparison
            }
        }
        return best
    }

    private fun DecodedPcmAudio.readLittleEndianShort(byteIndex: Int): Short {
        val low = pcm16[byteIndex].toInt() and 0xFF
        val high = pcm16[byteIndex + 1].toInt()
        return ((high shl 8) or low).toShort()
    }

    private fun DecodedPcmAudio.slicePcm16IntoWindow(
        frames: Int,
        outputChannelCount: Int,
        destinationStartFrame: Int,
    ): ShortArray {
        val output = ShortArray(frames * outputChannelCount)
        val safeDestinationStartFrame = destinationStartFrame.coerceIn(0, frames)
        val copyFrames = minOf(frameCount, frames - safeDestinationStartFrame)
        if (copyFrames <= 0) return output
        val source = slicePcm16(
            startFrame = 0,
            frames = copyFrames,
            outputChannelCount = outputChannelCount,
        )
        source.copyInto(
            destination = output,
            destinationOffset = safeDestinationStartFrame * outputChannelCount,
        )
        return output
    }

    private inline fun <T> timed(block: () -> T): TimedValue<T> {
        val start = SystemClock.elapsedRealtime()
        return TimedValue(
            value = block(),
            elapsedMs = SystemClock.elapsedRealtime() - start,
        )
    }

    private fun firstOutputDestinationFrame(
        windowStartFrame: Int,
        firstOutputTimeUs: Long?,
    ): Int {
        val firstOutputFrame = firstOutputTimeUs
            ?.let { usToFrame(it, config.sampleRate) }
            ?: windowStartFrame.coerceAtLeast(0)
        return firstOutputFrame - windowStartFrame
    }

    private fun frameToMs(frame: Int, sampleRate: Int): Long {
        return (frame.toLong() * MILLIS_PER_SECOND) / sampleRate.toLong()
    }

    private fun frameToUs(frame: Int, sampleRate: Int): Long {
        return (frame.toLong() * MICROS_PER_SECOND) / sampleRate.toLong()
    }

    private fun usToFrame(timeUs: Long, sampleRate: Int): Int {
        return (timeUs.toDouble() * sampleRate / MICROS_PER_SECOND).roundToLong().toInt()
    }

    private fun uniqueFile(dir: File, name: String): File {
        val extensionIndex = name.lastIndexOf('.')
        val base = if (extensionIndex > 0) name.substring(0, extensionIndex) else name
        val extension = if (extensionIndex > 0) name.substring(extensionIndex) else ""
        var candidate = File(dir, name)
        var suffix = 2
        while (candidate.exists()) {
            candidate = File(dir, "${base}_$suffix$extension")
            suffix += 1
        }
        return candidate
    }

    private data class TimedValue<T>(
        val value: T,
        val elapsedMs: Long,
    )

    private companion object {
        const val MILLIS_PER_SECOND = 1000L
        const val MICROS_PER_SECOND = 1_000_000L
        const val MAX_OFFSET_SEARCH_FRAMES = 4096
        const val PROBE_WINDOW_MARGIN_MS = 7_000L
    }
}

data class AudioWindowDecodeExperimentResult(
    val reportFile: File,
    val displayName: String,
    val requestedPlaybackPositionMs: Long,
    val referenceFrameCount: Int,
    val referenceSampleRate: Int,
    val referenceChannelCount: Int,
    val fullDecodeMs: Long,
    val fullResampleMs: Long,
    val probes: List<AudioWindowDecodeProbeResult>,
) {
    val totalWindowDecodeMs: Long = probes.sumOf { it.windowDecodeMs }
    val totalWindowResampleMs: Long = probes.sumOf { it.windowResampleMs }
    val bestSummary: PcmWindowComparison = probes.minByOrNull {
        it.bestCandidate.bestOffset.meanAbsoluteError
    }?.bestCandidate?.bestOffset ?: PcmWindowComparison(
        offsetFrames = 0,
        comparedSamples = 0,
        equalSamples = 0,
        meanAbsoluteError = 0.0,
        maxAbsoluteError = 0,
    )
    val worstSummary: PcmWindowComparison = probes.maxByOrNull {
        it.bestCandidate.bestOffset.meanAbsoluteError
    }?.bestCandidate?.bestOffset ?: bestSummary

    fun toReportText(): String {
        return buildString {
            appendLine("Audio window decode experiment")
            appendLine("Song: $displayName")
            appendLine("Requested playback position: ${requestedPlaybackPositionMs}ms")
            appendLine()
            appendLine("Reference full decode:")
            appendLine("Frames: $referenceFrameCount")
            appendLine("Sample rate: $referenceSampleRate")
            appendLine("Channels: $referenceChannelCount")
            appendLine("Full decode: ${fullDecodeMs}ms")
            appendLine("Full resample: ${fullResampleMs}ms")
            appendLine()
            appendLine("Probe summary:")
            appendLine("Probe count: ${probes.size}")
            appendLine("Total window decode: ${totalWindowDecodeMs}ms")
            appendLine("Total window resample: ${totalWindowResampleMs}ms")
            appendLine("Best probe/candidate: ${bestSummary.toReportLine()}")
            appendLine("Worst probe/candidate: ${worstSummary.toReportLine()}")
            appendLine()
            probes.forEachIndexed { index, probe ->
                appendLine("Probe ${index + 1}: ${probe.label}")
                appendLine(probe.toReportText())
                appendLine()
            }
            appendLine("Note:")
            appendLine("This diagnostic still performs a full decode once for reference comparison.")
            appendLine("Request-aligned places the decoded PCM at the requested window start.")
            appendLine("First-output-aligned places the decoded PCM at the decoder's first output timestamp.")
            appendLine("The window decoder is production-worthy only if several source formats show low error and stable offset behavior at multiple positions.")
        }
    }
}

data class AudioWindowDecodeProbeResult(
    val label: String,
    val playbackPositionMs: Long,
    val segmentIndex: Int,
    val targetFrame: Int,
    val requestedStartUs: Long,
    val requestedEndUs: Long,
    val requestedWindowStartFrame: Int,
    val requestedWindowEndFrame: Int,
    val windowDecodeMs: Long,
    val windowResampleMs: Long,
    val extractorStartUs: Long,
    val firstOutputTimeUs: Long?,
    val lastOutputTimeUs: Long?,
    val outputBufferCount: Int,
    val windowFrameCount: Int,
    val requestAligned: PcmWindowAlignmentComparison,
    val firstOutputAligned: PcmWindowAlignmentComparison,
) {
    val bestCandidate: PcmWindowAlignmentComparison
        get() = if (firstOutputAligned.bestOffset.meanAbsoluteError < requestAligned.bestOffset.meanAbsoluteError) {
            firstOutputAligned
        } else {
            requestAligned
        }

    fun toReportText(): String {
        return buildString {
            appendLine("Playback position: ${playbackPositionMs}ms")
            appendLine("Segment index: $segmentIndex")
            appendLine("Target frame: $targetFrame")
            appendLine("Requested window frames: $requestedWindowStartFrame..$requestedWindowEndFrame")
            appendLine("Requested window time: ${requestedStartUs}us..${requestedEndUs}us")
            appendLine()
            appendLine("Local window decode:")
            appendLine("Extractor start: ${extractorStartUs}us")
            appendLine("First output: ${firstOutputTimeUs}us")
            appendLine("Last output: ${lastOutputTimeUs}us")
            appendLine("Output buffers: $outputBufferCount")
            appendLine("Frames after resample: $windowFrameCount")
            appendLine("Window decode: ${windowDecodeMs}ms")
            appendLine("Window resample: ${windowResampleMs}ms")
            appendLine()
            appendLine("PCM comparison:")
            appendLine("Request-aligned direct: ${requestAligned.direct.toReportLine()}")
            appendLine("Request-aligned best offset: ${requestAligned.bestOffset.toReportLine()}")
            appendLine("First-output-aligned direct: ${firstOutputAligned.direct.toReportLine()}")
            appendLine("First-output-aligned best offset: ${firstOutputAligned.bestOffset.toReportLine()}")
        }
    }
}

data class PcmWindowAlignmentComparison(
    val direct: PcmWindowComparison,
    val bestOffset: PcmWindowComparison,
)

data class PcmWindowComparison(
    val offsetFrames: Int,
    val comparedSamples: Int,
    val equalSamples: Int,
    val meanAbsoluteError: Double,
    val maxAbsoluteError: Int,
) {
    fun toReportLine(): String {
        val equalPercent = if (comparedSamples > 0) {
            equalSamples.toDouble() * 100.0 / comparedSamples.toDouble()
        } else {
            0.0
        }
        return "offsetFrames=$offsetFrames comparedSamples=$comparedSamples " +
                "equalSamples=$equalSamples (${String.format("%.2f", equalPercent)}%) " +
                "meanAbsError=${String.format("%.3f", meanAbsoluteError)} maxAbsError=$maxAbsoluteError"
    }
}
