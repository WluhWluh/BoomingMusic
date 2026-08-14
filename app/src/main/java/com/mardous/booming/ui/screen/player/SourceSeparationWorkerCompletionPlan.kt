package com.mardous.booming.ui.screen.player

internal data class SourceSeparationWorkerCompletionPlan(
    val refreshCurrentCacheState: Boolean,
    val clearCurrentPausePendingAction: Boolean,
    val syncCurrentPlayback: Boolean,
    val promoteCompletedStems: Boolean,
    val cleanTemporaryFilesNow: Boolean,
)

internal fun sourceSeparationWorkerCompletionPlan(
    isCurrentSong: Boolean,
    acceptsCurrentPlaybackState: Boolean,
    playWhenReady: Boolean,
    shouldPromoteCompletedStems: Boolean,
): SourceSeparationWorkerCompletionPlan = SourceSeparationWorkerCompletionPlan(
    refreshCurrentCacheState = isCurrentSong,
    clearCurrentPausePendingAction = acceptsCurrentPlaybackState,
    syncCurrentPlayback = acceptsCurrentPlaybackState && !playWhenReady,
    promoteCompletedStems = shouldPromoteCompletedStems,
    cleanTemporaryFilesNow = !shouldPromoteCompletedStems,
)
