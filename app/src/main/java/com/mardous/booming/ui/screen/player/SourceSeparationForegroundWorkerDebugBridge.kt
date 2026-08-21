package com.mardous.booming.ui.screen.player

import com.mardous.booming.data.model.Song
import com.mardous.booming.separation.SourceSeparationCompressionFormat
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheMutationResult
import org.koin.java.KoinJavaComponent.get
import java.lang.ref.WeakReference

object SourceSeparationForegroundWorkerDebugBridge {
    private var viewModelRef: WeakReference<PlayerViewModel>? = null

    fun register(viewModel: PlayerViewModel) {
        viewModelRef = WeakReference(viewModel)
    }

    fun unregister(viewModel: PlayerViewModel) {
        if (viewModelRef?.get() === viewModel) {
            viewModelRef = null
        }
    }

    fun startCurrentSong(): Boolean {
        viewModelRef?.get()?.let { viewModel ->
            viewModel.startSourceSeparationForCurrentSong()
            return true
        }
        return runCatching {
            worker().startCurrentSong()
        }.getOrDefault(false)
    }

    fun setPlaybackEnabled(enabled: Boolean, blend: Float?): Boolean {
        val viewModel = viewModelRef?.get() ?: return false
        viewModel.setSourceSeparationPlaybackEnabled(enabled, blend)
        return true
    }

    suspend fun preStartSong(song: Song, readyWindowCount: Int): Boolean =
        worker().preStartSong(song, readyWindowCount)

    fun pause(): Boolean {
        viewModelRef?.get()?.let { viewModel ->
            viewModel.pauseSourceSeparation()
            return true
        }
        return runCatching {
            val worker = worker()
            worker.pauseCurrentSong(worker.playbackStateFlow.value.song)
            true
        }.getOrDefault(false)
    }

    fun cancel(): Boolean {
        viewModelRef?.get()?.let { viewModel ->
            viewModel.cancelSourceSeparation()
            return true
        }
        return runCatching {
            worker().cancel()
            true
        }.getOrDefault(false)
    }

    fun configure(
        autoStart: Boolean? = null,
        modeName: String? = null,
        blend: Float? = null,
        autoFlac: Boolean? = null,
        compressionFormatName: String? = null,
        gpuEnabled: Boolean? = null,
        windowDecode: Boolean? = null,
        snackbarProgress: Boolean? = null,
        snackbarMessages: Boolean? = null,
        mixedOutputPrerollMs: Long? = null,
        readyWindowCount: Int? = null,
        autoCacheCleanup: Boolean? = null,
        partialCacheLimit: Int? = null,
        completedCacheLimit: Int? = null,
    ): Boolean {
        val mode = modeName?.let { name ->
            requireNotNull(blendModeFromName(name)) { "Unknown source-separation mix mode '$name'." }
        }
        val viewModel = viewModelRef?.get() ?: return false
        autoFlac?.let(viewModel::setSourceSeparationAutoFlacCompressionEnabled)
        compressionFormatName?.let { name ->
            val format = SourceSeparationCompressionFormat.entries.firstOrNull {
                it.name.equals(name, ignoreCase = true) ||
                        it.preferenceValue().equals(name, ignoreCase = true)
            } ?: error("Unknown source-separation compression format '$name'.")
            viewModel.setSourceSeparationCompressionFormat(format)
        }
        gpuEnabled?.let(viewModel::setSourceSeparationGpuEnabled)
        windowDecode?.let(viewModel::setSourceSeparationWindowDecodeEnabled)
        snackbarProgress?.let(viewModel::setSourceSeparationShowSnackbarProgressEnabled)
        snackbarMessages?.let(viewModel::setSourceSeparationShowSnackbarMessagesEnabled)
        mixedOutputPrerollMs?.let(viewModel::setSourceSeparationMixedOutputPrerollMs)
        readyWindowCount?.let(viewModel::setSourceSeparationPlaybackReadyWindowCount)
        autoCacheCleanup?.let(viewModel::setSourceSeparationAutoCacheCleanupEnabled)
        partialCacheLimit?.let(viewModel::setSourceSeparationAutoCacheCleanupPartialLimit)
        completedCacheLimit?.let(viewModel::setSourceSeparationAutoCacheCleanupCompletedLimit)
        if (autoStart == false) viewModel.setSourceSeparationAutoStartEnabled(false)
        mode?.let(viewModel::setSourceSeparationBlendMode)
        if (autoStart == true) viewModel.setSourceSeparationAutoStartEnabled(true)
        blend?.let(viewModel::setSourceSeparationBlend)
        return true
    }

