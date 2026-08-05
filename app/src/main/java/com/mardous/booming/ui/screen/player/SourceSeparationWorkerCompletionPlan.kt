package com.mardous.booming.ui.screen.player

internal data class SourceSeparationWorkerCompletionPlan(
    val refreshCurrentCacheState: Boolean,
    val syncCurrentPlayback: Boolean,
    val promoteCompletedStems: Boolean,
    val cleanTemporaryFilesNow: Boolean,
)

internal fun sourceSeparationWorkerCompletionPlan(
    acceptsCurrentPlaybackState: Boolean,
    playWhenReady: Boolean,
    shouldPromoteCompletedStems: Boolean,
): SourceSeparationWorkerCompletionPlan = SourceSeparationWorkerCompletionPlan(
    refreshCurrentCacheState = acceptsCurrentPlaybackState,
    syncCurrentPlayback = acceptsCurrentPlaybackState && !playWhenReady,
    promoteCompletedStems = shouldPromoteCompletedStems,
    cleanTemporaryFilesNow = !shouldPromoteCompletedStems,
)
