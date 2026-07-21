package com.mardous.booming.separation.model

import org.junit.Assert.assertArrayEquals
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
    fun `legacy profile preserves current 9482 behavior`() {
        val config = MdxDspConfig()

        val profile = MdxExecutionProfile.legacy(MdxModelVariant.MDXNET_9482, config)

        assertEquals(MdxModelFormat.Onnx, profile.modelFormat)
        assertEquals(MdxTensorLayout.Nchw, profile.inputTensor.layout)
        assertEquals(listOf(1, 4, 2048, 256), profile.inputTensor.shape)
        assertEquals(config.tensorElementCount, profile.inputTensor.elementCount)
        assertEquals(1f, profile.modelOutputScale)
        assertEquals(MdxStem.VOCALS, profile.modelOutputStem)
    }

    @Test
    fun `reusable provider reuses only an identical session key`() {
        val factory = FakeFactory()
        val provider = ReusableMdxInferenceSessionProvider(factory)
        val profile = MdxExecutionProfile.legacy(MdxModelVariant.MDXNET_9482)
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
        val profile = MdxExecutionProfile.legacy(MdxModelVariant.MDXNET_9482)
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
        val profile = MdxExecutionProfile.legacy(MdxModelVariant.MDXNET_9482)
        val lease = provider.acquire(artifact(profile), profile, MdxRuntimeSettings())

        lease.close()
        lease.close()

        assertEquals(1, factory.sessions.single().closeCount)
    }

    @Test
    fun `ORT output flattening enforces the declared element count`() {
        val output = arrayOf(
            arrayOf(
                arrayOf(floatArrayOf(1f, 2f), floatArrayOf(3f, 4f)),
                arrayOf(floatArrayOf(5f, 6f), floatArrayOf(7f, 8f)),
            )
        )

        assertArrayEquals(
            floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f),
            flattenOutput(output, expectedElementCount = 8),
            0f,
        )
        assertThrows(IllegalArgumentException::class.java) {
            flattenOutput(output, expectedElementCount = 7)
        }
        assertThrows(IllegalArgumentException::class.java) {
            flattenOutput(output, expectedElementCount = 9)
        }
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

    private class FakeFactory : MdxInferenceSessionFactory {
        override val factoryId = "fake"
        override val backend = MdxInferenceBackend.OrtCpu
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
            backend = MdxInferenceBackend.OrtCpu,
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
