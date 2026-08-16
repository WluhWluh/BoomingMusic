package com.mardous.booming.playback

import java.util.concurrent.atomic.AtomicLong

internal data class PlaybackMediaItemTransition(
    val generation: Long,
    val mediaId: String?,
)

internal class PlaybackMediaItemTransitionTracker {
    private val latestGeneration = AtomicLong()

    fun begin(mediaId: String?): PlaybackMediaItemTransition =
        PlaybackMediaItemTransition(
            generation = latestGeneration.incrementAndGet(),
            mediaId = mediaId,
        )

    fun isCurrent(
        transition: PlaybackMediaItemTransition,
        currentMediaId: String?,
    ): Boolean =
        transition.generation == latestGeneration.get() &&
                transition.mediaId == currentMediaId
}
