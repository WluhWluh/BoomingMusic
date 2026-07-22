package com.mardous.booming.separation.model.preset

import com.mardous.booming.separation.model.MdxRuntimeAbi
import com.mardous.booming.separation.model.MdxRuntimePlatform
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
import com.mardous.booming.separation.model.contract.ContractArtifact
import com.mardous.booming.separation.model.contract.ContractArtifactFormat
import com.mardous.booming.separation.model.contract.ContractConversion
import com.mardous.booming.separation.model.contract.ContractDsp
import com.mardous.booming.separation.model.contract.ContractDtype
import com.mardous.booming.separation.model.contract.ContractResidualRule
import com.mardous.booming.separation.model.contract.ContractSource
import com.mardous.booming.separation.model.contract.ContractStem
import com.mardous.booming.separation.model.contract.ContractStemSemantic
import com.mardous.booming.separation.model.contract.ContractTensor
import com.mardous.booming.separation.model.contract.ContractTensorLayout
import com.mardous.booming.separation.model.contract.ContractWindow
import com.mardous.booming.separation.model.contract.PipelineCompatibility
import com.mardous.booming.separation.model.contract.SourceSeparationModelCatalog
import com.mardous.booming.separation.model.contract.SourceSeparationModelContract
import com.mardous.booming.separation.model.contract.SourceSeparationModelMetadata
import com.mardous.booming.separation.model.contract.StemContract
import com.mardous.booming.separation.model.contract.TensorContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.security.MessageDigest

class SourceSeparationPresetImportCoordinatorTest {
    @Test
    fun `known hash installs as an official preset without changing active model`() {
        val payload = "official-model".encodeToByteArray()
        fixture(payload).use { fixture ->
            val outcome = fixture.coordinator.begin(
                input = ByteArrayInputStream(payload),
                originalFileName = "renamed-model.tflite",
            ) as SourceSeparationPresetImportOutcome.Installed

            assertEquals(SourceSeparationPresetBindingKind.Official, outcome.installed.bindingKind)
            assertEquals("official_model", outcome.installed.modelId)
            assertNull(fixture.coordinator.pending())
            assertEquals(SourceSeparationActivePresetState.None, fixture.repository.activeModel())
        }
    }

    @Test
    fun `unknown model accepts only an exact-name hash-matching sidecar`() {
        val payload = "sidecar-model".encodeToByteArray()
        fixture("official-model".encodeToByteArray()).use { fixture ->
            val awaiting = fixture.coordinator.begin(
                input = ByteArrayInputStream(payload),
                originalFileName = "sidecar-model.tflite",
            ) as SourceSeparationPresetImportOutcome.AwaitingMetadata

            assertEquals(sha256(payload), awaiting.pending.sha256)
            assertThrows(IllegalArgumentException::class.java) {
                fixture.coordinator.installWithSidecar(
                    sidecarInput = ByteArrayInputStream(
                        SourceSeparationModelMetadata.json.encodeToString(
                            sidecarContract(payload, "sidecar-model.tflite"),
                        ).encodeToByteArray(),
                    ),
                    sidecarFileName = "wrong-name.tflite.json",
                )
            }
            assertNotNull(fixture.coordinator.pending())
            assertTrue(fixture.repository.installedModels().isEmpty())
            assertThrows(IllegalArgumentException::class.java) {
                fixture.coordinator.installWithSidecar(
                    sidecarInput = ByteArrayInputStream(
                        SourceSeparationModelMetadata.json.encodeToString(
                            sidecarContract(
                                payload = "different-model".encodeToByteArray(),
                                fileName = "sidecar-model.tflite",
                            ),
                        ).encodeToByteArray(),
                    ),
                    sidecarFileName = "sidecar-model.tflite.json",
                )
            }
            assertNotNull(fixture.coordinator.pending())

            val installed = fixture.coordinator.installWithSidecar(
                sidecarInput = ByteArrayInputStream(
                    SourceSeparationModelMetadata.json.encodeToString(
                        sidecarContract(payload, "sidecar-model.tflite"),
                    ).encodeToByteArray(),
                ),
                sidecarFileName = "sidecar-model.tflite.json",
            )

            assertEquals(SourceSeparationPresetBindingKind.Sidecar, installed.installed.bindingKind)
            assertNull(fixture.coordinator.pending())
            assertEquals(SourceSeparationActivePresetState.None, fixture.repository.activeModel())
        }
    }

    @Test
    fun `manual profile remains quality-unverified and import does not activate it`() {
        val payload = "manual-model".encodeToByteArray()
        fixture("official-model".encodeToByteArray()).use { fixture ->
            fixture.coordinator.begin(
                input = ByteArrayInputStream(payload),
                originalFileName = "manual-model.tflite",
            )

            val outcome = fixture.coordinator.installWithManualProfile(manualDraft())

            assertEquals(SourceSeparationPresetBindingKind.CustomProfile, outcome.installed.bindingKind)
            assertTrue(requireNotNull(outcome.installed.customProfile).qualityUnverified)
            assertTrue(
                outcome.structuralInspection is SourceSeparationPresetStructuralInspection.Compatible,
            )
            assertNull(fixture.coordinator.pending())
            assertEquals(SourceSeparationActivePresetState.None, fixture.repository.activeModel())
        }
    }

