package com.mardous.booming.ui.screen.lyrics

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class CoverLyricsOverlayAvoidanceTest {

    @Test
    fun `hidden source separation controls do not narrow lyrics`() {
        val clearance = coverLyricsEndClearance(
            showSourceSeparationQuickControls = false,
            reserveExpandedControls = false,
        )

        assertEquals(0.dp, clearance)
    }

    @Test
    fun `collapsed quick controls do not narrow lyrics`() {
        val clearance = coverLyricsEndClearance(
            showSourceSeparationQuickControls = true,
            reserveExpandedControls = false,
        )

        assertEquals(0.dp, clearance)
    }

    @Test
    fun `expanded quick controls narrow the logical end by one slot`() {
        val clearance = coverLyricsEndClearance(
            showSourceSeparationQuickControls = true,
            reserveExpandedControls = true,
        )

        assertEquals(48.dp, clearance)
    }

    @Test
    fun `hidden controls never reserve an expanded side strip`() {
        val clearance = coverLyricsEndClearance(
            showSourceSeparationQuickControls = false,
            reserveExpandedControls = true,
        )

        assertEquals(0.dp, clearance)
    }
}
