package com.mardous.booming.separation.process

import com.mardous.booming.separation.SourceSeparationBackgroundPolicy
import com.mardous.booming.separation.SourceSeparationExecutionRunClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSeparationIndependentRunAuthorityTest {
    @Test
    fun `exact protected manual run can continue without its observer`() {
        assertTrue(SourceSeparationIndependentRunAuthorityPolicy.isEstablished(evidence()))
    }

    @Test
    fun `client-bound work and incomplete protection remain fail closed`() {
        assertFalse(SourceSeparationIndependentRunAuthorityPolicy.isEstablished(
            evidence().copy(
                runClass = SourceSeparationExecutionRunClass.PlaybackDemandWindow,
                backgroundPolicy = SourceSeparationBackgroundPolicy.PlaybackServiceOwned,
            )
        ))
        assertFalse(SourceSeparationIndependentRunAuthorityPolicy.isEstablished(
            evidence().copy(hostSnapshotAvailable = false)
        ))
        assertFalse(SourceSeparationIndependentRunAuthorityPolicy.isEstablished(
            evidence().copy(foregroundLease = null)
        ))
        assertFalse(SourceSeparationIndependentRunAuthorityPolicy.isEstablished(
            evidence().copy(wakeLockLease = null)
        ))
        assertFalse(SourceSeparationIndependentRunAuthorityPolicy.isEstablished(
            evidence().copy(platformWakeLockHeld = false)
        ))
    }

    @Test
    fun `stale lease identities cannot establish authority`() {
        assertFalse(SourceSeparationIndependentRunAuthorityPolicy.isEstablished(
            evidence().copy(
                foregroundLease = foregroundLease().copy(
                    request = request().copy(runId = "stale-run"),
                ),
            )
        ))
        assertFalse(SourceSeparationIndependentRunAuthorityPolicy.isEstablished(
            evidence().copy(
                wakeLockLease = wakeLockLease().copy(
                    request = request().copy(processGeneration = 8L),
                ),
            )
        ))
    }

    @Test
    fun `detached observer expires once at the bounded deadline`() {
        val scheduler = RecordingDeadlineScheduler()
        val expired = mutableListOf<SourceSeparationIndependentRunIdentity>()
        val deadline = SourceSeparationIndependentRunObserverDeadline(
            scheduler = scheduler,
            timeoutMs = 123L,
            onExpired = expired::add,
        )
        val identity = SourceSeparationIndependentRunIdentity("run-1", 7L)

        deadline.observerDisconnected(identity)
        assertEquals(123L, scheduler.tasks.single().delayMs)
        scheduler.tasks.single().run()
        scheduler.tasks.single().run()

        assertEquals(listOf(identity), expired)
    }

    @Test
    fun `exact reconnection or close cancels observer deadline`() {
        val scheduler = RecordingDeadlineScheduler()
        val expired = mutableListOf<SourceSeparationIndependentRunIdentity>()
        val deadline = SourceSeparationIndependentRunObserverDeadline(
            scheduler = scheduler,
            timeoutMs = 123L,
            onExpired = expired::add,
        )
        val first = SourceSeparationIndependentRunIdentity("run-1", 7L)
        val second = SourceSeparationIndependentRunIdentity("run-2", 8L)

        deadline.observerDisconnected(first)
        deadline.observerConnected(first)
        deadline.observerDisconnected(second)
        deadline.runClosed(second)
        scheduler.tasks.forEach(RecordingDeadlineTask::run)

        assertTrue(scheduler.tasks.all(RecordingDeadlineTask::canceled))
        assertTrue(expired.isEmpty())
    }

    @Test
    fun `stale connection cannot cancel a newer observer deadline`() {
        val scheduler = RecordingDeadlineScheduler()
        val expired = mutableListOf<SourceSeparationIndependentRunIdentity>()
        val deadline = SourceSeparationIndependentRunObserverDeadline(
            scheduler = scheduler,
            timeoutMs = 123L,
            onExpired = expired::add,
        )
        val stale = SourceSeparationIndependentRunIdentity("run-1", 7L)
        val current = SourceSeparationIndependentRunIdentity("run-2", 8L)

        deadline.observerDisconnected(stale)
        deadline.observerDisconnected(current)
        deadline.observerConnected(stale)
        scheduler.tasks.forEach(RecordingDeadlineTask::run)

        assertTrue(scheduler.tasks.first().canceled)
        assertEquals(listOf(current), expired)
    }

    @Test
    fun `production unobserved bound leaves media-processing quota margin`() {
        assertEquals(
            20_700_000L,
            SourceSeparationIndependentRunAuthorityPolicy.MAX_UNOBSERVED_CONTINUATION_MS,
        )
    }

    private fun evidence() = SourceSeparationIndependentRunAuthorityEvidence(
        runId = "run-1",
        processGeneration = 7L,
        displayName = "Song",
        runClass = SourceSeparationExecutionRunClass.ManualFullSong,
        backgroundPolicy = SourceSeparationBackgroundPolicy.IndependentForegroundEligible,
        hostSnapshotAvailable = true,
        foregroundLease = foregroundLease(),
        wakeLockLease = wakeLockLease(),
        platformWakeLockHeld = true,
    )

    private fun request() = SourceSeparationForegroundLeaseRequest(
        leaseId = "foreground-authority-1",
        runId = "run-1",
        processGeneration = 7L,
        displayName = "Song",
    )

    private fun foregroundLease() = SourceSeparationForegroundLeaseRecord(
        request = request(),
        lifecycle = SourceSeparationForegroundLeaseLifecycle.Active,
        notificationId = 21_331,
        platformPolicy = SourceSeparationForegroundPlatformPolicy.LegacyMediaProcessing,
        startedAtElapsedRealtimeNanos = 10L,
        attachedAtElapsedRealtimeNanos = 20L,
    )

    private fun wakeLockLease() = SourceSeparationProcessingWakeLockLeaseRecord(
        request = request(),
        tag = "test:SourceSeparationInference",
        lifecycle = SourceSeparationProcessingWakeLockLifecycle.Held,
        acquiredAtElapsedRealtimeNanos = 30L,
        expiresAtElapsedRealtimeNanos = 1_000L,
        events = listOf(
            SourceSeparationProcessingWakeLockEvent(
                action = SourceSeparationProcessingWakeLockAction.Acquire,
                timestampElapsedRealtimeNanos = 30L,
                timeoutMs = 970L,
            )
        ),
    )

    private class RecordingDeadlineScheduler :
        SourceSeparationIndependentRunDeadlineScheduler {
        val tasks = mutableListOf<RecordingDeadlineTask>()

        override fun schedule(
            delayMs: Long,
            action: () -> Unit,
        ): SourceSeparationIndependentRunDeadlineCancellation {
            return RecordingDeadlineTask(delayMs, action).also(tasks::add)
        }
    }

    private class RecordingDeadlineTask(
        val delayMs: Long,
        private val action: () -> Unit,
    ) : SourceSeparationIndependentRunDeadlineCancellation {
        var canceled = false
            private set

        override fun cancel() {
            canceled = true
        }

        fun run() {
            if (!canceled) action()
        }
    }
}
