package com.mardous.booming.separation.process

import com.mardous.booming.separation.SourceSeparationExecutionRunClass
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSeparationPlaybackLifecyclePolicyTest {
    @Test
    fun `manual full-song work survives playback shutdown`() {
        assertFalse(
            SourceSeparationPlaybackLifecyclePolicy.shouldPauseWhenPlaybackStops(
                SourceSeparationExecutionRunClass.ManualFullSong,
            )
        )
    }

    @Test
    fun `playback-owned work pauses with playback shutdown`() {
        assertTrue(
            SourceSeparationPlaybackLifecyclePolicy.shouldPauseWhenPlaybackStops(
                SourceSeparationExecutionRunClass.PlaybackDemandWindow,
            )
        )
        assertTrue(
            SourceSeparationPlaybackLifecyclePolicy.shouldPauseWhenPlaybackStops(
                SourceSeparationExecutionRunClass.NextSongPrefetch,
            )
        )
    }

    @Test
    fun `idle worker needs no playback shutdown control`() {
        assertFalse(
            SourceSeparationPlaybackLifecyclePolicy.shouldPauseWhenPlaybackStops(null)
        )
    }
}
