package com.mardous.booming.separation.model.litert

import com.mardous.booming.separation.model.MdxCompatibilityPolicy
import com.mardous.booming.separation.model.MdxExecutionProfile
import com.mardous.booming.separation.model.MdxInferenceBackend
import com.mardous.booming.separation.model.MdxInferenceCompatibilityException
import com.mardous.booming.separation.model.MdxInferenceSession
import com.mardous.booming.separation.model.MdxInferenceSessionFactory
import com.mardous.booming.separation.model.MdxModelArtifact
import com.mardous.booming.separation.model.MdxRuntimeAbi
import com.mardous.booming.separation.model.MdxRuntimeDiagnostics
import com.mardous.booming.separation.model.MdxRuntimePlatform
import com.mardous.booming.separation.model.MdxRuntimeSettings
import com.mardous.booming.separation.model.contract.SourceSeparationModelCatalog
import com.mardous.booming.separation.model.contract.SourceSeparationModelMetadata
import com.mardous.booming.separation.model.contract.toMdxExecutionProfile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.concurrent.CancellationException

class MdxLiteRtAutoInferenceSessionFactoryTest {
    @Test
    fun `CPU-only ABIs bypass GPU eligibility and allocation`() {
        val profile = profile("uvr_mdxnet_3_9662")
        for (abi in listOf(MdxRuntimeAbi.ArmeabiV7a, MdxRuntimeAbi.X86_64)) {
            var eligibilityCalls = 0
            val gpuFactory = RecordingFactory(MdxInferenceBackend.LiteRtGpu)
            val cpuFactory = RecordingFactory(MdxInferenceBackend.LiteRtCpu)
            val factory = factory(
                abi = abi,
                gpuFactory = gpuFactory,
                cpuFactory = cpuFactory,
                eligibilityProvider = { _, _, _ ->
                    eligibilityCalls += 1
                    eligible()
                },
            )

            val session = factory.create(artifact(profile), profile, MdxRuntimeSettings())
            val output = session.run(floatArrayOf(1f, 2f))
            val diagnostics = session.autoDiagnostics()

            assertArrayEquals(floatArrayOf(1f, 2f), output, 0f)
            assertEquals(0, eligibilityCalls)
            assertEquals(0, gpuFactory.createCount)
            assertEquals(1, cpuFactory.createCount)
            assertEquals(MdxLiteRtAutoSessionState.CpuDirect, diagnostics.state)
            assertEquals(
                MdxLiteRtGpuEligibilityReason.GpuCompatibilityUnavailable,
                diagnostics.eligibilityReason,
            )
            assertEquals(MdxInferenceBackend.LiteRtCpu, diagnostics.acceptedOutputBackend)
            session.close()
        }
    }

    @Test
    fun `lifecycle unsafe x86 is rejected before GPU eligibility or allocation`() {
        val profile = profile("uvr_mdxnet_3_9662")
        var eligibilityCalls = 0
        val gpuFactory = RecordingFactory(MdxInferenceBackend.LiteRtGpu)
        val cpuFactory = RecordingFactory(MdxInferenceBackend.LiteRtCpu)

        assertThrows(MdxInferenceCompatibilityException::class.java) {
            factory(
                abi = MdxRuntimeAbi.X86,
                gpuFactory = gpuFactory,
                cpuFactory = cpuFactory,
                eligibilityProvider = { _, _, _ ->
                    eligibilityCalls += 1
                    eligible()
                },
            ).create(artifact(profile), profile, MdxRuntimeSettings())
        }

        assertEquals(0, eligibilityCalls)
        assertEquals(0, gpuFactory.createCount)
        assertEquals(0, cpuFactory.createCount)
    }

    @Test
    fun `static eligibility rejection creates CPU directly`() {
        val profile = profile("uvr_mdxnet_3_9662")
        val gpuFactory = RecordingFactory(MdxInferenceBackend.LiteRtGpu)
        val cpuFactory = RecordingFactory(MdxInferenceBackend.LiteRtCpu)
        val factory = factory(
            gpuFactory = gpuFactory,
            cpuFactory = cpuFactory,
            eligibilityProvider = { _, _, _ ->
                MdxLiteRtGpuEligibilityDecision.ineligible(
                    MdxLiteRtGpuEligibilityReason.InsufficientAvailableMemory,
                    "test memory gate",
                )
            },
        )

        val session = factory.create(artifact(profile), profile, MdxRuntimeSettings())

        assertEquals(0, gpuFactory.createCount)
        assertEquals(1, cpuFactory.createCount)
        assertEquals(
            MdxLiteRtGpuEligibilityReason.InsufficientAvailableMemory,
            session.autoDiagnostics().eligibilityReason,
        )
        session.close()
    }

