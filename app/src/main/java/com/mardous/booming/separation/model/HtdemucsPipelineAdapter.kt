package com.mardous.booming.separation.model

import android.os.Build
import com.mardous.booming.separation.model.contract.MultiTensorDescriptor
import com.mardous.booming.separation.model.contract.MultiTensorDtype
import com.mardous.booming.separation.model.contract.MultiTensorRenderMode
import com.mardous.booming.separation.model.contract.SourceSeparationMultiTensorContract
import com.mardous.booming.separation.model.contract.SourceSeparationMultiTensorContractValidator
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.jtransforms.fft.FloatFFT_1D
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
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

internal interface HtdemucsDspSession : AutoCloseable {
    val implementationId: String

    fun waveformToSpectrum(planarStereoWaveform: FloatArray): FloatArray

    fun frequencyToWaveform(
        packedFrequency: FloatArray,
        stemCount: Int,
        shouldCancel: () -> Boolean = { false },
    ): FloatArray

    fun reconstructBranches(
        packedFrequency: FloatArray,
        timeWaveform: FloatArray,
        stemCount: Int,
        shouldCancel: () -> Boolean = { false },
    ): FloatArray
}

/** Host DSP and branch reconstruction for the reviewed canonical HTDemucs neural core. */
class HtdemucsPipelineAdapter(
    val contract: SourceSeparationMultiTensorContract,
    istftMode: HtdemucsIstftMode = HtdemucsIstftMode.Serial,
    istftWorkers: Int = 1,
) : AutoCloseable {
    private val validatedIstftWorkers = validateHtdemucsIstftConfig(istftMode, istftWorkers)
    private val validatedContract = contract.also { candidate ->
        SourceSeparationMultiTensorContractValidator.validate(candidate)
        validateSupportedContract(candidate)
    }

    val orderedStemIds: List<String> = validatedContract.stemContract.stems.map { it.stemId }

    private val dsp: HtdemucsDspSession = if (Build.VERSION.SDK_INT > 0) {
        NativeHtdemucsDsp(
            sourceCount = validatedContract.stemContract.stems.size,
            windowSamples = validatedContract.pipelineContract.windowSamples,
            spectrumFrames = FRAME_COUNT,
            workerCount = validatedIstftWorkers,
        )
    } else {
        HtdemucsHostDsp(
            windowSamples = validatedContract.pipelineContract.windowSamples,
            istftMode = istftMode,
            istftWorkers = validatedIstftWorkers,
        )
    }

    val dspImplementationId: String
        get() = dsp.implementationId

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

    fun reconstructWindow(
        outputs: HtdemucsNeuralOutputs,
        shouldCancel: () -> Boolean = { false },
    ): HtdemucsWindowStemSet {
        val expectedWaveformElements = orderedStemIds.size * CHANNEL_COUNT * WINDOW_SAMPLES
        require(outputs.waveform.size == expectedWaveformElements) {
            "HTDemucs waveform branch has ${outputs.waveform.size} values; expected " +
                "$expectedWaveformElements."
        }
        val combined = dsp.reconstructBranches(
            packedFrequency = outputs.frequency,
            timeWaveform = outputs.waveform,
            stemCount = orderedStemIds.size,
            shouldCancel = shouldCancel,
        )
        return HtdemucsWindowStemSet(
            orderedStemIds = orderedStemIds,
            planarSamples = combined,
            samplesPerStem = WINDOW_SAMPLES,
        )
    }

    override fun close() = dsp.close()

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

        internal fun validateSupportedContract(contract: SourceSeparationMultiTensorContract) {
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

enum class HtdemucsIstftMode {
    Serial,
    ParallelLanes,
}

internal fun validateHtdemucsIstftConfig(mode: HtdemucsIstftMode, workers: Int): Int {
    require(workers in 1..4)
    require(mode != HtdemucsIstftMode.Serial || workers == 1) {
        "Serial HTDemucs iSTFT requires exactly one worker."
    }
    require(mode != HtdemucsIstftMode.ParallelLanes || workers >= 2) {
        "Parallel HTDemucs iSTFT requires at least two workers."
    }
    return workers
}

internal class HtdemucsHostDsp(
    private val windowSamples: Int,
    val istftMode: HtdemucsIstftMode = HtdemucsIstftMode.Serial,
    val istftWorkers: Int = 1,
) : HtdemucsDspSession {
    override val implementationId: String = "host-jtransforms-v1"
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
    private val validatedIstftWorkers = validateHtdemucsIstftConfig(istftMode, istftWorkers)
    private val inverseWorkspaces = when (istftMode) {
        HtdemucsIstftMode.Serial -> emptyArray()
        HtdemucsIstftMode.ParallelLanes ->
            Array(validatedIstftWorkers) { InverseWorkspace() }
    }
    private val inverseExecutor: ExecutorService? = when (istftMode) {
        HtdemucsIstftMode.Serial -> null
        HtdemucsIstftMode.ParallelLanes -> Executors.newFixedThreadPool(
            validatedIstftWorkers,
        ) { runnable ->
            Thread(
                runnable,
                "booming-htdemucs-istft-${ISTFT_THREAD_SEQUENCE.incrementAndGet()}",
            )
        }.also { executor ->
            (executor as ThreadPoolExecutor).prestartAllCoreThreads()
        }
    }
    private val closed = AtomicBoolean(false)

    init {
        require(windowSamples > 0 && outerPadRight >= 0)
    }

    @Synchronized
    override fun waveformToSpectrum(planarStereoWaveform: FloatArray): FloatArray {
        check(!closed.get()) { "HTDemucs host DSP is closed." }
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
    override fun frequencyToWaveform(
        packedFrequency: FloatArray,
        stemCount: Int,
        shouldCancel: () -> Boolean,
    ): FloatArray {
        check(!closed.get()) { "HTDemucs host DSP is closed." }
        require(stemCount > 0)
        val expected = stemCount * HtdemucsPipelineAdapter.FEATURE_COUNT *
            HtdemucsPipelineAdapter.FREQUENCY_BINS * frameCount
        require(packedFrequency.size == expected) {
            "Expected packed frequency tensor with $expected values."
        }
        val output = FloatArray(stemCount * HtdemucsPipelineAdapter.CHANNEL_COUNT * windowSamples)
        val executor = inverseExecutor
        if (executor == null) {
            processSerialInverse(packedFrequency, stemCount, output, shouldCancel)
        } else {
            processParallelInverse(packedFrequency, stemCount, output, shouldCancel, executor)
        }
        return output
    }

    private fun processSerialInverse(
        packedFrequency: FloatArray,
        stemCount: Int,
        output: FloatArray,
        shouldCancel: () -> Boolean,
    ) {
        val fftBuffer = FloatArray(HtdemucsPipelineAdapter.FFT_SIZE * 2)
        val overlap = FloatArray(overlapLength)
        repeat(stemCount) { stem ->
            repeat(HtdemucsPipelineAdapter.CHANNEL_COUNT) { channel ->
                throwIfCanceled(shouldCancel)
                overlap.fill(0f)
                val realFeature = channel * 2
                val imaginaryFeature = realFeature + 1
                repeat(frameCount) { frame ->
                    throwIfCanceled(shouldCancel)
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
    }

    private fun processParallelInverse(
        packedFrequency: FloatArray,
        stemCount: Int,
        output: FloatArray,
        shouldCancel: () -> Boolean,
        executor: ExecutorService,
    ) {
        val planeCount = stemCount * HtdemucsPipelineAdapter.CHANNEL_COUNT
        val activeWorkers = min(validatedIstftWorkers, planeCount)
        val cancellationRequested = AtomicBoolean(false)
        val tasks = (0 until activeWorkers).map { workerIndex ->
            Callable {
                processInversePlanes(
                    workerIndex = workerIndex,
                    workerCount = activeWorkers,
                    planeCount = planeCount,
                    packedFrequency = packedFrequency,
                    output = output,
                    cancellationRequested = cancellationRequested,
                    shouldCancel = shouldCancel,
                )
            }
        }
        try {
            executor.invokeAll(tasks).forEach { future ->
                try {
                    future.get()
                } catch (error: ExecutionException) {
                    cancellationRequested.set(true)
                    throw error.cause ?: error
                }
            }
        } catch (error: InterruptedException) {
            cancellationRequested.set(true)
            Thread.currentThread().interrupt()
            throw CancellationException("Interrupted while running HTDemucs iSTFT.").apply {
                initCause(error)
            }
        }
    }

    private fun processInversePlanes(
        workerIndex: Int,
        workerCount: Int,
        planeCount: Int,
        packedFrequency: FloatArray,
        output: FloatArray,
        cancellationRequested: AtomicBoolean,
        shouldCancel: () -> Boolean,
    ) {
        val workspace = inverseWorkspaces[workerIndex]
        var plane = workerIndex
        while (plane < planeCount) {
            throwIfCanceled(shouldCancel, cancellationRequested)
            val stem = plane / HtdemucsPipelineAdapter.CHANNEL_COUNT
            val channel = plane % HtdemucsPipelineAdapter.CHANNEL_COUNT
            workspace.overlap.fill(0f)
            val realFeature = channel * 2
            val imaginaryFeature = realFeature + 1
            repeat(frameCount) { frame ->
                throwIfCanceled(shouldCancel, cancellationRequested)
                workspace.fftBuffer.fill(0f)
                repeat(HtdemucsPipelineAdapter.FREQUENCY_BINS) { frequency ->
                    val real = packedFrequency[
                        frequencyIndex(stem, realFeature, frequency, frame)
                    ]
                    val imaginary = packedFrequency[
                        frequencyIndex(stem, imaginaryFeature, frequency, frame)
                    ]
                    setComplex(workspace.fftBuffer, frequency, real, imaginary)
                    if (frequency > 0) {
                        setComplex(
                            workspace.fftBuffer,
                            HtdemucsPipelineAdapter.FFT_SIZE - frequency,
                            real,
                            -imaginary,
                        )
                    }
                }
                workspace.fft.complexInverse(workspace.fftBuffer, true)
                val start = (frame + FRAME_PAD_LEFT) * HtdemucsPipelineAdapter.HOP_LENGTH
                repeat(HtdemucsPipelineAdapter.FFT_SIZE) { sample ->
                    workspace.overlap[start + sample] +=
                        workspace.fftBuffer[sample * 2] * inverseScale * hann[sample]
                }
            }
            val outputOffset = plane * windowSamples
            repeat(windowSamples) { sample ->
                val overlapIndex = CENTER_TRIM + OUTER_PAD_LEFT + sample
                val divisor = windowSquareSum[overlapIndex]
                check(divisor > 0f)
                output[outputOffset + sample] = workspace.overlap[overlapIndex] / divisor
            }
            plane += workerCount
        }
    }

    override fun reconstructBranches(
        packedFrequency: FloatArray,
        timeWaveform: FloatArray,
        stemCount: Int,
        shouldCancel: () -> Boolean,
    ): FloatArray {
        val expectedWaveformElements = stemCount * HtdemucsPipelineAdapter.CHANNEL_COUNT *
            windowSamples
        require(timeWaveform.size == expectedWaveformElements) {
            "Expected waveform branch with $expectedWaveformElements values."
        }
        val frequencyWaveform = frequencyToWaveform(packedFrequency, stemCount, shouldCancel)
        throwIfCanceled(shouldCancel)
        return FloatArray(expectedWaveformElements) { index ->
            frequencyWaveform[index] + timeWaveform[index]
        }
    }

    @Synchronized
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val executor = inverseExecutor ?: return
        executor.shutdown()
        if (!awaitTerminationPreservingInterrupt(executor)) {
            executor.shutdownNow()
            check(awaitTerminationPreservingInterrupt(executor)) {
                "HTDemucs iSTFT executor did not terminate."
            }
        }
    }

    private fun awaitTerminationPreservingInterrupt(executor: ExecutorService): Boolean {
        var interrupted = Thread.interrupted()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(EXECUTOR_CLOSE_TIMEOUT_SECONDS)
        try {
            while (true) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) return executor.isTerminated
                try {
                    return executor.awaitTermination(remaining, TimeUnit.NANOSECONDS)
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun throwIfCanceled(
        shouldCancel: () -> Boolean,
        sharedCancellation: AtomicBoolean? = null,
    ) {
        if (sharedCancellation?.get() == true || Thread.currentThread().isInterrupted ||
            shouldCancel()
        ) {
            sharedCancellation?.set(true)
            throw CancellationException("HTDemucs host reconstruction was canceled.")
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

    private inner class InverseWorkspace {
        val fft = FloatFFT_1D(HtdemucsPipelineAdapter.FFT_SIZE.toLong())
        val fftBuffer = FloatArray(HtdemucsPipelineAdapter.FFT_SIZE * 2)
        val overlap = FloatArray(overlapLength)
    }

    private companion object {
        const val OUTER_PAD_LEFT = 1_536
        const val CENTER_TRIM = HtdemucsPipelineAdapter.FFT_SIZE / 2
        const val FRAME_PAD_LEFT = 2
        const val FRAME_PAD_RIGHT = 2
        const val EXECUTOR_CLOSE_TIMEOUT_SECONDS = 5L
        val ISTFT_THREAD_SEQUENCE = AtomicInteger()

    }
}
