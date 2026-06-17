package com.mardous.booming.separation.audio

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.mardous.booming.separation.model.MdxDspConfig
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

class AudioWindowDecodeExperiment(
    private val context: Context,
    private val config: MdxDspConfig = MdxDspConfig(),
) {
    fun run(
        uri: Uri,
        displayName: String,
        playbackPositionMs: Long,
        reportDir: File,
        onProgress: (AudioWindowDecodeExperimentProgress) -> Unit = {},
        shouldCancel: () -> Boolean = { false },
    ): AudioWindowDecodeExperimentResult {
        reportDir.mkdirs()
        var completedSteps = 0
        var totalSteps = REFERENCE_STEP_COUNT
        var probeCount = 0

        fun publish(
            stage: String,
            probeIndex: Int? = null,
        ) {
            onProgress(
                AudioWindowDecodeExperimentProgress(
                    completedSteps = completedSteps,
                    totalSteps = totalSteps,
                    stage = stage,
                    probeIndex = probeIndex,
                    probeCount = probeCount.takeIf { it > 0 },
                )
            )
        }

        publish("Decoding full reference audio")
        val referenceTimed = timed {
            AudioPcmDecoder(context).decode(uri, shouldCancel = shouldCancel)
        }
        completedSteps += 1
        publish("Resampling full reference audio")
        val referenceResampledTimed = timed {
            referenceTimed.value.resampleTo(config.sampleRate, shouldCancel = shouldCancel)
        }
        completedSteps += 1

        val targetPlaybackPositionMs = playbackPositionMs.coerceAtLeast(0L)
        val probePlans = buildProbePlans(
            requestedPositionMs = targetPlaybackPositionMs,
            totalFrames = referenceTimed.value.frameCount,
            sourceSampleRate = referenceTimed.value.sampleRate,
        )
        probeCount = probePlans.size
        totalSteps = REFERENCE_STEP_COUNT + probeCount * PROBE_STEP_COUNT + REPORT_STEP_COUNT

        val probeResultsByIndex = mutableMapOf<Int, AudioWindowDecodeProbeResult>()
        val calibrationPlans = probePlans.filter { it.isCalibration }
        val holdoutPlans = probePlans.filterNot { it.isCalibration }

        if (calibrationPlans.isNotEmpty()) {
            calibrationPlans.forEachIndexed { calibrationIndex, plan ->
                val result = runProbe(
                    uri = uri,
                    referenceFull = referenceTimed.value,
                    referenceResampled = referenceResampledTimed.value,
                    requestedLabel = plan.label,
                    playbackPositionMs = plan.playbackPositionMs,
                    isCalibrationProbe = plan.isCalibration,
                    mp3CalibrationCorrectionFrames = null,
                    onStage = { stage ->
                        publish(
                            "Calibration probe ${calibrationIndex + 1}/${calibrationPlans.size}: $stage",
                            plan.index + 1,
                        )
                    },
                    onStepComplete = {
                        completedSteps += 1
                    },
                    shouldCancel = shouldCancel,
                )
                probeResultsByIndex[plan.index] = result
            }
        }

        val mp3CalibrationCorrectionFrames = deriveMp3CalibrationCorrection(
            calibrationPlans.mapNotNull { probeResultsByIndex[it.index] },
        )

        if (holdoutPlans.isNotEmpty()) {
            holdoutPlans.forEachIndexed { holdoutIndex, plan ->
                val result = runProbe(
                    uri = uri,
                    referenceFull = referenceTimed.value,
                    referenceResampled = referenceResampledTimed.value,
                    requestedLabel = plan.label,
                    playbackPositionMs = plan.playbackPositionMs,
                    isCalibrationProbe = plan.isCalibration,
                    mp3CalibrationCorrectionFrames = mp3CalibrationCorrectionFrames,
                    onStage = { stage ->
                        publish(
                            "Holdout probe ${holdoutIndex + 1}/${holdoutPlans.size}: $stage",
                            plan.index + 1,
                        )
                    },
                    onStepComplete = {
                        completedSteps += 1
                    },
                    shouldCancel = shouldCancel,
                )
                probeResultsByIndex[plan.index] = result
            }
        }

        val probes = probeResultsByIndex.toSortedMap().values.toList()

        val safeName = displayName.substringBeforeLast('.')
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .ifBlank { "audio" }
        val reportFile = uniqueFile(reportDir, "${safeName}_window_decode_experiment.txt")
        publish("Writing report")
        val result = AudioWindowDecodeExperimentResult(
            reportFile = reportFile,
            displayName = displayName,
            requestedPlaybackPositionMs = targetPlaybackPositionMs,
            sourceSampleRate = referenceTimed.value.sampleRate,
            referenceFrameCount = referenceTimed.value.frameCount,
            referenceChannelCount = referenceTimed.value.channelCount,
            fullDecodeMs = referenceTimed.elapsedMs,
            fullResampleMs = referenceResampledTimed.elapsedMs,
            probes = probes,
        )
        reportFile.writeText(result.toReportText(), Charsets.UTF_8)
        completedSteps += 1
        publish("Finished")
        return result
    }

    private fun runProbe(
        uri: Uri,
        referenceFull: DecodedPcmAudio,
        referenceResampled: DecodedPcmAudio,
        requestedLabel: String,
        playbackPositionMs: Long,
        isCalibrationProbe: Boolean,
        mp3CalibrationCorrectionFrames: Int?,
        onStage: (String) -> Unit,
        onStepComplete: () -> Unit,
        shouldCancel: () -> Boolean,
    ): AudioWindowDecodeProbeResult {
        fun <T> timedProbeStage(
            stage: String,
            block: () -> T,
        ): TimedValue<T> {
            onStage(stage)
            val result = timed(block)
            onStepComplete()
            return result
        }

        fun completeProbeStage(stage: String) {
            onStage(stage)
            onStepComplete()
        }

        fun <T> probeStage(
            stage: String,
            block: () -> T,
        ): T {
            onStage(stage)
            throwIfCanceled(shouldCancel)
            val result = block()
            throwIfCanceled(shouldCancel)
            onStepComplete()
            return result
        }

        val referenceTargetFrame = ((playbackPositionMs.coerceAtLeast(0L) * referenceFull.sampleRate) / MILLIS_PER_SECOND)
            .coerceIn(0L, referenceFull.frameCount.toLong())
            .toInt()
        val modelTargetFrame = sourceFrameToTargetFrame(referenceTargetFrame, referenceFull.sampleRate)
        val segmentIndex = (modelTargetFrame / config.generationSize).coerceAtLeast(0)
        val playbackStartFrame = segmentIndex * config.generationSize
        val windowStartFrame = playbackStartFrame - config.trim
        val windowEndFrame = windowStartFrame + config.chunkSize
        val referenceWindowStartFrame = targetFrameToSourceFrameFloor(
            targetFrame = windowStartFrame.coerceAtLeast(0),
            sourceSampleRate = referenceFull.sampleRate,
        )
        val referenceWindowEndFrame = targetFrameToSourceFrameCeil(
            targetFrame = windowEndFrame.coerceAtLeast(0),
            sourceSampleRate = referenceFull.sampleRate,
        ).coerceAtLeast(referenceWindowStartFrame + 1)
        val requestedStartUs = frameToUs(referenceWindowStartFrame, referenceFull.sampleRate)
        val requestedEndUs = frameToUs(referenceWindowEndFrame, referenceFull.sampleRate)
        val prerollUs = config.trimFramesToUs()

        val localWindow = timedProbeStage("Local window decode") {
            AudioPcmDecoder(context).decodeWindow(
                uri = uri,
                startUs = requestedStartUs,
                endUs = requestedEndUs,
                shouldCancel = shouldCancel,
            )
        }
        val prerollWindow = timedProbeStage("Preroll window decode") {
            AudioPcmDecoder(context).decodeWindowWithPrerollCursor(
                uri = uri,
                startUs = requestedStartUs,
                endUs = requestedEndUs,
                prerollUs = prerollUs,
                shouldCancel = shouldCancel,
            )
        }

        val sourceRateExpected = referenceFull.slicePcm16(
            startFrame = referenceWindowStartFrame,
            frames = referenceWindowEndFrame - referenceWindowStartFrame,
            outputChannelCount = MdxDspConfig.STEREO_CHANNELS,
        )
        val sourceRateLocal = localWindow.value.audio.toStereoPcm16(
            startFrame = 0,
            maxFrames = referenceWindowEndFrame - referenceWindowStartFrame,
        )
        val sourceRatePreroll = prerollWindow.value.audio.toStereoPcm16(
            startFrame = 0,
            maxFrames = referenceWindowEndFrame - referenceWindowStartFrame,
        )
        val stableSourceStartFrame = (
                targetFrameToSourceFrameFloor(playbackStartFrame, referenceFull.sampleRate) -
                        referenceWindowStartFrame
                ).coerceAtLeast(0)
        val stableSourceEndFrame = (
                targetFrameToSourceFrameCeil(playbackStartFrame + config.generationSize, referenceFull.sampleRate) -
                        referenceWindowStartFrame
                ).coerceAtLeast(stableSourceStartFrame)
        val stableSourceFrameCount = stableSourceEndFrame - stableSourceStartFrame
        val stableTargetStartFrame = config.trim
        val stableTargetFrameCount = config.generationSize
        val timestampPlacementOffsetSourceFrames = localWindow.value.firstOutputTimeUs
            ?.let { usDeltaToFrame(it - requestedStartUs, referenceFull.sampleRate) }
        val frameDeficitPlacementOffsetSourceFrames = (
                referenceWindowEndFrame - referenceWindowStartFrame - localWindow.value.audio.frameCount
                ).coerceAtLeast(0)
        val metadataDelayPlacementOffsetSourceFrames = localWindow.value.trackMetadata
            ?.encoderDelayFrames
            ?.takeIf { referenceWindowStartFrame == 0 }
        val sourceRateTimestampPlaced = localWindow.value.audio.toStereoPcm16Placed(
            maxFrames = referenceWindowEndFrame - referenceWindowStartFrame,
            destinationStartFrame = timestampPlacementOffsetSourceFrames ?: 0,
        )
        val sourceRateFrameDeficitPlaced = localWindow.value.audio.toStereoPcm16Placed(
            maxFrames = referenceWindowEndFrame - referenceWindowStartFrame,
            destinationStartFrame = frameDeficitPlacementOffsetSourceFrames,
        )
        val mp3CalibratedPlacementOffsetSourceFrames = if (
            localWindow.value.trackMetadata?.mimeType == MP3_MIME_TYPE &&
            mp3CalibrationCorrectionFrames != null
        ) {
            frameDeficitPlacementOffsetSourceFrames + mp3CalibrationCorrectionFrames
        } else {
            null
        }
        val sourceRateMp3CalibratedPlaced = mp3CalibratedPlacementOffsetSourceFrames?.let { offsetFrames ->
            localWindow.value.audio.toStereoPcm16Placed(
                maxFrames = referenceWindowEndFrame - referenceWindowStartFrame,
                destinationStartFrame = offsetFrames,
            )
        }
        val sourceRateMetadataDelayPlaced = metadataDelayPlacementOffsetSourceFrames?.let { offsetFrames ->
            localWindow.value.audio.toStereoPcm16Placed(
                maxFrames = referenceWindowEndFrame - referenceWindowStartFrame,
                destinationStartFrame = offsetFrames,
            )
        }

        val currentResampledTimed = timedProbeStage("Current local resample") {
            localWindow.value.audio.resampleTo(config.sampleRate, shouldCancel = shouldCancel)
        }
        val currentExpected = referenceResampled.slicePcm16(
                startFrame = windowStartFrame,
                frames = config.chunkSize,
                outputChannelCount = MdxDspConfig.STEREO_CHANNELS,
            )
        val currentResampledAligned = currentResampledTimed.value.slicePcm16IntoWindow(
            frames = config.chunkSize,
            outputChannelCount = MdxDspConfig.STEREO_CHANNELS,
            destinationStartFrame = maxOf(0, -windowStartFrame),
        )
        val songTimelineResampledTimed = timedProbeStage("Song-timeline resample") {
            localWindow.value.audio.resampleToTargetWindowOnSongTimeline(
                targetSampleRate = config.sampleRate,
                targetWindowStartFrame = windowStartFrame,
                sourceWindowStartFrame = referenceWindowStartFrame,
                shouldCancel = shouldCancel,
            )
        }
        val songTimelineTimestampResampledTimed = timedProbeStage("Song-timeline timestamp resample") {
            localWindow.value.audio.resampleToTargetWindowOnSongTimeline(
                targetSampleRate = config.sampleRate,
                targetWindowStartFrame = windowStartFrame,
                sourceWindowStartFrame = referenceWindowStartFrame +
                        (timestampPlacementOffsetSourceFrames ?: 0),
                shouldCancel = shouldCancel,
            )
        }
        val songTimelineFrameDeficitResampledTimed = timedProbeStage("Song-timeline frame-deficit resample") {
            localWindow.value.audio.resampleToTargetWindowOnSongTimeline(
                targetSampleRate = config.sampleRate,
                targetWindowStartFrame = windowStartFrame,
                sourceWindowStartFrame = referenceWindowStartFrame +
                        frameDeficitPlacementOffsetSourceFrames,
                shouldCancel = shouldCancel,
            )
        }
        val songTimelineMp3CalibratedResampledTimed = mp3CalibratedPlacementOffsetSourceFrames?.let { offsetFrames ->
            timedProbeStage("Song-timeline MP3 calibrated resample") {
                localWindow.value.audio.resampleToTargetWindowOnSongTimeline(
                    targetSampleRate = config.sampleRate,
                    targetWindowStartFrame = windowStartFrame,
                    sourceWindowStartFrame = referenceWindowStartFrame + offsetFrames,
                    shouldCancel = shouldCancel,
                )
            }
        } ?: run {
            completeProbeStage("Song-timeline MP3 calibrated resample unavailable")
            null
        }
        val songTimelineMetadataDelayResampledTimed = metadataDelayPlacementOffsetSourceFrames?.let { offsetFrames ->
            timedProbeStage("Song-timeline metadata-delay resample") {
                localWindow.value.audio.resampleToTargetWindowOnSongTimeline(
                    targetSampleRate = config.sampleRate,
                    targetWindowStartFrame = windowStartFrame,
                    sourceWindowStartFrame = referenceWindowStartFrame + offsetFrames,
                    shouldCancel = shouldCancel,
                )
            }
        } ?: run {
            completeProbeStage("Song-timeline metadata-delay resample unavailable")
            null
        }

        val prerollResampledTimed = timedProbeStage("Preroll local resample") {
            prerollWindow.value.audio.resampleTo(config.sampleRate, shouldCancel = shouldCancel)
        }
        val prerollResampledAligned = prerollResampledTimed.value.slicePcm16IntoWindow(
            frames = config.chunkSize,
            outputChannelCount = MdxDspConfig.STEREO_CHANNELS,
            destinationStartFrame = maxOf(0, -windowStartFrame),
        )
        val prerollSongTimelineResampledTimed = timedProbeStage("Preroll song-timeline resample") {
            prerollWindow.value.audio.resampleToTargetWindowOnSongTimeline(
                targetSampleRate = config.sampleRate,
                targetWindowStartFrame = windowStartFrame,
                sourceWindowStartFrame = referenceWindowStartFrame,
                shouldCancel = shouldCancel,
            )
        }

        val sourceRateComparisons = probeStage("Comparing source-rate candidates") {
            SourceRateCandidateComparisons(
                sourceRate = compareCandidate(
                    reference = sourceRateExpected,
                    candidate = sourceRateLocal,
                    stableStartFrame = stableSourceStartFrame,
                    stableFrames = stableSourceFrameCount,
                ),
                sourceRatePreroll = compareCandidate(
                    reference = sourceRateExpected,
                    candidate = sourceRatePreroll,
                    stableStartFrame = stableSourceStartFrame,
                    stableFrames = stableSourceFrameCount,
                ),
                sourceRateTimestampPlacement = timestampPlacementOffsetSourceFrames?.let {
                    compareCandidate(
                        reference = sourceRateExpected,
                        candidate = sourceRateTimestampPlaced,
                        stableStartFrame = stableSourceStartFrame,
                        stableFrames = stableSourceFrameCount,
                    )
                },
                sourceRateFrameDeficitPlacement = compareCandidate(
                    reference = sourceRateExpected,
                    candidate = sourceRateFrameDeficitPlaced,
                    stableStartFrame = stableSourceStartFrame,
                    stableFrames = stableSourceFrameCount,
                ),
                sourceRateMp3CalibratedPlacement = sourceRateMp3CalibratedPlaced?.let {
                    compareCandidate(
                        reference = sourceRateExpected,
                        candidate = it,
                        stableStartFrame = stableSourceStartFrame,
                        stableFrames = stableSourceFrameCount,
                    )
                },
                sourceRateMetadataDelayPlacement = sourceRateMetadataDelayPlaced?.let {
                    compareCandidate(
                        reference = sourceRateExpected,
                        candidate = it,
                        stableStartFrame = stableSourceStartFrame,
                        stableFrames = stableSourceFrameCount,
                    )
                },
            )
        }

        val currentResampleComparison = probeStage("Comparing current-resample candidate") {
            compareCandidate(
                reference = currentExpected,
                candidate = currentResampledAligned,
                stableStartFrame = stableTargetStartFrame,
                stableFrames = stableTargetFrameCount,
            )
        }

        val songTimelineComparisons = probeStage("Comparing song-timeline candidates") {
            SongTimelineCandidateComparisons(
                songTimeline = compareCandidate(
                    reference = currentExpected,
                    candidate = songTimelineResampledTimed.value,
                    stableStartFrame = stableTargetStartFrame,
                    stableFrames = stableTargetFrameCount,
                ),
                songTimelineTimestampPlacement = timestampPlacementOffsetSourceFrames?.let {
                    compareCandidate(
                        reference = currentExpected,
                        candidate = songTimelineTimestampResampledTimed.value,
                        stableStartFrame = stableTargetStartFrame,
                        stableFrames = stableTargetFrameCount,
                    )
                },
                songTimelineFrameDeficitPlacement = compareCandidate(
                    reference = currentExpected,
                    candidate = songTimelineFrameDeficitResampledTimed.value,
                    stableStartFrame = stableTargetStartFrame,
                    stableFrames = stableTargetFrameCount,
                ),
                songTimelineMp3CalibratedPlacement = songTimelineMp3CalibratedResampledTimed?.value?.let {
                    compareCandidate(
                        reference = currentExpected,
                        candidate = it,
                        stableStartFrame = stableTargetStartFrame,
                        stableFrames = stableTargetFrameCount,
                    )
                },
                songTimelineMetadataDelayPlacement = songTimelineMetadataDelayResampledTimed?.value?.let {
                    compareCandidate(
                        reference = currentExpected,
                        candidate = it,
                        stableStartFrame = stableTargetStartFrame,
                        stableFrames = stableTargetFrameCount,
                    )
                },
            )
        }

        val prerollComparisons = probeStage("Comparing preroll candidates") {
            PrerollCandidateComparisons(
                prerollResample = compareCandidate(
                    reference = currentExpected,
                    candidate = prerollResampledAligned,
                    stableStartFrame = stableTargetStartFrame,
                    stableFrames = stableTargetFrameCount,
                ),
                prerollSongTimeline = compareCandidate(
                    reference = currentExpected,
                    candidate = prerollSongTimelineResampledTimed.value,
                    stableStartFrame = stableTargetStartFrame,
                    stableFrames = stableTargetFrameCount,
                ),
            )
        }

        return AudioWindowDecodeProbeResult(
            label = requestedLabel,
            isCalibrationProbe = isCalibrationProbe,
            playbackPositionMs = playbackPositionMs,
            segmentIndex = segmentIndex,
            targetFrame = referenceTargetFrame,
            targetSampleRateFrame = modelTargetFrame,
            requestedStartUs = requestedStartUs,
            requestedEndUs = requestedEndUs,
            requestedWindowStartFrame = windowStartFrame,
            requestedWindowEndFrame = windowEndFrame,
            requestedSourceWindowStartFrame = referenceWindowStartFrame,
            requestedSourceWindowEndFrame = referenceWindowEndFrame,
            stableSourceStartFrame = stableSourceStartFrame,
            stableSourceFrameCount = stableSourceFrameCount,
            stableTargetStartFrame = stableTargetStartFrame,
            stableTargetFrameCount = stableTargetFrameCount,
            localWindowDecodeMs = localWindow.elapsedMs,
            localWindowResampleMs = currentResampledTimed.elapsedMs,
            prerollWindowDecodeMs = prerollWindow.elapsedMs,
            prerollWindowResampleMs = prerollResampledTimed.elapsedMs,
            songTimelineResampleMs = songTimelineResampledTimed.elapsedMs,
            songTimelineTimestampResampleMs = songTimelineTimestampResampledTimed.elapsedMs,
            songTimelineFrameDeficitResampleMs = songTimelineFrameDeficitResampledTimed.elapsedMs,
            songTimelineMp3CalibratedResampleMs = songTimelineMp3CalibratedResampledTimed?.elapsedMs,
            songTimelineMetadataDelayResampleMs = songTimelineMetadataDelayResampledTimed?.elapsedMs,
            prerollSongTimelineResampleMs = prerollSongTimelineResampledTimed.elapsedMs,
            extractorStartUs = localWindow.value.extractorStartUs,
            firstOutputTimeUs = localWindow.value.firstOutputTimeUs,
            lastOutputTimeUs = localWindow.value.lastOutputTimeUs,
            prerollCursorAnchorTimeUs = prerollWindow.value.cursorAnchorTimeUs,
            outputBufferCount = localWindow.value.outputBufferCount,
            windowFrameCount = localWindow.value.audio.frameCount,
            trackMetadata = localWindow.value.trackMetadata,
            timestampPlacementOffsetSourceFrames = timestampPlacementOffsetSourceFrames,
            frameDeficitPlacementOffsetSourceFrames = frameDeficitPlacementOffsetSourceFrames,
            mp3CalibrationCorrectionFrames = mp3CalibrationCorrectionFrames,
            mp3CalibratedPlacementOffsetSourceFrames = mp3CalibratedPlacementOffsetSourceFrames,
            metadataDelayPlacementOffsetSourceFrames = metadataDelayPlacementOffsetSourceFrames,
            sourceRateComparison = sourceRateComparisons.sourceRate,
            sourceRatePrerollComparison = sourceRateComparisons.sourceRatePreroll,
            sourceRateTimestampPlacementComparison = sourceRateComparisons.sourceRateTimestampPlacement,
            sourceRateFrameDeficitPlacementComparison = sourceRateComparisons.sourceRateFrameDeficitPlacement,
            sourceRateMp3CalibratedPlacementComparison = sourceRateComparisons.sourceRateMp3CalibratedPlacement,
            sourceRateMetadataDelayPlacementComparison = sourceRateComparisons.sourceRateMetadataDelayPlacement,
            currentResampledComparison = currentResampleComparison,
            songTimelineResampledComparison = songTimelineComparisons.songTimeline,
            songTimelineTimestampPlacementComparison = songTimelineComparisons.songTimelineTimestampPlacement,
            songTimelineFrameDeficitPlacementComparison = songTimelineComparisons.songTimelineFrameDeficitPlacement,
            songTimelineMp3CalibratedPlacementComparison = songTimelineComparisons.songTimelineMp3CalibratedPlacement,
            songTimelineMetadataDelayPlacementComparison = songTimelineComparisons.songTimelineMetadataDelayPlacement,
            prerollResampledComparison = prerollComparisons.prerollResample,
            prerollSongTimelineResampledComparison = prerollComparisons.prerollSongTimeline,
        )
    }

    private fun compareCandidate(
        reference: ShortArray,
        candidate: ShortArray,
        stableStartFrame: Int? = null,
        stableFrames: Int? = null,
    ): PcmWindowAlignmentComparison {
        val stableComparison = if (stableStartFrame != null && stableFrames != null && stableFrames > 0) {
            val stableReference = reference.sliceInterleavedFrames(
                startFrame = stableStartFrame,
                frames = stableFrames,
                channelCount = MdxDspConfig.STEREO_CHANNELS,
            )
            val stableCandidate = candidate.sliceInterleavedFrames(
                startFrame = stableStartFrame,
                frames = stableFrames,
                channelCount = MdxDspConfig.STEREO_CHANNELS,
            )
            comparePcm16(stableReference, stableCandidate) to bestOffsetComparison(
                reference = stableReference,
                candidate = stableCandidate,
                maxOffsetFrames = MAX_OFFSET_SEARCH_FRAMES,
                channelCount = MdxDspConfig.STEREO_CHANNELS,
            )
        } else {
            null
        }
        return PcmWindowAlignmentComparison(
            direct = comparePcm16(reference, candidate),
            bestOffset = bestOffsetComparison(
                reference = reference,
                candidate = candidate,
                maxOffsetFrames = MAX_OFFSET_SEARCH_FRAMES,
                channelCount = MdxDspConfig.STEREO_CHANNELS,
            ),
            stableDirect = stableComparison?.first,
            stableBestOffset = stableComparison?.second,
        )
    }

    private fun buildProbePositionsMs(
        requestedPositionMs: Long,
        totalFrames: Int,
        sourceSampleRate: Int,
    ): List<Long> {
        val durationMs = frameToMs(totalFrames, sourceSampleRate)
        val nearEndMs = (durationMs - PROBE_WINDOW_MARGIN_MS).coerceAtLeast(0L)
        val firstSegmentBoundaryMs = frameToMs(config.generationSize, config.sampleRate)
        val secondSegmentBoundaryMs = frameToMs(config.generationSize * 2, config.sampleRate)
        return listOf(
            requestedPositionMs,
            0L,
            1_000L,
            5_000L,
            firstSegmentBoundaryMs - 1L,
            firstSegmentBoundaryMs,
            firstSegmentBoundaryMs + 1L,
            secondSegmentBoundaryMs - 1L,
            secondSegmentBoundaryMs,
            secondSegmentBoundaryMs + 1L,
            30_000L,
            60_000L,
            durationMs / 2L,
            nearEndMs,
        )
            .map { it.coerceIn(0L, nearEndMs) }
            .distinct()
    }

    private fun buildProbePlans(
        requestedPositionMs: Long,
        totalFrames: Int,
        sourceSampleRate: Int,
    ): List<AudioWindowDecodeProbePlan> {
        return buildProbePositionsMs(
            requestedPositionMs = requestedPositionMs,
            totalFrames = totalFrames,
            sourceSampleRate = sourceSampleRate,
        ).mapIndexed { index, playbackPositionMs ->
            val isCalibration = when (index) {
                0, 1, 2, 3, 4, 5 -> true
                else -> false
            }
            AudioWindowDecodeProbePlan(
                index = index,
                playbackPositionMs = playbackPositionMs,
                label = if (playbackPositionMs == requestedPositionMs) {
                    "Current playback"
                } else {
                    "Probe ${index + 1}"
                },
                isCalibration = isCalibration,
            )
        }
    }

    private fun deriveMp3CalibrationCorrection(probes: List<AudioWindowDecodeProbeResult>): Int? {
        val mp3Probes = probes.filter { it.trackMetadata?.mimeType == MP3_MIME_TYPE }
        if (mp3Probes.isEmpty()) return null
        val calibrationRows = mp3Probes.filter { it.isCalibrationProbe }
        val rows = if (calibrationRows.isNotEmpty()) calibrationRows else mp3Probes
        val residuals = rows.mapNotNull {
            it.sourceRateFrameDeficitPlacementComparison.stableBestOffset?.offsetFrames
        }
        if (residuals.isEmpty()) return null
        return medianRounded(residuals)
    }

    private fun medianRounded(values: List<Int>): Int {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            ((sorted[middle - 1] + sorted[middle]).toDouble() / 2.0).roundToInt()
        }
    }

    private fun DecodedPcmAudio.slicePcm16(
        startFrame: Int,
        frames: Int,
        outputChannelCount: Int,
    ): ShortArray {
        val output = ShortArray(frames * outputChannelCount)
        val copyStartFrame = startFrame.coerceIn(0, frameCount)
        val copyEndFrame = (startFrame + frames).coerceIn(copyStartFrame, frameCount)
        var outputOffset = (copyStartFrame - startFrame) * outputChannelCount
        for (frame in copyStartFrame until copyEndFrame) {
            val sourceFrameOffset = frame * channelCount
            for (channel in 0 until outputChannelCount) {
                val sourceChannel = channel.coerceAtMost(channelCount - 1)
                output[outputOffset++] = readLittleEndianShort(
                    byteIndex = (sourceFrameOffset + sourceChannel) * Short.SIZE_BYTES,
                )
            }
        }
        return output
    }

    private fun comparePcm16(
        reference: ShortArray,
        candidate: ShortArray,
    ): PcmWindowComparison {
        return comparePcm16(
            reference = reference,
            candidate = candidate,
            referenceStart = 0,
            candidateStart = 0,
            count = minOf(reference.size, candidate.size),
            offsetFrames = 0,
        )
    }

    private fun comparePcm16(
        reference: ShortArray,
        candidate: ShortArray,
        referenceStart: Int,
        candidateStart: Int,
        count: Int,
        offsetFrames: Int,
    ): PcmWindowComparison {
        if (count == 0) {
            return PcmWindowComparison(0, 0, 0, 0.0, 0)
        }
        var equal = 0
        var absoluteErrorTotal = 0L
        var maxAbsoluteError = 0
        for (index in 0 until count) {
            val error = abs(reference[referenceStart + index].toInt() - candidate[candidateStart + index].toInt())
            if (error == 0) equal += 1
            absoluteErrorTotal += error.toLong()
            if (error > maxAbsoluteError) maxAbsoluteError = error
        }
        val comparison = PcmWindowComparison(
            offsetFrames = offsetFrames,
            comparedSamples = count,
            equalSamples = equal,
            meanAbsoluteError = absoluteErrorTotal.toDouble() / count.toDouble(),
            maxAbsoluteError = maxAbsoluteError,
        )
        return comparison
    }

    private fun bestOffsetComparison(
        reference: ShortArray,
        candidate: ShortArray,
        maxOffsetFrames: Int,
        channelCount: Int,
    ): PcmWindowComparison {
        val sampledSearch = sampledBestOffsetSearch(
            reference = reference,
            candidate = candidate,
            maxOffsetFrames = maxOffsetFrames,
            channelCount = channelCount,
        )
        val candidateOffsets = buildSet {
            add(0)
            add(sampledSearch)
            for (offset in sampledSearch - BEST_OFFSET_REFINE_RADIUS_FRAMES
                    ..sampledSearch + BEST_OFFSET_REFINE_RADIUS_FRAMES) {
                add(offset.coerceIn(-maxOffsetFrames, maxOffsetFrames))
            }
        }
        return candidateOffsets
            .mapNotNull { offsetFrames ->
                comparePcm16AtOffset(
                    reference = reference,
                    candidate = candidate,
                    offsetFrames = offsetFrames,
                    channelCount = channelCount,
                )
            }
            .minByOrNull { it.meanAbsoluteError }
            ?: comparePcm16(reference, candidate)
    }

    private fun sampledBestOffsetSearch(
        reference: ShortArray,
        candidate: ShortArray,
        maxOffsetFrames: Int,
        channelCount: Int,
    ): Int {
        var bestOffsetFrames = 0
        var bestError = sampledMeanAbsoluteErrorAtOffset(
            reference = reference,
            candidate = candidate,
            offsetFrames = 0,
            channelCount = channelCount,
        )
        for (offsetFrames in -maxOffsetFrames..maxOffsetFrames step BEST_OFFSET_COARSE_STEP_FRAMES) {
            val error = sampledMeanAbsoluteErrorAtOffset(
                reference = reference,
                candidate = candidate,
                offsetFrames = offsetFrames,
                channelCount = channelCount,
            )
            if (error < bestError) {
                bestError = error
                bestOffsetFrames = offsetFrames
            }
        }
        return bestOffsetFrames
    }

    private fun sampledMeanAbsoluteErrorAtOffset(
        reference: ShortArray,
        candidate: ShortArray,
        offsetFrames: Int,
        channelCount: Int,
    ): Double {
        val offsetSamples = offsetFrames * channelCount
        val referenceStart = maxOf(0, offsetSamples)
        val candidateStart = maxOf(0, -offsetSamples)
        val count = minOf(reference.size - referenceStart, candidate.size - candidateStart)
        if (count <= 0) return Double.POSITIVE_INFINITY

        val sampleStride = (BEST_OFFSET_SAMPLE_STRIDE_FRAMES * channelCount).coerceAtLeast(channelCount)
        var index = 0
        var sampled = 0
        var absoluteErrorTotal = 0L
        while (index < count) {
            for (channel in 0 until channelCount) {
                val sampleIndex = index + channel
                if (sampleIndex >= count) break
                val error = abs(
                    reference[referenceStart + sampleIndex].toInt() -
                            candidate[candidateStart + sampleIndex].toInt()
                )
                absoluteErrorTotal += error.toLong()
                sampled += 1
            }
            index += sampleStride
        }
        return if (sampled > 0) {
            absoluteErrorTotal.toDouble() / sampled.toDouble()
        } else {
            Double.POSITIVE_INFINITY
        }
    }

    private fun comparePcm16AtOffset(
        reference: ShortArray,
        candidate: ShortArray,
        offsetFrames: Int,
        channelCount: Int,
    ): PcmWindowComparison? {
        val offsetSamples = offsetFrames * channelCount
        val referenceStart = maxOf(0, offsetSamples)
        val candidateStart = maxOf(0, -offsetSamples)
        val count = minOf(reference.size - referenceStart, candidate.size - candidateStart)
        if (count <= 0) return null
        return comparePcm16(
            reference = reference,
            candidate = candidate,
            referenceStart = referenceStart,
            candidateStart = candidateStart,
            count = count,
            offsetFrames = offsetFrames,
        )
    }

    private fun DecodedPcmAudio.readLittleEndianShort(byteIndex: Int): Short {
        val low = pcm16[byteIndex].toInt() and 0xFF
        val high = pcm16[byteIndex + 1].toInt()
        return ((high shl 8) or low).toShort()
    }

    private fun DecodedPcmAudio.toStereoPcm16(
        startFrame: Int,
        maxFrames: Int,
    ): ShortArray {
        val frames = minOf(frameCount - startFrame.coerceAtLeast(0), maxFrames)
        val output = ShortArray(maxFrames * MdxDspConfig.STEREO_CHANNELS)
        if (frames <= 0) return output
        val source = slicePcm16(
            startFrame = startFrame,
            frames = frames,
            outputChannelCount = MdxDspConfig.STEREO_CHANNELS,
        )
        source.copyInto(output)
        return output
    }

    private fun DecodedPcmAudio.toStereoPcm16Placed(
        maxFrames: Int,
        destinationStartFrame: Int,
    ): ShortArray {
        val output = ShortArray(maxFrames * MdxDspConfig.STEREO_CHANNELS)
        val safeDestinationStartFrame = destinationStartFrame.coerceIn(0, maxFrames)
        val copyFrames = minOf(frameCount, maxFrames - safeDestinationStartFrame)
        if (copyFrames <= 0) return output
        val source = slicePcm16(
            startFrame = 0,
            frames = copyFrames,
            outputChannelCount = MdxDspConfig.STEREO_CHANNELS,
        )
        source.copyInto(
            destination = output,
            destinationOffset = safeDestinationStartFrame * MdxDspConfig.STEREO_CHANNELS,
        )
        return output
    }

    private fun DecodedPcmAudio.resampleToTargetWindowOnSongTimeline(
        targetSampleRate: Int,
        targetWindowStartFrame: Int,
        sourceWindowStartFrame: Int,
        shouldCancel: () -> Boolean = { false },
    ): ShortArray {
        val output = ShortArray(config.chunkSize * MdxDspConfig.STEREO_CHANNELS)
        if (frameCount == 0) return output

        for (targetFrameInWindow in 0 until config.chunkSize) {
            if (targetFrameInWindow % CANCEL_CHECK_INTERVAL_FRAMES == 0) {
                throwIfCanceled(shouldCancel)
            }
            val globalTargetFrame = targetWindowStartFrame + targetFrameInWindow
            if (globalTargetFrame < 0) continue

            val globalSourcePosition = globalTargetFrame.toDouble() * sampleRate.toDouble() /
                    targetSampleRate.toDouble()
            val localSourcePosition = globalSourcePosition - sourceWindowStartFrame.toDouble()
            if (localSourcePosition < 0.0 || localSourcePosition > (frameCount - 1).toDouble()) {
                continue
            }

            val sourceFrame = floor(localSourcePosition).toInt().coerceIn(0, frameCount - 1)
            val nextFrame = (sourceFrame + 1).coerceAtMost(frameCount - 1)
            val fraction = (localSourcePosition - sourceFrame).toFloat()
            val outputOffset = targetFrameInWindow * MdxDspConfig.STEREO_CHANNELS

            for (channel in 0 until MdxDspConfig.STEREO_CHANNELS) {
                val sourceChannel = channel.coerceAtMost(channelCount - 1)
                val current = readSample(sourceFrame, sourceChannel).toFloat()
                val next = readSample(nextFrame, sourceChannel).toFloat()
                val value = (current + (next - current) * fraction)
                    .roundToInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                output[outputOffset + channel] = value.toShort()
            }
        }
        return output
    }

    private fun DecodedPcmAudio.slicePcm16IntoWindow(
        frames: Int,
        outputChannelCount: Int,
        destinationStartFrame: Int,
    ): ShortArray {
        val output = ShortArray(frames * outputChannelCount)
        val safeDestinationStartFrame = destinationStartFrame.coerceIn(0, frames)
        val copyFrames = minOf(frameCount, frames - safeDestinationStartFrame)
        if (copyFrames <= 0) return output
        val source = slicePcm16(
            startFrame = 0,
            frames = copyFrames,
            outputChannelCount = outputChannelCount,
        )
        source.copyInto(
            destination = output,
            destinationOffset = safeDestinationStartFrame * outputChannelCount,
        )
        return output
    }

    private fun ShortArray.sliceInterleavedFrames(
        startFrame: Int,
        frames: Int,
        channelCount: Int,
    ): ShortArray {
        val output = ShortArray(frames * channelCount)
        val sourceFrameCount = size / channelCount
        val copyStartFrame = startFrame.coerceIn(0, sourceFrameCount)
        val copyEndFrame = (startFrame + frames).coerceIn(copyStartFrame, sourceFrameCount)
        val copyFrames = copyEndFrame - copyStartFrame
        if (copyFrames <= 0) return output
        copyInto(
            destination = output,
            destinationOffset = (copyStartFrame - startFrame) * channelCount,
            startIndex = copyStartFrame * channelCount,
            endIndex = copyEndFrame * channelCount,
        )
        return output
    }

    private fun DecodedPcmAudio.readSample(frame: Int, channel: Int): Short {
        return readLittleEndianShort((frame * channelCount + channel) * Short.SIZE_BYTES)
    }

    private fun frameToMs(frame: Int, sampleRate: Int): Long {
        return (frame.toLong() * MILLIS_PER_SECOND) / sampleRate.toLong()
    }

    private fun frameToUs(frame: Int, sampleRate: Int): Long {
        return (frame.toLong() * MICROS_PER_SECOND) / sampleRate.toLong()
    }

    private fun usDeltaToFrame(timeUs: Long, sampleRate: Int): Int {
        return floor(timeUs.toDouble() * sampleRate.toDouble() / MICROS_PER_SECOND.toDouble())
            .toInt()
            .coerceAtLeast(0)
    }

    private fun sourceFrameToTargetFrame(sourceFrame: Int, sourceSampleRate: Int): Int {
        return floor(sourceFrame.toDouble() * config.sampleRate.toDouble() / sourceSampleRate.toDouble())
            .toInt()
            .coerceAtLeast(0)
    }

    private fun targetFrameToSourceFrameFloor(targetFrame: Int, sourceSampleRate: Int): Int {
        return floor(targetFrame.toDouble() * sourceSampleRate.toDouble() / config.sampleRate.toDouble())
            .toInt()
            .coerceAtLeast(0)
    }

    private fun targetFrameToSourceFrameCeil(targetFrame: Int, sourceSampleRate: Int): Int {
        return ceil(targetFrame.toDouble() * sourceSampleRate.toDouble() / config.sampleRate.toDouble())
            .toInt()
            .coerceAtLeast(0)
    }

    private fun MdxDspConfig.trimFramesToUs(): Long {
        return (trim.toLong() * MICROS_PER_SECOND) / sampleRate.toLong()
    }

    private fun uniqueFile(dir: File, name: String): File {
        val extensionIndex = name.lastIndexOf('.')
        val base = if (extensionIndex > 0) name.substring(0, extensionIndex) else name
        val extension = if (extensionIndex > 0) name.substring(extensionIndex) else ""
        var candidate = File(dir, name)
        var suffix = 2
        while (candidate.exists()) {
            candidate = File(dir, "${base}_$suffix$extension")
            suffix += 1
        }
        return candidate
    }

    private inline fun <T> timed(block: () -> T): TimedValue<T> {
        val start = SystemClock.elapsedRealtime()
        return TimedValue(
            value = block(),
            elapsedMs = SystemClock.elapsedRealtime() - start,
        )
    }

    private fun throwIfCanceled(shouldCancel: () -> Boolean) {
        if (shouldCancel()) {
            throw CancellationException("Source separation canceled.")
        }
    }

    private data class TimedValue<T>(
        val value: T,
        val elapsedMs: Long,
    )

    private companion object {
        const val MILLIS_PER_SECOND = 1000L
        const val MICROS_PER_SECOND = 1_000_000L
        const val MAX_OFFSET_SEARCH_FRAMES = 4096
        const val BEST_OFFSET_COARSE_STEP_FRAMES = 16
        const val BEST_OFFSET_REFINE_RADIUS_FRAMES = 32
        const val BEST_OFFSET_SAMPLE_STRIDE_FRAMES = 64
        const val PROBE_WINDOW_MARGIN_MS = 7_000L
        const val CANCEL_CHECK_INTERVAL_FRAMES = 16_384
        const val REFERENCE_STEP_COUNT = 2
        const val PROBE_STEP_COUNT = 14
        const val REPORT_STEP_COUNT = 1
        const val MP3_MIME_TYPE = "audio/mpeg"
    }
}

