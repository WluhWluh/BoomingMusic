package com.mardous.booming.separation.model

import com.mardous.booming.separation.SourceSeparationPauseReason
import com.mardous.booming.separation.SourceSeparationPausedException
import com.mardous.booming.separation.cache.SourceSeparationSegmentState
import com.mardous.booming.separation.cache.SourceSeparationSegmentPlan
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertArrayEquals
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

    @Test
    fun `resume reuses committed segments and matches uninterrupted wav output`() {
        val stemIds = listOf("drums", "vocals")
        val frames = HtdemucsPipelineAdapter.WINDOW_SAMPLES +
            HtdemucsTrackWindowPlanner.STRIDE_SAMPLES * 3 + 7_000
        val source = FakeTrackSource(frames)
        val fullRoot = temporary.newFolder("resume-full")
        val fullSession = CopyingSession(stemIds)
        val full = HtdemucsRangeRunner(source, fullSession).run(
            outputDirectory = fullRoot.resolve("work"),
            segmentDirectory = fullRoot.resolve("segments"),
        )
        val expected = full.stemFiles.map { it.file.readBytes() }

        val resumedRoot = temporary.newFolder("resume-partial")
        val pausedSession = CopyingSession(stemIds)
        val pause = AtomicBoolean(false)
        var partialPlan: SourceSeparationSegmentPlan? = null
        assertThrows(SourceSeparationPausedException::class.java) {
            HtdemucsRangeRunner(source, pausedSession).run(
                outputDirectory = resumedRoot.resolve("work"),
                segmentDirectory = resumedRoot.resolve("segments"),
                onPrepared = { partialPlan = it.segmentPlan },
                onSegmentStateChanged = { index, state ->
                    partialPlan = requireNotNull(partialPlan).withSegmentState(index, state)
                    if (state == SourceSeparationSegmentState.Ready && index == 2) {
                        pause.set(true)
                    }
                },
                shouldPause = pause::get,
            )
        }
        val resumablePlan = requireNotNull(partialPlan).copy(
            segments = requireNotNull(partialPlan).segments.map { segment ->
                if (segment.state == SourceSeparationSegmentState.Running) {
                    segment.copy(state = SourceSeparationSegmentState.Queued)
                } else {
                    segment
                }
            },
        )
        assertEquals(listOf(0, 1, 2), resumablePlan.segments
            .filter { it.state.isPlaybackReady }.map { it.index })
        val committedBeforeResume = resumablePlan.segments
            .filter { it.state.isPlaybackReady }
            .flatMap { segment -> segment.stems.map { resumedRoot.resolve(it.path) } }
            .associateWith(File::readBytes)
        resumedRoot.resolve("work").listFiles().orEmpty().forEach { work ->
            if (work.name.startsWith("stem-")) work.writeText("corrupt-work-file")
        }

        val resumeSession = CopyingSession(stemIds)
        val resumed = HtdemucsRangeRunner(source, resumeSession).run(
            outputDirectory = resumedRoot.resolve("work"),
            segmentDirectory = resumedRoot.resolve("segments"),
            resumeState = HtdemucsRangeResumeState(resumablePlan),
        )

        assertTrue(resumeSession.runCount < fullSession.runCount)
        committedBeforeResume.forEach { (file, bytes) -> assertArrayEquals(bytes, file.readBytes()) }
        resumed.stemFiles.forEachIndexed { index, stem ->
            assertArrayEquals(expected[index], stem.file.readBytes())
        }
        assertTrue(resumed.segmentPlan.segments.all { it.state.isPlaybackReady })
    }

    @Test
    fun `invalid normalization checkpoint is discarded and recomputed`() {
        val stemIds = listOf("drums", "vocals")
        val frames = HtdemucsPipelineAdapter.WINDOW_SAMPLES +
            HtdemucsTrackWindowPlanner.STRIDE_SAMPLES * 2 + 1
        val root = temporary.newFolder("resume-invalid-checkpoint")
        val source = CountingTrackSource(frames)
        val pause = AtomicBoolean(false)
        var partialPlan: SourceSeparationSegmentPlan? = null
        assertThrows(SourceSeparationPausedException::class.java) {
            HtdemucsRangeRunner(source, CopyingSession(stemIds)).run(
                outputDirectory = root.resolve("work"),
                segmentDirectory = root.resolve("segments"),
                onPrepared = { partialPlan = it.segmentPlan },
                onSegmentStateChanged = { index, state ->
                    partialPlan = requireNotNull(partialPlan).withSegmentState(index, state)
                    if (state == SourceSeparationSegmentState.Ready && index == 0) pause.set(true)
                },
                shouldPause = pause::get,
            )
        }
        val resumablePlan = requireNotNull(partialPlan).copy(
            segments = requireNotNull(partialPlan).segments.map { segment ->
                if (segment.state == SourceSeparationSegmentState.Running) {
                    segment.copy(state = SourceSeparationSegmentState.Queued)
                } else segment
            },
        )
        root.resolve("work/htdemucs-normalization-v1.bin").writeText("invalid")
        source.readCount = 0

        HtdemucsRangeRunner(source, CopyingSession(stemIds)).run(
            outputDirectory = root.resolve("work"),
            segmentDirectory = root.resolve("segments"),
            resumeState = HtdemucsRangeResumeState(resumablePlan),
        )

        assertTrue(source.readCount > HtdemucsTrackWindowPlanner.plans(frames).size)
        assertTrue(root.resolve("work/htdemucs-normalization-v1.bin").length() > 16L)
    }

    private open class FakeTrackSource(
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

    private class CountingTrackSource(frameCount: Int) : FakeTrackSource(frameCount) {
        var readCount = 0

        override fun readPlanarStereo(
            startFrame: Int,
            frameCount: Int,
            shouldCancel: () -> Boolean,
        ): FloatArray {
            readCount += 1
            return super.readPlanarStereo(startFrame, frameCount, shouldCancel)
        }
    }

    private class CopyingSession(
        override val orderedStemIds: List<String>,
    ) : HtdemucsTrackInferenceSession {
        var runCount = 0

        override fun runNormalizedWindow(
            normalizedPlanarStereo: FloatArray,
            shouldCancel: () -> Boolean,
        ): HtdemucsWindowStemSet {
            shouldCancel()
            runCount += 1
            val frames = HtdemucsPipelineAdapter.WINDOW_SAMPLES
            return HtdemucsWindowStemSet(
                orderedStemIds = orderedStemIds,
                planarSamples = FloatArray(orderedStemIds.size * 2 * frames).also { output ->
                    orderedStemIds.indices.forEach { stem ->
                        repeat(2) { channel ->
                            normalizedPlanarStereo.copyInto(
                                destination = output,
                                destinationOffset = (stem * 2 + channel) * frames,
                                startIndex = channel * frames,
                                endIndex = (channel + 1) * frames,
                            )
                        }
                    }
                },
                samplesPerStem = frames,
            )
        }

        override fun close() = Unit
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
