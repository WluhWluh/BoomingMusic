package com.mardous.booming.separation.model.litert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MdxLiteRtRemoteFaultInjectionTest {
    @Test
    fun `remote failpoint names and fallback stages form a stable contract`() {
        MdxLiteRtRemoteFailpoint.entries.forEach { failpoint ->
            assertEquals(
                failpoint,
                MdxLiteRtRemoteFailpoint.parse(failpoint.argumentValue),
            )
        }
        assertEquals(
            MdxLiteRtAutoFailureStage.GpuCleanup,
            MdxLiteRtRemoteFailpoint.Cleanup.expectedFallbackStage,
        )
        assertTrue(MdxLiteRtRemoteFailpoint.Cleanup.requiresProcessRecycle)
        assertFalse(MdxLiteRtRemoteFailpoint.Invocation.requiresProcessRecycle)
        assertThrows(IllegalStateException::class.java) {
            MdxLiteRtRemoteFailpoint.parse("unknown")
        }
    }

    @Test
    fun `remote fault control rejects probe and identity hazards`() {
        val valid = MdxLiteRtRemoteFaultControl(
            token = "phase5-cleanup-1",
            failpoint = MdxLiteRtRemoteFailpoint.Cleanup.argumentValue,
            failureInvocationCount = 2,
            expiresAtElapsedRealtimeMs = 1L,
        )
        assertEquals(2, valid.failureInvocationCount)

        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(failpoint = MdxLiteRtRemoteFailpoint.None.argumentValue)
        }
        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(failureInvocationCount = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(token = "contains spaces")
        }
    }
}
