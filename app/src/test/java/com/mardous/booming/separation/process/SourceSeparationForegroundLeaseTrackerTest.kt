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
        assertThrows(IllegalArgumentException::class.java) {
            tracker.control(
                request,
                commandId = "notification-pause-lease-0001",
                action = SourceSeparationForegroundControlAction.Cancel,
            )
        }
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
        assertThrows(IllegalArgumentException::class.java) {
            tracker.control(
                request,
                commandId = "notification-pause-lease-0001",
                action = SourceSeparationForegroundControlAction.Cancel,
            )
        }
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

    @Test
    fun `media processing timeout stops and records one exact lease`() {
        var now = 20L
        val tracker = SourceSeparationForegroundLeaseTracker { now++ }
        val request = request()
        tracker.started(
            request,
            notificationId = 21_331,
            platformPolicy = SourceSeparationForegroundPlatformPolicy.TimedMediaProcessing,
        )
        tracker.attach(request)

        assertEquals(
            SourceSeparationForegroundLeaseOperationResult.Applied,
            tracker.timedOut(request, startId = 4, foregroundServiceType = 0x2000),
        )
        assertEquals(
            SourceSeparationForegroundLeaseOperationResult.AlreadyApplied,
            tracker.timedOut(request, startId = 4, foregroundServiceType = 0x2000),
        )

        val diagnostics = tracker.diagnostics()
        assertNull(diagnostics.activeLease)
        val stopped = requireNotNull(diagnostics.lastStoppedLease)
        assertEquals("media-processing-timeout", stopped.stopReason)
        assertEquals(4, stopped.timeout?.startId)
        assertEquals(0x2000, stopped.timeout?.foregroundServiceType)
        assertEquals(stopped.stoppedAtElapsedRealtimeNanos,
            stopped.timeout?.timestampElapsedRealtimeNanos)
    }

    @Test
    fun `only the media processing foreground type owns timeout policy`() {
        assertTrue(isSourceSeparationMediaProcessingForegroundServiceType(0x2000))
        assertTrue(!isSourceSeparationMediaProcessingForegroundServiceType(0x0001))
        assertTrue(!isSourceSeparationMediaProcessingForegroundServiceType(0))
    }

    private fun request() = SourceSeparationForegroundLeaseRequest(
        leaseId = "foreground-lease-0001",
        runId = "manual-run",
        processGeneration = 7L,
        displayName = "Test song",
    )
}
