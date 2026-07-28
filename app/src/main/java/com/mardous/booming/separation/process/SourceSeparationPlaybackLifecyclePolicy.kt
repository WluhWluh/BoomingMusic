package com.mardous.booming.separation.process

import com.mardous.booming.separation.SourceSeparationExecutionRunClass

internal object SourceSeparationPlaybackLifecyclePolicy {
    fun shouldPauseWhenPlaybackStops(
        runClass: SourceSeparationExecutionRunClass?,
    ): Boolean = when (runClass) {
        SourceSeparationExecutionRunClass.PlaybackDemandWindow,
        SourceSeparationExecutionRunClass.NextSongPrefetch,
        -> true

        SourceSeparationExecutionRunClass.ManualFullSong,
        null,
        -> false
    }
}
