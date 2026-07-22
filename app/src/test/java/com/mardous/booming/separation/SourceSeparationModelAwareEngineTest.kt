package com.mardous.booming.separation

import com.mardous.booming.separation.cache.SourceSeparationSegmentPlan
import com.mardous.booming.separation.cache.SourceSeparationSegmentState
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheContractSnapshot
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheManifestState
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheModelAvailability
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheModelAvailabilityProvider
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRoot
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRootLocation
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunCoordinator
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
import com.mardous.booming.separation.model.preset.SourceSeparationInstalledPreset
import com.mardous.booming.separation.model.preset.SourceSeparationInstalledPresetOrigin
import com.mardous.booming.separation.model.preset.SourceSeparationPresetBindingKind
import java.io.File
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
            val resume = requireNotNull(request.run.resumeState)
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
            val key = request.run.identity.cacheKey
            events += "gpu-active"
            assertTrue(fixture.repository.isLeased(key))
            assertEquals(null, fixture.repository.tryAcquireRunWrite(request.run.identity))
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
            constructionGate = { constructionGate },
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

        fun prepare(
            request: SourceSeparationModelAwareExecutionRequest,
            preserveFiles: Boolean = false,
        ): MdxRangePreparation {
            val vocals = File(request.run.workDirectory, "vocals.wav").apply {
                if (!preserveFiles || !exists()) writeText("vocals")
            }
            val instrumental = File(request.run.workDirectory, "instrumental.wav").apply {
                if (!preserveFiles || !exists()) writeText("instrumental")
            }
            val timing = File(request.run.workDirectory, "timing.txt").apply {
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
                    store.resolveEntryPath(request.run.identity.cacheKey, path).apply {
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
                sourceAudioFingerprint = request.run.identity.source.audioFingerprint,
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
