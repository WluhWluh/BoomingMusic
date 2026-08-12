package com.mardous.booming.debug

import android.os.Build
import android.os.Process
import com.mardous.booming.separation.SourceSeparationMultiStemPlaybackSelectionStore
import com.mardous.booming.separation.model.AndroidMdxRuntimePlatformProvider
import com.mardous.booming.separation.model.contract.CatalogEntry
import com.mardous.booming.separation.model.contract.SourceSeparationInstalledMultiStemModel
import com.mardous.booming.separation.model.contract.SourceSeparationMultiStemInstallProgressKind
import com.mardous.booming.separation.model.contract.SourceSeparationMultiStemReleaseInstaller
import com.mardous.booming.separation.model.contract.SourceSeparationReleaseCatalog
import com.mardous.booming.separation.model.contract.SourceSeparationReleaseCatalogMetadata
import com.mardous.booming.separation.model.preset.SourceSeparationActivePresetState
import com.mardous.booming.separation.model.preset.SourceSeparationInstalledPreset
import com.mardous.booming.separation.model.preset.SourceSeparationPresetBindingKind
import com.mardous.booming.separation.model.preset.SourceSeparationPresetDownloader
import com.mardous.booming.separation.model.preset.SourceSeparationPresetRepository
import com.mardous.booming.separation.model.preset.SourceSeparationPresetSelectionScope
import com.mardous.booming.separation.runtime.SourceSeparationGpuRuntimeInventoryItem
import com.mardous.booming.separation.runtime.SourceSeparationGpuRuntimeStore
import com.mardous.booming.separation.runtime.SourceSeparationRuntimeInventoryItem
import com.mardous.booming.separation.runtime.SourceSeparationRuntimeProcessController
import com.mardous.booming.separation.runtime.SourceSeparationRuntimeStore
import com.mardous.booming.ui.screen.player.SourceSeparationForegroundWorkerDebugBridge
import org.json.JSONArray
import org.json.JSONObject
import org.koin.java.KoinJavaComponent.get

