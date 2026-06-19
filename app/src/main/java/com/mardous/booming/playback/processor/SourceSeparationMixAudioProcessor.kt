package com.mardous.booming.playback.processor

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.mardous.booming.separation.audio.Pcm16StereoFlacEncoder
import java.io.File
import java.io.Closeable
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToLong

@OptIn(UnstableApi::class)
class SourceSeparationMixAudioProcessor : BaseAudioProcessor() {

    @Volatile
    var blend: Float = CENTER_BLEND
        private set

    @Volatile
    var debugTraceSink: ((String) -> Unit)? = null

    @Volatile
    var mixedOutputStartedSink: (() -> Unit)? = null

    @Volatile
    private var active = false

    @Volatile
    private var vocalsGain = 1f

    @Volatile
    private var instrumentalGain = 1f

    @Volatile
    private var inputMode = InputMode.InstrumentalStem

    @Volatile
    private var stemSampleRate = DEFAULT_SAMPLE_RATE

    @Volatile
    private var stemChannelCount = CHANNEL_COUNT_STEREO

    private val lock = Any()
    private var vocalsInput: StemPcmInput? = null
    private var instrumentalInput: StemPcmInput? = null
    private var scratch = ByteArray(0)
    private var instrumentalScratch = ByteArray(0)
    private val debugSessionSeq = AtomicLong()
    private val debugQueueSeq = AtomicLong()

    @Volatile
    private var debugSessionId = 0L

    @Volatile
    private var debugQueueTraceRemaining = 0

    @Volatile
    private var notifyMixedOutputStarted = false

    private var mixedOutputPrerollFramesRemaining = 0L
    private var mixedOutputReadyPrerollMs = DEFAULT_MIXED_OUTPUT_READY_PREROLL_MS

    fun enable(
        vocalsFile: File,
        instrumentalFile: File? = null,
        positionMs: Long,
        initialBlend: Float = blend,
        inputMode: InputMode = InputMode.InstrumentalStem,
        stemSampleRate: Int = DEFAULT_SAMPLE_RATE,
        stemChannelCount: Int = CHANNEL_COUNT_STEREO,
        mixedOutputReadyPrerollMs: Long = DEFAULT_MIXED_OUTPUT_READY_PREROLL_MS,
    ) {
        setBlend(initialBlend)
        synchronized(lock) {
            closeLocked()
            debugSessionId = debugSessionSeq.incrementAndGet()
            debugQueueSeq.set(0)
            debugQueueTraceRemaining = DEBUG_INITIAL_QUEUE_TRACE_COUNT
            resetMixedOutputNotificationLocked()
            this.inputMode = inputMode
            this.stemSampleRate = stemSampleRate.takeIf { it > 0 } ?: DEFAULT_SAMPLE_RATE
            this.stemChannelCount = stemChannelCount.takeIf { it > 0 } ?: CHANNEL_COUNT_STEREO
            this.mixedOutputReadyPrerollMs = mixedOutputReadyPrerollMs.coerceAtLeast(0L)
            vocalsInput = openStemInput(vocalsFile)
            instrumentalInput = instrumentalFile?.let(::openStemInput)
            active = true
            seekToLocked(positionMs)
            traceDebug(
                "enable",
                "session=$debugSessionId mode=$inputMode positionMs=$positionMs " +
                        "blend=$blend stemRate=${this.stemSampleRate} stemChannels=${this.stemChannelCount} " +
                        "mixedPrerollMs=${this.mixedOutputReadyPrerollMs} " +
                        "vocals=${vocalsFile.length()} instrumental=${instrumentalFile?.length()}"
            )
        }
    }

    fun disable() {
        synchronized(lock) {
            traceDebug("disable", "session=$debugSessionId active=$active")
            active = false
            closeLocked()
        }
    }

    fun setBlend(value: Float) {
        val normalized = value.coerceIn(0f, 1f)
        blend = normalized
        vocalsGain = if (normalized <= CENTER_BLEND) 1f else (1f - normalized) / CENTER_BLEND
        instrumentalGain = if (normalized >= CENTER_BLEND) 1f else normalized / CENTER_BLEND
        traceDebug(
            "setBlend",
            "session=$debugSessionId blend=$blend vocalsGain=$vocalsGain instrumentalGain=$instrumentalGain"
        )
    }

