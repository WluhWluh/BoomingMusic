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
        return viewModelRef?.get()?.run {
            startSourceSeparationForCurrentSong()
            true
        } == true
    }

    fun pause(): Boolean {
        return viewModelRef?.get()?.run {
            pauseSourceSeparation()
            true
        } == true
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
            val workerStatus = get<SourceSeparationForegroundWorkerCoordinator>(
                SourceSeparationForegroundWorkerCoordinator::class.java,
            ).debugStatus()
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
            get<SourceSeparationForegroundWorkerCoordinator>(
                SourceSeparationForegroundWorkerCoordinator::class.java,
            ).windowSamples()
        }.getOrElse { error ->
            "workerSamplesError=${error::class.java.simpleName}:${error.message}"
        }
    }

    fun clearWindowSamples(): Boolean {
        return runCatching {
            get<SourceSeparationForegroundWorkerCoordinator>(
                SourceSeparationForegroundWorkerCoordinator::class.java,
            ).clearWindowSamples()
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
}
