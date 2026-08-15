package com.mardous.booming.separation.model

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

@RunWith(AndroidJUnit4::class)
class NativeMdxDspParityDeviceTest {
    @Test
    fun packedRealMatchesProductSpectrogram() {
        val requestedShape = InstrumentationRegistry.getArguments()
            .getString(SHAPE_ARGUMENT, ALL_SHAPES)
            .orEmpty()
            .lowercase()
        val selectedShapes = if (requestedShape == ALL_SHAPES) {
            SHAPES.entries
        } else {
            val config = requireNotNull(SHAPES[requestedShape]) {
                "Unknown $SHAPE_ARGUMENT '$requestedShape'; expected ${SHAPES.keys.joinToString()} or $ALL_SHAPES."
            }
            listOf(MapEntry(requestedShape, config))
        }

        for ((shapeName, config) in selectedShapes) {
            assertParity(shapeName, config)
        }
    }

    private fun assertParity(shapeName: String, config: MdxDspConfig) {
        val input = fixture(config)
        val productDsp = MdxSpectrogram(config)
        val productTensorNchw = productDsp.waveformToTensor(input)
        val productTensorNhwc = nchwToNhwc(productTensorNchw, config)
        val productWaveform = productDsp.tensorToWaveform(productTensorNchw)

        val nativeTensorNhwc = FloatArray(config.tensorElementCount)
        val nativeWaveform = Array(MdxDspConfig.STEREO_CHANNELS) {
            FloatArray(config.chunkSize)
        }
        NativeMdxDsp(config).use { nativeDsp ->
            nativeDsp.waveformToNhwcTensorInto(input, nativeTensorNhwc)
            nativeDsp.nhwcTensorToWaveformInto(productTensorNhwc, nativeWaveform)
        }

        assertEquals(config.tensorElementCount, productTensorNhwc.size)
        assertEquals(config.tensorElementCount, nativeTensorNhwc.size)
        assertTrue(
            "$shapeName product tensor contains non-finite values",
            productTensorNhwc.all { it.isFinite() },
        )
        assertTrue(
            "$shapeName native tensor contains non-finite values",
            nativeTensorNhwc.all { it.isFinite() },
        )
        assertWaveform(shapeName, "product", productWaveform, config)
        assertWaveform(shapeName, "native", nativeWaveform, config)

        val stft = errorStats(productTensorNhwc, nativeTensorNhwc)
        val iStft = errorStats(productWaveform, nativeWaveform)
        println("Native MDX parity $shapeName: STFT=$stft iSTFT=$iStft")
        assertNumericalGate(shapeName, "STFT", stft)
        assertNumericalGate(shapeName, "iSTFT", iStft)
    }

    private fun assertWaveform(
        shapeName: String,
        implementation: String,
        waveform: Array<FloatArray>,
        config: MdxDspConfig,
    ) {
        assertEquals("$shapeName $implementation channel count", MdxDspConfig.STEREO_CHANNELS, waveform.size)
        waveform.forEachIndexed { channel, samples ->
            assertEquals("$shapeName $implementation channel $channel size", config.chunkSize, samples.size)
            assertTrue(
                "$shapeName $implementation channel $channel contains non-finite values",
                samples.all { it.isFinite() },
            )
        }
    }

    private fun assertNumericalGate(shapeName: String, stage: String, stats: ErrorStats) {
        assertTrue(
            "$shapeName $stage SNR ${stats.snrDb} dB is below $MIN_SNR_DB dB",
            stats.snrDb >= MIN_SNR_DB,
        )
        assertTrue(
            "$shapeName $stage max abs ${stats.maxAbs} exceeds $MAX_ABS_ERROR",
            stats.maxAbs <= MAX_ABS_ERROR,
        )
    }

    private fun fixture(config: MdxDspConfig): Array<FloatArray> =
        Array(MdxDspConfig.STEREO_CHANNELS) { channel ->
            FloatArray(config.chunkSize) { index ->
                (
                    0.1 * sin(2.0 * PI * (220 + channel * 37) * index / config.sampleRate) +
                        0.01 * sin(index * 0.013)
                    ).toFloat()
            }
        }

