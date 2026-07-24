package com.mardous.booming.separation.process.ipc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.IBinder
import android.os.RemoteException
import com.mardous.booming.AppProcessResolver
import com.mardous.booming.separation.SourceSeparationPausedException
import com.mardous.booming.separation.process.SourceSeparationExecutionHost
import com.mardous.booming.separation.process.SourceSeparationExecutionHostControlResult
import com.mardous.booming.separation.process.SourceSeparationExecutionHostMode
import com.mardous.booming.separation.process.SourceSeparationExecutionHostRequest
import com.mardous.booming.separation.process.SourceSeparationExecutionHostSnapshot
import com.mardous.booming.separation.process.SourceSeparationExecutionHostStartResult
import com.mardous.booming.separation.process.toMdxRangeSeparationResult
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class BoundRemoteSourceSeparationExecutionHost(
    context: Context,
    private val connectionTimeoutMs: Long = DEFAULT_CONNECTION_TIMEOUT_MS,
    private val controlPollIntervalMs: Long = DEFAULT_CONTROL_POLL_INTERVAL_MS,
    private val commandIdFactory: (String) -> String = { prefix ->
        "$prefix-${UUID.randomUUID()}"
    },
) : SourceSeparationExecutionHost {
    override val mode = SourceSeparationExecutionHostMode.BoundRemote

    private val applicationContext = context.applicationContext
    private val connectionLock = ReentrantLock()
    private val connectionChanged = connectionLock.newCondition()
    private var connectionState = SourceSeparationRemoteConnectionState.Unbound
    private var remoteService: ISourceSeparationExecutionService? = null
    private var remoteBinder: IBinder? = null
    private var remoteDeathRecipient: IBinder.DeathRecipient? = null
    private var connectResponse: SourceSeparationIpcConnectResponse? = null
    private var bound = false
    private var bindingSequence = 0L
    private var activeBindingGeneration = 0L
    private var activeServiceConnection: ServiceConnection? = null
    private var activeRequest: SourceSeparationExecutionHostRequest? = null
    private var activePump: RemoteControlPump? = null
    private val controlSequence = AtomicLong(0L)
    private val terminalConnectionFailure = AtomicReference<Throwable?>(null)
    private val callbackFailure = AtomicReference<Throwable?>(null)

    override val processGeneration: Long
        get() = ensureConnected().processGeneration

    val connectionDiagnostics: SourceSeparationRemoteConnectionDiagnostics
        get() = connectionLock.withLock {
            SourceSeparationRemoteConnectionDiagnostics(
                state = connectionState,
                processGeneration = connectResponse?.processGeneration,
                processName = connectResponse?.processName,
                pid = connectResponse?.pid,
                idlePssBytes = connectResponse?.idlePssBytes,
                failure = terminalConnectionFailure.get()?.message,
            )
        }

    override fun start(
        request: SourceSeparationExecutionHostRequest,
    ): SourceSeparationExecutionHostStartResult {
        val connection = ensureConnected()
        require(request.descriptor.processGeneration == connection.processGeneration) {
            "Bound-remote request targets a stale process generation."
        }
        val service = connectionLock.withLock {
            check(activeRequest == null) { "Bound-remote execution host is busy." }
            callbackFailure.set(null)
            activeRequest = request
            requireNotNull(remoteService)
        }
        val pump = RemoteControlPump(request).also {
            connectionLock.withLock { activePump = it }
            it.start()
        }
        val response = try {
            val payload = SourceSeparationExecutionIpcCodec.encodeStartCommand(
                SourceSeparationIpcStartCommand(
                    commandId = nextCommandId("start"),
                    descriptor = request.descriptor,
                )
            )
            SourceSeparationExecutionIpcCodec.decodeStartResponse(service.start(payload))
        } catch (error: Throwable) {
            throw handleRemoteFailure(error)
        } finally {
            pump.close()
            connectionLock.withLock {
                if (activePump === pump) activePump = null
            }
        }
        pump.failure()?.let {
            throw SourceSeparationRemoteExecutionException(
                SourceSeparationIpcError(
                    category = SourceSeparationIpcErrorCategory.Internal,
                    type = it::class.java.name,
                    message = it.message,
                ),
            )
        }
        callbackFailure.get()?.let { throw SourceSeparationRemoteCallbackException(it) }
        return when (response.status) {
            SourceSeparationIpcStatus.Completed -> {
                val completion = requireNotNull(response.completion)
                val diagnostics = requireNotNull(response.diagnostics)
                require(diagnostics.mode == SourceSeparationExecutionHostMode.BoundRemote &&
                    diagnostics.runId == request.descriptor.runId &&
                    diagnostics.processGeneration == connection.processGeneration
                ) {
                    "Bound-remote completion diagnostics do not match the admitted run."
                }
                SourceSeparationExecutionHostStartResult(
                    result = completion.toMdxRangeSeparationResult(request.executionRequest),
                    diagnostics = diagnostics,
                )
            }

            SourceSeparationIpcStatus.Paused -> throw SourceSeparationPausedException()
            SourceSeparationIpcStatus.Canceled ->
                throw CancellationException(response.error?.message ?: "Remote run canceled.")
            else -> throw SourceSeparationRemoteExecutionException(
                response.error ?: SourceSeparationIpcError(
                    category = SourceSeparationIpcErrorCategory.Internal,
                    type = "RemoteStart${response.status}",
                    message = "Remote start ended with status ${response.status}.",
                ),
            )
        }
    }

    override fun snapshot(
        runId: String,
        processGeneration: Long,
    ): SourceSeparationExecutionHostSnapshot? {
        val response = runCatching {
            callRunOperation("snapshot", runId, processGeneration) { service, payload ->
                service.snapshot(payload)
            }
        }.getOrElse { return null }
        return response.snapshot
    }

    override fun pause(
        runId: String,
        processGeneration: Long,
    ) = sendControl(
        runId,
        processGeneration,
        SourceSeparationIpcControlAction.Pause,
        false,
        null,
        null,
    )

    override fun cancel(
        runId: String,
        processGeneration: Long,
    ) = sendControl(
        runId,
        processGeneration,
        SourceSeparationIpcControlAction.Cancel,
        false,
        null,
        null,
    )

    override fun closeRun(
        runId: String,
        processGeneration: Long,
    ): SourceSeparationExecutionHostControlResult {
        val response = try {
            callRunOperation("close", runId, processGeneration) { service, payload ->
                service.closeRun(payload)
            }
        } catch (_: Throwable) {
            clearActiveRequest()
            return SourceSeparationExecutionHostControlResult.HostClosed
        }
        val result = response.status.toHostControlResult()
        if (result == SourceSeparationExecutionHostControlResult.Applied ||
            result == SourceSeparationExecutionHostControlResult.NoActiveRun ||
            result == SourceSeparationExecutionHostControlResult.HostClosed
        ) {
            clearActiveRequest()
        }
        return result
    }

    override fun close() {
        val shutdown = connectionLock.withLock {
            if (connectionState == SourceSeparationRemoteConnectionState.Closed) return
            connectionState = SourceSeparationRemoteConnectionState.Closed
            val state = ShutdownState(
                request = activeRequest,
                pump = activePump,
                service = remoteService,
                binder = remoteBinder,
                deathRecipient = remoteDeathRecipient,
                serviceConnection = activeServiceConnection,
                wasBound = bound,
            )
            activePump = null
            bound = false
            activeBindingGeneration = 0L
            activeServiceConnection = null
            remoteBinder = null
            remoteDeathRecipient = null
            remoteService = null
            connectResponse = null
            activeRequest = null
            callbackFailure.set(null)
            connectionChanged.signalAll()
            state
        }
        shutdown.pump?.close()
        if (shutdown.request != null && shutdown.service != null) {
            runCatching { sendShutdownCancel(shutdown.service, shutdown.request) }
        }
        if (shutdown.binder != null && shutdown.deathRecipient != null) {
            runCatching { shutdown.binder.unlinkToDeath(shutdown.deathRecipient, 0) }
        }
        if (shutdown.wasBound && shutdown.serviceConnection != null) {
            runCatching { applicationContext.unbindService(shutdown.serviceConnection) }
        }
    }

    private fun sendShutdownCancel(
        service: ISourceSeparationExecutionService,
        request: SourceSeparationExecutionHostRequest,
    ) {
        val descriptor = request.descriptor
        val command = SourceSeparationIpcControlCommand(
            commandId = nextCommandId("shutdown-cancel"),
            runId = descriptor.runId,
            processGeneration = descriptor.processGeneration,
            controlSequence = controlSequence.incrementAndGet(),
            action = SourceSeparationIpcControlAction.Cancel,
            hasPlaybackPositionUpdate = false,
            playbackPositionMs = null,
            playbackReadyWindowCount = null,
        )
        SourceSeparationExecutionIpcCodec.decodeOperationResponse(
            service.updateControl(SourceSeparationExecutionIpcCodec.encodeControlCommand(command)),
        )
    }

    private fun ensureConnected(): SourceSeparationIpcConnectResponse {
        val bindingAttempt = connectionLock.withLock {
            when (connectionState) {
                SourceSeparationRemoteConnectionState.Connected ->
                    return requireNotNull(connectResponse)
                SourceSeparationRemoteConnectionState.Closed ->
                    throw IllegalStateException("Bound-remote execution host is closed.")
                SourceSeparationRemoteConnectionState.Binding -> null
                SourceSeparationRemoteConnectionState.Unbound,
                SourceSeparationRemoteConnectionState.Dead,
                -> {
                    check(activeRequest == null) {
                        "A dead bound-remote run cannot rebind in place."
                    }
                    connectionState = SourceSeparationRemoteConnectionState.Binding
                    terminalConnectionFailure.set(null)
                    bindingSequence += 1L
                    val generation = bindingSequence.coerceAtLeast(1L)
                    val serviceConnection = createServiceConnection(generation)
                    activeBindingGeneration = generation
                    activeServiceConnection = serviceConnection
                    BindingAttempt(generation, serviceConnection)
                }
            }
        }
        if (bindingAttempt != null) {
            val intent = Intent(
                applicationContext,
                SourceSeparationExecutionService::class.java,
            ).setAction(SourceSeparationExecutionService.ACTION_BIND)
            val didBind = try {
                applicationContext.bindService(
                    intent,
                    bindingAttempt.serviceConnection,
                    Context.BIND_AUTO_CREATE,
                )
            } catch (error: Throwable) {
                markConnectionDead(error, bindingAttempt.generation)
                throw SourceSeparationRemoteHostDiedException(error)
            }
            val shouldUnbindRejectedBinding = connectionLock.withLock {
                if (bindingAttempt.generation == activeBindingGeneration &&
                    didBind &&
                    (connectionState == SourceSeparationRemoteConnectionState.Binding ||
                        connectionState == SourceSeparationRemoteConnectionState.Connected)
                ) {
                    bound = true
                    false
                } else if (bindingAttempt.generation == activeBindingGeneration &&
                    !didBind &&
                    connectionState == SourceSeparationRemoteConnectionState.Binding
                ) {
                    activeBindingGeneration = 0L
                    activeServiceConnection = null
                    connectionState = SourceSeparationRemoteConnectionState.Dead
                    terminalConnectionFailure.set(
                        IllegalStateException("Unable to bind the source-separation service."),
                    )
                    connectionChanged.signalAll()
                    false
                } else {
                    didBind
                }
            }
            if (shouldUnbindRejectedBinding) {
                runCatching {
                    applicationContext.unbindService(bindingAttempt.serviceConnection)
                }
            }
        }
        val observedBindingGeneration = connectionLock.withLock {
            activeBindingGeneration
        }
        val deadlineNanos = System.nanoTime() + connectionTimeoutMs * 1_000_000L
        return try {
            connectionLock.withLock {
                while (connectionState == SourceSeparationRemoteConnectionState.Binding) {
                    val remaining = deadlineNanos - System.nanoTime()
                    if (remaining <= 0L) {
                        throw SourceSeparationRemoteConnectionTimeoutException(
                            connectionTimeoutMs,
                        )
                    }
                    connectionChanged.awaitNanos(remaining)
                }
                if (connectionState != SourceSeparationRemoteConnectionState.Connected) {
                    throw SourceSeparationRemoteHostDiedException(
                        terminalConnectionFailure.get(),
                    )
                }
                requireNotNull(connectResponse)
            }
        } catch (timeout: SourceSeparationRemoteConnectionTimeoutException) {
            markConnectionDead(timeout, observedBindingGeneration)
            throw timeout
        }
    }

    private fun connect(
        bindingGeneration: Long,
        binder: IBinder,
    ) {
        var deathRecipient: IBinder.DeathRecipient? = null
        try {
            val isCurrentBinding = connectionLock.withLock {
                bindingGeneration == activeBindingGeneration &&
                    connectionState == SourceSeparationRemoteConnectionState.Binding
            }
            if (!isCurrentBinding) return
            val service = ISourceSeparationExecutionService.Stub.asInterface(binder)
            val recipient = IBinder.DeathRecipient {
                markConnectionDead(
                    DeadObjectException("Source-separation service binder died."),
                    bindingGeneration,
                    binder,
                )
            }
            deathRecipient = recipient
            binder.linkToDeath(recipient, 0)
            val processName = AppProcessResolver.resolve(applicationContext).processName
            val request = SourceSeparationIpcConnectRequest(
                commandId = nextCommandId("connect"),
                clientProcessName = processName,
            )
            val response = SourceSeparationExecutionIpcCodec.decodeConnectResponse(
                service.connect(
                    SourceSeparationExecutionIpcCodec.encodeConnectRequest(request),
                    callback,
                )
            )
            require(response.commandId == request.commandId &&
                response.processName == applicationContext.packageName +
                AppProcessResolver.SOURCE_SEPARATION_PROCESS_SUFFIX
            ) {
                "Bound service returned an invalid process identity."
            }
            val accepted = connectionLock.withLock {
                if (bindingGeneration != activeBindingGeneration ||
                    connectionState != SourceSeparationRemoteConnectionState.Binding
                ) {
                    false
                } else {
                    remoteService = service
                    remoteBinder = binder
                    remoteDeathRecipient = recipient
                    connectResponse = response
                    connectionState = SourceSeparationRemoteConnectionState.Connected
                    connectionChanged.signalAll()
                    true
                }
            }
            if (!accepted) {
                runCatching { binder.unlinkToDeath(recipient, 0) }
            }
        } catch (error: Throwable) {
            deathRecipient?.let { recipient ->
                runCatching { binder.unlinkToDeath(recipient, 0) }
            }
            markConnectionDead(error, bindingGeneration, binder)
        }
    }

    private val callback = object : ISourceSeparationExecutionCallback.Stub() {
        override fun onEvent(eventJson: String) {
            val event = SourceSeparationExecutionIpcCodec.decodeEvent(eventJson)
            val request = connectionLock.withLock {
                activeRequest ?: throw IllegalStateException(
                    "Bound-remote event arrived without an active request.",
                )
            }
            require(event.runId == request.descriptor.runId &&
                event.processGeneration == request.descriptor.processGeneration
            ) {
                "Bound-remote event targets a stale run or process generation."
            }
            try {
                request.onEvent(event)
            } catch (error: Throwable) {
                callbackFailure.compareAndSet(null, error)
                throw error
            }
        }
    }

    private fun createServiceConnection(bindingGeneration: Long) =
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                connect(bindingGeneration, service)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                markConnectionDead(
                    RemoteException("Source-separation service disconnected."),
                    bindingGeneration,
                )
            }

            override fun onBindingDied(name: ComponentName) {
                markConnectionDead(
                    DeadObjectException("Source-separation service binding died."),
                    bindingGeneration,
                )
            }

            override fun onNullBinding(name: ComponentName) {
                markConnectionDead(
                    RemoteException("Source-separation service returned null binding."),
                    bindingGeneration,
                )
            }
        }

    private fun markConnectionDead(
        error: Throwable,
        bindingGeneration: Long? = null,
        binder: IBinder? = null,
    ) {
        val disconnected = connectionLock.withLock {
            if (connectionState == SourceSeparationRemoteConnectionState.Closed) return
            if (bindingGeneration != null &&
                bindingGeneration != activeBindingGeneration
            ) return
            if (binder != null && remoteBinder != null && binder !== remoteBinder) return
            terminalConnectionFailure.compareAndSet(null, error)
            val state = DisconnectedBinding(
                binder = remoteBinder,
                deathRecipient = remoteDeathRecipient,
                serviceConnection = activeServiceConnection,
                wasBound = bound,
            )
            connectionState = SourceSeparationRemoteConnectionState.Dead
            bound = false
            activeBindingGeneration = 0L
            activeServiceConnection = null
            remoteService = null
            remoteBinder = null
            remoteDeathRecipient = null
            connectResponse = null
            connectionChanged.signalAll()
            state
        }
        if (disconnected.binder != null && disconnected.deathRecipient != null) {
            runCatching {
                disconnected.binder.unlinkToDeath(disconnected.deathRecipient, 0)
            }
        }
        if (disconnected.wasBound && disconnected.serviceConnection != null) {
            runCatching {
                applicationContext.unbindService(disconnected.serviceConnection)
            }
        }
    }

    private fun sendControl(
        runId: String,
        processGeneration: Long,
        action: SourceSeparationIpcControlAction,
        hasPlaybackPositionUpdate: Boolean,
        playbackPositionMs: Long?,
        playbackReadyWindowCount: Int?,
    ): SourceSeparationExecutionHostControlResult {
        val service = connectionLock.withLock {
            if (connectionState == SourceSeparationRemoteConnectionState.Closed ||
                connectionState != SourceSeparationRemoteConnectionState.Connected
            ) {
                return SourceSeparationExecutionHostControlResult.HostClosed
            }
            val request = activeRequest
                ?: return SourceSeparationExecutionHostControlResult.NoActiveRun
            if (request.descriptor.processGeneration != processGeneration) {
                return SourceSeparationExecutionHostControlResult.StaleGeneration
            }
            if (request.descriptor.runId != runId) {
                return SourceSeparationExecutionHostControlResult.StaleRun
            }
            requireNotNull(remoteService)
        }
        return try {
            val command = SourceSeparationIpcControlCommand(
                commandId = nextCommandId("control"),
                runId = runId,
                processGeneration = processGeneration,
                controlSequence = controlSequence.incrementAndGet(),
                action = action,
                hasPlaybackPositionUpdate = hasPlaybackPositionUpdate,
                playbackPositionMs = playbackPositionMs,
                playbackReadyWindowCount = playbackReadyWindowCount,
            )
            val response = SourceSeparationExecutionIpcCodec.decodeOperationResponse(
                service.updateControl(
                    SourceSeparationExecutionIpcCodec.encodeControlCommand(command),
                )
            )
            response.status.toHostControlResult()
        } catch (error: Throwable) {
            markConnectionDead(error)
            SourceSeparationExecutionHostControlResult.HostClosed
        }
    }

    private fun callRunOperation(
        prefix: String,
        runId: String,
        processGeneration: Long,
        call: (ISourceSeparationExecutionService, String) -> String,
    ): SourceSeparationIpcOperationResponse {
        val service = connectionLock.withLock {
            check(connectionState == SourceSeparationRemoteConnectionState.Connected) {
                "Bound-remote service is not connected."
            }
            requireNotNull(remoteService)
        }
        val command = SourceSeparationIpcRunCommand(
            commandId = nextCommandId(prefix),
            runId = runId,
            processGeneration = processGeneration,
        )
        return try {
            SourceSeparationExecutionIpcCodec.decodeOperationResponse(
                call(service, SourceSeparationExecutionIpcCodec.encodeRunCommand(command)),
            )
        } catch (error: Throwable) {
            throw handleRemoteFailure(error)
        }
    }

    private fun handleRemoteFailure(error: Throwable): RuntimeException {
        markConnectionDead(error)
        return SourceSeparationRemoteHostDiedException(error)
    }

    private fun clearActiveRequest() {
        val pump = connectionLock.withLock {
            val value = activePump
            activePump = null
            activeRequest = null
            controlSequence.set(0L)
            callbackFailure.set(null)
            value
        }
        pump?.close()
    }

    private fun nextCommandId(prefix: String): String {
        val value = commandIdFactory(prefix)
        require(value.isNotBlank()) { "IPC command ID factory returned an empty ID." }
        return value
    }

    private inner class RemoteControlPump(
        private val request: SourceSeparationExecutionHostRequest,
    ) : AutoCloseable {
        private val stopped = AtomicBoolean(false)
        private val failure = AtomicReference<Throwable?>(null)
        private var lastPlaybackPositionMs =
            request.descriptor.runtime.initialPlaybackPositionMs
        private var lastReadyWindowCount =
            request.descriptor.runtime.initialPlaybackReadyWindowCount
        private var pauseSent = false
        private var cancelSent = false
        private val thread = Thread(::run, CONTROL_THREAD_NAME).apply {
            isDaemon = true
        }

        fun start() = thread.start()

        fun failure(): Throwable? = failure.get()

        override fun close() {
            if (!stopped.compareAndSet(false, true)) return
            thread.interrupt()
            thread.join(CONTROL_CLOSE_TIMEOUT_MS)
        }

        private fun run() {
            try {
                while (!stopped.get()) {
                    val execution = request.executionRequest
                    val cancel = execution.shouldCancel()
                    val pause = execution.shouldPause()
                    if (cancel && !cancelSent) {
                        cancelSent = true
                        sendControl(
                            request.descriptor.runId,
                            request.descriptor.processGeneration,
                            SourceSeparationIpcControlAction.Cancel,
                            false,
                            null,
                            null,
                        )
                    } else if (pause && !pauseSent) {
                        pauseSent = true
                        sendControl(
                            request.descriptor.runId,
                            request.descriptor.processGeneration,
                            SourceSeparationIpcControlAction.Pause,
                            false,
                            null,
                            null,
                        )
                    }
                    val position = execution.playbackPositionMsProvider()
                        ?.takeIf { it >= 0L }
                    val readyCount = execution.playbackReadyWindowCountProvider()
                        .coerceAtLeast(1)
                    val positionChanged = position != lastPlaybackPositionMs
                    val readyCountChanged = readyCount != lastReadyWindowCount
                    if (positionChanged || readyCountChanged) {
                        sendControl(
                            request.descriptor.runId,
                            request.descriptor.processGeneration,
                            SourceSeparationIpcControlAction.Update,
                            positionChanged,
                            position,
                            readyCount.takeIf { readyCountChanged },
                        )
                        lastPlaybackPositionMs = position
                        lastReadyWindowCount = readyCount
                    }
                    Thread.sleep(controlPollIntervalMs)
                }
            } catch (_: InterruptedException) {
                Unit
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
                runCatching {
                    sendControl(
                        request.descriptor.runId,
                        request.descriptor.processGeneration,
                        SourceSeparationIpcControlAction.Cancel,
                        false,
                        null,
                        null,
                    )
                }
            }
        }
    }

    private companion object {
        const val DEFAULT_CONNECTION_TIMEOUT_MS = 10_000L
        const val DEFAULT_CONTROL_POLL_INTERVAL_MS = 100L
        const val CONTROL_THREAD_NAME = "SourceSeparationIpcControl"
        const val CONTROL_CLOSE_TIMEOUT_MS = 2_000L
    }

    private data class ShutdownState(
        val request: SourceSeparationExecutionHostRequest?,
        val pump: RemoteControlPump?,
        val service: ISourceSeparationExecutionService?,
        val binder: IBinder?,
        val deathRecipient: IBinder.DeathRecipient?,
        val serviceConnection: ServiceConnection?,
        val wasBound: Boolean,
    )

    private data class DisconnectedBinding(
        val binder: IBinder?,
        val deathRecipient: IBinder.DeathRecipient?,
        val serviceConnection: ServiceConnection?,
        val wasBound: Boolean,
    )

    private data class BindingAttempt(
        val generation: Long,
        val serviceConnection: ServiceConnection,
    )
}

