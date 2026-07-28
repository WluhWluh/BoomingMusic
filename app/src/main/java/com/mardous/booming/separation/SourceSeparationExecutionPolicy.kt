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

@Serializable
data class SourceSeparationGpuFallbackLatch(
    val stage: String,
    val reason: String?,
) {
    init {
        require(stage.isNotBlank()) { "GPU fallback stage is empty." }
        require(reason == null || reason.isNotBlank()) { "GPU fallback reason is empty." }
    }
}
