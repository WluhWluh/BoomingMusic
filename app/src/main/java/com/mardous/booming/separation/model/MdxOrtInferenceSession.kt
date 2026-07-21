package com.mardous.booming.separation.model

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

object MdxOrtInferenceSessionFactory : MdxInferenceSessionFactory {
    override val factoryId: String = "onnx-runtime-cpu"
    override val backend: MdxInferenceBackend = MdxInferenceBackend.OrtCpu

    override fun create(
        artifact: MdxModelArtifact,
        profile: MdxExecutionProfile,
        runtimeSettings: MdxRuntimeSettings,
    ): MdxInferenceSession {
        require(profile.modelFormat == MdxModelFormat.Onnx) {
            "ONNX Runtime cannot load ${profile.modelFormat} models."
        }
        val variant = requireNotNull(profile.legacyModelVariant) {
            "The legacy ONNX adapter requires a model variant."
        }
        val session = runtimeSettings.createOrtSessionOptions().use { options ->
            try {
                OrtEnvironment.getEnvironment().createSession(artifact.file.absolutePath, options)
            } catch (error: Exception) {
                throw SourceSeparationModelLoadException(variant, error)
            }
        }
        return try {
            MdxOrtInferenceSession(session, profile, runtimeSettings)
        } catch (error: Throwable) {
            session.close()
            throw error
        }
    }
}

private class MdxOrtInferenceSession(
    private val session: OrtSession,
    private val profile: MdxExecutionProfile,
    runtimeSettings: MdxRuntimeSettings,
) : MdxInferenceSession {
    private val inputName = session.inputInfo.keys.firstOrNull()
        ?: error("ONNX model has no input tensor.")
    private val outputName = session.outputInfo.keys.firstOrNull()
        ?: error("ONNX model has no output tensor.")
    private val inputShape = profile.inputTensor.shape.map(Int::toLong).toLongArray()

    override val diagnostics = MdxRuntimeDiagnostics(
        runtimeName = "ONNX Runtime",
        backend = MdxInferenceBackend.OrtCpu,
        cpuThreads = if (runtimeSettings.useXnnpack) {
            runtimeSettings.cpuThreads.coerceAtLeast(1)
        } else {
            runtimeSettings.cpuThreads.takeIf { it > 0 }
        },
        detail = if (runtimeSettings.useXnnpack) {
            "provider=XNNPACK, optimization=ALL_OPT, execution=SEQUENTIAL"
        } else {
            "provider=CPU, optimization=ALL_OPT, execution=SEQUENTIAL"
        },
    )

    override fun run(inputNchw: FloatArray): FloatArray {
        require(inputNchw.size == profile.inputTensor.elementCount) {
            "Expected ${profile.inputTensor.elementCount} input elements, got ${inputNchw.size}."
        }
        OnnxTensor.createTensor(
            OrtEnvironment.getEnvironment(),
            FloatBuffer.wrap(inputNchw),
            inputShape,
        ).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { outputs ->
                val output = outputs[outputName].orElseThrow {
                    IllegalStateException("Missing ONNX output: $outputName")
                }.value
                @Suppress("UNCHECKED_CAST")
                val outputArray = output as Array<Array<Array<FloatArray>>>
                return flattenOutput(outputArray, profile.outputTensor.elementCount)
            }
        }
    }

    override fun close() {
        session.close()
    }
}

private fun MdxRuntimeSettings.createOrtSessionOptions(): OrtSession.SessionOptions {
    return OrtSession.SessionOptions().apply {
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
        setInterOpNumThreads(1)
        if (useXnnpack) {
            addConfigEntry("session.intra_op.allow_spinning", "0")
            addConfigEntry("session.inter_op.allow_spinning", "0")
            setIntraOpNumThreads(1)
            addXnnpack(mapOf("intra_op_num_threads" to cpuThreads.coerceAtLeast(1).toString()))
        } else if (cpuThreads > 0) {
            setIntraOpNumThreads(cpuThreads)
        }
    }
}

internal fun flattenOutput(
    output: Array<Array<Array<FloatArray>>>,
    expectedElementCount: Int,
): FloatArray {
    require(output.size == 1) { "Expected batch size 1, got ${output.size}." }
    val flat = FloatArray(expectedElementCount)
    var offset = 0
    for (channel in output[0]) {
        for (frequency in channel) {
            for (value in frequency) {
                require(offset < flat.size) { "ONNX output contains too many elements." }
                flat[offset++] = value
            }
        }
    }
    require(offset == flat.size) {
        "Expected $expectedElementCount ONNX output elements, got $offset."
    }
    return flat
}
