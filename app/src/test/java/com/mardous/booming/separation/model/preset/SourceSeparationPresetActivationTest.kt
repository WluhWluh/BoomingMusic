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
    fun `default model is selectable with experimental confirmation`() {
        val eligibility = resolve(
            modelId = "uvr_mdxnet_3_9662",
            scope = SourceSeparationPresetSelectionScope.User,
        )

        assertTrue(eligibility.allowed)
        assertTrue(eligibility.requiresExperimentalConfirmation)
    }

    @Test
    fun `default model can be selected internally on known good CPU`() {
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
    fun `HQ4 keeps reviewed qualification while generic model uses experimental CPU admission`() {
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
        assertTrue(generic.allowed)
        assertFalse(generic.requiresExperimentalConfirmation)
        assertEquals(null, generic.cpuQualification)
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

    @Test
    fun `unqualified reviewed models support product ABIs except pure x86`() {
        for (abi in listOf(
            MdxRuntimeAbi.Arm64V8a,
            MdxRuntimeAbi.ArmeabiV7a,
            MdxRuntimeAbi.X86_64,
        )) {
            val eligibility = SourceSeparationPresetActivationResolver.resolve(
                catalog = catalog,
                modelId = "kuielab_a_bass",
                platform = MdxRuntimePlatform(androidApi = 35, runtimeAbi = abi),
                scope = SourceSeparationPresetSelectionScope.User,
            )
            assertTrue(eligibility.allowed)
            assertTrue(eligibility.requiresExperimentalConfirmation)
            assertEquals(null, eligibility.cpuQualification)
        }

        val x86 = SourceSeparationPresetActivationResolver.resolve(
            catalog = catalog,
            modelId = "kuielab_a_bass",
            platform = MdxRuntimePlatform(androidApi = 35, runtimeAbi = MdxRuntimeAbi.X86),
            scope = SourceSeparationPresetSelectionScope.User,
        )
        assertFalse(x86.allowed)
        assertEquals(
            SourceSeparationPresetSelectionBlockReason.MissingKnownGoodCpuProfile,
            x86.blockReason,
        )
    }

    @Test
    fun `explicit CPU rejection is not replaced by unqualified admission`() {
        val eligibility = SourceSeparationPresetActivationResolver.resolve(
            catalog = catalog,
            modelId = "uvr_mdxnet_inst_hq_4",
            platform = MdxRuntimePlatform(androidApi = 35, runtimeAbi = MdxRuntimeAbi.X86_64),
            scope = SourceSeparationPresetSelectionScope.User,
        )

        assertFalse(eligibility.allowed)
        assertEquals(
            SourceSeparationPresetSelectionBlockReason.MissingKnownGoodCpuProfile,
            eligibility.blockReason,
        )
        assertEquals(
            ContractRuntimeQualificationStatus.Rejected,
            eligibility.cpuQualification?.status,
        )
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