internal class SourceSeparationDebugModelRuntimeController(
    private val operations: SourceSeparationDebugOperationRegistry,
) {
    private val presetRepository: SourceSeparationPresetRepository
        get() = get(SourceSeparationPresetRepository::class.java)
    private val presetDownloader: SourceSeparationPresetDownloader
        get() = get(SourceSeparationPresetDownloader::class.java)
    private val multiStemInstaller: SourceSeparationMultiStemReleaseInstaller
        get() = get(SourceSeparationMultiStemReleaseInstaller::class.java)
    private val multiStemSelection: SourceSeparationMultiStemPlaybackSelectionStore
        get() = get(SourceSeparationMultiStemPlaybackSelectionStore::class.java)
    private val runtimeStore: SourceSeparationRuntimeStore
        get() = get(SourceSeparationRuntimeStore::class.java)
    private val gpuRuntimeStore: SourceSeparationGpuRuntimeStore
        get() = get(SourceSeparationGpuRuntimeStore::class.java)
    private val runtimeProcessController: SourceSeparationRuntimeProcessController
        get() = get(SourceSeparationRuntimeProcessController::class.java)

    fun models(
        refresh: Boolean,
        allowCatalogDownload: Boolean = true,
    ): JSONObject {
        val repository = presetRepository
        val activeReference = repository.activeSelectionFlow.value.reference
        val selectedMultiStem = multiStemSelection.selectedModelId()
        val installedMdx = repository.installedModels()
        val installedBySha = installedMdx.associateBy { it.sha256.lowercase() }
        val multiCatalogResult = runCatching {
            when {
                refresh -> multiStemInstaller.refreshCatalog()
                allowCatalogDownload -> multiStemInstaller.catalog()
                else -> multiStemInstaller.cachedCatalog()
                    ?: error("The multi-stem Release catalog is not cached.")
            }
        }
        val multiInstalled = multiStemInstaller.installedModels()
            .associateBy(SourceSeparationInstalledMultiStemModel::modelId)

        val entries = JSONArray()
        repository.catalogEntries().forEach { entry ->
            val official = runCatching { repository.officialPreset(entry.modelId) }.getOrNull()
            val installed = official?.sha256?.lowercase()?.let(installedBySha::get)
            entries.put(
                entry.toJson(
                    installed = installed,
                    active = selectedMultiStem == null &&
                        activeReference?.modelId == entry.modelId &&
                        activeReference.artifactSha256.equals(official?.sha256, ignoreCase = true),
                    artifactSha256 = official?.sha256,
                    byteSize = official?.byteSize,
                    releaseTag = official?.releaseTag,
                ),
            )
        }
        multiCatalogResult.getOrNull()?.entries
            ?.filter { entry ->
                entry.artifactFamily ==
                    SourceSeparationReleaseCatalogMetadata.MULTISTEM_ARTIFACT_FAMILY &&
                    entry.pipelineId ==
                    SourceSeparationReleaseCatalogMetadata.MULTISTEM_PIPELINE_ID
            }
            ?.forEach { entry ->
            entries.put(
                JSONObject()
                    .put("family", "htdemucs_multistem")
                    .put("modelId", entry.modelId)
                    .put("displayName", entry.displayName)
                    .put("supportLevel", entry.supportLevel)
                    .put("activationPolicy", entry.activationPolicy)
                    .put("releaseMaturity", "experimental")
                    .put("isDefault", entry.isDefault)
                    .put("artifactSha256", entry.artifact.sha256)
                    .put("byteSize", entry.artifact.byteSize + entry.contract.byteSize)
                    .put("releaseTag", multiCatalogResult.getOrThrow().releaseTag)
                    .put("installed", entry.modelId in multiInstalled)
                    .put("active", selectedMultiStem == entry.modelId)
                    .put("allowedBackends", JSONArray(entry.allowedBackends)),
            )
        }

        return JSONObject()
            .put("activeFamily", if (selectedMultiStem == null) "mdx" else "htdemucs_multistem")
            .put("activeModelId", selectedMultiStem ?: activeReference?.modelId)
            .put("activeArtifactSha256", if (selectedMultiStem == null) {
                activeReference?.artifactSha256
            } else {
                multiInstalled[selectedMultiStem]?.modelSha256
            })
            .put("entries", entries)
            .put(
                "installedMdxArtifacts",
                JSONArray().also { array -> installedMdx.forEach { array.put(it.toJson()) } },
            )
            .put(
                "installedMultiStemArtifacts",
                JSONArray().also { array ->
                    multiInstalled.values.forEach { array.put(it.toJson()) }
                },
            )
            .put("multiStemCatalogError", multiCatalogResult.exceptionOrNull()?.message)
    }

    fun submitModelInstall(modelId: String): SourceSeparationDebugOperationSnapshot {
        require(modelId.isNotBlank()) { "model_id is empty." }
        return operations.submit("model.install", modelId) {
            val mdxEntry = presetRepository.catalogEntries().singleOrNull { it.modelId == modelId }
            if (mdxEntry != null) {
                val preset = presetRepository.officialPreset(modelId)
                onCancel { presetDownloader.cancel(modelId) }
                progress(0L, preset.byteSize, stage = "model")
                val installed = presetDownloader.download(modelId) { update ->
                    progress(
                        downloadedBytes = update.downloadedBytes,
                        totalBytes = update.totalBytes,
                        stage = if (update.usingMirror) "model_mirror" else "model",
                    )
                }
                JSONObject().put("family", "mdx").put("installed", installed.toJson())
            } else {
                val catalog = multiStemInstaller.catalog()
                val entry = requireMultiStemEntry(catalog, modelId)
                val modelBytes = entry.artifact.byteSize
                val totalBytes = modelBytes + entry.contract.byteSize
                progress(0L, totalBytes, stage = "model")
                val installed = multiStemInstaller.install(modelId) { update ->
                    ensureActive()
                    val downloaded = when (update.kind) {
                        SourceSeparationMultiStemInstallProgressKind.Model -> update.downloadedBytes
                        SourceSeparationMultiStemInstallProgressKind.Sidecar ->
                            modelBytes + update.downloadedBytes
                    }
                    progress(
                        downloadedBytes = downloaded.coerceAtMost(totalBytes),
                        totalBytes = totalBytes,
                        stage = update.kind.name.lowercase(),
                    )
                }
                JSONObject()
                    .put("family", "htdemucs_multistem")
                    .put("installed", installed.toJson())
            }
        }
    }

    fun submitModelSelect(
        modelId: String?,
        sha256: String?,
        profileId: String?,
    ): SourceSeparationDebugOperationSnapshot {
        require(!modelId.isNullOrBlank() || !sha256.isNullOrBlank()) {
            "model_id or sha256 is required."
        }
        val target = sha256?.lowercase() ?: requireNotNull(modelId)
        return operations.submit("model.select", target) {
            val multiInstalled = resolveInstalledMultiStem(modelId, sha256)
            if (multiInstalled != null) {
                require(profileId == null) { "profile_id is only valid for an imported MDX model." }
                if (multiStemSelection.selectedModelId() == multiInstalled.modelId) {
                    return@submit JSONObject()
                        .put("family", "htdemucs_multistem")
                        .put("alreadySelected", true)
                        .put("selected", multiInstalled.toJson())
                }
                stage("pause_old_model")
                SourceSeparationForegroundWorkerDebugBridge.pauseForModelSupersession()
                ensureActive()
                multiStemSelection.select(multiInstalled.modelId)
                JSONObject()
                    .put("family", "htdemucs_multistem")
                    .put("selected", multiInstalled.toJson())
            } else {
                val installed = resolveInstalledMdx(modelId, sha256)
                val active = presetRepository.activeSelectionFlow.value.reference
                if (multiStemSelection.selectedModelId() == null &&
                    active?.artifactSha256.equals(installed.sha256, ignoreCase = true) &&
                    active?.profileId == profileId
                ) {
                    return@submit JSONObject()
                        .put("family", "mdx")
                        .put("alreadySelected", true)
                        .put("modelId", active?.modelId)
                        .put("artifactSha256", active?.artifactSha256)
                        .put("profileId", active?.profileId)
                }
                stage("pause_old_model")
                SourceSeparationForegroundWorkerDebugBridge.pauseForModelSupersession()
                ensureActive()
                stage("activate")
                val reference = if (profileId != null) {
                    presetRepository.activateCustomProfile(
                        sha256 = installed.sha256,
                        profileId = profileId,
                        platform = AndroidMdxRuntimePlatformProvider.current(),
                        scope = SourceSeparationPresetSelectionScope.InternalValidation,
                    )
                } else {
                    presetRepository.activate(
                        sha256 = installed.sha256,
                        platform = AndroidMdxRuntimePlatformProvider.current(),
                        scope = SourceSeparationPresetSelectionScope.InternalValidation,
                        experimentalConfirmed = true,
                    )
                }
                multiStemSelection.select(null)
                JSONObject()
                    .put("family", "mdx")
                    .put("modelId", reference.modelId)
                    .put("artifactSha256", reference.artifactSha256)
                    .put("profileId", reference.profileId)
            }
        }
    }

    fun submitModelDelete(
        modelId: String?,
        sha256: String?,
    ): SourceSeparationDebugOperationSnapshot {
        require(!modelId.isNullOrBlank() || !sha256.isNullOrBlank()) {
            "model_id or sha256 is required."
        }
        val target = sha256?.lowercase() ?: requireNotNull(modelId)
        return operations.submit("model.delete", target) {
            val multiInstalled = resolveInstalledMultiStem(modelId, sha256)
            if (multiInstalled != null) {
                require(multiStemSelection.selectedModelId() != multiInstalled.modelId) {
                    "The active multi-stem model must be changed before it can be deleted."
                }
                require(!SourceSeparationForegroundWorkerDebugBridge.isModelArtifactInUse(
                    multiInstalled.modelSha256,
                )) { "The model is still used by source-separation work." }
                check(multiStemInstaller.delete(multiInstalled.modelId)) {
                    "The multi-stem model was not installed."
                }
                JSONObject()
                    .put("family", "htdemucs_multistem")
                    .put("modelId", multiInstalled.modelId)
                    .put("deleted", true)
            } else {
                val installed = resolveInstalledMdx(modelId, sha256)
                require(!SourceSeparationForegroundWorkerDebugBridge.isModelArtifactInUse(
                    installed.sha256,
                )) { "The model is still used by source-separation work." }
                check(presetRepository.delete(installed.sha256)) {
                    "The MDX model was not installed."
                }
                JSONObject()
                    .put("family", "mdx")
                    .put("modelId", installed.modelId)
                    .put("artifactSha256", installed.sha256)
                    .put("deleted", true)
            }
        }
    }

    fun runtimes(verify: Boolean): JSONObject {
        val cpuItems = if (verify) runtimeStore.inventory() else runtimeStore.trustedInventory()
        val gpuItems = if (verify) gpuRuntimeStore.inventory() else gpuRuntimeStore.trustedInventory()
        return JSONObject()
            .put("currentAbi", currentAbi())
            .put("androidApi", Build.VERSION.SDK_INT)
            .put("verifiedPayloadHashes", verify)
            .put(
                "cpu",
                JSONArray().also { array -> cpuItems.forEach { array.put(it.toJson()) } },
            )
            .put(
                "gpu",
                JSONArray().also { array -> gpuItems.forEach { array.put(it.toJson()) } },
            )
            .put("npuSupported", false)
    }

    fun submitRuntimeMutation(
        action: String,
        requestedKind: String?,
        componentId: String?,
    ): SourceSeparationDebugOperationSnapshot {
        val kind = resolveRuntimeKind(requestedKind, componentId)
        val resolvedComponentId = resolveRuntimeComponentId(kind, componentId)
        return operations.submit("runtime.$action", "$kind:$resolvedComponentId") {
            stage("recycle_idle_process")
            runtimeProcessController.recycleIfIdle()
            ensureActive()
            val result = when (kind) {
                "cpu" -> mutateCpuRuntime(action, resolvedComponentId, this)
                "gpu" -> mutateGpuRuntime(action, resolvedComponentId, this)
                else -> error("Unsupported runtime kind '$kind'.")
            }
            JSONObject()
                .put("kind", kind)
                .put("action", action)
                .put("component", result)
        }
    }

    private fun mutateCpuRuntime(
        action: String,
        componentId: String,
        context: SourceSeparationDebugOperationContext,
    ): JSONObject {
        val result = when (action) {
            "install" -> runtimeStore.install(componentId) { downloaded, total ->
                context.progress(downloaded, total, "download")
            }
            "repair" -> runtimeStore.repair(componentId) { downloaded, total ->
                context.progress(downloaded, total, "download")
            }
            "activate" -> runtimeStore.activatePending(componentId)
            "remove" -> runtimeStore.remove(componentId)
            else -> throw IllegalArgumentException("Unknown runtime action '$action'.")
        }
        return result.toJson()
    }

    private fun mutateGpuRuntime(
        action: String,
        componentId: String,
        context: SourceSeparationDebugOperationContext,
    ): JSONObject {
        val result = when (action) {
            "install" -> gpuRuntimeStore.install(componentId) { downloaded, total ->
                context.progress(downloaded, total, "download")
            }
            "repair" -> gpuRuntimeStore.repair(componentId) { downloaded, total ->
                context.progress(downloaded, total, "download")
            }
            "activate" -> gpuRuntimeStore.activatePending(componentId)
            "remove" -> gpuRuntimeStore.remove(componentId)
            else -> throw IllegalArgumentException("Unknown runtime action '$action'.")
        }
        return result.toJson()
    }

    private fun resolveRuntimeKind(requested: String?, componentId: String?): String {
        requested?.trim()?.lowercase()?.let { kind ->
            require(kind == "cpu" || kind == "gpu") { "runtime_kind must be cpu or gpu." }
            return kind
        }
        if (componentId != null) {
            if (runtimeStore.trustedInventory().any { it.catalogEntry.componentId == componentId }) {
                return "cpu"
            }
            if (gpuRuntimeStore.trustedInventory().any {
                    it.catalogEntry.componentId == componentId
                }
            ) {
                return "gpu"
            }
            throw IllegalArgumentException("Unknown runtime component '$componentId'.")
        }
        return "cpu"
    }

    private fun resolveRuntimeComponentId(kind: String, requested: String?): String {
        val abi = currentAbi()
        val items = when (kind) {
            "cpu" -> runtimeStore.trustedInventory().map { item ->
                item.catalogEntry.componentId to item.catalogEntry.abi
            }
            "gpu" -> gpuRuntimeStore.trustedInventory().map { item ->
                item.catalogEntry.componentId to item.catalogEntry.abi
            }
            else -> emptyList()
        }
        val component = if (requested != null) {
            items.singleOrNull { it.first == requested }
                ?: throw IllegalArgumentException("Unknown $kind runtime component '$requested'.")
        } else {
            items.singleOrNull { it.second == abi }
                ?: throw IllegalArgumentException("No $kind runtime is available for ABI $abi.")
        }
        require(component.second == abi) {
            "Runtime mutation is limited to the current process ABI $abi, not ${component.second}."
        }
        return component.first
    }

    private fun resolveInstalledMdx(
        modelId: String?,
        sha256: String?,
    ): SourceSeparationInstalledPreset {
        sha256?.let { requestedSha ->
            val installed = presetRepository.installedModel(requestedSha.lowercase())
                ?: throw IllegalArgumentException("No installed MDX model has SHA-256 '$requestedSha'.")
            require(modelId == null || installed.modelId == modelId) {
                "MDX model '$modelId' does not have SHA-256 '$requestedSha'."
            }
            return installed
        }
        val matches = presetRepository.installedModels().filter { it.modelId == modelId }
        require(matches.isNotEmpty()) { "MDX model '$modelId' is not installed." }
        require(matches.size == 1) {
            "MDX model '$modelId' has ${matches.size} installed artifacts; specify sha256."
        }
        return matches.single()
    }

    private fun resolveInstalledMultiStem(
        modelId: String?,
        sha256: String?,
    ): SourceSeparationInstalledMultiStemModel? {
        val normalizedSha = sha256?.lowercase()
        val matches = multiStemInstaller.installedModels().filter { installed ->
            (modelId == null || installed.modelId == modelId) &&
                (normalizedSha == null || installed.modelSha256.equals(normalizedSha, true))
        }
        require(matches.size <= 1) {
            "Multiple installed multi-stem models matched; specify both model_id and sha256."
        }
        return matches.singleOrNull()
    }

    private fun requireMultiStemEntry(
        catalog: SourceSeparationReleaseCatalog,
        modelId: String,
    ) = catalog.entries.singleOrNull { entry ->
        entry.modelId == modelId &&
            entry.artifactFamily ==
            SourceSeparationReleaseCatalogMetadata.MULTISTEM_ARTIFACT_FAMILY &&
            entry.pipelineId == SourceSeparationReleaseCatalogMetadata.MULTISTEM_PIPELINE_ID
    }
        ?: throw IllegalArgumentException("Unknown model_id '$modelId'.")

    private fun currentAbi(): String {
        val candidates = if (Process.is64Bit()) {
            Build.SUPPORTED_64_BIT_ABIS
        } else {
            Build.SUPPORTED_32_BIT_ABIS
        }
        return candidates.firstOrNull()
            ?: throw IllegalStateException("The process ABI is unavailable.")
    }
}

