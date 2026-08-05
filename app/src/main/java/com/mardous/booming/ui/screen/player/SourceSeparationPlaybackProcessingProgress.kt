package com.mardous.booming.ui.screen.player

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import com.mardous.booming.util.DEFAULT_SOURCE_SEPARATION_AVERAGE_WINDOW_MS
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

data class SourceSeparationPlaybackProcessingProgressState(
    val progress: Float,
    val estimatedRemainingSeconds: Int,
    val readyWindows: Int,
    val targetWindows: Int,
    val pendingWindows: Int,
    val initialProcessingLabel: String,
    val hasScheduler: Boolean,
    val isCompletionTail: Boolean = false,
)

@Composable
internal fun animateSourceSeparationPlaybackProcessingProgress(
    state: SourceSeparationPlaybackProcessingProgressState,
): Float {
    val progress by animateFloatAsState(
        targetValue = state.progress,
        animationSpec = if (state.isCompletionTail) {
            tween(
                durationMillis = SOURCE_SEPARATION_PLAYBACK_PROGRESS_COMPLETION_TAIL_MS.toInt(),
                easing = LinearOutSlowInEasing,
            )
        } else {
            snap()
        },
        label = "SourceSeparationPlaybackProgress",
    )
    return progress
}

internal class SourceSeparationPlaybackProcessingProgressCoordinator {
    private var sessionKey: SourceSeparationPlaybackProcessingSessionKey? = null
    private var tracker: SourceSeparationPlaybackProcessingProgressTracker? = null
    private val generationGuard = SourceSeparationPlaybackProcessingProgressGenerationGuard()
    private var wasProcessing = false
    private var becamePlayableSinceLastWait = false
    private var lastProgressState: SourceSeparationPlaybackProcessingProgressState? = null
    private var completionTailStartedAtMs: Long? = null

    fun sample(
        playbackState: SourceSeparationPlaybackUiState,
        separationState: SourceSeparationUiState,
        currentSongId: Long?,
        nowMs: Long,
    ): SourceSeparationPlaybackProcessingProgressState? {
        val processingSongId = playbackState.songId ?: currentSongId
        if (!playbackState.processing) {
            val becameReady = playbackState.enabled &&
                    sessionKey?.songId == processingSongId
            if (becameReady) {
                becamePlayableSinceLastWait = true
            }
            if (wasProcessing && becameReady &&
                lastProgressState?.progress?.let { it < 1f } == true
            ) {
                completionTailStartedAtMs = nowMs
            }
            wasProcessing = false
            if (!playbackState.enabled) {
                completionTailStartedAtMs = null
                return null
            }
            val tailStartedAtMs = completionTailStartedAtMs ?: return null
            if (nowMs - tailStartedAtMs >=
                SOURCE_SEPARATION_PLAYBACK_PROGRESS_COMPLETION_TAIL_MS
            ) {
                completionTailStartedAtMs = null
                return null
            }
            return lastProgressState?.copy(
                progress = 1f,
                isCompletionTail = true,
            )
        }

        completionTailStartedAtMs = null
        val nextSessionKey = SourceSeparationPlaybackProcessingSessionKey(
            generation = playbackState.processingGeneration,
            songId = processingSongId,
        )
        if (sessionKey != nextSessionKey) {
            sessionKey = nextSessionKey
            tracker = SourceSeparationPlaybackProcessingProgressTracker(startedAtMs = nowMs)
            becamePlayableSinceLastWait = false
            lastProgressState = null
        } else if (!wasProcessing && becamePlayableSinceLastWait) {
            tracker = SourceSeparationPlaybackProcessingProgressTracker(startedAtMs = nowMs)
            becamePlayableSinceLastWait = false
            lastProgressState = null
        }
        wasProcessing = true

        val rawRunningState = (separationState as? SourceSeparationUiState.Running)
            ?.takeIf { state ->
                processingSongId == null || state.songId == processingSongId
            }
        val rawSnapshotKey = rawRunningState.playbackProcessingProgressSnapshotKey()
        val runningState = rawRunningState.takeIf {
            generationGuard.accepts(playbackState.processingGeneration, rawSnapshotKey)
        }
        return requireNotNull(tracker).sample(
            observation = runningState?.toPlaybackProcessingProgressObservation(),
            nowMs = nowMs,
        ).also { lastProgressState = it }
    }
}

internal class SourceSeparationPlaybackProcessingSessionGeneration {
    private var songId: Long? = null

    fun generationFor(songId: Long, currentGeneration: Long): Long {
        if (this.songId == songId) return currentGeneration
        this.songId = songId
        return currentGeneration + 1L
    }

    fun invalidate(songId: Long? = null) {
        if (songId == null || this.songId == songId) {
            this.songId = null
        }
    }
}

private data class SourceSeparationPlaybackProcessingSessionKey(
    val generation: Long,
    val songId: Long?,
)

internal class SourceSeparationPlaybackProcessingProgressGenerationGuard {
    var generation: Long = Long.MIN_VALUE
    var ignoredSnapshotKey: List<Any?>? = null
    var latestSnapshotKey: List<Any?>? = null
    var hasSeenGeneration: Boolean = false

