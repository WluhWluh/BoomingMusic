package com.mardous.booming.ui.screen.player.cover

import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import com.mardous.booming.R
import com.mardous.booming.extensions.currentFragment
import com.mardous.booming.ui.component.base.goToDestination
import com.mardous.booming.ui.screen.MainActivity
import com.mardous.booming.ui.screen.lyrics.CoverLyricsScreen
import com.mardous.booming.ui.screen.lyrics.LyricsFragment
import com.mardous.booming.ui.screen.lyrics.LyricsViewModel
import com.mardous.booming.ui.screen.player.PlayerViewModel
import com.mardous.booming.ui.theme.BoomingMusicTheme
import com.mardous.booming.util.Preferences
import com.mardous.booming.util.SOURCE_SEPARATION_QUICK_CONTROLS_VISIBLE
import org.koin.androidx.viewmodel.ext.android.activityViewModel

class CoverLyricsFragment : Fragment(), SharedPreferences.OnSharedPreferenceChangeListener {

    private val lyricsViewModel: LyricsViewModel by activityViewModel()
    private val playerViewModel: PlayerViewModel by activityViewModel()
    private val showSourceSeparationQuickControlsState =
        mutableStateOf(Preferences.sourceSeparationQuickControlsVisible)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            setContent {
                BoomingMusicTheme {
                    CoverLyricsScreen(
                        lyricsViewModel,
                        playerViewModel,
                        showSourceSeparationQuickControls =
                            showSourceSeparationQuickControlsState.value,
                        onExpandClick = {
                            if (currentFragment(R.id.fragment_container) is LyricsFragment) {
                                (activity as? MainActivity)?.collapsePanel()
                            } else {
                                goToDestination(requireActivity(), R.id.nav_lyrics)
                            }
                        })
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Preferences.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onDestroyView() {
        Preferences.unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroyView()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        if (key == SOURCE_SEPARATION_QUICK_CONTROLS_VISIBLE) {
            showSourceSeparationQuickControlsState.value =
                Preferences.sourceSeparationQuickControlsVisible
        }
    }
}
