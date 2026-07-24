package com.mardous.booming.separation.process

import com.mardous.booming.separation.SourceSeparationModelAwareExecutionRequest
import com.mardous.booming.separation.cache.SourceSeparationSegmentPlan
import com.mardous.booming.separation.cache.SourceSeparationSegmentState
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheContractSnapshot
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheIdentity
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRelativePath
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheSourceDiagnostics
import com.mardous.booming.separation.model.MdxRangeSeparationResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal const val SOURCE_SEPARATION_EXECUTION_PROTOCOL_VERSION = 2

internal interface SourceSeparationExecutionHost : AutoCloseable {
    val mode: SourceSeparationExecutionHostMode
    val processGeneration: Long

    fun start(
        request: SourceSeparationExecutionHostRequest,
    ): SourceSeparationExecutionHostStartResult

    fun snapshot(
        runId: String,
        processGeneration: Long,
    ): SourceSeparationExecutionHostSnapshot?

    fun pause(
        runId: String,
        processGeneration: Long,
    ): SourceSeparationExecutionHostControlResult

    fun cancel(
        runId: String,
        processGeneration: Long,
    ): SourceSeparationExecutionHostControlResult

    fun closeRun(
        runId: String,
        processGeneration: Long,
    ): SourceSeparationExecutionHostControlResult
}

internal data class SourceSeparationExecutionHostRequest(
    val descriptor: SourceSeparationExecutionDescriptor,
    val executionRequest: SourceSeparationModelAwareExecutionRequest,
    val onEvent: (SourceSeparationExecutionHostEvent) -> Unit = {},
)

internal data class SourceSeparationExecutionHostStartResult(
    val result: MdxRangeSeparationResult,
    val diagnostics: SourceSeparationExecutionHostDiagnostics,
)

@Serializable
internal data class SourceSeparationExecutionDescriptor(
    val protocolVersion: Int = SOURCE_SEPARATION_EXECUTION_PROTOCOL_VERSION,
    val runId: String,
    val processGeneration: Long,
    val cacheKey: String,
    val cacheIdentity: SourceSeparationCacheIdentity,
    val contract: SourceSeparationCacheContractSnapshot,
    val model: SourceSeparationExecutionModelIdentity,
    val source: SourceSeparationExecutionSourceIdentity,
    val runtime: SourceSeparationExecutionRuntimeIdentity,
    val resume: SourceSeparationExecutionResumeState? = null,
) {
    init {
        require(protocolVersion == SOURCE_SEPARATION_EXECUTION_PROTOCOL_VERSION) {
            "Unsupported execution protocol version: $protocolVersion"
        }
        require(runId.isNotBlank()) { "Execution run ID is empty." }
        require(processGeneration > 0L) { "Execution process generation is invalid." }
        require(cacheKey == cacheIdentity.cacheKey) {
            "Execution cache key does not match its identity."
        }
        require(contract.modelId == cacheIdentity.modelId) {
            "Execution contract model ID does not match the cache identity."
        }
        require(contract.artifactSha256.equals(cacheIdentity.artifactSha256, ignoreCase = true)) {
            "Execution contract artifact hash does not match the cache identity."
        }
        require(contract.contractId == cacheIdentity.contractId) {
            "Execution contract ID does not match the cache identity."
        }
        require(contract.contractSchemaVersion == cacheIdentity.contractSchemaVersion) {
            "Execution contract schema does not match the cache identity."
        }
        require(contract.contractFingerprint.equals(
            cacheIdentity.contractFingerprint,
            ignoreCase = true,
        )) {
            "Execution contract fingerprint does not match the cache identity."
        }
        require(contract.profileRevisionId == cacheIdentity.profileRevisionId) {
            "Execution profile revision does not match the cache identity."
        }
        require(contract.pipelineId == cacheIdentity.pipelineId &&
            contract.pipelineVersion == cacheIdentity.pipelineVersion
        ) {
            "Execution pipeline does not match the cache identity."
        }
        model.requireMatches(cacheIdentity, contract)
        require(source.expectedAudioFingerprint ==
            cacheIdentity.source.audioFingerprint
        ) {
            "Execution source fingerprint does not match the cache identity."
        }
    }
}

