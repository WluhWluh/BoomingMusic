package com.mardous.booming.separation.process.ipc

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.mardous.booming.AppProcessResolver
import com.mardous.booming.separation.SourceSeparationBackgroundPolicy
import com.mardous.booming.separation.SourceSeparationExecutionRunClass
import com.mardous.booming.separation.SourceSeparationPausedException
import com.mardous.booming.separation.SourceSeparationPauseReason
import com.mardous.booming.separation.SourceSeparationModelFamily
import com.mardous.booming.separation.cache.v2.SourceSeparationExactCacheModelException
import com.mardous.booming.separation.model.preset.SourceSeparationPresetRepository
import com.mardous.booming.separation.process.InProcessSourceSeparationExecutionHost
import com.mardous.booming.separation.process.SourceSeparationExecutionHostControlResult
import com.mardous.booming.separation.process.SourceSeparationExecutionHostMode
import com.mardous.booming.separation.process.SourceSeparationExecutionHostRequest
import com.mardous.booming.separation.process.SourceSeparationForegroundControlAction
import com.mardous.booming.separation.process.SourceSeparationForegroundDeferredReason
import com.mardous.booming.separation.process.SourceSeparationForegroundExecutionDeferredException
import com.mardous.booming.separation.process.SourceSeparationForegroundLeaseLifecycle
import com.mardous.booming.separation.process.SourceSeparationForegroundLeaseOperationResult
import com.mardous.booming.separation.process.SourceSeparationForegroundLeaseRequest
import com.mardous.booming.separation.process.SourceSeparationIndependentRunAuthorityEvidence
import com.mardous.booming.separation.process.SourceSeparationIndependentRunAuthorityPolicy
import com.mardous.booming.separation.process.SourceSeparationIndependentRunDeadlineCancellation
import com.mardous.booming.separation.process.SourceSeparationIndependentRunDeadlineScheduler
import com.mardous.booming.separation.process.SourceSeparationIndependentRunIdentity
import com.mardous.booming.separation.process.SourceSeparationIndependentRunObserverDeadline
import com.mardous.booming.separation.process.SourceSeparationProcessDiagnostics
import com.mardous.booming.separation.process.SourceSeparationProcessDiagnosticsCollector
import com.mardous.booming.separation.process.SourceSeparationProcessLifecyclePolicy
import com.mardous.booming.separation.process.SourceSeparationProcessSessionDiagnostics
import com.mardous.booming.separation.process.SourceSeparationProcessSessionRecycleRequiredException
import com.mardous.booming.separation.process.SourceSeparationProcessSessionPoisonedException
import com.mardous.booming.separation.process.SourceSeparationProcessExecutionAuthority
import com.mardous.booming.separation.process.SourceSeparationProcessExecutionBusyException
import com.mardous.booming.separation.process.SourceSeparationProcessExecutionLease
import com.mardous.booming.separation.process.SourceSeparationProcessExecutionLifetime
import com.mardous.booming.separation.process.SourceSeparationProcessExecutionOwner
import com.mardous.booming.separation.process.SourceSeparationProcessingWakeLockOperationResult
import com.mardous.booming.separation.process.SourceSeparationRemoteCacheUnavailableException
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheLostException
import com.mardous.booming.separation.process.SourceSeparationRemoteExecutionControl
import com.mardous.booming.separation.process.SourceSeparationRemoteExecutionEnvironment
import com.mardous.booming.separation.process.SourceSeparationRemoteSourceUnavailableException
import com.mardous.booming.separation.process.SourceSeparationResidentProcessValidation
import com.mardous.booming.separation.process.toExecutionCompletion
import java.util.LinkedHashSet
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.koin.android.ext.android.inject

