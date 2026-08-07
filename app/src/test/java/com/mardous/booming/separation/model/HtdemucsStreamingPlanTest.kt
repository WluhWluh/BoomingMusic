package com.mardous.booming.separation.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HtdemucsStreamingPlanTest {
    @Test
    fun `EOF window is centered padded and cropped`() {
        val planner = HtdemucsStreamingPlan()
        val trackSamples = HtdemucsStreamingPlan.STRIDE_SAMPLES + 101

        val windows = planner.windowPlans(trackSamples)
        val eof = windows.last()

        assertEquals(2, windows.size)
        assertEquals(101, eof.actualSamples)
        assertEquals(HtdemucsStreamingPlan.WINDOW_SAMPLES - 101, eof.cropLeft + eof.cropRight)
        assertEquals(HtdemucsStreamingPlan.WINDOW_SAMPLES, eof.padLeft + eof.sourceEnd - eof.sourceStart + eof.padRight)
    }

    @Test
    fun `triangular overlap add preserves constant planes`() {
        val planner = HtdemucsStreamingPlan()
        val trackSamples = HtdemucsStreamingPlan.STRIDE_SAMPLES + 101
        val outputs = planner.windowPlans(trackSamples).map { window ->
            HtdemucsWindowOutput(
                offset = window.offset,
                planarSamples = FloatArray(HtdemucsStreamingPlan.WINDOW_SAMPLES) { 0.375f },
            )
        }

        val result = planner.overlapAdd(trackSamples, 1, outputs)

        assertArrayEquals(FloatArray(trackSamples) { 0.375f }, result.planarSamples, 1e-6f)
        assertTrue(result.accumulatedWeights.all { it > 0f })
    }
}