private data class SourceRateCandidateComparisons(
    val sourceRate: PcmWindowAlignmentComparison,
    val sourceRatePreroll: PcmWindowAlignmentComparison,
    val sourceRateTimestampPlacement: PcmWindowAlignmentComparison?,
    val sourceRateFrameDeficitPlacement: PcmWindowAlignmentComparison,
    val sourceRateMp3CalibratedPlacement: PcmWindowAlignmentComparison?,
    val sourceRateMetadataDelayPlacement: PcmWindowAlignmentComparison?,
)

private data class SongTimelineCandidateComparisons(
    val songTimeline: PcmWindowAlignmentComparison,
    val songTimelineTimestampPlacement: PcmWindowAlignmentComparison?,
    val songTimelineFrameDeficitPlacement: PcmWindowAlignmentComparison,
    val songTimelineMp3CalibratedPlacement: PcmWindowAlignmentComparison?,
    val songTimelineMetadataDelayPlacement: PcmWindowAlignmentComparison?,
)

private data class PrerollCandidateComparisons(
    val prerollResample: PcmWindowAlignmentComparison,
    val prerollSongTimeline: PcmWindowAlignmentComparison,
)

data class AudioWindowDecodeExperimentProgress(
    val completedSteps: Int,
    val totalSteps: Int,
    val stage: String,
    val probeIndex: Int? = null,
    val probeCount: Int? = null,
) {
    val percent: Int = if (totalSteps > 0) {
        ((completedSteps * 100.0) / totalSteps.toDouble()).roundToInt().coerceIn(0, 100)
    } else {
        0
    }
}

