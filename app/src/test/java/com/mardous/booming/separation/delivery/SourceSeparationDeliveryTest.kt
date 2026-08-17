package com.mardous.booming.separation.delivery

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
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
    fun `GitHub capability policy exposes only implemented release features`() {
        val policy = GitHubProductCapabilityPolicy

        assertTrue(policy.supports(SourceSeparationProductCapability.RuntimeInstall))
        assertTrue(policy.supports(SourceSeparationProductCapability.CpuExecution))
        assertTrue(policy.supports(SourceSeparationProductCapability.GpuExecution))
        assertTrue(policy.supports(SourceSeparationProductCapability.CustomModelImport))
        assertTrue(policy.supports(SourceSeparationProductCapability.RuntimeDiagnostics))
        assertFalse(policy.supports(SourceSeparationProductCapability.VendorNpuExecution))
        assertFalse(policy.supports(SourceSeparationProductCapability.AotExecution))
        assertFalse(policy.supports(SourceSeparationProductCapability.QnnJitExecution))
        assertFalse(policy.supports(SourceSeparationProductCapability.CustomRuntimeImport))
        assertEquals(
            setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86"),
            setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
                .filterTo(mutableSetOf(), policy::supportsCpuRuntimeAbi),
        )
        assertFalse(policy.supportsCpuRuntimeAbi("mips"))
        assertTrue(policy.supportsDeliveryProvider("github"))
        assertFalse(policy.supportsDeliveryProvider("play"))
    }

    @Test
    fun `deterministic providers isolate transport behavior without network access`() {
        val runtimeBytes = "runtime".toByteArray()
        val modelBytes = "model".toByteArray()
        val runtime = DeterministicRuntimeDeliveryProvider(
            artifacts = mapOf("runtime_v1" to runtimeBytes),
            supportsPlatformManagedPayloads = true,
        )
        val model = DeterministicModelDeliveryProvider(
            artifacts = mapOf("model_v1" to modelBytes),
        )
        val runtimeReference = reference(
            providerId = runtime.providerId,
            artifactId = "runtime_v1",
            byteSize = runtimeBytes.size.toLong(),
        )
        val modelReference = reference(
            providerId = model.providerId,
            artifactId = "model_v1",
            byteSize = modelBytes.size.toLong(),
        )

        runtime.acquire(runtimeReference).use { payload ->
            assertArrayEquals(runtimeBytes, payload.openStream().readBytes())
        }
        model.acquire(modelReference).use { payload ->
            assertArrayEquals(modelBytes, payload.openStream().readBytes())
        }

        assertEquals(1, runtime.acquireCount.get())
        assertEquals(1, model.acquireCount.get())
        assertTrue(runtime.capabilities.supportsPlatformManagedPayloads)
        assertFalse(model.supports(runtimeReference))
    }

    @Test
    fun `deterministic providers reject unsupported and injected failure cases`() {
        val expectedFailure = IllegalStateException("offline")
        val provider = DeterministicRuntimeDeliveryProvider(
            artifacts = mapOf("runtime_v1" to byteArrayOf(1)),
            acquireFailure = expectedFailure,
        )
        val supported = reference(
            providerId = provider.providerId,
            artifactId = "runtime_v1",
            byteSize = 1L,
        )

        assertThrows(IllegalStateException::class.java) { provider.acquire(supported) }
        assertThrows(IllegalArgumentException::class.java) {
            provider.acquire(supported.copy(artifactId = "missing"))
        }
        assertEquals(1, provider.acquireCount.get())
    }

    @Test
    fun `delivery references validate immutable content identity`() {
        val reference = reference()

        assertTrue(reference.expectedByteSize == 12L)
    }

    private fun reference(
        locator: String = "https://github.com/example/repo/releases/download/v1/model.tflite",
        providerId: String = "github",
        artifactId: String = "model_v1",
        byteSize: Long = 12L,
    ) = SourceSeparationDeliveryReference(
        providerId = providerId,
        artifactId = artifactId,
        locator = locator,
        expectedSha256 = "a".repeat(64),
        expectedByteSize = byteSize,
    )
}
