package com.mardous.booming.separation.model.contract

/**
 * Reviewed presentation metadata kept outside executable model contracts.
 * Model semantics and cache identity must never depend on these records.
 */
data class SourceSeparationModelPresentation(
    val modelId: String,
    val family: SourceSeparationModelFamily,
    val purpose: SourceSeparationModelPurpose,
    val category: SourceSeparationModelCategory,
    val representative: SourceSeparationModelRepresentative? = null,
)

enum class SourceSeparationModelFamily {
    MdxNet,
    HtDemucs,
}

enum class SourceSeparationModelPurpose {
    GeneralVocalsInstrumental,
    VocalIsolation,
    InstrumentalIsolation,
    Karaoke,
    BassIsolation,
    DrumIsolation,
    OtherIsolation,
    ReverbRemoval,
    CrowdRemoval,
    GeneralFourStem,
    GeneralSixStem,
    GuitarFocusedSixStem,
}

enum class SourceSeparationModelCategory {
    GeneralTwoStem,
    VocalAndInstrumental,
    Karaoke,
    TargetStem,
    Cleanup,
    MultiStem,
}

enum class SourceSeparationModelRepresentative {
    StableQuickSetup,
    ExperimentalMultiStem,
}

data class SourceSeparationModelPresentationGroup(
    val category: SourceSeparationModelCategory,
    val entries: List<SourceSeparationModelPresentation>,
)

object SourceSeparationModelPresentationCatalog {
    val entries: List<SourceSeparationModelPresentation> = buildList {
        mdx(
            purpose = SourceSeparationModelPurpose.InstrumentalIsolation,
            category = SourceSeparationModelCategory.VocalAndInstrumental,
            "kim_inst",
            "uvr_mdxnet_inst_1",
            "uvr_mdxnet_inst_2",
            "uvr_mdxnet_inst_3",
            "uvr_mdxnet_inst_hq_1",
            "uvr_mdxnet_inst_hq_2",
            "uvr_mdxnet_inst_hq_3",
            "uvr_mdxnet_inst_hq_4",
            "uvr_mdxnet_inst_hq_5",
            "uvr_mdxnet_inst_main",
        )
        mdx(
            purpose = SourceSeparationModelPurpose.VocalIsolation,
            category = SourceSeparationModelCategory.VocalAndInstrumental,
            "kim_vocal_1",
            "kim_vocal_2",
            "uvr_mdxnet_voc_ft",
        )
        mdxTarget(SourceSeparationModelPurpose.BassIsolation, "kuielab_a_bass", "kuielab_b_bass")
        mdxTarget(SourceSeparationModelPurpose.DrumIsolation, "kuielab_a_drums", "kuielab_b_drums")
        mdxTarget(SourceSeparationModelPurpose.OtherIsolation, "kuielab_a_other", "kuielab_b_other")
        mdxTarget(SourceSeparationModelPurpose.VocalIsolation, "kuielab_a_vocals", "kuielab_b_vocals")
        mdx(
            purpose = SourceSeparationModelPurpose.ReverbRemoval,
            category = SourceSeparationModelCategory.Cleanup,
            "reverb_hq_by_foxjoy",
        )
        mdx(
            purpose = SourceSeparationModelPurpose.CrowdRemoval,
            category = SourceSeparationModelCategory.Cleanup,
            "uvr_mdxnet_crowd_hq_1",
        )
        mdx(
            purpose = SourceSeparationModelPurpose.GeneralVocalsInstrumental,
            category = SourceSeparationModelCategory.GeneralTwoStem,
            "uvr_mdxnet_1_9703",
            "uvr_mdxnet_2_9682",
            "uvr_mdxnet_9482",
            "uvr_mdxnet_main",
        )
        add(
            presentation(
                modelId = "uvr_mdxnet_3_9662",
                family = SourceSeparationModelFamily.MdxNet,
                purpose = SourceSeparationModelPurpose.GeneralVocalsInstrumental,
                category = SourceSeparationModelCategory.GeneralTwoStem,
                representative = SourceSeparationModelRepresentative.StableQuickSetup,
            ),
        )
        mdx(
            purpose = SourceSeparationModelPurpose.Karaoke,
            category = SourceSeparationModelCategory.Karaoke,
            "uvr_mdxnet_kara",
            "uvr_mdxnet_kara_2",
        )
        add(
            presentation(
                modelId = OFFICIAL_FOUR_STEM_MODEL_ID,
                family = SourceSeparationModelFamily.HtDemucs,
                purpose = SourceSeparationModelPurpose.GeneralFourStem,
                category = SourceSeparationModelCategory.MultiStem,
            ),
        )
        add(
            presentation(
                modelId = GUITAR_FT_MODEL_ID,
                family = SourceSeparationModelFamily.HtDemucs,
                purpose = SourceSeparationModelPurpose.GuitarFocusedSixStem,
                category = SourceSeparationModelCategory.MultiStem,
            ),
        )
        add(
            presentation(
                modelId = OFFICIAL_SIX_STEM_MODEL_ID,
                family = SourceSeparationModelFamily.HtDemucs,
                purpose = SourceSeparationModelPurpose.GeneralSixStem,
                category = SourceSeparationModelCategory.MultiStem,
                representative = SourceSeparationModelRepresentative.ExperimentalMultiStem,
            ),
        )
    }.also(::validate)

