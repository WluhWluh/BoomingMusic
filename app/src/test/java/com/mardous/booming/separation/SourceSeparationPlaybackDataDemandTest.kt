package com.mardous.booming.separation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSeparationPlaybackDataDemandTest {
    @Test
    fun `initial neutral mix does not require separated data`() {
        val demand = SourceSeparationPlaybackDataDemand()

        demand.moveToMedia("101")
        demand.observeMix(requiresSeparatedOutput = false)

        assertFalse(demand.requiresSeparatedData(playbackRequested = true))
    }

    @Test
    fun `neutral mix remains active after separated output was entered`() {
        val demand = SourceSeparationPlaybackDataDemand()

        demand.moveToMedia("101")
        demand.observeMix(requiresSeparatedOutput = true)
        demand.observeMix(requiresSeparatedOutput = false)

        assertTrue(demand.requiresSeparatedData(playbackRequested = true))
        assertFalse(demand.requiresSeparatedData(playbackRequested = false))
    }

    @Test
    fun `changing songs clears the latched demand`() {
        val demand = SourceSeparationPlaybackDataDemand()

        demand.moveToMedia("101")
        demand.observeMix(requiresSeparatedOutput = true)
        demand.moveToMedia("202")

        assertFalse(demand.requiresSeparatedData(playbackRequested = true))
    }

    @Test
    fun `moving within the same song preserves the latched demand`() {
        val demand = SourceSeparationPlaybackDataDemand()

        demand.moveToMedia("101")
        demand.observeMix(requiresSeparatedOutput = true)
        demand.moveToMedia("101")

        assertTrue(demand.requiresSeparatedData(playbackRequested = true))
    }
}
