package com.mardous.booming.ui.screen.player

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.navigation.fragment.findNavController
import androidx.navigation.navOptions
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.mardous.booming.R
import com.mardous.booming.extensions.isLandscape
import com.mardous.booming.ui.theme.BoomingMusicTheme
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel

class SourceSeparationQuickSetupFragment : BottomSheetDialogFragment() {
    private val playerViewModel: PlayerViewModel by activityViewModel()
    private val quickSetupViewModel: SourceSeparationQuickSetupViewModel by viewModel()

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
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            BoomingMusicTheme {
                val state by quickSetupViewModel.state.collectAsState()
                SourceSeparationQuickSetupSheet(
                    state = state,
                    onBack = ::dismiss,
                    onToggleItem = quickSetupViewModel::setSelected,
                    onInstall = quickSetupViewModel::installSelected,
                    onCancel = quickSetupViewModel::cancel,
                    onRetry = quickSetupViewModel::analyze,
                    onOpenRuntimeManagement = ::openRuntimeManagement,
                    onOpenModelManagement = ::openModelManagement,
                )
            }
        }
    }

    private fun openRuntimeManagement() {
        navigateReplacingQuickSetup(
            R.id.nav_source_separation_settings,
            Bundle().apply {
                putBoolean(SourceSeparationSettingsFragment.ARG_OPEN_RUNTIME_MANAGEMENT, true)
            },
        )
    }

    private fun openModelManagement() {
        navigateReplacingQuickSetup(R.id.nav_source_separation_model_management)
    }

    private fun navigateReplacingQuickSetup(destinationId: Int, arguments: Bundle? = null) {
        findNavController().navigate(
            destinationId,
            arguments,
            navOptions {
                popUpTo(R.id.nav_source_separation_quick_setup) {
                    inclusive = true
                }
            },
        )
    }

    override fun onDestroyView() {
        playerViewModel.refreshCurrentSourceSeparationCacheAvailable()
        super.onDestroyView()
    }
}
