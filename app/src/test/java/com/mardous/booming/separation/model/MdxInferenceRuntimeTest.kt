package com.mardous.booming.separation.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CancellationException

class MdxInferenceRuntimeTest {
    @Test
    fun `reusable provider reuses only an identical session key`() {
        val factory = FakeFactory()
        val provider = ReusableMdxInferenceSessionProvider(factory)
        val profile = profile()
        val artifact = artifact(profile)
        val settings = MdxRuntimeSettings(cpuThreads = 4)

        val first = provider.acquire(artifact, profile, settings)
        val firstSession = first.session
        first.close()
        val second = provider.acquire(artifact, profile, settings)

        assertSame(firstSession, second.session)
        assertEquals(1, factory.sessions.size)
        second.close()

        val replacement = provider.acquire(
            artifact = artifact.copy(sha256 = "b".repeat(64)),
            profile = profile,
            runtimeSettings = settings,
        )
        assertEquals(2, factory.sessions.size)
        assertTrue(factory.sessions.first().closed)
        replacement.close()
        provider.close()
        assertTrue(factory.sessions.last().closed)
    }

    @Test
    fun `provider defers close until an active lease returns`() {
        val factory = FakeFactory()
        val provider = ReusableMdxInferenceSessionProvider(factory)
        val profile = profile()
        val lease = provider.acquire(artifact(profile), profile, MdxRuntimeSettings())
        val session = factory.sessions.single()

        provider.close()

        assertFalse(session.closed)
        assertThrows(IllegalStateException::class.java) {
            provider.acquire(artifact(profile), profile, MdxRuntimeSettings())
        }

        lease.close()
        lease.close()
        assertTrue(session.closed)
        assertEquals(1, session.closeCount)
    }

    @Test
    fun `single use lease closes its session once`() {
        val factory = FakeFactory()
        val provider = SingleUseMdxInferenceSessionProvider(factory)
        val profile = profile()
        val lease = provider.acquire(artifact(profile), profile, MdxRuntimeSettings())

        lease.close()
        lease.close()

        assertEquals(1, factory.sessions.single().closeCount)
    }

    @Test
    fun `timed factory separates setup first and reused inference`() {
        val timestamps = ArrayDeque(listOf(10L, 20L, 30L, 50L, 60L, 90L))
        val factory = FakeFactory().withMdxInferenceTiming { timestamps.removeFirst() }
        val profile = profile()
        val session = factory.create(artifact(profile), profile, MdxRuntimeSettings())

        session.run(floatArrayOf(1f))
        session.run(floatArrayOf(2f))

        assertEquals(10L, session.diagnostics.modelSetupNanos)
        assertEquals(2L, session.diagnostics.inferenceInvocationCount)
        assertEquals(20L, session.diagnostics.firstInferenceNanos)
        assertEquals(1L, session.diagnostics.reusedInferenceCount)
        assertEquals(30L, session.diagnostics.reusedInferenceTotalNanos)
        assertEquals(30L, session.diagnostics.lastInferenceNanos)
        assertSame(factory, factory.withMdxInferenceTiming())
    }

    @Test
    fun `non interruptible invocation discards an output canceled in flight`() {
        var canceled = false
        var invocationCount = 0

        assertThrows(CancellationException::class.java) {
            runNonInterruptibleMdxInference(shouldCancel = { canceled }) {
                invocationCount += 1
                canceled = true
                floatArrayOf(1f)
            }
        }

        assertEquals(1, invocationCount)
    }

    @Test
    fun `cancellation before invocation does not enter the runtime`() {
        var invocationCount = 0

        assertThrows(CancellationException::class.java) {
            runNonInterruptibleMdxInference(shouldCancel = { true }) {
                invocationCount += 1
            }
        }

        assertEquals(0, invocationCount)
    }

    private fun artifact(profile: MdxExecutionProfile): MdxModelArtifact {
        val directory = Files.createTempDirectory("mdx-runtime-test").toFile()
        val file = directory.resolve(profile.expectedFileName)
        file.writeBytes(byteArrayOf(1))
        file.deleteOnExit()
        directory.deleteOnExit()
        return MdxModelArtifact(
            file = file,
            byteSize = file.length(),
            sha256 = "a".repeat(64),
        )
    }

    private fun profile(): MdxExecutionProfile {
        val config = MdxDspConfig()
        val shape = listOf(
            1,
            config.dimT,
            config.dimF,
            MdxDspConfig.STEM_COMPLEX_CHANNELS,
        )
        return MdxExecutionProfile(
            profileId = "test_tflite",
            displayName = "Test TFLite",
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
            expectedFileName = "test.tflite",
        )
    }

    private class FakeFactory : MdxInferenceSessionFactory {
        override val factoryId = "fake"
        override val backend = MdxInferenceBackend.LiteRtCpu
        val sessions = mutableListOf<FakeSession>()

        override fun create(
            artifact: MdxModelArtifact,
            profile: MdxExecutionProfile,
            runtimeSettings: MdxRuntimeSettings,
        ): MdxInferenceSession {
            return FakeSession().also(sessions::add)
        }
    }

    private class FakeSession : MdxInferenceSession {
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
        ): FloatArray = inputNchw.copyOf()

        override fun close() {
            closeCount += 1
        }
    }
}
