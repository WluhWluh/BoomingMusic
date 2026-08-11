package com.mardous.booming.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSeparationPlaybackWindowWaitTrackerTest {
    @Test
    fun `repeated waits keep one recovery anchor and epoch`() {
        val tracker = SourceSeparationPlaybackWindowWaitTracker()
        val first = tracker.begin(
            cacheKey = "cache-a",
            anchorPositionMs = 5_850L,
            requiredReadyWindowCount = 2,
            resumeWhenReady = false,
        )

        val repeated = tracker.begin(
            cacheKey = "cache-a",
            anchorPositionMs = 5_799L,
            requiredReadyWindowCount = 1,
            resumeWhenReady = true,
        )

        assertEquals(first.epoch, repeated.epoch)
        assertEquals(5_850L, repeated.anchorPositionMs)
        assertEquals(2, repeated.requiredReadyWindowCount)
        assertTrue(repeated.resumeWhenReady)
    }

    @Test
    fun `user seek retargets recovery and invalidates the old epoch`() {
        val tracker = SourceSeparationPlaybackWindowWaitTracker()
        val first = tracker.begin("cache-a", 5_850L, 2, true)

        val retargeted = requireNotNull(tracker.retarget(12_000L, 3))

        assertTrue(retargeted.epoch > first.epoch)
        assertEquals(12_000L, retargeted.anchorPositionMs)
        assertEquals(3, retargeted.requiredReadyWindowCount)
        assertFalse(tracker.isCurrent(first.epoch))
        assertTrue(tracker.isCurrent(retargeted.epoch))
    }

    @Test
    fun `resolved cache change rebinds recovery without weakening its waterline`() {
        val tracker = SourceSeparationPlaybackWindowWaitTracker()
        val first = tracker.begin("old-song-cache", 326_937L, 2, true)

        val rebound = requireNotNull(tracker.rebind("new-song-cache", 0L))

        assertTrue(rebound.epoch > first.epoch)
        assertEquals("new-song-cache", rebound.cacheKey)
        assertEquals(0L, rebound.anchorPositionMs)
        assertEquals(2, rebound.requiredReadyWindowCount)
        assertTrue(rebound.resumeWhenReady)
        assertFalse(tracker.isCurrent(first.epoch))
        assertTrue(tracker.isCurrent(rebound.epoch))
    }

    @Test
    fun `rebind to the current cache keeps the active recovery epoch`() {
        val tracker = SourceSeparationPlaybackWindowWaitTracker()
        val first = tracker.begin("cache-a", 5_850L, 2, true)

        val unchanged = tracker.rebind("cache-a", 12_000L)

        assertSame(first, unchanged)
        assertSame(first, tracker.current)
    }

    @Test
    fun `stale completion cannot clear a newer wait`() {
        val tracker = SourceSeparationPlaybackWindowWaitTracker()
        val first = tracker.begin("cache-a", 0L, 2, true)
        val second = requireNotNull(tracker.retarget(9_000L, 2))

        assertNull(tracker.clear(first.epoch))
        assertSame(second, tracker.current)
        assertSame(second, tracker.clear(second.epoch))
        assertNull(tracker.current)
    }
}
