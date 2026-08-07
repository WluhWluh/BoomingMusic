package com.mardous.booming.separation.process

import com.mardous.booming.separation.SourceSeparationBackgroundPolicy
import com.mardous.booming.separation.SourceSeparationExecutionRunClass
import com.mardous.booming.separation.cache.SourceSeparationSegmentPlan
import com.mardous.booming.separation.cache.SourceSeparationSegmentState
import com.mardous.booming.separation.cache.SourceSeparationCacheRelativePath
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheContractSnapshot
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheIdentity
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRuntimeRecord
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheSongLocator
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheSourceDiagnostics
import com.mardous.booming.separation.model.contract.StemId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal const val SOURCE_SEPARATION_MULTISTEM_EXECUTION_PROTOCOL_VERSION = 1

/** Wire descriptor for the multi-stem worker; it intentionally has no MDX fields. */
@Serializable
internal data class SourceSeparationMultiStemExecutionDescriptor(
    val protocolVersion: Int = SOURCE_SEPARATION_MULTISTEM_EXECUTION_PROTOCOL_VERSION,
    val runId: String,
    val processGeneration: Long,
    val cacheKey: String,
    val cacheIdentity: SourceSeparationCacheIdentity,
    val contract: SourceSeparationCacheContractSnapshot,
    val model: SourceSeparationMultiStemExecutionModelIdentity,
    val source: SourceSeparationMultiStemExecutionSourceIdentity,
    val song: SourceSeparationCacheSongLocator,
    val runtime: SourceSeparationMultiStemExecutionRuntime,
) {
    init {
        require(protocolVersion == SOURCE_SEPARATION_MULTISTEM_EXECUTION_PROTOCOL_VERSION) {
            "Unsupported multi-stem execution protocol version: $protocolVersion"
        }
        require(runId.isNotBlank()) { "Multi-stem execution run ID is empty." }
        require(processGeneration > 0L) { "Multi-stem execution generation is invalid." }
        require(cacheKey == cacheIdentity.cacheKey) {
            "Multi-stem execution cache key does not match its identity."
        }
        require(contract.multiTensorContract != null && contract.multiStemSet != null) {
            "Multi-stem execution requires a multi-tensor contract."
        }
        require(contract.identity(source.source, cacheIdentity.renderProfileId) == cacheIdentity) {
            "Multi-stem execution contract does not match its cache identity."
        }
        model.requireMatches(contract)
    }
}

@Serializable
internal data class SourceSeparationMultiStemExecutionModelIdentity(
    val modelId: String,
    val artifactFileName: String,
    val artifactByteSize: Long,
    val artifactSha256: String,
    val contractId: String,
    val pipelineId: String,
    val pipelineVersion: Int,
) {
    init {
        require(modelId.isNotBlank() && artifactFileName.isNotBlank()) {
            "Multi-stem execution model identity is incomplete."
        }
        require(artifactByteSize > 0L) { "Multi-stem artifact size is invalid." }
        require(SHA256_PATTERN.matches(artifactSha256)) {
            "Multi-stem artifact SHA-256 is invalid."
        }
        require(contractId.isNotBlank() && pipelineId.isNotBlank() && pipelineVersion > 0) {
            "Multi-stem execution pipeline identity is invalid."
        }
    }

    fun requireMatches(contract: SourceSeparationCacheContractSnapshot) {
        require(modelId == contract.modelId && artifactFileName == contract.artifactFileName) {
            "Multi-stem execution model does not match the contract."
        }
        require(artifactByteSize == contract.artifactByteSize &&
            artifactSha256.equals(contract.artifactSha256, ignoreCase = true) &&
            contractId == contract.contractId && pipelineId == contract.pipelineId &&
            pipelineVersion == contract.pipelineVersion
        ) { "Multi-stem execution model identity is inconsistent." }
    }
}

@Serializable
internal data class SourceSeparationMultiStemExecutionSourceIdentity(
    val sourceUri: String,
    val displayName: String,
    val source: com.mardous.booming.separation.cache.v2.SourceSeparationCacheSourceIdentity,
    val diagnostics: SourceSeparationCacheSourceDiagnostics,
) {
    init {
        require(sourceUri.isNotBlank() && displayName.isNotBlank()) {
            "Multi-stem execution source identity is incomplete."
        }
        require(source.audioFingerprint.isNotBlank()) {
            "Multi-stem execution source fingerprint is empty."
        }
    }
}

