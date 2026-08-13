package com.mardous.booming.separation

import com.mardous.booming.separation.cache.SourceSeparationSegmentState
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheContractSnapshot
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheManifest
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunCoordinator
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunRequest
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunStart
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheSourcePreflight
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwareCacheRun
import com.mardous.booming.separation.model.MdxRangeProgress
import com.mardous.booming.separation.model.contract.SourceSeparationInstalledMultiStemModel
import com.mardous.booming.separation.model.contract.SourceSeparationMultiTensorExecutableContractLoader
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException

/** Product lifecycle adapter for one installed, CPU-only HTDemucs Release model. */
internal class HtdemucsSourceSeparationEngine(
    private val coordinator: SourceSeparationCacheRunCoordinator,
    private val rangeExecutor: HtdemucsSourceSeparationRangeExecutorContract,
    private val runIdFactory: () -> String = { UUID.randomUUID().toString() },
    internal val processGeneration: Long = 1L,
    private val ownerPid: Int? = null,
) {
    fun separate(
        input: SourceSeparationModelAwareSongInput,
        installedModel: SourceSeparationInstalledMultiStemModel,
        preflight: SourceSeparationCacheSourcePreflight,
        runId: String = runIdFactory(),
        runClass: SourceSeparationExecutionRunClass =
            SourceSeparationExecutionRunClass.ManualFullSong,
        windowDecodeEnabled: Boolean = true,
        onProgress: (MdxRangeProgress) -> Unit = {},
        onPrepared: (SourceSeparationCacheManifest) -> Unit = {},
        onSegmentStateChanged: (Int, SourceSeparationSegmentState) -> Unit = { _, _ -> },
        playbackPositionMsProvider: () -> Long? = { null },
        playbackReadyWindowCountProvider: () -> Int = { 2 },
        shouldPause: () -> Boolean = { false },
        pauseReasonProvider: () -> SourceSeparationPauseReason = {
            SourceSeparationPauseReason.Standard
        },
        shouldCancel: () -> Boolean = { false },
    ): HtdemucsSourceSeparationEngineResult {
        check(input.sourceUri.isNotBlank()) { "Source URI is empty." }
        check(processGeneration > 0L) { "Process generation is invalid." }
        require(installedModel.modelFile.isFile && installedModel.sidecarFile.isFile) {
            "Installed HTDemucs model files are unavailable."
        }
        require(installedModel.modelFile.length() == installedModel.modelByteSize) {
            "Installed HTDemucs model size changed after installation."
        }
        val executable = installedModel.sidecarFile.bufferedReader().use { reader ->
            SourceSeparationMultiTensorExecutableContractLoader.load(reader.readText())
        }
        require(installedModel.modelId == executable.modelContract.modelId &&
            installedModel.contractId == executable.modelContract.contractId &&
            installedModel.modelFile.name == executable.artifact.fileName &&
            installedModel.modelByteSize == executable.artifact.byteSize &&
            installedModel.modelSha256.equals(executable.artifact.sha256, ignoreCase = true)
        ) { "Installed HTDemucs model no longer matches its executable contract." }
        val contract = SourceSeparationCacheContractSnapshot.fromMultiTensor(executable)
        val identity = contract.identity(preflight.identity, HTDEMUCS_CPU_PROFILE_ID)
        require(runId.isNotBlank()) { "Multi-stem execution run ID is empty." }
        val request = SourceSeparationCacheRunRequest(
            identity = identity,
            contract = contract,
            song = input.song,
            sourceDiagnostics = input.sourceDiagnostics,
            runClass = runClass,
            backgroundPolicy = runClass.backgroundPolicy,
            tryGpu = false,
            gpuRuntimeIdentity = null,
            gpuFallbackLatch = null,
            runId = runId,
            processGeneration = processGeneration,
            ownerPid = ownerPid,
        )
        return when (val start = coordinator.begin(request)) {
            SourceSeparationCacheRunStart.Busy ->
                HtdemucsSourceSeparationEngineResult.Busy(identity.cacheKey)
            is SourceSeparationCacheRunStart.AlreadyCompleted ->
                HtdemucsSourceSeparationEngineResult.AlreadyCompleted(start.manifest)
            is SourceSeparationCacheRunStart.Ready -> execute(
                run = start.run,
                input = input,
                installedModel = installedModel,
                identity = identity,
                windowDecodeEnabled = windowDecodeEnabled,
                onProgress = onProgress,
                onPrepared = onPrepared,
                onSegmentStateChanged = onSegmentStateChanged,
                playbackPositionMsProvider = playbackPositionMsProvider,
                playbackReadyWindowCountProvider = playbackReadyWindowCountProvider,
                shouldPause = shouldPause,
                pauseReasonProvider = pauseReasonProvider,
                shouldCancel = shouldCancel,
            )
        }
    }

    private fun execute(
        run: SourceSeparationModelAwareCacheRun,
        input: SourceSeparationModelAwareSongInput,
        installedModel: SourceSeparationInstalledMultiStemModel,
        identity: com.mardous.booming.separation.cache.v2.SourceSeparationCacheIdentity,
        windowDecodeEnabled: Boolean,
        onProgress: (MdxRangeProgress) -> Unit,
        onPrepared: (SourceSeparationCacheManifest) -> Unit,
        onSegmentStateChanged: (Int, SourceSeparationSegmentState) -> Unit,
        playbackPositionMsProvider: () -> Long?,
        playbackReadyWindowCountProvider: () -> Int,
        shouldPause: () -> Boolean,
        pauseReasonProvider: () -> SourceSeparationPauseReason,
        shouldCancel: () -> Boolean,
    ): HtdemucsSourceSeparationEngineResult {
        var terminal: Throwable? = null
        return try {
            val result = rangeExecutor.separate(
                HtdemucsSourceSeparationRangeRequest(
                    sourceUri = input.sourceUri,
                    displayName = input.displayName,
                    installedModel = installedModel,
                    workDirectory = run.workDirectory,
                    segmentsDirectory = run.segmentsDirectory,
                    expectedSourceAudioFingerprint = identity.source.audioFingerprint,
                    windowDecodeEnabled = windowDecodeEnabled,
                    resumeState = run.htdemucsResumeState,
                    onPrepared = { preparation ->
                        onPrepared(coordinator.updatePreparation(run, preparation))
                    },
                    onSegmentStateChanged = { index, state ->
                        coordinator.updateSegmentState(run, index, state)
                        onSegmentStateChanged(index, state)
                    },
                    onProgress = onProgress,
                    playbackPositionMsProvider = playbackPositionMsProvider,
                    playbackReadyWindowCountProvider = playbackReadyWindowCountProvider,
                    shouldPause = shouldPause,
                    pauseReasonProvider = pauseReasonProvider,
                    shouldCancel = shouldCancel,
                    requireWorkspaceAvailable = run::requireOpen,
                ),
            )
            HtdemucsSourceSeparationEngineResult.Completed(
                coordinator.complete(run, result.completion),
            )
        } catch (error: SourceSeparationPausedException) {
            terminal = error
            coordinator.pause(run, error.pauseReason)
            throw error
        } catch (error: CancellationException) {
            terminal = error
            coordinator.cancel(run, error)
            throw error
        } catch (error: Throwable) {
            terminal = error
            coordinator.fail(run, error)
            throw error
        } finally {
            if (terminal == null) run.close()
        }
    }

    companion object {
        const val HTDEMUCS_CPU_PROFILE_ID = "htdemucs-cpu-fp32-v1"
    }
}

internal sealed class HtdemucsSourceSeparationEngineResult {
    data class Completed(val manifest: SourceSeparationCacheManifest) :
        HtdemucsSourceSeparationEngineResult()
    data class AlreadyCompleted(val manifest: SourceSeparationCacheManifest) :
        HtdemucsSourceSeparationEngineResult()
    data class Busy(val cacheKey: String) : HtdemucsSourceSeparationEngineResult()
}
