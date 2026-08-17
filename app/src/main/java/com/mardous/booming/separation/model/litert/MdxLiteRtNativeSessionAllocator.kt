package com.mardous.booming.separation.model.litert

import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import com.google.ai.edge.litert.TensorType
import com.mardous.booming.separation.model.MdxCompatibilityDecision
import com.mardous.booming.separation.model.MdxExecutionProfile
import com.mardous.booming.separation.model.MdxInferenceBackend
import com.mardous.booming.separation.model.MdxInferenceCompatibilityException
import com.mardous.booming.separation.model.MdxInferenceSession
import com.mardous.booming.separation.model.MdxModelArtifact
import com.mardous.booming.separation.model.MdxRuntimeDiagnostics
import com.mardous.booming.separation.model.MdxTensorDataType
import com.mardous.booming.separation.model.MdxTensorLayout
import com.mardous.booming.separation.model.MdxTensorLayoutConverter
import com.mardous.booming.separation.model.MdxTensorSpec
import com.mardous.booming.separation.model.MdxWaveformInferenceSession
import com.mardous.booming.separation.model.runNonInterruptibleMdxInference
import com.mardous.booming.separation.model.throwIfMdxInferenceCanceled
import com.mardous.booming.separation.runtime.SourceSeparationCpuRuntimeInstallation
import com.mardous.booming.separation.runtime.SourceSeparationRuntimeBootstrap
import java.util.concurrent.CancellationException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal enum class MdxLiteRtFailureStage {
    EnvironmentCreate,
    AcceleratorDiscovery,
    ModelCompile,
    TensorMetadata,
    BufferAllocation,
    InputWrite,
    Invocation,
    OutputRead,
    OutputValidation,
    Cleanup,
}

internal class MdxLiteRtBackendException(
    val stage: MdxLiteRtFailureStage,
    val isRecoverable: Boolean,
    cause: Throwable,
) : IllegalStateException(
    "LiteRT ${stage.name} failed: ${cause.message.orEmpty()}",
    cause,
)

internal object MdxLiteRtNativeSessionAllocator : MdxLiteRtSessionAllocator {
    override fun create(
        artifact: MdxModelArtifact,
        profile: MdxExecutionProfile,
        cpuThreads: Int,
        xnnPackFlags: Int?,
        compatibility: MdxCompatibilityDecision,
    ): MdxInferenceSession {
        val installation = runLiteRtOperation(MdxLiteRtFailureStage.EnvironmentCreate) {
            LiteRt220RuntimeIdentity.requireExact(
                SourceSeparationRuntimeBootstrap.requireLoadedInstallation(),
            )
        }
        val diagnostics = MdxRuntimeDiagnostics(
            runtimeName = "LiteRT 2.2.0",
            backend = MdxInferenceBackend.LiteRtCpu,
            cpuThreads = cpuThreads,
            detail = buildString {
                append(compatibilityDetail(compatibility))
                xnnPackFlags?.let { append(", xnnpackFlags=").append(it) }
            },
        )
        return if (!shouldUseJvmMdxCpuPipeline(installation.identity.abi, xnnPackFlags)) {
            createManagedLiteRtSession(
                installation = installation,
                artifact = artifact,
                profile = profile,
                cpuThreads = cpuThreads,
                diagnostics = diagnostics,
                backend = MdxLiteRtManagedPipeline.Backend.Cpu,
            )
        } else {
            createJvmLiteRtSession(
                artifact = artifact,
                profile = profile,
                requiredAccelerator = Accelerator.CPU,
                options = CompiledModel.Options(Accelerator.CPU).apply {
                    this.cpuOptions = CompiledModel.CpuOptions(cpuThreads, xnnPackFlags, null)
                },
                diagnostics = diagnostics.copy(
                    detail = diagnostics.detail + if (installation.identity.abi == "x86") {
                        ", pipeline=jvm-tensor-buffer-x86-fallback"
                    } else {
                        ", pipeline=jvm-tensor-buffer-flags-fallback"
                    },
                ),
            )
        }
    }
}