@Serializable
internal data class SourceSeparationMultiStemExecutionRuntime(
    val executionProfileId: String,
    val runClass: SourceSeparationExecutionRunClass,
    val backgroundPolicy: SourceSeparationBackgroundPolicy,
    val windowDecodeEnabled: Boolean,
) {
    init {
        require(executionProfileId.isNotBlank()) {
            "Multi-stem execution profile is empty."
        }
        require(backgroundPolicy == runClass.backgroundPolicy) {
            "Multi-stem execution background policy does not match its run class."
        }
    }
}

@Serializable
internal data class SourceSeparationMultiStemExecutionStemPath(
    val stemId: StemId,
    val order: Int,
    val path: String,
) {
    init {
        require(order >= 0) { "Multi-stem execution stem order is invalid." }
        SourceSeparationCacheRelativePath.requireValid(path)
    }
}

@Serializable
internal data class SourceSeparationMultiStemExecutionPreparation(
    val stemPaths: List<SourceSeparationMultiStemExecutionStemPath>,
    val timingPath: String?,
    val outputFrameCount: Int,
    val outputSampleRate: Int,
    val windowCount: Int,
    val sourceAudioFingerprint: String,
    val segmentPlan: SourceSeparationSegmentPlan,
) {
    init {
        requireStemPaths(stemPaths)
        require(outputFrameCount > 0 && outputSampleRate > 0 && windowCount > 0)
        require(sourceAudioFingerprint.isNotBlank())
        require(segmentPlan.stemIds == stemPaths.map { it.stemId })
        timingPath?.let(SourceSeparationCacheRelativePath::requireValid)
    }
}

@Serializable
internal data class SourceSeparationMultiStemExecutionCompletion(
    val preparation: SourceSeparationMultiStemExecutionPreparation,
    val elapsedMs: Long,
    val runtimeRecord: SourceSeparationCacheRuntimeRecord,
) {
    init {
        require(elapsedMs >= 0L)
    }
}

@Serializable
internal sealed class SourceSeparationMultiStemExecutionEventPayload {
    @Serializable
    @SerialName("progress")
    data class Progress(
        val completedWindows: Int,
        val totalWindows: Int,
        val stage: String? = null,
    ) : SourceSeparationMultiStemExecutionEventPayload() {
        init {
            require(completedWindows >= 0 && totalWindows > 0 &&
                completedWindows <= totalWindows)
        }
    }

    @Serializable
    @SerialName("prepared")
    data class Prepared(
        val preparation: SourceSeparationMultiStemExecutionPreparation,
    ) : SourceSeparationMultiStemExecutionEventPayload()

    @Serializable
    @SerialName("segment-state")
    data class SegmentStateChanged(
        val segmentIndex: Int,
        val state: SourceSeparationSegmentState,
    ) : SourceSeparationMultiStemExecutionEventPayload() {
        init { require(segmentIndex >= 0) }
    }

    @Serializable
    @SerialName("completed")
    data class Completed(
        val completion: SourceSeparationMultiStemExecutionCompletion,
    ) : SourceSeparationMultiStemExecutionEventPayload()
}

@Serializable
internal data class SourceSeparationMultiStemExecutionEvent(
    val protocolVersion: Int = SOURCE_SEPARATION_MULTISTEM_EXECUTION_PROTOCOL_VERSION,
    val runId: String,
    val processGeneration: Long,
    val sequence: Long,
    val payload: SourceSeparationMultiStemExecutionEventPayload,
) {
    init {
        require(protocolVersion == SOURCE_SEPARATION_MULTISTEM_EXECUTION_PROTOCOL_VERSION)
        require(runId.isNotBlank() && processGeneration > 0L && sequence > 0L)
    }
}

private fun requireStemPaths(paths: List<SourceSeparationMultiStemExecutionStemPath>) {
    require(paths.isNotEmpty()) { "Multi-stem execution stem paths are empty." }
    require(paths.map { it.stemId }.distinct().size == paths.size)
    require(paths.map { it.order } == paths.indices.toList())
    require(paths.map { it.path }.distinct().size == paths.size)
}

private val SHA256_PATTERN = Regex("^[0-9a-fA-F]{64}$")