@Serializable
internal data class SourceSeparationExecutionModelIdentity(
    val modelId: String,
    val artifactFileName: String,
    val artifactByteSize: Long,
    val artifactSha256: String,
    val contractId: String,
    val contractSchemaVersion: Int,
    val contractFingerprint: String,
    val profileRevisionId: String,
    val executionProfileId: String,
    val executionSessionIdentity: String,
    val pipelineId: String,
    val pipelineVersion: Int,
) {
    init {
        require(modelId.isNotBlank()) { "Execution model ID is empty." }
        require(artifactFileName.isNotBlank()) { "Execution artifact filename is empty." }
        require(artifactByteSize > 0L) { "Execution artifact size is invalid." }
        require(SHA256_PATTERN.matches(artifactSha256)) {
            "Execution artifact SHA-256 is invalid."
        }
        require(contractId.isNotBlank()) { "Execution contract ID is empty." }
        require(contractSchemaVersion > 0) { "Execution contract schema is invalid." }
        require(SHA256_PATTERN.matches(contractFingerprint)) {
            "Execution contract fingerprint is invalid."
        }
        require(profileRevisionId.isNotBlank()) { "Execution profile revision is empty." }
        require(executionProfileId.isNotBlank()) { "Execution profile ID is empty." }
        require(executionSessionIdentity.isNotBlank()) {
            "Execution session identity is empty."
        }
        require(pipelineId.isNotBlank() && pipelineVersion > 0) {
            "Execution pipeline identity is invalid."
        }
    }

    fun requireMatches(
        cacheIdentity: SourceSeparationCacheIdentity,
        contract: SourceSeparationCacheContractSnapshot,
    ) {
        require(modelId == cacheIdentity.modelId && modelId == contract.modelId) {
            "Execution model ID is inconsistent."
        }
        require(artifactFileName == contract.artifactFileName) {
            "Execution artifact filename is inconsistent."
        }
        require(artifactByteSize == contract.artifactByteSize) {
            "Execution artifact size is inconsistent."
        }
        require(artifactSha256.equals(cacheIdentity.artifactSha256, ignoreCase = true) &&
            artifactSha256.equals(contract.artifactSha256, ignoreCase = true)
        ) {
            "Execution artifact hash is inconsistent."
        }
        require(contractId == cacheIdentity.contractId && contractId == contract.contractId) {
            "Execution contract ID is inconsistent."
        }
        require(contractSchemaVersion == cacheIdentity.contractSchemaVersion &&
            contractSchemaVersion == contract.contractSchemaVersion
        ) {
            "Execution contract schema is inconsistent."
        }
        require(contractFingerprint.equals(cacheIdentity.contractFingerprint, ignoreCase = true) &&
            contractFingerprint.equals(contract.contractFingerprint, ignoreCase = true)
        ) {
            "Execution contract fingerprint is inconsistent."
        }
        require(profileRevisionId == cacheIdentity.profileRevisionId &&
            profileRevisionId == contract.profileRevisionId
        ) {
            "Execution profile revision is inconsistent."
        }
        require(pipelineId == cacheIdentity.pipelineId && pipelineId == contract.pipelineId &&
            pipelineVersion == cacheIdentity.pipelineVersion &&
            pipelineVersion == contract.pipelineVersion
        ) {
            "Execution pipeline identity is inconsistent."
        }
    }
}

@Serializable
internal data class SourceSeparationExecutionSourceIdentity(
    val sourceUri: String,
    val displayName: String,
    val expectedAudioFingerprint: String,
    val diagnostics: SourceSeparationCacheSourceDiagnostics,
) {
    init {
        require(sourceUri.isNotBlank()) { "Execution source URI is empty." }
        require(displayName.isNotBlank()) { "Execution source display name is empty." }
        require(expectedAudioFingerprint.isNotBlank()) {
            "Execution source fingerprint is empty."
        }
    }
}

