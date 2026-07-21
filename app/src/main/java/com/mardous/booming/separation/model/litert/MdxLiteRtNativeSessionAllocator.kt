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
import com.mardous.booming.separation.model.runNonInterruptibleMdxInference
import com.mardous.booming.separation.model.throwIfMdxInferenceCanceled

internal object MdxLiteRtNativeSessionAllocator : MdxLiteRtSessionAllocator {
    override fun create(
        artifact: MdxModelArtifact,
        profile: MdxExecutionProfile,
        cpuThreads: Int,
        compatibility: MdxCompatibilityDecision,
    ): MdxInferenceSession {
        val environment = Environment.create()
        var compiledModel: CompiledModel? = null
        var inputBuffers: List<TensorBuffer> = emptyList()
        var outputBuffers: List<TensorBuffer> = emptyList()
        try {
            if (!environment.getAvailableAccelerators().contains(Accelerator.CPU)) {
                throw MdxInferenceCompatibilityException(
                    "The app-packaged LiteRT runtime has no CPU accelerator."
                )
            }
            val options = CompiledModel.Options(Accelerator.CPU).apply {
                this.cpuOptions = CompiledModel.CpuOptions(cpuThreads, null, null)
            }
            compiledModel = CompiledModel.create(
                artifact.file.absolutePath,
                options,
                environment,
            )
            validateTensorType(
                declared = profile.inputTensor,
                actual = compiledModel.getInputTensorType(requireNotNull(profile.inputTensor.name)),
                role = "input",
            )
            validateTensorType(
                declared = profile.outputTensor,
                actual = compiledModel.getOutputTensorType(requireNotNull(profile.outputTensor.name)),
                role = "output",
            )
            validateBufferSize(
                role = "input",
                expectedElementCount = profile.inputTensor.elementCount,
                actualBytes = compiledModel.getInputBufferRequirements(
                    requireNotNull(profile.inputTensor.name)
                ).bufferSize,
            )
            validateBufferSize(
                role = "output",
                expectedElementCount = profile.outputTensor.elementCount,
                actualBytes = compiledModel.getOutputBufferRequirements(
                    requireNotNull(profile.outputTensor.name)
                ).bufferSize,
            )
            inputBuffers = compiledModel.createInputBuffers()
            outputBuffers = compiledModel.createOutputBuffers()
            requireSingleTensor(inputBuffers, "input")
            requireSingleTensor(outputBuffers, "output")
            return MdxLiteRtCpuInferenceSession(
                environment = environment,
                compiledModel = compiledModel,
                inputBuffer = inputBuffers.single(),
                outputBuffer = outputBuffers.single(),
                profile = profile,
                cpuThreads = cpuThreads,
                compatibility = compatibility,
            )
        } catch (error: Throwable) {
            inputBuffers.closeQuietly()
            outputBuffers.closeQuietly()
            compiledModel?.closeQuietly()
            environment.closeQuietly()
            throw error
        }
    }
}

private class MdxLiteRtCpuInferenceSession(
    private val environment: Environment,
    private val compiledModel: CompiledModel,
    private val inputBuffer: TensorBuffer,
    private val outputBuffer: TensorBuffer,
    private val profile: MdxExecutionProfile,
    cpuThreads: Int,
    compatibility: MdxCompatibilityDecision,
) : MdxInferenceSession {
    private val inputNhwc = FloatArray(profile.inputTensor.elementCount)
    private val outputNchw = FloatArray(profile.outputTensor.elementCount)
    private val inputBuffers = listOf(inputBuffer)
    private val outputBuffers = listOf(outputBuffer)
    private var closed = false

    override val diagnostics = MdxRuntimeDiagnostics(
        runtimeName = "LiteRT 2.1.5",
        backend = MdxInferenceBackend.LiteRtCpu,
        cpuThreads = cpuThreads,
        detail = buildString {
            append("layout=NHWC, status=").append(compatibility.outcome.name)
            compatibility.evidence?.let { append(", evidence=").append(it) }
        },
    )

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
        inputBuffer.writeFloat(inputNhwc)
        runNonInterruptibleMdxInference(shouldCancel) {
            compiledModel.run(inputBuffers, outputBuffers)
        }
        val rawOutputNhwc = outputBuffer.readFloat()
        require(rawOutputNhwc.size == profile.outputTensor.elementCount) {
            "Expected ${profile.outputTensor.elementCount} output elements, " +
                "got ${rawOutputNhwc.size}."
        }
        requireFiniteMdxTensor(rawOutputNhwc)
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
        var failure: Throwable? = null
        listOf<AutoCloseable>(inputBuffer, outputBuffer, compiledModel, environment).forEach { resource ->
            try {
                resource.close()
            } catch (error: Throwable) {
                val existingFailure = failure
                if (existingFailure == null) failure = error else existingFailure.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }
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

private fun Iterable<AutoCloseable>.closeQuietly() {
    forEach { it.closeQuietly() }
}

private fun AutoCloseable.closeQuietly() {
    runCatching { close() }
}
