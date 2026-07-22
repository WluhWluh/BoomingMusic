package com.mardous.booming.separation.cache.v2

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
    fun `completion publishes validated output before releasing the run lease`() {
        val fixture = fixture()
        val run = fixture.beginReady()
        val preparation = fixture.preparation(first = run, state = SourceSeparationSegmentState.Ready)
        fixture.coordinator.updatePreparation(run, preparation)

        val completed = fixture.coordinator.complete(run, fixture.result(preparation))

        assertEquals(SourceSeparationCacheManifestState.Completed, completed.state)
        assertEquals(SourceSeparationCacheValidationResult.Valid, fixture.store.validateCompletedEntry(completed))
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
                modelVariant = null,
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