    @Test
    fun `missing CPU runtime on a CPU-only ABI fails without trying GPU`() {
        val profile = profile("uvr_mdxnet_3_9662")
        val gpuFactory = RecordingFactory(MdxInferenceBackend.LiteRtGpu)
        val missingRuntime = UnsatisfiedLinkError("missing libLiteRt.so")
        val cpuFactory = RecordingFactory(
            backend = MdxInferenceBackend.LiteRtCpu,
            createFailure = missingRuntime,
        )

        val error = assertThrows(UnsatisfiedLinkError::class.java) {
            factory(
                abi = MdxRuntimeAbi.ArmeabiV7a,
                gpuFactory = gpuFactory,
                cpuFactory = cpuFactory,
            ).create(artifact(profile), profile, MdxRuntimeSettings())
        }

        assertSame(missingRuntime, error)
        assertEquals(0, gpuFactory.createCount)
        assertEquals(1, cpuFactory.createCount)
    }

    @Test
    fun `successful probe keeps GPU active and reports accepted GPU output`() {
        val profile = profile("uvr_mdxnet_3_9662")
        val gpuFactory = RecordingFactory(MdxInferenceBackend.LiteRtGpu) {
            RecordingSession(MdxInferenceBackend.LiteRtGpu, outputOffset = 1f)
        }
        val cpuFactory = RecordingFactory(MdxInferenceBackend.LiteRtCpu)
        var probeCalls = 0
        val factory = factory(
            gpuFactory = gpuFactory,
            cpuFactory = cpuFactory,
            probe = { _, _ ->
                probeCalls += 1
                MdxLiteRtGpuProbeResult.accepted("probe passed")
            },
        )

        val session = factory.create(artifact(profile), profile, MdxRuntimeSettings())
        val output = session.run(floatArrayOf(2f, 4f))
        val diagnostics = session.autoDiagnostics()

        assertArrayEquals(floatArrayOf(3f, 5f), output, 0f)
        assertEquals(1, probeCalls)
        assertEquals(1, gpuFactory.createCount)
        assertEquals(0, cpuFactory.createCount)
        assertEquals(MdxLiteRtAutoSessionState.GpuActive, diagnostics.state)
        assertEquals(MdxInferenceBackend.LiteRtGpu, diagnostics.acceptedOutputBackend)
        assertTrue(diagnostics.gpuSetupNanos != null)
        assertTrue(diagnostics.gpuProbeNanos != null)
        assertTrue(session.diagnostics.detail.contains("eligibilityDetail=test eligible"))
        session.close()
    }

    @Test
    fun `recoverable GPU setup failure creates CPU fallback`() {
        val profile = profile("uvr_mdxnet_3_9662")
        val gpuFailure = backendFailure(MdxLiteRtFailureStage.ModelCompile)
        val gpuFactory = RecordingFactory(
            backend = MdxInferenceBackend.LiteRtGpu,
            createFailure = gpuFailure,
        )
        val cpuFactory = RecordingFactory(MdxInferenceBackend.LiteRtCpu)
        val session = factory(gpuFactory = gpuFactory, cpuFactory = cpuFactory)
            .create(artifact(profile), profile, MdxRuntimeSettings())

        val output = session.run(floatArrayOf(3f))
        val diagnostics = session.autoDiagnostics()

        assertArrayEquals(floatArrayOf(3f), output, 0f)
        assertEquals(1, gpuFactory.createCount)
        assertEquals(1, cpuFactory.createCount)
        assertEquals(MdxLiteRtAutoSessionState.CpuFallback, diagnostics.state)
        assertEquals(MdxLiteRtAutoFailureStage.GpuSetup, diagnostics.fallbackStage)
        assertEquals(MdxInferenceBackend.LiteRtCpu, diagnostics.acceptedOutputBackend)
        assertEquals(MdxLiteRtAutoFailureStage.GpuSetup.name, session.diagnostics.fallbackStage)
        assertEquals(gpuFailure.message, session.diagnostics.fallbackReason)
        session.close()
    }

