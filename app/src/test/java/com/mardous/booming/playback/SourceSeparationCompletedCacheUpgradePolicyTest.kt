package com.mardous.booming.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSeparationCompletedCacheUpgradePolicyTest {
    @Test
    fun `active playback defers automatic cache upgrades`() {
        assertTrue(
            shouldDefer(
                playWhenReady = true,
                hasPlayIntent = true,
                isProcessing = false,
            ),
        )
    }

    @Test
    fun `internal pause retains the deferred upgrade`() {
        assertTrue(
            shouldDefer(
                playWhenReady = false,
                hasPlayIntent = true,
                isProcessing = false,
            ),
        )
    }

    @Test
    fun `manual pause permits an inaudible upgrade`() {
        assertFalse(
            shouldDefer(
                playWhenReady = false,
                hasPlayIntent = false,
                isProcessing = false,
            ),
        )
    }

    @Test
    fun `a missing session may be established while processing completes`() {
        assertFalse(
            SourceSeparationCompletedCacheUpgradePolicy.shouldDeferAutomaticUpgrade(
                hasActiveSession = false,
                playWhenReady = false,
                hasPlayIntent = true,
                isProcessing = true,
            ),
        )
    }

    private fun shouldDefer(
        playWhenReady: Boolean,
        hasPlayIntent: Boolean,
        isProcessing: Boolean,
    ): Boolean = SourceSeparationCompletedCacheUpgradePolicy.shouldDeferAutomaticUpgrade(
        hasActiveSession = true,
        playWhenReady = playWhenReady,
        hasPlayIntent = hasPlayIntent,
        isProcessing = isProcessing,
    )
}
