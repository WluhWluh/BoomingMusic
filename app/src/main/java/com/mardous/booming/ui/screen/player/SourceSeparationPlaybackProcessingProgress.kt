package com.mardous.booming.ui.screen.player

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.mardous.booming.util.DEFAULT_SOURCE_SEPARATION_AVERAGE_WINDOW_MS
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.max

data class SourceSeparationPlaybackProcessingProgressState(
    val progress: Float,
    val resetKey: List<Any?>,
    val estimatedRemainingSeconds: Int,
    val readyWindows: Int,
    val targetWindows: Int,
    val pendingWindows: Int,
    val initialProcessingLabel: String,
    val hasScheduler: Boolean,
)

@Composable
fun rememberSourceSeparationPlaybackProcessingProgressState(
    separationState: SourceSeparationUiState,
    processingGeneration: Long,
    processingSongId: Long? = null,
): SourceSeparationPlaybackProcessingProgressState {
    val rawRunningState = (separationState as? SourceSeparationUiState.Running)
        ?.takeIf { state ->
            processingSongId == null || state.songId == processingSongId
        }
    val rawScheduler = rawRunningState?.scheduler
    val rawSnapshotKey = listOf(
        rawRunningState?.songId,
        rawRunningState?.sourceDecodeMode,
        rawRunningState?.stage,
        rawScheduler?.playbackSegmentIndex,
        rawScheduler?.processingSegmentIndex,
        rawScheduler?.playbackReadyWindowReadyCount,
        rawScheduler?.playbackReadyWindowPendingCount,
        rawScheduler?.readyWindowCount,
    )
    val generationGuard = remember {
        SourceSeparationPlaybackProcessingProgressGenerationGuard()
    }
    if (generationGuard.generation != processingGeneration) {
        generationGuard.ignoredSnapshotKey = if (
            generationGuard.hasSeenGeneration &&
            generationGuard.latestSnapshotKey == rawSnapshotKey
        ) {
            rawSnapshotKey
        } else {
            null
        }
        generationGuard.generation = processingGeneration
        generationGuard.hasSeenGeneration = true
    } else if (generationGuard.ignoredSnapshotKey != null &&
        generationGuard.ignoredSnapshotKey != rawSnapshotKey
    ) {
        generationGuard.ignoredSnapshotKey = null
    }
    generationGuard.latestSnapshotKey = rawSnapshotKey

    val runningState = rawRunningState.takeUnless {
        generationGuard.ignoredSnapshotKey != null &&
                generationGuard.ignoredSnapshotKey == rawSnapshotKey
    }
    val scheduler = runningState?.scheduler
    val pendingWindows = scheduler?.playbackReadyWindowPendingCount?.coerceAtLeast(0) ?: 0
    val readyWindows = scheduler?.playbackReadyWindowReadyCount?.coerceAtLeast(0) ?: 0
    val targetWindows = scheduler?.readyWindowCount?.coerceAtLeast(1)
        ?: runningState?.initialProcessingWindowCount()
        ?: 0
    val schedulerHasOnlyOutsidePendingWork = scheduler != null &&
            targetWindows > 0 &&
            pendingWindows > 0 &&
            readyWindows >= targetWindows
    val hasUsableProgressSource = when {
        scheduler != null -> pendingWindows > 0 && !schedulerHasOnlyOutsidePendingWork
        runningState?.sourceDecodeMode != null -> targetWindows > 0
        else -> false
    }
    val averageWindowMs = runningState
        ?.averageWindowMs
        ?.coerceAtLeast(MIN_SOURCE_SEPARATION_PROGRESS_WINDOW_MS)
        ?: DEFAULT_SOURCE_SEPARATION_AVERAGE_WINDOW_MS
    val estimatedWindowMs = (averageWindowMs * PROGRESS_WINDOW_ESTIMATE_SCALE)
        .toLong()
        .coerceAtLeast(MIN_SOURCE_SEPARATION_PROGRESS_WINDOW_MS)
    val effectiveProgressSongId = processingSongId ?: runningState?.songId
    val progressSessionKey = listOf(
        processingGeneration,
        effectiveProgressSongId,
    )
    val progressPhaseKey = progressSessionKey + listOf(
        scheduler?.playbackSegmentIndex,
    )
    var elapsedInProgressMs by remember(progressSessionKey) {
        mutableStateOf(0L)
    }
    LaunchedEffect(progressSessionKey) {
        val startedAt = SystemClock.elapsedRealtime()
        while (true) {
            elapsedInProgressMs = SystemClock.elapsedRealtime() - startedAt
            delay(PROGRESS_ESTIMATE_TICK_MS)
        }
    }
    val estimateKey = listOf(
        processingGeneration,
        processingSongId,
        runningState?.songId,
        scheduler?.playbackSegmentIndex,
        scheduler?.processingSegmentIndex,
        pendingWindows,
        readyWindows,
        targetWindows,
    )
    var elapsedInEstimateMs by remember(estimateKey) {
        mutableStateOf(0L)
    }
    LaunchedEffect(estimateKey) {
        val startedAt = SystemClock.elapsedRealtime()
        while (true) {
            elapsedInEstimateMs = SystemClock.elapsedRealtime() - startedAt
            delay(PROGRESS_ESTIMATE_TICK_MS)
        }
    }
    val estimatedRemainingSeconds = ceil(
        (((pendingWindows.takeIf { hasUsableProgressSource && it > 0 } ?: targetWindows)
            .coerceAtLeast(1) *
                estimatedWindowMs) - elapsedInEstimateMs)
            .coerceAtLeast(0L) / 1000.0
    ).toInt()
    val initialCompletedUnits = runningState?.initialProcessingCompletedUnits()
        ?.coerceAtLeast(0)
        ?: 0
    val completeSchedulerSnapshot = scheduler != null &&
            targetWindows > 0 &&
            pendingWindows == 0 &&
            readyWindows >= targetWindows
    val completeInitialSnapshot = scheduler == null &&
            targetWindows > 0 &&
            initialCompletedUnits >= targetWindows
    val ignoreCompleteSnapshot = completeSchedulerSnapshot || completeInitialSnapshot
    var progressWindowCount by remember(progressPhaseKey) {
        mutableStateOf(
            if (scheduler != null && hasUsableProgressSource) {
                max(targetWindows, pendingWindows)
            } else {
                targetWindows
            }
        )
    }
    LaunchedEffect(
        progressPhaseKey,
        pendingWindows,
        targetWindows,
    ) {
        if (scheduler != null && hasUsableProgressSource) {
            progressWindowCount = max(
                progressWindowCount,
                max(targetWindows, pendingWindows),
            )
        } else {
            progressWindowCount = targetWindows
        }
    }
    val progressTarget = if (progressWindowCount > 0 && hasUsableProgressSource) {
        val baseCompletedWindows = when {
            ignoreCompleteSnapshot -> 0f
            scheduler != null -> (progressWindowCount - pendingWindows)
                .coerceIn(0, progressWindowCount)
                .toFloat()
            else -> initialCompletedUnits.toFloat()
        }
        val estimatedCompletedWindows = if (ignoreCompleteSnapshot) {
            0f
        } else {
            val initialLeadMs = (estimatedWindowMs / 5L)
                .coerceAtMost(PROGRESS_INITIAL_LEAD_MS)
            (elapsedInEstimateMs + initialLeadMs).toFloat() / estimatedWindowMs.toFloat()
        }
        val estimatedWorkProgress =
            (baseCompletedWindows + estimatedCompletedWindows) / progressWindowCount.toFloat()
        (PROGRESS_WARMUP_CAP +
                estimatedWorkProgress * (PROGRESS_MAX_TARGET - PROGRESS_WARMUP_CAP))
            .coerceIn(0f, 0.98f)
    } else {
        ((elapsedInProgressMs.toFloat() / PROGRESS_WARMUP_DURATION_MS.toFloat()) *
                PROGRESS_WARMUP_CAP)
            .coerceIn(0f, PROGRESS_WARMUP_CAP)
    }
    var displayedProgressTarget by remember(progressSessionKey) {
        mutableFloatStateOf(0f)
    }
    LaunchedEffect(progressPhaseKey, progressTarget) {
        displayedProgressTarget = max(displayedProgressTarget, progressTarget)
    }
    val animatedProgress = remember(progressSessionKey) {
        Animatable(0f)
    }
    LaunchedEffect(progressSessionKey, displayedProgressTarget) {
        val progressDelta = (displayedProgressTarget - animatedProgress.value)
            .coerceAtLeast(0f)
        if (progressDelta <= PROGRESS_ANIMATION_EPSILON) {
            animatedProgress.snapTo(displayedProgressTarget)
            return@LaunchedEffect
        }
        val durationMillis = if (progressDelta >= PROGRESS_LARGE_JUMP_DELTA) {
            (progressDelta * PROGRESS_LARGE_JUMP_DURATION_FACTOR_MS)
                .toInt()
                .coerceIn(
                    PROGRESS_LARGE_JUMP_MIN_ANIMATION_MS,
                    PROGRESS_LARGE_JUMP_MAX_ANIMATION_MS,
                )
        } else {
            PROGRESS_SMALL_UPDATE_ANIMATION_MS
        }
        animatedProgress.animateTo(
            targetValue = displayedProgressTarget,
            animationSpec = tween(
                durationMillis = durationMillis,
                easing = if (progressDelta >= PROGRESS_LARGE_JUMP_DELTA) {
                    FastOutSlowInEasing
                } else {
                    LinearEasing
                },
            ),
        )
    }

    return SourceSeparationPlaybackProcessingProgressState(
        progress = animatedProgress.value,
        resetKey = progressSessionKey,
        estimatedRemainingSeconds = estimatedRemainingSeconds,
        readyWindows = if (ignoreCompleteSnapshot) 0 else readyWindows,
        targetWindows = targetWindows,
        pendingWindows = if (ignoreCompleteSnapshot) targetWindows else pendingWindows,
        initialProcessingLabel = runningState?.initialProcessingLabel().orEmpty(),
        hasScheduler = scheduler != null && !ignoreCompleteSnapshot && hasUsableProgressSource,
    )
}

