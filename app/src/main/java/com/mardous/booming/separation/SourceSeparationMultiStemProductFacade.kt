package com.mardous.booming.separation

import com.mardous.booming.data.model.Song
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheManifest
import com.mardous.booming.separation.model.MdxRangeProgress
import com.mardous.booming.separation.model.contract.SourceSeparationInstalledMultiStemModel
import com.mardous.booming.separation.model.contract.SourceSeparationMultiStemInstallProgress
import com.mardous.booming.separation.model.contract.SourceSeparationMultiStemReleaseInstaller
import com.mardous.booming.separation.model.contract.SourceSeparationReleaseCatalog
import java.util.concurrent.CancellationException

/** Product boundary for experimental multi-stem Release models. */
internal class SourceSeparationMultiStemProductFacade(
    private val installer: SourceSeparationMultiStemReleaseInstaller,
    private val engine: HtdemucsSourceSeparationEngine,
    private val preflightResolver: SourceSeparationModelAwarePreflightResolver,
) {
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
        val installed = requireNotNull(installer.installed(modelId)) {
            "The selected multi-stem model is not installed: $modelId"
        }
        if (shouldCancel()) throw CancellationException("Multi-stem separation canceled.")
        val input = SourceSeparationModelAwareSongInput.from(song)
        val preflight = preflightResolver.resolve(input.sourceUri, shouldCancel)
        return engine.separate(
            input = input,
            installedModel = installed,
            preflight = preflight,
            runClass = runClass,
            windowDecodeEnabled = windowDecodeEnabled,
            onProgress = onProgress,
            onPrepared = onPrepared,
            shouldPause = shouldPause,
            pauseReasonProvider = pauseReasonProvider,
            shouldCancel = shouldCancel,
        )
    }
}
