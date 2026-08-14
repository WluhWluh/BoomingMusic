package com.mardous.booming.separation.model

import com.mardous.booming.separation.audio.WavFileWriter
import com.mardous.booming.separation.audio.Pcm16WavFileReader
import com.mardous.booming.separation.SourceSeparationPauseReason
import com.mardous.booming.separation.SourceSeparationPausedException
import com.mardous.booming.separation.cache.SourceSeparationSegmentPlan
import com.mardous.booming.separation.cache.SourceSeparationSegmentScheduler
import com.mardous.booming.separation.cache.SourceSeparationSegmentState
import com.mardous.booming.separation.model.contract.StemId
import java.io.File
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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

internal data class HtdemucsRangeResumeState(
    val segmentPlan: SourceSeparationSegmentPlan,
)

internal data class HtdemucsRangeProgress(
    val completedWindows: Int,
    val totalWindows: Int,
    val stage: String,
    val completedWindowElapsedMs: Long? = null,
    val scheduler: SourceSeparationSegmentSchedulerProgress? = null,
)

internal class HtdemucsRangeRunner(
    private val source: HtdemucsTrackSource,
    private val session: HtdemucsTrackInferenceSession,
) {
    fun run(
        outputDirectory: File,
        segmentDirectory: File,
        resumeState: HtdemucsRangeResumeState? = null,
        onPrepared: (HtdemucsRangePreparation) -> Unit = {},
        onSegmentStateChanged: (Int, SourceSeparationSegmentState, Int?) -> Unit =
            { _, _, _ -> },
        onProgress: (HtdemucsRangeProgress) -> Unit = {},
        playbackPositionMsProvider: () -> Long? = { null },
        playbackReadyWindowCountProvider: () -> Int = { DEFAULT_READY_WINDOW_COUNT },
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
        val freshSegmentPlan = SourceSeparationSegmentPlan.build(
            rangeStartFrame = 0,
            rangeEndFrame = source.frameCount,
            sampleRate = HtdemucsPipelineAdapter.SAMPLE_RATE,
            generationSize = HtdemucsTrackWindowPlanner.STRIDE_SAMPLES,
            trim = 0,
            chunkSize = HtdemucsPipelineAdapter.WINDOW_SAMPLES,
            stemIds = stemIds,
            defaultState = SourceSeparationSegmentState.Queued,
        )
        require(freshSegmentPlan.segmentCount == plans.size)
        val segmentPlan = resumeState?.segmentPlan?.also { resumed ->
            require(resumed.copy(segments = freshSegmentPlan.segments) == freshSegmentPlan) {
                "HTDemucs resume geometry does not match the current source and model."
            }
        } ?: freshSegmentPlan
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

        var completedWindows = segmentPlan.segments.count { it.state.isComplete }
        var currentPlan = segmentPlan
        if (completedWindows == plans.size) {
            rebuildWorkFilesFromCommittedSegments(
                stemFiles = stemFiles,
                segmentPlan = currentPlan,
                segmentDirectory = segmentDirectory,
                requireWorkspaceAvailable = requireWorkspaceAvailable,
            )
            onProgress(
                HtdemucsRangeProgress(
                    completedWindows = completedWindows,
                    totalWindows = plans.size,
                    stage = "Completed",
                    scheduler = currentPlan.schedulerProgress(
                        playbackSegmentIndex = playbackSegmentIndex(
                            playbackPositionMsProvider(),
                            currentPlan,
                        ),
                        processingSegmentIndex = plans.lastIndex,
                        readyWindowCount = playbackReadyWindowCountProvider(),
                    ),
                ),
            )
            return HtdemucsRangeResult(
                stemFiles = stemFiles,
                outputFrameCount = source.frameCount,
                outputSampleRate = HtdemucsPipelineAdapter.SAMPLE_RATE,
                windowCount = plans.size,
                elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L,
                segmentPlan = currentPlan,
            )
        }
        val checkpointStore = HtdemucsNormalizationCheckpointStore(
            File(outputDirectory, NORMALIZATION_CHECKPOINT_FILE_NAME),
        )
        val normalization = checkpointStore.read(
            trackSamples = source.frameCount,
            orderedStemIds = session.orderedStemIds,
        ) ?: run {
            onProgress(HtdemucsRangeProgress(
                completedWindows,
                plans.size,
                "Computing global normalization",
            ))
            computeNormalization(
                shouldPause,
                pauseReasonProvider,
                shouldInterrupt,
            ).also { computed ->
                throwIfStopped(shouldPause, pauseReasonProvider, shouldCancel)
                requireWorkspaceAvailable()
                checkpointStore.write(
                    trackSamples = source.frameCount,
                    orderedStemIds = session.orderedStemIds,
                    normalization = computed,
                )
            }
        }
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
        try {
            restoreCommittedSegments(
                writers = writers,
                segmentPlan = currentPlan,
                segmentDirectory = segmentDirectory,
                requireWorkspaceAvailable = requireWorkspaceAvailable,
            )
            val firstPendingSegment = currentPlan.segments.indexOfFirst {
                !it.state.isComplete
            }
            check(firstPendingSegment >= 0)
            val initialPlaybackFrame = playbackFrame(playbackPositionMsProvider())
            val initialPlaybackIndex = initialPlaybackFrame
                ?.let(currentPlan::segmentIndexForFrame)
            val currentDemandUsesProvisionalArtifact = initialPlaybackIndex
                ?.let { playbackIndex ->
                    val segment = currentPlan.segments[playbackIndex]
                    segment.state == SourceSeparationSegmentState.Provisional &&
                        initialPlaybackFrame >= requireNotNull(segment.playableFromFrame)
                }
                ?: false
            val initialPendingSegment = initialPlaybackIndex?.let { playbackIndex ->
                currentPlan.segments.indices.firstOrNull { index ->
                    index >= playbackIndex &&
                        !currentPlan.segments[index].state.isComplete &&
                        !(index == playbackIndex && currentDemandUsesProvisionalArtifact)
                }
            }
                ?: firstPendingSegment
            val canStartAtDemandSegment = initialPlaybackIndex
                ?.takeIf { it > 0 && it == initialPendingSegment }
                ?.takeIf { !currentPlan.segments[it].state.isComplete }
                ?.takeIf { index ->
                    val segment = currentPlan.segments[index]
                    val playableFromFrame = segment.playbackStartFrame +
                        HtdemucsTrackWindowPlanner.OVERLAP_SAMPLES
                    playableFromFrame < segment.playbackEndFrame &&
                        initialPlaybackFrame >= playableFromFrame
                }
            val firstPlanIndex = canStartAtDemandSegment
                ?: (initialPendingSegment - 1).coerceAtLeast(0)
            val provisionalOutputSegmentIndex = firstPlanIndex.takeIf { index ->
                index > 0 && !currentPlan.segments[index].state.isComplete
            }
            val segmentPublicationId = java.util.UUID.randomUUID().toString()

            fun processPass(
                startPlanIndex: Int,
                provisionalOutputSegmentIndex: Int? = null,
            ) {
                val ola = HtdemucsStreamingOverlapAdd(
                    orderedStemIds = session.orderedStemIds,
                    trackSamples = source.frameCount,
                    normalization = normalization,
                    initialPlanIndex = startPlanIndex,
                    initialBufferStart = plans[startPlanIndex].offset,
                )
                for (plan in plans.drop(startPlanIndex)) {
                    if (completedWindows == plans.size) break
                    val windowStartedAtNanos = System.nanoTime()
                    throwIfStopped(shouldPause, pauseReasonProvider, shouldCancel)
                    requireWorkspaceAvailable()
                    if (!currentPlan.segments[plan.index].state.hasPlaybackArtifact) {
                        currentPlan = currentPlan.withSegmentState(
                            plan.index,
                            SourceSeparationSegmentState.Running,
                        )
                        onSegmentStateChanged(
                            plan.index,
                            SourceSeparationSegmentState.Running,
                            null,
                        )
                    }
                    val currentPlaybackFrame = playbackFrame(playbackPositionMsProvider())
                    val playbackIndex = currentPlaybackFrame
                        ?.let(currentPlan::segmentIndexForFrame)
                    val readyWindowCount = playbackReadyWindowCountProvider().coerceAtLeast(1)
                    val selectedPriority = SourceSeparationSegmentScheduler
                        .prioritize(
                            segmentPlan = currentPlan,
                            playbackFrame = playbackIndex
                                ?.let { currentPlan.segments[it].playbackStartFrame }
                                ?: plan.offset,
                            readyWindowCount = readyWindowCount,
                        )
                        .firstOrNull { it.segment.index == plan.index }
                        ?.priority
                    onProgress(
                        HtdemucsRangeProgress(
                            completedWindows = completedWindows,
                            totalWindows = plans.size,
                            stage = "Processing window ${plan.index + 1}/${plans.size}",
                            scheduler = currentPlan.schedulerProgress(
                                playbackSegmentIndex = playbackIndex,
                                playbackFrame = currentPlaybackFrame,
                                processingSegmentIndex = plan.index,
                                readyWindowCount = readyWindowCount,
                                priority = selectedPriority,
                            ),
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
                        val segmentIndex = plan.index - 1
                        if (!currentPlan.segments[segmentIndex].state.isComplete) {
                            val outputState = if (
                                segmentIndex == provisionalOutputSegmentIndex
                            ) {
                                SourceSeparationSegmentState.Provisional
                            } else {
                                SourceSeparationSegmentState.Ready
                            }
                            val playableFromFrame = if (
                                outputState == SourceSeparationSegmentState.Provisional
                            ) {
                                currentPlan.segments[segmentIndex].playbackStartFrame +
                                    HtdemucsTrackWindowPlanner.OVERLAP_SAMPLES
                            } else {
                                null
                            }
                            publishChunk(
                                chunk = chunk,
                                segmentIndex = segmentIndex,
                                writers = writers,
                                segmentPlan = currentPlan,
                                segmentDirectory = segmentDirectory,
                                publicationId = segmentPublicationId,
                                requireWorkspaceAvailable = requireWorkspaceAvailable,
                            )
                            currentPlan = currentPlan.withSegmentState(
                                segmentIndex = segmentIndex,
                                state = outputState,
                                playableFromFrame = playableFromFrame,
                            )
                            onSegmentStateChanged(
                                segmentIndex,
                                outputState,
                                playableFromFrame,
                            )
                            if (outputState.isComplete) completedWindows += 1
                            val latestPlaybackFrame = playbackFrame(
                                playbackPositionMsProvider(),
                            )
                            onProgress(
                                HtdemucsRangeProgress(
                                    completedWindows = completedWindows,
                                    totalWindows = plans.size,
                                    stage = "Processed window ${segmentIndex + 1}/${plans.size}",
                                    completedWindowElapsedMs =
                                        (System.nanoTime() - windowStartedAtNanos) / 1_000_000L,
                                    scheduler = currentPlan.schedulerProgress(
                                        playbackSegmentIndex = latestPlaybackFrame
                                            ?.let(currentPlan::segmentIndexForFrame),
                                        playbackFrame = latestPlaybackFrame,
                                        processingSegmentIndex = segmentIndex,
                                        readyWindowCount = playbackReadyWindowCountProvider(),
                                        priority = selectedPriority,
                                    ),
                                ),
                            )
                        }
                    }
                }
                if (completedWindows < plans.size) {
                    val tail = ola.finish()
                    if (!currentPlan.segments[plans.lastIndex].state.isComplete) {
                        val outputState = if (
                            plans.lastIndex == provisionalOutputSegmentIndex
                        ) {
                            SourceSeparationSegmentState.Provisional
                        } else {
                            SourceSeparationSegmentState.Ready
                        }
                        val playableFromFrame = if (
                            outputState == SourceSeparationSegmentState.Provisional
                        ) {
                            currentPlan.segments[plans.lastIndex].playbackStartFrame +
                                HtdemucsTrackWindowPlanner.OVERLAP_SAMPLES
                        } else {
                            null
                        }
                        publishChunk(
                            chunk = tail,
                            segmentIndex = plans.lastIndex,
                            writers = writers,
                            segmentPlan = currentPlan,
                            segmentDirectory = segmentDirectory,
                            publicationId = segmentPublicationId,
                            requireWorkspaceAvailable = requireWorkspaceAvailable,
                        )
                        currentPlan = currentPlan.withSegmentState(
                            segmentIndex = plans.lastIndex,
                            state = outputState,
                            playableFromFrame = playableFromFrame,
                        )
                        onSegmentStateChanged(
                            plans.lastIndex,
                            outputState,
                            playableFromFrame,
                        )
                        if (outputState.isComplete) completedWindows += 1
                    }
                }
            }

            // A demand outside the leading overlap can use the first pass immediately.
            // Continue that OLA pass to the end before backfilling from the track start.
            processPass(
                startPlanIndex = firstPlanIndex,
                provisionalOutputSegmentIndex = provisionalOutputSegmentIndex,
            )
            if (completedWindows < plans.size) processPass(0)
            require(completedWindows == plans.size)
            onProgress(
                HtdemucsRangeProgress(
                    completedWindows = completedWindows,
                    totalWindows = plans.size,
                    stage = "Completed",
                    scheduler = currentPlan.schedulerProgress(
                        playbackSegmentIndex = playbackSegmentIndex(
                            playbackPositionMsProvider(),
                            currentPlan,
                        ),
                        processingSegmentIndex = plans.lastIndex,
                        readyWindowCount = playbackReadyWindowCountProvider(),
                    ),
                ),
            )
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
        publicationId: String,
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
            publishSourceSeparationSegmentWav(
                file = segmentFile,
                pcm16 = pcm16,
                sampleRate = HtdemucsPipelineAdapter.SAMPLE_RATE,
                channelCount = HtdemucsPipelineAdapter.CHANNEL_COUNT,
                publicationId = publicationId,
                requireWorkspaceAvailable = requireWorkspaceAvailable,
            )
        }
    }

    private fun rebuildWorkFilesFromCommittedSegments(
        stemFiles: List<HtdemucsRangeStemFile>,
        segmentPlan: SourceSeparationSegmentPlan,
        segmentDirectory: File,
        requireWorkspaceAvailable: () -> Unit,
    ) {
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
        try {
            restoreCommittedSegments(
                writers,
                segmentPlan,
                segmentDirectory,
                requireWorkspaceAvailable,
            )
        } finally {
            closeAll(writers)
        }
    }

    private fun restoreCommittedSegments(
        writers: List<WavFileWriter>,
        segmentPlan: SourceSeparationSegmentPlan,
        segmentDirectory: File,
        requireWorkspaceAvailable: () -> Unit,
    ) {
        segmentPlan.segments.filter { it.state.hasPlaybackArtifact }.forEach { segment ->
            segment.stems.forEachIndexed { stemOrder, stemPath ->
                requireWorkspaceAvailable()
                val segmentFile = File(segmentDirectory.parentFile, stemPath.path)
                val info = Pcm16WavFileReader.read(segmentFile)
                require(info.sampleRate == HtdemucsPipelineAdapter.SAMPLE_RATE &&
                    info.channelCount == HtdemucsPipelineAdapter.CHANNEL_COUNT &&
                    info.frameCount == segment.playbackFrameCount.toLong() &&
                    info.dataSize <= Int.MAX_VALUE
                ) { "Committed HTDemucs segment geometry is invalid." }
                val pcm16 = ByteArray(info.dataSize.toInt())
                RandomAccessFile(segmentFile, "r").use { input ->
                    input.seek(info.dataOffset)
                    input.readFully(pcm16)
                }
                writers[stemOrder].writePcm16AtFrame(segment.playbackStartFrame, pcm16)
            }
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

    private fun playbackSegmentIndex(
        positionMs: Long?,
        plan: SourceSeparationSegmentPlan,
    ): Int? = playbackFrame(positionMs)?.let(plan::segmentIndexForFrame)

    private fun playbackFrame(positionMs: Long?): Int? {
        val position = positionMs?.takeIf { it >= 0L } ?: return null
        return (position.toDouble() * HtdemucsPipelineAdapter.SAMPLE_RATE / 1_000.0)
            .toLong()
            .coerceIn(0L, (source.frameCount - 1).toLong())
            .toInt()
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
        const val NORMALIZATION_CHECKPOINT_FILE_NAME = "htdemucs-normalization-v1.bin"
        const val DEFAULT_READY_WINDOW_COUNT = 2
    }
}

private class HtdemucsNormalizationCheckpointStore(
    private val file: File,
) {
    fun read(
        trackSamples: Int,
        orderedStemIds: List<String>,
    ): HtdemucsGlobalNormalization? = runCatching {
        if (!file.isFile) return null
        DataInputStream(FileInputStream(file).buffered()).use { input ->
            require(input.readInt() == MAGIC)
            require(input.readInt() == VERSION)
            require(input.readInt() == trackSamples)
            require(input.readInt() == HtdemucsPipelineAdapter.SAMPLE_RATE)
            require(input.readInt() == HtdemucsPipelineAdapter.WINDOW_SAMPLES)
            require(input.readInt() == HtdemucsTrackWindowPlanner.STRIDE_SAMPLES)
            val stemCount = input.readInt()
            require(stemCount == orderedStemIds.size)
            repeat(stemCount) { index -> require(input.readUTF() == orderedStemIds[index]) }
            val normalization = HtdemucsGlobalNormalization(
                mean = input.readFloat(),
                sampleStandardDeviation = input.readFloat(),
                divisor = input.readFloat(),
            )
            require(normalization.mean.isFinite() &&
                normalization.sampleStandardDeviation.isFinite() &&
                normalization.sampleStandardDeviation >= 0f &&
                normalization.divisor.isFinite() &&
                normalization.divisor > 0f &&
                input.read() == -1
            )
            normalization
        }
    }.getOrElse {
        file.delete()
        null
    }

    fun write(
        trackSamples: Int,
        orderedStemIds: List<String>,
        normalization: HtdemucsGlobalNormalization,
    ) {
        file.parentFile?.mkdirs()
        val temporary = File.createTempFile("${file.name}.", ".tmp", file.parentFile)
        try {
            FileOutputStream(temporary).use { output ->
                val data = DataOutputStream(output.buffered())
                data.writeInt(MAGIC)
                data.writeInt(VERSION)
                data.writeInt(trackSamples)
                data.writeInt(HtdemucsPipelineAdapter.SAMPLE_RATE)
                data.writeInt(HtdemucsPipelineAdapter.WINDOW_SAMPLES)
                data.writeInt(HtdemucsTrackWindowPlanner.STRIDE_SAMPLES)
                data.writeInt(orderedStemIds.size)
                orderedStemIds.forEach(data::writeUTF)
                data.writeFloat(normalization.mean)
                data.writeFloat(normalization.sampleStandardDeviation)
                data.writeFloat(normalization.divisor)
                data.flush()
                output.fd.sync()
            }
            runCatching {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            }.getOrElse {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            temporary.delete()
        }
    }

    private companion object {
        const val MAGIC = 0x4854444E
        const val VERSION = 1
    }
}