internal object MdxLiteRtNativeGpuSessionAllocator : MdxLiteRtGpuSessionAllocator {
    override fun create(
        artifact: MdxModelArtifact,
        profile: MdxExecutionProfile,
        runtimeProfile: MdxLiteRtGpuRuntimeProfile,
        compatibility: MdxCompatibilityDecision,
    ): MdxInferenceSession {
        val installation = runLiteRtOperation(MdxLiteRtFailureStage.EnvironmentCreate) {
            LiteRt220RuntimeIdentity.requireExact(
                SourceSeparationRuntimeBootstrap.requireLoadedInstallation(),
            )
        }
        val boundedRuntimeEnabled = runtimeProfile.profileId ==
            MdxLiteRtBoundedGpuContract.PROFILE_ID
        val boundedCapability = if (boundedRuntimeEnabled) {
            try {
                MdxLiteRtBoundedGpuRuntime.requireExactCapability()
            } catch (error: Exception) {
                throw MdxLiteRtBackendException(
                    stage = MdxLiteRtFailureStage.AcceleratorDiscovery,
                    isRecoverable = true,
                    cause = error,
                )
            }
        } else {
            null
        }
        val diagnostics = MdxRuntimeDiagnostics(
            runtimeName = "LiteRT 2.2.0",
            backend = MdxInferenceBackend.LiteRtGpu,
            cpuThreads = null,
            detail = buildString {
                append("layout=NHWC, profile=").append(runtimeProfile.profileId)
                append(", api=").append(runtimeProfile.api.name)
                append(", precision=").append(runtimeProfile.precision.name)
                append(", priority=").append(runtimeProfile.priority?.name ?: "Default")
                boundedCapability?.let {
                    append(", boundedRuntime=").append(it.detail)
                }
                append(", ").append(compatibilityDetail(compatibility))
            },
        )
        return if (boundedRuntimeEnabled) {
            createManagedLiteRtSession(
                installation = installation,
                artifact = artifact,
                profile = profile,
                cpuThreads = 1,
                diagnostics = diagnostics,
                boundedRuntimeEnabled = true,
                backend = MdxLiteRtManagedPipeline.Backend.BoundedGpu,
            )
        } else {
            createJvmLiteRtSession(
                artifact = artifact,
                profile = profile,
                requiredAccelerator = Accelerator.GPU,
                options = CompiledModel.Options(Accelerator.GPU).apply {
                    gpuOptions = CompiledModel.GpuOptions(
                        precision = runtimeProfile.precision.toLiteRtPrecision(),
                        backend = runtimeProfile.api.toLiteRtBackend(),
                        priority = runtimeProfile.priority?.toLiteRtPriority(),
                    )
                },
                diagnostics = diagnostics.copy(
                    detail = diagnostics.detail + ", pipeline=jvm-tensor-buffer-fallback",
                ),
            )
        }
    }
}

private fun createManagedLiteRtSession(
    installation: SourceSeparationCpuRuntimeInstallation,
    artifact: MdxModelArtifact,
    profile: MdxExecutionProfile,
    cpuThreads: Int,
    diagnostics: MdxRuntimeDiagnostics,
    backend: MdxLiteRtManagedPipeline.Backend,
    boundedRuntimeEnabled: Boolean = false,
): MdxInferenceSession {
    val pipeline = MdxLiteRtManagedPipeline(
        coreLibraryFile = installation.libraryFile,
        modelFile = artifact.file,
        profile = profile,
        cpuThreads = cpuThreads,
        backend = backend,
        slotCount = 2,
    )
    return MdxLiteRtManagedInferenceSession(
        pipeline = pipeline,
        profile = profile,
        baseDiagnostics = diagnostics.copy(
            detail = diagnostics.detail + ", pipeline=native-managed-dual-slot",
        ),
        boundedRuntimeEnabled = boundedRuntimeEnabled,
    )
}

