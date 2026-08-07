package com.mardous.booming.separation.cache.v2

import com.mardous.booming.separation.SourceSeparationBackgroundPolicy
import com.mardous.booming.separation.SourceSeparationExecutionRunClass
import com.mardous.booming.separation.SourceSeparationPauseReason
import com.mardous.booming.separation.SourceSeparationGpuFallbackLatch
import com.mardous.booming.separation.cache.SourceSeparationSegmentPlan
import com.mardous.booming.separation.cache.SourceSeparationSegment
import com.mardous.booming.separation.cache.SourceSeparationSegmentState
import com.mardous.booming.separation.model.MdxRangePreparation
import com.mardous.booming.separation.model.MdxRangeResumeState
import com.mardous.booming.separation.model.MdxRangeSeparationResult
import com.mardous.booming.separation.model.contract.StemDescriptor
import com.mardous.booming.separation.model.contract.StemId
import com.mardous.booming.separation.model.contract.StemSemanticId
import com.mardous.booming.separation.model.contract.toStemSet
import java.io.File

class SourceSeparationCacheRunCoordinator(
    private val store: SourceSeparationCacheStore,
    private val repository: SourceSeparationModelAwareCacheRepository,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) {
    fun inspectManifest(
        identity: SourceSeparationCacheIdentity,
    ): SourceSeparationCacheManifest? = store.readManifest(identity.cacheKey)
        ?.takeIf { it.identity == identity }

    fun inspectCompleted(
        identity: SourceSeparationCacheIdentity,
    ): SourceSeparationCacheManifest? {
        val manifest = store.readManifest(identity.cacheKey)
            ?.takeIf { it.identity == identity }
            ?.takeIf { it.state == SourceSeparationCacheManifestState.Completed }
            ?: return null
        return manifest.takeIf {
            store.validateCompletedEntry(it, verifyHashes = false) ==
                SourceSeparationCacheValidationResult.Valid &&
                store.readRunJournal(identity.cacheKey)?.lifecycle ==
                SourceSeparationCacheRunJournalLifecycle.Completed
        }
    }

    fun inspectAdmittedRuntimePolicy(
        identity: SourceSeparationCacheIdentity,
    ): SourceSeparationCacheAdmittedRuntimePolicy? = store.readRunJournal(identity.cacheKey)
        ?.takeIf { journal ->
            journal.request.identity == identity &&
                (journal.lifecycle == SourceSeparationCacheRunJournalLifecycle.Running ||
                    journal.lifecycle == SourceSeparationCacheRunJournalLifecycle.Paused)
        }
        ?.request
        ?.let { request ->
            SourceSeparationCacheAdmittedRuntimePolicy(
                tryGpu = request.tryGpu,
                gpuRuntimeIdentity = request.gpuRuntimeIdentity,
                gpuFallbackLatch = request.gpuFallbackLatch,
            )
        }

    fun previewWorkspace(
        identity: SourceSeparationCacheIdentity,
    ): SourceSeparationCacheWorkspacePreview {
        val entry = store.entryDirectory(identity.cacheKey)
        return SourceSeparationCacheWorkspacePreview(
            entryDirectory = entry,
            workDirectory = store.resolveEntryPath(identity.cacheKey, WORK_DIRECTORY),
            segmentsDirectory = store.resolveEntryPath(identity.cacheKey, SEGMENTS_DIRECTORY),
        )
    }

    fun begin(
        request: SourceSeparationCacheRunRequest,
    ): SourceSeparationCacheRunStart {
        require(request.contract.identity(
            source = request.identity.source,
            renderProfileId = request.identity.renderProfileId,
        ) == request.identity) {
            "Cache run identity does not match its contract snapshot."
        }
        val lease = repository.tryAcquireRunWrite(
            request.identity,
            SourceSeparationCacheLockOwner(
                purpose = SourceSeparationCacheLockPurpose.Run,
                runId = request.runId,
                processGeneration = request.processGeneration,
                pid = request.ownerPid,
            ),
        )
            ?: return SourceSeparationCacheRunStart.Busy
        return try {
            val entryDirectory = store.entryDirectory(request.identity.cacheKey)
            lease.bindEntryDirectory(entryDirectory)
            val existing = store.readManifest(request.identity.cacheKey)
            val existingJournal = store.readRunJournal(request.identity.cacheKey)
                ?.takeIf { journal ->
                    journal.request.identity == request.identity &&
                        journal.request.contract == request.contract
                }
            if (existing?.state == SourceSeparationCacheManifestState.Completed &&
                store.validateCompletedEntry(existing, verifyHashes = false) ==
                SourceSeparationCacheValidationResult.Valid
            ) {
                val completedJournal = existingJournal?.let { journal ->
                    if (journal.lifecycle == SourceSeparationCacheRunJournalLifecycle.Completed) {
                        journal
                    } else {
                        val recovered = if (journal.lifecycle ==
                            SourceSeparationCacheRunJournalLifecycle.Running
                        ) {
                            journal.append(
                                type = SourceSeparationCacheRunTransitionType.PreviousOwnerDied,
                                nowEpochMs = nowEpochMs(),
                            )
                        } else {
                            journal
                        }
                        recovered.append(
                            type = SourceSeparationCacheRunTransitionType.Completed,
                            nowEpochMs = nowEpochMs(),
                            lifecycle = SourceSeparationCacheRunJournalLifecycle.Completed,
                        )
                    }
                } ?: SourceSeparationCacheRunJournal.admitted(
                    request.toJournalRequest(nowEpochMs()),
                ).append(
                    type = SourceSeparationCacheRunTransitionType.Completed,
                    nowEpochMs = nowEpochMs(),
                    lifecycle = SourceSeparationCacheRunJournalLifecycle.Completed,
                )
                store.writeRunJournal(completedJournal)
                lease.close()
                return SourceSeparationCacheRunStart.AlreadyCompleted(existing)
            }
            val workDirectory = store.resolveEntryPath(request.identity.cacheKey, WORK_DIRECTORY)
            val completedDirectory = store.resolveEntryPath(
                request.identity.cacheKey,
                COMPLETED_DIRECTORY,
            )
            val segmentsDirectory = store.resolveEntryPath(
                request.identity.cacheKey,
                SEGMENTS_DIRECTORY,
            )
            val committedSegments = existingJournal
                ?.committedSegments
                ?.filter { segment ->
                    segment.hasValidArtifactSet(store, request.identity.cacheKey)
                }
                .orEmpty()
            val resumed = existing?.toResumeState(store)
            val manifest = if (resumed != null) {
                val resumedAt = nowEpochMs()
                val resumedPlan = existing.segmentPlan!!.withValidatedReadySegments(
                    store = store,
                    cacheKey = existing.cacheKey,
                    committedSegments = committedSegments,
                )
                existing.copy(
                    state = SourceSeparationCacheManifestState.Partial,
                    segmentPlan = resumedPlan,
                    error = null,
                    updatedAtEpochMs = resumedAt,
                    lastAccessedAtEpochMs = maxOf(existing.lastAccessedAtEpochMs, resumedAt),
                ).also(store::writeManifest)
            } else {
                listOf(WORK_DIRECTORY, SEGMENTS_DIRECTORY, COMPLETED_DIRECTORY).forEach { path ->
                    store.deleteRelativePath(request.identity.cacheKey, path)
                }
                workDirectory.mkdirs()
                completedDirectory.mkdirs()
                segmentsDirectory.mkdirs()
                val now = nowEpochMs()
                SourceSeparationCacheManifest(
                    cacheKey = request.identity.cacheKey,
                    identity = request.identity,
                    contract = request.contract,
                    state = SourceSeparationCacheManifestState.Partial,
                    song = request.song,
                    sourceDiagnostics = request.sourceDiagnostics,
                    createdAtEpochMs = existing?.createdAtEpochMs ?: now,
                    updatedAtEpochMs = now,
                    lastAccessedAtEpochMs = existing?.lastAccessedAtEpochMs ?: now,
                ).also(store::writeManifest)
            }
            cleanUncommittedSegments(
                cacheKey = request.identity.cacheKey,
                plan = manifest.segmentPlan,
                committedSegments = committedSegments,
            )
            val journalRequest = request.toJournalRequest(nowEpochMs())
            val journal = existingJournal?.let { previous ->
                SourceSeparationCacheRunJournal.resume(
                    previous = previous,
                    request = journalRequest,
                    committedSegments = committedSegments,
                )
            } ?: SourceSeparationCacheRunJournal.admitted(journalRequest)
            store.writeRunJournal(journal)
            SourceSeparationCacheRunStart.Ready(
                SourceSeparationModelAwareCacheRun(
                    identity = request.identity,
                    contract = request.contract,
                    entryDirectory = entryDirectory,
                    workDirectory = workDirectory,
                    completedDirectory = completedDirectory,
                    segmentsDirectory = segmentsDirectory,
                    resumeState = manifest.toResumeState(store),
                    runId = request.runId,
                    processGeneration = request.processGeneration,
                    lease = lease,
                )
            )
        } catch (error: Throwable) {
            lease.close()
            throw error
        }
    }

    fun updatePreparation(
        run: SourceSeparationModelAwareCacheRun,
        preparation: MdxRangePreparation,
    ): SourceSeparationCacheManifest {
        run.requireOpen()
        require(preparation.sourceAudioFingerprint == run.identity.source.audioFingerprint) {
            "Prepared source fingerprint does not match the cache identity."
        }
        val current = requireNotNull(store.readManifest(run.identity.cacheKey)) {
            "Cache run manifest disappeared during preparation."
        }
        val output = SourceSeparationCacheOutput(
            stems = run.mdxStemFiles(
                vocalsFile = preparation.vocalsFile,
                instrumentalFile = preparation.instrumentalFile,
            ).map { (descriptor, file) ->
                run.renderedStem(
                    descriptor = descriptor,
                    file = file,
                    frameCount = preparation.frames,
                    sampleRate = preparation.outputSampleRate,
                )
            },
            timingPath = store.relativeEntryPath(run.identity.cacheKey, preparation.timingFile),
            outputSampleRate = preparation.outputSampleRate,
            outputFrameCount = preparation.frames,
            windowCount = preparation.windowCount,
            elapsedMs = 0L,
            totalBytes = store.entrySize(run.identity.cacheKey),
        )
        updateJournal(run) { journal, now ->
            preparation.segmentPlan.segments
                .filter { segment ->
                    segment.state.isPlaybackReady && journal.committedSegments.none {
                        it.segmentIndex == segment.index
                    }
                }
                .fold(
                    journal.append(
                        type = SourceSeparationCacheRunTransitionType.Prepared,
                        nowEpochMs = now,
                    )
                ) { currentJournal, segment ->
                    currentJournal.append(
                        type = SourceSeparationCacheRunTransitionType.SegmentReady,
                        nowEpochMs = now,
                        segmentIndex = segment.index,
                        committedSegment = segment.captureCommittedArtifactSet(
                            store,
                            run.identity.cacheKey,
                        ),
                    )
                }
        }
        return current.copy(
            state = SourceSeparationCacheManifestState.Partial,
            output = output,
            segmentPlan = preparation.segmentPlan,
            error = null,
            updatedAtEpochMs = nowEpochMs(),
        ).also(store::writeManifest)
    }

    fun latchGpuFallback(
        run: SourceSeparationModelAwareCacheRun,
        latch: SourceSeparationGpuFallbackLatch,
    ): SourceSeparationCacheRunJournal = updateJournal(run) { journal, now ->
        journal.latchGpuFallback(latch, now)
    }

    fun observerConnected(
        run: SourceSeparationModelAwareCacheRun,
        observerId: String,
        observerProcessName: String,
    ): SourceSeparationCacheRunJournal = updateJournal(run) { journal, now ->
        journal.observerConnected(observerId, observerProcessName, now)
    }

    fun observerDisconnected(
        run: SourceSeparationModelAwareCacheRun,
        observerId: String,
        observerProcessName: String,
        reason: String,
    ): SourceSeparationCacheRunJournal = updateJournal(run) { journal, now ->
        journal.observerDisconnected(observerId, observerProcessName, reason, now)
    }

    fun updateSegmentState(
        run: SourceSeparationModelAwareCacheRun,
        segmentIndex: Int,
        state: SourceSeparationSegmentState,
    ): SourceSeparationCacheManifest? {
        run.requireOpen()
        val current = store.readManifest(run.identity.cacheKey) ?: return null
        val plan = current.segmentPlan ?: return current
        val segment = plan.segments.singleOrNull { it.index == segmentIndex }
            ?: return current
        updateJournal(run) { journal, now ->
            when (state) {
                SourceSeparationSegmentState.Running -> journal.append(
                    type = SourceSeparationCacheRunTransitionType.SegmentRunning,
                    nowEpochMs = now,
                    segmentIndex = segmentIndex,
                )

                SourceSeparationSegmentState.Ready -> {
                    val committed = segment.captureCommittedArtifactSet(
                        store,
                        run.identity.cacheKey,
                    )
                    journal.append(
                        type = SourceSeparationCacheRunTransitionType.SegmentReady,
                        nowEpochMs = now,
                        segmentIndex = segmentIndex,
                        committedSegment = committed,
                    )
                }

                else -> journal.append(
                    type = SourceSeparationCacheRunTransitionType.SegmentInvalidated,
                    nowEpochMs = now,
                    segmentIndex = segmentIndex,
                    removeCommittedSegmentIndex = segmentIndex,
                )
            }
        }
        val updated = current.copy(
            segmentPlan = plan.withSegmentState(segmentIndex, state),
            updatedAtEpochMs = nowEpochMs(),
        ).also(store::writeManifest)
        if (state != SourceSeparationSegmentState.Running && !state.isPlaybackReady) {
            segment.deleteArtifactSet(store, run.identity.cacheKey)
        }
        return updated
    }

    fun complete(
        run: SourceSeparationModelAwareCacheRun,
        result: MdxRangeSeparationResult,
    ): SourceSeparationCacheManifest {
        run.requireOpen()
        require(result.sourceAudioFingerprint == run.identity.source.audioFingerprint) {
            "Completed source fingerprint does not match the cache identity."
        }
        val completedStems = run.mdxStemFiles(
            vocalsFile = result.vocalsFile,
            instrumentalFile = result.instrumentalFile,
        ).map { (descriptor, file) ->
            val path = completedStemPath(descriptor.order)
            val integrity = store.copyIntoEntryAtomically(
                cacheKey = run.identity.cacheKey,
                source = file,
                relativePath = path,
            )
            completedStem(
                descriptor = descriptor,
                path = path,
                integrity = integrity,
                result = result,
            )
        }
        store.copyIntoEntryAtomically(
            cacheKey = run.identity.cacheKey,
            source = result.timingFile,
            relativePath = COMPLETED_TIMING_PATH,
        )
        val current = requireNotNull(store.readManifest(run.identity.cacheKey)) {
            "Cache run manifest disappeared before completion."
        }
        val output = SourceSeparationCacheOutput(
            stems = completedStems,
            timingPath = COMPLETED_TIMING_PATH,
            outputSampleRate = result.outputSampleRate,
            outputFrameCount = result.frames,
            windowCount = result.windowCount,
            elapsedMs = result.elapsedMs,
            totalBytes = store.entrySize(run.identity.cacheKey),
        )
        val completedAt = nowEpochMs()
        var completed = current.copy(
            state = SourceSeparationCacheManifestState.Completed,
            output = output,
            segmentPlan = result.segmentPlan,
            cleanup = SourceSeparationCacheCleanup(
                paths = listOf(WORK_DIRECTORY, SEGMENTS_DIRECTORY),
            ),
            error = null,
            runtimeRecords = current.runtimeRecords + SourceSeparationCacheRuntimeRecord(
                backend = result.runtimeDiagnostics.backend.name,
                runtimeProfileId = result.executionProfile.profileId,
                precision = "fp32",
                elapsedMs = result.elapsedMs,
                fallbackStage = result.runtimeDiagnostics.fallbackStage,
                fallbackReason = result.runtimeDiagnostics.fallbackReason,
                sourceDecodeMode = result.sourceDecodeDiagnostics.mode.name,
                sourceDecodeProfile = result.sourceDecodeDiagnostics.profile,
                sourceDecodeMimeType = result.sourceDecodeDiagnostics.mimeType,
                sourceDecodeFallbackReason = result.sourceDecodeDiagnostics.fallbackReason,
                sourceDecodeSampleRate = result.sourceDecodeDiagnostics.sampleRate,
                sourceDecodeChannelCount = result.sourceDecodeDiagnostics.channelCount,
                sourceDecodeSourceFrameCount = result.sourceDecodeDiagnostics.sourceFrameCount,
                sourceDecodeOutputFrameCount = result.sourceDecodeDiagnostics.outputFrameCount,
                sourceDecodeEncoderDelayFrames =
                    result.sourceDecodeDiagnostics.encoderDelayFrames,
                sourceDecodeEncoderPaddingFrames =
                    result.sourceDecodeDiagnostics.encoderPaddingFrames,
            ),
            updatedAtEpochMs = completedAt,
            lastAccessedAtEpochMs = maxOf(current.lastAccessedAtEpochMs, completedAt),
        )
        store.writeManifest(completed)
        completed = completed.copy(
            output = completed.output?.copy(totalBytes = store.entrySize(run.identity.cacheKey)),
        )
        store.writeManifest(completed)
        SourceSeparationCacheFaultInjection.reach(
            SourceSeparationCacheFaultStage.TerminalCommit,
            store.root().directory,
        )
        updateJournal(run) { journal, now ->
            journal.append(
                type = SourceSeparationCacheRunTransitionType.Completed,
                nowEpochMs = now,
                lifecycle = SourceSeparationCacheRunJournalLifecycle.Completed,
            )
        }
        run.close()
        return completed
    }

    fun pause(
        run: SourceSeparationModelAwareCacheRun,
        reason: SourceSeparationPauseReason = SourceSeparationPauseReason.Standard,
    ): SourceSeparationCacheManifest? {
        return finishIncomplete(
            run = run,
            transition = when (reason) {
                SourceSeparationPauseReason.Standard ->
                    SourceSeparationCacheRunTransitionType.Paused
                SourceSeparationPauseReason.ActiveModelSuperseded ->
                    SourceSeparationCacheRunTransitionType.ActiveModelSuperseded
            },
            error = null,
        )
    }

    fun cancel(
        run: SourceSeparationModelAwareCacheRun,
        error: Throwable,
    ): SourceSeparationCacheManifest? {
        return finishIncomplete(
            run = run,
            transition = SourceSeparationCacheRunTransitionType.UserCanceled,
            error = error,
        )
    }

    fun fail(
        run: SourceSeparationModelAwareCacheRun,
        error: Throwable,
    ): SourceSeparationCacheManifest? {
        return finishIncomplete(
            run = run,
            transition = SourceSeparationCacheRunTransitionType.Failed,
            error = error,
        )
    }

    fun cleanCompletedTemporaryFiles(cacheKey: String): Boolean {
        val observedManifest = store.readManifest(cacheKey)
            ?.takeIf { it.state == SourceSeparationCacheManifestState.Completed }
            ?: return false
        val observedCleanup = observedManifest.cleanup ?: return false
        var completedCleanup = false
        observedCleanup.paths.forEach { path ->
            val lease = repository.tryAcquireCleanup(cacheKey, setOf(path))
                ?: return@forEach
            lease.use {
                it.bindEntryDirectory(store.entryDirectory(cacheKey))
                val manifest = store.readManifest(cacheKey)
                    ?.takeIf { current ->
                        current.state == SourceSeparationCacheManifestState.Completed &&
                            current.cleanup?.paths?.contains(path) == true
                    }
                    ?: return@use
                if (!store.deleteRelativePath(cacheKey, path)) return@use
                val remainingPaths = requireNotNull(manifest.cleanup).paths
                    .filterNot { it == path }
                store.writeManifest(
                    manifest.copy(
                        cleanup = if (remainingPaths.isEmpty()) {
                            null
                        } else {
                            SourceSeparationCacheCleanup(remainingPaths)
                        },
                        output = manifest.output?.copy(totalBytes = store.entrySize(cacheKey)),
                        updatedAtEpochMs = nowEpochMs(),
                    )
                )
                if (remainingPaths.isEmpty()) completedCleanup = true
            }
        }
        return completedCleanup
    }

    private fun finishIncomplete(
        run: SourceSeparationModelAwareCacheRun,
        transition: SourceSeparationCacheRunTransitionType,
        error: Throwable?,
    ): SourceSeparationCacheManifest? {
        return try {
            run.requireOpen()
            val current = store.readManifest(run.identity.cacheKey) ?: return null
            val discardedSegments = current.segmentPlan?.segments.orEmpty()
                .filter { it.state == SourceSeparationSegmentState.Running }
            val resetPlan = current.segmentPlan?.copy(
                segments = current.segmentPlan.segments.map { segment ->
                    if (segment.state == SourceSeparationSegmentState.Running) {
                        segment.copy(state = SourceSeparationSegmentState.Queued)
                    } else {
                        segment
                    }
                }
            )
            val updated = current.copy(
                state = SourceSeparationCacheManifestState.Partial,
                segmentPlan = resetPlan,
                error = error?.let {
                    SourceSeparationCacheError(
                        type = it::class.java.name,
                        message = it.message,
                    )
                },
                updatedAtEpochMs = nowEpochMs(),
            ).also(store::writeManifest)
            updateJournal(run) { journal, now ->
                val lifecycle = when (transition) {
                    SourceSeparationCacheRunTransitionType.Paused,
                    SourceSeparationCacheRunTransitionType.ActiveModelSuperseded,
                    -> SourceSeparationCacheRunJournalLifecycle.Paused
                    SourceSeparationCacheRunTransitionType.UserCanceled ->
                        SourceSeparationCacheRunJournalLifecycle.Canceled
                    SourceSeparationCacheRunTransitionType.Failed ->
                        SourceSeparationCacheRunJournalLifecycle.Failed
                    else -> error("Invalid incomplete cache transition: $transition")
                }
                journal.append(
                    type = transition,
                    nowEpochMs = now,
                    error = error?.toCacheError(),
                    lifecycle = lifecycle,
                )
            }
            deleteSegmentArtifactSets(run.identity.cacheKey, discardedSegments)
            updated
        } finally {
            run.close()
        }
    }

    private fun SourceSeparationCacheManifest.toResumeState(
        store: SourceSeparationCacheStore,
    ): MdxRangeResumeState? {
        if (state == SourceSeparationCacheManifestState.Completed) return null
        val plan = segmentPlan ?: return null
        val stems = output?.stems.orEmpty().associateBy { it.semanticId }
        val vocals = stems[StemSemanticId.Vocals] ?: return null
        val instrumental = stems[StemSemanticId.Instrumental] ?: return null
        val vocalsFile = store.resolveEntryPath(cacheKey, vocals.wavPath)
        val instrumentalFile = store.resolveEntryPath(cacheKey, instrumental.wavPath)
        if (!vocalsFile.isFile || !instrumentalFile.isFile) return null
        return MdxRangeResumeState(
            vocalsFile = vocalsFile,
            instrumentalFile = instrumentalFile,
            timingFile = output?.timingPath?.let { store.resolveEntryPath(cacheKey, it) },
            segmentPlan = plan,
        )
    }

    private fun SourceSeparationSegmentPlan.withValidatedReadySegments(
        store: SourceSeparationCacheStore,
        cacheKey: String,
        committedSegments: List<SourceSeparationCacheCommittedSegment>,
    ): SourceSeparationSegmentPlan {
        val committedByIndex = committedSegments.associateBy { it.segmentIndex }
        return copy(
            segments = segments.map { segment ->
                val committed = committedByIndex[segment.index]
                val canPreserve = segment.state.isPlaybackReady && committed != null &&
                    committed.matches(segment) &&
                    committed.hasValidArtifactSet(store, cacheKey)
                segment.copy(
                    state = if (canPreserve) {
                        segment.state
                    } else {
                        SourceSeparationSegmentState.Queued
                    }
                )
            }
        )
    }

    private fun updateJournal(
        run: SourceSeparationModelAwareCacheRun,
        update: (
            SourceSeparationCacheRunJournal,
            Long,
        ) -> SourceSeparationCacheRunJournal,
    ): SourceSeparationCacheRunJournal {
        run.requireOpen()
        val current = requireNotNull(store.readRunJournal(run.identity.cacheKey)) {
            "Cache run journal disappeared during an active run."
        }
        require(current.request.runId == run.runId &&
            current.request.processGeneration == run.processGeneration
        ) { "Cache run journal belongs to a stale writer." }
        return update(current, nowEpochMs()).also(store::writeRunJournal)
    }

    private fun cleanUncommittedSegments(
        cacheKey: String,
        plan: SourceSeparationSegmentPlan?,
        committedSegments: List<SourceSeparationCacheCommittedSegment>,
    ) {
        val committedIndexes = committedSegments.map { it.segmentIndex }.toSet()
        deleteSegmentArtifactSets(
            cacheKey = cacheKey,
            segments = plan?.segments.orEmpty().filterNot { it.index in committedIndexes },
        )
        store.resolveEntryPath(cacheKey, SEGMENTS_DIRECTORY)
            .walkTopDown()
            .filter { file -> file.isFile && file.name.endsWith(".tmp") }
            .forEach(File::delete)
    }

    private fun deleteSegmentArtifactSets(
        cacheKey: String,
        segments: List<SourceSeparationSegment>,
    ) {
        segments.forEach { segment ->
            segment.deleteArtifactSet(store, cacheKey)
            segment.stems.asSequence()
                .map { stem -> store.resolveEntryPath(cacheKey, stem.path).parentFile }
                .filterNotNull()
                .distinct()
                .filter { directory -> directory.listFiles()?.isEmpty() == true }
                .forEach(File::delete)
        }
    }

    private fun SourceSeparationCacheRunRequest.toJournalRequest(
        admittedAtEpochMs: Long,
    ) = SourceSeparationCacheRunJournalRequest(
        cacheKey = identity.cacheKey,
        identity = identity,
        contract = contract,
        song = song,
        sourceDiagnostics = sourceDiagnostics,
        runId = runId,
        processGeneration = processGeneration,
        ownerPid = ownerPid,
        runClass = runClass,
        backgroundPolicy = backgroundPolicy,
        tryGpu = tryGpu,
        gpuRuntimeIdentity = gpuRuntimeIdentity,
        gpuFallbackLatch = gpuFallbackLatch,
        admittedAtEpochMs = admittedAtEpochMs,
    )

    private fun Throwable.toCacheError() = SourceSeparationCacheError(
        type = this::class.java.name,
        message = message,
    )

    private fun SourceSeparationModelAwareCacheRun.renderedStem(
        descriptor: StemDescriptor,
        file: File,
        frameCount: Int,
        sampleRate: Int,
    ): SourceSeparationCacheRenderedStem {
        return SourceSeparationCacheRenderedStem(
            stemId = descriptor.stemId,
            semanticId = descriptor.semanticId,
            canonicalLabel = descriptor.canonicalLabel,
            order = descriptor.order,
            production = descriptor.production,
            wavPath = store.relativeEntryPath(identity.cacheKey, file),
            channelCount = 2,
            sampleRate = sampleRate,
            frameCount = frameCount,
        )
    }

    private fun completedStem(
        descriptor: StemDescriptor,
        path: String,
        integrity: SourceSeparationCacheFileIntegrity,
        result: MdxRangeSeparationResult,
    ): SourceSeparationCacheRenderedStem {
        return SourceSeparationCacheRenderedStem(
            stemId = descriptor.stemId,
            semanticId = descriptor.semanticId,
            canonicalLabel = descriptor.canonicalLabel,
            order = descriptor.order,
            production = descriptor.production,
            wavPath = path,
            channelCount = 2,
            sampleRate = result.outputSampleRate,
            frameCount = result.frames,
            wavIntegrity = integrity,
        )
    }

    private fun SourceSeparationModelAwareCacheRun.mdxStemFiles(
        vocalsFile: File,
        instrumentalFile: File,
    ): List<Pair<StemDescriptor, File>> {
        val files = mapOf(
            StemSemanticId.Vocals to vocalsFile,
            StemSemanticId.Instrumental to instrumentalFile,
        )
        return contract.expectedStemSet().stems.map { descriptor ->
            descriptor to requireNotNull(files[descriptor.semanticId]) {
                "The MDX adapter cannot render stem ${descriptor.stemId}."
            }
        }
    }

    private fun completedStemPath(order: Int): String =
        "$COMPLETED_DIRECTORY/stem-%02d.wav".format(order)

    companion object {
        const val WORK_DIRECTORY = "work"
        const val COMPLETED_DIRECTORY = "completed"
        const val SEGMENTS_DIRECTORY = "segments"
        const val COMPLETED_TIMING_PATH = "completed/timing.txt"
    }
}