    @Test
    fun `structural rejection preserves pending import without installing a model`() {
        val payload = "invalid-model".encodeToByteArray()
        fixture(
            officialPayload = "official-model".encodeToByteArray(),
            structuralInspector = SourceSeparationPresetStructuralInspector { _, _ ->
                SourceSeparationPresetStructuralInspection.Incompatible("Expected one input and one output")
            },
        ).use { fixture ->
            fixture.coordinator.begin(
                input = ByteArrayInputStream(payload),
                originalFileName = "invalid-model.tflite",
            )

            assertThrows(SourceSeparationPresetProfileException::class.java) {
                fixture.coordinator.installWithManualProfile(manualDraft())
            }
            assertNotNull(fixture.coordinator.pending())
            assertTrue(fixture.repository.installedModels().isEmpty())
            assertTrue(fixture.coordinator.discard())
            assertNull(fixture.coordinator.pending())
        }
    }

    private fun fixture(
        officialPayload: ByteArray,
        structuralInspector: SourceSeparationPresetStructuralInspector =
            SourceSeparationPresetStructuralInspector { _, _ ->
                SourceSeparationPresetStructuralInspection.Compatible(1, 1)
            },
    ): Fixture {
        val root = Files.createTempDirectory("source-separation-import-test").toFile()
        val repository = SourceSeparationPresetRepository(
            rootDirectory = root.resolve("models"),
            catalog = catalog(officialPayload),
            activeModelStore = InMemoryActiveModelStore(),
            clock = { 1_000L },
            customProfileStore = FileSourceSeparationCustomProfileStore(root.resolve("profiles")),
            structuralInspector = structuralInspector,
        )
        return Fixture(
            root = root,
            repository = repository,
            coordinator = SourceSeparationPresetImportCoordinator(
                repository = repository,
                stagingDirectory = root.resolve("imports"),
                structuralInspector = structuralInspector,
                platformProvider = { MdxRuntimePlatform(35, MdxRuntimeAbi.Arm64V8a) },
            ),
        )
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
                artifactId = "official_model",
                canonicalSourceId = "source:official.onnx",
                sourceIds = listOf("source:official.onnx"),
                plannedFileName = "official-model.tflite",
                conversionState = CatalogConversionState.Released,
                tflite = CatalogTfliteArtifact(
                    fileName = "official-model.tflite",
                    byteSize = payload.size.toLong(),
                    sha256 = sha256(payload),
                    releaseAsset = CatalogReleaseAsset(
                        tag = "v1",
                        url = "https://example.com/v1/official-model.tflite",
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

    private fun sidecarContract(
        payload: ByteArray,
        fileName: String,
    ) = SourceSeparationModelContract(
        contractSchemaVersion = 2,
        contractId = "sidecar_model@2",
        modelId = "sidecar_model",
        displayName = "Sidecar Model",
        artifact = ContractArtifact(
            fileName = fileName,
            byteSize = payload.size.toLong(),
            sha256 = sha256(payload),
            format = ContractArtifactFormat.TfliteFlatbuffer,
        ),
        source = ContractSource(
            canonicalSourceId = "source:sidecar.onnx",
            fileName = "sidecar.onnx",
            url = "https://example.com/sidecar.onnx",
            byteSize = payload.size.toLong(),
            sha256 = "b".repeat(64),
            attribution = listOf("Test"),
        ),
        conversion = ContractConversion(
            repository = "https://example.com/converter",
            revision = "c".repeat(40),
            pipelineVersion = 1,
            toolVersions = mapOf("converter" to "1"),
        ),
        tensorContract = TensorContract(
            input = tensor("input"),
            output = tensor("output"),
            batchSize = 1,
            complexChannelCount = 4,
        ),
        dsp = dsp(),
        stemContract = stems(),
        pipelineCompatibility = pipeline(),
    )

    private fun manualDraft() = SourceSeparationManualModelProfileDraft(
        modelId = "manual_model",
        displayName = "Manual Model",
        inputTensorName = "input",
        outputTensorName = "output",
        sampleRate = 44_100,
        nFft = 6_144,
        hopLength = 1_024,
        dimF = 2_048,
        dimTPower = 8,
        modelOutputScale = 1.035,
        modelOutputStem = SourceSeparationManualModelStem.Vocals,
    )

    private fun tensor(name: String) = ContractTensor(
        name = name,
        dtype = ContractDtype.Float32,
        layout = ContractTensorLayout.Nhwc,
        shape = listOf(1, 2_048, 256, 4),
    )

    private fun dsp() = ContractDsp(
        sampleRate = 44_100,
        channelCount = 2,
        nFft = 6_144,
        hopLength = 1_024,
        dimF = 2_048,
        dimTPower = 8,
        modelTimeFrames = 256,
        window = ContractWindow.PeriodicHann,
        modelOutputScale = 1.035,
    )

    private fun stems() = StemContract(
        modelOutput = ContractStem(ContractStemSemantic.Vocals, "Vocals"),
        residual = ContractStem(ContractStemSemantic.Instrumental, "Instrumental"),
        residualRule = ContractResidualRule.MixtureMinusScaledModelOutput,
    )

    private fun pipeline() = PipelineCompatibility(
        pipelineId = "booming-ss-mdx-stft",
        minimumVersion = 1,
        maximumVersion = 1,
    )

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }

    private data class Fixture(
        val root: java.io.File,
        val repository: SourceSeparationPresetRepository,
        val coordinator: SourceSeparationPresetImportCoordinator,
    ) : AutoCloseable {
        override fun close() {
            root.deleteRecursively()
        }
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
}
