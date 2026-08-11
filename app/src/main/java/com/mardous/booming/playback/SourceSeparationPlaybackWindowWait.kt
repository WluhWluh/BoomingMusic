package com.mardous.booming.playback

internal data class SourceSeparationPlaybackWindowWait(
    val epoch: Long,
    val cacheKey: String,
    val anchorPositionMs: Long,
    val requiredReadyWindowCount: Int,
    val resumeWhenReady: Boolean,
) {
    init {
        require(epoch > 0L) { "Playback window wait epoch must be positive." }
        require(cacheKey.isNotBlank()) { "Playback window wait cache key is empty." }
        require(anchorPositionMs >= 0L) { "Playback window wait position is negative." }
        require(requiredReadyWindowCount > 0) {
            "Playback window wait recovery count must be positive."
        }
    }
}

internal class SourceSeparationPlaybackWindowWaitTracker {
    var current: SourceSeparationPlaybackWindowWait? = null
        private set

    private var nextEpoch = 0L

    fun begin(
        cacheKey: String,
        anchorPositionMs: Long,
        requiredReadyWindowCount: Int,
        resumeWhenReady: Boolean,
    ): SourceSeparationPlaybackWindowWait {
        val existing = current?.takeIf { wait -> wait.cacheKey == cacheKey }
        if (existing != null) {
            return existing.copy(
                resumeWhenReady = existing.resumeWhenReady || resumeWhenReady,
            ).also { current = it }
        }
        return SourceSeparationPlaybackWindowWait(
            epoch = newEpoch(),
            cacheKey = cacheKey,
            anchorPositionMs = anchorPositionMs.coerceAtLeast(0L),
            requiredReadyWindowCount = requiredReadyWindowCount.coerceAtLeast(1),
            resumeWhenReady = resumeWhenReady,
        ).also { current = it }
    }

    fun retarget(
        anchorPositionMs: Long,
        requiredReadyWindowCount: Int,
    ): SourceSeparationPlaybackWindowWait? {
        val existing = current ?: return null
        return existing.copy(
            epoch = newEpoch(),
            anchorPositionMs = anchorPositionMs.coerceAtLeast(0L),
            requiredReadyWindowCount = requiredReadyWindowCount.coerceAtLeast(1),
        ).also { current = it }
    }

    fun forCache(cacheKey: String): SourceSeparationPlaybackWindowWait? =
        current?.takeIf { wait -> wait.cacheKey == cacheKey }

    fun rebind(
        cacheKey: String,
        anchorPositionMs: Long,
    ): SourceSeparationPlaybackWindowWait? {
        val existing = current ?: return null
        if (existing.cacheKey == cacheKey) return existing
        return existing.copy(
            epoch = newEpoch(),
            cacheKey = cacheKey,
            anchorPositionMs = anchorPositionMs.coerceAtLeast(0L),
        ).also { current = it }
    }

    fun isCurrent(epoch: Long?): Boolean = current?.epoch == epoch

    fun clear(expectedEpoch: Long? = null): SourceSeparationPlaybackWindowWait? {
        val existing = current ?: return null
        if (expectedEpoch != null && existing.epoch != expectedEpoch) return null
        current = null
        return existing
    }

    private fun newEpoch(): Long {
        nextEpoch = if (nextEpoch == Long.MAX_VALUE) 1L else nextEpoch + 1L
        return nextEpoch
    }
}
