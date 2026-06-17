package com.mardous.booming.ui.screen.player

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.mardous.booming.R
import com.mardous.booming.extensions.isLandscape
import com.mardous.booming.ui.component.compose.BottomSheetDialogSurface
import com.mardous.booming.ui.component.compose.TitledCard
import com.mardous.booming.ui.theme.BoomingMusicTheme
import com.mardous.booming.ui.theme.SliderTokens
import org.koin.androidx.viewmodel.ext.android.activityViewModel

class SourceSeparationSettingsFragment : BottomSheetDialogFragment() {

    private val viewModel: PlayerViewModel by activityViewModel()

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
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            setContent {
                BoomingMusicTheme {
                    SourceSeparationSettingsSheet(viewModel)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SourceSeparationSettingsSheet(
    viewModel: PlayerViewModel
) {
    val hapticFeedback = LocalHapticFeedback.current

    val playbackState by viewModel.sourceSeparationPlaybackStateFlow.collectAsState()
    val blendMode by viewModel.sourceSeparationBlendModeFlow.collectAsState()
    val separationState by viewModel.sourceSeparationStateFlow.collectAsState()
    val windowDecodeExperimentState by viewModel
        .sourceSeparationWindowDecodeExperimentStateFlow
        .collectAsState()

    val separatedPlaybackEnabled = blendMode != SourceSeparationBlendMode.Off
    val rememberPerSong = blendMode == SourceSeparationBlendMode.PerSong
    var blend by remember(playbackState.blend) {
        mutableFloatStateOf(playbackState.blend.coerceIn(0f, 1f))
    }

    BottomSheetDialogSurface {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .nestedScroll(rememberNestedScrollInteropConnection())
        ) {
            BottomSheetDefaults.DragHandle(
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp)
            ) {
                item {
                    Text(
                        text = stringResource(R.string.action_source_separation_settings),
                        style = MaterialTheme.typography.headlineSmallEmphasized
                    )
                }

                item {
                    TitledCard(
                        title = stringResource(R.string.source_separation_playback_title),
                        modifier = Modifier.fillMaxWidth()
                    ) { cardContentPadding ->
                        Column(
                            modifier = Modifier.padding(cardContentPadding)
                        ) {
                            LabeledSwitch(
                                checked = separatedPlaybackEnabled,
                                title = stringResource(R.string.source_separation_playback_title),
                                description = stringResource(R.string.source_separation_playback_description)
                            ) { checked ->
                                val nextMode = if (checked) {
                                    if (rememberPerSong) {
                                        SourceSeparationBlendMode.PerSong
                                    } else {
                                        SourceSeparationBlendMode.Global
                                    }
                                } else {
                                    SourceSeparationBlendMode.Off
                                }
                                viewModel.setSourceSeparationBlendMode(nextMode)
                                viewModel.setSourceSeparationPlaybackEnabled(checked, blend)
                            }

                            LabeledSwitch(
                                checked = rememberPerSong,
                                title = stringResource(R.string.source_separation_remember_per_song_title),
                                description = stringResource(R.string.source_separation_remember_per_song_description),
                                enabled = separatedPlaybackEnabled
                            ) { checked ->
                                viewModel.setSourceSeparationBlendMode(
                                    if (checked) {
                                        SourceSeparationBlendMode.PerSong
                                    } else {
                                        SourceSeparationBlendMode.Global
                                    }
                                )
                            }

                            Text(
                                text = stringResource(
                                    R.string.source_separation_active_mode,
                                    stringResource(blendMode.titleRes)
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)
                            )
                        }
                    }
                }

                item {
                    TitledCard(
                        title = stringResource(R.string.source_separation_blend_title),
                        titleEndContent = {
                            IconButton(
                                onClick = {
                                    hapticFeedback.performHapticFeedback(
                                        HapticFeedbackType.Confirm
                                    )
                                    blend = 0.5f
                                    viewModel.setSourceSeparationBlend(0.5f)
                                },
                                enabled = separatedPlaybackEnabled,
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_restart_alt_24dp),
                                    tint = MaterialTheme.colorScheme.secondary,
                                    contentDescription = stringResource(
                                        R.string.source_separation_reset_blend
                                    ),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { cardContentPadding ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(cardContentPadding)
                        ) {
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                Slider(
                                    value = blend,
                                    valueRange = 0f..1f,
                                    enabled = separatedPlaybackEnabled,
                                    onValueChange = { value ->
                                        blend = value
                                        if (playbackState.enabled) {
                                            viewModel.setSourceSeparationBlend(value)
                                        }
                                    },
                                    onValueChangeFinished = {
                                        hapticFeedback.performHapticFeedback(
                                            HapticFeedbackType.SegmentFrequentTick
                                        )
                                        viewModel.setSourceSeparationBlend(blend)
                                    },
                                    track = {
                                        SliderDefaults.CenteredTrack(
                                            sliderState = it,
                                            trackCornerSize = SliderTokens.TrackCornerSize,
                                            modifier = Modifier.height(SliderTokens.LargeTrackHeight)
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            Row(
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.source_separation_blend_vocals),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = FontWeight.SemiBold,
                                    textAlign = TextAlign.Start,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall
                                )

                                Text(
                                    text = stringResource(R.string.source_separation_blend_instrumental),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = FontWeight.SemiBold,
                                    textAlign = TextAlign.End,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }

                item {
                    TitledCard(
                        title = stringResource(R.string.source_separation_current_song),
                        modifier = Modifier.fillMaxWidth()
                    ) { cardContentPadding ->
                        Column(
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(cardContentPadding)
                        ) {
                            SourceSeparationStatusText(separationState)
                            AnimatedVisibility(
                                visible = playbackState.processing
                            ) {
                                Text(
                                    text = stringResource(R.string.source_separation_playback_processing),
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }

                            AnimatedVisibility(
                                visible = separationState is SourceSeparationUiState.Running
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                                        viewModel.cancelSourceSeparation()
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(stringResource(R.string.action_cancel))
                                }
                            }

                            AnimatedVisibility(
                                visible = separationState !is SourceSeparationUiState.Running
                            ) {
                                Button(
                                    onClick = {
                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                                        viewModel.startSourceSeparationForCurrentSong()
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(stringResource(R.string.source_separation_start_current_song))
                                }
                            }

                            OutlinedButton(
                                onClick = {
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                                    viewModel.runWindowDecodeExperimentForCurrentSong()
                                },
                                enabled = windowDecodeExperimentState !is
                                        SourceSeparationWindowDecodeExperimentUiState.Running,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.source_separation_window_decode_experiment))
                            }

                            SourceSeparationWindowDecodeExperimentStatusText(windowDecodeExperimentState)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceSeparationWindowDecodeExperimentStatusText(
    state: SourceSeparationWindowDecodeExperimentUiState
) {
    val text = when (state) {
        SourceSeparationWindowDecodeExperimentUiState.Idle -> null
        is SourceSeparationWindowDecodeExperimentUiState.Running -> {
            val probeText = if (state.probeIndex != null && state.probeCount != null) {
                stringResource(
                    R.string.source_separation_window_decode_probe_progress,
                    state.probeIndex,
                    state.probeCount,
                )
            } else {
                stringResource(R.string.source_separation_window_decode_probe_preparing)
            }
            stringResource(
                R.string.source_separation_window_decode_running,
                state.percent,
                state.completedSteps,
                state.totalSteps,
                probeText,
                state.stage,
            )
        }
        is SourceSeparationWindowDecodeExperimentUiState.Completed -> {
            stringResource(
                R.string.source_separation_window_decode_completed,
                state.reportPath,
                state.fullDecodeMs,
                state.probeCount,
                state.totalWindowDecodeMs,
                state.worstOffsetFrames,
                state.worstMeanAbsoluteError,
            )
        }
        is SourceSeparationWindowDecodeExperimentUiState.Failed -> {
            stringResource(
                R.string.source_separation_window_decode_failed,
                state.message.orEmpty(),
            )
        }
    } ?: return

    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall
    )
}

@Composable
private fun SourceSeparationStatusText(
    state: SourceSeparationUiState
) {
    val text = when (state) {
        SourceSeparationUiState.Idle -> stringResource(R.string.source_separation_status_idle)
        is SourceSeparationUiState.Running -> {
            if (state.totalWindows > 0) {
                stringResource(
                    R.string.source_separation_progress,
                    state.completedWindows,
                    state.totalWindows,
                    state.percent,
                    state.stage ?: stringResource(R.string.source_separation_processing_windows),
                )
            } else {
                stringResource(
                    R.string.source_separation_stage,
                    state.stage ?: stringResource(R.string.source_separation_preparing),
                )
            }
        }
        is SourceSeparationUiState.Completed -> {
            stringResource(R.string.source_separation_status_completed, state.songTitle)
        }
        is SourceSeparationUiState.Canceled -> {
            stringResource(R.string.source_separation_status_canceled, state.songTitle)
        }
        is SourceSeparationUiState.Failed -> {
            if (state.message.isNullOrBlank()) {
                stringResource(R.string.source_separation_status_failed, state.songTitle)
            } else {
                stringResource(
                    R.string.source_separation_status_failed_with_message,
                    state.songTitle,
                    state.message,
                )
            }
        }
    }

    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium
    )
}

@Composable
private fun LabeledSwitch(
    checked: Boolean,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onStateChange: (Boolean) -> Unit
) {
    Row(
        modifier = modifier
            .clickable(
                enabled = enabled,
                role = Role.Switch,
                onClick = { onStateChange(!checked) }
            )
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                maxLines = 1,
                style = MaterialTheme.typography.bodyMediumEmphasized,
                fontWeight = FontWeight.Medium
            )

            Text(
                text = description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = { onStateChange(it) },
            thumbContent = {
                if (checked) {
                    Icon(
                        painter = painterResource(R.drawable.ic_check_24dp),
                        contentDescription = null,
                        modifier = Modifier.size(SwitchDefaults.IconSize)
                    )
                }
            }
        )
    }
}

private val SourceSeparationBlendMode.titleRes: Int
    get() = when (this) {
        SourceSeparationBlendMode.Off -> R.string.source_separation_blend_mode_off
        SourceSeparationBlendMode.Global -> R.string.source_separation_blend_mode_global
        SourceSeparationBlendMode.PerSong -> R.string.source_separation_blend_mode_per_song
    }
