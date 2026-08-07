package com.mardous.booming.separation.model

import com.mardous.booming.separation.model.contract.MultiTensorContract
import com.mardous.booming.separation.model.contract.MultiTensorDescriptor
import com.mardous.booming.separation.model.contract.MultiTensorDtype
import com.mardous.booming.separation.model.contract.MultiTensorOutputBinding
import com.mardous.booming.separation.model.contract.MultiTensorOutputPacking
import com.mardous.booming.separation.model.contract.MultiTensorPipelineContract
import com.mardous.booming.separation.model.contract.MultiTensorRenderMode
import com.mardous.booming.separation.model.contract.MultiTensorStemContract
import com.mardous.booming.separation.model.contract.MultiTensorStemDescriptor
import com.mardous.booming.separation.model.contract.SourceSeparationMultiTensorContract
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException

class HtdemucsPipelineAdapterTest {
    @Test
    fun `accepts only reviewed canonical four and six stem contracts`() {
        listOf(4, 6).forEach { stemCount ->
            assertEquals(stemIds(stemCount), HtdemucsPipelineAdapter(contract(stemCount)).orderedStemIds)
        }

        val unsupportedGeometry = contract(6).let { baseline ->
            baseline.copy(
                pipelineContract = baseline.pipelineContract.copy(windowSamples = 343_979),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            HtdemucsPipelineAdapter(unsupportedGeometry)
        }
    }

    @Test
    fun `global normalization round trips finite planar stereo`() {
        val adapter = HtdemucsPipelineAdapter(contract(4))
        val track = floatArrayOf(-0.75f, 0.25f, 0.5f, 1f, 0.5f, -0.25f, 0.75f, -1f)

        val normalization = adapter.globalNormalization(track)
        val restored = adapter.denormalizeStemSet(
            adapter.normalizeTrack(track, normalization),
            normalization,
        )

        assertArrayEquals(track, restored, 1e-6f)
        assertTrue(normalization.divisor > 0f)
    }

    @Test
    fun `synthetic four and six stem branches reconstruct one coherent ordered set`() {
        listOf(4, 6).forEach { stemCount ->
            val dsp = HtdemucsHostDsp(SYNTHETIC_WINDOW_SAMPLES)
            val frequency = FloatArray(
                stemCount * HtdemucsPipelineAdapter.FEATURE_COUNT *
                    HtdemucsPipelineAdapter.FREQUENCY_BINS * dsp.frameCount,
            )
            val time = FloatArray(
                stemCount * HtdemucsPipelineAdapter.CHANNEL_COUNT * SYNTHETIC_WINDOW_SAMPLES,
            ) { index -> ((index * 17L + stemCount) % 257L).toFloat() / 257f }

            val first = dsp.reconstructBranches(frequency, time, stemCount)
            val second = dsp.reconstructBranches(frequency, time, stemCount)

            assertArrayEquals("zero frequency branch must preserve time branch", time, first, 0f)
            assertArrayEquals("reconstruction must be deterministic", first, second, 0f)
            assertEquals(stemCount * 2 * SYNTHETIC_WINDOW_SAMPLES, first.size)
        }
    }

    @Test
    fun `waveform preparation is deterministic and finite`() {
        val dsp = HtdemucsHostDsp(SYNTHETIC_WINDOW_SAMPLES)
        val waveform = FloatArray(2 * SYNTHETIC_WINDOW_SAMPLES) { index ->
            ((index * 31L + 7L) % 511L).toFloat() / 511f - 0.5f
        }

        val first = dsp.waveformToSpectrum(waveform)
        val second = dsp.waveformToSpectrum(waveform)

        assertArrayEquals(first, second, 0f)
        assertTrue(first.all(Float::isFinite))
        assertEquals(4 * 2048 * dsp.frameCount, first.size)
    }

    @Test
    fun `parallel lanes preserve serial raw float output for four and six stems`() {
        listOf(4, 6).forEach { stemCount ->
            val serialDsp = HtdemucsHostDsp(SYNTHETIC_WINDOW_SAMPLES)
            val frequency = FloatArray(
                stemCount * HtdemucsPipelineAdapter.FEATURE_COUNT *
                    HtdemucsPipelineAdapter.FREQUENCY_BINS * serialDsp.frameCount,
            ) { index ->
                (((index * 37L + 11L) % 257L).toInt() - 128) * 1e-5f
            }
            val serial = serialDsp.use { it.frequencyToWaveform(frequency, stemCount) }

            listOf(2, 4).forEach { workers ->
                HtdemucsHostDsp(
                    windowSamples = SYNTHETIC_WINDOW_SAMPLES,
                    istftMode = HtdemucsIstftMode.ParallelLanes,
                    istftWorkers = workers,
                ).use { dsp ->
                    repeat(2) { repetition ->
                        assertRawFloatEquals(
                            serial,
                            dsp.frequencyToWaveform(frequency, stemCount),
                            "$stemCount stems, $workers workers, repetition $repetition",
                        )
                    }
                }
            }
        }

        assertTrue(
            Thread.getAllStackTraces().keys.none { thread ->
                thread.isAlive && thread.name.startsWith("booming-htdemucs-istft-")
            },
        )
    }

    @Test
    fun `parallel reconstruction rejects invalid worker counts and cancellation`() {
        assertThrows(IllegalArgumentException::class.java) {
            HtdemucsHostDsp(
                SYNTHETIC_WINDOW_SAMPLES,
                HtdemucsIstftMode.ParallelLanes,
                1,
            )
        }
        HtdemucsHostDsp(
            SYNTHETIC_WINDOW_SAMPLES,
            HtdemucsIstftMode.ParallelLanes,
            2,
        ).use { dsp ->
            assertThrows(CancellationException::class.java) {
                dsp.frequencyToWaveform(
                    FloatArray(4 * 4 * 2048 * dsp.frameCount),
                    stemCount = 4,
                    shouldCancel = { true },
                )
            }
        }
    }

    private fun assertRawFloatEquals(
        expected: FloatArray,
        actual: FloatArray,
        label: String,
    ) {
        assertEquals("$label element count", expected.size, actual.size)
        expected.indices.forEach { index ->
            assertEquals(
                "$label raw float mismatch at $index",
                expected[index].toRawBits(),
                actual[index].toRawBits(),
            )
        }
    }

    private fun contract(stemCount: Int): SourceSeparationMultiTensorContract {
        val stemIds = stemIds(stemCount)
        return SourceSeparationMultiTensorContract(
            contractSchemaVersion = 1,
            contractKind = "bss-static-multitensor-v1",
            contractId = "htdemucs_${stemCount}s@1",
            modelId = "htdemucs_${stemCount}s",
            displayName = "HTDemucs $stemCount stem",
            tensorContract = MultiTensorContract(
                inputs = listOf(
                    tensor(0, "args_0", listOf(1, 2, 343980), "batch", "channel", "sample"),
                    tensor(
                        1,
                        "args_1",
                        listOf(1, 4, 2048, 336),
                        "batch",
                        "feature",
                        "frequency",
                        "frame",
                    ),
                ),
                outputs = listOf(
                    tensor(
                        0,
                        "output_0",
                        listOf(1, stemCount, 4, 2048, 336),
                        "batch",
                        "stem",
                        "feature",
                        "frequency",
                        "frame",
                    ),
                    tensor(
                        1,
                        "output_1",
                        listOf(1, stemCount, 2, 343980),
                        "batch",
                        "stem",
                        "channel",
                        "sample",
                    ),
                ),
                outputBindings = listOf(
                    MultiTensorOutputBinding(
                        tensorIndex = 0,
                        tensorName = "output_0",
                        stemAxis = 1,
                        packing = MultiTensorOutputPacking.StemAxis,
                        stemIds = stemIds,
                    ),
                    MultiTensorOutputBinding(
                        tensorIndex = 1,
                        tensorName = "output_1",
                        stemAxis = 1,
                        packing = MultiTensorOutputPacking.StemAxis,
                        stemIds = stemIds,
                    ),
                ),
            ),
            stemContract = MultiTensorStemContract(
                stems = stemIds.mapIndexed { index, id ->
                    MultiTensorStemDescriptor(id, id, id.replaceFirstChar(Char::uppercase), index)
                },
            ),
            pipelineContract = MultiTensorPipelineContract(
                pipelineId = HtdemucsPipelineAdapter.PIPELINE_ID,
                pipelineVersion = HtdemucsPipelineAdapter.PIPELINE_VERSION,
                sampleRate = HtdemucsPipelineAdapter.SAMPLE_RATE,
                channelCount = HtdemucsPipelineAdapter.CHANNEL_COUNT,
                windowSamples = HtdemucsPipelineAdapter.WINDOW_SAMPLES,
                fftSize = HtdemucsPipelineAdapter.FFT_SIZE,
                hopLength = HtdemucsPipelineAdapter.HOP_LENGTH,
                renderMode = MultiTensorRenderMode.NeuralCoreWaveformFrequencyOla,
            ),
        )
    }

    private fun tensor(
        index: Int,
        name: String,
        shape: List<Int>,
        vararg axes: String,
    ) = MultiTensorDescriptor(index, name, MultiTensorDtype.Float32, shape, axes.toList())

    private fun stemIds(stemCount: Int) = when (stemCount) {
        4 -> listOf("drums", "bass", "other", "vocals")
        6 -> listOf("drums", "bass", "other", "vocals", "guitar", "piano")
        else -> error("Unsupported test stem count")
    }

    private companion object {
        const val SYNTHETIC_WINDOW_SAMPLES = 4_096
    }
}
