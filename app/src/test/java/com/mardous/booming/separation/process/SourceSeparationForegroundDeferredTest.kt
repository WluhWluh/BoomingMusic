package com.mardous.booming.separation.process

import com.mardous.booming.separation.SourceSeparationPausedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSeparationForegroundDeferredTest {
    @Test
    fun `service start denial becomes a typed paused outcome`() {
        val cause = IllegalStateException("background start denied")
        val deferred = cause.toForegroundExecutionDeferredException(
            SourceSeparationForegroundStartStage.ServiceStart,
        )

        assertEquals(
            SourceSeparationForegroundDeferredReason.StartNotAllowed,
            deferred?.reason,
        )
        assertTrue(deferred is SourceSeparationPausedException)
        assertEquals(cause, deferred?.cause)
    }

    @Test
    fun `promotion security failure becomes a typed paused outcome`() {
        val deferred = SecurityException("promotion denied")
            .toForegroundExecutionDeferredException(
                SourceSeparationForegroundStartStage.Promotion,
            )

        assertEquals(
            SourceSeparationForegroundDeferredReason.PromotionDenied,
            deferred?.reason,
        )
    }

    @Test
    fun `unrelated runtime failures remain failures`() {
        assertNull(
            UnsupportedOperationException("notification rendering failed")
                .toForegroundExecutionDeferredException(
                    SourceSeparationForegroundStartStage.Promotion,
                )
        )
    }
}
