package com.mardous.booming.separation

import com.mardous.booming.separation.cache.SourceSeparationSegmentState
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheManifest
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheSourcePreflight
import com.mardous.booming.separation.model.MdxRangeProgress
import com.mardous.booming.separation.model.contract.SourceSeparationInstalledMultiStemModel
import java.util.UUID

/** Execution boundary for the product's multi-stem separation worker. */
internal fun interface SourceSeparationMultiStemExecutionHost {
    fun separate(request: SourceSeparationMultiStemExecutionRequest):
        HtdemucsSourceSeparationEngineResult
}

internal data class SourceSeparationMultiStemExecutionRequest(
    val runId: String = "multistem-${UUID.randomUUID()}",
    val input: SourceSeparationModelAwareSongInput,
    val installedModel: SourceSeparationInstalledMultiStemModel,
    val preflight: SourceSeparationCacheSourcePreflight,
    val runClass: SourceSeparationExecutionRunClass,
    val windowDecodeEnabled: Boolean,
    val onProgress: (MdxRangeProgress) -> Unit,
    val onPrepared: (SourceSeparationCacheManifest) -> Unit,
    val onSegmentStateChanged: (Int, SourceSeparationSegmentState) -> Unit,
    val playbackPositionMsProvider: () -> Long? = { null },
    val playbackReadyWindowCountProvider: () -> Int = { 2 },
    val shouldPause: () -> Boolean,
    val pauseReasonProvider: () -> SourceSeparationPauseReason,
    val shouldCancel: () -> Boolean,
) {
    init {
        require(runId.isNotBlank()) { "Multi-stem execution run ID is empty." }
    }
}

/** Current product implementation; a remote implementation can use the same request. */
internal class InProcessSourceSeparationMultiStemExecutionHost(
    private val engine: HtdemucsSourceSeparationEngine,
) : SourceSeparationMultiStemExecutionHost {
    override fun separate(
        request: SourceSeparationMultiStemExecutionRequest,
    ): HtdemucsSourceSeparationEngineResult = engine.separate(
        runId = request.runId,
        input = request.input,
        installedModel = request.installedModel,
        preflight = request.preflight,
        runClass = request.runClass,
        windowDecodeEnabled = request.windowDecodeEnabled,
        onProgress = request.onProgress,
        onPrepared = request.onPrepared,
        onSegmentStateChanged = request.onSegmentStateChanged,
        playbackPositionMsProvider = request.playbackPositionMsProvider,
        playbackReadyWindowCountProvider = request.playbackReadyWindowCountProvider,
        shouldPause = request.shouldPause,
        pauseReasonProvider = request.pauseReasonProvider,
        shouldCancel = request.shouldCancel,
    )
}
