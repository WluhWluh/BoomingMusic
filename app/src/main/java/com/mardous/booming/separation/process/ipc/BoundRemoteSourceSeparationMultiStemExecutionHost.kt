package com.mardous.booming.separation.process.ipc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.IBinder
import android.os.RemoteException
import androidx.core.content.ContextCompat
import com.mardous.booming.separation.HtdemucsSourceSeparationEngineResult
import com.mardous.booming.separation.SourceSeparationMultiStemExecutionHost
import com.mardous.booming.separation.SourceSeparationMultiStemExecutionRequest
import com.mardous.booming.separation.SourceSeparationPausedException
import com.mardous.booming.separation.SourceSeparationPauseReason
import com.mardous.booming.separation.cache.v2.AndroidSourceSeparationCacheRootProvider
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheStore
import com.mardous.booming.separation.process.SourceSeparationMultiStemExecutionCodec
import com.mardous.booming.separation.process.SourceSeparationMultiStemExecutionDescriptor
import com.mardous.booming.separation.process.SourceSeparationMultiStemExecutionEvent
import com.mardous.booming.separation.process.SourceSeparationMultiStemExecutionEventPayload
import com.mardous.booming.separation.process.SourceSeparationMultiStemIpcControlAction
import com.mardous.booming.separation.process.SourceSeparationMultiStemIpcControlCommand
import com.mardous.booming.separation.process.SourceSeparationMultiStemIpcStartCommand
import com.mardous.booming.separation.process.SourceSeparationMultiStemIpcStatus
import com.mardous.booming.separation.process.SourceSeparationForegroundLeaseRequest
import com.mardous.booming.separation.toExecutionDescriptor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID
import kotlin.concurrent.thread

/** Bound client for the dedicated multi-stem process. */
internal class BoundRemoteSourceSeparationMultiStemExecutionHost(
    context: Context,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val controlPollMs: Long = DEFAULT_CONTROL_POLL_MS,
) : SourceSeparationMultiStemExecutionHost {
    private val applicationContext = context.applicationContext
    private val cacheStore = SourceSeparationCacheStore(
        AndroidSourceSeparationCacheRootProvider(applicationContext).resolveRoot(),
    )

    internal fun terminateRemoteProcessForValidation() {
        val connected = bind()
        try {
            connected.service.terminateForValidation()
        } finally {
            runCatching { applicationContext.unbindService(connected.connection) }
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
                            request.onProgress(
                                com.mardous.booming.separation.model.MdxRangeProgress(
                                    completedWindows = payload.completedWindows,
                                    totalWindows = payload.totalWindows,
                                    stage = payload.stage,
                                ),
                            )
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
                            failure.compareAndSet(null, IllegalStateException(
                                "${payload.errorType}: ${payload.message ?: "remote failure"}",
                            ))
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
            failure.compareAndSet(null, DeadObjectException("Multi-stem service died."))
            terminal.countDown()
        }
        connected.binder.linkToDeath(deathRecipient, 0)
        val controlThread = thread(start = true, isDaemon = true, name = "BSS-MultiStem-Control") {
            while (terminal.count > 0L) {
                try {
                    if (request.shouldCancel()) {
                        service.updateControl(SourceSeparationMultiStemExecutionCodec.encodeControlCommand(
                            SourceSeparationMultiStemIpcControlCommand(
                                runId = descriptor.runId,
                                processGeneration = descriptor.processGeneration,
                                action = SourceSeparationMultiStemIpcControlAction.Cancel,
                            ),
                        ))
                        return@thread
                    }
                    if (request.shouldPause()) {
                        service.updateControl(SourceSeparationMultiStemExecutionCodec.encodeControlCommand(
                            SourceSeparationMultiStemIpcControlCommand(
                                runId = descriptor.runId,
                                processGeneration = descriptor.processGeneration,
                                action = SourceSeparationMultiStemIpcControlAction.Pause,
                                pauseReason = request.pauseReasonProvider(),
                            ),
                        ))
                        return@thread
                    }
                    Thread.sleep(controlPollMs)
                } catch (error: Throwable) {
                    failure.compareAndSet(null, error)
                    terminal.countDown()
                    return@thread
                }
            }
        }
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
                return HtdemucsSourceSeparationEngineResult.Busy(descriptor.cacheKey)
            }
            if (response.status != SourceSeparationMultiStemIpcStatus.Accepted) {
                throw IllegalStateException(
                    "Remote multi-stem start rejected: ${response.status} " +
                        (response.message ?: ""),
                )
            }
            check(terminal.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                "Timed out waiting for remote multi-stem execution."
            }
            failure.get()?.takeUnless { it === AlreadyCompletedSignal }?.let { throw it }
            val manifest = requireNotNull(cacheStore.readManifest(descriptor.cacheKey)) {
                "Remote multi-stem execution completed without a manifest."
            }
            if (failure.get() === AlreadyCompletedSignal) {
                HtdemucsSourceSeparationEngineResult.AlreadyCompleted(manifest)
            } else {
                HtdemucsSourceSeparationEngineResult.Completed(manifest)
            }
        } finally {
            controlThread.interrupt()
            runCatching { connected.binder.unlinkToDeath(deathRecipient, 0) }
            runCatching { applicationContext.unbindService(connected.connection) }
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
            check(connected.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                "Timed out binding the multi-stem execution service."
            }
            error.get()?.let { throw it }
            val binder = requireNotNull(binderRef.get())
            val service = requireNotNull(serviceRef.get())
            val response = SourceSeparationMultiStemExecutionCodec.decodeConnectResponse(
                service.connect(),
            )
            return Connected(connection, binder, service, response.processGeneration)
        } catch (error: Throwable) {
            runCatching { applicationContext.unbindService(connection) }
            throw error
        }
    }

    private data class Connected(
        val connection: ServiceConnection,
        val binder: IBinder,
        val service: ISourceSeparationMultiStemExecutionService,
        val generation: Long,
    )

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 30 * 60 * 1_000L
        const val DEFAULT_CONTROL_POLL_MS = 50L
        val AlreadyCompletedSignal = IllegalStateException("already-completed")
    }
}
