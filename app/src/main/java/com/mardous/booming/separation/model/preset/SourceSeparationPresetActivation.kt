package com.mardous.booming.separation.model.preset

import com.mardous.booming.separation.model.MdxRuntimeAbi
import com.mardous.booming.separation.model.MdxRuntimePlatform
import com.mardous.booming.separation.model.MdxRuntimeProfiles
import com.mardous.booming.separation.model.MdxRuntimeSupportStatus
import com.mardous.booming.separation.model.MdxX86ProcessValidationOverride
import com.mardous.booming.separation.model.contract.CatalogActivationPolicy
import com.mardous.booming.separation.model.contract.CatalogReleaseMaturity
import com.mardous.booming.separation.model.contract.CatalogRuntimeQualification
import com.mardous.booming.separation.model.contract.CatalogSupportLevel
import com.mardous.booming.separation.model.contract.CatalogValidationStatus
import com.mardous.booming.separation.model.contract.ContractAbi
import com.mardous.booming.separation.model.contract.ContractBackend
import com.mardous.booming.separation.model.contract.ContractRuntimePrecision
import com.mardous.booming.separation.model.contract.ContractRuntimeQualificationStatus
import com.mardous.booming.separation.model.contract.SourceSeparationModelCatalog

enum class SourceSeparationPresetSelectionScope {
    User,
    InternalValidation,
}

enum class SourceSeparationPresetSelectionBlockReason {
    MissingCatalogEntry,
    MissingReviewedContract,
    DownloadOnly,
    UnsupportedActivationPolicy,
    AndroidApiTooLow,
    MissingKnownGoodCpuProfile,
    CandidatePromotionPending,
    ExperimentalConfirmationRequired,
    UnsupportedCustomModel,
    CustomModelStructuralInspectionUnavailable,
    CustomModelStructuralInspectionFailed,
}

data class SourceSeparationPresetSelectionEligibility(
    val allowed: Boolean,
    val requiresExperimentalConfirmation: Boolean,
    val blockReason: SourceSeparationPresetSelectionBlockReason? = null,
    val cpuQualification: CatalogRuntimeQualification? = null,
) {
    init {
        require(allowed || blockReason != null) {
            "A blocked preset selection must report a block reason."
        }
        require(!allowed || blockReason == null) {
            "An allowed preset selection cannot report a block reason."
        }
    }
}

