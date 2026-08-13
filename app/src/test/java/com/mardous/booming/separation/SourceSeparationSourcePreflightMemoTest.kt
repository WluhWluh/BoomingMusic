package com.mardous.booming.separation

import com.mardous.booming.data.model.Song
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheSongLocator
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheSourceDiagnostics
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheSourceIdentity
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheSourcePreflight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SourceSeparationSourcePreflightMemoTest {
    @Test
    fun `successful preflight is shared across callers until the source stamp changes`() {
        val memo = SourceSeparationSourcePreflightMemo()
        var resolutions = 0
        val resolver = SourceSeparationModelAwarePreflightResolver { _, _ ->
            resolutions += 1
            preflight(elapsedMs = 23L)
        }
        val song = song(rawDateModified = 2L)

        val first = memo.resolve(song, input(song), resolver) { false }
        val second = memo.resolve(song, input(song), resolver) { false }
        val changed = song(rawDateModified = 3L)
        val third = memo.resolve(changed, input(changed), resolver) { false }

        assertEquals(23L, first.elapsedMs)
        assertEquals(0L, second.elapsedMs)
        assertEquals(23L, third.elapsedMs)
        assertEquals(2, resolutions)
        assertEquals(
            SourceSeparationSourcePreflightMemoSnapshot(
                hits = 1L,
                misses = 2L,
                evictions = 0L,
                entryCount = 2,
            ),
            memo.snapshot(),
        )
    }

    @Test
    fun `failed and canceled preflights are not memoized`() {
        val memo = SourceSeparationSourcePreflightMemo()
        val song = song()
        var attempts = 0
        val resolver = SourceSeparationModelAwarePreflightResolver { _, _ ->
            attempts += 1
            if (attempts == 1) error("injected preflight failure")
            preflight(elapsedMs = 7L)
        }

        assertThrows(IllegalStateException::class.java) {
            memo.resolve(song, input(song), resolver) { false }
        }
        assertEquals(7L, memo.resolve(song, input(song), resolver) { false }.elapsedMs)
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            memo.resolve(song, input(song), resolver) { true }
        }

        assertEquals(2, attempts)
        assertEquals(2L, memo.snapshot().misses)
        assertEquals(1, memo.snapshot().entryCount)
    }

    @Test
    fun `least recently used entries are bounded`() {
        val memo = SourceSeparationSourcePreflightMemo(maxEntries = 2)
        var resolutions = 0
        val resolver = SourceSeparationModelAwarePreflightResolver { _, _ ->
            resolutions += 1
            preflight(elapsedMs = resolutions.toLong())
        }
        val first = song(id = 1L)
        val second = song(id = 2L)
        val third = song(id = 3L)

        memo.resolve(first, input(first), resolver) { false }
        memo.resolve(second, input(second), resolver) { false }
        memo.resolve(first, input(first), resolver) { false }
        memo.resolve(third, input(third), resolver) { false }
        memo.resolve(second, input(second), resolver) { false }

        assertEquals(4, resolutions)
        assertEquals(2L, memo.snapshot().evictions)
        assertEquals(2, memo.snapshot().entryCount)
    }

    @Test
    fun `concurrent callers share one in flight source probe`() {
        val memo = SourceSeparationSourcePreflightMemo()
        val song = song()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var resolutions = 0
        val resolver = SourceSeparationModelAwarePreflightResolver { _, _ ->
            synchronized(memo) { resolutions += 1 }
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            preflight(elapsedMs = 17L)
        }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<SourceSeparationCacheSourcePreflight> {
                memo.resolve(song, input(song), resolver) { false }
            }
            check(entered.await(5, TimeUnit.SECONDS))
            val second = executor.submit<SourceSeparationCacheSourcePreflight> {
                memo.resolve(song, input(song), resolver) { false }
            }
            release.countDown()

            assertEquals(17L, first.get(5, TimeUnit.SECONDS).elapsedMs)
            assertEquals(0L, second.get(5, TimeUnit.SECONDS).elapsedMs)
            assertEquals(1, resolutions)
            assertEquals(1L, memo.snapshot().hits)
            assertEquals(1L, memo.snapshot().misses)
            assertEquals(1, memo.snapshot().entryCount)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `valid waiter retries after the in flight owner is canceled`() {
        val memo = SourceSeparationSourcePreflightMemo()
        val song = song()
        val entered = CountDownLatch(1)
        val cancelOwner = java.util.concurrent.atomic.AtomicBoolean(false)
        var resolutions = 0
        val resolver = SourceSeparationModelAwarePreflightResolver { _, shouldCancel ->
            synchronized(memo) { resolutions += 1 }
            if (resolutions == 1) {
                entered.countDown()
                while (!shouldCancel()) Thread.sleep(5L)
                throw java.util.concurrent.CancellationException("owner canceled")
            }
            preflight(elapsedMs = 19L)
        }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val owner = executor.submit<SourceSeparationCacheSourcePreflight> {
                memo.resolve(song, input(song), resolver, cancelOwner::get)
            }
            check(entered.await(5, TimeUnit.SECONDS))
            val waiter = executor.submit<SourceSeparationCacheSourcePreflight> {
                memo.resolve(song, input(song), resolver) { false }
            }
            cancelOwner.set(true)

            assertThrows(java.util.concurrent.ExecutionException::class.java) {
                owner.get(5, TimeUnit.SECONDS)
            }
            assertEquals(19L, waiter.get(5, TimeUnit.SECONDS).elapsedMs)
            assertEquals(2, resolutions)
            assertEquals(1, memo.snapshot().entryCount)
        } finally {
            cancelOwner.set(true)
            executor.shutdownNow()
        }
    }

    private fun song(
        id: Long = 42L,
        rawDateModified: Long = 2L,
    ) = Song(
        id = id,
        data = "/music/song-$id.flac",
        title = "Song $id",
        trackNumber = 1,
        year = 2026,
        size = 1_024L,
        duration = 2_000L,
        dateAdded = 1L,
        rawDateModified = rawDateModified,
        albumId = 3L,
        albumName = "Album",
        artistId = 4L,
        artistName = "Artist",
        albumArtistName = null,
        genreName = null,
    )

    private fun input(song: Song) = SourceSeparationModelAwareSongInput(
        sourceUri = "content://media/${song.id}",
        displayName = "song-${song.id}.flac",
        song = SourceSeparationCacheSongLocator(
            songId = song.id,
            mediaUri = "content://media/${song.id}",
            filePath = song.data,
            title = song.title,
            artist = song.artistName,
            album = song.albumName,
        ),
        sourceDiagnostics = SourceSeparationCacheSourceDiagnostics(
            fileSize = song.size,
            rawDateModified = song.rawDateModified,
            durationMs = song.duration,
        ),
    )

    private fun preflight(elapsedMs: Long) = SourceSeparationCacheSourcePreflight(
        identity = SourceSeparationCacheSourceIdentity(
            audioFingerprint = "encoded-samples-v1:${"a".repeat(64)}",
            encodedSampleCount = 100L,
            encodedByteCount = 1_024L,
            mimeType = "audio/flac",
            sourceSampleRate = 44_100,
            sourceChannelCount = 2,
            sourceDurationUs = 2_000_000L,
        ),
        elapsedMs = elapsedMs,
    )
}
