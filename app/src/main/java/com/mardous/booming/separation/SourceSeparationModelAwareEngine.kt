package com.mardous.booming.separation

import android.content.Context
import android.net.Uri
import com.mardous.booming.data.model.Song
import com.mardous.booming.separation.cache.SourceSeparationSegmentState
import com.mardous.booming.separation.cache.v2.AndroidSourceSeparationCacheRootProvider
import com.mardous.booming.separation.cache.v2.SourceSeparationAdmittedGpuRuntimeIdentity
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheAdmittedRuntimePolicy
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheManifest
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunCoordinator
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunRequest
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunStart
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheSongLocator
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheSourceDiagnostics
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheSourceIdentityResolver
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheSourcePreflight
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheStore
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwareCacheRepository
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwareCacheRun
import com.mardous.booming.separation.cache.v2.SourceSeparationPresetCacheAvailabilityProvider
import com.mardous.booming.separation.cache.v2.SourceSeparationResolvedCacheModel
import com.mardous.booming.separation.cache.v2.resolveTrustedActiveCacheModel
import com.mardous.booming.separation.model.MdxRangeProgress
import com.mardous.booming.separation.model.MdxRangeSeparationResult
import com.mardous.booming.separation.model.MdxRuntimeSettings
import com.mardous.booming.separation.model.litert.MdxLiteRtBoundedGpuContract
import com.mardous.booming.separation.model.preset.SourceSeparationPresetRepository
import com.mardous.booming.separation.process.InProcessSourceSeparationExecutionHost
import com.mardous.booming.separation.process.SOURCE_SEPARATION_EXECUTION_PROTOCOL_VERSION
import com.mardous.booming.separation.process.SourceSeparationExecutionHost
import com.mardous.booming.separation.process.SourceSeparationExecutionHostControlResult
import com.mardous.booming.separation.process.SourceSeparationExecutionHostDiagnostics
import com.mardous.booming.separation.process.SourceSeparationExecutionBackendPolicy
import com.mardous.booming.separation.process.SourceSeparationExecutionHostEvent
import com.mardous.booming.separation.process.SourceSeparationExecutionHostEventPayload
import com.mardous.booming.separation.process.SourceSeparationExecutionHostMode
import com.mardous.booming.separation.process.SourceSeparationExecutionHostRequest
import com.mardous.booming.separation.process.SourceSeparationExecutionSessionIdentity
import com.mardous.booming.separation.process.SourceSeparationProcessingOwnerToken
import com.mardous.booming.separation.process.SourceSeparationProcessingOwnershipHandoff
import com.mardous.booming.separation.process.toExecutionDescriptor
import com.mardous.booming.separation.process.toMdxRangePreparation
import com.mardous.booming.separation.process.toMdxRangeProgress
import com.mardous.booming.separation.process.ipc.BoundRemoteSourceSeparationExecutionHost
import com.mardous.booming.separation.process.ipc.SourceSeparationRemoteForegroundPolicy
import com.mardous.booming.separation.process.ipc.SourceSeparationRemoteCacheAlreadyCompletedException
import com.mardous.booming.separation.process.ipc.SourceSeparationRemoteCacheBusyException
import java.util.UUID
import java.util.concurrent.CancellationException

