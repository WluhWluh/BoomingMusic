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
import com.mardous.booming.separation.model.SourceSeparationSegmentSchedulerProgress
import com.mardous.booming.separation.model.MdxInferenceBackend
import com.mardous.booming.separation.model.MdxRangeProgress
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal const val SOURCE_SEPARATION_MULTISTEM_EXECUTION_PROTOCOL_VERSION = 4

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
        require(source.sourceUri == song.mediaUri) {
            "Multi-stem execution source URI does not match the song locator."
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
    val initialPlaybackPositionMs: Long? = null,
    val initialPlaybackReadyWindowCount: Int = 2,
) {
    init {
        require(executionProfileId.isNotBlank()) {
            "Multi-stem execution profile is empty."
        }
        require(backgroundPolicy == runClass.backgroundPolicy) {
            "Multi-stem execution background policy does not match its run class."
        }
        require(initialPlaybackPositionMs == null || initialPlaybackPositionMs >= 0L) {
            "Multi-stem initial playback position is invalid."
        }
        require(initialPlaybackReadyWindowCount > 0) {
            "Multi-stem initial playback ready-window count is invalid."
        }
    }
}

@Serializable
internal data class SourceSeparationMultiStemIpcStartCommand(
    val protocolVersion: Int = SOURCE_SEPARATION_MULTISTEM_EXECUTION_PROTOCOL_VERSION,
    val descriptor: SourceSeparationMultiStemExecutionDescriptor,
    val foregroundLease: SourceSeparationForegroundLeaseRequest? = null,
) {
    init {
        require(protocolVersion == SOURCE_SEPARATION_MULTISTEM_EXECUTION_PROTOCOL_VERSION)
        val independentlyOwned = descriptor.runtime.runClass ==
            SourceSeparationExecutionRunClass.ManualFullSong &&
            descriptor.runtime.backgroundPolicy ==
            SourceSeparationBackgroundPolicy.IndependentForegroundEligible
        require((foregroundLease != null) == independentlyOwned) {
            "Multi-stem foreground ownership does not match the admitted run class."
        }
        foregroundLease?.let { lease ->
            require(lease.runId == descriptor.runId &&
                lease.processGeneration == descriptor.processGeneration &&
                lease.displayName == descriptor.source.displayName
            ) { "Multi-stem foreground lease does not match its descriptor." }
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
    @SerialName("accepted")
    data class Accepted(
        val descriptor: SourceSeparationMultiStemExecutionDescriptor,
    ) : SourceSeparationMultiStemExecutionEventPayload()

    @Serializable
    @SerialName("progress")
    data class Progress(
        val completedWindows: Int,
        val totalWindows: Int,
        val stage: String? = null,
        val sourceDecodeDiagnostics: SourceSeparationExecutionSourceDecodeDiagnostics? = null,
        val completedWindowElapsedMs: Long? = null,
        val scheduler: SourceSeparationSegmentSchedulerProgress? = null,
        val runtimeBackend: String? = null,
    ) : SourceSeparationMultiStemExecutionEventPayload() {
        init {
            require(completedWindows >= 0 && totalWindows > 0 &&
                completedWindows <= totalWindows)
            require(completedWindowElapsedMs == null || completedWindowElapsedMs >= 0L)
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

    @Serializable
    @SerialName("already-completed")
    data class AlreadyCompleted(
        val completion: SourceSeparationMultiStemExecutionCompletion,
    ) : SourceSeparationMultiStemExecutionEventPayload()

    @Serializable
    @SerialName("paused")
    data class Paused(
        val reason: com.mardous.booming.separation.SourceSeparationPauseReason,
    ) : SourceSeparationMultiStemExecutionEventPayload()

    @Serializable
    @SerialName("canceled")
    data class Canceled(
        val message: String? = null,
    ) : SourceSeparationMultiStemExecutionEventPayload()

    @Serializable
    @SerialName("failed")
    data class Failed(
        val errorType: String,
        val message: String? = null,
    ) : SourceSeparationMultiStemExecutionEventPayload() {
        init { require(errorType.isNotBlank()) }
    }
}

internal fun MdxRangeProgress.toMultiStemExecutionProgress() =
    SourceSeparationMultiStemExecutionEventPayload.Progress(
        completedWindows = completedWindows.coerceAtLeast(0),
        totalWindows = totalWindows.coerceAtLeast(1),
        stage = stage,
        sourceDecodeDiagnostics = sourceDecodeDiagnostics?.toExecutionDiagnostics(),
        completedWindowElapsedMs = completedWindowElapsedMs,
        scheduler = scheduler,
        runtimeBackend = runtimeBackend?.name,
    )

internal fun SourceSeparationMultiStemExecutionEventPayload.Progress.toMdxRangeProgress() =
    MdxRangeProgress(
        completedWindows = completedWindows,
        totalWindows = totalWindows,
        stage = stage,
        sourceDecodeDiagnostics = sourceDecodeDiagnostics?.toMdxDiagnostics(),
        completedWindowElapsedMs = completedWindowElapsedMs,
        scheduler = scheduler,
        runtimeBackend = runtimeBackend?.let(MdxInferenceBackend::valueOf),
    )

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

@Serializable
internal data class SourceSeparationMultiStemIpcConnectResponse(
    val protocolVersion: Int = SOURCE_SEPARATION_MULTISTEM_EXECUTION_PROTOCOL_VERSION,
    val processGeneration: Long,
    val pid: Int,
) {
    init {
        require(protocolVersion == SOURCE_SEPARATION_MULTISTEM_EXECUTION_PROTOCOL_VERSION)
        require(processGeneration > 0L && pid > 0)
    }
}

@Serializable
internal data class SourceSeparationMultiStemIpcStartResponse(
    val protocolVersion: Int = SOURCE_SEPARATION_MULTISTEM_EXECUTION_PROTOCOL_VERSION,
    val status: SourceSeparationMultiStemIpcStatus,
    val runId: String? = null,
    val processGeneration: Long? = null,
    val errorType: String? = null,
    val message: String? = null,
) {
    init {
        require(protocolVersion == SOURCE_SEPARATION_MULTISTEM_EXECUTION_PROTOCOL_VERSION)
        if (status == SourceSeparationMultiStemIpcStatus.Accepted) {
            require(!runId.isNullOrBlank() && processGeneration != null && processGeneration > 0L)
            require(errorType == null)
        }
        require(errorType == null || errorType.isNotBlank())
    }
}

@Serializable
internal enum class SourceSeparationMultiStemIpcStatus {
    Accepted,
    Active,
    Applied,
    AlreadyApplied,
    Busy,
    NoActiveRun,
    StaleRun,
    StaleGeneration,
    Terminal,
    Rejected,
    Failed,
}

@Serializable
internal enum class SourceSeparationMultiStemIpcRunAuthority {
    ClientBound,
    IndependentForeground,
}

@Serializable
internal data class SourceSeparationMultiStemIpcActiveRunState(
    val descriptor: SourceSeparationMultiStemExecutionDescriptor,
    val authority: SourceSeparationMultiStemIpcRunAuthority,
    val latestEvent: SourceSeparationMultiStemExecutionEvent,
    val observerConnected: Boolean,
    val foregroundLease: SourceSeparationForegroundLeaseRequest? = null,
) {
    init {
        require(latestEvent.runId == descriptor.runId &&
            latestEvent.processGeneration == descriptor.processGeneration
        ) { "Multi-stem active snapshot contains a stale event." }
        val independentlyOwned = authority ==
            SourceSeparationMultiStemIpcRunAuthority.IndependentForeground
        require(independentlyOwned ==
            (descriptor.runtime.backgroundPolicy ==
                SourceSeparationBackgroundPolicy.IndependentForegroundEligible)
        ) { "Multi-stem active snapshot authority differs from its descriptor." }
        require((foregroundLease != null) == independentlyOwned) {
            "Multi-stem active snapshot foreground lease is inconsistent."
        }
        foregroundLease?.let { lease ->
            require(lease.runId == descriptor.runId &&
                lease.processGeneration == descriptor.processGeneration
            ) { "Multi-stem active snapshot foreground lease is stale." }
        }
    }
}

@Serializable
internal data class SourceSeparationMultiStemIpcActiveRunResponse(
    val protocolVersion: Int = SOURCE_SEPARATION_MULTISTEM_EXECUTION_PROTOCOL_VERSION,
    val status: SourceSeparationMultiStemIpcStatus,
    val state: SourceSeparationMultiStemIpcActiveRunState? = null,
) {
    init {
        require(protocolVersion == SOURCE_SEPARATION_MULTISTEM_EXECUTION_PROTOCOL_VERSION)
        require((status == SourceSeparationMultiStemIpcStatus.Active) == (state != null)) {
            "Multi-stem active-run response is inconsistent."
        }
        require(status == SourceSeparationMultiStemIpcStatus.Active ||
            status == SourceSeparationMultiStemIpcStatus.NoActiveRun
        ) { "Unsupported multi-stem active-run response status: $status" }
    }
}

@Serializable
internal enum class SourceSeparationMultiStemIpcControlAction {
    Update,
    Pause,
    Cancel,
}

@Serializable
internal data class SourceSeparationMultiStemIpcControlCommand(
    val protocolVersion: Int = SOURCE_SEPARATION_MULTISTEM_EXECUTION_PROTOCOL_VERSION,
    val runId: String,
    val processGeneration: Long,
    val action: SourceSeparationMultiStemIpcControlAction,
    val pauseReason: com.mardous.booming.separation.SourceSeparationPauseReason? = null,
    val hasPlaybackPositionUpdate: Boolean = false,
    val playbackPositionMs: Long? = null,
    val playbackReadyWindowCount: Int? = null,
) {
    init {
        require(protocolVersion == SOURCE_SEPARATION_MULTISTEM_EXECUTION_PROTOCOL_VERSION)
        require(runId.isNotBlank() && processGeneration > 0L)
        require((action == SourceSeparationMultiStemIpcControlAction.Pause) ==
            (pauseReason != null))
        require(hasPlaybackPositionUpdate || playbackPositionMs == null) {
            "Multi-stem playback position was supplied without an update marker."
        }
        require(playbackPositionMs == null || playbackPositionMs >= 0L)
        require(playbackReadyWindowCount == null || playbackReadyWindowCount > 0)
    }
}

@Serializable
internal data class SourceSeparationMultiStemIpcControlResponse(
    val protocolVersion: Int = SOURCE_SEPARATION_MULTISTEM_EXECUTION_PROTOCOL_VERSION,
    val status: SourceSeparationMultiStemIpcStatus,
) {
    init {
        require(protocolVersion == SOURCE_SEPARATION_MULTISTEM_EXECUTION_PROTOCOL_VERSION)
    }
}

internal object SourceSeparationMultiStemExecutionCodec {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        classDiscriminator = "eventType"
    }

    fun encodeDescriptor(value: SourceSeparationMultiStemExecutionDescriptor): String =
        json.encodeToString(SourceSeparationMultiStemExecutionDescriptor.serializer(), value)

    fun decodeDescriptor(value: String): SourceSeparationMultiStemExecutionDescriptor =
        json.decodeFromString(SourceSeparationMultiStemExecutionDescriptor.serializer(), value)

    fun encodeStartCommand(value: SourceSeparationMultiStemIpcStartCommand): String =
        json.encodeToString(SourceSeparationMultiStemIpcStartCommand.serializer(), value)

    fun decodeStartCommand(value: String): SourceSeparationMultiStemIpcStartCommand =
        json.decodeFromString(SourceSeparationMultiStemIpcStartCommand.serializer(), value)

    fun encodeEvent(value: SourceSeparationMultiStemExecutionEvent): String =
        json.encodeToString(SourceSeparationMultiStemExecutionEvent.serializer(), value)

    fun decodeEvent(value: String): SourceSeparationMultiStemExecutionEvent =
        json.decodeFromString(SourceSeparationMultiStemExecutionEvent.serializer(), value)

    fun encodeConnectResponse(value: SourceSeparationMultiStemIpcConnectResponse): String =
        json.encodeToString(SourceSeparationMultiStemIpcConnectResponse.serializer(), value)

    fun decodeConnectResponse(value: String): SourceSeparationMultiStemIpcConnectResponse =
        json.decodeFromString(SourceSeparationMultiStemIpcConnectResponse.serializer(), value)

    fun encodeStartResponse(value: SourceSeparationMultiStemIpcStartResponse): String =
        json.encodeToString(SourceSeparationMultiStemIpcStartResponse.serializer(), value)

    fun decodeStartResponse(value: String): SourceSeparationMultiStemIpcStartResponse =
        json.decodeFromString(SourceSeparationMultiStemIpcStartResponse.serializer(), value)

    fun encodeControlCommand(value: SourceSeparationMultiStemIpcControlCommand): String =
        json.encodeToString(SourceSeparationMultiStemIpcControlCommand.serializer(), value)

    fun decodeControlCommand(value: String): SourceSeparationMultiStemIpcControlCommand =
        json.decodeFromString(SourceSeparationMultiStemIpcControlCommand.serializer(), value)

    fun encodeControlResponse(value: SourceSeparationMultiStemIpcControlResponse): String =
        json.encodeToString(SourceSeparationMultiStemIpcControlResponse.serializer(), value)

    fun decodeControlResponse(value: String): SourceSeparationMultiStemIpcControlResponse =
        json.decodeFromString(SourceSeparationMultiStemIpcControlResponse.serializer(), value)

    fun encodeActiveRunResponse(value: SourceSeparationMultiStemIpcActiveRunResponse): String =
        json.encodeToString(SourceSeparationMultiStemIpcActiveRunResponse.serializer(), value)

    fun decodeActiveRunResponse(value: String): SourceSeparationMultiStemIpcActiveRunResponse =
        json.decodeFromString(SourceSeparationMultiStemIpcActiveRunResponse.serializer(), value)
}

private fun requireStemPaths(paths: List<SourceSeparationMultiStemExecutionStemPath>) {
    require(paths.isNotEmpty()) { "Multi-stem execution stem paths are empty." }
    require(paths.map { it.stemId }.distinct().size == paths.size)
    require(paths.map { it.order } == paths.indices.toList())
    require(paths.map { it.path }.distinct().size == paths.size)
}

private val SHA256_PATTERN = Regex("^[0-9a-fA-F]{64}$")
