package com.mardous.booming.ui.screen.lyrics

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class CoverLyricsMultiStemControlTest {
    @Test
    fun `expanded height includes every segment and internal gap`() {
        assertEquals(348.dp, coverLyricsMultiStemExpandedHeight(stemCount = 6))
    }

    @Test
    fun `gap hit areas are split between adjacent segments`() {
        assertEquals(
            0,
            coverLyricsMultiStemHitSegment(
                y = 41f,
                segmentCount = 4,
                segmentHeightPx = 40f,
                segmentGapPx = 4f,
            ),
        )
        assertEquals(
            1,
            coverLyricsMultiStemHitSegment(
                y = 43f,
                segmentCount = 4,
                segmentHeightPx = 40f,
                segmentGapPx = 4f,
            ),
        )
    }

    @Test
    fun `full gain range requires twice the segment height`() {
        assertEquals(
            1f,
            coverLyricsMultiStemGainForDrag(
                startGain = 0f,
                deltaY = -80f,
                segmentHeightPx = 40f,
            ),
            0f,
        )
        assertEquals(
            0f,
            coverLyricsMultiStemGainForDrag(
                startGain = 1f,
                deltaY = 80f,
                segmentHeightPx = 40f,
            ),
            0f,
        )
        assertEquals(
            0.5f,
            coverLyricsMultiStemGainForDrag(
                startGain = 0f,
                deltaY = -40f,
                segmentHeightPx = 40f,
            ),
            0f,
        )
    }
}
