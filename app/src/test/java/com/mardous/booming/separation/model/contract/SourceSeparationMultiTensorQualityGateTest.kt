package com.mardous.booming.separation.model.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSeparationMultiTensorQualityGateTest {
    @Test
    fun `strict host gate requires finite tight FP32 parity`() {
        val exact = SourceSeparationMultiTensorQualityGate.compareFloat(
            expected = floatArrayOf(0.25f, -0.5f, 0.75f),
            actual = floatArrayOf(0.25f, -0.5f, 0.75f),
        )
        assertTrue(SourceSeparationMultiTensorQualityGate.passesStrictHost(exact))

        val drift = SourceSeparationMultiTensorQualityGate.compareFloat(
            expected = floatArrayOf(0.25f, -0.5f, 0.75f),
            actual = floatArrayOf(0.25f, -0.5f, 0.75001f),
        )
        assertFalse(SourceSeparationMultiTensorQualityGate.passesStrictHost(drift))
    }

    @Test
    fun `low energy stem uses absolute error instead of an inflated SNR`() {
        val expected = floatArrayOf(1e-7f, -1e-7f, 0f, 1e-7f)
        val actual = floatArrayOf(2e-7f, -2e-7f, 1e-7f, 0f)
        val result = SourceSeparationMultiTensorQualityGate.comparePerStem(
            expected = expected,
            actual = actual,
            stemCount = 1,
        ).single()

        assertTrue(result.lowEnergy)
        assertTrue(result.passes)
        assertTrue(
            result.metrics.signalToNoiseDb <
                SourceSeparationMultiTensorQualityThresholds.FrozenV1.tensorMinimumSnrDb,
        )
    }

    @Test
    fun `phase 6 v2 classifies quiet stems below one millirms as low energy`() {
        val expected = FloatArray(4) { 7.5e-4f }
        val actual = FloatArray(4) { 7.5e-4f + 1e-6f }
        val v1 = SourceSeparationMultiTensorQualityGate.comparePerStem(
            expected = expected,
            actual = actual,
            stemCount = 1,
            thresholds = SourceSeparationMultiTensorQualityThresholds.FrozenV1,
        ).single()
        val v2 = SourceSeparationMultiTensorQualityGate.comparePerStem(
            expected = expected,
            actual = actual,
            stemCount = 1,
            thresholds = SourceSeparationMultiTensorQualityThresholds.FrozenV2,
        ).single()

        assertFalse(v1.lowEnergy)
        assertTrue(v2.lowEnergy)
        assertTrue(v2.passes)
    }

    @Test
    fun `energetic stem requires both SNR and cosine similarity`() {
        val result = SourceSeparationMultiTensorQualityGate.comparePerStem(
            expected = floatArrayOf(1f, 1f, 1f, 1f),
            actual = floatArrayOf(1.0001f, 1.0001f, 1.0001f, 1.0001f),
            stemCount = 1,
        ).single()

        assertFalse(result.lowEnergy)
        assertTrue(result.passes)

        val bad = SourceSeparationMultiTensorQualityGate.comparePerStem(
            expected = floatArrayOf(1f, 1f, 1f, 1f),
            actual = floatArrayOf(-1f, -1f, -1f, -1f),
            stemCount = 1,
        ).single()
        assertFalse(bad.passes)
    }

    @Test
    fun `pcm16 one lsb difference passes while two lsb fails`() {
        val expected = byteArrayOf(0, 0, 1.toByte(), 0, 0, Byte.MIN_VALUE, 0, 0)
        val oneLsb = byteArrayOf(0, 0, 2.toByte(), 0, 0, Byte.MIN_VALUE, 0, 0)
        val twoLsb = byteArrayOf(0, 0, 3.toByte(), 0, 0, Byte.MIN_VALUE, 0, 0)

        assertTrue(
            SourceSeparationMultiTensorQualityGate.comparePcm16(
                expected, oneLsb, expectedFrameCount = 2, channelCount = 2,
            ).passes,
        )
        assertFalse(
            SourceSeparationMultiTensorQualityGate.comparePcm16(
                expected, twoLsb, expectedFrameCount = 2, channelCount = 2,
            ).passes,
        )
        assertEquals(2, SourceSeparationMultiTensorQualityGate.comparePcm16(
            expected, twoLsb, expectedFrameCount = 2, channelCount = 2,
        ).maximumSampleDelta)
    }
}
