package com.mardous.booming.separation.model.contract

import com.mardous.booming.separation.model.toMdxPhysicalStemIds

data class SourceSeparationMdxStemLabels(
    val vocalsCanonicalLabel: String,
    val instrumentalCanonicalLabel: String,
) {
    companion object {
        val Default = SourceSeparationMdxStemLabels(
            vocalsCanonicalLabel = "Vocals",
            instrumentalCanonicalLabel = "Instrumental",
        )
    }
}

fun StemContract.toMdxStemLabels(): SourceSeparationMdxStemLabels {
    val stems = toStemSet().stems.associateBy { it.stemId }
    val physicalStemIds = stems.keys.toList().toMdxPhysicalStemIds()
    return SourceSeparationMdxStemLabels(
        vocalsCanonicalLabel = requireNotNull(stems[physicalStemIds.vocals]).canonicalLabel,
        instrumentalCanonicalLabel =
            requireNotNull(stems[physicalStemIds.instrumental]).canonicalLabel,
    )
}
