package com.mardous.booming.separation.cache

data class SourceSeparationSegmentWorkItem(
    val segment: SourceSeparationSegment,
    val priority: SourceSeparationSegmentPriority,
    val state: SourceSeparationSegmentState,
)

enum class SourceSeparationSegmentPriority {
    CurrentPlayback,
    NextPlayback,
    NearFuture,
    EarlierMissing,
    IdleBackfill,
}

object SourceSeparationSegmentScheduler {
    fun prioritize(
        segmentPlan: SourceSeparationSegmentPlan,
        playbackFrame: Int,
        readyWindowCount: Int = DEFAULT_READY_WINDOW_COUNT,
        nearFutureCount: Int = DEFAULT_NEAR_FUTURE_COUNT,
    ): List<SourceSeparationSegmentWorkItem> {
        val pendingSegments = segmentPlan.segments
            .filter { it.state != SourceSeparationSegmentState.Ready }
        if (pendingSegments.isEmpty()) {
            return emptyList()
        }

        val currentIndex = segmentPlan.segmentIndexForFrame(playbackFrame)
        return pendingSegments
            .map { segment ->
                SourceSeparationSegmentWorkItem(
                    segment = segment,
                    priority = segment.priorityFor(
                        currentIndex = currentIndex,
                        readyWindowCount = readyWindowCount,
                        nearFutureCount = nearFutureCount,
                    ),
                    state = segment.state,
                )
            }
            .sortedByPriority(currentIndex)
    }

    private fun List<SourceSeparationSegmentWorkItem>.sortedByPriority(
        currentIndex: Int,
    ): List<SourceSeparationSegmentWorkItem> {
        return sortedWith(
            compareBy<SourceSeparationSegmentWorkItem> { it.priority.ordinal }
                .thenBy { distanceFromPlayback(it.segment.index, currentIndex) }
                .thenBy { it.segment.index }
        )
    }

    private fun SourceSeparationSegment.priorityFor(
        currentIndex: Int,
        readyWindowCount: Int,
        nearFutureCount: Int,
    ): SourceSeparationSegmentPriority {
        val lastPlaybackBufferIndex = currentIndex + readyWindowCount.coerceAtLeast(1) - 1
        return when {
            index == currentIndex -> SourceSeparationSegmentPriority.CurrentPlayback
            index in (currentIndex + 1)..lastPlaybackBufferIndex ->
                SourceSeparationSegmentPriority.NextPlayback
            index > lastPlaybackBufferIndex && index <= currentIndex + nearFutureCount ->
                SourceSeparationSegmentPriority.NearFuture
            index < currentIndex -> SourceSeparationSegmentPriority.EarlierMissing
            else -> SourceSeparationSegmentPriority.IdleBackfill
        }
    }

    private fun distanceFromPlayback(segmentIndex: Int, currentIndex: Int): Int {
        return if (segmentIndex >= currentIndex) {
            segmentIndex - currentIndex
        } else {
            currentIndex - segmentIndex
        }
    }

    private const val DEFAULT_NEAR_FUTURE_COUNT = 4
    private const val DEFAULT_READY_WINDOW_COUNT = 2
}
