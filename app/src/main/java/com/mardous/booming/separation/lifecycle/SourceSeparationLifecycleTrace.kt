package com.mardous.booming.separation.lifecycle

internal enum class SourceSeparationLifecycleStopReason(val traceValue: String) {
    ActiveModelSuperseded("active-model-superseded"),
    PlaybackSongChanged("playback-song-changed"),
    PlaybackStopped("playback-stopped"),
    UserPaused("user-paused"),
    UserCanceled("user-canceled"),
    CacheDeleted("cache-deleted"),
}

internal object SourceSeparationLifecycleTrace {
    fun format(
        event: String,
        selectionGeneration: Long? = null,
        cacheKey: String? = null,
        requestGeneration: Long? = null,
        runId: String? = null,
        stopReason: SourceSeparationLifecycleStopReason? = null,
    ): String {
        require(event.isNotBlank()) { "Lifecycle trace event is empty." }
        require(selectionGeneration == null || selectionGeneration >= 0L) {
            "Selection generation is invalid."
        }
        require(requestGeneration == null || requestGeneration > 0L) {
            "Request generation is invalid."
        }
        return buildList {
            add("event=$event")
            selectionGeneration?.let { add("selectionGeneration=$it") }
            cacheKey?.let { add("cache=${it.shortTraceIdentity()}") }
            requestGeneration?.let { add("requestGeneration=$it") }
            runId?.let { add("run=${it.shortTraceIdentity()}") }
            stopReason?.let { add("stop=${it.traceValue}") }
        }.joinToString(" ")
    }

    private fun String.shortTraceIdentity(): String = take(12)
}
