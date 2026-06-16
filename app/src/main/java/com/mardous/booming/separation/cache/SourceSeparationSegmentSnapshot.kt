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
