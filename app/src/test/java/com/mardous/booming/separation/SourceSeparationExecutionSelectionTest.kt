package com.mardous.booming.separation

import com.mardous.booming.separation.lifecycle.SourceSeparationLifecycleTestFixtures
import com.mardous.booming.separation.model.preset.SourceSeparationActiveSelectionSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SourceSeparationExecutionSelectionTest {
    @Test
    fun `visible Demucs selection ignores a hidden MDX update`() {
        val demucs = SourceSeparationMultiStemPlaybackSelectionSnapshot(
            modelId = "htdemucs_6s",
            generation = 2L,
        )
        val before = SourceSeparationExecutionSelectionSnapshot.fromFamilySelections(
            mdx = mdxSelection(modelId = SourceSeparationLifecycleTestFixtures.MODEL_A, generation = 1L),
            multiStem = demucs,
        )
        val afterHiddenMdxUpdate = SourceSeparationExecutionSelectionSnapshot.fromFamilySelections(
            mdx = mdxSelection(modelId = SourceSeparationLifecycleTestFixtures.MODEL_B, generation = 3L),
            multiStem = demucs,
        )

        assertEquals(before, afterHiddenMdxUpdate)
    }

    @Test
    fun `clearing Demucs exposes MDX with the latest global generation`() {
        val selectedMdx = mdxSelection(
            modelId = SourceSeparationLifecycleTestFixtures.MODEL_B,
            generation = 3L,
        )
        val demucs = SourceSeparationMultiStemPlaybackSelectionSnapshot("htdemucs_6s", 2L)
        val clearedDemucs = SourceSeparationMultiStemPlaybackSelectionSnapshot(null, 4L)

        val before = SourceSeparationExecutionSelectionSnapshot.fromFamilySelections(
            selectedMdx,
            demucs,
        )
        val after = SourceSeparationExecutionSelectionSnapshot.fromFamilySelections(
            selectedMdx,
            clearedDemucs,
        )

        assertNotEquals(before, after)
        assertEquals(SourceSeparationModelFamily.Mdx, after.family)
        assertEquals(SourceSeparationLifecycleTestFixtures.MODEL_B, after.modelId)
        assertEquals(4L, after.generation)
    }

    @Test
    fun `A to Demucs to A remains generation distinct`() {
        val mdxA = mdxSelection(SourceSeparationLifecycleTestFixtures.MODEL_A, 1L)
        val none = SourceSeparationMultiStemPlaybackSelectionSnapshot(null, 0L)
        val demucs = SourceSeparationMultiStemPlaybackSelectionSnapshot("htdemucs_6s", 2L)
        val cleared = SourceSeparationMultiStemPlaybackSelectionSnapshot(null, 3L)

        val firstA = SourceSeparationExecutionSelectionSnapshot.fromFamilySelections(mdxA, none)
        val middle = SourceSeparationExecutionSelectionSnapshot.fromFamilySelections(mdxA, demucs)
        val secondA = SourceSeparationExecutionSelectionSnapshot.fromFamilySelections(mdxA, cleared)

        assertEquals(listOf(1L, 2L, 3L), listOf(firstA.generation, middle.generation, secondA.generation))
        assertNotEquals(firstA, secondA)
    }

    private fun mdxSelection(modelId: String, generation: Long) =
        SourceSeparationActiveSelectionSnapshot(
            reference = SourceSeparationLifecycleTestFixtures.activeReference(modelId),
            generation = generation,
        )
}
