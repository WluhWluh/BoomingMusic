package com.mardous.booming.separation.model

data class MdxDspConfig(
    val sampleRate: Int = 44_100,
    val nFft: Int = 6_144,
    val hopLength: Int = 1_024,
    val dimF: Int = 2_048,
    val dimTPower: Int = 8,
) {
    val dimT: Int = 1 shl dimTPower
    val nBins: Int = nFft / 2 + 1
    val chunkSize: Int = hopLength * (dimT - 1)
    val trim: Int = nFft / 2
    val generationSize: Int = chunkSize - 2 * trim
    val tensorElementCount: Int = STEM_COMPLEX_CHANNELS * dimF * dimT

    init {
        require(sampleRate > 0) { "Sample rate must be positive." }
        require(nFft > 0 && nFft % 2 == 0) { "FFT size must be a positive even number." }
        require(hopLength > 0) { "Hop length must be positive." }
        require(dimF in 1..nBins) { "Frequency bins must be between 1 and $nBins." }
        require(dimTPower > 0) { "Time dimension power must be positive." }
        require(generationSize > 0) { "Generation size must be positive." }
    }

    companion object {
        const val STEREO_CHANNELS = 2
        const val STEM_COMPLEX_CHANNELS = 4
    }
}
