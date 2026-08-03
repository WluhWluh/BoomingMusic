package com.mardous.booming.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSeparationExactActivePlaybackPolicyTest {
    @Test
    fun `session reuse requires current selection generation and exact cache`() {
        assertTrue(
            SourceSeparationExactActivePlaybackPolicy.canReuseSession(
                activeSelectionGeneration = 4L,
                sessionSelectionGeneration = 4L,
                sessionCacheKey = "cache-b",
                resolvedSessionCacheKey = "cache-b",
            ),
        )
        assertFalse(
            SourceSeparationExactActivePlaybackPolicy.canReuseSession(
                activeSelectionGeneration = 5L,
                sessionSelectionGeneration = 4L,
                sessionCacheKey = "cache-a",
                resolvedSessionCacheKey = "cache-a",
            ),
        )
        assertFalse(
            SourceSeparationExactActivePlaybackPolicy.canReuseSession(
                activeSelectionGeneration = 4L,
                sessionSelectionGeneration = 4L,
                sessionCacheKey = "cache-a",
                resolvedSessionCacheKey = "cache-b",
            ),
        )
    }
}
