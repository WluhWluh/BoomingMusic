package com.mardous.booming.separation.audio

import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlin.math.roundToLong

data class DecodedPcmAudio(
    val sampleRate: Int,
    val channelCount: Int,
    val pcm16: ByteArray,
) {
    init {
        require(sampleRate > 0) { "Sample rate must be positive." }
        require(channelCount > 0) { "Channel count must be positive." }
        require(pcm16.size % (channelCount * Short.SIZE_BYTES) == 0) {
            "PCM byte count must align to complete frames."
        }
    }

    val frameCount: Int = pcm16.size / (channelCount * Short.SIZE_BYTES)

    fun resampleTo(
        targetSampleRate: Int,
        shouldCancel: () -> Boolean = { false },
    ): DecodedPcmAudio {
        require(targetSampleRate > 0) { "Target sample rate must be positive." }
        if (targetSampleRate == sampleRate) return this
        if (frameCount == 0) {
            return copy(sampleRate = targetSampleRate)
        }

        val outputFrameCount = ((frameCount.toDouble() * targetSampleRate) / sampleRate)
            .roundToLong()
            .coerceAtLeast(1L)
        require(outputFrameCount <= Int.MAX_VALUE) {
            "Resampled audio is too large."
        }

        val bytesPerFrame = channelCount * Short.SIZE_BYTES
        val outputByteCount = outputFrameCount * bytesPerFrame
        require(outputByteCount <= Int.MAX_VALUE) {
            "Resampled PCM buffer is too large."
        }

        val output = ByteArray(outputByteCount.toInt())
        val sourceFramesPerTargetFrame = sampleRate.toDouble() / targetSampleRate.toDouble()
        var outputOffset = 0

        for (targetFrame in 0 until outputFrameCount.toInt()) {
            if (targetFrame % CANCEL_CHECK_INTERVAL_FRAMES == 0) {
                throwIfCanceled(shouldCancel)
            }
            val sourcePosition = targetFrame * sourceFramesPerTargetFrame
            val sourceFrame = sourcePosition.toInt().coerceIn(0, frameCount - 1)
            val nextFrame = (sourceFrame + 1).coerceAtMost(frameCount - 1)
            val fraction = (sourcePosition - sourceFrame).toFloat()

            for (channel in 0 until channelCount) {
                val current = readLittleEndianShort(frameByteIndex(sourceFrame, channel)).toFloat()
                val next = readLittleEndianShort(frameByteIndex(nextFrame, channel)).toFloat()
                val value = (current + (next - current) * fraction)
                    .roundToInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                output[outputOffset++] = (value and 0xFF).toByte()
                output[outputOffset++] = ((value ushr 8) and 0xFF).toByte()
            }
        }

        return DecodedPcmAudio(
            sampleRate = targetSampleRate,
            channelCount = channelCount,
            pcm16 = output,
        )
    }

    fun fitToFrameCount(targetFrameCount: Int): DecodedPcmAudio {
        require(targetFrameCount >= 0) { "Target frame count must not be negative." }
        if (targetFrameCount == frameCount) return this
        val targetByteCount = Math.multiplyExact(
            targetFrameCount,
            channelCount * Short.SIZE_BYTES,
        )
        return copy(pcm16 = pcm16.copyOf(targetByteCount))
    }

    fun toStereoFloat(startFrame: Int = 0, maxFrames: Int? = null): Array<FloatArray> {
        require(startFrame >= 0) { "Start frame must not be negative." }
        require(startFrame <= frameCount) { "Start frame exceeds decoded audio length." }

        val frames = minOf(frameCount - startFrame, maxFrames ?: (frameCount - startFrame))
        val left = FloatArray(frames)
        val right = FloatArray(frames)

        var byteIndex = startFrame * channelCount * Short.SIZE_BYTES
        for (frame in 0 until frames) {
            val leftSample = readLittleEndianShort(byteIndex)
            left[frame] = leftSample / 32768f

            val rightSample = if (channelCount > 1) {
                readLittleEndianShort(byteIndex + Short.SIZE_BYTES)
            } else {
                leftSample
            }
            right[frame] = rightSample / 32768f

            byteIndex += channelCount * Short.SIZE_BYTES
        }

        return arrayOf(left, right)
    }

    private fun throwIfCanceled(shouldCancel: () -> Boolean) {
        if (shouldCancel()) {
            throw CancellationException("Source separation canceled.")
        }
    }

    private fun frameByteIndex(frame: Int, channel: Int): Int {
        return (frame * channelCount + channel) * Short.SIZE_BYTES
    }

    private fun readLittleEndianShort(byteIndex: Int): Short {
        val low = pcm16[byteIndex].toInt() and 0xFF
        val high = pcm16[byteIndex + 1].toInt()
        return ((high shl 8) or low).toShort()
    }

    private companion object {
        const val CANCEL_CHECK_INTERVAL_FRAMES = 16_384
    }
}
