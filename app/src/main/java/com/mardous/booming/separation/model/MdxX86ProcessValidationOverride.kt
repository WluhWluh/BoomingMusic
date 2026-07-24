package com.mardous.booming.separation.model

import com.mardous.booming.BuildConfig
import com.mardous.booming.separation.cache.v2.SourceSeparationResolvedCacheModel
import java.util.Locale

/** Compile-time-only admission exception for the Phase 3 pure-x86 process experiment. */
internal object MdxX86ProcessValidationOverride {
    const val MODEL_ID_9662 = "uvr_mdxnet_3_9662"
    const val ARTIFACT_SHA256_9662 =
        "f74eee1ac06845a7cf277416138b19a6203f34316a3a74b2bde19acbfb2f8378"
    const val CONTRACT_ID_9662 = "uvr_mdxnet_3_9662@2"

    const val MODEL_ID_KARA = "uvr_mdxnet_kara"
    const val ARTIFACT_SHA256_KARA =
        "4bf2fbd2c416a934cd5f9e3f8a154dc7c30bc616494216699ae2459c18f51c64"
    const val CONTRACT_ID_KARA = "uvr_mdxnet_kara@2"

    val buildEnabled: Boolean
        get() = BuildConfig.X86_PROCESS_VALIDATION

    fun permitsCatalogQualification(
        modelId: String,
        artifactSha256: String,
        contractId: String,
        platform: MdxRuntimePlatform,
        originalStatus: MdxRuntimeSupportStatus,
        enabled: Boolean = buildEnabled,
    ): Boolean = enabled &&
        platform.runtimeAbi == MdxRuntimeAbi.X86 &&
        platform.androidApi >= MINIMUM_ANDROID_API &&
        originalStatus == MdxRuntimeSupportStatus.Unsupported &&
        pinnedModels[modelId] == PinnedModel(
            artifactSha256.lowercase(Locale.US),
            contractId,
        )

    fun applyTo(
        model: SourceSeparationResolvedCacheModel,
        platform: MdxRuntimePlatform,
        enabled: Boolean = buildEnabled,
    ): MdxX86ProcessValidationOverrideResult? {
        val originalRecord = model.executionProfile.runtimeCompatibility.singleOrNull { record ->
            record.abi == MdxRuntimeAbi.X86 &&
                record.backend == MdxInferenceBackend.LiteRtCpu &&
                record.profileId == MdxRuntimeProfiles.CPU_DEFAULT_FP32 &&
                record.precision == MdxRuntimePrecision.Fp32
        } ?: return null
        if (!permitsCatalogQualification(
                modelId = model.contract.modelId,
                artifactSha256 = model.artifact.sha256,
                contractId = model.contract.contractId,
                platform = platform,
                originalStatus = originalRecord.status,
                enabled = enabled,
            )
        ) return null
        if (!model.executionProfile.expectedSha256.equals(
                model.artifact.sha256,
                ignoreCase = true,
            ) || model.executionProfile.modelFormat != MdxModelFormat.Tflite
        ) return null

        val originalDecision = MdxLiteRtCompatibilityResolver.resolve(
            profile = model.executionProfile,
            backend = MdxInferenceBackend.LiteRtCpu,
            platform = platform,
            policy = MdxCompatibilityPolicy.KnownGoodOnly,
        )
        if (originalDecision.outcome != MdxCompatibilityOutcome.Unsupported) return null

        val effectiveRecord = originalRecord.copy(
            status = MdxRuntimeSupportStatus.KnownGood,
            evidence = VALIDATION_EVIDENCE_PREFIX + originalRecord.evidence,
        )
        val effectiveProfile = model.executionProfile.copy(
            runtimeCompatibility = model.executionProfile.runtimeCompatibility.map { record ->
                if (record == originalRecord) effectiveRecord else record
            },
        )
        val effectiveDecision = MdxLiteRtCompatibilityResolver.resolve(
            profile = effectiveProfile,
            backend = MdxInferenceBackend.LiteRtCpu,
            platform = platform,
            policy = MdxCompatibilityPolicy.KnownGoodOnly,
        )
        check(effectiveDecision.outcome == MdxCompatibilityOutcome.Supported) {
            "The x86 process-validation override did not produce an allowed CPU profile."
        }
        return MdxX86ProcessValidationOverrideResult(
            model = model.copy(executionProfile = effectiveProfile),
            originalRecord = originalRecord,
            originalDecision = originalDecision,
            effectiveRecord = effectiveRecord,
            effectiveDecision = effectiveDecision,
        )
    }

    private const val MINIMUM_ANDROID_API = 26
    private const val VALIDATION_EVIDENCE_PREFIX =
        "Phase 3 compile-time pure-x86 process validation override; original evidence: "

    private data class PinnedModel(
        val artifactSha256: String,
        val contractId: String,
    )

    private val pinnedModels = mapOf(
        MODEL_ID_9662 to PinnedModel(ARTIFACT_SHA256_9662, CONTRACT_ID_9662),
        MODEL_ID_KARA to PinnedModel(ARTIFACT_SHA256_KARA, CONTRACT_ID_KARA),
    )
}

internal data class MdxX86ProcessValidationOverrideResult(
    val model: SourceSeparationResolvedCacheModel,
    val originalRecord: MdxRuntimeCompatibilityRecord,
    val originalDecision: MdxCompatibilityDecision,
    val effectiveRecord: MdxRuntimeCompatibilityRecord,
    val effectiveDecision: MdxCompatibilityDecision,
)