    fun setBlend(blend: Float, persist: Boolean): Boolean {
        val viewModel = viewModelRef?.get() ?: return false
        if (persist) {
            viewModel.setSourceSeparationBlend(blend)
        } else {
            viewModel.previewSourceSeparationBlend(blend)
        }
        return true
    }

    fun setStemGains(gainsByStemId: Map<String, Float>, persist: Boolean): Boolean {
        val viewModel = viewModelRef?.get() ?: return false
        return if (persist) {
            viewModel.setSourceSeparationStemGains(gainsByStemId)
        } else {
            viewModel.previewSourceSeparationStemGains(gainsByStemId)
        }
    }

    suspend fun prepareCacheForManualDelete(cacheKey: String): Boolean {
        val viewModel = viewModelRef?.get() ?: return false
        viewModel.prepareSourceSeparationCacheForManualDelete(cacheKey)
        return true
    }

    suspend fun handleCacheManualDeleteResult(
        cacheKey: String,
        result: SourceSeparationCacheMutationResult,
    ): Boolean {
        val viewModel = viewModelRef?.get() ?: return false
        viewModel.handleSourceSeparationCacheManualDeleteResult(cacheKey, result)
        return true
    }

    suspend fun prepareCacheForManualDeleteFallback(
        cacheKey: String,
        isCurrentCache: Boolean,
    ) {
        val worker = worker()
        val song = worker.playbackStateFlow.value.song
        if (isCurrentCache) {
            worker.suppressAndPauseSong(song.id)
        }
        val preflightIdentity = if (isCurrentCache && song != Song.emptySong) {
            SourceSeparationWorkerRequestIdentity.from(
                song,
                worker.executionSelectionStateFlow.value,
            )
        } else {
            null
        }
        val canceled = worker.cancelForCacheDeletion(
            cacheKey = cacheKey,
            preflightIdentity = preflightIdentity,
        )
        if (isCurrentCache && !canceled) {
            worker.cancel()
        }
    }

    fun pauseForModelSupersession() {
        worker().pauseForActiveModelSupersession()
    }

    fun isModelArtifactInUse(sha256: String): Boolean =
        worker().isModelArtifactInUse(sha256)

    fun requestAutomaticPrune() {
        worker().requestAutomaticPrune()
    }

    fun currentSong(): Song = worker().playbackStateFlow.value.song

    fun clearStatusIfNotRunning() {
        worker().clearStatusIfNotRunning()
    }

    fun status(): String {
        return runCatching {
            val workerStatus = worker().debugStatus()
            val viewModelStatus = viewModelRef?.get()?.sourceSeparationDebugStatus()
            if (viewModelStatus == null) {
                "$workerStatus viewModel=null"
            } else {
                viewModelStatus
            }
        }.getOrElse { error ->
            "workerStatusError=${error::class.java.simpleName}:${error.message}"
        }
    }

    fun windowSamples(): String {
        return runCatching {
            worker().windowSamples()
        }.getOrElse { error ->
            "workerSamplesError=${error::class.java.simpleName}:${error.message}"
        }
    }

    fun clearWindowSamples(): Boolean {
        return runCatching {
            worker().clearWindowSamples()
            true
        }.getOrDefault(false)
    }

    private fun blendModeFromName(name: String): SourceSeparationBlendMode? {
        return when (name.trim().lowercase()) {
            "off", "original", "disabled" -> SourceSeparationBlendMode.Off
            "global", "globalblend", "global_blend" -> SourceSeparationBlendMode.Global
            "persong", "per_song", "per-song", "remember" -> SourceSeparationBlendMode.PerSong
            else -> null
        }
    }

    private fun worker(): SourceSeparationForegroundWorkerCoordinator =
        get(SourceSeparationForegroundWorkerCoordinator::class.java)
}
