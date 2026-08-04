package com.mardous.booming.ui.screen.player

internal data class SourceSeparationWorkerCompletionPlan(
    val syncCurrentPlayback: Boolean,
    val promoteCompletedStems: Boolean,
    val cleanTemporaryFilesNow: Boolean,
)

internal fun sourceSeparationWorkerCompletionPlan(
    acceptsCurrentPlaybackState: Boolean,
    shouldPromoteCompletedStems: Boolean,
): SourceSeparationWorkerCompletionPlan = SourceSeparationWorkerCompletionPlan(
    syncCurrentPlayback = acceptsCurrentPlaybackState,
    promoteCompletedStems = shouldPromoteCompletedStems,
    cleanTemporaryFilesNow = !shouldPromoteCompletedStems,
)
