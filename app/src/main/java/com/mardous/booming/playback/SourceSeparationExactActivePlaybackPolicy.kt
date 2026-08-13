package com.mardous.booming.playback

internal object SourceSeparationExactActivePlaybackPolicy {
    fun canReuseSession(
        activeSelectionGeneration: Long,
        sessionSelectionGeneration: Long,
        activeSelectionMatchesSession: Boolean,
        sessionCacheKey: String,
        resolvedSessionCacheKey: String?,
    ): Boolean = activeSelectionGeneration == sessionSelectionGeneration &&
        activeSelectionMatchesSession &&
        sessionCacheKey.isNotBlank() &&
        sessionCacheKey == resolvedSessionCacheKey
}
