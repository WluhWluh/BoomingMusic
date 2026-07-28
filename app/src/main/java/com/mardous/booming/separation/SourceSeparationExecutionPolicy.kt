package com.mardous.booming.separation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class SourceSeparationExecutionRunClass(
    val backgroundPolicy: SourceSeparationBackgroundPolicy,
) {
    @SerialName("manual-full-song")
    ManualFullSong(SourceSeparationBackgroundPolicy.IndependentForegroundEligible),

    @SerialName("playback-demand-window")
    PlaybackDemandWindow(SourceSeparationBackgroundPolicy.PlaybackServiceOwned),

    @SerialName("next-song-prefetch")
    NextSongPrefetch(SourceSeparationBackgroundPolicy.ClientBound),
}

@Serializable
enum class SourceSeparationBackgroundPolicy {
    @SerialName("independent-foreground-eligible")
    IndependentForegroundEligible,

    @SerialName("playback-service-owned")
    PlaybackServiceOwned,

    @SerialName("client-bound")
    ClientBound,
}
