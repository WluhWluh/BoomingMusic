package com.mardous.booming.separation.model

import com.mardous.booming.separation.cache.SourceSeparationSegmentPlan
import com.mardous.booming.separation.cache.SourceSeparationSegmentPriority
import kotlinx.serialization.Serializable

/** Family-neutral scheduler snapshot shared by MDX and multi-stem pipelines. */
@Serializable
data class SourceSeparationSegmentSchedulerProgress(
    val playbackSegmentIndex: Int?,
    val playbackSegmentState: String?,
    val nextSegmentIndex: Int?,
    val nextSegmentState: String?,
    val processingSegmentIndex: Int,
    val priority: String?,
    val readySegments: Int,
    val totalSegments: Int,
    val readyWindowCount: Int,
    val playbackReadyWindowReadyCount: Int,
    val playbackReadyWindowPendingCount: Int,
)

internal fun SourceSeparationSegmentPlan.schedulerProgress(
    playbackSegmentIndex: Int?,
    processingSegmentIndex: Int,
    readyWindowCount: Int,
    priority: SourceSeparationSegmentPriority? = null,
): SourceSeparationSegmentSchedulerProgress {
    val safeReadyWindowCount = readyWindowCount.coerceAtLeast(1)
    val safePlaybackIndex = playbackSegmentIndex?.takeIf { it in segments.indices }
    val nextIndex = safePlaybackIndex
        ?.plus(1)
        ?.takeIf { it < segments.size }
    val playbackWindow = safePlaybackIndex?.let { start ->
        segments.subList(
            start,
            (start + safeReadyWindowCount).coerceAtMost(segments.size),
        )
    }.orEmpty()
    val processingReady = segments.getOrNull(processingSegmentIndex)?.state
        ?.isPlaybackReady == true
    val effectivePriority = priority ?: safePlaybackIndex?.let { current ->
        com.mardous.booming.separation.cache.SourceSeparationSegmentScheduler
            .prioritize(
                segmentPlan = this,
                playbackFrame = segments[current].playbackStartFrame,
                readyWindowCount = safeReadyWindowCount,
            )
            .firstOrNull { it.segment.index == processingSegmentIndex }
            ?.priority
    }
    return SourceSeparationSegmentSchedulerProgress(
        playbackSegmentIndex = safePlaybackIndex,
        playbackSegmentState = safePlaybackIndex?.let { segments[it].state.name },
        nextSegmentIndex = nextIndex,
        nextSegmentState = nextIndex?.let { segments[it].state.name },
        processingSegmentIndex = processingSegmentIndex,
        priority = effectivePriority?.name,
        readySegments = segments.count { it.state.isPlaybackReady },
        totalSegments = segments.size,
        readyWindowCount = safeReadyWindowCount,
        playbackReadyWindowReadyCount = playbackWindow.count { it.state.isPlaybackReady },
        playbackReadyWindowPendingCount = playbackWindow.count { !it.state.isPlaybackReady } +
            if (!processingReady &&
                processingSegmentIndex >= 0 &&
                playbackWindow.none { it.index == processingSegmentIndex }
            ) 1 else 0,
    )
}
