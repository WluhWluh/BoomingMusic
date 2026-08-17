package com.mardous.booming.separation.model

import android.os.Build
import android.os.Process
import java.util.Locale

enum class MdxRuntimeAbi(val androidName: String) {
    Arm64V8a("arm64-v8a"),
    ArmeabiV7a("armeabi-v7a"),
    X86_64("x86_64"),
    X86("x86"),
}

enum class MdxRuntimeSupportStatus {
    KnownGood,
    Candidate,
    Rejected,
    Untested,
    Unsupported,
}

enum class MdxRuntimePrecision {
    Fp32,
    Fp16,
}

object MdxRuntimeProfiles {
    const val LITERT_VERSION = "2.2.0"
    const val CPU_DEFAULT_FP32 = "cpu-default-fp32-v1"
    const val GPU_AUTO_FP32 = "gpu-auto-fp32-v1"
}

data class MdxRuntimeCompatibilityRecord(
    val runtimeVersion: String,
    val abi: MdxRuntimeAbi,
    val backend: MdxInferenceBackend,
    val profileId: String,
    val precision: MdxRuntimePrecision,
    val status: MdxRuntimeSupportStatus,
    val evidence: String,
) {
    init {
        require(runtimeVersion.isNotBlank()) { "Runtime compatibility version is empty." }
        require(profileId.isNotBlank()) { "Runtime compatibility profile ID is empty." }
        require(evidence.isNotBlank()) { "Runtime compatibility evidence is empty." }
    }
}

data class MdxRuntimePlatform(
    val androidApi: Int,
    val runtimeAbi: MdxRuntimeAbi,
    val runtimeVersion: String = MdxRuntimeProfiles.LITERT_VERSION,
) {
    init {
        require(androidApi > 0) { "Android API level must be positive." }
        require(runtimeVersion.isNotBlank()) { "LiteRT runtime version is empty." }
    }
}

fun interface MdxRuntimePlatformProvider {
    fun current(): MdxRuntimePlatform
}

object AndroidMdxRuntimePlatformProvider : MdxRuntimePlatformProvider {
    override fun current(): MdxRuntimePlatform {
        val osArch = System.getProperty("os.arch").orEmpty()
        val runtimeAbi = resolveMdxRuntimeAbi(
            osArch = osArch,
            is64Bit = Process.is64Bit(),
            supportedAbis = Build.SUPPORTED_ABIS.toList(),
        )
            ?: throw MdxInferenceCompatibilityException(
                "Unsupported process architecture: $osArch"
            )
        return MdxRuntimePlatform(Build.VERSION.SDK_INT, runtimeAbi)
    }
}

enum class MdxCompatibilityPolicy {
    KnownGoodOnly,
    AllowCandidates,
    AllowUserAttempts,
    AllowUntestedInternal,
}

enum class MdxCompatibilityOutcome {
    Supported,
    Experimental,
    InternalValidationOnly,
    Unsupported,
}

data class MdxCompatibilityDecision(
    val outcome: MdxCompatibilityOutcome,
    val reason: String,
    val evidence: String? = null,
) {
    val isAllowed: Boolean
        get() = outcome != MdxCompatibilityOutcome.Unsupported

    fun requireAllowed() {
        if (!isAllowed) throw MdxInferenceCompatibilityException(reason)
    }
}

class MdxInferenceCompatibilityException(message: String) :
    IllegalStateException(message)

