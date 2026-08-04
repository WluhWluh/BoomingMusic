package com.mardous.booming.playback

internal object SourceSeparationExpectedProcessingTimeoutPolicy {
    fun hasTimedOut(
        expected: Boolean,
        startedAtMs: Long,
        nowMs: Long,
        waitingCacheKey: String?,
        activeOwnerCacheKey: String?,
        pendingResolutionTimeoutMs: Long,
        concreteTargetTimeoutMs: Long,
    ): Boolean {
        if (!expected || startedAtMs <= 0L || nowMs < startedAtMs) return false
        if (waitingCacheKey != null && waitingCacheKey == activeOwnerCacheKey) return false
        val timeoutMs = if (waitingCacheKey == null) {
            pendingResolutionTimeoutMs
        } else {
            concreteTargetTimeoutMs
        }
        return nowMs - startedAtMs > timeoutMs
    }
}
