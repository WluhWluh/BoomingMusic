package com.mardous.booming.separation

import android.content.SharedPreferences
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheAudioFormat
import com.mardous.booming.util.SOURCE_SEPARATION_AUTO_FLAC_COMPRESSION
import com.mardous.booming.util.SOURCE_SEPARATION_COMPRESSION_FORMAT

/** One completed cache can have at most one promoted compression variant. */
enum class SourceSeparationCompressionFormat {
    None,
    Flac,
    AacLcM4a,
    ;

    fun cacheFormat(): SourceSeparationCacheAudioFormat? = when (this) {
        None -> null
        Flac -> SourceSeparationCacheAudioFormat.Flac
        AacLcM4a -> SourceSeparationCacheAudioFormat.AacLcM4a
    }

    fun preferenceValue(): String = when (this) {
        None -> PREFERENCE_NONE
        Flac -> PREFERENCE_FLAC
        AacLcM4a -> PREFERENCE_AAC
    }

    companion object {
        const val PREFERENCE_NONE = "none"
        const val PREFERENCE_FLAC = "flac"
        const val PREFERENCE_AAC = "aac_lc_160k"

        fun fromPreference(value: String?, legacyAutoFlac: Boolean): SourceSeparationCompressionFormat {
            return when (value?.lowercase()) {
                PREFERENCE_NONE -> None
                PREFERENCE_AAC -> AacLcM4a
                PREFERENCE_FLAC -> Flac
                else -> if (legacyAutoFlac) Flac else None
            }
        }
    }
}

fun SharedPreferences.sourceSeparationCompressionFormat(): SourceSeparationCompressionFormat =
    SourceSeparationCompressionFormat.fromPreference(
        value = getString(SOURCE_SEPARATION_COMPRESSION_FORMAT, null),
        legacyAutoFlac = getBoolean(SOURCE_SEPARATION_AUTO_FLAC_COMPRESSION, true),
    )
