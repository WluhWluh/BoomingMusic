package com.mardous.booming.separation.process

import com.mardous.booming.separation.SourceSeparationModelAwareExecutionRequest
import com.mardous.booming.separation.SourceSeparationModelAwareRangeExecutor
import com.mardous.booming.separation.SourceSeparationPausedException
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRelativePath
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheSourceDiagnostics
import com.mardous.booming.separation.model.MdxRangePreparation
import com.mardous.booming.separation.model.MdxRangeProgress
import com.mardous.booming.separation.model.MdxRangeSeparationResult
import com.mardous.booming.separation.model.MdxRangeTimingReport
import com.mardous.booming.separation.model.MdxRuntimeDiagnostics
import com.mardous.booming.separation.model.MdxRuntimeSettings
import com.mardous.booming.separation.model.MdxInferenceBackend
import com.mardous.booming.separation.model.MdxModelVariant
import com.mardous.booming.separation.model.MdxSegmentSchedulerProgress
import com.mardous.booming.separation.model.MdxSourceDecodeDiagnostics
import com.mardous.booming.separation.model.MdxSourceDecodeMode
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

internal class InProcessSourceSeparationExecutionHost(
    private val rangeExecutor: SourceSeparationModelAwareRangeExecutor,
    override val processGeneration: Long = DEFAULT_PROCESS_GENERATION,
) : SourceSeparationExecutionHost {
    override val mode: SourceSeparationExecutionHostMode =
        SourceSeparationExecutionHostMode.InProcess

    private val lock = Any()
    private var closed = false
    private var activeRun: ActiveRun? = null

    init {
        require(processGeneration > 0L) { "Execution process generation is invalid." }
    }

    override fun start(
        request: SourceSeparationExecutionHostRequest,
    ): SourceSeparationExecutionHostStartResult {
        val active = synchronized(lock) {
            check(!closed) { "The in-process execution host is closed." }
            check(activeRun == null) { "The in-process execution host is busy." }
            require(request.descriptor.processGeneration == processGeneration) {
                "Execution descriptor targets a stale process generation."
            }
            request.requireExactDescriptor()
            ActiveRun(request).also { activeRun = it }
        }
        return try {
            emit(
                active = active,
                payload = SourceSeparationExecutionHostEventPayload.Accepted(request.descriptor),
                lifecycle = SourceSeparationExecutionHostLifecycle.Running,
            )
            val result = rangeExecutor.separate(active.executionRequest())
            val event = emit(
                active = active,
                payload = SourceSeparationExecutionHostEventPayload.Completed(
                    result.toExecutionCompletion(active.request.executionRequest),
                ),
                lifecycle = SourceSeparationExecutionHostLifecycle.Completed,
                backend = result.runtimeDiagnostics.backend.name,
                runtimeName = result.runtimeDiagnostics.runtimeName,
            )
            SourceSeparationExecutionHostStartResult(
                result = result,
                diagnostics = event.toDiagnostics(
                    mode = mode,
                    lifecycle = SourceSeparationExecutionHostLifecycle.Completed,
                    backend = result.runtimeDiagnostics.backend.name,
                    runtimeName = result.runtimeDiagnostics.runtimeName,
                ),
            )
        } catch (error: SourceSeparationPausedException) {
            emit(
                active = active,
                payload = SourceSeparationExecutionHostEventPayload.Paused(error.message),
                lifecycle = SourceSeparationExecutionHostLifecycle.Paused,
            )
            throw error
        } catch (error: CancellationException) {
            emit(
                active = active,
                payload = SourceSeparationExecutionHostEventPayload.Canceled(error.message),
                lifecycle = SourceSeparationExecutionHostLifecycle.Canceled,
            )
            throw error
        } catch (error: Throwable) {
            emit(
                active = active,
                payload = SourceSeparationExecutionHostEventPayload.Failed(
                    errorType = error::class.java.name,
                    message = error.message,
                ),
                lifecycle = SourceSeparationExecutionHostLifecycle.Failed,
            )
            throw error
        }
    }

    override fun snapshot(
        runId: String,
        processGeneration: Long,
    ): SourceSeparationExecutionHostSnapshot? = synchronized(lock) {
        if (processGeneration != this.processGeneration) return@synchronized null
        val active = activeRun?.takeIf { it.request.descriptor.runId == runId }
            ?: return@synchronized null
        val latestEvent = active.latestEvent ?: return@synchronized null
        SourceSeparationExecutionHostSnapshot(
            descriptor = active.request.descriptor,
            diagnostics = active.diagnostics(mode),
            latestEvent = latestEvent,
        )
    }

    override fun pause(
        runId: String,
        processGeneration: Long,
    ): SourceSeparationExecutionHostControlResult = updateControl(
        runId = runId,
        processGeneration = processGeneration,
    ) { active ->
        if (active.pauseRequested.compareAndSet(false, true)) {
            SourceSeparationExecutionHostControlResult.Applied
        } else {
            SourceSeparationExecutionHostControlResult.AlreadyApplied
        }
    }

    override fun cancel(
        runId: String,
        processGeneration: Long,
    ): SourceSeparationExecutionHostControlResult = updateControl(
        runId = runId,
        processGeneration = processGeneration,
    ) { active ->
        if (active.cancelRequested.compareAndSet(false, true)) {
            SourceSeparationExecutionHostControlResult.Applied
        } else {
            SourceSeparationExecutionHostControlResult.AlreadyApplied
        }
    }

    override fun closeRun(
        runId: String,
        processGeneration: Long,
    ): SourceSeparationExecutionHostControlResult = synchronized(lock) {
        if (processGeneration != this.processGeneration) {
            return@synchronized SourceSeparationExecutionHostControlResult.StaleGeneration
        }
        val active = activeRun
            ?: return@synchronized SourceSeparationExecutionHostControlResult.NoActiveRun
        if (active.request.descriptor.runId != runId) {
            return@synchronized SourceSeparationExecutionHostControlResult.StaleRun
        }
        if (!active.lifecycle.isTerminal) {
            return@synchronized SourceSeparationExecutionHostControlResult.RunActive
        }
        active.lifecycle = SourceSeparationExecutionHostLifecycle.Closed
        activeRun = null
        SourceSeparationExecutionHostControlResult.Applied
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            activeRun?.cancelRequested?.set(true)
        }
    }

    private fun updateControl(
        runId: String,
        processGeneration: Long,
        update: (ActiveRun) -> SourceSeparationExecutionHostControlResult,
    ): SourceSeparationExecutionHostControlResult = synchronized(lock) {
        if (closed) return@synchronized SourceSeparationExecutionHostControlResult.HostClosed
        if (processGeneration != this.processGeneration) {
            return@synchronized SourceSeparationExecutionHostControlResult.StaleGeneration
        }
        val active = activeRun
            ?: return@synchronized SourceSeparationExecutionHostControlResult.NoActiveRun
        if (active.request.descriptor.runId != runId) {
            return@synchronized SourceSeparationExecutionHostControlResult.StaleRun
        }
        if (active.lifecycle.isTerminal) {
            return@synchronized SourceSeparationExecutionHostControlResult.Terminal
        }
        update(active)
    }

    private fun emit(
        active: ActiveRun,
        payload: SourceSeparationExecutionHostEventPayload,
        lifecycle: SourceSeparationExecutionHostLifecycle? = null,
        backend: String? = null,
        runtimeName: String? = null,
    ): SourceSeparationExecutionHostEvent {
        val event = synchronized(lock) {
            check(activeRun === active) { "Execution event targets a stale host run." }
            check(active.request.descriptor.processGeneration == processGeneration) {
                "Execution event targets a stale process generation."
            }
            active.sequence += 1L
            lifecycle?.let { active.lifecycle = it }
            if (backend != null) active.backend = backend
            if (runtimeName != null) active.runtimeName = runtimeName
            SourceSeparationExecutionHostEvent(
                runId = active.request.descriptor.runId,
                processGeneration = processGeneration,
                sequence = active.sequence,
                payload = payload,
            ).also { active.latestEvent = it }
        }
        active.request.onEvent(event)
        return event
    }

    private fun ActiveRun.executionRequest(): SourceSeparationModelAwareExecutionRequest {
        val original = request.executionRequest
        return original.copy(
            onProgress = { progress ->
                emit(
                    active = this,
                    payload = SourceSeparationExecutionHostEventPayload.Progress(
                        progress.toExecutionProgress(),
                    ),
                )
            },
            onPrepared = { preparation ->
                emit(
                    active = this,
                    payload = SourceSeparationExecutionHostEventPayload.Prepared(
                        preparation.toExecutionPreparation(original.workspace.entryDirectory),
                    ),
                    lifecycle = SourceSeparationExecutionHostLifecycle.Prepared,
                )
            },
            onSegmentStateChanged = { index, state ->
                emit(
                    active = this,
                    payload = SourceSeparationExecutionHostEventPayload.SegmentStateChanged(
                        segmentIndex = index,
                        state = state,
                    ),
                )
            },
            shouldPause = {
                pauseRequested.get() || original.shouldPause()
            },
            shouldCancel = {
                cancelRequested.get() || original.shouldCancel()
            },
        )
    }

    private class ActiveRun(
        val request: SourceSeparationExecutionHostRequest,
    ) {
        val pauseRequested = AtomicBoolean(false)
        val cancelRequested = AtomicBoolean(false)
        var lifecycle = SourceSeparationExecutionHostLifecycle.Starting
        var sequence = 0L
        var latestEvent: SourceSeparationExecutionHostEvent? = null
        var backend: String? = null
        var runtimeName: String? = null

        fun diagnostics(
            mode: SourceSeparationExecutionHostMode,
        ) = SourceSeparationExecutionHostDiagnostics(
            mode = mode,
            runId = request.descriptor.runId,
            processGeneration = request.descriptor.processGeneration,
            lifecycle = lifecycle,
            backend = backend,
            runtimeName = runtimeName,
            latestEventSequence = sequence,
        )
    }

    private companion object {
        const val DEFAULT_PROCESS_GENERATION = 1L
    }
}

