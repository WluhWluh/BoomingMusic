package com.mardous.booming.separation.model.preset

import com.mardous.booming.separation.model.contract.CatalogActivationPolicy
import com.mardous.booming.separation.model.contract.CatalogArtifactRecord
import com.mardous.booming.separation.model.contract.CatalogConversionState
import com.mardous.booming.separation.model.contract.CatalogEntry
import com.mardous.booming.separation.model.contract.CatalogInventoryReference
import com.mardous.booming.separation.model.contract.CatalogReleaseAsset
import com.mardous.booming.separation.model.contract.CatalogReleaseMaturity
import com.mardous.booming.separation.model.contract.CatalogStemUi
import com.mardous.booming.separation.model.contract.CatalogSupportLevel
import com.mardous.booming.separation.model.contract.CatalogTfliteArtifact
import com.mardous.booming.separation.model.contract.CatalogValidationState
import com.mardous.booming.separation.model.contract.CatalogValidationStatus
import com.mardous.booming.separation.model.contract.SourceSeparationModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest

class SourceSeparationPresetDownloaderTest {
    @Test
    fun `download verifies immutable asset and leaves active model unchanged`() {
        val payload = ByteArray(700_000) { (it % 251).toByte() }
        fixture(payload).use { fixture ->
            val progress = mutableListOf<SourceSeparationPresetDownloadProgress>()
            val downloader = fixture.downloader(
                connections = mapOf(PRIMARY_URL to FakeConnection(payload = payload)),
            )

            val installed = downloader.download(MODEL_ID, progress::add)

            assertEquals(MODEL_ID, installed.modelId)
            assertEquals(sha256(payload), installed.sha256)
            assertEquals(SourceSeparationActivePresetState.None, fixture.repository.activeModel())
            assertEquals(0L, progress.first().downloadedBytes)
            assertEquals(payload.size.toLong(), progress.last().downloadedBytes)
            assertTrue(progress.none(SourceSeparationPresetDownloadProgress::usingMirror))
        }
    }

    @Test
    fun `size or hash mismatch never installs an official model`() {
        val expected = "expected-model".encodeToByteArray()
        fixture(expected).use { fixture ->
            val wrongSizeDownloader = fixture.downloader(
                connections = mapOf(
                    PRIMARY_URL to FakeConnection(
                        payload = expected,
                        declaredLength = expected.size.toLong() + 1L,
                    ),
                ),
            )
            assertThrows(SourceSeparationPresetDownloadException::class.java) {
                wrongSizeDownloader.download(MODEL_ID)
            }
            assertTrue(fixture.repository.installedModels().isEmpty())

            val wrongHash = expected.copyOf().also { it[0] = (it[0] + 1).toByte() }
            val wrongHashDownloader = fixture.downloader(
                connections = mapOf(PRIMARY_URL to FakeConnection(payload = wrongHash)),
            )
            assertThrows(SourceSeparationPresetDownloadIntegrityException::class.java) {
                wrongHashDownloader.download(MODEL_ID)
            }
            assertTrue(fixture.repository.installedModels().isEmpty())
        }
    }

    @Test
    fun `retryable primary failure uses the mirror without changing asset identity`() {
        val payload = "mirror-model".encodeToByteArray()
        fixture(payload).use { fixture ->
            val opened = mutableListOf<String>()
            val downloader = fixture.downloader(
                connections = mapOf(
                    PRIMARY_URL to FakeConnection(payload, responseCode = 503),
                    MIRROR_URL to FakeConnection(payload),
                ),
                mirrorUrl = MIRROR_URL,
                opened = opened,
            )
            val progress = mutableListOf<SourceSeparationPresetDownloadProgress>()

            val installed = downloader.download(MODEL_ID, progress::add)

            assertEquals(listOf(PRIMARY_URL, MIRROR_URL), opened)
            assertEquals(sha256(payload), installed.sha256)
            assertTrue(progress.all(SourceSeparationPresetDownloadProgress::usingMirror))
        }
    }

    @Test
    fun `cancel during copy removes staging data and does not retry`() {
        val payload = ByteArray(700_000) { (it % 239).toByte() }
        fixture(payload).use { fixture ->
            val opened = mutableListOf<String>()
            lateinit var downloader: SourceSeparationPresetDownloader
            downloader = fixture.downloader(
                connections = mapOf(PRIMARY_URL to FakeConnection(payload)),
                mirrorUrl = MIRROR_URL,
                opened = opened,
            )

            assertThrows(SourceSeparationPresetDownloadCanceledException::class.java) {
                downloader.download(MODEL_ID) { progress ->
                    if (progress.downloadedBytes > 0L) downloader.cancel(MODEL_ID)
                }
            }

            assertEquals(listOf(PRIMARY_URL), opened)
            assertFalse(downloader.isDownloading(MODEL_ID))
            assertTrue(fixture.repository.installedModels().isEmpty())
            assertTrue(
                fixture.modelsRoot.resolve(".staging").listFiles().isNullOrEmpty(),
            )
        }
    }

