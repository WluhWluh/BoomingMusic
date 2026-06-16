package com.mardous.booming.separation

import android.content.Context
import com.mardous.booming.data.model.Song
import com.mardous.booming.separation.cache.SourceSeparationCache
import com.mardous.booming.separation.cache.SourceSeparationCacheState
import com.mardous.booming.separation.cache.SourceSeparationManifest
import com.mardous.booming.separation.model.MdxModelVariant
import com.mardous.booming.separation.model.MdxRangeProgress
import com.mardous.booming.separation.model.MdxRangeSeparationResult
import com.mardous.booming.separation.model.MdxRangeSeparator
import com.mardous.booming.separation.model.MdxRuntimeSettings
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

    fun playableCacheForSong(
        song: Song,
        playbackPositionMs: Long,
        modelVariant: MdxModelVariant = MdxModelVariant.MDXNET_9482,
    ): SourceSeparationManifest? {
        require(song != Song.emptySong) { "Cannot read separated cache for an empty song." }
        val manifest = cache.readPlayableForSong(song, modelVariant) ?: return null
        if (manifest.state == SourceSeparationCacheState.Completed) {
            return manifest
        }

        val snapshot = cache.readSegmentSnapshot(manifest) ?: return null
        val sampleRate = snapshot.segmentPlan.sampleRate.takeIf { it > 0 } ?: return null
        val frame = ((playbackPositionMs.coerceAtLeast(0L) * sampleRate) / 1000L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        return manifest.takeIf {
            snapshot.hasReadyPlaybackWindowAtFrame(frame)
        }
    }

    fun separateSongToWav(
        song: Song,
        runtimeSettings: MdxRuntimeSettings = MdxRuntimeSettings(),
        modelVariant: MdxModelVariant = MdxModelVariant.MDXNET_9482,
        onProgress: (MdxRangeProgress) -> Unit = {},
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
                        cache.updateRunPreparation(run, preparation)
                    },
                    onSegmentStateChanged = { segmentIndex, state ->
                        cache.updateSegmentState(run, segmentIndex, state)
                    },
                    shouldCancel = shouldCancel,
                )
                .let { result ->
                    if (shouldCancel()) {
                        throw CancellationException("Source separation canceled.")
                    }
                    cache.completeRun(run, result).result
                }
        } catch (error: CancellationException) {
            cache.cancelRun(run, error)
            throw error
        } catch (error: Throwable) {
            cache.failRun(run, error)
            throw error
        }
    }
}
