package com.mardous.booming.separation.process

import com.mardous.booming.separation.model.MdxExecutionProfile
import com.mardous.booming.separation.model.MdxInferenceBackend
import com.mardous.booming.separation.model.MdxInferenceSession
import com.mardous.booming.separation.model.MdxInferenceSessionFactory
import com.mardous.booming.separation.model.MdxModelArtifact
import com.mardous.booming.separation.model.MdxRuntimeSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
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
}