    private fun fixture(payload: ByteArray): DownloadFixture {
        val root = Files.createTempDirectory("preset-downloader-test").toFile()
        val modelsRoot = root.resolve("models")
        val repository = SourceSeparationPresetRepository(
            rootDirectory = modelsRoot,
            catalog = catalog(payload),
            activeModelStore = InMemoryActiveModelStore(),
            clock = { 1_000L },
            customProfileStore = FileSourceSeparationCustomProfileStore(root.resolve("profiles")),
        )
        return DownloadFixture(root, modelsRoot, repository)
    }

    private fun catalog(payload: ByteArray) = SourceSeparationModelCatalog(
        catalogSchemaVersion = 2,
        contractSchemaVersion = 2,
        catalogId = "booming-ss-model-catalog-v2",
        inventory = CatalogInventoryReference(
            fileName = "candidate-sources-v1.json",
            inventorySchemaVersion = 1,
            sha256 = "a".repeat(64),
        ),
        sources = emptyList(),
        artifacts = listOf(
            CatalogArtifactRecord(
                artifactId = MODEL_ID,
                canonicalSourceId = "source:model.onnx",
                sourceIds = listOf("source:model.onnx"),
                plannedFileName = FILE_NAME,
                conversionState = CatalogConversionState.Released,
                tflite = CatalogTfliteArtifact(
                    fileName = FILE_NAME,
                    byteSize = payload.size.toLong(),
                    sha256 = sha256(payload),
                    releaseAsset = CatalogReleaseAsset(tag = "v1", url = PRIMARY_URL),
                ),
            ),
        ),
        contracts = emptyList(),
        entries = listOf(
            CatalogEntry(
                modelId = MODEL_ID,
                artifactId = MODEL_ID,
                displayName = "Test Model",
                supportLevel = CatalogSupportLevel.DownloadOnly,
                activationPolicy = CatalogActivationPolicy.BlockedUntilReviewedContract,
                releaseMaturity = CatalogReleaseMaturity.Candidate,
                downloadActivatesModel = false,
                isDefault = false,
                stemUi = CatalogStemUi.VocalsInstrumental,
                validation = CatalogValidationState(
                    conversion = CatalogValidationStatus.Passed,
                    desktopNumerical = CatalogValidationStatus.Passed,
                    androidCpu = CatalogValidationStatus.Pending,
                    androidGpu = CatalogValidationStatus.Pending,
                    fullSong = CatalogValidationStatus.Pending,
                ),
            ),
        ),
        runtimeQualifications = emptyList(),
    )

    private data class DownloadFixture(
        val root: java.io.File,
        val modelsRoot: java.io.File,
        val repository: SourceSeparationPresetRepository,
    ) : AutoCloseable {
        fun downloader(
            connections: Map<String, FakeConnection>,
            mirrorUrl: String? = null,
            opened: MutableList<String> = mutableListOf(),
        ) = SourceSeparationPresetDownloader(
            repository = repository,
            connectionFactory = SourceSeparationPresetConnectionFactory { url ->
                opened += url
                connections[url] ?: error("Unexpected URL: $url")
            },
            mirrorResolver = SourceSeparationPresetMirrorResolver { mirrorUrl },
        )

        override fun close() {
            root.deleteRecursively()
        }
    }

    private class FakeConnection(
        payload: ByteArray,
        override val responseCode: Int = 200,
        declaredLength: Long = payload.size.toLong(),
    ) : SourceSeparationPresetConnection {
        override val contentLength: Long = declaredLength
        override val inputStream: InputStream = ByteArrayInputStream(payload)

        override fun close() = Unit
    }

    private class InMemoryActiveModelStore : SourceSeparationActiveModelStore {
        private var value: SourceSeparationActiveModelReference? = null
        private var pendingValue: SourceSeparationActiveModelReference? = null

        override fun read(): SourceSeparationActiveModelReference? = value

        override fun write(reference: SourceSeparationActiveModelReference?) {
            value = reference
        }

        override fun readPending(): SourceSeparationActiveModelReference? = pendingValue

        override fun writePending(reference: SourceSeparationActiveModelReference?) {
            pendingValue = reference
        }
    }

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }

    private companion object {
        const val MODEL_ID = "test_model"
        const val FILE_NAME = "test-model.tflite"
        const val PRIMARY_URL = "https://example.com/download/v1/test-model.tflite"
        const val MIRROR_URL = "https://mirror.example/test-model.tflite"
    }
}
