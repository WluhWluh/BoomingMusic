package com.mardous.booming.separation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSeparationBlendDemandTest {
    @Test
    fun centerAndRoundingNoiseDoNotRequireSeparatedOutput() {
        assertFalse(SourceSeparationBlendDemand.requiresSeparatedOutput(0.5f))
        assertFalse(SourceSeparationBlendDemand.requiresSeparatedOutput(0.50005f))
        assertFalse(SourceSeparationBlendDemand.requiresSeparatedOutput(0.49995f))
    }

    @Test
    fun audibleControlPositionsRequireSeparatedOutput() {
        assertTrue(SourceSeparationBlendDemand.requiresSeparatedOutput(0f))
        assertTrue(SourceSeparationBlendDemand.requiresSeparatedOutput(1f))
        assertTrue(SourceSeparationBlendDemand.requiresSeparatedOutput(0.5002f))
        assertTrue(SourceSeparationBlendDemand.requiresSeparatedOutput(0.4998f))
    }

    @Test
    fun valuesAreClampedBeforeDemandIsEvaluated() {
        assertTrue(SourceSeparationBlendDemand.requiresSeparatedOutput(-1f))
        assertTrue(SourceSeparationBlendDemand.requiresSeparatedOutput(2f))
    }
}
