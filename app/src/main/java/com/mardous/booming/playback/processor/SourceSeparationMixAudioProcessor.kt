package com.mardous.booming.playback.processor

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.mardous.booming.separation.audio.Pcm16StereoFlacEncoder
import com.mardous.booming.util.DEFAULT_SOURCE_SEPARATION_MIXED_OUTPUT_PREROLL_MS
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

    fun hotSwapToPcmInputs(
        vocalsFile: File,
        instrumentalFile: File,
    ): Boolean {
        val newVocalsInput = runCatching { RawPcmStemInput(vocalsFile) }
            .getOrElse { error ->
                traceDebug("hotSwapPcm.failed", "session=$debugSessionId stem=vocals error=${error.message}")
                return false
            }
        val newInstrumentalInput = runCatching { RawPcmStemInput(instrumentalFile) }
            .getOrElse { error ->
                newVocalsInput.close()
                traceDebug(
                    "hotSwapPcm.failed",
                    "session=$debugSessionId stem=instrumental error=${error.message}"
                )
                return false
            }

        synchronized(lock) {
            val oldVocalsInput = vocalsInput
            val oldInstrumentalInput = instrumentalInput
            if (!active ||
                inputMode != InputMode.OriginalSource ||
                oldVocalsInput == null ||
                oldInstrumentalInput == null
            ) {
                newVocalsInput.close()
                newInstrumentalInput.close()
                traceDebug(
                    "hotSwapPcm.skip",
                    "session=$debugSessionId active=$active mode=$inputMode " +
                            "vocals=${oldVocalsInput != null} instrumental=${oldInstrumentalInput != null}"
                )
                return false
            }

            val vocalsPosition = oldVocalsInput.pcmBytePosition
            val instrumentalPosition = oldInstrumentalInput.pcmBytePosition
            val bytePosition = minOf(vocalsPosition, instrumentalPosition)
            newVocalsInput.seekToPcmByte(bytePosition)
            newInstrumentalInput.seekToPcmByte(bytePosition)
            vocalsInput = newVocalsInput
            instrumentalInput = newInstrumentalInput
            oldVocalsInput.close()
            oldInstrumentalInput.close()
            traceDebug(
                "hotSwapPcm",
                "session=$debugSessionId byte=$bytePosition vocalsByte=$vocalsPosition " +
                        "instrumentalByte=$instrumentalPosition"
            )
            return true
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
        val stemReadResult = readStems(bytesToRead)
        val bytesRead = stemReadResult.vocalsBytesRead
        val instrumentalBytesRead = stemReadResult.instrumentalBytesRead

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
                inputAudioFormat.channelCount == CHANNEL_COUNT_STEREO &&
                inputAudioFormat.sampleRate == stemSampleRate
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

    private fun readStems(byteCount: Int): StemReadResult {
        return synchronized(lock) {
            val activeVocalsInput = vocalsInput
            val activeInstrumentalInput = instrumentalInput
            val vocalsBytesRead = if (!active || activeVocalsInput == null) {
                null
            } else {
                if (scratch.size < byteCount) {
                    scratch = ByteArray(byteCount)
                }
                activeVocalsInput.read(scratch, byteCount).coerceAtLeast(0)
            }
            val instrumentalBytesRead = if (!active || activeInstrumentalInput == null) {
                null
            } else {
                if (instrumentalScratch.size < byteCount) {
                    instrumentalScratch = ByteArray(byteCount)
                }
                activeInstrumentalInput.read(instrumentalScratch, byteCount).coerceAtLeast(0)
            }
            StemReadResult(vocalsBytesRead, instrumentalBytesRead)
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
                    "stemRate=$stemSampleRate inputChannels=${inputAudioFormat.channelCount} " +
                    "remaining=$remaining " +
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

    private fun traceSinkForDebugEvent(event: String): ((String) -> Unit)? {
        return if (debugTraceSink == null) {
            null
        } else {
            { detail -> traceDebug(event, detail) }
        }
    }

    private fun openStemInput(file: File): StemPcmInput {
        return when {
            file.extension.equals("flac", ignoreCase = true) -> {
                FlacStemPcmInput(file, traceSink = traceSinkForDebugEvent("flac"))
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
        const val DEFAULT_MIXED_OUTPUT_READY_PREROLL_MS =
            DEFAULT_SOURCE_SEPARATION_MIXED_OUTPUT_PREROLL_MS

        private const val DEBUG_INITIAL_QUEUE_TRACE_COUNT = 80
        private const val DEBUG_QUEUE_TRACE_INTERVAL = 200L
        private const val CHANNEL_COUNT_STEREO = 2
        private const val BYTES_PER_SAMPLE = 2
        private const val DEFAULT_SAMPLE_RATE = 44_100
        private const val DEFAULT_FRAME_SIZE = CHANNEL_COUNT_STEREO * BYTES_PER_SAMPLE
        private const val MILLIS_PER_SECOND = 1000
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val WAV_HEADER_SIZE = 44L
    }

    enum class InputMode {
        InstrumentalStem,
        OriginalSource,
    }

    private interface StemPcmInput : Closeable {
        val pcmBytePosition: Long

        fun read(buffer: ByteArray, byteCount: Int): Int
        fun seekToPcmByte(bytePosition: Long)
    }

    private class WavStemPcmInput(file: File) : StemPcmInput {
        private val input = RandomAccessFile(file, "r")

        override val pcmBytePosition: Long
            get() = (input.filePointer - WAV_HEADER_SIZE).coerceAtLeast(0L)

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

        override val pcmBytePosition: Long
            get() = input.filePointer.coerceAtLeast(0L)

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
        private val traceSink: ((String) -> Unit)?,
    ) : StemPcmInput {
        private val reader = Pcm16StereoFlacEncoder.openIndexedPcmReader(file, traceSink)
        private val fallbackPcm = if (reader == null) {
            val startedAtNs = System.nanoTime()
            traceSink?.invoke("fallbackWholeFileDecode.start file=${file.name} bytes=${file.length()}")
            Pcm16StereoFlacEncoder.decodeFlacFile(file).pcm16.also { pcm ->
                traceSink?.invoke(
                    "fallbackWholeFileDecode.end file=${file.name} pcmBytes=${pcm.size} " +
                            "decodeMs=${(System.nanoTime() - startedAtNs) / NANOS_PER_MILLISECOND.toFloat()}"
                )
            }
        } else {
            null
        }
        private var position = 0L

        override val pcmBytePosition: Long
            get() = position

        override fun read(buffer: ByteArray, byteCount: Int): Int {
            val activeReader = reader
            if (activeReader != null) {
                val count = activeReader.read(buffer, byteCount)
                if (count > 0) {
                    position += count
                }
                return count
            }
            val pcm = fallbackPcm ?: return -1
            if (position >= pcm.size) return -1
            val start = position.toInt()
            val count = minOf(byteCount, pcm.size - start)
            pcm.copyInto(buffer, destinationOffset = 0, startIndex = start, endIndex = start + count)
            position += count
            return count
        }

        override fun seekToPcmByte(bytePosition: Long) {
            val activeReader = reader
            if (activeReader != null) {
                activeReader.seekToPcmByte(bytePosition)
            }
            val maxBytes = fallbackPcm?.size?.toLong()
            position = bytePosition
                .coerceAtLeast(0L)
                .let { position ->
                    maxBytes?.let(position::coerceAtMost) ?: position
                }
            traceSink?.invoke(
                "flacInput.seek requestedByte=$bytePosition appliedByte=$position " +
                        "indexed=${activeReader != null} fallbackBytes=${fallbackPcm?.size}"
            )
        }

        override fun close() {
            reader?.close()
        }
    }

    private data class StemReadResult(
        val vocalsBytesRead: Int?,
        val instrumentalBytesRead: Int?,
    )
}
