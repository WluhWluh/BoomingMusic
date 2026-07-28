package com.mardous.booming.separation.process

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSeparationProcessingOwnershipHandoffTest {
    @Test
    fun `accepted run remains owner until its lease is explicitly released`() {
        var now = 10L
        val handoff = SourceSeparationProcessingOwnershipHandoff { now++ }
        val lease = handoff.createLease()
        val owner = owner()

        assertTrue(lease.accepted(owner))
        assertFalse(lease.accepted(owner))
        assertEquals(owner, handoff.stateFlow.value.activeOwner?.owner)
        assertEquals(10L, handoff.stateFlow.value.activeOwner?.acceptedAtElapsedRealtimeNanos)
        assertNull(handoff.stateFlow.value.lastReleasedOwner)

        assertTrue(lease.release("execution-host-closed"))
        assertFalse(lease.release("duplicate"))
        val snapshot = handoff.stateFlow.value
        assertNull(snapshot.activeOwner)
        assertEquals(owner, snapshot.lastReleasedOwner?.owner)
        assertEquals(11L, snapshot.lastReleasedOwner?.releasedAtElapsedRealtimeNanos)
        assertEquals("execution-host-closed", snapshot.lastReleasedOwner?.releaseReason)
    }

    @Test
    fun `stale lease cannot clear a newer owner`() {
        var now = 20L
        val handoff = SourceSeparationProcessingOwnershipHandoff { now++ }
        val staleLease = handoff.createLease()
        staleLease.accepted(owner(runId = "old-run"))
        staleLease.release("old-host-closed")

        val currentOwner = owner(runId = "new-run", processGeneration = 8L)
        val currentLease = handoff.createLease()
        currentLease.accepted(currentOwner)

        assertFalse(staleLease.release("stale-release"))
        assertEquals(currentOwner, handoff.stateFlow.value.activeOwner?.owner)
        currentLease.close()
        assertNull(handoff.stateFlow.value.activeOwner)
    }

    @Test
    fun `a second lease cannot replace an active remote owner`() {
        val handoff = SourceSeparationProcessingOwnershipHandoff { 30L }
        handoff.createLease().accepted(owner())

        assertThrows(IllegalStateException::class.java) {
            handoff.createLease().accepted(owner(runId = "other-run"))
        }
        assertEquals("manual-run", handoff.stateFlow.value.activeOwner?.owner?.runId)
    }

    @Test
    fun `closing before acceptance cannot publish ownership`() {
        val handoff = SourceSeparationProcessingOwnershipHandoff { 40L }
        val lease = handoff.createLease()

        lease.close()

        assertFalse(lease.accepted(owner()))
        assertEquals(SourceSeparationProcessingOwnershipSnapshot(), handoff.stateFlow.value)
    }

    @Test
    fun `playback lease is suppressed only for the exact cache takeover`() {
        val owner = SourceSeparationProcessingOwnershipRecord(
            owner = owner(cacheKey = "model-a-song-1"),
            acceptedAtElapsedRealtimeNanos = 50L,
        )
        val handoff = SourceSeparationProcessingOwnershipSnapshot(activeOwner = owner)

        assertFalse(SourceSeparationProcessingLeasePolicy.shouldPlaybackServiceOwn(
            waitingForProcessingCache = true,
            waitingCacheKey = "model-a-song-1",
            handoff = handoff,
        ))
        assertTrue(SourceSeparationProcessingLeasePolicy.shouldPlaybackServiceOwn(
            waitingForProcessingCache = true,
            waitingCacheKey = "model-b-song-1",
            handoff = handoff,
        ))
        assertTrue(SourceSeparationProcessingLeasePolicy.shouldPlaybackServiceOwn(
            waitingForProcessingCache = true,
            waitingCacheKey = null,
            handoff = handoff,
        ))
        assertFalse(SourceSeparationProcessingLeasePolicy.shouldPlaybackServiceOwn(
            waitingForProcessingCache = false,
            waitingCacheKey = "model-a-song-1",
            handoff = handoff,
        ))
    }

    private fun owner(
        cacheKey: String = "model-a-song-1",
        runId: String = "manual-run",
        processGeneration: Long = 7L,
    ) = SourceSeparationProcessingOwnerToken(
        cacheKey = cacheKey,
        runId = runId,
        processGeneration = processGeneration,
    )
}
