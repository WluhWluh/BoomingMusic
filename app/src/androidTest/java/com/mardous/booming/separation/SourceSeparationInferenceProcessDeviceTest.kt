package com.mardous.booming.separation

import android.app.ActivityManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mardous.booming.AppProcessResolver
import com.mardous.booming.separation.process.SourceSeparationExecutionHostControlResult
import com.mardous.booming.separation.process.ipc.BoundRemoteSourceSeparationExecutionHost
import com.mardous.booming.separation.process.ipc.SourceSeparationRemoteConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourceSeparationInferenceProcessDeviceTest {
    @Test
    fun validateBoundServiceStartup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val host = BoundRemoteSourceSeparationExecutionHost(context)

        try {
            val generation = host.processGeneration
            val diagnostics = host.connectionDiagnostics
            val expectedProcessName = context.packageName +
                AppProcessResolver.SOURCE_SEPARATION_PROCESS_SUFFIX

            assertTrue(generation > 0L)
            assertEquals(SourceSeparationRemoteConnectionState.Connected, diagnostics.state)
            assertEquals(expectedProcessName, diagnostics.processName)
            assertNotEquals(android.os.Process.myPid(), diagnostics.pid)
            assertTrue(requireNotNull(diagnostics.idlePssBytes) > 0L)
            assertTrue(requireNotNull(diagnostics.processStartTicks) > 0L)
            val processDiagnostics = host.processDiagnostics()
            assertEquals(generation, processDiagnostics.processGeneration)
            assertEquals(diagnostics.pid, processDiagnostics.pid)
            assertEquals(diagnostics.processStartTicks, processDiagnostics.processStartTicks)
            assertTrue(processDiagnostics.memory.vmSizeBytes != null)
            assertTrue(processDiagnostics.memory.vmPeakBytes != null)
            assertTrue(processDiagnostics.memory.vmRssBytes != null)
            assertTrue(processDiagnostics.memory.pssBytes > 0L)
            assertTrue(processDiagnostics.memory.nativePssBytes >= 0L)
            assertTrue(processDiagnostics.memory.threadCount > 0)
            assertTrue(processDiagnostics.memory.mappedRegionCount > 0)
            assertTrue(processDiagnostics.memory.largestFreeAddressGapBytes != null)
            assertEquals(0, diagnostics.expectedBinderDeathCount)
            assertEquals(0, diagnostics.unexpectedBinderDeathCount)
            Thread.sleep(IDLE_SETTLE_MS)
            val manager = context.getSystemService(ActivityManager::class.java)
            val settledPssBytes = manager.getProcessMemoryInfo(
                intArrayOf(requireNotNull(diagnostics.pid)),
            ).single().totalPss.toLong() * 1_024L
            assertTrue(
                "Idle inference process exceeded the frozen 96 MiB gate: " +
                    "startup=${diagnostics.idlePssBytes}, " +
                    "settled=$settledPssBytes",
                settledPssBytes <= MAXIMUM_IDLE_REMOTE_PSS_BYTES,
            )
            assertTrue(manager.runningAppProcesses.orEmpty().any { process ->
                process.pid == diagnostics.pid && process.processName == expectedProcessName
            })
            assertEquals(
                SourceSeparationExecutionHostControlResult.NoActiveRun,
                host.pause("no-active-run", generation),
            )
            assertNull(host.snapshot("no-active-run", generation))
        } finally {
            host.close()
        }

        assertEquals(
            SourceSeparationRemoteConnectionState.Closed,
            host.connectionDiagnostics.state,
        )

        val reboundHost = BoundRemoteSourceSeparationExecutionHost(context)
        try {
            assertTrue(reboundHost.processGeneration > 0L)
            assertEquals(
                SourceSeparationRemoteConnectionState.Connected,
                reboundHost.connectionDiagnostics.state,
            )
        } finally {
            reboundHost.close()
        }
        reboundHost.close()
        assertEquals(
            SourceSeparationRemoteConnectionState.Closed,
            reboundHost.connectionDiagnostics.state,
        )
    }

    @Test
    fun reconnectsAfterIdleRemoteProcessDeath() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val host = BoundRemoteSourceSeparationExecutionHost(context)

        try {
            val firstGeneration = host.processGeneration
            val firstPid = requireNotNull(host.connectionDiagnostics.pid)
            android.os.Process.killProcess(firstPid)

            val deathDeadline = SystemClock.elapsedRealtime() + PROCESS_DEATH_TIMEOUT_MS
            while (host.connectionDiagnostics.state !=
                SourceSeparationRemoteConnectionState.Dead &&
                SystemClock.elapsedRealtime() < deathDeadline
            ) {
                SystemClock.sleep(PROCESS_STATE_POLL_MS)
            }
            assertEquals(
                SourceSeparationRemoteConnectionState.Dead,
                host.connectionDiagnostics.state,
            )
            assertEquals(1, host.connectionDiagnostics.unexpectedBinderDeathCount)
            assertEquals(false, host.connectionDiagnostics.lastBinderDeath?.expected)
            assertEquals(
                firstGeneration,
                host.connectionDiagnostics.lastBinderDeath?.processGeneration,
            )

            val secondGeneration = host.processGeneration
            val secondDiagnostics = host.connectionDiagnostics
            assertNotEquals(firstGeneration, secondGeneration)
            assertNotEquals(firstPid, secondDiagnostics.pid)
            assertEquals(
                SourceSeparationRemoteConnectionState.Connected,
                secondDiagnostics.state,
            )
        } finally {
            host.close()
        }
    }

    private companion object {
        const val MAXIMUM_IDLE_REMOTE_PSS_BYTES = 96L * 1024L * 1024L
        const val IDLE_SETTLE_MS = 2_000L
        const val PROCESS_DEATH_TIMEOUT_MS = 10_000L
        const val PROCESS_STATE_POLL_MS = 50L
    }
}
