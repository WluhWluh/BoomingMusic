package com.mardous.booming.separation.model.contract

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
    val stems = listOf(modelOutput, residual)
    return SourceSeparationMdxStemLabels(
        vocalsCanonicalLabel = stems.single {
            it.semantic == ContractStemSemantic.Vocals
        }.canonicalLabel,
        instrumentalCanonicalLabel = stems.single {
            it.semantic == ContractStemSemantic.Instrumental
        }.canonicalLabel,
    )
}
