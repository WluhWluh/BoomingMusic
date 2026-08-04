package com.mardous.booming.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mardous.booming.playback.processor.SourceSeparationMixAudioProcessor
import com.mardous.booming.separation.audio.Pcm16StereoFlacEncoder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.roundToLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourceSeparationPlaybackDataPlaneDeviceTest {
    @Test
    fun indexedFlacOutputSurvivesOneHundredRandomSeeks() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "stem-playback-device-test").apply {
            deleteRecursively()
            check(mkdirs())
        }
        val vocalsWav = File(root, "vocals.wav")
        val instrumentalWav = File(root, "instrumental.wav")
        val vocalsFlac = File(root, "vocals.flac")
        val instrumentalFlac = File(root, "instrumental.flac")
        val processor = SourceSeparationMixAudioProcessor()
        try {
            writeWav(vocalsWav, FRAME_COUNT, ::vocalSample)
            writeWav(instrumentalWav, FRAME_COUNT, ::instrumentalSample)
            Pcm16StereoFlacEncoder.encodeWavToFlac(
                wavFile = vocalsWav,
                flacFile = vocalsFlac,
                expectedSampleRate = SAMPLE_RATE,
                expectedFrameCount = FRAME_COUNT,
            )
            Pcm16StereoFlacEncoder.encodeWavToFlac(
                wavFile = instrumentalWav,
                flacFile = instrumentalFlac,
                expectedSampleRate = SAMPLE_RATE,
                expectedFrameCount = FRAME_COUNT,
            )

            val prepared = processor.prepareInputs(
                vocalsFile = vocalsFlac,
                instrumentalFile = instrumentalFlac,
                stemSampleRate = SAMPLE_RATE,
                stemChannelCount = CHANNEL_COUNT,
            )
            processor.configure(
                AudioProcessor.AudioFormat(SAMPLE_RATE, CHANNEL_COUNT, C.ENCODING_PCM_16BIT),
            )
            processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
            processor.enable(
                vocalsFile = vocalsFlac,
                instrumentalFile = instrumentalFlac,
                positionMs = 0L,
                initialBlend = SourceSeparationMixAudioProcessor.CENTER_BLEND,
                inputMode = SourceSeparationMixAudioProcessor.InputMode.OriginalSource,
                stemSampleRate = SAMPLE_RATE,
                stemChannelCount = CHANNEL_COUNT,
                mixedOutputReadyPrerollMs = 0L,
                preparedInputs = prepared,
            )
            awaitReady(processor)

            val random = Random(0x425353L)
            repeat(SEEK_COUNT) {
                val positionMs = random.nextInt(MAX_SEEK_POSITION_MS + 1).toLong()
                processor.seekTo(positionMs)
                awaitReady(processor)
                val expectedStartFrame = (
                    positionMs * SAMPLE_RATE / MILLIS_PER_SECOND.toFloat()
                ).roundToLong().toInt()
                val input = ByteBuffer.allocateDirect(READ_FRAMES * BYTES_PER_FRAME)
                    .order(ByteOrder.LITTLE_ENDIAN)
                repeat(READ_FRAMES * CHANNEL_COUNT) { input.putShort(9_000) }
                input.flip()
                processor.queueInput(input)
                val output = processor.output.order(ByteOrder.LITTLE_ENDIAN)
                repeat(READ_FRAMES) { frameOffset ->
                    val frame = expectedStartFrame + frameOffset
                    val expected = vocalSample(frame) + instrumentalSample(frame)
                    assertEquals(expected, output.short.toInt())
                    assertEquals(expected, output.short.toInt())
                }
            }

            val metrics = requireNotNull(processor.dataPlaneMetrics())
            assertEquals(SEEK_COUNT.toLong(), metrics.seekRequests)
            assertTrue(metrics.decodeBlockCount >= SEEK_COUNT)
            assertEquals(0L, metrics.underruns)
        } finally {
            processor.disable()
            root.deleteRecursively()
        }
    }

    private fun awaitReady(processor: SourceSeparationMixAudioProcessor) {
        val deadline = System.nanoTime() + READY_TIMEOUT_MS * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (processor.isDataPlaneReady()) return
            Thread.sleep(2L)
        }
        assertTrue("Timed out waiting for indexed FLAC playback", processor.isDataPlaneReady())
    }

    private fun writeWav(file: File, frameCount: Int, sample: (Int) -> Int) {
        RandomAccessFile(file, "rw").use { output ->
            val dataBytes = frameCount * BYTES_PER_FRAME
            output.writeBytes("RIFF")
            output.writeLittleEndianInt(36 + dataBytes)
            output.writeBytes("WAVE")
            output.writeBytes("fmt ")
            output.writeLittleEndianInt(16)
            output.writeLittleEndianShort(1)
            output.writeLittleEndianShort(CHANNEL_COUNT)
            output.writeLittleEndianInt(SAMPLE_RATE)
            output.writeLittleEndianInt(SAMPLE_RATE * BYTES_PER_FRAME)
            output.writeLittleEndianShort(BYTES_PER_FRAME)
            output.writeLittleEndianShort(16)
            output.writeBytes("data")
            output.writeLittleEndianInt(dataBytes)
            repeat(frameCount) { frame ->
                val value = sample(frame)
                output.writeLittleEndianShort(value)
                output.writeLittleEndianShort(value)
            }
        }
    }

    private fun vocalSample(frame: Int): Int = 1_000 + frame % 997

    private fun instrumentalSample(frame: Int): Int = 2_000 - frame % 499

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

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val CHANNEL_COUNT = 2
        const val BYTES_PER_FRAME = CHANNEL_COUNT * Short.SIZE_BYTES
        const val FRAME_COUNT = SAMPLE_RATE * 12
        const val READ_FRAMES = 64
        const val SEEK_COUNT = 100
        const val MILLIS_PER_SECOND = 1_000
        const val MAX_SEEK_POSITION_MS = 11_000
        const val READY_TIMEOUT_MS = 5_000L
    }
}
