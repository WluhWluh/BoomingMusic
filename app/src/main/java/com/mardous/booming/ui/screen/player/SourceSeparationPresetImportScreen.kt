package com.mardous.booming.ui.screen.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.mardous.booming.R
import com.mardous.booming.extensions.files.asReadableFileSize
import com.mardous.booming.separation.model.preset.SourceSeparationManualModelProfileDraft
import com.mardous.booming.separation.model.preset.SourceSeparationManualModelStem
import com.mardous.booming.separation.model.preset.SourceSeparationPendingModelImport

@Composable
internal fun SourceSeparationPresetImportDialogs(
    importState: SourceSeparationPresetImportUiState,
    onImportSidecar: () -> Unit,
    onStartManualProfile: () -> Unit,
    onSaveManualProfile: (SourceSeparationManualModelProfileDraft) -> Unit,
    onCancelManualProfile: () -> Unit,
    onRetry: () -> Unit,
    onDiscard: () -> Unit,
    onDismissSuccess: () -> Unit,
) {
    when (importState) {
        SourceSeparationPresetImportUiState.Idle -> Unit

        SourceSeparationPresetImportUiState.PreparingModel -> {
            ImportProgressDialog(
                text = stringResource(R.string.source_separation_preset_import_preparing_model),
            )
        }

        is SourceSeparationPresetImportUiState.AwaitingMetadata -> {
            AwaitingMetadataDialog(
                pending = importState.pending,
                onImportSidecar = onImportSidecar,
                onStartManualProfile = onStartManualProfile,
                onDiscard = onDiscard,
            )
        }

        is SourceSeparationPresetImportUiState.PreparingSidecar -> {
            ImportProgressDialog(
                text = stringResource(R.string.source_separation_preset_import_preparing_sidecar),
            )
        }

        is SourceSeparationPresetImportUiState.EditingManualProfile -> {
            ManualProfileDialog(
                pending = importState.pending,
                initialDraft = importState.initialDraft,
                onSave = onSaveManualProfile,
                onCancel = onCancelManualProfile,
            )
        }

        is SourceSeparationPresetImportUiState.SavingManualProfile -> {
            ImportProgressDialog(
                text = stringResource(R.string.source_separation_preset_import_saving_profile),
            )
        }

        is SourceSeparationPresetImportUiState.Failed -> {
            ImportFailureDialog(
                stage = importState.stage,
                canRetry = importState.pending != null,
                onRetry = onRetry,
                onDiscard = onDiscard,
            )
        }

        is SourceSeparationPresetImportUiState.Success -> {
            AlertDialog(
                onDismissRequest = onDismissSuccess,
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_check_24dp),
                        contentDescription = null,
                    )
                },
                title = {
                    Text(stringResource(R.string.source_separation_preset_import_success_title))
                },
                text = {
                    Text(
                        stringResource(
                            R.string.source_separation_preset_import_success_message,
                            importState.displayName,
                        ),
                    )
                },
                confirmButton = {
                    Button(onClick = onDismissSuccess) {
                        Text(stringResource(android.R.string.ok))
                    }
                },
            )
        }
    }
}

@Composable
private fun ImportProgressDialog(text: String) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.source_separation_preset_import_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(text)
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun AwaitingMetadataDialog(
    pending: SourceSeparationPendingModelImport,
    onImportSidecar: () -> Unit,
    onStartManualProfile: () -> Unit,
    onDiscard: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDiscard,
        icon = {
            Icon(
                painter = painterResource(R.drawable.ic_info_24dp),
                contentDescription = null,
            )
        },
        title = { Text(stringResource(R.string.source_separation_preset_import_metadata_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.source_separation_preset_import_metadata_message))
                PendingImportIdentity(pending)
            }
        },
        confirmButton = {
            Button(onClick = onImportSidecar) {
                Text(stringResource(R.string.source_separation_preset_import_sidecar))
            }
        },
        dismissButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = onStartManualProfile) {
                    Text(stringResource(R.string.source_separation_preset_import_manual_profile))
                }
                TextButton(onClick = onDiscard) {
                    Text(stringResource(R.string.source_separation_preset_import_discard))
                }
            }
        },
    )
}