data class AudioWindowDecodeExperimentResult(
    val reportFile: File,
    val displayName: String,
    val requestedPlaybackPositionMs: Long,
    val sourceSampleRate: Int,
    val referenceFrameCount: Int,
    val referenceChannelCount: Int,
    val fullDecodeMs: Long,
    val fullResampleMs: Long,
    val probes: List<AudioWindowDecodeProbeResult>,
) {
    val totalLocalDecodeMs: Long = probes.sumOf { it.localWindowDecodeMs }
    val totalPrerollDecodeMs: Long = probes.sumOf { it.prerollWindowDecodeMs }
    val totalLocalResampleMs: Long = probes.sumOf { it.localWindowResampleMs }
    val totalPrerollResampleMs: Long = probes.sumOf { it.prerollWindowResampleMs }
    val totalSongTimelineResampleMs: Long = probes.sumOf { it.songTimelineResampleMs }
    val totalPrerollSongTimelineResampleMs: Long = probes.sumOf { it.prerollSongTimelineResampleMs }
    val worstSongTimelineSummary: PcmWindowComparison = familySummary(
        AudioWindowDecodeCandidateFamily.SongTimelineResample,
    ).worstBestOffset

    fun toReportText(): String {
        return buildString {
            appendLine("Audio window decode experiment")
            appendLine("Song: $displayName")
            appendLine("Requested playback position: ${requestedPlaybackPositionMs}ms")
            appendLine()
            appendLine("Reference full decode:")
            appendLine("Frames: $referenceFrameCount")
            appendLine("Sample rate: $sourceSampleRate")
            appendLine("Channels: $referenceChannelCount")
            appendLine("Full decode: ${fullDecodeMs}ms")
            appendLine("Full resample: ${fullResampleMs}ms")
            appendLine()
            appendLine("Probe summary:")
            appendLine("Probe count: ${probes.size}")
            appendLine("Total local decode: ${totalLocalDecodeMs}ms")
            appendLine("Total preroll decode: ${totalPrerollDecodeMs}ms")
            appendLine("Total local resample: ${totalLocalResampleMs}ms")
            appendLine("Total preroll resample: ${totalPrerollResampleMs}ms")
            appendLine("Total song-timeline resample: ${totalSongTimelineResampleMs}ms")
            appendLine("Total preroll song-timeline resample: ${totalPrerollSongTimelineResampleMs}ms")
            appendLine()
            appendLine("Production candidate focus:")
            appendFamilyFocus(AudioWindowDecodeCandidateFamily.SongTimelineResample)
            appendFamilyFocus(AudioWindowDecodeCandidateFamily.SongTimelineFrameDeficitPlacement)
            appendFamilyFocus(AudioWindowDecodeCandidateFamily.PrerollSongTimelineResample)
            appendLine()
            appendMp3AnchorCalibration()
            appendLine()
            appendLine("Candidate family summary:")
            AudioWindowDecodeCandidateFamily.entries.forEach { family ->
                val summary = familySummary(family)
                appendLine("${family.reportName}:")
                if (summary.available) {
                    appendLine("  Full-window direct best: ${summary.bestDirect.toReportLine()}")
                    appendLine("  Full-window direct worst: ${summary.worstDirect.toReportLine()}")
                    appendLine("  Stable-region direct best: ${summary.bestStableDirect.toNullableReportLine()}")
                    appendLine("  Stable-region direct worst: ${summary.worstStableDirect.toNullableReportLine()}")
                    appendLine("  Full-window best-offset best: ${summary.bestBestOffset.toReportLine()}")
                    appendLine("  Full-window best-offset worst: ${summary.worstBestOffset.toReportLine()}")
                    appendLine("  Stable-region best-offset best: ${summary.bestStableBestOffset.toNullableReportLine()}")
                    appendLine("  Stable-region best-offset worst: ${summary.worstStableBestOffset.toNullableReportLine()}")
                    appendLine("  Full-window best-offset frames: ${summary.bestOffsetFrames.toOffsetSeriesReport()}")
                    appendLine("  Stable-region best-offset frames: ${summary.stableBestOffsetFrames.toOffsetSeriesReport()}")
                    appendLine("  Worst absolute full-window best offset: ${summary.worstAbsoluteBestOffsetFrames} frames")
                    appendLine("  Worst absolute stable-region best offset: ${summary.worstAbsoluteStableBestOffsetFrames.toNullableFramesReport()}")
                    appendLine("  Direct usability: ${summary.directUsability}")
                } else {
                    appendLine("  Unavailable for these probes")
                }
            }
            appendLine()
            probes.forEachIndexed { index, probe ->
                appendLine("Probe ${index + 1}: ${probe.label}")
                appendLine(probe.toReportText())
                appendLine()
            }
            appendLine("Note:")
            appendLine("Raw source-rate comparison isolates seek and decoder delay from resampling.")
            appendLine("Current resample uses the existing local-window phase.")
            appendLine("Song-timeline resample samples the local decoded PCM using absolute song-frame positions on the target 44.1 kHz timeline.")
            appendLine("Timestamp and frame-deficit placement candidates test whether local decoder delay can be explained before production rules are chosen.")
            appendLine("Preroll cursor uses an earlier seek point and trims by decoded frame cursor.")
            appendLine("Best/worst summaries are intentionally grouped by candidate family; raw source-rate and resampled candidates are not mixed.")
            appendLine("Direct summaries compare the candidate exactly as production would use it; best-offset summaries only diagnose whether a fixed or variable delay explains a mismatch.")
            appendLine("Stable-region summaries compare only the segment body that would be written after trimming model context.")
            appendLine("MP3 anchor calibration is diagnostic only; production must use a deterministic correction profile or fall back to full-song decode.")
        }
    }

    private fun familySummary(
        family: AudioWindowDecodeCandidateFamily,
    ): AudioWindowDecodeCandidateFamilySummary {
        val comparisons = probes.mapNotNull { it.comparisonFor(family) }
        val directComparisons = comparisons.map { it.direct }
        val bestOffsetComparisons = comparisons.map { it.bestOffset }
        val stableDirectComparisons = comparisons.mapNotNull { it.stableDirect }
        val stableBestOffsetComparisons = comparisons.mapNotNull { it.stableBestOffset }
        return AudioWindowDecodeCandidateFamilySummary(
            available = comparisons.isNotEmpty(),
            bestDirect = directComparisons.minByOrNull { it.meanAbsoluteError } ?: emptyComparison(),
            worstDirect = directComparisons.maxByOrNull { it.meanAbsoluteError } ?: emptyComparison(),
            bestStableDirect = stableDirectComparisons.minByOrNull { it.meanAbsoluteError },
            worstStableDirect = stableDirectComparisons.maxByOrNull { it.meanAbsoluteError },
            bestBestOffset = bestOffsetComparisons.minByOrNull { it.meanAbsoluteError } ?: emptyComparison(),
            worstBestOffset = bestOffsetComparisons.maxByOrNull { it.meanAbsoluteError } ?: emptyComparison(),
            bestStableBestOffset = stableBestOffsetComparisons.minByOrNull { it.meanAbsoluteError },
            worstStableBestOffset = stableBestOffsetComparisons.maxByOrNull { it.meanAbsoluteError },
            bestOffsetFrames = bestOffsetComparisons.map { it.offsetFrames },
            stableBestOffsetFrames = stableBestOffsetComparisons.map { it.offsetFrames },
        )
    }

    private fun emptyComparison(): PcmWindowComparison {
        return PcmWindowComparison(0, 0, 0, 0.0, 0)
    }

    private fun StringBuilder.appendFamilyFocus(
        family: AudioWindowDecodeCandidateFamily,
    ) {
        val summary = familySummary(family)
        appendLine("${family.reportName}:")
        if (!summary.available) {
            appendLine("  unavailable")
            return
        }
        appendLine("  Stable direct worst: ${summary.worstStableDirect.toNullableReportLine()}")
        appendLine("  Stable best-offset worst: ${summary.worstStableBestOffset.toNullableReportLine()}")
        appendLine("  Stable best-offset frames: ${summary.stableBestOffsetFrames.toOffsetSeriesReport()}")
        appendLine("  Direct usability: ${summary.directUsability}")
    }

    private fun StringBuilder.appendMp3AnchorCalibration() {
        val summary = mp3AnchorCalibrationSummary()
        appendLine("MP3 anchor calibration:")
        if (summary == null) {
            appendLine("  Not applicable; this report did not decode audio/mpeg probes with stable comparisons.")
            return
        }

        appendLine("  MIME type: ${summary.mimeType}")
        appendLine("  Encoder delay frames: ${summary.encoderDelayFrames.toNullableFramesReport()}")
        appendLine("  Encoder padding frames: ${summary.encoderPaddingFrames.toNullableFramesReport()}")
        appendLine("  Assumed MP3 frame size: $MP3_FRAME_SIZE_FRAMES source frames")
        appendLine("  First-probe correction: ${summary.firstProbeCorrectionFrames} frames")
        appendLine(
            "  Beginning-segment correction: ${summary.beginningSegmentCorrectionFrames} frames " +
                    "from ${summary.beginningSegmentProbeCount} probe(s), spread ${summary.beginningSegmentSpreadFrames} frames"
        )
        appendLine(
            "  All-probe median correction: ${summary.allProbeMedianCorrectionFrames} frames, " +
                    "spread ${summary.allProbeSpreadFrames} frames"
        )
        appendLine(
            "  Beginning correction prediction: exact ${summary.beginningPredictionExactCount}/${summary.probeRows.size}, " +
                    "within 1 frame ${summary.beginningPredictionWithinOneFrameCount}/${summary.probeRows.size}, " +
                    "within 4 frames ${summary.beginningPredictionWithinFourFramesCount}/${summary.probeRows.size}, " +
                    "worst error ${summary.beginningPredictionWorstAbsErrorFrames} frames"
        )
        appendLine(
            "  Holdout calibrated direct: ${summary.holdoutCalibratedDirectSummary}"
        )
        appendLine(
            "  Holdout calibrated best-offset: ${summary.holdoutCalibratedBestOffsetSummary}"
        )
        appendLine("  Recommendation: ${summary.recommendation}")
        appendLine("  Probe rows:")
        appendLine("    # phase posMs segment requestedMod extractorDelta frameDeficit residual resolved directBest calibratedDirect calibratedBest predicted error")
        summary.probeRows.forEachIndexed { index, row ->
            val predicted = row.predictedPlacement(summary.beginningSegmentCorrectionFrames)
            val error = row.predictionError(summary.beginningSegmentCorrectionFrames)
            appendLine(
                "    ${index + 1} ${if (row.isCalibrationProbe) "calibration" else "holdout"} " +
                        "${row.playbackPositionMs} ${row.segmentIndex} " +
                        "${row.requestedStartModuloFrames} ${row.extractorStartDeltaFrames} " +
                        "${row.frameDeficitOffsetFrames} ${row.frameDeficitResidualFrames} " +
                        "${row.resolvedPlacementFrames} ${row.directStableBestOffsetFrames} " +
                        "${row.calibratedStableDirect.toNullableReportLine()} " +
                        "${row.calibratedStableBestOffset.toNullableReportLine()} " +
                        "$predicted $error"
            )
        }
    }

    private fun mp3AnchorCalibrationSummary(): Mp3AnchorCalibrationSummary? {
        val mp3Probes = probes.filter { it.trackMetadata?.mimeType == MP3_MIME_TYPE }
        if (mp3Probes.isEmpty()) return null

        val rows = mp3Probes.mapNotNull { probe ->
            val directBest = probe.sourceRateComparison.stableBestOffset ?: return@mapNotNull null
            val frameDeficitBest = probe.sourceRateFrameDeficitPlacementComparison.stableBestOffset
                ?: return@mapNotNull null
            val extractorStartFrame = usToSourceFrameFloor(probe.extractorStartUs)
            val extractorStartDeltaFrames = extractorStartFrame - probe.requestedSourceWindowStartFrame
            val resolvedPlacementFrames = probe.frameDeficitPlacementOffsetSourceFrames +
                    frameDeficitBest.offsetFrames
            Mp3AnchorCalibrationProbeRow(
                playbackPositionMs = probe.playbackPositionMs,
                segmentIndex = probe.segmentIndex,
                isCalibrationProbe = probe.isCalibrationProbe,
                requestedSourceWindowStartFrame = probe.requestedSourceWindowStartFrame,
                requestedStartModuloFrames = positiveModulo(
                    probe.requestedSourceWindowStartFrame,
                    MP3_FRAME_SIZE_FRAMES,
                ),
                extractorStartDeltaFrames = extractorStartDeltaFrames,
                frameDeficitOffsetFrames = probe.frameDeficitPlacementOffsetSourceFrames,
                frameDeficitResidualFrames = frameDeficitBest.offsetFrames,
                resolvedPlacementFrames = resolvedPlacementFrames,
                directStableBestOffsetFrames = directBest.offsetFrames,
                calibratedStableDirect = probe.sourceRateMp3CalibratedPlacementComparison?.stableDirect,
                calibratedStableBestOffset = probe.sourceRateMp3CalibratedPlacementComparison?.stableBestOffset,
            )
        }
        if (rows.isEmpty()) return null

        val beginningRows = rows.filter { it.segmentIndex == 0 }.ifEmpty { listOf(rows.first()) }
        val beginningCorrectionFrames = medianRounded(beginningRows.map { it.frameDeficitResidualFrames })
        val allCorrectionFrames = medianRounded(rows.map { it.frameDeficitResidualFrames })
        val metadata = mp3Probes.firstNotNullOfOrNull { it.trackMetadata }
        return Mp3AnchorCalibrationSummary(
            mimeType = metadata?.mimeType ?: MP3_MIME_TYPE,
            encoderDelayFrames = metadata?.encoderDelayFrames,
            encoderPaddingFrames = metadata?.encoderPaddingFrames,
            probeRows = rows,
            firstProbeCorrectionFrames = rows.first().frameDeficitResidualFrames,
            beginningSegmentCorrectionFrames = beginningCorrectionFrames,
            beginningSegmentProbeCount = beginningRows.size,
            beginningSegmentSpreadFrames = spreadFrames(beginningRows.map { it.frameDeficitResidualFrames }),
            allProbeMedianCorrectionFrames = allCorrectionFrames,
            allProbeSpreadFrames = spreadFrames(rows.map { it.frameDeficitResidualFrames }),
        )
    }

    private fun usToSourceFrameFloor(timeUs: Long): Int {
        return floor(timeUs.toDouble() * sourceSampleRate.toDouble() / MICROS_PER_SECOND.toDouble())
            .toInt()
    }

    private fun medianRounded(values: List<Int>): Int {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            ((sorted[middle - 1] + sorted[middle]).toDouble() / 2.0).roundToInt()
        }
    }

    private fun spreadFrames(values: List<Int>): Int {
        return (values.maxOrNull() ?: 0) - (values.minOrNull() ?: 0)
    }

    private fun positiveModulo(value: Int, modulo: Int): Int {
        return ((value % modulo) + modulo) % modulo
    }

    private fun PcmWindowComparison?.toNullableReportLine(): String {
        return this?.toReportLine() ?: "unavailable"
    }

    private fun Int?.toNullableFramesReport(): String {
        return this?.let { "$it frames" } ?: "unavailable"
    }

    private fun List<Int>.toOffsetSeriesReport(): String {
        if (isEmpty()) return "unavailable"
        val distinct = distinct()
        val values = joinToString(prefix = "[", postfix = "]")
        return if (distinct.size == 1) {
            "$values constant"
        } else {
            "$values variable"
        }
    }

    private companion object {
        const val MP3_MIME_TYPE = "audio/mpeg"
        const val MP3_FRAME_SIZE_FRAMES = 1152
        const val MICROS_PER_SECOND = 1_000_000L
    }
}

