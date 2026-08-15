package com.mardous.booming.separation.model

import android.util.Log

internal interface MdxWindowDsp : AutoCloseable {
    val implementationId: String

    /** The returned tensor remains valid until the next STFT call or close. */
    fun waveformToNchwTensor(waveform: Array<FloatArray>): FloatArray

    /** The returned waveform remains valid until the next iSTFT call or close. */
    fun nchwTensorToWaveform(tensor: FloatArray): Array<FloatArray>
}

internal object MdxWindowDspFactory {
    fun create(config: MdxDspConfig): MdxWindowDsp = ProductMdxWindowDsp(config)
}

private class ProductMdxWindowDsp(
    private val config: MdxDspConfig,
) : MdxWindowDsp {
    private var delegate: MdxWindowDsp = createPreferred(config)

    override val implementationId: String
        get() = delegate.implementationId

    override fun waveformToNchwTensor(waveform: Array<FloatArray>): FloatArray =
        runWithFallback { it.waveformToNchwTensor(waveform) }

    override fun nchwTensorToWaveform(tensor: FloatArray): Array<FloatArray> =
        runWithFallback { it.nchwTensorToWaveform(tensor) }

    override fun close() {
        delegate.close()
    }

    private inline fun <T> runWithFallback(operation: (MdxWindowDsp) -> T): T {
        val active = delegate
        try {
            return operation(active)
        } catch (error: MdxNativeDspException) {
            if (active !is NativePackedMdxWindowDsp) throw error
            runCatching { active.close() }
            Log.w(TAG, "Native packed-real MDX DSP failed; using Kotlin DSP.", error)
            delegate = KotlinMdxWindowDsp(config)
            return operation(delegate)
        }
    }

    private companion object {
        const val TAG = "MdxWindowDsp"

        fun createPreferred(config: MdxDspConfig): MdxWindowDsp = try {
            NativePackedMdxWindowDsp(config)
        } catch (error: LinkageError) {
            Log.w(TAG, "Native packed-real MDX DSP is unavailable; using Kotlin DSP.", error)
            KotlinMdxWindowDsp(config)
        } catch (error: MdxNativeDspException) {
            Log.w(TAG, "Native packed-real MDX DSP could not initialize; using Kotlin DSP.", error)
            KotlinMdxWindowDsp(config)
        }
    }
}

private class NativePackedMdxWindowDsp(
    private val config: MdxDspConfig,
) : MdxWindowDsp {
    private val nativeDsp = NativeMdxDsp(config)
    private val inputNhwc = FloatArray(config.tensorElementCount)
    private val inputNchw = FloatArray(config.tensorElementCount)
    private val outputNhwc = FloatArray(config.tensorElementCount)
    private val outputWaveform = Array(MdxDspConfig.STEREO_CHANNELS) {
        FloatArray(config.chunkSize)
    }

    override val implementationId: String = NATIVE_IMPLEMENTATION_ID

    override fun waveformToNchwTensor(waveform: Array<FloatArray>): FloatArray {
        requireFiniteWaveform(waveform, "MDX input")
        nativeDsp.waveformToNhwcTensorInto(waveform, inputNhwc)
        requireFiniteTensor(inputNhwc, "Native MDX STFT output")
        MdxTensorLayoutConverter.nhwcToNchw(
            source = inputNhwc,
            destination = inputNchw,
            batch = 1,
            channels = MdxDspConfig.STEM_COMPLEX_CHANNELS,
            height = config.dimF,
            width = config.dimT,
        )
        return inputNchw
    }

    override fun nchwTensorToWaveform(tensor: FloatArray): Array<FloatArray> {
        require(tensor.size == config.tensorElementCount) {
            "Expected tensor size ${config.tensorElementCount}, got ${tensor.size}."
        }
        requireFiniteTensor(tensor, "MDX model output")
        MdxTensorLayoutConverter.nchwToNhwc(
            source = tensor,
            destination = outputNhwc,
            batch = 1,
            channels = MdxDspConfig.STEM_COMPLEX_CHANNELS,
            height = config.dimF,
            width = config.dimT,
        )
        nativeDsp.nhwcTensorToWaveformInto(outputNhwc, outputWaveform)
        requireFiniteWaveform(outputWaveform, "Native MDX iSTFT output")
        return outputWaveform
    }

    override fun close() {
        nativeDsp.close()
    }

    companion object {
        const val NATIVE_IMPLEMENTATION_ID = "native-pocketfft-packed-real-v1"
    }
}

private class KotlinMdxWindowDsp(
    config: MdxDspConfig,
) : MdxWindowDsp {
    private val spectrogram = MdxSpectrogram(config)

    override val implementationId: String = "kotlin-jtransforms-v1"

    override fun waveformToNchwTensor(waveform: Array<FloatArray>): FloatArray =
        spectrogram.waveformToTensor(waveform)

    override fun nchwTensorToWaveform(tensor: FloatArray): Array<FloatArray> =
        spectrogram.tensorToWaveform(tensor)

    override fun close() = Unit
}

private fun requireFiniteTensor(values: FloatArray, label: String) {
    val nonFiniteIndex = values.indexOfFirst { !it.isFinite() }
    require(nonFiniteIndex < 0) { "$label contains a non-finite value at index $nonFiniteIndex." }
}

private fun requireFiniteWaveform(waveform: Array<FloatArray>, label: String) {
    require(waveform.size == MdxDspConfig.STEREO_CHANNELS) { "$label must be stereo." }
    waveform.forEachIndexed { channel, samples ->
        val nonFiniteIndex = samples.indexOfFirst { !it.isFinite() }
        require(nonFiniteIndex < 0) {
            "$label channel $channel contains a non-finite value at index $nonFiniteIndex."
        }
    }
}
