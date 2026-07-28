package com.mardous.booming.separation.process

import com.mardous.booming.separation.SourceSeparationPausedException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal const val SOURCE_SEPARATION_MEDIA_PROCESSING_FOREGROUND_SERVICE_TYPE = 0x2000

internal fun isSourceSeparationMediaProcessingForegroundServiceType(type: Int): Boolean =
    type == SOURCE_SEPARATION_MEDIA_PROCESSING_FOREGROUND_SERVICE_TYPE

@Serializable
enum class SourceSeparationForegroundDeferredReason {
    @SerialName("start-not-allowed")
    StartNotAllowed,

    @SerialName("promotion-denied")
    PromotionDenied,

    @SerialName("timed-out")
    TimedOut,
}

class SourceSeparationForegroundExecutionDeferredException(
    val reason: SourceSeparationForegroundDeferredReason,
    cause: Throwable? = null,
) : SourceSeparationPausedException(
    message = "Source separation foreground execution was deferred: ${reason.name}.",
    cause = cause,
)

internal enum class SourceSeparationForegroundStartStage {
    ServiceStart,
    Promotion,
}

internal fun Throwable.toForegroundExecutionDeferredException(
    stage: SourceSeparationForegroundStartStage,
): SourceSeparationForegroundExecutionDeferredException? {
    if (this is SourceSeparationForegroundExecutionDeferredException) return this
    val isPlatformStartDenial = javaClass.name ==
        "android.app.ForegroundServiceStartNotAllowedException"
    val reason = when {
        isPlatformStartDenial -> SourceSeparationForegroundDeferredReason.StartNotAllowed
        stage == SourceSeparationForegroundStartStage.ServiceStart &&
            this is IllegalStateException ->
            SourceSeparationForegroundDeferredReason.StartNotAllowed
        this is SecurityException -> SourceSeparationForegroundDeferredReason.PromotionDenied
        stage == SourceSeparationForegroundStartStage.Promotion &&
            (this is IllegalStateException || this is IllegalArgumentException) ->
            SourceSeparationForegroundDeferredReason.PromotionDenied
        else -> return null
    }
    return SourceSeparationForegroundExecutionDeferredException(reason, this)
}

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
data class SourceSeparationForegroundTimeoutRecord(
    val startId: Int,
    val foregroundServiceType: Int,
    val timestampElapsedRealtimeNanos: Long,
) {
    init {
        require(startId > 0) { "Foreground timeout start ID is invalid." }
        require(foregroundServiceType > 0) {
            "Foreground timeout service type is invalid."
        }
        require(timestampElapsedRealtimeNanos > 0L) {
            "Foreground timeout timestamp is invalid."
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
    val timeout: SourceSeparationForegroundTimeoutRecord? = null,
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
        require(timeout == null ||
            (lifecycle == SourceSeparationForegroundLeaseLifecycle.Stopped &&
                stoppedAtElapsedRealtimeNanos == timeout.timestampElapsedRealtimeNanos)
        ) { "Foreground timeout record is inconsistent." }
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
            ?: return stoppedControlResult(request, commandId, action)
        requireExact(existing.request, request)?.let { return it }
        existing.controls.firstOrNull { it.commandId == commandId }?.let { previous ->
            require(previous.action == action) {
                "Foreground control command ID was reused for another action."
            }
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
    fun timedOut(
        request: SourceSeparationForegroundLeaseRequest,
        startId: Int,
        foregroundServiceType: Int,
    ): SourceSeparationForegroundLeaseOperationResult {
        val existing = activeLease ?: return lastStoppedLease?.let { stopped ->
            requireExact(stopped.request, request)
                ?: if (stopped.timeout != null) {
                    SourceSeparationForegroundLeaseOperationResult.AlreadyApplied
                } else {
                    SourceSeparationForegroundLeaseOperationResult.NoActiveLease
                }
        } ?: SourceSeparationForegroundLeaseOperationResult.NoActiveLease
        requireExact(existing.request, request)?.let { return it }
        val timeout = SourceSeparationForegroundTimeoutRecord(
            startId = startId,
            foregroundServiceType = foregroundServiceType,
            timestampElapsedRealtimeNanos = now(),
        )
        val stopped = existing.copy(
            lifecycle = SourceSeparationForegroundLeaseLifecycle.Stopped,
            stoppedAtElapsedRealtimeNanos = timeout.timestampElapsedRealtimeNanos,
            stopReason = "media-processing-timeout",
            timeout = timeout,
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
        action: SourceSeparationForegroundControlAction,
    ): SourceSeparationForegroundLeaseOperationResult {
        val stopped = lastStoppedLease
            ?: return SourceSeparationForegroundLeaseOperationResult.NoActiveLease
        requireExact(stopped.request, request)?.let { return it }
        val previous = stopped.controls.firstOrNull { it.commandId == commandId }
            ?: return SourceSeparationForegroundLeaseOperationResult.NoActiveLease
        require(previous.action == action) {
            "Foreground control command ID was reused for another action."
        }
        return SourceSeparationForegroundLeaseOperationResult.AlreadyApplied
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