private fun SourceSeparationExecutionHostRequest.requireExactDescriptor() {
    val execution = executionRequest
    val descriptor = descriptor
    val model = execution.model
    val profile = model.executionProfile
    require(descriptor.cacheIdentity == execution.workspace.identity) {
        "Execution descriptor does not match the admitted cache identity."
    }
    require(descriptor.contract == execution.workspace.contract) {
        "Execution descriptor does not match the admitted cache contract."
    }
    require(descriptor.source.sourceUri == execution.sourceUri &&
        descriptor.source.displayName == execution.displayName &&
        descriptor.source.expectedAudioFingerprint ==
        execution.workspace.identity.source.audioFingerprint
    ) {
        "Execution descriptor does not match the admitted source."
    }
    require(descriptor.model.modelId == model.contract.modelId &&
        descriptor.model.artifactFileName == model.artifact.file.name &&
        descriptor.model.artifactByteSize == model.artifact.byteSize &&
        descriptor.model.artifactSha256.equals(model.artifact.sha256, ignoreCase = true) &&
        descriptor.model.executionProfileId == profile.profileId &&
        descriptor.model.executionSessionIdentity == profile.sessionIdentity
    ) {
        "Execution descriptor does not match the resolved model."
    }
    require(descriptor.runtime.executionProfileId == profile.profileId &&
        descriptor.runtime.executionSessionIdentity == profile.sessionIdentity &&
        descriptor.runtime.cpuThreads == execution.runtimeSettings.cpuThreads &&
        descriptor.runtime.useXnnpack == execution.runtimeSettings.useXnnpack &&
        descriptor.runtime.windowDecodeEnabled == execution.windowDecodeEnabled
    ) {
        "Execution descriptor does not match the runtime request."
    }
    require(descriptor.resume == execution.resumeDescriptor()) {
        "Execution descriptor does not match the admitted resume state."
    }
}

