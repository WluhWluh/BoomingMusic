package com.mardous.booming.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceSeparationAacSeekModeTest {
    @Test
    fun unknownOrMissingPreferenceKeepsPreviousSyncDefault() {
        assertEquals(
            SourceSeparationAacSeekMode.PREVIOUS,
            SourceSeparationAacSeekMode.fromPreference(null),
        )
        assertEquals(
            SourceSeparationAacSeekMode.PREVIOUS,
            SourceSeparationAacSeekMode.fromPreference("unsupported"),
        )
    }

    @Test
    fun experimentalModesRoundTripTheirPreferenceValues() {
        SourceSeparationAacSeekMode.entries.forEach { mode ->
            assertEquals(
                mode,
                SourceSeparationAacSeekMode.fromPreference(mode.preferenceValue.uppercase()),
            )
        }
    }
}
