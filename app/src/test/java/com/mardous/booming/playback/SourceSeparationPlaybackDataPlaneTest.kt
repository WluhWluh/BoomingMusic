package com.mardous.booming.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSeparationPlaybackDataPlaneTest {
    @Test
    fun geometryMapsTransportPositionAndRejectsIncompatibleStems() {
        val geometry = SourceSeparationPlaybackGeometry(
            sampleRate = 48_000,
            channelCount = 2,
            frameCount = 96_000L,
            encoderDelayFrames = 480L,
            paddingFrames = 480L,
        )
        assertEquals(48_480L, geometry.transportPositionToStemFrame(1_000L))
        assertEquals(980L, geometry.stemFrameToTransportPosition(47_520L))

        val first = SourceSeparationPlaybackStemSpec("vocals", geometry)
        val second = SourceSeparationPlaybackStemSpec(
            "instrumental",
            geometry.copy(frameCount = 95_999L),
        )
        assertThrows(IllegalArgumentException::class.java) {
            SourceSeparationPlaybackDataSession(1L, 1L, listOf(first, second))
        }
    }

    @Test
    fun stateMachineAllowsOnlyExplicitTransportTransitions() {
        val machine = SourceSeparationPlaybackStateMachine()
        machine.transition(SourceSeparationPlaybackDataState.Preparing)
        machine.transition(SourceSeparationPlaybackDataState.Buffering)
        machine.transition(SourceSeparationPlaybackDataState.Ready)
        machine.transition(SourceSeparationPlaybackDataState.Seeking)
        machine.transition(SourceSeparationPlaybackDataState.Buffering)
        machine.transition(SourceSeparationPlaybackDataState.Ready)
        machine.transition(SourceSeparationPlaybackDataState.Ended)
        machine.transition(SourceSeparationPlaybackDataState.Idle)
        assertEquals(SourceSeparationPlaybackDataState.Idle, machine.state)
        assertThrows(IllegalArgumentException::class.java) {
            machine.transition(SourceSeparationPlaybackDataState.Ready)
        }
    }

    @Test
    fun epochsRejectStaleResultsAndMetricsExposeRequiredSignals() {
        val epochs = SourceSeparationPlaybackEpoch()
        val first = epochs.next()
        val second = epochs.next()
        assertFalse(epochs.isCurrent(first))
        assertTrue(epochs.isCurrent(second))

        val metrics = SourceSeparationPlaybackMetrics(latencySampleCapacity = 4)
        metrics.recordDecodeBlock(40L)
        metrics.recordDecodeBlock(10L)
        metrics.recordDecodeBlock(30L)
        metrics.recordRingOccupancy(3)
        metrics.recordLowWater()
        metrics.recordUnderrun()
        metrics.recordSeekRequest()
        metrics.recordSeekReady()
        metrics.recordEpochChange()
        metrics.recordAudioThreadAllocation()
        metrics.setOpenFileDescriptors(2L)
        metrics.setBufferPool(stemCount = 4, byteCount = 12_288L)
        val snapshot = metrics.snapshot()
        assertEquals(3L, snapshot.decodeBlockCount)
        assertEquals(30L, snapshot.decodeBlockLatencyNs.p50)
        assertEquals(40L, snapshot.decodeBlockLatencyNs.max)
        assertEquals(1L, snapshot.lowWaterEvents)
        assertEquals(1L, snapshot.underruns)
        assertEquals(1L, snapshot.seekRequests)
        assertEquals(1L, snapshot.seekReady)
        assertEquals(1L, snapshot.epochChanges)
        assertEquals(1L, snapshot.audioThreadAllocations)
        assertEquals(2L, snapshot.openFileDescriptors)
        assertEquals(4, snapshot.activeStemCount)
        assertEquals(12_288L, snapshot.bufferPoolBytes)
    }

    @Test
    fun realtimeAuditStartsCleanAndRecordsForbiddenOperations() {
        val audit = SourceSeparationPlaybackRealtimeAudit()
        assertFalse(audit.hasViolations())
        audit.record(SourceSeparationRealtimeViolation.FileIo)
        audit.record(SourceSeparationRealtimeViolation.FileIo)
        audit.record(SourceSeparationRealtimeViolation.Decode)
        assertTrue(audit.hasViolations())
        assertEquals(
            2L,
            audit.snapshot()[SourceSeparationRealtimeViolation.FileIo],
        )
        assertEquals(
            1L,
            audit.snapshot()[SourceSeparationRealtimeViolation.Decode],
        )
    }
}
