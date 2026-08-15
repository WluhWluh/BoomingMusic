package com.mardous.booming.ui.screen.lyrics

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class CoverLyricsMultiStemControlTest {
    @Test
    fun `two segment tap expansion is fully absorbed by other segment`() {
        assertEquals(
            listOf(4.dp, (-4).dp),
            coverLyricsSegmentTapHeightOffsets(segmentCount = 2, tappedSegment = 0),
        )
    }

    @Test
    fun `end segment tap expansion is absorbed three then one dp`() {
        val topOffsets = coverLyricsSegmentTapHeightOffsets(
            segmentCount = 6,
            tappedSegment = 0,
        )
        val bottomOffsets = coverLyricsSegmentTapHeightOffsets(
            segmentCount = 6,
            tappedSegment = 5,
        )

        assertEquals(listOf(4.dp, (-3).dp, (-1).dp, 0.dp, 0.dp, 0.dp), topOffsets)
        assertEquals(listOf(0.dp, 0.dp, 0.dp, (-1).dp, (-3).dp, 4.dp), bottomOffsets)
        assertEquals(0.dp, topOffsets.fold(0.dp) { total, value -> total + value })
        assertEquals(0.dp, bottomOffsets.fold(0.dp) { total, value -> total + value })
    }

    @Test
    fun `middle segment expands six dp split across adjacent segments`() {
        val offsets = coverLyricsSegmentTapHeightOffsets(
            segmentCount = 6,
            tappedSegment = 3,
        )

        assertEquals(listOf(0.dp, 0.dp, (-3).dp, 6.dp, (-3).dp, 0.dp), offsets)
        assertEquals(0.dp, offsets.fold(0.dp) { total, value -> total + value })
    }

    @Test
    fun `tap expansion yields to a simultaneous zero height transition`() {
        assertEquals(
            0f,
            coverLyricsConstrainedSegmentTapExpansion(
                requestedExpansion = 1f,
                baseHeights = listOf(20.dp, 0.dp, 0.dp, 20.dp),
                heightOffsets = listOf(0.dp, (-2).dp, (-4).dp, 6.dp),
            ),
            0f,
        )
    }

    @Test
    fun `tapped capsule end corner reaches ten dp`() {
        assertEquals(
            10.dp,
            coverLyricsSegmentTapOuterCornerRadius(
                segmentIndex = 0,
                endSegmentIndex = 0,
                tappedSegment = 0,
                expansion = 1f,
            ),
        )
        assertEquals(
            20.dp,
            coverLyricsSegmentTapOuterCornerRadius(
                segmentIndex = 0,
                endSegmentIndex = 0,
                tappedSegment = 1,
                expansion = 1f,
            ),
        )
    }

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
        assertEquals(
            Offset(20f, 40f),
            coverLyricsSegmentPressPosition(
                pointerPosition = Offset(20f, 41f),
                segmentIndex = 0,
                segmentHeightPx = 40f,
                segmentGapPx = 4f,
            ),
        )
        assertEquals(
            Offset(20f, 0f),
            coverLyricsSegmentPressPosition(
                pointerPosition = Offset(20f, 43f),
                segmentIndex = 1,
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
