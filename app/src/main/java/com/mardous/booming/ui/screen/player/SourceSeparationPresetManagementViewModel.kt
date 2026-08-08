package com.mardous.booming.ui.screen.player

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mardous.booming.separation.model.MdxRuntimeAbi
import com.mardous.booming.separation.model.AndroidMdxRuntimePlatformProvider
import com.mardous.booming.separation.model.MdxRuntimePlatformProvider
import com.mardous.booming.separation.model.contract.CatalogActivationPolicy
import com.mardous.booming.separation.model.contract.CatalogReleaseMaturity
import com.mardous.booming.separation.model.contract.CatalogSupportLevel
import com.mardous.booming.separation.model.contract.SourceSeparationModelCategory
import com.mardous.booming.separation.model.contract.SourceSeparationModelPresentation
import com.mardous.booming.separation.model.contract.SourceSeparationModelPresentationCatalog
import com.mardous.booming.separation.model.contract.SourceSeparationCustomModelProfile
import com.mardous.booming.separation.model.contract.SourceSeparationModelMetadata
import com.mardous.booming.separation.model.preset.SourceSeparationActivePresetState
import com.mardous.booming.separation.model.preset.SourceSeparationActiveModelReference
import com.mardous.booming.separation.model.preset.SourceSeparationInstalledPreset
import com.mardous.booming.separation.model.preset.SourceSeparationPresetActivationResolver
import com.mardous.booming.separation.model.preset.SourceSeparationPresetBindingKind
import com.mardous.booming.separation.model.preset.SourceSeparationPresetDownloadCanceledException
import com.mardous.booming.separation.model.preset.SourceSeparationPresetDownloader
import com.mardous.booming.separation.model.preset.SourceSeparationPresetDeletionException
import com.mardous.booming.separation.model.preset.SourceSeparationPresetImportCoordinator
import com.mardous.booming.separation.model.preset.SourceSeparationPresetImportOutcome
import com.mardous.booming.separation.model.preset.SourceSeparationPresetRepository
import com.mardous.booming.separation.model.preset.SourceSeparationPresetSelectionBlockReason
import com.mardous.booming.separation.model.preset.SourceSeparationPresetSelectionScope
import com.mardous.booming.separation.model.preset.SourceSeparationPendingModelImport
import com.mardous.booming.separation.model.preset.SourceSeparationManualModelProfileDraft
import com.mardous.booming.separation.model.preset.SourceSeparationManualModelStem
import com.mardous.booming.separation.model.preset.toManualDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import java.util.concurrent.ConcurrentHashMap
import java.util.Locale

