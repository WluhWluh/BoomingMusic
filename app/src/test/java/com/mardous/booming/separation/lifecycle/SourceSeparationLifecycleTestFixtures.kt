package com.mardous.booming.separation.lifecycle

import com.mardous.booming.separation.SourceSeparationExecutionRunClass
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheContractSnapshot
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheIdentity
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunJournal
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunJournalLifecycle
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunJournalRequest
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunJournalTransition
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunTransitionType
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheSongLocator
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheSourceDiagnostics
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheSourceIdentity
import com.mardous.booming.separation.model.contract.SourceSeparationModelCatalog
import com.mardous.booming.separation.model.contract.SourceSeparationModelMetadata
import com.mardous.booming.separation.model.preset.SourceSeparationActiveModelReference

internal object SourceSeparationLifecycleTestFixtures {
    const val MODEL_A = "uvr_mdxnet_3_9662"
    const val MODEL_B = "uvr_mdxnet_kara"

    private val catalog: SourceSeparationModelCatalog by lazy {
        val input = requireNotNull(
            javaClass.classLoader?.getResourceAsStream(
                SourceSeparationModelMetadata.CATALOG_ASSET_PATH,
            ),
        )
        input.use { stream ->
            SourceSeparationModelMetadata.decodeCatalog(
                stream.readBytes().toString(Charsets.UTF_8),
            )
        }
    }

    fun activeReference(modelId: String): SourceSeparationActiveModelReference {
        val contract = contract(modelId)
        return SourceSeparationActiveModelReference(
            modelId = contract.modelId,
            artifactSha256 = contract.artifactSha256,
            contractSchemaVersion = contract.contractSchemaVersion,
        )
    }

    fun contract(modelId: String): SourceSeparationCacheContractSnapshot =
        SourceSeparationCacheContractSnapshot.fromOfficial(
            catalog.contracts.single { it.modelId == modelId },
        )

    fun source(seed: Char = 'a'): SourceSeparationCacheSourceIdentity =
        SourceSeparationCacheSourceIdentity(
            audioFingerprint = "encoded-samples-v1:${seed.toString().repeat(64)}",
            encodedSampleCount = 88_200L,
            encodedByteCount = 705_600L,
            mimeType = "audio/flac",
            sourceSampleRate = 44_100,
            sourceChannelCount = 2,
            sourceDurationUs = 2_000_000L,
        )

    fun identity(
        modelId: String,
        source: SourceSeparationCacheSourceIdentity = source(),
    ): SourceSeparationCacheIdentity = contract(modelId).identity(source)

    fun journal(
        modelId: String,
        songId: Long = 42L,
        runId: String = "run-$modelId",
        processGeneration: Long = 7L,
        lifecycle: SourceSeparationCacheRunJournalLifecycle =
            SourceSeparationCacheRunJournalLifecycle.Running,
    ): SourceSeparationCacheRunJournal {
        val contract = contract(modelId)
        val identity = contract.identity(source())
        val runClass = SourceSeparationExecutionRunClass.ManualFullSong
        val request = SourceSeparationCacheRunJournalRequest(
            cacheKey = identity.cacheKey,
            identity = identity,
            contract = contract,
            song = SourceSeparationCacheSongLocator(
                songId = songId,
                mediaUri = "content://media/$songId",
                filePath = "/music/song-$songId.flac",
                title = "Song $songId",
                artist = "Artist",
                album = "Album",
            ),
            sourceDiagnostics = SourceSeparationCacheSourceDiagnostics(
                fileSize = 1_024L,
                rawDateModified = 2L,
                durationMs = 2_000L,
            ),
            runId = runId,
            processGeneration = processGeneration,
            ownerPid = 10,
            runClass = runClass,
            backgroundPolicy = runClass.backgroundPolicy,
            tryGpu = false,
            gpuRuntimeIdentity = null,
            gpuFallbackLatch = null,
            admittedAtEpochMs = 1L,
        )
        return SourceSeparationCacheRunJournal(
            request = request,
            lifecycle = lifecycle,
            transitions = listOf(
                SourceSeparationCacheRunJournalTransition(
                    sequence = 1L,
                    runId = runId,
                    processGeneration = processGeneration,
                    ownerPid = 10,
                    runClass = runClass,
                    backgroundPolicy = runClass.backgroundPolicy,
                    type = SourceSeparationCacheRunTransitionType.Admitted,
                    timestampEpochMs = 1L,
                ),
            ),
            updatedAtEpochMs = 1L,
        )
    }
}
