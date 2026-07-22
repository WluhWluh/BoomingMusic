package com.mardous.booming.ui.screen.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mardous.booming.separation.SourceSeparationRuntimeFacade
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheMutationResult
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwareCacheEntry
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwareCacheEntryState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SourceSeparationModelAwareCacheManagementViewModel internal constructor(
    private val runtime: SourceSeparationRuntimeFacade,
) : ViewModel() {
    private val _state = MutableStateFlow(SourceSeparationModelAwareCacheManagementUiState())
    val state = _state.asStateFlow()

    private var loadJob: Job? = null
    private var cleanupPolicy: SourceSeparationModelAwareCacheCleanupPolicy? = null

    init { refresh() }

    fun updateCleanupPolicy(enabled: Boolean, partialLimit: Int, completedLimit: Int) {
        val next = SourceSeparationModelAwareCacheCleanupPolicy(
            enabled = enabled,
            partialLimit = partialLimit.coerceAtLeast(0),
            completedLimit = completedLimit.coerceAtLeast(0),
        )
        if (cleanupPolicy == next) return
        cleanupPolicy = next
        refresh(pruneFirst = true)
    }

    fun refresh() {
        refresh(pruneFirst = cleanupPolicy?.enabled == true)
    }

    fun delete(cacheKey: String) {
        val current = _state.value
        if (current.deletingAll || cacheKey in current.deletingCacheKeys) return
        if (current.items.none { it.cacheKey == cacheKey }) return
        loadJob?.cancel()
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = _state.value.copy(
                deletingCacheKeys = _state.value.deletingCacheKeys + cacheKey,
                failure = null,
            )
            val failure = runtime.delete(cacheKey).toFailure()
            loadEntries(
                failure = failure,
                deletingCacheKeys = _state.value.deletingCacheKeys - cacheKey,
            )
        }
    }

    fun deleteAll() {
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
                val resultFailure = runtime.delete(cacheKey).toFailure()
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

    private fun refresh(pruneFirst: Boolean) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            _state.value = _state.value.copy(loading = true, failure = null)
            val result = runCatching {
                cleanupPolicy?.takeIf { pruneFirst && it.enabled }?.let { policy ->
                    runtime.prune(
                        partialLimit = policy.partialLimit,
                        completedLimit = policy.completedLimit,
                    )
                }
                runtime.entries()
            }
            _state.value = result.fold(
                onSuccess = { items ->
                    _state.value.copy(loading = false, items = items)
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
                    failure = failure,
                )
            },
            onFailure = { error ->
                _state.value.copy(
                    loading = false,
                    deletingAll = deletingAll,
                    deletingCacheKeys = deletingCacheKeys,
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
}

private data class SourceSeparationModelAwareCacheCleanupPolicy(
    val enabled: Boolean,
    val partialLimit: Int,
    val completedLimit: Int,
)
