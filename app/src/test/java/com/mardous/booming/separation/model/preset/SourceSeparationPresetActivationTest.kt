package com.mardous.booming.separation.model.preset

import com.mardous.booming.separation.model.MdxRuntimeAbi
import com.mardous.booming.separation.model.MdxRuntimePlatform
import com.mardous.booming.separation.model.contract.ContractAbi
import com.mardous.booming.separation.model.contract.ContractRuntimeQualificationStatus
import com.mardous.booming.separation.model.contract.SourceSeparationModelCatalog
import com.mardous.booming.separation.model.contract.SourceSeparationModelMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class SourceSeparationPresetActivationTest {
    @Test
    fun `recommended candidate remains blocked for user selection before Phase 7 promotion`() {
        val eligibility = resolve(
            modelId = "uvr_mdxnet_3_9662",
            scope = SourceSeparationPresetSelectionScope.User,
        )

        assertFalse(eligibility.allowed)
        assertEquals(
            SourceSeparationPresetSelectionBlockReason.CandidatePromotionPending,
            eligibility.blockReason,
        )
    }

    @Test
    fun `recommended candidate can be selected only by internal validation on known good CPU`() {
        val eligibility = resolve(
            modelId = "uvr_mdxnet_3_9662",
            scope = SourceSeparationPresetSelectionScope.InternalValidation,
        )

        assertTrue(eligibility.allowed)
        assertFalse(eligibility.requiresExperimentalConfirmation)
        assertEquals("cpu-default-fp32-v1", eligibility.cpuQualification?.profileId)
    }

    @Test
    fun `karaoke is selectable only with experimental confirmation`() {
        val userEligibility = resolve(
            modelId = "uvr_mdxnet_kara",
            scope = SourceSeparationPresetSelectionScope.User,
        )
        val internalEligibility = resolve(
            modelId = "uvr_mdxnet_kara",
            scope = SourceSeparationPresetSelectionScope.InternalValidation,
        )

        assertTrue(userEligibility.allowed)
        assertTrue(userEligibility.requiresExperimentalConfirmation)
        assertTrue(internalEligibility.allowed)
    }

    @Test
    fun `HQ4 uses reviewed candidate fallback while generic models remain blocked`() {
        val hq4 = resolve(
            modelId = "uvr_mdxnet_inst_hq_4",
            scope = SourceSeparationPresetSelectionScope.InternalValidation,
        )
        val generic = resolve(
            modelId = "kuielab_a_bass",
            scope = SourceSeparationPresetSelectionScope.InternalValidation,
        )

        assertTrue(hq4.allowed)
        assertEquals(
            ContractRuntimeQualificationStatus.Candidate,
            hq4.cpuQualification?.status,
        )
        assertFalse(generic.allowed)
        assertEquals(SourceSeparationPresetSelectionBlockReason.DownloadOnly, generic.blockReason)
    }

    @Test
    fun `selection requires a known good CPU profile for the actual process ABI`() {
        val eligibility = SourceSeparationPresetActivationResolver.resolve(
            catalog = catalog,
            modelId = "uvr_mdxnet_3_9662",
            platform = MdxRuntimePlatform(androidApi = 35, runtimeAbi = MdxRuntimeAbi.X86_64),
            scope = SourceSeparationPresetSelectionScope.InternalValidation,
        )

        assertTrue(eligibility.allowed)
        assertEquals(ContractAbi.X86_64, eligibility.cpuQualification?.abi)
    }

    private fun resolve(
        modelId: String,
        scope: SourceSeparationPresetSelectionScope,
    ) = SourceSeparationPresetActivationResolver.resolve(
        catalog = catalog,
        modelId = modelId,
        platform = MdxRuntimePlatform(androidApi = 35, runtimeAbi = MdxRuntimeAbi.Arm64V8a),
        scope = scope,
    )

    companion object {
        private lateinit var catalog: SourceSeparationModelCatalog

        @JvmStatic
        @BeforeClass
        fun loadCatalog() {
            val bytes = requireNotNull(
                SourceSeparationPresetActivationTest::class.java.classLoader
                    ?.getResourceAsStream(SourceSeparationModelMetadata.CATALOG_ASSET_PATH)
            ).use { it.readBytes() }
            catalog = SourceSeparationModelMetadata.decodeBundledCatalog(bytes)
        }
    }
}
