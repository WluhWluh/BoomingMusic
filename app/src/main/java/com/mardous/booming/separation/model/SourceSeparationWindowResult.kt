package com.mardous.booming.separation.model

import com.mardous.booming.separation.model.contract.StemId

internal data class SourceSeparationStemChunk(
    val stemId: StemId,
    val order: Int,
    val pcm16: ByteArray,
) {
    init {
        require(order >= 0) { "Stem chunk order is invalid." }
    }
}

internal data class SourceSeparationWindowResult(
    val frameCount: Int,
    val channelCount: Int,
    val stems: List<SourceSeparationStemChunk>,
) {
    init {
        require(frameCount > 0 && channelCount > 0) {
            "Separation window audio geometry is invalid."
        }
        require(stems.isNotEmpty()) { "Separation window stem set is empty." }
        require(stems.map(SourceSeparationStemChunk::stemId).distinct().size == stems.size) {
            "Separation window stem IDs are not unique."
        }
        require(stems.map(SourceSeparationStemChunk::order) == stems.indices.toList()) {
            "Separation window stem order is not contiguous."
        }
        val expectedByteCount = Math.multiplyExact(
            Math.multiplyExact(frameCount, channelCount),
            Short.SIZE_BYTES,
        )
        require(stems.all { it.pcm16.size == expectedByteCount }) {
            "Separation window stem PCM geometry is inconsistent."
        }
    }
}
