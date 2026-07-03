package com.mardous.booming.debug

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.mardous.booming.separation.audio.AudioPcmDecoder
import com.mardous.booming.separation.audio.AudioSourceInfo
import com.mardous.booming.separation.audio.WindowDecodedPcmAudio
import com.mardous.booming.separation.model.MdxDspConfig
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

class WindowDecodeRepeatExperiment(
    private val context: Context,
    private val config: MdxDspConfig = MdxDspConfig(),
) {
    fun run(
        uri: Uri,
        displayName: String,
        playbackPositionMs: Long,
        repeatCount: Int,
        reportDir: File,
        onProgress: (String) -> Unit = {},
    ): WindowDecodeRepeatExperimentResult {
        reportDir.mkdirs()
        val safeRepeatCount = repeatCount.coerceIn(1, MAX_REPEAT_COUNT)
        val decoder = AudioPcmDecoder(context)
        onProgress("Inspecting source audio")
        val sourceInfo = decoder.inspect(uri)
        val window = buildWindow(
            playbackPositionMs = playbackPositionMs.coerceAtLeast(0L),
            sourceInfo = sourceInfo,
        )

        val localRuns = mutableListOf<WindowDecodeRepeatRun>()
        val prerollRuns = mutableListOf<WindowDecodeRepeatRun>()
        repeat(safeRepeatCount) { index ->
            val iteration = index + 1
            onProgress("Local decode $iteration/$safeRepeatCount")
            localRuns += decodeRun(
                decoder = decoder,
                iteration = iteration,
                kind = "local",
                window = window,
            ) {
                decodeWindow(
                    uri = uri,
                    startUs = window.requestedStartUs,
                    endUs = window.requestedEndUs,
                )
            }

            onProgress("Preroll cursor decode $iteration/$safeRepeatCount")
            prerollRuns += decodeRun(
                decoder = decoder,
                iteration = iteration,
                kind = "preroll",
                window = window,
            ) {
                decodeWindowWithPrerollCursor(
                    uri = uri,
                    startUs = window.requestedStartUs,
                    endUs = window.requestedEndUs,
                    prerollUs = frameToUs(config.trim, config.sampleRate),
                )
            }
        }

        val reportFile = uniqueFile(
            reportDir,
            "${displayName.toSafeFileStem()}_window_repeat_decode.txt",
        )
        val result = WindowDecodeRepeatExperimentResult(
            reportFile = reportFile,
            displayName = displayName,
            playbackPositionMs = playbackPositionMs,
            sourceInfo = sourceInfo,
            window = window,
            localRuns = localRuns,
            prerollRuns = prerollRuns,
        )
        reportFile.writeText(result.toReportText(), Charsets.UTF_8)
        onProgress("Finished")
        return result
    }

    private fun buildWindow(
        playbackPositionMs: Long,
        sourceInfo: AudioSourceInfo,
    ): WindowDecodeRepeatWindow {
        val sourceFrameCount = sourceInfo.frameCount
        val sourceTargetFrame = ((playbackPositionMs * sourceInfo.sampleRate) / MILLIS_PER_SECOND)
            .coerceAtLeast(0L)
            .let { frame ->
                if (sourceFrameCount == null) frame else frame.coerceAtMost(sourceFrameCount.toLong())
            }
            .toInt()
        val targetFrame = sourceFrameToTargetFrame(sourceTargetFrame, sourceInfo.sampleRate)
        val segmentIndex = (targetFrame / config.generationSize).coerceAtLeast(0)
        val playbackStartFrame = segmentIndex * config.generationSize
        val targetWindowStartFrame = playbackStartFrame - config.trim
        val targetWindowEndFrame = targetWindowStartFrame + config.chunkSize
        val sourceWindowStartFrame = targetFrameToSourceFrameFloor(
            targetFrame = targetWindowStartFrame.coerceAtLeast(0),
            sourceSampleRate = sourceInfo.sampleRate,
        )
        val sourceWindowEndFrame = targetFrameToSourceFrameCeil(
            targetFrame = targetWindowEndFrame.coerceAtLeast(0),
            sourceSampleRate = sourceInfo.sampleRate,
        ).coerceAtLeast(sourceWindowStartFrame + 1)
        return WindowDecodeRepeatWindow(
            segmentIndex = segmentIndex,
            sourceTargetFrame = sourceTargetFrame,
            targetFrame = targetFrame,
            targetWindowStartFrame = targetWindowStartFrame,
            targetWindowEndFrame = targetWindowEndFrame,
            sourceWindowStartFrame = sourceWindowStartFrame,
            sourceWindowEndFrame = sourceWindowEndFrame,
            requestedStartUs = frameToUs(sourceWindowStartFrame, sourceInfo.sampleRate),
            requestedEndUs = frameToUs(sourceWindowEndFrame, sourceInfo.sampleRate),
        )
    }

    private fun decodeRun(
        decoder: AudioPcmDecoder,
        iteration: Int,
        kind: String,
        window: WindowDecodeRepeatWindow,
        block: AudioPcmDecoder.() -> WindowDecodedPcmAudio,
    ): WindowDecodeRepeatRun {
        val startedAtMs = SystemClock.elapsedRealtime()
        val decoded = decoder.block()
        val elapsedMs = SystemClock.elapsedRealtime() - startedAtMs
        return WindowDecodeRepeatRun(
            kind = kind,
            iteration = iteration,
            elapsedMs = elapsedMs,
            extractorStartUs = decoded.extractorStartUs,
            extractorStartDeltaFrames = usDeltaToFrame(
                decoded.extractorStartUs - window.requestedStartUs,
                decoded.audio.sampleRate,
            ),
            firstOutputTimeUs = decoded.firstOutputTimeUs,
            firstOutputDeltaFrames = decoded.firstOutputTimeUs?.let {
                usDeltaToFrame(it - window.requestedStartUs, decoded.audio.sampleRate)
            },
            lastOutputTimeUs = decoded.lastOutputTimeUs,
            cursorAnchorTimeUs = decoded.cursorAnchorTimeUs,
            outputBufferCount = decoded.outputBufferCount,
            sampleRate = decoded.audio.sampleRate,
            channelCount = decoded.audio.channelCount,
            frameCount = decoded.audio.frameCount,
            frameDeficit = (window.requestedSourceFrameCount - decoded.audio.frameCount).coerceAtLeast(0),
            pcmByteCount = decoded.audio.pcm16.size,
            pcmSha256 = decoded.audio.pcm16.sha256(),
        )
    }

    private fun sourceFrameToTargetFrame(sourceFrame: Int, sourceSampleRate: Int): Int {
        return floor(sourceFrame.toDouble() * config.sampleRate.toDouble() / sourceSampleRate.toDouble())
            .toInt()
            .coerceAtLeast(0)
    }

    private fun targetFrameToSourceFrameFloor(targetFrame: Int, sourceSampleRate: Int): Int {
        return floor(targetFrame.toDouble() * sourceSampleRate.toDouble() / config.sampleRate.toDouble())
            .toInt()
            .coerceAtLeast(0)
    }

    private fun targetFrameToSourceFrameCeil(targetFrame: Int, sourceSampleRate: Int): Int {
        return ceil(targetFrame.toDouble() * sourceSampleRate.toDouble() / config.sampleRate.toDouble())
            .toInt()
            .coerceAtLeast(0)
    }

    private fun frameToUs(frame: Int, sampleRate: Int): Long {
        return (frame.toLong() * MICROS_PER_SECOND) / sampleRate.toLong()
    }

    private fun usDeltaToFrame(timeUs: Long, sampleRate: Int): Int {
        return floor(timeUs.toDouble() * sampleRate.toDouble() / MICROS_PER_SECOND.toDouble())
            .toInt()
    }

    private fun ByteArray.sha256(): String {
        return MessageDigest.getInstance("SHA-256").digest(this).toHexString()
    }

    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02x".format(it) }
    }

    private fun String.toSafeFileStem(): String {
        return substringBeforeLast('.')
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .trim('_')
            .ifBlank { "current-song" }
    }

    private fun uniqueFile(directory: File, name: String): File {
        val base = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "")
            .takeIf { it != name }
            ?.let { ".$it" }
            .orEmpty()
        var candidate = File(directory, name)
        var index = 2
        while (candidate.exists()) {
            candidate = File(directory, "$base-$index$extension")
            index += 1
        }
        return candidate
    }

    private companion object {
        const val MICROS_PER_SECOND = 1_000_000L
        const val MILLIS_PER_SECOND = 1_000L
        const val MAX_REPEAT_COUNT = 20
    }
}