object MdxLiteRtCompatibilityResolver {
    fun resolve(
        profile: MdxExecutionProfile,
        backend: MdxInferenceBackend,
        platform: MdxRuntimePlatform,
        policy: MdxCompatibilityPolicy,
        profileId: String = MdxRuntimeProfiles.CPU_DEFAULT_FP32,
        precision: MdxRuntimePrecision = MdxRuntimePrecision.Fp32,
    ): MdxCompatibilityDecision {
        if (profile.modelFormat != MdxModelFormat.Tflite) {
            return unsupported("LiteRT requires a TFLite execution profile.")
        }
        if (backend != MdxInferenceBackend.LiteRtCpu &&
            backend != MdxInferenceBackend.LiteRtGpu
        ) {
            return unsupported("The requested backend is not a LiteRT backend.")
        }
        val minimumApi = profile.minimumAndroidApi
            ?: return unsupported("The execution profile has no minimum Android API.")
        if (platform.androidApi < minimumApi) {
            return unsupported(
                "Android API ${platform.androidApi} is below the required API $minimumApi."
            )
        }
        val record = profile.runtimeCompatibility.singleOrNull {
            it.runtimeVersion == platform.runtimeVersion &&
                it.abi == platform.runtimeAbi &&
                it.backend == backend &&
                it.profileId == profileId &&
                it.precision == precision
        } ?: return resolveUnqualifiedReviewedRuntime(
            profile = profile,
            backend = backend,
            platform = platform,
            policy = policy,
            profileId = profileId,
            precision = precision,
        )
        return when (record.status) {
            MdxRuntimeSupportStatus.KnownGood -> MdxCompatibilityDecision(
                outcome = MdxCompatibilityOutcome.Supported,
                reason = "The runtime target is supported.",
                evidence = record.evidence,
            )

            MdxRuntimeSupportStatus.Candidate -> when (policy) {
                MdxCompatibilityPolicy.KnownGoodOnly -> unsupported(
                    "The runtime target is experimental and requires explicit candidate admission.",
                    record.evidence,
                )
                MdxCompatibilityPolicy.AllowCandidates,
                MdxCompatibilityPolicy.AllowUserAttempts -> MdxCompatibilityDecision(
                    outcome = MdxCompatibilityOutcome.Experimental,
                    reason = "The reviewed experimental runtime target is allowed.",
                    evidence = record.evidence,
                )
                MdxCompatibilityPolicy.AllowUntestedInternal -> MdxCompatibilityDecision(
                    outcome = MdxCompatibilityOutcome.InternalValidationOnly,
                    reason = "The runtime target is available only for internal validation.",
                    evidence = record.evidence,
                )
            }

            MdxRuntimeSupportStatus.Untested -> when (policy) {
                MdxCompatibilityPolicy.AllowUserAttempts -> MdxCompatibilityDecision(
                    outcome = MdxCompatibilityOutcome.Experimental,
                    reason = "The untested runtime target is available for a user-requested attempt.",
                    evidence = record.evidence,
                )
                MdxCompatibilityPolicy.AllowUntestedInternal -> MdxCompatibilityDecision(
                    outcome = MdxCompatibilityOutcome.InternalValidationOnly,
                    reason = "The runtime target is available only for internal validation.",
                    evidence = record.evidence,
                )
                MdxCompatibilityPolicy.KnownGoodOnly,
                MdxCompatibilityPolicy.AllowCandidates -> unsupported(
                    "The runtime target is untested and cannot be used by the product.",
                    record.evidence,
                )
            }

            MdxRuntimeSupportStatus.Rejected -> if (
                policy == MdxCompatibilityPolicy.AllowUserAttempts
            ) {
                userAttempt("The runtime profile has prior rejection evidence.", record.evidence)
            } else {
                unsupported("The runtime profile was rejected by validation.", record.evidence)
            }

            MdxRuntimeSupportStatus.Unsupported -> if (
                policy == MdxCompatibilityPolicy.AllowUserAttempts
            ) {
                userAttempt("The runtime target has prior unsupported evidence.", record.evidence)
            } else {
                unsupported("The runtime target is explicitly unsupported.", record.evidence)
            }
        }
    }

