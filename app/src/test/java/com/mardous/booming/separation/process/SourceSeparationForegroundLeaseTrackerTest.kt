package com.mardous.booming.separation.process

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSeparationForegroundLeaseTrackerTest {
    @Test
    fun `lease records one exact foreground lifetime`() {
        var now = 10L
        val tracker = SourceSeparationForegroundLeaseTracker { now++ }
        val request = request()

        assertEquals(
            SourceSeparationForegroundLeaseOperationResult.Applied,
            tracker.started(
                request,
                notificationId = 21_331,
                platformPolicy = SourceSeparationForegroundPlatformPolicy
                    .TimedMediaProcessing,
            ),
        )
        assertEquals(
            SourceSeparationForegroundLeaseOperationResult.AlreadyApplied,
            tracker.started(
                request,
                notificationId = 21_331,
                platformPolicy = SourceSeparationForegroundPlatformPolicy
                    .TimedMediaProcessing,
            ),
        )
        assertEquals(
            SourceSeparationForegroundLeaseOperationResult.Applied,
            tracker.attach(request),
        )
        assertEquals(
            SourceSeparationForegroundLeaseOperationResult.Applied,
            tracker.control(
                request,
                commandId = "notification-pause-lease-0001",
                action = SourceSeparationForegroundControlAction.Pause,
            ),
        )
        assertEquals(
            SourceSeparationForegroundLeaseOperationResult.AlreadyApplied,
            tracker.control(
                request,
                commandId = "notification-pause-lease-0001",
                action = SourceSeparationForegroundControlAction.Pause,
            ),
        )
        assertEquals(
            SourceSeparationForegroundLeaseOperationResult.Applied,
            tracker.stop(request, "paused"),
        )
        assertEquals(
            SourceSeparationForegroundLeaseOperationResult.AlreadyApplied,
            tracker.stop(request, "paused"),
        )
        assertEquals(
            SourceSeparationForegroundLeaseOperationResult.AlreadyApplied,
            tracker.started(
                request,
                notificationId = 21_331,
                platformPolicy = SourceSeparationForegroundPlatformPolicy
                    .TimedMediaProcessing,
            ),
        )

        val diagnostics = tracker.diagnostics()
        assertNull(diagnostics.activeLease)
        val stopped = requireNotNull(diagnostics.lastStoppedLease)
        assertEquals(SourceSeparationForegroundLeaseLifecycle.Stopped, stopped.lifecycle)
        assertEquals("paused", stopped.stopReason)
        assertEquals(1, stopped.controls.size)
        assertTrue(requireNotNull(stopped.attachedAtElapsedRealtimeNanos) >=
            stopped.startedAtElapsedRealtimeNanos)
        assertTrue(requireNotNull(stopped.stoppedAtElapsedRealtimeNanos) >=
            stopped.attachedAtElapsedRealtimeNanos)
    }

    @Test
    fun `stale foreground controls cannot affect another run`() {
        val tracker = SourceSeparationForegroundLeaseTracker { 10L }
        val request = request()
        tracker.started(
            request,
            notificationId = 21_331,
            platformPolicy = SourceSeparationForegroundPlatformPolicy.LegacyMediaProcessing,
        )

        assertEquals(
            SourceSeparationForegroundLeaseOperationResult.StaleRun,
            tracker.control(
                request.copy(runId = "other-run"),
                "notification-cancel-lease-0001",
                SourceSeparationForegroundControlAction.Cancel,
            ),
        )
        assertEquals(
            SourceSeparationForegroundLeaseOperationResult.StaleGeneration,
            tracker.attach(request.copy(processGeneration = 8L)),
        )
        assertEquals(
            SourceSeparationForegroundLeaseOperationResult.StaleLease,
            tracker.stop(request.copy(leaseId = "foreground-lease-0002"), "stale"),
        )
        assertEquals(request, tracker.diagnostics().activeLease?.request)
    }

    @Test
    fun `a second foreground lease cannot replace the active owner`() {
        val tracker = SourceSeparationForegroundLeaseTracker { 10L }
        tracker.started(
            request(),
            notificationId = 21_331,
            platformPolicy = SourceSeparationForegroundPlatformPolicy.LegacyMediaProcessing,
        )

        assertThrows(IllegalArgumentException::class.java) {
            tracker.started(
                request().copy(runId = "other-run"),
                notificationId = 21_331,
                platformPolicy = SourceSeparationForegroundPlatformPolicy
                    .LegacyMediaProcessing,
            )
        }
    }

    private fun request() = SourceSeparationForegroundLeaseRequest(
        leaseId = "foreground-lease-0001",
        runId = "manual-run",
        processGeneration = 7L,
        displayName = "Test song",
    )
}
