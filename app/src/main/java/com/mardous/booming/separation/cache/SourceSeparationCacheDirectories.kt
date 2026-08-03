package com.mardous.booming.separation.cache

import android.content.Context
import com.mardous.booming.separation.cache.v2.AndroidSourceSeparationCacheRootProvider
import java.io.File

object SourceSeparationCacheDirectories {
    fun root(context: Context): File =
        AndroidSourceSeparationCacheRootProvider(context.applicationContext).resolveRoot().directory

    fun mp3Calibration(context: Context): File =
        File(root(context), "mp3-no-gapless-calibration")

    fun diagnostics(context: Context): File =
        File(root(context), "diagnostics")

    fun debug(context: Context): File =
        File(root(context), "debug")
}
