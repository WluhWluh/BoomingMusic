package com.mardous.booming.separation.model.litert

import com.google.ai.edge.litert.TensorType
import com.mardous.booming.separation.model.HtdemucsNeuralInputs
import com.mardous.booming.separation.model.HtdemucsNeuralOutputs
import com.mardous.booming.separation.model.HtdemucsPipelineAdapter
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
        var observed = 0
        val backend = FakeBackend { inputs ->
            assertEquals(setOf("waveform", "spectrum"), inputs.keys)
            linkedMapOf(
                "frequency" to floatArrayOf(5f, 6f),
                "time" to floatArrayOf(7f, 8f),
            )
        }
        val forward = AtomicHtdemucsNamedTensorForward(INPUTS, OUTPUTS, backend) {
            observed += 1
        }

        val outputs = forward.run(
            HtdemucsNeuralInputs(floatArrayOf(1f, 2f), floatArrayOf(3f, 4f)),
            shouldCancel = { false },
        )

        assertArrayEquals(floatArrayOf(5f, 6f), outputs.frequency, 0f)
        assertArrayEquals(floatArrayOf(7f, 8f), outputs.waveform, 0f)
        assertEquals(1, observed)
    }

    @Test
    fun `atomic forward rejects missing malformed and non-finite branches`() {
        var observed = 0
        listOf(
            linkedMapOf("frequency" to floatArrayOf(1f, 2f)),
            linkedMapOf(
                "frequency" to floatArrayOf(1f),
                "time" to floatArrayOf(2f, 3f),
            ),
            linkedMapOf(
                "frequency" to floatArrayOf(1f, 2f),
                "time" to floatArrayOf(Float.NaN, 3f),
            ),
        ).forEach { outputs ->
            assertThrows(IllegalArgumentException::class.java) {
                AtomicHtdemucsNamedTensorForward(
                    INPUTS,
                    OUTPUTS,
                    FakeBackend { outputs },
                ) { observed += 1 }.run(validInputs(), shouldCancel = { false })
            }
        }
        assertEquals(0, observed)
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
                contract.flatBuffer.inputs.map { it.logicalName },
                recordingFactory.inputs.map { it.signatureName },
            )
            assertEquals(
                contract.flatBuffer.outputs.map { it.logicalName },
                recordingFactory.outputs.map { it.signatureName },
            )
            assertEquals(
                contract.flatBuffer.inputs.map { it.tensorName },
                recordingFactory.inputs.map { it.tensorName },
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

    @Test
    fun `factory defaults to native direct managed buffers`() {
        val contract = loadContract("htdemucs-6s-official-fp32.json")
        val directory = Files.createTempDirectory("htdemucs-direct-session-test").toFile()
        val model = File(directory, contract.artifact.fileName)
        RandomAccessFile(model, "rw").use { it.setLength(contract.artifact.byteSize) }
        val directFactory = RecordingDirectPipelineFactory()
        try {
            val session = HtdemucsLiteRtCpuInferenceSessionFactory(
                directPipelineFactory = directFactory,
                availableProcessors = { 8 },
            ).create(
                HtdemucsVerifiedArtifact(
                    file = model,
                    byteSize = contract.artifact.byteSize,
                    sha256 = contract.artifact.sha256,
                ),
                contract,
            )

            assertEquals(6, directFactory.stemCount)
            assertEquals(4, directFactory.cpuThreads)
            assertEquals(4, directFactory.workerCount)
            assertEquals("fake-native-direct-v1", session.implementationId)
            session.close()
            assertTrue(directFactory.pipeline.closed)
        } finally {
            model.delete()
            directory.delete()
        }
    }

    @Test
    fun `direct session publishes only complete output and discards cancellation`() {
        val pipeline = RecordingDirectPipeline()
        var observed = 0
        val session = DirectHtdemucsCpuInferenceSession(
            pipeline = pipeline,
            orderedStemIds = listOf("stem"),
            validatedOutputObserver = { observed += 1 },
        )
        val input = FloatArray(
            HtdemucsPipelineAdapter.CHANNEL_COUNT * HtdemucsPipelineAdapter.WINDOW_SAMPLES,
        )

        val completed = session.runNormalizedWindow(input) { false }
        assertEquals(listOf("stem"), completed.orderedStemIds)
        assertEquals(HtdemucsPipelineAdapter.WINDOW_SAMPLES, completed.samplesPerStem)
        assertEquals(listOf("preprocess", "run", "read", "postprocess"), pipeline.calls)
        assertEquals(1, observed)

        pipeline.calls.clear()
        var canceled = false
        pipeline.onRun = { canceled = true }
        assertThrows(CancellationException::class.java) {
            session.runNormalizedWindow(input) { canceled }
        }
        assertEquals(listOf("preprocess", "run", "discard"), pipeline.calls)
        session.close()
    }

    @Test
    fun `managed pipeline fixes canonical logical names shapes and element counts`() {
        val contract = loadContract("htdemucs-6s-official-fp32.json")
        val inputs = contract.flatBuffer.inputs.map(HtdemucsTensorBinding::from)
        val outputs = contract.flatBuffer.outputs.map(HtdemucsTensorBinding::from)

        validateHtdemucsManagedBindings(inputs, outputs)

        assertThrows(IllegalArgumentException::class.java) {
            validateHtdemucsManagedBindings(
                inputs = listOf(inputs[0].copy(logicalName = "waveform"), inputs[1]),
                outputs = outputs,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateHtdemucsManagedBindings(
                inputs = inputs,
                outputs = listOf(
                    outputs[0].copy(shape = outputs[0].shape.dropLast(1)),
                    outputs[1],
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateHtdemucsManagedBindings(
                inputs = inputs,
                outputs = listOf(
                    outputs[0],
                    outputs[1].copy(elementCount = outputs[1].elementCount - 1),
                ),
            )
        }
    }

    @Test
    fun `managed pipeline requires fp32 static shapes and sufficient buffers`() {
        val binding = loadContract("htdemucs-6s-official-fp32.json")
            .flatBuffer.inputs.first()
            .let(HtdemucsTensorBinding::from)
        val expectedBytes = Math.multiplyExact(binding.elementCount, Float.SIZE_BYTES)

        validateHtdemucsManagedTensorType(
            actual = TensorType(
                TensorType.ElementType.FLOAT,
                TensorType.Layout(binding.shape),
            ),
            expected = binding,
            role = "input",
        )
        validateHtdemucsManagedBufferSize("input", binding, expectedBytes)
        validateHtdemucsManagedBufferSize("input", binding, expectedBytes + 64)

        assertThrows(IllegalArgumentException::class.java) {
            validateHtdemucsManagedTensorType(
                TensorType(TensorType.ElementType.INT, TensorType.Layout(binding.shape)),
                binding,
                "input",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateHtdemucsManagedTensorType(
                TensorType(
                    TensorType.ElementType.FLOAT,
                    TensorType.Layout(binding.shape, List(binding.shape.size) { 1 }),
                ),
                binding,
                "input",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateHtdemucsManagedBufferSize("input", binding, expectedBytes - 1)
        }
    }

    private fun forward(backend: HtdemucsNamedTensorBackend) =
        AtomicHtdemucsNamedTensorForward(INPUTS, OUTPUTS, backend)

    private fun validInputs() =
        HtdemucsNeuralInputs(floatArrayOf(1f, 2f), floatArrayOf(3f, 4f))

    private fun validOutputs() = linkedMapOf(
        "frequency" to floatArrayOf(5f, 6f),
        "time" to floatArrayOf(7f, 8f),
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

    private class RecordingDirectPipelineFactory : HtdemucsDirectPipelineFactory {
        val pipeline = RecordingDirectPipeline()
        var stemCount = 0
        var cpuThreads = 0
        var workerCount = 0

        override fun create(
            modelFile: File,
            stemCount: Int,
            cpuThreads: Int,
            workerCount: Int,
        ): HtdemucsDirectPipeline {
            this.stemCount = stemCount
            this.cpuThreads = cpuThreads
            this.workerCount = workerCount
            return pipeline
        }
    }

    private class RecordingDirectPipeline : HtdemucsDirectPipeline {
        override val implementationId = "fake-native-direct-v1"
        val calls = mutableListOf<String>()
        var onRun: () -> Unit = {}
        var closed = false

        override fun writeInputs(inputs: HtdemucsNeuralInputs) {
            calls += "write"
        }

        override fun preprocessAndWriteInput(normalizedPlanarStereo: FloatArray) {
            calls += "preprocess"
        }

        override fun run() {
            calls += "run"
            onRun()
        }

        override fun readOutputs(): HtdemucsNeuralOutputs {
            calls += "read"
            return HtdemucsNeuralOutputs(floatArrayOf(1f), floatArrayOf(2f))
        }

        override fun postprocessOutputs(): FloatArray {
            calls += "postprocess"
            return FloatArray(
                HtdemucsPipelineAdapter.CHANNEL_COUNT * HtdemucsPipelineAdapter.WINDOW_SAMPLES,
            )
        }

        override fun discard() {
            calls += "discard"
        }

        override fun close() {
            closed = true
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
            "frequency" to floatArrayOf(5f, 6f),
            "time" to floatArrayOf(7f, 8f),
        )
    }
}
