package com.mardous.booming.separation.model

internal data class MdxMappedStemWaveforms(
    val vocals: Array<FloatArray>,
    val instrumental: Array<FloatArray>,
)

internal fun mapMdxStemWaveforms(
    mixture: Array<FloatArray>,
    rawModelOutput: Array<FloatArray>,
    modelOutputScale: Float,
    modelOutputStem: MdxStem,
): MdxMappedStemWaveforms {
    val scaledModelOutput = compensateMdxModelOutput(rawModelOutput, modelOutputScale)
    val residual = reconstructMdxResidual(mixture, scaledModelOutput)
    return mapMdxStemWaveformsFromComponents(scaledModelOutput, residual, modelOutputStem)
}

internal fun compensateMdxModelOutput(
    rawModelOutput: Array<FloatArray>,
    modelOutputScale: Float,
): Array<FloatArray> {
    require(modelOutputScale.isFinite() && modelOutputScale > 0f) {
        "Model output scale must be finite and positive."
    }
    require(rawModelOutput.size == MdxDspConfig.STEREO_CHANNELS) {
        "Model output must be stereo."
    }
    val frameCount = rawModelOutput.first().size
    require(rawModelOutput.all { it.size == frameCount }) {
        "Model output channels have different lengths."
    }
    if (modelOutputScale == 1f) return rawModelOutput
    return Array(MdxDspConfig.STEREO_CHANNELS) { channel ->
        FloatArray(frameCount) { frame ->
            rawModelOutput[channel][frame] * modelOutputScale
        }
    }
}

internal fun reconstructMdxResidual(
    mixture: Array<FloatArray>,
    scaledModelOutput: Array<FloatArray>,
): Array<FloatArray> {
    require(mixture.size == MdxDspConfig.STEREO_CHANNELS) { "Mixture must be stereo." }
    require(scaledModelOutput.size == MdxDspConfig.STEREO_CHANNELS) {
        "Scaled model output must be stereo."
    }
    val frameCount = mixture.first().size
    require(mixture.all { it.size == frameCount }) { "Mixture channels have different lengths." }
    require(scaledModelOutput.all { it.size == frameCount }) {
        "Scaled model output channels do not match the mixture."
    }
    return Array(MdxDspConfig.STEREO_CHANNELS) { channel ->
        FloatArray(frameCount) { frame ->
            mixture[channel][frame] - scaledModelOutput[channel][frame]
        }
    }
}

internal fun mapMdxStemWaveformsFromComponents(
    scaledModelOutput: Array<FloatArray>,
    residual: Array<FloatArray>,
    modelOutputStem: MdxStem,
): MdxMappedStemWaveforms {
    return when (modelOutputStem) {
        MdxStem.VOCALS -> MdxMappedStemWaveforms(
            vocals = scaledModelOutput,
            instrumental = residual,
        )

        MdxStem.INSTRUMENTAL -> MdxMappedStemWaveforms(
            vocals = residual,
            instrumental = scaledModelOutput,
        )
    }
}