private data class AudioWindowDecodeProbePlan(
    val index: Int,
    val playbackPositionMs: Long,
    val label: String,
    val isCalibration: Boolean,
)

private data class Mp3AnchorCalibrationSummary(
    val mimeType: String,
    val encoderDelayFrames: Int?,
    val encoderPaddingFrames: Int?,
    val probeRows: List<Mp3AnchorCalibrationProbeRow>,
    val firstProbeCorrectionFrames: Int,
    val beginningSegmentCorrectionFrames: Int,
    val beginningSegmentProbeCount: Int,
    val beginningSegmentSpreadFrames: Int,
    val allProbeMedianCorrectionFrames: Int,
    val allProbeSpreadFrames: Int,
) {
    val beginningPredictionExactCount: Int = probeRows.count {
        it.predictionError(beginningSegmentCorrectionFrames) == 0
    }
    val beginningPredictionWithinOneFrameCount: Int = probeRows.count {
        abs(it.predictionError(beginningSegmentCorrectionFrames)) <= 1
    }
    val beginningPredictionWithinFourFramesCount: Int = probeRows.count {
        abs(it.predictionError(beginningSegmentCorrectionFrames)) <= 4
    }
    val beginningPredictionWorstAbsErrorFrames: Int = probeRows.maxOfOrNull {
        abs(it.predictionError(beginningSegmentCorrectionFrames))
    } ?: 0
    val holdoutCalibratedDirectSummary: String = summarizeHoldoutComparisons {
        it.calibratedStableDirect
    }
    val holdoutCalibratedBestOffsetSummary: String = summarizeHoldoutComparisons {
        it.calibratedStableBestOffset
    }
    val recommendation: String = when {
        holdoutCalibratedDirectIsBitPerfect ->
            "strong; calibrated holdout windows are bit-perfect at zero offset"
        beginningSegmentSpreadFrames <= 1 && beginningPredictionWorstAbsErrorFrames <= 1 ->
            "strong; a short beginning-segment calibration predicts all tested MP3 anchors within 1 frame"
        beginningSegmentSpreadFrames <= 4 && beginningPredictionWorstAbsErrorFrames <= 4 ->
            "promising; a short beginning-segment calibration predicts all tested MP3 anchors within 4 frames"
        else ->
            "risky; correction spread is too wide for production without a fallback"
    }

    private val holdoutCalibratedDirectIsBitPerfect: Boolean
        get() {
            val holdoutComparisons = probeRows
                .filterNot { it.isCalibrationProbe }
                .mapNotNull { it.calibratedStableDirect }
            return holdoutComparisons.isNotEmpty() && holdoutComparisons.all { it.maxAbsoluteError == 0 }
        }

    private fun summarizeHoldoutComparisons(
        selector: (Mp3AnchorCalibrationProbeRow) -> PcmWindowComparison?,
    ): String {
        val comparisons = probeRows
            .filterNot { it.isCalibrationProbe }
            .mapNotNull(selector)
        if (comparisons.isEmpty()) return "unavailable"
        val worst = comparisons.maxBy { it.meanAbsoluteError }
        val bitPerfect = comparisons.count { it.maxAbsoluteError == 0 }
        return "$bitPerfect/${comparisons.size} bit-perfect, worst ${worst.toReportLine()}"
    }
}

