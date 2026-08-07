package com.mardous.booming.separation.model.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SourceSeparationMultiTensorExecutableContract(
    val executableSchemaVersion: Int,
    val executableKind: String,
    val modelContract: SourceSeparationMultiTensorContract,
    val artifact: MultiTensorExecutableArtifact,
    val flatBuffer: MultiTensorFlatBufferIdentity,
    val provenance: MultiTensorExecutableProvenance,
    val conversion: MultiTensorConversionIdentity,
    val fixtures: List<MultiTensorFixtureIdentity>,
    val allowedBackends: List<MultiTensorExecutableBackend>,
    val notices: List<MultiTensorLicenseNotice>,
)

@Serializable
data class MultiTensorExecutableArtifact(
    val fileName: String,
    val byteSize: Long,
    val sha256: String,
    val format: MultiTensorExecutableArtifactFormat,
)

@Serializable
enum class MultiTensorExecutableArtifactFormat {
    @SerialName("tflite-flatbuffer")
    TfliteFlatbuffer,
}

@Serializable
enum class MultiTensorExecutableBackend {
    @SerialName("cpu")
    Cpu,
}

@Serializable
data class MultiTensorFlatBufferIdentity(
    val schemaVersion: Int,
    val minimumRuntimeVersion: String,
    val customOperatorCount: Int,
    val signatureKey: String,
    val inputs: List<MultiTensorFlatBufferBinding>,
    val outputs: List<MultiTensorFlatBufferBinding>,
)

@Serializable
data class MultiTensorFlatBufferBinding(
    val index: Int,
    val logicalName: String,
    val tensorName: String,
    val tensorIndex: Int,
    val dtype: MultiTensorDtype,
    val shape: List<Int>,
    val axes: List<String>,
)

@Serializable
data class MultiTensorExecutableProvenance(
    val sources: List<MultiTensorPinnedFile>,
    val loaderRevision: String,
    val neuralCoreReferenceRevision: String,
    val exportScript: MultiTensorPinnedFile,
    val requirementsLock: MultiTensorPinnedFile,
)

@Serializable
data class MultiTensorPinnedFile(
    val role: String,
    val fileName: String,
    val byteSize: Long,
    val sha256: String,
    val repository: String? = null,
    val revision: String? = null,
)

@Serializable
data class MultiTensorConversionIdentity(
    val exportReport: MultiTensorPinnedFile,
    val toolVersions: Map<String, String>,
    val inputKind: String,
    val onnxRole: String,
    val strictExport: Boolean,
    val deterministicPositionalEmbedding: Boolean,
    val lightweightConversion: Boolean,
    val runtimeConstantFolding: Boolean,
    val enableX64: Boolean,
)

@Serializable
data class MultiTensorFixtureIdentity(
    val role: MultiTensorFixtureRole,
    val fileName: String,
    val byteSize: Long,
    val sha256: String,
    val dtype: MultiTensorFixtureDtype,
    val shape: List<Int>,
)

@Serializable
enum class MultiTensorFixtureRole {
    @SerialName("waveform-input")
    WaveformInput,

    @SerialName("spectrum-input")
    SpectrumInput,

    @SerialName("frequency-golden")
    FrequencyGolden,

    @SerialName("waveform-golden")
    WaveformGolden,

    @SerialName("frequency-waveform-golden")
    FrequencyWaveformGolden,

    @SerialName("combined-golden")
    CombinedGolden,

    @SerialName("ola-mix-input")
    OlaMixInput,

    @SerialName("ola-combined-golden")
    OlaCombinedGolden,
}

@Serializable
enum class MultiTensorFixtureDtype {
    @SerialName("float32-le")
    Float32Le,
}

@Serializable
data class MultiTensorLicenseNotice(
    val noticeId: String,
    val subject: String,
    val licenseId: String,
    val sourceUrl: String,
    val sourceRevision: String? = null,
    val statement: String,
)
