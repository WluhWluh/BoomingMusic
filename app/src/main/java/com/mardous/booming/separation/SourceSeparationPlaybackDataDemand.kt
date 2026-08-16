package com.mardous.booming.separation

internal class SourceSeparationPlaybackDataDemand {
    private var mediaId: String? = null
    private var enteredSeparatedOutput = false

    fun moveToMedia(mediaId: String?) {
        if (this.mediaId == mediaId) return
        this.mediaId = mediaId
        enteredSeparatedOutput = false
    }

    fun observeMix(requiresSeparatedOutput: Boolean) {
        if (requiresSeparatedOutput) {
            enteredSeparatedOutput = true
        }
    }

    fun reset() {
        mediaId = null
        enteredSeparatedOutput = false
    }

    fun requiresSeparatedData(playbackRequested: Boolean): Boolean =
        playbackRequested && enteredSeparatedOutput
}
