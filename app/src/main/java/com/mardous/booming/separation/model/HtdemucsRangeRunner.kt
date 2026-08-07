package com.mardous.booming.separation.model

import com.mardous.booming.separation.audio.WavFileWriter
import com.mardous.booming.separation.SourceSeparationPauseReason
import com.mardous.booming.separation.SourceSeparationPausedException
import com.mardous.booming.separation.cache.SourceSeparationSegmentPlan
import com.mardous.booming.separation.cache.SourceSeparationSegmentState
import com.mardous.booming.separation.model.contract.StemId
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.math.roundToInt

internal interface HtdemucsTrackSource {
    val frameCount: Int

    /** Returns packed planar stereo with zero padding outside the track. */
    fun readPlanarStereo(
        startFrame: Int,
        frameCount: Int,
        shouldCancel: () -> Boolean,
    ): FloatArray
}

internal interface HtdemucsTrackInferenceSession : AutoCloseable {
    val orderedStemIds: List<String>

    fun runNormalizedWindow(
        normalizedPlanarStereo: FloatArray,
        shouldCancel: () -> Boolean,
    ): HtdemucsWindowStemSet
}

internal data class HtdemucsRangeStemFile(
    val stemId: StemId,
    val file: File,
)

internal data class HtdemucsRangePreparation(
    val stemFiles: List<HtdemucsRangeStemFile>,
    val outputFrameCount: Int,
    val outputSampleRate: Int,
    val windowCount: Int,
    val segmentPlan: SourceSeparationSegmentPlan,
)

internal data class HtdemucsRangeResult(
    val stemFiles: List<HtdemucsRangeStemFile>,
    val outputFrameCount: Int,
    val outputSampleRate: Int,
    val windowCount: Int,
    val elapsedMs: Long,
    val segmentPlan: SourceSeparationSegmentPlan,
)

internal data class HtdemucsRangeProgress(
    val completedWindows: Int,
    val totalWindows: Int,
    val stage: String,
)

