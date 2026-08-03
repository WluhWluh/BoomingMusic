package com.mardous.booming.playback

internal object SourceSeparationExactActivePlaybackPolicy {
    fun canReuseSession(
        activeSelectionGeneration: Long,
        sessionSelectionGeneration: Long,
        sessionCacheKey: String,
        resolvedSessionCacheKey: String?,
    ): Boolean = activeSelectionGeneration == sessionSelectionGeneration &&
        sessionCacheKey.isNotBlank() &&
        sessionCacheKey == resolvedSessionCacheKey
}
