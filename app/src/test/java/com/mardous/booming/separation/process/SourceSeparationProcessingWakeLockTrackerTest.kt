package com.mardous.booming.separation.process

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSeparationProcessingWakeLockTrackerTest {
    @Test
    fun `wake lock records a bounded exact lifetime`() {
        var now = 10L
        val tracker = SourceSeparationProcessingWakeLockTracker { now++ }
        val request = request()

        assertEquals(
            SourceSeparationProcessingWakeLockOperationResult.Applied,
            tracker.acquire(request, WAKE_LOCK_TAG, timeoutMs = 600_000L),
        )
        assertEquals(
            SourceSeparationProcessingWakeLockOperationResult.AlreadyApplied,
            tracker.acquire(request, WAKE_LOCK_TAG, timeoutMs = 600_000L),
        )
        assertEquals(
            SourceSeparationProcessingWakeLockOperationResult.Applied,
            tracker.renew(request, timeoutMs = 600_000L),
        )

        val held = requireNotNull(tracker.diagnostics().activeLease)
        assertTrue(tracker.diagnostics().platformHeld)
        assertEquals(SourceSeparationProcessingWakeLockLifecycle.Held, held.lifecycle)
        assertEquals(
            listOf(
                SourceSeparationProcessingWakeLockAction.Acquire,
                SourceSeparationProcessingWakeLockAction.Renew,
            ),
            held.events.map(SourceSeparationProcessingWakeLockEvent::action),
        )
        assertTrue(held.expiresAtElapsedRealtimeNanos >
            held.acquiredAtElapsedRealtimeNanos)

        assertEquals(
            SourceSeparationProcessingWakeLockOperationResult.Applied,
            tracker.release(request, "completed"),
        )
        assertEquals(
            SourceSeparationProcessingWakeLockOperationResult.AlreadyApplied,
            tracker.release(request, "completed"),
        )
        assertNull(tracker.diagnostics().activeLease)
        assertFalse(tracker.diagnostics().platformHeld)
        val released = requireNotNull(tracker.diagnostics().lastReleasedLease)
        assertEquals(SourceSeparationProcessingWakeLockLifecycle.Released, released.lifecycle)
        assertEquals("completed", released.releaseReason)
        assertEquals(SourceSeparationProcessingWakeLockAction.Release,
            released.events.last().action)
    }

    @Test
    fun `stale wake lock operations cannot affect another owner`() {
        val tracker = SourceSeparationProcessingWakeLockTracker { 10L }
        val request = request()
        tracker.acquire(request, WAKE_LOCK_TAG, timeoutMs = 600_000L)

        assertEquals(
            SourceSeparationProcessingWakeLockOperationResult.StaleRun,
            tracker.release(request.copy(runId = "other-run"), "stale"),
        )
        assertEquals(
            SourceSeparationProcessingWakeLockOperationResult.StaleGeneration,
            tracker.renew(request.copy(processGeneration = 8L), timeoutMs = 600_000L),
        )
        assertEquals(
            SourceSeparationProcessingWakeLockOperationResult.StaleLease,
            tracker.release(
                request.copy(leaseId = "foreground-wake-lease-0002"),
                "stale",
            ),
        )
        assertEquals(request, tracker.diagnostics().activeLease?.request)
    }

    @Test
    fun `a second wake lock lease cannot replace the active owner`() {
        val tracker = SourceSeparationProcessingWakeLockTracker { 10L }
        tracker.acquire(request(), WAKE_LOCK_TAG, timeoutMs = 600_000L)

        assertThrows(IllegalArgumentException::class.java) {
            tracker.acquire(
                request().copy(runId = "other-run"),
                WAKE_LOCK_TAG,
                timeoutMs = 600_000L,
            )
        }
    }

    private fun request() = SourceSeparationForegroundLeaseRequest(
        leaseId = "foreground-wake-lease-0001",
        runId = "manual-run",
        processGeneration = 7L,
        displayName = "Test song",
    )

    private companion object {
        const val WAKE_LOCK_TAG = "com.example:SourceSeparationInference"
    }
}
