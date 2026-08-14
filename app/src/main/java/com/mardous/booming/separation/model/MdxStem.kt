package com.mardous.booming.separation.model

import com.mardous.booming.separation.model.contract.StemId

/** Internal two-file slots retained by the MDX renderer. */
enum class MdxStem {
    VOCALS,
    INSTRUMENTAL,
}

data class MdxPhysicalStemIds(
    val vocals: StemId,
    val instrumental: StemId,
) {
    init {
        require(vocals != instrumental) { "MDX physical stem IDs must be unique." }
    }

    fun stemIdFor(stem: MdxStem): StemId = when (stem) {
        MdxStem.VOCALS -> vocals
        MdxStem.INSTRUMENTAL -> instrumental
    }

    fun physicalStemFor(stemId: StemId): MdxStem = when (stemId) {
        vocals -> MdxStem.VOCALS
        instrumental -> MdxStem.INSTRUMENTAL
        else -> throw IllegalArgumentException("Unknown MDX stem ID: $stemId")
    }
}

/**
 * MDX contracts order the direct model output before its residual. A vocals target uses the
 * vocals output slot; every other target uses the instrumental slot.
 */
fun List<StemId>.toMdxPhysicalStemIds(): MdxPhysicalStemIds {
    require(size == 2 && distinct().size == size) {
        "MDX execution requires exactly two unique ordered stem IDs."
    }
    return if (first() == StemId.Vocals) {
        MdxPhysicalStemIds(vocals = first(), instrumental = last())
    } else {
        MdxPhysicalStemIds(vocals = last(), instrumental = first())
    }
}
