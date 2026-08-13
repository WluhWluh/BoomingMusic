package com.mardous.booming.separation.process

import com.mardous.booming.separation.SourceSeparationExecutionRunClass
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheContractSnapshot
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheSourceDiagnostics
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheSourceIdentity
import com.mardous.booming.separation.model.contract.SourceSeparationMultiTensorExecutableContractLoader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSeparationMultiStemExecutionProtocolTest {
    @Test
    fun `descriptor round trip preserves exact multistem identity`() {
        val fixture = fixture()
        val json = Json { encodeDefaults = true }
        val decoded = json.decodeFromJsonElement<SourceSeparationMultiStemExecutionDescriptor>(
            json.encodeToJsonElement(fixture),
        )

        assertEquals(fixture, decoded)
        assertEquals(fixture.contract.expectedStemSet().stems.map { it.stemId },
            decoded.contract.expectedStemSet().stems.map { it.stemId })
        assertEquals(false, decoded.runtime.windowDecodeEnabled)
    }

    @Test
    fun `descriptor rejects a cache identity from another source`() {
        val fixture = fixture()
        val otherSource = fixture.source.copy(
            source = fixture.source.source.copy(
                audioFingerprint = "encoded-samples-v1:${"b".repeat(64)}",
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            fixture.copy(source = otherSource)
        }
    }

    @Test
    fun `manual full song command requires an exact foreground lease`() {
        val descriptor = fixture()
        val lease = SourceSeparationForegroundLeaseRequest(
            leaseId = "multistem-protocol-lease-0001",
            runId = descriptor.runId,
            processGeneration = descriptor.processGeneration,
            displayName = descriptor.source.displayName,
        )
        val command = SourceSeparationMultiStemIpcStartCommand(
            descriptor = descriptor,
            foregroundLease = lease,
        )

        assertEquals(
            command,
            SourceSeparationMultiStemExecutionCodec.decodeStartCommand(
                SourceSeparationMultiStemExecutionCodec.encodeStartCommand(command),
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            SourceSeparationMultiStemIpcStartCommand(
                descriptor = descriptor,
                foregroundLease = null,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            SourceSeparationMultiStemIpcStartCommand(
                descriptor = descriptor,
                foregroundLease = lease.copy(runId = "another-run"),
            )
        }
    }

    @Test
    fun `active snapshot binds authority observer and latest event`() {
        val descriptor = fixture()
        val lease = SourceSeparationForegroundLeaseRequest(
            leaseId = "multistem-protocol-lease-0002",
            runId = descriptor.runId,
            processGeneration = descriptor.processGeneration,
            displayName = descriptor.source.displayName,
        )
        val event = SourceSeparationMultiStemExecutionEvent(
            runId = descriptor.runId,
            processGeneration = descriptor.processGeneration,
            sequence = 1L,
            payload = SourceSeparationMultiStemExecutionEventPayload.Accepted(descriptor),
        )
        val state = SourceSeparationMultiStemIpcActiveRunState(
            descriptor = descriptor,
            authority = SourceSeparationMultiStemIpcRunAuthority.IndependentForeground,
            latestEvent = event,
            observerConnected = false,
            foregroundLease = lease,
        )
        val response = SourceSeparationMultiStemIpcActiveRunResponse(
            status = SourceSeparationMultiStemIpcStatus.Active,
            state = state,
        )

        assertEquals(
            response,
            SourceSeparationMultiStemExecutionCodec.decodeActiveRunResponse(
                SourceSeparationMultiStemExecutionCodec.encodeActiveRunResponse(response),
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            state.copy(authority = SourceSeparationMultiStemIpcRunAuthority.ClientBound)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SourceSeparationMultiStemIpcActiveRunResponse(
                status = SourceSeparationMultiStemIpcStatus.NoActiveRun,
                state = state,
            )
        }
    }

    @Test
    fun `progress and demand control preserve scheduler waterline`() {
        val scheduler = com.mardous.booming.separation.model
            .SourceSeparationSegmentSchedulerProgress(
                playbackSegmentIndex = 1,
                playbackSegmentState = "Queued",
                nextSegmentIndex = 2,
                nextSegmentState = "Ready",
                processingSegmentIndex = 1,
                priority = "CurrentPlayback",
                readySegments = 2,
                totalSegments = 4,
                readyWindowCount = 2,
                playbackReadyWindowReadyCount = 1,
                playbackReadyWindowPendingCount = 1,
            )
        val event = SourceSeparationMultiStemExecutionEvent(
            runId = fixture().runId,
            processGeneration = 3L,
            sequence = 1L,
            payload = SourceSeparationMultiStemExecutionEventPayload.Progress(
                completedWindows = 1,
                totalWindows = 4,
                stage = "Processed window 1/4",
                completedWindowElapsedMs = 123L,
                scheduler = scheduler,
            ),
        )
        val decodedEvent = SourceSeparationMultiStemExecutionCodec.decodeEvent(
            SourceSeparationMultiStemExecutionCodec.encodeEvent(event),
        )
        val progress = decodedEvent.payload as
            SourceSeparationMultiStemExecutionEventPayload.Progress
        assertEquals(123L, progress.completedWindowElapsedMs)
        assertEquals(scheduler, progress.scheduler)

        val command = SourceSeparationMultiStemIpcControlCommand(
            runId = fixture().runId,
            processGeneration = 3L,
            action = SourceSeparationMultiStemIpcControlAction.Update,
            hasPlaybackPositionUpdate = true,
            playbackPositionMs = 5_000L,
            playbackReadyWindowCount = 3,
        )
        val decodedCommand = SourceSeparationMultiStemExecutionCodec.decodeControlCommand(
            SourceSeparationMultiStemExecutionCodec.encodeControlCommand(command),
        )
        assertEquals(command, decodedCommand)
        assertTrue(decodedCommand.playbackReadyWindowCount == 3)
    }

    private fun fixture(): SourceSeparationMultiStemExecutionDescriptor {
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
            runId = "protocol-test-run",
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
            song = com.mardous.booming.separation.cache.v2.SourceSeparationCacheSongLocator(
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
                backgroundPolicy = SourceSeparationExecutionRunClass.ManualFullSong.backgroundPolicy,
                windowDecodeEnabled = false,
            ),
        )
    }
}
