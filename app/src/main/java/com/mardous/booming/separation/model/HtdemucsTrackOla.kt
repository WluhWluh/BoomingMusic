package com.mardous.booming.separation.model

import kotlin.math.sqrt

data class HtdemucsTrackWindowPlan(
    val index: Int,
    val offset: Int,
    val actualSamples: Int,
    val contextStart: Int,
    val sourceStart: Int,
    val sourceEnd: Int,
    val padLeft: Int,
    val padRight: Int,
    val cropLeft: Int,
    val cropRight: Int,
)

object HtdemucsTrackWindowPlanner {
    const val STRIDE_SAMPLES = 257_985
    const val OVERLAP_SAMPLES = HtdemucsPipelineAdapter.WINDOW_SAMPLES - STRIDE_SAMPLES

    fun plans(trackSamples: Int): List<HtdemucsTrackWindowPlan> {
        require(trackSamples > 0) { "HTDemucs track must contain audio samples." }
        return buildList {
            var offset = 0
            var index = 0
            while (offset < trackSamples) {
                val actual = minOf(trackSamples - offset, HtdemucsPipelineAdapter.WINDOW_SAMPLES)
                val delta = HtdemucsPipelineAdapter.WINDOW_SAMPLES - actual
                val contextStart = offset - delta / 2
                val contextEnd = contextStart + HtdemucsPipelineAdapter.WINDOW_SAMPLES
                val sourceStart = maxOf(0, contextStart)
                val sourceEnd = minOf(trackSamples, contextEnd)
                add(
                    HtdemucsTrackWindowPlan(
                        index = index,
                        offset = offset,
                        actualSamples = actual,
                        contextStart = contextStart,
                        sourceStart = sourceStart,
                        sourceEnd = sourceEnd,
                        padLeft = sourceStart - contextStart,
                        padRight = contextEnd - sourceEnd,
                        cropLeft = delta / 2,
                        cropRight = delta - delta / 2,
                    ),
                )
                offset += STRIDE_SAMPLES
                index += 1
            }
        }
    }

    fun paddedWindow(
        planarStereoTrack: FloatArray,
        plan: HtdemucsTrackWindowPlan,
    ): FloatArray {
        require(planarStereoTrack.size % HtdemucsPipelineAdapter.CHANNEL_COUNT == 0)
        val trackSamples = planarStereoTrack.size / HtdemucsPipelineAdapter.CHANNEL_COUNT
        require(plan.sourceEnd <= trackSamples)
        return FloatArray(
            HtdemucsPipelineAdapter.CHANNEL_COUNT * HtdemucsPipelineAdapter.WINDOW_SAMPLES,
        ).also { output ->
            val copiedSamples = plan.sourceEnd - plan.sourceStart
            repeat(HtdemucsPipelineAdapter.CHANNEL_COUNT) { channel ->
                planarStereoTrack.copyInto(
                    destination = output,
                    destinationOffset = channel * HtdemucsPipelineAdapter.WINDOW_SAMPLES + plan.padLeft,
                    startIndex = channel * trackSamples + plan.sourceStart,
                    endIndex = channel * trackSamples + plan.sourceEnd,
                )
                require(plan.padLeft + copiedSamples + plan.padRight ==
                    HtdemucsPipelineAdapter.WINDOW_SAMPLES)
            }
        }
    }
}

class HtdemucsGlobalNormalizationAccumulator {
    private var count = 0L
    private var mean = 0.0
    private var sumSquaredDeviation = 0.0

    fun add(planarStereo: FloatArray) {
        require(planarStereo.size % HtdemucsPipelineAdapter.CHANNEL_COUNT == 0)
        val frames = planarStereo.size / HtdemucsPipelineAdapter.CHANNEL_COUNT
        repeat(frames) { frame ->
            val mono = (
                planarStereo[frame].toDouble() + planarStereo[frames + frame].toDouble()
                ) * 0.5
            require(mono.isFinite()) { "Normalization input must be finite." }
            count += 1
            val delta = mono - mean
            mean += delta / count
            val nextDelta = mono - mean
            sumSquaredDeviation += delta * nextDelta
        }
    }

    fun finish(): HtdemucsGlobalNormalization {
        require(count >= 2L) { "HTDemucs normalization requires at least two samples." }
        val standardDeviation = sqrt(sumSquaredDeviation / (count - 1L)).toFloat()
        val divisor = standardDeviation + HtdemucsPipelineAdapter.NORMALIZATION_EPSILON
        require(mean.toFloat().isFinite() && divisor.isFinite() && divisor > 0f)
        return HtdemucsGlobalNormalization(mean.toFloat(), standardDeviation, divisor)
    }
}

data class HtdemucsRenderedTrackChunk(
    val startFrame: Int,
    val frameCount: Int,
    /** Packed planar [stem, channel, frame] samples. */
    val planarSamples: FloatArray,
)

