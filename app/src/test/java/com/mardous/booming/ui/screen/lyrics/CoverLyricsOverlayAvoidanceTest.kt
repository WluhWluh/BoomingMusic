package com.mardous.booming.ui.screen.lyrics

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class CoverLyricsOverlayAvoidanceTest {

    @Test
    fun `hidden source separation controls retain the base lyrics area`() {
        val avoidance = coverLyricsOverlayAvoidance(
            showSourceSeparationQuickControls = false,
            reserveExpandedControls = false,
        )

        assertEquals(72.dp, avoidance.minimumBottomPadding)
        assertEquals(0.dp, avoidance.endClearance)
    }

    @Test
    fun `collapsed quick controls reserve only their fixed bottom area`() {
        val avoidance = coverLyricsOverlayAvoidance(
            showSourceSeparationQuickControls = true,
            reserveExpandedControls = false,
        )

        assertEquals(124.dp, avoidance.minimumBottomPadding)
        assertEquals(0.dp, avoidance.endClearance)
    }

    @Test
    fun `expanded quick controls narrow the logical end without growing bottom padding`() {
        val avoidance = coverLyricsOverlayAvoidance(
            showSourceSeparationQuickControls = true,
            reserveExpandedControls = true,
        )

        assertEquals(124.dp, avoidance.minimumBottomPadding)
        assertEquals(48.dp, avoidance.endClearance)
    }

    @Test
    fun `hidden controls never reserve an expanded side strip`() {
        val avoidance = coverLyricsOverlayAvoidance(
            showSourceSeparationQuickControls = false,
            reserveExpandedControls = true,
        )

        assertEquals(72.dp, avoidance.minimumBottomPadding)
        assertEquals(0.dp, avoidance.endClearance)
    }
}