/** Runs one contract-bound source-separation request through the v2 cache and runtime stack. */
internal class SourceSeparationModelAwareEngine(
    private val activeModelResolver: () -> SourceSeparationResolvedCacheModel?,
    private val preflightResolver: SourceSeparationModelAwarePreflightResolver,
    private val coordinator: SourceSeparationCacheRunCoordinator,
    rangeExecutor: SourceSeparationModelAwareRangeExecutor,
    private val executionHost: SourceSeparationExecutionHost =
        InProcessSourceSeparationExecutionHost(rangeExecutor),
    private val executionBackendPolicy: SourceSeparationExecutionBackendPolicy =
        SourceSeparationExecutionBackendPolicy.Auto,
    private val constructionGate: () -> Boolean,
    private val runIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val executionHostEventSink: (SourceSeparationExecutionHostEvent) -> Unit = {},
    private val processingOwnershipLease: SourceSeparationProcessingOwnershipHandoff
        .SourceSeparationProcessingOwnershipLease? = null,
) : AutoCloseable {
    private val closeLock = Any()
    private var closed = false

    override fun close() {
        synchronized(closeLock) {
            if (closed) return
            closed = true
        }
        try {
            executionHost.close()
        } finally {
            processingOwnershipLease?.release("execution-host-closed")
        }
    }

    fun separate(
        input: SourceSeparationModelAwareSongInput,
        runtimeSettings: MdxRuntimeSettings = MdxRuntimeSettings(),
        executionBackendPolicy: SourceSeparationExecutionBackendPolicy =
            this.executionBackendPolicy,
        runClass: SourceSeparationExecutionRunClass =
            SourceSeparationExecutionRunClass.PlaybackDemandWindow,
        onProgress: (MdxRangeProgress) -> Unit = {},
        playbackPositionMsProvider: () -> Long? = { null },
        playbackReadyWindowCountProvider: () -> Int = { DEFAULT_PLAYBACK_READY_WINDOW_COUNT },
        windowDecodeEnabled: Boolean = true,
        onPrepared: (SourceSeparationCacheManifest) -> Unit = {},
        shouldPause: () -> Boolean = { false },
        pauseReasonProvider: () -> SourceSeparationPauseReason = {
            SourceSeparationPauseReason.Standard
        },
        shouldCancel: () -> Boolean = { false },
    ): SourceSeparationModelAwareEngineResult {
        check(constructionGate()) {
            "The model-aware LiteRT engine is disabled by its construction gate."
        }
        val model = activeModelResolver()
            ?: return SourceSeparationModelAwareEngineResult.ActiveModelUnavailable
        val preflight = preflightResolver.resolve(input.sourceUri, shouldCancel)
        return separateResolved(
            input = input,
            model = model,
            preflight = preflight,
            runtimeSettings = runtimeSettings,
            executionBackendPolicy = executionBackendPolicy,
            runClass = runClass,
            onProgress = onProgress,
            playbackPositionMsProvider = playbackPositionMsProvider,
            playbackReadyWindowCountProvider = playbackReadyWindowCountProvider,
            windowDecodeEnabled = windowDecodeEnabled,
            onPrepared = onPrepared,
            shouldPause = shouldPause,
            pauseReasonProvider = pauseReasonProvider,
            shouldCancel = shouldCancel,
        )
    }

    fun separateResolved(
        input: SourceSeparationModelAwareSongInput,
        model: SourceSeparationResolvedCacheModel,
        preflight: SourceSeparationCacheSourcePreflight,
        runtimeSettings: MdxRuntimeSettings = MdxRuntimeSettings(),
        executionBackendPolicy: SourceSeparationExecutionBackendPolicy =
            this.executionBackendPolicy,
        runClass: SourceSeparationExecutionRunClass =
            SourceSeparationExecutionRunClass.PlaybackDemandWindow,
        onProgress: (MdxRangeProgress) -> Unit = {},
        playbackPositionMsProvider: () -> Long? = { null },
        playbackReadyWindowCountProvider: () -> Int = { DEFAULT_PLAYBACK_READY_WINDOW_COUNT },
        windowDecodeEnabled: Boolean = true,
        onPrepared: (SourceSeparationCacheManifest) -> Unit = {},
        shouldPause: () -> Boolean = { false },
        pauseReasonProvider: () -> SourceSeparationPauseReason = {
            SourceSeparationPauseReason.Standard
        },
        shouldCancel: () -> Boolean = { false },
    ): SourceSeparationModelAwareEngineResult {
        check(constructionGate()) {
            "The model-aware LiteRT engine is disabled by its construction gate."
        }
        val identity = model.contract.identity(preflight.identity)
        val admittedRuntimePolicy = coordinator.inspectAdmittedRuntimePolicy(identity)
            ?: executionBackendPolicy.toAdmittedRuntimePolicy()
        admittedRuntimePolicy.requireSupportedByCurrentBuild()
        val admittedBackendPolicy = if (admittedRuntimePolicy.tryGpu &&
            admittedRuntimePolicy.gpuFallbackLatch == null
        ) {
            SourceSeparationExecutionBackendPolicy.Auto
        } else {
            SourceSeparationExecutionBackendPolicy.Cpu
        }
        val executionRunId = runIdFactory().also {
            require(it.isNotBlank()) { "Execution run ID factory returned an empty ID." }
        }
        val executionSessionIdentity = SourceSeparationExecutionSessionIdentity.from(
            model = model,
            backendPolicy = admittedBackendPolicy,
            gpuRuntimeIdentity = admittedRuntimePolicy.gpuRuntimeIdentity,
            runtimeSettings = runtimeSettings,
        )
        val executionProcessGeneration = executionHost.prepareForExecutionSession(
            executionSessionIdentity,
        )
        if (executionHost.mode == SourceSeparationExecutionHostMode.BoundRemote) {
            coordinator.inspectCompleted(identity)?.let { manifest ->
                return SourceSeparationModelAwareEngineResult.AlreadyCompleted(
                    manifest = manifest,
                    preflightElapsedMs = preflight.elapsedMs,
                )
            }
            return executeRemote(
                input = input,
                model = model,
                identity = identity,
                preflightElapsedMs = preflight.elapsedMs,
                runtimeSettings = runtimeSettings,
                executionBackendPolicy = admittedBackendPolicy,
                runClass = runClass,
                tryGpu = admittedRuntimePolicy.tryGpu,
                gpuRuntimeIdentity = admittedRuntimePolicy.gpuRuntimeIdentity,
                gpuFallbackLatch = admittedRuntimePolicy.gpuFallbackLatch,
                onProgress = onProgress,
                playbackPositionMsProvider = playbackPositionMsProvider,
                playbackReadyWindowCountProvider = playbackReadyWindowCountProvider,
                windowDecodeEnabled = windowDecodeEnabled,
                onPrepared = onPrepared,
                shouldPause = shouldPause,
                pauseReasonProvider = pauseReasonProvider,
                shouldCancel = shouldCancel,
                runId = executionRunId,
                processGeneration = executionProcessGeneration,
            )
        }
        val runRequest = SourceSeparationCacheRunRequest(
            identity = identity,
            contract = model.contract,
            song = input.song,
            sourceDiagnostics = input.sourceDiagnostics,
            runId = executionRunId,
            processGeneration = executionProcessGeneration,
            ownerPid = runCatching { android.os.Process.myPid() }
                .getOrNull()
                ?.takeIf { it > 0 },
            runClass = runClass,
            backgroundPolicy = runClass.backgroundPolicy,
            tryGpu = admittedRuntimePolicy.tryGpu,
            gpuRuntimeIdentity = admittedRuntimePolicy.gpuRuntimeIdentity,
            gpuFallbackLatch = admittedRuntimePolicy.gpuFallbackLatch,
        )
        return when (val start = coordinator.begin(runRequest)) {
            SourceSeparationCacheRunStart.Busy ->
                SourceSeparationModelAwareEngineResult.Busy(identity.cacheKey, preflight.elapsedMs)

            is SourceSeparationCacheRunStart.AlreadyCompleted ->
                SourceSeparationModelAwareEngineResult.AlreadyCompleted(
                    manifest = start.manifest,
                    preflightElapsedMs = preflight.elapsedMs,
                )

            is SourceSeparationCacheRunStart.Ready -> execute(
                input = input,
                model = model,
                run = start.run,
                preflightElapsedMs = preflight.elapsedMs,
                runtimeSettings = runtimeSettings,
                executionBackendPolicy = admittedBackendPolicy,
                runClass = runClass,
                tryGpu = admittedRuntimePolicy.tryGpu,
                gpuRuntimeIdentity = admittedRuntimePolicy.gpuRuntimeIdentity,
                gpuFallbackLatch = admittedRuntimePolicy.gpuFallbackLatch,
                onProgress = onProgress,
                playbackPositionMsProvider = playbackPositionMsProvider,
                playbackReadyWindowCountProvider = playbackReadyWindowCountProvider,
                windowDecodeEnabled = windowDecodeEnabled,
                onPrepared = onPrepared,
                shouldPause = shouldPause,
                pauseReasonProvider = pauseReasonProvider,
                shouldCancel = shouldCancel,
                runId = executionRunId,
                processGeneration = executionProcessGeneration,
            )
        }
    }

    private fun executeRemote(
        input: SourceSeparationModelAwareSongInput,
        model: SourceSeparationResolvedCacheModel,
        identity: com.mardous.booming.separation.cache.v2.SourceSeparationCacheIdentity,
        preflightElapsedMs: Long,
        runtimeSettings: MdxRuntimeSettings,
        executionBackendPolicy: SourceSeparationExecutionBackendPolicy,
        runClass: SourceSeparationExecutionRunClass,
        tryGpu: Boolean,
        gpuRuntimeIdentity: SourceSeparationAdmittedGpuRuntimeIdentity?,
        gpuFallbackLatch: SourceSeparationGpuFallbackLatch?,
        onProgress: (MdxRangeProgress) -> Unit,
        playbackPositionMsProvider: () -> Long?,
        playbackReadyWindowCountProvider: () -> Int,
        windowDecodeEnabled: Boolean,
        onPrepared: (SourceSeparationCacheManifest) -> Unit,
        shouldPause: () -> Boolean,
        pauseReasonProvider: () -> SourceSeparationPauseReason,
        shouldCancel: () -> Boolean,
        runId: String,
        processGeneration: Long,
    ): SourceSeparationModelAwareEngineResult {
        var hostStartAttempted = false
        var hostRunAccepted = false
        var terminalError: Throwable? = null
        val eventLock = Any()
        val preview = coordinator.previewWorkspace(identity)
        val executionRequest = SourceSeparationModelAwareExecutionRequest(
            sourceUri = input.sourceUri,
            displayName = input.displayName,
            model = model,
            workspace = SourceSeparationModelAwareExecutionWorkspace(
                identity = identity,
                contract = model.contract,
                entryDirectory = preview.entryDirectory,
                workDirectory = preview.workDirectory,
                segmentsDirectory = preview.segmentsDirectory,
                resumeState = null,
            ),
            runtimeSettings = runtimeSettings,
            backendPolicy = executionBackendPolicy,
            runClass = runClass,
            backgroundPolicy = runClass.backgroundPolicy,
            tryGpu = tryGpu,
            gpuRuntimeIdentity = gpuRuntimeIdentity,
            gpuFallbackLatch = gpuFallbackLatch,
            onProgress = {},
            onPrepared = {},
            onSegmentStateChanged = { _, _ -> },
            onGpuFallbackLatched = {},
            playbackPositionMsProvider = playbackPositionMsProvider,
            playbackReadyWindowCountProvider = playbackReadyWindowCountProvider,
            windowDecodeEnabled = windowDecodeEnabled,
            shouldPause = shouldPause,
            pauseReasonProvider = pauseReasonProvider,
            shouldCancel = shouldCancel,
        )
        val descriptor = executionRequest.toExecutionDescriptor(
            runId = runId,
            processGeneration = processGeneration,
            backendPolicy = executionRequest.backendPolicy,
            sourceDiagnostics = input.sourceDiagnostics,
            song = input.song,
            initialPlaybackPositionMs = playbackPositionMsProvider()?.takeIf { it >= 0L },
            initialPlaybackReadyWindowCount = playbackReadyWindowCountProvider().coerceAtLeast(1),
        )
        return try {
            if (shouldCancel()) throw CancellationException("Source separation canceled.")
            var latestEventSequence = 0L
            hostStartAttempted = true
            val hosted = executionHost.start(
                SourceSeparationExecutionHostRequest(
                    descriptor = descriptor,
                    executionRequest = executionRequest,
                    onEvent = { event ->
                        synchronized(eventLock) {
                            require(event.protocolVersion ==
                                SOURCE_SEPARATION_EXECUTION_PROTOCOL_VERSION
                            ) { "Execution host event uses an unsupported protocol version." }
                            require(event.runId == runId &&
                                event.processGeneration == processGeneration
                            ) { "Execution host event targets a stale run or generation." }
                            require(event.sequence > latestEventSequence) {
                                "Execution host event sequence is stale."
                            }
                            when (val payload = event.payload) {
                                is SourceSeparationExecutionHostEventPayload.Accepted -> {
                                    require(payload.descriptor == descriptor) {
                                        "Execution host accepted a different descriptor."
                                    }
                                    hostRunAccepted = true
                                    processingOwnershipLease?.accepted(
                                        SourceSeparationProcessingOwnerToken(
                                            cacheKey = descriptor.cacheKey,
                                            runId = descriptor.runId,
                                            processGeneration = descriptor.processGeneration,
                                        )
                                    )
                                }
                                is SourceSeparationExecutionHostEventPayload.Progress ->
                                    onProgress(payload.progress.toMdxRangeProgress())
                                is SourceSeparationExecutionHostEventPayload.Prepared ->
                                    onPrepared(requireNotNull(
                                        coordinator.inspectManifest(identity),
                                    ) { "Remote cache preparation was not durably published." })
                                is SourceSeparationExecutionHostEventPayload
                                    .SegmentStateChanged,
                                is SourceSeparationExecutionHostEventPayload.GpuFallbackLatched,
                                is SourceSeparationExecutionHostEventPayload.Completed,
                                is SourceSeparationExecutionHostEventPayload.Paused,
                                is SourceSeparationExecutionHostEventPayload.Canceled,
                                is SourceSeparationExecutionHostEventPayload.Failed,
                                -> Unit
                            }
                            executionHostEventSink(event)
                            latestEventSequence = event.sequence
                        }
                    },
                )
            )
            synchronized(eventLock) {
                check(hostRunAccepted) { "Execution host completed without accepting the run." }
            }
            val manifest = requireNotNull(coordinator.inspectCompleted(identity)) {
                "Remote execution completed without a durable completed cache."
            }
            SourceSeparationModelAwareEngineResult.Completed(
                manifest = manifest,
                result = hosted.result,
                preflightElapsedMs = preflightElapsedMs,
                hostDiagnostics = hosted.diagnostics,
            )
        } catch (error: SourceSeparationRemoteCacheBusyException) {
            terminalError = error
            SourceSeparationModelAwareEngineResult.Busy(identity.cacheKey, preflightElapsedMs)
        } catch (error: SourceSeparationRemoteCacheAlreadyCompletedException) {
            terminalError = error
            SourceSeparationModelAwareEngineResult.AlreadyCompleted(
                manifest = requireNotNull(coordinator.inspectCompleted(identity)) {
                    "Remote cache reported completion without a valid manifest."
                },
                preflightElapsedMs = preflightElapsedMs,
            )
        } catch (error: Throwable) {
            terminalError = error
            throw error
        } finally {
            if (hostStartAttempted) {
                val closeResult = executionHost.closeRun(runId, processGeneration)
                check(
                    closeResult == SourceSeparationExecutionHostControlResult.Applied ||
                        closeResult == SourceSeparationExecutionHostControlResult.NoActiveRun ||
                        (terminalError != null &&
                            closeResult == SourceSeparationExecutionHostControlResult.HostClosed)
                ) { "Execution host did not close the terminal run: $closeResult" }
            }
        }
    }

    private fun execute(
        input: SourceSeparationModelAwareSongInput,
        model: SourceSeparationResolvedCacheModel,
        run: SourceSeparationModelAwareCacheRun,
        preflightElapsedMs: Long,
        runtimeSettings: MdxRuntimeSettings,
        executionBackendPolicy: SourceSeparationExecutionBackendPolicy,
        runClass: SourceSeparationExecutionRunClass,
        tryGpu: Boolean,
        gpuRuntimeIdentity: SourceSeparationAdmittedGpuRuntimeIdentity?,
        gpuFallbackLatch: SourceSeparationGpuFallbackLatch?,
        onProgress: (MdxRangeProgress) -> Unit,
        playbackPositionMsProvider: () -> Long?,
        playbackReadyWindowCountProvider: () -> Int,
        windowDecodeEnabled: Boolean,
        onPrepared: (SourceSeparationCacheManifest) -> Unit,
        shouldPause: () -> Boolean,
        pauseReasonProvider: () -> SourceSeparationPauseReason,
        shouldCancel: () -> Boolean,
        runId: String,
        processGeneration: Long,
    ): SourceSeparationModelAwareEngineResult {
        var hostStartAttempted = false
        var hostRunAccepted = false
        var terminalError: Throwable? = null
        val eventLock = Any()
        return try {
            val currentRunId = runId
            val currentProcessGeneration = processGeneration
            if (shouldCancel()) throw CancellationException("Source separation canceled.")
            val initialPlaybackPositionMs = playbackPositionMsProvider()
                ?.takeIf { it >= 0L }
            val initialPlaybackReadyWindowCount = playbackReadyWindowCountProvider()
                .coerceAtLeast(1)
            val executionRequest = SourceSeparationModelAwareExecutionRequest(
                sourceUri = input.sourceUri,
                displayName = input.displayName,
                model = model,
                workspace = SourceSeparationModelAwareExecutionWorkspace.from(run),
                runtimeSettings = runtimeSettings,
                backendPolicy = executionBackendPolicy,
                runClass = runClass,
                backgroundPolicy = runClass.backgroundPolicy,
                tryGpu = tryGpu,
                gpuRuntimeIdentity = gpuRuntimeIdentity,
                gpuFallbackLatch = gpuFallbackLatch,
                onProgress = {},
                onPrepared = {},
                onSegmentStateChanged = { _, _ -> },
                onGpuFallbackLatched = {},
                playbackPositionMsProvider = playbackPositionMsProvider,
                playbackReadyWindowCountProvider = playbackReadyWindowCountProvider,
                windowDecodeEnabled = windowDecodeEnabled,
                shouldPause = shouldPause,
                pauseReasonProvider = pauseReasonProvider,
                shouldCancel = shouldCancel,
                requireWorkspaceAvailable = run::requireOpen,
            )
            val descriptor = executionRequest.toExecutionDescriptor(
                runId = currentRunId,
                processGeneration = currentProcessGeneration,
                backendPolicy = executionRequest.backendPolicy,
                sourceDiagnostics = input.sourceDiagnostics,
                song = input.song,
                initialPlaybackPositionMs = initialPlaybackPositionMs,
                initialPlaybackReadyWindowCount = initialPlaybackReadyWindowCount,
            )
            var latestEventSequence = 0L
            hostStartAttempted = true
            val hosted = executionHost.start(
                SourceSeparationExecutionHostRequest(
                    descriptor = descriptor,
                    executionRequest = executionRequest,
                    onEvent = { event ->
                        synchronized(eventLock) {
                            require(event.protocolVersion ==
                                SOURCE_SEPARATION_EXECUTION_PROTOCOL_VERSION
                            ) {
                                "Execution host event uses an unsupported protocol version."
                            }
                            require(event.runId == currentRunId &&
                                event.processGeneration == currentProcessGeneration
                            ) {
                                "Execution host event targets a stale run or generation."
                            }
                            require(event.sequence > latestEventSequence) {
                                "Execution host event sequence is stale."
                            }
                            when (val payload = event.payload) {
                                is SourceSeparationExecutionHostEventPayload.Accepted -> {
                                    require(payload.descriptor == descriptor) {
                                        "Execution host accepted a different descriptor."
                                    }
                                    hostRunAccepted = true
                                }

                                is SourceSeparationExecutionHostEventPayload.Progress ->
                                    onProgress(payload.progress.toMdxRangeProgress())

                                is SourceSeparationExecutionHostEventPayload.Prepared ->
                                    onPrepared(coordinator.updatePreparation(
                                        run,
                                        payload.preparation.toMdxRangePreparation(
                                            run.entryDirectory,
                                        ),
                                    ))

                                is SourceSeparationExecutionHostEventPayload
                                    .SegmentStateChanged ->
                                    coordinator.updateSegmentState(
                                        run,
                                        payload.segmentIndex,
                                        payload.state,
                                    )

                                is SourceSeparationExecutionHostEventPayload
                                    .GpuFallbackLatched ->
                                    coordinator.latchGpuFallback(run, payload.latch)

                                is SourceSeparationExecutionHostEventPayload.Completed,
                                is SourceSeparationExecutionHostEventPayload.Paused,
                                is SourceSeparationExecutionHostEventPayload.Canceled,
                                is SourceSeparationExecutionHostEventPayload.Failed,
                                -> Unit
                            }
                            executionHostEventSink(event)
                            latestEventSequence = event.sequence
                        }
                    },
                )
            )
            synchronized(eventLock) {
                check(hostRunAccepted) { "Execution host completed without accepting the run." }
            }
            if (shouldCancel()) throw CancellationException("Source separation canceled.")
            SourceSeparationModelAwareEngineResult.Completed(
                manifest = coordinator.complete(run, hosted.result),
                result = hosted.result,
                preflightElapsedMs = preflightElapsedMs,
                hostDiagnostics = hosted.diagnostics,
            )
        } catch (error: SourceSeparationPausedException) {
            terminalError = error
            coordinator.pause(run, pauseReasonProvider())
            throw error
        } catch (error: CancellationException) {
            terminalError = error
            coordinator.cancel(run, error)
            throw error
        } catch (error: Throwable) {
            terminalError = error
            coordinator.fail(run, error)
            throw error
        } finally {
            if (hostStartAttempted) {
                val closeResult = executionHost.closeRun(
                    runId,
                    processGeneration,
                )
                check(
                    closeResult == SourceSeparationExecutionHostControlResult.Applied ||
                        closeResult == SourceSeparationExecutionHostControlResult.NoActiveRun ||
                        (terminalError != null &&
                            closeResult == SourceSeparationExecutionHostControlResult.HostClosed)
                ) {
                    "Execution host did not close the terminal run: $closeResult"
                }
            }
        }
    }

    companion object {
        fun createProduction(
            context: Context,
            presetRepository: SourceSeparationPresetRepository,
        ): SourceSeparationModelAwareEngine {
            val appContext = context.applicationContext
            val store = SourceSeparationCacheStore(
                AndroidSourceSeparationCacheRootProvider(appContext).resolveRoot(),
            ).also(SourceSeparationCacheStore::recover)
            val cacheRepository = SourceSeparationModelAwareCacheRepository(
                store = store,
                modelAvailability = SourceSeparationPresetCacheAvailabilityProvider(
                    presetRepository,
                ),
            )
            return createProduction(
                context = appContext,
                presetRepository = presetRepository,
                coordinator = SourceSeparationCacheRunCoordinator(store, cacheRepository),
            )
        }

        fun createBoundRemotePrototype(
            context: Context,
            presetRepository: SourceSeparationPresetRepository,
            coordinator: SourceSeparationCacheRunCoordinator,
            executionHost: SourceSeparationExecutionHost =
                BoundRemoteSourceSeparationExecutionHost(context.applicationContext),
            executionBackendPolicy: SourceSeparationExecutionBackendPolicy =
                SourceSeparationExecutionBackendPolicy.Auto,
            executionHostEventSink: (SourceSeparationExecutionHostEvent) -> Unit = {},
            processingOwnershipLease: SourceSeparationProcessingOwnershipHandoff
                .SourceSeparationProcessingOwnershipLease? = null,
        ): SourceSeparationModelAwareEngine {
            val appContext = context.applicationContext
            return SourceSeparationModelAwareEngine(
                activeModelResolver = presetRepository::resolveTrustedActiveCacheModel,
                preflightResolver = AndroidSourceSeparationModelAwarePreflightResolver(appContext),
                coordinator = coordinator,
                rangeExecutor = MdxSourceSeparationModelAwareRangeExecutor(appContext),
                executionHost = executionHost,
                executionBackendPolicy = executionBackendPolicy,
                constructionGate = { true },
                executionHostEventSink = executionHostEventSink,
                processingOwnershipLease = processingOwnershipLease,
            )
        }

        fun createIndependentForegroundPrototype(
            context: Context,
            presetRepository: SourceSeparationPresetRepository,
            coordinator: SourceSeparationCacheRunCoordinator,
            executionHost: SourceSeparationExecutionHost =
                BoundRemoteSourceSeparationExecutionHost(
                    context.applicationContext,
                    foregroundPolicy = SourceSeparationRemoteForegroundPolicy.ManualFullSong,
                ),
            executionBackendPolicy: SourceSeparationExecutionBackendPolicy =
                SourceSeparationExecutionBackendPolicy.Auto,
            executionHostEventSink: (SourceSeparationExecutionHostEvent) -> Unit = {},
            processingOwnershipLease: SourceSeparationProcessingOwnershipHandoff
                .SourceSeparationProcessingOwnershipLease? = null,
        ): SourceSeparationModelAwareEngine = createBoundRemotePrototype(
            context = context,
            presetRepository = presetRepository,
            coordinator = coordinator,
            executionHost = executionHost,
            executionBackendPolicy = executionBackendPolicy,
            executionHostEventSink = executionHostEventSink,
            processingOwnershipLease = processingOwnershipLease,
        )

        fun createProduction(
            context: Context,
            presetRepository: SourceSeparationPresetRepository,
            coordinator: SourceSeparationCacheRunCoordinator,
        ): SourceSeparationModelAwareEngine {
            val appContext = context.applicationContext
            return SourceSeparationModelAwareEngine(
                activeModelResolver = presetRepository::resolveTrustedActiveCacheModel,
                preflightResolver = AndroidSourceSeparationModelAwarePreflightResolver(appContext),
                coordinator = coordinator,
                rangeExecutor = MdxSourceSeparationModelAwareRangeExecutor(appContext),
                constructionGate = { true },
            )
        }

        private const val DEFAULT_PLAYBACK_READY_WINDOW_COUNT = 2
    }
}