private data class Mp3AnchorCalibrationProbeRow(
    val playbackPositionMs: Long,
    val segmentIndex: Int,
    val isCalibrationProbe: Boolean,
    val requestedSourceWindowStartFrame: Int,
    val requestedStartModuloFrames: Int,
    val extractorStartDeltaFrames: Int,
    val frameDeficitOffsetFrames: Int,
    val frameDeficitResidualFrames: Int,
    val resolvedPlacementFrames: Int,
    val directStableBestOffsetFrames: Int,
    val calibratedStableDirect: PcmWindowComparison?,
    val calibratedStableBestOffset: PcmWindowComparison?,
) {
    fun predictedPlacement(correctionFrames: Int): Int {
        return frameDeficitOffsetFrames + correctionFrames
    }

    fun predictionError(correctionFrames: Int): Int {
        return predictedPlacement(correctionFrames) - directStableBestOffsetFrames
    }
}

data class AudioWindowDecodeProbeResult(
    val label: String,
    val isCalibrationProbe: Boolean,
    val playbackPositionMs: Long,
    val segmentIndex: Int,
    val targetFrame: Int,
    val targetSampleRateFrame: Int,
    val requestedStartUs: Long,
    val requestedEndUs: Long,
    val requestedWindowStartFrame: Int,
    val requestedWindowEndFrame: Int,
    val requestedSourceWindowStartFrame: Int,
    val requestedSourceWindowEndFrame: Int,
    val stableSourceStartFrame: Int,
    val stableSourceFrameCount: Int,
    val stableTargetStartFrame: Int,
    val stableTargetFrameCount: Int,
    val localWindowDecodeMs: Long,
    val localWindowResampleMs: Long,
    val prerollWindowDecodeMs: Long,
    val prerollWindowResampleMs: Long,
    val songTimelineResampleMs: Long,
    val songTimelineTimestampResampleMs: Long,
    val songTimelineFrameDeficitResampleMs: Long,
    val songTimelineMp3CalibratedResampleMs: Long?,
    val songTimelineMetadataDelayResampleMs: Long?,
    val prerollSongTimelineResampleMs: Long,
    val extractorStartUs: Long,
    val firstOutputTimeUs: Long?,
    val lastOutputTimeUs: Long?,
    val prerollCursorAnchorTimeUs: Long?,
    val outputBufferCount: Int,
    val windowFrameCount: Int,
    val trackMetadata: AudioDecodeTrackMetadata?,
    val timestampPlacementOffsetSourceFrames: Int?,
    val frameDeficitPlacementOffsetSourceFrames: Int,
    val mp3CalibrationCorrectionFrames: Int?,
    val mp3CalibratedPlacementOffsetSourceFrames: Int?,
    val metadataDelayPlacementOffsetSourceFrames: Int?,
    val sourceRateComparison: PcmWindowAlignmentComparison,
    val sourceRatePrerollComparison: PcmWindowAlignmentComparison,
    val sourceRateTimestampPlacementComparison: PcmWindowAlignmentComparison?,
    val sourceRateFrameDeficitPlacementComparison: PcmWindowAlignmentComparison,
    val sourceRateMp3CalibratedPlacementComparison: PcmWindowAlignmentComparison?,
    val sourceRateMetadataDelayPlacementComparison: PcmWindowAlignmentComparison?,
    val currentResampledComparison: PcmWindowAlignmentComparison,
    val songTimelineResampledComparison: PcmWindowAlignmentComparison,
    val songTimelineTimestampPlacementComparison: PcmWindowAlignmentComparison?,
    val songTimelineFrameDeficitPlacementComparison: PcmWindowAlignmentComparison,
    val songTimelineMp3CalibratedPlacementComparison: PcmWindowAlignmentComparison?,
    val songTimelineMetadataDelayPlacementComparison: PcmWindowAlignmentComparison?,
    val prerollResampledComparison: PcmWindowAlignmentComparison,
    val prerollSongTimelineResampledComparison: PcmWindowAlignmentComparison,
) {
    fun comparisonFor(
        family: AudioWindowDecodeCandidateFamily,
    ): PcmWindowAlignmentComparison? {
        return when (family) {
            AudioWindowDecodeCandidateFamily.SourceRate -> sourceRateComparison
            AudioWindowDecodeCandidateFamily.SourceRatePreroll -> sourceRatePrerollComparison
            AudioWindowDecodeCandidateFamily.SourceRateTimestampPlacement -> sourceRateTimestampPlacementComparison
            AudioWindowDecodeCandidateFamily.SourceRateFrameDeficitPlacement -> sourceRateFrameDeficitPlacementComparison
            AudioWindowDecodeCandidateFamily.SourceRateMp3CalibratedPlacement -> sourceRateMp3CalibratedPlacementComparison
            AudioWindowDecodeCandidateFamily.SourceRateMetadataDelayPlacement -> sourceRateMetadataDelayPlacementComparison
            AudioWindowDecodeCandidateFamily.CurrentResample -> currentResampledComparison
            AudioWindowDecodeCandidateFamily.SongTimelineResample -> songTimelineResampledComparison
            AudioWindowDecodeCandidateFamily.SongTimelineTimestampPlacement -> songTimelineTimestampPlacementComparison
            AudioWindowDecodeCandidateFamily.SongTimelineFrameDeficitPlacement -> songTimelineFrameDeficitPlacementComparison
            AudioWindowDecodeCandidateFamily.SongTimelineMp3CalibratedPlacement -> songTimelineMp3CalibratedPlacementComparison
            AudioWindowDecodeCandidateFamily.SongTimelineMetadataDelayPlacement -> songTimelineMetadataDelayPlacementComparison
            AudioWindowDecodeCandidateFamily.PrerollResample -> prerollResampledComparison
            AudioWindowDecodeCandidateFamily.PrerollSongTimelineResample -> prerollSongTimelineResampledComparison
        }
    }

    fun toReportText(): String {
        return buildString {
            appendLine("Playback position: ${playbackPositionMs}ms")
            appendLine("Calibration probe: ${if (isCalibrationProbe) "yes" else "no"}")
            appendLine("Segment index: $segmentIndex")
            appendLine("Source-rate target frame: $targetFrame")
            appendLine("Target-rate frame: $targetSampleRateFrame")
            appendLine("Requested target-rate window frames: $requestedWindowStartFrame..$requestedWindowEndFrame")
            appendLine("Requested source-rate window frames: $requestedSourceWindowStartFrame..$requestedSourceWindowEndFrame")
            appendLine(
                "Stable target-rate region in window: $stableTargetStartFrame.." +
                        "${stableTargetStartFrame + stableTargetFrameCount}"
            )
            appendLine(
                "Stable source-rate region in window: $stableSourceStartFrame.." +
                        "${stableSourceStartFrame + stableSourceFrameCount}"
            )
            appendLine("Requested window time: ${requestedStartUs}us..${requestedEndUs}us")
            appendLine()
            appendLine("Local window decode:")
            appendLine("Extractor start: ${extractorStartUs}us")
            appendLine("First output: ${firstOutputTimeUs.toOptionalUs()}")
            appendLine("Last output: ${lastOutputTimeUs.toOptionalUs()}")
            appendLine("Preroll cursor anchor: ${prerollCursorAnchorTimeUs.toOptionalUs()}")
            appendLine("Output buffers: $outputBufferCount")
            appendLine("Frames after decode: $windowFrameCount")
            appendLine("MIME type: ${trackMetadata?.mimeType ?: "unavailable"}")
            appendLine("Encoder delay frames: ${trackMetadata?.encoderDelayFrames.toOptionalFrames()}")
            appendLine("Encoder padding frames: ${trackMetadata?.encoderPaddingFrames.toOptionalFrames()}")
            appendLine("Timestamp placement offset source frames: ${timestampPlacementOffsetSourceFrames.toOptionalFrames()}")
            appendLine("Frame-deficit placement offset source frames: $frameDeficitPlacementOffsetSourceFrames")
            appendLine("MP3 calibration correction frames: ${mp3CalibrationCorrectionFrames.toOptionalFrames()}")
            appendLine("MP3 calibrated placement offset source frames: ${mp3CalibratedPlacementOffsetSourceFrames.toOptionalFrames()}")
            appendLine("Metadata-delay placement offset source frames: ${metadataDelayPlacementOffsetSourceFrames.toOptionalFrames()}")
            appendLine("Local decode: ${localWindowDecodeMs}ms")
            appendLine("Local resample: ${localWindowResampleMs}ms")
            appendLine("Preroll decode: ${prerollWindowDecodeMs}ms")
            appendLine("Preroll resample: ${prerollWindowResampleMs}ms")
            appendLine("Song-timeline resample: ${songTimelineResampleMs}ms")
            appendLine("Song-timeline timestamp resample: ${songTimelineTimestampResampleMs}ms")
            appendLine("Song-timeline frame-deficit resample: ${songTimelineFrameDeficitResampleMs}ms")
            appendLine("Song-timeline MP3 calibrated resample: ${songTimelineMp3CalibratedResampleMs.toOptionalMs()}")
            appendLine("Song-timeline metadata-delay resample: ${songTimelineMetadataDelayResampleMs.toOptionalMs()}")
            appendLine("Preroll song-timeline resample: ${prerollSongTimelineResampleMs}ms")
            appendLine()
            appendLine("PCM comparison:")
            appendComparison("Source-rate", sourceRateComparison)
            appendComparison("Source-rate preroll", sourceRatePrerollComparison)
            appendNullableComparison("Source-rate timestamp placement", sourceRateTimestampPlacementComparison)
            appendComparison("Source-rate frame-deficit placement", sourceRateFrameDeficitPlacementComparison)
            appendNullableComparison("Source-rate MP3 calibrated placement", sourceRateMp3CalibratedPlacementComparison)
            appendNullableComparison("Source-rate metadata-delay placement", sourceRateMetadataDelayPlacementComparison)
            appendComparison("Current resample", currentResampledComparison)
            appendComparison("Song-timeline resample", songTimelineResampledComparison)
            appendNullableComparison("Song-timeline timestamp placement", songTimelineTimestampPlacementComparison)
            appendComparison("Song-timeline frame-deficit placement", songTimelineFrameDeficitPlacementComparison)
            appendNullableComparison("Song-timeline MP3 calibrated placement", songTimelineMp3CalibratedPlacementComparison)
            appendNullableComparison("Song-timeline metadata-delay placement", songTimelineMetadataDelayPlacementComparison)
            appendComparison("Preroll resample", prerollResampledComparison)
            appendComparison("Preroll song-timeline resample", prerollSongTimelineResampledComparison)
        }
    }

    private fun StringBuilder.appendNullableComparison(
        label: String,
        comparison: PcmWindowAlignmentComparison?,
    ) {
        if (comparison == null) {
            appendLine("$label: unavailable")
        } else {
            appendComparison(label, comparison)
        }
    }

    private fun StringBuilder.appendComparison(
        label: String,
        comparison: PcmWindowAlignmentComparison,
    ) {
        appendLine("$label direct: ${comparison.direct.toReportLine()}")
        appendLine("$label stable direct: ${comparison.stableDirect.toNullableReportLine()}")
        appendLine("$label best offset: ${comparison.bestOffset.toReportLine()}")
        appendLine("$label stable best offset: ${comparison.stableBestOffset.toNullableReportLine()}")
    }

    private fun PcmWindowComparison?.toNullableReportLine(): String {
        return this?.toReportLine() ?: "unavailable"
    }

    private fun Long?.toOptionalMs(): String {
        return this?.let { "${it}ms" } ?: "unavailable"
    }

    private fun Long?.toOptionalUs(): String {
        return this?.let { "${it}us" } ?: "unavailable"
    }

    private fun Int?.toOptionalFrames(): String {
        return this?.let { "$it" } ?: "unavailable"
    }
}

