package com.mardous.booming.separation.model.litert

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MdxLiteRtBoundedGpuRuntimeTest {
    @Test
    fun `exact bounded runtime capability is accepted`() {
        val decision = MdxLiteRtBoundedGpuContract.evaluate(exactCapability())

        assertTrue(decision.isExact)
        assertTrue(decision.detail.contains("artifact=2.1.5-bss.2"))
        assertTrue(decision.detail.contains("kernelBatch=1"))
        assertTrue(decision.detail.contains("queueWindow=1"))
    }

    @Test
    fun `missing or mismatched bounded capability is rejected`() {
        val unavailable = MdxLiteRtBoundedGpuContract.evaluate(
            exactCapability().copy(available = false)
        )
        val unbounded = MdxLiteRtBoundedGpuContract.evaluate(
            exactCapability().copy(commandQueueWindowSize = 0)
        )
        val wrongArtifact = MdxLiteRtBoundedGpuContract.evaluate(
            exactCapability().copy(artifactVersion = "2.1.5")
        )

        assertFalse(unavailable.isExact)
        assertFalse(unbounded.isExact)
        assertTrue(unbounded.detail.contains("queueWindow=0"))
        assertFalse(wrongArtifact.isExact)
        assertTrue(wrongArtifact.detail.contains("artifact=2.1.5"))
    }

    private fun exactCapability() = MdxLiteRtBoundedGpuCapability(
        available = true,
        schemaVersion = MdxLiteRtBoundedGpuContract.CAPABILITY_SCHEMA_VERSION,
        artifactVersion = MdxLiteRtBoundedGpuContract.ARTIFACT_VERSION,
        profileId = MdxLiteRtBoundedGpuContract.PROFILE_ID,
        kernelBatchSize = MdxLiteRtBoundedGpuContract.KERNEL_BATCH_SIZE,
        commandQueueWindowSize =
            MdxLiteRtBoundedGpuContract.COMMAND_QUEUE_WINDOW_SIZE,
    )
}
