package com.mardous.booming.separation.model.litert

import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.LiteRtNativeLibraryLoader
import com.google.ai.edge.litert.TensorBuffer
import com.google.ai.edge.litert.TensorType
import com.mardous.booming.separation.model.HtdemucsPipelineAdapter
import com.mardous.booming.separation.runtime.SourceSeparationRuntimeBootstrap
import java.io.File

internal object HtdemucsNativeLiteRtCpuBackendFactory : HtdemucsNamedTensorBackendFactory {
    override fun create(
        modelFile: File,
        signatureKey: String,
        inputs: List<HtdemucsTensorBinding>,
        outputs: List<HtdemucsTensorBinding>,
        cpuThreads: Int,
    ): HtdemucsNamedTensorBackend {
        validateHtdemucsManagedBindings(inputs, outputs)
        requireLoadedHtdemucsCoreLibrary()

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
                validateHtdemucsManagedTensorType(
                    activeModel.getInputTensorType(binding.signatureName, signatureKey),
                    binding,
                    "input",
                )
                validateHtdemucsManagedBufferSize(
                    role = "input",
                    binding = binding,
                    actualBytes = activeModel.getInputBufferRequirements(
                        binding.signatureName,
                        signatureKey,
                    ).bufferSize,
                )
                inputBuffers[binding.signatureName] =
                    activeModel.createInputBuffer(binding.signatureName, signatureKey)
            }
            outputs.forEach { binding ->
                validateHtdemucsManagedTensorType(
                    activeModel.getOutputTensorType(binding.signatureName, signatureKey),
                    binding,
                    "output",
                )
                validateHtdemucsManagedBufferSize(
                    role = "output",
                    binding = binding,
                    actualBytes = activeModel.getOutputBufferRequirements(
                        binding.signatureName,
                        signatureKey,
                    ).bufferSize,
                )
                outputBuffers[binding.signatureName] =
                    activeModel.createOutputBuffer(binding.signatureName, signatureKey)
            }
            return HtdemucsLiteRtManagedPipeline(
                environment,
                activeModel,
                signatureKey,
                inputBuffers,
                outputBuffers,
            )
        } catch (error: Throwable) {
            closeHtdemucsManagedAfterFailure(
                outputBuffers.values.toList().asReversed() +
                    inputBuffers.values.toList().asReversed() + listOfNotNull(model, environment),
                error,
            )
            throw error
        }
    }
}

private class HtdemucsLiteRtManagedPipeline(
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
        closeHtdemucsManagedResources(
            *(outputBuffers.values.toList().asReversed() +
                inputBuffers.values.toList().asReversed() + listOf(model, environment)).toTypedArray(),
        )
    }
}

internal fun requireLoadedHtdemucsCoreLibrary(): File {
    val installation = LiteRt220RuntimeIdentity.requireExact(
        SourceSeparationRuntimeBootstrap.requireLoadedInstallation(),
    )
    val coreLibrary = installation.libraryFile
    require(coreLibrary.isAbsolute && coreLibrary.isFile) {
        "The loaded LiteRT core library path is invalid."
    }
    val absolutePath = coreLibrary.absolutePath
    check(LiteRtNativeLibraryLoader.configuredAbsolutePath() == absolutePath) {
        "LiteRT is not configured for the loaded core library path."
    }
    check(LiteRtNativeLibraryLoader.isLoaded()) {
        "The verified LiteRT core library is not loaded."
    }
    return coreLibrary
}

internal fun validateHtdemucsManagedBindings(
    inputs: List<HtdemucsTensorBinding>,
    outputs: List<HtdemucsTensorBinding>,
) {
    require(inputs.map(HtdemucsTensorBinding::logicalName) == HTDEMUCS_INPUT_NAMES) {
        "HTDemucs LiteRT inputs must use the canonical logical names."
    }
    require(outputs.map(HtdemucsTensorBinding::logicalName) == HTDEMUCS_OUTPUT_NAMES) {
        "HTDemucs LiteRT outputs must use the canonical logical names."
    }

    val stemCount = outputs.first().shape.getOrNull(1)
    require(stemCount == 4 || stemCount == 6) {
        "HTDemucs LiteRT output tensors must describe four or six stems."
    }
    val expectedShapes = listOf(
        listOf(1, HtdemucsPipelineAdapter.CHANNEL_COUNT, HtdemucsPipelineAdapter.WINDOW_SAMPLES),
        listOf(
            1,
            HtdemucsPipelineAdapter.FEATURE_COUNT,
            HtdemucsPipelineAdapter.FREQUENCY_BINS,
            HtdemucsPipelineAdapter.FRAME_COUNT,
        ),
        listOf(
            1,
            stemCount,
            HtdemucsPipelineAdapter.FEATURE_COUNT,
            HtdemucsPipelineAdapter.FREQUENCY_BINS,
            HtdemucsPipelineAdapter.FRAME_COUNT,
        ),
        listOf(
            1,
            stemCount,
            HtdemucsPipelineAdapter.CHANNEL_COUNT,
            HtdemucsPipelineAdapter.WINDOW_SAMPLES,
        ),
    )
    (inputs + outputs).zip(expectedShapes).forEach { (binding, expectedShape) ->
        require(binding.shape == expectedShape) {
            "HTDemucs LiteRT tensor ${binding.logicalName} shape differs from the canonical contract."
        }
        val expectedElements = expectedShape.fold(1, Math::multiplyExact)
        require(binding.elementCount == expectedElements) {
            "HTDemucs LiteRT tensor ${binding.logicalName} element count differs from its shape."
        }
    }
}

internal fun validateHtdemucsManagedTensorType(
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

internal fun validateHtdemucsManagedBufferSize(
    role: String,
    binding: HtdemucsTensorBinding,
    actualBytes: Int,
) {
    val expectedBytes = Math.multiplyExact(binding.elementCount, Float.SIZE_BYTES)
    require(actualBytes >= expectedBytes) {
        "HTDemucs LiteRT $role ${binding.signatureName} buffer has $actualBytes bytes; " +
            "expected at least $expectedBytes."
    }
}

private fun closeHtdemucsManagedResources(vararg resources: AutoCloseable) {
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

private fun closeHtdemucsManagedAfterFailure(
    resources: List<AutoCloseable>,
    primaryFailure: Throwable,
) {
    resources.forEach { resource ->
        try {
            resource.close()
        } catch (cleanupError: Throwable) {
            primaryFailure.addSuppressed(cleanupError)
        }
    }
}

private val HTDEMUCS_INPUT_NAMES = listOf("args_0", "args_1")
private val HTDEMUCS_OUTPUT_NAMES = listOf("output_0", "output_1")