internal fun interface SourceSeparationModelAwarePreflightResolver {
    fun resolve(
        sourceUri: String,
        shouldCancel: () -> Boolean,
    ): SourceSeparationCacheSourcePreflight
}

internal class AndroidSourceSeparationModelAwarePreflightResolver(
    context: Context,
) : SourceSeparationModelAwarePreflightResolver {
    private val delegate = SourceSeparationCacheSourceIdentityResolver(context)

    override fun resolve(
        sourceUri: String,
        shouldCancel: () -> Boolean,
    ): SourceSeparationCacheSourcePreflight = delegate.resolve(
        uri = Uri.parse(sourceUri),
        shouldCancel = shouldCancel,
    )
}

internal fun interface SourceSeparationModelAwareRangeExecutor {
    fun separate(
        request: SourceSeparationModelAwareExecutionRequest,
    ): MdxRangeSeparationResult
}

internal data class SourceSeparationModelAwareExecutionRequest(
    val sourceUri: String,
    val displayName: String,
    val model: SourceSeparationResolvedCacheModel,
    val workspace: SourceSeparationModelAwareExecutionWorkspace,
    val runtimeSettings: MdxRuntimeSettings,
    val backendPolicy: SourceSeparationExecutionBackendPolicy,
    val runClass: SourceSeparationExecutionRunClass,
    val backgroundPolicy: SourceSeparationBackgroundPolicy,
    val tryGpu: Boolean,
    val gpuRuntimeIdentity: SourceSeparationAdmittedGpuRuntimeIdentity?,
    val gpuFallbackLatch: SourceSeparationGpuFallbackLatch?,
    val onProgress: (MdxRangeProgress) -> Unit,
    val onPrepared: (com.mardous.booming.separation.model.MdxRangePreparation) -> Unit,
    val onSegmentStateChanged: (Int, SourceSeparationSegmentState) -> Unit,
    val onGpuFallbackLatched: (SourceSeparationGpuFallbackLatch) -> Unit,
    val playbackPositionMsProvider: () -> Long?,
    val playbackReadyWindowCountProvider: () -> Int,
    val windowDecodeEnabled: Boolean,
    val shouldPause: () -> Boolean,
    val pauseReasonProvider: () -> SourceSeparationPauseReason,
    val shouldCancel: () -> Boolean,
    val requireWorkspaceAvailable: () -> Unit = {},
) {
    init {
        require(tryGpu == (gpuRuntimeIdentity != null)) {
            "Execution GPU preference and admitted runtime identity disagree."
        }
        require(backgroundPolicy == runClass.backgroundPolicy) {
            "Execution background policy does not match its run class."
        }
        require(tryGpu || gpuFallbackLatch == null) {
            "A CPU-only execution cannot carry a GPU fallback latch."
        }
        val expectedBackendPolicy = if (tryGpu && gpuFallbackLatch == null) {
            SourceSeparationExecutionBackendPolicy.Auto
        } else {
            SourceSeparationExecutionBackendPolicy.Cpu
        }
        require(backendPolicy == expectedBackendPolicy) {
            "Execution backend policy does not match its admitted GPU state."
        }
        require(!tryGpu || gpuFallbackLatch != null ||
            gpuRuntimeIdentity == BOUNDED_GPU_RUNTIME_IDENTITY
        ) {
            "Execution GPU runtime identity is not supported by this build."
        }
    }
}

