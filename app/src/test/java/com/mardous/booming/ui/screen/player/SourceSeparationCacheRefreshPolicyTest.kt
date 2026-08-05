package com.mardous.booming.ui.screen.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSeparationCacheRefreshPolicyTest {
    @Test
    fun `only the latest matching cache refresh may publish`() {
        assertTrue(canApply(requestGeneration = 3L, latestGeneration = 3L))
        assertFalse(canApply(requestGeneration = 2L, latestGeneration = 3L))
    }

    @Test
    fun `song or selection changes reject an otherwise latest refresh`() {
        assertFalse(
            canApply(
                requestGeneration = 3L,
                latestGeneration = 3L,
                requestedSongId = 1L,
                currentSongId = 2L,
            ),
        )
        assertFalse(
            canApply(
                requestGeneration = 3L,
                latestGeneration = 3L,
                selectionMatches = false,
            ),
        )
    }

    private fun canApply(
        requestGeneration: Long,
        latestGeneration: Long,
        requestedSongId: Long = 1L,
        currentSongId: Long = 1L,
        selectionMatches: Boolean = true,
    ): Boolean = SourceSeparationCacheRefreshPolicy.canApply(
        requestGeneration = requestGeneration,
        latestGeneration = latestGeneration,
        requestedSongId = requestedSongId,
        currentSongId = currentSongId,
        selectionMatches = selectionMatches,
    )
}
