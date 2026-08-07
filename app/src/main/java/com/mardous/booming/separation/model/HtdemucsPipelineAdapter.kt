package com.mardous.booming.separation.model

import com.mardous.booming.separation.model.contract.MultiTensorDescriptor
import com.mardous.booming.separation.model.contract.MultiTensorDtype
import com.mardous.booming.separation.model.contract.MultiTensorRenderMode
import com.mardous.booming.separation.model.contract.SourceSeparationMultiTensorContract
import com.mardous.booming.separation.model.contract.SourceSeparationMultiTensorContractValidator
import org.jtransforms.fft.FloatFFT_1D
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sqrt

data class HtdemucsGlobalNormalization(
    val mean: Float,
    val sampleStandardDeviation: Float,
    val divisor: Float,
)

data class HtdemucsNeuralInputs(
    val waveform: FloatArray,
    val spectrum: FloatArray,
)

data class HtdemucsNeuralOutputs(
    val frequency: FloatArray,
    val waveform: FloatArray,
)

data class HtdemucsWindowStemSet(
    val orderedStemIds: List<String>,
    /** Packed planar samples in [stem, channel, sample] order. */
    val planarSamples: FloatArray,
    val samplesPerStem: Int,
)

/** Host DSP and branch reconstruction for the reviewed canonical HTDemucs neural core. */
class HtdemucsPipelineAdapter(
    val contract: SourceSeparationMultiTensorContract,
) {
    private val dsp by lazy {
        HtdemucsHostDsp(contract.pipelineContract.windowSamples)
    }

    val orderedStemIds: List<String> = contract.stemContract.stems.map { it.stemId }

    init {
        SourceSeparationMultiTensorContractValidator.validate(contract)
        validateSupportedContract(contract)
    }

    fun globalNormalization(planarStereoTrack: FloatArray): HtdemucsGlobalNormalization {
        require(planarStereoTrack.size % CHANNEL_COUNT == 0) { "Expected a planar stereo track." }
        val sampleCount = planarStereoTrack.size / CHANNEL_COUNT
        require(sampleCount >= 2) {
            "Sample standard deviation with correction=1 requires at least two samples."
        }

        var sum = 0.0
        repeat(sampleCount) { sample ->
            val left = planarStereoTrack[sample]
            val right = planarStereoTrack[sampleCount + sample]
            require(left.isFinite() && right.isFinite()) { "Normalization input must be finite." }
            sum += (left.toDouble() + right.toDouble()) * 0.5
        }
        val mean = sum / sampleCount
        var squaredDeviationSum = 0.0
        repeat(sampleCount) { sample ->
            val mono = (
                planarStereoTrack[sample].toDouble() +
                    planarStereoTrack[sampleCount + sample].toDouble()
                ) * 0.5
            val deviation = mono - mean
            squaredDeviationSum += deviation * deviation
        }
        val standardDeviation = sqrt(squaredDeviationSum / (sampleCount - 1)).toFloat()
        val divisor = standardDeviation + NORMALIZATION_EPSILON
        require(mean.toFloat().isFinite() && divisor.isFinite() && divisor > 0f) {
            "Normalization parameters must be finite with a positive divisor."
        }
        return HtdemucsGlobalNormalization(mean.toFloat(), standardDeviation, divisor)
    }

    fun normalizeTrack(
        planarStereoTrack: FloatArray,
        normalization: HtdemucsGlobalNormalization = globalNormalization(planarStereoTrack),
    ): FloatArray = FloatArray(planarStereoTrack.size) { index ->
        (planarStereoTrack[index] - normalization.mean) / normalization.divisor
    }

    fun denormalizeStemSet(
        normalizedStemSet: FloatArray,
        normalization: HtdemucsGlobalNormalization,
    ): FloatArray = FloatArray(normalizedStemSet.size) { index ->
        normalizedStemSet[index] * normalization.divisor + normalization.mean
    }

    /** The caller must extract this window from the globally normalized track. */
    fun prepareWindow(normalizedPlanarStereoWindow: FloatArray): HtdemucsNeuralInputs {
        require(normalizedPlanarStereoWindow.size == CHANNEL_COUNT * WINDOW_SAMPLES) {
            "Expected one complete canonical planar stereo window."
        }
        return HtdemucsNeuralInputs(
            waveform = normalizedPlanarStereoWindow.copyOf(),
            spectrum = dsp.waveformToSpectrum(normalizedPlanarStereoWindow),
        )
    }

    fun reconstructWindow(outputs: HtdemucsNeuralOutputs): HtdemucsWindowStemSet {
        val expectedWaveformElements = orderedStemIds.size * CHANNEL_COUNT * WINDOW_SAMPLES
        require(outputs.waveform.size == expectedWaveformElements) {
            "HTDemucs waveform branch has ${outputs.waveform.size} values; expected " +
                "$expectedWaveformElements."
        }
        val combined = dsp.reconstructBranches(
            packedFrequency = outputs.frequency,
            timeWaveform = outputs.waveform,
            stemCount = orderedStemIds.size,
        )
        return HtdemucsWindowStemSet(
            orderedStemIds = orderedStemIds,
            planarSamples = combined,
            samplesPerStem = WINDOW_SAMPLES,
        )
    }

    companion object {
        const val PIPELINE_ID = "booming-ss-htdemucs-neural-core"
        const val PIPELINE_VERSION = 1
        const val SAMPLE_RATE = 44_100
        const val CHANNEL_COUNT = 2
        const val WINDOW_SAMPLES = 343_980
        const val FFT_SIZE = 4_096
        const val HOP_LENGTH = 1_024
        const val FRAME_COUNT = 336
        const val FREQUENCY_BINS = 2_048
        const val FEATURE_COUNT = 4
        const val NORMALIZATION_EPSILON = 1e-8f

        private fun validateSupportedContract(contract: SourceSeparationMultiTensorContract) {
            val pipeline = contract.pipelineContract
            require(
                pipeline.pipelineId == PIPELINE_ID &&
                    pipeline.pipelineVersion == PIPELINE_VERSION &&
                    pipeline.sampleRate == SAMPLE_RATE &&
                    pipeline.channelCount == CHANNEL_COUNT &&
                    pipeline.windowSamples == WINDOW_SAMPLES &&
                    pipeline.fftSize == FFT_SIZE &&
                    pipeline.hopLength == HOP_LENGTH &&
                    pipeline.renderMode == MultiTensorRenderMode.NeuralCoreWaveformFrequencyOla,
            ) { "Unsupported HTDemucs pipeline contract or geometry." }

            require(
                contract.tensorContract.inputs.size == 2 &&
                    contract.tensorContract.outputs.size == 2,
            ) { "The reviewed HTDemucs pipeline requires exactly two inputs and two outputs." }

            requireTensor(
                contract.tensorContract.inputs[0],
                "args_0",
                listOf(1, CHANNEL_COUNT, WINDOW_SAMPLES),
                listOf("batch", "channel", "sample"),
            )
            requireTensor(
                contract.tensorContract.inputs[1],
                "args_1",
                listOf(1, FEATURE_COUNT, FREQUENCY_BINS, FRAME_COUNT),
                listOf("batch", "feature", "frequency", "frame"),
            )
            val stemCount = contract.stemContract.stems.size
            require(stemCount == 4 || stemCount == 6) {
                "The reviewed HTDemucs adapter supports only four or six stems."
            }
            requireTensor(
                contract.tensorContract.outputs[0],
                "output_0",
                listOf(1, stemCount, FEATURE_COUNT, FREQUENCY_BINS, FRAME_COUNT),
                listOf("batch", "stem", "feature", "frequency", "frame"),
            )
            requireTensor(
                contract.tensorContract.outputs[1],
                "output_1",
                listOf(1, stemCount, CHANNEL_COUNT, WINDOW_SAMPLES),
                listOf("batch", "stem", "channel", "sample"),
            )
        }

        private fun requireTensor(
            descriptor: MultiTensorDescriptor,
            name: String,
            shape: List<Int>,
            axes: List<String>,
        ) {
            require(
                descriptor.name == name && descriptor.dtype == MultiTensorDtype.Float32 &&
                    descriptor.shape == shape && descriptor.axes == axes,
            ) { "Unsupported HTDemucs tensor contract for $name." }
        }
    }
}

