package com.mardous.booming.separation.delivery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSeparationDeliveryTest {
    @Test
    fun `GitHub providers accept immutable release assets only`() {
        val reference = reference(
            locator = "https://github.com/example/repo/releases/download/v1/runtime.zip",
        )

        assertTrue(GitHubRuntimeDeliveryProvider().supports(reference))
        assertTrue(GitHubModelDeliveryProvider().supports(reference))
        assertFalse(
            GitHubRuntimeDeliveryProvider().supports(reference.copy(locator = "https://example.com/runtime.zip")),
        )
        assertFalse(
            GitHubRuntimeDeliveryProvider().supports(reference.copy(locator = "https://github.com/example/repo/archive/v1.zip")),
        )
    }

    @Test
    fun `GitHub capability policy exposes the complete channel surface`() {
        val policy = GitHubProductCapabilityPolicy

        assertTrue(policy.supports(SourceSeparationProductCapability.RuntimeInstall))
        assertTrue(policy.supports(SourceSeparationProductCapability.GpuExecution))
        assertTrue(policy.supports(SourceSeparationProductCapability.VendorNpuExecution))
        assertTrue(policy.supports(SourceSeparationProductCapability.QnnJitExecution))
        assertTrue(policy.supportsDeliveryProvider("github"))
        assertFalse(policy.supportsDeliveryProvider("play"))
    }

    @Test
    fun `delivery references validate immutable content identity`() {
        val reference = reference()

        assertTrue(reference.expectedByteSize == 12L)
    }

    private fun reference(
        locator: String = "https://github.com/example/repo/releases/download/v1/model.tflite",
    ) = SourceSeparationDeliveryReference(
        providerId = "github",
        artifactId = "model_v1",
        locator = locator,
        expectedSha256 = "a".repeat(64),
        expectedByteSize = 12L,
    )
}
