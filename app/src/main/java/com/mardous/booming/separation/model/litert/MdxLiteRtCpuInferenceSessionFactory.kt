package com.mardous.booming.separation.model.litert

import com.mardous.booming.separation.model.AndroidMdxRuntimePlatformProvider
import com.mardous.booming.separation.model.MdxCompatibilityDecision
import com.mardous.booming.separation.model.MdxCompatibilityPolicy
import com.mardous.booming.separation.model.MdxExecutionProfile
import com.mardous.booming.separation.model.MdxInferenceBackend
import com.mardous.booming.separation.model.MdxInferenceCompatibilityException
import com.mardous.booming.separation.model.MdxInferenceSession
import com.mardous.booming.separation.model.MdxInferenceSessionFactory
import com.mardous.booming.separation.model.MdxLiteRtCompatibilityResolver
import com.mardous.booming.separation.model.MdxModelArtifact
import com.mardous.booming.separation.model.MdxModelFormat
import com.mardous.booming.separation.model.MdxRuntimePlatformProvider
import com.mardous.booming.separation.model.MdxRuntimeSettings
import com.mardous.booming.separation.model.MdxTensorDataType
import com.mardous.booming.separation.model.MdxTensorLayout

internal class MdxLiteRtCpuInferenceSessionFactory(
    private val platformProvider: MdxRuntimePlatformProvider = AndroidMdxRuntimePlatformProvider,
    private val compatibilityPolicy: MdxCompatibilityPolicy = MdxCompatibilityPolicy.KnownGoodOnly,
    private val sessionAllocator: MdxLiteRtSessionAllocator = MdxLiteRtNativeSessionAllocator,
    private val availableProcessors: () -> Int = { Runtime.getRuntime().availableProcessors() },
    private val xnnPackFlags: Int? = null,
) : MdxInferenceSessionFactory {
    init {
        require(xnnPackFlags == null || xnnPackFlags >= 0) {
            "XNNPACK flags must be non-negative."
        }
    }

    override val factoryId: String = buildString {
        append("litert-2.1.5-cpu-").append(compatibilityPolicy.name)
        xnnPackFlags?.let { append("-xnnpack-flags-").append(it) }
    }
    override val backend: MdxInferenceBackend = MdxInferenceBackend.LiteRtCpu

    override fun create(
        artifact: MdxModelArtifact,
        profile: MdxExecutionProfile,
        runtimeSettings: MdxRuntimeSettings,
    ): MdxInferenceSession {
        profile.validateArtifact(artifact)
        validateLiteRtExecutionProfile(profile)
        val decision = MdxLiteRtCompatibilityResolver.resolve(
            profile = profile,
            backend = backend,
            platform = platformProvider.current(),
            policy = compatibilityPolicy,
        )
        decision.requireAllowed()
        return sessionAllocator.create(
            artifact = artifact,
            profile = profile,
            cpuThreads = resolveLiteRtCpuThreadCount(availableProcessors()),
            xnnPackFlags = xnnPackFlags,
            compatibility = decision,
        )
    }
}

internal interface MdxLiteRtSessionAllocator {
    fun create(
        artifact: MdxModelArtifact,
        profile: MdxExecutionProfile,
        cpuThreads: Int,
        xnnPackFlags: Int?,
        compatibility: MdxCompatibilityDecision,
    ): MdxInferenceSession
}

internal fun resolveLiteRtCpuThreadCount(processorCount: Int): Int =
    (processorCount - 1).coerceIn(2, 4)

internal fun validateLiteRtExecutionProfile(profile: MdxExecutionProfile) {
    if (profile.modelFormat != MdxModelFormat.Tflite) {
        throw MdxInferenceCompatibilityException("LiteRT requires a TFLite model.")
    }
    val expectedShape = listOf(
        1,
        profile.dspConfig.dimF,
        profile.dspConfig.dimT,
        profile.dspConfig.tensorElementCount /
            (profile.dspConfig.dimF * profile.dspConfig.dimT),
    )
    for ((role, tensor) in listOf(
        "input" to profile.inputTensor,
        "output" to profile.outputTensor,
    )) {
        if (tensor.name.isNullOrBlank()) {
            throw MdxInferenceCompatibilityException("LiteRT $role tensor name is empty.")
        }
        if (tensor.layout != MdxTensorLayout.Nhwc) {
            throw MdxInferenceCompatibilityException("LiteRT $role tensor must use NHWC layout.")
        }
        if (tensor.dataType != MdxTensorDataType.Float32) {
            throw MdxInferenceCompatibilityException("LiteRT $role tensor must use float32 data.")
        }
        if (tensor.shape != expectedShape) {
            throw MdxInferenceCompatibilityException(
                "LiteRT $role tensor shape ${tensor.shape} does not match $expectedShape."
            )
        }
    }
}
