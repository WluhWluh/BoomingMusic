package com.mardous.booming.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackContentionDiagnosticsTest {
    @Test
    fun `schedstat parser reads runtime wait and timeslices`() {
        assertEquals(
            Triple(123L, 456L, 7L),
            PlaybackSchedulerDiagnosticsParser.parseSchedstat("123 456 7\n"),
        )
        assertNull(PlaybackSchedulerDiagnosticsParser.parseSchedstat("123 456"))
        assertNull(PlaybackSchedulerDiagnosticsParser.parseSchedstat("123 -1 7"))
    }

    @Test
    fun `status parser reads both context switch counters`() {
        val status = "Name:\tBooming-ExoPlayer\n" +
            "voluntary_ctxt_switches:\t321\n" +
            "nonvoluntary_ctxt_switches:\t45\n"

        assertEquals(
            321L to 45L,
            PlaybackSchedulerDiagnosticsParser.parseContextSwitches(status),
        )
    }

    @Test
    fun `snapshot delta requires one attached thread generation`() {
        val baseline = snapshot(generation = 4L, underruns = 1L, schedulerOffset = 10L)
        val current = snapshot(generation = 4L, underruns = 3L, schedulerOffset = 25L)

        val delta = current.deltaFrom(baseline)

        assertTrue(delta.available)
        assertEquals(2L, delta.audioUnderrunCount)
        assertEquals(15L, delta.playerRunTimeNanos)
        assertEquals(15L, delta.playerRunQueueWaitNanos)
        assertEquals(15L, delta.playerVoluntaryContextSwitches)
        assertFalse(current.copy(generation = 5L).deltaFrom(baseline).available)
    }

    private fun snapshot(
        generation: Long,
        underruns: Long,
        schedulerOffset: Long,
    ) = PlaybackContentionSnapshot(
        generation = generation,
        available = true,
        playerThreadId = 42,
        audioUnderrunCount = underruns,
        audioUnderrunElapsedSinceLastFeedTotalMs = underruns * 2L,
        maximumAudioUnderrunElapsedSinceLastFeedMs = 2L,
        maximumAudioUnderrunBufferSizeMs = 4L,
        scheduler = PlaybackSchedulerSnapshot(
            runTimeNanos = schedulerOffset,
            runQueueWaitNanos = schedulerOffset,
            timesliceCount = schedulerOffset,
            voluntaryContextSwitches = schedulerOffset,
            involuntaryContextSwitches = schedulerOffset,
        ),
    )
}