@Serializable
internal data class SourceSeparationExecutionRuntimeIdentity(
    val executionProfileId: String,
    val executionSessionIdentity: String,
    val cpuThreads: Int,
    val useXnnpack: Boolean,
    val windowDecodeEnabled: Boolean,
    val initialPlaybackPositionMs: Long?,
    val initialPlaybackReadyWindowCount: Int,
    val runClass: SourceSeparationExecutionRunClass =
        SourceSeparationExecutionRunClass.PlaybackAware,
) {
    init {
        require(executionProfileId.isNotBlank()) { "Runtime execution profile ID is empty." }
        require(executionSessionIdentity.isNotBlank()) {
            "Runtime execution session identity is empty."
        }
        require(cpuThreads > 0) { "Runtime CPU thread count is invalid." }
        require(initialPlaybackPositionMs == null || initialPlaybackPositionMs >= 0L) {
            "Initial playback position is invalid."
        }
        require(initialPlaybackReadyWindowCount > 0) {
            "Initial playback ready-window count is invalid."
        }
    }
}

@Serializable
internal enum class SourceSeparationExecutionRunClass {
    PlaybackAware,
}

@Serializable
internal data class SourceSeparationExecutionResumeState(
    val vocalsPath: String,
    val instrumentalPath: String,
    val timingPath: String?,
    val segmentPlan: SourceSeparationSegmentPlan,
) {
    init {
        SourceSeparationCacheRelativePath.requireValid(vocalsPath)
        SourceSeparationCacheRelativePath.requireValid(instrumentalPath)
        timingPath?.let(SourceSeparationCacheRelativePath::requireValid)
    }
}

@Serializable
enum class SourceSeparationExecutionHostMode {
    InProcess,
    BoundRemote,
}

@Serializable
enum class SourceSeparationExecutionHostLifecycle {
    Starting,
    Running,
    Prepared,
    Completed,
    Paused,
    Canceled,
    Failed,
    Closed,
}

@Serializable
internal enum class SourceSeparationExecutionHostControlResult {
    Applied,
    AlreadyApplied,
    NoActiveRun,
    StaleRun,
    StaleGeneration,
    RunActive,
    Terminal,
    HostClosed,
}

@Serializable
data class SourceSeparationExecutionHostDiagnostics(
    val mode: SourceSeparationExecutionHostMode,
    val runId: String,
    val processGeneration: Long,
    val lifecycle: SourceSeparationExecutionHostLifecycle,
    val backend: String? = null,
    val runtimeName: String? = null,
    val latestEventSequence: Long,
)

@Serializable
internal data class SourceSeparationExecutionHostSnapshot(
    val descriptor: SourceSeparationExecutionDescriptor,
    val diagnostics: SourceSeparationExecutionHostDiagnostics,
    val latestEvent: SourceSeparationExecutionHostEvent,
)

@Serializable
internal data class SourceSeparationExecutionHostEvent(
    val protocolVersion: Int = SOURCE_SEPARATION_EXECUTION_PROTOCOL_VERSION,
    val runId: String,
    val processGeneration: Long,
    val sequence: Long,
    val payload: SourceSeparationExecutionHostEventPayload,
) {
    init {
        require(protocolVersion == SOURCE_SEPARATION_EXECUTION_PROTOCOL_VERSION) {
            "Unsupported execution event protocol version: $protocolVersion"
        }
        require(runId.isNotBlank()) { "Execution event run ID is empty." }
        require(processGeneration > 0L) { "Execution event generation is invalid." }
        require(sequence > 0L) { "Execution event sequence is invalid." }
    }
}

@Serializable
internal sealed class SourceSeparationExecutionHostEventPayload {
    @Serializable
    @SerialName("accepted")
    data class Accepted(
        val descriptor: SourceSeparationExecutionDescriptor,
    ) : SourceSeparationExecutionHostEventPayload()

    @Serializable
    @SerialName("progress")
    data class Progress(
        val progress: SourceSeparationExecutionProgress,
    ) : SourceSeparationExecutionHostEventPayload()

    @Serializable
    @SerialName("prepared")
    data class Prepared(
        val preparation: SourceSeparationExecutionPreparation,
    ) : SourceSeparationExecutionHostEventPayload()

    @Serializable
    @SerialName("segment-state")
    data class SegmentStateChanged(
        val segmentIndex: Int,
        val state: SourceSeparationSegmentState,
    ) : SourceSeparationExecutionHostEventPayload()

    @Serializable
    @SerialName("completed")
    data class Completed(
        val completion: SourceSeparationExecutionCompletion,
    ) : SourceSeparationExecutionHostEventPayload()