data class SourceSeparationCacheRunRequest(
    val identity: SourceSeparationCacheIdentity,
    val contract: SourceSeparationCacheContractSnapshot,
    val song: SourceSeparationCacheSongLocator,
    val sourceDiagnostics: SourceSeparationCacheSourceDiagnostics,
    val runClass: SourceSeparationExecutionRunClass,
    val backgroundPolicy: SourceSeparationBackgroundPolicy,
    val tryGpu: Boolean,
    val gpuRuntimeIdentity: SourceSeparationAdmittedGpuRuntimeIdentity?,
    val gpuFallbackLatch: SourceSeparationGpuFallbackLatch?,
    val runId: String = java.util.UUID.randomUUID().toString(),
    val processGeneration: Long = 1L,
    val ownerPid: Int? = null,
) {
    init {
        require(runId.isNotBlank()) { "Cache run ID is empty." }
        require(processGeneration > 0L) { "Cache run process generation is invalid." }
        require(ownerPid == null || ownerPid > 0) { "Cache run owner PID is invalid." }
        require(backgroundPolicy == runClass.backgroundPolicy) {
            "Cache run background policy does not match its run class."
        }
        require(tryGpu == (gpuRuntimeIdentity != null)) {
            "Cache run GPU preference and runtime identity disagree."
        }
        require(tryGpu || gpuFallbackLatch == null) {
            "A CPU-only cache run cannot carry a GPU fallback latch."
        }
    }
}

