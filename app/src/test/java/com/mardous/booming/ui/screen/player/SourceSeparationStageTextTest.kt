package com.mardous.booming.ui.screen.player

import com.mardous.booming.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceSeparationStageTextTest {
    @Test
    fun `htdemucs stages resolve to localized resources`() {
        assertStage(
            "Computing global normalization",
            R.string.source_separation_stage_computing_global_normalization,
        )
        assertStage(
            "Processing window 3/11",
            R.string.source_separation_stage_processing_window,
            3,
            11,
        )
        assertStage(
            "Processed window 3/11",
            R.string.source_separation_stage_processed_window,
            3,
            11,
        )
        assertStage("Completed", R.string.source_separation_stage_completed)
    }

    @Test
    fun `source decode stages preserve the dynamic profile label`() {
        assertStage(
            "Checking FLAC window decoding",
            R.string.source_separation_stage_checking_window_decode,
            "FLAC",
        )
        assertStage(
            "Using FLAC window decoding",
            R.string.source_separation_stage_using_window_decode,
            "FLAC",
        )
        assertStage(
            "Inspecting source audio",
            R.string.source_separation_stage_inspecting_source_audio,
        )
        assertStage(
            "Decoding source audio",
            R.string.source_separation_stage_decoding_source_audio,
        )
        assertStage(
            "Resampling source audio",
            R.string.source_separation_stage_resampling_source_audio,
        )
    }

    @Test
    fun `unknown stage remains available for raw fallback`() {
        assertNull(sourceSeparationStageResource("Custom diagnostic stage"))
    }

    private fun assertStage(stage: String, resourceId: Int, vararg args: Any) {
        assertEquals(
            SourceSeparationStageResource(resourceId, args.toList()),
            sourceSeparationStageResource(stage),
        )
    }
}
