package com.mardous.booming.separation

import android.content.Context
import android.net.Uri
import com.mardous.booming.data.model.Song
import com.mardous.booming.separation.cache.SourceSeparationSegmentState
import com.mardous.booming.separation.cache.v2.AndroidSourceSeparationCacheRootProvider
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
import com.mardous.booming.separation.cache.v2.resolveActiveCacheModel
import com.mardous.booming.separation.model.MdxRangeProgress
import com.mardous.booming.separation.model.MdxRangeSeparationResult
import com.mardous.booming.separation.model.MdxRuntimeSettings
import com.mardous.booming.separation.model.preset.SourceSeparationPresetRepository
import com.mardous.booming.separation.process.InProcessSourceSeparationExecutionHost
import com.mardous.booming.separation.process.SOURCE_SEPARATION_EXECUTION_PROTOCOL_VERSION
import com.mardous.booming.separation.process.SourceSeparationExecutionHost
import com.mardous.booming.separation.process.SourceSeparationExecutionHostControlResult
import com.mardous.booming.separation.process.SourceSeparationExecutionHostDiagnostics
import com.mardous.booming.separation.process.SourceSeparationExecutionHostEvent
import com.mardous.booming.separation.process.SourceSeparationExecutionHostEventPayload
import com.mardous.booming.separation.process.SourceSeparationExecutionHostRequest
import com.mardous.booming.separation.process.toExecutionDescriptor
import com.mardous.booming.separation.process.toMdxRangePreparation
import com.mardous.booming.separation.process.toMdxRangeProgress
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
    private val constructionGate: () -> Boolean,
    private val runIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val executionHostEventSink: (SourceSeparationExecutionHostEvent) -> Unit = {},
) {
    fun separate(
        input: SourceSeparationModelAwareSongInput,
        runtimeSettings: MdxRuntimeSettings = MdxRuntimeSettings(),
        onProgress: (MdxRangeProgress) -> Unit = {},
        playbackPositionMsProvider: () -> Long? = { null },
        playbackReadyWindowCountProvider: () -> Int = { DEFAULT_PLAYBACK_READY_WINDOW_COUNT },
        windowDecodeEnabled: Boolean = true,
        onPrepared: (SourceSeparationCacheManifest) -> Unit = {},
        shouldPause: () -> Boolean = { false },
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
            onProgress = onProgress,
            playbackPositionMsProvider = playbackPositionMsProvider,
            playbackReadyWindowCountProvider = playbackReadyWindowCountProvider,
            windowDecodeEnabled = windowDecodeEnabled,
            onPrepared = onPrepared,
            shouldPause = shouldPause,
            shouldCancel = shouldCancel,
        )
    }

    fun separateResolved(
        input: SourceSeparationModelAwareSongInput,
        model: SourceSeparationResolvedCacheModel,
        preflight: SourceSeparationCacheSourcePreflight,
        runtimeSettings: MdxRuntimeSettings = MdxRuntimeSettings(),
        onProgress: (MdxRangeProgress) -> Unit = {},
        playbackPositionMsProvider: () -> Long? = { null },
        playbackReadyWindowCountProvider: () -> Int = { DEFAULT_PLAYBACK_READY_WINDOW_COUNT },
        windowDecodeEnabled: Boolean = true,
        onPrepared: (SourceSeparationCacheManifest) -> Unit = {},
        shouldPause: () -> Boolean = { false },
        shouldCancel: () -> Boolean = { false },
    ): SourceSeparationModelAwareEngineResult {
        check(constructionGate()) {
            "The model-aware LiteRT engine is disabled by its construction gate."
        }
        val identity = model.contract.identity(preflight.identity)
        val runRequest = SourceSeparationCacheRunRequest(
            identity = identity,
            contract = model.contract,
            song = input.song,
            sourceDiagnostics = input.sourceDiagnostics,
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
                onProgress = onProgress,
                playbackPositionMsProvider = playbackPositionMsProvider,
                playbackReadyWindowCountProvider = playbackReadyWindowCountProvider,
                windowDecodeEnabled = windowDecodeEnabled,
                onPrepared = onPrepared,
                shouldPause = shouldPause,
                shouldCancel = shouldCancel,
            )
        }
    }

    private fun execute(
        input: SourceSeparationModelAwareSongInput,
        model: SourceSeparationResolvedCacheModel,
        run: SourceSeparationModelAwareCacheRun,
        preflightElapsedMs: Long,
        runtimeSettings: MdxRuntimeSettings,
        onProgress: (MdxRangeProgress) -> Unit,
        playbackPositionMsProvider: () -> Long?,
        playbackReadyWindowCountProvider: () -> Int,
        windowDecodeEnabled: Boolean,
        onPrepared: (SourceSeparationCacheManifest) -> Unit,
        shouldPause: () -> Boolean,
        shouldCancel: () -> Boolean,
    ): SourceSeparationModelAwareEngineResult {
        val runId = runIdFactory().also {
            require(it.isNotBlank()) { "Execution run ID factory returned an empty ID." }
        }
        val processGeneration = executionHost.processGeneration
        var hostRunAccepted = false
        return try {
            if (shouldCancel()) throw CancellationException("Source separation canceled.")
            val initialPlaybackPositionMs = playbackPositionMsProvider()
                ?.takeIf { it >= 0L }
            val initialPlaybackReadyWindowCount = playbackReadyWindowCountProvider()
                .coerceAtLeast(1)
            val executionRequest = SourceSeparationModelAwareExecutionRequest(
                sourceUri = input.sourceUri,
                displayName = input.displayName,
                model = model,
                run = run,
                runtimeSettings = runtimeSettings,
                onProgress = {},
                onPrepared = {},
                onSegmentStateChanged = { _, _ -> },
                playbackPositionMsProvider = playbackPositionMsProvider,
                playbackReadyWindowCountProvider = playbackReadyWindowCountProvider,
                windowDecodeEnabled = windowDecodeEnabled,
                shouldPause = shouldPause,
                shouldCancel = shouldCancel,
            )
            val descriptor = executionRequest.toExecutionDescriptor(
                runId = runId,
                processGeneration = processGeneration,
                sourceDiagnostics = input.sourceDiagnostics,
                initialPlaybackPositionMs = initialPlaybackPositionMs,
                initialPlaybackReadyWindowCount = initialPlaybackReadyWindowCount,
            )
            var latestEventSequence = 0L
            val hosted = executionHost.start(
                SourceSeparationExecutionHostRequest(
                    descriptor = descriptor,
                    executionRequest = executionRequest,
                    onEvent = { event ->
                        require(event.protocolVersion ==
                            SOURCE_SEPARATION_EXECUTION_PROTOCOL_VERSION
                        ) {
                            "Execution host event uses an unsupported protocol version."
                        }
                        require(event.runId == runId &&
                            event.processGeneration == processGeneration
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
                                onPrepared(
                                    coordinator.updatePreparation(
                                        run,
                                        payload.preparation.toMdxRangePreparation(
                                            run.entryDirectory,
                                        ),
                                    )
                                )

                            is SourceSeparationExecutionHostEventPayload.SegmentStateChanged ->
                                coordinator.updateSegmentState(
                                    run,
                                    payload.segmentIndex,
                                    payload.state,
                                )

                            is SourceSeparationExecutionHostEventPayload.Completed,
                            is SourceSeparationExecutionHostEventPayload.Paused,
                            is SourceSeparationExecutionHostEventPayload.Canceled,
                            is SourceSeparationExecutionHostEventPayload.Failed,
                            -> Unit
                        }
                        executionHostEventSink(event)
                        latestEventSequence = event.sequence
                    },
                )
            )
            if (shouldCancel()) throw CancellationException("Source separation canceled.")
            SourceSeparationModelAwareEngineResult.Completed(
                manifest = coordinator.complete(run, hosted.result),
                result = hosted.result,
                preflightElapsedMs = preflightElapsedMs,
                hostDiagnostics = hosted.diagnostics,
            )
        } catch (error: SourceSeparationPausedException) {
            coordinator.pause(run)
            throw error
        } catch (error: CancellationException) {
            coordinator.cancel(run, error)
            throw error
        } catch (error: Throwable) {
            coordinator.fail(run, error)
            throw error
        } finally {
            if (hostRunAccepted) {
                check(
                    executionHost.closeRun(runId, processGeneration) ==
                        SourceSeparationExecutionHostControlResult.Applied
                ) {
                    "Execution host did not close the terminal run."
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

        fun createProduction(
            context: Context,
            presetRepository: SourceSeparationPresetRepository,
            coordinator: SourceSeparationCacheRunCoordinator,
        ): SourceSeparationModelAwareEngine {
            val appContext = context.applicationContext
            return SourceSeparationModelAwareEngine(
                activeModelResolver = presetRepository::resolveActiveCacheModel,
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
    val run: SourceSeparationModelAwareCacheRun,
    val runtimeSettings: MdxRuntimeSettings,
    val onProgress: (MdxRangeProgress) -> Unit,
    val onPrepared: (com.mardous.booming.separation.model.MdxRangePreparation) -> Unit,
    val onSegmentStateChanged: (Int, SourceSeparationSegmentState) -> Unit,
    val playbackPositionMsProvider: () -> Long?,
    val playbackReadyWindowCountProvider: () -> Int,
    val windowDecodeEnabled: Boolean,
    val shouldPause: () -> Boolean,
    val shouldCancel: () -> Boolean,
)

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
