package com.mardous.booming.separation.model.contract

import com.mardous.booming.separation.model.MdxInferenceBackend
import com.mardous.booming.separation.model.MdxModelFormat
import com.mardous.booming.separation.model.MdxRuntimeAbi
import com.mardous.booming.separation.model.MdxRuntimeSupportStatus
import com.mardous.booming.separation.model.MdxStem
import com.mardous.booming.separation.model.MdxTensorLayout
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test

class MdxContractExecutionProfileTest {
    @Test
    fun `reviewed 9662 contract creates a complete LiteRT execution profile`() {
        val contract = catalog.contracts.single { it.modelId == "uvr_mdxnet_3_9662" }

        val profile = contract.toMdxExecutionProfile()

        assertEquals(MdxModelFormat.Tflite, profile.modelFormat)
        assertEquals(MdxTensorLayout.Nhwc, profile.inputTensor.layout)
        assertEquals("input", profile.inputTensor.name)
        assertEquals(listOf(1, 2048, 256, 4), profile.inputTensor.shape)
        assertEquals("output", profile.outputTensor.name)
        assertEquals(6_144, profile.dspConfig.nFft)
        assertEquals(2_048, profile.dspConfig.dimF)
        assertEquals(1.035f, profile.modelOutputScale)
        assertEquals(MdxStem.VOCALS, profile.modelOutputStem)
        assertEquals(contract.artifact.sha256, profile.expectedSha256)
    }

    @Test
    fun `HQ4 profile retains its distinct DSP and 32-bit rejection`() {
        val contract = catalog.contracts.single { it.modelId == "uvr_mdxnet_inst_hq_4" }

        val profile = contract.toMdxExecutionProfile()

        assertEquals(5_120, profile.dspConfig.nFft)
        assertEquals(2_560, profile.dspConfig.dimF)
        assertEquals(listOf(1, 2560, 256, 4), profile.outputTensor.shape)
        assertEquals(MdxStem.INSTRUMENTAL, profile.modelOutputStem)
        for (abi in listOf(MdxRuntimeAbi.ArmeabiV7a, MdxRuntimeAbi.X86)) {
            assertEquals(
                MdxRuntimeSupportStatus.Unsupported,
                profile.runtimeCompatibility.single {
                    it.abi == abi && it.backend == MdxInferenceBackend.LiteRtCpu
                }.status,
            )
        }
    }

    companion object {
        private lateinit var catalog: SourceSeparationModelCatalog

        @JvmStatic
        @BeforeClass
        fun loadCatalog() {
            val bytes = requireNotNull(
                MdxContractExecutionProfileTest::class.java.classLoader
                    ?.getResourceAsStream(SourceSeparationModelMetadata.CATALOG_ASSET_PATH)
            ).use { it.readBytes() }
            catalog = SourceSeparationModelMetadata.decodeBundledCatalog(bytes)
        }
    }
}
