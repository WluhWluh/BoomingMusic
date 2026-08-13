package com.mardous.booming.separation.process.ipc

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Process
import android.os.RemoteException
import android.util.Log
import com.mardous.booming.AppProcessResolver
import com.mardous.booming.BuildConfig
import com.mardous.booming.separation.HtdemucsSourceSeparationEngine
import com.mardous.booming.separation.HtdemucsSourceSeparationEngineResult
import com.mardous.booming.separation.SourceSeparationModelAwareSongInput
import com.mardous.booming.separation.SourceSeparationMultiStemExecutionRequest
import com.mardous.booming.separation.InProcessSourceSeparationMultiStemExecutionHost
import com.mardous.booming.separation.SourceSeparationPausedException
import com.mardous.booming.separation.SourceSeparationPauseReason
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheSourcePreflight
import com.mardous.booming.separation.model.contract.SourceSeparationMultiStemReleaseInstaller
import com.mardous.booming.separation.process.SourceSeparationMultiStemExecutionDescriptor
import com.mardous.booming.separation.process.SourceSeparationMultiStemExecutionEvent
import com.mardous.booming.separation.process.SourceSeparationMultiStemExecutionEventPayload
import com.mardous.booming.separation.process.SourceSeparationMultiStemExecutionCodec
import com.mardous.booming.separation.process.toMultiStemExecutionCompletion
import com.mardous.booming.separation.process.toMultiStemExecutionPreparation
import com.mardous.booming.separation.process.SourceSeparationMultiStemIpcControlAction
import com.mardous.booming.separation.process.SourceSeparationMultiStemIpcControlCommand
import com.mardous.booming.separation.process.SourceSeparationMultiStemIpcControlResponse
import com.mardous.booming.separation.process.SourceSeparationMultiStemIpcActiveRunResponse
import com.mardous.booming.separation.process.SourceSeparationMultiStemIpcActiveRunState
import com.mardous.booming.separation.process.SourceSeparationMultiStemIpcRunAuthority
import com.mardous.booming.separation.process.SourceSeparationMultiStemIpcStartResponse
import com.mardous.booming.separation.process.SourceSeparationMultiStemIpcStatus
import com.mardous.booming.separation.process.SourceSeparationForegroundControlAction
import com.mardous.booming.separation.process.SourceSeparationForegroundLeaseLifecycle
import com.mardous.booming.separation.process.SourceSeparationForegroundLeaseOperationResult
import com.mardous.booming.separation.process.SourceSeparationForegroundLeaseRequest
import com.mardous.booming.separation.process.SourceSeparationProcessingWakeLockOperationResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.koin.android.ext.android.inject

