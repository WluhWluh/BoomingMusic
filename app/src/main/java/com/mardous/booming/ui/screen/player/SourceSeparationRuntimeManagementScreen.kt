package com.mardous.booming.ui.screen.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mardous.booming.R
import com.mardous.booming.extensions.files.asReadableFileSize
import com.mardous.booming.separation.runtime.SourceSeparationGpuRuntimeInventoryItem
import com.mardous.booming.separation.runtime.SourceSeparationGpuRuntimeState
import com.mardous.booming.separation.runtime.SourceSeparationRuntimeInventoryItem
import com.mardous.booming.separation.runtime.SourceSeparationRuntimeState
import com.mardous.booming.ui.component.compose.TitledCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SourceSeparationRuntimeManagementPage(
    state: SourceSeparationRuntimeManagementUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onInstall: (String) -> Unit,
    onRepair: (String) -> Unit,
    onActivatePending: (String) -> Unit,
    onRemove: (String) -> Unit,
    onInstallGpu: (String) -> Unit,
    onRepairGpu: (String) -> Unit,
    onActivatePendingGpu: (String) -> Unit,
    onRemoveGpu: (String) -> Unit,
    onGpuEnabledChange: (Boolean) -> Unit,
    onDismissError: () -> Unit,
) {
    var pendingRemove by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingGpuRemove by rememberSaveable { mutableStateOf<String?>(null) }
    val pendingItem = state.items.singleOrNull { it.catalogEntry.componentId == pendingRemove }
    val pendingGpuItem = state.gpuItems.singleOrNull {
        it.catalogEntry.componentId == pendingGpuRemove
    }

    if (pendingItem != null || pendingGpuItem != null) {
        val pendingAbi = pendingItem?.catalogEntry?.abi ?: pendingGpuItem!!.catalogEntry.abi
        AlertDialog(
            onDismissRequest = {
                pendingRemove = null
                pendingGpuRemove = null
            },
            title = { Text(stringResource(R.string.source_separation_runtime_remove_component_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.source_separation_runtime_remove_component_message,
                        pendingAbi,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (pendingItem != null) {
                            onRemove(pendingItem.catalogEntry.componentId)
                        } else {
                            onRemoveGpu(pendingGpuItem!!.catalogEntry.componentId)
                        }
                        pendingRemove = null
                        pendingGpuRemove = null
                    },
                ) {
                    Text(stringResource(R.string.source_separation_runtime_remove_action))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingRemove = null
                    pendingGpuRemove = null
                }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

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
                    text = stringResource(R.string.source_separation_runtime_management_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onRefresh, enabled = state.operation == null) {
                    Icon(
                        painter = painterResource(R.drawable.ic_update_24dp),
                        contentDescription = stringResource(R.string.refresh_action),
                    )
                }
            }
        }

        item {
            TitledCard(
                title = stringResource(R.string.source_separation_runtime_device_title),
                modifier = Modifier.fillMaxWidth(),
            ) { padding ->
                Column(
                    modifier = Modifier.padding(padding),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "${state.device.manufacturer} ${state.device.model}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(
                            R.string.source_separation_runtime_device_summary,
                            state.device.abi,
                            state.device.api,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    val hardware = listOf(state.device.soc, state.device.hardware)
                        .filter(String::isNotBlank)
                        .distinct()
                        .joinToString(" / ")
                    if (hardware.isNotBlank()) {
                        Text(
                            text = hardware,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        item {
            TitledCard(
                title = stringResource(R.string.source_separation_runtime_gpu_title),
                modifier = Modifier.fillMaxWidth(),
            ) { padding ->
                Column(
                    modifier = Modifier.padding(padding),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.source_separation_runtime_gpu_enabled_title),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = stringResource(
                                    R.string.source_separation_runtime_gpu_enabled_description,
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = state.gpuEnabled,
                            onCheckedChange = onGpuEnabledChange,
                            enabled = state.gpuItems.any {
                                it.catalogEntry.abi == state.device.abi
                            },
                        )
                    }
                    Text(
                        text = stringResource(R.string.source_separation_runtime_gpu_profile),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (state.gpuItems.none { it.catalogEntry.abi == state.device.abi }) {
                        Text(
                            text = stringResource(R.string.source_separation_runtime_gpu_unavailable),
                            color = MaterialTheme.colorScheme.tertiary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        state.errorMessage?.let { message ->
            item {
                TitledCard(
                    title = stringResource(R.string.source_separation_runtime_diagnostics_title),
                    modifier = Modifier.fillMaxWidth(),
                ) { padding ->
                    Column(modifier = Modifier.padding(padding)) {
                        Text(text = message, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = onDismissError) {
                            Text(stringResource(R.string.close_action))
                        }
                    }
                }
            }
        }

        if (state.isLoading) {
            item {
                CircularProgressIndicator(modifier = Modifier.padding(16.dp))
            }
        } else {
            items(
                items = state.items,
                key = { it.catalogEntry.componentId },
            ) { item ->
                SourceSeparationRuntimeItemCard(
                    item = item,
                    currentAbi = state.device.abi,
                    operation = state.operation,
                    onInstall = onInstall,
                    onRepair = onRepair,
                    onActivatePending = onActivatePending,
                    onRemove = { pendingRemove = it },
                )
            }
            items(
                items = state.gpuItems,
                key = { "gpu-${it.catalogEntry.componentId}" },
            ) { item ->
                SourceSeparationGpuRuntimeItemCard(
                    item = item,
                    currentAbi = state.device.abi,
                    operation = state.operation,
                    onInstall = onInstallGpu,
                    onRepair = onRepairGpu,
                    onActivatePending = onActivatePendingGpu,
                    onRemove = { pendingGpuRemove = it },
                )
            }
        }
    }
}

@Composable
private fun SourceSeparationRuntimeItemCard(
    item: SourceSeparationRuntimeInventoryItem,
    currentAbi: String,
    operation: SourceSeparationRuntimeOperation?,
    onInstall: (String) -> Unit,
    onRepair: (String) -> Unit,
    onActivatePending: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    val entry = item.catalogEntry
    val isCurrentAbi = entry.abi == currentAbi
    val isBusy = operation?.componentId == entry.componentId
    TitledCard(
        title = entry.abi,
        modifier = Modifier.fillMaxWidth(),
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(
                    R.string.source_separation_runtime_release_summary,
                    entry.runtimeArtifactVersion,
                    entry.producerReleaseVersion,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = runtimeStateText(item.state),
                color = if (item.state == SourceSeparationRuntimeState.Invalid) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(
                    R.string.source_separation_runtime_size_summary,
                    entry.delivery.expectedByteSize.asReadableFileSize(),
                    entry.innerLibraries.sumOf { it.byteSize }.asReadableFileSize(),
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = entry.innerLibraries.joinToString("\n") { it.sha256 },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
            )
            item.reason?.let { reason ->
                Text(
                    text = reason,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (isBusy && operation != null && operation.totalBytes > 0L) {
                LinearProgressIndicator(
                    progress = {
                        (operation.downloadedBytes.toFloat() / operation.totalBytes)
                            .coerceIn(0f, 1f)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(
                        R.string.source_separation_runtime_progress,
                        operation.downloadedBytes.asReadableFileSize(),
                        operation.totalBytes.asReadableFileSize(),
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (!isCurrentAbi) {
                Text(
                    text = stringResource(R.string.source_separation_runtime_other_abi),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                RuntimeActionButton(
                    item = item,
                    enabled = operation == null,
                    onInstall = onInstall,
                    onRepair = onRepair,
                    onActivatePending = onActivatePending,
                    onRemove = onRemove,
                )
            }
        }
    }
}

@Composable
private fun RuntimeActionButton(
    item: SourceSeparationRuntimeInventoryItem,
    enabled: Boolean,
    onInstall: (String) -> Unit,
    onRepair: (String) -> Unit,
    onActivatePending: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    val componentId = item.catalogEntry.componentId
    when (item.state) {
        SourceSeparationRuntimeState.Missing -> OutlinedButton(
            onClick = { onInstall(componentId) },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(painterResource(R.drawable.ic_download_24dp), null, Modifier.size(18.dp))
            Text(
                stringResource(R.string.source_separation_runtime_install),
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        SourceSeparationRuntimeState.Invalid -> OutlinedButton(
            onClick = { onRepair(componentId) },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(painterResource(R.drawable.ic_update_24dp), null, Modifier.size(18.dp))
            Text(
                stringResource(R.string.source_separation_runtime_repair),
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        SourceSeparationRuntimeState.Installed -> OutlinedButton(
            onClick = { onRemove(componentId) },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(painterResource(R.drawable.ic_delete_24dp), null, Modifier.size(18.dp))
            Text(
                stringResource(R.string.source_separation_runtime_remove_action),
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        SourceSeparationRuntimeState.PendingActivation -> Button(
            onClick = { onActivatePending(componentId) },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(painterResource(R.drawable.ic_check_24dp), null, Modifier.size(18.dp))
            Text(
                stringResource(R.string.source_separation_runtime_activate),
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        SourceSeparationRuntimeState.PendingDeletion -> Text(
            text = stringResource(R.string.source_separation_runtime_pending_deletion),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun runtimeStateText(state: SourceSeparationRuntimeState): String = when (state) {
    SourceSeparationRuntimeState.Missing -> stringResource(R.string.source_separation_runtime_missing)
    SourceSeparationRuntimeState.Installed -> stringResource(R.string.source_separation_runtime_installed)
    SourceSeparationRuntimeState.Invalid -> stringResource(R.string.source_separation_runtime_invalid)
    SourceSeparationRuntimeState.PendingActivation ->
        stringResource(R.string.source_separation_runtime_pending_activation)
    SourceSeparationRuntimeState.PendingDeletion ->
        stringResource(R.string.source_separation_runtime_pending_deletion)
}

@Composable
private fun SourceSeparationGpuRuntimeItemCard(
    item: SourceSeparationGpuRuntimeInventoryItem,
    currentAbi: String,
    operation: SourceSeparationRuntimeOperation?,
    onInstall: (String) -> Unit,
    onRepair: (String) -> Unit,
    onActivatePending: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    val entry = item.catalogEntry
    val isCurrentAbi = entry.abi == currentAbi
    val isBusy = operation?.componentId == entry.componentId
    TitledCard(
        title = stringResource(R.string.source_separation_runtime_gpu_component_title),
        modifier = Modifier.fillMaxWidth(),
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(
                    R.string.source_separation_runtime_release_summary,
                    entry.runtimeArtifactVersion,
                    entry.producerReleaseVersion,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = gpuRuntimeStateText(item.state),
                color = if (item.state == SourceSeparationGpuRuntimeState.Invalid) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(
                    R.string.source_separation_runtime_gpu_size_summary,
                    entry.delivery.expectedByteSize.asReadableFileSize(),
                    entry.files.sumOf { it.byteSize }.asReadableFileSize(),
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = entry.capability.profileId,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
            item.reason?.let { reason ->
                Text(
                    text = reason,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (isBusy && operation != null && operation.totalBytes > 0L) {
                LinearProgressIndicator(
                    progress = {
                        (operation.downloadedBytes.toFloat() / operation.totalBytes)
                            .coerceIn(0f, 1f)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(
                        R.string.source_separation_runtime_progress,
                        operation.downloadedBytes.asReadableFileSize(),
                        operation.totalBytes.asReadableFileSize(),
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (!isCurrentAbi) {
                Text(
                    text = stringResource(R.string.source_separation_runtime_other_abi),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                GpuRuntimeActionButton(
                    item = item,
                    enabled = operation == null,
                    onInstall = onInstall,
                    onRepair = onRepair,
                    onActivatePending = onActivatePending,
                    onRemove = onRemove,
                )
            }
        }
    }
}

@Composable
private fun GpuRuntimeActionButton(
    item: SourceSeparationGpuRuntimeInventoryItem,
    enabled: Boolean,
    onInstall: (String) -> Unit,
    onRepair: (String) -> Unit,
    onActivatePending: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    val componentId = item.catalogEntry.componentId
    when (item.state) {
        SourceSeparationGpuRuntimeState.Missing -> OutlinedButton(
            onClick = { onInstall(componentId) },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(painterResource(R.drawable.ic_download_24dp), null, Modifier.size(18.dp))
            Text(
                stringResource(R.string.source_separation_runtime_gpu_install),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        SourceSeparationGpuRuntimeState.Invalid -> OutlinedButton(
            onClick = { onRepair(componentId) },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(painterResource(R.drawable.ic_update_24dp), null, Modifier.size(18.dp))
            Text(
                stringResource(R.string.source_separation_runtime_repair),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        SourceSeparationGpuRuntimeState.Installed -> OutlinedButton(
            onClick = { onRemove(componentId) },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(painterResource(R.drawable.ic_delete_24dp), null, Modifier.size(18.dp))
            Text(
                stringResource(R.string.source_separation_runtime_remove_action),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        SourceSeparationGpuRuntimeState.PendingActivation -> Button(
            onClick = { onActivatePending(componentId) },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(painterResource(R.drawable.ic_check_24dp), null, Modifier.size(18.dp))
            Text(
                stringResource(R.string.source_separation_runtime_activate),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        SourceSeparationGpuRuntimeState.PendingDeletion -> Text(
            text = stringResource(R.string.source_separation_runtime_pending_deletion),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun gpuRuntimeStateText(state: SourceSeparationGpuRuntimeState): String = when (state) {
    SourceSeparationGpuRuntimeState.Missing -> stringResource(R.string.source_separation_runtime_missing)
    SourceSeparationGpuRuntimeState.Installed -> stringResource(R.string.source_separation_runtime_installed)
    SourceSeparationGpuRuntimeState.Invalid -> stringResource(R.string.source_separation_runtime_invalid)
    SourceSeparationGpuRuntimeState.PendingActivation ->
        stringResource(R.string.source_separation_runtime_pending_activation)
    SourceSeparationGpuRuntimeState.PendingDeletion ->
        stringResource(R.string.source_separation_runtime_pending_deletion)
}
