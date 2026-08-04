package com.mardous.booming.separation.cache.v2

import java.util.concurrent.CancellationException
import kotlinx.coroutines.delay

internal suspend fun promoteSourceSeparationCacheWhenAvailable(
    shouldCancel: () -> Boolean,
    maxAttempts: Int = DEFAULT_FLAC_PROMOTION_ATTEMPTS,
    retryDelayMs: Long = DEFAULT_FLAC_PROMOTION_RETRY_DELAY_MS,
    waitForRetry: suspend (Long) -> Unit = { delay(it) },
    promote: () -> SourceSeparationCacheFlacPromotionResult,
): SourceSeparationCacheFlacPromotionResult {
    require(maxAttempts > 0) { "FLAC promotion attempt count must be positive." }
    require(retryDelayMs >= 0L) { "FLAC promotion retry delay cannot be negative." }
    repeat(maxAttempts) { attempt ->
        if (shouldCancel()) throw CancellationException("FLAC promotion canceled.")
        val result = promote()
        if (result != SourceSeparationCacheFlacPromotionResult.Busy ||
            attempt == maxAttempts - 1
        ) {
            return result
        }
        waitForRetry(retryDelayMs)
    }
    error("FLAC promotion retry loop ended unexpectedly.")
}

private const val DEFAULT_FLAC_PROMOTION_ATTEMPTS = 40
private const val DEFAULT_FLAC_PROMOTION_RETRY_DELAY_MS = 250L
