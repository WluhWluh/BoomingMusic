package com.mardous.booming.separation.process

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SourceSeparationForegroundLeaseRequest(
    val leaseId: String,
    val runId: String,
    val processGeneration: Long,
    val displayName: String,
) {
    init {
        require(LEASE_ID_PATTERN.matches(leaseId)) {
            "Foreground lease ID is invalid."
        }
        require(runId.isNotBlank()) { "Foreground lease run ID is empty." }
        require(processGeneration > 0L) { "Foreground lease generation is invalid." }
        require(displayName.isNotBlank()) { "Foreground lease display name is empty." }
    }

    companion object {
        private val LEASE_ID_PATTERN = Regex("^[A-Za-z0-9._-]{16,128}$")
    }
}

@Serializable
enum class SourceSeparationForegroundPlatformPolicy {
    @SerialName("legacy-media-processing")
    LegacyMediaProcessing,

    @SerialName("timed-media-processing")
    TimedMediaProcessing,
}

@Serializable
enum class SourceSeparationForegroundLeaseLifecycle {
    @SerialName("awaiting-run")
    AwaitingRun,

    @SerialName("active")
    Active,

    @SerialName("stopped")
    Stopped,
}

@Serializable
enum class SourceSeparationForegroundControlAction {
    Pause,
    Cancel,
}

@Serializable
data class SourceSeparationForegroundControlRecord(
    val commandId: String,
    val action: SourceSeparationForegroundControlAction,
    val timestampElapsedRealtimeNanos: Long,
) {
    init {
        require(commandId.isNotBlank()) { "Foreground control command ID is empty." }
        require(timestampElapsedRealtimeNanos > 0L) {
            "Foreground control timestamp is invalid."
        }
    }
}

@Serializable
data class SourceSeparationForegroundLeaseRecord(
    val request: SourceSeparationForegroundLeaseRequest,
    val lifecycle: SourceSeparationForegroundLeaseLifecycle,
    val notificationId: Int,
    val platformPolicy: SourceSeparationForegroundPlatformPolicy,
    val startedAtElapsedRealtimeNanos: Long,
    val attachedAtElapsedRealtimeNanos: Long? = null,
    val stoppedAtElapsedRealtimeNanos: Long? = null,
    val stopReason: String? = null,
    val controls: List<SourceSeparationForegroundControlRecord> = emptyList(),
) {
    init {
        require(notificationId > 0) { "Foreground notification ID is invalid." }
        require(startedAtElapsedRealtimeNanos > 0L) {
            "Foreground start timestamp is invalid."
        }
        require(attachedAtElapsedRealtimeNanos == null ||
            attachedAtElapsedRealtimeNanos >= startedAtElapsedRealtimeNanos
        ) { "Foreground attachment timestamp is invalid." }
        require(stoppedAtElapsedRealtimeNanos == null ||
            stoppedAtElapsedRealtimeNanos >= startedAtElapsedRealtimeNanos
        ) { "Foreground stop timestamp is invalid." }
        require((lifecycle == SourceSeparationForegroundLeaseLifecycle.Stopped) ==
            (stoppedAtElapsedRealtimeNanos != null && !stopReason.isNullOrBlank())
        ) { "Foreground terminal state is inconsistent." }
        require(lifecycle != SourceSeparationForegroundLeaseLifecycle.Active ||
            attachedAtElapsedRealtimeNanos != null
        ) { "An active foreground lease was not attached to a run." }
        require(controls.map { it.commandId }.distinct().size == controls.size) {
            "Foreground lease contains duplicate control commands."
        }
    }
}

@Serializable
data class SourceSeparationForegroundServiceDiagnostics(
    val activeLease: SourceSeparationForegroundLeaseRecord? = null,
    val lastStoppedLease: SourceSeparationForegroundLeaseRecord? = null,
) {
    init {
        require(activeLease?.lifecycle != SourceSeparationForegroundLeaseLifecycle.Stopped) {
            "Foreground diagnostics expose a stopped lease as active."
        }
        require(lastStoppedLease?.lifecycle !=
            SourceSeparationForegroundLeaseLifecycle.AwaitingRun &&
            lastStoppedLease?.lifecycle != SourceSeparationForegroundLeaseLifecycle.Active
        ) { "Foreground diagnostics expose a live lease as stopped." }
    }
}

internal enum class SourceSeparationForegroundLeaseOperationResult {
    Applied,
    AlreadyApplied,
    NoActiveLease,
    StaleRun,
    StaleGeneration,
    StaleLease,
}

