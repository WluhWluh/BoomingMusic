package com.mardous.booming.separation.process.ipc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Process
import android.os.RemoteException
import android.util.Log
import androidx.core.content.ContextCompat
import com.mardous.booming.separation.HtdemucsSourceSeparationEngineResult
import com.mardous.booming.separation.SourceSeparationMultiStemExecutionHost
import com.mardous.booming.separation.SourceSeparationMultiStemExecutionRequest
import com.mardous.booming.separation.SourceSeparationPausedException
import com.mardous.booming.separation.SourceSeparationPauseReason
import com.mardous.booming.separation.cache.v2.AndroidSourceSeparationCacheRootProvider
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheManifestState
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheStore
import com.mardous.booming.separation.process.SourceSeparationMultiStemExecutionCodec
import com.mardous.booming.separation.process.SourceSeparationMultiStemExecutionDescriptor
import com.mardous.booming.separation.process.SourceSeparationMultiStemExecutionEvent
import com.mardous.booming.separation.process.SourceSeparationMultiStemExecutionEventPayload
import com.mardous.booming.separation.process.SourceSeparationMultiStemIpcControlAction
import com.mardous.booming.separation.process.SourceSeparationMultiStemIpcControlCommand
import com.mardous.booming.separation.process.SourceSeparationMultiStemIpcStartCommand
import com.mardous.booming.separation.process.SourceSeparationMultiStemIpcStatus
import com.mardous.booming.separation.process.SourceSeparationForegroundExecutionDeferredException
import com.mardous.booming.separation.process.SourceSeparationProcessLifecyclePolicy
import com.mardous.booming.separation.process.SourceSeparationProcessDiagnostics
import com.mardous.booming.separation.process.SourceSeparationMultiStemIpcActiveRunState
import com.mardous.booming.separation.process.SourceSeparationForegroundLeaseRequest
import com.mardous.booming.separation.toExecutionDescriptor
import com.mardous.booming.separation.process.toMdxRangeProgress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID
import kotlin.concurrent.thread

