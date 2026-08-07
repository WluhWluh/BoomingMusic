package com.mardous.booming.separation.model

import kotlin.math.max
import kotlin.math.min

data class HtdemucsWindowPlan(
    val index: Int,
    val trackSamples: Int,
    val offset: Int,
    val actualSamples: Int,
    val contextStart: Int,
    val contextEnd: Int,
    val sourceStart: Int,
    val sourceEnd: Int,
    val padLeft: Int,
    val padRight: Int,
    val cropLeft: Int,
    val cropRight: Int,
)

data class HtdemucsWindowOutput(
    val offset: Int,
    val planarSamples: FloatArray,
)

data class HtdemucsOlaResult(
    val planarSamples: FloatArray,
    val accumulatedWeights: FloatArray,
)

/** Canonical 75%-stride window planning and triangular overlap-add for HTDemucs. */
class HtdemucsStreamingPlan {
    fun windowPlans(trackSamples: Int): List<HtdemucsWindowPlan> {
        require(trackSamples > 0) { "trackSamples must be positive." }
        require(trackSamples <= Int.MAX_VALUE - WINDOW_SAMPLES) {
            "trackSamples is too large for canonical window planning."
        }
        val plans = mutableListOf<HtdemucsWindowPlan>()
        var offset = 0
        while (offset < trackSamples) {
            val actualSamples = min(trackSamples - offset, WINDOW_SAMPLES)
            val missing = WINDOW_SAMPLES - actualSamples
            val cropLeft = missing / 2
            val cropRight = missing - cropLeft
            val contextStart = offset - cropLeft
            val contextEnd = contextStart + WINDOW_SAMPLES
            val sourceStart = max(0, contextStart)
            val sourceEnd = min(trackSamples, contextEnd)
            plans += HtdemucsWindowPlan(
                index = plans.size,
                trackSamples = trackSamples,
                offset = offset,
                actualSamples = actualSamples,
                contextStart = contextStart,
                contextEnd = contextEnd,
                sourceStart = sourceStart,
                sourceEnd = sourceEnd,
                padLeft = sourceStart - contextStart,
                padRight = contextEnd - sourceEnd,
                cropLeft = cropLeft,
                cropRight = cropRight,
            )
            offset = Math.addExact(offset, STRIDE_SAMPLES)
        }
        return plans
    }

    fun extractPaddedWindow(
        planarTrack: FloatArray,
        planeCount: Int,
        window: HtdemucsWindowPlan,
    ): FloatArray {
        require(planeCount > 0 && planarTrack.size % planeCount == 0)
        val trackSamples = planarTrack.size / planeCount
        require(window.trackSamples == trackSamples) { "Window belongs to a different track." }
        val expectedPlans = windowPlans(trackSamples)
        require(window.index in expectedPlans.indices && expectedPlans[window.index] == window) {
            "Window does not match the canonical ascending plan."
        }
        return FloatArray(Math.multiplyExact(planeCount, WINDOW_SAMPLES)).also { padded ->
            repeat(planeCount) { plane ->
                planarTrack.copyInto(
                    destination = padded,
                    destinationOffset = plane * WINDOW_SAMPLES + window.padLeft,
                    startIndex = plane * trackSamples + window.sourceStart,
                    endIndex = plane * trackSamples + window.sourceEnd,
                )
            }
        }
    }

    fun triangleWeight(index: Int): Float {
        require(index in 0 until WINDOW_SAMPLES)
        val numerator = if (index < WEIGHT_PEAK_SAMPLES) index + 1 else WINDOW_SAMPLES - index
        return numerator.toFloat() / WEIGHT_PEAK_SAMPLES.toFloat()
    }

    fun overlapAdd(
        trackSamples: Int,
        outputPlaneCount: Int,
        windowOutputs: List<HtdemucsWindowOutput>,
    ): HtdemucsOlaResult {
        require(outputPlaneCount > 0)
        val windows = windowPlans(trackSamples)
        require(windowOutputs.size == windows.size) {
            "Expected ${windows.size} window outputs, received ${windowOutputs.size}."
        }
        val output = FloatArray(Math.multiplyExact(outputPlaneCount, trackSamples))
        val accumulatedWeights = FloatArray(trackSamples)
        windows.forEachIndexed { index, window ->
            val windowOutput = windowOutputs[index]
            require(windowOutput.offset == window.offset) {
                "Window outputs must be supplied in ascending offset order."
            }
            require(
                windowOutput.planarSamples.size == Math.multiplyExact(outputPlaneCount, WINDOW_SAMPLES),
            ) { "Each model output must contain one complete canonical window." }
            repeat(window.actualSamples) { localSample ->
                val trackSample = window.offset + localSample
                val weight = triangleWeight(localSample)
                accumulatedWeights[trackSample] += weight
                repeat(outputPlaneCount) { plane ->
                    val outputIndex = plane * trackSamples + trackSample
                    val windowIndex = plane * WINDOW_SAMPLES + window.cropLeft + localSample
                    output[outputIndex] += windowOutput.planarSamples[windowIndex] * weight
                }
            }
        }
        accumulatedWeights.forEachIndexed { sample, weight ->
            check(weight > 0f) { "OLA weight is not positive at sample $sample." }
            repeat(outputPlaneCount) { plane ->
                val index = plane * trackSamples + sample
                output[index] /= weight
            }
        }
        return HtdemucsOlaResult(output, accumulatedWeights)
    }

    companion object {
        const val WINDOW_SAMPLES = HtdemucsPipelineAdapter.WINDOW_SAMPLES
        const val STRIDE_SAMPLES = 257_985
        const val OVERLAP_SAMPLES = 85_995
        private const val WEIGHT_PEAK_SAMPLES = WINDOW_SAMPLES / 2
    }
}
