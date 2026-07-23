package com.mardous.booming.separation.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioTimelineTest {

    @Test
    fun `MP3 source frame count removes complete gapless metadata`() {
        val source = sourceInfo(
            mimeType = "audio/mpeg",
            durationFrames = 663_552,
            encoderDelayFrames = 576,
            encoderPaddingFrames = 1_476,
        )

        assertEquals(661_500, source.frameCount)
    }

    @Test
    fun `MP3 source frame count keeps encoded duration when metadata is incomplete`() {
        val source = sourceInfo(
            mimeType = "audio/mpeg",
            durationFrames = 663_552,
            encoderDelayFrames = 576,
            encoderPaddingFrames = null,
        )

        assertEquals(663_552, source.frameCount)
    }

    @Test
    fun `AAC source frame count also removes complete gapless metadata`() {
        val source = sourceInfo(
            mimeType = "audio/mp4a-latm",
            durationFrames = 662_524,
            encoderDelayFrames = 1_024,
            encoderPaddingFrames = 0,
        )

        assertEquals(661_500, source.frameCount)
    }

    @Test
    fun `legacy AAC inspection recovers a material edit-list duration`() {
        val corrected = correctedAacPresentationFrameCount(
            encodedDurationUs = 15_023_220,
            presentationDurationUs = 15_000_000,
            sampleRate = SAMPLE_RATE,
            mimeType = "audio/mp4a-latm",
        )

        assertEquals(661_500, corrected)
    }

    @Test
    fun `AAC presentation frames accept an already-trimmed extractor duration`() {
        val corrected = correctedAacPresentationFrameCount(
            encodedDurationUs = 15_000_090,
            presentationDurationUs = 15_000_000,
            sampleRate = SAMPLE_RATE,
            mimeType = "audio/mp4a-latm",
        )

        assertEquals(661_500, corrected)
    }

    @Test
    fun `AAC presentation frames reject an implausibly distant movie duration`() {
        val corrected = correctedAacPresentationFrameCount(
            encodedDurationUs = 16_000_000,
            presentationDurationUs = 15_000_000,
            sampleRate = SAMPLE_RATE,
            mimeType = "audio/mp4a-latm",
        )

        assertEquals(null, corrected)
    }

    @Test
    fun `decoded PCM fits the inspected timeline by trimming or zero padding`() {
        val source = DecodedPcmAudio(
            sampleRate = 44_100,
            channelCount = 2,
            pcm16 = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
        )

        assertArrayEquals(byteArrayOf(1, 2, 3, 4), source.fitToFrameCount(1).pcm16)
        assertArrayEquals(
            byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 0, 0, 0, 0),
            source.fitToFrameCount(3).pcm16,
        )
    }

    private fun sourceInfo(
        mimeType: String,
        durationFrames: Int,
        encoderDelayFrames: Int?,
        encoderPaddingFrames: Int?,
    ): AudioSourceInfo {
        return AudioSourceInfo(
            mimeType = mimeType,
            sampleRate = SAMPLE_RATE,
            channelCount = 2,
            durationUs = (durationFrames.toLong() * 1_000_000L) / SAMPLE_RATE,
            trackMetadata = AudioDecodeTrackMetadata(
                mimeType = mimeType,
                encoderDelayFrames = encoderDelayFrames,
                encoderPaddingFrames = encoderPaddingFrames,
            ),
        )
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
    }
}
