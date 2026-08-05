package com.mardous.booming.ui.screen.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceSeparationWorkerCompletionPlanTest {

    @Test
    fun `non-current completion still promotes its completed cache`() {
        assertEquals(
            SourceSeparationWorkerCompletionPlan(
                refreshCurrentCacheState = false,
                syncCurrentPlayback = false,
                promoteCompletedStems = true,
                cleanTemporaryFilesNow = false,
            ),
            sourceSeparationWorkerCompletionPlan(
                acceptsCurrentPlaybackState = false,
                playWhenReady = true,
                shouldPromoteCompletedStems = true,
            ),
        )
    }

    @Test
    fun `wav cleanup waits until promotion finishes`() {
        assertEquals(
            SourceSeparationWorkerCompletionPlan(
                refreshCurrentCacheState = true,
                syncCurrentPlayback = true,
                promoteCompletedStems = true,
                cleanTemporaryFilesNow = false,
            ),
            sourceSeparationWorkerCompletionPlan(
                acceptsCurrentPlaybackState = true,
                playWhenReady = false,
                shouldPromoteCompletedStems = true,
            ),
        )
    }

    @Test
    fun `completion refreshes state but defers playback sync while playback is requested`() {
        assertEquals(
            SourceSeparationWorkerCompletionPlan(
                refreshCurrentCacheState = true,
                syncCurrentPlayback = false,
                promoteCompletedStems = true,
                cleanTemporaryFilesNow = false,
            ),
            sourceSeparationWorkerCompletionPlan(
                acceptsCurrentPlaybackState = true,
                playWhenReady = true,
                shouldPromoteCompletedStems = true,
            ),
        )
    }

    @Test
    fun `completion without promotion cleans temporary work immediately`() {
        assertEquals(
            SourceSeparationWorkerCompletionPlan(
                refreshCurrentCacheState = true,
                syncCurrentPlayback = true,
                promoteCompletedStems = false,
                cleanTemporaryFilesNow = true,
            ),
            sourceSeparationWorkerCompletionPlan(
                acceptsCurrentPlaybackState = true,
                playWhenReady = false,
                shouldPromoteCompletedStems = false,
            ),
        )
    }
}