private class SourceSeparationPlaybackProcessingProgressGenerationGuard {
    var generation: Long = Long.MIN_VALUE
    var ignoredSnapshotKey: List<Any?>? = null
    var latestSnapshotKey: List<Any?>? = null
    var hasSeenGeneration: Boolean = false
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

private const val MIN_SOURCE_SEPARATION_PROGRESS_WINDOW_MS = 500L
private const val PROGRESS_ESTIMATE_TICK_MS = 100L
private const val PROGRESS_WINDOW_ESTIMATE_SCALE = 0.9
private const val PROGRESS_WARMUP_CAP = 0.2f
private const val PROGRESS_WARMUP_DURATION_MS = 3000L
private const val PROGRESS_MAX_TARGET = 0.9f
private const val PROGRESS_INITIAL_LEAD_MS = 700L
private const val PROGRESS_ANIMATION_EPSILON = 0.001f
private const val PROGRESS_LARGE_JUMP_DELTA = 0.08f
private const val PROGRESS_SMALL_UPDATE_ANIMATION_MS = 80
private const val PROGRESS_LARGE_JUMP_MIN_ANIMATION_MS = 220
private const val PROGRESS_LARGE_JUMP_MAX_ANIMATION_MS = 700
private const val PROGRESS_LARGE_JUMP_DURATION_FACTOR_MS = 1800
