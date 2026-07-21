package com.mardous.booming.separation.model.litert

import com.mardous.booming.separation.model.AndroidMdxRuntimePlatformProvider
import com.mardous.booming.separation.model.MdxCompatibilityDecision
import com.mardous.booming.separation.model.MdxCompatibilityPolicy
import com.mardous.booming.separation.model.MdxExecutionProfile
import com.mardous.booming.separation.model.MdxInferenceBackend
import com.mardous.booming.separation.model.MdxInferenceSession
import com.mardous.booming.separation.model.MdxInferenceSessionFactory
import com.mardous.booming.separation.model.MdxLiteRtCompatibilityResolver
import com.mardous.booming.separation.model.MdxModelArtifact
import com.mardous.booming.separation.model.MdxRuntimePlatformProvider
import com.mardous.booming.separation.model.MdxRuntimePrecision
import com.mardous.booming.separation.model.MdxRuntimeSettings

internal enum class MdxLiteRtGpuApi {
    Automatic,
    OpenCl,
    OpenGl,
}

internal enum class MdxLiteRtGpuPrecision {
    Float32,
    Float16,
}

internal data class MdxLiteRtGpuRuntimeProfile(
    val profileId: String,
    val api: MdxLiteRtGpuApi,
    val precision: MdxLiteRtGpuPrecision,
) {
    init {
        require(PROFILE_ID_PATTERN.matches(profileId)) {
            "LiteRT GPU runtime profile ID is invalid."
        }
    }

    companion object {
        private val PROFILE_ID_PATTERN = Regex("^[a-z0-9][a-z0-9-]{0,63}$")

        val AutomaticFp32V1 = MdxLiteRtGpuRuntimeProfile(
            profileId = "gpu-auto-fp32-v1",
            api = MdxLiteRtGpuApi.Automatic,
            precision = MdxLiteRtGpuPrecision.Float32,
        )

        val AutomaticFp16V1 = MdxLiteRtGpuRuntimeProfile(
            profileId = "gpu-auto-fp16-v1",
            api = MdxLiteRtGpuApi.Automatic,
            precision = MdxLiteRtGpuPrecision.Float16,
        )
    }
}

internal class MdxLiteRtGpuInferenceSessionFactory(
    private val runtimeProfile: MdxLiteRtGpuRuntimeProfile =
        MdxLiteRtGpuRuntimeProfile.AutomaticFp32V1,
    private val platformProvider: MdxRuntimePlatformProvider = AndroidMdxRuntimePlatformProvider,
    private val compatibilityPolicy: MdxCompatibilityPolicy = MdxCompatibilityPolicy.KnownGoodOnly,
    private val sessionAllocator: MdxLiteRtGpuSessionAllocator =
        MdxLiteRtNativeGpuSessionAllocator,
) : MdxInferenceSessionFactory {
    override val factoryId: String =
        "litert-2.1.5-${runtimeProfile.profileId}-${compatibilityPolicy.name}"
    override val backend: MdxInferenceBackend = MdxInferenceBackend.LiteRtGpu

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
            profileId = runtimeProfile.profileId,
            precision = runtimeProfile.precision.toMdxRuntimePrecision(),
        )
        decision.requireAllowed()
        return sessionAllocator.create(
            artifact = artifact,
            profile = profile,
            runtimeProfile = runtimeProfile,
            compatibility = decision,
        )
    }
}

internal fun MdxLiteRtGpuPrecision.toMdxRuntimePrecision() = when (this) {
    MdxLiteRtGpuPrecision.Float32 -> MdxRuntimePrecision.Fp32
    MdxLiteRtGpuPrecision.Float16 -> MdxRuntimePrecision.Fp16
}

internal interface MdxLiteRtGpuSessionAllocator {
    fun create(
        artifact: MdxModelArtifact,
        profile: MdxExecutionProfile,
        runtimeProfile: MdxLiteRtGpuRuntimeProfile,
        compatibility: MdxCompatibilityDecision,
    ): MdxInferenceSession
}