internal enum class SourceSeparationRemoteConnectionState {
    Unbound,
    Binding,
    Connected,
    Dead,
    Closed,
}

internal data class SourceSeparationRemoteConnectionDiagnostics(
    val state: SourceSeparationRemoteConnectionState,
    val processGeneration: Long?,
    val processName: String?,
    val pid: Int?,
    val idlePssBytes: Long?,
    val failure: String?,
)

internal class SourceSeparationRemoteConnectionTimeoutException(
    timeoutMs: Long,
) : IllegalStateException("Source-separation service bind timed out after $timeoutMs ms.")

internal class SourceSeparationRemoteHostDiedException(
    cause: Throwable?,
) : IllegalStateException("Source-separation remote host died.", cause)

internal class SourceSeparationRemoteExecutionException(
    val remoteError: SourceSeparationIpcError,
) : IllegalStateException(
    "${remoteError.category}: ${remoteError.message ?: remoteError.type}",
)

internal class SourceSeparationRemoteCallbackException(
    cause: Throwable,
) : IllegalStateException("Unable to apply a source-separation remote event.", cause)

private fun SourceSeparationIpcStatus.toHostControlResult():
        SourceSeparationExecutionHostControlResult = when (this) {
    SourceSeparationIpcStatus.Applied -> SourceSeparationExecutionHostControlResult.Applied
    SourceSeparationIpcStatus.AlreadyApplied,
    SourceSeparationIpcStatus.Duplicate,
    -> SourceSeparationExecutionHostControlResult.AlreadyApplied
    SourceSeparationIpcStatus.NoActiveRun ->
        SourceSeparationExecutionHostControlResult.NoActiveRun
    SourceSeparationIpcStatus.StaleRun ->
        SourceSeparationExecutionHostControlResult.StaleRun
    SourceSeparationIpcStatus.StaleGeneration ->
        SourceSeparationExecutionHostControlResult.StaleGeneration
    SourceSeparationIpcStatus.RunActive,
    SourceSeparationIpcStatus.Busy,
    -> SourceSeparationExecutionHostControlResult.RunActive
    SourceSeparationIpcStatus.Terminal,
    SourceSeparationIpcStatus.Completed,
    SourceSeparationIpcStatus.Paused,
    SourceSeparationIpcStatus.Canceled,
    SourceSeparationIpcStatus.Failed,
    SourceSeparationIpcStatus.StaleControl,
    SourceSeparationIpcStatus.Rejected,
    -> SourceSeparationExecutionHostControlResult.Terminal
}