@Composable
private fun ImportFailureDialog(
    stage: SourceSeparationPresetImportFailureStage,
    canRetry: Boolean,
    onRetry: () -> Unit,
    onDiscard: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDiscard,
        icon = {
            Icon(
                painter = painterResource(R.drawable.ic_info_24dp),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = { Text(stringResource(R.string.source_separation_preset_import_failed_title)) },
        text = { Text(stage.message()) },
        confirmButton = {
            Button(onClick = if (canRetry) onRetry else onDiscard) {
                Text(
                    stringResource(
                        if (canRetry) {
                            R.string.source_separation_preset_import_retry
                        } else {
                            R.string.close_action
                        },
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDiscard) {
                Text(stringResource(R.string.source_separation_preset_import_discard))
            }
        },
    )
}

@Composable
private fun SourceSeparationPresetImportFailureStage.message(): String = when (this) {
    SourceSeparationPresetImportFailureStage.Model ->
        stringResource(R.string.source_separation_preset_import_model_failed_message)
    SourceSeparationPresetImportFailureStage.Sidecar ->
        stringResource(R.string.source_separation_preset_import_sidecar_failed_message)
    SourceSeparationPresetImportFailureStage.ManualProfile ->
        stringResource(R.string.source_separation_preset_import_profile_failed_message)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualProfileDialog(
    pending: SourceSeparationPendingModelImport,
    initialDraft: SourceSeparationManualModelProfileDraft,
    onSave: (SourceSeparationManualModelProfileDraft) -> Unit,
    onCancel: () -> Unit,
) {
    var modelId by rememberSaveable(pending.sha256) { mutableStateOf(initialDraft.modelId) }
    var displayName by rememberSaveable(pending.sha256) { mutableStateOf(initialDraft.displayName) }
    var inputTensorName by rememberSaveable(pending.sha256) {
        mutableStateOf(initialDraft.inputTensorName)
    }
    var outputTensorName by rememberSaveable(pending.sha256) {
        mutableStateOf(initialDraft.outputTensorName)
    }
    var sampleRate by rememberSaveable(pending.sha256) { mutableStateOf(initialDraft.sampleRate.toString()) }
    var nFft by rememberSaveable(pending.sha256) { mutableStateOf(initialDraft.nFft.toString()) }
    var hopLength by rememberSaveable(pending.sha256) { mutableStateOf(initialDraft.hopLength.toString()) }
    var dimF by rememberSaveable(pending.sha256) { mutableStateOf(initialDraft.dimF.toString()) }
    var dimTPower by rememberSaveable(pending.sha256) { mutableStateOf(initialDraft.dimTPower.toString()) }
    var modelOutputScale by rememberSaveable(pending.sha256) {
        mutableStateOf(initialDraft.modelOutputScale.toString())
    }
    var outputStemName by rememberSaveable(pending.sha256) {
        mutableStateOf(initialDraft.modelOutputStem.name)
    }

    val outputStem = SourceSeparationManualModelStem.entries.singleOrNull {
        it.name == outputStemName
    } ?: SourceSeparationManualModelStem.Vocals
    val draft = manualDraftOrNull(
        modelId = modelId,
        displayName = displayName,
        inputTensorName = inputTensorName,
        outputTensorName = outputTensorName,
        sampleRate = sampleRate,
        nFft = nFft,
        hopLength = hopLength,
        dimF = dimF,
        dimTPower = dimTPower,
        modelOutputScale = modelOutputScale,
        outputStem = outputStem,
    )?.takeIf { candidate ->
        runCatching { candidate.toProfile(pending) }.isSuccess
    }

    Dialog(onDismissRequest = onCancel) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(24.dp),
                ) {
                    Text(
                        text = stringResource(R.string.source_separation_preset_import_manual_title),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        text = stringResource(
                            R.string.source_separation_preset_import_quality_unverified,
                        ),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    PendingImportIdentity(pending)
                }
                HorizontalDivider()
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
                ) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            ManualProfileTextField(
                                value = modelId,
                                onValueChange = { modelId = it },
                                label = stringResource(
                                    R.string.source_separation_preset_import_model_id,
                                ),
                            )
                            ManualProfileTextField(
                                value = displayName,
                                onValueChange = { displayName = it },
                                label = stringResource(
                                    R.string.source_separation_preset_import_display_name,
                                ),
                            )
                            ManualProfileTextField(
                                value = inputTensorName,
                                onValueChange = { inputTensorName = it },
                                label = stringResource(
                                    R.string.source_separation_preset_import_input_tensor,
                                ),
                            )
                            ManualProfileTextField(
                                value = outputTensorName,
                                onValueChange = { outputTensorName = it },
                                label = stringResource(
                                    R.string.source_separation_preset_import_output_tensor,
                                ),
                            )
                            ManualProfileTextField(
                                value = sampleRate,
                                onValueChange = { sampleRate = it },
                                label = stringResource(
                                    R.string.source_separation_preset_import_sample_rate,
                                ),
                                keyboardType = KeyboardType.Number,
                            )
                            ManualProfileTextField(
                                value = nFft,
                                onValueChange = { nFft = it },
                                label = stringResource(R.string.source_separation_preset_import_fft_size),
                                keyboardType = KeyboardType.Number,
                            )
                            ManualProfileTextField(
                                value = hopLength,
                                onValueChange = { hopLength = it },
                                label = stringResource(R.string.source_separation_preset_import_hop_length),
                                keyboardType = KeyboardType.Number,
                            )
                            ManualProfileTextField(
                                value = dimF,
                                onValueChange = { dimF = it },
                                label = stringResource(R.string.source_separation_preset_import_dim_f),
                                keyboardType = KeyboardType.Number,
                            )
                            ManualProfileTextField(
                                value = dimTPower,
                                onValueChange = { dimTPower = it },
                                label = stringResource(
                                    R.string.source_separation_preset_import_dim_t_power,
                                ),
                                keyboardType = KeyboardType.Number,
                            )
                            ManualProfileTextField(
                                value = modelOutputScale,
                                onValueChange = { modelOutputScale = it },
                                label = stringResource(
                                    R.string.source_separation_preset_import_output_scale,
                                ),
                                keyboardType = KeyboardType.Decimal,
                            )
                            Text(
                                text = stringResource(
                                    R.string.source_separation_preset_import_output_stem,
                                ),
                                style = MaterialTheme.typography.labelLarge,
                            )
                            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                SegmentedButton(
                                    selected = outputStem == SourceSeparationManualModelStem.Vocals,
                                    onClick = {
                                        outputStemName = SourceSeparationManualModelStem.Vocals.name
                                    },
                                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(stringResource(R.string.source_separation_blend_vocals))
                                }
                                SegmentedButton(
                                    selected = outputStem == SourceSeparationManualModelStem.Instrumental,
                                    onClick = {
                                        outputStemName = SourceSeparationManualModelStem.Instrumental.name
                                    },
                                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(stringResource(R.string.source_separation_blend_instrumental))
                                }
                            }
                        }
                    }
                }
                HorizontalDivider()
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) {
                    TextButton(onClick = onCancel) {
                        Text(stringResource(android.R.string.cancel))
                    }
                    Button(
                        onClick = { draft?.let(onSave) },
                        enabled = draft != null,
                    ) {
                        Text(stringResource(R.string.source_separation_preset_import_save_profile))
                    }
                }
            }
        }
    }
}

