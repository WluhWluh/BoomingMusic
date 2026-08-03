package com.mardous.booming.separation.process

import com.mardous.booming.separation.cache.v2.SourceSeparationAdmittedGpuRuntimeIdentity
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheContractSnapshot
import com.mardous.booming.separation.cache.v2.SourceSeparationResolvedCacheModel
import com.mardous.booming.separation.model.MdxExecutionProfile
import com.mardous.booming.separation.model.MdxInferenceBackend
import com.mardous.booming.separation.model.MdxInferenceSessionFactory
import com.mardous.booming.separation.model.MdxModelArtifact
import com.mardous.booming.separation.model.MdxRuntimeSettings
import java.security.MessageDigest
import java.util.Locale

internal data class SourceSeparationExecutionSessionIdentity(
    val model: SourceSeparationExecutionModelIdentity,
    val backendPolicy: SourceSeparationExecutionBackendPolicy,
    val gpuRuntimeIdentity: SourceSeparationAdmittedGpuRuntimeIdentity?,
    val cpuThreads: Int,
    val useXnnpack: Boolean,
) {
    val diagnosticKey: String = sha256(buildString {
        append(model.modelId)
        append('|').append(model.artifactFileName)
        append('|').append(model.artifactByteSize)
        append('|').append(model.artifactSha256.lowercase(Locale.US))
        append('|').append(model.contractId)
        append('|').append(model.contractSchemaVersion)
        append('|').append(model.contractFingerprint.lowercase(Locale.US))
        append('|').append(model.profileRevisionId)
        append('|').append(model.executionProfileId)
        append('|').append(model.executionSessionIdentity)
        append('|').append(model.pipelineId)
        append('|').append(model.pipelineVersion)
        append('|').append(backendPolicy.name)
        append('|').append(cpuThreads)
        append('|').append(useXnnpack)
        gpuRuntimeIdentity?.let { runtime ->
            append('|').append(runtime.profileId)
            append('|').append(runtime.artifactVersion)
            append('|').append(runtime.capabilitySchemaVersion)
            append('|').append(runtime.backend)
            append('|').append(runtime.precision)
            append('|').append(runtime.kernelBatchSize)
            append('|').append(runtime.commandQueueWindowSize)
        }
    })

    init {
        require(cpuThreads > 0) { "Execution session CPU thread count is invalid." }
        require((backendPolicy == SourceSeparationExecutionBackendPolicy.Auto) ==
            (gpuRuntimeIdentity != null)
        ) {
            "Execution session backend and GPU runtime identity disagree."
        }
    }

    fun requireMatches(
        factory: MdxInferenceSessionFactory,
        artifact: MdxModelArtifact,
        profile: MdxExecutionProfile,
        runtimeSettings: MdxRuntimeSettings,
    ) {
        val expectedFactoryBackend = when (backendPolicy) {
            SourceSeparationExecutionBackendPolicy.Auto -> MdxInferenceBackend.LiteRtAuto
            SourceSeparationExecutionBackendPolicy.Cpu -> MdxInferenceBackend.LiteRtCpu
        }
        require(factory.backend == expectedFactoryBackend) {
            "Execution session backend does not match its selected factory."
        }
        require(model.artifactFileName == artifact.file.name &&
            model.artifactByteSize == artifact.byteSize &&
            model.artifactSha256.equals(artifact.sha256, ignoreCase = true)
        ) {
            "Execution session model artifact does not match its admitted identity."
        }
        require(model.executionProfileId == profile.profileId &&
            model.executionSessionIdentity == profile.sessionIdentity
        ) {
            "Execution session profile does not match its admitted identity."
        }
        require(cpuThreads == runtimeSettings.cpuThreads &&
            useXnnpack == runtimeSettings.useXnnpack
        ) {
            "Execution session settings do not match their admitted identity."
        }
    }

    companion object {
        fun from(
            model: SourceSeparationResolvedCacheModel,
            backendPolicy: SourceSeparationExecutionBackendPolicy,
            gpuRuntimeIdentity: SourceSeparationAdmittedGpuRuntimeIdentity?,
            runtimeSettings: MdxRuntimeSettings,
        ) = SourceSeparationExecutionSessionIdentity(
            model = model.toExecutionModelIdentity(),
            backendPolicy = backendPolicy,
            gpuRuntimeIdentity = gpuRuntimeIdentity.takeIf {
                backendPolicy == SourceSeparationExecutionBackendPolicy.Auto
            },
            cpuThreads = runtimeSettings.cpuThreads,
            useXnnpack = runtimeSettings.useXnnpack,
        )

        fun from(
            descriptor: SourceSeparationExecutionDescriptor,
        ) = SourceSeparationExecutionSessionIdentity(
            model = descriptor.model,
            backendPolicy = descriptor.runtime.backendPolicy,
            gpuRuntimeIdentity = descriptor.runtime.gpuRuntimeIdentity.takeIf {
                descriptor.runtime.backendPolicy == SourceSeparationExecutionBackendPolicy.Auto
            },
            cpuThreads = descriptor.runtime.cpuThreads,
            useXnnpack = descriptor.runtime.useXnnpack,
        )

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte ->
                "%02x".format(Locale.US, byte.toInt() and 0xff)
            }
    }
}

internal fun SourceSeparationResolvedCacheModel.toExecutionModelIdentity(
    contract: SourceSeparationCacheContractSnapshot = this.contract,
) = SourceSeparationExecutionModelIdentity(
    modelId = contract.modelId,
    artifactFileName = artifact.file.name,
    artifactByteSize = artifact.byteSize,
    artifactSha256 = artifact.sha256,
    contractId = contract.contractId,
    contractSchemaVersion = contract.contractSchemaVersion,
    contractFingerprint = contract.contractFingerprint,
    profileRevisionId = contract.profileRevisionId,
    executionProfileId = executionProfile.profileId,
    executionSessionIdentity = executionProfile.sessionIdentity,
    pipelineId = contract.pipelineId,
    pipelineVersion = contract.pipelineVersion,
)
