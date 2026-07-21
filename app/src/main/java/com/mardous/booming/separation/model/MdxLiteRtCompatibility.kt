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
    Untested,
    Unsupported,
}

data class MdxRuntimeCompatibilityRecord(
    val abi: MdxRuntimeAbi,
    val backend: MdxInferenceBackend,
    val status: MdxRuntimeSupportStatus,
    val evidence: String,
) {
    init {
        require(evidence.isNotBlank()) { "Runtime compatibility evidence is empty." }
    }
}

data class MdxRuntimePlatform(
    val androidApi: Int,
    val runtimeAbi: MdxRuntimeAbi,
) {
    init {
        require(androidApi > 0) { "Android API level must be positive." }
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
    AllowUntestedInternal,
}

enum class MdxCompatibilityOutcome {
    Supported,
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
            it.abi == platform.runtimeAbi && it.backend == backend
        } ?: return unsupported(
            "No ${backend.name} compatibility record exists for ${platform.runtimeAbi.androidName}."
        )
        return when (record.status) {
            MdxRuntimeSupportStatus.KnownGood -> MdxCompatibilityDecision(
                outcome = MdxCompatibilityOutcome.Supported,
                reason = "The runtime target is supported.",
                evidence = record.evidence,
            )

            MdxRuntimeSupportStatus.Untested -> if (
                policy == MdxCompatibilityPolicy.AllowUntestedInternal
            ) {
                MdxCompatibilityDecision(
                    outcome = MdxCompatibilityOutcome.InternalValidationOnly,
                    reason = "The runtime target is available only for internal validation.",
                    evidence = record.evidence,
                )
            } else {
                unsupported(
                    "The runtime target is untested and cannot be used outside internal validation.",
                    record.evidence,
                )
            }

            MdxRuntimeSupportStatus.Unsupported -> unsupported(
                "The runtime target is explicitly unsupported.",
                record.evidence,
            )
        }
    }

    private fun unsupported(reason: String, evidence: String? = null) =
        MdxCompatibilityDecision(
            outcome = MdxCompatibilityOutcome.Unsupported,
            reason = reason,
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