internal enum class SourceSeparationAdmittedRuntimeResumeMode {
    CpuOnly,
    BoundedGpu,
    LatchedCpuFallback,
}

internal fun SourceSeparationCacheAdmittedRuntimePolicy.requireSupportedByCurrentBuild():
        SourceSeparationAdmittedRuntimeResumeMode = when {
    !tryGpu -> SourceSeparationAdmittedRuntimeResumeMode.CpuOnly
    gpuFallbackLatch != null ->
        SourceSeparationAdmittedRuntimeResumeMode.LatchedCpuFallback
    gpuRuntimeIdentity == BOUNDED_GPU_RUNTIME_IDENTITY ->
        SourceSeparationAdmittedRuntimeResumeMode.BoundedGpu
    else -> throw SourceSeparationAdmittedGpuRuntimeMismatchException(
        admitted = requireNotNull(gpuRuntimeIdentity),
        supported = BOUNDED_GPU_RUNTIME_IDENTITY,
    )
}

internal class SourceSeparationAdmittedGpuRuntimeMismatchException(
    val admitted: SourceSeparationAdmittedGpuRuntimeIdentity,
    val supported: SourceSeparationAdmittedGpuRuntimeIdentity,
) : IllegalStateException(
    "The admitted GPU runtime does not match this build.",
)

