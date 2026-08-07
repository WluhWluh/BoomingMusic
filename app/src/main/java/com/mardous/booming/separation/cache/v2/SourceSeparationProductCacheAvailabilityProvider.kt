package com.mardous.booming.separation.cache.v2

import com.mardous.booming.separation.model.contract.SourceSeparationInstalledMultiStemModel
import com.mardous.booming.separation.model.contract.SourceSeparationMultiStemReleaseInstaller
import com.mardous.booming.separation.model.contract.SourceSeparationMultiTensorExecutableContractLoader

class SourceSeparationProductCacheAvailabilityProvider(
    private val preset: SourceSeparationPresetCacheAvailabilityProvider,
    private val multiStem: SourceSeparationMultiStemCacheAvailabilityProvider,
) : SourceSeparationCacheModelAvailabilityProvider {
    override fun availability(
        manifest: SourceSeparationCacheManifest,
    ): SourceSeparationCacheModelAvailability = if (manifest.contract.multiTensorContract != null) {
        multiStem.availability(manifest)
    } else {
        preset.availability(manifest)
    }
}

class SourceSeparationMultiStemCacheAvailabilityProvider internal constructor(
    private val installedModel: (String) -> SourceSeparationInstalledMultiStemModel?,
) : SourceSeparationCacheModelAvailabilityProvider {
    constructor(installer: SourceSeparationMultiStemReleaseInstaller) :
        this(installer::installed)

    override fun availability(
        manifest: SourceSeparationCacheManifest,
    ): SourceSeparationCacheModelAvailability {
        if (manifest.contract.multiTensorContract == null) {
            return SourceSeparationCacheModelAvailability.Unknown
        }
        val installed = installedModel(manifest.identity.modelId)
            ?: return SourceSeparationCacheModelAvailability.ModelNotInstalled
        if (installed.modelId != manifest.identity.modelId ||
            !installed.modelSha256.equals(manifest.identity.artifactSha256, ignoreCase = true) ||
            installed.contractId != manifest.identity.contractId ||
            installed.pipelineId != manifest.identity.pipelineId ||
            !installed.modelFile.isFile || !installed.sidecarFile.isFile ||
            installed.modelFile.length() != installed.modelByteSize
        ) {
            return SourceSeparationCacheModelAvailability.ContractMismatch
        }
        val executable = runCatching {
            installed.sidecarFile.bufferedReader().use { reader ->
                SourceSeparationMultiTensorExecutableContractLoader.load(reader.readText())
            }
        }.getOrNull() ?: return SourceSeparationCacheModelAvailability.ContractMismatch
        val snapshot = SourceSeparationCacheContractSnapshot.fromMultiTensor(executable)
        return if (snapshot.matchesIdentity(manifest)) {
            SourceSeparationCacheModelAvailability.InstalledExact
        } else {
            SourceSeparationCacheModelAvailability.ContractMismatch
        }
    }
}

private fun SourceSeparationCacheContractSnapshot.matchesIdentity(
    manifest: SourceSeparationCacheManifest,
): Boolean = modelId == manifest.identity.modelId &&
    artifactSha256.equals(manifest.identity.artifactSha256, ignoreCase = true) &&
    contractId == manifest.identity.contractId &&
    contractSchemaVersion == manifest.identity.contractSchemaVersion &&
    contractFingerprint.equals(manifest.identity.contractFingerprint, ignoreCase = true) &&
    profileRevisionId == manifest.identity.profileRevisionId &&
    pipelineId == manifest.identity.pipelineId &&
    pipelineVersion == manifest.identity.pipelineVersion
