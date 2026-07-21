package com.mardous.booming.separation.model.preset

import com.mardous.booming.separation.model.MdxRuntimeAbi
import com.mardous.booming.separation.model.MdxRuntimePlatform
import com.mardous.booming.separation.model.contract.CatalogActivationPolicy
import com.mardous.booming.separation.model.contract.CatalogArtifactRecord
import com.mardous.booming.separation.model.contract.CatalogConversionState
import com.mardous.booming.separation.model.contract.CatalogEntry
import com.mardous.booming.separation.model.contract.CatalogInventoryReference
import com.mardous.booming.separation.model.contract.CatalogReleaseMaturity
import com.mardous.booming.separation.model.contract.CatalogReleaseAsset
import com.mardous.booming.separation.model.contract.CatalogStemUi
import com.mardous.booming.separation.model.contract.CatalogSupportLevel
import com.mardous.booming.separation.model.contract.CatalogTfliteArtifact
import com.mardous.booming.separation.model.contract.CatalogValidationState
import com.mardous.booming.separation.model.contract.CatalogValidationStatus
import com.mardous.booming.separation.model.contract.ContractArtifact
import com.mardous.booming.separation.model.contract.ContractArtifactFormat
import com.mardous.booming.separation.model.contract.ContractDsp
import com.mardous.booming.separation.model.contract.ContractDtype
import com.mardous.booming.separation.model.contract.ContractResidualRule
import com.mardous.booming.separation.model.contract.ContractStem
import com.mardous.booming.separation.model.contract.ContractStemSemantic
import com.mardous.booming.separation.model.contract.ContractTensor
import com.mardous.booming.separation.model.contract.ContractTensorLayout
import com.mardous.booming.separation.model.contract.ContractWindow
import com.mardous.booming.separation.model.contract.PipelineCompatibility
import com.mardous.booming.separation.model.contract.SourceSeparationCustomModelProfile
import com.mardous.booming.separation.model.contract.SourceSeparationModelCatalog
import com.mardous.booming.separation.model.contract.StemContract
import com.mardous.booming.separation.model.contract.TensorContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.security.MessageDigest

class SourceSeparationPresetRepositoryTest {
    @Test
    fun `official installation is hash aware and does not become active`() {
        val payload = "official-model".encodeToByteArray()
        fixture(payload).use { fixture ->
            val installed = fixture.repository.install(
                input = ByteArrayInputStream(payload),
                originalFileName = "renamed-import.tflite",
                origin = SourceSeparationInstalledPresetOrigin.OfficialDownload,
            )

            assertEquals("official_model", installed.modelId)
            assertEquals(SourceSeparationPresetBindingKind.Official, installed.bindingKind)
            assertEquals("Official Model", installed.displayName)
            assertEquals("official-model.tflite", installed.file.name)
            assertEquals(installed.sha256, installed.file.parentFile?.name)
            assertTrue(installed.file.isFile)
            assertEquals(SourceSeparationActivePresetState.None, fixture.repository.activeModel())
        }
    }

    @Test
    fun `unknown import requires a verified custom profile and active deletion is blocked`() {
        val payload = "custom-model".encodeToByteArray()
        fixture("official-model".encodeToByteArray()).use { fixture ->
            assertThrows(IllegalArgumentException::class.java) {
                fixture.repository.install(
                    input = ByteArrayInputStream(payload),
                    originalFileName = "custom.tflite",
                    origin = SourceSeparationInstalledPresetOrigin.ImportedFile,
                )
            }

            val installed = fixture.repository.install(
                input = ByteArrayInputStream(payload),
                originalFileName = "custom.tflite",
                origin = SourceSeparationInstalledPresetOrigin.ImportedFile,
                customProfile = customProfile(payload),
            )
            assertEquals(SourceSeparationPresetBindingKind.CustomProfile, installed.bindingKind)
            assertEquals(listOf("custom-profile"), fixture.repository.customProfiles().map { it.profileId })
            assertEquals(SourceSeparationActivePresetState.None, fixture.repository.activeModel())

            val reference = fixture.repository.activate(
                sha256 = installed.sha256,
                platform = MdxRuntimePlatform(35, MdxRuntimeAbi.Arm64V8a),
                scope = SourceSeparationPresetSelectionScope.InternalValidation,
            )
            assertEquals("custom_model", reference.modelId)
            assertEquals("custom-profile", reference.profileId)
            assertThrows(SourceSeparationPresetDeletionException::class.java) {
                fixture.repository.delete(installed.sha256)
            }
            assertThrows(SourceSeparationPresetProfileException::class.java) {
                fixture.repository.deleteCustomProfile("custom-profile")
            }

            fixture.repository.setPendingActiveModel(null)
            assertTrue(fixture.repository.delete(installed.sha256))
            assertFalse(installed.file.parentFile?.exists() ?: true)
            assertEquals("custom-profile", fixture.repository.customProfiles().single().profileId)
            assertTrue(fixture.repository.deleteCustomProfile("custom-profile"))
            assertTrue(fixture.repository.customProfiles().isEmpty())
        }
    }

