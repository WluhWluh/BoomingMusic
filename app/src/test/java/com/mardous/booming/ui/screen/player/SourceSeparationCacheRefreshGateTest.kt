package com.mardous.booming.ui.screen.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class SourceSeparationCacheRefreshGateTest {
    @Test
    fun `older generation cannot publish`() {
        val gate = SourceSeparationCacheRefreshGate()
        val older = gate.nextGeneration()
        val latest = gate.nextGeneration()
        var olderPublished = false
        var latestPublished = false

        val olderResult = gate.publishIfCurrent(older, 1L, 1L, true) {
            olderPublished = true
        }
        val latestResult = gate.publishIfCurrent(latest, 1L, 1L, true) {
            latestPublished = true
        }

        assertFalse(olderResult)
        assertFalse(olderPublished)
        assertTrue(latestResult)
        assertTrue(latestPublished)
    }

    @Test
    fun `new request cannot interleave with accepted publication`() {
        val gate = SourceSeparationCacheRefreshGate()
        val first = gate.nextGeneration()
        val publicationStarted = CountDownLatch(1)
        val releasePublication = CountDownLatch(1)
        val publicationFinished = CountDownLatch(1)
        val nextRequestStarted = CountDownLatch(1)
        val nextRequestFinished = CountDownLatch(1)

        val publisher = thread {
            gate.publishIfCurrent(first, 1L, 1L, true) {
                publicationStarted.countDown()
                releasePublication.await(5, TimeUnit.SECONDS)
            }
            publicationFinished.countDown()
        }
        assertTrue(publicationStarted.await(5, TimeUnit.SECONDS))

        val requester = thread {
            nextRequestStarted.countDown()
            gate.nextGeneration()
            nextRequestFinished.countDown()
        }
        try {
            assertTrue(nextRequestStarted.await(5, TimeUnit.SECONDS))
            assertFalse(nextRequestFinished.await(100, TimeUnit.MILLISECONDS))
        } finally {
            releasePublication.countDown()
        }
        assertTrue(publicationFinished.await(5, TimeUnit.SECONDS))
        assertTrue(nextRequestFinished.await(5, TimeUnit.SECONDS))
        publisher.join()
        requester.join()
    }
}
