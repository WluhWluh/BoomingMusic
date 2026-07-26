package com.mardous.booming.separation.model.litert

import com.mardous.booming.separation.model.MdxCompatibilityDecision
import com.mardous.booming.separation.model.MdxCompatibilityPolicy
import com.mardous.booming.separation.model.MdxExecutionProfile
import com.mardous.booming.separation.model.MdxInferenceBackend
import com.mardous.booming.separation.model.MdxInferenceCompatibilityException
import com.mardous.booming.separation.model.MdxInferenceSession
import com.mardous.booming.separation.model.MdxModelArtifact
import com.mardous.booming.separation.model.MdxRuntimeAbi
import com.mardous.booming.separation.model.MdxRuntimeDiagnostics
import com.mardous.booming.separation.model.MdxRuntimePlatform
import com.mardous.booming.separation.model.MdxRuntimeSettings
import com.mardous.booming.separation.model.contract.SourceSeparationModelCatalog
import com.mardous.booming.separation.model.contract.SourceSeparationModelMetadata
import com.mardous.booming.separation.model.contract.toMdxExecutionProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class MdxLiteRtGpuInferenceSessionFactoryTest {
    @Test
    fun `GPU runtime profiles have stable distinct identities`() {
        val fp32 = MdxLiteRtGpuRuntimeProfile.AutomaticFp32V1
        val fp16 = MdxLiteRtGpuRuntimeProfile.AutomaticFp16V1
        val openClLow = MdxLiteRtGpuRuntimeProfile.LowPriorityOpenClFp32V1
        val openGl = MdxLiteRtGpuRuntimeProfile.OpenGlFp32V1

        assertEquals("gpu-auto-fp32-v1", fp32.profileId)
        assertEquals(MdxLiteRtGpuPrecision.Float32, fp32.precision)
        assertEquals("gpu-auto-fp16-v1", fp16.profileId)
        assertEquals(MdxLiteRtGpuPrecision.Float16, fp16.precision)
        assertEquals(MdxLiteRtGpuApi.OpenCl, openClLow.api)
        assertEquals(MdxLiteRtGpuPriority.Low, openClLow.priority)
        assertEquals(fp32.profileId, openClLow.qualificationProfileId)
        assertEquals(MdxLiteRtGpuApi.OpenGl, openGl.api)
        assertEquals(fp32.profileId, openGl.qualificationProfileId)
        assertNotEquals(fp32, fp16)
        assertThrows(IllegalArgumentException::class.java) {
            MdxLiteRtGpuRuntimeProfile(
                profileId = "GPU profile",
                api = MdxLiteRtGpuApi.Automatic,
                precision = MdxLiteRtGpuPrecision.Float32,
            )
        }
    }

    @Test
    fun `derived GPU profile is restricted to internal validation`() {
        val profile = profile("uvr_mdxnet_3_9662")
        val internalAllocator = RecordingGpuAllocator()

        assertThrows(IllegalArgumentException::class.java) {
            factory(
                abi = MdxRuntimeAbi.Arm64V8a,
                policy = MdxCompatibilityPolicy.KnownGoodOnly,
                allocator = RecordingGpuAllocator(),
                runtimeProfile = MdxLiteRtGpuRuntimeProfile.LowPriorityOpenClFp32V1,
            ).create(artifact(profile), profile, MdxRuntimeSettings())
        }
        factory(
            abi = MdxRuntimeAbi.Arm64V8a,
            policy = MdxCompatibilityPolicy.AllowUntestedInternal,
            allocator = internalAllocator,
            runtimeProfile = MdxLiteRtGpuRuntimeProfile.LowPriorityOpenClFp32V1,
        ).create(artifact(profile), profile, MdxRuntimeSettings())

        assertEquals(1, internalAllocator.createCount)
    }

    @Test
    fun `untested arm64 GPU target is internal only`() {
        val profile = profile("uvr_mdxnet_3_9662")
        val productionAllocator = RecordingGpuAllocator()
        val internalAllocator = RecordingGpuAllocator()

        assertThrows(MdxInferenceCompatibilityException::class.java) {
            factory(
                abi = MdxRuntimeAbi.Arm64V8a,
                policy = MdxCompatibilityPolicy.KnownGoodOnly,
                allocator = productionAllocator,
            ).create(artifact(profile), profile, MdxRuntimeSettings())
        }
        val session = factory(
            abi = MdxRuntimeAbi.Arm64V8a,
            policy = MdxCompatibilityPolicy.AllowUntestedInternal,
            allocator = internalAllocator,
        ).create(artifact(profile), profile, MdxRuntimeSettings())

        assertEquals(0, productionAllocator.createCount)
        assertEquals(1, internalAllocator.createCount)
        assertEquals(MdxInferenceBackend.LiteRtGpu, session.diagnostics.backend)
        assertTrue(session.diagnostics.detail.contains("gpu-auto-fp32-v1"))
    }

    @Test
    fun `ABI without exact GPU record is rejected before allocation`() {
        val profile = profile("uvr_mdxnet_3_9662")
        for (abi in listOf(MdxRuntimeAbi.ArmeabiV7a, MdxRuntimeAbi.X86, MdxRuntimeAbi.X86_64)) {
            val allocator = RecordingGpuAllocator()

            assertThrows(MdxInferenceCompatibilityException::class.java) {
                factory(
                    abi = abi,
                    policy = MdxCompatibilityPolicy.AllowUntestedInternal,
                    allocator = allocator,
                ).create(artifact(profile), profile, MdxRuntimeSettings())
            }

            assertEquals(0, allocator.createCount)
        }
    }

    @Test
    fun `GPU profile identity participates in the factory key`() {
        val fp32 = factory(
            abi = MdxRuntimeAbi.Arm64V8a,
            policy = MdxCompatibilityPolicy.AllowUntestedInternal,
            allocator = RecordingGpuAllocator(),
            runtimeProfile = MdxLiteRtGpuRuntimeProfile.AutomaticFp32V1,
        )
        val fp16 = factory(
            abi = MdxRuntimeAbi.Arm64V8a,
            policy = MdxCompatibilityPolicy.AllowUntestedInternal,
            allocator = RecordingGpuAllocator(),
            runtimeProfile = MdxLiteRtGpuRuntimeProfile.AutomaticFp16V1,
        )

        assertNotEquals(fp32.factoryId, fp16.factoryId)
        assertTrue(fp32.factoryId.contains("gpu-auto-fp32-v1"))
        assertTrue(fp16.factoryId.contains("gpu-auto-fp16-v1"))
    }

    private fun factory(
        abi: MdxRuntimeAbi,
        policy: MdxCompatibilityPolicy,
        allocator: RecordingGpuAllocator,
        runtimeProfile: MdxLiteRtGpuRuntimeProfile =
            MdxLiteRtGpuRuntimeProfile.AutomaticFp32V1,
    ) = MdxLiteRtGpuInferenceSessionFactory(
        runtimeProfile = runtimeProfile,
        platformProvider = { MdxRuntimePlatform(35, abi) },
        compatibilityPolicy = policy,
        sessionAllocator = allocator,
    )

    private fun profile(modelId: String): MdxExecutionProfile =
        catalog.contracts.single { it.modelId == modelId }
            .toMdxExecutionProfile(catalog.runtimeQualifications)

    private fun artifact(profile: MdxExecutionProfile) = MdxModelArtifact(
        file = File("build/test-models/${profile.expectedFileName}"),
        byteSize = requireNotNull(profile.expectedByteSize),
        sha256 = requireNotNull(profile.expectedSha256),
    )

    private class RecordingGpuAllocator : MdxLiteRtGpuSessionAllocator {
        var createCount = 0

        override fun create(
            artifact: MdxModelArtifact,
            profile: MdxExecutionProfile,
            runtimeProfile: MdxLiteRtGpuRuntimeProfile,
            compatibility: MdxCompatibilityDecision,
        ): MdxInferenceSession {
            createCount += 1
            return FakeGpuSession(runtimeProfile, compatibility)
        }
    }

    private class FakeGpuSession(
        runtimeProfile: MdxLiteRtGpuRuntimeProfile,
        compatibility: MdxCompatibilityDecision,
    ) : MdxInferenceSession {
        override val diagnostics = MdxRuntimeDiagnostics(
            runtimeName = "Fake LiteRT",
            backend = MdxInferenceBackend.LiteRtGpu,
            cpuThreads = null,
            detail = "profile=${runtimeProfile.profileId}, status=${compatibility.outcome.name}",
        )

        override fun run(
            inputNchw: FloatArray,
            shouldCancel: () -> Boolean,
        ): FloatArray = inputNchw

        override fun close() = Unit
    }

    companion object {
        private lateinit var catalog: SourceSeparationModelCatalog

        @JvmStatic
        @BeforeClass
        fun loadCatalog() {
            val bytes = requireNotNull(
                MdxLiteRtGpuInferenceSessionFactoryTest::class.java.classLoader
                    ?.getResourceAsStream(SourceSeparationModelMetadata.CATALOG_ASSET_PATH)
            ).use { it.readBytes() }
            catalog = SourceSeparationModelMetadata.decodeBundledCatalog(bytes)
        }
    }
}
