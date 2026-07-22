package com.mardous.booming.separation.cache.v2

import com.mardous.booming.separation.cache.SourceSeparationSegmentPlan
import com.mardous.booming.separation.cache.SourceSeparationSegmentState
import com.mardous.booming.separation.model.contract.ContractStemSemantic
import com.mardous.booming.separation.model.contract.SourceSeparationModelCatalog
import com.mardous.booming.separation.model.contract.SourceSeparationModelMetadata
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SourceSeparationModelAwareCacheRepositoryTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `normal lookup never substitutes another model cache for the same song`() {
        val store = store()
        val modelA = completedManifest(store, "uvr_mdxnet_3_9662", 'a')
        val modelB = completedManifest(store, "uvr_mdxnet_kara", 'a')
        store.writeManifest(modelA)
        store.writeManifest(modelB)
        val repository = repository(store)

        val playbackA = repository.playableStatus(modelA.identity, 0L, 2)
        assertTrue(playbackA is SourceSeparationModelAwarePlayableStatus.Ready)
        (playbackA as SourceSeparationModelAwarePlayableStatus.Ready).playback.close()

        val unknownSource = modelA.identity.copy(
            source = sourceIdentity('c'),
        )
        assertEquals(
            SourceSeparationModelAwarePlayableStatus.Unavailable,
            repository.playableStatus(unknownSource, 0L, 2),
        )
        assertEquals(2, repository.entries().size)
    }

    @Test
    fun `completed cache remains playable while partial cache becomes stale without model`() {
        val store = store()
        val completed = completedManifest(store, "uvr_mdxnet_3_9662", 'a')
        val running = runningManifest(store, "uvr_mdxnet_kara", 'b')
        store.writeManifest(completed)
        store.writeManifest(running)
        val repository = SourceSeparationModelAwareCacheRepository(
            store = store,
            modelAvailability = SourceSeparationCacheModelAvailabilityProvider {
                SourceSeparationCacheModelAvailability.ModelNotInstalled
            },
        )

        val entries = repository.entries().associateBy { it.cacheKey }
        assertEquals(
            SourceSeparationModelAwareCacheEntryState.Completed,
            entries.getValue(completed.cacheKey).state,
        )
        assertEquals(
            SourceSeparationModelAwareCacheEntryState.Stale,
            entries.getValue(running.cacheKey).state,
        )
        repository.openCompletedCache(completed.cacheKey).use { playback ->
            assertNotNull(playback)
            assertTrue(playback!!.vocalsFile.isFile)
            assertTrue(playback.instrumentalFile.isFile)
            assertEquals("timing", playback.timingFile?.readText())
        }
    }

    @Test
    fun `active playback read lease blocks deletion until close`() {
        val store = store()
        val manifest = completedManifest(store, "uvr_mdxnet_3_9662", 'a')
        store.writeManifest(manifest)
        val repository = repository(store)
        val playback = requireNotNull(repository.openCompletedCache(manifest.cacheKey))

        assertEquals(SourceSeparationCacheMutationResult.Busy, repository.delete(manifest.cacheKey))
        assertTrue(store.entryDirectory(manifest.cacheKey).exists())

        playback.close()
        assertEquals(
            SourceSeparationCacheMutationResult.Completed,
            repository.delete(manifest.cacheKey),
        )
        assertFalse(store.entryDirectory(manifest.cacheKey).exists())
    }

    @Test
    fun `blend settings are isolated by model-aware cache identity`() {
        val store = store()
        val modelA = completedManifest(store, "uvr_mdxnet_3_9662", 'a')
        val modelB = completedManifest(store, "uvr_mdxnet_kara", 'a')
        store.writeManifest(modelA)
        store.writeManifest(modelB)
        val repository = repository(store)

        assertTrue(repository.writeBlend(modelA.identity, 0.2f))
        assertTrue(repository.writeBlend(modelB.identity, 0.8f))

        assertEquals(0.2f, repository.readBlend(modelA.identity))
        assertEquals(0.8f, repository.readBlend(modelB.identity))
    }

    @Test
    fun `running cache requires consecutive ready segment files`() {
        val store = store()
        val running = runningManifest(store, "uvr_mdxnet_3_9662", 'a')
        store.writeManifest(running)
        val repository = repository(store)

        val ready = repository.playableStatus(running.identity, 0L, 2)
        assertTrue(ready is SourceSeparationModelAwarePlayableStatus.Ready)
        (ready as SourceSeparationModelAwarePlayableStatus.Ready).playback.close()

        store.resolveEntryPath(running.cacheKey, running.segmentPlan!!.segments[1].vocalsPath)
            .delete()
        assertEquals(
            SourceSeparationModelAwarePlayableStatus.Processing,
            repository.playableStatus(running.identity, 0L, 2),
        )
    }

    @Test
    fun `status and ready horizon expose exact entry state`() {
        val store = store()
        val running = runningManifest(store, "uvr_mdxnet_3_9662", 'a')
        val completed = completedManifest(store, "uvr_mdxnet_kara", 'b').copy(
            cleanup = SourceSeparationCacheCleanup(paths = listOf("work")),
        )
        store.writeManifest(running)
        store.writeManifest(completed)
        val repository = repository(store)

        val runningStatus = repository.status(running.identity)
            as SourceSeparationModelAwareCacheStatus.Incomplete
        assertEquals(2, runningStatus.readySegments)
        assertEquals(2, runningStatus.totalSegments)

        val horizon = repository.readyHorizon(running.identity, playbackPositionMs = 0L)
            as SourceSeparationModelAwareReadyHorizonStatus.Ready
        assertEquals(0, horizon.segmentIndex)
        assertEquals(1, horizon.readyThroughSegmentIndex)
        assertTrue(horizon.readyThroughEnd)

        val completedStatus = repository.status(completed.identity)
            as SourceSeparationModelAwareCacheStatus.Completed
        assertTrue(completedStatus.cleanupPending)
        assertTrue(completedStatus.canPromote)
    }

    @Test
    fun `status reports busy while an exact entry is exclusively leased`() {
        val store = store()
        val manifest = completedManifest(store, "uvr_mdxnet_3_9662", 'a')
        store.writeManifest(manifest)
        val repository = repository(store)
        val lease = requireNotNull(repository.tryAcquireExclusive(manifest.cacheKey))

        assertEquals(
            SourceSeparationModelAwareCacheStatus.Busy,
            repository.status(manifest.identity),
        )
        lease.close()
        assertTrue(repository.status(manifest.identity) is
            SourceSeparationModelAwareCacheStatus.Completed)
    }

    @Test
    fun `cleanup limits count each model entry independently and respect exact protection`() {
        val store = store()
        val completedA = completedManifest(store, "uvr_mdxnet_3_9662", 'a', updatedAt = 10L)
        val completedB = completedManifest(store, "uvr_mdxnet_kara", 'a', updatedAt = 20L)
        val partialA = runningManifest(store, "uvr_mdxnet_3_9662", 'b', updatedAt = 30L)
        val partialB = runningManifest(store, "uvr_mdxnet_kara", 'b', updatedAt = 40L)
        listOf(completedA, completedB, partialA, partialB).forEach(store::writeManifest)
        val repository = repository(store)

        val result = repository.prune(
            partialLimit = 1,
            completedLimit = 1,
            protectedCacheKeys = setOf(partialB.cacheKey),
        )

        assertEquals(2, result.deletedEntries)
        assertNull(store.readManifest(completedA.cacheKey))
        assertNotNull(store.readManifest(completedB.cacheKey))
        assertNull(store.readManifest(partialA.cacheKey))
        assertNotNull(store.readManifest(partialB.cacheKey))
    }

    private fun repository(
        store: SourceSeparationCacheStore,
    ): SourceSeparationModelAwareCacheRepository {
        return SourceSeparationModelAwareCacheRepository(
            store = store,
            modelAvailability = SourceSeparationCacheModelAvailabilityProvider {
                SourceSeparationCacheModelAvailability.InstalledExact
            },
            nowEpochMs = { 100L },
        )
    }

    private fun store(): SourceSeparationCacheStore {
        return SourceSeparationCacheStore(
            root = SourceSeparationCacheRoot(
                directory = temporary.newFolder().absoluteFile,
                location = SourceSeparationCacheRootLocation.InternalCache,
            ),
            nowEpochMs = { 100L },
        )
    }

    private fun completedManifest(
        store: SourceSeparationCacheStore,
        modelId: String,
        fingerprintSeed: Char,
        updatedAt: Long = 2L,
    ): SourceSeparationCacheManifest {
        val snapshot = snapshot(modelId)
        val identity = snapshot.identity(sourceIdentity(fingerprintSeed))
        val directory = store.entryDirectory(identity.cacheKey).apply { mkdirs() }
        val contents = mapOf(
            "completed/vocals.wav" to "vocals-$modelId-$fingerprintSeed",
            "completed/instrumental.wav" to "instrumental-$modelId-$fingerprintSeed",
            "completed/timing.txt" to "timing",
        )
        contents.forEach { (path, content) ->
            File(directory, path).apply {
                parentFile?.mkdirs()
                writeText(content)
            }
        }
        fun integrity(path: String): SourceSeparationCacheFileIntegrity {
            val bytes = contents.getValue(path).toByteArray()
            return SourceSeparationCacheFileIntegrity(bytes.size.toLong(), sha256(bytes))
        }
        return manifest(
            identity = identity,
            snapshot = snapshot,
            state = SourceSeparationCacheManifestState.Completed,
            output = SourceSeparationCacheOutput(
                stems = listOf(
                    renderedStem(
                        ContractStemSemantic.Vocals,
                        "completed/vocals.wav",
                        integrity("completed/vocals.wav"),
                    ),
                    renderedStem(
                        ContractStemSemantic.Instrumental,
                        "completed/instrumental.wav",
                        integrity("completed/instrumental.wav"),
                    ),
                ),
                timingPath = "completed/timing.txt",
                outputSampleRate = 44_100,
                outputFrameCount = 88_200,
                windowCount = 2,
                elapsedMs = 1_000L,
                totalBytes = contents.values.sumOf { it.toByteArray().size }.toLong(),
            ),
            segmentPlan = null,
            updatedAt = updatedAt,
        )
    }

    private fun runningManifest(
        store: SourceSeparationCacheStore,
        modelId: String,
        fingerprintSeed: Char,
        updatedAt: Long = 2L,
    ): SourceSeparationCacheManifest {
        val snapshot = snapshot(modelId)
        val identity = snapshot.identity(sourceIdentity(fingerprintSeed))
        val directory = store.entryDirectory(identity.cacheKey).apply { mkdirs() }
        val plan = SourceSeparationSegmentPlan.build(
            rangeStartFrame = 0,
            rangeEndFrame = 88_200,
            sampleRate = 44_100,
            generationSize = 44_100,
            trim = 1_024,
            chunkSize = 46_148,
            defaultState = SourceSeparationSegmentState.Ready,
        )
        listOf("work/vocals.wav", "work/instrumental.wav").forEach { path ->
            File(directory, path).apply {
                parentFile?.mkdirs()
                writeText(path)
            }
        }
        plan.segments.forEach { segment ->
            listOf(segment.vocalsPath, segment.instrumentalPath).forEach { path ->
                File(directory, path).apply {
                    parentFile?.mkdirs()
                    writeText(path)
                }
            }
        }
        return manifest(
            identity = identity,
            snapshot = snapshot,
            state = SourceSeparationCacheManifestState.Running,
            output = SourceSeparationCacheOutput(
                stems = listOf(
                    renderedStem(ContractStemSemantic.Vocals, "work/vocals.wav", null),
                    renderedStem(
                        ContractStemSemantic.Instrumental,
                        "work/instrumental.wav",
                        null,
                    ),
                ),
                outputSampleRate = 44_100,
                outputFrameCount = 88_200,
                windowCount = 2,
                elapsedMs = 0L,
                totalBytes = 0L,
            ),
            segmentPlan = plan,
            updatedAt = updatedAt,
        )
    }

    private fun manifest(
        identity: SourceSeparationCacheIdentity,
        snapshot: SourceSeparationCacheContractSnapshot,
        state: SourceSeparationCacheManifestState,
        output: SourceSeparationCacheOutput,
        segmentPlan: SourceSeparationSegmentPlan?,
        updatedAt: Long,
    ): SourceSeparationCacheManifest {
        return SourceSeparationCacheManifest(
            cacheKey = identity.cacheKey,
            identity = identity,
            contract = snapshot,
            state = state,
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
            output = output,
            segmentPlan = segmentPlan,
            createdAtEpochMs = 1L,
            updatedAtEpochMs = updatedAt,
        )
    }

    private fun renderedStem(
        semantic: ContractStemSemantic,
        path: String,
        integrity: SourceSeparationCacheFileIntegrity?,
    ): SourceSeparationCacheRenderedStem {
        return SourceSeparationCacheRenderedStem(
            semantic = semantic,
            displayLabel = semantic.name,
            wavPath = path,
            channelCount = 2,
            sampleRate = 44_100,
            frameCount = 88_200,
            wavIntegrity = integrity,
        )
    }

    private fun snapshot(modelId: String): SourceSeparationCacheContractSnapshot {
        return SourceSeparationCacheContractSnapshot.fromOfficial(
            catalog.contracts.single { it.modelId == modelId }
        )
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

    companion object {
        private lateinit var catalog: SourceSeparationModelCatalog

        @JvmStatic
        @BeforeClass
        fun loadCatalog() {
            val resource = requireNotNull(
                SourceSeparationModelAwareCacheRepositoryTest::class.java.classLoader
                    ?.getResourceAsStream(SourceSeparationModelMetadata.CATALOG_ASSET_PATH)
            )
            catalog = resource.use { input ->
                SourceSeparationModelMetadata.decodeCatalog(input.readBytes().toString(Charsets.UTF_8))
            }
        }

        private fun sha256(bytes: ByteArray): String {
            return MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { byte ->
                    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
                }
        }
    }
}
