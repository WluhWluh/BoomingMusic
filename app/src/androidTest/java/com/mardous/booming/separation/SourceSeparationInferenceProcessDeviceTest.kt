package com.mardous.booming.separation

import android.app.ActivityManager
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

    private companion object {
        const val MAXIMUM_IDLE_REMOTE_PSS_BYTES = 96L * 1024L * 1024L
        const val IDLE_SETTLE_MS = 2_000L
    }
}