internal class HtdemucsStreamingOverlapAdd(
    private val orderedStemIds: List<String>,
    private val trackSamples: Int,
    private val normalization: HtdemucsGlobalNormalization,
    initialPlanIndex: Int = 0,
    initialBufferStart: Int = 0,
) {
    private val planeCount = orderedStemIds.size * HtdemucsPipelineAdapter.CHANNEL_COUNT
    private val accumulation = FloatArray(planeCount * HtdemucsPipelineAdapter.WINDOW_SAMPLES)
    private val accumulatedWeight = FloatArray(HtdemucsPipelineAdapter.WINDOW_SAMPLES)
    private val weight = triangularWeight()
    private var bufferStart = initialBufferStart
    private var nextPlanIndex = initialPlanIndex
    private var finished = false

    init {
        require(orderedStemIds.isNotEmpty() && orderedStemIds.distinct().size == orderedStemIds.size)
        require(trackSamples > 0)
        require(initialPlanIndex >= 0)
        require(initialBufferStart in 0 until trackSamples)
    }

    fun addWindow(
        plan: HtdemucsTrackWindowPlan,
        stemSet: HtdemucsWindowStemSet,
    ): HtdemucsRenderedTrackChunk? {
        check(!finished) { "HTDemucs overlap-add is finished." }
        require(plan.index == nextPlanIndex) { "HTDemucs windows must be added in order." }
        require(plan.offset >= bufferStart)
        require(stemSet.orderedStemIds == orderedStemIds)
        require(stemSet.samplesPerStem == HtdemucsPipelineAdapter.WINDOW_SAMPLES)
        require(stemSet.planarSamples.size == planeCount * HtdemucsPipelineAdapter.WINDOW_SAMPLES)

        val ready = if (plan.offset > bufferStart) flush(plan.offset - bufferStart) else null
        require(bufferStart == plan.offset)
        val activeStart = plan.cropLeft
        repeat(planeCount) { plane ->
            val inputOffset = plane * HtdemucsPipelineAdapter.WINDOW_SAMPLES + activeStart
            val outputOffset = plane * HtdemucsPipelineAdapter.WINDOW_SAMPLES
            repeat(plan.actualSamples) { sample ->
                accumulation[outputOffset + sample] +=
                    stemSet.planarSamples[inputOffset + sample] * weight[sample]
            }
        }
        repeat(plan.actualSamples) { sample -> accumulatedWeight[sample] += weight[sample] }
        nextPlanIndex += 1
        return ready
    }

    fun finish(): HtdemucsRenderedTrackChunk {
        check(!finished) { "HTDemucs overlap-add is already finished." }
        finished = true
        require(nextPlanIndex > 0) { "HTDemucs overlap-add has no windows." }
        return requireNotNull(flush(trackSamples - bufferStart))
    }

    private fun flush(frames: Int): HtdemucsRenderedTrackChunk? {
        require(frames in 0..HtdemucsPipelineAdapter.WINDOW_SAMPLES)
        if (frames == 0) return null
        val start = bufferStart
        val output = FloatArray(planeCount * frames)
        repeat(frames) { frame -> require(accumulatedWeight[frame] > 0f) }
        repeat(planeCount) { plane ->
            val inputOffset = plane * HtdemucsPipelineAdapter.WINDOW_SAMPLES
            val outputOffset = plane * frames
            repeat(frames) { frame ->
                val normalized = accumulation[inputOffset + frame] / accumulatedWeight[frame]
                output[outputOffset + frame] =
                    normalized * normalization.divisor + normalization.mean
            }
            accumulation.copyInto(
                destination = accumulation,
                destinationOffset = inputOffset,
                startIndex = inputOffset + frames,
                endIndex = inputOffset + HtdemucsPipelineAdapter.WINDOW_SAMPLES,
            )
            accumulation.fill(
                0f,
                inputOffset + HtdemucsPipelineAdapter.WINDOW_SAMPLES - frames,
                inputOffset + HtdemucsPipelineAdapter.WINDOW_SAMPLES,
            )
        }
        accumulatedWeight.copyInto(
            destination = accumulatedWeight,
            destinationOffset = 0,
            startIndex = frames,
            endIndex = HtdemucsPipelineAdapter.WINDOW_SAMPLES,
        )
        accumulatedWeight.fill(
            0f,
            HtdemucsPipelineAdapter.WINDOW_SAMPLES - frames,
            HtdemucsPipelineAdapter.WINDOW_SAMPLES,
        )
        bufferStart += frames
        return HtdemucsRenderedTrackChunk(start, frames, output)
    }

    private fun triangularWeight(): FloatArray {
        val midpoint = HtdemucsPipelineAdapter.WINDOW_SAMPLES / 2
        val maximum = midpoint.toFloat()
        return FloatArray(HtdemucsPipelineAdapter.WINDOW_SAMPLES) { index ->
            val unscaled = if (index < midpoint) index + 1 else HtdemucsPipelineAdapter.WINDOW_SAMPLES - index
            unscaled / maximum
        }
    }
}
