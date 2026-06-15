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
import java.io.File
import java.nio.FloatBuffer
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
        displayName: String,
        startMs: Long = 0L,
        endMs: Long? = null,
        runtimeSettings: MdxRuntimeSettings = this.runtimeSettings,
        modelVariant: MdxModelVariant = this.modelVariant,
        onProgress: (MdxRangeProgress) -> Unit = {},
    ): MdxRangeSeparationResult {
        val totalStartedAt = SystemClock.elapsedRealtime()
        val source = AudioPcmDecoder(context).decode(uri)
        val decoded = source.resampleTo(config.sampleRate)

        val startFrame = msToFrame(startMs).coerceIn(0, decoded.frameCount)
        val requestedEndFrame = endMs?.let { msToFrame(it) } ?: decoded.frameCount
        val endFrame = requestedEndFrame.coerceIn(startFrame, decoded.frameCount)
        val targetFrames = endFrame - startFrame
        require(targetFrames > 0) { "Selected range is empty." }

        val modelFile = MdxModelFile.get(context, modelVariant)
        outputDir.mkdirs()

        val baseName = safeBaseName(displayName)
        val rangeTag = "${modelVariant.outputTag}_${frameToMs(startFrame)}ms_${frameToMs(endFrame)}ms"
        val vocalsFile = uniqueOutputFile(outputDir, "${baseName}_${rangeTag}_vocals.wav")
        val instrumentalFile = uniqueOutputFile(outputDir, "${baseName}_${rangeTag}_instrumental.wav")

        val windowCount = ceil(targetFrames.toDouble() / config.generationSize.toDouble()).toInt()
        val environment = OrtEnvironment.getEnvironment()
        val spectrogram = MdxSpectrogram(config)

        WavFileWriter(vocalsFile, config.sampleRate, MdxDspConfig.STEREO_CHANNELS).use { vocalsWriter ->
            WavFileWriter(instrumentalFile, config.sampleRate, MdxDspConfig.STEREO_CHANNELS).use { instrumentalWriter ->
                val session = runtimeSettings.createSessionOptions().use { options ->
                    environment.createSession(modelFile.absolutePath, options)
                }
                session.use {
                    val inputName = session.inputInfo.keys.first()
                    val outputName = session.outputInfo.keys.first()
                    for (windowIndex in 0 until windowCount) {
                        val generationStartFrame = startFrame + windowIndex * config.generationSize
                        val remainingFrames = endFrame - generationStartFrame
                        val writeFrames = minOf(config.generationSize, remainingFrames)
                        val mixWindow = decoded.toStereoFloatContextWindow(
                            windowStartFrame = generationStartFrame - config.trim,
                            frames = config.chunkSize,
                        )

                        val modelOutputWindow = runWindow(
                            session = session,
                            inputName = inputName,
                            outputName = outputName,
                            spectrogram = spectrogram,
                            mixWindow = mixWindow,
                        )
                        val residualWindow = subtract(mixWindow, modelOutputWindow)
                        val vocalsWindow = when (modelVariant.modelOutputStem) {
                            MdxStem.VOCALS -> modelOutputWindow
                            MdxStem.INSTRUMENTAL -> residualWindow
                        }
                        val instrumentalWindow = when (modelVariant.modelOutputStem) {
                            MdxStem.VOCALS -> residualWindow
                            MdxStem.INSTRUMENTAL -> modelOutputWindow
                        }

                        val vocalsPcm = stereoFloatToPcm16(
                            waveform = vocalsWindow,
                            startFrame = config.trim,
                            frames = writeFrames,
                        )
                        val instrumentalPcm = stereoFloatToPcm16(
                            waveform = instrumentalWindow,
                            startFrame = config.trim,
                            frames = writeFrames,
                        )
                        vocalsWriter.writePcm16(vocalsPcm)
                        instrumentalWriter.writePcm16(instrumentalPcm)
                        onProgress(MdxRangeProgress(windowIndex + 1, windowCount))
                    }
                }
            }
        }

        return MdxRangeSeparationResult(
            vocalsFile = vocalsFile,
            instrumentalFile = instrumentalFile,
            startMs = frameToMs(startFrame),
            endMs = frameToMs(endFrame),
            frames = targetFrames,
            windowCount = windowCount,
            elapsedMs = SystemClock.elapsedRealtime() - totalStartedAt,
            sourcePcmSha256 = SourceSeparationCache.sha256Hex(source.pcm16),
            sourceFrameCount = source.frameCount,
            sourceSampleRate = source.sampleRate,
            sourceChannelCount = source.channelCount,
            outputSampleRate = decoded.sampleRate,
            runtimeSettings = runtimeSettings,
            modelVariant = modelVariant,
        )
    }

    private fun runWindow(
        session: OrtSession,
        inputName: String,
        outputName: String,
        spectrogram: MdxSpectrogram,
        mixWindow: Array<FloatArray>,
    ): Array<FloatArray> {
        val modelInput = spectrogram.waveformToTensor(mixWindow)
        val shape = longArrayOf(1, 4, config.dimF.toLong(), config.dimT.toLong())

        OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), FloatBuffer.wrap(modelInput), shape).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { outputs ->
                val output = outputs[outputName].orElseThrow {
                    IllegalStateException("Missing ONNX output: $outputName")
                }.value
                @Suppress("UNCHECKED_CAST")
                val outputArray = output as Array<Array<Array<FloatArray>>>
                return spectrogram.tensorToWaveform(flattenOutput(outputArray))
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
) {
    val percent: Int = ((completedWindows * 100.0) / totalWindows).roundToInt()
}

data class MdxRangeSeparationResult(
    val vocalsFile: File,
    val instrumentalFile: File,
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
    val runtimeSettings: MdxRuntimeSettings,
    val modelVariant: MdxModelVariant,
) {
    val durationSeconds: Double
        get() = frames.toDouble() / outputSampleRate
}
