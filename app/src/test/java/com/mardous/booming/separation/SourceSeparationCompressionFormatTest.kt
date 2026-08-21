package com.mardous.booming.separation

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceSeparationCompressionFormatTest {
    @Test
    fun `preference values round trip`() {
        SourceSeparationCompressionFormat.entries.forEach { format ->
            assertEquals(
                format,
                SourceSeparationCompressionFormat.fromPreference(
                    format.preferenceValue(),
                    legacyAutoFlac = true,
                ),
            )
        }
    }

    @Test
    fun `legacy boolean is only used when new preference is absent`() {
        assertEquals(
            SourceSeparationCompressionFormat.Flac,
            SourceSeparationCompressionFormat.fromPreference(null, legacyAutoFlac = true),
        )
        assertEquals(
            SourceSeparationCompressionFormat.None,
            SourceSeparationCompressionFormat.fromPreference(null, legacyAutoFlac = false),
        )
        assertEquals(
            SourceSeparationCompressionFormat.AacLcM4a,
            SourceSeparationCompressionFormat.fromPreference(
                SourceSeparationCompressionFormat.PREFERENCE_AAC,
                legacyAutoFlac = true,
            ),
        )
    }

    @Test
    fun `cache formats are mutually exclusive`() {
        assertEquals(null, SourceSeparationCompressionFormat.None.cacheFormat())
        assertEquals(
            com.mardous.booming.separation.cache.v2.SourceSeparationCacheAudioFormat.Flac,
            SourceSeparationCompressionFormat.Flac.cacheFormat(),
        )
        assertEquals(
            com.mardous.booming.separation.cache.v2.SourceSeparationCacheAudioFormat.AacLcM4a,
            SourceSeparationCompressionFormat.AacLcM4a.cacheFormat(),
        )
    }
}
