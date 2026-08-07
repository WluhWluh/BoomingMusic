package com.mardous.booming.separation.model.litert

import com.mardous.booming.separation.model.HtdemucsNeuralInputs
import com.mardous.booming.separation.model.HtdemucsWindowStemSet
import com.mardous.booming.separation.model.contract.SourceSeparationMultiTensorExecutableContract
import com.mardous.booming.separation.model.contract.SourceSeparationMultiTensorExecutableContractLoader
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HtdemucsLiteRtCpuInferenceSessionTest {
    @Test
    fun `atomic forward binds both inputs and publishes both validated outputs`() {
        val backend = FakeBackend { inputs ->
            assertEquals(setOf("waveform_tensor", "spectrum_tensor"), inputs.keys)
            linkedMapOf(
                "frequency_tensor" to floatArrayOf(5f, 6f),
                "time_tensor" to floatArrayOf(7f, 8f),
            )
        }
        val forward = forward(backend)

        val outputs = forward.run(
            HtdemucsNeuralInputs(floatArrayOf(1f, 2f), floatArrayOf(3f, 4f)),
            shouldCancel = { false },
        )

        assertArrayEquals(floatArrayOf(5f, 6f), outputs.frequency, 0f)
        assertArrayEquals(floatArrayOf(7f, 8f), outputs.waveform, 0f)
    }

    @Test
    fun `atomic forward rejects missing malformed and non-finite branches`() {
        listOf(
            linkedMapOf("frequency_tensor" to floatArrayOf(1f, 2f)),
            linkedMapOf(
                "frequency_tensor" to floatArrayOf(1f),
                "time_tensor" to floatArrayOf(2f, 3f),
            ),
            linkedMapOf(
                "frequency_tensor" to floatArrayOf(1f, 2f),
                "time_tensor" to floatArrayOf(Float.NaN, 3f),
            ),
        ).forEach { outputs ->
            assertThrows(IllegalArgumentException::class.java) {
                forward(FakeBackend { outputs }).run(validInputs(), shouldCancel = { false })
            }
        }
    }

    @Test
    fun `cancellation after invocation does not publish a reconstructed stem set`() {
        var canceled = false
        var reconstructed = false
        val backend = FakeBackend {
            canceled = true
            validOutputs()
        }
        val session = AtomicHtdemucsCpuInferenceSession(
            forward = forward(backend),
            reconstruct = { _, _ ->
                reconstructed = true
                stemSet()
            },
            closeResources = backend::close,
        )

        assertThrows(CancellationException::class.java) {
            session.run(validInputs(), shouldCancel = { canceled })
        }
        assertFalse(reconstructed)
        session.close()
        assertTrue(backend.closed)
    }

    @Test
    fun `reconstruction failure does not publish a partial stem set`() {
        val session = AtomicHtdemucsCpuInferenceSession(
            forward = forward(FakeBackend { validOutputs() }),
            reconstruct = { _, _ -> error("second branch reconstruction failed") },
            closeResources = {},
        )

        val error = assertThrows(IllegalStateException::class.java) {
            session.run(validInputs())
        }
        assertEquals("second branch reconstruction failed", error.message)
    }

    @Test
    fun `factory freezes names cpu policy and trusted artifact identity`() {
        val contract = loadContract("htdemucs-6s-official-fp32.json")
        val directory = Files.createTempDirectory("htdemucs-session-test").toFile()
        val model = File(directory, contract.artifact.fileName)
        RandomAccessFile(model, "rw").use { it.setLength(contract.artifact.byteSize) }
        val recordingFactory = RecordingBackendFactory()
        try {
            val session = HtdemucsLiteRtCpuInferenceSessionFactory(
                backendFactory = recordingFactory,
                availableProcessors = { 8 },
            ).create(
                HtdemucsVerifiedArtifact(
                    file = model,
                    byteSize = contract.artifact.byteSize,
                    sha256 = contract.artifact.sha256,
                ),
                contract,
            )

            assertEquals(4, recordingFactory.cpuThreads)
            assertEquals(contract.flatBuffer.signatureKey, recordingFactory.signatureKey)
            assertEquals(
                contract.flatBuffer.inputs.map { it.tensorName },
                recordingFactory.inputs.map { it.tensorName },
            )
            assertEquals(
                contract.flatBuffer.outputs.map { it.tensorName },
                recordingFactory.outputs.map { it.tensorName },
            )
            session.close()
            assertTrue(recordingFactory.backend.closed)
        } finally {
            model.delete()
            directory.delete()
        }
    }

    @Test
    fun `cpu thread count remains bounded`() {
        assertEquals(2, resolveHtdemucsCpuThreadCount(1))
        assertEquals(3, resolveHtdemucsCpuThreadCount(4))
        assertEquals(4, resolveHtdemucsCpuThreadCount(32))
    }

    private fun forward(backend: HtdemucsNamedTensorBackend) =
        AtomicHtdemucsNamedTensorForward(INPUTS, OUTPUTS, backend)

    private fun validInputs() =
        HtdemucsNeuralInputs(floatArrayOf(1f, 2f), floatArrayOf(3f, 4f))

    private fun validOutputs() = linkedMapOf(
        "frequency_tensor" to floatArrayOf(5f, 6f),
        "time_tensor" to floatArrayOf(7f, 8f),
    )

    private fun stemSet() = HtdemucsWindowStemSet(
        orderedStemIds = listOf("a", "b"),
        planarSamples = floatArrayOf(1f, 2f),
        samplesPerStem = 1,
    )

    private fun loadContract(name: String): SourceSeparationMultiTensorExecutableContract {
        val path = "source-separation/research-contracts/$name"
        val serialized = requireNotNull(javaClass.classLoader?.getResourceAsStream(path))
            .bufferedReader().use { it.readText() }
        return SourceSeparationMultiTensorExecutableContractLoader.load(serialized)
    }

    private class FakeBackend(
        private val runAction: (Map<String, FloatArray>) -> Map<String, FloatArray>,
    ) : HtdemucsNamedTensorBackend {
        var closed = false

        override fun run(inputs: Map<String, FloatArray>) = runAction(inputs)

        override fun close() {
            closed = true
        }
    }

    private class RecordingBackendFactory : HtdemucsNamedTensorBackendFactory {
        val backend = FakeBackend { validOutputsStatic() }
        var signatureKey = ""
        var inputs = emptyList<HtdemucsTensorBinding>()
        var outputs = emptyList<HtdemucsTensorBinding>()
        var cpuThreads = 0

        override fun create(
            modelFile: File,
            signatureKey: String,
            inputs: List<HtdemucsTensorBinding>,
            outputs: List<HtdemucsTensorBinding>,
            cpuThreads: Int,
        ): HtdemucsNamedTensorBackend {
            this.signatureKey = signatureKey
            this.inputs = inputs
            this.outputs = outputs
            this.cpuThreads = cpuThreads
            return backend
        }
    }

    private companion object {
        val INPUTS = listOf(
            HtdemucsTensorBinding("waveform", "waveform_tensor", 2, listOf(1, 2)),
            HtdemucsTensorBinding("spectrum", "spectrum_tensor", 2, listOf(1, 2)),
        )
        val OUTPUTS = listOf(
            HtdemucsTensorBinding("frequency", "frequency_tensor", 2, listOf(1, 2)),
            HtdemucsTensorBinding("time", "time_tensor", 2, listOf(1, 2)),
        )

        fun validOutputsStatic() = linkedMapOf(
            "frequency_tensor" to floatArrayOf(5f, 6f),
            "time_tensor" to floatArrayOf(7f, 8f),
        )
    }
}
