package com.mardous.booming.separation

import android.content.Context
import com.mardous.booming.data.model.Song
import com.mardous.booming.separation.cache.SourceSeparationCache
import com.mardous.booming.separation.model.MdxModelVariant
import com.mardous.booming.separation.model.MdxRangeProgress
import com.mardous.booming.separation.model.MdxRangeSeparationResult
import com.mardous.booming.separation.model.MdxRangeSeparator
import com.mardous.booming.separation.model.MdxRuntimeSettings

class SourceSeparationEngine(
    private val context: Context,
) {
    private val cache = SourceSeparationCache(context)

    fun separateSongToWav(
        song: Song,
        runtimeSettings: MdxRuntimeSettings = MdxRuntimeSettings(),
        modelVariant: MdxModelVariant = MdxModelVariant.MDXNET_9482,
        onProgress: (MdxRangeProgress) -> Unit = {},
    ): MdxRangeSeparationResult {
        require(song != Song.emptySong) { "Cannot separate an empty song." }
        val run = cache.beginOfflineRun(song, modelVariant)
        return try {
            MdxRangeSeparator(context)
                .separate(
                    uri = song.uri,
                    outputDir = run.workDir,
                    displayName = song.fileName,
                    runtimeSettings = runtimeSettings,
                    modelVariant = modelVariant,
                    onProgress = onProgress,
                )
                .let { result -> cache.completeRun(run, result).result }
        } catch (error: Throwable) {
            cache.failRun(run, error)
            throw error
        }
    }
}
