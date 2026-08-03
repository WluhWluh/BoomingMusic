package com.mardous.booming.separation.cache.v2

import com.mardous.booming.separation.model.contract.ContractStemSemantic
import com.mardous.booming.separation.model.contract.SourceSeparationModelContract
import com.mardous.booming.separation.model.contract.SourceSeparationModelMetadata
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class SourceSeparationCacheIdentityTest {

    @Test
    fun `cache key is deterministic and independent of song locator`() {
        val source = sourceIdentity()
        val snapshot = officialSnapshot()

        val first = snapshot.identity(source)
        val second = snapshot.identity(source)

        assertEquals(first.cacheKey, second.cacheKey)
        assertEquals(64, first.cacheKey.length)
        assertTrue(first.cacheKey.matches(LOWERCASE_SHA256))
        assertEquals(
            first.cacheKey,
            first.copy(
                artifactSha256 = first.artifactSha256.uppercase(),
                contractFingerprint = first.contractFingerprint.uppercase(),
            ).cacheKey,
        )
    }

    @Test
    fun `source replacement and profile revision create separate identities`() {
        val snapshot = officialSnapshot()
        val source = sourceIdentity()
        val changedSource = source.copy(audioFingerprint = "encoded-samples-v1:${"b".repeat(64)}")
        val first = snapshot.identity(source)

        assertNotEquals(first.cacheKey, snapshot.identity(changedSource).cacheKey)
        assertNotEquals(
            first.cacheKey,
            snapshot.copy(profileRevisionId = "${snapshot.profileRevisionId}-revision-2")
                .identity(source)
                .cacheKey,
        )
        assertNotEquals(
            first.cacheKey,
            snapshot.identity(
                source = source,
                renderProfileId = "mdx-fp16-render-v1",
            ).cacheKey,
        )
    }

    @Test
    fun `same render profile can share CPU and GPU identity`() {
        val snapshot = officialSnapshot()
        val source = sourceIdentity()

        assertEquals(
            snapshot.identity(source, "mdx-fp32-render-v1").cacheKey,
            snapshot.identity(source, "mdx-fp32-render-v1").cacheKey,
        )
    }

    @Test
    fun `contract fingerprint excludes presentation and provenance`() {
        val original = officialSnapshot()
        val changedPresentation = original.copy(
            displayName = "Renamed model",
            source = original.source?.copy(url = "https://example.invalid/repacked.onnx"),
            stemContract = original.stemContract.copy(
                modelOutput = original.stemContract.modelOutput.copy(displayLabel = "Voice"),
                residual = original.stemContract.residual.copy(displayLabel = "Music"),
            ),
        )

        assertEquals(original.contractFingerprint, changedPresentation.contractFingerprint)
    }

    @Test
    fun `contract fingerprint changes when rendering semantics change`() {
        val original = officialSnapshot()
        val changedDsp = original.copy(
            dsp = original.dsp.copy(dimF = original.dsp.dimF + 1),
        )

        assertNotEquals(original.contractFingerprint, changedDsp.contractFingerprint)
    }

    @Test
    fun `manifest round trips with relative output paths`() {
        val manifest = completedManifest()
        val json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = false
        }

        val decoded = json.decodeFromString<SourceSeparationCacheManifest>(
            json.encodeToString(manifest),
        )

        assertEquals(manifest, decoded)
        assertEquals(manifest.cacheKey, decoded.identity.cacheKey)
    }

    @Test
    fun `manifest rejects a mismatched key`() {
        val manifest = completedManifest()
        val changedIdentity = manifest.identity.copy(renderProfileId = "other-render-v1")

        assertThrows(IllegalArgumentException::class.java) {
            manifest.copy(identity = changedIdentity)
        }
    }

    @Test
    fun `manifest rejects historical and future schemas`() {
        val manifest = completedManifest()

        assertThrows(IllegalArgumentException::class.java) {
            manifest.copy(manifestSchemaVersion = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            manifest.copy(manifestSchemaVersion = 4)
        }
    }

    @Test
    fun `manifest rejects unsafe relative paths`() {
        assertThrows(IllegalArgumentException::class.java) {
            SourceSeparationCacheRelativePath.requireValid("../outside.wav")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SourceSeparationCacheRelativePath.requireValid("C:\\outside.wav")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SourceSeparationCacheRelativePath.requireValid("segments\\voice.wav")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SourceSeparationCacheRelativePath.requireValid("/outside.wav")
        }
    }

    @Test
    fun `completed manifest requires stem integrity`() {
        val manifest = completedManifest()
        val incompleteOutput = manifest.output!!.copy(
            stems = manifest.output.stems.map { it.copy(wavIntegrity = null) },
        )

        assertThrows(IllegalArgumentException::class.java) {
            manifest.copy(output = incompleteOutput)
        }
    }

    @Test
    fun `playback settings bind to the exact cache key`() {
        val manifest = completedManifest()
        val settings = SourceSeparationCachePlaybackSettings(
            cacheKey = manifest.cacheKey,
            audioFingerprint = manifest.identity.source.audioFingerprint,
            blend = 0.25f,
            updatedAtEpochMs = 10L,
        )

        assertTrue(settings.matches(manifest))
        assertTrue(
            !settings.copy(cacheKey = "0".repeat(64)).matches(manifest)
        )
    }

    private fun completedManifest(): SourceSeparationCacheManifest {
        val snapshot = officialSnapshot()
        val identity = snapshot.identity(sourceIdentity())
        val integrity = SourceSeparationCacheFileIntegrity(
            byteSize = 128L,
            sha256 = "c".repeat(64),
        )
        val stems = listOf(
            SourceSeparationCacheRenderedStem(
                semantic = ContractStemSemantic.Vocals,
                displayLabel = "Vocals",
                wavPath = "completed/vocals.wav",
                channelCount = 2,
                sampleRate = 44_100,
                frameCount = 44_100,
                wavIntegrity = integrity,
            ),
            SourceSeparationCacheRenderedStem(
                semantic = ContractStemSemantic.Instrumental,
                displayLabel = "Instrumental",
                wavPath = "completed/instrumental.wav",
                channelCount = 2,
                sampleRate = 44_100,
                frameCount = 44_100,
                wavIntegrity = integrity,
            ),
        )
        return SourceSeparationCacheManifest(
            cacheKey = identity.cacheKey,
            identity = identity,
            contract = snapshot,
            state = SourceSeparationCacheManifestState.Completed,
            song = SourceSeparationCacheSongLocator(
                songId = 42L,
                mediaUri = "content://media/42",
                filePath = "/music/song.flac",
                title = "Song",
                artist = "Artist",
                album = "Album",
            ),
            sourceDiagnostics = SourceSeparationCacheSourceDiagnostics(
                fileSize = 128L,
                rawDateModified = 20L,
                durationMs = 1_000L,
            ),
            output = SourceSeparationCacheOutput(
                stems = stems,
                timingPath = "completed/timing.txt",
                outputSampleRate = 44_100,
                outputFrameCount = 44_100,
                windowCount = 1,
                elapsedMs = 100L,
                totalBytes = 256L,
            ),
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 2L,
        )
    }

    private fun sourceIdentity() = SourceSeparationCacheSourceIdentity(
        audioFingerprint = "encoded-samples-v1:${"a".repeat(64)}",
        encodedSampleCount = 100L,
        encodedByteCount = 1_024L,
        mimeType = "audio/flac",
        sourceSampleRate = 44_100,
        sourceChannelCount = 2,
        sourceDurationUs = 1_000_000L,
    )

    private fun officialSnapshot(): SourceSeparationCacheContractSnapshot {
        return SourceSeparationCacheContractSnapshot.fromOfficial(contract)
    }

    companion object {
        private val LOWERCASE_SHA256 = Regex("^[0-9a-f]{64}$")
        private lateinit var contract: SourceSeparationModelContract

        @JvmStatic
        @BeforeClass
        fun loadContract() {
            val resource = requireNotNull(
                SourceSeparationCacheIdentityTest::class.java.classLoader
                    ?.getResourceAsStream(SourceSeparationModelMetadata.CATALOG_ASSET_PATH)
            )
            val catalog = resource.use { input ->
                SourceSeparationModelMetadata.decodeCatalog(input.readBytes().toString(Charsets.UTF_8))
            }
            contract = catalog.contracts.single { it.modelId == "uvr_mdxnet_3_9662" }
        }
    }
}
