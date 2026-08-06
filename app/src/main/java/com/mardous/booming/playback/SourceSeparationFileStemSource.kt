package com.mardous.booming.playback

import com.mardous.booming.separation.audio.Pcm16WavFileReader
import java.io.File
import java.io.RandomAccessFile

internal class SourceSeparationFileStemSourceFactory(
    private val file: File,
    stemId: String,
    sampleRate: Int,
    channelCount: Int = DEFAULT_CHANNEL_COUNT,
) : SourceSeparationPlaybackStemSourceFactory {
    private val wavInfo = if (file.extension.equals("wav", ignoreCase = true)) {
        Pcm16WavFileReader.read(file)
    } else {
        null
    }
    private val dataOffsetBytes = wavInfo?.dataOffset ?: 0L
    private val bytesPerFrame = channelCount * BYTES_PER_SAMPLE
    private val dataBytes = wavInfo?.dataSize ?: file.length()

    override val spec = SourceSeparationPlaybackStemSpec(
        stemId = stemId,
        geometry = SourceSeparationPlaybackGeometry(
            sampleRate = sampleRate,
            channelCount = channelCount,
            frameCount = dataBytes / bytesPerFrame,
        ),
    )

    init {
        require(file.isFile) { "Playback stem file does not exist: ${file.absolutePath}" }
        require(file.extension.equals("wav", ignoreCase = true) ||
                file.extension.equals("pcm", ignoreCase = true)
        ) {
            "Unsupported file-backed playback stem: ${file.name}"
        }
        wavInfo?.let { info ->
            require(info.sampleRate == sampleRate) {
                "Playback WAV sample rate does not match the separation contract."
            }
            require(info.channelCount == channelCount) {
                "Playback WAV channel count does not match the separation contract."
            }
        }
        require(file.length() >= dataOffsetBytes) {
            "Playback stem file is shorter than its header: ${file.absolutePath}"
        }
        require(dataBytes % bytesPerFrame == 0L) {
            "Playback stem file has a partial audio frame: ${file.absolutePath}"
        }
    }

    override fun open(): SourceSeparationPlaybackStemSource {
        return FileStemSource(
            file = file,
            geometry = spec.geometry,
            dataOffsetBytes = dataOffsetBytes,
        )
    }

    private class FileStemSource(
        private val file: File,
        override val geometry: SourceSeparationPlaybackGeometry,
        private val dataOffsetBytes: Long,
    ) : SourceSeparationPlaybackStemSource {
        private val input = RandomAccessFile(file, "r")
        private val bytesPerFrame = geometry.channelCount * BYTES_PER_SAMPLE
        private var nextFrame = -1L

        override fun seekToFrame(frame: Long) {
            val targetFrame = frame.coerceIn(0L, geometry.frameCount)
            if (targetFrame == nextFrame) return
            input.seek(dataOffsetBytes + targetFrame * bytesPerFrame)
            nextFrame = targetFrame
        }

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
            val byteCount = frameCount * bytesPerFrame
            require(destinationOffsetBytes >= 0 &&
                    destinationOffsetBytes + byteCount <= destination.size
            ) {
                "Playback source destination is too small."
            }
            if (startFrame != nextFrame) {
                seekToFrame(startFrame)
            }
            input.readFully(destination, destinationOffsetBytes, byteCount)
            nextFrame += frameCount
            return frameCount
        }

        override fun close() {
            input.close()
        }
    }

    private companion object {
        const val DEFAULT_CHANNEL_COUNT = 2
        const val BYTES_PER_SAMPLE = 2
    }
}
