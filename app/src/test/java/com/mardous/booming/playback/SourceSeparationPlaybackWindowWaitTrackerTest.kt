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
