package com.mardous.booming.ui.screen.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mardous.booming.R
import com.mardous.booming.extensions.files.asReadableFileSize
import com.mardous.booming.extensions.utilities.dateStr
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheModelAvailability
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwareCacheEntry
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwareCacheEntryState
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwareCacheFormat

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SourceSeparationModelAwareCacheManagementPage(
    state: SourceSeparationModelAwareCacheManagementUiState,
    autoCleanupEnabled: Boolean,
    partialLimit: Int,
    completedLimit: Int,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onDeleteAll: () -> Unit,
    onDelete: (String) -> Unit,
    onPlay: (String) -> Unit,
    onDismissFailure: () -> Unit,
    onAutoCleanupChange: (Boolean) -> Unit,
    onPartialLimitChange: (Int) -> Unit,
    onCompletedLimitChange: (Int) -> Unit,
) {
    val incompleteItems = state.incompleteItems.sortedByDescending { it.lastAccessedAtEpochMs }
    val completedItems = state.completedItems.sortedByDescending { it.lastAccessedAtEpochMs }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        painter = painterResource(R.drawable.ic_back_24dp),
                        contentDescription = stringResource(R.string.back_action),
                    )
                }
                Text(
                    text = stringResource(R.string.source_separation_manage_caches),
                    style = MaterialTheme.typography.headlineSmallEmphasized,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onRefresh,
                    enabled = !state.loading && !state.deletingAll,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_update_24dp),
                        contentDescription = stringResource(R.string.refresh_action),
                    )
                }
                IconButton(
                    onClick = onDeleteAll,
                    enabled = state.items.isNotEmpty() && !state.loading && !state.deletingAll,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_delete_24dp),
                        contentDescription = stringResource(
                            R.string.source_separation_delete_all_caches,
                        ),
                        tint = if (state.items.isNotEmpty() && !state.deletingAll) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    LabeledSwitch(
                        checked = autoCleanupEnabled,
                        title = stringResource(R.string.source_separation_auto_cache_cleanup_title),
                        description = stringResource(
                            R.string.source_separation_model_aware_cache_cleanup_description,
                        ),
                        onStateChange = onAutoCleanupChange,
                    )
                    AnimatedVisibility(visible = autoCleanupEnabled) {
                        Column {
                            NumberSettingField(
                                value = partialLimit,
                                title = stringResource(
                                    R.string.source_separation_model_aware_cache_partial_limit_title,
                                ),
                                description = stringResource(
                                    R.string.source_separation_model_aware_cache_partial_limit_description,
                                ),
                                suffix = stringResource(
                                    R.string.source_separation_model_aware_cache_entry_suffix,
                                ),
                                onValueChange = onPartialLimitChange,
                            )
                            NumberSettingField(
                                value = completedLimit,
                                title = stringResource(
                                    R.string.source_separation_model_aware_cache_completed_limit_title,
                                ),
                                description = stringResource(
                                    R.string.source_separation_model_aware_cache_completed_limit_description,
                                ),
                                suffix = stringResource(
                                    R.string.source_separation_model_aware_cache_entry_suffix,
                                ),
                                onValueChange = onCompletedLimitChange,
                            )
                        }
                    }
                }
            }
        }

        item {
            Text(
                text = stringResource(
                    R.string.source_separation_model_aware_cache_management_summary,
                    state.items.size,
                    state.totalSizeBytes.asReadableFileSize(),
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        if (state.loading) {
            item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
        }
        if (state.deletingAll) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = stringResource(R.string.source_separation_deleting_all_caches),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        state.failure?.let { failure ->
            item {
                Text(
                    text = failure.displayText(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.clickable(onClick = onDismissFailure),
                )
            }
        }
        if (!state.loading && state.items.isEmpty() && state.failure == null) {
            item {
                Text(
                    text = stringResource(R.string.source_separation_cache_management_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        if (incompleteItems.isNotEmpty()) {
            item {
                SourceSeparationCacheSectionHeader(
                    stringResource(R.string.source_separation_cache_section_incomplete),
                )
            }
            items(incompleteItems, key = SourceSeparationModelAwareCacheEntry::cacheKey) { item ->
                SourceSeparationModelAwareCacheRow(
                    item = item,
                    deleting = item.cacheKey in state.deletingCacheKeys,
                    onDelete = { onDelete(item.cacheKey) },
                    onPlay = {},
                )
            }
        }
        if (completedItems.isNotEmpty()) {
            item {
                SourceSeparationCacheSectionHeader(
                    stringResource(R.string.source_separation_cache_section_completed),
                )
            }
            items(completedItems, key = SourceSeparationModelAwareCacheEntry::cacheKey) { item ->
                SourceSeparationModelAwareCacheRow(
                    item = item,
                    deleting = item.cacheKey in state.deletingCacheKeys,
                    onDelete = { onDelete(item.cacheKey) },
                    onPlay = { onPlay(item.cacheKey) },
                )
            }
        }
    }
}

@Composable
private fun SourceSeparationModelAwareCacheRow(
    item: SourceSeparationModelAwareCacheEntry,
    deleting: Boolean,
    onDelete: () -> Unit,
    onPlay: () -> Unit,
) {
    val context = LocalContext.current
    val title = item.title.takeIf(String::isNotBlank) ?: stringResource(R.string.unknown_song)
    val artist = item.artist.takeIf(String::isNotBlank) ?: stringResource(R.string.unknown_artist)
    var expanded by remember(item.cacheKey) { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(16.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_music_note_24dp),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = item.displayName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                text = item.sizeBytes.asReadableFileSize(),
                maxLines = 1,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            IconButton(
                onClick = onDelete,
                enabled = !deleting,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_delete_24dp),
                    contentDescription = stringResource(R.string.delete_action),
                    tint = if (deleting) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
            Icon(
                painter = painterResource(
                    if (expanded) {
                        R.drawable.ic_keyboard_arrow_up_24dp
                    } else {
                        R.drawable.ic_keyboard_arrow_down_24dp
                    },
                ),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            ) {
                Text(
                    text = artist,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                HorizontalDivider()
                SourceSeparationCacheMetadataRow(
                    label = stringResource(R.string.source_separation_cache_state_label),
                    value = item.state.displayText(item.readySegments, item.totalSegments),
                )
                SourceSeparationCacheMetadataRow(
                    label = stringResource(R.string.source_separation_cache_format_label),
                    value = item.format.displayText(),
                )
                SourceSeparationCacheMetadataRow(
                    label = stringResource(R.string.source_separation_model_details_model_id),
                    value = item.modelId,
                )
                SourceSeparationCacheMetadataRow(
                    label = stringResource(R.string.source_separation_model_actual_hash_label),
                    value = item.artifactSha256.take(SHORT_IDENTITY_LENGTH),
                )
                SourceSeparationCacheMetadataRow(
                    label = stringResource(R.string.source_separation_model_details_contract_id),
                    value = item.contractId,
                )
                SourceSeparationCacheMetadataRow(
                    label = stringResource(
                        R.string.source_separation_model_details_profile_revision,
                    ),
                    value = item.profileRevisionId,
                )
                SourceSeparationCacheMetadataRow(
                    label = stringResource(
                        R.string.source_separation_cache_render_profile_label,
                    ),
                    value = item.renderProfileId,
                )
                SourceSeparationCacheMetadataRow(
                    label = stringResource(
                        R.string.source_separation_cache_model_availability_label,
                    ),
                    value = item.modelAvailability.displayText(),
                )
                SourceSeparationCacheMetadataRow(
                    label = stringResource(R.string.source_separation_cache_identity_label),
                    value = item.cacheKey.take(SHORT_IDENTITY_LENGTH),
                )
                SourceSeparationCacheMetadataRow(
                    label = stringResource(R.string.source_separation_cache_updated_label),
                    value = context.dateStr(item.updatedAtEpochMs),
                )
                SourceSeparationCacheMetadataRow(
                    label = stringResource(R.string.source_separation_cache_accessed_label),
                    value = context.dateStr(item.lastAccessedAtEpochMs),
                )
                if (item.state == SourceSeparationModelAwareCacheEntryState.Completed) {
                    OutlinedButton(
                        onClick = onPlay,
                        enabled = !deleting,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_play_24dp),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = stringResource(
                                R.string.source_separation_play_cached_result,
                            ),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                if (deleting) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = stringResource(R.string.source_separation_clearing_current_cache),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceSeparationModelAwareCacheEntryState.displayText(
    readySegments: Int?,
    totalSegments: Int?,
): String = when (this) {
    SourceSeparationModelAwareCacheEntryState.Partial -> stringResource(
        R.string.source_separation_cache_state_partial,
        readySegments ?: 0,
        totalSegments ?: 0,
    )
    SourceSeparationModelAwareCacheEntryState.Stale ->
        stringResource(R.string.source_separation_cache_state_stale)
    SourceSeparationModelAwareCacheEntryState.Completed ->
        stringResource(R.string.source_separation_cache_state_completed)
    SourceSeparationModelAwareCacheEntryState.Canceled ->
        stringResource(R.string.source_separation_cache_state_canceled)
    SourceSeparationModelAwareCacheEntryState.Failed ->
        stringResource(R.string.source_separation_cache_state_failed)
    SourceSeparationModelAwareCacheEntryState.Corrupt ->
        stringResource(R.string.source_separation_cache_state_corrupt)
}

@Composable
private fun SourceSeparationModelAwareCacheFormat.displayText(): String = when (this) {
    SourceSeparationModelAwareCacheFormat.Wav ->
        stringResource(R.string.source_separation_cache_format_wav)
    SourceSeparationModelAwareCacheFormat.Flac ->
        stringResource(R.string.source_separation_cache_format_flac)
    SourceSeparationModelAwareCacheFormat.Unknown ->
        stringResource(R.string.source_separation_cache_format_unknown)
}

@Composable
private fun SourceSeparationCacheModelAvailability.displayText(): String = when (this) {
    SourceSeparationCacheModelAvailability.InstalledExact ->
        stringResource(R.string.source_separation_cache_model_installed_exact)
    SourceSeparationCacheModelAvailability.ModelNotInstalled ->
        stringResource(R.string.source_separation_cache_model_not_installed)
    SourceSeparationCacheModelAvailability.ProfileNotInstalled ->
        stringResource(R.string.source_separation_cache_profile_not_installed)
    SourceSeparationCacheModelAvailability.ContractMismatch ->
        stringResource(R.string.source_separation_cache_contract_mismatch)
    SourceSeparationCacheModelAvailability.Unknown ->
        stringResource(R.string.source_separation_model_source_unknown)
}

@Composable
private fun SourceSeparationModelAwareCacheManagementFailure.displayText(): String = when (reason) {
    SourceSeparationModelAwareCacheManagementFailureReason.Load -> stringResource(
        R.string.source_separation_cache_management_load_failed,
        detail ?: stringResource(R.string.source_separation_model_source_unknown),
    )
    SourceSeparationModelAwareCacheManagementFailureReason.Busy ->
        stringResource(R.string.source_separation_cache_entry_busy)
    SourceSeparationModelAwareCacheManagementFailureReason.Delete ->
        stringResource(R.string.source_separation_cache_entry_delete_failed)
}

private const val SHORT_IDENTITY_LENGTH = 12
