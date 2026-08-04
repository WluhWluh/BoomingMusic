package com.mardous.booming.separation.setup

import android.content.SharedPreferences
import com.mardous.booming.separation.cache.v2.SourceSeparationActiveCacheModelResolution
import com.mardous.booming.separation.cache.v2.SourceSeparationActiveCacheModelUnavailableReason
import com.mardous.booming.separation.cache.v2.resolveActiveCacheModelResolution
import com.mardous.booming.separation.cache.v2.resolveTrustedActiveCacheModelResolution
import com.mardous.booming.separation.delivery.SourceSeparationDeliveryReference
import com.mardous.booming.separation.model.AndroidMdxRuntimePlatformProvider
import com.mardous.booming.separation.model.MdxInferenceBackend
import com.mardous.booming.separation.model.MdxLiteRtCompatibilityResolver
import com.mardous.booming.separation.model.MdxRuntimePlatform
import com.mardous.booming.separation.model.MdxCompatibilityPolicy
import com.mardous.booming.separation.model.contract.CatalogEntry
import com.mardous.booming.separation.model.contract.SourceSeparationModelCatalog
import com.mardous.booming.separation.model.preset.SourceSeparationActivePresetState
import com.mardous.booming.separation.model.preset.SourceSeparationActiveModelReference
import com.mardous.booming.separation.model.preset.SourceSeparationPresetRepository
import com.mardous.booming.separation.model.preset.SourceSeparationPresetBindingKind
import com.mardous.booming.separation.runtime.SourceSeparationRuntimeInventoryItem
import com.mardous.booming.separation.runtime.SourceSeparationRuntimeState
import com.mardous.booming.separation.runtime.SourceSeparationRuntimeStore
import com.mardous.booming.separation.runtime.SourceSeparationGpuRuntimeInventoryItem
import com.mardous.booming.separation.runtime.SourceSeparationGpuRuntimeState
import com.mardous.booming.separation.runtime.SourceSeparationGpuRuntimeStore
import com.mardous.booming.util.readSourceSeparationGpuEnabled
import java.security.MessageDigest

internal object LocalSeparationReadinessContract {
    const val SCHEMA_VERSION = 2
    const val PLAN_SCHEMA_VERSION = 3
}

internal enum class LocalSeparationReadinessState {
    Ready,
    Degraded,
    NeedsSetup,
    RepairRequired,
    Unsupported,
}

internal enum class LocalSeparationBlockerCode {
    RuntimeInventoryUnavailable,
    UnsupportedProcessAbi,
    UnsupportedRuntimeApi,
    MissingCpuRuntime,
    InvalidCpuRuntime,
    PendingCpuRuntimeActivation,
    PendingCpuRuntimeDeletion,
    MissingGpuRuntime,
    InvalidGpuRuntime,
    PendingGpuRuntimeActivation,
    PendingGpuRuntimeDeletion,
    NoActiveModel,
    PendingActiveModel,
    ActiveModelNotInstalled,
    ActiveModelIdentityMismatch,
    ActiveModelProfileMissing,
    ActiveModelContractMismatch,
    ActiveModelContractInvalid,
    ActiveModelDeviceUnsupported,
}

internal enum class LocalSeparationDegradationCode {
    RuntimeActivationPending,
    RuntimeDeletionPending,
    GpuRuntimeMissing,
    GpuRuntimeActivationPending,
    GpuRuntimeDeletionPending,
    GpuInventoryUnavailable,
}

internal enum class LocalSeparationRepairCandidateKind {
    InstallCpuRuntime,
    RepairCpuRuntime,
    ActivatePendingCpuRuntime,
    InstallGpuRuntime,
    RepairGpuRuntime,
    ActivatePendingGpuRuntime,
    ConfigureGpuRuntime,
    InstallActiveModel,
    InstallRecommendedModel,
    OpenRuntimeManagement,
    OpenModelManagement,
}

internal data class LocalSeparationIssue(
    val code: Enum<*>,
    val detail: String,
)

internal data class LocalSeparationRepairCandidate(
    val kind: LocalSeparationRepairCandidateKind,
    val reason: String,
    val required: Boolean,
    val componentId: String? = null,
    val modelId: String? = null,
)

internal data class LocalSeparationRuntimeSnapshot(
    val componentId: String,
    val abi: String,
    val androidMinApi: Int,
    val runtimeArtifactVersion: String,
    val producerReleaseVersion: String,
    val downloadBytes: Long,
    val installedBytes: Long,
    val state: SourceSeparationRuntimeState,
    val reason: String?,
    val delivery: SourceSeparationDeliveryReference,
    val isRunnable: Boolean,
)

internal data class LocalSeparationGpuRuntimeSnapshot(
    val componentId: String,
    val abi: String,
    val androidMinApi: Int,
    val runtimeArtifactVersion: String,
    val producerReleaseVersion: String,
    val downloadBytes: Long,
    val installedBytes: Long,
    val state: SourceSeparationGpuRuntimeState,
    val reason: String?,
    val delivery: SourceSeparationDeliveryReference,
    val isRunnable: Boolean,
    val maturity: String,
    val profileId: String,
)

internal data class LocalSeparationModelSnapshot(
    val modelId: String,
    val displayName: String,
    val artifactSha256: String,
    val contractSchemaVersion: Int,
    val byteSize: Long,
    val releaseTag: String?,
    val installed: Boolean,
    val active: Boolean,
    val official: Boolean,
)