    @Test
    fun `existing active reference is retained when its installed artifact is absent`() {
        fixture("official-model".encodeToByteArray()).use { fixture ->
            val reference = SourceSeparationActiveModelReference(
                modelId = "official_model",
                artifactSha256 = "a".repeat(64),
                contractSchemaVersion = 2,
            )

            fixture.repository.setPendingActiveModel(reference)

            val state = fixture.repository.activeModel()
            assertTrue(state is SourceSeparationActivePresetState.Reference)
            state as SourceSeparationActivePresetState.Reference
            assertEquals(reference, state.reference)
            assertEquals(null, state.installedModel)
        }
    }

    private fun fixture(officialPayload: ByteArray): RepositoryFixture {
        val root = Files.createTempDirectory("source-separation-preset-test").toFile()
        val store = InMemoryActiveModelStore()
        val repository = SourceSeparationPresetRepository(
            rootDirectory = root.resolve("models"),
            catalog = catalog(officialPayload),
            activeModelStore = store,
            clock = { 1_000L },
            customProfileStore = FileSourceSeparationCustomProfileStore(root.resolve("profiles")),
        )
        return RepositoryFixture(root, repository)
    }

    private fun catalog(officialPayload: ByteArray): SourceSeparationModelCatalog {
        val hash = sha256(officialPayload)
        return SourceSeparationModelCatalog(
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
                    artifactId = "official_model",
                    canonicalSourceId = "source:official.onnx",
                    sourceIds = listOf("source:official.onnx"),
                    plannedFileName = "official-model.tflite",
                    conversionState = CatalogConversionState.Released,
                    tflite = CatalogTfliteArtifact(
                        fileName = "official-model.tflite",
                        byteSize = officialPayload.size.toLong(),
                        sha256 = hash,
                        releaseAsset = CatalogReleaseAsset(
                            tag = "v1",
                            url = "https://example.com/download/v1/official-model.tflite",
                        ),
                    ),
                ),
            ),
            contracts = emptyList(),
            entries = listOf(
                CatalogEntry(
                    modelId = "official_model",
                    artifactId = "official_model",
                    displayName = "Official Model",
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
    }

    private fun customProfile(payload: ByteArray) = SourceSeparationCustomModelProfile(
        profileSchemaVersion = 1,
        profileId = "custom-profile",
        modelId = "custom_model",
        displayName = "Custom Model",
        artifact = ContractArtifact(
            fileName = "custom.tflite",
            byteSize = payload.size.toLong(),
            sha256 = sha256(payload),
            format = ContractArtifactFormat.TfliteFlatbuffer,
        ),
        tensorContract = TensorContract(
            input = tensor(),
            output = tensor(),
            batchSize = 1,
            complexChannelCount = 4,
        ),
        dsp = ContractDsp(
            sampleRate = 44_100,
            channelCount = 2,
            nFft = 6_144,
            hopLength = 1_024,
            dimF = 2_048,
            dimTPower = 8,
            modelTimeFrames = 256,
            window = ContractWindow.PeriodicHann,
            modelOutputScale = 1.035,
        ),
        stemContract = StemContract(
            modelOutput = ContractStem(ContractStemSemantic.Vocals, "Vocals"),
            residual = ContractStem(ContractStemSemantic.Instrumental, "Instrumental"),
            residualRule = ContractResidualRule.MixtureMinusScaledModelOutput,
        ),
        pipelineCompatibility = PipelineCompatibility(
            pipelineId = "booming-ss-mdx-stft",
            minimumVersion = 1,
            maximumVersion = 1,
        ),
        qualityUnverified = true,
    )

    private fun tensor() = ContractTensor(
        name = "input",
        dtype = ContractDtype.Float32,
        layout = ContractTensorLayout.Nhwc,
        shape = listOf(1, 2_048, 256, 4),
    )

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }

    private data class RepositoryFixture(
        val root: java.io.File,
        val repository: SourceSeparationPresetRepository,
    ) : AutoCloseable {
        override fun close() {
            root.deleteRecursively()
        }
    }

    private class InMemoryActiveModelStore : SourceSeparationActiveModelStore {
        private var value: SourceSeparationActiveModelReference? = null

        override fun read(): SourceSeparationActiveModelReference? = value

        override fun write(reference: SourceSeparationActiveModelReference?) {
            value = reference
        }
    }
}
