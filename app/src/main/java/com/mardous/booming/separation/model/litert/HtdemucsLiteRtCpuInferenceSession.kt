package com.mardous.booming.separation.model.litert

import com.mardous.booming.separation.model.HtdemucsIstftMode
import com.mardous.booming.separation.model.HtdemucsNeuralInputs
import com.mardous.booming.separation.model.HtdemucsNeuralOutputs
import com.mardous.booming.separation.model.HtdemucsPipelineAdapter
import com.mardous.booming.separation.model.HtdemucsTrackInferenceSession
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

internal interface HtdemucsCpuInferenceSession : HtdemucsTrackInferenceSession {
    val implementationId: String

    fun run(
        inputs: HtdemucsNeuralInputs,
        shouldCancel: () -> Boolean = { false },
    ): HtdemucsWindowStemSet
}

internal class HtdemucsLiteRtCpuInferenceSessionFactory(
    private val backendFactory: HtdemucsNamedTensorBackendFactory? = null,
    private val directPipelineFactory: HtdemucsDirectPipelineFactory =
        HtdemucsNativeDirectPipelineFactory,
    private val availableProcessors: () -> Int = { Runtime.getRuntime().availableProcessors() },
    private val validatedOutputObserver: ((HtdemucsNeuralOutputs) -> Unit)? = null,
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
        validateHtdemucsManagedBindings(inputs, outputs)
        HtdemucsPipelineAdapter.validateSupportedContract(contract.modelContract)
        val cpuThreads = resolveHtdemucsCpuThreadCount(availableProcessors())
        val stemIds = contract.modelContract.stemContract.stems.map { it.stemId }
        val legacyBackendFactory = backendFactory
        if (legacyBackendFactory == null) {
            var pipeline: HtdemucsDirectPipeline? = null
            try {
                val activePipeline = directPipelineFactory.create(
                    modelFile = artifact.file,
                    stemCount = stemIds.size,
                    cpuThreads = cpuThreads,
                    workerCount = HTDEMUCS_ISTFT_WORKERS,
                )
                pipeline = activePipeline
                return DirectHtdemucsCpuInferenceSession(
                    pipeline = activePipeline,
                    orderedStemIds = stemIds,
                    validatedOutputObserver = validatedOutputObserver,
                )
            } catch (error: Throwable) {
                closeAfterFailure(listOfNotNull(pipeline), error)
                throw error
            }
        }
        val adapter = HtdemucsPipelineAdapter(
            contract = contract.modelContract,
            istftMode = HtdemucsIstftMode.ParallelLanes,
            istftWorkers = HTDEMUCS_ISTFT_WORKERS,
        )
        var backend: HtdemucsNamedTensorBackend? = null
        try {
            val activeBackend = legacyBackendFactory.create(
                modelFile = artifact.file,
                signatureKey = contract.flatBuffer.signatureKey,
                inputs = inputs,
                outputs = outputs,
                cpuThreads = cpuThreads,
            )
            backend = activeBackend
            return AtomicHtdemucsCpuInferenceSession(
                forward = AtomicHtdemucsNamedTensorForward(
                    inputs,
                    outputs,
                    activeBackend,
                    validatedOutputObserver,
                ),
                reconstruct = adapter::reconstructWindow,
                prepareNormalizedWindow = adapter::prepareWindow,
                orderedStemIds = adapter.orderedStemIds,
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
    private val validatedOutputObserver: ((HtdemucsNeuralOutputs) -> Unit)? = null,
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
        ).also { outputs -> validatedOutputObserver?.invoke(outputs) }
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

internal class DirectHtdemucsCpuInferenceSession(
    private val pipeline: HtdemucsDirectPipeline,
    override val orderedStemIds: List<String>,
    private val validatedOutputObserver: ((HtdemucsNeuralOutputs) -> Unit)? = null,
) : HtdemucsCpuInferenceSession {
    override val implementationId: String = pipeline.implementationId
    private var closed = false

    @Synchronized
    override fun run(
        inputs: HtdemucsNeuralInputs,
        shouldCancel: () -> Boolean,
    ): HtdemucsWindowStemSet {
        check(!closed) { "HTDemucs direct CPU session is closed." }
        throwIfHtdemucsCanceled(shouldCancel)
        pipeline.writeInputs(inputs)
        return invokeAndPostprocess(shouldCancel)
    }

    @Synchronized
    override fun runNormalizedWindow(
        normalizedPlanarStereo: FloatArray,
        shouldCancel: () -> Boolean,
    ): HtdemucsWindowStemSet {
        check(!closed) { "HTDemucs direct CPU session is closed." }
        throwIfHtdemucsCanceled(shouldCancel)
        pipeline.preprocessAndWriteInput(normalizedPlanarStereo)
        return invokeAndPostprocess(shouldCancel)
    }

    private fun invokeAndPostprocess(
        shouldCancel: () -> Boolean,
    ): HtdemucsWindowStemSet {
        try {
            pipeline.run()
            throwIfHtdemucsCanceled(shouldCancel)
            validatedOutputObserver?.invoke(pipeline.readOutputs())
            throwIfHtdemucsCanceled(shouldCancel)
            val combined = pipeline.postprocessOutputs()
            require(combined.size == orderedStemIds.size *
                HtdemucsPipelineAdapter.CHANNEL_COUNT * HtdemucsPipelineAdapter.WINDOW_SAMPLES
            ) { "HTDemucs direct pipeline returned an invalid combined waveform size." }
            throwIfHtdemucsCanceled(shouldCancel)
            return HtdemucsWindowStemSet(
                orderedStemIds = orderedStemIds,
                planarSamples = combined,
                samplesPerStem = HtdemucsPipelineAdapter.WINDOW_SAMPLES,
            )
        } catch (error: Throwable) {
            try {
                pipeline.discard()
            } catch (discardError: Throwable) {
                error.addSuppressed(discardError)
            }
            throw error
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        pipeline.close()
    }
}

internal class AtomicHtdemucsCpuInferenceSession(
    private val forward: AtomicHtdemucsNamedTensorForward,
    private val reconstruct: (HtdemucsNeuralOutputs, () -> Boolean) -> HtdemucsWindowStemSet,
    private val prepareNormalizedWindow: ((FloatArray) -> HtdemucsNeuralInputs)? = null,
    override val orderedStemIds: List<String> = emptyList(),
    private val closeResources: () -> Unit,
) : HtdemucsCpuInferenceSession {
    override val implementationId: String = "jvm-tensor-buffer-host-adapter-v1"
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
    override fun runNormalizedWindow(
        normalizedPlanarStereo: FloatArray,
        shouldCancel: () -> Boolean,
    ): HtdemucsWindowStemSet {
        check(!closed) { "HTDemucs LiteRT CPU session is closed." }
        val prepare = requireNotNull(prepareNormalizedWindow) {
            "HTDemucs session has no host input adapter."
        }
        throwIfHtdemucsCanceled(shouldCancel)
        return run(prepare(normalizedPlanarStereo), shouldCancel)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        closeResources()
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
