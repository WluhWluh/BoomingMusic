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
    val endpointStemIds = toMdxBlendEndpointStemIds()
    return SourceSeparationMdxStemLabels(
        vocalsCanonicalLabel = requireNotNull(stems[endpointStemIds[0]]).canonicalLabel,
        instrumentalCanonicalLabel =
            requireNotNull(stems[endpointStemIds[1]]).canonicalLabel,
    )
}

/** Left/top then right/bottom endpoint IDs for the MDX blend control. */
fun StemContract.toMdxBlendEndpointStemIds(): List<StemId> {
    val orderedStemIds = toStemSet().stems.map(StemDescriptor::stemId)
    val physicalStemIds = orderedStemIds.toMdxPhysicalStemIds()
    return listOf(physicalStemIds.vocals, physicalStemIds.instrumental)
}