internal fun SourceSeparationModelAwareExecutionRequest.toExecutionDescriptor(
    runId: String,
    processGeneration: Long,
    sourceDiagnostics: SourceSeparationCacheSourceDiagnostics,
    initialPlaybackPositionMs: Long?,
    initialPlaybackReadyWindowCount: Int,
): SourceSeparationExecutionDescriptor {
    val profile = model.executionProfile
    val contract = workspace.contract
    return SourceSeparationExecutionDescriptor(
        runId = runId,
        processGeneration = processGeneration,
        cacheKey = workspace.identity.cacheKey,
        cacheIdentity = workspace.identity,
        contract = contract,
        model = SourceSeparationExecutionModelIdentity(
            modelId = contract.modelId,
            artifactFileName = model.artifact.file.name,
            artifactByteSize = model.artifact.byteSize,
            artifactSha256 = model.artifact.sha256,
            contractId = contract.contractId,
            contractSchemaVersion = contract.contractSchemaVersion,
            contractFingerprint = contract.contractFingerprint,
            profileRevisionId = contract.profileRevisionId,
            executionProfileId = profile.profileId,
            executionSessionIdentity = profile.sessionIdentity,
            pipelineId = contract.pipelineId,
            pipelineVersion = contract.pipelineVersion,
        ),
        source = SourceSeparationExecutionSourceIdentity(
            sourceUri = sourceUri,
            displayName = displayName,
            expectedAudioFingerprint = workspace.identity.source.audioFingerprint,
            diagnostics = sourceDiagnostics,
        ),
        runtime = SourceSeparationExecutionRuntimeIdentity(
            executionProfileId = profile.profileId,
            executionSessionIdentity = profile.sessionIdentity,
            cpuThreads = runtimeSettings.cpuThreads,
            useXnnpack = runtimeSettings.useXnnpack,
            windowDecodeEnabled = windowDecodeEnabled,
            initialPlaybackPositionMs = initialPlaybackPositionMs,
            initialPlaybackReadyWindowCount = initialPlaybackReadyWindowCount,
        ),
        resume = resumeDescriptor(),
    )
}

