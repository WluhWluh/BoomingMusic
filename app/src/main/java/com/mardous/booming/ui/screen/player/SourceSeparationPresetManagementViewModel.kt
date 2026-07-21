package com.mardous.booming.ui.screen.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mardous.booming.separation.model.AndroidMdxRuntimePlatformProvider
import com.mardous.booming.separation.model.MdxRuntimePlatformProvider
import com.mardous.booming.separation.model.contract.CatalogActivationPolicy
import com.mardous.booming.separation.model.contract.CatalogReleaseMaturity
import com.mardous.booming.separation.model.contract.CatalogSupportLevel
import com.mardous.booming.separation.model.preset.SourceSeparationActivePresetState
import com.mardous.booming.separation.model.preset.SourceSeparationInstalledPreset
import com.mardous.booming.separation.model.preset.SourceSeparationPresetActivationResolver
import com.mardous.booming.separation.model.preset.SourceSeparationPresetDownloadCanceledException
import com.mardous.booming.separation.model.preset.SourceSeparationPresetDownloader
import com.mardous.booming.separation.model.preset.SourceSeparationPresetRepository
import com.mardous.booming.separation.model.preset.SourceSeparationPresetSelectionBlockReason
import com.mardous.booming.separation.model.preset.SourceSeparationPresetSelectionScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class SourceSeparationPresetManagementViewModel internal constructor(
    private val repository: SourceSeparationPresetRepository,
    private val downloader: SourceSeparationPresetDownloader,
    private val platformProvider: MdxRuntimePlatformProvider = AndroidMdxRuntimePlatformProvider,
) : ViewModel() {
    private val downloadJobs = ConcurrentHashMap<String, Job>()
    private val transferStates = ConcurrentHashMap<String, SourceSeparationPresetTransferState>()
    private val _state = MutableStateFlow(buildState())

    val state = _state.asStateFlow()

    fun refresh() {
        publishState()
    }

    fun download(modelId: String) {
        if (downloadJobs[modelId]?.isActive == true) return
        transferStates[modelId] = SourceSeparationPresetTransferState.Downloading(
            downloadedBytes = 0L,
            totalBytes = repository.officialPreset(modelId).byteSize,
            usingMirror = false,
        )
        publishState()
        downloadJobs[modelId] = viewModelScope.launch(Dispatchers.IO) {
            try {
                downloader.download(modelId) { progress ->
                    transferStates[modelId] = SourceSeparationPresetTransferState.Downloading(
                        downloadedBytes = progress.downloadedBytes,
                        totalBytes = progress.totalBytes,
                        usingMirror = progress.usingMirror,
                    )
                    publishState()
                }
                transferStates.remove(modelId)
            } catch (_: SourceSeparationPresetDownloadCanceledException) {
                transferStates.remove(modelId)
            } catch (_: CancellationException) {
                downloader.cancel(modelId)
                transferStates.remove(modelId)
                throw CancellationException()
            } catch (error: Throwable) {
                transferStates[modelId] = SourceSeparationPresetTransferState.Failed(
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
        transferStates.remove(modelId)
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
        val installed = item.installed ?: return
        if (item.operationInProgress) return
        transferStates[modelId] = SourceSeparationPresetTransferState.Deleting
        publishState()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.delete(installed.sha256) }
                .onFailure { error ->
                    _state.value = _state.value.copy(errorMessage = error.message)
                }
            transferStates.remove(modelId)
            publishState()
        }
    }

    private fun use(modelId: String, experimentalConfirmed: Boolean) {
        val item = _state.value.entries.singleOrNull { it.modelId == modelId } ?: return
        val installed = item.installed ?: return
        if (item.operationInProgress) return
        transferStates[modelId] = SourceSeparationPresetTransferState.Activating
        _state.value = buildState().copy(confirmationModelId = null)
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
            transferStates.remove(modelId)
            publishState()
        }
    }

    fun clearError(modelId: String? = null) {
        if (modelId != null && transferStates[modelId] is SourceSeparationPresetTransferState.Failed) {
            transferStates.remove(modelId)
        }
        _state.value = buildState().copy(errorMessage = null)
    }

    private fun publishState() {
        val previous = _state.value
        _state.value = buildState().copy(
            confirmationModelId = previous.confirmationModelId,
            errorMessage = previous.errorMessage,
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
                transferState = transferStates[entry.modelId],
            )
        }.sortedWith(
            compareBy<SourceSeparationPresetManagementItem> { it.supportLevel.sortOrder }
                .thenByDescending(SourceSeparationPresetManagementItem::isDefault)
                .thenBy(SourceSeparationPresetManagementItem::displayName),
        )
        return SourceSeparationPresetManagementUiState(
            entries = entries,
            activeReferenceMissing = active is SourceSeparationActivePresetState.Reference &&
                active.installedModel == null,
        )
    }

    override fun onCleared() {
        downloadJobs.keys.toList().forEach(downloader::cancel)
        super.onCleared()
    }
}

data class SourceSeparationPresetManagementUiState(
    val entries: List<SourceSeparationPresetManagementItem> = emptyList(),
    val activeReferenceMissing: Boolean = false,
    val confirmationModelId: String? = null,
    val errorMessage: String? = null,
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
) {
    val offersUseForValidation: Boolean
        get() = activationPolicy == CatalogActivationPolicy.SelectableWhenQualified ||
            activationPolicy == CatalogActivationPolicy.SelectableExperimentalCpuOnly

    val canDelete: Boolean
        get() = installed != null && !active
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

private val CatalogSupportLevel.sortOrder: Int
    get() = when (this) {
        CatalogSupportLevel.Recommended -> 0
        CatalogSupportLevel.Experimental -> 1
        CatalogSupportLevel.DownloadOnly -> 2
    }
