package com.mardous.booming.separation

import com.mardous.booming.separation.cache.SourceSeparationSegmentPlan
import com.mardous.booming.separation.cache.SourceSeparationSegmentState
import com.mardous.booming.separation.cache.v2.SourceSeparationAdmittedGpuRuntimeIdentity
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheContractSnapshot
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheManifestState
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheModelAvailability
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheModelAvailabilityProvider
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRoot
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRootLocation
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunCoordinator
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunTransitionType
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheSongLocator
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheSourceDiagnostics
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheSourceIdentity
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheSourcePreflight
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheStore
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwareCacheRepository
import com.mardous.booming.separation.cache.v2.SourceSeparationResolvedCacheModel
import com.mardous.booming.separation.model.MdxInferenceBackend
import com.mardous.booming.separation.model.MdxModelArtifact
import com.mardous.booming.separation.model.MdxRangePreparation
import com.mardous.booming.separation.model.MdxRangeProgress
import com.mardous.booming.separation.model.MdxRangeSeparationResult
import com.mardous.booming.separation.model.MdxRangeTimingReport
import com.mardous.booming.separation.model.MdxRuntimeDiagnostics
import com.mardous.booming.separation.model.MdxRuntimeSettings
import com.mardous.booming.separation.model.MdxSourceDecodeDiagnostics
import com.mardous.booming.separation.model.MdxSourceDecodeMode
import com.mardous.booming.separation.model.contract.SourceSeparationModelCatalog
import com.mardous.booming.separation.model.contract.SourceSeparationModelContract
import com.mardous.booming.separation.model.contract.SourceSeparationModelMetadata
import com.mardous.booming.separation.model.contract.toMdxExecutionProfile
import com.mardous.booming.separation.model.litert.MdxLiteRtBoundedGpuContract
import com.mardous.booming.separation.model.preset.SourceSeparationInstalledPreset
import com.mardous.booming.separation.model.preset.SourceSeparationInstalledPresetOrigin
import com.mardous.booming.separation.model.preset.SourceSeparationPresetBindingKind
import com.mardous.booming.separation.process.InProcessSourceSeparationExecutionHost
import com.mardous.booming.separation.process.SourceSeparationExecutionBackendPolicy
import com.mardous.booming.separation.process.SourceSeparationExecutionHost
import com.mardous.booming.separation.process.SourceSeparationExecutionHostControlResult
import com.mardous.booming.separation.process.SourceSeparationExecutionHostEvent
import com.mardous.booming.separation.process.SourceSeparationExecutionHostEventPayload
import com.mardous.booming.separation.process.SourceSeparationExecutionHostLifecycle
import com.mardous.booming.separation.process.SourceSeparationExecutionHostMode
import com.mardous.booming.separation.process.SourceSeparationExecutionHostRequest
import com.mardous.booming.separation.process.SourceSeparationExecutionHostStartResult
import com.mardous.booming.separation.process.ipc.SourceSeparationExecutionIpcCodec
import com.mardous.booming.separation.process.ipc.SourceSeparationIpcStartCommand
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SourceSeparationModelAwareEngineTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `construction gate and missing active model stop before preflight`() {
        val fixture = fixture()
        val blocked = fixture.engine(constructionGate = false)

        assertThrows(IllegalStateException::class.java) {
            blocked.separate(fixture.input)
        }
        assertEquals(0, fixture.preflightCount)

        fixture.activeModel = null
        assertEquals(
            SourceSeparationModelAwareEngineResult.ActiveModelUnavailable,
            fixture.engine().separate(fixture.input),
        )
        assertEquals(0, fixture.preflightCount)
    }

    @Test
    fun `cancellation records resumable canceled state and releases run lease`() {
        val fixture = fixture()
        val engine = fixture.engine { request ->
            val preparation = fixture.prepare(request)
            request.onPrepared(preparation)
            request.onSegmentStateChanged(0, SourceSeparationSegmentState.Running)
            throw CancellationException("test cancellation")
        }

        assertThrows(CancellationException::class.java) {
            engine.separate(fixture.input)
        }

        val manifest = fixture.store.listManifests().single()
        assertEquals(SourceSeparationCacheManifestState.Canceled, manifest.state)
        assertEquals(SourceSeparationSegmentState.Queued, manifest.segmentPlan?.segments?.first()?.state)
        assertFalse(fixture.repository.isLeased(manifest.cacheKey))
    }

    @Test
    fun `inference failure records failed state without publishing completed output`() {
        val fixture = fixture()
        val engine = fixture.engine { request ->
            request.onPrepared(fixture.prepare(request))
            throw IllegalStateException("injected inference failure")
        }

        assertThrows(IllegalStateException::class.java) {
            engine.separate(fixture.input)
        }

        val manifest = fixture.store.listManifests().single()
        assertEquals(SourceSeparationCacheManifestState.Failed, manifest.state)
        assertEquals("injected inference failure", manifest.error?.message)
        assertFalse(fixture.repository.isLeased(manifest.cacheKey))
        assertFalse(fixture.store.resolveEntryPath(manifest.cacheKey, "completed/vocals.wav").exists())
    }

    @Test
    fun `paused run resumes exact ready windows and then completes`() {
        val fixture = fixture()
        var attempt = 0
        val engine = fixture.engine { request ->
            attempt += 1
            if (attempt == 1) {
                val preparation = fixture.prepare(request)
                request.onPrepared(preparation)
                request.onSegmentStateChanged(0, SourceSeparationSegmentState.Ready)
                request.onSegmentStateChanged(1, SourceSeparationSegmentState.Running)
                throw SourceSeparationPausedException()
            }
            val resume = requireNotNull(request.workspace.resumeState)
            assertEquals(SourceSeparationSegmentState.Ready, resume.segmentPlan.segments[0].state)
            assertEquals(SourceSeparationSegmentState.Queued, resume.segmentPlan.segments[1].state)
            fixture.complete(request, fixture.prepare(request, preserveFiles = true))
        }

        assertThrows(SourceSeparationPausedException::class.java) {
            engine.separate(fixture.input)
        }
        val completed = engine.separate(fixture.input)

        assertTrue(completed is SourceSeparationModelAwareEngineResult.Completed)
        assertEquals(SourceSeparationCacheManifestState.Completed, fixture.store.listManifests().single().state)
        assertEquals(2, attempt)
    }

    @Test
    fun `active model switch affects only the next run`() {
        val fixture = fixture()
        val firstModel = requireNotNull(fixture.activeModel)
        val secondModel = fixture.resolvedModel("uvr_mdxnet_kara")
        var runCount = 0
        val engine = fixture.engine { request ->
            runCount += 1
            if (runCount == 1) fixture.activeModel = secondModel
            fixture.complete(request, fixture.prepare(request))
        }

        val first = engine.separate(fixture.input) as SourceSeparationModelAwareEngineResult.Completed
        val second = engine.separate(fixture.input) as SourceSeparationModelAwareEngineResult.Completed

        assertEquals(firstModel.contract.modelId, first.manifest.identity.modelId)
        assertEquals(secondModel.contract.modelId, second.manifest.identity.modelId)
        assertNotEquals(first.manifest.cacheKey, second.manifest.cacheKey)
        assertEquals(2, fixture.repository.entries().size)
    }

    @Test
    fun `gpu to cpu recreation retains one cache run lease`() {
        val fixture = fixture()
        val events = mutableListOf<String>()
        val engine = fixture.engine { request ->
            val key = request.workspace.identity.cacheKey
            events += "gpu-active"
            assertTrue(fixture.repository.isLeased(key))
            assertEquals(null, fixture.repository.tryAcquireRunWrite(request.workspace.identity))
            events += "gpu-closed"
            assertTrue(fixture.repository.isLeased(key))
            events += "cpu-created"
            fixture.complete(
                request = request,
                preparation = fixture.prepare(request),
                backend = MdxInferenceBackend.LiteRtCpu,
                detail = "GPU invocation failed; CPU fallback accepted.",
            )
        }

        val completed = engine.separate(fixture.input)
            as SourceSeparationModelAwareEngineResult.Completed

        assertEquals(listOf("gpu-active", "gpu-closed", "cpu-created"), events)
        assertEquals("LiteRtCpu", completed.manifest.runtimeRecords.single().backend)
        assertFalse(fixture.repository.isLeased(completed.manifest.cacheKey))
    }

    @Test
    fun `completed exact entry bypasses executor but still measures preflight`() {
        val fixture = fixture()
        var executionCount = 0
        var preparedManifestState: SourceSeparationCacheManifestState? = null
        val engine = fixture.engine { request ->
            executionCount += 1
            fixture.complete(request, fixture.prepare(request))
        }

        engine.separate(
            input = fixture.input,
            onPrepared = { preparedManifestState = it.state },
        )
        val second = engine.separate(fixture.input)

        assertTrue(second is SourceSeparationModelAwareEngineResult.AlreadyCompleted)
        assertEquals(1, executionCount)
        assertEquals(SourceSeparationCacheManifestState.Running, preparedManifestState)
        assertEquals(2, fixture.preflightCount)
    }

    @Test
    fun `in process host preserves result and emits serializable monotonic events`() {
        val fixture = fixture()
        val events = mutableListOf<SourceSeparationExecutionHostEvent>()
        lateinit var executorResult: MdxRangeSeparationResult
        val engine = fixture.engine(
            runIdFactory = { "run-parity" },
            eventSink = { event ->
                Json.encodeToString(SourceSeparationExecutionHostEvent.serializer(), event)
                events += event
            },
        ) { request ->
            request.onProgress(
                MdxRangeProgress(
                    completedWindows = 1,
                    totalWindows = 2,
                    stage = "test-progress",
                )
            )
            fixture.complete(request, fixture.prepare(request)).also {
                executorResult = it
            }
        }

        val completed = engine.separate(fixture.input)
            as SourceSeparationModelAwareEngineResult.Completed

        assertSame(executorResult, completed.result)
        assertEquals(SourceSeparationExecutionHostMode.InProcess, completed.hostDiagnostics.mode)
        assertEquals(SourceSeparationExecutionHostLifecycle.Completed,
            completed.hostDiagnostics.lifecycle)
        assertEquals("run-parity", completed.hostDiagnostics.runId)
        assertEquals(1L, completed.hostDiagnostics.processGeneration)
        assertEquals("LiteRtCpu", completed.hostDiagnostics.backend)
        assertEquals(events.size.toLong(), completed.hostDiagnostics.latestEventSequence)
        assertEquals((1L..events.size.toLong()).toList(), events.map { it.sequence })
        assertTrue(events.all { it.runId == "run-parity" && it.processGeneration == 1L })
        assertEquals(
            listOf(
                SourceSeparationExecutionHostEventPayload.Accepted::class,
                SourceSeparationExecutionHostEventPayload.Progress::class,
                SourceSeparationExecutionHostEventPayload.Prepared::class,
                SourceSeparationExecutionHostEventPayload.SegmentStateChanged::class,
                SourceSeparationExecutionHostEventPayload.SegmentStateChanged::class,
                SourceSeparationExecutionHostEventPayload.Completed::class,
            ),
            events.map { it.payload::class },
        )
        assertEquals("vocals", completed.result.vocalsFile.readText())
        assertEquals("instrumental", completed.result.instrumentalFile.readText())
        assertEquals(SourceSeparationCacheManifestState.Completed, completed.manifest.state)
        assertFalse(fixture.repository.isLeased(completed.manifest.cacheKey))
    }

    @Test
    fun `in process host rejects a descriptor that differs from the admitted source`() {
        val fixture = fixture()
        val executor = SourceSeparationModelAwareRangeExecutor { request ->
            fixture.complete(request, fixture.prepare(request))
        }
        val delegate = InProcessSourceSeparationExecutionHost(executor)
        val mutatingHost = object : SourceSeparationExecutionHost by delegate {
            override fun start(
                request: SourceSeparationExecutionHostRequest,
            ) = delegate.start(
                request.copy(
                    descriptor = request.descriptor.copy(
                        source = request.descriptor.source.copy(
                            sourceUri = "content://media/wrong",
                        ),
                    ),
                )
            )
        }
        val engine = fixture.engine(
            executionHost = mutatingHost,
            runIdFactory = { "run-descriptor" },
            executor = executor,
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            engine.separate(fixture.input)
        }

        assertTrue(error.message.orEmpty().contains("admitted source"))
        val manifest = fixture.store.listManifests().single()
        assertEquals(SourceSeparationCacheManifestState.Failed, manifest.state)
        assertFalse(fixture.repository.isLeased(manifest.cacheKey))
    }

    @Test
    fun `execution backend policy is frozen into the host descriptor`() {
        val fixture = fixture()
        var observedRequestPolicy: SourceSeparationExecutionBackendPolicy? = null
        val executor = SourceSeparationModelAwareRangeExecutor { request ->
            observedRequestPolicy = request.backendPolicy
            fixture.complete(request, fixture.prepare(request))
        }
        val delegate = InProcessSourceSeparationExecutionHost(executor)
        var observedPolicy: SourceSeparationExecutionBackendPolicy? = null
        var observedTryGpu: Boolean? = null
        val observingHost = object : SourceSeparationExecutionHost by delegate {
            override fun start(
                request: SourceSeparationExecutionHostRequest,
            ) = delegate.start(
                SourceSeparationExecutionIpcCodec.decodeStartCommand(
                    SourceSeparationExecutionIpcCodec.encodeStartCommand(
                        SourceSeparationIpcStartCommand(
                            commandId = "start-policy",
                            descriptor = request.descriptor,
                        )
                    )
                ).descriptor.let { descriptor ->
                    observedPolicy = descriptor.runtime.backendPolicy
                    observedTryGpu = descriptor.runtime.tryGpu
                    request.copy(descriptor = descriptor)
                }
            )
        }

        fixture.engine(
            executionHost = observingHost,
            executionBackendPolicy = SourceSeparationExecutionBackendPolicy.Auto,
            executor = executor,
        ).separate(
            input = fixture.input,
            executionBackendPolicy = SourceSeparationExecutionBackendPolicy.Cpu,
        )

        assertEquals(SourceSeparationExecutionBackendPolicy.Cpu, observedPolicy)
        assertEquals(false, observedTryGpu)
        assertEquals(SourceSeparationExecutionBackendPolicy.Cpu, observedRequestPolicy)
        assertEquals(false, fixture.currentRunJournal().request.tryGpu)
        assertEquals(null, fixture.currentRunJournal().request.gpuRuntimeIdentity)
    }

    @Test
    fun `run class and background policy survive admission IPC and diagnostics`() {
        SourceSeparationExecutionRunClass.entries.forEach { runClass ->
            val fixture = fixture()
            var observedDescriptorClass: SourceSeparationExecutionRunClass? = null
            var observedDescriptorPolicy: SourceSeparationBackgroundPolicy? = null
            var observedRequestClass: SourceSeparationExecutionRunClass? = null
            var observedRequestPolicy: SourceSeparationBackgroundPolicy? = null
            val executor = SourceSeparationModelAwareRangeExecutor { request ->
                observedRequestClass = request.runClass
                observedRequestPolicy = request.backgroundPolicy
                fixture.complete(request, fixture.prepare(request))
            }
            val delegate = InProcessSourceSeparationExecutionHost(executor)
            val observingHost = object : SourceSeparationExecutionHost by delegate {
                override fun start(
                    request: SourceSeparationExecutionHostRequest,
                ): SourceSeparationExecutionHostStartResult {
                    val descriptor = SourceSeparationExecutionIpcCodec.decodeStartCommand(
                        SourceSeparationExecutionIpcCodec.encodeStartCommand(
                            SourceSeparationIpcStartCommand(
                                commandId = "start-${runClass.name}",
                                descriptor = request.descriptor,
                            )
                        )
                    ).descriptor
                    observedDescriptorClass = descriptor.runtime.runClass
                    observedDescriptorPolicy = descriptor.runtime.backgroundPolicy
                    return delegate.start(request.copy(descriptor = descriptor))
                }
            }

            val completed = fixture.engine(
                executionHost = observingHost,
                executionBackendPolicy = SourceSeparationExecutionBackendPolicy.Cpu,
                executor = executor,
            ).separate(
                input = fixture.input,
                executionBackendPolicy = SourceSeparationExecutionBackendPolicy.Cpu,
                runClass = runClass,
            ) as SourceSeparationModelAwareEngineResult.Completed

            assertEquals(runClass, observedDescriptorClass)
            assertEquals(runClass.backgroundPolicy, observedDescriptorPolicy)
            assertEquals(runClass, observedRequestClass)
            assertEquals(runClass.backgroundPolicy, observedRequestPolicy)
            assertEquals(runClass, completed.hostDiagnostics.runClass)
            assertEquals(runClass.backgroundPolicy, completed.hostDiagnostics.backgroundPolicy)
            val journal = fixture.currentRunJournal()
            assertEquals(runClass, journal.request.runClass)
            assertEquals(runClass.backgroundPolicy, journal.request.backgroundPolicy)
            assertTrue(journal.transitions.all { it.runClass == runClass })
            assertTrue(journal.transitions.all {
                it.backgroundPolicy == runClass.backgroundPolicy
            })
        }
    }

    @Test
    fun `bounded GPU runtime identity survives admission and IPC`() {
        val fixture = fixture()
        val executor = SourceSeparationModelAwareRangeExecutor { request ->
            fixture.complete(request, fixture.prepare(request))
        }
        val delegate = InProcessSourceSeparationExecutionHost(executor)
        var observedRuntimeIdentity: SourceSeparationAdmittedGpuRuntimeIdentity? = null
        val observingHost = object : SourceSeparationExecutionHost by delegate {
            override fun start(
                request: SourceSeparationExecutionHostRequest,
            ): SourceSeparationExecutionHostStartResult {
                val descriptor = SourceSeparationExecutionIpcCodec.decodeStartCommand(
                    SourceSeparationExecutionIpcCodec.encodeStartCommand(
                        SourceSeparationIpcStartCommand(
                            commandId = "start-bounded-runtime",
                            descriptor = request.descriptor,
                        )
                    )
                ).descriptor
                observedRuntimeIdentity = descriptor.runtime.gpuRuntimeIdentity
                return delegate.start(request.copy(descriptor = descriptor))
            }
        }

        val result = fixture.engine(
            executionHost = observingHost,
            executionBackendPolicy = SourceSeparationExecutionBackendPolicy.Auto,
            executor = executor,
        ).separate(fixture.input)

        assertTrue(result is SourceSeparationModelAwareEngineResult.Completed)
        val identity = requireNotNull(observedRuntimeIdentity)
        assertEquals(MdxLiteRtBoundedGpuContract.PROFILE_ID, identity.profileId)
        assertEquals(MdxLiteRtBoundedGpuContract.ARTIFACT_VERSION, identity.artifactVersion)
        assertEquals(
            MdxLiteRtBoundedGpuContract.CAPABILITY_SCHEMA_VERSION,
            identity.capabilitySchemaVersion,
        )
        assertEquals(MdxLiteRtBoundedGpuContract.BACKEND, identity.backend)
        assertEquals(MdxLiteRtBoundedGpuContract.PRECISION, identity.precision)
        assertEquals(MdxLiteRtBoundedGpuContract.KERNEL_BATCH_SIZE, identity.kernelBatchSize)
        assertEquals(
            MdxLiteRtBoundedGpuContract.COMMAND_QUEUE_WINDOW_SIZE,
            identity.commandQueueWindowSize,
        )
        assertEquals(identity, fixture.currentRunJournal().request.gpuRuntimeIdentity)
        assertEquals(5, fixture.currentRunJournal().journalSchemaVersion)
    }

    @Test
    fun `latched GPU fallback resumes on CPU with exact admitted identity`() {
        val fixture = fixture()
        val observedPolicies = mutableListOf<SourceSeparationExecutionBackendPolicy>()
        val observedTryGpu = mutableListOf<Boolean>()
        val observedRuntimeIdentities = mutableListOf<SourceSeparationAdmittedGpuRuntimeIdentity?>()
        val observedLatches = mutableListOf<SourceSeparationGpuFallbackLatch?>()
        val events = mutableListOf<SourceSeparationExecutionHostEvent>()
        val latch = SourceSeparationGpuFallbackLatch(
            stage = "GpuInvocation",
            reason = "Injected recoverable GPU failure.",
        )
        var attempt = 0
        val engine = fixture.engine(
            eventSink = events::add,
        ) { request ->
            attempt += 1
            observedPolicies += request.backendPolicy
            observedTryGpu += request.tryGpu
            observedRuntimeIdentities += request.gpuRuntimeIdentity
            observedLatches += request.gpuFallbackLatch
            val preparation = fixture.prepare(request, preserveFiles = attempt > 1)
            if (attempt == 1) {
                request.onGpuFallbackLatched(latch)
                request.onGpuFallbackLatched(latch)
                request.onPrepared(preparation)
                throw SourceSeparationPausedException()
            }
            fixture.complete(request, preparation)
        }

        assertThrows(SourceSeparationPausedException::class.java) {
            engine.separate(fixture.input)
        }
        val pausedJournal = fixture.currentRunJournal()
        val admittedIdentity = requireNotNull(pausedJournal.request.gpuRuntimeIdentity)
        assertEquals(latch, pausedJournal.request.gpuFallbackLatch)
        assertTrue(pausedJournal.request.tryGpu)
        assertEquals(
            1,
            pausedJournal.transitions.count {
                it.type == SourceSeparationCacheRunTransitionType.GpuFallbackLatched
            },
        )

        val completed = engine.separate(
            input = fixture.input,
            executionBackendPolicy = SourceSeparationExecutionBackendPolicy.Auto,
        )

        assertTrue(completed is SourceSeparationModelAwareEngineResult.Completed)
        assertEquals(
            listOf(
                SourceSeparationExecutionBackendPolicy.Auto,
                SourceSeparationExecutionBackendPolicy.Cpu,
            ),
            observedPolicies,
        )
        assertEquals(listOf(true, true), observedTryGpu)
        assertEquals(listOf(admittedIdentity, admittedIdentity), observedRuntimeIdentities)
        assertEquals(listOf(null, latch), observedLatches)
        assertEquals(
            1,
            events.count {
                it.payload is SourceSeparationExecutionHostEventPayload.GpuFallbackLatched
            },
        )
        val completedJournal = fixture.currentRunJournal()
        assertEquals(latch, completedJournal.request.gpuFallbackLatch)
        assertEquals(admittedIdentity, completedJournal.request.gpuRuntimeIdentity)
        assertTrue(completedJournal.request.tryGpu)
    }

    @Test
    fun `paused run keeps its admitted GPU policy when the next call changes`() {
        val fixture = fixture()
        val observedPolicies = mutableListOf<SourceSeparationExecutionBackendPolicy>()
        var attempt = 0
        val engine = fixture.engine { request ->
            attempt += 1
            observedPolicies += request.backendPolicy
            val preparation = fixture.prepare(request, preserveFiles = attempt > 1)
            if (attempt == 1) {
                request.onPrepared(preparation)
                throw SourceSeparationPausedException()
            }
            fixture.complete(request, preparation)
        }

        assertThrows(SourceSeparationPausedException::class.java) {
            engine.separate(
                input = fixture.input,
                executionBackendPolicy = SourceSeparationExecutionBackendPolicy.Cpu,
            )
        }
        assertEquals(false, fixture.currentRunJournal().request.tryGpu)

        val completed = engine.separate(
            input = fixture.input,
            executionBackendPolicy = SourceSeparationExecutionBackendPolicy.Auto,
        )

        assertTrue(completed is SourceSeparationModelAwareEngineResult.Completed)
        assertEquals(
            listOf(
                SourceSeparationExecutionBackendPolicy.Cpu,
                SourceSeparationExecutionBackendPolicy.Cpu,
            ),
            observedPolicies,
        )
        assertEquals(false, fixture.currentRunJournal().request.tryGpu)
    }

    @Test
    fun `engine rejects stale generation events and closes the host run`() {
        val fixture = fixture()
        val executor = SourceSeparationModelAwareRangeExecutor { request ->
            fixture.complete(request, fixture.prepare(request))
        }
        val delegate = InProcessSourceSeparationExecutionHost(
            rangeExecutor = executor,
            processGeneration = 7L,
        )
        val staleEventHost = object : SourceSeparationExecutionHost by delegate {
            override fun start(
                request: SourceSeparationExecutionHostRequest,
            ) = delegate.start(
                request.copy(
                    onEvent = { event ->
                        request.onEvent(event.copy(processGeneration = 6L))
                    },
                )
            )
        }
        val engine = fixture.engine(
            executionHost = staleEventHost,
            runIdFactory = { "run-stale-generation" },
            executor = executor,
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            engine.separate(fixture.input)
        }

        assertTrue(error.message.orEmpty().contains("stale run or generation"))
        assertEquals(null, delegate.snapshot("run-stale-generation", 7L))
        val manifest = fixture.store.listManifests().single()
        assertEquals(SourceSeparationCacheManifestState.Failed, manifest.state)
        assertFalse(fixture.repository.isLeased(manifest.cacheKey))
    }

    @Test
    fun `host connection failure does not admit a cache writer`() {
        val fixture = fixture()
        val executor = SourceSeparationModelAwareRangeExecutor { request ->
            fixture.complete(request, fixture.prepare(request))
        }
        val delegate = InProcessSourceSeparationExecutionHost(executor)
        val unavailableHost = object : SourceSeparationExecutionHost by delegate {
            override val processGeneration: Long
                get() = throw IllegalStateException("injected connection failure")
        }
        val engine = fixture.engine(
            executionHost = unavailableHost,
            executor = executor,
        )

        val error = assertThrows(IllegalStateException::class.java) {
            engine.separate(fixture.input)
        }

        assertEquals("injected connection failure", error.message)
        assertTrue(fixture.store.listManifests().isEmpty())
        assertTrue(fixture.repository.entries().isEmpty())
    }

    @Test
    fun `host pause command reaches the admitted run and preserves resumable state`() {
        val fixture = fixture()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = SourceSeparationModelAwareRangeExecutor { request ->
            request.onPrepared(fixture.prepare(request))
            request.onSegmentStateChanged(0, SourceSeparationSegmentState.Ready)
            request.onSegmentStateChanged(1, SourceSeparationSegmentState.Running)
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "Pause test timed out." }
            if (request.shouldPause()) throw SourceSeparationPausedException()
            fixture.complete(request, fixture.prepare(request, preserveFiles = true))
        }
        val host = InProcessSourceSeparationExecutionHost(executor, processGeneration = 9L)
        val engine = fixture.engine(
            executionHost = host,
            runIdFactory = { "run-pause" },
            executor = executor,
        )
        val thread = Executors.newSingleThreadExecutor()

        try {
            val future = thread.submit<SourceSeparationModelAwareEngineResult> {
                engine.separate(fixture.input)
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertEquals(
                SourceSeparationExecutionHostControlResult.StaleGeneration,
                host.pause("run-pause", 8L),
            )
            assertEquals(
                SourceSeparationExecutionHostControlResult.StaleRun,
                host.pause("another-run", 9L),
            )
            assertEquals(
                SourceSeparationExecutionHostControlResult.Applied,
                host.pause("run-pause", 9L),
            )
            assertEquals(
                SourceSeparationExecutionHostControlResult.AlreadyApplied,
                host.pause("run-pause", 9L),
            )
            val snapshot = requireNotNull(host.snapshot("run-pause", 9L))
            assertEquals("run-pause", snapshot.diagnostics.runId)
            assertEquals(9L, snapshot.diagnostics.processGeneration)
            assertEquals(SourceSeparationExecutionHostLifecycle.Prepared,
                snapshot.diagnostics.lifecycle)
            assertTrue(snapshot.diagnostics.latestEventSequence > 0L)
            release.countDown()
            val error = assertThrows(ExecutionException::class.java) {
                future.get(5, TimeUnit.SECONDS)
            }
            assertTrue(error.cause is SourceSeparationPausedException)
        } finally {
            release.countDown()
            thread.shutdownNow()
        }

        val manifest = fixture.store.listManifests().single()
        assertEquals(SourceSeparationCacheManifestState.Running, manifest.state)
        assertEquals(SourceSeparationSegmentState.Ready,
            manifest.segmentPlan?.segments?.get(0)?.state)
        assertEquals(SourceSeparationSegmentState.Queued,
            manifest.segmentPlan?.segments?.get(1)?.state)
        assertFalse(fixture.repository.isLeased(manifest.cacheKey))
        assertEquals(null, host.snapshot("run-pause", 9L))
        host.close()
        assertEquals(
            SourceSeparationExecutionHostControlResult.HostClosed,
            host.pause("run-pause", 9L),
        )
    }

    @Test
    fun `host cancel command reaches the admitted run and releases its only lease`() {
        val fixture = fixture()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = SourceSeparationModelAwareRangeExecutor { request ->
            request.onPrepared(fixture.prepare(request))
            request.onSegmentStateChanged(0, SourceSeparationSegmentState.Running)
            assertTrue(fixture.repository.isLeased(request.workspace.identity.cacheKey))
            assertEquals(null, fixture.repository.tryAcquireRunWrite(request.workspace.identity))
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "Cancel test timed out." }
            if (request.shouldCancel()) throw CancellationException("host cancel")
            fixture.complete(request, fixture.prepare(request, preserveFiles = true))
        }
        val host = InProcessSourceSeparationExecutionHost(executor, processGeneration = 11L)
        val engine = fixture.engine(
            executionHost = host,
            runIdFactory = { "run-cancel" },
            executor = executor,
        )
        val thread = Executors.newSingleThreadExecutor()

        try {
            val future = thread.submit<SourceSeparationModelAwareEngineResult> {
                engine.separate(fixture.input)
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertEquals(
                SourceSeparationExecutionHostControlResult.Applied,
                host.cancel("run-cancel", 11L),
            )
            assertEquals(
                SourceSeparationExecutionHostControlResult.AlreadyApplied,
                host.cancel("run-cancel", 11L),
            )
            release.countDown()
            val error = assertThrows(ExecutionException::class.java) {
                future.get(5, TimeUnit.SECONDS)
            }
            assertTrue(error.cause is CancellationException)
        } finally {
            release.countDown()
            thread.shutdownNow()
        }

        val manifest = fixture.store.listManifests().single()
        assertEquals(SourceSeparationCacheManifestState.Canceled, manifest.state)
        assertEquals(SourceSeparationSegmentState.Queued,
            manifest.segmentPlan?.segments?.first()?.state)
        assertFalse(fixture.repository.isLeased(manifest.cacheKey))
        assertEquals(null, host.snapshot("run-cancel", 11L))
    }

    private fun fixture(): EngineFixture {
        val root = temporary.newFolder().absoluteFile
        val store = SourceSeparationCacheStore(
            SourceSeparationCacheRoot(root, SourceSeparationCacheRootLocation.InternalCache),
            nowEpochMs = { 10L },
        )
        val repository = SourceSeparationModelAwareCacheRepository(
            store = store,
            modelAvailability = SourceSeparationCacheModelAvailabilityProvider {
                SourceSeparationCacheModelAvailability.InstalledExact
            },
            nowEpochMs = { 10L },
        )
        val fixture = EngineFixture(
            root = root,
            store = store,
            repository = repository,
            coordinator = SourceSeparationCacheRunCoordinator(store, repository) { 10L },
        )
        fixture.activeModel = fixture.resolvedModel("uvr_mdxnet_3_9662")
        return fixture
    }

    private class EngineFixture(
        val root: File,
        val store: SourceSeparationCacheStore,
        val repository: SourceSeparationModelAwareCacheRepository,
        val coordinator: SourceSeparationCacheRunCoordinator,
    ) {
        var activeModel: SourceSeparationResolvedCacheModel? = null
        var preflightCount: Int = 0
        private var runIdSequence: Int = 0

        val input = SourceSeparationModelAwareSongInput(
            sourceUri = "content://media/42",
            displayName = "song.flac",
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
        )

        fun engine(
            constructionGate: Boolean = true,
            executionHost: SourceSeparationExecutionHost? = null,
            executionBackendPolicy: SourceSeparationExecutionBackendPolicy =
                SourceSeparationExecutionBackendPolicy.Auto,
            runIdFactory: () -> String = { "test-run-${++runIdSequence}" },
            eventSink: (SourceSeparationExecutionHostEvent) -> Unit = {},
            executor: SourceSeparationModelAwareRangeExecutor = SourceSeparationModelAwareRangeExecutor {
                request -> complete(request, prepare(request))
            },
        ) = SourceSeparationModelAwareEngine(
            activeModelResolver = { activeModel },
            preflightResolver = SourceSeparationModelAwarePreflightResolver { _, shouldCancel ->
                if (shouldCancel()) throw CancellationException("preflight canceled")
                preflightCount += 1
                SourceSeparationCacheSourcePreflight(sourceIdentity(), elapsedMs = 7L)
            },
            coordinator = coordinator,
            rangeExecutor = executor,
            executionHost = executionHost ?: InProcessSourceSeparationExecutionHost(executor),
            executionBackendPolicy = executionBackendPolicy,
            constructionGate = { constructionGate },
            runIdFactory = runIdFactory,
            executionHostEventSink = eventSink,
        )

        fun resolvedModel(modelId: String): SourceSeparationResolvedCacheModel {
            val contract = catalog.contracts.single { it.modelId == modelId }
            val snapshot = SourceSeparationCacheContractSnapshot.fromOfficial(contract)
            val artifactFile = File(root, contract.artifact.fileName).apply {
                if (!exists()) writeText(modelId)
            }
            return SourceSeparationResolvedCacheModel(
                installed = SourceSeparationInstalledPreset(
                    modelId = modelId,
                    displayName = contract.displayName,
                    file = artifactFile,
                    byteSize = contract.artifact.byteSize,
                    sha256 = contract.artifact.sha256,
                    origin = SourceSeparationInstalledPresetOrigin.OfficialDownload,
                    bindingKind = SourceSeparationPresetBindingKind.Official,
                    contractId = contract.contractId,
                    sidecarContract = null,
                    customProfile = null,
                    installedAtEpochMs = 1L,
                ),
                contract = snapshot,
                artifact = MdxModelArtifact(
                    file = artifactFile,
                    byteSize = contract.artifact.byteSize,
                    sha256 = contract.artifact.sha256,
                ),
                executionProfile = contract.toMdxExecutionProfile(catalog.runtimeQualifications),
            )
        }

        fun currentRunJournal() = requireNotNull(
            store.readRunJournal(store.listManifests().single().cacheKey)
        )

        fun prepare(
            request: SourceSeparationModelAwareExecutionRequest,
            preserveFiles: Boolean = false,
        ): MdxRangePreparation {
            val vocals = File(request.workspace.workDirectory, "vocals.wav").apply {
                if (!preserveFiles || !exists()) writeText("vocals")
            }
            val instrumental = File(request.workspace.workDirectory, "instrumental.wav").apply {
                if (!preserveFiles || !exists()) writeText("instrumental")
            }
            val timing = File(request.workspace.workDirectory, "timing.txt").apply {
                if (!preserveFiles || !exists()) writeText("timing")
            }
            val plan = SourceSeparationSegmentPlan.build(
                rangeStartFrame = 0,
                rangeEndFrame = 88_200,
                sampleRate = 44_100,
                generationSize = 44_100,
                trim = 1_024,
                chunkSize = 46_148,
                defaultState = SourceSeparationSegmentState.Queued,
            )
            plan.segments.forEach { segment ->
                listOf(segment.vocalsPath, segment.instrumentalPath).forEach { path ->
                    store.resolveEntryPath(request.workspace.identity.cacheKey, path).apply {
                        parentFile?.mkdirs()
                        if (!preserveFiles || !exists()) writeText(path)
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
                sourceAudioFingerprint = request.workspace.identity.source.audioFingerprint,
                sourceFrameCount = 88_200,
                sourceSampleRate = 44_100,
                sourceChannelCount = 2,
                outputSampleRate = 44_100,
                segmentPlan = plan,
            )
        }

        fun complete(
            request: SourceSeparationModelAwareExecutionRequest,
            preparation: MdxRangePreparation,
            backend: MdxInferenceBackend = MdxInferenceBackend.LiteRtCpu,
            detail: String = "test",
        ): MdxRangeSeparationResult {
            request.onPrepared(preparation)
            preparation.segmentPlan.segments.forEach { segment ->
                request.onSegmentStateChanged(segment.index, SourceSeparationSegmentState.Ready)
            }
            val completedPlan = preparation.segmentPlan.copy(
                segments = preparation.segmentPlan.segments.map { segment ->
                    segment.copy(state = SourceSeparationSegmentState.Ready)
                },
            )
            val runtimeDiagnostics = MdxRuntimeDiagnostics(
                runtimeName = "fake-litert",
                backend = backend,
                cpuThreads = if (backend == MdxInferenceBackend.LiteRtCpu) 4 else null,
                detail = detail,
            )
            val decodeDiagnostics = MdxSourceDecodeDiagnostics(
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
                runtimeSettings = request.runtimeSettings,
                runtimeDiagnostics = runtimeDiagnostics,
                executionProfile = request.model.executionProfile,
                sourceDecodeDiagnostics = decodeDiagnostics,
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
                segmentPlan = completedPlan,
                timingReport = timingReport,
                runtimeSettings = request.runtimeSettings,
                runtimeDiagnostics = runtimeDiagnostics,
                modelVariant = null,
                executionProfile = request.model.executionProfile,
                sourceDecodeDiagnostics = decodeDiagnostics,
            )
        }

        private fun sourceIdentity() = SourceSeparationCacheSourceIdentity(
            audioFingerprint = "encoded-samples-v1:${"a".repeat(64)}",
            encodedSampleCount = 100L,
            encodedByteCount = 1_024L,
            mimeType = "audio/flac",
            sourceSampleRate = 44_100,
            sourceChannelCount = 2,
            sourceDurationUs = 2_000_000L,
        )
    }

    companion object {
        private lateinit var catalog: SourceSeparationModelCatalog

        @JvmStatic
        @BeforeClass
        fun loadCatalog() {
            val resource = requireNotNull(
                SourceSeparationModelAwareEngineTest::class.java.classLoader
                    ?.getResourceAsStream(SourceSeparationModelMetadata.CATALOG_ASSET_PATH),
            )
            catalog = resource.use { input ->
                SourceSeparationModelMetadata.decodeCatalog(input.readBytes().toString(Charsets.UTF_8))
            }
        }
    }
}
