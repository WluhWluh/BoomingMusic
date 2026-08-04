package com.mardous.booming.separation.cache

import com.mardous.booming.separation.model.contract.StemId
import kotlinx.serialization.Serializable
import kotlin.math.ceil

@Serializable
data class SourceSeparationSegmentPlan(
    val rangeStartFrame: Int,
    val rangeEndFrame: Int,
    val sampleRate: Int,
    val generationSize: Int,
    val trim: Int,
    val chunkSize: Int,
    val stemIds: List<StemId>,
    val segments: List<SourceSeparationSegment>,
) {
    init {
        require(rangeStartFrame >= 0 && rangeEndFrame >= rangeStartFrame) {
            "Segment plan frame range is invalid."
        }
        require(sampleRate > 0 && generationSize > 0 && trim >= 0 && chunkSize > 0) {
            "Segment plan audio geometry is invalid."
        }
        require(stemIds.isNotEmpty() && stemIds.distinct().size == stemIds.size) {
            "Segment plan stem IDs must be non-empty and unique."
        }
        require(segments.map(SourceSeparationSegment::index) == segments.indices.toList()) {
            "Segment plan indexes must be contiguous."
        }
        segments.forEach { segment ->
            require(segment.stems.map(SourceSeparationSegmentStemPath::stemId) == stemIds) {
                "Segment ${segment.index} does not contain the complete ordered stem set."
            }
        }
        require(segments.flatMap(SourceSeparationSegment::stems)
            .map(SourceSeparationSegmentStemPath::path).distinct().size ==
            segments.sumOf { it.stems.size }
        ) { "Segment plan stem paths must be unique across the complete plan." }
    }

    val rangeFrameCount: Int
        get() = (rangeEndFrame - rangeStartFrame).coerceAtLeast(0)

    val segmentCount: Int
        get() = segments.size

    fun segmentIndexForFrame(frame: Int): Int {
        if (segments.isEmpty()) return 0
        val relativeFrame = (frame - rangeStartFrame).coerceAtLeast(0)
        return (relativeFrame / generationSize).coerceIn(0, segments.lastIndex)
    }

    fun withSegmentState(
        segmentIndex: Int,
        state: SourceSeparationSegmentState,
    ): SourceSeparationSegmentPlan {
        return copy(
            segments = segments.map { segment ->
                if (segment.index == segmentIndex) {
                    segment.copy(state = state)
                } else {
                    segment
                }
            }
        )
    }

    companion object {
        fun build(
            rangeStartFrame: Int,
            rangeEndFrame: Int,
            sampleRate: Int,
            generationSize: Int,
            trim: Int,
            chunkSize: Int,
            stemIds: List<StemId> = StemId.MdxOrdered,
            defaultState: SourceSeparationSegmentState = SourceSeparationSegmentState.Ready,
        ): SourceSeparationSegmentPlan {
            val safeStartFrame = rangeStartFrame.coerceAtLeast(0)
            val safeEndFrame = rangeEndFrame.coerceAtLeast(safeStartFrame)
            val frameCount = safeEndFrame - safeStartFrame
            val segmentCount = if (frameCount > 0) {
                ceil(frameCount.toDouble() / generationSize.toDouble()).toInt()
            } else {
                0
            }

            val segments = buildList(segmentCount) {
                for (index in 0 until segmentCount) {
                    val playbackStartFrame = safeStartFrame + index * generationSize
                    val playbackEndFrame = minOf(playbackStartFrame + generationSize, safeEndFrame)
                    val windowStartFrame = playbackStartFrame - trim
                    val windowEndFrame = windowStartFrame + chunkSize
                    add(
                        SourceSeparationSegment(
                            index = index,
                            playbackStartFrame = playbackStartFrame,
                            playbackEndFrame = playbackEndFrame,
                            windowStartFrame = windowStartFrame,
                            windowEndFrame = windowEndFrame,
                            stems = stemIds.mapIndexed { order, stemId ->
                                SourceSeparationSegmentStemPath(
                                    stemId = stemId,
                                    order = order,
                                    path = segmentStemPath(index, order),
                                )
                            },
                            state = defaultState,
                        )
                    )
                }
            }

            return SourceSeparationSegmentPlan(
                rangeStartFrame = safeStartFrame,
                rangeEndFrame = safeEndFrame,
                sampleRate = sampleRate,
                generationSize = generationSize,
                trim = trim,
                chunkSize = chunkSize,
                stemIds = stemIds,
                segments = segments,
            )
        }
    }
}

private fun segmentStemPath(index: Int, stemOrder: Int): String {
    return "segments/%05d/stem-%02d.wav".format(index, stemOrder)
}

@Serializable
data class SourceSeparationSegment(
    val index: Int,
    val playbackStartFrame: Int,
    val playbackEndFrame: Int,
    val windowStartFrame: Int,
    val windowEndFrame: Int,
    val stems: List<SourceSeparationSegmentStemPath>,
    val state: SourceSeparationSegmentState = SourceSeparationSegmentState.Ready,
) {
    init {
        require(index >= 0) { "Segment index is invalid." }
        require(stems.isNotEmpty()) { "Segment stem paths are empty." }
        require(stems.map(SourceSeparationSegmentStemPath::stemId).distinct().size == stems.size) {
            "Segment stem IDs must be unique."
        }
        require(stems.map(SourceSeparationSegmentStemPath::order) == stems.indices.toList()) {
            "Segment stem order must be contiguous."
        }
        require(stems.map(SourceSeparationSegmentStemPath::path).distinct().size == stems.size) {
            "Segment stem paths must be unique."
        }
    }

    val playbackFrameCount: Int
        get() = (playbackEndFrame - playbackStartFrame).coerceAtLeast(0)

    fun pathFor(stemId: StemId): String = requireNotNull(
        stems.singleOrNull { it.stemId == stemId }?.path
    ) { "Segment $index has no path for stem $stemId." }
}

@Serializable
data class SourceSeparationSegmentStemPath(
    val stemId: StemId,
    val order: Int,
    val path: String,
) {
    init {
        require(order >= 0) { "Segment stem order is invalid." }
        SourceSeparationCacheRelativePath.requireValid(path)
    }
}

@Serializable
enum class SourceSeparationSegmentState {
    Missing,
    Queued,
    Running,
    Misaligned,
    Ready,
    Failed,

    ;

    val isComplete: Boolean
        get() = this == Ready

    val isPlaybackReady: Boolean
        get() = this == Ready || this == Misaligned
}
