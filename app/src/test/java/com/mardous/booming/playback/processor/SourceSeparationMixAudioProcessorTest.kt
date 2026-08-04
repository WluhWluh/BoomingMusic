package com.mardous.booming.playback.processor

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.mardous.booming.separation.audio.Pcm16StereoFlacEncoder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SourceSeparationMixAudioProcessorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun wavStemsPlayThroughBoundedEngineWithExistingBlendLaw() {
        val frames = 16_384
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
                vocalsFile = vocals,
                instrumentalFile = instrumental,
                positionMs = 0L,
                initialBlend = 0.5f,
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
                vocalsFile = vocalsFlac,
                instrumentalFile = instrumentalFlac,
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
            repeat(8) { assertEquals(3_000, output.short.toInt()) }
        } finally {
            processor.disable()
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
                vocalsFile = vocals,
                instrumentalFile = instrumental,
                positionMs = 0L,
                inputMode = SourceSeparationMixAudioProcessor.InputMode.OriginalSource,
                stemSampleRate = 44_100,
                stemChannelCount = 2,
                mixedOutputReadyPrerollMs = 0L,
            )
            await { processor.isDataPlaneReady() }
            assertEquals(true, processor.hotSwapToPcmInputs(
                vocalsFile = replacementVocals,
                instrumentalFile = replacementInstrumental,
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

    private fun writePcm(name: String, frames: Int, sample: Int): File {
        val file = temporaryFolder.newFile(name)
        RandomAccessFile(file, "rw").use { output ->
            repeat(frames * 2) { output.writeLittleEndianShort(sample) }
        }
        return file
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
        const val BYTES_PER_FRAME = 4
    }
}
