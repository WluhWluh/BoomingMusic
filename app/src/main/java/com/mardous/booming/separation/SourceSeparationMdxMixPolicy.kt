package com.mardous.booming.separation

import kotlin.math.abs

object SourceSeparationMdxMixPolicy {
    fun endpointGains(blend: Float): List<Float> {
        val normalized = blend.coerceIn(0f, 1f)
        val firstGain = if (normalized <= SourceSeparationBlendDemand.CENTER_BLEND) {
            SourceSeparationStemGainPolicy.NEUTRAL_GAIN
        } else {
            (1f - normalized) / SourceSeparationBlendDemand.CENTER_BLEND
        }
        val secondGain = if (normalized >= SourceSeparationBlendDemand.CENTER_BLEND) {
            SourceSeparationStemGainPolicy.NEUTRAL_GAIN
        } else {
            normalized / SourceSeparationBlendDemand.CENTER_BLEND
        }
        return listOf(firstGain, secondGain)
    }

    fun orderedGains(
        stemIds: List<String>,
        endpointStemIds: List<String>,
        blend: Float,
    ): List<Float> {
        val endpointIndexes = endpointIndexes(stemIds, endpointStemIds)
        val endpointGains = endpointGains(blend)
        return MutableList(stemIds.size) { SourceSeparationStemGainPolicy.NEUTRAL_GAIN }.apply {
            this[endpointIndexes[0]] = endpointGains[0]
            this[endpointIndexes[1]] = endpointGains[1]
        }
    }

    fun blendFromOrderedGains(
        stemIds: List<String>,
        endpointStemIds: List<String>,
        gains: List<Float>,
    ): Float? {
        if (gains.size != stemIds.size) return null
        val endpointIndexes = runCatching {
            endpointIndexes(stemIds, endpointStemIds)
        }.getOrNull() ?: return null
        val normalized = runCatching {
            SourceSeparationStemGainPolicy.normalize(gains)
        }.getOrNull() ?: return null
        if (normalized.indices.any { index ->
                index !in endpointIndexes && !approximatelyNeutral(normalized[index])
            }
        ) {
            return null
        }
        val first = normalized[endpointIndexes[0]]
        val second = normalized[endpointIndexes[1]]
        return when {
            approximatelyNeutral(first) && approximatelyNeutral(second) ->
                SourceSeparationBlendDemand.CENTER_BLEND
            approximatelyNeutral(first) ->
                (second * SourceSeparationBlendDemand.CENTER_BLEND).coerceIn(
                    0f,
                    SourceSeparationBlendDemand.CENTER_BLEND,
                )
            approximatelyNeutral(second) ->
                (1f - first * SourceSeparationBlendDemand.CENTER_BLEND).coerceIn(
                    SourceSeparationBlendDemand.CENTER_BLEND,
                    1f,
                )
            else -> null
        }
    }

    private fun endpointIndexes(
        stemIds: List<String>,
        endpointStemIds: List<String>,
    ): IntArray {
        require(stemIds.isNotEmpty() &&
            stemIds.all(String::isNotBlank) &&
            stemIds.distinct().size == stemIds.size
        ) {
            "MDX mix stem IDs must be unique and non-empty."
        }
        require(endpointStemIds.size == 2 && endpointStemIds.distinct().size == 2) {
            "MDX mix requires exactly two unique endpoint stem IDs."
        }
        return endpointStemIds.map { endpointId ->
            stemIds.indexOf(endpointId).also { index ->
                require(index >= 0) { "MDX mix endpoint $endpointId is not in the stem set." }
            }
        }.toIntArray()
    }

    private fun approximatelyNeutral(gain: Float): Boolean =
        abs(gain - SourceSeparationStemGainPolicy.NEUTRAL_GAIN) < GAIN_EPSILON

    private const val GAIN_EPSILON = 0.0001f
}
