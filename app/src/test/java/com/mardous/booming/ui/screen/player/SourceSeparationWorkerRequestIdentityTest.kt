package com.mardous.booming.ui.screen.player

import com.mardous.booming.data.model.Song
import com.mardous.booming.separation.SourceSeparationExecutionModelIdentity
import com.mardous.booming.separation.SourceSeparationExecutionSelectionSnapshot
import com.mardous.booming.separation.SourceSeparationModelFamily
import com.mardous.booming.separation.lifecycle.SourceSeparationLifecycleTestFixtures
import com.mardous.booming.separation.model.preset.SourceSeparationActiveSelectionSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SourceSeparationWorkerRequestIdentityTest {
    @Test
    fun `same song A and B requests never share preflight identity`() {
        val song = song()
        val selectionA = SourceSeparationActiveSelectionSnapshot(
            SourceSeparationLifecycleTestFixtures.activeReference(
                SourceSeparationLifecycleTestFixtures.MODEL_A,
            ),
            generation = 1L,
        )
        val selectionB = SourceSeparationActiveSelectionSnapshot(
            SourceSeparationLifecycleTestFixtures.activeReference(
                SourceSeparationLifecycleTestFixtures.MODEL_B,
            ),
            generation = 2L,
        )

        assertNotEquals(
            SourceSeparationWorkerRequestIdentity.from(song, selectionA),
            SourceSeparationWorkerRequestIdentity.from(song, selectionB),
        )
    }

    @Test
    fun `A to B to A keeps request generations distinct`() {
        val song = song()
        val referenceA = SourceSeparationLifecycleTestFixtures.activeReference(
            SourceSeparationLifecycleTestFixtures.MODEL_A,
        )

        assertNotEquals(
            SourceSeparationWorkerRequestIdentity.from(
                song,
                SourceSeparationActiveSelectionSnapshot(referenceA, 1L),
            ),
            SourceSeparationWorkerRequestIdentity.from(
                song,
                SourceSeparationActiveSelectionSnapshot(referenceA, 3L),
            ),
        )
    }

    @Test
    fun `display metadata changes do not create a new source request`() {
        val selection = SourceSeparationActiveSelectionSnapshot(
            SourceSeparationLifecycleTestFixtures.activeReference(
                SourceSeparationLifecycleTestFixtures.MODEL_A,
            ),
            generation = 1L,
        )
        val first = song()
        val renamed = song(title = "Updated title", artist = "Updated artist")

        assertEquals(
            SourceSeparationWorkerRequestIdentity.from(first, selection),
            SourceSeparationWorkerRequestIdentity.from(renamed, selection),
        )
    }

    @Test
    fun `MDX and Demucs requests never share an identity`() {
        val mdx = executionSelection(SourceSeparationModelFamily.Mdx, generation = 7L)
        val demucs = executionSelection(SourceSeparationModelFamily.Htdemucs, generation = 8L)

        assertNotEquals(
            SourceSeparationWorkerRequestIdentity.from(song(), mdx),
            SourceSeparationWorkerRequestIdentity.from(song(), demucs),
        )
    }

    @Test
    fun `same model ID with a replacement artifact is a distinct request`() {
        val original = executionSelection(
            family = SourceSeparationModelFamily.Htdemucs,
            generation = 10L,
            artifactSha256 = "a".repeat(64),
        )
        val replacement = executionSelection(
            family = SourceSeparationModelFamily.Htdemucs,
            generation = 11L,
            artifactSha256 = "b".repeat(64),
        )

        assertNotEquals(
            SourceSeparationWorkerRequestIdentity.from(song(), original),
            SourceSeparationWorkerRequestIdentity.from(song(), replacement),
        )
    }

    @Test
    fun `contract and pipeline revisions are part of request identity`() {
        val original = executionSelection(
            family = SourceSeparationModelFamily.Htdemucs,
            generation = 12L,
        )
        val revised = original.copy(
            identity = requireNotNull(original.identity).copy(
                contractFingerprint = "d".repeat(64),
                pipelineVersion = 2,
            ),
            generation = 13L,
        )

        assertNotEquals(
            SourceSeparationWorkerRequestIdentity.from(song(), original),
            SourceSeparationWorkerRequestIdentity.from(song(), revised),
        )
    }

    private fun executionSelection(
        family: SourceSeparationModelFamily,
        generation: Long,
        artifactSha256: String = "a".repeat(64),
    ): SourceSeparationExecutionSelectionSnapshot {
        val modelId = "shared_model"
        return SourceSeparationExecutionSelectionSnapshot(
            family = family,
            modelId = modelId,
            identity = SourceSeparationExecutionModelIdentity(
                family = family,
                modelId = modelId,
                artifactSha256 = artifactSha256,
                contractId = "contract-v1",
                contractSchemaVersion = 1,
                contractFingerprint = "c".repeat(64),
                profileRevisionId = "profile-v1",
                pipelineId = "pipeline",
                pipelineVersion = 1,
                renderProfileId = "cpu-fp32-v1",
            ),
            generation = generation,
        )
    }

    private fun song(
        title: String = "Song",
        artist: String = "Artist",
    ) = Song(
        id = 42L,
        data = "/music/song.flac",
        title = title,
        trackNumber = 1,
        year = 2026,
        size = 1_024L,
        duration = 2_000L,
        dateAdded = 1L,
        rawDateModified = 2L,
        albumId = 3L,
        albumName = "Album",
        artistId = 4L,
        artistName = artist,
        albumArtistName = null,
        genreName = null,
    )
}
