package com.mardous.booming.separation.process

import com.mardous.booming.separation.SourceSeparationBackgroundPolicy
import com.mardous.booming.separation.SourceSeparationExecutionRunClass

internal data class SourceSeparationIndependentRunAuthorityEvidence(
    val runId: String,
    val processGeneration: Long,
    val displayName: String,
    val runClass: SourceSeparationExecutionRunClass,
    val backgroundPolicy: SourceSeparationBackgroundPolicy,
    val hostSnapshotAvailable: Boolean,
    val foregroundLease: SourceSeparationForegroundLeaseRecord?,
    val wakeLockLease: SourceSeparationProcessingWakeLockLeaseRecord?,
    val platformWakeLockHeld: Boolean,
)

internal object SourceSeparationIndependentRunAuthorityPolicy {
    fun isEstablished(evidence: SourceSeparationIndependentRunAuthorityEvidence): Boolean {
        if (evidence.runClass != SourceSeparationExecutionRunClass.ManualFullSong ||
            evidence.backgroundPolicy !=
            SourceSeparationBackgroundPolicy.IndependentForegroundEligible ||
            !evidence.hostSnapshotAvailable ||
            evidence.foregroundLease?.lifecycle != SourceSeparationForegroundLeaseLifecycle.Active ||
            !evidence.platformWakeLockHeld
        ) {
            return false
        }
        return evidence.foregroundLease.request.matches(evidence) &&
            evidence.wakeLockLease?.request?.matches(evidence) == true
    }

    private fun SourceSeparationForegroundLeaseRequest.matches(
        evidence: SourceSeparationIndependentRunAuthorityEvidence,
    ): Boolean = runId == evidence.runId &&
        processGeneration == evidence.processGeneration &&
        displayName == evidence.displayName
}
