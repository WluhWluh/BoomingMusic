package com.mardous.booming.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackMediaItemTransitionTrackerTest {
    @Test
    fun `a newer media transition rejects an older resolution`() {
        val tracker = PlaybackMediaItemTransitionTracker()
        val first = tracker.begin("504")
        val latest = tracker.begin("832")

        assertFalse(tracker.isCurrent(first, "504"))
        assertFalse(tracker.isCurrent(first, "832"))
        assertTrue(tracker.isCurrent(latest, "832"))
    }

    @Test
    fun `repeating the same media item still invalidates the older transition`() {
        val tracker = PlaybackMediaItemTransitionTracker()
        val first = tracker.begin("504")
        val latest = tracker.begin("504")

        assertFalse(tracker.isCurrent(first, "504"))
        assertTrue(tracker.isCurrent(latest, "504"))
    }

    @Test
    fun `the latest resolution must also match the player media item`() {
        val tracker = PlaybackMediaItemTransitionTracker()
        val transition = tracker.begin("504")

        assertFalse(tracker.isCurrent(transition, "832"))
        assertTrue(tracker.isCurrent(transition, "504"))
    }
}