    private fun nchwToNhwc(input: FloatArray, config: MdxDspConfig): FloatArray {
        require(input.size == config.tensorElementCount)
        val output = FloatArray(input.size)
        for (channel in 0 until MdxDspConfig.STEM_COMPLEX_CHANNELS) {
            for (frequency in 0 until config.dimF) {
                for (frame in 0 until config.dimT) {
                    output[(frequency * config.dimT + frame) * MdxDspConfig.STEM_COMPLEX_CHANNELS + channel] =
                        input[(channel * config.dimF + frequency) * config.dimT + frame]
                }
            }
        }
        return output
    }

    private fun errorStats(reference: FloatArray, candidate: FloatArray): ErrorStats {
        require(reference.size == candidate.size)
        val accumulator = ErrorAccumulator()
        for (index in reference.indices) {
            accumulator.add(reference[index], candidate[index])
        }
        return accumulator.result()
    }

    private fun errorStats(
        reference: Array<FloatArray>,
        candidate: Array<FloatArray>,
    ): ErrorStats {
        require(reference.size == candidate.size)
        val accumulator = ErrorAccumulator()
        for (channel in reference.indices) {
            require(reference[channel].size == candidate[channel].size)
            for (index in reference[channel].indices) {
                accumulator.add(reference[channel][index], candidate[channel][index])
            }
        }
        return accumulator.result()
    }

    private class ErrorAccumulator {
        private var signal = 0.0
        private var squaredError = 0.0
        private var maxAbs = 0.0
        private var count = 0

        fun add(reference: Float, candidate: Float) {
            val expected = reference.toDouble()
            val delta = candidate.toDouble() - expected
            signal += expected * expected
            squaredError += delta * delta
            maxAbs = maxOf(maxAbs, abs(delta))
            count++
        }

        fun result(): ErrorStats {
            check(count > 0)
            val snrDb = if (squaredError == 0.0) {
                Double.POSITIVE_INFINITY
            } else {
                10.0 * log10(signal / squaredError)
            }
            return ErrorStats(
                snrDb = snrDb,
                maxAbs = maxAbs,
                rmsError = sqrt(squaredError / count),
            )
        }
    }

    private data class ErrorStats(
        val snrDb: Double,
        val maxAbs: Double,
        val rmsError: Double,
    )

    private data class MapEntry(
        override val key: String,
        override val value: MdxDspConfig,
    ) : Map.Entry<String, MdxDspConfig>

    private companion object {
        const val SHAPE_ARGUMENT = "mdxDspShape"
        const val ALL_SHAPES = "all"
        const val MIN_SNR_DB = 80.0
        const val MAX_ABS_ERROR = 1e-3

        val SHAPES = linkedMapOf(
            "fft4096-f2048-t128" to MdxDspConfig(nFft = 4_096, dimF = 2_048, dimTPower = 7),
            "fft4096-f2048-t512" to MdxDspConfig(nFft = 4_096, dimF = 2_048, dimTPower = 9),
            "fft5120-f2048-t256" to MdxDspConfig(nFft = 5_120, dimF = 2_048, dimTPower = 8),
            "fft5120-f2560-t256" to MdxDspConfig(nFft = 5_120, dimF = 2_560, dimTPower = 8),
            "fft6144-f2048-t256" to MdxDspConfig(nFft = 6_144, dimF = 2_048, dimTPower = 8),
            "fft6144-f2048-t512" to MdxDspConfig(nFft = 6_144, dimF = 2_048, dimTPower = 9),
            "fft6144-f3072-t256" to MdxDspConfig(nFft = 6_144, dimF = 3_072, dimTPower = 8),
            "fft6144-f3072-t512" to MdxDspConfig(nFft = 6_144, dimF = 3_072, dimTPower = 9),
            "fft7680-f3072-t256" to MdxDspConfig(nFft = 7_680, dimF = 3_072, dimTPower = 8),
            "fft8192-f2048-t256" to MdxDspConfig(nFft = 8_192, dimF = 2_048, dimTPower = 8),
            "fft8192-f2048-t512" to MdxDspConfig(nFft = 8_192, dimF = 2_048, dimTPower = 9),
            "fft16384-f2048-t256" to MdxDspConfig(nFft = 16_384, dimF = 2_048, dimTPower = 8),
            "fft16384-f2048-t512" to MdxDspConfig(nFft = 16_384, dimF = 2_048, dimTPower = 9),
        )
    }
}