/** Bound client for the dedicated multi-stem process. */
internal class BoundRemoteSourceSeparationMultiStemExecutionHost(
    context: Context,
    private val connectionTimeoutMs: Long = DEFAULT_CONNECTION_TIMEOUT_MS,
    private val controlPollMs: Long = DEFAULT_CONTROL_POLL_MS,
    private val controlledTerminationGraceMs: Long =
        SourceSeparationMultiStemHardTerminationPolicy.DEFAULT_GRACE_MS,
) : SourceSeparationMultiStemExecutionHost {
    private val applicationContext = context.applicationContext
    private val cacheStore = SourceSeparationCacheStore(
        AndroidSourceSeparationCacheRootProvider(applicationContext).resolveRoot(),
    )

    init {
        require(connectionTimeoutMs > 0L)
        require(controlPollMs > 0L)
        require(controlledTerminationGraceMs > 0L)
    }

    internal fun terminateRemoteProcessForValidation() {
        val connected = bind()
        try {
            connected.service.terminateForValidation()
        } finally {
            runCatching { applicationContext.unbindService(connected.connection) }
        }
    }

    internal fun reconnectableRun(): SourceSeparationMultiStemIpcActiveRunState? {
        val connected = bind()
        return try {
            SourceSeparationMultiStemExecutionCodec.decodeActiveRunResponse(
                connected.service.activeRun(),
            ).state
        } catch (error: Throwable) {
            throw error.asSourceSeparationRemoteHostDied()
        } finally {
            runCatching { applicationContext.unbindService(connected.connection) }
        }
    }

    internal fun processDiagnostics(): SourceSeparationProcessDiagnostics {
        val connected = bind()
        return try {
            SourceSeparationMultiStemExecutionCodec.decodeProcessDiagnostics(
                connected.service.diagnostics(),
            ).also { diagnostics ->
                check(diagnostics.processGeneration == connected.generation) {
                    "Multi-stem diagnostics target a stale process generation."
                }
            }
        } catch (error: Throwable) {
            throw error.asSourceSeparationRemoteHostDied()
        } finally {
            runCatching { applicationContext.unbindService(connected.connection) }
        }
    }

    internal fun adoptReconnectableRun(
        onEvent: (SourceSeparationMultiStemExecutionEvent) -> Unit,
    ): SourceSeparationMultiStemAdoptedRun? {
        val connected = bind()
        var adopted = false
        val terminal = CountDownLatch(1)
        val forcedControl = AtomicReference<SourceSeparationMultiStemTerminalControl?>(null)
        val forceScheduled = AtomicBoolean(false)
        val hardTerminationReconciled = AtomicBoolean(false)
        val callback = object : ISourceSeparationMultiStemExecutionCallback.Stub() {
            override fun onEvent(eventJson: String) {
                val event = SourceSeparationMultiStemExecutionCodec.decodeEvent(eventJson)
                if (event.payload.isTerminal()) terminal.countDown()
                onEvent(event)
            }
        }
        return try {
            val response = SourceSeparationMultiStemExecutionCodec.decodeActiveRunResponse(
                connected.service.adopt(callback),
            )
            val state = response.state ?: return null
            adopted = true
            SourceSeparationMultiStemAdoptedRun(
                state = state,
                callback = callback,
                control = { action, reason ->
                    try {
                        SourceSeparationMultiStemExecutionCodec.decodeControlResponse(
                            connected.service.updateControl(
                                SourceSeparationMultiStemExecutionCodec.encodeControlCommand(
                                    SourceSeparationMultiStemIpcControlCommand(
                                        runId = state.descriptor.runId,
                                        processGeneration = state.descriptor.processGeneration,
                                        action = action,
                                        pauseReason = reason,
                                    ),
                                ),
                            ),
                        ).status
                    } catch (error: Throwable) {
                        throw error.asSourceSeparationRemoteHostDied()
                    }
                },
                forceControl = { control ->
                    forcedControl.set(control)
                    if (forceScheduled.compareAndSet(false, true)) {
                        thread(
                            isDaemon = true,
                            name = "BSS-MultiStem-Adopted-Control",
                        ) {
                            runCatching {
                                if (!terminal.await(
                                        controlledTerminationGraceMs,
                                        TimeUnit.MILLISECONDS,
                                    ) && connected.service.terminateControlledRun(
                                        state.descriptor.runId,
                                        state.descriptor.processGeneration,
                                    )
                                ) {
                                    val deadlineNanos = System.nanoTime() +
                                        TimeUnit.MILLISECONDS.toNanos(connectionTimeoutMs)
                                    while (connected.binder.isBinderAlive &&
                                        terminal.count > 0L &&
                                        System.nanoTime() < deadlineNanos
                                    ) {
                                        Thread.sleep(controlPollMs)
                                    }
                                    if (!connected.binder.isBinderAlive) {
                                        reconcileControlledProcessDeath(
                                            descriptor = state.descriptor,
                                            control = forcedControl.get(),
                                            reconciled = hardTerminationReconciled,
                                        )
                                    }
                                }
                            }.onFailure { error ->
                                Log.w(TAG, "Unable to force-stop an adopted multi-stem run", error)
                            }
                        }
                    }
                },
                closeBinding = {
                    runCatching { applicationContext.unbindService(connected.connection) }
                },
            )
        } catch (error: Throwable) {
            throw error.asSourceSeparationRemoteHostDied()
        } finally {
            if (!adopted) {
                runCatching { applicationContext.unbindService(connected.connection) }
            }
        }
    }

    override fun separate(
        request: SourceSeparationMultiStemExecutionRequest,
    ): HtdemucsSourceSeparationEngineResult {
        val connected = bind()
        val descriptor = request.toExecutionDescriptor(
            processGeneration = connected.generation,
        )
        val foregroundLease = descriptor.takeIf { execution ->
            execution.runtime.runClass ==
                com.mardous.booming.separation.SourceSeparationExecutionRunClass.ManualFullSong
        }?.let { execution ->
            SourceSeparationForegroundLeaseRequest(
                leaseId = UUID.randomUUID().toString(),
                runId = execution.runId,
                processGeneration = execution.processGeneration,
                displayName = execution.source.displayName,
            )
        }
        val terminal = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val terminalControl = AtomicReference<SourceSeparationMultiStemTerminalControl?>(null)
        val hardTerminationReconciled = AtomicBoolean(false)
        val service = connected.service
        val callback = object : ISourceSeparationMultiStemExecutionCallback.Stub() {
            override fun onEvent(eventJson: String) {
                try {
                    val event = SourceSeparationMultiStemExecutionCodec.decodeEvent(eventJson)
                    require(event.runId == descriptor.runId &&
                        event.processGeneration == descriptor.processGeneration
                    ) { "Multi-stem event targets a stale run." }
                    when (val payload = event.payload) {
                        is SourceSeparationMultiStemExecutionEventPayload.Accepted -> {
                            require(payload.descriptor == descriptor)
                        }
                        is SourceSeparationMultiStemExecutionEventPayload.Progress ->
                            request.onProgress(payload.toMdxRangeProgress())
                        is SourceSeparationMultiStemExecutionEventPayload.Prepared -> {
                            cacheStore.readManifest(descriptor.cacheKey)?.let(request.onPrepared)
                        }
                        is SourceSeparationMultiStemExecutionEventPayload.SegmentStateChanged ->
                            request.onSegmentStateChanged(payload.segmentIndex, payload.state)
                        is SourceSeparationMultiStemExecutionEventPayload.Completed -> {
                            check(cacheStore.readManifest(descriptor.cacheKey) != null) {
                                "Multi-stem remote completion has no durable manifest."
                            }
                            terminal.countDown()
                        }
                        is SourceSeparationMultiStemExecutionEventPayload.AlreadyCompleted -> {
                            check(cacheStore.readManifest(descriptor.cacheKey) != null) {
                                "Remote already-completed result has no durable manifest."
                            }
                            failure.compareAndSet(
                                null,
                                AlreadyCompletedSignal,
                            )
                            terminal.countDown()
                        }
                        is SourceSeparationMultiStemExecutionEventPayload.Paused -> {
                            failure.compareAndSet(null, SourceSeparationPausedException(
                                pauseReason = payload.reason,
                            ))
                            terminal.countDown()
                        }
                        is SourceSeparationMultiStemExecutionEventPayload.Canceled -> {
                            failure.compareAndSet(null,
                                java.util.concurrent.CancellationException(payload.message),
                            )
                            terminal.countDown()
                        }
                        is SourceSeparationMultiStemExecutionEventPayload.Failed -> {
                            failure.compareAndSet(
                                null,
                                if (payload.errorType == com.mardous.booming.separation.process
                                        .SourceSeparationRemoteCacheBusyException::class.java.name
                                ) {
                                    SourceSeparationRemoteCacheBusyException(descriptor.cacheKey)
                                } else {
                                    IllegalStateException(
                                        "${payload.errorType}: " +
                                            (payload.message ?: "remote failure"),
                                    )
                                },
                            )
                            terminal.countDown()
                        }
                    }
                } catch (error: Throwable) {
                    failure.compareAndSet(null, error)
                    terminal.countDown()
                }
            }
        }
        val deathRecipient = IBinder.DeathRecipient {
            failure.compareAndSet(
                null,
                terminalControl.get()?.let { control ->
                    SourceSeparationMultiStemHardTerminationPolicy.failure(
                        control,
                        DeadObjectException("Multi-stem service died after terminal control."),
                    )
                } ?: SourceSeparationRemoteHostDiedException(
                    DeadObjectException("Multi-stem service died."),
                ),
            )
            terminal.countDown()
        }
        try {
            connected.binder.linkToDeath(deathRecipient, 0)
        } catch (error: Throwable) {
            runCatching { applicationContext.unbindService(connected.connection) }
            throw error.asSourceSeparationRemoteHostDied()
        }
        val controlThread = thread(start = false, isDaemon = true, name = "BSS-MultiStem-Control") {
            var lastPlaybackPositionMs = descriptor.runtime.initialPlaybackPositionMs
            var lastReadyWindowCount = descriptor.runtime.initialPlaybackReadyWindowCount
            while (terminal.count > 0L) {
                try {
                    if (request.shouldCancel()) {
                        val control = SourceSeparationMultiStemTerminalControl.Cancel
                        terminalControl.set(control)
                        val status = sendTerminalControl(
                            service = service,
                            descriptor = descriptor,
                            action = SourceSeparationMultiStemIpcControlAction.Cancel,
                        )
                        forceControlledTerminationAfterGrace(
                            service,
                            descriptor,
                            terminal,
                            status,
                        )
                        return@thread
                    }
                    if (request.shouldPause()) {
                        val reason = request.pauseReasonProvider()
                        terminalControl.set(SourceSeparationMultiStemTerminalControl.Pause(reason))
                        val status = sendTerminalControl(
                            service = service,
                            descriptor = descriptor,
                            action = SourceSeparationMultiStemIpcControlAction.Pause,
                            pauseReason = reason,
                        )
                        forceControlledTerminationAfterGrace(
                            service,
                            descriptor,
                            terminal,
                            status,
                        )
                        return@thread
                    }
                    val playbackPositionMs = request.playbackPositionMsProvider()
                        ?.takeIf { it >= 0L }
                    val readyWindowCount = request.playbackReadyWindowCountProvider()
                        .coerceAtLeast(1)
                    val positionChanged = playbackPositionMs != lastPlaybackPositionMs
                    val readyCountChanged = readyWindowCount != lastReadyWindowCount
                    if (positionChanged || readyCountChanged) {
                        service.updateControl(
                            SourceSeparationMultiStemExecutionCodec.encodeControlCommand(
                                SourceSeparationMultiStemIpcControlCommand(
                                    runId = descriptor.runId,
                                    processGeneration = descriptor.processGeneration,
                                    action = SourceSeparationMultiStemIpcControlAction.Update,
                                    hasPlaybackPositionUpdate = positionChanged,
                                    playbackPositionMs = playbackPositionMs
                                        .takeIf { positionChanged },
                                    playbackReadyWindowCount = readyWindowCount
                                        .takeIf { readyCountChanged },
                                ),
                            ),
                        )
                        lastPlaybackPositionMs = playbackPositionMs
                        lastReadyWindowCount = readyWindowCount
                    }
                    Thread.sleep(controlPollMs)
                } catch (error: Throwable) {
                    failure.compareAndSet(
                        null,
                        mapTerminalControlFailure(error, terminalControl.get()),
                    )
                    terminal.countDown()
                    return@thread
                }
            }
        }
        var recycleTerminalProcess = false
        return try {
            foregroundLease?.let { lease ->
                ContextCompat.startForegroundService(
                    applicationContext,
                    SourceSeparationMultiStemExecutionService.foregroundStartIntent(
                        applicationContext,
                        lease,
                    ),
                )
            }
            val response = SourceSeparationMultiStemExecutionCodec.decodeStartResponse(
                service.start(
                    SourceSeparationMultiStemExecutionCodec.encodeStartCommand(
                        SourceSeparationMultiStemIpcStartCommand(
                            descriptor = descriptor,
                            foregroundLease = foregroundLease,
                        ),
                    ),
                    callback,
                ),
            )
            if (response.status == SourceSeparationMultiStemIpcStatus.Busy) {
                throw sourceSeparationRemoteBusyFailure(
                    errorType = response.errorType,
                    message = response.message,
                    cacheKey = descriptor.cacheKey,
                )
            }
            if (response.status == SourceSeparationMultiStemIpcStatus.Deferred) {
                throw SourceSeparationForegroundExecutionDeferredException(
                    requireNotNull(response.deferredReason),
                )
            }
            if (response.status != SourceSeparationMultiStemIpcStatus.Accepted) {
                throw IllegalStateException(
                    "Remote multi-stem start rejected: ${response.status} " +
                        (response.message ?: ""),
                )
            }
            controlThread.start()
            while (!terminal.await(TERMINAL_OBSERVATION_POLL_MS, TimeUnit.MILLISECONDS)) {
                if (!connected.binder.isBinderAlive) {
                    failure.compareAndSet(
                        null,
                        terminalControl.get()?.let { control ->
                            SourceSeparationMultiStemHardTerminationPolicy.failure(
                                control,
                                DeadObjectException("Multi-stem service is no longer alive."),
                            )
                        } ?: SourceSeparationRemoteHostDiedException(
                            DeadObjectException("Multi-stem service is no longer alive."),
                        ),
                    )
                    terminal.countDown()
                    break
                }
                val activeResponse = SourceSeparationMultiStemExecutionCodec
                    .decodeActiveRunResponse(service.activeRun())
                val activeState = activeResponse.state
                if (activeState != null) {
                    require(activeState.descriptor.runId == descriptor.runId &&
                        activeState.descriptor.processGeneration ==
                            descriptor.processGeneration &&
                        activeState.descriptor.cacheKey == descriptor.cacheKey
                    ) { "Remote multi-stem lifecycle moved to a different run." }
                    continue
                }
                val journal = cacheStore.readRunJournal(descriptor.cacheKey)
                when (val durable = journal?.terminalStateFor(descriptor)) {
                    null -> Unit
                    SourceSeparationMultiStemDurableTerminal.Completed -> {
                        check(cacheStore.readManifest(descriptor.cacheKey)?.state ==
                            SourceSeparationCacheManifestState.Completed
                        ) { "Durable multi-stem completion has no completed manifest." }
                        terminal.countDown()
                    }
                    is SourceSeparationMultiStemDurableTerminal.Paused -> {
                        failure.compareAndSet(
                            null,
                            SourceSeparationPausedException(
                                pauseReason = durable.reason,
                            ),
                        )
                        terminal.countDown()
                    }
                    is SourceSeparationMultiStemDurableTerminal.Canceled -> {
                        failure.compareAndSet(
                            null,
                            java.util.concurrent.CancellationException(durable.message),
                        )
                        terminal.countDown()
                    }
                    is SourceSeparationMultiStemDurableTerminal.Failed -> {
                        failure.compareAndSet(
                            null,
                            IllegalStateException(
                                "${durable.errorType}: " +
                                    (durable.message ?: "remote failure"),
                            ),
                        )
                        terminal.countDown()
                    }
                }
                if (terminal.count > 0L) {
                    throw IllegalStateException(
                        "Remote multi-stem run ended without a durable terminal state.",
                    )
                }
            }
            if (!connected.binder.isBinderAlive) {
                reconcileControlledProcessDeath(
                    descriptor = descriptor,
                    control = terminalControl.get(),
                    reconciled = hardTerminationReconciled,
                )
            } else {
                awaitRemoteRunRelease(service, descriptor)
            }
            recycleTerminalProcess = SourceSeparationProcessLifecyclePolicy
                .requiresMultiStemTerminalRecycle(Process.is64Bit())
            failure.get()?.takeUnless { it === AlreadyCompletedSignal }?.let { error ->
                if (error is SourceSeparationRemoteCacheBusyException) {
                    return HtdemucsSourceSeparationEngineResult.Busy(descriptor.cacheKey)
                }
                throw error
            }
            val manifest = requireNotNull(cacheStore.readManifest(descriptor.cacheKey)) {
                "Remote multi-stem execution completed without a manifest."
            }
            if (failure.get() === AlreadyCompletedSignal) {
                HtdemucsSourceSeparationEngineResult.AlreadyCompleted(manifest)
            } else {
                HtdemucsSourceSeparationEngineResult.Completed(manifest)
            }
        } catch (error: Throwable) {
            val mapped = mapTerminalControlFailure(error, terminalControl.get())
            if (!connected.binder.isBinderAlive) {
                reconcileControlledProcessDeath(
                    descriptor = descriptor,
                    control = terminalControl.get(),
                    reconciled = hardTerminationReconciled,
                )
            }
            throw mapped
        } finally {
            controlThread.interrupt()
            runCatching { connected.binder.unlinkToDeath(deathRecipient, 0) }
            runCatching { applicationContext.unbindService(connected.connection) }
            if (recycleTerminalProcess) recycleTerminalProcess()
        }
    }

    private fun sendTerminalControl(
        service: ISourceSeparationMultiStemExecutionService,
        descriptor: SourceSeparationMultiStemExecutionDescriptor,
        action: SourceSeparationMultiStemIpcControlAction,
        pauseReason: SourceSeparationPauseReason? = null,
    ): SourceSeparationMultiStemIpcStatus = SourceSeparationMultiStemExecutionCodec
        .decodeControlResponse(
            service.updateControl(
                SourceSeparationMultiStemExecutionCodec.encodeControlCommand(
                    SourceSeparationMultiStemIpcControlCommand(
                        runId = descriptor.runId,
                        processGeneration = descriptor.processGeneration,
                        action = action,
                        pauseReason = pauseReason,
                    ),
                ),
            ),
        ).status

    private fun forceControlledTerminationAfterGrace(
        service: ISourceSeparationMultiStemExecutionService,
        descriptor: SourceSeparationMultiStemExecutionDescriptor,
        terminal: CountDownLatch,
        controlStatus: SourceSeparationMultiStemIpcStatus,
    ) {
        if (controlStatus != SourceSeparationMultiStemIpcStatus.Applied ||
            terminal.await(controlledTerminationGraceMs, TimeUnit.MILLISECONDS)
        ) return
        service.terminateControlledRun(descriptor.runId, descriptor.processGeneration)
    }

    private fun reconcileControlledProcessDeath(
        descriptor: SourceSeparationMultiStemExecutionDescriptor,
        control: SourceSeparationMultiStemTerminalControl?,
        reconciled: AtomicBoolean,
    ) {
        if (control == null || !reconciled.compareAndSet(false, true)) return
        val expectedTransition = SourceSeparationMultiStemHardTerminationPolicy.transition(control)
        val expectedLifecycle = SourceSeparationMultiStemHardTerminationPolicy.lifecycle(control)
        val deadlineNanos = System.nanoTime() +
            TimeUnit.MILLISECONDS.toNanos(connectionTimeoutMs)
        while (true) {
            val journal = cacheStore.recoverForcedTerminalRun(
                cacheKey = descriptor.cacheKey,
                runId = descriptor.runId,
                processGeneration = descriptor.processGeneration,
                terminalTransition = expectedTransition,
                terminalLifecycle = expectedLifecycle,
                terminalError = SourceSeparationMultiStemHardTerminationPolicy.cacheError(control),
            )
            if (journal?.lifecycle == expectedLifecycle &&
                journal.transitions.last().type == expectedTransition
            ) return
            check(System.nanoTime() < deadlineNanos) {
                "Timed out recovering cache state after controlled multi-stem process death."
            }
            Thread.sleep(controlPollMs)
        }
    }

    private fun mapTerminalControlFailure(
        error: Throwable,
        control: SourceSeparationMultiStemTerminalControl?,
    ): Throwable {
        val mapped = error.asSourceSeparationRemoteHostDied()
        return if (control != null && mapped is SourceSeparationRemoteHostDiedException) {
            SourceSeparationMultiStemHardTerminationPolicy.failure(control, mapped)
        } else {
            mapped
        }
    }

    private fun awaitRemoteRunRelease(
        service: ISourceSeparationMultiStemExecutionService,
        descriptor: SourceSeparationMultiStemExecutionDescriptor,
    ) {
        val deadlineNanos = System.nanoTime() +
            TimeUnit.MILLISECONDS.toNanos(connectionTimeoutMs)
        while (true) {
            val state = SourceSeparationMultiStemExecutionCodec.decodeActiveRunResponse(
                service.activeRun(),
            ).state ?: return
            check(state.descriptor.runId == descriptor.runId &&
                state.descriptor.processGeneration == descriptor.processGeneration
            ) { "Remote multi-stem lifecycle moved to a different terminal run." }
            check(System.nanoTime() < deadlineNanos) {
                "Timed out waiting for the terminal multi-stem run to release process ownership."
            }
            Thread.sleep(controlPollMs.coerceAtLeast(1L))
        }
    }

    private fun recycleTerminalProcess() {
        runCatching {
            BoundRemoteSourceSeparationExecutionHost(applicationContext).recycleAndStop(
                SourceSeparationIpcRecycleReason.MemoryPressure,
            )
        }.onFailure { error ->
            Log.w(TAG, "Unable to recycle the 32-bit process after a multi-stem run", error)
        }
    }

    private fun bind(): Connected {
        val connected = CountDownLatch(1)
        val serviceRef = AtomicReference<ISourceSeparationMultiStemExecutionService?>(null)
        val binderRef = AtomicReference<IBinder?>(null)
        val error = AtomicReference<Throwable?>(null)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                binderRef.set(service)
                serviceRef.set(ISourceSeparationMultiStemExecutionService.Stub.asInterface(service))
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) {
                error.compareAndSet(null, RemoteException("Multi-stem service disconnected."))
                connected.countDown()
            }
            override fun onBindingDied(name: ComponentName) {
                error.compareAndSet(null, DeadObjectException("Multi-stem binding died."))
                connected.countDown()
            }
            override fun onNullBinding(name: ComponentName) {
                error.compareAndSet(null, RemoteException("Multi-stem null binding."))
                connected.countDown()
            }
        }
        check(applicationContext.bindService(
            Intent(applicationContext, SourceSeparationMultiStemExecutionService::class.java)
                .setAction(SourceSeparationMultiStemExecutionService.ACTION_BIND),
            connection,
            Context.BIND_AUTO_CREATE,
        )) { "Unable to bind the multi-stem execution service." }
        try {
            check(connected.await(connectionTimeoutMs, TimeUnit.MILLISECONDS)) {
                "Timed out binding the multi-stem execution service."
            }
            error.get()?.let { throw it.asSourceSeparationRemoteHostDied() }
            val binder = requireNotNull(binderRef.get())
            val service = requireNotNull(serviceRef.get())
            val response = SourceSeparationMultiStemExecutionCodec.decodeConnectResponse(
                service.connect(),
            )
            return Connected(connection, binder, service, response.processGeneration)
        } catch (error: Throwable) {
            runCatching { applicationContext.unbindService(connection) }
            throw error.asSourceSeparationRemoteHostDied()
        }
    }

    private data class Connected(
        val connection: ServiceConnection,
        val binder: IBinder,
        val service: ISourceSeparationMultiStemExecutionService,
        val generation: Long,
    )

    private companion object {
        const val TAG = "BssMultiStemHost"
        const val DEFAULT_CONNECTION_TIMEOUT_MS = 10_000L
        const val DEFAULT_CONTROL_POLL_MS = 50L
        const val TERMINAL_OBSERVATION_POLL_MS = 5_000L
        val AlreadyCompletedSignal = IllegalStateException("already-completed")
    }
}

