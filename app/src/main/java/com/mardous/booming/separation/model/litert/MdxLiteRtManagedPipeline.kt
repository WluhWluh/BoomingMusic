package com.mardous.booming.separation.model.litert

import com.mardous.booming.separation.model.MdxDspConfig
import com.mardous.booming.separation.model.MdxExecutionProfile
import com.mardous.booming.separation.model.MdxTensorDataType
import com.mardous.booming.separation.model.MdxTensorLayout
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

internal class MdxLiteRtManagedPipeline(
    coreLibraryFile: File,
    modelFile: File,
    private val profile: MdxExecutionProfile,
    cpuThreads: Int,
    val backend: Backend,
    val slotCount: Int = 1,
) : AutoCloseable {
    private val config: MdxDspConfig = profile.dspConfig
    private val lifecycleLock = ReentrantReadWriteLock()
    private val tensorOutputs = Array(slotCount) { FloatArray(profile.outputTensor.elementCount) }
    private val waveformOutputs = Array(slotCount) {
        Array(MdxDspConfig.STEREO_CHANNELS) { FloatArray(config.chunkSize) }
    }
    private var handle: Long

    init {
        require(coreLibraryFile.isAbsolute && coreLibraryFile.isFile) {
            "The verified LiteRT core library path is invalid."
        }
        require(modelFile.isAbsolute && modelFile.isFile) { "The MDX model path is invalid." }
        require(slotCount in 1..2) { "Managed buffer slot count must be 1 or 2." }
        require(cpuThreads in 1..16) { "LiteRT CPU thread count must be in 1..16." }
        require(profile.inputTensor.layout == MdxTensorLayout.Nhwc &&
            profile.outputTensor.layout == MdxTensorLayout.Nhwc &&
            profile.inputTensor.dataType == MdxTensorDataType.Float32 &&
            profile.outputTensor.dataType == MdxTensorDataType.Float32
        ) { "The native managed pipeline requires FP32 NHWC tensors." }

        handle = nativeCreate(
            coreLibraryPath = coreLibraryFile.absolutePath,
            modelPath = modelFile.absolutePath,
            inputName = requireNotNull(profile.inputTensor.name),
            outputName = requireNotNull(profile.outputTensor.name),
            nFft = config.nFft,
            hopLength = config.hopLength,
            dimF = config.dimF,
            dimT = config.dimT,
            chunkSize = config.chunkSize,
            cpuThreads = cpuThreads,
            boundedGpu = backend == Backend.BoundedGpu,
            slotCount = slotCount,
        )
        if (handle == 0L) throw nativeFailure(MdxLiteRtFailureStage.ModelCompile)
    }

    fun writeTensorNchw(input: FloatArray, slot: Int = 0) = lifecycleLock.read {
        checkOpen()
        requireSlot(slot)
        require(input.size == profile.inputTensor.elementCount) {
            "Expected ${profile.inputTensor.elementCount} input elements, got ${input.size}."
        }
        if (!nativeWriteTensorNchw(handle, slot, input)) {
            throw nativeFailure(MdxLiteRtFailureStage.InputWrite)
        }
    }

    fun preprocessWaveform(waveform: Array<FloatArray>, slot: Int = 0) = lifecycleLock.read {
        checkOpen()
        requireSlot(slot)
        require(waveform.size == MdxDspConfig.STEREO_CHANNELS) { "Expected stereo waveform." }
        waveform.forEachIndexed { channel, samples ->
            require(samples.size == config.chunkSize) {
                "Expected channel $channel to contain ${config.chunkSize} samples, got ${samples.size}."
            }
        }
        if (!nativePreprocessWaveform(handle, slot, waveform[0], waveform[1])) {
            throw nativeFailure(MdxLiteRtFailureStage.InputWrite)
        }
    }

    fun run(slot: Int = 0) = lifecycleLock.read {
        checkOpen()
        requireSlot(slot)
        if (!nativeRun(handle, slot)) throw nativeFailure(MdxLiteRtFailureStage.Invocation)
    }

    fun readTensorNchw(slot: Int = 0): FloatArray = lifecycleLock.read {
        checkOpen()
        requireSlot(slot)
        tensorOutputs[slot].also { output ->
            if (!nativeReadTensorNchw(handle, slot, output)) {
                throw nativeFailure(MdxLiteRtFailureStage.OutputRead)
            }
        }
    }

    fun postprocessWaveform(slot: Int = 0): Array<FloatArray> = lifecycleLock.read {
        checkOpen()
        requireSlot(slot)
        waveformOutputs[slot].also { output ->
            if (!nativePostprocessWaveform(handle, slot, output[0], output[1])) {
                throw nativeFailure(MdxLiteRtFailureStage.OutputRead)
            }
        }
    }

    fun discard(slot: Int) = lifecycleLock.read {
        checkOpen()
        requireSlot(slot)
        if (!nativeDiscard(handle, slot)) throw nativeFailure(MdxLiteRtFailureStage.Cleanup)
    }

    fun isInvocationInFlight(): Boolean = lifecycleLock.read {
        checkOpen()
        nativeIsInvocationInFlight(handle)
    }

    override fun close() = lifecycleLock.write {
        if (handle == 0L) return@write
        if (!nativeDestroy(handle)) throw nativeFailure(MdxLiteRtFailureStage.Cleanup)
        handle = 0L
    }

    private fun checkOpen() {
        check(handle != 0L) { "Native LiteRT managed pipeline is closed." }
    }

    private fun requireSlot(slot: Int) {
        require(slot in 0 until slotCount) { "Slot $slot is outside 0 until $slotCount." }
    }

    private fun nativeFailure(defaultStage: MdxLiteRtFailureStage): MdxLiteRtBackendException {
        val stage = MdxLiteRtFailureStage.entries.getOrElse(nativeLastErrorStage()) { defaultStage }
        val detail = nativeLastError().ifBlank { "Unknown native LiteRT pipeline failure." }
        return MdxLiteRtBackendException(
            stage = stage,
            isRecoverable = stage != MdxLiteRtFailureStage.TensorMetadata &&
                stage != MdxLiteRtFailureStage.Cleanup,
            cause = IllegalStateException(detail),
        )
    }

    private external fun nativeCreate(
        coreLibraryPath: String,
        modelPath: String,
        inputName: String,
        outputName: String,
        nFft: Int,
        hopLength: Int,
        dimF: Int,
        dimT: Int,
        chunkSize: Int,
        cpuThreads: Int,
        boundedGpu: Boolean,
        slotCount: Int,
    ): Long

    private external fun nativeWriteTensorNchw(
        handle: Long,
        slot: Int,
        tensor: FloatArray,
    ): Boolean

    private external fun nativePreprocessWaveform(
        handle: Long,
        slot: Int,
        left: FloatArray,
        right: FloatArray,
    ): Boolean

    private external fun nativeRun(handle: Long, slot: Int): Boolean

    private external fun nativeReadTensorNchw(
        handle: Long,
        slot: Int,
        tensor: FloatArray,
    ): Boolean

    private external fun nativePostprocessWaveform(
        handle: Long,
        slot: Int,
        left: FloatArray,
        right: FloatArray,
    ): Boolean

    private external fun nativeDiscard(handle: Long, slot: Int): Boolean

    private external fun nativeIsInvocationInFlight(handle: Long): Boolean

    private external fun nativeDestroy(handle: Long): Boolean

    private external fun nativeLastError(): String

    private external fun nativeLastErrorStage(): Int

    enum class Backend {
        Cpu,
        BoundedGpu,
    }

    private companion object {
        init {
            System.loadLibrary("booming_ss_separation")
        }
    }
}
