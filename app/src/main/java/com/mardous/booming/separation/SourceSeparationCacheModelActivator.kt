package com.mardous.booming.separation

import com.mardous.booming.separation.cache.v2.SourceSeparationCacheModelAvailability
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwareCacheEntry
import com.mardous.booming.separation.model.AndroidMdxRuntimePlatformProvider
import com.mardous.booming.separation.model.MdxRuntimePlatformProvider
import com.mardous.booming.separation.model.contract.SourceSeparationMultiStemReleaseInstaller
import com.mardous.booming.separation.model.preset.SourceSeparationPresetBindingKind
import com.mardous.booming.separation.model.preset.SourceSeparationPresetRepository
import com.mardous.booming.separation.model.preset.SourceSeparationPresetSelectionScope

/** Activates the exact installed model identity carried by one cache entry. */
class SourceSeparationCacheModelActivator internal constructor(
    private val currentSelection: () -> SourceSeparationExecutionSelectionSnapshot,
    private val validateTarget: (SourceSeparationModelAwareCacheEntry) -> Unit,
    private val activateTarget: (SourceSeparationModelAwareCacheEntry) -> Unit,
    private val pauseForModelSupersession: () -> Unit,
) {
    constructor(
        presetRepository: SourceSeparationPresetRepository,
        multiStemInstaller: SourceSeparationMultiStemReleaseInstaller,
        multiStemSelectionStore: SourceSeparationMultiStemPlaybackSelectionStore,
        executionSelectionResolver: SourceSeparationExecutionSelectionResolver,
        platformProvider: MdxRuntimePlatformProvider = AndroidMdxRuntimePlatformProvider,
        pauseForModelSupersession: () -> Unit = {},
    ) : this(
        currentSelection = executionSelectionResolver::current,
        validateTarget = { entry ->
            when (entry.modelFamily) {
                SourceSeparationModelFamily.Mdx -> {
                    val installed = requireNotNull(
                        presetRepository.installedModel(entry.artifactSha256),
                    ) { "The exact MDX model is not installed." }
                    require(installed.modelId == entry.modelId ||
                        installed.bindingKind == SourceSeparationPresetBindingKind.CustomProfile
                    ) { "The installed MDX model identity does not match the cache." }
                }
                SourceSeparationModelFamily.Htdemucs -> {
                    val installed = requireNotNull(multiStemInstaller.installed(entry.modelId)) {
                        "The exact HTDemucs model is not installed."
                    }
                    require(installed.modelSha256.equals(entry.artifactSha256, ignoreCase = true) &&
                        installed.contractId == entry.contractId &&
                        installed.pipelineId == entry.executionIdentity.pipelineId
                    ) { "The installed HTDemucs model identity does not match the cache." }
                }
            }
        },
        activateTarget = { entry ->
            when (entry.modelFamily) {
                SourceSeparationModelFamily.Mdx -> {
                    val installed = requireNotNull(
                        presetRepository.installedModel(entry.artifactSha256),
                    )
                    when (installed.bindingKind) {
                        SourceSeparationPresetBindingKind.CustomProfile ->
                            presetRepository.activateCustomProfile(
                                sha256 = installed.sha256,
                                profileId = entry.profileRevisionId,
                                platform = platformProvider.current(),
                                scope = SourceSeparationPresetSelectionScope.InternalValidation,
                            )
                        SourceSeparationPresetBindingKind.Official,
                        SourceSeparationPresetBindingKind.Sidecar,
                        -> presetRepository.activate(
                            sha256 = installed.sha256,
                            platform = platformProvider.current(),
                            scope = SourceSeparationPresetSelectionScope.InternalValidation,
                            experimentalConfirmed = true,
                        )
                    }
                    multiStemSelectionStore.select(null)
                }
                SourceSeparationModelFamily.Htdemucs ->
                    multiStemSelectionStore.select(entry.modelId)
            }
        },
        pauseForModelSupersession = pauseForModelSupersession,
    )

    fun current(): SourceSeparationExecutionSelectionSnapshot = currentSelection()

    fun activate(
        entry: SourceSeparationModelAwareCacheEntry,
    ): SourceSeparationExecutionSelectionSnapshot {
        require(entry.modelAvailability == SourceSeparationCacheModelAvailability.InstalledExact) {
            "The cache model is not installed with its exact execution contract."
        }
        current().takeIf { it.matches(entry.executionIdentity) }?.let { return it }
        validateTarget(entry)
        pauseForModelSupersession()
        activateTarget(entry)
        return current().also { selected ->
            check(selected.matches(entry.executionIdentity)) {
                "The activated model does not match the cache execution identity."
            }
        }
    }
}
