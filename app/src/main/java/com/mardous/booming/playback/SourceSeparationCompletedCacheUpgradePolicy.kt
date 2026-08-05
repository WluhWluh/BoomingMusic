package com.mardous.booming.playback

internal object SourceSeparationCompletedCacheUpgradePolicy {
    fun shouldDeferAutomaticUpgrade(
        hasActiveSession: Boolean,
        playWhenReady: Boolean,
        hasPlayIntent: Boolean,
        isProcessing: Boolean,
    ): Boolean {
        if (!hasActiveSession) return false
        return playWhenReady || (hasPlayIntent && !isProcessing)
    }
}
