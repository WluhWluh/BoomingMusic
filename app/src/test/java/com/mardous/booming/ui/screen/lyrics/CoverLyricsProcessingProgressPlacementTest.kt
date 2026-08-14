package com.mardous.booming.ui.screen.lyrics

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class CoverLyricsProcessingProgressPlacementTest {

    @Test
    fun `full control remains when its own height exactly fits`() {
        assertEquals(
            CoverLyricsQuickControlLayout.Full,
            coverLyricsQuickControlLayout(
                availableHeight = 388.dp,
                safeDrawingTop = 20.dp,
                bottomPadding = 16.dp,
                fullQuickControlHeight = 352.dp,
                pagedQuickControlHeight = 264.dp,
            ),
        )
    }

    @Test
    fun `paged control replaces full control when full height misses by one dp`() {
        assertEquals(
            CoverLyricsQuickControlLayout.Paged,
            coverLyricsQuickControlLayout(
                availableHeight = 387.dp,
                safeDrawingTop = 20.dp,
                bottomPadding = 16.dp,
                fullQuickControlHeight = 352.dp,
                pagedQuickControlHeight = 264.dp,
            ),
        )
    }

    @Test
    fun `compact control replaces paged control when paged height misses by one dp`() {
        assertEquals(
            CoverLyricsQuickControlLayout.Compact,
            coverLyricsQuickControlLayout(
                availableHeight = 299.dp,
                safeDrawingTop = 20.dp,
                bottomPadding = 16.dp,
                fullQuickControlHeight = 352.dp,
                pagedQuickControlHeight = 264.dp,
            ),
        )
    }

    @Test
    fun `two stem control falls directly to compact when full height misses`() {
        assertEquals(
            CoverLyricsQuickControlLayout.Compact,
            coverLyricsQuickControlLayout(
                availableHeight = 159.dp,
                safeDrawingTop = 20.dp,
                bottomPadding = 16.dp,
                fullQuickControlHeight = 124.dp,
                pagedQuickControlHeight = null,
            ),
        )
    }

    @Test
    fun `compact control progress remains above when its final height fits`() {
        assertEquals(
            CoverLyricsProcessingProgressPlacement.AboveQuickControl,
            coverLyricsProcessingProgressPlacement(
                availableHeight = 160.dp,
                safeDrawingTop = 20.dp,
                bottomPadding = 16.dp,
                quickControlHeight = 88.dp,
            ),
        )
    }

    @Test
    fun `compact control progress uses inner slot when only control fits`() {
        assertEquals(
            CoverLyricsProcessingProgressPlacement.FullscreenButtonInnerSlot,
            coverLyricsProcessingProgressPlacement(
                availableHeight = 150.dp,
                safeDrawingTop = 20.dp,
                bottomPadding = 16.dp,
                quickControlHeight = 88.dp,
            ),
        )
    }

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

    @Test
    fun `compact control top heat area includes upper half of visual gap`() {
        assertEquals(
            CoverLyricsCompactControlAction.OpenPanel,
            coverLyricsCompactControlActionForY(y = 41f, heightPx = 84f),
        )
    }

    @Test
    fun `compact control bottom heat area includes lower half of visual gap`() {
        assertEquals(
            CoverLyricsCompactControlAction.DisableSeparatedPlayback,
            coverLyricsCompactControlActionForY(y = 43f, heightPx = 84f),
        )
    }

    @Test
    fun `compact control heat areas meet at visual gap midpoint`() {
        assertEquals(
            CoverLyricsCompactControlAction.DisableSeparatedPlayback,
            coverLyricsCompactControlActionForY(y = 42f, heightPx = 84f),
        )
    }
}