private val BOUNDED_GPU_RUNTIME_IDENTITY = SourceSeparationAdmittedGpuRuntimeIdentity(
    profileId = MdxLiteRtBoundedGpuContract.PROFILE_ID,
    artifactVersion = MdxLiteRtBoundedGpuContract.ARTIFACT_VERSION,
    capabilitySchemaVersion = MdxLiteRtBoundedGpuContract.CAPABILITY_SCHEMA_VERSION,
    backend = MdxLiteRtBoundedGpuContract.BACKEND,
    precision = MdxLiteRtBoundedGpuContract.PRECISION,
    kernelBatchSize = MdxLiteRtBoundedGpuContract.KERNEL_BATCH_SIZE,
    commandQueueWindowSize = MdxLiteRtBoundedGpuContract.COMMAND_QUEUE_WINDOW_SIZE,
)

private fun SourceSeparationExecutionBackendPolicy.toAdmittedRuntimePolicy() =
    when (this) {
        SourceSeparationExecutionBackendPolicy.Auto ->
            SourceSeparationCacheAdmittedRuntimePolicy(
                tryGpu = true,
                gpuRuntimeIdentity = BOUNDED_GPU_RUNTIME_IDENTITY,
                gpuFallbackLatch = null,
            )
        SourceSeparationExecutionBackendPolicy.Cpu ->
            SourceSeparationCacheAdmittedRuntimePolicy(
                tryGpu = false,
                gpuRuntimeIdentity = null,
                gpuFallbackLatch = null,
            )
    }

