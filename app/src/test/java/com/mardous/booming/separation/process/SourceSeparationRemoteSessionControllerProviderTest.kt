package com.mardous.booming.separation.process

import com.mardous.booming.separation.model.MdxExecutionProfile
import com.mardous.booming.separation.model.MdxInferenceBackend
import com.mardous.booming.separation.model.MdxInferenceSession
import com.mardous.booming.separation.model.MdxInferenceSessionFactory
import com.mardous.booming.separation.model.MdxModelArtifact
import com.mardous.booming.separation.model.MdxRuntimeSettings
import com.mardous.booming.separation.cache.v2.SourceSeparationAdmittedGpuRuntimeIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSeparationRemoteSessionControllerProviderTest {
    @Test
    fun `auto selection is stable and rejects a cpu change in one generation`() {
        val auto = controller(MdxInferenceBackend.LiteRtAuto)
        val cpu = controller(MdxInferenceBackend.LiteRtCpu)
        var autoCreations = 0
        var cpuCreations = 0
        val provider = SourceSeparationRemoteSessionControllerProvider(
            autoControllerFactory = {
                autoCreations += 1
                auto
            },
            cpuControllerFactory = {
                cpuCreations += 1
                cpu
            },
        )

        assertSame(auto, provider.select(SourceSeparationExecutionBackendPolicy.Auto))
        assertSame(auto, provider.select(SourceSeparationExecutionBackendPolicy.Auto))
        val error = assertThrows(SourceSeparationRemoteBackendPolicyChangeException::class.java) {
            provider.select(SourceSeparationExecutionBackendPolicy.Cpu)
        }

        assertEquals(SourceSeparationExecutionBackendPolicy.Auto, error.selectedBackendPolicy)
        assertEquals(SourceSeparationExecutionBackendPolicy.Cpu, error.requestedBackendPolicy)
        assertEquals(1, autoCreations)
        assertEquals(0, cpuCreations)
    }

    @Test
    fun `cpu selection invokes only the cpu controller factory`() {
        val cpu = controller(MdxInferenceBackend.LiteRtCpu)
        var autoCreations = 0
        var cpuCreations = 0
        val provider = SourceSeparationRemoteSessionControllerProvider(
            autoControllerFactory = {
                autoCreations += 1
                controller(MdxInferenceBackend.LiteRtAuto)
            },
            cpuControllerFactory = {
                cpuCreations += 1
                cpu
            },
        )

        assertSame(cpu, provider.select(SourceSeparationExecutionBackendPolicy.Cpu))
        assertEquals(SourceSeparationExecutionBackendPolicy.Cpu, provider.selectedBackendPolicy())
        assertSame(cpu, provider.requireCurrent())
        assertEquals(0, autoCreations)
        assertEquals(1, cpuCreations)
    }

    @Test
    fun `session diagnostics require recycle for backend or resident identity changes`() {
        val empty = SourceSeparationProcessSessionDiagnostics.empty()
        val cpu = empty.copy(backendPolicy = SourceSeparationExecutionBackendPolicy.Cpu)
        val cpuIdentity = executionIdentity(SourceSeparationExecutionBackendPolicy.Cpu)
        val autoIdentity = executionIdentity(SourceSeparationExecutionBackendPolicy.Auto)
        val changedModel = cpuIdentity.copy(
            model = cpuIdentity.model.copy(artifactSha256 = "b".repeat(64)),
        )
        val changedContract = cpuIdentity.copy(
            model = cpuIdentity.model.copy(contractFingerprint = "d".repeat(64)),
        )
        val changedRuntime = cpuIdentity.copy(cpuThreads = cpuIdentity.cpuThreads + 1)
        val residentCpu = cpu.copy(
            state = SourceSeparationProcessSessionState.Resident,
            sessionKey = cpuIdentity.diagnosticKey,
        )

        assertFalse(empty.requiresRecycleFor(autoIdentity))
        assertFalse(cpu.requiresRecycleFor(cpuIdentity))
        assertTrue(cpu.requiresRecycleFor(autoIdentity))
        assertFalse(residentCpu.requiresRecycleFor(cpuIdentity))
        assertTrue(residentCpu.requiresRecycleFor(changedModel))
        assertTrue(residentCpu.requiresRecycleFor(changedContract))
        assertTrue(residentCpu.requiresRecycleFor(changedRuntime))
    }

    private fun controller(backend: MdxInferenceBackend) =
        SourceSeparationProcessSessionController(
            factory = object : MdxInferenceSessionFactory {
                override val factoryId: String = "fake-${backend.name}"
                override val backend: MdxInferenceBackend = backend

                override fun create(
                    artifact: MdxModelArtifact,
                    profile: MdxExecutionProfile,
                    runtimeSettings: MdxRuntimeSettings,
                ): MdxInferenceSession = error("The provider test must not allocate a session.")
            },
            ownership = SourceSeparationProcessSessionOwnership.SingleUse,
        )

    private fun executionIdentity(
        backendPolicy: SourceSeparationExecutionBackendPolicy,
    ) = SourceSeparationExecutionSessionIdentity(
        model = SourceSeparationExecutionModelIdentity(
            modelId = "test-model",
            artifactFileName = "test.tflite",
            artifactByteSize = 1L,
            artifactSha256 = "a".repeat(64),
            contractId = "test-contract",
            contractSchemaVersion = 1,
            contractFingerprint = "c".repeat(64),
            profileRevisionId = "test-profile",
            executionProfileId = "test-execution",
            executionSessionIdentity = "test-session",
            pipelineId = "test-pipeline",
            pipelineVersion = 1,
        ),
        backendPolicy = backendPolicy,
        gpuRuntimeIdentity = if (backendPolicy == SourceSeparationExecutionBackendPolicy.Auto) {
            SourceSeparationAdmittedGpuRuntimeIdentity(
                profileId = "test-gpu",
                artifactVersion = "test-runtime",
                capabilitySchemaVersion = 1,
                backend = "OpenCL",
                precision = "FP32",
                kernelBatchSize = 1,
                commandQueueWindowSize = 1,
            )
        } else {
            null
        },
        cpuThreads = 4,
        useXnnpack = true,
    )
}