    fun seekTo(positionMs: Long) {
        synchronized(lock) {
            if (active) {
                seekToLocked(positionMs)
                debugQueueSeq.set(0)
                debugQueueTraceRemaining = DEBUG_INITIAL_QUEUE_TRACE_COUNT
                resetMixedOutputNotificationLocked()
                traceDebug("seekTo", "session=$debugSessionId positionMs=$positionMs")
            }
        }
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        traceDebug(
            "onConfigure",
            "session=$debugSessionId active=$active encoding=${inputAudioFormat.encoding} " +
                    "rate=${inputAudioFormat.sampleRate} channels=${inputAudioFormat.channelCount}"
        )
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

        val queueSeq = debugQueueSeq.incrementAndGet()
        if (!canMixCurrentFormat()) {
            queueUnmixedInput(inputBuffer, buffer, remaining, "format")
            traceQueueIfNeeded(
                queueSeq = queueSeq,
                branch = "unmixed-format-${unmixedOutputAction()}",
                remaining = remaining,
                bytesRead = null,
                instrumentalBytesRead = null,
            )
            return
        }

        val frameSize = inputAudioFormat.channelCount * BYTES_PER_SAMPLE
        val frames = remaining / frameSize
        val bytesToRead = frames * frameSize
        val bytesRead = readVocals(bytesToRead)
        val instrumentalBytesRead = readInstrumental(bytesToRead)

        if (bytesRead == null) {
            queueUnmixedInput(inputBuffer, buffer, remaining, "vocals-null")
            traceQueueIfNeeded(
                queueSeq = queueSeq,
                branch = "unmixed-vocals-null-${unmixedOutputAction()}",
                remaining = remaining,
                bytesRead = null,
                instrumentalBytesRead = instrumentalBytesRead,
            )
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
        notifyMixedOutputStartedIfNeeded(frames)
        traceQueueIfNeeded(
            queueSeq = queueSeq,
            branch = "mixed",
            remaining = remaining,
            bytesRead = bytesRead,
            instrumentalBytesRead = instrumentalBytesRead,
        )
    }

    private fun canMixCurrentFormat(): Boolean {
        return active &&
                inputAudioFormat.encoding == C.ENCODING_PCM_16BIT &&
                inputAudioFormat.channelCount == CHANNEL_COUNT_STEREO
    }

    private fun queueUnmixedInput(
        inputBuffer: ByteBuffer,
        outputBuffer: ByteBuffer,
        byteCount: Int,
        reason: String,
    ) {
        if (active && inputMode == InputMode.OriginalSource) {
            repeat(byteCount) {
                outputBuffer.put(0)
            }
            inputBuffer.position(inputBuffer.limit())
        } else {
            outputBuffer.put(inputBuffer)
        }
        outputBuffer.flip()
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
                input.read(scratch, byteCount).coerceAtLeast(0)
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
                input.read(instrumentalScratch, byteCount).coerceAtLeast(0)
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
        val sampleRate = stemSampleRate.takeIf { it > 0 } ?: DEFAULT_SAMPLE_RATE
        val frameSize = stemChannelCount
            .takeIf { it > 0 }
            ?.times(BYTES_PER_SAMPLE)
            ?: DEFAULT_FRAME_SIZE
        val frame = (positionMs.coerceAtLeast(0) * sampleRate / MILLIS_PER_SECOND.toFloat()).roundToLong()
        val bytePosition = frame * frameSize
        vocalsInput?.seekToPcmByte(bytePosition)
        instrumentalInput?.seekToPcmByte(bytePosition)
    }

    private fun closeLocked() {
        vocalsInput?.close()
        vocalsInput = null
        instrumentalInput?.close()
        instrumentalInput = null
        notifyMixedOutputStarted = false
        mixedOutputPrerollFramesRemaining = 0L
    }

    private fun resetMixedOutputNotificationLocked() {
        notifyMixedOutputStarted = true
        mixedOutputPrerollFramesRemaining = -1L
    }

    private fun notifyMixedOutputStartedIfNeeded(frames: Int) {
        if (!notifyMixedOutputStarted) return
        if (mixedOutputPrerollFramesRemaining < 0L) {
            val sampleRate = inputAudioFormat.sampleRate.takeIf { it > 0 } ?: DEFAULT_SAMPLE_RATE
            mixedOutputPrerollFramesRemaining =
                sampleRate * mixedOutputReadyPrerollMs / MILLIS_PER_SECOND
            traceDebug(
                "mixedOutputPreroll.start",
                "session=$debugSessionId sampleRate=$sampleRate ms=$mixedOutputReadyPrerollMs " +
                        "frames=$mixedOutputPrerollFramesRemaining"
            )
        }
        mixedOutputPrerollFramesRemaining -= frames.toLong()
        if (mixedOutputPrerollFramesRemaining > 0L) return
        notifyMixedOutputStarted = false
        mixedOutputPrerollFramesRemaining = 0L
        traceDebug("mixedOutputPreroll.ready", "session=$debugSessionId")
        mixedOutputStartedSink?.invoke()
    }

    private fun traceQueueIfNeeded(
        queueSeq: Long,
        branch: String,
        remaining: Int,
        bytesRead: Int?,
        instrumentalBytesRead: Int?,
    ) {
        val remainingTraces = debugQueueTraceRemaining
        if (remainingTraces <= 0 && queueSeq % DEBUG_QUEUE_TRACE_INTERVAL != 0L) {
            return
        }
        if (remainingTraces > 0) {
            debugQueueTraceRemaining = remainingTraces - 1
        }
        traceDebug(
            "queueInput",
            "session=$debugSessionId seq=$queueSeq branch=$branch active=$active mode=$inputMode " +
                    "inputEncoding=${inputAudioFormat.encoding} inputRate=${inputAudioFormat.sampleRate} " +
                    "inputChannels=${inputAudioFormat.channelCount} remaining=$remaining " +
                    "vocalsBytes=$bytesRead instrumentalBytes=$instrumentalBytesRead"
        )
    }

    private fun unmixedOutputAction(): String {
        return if (active && inputMode == InputMode.OriginalSource) {
            "muteOriginal"
        } else {
            "passthroughOriginal"
        }
    }

    private fun traceDebug(event: String, detail: String) {
        debugTraceSink?.invoke("mix.$event | $detail")
    }

    private fun openStemInput(file: File): StemPcmInput {
        return when {
            file.extension.equals("flac", ignoreCase = true) -> {
                FlacStemPcmInput(file) { detail ->
                    traceDebug("flac", detail)
                }
            }
            file.extension.equals("pcm", ignoreCase = true) -> {
                RawPcmStemInput(file)
            }
            else -> {
                WavStemPcmInput(file)
            }
        }
    }

    companion object {
        const val CENTER_BLEND = 0.5f
        const val DEFAULT_MIXED_OUTPUT_READY_PREROLL_MS = 800L
        const val HYDRATED_MIXED_OUTPUT_READY_PREROLL_MS = 80L

        private const val DEBUG_INITIAL_QUEUE_TRACE_COUNT = 80
        private const val DEBUG_QUEUE_TRACE_INTERVAL = 200L
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

    private interface StemPcmInput : Closeable {
        fun read(buffer: ByteArray, byteCount: Int): Int
        fun seekToPcmByte(bytePosition: Long)
    }

    private class WavStemPcmInput(file: File) : StemPcmInput {
        private val input = RandomAccessFile(file, "r")

        override fun read(buffer: ByteArray, byteCount: Int): Int {
            return input.read(buffer, 0, byteCount)
        }

        override fun seekToPcmByte(bytePosition: Long) {
            input.seek(WAV_HEADER_SIZE + bytePosition.coerceAtLeast(0L))
        }

        override fun close() {
            input.close()
        }
    }

    private class RawPcmStemInput(file: File) : StemPcmInput {
        private val input = RandomAccessFile(file, "r")

        override fun read(buffer: ByteArray, byteCount: Int): Int {
            return input.read(buffer, 0, byteCount)
        }

        override fun seekToPcmByte(bytePosition: Long) {
            input.seek(bytePosition.coerceAtLeast(0L))
        }

        override fun close() {
            input.close()
        }
    }

    private class FlacStemPcmInput(
        file: File,
        traceSink: ((String) -> Unit)?,
    ) : StemPcmInput {
        private val reader = Pcm16StereoFlacEncoder.openIndexedPcmReader(file, traceSink)
        private val fallbackPcm = if (reader == null) {
            traceSink?.invoke("fallbackWholeFileDecode file=${file.name}")
            Pcm16StereoFlacEncoder.decodeFlacFile(file).pcm16
        } else {
            null
        }
        private var position = 0

        override fun read(buffer: ByteArray, byteCount: Int): Int {
            val activeReader = reader
            if (activeReader != null) {
                return activeReader.read(buffer, byteCount)
            }
            val pcm = fallbackPcm ?: return -1
            if (position >= pcm.size) return -1
            val count = minOf(byteCount, pcm.size - position)
            pcm.copyInto(buffer, destinationOffset = 0, startIndex = position, endIndex = position + count)
            position += count
            return count
        }

        override fun seekToPcmByte(bytePosition: Long) {
            val activeReader = reader
            if (activeReader != null) {
                activeReader.seekToPcmByte(bytePosition)
                return
            }
            val pcm = fallbackPcm ?: return
            position = bytePosition
                .coerceAtLeast(0L)
                .coerceAtMost(pcm.size.toLong())
                .toInt()
        }

        override fun close() {
            reader?.close()
        }
    }
}
