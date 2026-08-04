package com.mardous.booming.ui.screen.player

import com.mardous.booming.R
import com.mardous.booming.separation.SourceSeparationStemLabelResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceSeparationStemLabelResolverTest {
    @Test
    fun `known labels use explicit localized resources`() {
        val expected = mapOf(
            "Vocals" to R.string.source_separation_blend_vocals,
            "Instrumental" to R.string.source_separation_blend_instrumental,
            "Bass" to R.string.source_separation_stem_bass,
            "Drums" to R.string.source_separation_stem_drums,
            "Other" to R.string.source_separation_stem_other,
            "Reverb" to R.string.source_separation_stem_reverb,
            "No Crowd" to R.string.source_separation_stem_no_crowd,
            "Guitar" to R.string.source_separation_stem_guitar,
            "Piano" to R.string.source_separation_stem_piano,
            "Target Stem" to R.string.source_separation_stem_target,
            "Remaining Audio" to R.string.source_separation_stem_remaining_audio,
        )

        expected.forEach { (label, resourceId) ->
            assertEquals(resourceId, SourceSeparationStemLabelResolver.resourceId(label))
        }
    }

    @Test
    fun `lookup normalization does not alter stored label`() {
        val canonicalLabel = "  rEmAiNiNg\t\nAuDiO  "

        assertEquals(
            "localized",
            SourceSeparationStemLabelResolver.resolve(canonicalLabel) { resourceId ->
                assertEquals(R.string.source_separation_stem_remaining_audio, resourceId)
                "localized"
            },
        )
    }

    @Test
    fun `unknown English and user labels remain exact`() {
        listOf("Lead Vocals", "Backing Track", "主唱", "Voix principale").forEach { label ->
            assertNull(SourceSeparationStemLabelResolver.resourceId(label))
            assertEquals(
                label,
                SourceSeparationStemLabelResolver.resolve(label) {
                    error("Unknown labels must not request a string resource.")
                },
            )
        }
    }
}