@Composable
private fun PendingImportIdentity(pending: SourceSeparationPendingModelImport) {
    Text(
        text = stringResource(
            R.string.source_separation_preset_import_identity,
            pending.fileName,
            pending.byteSize.asReadableFileSize(),
            pending.sha256,
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun ManualProfileTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun manualDraftOrNull(
    modelId: String,
    displayName: String,
    inputTensorName: String,
    outputTensorName: String,
    sampleRate: String,
    nFft: String,
    hopLength: String,
    dimF: String,
    dimTPower: String,
    modelOutputScale: String,
    outputStem: SourceSeparationManualModelStem,
): SourceSeparationManualModelProfileDraft? {
    val parsedSampleRate = sampleRate.toIntOrNull() ?: return null
    val parsedNfFt = nFft.toIntOrNull() ?: return null
    val parsedHopLength = hopLength.toIntOrNull() ?: return null
    val parsedDimF = dimF.toIntOrNull() ?: return null
    val parsedDimTPower = dimTPower.toIntOrNull() ?: return null
    val parsedOutputScale = modelOutputScale.toDoubleOrNull() ?: return null
    if (modelId.isBlank() || displayName.isBlank() ||
        inputTensorName.isBlank() || outputTensorName.isBlank()
    ) return null
    return SourceSeparationManualModelProfileDraft(
        modelId = modelId,
        displayName = displayName,
        inputTensorName = inputTensorName,
        outputTensorName = outputTensorName,
        sampleRate = parsedSampleRate,
        nFft = parsedNfFt,
        hopLength = parsedHopLength,
        dimF = parsedDimF,
        dimTPower = parsedDimTPower,
        modelOutputScale = parsedOutputScale,
        modelOutputStem = outputStem,
    )
}
