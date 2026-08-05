package com.mardous.booming.ui.screen.player

internal object SourceSeparationCacheRefreshPolicy {
    fun canApply(
        requestGeneration: Long,
        latestGeneration: Long,
        requestedSongId: Long,
        currentSongId: Long,
        selectionMatches: Boolean,
    ): Boolean {
        return requestGeneration == latestGeneration &&
                requestedSongId == currentSongId &&
                selectionMatches
    }
}