object SourceSeparationPresetActivationResolver {
    fun resolve(
        catalog: SourceSeparationModelCatalog,
        modelId: String,
        platform: MdxRuntimePlatform,
        scope: SourceSeparationPresetSelectionScope,
        x86ValidationEnabled: Boolean = MdxX86ProcessValidationOverride.buildEnabled,
    ): SourceSeparationPresetSelectionEligibility {
        val entry = catalog.entries.singleOrNull { it.modelId == modelId }
            ?: return blocked(SourceSeparationPresetSelectionBlockReason.MissingCatalogEntry)
        when (entry.activationPolicy) {
            CatalogActivationPolicy.DownloadOnlyResourceGated,
            CatalogActivationPolicy.BlockedUntilReviewedContract,
            CatalogActivationPolicy.DownloadOnlyGenericStem -> {
                return blocked(SourceSeparationPresetSelectionBlockReason.DownloadOnly)
            }

            CatalogActivationPolicy.SelectableWhenQualified,
            CatalogActivationPolicy.SelectableExperimental -> Unit
        }
        val contractId = entry.contractId
            ?: return blocked(SourceSeparationPresetSelectionBlockReason.MissingReviewedContract)
        val contract = catalog.contracts.singleOrNull { it.contractId == contractId }
            ?: return blocked(SourceSeparationPresetSelectionBlockReason.MissingReviewedContract)
        val artifact = catalog.artifacts.singleOrNull { it.artifactId == entry.artifactId }
            ?: return blocked(SourceSeparationPresetSelectionBlockReason.MissingCatalogEntry)
        val artifactSha256 = artifact.tflite?.sha256
            ?: return blocked(SourceSeparationPresetSelectionBlockReason.MissingCatalogEntry)

        val matchingQualifications = catalog.runtimeQualifications.filter {
            it.modelId == modelId &&
                it.contractId == contract.contractId &&
                it.artifactSha256 == artifactSha256 &&
                it.backend == ContractBackend.Cpu &&
                it.profileId == MdxRuntimeProfiles.CPU_DEFAULT_FP32 &&
                it.precision == ContractRuntimePrecision.Fp32 &&
                it.abi.matches(platform.runtimeAbi)
        }
        val exactQualification = matchingQualifications.singleOrNull {
            it.runtimeVersion == platform.runtimeVersion
        }
        val x86ValidationSentinel = if (
            exactQualification == null &&
            scope == SourceSeparationPresetSelectionScope.InternalValidation
        ) {
            matchingQualifications.singleOrNull { candidate ->
                MdxX86ProcessValidationOverride.permitsCatalogQualification(
                    modelId = modelId,
                    artifactSha256 = artifactSha256,
                    contractId = contract.contractId,
                    platform = platform,
                    originalStatus = candidate.status.toMdxRuntimeSupportStatus(),
                    enabled = x86ValidationEnabled,
                )
            }
        } else {
            null
        }
        val qualification = exactQualification ?: x86ValidationSentinel

        if (qualification == null) {
            if (platform.androidApi < MINIMUM_UNQUALIFIED_ANDROID_API) {
                return blocked(SourceSeparationPresetSelectionBlockReason.AndroidApiTooLow)
            }
            val unqualifiedExperimentalCpuAllowed =
                entry.supportLevel == CatalogSupportLevel.Experimental
            if (!unqualifiedExperimentalCpuAllowed) {
                return blocked(
                    SourceSeparationPresetSelectionBlockReason.MissingKnownGoodCpuProfile
                )
            }
            return if (scope == SourceSeparationPresetSelectionScope.InternalValidation) {
                allowed(null, requiresExperimentalConfirmation = false)
            } else {
                allowed(null, requiresExperimentalConfirmation = true)
            }
        }

        if (platform.androidApi < qualification.minimumAndroidApi) {
            return blocked(
                SourceSeparationPresetSelectionBlockReason.AndroidApiTooLow,
                qualification,
            )
        }
        val reviewedExperimentalCandidate =
            entry.supportLevel == CatalogSupportLevel.Experimental &&
                qualification.status == ContractRuntimeQualificationStatus.Candidate
        val reviewedUserAttempt = entry.supportLevel == CatalogSupportLevel.Experimental
        if (qualification.status != ContractRuntimeQualificationStatus.KnownGood &&
            !reviewedExperimentalCandidate &&
            !reviewedUserAttempt &&
            x86ValidationSentinel == null
        ) {
            return blocked(
                SourceSeparationPresetSelectionBlockReason.MissingKnownGoodCpuProfile,
                qualification,
            )
        }
        if (scope == SourceSeparationPresetSelectionScope.InternalValidation) {
            val effectiveQualification = x86ValidationSentinel?.copy(
                runtimeVersion = platform.runtimeVersion,
                status = ContractRuntimeQualificationStatus.KnownGood,
                evidence = "Compile-time x86 process validation; " +
                    x86ValidationSentinel.evidence,
            ) ?: qualification
            return allowed(effectiveQualification, requiresExperimentalConfirmation = false)
        }

        return when (entry.supportLevel) {
            CatalogSupportLevel.Recommended -> {
                if (entry.releaseMaturity != CatalogReleaseMaturity.Stable ||
                    entry.validation.fullSong != CatalogValidationStatus.Passed
                ) {
                    blocked(
                        SourceSeparationPresetSelectionBlockReason.CandidatePromotionPending,
                        qualification,
                    )
                } else {
                    allowed(qualification, requiresExperimentalConfirmation = false)
                }
            }

            CatalogSupportLevel.Experimental -> {
                allowed(qualification, requiresExperimentalConfirmation = true)
            }

            CatalogSupportLevel.DownloadOnly ->
                blocked(SourceSeparationPresetSelectionBlockReason.DownloadOnly, qualification)
        }
    }

    private fun allowed(
        qualification: CatalogRuntimeQualification?,
        requiresExperimentalConfirmation: Boolean,
    ) = SourceSeparationPresetSelectionEligibility(
        allowed = true,
        requiresExperimentalConfirmation = requiresExperimentalConfirmation,
        cpuQualification = qualification,
    )

    private fun blocked(
        reason: SourceSeparationPresetSelectionBlockReason,
        qualification: CatalogRuntimeQualification? = null,
    ) = SourceSeparationPresetSelectionEligibility(
        allowed = false,
        requiresExperimentalConfirmation = false,
        blockReason = reason,
        cpuQualification = qualification,
    )

    private const val MINIMUM_UNQUALIFIED_ANDROID_API = 26
}

private fun ContractAbi.matches(runtimeAbi: MdxRuntimeAbi): Boolean = when (this) {
    ContractAbi.Arm64V8a -> runtimeAbi == MdxRuntimeAbi.Arm64V8a
    ContractAbi.ArmeabiV7a -> runtimeAbi == MdxRuntimeAbi.ArmeabiV7a
    ContractAbi.X86_64 -> runtimeAbi == MdxRuntimeAbi.X86_64
    ContractAbi.X86 -> runtimeAbi == MdxRuntimeAbi.X86
}

private fun ContractRuntimeQualificationStatus.toMdxRuntimeSupportStatus() = when (this) {
    ContractRuntimeQualificationStatus.KnownGood -> MdxRuntimeSupportStatus.KnownGood
    ContractRuntimeQualificationStatus.Candidate -> MdxRuntimeSupportStatus.Candidate
    ContractRuntimeQualificationStatus.Rejected -> MdxRuntimeSupportStatus.Rejected
    ContractRuntimeQualificationStatus.Untested -> MdxRuntimeSupportStatus.Untested
    ContractRuntimeQualificationStatus.Unsupported -> MdxRuntimeSupportStatus.Unsupported
}
