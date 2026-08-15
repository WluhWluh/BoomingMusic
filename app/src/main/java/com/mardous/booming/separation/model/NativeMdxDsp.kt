package com.mardous.booming.separation.model

internal class NativeMdxDsp(
    private val config: MdxDspConfig,
) : AutoCloseable {
    private var handle = nativeCreate(
        config.nFft,
        config.hopLength,
        config.dimF,
        config.dimT,
        config.chunkSize,
    ).also {
        if (it == 0L) {
            throw MdxNativeDspException("Native packed-real MDX DSP plan creation failed.")
        }
    }

    fun waveformToNhwcTensorInto(waveform: Array<FloatArray>, tensor: FloatArray) {
        check(handle != 0L) { "Native packed-real MDX DSP plan is closed." }
        require(waveform.size == MdxDspConfig.STEREO_CHANNELS) {
            "Expected stereo waveform."
        }
        waveform.forEachIndexed { channel, samples ->
            require(samples.size == config.chunkSize) {
                "Expected channel $channel to contain ${config.chunkSize} samples, got ${samples.size}."
            }
        }
        require(tensor.size == config.tensorElementCount) {
            "Expected tensor size ${config.tensorElementCount}, got ${tensor.size}."
        }
        if (!nativePreprocess(handle, waveform[0], waveform[1], tensor)) {
            throw MdxNativeDspException("Native packed-real MDX STFT failed.")
        }
    }

    fun nhwcTensorToWaveformInto(tensor: FloatArray, waveform: Array<FloatArray>) {
        check(handle != 0L) { "Native packed-real MDX DSP plan is closed." }
        require(tensor.size == config.tensorElementCount) {
            "Expected tensor size ${config.tensorElementCount}, got ${tensor.size}."
        }
        require(waveform.size == MdxDspConfig.STEREO_CHANNELS) {
            "Expected stereo waveform."
        }
        waveform.forEachIndexed { channel, samples ->
            require(samples.size == config.chunkSize) {
                "Expected channel $channel to contain ${config.chunkSize} samples, got ${samples.size}."
            }
        }
        if (!nativePostprocess(handle, tensor, waveform[0], waveform[1])) {
            throw MdxNativeDspException("Native packed-real MDX iSTFT failed.")
        }
    }

    override fun close() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    private external fun nativeCreate(
        nFft: Int,
        hopLength: Int,
        dimF: Int,
        dimT: Int,
        chunkSize: Int,
    ): Long

    private external fun nativePreprocess(
        handle: Long,
        left: FloatArray,
        right: FloatArray,
        tensorNhwc: FloatArray,
    ): Boolean

    private external fun nativePostprocess(
        handle: Long,
        tensorNhwc: FloatArray,
        left: FloatArray,
        right: FloatArray,
    ): Boolean

    private external fun nativeDestroy(handle: Long)

    private companion object {
        init {
            System.loadLibrary("booming_ss_separation")
        }
    }
}

internal class MdxNativeDspException(message: String) : IllegalStateException(message)