    fun accepts(
        processingGeneration: Long,
        snapshotKey: List<Any?>,
    ): Boolean {
        if (generation != processingGeneration) {
            ignoredSnapshotKey = if (
                hasSeenGeneration && latestSnapshotKey == snapshotKey
            ) {
                snapshotKey
            } else {
                null
            }
            generation = processingGeneration
            hasSeenGeneration = true
        } else if (ignoredSnapshotKey != null && ignoredSnapshotKey != snapshotKey) {
            ignoredSnapshotKey = null
        }
        latestSnapshotKey = snapshotKey
        return ignoredSnapshotKey == null || ignoredSnapshotKey != snapshotKey
    }
}

private fun SourceSeparationUiState.Running?.playbackProcessingProgressSnapshotKey():
        List<Any?> {
    val scheduler = this?.scheduler
    return listOf(
        this?.songId,
        this?.sourceDecodeMode,
        this?.stage,
        scheduler?.playbackSegmentIndex,
        scheduler?.processingSegmentIndex,
        scheduler?.playbackReadyWindowReadyCount,
        scheduler?.playbackReadyWindowPendingCount,
        scheduler?.readyWindowCount,
    )
}

internal data class SourceSeparationPlaybackProcessingProgressObservation(
    val schedulerPlaybackSegmentIndex: Int?,
    val hasScheduler: Boolean,
    val hasUsableProgressSource: Boolean,
    val completeSnapshot: Boolean,
    val completedWorkUnits: Int,
    val readyWindows: Int,
    val targetWindows: Int,
    val pendingWindows: Int,
    val estimatedWindowMs: Long,
    val initialProcessingLabel: String,
)

internal class SourceSeparationPlaybackProcessingProgressTracker(
    private val startedAtMs: Long,
) {
    private var latestWorkIdentity: WorkIdentity? = null
    private var estimateStartedAtMs: Long = startedAtMs
    private var projectedProgress: Float = 0f
    private var displayedProgress: Float = 0f
    private var lastSampleAtMs: Long = startedAtMs

    fun sample(
        observation: SourceSeparationPlaybackProcessingProgressObservation?,
        nowMs: Long,
    ): SourceSeparationPlaybackProcessingProgressState {
        val safeNowMs = nowMs.coerceAtLeast(startedAtMs)
        val workIdentity = observation
            ?.takeIf { it.hasUsableProgressSource }
            ?.let {
                WorkIdentity(
                    scheduler = it.hasScheduler,
                    playbackSegmentIndex = it.schedulerPlaybackSegmentIndex,
                    completedWorkUnits = it.completedWorkUnits,
                    targetWindows = it.targetWindows,
                )
            }
        if (workIdentity != null && workIdentity != latestWorkIdentity) {
            latestWorkIdentity = workIdentity
            estimateStartedAtMs = safeNowMs
        }
        val elapsedInEstimateMs = if (workIdentity != null) {
            (safeNowMs - estimateStartedAtMs).coerceAtLeast(0L)
        } else {
            0L
        }
        val estimatedWindowMs = observation?.estimatedWindowMs
            ?: estimatedWindowMs(DEFAULT_SOURCE_SEPARATION_AVERAGE_WINDOW_MS)
        val targetProgress = if (
            observation != null &&
            observation.hasUsableProgressSource &&
            observation.targetWindows > 0
        ) {
            val initialLeadMs = (estimatedWindowMs / 5L)
                .coerceAtMost(PROGRESS_INITIAL_LEAD_MS)
            val estimatedWorkUnits = (
                    (elapsedInEstimateMs + initialLeadMs).toFloat() /
                            estimatedWindowMs.toFloat()
                    ).coerceIn(0f, PROGRESS_MAX_ESTIMATED_WORK_UNITS)
            val workProgress = (
                    observation.completedWorkUnits.toFloat() + estimatedWorkUnits
                    ) / observation.targetWindows.toFloat()
            (PROGRESS_WARMUP_CAP +
                    workProgress * (PROGRESS_MAX_TARGET - PROGRESS_WARMUP_CAP))
                .coerceIn(0f, PROGRESS_ABSOLUTE_CAP)
        } else {
            (((safeNowMs - startedAtMs).toFloat() / PROGRESS_WARMUP_DURATION_MS.toFloat()) *
                    PROGRESS_WARMUP_CAP)
                .coerceIn(0f, PROGRESS_WARMUP_CAP)
        }
        projectedProgress = max(projectedProgress, targetProgress)
        val elapsedSinceSampleMs = (safeNowMs - lastSampleAtMs).coerceAtLeast(0L)
        val maximumAdvance = elapsedSinceSampleMs * PROGRESS_MAX_ADVANCE_PER_MS
        displayedProgress = min(projectedProgress, displayedProgress + maximumAdvance)
        lastSampleAtMs = safeNowMs

        val remainingWorkUnits = when {
            observation == null -> 1
            observation.hasScheduler && observation.pendingWindows > 0 ->
                observation.pendingWindows
            else -> observation.targetWindows - observation.completedWorkUnits
        }.coerceAtLeast(1)
        val estimatedRemainingSeconds = ceil(
            ((remainingWorkUnits * estimatedWindowMs) - elapsedInEstimateMs)
                .coerceAtLeast(0L) / 1000.0
        ).toInt()
        val hideCompleteSnapshot = observation?.completeSnapshot == true

        return SourceSeparationPlaybackProcessingProgressState(
            progress = displayedProgress,
            estimatedRemainingSeconds = estimatedRemainingSeconds,
            readyWindows = if (hideCompleteSnapshot) 0 else observation?.readyWindows ?: 0,
            targetWindows = observation?.targetWindows ?: 0,
            pendingWindows = if (hideCompleteSnapshot) {
                observation.targetWindows
            } else {
                observation?.pendingWindows ?: 0
            },
            initialProcessingLabel = observation?.initialProcessingLabel.orEmpty(),
            hasScheduler = observation?.hasScheduler == true &&
                    observation.hasUsableProgressSource &&
                    !hideCompleteSnapshot,
        )
    }

    private data class WorkIdentity(
        val scheduler: Boolean,
        val playbackSegmentIndex: Int?,
        val completedWorkUnits: Int,
        val targetWindows: Int,
    )
}

