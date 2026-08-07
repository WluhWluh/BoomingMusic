package com.mardous.booming.separation.model

/** Reuses the established source decoder and its unchanged window policies. */
internal class HtdemucsMdxTrackSource(
    private val source: MdxSourceInput,
    private val timing: MdxRangeTimingAccumulator,
) : HtdemucsTrackSource {
    override val frameCount: Int
        get() = source.outputFrameCount

    val diagnostics: MdxSourceDecodeDiagnostics
        get() = source.diagnostics

    override fun readPlanarStereo(
        startFrame: Int,
        frameCount: Int,
        shouldCancel: () -> Boolean,
    ): FloatArray {
        val channels = source.toStereoFloatContextWindow(
            windowStartFrame = startFrame,
            frames = frameCount,
            timing = timing,
            shouldCancel = shouldCancel,
        )
        require(channels.size == HtdemucsPipelineAdapter.CHANNEL_COUNT &&
            channels.all { it.size == frameCount }
        ) { "Source decoder returned unexpected stereo geometry." }
        return FloatArray(frameCount * HtdemucsPipelineAdapter.CHANNEL_COUNT).also { planar ->
            channels.forEachIndexed { channel, values ->
                values.copyInto(planar, destinationOffset = channel * frameCount)
            }
        }
    }

    fun sourceAudioFingerprint(shouldCancel: () -> Boolean): String =
        source.sourceAudioFingerprint(timing, shouldCancel)
}
