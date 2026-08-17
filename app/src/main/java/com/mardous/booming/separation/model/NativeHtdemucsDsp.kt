package com.mardous.booming.separation.model

import java.util.concurrent.CancellationException

internal class NativeHtdemucsDsp(
    private val sourceCount: Int,
    private val windowSamples: Int = HtdemucsPipelineAdapter.WINDOW_SAMPLES,
    private val spectrumFrames: Int = HtdemucsPipelineAdapter.FRAME_COUNT,
    workerCount: Int = 4,
) : HtdemucsDspSession {
    override val implementationId: String = "native-pocketfft-region-copy-v2"

    private var handle = nativeCreate(
        sourceCount = sourceCount,
        windowSamples = windowSamples,
        spectrumFrames = spectrumFrames,
        fftSize = HtdemucsPipelineAdapter.FFT_SIZE,
        hopSize = HtdemucsPipelineAdapter.HOP_LENGTH,
        dimF = HtdemucsPipelineAdapter.FREQUENCY_BINS,
        outerPadLeft = OUTER_PAD_LEFT,
        framePadLeft = FRAME_PAD_LEFT,
        framePadRight = FRAME_PAD_RIGHT,
        centerTrim = CENTER_TRIM,
        workerCount = workerCount,
    ).also { created ->
        check(created != 0L) {
            "Native HTDemucs DSP plan creation failed: ${nativeLastError()}"
        }
    }

    @Synchronized
    override fun waveformToSpectrum(planarStereoWaveform: FloatArray): FloatArray {
        checkOpen()
        require(planarStereoWaveform.size == CHANNEL_COUNT * windowSamples)
        return FloatArray(
            FEATURE_COUNT * HtdemucsPipelineAdapter.FREQUENCY_BINS * spectrumFrames,
        ).also { output ->
            check(nativePreprocess(handle, planarStereoWaveform, output)) {
                "Native HTDemucs preprocess failed: ${nativeLastError()}"
            }
        }
    }

    @Synchronized
    override fun frequencyToWaveform(
        packedFrequency: FloatArray,
        stemCount: Int,
        shouldCancel: () -> Boolean,
    ): FloatArray {
        checkOpen()
        require(stemCount == sourceCount)
        throwIfCanceled(shouldCancel)
        return FloatArray(sourceCount * CHANNEL_COUNT * windowSamples).also { output ->
            check(nativePostprocess(handle, packedFrequency, null, output)) {
                "Native HTDemucs postprocess failed: ${nativeLastError()}"
            }
            throwIfCanceled(shouldCancel)
        }
    }

    @Synchronized
    override fun reconstructBranches(
        packedFrequency: FloatArray,
        timeWaveform: FloatArray,
        stemCount: Int,
        shouldCancel: () -> Boolean,
    ): FloatArray {
        checkOpen()
        require(stemCount == sourceCount)
        throwIfCanceled(shouldCancel)
        return FloatArray(sourceCount * CHANNEL_COUNT * windowSamples).also { output ->
            check(nativePostprocess(handle, packedFrequency, timeWaveform, output)) {
                "Native HTDemucs fused postprocess failed: ${nativeLastError()}"
            }
            throwIfCanceled(shouldCancel)
        }
    }

    @Synchronized
    override fun close() {
        if (handle == 0L) return
        nativeDestroy(handle)
        handle = 0L
    }

    private fun checkOpen() {
        check(handle != 0L) { "Native HTDemucs DSP is closed." }
    }

    private fun throwIfCanceled(shouldCancel: () -> Boolean) {
        if (Thread.currentThread().isInterrupted || shouldCancel()) {
            throw CancellationException("HTDemucs native DSP was canceled.")
        }
    }

    private external fun nativeCreate(
        sourceCount: Int,
        windowSamples: Int,
        spectrumFrames: Int,
        fftSize: Int,
        hopSize: Int,
        dimF: Int,
        outerPadLeft: Int,
        framePadLeft: Int,
        framePadRight: Int,
        centerTrim: Int,
        workerCount: Int,
    ): Long

    private external fun nativePreprocess(
        handle: Long,
        waveform: FloatArray,
        spectrum: FloatArray,
    ): Boolean

    private external fun nativePostprocess(
        handle: Long,
        frequency: FloatArray,
        timeWaveform: FloatArray?,
        output: FloatArray,
    ): Boolean

    private external fun nativeDestroy(handle: Long)
    private external fun nativeLastError(): String

    private companion object {
        const val CHANNEL_COUNT = 2
        const val FEATURE_COUNT = 4
        const val OUTER_PAD_LEFT = 1_536
        const val CENTER_TRIM = HtdemucsPipelineAdapter.FFT_SIZE / 2
        const val FRAME_PAD_LEFT = 2
        const val FRAME_PAD_RIGHT = 2

        init {
            System.loadLibrary("booming_ss_separation")
        }
    }
}

