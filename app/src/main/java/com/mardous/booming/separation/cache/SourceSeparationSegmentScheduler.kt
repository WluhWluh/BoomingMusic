package com.mardous.booming.separation.cache

data class SourceSeparationSegmentWorkItem(
    val segment: SourceSeparationSegment,
    val priority: SourceSeparationSegmentPriority,
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
                    priority = segment.priorityFor(currentIndex, nearFutureCount),
                )
            }
            .sortedWith(
                compareBy<SourceSeparationSegmentWorkItem> { it.priority.ordinal }
                    .thenBy { distanceFromPlayback(it.segment.index, currentIndex) }
                    .thenBy { it.segment.index }
            )
    }

    private fun SourceSeparationSegment.priorityFor(
        currentIndex: Int,
        nearFutureCount: Int,
    ): SourceSeparationSegmentPriority {
        return when {
            index == currentIndex -> SourceSeparationSegmentPriority.CurrentPlayback
            index == currentIndex + 1 -> SourceSeparationSegmentPriority.NextPlayback
            index > currentIndex + 1 && index <= currentIndex + nearFutureCount ->
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
}
