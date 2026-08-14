package com.mardous.booming.separation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSeparationStemGainPolicyTest {
    @Test
    fun `neutral gains bypass separated-output demand`() {
        assertFalse(SourceSeparationStemGainPolicy.requiresSeparatedOutput(List(6) { 1f }))
        assertEquals(
            SourceSeparationBlendDemand.CENTER_BLEND,
            SourceSeparationStemGainPolicy.demandBlend(List(6) { 1f }),
        )
    }

    @Test
    fun `any attenuated stem requires separated output`() {
        val gains = listOf(1f, 1f, 0.35f, 1f)

        assertTrue(SourceSeparationStemGainPolicy.requiresSeparatedOutput(gains))
        assertEquals(0f, SourceSeparationStemGainPolicy.demandBlend(gains))
    }

    @Test
    fun `inverting gains swaps mute and full volume`() {
        assertEquals(1f, SourceSeparationStemGainPolicy.invert(0f))
        assertEquals(0f, SourceSeparationStemGainPolicy.invert(1f))
        assertEquals(0.25f, SourceSeparationStemGainPolicy.invert(0.75f))
    }

    @Test
    fun `ordered gain map requires the exact stem identity set`() {
        val stemIds = listOf("drums", "bass", "other", "vocals")
        val stored = mapOf(
            "vocals" to 0.8f,
            "other" to 0.7f,
            "bass" to 0.6f,
            "drums" to 0.5f,
        )

        assertEquals(
            listOf(0.5f, 0.6f, 0.7f, 0.8f),
            SourceSeparationStemGainPolicy.orderedGains(stemIds, stored),
        )
        assertNull(
            SourceSeparationStemGainPolicy.orderedGains(
                stemIds,
                stored - "other",
            ),
        )
    }

    @Test
    fun `MDX blend policy round trips endpoint gains in contract order`() {
        val stemIds = listOf("remaining_audio", "bass")
        val endpoints = listOf("bass", "remaining_audio")

        listOf(0f, 0.2f, 0.5f, 0.7f, 1f).forEach { blend ->
            val gains = SourceSeparationMdxMixPolicy.orderedGains(
                stemIds = stemIds,
                endpointStemIds = endpoints,
                blend = blend,
            )
            assertEquals(
                blend,
                SourceSeparationMdxMixPolicy.blendFromOrderedGains(
                    stemIds = stemIds,
                    endpointStemIds = endpoints,
                    gains = gains,
                ),
            )
        }
    }
}
