package com.mardous.booming.separation.model.litert

import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import com.google.ai.edge.litert.TensorType
import com.mardous.booming.separation.model.HtdemucsIstftMode
import com.mardous.booming.separation.model.HtdemucsNeuralInputs
import com.mardous.booming.separation.model.HtdemucsNeuralOutputs
import com.mardous.booming.separation.model.HtdemucsPipelineAdapter
import com.mardous.booming.separation.model.HtdemucsWindowStemSet
import com.mardous.booming.separation.model.contract.MultiTensorFlatBufferBinding
import com.mardous.booming.separation.model.contract.MultiTensorExecutableBackend
import com.mardous.booming.separation.model.contract.SourceSeparationMultiTensorExecutableContract
import com.mardous.booming.separation.model.contract.SourceSeparationMultiTensorExecutableContractValidator
import java.io.File
import java.util.concurrent.CancellationException

internal data class HtdemucsVerifiedArtifact(
    val file: File,
    val byteSize: Long,
    val sha256: String,
)

internal interface HtdemucsCpuInferenceSession : AutoCloseable {
    fun run(
        inputs: HtdemucsNeuralInputs,
        shouldCancel: () -> Boolean = { false },
    ): HtdemucsWindowStemSet
}

internal class HtdemucsLiteRtCpuInferenceSessionFactory(
    private val backendFactory: HtdemucsNamedTensorBackendFactory =
        HtdemucsNativeLiteRtCpuBackendFactory,
    private val availableProcessors: () -> Int = { Runtime.getRuntime().availableProcessors() },
) {
    fun create(
        artifact: HtdemucsVerifiedArtifact,
        contract: SourceSeparationMultiTensorExecutableContract,
    ): HtdemucsCpuInferenceSession {
        SourceSeparationMultiTensorExecutableContractValidator.validate(contract)
        validateArtifactIdentity(artifact, contract)
        require(contract.allowedBackends == listOf(MultiTensorExecutableBackend.Cpu)) {
            "HTDemucs LiteRT session requires a CPU-only executable contract."
        }
        val inputs = contract.flatBuffer.inputs.map(HtdemucsTensorBinding::from)
        val outputs = contract.flatBuffer.outputs.map(HtdemucsTensorBinding::from)
        require(inputs.size == 2 && outputs.size == 2) {
            "HTDemucs LiteRT session requires exactly two inputs and two outputs."
        }
        val adapter = HtdemucsPipelineAdapter(
            contract = contract.modelContract,
            istftMode = HtdemucsIstftMode.ParallelLanes,
            istftWorkers = HTDEMUCS_ISTFT_WORKERS,
        )
        var backend: HtdemucsNamedTensorBackend? = null
        try {
            val activeBackend = backendFactory.create(
                modelFile = artifact.file,
                signatureKey = contract.flatBuffer.signatureKey,
                inputs = inputs,
                outputs = outputs,
                cpuThreads = resolveHtdemucsCpuThreadCount(availableProcessors()),
            )
            backend = activeBackend
            return AtomicHtdemucsCpuInferenceSession(
                forward = AtomicHtdemucsNamedTensorForward(inputs, outputs, activeBackend),
                reconstruct = adapter::reconstructWindow,
                closeResources = { closeAll(activeBackend, adapter) },
            )
        } catch (error: Throwable) {
            closeAfterFailure(listOfNotNull(backend, adapter), error)
            throw error
        }
    }

    private fun validateArtifactIdentity(
        artifact: HtdemucsVerifiedArtifact,
        contract: SourceSeparationMultiTensorExecutableContract,
    ) {
        require(artifact.file.isFile) { "HTDemucs TFLite artifact is unavailable." }
        require(artifact.file.name == contract.artifact.fileName) {
            "HTDemucs TFLite file name differs from its executable contract."
        }
        require(artifact.byteSize == contract.artifact.byteSize &&
            artifact.file.length() == contract.artifact.byteSize
        ) { "HTDemucs TFLite byte size differs from its executable contract." }
        require(artifact.sha256.equals(contract.artifact.sha256, ignoreCase = true)) {
            "HTDemucs TFLite SHA-256 differs from its executable contract."
        }
    }

    private companion object {
        const val HTDEMUCS_ISTFT_WORKERS = 4
    }
}