data class WindowDecodeRepeatExperimentResult(
    val reportFile: File,
    val displayName: String,
    val playbackPositionMs: Long,
    val sourceInfo: AudioSourceInfo,
    val window: WindowDecodeRepeatWindow,
    val localRuns: List<WindowDecodeRepeatRun>,
    val prerollRuns: List<WindowDecodeRepeatRun>,
) {
    fun toReportText(): String {
        return buildString {
            appendLine("Window decode repeat experiment")
            appendLine("Song: $displayName")
            appendLine("Playback position: ${playbackPositionMs}ms")
            appendLine()
            appendLine("Source:")
            appendLine("MIME type: ${sourceInfo.mimeType}")
            appendLine("Sample rate: ${sourceInfo.sampleRate}")
            appendLine("Channels: ${sourceInfo.channelCount}")
            appendLine("Duration: ${sourceInfo.durationUs?.let { "${it}us" } ?: "unavailable"}")
            appendLine("Frame count: ${sourceInfo.frameCount ?: "unavailable"}")
            appendLine("Encoder delay frames: ${sourceInfo.trackMetadata.encoderDelayFrames.toOptionalFrames()}")
            appendLine("Encoder padding frames: ${sourceInfo.trackMetadata.encoderPaddingFrames.toOptionalFrames()}")
            appendLine()
            appendLine("Window:")
            appendLine("Segment index: ${window.segmentIndex}")
            appendLine("Source target frame: ${window.sourceTargetFrame}")
            appendLine("Target frame: ${window.targetFrame}")
            appendLine("Target window frames: ${window.targetWindowStartFrame}..${window.targetWindowEndFrame}")
            appendLine("Source window frames: ${window.sourceWindowStartFrame}..${window.sourceWindowEndFrame}")
            appendLine("Requested source frames: ${window.requestedSourceFrameCount}")
            appendLine("Requested time: ${window.requestedStartUs}us..${window.requestedEndUs}us")
            appendLine()
            appendRunSummary("Local decodeWindow", localRuns)
            appendLine()
            appendRunSummary("Preroll decodeWindowWithPrerollCursor", prerollRuns)
            appendLine()
            appendLine("Local runs:")
            appendRunTable(localRuns)
            appendLine()
            appendLine("Preroll runs:")
            appendRunTable(prerollRuns)
        }
    }

    private fun StringBuilder.appendRunSummary(
        label: String,
        runs: List<WindowDecodeRepeatRun>,
    ) {
        appendLine("$label summary:")
        appendLine("Runs: ${runs.size}")
        appendLine("Unique PCM hashes: ${runs.map { it.pcmSha256 }.distinct().size}")
        appendLine("Unique extractor starts: ${runs.map { it.extractorStartUs }.distinct().size}")
        appendLine("Unique first outputs: ${runs.map { it.firstOutputTimeUs }.distinct().size}")
        appendLine("Unique output buffer counts: ${runs.map { it.outputBufferCount }.distinct().size}")
        appendLine("Unique frame counts: ${runs.map { it.frameCount }.distinct().size}")
        appendLine("Elapsed ms: ${runs.joinToString { it.elapsedMs.toString() }}")
        appendLine(
            "Extractor start delta frames: " +
                    runs.joinToString { it.extractorStartDeltaFrames.toString() }
        )
        appendLine(
            "First output delta frames: " +
                    runs.joinToString { it.firstOutputDeltaFrames?.toString() ?: "unavailable" }
        )
        appendLine("Frame deficits: ${runs.joinToString { it.frameDeficit.toString() }}")
    }

    private fun StringBuilder.appendRunTable(runs: List<WindowDecodeRepeatRun>) {
        appendLine(
            "kind iter elapsedMs extractorStartUs extractorDeltaFrames firstOutputUs " +
                    "firstOutputDeltaFrames lastOutputUs cursorAnchorUs buffers rate channels " +
                    "frames frameDeficit bytes sha256"
        )
        runs.forEach { run ->
            appendLine(
                listOf(
                    run.kind,
                    run.iteration,
                    run.elapsedMs,
                    run.extractorStartUs,
                    run.extractorStartDeltaFrames,
                    run.firstOutputTimeUs ?: "unavailable",
                    run.firstOutputDeltaFrames ?: "unavailable",
                    run.lastOutputTimeUs ?: "unavailable",
                    run.cursorAnchorTimeUs ?: "unavailable",
                    run.outputBufferCount,
                    run.sampleRate,
                    run.channelCount,
                    run.frameCount,
                    run.frameDeficit,
                    run.pcmByteCount,
                    run.pcmSha256,
                ).joinToString(" ")
            )
        }
    }

    private fun Int?.toOptionalFrames(): String {
        return this?.toString() ?: "unavailable"
    }
}

data class WindowDecodeRepeatWindow(
    val segmentIndex: Int,
    val sourceTargetFrame: Int,
    val targetFrame: Int,
    val targetWindowStartFrame: Int,
    val targetWindowEndFrame: Int,
    val sourceWindowStartFrame: Int,
    val sourceWindowEndFrame: Int,
    val requestedStartUs: Long,
    val requestedEndUs: Long,
) {
    val requestedSourceFrameCount: Int
        get() = sourceWindowEndFrame - sourceWindowStartFrame
}

data class WindowDecodeRepeatRun(
    val kind: String,
    val iteration: Int,
    val elapsedMs: Long,
    val extractorStartUs: Long,
    val extractorStartDeltaFrames: Int,
    val firstOutputTimeUs: Long?,
    val firstOutputDeltaFrames: Int?,
    val lastOutputTimeUs: Long?,
    val cursorAnchorTimeUs: Long?,
    val outputBufferCount: Int,
    val sampleRate: Int,
    val channelCount: Int,
    val frameCount: Int,
    val frameDeficit: Int,
    val pcmByteCount: Int,
    val pcmSha256: String,
)