internal data class SourceSeparationModelAwareExecutionWorkspace(
    val identity: com.mardous.booming.separation.cache.v2.SourceSeparationCacheIdentity,
    val contract: com.mardous.booming.separation.cache.v2.SourceSeparationCacheContractSnapshot,
    val entryDirectory: java.io.File,
    val workDirectory: java.io.File,
    val segmentsDirectory: java.io.File,
    val resumeState: com.mardous.booming.separation.model.MdxRangeResumeState?,
) {
    init {
        require(identity.cacheKey == entryDirectory.name) {
            "Execution workspace directory does not match its cache identity."
        }
        require(workDirectory.parentFile == entryDirectory) {
            "Execution work directory is outside its cache entry."
        }
        require(segmentsDirectory.parentFile == entryDirectory) {
            "Execution segment directory is outside its cache entry."
        }
    }

    companion object {
        fun from(
            run: SourceSeparationModelAwareCacheRun,
        ) = SourceSeparationModelAwareExecutionWorkspace(
            identity = run.identity,
            contract = run.contract,
            entryDirectory = run.entryDirectory,
            workDirectory = run.workDirectory,
            segmentsDirectory = run.segmentsDirectory,
            resumeState = run.resumeState,
        )
    }
}

internal data class SourceSeparationModelAwareSongInput(
    val sourceUri: String,
    val displayName: String,
    val song: SourceSeparationCacheSongLocator,
    val sourceDiagnostics: SourceSeparationCacheSourceDiagnostics,
) {
    companion object {
        fun from(song: Song): SourceSeparationModelAwareSongInput {
            require(song != Song.emptySong) { "Cannot separate an empty song." }
            return SourceSeparationModelAwareSongInput(
                sourceUri = song.uri.toString(),
                displayName = song.fileName,
                song = SourceSeparationCacheSongLocator(
                    songId = song.id,
                    mediaUri = song.uri.toString(),
                    filePath = song.data,
                    title = song.title,
                    artist = song.artistName,
                    album = song.albumName,
                ),
                sourceDiagnostics = SourceSeparationCacheSourceDiagnostics(
                    fileSize = song.size,
                    rawDateModified = song.rawDateModified,
                    durationMs = song.duration,
                ),
            )
        }
    }
}

sealed class SourceSeparationModelAwareEngineResult {
    data class Completed(
        val manifest: SourceSeparationCacheManifest,
        val result: MdxRangeSeparationResult,
        val preflightElapsedMs: Long,
        val hostDiagnostics: SourceSeparationExecutionHostDiagnostics,
    ) : SourceSeparationModelAwareEngineResult()

    data class AlreadyCompleted(
        val manifest: SourceSeparationCacheManifest,
        val preflightElapsedMs: Long,
    ) : SourceSeparationModelAwareEngineResult()

    data class Busy(
        val cacheKey: String,
        val preflightElapsedMs: Long,
    ) : SourceSeparationModelAwareEngineResult()

    data object ActiveModelUnavailable : SourceSeparationModelAwareEngineResult()
}
