package com.mardous.booming.separation.cache.v2

import com.mardous.booming.separation.cache.SourceSeparationSegmentPlan
import com.mardous.booming.separation.cache.SourceSeparationSegmentState
import com.mardous.booming.separation.model.MdxRangePreparation
import com.mardous.booming.separation.model.MdxRangeResumeState
import com.mardous.booming.separation.model.MdxRangeSeparationResult
import com.mardous.booming.separation.model.contract.ContractStemSemantic
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
                ?.filter { segment -> segment.isValid(store, request.identity.cacheKey) }
                .orEmpty()
            val resumed = existing?.toResumeState(store)
            val manifest = if (resumed != null) {
                val resumedPlan = existing.segmentPlan!!.withValidatedReadySegments(
                    store = store,
                    cacheKey = existing.cacheKey,
                    committedSegments = committedSegments,
                )
                existing.copy(
                    state = SourceSeparationCacheManifestState.Running,
                    segmentPlan = resumedPlan,
                    error = null,
                    updatedAtEpochMs = nowEpochMs(),
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
                    state = SourceSeparationCacheManifestState.Running,
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
            stems = listOf(
                run.renderedStem(
                    semantic = ContractStemSemantic.Vocals,
                    file = preparation.vocalsFile,
                    frameCount = preparation.frames,
                    sampleRate = preparation.outputSampleRate,
                ),
                run.renderedStem(
                    semantic = ContractStemSemantic.Instrumental,
                    file = preparation.instrumentalFile,
                    frameCount = preparation.frames,
                    sampleRate = preparation.outputSampleRate,
                ),
            ),
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
                        committedSegment = committedSegment(run, segment),
                    )
                }
        }
        return current.copy(
            state = SourceSeparationCacheManifestState.Running,
            output = output,
            segmentPlan = preparation.segmentPlan,
            error = null,
            updatedAtEpochMs = nowEpochMs(),
        ).also(store::writeManifest)
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
                    val committed = committedSegment(run, segment)
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
        return current.copy(
            segmentPlan = plan.withSegmentState(segmentIndex, state),
            updatedAtEpochMs = nowEpochMs(),
        ).also(store::writeManifest)
    }

    fun complete(
        run: SourceSeparationModelAwareCacheRun,
        result: MdxRangeSeparationResult,
    ): SourceSeparationCacheManifest {
        run.requireOpen()
        require(result.sourceAudioFingerprint == run.identity.source.audioFingerprint) {
            "Completed source fingerprint does not match the cache identity."
        }
        val vocalsIntegrity = store.copyIntoEntryAtomically(
            cacheKey = run.identity.cacheKey,
            source = result.vocalsFile,
            relativePath = COMPLETED_VOCALS_PATH,
        )
        val instrumentalIntegrity = store.copyIntoEntryAtomically(
            cacheKey = run.identity.cacheKey,
            source = result.instrumentalFile,
            relativePath = COMPLETED_INSTRUMENTAL_PATH,
        )
        store.copyIntoEntryAtomically(
            cacheKey = run.identity.cacheKey,
            source = result.timingFile,
            relativePath = COMPLETED_TIMING_PATH,
        )
        val current = requireNotNull(store.readManifest(run.identity.cacheKey)) {
            "Cache run manifest disappeared before completion."
        }
        val output = SourceSeparationCacheOutput(
            stems = listOf(
                completedStem(
                    run = run,
                    semantic = ContractStemSemantic.Vocals,
                    path = COMPLETED_VOCALS_PATH,
                    integrity = vocalsIntegrity,
                    result = result,
                ),
                completedStem(
                    run = run,
                    semantic = ContractStemSemantic.Instrumental,
                    path = COMPLETED_INSTRUMENTAL_PATH,
                    integrity = instrumentalIntegrity,
                    result = result,
                ),
            ),
            timingPath = COMPLETED_TIMING_PATH,
            outputSampleRate = result.outputSampleRate,
            outputFrameCount = result.frames,
            windowCount = result.windowCount,
            elapsedMs = result.elapsedMs,
            totalBytes = store.entrySize(run.identity.cacheKey),
        )
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
            updatedAtEpochMs = nowEpochMs(),
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

    fun pause(run: SourceSeparationModelAwareCacheRun): SourceSeparationCacheManifest? {
        return finishIncomplete(run, SourceSeparationCacheManifestState.Running, null)
    }

    fun cancel(
        run: SourceSeparationModelAwareCacheRun,
        error: Throwable,
    ): SourceSeparationCacheManifest? {
        return finishIncomplete(run, SourceSeparationCacheManifestState.Canceled, error)
    }

    fun fail(
        run: SourceSeparationModelAwareCacheRun,
        error: Throwable,
    ): SourceSeparationCacheManifest? {
        return finishIncomplete(run, SourceSeparationCacheManifestState.Failed, error)
    }

    fun cleanCompletedTemporaryFiles(cacheKey: String): Boolean {
        val manifest = store.readManifest(cacheKey)
            ?.takeIf { it.state == SourceSeparationCacheManifestState.Completed }
            ?: return false
        val cleanup = manifest.cleanup ?: return false
        val lease = repository.tryAcquireExclusive(
            cacheKey,
            SourceSeparationCacheLockPurpose.Cleanup,
        ) ?: return false
        return lease.use {
            it.bindEntryDirectory(store.entryDirectory(cacheKey))
            val cleaned = cleanup.paths.all { path ->
                store.deleteRelativePath(cacheKey, path)
            }
            if (cleaned) {
                store.writeManifest(
                    manifest.copy(
                        cleanup = null,
                        output = manifest.output?.copy(totalBytes = store.entrySize(cacheKey)),
                        updatedAtEpochMs = nowEpochMs(),
                    )
                )
            }
            cleaned
        }
    }

    private fun finishIncomplete(
        run: SourceSeparationModelAwareCacheRun,
        state: SourceSeparationCacheManifestState,
        error: Throwable?,
    ): SourceSeparationCacheManifest? {
        return try {
            run.requireOpen()
            val current = store.readManifest(run.identity.cacheKey) ?: return null
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
                state = state,
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
                val transition = when (state) {
                    SourceSeparationCacheManifestState.Running ->
                        SourceSeparationCacheRunTransitionType.Paused
                    SourceSeparationCacheManifestState.Canceled ->
                        SourceSeparationCacheRunTransitionType.UserCanceled
                    SourceSeparationCacheManifestState.Failed ->
                        SourceSeparationCacheRunTransitionType.Failed
                    SourceSeparationCacheManifestState.Completed -> error(
                        "Incomplete cache run cannot become completed.",
                    )
                }
                val lifecycle = when (state) {
                    SourceSeparationCacheManifestState.Running ->
                        SourceSeparationCacheRunJournalLifecycle.Paused
                    SourceSeparationCacheManifestState.Canceled ->
                        SourceSeparationCacheRunJournalLifecycle.Canceled
                    SourceSeparationCacheManifestState.Failed ->
                        SourceSeparationCacheRunJournalLifecycle.Failed
                    SourceSeparationCacheManifestState.Completed -> error(
                        "Incomplete cache run cannot become completed.",
                    )
                }
                journal.append(
                    type = transition,
                    nowEpochMs = now,
                    error = error?.toCacheError(),
                    lifecycle = lifecycle,
                )
            }
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
        val stems = output?.stems.orEmpty().associateBy { it.semantic }
        val vocals = stems[ContractStemSemantic.Vocals] ?: return null
        val instrumental = stems[ContractStemSemantic.Instrumental] ?: return null
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
                    committed.vocalsPath == segment.vocalsPath &&
                    committed.instrumentalPath == segment.instrumentalPath &&
                    committed.isValid(store, cacheKey)
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

    private fun committedSegment(
        run: SourceSeparationModelAwareCacheRun,
        segment: com.mardous.booming.separation.cache.SourceSeparationSegment,
    ) = SourceSeparationCacheCommittedSegment(
        segmentIndex = segment.index,
        vocalsPath = segment.vocalsPath,
        vocalsIntegrity = store.fileIntegrity(
            store.resolveEntryPath(run.identity.cacheKey, segment.vocalsPath),
        ),
        instrumentalPath = segment.instrumentalPath,
        instrumentalIntegrity = store.fileIntegrity(
            store.resolveEntryPath(run.identity.cacheKey, segment.instrumentalPath),
        ),
    )

    private fun cleanUncommittedSegments(
        cacheKey: String,
        plan: SourceSeparationSegmentPlan?,
        committedSegments: List<SourceSeparationCacheCommittedSegment>,
    ) {
        val committedIndexes = committedSegments.map { it.segmentIndex }.toSet()
        plan?.segments.orEmpty()
            .filterNot { it.index in committedIndexes }
            .forEach { segment ->
                store.deleteRelativePath(cacheKey, segment.vocalsPath)
                store.deleteRelativePath(cacheKey, segment.instrumentalPath)
            }
        store.resolveEntryPath(cacheKey, SEGMENTS_DIRECTORY)
            .walkTopDown()
            .filter { file -> file.isFile && file.name.endsWith(".tmp") }
            .forEach(File::delete)
    }

    private fun SourceSeparationCacheCommittedSegment.isValid(
        store: SourceSeparationCacheStore,
        cacheKey: String,
    ): Boolean = store.validateIntegrity(
        cacheKey = cacheKey,
        relativePath = vocalsPath,
        expected = vocalsIntegrity,
    ) && store.validateIntegrity(
        cacheKey = cacheKey,
        relativePath = instrumentalPath,
        expected = instrumentalIntegrity,
    )

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
        admittedAtEpochMs = admittedAtEpochMs,
    )

    private fun Throwable.toCacheError() = SourceSeparationCacheError(
        type = this::class.java.name,
        message = message,
    )

    private fun SourceSeparationModelAwareCacheRun.renderedStem(
        semantic: ContractStemSemantic,
        file: File,
        frameCount: Int,
        sampleRate: Int,
    ): SourceSeparationCacheRenderedStem {
        return SourceSeparationCacheRenderedStem(
            semantic = semantic,
            displayLabel = labelFor(semantic),
            wavPath = store.relativeEntryPath(identity.cacheKey, file),
            channelCount = 2,
            sampleRate = sampleRate,
            frameCount = frameCount,
        )
    }

    private fun completedStem(
        run: SourceSeparationModelAwareCacheRun,
        semantic: ContractStemSemantic,
        path: String,
        integrity: SourceSeparationCacheFileIntegrity,
        result: MdxRangeSeparationResult,
    ): SourceSeparationCacheRenderedStem {
        return SourceSeparationCacheRenderedStem(
            semantic = semantic,
            displayLabel = run.labelFor(semantic),
            wavPath = path,
            channelCount = 2,
            sampleRate = result.outputSampleRate,
            frameCount = result.frames,
            wavIntegrity = integrity,
        )
    }

    private fun SourceSeparationModelAwareCacheRun.labelFor(
        semantic: ContractStemSemantic,
    ): String {
        val stems = listOf(contract.stemContract.modelOutput, contract.stemContract.residual)
        return stems.singleOrNull { it.semantic == semantic }?.displayLabel ?: semantic.name
    }

    companion object {
        const val WORK_DIRECTORY = "work"
        const val COMPLETED_DIRECTORY = "completed"
        const val SEGMENTS_DIRECTORY = "segments"
        const val COMPLETED_VOCALS_PATH = "completed/vocals.wav"
        const val COMPLETED_INSTRUMENTAL_PATH = "completed/instrumental.wav"
        const val COMPLETED_TIMING_PATH = "completed/timing.txt"
    }
}

data class SourceSeparationCacheRunRequest(
    val identity: SourceSeparationCacheIdentity,
    val contract: SourceSeparationCacheContractSnapshot,
    val song: SourceSeparationCacheSongLocator,
    val sourceDiagnostics: SourceSeparationCacheSourceDiagnostics,
    val runId: String = java.util.UUID.randomUUID().toString(),
    val processGeneration: Long = 1L,
    val ownerPid: Int? = null,
) {
    init {
        require(runId.isNotBlank()) { "Cache run ID is empty." }
        require(processGeneration > 0L) { "Cache run process generation is invalid." }
        require(ownerPid == null || ownerPid > 0) { "Cache run owner PID is invalid." }
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
