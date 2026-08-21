package com.mardous.booming.separation.audio

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mardous.booming.playback.SourceSeparationAacPlaybackProfile
import com.mardous.booming.playback.SourceSeparationAacStemSourceFactory
import com.mardous.booming.playback.SourceSeparationStemPlaybackEngine
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/**
 * Long-song AAC playback-only seek matrix. The six M4A files are prepared
 * from one real 210-second six-stem render; no model inference is performed.
 */
@RunWith(AndroidJUnit4::class)
class SourceSeparationAacLongSongSeekDeviceTest {

    @Test
    fun longSongSixStemSeekDistanceMatrix() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, FIXTURE_DIRECTORY)
        val stems = (0 until STEM_COUNT).map { index ->
            root.resolve("stem-%02d.m4a".format(index))
        }
        assumeTrue("Long AAC fixture is not staged.", stems.all(File::isFile))

        val trackInfo = stems.map(::inspectTrack)
        assertEquals(1, trackInfo.map { it.sampleRate }.distinct().size)
        assertEquals(1, trackInfo.map { it.channels }.distinct().size)
        assertEquals(1, trackInfo.map { it.frameCount }.distinct().size)
        val sampleRate = trackInfo.first().sampleRate
        val frameCount = trackInfo.first().frameCount
        val durationMs = frameCount * 1_000L / sampleRate
        assertTrue("Fixture must be a long song.", durationMs >= 180_000L)

        val baseMs = (durationMs / 2L).coerceAtLeast(1_000L)
        val cases = listOf(
            SeekCase("near_future", +5_000L),
            SeekCase("far_future", +60_000L),
            SeekCase("near_past", -5_000L),
            SeekCase("far_past", -60_000L),
        ).map { case ->
            case.copy(targetMs = (baseMs + case.deltaMs)
                .coerceIn(2_000L, (durationMs - 2_000L).coerceAtLeast(2_000L)))
        }

        val warmResults = cases.map { case ->
            measureWarmCase(
                stems = stems,
                sampleRate = sampleRate,
                frameCount = frameCount,
                baseFrame = msToFrame(baseMs, sampleRate),
                case = case,
            )
        }
        val coldResults = cases.map { case ->
            measureColdCase(
                stems = stems,
                sampleRate = sampleRate,
                frameCount = frameCount,
                baseFrame = msToFrame(baseMs, sampleRate),
                case = case,
            )
        }

        val report = JSONObject()
            .put("status", "passed")
            .put("mode", "long-song-six-stem-aac-seek-distance")
            .put("fixtureDirectory", FIXTURE_DIRECTORY)
            .put("device", JSONObject()
                .put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL)
                .put("fingerprint", Build.FINGERPRINT)
                .put("sdk", Build.VERSION.SDK_INT)
                .put("supportedAbis", JSONArray(Build.SUPPORTED_ABIS.toList())))
            .put("codec", JSONObject()
                .put("mime", trackInfo.first().mime)
                .put("codecNames", JSONArray(trackInfo.map { it.codecName }))
                .put("sampleRate", sampleRate)
                .put("channels", trackInfo.first().channels)
                .put("frameCount", frameCount)
                .put("durationMs", durationMs))
            .put("stems", JSONArray(stems.map { stem ->
                JSONObject().put("name", stem.name).put("bytes", stem.length())
                    .put("sha256", stem.sha256())
            }))
            .put("basePositionMs", baseMs)
            .put("cases", JSONArray(cases.map { case ->
                JSONObject().put("id", case.id).put("deltaMs", case.deltaMs)
                    .put("targetMs", case.targetMs)
            }))
            .put("warm", JSONArray(warmResults.map(SeekMeasurement::toJson)))
            .put("cold", JSONArray(coldResults.map(SeekMeasurement::toJson)))

        val reportFile = File(context.filesDir, REPORT_FILE)
        reportFile.parentFile?.mkdirs()
        reportFile.writeText(report.toString(2))
        println(report.toString(2))
    }

    private fun measureWarmCase(
        stems: List<File>,
        sampleRate: Int,
        frameCount: Long,
        baseFrame: Long,
        case: SeekCase,
    ): SeekMeasurement {
        val trace = CopyOnWriteArrayList<TraceSample>()
        val factories = factories(stems, sampleRate, frameCount, trace)
        val engine = newEngine()
        val destination = destination(engine)
        try {
            engine.start(sessionId = 1L, factories = factories, startFrame = baseFrame)
            awaitFirstPcm(engine, destination)
            val samples = ArrayList<SeekSample>()
            repeat(WARM_REPETITIONS) {
                seekAndMeasure(engine, destination, msToFrame(case.targetMs, sampleRate), trace)
                    .also(samples::add)
                seekAndMeasure(engine, destination, baseFrame, trace)
            }
            return SeekMeasurement(case.id, case.deltaMs, "warm", samples)
        } finally {
            engine.close()
        }
    }

    private fun measureColdCase(
        stems: List<File>,
        sampleRate: Int,
        frameCount: Long,
        baseFrame: Long,
        case: SeekCase,
    ): SeekMeasurement {
        val samples = ArrayList<SeekSample>()
        repeat(COLD_REPETITIONS) { iteration ->
            val trace = CopyOnWriteArrayList<TraceSample>()
            val engine = newEngine()
            val destination = destination(engine)
            try {
                engine.start(
                    sessionId = (iteration + 2).toLong(),
                    factories = factories(stems, sampleRate, frameCount, trace),
                    startFrame = baseFrame,
                )
                awaitFirstPcm(engine, destination)
                samples += seekAndMeasure(
                    engine,
                    destination,
                    msToFrame(case.targetMs, sampleRate),
                    trace,
                )
            } finally {
                engine.close()
            }
        }
        return SeekMeasurement(case.id, case.deltaMs, "cold", samples)
    }

    private fun seekAndMeasure(
        engine: SourceSeparationStemPlaybackEngine,
        destination: Array<ByteArray>,
        targetFrame: Long,
        trace: List<TraceSample>,
    ): SeekSample {
        val traceStart = trace.size
        val metricsBefore = engine.metricsSnapshot()
        val startedNs = System.nanoTime()
        engine.seekTo(targetFrame)
        var firstPcmMs: Long? = null
        val deadlineNs = startedNs + SEEK_TIMEOUT_NS
        while (System.nanoTime() < deadlineNs) {
            val read = engine.readInto(destination, engine.blockFrameCapacity)
            if (read > 0) {
                firstPcmMs = (System.nanoTime() - startedNs) / 1_000_000L
                break
            }
            Thread.sleep(POLL_MS)
        }
        assertTrue("AAC seek did not produce PCM within timeout.", firstPcmMs != null)
        while (!engine.hasResumeWaterline() && System.nanoTime() < deadlineNs) {
            Thread.sleep(POLL_MS)
        }
        val readyMs = (System.nanoTime() - startedNs) / 1_000_000L
        assertTrue("AAC seek did not reach its resume waterline.", engine.hasResumeWaterline())
        val anchors = trace.drop(traceStart).mapNotNull(::parseAnchor)
        val metricsAfter = engine.metricsSnapshot()
        return SeekSample(
            targetFrame = targetFrame,
            firstPcmMs = requireNotNull(firstPcmMs),
            readyMs = readyMs,
            anchorFrames = anchors.map { it.anchorFrame },
            offsetsFrames = anchors.map { it.offsetFrames },
            lowWaterEvents = (metricsAfter.lowWaterEvents - metricsBefore.lowWaterEvents)
                .coerceAtLeast(0L),
            underruns = (metricsAfter.underruns - metricsBefore.underruns).coerceAtLeast(0L),
            trace = trace.drop(traceStart).map { it.text },
        )
    }

    private fun awaitFirstPcm(
        engine: SourceSeparationStemPlaybackEngine,
        destination: Array<ByteArray>,
    ) {
        val deadlineNs = System.nanoTime() + SEEK_TIMEOUT_NS
        while (System.nanoTime() < deadlineNs) {
            if (engine.readInto(destination, engine.blockFrameCapacity) > 0) return
            Thread.sleep(POLL_MS)
        }
        error("AAC fixture engine did not produce initial PCM.")
    }

    private fun factories(
        stems: List<File>,
        sampleRate: Int,
        frameCount: Long,
        trace: MutableList<TraceSample>,
    ) = stems.mapIndexed { index, file ->
        SourceSeparationAacStemSourceFactory(
            file = file,
            stemId = "long-stem-$index",
            expectedSampleRate = sampleRate,
            expectedChannelCount = 2,
            expectedFrameCount = frameCount,
            profile = SourceSeparationAacPlaybackProfile(
                seekQuantumFrames = 1_024,
                maxAnchorOffsetFrames = 4_096,
                fastSeekParallelism = 6,
            ),
            traceSink = { text -> trace += TraceSample(System.nanoTime(), text) },
        )
    }

    private fun newEngine() = SourceSeparationStemPlaybackEngine(
        blockFrames = 4_096,
        resumeWaterlineBlocks = 1,
        seekResumeWaterlineBlocks = 1,
        targetWaterlineBlocks = 2,
        blockCapacity = 3,
    )

    private fun destination(engine: SourceSeparationStemPlaybackEngine) =
        Array(STEM_COUNT) { ByteArray(engine.blockFrameCapacity * 2 * 2) }

    private fun parseAnchor(sample: TraceSample): Anchor? {
        val match = ANCHOR_PATTERN.find(sample.text) ?: return null
        return Anchor(
            anchorFrame = match.groupValues[2].toLong(),
            offsetFrames = match.groupValues[3].toLong(),
        )
    }

    private fun inspectTrack(file: File): TrackInfo {
        val extractor = android.media.MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).first { index ->
                extractor.getTrackFormat(index).getString(android.media.MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            }
            val format = extractor.getTrackFormat(track)
            val mime = requireNotNull(format.getString(android.media.MediaFormat.KEY_MIME))
            val codec = android.media.MediaCodec.createDecoderByType(mime)
            val codecName = codec.name
            codec.release()
            val sampleRate = format.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
            val durationUs = format.getLong(android.media.MediaFormat.KEY_DURATION)
            return TrackInfo(mime, codecName, sampleRate, channels,
                durationUs * sampleRate / 1_000_000L)
        } finally {
            extractor.release()
        }
    }

    private fun msToFrame(ms: Long, sampleRate: Int): Long = ms * sampleRate / 1_000L

    private fun File.sha256(): String = inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(256 * 1_024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private data class SeekCase(val id: String, val deltaMs: Long, val targetMs: Long = 0L)
    private data class TrackInfo(
        val mime: String,
        val codecName: String,
        val sampleRate: Int,
        val channels: Int,
        val frameCount: Long,
    )
    private data class TraceSample(val timestampNs: Long, val text: String)
    private data class Anchor(val anchorFrame: Long, val offsetFrames: Long)
    private data class SeekSample(
        val targetFrame: Long,
        val firstPcmMs: Long,
        val readyMs: Long,
        val anchorFrames: List<Long>,
        val offsetsFrames: List<Long>,
        val lowWaterEvents: Long,
        val underruns: Long,
        val trace: List<String>,
    ) {
        fun toJson() = JSONObject()
            .put("targetFrame", targetFrame)
            .put("firstPcmMs", firstPcmMs)
            .put("resumeWaterlineMs", readyMs)
            .put("anchorFrames", JSONArray(anchorFrames))
            .put("anchorOffsetsFrames", JSONArray(offsetsFrames))
            .put("anchorCount", anchorFrames.size)
            .put("anchorOffsetMinFrames", offsetsFrames.minOrNull() ?: JSONObject.NULL)
            .put("anchorOffsetMaxFrames", offsetsFrames.maxOrNull() ?: JSONObject.NULL)
            .put("anchorOffsetSpreadFrames", offsetsFrames.maxOrNull()?.let { max ->
                max - (offsetsFrames.minOrNull() ?: max)
            } ?: JSONObject.NULL)
            .put("lowWaterEvents", lowWaterEvents)
            .put("underruns", underruns)
            .put("trace", JSONArray(trace))
    }
    private data class SeekMeasurement(
        val caseId: String,
        val deltaMs: Long,
        val mode: String,
        val samples: List<SeekSample>,
    ) {
        fun toJson() = JSONObject()
            .put("case", caseId)
            .put("deltaMs", deltaMs)
            .put("mode", mode)
            .put("sampleCount", samples.size)
            .put("firstPcmP50Ms", percentileValue(samples.map { it.firstPcmMs }, 0.50))
            .put("firstPcmP95Ms", percentileValue(samples.map { it.firstPcmMs }, 0.95))
            .put("resumeWaterlineP50Ms", percentileValue(samples.map { it.readyMs }, 0.50))
            .put("resumeWaterlineP95Ms", percentileValue(samples.map { it.readyMs }, 0.95))
            .put("samples", JSONArray(samples.map(SeekSample::toJson)))

        private fun percentileValue(values: List<Long>, fraction: Double): Long {
            val sorted = values.sorted()
            return sorted[((sorted.size - 1) * fraction).toInt()
                .coerceIn(0, sorted.lastIndex)]
        }
    }

    private fun percentile(values: List<Long>, fraction: Double): Long {
        val sorted = values.sorted()
        return sorted[((sorted.size - 1) * fraction).toInt().coerceIn(0, sorted.lastIndex)]
    }

    private companion object {
        const val FIXTURE_DIRECTORY = "aac-long-s10-fixture"
        const val REPORT_FILE = "source-separation/aac-long-s10-seek-report.json"
        const val STEM_COUNT = 6
        const val WARM_REPETITIONS = 8
        const val COLD_REPETITIONS = 4
        const val POLL_MS = 1L
        const val SEEK_TIMEOUT_NS = 5_000_000_000L
        val ANCHOR_PATTERN = Regex(
            "seek requestedFrame=(-?\\d+) anchorFrame=(-?\\d+) offsetFrames=(-?\\d+)",
        )
    }
}