internal fun SourceSeparationModelAwareExecutionRequest.resumeDescriptor():
    SourceSeparationExecutionResumeState? {
    val resume = workspace.resumeState ?: return null
    return SourceSeparationExecutionResumeState(
        vocalsPath = relativeEntryPath(workspace.entryDirectory, resume.vocalsFile),
        instrumentalPath = relativeEntryPath(workspace.entryDirectory, resume.instrumentalFile),
        timingPath = resume.timingFile?.let {
            relativeEntryPath(workspace.entryDirectory, it)
        },
        segmentPlan = resume.segmentPlan,
    )
}

internal fun MdxRangeProgress.toExecutionProgress() = SourceSeparationExecutionProgress(
    completedWindows = completedWindows,
    totalWindows = totalWindows,
    stage = stage,
    sourceDecodeDiagnostics = sourceDecodeDiagnostics?.toExecutionDiagnostics(),
    completedWindowElapsedMs = completedWindowElapsedMs,
    scheduler = scheduler?.toExecutionProgress(),
)

internal fun SourceSeparationExecutionProgress.toMdxRangeProgress() = MdxRangeProgress(
    completedWindows = completedWindows,
    totalWindows = totalWindows,
    stage = stage,
    sourceDecodeDiagnostics = sourceDecodeDiagnostics?.toMdxDiagnostics(),
    completedWindowElapsedMs = completedWindowElapsedMs,
    scheduler = scheduler?.toMdxProgress(),
)

private fun MdxSegmentSchedulerProgress.toExecutionProgress() =
    SourceSeparationExecutionSchedulerProgress(
        playbackSegmentIndex = playbackSegmentIndex,
        playbackSegmentState = playbackSegmentState,
        nextSegmentIndex = nextSegmentIndex,
        nextSegmentState = nextSegmentState,
        processingSegmentIndex = processingSegmentIndex,
        priority = priority,
        readySegments = readySegments,
        totalSegments = totalSegments,
        readyWindowCount = readyWindowCount,
        playbackReadyWindowReadyCount = playbackReadyWindowReadyCount,
        playbackReadyWindowPendingCount = playbackReadyWindowPendingCount,
    )

private fun SourceSeparationExecutionSchedulerProgress.toMdxProgress() =
    MdxSegmentSchedulerProgress(
        playbackSegmentIndex = playbackSegmentIndex,
        playbackSegmentState = playbackSegmentState,
        nextSegmentIndex = nextSegmentIndex,
        nextSegmentState = nextSegmentState,
        processingSegmentIndex = processingSegmentIndex,
        priority = priority,
        readySegments = readySegments,
        totalSegments = totalSegments,
        readyWindowCount = readyWindowCount,
        playbackReadyWindowReadyCount = playbackReadyWindowReadyCount,
        playbackReadyWindowPendingCount = playbackReadyWindowPendingCount,
    )

