package com.mardous.booming.separation.cache

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
    val segments: List<SourceSeparationSegment>,
) {
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
                            vocalsPath = segmentStemPath(index, "vocals"),
                            instrumentalPath = segmentStemPath(index, "instrumental"),
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
                segments = segments,
            )
        }
    }
}

private fun segmentStemPath(index: Int, stemName: String): String {
    return "segments/%05d_%s.wav".format(index, stemName)
}

@Serializable
data class SourceSeparationSegment(
    val index: Int,
    val playbackStartFrame: Int,
    val playbackEndFrame: Int,
    val windowStartFrame: Int,
    val windowEndFrame: Int,
    val vocalsPath: String,
    val instrumentalPath: String,
    val state: SourceSeparationSegmentState = SourceSeparationSegmentState.Ready,
) {
    val playbackFrameCount: Int
        get() = (playbackEndFrame - playbackStartFrame).coerceAtLeast(0)
}

@Serializable
enum class SourceSeparationSegmentState {
    Missing,
    Queued,
    Running,
    Ready,
    Failed,
}