internal class SourceSeparationForegroundLeaseTracker(
    private val elapsedRealtimeNanos: () -> Long,
) {
    private var activeLease: SourceSeparationForegroundLeaseRecord? = null
    private var lastStoppedLease: SourceSeparationForegroundLeaseRecord? = null

    @Synchronized
    fun started(
        request: SourceSeparationForegroundLeaseRequest,
        notificationId: Int,
        platformPolicy: SourceSeparationForegroundPlatformPolicy,
    ): SourceSeparationForegroundLeaseOperationResult {
        activeLease?.let { existing ->
            require(existing.request == request) {
                "A different foreground lease is already active."
            }
            return SourceSeparationForegroundLeaseOperationResult.AlreadyApplied
        }
        lastStoppedLease?.let { stopped ->
            if (stopped.request == request) {
                return SourceSeparationForegroundLeaseOperationResult.AlreadyApplied
            }
            require(stopped.request.leaseId != request.leaseId) {
                "A stopped foreground lease ID cannot be reused."
            }
        }
        activeLease = SourceSeparationForegroundLeaseRecord(
            request = request,
            lifecycle = SourceSeparationForegroundLeaseLifecycle.AwaitingRun,
            notificationId = notificationId,
            platformPolicy = platformPolicy,
            startedAtElapsedRealtimeNanos = now(),
        )
        return SourceSeparationForegroundLeaseOperationResult.Applied
    }

    @Synchronized
    fun attach(
        request: SourceSeparationForegroundLeaseRequest,
    ): SourceSeparationForegroundLeaseOperationResult {
        val existing = activeLease ?: return SourceSeparationForegroundLeaseOperationResult
            .NoActiveLease
        requireExact(existing.request, request)?.let { return it }
        if (existing.lifecycle == SourceSeparationForegroundLeaseLifecycle.Active) {
            return SourceSeparationForegroundLeaseOperationResult.AlreadyApplied
        }
        activeLease = existing.copy(
            lifecycle = SourceSeparationForegroundLeaseLifecycle.Active,
            attachedAtElapsedRealtimeNanos = now(),
        )
        return SourceSeparationForegroundLeaseOperationResult.Applied
    }

    @Synchronized
    fun control(
        request: SourceSeparationForegroundLeaseRequest,
        commandId: String,
        action: SourceSeparationForegroundControlAction,
    ): SourceSeparationForegroundLeaseOperationResult {
        require(commandId.isNotBlank()) { "Foreground control command ID is empty." }
        val existing = activeLease
            ?: return stoppedControlResult(request, commandId)
        requireExact(existing.request, request)?.let { return it }
        if (existing.controls.any { it.commandId == commandId }) {
            return SourceSeparationForegroundLeaseOperationResult.AlreadyApplied
        }
        activeLease = existing.copy(
            controls = existing.controls + SourceSeparationForegroundControlRecord(
                commandId = commandId,
                action = action,
                timestampElapsedRealtimeNanos = now(),
            ),
        )
        return SourceSeparationForegroundLeaseOperationResult.Applied
    }

    @Synchronized
    fun stop(
        request: SourceSeparationForegroundLeaseRequest,
        reason: String,
    ): SourceSeparationForegroundLeaseOperationResult {
        require(reason.isNotBlank()) { "Foreground stop reason is empty." }
        val existing = activeLease ?: return lastStoppedLease?.let { stopped ->
            requireExact(stopped.request, request)
                ?: SourceSeparationForegroundLeaseOperationResult.AlreadyApplied
        } ?: SourceSeparationForegroundLeaseOperationResult.NoActiveLease
        requireExact(existing.request, request)?.let { return it }
        val stopped = existing.copy(
            lifecycle = SourceSeparationForegroundLeaseLifecycle.Stopped,
            stoppedAtElapsedRealtimeNanos = now(),
            stopReason = reason,
        )
        activeLease = null
        lastStoppedLease = stopped
        return SourceSeparationForegroundLeaseOperationResult.Applied
    }

    @Synchronized
    fun diagnostics() = SourceSeparationForegroundServiceDiagnostics(
        activeLease = activeLease,
        lastStoppedLease = lastStoppedLease,
    )

    private fun stoppedControlResult(
        request: SourceSeparationForegroundLeaseRequest,
        commandId: String,
    ): SourceSeparationForegroundLeaseOperationResult {
        val stopped = lastStoppedLease
            ?: return SourceSeparationForegroundLeaseOperationResult.NoActiveLease
        requireExact(stopped.request, request)?.let { return it }
        return if (stopped.controls.any { it.commandId == commandId }) {
            SourceSeparationForegroundLeaseOperationResult.AlreadyApplied
        } else {
            SourceSeparationForegroundLeaseOperationResult.NoActiveLease
        }
    }

    private fun requireExact(
        existing: SourceSeparationForegroundLeaseRequest,
        incoming: SourceSeparationForegroundLeaseRequest,
    ): SourceSeparationForegroundLeaseOperationResult? = when {
        existing.processGeneration != incoming.processGeneration ->
            SourceSeparationForegroundLeaseOperationResult.StaleGeneration
        existing.runId != incoming.runId ->
            SourceSeparationForegroundLeaseOperationResult.StaleRun
        existing.leaseId != incoming.leaseId ->
            SourceSeparationForegroundLeaseOperationResult.StaleLease
        else -> {
            require(existing.displayName == incoming.displayName) {
                "Foreground lease display name changed for the same identity."
            }
            null
        }
    }

    private fun now(): Long = elapsedRealtimeNanos().coerceAtLeast(1L)
}