internal fun MdxSourceDecodeDiagnostics.toExecutionDiagnostics() =
    SourceSeparationExecutionSourceDecodeDiagnostics(
        mode = mode.name,
        profile = profile,
        mimeType = mimeType,
        sampleRate = sampleRate,
        channelCount = channelCount,
        sourceFrameCount = sourceFrameCount,
        outputFrameCount = outputFrameCount,
        fallbackReason = fallbackReason,
        experimental = experimental,
        calibration = calibration,
        encoderDelayFrames = encoderDelayFrames,
        encoderPaddingFrames = encoderPaddingFrames,
    )

internal fun SourceSeparationExecutionSourceDecodeDiagnostics.toMdxDiagnostics() =
    MdxSourceDecodeDiagnostics(
        mode = MdxSourceDecodeMode.valueOf(mode),
        profile = profile,
        mimeType = mimeType,
        sampleRate = sampleRate,
        channelCount = channelCount,
        sourceFrameCount = sourceFrameCount,
        outputFrameCount = outputFrameCount,
        fallbackReason = fallbackReason,
        experimental = experimental,
        calibration = calibration,
        encoderDelayFrames = encoderDelayFrames,
        encoderPaddingFrames = encoderPaddingFrames,
    )

private fun MdxRangePreparation.toExecutionPreparation(
    entryDirectory: File,
) = SourceSeparationExecutionPreparation(
    vocalsPath = relativeEntryPath(entryDirectory, vocalsFile),
    instrumentalPath = relativeEntryPath(entryDirectory, instrumentalFile),
    timingPath = relativeEntryPath(entryDirectory, timingFile),
    startMs = startMs,
    endMs = endMs,
    frames = frames,
    windowCount = windowCount,
    sourceAudioFingerprint = sourceAudioFingerprint,
    sourceFrameCount = sourceFrameCount,
    sourceSampleRate = sourceSampleRate,
    sourceChannelCount = sourceChannelCount,
    outputSampleRate = outputSampleRate,
    segmentPlan = segmentPlan,
)

internal fun SourceSeparationExecutionPreparation.toMdxRangePreparation(
    entryDirectory: File,
) = MdxRangePreparation(
    vocalsFile = resolveEntryPath(entryDirectory, vocalsPath),
    instrumentalFile = resolveEntryPath(entryDirectory, instrumentalPath),
    timingFile = resolveEntryPath(entryDirectory, timingPath),
    startMs = startMs,
    endMs = endMs,
    frames = frames,
    windowCount = windowCount,
    sourceAudioFingerprint = sourceAudioFingerprint,
    sourceFrameCount = sourceFrameCount,
    sourceSampleRate = sourceSampleRate,
    sourceChannelCount = sourceChannelCount,
    outputSampleRate = outputSampleRate,
    segmentPlan = segmentPlan,
)

internal fun MdxRangeSeparationResult.toExecutionCompletion(
    request: SourceSeparationModelAwareExecutionRequest,
) = SourceSeparationExecutionCompletion(
    vocalsPath = relativeEntryPath(request.workspace.entryDirectory, vocalsFile),
    instrumentalPath = relativeEntryPath(request.workspace.entryDirectory, instrumentalFile),
    timingPath = relativeEntryPath(request.workspace.entryDirectory, timingFile),
    startMs = startMs,
    endMs = endMs,
    frames = frames,
    windowCount = windowCount,
    elapsedMs = elapsedMs,
    sourceAudioFingerprint = sourceAudioFingerprint,
    sourceFrameCount = sourceFrameCount,
    sourceSampleRate = sourceSampleRate,
    sourceChannelCount = sourceChannelCount,
    outputSampleRate = outputSampleRate,
    segmentPlan = segmentPlan,
    runtimeSettings = SourceSeparationExecutionRuntimeSettings(
        cpuThreads = runtimeSettings.cpuThreads,
        useXnnpack = runtimeSettings.useXnnpack,
    ),
    runtimeDiagnostics = SourceSeparationExecutionRuntimeDiagnostics(
        runtimeName = runtimeDiagnostics.runtimeName,
        backend = runtimeDiagnostics.backend.name,
        cpuThreads = runtimeDiagnostics.cpuThreads,
        detail = runtimeDiagnostics.detail,
        fallbackStage = runtimeDiagnostics.fallbackStage,
        fallbackReason = runtimeDiagnostics.fallbackReason,
    ),
    sourceDecodeDiagnostics = sourceDecodeDiagnostics.toExecutionDiagnostics(),
    timingAudioDurationSeconds = timingReport.audioDurationSeconds,
    timingStageMs = timingReport.stageMs,
    modelVariant = modelVariant?.name,
)