class SourceSeparationPresetManagementViewModel internal constructor(
    private val contentResolver: ContentResolver,
    private val repository: SourceSeparationPresetRepository,
    private val downloader: SourceSeparationPresetDownloader,
    private val importCoordinator: SourceSeparationPresetImportCoordinator,
    private val platformProvider: MdxRuntimePlatformProvider = AndroidMdxRuntimePlatformProvider,
    private val modelArtifactInUse: (String) -> Boolean = { false },
) : ViewModel() {
    private val downloadJobs = ConcurrentHashMap<String, Job>()
    private val transferStates = ConcurrentHashMap<String, SourceSeparationPresetTransferState>()
    private var importJob: Job? = null
    private val _state = MutableStateFlow(
        buildState().copy(
            importState = importCoordinator.pending()
                ?.let(SourceSeparationPresetImportUiState::AwaitingMetadata)
                ?: SourceSeparationPresetImportUiState.Idle,
        ),
    )

    val state = _state.asStateFlow()

    fun refresh() {
        publishState()
    }

    fun clearRestoredModelTarget() {
        repository.setPendingActiveModel(null)
        publishState()
    }

    fun download(modelId: String) {
        if (downloadJobs[modelId]?.isActive == true) return
        val operationKey = catalogOperationKey(modelId)
        transferStates[operationKey] = SourceSeparationPresetTransferState.Downloading(
            downloadedBytes = 0L,
            totalBytes = repository.officialPreset(modelId).byteSize,
            usingMirror = false,
        )
        publishState()
        downloadJobs[modelId] = viewModelScope.launch(Dispatchers.IO) {
            try {
                downloader.download(modelId) { progress ->
                    transferStates[operationKey] = SourceSeparationPresetTransferState.Downloading(
                        downloadedBytes = progress.downloadedBytes,
                        totalBytes = progress.totalBytes,
                        usingMirror = progress.usingMirror,
                    )
                    publishState()
                }
                transferStates.remove(operationKey)
            } catch (_: SourceSeparationPresetDownloadCanceledException) {
                transferStates.remove(operationKey)
            } catch (_: CancellationException) {
                downloader.cancel(modelId)
                transferStates.remove(operationKey)
                throw CancellationException()
            } catch (error: Throwable) {
                transferStates[operationKey] = SourceSeparationPresetTransferState.Failed(
                    error.message.orEmpty(),
                )
            } finally {
                downloadJobs.remove(modelId)
                publishState()
            }
        }
    }

    fun cancelDownload(modelId: String) {
        downloader.cancel(modelId)
        downloadJobs.remove(modelId)?.cancel()
        transferStates.remove(catalogOperationKey(modelId))
        publishState()
    }

    fun requestUse(modelId: String) {
        val item = _state.value.entries.singleOrNull { it.modelId == modelId } ?: return
        if (!item.offersUseForValidation ||
            !item.canUseForValidation ||
            item.operationInProgress
        ) return
        if (item.supportLevel == CatalogSupportLevel.Experimental) {
            _state.value = _state.value.copy(confirmationModelId = modelId, errorMessage = null)
            return
        }
        use(modelId, experimentalConfirmed = false)
    }

    fun confirmExperimentalUse() {
        val modelId = _state.value.confirmationModelId ?: return
        use(modelId, experimentalConfirmed = true)
    }

    fun dismissExperimentalUse() {
        _state.value = _state.value.copy(confirmationModelId = null)
    }

    fun delete(modelId: String) {
        val item = _state.value.entries.singleOrNull { it.modelId == modelId } ?: return
        deleteInstalled(item.installed ?: return, item.operationKey)
    }

    private fun use(modelId: String, experimentalConfirmed: Boolean) {
        val item = _state.value.entries.singleOrNull { it.modelId == modelId } ?: return
        useInstalled(
            installed = item.installed ?: return,
            operationKey = item.operationKey,
            experimentalConfirmed = experimentalConfirmed,
        )
    }

    fun requestUseImported(sha256: String) {
        val item = _state.value.importedEntries.singleOrNull { it.sha256 == sha256 } ?: return
        if (!item.canUseForValidation || item.operationInProgress) return
        useInstalled(item.installed, item.operationKey, experimentalConfirmed = false)
    }

    fun deleteImported(sha256: String) {
        val item = _state.value.importedEntries.singleOrNull { it.sha256 == sha256 } ?: return
        deleteInstalled(item.installed, item.operationKey)
    }

    fun showCatalogDetails(modelId: String) {
        _state.value = _state.value.copy(modelDetails = repository.catalogModelDetails(modelId))
    }

    fun showImportedDetails(sha256: String, profileId: String? = null) {
        _state.value = _state.value.copy(
            modelDetails = repository.importedModelDetails(sha256, profileId),
        )
    }

    fun dismissModelDetails() {
        _state.value = _state.value.copy(modelDetails = null)
    }

    fun editCustomProfile(sha256: String, profileId: String) {
        val installed = repository.installedModel(sha256) ?: return
        val profile = repository.customProfiles().singleOrNull { it.profileId == profileId }
            ?: return
        _state.value = _state.value.copy(
            profileEditor = SourceSeparationCustomProfileEditorUiState(
                artifactSha256 = installed.sha256,
                sourceProfileId = profile.profileId,
                artifact = SourceSeparationPendingModelImport(
                    fileName = installed.file.name,
                    byteSize = installed.byteSize,
                    sha256 = installed.sha256,
                ),
                initialDraft = profile.toManualDraft(),
            ),
            errorMessage = null,
        )
    }

    fun saveCustomProfileRevision(draft: SourceSeparationManualModelProfileDraft) {
        val editor = _state.value.profileEditor ?: return
        if (editor.saving) return
        _state.value = _state.value.copy(profileEditor = editor.copy(saving = true))
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val profile = draft.toProfile(editor.artifact)
                repository.saveCustomProfileRevision(
                    artifactSha256 = editor.artifactSha256,
                    profile = profile,
                    platform = platformProvider.current(),
                )
                val previous = _state.value
                _state.value = buildState().copy(
                    confirmationModelId = previous.confirmationModelId,
                    errorMessage = null,
                    importState = previous.importState,
                    modelDetails = repository.importedModelDetails(
                        editor.artifactSha256,
                        profile.profileId,
                    ),
                    profileEditor = null,
                )
            } catch (error: Throwable) {
                _state.value = _state.value.copy(
                    profileEditor = editor.copy(saving = false),
                    errorMessage = error.message.orEmpty(),
                )
            }
        }
    }

    fun cancelCustomProfileEdit() {
        if (_state.value.profileEditor?.saving == true) return
        _state.value = _state.value.copy(profileEditor = null)
    }

    fun useCustomProfile(sha256: String, profileId: String) {
        val operationKey = importOperationKey(sha256)
        if (transferStates[operationKey].isOperationInProgress) return
        transferStates[operationKey] = SourceSeparationPresetTransferState.Activating
        publishState()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.activateCustomProfile(
                    sha256 = sha256,
                    profileId = profileId,
                    platform = platformProvider.current(),
                    scope = SourceSeparationPresetSelectionScope.InternalValidation,
                )
                transferStates.remove(operationKey)
            } catch (error: Throwable) {
                transferStates.remove(operationKey)
                _state.value = _state.value.copy(errorMessage = error.message.orEmpty())
            } finally {
                publishState()
            }
        }
    }

    fun deleteCustomProfile(profileId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.deleteCustomProfile(profileId) }
                .onFailure { error ->
                    _state.value = _state.value.copy(errorMessage = error.message.orEmpty())
                }
            publishState()
        }
    }

    fun exportCustomProfile(profileId: String): SourceSeparationCustomProfileExport? {
        return repository.customProfiles()
            .singleOrNull { it.profileId == profileId }
            ?.toPortableExport()
    }

    fun beginImport(uri: Uri) {
        if (importJob?.isActive == true || _state.value.importState.blocksNewImport) return
        setImportState(SourceSeparationPresetImportUiState.PreparingModel)
        importJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val fileName = documentDisplayName(uri)
                val outcome = contentResolver.openInputStream(uri)
                    ?.let { input -> importCoordinator.begin(input, fileName) }
                    ?: throw IllegalStateException("Could not open the selected TFLite file.")
                publishImportOutcome(outcome)
            } catch (error: Throwable) {
                publishImportFailure(error, SourceSeparationPresetImportFailureStage.Model)
            } finally {
                importJob = null
            }
        }
    }

    fun importSidecar(uri: Uri) {
        val pending = (_state.value.importState as? SourceSeparationPresetImportUiState.AwaitingMetadata)
            ?.pending ?: return
        if (importJob?.isActive == true) return
        setImportState(SourceSeparationPresetImportUiState.PreparingSidecar(pending))
        importJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val sidecarFileName = documentDisplayName(uri)
                val outcome = contentResolver.openInputStream(uri)
                    ?.let { input -> importCoordinator.installWithSidecar(input, sidecarFileName) }
                    ?: throw IllegalStateException("Could not open the selected model sidecar.")
                publishImportOutcome(outcome)
            } catch (error: Throwable) {
                publishImportFailure(error, SourceSeparationPresetImportFailureStage.Sidecar)
            } finally {
                importJob = null
            }
        }
    }

    fun startManualProfile() {
        val pending = (_state.value.importState as? SourceSeparationPresetImportUiState.AwaitingMetadata)
            ?.pending ?: return
        setImportState(
            SourceSeparationPresetImportUiState.EditingManualProfile(
                pending = pending,
                initialDraft = pending.defaultManualProfileDraft(),
            ),
        )
    }

    fun saveManualProfile(draft: SourceSeparationManualModelProfileDraft) {
        val pending = (_state.value.importState as? SourceSeparationPresetImportUiState.EditingManualProfile)
            ?.pending ?: return
        if (importJob?.isActive == true) return
        setImportState(SourceSeparationPresetImportUiState.SavingManualProfile(pending))
        importJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                publishImportOutcome(importCoordinator.installWithManualProfile(draft))
            } catch (error: Throwable) {
                publishImportFailure(
                    error = error,
                    stage = SourceSeparationPresetImportFailureStage.ManualProfile,
                    manualDraft = draft,
                )
            } finally {
                importJob = null
            }
        }
    }

    fun cancelManualProfile() {
        val pending = (_state.value.importState as? SourceSeparationPresetImportUiState.EditingManualProfile)
            ?.pending ?: return
        setImportState(SourceSeparationPresetImportUiState.AwaitingMetadata(pending))
    }

    fun retryImport() {
        val failure = _state.value.importState as? SourceSeparationPresetImportUiState.Failed
            ?: return
        val recovered = when {
            failure.manualDraft != null && failure.pending != null ->
                SourceSeparationPresetImportUiState.EditingManualProfile(
                    failure.pending,
                    failure.manualDraft,
                )
            failure.pending != null -> SourceSeparationPresetImportUiState.AwaitingMetadata(failure.pending)
            else -> SourceSeparationPresetImportUiState.Idle
        }
        setImportState(recovered)
    }

    fun discardImport() {
        if (importJob?.isActive == true) return
        viewModelScope.launch(Dispatchers.IO) {
            importCoordinator.discard()
            setImportState(SourceSeparationPresetImportUiState.Idle)
        }
    }

    fun dismissImportSuccess() {
        if (_state.value.importState is SourceSeparationPresetImportUiState.Success) {
            setImportState(SourceSeparationPresetImportUiState.Idle)
        }
    }

    fun clearError(modelId: String? = null) {
        val operationKey = modelId?.let(::catalogOperationKey)
        if (operationKey != null &&
            transferStates[operationKey] is SourceSeparationPresetTransferState.Failed
        ) {
            transferStates.remove(operationKey)
        }
        val previous = _state.value
        _state.value = buildState().copy(
            confirmationModelId = previous.confirmationModelId,
            errorMessage = null,
            importState = previous.importState,
        )
    }

    private fun publishState() {
        val previous = _state.value
        _state.value = buildState().copy(
            confirmationModelId = previous.confirmationModelId,
            errorMessage = previous.errorMessage,
            importState = previous.importState,
            modelDetails = previous.modelDetails,
            profileEditor = previous.profileEditor,
        )
    }

    private fun buildState(): SourceSeparationPresetManagementUiState {
        val platform = runCatching(platformProvider::current).getOrNull()
        val active = repository.activeModel()
        val activeReference = (active as? SourceSeparationActivePresetState.Reference)?.reference
        val customProfiles = repository.customProfiles()
        val installedByHash = repository.installedModels().associateBy { it.sha256.lowercase() }
        val entries = repository.catalogEntries().map { entry ->
            val officialPreset = runCatching { repository.officialPreset(entry.modelId) }.getOrNull()
            val installed = officialPreset?.sha256?.lowercase()?.let(installedByHash::get)
            val eligibility = platform?.let { currentPlatform ->
                SourceSeparationPresetActivationResolver.resolve(
                    catalog = repository.catalogSnapshot(),
                    modelId = entry.modelId,
                    platform = currentPlatform,
                    scope = SourceSeparationPresetSelectionScope.InternalValidation,
                )
            }
            SourceSeparationPresetManagementItem(
                modelId = entry.modelId,
                displayName = entry.displayName,
                presentation = SourceSeparationModelPresentationCatalog.find(entry.modelId),
                supportLevel = entry.supportLevel,
                activationPolicy = entry.activationPolicy,
                releaseMaturity = entry.releaseMaturity,
                isDefault = entry.isDefault,
                byteSize = officialPreset?.byteSize,
                sha256 = officialPreset?.sha256,
                releaseTag = officialPreset?.releaseTag,
                installed = installed,
                active = activeReference?.modelId == entry.modelId &&
                    activeReference.artifactSha256.equals(officialPreset?.sha256, ignoreCase = true),
                canUseForValidation = installed != null && eligibility?.allowed == true,
                useBlockReason = eligibility?.blockReason,
                transferState = transferStates[catalogOperationKey(entry.modelId)],
                operationKey = catalogOperationKey(entry.modelId),
            )
        }.sortedWith(
            compareBy<SourceSeparationPresetManagementItem> { it.supportLevel.sortOrder }
                .thenByDescending(SourceSeparationPresetManagementItem::isDefault)
                .thenBy(SourceSeparationPresetManagementItem::displayName),
        )
        val importedEntries = installedByHash.values
            .filter { it.bindingKind != SourceSeparationPresetBindingKind.Official }
            .map { installed ->
                val useBlockReason = when {
                    platform == null -> SourceSeparationPresetSelectionBlockReason
                        .CustomModelStructuralInspectionUnavailable
                    installed.bindingKind == SourceSeparationPresetBindingKind.CustomProfile &&
                        platform.runtimeAbi == MdxRuntimeAbi.X86 ->
                        SourceSeparationPresetSelectionBlockReason
                            .CustomModelStructuralInspectionUnavailable
                    else -> null
                }
                SourceSeparationImportedModelManagementItem(
                    modelId = installed.modelId,
                    displayName = installed.displayName,
                    sha256 = installed.sha256,
                    byteSize = installed.byteSize,
                    bindingKind = installed.bindingKind,
                    qualityUnverified = installed.customProfile?.qualityUnverified == true,
                    installed = installed,
                    active = activeReference?.artifactSha256.equals(
                        installed.sha256,
                        ignoreCase = true,
                    ),
                    canUseForValidation = useBlockReason == null,
                    useBlockReason = useBlockReason,
                    transferState = transferStates[importOperationKey(installed.sha256)],
                    operationKey = importOperationKey(installed.sha256),
                    profileRevisions = customProfiles
                        .filter { profile ->
                            profile.artifact.sha256.equals(installed.sha256, ignoreCase = true)
                        }
                        .map { profile ->
                            SourceSeparationCustomProfileRevisionUiState(
                                profileId = profile.profileId,
                                displayName = profile.displayName,
                                modelId = profile.modelId,
                                active = activeReference?.profileId == profile.profileId &&
                                    activeReference.artifactSha256.equals(
                                        installed.sha256,
                                        ignoreCase = true,
                                    ),
                                defaultBinding = installed.customProfile?.profileId ==
                                    profile.profileId,
                            )
                        }
                        .sortedBy(SourceSeparationCustomProfileRevisionUiState::profileId),
                )
            }
            .sortedBy { it.displayName.lowercase(Locale.ROOT) }
        return SourceSeparationPresetManagementUiState(
            entries = entries,
            importedEntries = importedEntries,
            activeReferenceMissing = active is SourceSeparationActivePresetState.Reference &&
                active.installedModel == null,
            restoredModelTarget = resolveRestoredModelTarget(
                reference = repository.pendingActiveModel(),
                entries = entries,
                importedEntries = importedEntries,
                customProfiles = customProfiles,
                activeReference = usableActiveModelReference(active),
            ),
        )
    }

    override fun onCleared() {
        downloadJobs.keys.toList().forEach(downloader::cancel)
        importJob?.let { job ->
            job.cancel()
            job.invokeOnCompletion { importCoordinator.discard() }
        } ?: importCoordinator.discard()
        super.onCleared()
    }

    private fun useInstalled(
        installed: SourceSeparationInstalledPreset,
        operationKey: String,
        experimentalConfirmed: Boolean,
    ) {
        if (transferStates[operationKey].isOperationInProgress) return
        transferStates[operationKey] = SourceSeparationPresetTransferState.Activating
        val previous = _state.value
        _state.value = buildState().copy(
            confirmationModelId = null,
            errorMessage = previous.errorMessage,
            importState = previous.importState,
        )
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                repository.activate(
                    sha256 = installed.sha256,
                    platform = platformProvider.current(),
                    scope = SourceSeparationPresetSelectionScope.InternalValidation,
                    experimentalConfirmed = experimentalConfirmed,
                )
            }.onFailure { error ->
                _state.value = _state.value.copy(errorMessage = error.message)
            }
            transferStates.remove(operationKey)
            publishState()
        }
    }

    private fun deleteInstalled(
        installed: SourceSeparationInstalledPreset,
        operationKey: String,
    ) {
        if (transferStates[operationKey].isOperationInProgress) return
        transferStates[operationKey] = SourceSeparationPresetTransferState.Deleting
        publishState()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                if (modelArtifactInUse(installed.sha256)) {
                    throw SourceSeparationPresetDeletionException(
                        "The model is still used by source-separation work.",
                    )
                }
                repository.delete(installed.sha256)
            }
                .onFailure { error ->
                    _state.value = _state.value.copy(errorMessage = error.message)
                }
            transferStates.remove(operationKey)
            publishState()
        }
    }

    private fun publishImportOutcome(outcome: SourceSeparationPresetImportOutcome) {
        val installed = when (outcome) {
            is SourceSeparationPresetImportOutcome.Installed -> outcome.installed
            is SourceSeparationPresetImportOutcome.AwaitingMetadata -> {
                setImportState(SourceSeparationPresetImportUiState.AwaitingMetadata(outcome.pending))
                return
            }
        }
        setImportState(
            SourceSeparationPresetImportUiState.Success(
                displayName = installed.displayName,
                sha256 = installed.sha256,
            ),
        )
        publishState()
    }

    private fun publishImportFailure(
        error: Throwable,
        stage: SourceSeparationPresetImportFailureStage,
        manualDraft: SourceSeparationManualModelProfileDraft? = null,
    ) {
        if (error is CancellationException) throw error
        setImportState(
            SourceSeparationPresetImportUiState.Failed(
                stage = stage,
                pending = importCoordinator.pending(),
                manualDraft = manualDraft,
            ),
        )
    }

    private fun setImportState(importState: SourceSeparationPresetImportUiState) {
        _state.value = _state.value.copy(importState = importState)
    }

    private fun documentDisplayName(uri: Uri): String {
        return contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) cursor.getString(index) else null
        }?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Could not read the selected file name.")
    }

    private fun SourceSeparationPendingModelImport.defaultManualProfileDraft():
        SourceSeparationManualModelProfileDraft {
        val fileStem = fileName.substringBeforeLast('.', fileName)
        val modelId = fileStem
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifBlank { "custom_model" }
            .let { normalized ->
                if (normalized.first().isDigit()) "model_$normalized" else normalized
            }
        return SourceSeparationManualModelProfileDraft(
            modelId = modelId,
            displayName = fileStem.ifBlank { modelId },
            inputTensorName = "input",
            outputTensorName = "output",
            sampleRate = 44_100,
            nFft = 6_144,
            hopLength = 1_024,
            dimF = 2_048,
            dimTPower = 8,
            modelOutputScale = 1.035,
            modelOutputStem = SourceSeparationManualModelStem.Vocals,
            modelOutputLabel = SourceSeparationManualModelStem.Vocals.defaultCanonicalLabel(),
            residualLabel = SourceSeparationManualModelStem.Instrumental.defaultCanonicalLabel(),
        )
    }

    private fun catalogOperationKey(modelId: String) = "catalog:$modelId"

    private fun importOperationKey(sha256: String) = "import:${sha256.lowercase(Locale.ROOT)}"
}