private fun CatalogEntry.toJson(
    installed: SourceSeparationInstalledPreset?,
    active: Boolean,
    artifactSha256: String?,
    byteSize: Long?,
    releaseTag: String?,
): JSONObject = JSONObject()
    .put("family", "mdx")
    .put("modelId", modelId)
    .put("displayName", displayName)
    .put("supportLevel", supportLevel.name.lowercase())
    .put("activationPolicy", activationPolicy.name)
    .put("releaseMaturity", releaseMaturity.name.lowercase())
    .put("isDefault", isDefault)
    .put("artifactSha256", artifactSha256)
    .put("byteSize", byteSize)
    .put("releaseTag", releaseTag)
    .put("installed", installed != null)
    .put("active", active)

private fun SourceSeparationInstalledPreset.toJson(): JSONObject = JSONObject()
    .put("modelId", modelId)
    .put("displayName", displayName)
    .put("fileName", file.name)
    .put("byteSize", byteSize)
    .put("sha256", sha256)
    .put("origin", origin.name)
    .put("bindingKind", bindingKind.name)
    .put("contractId", contractId)
    .put("profileId", customProfile?.profileId)
    .put("installedAtEpochMs", installedAtEpochMs)

private fun SourceSeparationInstalledMultiStemModel.toJson(): JSONObject = JSONObject()
    .put("modelId", modelId)
    .put("displayName", displayName)
    .put("fileName", modelFile.name)
    .put("sidecarFileName", sidecarFile.name)
    .put("byteSize", modelByteSize)
    .put("sha256", modelSha256)
    .put("contractId", contractId)
    .put("pipelineId", pipelineId)
    .put("installedAtEpochMs", installedAtEpochMs)

