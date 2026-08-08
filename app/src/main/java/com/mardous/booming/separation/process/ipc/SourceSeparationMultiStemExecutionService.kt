package com.mardous.booming.separation.process.ipc

import android.app.Service
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
import com.mardous.booming.separation.process.SourceSeparationMultiStemIpcStartResponse
import com.mardous.booming.separation.process.SourceSeparationMultiStemIpcStatus
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
            return try {
                val descriptor = SourceSeparationMultiStemExecutionCodec
                    .decodeDescriptor(descriptorJson)
                check(descriptor.processGeneration == engine.processGeneration) {
                    "Multi-stem descriptor targets a stale process generation."
                }
                val run = synchronized(stateLock) {
                    check(active == null) { "A multi-stem run is already active." }
                    ActiveRun(descriptor, callback).also { active = it }
                }
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
                when (command.action) {
                    SourceSeparationMultiStemIpcControlAction.Pause ->
                        run.requestPause(requireNotNull(command.pauseReason))
                    SourceSeparationMultiStemIpcControlAction.Cancel -> run.requestCancel()
                }
                controlResponse(SourceSeparationMultiStemIpcStatus.Applied)
            } catch (error: Throwable) {
                controlResponse(SourceSeparationMultiStemIpcStatus.Failed)
            }
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

    override fun onDestroy() {
        synchronized(stateLock) {
            active?.requestCancel()
            active = null
        }
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun execute(run: ActiveRun) {
        try {
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
                    shouldPause = run::shouldPause,
                    pauseReasonProvider = run::pauseReason,
                    shouldCancel = run::shouldCancel,
                ),
            )
            when (result) {
                is HtdemucsSourceSeparationEngineResult.Completed -> {
                    run.emit(SourceSeparationMultiStemExecutionEventPayload.Completed(
                        result.manifest.toMultiStemExecutionCompletion(),
                    ))
                    return
                }
                is HtdemucsSourceSeparationEngineResult.AlreadyCompleted -> {
                    run.emit(SourceSeparationMultiStemExecutionEventPayload.AlreadyCompleted(
                        result.manifest.toMultiStemExecutionCompletion(),
                    ))
                    return
                }
                is HtdemucsSourceSeparationEngineResult.Busy ->
                    error("The multi-stem cache is busy: ${result.cacheKey}")
            }
        } catch (error: SourceSeparationPausedException) {
            run.emit(SourceSeparationMultiStemExecutionEventPayload.Paused(error.pauseReason))
        } catch (error: java.util.concurrent.CancellationException) {
            run.emit(SourceSeparationMultiStemExecutionEventPayload.Canceled(error.message))
        } catch (error: Throwable) {
            Log.e(TAG, "Remote multi-stem execution failed", error)
            run.emit(SourceSeparationMultiStemExecutionEventPayload.Failed(
                error::class.java.name,
                error.message,
            ))
        } finally {
            synchronized(stateLock) {
                if (active === run) active = null
            }
        }
    }

    private fun controlResponse(status: SourceSeparationMultiStemIpcStatus): String =
        SourceSeparationMultiStemExecutionCodec.encodeControlResponse(
            SourceSeparationMultiStemIpcControlResponse(status = status),
        )

    private class ActiveRun(
        val descriptor: SourceSeparationMultiStemExecutionDescriptor,
        private val callback: ISourceSeparationMultiStemExecutionCallback,
    ) {
        private val sequence = AtomicLong(0L)
        private val pause = AtomicBoolean(false)
        private val cancel = AtomicBoolean(false)
        private val pauseReason = AtomicReference<SourceSeparationPauseReason?>(null)

        fun requestPause(reason: SourceSeparationPauseReason) {
            pauseReason.set(reason)
            pause.set(true)
        }

        fun requestCancel() {
            pause.set(false)
            cancel.set(true)
        }

        fun shouldPause(): Boolean = pause.get() && !cancel.get()
        fun shouldCancel(): Boolean = cancel.get()
        fun pauseReason(): SourceSeparationPauseReason =
            pauseReason.get() ?: SourceSeparationPauseReason.Standard

        fun emit(payload: SourceSeparationMultiStemExecutionEventPayload) {
            val event = SourceSeparationMultiStemExecutionEvent(
                runId = descriptor.runId,
                processGeneration = descriptor.processGeneration,
                sequence = sequence.incrementAndGet(),
                payload = payload,
            )
            runCatching {
                callback.onEvent(SourceSeparationMultiStemExecutionCodec.encodeEvent(event))
            }.onFailure { error ->
                if (error !is RemoteException) Log.w(TAG, "Multi-stem callback failed", error)
            }
        }
    }

    companion object {
        const val ACTION_BIND = "com.wluhwluh.booming.action.BIND_MULTISTEM_EXECUTION"
        private const val TAG = "BssMultiStemService"
    }
}