data class SourceSeparationPresetManagementUiState(
    val entries: List<SourceSeparationPresetManagementItem> = emptyList(),
    val importedEntries: List<SourceSeparationImportedModelManagementItem> = emptyList(),
    val activeReferenceMissing: Boolean = false,
    val restoredModelTarget: SourceSeparationRestoredModelTargetUiState? = null,
    val confirmationModelId: String? = null,
    val errorMessage: String? = null,
    val importState: SourceSeparationPresetImportUiState = SourceSeparationPresetImportUiState.Idle,
    val modelDetails: SourceSeparationModelDetailsUiState? = null,
    val profileEditor: SourceSeparationCustomProfileEditorUiState? = null,
) {
    val confirmationModel: SourceSeparationPresetManagementItem?
        get() = entries.singleOrNull { it.modelId == confirmationModelId }
}

data class SourceSeparationRestoredModelTargetUiState(
    val modelId: String,
    val displayName: String,
    val artifactSha256: String,
    val exactModelInstalled: Boolean,
    val currentModelRetained: Boolean,
) {
    val shortSha256: String
        get() = artifactSha256.take(SHORT_SHA256_LENGTH)

    private companion object {
        const val SHORT_SHA256_LENGTH = 12
    }
}

data class SourceSeparationPresetManagementItem(
    val modelId: String,
    val displayName: String,
    val presentation: SourceSeparationModelPresentation? = null,
    val supportLevel: CatalogSupportLevel,
    val activationPolicy: CatalogActivationPolicy,
    val releaseMaturity: CatalogReleaseMaturity,
    val isDefault: Boolean,
    val byteSize: Long?,
    val sha256: String?,
    val releaseTag: String?,
    val installed: SourceSeparationInstalledPreset?,
    val active: Boolean,
    val canUseForValidation: Boolean,
    val useBlockReason: SourceSeparationPresetSelectionBlockReason?,
    val transferState: SourceSeparationPresetTransferState?,
    val operationKey: String,
) {
    val offersUseForValidation: Boolean
        get() = activationPolicy == CatalogActivationPolicy.SelectableWhenQualified ||
            activationPolicy == CatalogActivationPolicy.SelectableExperimental

    val canDelete: Boolean
        get() = installed != null && !active
}

