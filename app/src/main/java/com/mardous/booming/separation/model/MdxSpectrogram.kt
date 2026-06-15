package com.mardous.booming.separation.model

import org.jtransforms.fft.FloatFFT_1D
import kotlin.math.PI
import kotlin.math.cos

class MdxSpectrogram(
    private val config: MdxDspConfig = MdxDspConfig(),
) {
    private val fft = FloatFFT_1D(config.nFft.toLong())
    private val window = FloatArray(config.nFft) { index ->
        (0.5 - 0.5 * cos(2.0 * PI * index / config.nFft)).toFloat()
    }

    fun waveformToTensor(waveform: Array<FloatArray>): FloatArray {
        requireStereoChunk(waveform)

        val tensor = FloatArray(config.tensorElementCount)
        for (channel in 0 until MdxDspConfig.STEREO_CHANNELS) {
            val padded = reflectPad(waveform[channel], config.trim)
            val fftBuffer = FloatArray(config.nFft * 2)
            for (frameIndex in 0 until config.dimT) {
                val start = frameIndex * config.hopLength
                fftBuffer.fill(0f)
                for (sampleIndex in 0 until config.nFft) {
                    fftBuffer[sampleIndex] = padded[start + sampleIndex] * window[sampleIndex]
                }
                fft.realForwardFull(fftBuffer)

                val realChannel = channel * 2
                val imaginaryChannel = realChannel + 1
                for (frequencyIndex in 0 until config.dimF) {
                    tensor[tensorIndex(realChannel, frequencyIndex, frameIndex)] =
                        fftBuffer[frequencyIndex * 2]
                    tensor[tensorIndex(imaginaryChannel, frequencyIndex, frameIndex)] =
                        fftBuffer[frequencyIndex * 2 + 1]
                }
            }
        }
        return tensor
    }

    fun tensorToWaveform(tensor: FloatArray): Array<FloatArray> {
        require(tensor.size == config.tensorElementCount) {
            "Expected tensor size ${config.tensorElementCount}, got ${tensor.size}."
        }

        val outputLength = config.chunkSize + config.nFft
        val output = Array(MdxDspConfig.STEREO_CHANNELS) { FloatArray(outputLength) }
        val windowSum = FloatArray(outputLength)
        val fftBuffer = FloatArray(config.nFft * 2)

        for (channel in 0 until MdxDspConfig.STEREO_CHANNELS) {
            for (frameIndex in 0 until config.dimT) {
                fftBuffer.fill(0f)
                val realChannel = channel * 2
                val imaginaryChannel = realChannel + 1

                for (frequencyIndex in 0 until config.dimF) {
                    val real = tensor[tensorIndex(realChannel, frequencyIndex, frameIndex)]
                    val imaginary = tensor[tensorIndex(imaginaryChannel, frequencyIndex, frameIndex)]
                    setComplexBin(fftBuffer, frequencyIndex, real, imaginary)
                    if (frequencyIndex in 1 until config.nBins - 1) {
                        setComplexBin(fftBuffer, config.nFft - frequencyIndex, real, -imaginary)
                    }
                }

                fft.complexInverse(fftBuffer, true)
                val start = frameIndex * config.hopLength
                for (sampleIndex in 0 until config.nFft) {
                    val value = fftBuffer[sampleIndex * 2] * window[sampleIndex]
                    output[channel][start + sampleIndex] += value
                    if (channel == 0) {
                        windowSum[start + sampleIndex] += window[sampleIndex] * window[sampleIndex]
                    }
                }
            }
        }

        for (sampleIndex in output.indicesForNormalization(windowSum)) {
            val divisor = windowSum[sampleIndex]
            output[0][sampleIndex] /= divisor
            output[1][sampleIndex] /= divisor
        }

        return Array(MdxDspConfig.STEREO_CHANNELS) { channel ->
            output[channel].copyOfRange(config.trim, config.trim + config.chunkSize)
        }
    }

    private fun requireStereoChunk(waveform: Array<FloatArray>) {
        require(waveform.size == MdxDspConfig.STEREO_CHANNELS) {
            "Expected stereo waveform."
        }
        for (channel in waveform.indices) {
            require(waveform[channel].size == config.chunkSize) {
                "Expected channel $channel to contain ${config.chunkSize} samples, got ${waveform[channel].size}."
            }
        }
    }

    private fun reflectPad(input: FloatArray, pad: Int): FloatArray {
        val result = FloatArray(input.size + 2 * pad)
        for (index in result.indices) {
            val sourceIndex = reflectIndex(index - pad, input.size)
            result[index] = input[sourceIndex]
        }
        return result
    }

    private fun reflectIndex(index: Int, size: Int): Int {
        var reflected = index
        while (reflected < 0 || reflected >= size) {
            reflected = if (reflected < 0) {
                -reflected
            } else {
                2 * size - reflected - 2
            }
        }
        return reflected
    }

    private fun tensorIndex(channel: Int, frequencyIndex: Int, frameIndex: Int): Int {
        return (channel * config.dimF + frequencyIndex) * config.dimT + frameIndex
    }

    private fun setComplexBin(buffer: FloatArray, bin: Int, real: Float, imaginary: Float) {
        val index = bin * 2
        buffer[index] = real
        buffer[index + 1] = imaginary
    }

    private fun Array<FloatArray>.indicesForNormalization(windowSum: FloatArray): IntRange {
        return 0 until minOf(first().size, windowSum.size)
    }
}