internal class HtdemucsHostDsp(
    private val windowSamples: Int,
) {
    val frameCount: Int = ceil(windowSamples.toDouble() / HtdemucsPipelineAdapter.HOP_LENGTH).toInt()

    private val fft = FloatFFT_1D(HtdemucsPipelineAdapter.FFT_SIZE.toLong())
    private val hann = FloatArray(HtdemucsPipelineAdapter.FFT_SIZE) { index ->
        (0.5 - 0.5 * cos(2.0 * PI * index / HtdemucsPipelineAdapter.FFT_SIZE)).toFloat()
    }
    private val forwardScale = (1.0 / sqrt(HtdemucsPipelineAdapter.FFT_SIZE.toDouble())).toFloat()
    private val inverseScale = sqrt(HtdemucsPipelineAdapter.FFT_SIZE.toDouble()).toFloat()
    private val outerLength = frameCount * HtdemucsPipelineAdapter.HOP_LENGTH + OUTER_PAD_LEFT * 2
    private val outerPadRight = outerLength - windowSamples - OUTER_PAD_LEFT
    private val fullFrameCount = frameCount + FRAME_PAD_LEFT + FRAME_PAD_RIGHT
    private val overlapLength = HtdemucsPipelineAdapter.FFT_SIZE +
        HtdemucsPipelineAdapter.HOP_LENGTH * (fullFrameCount - 1)
    private val windowSquareSum = FloatArray(overlapLength).also { sum ->
        repeat(fullFrameCount) { frame ->
            val start = frame * HtdemucsPipelineAdapter.HOP_LENGTH
            hann.indices.forEach { sample -> sum[start + sample] += hann[sample] * hann[sample] }
        }
    }

    init {
        require(windowSamples > 0 && outerPadRight >= 0)
    }

    @Synchronized
    fun waveformToSpectrum(planarStereoWaveform: FloatArray): FloatArray {
        require(planarStereoWaveform.size == HtdemucsPipelineAdapter.CHANNEL_COUNT * windowSamples)
        val output = FloatArray(
            HtdemucsPipelineAdapter.FEATURE_COUNT * HtdemucsPipelineAdapter.FREQUENCY_BINS *
                frameCount,
        )
        val padded = FloatArray(outerLength)
        val fftBuffer = FloatArray(HtdemucsPipelineAdapter.FFT_SIZE * 2)
        repeat(HtdemucsPipelineAdapter.CHANNEL_COUNT) { channel ->
            val channelOffset = channel * windowSamples
            padded.indices.forEach { index ->
                padded[index] = planarStereoWaveform[
                    channelOffset + reflectIndex(index - OUTER_PAD_LEFT, windowSamples)
                ]
            }
            repeat(frameCount) { frame ->
                val start = frame * HtdemucsPipelineAdapter.HOP_LENGTH
                fftBuffer.fill(0f)
                repeat(HtdemucsPipelineAdapter.FFT_SIZE) { sample ->
                    fftBuffer[sample] = padded[start + sample] * hann[sample]
                }
                fft.realForwardFull(fftBuffer)
                val realFeature = channel * 2
                val imaginaryFeature = realFeature + 1
                repeat(HtdemucsPipelineAdapter.FREQUENCY_BINS) { frequency ->
                    output[spectrumIndex(realFeature, frequency, frame)] =
                        fftBuffer[frequency * 2] * forwardScale
                    output[spectrumIndex(imaginaryFeature, frequency, frame)] =
                        fftBuffer[frequency * 2 + 1] * forwardScale
                }
            }
        }
        return output
    }

    @Synchronized
    fun frequencyToWaveform(packedFrequency: FloatArray, stemCount: Int): FloatArray {
        require(stemCount > 0)
        val expected = stemCount * HtdemucsPipelineAdapter.FEATURE_COUNT *
            HtdemucsPipelineAdapter.FREQUENCY_BINS * frameCount
        require(packedFrequency.size == expected) {
            "Expected packed frequency tensor with $expected values."
        }
        val output = FloatArray(stemCount * HtdemucsPipelineAdapter.CHANNEL_COUNT * windowSamples)
        val fftBuffer = FloatArray(HtdemucsPipelineAdapter.FFT_SIZE * 2)
        val overlap = FloatArray(overlapLength)
        repeat(stemCount) { stem ->
            repeat(HtdemucsPipelineAdapter.CHANNEL_COUNT) { channel ->
                overlap.fill(0f)
                val realFeature = channel * 2
                val imaginaryFeature = realFeature + 1
                repeat(frameCount) { frame ->
                    fftBuffer.fill(0f)
                    repeat(HtdemucsPipelineAdapter.FREQUENCY_BINS) { frequency ->
                        val real = packedFrequency[frequencyIndex(stem, realFeature, frequency, frame)]
                        val imaginary = packedFrequency[
                            frequencyIndex(stem, imaginaryFeature, frequency, frame)
                        ]
                        setComplex(fftBuffer, frequency, real, imaginary)
                        if (frequency > 0) {
                            setComplex(
                                fftBuffer,
                                HtdemucsPipelineAdapter.FFT_SIZE - frequency,
                                real,
                                -imaginary,
                            )
                        }
                    }
                    fft.complexInverse(fftBuffer, true)
                    val start = (frame + FRAME_PAD_LEFT) * HtdemucsPipelineAdapter.HOP_LENGTH
                    repeat(HtdemucsPipelineAdapter.FFT_SIZE) { sample ->
                        overlap[start + sample] += fftBuffer[sample * 2] * inverseScale * hann[sample]
                    }
                }
                val outputOffset = (stem * HtdemucsPipelineAdapter.CHANNEL_COUNT + channel) *
                    windowSamples
                repeat(windowSamples) { sample ->
                    val overlapIndex = CENTER_TRIM + OUTER_PAD_LEFT + sample
                    val divisor = windowSquareSum[overlapIndex]
                    check(divisor > 0f)
                    output[outputOffset + sample] = overlap[overlapIndex] / divisor
                }
            }
        }
        return output
    }

    fun reconstructBranches(
        packedFrequency: FloatArray,
        timeWaveform: FloatArray,
        stemCount: Int,
    ): FloatArray {
        val expectedWaveformElements = stemCount * HtdemucsPipelineAdapter.CHANNEL_COUNT *
            windowSamples
        require(timeWaveform.size == expectedWaveformElements) {
            "Expected waveform branch with $expectedWaveformElements values."
        }
        val frequencyWaveform = frequencyToWaveform(packedFrequency, stemCount)
        return FloatArray(expectedWaveformElements) { index ->
            frequencyWaveform[index] + timeWaveform[index]
        }
    }

    private fun spectrumIndex(feature: Int, frequency: Int, frame: Int): Int =
        (feature * HtdemucsPipelineAdapter.FREQUENCY_BINS + frequency) * frameCount + frame

    private fun frequencyIndex(stem: Int, feature: Int, frequency: Int, frame: Int): Int =
        ((stem * HtdemucsPipelineAdapter.FEATURE_COUNT + feature) *
            HtdemucsPipelineAdapter.FREQUENCY_BINS + frequency) * frameCount + frame

    private fun setComplex(buffer: FloatArray, bin: Int, real: Float, imaginary: Float) {
        buffer[bin * 2] = real
        buffer[bin * 2 + 1] = imaginary
    }

    private fun reflectIndex(index: Int, size: Int): Int {
        var reflected = index
        while (reflected < 0 || reflected >= size) {
            reflected = if (reflected < 0) -reflected else 2 * size - reflected - 2
        }
        return reflected
    }

    private companion object {
        const val OUTER_PAD_LEFT = 1_536
        const val CENTER_TRIM = HtdemucsPipelineAdapter.FFT_SIZE / 2
        const val FRAME_PAD_LEFT = 2
        const val FRAME_PAD_RIGHT = 2
    }
}