internal data class SourceSeparationPresetManagementGroup(
    val category: SourceSeparationModelCategory?,
    val entries: List<SourceSeparationPresetManagementItem>,
)

internal fun groupPresetManagementEntries(
    entries: List<SourceSeparationPresetManagementItem>,
): List<SourceSeparationPresetManagementGroup> {
    val groups = SourceSeparationModelCategory.entries.mapNotNull { category ->
        entries.filter { it.presentation?.category == category }
            .takeIf(List<SourceSeparationPresetManagementItem>::isNotEmpty)
            ?.let { SourceSeparationPresetManagementGroup(category, it) }
    }
    val unclassified = entries.filter { it.presentation == null }
    return if (unclassified.isEmpty()) groups else groups +
        SourceSeparationPresetManagementGroup(category = null, entries = unclassified)
}

data class SourceSeparationImportedModelManagementItem(
    val modelId: String,
    val displayName: String,
    val sha256: String,
    val byteSize: Long,
    val bindingKind: SourceSeparationPresetBindingKind,
    val qualityUnverified: Boolean,
    val installed: SourceSeparationInstalledPreset,
    val active: Boolean,
    val canUseForValidation: Boolean,
    val useBlockReason: SourceSeparationPresetSelectionBlockReason?,
    val transferState: SourceSeparationPresetTransferState?,
    val operationKey: String,
    val profileRevisions: List<SourceSeparationCustomProfileRevisionUiState> = emptyList(),
) {
    val canDelete: Boolean
        get() = !active && !operationInProgress
}

