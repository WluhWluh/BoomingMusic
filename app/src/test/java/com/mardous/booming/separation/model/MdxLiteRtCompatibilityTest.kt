package com.mardous.booming.separation.model

import com.mardous.booming.separation.model.contract.SourceSeparationModelCatalog
import com.mardous.booming.separation.model.contract.SourceSeparationModelMetadata
import com.mardous.booming.separation.model.contract.toMdxExecutionProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class MdxLiteRtCompatibilityTest {
    @Test
    fun `known good CPU target is supported`() {
        val decision = decision(
            modelId = "uvr_mdxnet_3_9662",
            platform = MdxRuntimePlatform(35, MdxRuntimeAbi.Arm64V8a),
            policy = MdxCompatibilityPolicy.KnownGoodOnly,
        )

        assertEquals(MdxCompatibilityOutcome.Supported, decision.outcome)
        assertTrue(decision.isAllowed)
        assertTrue(decision.evidence.orEmpty().contains("S10"))
    }

    @Test
    fun `rejected target remains blocked internally`() {
        val platform = MdxRuntimePlatform(35, MdxRuntimeAbi.X86_64)

        val production = decision(
            "uvr_mdxnet_inst_hq_4",
            platform,
            MdxCompatibilityPolicy.KnownGoodOnly,
        )
        val internal = decision(
            "uvr_mdxnet_inst_hq_4",
            platform,
            MdxCompatibilityPolicy.AllowUntestedInternal,
        )

        assertEquals(MdxCompatibilityOutcome.Unsupported, production.outcome)
        assertFalse(production.isAllowed)
        assertEquals(MdxCompatibilityOutcome.Unsupported, internal.outcome)
        assertFalse(internal.isAllowed)
    }

    @Test
    fun `candidate GPU profile requires explicit product or internal admission`() {
        val profile = profile("uvr_mdxnet_3_9662")
        val platform = MdxRuntimePlatform(35, MdxRuntimeAbi.Arm64V8a)
        val productCandidate = MdxLiteRtCompatibilityResolver.resolve(
            profile = profile,
            backend = MdxInferenceBackend.LiteRtGpu,
            platform = platform,
            policy = MdxCompatibilityPolicy.AllowCandidates,
            profileId = "gpu-auto-fp32-v1",
            precision = MdxRuntimePrecision.Fp32,
        )
        val candidate = MdxLiteRtCompatibilityResolver.resolve(
            profile = profile,
            backend = MdxInferenceBackend.LiteRtGpu,
            platform = platform,
            policy = MdxCompatibilityPolicy.AllowUntestedInternal,
            profileId = "gpu-auto-fp32-v1",
            precision = MdxRuntimePrecision.Fp32,
        )
        val rejectedFp16 = MdxLiteRtCompatibilityResolver.resolve(
            profile = profile,
            backend = MdxInferenceBackend.LiteRtGpu,
            platform = platform,
            policy = MdxCompatibilityPolicy.AllowUntestedInternal,
            profileId = "gpu-auto-fp16-v1",
            precision = MdxRuntimePrecision.Fp16,
        )

        assertEquals(MdxCompatibilityOutcome.Experimental, productCandidate.outcome)
        assertEquals(MdxCompatibilityOutcome.InternalValidationOnly, candidate.outcome)
        assertEquals(MdxCompatibilityOutcome.Unsupported, rejectedFp16.outcome)
    }

    @Test
    fun `reviewed HQ4 CPU and GPU candidates are product experimental on arm64`() {
        val profile = profile("uvr_mdxnet_inst_hq_4")
        val platform = MdxRuntimePlatform(35, MdxRuntimeAbi.Arm64V8a)

        val cpu = MdxLiteRtCompatibilityResolver.resolve(
            profile,
            MdxInferenceBackend.LiteRtCpu,
            platform,
            MdxCompatibilityPolicy.AllowCandidates,
        )
        val gpu = MdxLiteRtCompatibilityResolver.resolve(
            profile,
            MdxInferenceBackend.LiteRtGpu,
            platform,
            MdxCompatibilityPolicy.AllowCandidates,
            profileId = "gpu-auto-fp32-v1",
            precision = MdxRuntimePrecision.Fp32,
        )

        assertEquals(MdxCompatibilityOutcome.Experimental, cpu.outcome)
        assertEquals(MdxCompatibilityOutcome.Experimental, gpu.outcome)
    }

    @Test
    fun `explicitly unsupported catalog targets and missing targets remain blocked internally`() {
        val lifecycleUnsafeX86 = listOf("uvr_mdxnet_3_9662", "uvr_mdxnet_kara").map { modelId ->
            decision(
                modelId,
                MdxRuntimePlatform(26, MdxRuntimeAbi.X86),
                MdxCompatibilityPolicy.AllowUntestedInternal,
            )
        }
        val hq4X86 = decision(
            "uvr_mdxnet_inst_hq_4",
            MdxRuntimePlatform(26, MdxRuntimeAbi.X86),
            MdxCompatibilityPolicy.AllowUntestedInternal,
        )
        val profileWithoutX86 = profile("uvr_mdxnet_3_9662").copy(
            runtimeCompatibility = profile("uvr_mdxnet_3_9662").runtimeCompatibility.filterNot {
                it.abi == MdxRuntimeAbi.X86 && it.backend == MdxInferenceBackend.LiteRtCpu
            }
        )
        val missing = MdxLiteRtCompatibilityResolver.resolve(
            profileWithoutX86,
            MdxInferenceBackend.LiteRtCpu,
            MdxRuntimePlatform(26, MdxRuntimeAbi.X86),
            MdxCompatibilityPolicy.AllowUntestedInternal,
        )

        lifecycleUnsafeX86.forEach { decision ->
            assertEquals(MdxCompatibilityOutcome.Unsupported, decision.outcome)
            assertTrue(decision.evidence.orEmpty().contains("repeated LiteRT/XNNPACK"))
        }
        assertEquals(MdxCompatibilityOutcome.Unsupported, hq4X86.outcome)
        assertTrue(hq4X86.evidence.orEmpty().contains("XNNPACK tensor allocation failed"))
        assertEquals(MdxCompatibilityOutcome.Unsupported, missing.outcome)
        assertNull(missing.evidence)
    }

    @Test
    fun `minimum API is enforced before runtime status`() {
        val decision = decision(
            "uvr_mdxnet_3_9662",
            MdxRuntimePlatform(25, MdxRuntimeAbi.Arm64V8a),
            MdxCompatibilityPolicy.AllowUntestedInternal,
        )

        assertEquals(MdxCompatibilityOutcome.Unsupported, decision.outcome)
        assertTrue(decision.reason.contains("below"))
    }

    @Test
    fun `process ABI resolution uses the actual bitness`() {
        assertEquals(MdxRuntimeAbi.Arm64V8a, resolveMdxProcessAbi("aarch64", true))
        assertEquals(MdxRuntimeAbi.ArmeabiV7a, resolveMdxProcessAbi("aarch64", false))
        assertEquals(MdxRuntimeAbi.X86_64, resolveMdxProcessAbi("x86_64", true))
        assertEquals(MdxRuntimeAbi.X86, resolveMdxProcessAbi("x86_64", false))
        assertNull(resolveMdxProcessAbi("riscv64", true))
    }

    @Test
    fun `runtime ABI follows a native bridge target when process ISA is not advertised`() {
        assertEquals(
            MdxRuntimeAbi.X86_64,
            resolveMdxRuntimeAbi("aarch64", true, listOf("x86_64", "x86")),
        )
        assertEquals(
            MdxRuntimeAbi.ArmeabiV7a,
            resolveMdxRuntimeAbi("armv8l", false, listOf("arm64-v8a", "armeabi-v7a")),
        )
    }

    private fun decision(
        modelId: String,
        platform: MdxRuntimePlatform,
        policy: MdxCompatibilityPolicy,
    ) = MdxLiteRtCompatibilityResolver.resolve(
        profile = profile(modelId),
        backend = MdxInferenceBackend.LiteRtCpu,
        platform = platform,
        policy = policy,
    )

    private fun profile(modelId: String): MdxExecutionProfile =
        catalog.contracts.single { it.modelId == modelId }
            .toMdxExecutionProfile(catalog.runtimeQualifications)

    companion object {
        private lateinit var catalog: SourceSeparationModelCatalog

        @JvmStatic
        @BeforeClass
        fun loadCatalog() {
            val bytes = requireNotNull(
                MdxLiteRtCompatibilityTest::class.java.classLoader
                    ?.getResourceAsStream(SourceSeparationModelMetadata.CATALOG_ASSET_PATH)
            ).use { it.readBytes() }
            catalog = SourceSeparationModelMetadata.decodeBundledCatalog(bytes)
        }
    }
}
