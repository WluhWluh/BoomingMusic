package com.mardous.booming.playback.processor

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.mardous.booming.playback.SourceSeparationPlaybackDataState
import com.mardous.booming.playback.SourceSeparationPlaybackFrameAvailability
import com.mardous.booming.separation.audio.Pcm16StereoFlacEncoder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SourceSeparationMixAudioProcessorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun partialCacheUnderflowDropsBlockedTransportInputWithoutQueuingSilence() {
        val frameCount = 32_768
        val vocals = writeWav("partial-vocals.wav", frameCount, 1_000)
        val instrumental = writeWav("partial-instrumental.wav", frameCount, 2_000)
        val readableEndFrame = AtomicLong(0L)
        val processor = SourceSeparationMixAudioProcessor()
        val stateChanges = CopyOnWriteArrayList<SourceSeparationPlaybackDataState>()
        processor.dataPlaneStateChangedSink = stateChanges::add
        try {
            val preparedInputs = processor.prepareInputs(
                stemFiles = listOf(vocals, instrumental),
                stemIds = MDX_STEM_IDS,
                stemSampleRate = 44_100,
                stemChannelCount = 2,
                frameAvailability = SourceSeparationPlaybackFrameAvailability {
                    readableEndFrame.get()
                },
            )
            processor.configure(AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT))
            processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
            processor.enable(
                stemFiles = listOf(vocals, instrumental),
                stemIds = MDX_STEM_IDS,
                blendEndpointStemIds = MDX_STEM_IDS,
                positionMs = 0L,
                inputMode = SourceSeparationMixAudioProcessor.InputMode.OriginalSource,
                stemSampleRate = 44_100,
                stemChannelCount = 2,
                mixedOutputReadyPrerollMs = 0L,
                preparedInputs = preparedInputs,
            )
            await { processor.dataPlaneState() == SourceSeparationPlaybackDataState.Buffering }
            assertEquals(0L, processor.dataPlaneUnavailableFrame())
            stateChanges.clear()

            val input = silentInput(512)
            processor.queueInput(input)

            assertEquals(input.limit(), input.position())
            assertFalse(processor.output.hasRemaining())
            assertEquals(
                listOf(SourceSeparationPlaybackDataState.Buffering),
                stateChanges.toList(),
            )

            val repeatedInput = silentInput(512)
            processor.queueInput(repeatedInput)
            assertEquals(repeatedInput.limit(), repeatedInput.position())
            assertEquals(1, stateChanges.size)

            readableEndFrame.set(frameCount.toLong())
            await { processor.isDataPlaneReady() }
            assertEquals(null, processor.dataPlaneUnavailableFrame())
            input.rewind()
            processor.queueInput(input)

            assertEquals(input.limit(), input.position())
            assertLastSample(processor.output, 3_000)
        } finally {
            processor.disable()
        }
    }

    @Test
    fun outputFlushBarrierSkipsPrerollOnlyAfterTheAudioProcessorFlushes() {
        val vocals = writeWav("flush-barrier-vocals.wav", 16_384, 1_000)
        val instrumental = writeWav("flush-barrier-instrumental.wav", 16_384, 2_000)
        val processor = SourceSeparationMixAudioProcessor()
        val flushes = CopyOnWriteArrayList<Pair<Long, Long>>()
        val mixedOutputGenerations = CopyOnWriteArrayList<Long>()
        processor.outputFlushedSink = { barrierId, outputGeneration ->
            flushes += barrierId to outputGeneration
        }
        processor.mixedOutputStartedSink = { outputGeneration ->
            mixedOutputGenerations += outputGeneration
        }
        try {
            processor.configure(AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT))
            processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
            processor.enable(
                stemFiles = listOf(vocals, instrumental),
                stemIds = MDX_STEM_IDS,
                blendEndpointStemIds = MDX_STEM_IDS,
                positionMs = 0L,
                inputMode = SourceSeparationMixAudioProcessor.InputMode.OriginalSource,
                stemSampleRate = 44_100,
                stemChannelCount = 2,
                mixedOutputReadyPrerollMs = 400L,
            )
            await { processor.isDataPlaneReady() }

            processor.queueInput(silentInput(1))
            processor.output
            assertTrue(mixedOutputGenerations.isEmpty())

            assertTrue(processor.armOutputFlushBarrier(73L))
            processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
            processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
            assertEquals(2, flushes.size)
            assertTrue(flushes.all { (barrierId, _) -> barrierId == 73L })
            assertTrue(flushes[1].second > flushes[0].second)

            processor.queueInput(silentInput(1))
            processor.output
            assertEquals(listOf(flushes.last().second), mixedOutputGenerations)

            processor.cancelOutputFlushBarrier(73L)
            processor.seekTo(100L)
            processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
            processor.queueInput(silentInput(1))
            processor.output
            assertEquals(2, flushes.size)
            assertEquals(listOf(flushes.last().second), mixedOutputGenerations)
        } finally {
            processor.disable()
        }
    }

    @Test
    fun fourStemWavSessionUsesOrderedGainsAndClampsOnce() {
        val frames = 16_384
        val stems = List(4) { index ->
            writeWav("four-stem-$index.wav", frames, (index + 1) * 1_000)
        }
        val processor = SourceSeparationMixAudioProcessor()
        try {
            processor.configure(
                AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT),
            )
            processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
            processor.enable(
                stemFiles = stems,
                stemIds = listOf("vocals", "drums", "bass", "other"),
                initialGains = listOf(1f, 2f, 3f, 4f),
                positionMs = 0L,
                stemSampleRate = 44_100,
                stemChannelCount = 2,
                mixedOutputReadyPrerollMs = 0L,
            )
            await { processor.isDataPlaneReady() }

            val initialInput = silentInput(4)
            processor.queueInput(initialInput)
            val initialOutput = processor.output.order(ByteOrder.LITTLE_ENDIAN)
            repeat(8) { assertEquals(30_000, initialOutput.short.toInt()) }

            processor.setStemGains(List(4) { 4f })
            val rampInput = silentInput(512)
            processor.queueInput(rampInput)
            val rampOutput = processor.output.order(ByteOrder.LITTLE_ENDIAN)
            rampOutput.position(rampOutput.limit() - 2)
            assertEquals(Short.MAX_VALUE.toInt(), rampOutput.short.toInt())
        } finally {
            processor.disable()
        }
    }

    @Test
    fun reversedMdxContractAppliesBlendToStemIdsInsteadOfFileOrder() {
        val frames = 16_384
        val instrumental = writeWav("reversed-instrumental.wav", frames, 2_000)
        val vocals = writeWav("reversed-vocals.wav", frames, 1_000)
        val processor = SourceSeparationMixAudioProcessor()
        try {
            processor.configure(AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT))
            processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
            processor.enable(
                stemFiles = listOf(instrumental, vocals),
                stemIds = listOf("instrumental", "vocals"),
                blendEndpointStemIds = listOf("vocals", "instrumental"),
                positionMs = 0L,
                stemSampleRate = 44_100,
                stemChannelCount = 2,
                mixedOutputReadyPrerollMs = 0L,
            )
            await { processor.isDataPlaneReady() }

            processor.setBlend(0f)
            processor.queueInput(silentInput(512))
            assertLastSample(processor.output, 1_000)

            processor.setBlend(1f)
            processor.queueInput(silentInput(512))
            assertLastSample(processor.output, 2_000)
        } finally {
            processor.disable()
        }
    }

    @Test
    fun genericMdxContractBlendsFromResidualToTargetStem() {
        val frames = 16_384
        val bass = writeWav("generic-bass.wav", frames, 2_000)
        val remaining = writeWav("generic-remaining.wav", frames, 1_000)
        val processor = SourceSeparationMixAudioProcessor()
        try {
            processor.configure(AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT))
            processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
            processor.enable(
                stemFiles = listOf(bass, remaining),
                stemIds = listOf("bass", "remaining_audio"),
                blendEndpointStemIds = listOf("remaining_audio", "bass"),
                positionMs = 0L,
                stemSampleRate = 44_100,
                stemChannelCount = 2,
                mixedOutputReadyPrerollMs = 0L,
            )
            await { processor.isDataPlaneReady() }

            processor.setBlend(0f)
            processor.queueInput(silentInput(512))
            assertLastSample(processor.output, 1_000)

            processor.setBlend(1f)
            processor.queueInput(silentInput(512))
            assertLastSample(processor.output, 2_000)
        } finally {
            processor.disable()
        }
    }

    @Test
    fun blendCommandDoesNotRewriteMultistemGainsWithoutMdxEndpoints() {
        val frames = 16_384
        val stems = List(4) { index ->
            writeWav("blend-independent-$index.wav", frames, (index + 1) * 100)
        }
        val processor = SourceSeparationMixAudioProcessor()
        try {
            processor.configure(AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT))
            processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
            processor.enable(
                stemFiles = stems,
                stemIds = listOf("drums", "bass", "other", "vocals"),
                initialGains = listOf(0f, 1f, 0f, 0f),
                positionMs = 0L,
                stemSampleRate = 44_100,
                stemChannelCount = 2,
                mixedOutputReadyPrerollMs = 0L,
            )
            await { processor.isDataPlaneReady() }

            processor.setBlend(0f)
            processor.queueInput(silentInput(4))
            val output = processor.output.order(ByteOrder.LITTLE_ENDIAN)
            repeat(8) { assertEquals(200, output.short.toInt()) }
        } finally {
            processor.disable()
        }
    }

    @Test
    fun sixStemEngineResamplingPreservesTheOrderedSum() {
        val frames = 16_384
        val stems = List(6) { index ->
            writeWav("six-stem-$index.wav", frames, (index + 1) * 100)
        }
        val processor = SourceSeparationMixAudioProcessor()
        try {
            processor.configure(
                AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT),
            )
            processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
            processor.enable(
                stemFiles = stems,
                stemIds = List(6) { index -> "stem-$index" },
                initialGains = List(6) { index -> (index + 1).toFloat() },
                positionMs = 0L,
                stemSampleRate = 44_100,
                stemChannelCount = 2,
                mixedOutputReadyPrerollMs = 0L,
            )
            await { processor.isDataPlaneReady() }

            processor.queueInput(silentInput(480))
            val output = processor.output.order(ByteOrder.LITTLE_ENDIAN)
            repeat(480 * 2) { assertEquals(9_100, output.short.toInt()) }
        } finally {
            processor.disable()
        }
    }

    @Test
    fun eightStemSessionPreservesOneHotStemSelection() {
        val frames = 16_384
        val stems = List(8) { index ->
            writeWav("eight-stem-$index.wav", frames, (index + 1) * 500)
        }
        val processor = SourceSeparationMixAudioProcessor()
        try {
            processor.configure(
                AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT),
            )
            processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
            processor.enable(
                stemFiles = stems,
                stemIds = List(8) { index -> "stem-$index" },
                initialGains = List(8) { index -> if (index == 5) 1f else 0f },
                positionMs = 0L,
                stemSampleRate = 44_100,
                stemChannelCount = 2,
                mixedOutputReadyPrerollMs = 0L,
            )
            await { processor.isDataPlaneReady() }

            processor.queueInput(silentInput(4))
            val output = processor.output.order(ByteOrder.LITTLE_ENDIAN)
            repeat(8) { assertEquals(3_000, output.short.toInt()) }
        } finally {
            processor.disable()
        }
    }

    @Test
    fun multistemSessionRejectsUnequalLengthsBeforePlayback() {
        val stems = listOf(
            writeWav("unequal-stem-0.wav", 16_384, 100),
            writeWav("unequal-stem-1.wav", 16_384, 200),
            writeWav("unequal-stem-2.wav", 8_192, 300),
            writeWav("unequal-stem-3.wav", 16_384, 400),
        )
        val processor = SourceSeparationMixAudioProcessor()
        try {
            assertThrows(IllegalArgumentException::class.java) {
                processor.enable(
                    stemFiles = stems,
                    positionMs = 0L,
                    stemSampleRate = 44_100,
                    stemChannelCount = 2,
                )
            }
        } finally {
            processor.disable()
        }
    }

    @Test
    fun malformedWavRejectsTheCompleteTwoFourSixAndEightStemSessions() {
        listOf(2, 4, 6, 8).forEachIndexed { geometryIndex, stemCount ->
            val stems = List(stemCount) { stemIndex ->
                writeWav(
                    "malformed-$stemCount-stem-$stemIndex.wav",
                    4_096,
                    (stemIndex + 1) * 100,
                )
            }
            val malformed = stems[stemCount / 2]
            RandomAccessFile(malformed, "rw").use { file ->
                when (geometryIndex) {
                    0 -> {
                        file.seek(0L)
                        file.writeBytes("NOPE")
                    }
                    1 -> {
                        file.seek(24L)
                        file.writeLittleEndianInt(48_000)
                    }
                    2 -> {
                        file.seek(40L)
                        file.writeLittleEndianInt(Int.MAX_VALUE)
                    }
                    else -> file.setLength(file.length() - 1L)
                }
            }

            val processor = SourceSeparationMixAudioProcessor()
            try {
                assertThrows(IllegalArgumentException::class.java) {
                    processor.enable(
                        stemFiles = stems,
                        stemIds = List(stemCount) { stemIndex -> "stem-$stemIndex" },
                        positionMs = 0L,
                        stemSampleRate = 44_100,
                        stemChannelCount = 2,
                    )
                }
            } finally {
                processor.disable()
            }
        }
    }

    @Test
    fun wavStemsPlayThroughBoundedEngineWithExistingBlendLaw() {
        val frames = 65_536
        val vocals = writeWav("vocals.wav", frames, 1_000)
        val instrumental = writeWav("instrumental.wav", frames, 2_000)
        val processor = SourceSeparationMixAudioProcessor()
        try {
            processor.configure(
                AudioProcessor.AudioFormat(
                    44_100,
                    2,
                    C.ENCODING_PCM_16BIT,
                ),
            )
            processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
            processor.enable(
                stemFiles = listOf(vocals, instrumental),
                stemIds = MDX_STEM_IDS,
                blendEndpointStemIds = MDX_STEM_IDS,
                positionMs = 0L,
                inputMode = SourceSeparationMixAudioProcessor.InputMode.OriginalSource,
                stemSampleRate = 44_100,
                stemChannelCount = 2,
                mixedOutputReadyPrerollMs = 0L,
            )
            await { processor.isDataPlaneReady() }

            val input = ByteBuffer.allocateDirect(4 * BYTES_PER_FRAME)
                .order(ByteOrder.LITTLE_ENDIAN)
            repeat(4) {
                input.putShort(9_000)
                input.putShort(9_000)
            }
            input.flip()
            processor.queueInput(input)
            val output = processor.output.order(ByteOrder.LITTLE_ENDIAN)
            repeat(8) {
                assertEquals(3_000, output.short.toInt())
            }
            val metrics = requireNotNull(processor.dataPlaneMetrics())
            assertTrue(metrics.audioThreadTimeNs.count > 0)
            assertEquals(2L, metrics.openFileDescriptors)
        } finally {
            processor.disable()
        }
    }

    @Test
    fun boundedEngineResamplesStemsWithoutReturningSilentFallback() {
        val frames = 16_384
        val vocals = writeWav("resampled-vocals.wav", frames, 1_000)
        val instrumental = writeWav("resampled-instrumental.wav", frames, 2_000)
        val processor = SourceSeparationMixAudioProcessor()
        try {
            processor.configure(
                AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT),
            )
            processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
            processor.enable(
                stemFiles = listOf(vocals, instrumental),
                stemIds = MDX_STEM_IDS,
                blendEndpointStemIds = MDX_STEM_IDS,
                positionMs = 0L,
                inputMode = SourceSeparationMixAudioProcessor.InputMode.OriginalSource,
                stemSampleRate = 44_100,
                stemChannelCount = 2,
                mixedOutputReadyPrerollMs = 0L,
            )
            await { processor.isDataPlaneReady() }

            val input = ByteBuffer.allocateDirect(480 * BYTES_PER_FRAME)
                .order(ByteOrder.LITTLE_ENDIAN)
            repeat(480) {
                input.putShort(9_000)
                input.putShort(9_000)
            }
            input.flip()
            processor.queueInput(input)
            val output = processor.output.order(ByteOrder.LITTLE_ENDIAN)
            repeat(480 * 2) {
                assertEquals(3_000, output.short.toInt())
            }
        } finally {
            processor.disable()
        }
    }

    @Test
    fun indexedFlacStemsUseTheSameBoundedEngineWithoutWholeSongFallback() {
        val frames = 16_384
        val vocalsWav = writeWav("vocals-source.wav", frames, 1_000)
        val instrumentalWav = writeWav("instrumental-source.wav", frames, 2_000)
        val vocalsFlac = temporaryFolder.newFile("vocals.flac")
        val instrumentalFlac = temporaryFolder.newFile("instrumental.flac")
        Pcm16StereoFlacEncoder.encodeWavToFlac(
            wavFile = vocalsWav,
            flacFile = vocalsFlac,
            expectedSampleRate = 44_100,
            expectedFrameCount = frames,
        )
        Pcm16StereoFlacEncoder.encodeWavToFlac(
            wavFile = instrumentalWav,
            flacFile = instrumentalFlac,
            expectedSampleRate = 44_100,
            expectedFrameCount = frames,
        )
        val processor = SourceSeparationMixAudioProcessor()
        try {
            processor.configure(
                AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT),
            )
            processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
            processor.enable(
                stemFiles = listOf(vocalsFlac, instrumentalFlac),
                stemIds = MDX_STEM_IDS,
                blendEndpointStemIds = MDX_STEM_IDS,
                positionMs = 0L,
                inputMode = SourceSeparationMixAudioProcessor.InputMode.OriginalSource,
                stemSampleRate = 44_100,
                stemChannelCount = 2,
                mixedOutputReadyPrerollMs = 0L,
            )
            await { processor.isDataPlaneReady() }
            val seekRequestsBeforeFlush = requireNotNull(processor.dataPlaneMetrics()).seekRequests
            processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
            assertEquals(
                seekRequestsBeforeFlush,
                requireNotNull(processor.dataPlaneMetrics()).seekRequests,
            )
            val input = ByteBuffer.allocateDirect(4 * BYTES_PER_FRAME)
                .order(ByteOrder.LITTLE_ENDIAN)
            repeat(4) {
                input.putShort(9_000)
                input.putShort(9_000)
            }
            input.flip()
            processor.queueInput(input)
            val output = processor.output.order(ByteOrder.LITTLE_ENDIAN)
            repeat(8) { assertEquals(3_000, output.short.toInt()) }
        } finally {
            processor.disable()
        }
    }

    @Test
    fun fourStemIndexedFlacSessionUsesTheCompleteOrderedSet() {
        val frames = 16_384
        val stems = List(4) { index ->
            writeIndexedFlac(
                name = "four-stem-$index.flac",
                frames = frames,
                sample = (index + 1) * 100,
            )
        }
        val traces = CopyOnWriteArrayList<String>()
        val processor = SourceSeparationMixAudioProcessor()
        try {
            processor.debugTraceSink = traces::add
            processor.configure(
                AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT),
            )
            processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
            processor.enable(
                stemFiles = stems,
                stemIds = listOf("vocals", "drums", "bass", "other"),
                positionMs = 0L,
                stemSampleRate = 44_100,
                stemChannelCount = 2,
                mixedOutputReadyPrerollMs = 0L,
            )
            await { processor.isDataPlaneReady() }

            processor.queueInput(silentInput(4))
            val output = processor.output.order(ByteOrder.LITTLE_ENDIAN)
            repeat(8) { assertEquals(1_000, output.short.toInt()) }
            assertFalse(traces.any { trace -> "fallbackWholeFileDecode" in trace })
        } finally {
            processor.disable()
        }
    }

    @Test
    fun missingIndexedFlacSidecarRejectsTheCompleteMultistemSession() {
        STEM_GEOMETRIES.forEach { stemCount ->
            val stems = List(stemCount) { index ->
                writeIndexedFlac(
                    "missing-index-$stemCount-$index.flac",
                    FLAC_FAILURE_FRAME_COUNT,
                    (index + 1) * 100,
                )
            }
            Pcm16StereoFlacEncoder.frameIndexFileFor(stems[stemCount / 2]).delete()
            val processor = SourceSeparationMixAudioProcessor()
            try {
                assertThrows(IllegalArgumentException::class.java) {
                    processor.enable(
                        stemFiles = stems,
                        stemIds = List(stemCount) { index -> "stem-$index" },
                        positionMs = 0L,
                        stemSampleRate = 44_100,
                        stemChannelCount = 2,
                    )
                }
            } finally {
                processor.disable()
            }
        }
    }

    @Test
    fun truncatedIndexedFlacRejectsOrFailsTheCompleteMultistemSession() {
        STEM_GEOMETRIES.forEach { stemCount ->
            val stems = List(stemCount) { index ->
                writeIndexedFlac(
                    "truncated-$stemCount-$index.flac",
                    FLAC_FAILURE_FRAME_COUNT,
                    (index + 1) * 100,
                )
            }
            RandomAccessFile(stems.last(), "rw").use { file ->
                file.setLength((file.length() - 32L).coerceAtLeast(0L))
            }
            val processor = SourceSeparationMixAudioProcessor()
            try {
                val installation = runCatching {
                    processor.enable(
                        stemFiles = stems,
                        stemIds = List(stemCount) { index -> "stem-$index" },
                        positionMs = 0L,
                        stemSampleRate = 44_100,
                        stemChannelCount = 2,
                    )
                }
                if (installation.isSuccess) {
                    await {
                        processor.dataPlaneState() ==
                                com.mardous.booming.playback.SourceSeparationPlaybackDataState.Failed
                    }
                } else {
                    assertTrue(installation.exceptionOrNull() is IllegalArgumentException)
                }
            } finally {
                processor.disable()
            }
        }
    }

    @Test
    fun frameCrcFailureFailsTheCompleteMultistemSessionWithoutFallback() {
        STEM_GEOMETRIES.forEach { stemCount ->
            val stems = List(stemCount) { index ->
                writeIndexedFlac(
                    "crc-failure-$stemCount-$index.flac",
                    FLAC_FAILURE_FRAME_COUNT,
                    (index + 1) * 100,
                )
            }
            corruptFirstFlacFrame(stems[1])
            val traces = CopyOnWriteArrayList<String>()
            val processor = SourceSeparationMixAudioProcessor()
            try {
                processor.debugTraceSink = traces::add
                processor.configure(
                    AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT),
                )
                processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
                processor.enable(
                    stemFiles = stems,
                    stemIds = List(stemCount) { index -> "stem-$index" },
                    positionMs = 0L,
                    stemSampleRate = 44_100,
                    stemChannelCount = 2,
                    mixedOutputReadyPrerollMs = 0L,
                )
                await {
                    processor.dataPlaneState() ==
                            com.mardous.booming.playback.SourceSeparationPlaybackDataState.Failed
                }
                assertFalse(traces.any { trace -> "fallbackWholeFileDecode" in trace })
            } finally {
                processor.disable()
            }
        }
    }

    @Test
    fun pcmHotSwapUsesOneLogicalFrameBarrier() {
        val frames = 16_384
        val vocals = writeWav("initial-vocals.wav", frames, 1_000)
        val instrumental = writeWav("initial-instrumental.wav", frames, 2_000)
        val replacementVocals = writePcm("replacement-vocals.pcm", frames, 3_000)
        val replacementInstrumental = writePcm("replacement-instrumental.pcm", frames, 4_000)
        val processor = SourceSeparationMixAudioProcessor()
        try {
            processor.configure(AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT))
            processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
            processor.enable(
                stemFiles = listOf(vocals, instrumental),
                stemIds = MDX_STEM_IDS,
                blendEndpointStemIds = MDX_STEM_IDS,
                positionMs = 0L,
                inputMode = SourceSeparationMixAudioProcessor.InputMode.OriginalSource,
                stemSampleRate = 44_100,
                stemChannelCount = 2,
                mixedOutputReadyPrerollMs = 0L,
            )
            await { processor.isDataPlaneReady() }
            assertEquals(true, processor.hotSwapToPcmInputs(
                stemFiles = listOf(replacementVocals, replacementInstrumental),
                stemIds = MDX_STEM_IDS,
            ))
            await { processor.isDataPlaneReady() }

            val input = ByteBuffer.allocateDirect(4 * BYTES_PER_FRAME)
                .order(ByteOrder.LITTLE_ENDIAN)
            repeat(4) {
                input.putShort(9_000)
                input.putShort(9_000)
            }
            input.flip()
            processor.queueInput(input)
            val output = processor.output.order(ByteOrder.LITTLE_ENDIAN)
            repeat(8) { assertEquals(7_000, output.short.toInt()) }
        } finally {
            processor.disable()
        }
    }

    @Test
    fun multistemPcmHotSwapReplacesTheCompleteOrderedSet() {
        val frames = 16_384
        val initial = List(4) { index ->
            writeWav("multistem-initial-$index.wav", frames, (index + 1) * 100)
        }
        val replacement = List(4) { index ->
            writePcm("multistem-replacement-$index.pcm", frames, (index + 1) * 1_000)
        }
        val stemIds = listOf("vocals", "drums", "bass", "other")
        val processor = SourceSeparationMixAudioProcessor()
        try {
            processor.configure(AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT))
            processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
            processor.enable(
                stemFiles = initial,
                stemIds = stemIds,
                positionMs = 0L,
                stemSampleRate = 44_100,
                stemChannelCount = 2,
                mixedOutputReadyPrerollMs = 0L,
            )
            await { processor.isDataPlaneReady() }
            assertTrue(
                processor.hotSwapToPcmInputs(
                    stemFiles = replacement,
                    stemIds = stemIds,
                ),
            )
            await { processor.isDataPlaneReady() }

            processor.queueInput(silentInput(4))
            val output = processor.output.order(ByteOrder.LITTLE_ENDIAN)
            repeat(8) { assertEquals(10_000, output.short.toInt()) }
        } finally {
            processor.disable()
        }
    }

    @Test
    fun gainChangesRampWithoutChangingTheSteadyStateBlendLaw() {
        val frames = 16_384
        val vocals = writeWav("ramp-vocals.wav", frames, 1_000)
        val instrumental = writeWav("ramp-instrumental.wav", frames, 2_000)
        val processor = SourceSeparationMixAudioProcessor()
        try {
            processor.configure(AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT))
            processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
            processor.enable(
                stemFiles = listOf(vocals, instrumental),
                stemIds = MDX_STEM_IDS,
                blendEndpointStemIds = MDX_STEM_IDS,
                positionMs = 0L,
                inputMode = SourceSeparationMixAudioProcessor.InputMode.OriginalSource,
                stemSampleRate = 44_100,
                stemChannelCount = 2,
                mixedOutputReadyPrerollMs = 0L,
            )
            await { processor.isDataPlaneReady() }
            processor.setBlend(0f)
            val input = ByteBuffer.allocateDirect(512 * BYTES_PER_FRAME)
                .order(ByteOrder.LITTLE_ENDIAN)
            repeat(512) {
                input.putShort(9_000)
                input.putShort(9_000)
            }
            input.flip()
            processor.queueInput(input)
            val output = processor.output.order(ByteOrder.LITTLE_ENDIAN)
            val first = output.short.toInt()
            output.position(output.limit() - 2)
            val last = output.short.toInt()
            assertTrue(first in 1_001..2_999)
            assertEquals(1_000, last)
        } finally {
            processor.disable()
        }
    }

    @Test
    fun unequalStemLengthsAreRejectedBeforeSessionInstallation() {
        val vocals = writeWav("unequal-vocals.wav", 16_384, 1_000)
        val instrumental = writeWav("unequal-instrumental.wav", 8_192, 2_000)
        val processor = SourceSeparationMixAudioProcessor()
        try {
            assertThrows(IllegalArgumentException::class.java) {
                processor.enable(
                    stemFiles = listOf(vocals, instrumental),
                    stemIds = MDX_STEM_IDS,
                    blendEndpointStemIds = MDX_STEM_IDS,
                    positionMs = 0L,
                    inputMode = SourceSeparationMixAudioProcessor.InputMode.OriginalSource,
                )
            }
        } finally {
            processor.disable()
        }
    }

    private fun writeWav(name: String, frames: Int, sample: Int): File {
        val file = temporaryFolder.newFile(name)
        RandomAccessFile(file, "rw").use { output ->
            val dataBytes = frames * BYTES_PER_FRAME
            output.writeBytes("RIFF")
            output.writeLittleEndianInt(36 + dataBytes)
            output.writeBytes("WAVE")
            output.writeBytes("fmt ")
            output.writeLittleEndianInt(16)
            output.writeLittleEndianShort(1)
            output.writeLittleEndianShort(2)
            output.writeLittleEndianInt(44_100)
            output.writeLittleEndianInt(44_100 * BYTES_PER_FRAME)
            output.writeLittleEndianShort(BYTES_PER_FRAME)
            output.writeLittleEndianShort(16)
            output.writeBytes("data")
            output.writeLittleEndianInt(dataBytes)
            repeat(frames * 2) {
                output.writeLittleEndianShort(sample)
            }
        }
        return file
    }

    private fun assertLastSample(buffer: ByteBuffer, expected: Int) {
        val output = buffer.order(ByteOrder.LITTLE_ENDIAN)
        output.position(output.limit() - Short.SIZE_BYTES)
        assertEquals(expected, output.short.toInt())
    }

    private fun writePcm(name: String, frames: Int, sample: Int): File {
        val file = temporaryFolder.newFile(name)
        RandomAccessFile(file, "rw").use { output ->
            repeat(frames * 2) { output.writeLittleEndianShort(sample) }
        }
        return file
    }

    private fun writeIndexedFlac(name: String, frames: Int, sample: Int): File {
        val wav = writeWav("$name.wav", frames, sample)
        val flac = temporaryFolder.newFile(name)
        Pcm16StereoFlacEncoder.encodeWavToFlac(
            wavFile = wav,
            flacFile = flac,
            expectedSampleRate = 44_100,
            expectedFrameCount = frames,
        )
        return flac
    }

    private fun corruptFirstFlacFrame(flac: File) {
        val index = Pcm16StereoFlacEncoder.frameIndexFileFor(flac)
        val firstFrameOffset = index.readLines()
            .first { line -> line.startsWith("0,") }
            .split(',')[3]
            .toLong()
        RandomAccessFile(flac, "rw").use { file ->
            file.seek(firstFrameOffset + 8L)
            file.write(file.read().xor(0x01))
        }
    }

    private fun silentInput(frames: Int): ByteBuffer {
        return ByteBuffer.allocateDirect(frames * BYTES_PER_FRAME)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                repeat(frames * 2) { putShort(0) }
                flip()
            }
    }

    private fun RandomAccessFile.writeLittleEndianInt(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 24) and 0xFF)
    }

    private fun RandomAccessFile.writeLittleEndianShort(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
    }

    private fun await(timeoutMs: Long = 2_000L, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(2L)
        }
        check(condition()) { "Timed out waiting for separated playback data." }
    }

    private companion object {
        val MDX_STEM_IDS = listOf("vocals", "instrumental")
        const val BYTES_PER_FRAME = 4
        const val FLAC_FAILURE_FRAME_COUNT = 4_096
        val STEM_GEOMETRIES = listOf(2, 4, 6, 8)
    }
}
