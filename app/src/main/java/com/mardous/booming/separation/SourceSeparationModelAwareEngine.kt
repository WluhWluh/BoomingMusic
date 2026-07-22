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
import java.util.concurrent.CancellationException

/** Runs one contract-bound source-separation request through the v2 cache and runtime stack. */
internal class SourceSeparationModelAwareEngine(
    private val activeModelResolver: () -> SourceSeparationResolvedCacheModel?,
    private val preflightResolver: SourceSeparationModelAwarePreflightResolver,
    private val coordinator: SourceSeparationCacheRunCoordinator,
    private val rangeExecutor: SourceSeparationModelAwareRangeExecutor,
    private val constructionGate: () -> Boolean,
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
        return try {
            if (shouldCancel()) throw CancellationException("Source separation canceled.")
            val result = rangeExecutor.separate(
                SourceSeparationModelAwareExecutionRequest(
                    sourceUri = input.sourceUri,
                    displayName = input.displayName,
                    model = model,
                    run = run,
                    runtimeSettings = runtimeSettings,
                    onProgress = onProgress,
                    onPrepared = { preparation ->
                        onPrepared(coordinator.updatePreparation(run, preparation))
                    },
                    onSegmentStateChanged = { index, state ->
                        coordinator.updateSegmentState(run, index, state)
                    },
                    playbackPositionMsProvider = playbackPositionMsProvider,
                    playbackReadyWindowCountProvider = playbackReadyWindowCountProvider,
                    windowDecodeEnabled = windowDecodeEnabled,
                    shouldPause = shouldPause,
                    shouldCancel = shouldCancel,
                )
            )
            if (shouldCancel()) throw CancellationException("Source separation canceled.")
            SourceSeparationModelAwareEngineResult.Completed(
                manifest = coordinator.complete(run, result),
                result = result,
                preflightElapsedMs = preflightElapsedMs,
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
