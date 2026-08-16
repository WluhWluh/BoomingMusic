package com.mardous.booming.separation.process.ipc

import com.mardous.booming.separation.SourceSeparationPauseReason
import com.mardous.booming.separation.SourceSeparationPausedException
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunJournalLifecycle
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunTransitionType
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSeparationMultiStemHardTerminationPolicyTest {
    @Test
    fun `termination requires the exact controlled run`() {
        fun allowed(
            runId: String? = "run-a",
            generation: Long? = 7L,
            cancel: Boolean = true,
            pause: Boolean = false,
        ) = SourceSeparationMultiStemHardTerminationPolicy.canTerminate(
            activeRunId = runId,
            activeProcessGeneration = generation,
            activeCancelRequested = cancel,
            activePauseRequested = pause,
            requestedRunId = "run-a",
            requestedProcessGeneration = 7L,
        )

        assertTrue(allowed())
        assertTrue(allowed(cancel = false, pause = true))
        assertFalse(allowed(runId = "run-b"))
        assertFalse(allowed(generation = 8L))
        assertFalse(allowed(cancel = false, pause = false))
    }

    @Test
    fun `controlled death preserves cancel and model supersession semantics`() {
        val canceled = SourceSeparationMultiStemTerminalControl.Cancel
        assertTrue(SourceSeparationMultiStemHardTerminationPolicy.failure(canceled) is CancellationException)
        assertEquals(
            SourceSeparationCacheRunTransitionType.UserCanceled,
            SourceSeparationMultiStemHardTerminationPolicy.transition(canceled),
        )
        assertEquals(
            SourceSeparationCacheRunJournalLifecycle.Canceled,
            SourceSeparationMultiStemHardTerminationPolicy.lifecycle(canceled),
        )

        val superseded = SourceSeparationMultiStemTerminalControl.Pause(
            SourceSeparationPauseReason.ActiveModelSuperseded,
        )
        val paused = SourceSeparationMultiStemHardTerminationPolicy.failure(superseded)
        assertTrue(paused is SourceSeparationPausedException)
        assertEquals(
            SourceSeparationPauseReason.ActiveModelSuperseded,
            (paused as SourceSeparationPausedException).pauseReason,
        )
        assertEquals(
            SourceSeparationCacheRunTransitionType.ActiveModelSuperseded,
            SourceSeparationMultiStemHardTerminationPolicy.transition(superseded),
        )
        assertEquals(
            SourceSeparationCacheRunJournalLifecycle.Paused,
            SourceSeparationMultiStemHardTerminationPolicy.lifecycle(superseded),
        )
    }
}
