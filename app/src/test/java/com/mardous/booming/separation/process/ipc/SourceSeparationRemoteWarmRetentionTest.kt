package com.mardous.booming.separation.process.ipc

import com.mardous.booming.separation.process.SourceSeparationProcessDiagnostics
import com.mardous.booming.separation.process.SourceSeparationProcessMemoryDiagnostics
import com.mardous.booming.separation.process.SourceSeparationProcessSessionDiagnostics
import com.mardous.booming.separation.process.SourceSeparationProcessSessionState
import com.mardous.booming.separation.process.SourceSeparationSmapsSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSeparationRemoteWarmRetentionTest {
    @Test
    fun `only an idle healthy resident session is eligible`() {
        assertTrue(diagnostics().isWarmRetentionCandidate())
        assertFalse(diagnostics(activeRunId = "run-1").isWarmRetentionCandidate())
        assertFalse(diagnostics(activeLeaseCount = 1).isWarmRetentionCandidate())
        assertFalse(
            diagnostics(state = SourceSeparationProcessSessionState.Empty)
                .isWarmRetentionCandidate(),
        )
        assertFalse(
            diagnostics(state = SourceSeparationProcessSessionState.Poisoned, poisoned = true)
                .isWarmRetentionCandidate(),
        )
    }

    private fun diagnostics(
        activeRunId: String? = null,
        state: SourceSeparationProcessSessionState =
            SourceSeparationProcessSessionState.Resident,
        activeLeaseCount: Int = 0,
        poisoned: Boolean = false,
    ) = SourceSeparationProcessDiagnostics(
        processGeneration = 1L,
        processName = "test:source_separation",
        pid = 10,
        processStartTicks = 20L,
        capturedAtElapsedRealtimeNanos = 30L,
        activeRunId = activeRunId,
        memory = SourceSeparationProcessMemoryDiagnostics(
            pssBytes = 0L,
            nativePssBytes = 0L,
            threadCount = 0,
            mappedRegionCount = 0,
            smapsSource = SourceSeparationSmapsSource.Unavailable,
        ),
        session = SourceSeparationProcessSessionDiagnostics(
            state = state,
            sessionId = "session-1",
            nativeSessionCreationCount = 1,
            activeLeaseCount = activeLeaseCount,
            poisoned = poisoned,
            poisonReason = "test".takeIf { poisoned },
        ),
    )
}