internal data class HtdemucsTensorBinding(
    val logicalName: String,
    val tensorName: String,
    val elementCount: Int,
    val shape: List<Int>,
) {
    val signatureName: String
        get() = logicalName

    companion object {
        fun from(binding: MultiTensorFlatBufferBinding): HtdemucsTensorBinding {
            val count = binding.shape.fold(1L, Math::multiplyExact)
            require(count <= Int.MAX_VALUE) { "HTDemucs tensor is too large for a JVM array." }
            return HtdemucsTensorBinding(
                logicalName = binding.logicalName,
                tensorName = binding.tensorName,
                elementCount = count.toInt(),
                shape = binding.shape,
            )
        }
    }
}

internal fun interface HtdemucsNamedTensorBackendFactory {
    fun create(
        modelFile: File,
        signatureKey: String,
        inputs: List<HtdemucsTensorBinding>,
        outputs: List<HtdemucsTensorBinding>,
        cpuThreads: Int,
    ): HtdemucsNamedTensorBackend
}

internal interface HtdemucsNamedTensorBackend : AutoCloseable {
    /** Returns a new map whose arrays remain owned by the caller. */
    fun run(inputs: Map<String, FloatArray>): Map<String, FloatArray>
}

internal class AtomicHtdemucsNamedTensorForward(
    private val inputBindings: List<HtdemucsTensorBinding>,
    private val outputBindings: List<HtdemucsTensorBinding>,
    private val backend: HtdemucsNamedTensorBackend,
) {
    fun run(
        inputs: HtdemucsNeuralInputs,
        shouldCancel: () -> Boolean,
    ): HtdemucsNeuralOutputs {
        require(inputBindings.size == 2 && outputBindings.size == 2)
        val inputValues = listOf(inputs.waveform, inputs.spectrum)
        inputBindings.zip(inputValues).forEach { (binding, values) ->
            requireTensor(binding, values, "input")
        }
        throwIfHtdemucsCanceled(shouldCancel)
        val rawOutputs = backend.run(
            inputBindings.zip(inputValues).associateTo(linkedMapOf()) { (binding, values) ->
                binding.signatureName to values
            },
        )
        throwIfHtdemucsCanceled(shouldCancel)
        require(rawOutputs.keys == outputBindings.map { it.signatureName }.toSet()) {
            "HTDemucs LiteRT forward did not return the complete named output set."
        }
        val validated = outputBindings.map { binding ->
            rawOutputs.getValue(binding.signatureName).also { values ->
                requireTensor(binding, values, "output")
                val nonFiniteIndex = values.indexOfFirst { !it.isFinite() }
                require(nonFiniteIndex < 0) {
                    "HTDemucs output ${binding.logicalName} is non-finite at $nonFiniteIndex."
                }
            }
        }
        throwIfHtdemucsCanceled(shouldCancel)
        return HtdemucsNeuralOutputs(
            frequency = validated[0],
            waveform = validated[1],
        )
    }

    private fun requireTensor(
        binding: HtdemucsTensorBinding,
        values: FloatArray,
        role: String,
    ) {
        require(values.size == binding.elementCount) {
            "HTDemucs $role ${binding.logicalName} has ${values.size} values; " +
                "expected ${binding.elementCount}."
        }
    }
}

internal class AtomicHtdemucsCpuInferenceSession(
    private val forward: AtomicHtdemucsNamedTensorForward,
    private val reconstruct: (HtdemucsNeuralOutputs, () -> Boolean) -> HtdemucsWindowStemSet,
    private val closeResources: () -> Unit,
) : HtdemucsCpuInferenceSession {
    private var closed = false

    @Synchronized
    override fun run(
        inputs: HtdemucsNeuralInputs,
        shouldCancel: () -> Boolean,
    ): HtdemucsWindowStemSet {
        check(!closed) { "HTDemucs LiteRT CPU session is closed." }
        val outputs = forward.run(inputs, shouldCancel)
        val stemSet = reconstruct(outputs, shouldCancel)
        throwIfHtdemucsCanceled(shouldCancel)
        return stemSet
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        closeResources()
    }
}

