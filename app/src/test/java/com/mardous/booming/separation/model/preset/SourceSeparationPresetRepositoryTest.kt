package com.mardous.booming.separation.model.preset

import com.mardous.booming.separation.cache.v2.resolveActiveCacheModel
import com.mardous.booming.separation.model.MdxRuntimeAbi
import com.mardous.booming.separation.model.MdxRuntimePlatform
import com.mardous.booming.separation.model.MdxRuntimeProfiles
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
import com.mardous.booming.separation.model.contract.CatalogRuntimeQualification
import com.mardous.booming.separation.model.contract.ContractAbi
import com.mardous.booming.separation.model.contract.ContractArtifact
import com.mardous.booming.separation.model.contract.ContractArtifactFormat
import com.mardous.booming.separation.model.contract.ContractBackend
import com.mardous.booming.separation.model.contract.ContractConversion
import com.mardous.booming.separation.model.contract.ContractDsp
import com.mardous.booming.separation.model.contract.ContractDtype
import com.mardous.booming.separation.model.contract.ContractResidualRule
import com.mardous.booming.separation.model.contract.ContractRuntimePrecision
import com.mardous.booming.separation.model.contract.ContractRuntimeQualificationStatus
import com.mardous.booming.separation.model.contract.ContractSource
import com.mardous.booming.separation.model.contract.ContractStem
import com.mardous.booming.separation.model.contract.ContractStemSemantic
import com.mardous.booming.separation.model.contract.ContractTensor
import com.mardous.booming.separation.model.contract.ContractTensorLayout
import com.mardous.booming.separation.model.contract.ContractWindow
import com.mardous.booming.separation.model.contract.PipelineCompatibility
import com.mardous.booming.separation.model.contract.SourceSeparationCustomModelProfile
import com.mardous.booming.separation.model.contract.SourceSeparationModelCatalog
import com.mardous.booming.separation.model.contract.SourceSeparationModelContract
import com.mardous.booming.separation.model.contract.StemContract
import com.mardous.booming.separation.model.contract.TensorContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SourceSeparationPresetRepositoryTest {
    @Test
    fun `active official preset resolves an immutable cache contract and artifact`() {
        val payload = "official-model".encodeToByteArray()
        fixture(
            officialPayload = payload,
            catalog = catalog(
                officialPayload = payload,
                supportLevel = CatalogSupportLevel.Recommended,
                activationPolicy = CatalogActivationPolicy.SelectableWhenQualified,
                includeReviewedContract = true,
            ),
        ).use { fixture ->
            val installed = fixture.repository.installOfficial(
                modelId = "official_model",
                input = ByteArrayInputStream(payload),
            )
            fixture.repository.activate(
                sha256 = installed.sha256,
                platform = MdxRuntimePlatform(35, MdxRuntimeAbi.Arm64V8a),
                scope = SourceSeparationPresetSelectionScope.InternalValidation,
            )

            val resolved = requireNotNull(fixture.repository.resolveActiveCacheModel())

            assertEquals("official_model", resolved.contract.modelId)
            assertEquals("official_model@2", resolved.contract.contractId)
            assertEquals(installed.sha256, resolved.artifact.sha256)
            assertEquals("official_model@2", resolved.executionProfile.profileId)
        }
    }

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

            fixture.store.write(null)
            assertTrue(fixture.repository.delete(installed.sha256))
            assertFalse(installed.file.parentFile?.exists() ?: true)
            assertEquals("custom-profile", fixture.repository.customProfiles().single().profileId)
            assertTrue(fixture.repository.deleteCustomProfile("custom-profile"))
            assertTrue(fixture.repository.customProfiles().isEmpty())
        }
    }

    @Test
    fun `pending reference does not replace the active reference`() {
        fixture("official-model".encodeToByteArray()).use { fixture ->
            val pending = SourceSeparationActiveModelReference(
                modelId = "official_model",
                artifactSha256 = "a".repeat(64),
                contractSchemaVersion = 2,
            )

            fixture.repository.setPendingActiveModel(pending)

            val state = fixture.repository.activeModel()
            assertEquals(SourceSeparationActivePresetState.None, state)
            assertEquals(pending, fixture.repository.pendingActiveModel())
        }
    }

    @Test
    fun `restore retains a usable current model and records a different pending target`() {
        val payload = "official-model".encodeToByteArray()
        fixture(
            officialPayload = payload,
            catalog = catalog(payload, includeReviewedContract = true),
        ).use { fixture ->
            val installed = fixture.repository.install(
                input = ByteArrayInputStream(payload),
                originalFileName = "official-model.tflite",
                origin = SourceSeparationInstalledPresetOrigin.OfficialDownload,
            )
            val current = SourceSeparationActiveModelReference(
                modelId = installed.modelId,
                artifactSha256 = installed.sha256,
                contractSchemaVersion = 2,
            )
            fixture.store.write(current)
            val restored = current.copy(
                modelId = "other_model",
                artifactSha256 = "b".repeat(64),
            )

            fixture.repository.restoreActiveModelReference(restored)

            assertEquals(current, (fixture.repository.activeModel() as
                SourceSeparationActivePresetState.Reference).reference)
            assertEquals(restored, fixture.repository.pendingActiveModel())
        }
    }

    @Test
    fun `restore selects an exact installed model when no usable model is active`() {
        val payload = "official-model".encodeToByteArray()
        fixture(
            officialPayload = payload,
            catalog = catalog(payload, includeReviewedContract = true),
        ).use { fixture ->
            val installed = fixture.repository.install(
                input = ByteArrayInputStream(payload),
                originalFileName = "official-model.tflite",
                origin = SourceSeparationInstalledPresetOrigin.OfficialDownload,
            )
            val restored = SourceSeparationActiveModelReference(
                modelId = installed.modelId,
                artifactSha256 = installed.sha256,
                contractSchemaVersion = 2,
            )

            fixture.repository.restoreActiveModelReference(restored)

            assertEquals(restored, (fixture.repository.activeModel() as
                SourceSeparationActivePresetState.Reference).reference)
            assertEquals(null, fixture.repository.pendingActiveModel())
        }
    }

    @Test
    fun `restore keeps a missing model pending without creating an active selection`() {
        fixture("official-model".encodeToByteArray()).use { fixture ->
            val restored = SourceSeparationActiveModelReference(
                modelId = "official_model",
                artifactSha256 = "c".repeat(64),
                contractSchemaVersion = 2,
            )

            fixture.repository.restoreActiveModelReference(restored)

            assertEquals(SourceSeparationActivePresetState.None, fixture.repository.activeModel())
            assertEquals(restored, fixture.repository.pendingActiveModel())
        }
    }

    @Test
    fun `explicit activation clears a matching restored target`() {
        val payload = "official-model".encodeToByteArray()
        fixture(
            officialPayload = payload,
            catalog = catalog(
                officialPayload = payload,
                supportLevel = CatalogSupportLevel.Recommended,
                activationPolicy = CatalogActivationPolicy.SelectableWhenQualified,
                includeReviewedContract = true,
            ),
        ).use { fixture ->
            val installed = fixture.repository.installOfficial(
                modelId = "official_model",
                input = ByteArrayInputStream(payload),
            )
            val restored = SourceSeparationActiveModelReference(
                modelId = installed.modelId,
                artifactSha256 = installed.sha256,
                contractSchemaVersion = 2,
            )
            fixture.repository.setPendingActiveModel(restored)

            assertEquals(SourceSeparationActivePresetState.None, fixture.repository.activeModel())
            assertEquals(restored, fixture.repository.pendingActiveModel())

            fixture.repository.activate(
                sha256 = installed.sha256,
                platform = MdxRuntimePlatform(35, MdxRuntimeAbi.Arm64V8a),
                scope = SourceSeparationPresetSelectionScope.InternalValidation,
            )

            assertEquals(restored, (fixture.repository.activeModel() as
                SourceSeparationActivePresetState.Reference).reference)
            assertEquals(null, fixture.repository.pendingActiveModel())
        }
    }

    @Test
    fun `manual custom profile cannot activate when structural inspection is unavailable`() {
        val payload = "custom-model".encodeToByteArray()
        fixture(
            officialPayload = "official-model".encodeToByteArray(),
            structuralInspector = SourceSeparationPresetStructuralInspector { _, _ ->
                SourceSeparationPresetStructuralInspection.Unavailable("x86 inspection unavailable")
            },
        ).use { fixture ->
            val installed = fixture.repository.install(
                input = ByteArrayInputStream(payload),
                originalFileName = "custom.tflite",
                origin = SourceSeparationInstalledPresetOrigin.ImportedFile,
                customProfile = customProfile(payload),
            )

            val error = assertThrows(SourceSeparationPresetActivationException::class.java) {
                fixture.repository.activate(
                    sha256 = installed.sha256,
                    platform = MdxRuntimePlatform(26, MdxRuntimeAbi.X86),
                    scope = SourceSeparationPresetSelectionScope.InternalValidation,
                )
            }
            assertEquals(
                SourceSeparationPresetSelectionBlockReason
                    .CustomModelStructuralInspectionUnavailable,
                error.eligibility.blockReason,
            )
        }
    }

    @Test
    fun `experimental official model requires confirmation during internal validation`() {
        val payload = "experimental-model".encodeToByteArray()
        fixture(
            officialPayload = payload,
            catalog = catalog(
                officialPayload = payload,
                supportLevel = CatalogSupportLevel.Experimental,
                activationPolicy = CatalogActivationPolicy.SelectableExperimentalCpuOnly,
                includeReviewedContract = true,
            ),
        ).use { fixture ->
            val installed = fixture.repository.installOfficial(
                modelId = "official_model",
                input = ByteArrayInputStream(payload),
            )
            val pending = SourceSeparationActiveModelReference(
                modelId = installed.modelId,
                artifactSha256 = installed.sha256,
                contractSchemaVersion = 2,
            )
            fixture.repository.setPendingActiveModel(pending)

            val error = assertThrows(SourceSeparationPresetActivationException::class.java) {
                fixture.repository.activate(
                    sha256 = installed.sha256,
                    platform = MdxRuntimePlatform(35, MdxRuntimeAbi.Arm64V8a),
                    scope = SourceSeparationPresetSelectionScope.InternalValidation,
                )
            }
            assertEquals(
                SourceSeparationPresetSelectionBlockReason.ExperimentalConfirmationRequired,
                error.eligibility.blockReason,
            )
            assertEquals(SourceSeparationActivePresetState.None, fixture.repository.activeModel())

            fixture.repository.activate(
                sha256 = installed.sha256,
                platform = MdxRuntimePlatform(35, MdxRuntimeAbi.Arm64V8a),
                scope = SourceSeparationPresetSelectionScope.InternalValidation,
                experimentalConfirmed = true,
            )
            assertTrue(fixture.repository.activeModel() is SourceSeparationActivePresetState.Reference)
            assertEquals(null, fixture.repository.pendingActiveModel())
        }
    }

    @Test
    fun `different model payloads are copied concurrently before repository commit`() {
        val firstPayload = "first-custom-model".encodeToByteArray()
        val secondPayload = "second-custom-model".encodeToByteArray()
        val copyBarrier = CountDownLatch(2)
        val releaseCopies = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        fixture("official-model".encodeToByteArray()).use { fixture ->
            try {
                val first = executor.submit<SourceSeparationInstalledPreset> {
                    fixture.repository.install(
                        input = BarrierInputStream(firstPayload, copyBarrier, releaseCopies),
                        originalFileName = "first.tflite",
                        origin = SourceSeparationInstalledPresetOrigin.ImportedFile,
                        customProfile = customProfile(
                            payload = firstPayload,
                            profileId = "first-profile",
                            modelId = "first_model",
                            fileName = "first.tflite",
                        ),
                    )
                }
                val second = executor.submit<SourceSeparationInstalledPreset> {
                    fixture.repository.install(
                        input = BarrierInputStream(secondPayload, copyBarrier, releaseCopies),
                        originalFileName = "second.tflite",
                        origin = SourceSeparationInstalledPresetOrigin.ImportedFile,
                        customProfile = customProfile(
                            payload = secondPayload,
                            profileId = "second-profile",
                            modelId = "second_model",
                            fileName = "second.tflite",
                        ),
                    )
                }

                assertTrue(
                    "Both model copies should enter the copy phase concurrently",
                    copyBarrier.await(2, TimeUnit.SECONDS),
                )
                releaseCopies.countDown()

                assertEquals(sha256(firstPayload), first.get(2, TimeUnit.SECONDS).sha256)
                assertEquals(sha256(secondPayload), second.get(2, TimeUnit.SECONDS).sha256)
                assertEquals(2, fixture.repository.customProfiles().size)
            } finally {
                releaseCopies.countDown()
                executor.shutdownNow()
            }
        }
    }

    private fun fixture(
        officialPayload: ByteArray,
        catalog: SourceSeparationModelCatalog = catalog(officialPayload),
        structuralInspector: SourceSeparationPresetStructuralInspector =
            SourceSeparationPresetStructuralInspector { _, _ ->
                SourceSeparationPresetStructuralInspection.Compatible(1, 1)
            },
    ): RepositoryFixture {
        val root = Files.createTempDirectory("source-separation-preset-test").toFile()
        val store = InMemoryActiveModelStore()
        val repository = SourceSeparationPresetRepository(
            rootDirectory = root.resolve("models"),
            catalog = catalog,
            activeModelStore = store,
            clock = { 1_000L },
            customProfileStore = FileSourceSeparationCustomProfileStore(root.resolve("profiles")),
            structuralInspector = structuralInspector,
        )
        return RepositoryFixture(root, store, repository)
    }

    private fun catalog(
        officialPayload: ByteArray,
        supportLevel: CatalogSupportLevel = CatalogSupportLevel.DownloadOnly,
        activationPolicy: CatalogActivationPolicy =
            CatalogActivationPolicy.BlockedUntilReviewedContract,
        includeReviewedContract: Boolean = false,
    ): SourceSeparationModelCatalog {
        val hash = sha256(officialPayload)
        val contract = contract(officialPayload)
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
            contracts = if (includeReviewedContract) listOf(contract) else emptyList(),
            entries = listOf(
                CatalogEntry(
                    modelId = "official_model",
                    artifactId = "official_model",
                    displayName = "Official Model",
                    supportLevel = supportLevel,
                    activationPolicy = activationPolicy,
                    releaseMaturity = CatalogReleaseMaturity.Candidate,
                    downloadActivatesModel = false,
                    isDefault = false,
                    stemUi = CatalogStemUi.VocalsInstrumental,
                    contractId = contract.contractId.takeIf { includeReviewedContract },
                    validation = CatalogValidationState(
                        conversion = CatalogValidationStatus.Passed,
                        desktopNumerical = CatalogValidationStatus.Passed,
                        androidCpu = CatalogValidationStatus.Pending,
                        androidGpu = CatalogValidationStatus.Pending,
                        fullSong = CatalogValidationStatus.Pending,
                    ),
                ),
            ),
            runtimeQualifications = if (includeReviewedContract) {
                listOf(
                    CatalogRuntimeQualification(
                        modelId = "official_model",
                        contractId = contract.contractId,
                        artifactSha256 = hash,
                        runtimeId = "litert",
                        runtimeVersion = "2.1.5",
                        minimumAndroidApi = 26,
                        abi = ContractAbi.Arm64V8a,
                        backend = ContractBackend.Cpu,
                        profileId = MdxRuntimeProfiles.CPU_DEFAULT_FP32,
                        precision = ContractRuntimePrecision.Fp32,
                        status = ContractRuntimeQualificationStatus.KnownGood,
                        evidence = "test",
                    ),
                )
            } else {
                emptyList()
            },
        )
    }

    private fun contract(payload: ByteArray) = SourceSeparationModelContract(
        contractSchemaVersion = 2,
        contractId = "official_model@2",
        modelId = "official_model",
        displayName = "Official Model",
        artifact = ContractArtifact(
            fileName = "official-model.tflite",
            byteSize = payload.size.toLong(),
            sha256 = sha256(payload),
            format = ContractArtifactFormat.TfliteFlatbuffer,
        ),
        source = ContractSource(
            canonicalSourceId = "source:official.onnx",
            fileName = "official.onnx",
            url = "https://example.com/official.onnx",
            byteSize = payload.size.toLong(),
            sha256 = "b".repeat(64),
            attribution = listOf("Test"),
        ),
        conversion = ContractConversion(
            repository = "https://example.com/converter",
            revision = "a".repeat(40),
            pipelineVersion = 1,
            toolVersions = mapOf("test" to "1"),
        ),
        tensorContract = TensorContract(
            input = tensor(),
            output = tensor(),
            batchSize = 1,
            complexChannelCount = 4,
        ),
        dsp = dsp(),
        stemContract = stems(),
        pipelineCompatibility = pipeline(),
    )

    private fun customProfile(
        payload: ByteArray,
        profileId: String = "custom-profile",
        modelId: String = "custom_model",
        fileName: String = "custom.tflite",
    ) = SourceSeparationCustomModelProfile(
        profileSchemaVersion = 1,
        profileId = profileId,
        modelId = modelId,
        displayName = modelId.replace('_', ' '),
        artifact = ContractArtifact(
            fileName = fileName,
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
        dsp = dsp(),
        stemContract = stems(),
        pipelineCompatibility = pipeline(),
        qualityUnverified = true,
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
        val store: InMemoryActiveModelStore,
        val repository: SourceSeparationPresetRepository,
    ) : AutoCloseable {
        override fun close() {
            root.deleteRecursively()
        }
    }

    private class BarrierInputStream(
        payload: ByteArray,
        private val entered: CountDownLatch,
        private val release: CountDownLatch,
    ) : InputStream() {
        private val delegate = ByteArrayInputStream(payload)
        private var firstRead = true

        override fun read(): Int = delegate.read()

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (firstRead) {
                firstRead = false
                entered.countDown()
                check(release.await(2, TimeUnit.SECONDS)) {
                    "Model copy did not run concurrently."
                }
            }
            return delegate.read(buffer, offset, length)
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
