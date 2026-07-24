package com.mardous.booming.separation.process.ipc

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import com.mardous.booming.AppProcessResolver
import com.mardous.booming.separation.SourceSeparationPausedException
import com.mardous.booming.separation.cache.v2.SourceSeparationExactCacheModelException
import com.mardous.booming.separation.model.preset.SourceSeparationPresetRepository
import com.mardous.booming.separation.model.MdxX86ProcessValidationOverride
import com.mardous.booming.separation.process.InProcessSourceSeparationExecutionHost
import com.mardous.booming.separation.process.SourceSeparationExecutionHostControlResult
import com.mardous.booming.separation.process.SourceSeparationExecutionHostMode
import com.mardous.booming.separation.process.SourceSeparationExecutionHostRequest
import com.mardous.booming.separation.process.SourceSeparationProcessDiagnostics
import com.mardous.booming.separation.process.SourceSeparationProcessDiagnosticsCollector
import com.mardous.booming.separation.process.SourceSeparationProcessLifecyclePolicy
import com.mardous.booming.separation.process.SourceSeparationProcessSessionDiagnostics
import com.mardous.booming.separation.process.SourceSeparationProcessSessionRecycleRequiredException
import com.mardous.booming.separation.process.SourceSeparationProcessSessionPoisonedException
import com.mardous.booming.separation.process.SourceSeparationRemoteCacheUnavailableException
import com.mardous.booming.separation.process.SourceSeparationRemoteExecutionControl
import com.mardous.booming.separation.process.SourceSeparationRemoteExecutionEnvironment
import com.mardous.booming.separation.process.SourceSeparationRemoteSourceUnavailableException
import com.mardous.booming.separation.process.toExecutionCompletion
import java.util.LinkedHashSet
import java.util.concurrent.CancellationException
import org.koin.android.ext.android.inject