data class SourceSeparationCustomProfileRevisionUiState(
    val profileId: String,
    val displayName: String,
    val modelId: String,
    val active: Boolean,
    val defaultBinding: Boolean,
) {
    val canDelete: Boolean
        get() = !active && !defaultBinding
}

data class SourceSeparationCustomProfileEditorUiState(
    val artifactSha256: String,
    val sourceProfileId: String,
    val artifact: SourceSeparationPendingModelImport,
    val initialDraft: SourceSeparationManualModelProfileDraft,
    val saving: Boolean = false,
)

data class SourceSeparationCustomProfileExport(
    val fileName: String,
    val contents: String,
)

internal fun SourceSeparationCustomModelProfile.toPortableExport() =
    SourceSeparationCustomProfileExport(
        fileName = "${artifact.fileName}.${profileId.fileNameDigest()}.profile.json",
        contents = SourceSeparationModelMetadata.json.encodeToString(this),
    )

private fun String.fileNameDigest(): String = java.security.MessageDigest
    .getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8))
    .joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
    .take(12)

sealed interface SourceSeparationPresetImportUiState {
    data object Idle : SourceSeparationPresetImportUiState

    data object PreparingModel : SourceSeparationPresetImportUiState

    data class AwaitingMetadata(
        val pending: SourceSeparationPendingModelImport,
    ) : SourceSeparationPresetImportUiState

