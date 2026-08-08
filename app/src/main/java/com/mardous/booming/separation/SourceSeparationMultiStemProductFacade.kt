package com.mardous.booming.separation

import com.mardous.booming.data.model.Song
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheContractSnapshot
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheManifest
import com.mardous.booming.separation.model.MdxRangeProgress
import com.mardous.booming.separation.model.contract.SourceSeparationInstalledMultiStemModel
import com.mardous.booming.separation.model.contract.SourceSeparationMultiStemInstallProgress
import com.mardous.booming.separation.model.contract.SourceSeparationMultiStemReleaseInstaller
import com.mardous.booming.separation.model.contract.SourceSeparationMultiTensorExecutableContractLoader
import com.mardous.booming.separation.model.contract.SourceSeparationReleaseCatalog
import java.util.concurrent.CancellationException

/** Product boundary for experimental multi-stem Release models. */
internal class SourceSeparationMultiStemProductFacade(
    private val installer: SourceSeparationMultiStemReleaseInstaller,
    private val executionHost: SourceSeparationMultiStemExecutionHost,
    private val preflightResolver: SourceSeparationModelAwarePreflightResolver,
) : SourceSeparationMultiStemRuntimeExecutor {
    fun catalog(): SourceSeparationReleaseCatalog = installer.catalog()

    fun installedModels(): List<SourceSeparationInstalledMultiStemModel> =
        installer.installedModels()

    fun install(
        modelId: String,
        onProgress: (SourceSeparationMultiStemInstallProgress) -> Unit = {},
    ): SourceSeparationInstalledMultiStemModel = installer.install(modelId, onProgress)

    fun separate(
        song: Song,
        modelId: String,
        runClass: SourceSeparationExecutionRunClass =
            SourceSeparationExecutionRunClass.ManualFullSong,
        windowDecodeEnabled: Boolean = true,
        onProgress: (MdxRangeProgress) -> Unit = {},
        onPrepared: (SourceSeparationCacheManifest) -> Unit = {},
        shouldPause: () -> Boolean = { false },
        pauseReasonProvider: () -> SourceSeparationPauseReason = {
            SourceSeparationPauseReason.Standard
        },
        shouldCancel: () -> Boolean = { false },
    ): HtdemucsSourceSeparationEngineResult {
        require(song != Song.emptySong) { "Cannot separate an empty song." }
        if (shouldCancel()) throw CancellationException("Multi-stem separation canceled.")
        val input = SourceSeparationModelAwareSongInput.from(song)
        val preflight = preflightResolver.resolve(input.sourceUri, shouldCancel)
        return execute(
            SourceSeparationMultiStemRuntimeExecutionRequest(
                song = SourceSeparationRuntimeSong.forMultiStem(
                    song = song,
                    identity = SourceSeparationCacheContractSnapshot.fromMultiTensor(
                        SourceSeparationMultiTensorExecutableContractLoader.load(
                            requireNotNull(installer.installed(modelId)) {
                                "The selected multi-stem model is not installed: $modelId"
                            }.sidecarFile.readText(),
                        ),
                    ).identity(
                        preflight.identity,
                        HtdemucsSourceSeparationEngine.HTDEMUCS_CPU_PROFILE_ID,
                    ),
                    input = input,
                    preflight = preflight,
                ),
                runClass = runClass,
                windowDecodeEnabled = windowDecodeEnabled,
                onProgress = onProgress,
                onPrepared = onPrepared,
                shouldPause = shouldPause,
                pauseReasonProvider = pauseReasonProvider,
                shouldCancel = shouldCancel,
            ),
        )
    }

    override fun execute(
        request: SourceSeparationMultiStemRuntimeExecutionRequest,
    ): HtdemucsSourceSeparationEngineResult {
        val modelId = request.song.modelId
        val installed = requireNotNull(installer.installed(modelId)) {
            "The selected multi-stem model is not installed: $modelId"
        }
        if (request.shouldCancel()) {
            throw CancellationException("Multi-stem separation canceled.")
        }
        return executionHost.separate(
            SourceSeparationMultiStemExecutionRequest(
                input = request.song.input,
                installedModel = installed,
                preflight = request.song.preflight,
                runClass = request.runClass,
                windowDecodeEnabled = request.windowDecodeEnabled,
                onProgress = request.onProgress,
                onPrepared = request.onPrepared,
                onSegmentStateChanged = { _, _ -> },
                shouldPause = request.shouldPause,
                pauseReasonProvider = request.pauseReasonProvider,
                shouldCancel = request.shouldCancel,
            ),
        )
    }
}

internal fun interface SourceSeparationMultiStemRuntimeExecutor {
    fun execute(
        request: SourceSeparationMultiStemRuntimeExecutionRequest,
    ): HtdemucsSourceSeparationEngineResult
}

internal data class SourceSeparationMultiStemRuntimeExecutionRequest(
    val song: SourceSeparationRuntimeSong,
    val runClass: SourceSeparationExecutionRunClass,
    val windowDecodeEnabled: Boolean,
    val onProgress: (MdxRangeProgress) -> Unit,
    val onPrepared: (SourceSeparationCacheManifest) -> Unit,
    val shouldPause: () -> Boolean,
    val pauseReasonProvider: () -> SourceSeparationPauseReason,
    val shouldCancel: () -> Boolean,
)
