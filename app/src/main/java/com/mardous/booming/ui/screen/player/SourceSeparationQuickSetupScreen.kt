package com.mardous.booming.ui.screen.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import com.mardous.booming.separation.setup.LocalSeparationBlockerCode
import com.mardous.booming.separation.setup.LocalSeparationDegradationCode
import com.mardous.booming.separation.setup.LocalSeparationIssue
import com.mardous.booming.separation.setup.LocalSeparationReadiness
import com.mardous.booming.separation.setup.LocalSeparationReadinessState
import com.mardous.booming.separation.setup.SourceSeparationQuickSetupAction
import com.mardous.booming.separation.setup.SourceSeparationQuickSetupItemState
import com.mardous.booming.separation.setup.SourceSeparationQuickSetupPlan
import com.mardous.booming.separation.setup.SourceSeparationQuickSetupPlanItem
import com.mardous.booming.separation.setup.SourceSeparationQuickSetupProgress
import com.mardous.booming.separation.setup.SourceSeparationQuickSetupRequirement
import com.mardous.booming.separation.setup.SourceSeparationQuickSetupTerminalStatus
import com.mardous.booming.ui.component.compose.BottomSheetDialogSurface
import com.mardous.booming.ui.component.compose.TitledCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SourceSeparationQuickSetupSheet(
    state: SourceSeparationQuickSetupUiState,
    onBack: () -> Unit,
    onToggleItem: (String, Boolean) -> Unit,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onOpenRuntimeManagement: () -> Unit,
    onOpenModelManagement: () -> Unit,
) {
    var showStatusDetails by rememberSaveable { mutableStateOf(false) }
    if (showStatusDetails) {
        QuickSetupStatusDialog(
            readiness = state.readiness,
            errorMessage = state.errorMessage,
            analyzing = state.phase == SourceSeparationQuickSetupPhase.Analyzing,
            onDismiss = { showStatusDetails = false },
        )
    }

    BottomSheetDialogSurface {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .nestedScroll(rememberNestedScrollInteropConnection()),
        ) {
            BottomSheetDefaults.DragHandle(
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            ) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        IconButton(
                            onClick = onBack,
                            enabled = state.phase != SourceSeparationQuickSetupPhase.Installing,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_back_24dp),
                                contentDescription = stringResource(R.string.back_action),
                            )
                        }
                        Text(
                            text = stringResource(R.string.source_separation_quick_setup_title),
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { showStatusDetails = true }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_info_24dp),
                                contentDescription = stringResource(R.string.more_info_action),
                            )
                        }
                    }
                }

                if (state.phase == SourceSeparationQuickSetupPhase.Analyzing) {
                    item {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(16.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            Text(stringResource(R.string.source_separation_quick_setup_analyzing))
                        }
                    }
                }

                if (state.phase == SourceSeparationQuickSetupPhase.Reviewing) {
                    state.plan?.let { plan ->
                        if (plan.items.isEmpty()) {
                            item {
                                FinishedCard(
                                    status = SourceSeparationQuickSetupTerminalStatus.Completed,
                                    errorMessage = null,
                                    onRetry = onRetry,
                                    onOpenRuntimeManagement = onOpenRuntimeManagement,
                                    onOpenModelManagement = onOpenModelManagement,
                                )
                            }
                        } else {
                            items(
                                items = plan.items,
                                key = SourceSeparationQuickSetupPlanItem::itemId,
                            ) { item ->
                                QuickSetupItemCard(
                                    item = item,
                                    selected = item.itemId in state.selectedItemIds,
                                    state = state.itemState(item),
                                    progress = state.progress?.takeIf {
                                        it.itemId == item.itemId
                                    },
                                    onToggle = { selected ->
                                        onToggleItem(item.itemId, selected)
                                    },
                                )
                            }
                            item {
                                SetupFooter(
                                    plan = plan,
                                    selectedItemIds = state.selectedItemIds,
                                    onInstall = onInstall,
                                    onOpenRuntimeManagement = onOpenRuntimeManagement,
                                    onOpenModelManagement = onOpenModelManagement,
                                )
                            }
                        }
                    }
                }

                if (state.phase == SourceSeparationQuickSetupPhase.Installing) {
                    state.plan?.let { plan ->
                        items(
                            items = plan.items.filter {
                                it.itemId in state.selectedItemIds
                            },
                            key = SourceSeparationQuickSetupPlanItem::itemId,
                        ) { item ->
                            QuickSetupItemCard(
                                item = item,
                                selected = true,
                                state = state.itemState(item),
                                progress = state.progress?.takeIf {
                                    it.itemId == item.itemId
                                },
                                onToggle = {},
                            )
                        }
                        item {
                            OutlinedButton(
                                onClick = onCancel,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_cancel_24dp),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Text(
                                    text = stringResource(R.string.action_cancel),
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                        }
                    }
                }

                if (state.phase == SourceSeparationQuickSetupPhase.Finished) {
                    item {
                        FinishedCard(
                            status = state.result?.status,
                            errorMessage = state.errorMessage,
                            onRetry = onRetry,
                            onOpenRuntimeManagement = onOpenRuntimeManagement,
                            onOpenModelManagement = onOpenModelManagement,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickSetupStatusDialog(
    readiness: LocalSeparationReadiness?,
    errorMessage: String?,
    analyzing: Boolean,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                painter = painterResource(R.drawable.ic_info_24dp),
                contentDescription = null,
            )
        },
        title = {
            Text(stringResource(R.string.source_separation_quick_setup_status_title))
        },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.heightIn(max = 420.dp),
            ) {
                if (readiness == null) {
                    item {
                        Text(
                            text = errorMessage ?: stringResource(
                                if (analyzing) {
                                    R.string.source_separation_quick_setup_analyzing
                                } else {
                                    R.string.source_separation_quick_setup_result_failed
                                },
                            ),
                            color = if (errorMessage == null) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    }
                } else {
                    item {
                        Text(
                            text = readinessStateText(readiness.state),
                            color = when (readiness.state) {
                                LocalSeparationReadinessState.Ready ->
                                    MaterialTheme.colorScheme.primary
                                LocalSeparationReadinessState.Degraded ->
                                    MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.error
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    readiness.cpuRuntime?.let { runtime ->
                        item {
                            Text(
                                text = stringResource(
                                    R.string.source_separation_quick_setup_runtime_summary,
                                    runtime.abi,
                                    runtime.runtimeArtifactVersion,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    readiness.gpuRuntime?.let { runtime ->
                        item {
                            Text(
                                text = stringResource(
                                    R.string.source_separation_quick_setup_gpu_runtime_summary,
                                    runtime.abi,
                                    runtime.runtimeArtifactVersion,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    readiness.activeModel?.let { model ->
                        item {
                            Text(
                                text = stringResource(
                                    R.string.source_separation_quick_setup_model_summary,
                                    model.displayName,
                                    if (model.installed) {
                                        stringResource(
                                            R.string.source_separation_quick_setup_installed,
                                        )
                                    } else {
                                        stringResource(
                                            R.string.source_separation_quick_setup_missing,
                                        )
                                    },
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(readiness.blockers) { issue ->
                        IssueText(issue = issue, error = true)
                    }
                    items(readiness.degradations) { issue ->
                        IssueText(issue = issue, error = false)
                    }
                    errorMessage?.let { message ->
                        item {
                            Text(text = message, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close_action))
            }
        },
    )
}

@Composable
private fun IssueText(issue: LocalSeparationIssue, error: Boolean) {
    Text(
        text = issueText(issue),
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun QuickSetupItemCard(
    item: SourceSeparationQuickSetupPlanItem,
    selected: Boolean,
    state: SourceSeparationQuickSetupItemState,
    progress: SourceSeparationQuickSetupProgress?,
    onToggle: (Boolean) -> Unit,
) {
    var showDetails by rememberSaveable(item.itemId) { mutableStateOf(false) }
    if (showDetails) {
        QuickSetupItemDetailsDialog(
            item = item,
            state = state,
            onDismiss = { showDetails = false },
        )
    }

    TitledCard(
        title = itemTitle(item),
        modifier = Modifier.fillMaxWidth(),
        titleStartContent = {
            Checkbox(
                checked = selected,
                onCheckedChange = onToggle,
                enabled = item.requirement != SourceSeparationQuickSetupRequirement.Required &&
                    item.disabledReason == null &&
                    state != SourceSeparationQuickSetupItemState.Succeeded,
            )
        },
        titleEndContent = {
            IconButton(onClick = { showDetails = true }) {
                Icon(
                    painter = painterResource(R.drawable.ic_info_24dp),
                    contentDescription = stringResource(R.string.more_info_action),
                )
            }
        },
    ) { padding ->
        val hasDownload = item.expectedDownloadBytes > 0L
        val downloadProgress = progress?.takeIf { it.totalBytes > 0L }
        if (hasDownload || downloadProgress != null) {
            Column(
                modifier = Modifier.padding(padding),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (hasDownload) {
                    Text(
                        text = stringResource(
                            R.string.source_separation_quick_setup_size_summary,
                            item.expectedDownloadBytes.asReadableFileSize(),
                            item.expectedInstalledBytes.asReadableFileSize(),
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                downloadProgress?.let { currentProgress ->
                    LinearProgressIndicator(
                        progress = {
                            (currentProgress.downloadedBytes.toFloat() /
                                currentProgress.totalBytes).coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = stringResource(
                            R.string.source_separation_quick_setup_progress,
                            currentProgress.downloadedBytes.asReadableFileSize(),
                            currentProgress.totalBytes.asReadableFileSize(),
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickSetupItemDetailsDialog(
    item: SourceSeparationQuickSetupPlanItem,
    state: SourceSeparationQuickSetupItemState,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                painter = painterResource(R.drawable.ic_info_24dp),
                contentDescription = null,
            )
        },
        title = { Text(itemTitle(item)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = requirementText(item.requirement),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    text = itemReason(item),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (state != SourceSeparationQuickSetupItemState.Pending) {
                    Text(
                        text = itemStateText(state),
                        color = if (state == SourceSeparationQuickSetupItemState.Failed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                item.disabledReason?.let { disabledReason ->
                    Text(
                        text = if (item.action in setOf(
                                SourceSeparationQuickSetupAction.OpenRuntimeManagement,
                                SourceSeparationQuickSetupAction.OpenModelManagement,
                            )
                        ) {
                            stringResource(
                                R.string.source_separation_quick_setup_auto_repair_unavailable,
                            )
                        } else {
                            disabledReason
                        },
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close_action))
            }
        },
    )
}

@Composable
private fun SetupFooter(
    plan: SourceSeparationQuickSetupPlan,
    selectedItemIds: Set<String>,
    onInstall: () -> Unit,
    onOpenRuntimeManagement: () -> Unit,
    onOpenModelManagement: () -> Unit,
) {
    val hasSelectedAction = plan.items.any { it.itemId in selectedItemIds }
    val hasDisabledSelectedAction = plan.items.any {
        it.itemId in selectedItemIds && it.disabledReason != null
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = onInstall,
            enabled = hasSelectedAction && !hasDisabledSelectedAction,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.source_separation_quick_setup_execute_selected))
        }
        if (plan.items.any { it.action == SourceSeparationQuickSetupAction.OpenRuntimeManagement }) {
            OutlinedButton(
                onClick = onOpenRuntimeManagement,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.source_separation_manage_runtime))
            }
        }
        if (plan.items.any { it.action == SourceSeparationQuickSetupAction.OpenModelManagement }) {
            OutlinedButton(
                onClick = onOpenModelManagement,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.source_separation_manage_model))
            }
        }
    }
}

@Composable
private fun FinishedCard(
    status: SourceSeparationQuickSetupTerminalStatus?,
    errorMessage: String?,
    onRetry: () -> Unit,
    onOpenRuntimeManagement: () -> Unit,
    onOpenModelManagement: () -> Unit,
) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = resultText(status),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                Icon(
                    painter = painterResource(R.drawable.ic_update_24dp),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(R.string.source_separation_quick_setup_retry),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            OutlinedButton(
                onClick = onOpenRuntimeManagement,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.source_separation_manage_runtime))
            }
            OutlinedButton(onClick = onOpenModelManagement, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.source_separation_manage_model))
            }
        }
    }
}

@Composable
private fun readinessStateText(state: LocalSeparationReadinessState): String = when (state) {
    LocalSeparationReadinessState.Ready ->
        stringResource(R.string.source_separation_quick_setup_state_ready)
    LocalSeparationReadinessState.Degraded ->
        stringResource(R.string.source_separation_quick_setup_state_degraded)
    LocalSeparationReadinessState.NeedsSetup ->
        stringResource(R.string.source_separation_quick_setup_state_needs_setup)
    LocalSeparationReadinessState.RepairRequired ->
        stringResource(R.string.source_separation_quick_setup_state_repair_required)
    LocalSeparationReadinessState.Unsupported ->
        stringResource(R.string.source_separation_quick_setup_state_unsupported)
}

@Composable
private fun requirementText(requirement: SourceSeparationQuickSetupRequirement): String = when (requirement) {
    SourceSeparationQuickSetupRequirement.Required ->
        stringResource(R.string.source_separation_quick_setup_required)
    SourceSeparationQuickSetupRequirement.Recommended ->
        stringResource(R.string.source_separation_quick_setup_recommended)
    SourceSeparationQuickSetupRequirement.Optional ->
        stringResource(R.string.source_separation_quick_setup_optional)
}

@Composable
private fun itemStateText(state: SourceSeparationQuickSetupItemState): String = when (state) {
    SourceSeparationQuickSetupItemState.Pending ->
        stringResource(R.string.source_separation_quick_setup_item_pending)
    SourceSeparationQuickSetupItemState.Running ->
        stringResource(R.string.source_separation_quick_setup_item_running)
    SourceSeparationQuickSetupItemState.Succeeded ->
        stringResource(R.string.source_separation_quick_setup_item_succeeded)
    SourceSeparationQuickSetupItemState.Failed ->
        stringResource(R.string.source_separation_quick_setup_item_failed)
    SourceSeparationQuickSetupItemState.Skipped ->
        stringResource(R.string.source_separation_quick_setup_item_skipped)
}

@Composable
private fun resultText(status: SourceSeparationQuickSetupTerminalStatus?): String = when (status) {
    SourceSeparationQuickSetupTerminalStatus.Completed ->
        stringResource(R.string.source_separation_quick_setup_result_completed)
    SourceSeparationQuickSetupTerminalStatus.PartiallyCompleted ->
        stringResource(R.string.source_separation_quick_setup_result_partial)
    SourceSeparationQuickSetupTerminalStatus.Blocked ->
        stringResource(R.string.source_separation_quick_setup_result_blocked)
    SourceSeparationQuickSetupTerminalStatus.Canceled ->
        stringResource(R.string.source_separation_quick_setup_result_canceled)
    SourceSeparationQuickSetupTerminalStatus.Failed ->
        stringResource(R.string.source_separation_quick_setup_result_failed)
    null -> stringResource(R.string.source_separation_quick_setup_result_failed)
}

@Composable
private fun issueText(issue: LocalSeparationIssue): String = when (issue.code) {
    LocalSeparationBlockerCode.RuntimeInventoryUnavailable ->
        stringResource(R.string.source_separation_quick_setup_issue_runtime_inventory)
    LocalSeparationBlockerCode.UnsupportedProcessAbi ->
        stringResource(R.string.source_separation_quick_setup_issue_unsupported_abi)
    LocalSeparationBlockerCode.UnsupportedRuntimeApi ->
        stringResource(R.string.source_separation_quick_setup_issue_unsupported_api)
    LocalSeparationBlockerCode.MissingCpuRuntime ->
        stringResource(R.string.source_separation_quick_setup_issue_cpu_missing)
    LocalSeparationBlockerCode.InvalidCpuRuntime ->
        stringResource(R.string.source_separation_quick_setup_issue_cpu_invalid)
    LocalSeparationBlockerCode.PendingCpuRuntimeActivation,
    LocalSeparationDegradationCode.RuntimeActivationPending,
    -> stringResource(R.string.source_separation_quick_setup_issue_cpu_activation_pending)
    LocalSeparationBlockerCode.PendingCpuRuntimeDeletion,
    LocalSeparationDegradationCode.RuntimeDeletionPending,
    -> stringResource(R.string.source_separation_quick_setup_issue_cpu_deletion_pending)
    LocalSeparationBlockerCode.MissingGpuRuntime,
    LocalSeparationDegradationCode.GpuRuntimeMissing,
    -> stringResource(R.string.source_separation_quick_setup_issue_gpu_missing)
    LocalSeparationBlockerCode.InvalidGpuRuntime ->
        stringResource(R.string.source_separation_quick_setup_issue_gpu_invalid)
    LocalSeparationBlockerCode.PendingGpuRuntimeActivation,
    LocalSeparationDegradationCode.GpuRuntimeActivationPending,
    -> stringResource(R.string.source_separation_quick_setup_issue_gpu_activation_pending)
    LocalSeparationBlockerCode.PendingGpuRuntimeDeletion,
    LocalSeparationDegradationCode.GpuRuntimeDeletionPending,
    -> stringResource(R.string.source_separation_quick_setup_issue_gpu_deletion_pending)
    LocalSeparationBlockerCode.NoActiveModel ->
        stringResource(R.string.source_separation_quick_setup_issue_model_unselected)
    LocalSeparationBlockerCode.PendingActiveModel ->
        stringResource(R.string.source_separation_quick_setup_issue_model_selection_pending)
    LocalSeparationBlockerCode.ActiveModelNotInstalled ->
        stringResource(R.string.source_separation_quick_setup_issue_model_missing)
    LocalSeparationBlockerCode.ActiveModelIdentityMismatch ->
        stringResource(R.string.source_separation_quick_setup_issue_model_identity)
    LocalSeparationBlockerCode.ActiveModelProfileMissing ->
        stringResource(R.string.source_separation_quick_setup_issue_model_profile)
    LocalSeparationBlockerCode.ActiveModelContractMismatch ->
        stringResource(R.string.source_separation_quick_setup_issue_model_contract_mismatch)
    LocalSeparationBlockerCode.ActiveModelContractInvalid ->
        stringResource(R.string.source_separation_quick_setup_issue_model_contract_invalid)
    LocalSeparationBlockerCode.ActiveModelDeviceUnsupported ->
        stringResource(R.string.source_separation_quick_setup_issue_model_device)
    LocalSeparationDegradationCode.GpuInventoryUnavailable ->
        stringResource(R.string.source_separation_quick_setup_issue_gpu_inventory)
    else -> issue.detail
}

@Composable
private fun itemTitle(item: SourceSeparationQuickSetupPlanItem): String = when (item.action) {
    SourceSeparationQuickSetupAction.OpenRuntimeManagement,
    SourceSeparationQuickSetupAction.OpenModelManagement,
    -> stringResource(R.string.source_separation_quick_setup_manual_review)
    SourceSeparationQuickSetupAction.InstallRuntime,
    SourceSeparationQuickSetupAction.RepairRuntime,
    SourceSeparationQuickSetupAction.ActivatePendingRuntime,
    SourceSeparationQuickSetupAction.InstallGpuRuntime,
    SourceSeparationQuickSetupAction.RepairGpuRuntime,
    SourceSeparationQuickSetupAction.ActivatePendingGpuRuntime,
    SourceSeparationQuickSetupAction.ConfigureGpuRuntime,
    SourceSeparationQuickSetupAction.InstallAndSelectModel,
    -> stringResource(
        if (item.expectedDownloadBytes > 0L) {
            R.string.source_separation_quick_setup_install_and_use
        } else {
            R.string.source_separation_quick_setup_use_model
        },
        itemDisplayName(item),
    )
    else -> item.title
}

@Composable
private fun itemDisplayName(item: SourceSeparationQuickSetupPlanItem): String = when (item.action) {
    SourceSeparationQuickSetupAction.InstallRuntime,
    SourceSeparationQuickSetupAction.RepairRuntime,
    SourceSeparationQuickSetupAction.ActivatePendingRuntime,
    -> stringResource(R.string.source_separation_quick_setup_cpu_runtime_title)
    SourceSeparationQuickSetupAction.InstallGpuRuntime,
    SourceSeparationQuickSetupAction.RepairGpuRuntime,
    SourceSeparationQuickSetupAction.ActivatePendingGpuRuntime,
    SourceSeparationQuickSetupAction.ConfigureGpuRuntime,
    -> stringResource(R.string.source_separation_quick_setup_gpu_runtime_title)
    else -> item.title
}

@Composable
private fun itemReason(item: SourceSeparationQuickSetupPlanItem): String = when (item.action) {
    SourceSeparationQuickSetupAction.InstallRuntime ->
        stringResource(R.string.source_separation_quick_setup_reason_install_cpu)
    SourceSeparationQuickSetupAction.RepairRuntime ->
        stringResource(R.string.source_separation_quick_setup_reason_repair_cpu)
    SourceSeparationQuickSetupAction.ActivatePendingRuntime ->
        stringResource(R.string.source_separation_quick_setup_reason_activate_cpu)
    SourceSeparationQuickSetupAction.InstallGpuRuntime ->
        stringResource(R.string.source_separation_quick_setup_reason_install_gpu)
    SourceSeparationQuickSetupAction.RepairGpuRuntime ->
        stringResource(R.string.source_separation_quick_setup_reason_repair_gpu)
    SourceSeparationQuickSetupAction.ActivatePendingGpuRuntime ->
        stringResource(R.string.source_separation_quick_setup_reason_activate_gpu)
    SourceSeparationQuickSetupAction.ConfigureGpuRuntime ->
        stringResource(R.string.source_separation_quick_setup_reason_configure_gpu)
    SourceSeparationQuickSetupAction.InstallAndSelectModel -> if (
        item.expectedDownloadBytes > 0L
    ) {
        listOf(
            stringResource(R.string.source_separation_quick_setup_reason_install_model),
            stringResource(R.string.source_separation_quick_setup_reason_select_model),
        ).joinToString(" ")
    } else {
        stringResource(R.string.source_separation_quick_setup_reason_select_model)
    }
    SourceSeparationQuickSetupAction.OpenRuntimeManagement,
    SourceSeparationQuickSetupAction.OpenModelManagement,
    -> stringResource(R.string.source_separation_quick_setup_reason_manual_review)
    else -> item.reason
}
