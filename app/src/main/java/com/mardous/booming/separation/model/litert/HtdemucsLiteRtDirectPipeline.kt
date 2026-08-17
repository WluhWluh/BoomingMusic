package com.mardous.booming.separation.model.litert

import com.mardous.booming.separation.model.HtdemucsNeuralInputs
import com.mardous.booming.separation.model.HtdemucsNeuralOutputs
import com.mardous.booming.separation.model.HtdemucsPipelineAdapter
import java.io.File

internal interface HtdemucsDirectPipeline : AutoCloseable {
    val implementationId: String

    fun writeInputs(inputs: HtdemucsNeuralInputs)

    fun preprocessAndWriteInput(normalizedPlanarStereo: FloatArray)

    fun run()

    fun readOutputs(): HtdemucsNeuralOutputs

    fun postprocessOutputs(): FloatArray

    fun discard()
}

internal fun interface HtdemucsDirectPipelineFactory {
    fun create(
        modelFile: File,
        stemCount: Int,
        cpuThreads: Int,
        workerCount: Int,
    ): HtdemucsDirectPipeline
}

internal object HtdemucsNativeDirectPipelineFactory : HtdemucsDirectPipelineFactory {
    override fun create(
        modelFile: File,
        stemCount: Int,
        cpuThreads: Int,
        workerCount: Int,
    ): HtdemucsDirectPipeline {
        val coreLibrary = requireLoadedHtdemucsCoreLibrary()
        return HtdemucsLiteRtDirectPipeline(
            coreLibraryFile = coreLibrary,
            modelFile = modelFile,
            stemCount = stemCount,
            cpuThreads = cpuThreads,
            workerCount = workerCount,
        )
    }
}

internal class HtdemucsLiteRtDirectPipeline(
    coreLibraryFile: File,
    modelFile: File,
    private val stemCount: Int,
    cpuThreads: Int,
    workerCount: Int,
) : HtdemucsDirectPipeline {
    override val implementationId: String = "native-packed-litert-c-direct-input-v1"

    init {
        require(stemCount == 4 || stemCount == 6)
        require(cpuThreads in 1..16)
        require(workerCount in 1..4)
    }

    private var handle = nativeCreate(
        coreLibraryPath = coreLibraryFile.requireAbsoluteFile("LiteRT core").absolutePath,
        modelPath = modelFile.requireAbsoluteFile("HTDemucs model").absolutePath,
        sourceCount = stemCount,
        windowSamples = HtdemucsPipelineAdapter.WINDOW_SAMPLES,
        spectrumFrames = HtdemucsPipelineAdapter.FRAME_COUNT,
        fftSize = HtdemucsPipelineAdapter.FFT_SIZE,
        hopSize = HtdemucsPipelineAdapter.HOP_LENGTH,
        dimF = HtdemucsPipelineAdapter.FREQUENCY_BINS,
        outerPadLeft = OUTER_PAD_LEFT,
        framePadLeft = FRAME_PAD_LEFT,
        framePadRight = FRAME_PAD_RIGHT,
        centerTrim = CENTER_TRIM,
        cpuThreads = cpuThreads,
        workerCount = workerCount,
    ).also { created ->
        check(created != 0L) {
            "Native LiteRT HTDemucs pipeline creation failed: ${nativeLastError()}"
        }
    }

    @Synchronized
    override fun writeInputs(inputs: HtdemucsNeuralInputs) {
        checkOpen()
        require(inputs.waveform.size == WAVEFORM_INPUT_ELEMENTS)
        require(inputs.spectrum.size == SPECTRUM_INPUT_ELEMENTS)
        check(nativeWriteInputs(handle, inputs.waveform, inputs.spectrum)) {
            "Native LiteRT HTDemucs input write failed: ${nativeLastError()}"
        }
    }

    @Synchronized
    override fun preprocessAndWriteInput(normalizedPlanarStereo: FloatArray) {
        checkOpen()
        require(normalizedPlanarStereo.size == WAVEFORM_INPUT_ELEMENTS)
        check(nativePreprocessAndWriteInput(handle, normalizedPlanarStereo)) {
            "Native LiteRT HTDemucs preprocess failed: ${nativeLastError()}"
        }
    }

    @Synchronized
    override fun run() {
        checkOpen()
        check(nativeRun(handle)) {
            "Native LiteRT HTDemucs invocation failed: ${nativeLastError()}"
        }
    }

    @Synchronized
    override fun readOutputs(): HtdemucsNeuralOutputs {
        checkOpen()
        val frequency = FloatArray(stemCount * SPECTRUM_INPUT_ELEMENTS)
        val waveform = FloatArray(stemCount * WAVEFORM_INPUT_ELEMENTS)
        check(nativeReadOutputs(handle, frequency, waveform)) {
            "Native LiteRT HTDemucs output read failed: ${nativeLastError()}"
        }
        return HtdemucsNeuralOutputs(frequency, waveform)
    }

    @Synchronized
    override fun postprocessOutputs(): FloatArray {
        checkOpen()
        return FloatArray(stemCount * WAVEFORM_INPUT_ELEMENTS).also { output ->
            check(nativePostprocessOutputs(handle, output)) {
                "Native LiteRT HTDemucs postprocess failed: ${nativeLastError()}"
            }
        }
    }

    @Synchronized
    override fun discard() {
        checkOpen()
        check(nativeDiscard(handle)) {
            "Native LiteRT HTDemucs discard failed: ${nativeLastError()}"
        }
    }

    @Synchronized
    override fun close() {
        if (handle == 0L) return
        nativeDestroy(handle)
        handle = 0L
    }

    private fun checkOpen() {
        check(handle != 0L) { "Native LiteRT HTDemucs pipeline is closed." }
    }

    private fun File.requireAbsoluteFile(label: String): File = also { file ->
        require(file.isAbsolute && file.isFile) { "$label path is invalid." }
    }

    private external fun nativeCreate(
        coreLibraryPath: String,
        modelPath: String,
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
        cpuThreads: Int,
        workerCount: Int,
    ): Long

    private external fun nativeWriteInputs(
        handle: Long,
        waveform: FloatArray,
        spectrum: FloatArray,
    ): Boolean

    private external fun nativePreprocessAndWriteInput(
        handle: Long,
        waveform: FloatArray,
    ): Boolean

    private external fun nativeRun(handle: Long): Boolean

    private external fun nativeReadOutputs(
        handle: Long,
        frequency: FloatArray,
        waveform: FloatArray,
    ): Boolean

    private external fun nativePostprocessOutputs(
        handle: Long,
        output: FloatArray,
    ): Boolean

    private external fun nativeDiscard(handle: Long): Boolean
    private external fun nativeDestroy(handle: Long)
    private external fun nativeLastError(): String

    private companion object {
        const val OUTER_PAD_LEFT = 1_536
        const val FRAME_PAD_LEFT = 2
        const val FRAME_PAD_RIGHT = 2
        const val CENTER_TRIM = HtdemucsPipelineAdapter.FFT_SIZE / 2
        const val WAVEFORM_INPUT_ELEMENTS =
            HtdemucsPipelineAdapter.CHANNEL_COUNT * HtdemucsPipelineAdapter.WINDOW_SAMPLES
        const val SPECTRUM_INPUT_ELEMENTS =
            HtdemucsPipelineAdapter.FEATURE_COUNT * HtdemucsPipelineAdapter.FREQUENCY_BINS *
                HtdemucsPipelineAdapter.FRAME_COUNT

        init {
            System.loadLibrary("booming_ss_separation")
        }
    }
}
