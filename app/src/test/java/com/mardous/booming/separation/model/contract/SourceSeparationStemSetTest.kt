package com.mardous.booming.separation.model.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class SourceSeparationStemSetTest {

    @Test
    fun `v2 contracts normalize model output before residual`() {
        val model9662 = contract("uvr_mdxnet_3_9662").stemContract.toStemSet()
        val hq4 = contract("uvr_mdxnet_inst_hq_4").stemContract.toStemSet()

        assertEquals(
            listOf("vocals", "instrumental"),
            model9662.stems.map { it.stemId.value },
        )
        assertEquals(
            listOf("Vocals", "Instrumental"),
            model9662.stems.map { it.canonicalLabel },
        )
        assertTrue(model9662.stems[0].production is StemProduction.DirectModelOutput)
        assertEquals(
            StemId("vocals"),
            (model9662.stems[1].production as StemProduction.DerivedResidual).sourceStemId,
        )

        assertEquals(listOf("instrumental", "vocals"), hq4.stems.map { it.stemId.value })
        assertEquals(listOf("Instrumental", "Vocals"), hq4.stems.map { it.canonicalLabel })
        assertTrue(hq4.stems[0].production is StemProduction.DirectModelOutput)
        assertEquals(
            StemId("instrumental"),
            (hq4.stems[1].production as StemProduction.DerivedResidual).sourceStemId,
        )
    }

    @Test
    fun `MDX playback labels follow physical blend endpoints instead of contract order`() {
        for (modelId in listOf("uvr_mdxnet_3_9662", "uvr_mdxnet_kara")) {
            assertEquals(
                SourceSeparationMdxStemLabels("Vocals", "Instrumental"),
                contract(modelId).stemContract.toMdxStemLabels(),
            )
        }
        assertEquals(
            SourceSeparationMdxStemLabels("Vocals", "Instrumental"),
            contract("uvr_mdxnet_inst_hq_4").stemContract.toMdxStemLabels(),
        )
        assertEquals(
            SourceSeparationMdxStemLabels("Remaining Audio", "Bass"),
            contract("kuielab_a_bass").stemContract.toMdxStemLabels(),
        )
        assertEquals(
            SourceSeparationMdxStemLabels("Crowd", "No Crowd"),
            contract("uvr_mdxnet_crowd_hq_1").stemContract.toMdxStemLabels(),
        )
        assertEquals(
            listOf("vocals", "instrumental"),
            contract("uvr_mdxnet_inst_hq_4").stemContract
                .toMdxBlendEndpointStemIds()
                .map { it.value },
        )
        assertEquals(
            listOf("remaining_audio", "bass"),
            contract("kuielab_a_bass").stemContract
                .toMdxBlendEndpointStemIds()
                .map { it.value },
        )
        assertEquals(
            listOf("remaining_audio", "no_crowd"),
            contract("uvr_mdxnet_crowd_hq_1").stemContract
                .toMdxBlendEndpointStemIds()
                .map { it.value },
        )
    }

    @Test
    fun `pipeline native stem set supports the reviewed six-stem order`() {
        val semantics = listOf(
            StemSemanticId.Drums to "Drums",
            StemSemanticId.Bass to "Bass",
            StemSemanticId.Other to "Other",
            StemSemanticId.Vocals to "Vocals",
            StemSemanticId.Guitar to "Guitar",
            StemSemanticId.Piano to "Piano",
        )
        val stems = StemSet(
            semantics.mapIndexed { index, (semantic, label) ->
                StemDescriptor(
                    stemId = StemId(semantic.value),
                    semanticId = semantic,
                    canonicalLabel = label,
                    order = index,
                    production = StemProduction.PipelineNative(index),
                )
            },
        )

        assertEquals(6, stems.stems.size)
        assertEquals(
            listOf("drums", "bass", "other", "vocals", "guitar", "piano"),
            stems.stems.map { it.stemId.value },
        )
    }

    @Test
    fun `unknown semantic IDs round trip independently of labels`() {
        val semantic = StemSemanticId("backing_vocals")
        val descriptor = StemDescriptor(
            stemId = StemId("backing_vocals"),
            semanticId = semantic,
            canonicalLabel = "Backing Vocals",
            order = 0,
            production = StemProduction.PipelineNative(0),
        )

        assertEquals("backing_vocals", descriptor.semanticId.value)
        assertEquals("Backing Vocals", descriptor.canonicalLabel)
    }

    @Test
    fun `stem set rejects duplicate IDs labels and dangling residuals`() {
        val direct = descriptor("vocals", "Vocals", 0)
        assertThrows(IllegalArgumentException::class.java) {
            StemSet(listOf(direct, direct.copy(order = 1, canonicalLabel = "Voice")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            StemSet(listOf(direct, descriptor("instrumental", "VOCALS", 1)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            StemSet(
                listOf(
                    direct,
                    descriptor("instrumental", "Instrumental", 1).copy(
                        production = StemProduction.DerivedResidual(
                            sourceStemId = StemId("missing"),
                            rule = ContractResidualRule.MixtureMinusScaledModelOutput,
                        ),
                    ),
                ),
            )
        }
    }

    private fun descriptor(
        id: String,
        label: String,
        order: Int,
    ) = StemDescriptor(
        stemId = StemId(id),
        semanticId = StemSemanticId(id),
        canonicalLabel = label,
        order = order,
        production = StemProduction.PipelineNative(order),
    )

    companion object {
        private lateinit var catalog: SourceSeparationModelCatalog

        @JvmStatic
        @BeforeClass
        fun loadCatalog() {
            val bytes = requireNotNull(
                SourceSeparationStemSetTest::class.java.classLoader
                    ?.getResourceAsStream(SourceSeparationModelMetadata.CATALOG_ASSET_PATH)
            ).use { it.readBytes() }
            catalog = SourceSeparationModelMetadata.decodeBundledCatalog(bytes)
        }

        private fun contract(modelId: String): SourceSeparationModelContract =
            catalog.contracts.single { it.modelId == modelId }
    }
}