private object HtdemucsNativeLiteRtCpuBackendFactory : HtdemucsNamedTensorBackendFactory {
    override fun create(
        modelFile: File,
        signatureKey: String,
        inputs: List<HtdemucsTensorBinding>,
        outputs: List<HtdemucsTensorBinding>,
        cpuThreads: Int,
    ): HtdemucsNamedTensorBackend {
        val environment = Environment.create()
        var model: CompiledModel? = null
        val inputBuffers = linkedMapOf<String, TensorBuffer>()
        val outputBuffers = linkedMapOf<String, TensorBuffer>()
        try {
            require(environment.getAvailableAccelerators().contains(Accelerator.CPU)) {
                "The installed LiteRT runtime has no CPU accelerator."
            }
            val activeModel = CompiledModel.create(
                modelFile.absolutePath,
                CompiledModel.Options(Accelerator.CPU).apply {
                    cpuOptions = CompiledModel.CpuOptions(cpuThreads, null, null)
                },
                environment,
            )
            model = activeModel
            inputs.forEach { binding ->
                validateTensorType(
                    activeModel.getInputTensorType(binding.signatureName, signatureKey),
                    binding,
                    "input",
                )
                inputBuffers[binding.signatureName] =
                    activeModel.createInputBuffer(binding.signatureName, signatureKey)
            }
            outputs.forEach { binding ->
                validateTensorType(
                    activeModel.getOutputTensorType(binding.signatureName, signatureKey),
                    binding,
                    "output",
                )
                outputBuffers[binding.signatureName] =
                    activeModel.createOutputBuffer(binding.signatureName, signatureKey)
            }
            return NativeHtdemucsNamedTensorBackend(
                environment,
                activeModel,
                signatureKey,
                inputBuffers,
                outputBuffers,
            )
        } catch (error: Throwable) {
            closeAfterFailure(
                outputBuffers.values.toList().asReversed() +
                    inputBuffers.values.toList().asReversed() + listOfNotNull(model, environment),
                error,
            )
            throw error
        }
    }

    private fun validateTensorType(
        actual: TensorType,
        expected: HtdemucsTensorBinding,
        role: String,
    ) {
        require(actual.elementType == TensorType.ElementType.FLOAT) {
            "HTDemucs LiteRT $role ${expected.signatureName} is not float32."
        }
        val layout = requireNotNull(actual.layout) {
            "HTDemucs LiteRT $role ${expected.signatureName} has no static layout."
        }
        require(!layout.hasStrides && layout.dimensions == expected.shape) {
            "HTDemucs LiteRT $role ${expected.signatureName} shape differs from its contract."
        }
    }
}

private class NativeHtdemucsNamedTensorBackend(
    private val environment: Environment,
    private val model: CompiledModel,
    private val signatureKey: String,
    private val inputBuffers: LinkedHashMap<String, TensorBuffer>,
    private val outputBuffers: LinkedHashMap<String, TensorBuffer>,
) : HtdemucsNamedTensorBackend {
    private var closed = false

    @Synchronized
    override fun run(inputs: Map<String, FloatArray>): Map<String, FloatArray> {
        check(!closed) { "HTDemucs LiteRT backend is closed." }
        require(inputs.keys == inputBuffers.keys) {
            "HTDemucs LiteRT invocation received an incomplete named input set."
        }
        inputs.forEach { (name, values) -> inputBuffers.getValue(name).writeFloat(values) }
        model.run(inputBuffers, outputBuffers, signatureKey)
        return outputBuffers.mapValuesTo(linkedMapOf()) { (_, buffer) -> buffer.readFloat() }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        closeAll(
            *(outputBuffers.values.toList().asReversed() +
                inputBuffers.values.toList().asReversed() + listOf(model, environment)).toTypedArray(),
        )
    }
}

internal fun resolveHtdemucsCpuThreadCount(processorCount: Int): Int =
    (processorCount - 1).coerceIn(2, 4)

private fun throwIfHtdemucsCanceled(shouldCancel: () -> Boolean) {
    if (shouldCancel()) throw CancellationException("HTDemucs inference was canceled.")
}

private fun closeAll(vararg resources: AutoCloseable) {
    var failure: Throwable? = null
    resources.forEach { resource ->
        try {
            resource.close()
        } catch (error: Throwable) {
            if (failure == null) failure = error else failure.addSuppressed(error)
        }
    }
    failure?.let { throw it }
}

private fun closeAfterFailure(resources: List<AutoCloseable>, primaryFailure: Throwable) {
    resources.forEach { resource ->
        try {
            resource.close()
        } catch (cleanupError: Throwable) {
            primaryFailure.addSuppressed(cleanupError)
        }
    }
}
