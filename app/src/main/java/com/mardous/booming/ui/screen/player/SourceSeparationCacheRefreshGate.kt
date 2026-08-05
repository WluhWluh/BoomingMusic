package com.mardous.booming.ui.screen.player

internal class SourceSeparationCacheRefreshGate {
    private val lock = Any()
    private var latestGeneration = 0L

    fun nextGeneration(): Long = synchronized(lock) {
        ++latestGeneration
    }

    fun publishIfCurrent(
        requestGeneration: Long,
        requestedSongId: Long,
        currentSongId: Long,
        selectionMatches: Boolean,
        publish: () -> Unit,
    ): Boolean = synchronized(lock) {
        val canApply = SourceSeparationCacheRefreshPolicy.canApply(
            requestGeneration = requestGeneration,
            latestGeneration = latestGeneration,
            requestedSongId = requestedSongId,
            currentSongId = currentSongId,
            selectionMatches = selectionMatches,
        )
        if (canApply) {
            publish()
        }
        canApply
    }
}
