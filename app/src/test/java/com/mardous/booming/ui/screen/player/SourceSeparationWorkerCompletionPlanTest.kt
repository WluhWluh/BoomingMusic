package com.mardous.booming.ui.screen.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceSeparationWorkerCompletionPlanTest {

    @Test
    fun `non-current completion still promotes its completed cache`() {
        assertEquals(
            SourceSeparationWorkerCompletionPlan(
                syncCurrentPlayback = false,
                promoteCompletedStems = true,
                cleanTemporaryFilesNow = false,
            ),
            sourceSeparationWorkerCompletionPlan(
                acceptsCurrentPlaybackState = false,
                shouldPromoteCompletedStems = true,
            ),
        )
    }

    @Test
    fun `wav cleanup waits until promotion finishes`() {
        assertEquals(
            SourceSeparationWorkerCompletionPlan(
                syncCurrentPlayback = true,
                promoteCompletedStems = true,
                cleanTemporaryFilesNow = false,
            ),
            sourceSeparationWorkerCompletionPlan(
                acceptsCurrentPlaybackState = true,
                shouldPromoteCompletedStems = true,
            ),
        )
    }

    @Test
    fun `completion without promotion cleans temporary work immediately`() {
        assertEquals(
            SourceSeparationWorkerCompletionPlan(
                syncCurrentPlayback = true,
                promoteCompletedStems = false,
                cleanTemporaryFilesNow = true,
            ),
            sourceSeparationWorkerCompletionPlan(
                acceptsCurrentPlaybackState = true,
                shouldPromoteCompletedStems = false,
            ),
        )
    }
}
