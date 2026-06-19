package com.mardous.booming.separation.cache

import java.io.File

data class SourceSeparationSegmentSnapshot(
    val manifest: SourceSeparationManifest,
    val segmentPlan: SourceSeparationSegmentPlan,
    val segments: List<SourceSeparationSegmentFileState>,
) {
    val readyCount: Int
        get() = segments.count { it.state == SourceSeparationSegmentState.Ready }

    val totalCount: Int
        get() = segments.size

    val percentReady: Int
        get() = if (totalCount > 0) {
            ((readyCount * 100.0) / totalCount).toInt()
        } else {
            0
        }

    fun segmentAtFrame(frame: Int): SourceSeparationSegmentFileState? {
        return segments.getOrNull(segmentPlan.segmentIndexForFrame(frame))
    }

    fun hasReadyPlaybackWindowAtFrame(
        frame: Int,
        readyWindowCount: Int = DEFAULT_READY_WINDOW_COUNT,
    ): Boolean {
        if (segments.isEmpty()) return false
        val segmentIndex = segmentPlan.segmentIndexForFrame(frame)
        return playbackWindowStatesAt(
            segmentIndex = segmentIndex,
            readyWindowCount = readyWindowCount,
        ).all { it.isReady }
    }

    fun playbackWindowStatesAt(
        segmentIndex: Int,
        readyWindowCount: Int = DEFAULT_READY_WINDOW_COUNT,
    ): List<SourceSeparationSegmentFileState> {
        if (segments.isEmpty()) return emptyList()
        val safeReadyWindowCount = readyWindowCount.coerceAtLeast(1)
        val safeSegmentIndex = segmentIndex.coerceIn(segments.indices)
        val endExclusive = (safeSegmentIndex + safeReadyWindowCount)
            .coerceAtMost(segments.size)
        return segments.subList(safeSegmentIndex, endExclusive)
    }

    companion object {
        const val DEFAULT_READY_WINDOW_COUNT = 2
    }
}

data class SourceSeparationSegmentFileState(
    val segment: SourceSeparationSegment,
    val state: SourceSeparationSegmentState,
    val vocalsFile: File,
    val instrumentalFile: File,
    val vocalsReady: Boolean,
    val instrumentalReady: Boolean,
) {
    val isReady: Boolean
        get() = state == SourceSeparationSegmentState.Ready
}
