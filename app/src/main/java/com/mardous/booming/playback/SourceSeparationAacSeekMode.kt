package com.mardous.booming.playback

import android.media.MediaExtractor

/**
 * Sync-point policy for the experimental AAC seek path.
 *
 * [PREVIOUS] remains the product-safe default. [CLOSEST] and [NEXT] are
 * opt-in experiments because they can move playback forward relative to the
 * requested timeline position.
 */
enum class SourceSeparationAacSeekMode(
    internal val extractorMode: Int,
    val preferenceValue: String,
) {
    PREVIOUS(MediaExtractor.SEEK_TO_PREVIOUS_SYNC, "previous"),
    CLOSEST(MediaExtractor.SEEK_TO_CLOSEST_SYNC, "closest"),
    NEXT(MediaExtractor.SEEK_TO_NEXT_SYNC, "next"),
    ;

    companion object {
        fun fromPreference(value: String?): SourceSeparationAacSeekMode = entries
            .firstOrNull { mode -> mode.preferenceValue.equals(value, ignoreCase = true) }
            ?: PREVIOUS
    }
}
