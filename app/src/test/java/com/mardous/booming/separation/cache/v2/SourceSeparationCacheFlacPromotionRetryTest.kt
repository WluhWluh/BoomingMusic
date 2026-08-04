package com.mardous.booming.separation.cache.v2

import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SourceSeparationCacheFlacPromotionRetryTest {

    @Test
    fun `busy promotion retries until the cache lease is available`() = runBlocking {
        var attempts = 0
        val waits = mutableListOf<Long>()
        val terminal = SourceSeparationCacheFlacPromotionResult.Unavailable

        val result = promoteSourceSeparationCacheWhenAvailable(
            shouldCancel = { false },
            maxAttempts = 4,
            retryDelayMs = 25L,
            waitForRetry = waits::add,
        ) {
            attempts += 1
            if (attempts < 3) SourceSeparationCacheFlacPromotionResult.Busy else terminal
        }

        assertEquals(terminal, result)
        assertEquals(3, attempts)
        assertEquals(listOf(25L, 25L), waits)
    }

    @Test
    fun `busy promotion stops after the bounded attempt count`() = runBlocking {
        var attempts = 0

        val result = promoteSourceSeparationCacheWhenAvailable(
            shouldCancel = { false },
            maxAttempts = 3,
            retryDelayMs = 0L,
            waitForRetry = {},
        ) {
            attempts += 1
            SourceSeparationCacheFlacPromotionResult.Busy
        }

        assertEquals(SourceSeparationCacheFlacPromotionResult.Busy, result)
        assertEquals(3, attempts)
    }

    @Test
    fun `cancellation prevents another promotion attempt`() {
        var attempts = 0
        var canceled = false

        assertThrows(CancellationException::class.java) {
            runBlocking {
                promoteSourceSeparationCacheWhenAvailable(
                    shouldCancel = { canceled },
                    maxAttempts = 3,
                    retryDelayMs = 0L,
                    waitForRetry = { canceled = true },
                ) {
                    attempts += 1
                    SourceSeparationCacheFlacPromotionResult.Busy
                }
            }
        }
        assertEquals(1, attempts)
    }
}
