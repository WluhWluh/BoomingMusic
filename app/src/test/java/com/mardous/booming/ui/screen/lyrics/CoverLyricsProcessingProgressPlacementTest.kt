package com.mardous.booming.ui.screen.lyrics

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class CoverLyricsProcessingProgressPlacementTest {

    @Test
    fun `progress remains above quick control when it fits safe drawing area`() {
        assertEquals(
            CoverLyricsProcessingProgressPlacement.AboveQuickControl,
            coverLyricsProcessingProgressPlacement(
                availableHeight = 424.dp,
                safeDrawingTop = 24.dp,
                bottomPadding = 16.dp,
                quickControlHeight = 352.dp,
            ),
        )
    }

    @Test
    fun `progress moves to inner slot when it misses safe area by one dp`() {
        assertEquals(
            CoverLyricsProcessingProgressPlacement.FullscreenButtonInnerSlot,
            coverLyricsProcessingProgressPlacement(
                availableHeight = 423.dp,
                safeDrawingTop = 24.dp,
                bottomPadding = 16.dp,
                quickControlHeight = 352.dp,
            ),
        )
    }

    @Test
    fun `six stem control keeps progress above on a normal portrait viewport`() {
        assertEquals(
            CoverLyricsProcessingProgressPlacement.AboveQuickControl,
            coverLyricsProcessingProgressPlacement(
                availableHeight = 800.dp,
                safeDrawingTop = 24.dp,
                bottomPadding = 16.dp,
                quickControlHeight = coverLyricsMultiStemExpandedHeight(6) + 4.dp,
            ),
        )
    }
}
