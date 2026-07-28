package com.mardous.booming.separation.process

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class SourceSeparationProcessingWakeLockLifecycle {
    @SerialName("held")
    Held,

    @SerialName("released")
    Released,
}

@Serializable
enum class SourceSeparationProcessingWakeLockAction {
    @SerialName("acquire")
    Acquire,

    @SerialName("renew")
    Renew,

    @SerialName("release")
    Release,
}

@Serializable
data class SourceSeparationProcessingWakeLockEvent(
    val action: SourceSeparationProcessingWakeLockAction,
    val timestampElapsedRealtimeNanos: Long,
    val timeoutMs: Long? = null,
    val reason: String? = null,
) {
    init {
        require(timestampElapsedRealtimeNanos > 0L) {
            "Processing wake-lock event timestamp is invalid."
        }
        when (action) {
            SourceSeparationProcessingWakeLockAction.Acquire,
            SourceSeparationProcessingWakeLockAction.Renew,
            -> require(timeoutMs != null && timeoutMs > 0L && reason == null) {
                "Processing wake-lock acquisition event is inconsistent."
            }
            SourceSeparationProcessingWakeLockAction.Release ->
                require(timeoutMs == null && !reason.isNullOrBlank()) {
                    "Processing wake-lock release event is inconsistent."
                }
        }
    }
}

@Serializable
data class SourceSeparationProcessingWakeLockLeaseRecord(
    val request: SourceSeparationForegroundLeaseRequest,
    val tag: String,
    val lifecycle: SourceSeparationProcessingWakeLockLifecycle,
    val acquiredAtElapsedRealtimeNanos: Long,
    val expiresAtElapsedRealtimeNanos: Long,
    val releasedAtElapsedRealtimeNanos: Long? = null,
    val releaseReason: String? = null,
    val events: List<SourceSeparationProcessingWakeLockEvent>,
) {
    init {
        require(tag.isNotBlank()) { "Processing wake-lock tag is empty." }
        require(acquiredAtElapsedRealtimeNanos > 0L &&
            expiresAtElapsedRealtimeNanos > acquiredAtElapsedRealtimeNanos
        ) { "Processing wake-lock acquisition interval is invalid." }
        require(events.isNotEmpty() &&
            events.first().action == SourceSeparationProcessingWakeLockAction.Acquire
        ) { "Processing wake-lock history has no acquisition event." }
        require(events.zipWithNext().all { (previous, next) ->
            next.timestampElapsedRealtimeNanos >= previous.timestampElapsedRealtimeNanos
        }) { "Processing wake-lock events are out of order." }
        require(events.last().timestampElapsedRealtimeNanos <=
            (releasedAtElapsedRealtimeNanos ?: expiresAtElapsedRealtimeNanos)
        ) { "Processing wake-lock event exceeds its recorded lifetime." }
        require((lifecycle == SourceSeparationProcessingWakeLockLifecycle.Released) ==
            (releasedAtElapsedRealtimeNanos != null && !releaseReason.isNullOrBlank())
        ) { "Processing wake-lock terminal state is inconsistent." }
        require((events.last().action == SourceSeparationProcessingWakeLockAction.Release) ==
            (lifecycle == SourceSeparationProcessingWakeLockLifecycle.Released)
        ) { "Processing wake-lock terminal event is inconsistent." }
    }
}

@Serializable
data class SourceSeparationProcessingWakeLockDiagnostics(
    val activeLease: SourceSeparationProcessingWakeLockLeaseRecord? = null,
    val lastReleasedLease: SourceSeparationProcessingWakeLockLeaseRecord? = null,
    val platformHeld: Boolean = false,
) {
    init {
        require(activeLease?.lifecycle != SourceSeparationProcessingWakeLockLifecycle.Released) {
            "Processing wake-lock diagnostics expose a released lease as active."
        }
        require(lastReleasedLease?.lifecycle !=
            SourceSeparationProcessingWakeLockLifecycle.Held
        ) { "Processing wake-lock diagnostics expose a held lease as released." }
        require(!platformHeld || activeLease != null) {
            "The platform processing wake lock has no active owner."
        }
    }
}

internal enum class SourceSeparationProcessingWakeLockOperationResult {
    Applied,
    AlreadyApplied,
    NoActiveLease,
    StaleRun,
    StaleGeneration,
    StaleLease,
}

