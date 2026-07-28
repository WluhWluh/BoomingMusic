package com.mardous.booming.separation.process

import com.mardous.booming.separation.SourceSeparationBackgroundPolicy
import com.mardous.booming.separation.SourceSeparationExecutionRunClass
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
}
