package com.mardous.booming.separation.model

import com.mardous.booming.separation.SourceSeparationPauseReason
import com.mardous.booming.separation.SourceSeparationPausedException
import com.mardous.booming.separation.cache.SourceSeparationSegmentState
import com.mardous.booming.separation.cache.SourceSeparationSegmentPlan
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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
                onSegmentStateChanged = { index, state, _ -> states += index to state },
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
                onSegmentStateChanged = { index, state, _ ->
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
                onSegmentStateChanged = { index, state, _ ->
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

    @Test
    fun `progress exposes changing playback demand and ready waterline`() {
        val frames = HtdemucsPipelineAdapter.WINDOW_SAMPLES +
            HtdemucsTrackWindowPlanner.STRIDE_SAMPLES * 3 + 7_000
        val root = temporary.newFolder("scheduler-progress")
        val positionMs = AtomicLong(0L)
        val progress = mutableListOf<HtdemucsRangeProgress>()
        val firstWindowEndMs =
            (HtdemucsTrackWindowPlanner.STRIDE_SAMPLES * 1_000L) /
                HtdemucsPipelineAdapter.SAMPLE_RATE + 1L

        HtdemucsRangeRunner(
            FakeTrackSource(frames),
            CopyingSession(listOf("drums", "bass", "other", "vocals")),
        ).run(
            outputDirectory = root.resolve("work"),
            segmentDirectory = root.resolve("segments"),
            onProgress = { snapshot ->
                progress += snapshot
                if (snapshot.stage == "Processed window 1/${HtdemucsTrackWindowPlanner.plans(frames).size}") {
                    positionMs.set(firstWindowEndMs)
                }
            },
            playbackPositionMsProvider = { positionMs.get() },
            playbackReadyWindowCountProvider = { 2 },
        )

        val shifted = progress.first {
            it.scheduler?.playbackSegmentIndex == 1
        }
        assertEquals(2, shifted.scheduler?.readyWindowCount)
        assertEquals(1, shifted.scheduler?.playbackSegmentIndex)
        assertEquals(2, shifted.scheduler?.playbackReadyWindowPendingCount)
        val ready = progress.last { it.completedWindows >= 2 && it.scheduler != null }
        assertTrue(ready.scheduler!!.playbackReadyWindowReadyCount >= 1)
    }

    @Test
    fun `mid run forward seek retargets before the old cursor and preserves output`() {
        val stemIds = listOf("drums", "vocals")
        val frames = HtdemucsPipelineAdapter.WINDOW_SAMPLES +
            HtdemucsTrackWindowPlanner.STRIDE_SAMPLES * 9 + 7_000
        val plans = HtdemucsTrackWindowPlanner.plans(frames)
        val targetIndex = 7
        val targetFrame = plans[targetIndex].offset +
            HtdemucsTrackWindowPlanner.OVERLAP_SAMPLES + 1_000
        val source = FakeTrackSource(frames)
        val referenceRoot = temporary.newFolder("mid-run-forward-reference")
        val reference = HtdemucsRangeRunner(
            source,
            CopyingSession(stemIds),
        ).run(
            outputDirectory = referenceRoot.resolve("work"),
            segmentDirectory = referenceRoot.resolve("segments"),
        )
        val expected = reference.stemFiles.map { it.file.readBytes() }

        val root = temporary.newFolder("mid-run-forward")
        val positionMs = AtomicLong(0L)
        val processingIndexes = mutableListOf<Int>()
        val progressSnapshots = mutableListOf<HtdemucsRangeProgress>()
        var preparedPlan: SourceSeparationSegmentPlan? = null
        var committedBeforeSeek: List<ByteArray>? = null
        var preservedAtTarget = false
        val result = HtdemucsRangeRunner(
            source,
            CopyingSession(stemIds),
        ).run(
            outputDirectory = root.resolve("work"),
            segmentDirectory = root.resolve("segments"),
            onPrepared = { preparedPlan = it.segmentPlan },
            onProgress = { progress ->
                progressSnapshots += progress
                if (progress.stage.startsWith("Processing window")) {
                    processingIndexes += requireNotNull(progress.scheduler)
                        .processingSegmentIndex
                }
                if (progress.stage == "Processed window 2/${plans.size}") {
                    positionMs.set(positionMsAtOrAfter(targetFrame))
                }
            },
            onSegmentStateChanged = { index, state, _ ->
                if (index == 1 && state == SourceSeparationSegmentState.Ready) {
                    committedBeforeSeek = requireNotNull(preparedPlan).segments
                        .take(2)
                        .flatMap { segment -> segment.stems }
                        .map { stem -> root.resolve(stem.path).readBytes() }
                }
                if (index == targetIndex &&
                    state == SourceSeparationSegmentState.Provisional
                ) {
                    val retained = requireNotNull(preparedPlan).segments
                        .take(2)
                        .flatMap { segment -> segment.stems }
                        .map { stem -> root.resolve(stem.path).readBytes() }
                    requireNotNull(committedBeforeSeek).zip(retained).forEach { (before, after) ->
                        assertArrayEquals(before, after)
                    }
                    preservedAtTarget = true
                }
            },
            playbackPositionMsProvider = { positionMs.get() },
            playbackReadyWindowCountProvider = { 2 },
        )

        assertEquals(listOf(0, 1, targetIndex, targetIndex + 1), processingIndexes.take(4))
        val demandReady = progressSnapshots.first {
            it.stage == "Processed window ${targetIndex + 2}/${plans.size}"
        }.scheduler
        assertEquals(2, demandReady?.playbackReadyWindowReadyCount)
        assertEquals(0, demandReady?.playbackReadyWindowPendingCount)
        assertTrue(preservedAtTarget)
        assertTrue(result.segmentPlan.segments.all { it.state.isComplete })
        result.stemFiles.forEachIndexed { index, stem ->
            assertArrayEquals(expected[index], stem.file.readBytes())
        }
    }

    @Test
    fun `mid run overlap seek warms the previous window and satisfies the waterline`() {
        val frames = HtdemucsPipelineAdapter.WINDOW_SAMPLES +
            HtdemucsTrackWindowPlanner.STRIDE_SAMPLES * 9 + 7_000
        val plans = HtdemucsTrackWindowPlanner.plans(frames)
        val targetIndex = 7
        val targetFrame = plans[targetIndex].offset + 1_000
        val positionMs = AtomicLong(0L)
        val processingIndexes = mutableListOf<Int>()
        val stateChanges = mutableListOf<Triple<Int, SourceSeparationSegmentState, Int?>>()
        val progressSnapshots = mutableListOf<HtdemucsRangeProgress>()

        HtdemucsRangeRunner(
            FakeTrackSource(frames),
            CopyingSession(listOf("drums", "bass", "other", "vocals")),
        ).run(
            outputDirectory = temporary.newFolder("mid-run-overlap").resolve("work"),
            segmentDirectory = temporary.root.resolve("mid-run-overlap/segments"),
            onProgress = { progress ->
                progressSnapshots += progress
                if (progress.stage.startsWith("Processing window")) {
                    processingIndexes += requireNotNull(progress.scheduler)
                        .processingSegmentIndex
                }
                if (progress.stage == "Processed window 2/${plans.size}") {
                    positionMs.set(positionMsAtOrAfter(targetFrame))
                }
            },
            onSegmentStateChanged = { index, state, playableFromFrame ->
                stateChanges += Triple(index, state, playableFromFrame)
            },
            playbackPositionMsProvider = { positionMs.get() },
            playbackReadyWindowCountProvider = { 2 },
        )

        assertEquals(
            listOf(0, 1, targetIndex - 1, targetIndex),
            processingIndexes.take(4),
        )
        assertTrue(stateChanges.any { (index, state, _) ->
            index == targetIndex - 1 && state == SourceSeparationSegmentState.Provisional
        })
        assertTrue(stateChanges.any { (index, state, _) ->
            index == targetIndex && state == SourceSeparationSegmentState.Ready
        })
        val demandReady = progressSnapshots.first {
            it.stage == "Processed window ${targetIndex + 1}/${plans.size}"
        }.scheduler
        assertEquals(2, demandReady?.playbackReadyWindowReadyCount)
        assertEquals(0, demandReady?.playbackReadyWindowPendingCount)
    }

    @Test
    fun `newest backward seek retargets again while a ready seek leaves the pass alone`() {
        val frames = HtdemucsPipelineAdapter.WINDOW_SAMPLES +
            HtdemucsTrackWindowPlanner.STRIDE_SAMPLES * 10 + 7_000
        val plans = HtdemucsTrackWindowPlanner.plans(frames)
        val forwardIndex = 8
        val backwardIndex = 3
        val forwardFrame = plans[forwardIndex].offset +
            HtdemucsTrackWindowPlanner.OVERLAP_SAMPLES + 1_000
        val backwardFrame = plans[backwardIndex].offset +
            HtdemucsTrackWindowPlanner.OVERLAP_SAMPLES + 1_000
        val positionMs = AtomicLong(0L)
        val processingIndexes = mutableListOf<Int>()
        var backwardSeekIssued = false
        var readySeekIssued = false

        HtdemucsRangeRunner(
            FakeTrackSource(frames),
            CopyingSession(listOf("drums", "vocals")),
        ).run(
            outputDirectory = temporary.newFolder("repeated-mid-run-seek").resolve("work"),
            segmentDirectory = temporary.root.resolve("repeated-mid-run-seek/segments"),
            onProgress = { progress ->
                if (progress.stage.startsWith("Processing window")) {
                    val processingIndex = requireNotNull(progress.scheduler)
                        .processingSegmentIndex
                    processingIndexes += processingIndex
                    if (processingIndex == forwardIndex && !backwardSeekIssued) {
                        positionMs.set(positionMsAtOrAfter(backwardFrame))
                        backwardSeekIssued = true
                    }
                }
                if (progress.stage == "Processed window 2/${plans.size}") {
                    positionMs.set(positionMsAtOrAfter(forwardFrame))
                }
                if (progress.stage ==
                    "Processed window ${backwardIndex + 1}/${plans.size}" &&
                    !readySeekIssued
                ) {
                    positionMs.set(positionMsAtOrAfter(plans[0].offset + 1_000))
                    readySeekIssued = true
                }
            },
            playbackPositionMsProvider = { positionMs.get() },
        )

        assertTrue(backwardSeekIssued)
        assertTrue(readySeekIssued)
        assertEquals(
            listOf(
                0,
                1,
                forwardIndex,
                backwardIndex,
                backwardIndex + 1,
                backwardIndex + 2,
                backwardIndex + 3,
            ),
            processingIndexes.take(7),
        )
    }

    @Test
    fun `playback demand inside the leading overlap warms the previous window`() {
        val frames = HtdemucsPipelineAdapter.WINDOW_SAMPLES +
            HtdemucsTrackWindowPlanner.STRIDE_SAMPLES * 3 + 7_000
        val plans = HtdemucsTrackWindowPlanner.plans(frames)
        val targetIndex = 2
        val positionMs = AtomicLong(
            plans[targetIndex].offset.toLong() * 1_000L /
                HtdemucsPipelineAdapter.SAMPLE_RATE,
        )
        val source = FakeTrackSource(frames)
        val referenceRoot = temporary.newFolder("playback-demand-start-reference")
        val reference = HtdemucsRangeRunner(
            source,
            CopyingSession(listOf("drums", "bass", "other", "vocals")),
        ).run(
            outputDirectory = referenceRoot.resolve("work"),
            segmentDirectory = referenceRoot.resolve("segments"),
        )
        val expected = reference.stemFiles.map { it.file.readBytes() }
        val root = temporary.newFolder("playback-demand-start")
        val processingIndexes = mutableListOf<Int>()
        val states = mutableListOf<Triple<Int, SourceSeparationSegmentState, Int?>>()
        val progressSnapshots = mutableListOf<HtdemucsRangeProgress>()

        val result = HtdemucsRangeRunner(
            source,
            CopyingSession(listOf("drums", "bass", "other", "vocals")),
        ).run(
            outputDirectory = root.resolve("work"),
            segmentDirectory = root.resolve("segments"),
            onProgress = { progress ->
                progressSnapshots += progress
                if (progress.stage.startsWith("Processing window")) {
                    processingIndexes += requireNotNull(progress.scheduler).processingSegmentIndex
                }
            },
            onSegmentStateChanged = { index, state, validFromFrame ->
                states += Triple(index, state, validFromFrame)
            },
            playbackPositionMsProvider = { positionMs.get() },
            playbackReadyWindowCountProvider = { 2 },
        )

        assertEquals(targetIndex - 1, processingIndexes.first())
        val provisional = states.first { it.second == SourceSeparationSegmentState.Provisional }
        assertEquals(targetIndex - 1, provisional.first)
        assertEquals(
            plans[targetIndex - 1].offset + HtdemucsTrackWindowPlanner.OVERLAP_SAMPLES,
            provisional.third,
        )
        val readyIndexes = states
            .filter { it.second == SourceSeparationSegmentState.Ready }
            .map { it.first }
        assertEquals(
            (targetIndex..plans.lastIndex).toList() +
                (0 until targetIndex).toList(),
            readyIndexes,
        )
        assertTrue(readyIndexes.indexOf(targetIndex) < readyIndexes.indexOf(0))
        val demandReady = progressSnapshots.first {
            it.stage == "Processed window ${targetIndex + 1}/${plans.size}"
        }.scheduler
        assertEquals(2, demandReady?.playbackReadyWindowReadyCount)
        assertEquals(0, demandReady?.playbackReadyWindowPendingCount)
        result.stemFiles.forEachIndexed { index, stem ->
            assertArrayEquals(expected[index], stem.file.readBytes())
        }
    }

    @Test
    fun `playback demand after the leading overlap publishes provisional suffix then repairs it`() {
        val frames = HtdemucsPipelineAdapter.WINDOW_SAMPLES +
            HtdemucsTrackWindowPlanner.STRIDE_SAMPLES * 3 + 7_000
        val plans = HtdemucsTrackWindowPlanner.plans(frames)
        val targetIndex = 2
        val playableFromFrame = plans[targetIndex].offset +
            HtdemucsTrackWindowPlanner.OVERLAP_SAMPLES
        val positionMs = AtomicLong(
            ((playableFromFrame + 1L) * 1_000L +
                HtdemucsPipelineAdapter.SAMPLE_RATE - 1L) /
                HtdemucsPipelineAdapter.SAMPLE_RATE,
        )
        val source = FakeTrackSource(frames)
        val referenceRoot = temporary.newFolder("playback-demand-reference")
        val reference = HtdemucsRangeRunner(
            source,
            CopyingSession(listOf("drums", "bass", "other", "vocals")),
        ).run(
            outputDirectory = referenceRoot.resolve("work"),
            segmentDirectory = referenceRoot.resolve("segments"),
        )
        val expected = reference.stemFiles.map { it.file.readBytes() }
        val root = temporary.newFolder("playback-demand-provisional")
        val processingIndexes = mutableListOf<Int>()
        val states = mutableListOf<Triple<Int, SourceSeparationSegmentState, Int?>>()

        val result = HtdemucsRangeRunner(
            source,
            CopyingSession(listOf("drums", "bass", "other", "vocals")),
        ).run(
            outputDirectory = root.resolve("work"),
            segmentDirectory = root.resolve("segments"),
            onProgress = { progress ->
                if (progress.stage.startsWith("Processing window")) {
                    processingIndexes += requireNotNull(progress.scheduler).processingSegmentIndex
                }
            },
            onSegmentStateChanged = { index, state, validFromFrame ->
                states += Triple(index, state, validFromFrame)
            },
            playbackPositionMsProvider = { positionMs.get() },
            playbackReadyWindowCountProvider = { 2 },
        )

        assertEquals(targetIndex, processingIndexes.first())
        val provisional = states.first { it.second == SourceSeparationSegmentState.Provisional }
        assertEquals(targetIndex, provisional.first)
        assertEquals(playableFromFrame, provisional.third)
        assertTrue(states.indexOf(provisional) < states.indexOfFirst {
            it.first == 0 && it.second == SourceSeparationSegmentState.Ready
        })
        assertEquals(
            SourceSeparationSegmentState.Ready,
            states.last { it.first == targetIndex }.second,
        )
        val readyIndexes = states
            .filter { it.second == SourceSeparationSegmentState.Ready }
            .map { it.first }
        assertEquals(
            ((targetIndex + 1)..plans.lastIndex).toList() +
                (0 until targetIndex).toList() +
                targetIndex,
            readyIndexes,
        )
        assertTrue(result.segmentPlan.segments.all { it.state == SourceSeparationSegmentState.Ready })
        result.stemFiles.forEachIndexed { index, stem ->
            assertArrayEquals(expected[index], stem.file.readBytes())
        }
    }

    @Test
    fun `resume continues beyond a safe provisional demand before zero based backfill`() {
        val stemIds = listOf("drums", "vocals")
        val frames = HtdemucsPipelineAdapter.WINDOW_SAMPLES +
            HtdemucsTrackWindowPlanner.STRIDE_SAMPLES * 7 + 7_000
        val plans = HtdemucsTrackWindowPlanner.plans(frames)
        val targetIndex = 2
        val playableFromFrame = plans[targetIndex].offset +
            HtdemucsTrackWindowPlanner.OVERLAP_SAMPLES
        val positionMs = AtomicLong(
            ((playableFromFrame + 1L) * 1_000L +
                HtdemucsPipelineAdapter.SAMPLE_RATE - 1L) /
                HtdemucsPipelineAdapter.SAMPLE_RATE,
        )
        val source = FakeTrackSource(frames)
        val referenceRoot = temporary.newFolder("provisional-resume-reference")
        val reference = HtdemucsRangeRunner(
            source,
            CopyingSession(stemIds),
        ).run(
            outputDirectory = referenceRoot.resolve("work"),
            segmentDirectory = referenceRoot.resolve("segments"),
        )
        val expected = reference.stemFiles.map { it.file.readBytes() }

        val root = temporary.newFolder("provisional-resume")
        val pause = AtomicBoolean(false)
        var partialPlan: SourceSeparationSegmentPlan? = null
        assertThrows(SourceSeparationPausedException::class.java) {
            HtdemucsRangeRunner(
                source,
                CopyingSession(stemIds),
            ).run(
                outputDirectory = root.resolve("work"),
                segmentDirectory = root.resolve("segments"),
                onPrepared = { partialPlan = it.segmentPlan },
                onSegmentStateChanged = { index, state, validFromFrame ->
                    partialPlan = requireNotNull(partialPlan).withSegmentState(
                        segmentIndex = index,
                        state = state,
                        playableFromFrame = validFromFrame,
                    )
                    if (state == SourceSeparationSegmentState.Ready && index == 4) {
                        pause.set(true)
                    }
                },
                playbackPositionMsProvider = { positionMs.get() },
                shouldPause = pause::get,
            )
        }
        val resumablePlan = requireNotNull(partialPlan).copy(
            segments = requireNotNull(partialPlan).segments.map { segment ->
                if (segment.state == SourceSeparationSegmentState.Running) {
                    segment.copy(
                        state = SourceSeparationSegmentState.Queued,
                        playableFromFrame = null,
                    )
                } else {
                    segment
                }
            },
        )
        assertEquals(SourceSeparationSegmentState.Provisional, resumablePlan.segments[2].state)
        assertEquals(playableFromFrame, resumablePlan.segments[2].playableFromFrame)
        assertEquals(
            listOf(3, 4),
            resumablePlan.segments.filter { it.state.isComplete }.map { it.index },
        )

        val processingIndexes = mutableListOf<Int>()
        val resumed = HtdemucsRangeRunner(
            source,
            CopyingSession(stemIds),
        ).run(
            outputDirectory = root.resolve("work"),
            segmentDirectory = root.resolve("segments"),
            resumeState = HtdemucsRangeResumeState(resumablePlan),
            onProgress = { progress ->
                if (progress.stage.startsWith("Processing window")) {
                    processingIndexes += requireNotNull(progress.scheduler)
                        .processingSegmentIndex
                }
            },
            playbackPositionMsProvider = { positionMs.get() },
        )

        assertEquals(
            (4..plans.lastIndex).toList() + (0..2).toList(),
            processingIndexes,
        )
        assertTrue(resumed.segmentPlan.segments.all { it.state.isComplete })
        resumed.stemFiles.forEachIndexed { index, stem ->
            assertArrayEquals(expected[index], stem.file.readBytes())
        }
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

        fun positionMsAtOrAfter(frame: Int): Long =
            (frame.toLong() * 1_000L + HtdemucsPipelineAdapter.SAMPLE_RATE - 1L) /
                HtdemucsPipelineAdapter.SAMPLE_RATE
    }
}