    data class PreparingSidecar(
        val pending: SourceSeparationPendingModelImport,
    ) : SourceSeparationPresetImportUiState

    data class EditingManualProfile(
        val pending: SourceSeparationPendingModelImport,
        val initialDraft: SourceSeparationManualModelProfileDraft,
    ) : SourceSeparationPresetImportUiState

    data class SavingManualProfile(
        val pending: SourceSeparationPendingModelImport,
    ) : SourceSeparationPresetImportUiState

    data class Failed(
        val stage: SourceSeparationPresetImportFailureStage,
        val pending: SourceSeparationPendingModelImport?,
        val manualDraft: SourceSeparationManualModelProfileDraft? = null,
    ) : SourceSeparationPresetImportUiState

    data class Success(
        val displayName: String,
        val sha256: String,
    ) : SourceSeparationPresetImportUiState
}

enum class SourceSeparationPresetImportFailureStage {
    Model,
    Sidecar,
    ManualProfile,
}

sealed class SourceSeparationPresetTransferState {
    data class Downloading(
        val downloadedBytes: Long,
        val totalBytes: Long,
        val usingMirror: Boolean,
    ) : SourceSeparationPresetTransferState() {
        val fraction: Float
            get() = if (totalBytes <= 0L) 0f else
                (downloadedBytes.toDouble() / totalBytes.toDouble())
                    .coerceIn(0.0, 1.0)
                    .toFloat()
    }

