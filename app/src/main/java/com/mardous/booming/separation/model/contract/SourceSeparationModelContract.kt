package com.mardous.booming.separation.model.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SourceSeparationModelContract(
    val contractSchemaVersion: Int,
    val contractId: String,
    val modelId: String,
    val displayName: String,
    val artifact: ContractArtifact,
    val source: ContractSource,
    val conversion: ContractConversion,
    val tensorContract: TensorContract,
    val dsp: ContractDsp,
    val stemContract: StemContract,
    val pipelineCompatibility: PipelineCompatibility,
    val runtimeCompatibility: RuntimeCompatibility,
)

@Serializable
data class SourceSeparationCustomModelProfile(
    val profileSchemaVersion: Int,
    val profileId: String,
    val modelId: String,
    val displayName: String,
    val artifact: ContractArtifact,
    val tensorContract: TensorContract,
    val dsp: ContractDsp,
    val stemContract: StemContract,
    val pipelineCompatibility: PipelineCompatibility,
    val qualityUnverified: Boolean,
)

@Serializable
data class ContractArtifact(
    val fileName: String,
    val byteSize: Long,
    val sha256: String,
    val format: ContractArtifactFormat,
)

@Serializable
enum class ContractArtifactFormat {
    @SerialName("tflite-flatbuffer")
    TfliteFlatbuffer,
}

@Serializable
data class ContractSource(
    val canonicalSourceId: String,
    val fileName: String,
    val url: String,
    val byteSize: Long,
    val sha256: String,
    val attribution: List<String>,
)

@Serializable
data class ContractConversion(
    val repository: String,
    val revision: String,
    val pipelineVersion: Int,
    val toolVersions: Map<String, String>,
)

@Serializable
data class TensorContract(
    val input: ContractTensor,
    val output: ContractTensor,
    val batchSize: Int,
    val complexChannelCount: Int,
)

@Serializable
data class ContractTensor(
    val name: String,
    val dtype: ContractDtype,
    val layout: ContractTensorLayout,
    val shape: List<Int>,
)

@Serializable
enum class ContractDtype {
    @SerialName("float32")
    Float32,
}

@Serializable
enum class ContractTensorLayout {
    @SerialName("NHWC")
    Nhwc,
}

@Serializable
data class ContractDsp(
    val sampleRate: Int,
    val channelCount: Int,
    val nFft: Int,
    val hopLength: Int,
    val dimF: Int,
    val dimTPower: Int,
    val modelTimeFrames: Int,
    val window: ContractWindow,
    val modelOutputScale: Double,
)

@Serializable
enum class ContractWindow {
    @SerialName("periodic-hann")
    PeriodicHann,
}

@Serializable
data class StemContract(
    val modelOutput: ContractStem,
    val residual: ContractStem,
    val residualRule: ContractResidualRule,
)

@Serializable
data class ContractStem(
    val semantic: ContractStemSemantic,
    val displayLabel: String,
)

@Serializable
enum class ContractStemSemantic {
    @SerialName("vocals")
    Vocals,

    @SerialName("instrumental")
    Instrumental,

    @SerialName("bass")
    Bass,

    @SerialName("drums")
    Drums,

    @SerialName("other")
    Other,

    @SerialName("reverb")
    Reverb,

    @SerialName("no_crowd")
    NoCrowd,

    @SerialName("target_stem")
    TargetStem,

    @SerialName("remaining_audio")
    RemainingAudio,
}

@Serializable
enum class ContractResidualRule {
    @SerialName("mixture-minus-scaled-model-output")
    MixtureMinusScaledModelOutput,
}

@Serializable
data class PipelineCompatibility(
    val pipelineId: String,
    val minimumVersion: Int,
    val maximumVersion: Int,
)

@Serializable
data class RuntimeCompatibility(
    val minimumAndroidApi: Int,
    val statuses: List<RuntimeCompatibilityStatus>,
)

@Serializable
data class RuntimeCompatibilityStatus(
    val abi: ContractAbi,
    val backend: ContractBackend,
    val status: ContractRuntimeStatus,
    val evidence: String,
)

@Serializable
enum class ContractAbi {
    @SerialName("arm64-v8a")
    Arm64V8a,

    @SerialName("armeabi-v7a")
    ArmeabiV7a,

    @SerialName("x86_64")
    X86_64,

    @SerialName("x86")
    X86,
}

@Serializable
enum class ContractBackend {
    @SerialName("cpu")
    Cpu,

    @SerialName("gpu")
    Gpu,
}

@Serializable
enum class ContractRuntimeStatus {
    @SerialName("known-good")
    KnownGood,

    @SerialName("untested")
    Untested,

    @SerialName("unsupported")
    Unsupported,
}
