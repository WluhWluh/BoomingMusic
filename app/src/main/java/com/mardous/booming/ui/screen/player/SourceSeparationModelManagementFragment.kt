package com.mardous.booming.ui.screen.player

import android.app.Dialog
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.mardous.booming.BuildConfig
import com.mardous.booming.R
import com.mardous.booming.extensions.files.asReadableFileSize
import com.mardous.booming.extensions.isLandscape
import com.mardous.booming.extensions.utilities.dateStr
import com.mardous.booming.separation.model.SourceSeparationModelSource
import com.mardous.booming.ui.component.compose.BottomSheetDialogSurface
import com.mardous.booming.ui.component.compose.TitledCard
import com.mardous.booming.ui.theme.BoomingMusicTheme
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.util.Locale

class SourceSeparationModelManagementFragment : BottomSheetDialogFragment() {

    private val viewModel: PlayerViewModel by activityViewModel()
    private val presetViewModel: SourceSeparationPresetManagementViewModel by viewModel()
    private val importOnnxModelLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        viewModel.importSourceSeparationModel(uri)
    }
    private val importTfliteModelLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        presetViewModel.beginImport(uri)
    }
    private val importTfliteSidecarLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        presetViewModel.importSidecar(uri)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        if (isLandscape()) {
            (dialog as? BottomSheetDialog)?.behavior?.state = BottomSheetBehavior.STATE_EXPANDED
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
            )
            setContent {
                BoomingMusicTheme {
                    if (BuildConfig.DEBUG) {
                        val state by presetViewModel.state.collectAsState()
                        SourceSeparationPresetManagementSheet(
                            state = state,
                            onDownload = presetViewModel::download,
                            onCancelDownload = presetViewModel::cancelDownload,
                            onUse = presetViewModel::requestUse,
                            onDelete = presetViewModel::delete,
                            onConfirmExperimental = presetViewModel::confirmExperimentalUse,
                            onDismissExperimental = presetViewModel::dismissExperimentalUse,
                            onClearError = presetViewModel::clearError,
                            onClearRestoredModelTarget =
                                presetViewModel::clearRestoredModelTarget,
                            onRefresh = presetViewModel::refresh,
                            onImportModel = {
                                importTfliteModelLauncher.launch(
                                    arrayOf(
                                        "application/octet-stream",
                                        "application/x-tflite",
                                        "application/*",
                                        "*/*",
                                    ),
                                )
                            },
                            onImportSidecar = {
                                importTfliteSidecarLauncher.launch(
                                    arrayOf(
                                        "application/json",
                                        "text/plain",
                                        "application/*",
                                        "*/*",
                                    ),
                                )
                            },
                            onStartManualProfile = presetViewModel::startManualProfile,
                            onSaveManualProfile = presetViewModel::saveManualProfile,
                            onCancelManualProfile = presetViewModel::cancelManualProfile,
                            onRetryImport = presetViewModel::retryImport,
                            onDiscardImport = presetViewModel::discardImport,
                            onDismissImportSuccess = presetViewModel::dismissImportSuccess,
                            onUseImported = presetViewModel::requestUseImported,
                            onDeleteImported = presetViewModel::deleteImported,
                            onShowCatalogDetails = presetViewModel::showCatalogDetails,
                            onShowImportedDetails = presetViewModel::showImportedDetails,
                            onDismissModelDetails = presetViewModel::dismissModelDetails,
                            onEditCustomProfile = presetViewModel::editCustomProfile,
                            onSaveCustomProfileRevision =
                                presetViewModel::saveCustomProfileRevision,
                            onCancelCustomProfileEdit =
                                presetViewModel::cancelCustomProfileEdit,
                            onUseCustomProfile = presetViewModel::useCustomProfile,
                            onDeleteCustomProfile = presetViewModel::deleteCustomProfile,
                        )
                    } else {
                        val state by viewModel.sourceSeparationModelStateFlow.collectAsState()
                        SourceSeparationModelManagementSheet(
                            state = state,
                            onImport = {
                                importOnnxModelLauncher.launch(
                                    arrayOf(
                                        "application/octet-stream",
                                        "application/x-onnx",
                                        "application/*",
                                        "*/*",
                                    ),
                                )
                            },
                            onDownloadPreset = viewModel::downloadPresetSourceSeparationModel,
                            onDownloadUrl = viewModel::downloadSourceSeparationModel,
                            onDelete = viewModel::deleteSourceSeparationModel,
                            onRefresh = viewModel::refreshSourceSeparationModelState,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceSeparationModelManagementSheet(
    state: SourceSeparationModelUiState,
    onImport: () -> Unit,
    onDownloadPreset: () -> Unit,
    onDownloadUrl: (String) -> Unit,
    onDelete: () -> Unit,
    onRefresh: () -> Unit,
) {
    val context = LocalContext.current
    var customUrl by rememberSaveable { mutableStateOf("") }

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
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            ) {
                item {
                    Text(
                        text = stringResource(R.string.source_separation_manage_model),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }

                item {
                    TitledCard(
                        title = stringResource(R.string.source_separation_model_status_title),
                        modifier = Modifier.fillMaxWidth(),
                    ) { cardPadding ->
                        Column(
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(cardPadding),
                        ) {
                            SourceSeparationModelStatusHeader(state)

                            if (state.busy) {
                                val progressFraction = state.downloadProgressFraction
                                if (progressFraction != null) {
                                    LinearProgressIndicator(
                                        progress = { progressFraction },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                } else {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                }
                                Text(
                                    text = when {
                                        state.importing ->
                                            stringResource(R.string.source_separation_model_importing)
                                        state.downloading ->
                                            downloadProgressText(state)
                                        else ->
                                            stringResource(R.string.source_separation_model_deleting)
                                    },
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }

                            state.errorMessage?.takeIf { it.isNotBlank() }?.let { message ->
                                Text(
                                    text = stringResource(
                                        R.string.source_separation_model_error,
                                        message,
                                    ),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }

                            if (state.available) {
                                HorizontalDivider()
                                SourceSeparationModelMetadataRow(
                                    label = stringResource(R.string.source_separation_model_file_label),
                                    value = state.importedDisplayName ?: state.fileName,
                                )
                                SourceSeparationModelMetadataRow(
                                    label = stringResource(R.string.source_separation_cache_size_label),
                                    value = state.sizeBytes.asReadableFileSize(),
                                )
                                SourceSeparationModelMetadataRow(
                                    label = stringResource(R.string.source_separation_model_source_label),
                                    value = state.source.label(),
                                )
                                state.updatedAtEpochMs?.let { updatedAt ->
                                    SourceSeparationModelMetadataRow(
                                        label = stringResource(R.string.source_separation_cache_updated_label),
                                        value = context.dateStr(updatedAt),
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    TitledCard(
                        title = stringResource(R.string.source_separation_model_hash_title),
                        modifier = Modifier.fillMaxWidth(),
                    ) { cardPadding ->
                        Column(
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(cardPadding),
                        ) {
                            SourceSeparationModelHashStatus(state)
                            SourceSeparationModelMetadataRow(
                                label = stringResource(R.string.source_separation_model_expected_hash_label),
                                value = state.expectedSha256,
                            )
                            SourceSeparationModelMetadataRow(
                                label = stringResource(R.string.source_separation_model_actual_hash_label),
                                value = state.actualSha256
                                    ?: stringResource(R.string.source_separation_model_hash_unavailable),
                            )
                        }
                    }
                }

                item {
                    TitledCard(
                        title = stringResource(R.string.source_separation_model_acquisition_title),
                        modifier = Modifier.fillMaxWidth(),
                    ) { cardPadding ->
                        Column(
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(cardPadding),
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.source_separation_model_preset_url,
                                    state.presetUrl,
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Button(
                                    onClick = onDownloadPreset,
                                    enabled = !state.busy,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_download_24dp),
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Text(
                                        text = stringResource(
                                            R.string.source_separation_download_preset_model,
                                        ),
                                        modifier = Modifier.padding(start = 8.dp),
                                    )
                                }

                                OutlinedButton(
                                    onClick = onRefresh,
                                    enabled = !state.busy,
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_update_24dp),
                                        contentDescription = stringResource(R.string.refresh_action),
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }

                            OutlinedTextField(
                                value = customUrl,
                                onValueChange = { customUrl = it },
                                label = {
                                    Text(
                                        text = stringResource(
                                            R.string.source_separation_custom_model_url,
                                        ),
                                    )
                                },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                enabled = !state.busy,
                                modifier = Modifier.fillMaxWidth(),
                            )

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                OutlinedButton(
                                    onClick = { onDownloadUrl(customUrl) },
                                    enabled = !state.busy && customUrl.isNotBlank(),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_download_24dp),
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Text(
                                        text = stringResource(
                                            R.string.source_separation_download_custom_model,
                                        ),
                                        modifier = Modifier.padding(start = 8.dp),
                                    )
                                }

                                OutlinedButton(
                                    onClick = onImport,
                                    enabled = !state.busy,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_file_open_24dp),
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Text(
                                        text = stringResource(R.string.source_separation_import_model),
                                        modifier = Modifier.padding(start = 8.dp),
                                    )
                                }

                            }

                            if (state.available) {
                                OutlinedButton(
                                    onClick = onDelete,
                                    enabled = !state.busy,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_delete_24dp),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Text(
                                        text = stringResource(R.string.source_separation_delete_model),
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.padding(start = 8.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceSeparationModelStatusHeader(state: SourceSeparationModelUiState) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(16.dp),
        ) {
            Icon(
                painter = painterResource(
                    if (state.available) R.drawable.ic_check_24dp else R.drawable.ic_info_24dp,
                ),
                contentDescription = null,
                tint = if (state.available) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.secondary
                },
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.modelName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (state.available) {
                        stringResource(R.string.source_separation_model_ready)
                    } else {
                        stringResource(R.string.source_separation_model_missing)
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun SourceSeparationModelHashStatus(state: SourceSeparationModelUiState) {
    val text = when (state.hashMatchesExpected) {
        true -> stringResource(R.string.source_separation_model_hash_matches)
        false -> stringResource(R.string.source_separation_model_hash_mismatch)
        null -> stringResource(R.string.source_separation_model_hash_not_checked)
    }
    val color = when (state.hashMatchesExpected) {
        true -> MaterialTheme.colorScheme.primary
        false -> MaterialTheme.colorScheme.error
        null -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun SourceSeparationModelMetadataRow(
    label: String,
    value: String,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(0.35f),
        )
        Text(
            text = value,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(0.65f),
        )
    }
}

@Composable
private fun SourceSeparationModelSource.label(): String {
    return when (this) {
        SourceSeparationModelSource.Imported ->
            stringResource(R.string.source_separation_model_source_imported)
        SourceSeparationModelSource.PresetDownload ->
            stringResource(R.string.source_separation_model_source_preset_download)
        SourceSeparationModelSource.CustomDownload ->
            stringResource(R.string.source_separation_model_source_custom_download)
        SourceSeparationModelSource.LegacyLocal ->
            stringResource(R.string.source_separation_model_source_legacy_local)
        SourceSeparationModelSource.Unknown ->
            stringResource(R.string.source_separation_model_source_unknown)
    }
}

@Composable
private fun downloadProgressText(state: SourceSeparationModelUiState): String {
    val base = if (state.downloadUsingMirror) {
        stringResource(R.string.source_separation_model_downloading_mirror)
    } else {
        stringResource(R.string.source_separation_model_downloading)
    }
    val body = when {
        state.downloadProgressFraction != null -> {
            val fraction = state.downloadProgressFraction ?: 0f
            val percent = (fraction * 100f).coerceIn(0f, 100f)
            val downloaded = state.downloadProgressBytes.asReadableFileSize()
            val total = state.downloadTotalBytes?.asReadableFileSize()
            if (total != null) {
                String.format(Locale.US, "%s %.0f%% (%s / %s)", base, percent, downloaded, total)
            } else {
                String.format(Locale.US, "%s %.0f%% (%s)", base, percent, downloaded)
            }
        }
        state.downloadProgressBytes > 0L -> {
            String.format(
                Locale.US,
                "%s (%s)",
                base,
                state.downloadProgressBytes.asReadableFileSize(),
            )
        }
        else -> base
    }
    val message = state.downloadMessage
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.takeUnless { it.isRedundantDownloadMessage(base, state.downloadUsingMirror) }
    return message?.let { "$body · $it" } ?: body
}

private fun String.isRedundantDownloadMessage(
    base: String,
    usingMirror: Boolean,
): Boolean {
    val normalizedMessage = normalizeDownloadMessage()
    val redundantMessages = buildSet {
        add(base.normalizeDownloadMessage())
        add("Downloading model...".normalizeDownloadMessage())
        if (usingMirror) {
            add("Downloading model from mirror...".normalizeDownloadMessage())
            add("Downloading model via mirror...".normalizeDownloadMessage())
        }
    }
    return normalizedMessage in redundantMessages
}

private fun String.normalizeDownloadMessage(): String {
    return lowercase(Locale.US)
        .replace("...", "")
        .replace("…", "")
        .trim()
        .trimEnd('.')
}
