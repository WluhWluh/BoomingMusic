package com.mardous.booming.separation.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs

class MdxStemOutputMapperTest {
    @Test
    fun `output compensation is applied once before residual reconstruction`() {
        val mixture = arrayOf(
            floatArrayOf(0.8f, -0.3f, 0.1f),
            floatArrayOf(-0.4f, 0.6f, 0.2f),
        )
        val rawOutput = arrayOf(
            floatArrayOf(0.2f, -0.1f, 0.05f),
            floatArrayOf(-0.2f, 0.3f, 0.1f),
        )

        val mapped = mapMdxStemWaveforms(
            mixture = mixture,
            rawModelOutput = rawOutput,
            modelOutputScale = 1.035f,
            modelOutputStem = MdxStem.VOCALS,
        )

        assertArrayEquals(
            floatArrayOf(0.207f, -0.1035f, 0.05175f),
            mapped.vocals[0],
            1e-7f,
        )
        for (channel in mixture.indices) {
            for (frame in mixture[channel].indices) {
                assertEquals(
                    mixture[channel][frame],
                    mapped.vocals[channel][frame] + mapped.instrumental[channel][frame],
                    1e-5f,
                )
            }
        }
    }

    @Test
    fun `instrumental model output maps residual to vocals`() {
        val mixture = arrayOf(floatArrayOf(0.8f), floatArrayOf(-0.4f))
        val rawOutput = arrayOf(floatArrayOf(0.2f), floatArrayOf(-0.1f))

        val mapped = mapMdxStemWaveforms(
            mixture = mixture,
            rawModelOutput = rawOutput,
            modelOutputScale = 1.019f,
            modelOutputStem = MdxStem.INSTRUMENTAL,
        )

        assertEquals(0.2038f, mapped.instrumental[0][0], 1e-7f)
        assertEquals(0.5962f, mapped.vocals[0][0], 1e-7f)
        assertEquals(
            0f,
            abs(mixture[0][0] - mapped.vocals[0][0] - mapped.instrumental[0][0]),
            1e-5f,
        )
    }
}
