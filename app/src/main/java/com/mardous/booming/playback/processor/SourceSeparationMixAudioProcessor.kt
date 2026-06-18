package com.mardous.booming.playback.processor

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToLong

@OptIn(UnstableApi::class)
class SourceSeparationMixAudioProcessor : BaseAudioProcessor() {

    @Volatile
    var blend: Float = CENTER_BLEND
        private set

    @Volatile
    private var active = false

    @Volatile
    private var vocalsGain = 1f

    @Volatile
    private var instrumentalGain = 1f

    @Volatile
    private var inputMode = InputMode.InstrumentalStem

    private val lock = Any()
    private var vocalsInput: RandomAccessFile? = null
    private var instrumentalInput: RandomAccessFile? = null
    private var scratch = ByteArray(0)
    private var instrumentalScratch = ByteArray(0)

    fun enable(
        vocalsFile: File,
        instrumentalFile: File? = null,
        positionMs: Long,
        initialBlend: Float = blend,
        inputMode: InputMode = InputMode.InstrumentalStem,
    ) {
        setBlend(initialBlend)
        synchronized(lock) {
            closeLocked()
            this.inputMode = inputMode
            vocalsInput = RandomAccessFile(vocalsFile, "r")
            instrumentalInput = instrumentalFile?.let { RandomAccessFile(it, "r") }
            active = true
            seekToLocked(positionMs)
        }
    }

    fun disable() {
        synchronized(lock) {
            active = false
            closeLocked()
        }
    }

    fun setBlend(value: Float) {
        val normalized = value.coerceIn(0f, 1f)
        blend = normalized
        vocalsGain = if (normalized <= CENTER_BLEND) 1f else (1f - normalized) / CENTER_BLEND
        instrumentalGain = if (normalized >= CENTER_BLEND) 1f else normalized / CENTER_BLEND
    }

    fun seekTo(positionMs: Long) {
        synchronized(lock) {
            if (active) {
                seekToLocked(positionMs)
            }
        }
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val buffer = replaceOutputBuffer(remaining)
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        if (!canMixCurrentFormat()) {
            buffer.put(inputBuffer)
            buffer.flip()
            return
        }

        val frameSize = inputAudioFormat.channelCount * BYTES_PER_SAMPLE
        val frames = remaining / frameSize
        val bytesToRead = frames * frameSize
        val bytesRead = readVocals(bytesToRead)
        val instrumentalBytesRead = readInstrumental(bytesToRead)

        if (bytesRead == null) {
            buffer.put(inputBuffer)
            buffer.flip()
            return
        }

        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val mode = inputMode
        val hasInstrumentalStemInput = instrumentalBytesRead != null
        var vocalsOffset = 0
        while (inputBuffer.remaining() >= frameSize) {
            val inputLeft = inputBuffer.short.toInt()
            val inputRight = inputBuffer.short.toInt()
            val vocalLeft = readPcm16(vocalsOffset, bytesRead)
            val vocalRight = readPcm16(vocalsOffset + BYTES_PER_SAMPLE, bytesRead)
            val instrumentalLeft = readPcm16(
                offset = vocalsOffset,
                bytes = instrumentalScratch,
                bytesRead = instrumentalBytesRead ?: 0,
            )
            val instrumentalRight = readPcm16(
                offset = vocalsOffset + BYTES_PER_SAMPLE,
                bytes = instrumentalScratch,
                bytesRead = instrumentalBytesRead ?: 0,
            )
            vocalsOffset += frameSize

            buffer.putShort(mixSample(inputLeft, vocalLeft, instrumentalLeft, mode, hasInstrumentalStemInput))
            buffer.putShort(mixSample(inputRight, vocalRight, instrumentalRight, mode, hasInstrumentalStemInput))
        }

        if (inputBuffer.hasRemaining()) {
            buffer.put(inputBuffer)
        }
        buffer.flip()
    }

    override fun onReset() {
        disable()
    }

    private fun canMixCurrentFormat(): Boolean {
        return active &&
                inputAudioFormat.encoding == C.ENCODING_PCM_16BIT &&
                inputAudioFormat.channelCount == CHANNEL_COUNT_STEREO
    }

    private fun readVocals(byteCount: Int): Int? {
        return synchronized(lock) {
            val input = vocalsInput
            if (!active || input == null) {
                null
            } else {
                if (scratch.size < byteCount) {
                    scratch = ByteArray(byteCount)
                }
                input.read(scratch, 0, byteCount).coerceAtLeast(0)
            }
        }
    }

    private fun readInstrumental(byteCount: Int): Int? {
        return synchronized(lock) {
            val input = instrumentalInput
            if (!active || input == null) {
                null
            } else {
                if (instrumentalScratch.size < byteCount) {
                    instrumentalScratch = ByteArray(byteCount)
                }
                input.read(instrumentalScratch, 0, byteCount).coerceAtLeast(0)
            }
        }
    }

    private fun readPcm16(offset: Int, bytesRead: Int): Int {
        return readPcm16(offset, scratch, bytesRead)
    }

    private fun readPcm16(offset: Int, bytes: ByteArray, bytesRead: Int): Int {
        if (offset + 1 >= bytesRead) return 0
        val low = bytes[offset].toInt() and 0xFF
        val high = bytes[offset + 1].toInt() and 0xFF
        return (low or (high shl 8)).toShort().toInt()
    }

    private fun mixSample(
        inputSample: Int,
        vocalSample: Int,
        instrumentalSample: Int,
        mode: InputMode,
        hasInstrumentalStemInput: Boolean,
    ): Short {
        val output = when (mode) {
            InputMode.InstrumentalStem ->
                inputSample * instrumentalGain + vocalSample * vocalsGain
            InputMode.OriginalSource ->
                if (hasInstrumentalStemInput) {
                    instrumentalSample * instrumentalGain + vocalSample * vocalsGain
                } else {
                    inputSample * instrumentalGain + vocalSample * (vocalsGain - instrumentalGain)
                }
        }
        return output
            .toInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            .toShort()
    }

    private fun seekToLocked(positionMs: Long) {
        val sampleRate = inputAudioFormat.sampleRate.takeIf { it > 0 } ?: DEFAULT_SAMPLE_RATE
        val frameSize = inputAudioFormat.channelCount
            .takeIf { it > 0 }
            ?.times(BYTES_PER_SAMPLE)
            ?: DEFAULT_FRAME_SIZE
        val frame = (positionMs.coerceAtLeast(0) * sampleRate / MILLIS_PER_SECOND.toFloat()).roundToLong()
        vocalsInput?.seek(WAV_HEADER_SIZE + frame * frameSize)
        instrumentalInput?.seek(WAV_HEADER_SIZE + frame * frameSize)
    }

    private fun closeLocked() {
        vocalsInput?.close()
        vocalsInput = null
        instrumentalInput?.close()
        instrumentalInput = null
    }

    companion object {
        const val CENTER_BLEND = 0.5f

        private const val CHANNEL_COUNT_STEREO = 2
        private const val BYTES_PER_SAMPLE = 2
        private const val DEFAULT_SAMPLE_RATE = 44_100
        private const val DEFAULT_FRAME_SIZE = CHANNEL_COUNT_STEREO * BYTES_PER_SAMPLE
        private const val MILLIS_PER_SECOND = 1000
        private const val WAV_HEADER_SIZE = 44L
    }

    enum class InputMode {
        InstrumentalStem,
        OriginalSource,
    }
}