    @Test
    fun `probe rejection closes GPU before CPU creation`() {
        val events = mutableListOf<String>()
        val profile = profile("uvr_mdxnet_3_9662")
        val gpuFactory = RecordingFactory(MdxInferenceBackend.LiteRtGpu, events = events) {
            RecordingSession(MdxInferenceBackend.LiteRtGpu, events = events, label = "gpu")
        }
        val cpuFactory = RecordingFactory(MdxInferenceBackend.LiteRtCpu, events = events) {
            RecordingSession(MdxInferenceBackend.LiteRtCpu, events = events, label = "cpu")
        }
        val session = factory(
            gpuFactory = gpuFactory,
            cpuFactory = cpuFactory,
            probe = { _, _ -> MdxLiteRtGpuProbeResult.rejected("parity mismatch") },
        ).create(artifact(profile), profile, MdxRuntimeSettings())

        assertEquals(
            listOf("gpu-create", "gpu-close", "cpu-create"),
            events,
        )
        assertEquals(
            MdxLiteRtAutoFailureStage.GpuProbeValidation,
            session.autoDiagnostics().fallbackStage,
        )
        session.close()
    }

    @Test
    fun `GPU invocation failure retries same input once and latches CPU`() {
        val profile = profile("uvr_mdxnet_3_9662")
        val gpuFailure = backendFailure(MdxLiteRtFailureStage.Invocation)
        val gpuSession = RecordingSession(
            backend = MdxInferenceBackend.LiteRtGpu,
            runFailure = gpuFailure,
        )
        val cpuSession = RecordingSession(
            backend = MdxInferenceBackend.LiteRtCpu,
            outputOffset = 2f,
        )
        val gpuFactory = RecordingFactory(MdxInferenceBackend.LiteRtGpu) { gpuSession }
        val cpuFactory = RecordingFactory(MdxInferenceBackend.LiteRtCpu) { cpuSession }
        val session = factory(gpuFactory = gpuFactory, cpuFactory = cpuFactory)
            .create(artifact(profile), profile, MdxRuntimeSettings())
        val input = floatArrayOf(5f, 7f)

        val first = session.run(input)
        val second = session.run(floatArrayOf(11f))
        val diagnostics = session.autoDiagnostics()

        assertArrayEquals(floatArrayOf(7f, 9f), first, 0f)
        assertArrayEquals(floatArrayOf(13f), second, 0f)
        assertEquals(1, gpuSession.runCount)
        assertEquals(2, cpuSession.runCount)
        assertArrayEquals(input, cpuSession.inputs.first(), 0f)
        assertEquals(1, gpuFactory.createCount)
        assertEquals(1, cpuFactory.createCount)
        assertTrue(gpuSession.closed)
        assertEquals(MdxLiteRtAutoSessionState.CpuFallback, diagnostics.state)
        assertEquals(MdxLiteRtAutoFailureStage.GpuInvocation, diagnostics.fallbackStage)
        assertEquals(MdxInferenceBackend.LiteRtCpu, diagnostics.acceptedOutputBackend)
        assertEquals(MdxLiteRtAutoFailureStage.GpuInvocation.name, session.diagnostics.fallbackStage)
        assertEquals(gpuFailure.message, session.diagnostics.fallbackReason)
        session.close()
    }

    @Test
    fun `cancellation never creates CPU fallback`() {
        val profile = profile("uvr_mdxnet_3_9662")
        val gpuSession = RecordingSession(
            backend = MdxInferenceBackend.LiteRtGpu,
            runFailure = CancellationException("test cancellation"),
        )
        val gpuFactory = RecordingFactory(MdxInferenceBackend.LiteRtGpu) { gpuSession }
        val cpuFactory = RecordingFactory(MdxInferenceBackend.LiteRtCpu)
        val session = factory(gpuFactory = gpuFactory, cpuFactory = cpuFactory)
            .create(artifact(profile), profile, MdxRuntimeSettings())

        assertThrows(CancellationException::class.java) {
            session.run(floatArrayOf(1f))
        }

        assertEquals(0, cpuFactory.createCount)
        assertEquals(MdxLiteRtAutoSessionState.GpuActive, session.autoDiagnostics().state)
        assertFalse(gpuSession.closed)
        session.close()
    }

