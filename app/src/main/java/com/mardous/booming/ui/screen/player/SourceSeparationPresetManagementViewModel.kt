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
import com.mardous.booming.separation.model.preset.SourceSeparationActivePresetState
import com.mardous.booming.separation.model.preset.SourceSeparationInstalledPreset
import com.mardous.booming.separation.model.preset.SourceSeparationPresetActivationResolver
import com.mardous.booming.separation.model.preset.SourceSeparationPresetBindingKind
import com.mardous.booming.separation.model.preset.SourceSeparationPresetDownloadCanceledException
import com.mardous.booming.separation.model.preset.SourceSeparationPresetDownloader
import com.mardous.booming.separation.model.preset.SourceSeparationPresetImportCoordinator
import com.mardous.booming.separation.model.preset.SourceSeparationPresetImportOutcome
import com.mardous.booming.separation.model.preset.SourceSeparationPresetRepository
import com.mardous.booming.separation.model.preset.SourceSeparationPresetSelectionBlockReason
import com.mardous.booming.separation.model.preset.SourceSeparationPresetSelectionScope
import com.mardous.booming.separation.model.preset.SourceSeparationPendingModelImport
import com.mardous.booming.separation.model.preset.SourceSeparationManualModelProfileDraft
import com.mardous.booming.separation.model.preset.SourceSeparationManualModelStem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.Locale

class SourceSeparationPresetManagementViewModel internal constructor(
    private val contentResolver: ContentResolver,
    private val repository: SourceSeparationPresetRepository,
    private val downloader: SourceSeparationPresetDownloader,
    private val importCoordinator: SourceSeparationPresetImportCoordinator,
    private val platformProvider: MdxRuntimePlatformProvider = AndroidMdxRuntimePlatformProvider,
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
        )
    }

    private fun buildState(): SourceSeparationPresetManagementUiState {
        val platform = runCatching(platformProvider::current).getOrNull()
        val active = repository.activeModel()
        val activeReference = (active as? SourceSeparationActivePresetState.Reference)?.reference
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
                )
            }
            .sortedBy { it.displayName.lowercase(Locale.ROOT) }
        return SourceSeparationPresetManagementUiState(
            entries = entries,
            importedEntries = importedEntries,
            activeReferenceMissing = active is SourceSeparationActivePresetState.Reference &&
                active.installedModel == null,
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
            runCatching { repository.delete(installed.sha256) }
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
        )
    }

    private fun catalogOperationKey(modelId: String) = "catalog:$modelId"

    private fun importOperationKey(sha256: String) = "import:${sha256.lowercase(Locale.ROOT)}"
}

data class SourceSeparationPresetManagementUiState(
    val entries: List<SourceSeparationPresetManagementItem> = emptyList(),
    val importedEntries: List<SourceSeparationImportedModelManagementItem> = emptyList(),
    val activeReferenceMissing: Boolean = false,
    val confirmationModelId: String? = null,
    val errorMessage: String? = null,
    val importState: SourceSeparationPresetImportUiState = SourceSeparationPresetImportUiState.Idle,
) {
    val confirmationModel: SourceSeparationPresetManagementItem?
        get() = entries.singleOrNull { it.modelId == confirmationModelId }
}

data class SourceSeparationPresetManagementItem(
    val modelId: String,
    val displayName: String,
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
            activationPolicy == CatalogActivationPolicy.SelectableExperimentalCpuOnly

    val canDelete: Boolean
        get() = installed != null && !active
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
) {
    val canDelete: Boolean
        get() = !active && !operationInProgress
}

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