private class MdxLiteRtManagedInferenceSession(
    private val pipeline: MdxLiteRtManagedPipeline,
    private val profile: MdxExecutionProfile,
    private val baseDiagnostics: MdxRuntimeDiagnostics,
    private val boundedRuntimeEnabled: Boolean,
) : MdxInferenceSession, MdxWaveformInferenceSession {
    private val oneShotSequenceLock = ReentrantLock()

    override val waveformSlotCount: Int = pipeline.slotCount
    override val waveformDspImplementationId: String = "native-managed-pocketfft-packed-real-v1"
    override val supportsStagedWaveformExecution: Boolean = true

    override val diagnostics: MdxRuntimeDiagnostics
        get() {
            if (!boundedRuntimeEnabled) return baseDiagnostics
            val statistics = runCatching {
                MdxLiteRtBoundedGpuRuntime.statistics()
            }.getOrNull() ?: return baseDiagnostics
            return baseDiagnostics.copy(
                detail = baseDiagnostics.detail +
                    ", boundedDispatches=${statistics.dispatchCount}" +
                    ", boundedEventWaits=${statistics.eventWaitCount}",
            )
        }

    init {
        if (boundedRuntimeEnabled) MdxLiteRtBoundedGpuRuntime.resetInferenceCounters()
    }

    override fun run(
        inputNchw: FloatArray,
        shouldCancel: () -> Boolean,
    ): FloatArray = oneShotSequenceLock.withLock {
        require(inputNchw.size == profile.inputTensor.elementCount) {
            "Expected ${profile.inputTensor.elementCount} input elements, got ${inputNchw.size}."
        }
        throwIfMdxInferenceCanceled(shouldCancel)
        pipeline.writeTensorNchw(inputNchw)
        try {
            invokePipeline(slot = 0, shouldCancel = shouldCancel)
            pipeline.readTensorNchw().also(::requireFiniteMdxTensor)
        } catch (error: Throwable) {
            runCatching { pipeline.discard(0) }
                .exceptionOrNull()
                ?.let(error::addSuppressed)
            throw error
        }.also {
            throwIfMdxInferenceCanceled(shouldCancel)
        }
    }

    override fun runWaveform(
        waveform: Array<FloatArray>,
        shouldCancel: () -> Boolean,
    ): Array<FloatArray> = oneShotSequenceLock.withLock {
        val slot = 0
        try {
            prepareWaveform(waveform, slot, shouldCancel)
            invokePreparedWaveform(slot, shouldCancel)
            readPreparedWaveform(slot, shouldCancel)
        } catch (error: Throwable) {
            runCatching { discardPreparedWaveform(slot) }
                .exceptionOrNull()
                ?.let(error::addSuppressed)
            throw error
        }
    }

    override fun prepareWaveform(
        waveform: Array<FloatArray>,
        slot: Int,
        shouldCancel: () -> Boolean,
    ) {
        throwIfMdxInferenceCanceled(shouldCancel)
        pipeline.preprocessWaveform(waveform, slot)
        throwIfMdxInferenceCanceled(shouldCancel)
    }

    override fun invokePreparedWaveform(
        slot: Int,
        shouldCancel: () -> Boolean,
    ) {
        invokePipeline(slot, shouldCancel)
    }

    override fun readPreparedWaveform(
        slot: Int,
        shouldCancel: () -> Boolean,
    ): Array<FloatArray> {
        throwIfMdxInferenceCanceled(shouldCancel)
        return pipeline.postprocessWaveform(slot).also { waveform ->
            waveform.forEach(::requireFiniteMdxTensor)
            throwIfMdxInferenceCanceled(shouldCancel)
        }
    }

    override fun discardPreparedWaveform(slot: Int) {
        pipeline.discard(slot)
    }

    override fun close() = oneShotSequenceLock.withLock(pipeline::close)

    private fun invokePipeline(slot: Int, shouldCancel: () -> Boolean) {
        runNonInterruptibleMdxInference(shouldCancel) {
            if (boundedRuntimeEnabled) MdxLiteRtBoundedGpuRuntime.beginInference()
            try {
                pipeline.run(slot)
            } finally {
                if (boundedRuntimeEnabled) MdxLiteRtBoundedGpuRuntime.endInference()
            }
        }
    }
}

