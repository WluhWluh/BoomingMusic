package com.mardous.booming.ui.screen.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSeparationMultiStemMixUiStateTest {
    @Test
    fun `one stem update preserves contract order and changes demand`() {
        val state = fixture()

        val updated = requireNotNull(state.withGain("bass", 0.35f))

        assertEquals(listOf("drums", "bass", "other", "vocals"), updated.stemIds)
        assertEquals(listOf(1f, 0.35f, 1f, 1f), updated.gains)
        assertTrue(updated.requiresSeparatedOutput)
        assertEquals(0f, updated.demandBlend)
        assertNull(state.withGain("missing", 0f))
    }

    @Test
    fun `reset returns every stem to neutral unity`() {
        val changed = requireNotNull(fixture().withGain("vocals", 0f))

        val neutral = changed.neutralized()

        assertEquals(List(4) { 1f }, neutral.gains)
        assertFalse(neutral.requiresSeparatedOutput)
        assertEquals(0.5f, neutral.demandBlend)
    }

    private fun fixture() = SourceSeparationMultiStemMixUiState(
        modelId = "htdemucs-4s-base",
        songId = 42L,
        cacheKey = "a".repeat(64),
        stems = listOf("drums", "bass", "other", "vocals").map { stemId ->
            SourceSeparationStemGainUiState(
                stemId = stemId,
                semanticId = stemId,
                canonicalLabel = stemId.replaceFirstChar(Char::uppercase),
                gain = 1f,
            )
        },
    )
}