    @Serializable
    @SerialName("paused")
    data class Paused(
        val reason: String?,
    ) : SourceSeparationExecutionHostEventPayload()

    @Serializable
    @SerialName("canceled")
    data class Canceled(
        val reason: String?,
    ) : SourceSeparationExecutionHostEventPayload()

    @Serializable
    @SerialName("failed")
    data class Failed(
        val errorType: String,
        val message: String?,
    ) : SourceSeparationExecutionHostEventPayload()
}

@Serializable
internal data class SourceSeparationExecutionProgress(
    val completedWindows: Int,
    val totalWindows: Int,
    val stage: String?,
    val sourceDecodeDiagnostics: SourceSeparationExecutionSourceDecodeDiagnostics?,
    val completedWindowElapsedMs: Long?,
    val scheduler: SourceSeparationExecutionSchedulerProgress?,
)

@Serializable
internal data class SourceSeparationExecutionSchedulerProgress(
    val playbackSegmentIndex: Int?,
    val playbackSegmentState: String?,
    val nextSegmentIndex: Int?,
    val nextSegmentState: String?,
    val processingSegmentIndex: Int,
    val priority: String?,
    val readySegments: Int,
    val totalSegments: Int,
    val readyWindowCount: Int,
    val playbackReadyWindowReadyCount: Int,
    val playbackReadyWindowPendingCount: Int,
)

@Serializable
internal data class SourceSeparationExecutionSourceDecodeDiagnostics(
    val mode: String,
    val profile: String?,
    val mimeType: String,
    val sampleRate: Int,
    val channelCount: Int,
    val sourceFrameCount: Int?,
    val outputFrameCount: Int?,
    val fallbackReason: String?,
    val experimental: Boolean,
    val calibration: String?,
    val encoderDelayFrames: Int?,
    val encoderPaddingFrames: Int?,
)

@Serializable
internal data class SourceSeparationExecutionPreparation(
    val vocalsPath: String,
    val instrumentalPath: String,
    val timingPath: String,
    val startMs: Long,
    val endMs: Long,
    val frames: Int,
    val windowCount: Int,
    val sourceAudioFingerprint: String,
    val sourceFrameCount: Int,
    val sourceSampleRate: Int,
    val sourceChannelCount: Int,
    val outputSampleRate: Int,
    val segmentPlan: SourceSeparationSegmentPlan,
) {
    init {
        SourceSeparationCacheRelativePath.requireValid(vocalsPath)
        SourceSeparationCacheRelativePath.requireValid(instrumentalPath)
        SourceSeparationCacheRelativePath.requireValid(timingPath)
    }
}

@Serializable
internal data class SourceSeparationExecutionCompletion(
    val vocalsPath: String,
    val instrumentalPath: String,
    val timingPath: String,
    val startMs: Long,
    val endMs: Long,
    val frames: Int,
    val windowCount: Int,
    val elapsedMs: Long,
    val sourceAudioFingerprint: String,
    val sourceFrameCount: Int,
    val sourceSampleRate: Int,
    val sourceChannelCount: Int,
    val outputSampleRate: Int,
    val segmentPlan: SourceSeparationSegmentPlan,
    val runtimeSettings: SourceSeparationExecutionRuntimeSettings,
    val runtimeDiagnostics: SourceSeparationExecutionRuntimeDiagnostics,
    val sourceDecodeDiagnostics: SourceSeparationExecutionSourceDecodeDiagnostics,
    val timingAudioDurationSeconds: Double,
    val timingStageMs: Map<String, Long>,
    val modelVariant: String?,
) {
    init {
        SourceSeparationCacheRelativePath.requireValid(vocalsPath)
        SourceSeparationCacheRelativePath.requireValid(instrumentalPath)
        SourceSeparationCacheRelativePath.requireValid(timingPath)
    }
}

@Serializable
internal data class SourceSeparationExecutionRuntimeSettings(
    val cpuThreads: Int,
    val useXnnpack: Boolean,
)

@Serializable
internal data class SourceSeparationExecutionRuntimeDiagnostics(
    val runtimeName: String,
    val backend: String,
    val cpuThreads: Int?,
    val detail: String,
    val fallbackStage: String?,
    val fallbackReason: String?,
)

private val SHA256_PATTERN = Regex("^[0-9a-fA-F]{64}$")
