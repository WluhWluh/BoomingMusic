package com.mardous.booming.playback

import com.mardous.booming.separation.audio.Pcm16StereoFlacEncoder
import com.mardous.booming.separation.audio.Pcm16StereoFlacPcmReader
import java.io.File

internal class SourceSeparationFlacStemSourceFactory(
    private val file: File,
    stemId: String,
    private val traceSink: ((String) -> Unit)? = null,
) : SourceSeparationPlaybackStemSourceFactory {
    private val indexedInfo = requireNotNull(
        Pcm16StereoFlacEncoder.inspectIndexedPcm(file),
    ) {
        "Indexed FLAC playback metadata is missing or invalid: ${file.absolutePath}"
    }

    override val spec = SourceSeparationPlaybackStemSpec(
        stemId = stemId,
        geometry = SourceSeparationPlaybackGeometry(
            sampleRate = indexedInfo.sampleRate,
            channelCount = indexedInfo.channelCount,
            frameCount = indexedInfo.frameCount.toLong(),
        ),
    )

    init {
        require(indexedInfo.channelCount == 2) {
            "Only stereo FLAC playback stems are supported."
        }
        require(indexedInfo.bitsPerSample == 16) {
            "Only 16-bit FLAC playback stems are supported."
        }
    }

    override fun open(): SourceSeparationPlaybackStemSource {
        val reader = requireNotNull(
            Pcm16StereoFlacEncoder.openIndexedPcmReader(file, traceSink),
        ) {
            "Indexed FLAC playback could not be opened: ${file.absolutePath}"
        }
        return FlacStemSource(reader, spec.geometry)
    }

    private class FlacStemSource(
        private val reader: Pcm16StereoFlacPcmReader,
        override val geometry: SourceSeparationPlaybackGeometry,
    ) : SourceSeparationPlaybackStemSource {
        override fun readFrames(
            startFrame: Long,
            destination: ByteArray,
            destinationOffsetBytes: Int,
            frameCount: Int,
        ): Int {
            require(startFrame >= 0L) { "Playback source frame must not be negative." }
            require(frameCount >= 0) { "Playback source read frame count must not be negative." }
            if (frameCount == 0 || startFrame >= geometry.frameCount) return 0
            require(startFrame + frameCount <= geometry.frameCount) {
                "Playback source read exceeds the declared frame count."
            }
            val byteCount = frameCount * BYTES_PER_FRAME
            require(destinationOffsetBytes >= 0 &&
                    destinationOffsetBytes + byteCount <= destination.size
            ) {
                "Playback source destination is too small."
            }
            reader.seekToPcmByte(startFrame * BYTES_PER_FRAME)
            var copied = 0
            while (copied < byteCount) {
                val read = reader.read(
                    buffer = destination,
                    byteCount = byteCount - copied,
                )
                if (read <= 0) {
                    throw IllegalStateException("Indexed FLAC source ended before the declared frame count.")
                }
                copied += read
            }
            return frameCount
        }

        override fun close() {
            reader.close()
        }
    }

    private companion object {
        const val BYTES_PER_FRAME = 4
    }
}
