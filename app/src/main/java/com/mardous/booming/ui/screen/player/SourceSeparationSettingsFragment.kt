package com.mardous.booming.ui.screen.player

import android.app.Dialog
import android.os.SystemClock
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.text.input.KeyboardType
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
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.max

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
    val rememberPerSong by viewModel.sourceSeparationRememberPerSongFlow.collectAsState()
    val separationState by viewModel.sourceSeparationStateFlow.collectAsState()
    val currentSongCacheAvailable by viewModel
        .currentSourceSeparationCacheAvailableFlow
        .collectAsState()
    val currentSongCacheState by viewModel
        .currentSourceSeparationCacheStateFlow
        .collectAsState()
    val pendingAction by viewModel.sourceSeparationPendingActionFlow.collectAsState()
    val windowDecodeExperimentState by viewModel
        .sourceSeparationWindowDecodeExperimentStateFlow
        .collectAsState()
    val autoFlacCompression by viewModel
        .sourceSeparationAutoFlacCompressionFlow
        .collectAsState()
    val showSnackbarProgress by viewModel
        .sourceSeparationShowSnackbarProgressFlow
        .collectAsState()
    val mixedOutputPrerollMs by viewModel
        .sourceSeparationMixedOutputPrerollMsFlow
        .collectAsState()
    val hydratedMixedOutputPrerollMs by viewModel
        .sourceSeparationHydratedMixedOutputPrerollMsFlow
        .collectAsState()
    val playbackReadyWindowCount by viewModel
        .sourceSeparationPlaybackReadyWindowCountFlow
        .collectAsState()

    val separatedPlaybackEnabled = blendMode != SourceSeparationBlendMode.Off
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
                                viewModel.setSourceSeparationPlaybackEnabled(checked, blend)
                            }

                            LabeledSwitch(
                                checked = rememberPerSong,
                                title = stringResource(R.string.source_separation_remember_per_song_title),
                                description = stringResource(R.string.source_separation_remember_per_song_description),
                                enabled = separatedPlaybackEnabled
                            ) { checked ->
                                viewModel.setSourceSeparationRememberPerSongEnabled(checked)
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
                            SourceSeparationStatusText(separationState, currentSongCacheState)
                            SourceSeparationSchedulerText(separationState)
                            SourceSeparationDecodeDiagnosticsText(separationState)
                            AnimatedVisibility(
                                visible = playbackState.processing
                            ) {
                                SourceSeparationPlaybackProcessingProgress(
                                    separationState = separationState,
                                    processingGeneration = playbackState.processingGeneration,
                                )
                            }

                            AnimatedVisibility(
                                visible = separationState is SourceSeparationUiState.Running
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                                        viewModel.pauseSourceSeparation()
                                    },
                                    enabled = pendingAction == null,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = if (pendingAction == SourceSeparationPendingAction.Pause) {
                                            stringResource(
                                                R.string.source_separation_wait_current_window
                                            )
                                        } else {
                                            stringResource(R.string.action_pause)
                                        }
                                    )
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

                            AnimatedVisibility(
                                visible = currentSongCacheAvailable
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                                        viewModel.deleteSourceSeparationCacheForCurrentSong()
                                    },
                                    enabled = pendingAction == null,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_delete_24dp),
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = if (pendingAction == SourceSeparationPendingAction.DeleteCache) {
                                            stringResource(
                                                R.string.source_separation_wait_current_window
                                            )
                                        } else {
                                            stringResource(
                                                R.string.source_separation_clear_current_cache
                                            )
                                        },
                                        modifier = Modifier.padding(start = 8.dp)
                                    )
                                }
                            }

                            AnimatedVisibility(
                                visible = currentSongCacheState.canPromoteCompletedStems
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                                        viewModel.tryFlacCompressionForCurrentSong()
                                    },
                                    enabled = pendingAction == null,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = if (pendingAction == SourceSeparationPendingAction.PromoteFlac) {
                                            stringResource(
                                                R.string.source_separation_flac_compression_working
                                            )
                                        } else {
                                            stringResource(
                                                R.string.source_separation_try_flac_compression
                                            )
                                        }
                                    )
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

                item {
                    TitledCard(
                        title = stringResource(R.string.source_separation_advanced_title),
                        modifier = Modifier.fillMaxWidth()
                    ) { cardContentPadding ->
                        Column(
                            modifier = Modifier.padding(cardContentPadding)
                        ) {
                            LabeledSwitch(
                                checked = autoFlacCompression,
                                title = stringResource(
                                    R.string.source_separation_auto_flac_compression_title
                                ),
                                description = stringResource(
                                    R.string.source_separation_auto_flac_compression_description
                                )
                            ) { checked ->
                                viewModel.setSourceSeparationAutoFlacCompressionEnabled(checked)
                            }

                            LabeledSwitch(
                                checked = showSnackbarProgress,
                                title = stringResource(
                                    R.string.source_separation_show_snackbar_progress_title
                                ),
                                description = stringResource(
                                    R.string.source_separation_show_snackbar_progress_description
                                )
                            ) { checked ->
                                viewModel.setSourceSeparationShowSnackbarProgressEnabled(checked)
                            }

                            PrerollMsField(
                                valueMs = mixedOutputPrerollMs,
                                title = stringResource(
                                    R.string.source_separation_mixed_output_preroll_title
                                ),
                                description = stringResource(
                                    R.string.source_separation_mixed_output_preroll_description
                                ),
                                onValueChange = viewModel::setSourceSeparationMixedOutputPrerollMs
                            )

                            PrerollMsField(
                                valueMs = hydratedMixedOutputPrerollMs,
                                title = stringResource(
                                    R.string.source_separation_hydrated_mixed_output_preroll_title
                                ),
                                description = stringResource(
                                    R.string.source_separation_hydrated_mixed_output_preroll_description
                                ),
                                onValueChange =
                                    viewModel::setSourceSeparationHydratedMixedOutputPrerollMs
                            )

                            NumberSettingField(
                                value = playbackReadyWindowCount,
                                title = stringResource(
                                    R.string.source_separation_playback_ready_windows_title
                                ),
                                description = stringResource(
                                    R.string.source_separation_playback_ready_windows_description
                                ),
                                suffix = stringResource(
                                    R.string.source_separation_playback_ready_windows_suffix
                                ),
                                onValueChange =
                                    viewModel::setSourceSeparationPlaybackReadyWindowCount
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceSeparationDecodeDiagnosticsText(
    state: SourceSeparationUiState
) {
    val text = (state as? SourceSeparationUiState.Running)
        ?.sourceDecodeDiagnostics
        ?.takeIf { it.isNotBlank() }
        ?: return

    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall
    )
}

@Composable
private fun SourceSeparationPlaybackProcessingProgress(
    separationState: SourceSeparationUiState,
    processingGeneration: Long,
) {
    val runningState = separationState as? SourceSeparationUiState.Running
    val scheduler = runningState?.scheduler
    val pendingWindows = scheduler?.playbackReadyWindowPendingCount?.coerceAtLeast(0) ?: 0
    val readyWindows = scheduler?.playbackReadyWindowReadyCount?.coerceAtLeast(0) ?: 0
    val targetWindows = scheduler?.readyWindowCount?.coerceAtLeast(1)
        ?: runningState?.initialProcessingWindowCount()
        ?: 0
    val averageWindowMs = runningState?.averageWindowMs?.coerceAtLeast(1L) ?: 3000L
    val estimateKey = listOf(
        processingGeneration,
        scheduler?.playbackSegmentIndex,
        scheduler?.processingSegmentIndex,
        pendingWindows,
        readyWindows,
        targetWindows,
    )
    var elapsedInEstimateMs by remember(estimateKey) {
        mutableStateOf(0L)
    }
    LaunchedEffect(estimateKey, averageWindowMs) {
        val startedAt = SystemClock.elapsedRealtime()
        while (true) {
            elapsedInEstimateMs = SystemClock.elapsedRealtime() - startedAt
            delay(100L)
        }
    }
    val estimatedRemainingSeconds = ceil(
        (((pendingWindows.takeIf { it > 0 } ?: targetWindows).coerceAtLeast(1) *
                averageWindowMs) - elapsedInEstimateMs)
            .coerceAtLeast(0L) / 1000.0
    ).toInt()
    val progressTarget = if (targetWindows > 0) {
        val baseCompletedWindows = if (scheduler != null) {
            readyWindows.toFloat()
        } else {
            runningState?.initialProcessingCompletedUnits()?.toFloat() ?: 0f
        }
        val estimatedCompletedWindows = elapsedInEstimateMs.toFloat() / averageWindowMs.toFloat()
        ((baseCompletedWindows + estimatedCompletedWindows) / targetWindows.toFloat())
            .coerceIn(0f, 0.98f)
    } else {
        0f
    }
    val progressResetKey = listOf(
        processingGeneration,
        scheduler?.playbackSegmentIndex,
        targetWindows,
    )
    var displayedProgressTarget by remember(progressResetKey) {
        mutableFloatStateOf(0f)
    }
    LaunchedEffect(progressResetKey, progressTarget) {
        displayedProgressTarget = max(displayedProgressTarget, progressTarget)
    }
    val animatedProgress = remember(progressResetKey) {
        Animatable(0f)
    }
    LaunchedEffect(progressResetKey, displayedProgressTarget, averageWindowMs) {
        animatedProgress.animateTo(
            targetValue = displayedProgressTarget,
            animationSpec = tween(
                durationMillis = 120,
                easing = LinearEasing,
            ),
        )
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = if (scheduler != null && targetWindows > 0) {
                stringResource(
                    R.string.source_separation_playback_processing_estimate,
                    readyWindows,
                    targetWindows,
                    pendingWindows,
                    estimatedRemainingSeconds,
                )
            } else if (runningState != null && targetWindows > 0) {
                stringResource(
                    R.string.source_separation_playback_initial_processing_estimate,
                    runningState.initialProcessingLabel(),
                    estimatedRemainingSeconds,
                )
            } else {
                stringResource(R.string.source_separation_playback_processing)
            },
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium
        )
        LinearProgressIndicator(
            progress = { animatedProgress.value },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun SourceSeparationUiState.Running.initialProcessingWindowCount(): Int {
    return when (sourceDecodeMode) {
        SourceSeparationDecodeModeUiState.FullSong -> 3
        SourceSeparationDecodeModeUiState.Window -> 2
        null -> 0
    }
}

private fun SourceSeparationUiState.Running.initialProcessingCompletedUnits(): Int {
    val stageText = stage.orEmpty()
    return when (sourceDecodeMode) {
        SourceSeparationDecodeModeUiState.FullSong -> when {
            stageText.contains("Processed window 2", ignoreCase = true) -> 3
            stageText.contains("Preparing window 2", ignoreCase = true) ||
                    stageText.contains("Processed window 1", ignoreCase = true) -> 2
            stageText.contains("Preparing window 1", ignoreCase = true) -> 1
            else -> 0
        }
        SourceSeparationDecodeModeUiState.Window -> when {
            stageText.contains("Processed window 2", ignoreCase = true) -> 2
            stageText.contains("Preparing window 2", ignoreCase = true) ||
                    stageText.contains("Processed window 1", ignoreCase = true) -> 1
            else -> 0
        }
        null -> 0
    }
}

private fun SourceSeparationUiState.Running.initialProcessingLabel(): String {
    val stageText = stage.orEmpty()
    return when (sourceDecodeMode) {
        SourceSeparationDecodeModeUiState.FullSong -> when {
            stageText.contains("Preparing window", ignoreCase = true) -> stageText
            else -> "Full decode"
        }
        SourceSeparationDecodeModeUiState.Window -> when {
            stageText.contains("Preparing window", ignoreCase = true) -> stageText
            else -> "Window decode"
        }
        null -> stage ?: ""
    }.ifBlank { "Processing" }
}

@Composable
private fun SourceSeparationSchedulerText(
    state: SourceSeparationUiState
) {
    val scheduler = (state as? SourceSeparationUiState.Running)
        ?.scheduler
        ?: return

    val playbackSegmentText = scheduler.playbackSegmentIndex?.let { index ->
        val segmentState = scheduler.playbackSegmentState ?: "?"
        "playback=$index/$segmentState"
    } ?: "playback=idle"
    val nextSegmentText = scheduler.nextSegmentIndex?.let { index ->
        val segmentState = scheduler.nextSegmentState ?: "?"
        "next=$index/$segmentState"
    } ?: "next=none"
    val processingText = "processing=${scheduler.processingSegmentIndex}/${scheduler.priority ?: "?"}"
    val readyText = "ready=${scheduler.readySegments}/${scheduler.totalSegments}"
    val bufferText = "buffer=${scheduler.playbackReadyWindowReadyCount}/${scheduler.readyWindowCount}"

    Text(
        text = stringResource(
            R.string.source_separation_scheduler_status,
            "$playbackSegmentText  $nextSegmentText  $processingText  $readyText  $bufferText",
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall
    )
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
    state: SourceSeparationUiState,
    cacheState: SourceSeparationCacheUiState,
) {
    val text = when (state) {
        SourceSeparationUiState.Idle -> when (cacheState) {
            SourceSeparationCacheUiState.NotStarted -> {
                stringResource(R.string.source_separation_status_not_started)
            }
            is SourceSeparationCacheUiState.Partial -> {
                stringResource(
                    R.string.source_separation_status_partial_cache,
                    cacheState.readySegments,
                    cacheState.totalSegments,
                )
            }
            is SourceSeparationCacheUiState.CompletedWithTemporaryFiles -> {
                stringResource(R.string.source_separation_status_completed_cleanup_pending)
            }
            is SourceSeparationCacheUiState.Completed -> {
                stringResource(R.string.source_separation_status_completed_cache)
            }
        }
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
        is SourceSeparationUiState.Paused -> {
            stringResource(R.string.source_separation_status_paused, state.songTitle)
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

@Composable
private fun PrerollMsField(
    valueMs: Long,
    title: String,
    description: String,
    onValueChange: (Long) -> Unit,
) {
    NumberSettingField(
        value = valueMs,
        title = title,
        description = description,
        suffix = stringResource(R.string.source_separation_preroll_ms_suffix),
        onValueChange = onValueChange,
    )
}

@Composable
private fun NumberSettingField(
    value: Int,
    title: String,
    description: String,
    suffix: String,
    onValueChange: (Int) -> Unit,
) {
    NumberSettingField(
        value = value.toLong(),
        title = title,
        description = description,
        suffix = suffix,
        onValueChange = { onValueChange(it.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) },
    )
}

@Composable
private fun NumberSettingField(
    value: Long,
    title: String,
    description: String,
    suffix: String,
    onValueChange: (Long) -> Unit,
) {
    var text by remember(value) {
        mutableStateOf(value.toString())
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMediumEmphasized,
                fontWeight = FontWeight.Medium
            )

            Text(
                text = description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }

        OutlinedTextField(
            value = text,
            onValueChange = { input ->
                val digits = input.filter(Char::isDigit)
                text = digits
                digits
                    .toLongOrNull()
                    ?.let(onValueChange)
            },
            singleLine = true,
            suffix = {
                Text(suffix)
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private val SourceSeparationBlendMode.titleRes: Int
    get() = when (this) {
        SourceSeparationBlendMode.Off -> R.string.source_separation_blend_mode_off
        SourceSeparationBlendMode.Global -> R.string.source_separation_blend_mode_global
        SourceSeparationBlendMode.PerSong -> R.string.source_separation_blend_mode_per_song
    }

private val SourceSeparationCacheUiState.canPromoteCompletedStems: Boolean
    get() = when (this) {
        is SourceSeparationCacheUiState.Completed -> canPromoteCompletedStems
        is SourceSeparationCacheUiState.CompletedWithTemporaryFiles -> canPromoteCompletedStems
        SourceSeparationCacheUiState.NotStarted,
        is SourceSeparationCacheUiState.Partial -> false
    }
