package com.mardous.booming.separation

import android.content.Context
import android.os.Environment
import com.mardous.booming.data.model.Song
import com.mardous.booming.separation.audio.AudioWindowDecodeExperiment
import com.mardous.booming.separation.audio.AudioWindowDecodeExperimentProgress
import com.mardous.booming.separation.audio.AudioWindowDecodeExperimentResult
import com.mardous.booming.separation.cache.SourceSeparationCache
import com.mardous.booming.separation.cache.SourceSeparationCacheState
import com.mardous.booming.separation.cache.SourceSeparationManifest
import com.mardous.booming.separation.model.MdxModelVariant
import com.mardous.booming.separation.model.MdxRangeProgress
import com.mardous.booming.separation.model.MdxRangeSeparationResult
import com.mardous.booming.separation.model.MdxRangeSeparator
import com.mardous.booming.separation.model.MdxRuntimeSettings
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

class SourceSeparationEngine(
    private val context: Context,
) {
    private val cache = SourceSeparationCache(context)

    fun completedCacheForSong(
        song: Song,
        modelVariant: MdxModelVariant = MdxModelVariant.MDXNET_9482,
    ): SourceSeparationManifest? {
        require(song != Song.emptySong) { "Cannot read separated cache for an empty song." }
        return cache.readCompletedForSong(song, modelVariant)
    }

    fun hasCacheForSong(
        song: Song,
        modelVariant: MdxModelVariant = MdxModelVariant.MDXNET_9482,
    ): Boolean {
        require(song != Song.emptySong) { "Cannot read separated cache for an empty song." }
        return cache.hasEntry(song, modelVariant)
    }

    fun cacheStatusForSong(
        song: Song,
        modelVariant: MdxModelVariant = MdxModelVariant.MDXNET_9482,
    ): SourceSeparationCacheStatus {
        require(song != Song.emptySong) { "Cannot read separated cache for an empty song." }
        val manifest = cache.readEntry(song, modelVariant)
            ?: return SourceSeparationCacheStatus.NotStarted
        return when (manifest.state) {
            SourceSeparationCacheState.Running -> {
                val snapshot = cache.readSegmentSnapshot(manifest)
                SourceSeparationCacheStatus.Partial(
                    readySegments = snapshot?.readyCount ?: 0,
                    totalSegments = snapshot?.totalCount ?: manifest.segmentPlan?.segmentCount ?: 0,
                )
            }
            SourceSeparationCacheState.Completed -> {
                val canPromoteCompletedStems = cache.canPromoteCompletedStemsForSong(
                    song = song,
                    modelVariant = modelVariant,
                )
                if (cache.hasPendingCompletedTemporaryDirs(manifest)) {
                    SourceSeparationCacheStatus.CompletedWithTemporaryFiles(
                        canPromoteCompletedStems = canPromoteCompletedStems,
                    )
                } else {
                    SourceSeparationCacheStatus.Completed(
                        canPromoteCompletedStems = canPromoteCompletedStems,
                    )
                }
            }
            SourceSeparationCacheState.Canceled,
            SourceSeparationCacheState.Failed -> SourceSeparationCacheStatus.NotStarted
        }
    }

    fun canPromoteCompletedStemsForSong(
        song: Song,
        modelVariant: MdxModelVariant = MdxModelVariant.MDXNET_9482,
    ): Boolean {
        require(song != Song.emptySong) { "Cannot read separated cache for an empty song." }
        return cache.canPromoteCompletedStemsForSong(song, modelVariant)
    }

    fun promoteCompletedStemsForSong(
        song: Song,
        modelVariant: MdxModelVariant = MdxModelVariant.MDXNET_9482,
        shouldCancel: () -> Boolean = { false },
    ): SourceSeparationManifest? {
        require(song != Song.emptySong) { "Cannot promote separated cache for an empty song." }
        return cache.promoteCompletedStemsForSong(
            song = song,
            modelVariant = modelVariant,
            shouldCancel = shouldCancel,
        )
    }

    fun deleteCacheForSong(
        song: Song,
        modelVariant: MdxModelVariant = MdxModelVariant.MDXNET_9482,
    ): Boolean {
        require(song != Song.emptySong) { "Cannot delete separated cache for an empty song." }
        return cache.deleteEntry(song, modelVariant)
    }

    fun cleanCompletedTemporaryDirs(
        manifest: SourceSeparationManifest,
        activeFiles: Set<String> = emptySet(),
    ): Boolean {
        return cache.cleanCompletedTemporaryDirs(manifest, activeFiles)
    }

    fun cleanPendingCompletedTemporaryDirs(activeFiles: Set<String> = emptySet()): Int {
        return cache.cleanPendingCompletedTemporaryDirs(activeFiles)
    }

    fun playableCacheForSong(
        song: Song,
        playbackPositionMs: Long,
        modelVariant: MdxModelVariant = MdxModelVariant.MDXNET_9482,
        readyWindowCount: Int = DEFAULT_PLAYBACK_READY_WINDOW_COUNT,
    ): SourceSeparationManifest? {
        return when (val status = playableCacheStatusForSong(
            song = song,
            playbackPositionMs = playbackPositionMs,
            modelVariant = modelVariant,
            readyWindowCount = readyWindowCount,
        )) {
            is SourceSeparationPlayableCacheStatus.Ready -> status.manifest
            SourceSeparationPlayableCacheStatus.Processing,
            SourceSeparationPlayableCacheStatus.Unavailable -> null
        }
    }

    fun separatedPlaybackBlendForSong(
        song: Song,
        modelVariant: MdxModelVariant = MdxModelVariant.MDXNET_9482,
    ): Float? {
        require(song != Song.emptySong) { "Cannot read separated playback settings for an empty song." }
        return cache.readPlaybackSettings(song, modelVariant)?.blend
    }

    fun saveSeparatedPlaybackBlendForSong(
        song: Song,
        blend: Float,
        modelVariant: MdxModelVariant = MdxModelVariant.MDXNET_9482,
    ): Boolean {
        require(song != Song.emptySong) { "Cannot save separated playback settings for an empty song." }
        return cache.writePlaybackSettings(song, modelVariant, blend) != null
    }

    fun playableCacheStatusForSong(
        song: Song,
        playbackPositionMs: Long,
        modelVariant: MdxModelVariant = MdxModelVariant.MDXNET_9482,
        readyWindowCount: Int = DEFAULT_PLAYBACK_READY_WINDOW_COUNT,
    ): SourceSeparationPlayableCacheStatus {
        require(song != Song.emptySong) { "Cannot read separated cache for an empty song." }
        val manifest = cache.readEntry(song, modelVariant)
            ?: return SourceSeparationPlayableCacheStatus.Unavailable
        if (manifest.state == SourceSeparationCacheState.Completed) {
            return if (manifest.hasUsableOutputFiles()) {
                SourceSeparationPlayableCacheStatus.Ready(manifest)
            } else {
                SourceSeparationPlayableCacheStatus.Unavailable
            }
        }
        if (manifest.state != SourceSeparationCacheState.Running) {
            return SourceSeparationPlayableCacheStatus.Unavailable
        }
        if (!manifest.hasUsableOutputFiles()) {
            return SourceSeparationPlayableCacheStatus.Processing
        }

        val snapshot = cache.readSegmentSnapshot(manifest)
            ?: return SourceSeparationPlayableCacheStatus.Processing
        val sampleRate = snapshot.segmentPlan.sampleRate.takeIf { it > 0 }
            ?: return SourceSeparationPlayableCacheStatus.Processing
        val frame = ((playbackPositionMs.coerceAtLeast(0L) * sampleRate) / 1000L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        return if (snapshot.hasReadyPlaybackWindowAtFrame(frame, readyWindowCount)) {
            SourceSeparationPlayableCacheStatus.Ready(manifest)
        } else {
            SourceSeparationPlayableCacheStatus.Processing
        }
    }

    fun playableCacheDebugInfoForSong(
        song: Song,
        playbackPositionMs: Long,
        modelVariant: MdxModelVariant = MdxModelVariant.MDXNET_9482,
        readyWindowCount: Int = DEFAULT_PLAYBACK_READY_WINDOW_COUNT,
    ): SourceSeparationPlayableCacheDebugInfo {
        require(song != Song.emptySong) { "Cannot read separated cache for an empty song." }
        val manifest = cache.readEntry(song, modelVariant)
            ?: return SourceSeparationPlayableCacheDebugInfo(
                status = "Unavailable",
                note = "manifestMissing",
            )
        val output = manifest.output
        val vocalsFile = output?.playbackVocalsPath()?.let(::File)
        val instrumentalFile = output?.playbackInstrumentalPath()?.let(::File)
        val outputReady = vocalsFile?.isFile == true && instrumentalFile?.isFile == true

        if (manifest.state == SourceSeparationCacheState.Completed) {
            return SourceSeparationPlayableCacheDebugInfo(
                status = if (outputReady) "Ready" else "Unavailable",
                manifestState = manifest.state,
                manifestUpdatedAtEpochMs = manifest.updatedAtEpochMs,
                outputReady = outputReady,
                vocalsLength = vocalsFile?.length(),
                instrumentalLength = instrumentalFile?.length(),
                note = if (outputReady) "completed" else "completedOutputMissing",
            )
        }
        if (manifest.state != SourceSeparationCacheState.Running) {
            return SourceSeparationPlayableCacheDebugInfo(
                status = "Unavailable",
                manifestState = manifest.state,
                manifestUpdatedAtEpochMs = manifest.updatedAtEpochMs,
                outputReady = outputReady,
                vocalsLength = vocalsFile?.length(),
                instrumentalLength = instrumentalFile?.length(),
                note = "notRunningOrCompleted",
            )
        }
        if (!outputReady) {
            return SourceSeparationPlayableCacheDebugInfo(
                status = "Processing",
                manifestState = manifest.state,
                manifestUpdatedAtEpochMs = manifest.updatedAtEpochMs,
                outputReady = false,
                vocalsLength = vocalsFile?.length(),
                instrumentalLength = instrumentalFile?.length(),
                note = "runningOutputMissing",
            )
        }

        val snapshot = cache.readSegmentSnapshot(manifest)
            ?: return SourceSeparationPlayableCacheDebugInfo(
                status = "Processing",
                manifestState = manifest.state,
                manifestUpdatedAtEpochMs = manifest.updatedAtEpochMs,
                outputReady = true,
                vocalsLength = vocalsFile.length(),
                instrumentalLength = instrumentalFile.length(),
                note = "segmentSnapshotMissing",
            )
        val sampleRate = snapshot.segmentPlan.sampleRate.takeIf { it > 0 }
            ?: return SourceSeparationPlayableCacheDebugInfo(
                status = "Processing",
                manifestState = manifest.state,
                manifestUpdatedAtEpochMs = manifest.updatedAtEpochMs,
                outputReady = true,
                vocalsLength = vocalsFile.length(),
                instrumentalLength = instrumentalFile.length(),
                note = "invalidSampleRate",
            )
        val frame = ((playbackPositionMs.coerceAtLeast(0L) * sampleRate) / 1000L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        val segmentIndex = snapshot.segmentPlan.segmentIndexForFrame(frame)
        val playbackWindowStates = snapshot.playbackWindowStatesAt(segmentIndex, readyWindowCount)
        val ready = playbackWindowStates.all { it.isReady }
        return SourceSeparationPlayableCacheDebugInfo(
            status = if (ready) "Ready" else "Processing",
            manifestState = manifest.state,
            manifestUpdatedAtEpochMs = manifest.updatedAtEpochMs,
            outputReady = true,
            vocalsLength = vocalsFile.length(),
            instrumentalLength = instrumentalFile.length(),
            frame = frame,
            sampleRate = sampleRate,
            segmentIndex = segmentIndex,
            currentSegment = snapshot.segments.getOrNull(segmentIndex)?.toDebugInfo(),
            nextSegment = snapshot.segments.getOrNull(segmentIndex + 1)?.toDebugInfo(),
            requiredWindowCount = playbackWindowStates.size,
            requiredReadyCount = playbackWindowStates.count { it.isReady },
            readyCount = snapshot.readyCount,
            totalCount = snapshot.totalCount,
            note = if (ready) "requiredPlaybackWindowsReady" else "requiredPlaybackWindowsNotReady",
        )
    }

    fun separateSongToWav(
        song: Song,
        runtimeSettings: MdxRuntimeSettings = MdxRuntimeSettings(),
        modelVariant: MdxModelVariant = MdxModelVariant.MDXNET_9482,
        promoteCompletedStems: Boolean = true,
        onProgress: (MdxRangeProgress) -> Unit = {},
        onPrepared: (SourceSeparationManifest) -> Unit = {},
        playbackPositionMsProvider: () -> Long? = { null },
        playbackReadyWindowCountProvider: () -> Int = { DEFAULT_PLAYBACK_READY_WINDOW_COUNT },
        shouldPause: () -> Boolean = { false },
        shouldCancel: () -> Boolean = { false },
    ): MdxRangeSeparationResult {
        require(song != Song.emptySong) { "Cannot separate an empty song." }
        val run = cache.beginOfflineRun(song, modelVariant)
        return try {
            if (shouldCancel()) {
                throw CancellationException("Source separation canceled.")
            }
            MdxRangeSeparator(context)
                .separate(
                    uri = song.uri,
                    outputDir = run.workDir,
                    segmentOutputDir = run.segmentsDir,
                    displayName = song.fileName,
                    runtimeSettings = runtimeSettings,
                    modelVariant = modelVariant,
                    onProgress = onProgress,
                    onPrepared = { preparation ->
                        cache.updateRunPreparation(run, preparation)?.let(onPrepared)
                    },
                    onSegmentStateChanged = { segmentIndex, state ->
                        cache.updateSegmentState(run, segmentIndex, state)
                    },
                    playbackPositionMsProvider = playbackPositionMsProvider,
                    playbackReadyWindowCountProvider = playbackReadyWindowCountProvider,
                    resumeManifest = run.resumeManifest,
                    shouldPause = shouldPause,
                    shouldCancel = shouldCancel,
                )
                .let { result ->
                    if (shouldCancel()) {
                        throw CancellationException("Source separation canceled.")
                    }
                    cache.completeRun(
                        run = run,
                        result = result,
                        shouldPromoteCompletedStems = promoteCompletedStems,
                    ).result
                }
        } catch (error: CancellationException) {
            if (error is SourceSeparationPausedException) {
                cache.pauseRun(run)
            } else {
                cache.cancelRun(run, error)
            }
            throw error
        } catch (error: Throwable) {
            cache.failRun(run, error)
            throw error
        }
    }

    fun runWindowDecodeExperiment(
        song: Song,
        playbackPositionMs: Long,
        onProgress: (AudioWindowDecodeExperimentProgress) -> Unit = {},
        shouldCancel: () -> Boolean = { false },
    ): AudioWindowDecodeExperimentResult {
        require(song != Song.emptySong) { "Cannot test window decoding for an empty song." }
        val reportDir = File(
            File(
                context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir,
                "source-separation",
            ),
            "debug",
        )
        return AudioWindowDecodeExperiment(context).run(
            uri = song.uri,
            displayName = song.fileName,
            playbackPositionMs = playbackPositionMs,
            reportDir = reportDir,
            onProgress = onProgress,
            shouldCancel = shouldCancel,
        )
    }
}

class SourceSeparationPausedException : CancellationException("Source separation paused.")

sealed class SourceSeparationCacheStatus {
    data object NotStarted : SourceSeparationCacheStatus()
    data class Partial(
        val readySegments: Int,
        val totalSegments: Int,
    ) : SourceSeparationCacheStatus()
    data class CompletedWithTemporaryFiles(
        val canPromoteCompletedStems: Boolean,
    ) : SourceSeparationCacheStatus()
    data class Completed(
        val canPromoteCompletedStems: Boolean,
    ) : SourceSeparationCacheStatus()
}

sealed class SourceSeparationPlayableCacheStatus {
    data class Ready(val manifest: SourceSeparationManifest) : SourceSeparationPlayableCacheStatus()
    data object Processing : SourceSeparationPlayableCacheStatus()
    data object Unavailable : SourceSeparationPlayableCacheStatus()
}

data class SourceSeparationPlayableCacheDebugInfo(
    val status: String,
    val manifestState: SourceSeparationCacheState? = null,
    val manifestUpdatedAtEpochMs: Long? = null,
    val outputReady: Boolean = false,
    val vocalsLength: Long? = null,
    val instrumentalLength: Long? = null,
    val frame: Int? = null,
    val sampleRate: Int? = null,
    val segmentIndex: Int? = null,
    val currentSegment: SourceSeparationSegmentDebugInfo? = null,
    val nextSegment: SourceSeparationSegmentDebugInfo? = null,
    val requiredWindowCount: Int? = null,
    val requiredReadyCount: Int? = null,
    val readyCount: Int? = null,
    val totalCount: Int? = null,
    val note: String? = null,
) {
    fun toTraceString(): String {
        return buildString {
            append("cacheStatus=").append(status)
            append(" manifestState=").append(manifestState)
            append(" outputReady=").append(outputReady)
            append(" vocalBytes=").append(vocalsLength)
            append(" instrumentalBytes=").append(instrumentalLength)
            append(" frame=").append(frame)
            append(" sampleRate=").append(sampleRate)
            append(" segmentIndex=").append(segmentIndex)
            append(" current=").append(currentSegment?.toTraceString())
            append(" next=").append(nextSegment?.toTraceString())
            append(" requiredReady=").append(requiredReadyCount).append('/').append(requiredWindowCount)
            append(" ready=").append(readyCount).append('/').append(totalCount)
            append(" updatedAt=").append(manifestUpdatedAtEpochMs)
            append(" note=").append(note)
        }
    }
}

data class SourceSeparationSegmentDebugInfo(
    val index: Int,
    val state: String,
    val playbackStartFrame: Int,
    val playbackEndFrame: Int,
    val vocalsReady: Boolean,
    val instrumentalReady: Boolean,
    val vocalsLength: Long,
    val instrumentalLength: Long,
) {
    fun toTraceString(): String {
        return "index=$index,state=$state,start=$playbackStartFrame,end=$playbackEndFrame," +
                "vocalsReady=$vocalsReady,instrumentalReady=$instrumentalReady," +
                "vocalsBytes=$vocalsLength,instrumentalBytes=$instrumentalLength"
    }
}

private fun SourceSeparationManifest.hasUsableOutputFiles(): Boolean {
    val output = output ?: return false
    return File(output.playbackVocalsPath()).isFile &&
            File(output.playbackInstrumentalPath()).isFile
}

private fun com.mardous.booming.separation.cache.SourceSeparationSegmentFileState.toDebugInfo(): SourceSeparationSegmentDebugInfo {
    return SourceSeparationSegmentDebugInfo(
        index = segment.index,
        state = state.name,
        playbackStartFrame = segment.playbackStartFrame,
        playbackEndFrame = segment.playbackEndFrame,
        vocalsReady = vocalsReady,
        instrumentalReady = instrumentalReady,
        vocalsLength = vocalsFile.length(),
        instrumentalLength = instrumentalFile.length(),
    )
}

private const val DEFAULT_PLAYBACK_READY_WINDOW_COUNT = 2