internal class SourceSeparationExecutionService : Service() {
    private val presetRepository: SourceSeparationPresetRepository by inject()
    private val stateLock = Any()
    private val environmentLock = Any()
    private val processGeneration by lazy(::createProcessGeneration)
    private val commandLedger = SourceSeparationIpcCommandLedger()
    private var executionEnvironment: SourceSeparationRemoteExecutionEnvironment? = null
    private var clientCallback: ISourceSeparationExecutionCallback? = null
    private var clientDeathRecipient: IBinder.DeathRecipient? = null
    private var activeRun: ActiveRemoteRun? = null
    private var recycleAcknowledgement: SourceSeparationIpcRecycleAcknowledgement? = null
    private val retentionBinder = Binder()

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
                check(MdxX86ProcessValidationOverride.buildEnabled) {
                    "Remote warm retention is unavailable outside x86 validation builds."
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
            abandonClientLocked()
        }
        return false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        synchronized(stateLock) {
            activeRun?.close()
            activeRun = null
            unlinkClientDeathLocked()
            clientCallback = null
        }
        synchronized(environmentLock) {
            executionEnvironment?.close()
            executionEnvironment = null
        }
        super.onDestroy()
    }

    private val binder = object : ISourceSeparationExecutionService.Stub() {
        override fun connect(
            requestJson: String,
            callback: ISourceSeparationExecutionCallback,
        ): String {
            requireSameUidCaller()
            val request = SourceSeparationExecutionIpcCodec.decodeConnectRequest(requestJson)
            synchronized(stateLock) {
                require(commandLedger.record(request.commandId)) {
                    "Duplicate IPC connect command."
                }
                check(activeRun == null) {
                    "The IPC callback cannot be replaced during an active run."
                }
                unlinkClientDeathLocked()
                clientCallback = callback
                val deathRecipient = IBinder.DeathRecipient(::handleClientDeath)
                callback.asBinder().linkToDeath(deathRecipient, 0)
                clientDeathRecipient = deathRecipient
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
                )
            )
        }

        override fun start(requestJson: String): String {
            requireSameUidCaller()
            val command = try {
                SourceSeparationExecutionIpcCodec.decodeStartCommand(requestJson)
            } catch (error: Throwable) {
                return rejectedStartResponse("malformed", error)
            }
            val active = try {
                reserveRun(command)
            } catch (error: Throwable) {
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
                        active.control.requestPause()
                        active.host.pause(command.runId, command.processGeneration)
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
                if (result == SourceSeparationExecutionHostControlResult.Applied) {
                    active.close()
                    activeRun = null
                }
                operationResponse(command.commandId, result.toIpcStatus())
            }
        }
    }

    private fun reserveRun(
        command: SourceSeparationIpcStartCommand,
    ): ActiveRemoteRun = synchronized(stateLock) {
        require(command.descriptor.processGeneration == processGeneration) {
            "IPC start command targets a stale process generation."
        }
        if (!commandLedger.record(command.commandId)) {
            throw SourceSeparationIpcDuplicateCommandException(command.commandId)
        }
        if (activeRun != null) throw SourceSeparationIpcBusyException()
        if (recycleAcknowledgement != null) throw SourceSeparationIpcRecyclingException()
        val callback = requireNotNull(clientCallback) {
            "The remote execution client is not connected."
        }
        val control = SourceSeparationRemoteExecutionControl(
            initialPlaybackPositionMs = command.descriptor.runtime.initialPlaybackPositionMs,
            initialPlaybackReadyWindowCount =
                command.descriptor.runtime.initialPlaybackReadyWindowCount,
        )
        val sender = SourceSeparationRemoteEventSender(callback) {
            control.requestPause()
            synchronized(stateLock) {
                if (clientCallback?.asBinder() === callback.asBinder()) {
                    unlinkClientDeathLocked()
                    clientCallback = null
                }
            }
        }
        val environment = executionEnvironment()
        val executionRequest = try {
            environment.prepare(command.descriptor, control)
        } catch (error: Throwable) {
            sender.close()
            throw error
        }
        val host = InProcessSourceSeparationExecutionHost(
            rangeExecutor = environment.rangeExecutor,
            processGeneration = processGeneration,
            mode = SourceSeparationExecutionHostMode.BoundRemote,
        )
        ActiveRemoteRun(
            descriptor = command.descriptor,
            control = control,
            sender = sender,
            host = host,
            environment = environment,
            executionRequest = executionRequest,
        ).also { activeRun = it }
    }

    private fun executeRun(
        command: SourceSeparationIpcStartCommand,
        active: ActiveRemoteRun,
    ): String {
        var executionBegan = false
        var executionFailure: Throwable? = null
        return try {
            active.environment.beginExecution(command.descriptor.runId)
            executionBegan = true
            val hosted = active.host.start(
                SourceSeparationExecutionHostRequest(
                    descriptor = command.descriptor,
                    executionRequest = active.executionRequest,
                    onEvent = active.sender::offer,
                )
            )
            active.sender.closeAndAwait()
            SourceSeparationExecutionIpcCodec.encodeStartResponse(
                SourceSeparationIpcStartResponse(
                    commandId = command.commandId,
                    status = SourceSeparationIpcStatus.Completed,
                    completion = hosted.result.toExecutionCompletion(active.executionRequest),
                    diagnostics = hosted.diagnostics,
                )
            )
        } catch (error: SourceSeparationPausedException) {
            terminalStartResponse(command.commandId, SourceSeparationIpcStatus.Paused, error)
        } catch (error: CancellationException) {
            terminalStartResponse(command.commandId, SourceSeparationIpcStatus.Canceled, error)
        } catch (error: SourceSeparationProcessSessionRecycleRequiredException) {
            executionFailure = error
            terminalStartResponse(
                command.commandId,
                SourceSeparationIpcStatus.RecycleRequired,
                error,
            )
        } catch (error: SourceSeparationProcessSessionPoisonedException) {
            executionFailure = error
            terminalStartResponse(
                command.commandId,
                SourceSeparationIpcStatus.RecycleRequired,
                error,
            )
        } catch (error: Throwable) {
            executionFailure = error
            terminalStartResponse(command.commandId, SourceSeparationIpcStatus.Failed, error)
        } finally {
            if (executionBegan) {
                active.environment.finishExecution(
                    command.descriptor.runId,
                    executionFailure,
                )
            }
            closeAbandonedRun(active)
        }
    }

    private fun terminalStartResponse(
        commandId: String,
        status: SourceSeparationIpcStatus,
        error: Throwable,
    ): String {
        val sender = synchronized(stateLock) { activeRun?.sender }
        runCatching { sender?.closeAndAwait() }
        return SourceSeparationExecutionIpcCodec.encodeStartResponse(
            SourceSeparationIpcStartResponse(
                commandId = commandId,
                status = status,
                error = error.toIpcError(),
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
                is SourceSeparationIpcBusyException -> SourceSeparationIpcStatus.Busy
                else -> SourceSeparationIpcStatus.Rejected
            },
            error = error.toIpcError(),
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

    private fun handleClientDeath() {
        synchronized(stateLock) {
            abandonClientLocked()
        }
    }

    private fun abandonClientLocked() {
        val active = activeRun
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
        clientCallback = null
    }

    private fun closeAbandonedRun(active: ActiveRemoteRun) {
        synchronized(stateLock) {
            if (clientCallback != null || activeRun !== active) return
            active.host.closeRun(
                active.descriptor.runId,
                active.descriptor.processGeneration,
            )
            active.close()
            activeRun = null
        }
    }

    private fun unlinkClientDeathLocked() {
        val callback = clientCallback
        val recipient = clientDeathRecipient
        if (callback != null && recipient != null) {
            runCatching { callback.asBinder().unlinkToDeath(recipient, 0) }
        }
        clientDeathRecipient = null
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

    private class ActiveRemoteRun(
        val descriptor: com.mardous.booming.separation.process
            .SourceSeparationExecutionDescriptor,
        val control: SourceSeparationRemoteExecutionControl,
        val sender: SourceSeparationRemoteEventSender,
        val host: InProcessSourceSeparationExecutionHost,
        val environment: SourceSeparationRemoteExecutionEnvironment,
        val executionRequest: com.mardous.booming.separation
            .SourceSeparationModelAwareExecutionRequest,
    ) : AutoCloseable {
        var highestControlSequence: Long = 0L

        fun requestPause() {
            control.requestPause()
            host.pause(descriptor.runId, descriptor.processGeneration)
        }

        override fun close() {
            sender.close()
            host.close()
        }
    }

    companion object {
        const val ACTION_BIND =
            "com.wluhwluh.booming.sourcesep.action.BIND_SOURCE_SEPARATION_EXECUTION"
        const val ACTION_RETAIN_WITHOUT_CLIENT =
            "com.wluhwluh.booming.sourcesep.action.RETAIN_SOURCE_SEPARATION_EXECUTION"
        private const val SELF_TERMINATION_DELAY_MS =
            SourceSeparationProcessLifecyclePolicy.RECYCLE_ACKNOWLEDGEMENT_GRACE_MS
        private const val SELF_TERMINATION_THREAD_NAME = "SourceSeparationProcessRecycle"
    }
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

private class SourceSeparationIpcDuplicateCommandException(
    commandId: String,
) : IllegalArgumentException("Duplicate IPC command: $commandId")

private class SourceSeparationIpcBusyException :
    IllegalStateException("The remote execution host is busy.")

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
        is SourceSeparationRemoteEventDeliveryException ->
            SourceSeparationIpcErrorCategory.HostDied
        is SourceSeparationProcessSessionRecycleRequiredException ->
            SourceSeparationIpcErrorCategory.RecycleRequired
        is SourceSeparationProcessSessionPoisonedException ->
            SourceSeparationIpcErrorCategory.RecycleRequired
        is IllegalArgumentException -> SourceSeparationIpcErrorCategory.IdentityMismatch
        else -> SourceSeparationIpcErrorCategory.RuntimeFailure
    }
    return SourceSeparationIpcError(
        category = category,
        type = this::class.java.name,
        message = message,
    )
}
