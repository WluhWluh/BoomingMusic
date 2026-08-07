package com.mardous.booming.separation.model.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Contract for a static neural core with named multi-input and multi-output tensors. */
@Serializable
data class SourceSeparationMultiTensorContract(
    val contractSchemaVersion: Int,
    val contractKind: String,
    val contractId: String,
    val modelId: String,
    val displayName: String,
    val tensorContract: MultiTensorContract,
    val stemContract: MultiTensorStemContract,
    val pipelineContract: MultiTensorPipelineContract,
)

@Serializable
data class MultiTensorContract(
    val inputs: List<MultiTensorDescriptor>,
    val outputs: List<MultiTensorDescriptor>,
    val outputBindings: List<MultiTensorOutputBinding>,
)

@Serializable
data class MultiTensorDescriptor(
    val index: Int,
    val name: String,
    val dtype: MultiTensorDtype,
    val shape: List<Int>,
    val axes: List<String>,
)

@Serializable
enum class MultiTensorDtype {
    @SerialName("float32")
    Float32,
}

@Serializable
data class MultiTensorOutputBinding(
    val tensorIndex: Int,
    val tensorName: String,
    val stemAxis: Int,
    val packing: MultiTensorOutputPacking,
    val stemIds: List<String>,
)

@Serializable
enum class MultiTensorOutputPacking {
    @SerialName("stem-axis")
    StemAxis,
}

@Serializable
data class MultiTensorStemContract(
    val stems: List<MultiTensorStemDescriptor>,
)

@Serializable
data class MultiTensorStemDescriptor(
    val stemId: String,
    val semanticId: String,
    val canonicalLabel: String,
    val order: Int,
)

@Serializable
data class MultiTensorPipelineContract(
    val pipelineId: String,
    val pipelineVersion: Int,
    val sampleRate: Int,
    val channelCount: Int,
    val windowSamples: Int,
    val fftSize: Int,
    val hopLength: Int,
    val renderMode: MultiTensorRenderMode,
)

@Serializable
enum class MultiTensorRenderMode {
    @SerialName("neural-core-waveform-frequency-ola")
    NeuralCoreWaveformFrequencyOla,
}
