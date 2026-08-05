package com.mardous.booming.ui.screen.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSeparationPlaybackProcessingProgressTrackerTest {

    @Test
    fun `transient service state cannot create a new processing generation`() {
        val sessions = SourceSeparationPlaybackProcessingSessionGeneration()

        val first = sessions.generationFor(songId = 42L, currentGeneration = 0L)
        val afterTransientState = sessions.generationFor(
            songId = 42L,
            currentGeneration = first,
        )

        assertTrue(first == 1L)
        assertTrue(afterTransientState == first)
    }

    @Test
    fun `explicit invalidation starts a fresh processing generation`() {
        val sessions = SourceSeparationPlaybackProcessingSessionGeneration()
        val first = sessions.generationFor(songId = 42L, currentGeneration = 0L)

        sessions.invalidate(songId = 42L)
        val restarted = sessions.generationFor(
            songId = 42L,
            currentGeneration = first,
        )

        assertTrue(restarted == first + 1L)
    }

    @Test
    fun `scheduler appearance does not reset initial progress`() {
        val tracker = SourceSeparationPlaybackProcessingProgressTracker(startedAtMs = 0L)
        val initial = observation(
            hasScheduler = false,
            completedWorkUnits = 0,
            targetWindows = 3,
            pendingWindows = 0,
        )
        val beforeScheduler = tracker.sample(initial, nowMs = 500L).progress
        val withScheduler = tracker.sample(
            observation = observation(
                hasScheduler = true,
                completedWorkUnits = 0,
                targetWindows = 2,
                pendingWindows = 2,
            ),
            nowMs = 600L,
        ).progress

        assertTrue(withScheduler >= beforeScheduler)
    }

    @Test
    fun `large initial target is approached without a visible jump`() {
        val tracker = SourceSeparationPlaybackProcessingProgressTracker(startedAtMs = 0L)
        val scheduler = observation(
            hasScheduler = true,
            completedWorkUnits = 0,
            targetWindows = 2,
            pendingWindows = 2,
        )

        val initial = tracker.sample(scheduler, nowMs = 0L).progress
        val nextFrame = tracker.sample(scheduler, nowMs = 50L).progress

        assertTrue(initial == 0f)
        assertTrue(nextFrame > initial)
        assertTrue(nextFrame < 0.1f)
    }

    @Test
    fun `rapid scheduler snapshots do not restart the estimate`() {
        val tracker = SourceSeparationPlaybackProcessingProgressTracker(startedAtMs = 0L)
        val first = observation(
            hasScheduler = true,
            completedWorkUnits = 0,
            targetWindows = 3,
            pendingWindows = 3,
        )
        tracker.sample(first, nowMs = 0L)
        val beforeMetadataUpdate = tracker.sample(first, nowMs = 400L).progress
        val afterMetadataUpdate = tracker.sample(
            observation = first.copy(pendingWindows = 4),
            nowMs = 500L,
        ).progress

        assertTrue(afterMetadataUpdate > beforeMetadataUpdate)
    }

    @Test
    fun `completed work reanchors without moving progress backward`() {
        val tracker = SourceSeparationPlaybackProcessingProgressTracker(startedAtMs = 0L)
        val waiting = observation(
            hasScheduler = true,
            completedWorkUnits = 0,
            targetWindows = 2,
            pendingWindows = 2,
        )
        tracker.sample(waiting, nowMs = 0L)
        val beforeCompletion = tracker.sample(waiting, nowMs = 700L).progress
        val afterCompletion = tracker.sample(
            observation = waiting.copy(
                completedWorkUnits = 1,
                readyWindows = 1,
                pendingWindows = 1,
            ),
            nowMs = 800L,
        ).progress
        val afterMoreTime = tracker.sample(
            observation = waiting.copy(
                completedWorkUnits = 1,
                readyWindows = 1,
                pendingWindows = 1,
            ),
            nowMs = 900L,
        ).progress

        assertTrue(afterCompletion >= beforeCompletion)
        assertTrue(afterMoreTime > afterCompletion)
    }

    @Test
    fun `temporary missing worker snapshot preserves session progress`() {
        val tracker = SourceSeparationPlaybackProcessingProgressTracker(startedAtMs = 0L)
        val running = observation(
            hasScheduler = true,
            completedWorkUnits = 0,
            targetWindows = 2,
            pendingWindows = 2,
        )
        tracker.sample(running, nowMs = 0L)
        val beforeMissingSnapshot = tracker.sample(running, nowMs = 700L).progress
        val duringMissingSnapshot = tracker.sample(null, nowMs = 1_000L).progress

        assertTrue(duringMissingSnapshot >= beforeMissingSnapshot)
    }

    @Test
    fun `view model coordinator preserves progress across a transient hidden state`() {
        val coordinator = SourceSeparationPlaybackProcessingProgressCoordinator()
        val running = SourceSeparationUiState.Running(
            songId = 42L,
            songTitle = "Song",
            sourceDecodeMode = SourceSeparationDecodeModeUiState.Window,
            averageWindowMs = 1_000L,
            scheduler = SourceSeparationSchedulerUiState(
                playbackSegmentIndex = 4,
                playbackSegmentState = "Missing",
                nextSegmentIndex = 5,
                nextSegmentState = "Missing",
                processingSegmentIndex = 4,
                priority = "PlaybackCritical",
                readySegments = 0,
                totalSegments = 20,
                readyWindowCount = 2,
                playbackReadyWindowReadyCount = 0,
                playbackReadyWindowPendingCount = 2,
            ),
        )
        val processing = SourceSeparationPlaybackUiState(
            processing = true,
            processingGeneration = 1L,
            songId = 42L,
        )
        coordinator.sample(processing, running, currentSongId = 42L, nowMs = 0L)
        val beforeHidden = requireNotNull(
            coordinator.sample(processing, running, currentSongId = 42L, nowMs = 500L)
        ).progress

        assertTrue(
            coordinator.sample(
                playbackState = processing.copy(processing = false),
                separationState = running,
                currentSongId = 42L,
                nowMs = 550L,
            ) == null
        )
        val afterVisible = requireNotNull(
            coordinator.sample(processing, running, currentSongId = 42L, nowMs = 600L)
        ).progress

        assertTrue(afterVisible >= beforeHidden)
    }

    @Test
    fun `a later wait starts from zero after playback became ready`() {
        val coordinator = SourceSeparationPlaybackProcessingProgressCoordinator()
        val running = runningState()
        val waiting = SourceSeparationPlaybackUiState(
            enabled = false,
            processing = true,
            processingGeneration = 1L,
            songId = 42L,
        )
        coordinator.sample(waiting, running, currentSongId = 42L, nowMs = 0L)
        val initialWaitProgress = requireNotNull(
            coordinator.sample(waiting, running, currentSongId = 42L, nowMs = 500L)
        ).progress

        coordinator.sample(
            playbackState = waiting.copy(enabled = true, processing = false),
            separationState = running,
            currentSongId = 42L,
            nowMs = 550L,
        )
        val laterWaitProgress = requireNotNull(
            coordinator.sample(
                playbackState = waiting.copy(enabled = true),
                separationState = running.copy(
                    scheduler = running.scheduler?.copy(
                        playbackSegmentIndex = 8,
                        processingSegmentIndex = 8,
                    ),
                ),
                currentSongId = 42L,
                nowMs = 600L,
            )
        ).progress

        assertTrue(initialWaitProgress > 0f)
        assertTrue(laterWaitProgress == 0f)
    }

    @Test
    fun `ready playback exposes a two hundred millisecond completion tail`() {
        val coordinator = SourceSeparationPlaybackProcessingProgressCoordinator()
        val running = runningState()
        val waiting = SourceSeparationPlaybackUiState(
            processing = true,
            processingGeneration = 1L,
            songId = 42L,
        )
        coordinator.sample(waiting, running, currentSongId = 42L, nowMs = 0L)
        coordinator.sample(waiting, running, currentSongId = 42L, nowMs = 500L)
        val ready = waiting.copy(enabled = true, processing = false)

        val tailStart = requireNotNull(
            coordinator.sample(ready, running, currentSongId = 42L, nowMs = 550L)
        )
        val tailBeforeDeadline = requireNotNull(
            coordinator.sample(ready, running, currentSongId = 42L, nowMs = 749L)
        )
        val afterDeadline = coordinator.sample(
            ready,
            running,
            currentSongId = 42L,
            nowMs = 750L,
        )

        assertTrue(tailStart.isCompletionTail)
        assertTrue(tailStart.progress == 1f)
        assertTrue(tailBeforeDeadline.isCompletionTail)
        assertTrue(afterDeadline == null)
    }

    @Test
    fun `new wait immediately replaces an active completion tail`() {
        val coordinator = SourceSeparationPlaybackProcessingProgressCoordinator()
        val running = runningState()
        val waiting = SourceSeparationPlaybackUiState(
            processing = true,
            processingGeneration = 1L,
            songId = 42L,
        )
        coordinator.sample(waiting, running, currentSongId = 42L, nowMs = 0L)
        coordinator.sample(waiting, running, currentSongId = 42L, nowMs = 500L)
        val ready = waiting.copy(enabled = true, processing = false)
        val tail = coordinator.sample(
            ready,
            running,
            currentSongId = 42L,
            nowMs = 550L,
        )

        val newWait = requireNotNull(
            coordinator.sample(
                playbackState = waiting.copy(enabled = true),
                separationState = running.copy(
                    scheduler = running.scheduler?.copy(
                        playbackSegmentIndex = 8,
                        processingSegmentIndex = 8,
                    ),
                ),
                currentSongId = 42L,
                nowMs = 600L,
            )
        )

        assertTrue(tail?.isCompletionTail == true)
        assertFalse(newWait.isCompletionTail)
        assertTrue(newWait.progress == 0f)
    }

    @Test
    fun `an unavailable transient state does not restart the current wait`() {
        val coordinator = SourceSeparationPlaybackProcessingProgressCoordinator()
        val running = runningState()
        val waiting = SourceSeparationPlaybackUiState(
            processing = true,
            processingGeneration = 1L,
            songId = 42L,
        )
        coordinator.sample(waiting, running, currentSongId = 42L, nowMs = 0L)
        val beforeTransient = requireNotNull(
            coordinator.sample(waiting, running, currentSongId = 42L, nowMs = 500L)
        ).progress

        coordinator.sample(
            playbackState = waiting.copy(processing = false),
            separationState = running,
            currentSongId = 42L,
            nowMs = 550L,
        )
        val afterTransient = requireNotNull(
            coordinator.sample(waiting, running, currentSongId = 42L, nowMs = 600L)
        ).progress

        assertTrue(afterTransient >= beforeTransient)
    }

    @Test
    fun `unchanged worker snapshot is ignored across a new generation`() {
        val guard = SourceSeparationPlaybackProcessingProgressGenerationGuard()
        val staleSnapshot = listOf<Any?>(42L, "Preparing")

        assertTrue(
            guard.accepts(
                processingGeneration = 1L,
                snapshotKey = staleSnapshot,
            )
        )
        assertTrue(
            guard.accepts(
                processingGeneration = 1L,
                snapshotKey = staleSnapshot,
            )
        )
        assertFalse(
            guard.accepts(
                processingGeneration = 2L,
                snapshotKey = staleSnapshot,
            )
        )
        assertTrue(
            guard.accepts(
                processingGeneration = 2L,
                snapshotKey = listOf(42L, "Processed"),
            )
        )
    }

    private fun observation(
        hasScheduler: Boolean,
        completedWorkUnits: Int,
        targetWindows: Int,
        pendingWindows: Int,
        readyWindows: Int = completedWorkUnits,
    ) = SourceSeparationPlaybackProcessingProgressObservation(
        schedulerPlaybackSegmentIndex = if (hasScheduler) 4 else null,
        hasScheduler = hasScheduler,
        hasUsableProgressSource = true,
        completeSnapshot = false,
        completedWorkUnits = completedWorkUnits,
        readyWindows = readyWindows,
        targetWindows = targetWindows,
        pendingWindows = pendingWindows,
        estimatedWindowMs = 1_000L,
        initialProcessingLabel = "Processing",
    )

    private fun runningState() = SourceSeparationUiState.Running(
        songId = 42L,
        songTitle = "Song",
        sourceDecodeMode = SourceSeparationDecodeModeUiState.Window,
        averageWindowMs = 1_000L,
        scheduler = SourceSeparationSchedulerUiState(
            playbackSegmentIndex = 4,
            playbackSegmentState = "Missing",
            nextSegmentIndex = 5,
            nextSegmentState = "Missing",
            processingSegmentIndex = 4,
            priority = "PlaybackCritical",
            readySegments = 0,
            totalSegments = 20,
            readyWindowCount = 2,
            playbackReadyWindowReadyCount = 0,
            playbackReadyWindowPendingCount = 2,
        ),
    )
}
