package com.mardous.booming.ui.screen.player

import android.app.Dialog
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.mardous.booming.R
import com.mardous.booming.extensions.isLandscape
import com.mardous.booming.extensions.showToast
import com.mardous.booming.ui.theme.BoomingMusicTheme
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel

class SourceSeparationModelManagementFragment : BottomSheetDialogFragment() {

    private val playerViewModel: PlayerViewModel by activityViewModel()
    private val presetViewModel: SourceSeparationPresetManagementViewModel by viewModel()
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
    private var pendingProfileExport: SourceSeparationCustomProfileExport? = null
    private val exportCustomProfileLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        val export = pendingProfileExport.also { pendingProfileExport = null }
            ?: return@registerForActivityResult
        uri ?: return@registerForActivityResult
        val exported = runCatching {
            val output = requireNotNull(requireContext().contentResolver.openOutputStream(uri, "w"))
            output.use { it.write(export.contents.encodeToByteArray()) }
        }.isSuccess
        showToast(
            if (exported) {
                R.string.source_separation_profile_exported
            } else {
                R.string.source_separation_profile_export_failed
            },
        )
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
                        onExportCustomProfile = { profileId ->
                            presetViewModel.exportCustomProfile(profileId)?.let { export ->
                                pendingProfileExport = export
                                exportCustomProfileLauncher.launch(export.fileName)
                            }
                        },
                        onDeleteCustomProfile = presetViewModel::deleteCustomProfile,
                    )
                }
            }
        }
    }

    override fun onDestroyView() {
        playerViewModel.refreshCurrentSourceSeparationCacheAvailable()
        super.onDestroyView()
    }
}