internal data class LocalSeparationReadiness(
    val schemaVersion: Int,
    val fingerprint: String,
    val state: LocalSeparationReadinessState,
    val platform: MdxRuntimePlatform?,
    val catalogRevision: String,
    val cpuRuntime: LocalSeparationRuntimeSnapshot?,
    val activeModel: LocalSeparationModelSnapshot?,
    val recommendedModel: LocalSeparationModelSnapshot?,
    val activeModelReference: SourceSeparationActiveModelReference?,
    val runnablePaths: List<LocalSeparationRunnablePath>,
    val blockers: List<LocalSeparationIssue>,
    val degradations: List<LocalSeparationIssue>,
    val repairCandidates: List<LocalSeparationRepairCandidate>,
    val processGeneration: Long? = null,
    val gpuRuntime: LocalSeparationGpuRuntimeSnapshot? = null,
    val gpuEnabled: Boolean = true,
) {
    val isRunnable: Boolean
        get() = runnablePaths.isNotEmpty()
}

internal data class LocalSeparationRunnablePath(
    val backend: String,
    val modelId: String,
    val runtimeComponentId: String,
    val profileId: String,
)

internal class LocalSeparationReadinessEvaluator(
    private val runtimeStore: SourceSeparationRuntimeStore,
    private val gpuRuntimeStore: SourceSeparationGpuRuntimeStore,
    private val presetRepository: SourceSeparationPresetRepository,
    private val preferences: SharedPreferences,
    private val platformProvider: () -> MdxRuntimePlatform = {
        AndroidMdxRuntimePlatformProvider.current()
    },
) {
    fun evaluate(verifyPayloadHashes: Boolean = true): LocalSeparationReadiness {
        val platformResult = runCatching { platformProvider() }
        val platform = platformResult.getOrNull()
        val catalog = presetRepository.catalogSnapshot()
        val catalogRevision = buildCatalogRevision(catalog.catalogId, catalog.catalogSchemaVersion)
        val runtimeInventoryResult = runCatching {
            if (verifyPayloadHashes) runtimeStore.inventory() else runtimeStore.trustedInventory()
        }
        val runtimeInventory = runtimeInventoryResult.getOrNull().orEmpty()
        val runtimeItem = platform?.let { current ->
            runtimeInventory.singleOrNull { it.catalogEntry.abi == current.runtimeAbi.androidName }
        }
        val runtimeSnapshot = runtimeItem?.toSnapshot()
        val gpuInventoryResult = runCatching {
            if (verifyPayloadHashes) gpuRuntimeStore.inventory() else gpuRuntimeStore.trustedInventory()
        }
        val gpuInventory = gpuInventoryResult.getOrNull().orEmpty()
        val gpuItem = platform?.let { current ->
            gpuInventory.singleOrNull { it.catalogEntry.abi == current.runtimeAbi.androidName }
        }
        val gpuSnapshot = gpuItem?.toSnapshot()
        val gpuEnabled = preferences.readSourceSeparationGpuEnabled()
        val activeState = presetRepository.activeModel()
        val activeReference = when (activeState) {
            is SourceSeparationActivePresetState.Reference -> activeState.reference
            SourceSeparationActivePresetState.None -> presetRepository.pendingActiveModel()
        }
        val modelResolution = runCatching {
            if (verifyPayloadHashes) {
                presetRepository.resolveActiveCacheModelResolution()
            } else {
                presetRepository.resolveTrustedActiveCacheModelResolution()
            }
        }.getOrNull()
        val activeModel = activeState.installedModelSnapshot(
            reference = activeReference,
            repository = presetRepository,
        )
        val recommendedModel = recommendedModelSnapshot(catalog, presetRepository, activeReference)
        val blockers = mutableListOf<LocalSeparationIssue>()
        val degradations = mutableListOf<LocalSeparationIssue>()
        val candidates = mutableListOf<LocalSeparationRepairCandidate>()

        if (platformResult.isFailure) {
            blockers += LocalSeparationIssue(
                LocalSeparationBlockerCode.UnsupportedProcessAbi,
                platformResult.exceptionOrNull()?.message ?: "The process ABI is unsupported.",
            )
            candidates += LocalSeparationRepairCandidate(
                kind = LocalSeparationRepairCandidateKind.OpenRuntimeManagement,
                reason = "Inspect runtime support for this process ABI.",
                required = true,
            )
        } else if (runtimeInventoryResult.isFailure) {
            blockers += LocalSeparationIssue(
                LocalSeparationBlockerCode.RuntimeInventoryUnavailable,
                runtimeInventoryResult.exceptionOrNull()?.message
                    ?: "The LiteRT runtime inventory could not be read.",
            )
            candidates += LocalSeparationRepairCandidate(
                kind = LocalSeparationRepairCandidateKind.OpenRuntimeManagement,
                reason = "Inspect and repair the LiteRT runtime inventory.",
                required = true,
            )
        } else if (runtimeItem == null) {
            blockers += LocalSeparationIssue(
                LocalSeparationBlockerCode.UnsupportedProcessAbi,
                "No CPU runtime catalog entry exists for the current process ABI.",
            )
            candidates += LocalSeparationRepairCandidate(
                kind = LocalSeparationRepairCandidateKind.OpenRuntimeManagement,
                reason = "Inspect runtime support for this process ABI.",
                required = true,
            )
        } else if (runtimeItem.catalogEntry.androidMinApi > requireNotNull(platform).androidApi) {
            blockers += LocalSeparationIssue(
                LocalSeparationBlockerCode.UnsupportedRuntimeApi,
                "The available CPU runtime requires Android API " +
                    "${runtimeItem.catalogEntry.androidMinApi}.",
            )
            candidates += LocalSeparationRepairCandidate(
                kind = LocalSeparationRepairCandidateKind.OpenRuntimeManagement,
                reason = "The current Android API is below the runtime minimum.",
                required = true,
                componentId = runtimeItem.catalogEntry.componentId,
            )
        } else {
            when (runtimeItem.state) {
                SourceSeparationRuntimeState.Missing -> {
                    blockers += LocalSeparationIssue(
                        LocalSeparationBlockerCode.MissingCpuRuntime,
                        "The CPU LiteRT runtime is not installed.",
                    )
                    candidates += LocalSeparationRepairCandidate(
                        kind = LocalSeparationRepairCandidateKind.InstallCpuRuntime,
                        reason = "Install the CPU runtime required for local separation.",
                        required = true,
                        componentId = runtimeItem.catalogEntry.componentId,
                    )
                }

                SourceSeparationRuntimeState.Invalid -> {
                    blockers += LocalSeparationIssue(
                        LocalSeparationBlockerCode.InvalidCpuRuntime,
                        runtimeItem.reason ?: "The installed CPU runtime failed validation.",
                    )
                    candidates += LocalSeparationRepairCandidate(
                        kind = LocalSeparationRepairCandidateKind.RepairCpuRuntime,
                        reason = "Repair the invalid CPU runtime.",
                        required = true,
                        componentId = runtimeItem.catalogEntry.componentId,
                    )
                }

                SourceSeparationRuntimeState.PendingActivation -> {
                    if (runtimeItem.installation == null) {
                        blockers += LocalSeparationIssue(
                            LocalSeparationBlockerCode.PendingCpuRuntimeActivation,
                            runtimeItem.reason ?: "A CPU runtime update is waiting to activate.",
                        )
                        candidates += LocalSeparationRepairCandidate(
                            kind = LocalSeparationRepairCandidateKind.ActivatePendingCpuRuntime,
                            reason = "Activate the pending CPU runtime before separating audio.",
                            required = true,
                            componentId = runtimeItem.catalogEntry.componentId,
                        )
                    } else {
                        degradations += LocalSeparationIssue(
                            LocalSeparationDegradationCode.RuntimeActivationPending,
                            runtimeItem.reason ?: "A newer CPU runtime is waiting to activate.",
                        )
                    }
                }

                SourceSeparationRuntimeState.PendingDeletion -> {
                    if (runtimeItem.installation == null) {
                        blockers += LocalSeparationIssue(
                            LocalSeparationBlockerCode.PendingCpuRuntimeDeletion,
                            runtimeItem.reason ?: "The CPU runtime is waiting to be removed.",
                        )
                    } else {
                        degradations += LocalSeparationIssue(
                            LocalSeparationDegradationCode.RuntimeDeletionPending,
                            runtimeItem.reason ?: "The CPU runtime is waiting to be removed.",
                        )
                    }
                }

                SourceSeparationRuntimeState.Installed -> Unit
            }
        }

        if (gpuInventoryResult.isFailure) {
            degradations += LocalSeparationIssue(
                LocalSeparationDegradationCode.GpuInventoryUnavailable,
                gpuInventoryResult.exceptionOrNull()?.message
                    ?: "The optional GPU runtime inventory could not be read.",
            )
        } else if (platform != null && gpuItem != null) {
            when (gpuItem.state) {
                SourceSeparationGpuRuntimeState.Missing -> {
                    if (gpuEnabled) {
                        degradations += LocalSeparationIssue(
                            LocalSeparationDegradationCode.GpuRuntimeMissing,
                            "The recommended bounded GPU runtime is not installed.",
                        )
                    }
                    candidates += LocalSeparationRepairCandidate(
                        kind = LocalSeparationRepairCandidateKind.InstallGpuRuntime,
                        reason = "Install the release-qualified bounded GPU component.",
                        required = false,
                        componentId = gpuItem.catalogEntry.componentId,
                    )
                }

                SourceSeparationGpuRuntimeState.Invalid -> {
                    if (gpuEnabled) {
                        degradations += LocalSeparationIssue(
                            LocalSeparationDegradationCode.GpuRuntimeMissing,
                            gpuItem.reason ?: "The bounded GPU runtime failed validation.",
                        )
                    }
                    candidates += LocalSeparationRepairCandidate(
                        kind = LocalSeparationRepairCandidateKind.RepairGpuRuntime,
                        reason = "Repair the invalid bounded GPU component.",
                        required = false,
                        componentId = gpuItem.catalogEntry.componentId,
                    )
                }

                SourceSeparationGpuRuntimeState.PendingActivation -> {
                    if (gpuItem.installation == null) {
                        if (gpuEnabled) {
                            degradations += LocalSeparationIssue(
                                LocalSeparationDegradationCode.GpuRuntimeActivationPending,
                                gpuItem.reason ?: "A GPU runtime update is waiting to activate.",
                            )
                        }
                        candidates += LocalSeparationRepairCandidate(
                            kind = LocalSeparationRepairCandidateKind.ActivatePendingGpuRuntime,
                            reason = "Activate the pending bounded GPU runtime.",
                            required = false,
                            componentId = gpuItem.catalogEntry.componentId,
                        )
                    } else if (gpuEnabled) {
                        degradations += LocalSeparationIssue(
                            LocalSeparationDegradationCode.GpuRuntimeActivationPending,
                            gpuItem.reason ?: "A newer GPU runtime is waiting to activate.",
                        )
                    }
                }

                SourceSeparationGpuRuntimeState.PendingDeletion -> {
                    if (gpuEnabled) {
                        degradations += LocalSeparationIssue(
                            LocalSeparationDegradationCode.GpuRuntimeDeletionPending,
                            gpuItem.reason ?: "The GPU runtime is waiting to be removed.",
                        )
                    }
                }

                SourceSeparationGpuRuntimeState.Installed -> Unit
            }
            if (gpuItem.state == SourceSeparationGpuRuntimeState.Installed &&
                !gpuEnabled &&
                gpuItem.catalogEntry.maturity == "recommended"
            ) {
                candidates += LocalSeparationRepairCandidate(
                    kind = LocalSeparationRepairCandidateKind.ConfigureGpuRuntime,
                    reason = "Enable the release-recommended bounded GPU path.",
                    required = false,
                    componentId = gpuItem.catalogEntry.componentId,
                )
            }
        }

        when (modelResolution) {
            is SourceSeparationActiveCacheModelResolution.Ready -> {
                val compatibility = platform?.let { current ->
                    MdxLiteRtCompatibilityResolver.resolve(
                        profile = modelResolution.model.executionProfile,
                        backend = MdxInferenceBackend.LiteRtCpu,
                        platform = current,
                        policy = MdxCompatibilityPolicy.AllowCandidates,
                    )
                }
                if (compatibility != null && !compatibility.isAllowed) {
                    blockers += LocalSeparationIssue(
                        LocalSeparationBlockerCode.ActiveModelDeviceUnsupported,
                        compatibility.reason,
                    )
                }
            }

            is SourceSeparationActiveCacheModelResolution.Unavailable -> {
                val issue = modelResolution.reason.toIssue()
                blockers += issue
                when (modelResolution.reason) {
                    SourceSeparationActiveCacheModelUnavailableReason.NoSelection,
                    SourceSeparationActiveCacheModelUnavailableReason.PendingSelection,
                    SourceSeparationActiveCacheModelUnavailableReason.ModelNotInstalled,
                    SourceSeparationActiveCacheModelUnavailableReason.ModelIdentityMismatch,
                    SourceSeparationActiveCacheModelUnavailableReason.ProfileNotInstalled,
                    SourceSeparationActiveCacheModelUnavailableReason.ContractMismatch,
                    SourceSeparationActiveCacheModelUnavailableReason.ContractInvalid,
                    -> {
                        val activeCandidate = activeModel?.takeIf { it.official }
                        if (activeCandidate != null) {
                            candidates += LocalSeparationRepairCandidate(
                                kind = LocalSeparationRepairCandidateKind.InstallActiveModel,
                                reason = "Restore the selected model without changing model selection.",
                                required = true,
                                modelId = activeCandidate.modelId,
                            )
                        } else if (activeReference != null) {
                            candidates += LocalSeparationRepairCandidate(
                                kind = LocalSeparationRepairCandidateKind.OpenModelManagement,
                                reason = "The selected model must be installed or profiled again.",
                                required = true,
                            )
                        }
                    }
                }
            }

            null -> blockers += LocalSeparationIssue(
                LocalSeparationBlockerCode.ActiveModelContractInvalid,
                "The active model resolution failed.",
            )
        }

        val hasValidModel = modelResolution is SourceSeparationActiveCacheModelResolution.Ready &&
            blockers.none { it.code == LocalSeparationBlockerCode.ActiveModelDeviceUnsupported }
        val hasRunnableCpu = runtimeSnapshot?.isRunnable == true && hasValidModel
        if (!hasValidModel && recommendedModel != null && activeReference == null) {
            candidates += LocalSeparationRepairCandidate(
                kind = LocalSeparationRepairCandidateKind.InstallRecommendedModel,
                reason = "Install the release-recommended model for the first local separation path.",
                required = true,
                modelId = recommendedModel.modelId,
            )
        }
        if (!hasValidModel && activeReference == null) {
            blockers += LocalSeparationIssue(
                LocalSeparationBlockerCode.NoActiveModel,
                "No active model has been selected.",
            )
        }
        val state = when {
            platform == null ||
                (runtimeInventoryResult.isSuccess && runtimeItem == null) ||
                blockers.any {
                    it.code == LocalSeparationBlockerCode.UnsupportedRuntimeApi ||
                        it.code == LocalSeparationBlockerCode.ActiveModelDeviceUnsupported
                } ->
                LocalSeparationReadinessState.Unsupported
            runtimeInventoryResult.isFailure -> LocalSeparationReadinessState.RepairRequired
            hasRunnableCpu && degradations.isNotEmpty() -> LocalSeparationReadinessState.Degraded
            hasRunnableCpu -> LocalSeparationReadinessState.Ready
            blockers.any { it.code == LocalSeparationBlockerCode.InvalidCpuRuntime ||
                it.code == LocalSeparationBlockerCode.ActiveModelIdentityMismatch ||
                it.code == LocalSeparationBlockerCode.ActiveModelContractMismatch ||
                it.code == LocalSeparationBlockerCode.ActiveModelContractInvalid
            } -> LocalSeparationReadinessState.RepairRequired
            else -> LocalSeparationReadinessState.NeedsSetup
        }
        val runnablePaths = if (hasRunnableCpu) {
            val currentRuntime = requireNotNull(runtimeSnapshot)
            val currentModel = requireNotNull(activeModel)
            listOf(
                LocalSeparationRunnablePath(
                    backend = MdxInferenceBackend.LiteRtCpu.name,
                    modelId = currentModel.modelId,
                    runtimeComponentId = currentRuntime.componentId,
                    profileId = "cpu-default-fp32-v1",
                ),
            )
        } else {
            emptyList()
        }
        return LocalSeparationReadiness(
            schemaVersion = LocalSeparationReadinessContract.SCHEMA_VERSION,
            fingerprint = fingerprint(
                platform = platform,
                catalogRevision = catalogRevision,
                runtime = runtimeSnapshot,
                gpuRuntime = gpuSnapshot,
                gpuEnabled = gpuEnabled,
                activeReference = activeReference,
                activeModel = activeModel,
                state = state,
            ),
            state = state,
            platform = platform,
            catalogRevision = catalogRevision,
            cpuRuntime = runtimeSnapshot,
            gpuRuntime = gpuSnapshot,
            gpuEnabled = gpuEnabled,
            activeModel = activeModel,
            recommendedModel = recommendedModel,
            activeModelReference = activeReference,
            runnablePaths = runnablePaths,
            blockers = blockers.distinctBy { it.code },
            degradations = degradations.distinctBy { it.code },
            repairCandidates = candidates.distinctBy {
                Triple(it.kind, it.componentId, it.modelId)
            },
        )
    }

    private fun recommendedModelSnapshot(
        catalog: SourceSeparationModelCatalog,
        repository: SourceSeparationPresetRepository,
        activeReference: SourceSeparationActiveModelReference?,
    ): LocalSeparationModelSnapshot? {
        val entry = catalog.entries.singleOrNull(CatalogEntry::isDefault) ?: return null
        val preset = runCatching { repository.officialPreset(entry.modelId) }.getOrNull() ?: return null
        return LocalSeparationModelSnapshot(
            modelId = preset.modelId,
            displayName = preset.displayName,
            artifactSha256 = preset.sha256,
            contractSchemaVersion = catalog.contracts
                .single { it.contractId == entry.contractId }
                .contractSchemaVersion,
            byteSize = preset.byteSize,
            releaseTag = preset.releaseTag,
            installed = repository.installedModel(preset.sha256) != null,
            active = activeReference?.artifactSha256.equals(preset.sha256, ignoreCase = true),
            official = true,
        )
    }

    private fun SourceSeparationRuntimeInventoryItem.toSnapshot() =
        LocalSeparationRuntimeSnapshot(
            componentId = catalogEntry.componentId,
            abi = catalogEntry.abi,
            androidMinApi = catalogEntry.androidMinApi,
            runtimeArtifactVersion = catalogEntry.runtimeArtifactVersion,
            producerReleaseVersion = catalogEntry.producerReleaseVersion,
            downloadBytes = catalogEntry.delivery.expectedByteSize,
            installedBytes = installedBytes.takeIf { it > 0L }
                ?: catalogEntry.innerLibrary.byteSize,
            state = state,
            reason = reason,
            delivery = catalogEntry.deliveryReference(),
            isRunnable = installation != null && state != SourceSeparationRuntimeState.Invalid,
        )

    private fun SourceSeparationGpuRuntimeInventoryItem.toSnapshot() =
        LocalSeparationGpuRuntimeSnapshot(
            componentId = catalogEntry.componentId,
            abi = catalogEntry.abi,
            androidMinApi = catalogEntry.androidMinApi,
            runtimeArtifactVersion = catalogEntry.runtimeArtifactVersion,
            producerReleaseVersion = catalogEntry.producerReleaseVersion,
            downloadBytes = catalogEntry.delivery.expectedByteSize,
            installedBytes = installedBytes.takeIf { it > 0L }
                ?: catalogEntry.files.sumOf { it.byteSize },
            state = state,
            reason = reason,
            delivery = catalogEntry.deliveryReference(),
            isRunnable = installation != null && state == SourceSeparationGpuRuntimeState.Installed,
            maturity = catalogEntry.maturity,
            profileId = catalogEntry.capability.profileId,
        )

    private fun SourceSeparationActivePresetState.installedModelSnapshot(
        reference: SourceSeparationActiveModelReference?,
        repository: SourceSeparationPresetRepository,
    ): LocalSeparationModelSnapshot? {
        val installed = when (this) {
            is SourceSeparationActivePresetState.Reference ->
                installedModel
            SourceSeparationActivePresetState.None -> null
        }
        if (installed == null) {
            val official = reference?.let { candidate ->
                runCatching { repository.officialPreset(candidate.modelId) }
                    .getOrNull()
                    ?.takeIf { it.sha256.equals(candidate.artifactSha256, ignoreCase = true) }
            }
            return reference?.let {
                LocalSeparationModelSnapshot(
                    modelId = it.modelId,
                    displayName = official?.displayName ?: it.modelId,
                    artifactSha256 = it.artifactSha256,
                    contractSchemaVersion = it.contractSchemaVersion,
                    byteSize = official?.byteSize ?: 0L,
                    releaseTag = official?.releaseTag,
                    installed = false,
                    active = true,
                    official = official != null,
                )
            }
        }
        return LocalSeparationModelSnapshot(
            modelId = installed.modelId,
            displayName = installed.displayName,
            artifactSha256 = installed.sha256,
            contractSchemaVersion = reference?.contractSchemaVersion ?: 0,
            byteSize = installed.byteSize,
            releaseTag = null,
            installed = true,
            active = true,
            official = installed.bindingKind == SourceSeparationPresetBindingKind.Official,
        )
    }

    private fun SourceSeparationActiveCacheModelUnavailableReason.toIssue() =
        LocalSeparationIssue(
            code = when (this) {
                SourceSeparationActiveCacheModelUnavailableReason.NoSelection ->
                    LocalSeparationBlockerCode.NoActiveModel
                SourceSeparationActiveCacheModelUnavailableReason.PendingSelection ->
                    LocalSeparationBlockerCode.PendingActiveModel
                SourceSeparationActiveCacheModelUnavailableReason.ModelNotInstalled ->
                    LocalSeparationBlockerCode.ActiveModelNotInstalled
                SourceSeparationActiveCacheModelUnavailableReason.ModelIdentityMismatch ->
                    LocalSeparationBlockerCode.ActiveModelIdentityMismatch
                SourceSeparationActiveCacheModelUnavailableReason.ProfileNotInstalled ->
                    LocalSeparationBlockerCode.ActiveModelProfileMissing
                SourceSeparationActiveCacheModelUnavailableReason.ContractMismatch ->
                    LocalSeparationBlockerCode.ActiveModelContractMismatch
                SourceSeparationActiveCacheModelUnavailableReason.ContractInvalid ->
                    LocalSeparationBlockerCode.ActiveModelContractInvalid
            },
            detail = when (this) {
                SourceSeparationActiveCacheModelUnavailableReason.NoSelection ->
                    "No active model selection exists."
                SourceSeparationActiveCacheModelUnavailableReason.PendingSelection ->
                    "An active model selection is waiting to be restored."
                SourceSeparationActiveCacheModelUnavailableReason.ModelNotInstalled ->
                    "The selected model is not installed."
                SourceSeparationActiveCacheModelUnavailableReason.ModelIdentityMismatch ->
                    "The selected model file does not match its identity."
                SourceSeparationActiveCacheModelUnavailableReason.ProfileNotInstalled ->
                    "The selected model profile is not installed."
                SourceSeparationActiveCacheModelUnavailableReason.ContractMismatch ->
                    "The selected model contract does not match its identity."
                SourceSeparationActiveCacheModelUnavailableReason.ContractInvalid ->
                    "The selected model contract is invalid."
            },
        )

    private fun buildCatalogRevision(catalogId: String, schemaVersion: Int): String =
        "$catalogId:$schemaVersion"

    private fun fingerprint(
        platform: MdxRuntimePlatform?,
        catalogRevision: String,
        runtime: LocalSeparationRuntimeSnapshot?,
        gpuRuntime: LocalSeparationGpuRuntimeSnapshot?,
        gpuEnabled: Boolean,
        activeReference: SourceSeparationActiveModelReference?,
        activeModel: LocalSeparationModelSnapshot?,
        state: LocalSeparationReadinessState,
    ): String {
        val canonical = buildString {
            append(LocalSeparationReadinessContract.SCHEMA_VERSION)
            append('|').append(catalogRevision)
            append('|').append(platform?.androidApi ?: 0)
            append('|').append(platform?.runtimeAbi?.androidName.orEmpty())
            append('|').append(runtime?.componentId.orEmpty())
            append('|').append(runtime?.state?.name.orEmpty())
            append('|').append(runtime?.runtimeArtifactVersion.orEmpty())
            append('|').append(runtime?.producerReleaseVersion.orEmpty())
            append('|').append(runtime?.reason.orEmpty())
            append('|').append(gpuRuntime?.componentId.orEmpty())
            append('|').append(gpuRuntime?.state?.name.orEmpty())
            append('|').append(gpuRuntime?.runtimeArtifactVersion.orEmpty())
            append('|').append(gpuRuntime?.producerReleaseVersion.orEmpty())
            append('|').append(gpuRuntime?.reason.orEmpty())
            append('|').append(gpuEnabled)
            append('|').append(activeReference?.modelId.orEmpty())
            append('|').append(activeReference?.artifactSha256.orEmpty())
            append('|').append(activeReference?.contractSchemaVersion ?: 0)
            append('|').append(activeReference?.profileId.orEmpty())
            append('|').append(activeModel?.artifactSha256.orEmpty())
            append('|').append(activeModel?.byteSize ?: 0L)
            append('|').append(state.name)
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}

internal enum class SourceSeparationQuickSetupMode {
    BootstrapRecommended,
    RepairCurrent,
    RestoreRecommended,
}

internal enum class SourceSeparationQuickSetupAction {
    InstallRuntime,
    RepairRuntime,
    ActivatePendingRuntime,
    InstallGpuRuntime,
    RepairGpuRuntime,
    ActivatePendingGpuRuntime,
    ConfigureGpuRuntime,
    InstallAndSelectModel,
    Validate,
    Configure,
    RecycleProcess,
    OpenRuntimeManagement,
    OpenModelManagement,
}

internal enum class SourceSeparationQuickSetupRequirement {
    Required,
    Recommended,
    Optional,
}

internal enum class SourceSeparationQuickSetupItemState {
    Pending,
    Running,
    Succeeded,
    Failed,
    Skipped,
}

internal data class SourceSeparationQuickSetupPlanItem(
    val itemId: String,
    val action: SourceSeparationQuickSetupAction,
    val requirement: SourceSeparationQuickSetupRequirement,
    val selected: Boolean,
    val title: String,
    val reason: String,
    val disabledReason: String? = null,
    val dependencyIds: List<String> = emptyList(),
    val componentId: String? = null,
    val modelId: String? = null,
    val expectedDownloadBytes: Long = 0L,
    val expectedInstalledBytes: Long = 0L,
    val state: SourceSeparationQuickSetupItemState = SourceSeparationQuickSetupItemState.Pending,
)

internal data class SourceSeparationQuickSetupPlan(
    val schemaVersion: Int,
    val planId: String,
    val mode: SourceSeparationQuickSetupMode,
    val inputReadinessFingerprint: String,
    val catalogRevision: String,
    val items: List<SourceSeparationQuickSetupPlanItem>,
    val proposedActiveModel: SourceSeparationActiveModelReference?,
    val proposedGpuEnabled: Boolean? = null,
    val proposedNpuEnabled: Boolean? = null,
) {
    val requiredItems: List<SourceSeparationQuickSetupPlanItem>
        get() = items.filter { it.requirement == SourceSeparationQuickSetupRequirement.Required }

    val selectedItems: List<SourceSeparationQuickSetupPlanItem>
        get() = items.filter { it.selected }
}

internal object SourceSeparationQuickSetupPlanner {
    fun create(
        readiness: LocalSeparationReadiness,
        mode: SourceSeparationQuickSetupMode,
    ): SourceSeparationQuickSetupPlan {
        val items = mutableListOf<SourceSeparationQuickSetupPlanItem>()
        val runtime = readiness.cpuRuntime
        val runtimeCandidate = readiness.repairCandidates.firstOrNull { candidate ->
            candidate.kind in setOf(
                LocalSeparationRepairCandidateKind.InstallCpuRuntime,
                LocalSeparationRepairCandidateKind.RepairCpuRuntime,
                LocalSeparationRepairCandidateKind.ActivatePendingCpuRuntime,
            )
        }
        if (runtimeCandidate != null && runtime != null) {
            val action = when (runtimeCandidate.kind) {
                LocalSeparationRepairCandidateKind.InstallCpuRuntime ->
                    SourceSeparationQuickSetupAction.InstallRuntime
                LocalSeparationRepairCandidateKind.RepairCpuRuntime ->
                    SourceSeparationQuickSetupAction.RepairRuntime
                LocalSeparationRepairCandidateKind.ActivatePendingCpuRuntime ->
                    SourceSeparationQuickSetupAction.ActivatePendingRuntime
                else -> error("Unexpected runtime candidate.")
            }
            items += SourceSeparationQuickSetupPlanItem(
                itemId = "runtime:${runtime.componentId}",
                action = action,
                requirement = if (runtimeCandidate.required) {
                    SourceSeparationQuickSetupRequirement.Required
                } else {
                    SourceSeparationQuickSetupRequirement.Recommended
                },
                selected = true,
                title = "LiteRT CPU runtime",
                reason = runtimeCandidate.reason,
                componentId = runtime.componentId,
                expectedDownloadBytes = if (action in setOf(
                        SourceSeparationQuickSetupAction.InstallRuntime,
                        SourceSeparationQuickSetupAction.RepairRuntime,
                    )
                ) {
                    runtime.downloadBytes
                } else {
                    0L
                },
                expectedInstalledBytes = runtime.installedBytes,
            )
        }

        val gpuRuntime = readiness.gpuRuntime
        val gpuCandidate = readiness.repairCandidates.firstOrNull { candidate ->
            candidate.kind in setOf(
                LocalSeparationRepairCandidateKind.InstallGpuRuntime,
                LocalSeparationRepairCandidateKind.RepairGpuRuntime,
                LocalSeparationRepairCandidateKind.ActivatePendingGpuRuntime,
                LocalSeparationRepairCandidateKind.ConfigureGpuRuntime,
            )
        }
        if (gpuCandidate != null && gpuRuntime != null) {
            val action = when (gpuCandidate.kind) {
                LocalSeparationRepairCandidateKind.InstallGpuRuntime ->
                    SourceSeparationQuickSetupAction.InstallGpuRuntime
                LocalSeparationRepairCandidateKind.RepairGpuRuntime ->
                    SourceSeparationQuickSetupAction.RepairGpuRuntime
                LocalSeparationRepairCandidateKind.ActivatePendingGpuRuntime ->
                    SourceSeparationQuickSetupAction.ActivatePendingGpuRuntime
                LocalSeparationRepairCandidateKind.ConfigureGpuRuntime ->
                    SourceSeparationQuickSetupAction.ConfigureGpuRuntime
                else -> error("Unexpected GPU runtime candidate.")
            }
            items += SourceSeparationQuickSetupPlanItem(
                itemId = "gpu:${gpuRuntime.componentId}",
                action = action,
                requirement = SourceSeparationQuickSetupRequirement.Recommended,
                selected = gpuRuntime.maturity == "recommended",
                title = "LiteRT bounded GPU",
                reason = gpuCandidate.reason,
                dependencyIds = items.filter {
                    it.requirement == SourceSeparationQuickSetupRequirement.Required
                }.map(SourceSeparationQuickSetupPlanItem::itemId),
                componentId = gpuRuntime.componentId,
                expectedDownloadBytes = if (action in setOf(
                        SourceSeparationQuickSetupAction.InstallGpuRuntime,
                        SourceSeparationQuickSetupAction.RepairGpuRuntime,
                    )
                ) {
                    gpuRuntime.downloadBytes
                } else {
                    0L
                },
                expectedInstalledBytes = gpuRuntime.installedBytes,
            )
        }

        val targetModel = if (readiness.state == LocalSeparationReadinessState.Unsupported) {
            null
        } else {
            when (mode) {
                SourceSeparationQuickSetupMode.BootstrapRecommended,
                SourceSeparationQuickSetupMode.RestoreRecommended -> readiness.recommendedModel
                SourceSeparationQuickSetupMode.RepairCurrent ->
                    readiness.activeModel?.takeIf { it.official }
                        ?: readiness.recommendedModel?.takeIf { readiness.activeModelReference == null }
            }
        }
        val shouldApplyModel = targetModel != null && (
            !targetModel.installed || !targetModel.active
        )
        val runtimeDependency = items.filter {
            it.requirement == SourceSeparationQuickSetupRequirement.Required
        }.map(SourceSeparationQuickSetupPlanItem::itemId)
        if (shouldApplyModel) {
            val model = requireNotNull(targetModel)
            items += SourceSeparationQuickSetupPlanItem(
                itemId = "model:${model.modelId}",
                action = SourceSeparationQuickSetupAction.InstallAndSelectModel,
                requirement = SourceSeparationQuickSetupRequirement.Required,
                selected = true,
                title = model.displayName,
                reason = if (mode == SourceSeparationQuickSetupMode.RepairCurrent) {
                    "Restore the selected official model."
                } else if (model.installed) {
                    "Use the installed release-recommended model."
                } else {
                    "Install and use the release-recommended model."
                },
                dependencyIds = runtimeDependency,
                modelId = model.modelId,
                expectedDownloadBytes = if (model.installed) 0L else model.byteSize,
                expectedInstalledBytes = model.byteSize,
            )
        }
        val hasUnresolvedRequiredBlocker = readiness.blockers.any { blocker ->
            blocker.code !in setOf(
                LocalSeparationBlockerCode.MissingCpuRuntime,
                LocalSeparationBlockerCode.InvalidCpuRuntime,
                LocalSeparationBlockerCode.PendingCpuRuntimeActivation,
                LocalSeparationBlockerCode.NoActiveModel,
                LocalSeparationBlockerCode.PendingActiveModel,
                LocalSeparationBlockerCode.ActiveModelNotInstalled,
                LocalSeparationBlockerCode.ActiveModelIdentityMismatch,
            )
        }
        if (hasUnresolvedRequiredBlocker && items.isEmpty()) {
            items += SourceSeparationQuickSetupPlanItem(
                itemId = "blocked:management",
                action = if (readiness.state == LocalSeparationReadinessState.Unsupported) {
                    SourceSeparationQuickSetupAction.OpenRuntimeManagement
                } else {
                    SourceSeparationQuickSetupAction.OpenModelManagement
                },
                requirement = SourceSeparationQuickSetupRequirement.Required,
                selected = false,
                title = "Manual resource review",
                reason = readiness.blockers.joinToString(" ") { it.detail },
                disabledReason = "This device cannot be repaired automatically.",
            )
        }
        val proposedModel = when {
            mode == SourceSeparationQuickSetupMode.RestoreRecommended ->
                targetModel?.let(::toReference)
            shouldApplyModel -> toReference(requireNotNull(targetModel))
            else -> readiness.activeModelReference
        }
        val proposedGpuEnabled = if (gpuCandidate != null && gpuRuntime?.maturity == "recommended") {
            true
        } else {
            null
        }
        val planId = hashPlan(
            mode,
            readiness.fingerprint,
            items,
            proposedModel,
            proposedGpuEnabled,
        )
        return SourceSeparationQuickSetupPlan(
            schemaVersion = LocalSeparationReadinessContract.PLAN_SCHEMA_VERSION,
            planId = planId,
            mode = mode,
            inputReadinessFingerprint = readiness.fingerprint,
            catalogRevision = readiness.catalogRevision,
            items = items,
            proposedActiveModel = proposedModel,
            proposedGpuEnabled = proposedGpuEnabled,
        )
    }

    private fun toReference(model: LocalSeparationModelSnapshot) =
        SourceSeparationActiveModelReference(
            modelId = model.modelId,
            artifactSha256 = model.artifactSha256,
            contractSchemaVersion = model.contractSchemaVersion,
        )

    private fun hashPlan(
        mode: SourceSeparationQuickSetupMode,
        fingerprint: String,
        items: List<SourceSeparationQuickSetupPlanItem>,
        proposedModel: SourceSeparationActiveModelReference?,
        proposedGpuEnabled: Boolean?,
    ): String {
        val canonical = buildString {
            append(mode.name).append('|').append(fingerprint)
            items.forEach { item ->
                append('|').append(item.itemId).append(':').append(item.action.name)
                    .append(':').append(item.selected)
            }
            append('|').append(proposedModel?.modelId.orEmpty())
            append('|').append(proposedModel?.artifactSha256.orEmpty())
            append('|').append(proposedGpuEnabled)
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}
