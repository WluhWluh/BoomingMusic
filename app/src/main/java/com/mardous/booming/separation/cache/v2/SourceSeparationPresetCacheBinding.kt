package com.mardous.booming.separation.cache.v2

import com.mardous.booming.separation.model.MdxExecutionProfile
import com.mardous.booming.separation.model.MdxModelArtifact
import com.mardous.booming.separation.model.contract.SourceSeparationModelContract
import com.mardous.booming.separation.model.contract.toMdxExecutionProfile
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
    return runCatching {
        val active = activeModel() as? SourceSeparationActivePresetState.Reference
            ?: return@runCatching null
        val installed = active.installedModel ?: return@runCatching null
        if (!installed.sha256.equals(active.reference.artifactSha256, ignoreCase = true) ||
            installed.modelId != active.reference.modelId
        ) {
            return@runCatching null
        }
        val snapshot = installed.cacheContractSnapshot(this) ?: return@runCatching null
        if (active.reference.profileId != null &&
            active.reference.profileId != snapshot.profileRevisionId
        ) {
            return@runCatching null
        }
        val catalog = catalogSnapshot()
        val executionProfile = when (installed.bindingKind) {
            SourceSeparationPresetBindingKind.Official,
            SourceSeparationPresetBindingKind.Sidecar -> {
                installed.executionContract(this)
                    ?.toMdxExecutionProfile(catalog.runtimeQualifications)
                    ?: return@runCatching null
            }
            SourceSeparationPresetBindingKind.CustomProfile -> {
                installed.customProfile
                    ?.toMdxExecutionProfile(catalog.runtimeQualifications)
                    ?: return@runCatching null
            }
        }
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
    }.getOrNull()
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
