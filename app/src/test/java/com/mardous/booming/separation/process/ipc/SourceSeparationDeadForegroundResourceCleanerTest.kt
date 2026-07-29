package com.mardous.booming.separation.process.ipc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSeparationDeadForegroundResourceCleanerTest {
    @Test
    fun `disabled foreground policy leaves resources untouched`() {
        val fixture = fixture(currentStartTicks = null)

        assertFalse(
            fixture.cleaner.cleanupIfProcessDied(
                SourceSeparationRemoteForegroundPolicy.Disabled,
                pid = 100,
                expectedProcessStartTicks = 200L,
            )
        )
        fixture.assertUntouched()
    }

    @Test
    fun `matching process incarnation leaves resources untouched`() {
        val fixture = fixture(currentStartTicks = 200L)

        assertFalse(
            fixture.cleaner.cleanupIfProcessDied(
                SourceSeparationRemoteForegroundPolicy.ManualFullSong,
                pid = 100,
                expectedProcessStartTicks = 200L,
            )
        )
        fixture.assertUntouched(expectedProcessChecks = 1)
    }

    @Test
    fun `confirmed binder death cleans before proc entry is reaped`() {
        val fixture = fixture(currentStartTicks = 200L)

        assertTrue(
            fixture.cleaner.cleanupIfProcessDied(
                SourceSeparationRemoteForegroundPolicy.ManualFullSong,
                pid = 100,
                expectedProcessStartTicks = 200L,
                processDeathConfirmed = true,
            )
        )
        fixture.assertCleaned(expectedProcessChecks = 0)
    }

    @Test
    fun `missing process incarnation releases orphaned foreground resources`() {
        val fixture = fixture(currentStartTicks = null)

        assertTrue(
            fixture.cleaner.cleanupIfProcessDied(
                SourceSeparationRemoteForegroundPolicy.ManualFullSong,
                pid = 100,
                expectedProcessStartTicks = 200L,
            )
        )
        fixture.assertCleaned()
    }

    @Test
    fun `reused pid releases resources from the dead incarnation`() {
        val fixture = fixture(currentStartTicks = 201L)

        assertTrue(
            fixture.cleaner.cleanupIfProcessDied(
                SourceSeparationRemoteForegroundPolicy.ManualFullSong,
                pid = 100,
                expectedProcessStartTicks = 200L,
            )
        )
        fixture.assertCleaned()
    }

    @Test
    fun `notification cleanup still runs when stopping the service fails`() {
        val fixture = fixture(
            currentStartTicks = null,
            stopServiceFailure = IllegalStateException("stop failed"),
        )

        assertTrue(
            fixture.cleaner.cleanupIfProcessDied(
                SourceSeparationRemoteForegroundPolicy.ManualFullSong,
                pid = 100,
                expectedProcessStartTicks = 200L,
            )
        )
        fixture.assertCleaned()
    }

    private fun fixture(
        currentStartTicks: Long?,
        stopServiceFailure: Throwable? = null,
    ): Fixture = Fixture(currentStartTicks, stopServiceFailure)

    private class Fixture(
        private val currentStartTicks: Long?,
        private val stopServiceFailure: Throwable?,
    ) {
        var processChecks = 0
        var serviceStops = 0
        var notificationCancellations = 0

        val cleaner = SourceSeparationDeadForegroundResourceCleaner(
            processStartTicks = {
                processChecks += 1
                currentStartTicks
            },
            stopService = {
                serviceStops += 1
                stopServiceFailure?.let { throw it }
            },
            cancelNotification = {
                notificationCancellations += 1
            },
        )

        fun assertUntouched(expectedProcessChecks: Int = 0) {
            check(processChecks == expectedProcessChecks)
            check(serviceStops == 0)
            check(notificationCancellations == 0)
        }

        fun assertCleaned(expectedProcessChecks: Int = 1) {
            check(processChecks == expectedProcessChecks)
            check(serviceStops == 1)
            check(notificationCancellations == 1)
        }
    }
}
