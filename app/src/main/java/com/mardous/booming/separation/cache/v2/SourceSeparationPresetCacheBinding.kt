package com.mardous.booming.separation.cache.v2

import com.mardous.booming.separation.model.MdxExecutionProfile
import com.mardous.booming.separation.model.MdxModelArtifact
import com.mardous.booming.separation.model.contract.SourceSeparationModelContract
import com.mardous.booming.separation.model.contract.toMdxExecutionProfile
import com.mardous.booming.separation.model.preset.SourceSeparationActiveModelReference
import com.mardous.booming.separation.model.preset.SourceSeparationActivePresetState
import com.mardous.booming.separation.model.preset.SourceSeparationInstalledPreset
import com.mardous.booming.separation.model.preset.SourceSeparationPresetBindingKind
import com.mardous.booming.separation.model.preset.SourceSeparationPresetRepository

class SourceSeparationPresetCacheAvailabilityProvider(
    private val repository: SourceSeparationPresetRepository,
) : SourceSeparationCacheModelAvailabilityProvider {
    override fun availability(
        manifest: SourceSeparationCacheManifest,
    ): SourceSeparationCacheModelAvailability {
        val installed = repository.installedModel(manifest.identity.artifactSha256)
            ?: return SourceSeparationCacheModelAvailability.ModelNotInstalled
        val snapshot = if (manifest.contract.profileOrigin ==
            SourceSeparationCacheProfileOrigin.Custom
        ) {
            repository.customProfiles()
                .singleOrNull { profile ->
                    profile.profileId == manifest.identity.profileRevisionId &&
                        profile.artifact.sha256.equals(installed.sha256, ignoreCase = true)
                }
                ?.let(SourceSeparationCacheContractSnapshot::fromCustom)
        } else {
            installed.cacheContractSnapshot(repository)
        }
            ?: return if (installed.bindingKind == SourceSeparationPresetBindingKind.CustomProfile) {
                SourceSeparationCacheModelAvailability.ProfileNotInstalled
            } else {
                SourceSeparationCacheModelAvailability.ContractMismatch
            }
        return if (snapshot.matches(manifest)) {
            SourceSeparationCacheModelAvailability.InstalledExact
        } else {
            SourceSeparationCacheModelAvailability.ContractMismatch
        }
    }
}

fun SourceSeparationPresetRepository.resolveActiveCacheModel(): SourceSeparationResolvedCacheModel? {
    return (resolveActiveCacheModelResolution() as?
        SourceSeparationActiveCacheModelResolution.Ready)?.model
}

fun SourceSeparationPresetRepository.resolveTrustedActiveCacheModel():
        SourceSeparationResolvedCacheModel? {
    return (resolveActiveCacheModelResolution(verifyArtifactHash = false) as?
        SourceSeparationActiveCacheModelResolution.Ready)?.model
}

fun SourceSeparationPresetRepository.resolveTrustedActiveCacheModelResolution():
        SourceSeparationActiveCacheModelResolution =
    resolveActiveCacheModelResolution(verifyArtifactHash = false)

fun SourceSeparationPresetRepository.resolveActiveCacheModelResolution():
        SourceSeparationActiveCacheModelResolution =
    resolveActiveCacheModelResolution(verifyArtifactHash = true)

