package com.mardous.booming.ui.screen.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mardous.booming.separation.SourceSeparationCacheModelActivator
import com.mardous.booming.separation.SourceSeparationExecutionSelectionSnapshot
import com.mardous.booming.separation.SourceSeparationRuntimeFacade
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheMutationResult
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheModelAvailability
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwareCacheEntry
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwareCacheEntryState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SourceSeparationModelAwareCacheManagementViewModel internal constructor(
    private val runtime: SourceSeparationRuntimeFacade,
    private val modelActivator: SourceSeparationCacheModelActivator? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(SourceSeparationModelAwareCacheManagementUiState())
    val state = _state.asStateFlow()

    private var loadJob: Job? = null

    init { refresh() }

    fun refresh() {
        refreshEntries()
    }

    fun delete(cacheKey: String) {
        delete(
            cacheKey = cacheKey,
            beforeDelete = {},
            onDeleteResult = { _, _ -> },
        )
    }

    fun delete(
        cacheKey: String,
        beforeDelete: suspend (String) -> Unit,
        onDeleteResult: suspend (String, SourceSeparationCacheMutationResult) -> Unit,
    ) {
        val current = _state.value
        if (current.deletingAll || cacheKey in current.deletingCacheKeys) return
        if (current.items.none { it.cacheKey == cacheKey }) return
        loadJob?.cancel()
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = _state.value.copy(
                deletingCacheKeys = _state.value.deletingCacheKeys + cacheKey,
                failure = null,
            )
            beforeDelete(cacheKey)
            val result = runtime.delete(cacheKey)
            onDeleteResult(cacheKey, result)
            val failure = result.toFailure()
            loadEntries(
                failure = failure,
                deletingCacheKeys = _state.value.deletingCacheKeys - cacheKey,
            )
        }
    }

    fun deleteAll() {
        deleteAll(
            beforeDelete = {},
            onDeleteResult = { _, _ -> },
        )
    }

    fun deleteAll(
        beforeDelete: suspend (String) -> Unit,
        onDeleteResult: suspend (String, SourceSeparationCacheMutationResult) -> Unit,
    ) {
        val current = _state.value
        if (current.deletingAll || current.items.isEmpty()) return
        val keys = current.items.mapTo(linkedSetOf(), SourceSeparationModelAwareCacheEntry::cacheKey)
        loadJob?.cancel()
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = _state.value.copy(
                deletingAll = true,
                deletingCacheKeys = keys,
                failure = null,
            )
            var failure: SourceSeparationModelAwareCacheManagementFailure? = null
            keys.forEach { cacheKey ->
                beforeDelete(cacheKey)
                val result = runtime.delete(cacheKey)
                onDeleteResult(cacheKey, result)
                val resultFailure = result.toFailure()
                if (failure == null && resultFailure != null) failure = resultFailure
                _state.value = _state.value.copy(
                    deletingCacheKeys = _state.value.deletingCacheKeys - cacheKey,
                )
            }
            loadEntries(failure = failure, deletingAll = false)
        }
    }

    fun clearFailure() {
        _state.value = _state.value.copy(failure = null)
    }

    fun useModel(cacheKey: String) {
        val activator = modelActivator ?: return
        val item = _state.value.items.singleOrNull { it.cacheKey == cacheKey } ?: return
        if (item.modelAvailability != SourceSeparationCacheModelAvailability.InstalledExact ||
            _state.value.activatingCacheKey != null
        ) return
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = _state.value.copy(activatingCacheKey = cacheKey, failure = null)
            val result = runCatching { activator.activate(item) }
            loadEntries(
                failure = result.exceptionOrNull()?.let { error ->
                    SourceSeparationModelAwareCacheManagementFailure(
                        reason = SourceSeparationModelAwareCacheManagementFailureReason.Activate,
                        detail = error.message,
                    )
                },
                activatingCacheKey = null,
            )
        }
    }

    private fun refreshEntries() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            _state.value = _state.value.copy(loading = true, failure = null)
            val result = runCatching(runtime::entries)
            _state.value = result.fold(
                onSuccess = { items ->
                    _state.value.copy(
                        loading = false,
                        items = items,
                        activeSelection = modelActivator?.current(),
                    )
                },
                onFailure = { error ->
                    _state.value.copy(
                        loading = false,
                        failure = SourceSeparationModelAwareCacheManagementFailure(
                            reason = SourceSeparationModelAwareCacheManagementFailureReason.Load,
                            detail = error.message,
                        ),
                    )
                },
            )
        }
    }

    private fun loadEntries(
        failure: SourceSeparationModelAwareCacheManagementFailure?,
        deletingAll: Boolean = _state.value.deletingAll,
        deletingCacheKeys: Set<String> = _state.value.deletingCacheKeys,
        activatingCacheKey: String? = _state.value.activatingCacheKey,
    ) {
        val result = runCatching(runtime::entries)
        _state.value = result.fold(
            onSuccess = { items ->
                SourceSeparationModelAwareCacheManagementUiState(
                    items = items,
                    deletingAll = deletingAll,
                    deletingCacheKeys = deletingCacheKeys.intersect(
                        items.mapTo(mutableSetOf(), SourceSeparationModelAwareCacheEntry::cacheKey),
                    ),
                    activatingCacheKey = activatingCacheKey,
                    activeSelection = modelActivator?.current(),
                    failure = failure,
                )
            },
            onFailure = { error ->
                _state.value.copy(
                    loading = false,
                    deletingAll = deletingAll,
                    deletingCacheKeys = deletingCacheKeys,
                    activatingCacheKey = activatingCacheKey,
                    failure = SourceSeparationModelAwareCacheManagementFailure(
                        reason = SourceSeparationModelAwareCacheManagementFailureReason.Load,
                        detail = error.message,
                    ),
                )
            },
        )
    }

    private fun SourceSeparationCacheMutationResult.toFailure():
        SourceSeparationModelAwareCacheManagementFailure? = when (this) {
        SourceSeparationCacheMutationResult.Completed -> null
        SourceSeparationCacheMutationResult.Busy ->
            SourceSeparationModelAwareCacheManagementFailure(
                SourceSeparationModelAwareCacheManagementFailureReason.Busy,
            )
        SourceSeparationCacheMutationResult.Failed ->
            SourceSeparationModelAwareCacheManagementFailure(
                SourceSeparationModelAwareCacheManagementFailureReason.Delete,
            )
    }
}

data class SourceSeparationModelAwareCacheManagementUiState(
    val loading: Boolean = false,
    val items: List<SourceSeparationModelAwareCacheEntry> = emptyList(),
    val deletingAll: Boolean = false,
    val deletingCacheKeys: Set<String> = emptySet(),
    val activatingCacheKey: String? = null,
    val activeSelection: SourceSeparationExecutionSelectionSnapshot? = null,
    val failure: SourceSeparationModelAwareCacheManagementFailure? = null,
) {
    val totalSizeBytes: Long
        get() = items.sumOf(SourceSeparationModelAwareCacheEntry::sizeBytes)

    val completedItems: List<SourceSeparationModelAwareCacheEntry>
        get() = items.filter { it.state == SourceSeparationModelAwareCacheEntryState.Completed }

    val incompleteItems: List<SourceSeparationModelAwareCacheEntry>
        get() = items.filterNot { it.state == SourceSeparationModelAwareCacheEntryState.Completed }
}

data class SourceSeparationModelAwareCacheManagementFailure(
    val reason: SourceSeparationModelAwareCacheManagementFailureReason,
    val detail: String? = null,
)

enum class SourceSeparationModelAwareCacheManagementFailureReason {
    Load,
    Busy,
    Delete,
    Activate,
}
