package com.mardous.booming.ui.screen.player

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
        return viewModelRef?.get()?.sourceSeparationDebugStatus() ?: "viewModel=null"
    }

    fun windowSamples(): String {
        return viewModelRef?.get()?.sourceSeparationDebugWindowSamples() ?: "viewModel=null"
    }

    fun clearWindowSamples(): Boolean {
        return viewModelRef?.get()?.run {
            clearSourceSeparationDebugWindowSamples()
            true
        } == true
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
