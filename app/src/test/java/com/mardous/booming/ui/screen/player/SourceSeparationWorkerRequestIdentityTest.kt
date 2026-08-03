package com.mardous.booming.ui.screen.player

import com.mardous.booming.data.model.Song
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
