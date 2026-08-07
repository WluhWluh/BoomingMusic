package com.mardous.booming.separation.model.contract

import com.mardous.booming.separation.delivery.ModelDeliveryProvider
import com.mardous.booming.separation.delivery.SourceSeparationDeliveryCapabilities
import com.mardous.booming.separation.delivery.SourceSeparationDeliveryOperation
import com.mardous.booming.separation.delivery.SourceSeparationDeliveryPayload
import com.mardous.booming.separation.delivery.SourceSeparationDeliveryReference
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

class SourceSeparationMultiStemModelStoreTest {
    @Test
    fun `release pair installs atomically and repeated install does not redownload`() {
        val fixture = fixture()
        val provider = FixtureProvider(fixture.artifactBytes, fixture.sidecarBytes)
        val root = Files.createTempDirectory("bss-multistem-models").toFile()
        try {
            val store = SourceSeparationMultiStemModelStore(root, fixture.catalog, provider)

            val installed = store.install(fixture.modelId)

            assertTrue(installed.modelFile.isFile)
            assertTrue(installed.sidecarFile.isFile)
            assertEquals(fixture.modelId, installed.modelId)
            assertEquals(2, provider.acquireCount.get())
            assertEquals(installed, store.installed(fixture.modelId))

            val repeated = store.install(fixture.modelId)
            assertEquals(installed, repeated)
            assertEquals(2, provider.acquireCount.get())
            assertEquals(1, store.installedModels().size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `sidecar binding failure removes staging and publishes nothing`() {
        val fixture = fixture()
        val invalidContract = fixture.contract.copy(
            artifact = fixture.contract.artifact.copy(sha256 = "0".repeat(64)),
        )
        val invalid = fixture.copy(
            sidecarBytes = SourceSeparationModelMetadata.json.encodeToString(invalidContract).toByteArray(),
        )
        val provider = FixtureProvider(invalid.artifactBytes, invalid.sidecarBytes)
        val root = Files.createTempDirectory("bss-multistem-models-invalid").toFile()
        try {
            val store = SourceSeparationMultiStemModelStore(root, invalid.catalog, provider)

            assertThrows(IllegalArgumentException::class.java) {
                store.install(invalid.modelId)
            }

            assertTrue(store.installedModels().isEmpty())
            assertFalse(
                File(root, SourceSeparationMultiStemModelStore.MODEL_ROOT_DIRECTORY)
                    .listFiles()
                    ?.any { it.name != ".staging" } == true,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private fun fixture(): Fixture {
        val modelId = "htdemucs_4s_core_canonical_7p8s_fp32_v1_0_0"
        val baseline = loadContract("htdemucs-4s-official-base-fp32.json")
        val artifactBytes = byteArrayOf(1, 2, 3, 4, 5)
        val artifactSha256 = sha256(artifactBytes)
        val contract = baseline.copy(
            artifact = baseline.artifact.copy(
                byteSize = artifactBytes.size.toLong(),
                sha256 = artifactSha256,
            ),
        )
        val sidecarBytes = SourceSeparationModelMetadata.json
            .encodeToString(contract)
            .toByteArray()
        val modelFileName = "htdemucs_4s.core.canonical_7p8s.fp32.tflite"
        val releaseUrl = "https://github.com/WluhWluh/bss-tflite/releases/download/v0.2.0-experimental.1"
        val entry = SourceSeparationReleaseCatalogEntry(
            activationPolicy = "selectable-experimental",
            allowedBackends = listOf("cpu"),
            artifact = SourceSeparationReleaseArtifact(
                byteSize = artifactBytes.size.toLong(),
                fileName = modelFileName,
                sha256 = artifactSha256,
                url = "$releaseUrl/$modelFileName",
            ),
            artifactFamily = "htdemucs-multistem",
            contract = SourceSeparationReleaseContractArtifact(
                byteSize = sidecarBytes.size.toLong(),
                contractId = contract.modelContract.contractId,
                fileName = "$modelFileName.json",
                schemaId = "multitensor-v1",
                sha256 = sha256(sidecarBytes),
                url = "$releaseUrl/$modelFileName.json",
            ),
            displayName = contract.modelContract.displayName,
            isDefault = true,
            modelId = modelId,
            pipelineId = "booming-ss-htdemucs-neural-core",
            supportLevel = "experimental",
            validation = mapOf(
                "canonicalDeviceGate" to "passed-s25-phase6-v2",
                "fullSong" to "pending",
                "lifecycle" to "pending",
                "listening" to "pending",
            ),
        )
        return Fixture(
            catalog = SourceSeparationReleaseCatalog(
                catalogId = "booming-ss-model-catalog-v3",
                catalogSchemaVersion = 3,
                entries = listOf(entry),
                releaseTag = "v0.2.0-experimental.1",
            ),
            modelId = modelId,
            contract = contract,
            artifactBytes = artifactBytes,
            sidecarBytes = sidecarBytes,
        )
    }

    private fun loadContract(name: String): SourceSeparationMultiTensorExecutableContract {
        val path = "source-separation/research-contracts/$name"
        val serialized = requireNotNull(javaClass.classLoader?.getResourceAsStream(path)) {
            "Missing executable contract asset $path"
        }.bufferedReader().use { it.readText() }
        return SourceSeparationMultiTensorExecutableContractLoader.load(serialized)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private data class Fixture(
        val catalog: SourceSeparationReleaseCatalog,
        val modelId: String,
        val contract: SourceSeparationMultiTensorExecutableContract,
        val artifactBytes: ByteArray,
        val sidecarBytes: ByteArray,
    )

    private class FixtureProvider(
        modelBytes: ByteArray,
        sidecarBytes: ByteArray,
    ) : ModelDeliveryProvider {
        override val providerId = "github"
        override val capabilities = SourceSeparationDeliveryCapabilities(
            operations = setOf(SourceSeparationDeliveryOperation.Acquire),
            supportsPlatformManagedPayloads = false,
        )
        private val artifacts = mapOf(
            "htdemucs_4s.core.canonical_7p8s.fp32.tflite" to modelBytes,
            "htdemucs_4s.core.canonical_7p8s.fp32.tflite.json" to sidecarBytes,
        )
        val acquireCount = AtomicInteger()

        override fun supports(reference: SourceSeparationDeliveryReference): Boolean =
            reference.providerId == providerId && reference.artifactId in artifacts

        override fun acquire(reference: SourceSeparationDeliveryReference): SourceSeparationDeliveryPayload {
            require(supports(reference))
            acquireCount.incrementAndGet()
            return Payload(reference, artifacts.getValue(reference.artifactId))
        }
    }

    private class Payload(
        override val reference: SourceSeparationDeliveryReference,
        private val bytes: ByteArray,
    ) : SourceSeparationDeliveryPayload {
        override val byteSize: Long = bytes.size.toLong()
        override fun openStream(): InputStream = ByteArrayInputStream(bytes)
        override fun close() = Unit
    }
}