internal class SourceSeparationMultiStemAdoptedRun(
    val state: SourceSeparationMultiStemIpcActiveRunState,
    @Suppress("unused")
    private val callback: ISourceSeparationMultiStemExecutionCallback,
    private val control: (
        SourceSeparationMultiStemIpcControlAction,
        SourceSeparationPauseReason?,
    ) -> SourceSeparationMultiStemIpcStatus,
    private val forceControl: (SourceSeparationMultiStemTerminalControl) -> Unit,
    private val closeBinding: () -> Unit,
) : AutoCloseable {
    fun pause(
        reason: SourceSeparationPauseReason = SourceSeparationPauseReason.Standard,
    ): SourceSeparationMultiStemIpcStatus = control(
        SourceSeparationMultiStemIpcControlAction.Pause,
        reason,
    ).also { status ->
        if (status == SourceSeparationMultiStemIpcStatus.Applied) {
            forceControl(SourceSeparationMultiStemTerminalControl.Pause(reason))
        }
    }

    fun cancel(): SourceSeparationMultiStemIpcStatus = control(
        SourceSeparationMultiStemIpcControlAction.Cancel,
        null,
    ).also { status ->
        if (status == SourceSeparationMultiStemIpcStatus.Applied) {
            forceControl(SourceSeparationMultiStemTerminalControl.Cancel)
        }
    }

    override fun close() = closeBinding()
}

private fun SourceSeparationMultiStemExecutionEventPayload.isTerminal(): Boolean = when (this) {
    is SourceSeparationMultiStemExecutionEventPayload.Completed,
    is SourceSeparationMultiStemExecutionEventPayload.AlreadyCompleted,
    is SourceSeparationMultiStemExecutionEventPayload.Paused,
    is SourceSeparationMultiStemExecutionEventPayload.Canceled,
    is SourceSeparationMultiStemExecutionEventPayload.Failed,
    -> true
    else -> false
}
