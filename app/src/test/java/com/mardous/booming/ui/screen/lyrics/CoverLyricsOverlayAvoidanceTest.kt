package com.mardous.booming.ui.screen.lyrics

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class CoverLyricsOverlayAvoidanceTest {

    @Test
    fun `hidden quick controls retain base bottom padding and full width`() {
        val avoidance = coverLyricsOverlayAvoidance(
            showSourceSeparationQuickControls = false,
            quickControlExpanded = true,
            quickControlHeight = 352.dp,
            quickControlVisibleHeight = 348.dp,
            progressAboveQuickControl = true,
            totalAvailableHeight = 800.dp,
        )

        assertEquals(72.dp, avoidance.minimumBottomPadding)
        assertEquals(0.dp, avoidance.endClearance)
    }

    @Test
    fun `collapsed horizontal controls fit inside base bottom padding`() {
        val avoidance = coverLyricsOverlayAvoidance(
            showSourceSeparationQuickControls = true,
            quickControlExpanded = false,
            quickControlHeight = 48.dp,
            quickControlVisibleHeight = 40.dp,
            progressAboveQuickControl = false,
            totalAvailableHeight = 800.dp,
        )

        assertEquals(72.dp, avoidance.minimumBottomPadding)
        assertEquals(0.dp, avoidance.endClearance)
    }

    @Test
    fun `short expanded control grows bottom padding instead of narrowing lyrics`() {
        val avoidance = coverLyricsOverlayAvoidance(
            showSourceSeparationQuickControls = true,
            quickControlExpanded = true,
            quickControlHeight = 124.dp,
            quickControlVisibleHeight = 120.dp,
            progressAboveQuickControl = false,
            totalAvailableHeight = 500.dp,
        )

        assertEquals(148.dp, avoidance.minimumBottomPadding)
        assertEquals(0.dp, avoidance.endClearance)
    }

    @Test
    fun `progress above short control contributes its full vertical offset`() {
        val avoidance = coverLyricsOverlayAvoidance(
            showSourceSeparationQuickControls = true,
            quickControlExpanded = true,
            quickControlHeight = 124.dp,
            quickControlVisibleHeight = 120.dp,
            progressAboveQuickControl = true,
            totalAvailableHeight = 500.dp,
        )

        assertEquals(180.dp, avoidance.minimumBottomPadding)
        assertEquals(0.dp, avoidance.endClearance)
    }

    @Test
    fun `progress in inner slot does not grow vertical avoidance`() {
        val avoidance = coverLyricsOverlayAvoidance(
            showSourceSeparationQuickControls = true,
            quickControlExpanded = true,
            quickControlHeight = 124.dp,
            quickControlVisibleHeight = 120.dp,
            progressAboveQuickControl = false,
            totalAvailableHeight = 500.dp,
        )

        assertEquals(148.dp, avoidance.minimumBottomPadding)
        assertEquals(0.dp, avoidance.endClearance)
    }

    @Test
    fun `tall expanded control narrows lyrics and disables variable bottom padding`() {
        val avoidance = coverLyricsOverlayAvoidance(
            showSourceSeparationQuickControls = true,
            quickControlExpanded = true,
            quickControlHeight = 352.dp,
            quickControlVisibleHeight = 348.dp,
            progressAboveQuickControl = true,
            totalAvailableHeight = 800.dp,
        )

        assertEquals(72.dp, avoidance.minimumBottomPadding)
        assertEquals(48.dp, avoidance.endClearance)
    }

    @Test
    fun `height equal to forty percent does not narrow lyrics`() {
        val avoidance = coverLyricsOverlayAvoidance(
            showSourceSeparationQuickControls = true,
            quickControlExpanded = true,
            quickControlHeight = 124.dp,
            quickControlVisibleHeight = 120.dp,
            progressAboveQuickControl = false,
            totalAvailableHeight = 300.dp,
        )

        assertEquals(148.dp, avoidance.minimumBottomPadding)
        assertEquals(0.dp, avoidance.endClearance)
    }

    @Test
    fun `height above forty percent narrows lyrics`() {
        val avoidance = coverLyricsOverlayAvoidance(
            showSourceSeparationQuickControls = true,
            quickControlExpanded = true,
            quickControlHeight = 124.dp,
            quickControlVisibleHeight = 120.dp,
            progressAboveQuickControl = false,
            totalAvailableHeight = 299.dp,
        )

        assertEquals(72.dp, avoidance.minimumBottomPadding)
        assertEquals(48.dp, avoidance.endClearance)
    }

    @Test
    fun `compact control uses bottom avoidance when below threshold`() {
        val avoidance = coverLyricsOverlayAvoidance(
            showSourceSeparationQuickControls = true,
            quickControlExpanded = true,
            quickControlHeight = 88.dp,
            quickControlVisibleHeight = 84.dp,
            progressAboveQuickControl = false,
            totalAvailableHeight = 300.dp,
        )

        assertEquals(112.dp, avoidance.minimumBottomPadding)
        assertEquals(0.dp, avoidance.endClearance)
    }

    @Test
    fun `compact control narrows lyrics when above threshold`() {
        val avoidance = coverLyricsOverlayAvoidance(
            showSourceSeparationQuickControls = true,
            quickControlExpanded = true,
            quickControlHeight = 88.dp,
            quickControlVisibleHeight = 84.dp,
            progressAboveQuickControl = false,
            totalAvailableHeight = 209.dp,
        )

        assertEquals(72.dp, avoidance.minimumBottomPadding)
        assertEquals(48.dp, avoidance.endClearance)
    }
}
