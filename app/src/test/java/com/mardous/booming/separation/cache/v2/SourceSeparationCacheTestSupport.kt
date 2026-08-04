package com.mardous.booming.separation.cache.v2

import com.mardous.booming.separation.model.contract.ContractStemSemantic
import com.mardous.booming.separation.model.contract.toSemanticId
import com.mardous.booming.separation.model.contract.toStemSet

internal fun SourceSeparationCacheContractSnapshot.renderedStemFor(
    semantic: ContractStemSemantic,
    path: String,
    integrity: SourceSeparationCacheFileIntegrity?,
    frameCount: Int = 88_200,
): SourceSeparationCacheRenderedStem {
    val descriptor = stemContract.toStemSet().stems.single {
        it.semanticId == semantic.toSemanticId()
    }
    return SourceSeparationCacheRenderedStem(
        stemId = descriptor.stemId,
        semanticId = descriptor.semanticId,
        canonicalLabel = descriptor.canonicalLabel,
        order = descriptor.order,
        production = descriptor.production,
        wavPath = path,
        channelCount = 2,
        sampleRate = 44_100,
        frameCount = frameCount,
        wavIntegrity = integrity,
    )
}