internal fun SourceSeparationUiState.Running.toPlaybackProcessingProgressObservation():
        SourceSeparationPlaybackProcessingProgressObservation {
    val scheduler = scheduler
    val pendingWindows = scheduler?.playbackReadyWindowPendingCount?.coerceAtLeast(0) ?: 0
    val readyWindows = scheduler?.playbackReadyWindowReadyCount?.coerceAtLeast(0) ?: 0
    val targetWindows = scheduler?.readyWindowCount?.coerceAtLeast(1)
        ?: initialProcessingWindowCount()
    val initialCompletedUnits = initialProcessingCompletedUnits()
        .coerceIn(0, targetWindows.coerceAtLeast(0))
    val completeSchedulerSnapshot = scheduler != null &&
            targetWindows > 0 &&
            pendingWindows == 0 &&
            readyWindows >= targetWindows
    val completeInitialSnapshot = scheduler == null &&
            targetWindows > 0 &&
            initialCompletedUnits >= targetWindows
    val completeSnapshot = completeSchedulerSnapshot || completeInitialSnapshot
    val schedulerHasOnlyOutsidePendingWork = scheduler != null &&
            targetWindows > 0 &&
            pendingWindows > 0 &&
            readyWindows >= targetWindows
    val hasUsableProgressSource = !completeSnapshot && when {
        scheduler != null -> pendingWindows > 0 && !schedulerHasOnlyOutsidePendingWork
        sourceDecodeMode != null -> targetWindows > 0
        else -> false
    }

    return SourceSeparationPlaybackProcessingProgressObservation(
        schedulerPlaybackSegmentIndex = scheduler?.playbackSegmentIndex,
        hasScheduler = scheduler != null,
        hasUsableProgressSource = hasUsableProgressSource,
        completeSnapshot = completeSnapshot,
        completedWorkUnits = if (scheduler != null) {
            readyWindows.coerceIn(0, targetWindows.coerceAtLeast(0))
        } else {
            initialCompletedUnits
        },
        readyWindows = readyWindows,
        targetWindows = targetWindows,
        pendingWindows = pendingWindows,
        estimatedWindowMs = estimatedWindowMs(averageWindowMs),
        initialProcessingLabel = initialProcessingLabel(),
    )
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

private fun estimatedWindowMs(averageWindowMs: Long): Long {
    return (averageWindowMs.coerceAtLeast(MIN_SOURCE_SEPARATION_PROGRESS_WINDOW_MS) *
            PROGRESS_WINDOW_ESTIMATE_SCALE)
        .toLong()
        .coerceAtLeast(MIN_SOURCE_SEPARATION_PROGRESS_WINDOW_MS)
}

private const val MIN_SOURCE_SEPARATION_PROGRESS_WINDOW_MS = 500L
internal const val SOURCE_SEPARATION_PLAYBACK_PROGRESS_SAMPLE_TICK_MS = 50L
private const val SOURCE_SEPARATION_PLAYBACK_PROGRESS_COMPLETION_TAIL_MS = 200L
private const val PROGRESS_WINDOW_ESTIMATE_SCALE = 0.9
private const val PROGRESS_WARMUP_CAP = 0.2f
private const val PROGRESS_WARMUP_DURATION_MS = 3000L
private const val PROGRESS_MAX_TARGET = 0.9f
private const val PROGRESS_INITIAL_LEAD_MS = 700L
private const val PROGRESS_MAX_ESTIMATED_WORK_UNITS = 0.9f
private const val PROGRESS_ABSOLUTE_CAP = 0.98f
private const val PROGRESS_MAX_ADVANCE_PER_MS = 0.0006f
