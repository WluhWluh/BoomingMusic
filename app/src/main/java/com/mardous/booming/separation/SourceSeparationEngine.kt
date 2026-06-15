package com.mardous.booming.separation

import android.content.Context
import android.os.Environment
import com.mardous.booming.data.model.Song
import com.mardous.booming.separation.model.MdxModelVariant
import com.mardous.booming.separation.model.MdxRangeProgress
import com.mardous.booming.separation.model.MdxRangeSeparationResult
import com.mardous.booming.separation.model.MdxRangeSeparator
import com.mardous.booming.separation.model.MdxRuntimeSettings
import java.io.File

class SourceSeparationEngine(
    private val context: Context,
) {
    fun separateSongToWav(
        song: Song,
        runtimeSettings: MdxRuntimeSettings = MdxRuntimeSettings(),
        modelVariant: MdxModelVariant = MdxModelVariant.MDXNET_9482,
        onProgress: (MdxRangeProgress) -> Unit = {},
    ): MdxRangeSeparationResult {
        require(song != Song.emptySong) { "Cannot separate an empty song." }
        return MdxRangeSeparator(context)
            .separate(
                uri = song.uri,
                outputDir = offlineOutputDir(),
                displayName = song.fileName,
                runtimeSettings = runtimeSettings,
                modelVariant = modelVariant,
                onProgress = onProgress,
            )
    }

    private fun offlineOutputDir(): File {
        val musicDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
        return File(musicDir, "source-separation/offline").apply { mkdirs() }
    }
}
