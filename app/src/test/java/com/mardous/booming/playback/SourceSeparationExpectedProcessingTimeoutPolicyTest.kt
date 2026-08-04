package com.mardous.booming.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSeparationExpectedProcessingTimeoutPolicyTest {
    @Test
    fun `unresolved processing intent retains the short startup timeout`() {
        assertTrue(
            hasTimedOut(
                elapsedMs = PENDING_TIMEOUT_MS + 1L,
                waitingCacheKey = null,
            ),
        )
    }

    @Test
    fun `concrete processing target does not reset at the startup timeout`() {
        assertFalse(
            hasTimedOut(
                elapsedMs = PENDING_TIMEOUT_MS + 1L,
                waitingCacheKey = CACHE_KEY,
            ),
        )
        assertTrue(
            hasTimedOut(
                elapsedMs = TARGET_TIMEOUT_MS + 1L,
                waitingCacheKey = CACHE_KEY,
            ),
        )
    }

    @Test
    fun `exact active execution owner prevents timeout`() {
        assertFalse(
            hasTimedOut(
                elapsedMs = TARGET_TIMEOUT_MS + 1L,
                waitingCacheKey = CACHE_KEY,
                activeOwnerCacheKey = CACHE_KEY,
            ),
        )
        assertTrue(
            hasTimedOut(
                elapsedMs = TARGET_TIMEOUT_MS + 1L,
                waitingCacheKey = CACHE_KEY,
                activeOwnerCacheKey = OTHER_CACHE_KEY,
            ),
        )
    }

    private fun hasTimedOut(
        elapsedMs: Long,
        waitingCacheKey: String?,
        activeOwnerCacheKey: String? = null,
    ): Boolean = SourceSeparationExpectedProcessingTimeoutPolicy.hasTimedOut(
        expected = true,
        startedAtMs = STARTED_AT_MS,
        nowMs = STARTED_AT_MS + elapsedMs,
        waitingCacheKey = waitingCacheKey,
        activeOwnerCacheKey = activeOwnerCacheKey,
        pendingResolutionTimeoutMs = PENDING_TIMEOUT_MS,
        concreteTargetTimeoutMs = TARGET_TIMEOUT_MS,
    )

    private companion object {
        const val STARTED_AT_MS = 1_000L
        const val PENDING_TIMEOUT_MS = 10_000L
        const val TARGET_TIMEOUT_MS = 600_000L
        val CACHE_KEY = "a".repeat(64)
        val OTHER_CACHE_KEY = "b".repeat(64)
    }
}