private fun createJvmLiteRtSession(
    artifact: MdxModelArtifact,
    profile: MdxExecutionProfile,
    requiredAccelerator: Accelerator,
    options: CompiledModel.Options,
    diagnostics: MdxRuntimeDiagnostics,
    boundedRuntimeEnabled: Boolean = false,
): MdxInferenceSession {
    val environment = runLiteRtOperation(MdxLiteRtFailureStage.EnvironmentCreate) {
        Environment.create()
    }
    var compiledModel: CompiledModel? = null
    var inputBuffers: List<TensorBuffer> = emptyList()
    var outputBuffers: List<TensorBuffer> = emptyList()
    try {
        runLiteRtOperation(MdxLiteRtFailureStage.AcceleratorDiscovery) {
            if (!environment.getAvailableAccelerators().contains(requiredAccelerator)) {
                throw MdxInferenceCompatibilityException(
                    "The app-packaged LiteRT runtime has no " +
                        "${requiredAccelerator.name} accelerator."
                )
            }
        }
        val activeCompiledModel = runLiteRtOperation(MdxLiteRtFailureStage.ModelCompile) {
            CompiledModel.create(
                artifact.file.absolutePath,
                options,
                environment,
            )
        }
        compiledModel = activeCompiledModel
        runLiteRtOperation(
            stage = MdxLiteRtFailureStage.TensorMetadata,
            isRecoverable = false,
        ) {
            validateTensorType(
                declared = profile.inputTensor,
                actual = activeCompiledModel.getInputTensorType(
                    requireNotNull(profile.inputTensor.name)
                ),
                role = "input",
            )
            validateTensorType(
                declared = profile.outputTensor,
                actual = activeCompiledModel.getOutputTensorType(
                    requireNotNull(profile.outputTensor.name)
                ),
                role = "output",
            )
        }
        runLiteRtOperation(MdxLiteRtFailureStage.BufferAllocation) {
            validateBufferSize(
                role = "input",
                expectedElementCount = profile.inputTensor.elementCount,
                actualBytes = activeCompiledModel.getInputBufferRequirements(
                    requireNotNull(profile.inputTensor.name)
                ).bufferSize,
            )
            validateBufferSize(
                role = "output",
                expectedElementCount = profile.outputTensor.elementCount,
                actualBytes = activeCompiledModel.getOutputBufferRequirements(
                    requireNotNull(profile.outputTensor.name)
                ).bufferSize,
            )
            inputBuffers = activeCompiledModel.createInputBuffers()
            outputBuffers = activeCompiledModel.createOutputBuffers()
            requireSingleTensor(inputBuffers, "input")
            requireSingleTensor(outputBuffers, "output")
        }
        return MdxLiteRtInferenceSession(
            environment = environment,
            compiledModel = activeCompiledModel,
            inputBuffer = inputBuffers.single(),
            outputBuffer = outputBuffers.single(),
            profile = profile,
            baseDiagnostics = diagnostics,
            boundedRuntimeEnabled = boundedRuntimeEnabled,
        )
    } catch (error: Throwable) {
        closeAfterFailure(
            resources = buildList {
                addAll(inputBuffers)
                addAll(outputBuffers)
                compiledModel?.let(::add)
                add(environment)
            },
            primaryFailure = error,
        )
        throw error
    }
}