private fun SourceSeparationPresetRepository.resolveActiveCacheModelResolution(
    verifyArtifactHash: Boolean,
): SourceSeparationActiveCacheModelResolution {
    val active = activeModel()
    if (active == SourceSeparationActivePresetState.None) {
        val pending = pendingActiveModel()
        return SourceSeparationActiveCacheModelResolution.Unavailable(
            reason = if (pending == null) {
                SourceSeparationActiveCacheModelUnavailableReason.NoSelection
            } else {
                SourceSeparationActiveCacheModelUnavailableReason.PendingSelection
            },
            reference = pending,
        )
    }
    active as SourceSeparationActivePresetState.Reference
    val reference = active.reference
    val installed = active.installedModel
        ?: return SourceSeparationActiveCacheModelResolution.Unavailable(
            reason = if (reference.profileId != null &&
                installedModel(reference.artifactSha256) != null
            ) {
                SourceSeparationActiveCacheModelUnavailableReason.ProfileNotInstalled
            } else {
                SourceSeparationActiveCacheModelUnavailableReason.ModelNotInstalled
            },
            reference = reference,
        )
    if (!installed.sha256.equals(reference.artifactSha256, ignoreCase = true) ||
        installed.modelId != reference.modelId
    ) {
        return SourceSeparationActiveCacheModelResolution.Unavailable(
            SourceSeparationActiveCacheModelUnavailableReason.ModelIdentityMismatch,
            reference,
        )
    }
    if (verifyArtifactHash && !isInstalledArtifactIntact(installed)) {
        return SourceSeparationActiveCacheModelResolution.Unavailable(
            SourceSeparationActiveCacheModelUnavailableReason.ModelIdentityMismatch,
            reference,
        )
    }
    val snapshot = try {
        installed.cacheContractSnapshot(this)
    } catch (_: Throwable) {
        return SourceSeparationActiveCacheModelResolution.Unavailable(
            SourceSeparationActiveCacheModelUnavailableReason.ContractInvalid,
            reference,
        )
    } ?: return SourceSeparationActiveCacheModelResolution.Unavailable(
        reason = if (installed.bindingKind == SourceSeparationPresetBindingKind.CustomProfile) {
            SourceSeparationActiveCacheModelUnavailableReason.ProfileNotInstalled
        } else {
            SourceSeparationActiveCacheModelUnavailableReason.ContractMismatch
        },
        reference = reference,
    )
    val expectedReferenceProfileId = snapshot.profileRevisionId.takeIf {
        snapshot.profileOrigin == SourceSeparationCacheProfileOrigin.Custom
    }
    if (reference.contractSchemaVersion != snapshot.contractSchemaVersion ||
        reference.profileId != expectedReferenceProfileId
    ) {
        return SourceSeparationActiveCacheModelResolution.Unavailable(
            reason = if (snapshot.profileOrigin == SourceSeparationCacheProfileOrigin.Custom) {
                SourceSeparationActiveCacheModelUnavailableReason.ProfileNotInstalled
            } else {
                SourceSeparationActiveCacheModelUnavailableReason.ContractMismatch
            },
            reference = reference,
        )
    }
    val executionProfile = runCatching {
        installed.executionProfile(this)
    }.getOrNull() ?: return SourceSeparationActiveCacheModelResolution.Unavailable(
        SourceSeparationActiveCacheModelUnavailableReason.ContractInvalid,
        reference,
    )
    return SourceSeparationActiveCacheModelResolution.Ready(
        SourceSeparationResolvedCacheModel(
            installed = installed,
            contract = snapshot,
            artifact = MdxModelArtifact(
                file = installed.file,
                byteSize = installed.byteSize,
                sha256 = installed.sha256,
            ),
            executionProfile = executionProfile,
        )
    )
}

/**
 * Resolves one already-installed model from immutable execution identity only.
 *
 * This path deliberately does not read or modify the active-model preference.
 */
fun SourceSeparationPresetRepository.resolveExactCacheModel(
    expected: SourceSeparationCacheContractSnapshot,
    verifyArtifactHash: Boolean = true,
): SourceSeparationResolvedCacheModel {
    val baseInstalled = try {
        if (verifyArtifactHash) {
            requireInstalledPreset(expected.artifactSha256)
        } else {
            requireTrustedInstalledPreset(expected.artifactSha256)
        }
    } catch (error: Throwable) {
        throw SourceSeparationExactCacheModelException(
            "The exact execution model is not installed or failed validation.",
            error,
        )
    }
    val installed = if (expected.profileOrigin == SourceSeparationCacheProfileOrigin.Custom) {
        val profile = customProfiles().singleOrNull { candidate ->
            candidate.profileId == expected.profileRevisionId &&
                candidate.modelId == expected.modelId &&
                candidate.artifact.sha256.equals(expected.artifactSha256, ignoreCase = true)
        } ?: throw SourceSeparationExactCacheModelException(
            "The exact custom execution profile is not installed.",
        )
        baseInstalled.copy(
            modelId = profile.modelId,
            displayName = profile.displayName,
            customProfile = profile,
        )
    } else {
        baseInstalled
    }
    if (installed.modelId != expected.modelId ||
        installed.file.name != expected.artifactFileName ||
        installed.byteSize != expected.artifactByteSize ||
        !installed.sha256.equals(expected.artifactSha256, ignoreCase = true)
    ) {
        throw SourceSeparationExactCacheModelException(
            "The installed artifact does not match the exact execution identity.",
        )
    }
    val actual = runCatching {
        installed.cacheContractSnapshot(this)
    }.getOrElse { error ->
        throw SourceSeparationExactCacheModelException(
            "The installed execution contract is invalid.",
            error,
        )
    } ?: throw SourceSeparationExactCacheModelException(
        "The installed execution contract is unavailable.",
    )
    if (actual != expected) {
        throw SourceSeparationExactCacheModelException(
            "The installed execution contract does not match the admitted contract.",
        )
    }
    val executionProfile = runCatching {
        installed.executionProfile(this)
    }.getOrElse { error ->
        throw SourceSeparationExactCacheModelException(
            "The installed execution profile is invalid.",
            error,
        )
    } ?: throw SourceSeparationExactCacheModelException(
        "The installed execution profile is unavailable.",
    )
    return SourceSeparationResolvedCacheModel(
        installed = installed,
        contract = actual,
        artifact = MdxModelArtifact(
            file = installed.file,
            byteSize = installed.byteSize,
            sha256 = installed.sha256,
        ),
        executionProfile = executionProfile,
    )
}