internal class SourceSeparationProcessingWakeLockTracker(
    private val elapsedRealtimeNanos: () -> Long,
) {
    private var activeLease: SourceSeparationProcessingWakeLockLeaseRecord? = null
    private var lastReleasedLease: SourceSeparationProcessingWakeLockLeaseRecord? = null

    @Synchronized
    fun acquire(
        request: SourceSeparationForegroundLeaseRequest,
        tag: String,
        timeoutMs: Long,
    ): SourceSeparationProcessingWakeLockOperationResult {
        activeLease?.let { existing ->
            require(existing.request == request) {
                "A different processing wake-lock lease is already active."
            }
            require(existing.tag == tag) { "Processing wake-lock tag changed during a lease." }
            return SourceSeparationProcessingWakeLockOperationResult.AlreadyApplied
        }
        lastReleasedLease?.let { released ->
            if (released.request == request) {
                require(released.tag == tag) {
                    "Processing wake-lock tag changed for a released lease."
                }
                return SourceSeparationProcessingWakeLockOperationResult.AlreadyApplied
            }
            require(released.request.leaseId != request.leaseId) {
                "A released processing wake-lock lease ID cannot be reused."
            }
        }
        val timestamp = now()
        val expiresAt = expiresAt(timestamp, timeoutMs)
        activeLease = SourceSeparationProcessingWakeLockLeaseRecord(
            request = request,
            tag = tag,
            lifecycle = SourceSeparationProcessingWakeLockLifecycle.Held,
            acquiredAtElapsedRealtimeNanos = timestamp,
            expiresAtElapsedRealtimeNanos = expiresAt,
            events = listOf(
                SourceSeparationProcessingWakeLockEvent(
                    action = SourceSeparationProcessingWakeLockAction.Acquire,
                    timestampElapsedRealtimeNanos = timestamp,
                    timeoutMs = timeoutMs,
                )
            ),
        )
        return SourceSeparationProcessingWakeLockOperationResult.Applied
    }

    @Synchronized
    fun renew(
        request: SourceSeparationForegroundLeaseRequest,
        timeoutMs: Long,
    ): SourceSeparationProcessingWakeLockOperationResult {
        val existing = activeLease ?: return releasedResult(request)
        requireExact(existing.request, request)?.let { return it }
        val timestamp = now()
        activeLease = existing.copy(
            expiresAtElapsedRealtimeNanos = expiresAt(timestamp, timeoutMs),
            events = existing.events + SourceSeparationProcessingWakeLockEvent(
                action = SourceSeparationProcessingWakeLockAction.Renew,
                timestampElapsedRealtimeNanos = timestamp,
                timeoutMs = timeoutMs,
            ),
        )
        return SourceSeparationProcessingWakeLockOperationResult.Applied
    }

    @Synchronized
    fun release(
        request: SourceSeparationForegroundLeaseRequest,
        reason: String,
    ): SourceSeparationProcessingWakeLockOperationResult {
        require(reason.isNotBlank()) { "Processing wake-lock release reason is empty." }
        val existing = activeLease ?: return releasedResult(request)
        requireExact(existing.request, request)?.let { return it }
        val timestamp = now()
        val released = existing.copy(
            lifecycle = SourceSeparationProcessingWakeLockLifecycle.Released,
            releasedAtElapsedRealtimeNanos = timestamp,
            releaseReason = reason,
            events = existing.events + SourceSeparationProcessingWakeLockEvent(
                action = SourceSeparationProcessingWakeLockAction.Release,
                timestampElapsedRealtimeNanos = timestamp,
                reason = reason,
            ),
        )
        activeLease = null
        lastReleasedLease = released
        return SourceSeparationProcessingWakeLockOperationResult.Applied
    }

    @Synchronized
    fun diagnostics(
        platformHeld: Boolean = activeLease != null,
    ) = SourceSeparationProcessingWakeLockDiagnostics(
        activeLease = activeLease,
        lastReleasedLease = lastReleasedLease,
        platformHeld = platformHeld,
    )

    private fun releasedResult(
        request: SourceSeparationForegroundLeaseRequest,
    ): SourceSeparationProcessingWakeLockOperationResult {
        val released = lastReleasedLease
            ?: return SourceSeparationProcessingWakeLockOperationResult.NoActiveLease
        return requireExact(released.request, request)
            ?: SourceSeparationProcessingWakeLockOperationResult.AlreadyApplied
    }

    private fun requireExact(
        existing: SourceSeparationForegroundLeaseRequest,
        incoming: SourceSeparationForegroundLeaseRequest,
    ): SourceSeparationProcessingWakeLockOperationResult? = when {
        existing.processGeneration != incoming.processGeneration ->
            SourceSeparationProcessingWakeLockOperationResult.StaleGeneration
        existing.runId != incoming.runId ->
            SourceSeparationProcessingWakeLockOperationResult.StaleRun
        existing.leaseId != incoming.leaseId ->
            SourceSeparationProcessingWakeLockOperationResult.StaleLease
        else -> {
            require(existing.displayName == incoming.displayName) {
                "Processing wake-lock display name changed for the same identity."
            }
            null
        }
    }

    private fun expiresAt(timestamp: Long, timeoutMs: Long): Long {
        require(timeoutMs > 0L) { "Processing wake-lock timeout is invalid." }
        return Math.addExact(timestamp, Math.multiplyExact(timeoutMs, NANOS_PER_MILLISECOND))
    }

    private fun now(): Long = elapsedRealtimeNanos().coerceAtLeast(1L)

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
