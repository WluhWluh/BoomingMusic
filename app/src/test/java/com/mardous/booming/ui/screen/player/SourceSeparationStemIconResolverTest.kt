package com.mardous.booming.ui.screen.player

import com.mardous.booming.R
import com.mardous.booming.separation.SourceSeparationStemIconResolver
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceSeparationStemIconResolverTest {
    @Test
    fun `published MDX stem labels use the frozen icon mapping`() {
        val expected = mapOf(
            "Vocals" to R.drawable.ic_person_24dp,
            "Instrumental" to R.drawable.ic_speaker_24dp,
            "Bass" to R.drawable.ic_stem_bass_24dp,
            "Drums" to R.drawable.ic_stem_drums_24dp,
            "Other" to R.drawable.ic_speaker_24dp,
            "Remaining Audio" to R.drawable.ic_graphic_eq_24dp,
            "Reverb" to R.drawable.ic_stem_reverb_24dp,
            "No Crowd" to R.drawable.ic_group_off_24dp,
            "Crowd" to R.drawable.ic_group_24dp,
            "Guitar" to R.drawable.ic_stem_guitar_24dp,
            "Piano" to R.drawable.ic_stem_piano_24dp,
            "Unknown" to R.drawable.ic_graphic_eq_24dp,
        )

        expected.forEach { (label, resourceId) ->
            assertEquals(resourceId, SourceSeparationStemIconResolver.resourceId(label))
        }
    }

    @Test
    fun `semantic-style aliases and custom labels use deterministic fallbacks`() {
        assertEquals(
            R.drawable.ic_graphic_eq_24dp,
            SourceSeparationStemIconResolver.resourceId("remaining_audio"),
        )
        assertEquals(
            R.drawable.ic_group_off_24dp,
            SourceSeparationStemIconResolver.resourceId("no_crowd"),
        )
        assertEquals(
            R.drawable.ic_graphic_eq_24dp,
            SourceSeparationStemIconResolver.resourceId("Backing Vocals"),
        )
    }
}