private class MdxLiteRtInferenceSession(
    private val environment: Environment,
    private val compiledModel: CompiledModel,
    private val inputBuffer: TensorBuffer,
    private val outputBuffer: TensorBuffer,
    private val profile: MdxExecutionProfile,
    private val baseDiagnostics: MdxRuntimeDiagnostics,
    private val boundedRuntimeEnabled: Boolean,
) : MdxInferenceSession {
    private val inputNhwc = FloatArray(profile.inputTensor.elementCount)
    private val outputNchw = FloatArray(profile.outputTensor.elementCount)
    private val inputBuffers = listOf(inputBuffer)
    private val outputBuffers = listOf(outputBuffer)
    private var closed = false

    override val diagnostics: MdxRuntimeDiagnostics
        get() {
            if (!boundedRuntimeEnabled) return baseDiagnostics
            val statistics = runCatching {
                MdxLiteRtBoundedGpuRuntime.statistics()
            }.getOrNull() ?: return baseDiagnostics
            return baseDiagnostics.copy(
                detail = baseDiagnostics.detail +
                    ", boundedDispatches=${statistics.dispatchCount}" +
                    ", boundedEventWaits=${statistics.eventWaitCount}",
            )
        }

    init {
        if (boundedRuntimeEnabled) {
            MdxLiteRtBoundedGpuRuntime.resetInferenceCounters()
        }
    }

    @Synchronized
    override fun run(
        inputNchw: FloatArray,
        shouldCancel: () -> Boolean,
    ): FloatArray {
        check(!closed) { "LiteRT inference session is closed." }
        require(inputNchw.size == profile.inputTensor.elementCount) {
            "Expected ${profile.inputTensor.elementCount} input elements, got ${inputNchw.size}."
        }
        throwIfMdxInferenceCanceled(shouldCancel)
        MdxTensorLayoutConverter.nchwToNhwc(
            source = inputNchw,
            destination = inputNhwc,
            batch = 1,
            channels = 4,
            height = profile.dspConfig.dimF,
            width = profile.dspConfig.dimT,
        )
        runLiteRtOperation(MdxLiteRtFailureStage.InputWrite) {
            inputBuffer.writeFloat(inputNhwc)
        }
        runNonInterruptibleMdxInference(shouldCancel) {
            if (boundedRuntimeEnabled) {
                MdxLiteRtBoundedGpuRuntime.beginInference()
            }
            try {
                runLiteRtOperation(MdxLiteRtFailureStage.Invocation) {
                    compiledModel.run(inputBuffers, outputBuffers)
                }
            } finally {
                if (boundedRuntimeEnabled) {
                    MdxLiteRtBoundedGpuRuntime.endInference()
                }
            }
        }
        val rawOutputNhwc = runLiteRtOperation(MdxLiteRtFailureStage.OutputRead) {
            outputBuffer.readFloat()
        }
        runLiteRtOperation(MdxLiteRtFailureStage.OutputValidation) {
            require(rawOutputNhwc.size == profile.outputTensor.elementCount) {
                "Expected ${profile.outputTensor.elementCount} output elements, " +
                    "got ${rawOutputNhwc.size}."
            }
            requireFiniteMdxTensor(rawOutputNhwc)
        }
        MdxTensorLayoutConverter.nhwcToNchw(
            source = rawOutputNhwc,
            destination = outputNchw,
            batch = 1,
            channels = 4,
            height = profile.dspConfig.dimF,
            width = profile.dspConfig.dimT,
        )
        throwIfMdxInferenceCanceled(shouldCancel)
        return outputNchw
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        var failure: MdxLiteRtBackendException? = null
        listOf<AutoCloseable>(inputBuffer, outputBuffer, compiledModel, environment).forEach { resource ->
            try {
                resource.close()
            } catch (error: Throwable) {
                val cleanupFailure = MdxLiteRtBackendException(
                    stage = MdxLiteRtFailureStage.Cleanup,
                    isRecoverable = false,
                    cause = error,
                )
                val existingFailure = failure
                if (existingFailure == null) {
                    failure = cleanupFailure
                } else {
                    existingFailure.addSuppressed(cleanupFailure)
                }
            }
        }
        failure?.let { throw it }
    }
}

private fun compatibilityDetail(compatibility: MdxCompatibilityDecision): String = buildString {
    append("status=").append(compatibility.outcome.name)
    compatibility.evidence?.let { append(", evidence=").append(it) }
}

private fun MdxLiteRtGpuPrecision.toLiteRtPrecision(): CompiledModel.GpuOptions.Precision =
    when (this) {
        MdxLiteRtGpuPrecision.Float32 -> CompiledModel.GpuOptions.Precision.FP32
        MdxLiteRtGpuPrecision.Float16 -> CompiledModel.GpuOptions.Precision.FP16
    }

private fun MdxLiteRtGpuApi.toLiteRtBackend(): CompiledModel.GpuOptions.Backend = when (this) {
    MdxLiteRtGpuApi.Automatic -> CompiledModel.GpuOptions.Backend.AUTOMATIC
    MdxLiteRtGpuApi.OpenCl -> CompiledModel.GpuOptions.Backend.OPENCL
    MdxLiteRtGpuApi.OpenGl -> CompiledModel.GpuOptions.Backend.OPENGL
}