internal class HtdemucsRangeRunner(
    private val source: HtdemucsTrackSource,
    private val session: HtdemucsTrackInferenceSession,
) {
    fun run(
        outputDirectory: File,
        segmentDirectory: File,
        onPrepared: (HtdemucsRangePreparation) -> Unit = {},
        onSegmentStateChanged: (Int, SourceSeparationSegmentState) -> Unit = { _, _ -> },
        onProgress: (HtdemucsRangeProgress) -> Unit = {},
        shouldPause: () -> Boolean = { false },
        pauseReasonProvider: () -> SourceSeparationPauseReason = {
            SourceSeparationPauseReason.Standard
        },
        shouldCancel: () -> Boolean = { false },
        requireWorkspaceAvailable: () -> Unit = {},
    ): HtdemucsRangeResult {
        val startedAt = System.nanoTime()
        val shouldInterrupt = interruptProbe(
            shouldPause,
            pauseReasonProvider,
            shouldCancel,
        )
        require(source.frameCount > 0) { "HTDemucs source is empty." }
        val stemIds = session.orderedStemIds.map(::StemId)
        require(stemIds.isNotEmpty() && stemIds.distinct().size == stemIds.size)
        val plans = HtdemucsTrackWindowPlanner.plans(source.frameCount)
        val segmentPlan = SourceSeparationSegmentPlan.build(
            rangeStartFrame = 0,
            rangeEndFrame = source.frameCount,
            sampleRate = HtdemucsPipelineAdapter.SAMPLE_RATE,
            generationSize = HtdemucsTrackWindowPlanner.STRIDE_SAMPLES,
            trim = 0,
            chunkSize = HtdemucsPipelineAdapter.WINDOW_SAMPLES,
            stemIds = stemIds,
            defaultState = SourceSeparationSegmentState.Queued,
        )
        require(segmentPlan.segmentCount == plans.size)
        requireWorkspaceAvailable()
        outputDirectory.mkdirs()
        segmentDirectory.mkdirs()
        val stemFiles = stemIds.mapIndexed { order, stemId ->
            HtdemucsRangeStemFile(stemId, File(outputDirectory, "stem-%02d.wav".format(order)))
        }
        val preparation = HtdemucsRangePreparation(
            stemFiles = stemFiles,
            outputFrameCount = source.frameCount,
            outputSampleRate = HtdemucsPipelineAdapter.SAMPLE_RATE,
            windowCount = plans.size,
            segmentPlan = segmentPlan,
        )
        onPrepared(preparation)

        onProgress(HtdemucsRangeProgress(0, plans.size, "Computing global normalization"))
        val normalization = computeNormalization(
            shouldPause,
            pauseReasonProvider,
            shouldInterrupt,
        )
        throwIfStopped(shouldPause, pauseReasonProvider, shouldCancel)
        val declaredBytes = source.frameCount.toLong() * HtdemucsPipelineAdapter.CHANNEL_COUNT *
            Short.SIZE_BYTES
        val writers = stemFiles.map { stem ->
            WavFileWriter(
                file = stem.file,
                sampleRate = HtdemucsPipelineAdapter.SAMPLE_RATE,
                channelCount = HtdemucsPipelineAdapter.CHANNEL_COUNT,
                declaredDataSizeBytes = declaredBytes,
            )
        }
        var completedWindows = 0
        var currentPlan = segmentPlan
        try {
            val ola = HtdemucsStreamingOverlapAdd(
                orderedStemIds = session.orderedStemIds,
                trackSamples = source.frameCount,
                normalization = normalization,
            )
            plans.forEach { plan ->
                throwIfStopped(shouldPause, pauseReasonProvider, shouldCancel)
                requireWorkspaceAvailable()
                currentPlan = currentPlan.withSegmentState(plan.index, SourceSeparationSegmentState.Running)
                onSegmentStateChanged(plan.index, SourceSeparationSegmentState.Running)
                onProgress(
                    HtdemucsRangeProgress(
                        completedWindows,
                        plans.size,
                        "Processing window ${plan.index + 1}/${plans.size}",
                    ),
                )
                val padded = source.readPlanarStereo(
                    plan.contextStart,
                    HtdemucsPipelineAdapter.WINDOW_SAMPLES,
                    shouldInterrupt,
                )
                normalizeInPlace(padded, normalization)
                val stemSet = session.runNormalizedWindow(padded, shouldInterrupt)
                ola.addWindow(plan, stemSet)?.let { chunk ->
                    publishChunk(
                        chunk = chunk,
                        segmentIndex = plan.index - 1,
                        writers = writers,
                        segmentPlan = currentPlan,
                        segmentDirectory = segmentDirectory,
                        requireWorkspaceAvailable = requireWorkspaceAvailable,
                    )
                    currentPlan = currentPlan.withSegmentState(
                        plan.index - 1,
                        SourceSeparationSegmentState.Ready,
                    )
                    onSegmentStateChanged(plan.index - 1, SourceSeparationSegmentState.Ready)
                    completedWindows += 1
                }
            }
            val tail = ola.finish()
            publishChunk(
                chunk = tail,
                segmentIndex = plans.lastIndex,
                writers = writers,
                segmentPlan = currentPlan,
                segmentDirectory = segmentDirectory,
                requireWorkspaceAvailable = requireWorkspaceAvailable,
            )
            currentPlan = currentPlan.withSegmentState(
                plans.lastIndex,
                SourceSeparationSegmentState.Ready,
            )
            onSegmentStateChanged(plans.lastIndex, SourceSeparationSegmentState.Ready)
            completedWindows += 1
            require(completedWindows == plans.size)
            onProgress(HtdemucsRangeProgress(completedWindows, plans.size, "Completed"))
        } finally {
            closeAll(writers)
        }
        return HtdemucsRangeResult(
            stemFiles = stemFiles,
            outputFrameCount = source.frameCount,
            outputSampleRate = HtdemucsPipelineAdapter.SAMPLE_RATE,
            windowCount = plans.size,
            elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L,
            segmentPlan = currentPlan,
        )
    }

    private fun computeNormalization(
        shouldPause: () -> Boolean,
        pauseReasonProvider: () -> SourceSeparationPauseReason,
        shouldInterrupt: () -> Boolean,
    ): HtdemucsGlobalNormalization {
        val accumulator = HtdemucsGlobalNormalizationAccumulator()
        var start = 0
        while (start < source.frameCount) {
            throwIfStopped(shouldPause, pauseReasonProvider, shouldInterrupt)
            val frames = minOf(NORMALIZATION_READ_FRAMES, source.frameCount - start)
            accumulator.add(source.readPlanarStereo(start, frames, shouldInterrupt))
            start += frames
        }
        return accumulator.finish()
    }

    private fun publishChunk(
        chunk: HtdemucsRenderedTrackChunk,
        segmentIndex: Int,
        writers: List<WavFileWriter>,
        segmentPlan: SourceSeparationSegmentPlan,
        segmentDirectory: File,
        requireWorkspaceAvailable: () -> Unit,
    ) {
        require(segmentIndex >= 0)
        val segment = segmentPlan.segments[segmentIndex]
        require(chunk.startFrame == segment.playbackStartFrame)
        require(chunk.frameCount == segment.playbackFrameCount)
        require(chunk.planarSamples.size ==
            writers.size * HtdemucsPipelineAdapter.CHANNEL_COUNT * chunk.frameCount)
        writers.indices.forEach { stemOrder ->
            requireWorkspaceAvailable()
            val pcm16 = planarStemToPcm16(chunk, stemOrder)
            writers[stemOrder].writePcm16AtFrame(chunk.startFrame, pcm16)
            val segmentFile = File(segmentDirectory.parentFile, segment.stems[stemOrder].path)
            segmentFile.parentFile?.mkdirs()
            WavFileWriter(
                file = segmentFile,
                sampleRate = HtdemucsPipelineAdapter.SAMPLE_RATE,
                channelCount = HtdemucsPipelineAdapter.CHANNEL_COUNT,
            ).use { writer -> writer.writePcm16(pcm16) }
        }
    }

    private fun planarStemToPcm16(
        chunk: HtdemucsRenderedTrackChunk,
        stemOrder: Int,
    ): ByteArray {
        val output = ByteArray(
            chunk.frameCount * HtdemucsPipelineAdapter.CHANNEL_COUNT * Short.SIZE_BYTES,
        )
        val planeBase = stemOrder * HtdemucsPipelineAdapter.CHANNEL_COUNT * chunk.frameCount
        var byteOffset = 0
        repeat(chunk.frameCount) { frame ->
            repeat(HtdemucsPipelineAdapter.CHANNEL_COUNT) { channel ->
                val sample = chunk.planarSamples[planeBase + channel * chunk.frameCount + frame]
                val pcm = (sample.coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                output[byteOffset++] = (pcm and 0xff).toByte()
                output[byteOffset++] = ((pcm ushr 8) and 0xff).toByte()
            }
        }
        return output
    }

    private fun normalizeInPlace(
        samples: FloatArray,
        normalization: HtdemucsGlobalNormalization,
    ) {
        samples.indices.forEach { index ->
            samples[index] = (samples[index] - normalization.mean) / normalization.divisor
        }
    }

    private fun closeAll(writers: List<WavFileWriter>) {
        var failure: Throwable? = null
        writers.asReversed().forEach { writer ->
            try {
                writer.close()
            } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }

    private fun throwIfStopped(
        shouldPause: () -> Boolean,
        pauseReasonProvider: () -> SourceSeparationPauseReason,
        shouldCancel: () -> Boolean,
    ) {
        if (shouldPause()) {
            throw SourceSeparationPausedException(pauseReason = pauseReasonProvider())
        }
        if (shouldCancel()) throw CancellationException("HTDemucs range run was canceled.")
    }

    private fun interruptProbe(
        shouldPause: () -> Boolean,
        pauseReasonProvider: () -> SourceSeparationPauseReason,
        shouldCancel: () -> Boolean,
    ): () -> Boolean = {
        if (shouldPause()) {
            throw SourceSeparationPausedException(pauseReason = pauseReasonProvider())
        }
        shouldCancel()
    }

    private companion object {
        const val NORMALIZATION_READ_FRAMES = 262_144
    }
}