    data class Failed(val message: String) : SourceSeparationPresetTransferState()

    data object Activating : SourceSeparationPresetTransferState()

    data object Deleting : SourceSeparationPresetTransferState()
}

private val SourceSeparationPresetManagementItem.operationInProgress: Boolean
    get() = transferState is SourceSeparationPresetTransferState.Downloading ||
        transferState == SourceSeparationPresetTransferState.Activating ||
        transferState == SourceSeparationPresetTransferState.Deleting

private val SourceSeparationPresetTransferState?.isOperationInProgress: Boolean
    get() = this is SourceSeparationPresetTransferState.Downloading ||
        this == SourceSeparationPresetTransferState.Activating ||
        this == SourceSeparationPresetTransferState.Deleting

private val SourceSeparationImportedModelManagementItem.operationInProgress: Boolean
    get() = transferState is SourceSeparationPresetTransferState.Activating ||
        transferState == SourceSeparationPresetTransferState.Deleting

private val SourceSeparationPresetImportUiState.blocksNewImport: Boolean
    get() = when (this) {
        SourceSeparationPresetImportUiState.Idle,
        is SourceSeparationPresetImportUiState.Success,
        is SourceSeparationPresetImportUiState.Failed -> false
        else -> true
    }

private val CatalogSupportLevel.sortOrder: Int
    get() = when (this) {
        CatalogSupportLevel.Recommended -> 0
        CatalogSupportLevel.Experimental -> 1
        CatalogSupportLevel.DownloadOnly -> 2
    }

