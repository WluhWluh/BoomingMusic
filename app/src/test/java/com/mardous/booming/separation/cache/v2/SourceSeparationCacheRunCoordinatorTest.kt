package com.mardous.booming.separation.cache.v2

import com.mardous.booming.separation.SourceSeparationExecutionRunClass
import com.mardous.booming.separation.SourceSeparationPauseReason
import com.mardous.booming.separation.SourceSeparationBackgroundPolicy
import com.mardous.booming.separation.SourceSeparationGpuFallbackLatch
import com.mardous.booming.separation.cache.SourceSeparationSegmentPlan
import com.mardous.booming.separation.cache.SourceSeparationSegmentState
import com.mardous.booming.separation.model.MdxInferenceBackend
import com.mardous.booming.separation.model.MdxRangePreparation
import com.mardous.booming.separation.model.MdxRangeSeparationResult
import com.mardous.booming.separation.model.MdxRangeTimingReport
import com.mardous.booming.separation.model.MdxRuntimeDiagnostics
import com.mardous.booming.separation.model.MdxRuntimeSettings
import com.mardous.booming.separation.model.MdxSourceDecodeDiagnostics
import com.mardous.booming.separation.model.MdxSourceDecodeMode
import com.mardous.booming.separation.model.contract.SourceSeparationModelCatalog
import com.mardous.booming.separation.model.contract.SourceSeparationModelMetadata
import com.mardous.booming.separation.model.contract.toMdxExecutionProfile
import com.mardous.booming.separation.model.litert.MdxLiteRtBoundedGpuContract
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SourceSeparationCacheRunCoordinatorTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `one run writer can publish ready windows while playback holds a read lease`() {
        val fixture = fixture()
        val run = fixture.beginReady()
        val preparation = fixture.preparation(run, SourceSeparationSegmentState.Ready)
        fixture.coordinator.updatePreparation(run, preparation)

        val status = fixture.repository.playableStatus(run.identity, 0L, 2)
        assertTrue(status is SourceSeparationModelAwarePlayableStatus.Ready)
        val playback = (status as SourceSeparationModelAwarePlayableStatus.Ready).playback
        assertEquals(SourceSeparationCacheMutationResult.Busy, fixture.repository.delete(run.identity.cacheKey))
        assertTrue(fixture.repository.isLeased(run.identity.cacheKey))

        playback.close()
        fixture.coordinator.pause(run)
        assertFalse(fixture.repository.isLeased(run.identity.cacheKey))
    }

    @Test
    fun `paused run resumes exact ready segments and resets a missing segment`() {
        val fixture = fixture()
        val first = fixture.beginReady()
        val preparation = fixture.preparation(first, SourceSeparationSegmentState.Ready)
        fixture.coordinator.updatePreparation(first, preparation)
        fixture.coordinator.pause(first)
        val missing = preparation.segmentPlan.segments[1]
        fixture.store.resolveEntryPath(first.identity.cacheKey, missing.vocalsPath).delete()

        val resumed = fixture.beginReady()
        val resumeState = requireNotNull(resumed.resumeState)

        assertEquals(
            SourceSeparationSegmentState.Ready,
            resumeState.segmentPlan.segments[0].state,
        )
        assertEquals(
            SourceSeparationSegmentState.Queued,
            resumeState.segmentPlan.segments[1].state,
        )
        fixture.coordinator.pause(resumed)
    }

    @Test
    fun `model handoff pauses and retains the exact partial cache`() {
        val fixture = fixture()
        val run = fixture.beginReady()
        val preparation = fixture.preparation(run, SourceSeparationSegmentState.Ready)
        fixture.coordinator.updatePreparation(run, preparation)

        val retained = requireNotNull(
            fixture.coordinator.pause(
                run,
                SourceSeparationPauseReason.ActiveModelSuperseded,
            ),
        )
        val journal = requireNotNull(fixture.store.readRunJournal(retained.cacheKey))

        assertEquals(SourceSeparationCacheManifestState.Running, retained.state)
        assertEquals(SourceSeparationCacheRunJournalLifecycle.Paused, journal.lifecycle)
        assertEquals(
            SourceSeparationCacheRunTransitionType.ActiveModelSuperseded,
            journal.transitions.last().type,
        )
        assertTrue(retained.segmentPlan?.segments?.all {
            it.state == SourceSeparationSegmentState.Ready
        } == true)
    }

    @Test
    fun `completion publishes validated output before releasing the run lease`() {
        val fixture = fixture()
        val run = fixture.beginReady()
        val preparation = fixture.preparation(first = run, state = SourceSeparationSegmentState.Ready)
        fixture.coordinator.updatePreparation(run, preparation)

        val completed = fixture.coordinator.complete(run, fixture.result(preparation))

        assertEquals(SourceSeparationCacheManifestState.Completed, completed.state)
        assertEquals(SourceSeparationCacheValidationResult.Valid, fixture.store.validateCompletedEntry(completed))
        val completedJournal = requireNotNull(fixture.store.readRunJournal(completed.cacheKey))
        assertEquals(SourceSeparationCacheRunJournalLifecycle.Completed, completedJournal.lifecycle)
        assertEquals(
            SourceSeparationCacheRunTransitionType.Completed,
            completedJournal.transitions.last().type,
        )
        assertFalse(fixture.repository.isLeased(completed.cacheKey))
        assertTrue(fixture.store.resolveEntryPath(completed.cacheKey, "work/vocals.wav").isFile)
        assertTrue(
            fixture.coordinator.begin(fixture.request) is SourceSeparationCacheRunStart.AlreadyCompleted
        )

        val playback = requireNotNull(fixture.repository.openCompletedCache(completed.cacheKey))
        assertFalse(fixture.coordinator.cleanCompletedTemporaryFiles(completed.cacheKey))
        playback.close()
        assertTrue(fixture.coordinator.cleanCompletedTemporaryFiles(completed.cacheKey))
        assertFalse(fixture.store.resolveEntryPath(completed.cacheKey, "work").exists())
        assertEquals(
            SourceSeparationCacheValidationResult.Valid,
            fixture.store.validateCompletedEntry(requireNotNull(fixture.store.readManifest(completed.cacheKey))),
        )
    }

    @Test
    fun `completion preserves the concrete backend and auto fallback diagnostics`() {
        val fixture = fixture()
        val run = fixture.beginReady()
        val preparation = fixture.preparation(run, SourceSeparationSegmentState.Ready)
        fixture.coordinator.updatePreparation(run, preparation)
        val result = fixture.result(preparation)
        val completed = fixture.coordinator.complete(
            run,
            result.copy(
                runtimeDiagnostics = result.runtimeDiagnostics.copy(
                    backend = MdxInferenceBackend.LiteRtCpu,
                    fallbackStage = "GpuInvocation",
                    fallbackReason = "Injected GPU invocation failure.",
                ),
            ),
        )

        val record = completed.runtimeRecords.single()
        assertEquals(MdxInferenceBackend.LiteRtCpu.name, record.backend)
        assertEquals("GpuInvocation", record.fallbackStage)
        assertEquals("Injected GPU invocation failure.", record.fallbackReason)
    }

    @Test
    fun `completion preserves structured source decode diagnostics`() {
        val fixture = fixture()
        val run = fixture.beginReady()
        val preparation = fixture.preparation(run, SourceSeparationSegmentState.Ready)
        fixture.coordinator.updatePreparation(run, preparation)
        val result = fixture.result(preparation)
        val completed = fixture.coordinator.complete(
            run,
            result.copy(
                sourceDecodeDiagnostics = result.sourceDecodeDiagnostics.copy(
                    mode = MdxSourceDecodeMode.FullSong,
                    profile = null,
                    mimeType = "audio/mp4a-latm",
                    fallbackReason = "Window decode is not enabled for this MIME/sample-rate profile.",
                ),
            ),
        )

        val record = completed.runtimeRecords.single()
        assertEquals(MdxSourceDecodeMode.FullSong.name, record.sourceDecodeMode)
        assertNull(record.sourceDecodeProfile)
        assertEquals("audio/mp4a-latm", record.sourceDecodeMimeType)
        assertEquals(
            "Window decode is not enabled for this MIME/sample-rate profile.",
            record.sourceDecodeFallbackReason,
        )
        assertEquals(44_100, record.sourceDecodeSampleRate)
        assertEquals(2, record.sourceDecodeChannelCount)
        assertEquals(88_200, record.sourceDecodeSourceFrameCount)
        assertEquals(88_200, record.sourceDecodeOutputFrameCount)
    }

    @Test
    fun `source replacement cannot complete or publish a ready cache`() {
        val fixture = fixture()
        val run = fixture.beginReady()
        val preparation = fixture.preparation(run, SourceSeparationSegmentState.Ready)
        fixture.coordinator.updatePreparation(run, preparation)
        val changed = fixture.result(preparation).copy(
            sourceAudioFingerprint = "encoded-samples-v1:${"b".repeat(64)}",
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            fixture.coordinator.complete(run, changed)
        }
        fixture.coordinator.fail(run, error)

        val manifest = requireNotNull(fixture.store.readManifest(run.identity.cacheKey))
        assertEquals(SourceSeparationCacheManifestState.Failed, manifest.state)
        assertNull(fixture.repository.openCompletedCache(run.identity.cacheKey))
        assertFalse(fixture.repository.isLeased(run.identity.cacheKey))
    }

    @Test
    fun `ready journal record is integrity checked before resume`() {
        val fixture = fixture()
        val first = fixture.beginReady()
        val preparation = fixture.preparation(first, SourceSeparationSegmentState.Queued)
        fixture.coordinator.updatePreparation(first, preparation)
        fixture.coordinator.updateSegmentState(first, 0, SourceSeparationSegmentState.Ready)

        val committed = requireNotNull(fixture.store.readRunJournal(first.identity.cacheKey))
            .committedSegments.single()
        assertTrue(
            fixture.store.validateIntegrity(
                first.identity.cacheKey,
                committed.vocalsPath,
                committed.vocalsIntegrity,
            )
        )
        fixture.coordinator.pause(first)
        fixture.store.resolveEntryPath(first.identity.cacheKey, committed.vocalsPath)
            .appendText("corrupt")

        val resumed = fixture.beginReady()

        assertEquals(
            SourceSeparationSegmentState.Queued,
            requireNotNull(resumed.resumeState).segmentPlan.segments[0].state,
        )
        assertTrue(
            requireNotNull(fixture.store.readRunJournal(resumed.identity.cacheKey))
                .committedSegments.isEmpty()
        )
        assertFalse(
            fixture.store.resolveEntryPath(resumed.identity.cacheKey, committed.vocalsPath).exists()
        )
        fixture.coordinator.pause(resumed)
    }

    @Test
    fun `new process generation records an abandoned running owner`() {
        val fixture = fixture()
        val abandoned = fixture.beginReady()
        abandoned.close()
        val nextRequest = fixture.request.copy(
            runId = "next-run",
            processGeneration = 2L,
            ownerPid = 200,
        )

        val resumed = (fixture.coordinator.begin(nextRequest) as SourceSeparationCacheRunStart.Ready)
            .run
        val journal = requireNotNull(fixture.store.readRunJournal(resumed.identity.cacheKey))

        assertEquals("next-run", journal.request.runId)
        assertEquals(2L, journal.request.processGeneration)
        assertEquals(
            listOf(
                SourceSeparationCacheRunTransitionType.Admitted,
                SourceSeparationCacheRunTransitionType.PreviousOwnerDied,
                SourceSeparationCacheRunTransitionType.Admitted,
            ),
            journal.transitions.map { it.type },
        )
        assertEquals(
            (1L..journal.transitions.size.toLong()).toList(),
            journal.transitions.map { it.sequence },
        )
        fixture.coordinator.pause(resumed)
    }

    @Test
    fun `new owner closes the observer abandoned with a dead process`() {
        val fixture = fixture()
        val abandoned = fixture.beginReady()
        fixture.coordinator.observerConnected(
            abandoned,
            observerId = "observer-old-owner",
            observerProcessName = "com.example",
        )
        abandoned.close()

        val resumed = (fixture.coordinator.begin(
            fixture.request.copy(
                runId = "resumed-run",
                processGeneration = 2L,
                ownerPid = 200,
            )
        ) as SourceSeparationCacheRunStart.Ready).run
        val journal = requireNotNull(
            fixture.store.readRunJournal(resumed.identity.cacheKey)
        )

        assertEquals(
            listOf(
                SourceSeparationCacheRunTransitionType.Admitted,
                SourceSeparationCacheRunTransitionType.ObserverConnected,
                SourceSeparationCacheRunTransitionType.PreviousOwnerDied,
                SourceSeparationCacheRunTransitionType.ObserverDisconnected,
                SourceSeparationCacheRunTransitionType.Admitted,
            ),
            journal.transitions.map { it.type },
        )
        val disconnected = journal.transitions[3]
        assertEquals("observer-old-owner", disconnected.observerId)
        assertEquals("com.example", disconnected.observerProcessName)
        assertEquals("owner-process-died", disconnected.observerReason)

        val reconnected = fixture.coordinator.observerConnected(
            resumed,
            observerId = "observer-new-owner",
            observerProcessName = "com.example",
        )
        assertEquals(
            SourceSeparationCacheRunTransitionType.ObserverConnected,
            reconnected.transitions.last().type,
        )
        fixture.coordinator.pause(resumed)
    }

    @Test
    fun `new owner closes the observer retained by a paused run`() {
        val fixture = fixture()
        val paused = fixture.beginReady()
        fixture.coordinator.observerConnected(
            paused,
            observerId = "observer-paused-owner",
            observerProcessName = "com.example",
        )
        fixture.coordinator.pause(paused)

        val resumed = (fixture.coordinator.begin(
            fixture.request.copy(
                runId = "resumed-paused-run",
                processGeneration = 2L,
                ownerPid = 200,
            )
        ) as SourceSeparationCacheRunStart.Ready).run
        val journal = requireNotNull(
            fixture.store.readRunJournal(resumed.identity.cacheKey)
        )

        assertEquals(
            listOf(
                SourceSeparationCacheRunTransitionType.Admitted,
                SourceSeparationCacheRunTransitionType.ObserverConnected,
                SourceSeparationCacheRunTransitionType.Paused,
                SourceSeparationCacheRunTransitionType.ObserverDisconnected,
                SourceSeparationCacheRunTransitionType.Admitted,
            ),
            journal.transitions.map { it.type },
        )
        val disconnected = journal.transitions[3]
        assertEquals("observer-paused-owner", disconnected.observerId)
        assertEquals("paused-run-replaced", disconnected.observerReason)

        fixture.coordinator.observerConnected(
            resumed,
            observerId = "observer-new-owner",
            observerProcessName = "com.example",
        )
        fixture.coordinator.pause(resumed)
    }

    @Test
    fun `run class is immutable for a running owner and explicit on paused readmission`() {
        val activeFixture = fixture()
        val prefetchRequest = activeFixture.request.copy(
            runClass = SourceSeparationExecutionRunClass.NextSongPrefetch,
            backgroundPolicy = SourceSeparationBackgroundPolicy.ClientBound,
        )
        val abandoned = (activeFixture.coordinator.begin(prefetchRequest) as
            SourceSeparationCacheRunStart.Ready).run
        abandoned.close()

        assertThrows(IllegalArgumentException::class.java) {
            activeFixture.coordinator.begin(
                prefetchRequest.copy(
                    runId = "illegal-upgrade",
                    processGeneration = 2L,
                    runClass = SourceSeparationExecutionRunClass.ManualFullSong,
                    backgroundPolicy =
                        SourceSeparationBackgroundPolicy.IndependentForegroundEligible,
                )
            )
        }
        assertFalse(activeFixture.repository.isLeased(prefetchRequest.identity.cacheKey))

        val pausedFixture = fixture()
        val pausedPrefetchRequest = pausedFixture.request.copy(
            runClass = SourceSeparationExecutionRunClass.NextSongPrefetch,
            backgroundPolicy = SourceSeparationBackgroundPolicy.ClientBound,
        )
        val prefetch = (pausedFixture.coordinator.begin(pausedPrefetchRequest) as
            SourceSeparationCacheRunStart.Ready).run
        pausedFixture.coordinator.pause(prefetch)
        val playbackRequest = pausedPrefetchRequest.copy(
            runId = "playback-readmission",
            processGeneration = 2L,
            runClass = SourceSeparationExecutionRunClass.PlaybackDemandWindow,
            backgroundPolicy = SourceSeparationBackgroundPolicy.PlaybackServiceOwned,
        )
        val playback = (pausedFixture.coordinator.begin(playbackRequest) as
            SourceSeparationCacheRunStart.Ready).run
        val journal = requireNotNull(
            pausedFixture.store.readRunJournal(playbackRequest.identity.cacheKey)
        )

        assertEquals(SourceSeparationExecutionRunClass.PlaybackDemandWindow,
            journal.request.runClass)
        assertEquals(SourceSeparationBackgroundPolicy.PlaybackServiceOwned,
            journal.request.backgroundPolicy)
        assertEquals(
            listOf(
                SourceSeparationExecutionRunClass.NextSongPrefetch,
                SourceSeparationExecutionRunClass.NextSongPrefetch,
                SourceSeparationExecutionRunClass.PlaybackDemandWindow,
            ),
            journal.transitions.map { it.runClass },
        )
        pausedFixture.coordinator.pause(playback)
    }

    @Test
    fun `resumable journal preserves its exact admitted GPU runtime`() {
        val fixture = fixture()
        val runtimeIdentity = admittedGpuRuntimeIdentity()
        val gpuRequest = fixture.request.copy(
            runId = "gpu-run",
            tryGpu = true,
            gpuRuntimeIdentity = runtimeIdentity,
        )
        val first = fixture.coordinator.begin(gpuRequest)
            as SourceSeparationCacheRunStart.Ready

        assertEquals(
            SourceSeparationCacheAdmittedRuntimePolicy(true, runtimeIdentity),
            fixture.coordinator.inspectAdmittedRuntimePolicy(gpuRequest.identity),
        )
        assertEquals(
            runtimeIdentity,
            requireNotNull(fixture.store.readRunJournal(gpuRequest.identity.cacheKey))
                .request
                .gpuRuntimeIdentity,
        )
        assertEquals(
            6,
            fixture.store.readRunJournal(gpuRequest.identity.cacheKey)?.journalSchemaVersion,
        )
        fixture.coordinator.pause(first.run)

        assertThrows(IllegalArgumentException::class.java) {
            fixture.coordinator.begin(
                gpuRequest.copy(
                    runId = "changed-runtime-run",
                    processGeneration = 2L,
                    gpuRuntimeIdentity = runtimeIdentity.copy(
                        artifactVersion = "different-runtime",
                    ),
                )
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            fixture.coordinator.begin(
                gpuRequest.copy(
                    runId = "changed-policy-run",
                    processGeneration = 2L,
                    tryGpu = false,
                    gpuRuntimeIdentity = null,
                )
            )
        }
        assertFalse(fixture.repository.isLeased(gpuRequest.identity.cacheKey))

        val resumed = fixture.coordinator.begin(
            gpuRequest.copy(
                runId = "resumed-gpu-run",
                processGeneration = 2L,
            )
        ) as SourceSeparationCacheRunStart.Ready
        assertEquals(
            SourceSeparationCacheAdmittedRuntimePolicy(true, runtimeIdentity),
            fixture.coordinator.inspectAdmittedRuntimePolicy(gpuRequest.identity),
        )
        fixture.coordinator.pause(resumed.run)
    }

    @Test
    fun `clearing an abandoned run starts a fresh backend admission`() {
        val fixture = fixture()
        val runtimeIdentity = admittedGpuRuntimeIdentity()
        val abandonedRequest = fixture.request.copy(
            runId = "abandoned-gpu-run",
            processGeneration = 1L,
            ownerPid = 100,
            tryGpu = true,
            gpuRuntimeIdentity = runtimeIdentity,
        )
        val abandoned = (fixture.coordinator.begin(abandonedRequest) as
            SourceSeparationCacheRunStart.Ready).run
        val preparation = fixture.preparation(abandoned, SourceSeparationSegmentState.Queued)
        fixture.coordinator.updatePreparation(abandoned, preparation)
        fixture.coordinator.updateSegmentState(abandoned, 0, SourceSeparationSegmentState.Ready)
        assertEquals(
            1,
            fixture.store.readRunJournal(abandoned.identity.cacheKey)?.committedSegments?.size,
        )
        abandoned.close()

        assertEquals(
            SourceSeparationCacheMutationResult.Completed,
            fixture.repository.delete(abandoned.identity.cacheKey),
        )
        assertNull(fixture.store.readRunJournal(abandoned.identity.cacheKey))
        assertNull(fixture.coordinator.inspectAdmittedRuntimePolicy(abandoned.identity))

        val freshRequest = fixture.request.copy(
            runId = "fresh-cpu-run",
            processGeneration = 2L,
            ownerPid = 200,
            tryGpu = false,
            gpuRuntimeIdentity = null,
        )
        val fresh = (fixture.coordinator.begin(freshRequest) as
            SourceSeparationCacheRunStart.Ready).run
        val freshJournal = requireNotNull(
            fixture.store.readRunJournal(fresh.identity.cacheKey)
        )

        assertNull(fresh.resumeState)
        assertFalse(freshJournal.request.tryGpu)
        assertNull(freshJournal.request.gpuRuntimeIdentity)
        assertEquals(
            listOf(SourceSeparationCacheRunTransitionType.Admitted),
            freshJournal.transitions.map { it.type },
        )
        assertEquals(listOf(1L), freshJournal.transitions.map { it.sequence })
        fixture.coordinator.pause(fresh)
    }

    @Test
    fun `GPU fallback latch is durable idempotent and required on resume`() {
        val fixture = fixture()
        val runtimeIdentity = admittedGpuRuntimeIdentity()
        val request = fixture.request.copy(
            runId = "gpu-fallback-run",
            tryGpu = true,
            gpuRuntimeIdentity = runtimeIdentity,
        )
        val first = fixture.coordinator.begin(request)
            as SourceSeparationCacheRunStart.Ready
        val latch = SourceSeparationGpuFallbackLatch(
            stage = "GpuInvocation",
            reason = "Injected recoverable GPU failure.",
        )

        val latched = fixture.coordinator.latchGpuFallback(first.run, latch)
        val duplicate = fixture.coordinator.latchGpuFallback(first.run, latch)

        assertEquals(latch, latched.request.gpuFallbackLatch)
        assertEquals(latched, duplicate)
        assertEquals(
            1,
            duplicate.transitions.count {
                it.type == SourceSeparationCacheRunTransitionType.GpuFallbackLatched
            },
        )
        assertEquals(
            SourceSeparationCacheAdmittedRuntimePolicy(true, runtimeIdentity, latch),
            fixture.coordinator.inspectAdmittedRuntimePolicy(request.identity),
        )
        assertThrows(IllegalArgumentException::class.java) {
            fixture.coordinator.latchGpuFallback(
                first.run,
                latch.copy(reason = "Conflicting fallback reason."),
            )
        }
        fixture.coordinator.pause(first.run)

        assertThrows(IllegalArgumentException::class.java) {
            fixture.coordinator.begin(
                request.copy(
                    runId = "missing-fallback-run",
                    processGeneration = 2L,
                )
            )
        }
        val resumed = fixture.coordinator.begin(
            request.copy(
                runId = "resumed-fallback-run",
                processGeneration = 2L,
                gpuFallbackLatch = latch,
            )
        ) as SourceSeparationCacheRunStart.Ready
        val resumedJournal = requireNotNull(
            fixture.store.readRunJournal(request.identity.cacheKey)
        )

        assertEquals(latch, resumedJournal.request.gpuFallbackLatch)
        assertEquals(runtimeIdentity, resumedJournal.request.gpuRuntimeIdentity)
        assertTrue(resumedJournal.request.tryGpu)
        assertEquals(
            1,
            resumedJournal.transitions.count {
                it.type == SourceSeparationCacheRunTransitionType.GpuFallbackLatched
            },
        )
        fixture.coordinator.pause(resumed.run)
    }

    @Test
    fun `observer ownership transitions are durable idempotent and exact`() {
        val fixture = fixture()
        val run = fixture.beginReady()

        val connected = fixture.coordinator.observerConnected(
            run,
            observerId = "observer-main-1",
            observerProcessName = "com.example",
        )
        val duplicateConnected = fixture.coordinator.observerConnected(
            run,
            observerId = "observer-main-1",
            observerProcessName = "com.example",
        )
        val disconnected = fixture.coordinator.observerDisconnected(
            run,
            observerId = "observer-main-1",
            observerProcessName = "com.example",
            reason = "binder-died",
        )
        val duplicateDisconnected = fixture.coordinator.observerDisconnected(
            run,
            observerId = "observer-main-1",
            observerProcessName = "com.example",
            reason = "duplicate",
        )

        assertEquals(connected, duplicateConnected)
        assertEquals(disconnected, duplicateDisconnected)
        assertEquals(6, disconnected.journalSchemaVersion)
        assertEquals(
            listOf(
                SourceSeparationCacheRunTransitionType.ObserverConnected,
                SourceSeparationCacheRunTransitionType.ObserverDisconnected,
            ),
            disconnected.transitions.takeLast(2).map { it.type },
        )
        assertEquals("binder-died", disconnected.transitions.last().observerReason)
        assertThrows(IllegalArgumentException::class.java) {
            fixture.coordinator.observerDisconnected(
                run,
                observerId = "observer-stale",
                observerProcessName = "com.example",
                reason = "stale",
            )
        }
        val reconnected = fixture.coordinator.observerConnected(
            run,
            observerId = "observer-main-2",
            observerProcessName = "com.example",
        )
        assertEquals(
            SourceSeparationCacheRunTransitionType.ObserverConnected,
            reconnected.transitions.last().type,
        )
        fixture.coordinator.pause(run)
    }

    @Test
    fun `cache disappearance is typed and is not recreated by the active run`() {
        val fixture = fixture()
        val run = fixture.beginReady()
        val entry = run.entryDirectory
        assertTrue(entry.deleteRecursively())

        val error = assertThrows(SourceSeparationCacheLostException::class.java) {
            fixture.coordinator.pause(run)
        }

        assertEquals(run.identity.cacheKey, error.cacheKey)
        assertFalse(entry.exists())
        assertFalse(fixture.repository.isLeased(run.identity.cacheKey))
    }

    @Test
    fun `second worker for the same identity is busy but another identity can start`() {
        val fixture = fixture()
        val first = fixture.beginReady()

        assertEquals(SourceSeparationCacheRunStart.Busy, fixture.coordinator.begin(fixture.request))

        val otherRequest = fixture.request.copy(
            identity = fixture.request.contract.identity(sourceIdentity('b')),
        )
        val other = fixture.coordinator.begin(otherRequest)
        assertTrue(other is SourceSeparationCacheRunStart.Ready)

        fixture.coordinator.pause(first)
        fixture.coordinator.pause((other as SourceSeparationCacheRunStart.Ready).run)
    }

    private fun admittedGpuRuntimeIdentity() = SourceSeparationAdmittedGpuRuntimeIdentity(
        profileId = MdxLiteRtBoundedGpuContract.PROFILE_ID,
        artifactVersion = MdxLiteRtBoundedGpuContract.ARTIFACT_VERSION,
        capabilitySchemaVersion = MdxLiteRtBoundedGpuContract.CAPABILITY_SCHEMA_VERSION,
        backend = MdxLiteRtBoundedGpuContract.BACKEND,
        precision = MdxLiteRtBoundedGpuContract.PRECISION,
        kernelBatchSize = MdxLiteRtBoundedGpuContract.KERNEL_BATCH_SIZE,
        commandQueueWindowSize = MdxLiteRtBoundedGpuContract.COMMAND_QUEUE_WINDOW_SIZE,
    )

    private fun fixture(): CoordinatorFixture {
        val store = SourceSeparationCacheStore(
            root = SourceSeparationCacheRoot(
                directory = temporary.newFolder().absoluteFile,
                location = SourceSeparationCacheRootLocation.InternalCache,
            ),
            nowEpochMs = { 10L },
        )
        val repository = SourceSeparationModelAwareCacheRepository(
            store = store,
            modelAvailability = SourceSeparationCacheModelAvailabilityProvider {
                SourceSeparationCacheModelAvailability.InstalledExact
            },
            nowEpochMs = { 10L },
        )
        val coordinator = SourceSeparationCacheRunCoordinator(
            store = store,
            repository = repository,
            nowEpochMs = { 10L },
        )
        val contract = SourceSeparationCacheContractSnapshot.fromOfficial(
            catalog.contracts.single { it.modelId == "uvr_mdxnet_3_9662" }
        )
        return CoordinatorFixture(
            store = store,
            repository = repository,
            coordinator = coordinator,
            request = SourceSeparationCacheRunRequest(
                identity = contract.identity(sourceIdentity('a')),
                contract = contract,
                song = SourceSeparationCacheSongLocator(
                    songId = 42L,
                    mediaUri = "content://media/42",
                    filePath = "/music/song.flac",
                    title = "Song",
                    artist = "Artist",
                    album = "Album",
                ),
                sourceDiagnostics = SourceSeparationCacheSourceDiagnostics(
                    fileSize = 1_024L,
                    rawDateModified = 50L,
                    durationMs = 2_000L,
                ),
                runClass = SourceSeparationExecutionRunClass.PlaybackDemandWindow,
                backgroundPolicy =
                    SourceSeparationExecutionRunClass.PlaybackDemandWindow.backgroundPolicy,
                tryGpu = false,
                gpuRuntimeIdentity = null,
                gpuFallbackLatch = null,
            ),
        )
    }

    private data class CoordinatorFixture(
        val store: SourceSeparationCacheStore,
        val repository: SourceSeparationModelAwareCacheRepository,
        val coordinator: SourceSeparationCacheRunCoordinator,
        val request: SourceSeparationCacheRunRequest,
    ) {
        fun beginReady(): SourceSeparationModelAwareCacheRun {
            return (coordinator.begin(request) as SourceSeparationCacheRunStart.Ready).run
        }

        fun preparation(
            first: SourceSeparationModelAwareCacheRun,
            state: SourceSeparationSegmentState,
        ): MdxRangePreparation {
            val vocals = File(first.workDirectory, "vocals.wav").apply { writeText("vocals") }
            val instrumental = File(first.workDirectory, "instrumental.wav").apply {
                writeText("instrumental")
            }
            val timing = File(first.workDirectory, "timing.txt").apply { writeText("timing") }
            val plan = SourceSeparationSegmentPlan.build(
                rangeStartFrame = 0,
                rangeEndFrame = 88_200,
                sampleRate = 44_100,
                generationSize = 44_100,
                trim = 1_024,
                chunkSize = 46_148,
                defaultState = state,
            )
            plan.segments.forEach { segment ->
                listOf(segment.vocalsPath, segment.instrumentalPath).forEach { path ->
                    store.resolveEntryPath(first.identity.cacheKey, path).apply {
                        parentFile?.mkdirs()
                        writeText(path)
                    }
                }
            }
            return MdxRangePreparation(
                vocalsFile = vocals,
                instrumentalFile = instrumental,
                timingFile = timing,
                startMs = 0L,
                endMs = 2_000L,
                frames = 88_200,
                windowCount = 2,
                sourceAudioFingerprint = first.identity.source.audioFingerprint,
                sourceFrameCount = 88_200,
                sourceSampleRate = 44_100,
                sourceChannelCount = 2,
                outputSampleRate = 44_100,
                segmentPlan = plan,
            )
        }

        fun result(preparation: MdxRangePreparation): MdxRangeSeparationResult {
            val profile = catalog.contracts.single { it.modelId == "uvr_mdxnet_3_9662" }
                .toMdxExecutionProfile(catalog.runtimeQualifications)
            val diagnostics = MdxRuntimeDiagnostics(
                runtimeName = "fake-litert",
                backend = MdxInferenceBackend.LiteRtCpu,
                cpuThreads = 4,
                detail = "test",
            )
            val sourceDiagnostics = MdxSourceDecodeDiagnostics(
                mode = MdxSourceDecodeMode.Window,
                profile = "test",
                mimeType = "audio/flac",
                sampleRate = 44_100,
                channelCount = 2,
                sourceFrameCount = 88_200,
                outputFrameCount = 88_200,
                fallbackReason = null,
            )
            val timingReport = MdxRangeTimingReport(
                audioDurationSeconds = 2.0,
                windowCount = 2,
                totalMs = 1_000L,
                runtimeSettings = MdxRuntimeSettings(),
                runtimeDiagnostics = diagnostics,
                executionProfile = profile,
                sourceDecodeDiagnostics = sourceDiagnostics,
                stageMs = emptyMap(),
            )
            return MdxRangeSeparationResult(
                vocalsFile = preparation.vocalsFile,
                instrumentalFile = preparation.instrumentalFile,
                timingFile = preparation.timingFile,
                startMs = preparation.startMs,
                endMs = preparation.endMs,
                frames = preparation.frames,
                windowCount = preparation.windowCount,
                elapsedMs = 1_000L,
                sourceAudioFingerprint = preparation.sourceAudioFingerprint,
                sourceFrameCount = preparation.sourceFrameCount,
                sourceSampleRate = preparation.sourceSampleRate,
                sourceChannelCount = preparation.sourceChannelCount,
                outputSampleRate = preparation.outputSampleRate,
                segmentPlan = preparation.segmentPlan,
                timingReport = timingReport,
                runtimeSettings = MdxRuntimeSettings(),
                runtimeDiagnostics = diagnostics,
                executionProfile = profile,
                sourceDecodeDiagnostics = sourceDiagnostics,
            )
        }
    }

    companion object {
        private lateinit var catalog: SourceSeparationModelCatalog

        @JvmStatic
        @BeforeClass
        fun loadCatalog() {
            val resource = requireNotNull(
                SourceSeparationCacheRunCoordinatorTest::class.java.classLoader
                    ?.getResourceAsStream(SourceSeparationModelMetadata.CATALOG_ASSET_PATH)
            )
            catalog = resource.use { input ->
                SourceSeparationModelMetadata.decodeCatalog(input.readBytes().toString(Charsets.UTF_8))
            }
        }

        private fun sourceIdentity(seed: Char): SourceSeparationCacheSourceIdentity {
            return SourceSeparationCacheSourceIdentity(
                audioFingerprint = "encoded-samples-v1:${seed.toString().repeat(64)}",
                encodedSampleCount = 100L,
                encodedByteCount = 1_024L,
                mimeType = "audio/flac",
                sourceSampleRate = 44_100,
                sourceChannelCount = 2,
                sourceDurationUs = 2_000_000L,
            )
        }
    }
}
