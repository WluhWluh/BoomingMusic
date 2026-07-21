package com.mardous.booming.separation.model.contract

import com.mardous.booming.separation.model.MdxDspConfig
import com.mardous.booming.separation.model.MdxExecutionProfile
import com.mardous.booming.separation.model.MdxInferenceBackend
import com.mardous.booming.separation.model.MdxModelFormat
import com.mardous.booming.separation.model.MdxRuntimeAbi
import com.mardous.booming.separation.model.MdxRuntimeCompatibilityRecord
import com.mardous.booming.separation.model.MdxRuntimeSupportStatus
import com.mardous.booming.separation.model.MdxStem
import com.mardous.booming.separation.model.MdxTensorDataType
import com.mardous.booming.separation.model.MdxTensorLayout
import com.mardous.booming.separation.model.MdxTensorSpec

fun SourceSeparationModelContract.toMdxExecutionProfile(): MdxExecutionProfile {
    val validated = SourceSeparationModelContractValidator.validateContract(this)
    val dspConfig = MdxDspConfig(
        sampleRate = validated.dsp.sampleRate,
        nFft = validated.dsp.nFft,
        hopLength = validated.dsp.hopLength,
        dimF = validated.dsp.dimF,
        dimTPower = validated.dsp.dimTPower,
    )
    return MdxExecutionProfile(
        profileId = validated.contractId,
        displayName = validated.displayName,
        outputTag = validated.modelId,
        modelFormat = MdxModelFormat.Tflite,
        inputTensor = validated.tensorContract.input.toMdxTensorSpec(),
        outputTensor = validated.tensorContract.output.toMdxTensorSpec(),
        dspConfig = dspConfig,
        modelOutputScale = validated.dsp.modelOutputScale.toFloat(),
        modelOutputStem = validated.stemContract.modelOutput.semantic.toMdxStem(),
        pipelineId = validated.pipelineCompatibility.pipelineId,
        pipelineVersion = SourceSeparationModelContractValidator.PIPELINE_VERSION,
        expectedFileName = validated.artifact.fileName,
        expectedByteSize = validated.artifact.byteSize,
        expectedSha256 = validated.artifact.sha256,
        minimumAndroidApi = validated.runtimeCompatibility.minimumAndroidApi,
        runtimeCompatibility = validated.runtimeCompatibility.statuses.map { status ->
            MdxRuntimeCompatibilityRecord(
                abi = status.abi.toMdxRuntimeAbi(),
                backend = status.backend.toMdxInferenceBackend(),
                status = status.status.toMdxRuntimeSupportStatus(),
                evidence = status.evidence,
            )
        },
    )
}

private fun ContractTensor.toMdxTensorSpec() = MdxTensorSpec(
    name = name,
    shape = shape,
    layout = when (layout) {
        ContractTensorLayout.Nhwc -> MdxTensorLayout.Nhwc
    },
    dataType = when (dtype) {
        ContractDtype.Float32 -> MdxTensorDataType.Float32
    },
)

private fun ContractStemSemantic.toMdxStem() = when (this) {
    ContractStemSemantic.Vocals -> MdxStem.VOCALS
    ContractStemSemantic.Instrumental -> MdxStem.INSTRUMENTAL
    else -> throw SourceSeparationModelContractException(
        "The current execution profile supports only vocals/instrumental stem semantics"
    )
}

private fun ContractAbi.toMdxRuntimeAbi() = when (this) {
    ContractAbi.Arm64V8a -> MdxRuntimeAbi.Arm64V8a
    ContractAbi.ArmeabiV7a -> MdxRuntimeAbi.ArmeabiV7a
    ContractAbi.X86_64 -> MdxRuntimeAbi.X86_64
    ContractAbi.X86 -> MdxRuntimeAbi.X86
}

private fun ContractBackend.toMdxInferenceBackend() = when (this) {
    ContractBackend.Cpu -> MdxInferenceBackend.LiteRtCpu
    ContractBackend.Gpu -> MdxInferenceBackend.LiteRtGpu
}

private fun ContractRuntimeStatus.toMdxRuntimeSupportStatus() = when (this) {
    ContractRuntimeStatus.KnownGood -> MdxRuntimeSupportStatus.KnownGood
    ContractRuntimeStatus.Untested -> MdxRuntimeSupportStatus.Untested
    ContractRuntimeStatus.Unsupported -> MdxRuntimeSupportStatus.Unsupported
}
