package com.mardous.booming.separation

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mardous.booming.R
import com.mardous.booming.separation.process.SourceSeparationForegroundControlAction
import com.mardous.booming.separation.process.SourceSeparationForegroundLeaseLifecycle
import com.mardous.booming.separation.process.SourceSeparationForegroundLeaseRequest
import com.mardous.booming.separation.process.SourceSeparationForegroundPlatformPolicy
import com.mardous.booming.separation.process.SourceSeparationForegroundServiceDiagnostics
import com.mardous.booming.separation.process.ipc.BoundRemoteSourceSeparationExecutionHost
import com.mardous.booming.separation.process.ipc.SourceSeparationExecutionService
import com.mardous.booming.separation.process.ipc.SourceSeparationMediaProcessingForegroundController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourceSeparationForegroundServiceDeviceTest {
    @Test
    fun notificationControlsOwnOneExactPendingLease() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        grantNotificationPermission(context)
        val host = BoundRemoteSourceSeparationExecutionHost(
            context,
            connectionTimeoutMs = COLD_INSTALL_CONNECTION_TIMEOUT_MS,
        )
        var cleanupLease: SourceSeparationForegroundLeaseRequest? = null

        try {
            val generation = host.processGeneration
            val firstLease = lease(
                leaseId = "foreground-device-lease-0001",
                runId = "manual-device-run-1",
                processGeneration = generation,
                displayName = "Foreground test one",
            )
            cleanupLease = firstLease
            startForegroundLease(context, firstLease)

            val firstActive = awaitDiagnostics(host) { diagnostics ->
                diagnostics.activeLease?.request == firstLease
            }.activeLease
            assertNotNull(firstActive)
            assertEquals(SourceSeparationForegroundLeaseLifecycle.AwaitingRun,
                firstActive?.lifecycle)
            assertEquals(expectedPlatformPolicy(), firstActive?.platformPolicy)

            val manager = context.getSystemService(NotificationManager::class.java)
            val firstNotification = awaitNotification(manager)
            assertEquals(SourceSeparationMediaProcessingForegroundController.CHANNEL_ID,
                firstNotification.channelId)
            assertEquals(Notification.CATEGORY_PROGRESS, firstNotification.category)
            assertTrue(firstNotification.flags and Notification.FLAG_ONGOING_EVENT != 0)
            assertEquals("Foreground test one",
                firstNotification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
            assertEquals(
                listOf(
                    context.getString(R.string.action_pause),
                    context.getString(R.string.action_cancel),
                ),
                firstNotification.actions.map { it.title.toString() },
            )

            firstNotification.actions[PAUSE_ACTION_INDEX].actionIntent.send()
            val firstStopped = awaitDiagnostics(host) { diagnostics ->
                diagnostics.activeLease == null &&
                    diagnostics.lastStoppedLease?.request == firstLease
            }.lastStoppedLease
            assertEquals("pause-before-admission", firstStopped?.stopReason)
            assertEquals(
                SourceSeparationForegroundControlAction.Pause,
                firstStopped?.controls?.single()?.action,
            )
            awaitNotificationRemoval(manager)

            val secondLease = lease(
                leaseId = "foreground-device-lease-0002",
                runId = "manual-device-run-2",
                processGeneration = generation,
                displayName = "Foreground test two",
            )
            cleanupLease = secondLease
            startForegroundLease(context, secondLease)
            awaitDiagnostics(host) { diagnostics ->
                diagnostics.activeLease?.request == secondLease
            }
            val secondNotification = awaitNotification(manager)

            firstNotification.actions[CANCEL_ACTION_INDEX].actionIntent.send()
            val afterStaleControl = awaitDiagnostics(host) { diagnostics ->
                diagnostics.activeLease?.request == secondLease
            }
            assertEquals(secondLease, afterStaleControl.activeLease?.request)
            assertTrue(afterStaleControl.activeLease?.controls.orEmpty().isEmpty())

            secondNotification.actions[CANCEL_ACTION_INDEX].actionIntent.send()
            val secondStopped = awaitDiagnostics(host) { diagnostics ->
                diagnostics.activeLease == null &&
                    diagnostics.lastStoppedLease?.request == secondLease
            }.lastStoppedLease
            assertEquals("cancel-before-admission", secondStopped?.stopReason)
            assertEquals(
                SourceSeparationForegroundControlAction.Cancel,
                secondStopped?.controls?.single()?.action,
            )
            awaitNotificationRemoval(manager)

            secondNotification.actions[CANCEL_ACTION_INDEX].actionIntent.send()
            val afterDuplicate = awaitDiagnostics(host) { diagnostics ->
                diagnostics.activeLease == null &&
                    diagnostics.lastStoppedLease?.request == secondLease
            }
            assertEquals(1, afterDuplicate.lastStoppedLease?.controls?.size)
            assertNull(afterDuplicate.activeLease)
            cleanupLease = null
        } finally {
            cleanupLease?.let { request ->
                runCatching {
                    context.startService(
                        SourceSeparationExecutionService.foregroundControlIntent(
                            context = context,
                            request = request,
                            commandId = "device-test-cleanup-${request.leaseId}",
                            action = SourceSeparationForegroundControlAction.Cancel,
                        )
                    )
                }
            }
            host.close()
        }
    }

    private fun startForegroundLease(
        context: Context,
        request: SourceSeparationForegroundLeaseRequest,
    ) {
        ContextCompat.startForegroundService(
            context,
            SourceSeparationExecutionService.foregroundStartIntent(context, request),
        )
    }

    private fun awaitDiagnostics(
        host: BoundRemoteSourceSeparationExecutionHost,
        predicate: (SourceSeparationForegroundServiceDiagnostics) -> Boolean,
    ): SourceSeparationForegroundServiceDiagnostics = awaitValue {
        host.processDiagnostics().foregroundService.takeIf(predicate)
    }

    private fun awaitNotification(manager: NotificationManager): Notification = awaitValue {
        manager.activeNotifications
            .firstOrNull {
                it.id == SourceSeparationMediaProcessingForegroundController.NOTIFICATION_ID
            }
            ?.notification
    }

    private fun awaitNotificationRemoval(manager: NotificationManager) {
        awaitValue {
            true.takeIf {
                manager.activeNotifications.none { notification ->
                    notification.id ==
                        SourceSeparationMediaProcessingForegroundController.NOTIFICATION_ID
                }
            }
        }
    }

    private fun <T> awaitValue(block: () -> T?): T {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        var value: T?
        do {
            value = block()
            if (value != null) return value
            SystemClock.sleep(POLL_INTERVAL_MS)
        } while (SystemClock.elapsedRealtime() < deadline)
        return requireNotNull(block()) { "Timed out waiting for foreground-service state." }
    }

    private fun grantNotificationPermission(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(
                "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS",
            )
            .close()
    }

    private fun expectedPlatformPolicy() = if (Build.VERSION.SDK_INT >= 35) {
        SourceSeparationForegroundPlatformPolicy.TimedMediaProcessing
    } else {
        SourceSeparationForegroundPlatformPolicy.LegacyMediaProcessing
    }

    private fun lease(
        leaseId: String,
        runId: String,
        processGeneration: Long,
        displayName: String,
    ) = SourceSeparationForegroundLeaseRequest(
        leaseId = leaseId,
        runId = runId,
        processGeneration = processGeneration,
        displayName = displayName,
    )

    private companion object {
        const val PAUSE_ACTION_INDEX = 0
        const val CANCEL_ACTION_INDEX = 1
        const val TIMEOUT_MS = 10_000L
        const val POLL_INTERVAL_MS = 50L
        const val COLD_INSTALL_CONNECTION_TIMEOUT_MS = 60_000L
    }
}