/** Dedicated remote worker for the CPU-only HTDemucs experimental path. */
internal class SourceSeparationMultiStemExecutionService : Service() {
    private val installer: SourceSeparationMultiStemReleaseInstaller by inject()
    private val engine: HtdemucsSourceSeparationEngine by inject()
    private val host by lazy { InProcessSourceSeparationMultiStemExecutionHost(engine) }
    private val worker: ExecutorService = Executors.newSingleThreadExecutor {
        Thread(it, "BSS-MultiStem-Remote").apply { isDaemon = true }
    }
    private val stateLock = Any()
    private var active: ActiveRun? = null
    private val foregroundController by lazy {
        SourceSeparationMediaProcessingForegroundController(
            service = this,
            controlIntentFactory = { _, request, commandId, action ->
                foregroundControlIntent(this, request, commandId, action)
            },
        )
    }
    private val processingWakeLockController by lazy {
        SourceSeparationProcessingWakeLockController(this) { request, reason ->
            Log.e(TAG, "Multi-stem processing wake-lock lease lost: $reason")
            synchronized(stateLock) {
                active?.takeIf { run -> run.matches(request) }
                    ?.requestPause(SourceSeparationPauseReason.Standard)
            }
        }
    }
    private val binder = object : ISourceSeparationMultiStemExecutionService.Stub() {
        override fun connect(): String = SourceSeparationMultiStemExecutionCodec
            .encodeConnectResponse(
                com.mardous.booming.separation.process.SourceSeparationMultiStemIpcConnectResponse(
                    processGeneration = engine.processGeneration,
                    pid = Process.myPid(),
                ),
            )

        override fun start(
            descriptorJson: String,
            callback: ISourceSeparationMultiStemExecutionCallback,
        ): String {
            var foregroundLease: SourceSeparationForegroundLeaseRequest? = null
            var reservedRun: ActiveRun? = null
            return try {
                val command = SourceSeparationMultiStemExecutionCodec
                    .decodeStartCommand(descriptorJson)
                val descriptor = command.descriptor
                foregroundLease = command.foregroundLease
                check(descriptor.processGeneration == engine.processGeneration) {
                    "Multi-stem descriptor targets a stale process generation."
                }
                val run = synchronized(stateLock) {
                    check(active == null) { "A multi-stem run is already active." }
                    ActiveRun(descriptor, callback, foregroundLease).also {
                        active = it
                        reservedRun = it
                    }
                }
                attachForegroundOwnership(run)
                run.emit(SourceSeparationMultiStemExecutionEventPayload.Accepted(descriptor))
                worker.execute { execute(run) }
                SourceSeparationMultiStemExecutionCodec.encodeStartResponse(
                    SourceSeparationMultiStemIpcStartResponse(
                        status = SourceSeparationMultiStemIpcStatus.Accepted,
                        runId = descriptor.runId,
                        processGeneration = descriptor.processGeneration,
                    ),
                )
            } catch (error: Throwable) {
                reservedRun?.observerDisconnected()
                synchronized(stateLock) {
                    if (active === reservedRun) active = null
                }
                foregroundLease?.let { lease ->
                    processingWakeLockController.release(lease, "start-rejected")
                    foregroundController.stop(lease, "start-rejected")
                }
                SourceSeparationMultiStemExecutionCodec.encodeStartResponse(
                    SourceSeparationMultiStemIpcStartResponse(
                        status = if (error.message?.contains("already active") == true) {
                            SourceSeparationMultiStemIpcStatus.Busy
                        } else {
                            SourceSeparationMultiStemIpcStatus.Failed
                        },
                        errorType = error::class.java.name,
                        message = error.message,
                    ),
                )
            }
        }

        override fun updateControl(commandJson: String): String {
            return try {
                val command = SourceSeparationMultiStemExecutionCodec
                    .decodeControlCommand(commandJson)
                val run = synchronized(stateLock) { active }
                    ?: return controlResponse(SourceSeparationMultiStemIpcStatus.NoActiveRun)
                if (run.descriptor.runId != command.runId) {
                    return controlResponse(SourceSeparationMultiStemIpcStatus.StaleRun)
                }
                if (run.descriptor.processGeneration != command.processGeneration) {
                    return controlResponse(SourceSeparationMultiStemIpcStatus.StaleGeneration)
                }
                run.updatePlaybackDemand(
                    hasPositionUpdate = command.hasPlaybackPositionUpdate,
                    positionMs = command.playbackPositionMs,
                    readyWindowCount = command.playbackReadyWindowCount,
                )
                when (command.action) {
                    SourceSeparationMultiStemIpcControlAction.Update -> Unit
                    SourceSeparationMultiStemIpcControlAction.Pause ->
                        run.requestPause(requireNotNull(command.pauseReason))
                    SourceSeparationMultiStemIpcControlAction.Cancel -> run.requestCancel()
                }
                controlResponse(SourceSeparationMultiStemIpcStatus.Applied)
            } catch (error: Throwable) {
                controlResponse(SourceSeparationMultiStemIpcStatus.Failed)
            }
        }

        override fun activeRun(): String = synchronized(stateLock) {
            val state = active?.snapshot()
            SourceSeparationMultiStemExecutionCodec.encodeActiveRunResponse(
                SourceSeparationMultiStemIpcActiveRunResponse(
                    status = if (state == null) {
                        SourceSeparationMultiStemIpcStatus.NoActiveRun
                    } else {
                        SourceSeparationMultiStemIpcStatus.Active
                    },
                    state = state,
                ),
            )
        }

        override fun adopt(
            callback: ISourceSeparationMultiStemExecutionCallback,
        ): String = synchronized(stateLock) {
            val run = active
                ?: return@synchronized SourceSeparationMultiStemExecutionCodec
                    .encodeActiveRunResponse(SourceSeparationMultiStemIpcActiveRunResponse(
                        status = SourceSeparationMultiStemIpcStatus.NoActiveRun,
                    ))
            check(run.independentlyOwned) {
                "A client-bound multi-stem run cannot replace its observer."
            }
            run.adopt(callback)
            SourceSeparationMultiStemExecutionCodec.encodeActiveRunResponse(
                SourceSeparationMultiStemIpcActiveRunResponse(
                    status = SourceSeparationMultiStemIpcStatus.Active,
                    state = requireNotNull(run.snapshot()),
                ),
            )
        }

        override fun terminateForValidation() {
            check(BuildConfig.DEBUG) { "Validation process termination is debug-only." }
            Thread({
                Thread.sleep(100L)
                Process.killProcess(Process.myPid())
            }, "BSS-MultiStem-ValidationDeath").apply {
                isDaemon = true
                start()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        check(AppProcessResolver.resolve(this).isSourceSeparationProcess) {
            "Multi-stem execution service started in the wrong process."
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        check(intent?.action == ACTION_BIND) {
            "Multi-stem execution service requires an explicit bind action."
        }
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (intent?.action != ACTION_BIND) return false
        synchronized(stateLock) {
            active?.let { run ->
                run.observerDisconnected()
                if (!run.independentlyOwned) run.requestCancel()
            }
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
        synchronized(stateLock) {
            active?.takeIf { run -> run.matches(request) }
                ?.requestPause(SourceSeparationPauseReason.Standard)
        }
        processingWakeLockController.release(request, "foreground-timeout")
    }

    override fun onDestroy() {
        processingWakeLockController.releaseActive("service-destroyed")
        foregroundController.stopActive("service-destroyed")
        synchronized(stateLock) {
            active?.requestCancel()
            active?.observerDisconnected()
            active = null
        }
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun execute(run: ActiveRun) {
        var terminalReason = "failed"
        try {
            run.foregroundLease?.let { lease ->
                val acquired = processingWakeLockController.acquire(lease)
                require(acquired == SourceSeparationProcessingWakeLockOperationResult.Applied ||
                    acquired == SourceSeparationProcessingWakeLockOperationResult.AlreadyApplied
                ) { "The multi-stem processing wake lock could not be acquired." }
            }
            val descriptor = run.descriptor
            val installed = requireNotNull(installer.installed(descriptor.model.modelId)) {
                "The selected multi-stem model is not installed."
            }
            require(installed.modelId == descriptor.model.modelId &&
                installed.modelSha256.equals(descriptor.model.artifactSha256, true) &&
                installed.contractId == descriptor.model.contractId
            ) { "Installed multi-stem model does not match the execution descriptor." }
            val input = SourceSeparationModelAwareSongInput(
                sourceUri = descriptor.source.sourceUri,
                displayName = descriptor.source.displayName,
                song = descriptor.song,
                sourceDiagnostics = descriptor.source.diagnostics,
            )
            val preflight = SourceSeparationCacheSourcePreflight(
                identity = descriptor.source.source,
                elapsedMs = 0L,
            )
            val result = host.separate(
                SourceSeparationMultiStemExecutionRequest(
                    runId = descriptor.runId,
                    input = input,
                    installedModel = installed,
                    preflight = preflight,
                    runClass = descriptor.runtime.runClass,
                    windowDecodeEnabled = descriptor.runtime.windowDecodeEnabled,
                    onProgress = { progress ->
                        run.emit(
                            SourceSeparationMultiStemExecutionEventPayload.Progress(
                                completedWindows = progress.completedWindows.coerceAtLeast(0),
                                totalWindows = progress.totalWindows.coerceAtLeast(1),
                                stage = progress.stage,
                                completedWindowElapsedMs = progress.completedWindowElapsedMs,
                                scheduler = progress.scheduler,
                            ),
                        )
                    },
                    onPrepared = { manifest ->
                        run.emit(SourceSeparationMultiStemExecutionEventPayload.Prepared(
                            manifest.toMultiStemExecutionPreparation(),
                        ))
                    },
                    onSegmentStateChanged = { index, state ->
                        run.emit(SourceSeparationMultiStemExecutionEventPayload.SegmentStateChanged(
                            index,
                            state,
                        ))
                    },
                    playbackPositionMsProvider = run::playbackPositionMs,
                    playbackReadyWindowCountProvider = run::playbackReadyWindowCount,
                    shouldPause = run::shouldPause,
                    pauseReasonProvider = run::pauseReason,
                    shouldCancel = run::shouldCancel,
                ),
            )
            when (result) {
                is HtdemucsSourceSeparationEngineResult.Completed -> {
                    terminalReason = "completed"
                    run.emit(SourceSeparationMultiStemExecutionEventPayload.Completed(
                        result.manifest.toMultiStemExecutionCompletion(),
                    ))
                    return
                }
                is HtdemucsSourceSeparationEngineResult.AlreadyCompleted -> {
                    terminalReason = "already-completed"
                    run.emit(SourceSeparationMultiStemExecutionEventPayload.AlreadyCompleted(
                        result.manifest.toMultiStemExecutionCompletion(),
                    ))
                    return
                }
                is HtdemucsSourceSeparationEngineResult.Busy ->
                    error("The multi-stem cache is busy: ${result.cacheKey}")
            }
        } catch (error: SourceSeparationPausedException) {
            terminalReason = "paused"
            run.emit(SourceSeparationMultiStemExecutionEventPayload.Paused(error.pauseReason))
        } catch (error: java.util.concurrent.CancellationException) {
            terminalReason = "canceled"
            run.emit(SourceSeparationMultiStemExecutionEventPayload.Canceled(error.message))
        } catch (error: Throwable) {
            terminalReason = "failed:${error::class.java.simpleName}"
            Log.e(TAG, "Remote multi-stem execution failed", error)
            run.emit(SourceSeparationMultiStemExecutionEventPayload.Failed(
                error::class.java.name,
                error.message,
            ))
        } finally {
            run.foregroundLease?.let { lease ->
                processingWakeLockController.release(lease, terminalReason)
                foregroundController.stop(lease, terminalReason)
            }
            synchronized(stateLock) {
                if (active === run) active = null
            }
            run.observerDisconnected()
        }
    }

    private fun controlResponse(status: SourceSeparationMultiStemIpcStatus): String =
        SourceSeparationMultiStemExecutionCodec.encodeControlResponse(
            SourceSeparationMultiStemIpcControlResponse(status = status),
        )

    private fun attachForegroundOwnership(run: ActiveRun) {
        val lease = run.foregroundLease ?: return
        val activeLease = foregroundController.diagnostics().activeLease
        if (activeLease == null) {
            val started = foregroundController.start(lease, startId = 0)
            require(started == SourceSeparationForegroundLeaseOperationResult.Applied ||
                started == SourceSeparationForegroundLeaseOperationResult.AlreadyApplied
            ) { "The multi-stem foreground lease could not start." }
        } else {
            require(activeLease.request == lease) {
                "Another media-processing foreground lease is active."
            }
        }
        val attached = foregroundController.attach(lease)
        require(attached == SourceSeparationForegroundLeaseOperationResult.Applied ||
            attached == SourceSeparationForegroundLeaseOperationResult.AlreadyApplied
        ) { "The multi-stem foreground lease could not attach." }
        val controls = foregroundController.diagnostics().activeLease
            ?.takeIf { record ->
                record.request == lease &&
                    record.lifecycle == SourceSeparationForegroundLeaseLifecycle.Active
            }
            ?.controls
            .orEmpty()
        when {
            controls.any { it.action == SourceSeparationForegroundControlAction.Cancel } ->
                run.requestCancel()
            controls.any { it.action == SourceSeparationForegroundControlAction.Pause } ->
                run.requestPause(SourceSeparationPauseReason.Standard)
        }
    }

    private fun handleForegroundStart(intent: Intent, startId: Int) {
        runCatching {
            val lease = intent.requireForegroundLease()
            require(lease.processGeneration == engine.processGeneration) {
                "Multi-stem foreground start targets a stale generation."
            }
            foregroundController.start(lease, startId)
        }.onFailure { error ->
            Log.e(TAG, "Unable to start multi-stem foreground ownership", error)
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
            val commandId = requireNotNull(intent.getStringExtra(EXTRA_COMMAND_ID))
            val result = foregroundController.control(lease, commandId, action, startId)
            if (result != SourceSeparationForegroundLeaseOperationResult.Applied) return
            synchronized(stateLock) {
                active?.takeIf { run -> run.matches(lease) }?.let { run ->
                    when (action) {
                        SourceSeparationForegroundControlAction.Pause ->
                            run.requestPause(SourceSeparationPauseReason.Standard)
                        SourceSeparationForegroundControlAction.Cancel -> run.requestCancel()
                    }
                }
            }
        }.onFailure { error ->
            Log.e(TAG, "Unable to apply multi-stem foreground control", error)
            foregroundController.releaseUnownedStart(startId)
        }
    }

    private class ActiveRun(
        val descriptor: SourceSeparationMultiStemExecutionDescriptor,
        callback: ISourceSeparationMultiStemExecutionCallback,
        val foregroundLease: SourceSeparationForegroundLeaseRequest?,
    ) {
        private val sequence = AtomicLong(0L)
        private val pause = AtomicBoolean(false)
        private val cancel = AtomicBoolean(false)
        private val pauseReason = AtomicReference<SourceSeparationPauseReason?>(null)
        private val playbackPositionMs = AtomicReference<Long?>(descriptor.runtime.initialPlaybackPositionMs)
        private val playbackReadyWindowCount = AtomicLong(
            descriptor.runtime.initialPlaybackReadyWindowCount.toLong(),
        )
        private var callback: ISourceSeparationMultiStemExecutionCallback? = null
        private var callbackBinder: IBinder? = null
        private var callbackDeathRecipient: IBinder.DeathRecipient? = null
        private var latestEvent: SourceSeparationMultiStemExecutionEvent? = null

        val independentlyOwned: Boolean
            get() = foregroundLease != null

        init {
            adopt(callback)
        }

        fun requestPause(reason: SourceSeparationPauseReason) {
            pauseReason.set(reason)
            pause.set(true)
        }

        fun requestCancel() {
            pause.set(false)
            cancel.set(true)
        }

        fun matches(request: SourceSeparationForegroundLeaseRequest): Boolean =
            descriptor.runId == request.runId &&
                descriptor.processGeneration == request.processGeneration &&
                foregroundLease == request

        @Synchronized
        fun adopt(newCallback: ISourceSeparationMultiStemExecutionCallback) {
            observerDisconnected()
            val binder = newCallback.asBinder()
            val recipient = IBinder.DeathRecipient {
                synchronized(this) {
                    if (callbackBinder === binder) observerDisconnected()
                }
            }
            binder.linkToDeath(recipient, 0)
            callback = newCallback
            callbackBinder = binder
            callbackDeathRecipient = recipient
        }

        @Synchronized
        fun observerDisconnected() {
            val binder = callbackBinder
            val recipient = callbackDeathRecipient
            if (binder != null && recipient != null) {
                runCatching { binder.unlinkToDeath(recipient, 0) }
            }
            callback = null
            callbackBinder = null
            callbackDeathRecipient = null
        }

        @Synchronized
        fun snapshot(): SourceSeparationMultiStemIpcActiveRunState? =
            latestEvent?.let { event ->
                SourceSeparationMultiStemIpcActiveRunState(
                    descriptor = descriptor,
                    authority = if (independentlyOwned) {
                        SourceSeparationMultiStemIpcRunAuthority.IndependentForeground
                    } else {
                        SourceSeparationMultiStemIpcRunAuthority.ClientBound
                    },
                    latestEvent = event,
                    observerConnected = callback != null,
                    foregroundLease = foregroundLease,
                )
            }

        fun shouldPause(): Boolean = pause.get() && !cancel.get()
        fun shouldCancel(): Boolean = cancel.get()
        fun pauseReason(): SourceSeparationPauseReason =
            pauseReason.get() ?: SourceSeparationPauseReason.Standard

        fun updatePlaybackDemand(
            hasPositionUpdate: Boolean,
            positionMs: Long?,
            readyWindowCount: Int?,
        ) {
            if (hasPositionUpdate) playbackPositionMs.set(positionMs)
            if (readyWindowCount != null) {
                playbackReadyWindowCount.set(readyWindowCount.coerceAtLeast(1).toLong())
            }
        }

        fun playbackPositionMs(): Long? = playbackPositionMs.get()

        fun playbackReadyWindowCount(): Int = playbackReadyWindowCount.get().toInt()

        fun emit(payload: SourceSeparationMultiStemExecutionEventPayload) {
            val event = SourceSeparationMultiStemExecutionEvent(
                runId = descriptor.runId,
                processGeneration = descriptor.processGeneration,
                sequence = sequence.incrementAndGet(),
                payload = payload,
            )
            val target = synchronized(this) {
                latestEvent = event
                callback
            } ?: return
            runCatching {
                target.onEvent(SourceSeparationMultiStemExecutionCodec.encodeEvent(event))
            }.onFailure { error ->
                synchronized(this) {
                    if (callback === target) observerDisconnected()
                }
                if (error !is RemoteException) Log.w(TAG, "Multi-stem callback failed", error)
            }
        }
    }

    companion object {
        const val ACTION_BIND = "com.wluhwluh.booming.action.BIND_MULTISTEM_EXECUTION"
        const val ACTION_START_MEDIA_PROCESSING =
            "com.wluhwluh.booming.action.START_MULTISTEM_MEDIA_PROCESSING"
        const val ACTION_PAUSE_MEDIA_PROCESSING =
            "com.wluhwluh.booming.action.PAUSE_MULTISTEM_MEDIA_PROCESSING"
        const val ACTION_CANCEL_MEDIA_PROCESSING =
            "com.wluhwluh.booming.action.CANCEL_MULTISTEM_MEDIA_PROCESSING"
        private const val EXTRA_LEASE_ID = "multistem_foreground_lease_id"
        private const val EXTRA_RUN_ID = "multistem_foreground_run_id"
        private const val EXTRA_PROCESS_GENERATION = "multistem_foreground_process_generation"
        private const val EXTRA_DISPLAY_NAME = "multistem_foreground_display_name"
        private const val EXTRA_COMMAND_ID = "multistem_foreground_command_id"
        private const val TAG = "BssMultiStemService"

        fun foregroundStartIntent(
            context: Context,
            request: SourceSeparationForegroundLeaseRequest,
        ): Intent = foregroundIntent(context, request).setAction(ACTION_START_MEDIA_PROCESSING)

        fun foregroundControlIntent(
            context: Context,
            request: SourceSeparationForegroundLeaseRequest,
            commandId: String,
            action: SourceSeparationForegroundControlAction,
        ): Intent = foregroundIntent(context, request)
            .setAction(when (action) {
                SourceSeparationForegroundControlAction.Pause -> ACTION_PAUSE_MEDIA_PROCESSING
                SourceSeparationForegroundControlAction.Cancel -> ACTION_CANCEL_MEDIA_PROCESSING
            })
            .putExtra(EXTRA_COMMAND_ID, commandId)

        private fun foregroundIntent(
            context: Context,
            request: SourceSeparationForegroundLeaseRequest,
        ) = Intent(context, SourceSeparationMultiStemExecutionService::class.java)
            .putExtra(EXTRA_LEASE_ID, request.leaseId)
            .putExtra(EXTRA_RUN_ID, request.runId)
            .putExtra(EXTRA_PROCESS_GENERATION, request.processGeneration)
            .putExtra(EXTRA_DISPLAY_NAME, request.displayName)
    }

    private fun Intent.requireForegroundLease() = SourceSeparationForegroundLeaseRequest(
        leaseId = requireNotNull(getStringExtra(EXTRA_LEASE_ID)),
        runId = requireNotNull(getStringExtra(EXTRA_RUN_ID)),
        processGeneration = getLongExtra(EXTRA_PROCESS_GENERATION, 0L),
        displayName = requireNotNull(getStringExtra(EXTRA_DISPLAY_NAME)),
    )
}
