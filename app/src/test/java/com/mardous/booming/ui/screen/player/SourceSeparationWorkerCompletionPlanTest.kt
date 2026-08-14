package com.mardous.booming.ui.screen.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceSeparationWorkerCompletionPlanTest {

    @Test
    fun `non-current completion still promotes its completed cache`() {
        assertEquals(
            SourceSeparationWorkerCompletionPlan(
                refreshCurrentCacheState = false,
                clearCurrentPausePendingAction = false,
                syncCurrentPlayback = false,
                promoteCompletedStems = true,
                cleanTemporaryFilesNow = false,
            ),
            sourceSeparationWorkerCompletionPlan(
                isCurrentSong = false,
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
                clearCurrentPausePendingAction = true,
                syncCurrentPlayback = true,
                promoteCompletedStems = true,
                cleanTemporaryFilesNow = false,
            ),
            sourceSeparationWorkerCompletionPlan(
                isCurrentSong = true,
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
                clearCurrentPausePendingAction = true,
                syncCurrentPlayback = false,
                promoteCompletedStems = true,
                cleanTemporaryFilesNow = false,
            ),
            sourceSeparationWorkerCompletionPlan(
                isCurrentSong = true,
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
                clearCurrentPausePendingAction = true,
                syncCurrentPlayback = true,
                promoteCompletedStems = false,
                cleanTemporaryFilesNow = true,
            ),
            sourceSeparationWorkerCompletionPlan(
                isCurrentSong = true,
                acceptsCurrentPlaybackState = true,
                playWhenReady = false,
                shouldPromoteCompletedStems = false,
            ),
        )
    }

    @Test
    fun `current song completion refreshes stale cache state after worker status clears`() {
        assertEquals(
            SourceSeparationWorkerCompletionPlan(
                refreshCurrentCacheState = true,
                clearCurrentPausePendingAction = false,
                syncCurrentPlayback = false,
                promoteCompletedStems = true,
                cleanTemporaryFilesNow = false,
            ),
            sourceSeparationWorkerCompletionPlan(
                isCurrentSong = true,
                acceptsCurrentPlaybackState = false,
                playWhenReady = true,
                shouldPromoteCompletedStems = true,
            ),
        )
    }

    @Test
    fun `stale model completion refreshes current song without syncing old playback`() {
        assertEquals(
            SourceSeparationWorkerCompletionPlan(
                refreshCurrentCacheState = true,
                clearCurrentPausePendingAction = false,
                syncCurrentPlayback = false,
                promoteCompletedStems = true,
                cleanTemporaryFilesNow = false,
            ),
            sourceSeparationWorkerCompletionPlan(
                isCurrentSong = true,
                acceptsCurrentPlaybackState = false,
                playWhenReady = false,
                shouldPromoteCompletedStems = true,
            ),
        )
    }
}
