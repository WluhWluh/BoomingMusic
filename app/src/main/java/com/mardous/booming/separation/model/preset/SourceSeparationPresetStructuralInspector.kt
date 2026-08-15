package com.mardous.booming.separation.model.preset

import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import com.mardous.booming.separation.model.MdxRuntimeAbi
import com.mardous.booming.separation.model.MdxRuntimePlatform
import java.io.File

/**
 * Performs the limited structural check LiteRT 2.2 exposes without an
 * Interpreter API: compile the FlatBuffer and enumerate its I/O buffers.
 * Tensor names and shapes remain explicit user-entered contract data.
 */
internal fun interface SourceSeparationPresetStructuralInspector {
    fun inspect(
        modelFile: File,
        platform: MdxRuntimePlatform,
    ): SourceSeparationPresetStructuralInspection
}

sealed interface SourceSeparationPresetStructuralInspection {
    data class Compatible(
        val inputCount: Int,
        val outputCount: Int,
    ) : SourceSeparationPresetStructuralInspection

    data class Unavailable(
        val reason: String,
    ) : SourceSeparationPresetStructuralInspection

    data class Incompatible(
        val reason: String,
    ) : SourceSeparationPresetStructuralInspection
}

internal object AndroidSourceSeparationPresetStructuralInspector :
    SourceSeparationPresetStructuralInspector {
    override fun inspect(
        modelFile: File,
        platform: MdxRuntimePlatform,
    ): SourceSeparationPresetStructuralInspection {
        if (platform.runtimeAbi == MdxRuntimeAbi.X86) {
            return SourceSeparationPresetStructuralInspection.Unavailable(
                "The x86 LiteRT package cannot inspect unknown TFLite models.",
            )
        }
        if (!modelFile.isFile || modelFile.length() <= 0L) {
            return SourceSeparationPresetStructuralInspection.Incompatible(
                "The imported TFLite file is unavailable.",
            )
        }

        var environment: Environment? = null
        var compiledModel: CompiledModel? = null
        var inputBuffers: List<TensorBuffer> = emptyList()
        var outputBuffers: List<TensorBuffer> = emptyList()
        return try {
            environment = Environment.create()
            if (!environment.getAvailableAccelerators().contains(Accelerator.CPU)) {
                return SourceSeparationPresetStructuralInspection.Unavailable(
                    "The installed LiteRT runtime has no CPU accelerator.",
                )
            }
            compiledModel = CompiledModel.create(
                modelFile.absolutePath,
                CompiledModel.Options(Accelerator.CPU),
                environment,
            )
            inputBuffers = compiledModel.createInputBuffers()
            outputBuffers = compiledModel.createOutputBuffers()
            if (inputBuffers.size != 1 || outputBuffers.size != 1) {
                SourceSeparationPresetStructuralInspection.Incompatible(
                    "The model must expose exactly one input and one output tensor.",
                )
            } else {
                SourceSeparationPresetStructuralInspection.Compatible(
                    inputCount = inputBuffers.size,
                    outputCount = outputBuffers.size,
                )
            }
        } catch (error: Throwable) {
            SourceSeparationPresetStructuralInspection.Incompatible(
                "LiteRT could not compile the imported model: ${error.message.orEmpty()}",
            )
        } finally {
            outputBuffers.asReversed().forEach(TensorBuffer::close)
            inputBuffers.asReversed().forEach(TensorBuffer::close)
            compiledModel?.close()
            environment?.close()
        }
    }
}

internal object UnavailableSourceSeparationPresetStructuralInspector :
    SourceSeparationPresetStructuralInspector {
    override fun inspect(
        modelFile: File,
        platform: MdxRuntimePlatform,
    ) = SourceSeparationPresetStructuralInspection.Unavailable(
        "No structural TFLite inspector is configured.",
    )
}
