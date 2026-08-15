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

internal enum class MdxLiteRtGpuPriority {
    Low,
    Normal,
    High,
}

internal data class MdxLiteRtGpuRuntimeProfile(
    val profileId: String,
    val api: MdxLiteRtGpuApi,
    val precision: MdxLiteRtGpuPrecision,
    val priority: MdxLiteRtGpuPriority? = null,
    val qualificationProfileId: String = profileId,
    val productionEligible: Boolean = false,
) {
    init {
        require(PROFILE_ID_PATTERN.matches(profileId)) {
            "LiteRT GPU runtime profile ID is invalid."
        }
        require(PROFILE_ID_PATTERN.matches(qualificationProfileId)) {
            "LiteRT GPU qualification profile ID is invalid."
        }
    }

    companion object {
        private val PROFILE_ID_PATTERN = Regex("^[a-z0-9][a-z0-9-]{0,63}$")

        val AutomaticFp32V1 = MdxLiteRtGpuRuntimeProfile(
            profileId = "gpu-auto-fp32-v1",
            api = MdxLiteRtGpuApi.Automatic,
            precision = MdxLiteRtGpuPrecision.Float32,
        )

        val BoundedOpenClFp32V1 = MdxLiteRtGpuRuntimeProfile(
            profileId = MdxLiteRtBoundedGpuContract.PROFILE_ID,
            api = MdxLiteRtGpuApi.OpenCl,
            precision = MdxLiteRtGpuPrecision.Float32,
            qualificationProfileId = AutomaticFp32V1.profileId,
            productionEligible = true,
        )

        val AutomaticFp16V1 = MdxLiteRtGpuRuntimeProfile(
            profileId = "gpu-auto-fp16-v1",
            api = MdxLiteRtGpuApi.Automatic,
            precision = MdxLiteRtGpuPrecision.Float16,
        )

        val ExplicitOpenClFp32V1 = MdxLiteRtGpuRuntimeProfile(
            profileId = "gpu-opencl-fp32-v1",
            api = MdxLiteRtGpuApi.OpenCl,
            precision = MdxLiteRtGpuPrecision.Float32,
            qualificationProfileId = AutomaticFp32V1.profileId,
        )

        val LowPriorityOpenClFp32V1 = MdxLiteRtGpuRuntimeProfile(
            profileId = "gpu-opencl-low-fp32-v1",
            api = MdxLiteRtGpuApi.OpenCl,
            precision = MdxLiteRtGpuPrecision.Float32,
            priority = MdxLiteRtGpuPriority.Low,
            qualificationProfileId = AutomaticFp32V1.profileId,
        )

        val OpenGlFp32V1 = MdxLiteRtGpuRuntimeProfile(
            profileId = "gpu-opengl-fp32-v1",
            api = MdxLiteRtGpuApi.OpenGl,
            precision = MdxLiteRtGpuPrecision.Float32,
            qualificationProfileId = AutomaticFp32V1.profileId,
        )

        val all = listOf(
            BoundedOpenClFp32V1,
            AutomaticFp32V1,
            AutomaticFp16V1,
            ExplicitOpenClFp32V1,
            LowPriorityOpenClFp32V1,
            OpenGlFp32V1,
        )

        fun find(profileId: String): MdxLiteRtGpuRuntimeProfile? =
            all.singleOrNull { it.profileId == profileId }
    }
}

internal class MdxLiteRtGpuInferenceSessionFactory(
    private val runtimeProfile: MdxLiteRtGpuRuntimeProfile =
        MdxLiteRtGpuRuntimeProfile.BoundedOpenClFp32V1,
    private val platformProvider: MdxRuntimePlatformProvider = AndroidMdxRuntimePlatformProvider,
    private val compatibilityPolicy: MdxCompatibilityPolicy = MdxCompatibilityPolicy.KnownGoodOnly,
    private val sessionAllocator: MdxLiteRtGpuSessionAllocator =
        MdxLiteRtNativeGpuSessionAllocator,
) : MdxInferenceSessionFactory {
    override val factoryId: String =
        "litert-2.2.0-${runtimeProfile.profileId}-${compatibilityPolicy.name}"
    override val backend: MdxInferenceBackend = MdxInferenceBackend.LiteRtGpu

    override fun create(
        artifact: MdxModelArtifact,
        profile: MdxExecutionProfile,
        runtimeSettings: MdxRuntimeSettings,
    ): MdxInferenceSession {
        profile.validateArtifact(artifact)
        validateLiteRtExecutionProfile(profile)
        require(
            runtimeProfile.productionEligible ||
                compatibilityPolicy == MdxCompatibilityPolicy.AllowUntestedInternal ||
                runtimeProfile.qualificationProfileId == runtimeProfile.profileId
        ) {
            "A derived LiteRT GPU profile is available only for internal validation."
        }
        val decision = MdxLiteRtCompatibilityResolver.resolve(
            profile = profile,
            backend = backend,
            platform = platformProvider.current(),
            policy = compatibilityPolicy,
            profileId = runtimeProfile.qualificationProfileId,
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
