package com.mardous.booming.separation

import android.content.ContentUris
import android.content.ContentValues
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mardous.booming.data.model.Song
import com.mardous.booming.playback.Playback
import com.mardous.booming.playback.PlaybackService
import com.mardous.booming.playback.processor.SourceSeparationMixAudioProcessor
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheModelAvailability
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheFlacPromoter
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheFlacPromotionResult
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheMutationResult
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunCoordinator
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunJournalLifecycle
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunTransitionType
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheManifestState
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheStore
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheValidationResult
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwareCacheRepository
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwarePlayableStatus
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwareCacheEntryState
import com.mardous.booming.separation.process.ipc.BoundRemoteSourceSeparationMultiStemExecutionHost
import com.mardous.booming.separation.SourceSeparationMultiStemPlaybackSelectionStore
import com.mardous.booming.separation.runtime.SourceSeparationRuntimeState
import com.mardous.booming.separation.runtime.SourceSeparationRuntimeStore
import com.mardous.booming.util.IGNORE_AUDIO_FOCUS
import com.mardous.booming.util.MINIMUM_SONG_DURATION
import com.mardous.booming.util.SOURCE_SEPARATION_AUTO_START
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

@RunWith(AndroidJUnit4::class)
class SourceSeparationMultiStemProductExecutionDeviceTest {
    @Test
    fun completedProductCachePlaysThroughMediaSession() {
        val arguments = InstrumentationRegistry.getArguments()
        val modelId = arguments.getString(ARG_MODEL_ID).orEmpty()
        val relativeSource = arguments.getString(ARG_SOURCE_PATH).orEmpty()
        assumeTrue(modelId.isNotBlank() && relativeSource.isNotBlank())
        require(modelId in EXPECTED_MODEL_IDS && SAFE_RELATIVE_PATH.matches(relativeSource))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.filesDir, relativeSource).canonicalFile
        require(source.toPath().startsWith(context.filesDir.canonicalFile.toPath()) && source.isFile)
        arguments.getString(ARG_SOURCE_SHA256)?.let { expected ->
            require(SHA256.matches(expected) && source.sha256() == expected)
        }
        val runId = arguments.getString(ARG_RUN_ID)?.takeIf(SAFE_NAME::matches)
            ?: "playback-$modelId-${System.currentTimeMillis()}"
        val koin = GlobalContext.get()
        val facade = koin.get<SourceSeparationMultiStemProductFacade>()
        val repository = koin.get<SourceSeparationModelAwareCacheRepository>()
        val store = koin.get<SourceSeparationCacheStore>()
        val promoter = koin.get<SourceSeparationCacheFlacPromoter>()
        val selectionStore = koin.get<SourceSeparationMultiStemPlaybackSelectionStore>()
        val processor = koin.get<SourceSeparationMixAudioProcessor>()
        val preferences = koin.get<SharedPreferences>()
        val initialSelectedModelId = selectionStore.selectedModelId()
        val preferenceSnapshot = snapshotPreferences(
            preferences,
            setOf(MINIMUM_SONG_DURATION, SOURCE_SEPARATION_AUTO_START, IGNORE_AUDIO_FOCUS),
        )
        val reportFile = File(context.filesDir, REPORT_DIRECTORY).apply { mkdirs() }
            .resolve("$runId.json")
        val report = JSONObject()
            .put("runId", runId)
            .put("mode", "playback-service")
            .put("modelId", modelId)
            .put("status", "running")
            .put("deviceModel", android.os.Build.MODEL)
            .put("sdk", android.os.Build.VERSION.SDK_INT)
            .put("abi", android.os.Build.SUPPORTED_ABIS.first())
        var mediaUri: Uri? = null
        var cacheKey: String? = null
        var controller: MediaController? = null
        try {
            check(preferences.edit()
                .putInt(MINIMUM_SONG_DURATION, 0)
                .putBoolean(SOURCE_SEPARATION_AUTO_START, false)
                .putBoolean(IGNORE_AUDIO_FOCUS, true)
                .commit())
            mediaUri = importIntoMediaStore(context, source, runId)
            val song = stagedSong(mediaUri, source, runId)
            repository.entries()
                .filter { it.modelId == modelId && it.title.startsWith(SONG_TITLE_PREFIX) }
                .forEach { repository.delete(it.cacheKey) }

            val separated = facade.separate(song = song, modelId = modelId)
            val manifest = when (separated) {
                is HtdemucsSourceSeparationEngineResult.Completed -> separated.manifest
                is HtdemucsSourceSeparationEngineResult.AlreadyCompleted -> separated.manifest
                is HtdemucsSourceSeparationEngineResult.Busy -> error("Unexpected busy result")
            }
            cacheKey = manifest.cacheKey
            val promoted = promoter.promote(manifest.cacheKey)
            val playbackManifest = when (promoted) {
                is SourceSeparationCacheFlacPromotionResult.Completed -> promoted.manifest
                is SourceSeparationCacheFlacPromotionResult.AlreadyPromoted -> promoted.manifest
                is SourceSeparationCacheFlacPromotionResult.Busy -> error("Unexpected promotion contention")
                SourceSeparationCacheFlacPromotionResult.Unavailable ->
                    error("FLAC promotion was unavailable")
            }
            val output = requireNotNull(playbackManifest.output)
            val expectedStemIds = output.stems.sortedBy { stem -> stem.order }
                .map { stem -> stem.stemId.value }
            assertTrue(expectedStemIds.size == 4 || expectedStemIds.size == 6)
            selectionStore.select(modelId)

            val mediaController = MediaController.Builder(
                context,
                SessionToken(context, ComponentName(context, PlaybackService::class.java)),
            ).buildAsync().get(MEDIA_SESSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            controller = mediaController
            onMediaControllerThread(mediaController) {
                mediaController.volume = 0f
                mediaController.setMediaItem(
                    MediaItem.Builder()
                        .setMediaId(song.id.toString())
                        .setUri(song.uri)
                        .build(),
                )
                mediaController.prepare()
            }
            waitForMediaController(mediaController, "source preparation") {
                mediaController.currentMediaItem?.mediaId == song.id.toString() &&
                    mediaController.duration > 0L
            }
            val enableResult = onMediaControllerThread(mediaController) {
                mediaController.sendCustomCommand(
                    SessionCommand(Playback.SET_SOURCE_SEPARATION_PLAYBACK_ENABLED, Bundle.EMPTY),
                    Bundle().apply {
                        putBoolean(Playback.EXTRA_SOURCE_SEPARATION_ENABLED, true)
                        putBoolean(Playback.EXTRA_SOURCE_SEPARATION_SHOW_MESSAGE, false)
                        putFloat(Playback.EXTRA_SOURCE_SEPARATION_BLEND, 0.7f)
                    },
                )
            }.get(MEDIA_SESSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            report.put("enableResult", JSONObject()
                .put("code", enableResult.resultCode)
                .put("enabled", enableResult.extras.getBoolean(
                    Playback.EXTRA_SOURCE_SEPARATION_ENABLED,
                ))
                .put("processing", enableResult.extras.getBoolean(
                    Playback.EXTRA_SOURCE_SEPARATION_PROCESSING,
                ))
                .put("message", enableResult.extras.getString(
                    Playback.EXTRA_SOURCE_SEPARATION_MESSAGE,
                )))
            val playbackResolution = koin.get<SourceSeparationRuntimeFacade>()
                .resolveForPlayback(song)
            report.put("playbackResolution", when (playbackResolution) {
                is SourceSeparationRuntimeSongResolution.Ready -> {
                    val directStatus = koin.get<SourceSeparationRuntimeFacade>().playableStatus(
                        playbackResolution.song,
                        playbackPositionMs = 0L,
                        readyWindowCount = 2,
                    )
                    JSONObject()
                        .put("cacheKey", playbackResolution.song.cacheKey)
                        .put("modelId", playbackResolution.song.modelId)
                        .put("status", directStatus.javaClass.simpleName)
                        .put("manifestState", store.readManifest(playbackResolution.song.cacheKey)?.state?.name)
                        .put("validationWithoutHashes", store.readManifest(playbackResolution.song.cacheKey)?.let {
                            store.validateCompletedEntry(it, verifyHashes = false).toString()
                        })
                        .put("entry", repository.entries().singleOrNull {
                            it.cacheKey == playbackResolution.song.cacheKey
                        }?.let { entry ->
                            JSONObject()
                                .put("modelAvailability", entry.modelAvailability.name)
                                .put("state", entry.state.name)
                                .put("stemCount", entry.stemLabels.size)
                        })
                        .put("openCompleted", repository.openCompletedCache(
                            playbackResolution.song.cacheKey,
                        ) != null)
                        .also { directStatus.closePlaybackForTest() }
                }
                is SourceSeparationRuntimeSongResolution.Unavailable -> JSONObject()
                    .put("reason", playbackResolution.reason.name)
                    .put("detail", playbackResolution.detail)
            })
            check(enableResult.resultCode == SessionResult.RESULT_SUCCESS) {
                "PlaybackService rejected multi-stem cache: " +
                    enableResult.extras.getString(Playback.EXTRA_SOURCE_SEPARATION_MESSAGE).orEmpty()
            }
            onMediaControllerThread(mediaController) { mediaController.play() }
            val syncResult = onMediaControllerThread(mediaController) {
                mediaController.sendCustomCommand(
                    SessionCommand(Playback.SYNC_SOURCE_SEPARATION_PLAYBACK, Bundle.EMPTY),
                    Bundle().apply {
                        putBoolean(Playback.EXTRA_SOURCE_SEPARATION_ALLOW_NEW_SESSION, true)
                        putBoolean(Playback.EXTRA_SOURCE_SEPARATION_PREFER_COMPLETED_CACHE, true)
                    },
                )
            }.get(MEDIA_SESSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            report.put("syncResult", JSONObject()
                .put("code", syncResult.resultCode)
                .put("message", syncResult.extras.getString(
                    Playback.EXTRA_SOURCE_SEPARATION_MESSAGE,
                )))
            check(syncResult.resultCode == SessionResult.RESULT_SUCCESS) {
                "PlaybackService could not synchronize the multi-stem cache: " +
                    syncResult.extras.getString(Playback.EXTRA_SOURCE_SEPARATION_MESSAGE).orEmpty()
            }
            waitForMediaController(mediaController, "multi-stem playback adoption") {
                mediaController.playWhenReady &&
                    mediaController.playbackState == Player.STATE_READY &&
                    processor.dataPlaneStemIds() == expectedStemIds
            }
            val metricsBeforeSeek = requireNotNull(processor.dataPlaneMetrics())
            assertEquals(expectedStemIds.size, metricsBeforeSeek.activeStemCount)

            onMediaControllerThread(mediaController) { mediaController.pause() }
            waitForMediaController(mediaController, "pause") { !mediaController.playWhenReady }
            val seekPositionMs = onMediaControllerThread(mediaController) {
                (mediaController.duration / 3L).coerceAtLeast(1_000L)
            }
            onMediaControllerThread(mediaController) { mediaController.seekTo(seekPositionMs) }
            waitForMediaController(mediaController, "seek") {
                kotlin.math.abs(mediaController.currentPosition - seekPositionMs) <=
                    MEDIA_SESSION_SEEK_TOLERANCE_MS
            }
            onMediaControllerThread(mediaController) { mediaController.play() }
            waitForMediaController(mediaController, "resume after seek") {
                mediaController.playWhenReady &&
                    mediaController.playbackState == Player.STATE_READY &&
                    processor.dataPlaneStemIds() == expectedStemIds
            }
            val metricsAfterSeek = requireNotNull(processor.dataPlaneMetrics())
            assertTrue(metricsAfterSeek.seekRequests > metricsBeforeSeek.seekRequests)
            assertEquals(metricsBeforeSeek.underruns, metricsAfterSeek.underruns)

            report.put("status", "complete")
                .put("cacheKey", manifest.cacheKey)
                .put("artifactSha256", manifest.identity.artifactSha256)
                .put("stemIds", JSONArray(expectedStemIds))
                .put("activeStemCount", metricsAfterSeek.activeStemCount)
                .put("seekRequests", metricsAfterSeek.seekRequests)
                .put("underrunsBeforeSeek", metricsBeforeSeek.underruns)
                .put("underruns", metricsAfterSeek.underruns)
                .put("transportMediaId", song.id)
                .put("transportUri", song.uri)
            reportFile.writeText(report.toString(2))
        } catch (error: Throwable) {
            report.put("status", "error")
                .put("errorType", error.javaClass.name)
                .put("errorMessage", error.message.orEmpty())
                .put("stackTrace", error.stackTraceToString())
            controller?.let { mediaController ->
                report.put("controllerState", onMediaControllerThread(mediaController) {
                    JSONObject()
                        .put("mediaId", mediaController.currentMediaItem?.mediaId)
                        .put("duration", mediaController.duration)
                        .put("playbackState", mediaController.playbackState)
                        .put("isPlaying", mediaController.isPlaying)
                        .put("playWhenReady", mediaController.playWhenReady)
                        .put("playerError", mediaController.playerError?.message)
                })
            }
            report.put("processorStemIds", processor.dataPlaneStemIds())
            reportFile.writeText(report.toString(2))
            throw error
        } finally {
            controller?.let { mediaController ->
                runCatching {
                    onMediaControllerThread(mediaController) {
                        mediaController.sendCustomCommand(
                            SessionCommand(
                                Playback.SET_SOURCE_SEPARATION_PLAYBACK_ENABLED,
                                Bundle.EMPTY,
                            ),
                            Bundle().apply {
                                putBoolean(Playback.EXTRA_SOURCE_SEPARATION_ENABLED, false)
                                putBoolean(Playback.EXTRA_SOURCE_SEPARATION_SHOW_MESSAGE, false)
                            },
                        ).get(MEDIA_SESSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        mediaController.pause()
                        mediaController.clearMediaItems()
                        mediaController.release()
                    }
                }
            }
            runCatching { context.stopService(Intent(context, PlaybackService::class.java)) }
            selectionStore.select(initialSelectedModelId)
            restorePreferences(preferences, preferenceSnapshot)
            cacheKey?.let { key ->
                repeat(20) {
                    if (!repository.isLeased(key)) return@repeat
                    SystemClock.sleep(100L)
                }
                runCatching { repository.delete(key) }
            }
            mediaUri?.let { uri -> runCatching { context.contentResolver.delete(uri, null, null) } }
        }
    }

    @Test
    fun activeModelSupersessionRetainsOldPartialAndCompletesReplacement() {
        val arguments = InstrumentationRegistry.getArguments()
        val modelId = arguments.getString(ARG_MODEL_ID).orEmpty()
        val replacementModelId = arguments.getString(ARG_REPLACEMENT_MODEL_ID).orEmpty()
        val relativeSource = arguments.getString(ARG_SOURCE_PATH).orEmpty()
        assumeTrue(modelId.isNotBlank() && replacementModelId.isNotBlank() &&
            relativeSource.isNotBlank())
        require(modelId in EXPECTED_MODEL_IDS && replacementModelId in EXPECTED_MODEL_IDS &&
            modelId != replacementModelId && SAFE_RELATIVE_PATH.matches(relativeSource))
        val runId = requireNotNull(arguments.getString(ARG_RUN_ID)).also {
            require(SAFE_NAME.matches(it))
        }
        val appCommit = arguments.getString(ARG_APP_COMMIT).orEmpty()
        val testCommit = arguments.getString(ARG_TEST_COMMIT).orEmpty()
        require(SHA1.matches(appCommit) && SHA1.matches(testCommit))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.filesDir, relativeSource).canonicalFile
        require(source.toPath().startsWith(context.filesDir.canonicalFile.toPath()) && source.isFile)
        arguments.getString(ARG_SOURCE_SHA256)?.let { expected ->
            require(SHA256.matches(expected) && source.sha256() == expected)
        }
        val reportFile = File(context.filesDir, REPORT_DIRECTORY).apply { mkdirs() }
            .resolve("$runId.json")
        var mediaUri: Uri? = null
        val report = JSONObject()
            .put("runId", runId)
            .put("mode", "active-model-supersession")
            .put("modelId", modelId)
            .put("replacementModelId", replacementModelId)
            .put("status", "running")
            .put("deviceModel", android.os.Build.MODEL)
            .put("sdk", android.os.Build.VERSION.SDK_INT)
            .put("abi", android.os.Build.SUPPORTED_ABIS.first())
        try {
            mediaUri = importIntoMediaStore(context, source, runId)
            val song = stagedSong(mediaUri, source, runId)
            val koin = GlobalContext.get()
            val facade = koin.get<SourceSeparationMultiStemProductFacade>()
            val repository = koin.get<SourceSeparationModelAwareCacheRepository>()
            val store = koin.get<SourceSeparationCacheStore>()
            repository.entries()
                .filter { it.modelId in setOf(modelId, replacementModelId) &&
                    it.title.startsWith(SONG_TITLE_PREFIX)
                }
                .forEach { repository.delete(it.cacheKey) }

            val pause = AtomicBoolean(false)
            val pauseStartedAt = SystemClock.elapsedRealtime()
            val paused = try {
                facade.separate(
                    song = song,
                    modelId = modelId,
                    onProgress = { progress ->
                        if (progress.completedWindows >= 1) pause.set(true)
                    },
                    shouldPause = pause::get,
                    pauseReasonProvider = { SourceSeparationPauseReason.ActiveModelSuperseded },
                )
                null
            } catch (error: SourceSeparationPausedException) {
                error
            }
            requireNotNull(paused)
            assertEquals(SourceSeparationPauseReason.ActiveModelSuperseded, paused.pauseReason)
            val pauseElapsedMs = SystemClock.elapsedRealtime() - pauseStartedAt
            val oldEntry = repository.entries().single {
                it.modelId == modelId && it.title == song.title
            }
            val oldManifest = requireNotNull(store.readManifest(oldEntry.cacheKey))
            val oldPlan = requireNotNull(oldManifest.segmentPlan)
            val oldReady = oldPlan.segments.count { it.state.isPlaybackReady }
            assertEquals(1, oldReady)
            val oldJournal = requireNotNull(store.readRunJournal(oldEntry.cacheKey))
            assertEquals(SourceSeparationCacheRunJournalLifecycle.Paused, oldJournal.lifecycle)
            assertEquals(
                SourceSeparationCacheRunTransitionType.ActiveModelSuperseded,
                oldJournal.transitions.last().type,
            )

            val replacementStartedAt = SystemClock.elapsedRealtime()
            val replacement = facade.separate(song, replacementModelId)
            val replacementElapsedMs = SystemClock.elapsedRealtime() - replacementStartedAt
            require(replacement is HtdemucsSourceSeparationEngineResult.Completed)
            assertTrue(replacement.manifest.cacheKey != oldManifest.cacheKey)
            assertEquals(replacementModelId, replacement.manifest.identity.modelId)
            assertTrue(store.validateCompletedEntry(replacement.manifest, verifyHashes = false) ==
                SourceSeparationCacheValidationResult.Valid)
            val retainedOld = requireNotNull(store.readManifest(oldManifest.cacheKey))
            assertEquals(SourceSeparationCacheManifestState.Partial, retainedOld.state)
            assertEquals(oldReady, retainedOld.segmentPlan?.segments?.count {
                it.state.isPlaybackReady
            })
            assertTrue(repository.openCompletedCache(oldManifest.cacheKey) == null)
            repository.openCompletedCache(replacement.manifest.cacheKey)!!.use { playback ->
                assertEquals(replacement.manifest.output?.stems?.size, playback.stems.size)
            }
            report.put("status", "complete")
                .put("build", JSONObject()
                    .put("appCommit", appCommit)
                    .put("testCommit", testCommit)
                    .put("appApkSha256", File(context.applicationInfo.sourceDir).sha256())
                    .put("testApkSha256", File(instrumentation.context.applicationInfo.sourceDir).sha256())
                )
                .put("oldCacheKey", oldManifest.cacheKey)
                .put("oldReadySegments", oldReady)
                .put("oldTotalSegments", oldPlan.segmentCount)
                .put("pauseElapsedMs", pauseElapsedMs)
                .put("replacementCacheKey", replacement.manifest.cacheKey)
                .put("replacementElapsedMs", replacementElapsedMs)
                .put("replacementStemCount", replacement.manifest.output?.stems?.size)
            reportFile.writeText(report.toString(2))
        } catch (error: Throwable) {
            report.put("status", "error")
                .put("errorType", error.javaClass.name)
                .put("errorMessage", error.message.orEmpty())
                .put("stackTrace", error.stackTraceToString())
            reportFile.writeText(report.toString(2))
            throw error
        } finally {
            mediaUri?.let { context.contentResolver.delete(it, null, null) }
        }
    }

    @Test
    fun cancelAfterFirstReadySegmentThenRestartCleanly() {
        val arguments = InstrumentationRegistry.getArguments()
        val modelId = arguments.getString(ARG_MODEL_ID).orEmpty()
        val relativeSource = arguments.getString(ARG_SOURCE_PATH).orEmpty()
        assumeTrue(modelId.isNotBlank() && relativeSource.isNotBlank())
        require(modelId in EXPECTED_MODEL_IDS && SAFE_RELATIVE_PATH.matches(relativeSource))
        val runId = requireNotNull(arguments.getString(ARG_RUN_ID)).also {
            require(SAFE_NAME.matches(it))
        }
        val appCommit = arguments.getString(ARG_APP_COMMIT).orEmpty()
        val testCommit = arguments.getString(ARG_TEST_COMMIT).orEmpty()
        require(SHA1.matches(appCommit) && SHA1.matches(testCommit))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.filesDir, relativeSource).canonicalFile
        require(source.toPath().startsWith(context.filesDir.canonicalFile.toPath()) && source.isFile)
        arguments.getString(ARG_SOURCE_SHA256)?.let { expected ->
            require(SHA256.matches(expected) && source.sha256() == expected)
        }
        val reportFile = File(context.filesDir, REPORT_DIRECTORY).apply { mkdirs() }
            .resolve("$runId.json")
        var mediaUri: Uri? = null
        val report = JSONObject()
            .put("runId", runId)
            .put("mode", "cancel-then-restart")
            .put("modelId", modelId)
            .put("status", "running")
            .put("deviceModel", android.os.Build.MODEL)
            .put("sdk", android.os.Build.VERSION.SDK_INT)
            .put("abi", android.os.Build.SUPPORTED_ABIS.first())
        try {
            mediaUri = importIntoMediaStore(context, source, runId)
            val song = stagedSong(mediaUri, source, runId)
            val koin = GlobalContext.get()
            val facade = koin.get<SourceSeparationMultiStemProductFacade>()
            val repository = koin.get<SourceSeparationModelAwareCacheRepository>()
            val store = koin.get<SourceSeparationCacheStore>()
            repository.entries()
                .filter { it.modelId == modelId && it.title.startsWith(SONG_TITLE_PREFIX) }
                .forEach { repository.delete(it.cacheKey) }

            val cancel = AtomicBoolean(false)
            val cancelStartedAt = SystemClock.elapsedRealtime()
            val canceled = try {
                facade.separate(
                    song = song,
                    modelId = modelId,
                    onProgress = { progress ->
                        if (progress.completedWindows >= 1) cancel.set(true)
                    },
                    shouldCancel = cancel::get,
                )
                null
            } catch (error: CancellationException) {
                error
            }
            requireNotNull(canceled) { "The multi-stem run ignored user cancellation." }
            val cancelElapsedMs = SystemClock.elapsedRealtime() - cancelStartedAt
            val canceledEntry = repository.entries().single {
                it.modelId == modelId && it.title == song.title
            }
            assertEquals(SourceSeparationModelAwareCacheEntryState.Canceled, canceledEntry.state)
            val partial = requireNotNull(store.readManifest(canceledEntry.cacheKey))
            assertEquals(SourceSeparationCacheManifestState.Partial, partial.state)
            val plan = requireNotNull(partial.segmentPlan)
            val readyBeforeRestart = plan.segments.count { it.state.isPlaybackReady }
            assertTrue(readyBeforeRestart in 1 until plan.segmentCount)
            assertTrue(plan.segments.none {
                it.state == com.mardous.booming.separation.cache.SourceSeparationSegmentState.Running
            })
            val canceledJournal = requireNotNull(store.readRunJournal(partial.cacheKey))
            assertEquals(SourceSeparationCacheRunJournalLifecycle.Canceled, canceledJournal.lifecycle)

            val restartStartedAt = SystemClock.elapsedRealtime()
            val restarted = facade.separate(song, modelId)
            val restartElapsedMs = SystemClock.elapsedRealtime() - restartStartedAt
            require(restarted is HtdemucsSourceSeparationEngineResult.Completed)
            assertEquals(partial.cacheKey, restarted.manifest.cacheKey)
            assertTrue(store.validateCompletedEntry(restarted.manifest, verifyHashes = false) ==
                SourceSeparationCacheValidationResult.Valid)
            assertEquals(
                SourceSeparationCacheRunJournalLifecycle.Completed,
                requireNotNull(store.readRunJournal(partial.cacheKey)).lifecycle,
            )
            report.put("status", "complete")
                .put("build", JSONObject()
                    .put("appCommit", appCommit)
                    .put("testCommit", testCommit)
                    .put("appApkSha256", File(context.applicationInfo.sourceDir).sha256())
                    .put("testApkSha256", File(instrumentation.context.applicationInfo.sourceDir).sha256())
                )
                .put("cacheKey", partial.cacheKey)
                .put("cancelElapsedMs", cancelElapsedMs)
                .put("readySegmentsBeforeRestart", readyBeforeRestart)
                .put("totalSegments", plan.segmentCount)
                .put("restartElapsedMs", restartElapsedMs)
                .put("completedStemCount", restarted.manifest.output?.stems?.size)
            reportFile.writeText(report.toString(2))
        } catch (error: Throwable) {
            report.put("status", "error")
                .put("errorType", error.javaClass.name)
                .put("errorMessage", error.message.orEmpty())
                .put("stackTrace", error.stackTraceToString())
            reportFile.writeText(report.toString(2))
            throw error
        } finally {
            mediaUri?.let { context.contentResolver.delete(it, null, null) }
        }
    }

    @Test
    fun separateStagedSongThroughInstalledReleaseAndProductCache() {
        val arguments = InstrumentationRegistry.getArguments()
        val modelId = arguments.getString(ARG_MODEL_ID).orEmpty()
        val relativeSource = arguments.getString(ARG_SOURCE_PATH).orEmpty()
        assumeTrue(
            "Pass -e $ARG_MODEL_ID <id> and -e $ARG_SOURCE_PATH <files-relative-path>.",
            modelId.isNotBlank() && relativeSource.isNotBlank(),
        )
        require(modelId in EXPECTED_MODEL_IDS)
        require(SAFE_RELATIVE_PATH.matches(relativeSource))
        val runId = arguments.getString(ARG_RUN_ID)
            ?.takeIf(SAFE_NAME::matches)
            ?: "${modelId}-${System.currentTimeMillis()}"
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val stagedSource = File(context.filesDir, relativeSource).canonicalFile
        require(stagedSource.toPath().startsWith(context.filesDir.canonicalFile.toPath()) &&
            stagedSource.isFile
        ) { "Staged product source is unavailable." }
        val expectedSourceSha = arguments.getString(ARG_SOURCE_SHA256)
            ?.lowercase()
            ?.also { require(SHA256.matches(it)) }
        expectedSourceSha?.let { require(stagedSource.sha256() == it) }
        val reportFile = File(context.filesDir, REPORT_DIRECTORY).apply { mkdirs() }
            .resolve("$runId.json")
        val report = JSONObject()
            .put("runId", runId)
            .put("modelId", modelId)
            .put("status", "running")
            .put("deviceModel", android.os.Build.MODEL)
            .put("sdk", android.os.Build.VERSION.SDK_INT)
            .put("abi", android.os.Build.SUPPORTED_ABIS.first())
            .put("sourceFile", stagedSource.name)
            .put("sourceBytes", stagedSource.length())
            .put("sourceSha256", expectedSourceSha ?: stagedSource.sha256())
        val appCommit = arguments.getString(ARG_APP_COMMIT).orEmpty()
        val testCommit = arguments.getString(ARG_TEST_COMMIT).orEmpty()
        require(SHA1.matches(appCommit) && SHA1.matches(testCommit)) {
            "Exact app and test source commits are required."
        }
        var mediaUri: Uri? = null
        try {
            mediaUri = importIntoMediaStore(context, stagedSource, runId)
            val song = stagedSong(mediaUri, stagedSource, runId)
            val koin = GlobalContext.get()
            val facade = koin.get<SourceSeparationMultiStemProductFacade>()
            val repository = koin.get<SourceSeparationModelAwareCacheRepository>()
            val store = koin.get<SourceSeparationCacheStore>()
            val coordinator = koin.get<SourceSeparationCacheRunCoordinator>()
            val promoter = koin.get<SourceSeparationCacheFlacPromoter>()
            val runtime = requireNotNull(
                koin.get<SourceSeparationRuntimeStore>()
                    .trustedInventory()
                    .singleOrNull { item ->
                        item.state == SourceSeparationRuntimeState.Installed &&
                            item.catalogEntry.abi == android.os.Build.SUPPORTED_ABIS.first()
                    }
                    ?.installation,
            ) { "The trusted CPU runtime installation is unavailable." }
            report.put("build", JSONObject()
                .put("appCommit", appCommit)
                .put("testCommit", testCommit)
                .put("appApkSha256", File(context.applicationInfo.sourceDir).sha256())
                .put("testApkSha256", File(instrumentation.context.applicationInfo.sourceDir).sha256())
            ).put("runtime", JSONObject()
                .put("abi", runtime.identity.abi)
                .put("contractSchemaVersion", runtime.identity.contractSchemaVersion)
                .put("runtimeArtifactVersion", runtime.identity.runtimeArtifactVersion)
                .put("releaseVersion", runtime.identity.releaseVersion)
                .put("librarySha256", runtime.identity.librarySha256)
            )
            repository.entries()
                .filter { it.modelId == modelId && it.title.startsWith(SONG_TITLE_PREFIX) }
                .forEach { entry ->
                    require(repository.delete(entry.cacheKey) == SourceSeparationCacheMutationResult.Completed)
                }

            val progress = mutableListOf<JSONObject>()
            val sampler = PssSampler().also(PssSampler::start)
            val thermalBefore = thermalStatus(context)
            val startedAt = SystemClock.elapsedRealtime()
            val result = try {
                facade.separate(
                    song = song,
                    modelId = modelId,
                    runClass = SourceSeparationExecutionRunClass.ManualFullSong,
                    onProgress = { event ->
                        val snapshot = JSONObject()
                            .put("completedWindows", event.completedWindows)
                            .put("totalWindows", event.totalWindows)
                            .put("stage", event.stage)
                        if (progress.lastOrNull()?.toString() != snapshot.toString()) {
                            progress += snapshot
                        }
                    },
                )
            } finally {
                sampler.stop()
            }
            val elapsedMs = SystemClock.elapsedRealtime() - startedAt
            require(result is HtdemucsSourceSeparationEngineResult.Completed) {
                "Fresh product run did not publish a completed cache: $result"
            }
            val manifest = result.manifest
            val output = requireNotNull(manifest.output)
            assertEquals(SourceSeparationCacheValidationResult.Valid,
                store.validateCompletedEntry(manifest, verifyHashes = false))
            val journal = requireNotNull(store.readRunJournal(manifest.cacheKey))
            assertEquals(SourceSeparationCacheRunJournalLifecycle.Completed, journal.lifecycle)
            val entry = repository.entries().single { it.cacheKey == manifest.cacheKey }
            assertEquals(SourceSeparationCacheModelAvailability.InstalledExact, entry.modelAvailability)
            assertFalse(entry.supportsStandardPlayback)
            val playback = requireNotNull(repository.openCompletedCache(manifest.cacheKey))
            playback.use {
                assertEquals(output.stems.map { stem -> stem.stemId.value }, it.stemIds)
                assertTrue(it.stemFiles.all(File::isFile))
            }
            val promotionStartedAt = SystemClock.elapsedRealtime()
            val promotedResult = promoter.promote(manifest.cacheKey)
            val promotionElapsedMs = SystemClock.elapsedRealtime() - promotionStartedAt
            require(promotedResult is SourceSeparationCacheFlacPromotionResult.Completed)
            val promotedManifest = promotedResult.manifest
            val promotedOutput = requireNotNull(promotedManifest.output)
            assertTrue(promotedOutput.stems.all { stem ->
                stem.promotionValidated && stem.promotedPath != null &&
                    stem.promotedIndexPath != null
            })
            assertTrue(coordinator.cleanCompletedTemporaryFiles(manifest.cacheKey))
            val cleanedManifest = requireNotNull(store.readManifest(manifest.cacheKey))
            assertTrue(cleanedManifest.cleanup == null)
            requireNotNull(cleanedManifest.output).stems.forEach { stem ->
                assertFalse(store.resolveEntryPath(manifest.cacheKey, stem.wavPath).exists())
                assertTrue(store.resolveEntryPath(
                    manifest.cacheKey,
                    requireNotNull(stem.promotedPath),
                ).isFile)
                assertTrue(store.resolveEntryPath(
                    manifest.cacheKey,
                    requireNotNull(stem.promotedIndexPath),
                ).isFile)
            }
            repository.openCompletedCache(manifest.cacheKey)!!.use { promotedPlayback ->
                assertEquals(output.stems.map { stem -> stem.stemId.value }, promotedPlayback.stemIds)
                assertTrue(promotedPlayback.stemFiles.all { file -> file.extension == "flac" })
            }
            val repeatStartedAt = SystemClock.elapsedRealtime()
            val repeated = facade.separate(song, modelId)
            val repeatElapsedMs = SystemClock.elapsedRealtime() - repeatStartedAt
            assertTrue(repeated is HtdemucsSourceSeparationEngineResult.AlreadyCompleted)
            assertTrue(coordinator.inspectCompleted(manifest.identity) != null)

            report.put("status", "complete")
                .put("cacheKey", manifest.cacheKey)
                .put("contractId", manifest.contract.contractId)
                .put("artifactSha256", manifest.identity.artifactSha256)
                .put("sourceAudioFingerprint", manifest.identity.source.audioFingerprint)
                .put("elapsedMs", elapsedMs)
                .put("repeatElapsedMs", repeatElapsedMs)
                .put("pssBaselineKiB", sampler.baselineKiB)
                .put("pssPeakKiB", sampler.peakKiB.get())
                .put("pssFinalKiB", Debug.getPss())
                .put("nativeHeapFinalBytes", Debug.getNativeHeapAllocatedSize())
                .put("thermalBefore", thermalBefore)
                .put("thermalAfter", thermalStatus(context))
                .put("outputSampleRate", output.outputSampleRate)
                .put("outputFrameCount", output.outputFrameCount)
                .put("windowCount", output.windowCount)
                .put("outputBytes", output.totalBytes)
                .put("promotionElapsedMs", promotionElapsedMs)
                .put("promotedBytes", promotedOutput.stems.sumOf { stem ->
                    requireNotNull(stem.promotedIntegrity).byteSize +
                        requireNotNull(stem.promotedIndexIntegrity).byteSize
                })
                .put("cleanedCacheBytes", requireNotNull(cleanedManifest.output).totalBytes)
                .put("stems", JSONArray().apply {
                    output.stems.forEach { stem ->
                        val integrity = requireNotNull(stem.wavIntegrity)
                        put(JSONObject()
                            .put("stemId", stem.stemId.value)
                            .put("semanticId", stem.semanticId.value)
                            .put("order", stem.order)
                            .put("wavPath", stem.wavPath)
                            .put("byteSize", integrity.byteSize)
                            .put("sha256", integrity.sha256)
                        )
                    }
                })
                .put("progress", JSONArray(progress))
            reportFile.writeText(report.toString(2))
        } catch (error: Throwable) {
            report.put("status", "error")
                .put("errorType", error.javaClass.name)
                .put("errorMessage", error.message.orEmpty())
                .put("stackTrace", error.stackTraceToString())
            reportFile.writeText(report.toString(2))
            throw error
        } finally {
            mediaUri?.let { context.contentResolver.delete(it, null, null) }
        }
    }

    @Test
    fun remoteProcessDeathLeavesDurableRunAndRecoversOnNewGeneration() {
        val arguments = InstrumentationRegistry.getArguments()
        val modelId = arguments.getString(ARG_MODEL_ID).orEmpty()
        val relativeSource = arguments.getString(ARG_SOURCE_PATH).orEmpty()
        assumeTrue(modelId.isNotBlank() && relativeSource.isNotBlank())
        require(modelId in EXPECTED_MODEL_IDS && SAFE_RELATIVE_PATH.matches(relativeSource))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.filesDir, relativeSource).canonicalFile
        require(source.isFile)
        arguments.getString(ARG_SOURCE_SHA256)?.let { expected ->
            require(SHA256.matches(expected) && source.sha256() == expected)
        }
        val runId = arguments.getString(ARG_RUN_ID)?.takeIf(SAFE_NAME::matches)
            ?: "remote-death-${System.currentTimeMillis()}"
        var mediaUri: Uri? = null
        try {
            mediaUri = importIntoMediaStore(context, source, runId)
            val song = stagedSong(mediaUri, source, runId)
            val koin = GlobalContext.get()
            val facade = koin.get<SourceSeparationMultiStemProductFacade>()
            val repository = koin.get<SourceSeparationModelAwareCacheRepository>()
            val store = koin.get<SourceSeparationCacheStore>()
            val remote = koin.get<BoundRemoteSourceSeparationMultiStemExecutionHost>()
            repository.entries()
                .filter { it.modelId == modelId && it.title.startsWith(SONG_TITLE_PREFIX) }
                .forEach { repository.delete(it.cacheKey) }

            val firstReady = CountDownLatch(1)
            val workerError = AtomicReference<Throwable?>(null)
            val worker = Thread({
                try {
                    facade.separate(
                        song = song,
                        modelId = modelId,
                        onProgress = { progress ->
                            if (progress.completedWindows >= 1) firstReady.countDown()
                        },
                    )
                } catch (error: Throwable) {
                    workerError.set(error)
                }
            }, "BSS-MultiStem-DeathProbe").apply { start() }
            assertTrue("The remote run did not reach its first window.",
                firstReady.await(120L, java.util.concurrent.TimeUnit.SECONDS))
            remote.terminateRemoteProcessForValidation()
            worker.join(30_000L)
            assertFalse("The caller remained blocked after remote process death.", worker.isAlive)
            assertTrue("Remote process death did not reach the caller.", workerError.get() != null)

            val partial = repository.entries()
                .single { it.modelId == modelId && it.title == song.title }
            val partialJournal = requireNotNull(store.readRunJournal(partial.cacheKey))
            assertEquals(SourceSeparationCacheRunJournalLifecycle.Running,
                partialJournal.lifecycle)

            val recovered = facade.separate(song = song, modelId = modelId)
            assertTrue(
                recovered is HtdemucsSourceSeparationEngineResult.Completed ||
                    recovered is HtdemucsSourceSeparationEngineResult.AlreadyCompleted,
            )
            val recoveredManifest = when (recovered) {
                is HtdemucsSourceSeparationEngineResult.Completed -> recovered.manifest
                is HtdemucsSourceSeparationEngineResult.AlreadyCompleted -> recovered.manifest
                is HtdemucsSourceSeparationEngineResult.Busy -> error("Unexpected busy result")
            }
            val finalJournal = requireNotNull(store.readRunJournal(recoveredManifest.cacheKey))
            assertEquals(SourceSeparationCacheRunJournalLifecycle.Completed,
                finalJournal.lifecycle)
            assertTrue(finalJournal.transitions.any {
                it.type == SourceSeparationCacheRunTransitionType.PreviousOwnerDied
            })
            assertTrue(finalJournal.request.processGeneration > partialJournal.request.processGeneration)
        } finally {
            mediaUri?.let { context.contentResolver.delete(it, null, null) }
        }
    }

