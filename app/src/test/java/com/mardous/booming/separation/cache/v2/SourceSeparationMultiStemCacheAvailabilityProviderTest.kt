package com.mardous.booming.separation.cache.v2

import com.mardous.booming.separation.HtdemucsSourceSeparationEngine
import com.mardous.booming.separation.SourceSeparationModelFamily
import com.mardous.booming.separation.model.contract.SourceSeparationInstalledMultiStemModel
import com.mardous.booming.separation.model.contract.SourceSeparationMultiTensorExecutableContractLoader
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SourceSeparationMultiStemCacheAvailabilityProviderTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `multistem manifest entry reports its full family-aware execution identity`() {
        val fixture = fixture()
        val store = SourceSeparationCacheStore(
            SourceSeparationCacheRoot(
                directory = temporary.newFolder(),
                location = SourceSeparationCacheRootLocation.InternalCache,
            ),
        )
        store.writeManifest(fixture.manifest)
        val repository = SourceSeparationModelAwareCacheRepository(
            store = store,
            modelAvailability = SourceSeparationMultiStemCacheAvailabilityProvider {
                fixture.installed
            },
        )

        val entry = repository.entries().single()

        assertEquals(SourceSeparationModelFamily.Htdemucs, entry.modelFamily)
        assertEquals(fixture.manifest.identity.modelId, entry.executionIdentity.modelId)
        assertEquals(
            fixture.manifest.identity.artifactSha256,
            entry.executionIdentity.artifactSha256,
        )
        assertEquals(
            fixture.manifest.identity.contractFingerprint,
            entry.executionIdentity.contractFingerprint,
        )
        assertEquals(
            fixture.manifest.identity.pipelineId,
            entry.executionIdentity.pipelineId,
        )
        assertEquals(
            fixture.manifest.identity.renderProfileId,
            entry.executionIdentity.renderProfileId,
        )
    }

    @Test
    fun `exact installed release contract admits its multistem cache`() {
        val fixture = fixture()
        val provider = SourceSeparationMultiStemCacheAvailabilityProvider { fixture.installed }

        assertEquals(
            SourceSeparationCacheModelAvailability.InstalledExact,
            provider.availability(fixture.manifest),
        )
    }

    @Test
    fun `missing installed release model rejects its multistem cache`() {
        val fixture = fixture()
        val provider = SourceSeparationMultiStemCacheAvailabilityProvider { null }

        assertEquals(
            SourceSeparationCacheModelAvailability.ModelNotInstalled,
            provider.availability(fixture.manifest),
        )
    }

    @Test
    fun `changed sidecar rejects cache without hashing the large model`() {
        val fixture = fixture()
        fixture.installed.sidecarFile.writeText("{}")
        val provider = SourceSeparationMultiStemCacheAvailabilityProvider { fixture.installed }

        assertEquals(
            SourceSeparationCacheModelAvailability.ContractMismatch,
            provider.availability(fixture.manifest),
        )
    }

    private fun fixture(): Fixture {
        val root = temporary.newFolder()
        val serialized = requireNotNull(javaClass.classLoader?.getResourceAsStream(
            "source-separation/research-contracts/htdemucs-4s-official-base-fp32.json",
        )).bufferedReader().use { it.readText() }
        val executable = SourceSeparationMultiTensorExecutableContractLoader.load(serialized)
        val model = root.resolve(executable.artifact.fileName)
        RandomAccessFile(model, "rw").use { it.setLength(executable.artifact.byteSize) }
        val sidecar = root.resolve("${executable.artifact.fileName}.json").apply {
            writeText(serialized)
        }
        val installed = SourceSeparationInstalledMultiStemModel(
            modelId = executable.modelContract.modelId,
            displayName = executable.modelContract.displayName,
            modelFile = model,
            sidecarFile = sidecar,
            modelByteSize = executable.artifact.byteSize,
            modelSha256 = executable.artifact.sha256,
            contractId = executable.modelContract.contractId,
            pipelineId = executable.modelContract.pipelineContract.pipelineId,
            installedAtEpochMs = 1L,
        )
        val contract = SourceSeparationCacheContractSnapshot.fromMultiTensor(executable)
        val source = SourceSeparationCacheSourceIdentity(
            audioFingerprint = "encoded-samples-v1:${"a".repeat(64)}",
            encodedSampleCount = 1L,
            encodedByteCount = 1L,
            mimeType = "audio/wav",
            sourceSampleRate = 44_100,
            sourceChannelCount = 2,
            sourceDurationUs = 1_000_000L,
        )
        val identity = contract.identity(
            source,
            HtdemucsSourceSeparationEngine.HTDEMUCS_CPU_PROFILE_ID,
        )
        val manifest = SourceSeparationCacheManifest(
            manifestSchemaVersion = SourceSeparationCacheManifest.SCHEMA_VERSION,
            cacheKey = identity.cacheKey,
            identity = identity,
            contract = contract,
            state = SourceSeparationCacheManifestState.Partial,
            song = SourceSeparationCacheSongLocator(
                1L,
                "content://media/1",
                "/music/source.wav",
                "Source",
                "Artist",
                "Album",
            ),
            sourceDiagnostics = SourceSeparationCacheSourceDiagnostics(1L, 1L, 1_000L),
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 1L,
        )
        return Fixture(installed, manifest)
    }

    private data class Fixture(
        val installed: SourceSeparationInstalledMultiStemModel,
        val manifest: SourceSeparationCacheManifest,
    )
}
