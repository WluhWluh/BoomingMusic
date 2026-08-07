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