internal fun SourceSeparationExecutionCompletion.toMdxRangeSeparationResult(
    request: SourceSeparationModelAwareExecutionRequest,
): MdxRangeSeparationResult {
    val runtimeSettings = MdxRuntimeSettings(
        cpuThreads = this.runtimeSettings.cpuThreads,
        useXnnpack = this.runtimeSettings.useXnnpack,
    )
    val runtimeDiagnostics = MdxRuntimeDiagnostics(
        runtimeName = this.runtimeDiagnostics.runtimeName,
        backend = MdxInferenceBackend.valueOf(this.runtimeDiagnostics.backend),
        cpuThreads = this.runtimeDiagnostics.cpuThreads,
        detail = this.runtimeDiagnostics.detail,
        fallbackStage = this.runtimeDiagnostics.fallbackStage,
        fallbackReason = this.runtimeDiagnostics.fallbackReason,
    )
    val sourceDiagnostics = sourceDecodeDiagnostics.toMdxDiagnostics()
    val profile = request.model.executionProfile
    return MdxRangeSeparationResult(
        vocalsFile = resolveEntryPath(request.workspace.entryDirectory, vocalsPath),
        instrumentalFile = resolveEntryPath(
            request.workspace.entryDirectory,
            instrumentalPath,
        ),
        timingFile = resolveEntryPath(request.workspace.entryDirectory, timingPath),
        startMs = startMs,
        endMs = endMs,
        frames = frames,
        windowCount = windowCount,
        elapsedMs = elapsedMs,
        sourceAudioFingerprint = sourceAudioFingerprint,
        sourceFrameCount = sourceFrameCount,
        sourceSampleRate = sourceSampleRate,
        sourceChannelCount = sourceChannelCount,
        outputSampleRate = outputSampleRate,
        segmentPlan = segmentPlan,
        timingReport = MdxRangeTimingReport(
            audioDurationSeconds = timingAudioDurationSeconds,
            windowCount = windowCount,
            totalMs = elapsedMs,
            runtimeSettings = runtimeSettings,
            runtimeDiagnostics = runtimeDiagnostics,
            executionProfile = profile,
            sourceDecodeDiagnostics = sourceDiagnostics,
            stageMs = timingStageMs,
        ),
        runtimeSettings = runtimeSettings,
        runtimeDiagnostics = runtimeDiagnostics,
        modelVariant = modelVariant?.let(MdxModelVariant::valueOf),
        executionProfile = profile,
        sourceDecodeDiagnostics = sourceDiagnostics,
    )
}

private fun SourceSeparationExecutionHostEvent.toDiagnostics(
    mode: SourceSeparationExecutionHostMode,
    lifecycle: SourceSeparationExecutionHostLifecycle,
    backend: String?,
    runtimeName: String?,
) = SourceSeparationExecutionHostDiagnostics(
    mode = mode,
    runId = runId,
    processGeneration = processGeneration,
    lifecycle = lifecycle,
    backend = backend,
    runtimeName = runtimeName,
    latestEventSequence = sequence,
)

private fun relativeEntryPath(
    entryDirectory: File,
    file: File,
): String {
    val root = entryDirectory.canonicalFile.toPath()
    val target = file.canonicalFile.toPath()
    require(target.startsWith(root)) { "Execution path escapes its cache entry." }
    val relative = root.relativize(target).joinToString("/") { it.toString() }
    SourceSeparationCacheRelativePath.requireValid(relative)
    return relative
}

private fun resolveEntryPath(
    entryDirectory: File,
    relativePath: String,
): File {
    SourceSeparationCacheRelativePath.requireValid(relativePath)
    val root = entryDirectory.canonicalFile
    val target = File(root, relativePath).canonicalFile
    require(target.toPath().startsWith(root.toPath())) {
        "Execution path escapes its cache entry."
    }
    return target
}

private val SourceSeparationExecutionHostLifecycle.isTerminal: Boolean
    get() = this == SourceSeparationExecutionHostLifecycle.Completed ||
        this == SourceSeparationExecutionHostLifecycle.Paused ||
        this == SourceSeparationExecutionHostLifecycle.Canceled ||
        this == SourceSeparationExecutionHostLifecycle.Failed ||
        this == SourceSeparationExecutionHostLifecycle.Closed
