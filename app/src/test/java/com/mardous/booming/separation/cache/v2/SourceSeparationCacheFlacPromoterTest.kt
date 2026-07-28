package com.mardous.booming.separation.cache.v2

import com.mardous.booming.separation.SourceSeparationExecutionRunClass
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
import java.io.RandomAccessFile
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SourceSeparationCacheFlacPromoterTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `promotion publishes both flac files and indexes before wav cleanup`() {
        val fixture = fixture()
        val completed = fixture.completedManifest()
        val promoter = fixture.promoter()

        val promoted = promoter.promote(completed.cacheKey)
            as SourceSeparationCacheFlacPromotionResult.Completed

        assertTrue(promoted.manifest.output?.stems?.all { it.promotionValidated } == true)
        assertEquals(
            SourceSeparationCacheValidationResult.Valid,
            fixture.store.validateCompletedEntry(promoted.manifest),
        )
        val playback = requireNotNull(fixture.repository.openCompletedCache(completed.cacheKey))
        assertEquals("vocals.flac", playback.vocalsFile.name)
        assertEquals("instrumental.flac", playback.instrumentalFile.name)
        assertFalse(fixture.coordinator.cleanCompletedTemporaryFiles(completed.cacheKey))
        playback.close()
        assertTrue(fixture.coordinator.cleanCompletedTemporaryFiles(completed.cacheKey))
        assertFalse(fixture.store.resolveEntryPath(completed.cacheKey, "completed/vocals.wav").exists())
        assertEquals(
            SourceSeparationCacheValidationResult.Valid,
            fixture.store.validateCompletedEntry(
                requireNotNull(fixture.store.readManifest(completed.cacheKey)),
            ),
        )
    }

    @Test
    fun `playback read lease blocks promotion`() {
        val fixture = fixture()
        val completed = fixture.completedManifest()
        val playback = requireNotNull(fixture.repository.openCompletedCache(completed.cacheKey))

        assertEquals(
            SourceSeparationCacheFlacPromotionResult.Busy,
            fixture.promoter().promote(completed.cacheKey),
        )

        playback.close()
        assertTrue(
            fixture.promoter().promote(completed.cacheKey) is
                SourceSeparationCacheFlacPromotionResult.Completed,
        )
    }

    @Test
    fun `failed promotion leaves the completed wav manifest authoritative`() {
        val fixture = fixture()
        val completed = fixture.completedManifest()
        var encoded = 0
        val promoter = fixture.promoter { wav, flac, _, _, _ ->
            encoded += 1
            if (encoded == 2) error("injected encoder failure")
            flac.writeText(wav.readText())
            val index = File(flac.absolutePath + ".frames").apply { writeText("index") }
            SourceSeparationCacheEncodedFlac(flac, index)
        }

        assertThrows(IllegalStateException::class.java) {
            promoter.promote(completed.cacheKey)
        }

        val unchanged = requireNotNull(fixture.store.readManifest(completed.cacheKey))
        assertTrue(unchanged.output?.stems?.none { it.promotionValidated } == true)
        assertEquals(SourceSeparationCacheValidationResult.Valid, fixture.store.validateCompletedEntry(unchanged))
        assertFalse(
            fixture.store.resolveEntryPath(
                completed.cacheKey,
                SourceSeparationCacheFlacPromoter.PROMOTION_STAGING_DIRECTORY,
            ).exists(),
        )
        assertFalse(fixture.repository.isLeased(completed.cacheKey))
    }

    @Test
    fun `canceled promotion releases exclusive lease and remains retryable`() {
        val fixture = fixture()
        val completed = fixture.completedManifest()
        var shouldCancel = false
        val promoter = fixture.promoter { wav, flac, _, _, _ ->
            flac.writeText(wav.readText())
            val index = File(flac.absolutePath + ".frames").apply { writeText("index") }
            shouldCancel = true
            SourceSeparationCacheEncodedFlac(flac, index)
        }

        assertThrows(CancellationException::class.java) {
            promoter.promote(completed.cacheKey) { shouldCancel }
        }

        assertFalse(fixture.repository.isLeased(completed.cacheKey))
        assertTrue(
            fixture.promoter().promote(completed.cacheKey) is
                SourceSeparationCacheFlacPromotionResult.Completed,
        )
    }

    @Test
    fun `hydration can read flac during playback and its session blocks deletion`() {
        val fixture = fixture()
        val completed = fixture.completedManifest()
        fixture.promoter().promote(completed.cacheKey)
        val flacPlayback = requireNotNull(fixture.repository.openCompletedCache(completed.cacheKey))
        val hydrator = fixture.hydrator()

        assertTrue(
            hydrator.hydrate(completed.cacheKey) is SourceSeparationCacheHydrationResult.Completed,
        )
        flacPlayback.close()
        val hydrated = requireNotNull(hydrator.open(completed.cacheKey))
        assertEquals(88_200L * 2L * 2L, hydrated.vocalsPcmFile.length())
        assertEquals(SourceSeparationCacheMutationResult.Busy, fixture.repository.delete(completed.cacheKey))

        hydrated.close()
        assertEquals(
            SourceSeparationCacheMutationResult.Completed,
            fixture.repository.delete(completed.cacheKey),
        )
    }

    @Test
    fun `corrupt hydrated pcm is rejected and rebuilt atomically`() {
        val fixture = fixture()
        val completed = fixture.completedManifest()
        fixture.promoter().promote(completed.cacheKey)
        val hydrator = fixture.hydrator()
        hydrator.hydrate(completed.cacheKey)
        val marker = requireNotNull(
            fixture.store.readHydrationMarker(
                requireNotNull(fixture.store.readManifest(completed.cacheKey)),
            ),
        )
        fixture.store.resolveEntryPath(completed.cacheKey, marker.stems.first().pcmPath)
            .writeText("corrupt")

        assertEquals(null, hydrator.open(completed.cacheKey))
        assertTrue(
            hydrator.hydrate(completed.cacheKey) is SourceSeparationCacheHydrationResult.Completed,
        )
        requireNotNull(hydrator.open(completed.cacheKey)).close()
    }

    private fun fixture(): PromotionFixture {
        val store = SourceSeparationCacheStore(
            SourceSeparationCacheRoot(
                temporary.newFolder().absoluteFile,
                SourceSeparationCacheRootLocation.InternalCache,
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
        return PromotionFixture(
            store = store,
            repository = repository,
            coordinator = SourceSeparationCacheRunCoordinator(store, repository) { 10L },
        )
    }

    private data class PromotionFixture(
        val store: SourceSeparationCacheStore,
        val repository: SourceSeparationModelAwareCacheRepository,
        val coordinator: SourceSeparationCacheRunCoordinator,
    ) {
        fun promoter(
            encoder: SourceSeparationCacheFlacEncoder = SourceSeparationCacheFlacEncoder {
                    wav, flac, _, _, _ ->
                flac.writeText("flac:${wav.readText()}")
                val index = File(flac.absolutePath + ".frames").apply { writeText("index") }
                SourceSeparationCacheEncodedFlac(flac, index)
            },
        ) = SourceSeparationCacheFlacPromoter(
            store = store,
            repository = repository,
            encoder = encoder,
            nowEpochMs = { 10L },
        )

        fun hydrator() = SourceSeparationCacheHydrator(
            store = store,
            repository = repository,
            decoder = SourceSeparationCacheFlacDecoder {
                    _, pcm, _, channels, frames, shouldCancel ->
                if (shouldCancel()) throw CancellationException("test cancellation")
                pcm.parentFile?.mkdirs()
                RandomAccessFile(pcm, "rw").use { output ->
                    output.setLength(frames.toLong() * channels * Short.SIZE_BYTES)
                }
            },
            nowEpochMs = { 10L },
        )

        fun completedManifest(): SourceSeparationCacheManifest {
            val contract = SourceSeparationCacheContractSnapshot.fromOfficial(
                catalog.contracts.single { it.modelId == "uvr_mdxnet_3_9662" },
            )
            val request = SourceSeparationCacheRunRequest(
                identity = contract.identity(sourceIdentity()),
                contract = contract,
                song = SourceSeparationCacheSongLocator(
                    songId = 42L,
                    mediaUri = "content://media/42",
                    filePath = "/music/song.flac",
                    title = "Song",
                    artist = "Artist",
                    album = "Album",
                ),
                sourceDiagnostics = SourceSeparationCacheSourceDiagnostics(1_024L, 50L, 2_000L),
                runClass = SourceSeparationExecutionRunClass.PlaybackDemandWindow,
                backgroundPolicy =
                    SourceSeparationExecutionRunClass.PlaybackDemandWindow.backgroundPolicy,
                tryGpu = false,
                gpuRuntimeIdentity = null,
            )
            val run = (coordinator.begin(request) as SourceSeparationCacheRunStart.Ready).run
            val vocals = File(run.workDirectory, "vocals.wav").apply { writeText("vocals") }
            val instrumental = File(run.workDirectory, "instrumental.wav").apply {
                writeText("instrumental")
            }
            val timing = File(run.workDirectory, "timing.txt").apply { writeText("timing") }
            val plan = SourceSeparationSegmentPlan.build(
                rangeStartFrame = 0,
                rangeEndFrame = 88_200,
                sampleRate = 44_100,
                generationSize = 44_100,
                trim = 1_024,
                chunkSize = 46_148,
                defaultState = SourceSeparationSegmentState.Ready,
            )
            plan.segments.forEach { segment ->
                listOf(segment.vocalsPath, segment.instrumentalPath).forEach { path ->
                    store.resolveEntryPath(run.identity.cacheKey, path).apply {
                        parentFile?.mkdirs()
                        writeText(path)
                    }
                }
            }
            val preparation = MdxRangePreparation(
                vocals,
                instrumental,
                timing,
                0L,
                2_000L,
                88_200,
                2,
                run.identity.source.audioFingerprint,
                88_200,
                44_100,
                2,
                44_100,
                plan,
            )
            coordinator.updatePreparation(run, preparation)
            val executionProfile = catalog.contracts
                .single { it.modelId == "uvr_mdxnet_3_9662" }
                .toMdxExecutionProfile(catalog.runtimeQualifications)
            val runtime = MdxRuntimeDiagnostics(
                "fake-litert",
                MdxInferenceBackend.LiteRtCpu,
                4,
                "test",
            )
            val decode = MdxSourceDecodeDiagnostics(
                MdxSourceDecodeMode.Window,
                "test",
                "audio/flac",
                44_100,
                2,
                88_200,
                88_200,
                null,
            )
            val timingReport = MdxRangeTimingReport(
                2.0,
                2,
                1_000L,
                MdxRuntimeSettings(),
                runtime,
                executionProfile,
                decode,
                emptyMap(),
            )
            return coordinator.complete(
                run,
                MdxRangeSeparationResult(
                    vocals,
                    instrumental,
                    timing,
                    0L,
                    2_000L,
                    88_200,
                    2,
                    1_000L,
                    run.identity.source.audioFingerprint,
                    88_200,
                    44_100,
                    2,
                    44_100,
                    plan,
                    timingReport,
                    MdxRuntimeSettings(),
                    runtime,
                    null,
                    executionProfile,
                    decode,
                ),
            )
        }

        private fun sourceIdentity() = SourceSeparationCacheSourceIdentity(
            "encoded-samples-v1:${"a".repeat(64)}",
            100L,
            1_024L,
            "audio/flac",
            44_100,
            2,
            2_000_000L,
        )
    }

    companion object {
        private lateinit var catalog: SourceSeparationModelCatalog

        @JvmStatic
        @BeforeClass
        fun loadCatalog() {
            val resource = requireNotNull(
                SourceSeparationCacheFlacPromoterTest::class.java.classLoader
                    ?.getResourceAsStream(SourceSeparationModelMetadata.CATALOG_ASSET_PATH),
            )
            catalog = resource.use { input ->
                SourceSeparationModelMetadata.decodeCatalog(input.readBytes().toString(Charsets.UTF_8))
            }
        }
    }
}