private fun MdxLiteRtGpuPriority.toLiteRtPriority(): CompiledModel.GpuOptions.Priority =
    when (this) {
        MdxLiteRtGpuPriority.Low -> CompiledModel.GpuOptions.Priority.LOW
        MdxLiteRtGpuPriority.Normal -> CompiledModel.GpuOptions.Priority.NORMAL
        MdxLiteRtGpuPriority.High -> CompiledModel.GpuOptions.Priority.HIGH
    }

internal fun validateLiteRtTensorMetadata(
    declared: MdxTensorSpec,
    actualName: String,
    actualDataType: MdxTensorDataType,
    actualLayout: MdxTensorLayout,
    actualShape: List<Int>,
    role: String,
) {
    require(actualName == declared.name) {
        "LiteRT $role tensor name $actualName does not match ${declared.name}."
    }
    require(actualDataType == declared.dataType) {
        "LiteRT $role tensor data type $actualDataType does not match ${declared.dataType}."
    }
    require(actualLayout == declared.layout) {
        "LiteRT $role tensor layout $actualLayout does not match ${declared.layout}."
    }
    require(actualShape == declared.shape) {
        "LiteRT $role tensor shape $actualShape does not match ${declared.shape}."
    }
    val actualElementCount = actualShape.fold(1, Math::multiplyExact)
    require(actualElementCount == declared.elementCount) {
        "LiteRT $role tensor has $actualElementCount elements, " +
            "expected ${declared.elementCount}."
    }
}

internal fun requireFiniteMdxTensor(values: FloatArray) {
    val nonFiniteIndex = values.indexOfFirst { !it.isFinite() }
    require(nonFiniteIndex < 0) {
        "LiteRT output contains a non-finite value at index $nonFiniteIndex."
    }
}

private fun validateTensorType(
    declared: MdxTensorSpec,
    actual: TensorType,
    role: String,
) {
    val name = requireNotNull(declared.name)
    val layout = actual.layout
        ?: throw IllegalArgumentException("LiteRT $role tensor has no static layout.")
    validateLiteRtTensorMetadata(
        declared = declared,
        actualName = name,
        actualDataType = when (actual.elementType) {
            TensorType.ElementType.FLOAT -> MdxTensorDataType.Float32
            else -> throw IllegalArgumentException(
                "LiteRT $role tensor has unsupported data type ${actual.elementType}."
            )
        },
        actualLayout = MdxTensorLayout.Nhwc,
        actualShape = layout.dimensions,
        role = role,
    )
    require(!layout.hasStrides) {
        "LiteRT $role tensor unexpectedly declares explicit strides."
    }
}

private fun validateBufferSize(role: String, expectedElementCount: Int, actualBytes: Int) {
    val expectedBytes = Math.multiplyExact(expectedElementCount, Float.SIZE_BYTES)
    require(actualBytes >= expectedBytes) {
        "LiteRT $role buffer has $actualBytes bytes, expected at least $expectedBytes."
    }
}

private fun requireSingleTensor(buffers: List<TensorBuffer>, role: String) {
    require(buffers.size == 1) {
        "LiteRT model must expose exactly one $role tensor, got ${buffers.size}."
    }
}

private inline fun <T> runLiteRtOperation(
    stage: MdxLiteRtFailureStage,
    isRecoverable: Boolean = true,
    operation: () -> T,
): T = try {
    operation()
} catch (error: CancellationException) {
    throw error
} catch (error: MdxLiteRtBackendException) {
    throw error
} catch (error: Exception) {
    throw MdxLiteRtBackendException(stage, isRecoverable, error)
}

private fun closeAfterFailure(
    resources: List<AutoCloseable>,
    primaryFailure: Throwable,
) {
    resources.forEach { resource ->
        try {
            resource.close()
        } catch (cleanupError: Throwable) {
            primaryFailure.addSuppressed(
                MdxLiteRtBackendException(
                    stage = MdxLiteRtFailureStage.Cleanup,
                    isRecoverable = false,
                    cause = cleanupError,
                )
            )
        }
    }
}
