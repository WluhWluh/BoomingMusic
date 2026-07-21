package com.mardous.booming.separation.model.contract

import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class SourceSeparationModelContractTest {

    @Test
    fun `bundled catalog parses and validates as v2`() {
        val validated = SourceSeparationModelContractValidator.validateCatalog(catalog)

        assertEquals(2, validated.catalogSchemaVersion)
        assertEquals(2, validated.contractSchemaVersion)
        assertEquals(48, validated.sources.size)
        assertEquals(30, validated.artifacts.size)
        assertEquals(30, validated.entries.size)
        assertEquals(3, validated.contracts.size)
        assertEquals(18, validated.sources.count { it.aliasOfSourceId != null })
    }

    @Test
    fun `only 9662 is the recommended default candidate`() {
        val recommended = catalog.entries.filter {
            it.supportLevel == CatalogSupportLevel.Recommended
        }

        assertEquals(1, recommended.size)
        assertEquals("uvr_mdxnet_3_9662", recommended.single().modelId)
        assertEquals(CatalogReleaseMaturity.Candidate, recommended.single().releaseMaturity)
        recommended.forEach { entry ->
            val contract = SourceSeparationModelContractValidator.resolveActivationContract(
                catalog,
                entry.modelId,
            )
            assertEquals(entry.modelId, contract.modelId)
            assertEquals(64, contract.artifact.sha256.length)
            assertTrue(contract.artifact.byteSize > 0L)
        }
    }

    @Test
    fun `contract freezes reviewed DSP and stem semantics`() {
        val model9662 = contract("uvr_mdxnet_3_9662")
        val karaoke = contract("uvr_mdxnet_kara")
        val hq4 = contract("uvr_mdxnet_inst_hq_4")

        assertEquals(6_144, model9662.dsp.nFft)
        assertEquals(2_048, model9662.dsp.dimF)
        assertEquals(1.035, model9662.dsp.modelOutputScale, 0.0)
        assertEquals(ContractStemSemantic.Vocals, model9662.stemContract.modelOutput.semantic)
        assertEquals(ContractStemSemantic.Vocals, karaoke.stemContract.modelOutput.semantic)
        assertEquals(5_120, hq4.dsp.nFft)
        assertEquals(2_560, hq4.dsp.dimF)
        assertEquals(1.019, hq4.dsp.modelOutputScale, 0.0)
        assertEquals(ContractStemSemantic.Instrumental, hq4.stemContract.modelOutput.semantic)
        assertEquals(ContractStemSemantic.Vocals, hq4.stemContract.residual.semantic)
    }

    @Test
    fun `matching filename cannot bind a mismatched sidecar hash`() {
        val contract = contract("uvr_mdxnet_3_9662")
        val artifact = contract.artifact
        val wrongHash = if (artifact.sha256 == "0".repeat(64)) {
            "1".repeat(64)
        } else {
            "0".repeat(64)
        }

        val error = assertThrows(SourceSeparationModelContractException::class.java) {
            SourceSeparationModelContractValidator.validateSidecarBinding(
                model = ModelFileIdentity(artifact.fileName, artifact.byteSize, wrongHash),
                sidecarFileName = "${artifact.fileName}.json",
                contract = contract,
            )
        }
        assertTrue(error.message.orEmpty().contains("SHA-256"))
    }

    @Test
    fun `sidecar must use the exact model filename plus json`() {
        val contract = contract("uvr_mdxnet_kara")
        val artifact = contract.artifact

        assertThrows(SourceSeparationModelContractException::class.java) {
            SourceSeparationModelContractValidator.validateSidecarBinding(
                model = ModelFileIdentity(artifact.fileName, artifact.byteSize, artifact.sha256),
                sidecarFileName = artifact.fileName.removeSuffix(".tflite") + ".json",
                contract = contract,
            )
        }
    }

    @Test
    fun `unreviewed candidate cannot activate`() {
        val entry = catalog.entries.single { it.modelId == "uvr_mdxnet_1_9703" }
        assertEquals(CatalogSupportLevel.DownloadOnly, entry.supportLevel)
        assertEquals(CatalogActivationPolicy.BlockedUntilReviewedContract, entry.activationPolicy)
        assertThrows(SourceSeparationModelContractException::class.java) {
            SourceSeparationModelContractValidator.resolveActivationContract(catalog, entry.modelId)
        }
    }

    @Test
    fun `reviewed support tier and activation policy remain independent`() {
        val karaoke = catalog.entries.single { it.modelId == "uvr_mdxnet_kara" }
        assertEquals(CatalogSupportLevel.Experimental, karaoke.supportLevel)
        assertEquals(
            CatalogActivationPolicy.SelectableExperimentalCpuOnly,
            karaoke.activationPolicy,
        )
        assertNotNull(
            SourceSeparationModelContractValidator.resolveActivationContract(
                catalog,
                karaoke.modelId,
            )
        )

        val hq4 = catalog.entries.single { it.modelId == "uvr_mdxnet_inst_hq_4" }
        assertEquals(CatalogSupportLevel.DownloadOnly, hq4.supportLevel)
        assertEquals(
            CatalogActivationPolicy.DownloadOnlyResourceGated,
            hq4.activationPolicy,
        )
        assertNotNull(
            SourceSeparationModelContractValidator.resolveReviewedContract(
                catalog,
                hq4.modelId,
            )
        )
        assertThrows(SourceSeparationModelContractException::class.java) {
            SourceSeparationModelContractValidator.resolveActivationContract(
                catalog,
                hq4.modelId,
            )
        }
    }

    @Test
    fun `generic target models remain download only`() {
        val genericEntries = catalog.entries.filter {
            it.stemUi == CatalogStemUi.GenericTargetResidualRequired
        }

        assertEquals(8, genericEntries.size)
        assertTrue(genericEntries.all { it.supportLevel == CatalogSupportLevel.DownloadOnly })
        assertTrue(
            genericEntries.all {
                it.activationPolicy == CatalogActivationPolicy.DownloadOnlyGenericStem
            }
        )
        assertTrue(catalog.entries.all { !it.downloadActivatesModel })
    }

    @Test
    fun `unknown import requires an explicit unverified profile`() {
        val known = contract("uvr_mdxnet_3_9662")
        val identity = ModelFileIdentity(
            known.artifact.fileName,
            known.artifact.byteSize,
            known.artifact.sha256,
        )
        assertThrows(SourceSeparationModelContractException::class.java) {
            SourceSeparationModelContractValidator.requireCustomImportProfile(identity, null)
        }

        val profile = SourceSeparationCustomModelProfile(
            profileSchemaVersion = 1,
            profileId = "custom-profile-test",
            modelId = "custom_model",
            displayName = "Custom model",
            artifact = known.artifact,
            tensorContract = known.tensorContract,
            dsp = known.dsp,
            stemContract = known.stemContract,
            pipelineCompatibility = known.pipelineCompatibility,
            qualityUnverified = true,
        )
        assertNotNull(
            SourceSeparationModelContractValidator.requireCustomImportProfile(identity, profile)
        )
        assertThrows(SourceSeparationModelContractException::class.java) {
            SourceSeparationModelContractValidator.validateCustomProfile(
                profile.copy(qualityUnverified = false)
            )
        }
    }

    @Test
    fun `strict decoder rejects undeclared contract fields`() {
        val encoded = SourceSeparationModelMetadata.json.encodeToString(
            contract("uvr_mdxnet_3_9662")
        )
        val withUnknownField = encoded.dropLast(1) + ",\"undeclared\":true}"

        assertThrows(Exception::class.java) {
            SourceSeparationModelMetadata.decodeContract(withUnknownField)
        }
    }

    @Test
    fun `unsupported contract schema is rejected independently of app version`() {
        val unsupported = contract("uvr_mdxnet_3_9662").copy(contractSchemaVersion = 1)

        assertThrows(SourceSeparationModelContractException::class.java) {
            SourceSeparationModelContractValidator.validateContract(unsupported)
        }
    }

    @Test
    fun `bundled catalog hash rejects byte-level drift`() {
        val original = catalogResourceBytes()
        val modified = original + byteArrayOf(' '.code.toByte())

        assertThrows(SourceSeparationModelContractException::class.java) {
            SourceSeparationModelMetadata.decodeBundledCatalog(modified)
        }
    }

    companion object {
        private lateinit var catalog: SourceSeparationModelCatalog

        @JvmStatic
        @BeforeClass
        fun loadCatalog() {
            catalog = SourceSeparationModelMetadata.decodeBundledCatalog(catalogResourceBytes())
        }

        private fun contract(modelId: String): SourceSeparationModelContract =
            catalog.contracts.single { it.modelId == modelId }

        private fun catalogResourceBytes(): ByteArray {
            val stream = requireNotNull(
                SourceSeparationModelContractTest::class.java.classLoader
                    ?.getResourceAsStream(SourceSeparationModelMetadata.CATALOG_ASSET_PATH)
            ) { "Bundled model catalog test resource is missing" }
            return stream.use { input -> input.readBytes() }
        }
    }
}