data class SourceSeparationCacheAdmittedRuntimePolicy(
    val tryGpu: Boolean,
    val gpuRuntimeIdentity: SourceSeparationAdmittedGpuRuntimeIdentity?,
    val gpuFallbackLatch: SourceSeparationGpuFallbackLatch? = null,
) {
    init {
        require(tryGpu == (gpuRuntimeIdentity != null)) {
            "Admitted GPU preference and runtime identity disagree."
        }
        require(tryGpu || gpuFallbackLatch == null) {
            "A CPU-only admitted policy cannot carry a GPU fallback latch."
        }
    }
}

data class SourceSeparationCacheWorkspacePreview(
    val entryDirectory: File,
    val workDirectory: File,
    val segmentsDirectory: File,
)

sealed class SourceSeparationCacheRunStart {
    data class Ready(val run: SourceSeparationModelAwareCacheRun) : SourceSeparationCacheRunStart()
    data class AlreadyCompleted(
        val manifest: SourceSeparationCacheManifest,
    ) : SourceSeparationCacheRunStart()
    data object Busy : SourceSeparationCacheRunStart()
}

class SourceSeparationModelAwareCacheRun internal constructor(
    val identity: SourceSeparationCacheIdentity,
    val contract: SourceSeparationCacheContractSnapshot,
    val entryDirectory: File,
    val workDirectory: File,
    val completedDirectory: File,
    val segmentsDirectory: File,
    val resumeState: MdxRangeResumeState?,
    val runId: String,
    val processGeneration: Long,
    private val lease: SourceSeparationCacheEntryLease,
) : AutoCloseable {
    private var closed = false

    internal fun requireOpen() {
        check(!closed) { "Cache run is closed." }
        lease.requireCacheAvailable()
    }

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
        }
        lease.close()
    }
}