    private fun resolveUnqualifiedReviewedRuntime(
        profile: MdxExecutionProfile,
        backend: MdxInferenceBackend,
        platform: MdxRuntimePlatform,
        policy: MdxCompatibilityPolicy,
        profileId: String,
        precision: MdxRuntimePrecision,
    ): MdxCompatibilityDecision {
        val missingRecordReason =
            "No ${backend.name} compatibility record exists for " +
                "${platform.runtimeAbi.androidName}, profile=$profileId, precision=$precision."
        val isReviewedCpu = profile.allowUnqualifiedExperimentalCpu &&
            backend == MdxInferenceBackend.LiteRtCpu &&
            (platform.runtimeAbi != MdxRuntimeAbi.X86 ||
                policy == MdxCompatibilityPolicy.AllowUserAttempts)
        val isReviewedGpu = profile.allowUnqualifiedExperimentalGpu &&
            backend == MdxInferenceBackend.LiteRtGpu &&
            platform.runtimeAbi == MdxRuntimeAbi.Arm64V8a &&
            profileId == MdxRuntimeProfiles.GPU_AUTO_FP32 &&
            precision == MdxRuntimePrecision.Fp32
        if (!isReviewedCpu && !isReviewedGpu) {
            return unsupported(missingRecordReason)
        }
        val pathName = if (isReviewedGpu) "GPU" else "CPU"
        return when (policy) {
            MdxCompatibilityPolicy.KnownGoodOnly -> unsupported(missingRecordReason)
            MdxCompatibilityPolicy.AllowCandidates,
            MdxCompatibilityPolicy.AllowUserAttempts -> MdxCompatibilityDecision(
                outcome = MdxCompatibilityOutcome.Experimental,
                reason = "The reviewed model is admitted to the unqualified experimental $pathName path.",
                evidence = missingRecordReason,
            )
            MdxCompatibilityPolicy.AllowUntestedInternal -> MdxCompatibilityDecision(
                outcome = MdxCompatibilityOutcome.InternalValidationOnly,
                reason = "The reviewed model is admitted to the internal unqualified $pathName path.",
                evidence = missingRecordReason,
            )
        }
    }

    private fun unsupported(reason: String, evidence: String? = null) =
        MdxCompatibilityDecision(
            outcome = MdxCompatibilityOutcome.Unsupported,
            reason = reason,
            evidence = evidence,
        )

    private fun userAttempt(reason: String, evidence: String?) =
        MdxCompatibilityDecision(
            outcome = MdxCompatibilityOutcome.Experimental,
            reason = "$reason The user-requested attempt is allowed.",
            evidence = evidence,
        )
}

internal fun resolveMdxProcessAbi(osArch: String, is64Bit: Boolean): MdxRuntimeAbi? {
    val normalized = osArch.trim().lowercase(Locale.US)
    return when {
        normalized in setOf("x86_64", "amd64") -> {
            if (is64Bit) MdxRuntimeAbi.X86_64 else MdxRuntimeAbi.X86
        }

        normalized in setOf("x86", "i386", "i486", "i586", "i686") -> {
            if (is64Bit) MdxRuntimeAbi.X86_64 else MdxRuntimeAbi.X86
        }

        normalized in setOf("aarch64", "arm64", "arm64-v8a") -> {
            if (is64Bit) MdxRuntimeAbi.Arm64V8a else MdxRuntimeAbi.ArmeabiV7a
        }

        normalized.startsWith("arm") -> {
            if (is64Bit) MdxRuntimeAbi.Arm64V8a else MdxRuntimeAbi.ArmeabiV7a
        }

        else -> null
    }
}

internal fun resolveMdxRuntimeAbi(
    osArch: String,
    is64Bit: Boolean,
    supportedAbis: List<String>,
): MdxRuntimeAbi? {
    val processAbi = resolveMdxProcessAbi(osArch, is64Bit)
    val advertised = supportedAbis.mapNotNull(::mdxRuntimeAbiFromAndroidName)
    if (processAbi in advertised) return processAbi
    return advertised.firstOrNull { candidate ->
        is64Bit == (candidate == MdxRuntimeAbi.Arm64V8a || candidate == MdxRuntimeAbi.X86_64)
    } ?: processAbi
}

internal fun mdxRuntimeAbiFromAndroidName(value: String): MdxRuntimeAbi? =
    MdxRuntimeAbi.entries.singleOrNull { it.androidName == value }