internal data class NativeHtdemucsOlaChunk(
    val finalizedFrames: Int,
    val nextCarryLength: Int,
)

internal class NativeHtdemucsOlaState(
    private val sourceCount: Int,
) : AutoCloseable {
    val outputElements: Int = sourceCount * CHANNEL_COUNT * WINDOW_SAMPLES

    private var handle = nativeCreate(sourceCount).also { created ->
        check(created != 0L) {
            "Native HTDemucs OLA creation failed: ${nativeLastError()}"
        }
    }

    @Synchronized
    fun processInto(
        combinedWindow: FloatArray,
        actualSamples: Int,
        cropLeft: Int,
        hasNext: Boolean,
        normalizationScale: Float,
        normalizationMean: Float,
        output: FloatArray,
    ): NativeHtdemucsOlaChunk {
        checkOpen()
        require(combinedWindow.size == outputElements)
        require(output.size == outputElements)
        require(combinedWindow !== output)
        require(actualSamples in 1..WINDOW_SAMPLES)
        require(cropLeft >= 0 && cropLeft + actualSamples <= WINDOW_SAMPLES)
        require(normalizationScale.isFinite() && normalizationScale > 0f)
        require(normalizationMean.isFinite())
        val finalized = nativeProcess(
            handle,
            combinedWindow,
            actualSamples,
            cropLeft,
            hasNext,
            normalizationScale,
            normalizationMean,
            output,
        )
        check(finalized >= 0) { "Native HTDemucs OLA failed: ${nativeLastError()}" }
        val carry = nativeCarryLength(handle)
        check(carry in 0..OVERLAP_SAMPLES)
        check(finalized == if (hasNext) STRIDE_SAMPLES else actualSamples)
        check(carry == if (hasNext) actualSamples - STRIDE_SAMPLES else 0)
        return NativeHtdemucsOlaChunk(finalized, carry)
    }

    @Synchronized
    fun reset() {
        checkOpen()
        nativeReset(handle)
        check(nativeCarryLength(handle) == 0)
    }

    @Synchronized
    fun carryLength(): Int {
        checkOpen()
        return nativeCarryLength(handle).also { check(it in 0..OVERLAP_SAMPLES) }
    }

    @Synchronized
    override fun close() {
        if (handle == 0L) return
        nativeDestroy(handle)
        handle = 0L
    }

    private fun checkOpen() {
        check(handle != 0L) { "Native HTDemucs OLA state is closed." }
    }

    private external fun nativeCreate(sourceCount: Int): Long
    private external fun nativeProcess(
        handle: Long,
        combinedWindow: FloatArray,
        actualSamples: Int,
        cropLeft: Int,
        hasNext: Boolean,
        normalizationScale: Float,
        normalizationMean: Float,
        output: FloatArray,
    ): Int
    private external fun nativeCarryLength(handle: Long): Int
    private external fun nativeReset(handle: Long)
    private external fun nativeDestroy(handle: Long)
    private external fun nativeLastError(): String

    private companion object {
        const val CHANNEL_COUNT = 2
        const val WINDOW_SAMPLES = HtdemucsPipelineAdapter.WINDOW_SAMPLES
        const val STRIDE_SAMPLES = HtdemucsTrackWindowPlanner.STRIDE_SAMPLES
        const val OVERLAP_SAMPLES = HtdemucsTrackWindowPlanner.OVERLAP_SAMPLES

        init {
            System.loadLibrary("booming_ss_separation")
        }
    }
}

internal object NativeHtdemucsPcm16 {
    fun encodePlanar(
        input: FloatArray,
        leftOffset: Int,
        rightOffset: Int,
        frameCount: Int,
        output: ByteArray,
    ): Int = nativeEncodePlanar(input, leftOffset, rightOffset, frameCount, output)

    fun implementationId(): String = nativeImplementationId()

    private external fun nativeEncodePlanar(
        input: FloatArray,
        leftOffset: Int,
        rightOffset: Int,
        frameCount: Int,
        output: ByteArray,
    ): Int

    private external fun nativeImplementationId(): String

    init {
        System.loadLibrary("booming_ss_separation")
    }
}