    @Test
    fun `out of memory closes GPU without allocating CPU`() {
        val profile = profile("uvr_mdxnet_3_9662")
        val gpuSession = RecordingSession(
            backend = MdxInferenceBackend.LiteRtGpu,
            runFailure = OutOfMemoryError("test pressure"),
        )
        val gpuFactory = RecordingFactory(MdxInferenceBackend.LiteRtGpu) { gpuSession }
        val cpuFactory = RecordingFactory(MdxInferenceBackend.LiteRtCpu)
        val session = factory(gpuFactory = gpuFactory, cpuFactory = cpuFactory)
            .create(artifact(profile), profile, MdxRuntimeSettings())

        assertThrows(OutOfMemoryError::class.java) {
            session.run(floatArrayOf(1f))
        }

        assertTrue(gpuSession.closed)
        assertEquals(0, cpuFactory.createCount)
        assertEquals(MdxLiteRtAutoSessionState.Terminal, session.autoDiagnostics().state)
    }

    @Test
    fun `GPU cleanup failure blocks CPU allocation`() {
        val profile = profile("uvr_mdxnet_3_9662")
        val gpuSession = RecordingSession(
            backend = MdxInferenceBackend.LiteRtGpu,
            runFailure = backendFailure(MdxLiteRtFailureStage.OutputRead),
            closeFailure = IllegalStateException("GPU cleanup failed"),
        )
        val gpuFactory = RecordingFactory(MdxInferenceBackend.LiteRtGpu) { gpuSession }
        val cpuFactory = RecordingFactory(MdxInferenceBackend.LiteRtCpu)
        val session = factory(gpuFactory = gpuFactory, cpuFactory = cpuFactory)
            .create(artifact(profile), profile, MdxRuntimeSettings())

        val error = assertThrows(MdxLiteRtAutoInferenceException::class.java) {
            session.run(floatArrayOf(1f))
        }

        assertEquals(MdxLiteRtAutoFailureStage.GpuCleanup, error.stage)
        assertEquals(0, cpuFactory.createCount)
        assertEquals(MdxLiteRtAutoSessionState.Terminal, session.autoDiagnostics().state)
    }

    @Test
    fun `CPU setup failure preserves GPU failure and does not retry`() {
        val profile = profile("uvr_mdxnet_3_9662")
        val gpuFailure = backendFailure(MdxLiteRtFailureStage.ModelCompile)
        val cpuFailure = IllegalStateException("CPU setup failed")
        val gpuFactory = RecordingFactory(
            MdxInferenceBackend.LiteRtGpu,
            createFailure = gpuFailure,
        )
        val cpuFactory = RecordingFactory(
            MdxInferenceBackend.LiteRtCpu,
            createFailure = cpuFailure,
        )

        val error = assertThrows(MdxLiteRtAutoInferenceException::class.java) {
            factory(gpuFactory = gpuFactory, cpuFactory = cpuFactory)
                .create(artifact(profile), profile, MdxRuntimeSettings())
        }

        assertEquals(MdxLiteRtAutoFailureStage.CpuSetup, error.stage)
        assertSame(gpuFailure, error.cause)
        assertSame(cpuFailure, error.suppressed.single())
        assertEquals(1, gpuFactory.createCount)
        assertEquals(1, cpuFactory.createCount)
    }

    @Test
    fun `CPU invocation failure after fallback never retries GPU`() {
        val profile = profile("uvr_mdxnet_3_9662")
        val gpuSession = RecordingSession(
            MdxInferenceBackend.LiteRtGpu,
            runFailure = backendFailure(MdxLiteRtFailureStage.OutputValidation),
        )
        val cpuSession = RecordingSession(
            MdxInferenceBackend.LiteRtCpu,
            runFailure = IllegalStateException("CPU invocation failed"),
        )
        val gpuFactory = RecordingFactory(MdxInferenceBackend.LiteRtGpu) { gpuSession }
        val cpuFactory = RecordingFactory(MdxInferenceBackend.LiteRtCpu) { cpuSession }
        val session = factory(gpuFactory = gpuFactory, cpuFactory = cpuFactory)
            .create(artifact(profile), profile, MdxRuntimeSettings())

        val error = assertThrows(MdxLiteRtAutoInferenceException::class.java) {
            session.run(floatArrayOf(1f))
        }

        assertEquals(MdxLiteRtAutoFailureStage.CpuInvocation, error.stage)
        assertEquals(1, gpuFactory.createCount)
        assertEquals(1, cpuFactory.createCount)
        assertEquals(1, gpuSession.runCount)
        assertEquals(1, cpuSession.runCount)
        assertEquals(MdxLiteRtAutoSessionState.Terminal, session.autoDiagnostics().state)
    }

