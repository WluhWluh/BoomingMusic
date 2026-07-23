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
    fun begin(
        request: SourceSeparationCacheRunRequest,
    ): SourceSeparationCacheRunStart {
        require(request.contract.identity(
            source = request.identity.source,
            renderProfileId = request.identity.renderProfileId,
        ) == request.identity) {
            "Cache run identity does not match its contract snapshot."
        }
        val lease = repository.tryAcquireRunWrite(request.identity)
            ?: return SourceSeparationCacheRunStart.Busy
        return try {
            val existing = store.readManifest(request.identity.cacheKey)
            if (existing?.state == SourceSeparationCacheManifestState.Completed &&
                store.validateCompletedEntry(existing, verifyHashes = false) ==
                SourceSeparationCacheValidationResult.Valid
            ) {
                lease.close()
                return SourceSeparationCacheRunStart.AlreadyCompleted(existing)
            }
            val entryDirectory = store.entryDirectory(request.identity.cacheKey).apply { mkdirs() }
            val workDirectory = store.resolveEntryPath(request.identity.cacheKey, WORK_DIRECTORY)
            val completedDirectory = store.resolveEntryPath(
                request.identity.cacheKey,
                COMPLETED_DIRECTORY,
            )
            val segmentsDirectory = store.resolveEntryPath(
                request.identity.cacheKey,
                SEGMENTS_DIRECTORY,
            )
            val resumed = existing?.toResumeState(store)
            val manifest = if (resumed != null) {
                val resumedPlan = existing.segmentPlan!!.withValidatedReadySegments(
                    store = store,
                    cacheKey = existing.cacheKey,
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
            SourceSeparationCacheRunStart.Ready(
                SourceSeparationModelAwareCacheRun(
                    identity = request.identity,
                    contract = request.contract,
                    entryDirectory = entryDirectory,
                    workDirectory = workDirectory,
                    completedDirectory = completedDirectory,
                    segmentsDirectory = segmentsDirectory,
                    resumeState = manifest.toResumeState(store),
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
            ),
            updatedAtEpochMs = nowEpochMs(),
        )
        store.writeManifest(completed)
        completed = completed.copy(
            output = completed.output?.copy(totalBytes = store.entrySize(run.identity.cacheKey)),
        )
        store.writeManifest(completed)
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
        val lease = repository.tryAcquireExclusive(cacheKey) ?: return false
        return lease.use {
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
        run.requireOpen()
        return try {
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
            current.copy(
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
    ): SourceSeparationSegmentPlan {
        return copy(
            segments = segments.map { segment ->
                val canPreserve = segment.state.isPlaybackReady &&
                    store.resolveEntryPath(cacheKey, segment.vocalsPath).isFile &&
                    store.resolveEntryPath(cacheKey, segment.instrumentalPath).isFile
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
    private val lease: SourceSeparationCacheEntryLease,
) : AutoCloseable {
    private var closed = false

    internal fun requireOpen() {
        check(!closed) { "Cache run is closed." }
    }

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
        }
        lease.close()
    }
}