enum class AudioWindowDecodeCandidateFamily(
    val reportName: String,
) {
    SourceRate("Source-rate local"),
    SourceRatePreroll("Source-rate preroll"),
    SourceRateTimestampPlacement("Source-rate timestamp placement"),
    SourceRateFrameDeficitPlacement("Source-rate frame-deficit placement"),
    SourceRateMp3CalibratedPlacement("Source-rate MP3 calibrated placement"),
    SourceRateMetadataDelayPlacement("Source-rate metadata-delay placement"),
    CurrentResample("Current local resample"),
    SongTimelineResample("Song-timeline resample"),
    SongTimelineTimestampPlacement("Song-timeline timestamp placement"),
    SongTimelineFrameDeficitPlacement("Song-timeline frame-deficit placement"),
    SongTimelineMp3CalibratedPlacement("Song-timeline MP3 calibrated placement"),
    SongTimelineMetadataDelayPlacement("Song-timeline metadata-delay placement"),
    PrerollResample("Preroll local resample"),
    PrerollSongTimelineResample("Preroll song-timeline resample"),
}

data class AudioWindowDecodeCandidateFamilySummary(
    val available: Boolean,
    val bestDirect: PcmWindowComparison,
    val worstDirect: PcmWindowComparison,
    val bestStableDirect: PcmWindowComparison?,
    val worstStableDirect: PcmWindowComparison?,
    val bestBestOffset: PcmWindowComparison,
    val worstBestOffset: PcmWindowComparison,
    val bestStableBestOffset: PcmWindowComparison?,
    val worstStableBestOffset: PcmWindowComparison?,
    val bestOffsetFrames: List<Int>,
    val stableBestOffsetFrames: List<Int>,
) {
    val worstAbsoluteBestOffsetFrames: Int = bestOffsetFrames.maxOfOrNull { abs(it) } ?: 0
    val worstAbsoluteStableBestOffsetFrames: Int? = stableBestOffsetFrames.maxOfOrNull { abs(it) }
    val directUsability: String
        get() {
            val stableWorst = worstStableDirect ?: return "unknown; stable-region comparison unavailable"
            return when {
                stableWorst.maxAbsoluteError == 0 -> "strong; stable region is bit-perfect at zero offset"
                stableWorst.meanAbsoluteError <= DIRECT_USABILITY_MEAN_ERROR_THRESHOLD &&
                        stableWorst.maxAbsoluteError <= DIRECT_USABILITY_MAX_ERROR_THRESHOLD ->
                    "likely usable; stable zero-offset error is tiny"
                else -> "risky; stable zero-offset comparison still has audible-scale mismatch"
            }
        }

    private companion object {
        const val DIRECT_USABILITY_MEAN_ERROR_THRESHOLD = 1.0
        const val DIRECT_USABILITY_MAX_ERROR_THRESHOLD = 256
    }
}

data class PcmWindowAlignmentComparison(
    val direct: PcmWindowComparison,
    val bestOffset: PcmWindowComparison,
    val stableDirect: PcmWindowComparison?,
    val stableBestOffset: PcmWindowComparison?,
)

data class PcmWindowComparison(
    val offsetFrames: Int,
    val comparedSamples: Int,
    val equalSamples: Int,
    val meanAbsoluteError: Double,
    val maxAbsoluteError: Int,
) {
    fun toReportLine(): String {
        val equalPercent = if (comparedSamples > 0) {
            equalSamples.toDouble() * 100.0 / comparedSamples.toDouble()
        } else {
            0.0
        }
        return "offsetFrames=$offsetFrames comparedSamples=$comparedSamples " +
                "equalSamples=$equalSamples (${String.format("%.2f", equalPercent)}%) " +
                "meanAbsError=${String.format("%.3f", meanAbsoluteError)} maxAbsError=$maxAbsoluteError"
    }
}
