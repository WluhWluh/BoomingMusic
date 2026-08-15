package com.mardous.booming.separation.process

import com.mardous.booming.separation.model.MdxDspConfig
import com.mardous.booming.separation.model.MdxExecutionProfile
import com.mardous.booming.separation.model.MdxInferenceBackend
import com.mardous.booming.separation.model.MdxInferenceSession
import com.mardous.booming.separation.model.MdxInferenceSessionFactory
import com.mardous.booming.separation.model.MdxModelArtifact
import com.mardous.booming.separation.model.MdxModelFormat
import com.mardous.booming.separation.model.MdxRuntimeDiagnostics
import com.mardous.booming.separation.model.MdxRuntimeAbi
import com.mardous.booming.separation.model.MdxRuntimeCompatibilityRecord
import com.mardous.booming.separation.model.MdxRuntimePrecision
import com.mardous.booming.separation.model.MdxRuntimeProfiles
import com.mardous.booming.separation.model.MdxRuntimeSettings
import com.mardous.booming.separation.model.MdxRuntimeSupportStatus
import com.mardous.booming.separation.model.MdxStem
import com.mardous.booming.separation.model.MdxTensorDataType
import com.mardous.booming.separation.model.MdxTensorLayout
import com.mardous.booming.separation.model.MdxTensorSpec
import com.mardous.booming.separation.model.litert.MdxLiteRtAutoFailureStage
import com.mardous.booming.separation.model.litert.MdxLiteRtAutoInferenceException
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheLostException
import com.mardous.booming.separation.cache.v2.SourceSeparationAdmittedGpuRuntimeIdentity
import java.nio.file.Files
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSeparationProcessSessionControllerTest {
    private val profile = testProfile().copy(
        runtimeCompatibility = listOf(
            MdxRuntimeCompatibilityRecord(
                runtimeVersion = MdxRuntimeProfiles.LITERT_VERSION,
                abi = MdxRuntimeAbi.X86,
                backend = MdxInferenceBackend.LiteRtCpu,
                profileId = MdxRuntimeProfiles.CPU_DEFAULT_FP32,
                precision = MdxRuntimePrecision.Fp32,
                status = MdxRuntimeSupportStatus.KnownGood,
                evidence = "test validation override",
            )
        ),
    )
    private val artifact = artifact(profile)
    private val settings = MdxRuntimeSettings(cpuThreads = 4, useXnnpack = true)

    private fun testProfile(): MdxExecutionProfile {
        val config = MdxDspConfig()
        val shape = listOf(
            1,
            config.dimT,
            config.dimF,
            MdxDspConfig.STEM_COMPLEX_CHANNELS,
        )
        return MdxExecutionProfile(
            profileId = "session_controller_test",
            displayName = "Session controller test",
            outputTag = "test",
            modelFormat = MdxModelFormat.Tflite,
            inputTensor = MdxTensorSpec(
                name = "input",
                shape = shape,
                layout = MdxTensorLayout.Nhwc,
                dataType = MdxTensorDataType.Float32,
            ),
            outputTensor = MdxTensorSpec(
                name = "output",
                shape = shape,
                layout = MdxTensorLayout.Nhwc,
                dataType = MdxTensorDataType.Float32,
            ),
            dspConfig = config,
            modelOutputScale = 1f,
            modelOutputStem = MdxStem.VOCALS,
            pipelineId = "test-tflite",
            pipelineVersion = 1,
            expectedFileName = "session-controller-test.tflite",
        )
    }

    @Test
    fun `resident authority reuses one exact session across completed executions`() {
        val factory = FakeFactory()
        val controller = residentController(factory)

        controller.beginExecution("run-1")
        val first = controller.acquire(artifact, profile, settings)
        val firstSession = first.session
        first.session.run(floatArrayOf(1f))
        first.close()
        controller.finishExecution("run-1", null)
        val firstDiagnostics = controller.diagnostics()

        controller.beginExecution("run-2")
        val second = controller.acquire(artifact, profile, settings)
        assertSame(firstSession, second.session)
        second.session.run(floatArrayOf(2f))
        second.close()
        controller.finishExecution("run-2", null)
        val secondDiagnostics = controller.diagnostics()

        assertEquals(SourceSeparationProcessSessionState.Resident, secondDiagnostics.state)
        assertEquals(1, secondDiagnostics.nativeSessionCreationCount)
        assertEquals(0, secondDiagnostics.activeLeaseCount)
        assertEquals(2L, secondDiagnostics.invocationCount)
        assertEquals(firstDiagnostics.sessionId, secondDiagnostics.sessionId)
        assertEquals(firstDiagnostics.sessionKey, secondDiagnostics.sessionKey)
        assertEquals(1, factory.sessions.size)
        assertFalse(factory.sessions.single().closed)

        controller.close()
        assertTrue(factory.sessions.single().closed)
    }

    @Test
    fun `resident arm32 accepts only its known good CPU record`() {
        val arm32Profile = profile.copy(
            runtimeCompatibility = profile.runtimeCompatibility.map { record ->
                record.copy(abi = MdxRuntimeAbi.ArmeabiV7a)
            },
        )
        val arm32Artifact = artifact(arm32Profile)
        val factory = FakeFactory()
        val controller = residentController(factory, MdxRuntimeAbi.ArmeabiV7a)

        controller.beginExecution(
            "run-arm32",
            sessionIdentity(arm32Artifact, arm32Profile),
        )
        controller.acquire(arm32Artifact, arm32Profile, settings).close()
        controller.finishExecution("run-arm32", null)

        assertEquals(SourceSeparationProcessSessionState.Resident,
            controller.diagnostics().state)
        assertEquals(1, factory.createCount)
    }

    @Test
    fun `resident authority rejects another ABI compatibility record`() {
        val controller = residentController(FakeFactory(), MdxRuntimeAbi.ArmeabiV7a)
        controller.beginExecution("run-wrong-abi")

        assertThrows(IllegalStateException::class.java) {
            controller.acquire(artifact, profile, settings)
        }
    }

    @Test
    fun `resident key mismatch requires recycle without replacing native state`() {
        val factory = FakeFactory()
        val controller = residentController(factory)
        controller.beginExecution("run-1")
        controller.acquire(artifact, profile, settings).close()
        controller.finishExecution("run-1", null)
        val firstKey = controller.diagnostics().sessionKey

        val changedArtifact = artifact.copy(sha256 = "b".repeat(64))
        controller.beginExecution(
            "run-2",
            sessionIdentity(changedArtifact, profile),
        )
        val mismatch = assertThrows(
            SourceSeparationProcessSessionRecycleRequiredException::class.java,
        ) {
            controller.acquire(
                changedArtifact,
                profile,
                settings,
            )
        }
        controller.finishExecution("run-2", mismatch)

        val diagnostics = controller.diagnostics()
        assertEquals(SourceSeparationProcessSessionState.Resident, diagnostics.state)
        assertEquals(firstKey, mismatch.currentSessionKey)
        assertNotEquals(firstKey, mismatch.requestedSessionKey)
        assertEquals("session-key-change", diagnostics.recycleReason)
        assertEquals(1, diagnostics.nativeSessionCreationCount)
        assertEquals(1, factory.sessions.size)
        assertFalse(factory.sessions.single().closed)
    }

    @Test
    fun `resident authority permits only one active lease`() {
        val controller = residentController(FakeFactory())
        controller.beginExecution("run-1")
        val lease = controller.acquire(artifact, profile, settings)

        assertThrows(IllegalStateException::class.java) {
            controller.acquire(artifact, profile, settings)
        }

        lease.close()
        controller.finishExecution("run-1", null)
    }

    @Test
    fun `native invocation failure poisons the process generation`() {
        val failure = IllegalStateException("invoke failed")
        val factory = FakeFactory(runFailure = failure)
        val controller = residentController(factory)
        controller.beginExecution("run-1")
        val lease = controller.acquire(artifact, profile, settings)

        val observed = assertThrows(IllegalStateException::class.java) {
            lease.session.run(floatArrayOf(1f))
        }
        lease.close()
        controller.finishExecution("run-1", observed)

        assertEquals(SourceSeparationProcessSessionState.Poisoned,
            controller.diagnostics().state)
        controller.beginExecution("run-2")
        assertThrows(SourceSeparationProcessSessionPoisonedException::class.java) {
            controller.acquire(artifact, profile, settings)
        }
        controller.finishExecution("run-2", null)
        assertEquals(1, factory.sessions.size)
    }

    @Test
    fun `non-finite output poisons the process generation`() {
        val factory = FakeFactory(output = floatArrayOf(Float.NaN))
        val controller = residentController(factory)
        controller.beginExecution("run-1")
        val lease = controller.acquire(artifact, profile, settings)

        val failure = assertThrows(IllegalStateException::class.java) {
            lease.session.run(floatArrayOf(1f))
        }
        lease.close()
        controller.finishExecution("run-1", failure)

        assertTrue(controller.diagnostics().poisoned)
        assertEquals(1L, controller.diagnostics().invocationCount)
    }

    @Test
    fun `cancellation and pre-acquisition validation failure remain healthy`() {
        val factory = FakeFactory(runFailure = CancellationException("cancel"))
        val controller = residentController(factory)
        controller.beginExecution("run-1")
        val lease = controller.acquire(artifact, profile, settings)
        assertThrows(CancellationException::class.java) {
            lease.session.run(floatArrayOf(1f))
        }
        lease.close()
        controller.finishExecution("run-1", null)
        assertEquals(SourceSeparationProcessSessionState.Resident,
            controller.diagnostics().state)

        controller.beginExecution("run-2")
        controller.finishExecution("run-2", IllegalArgumentException("bad descriptor"))
        assertEquals(SourceSeparationProcessSessionState.Resident,
            controller.diagnostics().state)
    }

    @Test
    fun `unexpected post-acquisition execution failure poisons resident state`() {
        val controller = residentController(FakeFactory())
        controller.beginExecution("run-1")
        controller.acquire(artifact, profile, settings).close()
        controller.finishExecution("run-1", IllegalStateException("DSP write failed"))

        assertEquals(SourceSeparationProcessSessionState.Poisoned,
            controller.diagnostics().state)
    }

    @Test
    fun `typed cache loss leaves a healthy resident session reusable`() {
        val factory = FakeFactory()
        val controller = residentController(factory)
        controller.beginExecution("run-1")
        val first = controller.acquire(artifact, profile, settings)
        val firstSession = first.session
        first.session.run(floatArrayOf(1f))
        first.close()
        controller.diagnostics().also {
            assertEquals(SourceSeparationProcessSessionState.Resident, it.state)
            assertEquals(0, it.activeLeaseCount)
        }
        controller.finishExecution(
            "run-1",
            SourceSeparationCacheLostException(
                cacheKey = "a".repeat(64),
                message = "The cache was cleared.",
            ),
        )

        val afterLoss = controller.diagnostics()
        assertEquals(SourceSeparationProcessSessionState.Resident, afterLoss.state)
        assertFalse(afterLoss.poisoned)

        controller.beginExecution("run-2")
        val second = controller.acquire(artifact, profile, settings)
        assertSame(firstSession, second.session)
        second.close()
        controller.finishExecution("run-2", null)
        assertEquals(1, factory.createCount)
    }

    @Test
    fun `native creation failure cannot lead to a second resident creation`() {
        val factory = FakeFactory(createFailure = IllegalStateException("create failed"))
        val controller = residentController(factory)
        controller.beginExecution("run-1")
        val failure = assertThrows(IllegalStateException::class.java) {
            controller.acquire(artifact, profile, settings)
        }
        controller.finishExecution("run-1", failure)
        assertEquals(1, controller.diagnostics().nativeSessionCreationCount)
        assertEquals(SourceSeparationProcessSessionState.Poisoned,
            controller.diagnostics().state)

        controller.beginExecution("run-2")
        assertThrows(SourceSeparationProcessSessionPoisonedException::class.java) {
            controller.acquire(artifact, profile, settings)
        }
        controller.finishExecution("run-2", null)
        assertEquals(1, factory.createCount)
    }

    @Test
    fun `single-use authority preserves close and recreate behavior`() {
        val factory = FakeFactory()
        val controller = SourceSeparationProcessSessionController(
            factory,
            SourceSeparationProcessSessionOwnership.SingleUse,
            sessionIdFactory = { "single-${factory.createCount}" },
        )

        repeat(2) { index ->
            val runId = "run-$index"
            controller.beginExecution(runId)
            controller.acquire(artifact, profile, settings).close()
            controller.finishExecution(runId, null)
        }

        assertEquals(2, factory.sessions.size)
        assertTrue(factory.sessions.all(FakeSession::closed))
        assertEquals(SourceSeparationProcessSessionState.Empty, controller.diagnostics().state)
        assertEquals(2, controller.diagnostics().nativeSessionCreationCount)
    }

    @Test
    fun `single-use cleanup failure requires a new process generation`() {
        val cleanupFailure = IllegalStateException("native cleanup failed")
        val factory = FakeFactory(closeFailure = cleanupFailure)
        val controller = SourceSeparationProcessSessionController(
            factory,
            SourceSeparationProcessSessionOwnership.SingleUse,
        )
        controller.beginExecution("run-1")
        val lease = controller.acquire(artifact, profile, settings)

        assertSame(cleanupFailure, assertThrows(IllegalStateException::class.java) {
            lease.close()
        })
        controller.finishExecution("run-1", cleanupFailure)

        assertEquals(SourceSeparationProcessSessionState.Poisoned, controller.diagnostics().state)
        assertTrue(controller.diagnostics().poisoned)
        controller.beginExecution("run-2")
        assertThrows(SourceSeparationProcessSessionPoisonedException::class.java) {
            controller.acquire(artifact, profile, settings)
        }
        controller.finishExecution("run-2", null)
        assertEquals(1, factory.createCount)
    }

    @Test
    fun `single-use Auto cleanup failure poisons before its empty outer close`() {
        val cleanupFailure = MdxLiteRtAutoInferenceException(
            MdxLiteRtAutoFailureStage.GpuCleanup,
            IllegalStateException("GPU cleanup failed"),
        )
        val factory = FakeFactory(runFailure = cleanupFailure)
        val controller = SourceSeparationProcessSessionController(
            factory,
            SourceSeparationProcessSessionOwnership.SingleUse,
        )
        controller.beginExecution("run-1")
        val lease = controller.acquire(artifact, profile, settings)

        assertSame(cleanupFailure, assertThrows(MdxLiteRtAutoInferenceException::class.java) {
            lease.session.run(floatArrayOf(1f))
        })
        lease.close()
        controller.finishExecution("run-1", cleanupFailure)

        assertEquals(SourceSeparationProcessSessionState.Poisoned, controller.diagnostics().state)
        controller.beginExecution("run-2")
        assertThrows(SourceSeparationProcessSessionPoisonedException::class.java) {
            controller.acquire(artifact, profile, settings)
        }
        controller.finishExecution("run-2", null)
        assertEquals(1, factory.createCount)
    }

    @Test
    fun `recycle transition is rejected until execution and lease are released`() {
        val controller = residentController(FakeFactory())
        controller.beginExecution("run-1")
        val lease = controller.acquire(artifact, profile, settings)
        assertThrows(IllegalStateException::class.java) {
            controller.markRecycling("test", "token")
        }
        lease.close()
        controller.finishExecution("run-1", null)

        controller.markRecycling("test", "token")
        val diagnostics = controller.diagnostics()
        assertEquals(SourceSeparationProcessSessionState.Recycling, diagnostics.state)
        assertEquals("token", diagnostics.recycleToken)
    }

    private fun residentController(
        factory: FakeFactory,
        runtimeAbi: MdxRuntimeAbi = MdxRuntimeAbi.X86,
    ) =
        SourceSeparationProcessSessionController(
            factory,
            SourceSeparationProcessSessionOwnership.ResidentUntilProcessExit,
            runtimeAbi = runtimeAbi,
            sessionIdFactory = { "session-1" },
        )

    private fun SourceSeparationProcessSessionController.beginExecution(runId: String) {
        beginExecution(runId, sessionIdentity())
    }

    private fun sessionIdentity(
        artifact: MdxModelArtifact = this.artifact,
        profile: MdxExecutionProfile = this.profile,
        runtimeSettings: MdxRuntimeSettings = settings,
    ) = SourceSeparationExecutionSessionIdentity(
        model = SourceSeparationExecutionModelIdentity(
            modelId = "test-model",
            artifactFileName = artifact.file.name,
            artifactByteSize = artifact.byteSize,
            artifactSha256 = artifact.sha256,
            contractId = "test-contract",
            contractSchemaVersion = 1,
            contractFingerprint = "c".repeat(64),
            profileRevisionId = "test-profile-revision",
            executionProfileId = profile.profileId,
            executionSessionIdentity = profile.sessionIdentity,
            pipelineId = profile.pipelineId,
            pipelineVersion = profile.pipelineVersion,
        ),
        backendPolicy = SourceSeparationExecutionBackendPolicy.Auto,
        gpuRuntimeIdentity = SourceSeparationAdmittedGpuRuntimeIdentity(
            profileId = "test-gpu",
            artifactVersion = "test-runtime",
            capabilitySchemaVersion = 1,
            backend = "OpenCL",
            precision = "FP32",
            kernelBatchSize = 1,
            commandQueueWindowSize = 1,
        ),
        cpuThreads = runtimeSettings.cpuThreads,
        useXnnpack = runtimeSettings.useXnnpack,
    )

    private fun artifact(profile: MdxExecutionProfile): MdxModelArtifact {
        val directory = Files.createTempDirectory("process-session-test").toFile()
        val file = directory.resolve(profile.expectedFileName)
        file.writeBytes(byteArrayOf(1))
        file.deleteOnExit()
        directory.deleteOnExit()
        return MdxModelArtifact(file, file.length(), "a".repeat(64))
    }

    private class FakeFactory(
        private val createFailure: Throwable? = null,
        private val runFailure: Throwable? = null,
        private val output: FloatArray? = null,
        private val closeFailure: Throwable? = null,
    ) : MdxInferenceSessionFactory {
        override val factoryId = "fake-auto"
        override val backend = MdxInferenceBackend.LiteRtAuto
        val sessions = mutableListOf<FakeSession>()
        var createCount = 0

        override fun create(
            artifact: MdxModelArtifact,
            profile: MdxExecutionProfile,
            runtimeSettings: MdxRuntimeSettings,
        ): MdxInferenceSession {
            createCount += 1
            createFailure?.let { throw it }
            return FakeSession(runFailure, output, closeFailure).also(sessions::add)
        }
    }

    private class FakeSession(
        private val runFailure: Throwable?,
        private val output: FloatArray?,
        private val closeFailure: Throwable?,
    ) : MdxInferenceSession {
        override val diagnostics = MdxRuntimeDiagnostics(
            runtimeName = "Fake",
            backend = MdxInferenceBackend.LiteRtCpu,
            cpuThreads = 1,
            detail = "test",
        )
        var closeCount = 0
        val closed: Boolean
            get() = closeCount > 0

        override fun run(
            inputNchw: FloatArray,
            shouldCancel: () -> Boolean,
        ): FloatArray {
            runFailure?.let { throw it }
            return output ?: inputNchw.copyOf()
        }

        override fun close() {
            closeCount += 1
            closeFailure?.let { throw it }
        }
    }
}
