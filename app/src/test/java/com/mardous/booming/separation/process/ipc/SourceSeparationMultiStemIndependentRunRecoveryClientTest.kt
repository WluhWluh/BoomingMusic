package com.mardous.booming.separation.process.ipc

import com.mardous.booming.separation.SourceSeparationExecutionRunClass
import com.mardous.booming.separation.SourceSeparationMultiStemPlaybackSelectionSnapshot
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheContractSnapshot
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunJournal
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunJournalLifecycle
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunJournalRequest
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunJournalTransition
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunTransitionType
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheSongLocator
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheSourceDiagnostics
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheSourceIdentity
import com.mardous.booming.separation.model.contract.SourceSeparationMultiTensorExecutableContractLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSeparationMultiStemIndependentRunRecoveryClientTest {
    @Test
    fun `selector admits only running manual journal for selected multistem model`() {
        val official = journal()
        val playback = official.withRunClass(SourceSeparationExecutionRunClass.PlaybackDemandWindow)
        val completed = official.copy(
            lifecycle = SourceSeparationCacheRunJournalLifecycle.Completed,
            transitions = official.transitions + official.transitions.last().copy(
                sequence = 2L,
                type = SourceSeparationCacheRunTransitionType.Completed,
                timestampEpochMs = 2L,
            ),
            updatedAtEpochMs = 2L,
        )

        assertEquals(
            listOf(official),
            SourceSeparationMultiStemRecoveryCandidateSelector.select(
                listOf(official, playback, completed),
                SourceSeparationMultiStemPlaybackSelectionSnapshot(
                    official.request.identity.modelId,
                    2L,
                ),
            ),
        )
        assertTrue(SourceSeparationMultiStemRecoveryCandidateSelector.select(
            listOf(official),
            SourceSeparationMultiStemPlaybackSelectionSnapshot(null, 3L),
        ).isEmpty())
        assertTrue(SourceSeparationMultiStemRecoveryCandidateSelector.select(
            listOf(official),
            SourceSeparationMultiStemPlaybackSelectionSnapshot("other-model", 4L),
        ).isEmpty())
    }

    private fun journal(): SourceSeparationCacheRunJournal {
        val resource = requireNotNull(javaClass.classLoader?.getResourceAsStream(
            "source-separation/research-contracts/htdemucs-6s-official-fp32.json",
        ))
        val contract = resource.use { input ->
            SourceSeparationCacheContractSnapshot.fromMultiTensor(
                SourceSeparationMultiTensorExecutableContractLoader.load(
                    input.readBytes().toString(Charsets.UTF_8),
                ),
            )
        }
        val source = SourceSeparationCacheSourceIdentity(
            audioFingerprint = "encoded-samples-v1:${"a".repeat(64)}",
            encodedSampleCount = 1L,
            encodedByteCount = 4L,
            mimeType = "audio/wav",
            sourceSampleRate = 44_100,
            sourceChannelCount = 2,
            sourceDurationUs = 1_000_000L,
        )
        val identity = contract.identity(source, "htdemucs-cpu-fp32-v1")
        val runClass = SourceSeparationExecutionRunClass.ManualFullSong
        val request = SourceSeparationCacheRunJournalRequest(
            cacheKey = identity.cacheKey,
            identity = identity,
            contract = contract,
            song = SourceSeparationCacheSongLocator(
                1L, "content://media/1", "/music/song.wav", "Song", "Artist", "Album",
            ),
            sourceDiagnostics = SourceSeparationCacheSourceDiagnostics(4L, 1L, 1_000L),
            runId = "multistem-run",
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
            journalSchemaVersion = SourceSeparationCacheRunJournal.SCHEMA_VERSION,
            request = request,
            lifecycle = SourceSeparationCacheRunJournalLifecycle.Running,
            transitions = listOf(SourceSeparationCacheRunJournalTransition(
                sequence = 1L,
                runId = request.runId,
                processGeneration = request.processGeneration,
                ownerPid = request.ownerPid,
                runClass = runClass,
                backgroundPolicy = runClass.backgroundPolicy,
                type = SourceSeparationCacheRunTransitionType.Admitted,
                timestampEpochMs = 1L,
            )),
            updatedAtEpochMs = 1L,
        )
    }

    private fun SourceSeparationCacheRunJournal.withRunClass(
        runClass: SourceSeparationExecutionRunClass,
    ): SourceSeparationCacheRunJournal = copy(
        request = request.copy(
            runClass = runClass,
            backgroundPolicy = runClass.backgroundPolicy,
        ),
        transitions = transitions.map { transition ->
            transition.copy(
                runClass = runClass,
                backgroundPolicy = runClass.backgroundPolicy,
            )
        },
    )
}
