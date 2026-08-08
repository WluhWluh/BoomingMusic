package com.mardous.booming.separation.model.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSeparationModelPresentationCatalogTest {
    @Test
    fun `reviewed metadata exactly covers the 33 published candidates`() {
        SourceSeparationModelPresentationCatalog.requireExactCoverage(PUBLISHED_MODEL_IDS)

        assertEquals(33, SourceSeparationModelPresentationCatalog.entries.size)
        assertEquals(
            setOf(SourceSeparationModelFamily.MdxNet, SourceSeparationModelFamily.HtDemucs),
            SourceSeparationModelPresentationCatalog.entries.map { it.family }.toSet(),
        )
        assertEquals(
            SourceSeparationModelCategory.entries.toSet(),
            SourceSeparationModelPresentationCatalog.groups().map { it.category }.toSet(),
        )
    }

    @Test
    fun `representatives do not change the stable Quick Setup model`() {
        val representatives = SourceSeparationModelPresentationCatalog.entries
            .filter { it.representative != null }
            .associateBy { it.representative }

        assertEquals(
            "uvr_mdxnet_3_9662",
            representatives.getValue(SourceSeparationModelRepresentative.StableQuickSetup).modelId,
        )
        val multistem = representatives.getValue(
            SourceSeparationModelRepresentative.ExperimentalMultiStem,
        )
        assertEquals(
            "htdemucs_6s_core_canonical_7p8s_fp32_v1_0_0",
            multistem.modelId,
        )
        assertEquals(SourceSeparationModelPurpose.GeneralSixStem, multistem.purpose)
    }

    @Test
    fun `multi-stem options retain distinct reviewed purposes`() {
        assertEquals(
            SourceSeparationModelPurpose.GeneralFourStem,
            SourceSeparationModelPresentationCatalog.require(
                "htdemucs_4s_core_canonical_7p8s_fp32_v1_0_0",
            ).purpose,
        )
        assertEquals(
            SourceSeparationModelPurpose.GuitarFocusedSixStem,
            SourceSeparationModelPresentationCatalog.require(
                "htdemucs_6s_guitar_ft_core_canonical_7p8s_fp32_v1_0_0",
            ).purpose,
        )
    }

    @Test
    fun `unknown model names are not classified by filename inference`() {
        assertNull(SourceSeparationModelPresentationCatalog.find("new_htdemucs_6s_model"))
        assertThrows(IllegalArgumentException::class.java) {
            SourceSeparationModelPresentationCatalog.requireExactCoverage(
                PUBLISHED_MODEL_IDS + "new_htdemucs_6s_model",
            )
        }
    }

    @Test
    fun `every target and cleanup candidate has an explicit purpose`() {
        val grouped = SourceSeparationModelPresentationCatalog.groups()
            .associateBy(SourceSeparationModelPresentationGroup::category)

        assertTrue(grouped.getValue(SourceSeparationModelCategory.TargetStem).entries.isNotEmpty())
        assertEquals(
            setOf(
                SourceSeparationModelPurpose.ReverbRemoval,
                SourceSeparationModelPurpose.CrowdRemoval,
            ),
            grouped.getValue(SourceSeparationModelCategory.Cleanup).entries
                .map { it.purpose }
                .toSet(),
        )
    }

    private companion object {
        val PUBLISHED_MODEL_IDS = setOf(
            "kim_inst",
            "kim_vocal_1",
            "kim_vocal_2",
            "kuielab_a_bass",
            "kuielab_a_drums",
            "kuielab_a_other",
            "kuielab_a_vocals",
            "kuielab_b_bass",
            "kuielab_b_drums",
            "kuielab_b_other",
            "kuielab_b_vocals",
            "reverb_hq_by_foxjoy",
            "uvr_mdxnet_inst_1",
            "uvr_mdxnet_inst_2",
            "uvr_mdxnet_inst_3",
            "uvr_mdxnet_inst_hq_1",
            "uvr_mdxnet_inst_hq_2",
            "uvr_mdxnet_inst_hq_3",
            "uvr_mdxnet_inst_hq_4",
            "uvr_mdxnet_inst_hq_5",
            "uvr_mdxnet_inst_main",
            "uvr_mdxnet_voc_ft",
            "uvr_mdxnet_crowd_hq_1",
            "uvr_mdxnet_1_9703",
            "uvr_mdxnet_2_9682",
            "uvr_mdxnet_3_9662",
            "uvr_mdxnet_9482",
            "uvr_mdxnet_kara",
            "uvr_mdxnet_kara_2",
            "uvr_mdxnet_main",
            "htdemucs_4s_core_canonical_7p8s_fp32_v1_0_0",
            "htdemucs_6s_guitar_ft_core_canonical_7p8s_fp32_v1_0_0",
            "htdemucs_6s_core_canonical_7p8s_fp32_v1_0_0",
        )
    }
}
