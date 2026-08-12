package com.mardous.booming.separation

import kotlin.math.abs

object SourceSeparationStemGainPolicy {
    const val MIN_GAIN = 0f
    const val NEUTRAL_GAIN = 1f
    const val MAX_GAIN = NEUTRAL_GAIN

    fun normalize(gains: List<Float>): List<Float> = gains.map(::normalize)

    fun normalize(gain: Float): Float {
        require(gain.isFinite()) { "Source-separation stem gain must be finite." }
        return gain.coerceIn(MIN_GAIN, MAX_GAIN)
    }

    fun invert(gain: Float): Float = normalize(MAX_GAIN - normalize(gain))

    fun requiresSeparatedOutput(gains: Collection<Float>): Boolean =
        gains.any { gain -> abs(normalize(gain) - NEUTRAL_GAIN) >= GAIN_EPSILON }

    fun demandBlend(gains: Collection<Float>): Float =
        if (requiresSeparatedOutput(gains)) 0f else SourceSeparationBlendDemand.CENTER_BLEND

    fun orderedMap(
        stemIds: List<String>,
        gains: List<Float>,
    ): Map<String, Float> {
        require(stemIds.isNotEmpty()) { "Source-separation stem IDs are empty." }
        require(stemIds.size == gains.size) {
            "Source-separation stem gains do not match the stem IDs."
        }
        require(stemIds.all(String::isNotBlank) && stemIds.distinct().size == stemIds.size) {
            "Source-separation stem IDs must be unique and non-empty."
        }
        val normalized = normalize(gains)
        return buildMap(stemIds.size) {
            stemIds.forEachIndexed { index, stemId -> put(stemId, normalized[index]) }
        }
    }

    fun orderedGains(
        stemIds: List<String>,
        gainsByStemId: Map<String, Float>,
    ): List<Float>? {
        if (stemIds.toSet() != gainsByStemId.keys) return null
        return stemIds.map { stemId -> normalize(requireNotNull(gainsByStemId[stemId])) }
    }

    private const val GAIN_EPSILON = 0.0001f
}
