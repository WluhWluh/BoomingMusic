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
    const val MAX_UNOBSERVED_CONTINUATION_MS = 5L * 60L * 60L * 1_000L +
            45L * 60L * 1_000L

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

internal data class SourceSeparationIndependentRunIdentity(
    val runId: String,
    val processGeneration: Long,
) {
    init {
        require(runId.isNotBlank()) { "Independent run ID is empty." }
        require(processGeneration > 0L) { "Independent run generation is invalid." }
    }
}

internal fun interface SourceSeparationIndependentRunDeadlineCancellation {
    fun cancel()
}

internal fun interface SourceSeparationIndependentRunDeadlineScheduler {
    fun schedule(
        delayMs: Long,
        action: () -> Unit,
    ): SourceSeparationIndependentRunDeadlineCancellation
}

internal class SourceSeparationIndependentRunObserverDeadline(
    private val scheduler: SourceSeparationIndependentRunDeadlineScheduler,
    private val timeoutMs: Long =
        SourceSeparationIndependentRunAuthorityPolicy.MAX_UNOBSERVED_CONTINUATION_MS,
    private val onExpired: (SourceSeparationIndependentRunIdentity) -> Unit,
) : AutoCloseable {
    private val lock = Any()
    private var pending: PendingDeadline? = null
    private var closed = false

    init {
        require(timeoutMs > 0L) { "Independent observer timeout is invalid." }
    }

    fun observerDisconnected(identity: SourceSeparationIndependentRunIdentity) {
        synchronized(lock) {
            if (closed) return
            pending?.cancellation?.cancel()
            val token = Any()
            val cancellation = scheduler.schedule(timeoutMs) {
                expire(identity, token)
            }
            pending = PendingDeadline(identity, token, cancellation)
        }
    }

    fun observerConnected(identity: SourceSeparationIndependentRunIdentity) {
        clear(identity)
    }

    fun runClosed(identity: SourceSeparationIndependentRunIdentity) {
        clear(identity)
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            pending?.cancellation?.cancel()
            pending = null
        }
    }

    private fun clear(identity: SourceSeparationIndependentRunIdentity) {
        synchronized(lock) {
            val current = pending?.takeIf { it.identity == identity } ?: return
            current.cancellation.cancel()
            pending = null
        }
    }

    private fun expire(
        identity: SourceSeparationIndependentRunIdentity,
        token: Any,
    ) {
        val shouldExpire = synchronized(lock) {
            val current = pending
            if (closed || current?.identity != identity || current.token !== token) {
                false
            } else {
                pending = null
                true
            }
        }
        if (shouldExpire) onExpired(identity)
    }

    private data class PendingDeadline(
        val identity: SourceSeparationIndependentRunIdentity,
        val token: Any,
        val cancellation: SourceSeparationIndependentRunDeadlineCancellation,
    )
}