internal fun resolveRestoredModelTarget(
    reference: SourceSeparationActiveModelReference?,
    entries: List<SourceSeparationPresetManagementItem>,
    importedEntries: List<SourceSeparationImportedModelManagementItem>,
    customProfiles: List<SourceSeparationCustomModelProfile> = emptyList(),
    activeReference: SourceSeparationActiveModelReference?,
): SourceSeparationRestoredModelTargetUiState? {
    reference ?: return null
    val official = entries.singleOrNull { entry -> entry.modelId == reference.modelId }
    val officialArtifactInstalled = official?.takeIf { entry ->
        entry.sha256.equals(reference.artifactSha256, ignoreCase = true)
    }?.installed != null
    val imported = importedEntries.singleOrNull { entry ->
        entry.modelId == reference.modelId &&
            entry.sha256.equals(reference.artifactSha256, ignoreCase = true)
    }
    val customProfile = customProfiles.singleOrNull { profile ->
        profile.profileId == reference.profileId &&
            profile.modelId == reference.modelId &&
            profile.artifact.sha256.equals(reference.artifactSha256, ignoreCase = true)
    }
    return SourceSeparationRestoredModelTargetUiState(
        modelId = reference.modelId,
        displayName = imported?.displayName
            ?: customProfile?.displayName
            ?: official?.displayName
            ?: reference.modelId,
        artifactSha256 = reference.artifactSha256.lowercase(Locale.ROOT),
        exactModelInstalled = officialArtifactInstalled || imported != null,
        currentModelRetained = activeReference != null && !activeReference.sameIdentity(reference),
    )
}

internal fun usableActiveModelReference(
    state: SourceSeparationActivePresetState,
): SourceSeparationActiveModelReference? =
    (state as? SourceSeparationActivePresetState.Reference)
        ?.takeIf { it.installedModel != null }
        ?.reference

private fun SourceSeparationActiveModelReference.sameIdentity(
    other: SourceSeparationActiveModelReference,
): Boolean = modelId == other.modelId &&
    artifactSha256.equals(other.artifactSha256, ignoreCase = true) &&
    contractSchemaVersion == other.contractSchemaVersion &&
    profileId == other.profileId
