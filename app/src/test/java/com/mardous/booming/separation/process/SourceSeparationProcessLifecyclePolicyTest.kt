package com.mardous.booming.separation.process

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceSeparationProcessLifecyclePolicyTest {
    @Test
    fun `phase 3 lifecycle bounds remain frozen`() {
        assertEquals(300_000L,
            SourceSeparationProcessLifecyclePolicy.NO_CLIENT_WARM_RETENTION_MS)
        assertEquals(10_000L, SourceSeparationProcessLifecyclePolicy.RECYCLE_TIMEOUT_MS)
        assertEquals(150L,
            SourceSeparationProcessLifecyclePolicy.RECYCLE_ACKNOWLEDGEMENT_GRACE_MS)
        assertEquals(134_217_728L,
            SourceSeparationProcessLifecyclePolicy.MINIMUM_LARGEST_FREE_ADDRESS_GAP_BYTES)
    }
}