private fun SourceSeparationInstalledPreset.cacheContractSnapshot(
    repository: SourceSeparationPresetRepository,
): SourceSeparationCacheContractSnapshot? {
    return when (bindingKind) {
        SourceSeparationPresetBindingKind.Official -> executionContract(repository)?.let {
            SourceSeparationCacheContractSnapshot.fromOfficial(it)
        }
        SourceSeparationPresetBindingKind.Sidecar -> sidecarContract?.let {
            SourceSeparationCacheContractSnapshot.fromOfficial(
                contract = it,
                origin = SourceSeparationCacheProfileOrigin.Sidecar,
            )
        }
        SourceSeparationPresetBindingKind.CustomProfile -> customProfile?.let {
            SourceSeparationCacheContractSnapshot.fromCustom(it)
        }
    }
}

private fun SourceSeparationInstalledPreset.executionContract(
    repository: SourceSeparationPresetRepository,
): SourceSeparationModelContract? {
    return when (bindingKind) {
        SourceSeparationPresetBindingKind.Official -> {
            val expectedContractId = contractId ?: return null
            repository.catalogSnapshot().contracts.singleOrNull { contract ->
                contract.contractId == expectedContractId &&
                    contract.modelId == modelId &&
                    contract.artifact.sha256.equals(sha256, ignoreCase = true)
            }
        }
        SourceSeparationPresetBindingKind.Sidecar -> sidecarContract
        SourceSeparationPresetBindingKind.CustomProfile -> null
    }
}

private fun SourceSeparationInstalledPreset.executionProfile(
    repository: SourceSeparationPresetRepository,
): MdxExecutionProfile? {
    val catalog = repository.catalogSnapshot()
    return when (bindingKind) {
        SourceSeparationPresetBindingKind.Official,
        SourceSeparationPresetBindingKind.Sidecar -> executionContract(repository)
            ?.toMdxExecutionProfile(catalog.runtimeQualifications)

        SourceSeparationPresetBindingKind.CustomProfile -> customProfile
            ?.toMdxExecutionProfile(catalog.runtimeQualifications)
    }
}

private fun SourceSeparationCacheContractSnapshot.matches(
    manifest: SourceSeparationCacheManifest,
): Boolean {
    return modelId == manifest.identity.modelId &&
        artifactSha256.equals(manifest.identity.artifactSha256, ignoreCase = true) &&
        contractId == manifest.identity.contractId &&
        contractSchemaVersion == manifest.identity.contractSchemaVersion &&
        contractFingerprint.equals(manifest.identity.contractFingerprint, ignoreCase = true) &&
        profileRevisionId == manifest.identity.profileRevisionId &&
        pipelineId == manifest.identity.pipelineId &&
        pipelineVersion == manifest.identity.pipelineVersion
}

data class SourceSeparationResolvedCacheModel(
    val installed: SourceSeparationInstalledPreset,
    val contract: SourceSeparationCacheContractSnapshot,
    val artifact: MdxModelArtifact,
    val executionProfile: MdxExecutionProfile,
)

sealed interface SourceSeparationActiveCacheModelResolution {
    data class Ready(
        val model: SourceSeparationResolvedCacheModel,
    ) : SourceSeparationActiveCacheModelResolution

    data class Unavailable(
        val reason: SourceSeparationActiveCacheModelUnavailableReason,
        val reference: SourceSeparationActiveModelReference? = null,
    ) : SourceSeparationActiveCacheModelResolution
}

enum class SourceSeparationActiveCacheModelUnavailableReason {
    NoSelection,
    PendingSelection,
    ModelNotInstalled,
    ModelIdentityMismatch,
    ProfileNotInstalled,
    ContractMismatch,
    ContractInvalid,
}

class SourceSeparationExactCacheModelException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
