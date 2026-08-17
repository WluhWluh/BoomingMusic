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
import com.mardous.booming.separation.model.MdxTensorDataType
import com.mardous.booming.separation.model.MdxTensorLayout
import com.mardous.booming.separation.model.contract.SourceSeparationModelCatalog
import com.mardous.booming.separation.model.contract.SourceSeparationModelMetadata
import com.mardous.booming.separation.model.contract.toMdxExecutionProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class MdxLiteRtCpuInferenceSessionFactoryTest {
    @Test
    fun `HQ4 32-bit targets are rejected before the session allocator runs`() {
        val profile = profile("uvr_mdxnet_inst_hq_4")
        for (abi in listOf(MdxRuntimeAbi.ArmeabiV7a, MdxRuntimeAbi.X86)) {
            val allocator = RecordingAllocator()
            val factory = factory(
                abi = abi,
                allocator = allocator,
                processors = 4,
            )

            assertThrows(MdxInferenceCompatibilityException::class.java) {
                factory.create(artifact(profile), profile, MdxRuntimeSettings())
            }

            assertEquals(0, allocator.createCount)
        }
    }

    @Test
    fun `user attempts send HQ4 to the allocator on every packaged ABI`() {
        val profile = profile("uvr_mdxnet_inst_hq_4")
        for (abi in MdxRuntimeAbi.entries) {
            val allocator = RecordingAllocator()
            val factory = factory(
                abi = abi,
                allocator = allocator,
                processors = 4,
                compatibilityPolicy = MdxCompatibilityPolicy.AllowUserAttempts,
            )

            factory.create(artifact(profile), profile, MdxRuntimeSettings())

            assertEquals("Allocator was blocked for ${abi.androidName}", 1, allocator.createCount)
        }
    }

    @Test
    fun `known good target reaches allocator with bounded thread policy`() {
        val allocator = RecordingAllocator()
        val profile = profile("uvr_mdxnet_3_9662")
        val factory = factory(
            abi = MdxRuntimeAbi.Arm64V8a,
            allocator = allocator,
            processors = 12,
        )

        val session = factory.create(artifact(profile), profile, MdxRuntimeSettings(cpuThreads = 99))

        assertEquals(1, allocator.createCount)
        assertEquals(4, allocator.cpuThreads)
        assertEquals(MdxInferenceBackend.LiteRtCpu, session.diagnostics.backend)
    }

    @Test
    fun `reviewed HQ4 candidate reaches CPU when GPU is disabled`() {
        val allocator = RecordingAllocator()
        val profile = profile("uvr_mdxnet_inst_hq_4")
        val factory = factory(
            abi = MdxRuntimeAbi.Arm64V8a,
            allocator = allocator,
            processors = 8,
            compatibilityPolicy = MdxCompatibilityPolicy.AllowCandidates,
        )

        val session = factory.create(artifact(profile), profile, MdxRuntimeSettings())

        assertEquals(1, allocator.createCount)
        assertEquals(MdxInferenceBackend.LiteRtCpu, session.diagnostics.backend)
    }

    @Test
    fun `explicit XNNPACK flags are part of factory identity and reach allocator`() {
        val allocator = RecordingAllocator()
        val profile = profile("uvr_mdxnet_3_9662")
        val factory = factory(
            abi = MdxRuntimeAbi.X86_64,
            allocator = allocator,
            processors = 6,
            xnnPackFlags = 32,
        )

        factory.create(artifact(profile), profile, MdxRuntimeSettings())

        assertEquals(32, allocator.xnnPackFlags)
        assertTrue(factory.factoryId.endsWith("-xnnpack-flags-32"))
    }

    @Test
    fun `CPU thread policy leaves one core free within two to four threads`() {
        assertEquals(2, resolveLiteRtCpuThreadCount(1))
        assertEquals(2, resolveLiteRtCpuThreadCount(2))
        assertEquals(2, resolveLiteRtCpuThreadCount(3))
        assertEquals(3, resolveLiteRtCpuThreadCount(4))
        assertEquals(4, resolveLiteRtCpuThreadCount(5))
        assertEquals(4, resolveLiteRtCpuThreadCount(64))
    }

    @Test
    fun `tensor metadata validation rejects name type layout and shape drift`() {
        val declared = profile("uvr_mdxnet_3_9662").inputTensor

        validateLiteRtTensorMetadata(
            declared = declared,
            actualName = "input",
            actualDataType = MdxTensorDataType.Float32,
            actualLayout = MdxTensorLayout.Nhwc,
            actualShape = listOf(1, 2048, 256, 4),
            role = "input",
        )
        assertThrows(IllegalArgumentException::class.java) {
            validateLiteRtTensorMetadata(
                declared,
                "wrong_input",
                MdxTensorDataType.Float32,
                MdxTensorLayout.Nhwc,
                declared.shape,
                "input",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateLiteRtTensorMetadata(
                declared,
                "input",
                MdxTensorDataType.Float32,
                MdxTensorLayout.Nchw,
                declared.shape,
                "input",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateLiteRtTensorMetadata(
                declared,
                "input",
                MdxTensorDataType.Float32,
                MdxTensorLayout.Nhwc,
                listOf(1, 2048, 255, 4),
                "input",
            )
        }
    }

    @Test
    fun `non finite LiteRT output is rejected`() {
        requireFiniteMdxTensor(floatArrayOf(-1f, 0f, 1f))

        val error = assertThrows(IllegalArgumentException::class.java) {
            requireFiniteMdxTensor(floatArrayOf(0f, Float.NaN, Float.POSITIVE_INFINITY))
        }

        assertTrue(error.message.orEmpty().contains("index 1"))
    }

    private fun factory(
        abi: MdxRuntimeAbi,
        allocator: RecordingAllocator,
        processors: Int,
        xnnPackFlags: Int? = null,
        compatibilityPolicy: MdxCompatibilityPolicy =
            MdxCompatibilityPolicy.AllowUntestedInternal,
    ) = MdxLiteRtCpuInferenceSessionFactory(
        platformProvider = {
            MdxRuntimePlatform(35, abi, CATALOG_LITERT_VERSION)
        },
        compatibilityPolicy = compatibilityPolicy,
        sessionAllocator = allocator,
        availableProcessors = { processors },
        xnnPackFlags = xnnPackFlags,
    )

    private fun profile(modelId: String): MdxExecutionProfile =
        catalog.contracts.single { it.modelId == modelId }
            .toMdxExecutionProfile(catalog.runtimeQualifications)

    private fun artifact(profile: MdxExecutionProfile) = MdxModelArtifact(
        file = File("build/test-models/${profile.expectedFileName}"),
        byteSize = requireNotNull(profile.expectedByteSize),
        sha256 = requireNotNull(profile.expectedSha256),
    )

    private class RecordingAllocator : MdxLiteRtSessionAllocator {
        var createCount = 0
        var cpuThreads: Int? = null
        var xnnPackFlags: Int? = null

        override fun create(
            artifact: MdxModelArtifact,
            profile: MdxExecutionProfile,
            cpuThreads: Int,
            xnnPackFlags: Int?,
            compatibility: MdxCompatibilityDecision,
        ): MdxInferenceSession {
            createCount += 1
            this.cpuThreads = cpuThreads
            this.xnnPackFlags = xnnPackFlags
            return FakeSession(cpuThreads)
        }
    }

    private class FakeSession(cpuThreads: Int) : MdxInferenceSession {
        override val diagnostics = MdxRuntimeDiagnostics(
            runtimeName = "Fake LiteRT",
            backend = MdxInferenceBackend.LiteRtCpu,
            cpuThreads = cpuThreads,
            detail = "test",
        )

        override fun run(
            inputNchw: FloatArray,
            shouldCancel: () -> Boolean,
        ): FloatArray = inputNchw

        override fun close() = Unit
    }

    companion object {
        private const val CATALOG_LITERT_VERSION = "2.1.5"
        private lateinit var catalog: SourceSeparationModelCatalog

        @JvmStatic
        @BeforeClass
        fun loadCatalog() {
            val bytes = requireNotNull(
                MdxLiteRtCpuInferenceSessionFactoryTest::class.java.classLoader
                    ?.getResourceAsStream(SourceSeparationModelMetadata.CATALOG_ASSET_PATH)
            ).use { it.readBytes() }
            catalog = SourceSeparationModelMetadata.decodeBundledCatalog(bytes)
        }
    }
}
