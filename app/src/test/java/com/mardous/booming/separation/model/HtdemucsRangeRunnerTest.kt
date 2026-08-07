package com.mardous.booming.separation.model

import com.mardous.booming.separation.SourceSeparationPauseReason
import com.mardous.booming.separation.SourceSeparationPausedException
import com.mardous.booming.separation.cache.SourceSeparationSegmentState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HtdemucsRangeRunnerTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `four and six stem runs publish complete ordered wav and segment sets`() {
        listOf(
            listOf("drums", "bass", "other", "vocals"),
            listOf("drums", "bass", "other", "vocals", "guitar", "piano"),
        ).forEachIndexed { runIndex, stemIds ->
            val source = FakeTrackSource(TRACK_FRAMES)
            val session = FakeSession(stemIds)
            val root = temporary.newFolder("run-$runIndex")
            val states = mutableListOf<Pair<Int, SourceSeparationSegmentState>>()
            var preparation: HtdemucsRangePreparation? = null

            val result = HtdemucsRangeRunner(source, session).run(
                outputDirectory = root.resolve("work"),
                segmentDirectory = root.resolve("segments"),
                onPrepared = { preparation = it },
                onSegmentStateChanged = { index, state -> states += index to state },
            )

            assertEquals(stemIds, result.stemFiles.map { it.stemId.value })
            assertEquals(1, result.windowCount)
            assertEquals(1, session.runCount)
            assertEquals(
                listOf(0 to SourceSeparationSegmentState.Running, 0 to SourceSeparationSegmentState.Ready),
                states,
            )
            assertEquals(stemIds, preparation?.stemFiles?.map { it.stemId.value })
            assertTrue(result.segmentPlan.segments.single().state == SourceSeparationSegmentState.Ready)
            result.stemFiles.forEach { stem ->
                assertEquals(WAV_HEADER_BYTES + TRACK_FRAMES * 4L, stem.file.length())
            }
            result.segmentPlan.segments.single().stems.forEach { stem ->
                val file = root.resolve(stem.path)
                assertEquals(WAV_HEADER_BYTES + TRACK_FRAMES * 4L, file.length())
            }
        }
    }

    @Test
    fun `pause raised during window progress interrupts before model execution`() {
        var pause = false
        val source = object : HtdemucsTrackSource {
            override val frameCount = HtdemucsPipelineAdapter.WINDOW_SAMPLES + 1

            override fun readPlanarStereo(
                startFrame: Int,
                frameCount: Int,
                shouldCancel: () -> Boolean,
            ): FloatArray {
                shouldCancel()
                return FloatArray(frameCount * 2)
            }
        }
        val session = FakeSession(listOf("drums", "bass", "other", "vocals"))
        val root = temporary.newFolder("pause-inside-window")

        val error = assertThrows(SourceSeparationPausedException::class.java) {
            HtdemucsRangeRunner(source, session).run(
                outputDirectory = root.resolve("work"),
                segmentDirectory = root.resolve("segments"),
                onProgress = { progress ->
                    if (progress.stage.startsWith("Processing window")) pause = true
                },
                shouldPause = { pause },
                pauseReasonProvider = { SourceSeparationPauseReason.ActiveModelSuperseded },
            )
        }

        assertEquals(SourceSeparationPauseReason.ActiveModelSuperseded, error.pauseReason)
        assertEquals(0, session.runCount)
    }

    private class FakeTrackSource(
        override val frameCount: Int,
    ) : HtdemucsTrackSource {
        private val track = FloatArray(frameCount * 2) { index ->
            ((index * 17L + 3L) % 257L).toFloat() / 257f - 0.5f
        }

        override fun readPlanarStereo(
            startFrame: Int,
            frameCount: Int,
            shouldCancel: () -> Boolean,
        ): FloatArray = FloatArray(frameCount * 2).also { output ->
            repeat(2) { channel ->
                repeat(frameCount) { localFrame ->
                    val sourceFrame = startFrame + localFrame
                    if (sourceFrame in 0 until this.frameCount) {
                        output[channel * frameCount + localFrame] =
                            track[channel * this.frameCount + sourceFrame]
                    }
                }
            }
        }
    }

    private class FakeSession(
        override val orderedStemIds: List<String>,
    ) : HtdemucsTrackInferenceSession {
        var runCount = 0

        override fun runNormalizedWindow(
            normalizedPlanarStereo: FloatArray,
            shouldCancel: () -> Boolean,
        ): HtdemucsWindowStemSet {
            assertEquals(HtdemucsPipelineAdapter.WINDOW_SAMPLES * 2, normalizedPlanarStereo.size)
            runCount += 1
            return HtdemucsWindowStemSet(
                orderedStemIds = orderedStemIds,
                planarSamples = FloatArray(
                    orderedStemIds.size * 2 * HtdemucsPipelineAdapter.WINDOW_SAMPLES,
                ),
                samplesPerStem = HtdemucsPipelineAdapter.WINDOW_SAMPLES,
            )
        }

        override fun close() = Unit
    }

    private companion object {
        const val TRACK_FRAMES = 4_096
        const val WAV_HEADER_BYTES = 44L
    }
}