    @Test
    fun `later CPU failure remains terminal after a successful fallback window`() {
        val profile = profile("uvr_mdxnet_3_9662")
        val gpuSession = RecordingSession(
            MdxInferenceBackend.LiteRtGpu,
            runFailure = backendFailure(MdxLiteRtFailureStage.Invocation),
        )
        val cpuFailure = IllegalStateException("later CPU failure")
        val cpuSession = RecordingSession(
            MdxInferenceBackend.LiteRtCpu,
            runFailure = cpuFailure,
            failureOnRun = 2,
        )
        val gpuFactory = RecordingFactory(MdxInferenceBackend.LiteRtGpu) { gpuSession }
        val cpuFactory = RecordingFactory(MdxInferenceBackend.LiteRtCpu) { cpuSession }
        val session = factory(gpuFactory = gpuFactory, cpuFactory = cpuFactory)
            .create(artifact(profile), profile, MdxRuntimeSettings())

        assertArrayEquals(floatArrayOf(1f), session.run(floatArrayOf(1f)), 0f)
        val error = assertThrows(MdxLiteRtAutoInferenceException::class.java) {
            session.run(floatArrayOf(2f))
        }

        assertEquals(MdxLiteRtAutoFailureStage.CpuInvocation, error.stage)
        assertSame(cpuFailure, error.suppressed.single())
        assertEquals(1, gpuFactory.createCount)
        assertEquals(1, gpuSession.runCount)
        assertEquals(2, cpuSession.runCount)
        assertEquals(MdxLiteRtAutoSessionState.Terminal, session.autoDiagnostics().state)
    }

    @Test
    fun `cancellation after GPU failure stops before CPU setup`() {
        val profile = profile("uvr_mdxnet_3_9662")
        val gpuSession = RecordingSession(
            MdxInferenceBackend.LiteRtGpu,
            runFailure = backendFailure(MdxLiteRtFailureStage.Invocation),
        )
        val gpuFactory = RecordingFactory(MdxInferenceBackend.LiteRtGpu) { gpuSession }
        val cpuFactory = RecordingFactory(MdxInferenceBackend.LiteRtCpu)
        val session = factory(gpuFactory = gpuFactory, cpuFactory = cpuFactory)
            .create(artifact(profile), profile, MdxRuntimeSettings())

        assertThrows(CancellationException::class.java) {
            session.run(floatArrayOf(1f), shouldCancel = { true })
        }

        assertTrue(gpuSession.closed)
        assertEquals(0, cpuFactory.createCount)
        assertEquals(MdxLiteRtAutoSessionState.Terminal, session.autoDiagnostics().state)
    }

    @Test
    fun `model without known good CPU fallback is rejected before allocation`() {
        val profile = profile("uvr_mdxnet_inst_hq_4")
        val gpuFactory = RecordingFactory(MdxInferenceBackend.LiteRtGpu)
        val cpuFactory = RecordingFactory(MdxInferenceBackend.LiteRtCpu)

        assertThrows(MdxInferenceCompatibilityException::class.java) {
            factory(gpuFactory = gpuFactory, cpuFactory = cpuFactory)
                .create(artifact(profile), profile, MdxRuntimeSettings())
        }

        assertEquals(0, gpuFactory.createCount)
        assertEquals(0, cpuFactory.createCount)
    }

