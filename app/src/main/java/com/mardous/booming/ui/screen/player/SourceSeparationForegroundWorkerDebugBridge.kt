package com.mardous.booming.ui.screen.player

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
        autoStart: Boolean?,
        modeName: String?,
        blend: Float?,
    ): Boolean {
        val viewModel = viewModelRef?.get() ?: return false
        autoStart?.let(viewModel::setSourceSeparationAutoStartEnabled)
        modeName?.let(::blendModeFromName)?.let(viewModel::setSourceSeparationBlendMode)
        blend?.let(viewModel::setSourceSeparationBlend)
        return true
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