internal class SourceSeparationExecutionService : Service() {
    private val presetRepository: SourceSeparationPresetRepository by inject()
    private val stateLock = Any()
    private val destroyed = AtomicBoolean(false)
    private val environmentLock = Any()
    private val processGeneration by lazy(::createProcessGeneration)
    private val commandLedger = SourceSeparationIpcCommandLedger()
    private var executionEnvironment: SourceSeparationRemoteExecutionEnvironment? = null
    private var client: ConnectedClient? = null
    private var activeRun: ActiveRemoteRun? = null
    private var recycleAcknowledgement: SourceSeparationIpcRecycleAcknowledgement? = null
    private val retentionBinder = Binder()
    private val observerDeadlineHandler = Handler(Looper.getMainLooper())
    private val observerDeadline = SourceSeparationIndependentRunObserverDeadline(
        scheduler = SourceSeparationIndependentRunDeadlineScheduler { delayMs, action ->
            val runnable = Runnable(action)
            check(observerDeadlineHandler.postDelayed(runnable, delayMs)) {
                "Unable to schedule the independent observer deadline."
            }
            SourceSeparationIndependentRunDeadlineCancellation {
                observerDeadlineHandler.removeCallbacks(runnable)
            }
        },
        onExpired = ::handleIndependentObserverDeadline,
    )
    private val foregroundController by lazy {
        SourceSeparationMediaProcessingForegroundController(this)
    }
    private val processingWakeLockController by lazy {
        SourceSeparationProcessingWakeLockController(this) { request, reason ->
            Log.e(TAG, "Processing wake-lock lease lost: $reason")
            synchronized(stateLock) {
                activeRun
                    ?.takeIf { active ->
                        active.descriptor.runId == request.runId &&
                            active.descriptor.processGeneration == request.processGeneration
                    }
                    ?.requestPause()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val process = AppProcessResolver.resolve(this)
        check(process.isSourceSeparationProcess) {
            "Source-separation execution service started in the wrong process."
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        return when (intent?.action) {
            ACTION_BIND -> {
                binder
            }
            ACTION_RETAIN_WITHOUT_CLIENT -> {
                check(SourceSeparationResidentProcessValidation.buildEnabled) {
                    "Remote warm retention requires a resident validation build."
                }
                retentionBinder
            }
            else -> error(
                "Source-separation execution service requires an explicit bind action.",
            )
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (intent?.action != ACTION_BIND) return false
        synchronized(stateLock) {
            handleClientLossLocked("service-unbound")
        }
        return false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        foregroundController.observeStartCommand(startId)
        when (intent?.action) {
            ACTION_START_MEDIA_PROCESSING -> handleForegroundStart(intent, startId)
            ACTION_PAUSE_MEDIA_PROCESSING -> handleForegroundControl(
                intent,
                startId,
                SourceSeparationForegroundControlAction.Pause,
            )
            ACTION_CANCEL_MEDIA_PROCESSING -> handleForegroundControl(
                intent,
                startId,
                SourceSeparationForegroundControlAction.Cancel,
            )
            else -> foregroundController.releaseUnownedStart(startId)
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        val request = foregroundController.onTimeout(startId, fgsType) ?: return
        Log.w(TAG, "Media-processing foreground lifetime timed out: ${request.runId}")
        synchronized(stateLock) {
            activeRun
                ?.takeIf { active ->
                    active.descriptor.runId == request.runId &&
                        active.descriptor.processGeneration == request.processGeneration
                }
                ?.requestForegroundDeferral(
                    SourceSeparationForegroundDeferredReason.TimedOut,
                )
        }
        processingWakeLockController.release(request, "foreground-timeout")
    }

    override fun onDestroy() {
        destroyed.set(true)
        observerDeadline.close()
        processingWakeLockController.releaseActive("service-destroyed")
        foregroundController.stopActive("service-destroyed")
        val closeEnvironmentNow = synchronized(stateLock) {
            activeRun?.requestCancel()
            unlinkClientDeathLocked()
            client = null
            closeFinishedRunLocked()
            activeRun == null && recycleAcknowledgement == null
        }
        if (closeEnvironmentNow) closeExecutionEnvironment()
        super.onDestroy()
    }

    private val binder = object : ISourceSeparationExecutionService.Stub() {
        override fun connect(
            requestJson: String,
            callback: ISourceSeparationExecutionCallback,
        ): String {
            requireSameUidCaller()
            val request = SourceSeparationExecutionIpcCodec.decodeConnectRequest(requestJson)
            val activeState = synchronized(stateLock) {
                require(commandLedger.record(request.commandId)) {
                    "Duplicate IPC connect command."
                }
                val active = activeRun
                if (active != null) {
                    check(client == null && hasIndependentAuthorityLocked(active)) {
                        "The IPC observer cannot replace an active client-bound owner."
                    }
                }
                val connected = replaceClientLocked(
                    observerId = request.observerId,
                    clientProcessName = request.clientProcessName,
                    callback = callback,
                )
                try {
                    active?.attachObserver(connected)
                    active?.let { run ->
                        observerDeadline.observerConnected(run.identity)
                    }
                } catch (error: Throwable) {
                    unlinkClientDeathLocked()
                    client = null
                    throw error
                }
                active?.ipcState()
            }
            val process = AppProcessResolver.resolve(this@SourceSeparationExecutionService)
            val diagnostics = captureProcessDiagnostics(process.processName)
            return SourceSeparationExecutionIpcCodec.encodeConnectResponse(
                SourceSeparationIpcConnectResponse(
                    commandId = request.commandId,
                    processGeneration = processGeneration,
                    processName = process.processName,
                    pid = Process.myPid(),
                    idlePssBytes = diagnostics.memory.pssBytes,
                    diagnostics = diagnostics,
                    activeRun = activeState,
                )
            )
        }

        override fun start(
            requestJson: String,
            observerId: String,
            clientProcessName: String,
            callback: ISourceSeparationExecutionCallback,
        ): String {
            requireSameUidCaller()
            val command = try {
                SourceSeparationExecutionIpcCodec.decodeStartCommand(requestJson)
            } catch (error: Throwable) {
                return rejectedStartResponse("malformed", error)
            }
            val active = try {
                reserveRun(
                    command = command,
                    observerId = observerId,
                    clientProcessName = clientProcessName,
                    callback = callback,
                )
            } catch (error: Throwable) {
                command.foregroundLease?.let { lease ->
                    foregroundController.stop(lease, "start-rejected")
                }
                return rejectedStartResponse(command.commandId, error)
            }
            return executeRun(command, active)
        }

        override fun updateControl(requestJson: String): String {
            requireSameUidCaller()
            val command = try {
                SourceSeparationExecutionIpcCodec.decodeControlCommand(requestJson)
            } catch (error: Throwable) {
                return rejectedOperationResponse("malformed", error)
            }
            return synchronized(stateLock) {
                if (!commandLedger.record(command.commandId)) {
                    return@synchronized operationResponse(
                        command.commandId,
                        SourceSeparationIpcStatus.Duplicate,
                    )
                }
                val active = activeRun
                    ?: return@synchronized operationResponse(
                        command.commandId,
                        SourceSeparationIpcStatus.NoActiveRun,
                    )
                if (command.processGeneration != processGeneration) {
                    return@synchronized operationResponse(
                        command.commandId,
                        SourceSeparationIpcStatus.StaleGeneration,
                    )
                }
                if (command.runId != active.descriptor.runId) {
                    return@synchronized operationResponse(
                        command.commandId,
                        SourceSeparationIpcStatus.StaleRun,
                    )
                }
                if (command.controlSequence <= active.highestControlSequence) {
                    return@synchronized operationResponse(
                        command.commandId,
                        SourceSeparationIpcStatus.StaleControl,
                    )
                }
                active.highestControlSequence = command.controlSequence
                active.control.update(
                    hasPlaybackPositionUpdate = command.hasPlaybackPositionUpdate,
                    playbackPositionMs = command.playbackPositionMs,
                    playbackReadyWindowCount = command.playbackReadyWindowCount,
                )
                val result = when (command.action) {
                    SourceSeparationIpcControlAction.Update ->
                        SourceSeparationExecutionHostControlResult.Applied
                    SourceSeparationIpcControlAction.Pause -> {
                        val reason = requireNotNull(command.pauseReason)
                        active.control.requestPause(reason)
                        active.host.pause(
                            command.runId,
                            command.processGeneration,
                            reason,
                        )
                    }
                    SourceSeparationIpcControlAction.Cancel -> {
                        active.control.requestCancel()
                        active.host.cancel(command.runId, command.processGeneration)
                    }
                }
                operationResponse(command.commandId, result.toIpcStatus())
            }
        }

        override fun snapshot(requestJson: String): String {
            requireSameUidCaller()
            val command = try {
                SourceSeparationExecutionIpcCodec.decodeRunCommand(requestJson)
            } catch (error: Throwable) {
                return rejectedOperationResponse("malformed", error)
            }
            return synchronized(stateLock) {
                if (!commandLedger.record(command.commandId)) {
                    return@synchronized operationResponse(
                        command.commandId,
                        SourceSeparationIpcStatus.Duplicate,
                    )
                }
                if (command.processGeneration != processGeneration) {
                    return@synchronized operationResponse(
                        command.commandId,
                        SourceSeparationIpcStatus.StaleGeneration,
                    )
                }
                val active = activeRun
                    ?: return@synchronized operationResponse(
                        command.commandId,
                        SourceSeparationIpcStatus.NoActiveRun,
                    )
                if (command.runId != active.descriptor.runId) {
                    return@synchronized operationResponse(
                        command.commandId,
                        SourceSeparationIpcStatus.StaleRun,
                    )
                }
                operationResponse(
                    commandId = command.commandId,
                    status = SourceSeparationIpcStatus.Applied,
                    snapshot = active.host.snapshot(command.runId, command.processGeneration),
                )
            }
        }

        override fun diagnostics(requestJson: String): String {
            requireSameUidCaller()
            val command = try {
                SourceSeparationExecutionIpcCodec.decodeDiagnosticsCommand(requestJson)
            } catch (error: Throwable) {
                return rejectedDiagnosticsResponse("malformed", error)
            }
            val status = synchronized(stateLock) {
                when {
                    !commandLedger.record(command.commandId) ->
                        SourceSeparationIpcStatus.Duplicate
                    command.processGeneration != processGeneration ->
                        SourceSeparationIpcStatus.StaleGeneration
                    else -> SourceSeparationIpcStatus.Applied
                }
            }
            val processDiagnostics = if (status == SourceSeparationIpcStatus.Applied) {
                val processName = AppProcessResolver.resolve(
                    this@SourceSeparationExecutionService,
                ).processName
                captureProcessDiagnostics(processName)
            } else {
                null
            }
            return SourceSeparationExecutionIpcCodec.encodeDiagnosticsResponse(
                SourceSeparationIpcDiagnosticsResponse(
                    commandId = command.commandId,
                    status = status,
                    diagnostics = processDiagnostics,
                )
            )
        }

        override fun recycle(requestJson: String): String {
            requireSameUidCaller()
            val command = try {
                SourceSeparationExecutionIpcCodec.decodeRecycleCommand(requestJson)
            } catch (error: Throwable) {
                return rejectedRecycleResponse("malformed", error)
            }
            val process = AppProcessResolver.resolve(this@SourceSeparationExecutionService)
            val processDiagnostics = captureProcessDiagnostics(process.processName)
            val response = synchronized(stateLock) {
                when {
                    !commandLedger.record(command.commandId) ->
                        SourceSeparationIpcRecycleResponse(
                            commandId = command.commandId,
                            status = SourceSeparationIpcStatus.Duplicate,
                        )
                    command.processGeneration != processGeneration ->
                        SourceSeparationIpcRecycleResponse(
                            commandId = command.commandId,
                            status = SourceSeparationIpcStatus.StaleGeneration,
                        )
                    activeRun != null -> SourceSeparationIpcRecycleResponse(
                        commandId = command.commandId,
                        status = SourceSeparationIpcStatus.RunActive,
                    )
                    recycleAcknowledgement != null -> SourceSeparationIpcRecycleResponse(
                        commandId = command.commandId,
                        status = SourceSeparationIpcStatus.AlreadyApplied,
                    )
                    else -> {
                        executionEnvironment().markRecycling(
                            reason = command.reason.name,
                            token = command.recycleToken,
                        )
                        val acknowledgement = SourceSeparationIpcRecycleAcknowledgement(
                            recycleToken = command.recycleToken,
                            processGeneration = processGeneration,
                            pid = Process.myPid(),
                            processStartTicks = processDiagnostics.processStartTicks,
                        )
                        recycleAcknowledgement = acknowledgement
                        SourceSeparationIpcRecycleResponse(
                            commandId = command.commandId,
                            status = SourceSeparationIpcStatus.RecycleAccepted,
                            acknowledgement = acknowledgement,
                        )
                    }
                }
            }
            val encoded = SourceSeparationExecutionIpcCodec.encodeRecycleResponse(response)
            if (response.status == SourceSeparationIpcStatus.RecycleAccepted) {
                scheduleSelfTermination()
            }
            return encoded
        }

        override fun closeRun(requestJson: String): String {
            requireSameUidCaller()
            val command = try {
                SourceSeparationExecutionIpcCodec.decodeRunCommand(requestJson)
            } catch (error: Throwable) {
                return rejectedOperationResponse("malformed", error)
            }
            return synchronized(stateLock) {
                if (!commandLedger.record(command.commandId)) {
                    return@synchronized operationResponse(
                        command.commandId,
                        SourceSeparationIpcStatus.Duplicate,
                    )
                }
                if (command.processGeneration != processGeneration) {
                    return@synchronized operationResponse(
                        command.commandId,
                        SourceSeparationIpcStatus.StaleGeneration,
                    )
                }
                val active = activeRun
                    ?: return@synchronized operationResponse(
                        command.commandId,
                        SourceSeparationIpcStatus.NoActiveRun,
                    )
                if (command.runId != active.descriptor.runId) {
                    return@synchronized operationResponse(
                        command.commandId,
                        SourceSeparationIpcStatus.StaleRun,
                    )
                }
                val result = active.host.closeRun(command.runId, command.processGeneration)
                if (result == SourceSeparationExecutionHostControlResult.Applied ||
                    result == SourceSeparationExecutionHostControlResult.NoActiveRun
                ) {
                    observerDeadline.runClosed(active.identity)
                    active.close()
                    activeRun = null
                }
                operationResponse(command.commandId, result.toIpcStatus())
            }
        }
    }

    private fun reserveRun(
        command: SourceSeparationIpcStartCommand,
        observerId: String,
        clientProcessName: String,
        callback: ISourceSeparationExecutionCallback,
    ): ActiveRemoteRun = synchronized(stateLock) {
        require(command.descriptor.processGeneration == processGeneration) {
            "IPC start command targets a stale process generation."
        }
        if (!commandLedger.record(command.commandId)) {
            throw SourceSeparationIpcDuplicateCommandException(command.commandId)
        }
        activeRun?.let { active ->
            throw SourceSeparationProcessExecutionBusyException(active.processOwner)
        }
        if (recycleAcknowledgement != null) throw SourceSeparationIpcRecyclingException()
        val processLease = SourceSeparationProcessExecutionAuthority.shared.acquire(
            SourceSeparationProcessExecutionOwner(
                family = SourceSeparationModelFamily.Mdx,
                runId = command.descriptor.runId,
                processGeneration = command.descriptor.processGeneration,
            ),
        )
        var sender: SourceSeparationRemoteEventSender? = null
        var admittedExecution: com.mardous.booming.separation.process
            .SourceSeparationRemoteAdmittedExecution? = null
        var active: ActiveRemoteRun? = null
        try {
            val connectedClient = replaceClientLocked(
                observerId = observerId,
                clientProcessName = clientProcessName,
                callback = callback,
            )
            ensureForegroundStarted(command.foregroundLease)
            val wakeLockDiagnostics = processingWakeLockController.diagnostics()
            require(wakeLockDiagnostics.activeLease == null &&
                !wakeLockDiagnostics.platformHeld
            ) { "A prior processing wake-lock lifetime has not ended." }
            val control = SourceSeparationRemoteExecutionControl(
                initialPlaybackPositionMs = command.descriptor.runtime.initialPlaybackPositionMs,
                initialPlaybackReadyWindowCount =
                    command.descriptor.runtime.initialPlaybackReadyWindowCount,
            )
            val preparedSender = SourceSeparationRemoteEventSender(
                initialObserverId = connectedClient.state.observerId,
                initialDelivery = connectedClient.callback::onEvent,
                onDeliveryFailure = ::handleObserverDeliveryFailure,
            ).also { sender = it }
            val environment = executionEnvironment()
            val preparedExecution = try {
                environment.prepare(command.descriptor, control)
            } catch (error: Throwable) {
                preparedSender.close()
                throw error
            }.also { admittedExecution = it }
            preparedExecution.observerConnected(
                observerId = connectedClient.state.observerId,
                observerProcessName = connectedClient.state.clientProcessName,
            )
            val host = InProcessSourceSeparationExecutionHost(
                rangeExecutor = environment.rangeExecutor,
                processGeneration = processGeneration,
                mode = SourceSeparationExecutionHostMode.BoundRemote,
            )
            active = ActiveRemoteRun(
                descriptor = command.descriptor,
                control = control,
                sender = preparedSender,
                host = host,
                environment = environment,
                admittedExecution = preparedExecution,
                observerState = connectedClient.state,
                processExecutionLease = processLease,
            )
            command.foregroundLease?.let { lease ->
                foregroundController.attachOrThrow(
                    lease,
                    "The media-processing foreground lease could not attach to its run.",
                )
                applyForegroundControls(active, lease)
            }
            active.also { activeRun = it }
        } catch (error: Throwable) {
            active?.rejectBeforeExecution(error) ?: run {
                runCatching { admittedExecution?.rejectBeforeExecution(error) }
                runCatching { admittedExecution?.close() }
                runCatching { sender?.close() }
                processLease.close()
            }
            throw error
        }
    }

    private fun executeRun(
        command: SourceSeparationIpcStartCommand,
        active: ActiveRemoteRun,
    ): String {
        var executionBegan = false
        var executionFailure: Throwable? = null
        var foregroundStopReason = "failed"
        return try {
            active.environment.beginExecution(command.descriptor)
            executionBegan = true
            command.foregroundLease?.let { lease ->
                require(foregroundController.diagnostics().activeLease?.let { active ->
                    active.request == lease &&
                        active.lifecycle == SourceSeparationForegroundLeaseLifecycle.Active
                } == true) {
                    "The processing wake lock requires an attached foreground lease."
                }
                val acquired = processingWakeLockController.acquire(lease)
                require(acquired == SourceSeparationProcessingWakeLockOperationResult.Applied ||
                    acquired == SourceSeparationProcessingWakeLockOperationResult.AlreadyApplied
                ) { "The processing wake-lock lease could not be acquired." }
            }
            val hosted = active.host.start(
                SourceSeparationExecutionHostRequest(
                    descriptor = command.descriptor,
                    executionRequest = active.executionRequest,
                    onEvent = { event ->
                        active.admittedExecution.persist(
                            event = event,
                            pauseReason = active.control.pauseReason(),
                        )
                        active.sender.offer(event)
                    },
                )
            )
            active.sender.closeAndAwait()
            foregroundStopReason = "completed"
            SourceSeparationExecutionIpcCodec.encodeStartResponse(
                SourceSeparationIpcStartResponse(
                    commandId = command.commandId,
                    status = SourceSeparationIpcStatus.Completed,
                    completion = hosted.result.toExecutionCompletion(active.executionRequest),
                    diagnostics = hosted.diagnostics.copy(
                        foregroundService = foregroundController.diagnostics(),
                        processingWakeLock = processingWakeLockController.diagnostics(),
                    ),
                )
            )
        } catch (error: SourceSeparationPausedException) {
            val deferred = active.foregroundDeferredException(error)
            if (deferred != null) {
                foregroundStopReason = "deferred:${deferred.reason.name}"
                terminalStartResponse(
                    commandId = command.commandId,
                    status = SourceSeparationIpcStatus.Deferred,
                    error = deferred,
                    deferredReason = deferred.reason,
                )
            } else {
                foregroundStopReason = "paused"
                terminalStartResponse(command.commandId, SourceSeparationIpcStatus.Paused, error)
            }
        } catch (error: CancellationException) {
            foregroundStopReason = "canceled"
            terminalStartResponse(command.commandId, SourceSeparationIpcStatus.Canceled, error)
        } catch (error: SourceSeparationProcessSessionRecycleRequiredException) {
            executionFailure = error
            foregroundStopReason = "recycle-required"
            terminalStartResponse(
                command.commandId,
                SourceSeparationIpcStatus.RecycleRequired,
                error,
            )
        } catch (error: SourceSeparationProcessSessionPoisonedException) {
            executionFailure = error
            foregroundStopReason = "session-poisoned"
            terminalStartResponse(
                command.commandId,
                SourceSeparationIpcStatus.RecycleRequired,
                error,
            )
        } catch (error: Throwable) {
            executionFailure = error
            foregroundStopReason = "failed:${error::class.java.simpleName}"
            terminalStartResponse(command.commandId, SourceSeparationIpcStatus.Failed, error)
        } finally {
            try {
                if (executionBegan) {
                    active.environment.finishExecution(
                        command.descriptor.runId,
                        executionFailure,
                    )
                }
            } finally {
                command.foregroundLease?.let { lease ->
                    processingWakeLockController.release(lease, foregroundStopReason)
                    foregroundController.stop(lease, foregroundStopReason)
                }
                active.markExecutionFinished()
                closeAbandonedRun(active)
                closeDestroyedServiceEnvironmentIfIdle()
            }
        }
    }

    private fun ensureForegroundStarted(
        lease: SourceSeparationForegroundLeaseRequest?,
    ) {
        if (lease == null) {
            require(foregroundController.diagnostics().activeLease == null) {
                "A foreground lease is pending for a different execution command."
            }
            return
        }
        val active = foregroundController.diagnostics().activeLease
        if (active == null) {
            val started = foregroundController.start(lease, startId = 0)
            require(started == SourceSeparationForegroundLeaseOperationResult.Applied &&
                foregroundController.diagnostics().activeLease != null
            ) { "The media-processing foreground lease is unavailable." }
        } else {
            require(active.request == lease) {
                "The active media-processing foreground lease has another identity."
            }
        }
    }

    private fun applyForegroundControls(
        active: ActiveRemoteRun,
        lease: SourceSeparationForegroundLeaseRequest,
    ) {
        val controls = foregroundController.diagnostics().activeLease
            ?.takeIf { it.request == lease }
            ?.controls
            .orEmpty()
        when {
            controls.any { it.action == SourceSeparationForegroundControlAction.Cancel } ->
                active.requestCancel()
            controls.any { it.action == SourceSeparationForegroundControlAction.Pause } ->
                active.requestPause()
        }
    }

    private fun handleForegroundStart(intent: Intent, startId: Int) {
        runCatching {
            val lease = intent.requireForegroundLease()
            require(lease.processGeneration == processGeneration) {
                "Foreground start targets a stale process generation."
            }
            foregroundController.start(lease, startId)
        }.onFailure { error ->
            Log.e(TAG, "Unable to start media-processing foreground ownership", error)
            foregroundController.releaseUnownedStart(startId)
        }
    }

    private fun handleForegroundControl(
        intent: Intent,
        startId: Int,
        action: SourceSeparationForegroundControlAction,
    ) {
        runCatching {
            val lease = intent.requireForegroundLease()
            val commandId = requireNotNull(intent.getStringExtra(EXTRA_COMMAND_ID)) {
                "Foreground control command ID is missing."
            }
            val result = foregroundController.control(
                request = lease,
                commandId = commandId,
                action = action,
                startId = startId,
            )
            if (result != SourceSeparationForegroundLeaseOperationResult.Applied) return
            synchronized(stateLock) {
                activeRun
                    ?.takeIf { active ->
                        active.descriptor.runId == lease.runId &&
                            active.descriptor.processGeneration == lease.processGeneration
                    }
                    ?.let { active ->
                        when (action) {
                            SourceSeparationForegroundControlAction.Pause ->
                                active.requestPause()
                            SourceSeparationForegroundControlAction.Cancel ->
                                active.requestCancel()
                        }
                    }
            }
        }.onFailure { error ->
            Log.e(TAG, "Unable to apply media-processing notification action", error)
            foregroundController.releaseUnownedStart(startId)
        }
    }

    private fun terminalStartResponse(
        commandId: String,
        status: SourceSeparationIpcStatus,
        error: Throwable,
        deferredReason: SourceSeparationForegroundDeferredReason? = null,
    ): String {
        val sender = synchronized(stateLock) { activeRun?.sender }
        runCatching { sender?.closeAndAwait() }
        return SourceSeparationExecutionIpcCodec.encodeStartResponse(
            SourceSeparationIpcStartResponse(
                commandId = commandId,
                status = status,
                error = error.toIpcError(),
                deferredReason = deferredReason,
            )
        )
    }

    private fun rejectedStartResponse(
        commandId: String,
        error: Throwable,
    ): String = SourceSeparationExecutionIpcCodec.encodeStartResponse(
        SourceSeparationIpcStartResponse(
            commandId = commandId,
            status = when (error) {
                is SourceSeparationIpcDuplicateCommandException ->
                    SourceSeparationIpcStatus.Duplicate
                is SourceSeparationProcessExecutionBusyException ->
                    SourceSeparationIpcStatus.Busy
                is com.mardous.booming.separation.process
                    .SourceSeparationRemoteCacheBusyException -> SourceSeparationIpcStatus.Busy
                is com.mardous.booming.separation.process
                    .SourceSeparationRemoteCacheAlreadyCompletedException ->
                    SourceSeparationIpcStatus.AlreadyCompleted
                is SourceSeparationForegroundExecutionDeferredException ->
                    SourceSeparationIpcStatus.Deferred
                else -> SourceSeparationIpcStatus.Rejected
            },
            error = error.toIpcError(),
            deferredReason = (error as? SourceSeparationForegroundExecutionDeferredException)
                ?.reason,
        )
    )

    private fun rejectedOperationResponse(
        commandId: String,
        error: Throwable,
    ): String = operationResponse(
        commandId = commandId,
        status = SourceSeparationIpcStatus.Rejected,
        error = error.toIpcError(),
    )

    private fun rejectedDiagnosticsResponse(
        commandId: String,
        error: Throwable,
    ): String = SourceSeparationExecutionIpcCodec.encodeDiagnosticsResponse(
        SourceSeparationIpcDiagnosticsResponse(
            commandId = commandId,
            status = SourceSeparationIpcStatus.Rejected,
            error = error.toIpcError(),
        )
    )

    private fun rejectedRecycleResponse(
        commandId: String,
        error: Throwable,
    ): String = SourceSeparationExecutionIpcCodec.encodeRecycleResponse(
        SourceSeparationIpcRecycleResponse(
            commandId = commandId,
            status = SourceSeparationIpcStatus.Rejected,
            error = error.toIpcError(),
        )
    )

    private fun operationResponse(
        commandId: String,
        status: SourceSeparationIpcStatus,
        snapshot: com.mardous.booming.separation.process
            .SourceSeparationExecutionHostSnapshot? = null,
        error: SourceSeparationIpcError? = null,
    ): String = SourceSeparationExecutionIpcCodec.encodeOperationResponse(
        SourceSeparationIpcOperationResponse(
            commandId = commandId,
            status = status,
            snapshot = snapshot,
            error = error,
        )
    )

    private fun handleClientDeath(observerId: String, callbackBinder: IBinder) {
        synchronized(stateLock) {
            val connected = client
            if (connected?.state?.observerId != observerId ||
                connected.callback.asBinder() !== callbackBinder
            ) {
                return
            }
            handleClientLossLocked("callback-binder-died")
        }
    }

    private fun replaceClientLocked(
        observerId: String,
        clientProcessName: String,
        callback: ISourceSeparationExecutionCallback,
    ): ConnectedClient {
        val state = SourceSeparationIpcObserverState(
            observerId = observerId,
            clientProcessName = clientProcessName,
            connectedAtElapsedRealtimeNanos = nowElapsedRealtimeNanos(),
        )
        unlinkClientDeathLocked()
        client = null
        val callbackBinder = callback.asBinder()
        val deathRecipient = IBinder.DeathRecipient {
            handleClientDeath(observerId, callbackBinder)
        }
        callbackBinder.linkToDeath(deathRecipient, 0)
        return ConnectedClient(
            state = state,
            callback = callback,
            deathRecipient = deathRecipient,
        ).also { client = it }
    }

    private fun handleObserverDeliveryFailure(observerId: String, error: Throwable) {
        Log.w(TAG, "Source-separation observer delivery failed: $observerId", error)
        synchronized(stateLock) {
            if (client?.state?.observerId != observerId) return
            handleClientLossLocked("callback-delivery-failed")
        }
    }

    private fun handleClientLossLocked(reason: String) {
        val active = activeRun
        val connected = client
        if (active != null && connected != null && hasIndependentAuthorityLocked(active)) {
            if (active.detachObserver(connected.state.observerId, reason)) {
                observerDeadline.observerDisconnected(active.identity)
                unlinkClientDeathLocked()
                client = null
                return
            }
        }
        if (active != null && connected == null && hasIndependentAuthorityLocked(active)) return
        active?.requestPause()
        val closeResult = active?.host?.closeRun(
            active.descriptor.runId,
            active.descriptor.processGeneration,
        )
        if (closeResult == SourceSeparationExecutionHostControlResult.Applied) {
            active.close()
            activeRun = null
        }
        unlinkClientDeathLocked()
        client = null
    }

    private fun closeAbandonedRun(active: ActiveRemoteRun) {
        synchronized(stateLock) {
            if (client != null || activeRun !== active) return
            observerDeadline.runClosed(active.identity)
            active.host.closeRun(
                active.descriptor.runId,
                active.descriptor.processGeneration,
            )
            active.close()
            activeRun = null
        }
    }

    private fun closeFinishedRunLocked() {
        val active = activeRun?.takeIf(ActiveRemoteRun::executionFinished) ?: return
        observerDeadline.runClosed(active.identity)
        active.host.closeRun(
            active.descriptor.runId,
            active.descriptor.processGeneration,
        )
        active.close()
        activeRun = null
    }

    private fun closeDestroyedServiceEnvironmentIfIdle() {
        if (!destroyed.get()) return
        val shouldClose = synchronized(stateLock) {
            activeRun == null && recycleAcknowledgement == null
        }
        if (shouldClose) closeExecutionEnvironment()
    }

    private fun closeExecutionEnvironment() {
        synchronized(environmentLock) {
            executionEnvironment?.close()
            executionEnvironment = null
        }
    }

    private fun unlinkClientDeathLocked() {
        client?.let { connected ->
            runCatching {
                connected.callback.asBinder().unlinkToDeath(connected.deathRecipient, 0)
            }
        }
    }

    private fun hasIndependentAuthorityLocked(active: ActiveRemoteRun): Boolean {
        val hostSnapshotAvailable = active.host.snapshot(
            active.descriptor.runId,
            active.descriptor.processGeneration,
        ) != null
        val wakeLock = processingWakeLockController.diagnostics()
        return SourceSeparationIndependentRunAuthorityPolicy.isEstablished(
            SourceSeparationIndependentRunAuthorityEvidence(
                runId = active.descriptor.runId,
                processGeneration = active.descriptor.processGeneration,
                displayName = active.descriptor.source.displayName,
                runClass = active.descriptor.runtime.runClass,
                backgroundPolicy = active.descriptor.runtime.backgroundPolicy,
                hostSnapshotAvailable = hostSnapshotAvailable,
                foregroundLease = foregroundController.diagnostics().activeLease,
                wakeLockLease = wakeLock.activeLease,
                platformWakeLockHeld = wakeLock.platformHeld,
            )
        )
    }

    private fun handleIndependentObserverDeadline(
        identity: SourceSeparationIndependentRunIdentity,
    ) {
        synchronized(stateLock) {
            val active = activeRun?.takeIf { run ->
                run.identity == identity && !run.observerConnected
            } ?: return
            Log.w(TAG, "Independent source-separation observer deadline expired: $identity")
            active.requestPause()
        }
    }

    private fun requireSameUidCaller() {
        require(Binder.getCallingUid() == applicationInfo.uid) {
            "Source-separation IPC caller UID is not allowed."
        }
    }

    private fun executionEnvironment(): SourceSeparationRemoteExecutionEnvironment {
        synchronized(environmentLock) {
            executionEnvironment?.let { return it }
            return SourceSeparationRemoteExecutionEnvironment(this, presetRepository)
                .also { executionEnvironment = it }
        }
    }

    private fun captureProcessDiagnostics(
        processName: String,
    ): SourceSeparationProcessDiagnostics {
        val activeRunId = synchronized(stateLock) { activeRun?.descriptor?.runId }
        val environment = synchronized(environmentLock) { executionEnvironment }
        return SourceSeparationProcessDiagnosticsCollector.capture(
            processGeneration = processGeneration,
            processName = processName,
            activeRunId = activeRunId,
            session = environment?.sessionDiagnostics()
                ?: SourceSeparationProcessSessionDiagnostics.empty(),
            validationOverride = environment?.validationOverrideDiagnostics(),
            foregroundService = foregroundController.diagnostics(),
            processingWakeLock = processingWakeLockController.diagnostics(),
        )
    }

    private fun scheduleSelfTermination() {
        Thread({
            SystemClock.sleep(SELF_TERMINATION_DELAY_MS)
            Process.killProcess(Process.myPid())
        }, SELF_TERMINATION_THREAD_NAME).apply {
            isDaemon = true
            start()
        }
    }

    private fun createProcessGeneration(): Long {
        val value = SystemClock.elapsedRealtimeNanos() xor
            (Process.myPid().toLong() shl 32)
        return (value and Long.MAX_VALUE).coerceAtLeast(1L)
    }

    private data class ConnectedClient(
        val state: SourceSeparationIpcObserverState,
        val callback: ISourceSeparationExecutionCallback,
        val deathRecipient: IBinder.DeathRecipient,
    )

    private class ActiveRemoteRun(
        val descriptor: com.mardous.booming.separation.process
            .SourceSeparationExecutionDescriptor,
        val control: SourceSeparationRemoteExecutionControl,
        val sender: SourceSeparationRemoteEventSender,
        val host: InProcessSourceSeparationExecutionHost,
        val environment: SourceSeparationRemoteExecutionEnvironment,
        val admittedExecution: com.mardous.booming.separation.process
            .SourceSeparationRemoteAdmittedExecution,
        observerState: SourceSeparationIpcObserverState,
        private val processExecutionLease: SourceSeparationProcessExecutionLease,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)
        private val processExecutionLifetime =
            SourceSeparationProcessExecutionLifetime(processExecutionLease)
        private val foregroundDeferredReason =
            AtomicReference<SourceSeparationForegroundDeferredReason?>(null)
        private var observerState = observerState

        val identity = SourceSeparationIndependentRunIdentity(
            runId = descriptor.runId,
            processGeneration = descriptor.processGeneration,
        )

        val observerConnected: Boolean
            get() = observerState.connected

        val independentAuthorityEligible: Boolean
            get() = descriptor.runtime.runClass == SourceSeparationExecutionRunClass.ManualFullSong &&
                descriptor.runtime.backgroundPolicy ==
                SourceSeparationBackgroundPolicy.IndependentForegroundEligible

        val processOwner: SourceSeparationProcessExecutionOwner
            get() = processExecutionLifetime.owner

        val executionRequest: com.mardous.booming.separation
            .SourceSeparationModelAwareExecutionRequest
            get() = admittedExecution.executionRequest

        var highestControlSequence: Long = 0L

        fun ipcState() = SourceSeparationIpcActiveRunState(
            descriptor = descriptor,
            authority = if (independentAuthorityEligible) {
                SourceSeparationIpcRunAuthority.IndependentForeground
            } else {
                SourceSeparationIpcRunAuthority.ClientBound
            },
            snapshot = host.snapshot(descriptor.runId, descriptor.processGeneration),
            observer = observerState,
        )

        fun attachObserver(connected: ConnectedClient) {
            check(independentAuthorityEligible) {
                "A client-bound run cannot replace its observer."
            }
            admittedExecution.observerConnected(
                connected.state.observerId,
                connected.state.clientProcessName,
            )
            observerState = connected.state
            sender.attach(connected.state.observerId, connected.callback::onEvent)
        }

        fun detachObserver(observerId: String, reason: String): Boolean {
            val current = observerState
            if (!current.connected || current.observerId != observerId) return false
            return try {
                admittedExecution.observerDisconnected(
                    observerId,
                    current.clientProcessName,
                    reason,
                )
                observerState = current.copy(
                    disconnectedAtElapsedRealtimeNanos = nowElapsedRealtimeNanos(),
                    disconnectReason = reason,
                )
                sender.detach(observerId)
                true
            } catch (error: Throwable) {
                Log.e(TAG, "Unable to persist source-separation observer loss", error)
                false
            }
        }

        fun requestPause() {
            control.requestPause()
            host.pause(descriptor.runId, descriptor.processGeneration)
        }

        fun requestCancel() {
            control.requestCancel()
            host.cancel(descriptor.runId, descriptor.processGeneration)
        }

        fun requestForegroundDeferral(reason: SourceSeparationForegroundDeferredReason) {
            foregroundDeferredReason.compareAndSet(null, reason)
            requestPause()
        }

        fun markExecutionFinished() {
            processExecutionLifetime.markExecutionFinished()
        }

        fun executionFinished(): Boolean = processExecutionLifetime.executionFinished()

        fun rejectBeforeExecution(error: Throwable) {
            processExecutionLifetime.rejectBeforeExecution()
            runCatching { admittedExecution.rejectBeforeExecution(error) }
                .onFailure { terminalError ->
                    Log.e(TAG, "Unable to persist rejected source-separation run", terminalError)
                }
            close()
        }

        fun foregroundDeferredException(
            cause: SourceSeparationPausedException,
        ): SourceSeparationForegroundExecutionDeferredException? =
            foregroundDeferredReason.get()?.let { reason ->
                SourceSeparationForegroundExecutionDeferredException(reason, cause)
            }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            try {
                sender.close()
            } finally {
                try {
                    host.close()
                } finally {
                    try {
                        admittedExecution.close()
                    } finally {
                        processExecutionLifetime.close()
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_BIND =
            "com.wluhwluh.booming.sourcesep.action.BIND_SOURCE_SEPARATION_EXECUTION"
        const val ACTION_RETAIN_WITHOUT_CLIENT =
            "com.wluhwluh.booming.sourcesep.action.RETAIN_SOURCE_SEPARATION_EXECUTION"
        const val ACTION_START_MEDIA_PROCESSING =
            "com.wluhwluh.booming.sourcesep.action.START_MEDIA_PROCESSING"
        const val ACTION_PAUSE_MEDIA_PROCESSING =
            "com.wluhwluh.booming.sourcesep.action.PAUSE_MEDIA_PROCESSING"
        const val ACTION_CANCEL_MEDIA_PROCESSING =
            "com.wluhwluh.booming.sourcesep.action.CANCEL_MEDIA_PROCESSING"
        private const val EXTRA_LEASE_ID = "foreground_lease_id"
        private const val EXTRA_RUN_ID = "foreground_run_id"
        private const val EXTRA_PROCESS_GENERATION = "foreground_process_generation"
        private const val EXTRA_DISPLAY_NAME = "foreground_display_name"
        private const val EXTRA_COMMAND_ID = "foreground_command_id"
        private const val SELF_TERMINATION_DELAY_MS =
            SourceSeparationProcessLifecyclePolicy.RECYCLE_ACKNOWLEDGEMENT_GRACE_MS
        private const val SELF_TERMINATION_THREAD_NAME = "SourceSeparationProcessRecycle"
        private const val TAG = "SourceSeparationFgs"

        fun foregroundStartIntent(
            context: Context,
            request: SourceSeparationForegroundLeaseRequest,
        ): Intent = foregroundIntent(context, request)
            .setAction(ACTION_START_MEDIA_PROCESSING)

        fun foregroundControlIntent(
            context: Context,
            request: SourceSeparationForegroundLeaseRequest,
            commandId: String,
            action: SourceSeparationForegroundControlAction,
        ): Intent = foregroundIntent(context, request)
            .setAction(when (action) {
                SourceSeparationForegroundControlAction.Pause ->
                    ACTION_PAUSE_MEDIA_PROCESSING
                SourceSeparationForegroundControlAction.Cancel ->
                    ACTION_CANCEL_MEDIA_PROCESSING
            })
            .putExtra(EXTRA_COMMAND_ID, commandId)

        private fun foregroundIntent(
            context: Context,
            request: SourceSeparationForegroundLeaseRequest,
        ) = Intent(context, SourceSeparationExecutionService::class.java)
            .putExtra(EXTRA_LEASE_ID, request.leaseId)
            .putExtra(EXTRA_RUN_ID, request.runId)
            .putExtra(EXTRA_PROCESS_GENERATION, request.processGeneration)
            .putExtra(EXTRA_DISPLAY_NAME, request.displayName)
    }

    private fun Intent.requireForegroundLease() = SourceSeparationForegroundLeaseRequest(
        leaseId = requireNotNull(getStringExtra(EXTRA_LEASE_ID)) {
            "Foreground lease ID is missing."
        },
        runId = requireNotNull(getStringExtra(EXTRA_RUN_ID)) {
            "Foreground run ID is missing."
        },
        processGeneration = getLongExtra(EXTRA_PROCESS_GENERATION, 0L),
        displayName = requireNotNull(getStringExtra(EXTRA_DISPLAY_NAME)) {
            "Foreground display name is missing."
        },
    )
}

private class SourceSeparationIpcCommandLedger(
    private val maximumEntries: Int = 512,
) {
    private val commandIds = LinkedHashSet<String>()

    init {
        require(maximumEntries > 0) { "IPC command-ledger limit is invalid." }
    }

    @Synchronized
    fun record(commandId: String): Boolean {
        if (!commandIds.add(commandId)) return false
        if (commandIds.size > maximumEntries) {
            val oldest = commandIds.iterator()
            oldest.next()
            oldest.remove()
        }
        return true
    }
}

private fun nowElapsedRealtimeNanos(): Long =
    SystemClock.elapsedRealtimeNanos().coerceAtLeast(1L)

private class SourceSeparationIpcDuplicateCommandException(
    commandId: String,
) : IllegalArgumentException("Duplicate IPC command: $commandId")

private class SourceSeparationIpcRecyclingException :
    IllegalStateException("The remote execution process is recycling.")

private fun SourceSeparationExecutionHostControlResult.toIpcStatus():
        SourceSeparationIpcStatus = when (this) {
    SourceSeparationExecutionHostControlResult.Applied -> SourceSeparationIpcStatus.Applied
    SourceSeparationExecutionHostControlResult.AlreadyApplied ->
        SourceSeparationIpcStatus.AlreadyApplied
    SourceSeparationExecutionHostControlResult.NoActiveRun ->
        SourceSeparationIpcStatus.NoActiveRun
    SourceSeparationExecutionHostControlResult.StaleRun ->
        SourceSeparationIpcStatus.StaleRun
    SourceSeparationExecutionHostControlResult.StaleGeneration ->
        SourceSeparationIpcStatus.StaleGeneration
    SourceSeparationExecutionHostControlResult.RunActive ->
        SourceSeparationIpcStatus.RunActive
    SourceSeparationExecutionHostControlResult.Terminal ->
        SourceSeparationIpcStatus.Terminal
    SourceSeparationExecutionHostControlResult.HostClosed ->
        SourceSeparationIpcStatus.Terminal
}

private fun Throwable.toIpcError(): SourceSeparationIpcError {
    val category = when (this) {
        is SourceSeparationIpcProtocolException ->
            SourceSeparationIpcErrorCategory.MalformedRequest
        is SourceSeparationExactCacheModelException ->
            SourceSeparationIpcErrorCategory.ModelUnavailable
        is SourceSeparationRemoteSourceUnavailableException ->
            SourceSeparationIpcErrorCategory.SourceUnavailable
        is SourceSeparationRemoteCacheUnavailableException ->
            SourceSeparationIpcErrorCategory.CacheUnavailable
        is SourceSeparationCacheLostException ->
            SourceSeparationIpcErrorCategory.CacheUnavailable
        is SourceSeparationProcessSessionRecycleRequiredException ->
            SourceSeparationIpcErrorCategory.RecycleRequired
        is SourceSeparationProcessSessionPoisonedException ->
            SourceSeparationIpcErrorCategory.RecycleRequired
        is SourceSeparationForegroundExecutionDeferredException ->
            SourceSeparationIpcErrorCategory.ForegroundServiceUnavailable
        is IllegalArgumentException -> SourceSeparationIpcErrorCategory.IdentityMismatch
        else -> SourceSeparationIpcErrorCategory.RuntimeFailure
    }
    return SourceSeparationIpcError(
        category = category,
        type = this::class.java.name,
        message = message,
    )
}
