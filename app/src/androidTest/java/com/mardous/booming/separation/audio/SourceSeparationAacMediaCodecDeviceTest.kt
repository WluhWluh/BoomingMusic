package com.mardous.booming.separation.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.mardous.booming.playback.SourceSeparationAacPlaybackProfile
import com.mardous.booming.playback.SourceSeparationAacStemSourceFactory
import com.mardous.booming.playback.SourceSeparationStemPlaybackEngine
import com.mardous.booming.playback.processor.SourceSeparationMixAudioProcessor
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * Short device-only gate for the optional AAC cache profile. It deliberately
 * uses synthetic PCM so codec/timeline failures are separable from model quality.
 */
@RunWith(AndroidJUnit4::class)
class SourceSeparationAacMediaCodecDeviceTest {

    @Test
    fun encodeDecodeAndRepeatedSixStemSeek() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "aac-device-gate").apply {
            deleteRecursively()
            mkdirs()
        }
        val sampleRate = 44_100
        val frameCount = sampleRate * 3 + 317
        val wav = File(root, "synthetic.wav")
        WavFileWriter(wav, sampleRate, 2).use { writer ->
            writer.writePcm16(syntheticPcm(frameCount))
        }
        val m4a = File(root, "synthetic.m4a")
        val encodeStartNs = System.nanoTime()
        val encoded = Pcm16StereoAacEncoder.encodeWavToM4a(
            wavFile = wav,
            m4aFile = m4a,
            expectedSampleRate = sampleRate,
            expectedFrameCount = frameCount,
        )
        val encodeMs = (System.nanoTime() - encodeStartNs) / 1_000_000L

        val track = inspectTrack(m4a)
        assertEquals(MediaFormat.MIMETYPE_AUDIO_AAC, track.mime)
        assertEquals(sampleRate, track.sampleRate)
        assertEquals(2, track.channels)
        assertTrue(
            kotlin.math.abs(encoded.decodedFrameCount - frameCount.toLong()) <= 8_192L,
        )

        val trace = mutableListOf<String>()
        val profile = SourceSeparationAacPlaybackProfile(
            encoderDelayFrames = encoded.encoderDelayFrames,
            encoderPaddingFrames = encoded.encoderPaddingFrames,
            seekQuantumFrames = 1_024,
            maxAnchorOffsetFrames = 4_096,
            timestampOffsetFrames = encoded.timestampOffsetFrames,
        )
        val factory = SourceSeparationAacStemSourceFactory(
            file = m4a,
            stemId = "synthetic",
            expectedSampleRate = sampleRate,
            expectedChannelCount = 2,
            expectedFrameCount = frameCount.toLong(),
            profile = profile,
            traceSink = trace::add,
        )
        val positions = longArrayOf(
            0L,
            sampleRate.toLong(),
            frameCount / 2L,
            (frameCount - 2_048).toLong().coerceAtLeast(0L),
        )
        val warmSeekSamplesMs = mutableListOf<Long>()
        val coldSeekSamplesMs = mutableListOf<Long>()
        val destination = ByteArray(2_048 * 2 * 2)
        val warmSource = factory.open()
        try {
            repeat(100) { iteration ->
                val position = positions[iteration % positions.size]
                val started = System.nanoTime()
                warmSource.seekToFrame(position)
                val read = warmSource.readFrames(
                    startFrame = position,
                    destination = destination,
                    destinationOffsetBytes = 0,
                    frameCount = min(2_048L, frameCount - position).toInt(),
                )
                warmSeekSamplesMs += (System.nanoTime() - started) / 1_000_000L
                assertTrue(read > 0)
            }
        } finally {
            warmSource.close()
        }
        repeat(20) { iteration ->
            val source = factory.open()
            try {
                val position = positions[iteration % positions.size]
                val started = System.nanoTime()
                source.seekToFrame(position)
                val read = source.readFrames(
                    startFrame = position,
                    destination = destination,
                    destinationOffsetBytes = 0,
                    frameCount = min(2_048L, frameCount - position).toInt(),
                )
                coldSeekSamplesMs += (System.nanoTime() - started) / 1_000_000L
                assertTrue(read > 0)
            } finally {
                source.close()
            }
        }

        val sixStemSamplesMs = mutableListOf<Long>()
        val sixSources = List(6) { factory.open() }
        try {
            repeat(20) { iteration ->
                val position = positions[iteration % positions.size]
                val started = System.nanoTime()
                sixSources.forEach { source ->
                    source.seekToFrame(position)
                    assertTrue(
                        source.readFrames(
                            startFrame = position,
                            destination = destination,
                            destinationOffsetBytes = 0,
                            frameCount = min(2_048L, frameCount - position).toInt(),
                        ) > 0,
                    )
                }
                sixStemSamplesMs += (System.nanoTime() - started) / 1_000_000L
            }
        } finally {
            sixSources.forEach { it.close() }
        }

        val engineTiming = runEngineFastSeekGate(
            m4a = m4a,
            sampleRate = sampleRate,
            frameCount = frameCount.toLong(),
            encoded = encoded,
        )
        val processorTiming = runProcessorFastResumeGate(
            m4a = m4a,
            sampleRate = sampleRate,
            frameCount = frameCount.toLong(),
            encoded = encoded,
        )
        val decoderParity = compareSeedBurstParity(
            m4a = m4a,
            sampleRate = sampleRate,
            frameCount = frameCount.toLong(),
            encoded = encoded,
        )
        assertTrue("AAC seek trace did not record extractor timing.", trace.any {
            it.contains("extractorSeekUs=")
        })
        assertTrue("AAC seek trace did not record codec flush timing.", trace.any {
            it.contains("codecFlushUs=")
        })
        assertTrue("AAC seek trace did not record first-input timing.", trace.any {
            it.contains("firstInputPtsUs=")
        })
        assertTrue("AAC seek trace did not record first-output timing.", trace.any {
            it.contains("queuedInputs=")
        })

        val report = JSONObject()
            .put("status", "passed")
            .put("device", JSONObject()
                .put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL)
                .put("fingerprint", Build.FINGERPRINT)
                .put("sdk", Build.VERSION.SDK_INT)
                .put("supportedAbis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            )
            .put("codec", JSONObject()
                .put("encoder", encoded.codecName)
                .put("decoder", track.codecName)
                .put("mime", track.mime)
                .put("sampleRate", sampleRate)
                .put("channels", 2)
                .put("bitRate", encoded.outputBitRate ?: encoded.bitRate)
                .put("profile", encoded.outputProfile ?: JSONObject.NULL)
                .put("encoderDelayFrames", encoded.encoderDelayFrames ?: JSONObject.NULL)
                .put("encoderPaddingFrames", encoded.encoderPaddingFrames ?: JSONObject.NULL)
                .put("timestampOffsetFrames", encoded.timestampOffsetFrames)
                .put("encodedSamples", encoded.encodedSampleCount)
                .put("decodedFrames", encoded.decodedFrameCount)
            )
            .put("timing", JSONObject()
                .put("encodeMs", encodeMs)
                .put("warmSeekP50Ms", percentile(warmSeekSamplesMs, 0.50))
                .put("warmSeekP95Ms", percentile(warmSeekSamplesMs, 0.95))
                .put("warmSeekP99Ms", percentile(warmSeekSamplesMs, 0.99))
                .put("coldSeekP50Ms", percentile(coldSeekSamplesMs, 0.50))
                .put("coldSeekP95Ms", percentile(coldSeekSamplesMs, 0.95))
                .put("sixStemSeekP50Ms", percentile(sixStemSamplesMs, 0.50))
                .put("sixStemSeekP95Ms", percentile(sixStemSamplesMs, 0.95))
                .put("sixStemSeekP99Ms", percentile(sixStemSamplesMs, 0.99))
                .put("warmSeekSamplesMs", JSONArray(warmSeekSamplesMs))
                .put("coldSeekSamplesMs", JSONArray(coldSeekSamplesMs))
                .put("sixStemSeekSamplesMs", JSONArray(sixStemSamplesMs))
            )
            .put("engineFastSeek", engineTiming)
            .put("processorFastResume", processorTiming)
            .put("decoderParity", decoderParity)
            .put("decoderTuning", JSONObject()
                .put("firstOutputInputSeedBurst", 4)
                .put("steadyOutputPollTimeoutUs", 10_000)
                .put("seedBurstBoundedPerDecoderReset", true)
            )
            .put("trace", JSONArray(trace))
        File(root, "aac-device-report.json").writeText(report.toString(2))
        println(report.toString(2))
    }

    private fun compareSeedBurstParity(
        m4a: File,
        sampleRate: Int,
        frameCount: Long,
        encoded: Pcm16StereoAacEncodeResult,
    ): JSONObject {
        val baselineProfile = SourceSeparationAacPlaybackProfile(
            encoderDelayFrames = encoded.encoderDelayFrames,
            encoderPaddingFrames = encoded.encoderPaddingFrames,
            seekQuantumFrames = 1_024,
            maxAnchorOffsetFrames = 4_096,
            timestampOffsetFrames = encoded.timestampOffsetFrames,
            firstOutputInputSeedBurst = 1,
        )
        val optimizedProfile = baselineProfile.copy(firstOutputInputSeedBurst = 4)
        fun factory(profile: SourceSeparationAacPlaybackProfile) =
            SourceSeparationAacStemSourceFactory(
                file = m4a,
                stemId = "parity",
                expectedSampleRate = sampleRate,
                expectedChannelCount = 2,
                expectedFrameCount = frameCount,
                profile = profile,
            )
        val baseline = factory(baselineProfile).open()
        val optimized = factory(optimizedProfile).open()
        val positions = longArrayOf(
            0L,
            sampleRate.toLong(),
            frameCount / 2L,
            (frameCount - 2_048L).coerceAtLeast(0L),
        )
        val baselineBytes = ByteArray(2_048 * 2 * 2)
        val optimizedBytes = ByteArray(baselineBytes.size)
        var comparedBlocks = 0
        try {
            positions.forEach { position ->
                val count = min(2_048L, frameCount - position).toInt()
                if (count <= 0) return@forEach
                baseline.seekToFrame(position)
                optimized.seekToFrame(position)
                assertEquals(
                    count,
                    baseline.readFrames(position, baselineBytes, 0, count),
                )
                assertEquals(
                    count,
                    optimized.readFrames(position, optimizedBytes, 0, count),
                )
                assertArrayEquals(baselineBytes, optimizedBytes)
                comparedBlocks += 1
            }
        } finally {
            baseline.close()
            optimized.close()
        }
        return JSONObject()
            .put("baselineSeedBurst", 1)
            .put("optimizedSeedBurst", 4)
            .put("comparedBlocks", comparedBlocks)
            .put("pcmBytesEqual", true)
    }

    /**
     * Measures both the established 4,096-frame gate and smaller AAC-only
     * first-block candidates. The latter intentionally trades timeline
     * granularity for the earliest complete mixed PCM block.
     */
    private fun runEngineFastSeekGate(
        m4a: File,
        sampleRate: Int,
        frameCount: Long,
        encoded: Pcm16StereoAacEncodeResult,
    ): JSONObject {
        val positions = longArrayOf(
            0L,
            sampleRate.toLong(),
            frameCount / 2L,
            (frameCount - 4_096L).coerceAtLeast(0L),
        )
        val result = JSONObject()
        listOf(1, 2, 3, 6).forEach { parallelism ->
            result.put(
                "workers$parallelism",
                measureEngineFirstBlock(
                    m4a = m4a,
                    sampleRate = sampleRate,
                    frameCount = frameCount,
                    encoded = encoded,
                    positions = positions,
                    parallelism = parallelism,
                    blockFrames = 4_096,
                    measuredIterations = 20,
                ),
            )
        }
        result.put(
            "firstBlockFrameMatrix",
            JSONObject().apply {
                listOf(1_024, 2_048, 4_096).forEach { blockFrames ->
                    put(
                        "blockFrames$blockFrames",
                        measureEngineFirstBlock(
                            m4a = m4a,
                            sampleRate = sampleRate,
                            frameCount = frameCount,
                            encoded = encoded,
                            positions = positions,
                            parallelism = 6,
                            blockFrames = blockFrames,
                            measuredIterations = 12,
                        ),
                    )
                }
            },
        )
        return result
    }

    private fun measureEngineFirstBlock(
        m4a: File,
        sampleRate: Int,
        frameCount: Long,
        encoded: Pcm16StereoAacEncodeResult,
        positions: LongArray,
        parallelism: Int,
        blockFrames: Int,
        measuredIterations: Int,
    ): JSONObject {
        val profile = SourceSeparationAacPlaybackProfile(
            encoderDelayFrames = encoded.encoderDelayFrames,
            encoderPaddingFrames = encoded.encoderPaddingFrames,
            seekQuantumFrames = 1_024,
            maxAnchorOffsetFrames = 4_096,
            timestampOffsetFrames = encoded.timestampOffsetFrames,
            fastSeekParallelism = parallelism,
        )
        val factories = (0 until 6).map { stemIndex ->
            SourceSeparationAacStemSourceFactory(
                file = m4a,
                stemId = "engine-stem-$stemIndex",
                expectedSampleRate = sampleRate,
                expectedChannelCount = 2,
                expectedFrameCount = frameCount,
                profile = profile,
            )
        }
        val engine = SourceSeparationStemPlaybackEngine(
            blockFrames = blockFrames,
            resumeWaterlineBlocks = 1,
            seekResumeWaterlineBlocks = 1,
            targetWaterlineBlocks = 2,
            blockCapacity = 3,
        )
        val samplesMs = mutableListOf<Long>()
        val destination = Array(6) { ByteArray(blockFrames * 2 * 2) }
        try {
            engine.start(
                sessionId = blockFrames.toLong() * 10L + parallelism,
                factories = factories,
                startFrame = 0L,
            )
            awaitEngineReady(engine)
            repeat(5) { iteration ->
                engine.seekTo(positions[iteration % positions.size])
                awaitEngineReady(engine)
                assertEquals(blockFrames, engine.readInto(destination, blockFrames))
            }
            repeat(measuredIterations) { iteration ->
                val started = System.nanoTime()
                engine.seekTo(positions[iteration % positions.size])
                awaitEngineReady(engine)
                samplesMs += (System.nanoTime() - started) / 1_000_000L
                assertEquals(blockFrames, engine.readInto(destination, blockFrames))
            }
        } finally {
            engine.close()
        }
        return JSONObject()
            .put("blockFrames", blockFrames)
            .put("parallelism", parallelism)
            .put("p50Ms", percentile(samplesMs, 0.50))
            .put("p95Ms", percentile(samplesMs, 0.95))
            .put("p99Ms", percentile(samplesMs, 0.99))
            .put("samplesMs", JSONArray(samplesMs))
    }

    private fun awaitEngineReady(engine: SourceSeparationStemPlaybackEngine) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            if (engine.hasResumeWaterline()) return
            Thread.sleep(2L)
        }
        assertTrue("Timed out waiting for AAC engine seek-ready", engine.hasResumeWaterline())
    }

    private fun runProcessorFastResumeGate(
        m4a: File,
        sampleRate: Int,
        frameCount: Long,
        encoded: Pcm16StereoAacEncodeResult,
    ): JSONObject {
        val stemIds = (0 until 6).map { index -> "processor-stem-$index" }
        val profile = SourceSeparationAacPlaybackProfile(
            encoderDelayFrames = encoded.encoderDelayFrames,
            encoderPaddingFrames = encoded.encoderPaddingFrames,
            seekQuantumFrames = 1_024,
            maxAnchorOffsetFrames = 4_096,
            timestampOffsetFrames = encoded.timestampOffsetFrames,
            fastSeekParallelism = 6,
        )
        val profiles = List(stemIds.size) { profile }
        val stems = List(stemIds.size) { m4a }
        val processor = SourceSeparationMixAudioProcessor()
        val notifications = java.util.concurrent.atomic.AtomicInteger()
        processor.mixedOutputStartedSink = { notifications.incrementAndGet() }
        val samplesMs = mutableListOf<Long>()
        val positionsMs = longArrayOf(
            0L,
            1_000L,
            frameCount * 500L / sampleRate,
            (frameCount - 4_096L).coerceAtLeast(0L) * 1_000L / sampleRate,
        )
        try {
            val prepared = processor.prepareInputs(
                stemFiles = stems,
                stemIds = stemIds,
                stemSampleRate = sampleRate,
                stemChannelCount = 2,
                stemFrameCount = frameCount,
                aacProfiles = profiles,
            )
            processor.configure(AudioProcessor.AudioFormat(sampleRate, 2, C.ENCODING_PCM_16BIT))
            processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
            processor.enable(
                stemFiles = stems,
                stemIds = stemIds,
                initialGains = List(stemIds.size) { 1f },
                positionMs = 0L,
                inputMode = SourceSeparationMixAudioProcessor.InputMode.OriginalSource,
                stemSampleRate = sampleRate,
                stemChannelCount = 2,
                stemFrameCount = frameCount,
                aacProfiles = profiles,
                // The prepared all-AAC profile must override this to zero.
                mixedOutputReadyPrerollMs = 400L,
                preparedInputs = prepared,
            )
            awaitProcessorReady(processor)
            repeat(5) { iteration ->
                consumeProcessorSeek(
                    processor = processor,
                    positionMs = positionsMs[iteration % positionsMs.size],
                    notifications = notifications,
                )
            }
            repeat(20) { iteration ->
                val started = System.nanoTime()
                consumeProcessorSeek(
                    processor = processor,
                    positionMs = positionsMs[iteration % positionsMs.size],
                    notifications = notifications,
                )
                samplesMs += (System.nanoTime() - started) / 1_000_000L
            }
        } finally {
            processor.disable()
        }
        return JSONObject()
            .put("p50Ms", percentile(samplesMs, 0.50))
            .put("p95Ms", percentile(samplesMs, 0.95))
            .put("p99Ms", percentile(samplesMs, 0.99))
            .put("mixedOutputNotifications", notifications.get())
            .put("samplesMs", JSONArray(samplesMs))
    }

    private fun consumeProcessorSeek(
        processor: SourceSeparationMixAudioProcessor,
        positionMs: Long,
        notifications: java.util.concurrent.atomic.AtomicInteger,
    ) {
        val notificationsBefore = notifications.get()
        processor.seekTo(positionMs)
        awaitProcessorReady(processor)
        val input = ByteBuffer.allocateDirect(1_024 * 2 * 2).order(ByteOrder.LITTLE_ENDIAN)
        input.limit(input.capacity())
        processor.queueInput(input)
        val output = processor.output
        assertTrue("AAC processor emitted no mixed PCM after seek", output.hasRemaining())
        output.position(output.limit())
        assertEquals(notificationsBefore + 1, notifications.get())
    }

    private fun awaitProcessorReady(processor: SourceSeparationMixAudioProcessor) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            if (processor.isDataPlaneReady()) return
            Thread.sleep(2L)
        }
        assertTrue("Timed out waiting for AAC processor seek-ready", processor.isDataPlaneReady())
    }

    private fun syntheticPcm(frameCount: Int): ByteArray {
        val output = ByteBuffer.allocate(frameCount * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frameCount) { frame ->
            val impulse = if (frame == 0 || frame == 44_100 || frame == 88_200) {
                Short.MAX_VALUE
            } else {
                ((frame % 2_048) - 1_024).toShort()
            }
            output.putShort(impulse)
            output.putShort((-impulse.toInt()).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
        }
        return output.array()
    }

    private fun inspectTrack(file: File): TrackInfo {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val index = (0 until extractor.trackCount).first { track ->
                extractor.getTrackFormat(track).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            val format = extractor.getTrackFormat(index)
            val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
            val decoder = MediaCodec.createDecoderByType(mime)
            val name = decoder.name
            decoder.release()
            return TrackInfo(
                mime = mime,
                sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                codecName = name,
            )
        } finally {
            extractor.release()
        }
    }

    private fun percentile(values: List<Long>, fraction: Double): Long {
        val sorted = values.sorted()
        val index = ((sorted.size - 1) * fraction).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    private data class TrackInfo(
        val mime: String,
        val sampleRate: Int,
        val channels: Int,
        val codecName: String,
    )
}
