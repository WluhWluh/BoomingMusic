package com.mardous.booming.playback.processor

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.mardous.booming.playback.SourceSeparationFileStemSourceFactory
import com.mardous.booming.playback.SourceSeparationFlacStemSourceFactory
import com.mardous.booming.playback.SourceSeparationPlaybackDataState
import com.mardous.booming.playback.SourceSeparationPlaybackStemSourceFactory
import com.mardous.booming.playback.SourceSeparationStemPlaybackEngine
import com.mardous.booming.util.DEFAULT_SOURCE_SEPARATION_MIXED_OUTPUT_PREROLL_MS
import java.io.File
import java.io.Closeable
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.floor
import kotlin.math.roundToInt
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

    private val gainGeneration = AtomicLong()
    private val gainSnapshot = AtomicReference(GainSnapshot.centered())
    private var appliedGainGeneration = 0L
    private var currentVocalsGain = 1f
    private var currentInstrumentalGain = 1f
    private var targetVocalsGain = 1f
    private var targetInstrumentalGain = 1f
    private var vocalsGainStep = 0f
    private var instrumentalGainStep = 0f
    private var gainRampFramesRemaining = 0

    @Volatile
    private var inputMode = InputMode.InstrumentalStem

    @Volatile
    private var stemSampleRate = DEFAULT_SAMPLE_RATE

    @Volatile
    private var stemChannelCount = CHANNEL_COUNT_STEREO

    private val lock = Any()
    @Volatile
    private var playbackEngine: SourceSeparationStemPlaybackEngine? = null
    private var engineStemBuffers: Array<ByteArray> = emptyArray()
    private var engineHasInstrumentalInput = false
    private var vocalsInput: StemPcmInput? = null
    private var instrumentalInput: StemPcmInput? = null
    private var scratch = ByteArray(0)
    private var instrumentalScratch = ByteArray(0)
    private val vocalsResampleCache = StemResampleCache()
    private val instrumentalResampleCache = StemResampleCache()
    private var resampleStemFramePosition = 0.0
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
            applyGainSnapshotImmediately()
            val engineFactories = createEngineFactories(vocalsFile, instrumentalFile)
            if (engineFactories != null) {
                val engine = SourceSeparationStemPlaybackEngine()
                playbackEngine = engine
                engineHasInstrumentalInput = instrumentalFile != null
                engineStemBuffers = Array(engineFactories.size) {
                    ByteArray(engine.blockFrameCapacity * DEFAULT_FRAME_SIZE)
                }
                val startFrame = engineFactories.first().spec.geometry
                    .transportPositionToStemFrame(positionMs)
                resampleStemFramePosition = startFrame.toDouble()
                prepareEngineResampleCachesLocked(engine.blockFrameCapacity, startFrame)
                engine.start(
                    sessionId = debugSessionId,
                    factories = engineFactories,
                    startFrame = startFrame,
                )
            } else {
                vocalsInput = openStemInput(vocalsFile)
                instrumentalInput = instrumentalFile?.let(::openStemInput)
            }
            active = true
            if (playbackEngine == null) seekToLocked(positionMs)
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
        val vocalsGain = if (normalized <= CENTER_BLEND) 1f else
            (1f - normalized) / CENTER_BLEND
        val instrumentalGain = if (normalized >= CENTER_BLEND) 1f else
            normalized / CENTER_BLEND
        gainSnapshot.set(
            GainSnapshot(
                generation = gainGeneration.incrementAndGet(),
                blend = normalized,
                vocalsGain = vocalsGain,
                instrumentalGain = instrumentalGain,
            ),
        )
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
        playbackEngine?.let { engine ->
            if (!active || inputMode != InputMode.OriginalSource) return false
            val factories = createEngineFactories(vocalsFile, instrumentalFile) ?: return false
            engineHasInstrumentalInput = true
            engineStemBuffers = Array(factories.size) {
                ByteArray(engine.blockFrameCapacity * DEFAULT_FRAME_SIZE)
            }
            engine.hotSwap(
                sessionId = debugSessionId,
                factories = factories,
                startFrame = if (inputAudioFormat.sampleRate != stemSampleRate) {
                    floor(resampleStemFramePosition).toLong().coerceAtLeast(0L)
                } else {
                    engine.currentFrame()
                },
            )
            val logicalFrame = if (inputAudioFormat.sampleRate != stemSampleRate) {
                floor(resampleStemFramePosition).toLong().coerceAtLeast(0L)
            } else {
                engine.currentFrame()
            }
            resampleStemFramePosition = logicalFrame.toDouble()
            prepareEngineResampleCachesLocked(engine.blockFrameCapacity, logicalFrame)
            resetMixedOutputNotificationLocked()
            traceDebug(
                "hotSwapPcm",
                "session=$debugSessionId engine=true frame=$logicalFrame stems=${factories.size}",
            )
            return true
        }
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

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        synchronized(lock) {
            val engine = playbackEngine
            if (active && engine != null) {
                val logicalFrame = if (inputAudioFormat.sampleRate != stemSampleRate) {
                    floor(resampleStemFramePosition).toLong().coerceAtLeast(0L)
                } else {
                    engine.currentFrame()
                }
                engine.seekTo(logicalFrame)
                resampleStemFramePosition = logicalFrame.toDouble()
                prepareEngineResampleCachesLocked(engine.blockFrameCapacity, logicalFrame)
            } else {
                clearResampleCachesLocked()
            }
            resetMixedOutputNotificationLocked()
        }
    }

    override fun onReset() {
        synchronized(lock) {
            active = false
            closeLocked()
        }
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
        prepareGainRamp()
        val resampled = inputAudioFormat.sampleRate != stemSampleRate
        if (playbackEngine != null) {
            val mixedFrames = if (resampled) {
                queueResampledEngineInput(
                    inputBuffer = inputBuffer,
                    outputBuffer = buffer,
                    frameCount = frames,
                    frameSize = frameSize,
                    outputSampleRate = inputAudioFormat.sampleRate,
                )
            } else {
                queueEngineInput(
                    inputBuffer = inputBuffer,
                    outputBuffer = buffer,
                    frameCount = frames,
                    frameSize = frameSize,
                )
            }
            if (inputBuffer.hasRemaining()) {
                buffer.put(inputBuffer)
            }
            buffer.flip()
            if (mixedFrames > 0) notifyMixedOutputStartedIfNeeded(mixedFrames)
            traceQueueIfNeeded(
                queueSeq = queueSeq,
                branch = when {
                    mixedFrames != frames -> "engine-underflow"
                    resampled -> "mixed-engine-resampled"
                    else -> "mixed-engine"
                },
                remaining = remaining,
                bytesRead = mixedFrames * frameSize,
                instrumentalBytesRead = if (engineHasInstrumentalInput) {
                    mixedFrames * frameSize
                } else {
                    null
                },
            )
            return
        }
        val stemReadResult = if (resampled) {
            readResampledStems(
                frameCount = frames,
                outputByteCount = bytesToRead,
                outputSampleRate = inputAudioFormat.sampleRate,
            )
        } else {
            readStems(bytesToRead)
        }
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
            advanceGainRamp()
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
            branch = if (resampled) "mixed-resampled" else "mixed",
            remaining = remaining,
            bytesRead = bytesRead,
            instrumentalBytesRead = instrumentalBytesRead,
        )
    }

    private fun canMixCurrentFormat(): Boolean {
        return active &&
                inputAudioFormat.encoding == C.ENCODING_PCM_16BIT &&
                inputAudioFormat.channelCount == CHANNEL_COUNT_STEREO &&
                inputAudioFormat.sampleRate > 0 &&
                stemSampleRate > 0 &&
                stemChannelCount == CHANNEL_COUNT_STEREO
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

    private fun queueEngineInput(
        inputBuffer: ByteBuffer,
        outputBuffer: ByteBuffer,
        frameCount: Int,
        frameSize: Int,
    ): Int {
        val engine = playbackEngine ?: return 0
        var handledFrames = 0
        var mixedFrames = 0
        while (handledFrames < frameCount) {
            val chunkFrames = minOf(
                frameCount - handledFrames,
                engine.blockFrameCapacity,
            )
            val chunkBytes = chunkFrames * frameSize
            val readFrames = engine.readInto(engineStemBuffers, chunkFrames)
            if (readFrames == chunkFrames) {
                val vocals = engineStemBuffers[0]
                val instrumental = engineStemBuffers.getOrNull(1)
                var stemOffset = 0
                repeat(chunkFrames) {
                    advanceGainRamp()
                    val inputLeft = inputBuffer.short.toInt()
                    val inputRight = inputBuffer.short.toInt()
                    val vocalLeft = readPcm16(stemOffset, vocals, chunkBytes)
                    val vocalRight = readPcm16(
                        stemOffset + BYTES_PER_SAMPLE,
                        vocals,
                        chunkBytes,
                    )
                    val instrumentalLeft = instrumental?.let { bytes ->
                        readPcm16(stemOffset, bytes, chunkBytes)
                    } ?: 0
                    val instrumentalRight = instrumental?.let { bytes ->
                        readPcm16(stemOffset + BYTES_PER_SAMPLE, bytes, chunkBytes)
                    } ?: 0
                    outputBuffer.putShort(
                        mixSample(
                            inputSample = inputLeft,
                            vocalSample = vocalLeft,
                            instrumentalSample = instrumentalLeft,
                            mode = inputMode,
                            hasInstrumentalStemInput = engineHasInstrumentalInput,
                        ),
                    )
                    outputBuffer.putShort(
                        mixSample(
                            inputSample = inputRight,
                            vocalSample = vocalRight,
                            instrumentalSample = instrumentalRight,
                            mode = inputMode,
                            hasInstrumentalStemInput = engineHasInstrumentalInput,
                        ),
                    )
                    stemOffset += frameSize
                }
                mixedFrames += chunkFrames
            } else {
                writeUnmixedInput(
                    inputBuffer = inputBuffer,
                    outputBuffer = outputBuffer,
                    byteCount = chunkBytes,
                )
            }
            handledFrames += chunkFrames
        }
        return mixedFrames
    }

    private fun queueResampledEngineInput(
        inputBuffer: ByteBuffer,
        outputBuffer: ByteBuffer,
        frameCount: Int,
        frameSize: Int,
        outputSampleRate: Int,
    ): Int {
        val engine = playbackEngine ?: return 0
        val ratio = stemSampleRate.toDouble() / outputSampleRate.toDouble()
        if (ratio <= 0.0 || !ratio.isFinite()) return 0

        val cacheFrameSize = stemChannelCount * BYTES_PER_SAMPLE
        val maxSourceFrames = engine.blockFrameCapacity - 2
        val maxOutputFrames = if (ratio <= 1.0) {
            engine.blockFrameCapacity
        } else {
            (maxSourceFrames / ratio).toInt().coerceAtLeast(1)
        }
        var handledFrames = 0
        var mixedFrames = 0
        val mode = inputMode
        val hasInstrumentalStemInput = engineHasInstrumentalInput

        while (handledFrames < frameCount) {
            val chunkFrames = minOf(frameCount - handledFrames, maxOutputFrames)
            val startPosition = resampleStemFramePosition
            val startFrame = floor(startPosition).toLong().coerceAtLeast(0L)
            val sessionEndFrame = engine.currentSession?.geometry?.frameCount ?: Long.MAX_VALUE
            val endExclusiveFrame = (floor(
                startPosition + chunkFrames * ratio,
            ).toLong().coerceAtLeast(startFrame) + 1L).coerceAtMost(sessionEndFrame)
            val cachedEndFrame = vocalsResampleCache.endExclusiveFrame
            val framesToReadLong = (endExclusiveFrame - cachedEndFrame).coerceAtLeast(0L)
            if (cachedEndFrame < startFrame ||
                framesToReadLong > engine.blockFrameCapacity
            ) {
                writeUnmixedInput(
                    inputBuffer = inputBuffer,
                    outputBuffer = outputBuffer,
                    byteCount = (frameCount - handledFrames) * frameSize,
                )
                break
            }
            val framesToRead = framesToReadLong.toInt()
            if (!vocalsResampleCache.canAppend(framesToRead, cacheFrameSize)) {
                writeUnmixedInput(
                    inputBuffer = inputBuffer,
                    outputBuffer = outputBuffer,
                    byteCount = (frameCount - handledFrames) * frameSize,
                )
                break
            }
            if (framesToRead > 0) {
                val readFrames = engine.readInto(engineStemBuffers, framesToRead)
                if (readFrames != framesToRead ||
                    !vocalsResampleCache.appendFrom(
                        source = engineStemBuffers[0],
                        frameCount = readFrames,
                        frameSize = cacheFrameSize,
                    ) ||
                    (engineHasInstrumentalInput &&
                            !instrumentalResampleCache.appendFrom(
                                source = engineStemBuffers[1],
                                frameCount = readFrames,
                                frameSize = cacheFrameSize,
                            ))
                ) {
                    writeUnmixedInput(
                        inputBuffer = inputBuffer,
                        outputBuffer = outputBuffer,
                        byteCount = (frameCount - handledFrames) * frameSize,
                    )
                    break
                }
            }

            repeat(chunkFrames) { outputFrame ->
                advanceGainRamp()
                val inputLeft = inputBuffer.short.toInt()
                val inputRight = inputBuffer.short.toInt()
                val sourcePosition = startPosition + outputFrame * ratio
                val sourceFrame = floor(sourcePosition).toLong().coerceAtLeast(0L)
                val fraction = sourcePosition - sourceFrame
                val vocalLeft = interpolatePcm16(
                    vocalsResampleCache,
                    sourceFrame,
                    CHANNEL_LEFT,
                    fraction,
                    cacheFrameSize,
                )
                val vocalRight = interpolatePcm16(
                    vocalsResampleCache,
                    sourceFrame,
                    CHANNEL_RIGHT,
                    fraction,
                    cacheFrameSize,
                )
                val instrumentalLeft = if (hasInstrumentalStemInput) {
                    interpolatePcm16(
                        instrumentalResampleCache,
                        sourceFrame,
                        CHANNEL_LEFT,
                        fraction,
                        cacheFrameSize,
                    )
                } else {
                    0
                }
                val instrumentalRight = if (hasInstrumentalStemInput) {
                    interpolatePcm16(
                        instrumentalResampleCache,
                        sourceFrame,
                        CHANNEL_RIGHT,
                        fraction,
                        cacheFrameSize,
                    )
                } else {
                    0
                }
                outputBuffer.putShort(
                    mixSample(
                        inputSample = inputLeft,
                        vocalSample = vocalLeft,
                        instrumentalSample = instrumentalLeft,
                        mode = mode,
                        hasInstrumentalStemInput = hasInstrumentalStemInput,
                    ),
                )
                outputBuffer.putShort(
                    mixSample(
                        inputSample = inputRight,
                        vocalSample = vocalRight,
                        instrumentalSample = instrumentalRight,
                        mode = mode,
                        hasInstrumentalStemInput = hasInstrumentalStemInput,
                    ),
                )
            }
            handledFrames += chunkFrames
            mixedFrames += chunkFrames
            resampleStemFramePosition += chunkFrames * ratio
            val keepFromFrame = floor(resampleStemFramePosition).toLong().coerceAtLeast(0L)
            vocalsResampleCache.dropBefore(keepFromFrame, cacheFrameSize)
            instrumentalResampleCache.dropBefore(keepFromFrame, cacheFrameSize)
        }
        return mixedFrames
    }

    private fun writeUnmixedInput(
        inputBuffer: ByteBuffer,
        outputBuffer: ByteBuffer,
        byteCount: Int,
    ) {
        if (active && inputMode == InputMode.OriginalSource) {
            repeat(byteCount) { outputBuffer.put(0) }
            inputBuffer.position(inputBuffer.position() + byteCount)
        } else {
            repeat(byteCount) { outputBuffer.put(inputBuffer.get()) }
        }
    }

    internal fun isDataPlaneReady(): Boolean = playbackEngine?.hasResumeWaterline() ?: active

    internal fun dataPlaneState(): SourceSeparationPlaybackDataState {
        return playbackEngine?.currentState ?: if (active) {
            SourceSeparationPlaybackDataState.Ready
        } else {
            SourceSeparationPlaybackDataState.Idle
        }
    }

    internal fun consumeDataPlaneReadyNotification(): Boolean {
        return playbackEngine?.pollReadyNotification() == true
    }

    private fun readStems(byteCount: Int): StemReadResult {
        return synchronized(lock) {
            val activeVocalsInput = vocalsInput
            val activeInstrumentalInput = instrumentalInput
            val vocalsBytesRead = if (!active || activeVocalsInput == null) {
                null
            } else {
                clearResampleCachesLocked()
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

    private fun readResampledStems(
        frameCount: Int,
        outputByteCount: Int,
        outputSampleRate: Int,
    ): StemReadResult {
        return synchronized(lock) {
            val activeVocalsInput = vocalsInput
            val activeInstrumentalInput = instrumentalInput
            val sourceFrameSize = stemChannelCount * BYTES_PER_SAMPLE
            val sourceRate = stemSampleRate
            if (!active || activeVocalsInput == null ||
                frameCount <= 0 ||
                outputByteCount <= 0 ||
                outputSampleRate <= 0 ||
                sourceRate <= 0 ||
                sourceFrameSize <= 0
            ) {
                return@synchronized StemReadResult(null, null)
            }

            if (scratch.size < outputByteCount) {
                scratch = ByteArray(outputByteCount)
            }
            val ratio = sourceRate.toDouble() / outputSampleRate.toDouble()
            val startPosition = resampleStemFramePosition
            val lastPosition = startPosition + (frameCount - 1).coerceAtLeast(0) * ratio
            val startFrame = floor(startPosition).toLong().coerceAtLeast(0L)
            val endFrame = floor(lastPosition).toLong().coerceAtLeast(startFrame) + 1L
            vocalsResampleCache.ensure(
                input = activeVocalsInput,
                startFrame = startFrame,
                endFrameInclusive = endFrame,
                frameSize = sourceFrameSize,
            )
            fillResampledStem(
                output = scratch,
                outputFrames = frameCount,
                startPosition = startPosition,
                ratio = ratio,
                cache = vocalsResampleCache,
                frameSize = sourceFrameSize,
            )

            val instrumentalBytesRead = if (activeInstrumentalInput == null) {
                null
            } else {
                if (instrumentalScratch.size < outputByteCount) {
                    instrumentalScratch = ByteArray(outputByteCount)
                }
                instrumentalResampleCache.ensure(
                    input = activeInstrumentalInput,
                    startFrame = startFrame,
                    endFrameInclusive = endFrame,
                    frameSize = sourceFrameSize,
                )
                fillResampledStem(
                    output = instrumentalScratch,
                    outputFrames = frameCount,
                    startPosition = startPosition,
                    ratio = ratio,
                    cache = instrumentalResampleCache,
                    frameSize = sourceFrameSize,
                )
                outputByteCount
            }

            resampleStemFramePosition += frameCount * ratio
            val keepFromFrame = floor(resampleStemFramePosition).toLong().coerceAtLeast(0L)
            vocalsResampleCache.dropBefore(keepFromFrame, sourceFrameSize)
            instrumentalResampleCache.dropBefore(keepFromFrame, sourceFrameSize)
            StemReadResult(outputByteCount, instrumentalBytesRead)
        }
    }

    private fun fillResampledStem(
        output: ByteArray,
        outputFrames: Int,
        startPosition: Double,
        ratio: Double,
        cache: StemResampleCache,
        frameSize: Int,
    ) {
        var outputOffset = 0
        repeat(outputFrames) { outputFrame ->
            val sourcePosition = startPosition + outputFrame * ratio
            val sourceFrame = floor(sourcePosition).toLong().coerceAtLeast(0L)
            val fraction = sourcePosition - sourceFrame
            val left = interpolatePcm16(cache, sourceFrame, CHANNEL_LEFT, fraction, frameSize)
            val right = interpolatePcm16(cache, sourceFrame, CHANNEL_RIGHT, fraction, frameSize)
            output[outputOffset++] = (left and 0xFF).toByte()
            output[outputOffset++] = ((left ushr 8) and 0xFF).toByte()
            output[outputOffset++] = (right and 0xFF).toByte()
            output[outputOffset++] = ((right ushr 8) and 0xFF).toByte()
        }
    }

    private fun interpolatePcm16(
        cache: StemResampleCache,
        sourceFrame: Long,
        channel: Int,
        fraction: Double,
        frameSize: Int,
    ): Int {
        val current = cache.readPcm16(sourceFrame, channel, frameSize)
        val next = cache.readPcm16(sourceFrame + 1L, channel, frameSize)
        return (current + (next - current) * fraction)
            .roundToInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
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
                inputSample * currentInstrumentalGain + vocalSample * currentVocalsGain
            InputMode.OriginalSource ->
                if (hasInstrumentalStemInput) {
                    instrumentalSample * currentInstrumentalGain +
                            vocalSample * currentVocalsGain
                } else {
                    inputSample * currentInstrumentalGain +
                            vocalSample * (currentVocalsGain - currentInstrumentalGain)
                }
        }
        return output
            .toInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            .toShort()
    }

    private fun prepareGainRamp() {
        val snapshot = gainSnapshot.get()
        if (snapshot.generation == appliedGainGeneration) return
        appliedGainGeneration = snapshot.generation
        targetVocalsGain = snapshot.vocalsGain
        targetInstrumentalGain = snapshot.instrumentalGain
        val sampleRate = inputAudioFormat.sampleRate.takeIf { it > 0 } ?: DEFAULT_SAMPLE_RATE
        gainRampFramesRemaining =
            (sampleRate * GAIN_RAMP_MILLIS / MILLIS_PER_SECOND).coerceAtLeast(1)
        vocalsGainStep = (targetVocalsGain - currentVocalsGain) / gainRampFramesRemaining
        instrumentalGainStep =
            (targetInstrumentalGain - currentInstrumentalGain) / gainRampFramesRemaining
    }

    private fun advanceGainRamp() {
        if (gainRampFramesRemaining <= 0) return
        currentVocalsGain += vocalsGainStep
        currentInstrumentalGain += instrumentalGainStep
        gainRampFramesRemaining -= 1
        if (gainRampFramesRemaining == 0) {
            currentVocalsGain = targetVocalsGain
            currentInstrumentalGain = targetInstrumentalGain
        }
    }

    private fun applyGainSnapshotImmediately() {
        val snapshot = gainSnapshot.get()
        appliedGainGeneration = snapshot.generation
        currentVocalsGain = snapshot.vocalsGain
        currentInstrumentalGain = snapshot.instrumentalGain
        targetVocalsGain = snapshot.vocalsGain
        targetInstrumentalGain = snapshot.instrumentalGain
        vocalsGainStep = 0f
        instrumentalGainStep = 0f
        gainRampFramesRemaining = 0
    }

    private fun seekToLocked(positionMs: Long) {
        val sampleRate = stemSampleRate.takeIf { it > 0 } ?: DEFAULT_SAMPLE_RATE
        val frameSize = stemChannelCount
            .takeIf { it > 0 }
            ?.times(BYTES_PER_SAMPLE)
            ?: DEFAULT_FRAME_SIZE
        val frame = (positionMs.coerceAtLeast(0) * sampleRate / MILLIS_PER_SECOND.toFloat()).roundToLong()
        val engine = playbackEngine
        if (engine != null) {
            engine.seekTo(frame)
            resampleStemFramePosition = frame.toDouble()
            prepareEngineResampleCachesLocked(engine.blockFrameCapacity, frame)
            return
        }
        val bytePosition = frame * frameSize
        resampleStemFramePosition = frame.toDouble()
        clearResampleCachesLocked()
        vocalsInput?.seekToPcmByte(bytePosition)
        instrumentalInput?.seekToPcmByte(bytePosition)
    }

    private fun closeLocked() {
        playbackEngine?.close()
        playbackEngine = null
        engineStemBuffers = emptyArray()
        engineHasInstrumentalInput = false
        vocalsInput?.close()
        vocalsInput = null
        instrumentalInput?.close()
        instrumentalInput = null
        clearResampleCachesLocked()
        notifyMixedOutputStarted = false
        mixedOutputPrerollFramesRemaining = 0L
    }

    private fun clearResampleCachesLocked() {
        vocalsResampleCache.clear()
        instrumentalResampleCache.clear()
    }

    private fun prepareEngineResampleCachesLocked(
        blockFrameCapacity: Int,
        startFrame: Long,
    ) {
        val frameSize = stemChannelCount * BYTES_PER_SAMPLE
        val capacityFrames = blockFrameCapacity + 2
        vocalsResampleCache.prepare(startFrame, capacityFrames, frameSize)
        instrumentalResampleCache.prepare(startFrame, capacityFrames, frameSize)
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

    private fun openStemInput(file: File): StemPcmInput {
        return when {
            file.extension.equals("pcm", ignoreCase = true) -> {
                RawPcmStemInput(file)
            }
            else -> {
                WavStemPcmInput(file)
            }
        }
    }

    private fun createEngineFactories(
        vocalsFile: File,
        instrumentalFile: File?,
    ): List<SourceSeparationPlaybackStemSourceFactory>? {
        if (stemChannelCount != CHANNEL_COUNT_STEREO ||
            !isEngineFile(vocalsFile) ||
            (instrumentalFile != null && !isEngineFile(instrumentalFile))
        ) {
            return null
        }
        val factories = buildList {
            add(createEngineFactory(vocalsFile, "vocals"))
            instrumentalFile?.let { file -> add(createEngineFactory(file, "instrumental")) }
        }
        require(factories.all { factory ->
            factory.spec.geometry.sampleRate == stemSampleRate &&
                    factory.spec.geometry.channelCount == stemChannelCount
        }) {
            "Playback stem geometry does not match the separation contract."
        }
        require(factories.map { it.spec.geometry }.distinct().size == 1) {
            "Playback stems do not share one complete audio geometry."
        }
        return factories
    }

    private fun createEngineFactory(
        file: File,
        stemId: String,
    ): SourceSeparationPlaybackStemSourceFactory {
        return if (file.extension.equals("flac", ignoreCase = true)) {
            SourceSeparationFlacStemSourceFactory(
                file = file,
                stemId = stemId,
                traceSink = traceSinkForEngineSource(),
            )
        } else {
            SourceSeparationFileStemSourceFactory(
                file = file,
                stemId = stemId,
                sampleRate = stemSampleRate,
                channelCount = stemChannelCount,
            )
        }
    }

    private fun isEngineFile(file: File): Boolean {
        return file.extension.equals("wav", ignoreCase = true) ||
                file.extension.equals("pcm", ignoreCase = true) ||
                file.extension.equals("flac", ignoreCase = true)
    }

    private fun traceSinkForEngineSource(): ((String) -> Unit)? {
        return debugTraceSink?.let { sink ->
            { detail -> sink("mix.flac | $detail") }
        }
    }

    companion object {
        const val CENTER_BLEND = 0.5f
        const val DEFAULT_MIXED_OUTPUT_READY_PREROLL_MS =
            DEFAULT_SOURCE_SEPARATION_MIXED_OUTPUT_PREROLL_MS

        private const val DEBUG_INITIAL_QUEUE_TRACE_COUNT = 80
        private const val DEBUG_QUEUE_TRACE_INTERVAL = 200L
        private const val CHANNEL_COUNT_STEREO = 2
        private const val CHANNEL_LEFT = 0
        private const val CHANNEL_RIGHT = 1
        private const val BYTES_PER_SAMPLE = 2
        private const val DEFAULT_SAMPLE_RATE = 44_100
        private const val DEFAULT_FRAME_SIZE = CHANNEL_COUNT_STEREO * BYTES_PER_SAMPLE
        private const val RESAMPLE_READ_CHUNK_BYTES = 16 * 1024
        private const val GAIN_RAMP_MILLIS = 5
        private const val MILLIS_PER_SECOND = 1000
        private const val WAV_HEADER_SIZE = 44L
    }

    enum class InputMode {
        InstrumentalStem,
        OriginalSource,
    }

    private data class GainSnapshot(
        val generation: Long,
        val blend: Float,
        val vocalsGain: Float,
        val instrumentalGain: Float,
    ) {
        companion object {
            fun centered(): GainSnapshot = GainSnapshot(
                generation = 0L,
                blend = CENTER_BLEND,
                vocalsGain = 1f,
                instrumentalGain = 1f,
            )
        }
    }

    private interface StemPcmInput : Closeable {
        val pcmBytePosition: Long

        fun read(buffer: ByteArray, byteCount: Int): Int
        fun seekToPcmByte(bytePosition: Long)
    }

    private class StemResampleCache {
        private var baseFrame = 0L
        private var frameCount = 0
        private var bytes = ByteArray(0)
        private var readScratch = ByteArray(0)

        val endExclusiveFrame: Long
            get() = baseFrame + frameCount

        fun prepare(
            startFrame: Long,
            capacityFrames: Int,
            frameSize: Int,
        ) {
            require(capacityFrames > 0) { "Resample cache capacity must be positive." }
            ensureCapacity(capacityFrames * frameSize)
            baseFrame = startFrame.coerceAtLeast(0L)
            frameCount = 0
        }

        fun clear() {
            baseFrame = 0L
            frameCount = 0
        }

        fun ensure(
            input: StemPcmInput,
            startFrame: Long,
            endFrameInclusive: Long,
            frameSize: Int,
        ) {
            val safeStartFrame = startFrame.coerceAtLeast(0L)
            val safeEndFrame = endFrameInclusive.coerceAtLeast(safeStartFrame)
            val endExclusiveFrame = baseFrame + frameCount
            if (frameCount == 0 || safeStartFrame < baseFrame || safeStartFrame > endExclusiveFrame) {
                input.seekToPcmByte(safeStartFrame * frameSize)
                baseFrame = safeStartFrame
                frameCount = 0
            } else if (safeStartFrame > baseFrame) {
                dropBefore(safeStartFrame, frameSize)
            }

            val requiredEndExclusiveFrame = safeEndFrame + 1L
            val framesToAppend = (requiredEndExclusiveFrame - (baseFrame + frameCount))
                .coerceAtLeast(0L)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
            if (framesToAppend == 0) return

            val appendOffset = frameCount * frameSize
            val appendBytes = framesToAppend * frameSize
            ensureCapacity(appendOffset + appendBytes)
            var totalRead = 0
            while (totalRead < appendBytes) {
                val chunkSize = minOf(appendBytes - totalRead, RESAMPLE_READ_CHUNK_BYTES)
                if (readScratch.size < chunkSize) {
                    readScratch = ByteArray(chunkSize)
                }
                val count = input.read(readScratch, chunkSize)
                if (count <= 0) break
                readScratch.copyInto(
                    destination = bytes,
                    destinationOffset = appendOffset + totalRead,
                    startIndex = 0,
                    endIndex = count,
                )
                totalRead += count
            }
            if (totalRead < appendBytes) {
                bytes.fill(0, appendOffset + totalRead, appendOffset + appendBytes)
            }
            frameCount += framesToAppend
        }

        fun canAppend(frameCountToAppend: Int, frameSize: Int): Boolean {
            if (frameCountToAppend <= 0) return true
            return (frameCount + frameCountToAppend) * frameSize <= bytes.size
        }

        fun appendFrom(
            source: ByteArray,
            frameCount: Int,
            frameSize: Int,
        ): Boolean {
            if (frameCount < 0 || !canAppend(frameCount, frameSize)) return false
            val byteCount = frameCount * frameSize
            val destinationOffset = this.frameCount * frameSize
            source.copyInto(
                destination = bytes,
                destinationOffset = destinationOffset,
                startIndex = 0,
                endIndex = byteCount,
            )
            this.frameCount += frameCount
            return true
        }

        fun dropBefore(frame: Long, frameSize: Int) {
            val safeFrame = frame.coerceAtLeast(0L)
            if (frameCount == 0 || safeFrame <= baseFrame) return
            val framesToDrop = (safeFrame - baseFrame)
                .coerceAtMost(frameCount.toLong())
                .toInt()
            if (framesToDrop >= frameCount) {
                baseFrame = safeFrame
                frameCount = 0
                return
            }
            val bytesToDrop = framesToDrop * frameSize
            val remainingBytes = (frameCount - framesToDrop) * frameSize
            bytes.copyInto(
                destination = bytes,
                destinationOffset = 0,
                startIndex = bytesToDrop,
                endIndex = bytesToDrop + remainingBytes,
            )
            baseFrame += framesToDrop
            frameCount -= framesToDrop
        }

        fun readPcm16(frame: Long, channel: Int, frameSize: Int): Int {
            val frameOffset = frame - baseFrame
            if (frameOffset < 0L || frameOffset >= frameCount) return 0
            val byteOffset = frameOffset.toInt() * frameSize + channel * BYTES_PER_SAMPLE
            if (byteOffset + 1 >= bytes.size) return 0
            val low = bytes[byteOffset].toInt() and 0xFF
            val high = bytes[byteOffset + 1].toInt() and 0xFF
            return (low or (high shl 8)).toShort().toInt()
        }

        private fun ensureCapacity(requiredBytes: Int) {
            if (bytes.size >= requiredBytes) return
            var capacity = bytes.size.coerceAtLeast(DEFAULT_FRAME_SIZE)
            while (capacity < requiredBytes) {
                capacity *= 2
            }
            bytes = bytes.copyOf(capacity)
        }
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

    private data class StemReadResult(
        val vocalsBytesRead: Int?,
        val instrumentalBytesRead: Int?,
    )
}
