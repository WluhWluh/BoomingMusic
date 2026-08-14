package com.mardous.booming.separation.model

import com.mardous.booming.separation.cache.SourceSeparationSegmentPlan
import com.mardous.booming.separation.cache.SourceSeparationSegmentPriority
import com.mardous.booming.separation.cache.SourceSeparationSegmentReadinessRequirement
import com.mardous.booming.separation.cache.SourceSeparationSegmentState
import com.mardous.booming.separation.cache.playbackReadinessRequirements
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
    playbackFrame: Int? = null,
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
        playbackReadinessRequirements(
            playbackFrame = playbackFrame ?: segments[start].playbackStartFrame,
            readyWindowCount = safeReadyWindowCount,
        )
    }.orEmpty()
    fun isReadyAtPlayback(
        requirement: SourceSeparationSegmentReadinessRequirement,
    ): Boolean {
        val segment = requirement.segment
        if (segment.state.isPlaybackReady) return true
        if (segment.state != SourceSeparationSegmentState.Provisional) return false
        return segment.playableFromFrame != null &&
            requirement.requiredFrame >= segment.playableFromFrame
    }
    val processingRequirement = playbackWindow.singleOrNull {
        it.segment.index == processingSegmentIndex
    }
    val processingReady = when {
        processingRequirement != null -> isReadyAtPlayback(processingRequirement)
        processingSegmentIndex in segments.indices ->
            segments[processingSegmentIndex].state.hasPlaybackArtifact
        else -> false
    }
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
        playbackReadyWindowReadyCount = playbackWindow.count(::isReadyAtPlayback),
        playbackReadyWindowPendingCount = playbackWindow.count { !isReadyAtPlayback(it) } +
            if (!processingReady &&
                processingSegmentIndex >= 0 &&
                playbackWindow.none { it.segment.index == processingSegmentIndex }
            ) 1 else 0,
    )
}
