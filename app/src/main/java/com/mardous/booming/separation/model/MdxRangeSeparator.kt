package com.mardous.booming.separation.model

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.mardous.booming.separation.audio.AudioPcmDecoder
import com.mardous.booming.separation.audio.DecodedPcmAudio
import com.mardous.booming.separation.audio.WavFileWriter
import com.mardous.booming.separation.cache.SourceSeparationCache
import com.mardous.booming.separation.cache.SourceSeparationSegmentPlan
import com.mardous.booming.separation.cache.SourceSeparationSegmentState
import java.io.File
import java.nio.FloatBuffer
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.ceil
import kotlin.math.roundToInt

class MdxRangeSeparator(
    private val context: Context,
    private val config: MdxDspConfig = MdxDspConfig(),
    private val runtimeSettings: MdxRuntimeSettings = MdxRuntimeSettings(),
    private val modelVariant: MdxModelVariant = MdxModelVariant.MDXNET_9482,
) {
    fun separate(
        uri: Uri,
        outputDir: File,
        segmentOutputDir: File? = null,
        displayName: String,
        startMs: Long = 0L,
        endMs: Long? = null,
        runtimeSettings: MdxRuntimeSettings = this.runtimeSettings,
        modelVariant: MdxModelVariant = this.modelVariant,
        onProgress: (MdxRangeProgress) -> Unit = {},
        onPrepared: (MdxRangePreparation) -> Unit = {},
        onSegmentStateChanged: (segmentIndex: Int, state: SourceSeparationSegmentState) -> Unit = { _, _ -> },
        shouldCancel: () -> Boolean = { false },
    ): MdxRangeSeparationResult {
        val timing = MdxRangeTimingAccumulator()
        val totalStartedAt = SystemClock.elapsedRealtime()
        throwIfCanceled(shouldCancel)
        onProgress(MdxRangeProgress.preparing("Decoding source audio"))
        val source = measureElapsed(timing, "Decode") {
            AudioPcmDecoder(context).decode(uri, shouldCancel = shouldCancel)
        }
        onProgress(MdxRangeProgress.preparing("Resampling source audio"))
        val decoded = measureElapsed(timing, "Resample") {
            source.resampleTo(config.sampleRate, shouldCancel = shouldCancel)
        }
        throwIfCanceled(shouldCancel)

        val startFrame = msToFrame(startMs).coerceIn(0, decoded.frameCount)
        val requestedEndFrame = endMs?.let { msToFrame(it) } ?: decoded.frameCount
        val endFrame = requestedEndFrame.coerceIn(startFrame, decoded.frameCount)
        val targetFrames = endFrame - startFrame
        require(targetFrames > 0) { "Selected range is empty." }
        val sourcePcmSha256 = SourceSeparationCache.sha256Hex(source.pcm16)

        onProgress(MdxRangeProgress.preparing("Preparing model file"))
        val modelFile = measureElapsed(timing, "Model file") {
            MdxModelFile.get(context, modelVariant)
        }
        onProgress(MdxRangeProgress.preparing("Preparing output files"))
        val (vocalsFile, instrumentalFile, timingFile) = measureElapsed(timing, "Output setup") {
            outputDir.mkdirs()
            val baseName = safeBaseName(displayName)
            val rangeTag = "${modelVariant.outputTag}_${frameToMs(startFrame)}ms_${frameToMs(endFrame)}ms"
            Triple(
                uniqueOutputFile(outputDir, "${baseName}_${rangeTag}_vocals.wav"),
                uniqueOutputFile(outputDir, "${baseName}_${rangeTag}_instrumental.wav"),
                uniqueOutputFile(outputDir, "${baseName}_${rangeTag}_timing.txt"),
            )
        }

        val segmentPlan = SourceSeparationSegmentPlan.build(
            rangeStartFrame = startFrame,
            rangeEndFrame = endFrame,
            sampleRate = config.sampleRate,
            generationSize = config.generationSize,
            trim = config.trim,
            chunkSize = config.chunkSize,
            defaultState = if (segmentOutputDir != null) {
                SourceSeparationSegmentState.Queued
            } else {
                SourceSeparationSegmentState.Missing
            },
        )
        var currentSegmentPlan = segmentPlan
        segmentOutputDir?.mkdirs()

        val windowCount = segmentPlan.segmentCount
        onPrepared(
            MdxRangePreparation(
                vocalsFile = vocalsFile,
                instrumentalFile = instrumentalFile,
                timingFile = timingFile,
                startMs = frameToMs(startFrame),
                endMs = frameToMs(endFrame),
                frames = targetFrames,
                windowCount = windowCount,
                sourcePcmSha256 = sourcePcmSha256,
                sourceFrameCount = source.frameCount,
                sourceSampleRate = source.sampleRate,
                sourceChannelCount = source.channelCount,
                outputSampleRate = decoded.sampleRate,
                segmentPlan = segmentPlan,
            )
        )

        val environment = OrtEnvironment.getEnvironment()
        val spectrogram = MdxSpectrogram(config)
        val declaredOutputDataSizeBytes = if (segmentOutputDir != null) {
            targetFrames.toLong() * MdxDspConfig.STEREO_CHANNELS * Short.SIZE_BYTES
        } else {
            null
        }

        WavFileWriter(
            file = vocalsFile,
            sampleRate = config.sampleRate,
            channelCount = MdxDspConfig.STEREO_CHANNELS,
            declaredDataSizeBytes = declaredOutputDataSizeBytes,
        ).use { vocalsWriter ->
            WavFileWriter(
                file = instrumentalFile,
                sampleRate = config.sampleRate,
                channelCount = MdxDspConfig.STEREO_CHANNELS,
                declaredDataSizeBytes = declaredOutputDataSizeBytes,
            ).use { instrumentalWriter ->
                onProgress(MdxRangeProgress.preparing("Creating ONNX session"))
                val session = measureElapsed(timing, "Session setup") {
                    runtimeSettings.createSessionOptions().use { options ->
                        environment.createSession(modelFile.absolutePath, options)
                    }
                }
                session.use {
                    val inputName = session.inputInfo.keys.first()
                    val outputName = session.outputInfo.keys.first()
                    for (windowIndex in 0 until windowCount) {
                        throwIfCanceled(shouldCancel)
                        val generationStartFrame = startFrame + windowIndex * config.generationSize
                        val remainingFrames = endFrame - generationStartFrame
                        val writeFrames = minOf(config.generationSize, remainingFrames)
                        val segment = segmentPlan.segments[windowIndex]
                        currentSegmentPlan = currentSegmentPlan.withSegmentState(
                            segmentIndex = segment.index,
                            state = SourceSeparationSegmentState.Running,
                        )
                        onSegmentStateChanged(segment.index, SourceSeparationSegmentState.Running)
                        onProgress(MdxRangeProgress(windowIndex, windowCount, stage = "Preparing window ${windowIndex + 1}/${windowCount}"))
                        val mixWindow = measureElapsed(timing, "Window input") {
                            decoded.toStereoFloatContextWindow(
                                windowStartFrame = generationStartFrame - config.trim,
                                frames = config.chunkSize,
                            )
                        }

                        val modelOutputWindow = runWindow(
                            session = session,
                            inputName = inputName,
                            outputName = outputName,
                            spectrogram = spectrogram,
                            mixWindow = mixWindow,
                            timing = timing,
                        )
                        throwIfCanceled(shouldCancel)
                        val residualWindow = measureElapsed(timing, "Stem subtract") {
                            subtract(mixWindow, modelOutputWindow)
                        }
                        val vocalsWindow = when (modelVariant.modelOutputStem) {
                            MdxStem.VOCALS -> modelOutputWindow
                            MdxStem.INSTRUMENTAL -> residualWindow
                        }
                        val instrumentalWindow = when (modelVariant.modelOutputStem) {
                            MdxStem.VOCALS -> residualWindow
                            MdxStem.INSTRUMENTAL -> modelOutputWindow
                        }

                        val vocalsPcm = measureElapsed(timing, "PCM convert") {
                            stereoFloatToPcm16(
                                waveform = vocalsWindow,
                                startFrame = config.trim,
                                frames = writeFrames,
                            )
                        }
                        val instrumentalPcm = measureElapsed(timing, "PCM convert") {
                            stereoFloatToPcm16(
                                waveform = instrumentalWindow,
                                startFrame = config.trim,
                                frames = writeFrames,
                            )
                        }
                        measureElapsed(timing, "WAV write") {
                            vocalsWriter.writePcm16(vocalsPcm)
                            instrumentalWriter.writePcm16(instrumentalPcm)
                            if (segmentOutputDir != null) {
                                writeSegmentWav(segmentOutputDir, segment.vocalsPath, vocalsPcm)
                                writeSegmentWav(segmentOutputDir, segment.instrumentalPath, instrumentalPcm)
                            }
                        }
                        currentSegmentPlan = currentSegmentPlan.withSegmentState(
                            segmentIndex = segment.index,
                            state = SourceSeparationSegmentState.Ready,
                        )
                        onSegmentStateChanged(segment.index, SourceSeparationSegmentState.Ready)
                        onProgress(MdxRangeProgress(windowIndex + 1, windowCount, stage = "Processed window ${windowIndex + 1}/${windowCount}"))
                        throwIfCanceled(shouldCancel)
                    }
                }
            }
        }

        val elapsedMs = SystemClock.elapsedRealtime() - totalStartedAt
        onProgress(MdxRangeProgress(windowCount, windowCount, stage = "Writing timing report"))
        val timingReport = timing.toReport(
            audioDurationSeconds = targetFrames.toDouble() / config.sampleRate,
            windowCount = windowCount,
            totalMs = elapsedMs,
            runtimeSettings = runtimeSettings,
            modelVariant = modelVariant,
        )
        timingFile.writeText(
            timingReport.toFileText(
                vocalsFile = vocalsFile,
                instrumentalFile = instrumentalFile,
            ),
            Charsets.UTF_8,
        )

        return MdxRangeSeparationResult(
            vocalsFile = vocalsFile,
            instrumentalFile = instrumentalFile,
            timingFile = timingFile,
            startMs = frameToMs(startFrame),
            endMs = frameToMs(endFrame),
            frames = targetFrames,
            windowCount = windowCount,
            elapsedMs = elapsedMs,
            sourcePcmSha256 = sourcePcmSha256,
            sourceFrameCount = source.frameCount,
            sourceSampleRate = source.sampleRate,
            sourceChannelCount = source.channelCount,
            outputSampleRate = decoded.sampleRate,
            segmentPlan = currentSegmentPlan,
            timingReport = timingReport,
            runtimeSettings = runtimeSettings,
            modelVariant = modelVariant,
        )
    }

    private fun writeSegmentWav(rootDir: File, relativePath: String, pcm16: ByteArray) {
        val file = File(rootDir.parentFile ?: rootDir, relativePath)
        file.parentFile?.mkdirs()
        WavFileWriter(file, config.sampleRate, MdxDspConfig.STEREO_CHANNELS).use { writer ->
            writer.writePcm16(pcm16)
        }
    }

    private inline fun <T> measureElapsed(timing: MdxRangeTimingAccumulator, stage: String, block: () -> T): T {
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

    private fun runWindow(
        session: OrtSession,
        inputName: String,
        outputName: String,
        spectrogram: MdxSpectrogram,
        mixWindow: Array<FloatArray>,
        timing: MdxRangeTimingAccumulator,
    ): Array<FloatArray> {
        val modelInput = measureElapsed(timing, "STFT") {
            spectrogram.waveformToTensor(mixWindow)
        }
        val shape = longArrayOf(1, 4, config.dimF.toLong(), config.dimT.toLong())

        measureElapsed(timing, "Tensor create") {
            OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), FloatBuffer.wrap(modelInput), shape)
        }.use { tensor ->
            measureElapsed(timing, "ONNX inference") {
                session.run(mapOf(inputName to tensor))
            }.use { outputs ->
                val output = outputs[outputName].orElseThrow {
                    IllegalStateException("Missing ONNX output: $outputName")
                }.value
                @Suppress("UNCHECKED_CAST")
                val outputArray = output as Array<Array<Array<FloatArray>>>
                val flatOutput = measureElapsed(timing, "Output flatten") {
                    flattenOutput(outputArray)
                }
                return measureElapsed(timing, "ISTFT") {
                    spectrogram.tensorToWaveform(flatOutput)
                }
            }
        }
    }

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

    private fun stereoFloatToPcm16(waveform: Array<FloatArray>, startFrame: Int, frames: Int): ByteArray {
        val bytes = ByteArray(frames * MdxDspConfig.STEREO_CHANNELS * Short.SIZE_BYTES)
        var offset = 0
        val endFrame = startFrame + frames
        for (frame in startFrame until endFrame) {
            for (channel in 0 until MdxDspConfig.STEREO_CHANNELS) {
                val value = (waveform[channel][frame].coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                bytes[offset++] = (value and 0xFF).toByte()
                bytes[offset++] = ((value ushr 8) and 0xFF).toByte()
            }
        }
        return bytes
    }

    private fun flattenOutput(output: Array<Array<Array<FloatArray>>>): FloatArray {
        require(output.size == 1) { "Expected batch size 1, got ${output.size}." }
        val flat = FloatArray(config.tensorElementCount)
        var offset = 0
        for (channel in output[0]) {
            for (frequency in channel) {
                for (value in frequency) {
                    flat[offset++] = value
                }
            }
        }
        return flat
    }

    private fun subtract(mix: Array<FloatArray>, stem: Array<FloatArray>): Array<FloatArray> {
        return Array(MdxDspConfig.STEREO_CHANNELS) { channel ->
            FloatArray(config.chunkSize) { index ->
                mix[channel][index] - stem[channel][index]
            }
        }
    }

    private fun msToFrame(ms: Long): Int {
        return ((ms.coerceAtLeast(0) * config.sampleRate) / 1000L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }

    private fun frameToMs(frame: Int): Long {
        return (frame.toLong() * 1000L) / config.sampleRate
    }

    private fun safeBaseName(displayName: String): String {
        return displayName.substringBeforeLast('.')
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .ifBlank { "audio" }
    }

    private fun uniqueOutputFile(outputDir: File, name: String): File {
        val extensionIndex = name.lastIndexOf('.')
        val base = if (extensionIndex > 0) name.substring(0, extensionIndex) else name
        val extension = if (extensionIndex > 0) name.substring(extensionIndex) else ""
        var candidate = File(outputDir, name)
        var suffix = 2
        while (candidate.exists()) {
            candidate = File(outputDir, "${base}_$suffix$extension")
            suffix += 1
        }
        return candidate
    }
}

data class MdxRangeProgress(
    val completedWindows: Int,
    val totalWindows: Int,
    val stage: String? = null,
) {
    val percent: Int = if (totalWindows > 0) {
        ((completedWindows * 100.0) / totalWindows).roundToInt()
    } else {
        0
    }

    companion object {
        fun preparing(stage: String): MdxRangeProgress {
            return MdxRangeProgress(
                completedWindows = 0,
                totalWindows = 0,
                stage = stage,
            )
        }
    }
}

data class MdxRangePreparation(
    val vocalsFile: File,
    val instrumentalFile: File,
    val timingFile: File,
    val startMs: Long,
    val endMs: Long,
    val frames: Int,
    val windowCount: Int,
    val sourcePcmSha256: String,
    val sourceFrameCount: Int,
    val sourceSampleRate: Int,
    val sourceChannelCount: Int,
    val outputSampleRate: Int,
    val segmentPlan: SourceSeparationSegmentPlan,
)

data class MdxRangeSeparationResult(
    val vocalsFile: File,
    val instrumentalFile: File,
    val timingFile: File,
    val startMs: Long,
    val endMs: Long,
    val frames: Int,
    val windowCount: Int,
    val elapsedMs: Long,
    val sourcePcmSha256: String,
    val sourceFrameCount: Int,
    val sourceSampleRate: Int,
    val sourceChannelCount: Int,
    val outputSampleRate: Int,
    val segmentPlan: SourceSeparationSegmentPlan,
    val timingReport: MdxRangeTimingReport,
    val runtimeSettings: MdxRuntimeSettings,
    val modelVariant: MdxModelVariant,
) {
    val durationSeconds: Double
        get() = frames.toDouble() / outputSampleRate
}
