package com.mardous.booming.playback

import java.util.concurrent.CancellationException

/**
 * Keeps the compressed source as the fast path, but can replace it at a frame
 * boundary when a platform decoder fails after the factory was created.
 */
internal class SourceSeparationFallbackStemSourceFactory(
    private val primary: SourceSeparationPlaybackStemSourceFactory,
    private val fallback: SourceSeparationPlaybackStemSourceFactory,
    private val traceSink: ((String) -> Unit)? = null,
) : SourceSeparationPlaybackStemSourceFactory {
    override val spec: SourceSeparationPlaybackStemSpec = primary.spec

    override val openFileDescriptorCount: Int
        get() = maxOf(primary.openFileDescriptorCount, fallback.openFileDescriptorCount)

    // The primary AAC path is still safe to service independently per stem;
    // if it fails, the frame-boundary WAV fallback remains independently safe.
    override val fastSeekParallelism: Int
        get() = primary.fastSeekParallelism

    override val playbackBlockFrameCapacity: Int?
        get() = primary.playbackBlockFrameCapacity

    override val seekResumeBlockFrameCapacity: Int?
        get() = primary.seekResumeBlockFrameCapacity

    override val seekResumeReadyFrameCapacity: Int?
        get() = primary.seekResumeReadyFrameCapacity

    override val allowPartialSeekRead: Boolean
        get() = primary.allowPartialSeekRead

    init {
        require(primary.spec.geometry == fallback.spec.geometry) {
            "Primary and fallback playback source geometry must match."
        }
    }

    override fun open(): SourceSeparationPlaybackStemSource {
        return try {
            FallbackStemSource(
                activeSource = primary.open(),
                fallbackFactory = fallback,
                geometry = spec.geometry,
                traceSink = traceSink,
                usingFallback = false,
            )
        } catch (error: Throwable) {
            if (error is CancellationException || error is InterruptedException) throw error
            val fallbackSource = fallback.open()
            require(fallbackSource.geometry == spec.geometry) {
                "Fallback playback source geometry changed while opening."
            }
            fallbackSource.seekToFrame(0L)
            traceSink?.invoke(
                "fallback format=wav operation=open " +
                        "reason=${error::class.java.simpleName}:${error.message ?: "unknown"}",
            )
            FallbackStemSource(
                activeSource = fallbackSource,
                fallbackFactory = fallback,
                geometry = spec.geometry,
                traceSink = traceSink,
                usingFallback = true,
            )
        }
    }

    private class FallbackStemSource(
        activeSource: SourceSeparationPlaybackStemSource,
        private val fallbackFactory: SourceSeparationPlaybackStemSourceFactory,
        override val geometry: SourceSeparationPlaybackGeometry,
        private val traceSink: ((String) -> Unit)?,
        usingFallback: Boolean,
    ) : SourceSeparationPlaybackStemSource {
        private var activeSource: SourceSeparationPlaybackStemSource? = activeSource
        private var usingFallback = usingFallback
        private var closed = false

        override fun seekToFrame(frame: Long) {
            check(!closed) { "Fallback playback source is closed." }
            val source = requireNotNull(activeSource)
            if (usingFallback) {
                source.seekToFrame(frame)
                return
            }
            try {
                source.seekToFrame(frame)
            } catch (error: Throwable) {
                switchToFallback(frame, error, operation = "seek")
            }
        }

        override fun readFrames(
            startFrame: Long,
            destination: ByteArray,
            destinationOffsetBytes: Int,
            frameCount: Int,
        ): Int {
            check(!closed) { "Fallback playback source is closed." }
            val source = requireNotNull(activeSource)
            if (usingFallback) {
                return source.readFrames(
                    startFrame,
                    destination,
                    destinationOffsetBytes,
                    frameCount,
                )
            }
            try {
                val read = source.readFrames(
                    startFrame,
                    destination,
                    destinationOffsetBytes,
                    frameCount,
                )
                if (read == frameCount || frameCount == 0) return read
                switchToFallback(
                    frame = startFrame,
                    error = IllegalStateException(
                        "Primary playback source returned $read of $frameCount frames.",
                    ),
                    operation = "short-read",
                )
            } catch (error: Throwable) {
                if (error is CancellationException || error is InterruptedException) throw error
                switchToFallback(startFrame, error, operation = "read")
            }
            return requireNotNull(activeSource).readFrames(
                startFrame,
                destination,
                destinationOffsetBytes,
                frameCount,
            )
        }

        override fun close() {
            if (closed) return
            closed = true
            activeSource?.let { source -> runCatching { source.close() } }
            activeSource = null
        }

        private fun switchToFallback(frame: Long, error: Throwable, operation: String) {
            if (usingFallback) throw error
            val oldSource = requireNotNull(activeSource)
            val replacement = try {
                fallbackFactory.open().also { source ->
                    require(source.geometry == geometry) {
                        "Fallback playback source geometry changed while switching."
                    }
                    source.seekToFrame(frame)
                }
            } catch (fallbackError: Throwable) {
                error.addSuppressed(fallbackError)
                throw error
            }
            runCatching { oldSource.close() }
            activeSource = replacement
            usingFallback = true
            traceSink?.invoke(
                "fallback format=wav operation=$operation frame=$frame " +
                        "reason=${error::class.java.simpleName}:${error.message ?: "unknown"}",
            )
        }
    }
}