    private fun factory(
        abi: MdxRuntimeAbi = MdxRuntimeAbi.Arm64V8a,
        gpuFactory: RecordingFactory = RecordingFactory(MdxInferenceBackend.LiteRtGpu),
        cpuFactory: RecordingFactory = RecordingFactory(MdxInferenceBackend.LiteRtCpu),
        eligibilityProvider: MdxLiteRtGpuEligibilityProvider = { _, _, _ -> eligible() },
        probe: MdxLiteRtGpuProbe = { _, _ ->
            MdxLiteRtGpuProbeResult.accepted("probe passed")
        },
    ) = MdxLiteRtAutoInferenceSessionFactory(
        gpuRuntimeProfile = MdxLiteRtGpuRuntimeProfile.AutomaticFp32V1,
        platformProvider = { MdxRuntimePlatform(35, abi) },
        gpuCompatibilityPolicy = MdxCompatibilityPolicy.AllowUntestedInternal,
        gpuEligibilityProvider = eligibilityProvider,
        gpuProbe = probe,
        gpuFactory = gpuFactory,
        cpuFactory = cpuFactory,
    )

    private fun MdxInferenceSession.autoDiagnostics(): MdxLiteRtAutoDiagnostics =
        (this as MdxLiteRtAutoDiagnosticsProvider).autoDiagnostics

    private fun profile(modelId: String): MdxExecutionProfile =
        catalog.contracts.single { it.modelId == modelId }
            .toMdxExecutionProfile(catalog.runtimeQualifications)

    private fun artifact(profile: MdxExecutionProfile) = MdxModelArtifact(
        file = File("build/test-models/${profile.expectedFileName}"),
        byteSize = requireNotNull(profile.expectedByteSize),
        sha256 = requireNotNull(profile.expectedSha256),
    )

    private class RecordingFactory(
        override val backend: MdxInferenceBackend,
        private val createFailure: Throwable? = null,
        private val events: MutableList<String>? = null,
        private val sessionFactory: () -> RecordingSession = { RecordingSession(backend) },
    ) : MdxInferenceSessionFactory {
        override val factoryId: String = "fake-${backend.name}"
        var createCount = 0
        val sessions = mutableListOf<RecordingSession>()

        override fun create(
            artifact: MdxModelArtifact,
            profile: MdxExecutionProfile,
            runtimeSettings: MdxRuntimeSettings,
        ): MdxInferenceSession {
            createCount += 1
            events?.add(if (backend == MdxInferenceBackend.LiteRtGpu) "gpu-create" else "cpu-create")
            createFailure?.let { throw it }
            return sessionFactory().also(sessions::add)
        }
    }

    private class RecordingSession(
        backend: MdxInferenceBackend,
        private val outputOffset: Float = 0f,
        private val runFailure: Throwable? = null,
        private val failureOnRun: Int = 1,
        private val closeFailure: Throwable? = null,
        private val events: MutableList<String>? = null,
        private val label: String = backend.name,
    ) : MdxInferenceSession {
        override val diagnostics = MdxRuntimeDiagnostics(
            runtimeName = "Fake LiteRT",
            backend = backend,
            cpuThreads = if (backend == MdxInferenceBackend.LiteRtCpu) 4 else null,
            detail = "test",
        )
        var runCount = 0
        var closeCount = 0
        val inputs = mutableListOf<FloatArray>()
        val closed: Boolean
            get() = closeCount > 0

        override fun run(
            inputNchw: FloatArray,
            shouldCancel: () -> Boolean,
        ): FloatArray {
            runCount += 1
            inputs += inputNchw.copyOf()
            if (runCount == failureOnRun) runFailure?.let { throw it }
            return FloatArray(inputNchw.size) { index -> inputNchw[index] + outputOffset }
        }

        override fun close() {
            closeCount += 1
            events?.add("$label-close")
            closeFailure?.let { throw it }
        }
    }

    companion object {
        private lateinit var catalog: SourceSeparationModelCatalog

        @JvmStatic
        @BeforeClass
        fun loadCatalog() {
            val bytes = requireNotNull(
                MdxLiteRtAutoInferenceSessionFactoryTest::class.java.classLoader
                    ?.getResourceAsStream(SourceSeparationModelMetadata.CATALOG_ASSET_PATH)
            ).use { it.readBytes() }
            catalog = SourceSeparationModelMetadata.decodeBundledCatalog(bytes)
        }

        private fun eligible() = MdxLiteRtGpuEligibilityDecision.eligible("test eligible")

        private fun backendFailure(
            stage: MdxLiteRtFailureStage,
            recoverable: Boolean = true,
        ) = MdxLiteRtBackendException(
            stage = stage,
            isRecoverable = recoverable,
            cause = IllegalStateException("test ${stage.name}"),
        )
    }
}
