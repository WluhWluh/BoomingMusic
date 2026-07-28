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
    fun `owner loss rejects playback work across admission boundaries`() {
        assertFalse(
            SourceSeparationPlaybackLifecyclePolicy.canRunWithPlaybackOwnerState(
                SourceSeparationExecutionRunClass.PlaybackDemandWindow,
                playbackOwnerActive = false,
            )
        )
        assertFalse(
            SourceSeparationPlaybackLifecyclePolicy.canRunWithPlaybackOwnerState(
                SourceSeparationExecutionRunClass.NextSongPrefetch,
                playbackOwnerActive = false,
            )
        )
        assertTrue(
            SourceSeparationPlaybackLifecyclePolicy.canRunWithPlaybackOwnerState(
                SourceSeparationExecutionRunClass.ManualFullSong,
                playbackOwnerActive = false,
            )
        )
    }

    @Test
    fun `live owner admits every work class`() {
        SourceSeparationExecutionRunClass.entries.forEach { runClass ->
            assertTrue(
                SourceSeparationPlaybackLifecyclePolicy.canRunWithPlaybackOwnerState(
                    runClass,
                    playbackOwnerActive = true,
                )
            )
        }
    }

    @Test
    fun `idle worker needs no playback shutdown control`() {
        assertFalse(
            SourceSeparationPlaybackLifecyclePolicy.shouldPauseWhenPlaybackStops(null)
        )
    }
}