    private fun importIntoMediaStore(context: Context, source: File, runId: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "$runId.wav")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/BoomingSS Validation")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = requireNotNull(context.contentResolver.insert(
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            values,
        ))
        try {
            context.contentResolver.openOutputStream(uri, "w")!!.use { output ->
                source.inputStream().use { input -> input.copyTo(output, 256 * 1024) }
            }
            context.contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.Audio.Media.IS_PENDING, 0)
            }, null, null)
            return uri
        } catch (error: Throwable) {
            context.contentResolver.delete(uri, null, null)
            throw error
        }
    }

    private fun stagedSong(uri: Uri, source: File, runId: String): Song = Song(
        id = ContentUris.parseId(uri),
        data = source.absolutePath,
        title = "$SONG_TITLE_PREFIX $runId",
        trackNumber = 1,
        year = 2026,
        size = source.length(),
        duration = SOURCE_DURATION_MS,
        dateAdded = System.currentTimeMillis() / 1_000L,
        rawDateModified = source.lastModified() / 1_000L,
        albumId = -1L,
        albumName = "Phase 6 Validation",
        artistId = -1L,
        artistName = "Scott Buckley",
        albumArtistName = "Scott Buckley",
        genreName = null,
        volumeName = MediaStore.VOLUME_EXTERNAL_PRIMARY,
    )

    private fun thermalStatus(context: Context): Int =
        context.getSystemService(PowerManager::class.java).currentThermalStatus

    private fun waitForMediaController(
        controller: MediaController,
        operation: String,
        predicate: () -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + MEDIA_SESSION_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (onMediaControllerThread(controller, predicate)) return
            SystemClock.sleep(MEDIA_SESSION_POLL_INTERVAL_MS)
        }
        error("MediaController did not complete $operation in time.")
    }

    private fun <T> onMediaControllerThread(
        controller: MediaController,
        block: () -> T,
    ): T {
        if (Looper.myLooper() == controller.applicationLooper) return block()
        val result = AtomicReference<T?>()
        val error = AtomicReference<Throwable?>()
        val completed = CountDownLatch(1)
        Handler(controller.applicationLooper).post {
            try {
                result.set(block())
            } catch (throwable: Throwable) {
                error.set(throwable)
            } finally {
                completed.countDown()
            }
        }
        assertTrue(completed.await(MEDIA_SESSION_TIMEOUT_MS, TimeUnit.MILLISECONDS))
        error.get()?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result.get() as T
    }

    private fun snapshotPreferences(
        preferences: SharedPreferences,
        keys: Set<String>,
    ): Map<String, Any?> = keys.associateWith(preferences.all::get)

    private fun restorePreferences(
        preferences: SharedPreferences,
        snapshot: Map<String, Any?>,
    ) {
        val editor = preferences.edit()
        snapshot.forEach { (key, value) ->
            when (value) {
                null -> editor.remove(key)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                else -> error("Unsupported test preference value for $key")
            }
        }
        check(editor.commit())
    }

    private fun SourceSeparationModelAwarePlayableStatus.closePlaybackForTest() {
        (this as? SourceSeparationModelAwarePlayableStatus.Ready)?.playback?.close()
    }

    private fun File.sha256(): String = inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(256 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private class PssSampler {
        private val running = AtomicBoolean(false)
        val baselineKiB = Debug.getPss().toLong()
        val peakKiB = AtomicLong(baselineKiB)
        private var thread: Thread? = null

        fun start() {
            check(running.compareAndSet(false, true))
            thread = Thread({
                while (running.get()) {
                    peakKiB.accumulateAndGet(Debug.getPss().toLong(), ::maxOf)
                    Thread.sleep(100L)
                }
            }, "BSS-Multistem-Pss").apply { start() }
        }

        fun stop() {
            running.set(false)
            thread?.join(2_000L)
            peakKiB.accumulateAndGet(Debug.getPss().toLong(), ::maxOf)
        }
    }

    private companion object {
        const val ARG_MODEL_ID = "bssMultistemModelId"
        const val ARG_REPLACEMENT_MODEL_ID = "bssMultistemReplacementModelId"
        const val ARG_SOURCE_PATH = "bssMultistemSourcePath"
        const val ARG_SOURCE_SHA256 = "bssMultistemSourceSha256"
        const val ARG_RUN_ID = "bssMultistemRunId"
        const val ARG_APP_COMMIT = "bssAppCommit"
        const val ARG_TEST_COMMIT = "bssTestCommit"
        const val REPORT_DIRECTORY = "source-separation/multistem-product-device-reports"
        const val SONG_TITLE_PREFIX = "BSS Phase 6 Product"
        const val SOURCE_DURATION_MS = 30_000L
        const val MEDIA_SESSION_TIMEOUT_SECONDS = 30L
        const val MEDIA_SESSION_TIMEOUT_MS = MEDIA_SESSION_TIMEOUT_SECONDS * 1_000L
        const val MEDIA_SESSION_POLL_INTERVAL_MS = 50L
        const val MEDIA_SESSION_SEEK_TOLERANCE_MS = 750L
        val SHA256 = Regex("^[0-9a-f]{64}$")
        val SHA1 = Regex("^[0-9a-f]{40}$")
        val SAFE_NAME = Regex("^[A-Za-z0-9._-]{1,160}$")
        val SAFE_RELATIVE_PATH = Regex("^[A-Za-z0-9._/-]{1,240}$")
        val EXPECTED_MODEL_IDS = setOf(
            "htdemucs_6s_core_canonical_7p8s_fp32_v1_0_0",
            "htdemucs_4s_core_canonical_7p8s_fp32_v1_0_0",
            "htdemucs_6s_guitar_ft_core_canonical_7p8s_fp32_v1_0_0",
        )
    }
}