    private val byModelId = entries.associateBy(SourceSeparationModelPresentation::modelId)

    fun find(modelId: String): SourceSeparationModelPresentation? = byModelId[modelId]

    fun require(modelId: String): SourceSeparationModelPresentation = requireNotNull(find(modelId)) {
        "No reviewed presentation metadata for model: $modelId"
    }

    fun groups(): List<SourceSeparationModelPresentationGroup> =
        SourceSeparationModelCategory.entries.mapNotNull { category ->
            entries.filter { it.category == category }
                .takeIf(List<SourceSeparationModelPresentation>::isNotEmpty)
                ?.let { categoryEntries ->
                    SourceSeparationModelPresentationGroup(category, categoryEntries)
                }
        }

    fun requireExactCoverage(modelIds: Collection<String>) {
        val expected = modelIds.toSet()
        val actual = byModelId.keys
        require(expected == actual) {
            "Presentation metadata coverage differs: missing=${expected - actual}, " +
                "unexpected=${actual - expected}"
        }
    }

    private fun MutableList<SourceSeparationModelPresentation>.mdx(
        purpose: SourceSeparationModelPurpose,
        category: SourceSeparationModelCategory,
        vararg modelIds: String,
    ) {
        modelIds.forEach { modelId ->
            add(
                presentation(
                    modelId = modelId,
                    family = SourceSeparationModelFamily.MdxNet,
                    purpose = purpose,
                    category = category,
                ),
            )
        }
    }

    private fun MutableList<SourceSeparationModelPresentation>.mdxTarget(
        purpose: SourceSeparationModelPurpose,
        vararg modelIds: String,
    ) = mdx(purpose, SourceSeparationModelCategory.TargetStem, *modelIds)

    private fun presentation(
        modelId: String,
        family: SourceSeparationModelFamily,
        purpose: SourceSeparationModelPurpose,
        category: SourceSeparationModelCategory,
        representative: SourceSeparationModelRepresentative? = null,
    ) = SourceSeparationModelPresentation(
        modelId = modelId,
        family = family,
        purpose = purpose,
        category = category,
        representative = representative,
    )

    private fun validate(entries: List<SourceSeparationModelPresentation>) {
        require(entries.isNotEmpty()) { "Presentation metadata is empty" }
        require(entries.size == entries.map { it.modelId }.toSet().size) {
            "Presentation metadata contains duplicate model IDs"
        }
        require(entries.all { it.modelId.isNotBlank() }) {
            "Presentation metadata contains an empty model ID"
        }
        SourceSeparationModelRepresentative.entries.forEach { representative ->
            require(entries.count { it.representative == representative } == 1) {
                "Presentation metadata must define exactly one $representative model"
            }
        }
    }

    private const val OFFICIAL_FOUR_STEM_MODEL_ID =
        "htdemucs_4s_core_canonical_7p8s_fp32_v1_0_0"
    private const val OFFICIAL_SIX_STEM_MODEL_ID =
        "htdemucs_6s_core_canonical_7p8s_fp32_v1_0_0"
    private const val GUITAR_FT_MODEL_ID =
        "htdemucs_6s_guitar_ft_core_canonical_7p8s_fp32_v1_0_0"
}
