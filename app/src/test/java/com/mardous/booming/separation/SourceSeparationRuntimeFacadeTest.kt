package com.mardous.booming.separation

import com.mardous.booming.data.model.Song
import com.mardous.booming.separation.cache.v2.SourceSeparationActiveCacheModelResolution
import com.mardous.booming.separation.cache.v2.SourceSeparationActiveCacheModelUnavailableReason
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheContractSnapshot
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheFlacPromoter
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheHydrator
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
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwareCacheStatus
import com.mardous.booming.separation.cache.v2.SourceSeparationResolvedCacheModel
import com.mardous.booming.separation.model.MdxModelArtifact
import com.mardous.booming.separation.model.contract.SourceSeparationModelCatalog
import com.mardous.booming.separation.model.contract.SourceSeparationModelMetadata
import com.mardous.booming.separation.model.contract.toMdxExecutionProfile
import com.mardous.booming.separation.model.preset.SourceSeparationActiveModelReference
import com.mardous.booming.separation.model.preset.SourceSeparationInstalledPreset
import com.mardous.booming.separation.model.preset.SourceSeparationInstalledPresetOrigin
import com.mardous.booming.separation.model.preset.SourceSeparationPresetBindingKind
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SourceSeparationRuntimeFacadeTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `unavailable active model reasons stop before source preflight`() {
        val fixture = fixture()
        val pendingReference = SourceSeparationActiveModelReference(
            modelId = "uvr_mdxnet_3_9662",
            artifactSha256 = "a".repeat(64),
            contractSchemaVersion = 2,
        )
        fixture.activeResolution = SourceSeparationActiveCacheModelResolution.Unavailable(
            reason = SourceSeparationActiveCacheModelUnavailableReason.PendingSelection,
            reference = pendingReference,
        )

        val result = fixture.facade().resolve(fixture.song)
            as SourceSeparationRuntimeSongResolution.Unavailable

        assertEquals(SourceSeparationRuntimeUnavailableReason.PendingSelection, result.reason)
        assertEquals(pendingReference, result.reference)
        assertEquals(0, fixture.preflightCount)
    }

    @Test
    fun `runtime qualification failure stops before source preflight`() {
        val fixture = fixture()
        fixture.activeResolution = SourceSeparationActiveCacheModelResolution.Ready(
            fixture.resolvedModel("uvr_mdxnet_3_9662"),
        )

        val result = fixture.facade(
            compatibilityResolver = SourceSeparationRuntimeCompatibilityResolver {
                "The CPU profile is not qualified for this ABI."
            },
        ).resolve(fixture.song) as SourceSeparationRuntimeSongResolution.Unavailable

        assertEquals(SourceSeparationRuntimeUnavailableReason.RuntimeUnsupported, result.reason)
        assertTrue(result.detail.orEmpty().contains("not qualified"))
        assertEquals(0, fixture.preflightCount)
    }

    @Test
    fun `resolved song freezes model identity for execution and cache settings`() {
        val fixture = fixture()
        val firstModel = fixture.resolvedModel("uvr_mdxnet_3_9662")
        val secondModel = fixture.resolvedModel("uvr_mdxnet_kara")
        fixture.activeResolution = SourceSeparationActiveCacheModelResolution.Ready(firstModel)
        var executedModelId: String? = null
        val facade = fixture.facade(
            executor = SourceSeparationModelAwareRangeExecutor { request ->
                executedModelId = request.model.contract.modelId
                throw IllegalStateException("injected stop")
            },
        )
        val first = (facade.resolve(fixture.song) as SourceSeparationRuntimeSongResolution.Ready).song

        fixture.activeResolution = SourceSeparationActiveCacheModelResolution.Ready(secondModel)
        assertThrows(IllegalStateException::class.java) {
            facade.separate(first)
        }

        assertEquals(firstModel.contract.modelId, executedModelId)
        assertEquals(firstModel.contract.modelId, first.modelId)
        assertTrue(facade.cacheStatus(first) is SourceSeparationModelAwareCacheStatus.Incomplete)
        assertTrue(facade.writeBlend(first, 0.2f))
        assertEquals(0.2f, facade.readBlend(first))

        val second = (facade.resolve(fixture.song) as SourceSeparationRuntimeSongResolution.Ready).song
        assertEquals(secondModel.contract.modelId, second.modelId)
        assertNotEquals(first.cacheKey, second.cacheKey)
        assertEquals(2, fixture.preflightCount)
    }

    @Test
    fun `source preflight failure becomes an explicit unavailable result`() {
        val fixture = fixture()
        fixture.activeResolution = SourceSeparationActiveCacheModelResolution.Ready(
            fixture.resolvedModel("uvr_mdxnet_3_9662"),
        )
        val facade = fixture.facade(
            preflightResolver = SourceSeparationModelAwarePreflightResolver { _, _ ->
                throw IllegalArgumentException("source cannot be opened")
            },
        )

        val result = facade.resolve(fixture.song)
            as SourceSeparationRuntimeSongResolution.Unavailable

        assertEquals(SourceSeparationRuntimeUnavailableReason.SourceUnavailable, result.reason)
        assertEquals("source cannot be opened", result.detail)
    }

    private fun fixture(): FacadeFixture {
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
        return FacadeFixture(
            root = root,
            store = store,
            repository = repository,
            coordinator = SourceSeparationCacheRunCoordinator(store, repository) { 10L },
        )
    }

    private class FacadeFixture(
        private val root: File,
        private val store: SourceSeparationCacheStore,
        private val repository: SourceSeparationModelAwareCacheRepository,
        private val coordinator: SourceSeparationCacheRunCoordinator,
    ) {
        var activeResolution: SourceSeparationActiveCacheModelResolution =
            SourceSeparationActiveCacheModelResolution.Unavailable(
                SourceSeparationActiveCacheModelUnavailableReason.NoSelection,
            )
        var preflightCount = 0

        val song = Song(
            id = 42L,
            data = "/music/song.flac",
            title = "Song",
            trackNumber = 1,
            year = 2026,
            size = 1_024L,
            duration = 2_000L,
            dateAdded = 1L,
            rawDateModified = 2L,
            albumId = 3L,
            albumName = "Album",
            artistId = 4L,
            artistName = "Artist",
            albumArtistName = null,
            genreName = null,
        )

        fun facade(
            compatibilityResolver: SourceSeparationRuntimeCompatibilityResolver =
                SourceSeparationRuntimeCompatibilityResolver { null },
            preflightResolver: SourceSeparationModelAwarePreflightResolver =
                SourceSeparationModelAwarePreflightResolver { _, _ ->
                    preflightCount += 1
                    SourceSeparationCacheSourcePreflight(sourceIdentity(), elapsedMs = 7L)
                },
            executor: SourceSeparationModelAwareRangeExecutor =
                SourceSeparationModelAwareRangeExecutor {
                    throw IllegalStateException("executor should not run")
                },
        ): SourceSeparationRuntimeFacade {
            val engine = SourceSeparationModelAwareEngine(
                activeModelResolver = { null },
                preflightResolver = preflightResolver,
                coordinator = coordinator,
                rangeExecutor = executor,
                constructionGate = { true },
            )
            return DefaultSourceSeparationRuntimeFacade(
                activeModelResolver = { activeResolution },
                compatibilityResolver = compatibilityResolver,
                preflightResolver = preflightResolver,
                inputFactory = SourceSeparationRuntimeSongInputFactory {
                    SourceSeparationModelAwareSongInput(
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
                            rawDateModified = 2L,
                            durationMs = 2_000L,
                        ),
                    )
                },
                engine = engine,
                cacheRepository = repository,
                runCoordinator = coordinator,
                flacPromoter = SourceSeparationCacheFlacPromoter(store, repository),
                hydrator = SourceSeparationCacheHydrator(store, repository),
            )
        }

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
                SourceSeparationRuntimeFacadeTest::class.java.classLoader
                    ?.getResourceAsStream(SourceSeparationModelMetadata.CATALOG_ASSET_PATH),
            )
            catalog = resource.use { input ->
                SourceSeparationModelMetadata.decodeCatalog(input.readBytes().toString(Charsets.UTF_8))
            }
        }
    }
}
