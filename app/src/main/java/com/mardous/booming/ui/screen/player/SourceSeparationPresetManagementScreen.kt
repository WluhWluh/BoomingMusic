package com.mardous.booming.ui.screen.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mardous.booming.R
import com.mardous.booming.extensions.files.asReadableFileSize
import com.mardous.booming.separation.model.contract.CatalogActivationPolicy
import com.mardous.booming.separation.model.contract.CatalogReleaseMaturity
import com.mardous.booming.separation.model.contract.CatalogSupportLevel
import com.mardous.booming.ui.component.compose.BottomSheetDialogSurface

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SourceSeparationPresetManagementSheet(
    state: SourceSeparationPresetManagementUiState,
    onDownload: (String) -> Unit,
    onCancelDownload: (String) -> Unit,
    onUse: (String) -> Unit,
    onDelete: (String) -> Unit,
    onConfirmExperimental: () -> Unit,
    onDismissExperimental: () -> Unit,
    onClearError: (String?) -> Unit,
    onRefresh: () -> Unit,
) {
    var pendingDeleteModelId by rememberSaveable { mutableStateOf<String?>(null) }
    val pendingDeleteModel = state.entries.singleOrNull { it.modelId == pendingDeleteModelId }

    BottomSheetDialogSurface {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .nestedScroll(rememberNestedScrollInteropConnection()),
        ) {
            BottomSheetDefaults.DragHandle(Modifier.align(Alignment.CenterHorizontally))
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
                        Text(
                            text = stringResource(R.string.source_separation_preset_models_title),
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onRefresh) {
                            Icon(
                                painter = painterResource(R.drawable.ic_update_24dp),
                                contentDescription = stringResource(R.string.refresh_action),
                            )
                        }
                    }
                }

                if (state.activeReferenceMissing) {
                    item {
                        Text(
                            text = stringResource(
                                R.string.source_separation_preset_active_missing,
                            ),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                state.errorMessage?.takeIf(String::isNotBlank)?.let { message ->
                    item {
                        ModelOperationError(
                            message = message,
                            onDismiss = { onClearError(null) },
                        )
                    }
                }

                modelSection(
                    titleRes = R.string.source_separation_preset_recommended_section,
                    entries = state.entries.filter {
                        it.supportLevel == CatalogSupportLevel.Recommended
                    },
                    onDownload = onDownload,
                    onCancelDownload = onCancelDownload,
                    onUse = onUse,
                    onDelete = { pendingDeleteModelId = it },
                    onClearError = onClearError,
                )
                modelSection(
                    titleRes = R.string.source_separation_preset_experimental_section,
                    entries = state.entries.filter {
                        it.supportLevel == CatalogSupportLevel.Experimental
                    },
                    onDownload = onDownload,
                    onCancelDownload = onCancelDownload,
                    onUse = onUse,
                    onDelete = { pendingDeleteModelId = it },
                    onClearError = onClearError,
                )
                modelSection(
                    titleRes = R.string.source_separation_preset_download_only_section,
                    entries = state.entries.filter {
                        it.supportLevel == CatalogSupportLevel.DownloadOnly
                    },
                    onDownload = onDownload,
                    onCancelDownload = onCancelDownload,
                    onUse = onUse,
                    onDelete = { pendingDeleteModelId = it },
                    onClearError = onClearError,
                )
            }
        }
    }

    state.confirmationModel?.let { model ->
        AlertDialog(
            onDismissRequest = onDismissExperimental,
            icon = {
                Icon(
                    painter = painterResource(R.drawable.ic_info_24dp),
                    contentDescription = null,
                )
            },
            title = {
                Text(stringResource(R.string.source_separation_preset_experimental_confirm_title))
            },
            text = {
                Text(
                    stringResource(
                        R.string.source_separation_preset_experimental_confirm_message,
                        model.displayName,
                    ),
                )
            },
            confirmButton = {
                Button(onClick = onConfirmExperimental) {
                    Text(stringResource(R.string.source_separation_preset_use_for_validation))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissExperimental) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    pendingDeleteModel?.let { model ->
        AlertDialog(
            onDismissRequest = { pendingDeleteModelId = null },
            icon = {
                Icon(
                    painter = painterResource(R.drawable.ic_delete_24dp),
                    contentDescription = null,
                )
            },
            title = { Text(stringResource(R.string.source_separation_delete_model)) },
            text = {
                Text(
                    stringResource(
                        R.string.source_separation_preset_delete_confirm_message,
                        model.displayName,
                    ),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDelete(model.modelId)
                        pendingDeleteModelId = null
                    },
                ) {
                    Text(stringResource(R.string.delete_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteModelId = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.modelSection(
    titleRes: Int,
    entries: List<SourceSeparationPresetManagementItem>,
    onDownload: (String) -> Unit,
    onCancelDownload: (String) -> Unit,
    onUse: (String) -> Unit,
    onDelete: (String) -> Unit,
    onClearError: (String?) -> Unit,
) {
    if (entries.isEmpty()) return
    item(key = "section-$titleRes") {
        Text(
            text = stringResource(titleRes),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    items(
        count = entries.size,
        key = { index -> entries[index].modelId },
    ) { index ->
        PresetModelCard(
            model = entries[index],
            onDownload = onDownload,
            onCancelDownload = onCancelDownload,
            onUse = onUse,
            onDelete = onDelete,
            onClearError = onClearError,
        )
    }
}

@Composable
private fun PresetModelCard(
    model: SourceSeparationPresetManagementItem,
    onDownload: (String) -> Unit,
    onCancelDownload: (String) -> Unit,
    onUse: (String) -> Unit,
    onDelete: (String) -> Unit,
    onClearError: (String?) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(16.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = model.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = model.statusText(),
                        color = if (model.active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (model.active) {
                    Icon(
                        painter = painterResource(R.drawable.ic_check_24dp),
                        contentDescription = stringResource(
                            R.string.source_separation_preset_selected_for_validation,
                        ),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Text(
                text = model.activationText(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            model.byteSize?.let { byteSize ->
                Text(
                    text = stringResource(
                        R.string.source_separation_preset_size_and_release,
                        byteSize.asReadableFileSize(),
                        model.releaseTag.orEmpty(),
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            when (val transfer = model.transferState) {
                is SourceSeparationPresetTransferState.Downloading -> {
                    LinearProgressIndicator(
                        progress = { transfer.fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(
                                if (transfer.usingMirror) {
                                    R.string.source_separation_model_downloading_mirror
                                } else {
                                    R.string.source_separation_model_downloading
                                },
                            ),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { onCancelDownload(model.modelId) },
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_cancel_24dp),
                                contentDescription = stringResource(R.string.action_cancel),
                            )
                        }
                    }
                }

                is SourceSeparationPresetTransferState.Failed -> {
                    ModelOperationError(
                        message = transfer.message,
                        onDismiss = { onClearError(model.modelId) },
                    )
                }

                SourceSeparationPresetTransferState.Activating -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = stringResource(R.string.source_separation_preset_selecting),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                SourceSeparationPresetTransferState.Deleting -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = stringResource(R.string.source_separation_model_deleting),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                null -> Unit
            }

            if (model.transferState == null ||
                model.transferState is SourceSeparationPresetTransferState.Failed
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (model.installed == null) {
                        Button(
                            onClick = { onDownload(model.modelId) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_download_24dp),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = stringResource(R.string.download_action),
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    } else {
                        if (model.offersUseForValidation) {
                            OutlinedButton(
                                onClick = { onUse(model.modelId) },
                                enabled = model.canUseForValidation && !model.active,
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_play_24dp),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Text(
                                    text = stringResource(
                                        R.string.source_separation_preset_use_for_validation,
                                    ),
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                        IconButton(
                            onClick = { onDelete(model.modelId) },
                            enabled = model.canDelete,
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_delete_24dp),
                                contentDescription = stringResource(
                                    R.string.source_separation_delete_model,
                                ),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelOperationError(message: String, onDismiss: () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(R.string.source_separation_model_error, message),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
            Icon(
                painter = painterResource(R.drawable.ic_close_24dp),
                contentDescription = stringResource(R.string.close_action),
            )
        }
    }
}

@Composable
private fun SourceSeparationPresetManagementItem.statusText(): String = when {
    active -> stringResource(R.string.source_separation_preset_selected_for_validation)
    installed != null -> stringResource(R.string.source_separation_preset_installed)
    else -> stringResource(R.string.source_separation_preset_not_installed)
}

@Composable
private fun SourceSeparationPresetManagementItem.activationText(): String = when (activationPolicy) {
    CatalogActivationPolicy.SelectableWhenQualified ->
        stringResource(
            if (releaseMaturity == CatalogReleaseMaturity.Stable) {
                R.string.source_separation_preset_recommended_ready
            } else {
                R.string.source_separation_preset_promotion_pending
            },
        )
    CatalogActivationPolicy.SelectableExperimentalCpuOnly ->
        stringResource(R.string.source_separation_preset_experimental_cpu_only)
    CatalogActivationPolicy.DownloadOnlyResourceGated ->
        stringResource(R.string.source_separation_preset_resource_gated)
    CatalogActivationPolicy.BlockedUntilReviewedContract ->
        stringResource(R.string.source_separation_preset_contract_pending)
    CatalogActivationPolicy.DownloadOnlyGenericStem ->
        stringResource(R.string.source_separation_preset_generic_stem_pending)
}
