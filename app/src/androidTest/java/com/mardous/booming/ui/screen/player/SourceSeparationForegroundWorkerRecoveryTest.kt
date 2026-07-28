package com.mardous.booming.ui.screen.player

import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mardous.booming.data.model.Song
import com.mardous.booming.separation.SourceSeparationBackgroundPolicy
import com.mardous.booming.separation.SourceSeparationExecutionRunClass
import com.mardous.booming.separation.SourceSeparationRuntimeFacade
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheContractSnapshot
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunJournal
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunJournalLifecycle
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunJournalRequest
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunJournalTransition
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunTransitionType
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheSongLocator
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheSourceDiagnostics
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheSourceIdentity
import com.mardous.booming.separation.model.contract.SourceSeparationModelMetadata
import com.mardous.booming.separation.process.SourceSeparationExecutionHostControlResult
import com.mardous.booming.separation.process.SourceSeparationExecutionHostEvent
import com.mardous.booming.separation.process.SourceSeparationExecutionHostEventPayload
import com.mardous.booming.separation.process.SourceSeparationExecutionProgress
import com.mardous.booming.separation.process.ipc.SourceSeparationIndependentRunRecovery
import com.mardous.booming.separation.process.ipc.SourceSeparationReconnectedSession
import com.mardous.booming.util.SOURCE_SEPARATION_AUTO_CACHE_CLEANUP
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourceSeparationForegroundWorkerRecoveryTest {
    @Test
    fun reconnectedRunGatesNewWorkerAndDefersTerminalCallback() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences(
            "source-separation-recovery-test",
            Context.MODE_PRIVATE,
        )
        preferences.edit()
            .clear()
            .putBoolean(SOURCE_SEPARATION_AUTO_CACHE_CLEANUP, false)
            .commit()
        val journal = journal(context)
        val session = FakeReconnectedSession(journal)
        val recovery = BlockingRecovery(session)
        val runtimeCalls = AtomicInteger(0)
        val runtime = Proxy.newProxyInstance(
            SourceSeparationRuntimeFacade::class.java.classLoader,
            arrayOf(SourceSeparationRuntimeFacade::class.java),
        ) { _, method, _ ->
            runtimeCalls.incrementAndGet()
            throw AssertionError("Unexpected runtime call: ${method.name}")
        } as SourceSeparationRuntimeFacade
        val coordinator = SourceSeparationForegroundWorkerCoordinator(
            context = context,
            preferences = preferences,
            sourceSeparationRuntime = runtime,
            independentRunRecovery = recovery,
        )

        assertTrue(recovery.entered.await(5L, TimeUnit.SECONDS))
        val song = recoveredSong(journal)
        coordinator.updateSong(
            song = song,
            positionMs = 0L,
            durationMs = song.duration,
            isPlaying = false,
            sourceSeparationBlend = 0.5f,
        )
        assertTrue(coordinator.startCurrentSong())
        recovery.release.countDown()

        awaitCondition {
            (coordinator.workerStateFlow.value as? SourceSeparationUiState.Running)
                ?.completedWindows == 2
        }
        assertEquals(song.id, coordinator.runningSongId())
        assertEquals(journal.request.cacheKey, coordinator.runningCacheKey())
        assertEquals(setOf(journal.request.cacheKey), coordinator.protectedCacheKeys())
        assertEquals(0, runtimeCalls.get())

        coordinator.pauseCurrentSong(song)
        awaitCondition { session.pauseCount.get() == 1 }
        recovery.emit(
            SourceSeparationExecutionHostEventPayload.Paused("user-request"),
            sequence = 3L,
        )
        awaitCondition { !coordinator.isWorkerActive() }

        val callbacks = RecordingCallbacks()
        coordinator.attachCallbacks(callbacks)
        assertEquals(1, callbacks.pausedCount.get())
        assertEquals(1, session.closeTerminalCount.get())
        assertEquals(1, session.closeCount.get())
        assertEquals(0, runtimeCalls.get())
        assertFalse(coordinator.protectedCacheKeys().contains(journal.request.cacheKey))
        preferences.edit().clear().commit()
    }

    private fun journal(context: Context): SourceSeparationCacheRunJournal {
        val catalog = SourceSeparationModelMetadata.loadBundledCatalog(context)
        val contract = SourceSeparationCacheContractSnapshot.fromOfficial(
            catalog.contracts.single { it.modelId == "uvr_mdxnet_3_9662" },
        )
        val identity = contract.identity(
            SourceSeparationCacheSourceIdentity(
                audioFingerprint = "encoded-samples-v1:${"a".repeat(64)}",
                encodedSampleCount = 44_100L,
                encodedByteCount = 352_800L,
                mimeType = "audio/wav",
                sourceSampleRate = 44_100,
                sourceChannelCount = 2,
                sourceDurationUs = 1_000_000L,
            )
        )
        val runId = "recovery-test-run"
        val runClass = SourceSeparationExecutionRunClass.ManualFullSong
        val request = SourceSeparationCacheRunJournalRequest(
            cacheKey = identity.cacheKey,
            identity = identity,
            contract = contract,
            song = SourceSeparationCacheSongLocator(
                songId = 42L,
                mediaUri = "content://media/external/audio/media/42",
                filePath = "/music/recovered.wav",
                title = "Recovered song",
                artist = "Artist",
                album = "Album",
            ),
            sourceDiagnostics = SourceSeparationCacheSourceDiagnostics(
                fileSize = 352_800L,
                rawDateModified = 1L,
                durationMs = 1_000L,
            ),
            runId = runId,
            processGeneration = 7L,
            ownerPid = 10,
            runClass = runClass,
            backgroundPolicy = SourceSeparationBackgroundPolicy.IndependentForegroundEligible,
            tryGpu = false,
            gpuRuntimeIdentity = null,
            gpuFallbackLatch = null,
            admittedAtEpochMs = 1L,
        )
        return SourceSeparationCacheRunJournal(
            request = request,
            lifecycle = SourceSeparationCacheRunJournalLifecycle.Running,
            transitions = listOf(
                SourceSeparationCacheRunJournalTransition(
                    sequence = 1L,
                    runId = runId,
                    processGeneration = 7L,
                    ownerPid = 10,
                    runClass = runClass,
                    backgroundPolicy = request.backgroundPolicy,
                    type = SourceSeparationCacheRunTransitionType.Admitted,
                    timestampEpochMs = 1L,
                )
            ),
            updatedAtEpochMs = 1L,
        )
    }

    private fun recoveredSong(journal: SourceSeparationCacheRunJournal): Song {
        val locator = journal.request.song
        val diagnostics = journal.request.sourceDiagnostics
        return Song(
            id = locator.songId,
            data = locator.filePath,
            title = locator.title,
            trackNumber = 0,
            year = 0,
            size = diagnostics.fileSize,
            duration = diagnostics.durationMs,
            dateAdded = 0L,
            rawDateModified = diagnostics.rawDateModified,
            albumId = 0L,
            albumName = locator.album,
            artistId = 0L,
            artistName = locator.artist,
            albumArtistName = null,
            genreName = null,
        )
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(20L)
        }
        assertTrue("Condition was not met before timeout.", condition())
    }

    private class BlockingRecovery(
        private val session: FakeReconnectedSession,
    ) : SourceSeparationIndependentRunRecovery {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        @Volatile
        private var onEvent: ((SourceSeparationExecutionHostEvent) -> Unit)? = null

        override fun reconnect(
            onEvent: (SourceSeparationExecutionHostEvent) -> Unit,
        ): SourceSeparationReconnectedSession {
            this.onEvent = onEvent
            entered.countDown()
            assertTrue(release.await(5L, TimeUnit.SECONDS))
            return session
        }

        fun emit(payload: SourceSeparationExecutionHostEventPayload, sequence: Long) {
            requireNotNull(onEvent).invoke(
                SourceSeparationExecutionHostEvent(
                    runId = session.runId,
                    processGeneration = session.processGeneration,
                    sequence = sequence,
                    payload = payload,
                )
            )
        }
    }

    private class FakeReconnectedSession(
        override val journal: SourceSeparationCacheRunJournal,
    ) : SourceSeparationReconnectedSession {
        val pauseCount = AtomicInteger(0)
        val closeTerminalCount = AtomicInteger(0)
        val closeCount = AtomicInteger(0)

        override val runId: String = journal.request.runId
        override val processGeneration: Long = journal.request.processGeneration
        override val cacheKey: String = journal.request.cacheKey
        override val baselineEvent = SourceSeparationExecutionHostEvent(
            runId = runId,
            processGeneration = processGeneration,
            sequence = 2L,
            payload = SourceSeparationExecutionHostEventPayload.Progress(
                SourceSeparationExecutionProgress(
                    completedWindows = 2,
                    totalWindows = 10,
                    stage = "Recovered",
                    sourceDecodeDiagnostics = null,
                    completedWindowElapsedMs = null,
                    scheduler = null,
                )
            ),
        )

        override fun pause(): SourceSeparationExecutionHostControlResult {
            pauseCount.incrementAndGet()
            return SourceSeparationExecutionHostControlResult.Applied
        }

        override fun cancel(): SourceSeparationExecutionHostControlResult =
            SourceSeparationExecutionHostControlResult.Applied

        override fun closeTerminal(): SourceSeparationExecutionHostControlResult {
            closeTerminalCount.incrementAndGet()
            close()
            return SourceSeparationExecutionHostControlResult.Applied
        }

        override fun close() {
            closeCount.incrementAndGet()
        }
    }

    private class RecordingCallbacks : SourceSeparationForegroundWorkerCallbacks {
        val pausedCount = AtomicInteger(0)

        override fun onSourceSeparationWorkerProgress(song: Song) = Unit

        override fun onSourceSeparationWorkerPrepared(song: Song) = Unit

        override fun onSourceSeparationWorkerCompleted(
            song: Song,
            cacheKey: String,
            shouldPromoteCompletedStems: Boolean,
        ) = Unit

        override fun onSourceSeparationWorkerPaused(song: Song) {
            pausedCount.incrementAndGet()
        }

        override fun onSourceSeparationWorkerModelLoadFailed(message: String) = Unit
    }
}
