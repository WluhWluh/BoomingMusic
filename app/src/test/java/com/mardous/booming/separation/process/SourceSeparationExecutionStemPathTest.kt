package com.mardous.booming.separation.process

import com.mardous.booming.separation.cache.SourceSeparationSegmentPlan
import com.mardous.booming.separation.model.contract.StemId
import java.nio.file.Files
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSeparationExecutionStemPathTest {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }

    @Test
    fun `two stem resume metadata round trips in declared order`() {
        val stemIds = listOf(StemId.Vocals, StemId.Instrumental)
        val value = SourceSeparationExecutionResumeState(
            stemPaths = stemPaths(stemIds),
            timingPath = "timing.json",
            segmentPlan = segmentPlan(stemIds),
        )

        val encoded = json.encodeToString(
            SourceSeparationExecutionResumeState.serializer(),
            value,
        )
        val decoded = json.decodeFromString(
            SourceSeparationExecutionResumeState.serializer(),
            encoded,
        )

        assertEquals(value, decoded)
        assertEquals(stemIds, decoded.stemPaths.map(SourceSeparationExecutionStemPath::stemId))
        assertTrue(encoded.indexOf("vocals") < encoded.indexOf("instrumental"))
        assertFalse(encoded.contains("vocalsPath"))
        assertFalse(encoded.contains("instrumentalPath"))
    }

    @Test
    fun `four stem preparation metadata round trips without MDX execution`() {
        val stemIds = listOf("vocals", "drums", "bass", "other").map(::StemId)
        val value = SourceSeparationExecutionPreparation(
            stemPaths = stemPaths(stemIds),
            timingPath = "timing.json",
            startMs = 0L,
            endMs = 2_000L,
            frames = 88_200,
            windowCount = 4,
            sourceAudioFingerprint = "audio-fingerprint",
            sourceFrameCount = 88_200,
            sourceSampleRate = 44_100,
            sourceChannelCount = 2,
            outputSampleRate = 44_100,
            segmentPlan = segmentPlan(stemIds),
        )

        val encoded = json.encodeToString(
            SourceSeparationExecutionPreparation.serializer(),
            value,
        )
        val decoded = json.decodeFromString(
            SourceSeparationExecutionPreparation.serializer(),
            encoded,
        )

        assertEquals(value, decoded)
        assertEquals(stemIds, decoded.stemPaths.map(SourceSeparationExecutionStemPath::stemId))
    }

    @Test
    fun `six stem completion metadata round trips without MDX execution`() {
        val stemIds = listOf(
            "vocals",
            "drums",
            "bass",
            "guitar",
            "piano",
            "other",
        ).map(::StemId)
        val value = SourceSeparationExecutionCompletion(
            stemPaths = stemPaths(stemIds),
            timingPath = "timing.json",
            startMs = 0L,
            endMs = 2_000L,
            frames = 88_200,
            windowCount = 4,
            elapsedMs = 750L,
            sourceAudioFingerprint = "audio-fingerprint",
            sourceFrameCount = 88_200,
            sourceSampleRate = 44_100,
            sourceChannelCount = 2,
            outputSampleRate = 44_100,
            segmentPlan = segmentPlan(stemIds),
            runtimeSettings = SourceSeparationExecutionRuntimeSettings(
                cpuThreads = 4,
                useXnnpack = true,
            ),
            runtimeDiagnostics = SourceSeparationExecutionRuntimeDiagnostics(
                runtimeName = "LiteRT",
                backend = "Cpu",
                cpuThreads = 4,
                detail = "test",
                fallbackStage = null,
                fallbackReason = null,
            ),
            sourceDecodeDiagnostics = SourceSeparationExecutionSourceDecodeDiagnostics(
                mode = "Window",
                profile = null,
                mimeType = "audio/wav",
                sampleRate = 44_100,
                channelCount = 2,
                sourceFrameCount = 88_200,
                outputFrameCount = 88_200,
                fallbackReason = null,
                experimental = false,
                calibration = null,
                encoderDelayFrames = null,
                encoderPaddingFrames = null,
            ),
            timingAudioDurationSeconds = 2.0,
            timingStageMs = mapOf("inference" to 500L),
        )

        val encoded = json.encodeToString(
            SourceSeparationExecutionCompletion.serializer(),
            value,
        )
        val decoded = json.decodeFromString(
            SourceSeparationExecutionCompletion.serializer(),
            encoded,
        )

        assertEquals(value, decoded)
        assertEquals(stemIds, decoded.stemPaths.map(SourceSeparationExecutionStemPath::stemId))
    }

    @Test
    fun `execution stem path validation rejects empty duplicate and unsafe metadata`() {
        val plan = segmentPlan(listOf(StemId.Vocals))

        assertThrows(IllegalArgumentException::class.java) {
            SourceSeparationExecutionResumeState(
                stemPaths = emptyList(),
                timingPath = null,
                segmentPlan = plan,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            SourceSeparationExecutionResumeState(
                stemPaths = listOf(
                    SourceSeparationExecutionStemPath(StemId.Vocals, 0, "vocals.wav"),
                    SourceSeparationExecutionStemPath(StemId.Vocals, 1, "vocals-copy.wav"),
                ),
                timingPath = null,
                segmentPlan = plan,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            SourceSeparationExecutionStemPath(StemId.Vocals, 0, "../vocals.wav")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SourceSeparationExecutionStemPath(StemId("Vocals"), 0, "vocals.wav")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SourceSeparationExecutionResumeState(
                stemPaths = stemPaths(listOf(StemId.Instrumental, StemId.Vocals)),
                timingPath = null,
                segmentPlan = segmentPlan(StemId.MdxOrdered),
            )
        }
    }

    @Test
    fun `MDX resume restores files by stem ID and rejects incomplete stem sets`() {
        val entryDirectory = Files.createTempDirectory("bss-execution-stems").toFile()
        try {
            val reversed = SourceSeparationExecutionResumeState(
                stemPaths = listOf(
                    SourceSeparationExecutionStemPath(
                        StemId.Instrumental,
                        0,
                        "stems/instrumental.wav",
                    ),
                    SourceSeparationExecutionStemPath(StemId.Vocals, 1, "stems/vocals.wav"),
                ),
                timingPath = "timing.json",
                segmentPlan = segmentPlan(StemId.MdxOrdered.reversed()),
            )

            val restored = reversed.toMdxRangeResumeState(entryDirectory)
            assertEquals(
                StemId.MdxOrdered.reversed(),
                reversed.stemPaths.map(SourceSeparationExecutionStemPath::stemId),
            )
            assertEquals("vocals.wav", restored.vocalsFile.name)
            assertEquals("instrumental.wav", restored.instrumentalFile.name)
            assertEquals("timing.json", restored.timingFile?.name)

            val incomplete = SourceSeparationExecutionResumeState(
                stemPaths = listOf(
                    SourceSeparationExecutionStemPath(StemId.Vocals, 0, "stems/vocals.wav"),
                ),
                timingPath = null,
                segmentPlan = segmentPlan(listOf(StemId.Vocals)),
            )
            assertThrows(IllegalArgumentException::class.java) {
                incomplete.toMdxRangeResumeState(entryDirectory)
            }

            val multiStem = SourceSeparationExecutionResumeState(
                stemPaths = stemPaths(
                    listOf(StemId.Vocals, StemId.Instrumental, StemId("drums")),
                ),
                timingPath = null,
                segmentPlan = segmentPlan(
                    listOf(StemId.Vocals, StemId.Instrumental, StemId("drums")),
                ),
            )
            assertThrows(IllegalArgumentException::class.java) {
                multiStem.toMdxRangeResumeState(entryDirectory)
            }
        } finally {
            entryDirectory.deleteRecursively()
        }
    }

    private fun stemPaths(stemIds: List<StemId>): List<SourceSeparationExecutionStemPath> =
        stemIds.mapIndexed { index, stemId ->
            SourceSeparationExecutionStemPath(
                stemId = stemId,
                order = index,
                path = "stems/%02d-%s.wav".format(index, stemId.value),
            )
        }

    private fun segmentPlan(stemIds: List<StemId>): SourceSeparationSegmentPlan =
        SourceSeparationSegmentPlan.build(
            rangeStartFrame = 0,
            rangeEndFrame = 0,
            sampleRate = 44_100,
            generationSize = 44_100,
            trim = 0,
            chunkSize = 44_100,
            stemIds = stemIds,
        )
}
