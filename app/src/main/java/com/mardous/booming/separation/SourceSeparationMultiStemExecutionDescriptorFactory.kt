package com.mardous.booming.separation

import com.mardous.booming.separation.cache.v2.SourceSeparationCacheContractSnapshot
import com.mardous.booming.separation.model.contract.SourceSeparationMultiTensorExecutableContractLoader
import com.mardous.booming.separation.process.SourceSeparationMultiStemExecutionDescriptor
import com.mardous.booming.separation.process.SourceSeparationMultiStemExecutionModelIdentity
import com.mardous.booming.separation.process.SourceSeparationMultiStemExecutionRuntime
import com.mardous.booming.separation.process.SourceSeparationMultiStemExecutionSourceIdentity
import java.util.UUID

internal fun SourceSeparationMultiStemExecutionRequest.toExecutionDescriptor(
    processGeneration: Long,
    runId: String = "multistem-${UUID.randomUUID()}",
): SourceSeparationMultiStemExecutionDescriptor {
    require(processGeneration > 0L)
    val serialized = installedModel.sidecarFile.bufferedReader().use { it.readText() }
    val executable = SourceSeparationMultiTensorExecutableContractLoader.load(serialized)
    val contract = SourceSeparationCacheContractSnapshot.fromMultiTensor(executable)
    require(installedModel.modelId == contract.modelId &&
        installedModel.contractId == contract.contractId &&
        installedModel.modelFile.name == contract.artifactFileName &&
        installedModel.modelByteSize == contract.artifactByteSize &&
        installedModel.modelSha256.equals(contract.artifactSha256, ignoreCase = true)
    ) { "Installed multi-stem model does not match its sidecar contract." }
    val identity = contract.identity(
        source = preflight.identity,
        renderProfileId = HtdemucsSourceSeparationEngine.HTDEMUCS_CPU_PROFILE_ID,
    )
    return SourceSeparationMultiStemExecutionDescriptor(
        runId = runId,
        processGeneration = processGeneration,
        cacheKey = identity.cacheKey,
        cacheIdentity = identity,
        contract = contract,
        model = SourceSeparationMultiStemExecutionModelIdentity(
            modelId = contract.modelId,
            artifactFileName = contract.artifactFileName,
            artifactByteSize = contract.artifactByteSize,
            artifactSha256 = contract.artifactSha256,
            contractId = contract.contractId,
            pipelineId = contract.pipelineId,
            pipelineVersion = contract.pipelineVersion,
        ),
        source = SourceSeparationMultiStemExecutionSourceIdentity(
            sourceUri = input.sourceUri,
            displayName = input.displayName,
            source = preflight.identity,
            diagnostics = input.sourceDiagnostics,
        ),
        song = input.song,
        runtime = SourceSeparationMultiStemExecutionRuntime(
            executionProfileId = HtdemucsSourceSeparationEngine.HTDEMUCS_CPU_PROFILE_ID,
            runClass = runClass,
            backgroundPolicy = runClass.backgroundPolicy,
            windowDecodeEnabled = windowDecodeEnabled,
        ),
    )
}
