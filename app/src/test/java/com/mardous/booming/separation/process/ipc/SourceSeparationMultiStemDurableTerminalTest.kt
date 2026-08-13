package com.mardous.booming.separation.process.ipc

import com.mardous.booming.separation.SourceSeparationExecutionRunClass
import com.mardous.booming.separation.SourceSeparationPauseReason
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheContractSnapshot
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheError
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunJournal
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunJournalLifecycle
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunJournalRequest
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunTransitionType
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheSongLocator
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheSourceDiagnostics
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheSourceIdentity
import com.mardous.booming.separation.model.contract.SourceSeparationMultiTensorExecutableContractLoader
import com.mardous.booming.separation.process.SourceSeparationMultiStemExecutionDescriptor
import com.mardous.booming.separation.process.SourceSeparationMultiStemExecutionModelIdentity
import com.mardous.booming.separation.process.SourceSeparationMultiStemExecutionRuntime
import com.mardous.booming.separation.process.SourceSeparationMultiStemExecutionSourceIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SourceSeparationMultiStemDurableTerminalTest {
    @Test
    fun `only the exact run can recover a durable completion`() {
        val descriptor = descriptor()
        val completed = journal(descriptor).append(
            type = SourceSeparationCacheRunTransitionType.Completed,
            nowEpochMs = 2L,
            lifecycle = SourceSeparationCacheRunJournalLifecycle.Completed,
        )

        assertEquals(
            SourceSeparationMultiStemDurableTerminal.Completed,
            completed.terminalStateFor(descriptor),
        )
        assertNull(completed.terminalStateFor(descriptor.copy(runId = "new-run")))
    }

    @Test
    fun `durable pause preserves active-model supersession`() {
        val descriptor = descriptor()
        val connected = journal(descriptor).observerConnected(
            observerId = "terminal-test-observer",
            observerProcessName = "test-process",
            nowEpochMs = 2L,
        )
        val paused = connected.append(
            type = SourceSeparationCacheRunTransitionType.ActiveModelSuperseded,
            nowEpochMs = 3L,
            lifecycle = SourceSeparationCacheRunJournalLifecycle.Paused,
        ).observerDisconnected(
            observerId = "terminal-test-observer",
            observerProcessName = "test-process",
            reason = "terminal",
            nowEpochMs = 4L,
        )

        assertEquals(
            SourceSeparationMultiStemDurableTerminal.Paused(
                SourceSeparationPauseReason.ActiveModelSuperseded,
            ),
            paused.terminalStateFor(descriptor),
        )
    }

    @Test
    fun `durable failure retains the recorded remote error`() {
        val descriptor = descriptor()
        val failed = journal(descriptor).append(
            type = SourceSeparationCacheRunTransitionType.Failed,
            nowEpochMs = 2L,
            lifecycle = SourceSeparationCacheRunJournalLifecycle.Failed,
            error = SourceSeparationCacheError("example.RemoteFailure", "failed"),
        )

        assertEquals(
            SourceSeparationMultiStemDurableTerminal.Failed(
                errorType = "example.RemoteFailure",
                message = "failed",
            ),
            failed.terminalStateFor(descriptor),
        )
    }

    @Test
    fun `matching run rejects a different exact cache identity`() {
        val descriptor = descriptor()
        val journal = journal(descriptor)
        val changed = descriptor.copy(
            source = descriptor.source.copy(
                diagnostics = descriptor.source.diagnostics.copy(fileSize = 2_048L),
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            journal.terminalStateFor(changed)
        }
    }

    private fun journal(
        descriptor: SourceSeparationMultiStemExecutionDescriptor,
    ): SourceSeparationCacheRunJournal = SourceSeparationCacheRunJournal.admitted(
        SourceSeparationCacheRunJournalRequest(
            cacheKey = descriptor.cacheKey,
            identity = descriptor.cacheIdentity,
            contract = descriptor.contract,
            song = descriptor.song,
            sourceDiagnostics = descriptor.source.diagnostics,
            runId = descriptor.runId,
            processGeneration = descriptor.processGeneration,
            ownerPid = 42,
            runClass = descriptor.runtime.runClass,
            backgroundPolicy = descriptor.runtime.backgroundPolicy,
            tryGpu = false,
            gpuRuntimeIdentity = null,
            gpuFallbackLatch = null,
            admittedAtEpochMs = 1L,
        ),
    )

    private fun descriptor(): SourceSeparationMultiStemExecutionDescriptor {
        val text = requireNotNull(javaClass.classLoader?.getResourceAsStream(
            "source-separation/research-contracts/htdemucs-4s-official-base-fp32.json",
        )).bufferedReader().use { it.readText() }
        val executable = SourceSeparationMultiTensorExecutableContractLoader.load(text)
        val contract = SourceSeparationCacheContractSnapshot.fromMultiTensor(executable)
        val source = SourceSeparationCacheSourceIdentity(
            audioFingerprint = "encoded-samples-v1:${"a".repeat(64)}",
            encodedSampleCount = 1_024L,
            encodedByteCount = 1_024L,
            mimeType = "audio/flac",
            sourceSampleRate = 44_100,
            sourceChannelCount = 2,
            sourceDurationUs = 7_800_000L,
        )
        val profile = "htdemucs-cpu-fp32-v1"
        return SourceSeparationMultiStemExecutionDescriptor(
            runId = "durable-terminal-run",
            processGeneration = 3L,
            cacheKey = contract.identity(source, profile).cacheKey,
            cacheIdentity = contract.identity(source, profile),
            contract = contract,
            model = SourceSeparationMultiStemExecutionModelIdentity(
                modelId = contract.modelId,
                artifactFileName = contract.artifactFileName,
                artifactByteSize = contract.artifactByteSize,
                artifactSha256 = contract.artifactSha256,
                contractId = contract.contractId,
                pipelineId = contract.pipelineId,
                pipelineVersion = contract.pipelineVersion,
            ),
            source = SourceSeparationMultiStemExecutionSourceIdentity(
                sourceUri = "content://media/42",
                displayName = "Song.flac",
                source = source,
                diagnostics = SourceSeparationCacheSourceDiagnostics(
                    fileSize = 1_024L,
                    rawDateModified = 1L,
                    durationMs = 7_800L,
                ),
            ),
            song = SourceSeparationCacheSongLocator(
                songId = 42L,
                mediaUri = "content://media/42",
                filePath = "/music/Song.flac",
                title = "Song",
                artist = "Artist",
                album = "Album",
            ),
            runtime = SourceSeparationMultiStemExecutionRuntime(
                executionProfileId = profile,
                runClass = SourceSeparationExecutionRunClass.ManualFullSong,
                backgroundPolicy =
                    SourceSeparationExecutionRunClass.ManualFullSong.backgroundPolicy,
                windowDecodeEnabled = false,
            ),
        )
    }
}
