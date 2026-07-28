package com.mardous.booming.separation.process.ipc

import com.mardous.booming.separation.SourceSeparationBackgroundPolicy
import com.mardous.booming.separation.SourceSeparationExecutionRunClass
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunJournal
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunJournalLifecycle
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunJournalRequest
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunJournalTransition
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunTransitionType
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheSongLocator
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheSourceDiagnostics
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheSourceIdentity
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheContractSnapshot
import com.mardous.booming.separation.model.contract.SourceSeparationModelCatalog
import com.mardous.booming.separation.model.contract.SourceSeparationModelMetadata
import org.junit.BeforeClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSeparationIndependentRunRecoveryClientTest {
    @Test
    fun `candidate selector admits only running independent manual journals`() {
        val independent = journal("manual", SourceSeparationExecutionRunClass.ManualFullSong)
        val playback = journal(
            "playback",
            SourceSeparationExecutionRunClass.PlaybackDemandWindow,
        )
        val prefetch = journal(
            "prefetch",
            SourceSeparationExecutionRunClass.NextSongPrefetch,
        )
        val completed = independent.copy(
            lifecycle = SourceSeparationCacheRunJournalLifecycle.Completed,
            transitions = independent.transitions + independent.transitions.last().copy(
                sequence = 2L,
                type = SourceSeparationCacheRunTransitionType.Completed,
                timestampEpochMs = 2L,
            ),
            updatedAtEpochMs = 2L,
        )

        assertEquals(
            listOf(independent),
            SourceSeparationIndependentRunRecoveryCandidateSelector.select(
                listOf(independent, playback, prefetch, completed),
            ),
        )
        assertTrue(SourceSeparationIndependentRunRecoveryCandidateSelector.select(
            listOf(playback, prefetch, completed),
        ).isEmpty())
    }

    private fun journal(
        runId: String,
        runClass: SourceSeparationExecutionRunClass,
    ): SourceSeparationCacheRunJournal {
        val contract = SourceSeparationCacheContractSnapshot.fromOfficial(
            catalog.contracts.single { it.modelId == "uvr_mdxnet_3_9662" },
        )
        val identity = contract.identity(
            source = SourceSeparationCacheSourceIdentity(
                audioFingerprint = "encoded-samples-v1:${"a".repeat(64)}",
                encodedSampleCount = 1L,
                encodedByteCount = 4L,
                mimeType = "audio/wav",
                sourceSampleRate = 44_100,
                sourceChannelCount = 2,
                sourceDurationUs = 1_000_000L,
            ),
            renderProfileId = "test-render",
        )
        val request = SourceSeparationCacheRunJournalRequest(
            cacheKey = identity.cacheKey,
            identity = identity,
            contract = contract,
            song = SourceSeparationCacheSongLocator(
                songId = 1L,
                mediaUri = "content://media/1",
                filePath = "/music/song.wav",
                title = "Song",
                artist = "Artist",
                album = "Album",
            ),
            sourceDiagnostics = SourceSeparationCacheSourceDiagnostics(
                fileSize = 1_000L,
                rawDateModified = 1L,
                durationMs = 10_000L,
            ),
            runId = runId,
            processGeneration = 7L,
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
            lifecycle = SourceSeparationCacheRunJournalLifecycle.Running,
            transitions = listOf(
                SourceSeparationCacheRunJournalTransition(
                    sequence = 1L,
                    runId = runId,
                    processGeneration = 7L,
                    ownerPid = 10,
                    runClass = runClass,
                    backgroundPolicy = runClass.backgroundPolicy,
                    type = SourceSeparationCacheRunTransitionType.Admitted,
                    timestampEpochMs = 1L,
                )
            ),
            updatedAtEpochMs = 1L,
        )
    }

    companion object {
        private lateinit var catalog: SourceSeparationModelCatalog

        @JvmStatic
        @BeforeClass
        fun loadCatalog() {
            val resource = requireNotNull(
                SourceSeparationIndependentRunRecoveryClientTest::class.java.classLoader
                    ?.getResourceAsStream(SourceSeparationModelMetadata.CATALOG_ASSET_PATH)
            )
            catalog = resource.use { input ->
                SourceSeparationModelMetadata.decodeCatalog(
                    input.readBytes().toString(Charsets.UTF_8),
                )
            }
        }
    }
}