private fun SourceSeparationRuntimeInventoryItem.toJson(): JSONObject = JSONObject()
    .put("componentId", catalogEntry.componentId)
    .put("componentType", catalogEntry.componentType)
    .put("abi", catalogEntry.abi)
    .put("androidMinApi", catalogEntry.androidMinApi)
    .put("baseLiteRtVersion", catalogEntry.baseLiteRtVersion)
    .put("runtimeArtifactVersion", catalogEntry.runtimeArtifactVersion)
    .put("releaseVersion", catalogEntry.producerReleaseVersion)
    .put("maturity", catalogEntry.maturity)
    .put("capabilityId", catalogEntry.capabilityId)
    .put("downloadBytes", catalogEntry.delivery.expectedByteSize)
    .put("installedBytes", installedBytes)
    .put("state", state.name.lowercase())
    .put("reason", reason)
    .put("installedAtEpochMs", installedAtEpochMs)
    .put("lastValidatedAtEpochMs", lastValidatedAtEpochMs)

private fun SourceSeparationGpuRuntimeInventoryItem.toJson(): JSONObject = JSONObject()
    .put("componentId", catalogEntry.componentId)
    .put("componentType", catalogEntry.componentType)
    .put("abi", catalogEntry.abi)
    .put("androidMinApi", catalogEntry.androidMinApi)
    .put("baseLiteRtVersion", catalogEntry.baseLiteRtVersion)
    .put("runtimeArtifactVersion", catalogEntry.runtimeArtifactVersion)
    .put("releaseVersion", catalogEntry.producerReleaseVersion)
    .put("maturity", catalogEntry.maturity)
    .put("capabilityId", catalogEntry.capabilityId)
    .put("profileId", catalogEntry.capability.profileId)
    .put("requiredCpuComponentId", catalogEntry.requiredCpuComponentId)
    .put("downloadBytes", catalogEntry.delivery.expectedByteSize)
    .put("installedBytes", installedBytes)
    .put("state", state.name.lowercase())
    .put("reason", reason)
    .put("installedAtEpochMs", installedAtEpochMs)
    .put("lastValidatedAtEpochMs", lastValidatedAtEpochMs)
