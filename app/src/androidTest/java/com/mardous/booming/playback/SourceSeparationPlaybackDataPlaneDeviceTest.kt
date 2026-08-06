package com.mardous.booming.playback

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
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
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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

    @Test
    fun fourStemIndexedFlacSmokeRecordsBoundedResourceMetrics() {
        runMultistemIndexedFlacSmoke(stemCount = 4)
    }

    @Test
    fun sixStemIndexedFlacSmokeRecordsBoundedResourceMetrics() {
        runMultistemIndexedFlacSmoke(stemCount = 6)
    }

    @Test
    fun multistemIndexedFlacSoakRecordsSustainedMetrics() {
        val arguments = InstrumentationRegistry.getArguments()
        val durationMinutes = arguments.getString(ARG_SOAK_MINUTES)?.toIntOrNull() ?: 0
        assumeTrue(
            "The sustained playback soak requires an explicit positive duration.",
            durationMinutes > 0,
        )
        require(durationMinutes in 1..MAX_SOAK_MINUTES) {
            "Playback soak duration must be between 1 and $MAX_SOAK_MINUTES minutes."
        }
        val stemCount = arguments.getString(ARG_SOAK_STEM_COUNT)?.toIntOrNull()
            ?: DEFAULT_SOAK_STEM_COUNT
        require(stemCount in SOAK_STEM_COUNTS) {
            "Playback soak stem count must be one of $SOAK_STEM_COUNTS."
        }
        val runId = arguments.getString(ARG_SOAK_RUN_ID)
            ?.takeIf(String::isNotBlank)
            ?: "${Build.MODEL}-${System.currentTimeMillis()}"
        require(SAFE_RUN_ID.matches(runId)) { "Playback soak run ID is unsafe." }

        runMultistemIndexedFlacSoak(
            stemCount = stemCount,
            durationMs = durationMinutes * 60_000L,
            runId = runId,
        )
    }

    private fun runMultistemIndexedFlacSmoke(stemCount: Int) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "multistem-$stemCount-flac-device-test").apply {
            deleteRecursively()
            check(mkdirs())
        }
        val stems = (0 until stemCount).map { index ->
            val wav = File(root, "stem-$index.wav")
            val flac = File(root, "stem-$index.flac")
            writeWav(wav, MULTISTEM_FRAME_COUNT) { frame ->
                multistemSample(index, frame)
            }
            Pcm16StereoFlacEncoder.encodeWavToFlac(
                wavFile = wav,
                flacFile = flac,
                expectedSampleRate = SAMPLE_RATE,
                expectedFrameCount = MULTISTEM_FRAME_COUNT,
            )
            flac
        }
        val stemIds = MULTISTEM_IDS.take(stemCount)
        val cacheBytes = stems.sumOf { flac ->
            flac.length() + Pcm16StereoFlacEncoder.frameIndexFileFor(flac).length()
        }
        val processor = SourceSeparationMixAudioProcessor()
        val baselinePssKb = Debug.getPss()
        var peakPssKb = baselinePssKb
        try {
            val prepared = processor.prepareInputs(
                stemFiles = stems,
                stemIds = stemIds,
                stemSampleRate = SAMPLE_RATE,
                stemChannelCount = CHANNEL_COUNT,
            )
            processor.configure(
                AudioProcessor.AudioFormat(SAMPLE_RATE, CHANNEL_COUNT, C.ENCODING_PCM_16BIT),
            )
            processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
            processor.enable(
                stemFiles = stems,
                stemIds = stemIds,
                positionMs = 0L,
                inputMode = SourceSeparationMixAudioProcessor.InputMode.OriginalSource,
                stemSampleRate = SAMPLE_RATE,
                stemChannelCount = CHANNEL_COUNT,
                mixedOutputReadyPrerollMs = 0L,
                preparedInputs = prepared,
            )
            awaitReady(processor)
            peakPssKb = maxOf(peakPssKb, Debug.getPss())

            val random = Random(0x4D53534CL + stemCount)
            repeat(MULTISTEM_SEEK_COUNT) {
                val positionMs = random.nextInt(MULTISTEM_MAX_SEEK_POSITION_MS + 1).toLong()
                processor.seekTo(positionMs)
                awaitReady(processor)
                val expectedStartFrame = (
                    positionMs * SAMPLE_RATE / MILLIS_PER_SECOND.toFloat()
                ).roundToLong().toInt()
                val input = ByteBuffer.allocateDirect(MULTISTEM_READ_FRAMES * BYTES_PER_FRAME)
                    .order(ByteOrder.LITTLE_ENDIAN)
                repeat(MULTISTEM_READ_FRAMES * CHANNEL_COUNT) { input.putShort(9_000) }
                input.flip()
                processor.queueInput(input)
                peakPssKb = maxOf(peakPssKb, Debug.getPss())
                val output = processor.output.order(ByteOrder.LITTLE_ENDIAN)
                repeat(MULTISTEM_READ_FRAMES) { frameOffset ->
                    val expected = (0 until stemCount).sumOf { stemIndex ->
                        multistemSample(stemIndex, expectedStartFrame + frameOffset)
                    }
                    assertEquals(expected, output.short.toInt())
                    assertEquals(expected, output.short.toInt())
                }
            }

            val metrics = requireNotNull(processor.dataPlaneMetrics())
            assertEquals(stemCount, metrics.activeStemCount)
            assertTrue(metrics.bufferPoolBytes > 0L)
            assertTrue(metrics.openFileDescriptors >= stemCount)
            assertEquals(0L, metrics.underruns)
            assertEquals(MULTISTEM_SEEK_COUNT.toLong(), metrics.seekRequests)
            assertTrue(metrics.audioThreadTimeNs.count > 0)
            Log.i(
                METRICS_TAG,
                "model=${android.os.Build.MODEL} stems=${metrics.activeStemCount} " +
                        "poolBytes=${metrics.bufferPoolBytes} " +
                        "fds=${metrics.openFileDescriptors} " +
                        "decodeP95Ns=${metrics.decodeBlockLatencyNs.p95} " +
                        "audioP95Ns=${metrics.audioThreadTimeNs.p95} " +
                        "cacheBytes=$cacheBytes baselinePssKb=$baselinePssKb " +
                        "peakPssKb=$peakPssKb " +
                        "pssDeltaKb=${(peakPssKb - baselinePssKb).coerceAtLeast(0)} " +
                        "underruns=${metrics.underruns} seeks=${metrics.seekRequests}",
            )
        } finally {
            processor.disable()
            root.deleteRecursively()
        }
    }

    private fun runMultistemIndexedFlacSoak(
        stemCount: Int,
        durationMs: Long,
        runId: String,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "multistem-$stemCount-flac-soak").apply {
            deleteRecursively()
            check(mkdirs())
        }
        val reportDirectory = File(context.filesDir, SOAK_REPORT_DIRECTORY).apply {
            check(isDirectory || mkdirs())
        }
        val reportFile = File(reportDirectory, "$runId.json")
        val processor = SourceSeparationMixAudioProcessor()
        val powerManager = context.getSystemService(PowerManager::class.java)
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "BoomingSS:multistem-playback-soak",
        )
        try {
            wakeLock.acquire(durationMs + SOAK_WAKE_LOCK_MARGIN_MS)
            val setupStartedAtMs = SystemClock.elapsedRealtime()
            val stems = (0 until stemCount).map { index ->
                val wav = File(root, "stem-$index.wav")
                val flac = File(root, "stem-$index.flac")
                writeWav(wav, SOAK_FRAME_COUNT) { frame ->
                    multistemSample(index, frame)
                }
                Pcm16StereoFlacEncoder.encodeWavToFlac(
                    wavFile = wav,
                    flacFile = flac,
                    expectedSampleRate = SAMPLE_RATE,
                    expectedFrameCount = SOAK_FRAME_COUNT,
                )
                check(wav.delete()) { "Could not remove the soak WAV staging file." }
                flac
            }
            val setupElapsedMs = SystemClock.elapsedRealtime() - setupStartedAtMs
            val cacheBytesBefore = root.directorySize()
            Runtime.getRuntime().gc()
            SystemClock.sleep(SOAK_BASELINE_SETTLE_MS)
            val baselinePssKb = Debug.getPss()
            var peakPssKb = baselinePssKb
            val thermalSamples = JSONArray()
            sampleThermal(context, powerManager, 0L)?.let(thermalSamples::put)

            val stemIds = MULTISTEM_IDS.take(stemCount)
            val prepared = processor.prepareInputs(
                stemFiles = stems,
                stemIds = stemIds,
                stemSampleRate = SAMPLE_RATE,
                stemChannelCount = CHANNEL_COUNT,
            )
            processor.configure(
                AudioProcessor.AudioFormat(SAMPLE_RATE, CHANNEL_COUNT, C.ENCODING_PCM_16BIT),
            )
            processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
            processor.enable(
                stemFiles = stems,
                stemIds = stemIds,
                positionMs = 0L,
                inputMode = SourceSeparationMixAudioProcessor.InputMode.OriginalSource,
                stemSampleRate = SAMPLE_RATE,
                stemChannelCount = CHANNEL_COUNT,
                mixedOutputReadyPrerollMs = 0L,
                preparedInputs = prepared,
            )
            awaitReady(processor)

            val startedAtMs = SystemClock.elapsedRealtime()
            val deadlineMs = startedAtMs + durationMs
            val seekIntervalMs = (durationMs / SOAK_TARGET_SEEK_COUNT)
                .coerceAtLeast(SOAK_MIN_SEEK_INTERVAL_MS)
            var nextSeekAtMs = startedAtMs + seekIntervalMs
            var nextResourceSampleAtMs = startedAtMs
            var nextThermalSampleAtMs = startedAtMs
            var nextCallbackAtNs = System.nanoTime()
            val callbackIntervalNs = SOAK_READ_FRAMES * NANOS_PER_SECOND / SAMPLE_RATE
            val maxSeekFrame = (
                SOAK_FRAME_COUNT.toLong() -
                        seekIntervalMs * SAMPLE_RATE / MILLIS_PER_SECOND -
                        SOAK_READ_FRAMES
                ).coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val random = Random(0x534F414BL + stemCount)
            val seekLatenciesMs = mutableListOf<Long>()
            val input = ByteBuffer.allocateDirect(SOAK_READ_FRAMES * BYTES_PER_FRAME)
                .order(ByteOrder.LITTLE_ENDIAN)
            var logicalFrame = 0L
            var callbackCount = 0L
            var lateCallbackCount = 0L
            var outputFrames = 0L

            while (SystemClock.elapsedRealtime() < deadlineMs) {
                val nowMs = SystemClock.elapsedRealtime()
                if (nowMs >= nextSeekAtMs) {
                    val targetFrame = if (maxSeekFrame == 0) 0 else {
                        random.nextInt(maxSeekFrame + 1)
                    }
                    val targetPositionMs =
                        targetFrame.toLong() * MILLIS_PER_SECOND / SAMPLE_RATE
                    val seekStartedAtMs = SystemClock.elapsedRealtime()
                    processor.seekTo(targetPositionMs)
                    awaitReady(processor)
                    seekLatenciesMs += SystemClock.elapsedRealtime() - seekStartedAtMs
                    logicalFrame = (
                        targetPositionMs * SAMPLE_RATE / MILLIS_PER_SECOND.toFloat()
                    ).roundToLong()
                    nextSeekAtMs += seekIntervalMs
                    nextCallbackAtNs = System.nanoTime()
                }
                check(logicalFrame + SOAK_READ_FRAMES <= SOAK_FRAME_COUNT) {
                    "Playback soak reached EOF before its next scheduled seek."
                }

                input.clear()
                input.position(SOAK_READ_FRAMES * BYTES_PER_FRAME)
                input.flip()
                processor.queueInput(input)
                val output = processor.output.order(ByteOrder.LITTLE_ENDIAN)
                assertEquals(SOAK_READ_FRAMES * BYTES_PER_FRAME, output.remaining())
                val expected = (0 until stemCount).sumOf { stemIndex ->
                    multistemSample(stemIndex, logicalFrame.toInt())
                }
                assertEquals(expected, output.short.toInt())
                assertEquals(expected, output.short.toInt())
                output.position(output.limit())
                logicalFrame += SOAK_READ_FRAMES
                outputFrames += SOAK_READ_FRAMES
                callbackCount += 1L

                val sampledAtMs = SystemClock.elapsedRealtime()
                if (sampledAtMs >= nextResourceSampleAtMs) {
                    peakPssKb = maxOf(peakPssKb, Debug.getPss())
                    nextResourceSampleAtMs += SOAK_RESOURCE_SAMPLE_INTERVAL_MS
                }
                if (sampledAtMs >= nextThermalSampleAtMs) {
                    sampleThermal(
                        context,
                        powerManager,
                        sampledAtMs - startedAtMs,
                    )?.let(thermalSamples::put)
                    nextThermalSampleAtMs += SOAK_THERMAL_SAMPLE_INTERVAL_MS
                }

                nextCallbackAtNs += callbackIntervalNs
                val sleepNs = nextCallbackAtNs - System.nanoTime()
                if (sleepNs > 0L) {
                    val sleepMs = sleepNs / NANOS_PER_MILLISECOND
                    val sleepNanos = (sleepNs % NANOS_PER_MILLISECOND).toInt()
                    Thread.sleep(sleepMs, sleepNanos)
                } else {
                    lateCallbackCount += 1L
                    nextCallbackAtNs = System.nanoTime()
                }
            }

            peakPssKb = maxOf(peakPssKb, Debug.getPss())
            sampleThermal(context, powerManager, durationMs)?.let(thermalSamples::put)
            val metrics = requireNotNull(processor.dataPlaneMetrics())
            val cacheBytesAfter = root.directorySize()
            assertEquals(0L, metrics.underruns)
            assertEquals(seekLatenciesMs.size.toLong(), metrics.seekRequests)
            assertEquals(stemCount, metrics.activeStemCount)
            assertEquals(cacheBytesBefore, cacheBytesAfter)
            val elapsedMs = SystemClock.elapsedRealtime() - startedAtMs
            val seekLatency = latencyJson(seekLatenciesMs)
            val report = JSONObject()
                .put("runId", runId)
                .put("device", Build.MODEL)
                .put("sdk", Build.VERSION.SDK_INT)
                .put("stemCount", stemCount)
                .put("setupElapsedMs", setupElapsedMs)
                .put("requestedDurationMs", durationMs)
                .put("elapsedMs", elapsedMs)
                .put("callbackCount", callbackCount)
                .put("lateCallbackCount", lateCallbackCount)
                .put("outputFrames", outputFrames)
                .put("seekCount", seekLatenciesMs.size)
                .put("seekLatencyMs", seekLatency)
                .put("decodeLatencyNs", latencyJson(metrics.decodeBlockLatencyNs))
                .put("audioCallbackLatencyNs", latencyJson(metrics.audioThreadTimeNs))
                .put("underruns", metrics.underruns)
                .put("lowWaterEvents", metrics.lowWaterEvents)
                .put("poolBytes", metrics.bufferPoolBytes)
                .put("openFileDescriptors", metrics.openFileDescriptors)
                .put("baselinePssKb", baselinePssKb)
                .put("peakPssKb", peakPssKb)
                .put("pssDeltaKb", (peakPssKb - baselinePssKb).coerceAtLeast(0))
                .put("cacheBytesBefore", cacheBytesBefore)
                .put("cacheBytesAfter", cacheBytesAfter)
                .put("thermal", thermalSamples)
            reportFile.writeText(report.toString(2))
            Log.i(
                METRICS_TAG,
                "soak=$runId stems=$stemCount elapsedMs=$elapsedMs " +
                        "seeks=${seekLatenciesMs.size} seekP95Ms=${seekLatency.getLong("p95")} " +
                        "decodeP99Ns=${metrics.decodeBlockLatencyNs.p99} " +
                        "audioP99Ns=${metrics.audioThreadTimeNs.p99} " +
                        "pssDeltaKb=${(peakPssKb - baselinePssKb).coerceAtLeast(0)} " +
                        "underruns=${metrics.underruns}",
            )
        } finally {
            processor.disable()
            if (wakeLock.isHeld) wakeLock.release()
            root.deleteRecursively()
        }
    }

    private fun sampleThermal(
        context: android.content.Context,
        powerManager: PowerManager,
        elapsedMs: Long,
    ): JSONObject? {
        val battery = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        )
        val temperatureDeciC = battery?.getIntExtra(
            BatteryManager.EXTRA_TEMPERATURE,
            Int.MIN_VALUE,
        )
            ?.takeUnless { it == Int.MIN_VALUE }
        val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            powerManager.currentThermalStatus
        } else {
            null
        }
        if (temperatureDeciC == null && thermalStatus == null) return null
        return JSONObject()
            .put("elapsedMs", elapsedMs)
            .put("thermalStatus", thermalStatus ?: JSONObject.NULL)
            .put("batteryTemperatureDeciC", temperatureDeciC ?: JSONObject.NULL)
    }

    private fun latencyJson(values: List<Long>): JSONObject {
        val sorted = values.sorted()
        fun percentile(percent: Int): Long {
            if (sorted.isEmpty()) return 0L
            val index = ((sorted.size - 1) * percent / 100).coerceIn(0, sorted.lastIndex)
            return sorted[index]
        }
        return JSONObject()
            .put("count", sorted.size)
            .put("p50", percentile(50))
            .put("p95", percentile(95))
            .put("p99", percentile(99))
            .put("max", sorted.lastOrNull() ?: 0L)
    }

    private fun latencyJson(
        summary: SourceSeparationPlaybackLatencySummary,
    ): JSONObject = JSONObject()
        .put("count", summary.count)
        .put("p50", summary.p50)
        .put("p95", summary.p95)
        .put("p99", summary.p99)
        .put("max", summary.max)

    private fun File.directorySize(): Long = walkTopDown()
        .filter(File::isFile)
        .sumOf(File::length)

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
            val pcm = ByteBuffer.allocate(WAV_WRITE_BUFFER_FRAMES * BYTES_PER_FRAME)
                .order(ByteOrder.LITTLE_ENDIAN)
            var firstFrame = 0
            while (firstFrame < frameCount) {
                val frames = minOf(WAV_WRITE_BUFFER_FRAMES, frameCount - firstFrame)
                pcm.clear()
                repeat(frames) { frameOffset ->
                    val value = sample(firstFrame + frameOffset).toShort()
                    pcm.putShort(value)
                    pcm.putShort(value)
                }
                output.write(pcm.array(), 0, pcm.position())
                firstFrame += frames
            }
        }
    }

    private fun vocalSample(frame: Int): Int = 1_000 + frame % 997

    private fun instrumentalSample(frame: Int): Int = 2_000 - frame % 499

    private fun multistemSample(stemIndex: Int, frame: Int): Int {
        return (stemIndex + 1) * 200 + frame % (37 + stemIndex * 5)
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

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val CHANNEL_COUNT = 2
        const val BYTES_PER_FRAME = CHANNEL_COUNT * Short.SIZE_BYTES
        const val FRAME_COUNT = SAMPLE_RATE * 12
        const val READ_FRAMES = 64
        const val SEEK_COUNT = 100
        const val MILLIS_PER_SECOND = 1_000
        const val MAX_SEEK_POSITION_MS = 11_000
        const val MULTISTEM_FRAME_COUNT = SAMPLE_RATE * 5
        const val MULTISTEM_READ_FRAMES = 64
        const val MULTISTEM_SEEK_COUNT = 20
        const val MULTISTEM_MAX_SEEK_POSITION_MS = 4_500
        const val READY_TIMEOUT_MS = 5_000L
        const val METRICS_TAG = "BSSMultistemPlayback"
        const val ARG_SOAK_MINUTES = "playbackSoakMinutes"
        const val ARG_SOAK_STEM_COUNT = "playbackSoakStemCount"
        const val ARG_SOAK_RUN_ID = "playbackSoakRunId"
        const val SOAK_REPORT_DIRECTORY = "source-separation-playback-soak"
        const val DEFAULT_SOAK_STEM_COUNT = 8
        const val MAX_SOAK_MINUTES = 120
        const val SOAK_FRAME_COUNT = SAMPLE_RATE * 30
        const val SOAK_READ_FRAMES = 2_048
        const val WAV_WRITE_BUFFER_FRAMES = 16_384
        const val SOAK_TARGET_SEEK_COUNT = 100L
        const val SOAK_MIN_SEEK_INTERVAL_MS = 1_000L
        const val SOAK_RESOURCE_SAMPLE_INTERVAL_MS = 1_000L
        const val SOAK_THERMAL_SAMPLE_INTERVAL_MS = 15_000L
        const val SOAK_BASELINE_SETTLE_MS = 500L
        const val SOAK_WAKE_LOCK_MARGIN_MS = 15 * 60_000L
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val NANOS_PER_MILLISECOND = 1_000_000L
        val SOAK_STEM_COUNTS = setOf(4, 6, 8)
        val SAFE_RUN_ID = Regex("^[A-Za-z0-9._-]{1,120}$")
        val MULTISTEM_IDS = listOf(
            "vocals",
            "drums",
            "bass",
            "other",
            "guitar",
            "piano",
            "stem-6",
            "stem-7",
        )
    }
}
