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
    fun `paged height includes swap tracks page and close segments`() {
        assertEquals(260.dp, coverLyricsMultiStemPagedExpandedHeight(stemCount = 6))
        assertEquals(216.dp, coverLyricsMultiStemPagedExpandedHeight(stemCount = 4))
        assertEquals(6, coverLyricsMultiStemPagedSegmentCount(stemCount = 6))
        assertEquals(4, coverLyricsMultiStemPagedPageSegmentIndex(stemCount = 6))
    }

    @Test
    fun `odd stem count gives first page the extra stem and pads second page`() {
        assertEquals(3, coverLyricsMultiStemPagedTrackSlotCount(stemCount = 5))
        assertEquals(0, coverLyricsMultiStemPagedStemIndex(5, page = 0, slot = 0))
        assertEquals(2, coverLyricsMultiStemPagedStemIndex(5, page = 0, slot = 2))
        assertEquals(3, coverLyricsMultiStemPagedStemIndex(5, page = 1, slot = 0))
        assertEquals(4, coverLyricsMultiStemPagedStemIndex(5, page = 1, slot = 1))
        assertEquals(null, coverLyricsMultiStemPagedStemIndex(5, page = 1, slot = 2))
    }

    @Test
    fun `even stem count fills both pages equally`() {
        assertEquals(3, coverLyricsMultiStemPagedTrackSlotCount(stemCount = 6))
        assertEquals(3, coverLyricsMultiStemPagedStemIndex(6, page = 1, slot = 0))
        assertEquals(5, coverLyricsMultiStemPagedStemIndex(6, page = 1, slot = 2))
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
