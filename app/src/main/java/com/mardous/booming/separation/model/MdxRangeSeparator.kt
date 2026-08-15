package com.mardous.booming.separation.model

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.mardous.booming.BuildConfig
import com.mardous.booming.separation.audio.WavFileWriter
import com.mardous.booming.separation.SourceSeparationPausedException
import com.mardous.booming.separation.SourceSeparationStemLabelResolver
import com.mardous.booming.separation.cache.SourceSeparationSegmentScheduler
import com.mardous.booming.separation.cache.SourceSeparationSegmentPlan
import com.mardous.booming.separation.cache.SourceSeparationSegmentPriority
import com.mardous.booming.separation.cache.SourceSeparationSegmentState
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheFaultInjection
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheFaultRuntimeDiagnostics
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheFaultStage
import java.io.File
import java.util.LinkedHashMap
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlin.math.sqrt

class MdxRangeSeparator(
    private val context: Context,
    private val config: MdxDspConfig = MdxDspConfig(),
    private val runtimeSettings: MdxRuntimeSettings = MdxRuntimeSettings(),
) {
    fun separate(
        uri: Uri,
        outputDir: File,
        segmentOutputDir: File? = null,
        displayName: String,
        startMs: Long = 0L,
        endMs: Long? = null,
        runtimeSettings: MdxRuntimeSettings = this.runtimeSettings,
        onProgress: (MdxRangeProgress) -> Unit = {},
        onPrepared: (MdxRangePreparation) -> Unit = {},
        onSegmentStateChanged: (segmentIndex: Int, state: SourceSeparationSegmentState) -> Unit = { _, _ -> },
        playbackPositionMsProvider: () -> Long? = { null },
        playbackReadyWindowCountProvider: () -> Int = { DEFAULT_PLAYBACK_READY_WINDOW_COUNT },
        windowDecodeEnabled: Boolean = true,
        resumeState: MdxRangeResumeState? = null,
        execution: MdxSeparationExecution,
        expectedSourceAudioFingerprint: String? = null,
        sessionProvider: MdxInferenceSessionProvider,
        onRuntimeDiagnostics: (MdxRuntimeDiagnostics) -> Unit = {},
        shouldPause: () -> Boolean = { false },
        shouldCancel: () -> Boolean = { false },
        requireWorkspaceAvailable: () -> Unit = {},
    ): MdxRangeSeparationResult {
        val timing = MdxRangeTimingAccumulator()
        val totalStartedAt = SystemClock.elapsedRealtime()
        throwIfCanceled(shouldCancel)
        val executionProfile = execution.profile
        require(executionProfile.dspConfig == config) {
            "The range separator DSP configuration does not match the execution profile."
        }
        require(expectedSourceAudioFingerprint == null ||
            expectedSourceAudioFingerprint.isNotBlank() &&
            expectedSourceAudioFingerprint != PENDING_SOURCE_AUDIO_FINGERPRINT
        ) {
            "Expected source audio fingerprint is invalid."
        }
        onProgress(MdxRangeProgress.preparing("Preparing model file"))
        val modelArtifact = execution.artifact
        executionProfile.validateArtifact(modelArtifact)
        val effectiveResume = resumeState
        var sourceInput = MdxSourceInput.create(
            context = context,
            config = config,
            uri = uri,
            displayName = displayName,
            windowDecodeEnabled = windowDecodeEnabled,
            timing = timing,
            onProgress = onProgress,
            shouldCancel = shouldCancel,
        )
        throwIfCanceled(shouldCancel)

        val startFrame = msToFrame(startMs).coerceIn(0, sourceInput.outputFrameCount)
        val requestedEndFrame = endMs?.let { msToFrame(it) } ?: sourceInput.outputFrameCount
        val endFrame = requestedEndFrame.coerceIn(startFrame, sourceInput.outputFrameCount)
        val targetFrames = endFrame - startFrame
        require(targetFrames > 0) { "Selected range is empty." }

        onProgress(MdxRangeProgress.preparing("Preparing output files"))
        val (vocalsFile, instrumentalFile, timingFile) = measureElapsed(timing, "Output setup") {
            requireWorkspaceAvailable()
            outputDir.mkdirs()
            val baseName = safeBaseName(displayName)
            val rangeTag =
                "${executionProfile.outputTag}_${frameToMs(startFrame)}ms_${frameToMs(endFrame)}ms"
            Triple(
                effectiveResume?.vocalsFile
                    ?.takeIf { it.parentFile == outputDir }
                    ?: uniqueOutputFile(outputDir, "${baseName}_${rangeTag}_vocals.wav"),
                effectiveResume?.instrumentalFile
                    ?.takeIf { it.parentFile == outputDir }
                    ?: uniqueOutputFile(outputDir, "${baseName}_${rangeTag}_instrumental.wav"),
                effectiveResume?.timingFile
                    ?.takeIf { it.parentFile == outputDir }
                    ?: uniqueOutputFile(outputDir, "${baseName}_${rangeTag}_timing.txt"),
            )
        }

        val segmentPlan = SourceSeparationSegmentPlan.build(
            rangeStartFrame = startFrame,
            rangeEndFrame = endFrame,
            sampleRate = config.sampleRate,
            generationSize = config.generationSize,
            trim = config.trim,
            chunkSize = config.chunkSize,
            stemIds = executionProfile.orderedStemIds,
            defaultState = if (segmentOutputDir != null) {
                SourceSeparationSegmentState.Queued
            } else {
                SourceSeparationSegmentState.Missing
            },
        )
        var currentSegmentPlan = segmentPlan
        currentSegmentPlan = effectiveResume?.segmentPlan
            ?.takeIf { existing ->
                existing.rangeStartFrame == segmentPlan.rangeStartFrame &&
                        existing.rangeEndFrame == segmentPlan.rangeEndFrame &&
                existing.sampleRate == segmentPlan.sampleRate &&
                        existing.generationSize == segmentPlan.generationSize &&
                        existing.segmentCount == segmentPlan.segmentCount &&
                        existing.stemIds == segmentPlan.stemIds
            }
            ?: segmentPlan
        requireWorkspaceAvailable()
        segmentOutputDir?.mkdirs()
        val segmentPublicationId = java.util.UUID.randomUUID().toString()

        val windowCount = segmentPlan.segmentCount
        onPrepared(
            MdxRangePreparation(
                vocalsFile = vocalsFile,
                instrumentalFile = instrumentalFile,
                timingFile = timingFile,
                startMs = frameToMs(startFrame),
                endMs = frameToMs(endFrame),
                frames = targetFrames,
                windowCount = windowCount,
                sourceAudioFingerprint = expectedSourceAudioFingerprint
                    ?: PENDING_SOURCE_AUDIO_FINGERPRINT,
                sourceFrameCount = sourceInput.sourceFrameCount,
                sourceSampleRate = sourceInput.sourceSampleRate,
                sourceChannelCount = sourceInput.sourceChannelCount,
                outputSampleRate = config.sampleRate,
                segmentPlan = currentSegmentPlan,
            )
        )

        val declaredOutputDataSizeBytes = if (segmentOutputDir != null) {
            targetFrames.toLong() * MdxDspConfig.STEREO_CHANNELS * Short.SIZE_BYTES
        } else {
            null
        }
        val canWriteWindowsByFrame = declaredOutputDataSizeBytes != null
        var mp3WindowOverlapGuard = if (canWriteWindowsByFrame && sourceInput.usesMp3WindowDecode) {
            Mp3LazyWindowOverlapGuard(config)
        } else {
            null
        }
        var runtimeDiagnostics: MdxRuntimeDiagnostics? = null

        val spectrogram = MdxWindowDspFactory.create(config)
        try {
            requireWorkspaceAvailable()
            WavFileWriter(
                file = vocalsFile,
                sampleRate = config.sampleRate,
                channelCount = MdxDspConfig.STEREO_CHANNELS,
                declaredDataSizeBytes = declaredOutputDataSizeBytes,
                preserveExistingData = effectiveResume != null,
            ).use { vocalsWriter ->
            requireWorkspaceAvailable()
            WavFileWriter(
                file = instrumentalFile,
                sampleRate = config.sampleRate,
                channelCount = MdxDspConfig.STEREO_CHANNELS,
                declaredDataSizeBytes = declaredOutputDataSizeBytes,
                preserveExistingData = effectiveResume != null,
            ).use { instrumentalWriter ->
                onProgress(MdxRangeProgress.preparing("Creating model session"))
                val sessionLease = measureElapsed(timing, "Session setup") {
                    sessionProvider.acquire(
                        artifact = modelArtifact,
                        profile = executionProfile,
                        runtimeSettings = runtimeSettings,
                    )
                }
                sessionLease.use { lease ->
                    val session = lease.session
                    session.diagnostics.also { diagnostics ->
                        runtimeDiagnostics = diagnostics
                        onRuntimeDiagnostics(diagnostics)
                    }
                    var processedWindowCount = currentSegmentPlan.segments
                        .count { it.state == SourceSeparationSegmentState.Ready }
                    val processedSegments = mutableSetOf<Int>()
                    var schedulerTargetSegmentIndex: Int? = null
                    while (processedWindowCount < windowCount) {
                        val windowStartedAt = SystemClock.elapsedRealtime()
                        throwIfPaused(shouldPause)
                        throwIfCanceled(shouldCancel)
                        var requestedPlaybackSegmentIndex: Int? = null
                        var selectedPriority: SourceSeparationSegmentPriority? = null
                        val readyWindowCount = playbackReadyWindowCountProvider()
                            .coerceAtLeast(DEFAULT_PLAYBACK_READY_WINDOW_COUNT)
                        val readySegmentCount = currentSegmentPlan.segments
                            .count { it.state == SourceSeparationSegmentState.Ready }
                        val segment = if (canWriteWindowsByFrame) {
                            val latestPlaybackSegmentIndex = playbackPositionMsProvider()
                                ?.let { msToFrame(it) }
                                ?.coerceIn(startFrame, endFrame)
                                ?.let { currentSegmentPlan.segmentIndexForFrame(it) }
                            if (latestPlaybackSegmentIndex != null &&
                                latestPlaybackSegmentIndex != schedulerTargetSegmentIndex
                            ) {
                                schedulerTargetSegmentIndex = latestPlaybackSegmentIndex
                            }
                            requestedPlaybackSegmentIndex = schedulerTargetSegmentIndex
                            val playbackFrame = schedulerTargetSegmentIndex
                                ?.let { index -> currentSegmentPlan.segments.getOrNull(index)?.playbackStartFrame }
                                ?: (startFrame + processedWindowCount * config.generationSize)
                            SourceSeparationSegmentScheduler
                                .prioritize(
                                    segmentPlan = currentSegmentPlan,
                                    playbackFrame = playbackFrame,
                                    readyWindowCount = readyWindowCount,
                                )
                                .firstOrNull { it.segment.index !in processedSegments }
                                ?.also { selectedPriority = it.priority }
                                ?.segment
                        } else {
                            null
                        } ?: currentSegmentPlan.segments.firstOrNull {
                            it.index !in processedSegments &&
                                    it.state != SourceSeparationSegmentState.Ready
                        }
                            ?: break
                        selectedPriority = selectedPriority ?: SourceSeparationSegmentPriority.IdleBackfill
                        val windowIndex = segment.index
                        val generationStartFrame = segment.playbackStartFrame
                        val remainingFrames = endFrame - generationStartFrame
                        val writeFrames = minOf(config.generationSize, remainingFrames)
                        val schedulerProgress = MdxSegmentSchedulerProgress(
                            playbackSegmentIndex = requestedPlaybackSegmentIndex,
                            playbackSegmentState = requestedPlaybackSegmentIndex
                                ?.let { currentSegmentPlan.segments.getOrNull(it)?.state?.name },
                            nextSegmentIndex = requestedPlaybackSegmentIndex?.plus(1)
                                ?.takeIf { it < windowCount },
                            nextSegmentState = requestedPlaybackSegmentIndex?.plus(1)
                                ?.let { currentSegmentPlan.segments.getOrNull(it)?.state?.name },
                            processingSegmentIndex = segment.index,
                            priority = selectedPriority.name,
                            readySegments = readySegmentCount,
                            totalSegments = windowCount,
                            readyWindowCount = readyWindowCount,
                            playbackReadyWindowReadyCount = currentSegmentPlan
                                .playbackReadyWindowReadyCount(
                                    playbackSegmentIndex = requestedPlaybackSegmentIndex,
                                    readyWindowCount = readyWindowCount,
                                ),
                            playbackReadyWindowPendingCount = currentSegmentPlan
                                .playbackReadyWindowPendingCount(
                                    playbackSegmentIndex = requestedPlaybackSegmentIndex,
                                    readyWindowCount = readyWindowCount,
                                    processingSegmentIndex = segment.index,
                                ),
                        )
                        val processingState = if (segment.state == SourceSeparationSegmentState.Misaligned) {
                            SourceSeparationSegmentState.Misaligned
                        } else {
                            SourceSeparationSegmentState.Running
                        }
                        currentSegmentPlan = currentSegmentPlan.withSegmentState(
                            segmentIndex = segment.index,
                            state = processingState,
                        )
                        if (processingState == SourceSeparationSegmentState.Running) {
                            onSegmentStateChanged(segment.index, SourceSeparationSegmentState.Running)
                        }
                        onProgress(
                            MdxRangeProgress(
                                processedWindowCount,
                                windowCount,
                                stage = "Preparing window ${windowIndex + 1}/${windowCount}",
                                sourceDecodeDiagnostics = sourceInput.diagnostics,
                                scheduler = schedulerProgress,
                                runtimeBackend = runtimeDiagnostics?.backend,
                            )
                        )
                        SourceSeparationCacheFaultInjection.reach(
                            SourceSeparationCacheFaultStage.Decode,
                        )
                        throwIfPaused(shouldPause)
                        throwIfCanceled(shouldCancel)
                        requireWorkspaceAvailable()
                        val mixWindow = sourceInput.toStereoFloatContextWindow(
                            windowStartFrame = generationStartFrame - config.trim,
                            frames = config.chunkSize,
                            timing = timing,
                            shouldCancel = shouldCancel,
                        )
                        val overlapResult = mp3WindowOverlapGuard?.observe(segment.index, mixWindow)
                        if (overlapResult is Mp3LazyWindowOverlapResult.Failed) {
                            Mp3WindowDecodeSessionGate.disable(overlapResult.reason)
                            val resetState = if (segmentOutputDir != null) {
                                SourceSeparationSegmentState.Queued
                            } else {
                                SourceSeparationSegmentState.Missing
                            }
                            val invalidatedSegments = overlapResult.misalignedSegmentIndexes + segment.index
                            for (index in invalidatedSegments.sorted()) {
                                val state = if (segmentOutputDir != null && index in processedSegments) {
                                    SourceSeparationSegmentState.Misaligned
                                } else {
                                    resetState
                                }
                                currentSegmentPlan = currentSegmentPlan.withSegmentState(
                                    segmentIndex = index,
                                    state = state,
                                )
                                processedSegments.remove(index)
                                onSegmentStateChanged(index, state)
                            }
                            processedWindowCount = currentSegmentPlan.segments
                                .count { it.state == SourceSeparationSegmentState.Ready }
                            mp3WindowOverlapGuard = null
                            onProgress(
                                MdxRangeProgress(
                                    processedWindowCount,
                                    windowCount,
                                    stage = "MP3 window overlap check failed; decoding full source",
                                    sourceDecodeDiagnostics = sourceInput.diagnostics,
                                    scheduler = schedulerProgress,
                                    runtimeBackend = runtimeDiagnostics?.backend,
                                )
                            )
                            sourceInput = MdxSourceInput.createFullSongFallback(
                                context = context,
                                config = config,
                                uri = uri,
                                fallbackReason = overlapResult.reason,
                                timing = timing,
                                onProgress = onProgress,
                                shouldCancel = shouldCancel,
                            )
                            throwIfCanceled(shouldCancel)
                            continue
                        }

                        throwIfCanceled(shouldCancel)
                        SourceSeparationCacheFaultInjection.reach(
                            SourceSeparationCacheFaultStage.Dsp,
                        )
                        throwIfPaused(shouldPause)
                        throwIfCanceled(shouldCancel)
                        requireWorkspaceAvailable()
                        val modelOutputWindow = runWindow(
                            session = session,
                            spectrogram = spectrogram,
                            mixWindow = mixWindow,
                            timing = timing,
                            shouldCancel = shouldCancel,
                            requireWorkspaceAvailable = requireWorkspaceAvailable,
                        )
                        // Auto may switch from GPU to CPU during invocation; capture the
                        // post-run diagnostics so the completed cache records the real path.
                        session.diagnostics.also { diagnostics ->
                            runtimeDiagnostics = diagnostics
                            onRuntimeDiagnostics(diagnostics)
                        }
                        throwIfCanceled(shouldCancel)
                        val scaledModelOutputWindow = measureElapsed(timing, "Output compensation") {
                            compensateMdxModelOutput(
                                rawModelOutput = modelOutputWindow,
                                modelOutputScale = executionProfile.modelOutputScale,
                            )
                        }
                        val residualWindow = measureElapsed(timing, "Stem subtract") {
                            reconstructMdxResidual(mixWindow, scaledModelOutputWindow)
                        }
                        val mappedStems = mapMdxStemWaveformsFromComponents(
                            scaledModelOutput = scaledModelOutputWindow,
                            residual = residualWindow,
                            modelOutputStem = executionProfile.modelOutputStem,
                        )

                        val vocalsPcm = measureElapsed(timing, "PCM convert") {
                            stereoFloatToPcm16(
                                waveform = mappedStems.vocals,
                                startFrame = config.trim,
                                frames = writeFrames,
                            )
                        }
                        val instrumentalPcm = measureElapsed(timing, "PCM convert") {
                            stereoFloatToPcm16(
                                waveform = mappedStems.instrumental,
                                startFrame = config.trim,
                                frames = writeFrames,
                            )
                        }
                        val windowResult = SourceSeparationWindowResult(
                            frameCount = writeFrames,
                            channelCount = MdxDspConfig.STEREO_CHANNELS,
                            stems = segment.stems.map { stem ->
                                SourceSeparationStemChunk(
                                    stemId = stem.stemId,
                                    order = stem.order,
                                    pcm16 = when (
                                        executionProfile.physicalStemFor(stem.stemId)
                                    ) {
                                        MdxStem.VOCALS -> vocalsPcm
                                        MdxStem.INSTRUMENTAL -> instrumentalPcm
                                    },
                                )
                            },
                        )
                        measureElapsed(timing, "WAV write") {
                            val writeFrameOffset = generationStartFrame - startFrame
                            windowResult.stems.forEach { stem ->
                                val writer = when (
                                    executionProfile.physicalStemFor(stem.stemId)
                                ) {
                                    MdxStem.VOCALS -> vocalsWriter
                                    MdxStem.INSTRUMENTAL -> instrumentalWriter
                                }
                                requireWorkspaceAvailable()
                                if (declaredOutputDataSizeBytes != null) {
                                    writer.writePcm16AtFrame(writeFrameOffset, stem.pcm16)
                                } else {
                                    writer.writePcm16(stem.pcm16)
                                }
                                if (segmentOutputDir != null) {
                                    requireWorkspaceAvailable()
                                    publishSourceSeparationSegmentWav(
                                        file = File(
                                            segmentOutputDir.parentFile ?: segmentOutputDir,
                                            segment.pathFor(stem.stemId),
                                        ),
                                        pcm16 = stem.pcm16,
                                        sampleRate = config.sampleRate,
                                        channelCount = MdxDspConfig.STEREO_CHANNELS,
                                        publicationId = segmentPublicationId,
                                        requireWorkspaceAvailable = requireWorkspaceAvailable,
                                    )
                                }
                            }
                        }
                        processedSegments += segment.index
                        currentSegmentPlan = currentSegmentPlan.withSegmentState(
                            segmentIndex = segment.index,
                            state = SourceSeparationSegmentState.Ready,
                        )
                        processedWindowCount = currentSegmentPlan.segments
                            .count { it.state == SourceSeparationSegmentState.Ready }
                        onSegmentStateChanged(segment.index, SourceSeparationSegmentState.Ready)
                        val windowElapsedMs = SystemClock.elapsedRealtime() - windowStartedAt
                        onProgress(
                            MdxRangeProgress(
                                processedWindowCount,
                                windowCount,
                                stage = "Processed window ${windowIndex + 1}/${windowCount}",
                                sourceDecodeDiagnostics = sourceInput.diagnostics,
                                completedWindowElapsedMs = windowElapsedMs,
                                scheduler = schedulerProgress.copy(
                                    playbackSegmentState = requestedPlaybackSegmentIndex
                                        ?.let { currentSegmentPlan.segments.getOrNull(it)?.state?.name },
                                    nextSegmentState = requestedPlaybackSegmentIndex?.plus(1)
                                        ?.let { currentSegmentPlan.segments.getOrNull(it)?.state?.name },
                                    readySegments = processedWindowCount,
                                    playbackReadyWindowReadyCount = currentSegmentPlan
                                        .playbackReadyWindowReadyCount(
                                            playbackSegmentIndex = requestedPlaybackSegmentIndex,
                                            readyWindowCount = readyWindowCount,
                                        ),
                                    playbackReadyWindowPendingCount = currentSegmentPlan
                                        .playbackReadyWindowPendingCount(
                                            playbackSegmentIndex = requestedPlaybackSegmentIndex,
                                            readyWindowCount = readyWindowCount,
                                            processingSegmentIndex = null,
                                        ),
                                ),
                                runtimeBackend = runtimeDiagnostics?.backend,
                            )
                        )
                        throwIfCanceled(shouldCancel)
                    }
                }
            }
            }
        } finally {
            spectrogram.close()
        }

        onProgress(
            MdxRangeProgress(
                windowCount,
                windowCount,
                stage = "Hashing source audio",
                sourceDecodeDiagnostics = sourceInput.diagnostics,
                runtimeBackend = runtimeDiagnostics?.backend,
            )
        )
        val sourceAudioFingerprint = sourceInput.sourceAudioFingerprint(timing, shouldCancel)
        require(expectedSourceAudioFingerprint == null ||
            sourceAudioFingerprint == expectedSourceAudioFingerprint
        ) {
            "Source audio changed while separation was running."
        }
        throwIfCanceled(shouldCancel)
        val elapsedMs = SystemClock.elapsedRealtime() - totalStartedAt
        onProgress(
            MdxRangeProgress(
                windowCount,
                windowCount,
                stage = "Writing timing report",
                sourceDecodeDiagnostics = sourceInput.diagnostics,
                runtimeBackend = runtimeDiagnostics?.backend,
            )
        )
        val timingReport = timing.toReport(
            audioDurationSeconds = targetFrames.toDouble() / config.sampleRate,
            windowCount = windowCount,
            totalMs = elapsedMs,
            runtimeSettings = runtimeSettings,
            runtimeDiagnostics = requireNotNull(runtimeDiagnostics) {
                "Inference runtime diagnostics were not initialized."
            },
            executionProfile = executionProfile,
            sourceDecodeDiagnostics = sourceInput.diagnostics,
            dspImplementationId = spectrogram.implementationId,
        )
        requireWorkspaceAvailable()
        timingFile.writeText(
            timingReport.toFileText(
                vocalsFile = vocalsFile,
                instrumentalFile = instrumentalFile,
                stemLabelResolver = { label ->
                    SourceSeparationStemLabelResolver.resolve(context, label)
                },
            ),
            Charsets.UTF_8,
        )

        return MdxRangeSeparationResult(
            vocalsFile = vocalsFile,
            instrumentalFile = instrumentalFile,
            timingFile = timingFile,
            startMs = frameToMs(startFrame),
            endMs = frameToMs(endFrame),
            frames = targetFrames,
            windowCount = windowCount,
            elapsedMs = elapsedMs,
            sourceAudioFingerprint = sourceAudioFingerprint,
            sourceFrameCount = sourceInput.sourceFrameCount,
            sourceSampleRate = sourceInput.sourceSampleRate,
            sourceChannelCount = sourceInput.sourceChannelCount,
            outputSampleRate = config.sampleRate,
            segmentPlan = currentSegmentPlan,
            timingReport = timingReport,
            runtimeSettings = runtimeSettings,
            runtimeDiagnostics = timingReport.runtimeDiagnostics,
            executionProfile = executionProfile,
            sourceDecodeDiagnostics = sourceInput.diagnostics,
        )
    }

    private inline fun <T> measureElapsed(timing: MdxRangeTimingAccumulator, stage: String, block: () -> T): T {
        val startedAt = SystemClock.elapsedRealtime()
        return try {
            block()
        } finally {
            timing.add(stage, SystemClock.elapsedRealtime() - startedAt)
        }
    }

    private fun throwIfCanceled(shouldCancel: () -> Boolean) {
        if (shouldCancel()) {
            throw CancellationException("Source separation canceled.")
        }
    }

    private fun throwIfPaused(shouldPause: () -> Boolean) {
        if (shouldPause()) {
            throw SourceSeparationPausedException()
        }
    }

    private fun runWindow(
        session: MdxInferenceSession,
        spectrogram: MdxWindowDsp,
        mixWindow: Array<FloatArray>,
        timing: MdxRangeTimingAccumulator,
        shouldCancel: () -> Boolean,
        requireWorkspaceAvailable: () -> Unit,
    ): Array<FloatArray> {
        val modelInput = measureElapsed(timing, "STFT") {
            spectrogram.waveformToNchwTensor(mixWindow)
        }
        val modelOutput = measureElapsed(timing, "Model inference") {
            val runtimeDiagnostics = if (BuildConfig.DEBUG) session.diagnostics else null
            SourceSeparationCacheFaultInjection.reach(
                SourceSeparationCacheFaultStage.NativeInvocation,
                runtime = runtimeDiagnostics?.let { diagnostics ->
                    SourceSeparationCacheFaultRuntimeDiagnostics(
                        runtimeName = diagnostics.runtimeName,
                        backend = diagnostics.backend.name,
                        fallbackStage = diagnostics.fallbackStage,
                        fallbackReason = diagnostics.fallbackReason,
                    )
                },
            )
            requireWorkspaceAvailable()
            session.run(modelInput, shouldCancel)
        }
        return measureElapsed(timing, "ISTFT") {
            spectrogram.nchwTensorToWaveform(modelOutput)
        }
    }

    private fun stereoFloatToPcm16(waveform: Array<FloatArray>, startFrame: Int, frames: Int): ByteArray {
        val bytes = ByteArray(frames * MdxDspConfig.STEREO_CHANNELS * Short.SIZE_BYTES)
        var offset = 0
        val endFrame = startFrame + frames
        for (frame in startFrame until endFrame) {
            for (channel in 0 until MdxDspConfig.STEREO_CHANNELS) {
                val value = (waveform[channel][frame].coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                bytes[offset++] = (value and 0xFF).toByte()
                bytes[offset++] = ((value ushr 8) and 0xFF).toByte()
            }
        }
        return bytes
    }

    private fun msToFrame(ms: Long): Int {
        return ((ms.coerceAtLeast(0) * config.sampleRate) / 1000L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }

    private fun frameToMs(frame: Int): Long {
        return (frame.toLong() * 1000L) / config.sampleRate
    }

    private fun safeBaseName(displayName: String): String {
        return displayName.substringBeforeLast('.')
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .ifBlank { "audio" }
    }

    private fun uniqueOutputFile(outputDir: File, name: String): File {
        val extensionIndex = name.lastIndexOf('.')
        val base = if (extensionIndex > 0) name.substring(0, extensionIndex) else name
        val extension = if (extensionIndex > 0) name.substring(extensionIndex) else ""
        var candidate = File(outputDir, name)
        var suffix = 2
        while (candidate.exists()) {
            candidate = File(outputDir, "${base}_$suffix$extension")
            suffix += 1
        }
        return candidate
    }

    private fun SourceSeparationSegmentPlan.playbackReadyWindowReadyCount(
        playbackSegmentIndex: Int?,
        readyWindowCount: Int,
    ): Int {
        return playbackReadyWindowStates(playbackSegmentIndex, readyWindowCount)
            .count { it.state.isPlaybackReady }
    }

    private fun SourceSeparationSegmentPlan.playbackReadyWindowPendingCount(
        playbackSegmentIndex: Int?,
        readyWindowCount: Int,
        processingSegmentIndex: Int?,
    ): Int {
        val playbackWindowStates = playbackReadyWindowStates(
            playbackSegmentIndex = playbackSegmentIndex,
            readyWindowCount = readyWindowCount,
        )
        val pendingPlaybackWindows = playbackWindowStates
            .count { !it.state.isPlaybackReady }
        val processingOutsidePlaybackWindow = processingSegmentIndex != null &&
                playbackWindowStates.none { it.index == processingSegmentIndex } &&
                segments.getOrNull(processingSegmentIndex)?.state?.isPlaybackReady != true
        return pendingPlaybackWindows + if (processingOutsidePlaybackWindow) 1 else 0
    }

    private fun SourceSeparationSegmentPlan.playbackReadyWindowStates(
        playbackSegmentIndex: Int?,
        readyWindowCount: Int,
    ): List<com.mardous.booming.separation.cache.SourceSeparationSegment> {
        if (playbackSegmentIndex == null || segments.isEmpty()) return emptyList()
        val startIndex = playbackSegmentIndex.coerceIn(segments.indices)
        val endIndex = (startIndex + readyWindowCount.coerceAtLeast(1))
            .coerceAtMost(segments.size)
        return segments.subList(startIndex, endIndex)
    }
}

private class Mp3LazyWindowOverlapGuard(
    private val config: MdxDspConfig,
) {
    private val windows = object : LinkedHashMap<Int, Array<FloatArray>>(MAX_STORED_WINDOWS, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<Int, Array<FloatArray>>,
        ): Boolean {
            return size > MAX_STORED_WINDOWS
        }
    }
    private val zeroOffsetObservations = mutableListOf<Mp3ZeroOffsetObservation>()

    fun observe(
        segmentIndex: Int,
        mixWindow: Array<FloatArray>,
    ): Mp3LazyWindowOverlapResult {
        val comparisons = buildList {
            windows[segmentIndex - 1]?.let { previous ->
                add(compareAdjacent(previous, mixWindow, segmentIndex - 1, segmentIndex))
            }
            windows[segmentIndex + 1]?.let { next ->
                add(compareAdjacent(mixWindow, next, segmentIndex, segmentIndex + 1))
            }
        }
        windows[segmentIndex] = mixWindow

        comparisons.firstOrNull { it is Mp3LazyWindowOverlapResult.Failed }?.let {
            return it
        }
        return if (comparisons.any { it is Mp3LazyWindowOverlapResult.Passed }) {
            Mp3LazyWindowOverlapResult.Passed
        } else {
            Mp3LazyWindowOverlapResult.Inconclusive
        }
    }

    private fun compareAdjacent(
        lowerWindow: Array<FloatArray>,
        upperWindow: Array<FloatArray>,
        lowerSegmentIndex: Int,
        upperSegmentIndex: Int,
    ): Mp3LazyWindowOverlapResult {
        val overlapFrames = config.chunkSize - config.generationSize
        val edgeGuardFrames = minOf(MP3_OVERLAP_EDGE_GUARD_FRAMES, overlapFrames / 4)
        val stableFrames = overlapFrames - edgeGuardFrames * 2
        if (stableFrames < MIN_MP3_OVERLAP_COMPARISON_FRAMES) {
            return Mp3LazyWindowOverlapResult.Inconclusive
        }

        val signalRms = signalRms(
            lowerWindow = lowerWindow,
            upperWindow = upperWindow,
            edgeGuardFrames = edgeGuardFrames,
            frames = stableFrames,
        )
        if (signalRms < MP3_OVERLAP_MIN_SIGNAL_RMS) {
            return Mp3LazyWindowOverlapResult.Inconclusive
        }

        val zeroMetrics = overlapMetricsAtOffset(
            lowerWindow = lowerWindow,
            upperWindow = upperWindow,
            edgeGuardFrames = edgeGuardFrames,
            offsetFrames = 0,
        ) ?: return Mp3LazyWindowOverlapResult.Inconclusive
        val zeroRelativeError = zeroMetrics.errorRms / signalRms.coerceAtLeast(Double.MIN_VALUE)
        val zeroBad = zeroRelativeError >= MP3_ZERO_OFFSET_BAD_RELATIVE_ERROR
        zeroOffsetObservations += Mp3ZeroOffsetObservation(
            lowerSegmentIndex = lowerSegmentIndex,
            upperSegmentIndex = upperSegmentIndex,
            zeroRelativeError = zeroRelativeError,
            bad = zeroBad,
        )
        val observedCount = zeroOffsetObservations.size
        val badCount = zeroOffsetObservations.count { it.bad }
        val badRatio = badCount.toDouble() / observedCount.toDouble().coerceAtLeast(1.0)
        val strongZeroMismatch = observedCount >= MP3_ZERO_OFFSET_MIN_COMPARISONS &&
                badCount >= MP3_ZERO_OFFSET_FAIL_BAD_COMPARISONS &&
                badRatio >= MP3_ZERO_OFFSET_FAIL_BAD_RATIO
        Log.i(
            MP3_OVERLAP_LOG_TAG,
            "segments=$lowerSegmentIndex/$upperSegmentIndex " +
                    "zeroErrorRms=${zeroMetrics.errorRms.format(6)} signalRms=${signalRms.format(6)} " +
                    "zeroLowerRms=${zeroMetrics.lowerRms.format(6)} " +
                    "zeroUpperRms=${zeroMetrics.upperRms.format(6)} " +
                    "zeroRelative=${zeroRelativeError.format(3)} zeroBad=$zeroBad " +
                    "zeroObserved=$observedCount zeroBadCount=$badCount " +
                    "zeroBadRatio=${badRatio.format(3)} strongZeroMismatch=$strongZeroMismatch",
        )
        return if (strongZeroMismatch) {
            val misalignedSegmentIndexes = zeroOffsetObservations
                .filter { it.bad }
                .flatMap { listOf(it.lowerSegmentIndex, it.upperSegmentIndex) }
                .toSet()
            Mp3LazyWindowOverlapResult.Failed(
                reason = "MP3 window overlap mismatch between segments " +
                        "$lowerSegmentIndex/$upperSegmentIndex: " +
                        "zeroErrorRms=${zeroMetrics.errorRms.format(6)}, " +
                        "signalRms=${signalRms.format(6)}, zeroRelative=${zeroRelativeError.format(3)}, " +
                        "zeroObserved=$observedCount, zeroBadCount=$badCount, " +
                        "zeroBadRatio=${badRatio.format(3)}",
                misalignedSegmentIndexes = misalignedSegmentIndexes,
            )
        } else if (zeroBad) {
            Mp3LazyWindowOverlapResult.Inconclusive
        } else {
            Mp3LazyWindowOverlapResult.Passed
        }
    }

    private fun signalRms(
        lowerWindow: Array<FloatArray>,
        upperWindow: Array<FloatArray>,
        edgeGuardFrames: Int,
        frames: Int,
    ): Double {
        var sumSquares = 0.0
        var count = 0
        val lowerStartFrame = config.generationSize + edgeGuardFrames
        val upperStartFrame = edgeGuardFrames
        for (channel in 0 until MdxDspConfig.STEREO_CHANNELS) {
            val lower = lowerWindow[channel]
            val upper = upperWindow[channel]
            for (frame in 0 until frames) {
                val lowerValue = lower[lowerStartFrame + frame].toDouble()
                val upperValue = upper[upperStartFrame + frame].toDouble()
                sumSquares += lowerValue * lowerValue + upperValue * upperValue
                count += 2
            }
        }
        return if (count > 0) sqrt(sumSquares / count.toDouble()) else 0.0
    }

    private fun overlapMetricsAtOffset(
        lowerWindow: Array<FloatArray>,
        upperWindow: Array<FloatArray>,
        edgeGuardFrames: Int,
        offsetFrames: Int,
    ): Mp3OverlapMetrics? {
        val overlapFrames = config.chunkSize - config.generationSize
        val localStartFrame = maxOf(edgeGuardFrames, edgeGuardFrames - offsetFrames)
        val localEndFrame = minOf(
            overlapFrames - edgeGuardFrames,
            overlapFrames - edgeGuardFrames - offsetFrames,
        )
        val frames = localEndFrame - localStartFrame
        if (frames < MIN_MP3_OVERLAP_COMPARISON_FRAMES) return null

        val lowerStartFrame = config.generationSize + localStartFrame
        val upperStartFrame = localStartFrame + offsetFrames
        var errorSumSquares = 0.0
        var lowerSumSquares = 0.0
        var upperSumSquares = 0.0
        var count = 0
        for (channel in 0 until MdxDspConfig.STEREO_CHANNELS) {
            val lower = lowerWindow[channel]
            val upper = upperWindow[channel]
            for (frame in 0 until frames) {
                val lowerValue = lower[lowerStartFrame + frame].toDouble()
                val upperValue = upper[upperStartFrame + frame].toDouble()
                val difference = lowerValue - upperValue
                errorSumSquares += difference * difference
                lowerSumSquares += lowerValue * lowerValue
                upperSumSquares += upperValue * upperValue
                count += 1
            }
        }
        if (count <= 0) return null
        return Mp3OverlapMetrics(
            errorRms = sqrt(errorSumSquares / count.toDouble()),
            lowerRms = sqrt(lowerSumSquares / count.toDouble()),
            upperRms = sqrt(upperSumSquares / count.toDouble()),
        )
    }

    private fun Double.format(decimals: Int): String {
        return "%.${decimals}f".format(Locale.US, this)
    }

    private companion object {
        const val MAX_STORED_WINDOWS = 8
        const val MP3_OVERLAP_EDGE_GUARD_FRAMES = 512
        const val MIN_MP3_OVERLAP_COMPARISON_FRAMES = 1_024
        const val MP3_OVERLAP_MIN_SIGNAL_RMS = 0.001
        const val MP3_ZERO_OFFSET_BAD_RELATIVE_ERROR = 1.15
        const val MP3_ZERO_OFFSET_MIN_COMPARISONS = 4
        const val MP3_ZERO_OFFSET_FAIL_BAD_COMPARISONS = 3
        const val MP3_ZERO_OFFSET_FAIL_BAD_RATIO = 0.75
        const val MP3_OVERLAP_LOG_TAG = "Mp3OverlapGuard"
    }
}

private data class Mp3OverlapMetrics(
    val errorRms: Double,
    val lowerRms: Double,
    val upperRms: Double,
)

private data class Mp3ZeroOffsetObservation(
    val lowerSegmentIndex: Int,
    val upperSegmentIndex: Int,
    val zeroRelativeError: Double,
    val bad: Boolean,
)

private sealed interface Mp3LazyWindowOverlapResult {
    data object Passed : Mp3LazyWindowOverlapResult
    data object Inconclusive : Mp3LazyWindowOverlapResult
    data class Failed(
        val reason: String,
        val misalignedSegmentIndexes: Set<Int>,
    ) : Mp3LazyWindowOverlapResult
}

data class MdxRangeProgress(
    val completedWindows: Int,
    val totalWindows: Int,
    val stage: String? = null,
    val sourceDecodeDiagnostics: MdxSourceDecodeDiagnostics? = null,
    val completedWindowElapsedMs: Long? = null,
    val scheduler: MdxSegmentSchedulerProgress? = null,
    val runtimeBackend: MdxInferenceBackend? = null,
) {
    val percent: Int = if (totalWindows > 0) {
        ((completedWindows * 100.0) / totalWindows).roundToInt()
    } else {
        0
    }

    companion object {
        fun preparing(
            stage: String,
            diagnostics: MdxSourceDecodeDiagnostics? = null,
        ): MdxRangeProgress {
            return MdxRangeProgress(
                completedWindows = 0,
                totalWindows = 0,
                stage = stage,
                sourceDecodeDiagnostics = diagnostics,
            )
        }
    }
}

typealias MdxSegmentSchedulerProgress = SourceSeparationSegmentSchedulerProgress

data class MdxRangePreparation(
    val vocalsFile: File,
    val instrumentalFile: File,
    val timingFile: File,
    val startMs: Long,
    val endMs: Long,
    val frames: Int,
    val windowCount: Int,
    val sourceAudioFingerprint: String,
    val sourceFrameCount: Int,
    val sourceSampleRate: Int,
    val sourceChannelCount: Int,
    val outputSampleRate: Int,
    val segmentPlan: SourceSeparationSegmentPlan,
)

data class MdxRangeSeparationResult(
    val vocalsFile: File,
    val instrumentalFile: File,
    val timingFile: File,
    val startMs: Long,
    val endMs: Long,
    val frames: Int,
    val windowCount: Int,
    val elapsedMs: Long,
    val sourceAudioFingerprint: String,
    val sourceFrameCount: Int,
    val sourceSampleRate: Int,
    val sourceChannelCount: Int,
    val outputSampleRate: Int,
    val segmentPlan: SourceSeparationSegmentPlan,
    val timingReport: MdxRangeTimingReport,
    val runtimeSettings: MdxRuntimeSettings,
    val runtimeDiagnostics: MdxRuntimeDiagnostics,
    val executionProfile: MdxExecutionProfile,
    val sourceDecodeDiagnostics: MdxSourceDecodeDiagnostics,
) {
    val durationSeconds: Double
        get() = frames.toDouble() / outputSampleRate
}

data class MdxSeparationExecution(
    val artifact: MdxModelArtifact,
    val profile: MdxExecutionProfile,
) {
    init {
        profile.validateArtifact(artifact)
    }
}

data class MdxRangeResumeState(
    val vocalsFile: File,
    val instrumentalFile: File,
    val timingFile: File?,
    val segmentPlan: SourceSeparationSegmentPlan,
)


private const val PENDING_SOURCE_AUDIO_FINGERPRINT = "pending"
private const val DEFAULT_PLAYBACK_READY_WINDOW_COUNT = 2
