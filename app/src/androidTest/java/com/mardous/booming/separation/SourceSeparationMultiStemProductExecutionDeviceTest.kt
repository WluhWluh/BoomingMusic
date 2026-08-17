package com.mardous.booming.separation

import android.app.ActivityManager
import android.app.NotificationManager
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
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mardous.booming.data.local.repository.Repository
import com.mardous.booming.data.model.Song
import com.mardous.booming.playback.Playback
import com.mardous.booming.playback.PlaybackService
import com.mardous.booming.playback.processor.SourceSeparationMixAudioProcessor
import com.mardous.booming.separation.audio.Pcm16WavFileReader
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
import com.mardous.booming.separation.process.ipc.SourceSeparationMultiStemAdoptedRun
import com.mardous.booming.separation.process.ipc.SourceSeparationMediaProcessingForegroundController
import com.mardous.booming.separation.process.ipc.SourceSeparationMultiStemExecutionService
import com.mardous.booming.separation.process.SourceSeparationMultiStemExecutionEventPayload
import com.mardous.booming.separation.process.SourceSeparationMultiStemIpcRunAuthority
import com.mardous.booming.separation.process.SourceSeparationMultiStemIpcStatus
import com.mardous.booming.separation.process.SourceSeparationArtRuntimeDiagnostics
import com.mardous.booming.separation.process.SourceSeparationProcessDiagnostics
import com.mardous.booming.separation.model.contract.SourceSeparationMultiTensorQualityGate
import com.mardous.booming.separation.SourceSeparationMultiStemPlaybackSelectionStore
import com.mardous.booming.separation.runtime.SourceSeparationRuntimeState
import com.mardous.booming.separation.runtime.SourceSeparationRuntimeStore
import com.mardous.booming.util.BLACKLIST_ENABLED
import com.mardous.booming.util.IGNORE_AUDIO_FOCUS
import com.mardous.booming.util.MINIMUM_SONG_DURATION
import com.mardous.booming.util.SOURCE_SEPARATION_AUTO_START
import com.mardous.booming.util.SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT
import com.mardous.booming.util.WHITELIST_ENABLED
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

@RunWith(AndroidJUnit4::class)
class SourceSeparationMultiStemProductExecutionDeviceTest {
    @Test
    fun completedPlaybackSwitchesExactModelAndRetainsOldCache() {
        val arguments = InstrumentationRegistry.getArguments()
        val modelId = arguments.getString(ARG_MODEL_ID).orEmpty()
        val replacementModelId = arguments.getString(ARG_REPLACEMENT_MODEL_ID).orEmpty()
        val relativeSource = arguments.getString(ARG_SOURCE_PATH).orEmpty()
        assumeTrue(
            modelId.isNotBlank() && replacementModelId.isNotBlank() &&
                relativeSource.isNotBlank(),
        )
        require(
            modelId in EXPECTED_MODEL_IDS && replacementModelId in EXPECTED_MODEL_IDS &&
                modelId != replacementModelId && SAFE_RELATIVE_PATH.matches(relativeSource),
        )
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.filesDir, relativeSource).canonicalFile
        require(source.toPath().startsWith(context.filesDir.canonicalFile.toPath()) && source.isFile)
        arguments.getString(ARG_SOURCE_SHA256)?.let { expected ->
            require(SHA256.matches(expected) && source.sha256() == expected)
        }
        val runId = arguments.getString(ARG_RUN_ID)?.takeIf(SAFE_NAME::matches)
            ?: "playback-switch-${System.currentTimeMillis()}"
        val koin = GlobalContext.get()
        val facade = koin.get<SourceSeparationMultiStemProductFacade>()
        val repository = koin.get<SourceSeparationModelAwareCacheRepository>()
        val promoter = koin.get<SourceSeparationCacheFlacPromoter>()
        val selectionStore = koin.get<SourceSeparationMultiStemPlaybackSelectionStore>()
        val processor = koin.get<SourceSeparationMixAudioProcessor>()
        val preferences = koin.get<SharedPreferences>()
        val initialSelectedModelId = selectionStore.selectedModelId()
        val preferenceSnapshot = snapshotPreferences(
            preferences,
            setOf(
                MINIMUM_SONG_DURATION,
                SOURCE_SEPARATION_AUTO_START,
                IGNORE_AUDIO_FOCUS,
                WHITELIST_ENABLED,
                BLACKLIST_ENABLED,
            ),
        )
        val reportFile = File(context.filesDir, REPORT_DIRECTORY).apply { mkdirs() }
            .resolve("$runId.json")
        val report = JSONObject()
            .put("runId", runId)
            .put("mode", "completed-playback-model-switch")
            .put("modelId", modelId)
            .put("replacementModelId", replacementModelId)
            .put("status", "running")
            .put("deviceModel", android.os.Build.MODEL)
            .put("sdk", android.os.Build.VERSION.SDK_INT)
            .put("abi", currentProcessAbi())
        val cacheKeys = mutableListOf<String>()
        var mediaUri: Uri? = null
        var controller: MediaController? = null
        try {
            check(
                preferences.edit()
                    .putInt(MINIMUM_SONG_DURATION, 0)
                    .putBoolean(SOURCE_SEPARATION_AUTO_START, false)
                    .putBoolean(IGNORE_AUDIO_FOCUS, true)
                    .putBoolean(WHITELIST_ENABLED, false)
                    .putBoolean(BLACKLIST_ENABLED, false)
                    .commit(),
            )
            mediaUri = importIntoMediaStore(context, source, runId)
            val song = stagedSong(mediaUri, source, runId)
            val repositorySong = runBlocking {
                koin.get<Repository>().songByMediaItem(song.toMediaItem())
            }
            report.put("repositorySong", JSONObject()
                .put("requestedId", song.id)
                .put("resolvedId", repositorySong.id)
                .put("resolvedUri", repositorySong.uri.toString())
                .put("resolvedTitle", repositorySong.title))
            check(repositorySong.id == song.id) {
                "The product repository did not resolve the staged MediaStore song."
            }
            repository.entries()
                .filter {
                    it.modelId in setOf(modelId, replacementModelId) &&
                        it.title.startsWith(SONG_TITLE_PREFIX)
                }
                .forEach { repository.delete(it.cacheKey) }

            fun prepareCompletedCache(targetModelId: String): Pair<String, List<String>> {
                val separated = facade.separate(song = song, modelId = targetModelId)
                val manifest = when (separated) {
                    is HtdemucsSourceSeparationEngineResult.Completed -> separated.manifest
                    is HtdemucsSourceSeparationEngineResult.AlreadyCompleted -> separated.manifest
                    is HtdemucsSourceSeparationEngineResult.Busy -> error("Unexpected busy result")
                }
                val promoted = promoter.promote(manifest.cacheKey)
                val playbackManifest = when (promoted) {
                    is SourceSeparationCacheFlacPromotionResult.Completed -> promoted.manifest
                    is SourceSeparationCacheFlacPromotionResult.AlreadyPromoted -> promoted.manifest
                    is SourceSeparationCacheFlacPromotionResult.Busy ->
                        error("Unexpected promotion contention")
                    SourceSeparationCacheFlacPromotionResult.Unavailable ->
                        error("FLAC promotion was unavailable")
                }
                cacheKeys += playbackManifest.cacheKey
                return playbackManifest.cacheKey to requireNotNull(playbackManifest.output).stems
                    .sortedBy { stem -> stem.order }
                    .map { stem -> stem.stemId.value }
            }

            val (oldCacheKey, oldStemIds) = prepareCompletedCache(modelId)
            val (replacementCacheKey, replacementStemIds) =
                prepareCompletedCache(replacementModelId)
            assertTrue(oldCacheKey != replacementCacheKey)
            assertTrue(oldStemIds != replacementStemIds)
            selectionStore.select(modelId)

            val mediaController = MediaController.Builder(
                context,
                SessionToken(context, ComponentName(context, PlaybackService::class.java)),
            ).buildAsync().get(MEDIA_SESSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            controller = mediaController
            awaitPlaybackRestoration(mediaController)
            onMediaControllerThread(mediaController) {
                mediaController.volume = 0f
                mediaController.setMediaItem(song.toMediaItem())
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
            check(enableResult.resultCode == SessionResult.RESULT_SUCCESS)
            onMediaControllerThread(mediaController) { mediaController.play() }
            waitForMediaController(mediaController, "original multi-stem adoption") {
                mediaController.playWhenReady &&
                    mediaController.playbackState == Player.STATE_READY &&
                    processor.dataPlaneStemIds() == oldStemIds
            }
            val oldMetrics = requireNotNull(processor.dataPlaneMetrics())

            selectionStore.select(replacementModelId)
            waitForMediaController(mediaController, "replacement multi-stem adoption") {
                mediaController.playWhenReady &&
                    mediaController.playbackState == Player.STATE_READY &&
                    processor.dataPlaneStemIds() == replacementStemIds
            }
            repository.openCompletedCache(oldCacheKey)!!.use { retained ->
                assertEquals(oldStemIds, retained.stemIds)
            }
            repository.openCompletedCache(replacementCacheKey)!!.use { replacement ->
                assertEquals(replacementStemIds, replacement.stemIds)
            }

            onMediaControllerThread(mediaController) { mediaController.pause() }
            waitForMediaController(mediaController, "pause after model switch") {
                !mediaController.playWhenReady
            }
            val seekPositionMs = onMediaControllerThread(mediaController) {
                (mediaController.duration / 3L).coerceAtLeast(1_000L)
            }
            onMediaControllerThread(mediaController) { mediaController.seekTo(seekPositionMs) }
            waitForMediaController(mediaController, "seek after model switch") {
                kotlin.math.abs(mediaController.currentPosition - seekPositionMs) <=
                    MEDIA_SESSION_SEEK_TOLERANCE_MS
            }
            onMediaControllerThread(mediaController) { mediaController.play() }
            waitForMediaController(mediaController, "resume after model switch") {
                mediaController.playWhenReady &&
                    mediaController.playbackState == Player.STATE_READY &&
                    processor.dataPlaneStemIds() == replacementStemIds
            }
            val replacementMetrics = requireNotNull(processor.dataPlaneMetrics())
            assertTrue(replacementMetrics.seekRequests > 0L)

            report.put("status", "complete")
                .put("oldCacheKey", oldCacheKey)
                .put("oldStemIds", JSONArray(oldStemIds))
                .put("oldUnderruns", oldMetrics.underruns)
                .put("replacementCacheKey", replacementCacheKey)
                .put("replacementStemIds", JSONArray(replacementStemIds))
                .put("replacementUnderruns", replacementMetrics.underruns)
                .put("replacementSeekRequests", replacementMetrics.seekRequests)
            reportFile.writeText(report.toString(2))
        } catch (error: Throwable) {
            report.put("status", "error")
                .put("errorType", error.javaClass.name)
                .put("errorMessage", error.message.orEmpty())
                .put("stackTrace", error.stackTraceToString())
                .put("processorStemIds", processor.dataPlaneStemIds())
            reportFile.writeText(report.toString(2))
            throw error
        } finally {
            controller?.let { mediaController ->
                runCatching {
                    onMediaControllerThread(mediaController) {
                        mediaController.pause()
                        mediaController.clearMediaItems()
                        mediaController.release()
                    }
                }
            }
            runCatching { context.stopService(Intent(context, PlaybackService::class.java)) }
            selectionStore.select(initialSelectedModelId)
            restorePreferences(preferences, preferenceSnapshot)
            cacheKeys.forEach { key ->
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
    fun completedProductCachePlaysThroughMediaSession() {
        val arguments = InstrumentationRegistry.getArguments()
        val modelId = arguments.getString(ARG_MODEL_ID).orEmpty()
        val relativeSource = arguments.getString(ARG_SOURCE_PATH).orEmpty()
        assumeTrue(modelId.isNotBlank() && relativeSource.isNotBlank())
        require(modelId == OFFICIAL_SIX_STEM_MODEL_ID && SAFE_RELATIVE_PATH.matches(relativeSource))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.filesDir, relativeSource).canonicalFile
        require(source.toPath().startsWith(context.filesDir.canonicalFile.toPath()) && source.isFile)
        arguments.getString(ARG_SOURCE_SHA256)?.let { expected ->
            require(SHA256.matches(expected) && source.sha256() == expected)
        }
        val runId = arguments.getString(ARG_RUN_ID)?.takeIf(SAFE_NAME::matches)
            ?: "playback-$modelId-${System.currentTimeMillis()}"
        val allowedSeekUnderruns = arguments.getString(ARG_ALLOWED_SEEK_UNDERRUNS)
            ?.toLongOrNull()
            ?.also { require(it >= 0L) }
            ?: 0L
        val deleteActiveCache = arguments.getString(ARG_DELETE_ACTIVE_CACHE)
            ?.toBooleanStrictOrNull()
            ?: false
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
            setOf(
                MINIMUM_SONG_DURATION,
                SOURCE_SEPARATION_AUTO_START,
                IGNORE_AUDIO_FOCUS,
                WHITELIST_ENABLED,
                BLACKLIST_ENABLED,
            ),
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
            .put("abi", currentProcessAbi())
        var mediaUri: Uri? = null
        var cacheKey: String? = null
        var controller: MediaController? = null
        try {
            check(preferences.edit()
                .putInt(MINIMUM_SONG_DURATION, 0)
                .putBoolean(SOURCE_SEPARATION_AUTO_START, false)
                .putBoolean(IGNORE_AUDIO_FOCUS, true)
                .putBoolean(WHITELIST_ENABLED, false)
                .putBoolean(BLACKLIST_ENABLED, false)
                .commit())
            mediaUri = importIntoMediaStore(context, source, runId)
            val song = stagedSong(mediaUri, source, runId)
            val repositorySong = runBlocking {
                koin.get<Repository>().songByMediaItem(song.toMediaItem())
            }
            report.put("repositorySong", JSONObject()
                .put("requestedId", song.id)
                .put("resolvedId", repositorySong.id)
                .put("resolvedUri", repositorySong.uri.toString())
                .put("resolvedTitle", repositorySong.title))
            check(repositorySong.id == song.id) {
                "The product repository did not resolve the staged MediaStore song."
            }
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
            awaitPlaybackRestoration(mediaController)
            onMediaControllerThread(mediaController) {
                mediaController.volume = 0f
                mediaController.setMediaItem(song.toMediaItem())
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
                        )?.use { true } == true)
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

            if (deleteActiveCache) {
                val disableResult = onMediaControllerThread(mediaController) {
                    mediaController.sendCustomCommand(
                        SessionCommand(
                            Playback.SET_SOURCE_SEPARATION_PLAYBACK_ENABLED,
                            Bundle.EMPTY,
                        ),
                        Bundle().apply {
                            putBoolean(Playback.EXTRA_SOURCE_SEPARATION_ENABLED, false)
                            putBoolean(Playback.EXTRA_SOURCE_SEPARATION_SHOW_MESSAGE, false)
                        },
                    )
                }.get(MEDIA_SESSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                assertEquals(SessionResult.RESULT_SUCCESS, disableResult.resultCode)
                waitForMediaController(mediaController, "multi-stem playback release") {
                    mediaController.currentMediaItem?.mediaId == song.id.toString() &&
                        mediaController.playWhenReady &&
                        mediaController.playbackState == Player.STATE_READY &&
                        processor.dataPlaneStemIds().isEmpty()
                }
                assertFalse(repository.isLeased(manifest.cacheKey))
                assertEquals(
                    SourceSeparationCacheMutationResult.Completed,
                    repository.delete(manifest.cacheKey),
                )
                assertFalse(store.entryDirectory(manifest.cacheKey).exists())
                assertTrue(repository.entries().none { entry ->
                    entry.cacheKey == manifest.cacheKey
                })
                val notifyResult = onMediaControllerThread(mediaController) {
                    mediaController.sendCustomCommand(
                        SessionCommand(
                            Playback.NOTIFY_SOURCE_SEPARATION_CACHE_DELETED,
                            Bundle.EMPTY,
                        ),
                        Bundle.EMPTY,
                    )
                }.get(MEDIA_SESSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                assertEquals(SessionResult.RESULT_SUCCESS, notifyResult.resultCode)
                assertTrue(processor.dataPlaneStemIds().isEmpty())
                report.put("status", "complete")
                    .put("mode", "active-completed-cache-deletion")
                    .put("cacheKey", manifest.cacheKey)
                    .put("stemIdsBeforeDelete", JSONArray(expectedStemIds))
                    .put("activeStemCountBeforeDelete", metricsBeforeSeek.activeStemCount)
                    .put("playbackReleased", true)
                    .put("cacheDeleted", true)
                    .put("originalTransportRetained", true)
                    .put("notifyResultCode", notifyResult.resultCode)
                reportFile.writeText(report.toString(2))
                return
            }

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
            val seekUnderruns = metricsAfterSeek.underruns - metricsBeforeSeek.underruns
            assertTrue(
                "Seek added $seekUnderruns underruns; allowed=$allowedSeekUnderruns",
                seekUnderruns in 0L..allowedSeekUnderruns,
            )

            onMediaControllerThread(mediaController) {
                mediaController.pause()
                mediaController.clearMediaItems()
                mediaController.release()
            }
            controller = null
            check(context.stopService(Intent(context, PlaybackService::class.java)))
            SystemClock.sleep(SERVICE_RECREATION_SETTLE_MS)

            val recreatedController = MediaController.Builder(
                context,
                SessionToken(context, ComponentName(context, PlaybackService::class.java)),
            ).buildAsync().get(MEDIA_SESSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            controller = recreatedController
            awaitPlaybackRestoration(recreatedController)
            onMediaControllerThread(recreatedController) {
                recreatedController.volume = 0f
                recreatedController.setMediaItem(song.toMediaItem())
                recreatedController.prepare()
            }
            waitForMediaController(recreatedController, "recreated source preparation") {
                recreatedController.currentMediaItem?.mediaId == song.id.toString() &&
                    recreatedController.duration > 0L
            }
            val recreatedEnable = onMediaControllerThread(recreatedController) {
                recreatedController.sendCustomCommand(
                    SessionCommand(Playback.SET_SOURCE_SEPARATION_PLAYBACK_ENABLED, Bundle.EMPTY),
                    Bundle().apply {
                        putBoolean(Playback.EXTRA_SOURCE_SEPARATION_ENABLED, true)
                        putBoolean(Playback.EXTRA_SOURCE_SEPARATION_SHOW_MESSAGE, false)
                        putFloat(Playback.EXTRA_SOURCE_SEPARATION_BLEND, 0.7f)
                    },
                )
            }.get(MEDIA_SESSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            check(recreatedEnable.resultCode == SessionResult.RESULT_SUCCESS)
            onMediaControllerThread(recreatedController) { recreatedController.play() }
            waitForMediaController(recreatedController, "recreated multi-stem adoption") {
                recreatedController.playWhenReady &&
                    recreatedController.playbackState == Player.STATE_READY &&
                    processor.dataPlaneStemIds() == expectedStemIds
            }

            report.put("status", "complete")
                .put("cacheKey", manifest.cacheKey)
                .put("artifactSha256", manifest.identity.artifactSha256)
                .put("stemIds", JSONArray(expectedStemIds))
                .put("activeStemCount", metricsAfterSeek.activeStemCount)
                .put("seekRequests", metricsAfterSeek.seekRequests)
                .put("underrunsBeforeSeek", metricsBeforeSeek.underruns)
                .put("underruns", metricsAfterSeek.underruns)
                .put("seekUnderruns", seekUnderruns)
                .put("allowedSeekUnderruns", allowedSeekUnderruns)
                .put("serviceRecreated", true)
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
    fun producerAheadPlaybackStartsFromTwoReadyWindows() {
        val arguments = InstrumentationRegistry.getArguments()
        val modelId = arguments.getString(ARG_MODEL_ID).orEmpty()
        val relativeSource = arguments.getString(ARG_SOURCE_PATH).orEmpty()
        assumeTrue(modelId.isNotBlank() && relativeSource.isNotBlank())
        require(modelId == OFFICIAL_SIX_STEM_MODEL_ID)
        require(SAFE_RELATIVE_PATH.matches(relativeSource))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.filesDir, relativeSource).canonicalFile
        require(source.toPath().startsWith(context.filesDir.canonicalFile.toPath()) && source.isFile)
        arguments.getString(ARG_SOURCE_SHA256)?.let { expected ->
            require(SHA256.matches(expected) && source.sha256() == expected)
        }
        val runId = arguments.getString(ARG_RUN_ID)?.takeIf(SAFE_NAME::matches)
            ?: "producer-ahead-${System.currentTimeMillis()}"
        val koin = GlobalContext.get()
        val facade = koin.get<SourceSeparationMultiStemProductFacade>()
        val repository = koin.get<SourceSeparationModelAwareCacheRepository>()
        val store = koin.get<SourceSeparationCacheStore>()
        val selectionStore = koin.get<SourceSeparationMultiStemPlaybackSelectionStore>()
        val processor = koin.get<SourceSeparationMixAudioProcessor>()
        val preferences = koin.get<SharedPreferences>()
        val initialSelectedModelId = selectionStore.selectedModelId()
        val preferenceSnapshot = snapshotPreferences(
            preferences,
            setOf(
                MINIMUM_SONG_DURATION,
                SOURCE_SEPARATION_AUTO_START,
                IGNORE_AUDIO_FOCUS,
                WHITELIST_ENABLED,
                BLACKLIST_ENABLED,
            ),
        )
        val reportFile = File(context.filesDir, REPORT_DIRECTORY).apply { mkdirs() }
            .resolve("$runId.json")
        val report = JSONObject()
            .put("runId", runId)
            .put("mode", "producer-ahead-playback")
            .put("modelId", modelId)
            .put("status", "running")
            .put("deviceModel", android.os.Build.MODEL)
            .put("sdk", android.os.Build.VERSION.SDK_INT)
            .put("abi", currentProcessAbi())
        val producerResult = AtomicReference<Result<HtdemucsSourceSeparationEngineResult>?>()
        val preparedManifest = AtomicReference<com.mardous.booming.separation.cache.v2.SourceSeparationCacheManifest?>()
        val producerFinished = CountDownLatch(1)
        var producerThread: Thread? = null
        var mediaUri: Uri? = null
        var cacheKey: String? = null
        var controller: MediaController? = null
        try {
            check(
                preferences.edit()
                    .putInt(MINIMUM_SONG_DURATION, 0)
                    .putBoolean(SOURCE_SEPARATION_AUTO_START, false)
                    .putBoolean(IGNORE_AUDIO_FOCUS, true)
                    .putBoolean(WHITELIST_ENABLED, false)
                    .putBoolean(BLACKLIST_ENABLED, false)
                    .commit(),
            )
            mediaUri = importIntoMediaStore(context, source, runId)
            val song = stagedSong(mediaUri, source, runId)
            repository.entries()
                .filter { it.modelId == modelId && it.title.startsWith(SONG_TITLE_PREFIX) }
                .forEach { repository.delete(it.cacheKey) }
            selectionStore.select(modelId)

            val mediaController = MediaController.Builder(
                context,
                SessionToken(context, ComponentName(context, PlaybackService::class.java)),
            ).buildAsync().get(MEDIA_SESSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            controller = mediaController
            awaitPlaybackRestoration(mediaController)
            onMediaControllerThread(mediaController) {
                mediaController.volume = 0f
                mediaController.setMediaItem(song.toMediaItem())
                mediaController.prepare()
            }
            waitForMediaController(mediaController, "producer-ahead source preparation") {
                mediaController.currentMediaItem?.mediaId == song.id.toString() &&
                    mediaController.duration > 0L &&
                    mediaController.playbackState == Player.STATE_READY
            }

            val producerStartedAt = SystemClock.elapsedRealtime()
            producerThread = Thread({
                try {
                    producerResult.set(runCatching {
                        facade.separate(
                            song = song,
                            modelId = modelId,
                            runClass = SourceSeparationExecutionRunClass.PlaybackDemandWindow,
                            onPrepared = preparedManifest::set,
                        )
                    })
                } finally {
                    producerFinished.countDown()
                }
            }, "BSS-Multistem-ProducerAhead").apply { start() }

            val readyDeadline = SystemClock.elapsedRealtime() + PRODUCER_AHEAD_READY_TIMEOUT_MS
            var readyManifest: com.mardous.booming.separation.cache.v2.SourceSeparationCacheManifest? = null
            while (SystemClock.elapsedRealtime() < readyDeadline) {
                val prepared = preparedManifest.get()
                if (prepared != null) {
                    cacheKey = prepared.cacheKey
                    val current = store.readManifest(prepared.cacheKey)
                    if (current != null) {
                        when (val status = repository.playableStatus(
                            identity = current.identity,
                            playbackPositionMs = 0L,
                            readyWindowCount = PRODUCER_AHEAD_READY_WINDOWS,
                        )) {
                            is SourceSeparationModelAwarePlayableStatus.Ready -> {
                                status.playback.close()
                                readyManifest = current
                                break
                            }
                            SourceSeparationModelAwarePlayableStatus.Processing,
                            SourceSeparationModelAwarePlayableStatus.Unavailable,
                            -> Unit
                        }
                    }
                }
                producerResult.get()?.exceptionOrNull()?.let { throw it }
                SystemClock.sleep(MEDIA_SESSION_POLL_INTERVAL_MS)
            }
            val partialManifest = requireNotNull(readyManifest) {
                "Producer did not publish two playable windows before the timeout."
            }
            assertEquals(SourceSeparationCacheManifestState.Partial, partialManifest.state)
            val expectedStemIds = requireNotNull(partialManifest.output).stems
                .sortedBy { stem -> stem.order }
                .map { stem -> stem.stemId.value }
            assertEquals(6, expectedStemIds.size)
            val readyAtMs = SystemClock.elapsedRealtime() - producerStartedAt

            val enableResult = onMediaControllerThread(mediaController) {
                mediaController.sendCustomCommand(
                    SessionCommand(Playback.SET_SOURCE_SEPARATION_PLAYBACK_ENABLED, Bundle.EMPTY),
                    Bundle().apply {
                        putBoolean(Playback.EXTRA_SOURCE_SEPARATION_ENABLED, true)
                        putBoolean(Playback.EXTRA_SOURCE_SEPARATION_SHOW_MESSAGE, false)
                        putBoolean(Playback.EXTRA_SOURCE_SEPARATION_EXPECT_PROCESSING, true)
                        putFloat(Playback.EXTRA_SOURCE_SEPARATION_BLEND, 0.7f)
                    },
                )
            }.get(MEDIA_SESSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertEquals(SessionResult.RESULT_SUCCESS, enableResult.resultCode)
            val syncResult = onMediaControllerThread(mediaController) {
                mediaController.sendCustomCommand(
                    SessionCommand(Playback.SYNC_SOURCE_SEPARATION_PLAYBACK, Bundle.EMPTY),
                    Bundle().apply {
                        putBoolean(Playback.EXTRA_SOURCE_SEPARATION_ALLOW_NEW_SESSION, true)
                        putBoolean(Playback.EXTRA_SOURCE_SEPARATION_EXPECT_PROCESSING, true)
                        putBoolean(Playback.EXTRA_SOURCE_SEPARATION_PREFER_COMPLETED_CACHE, false)
                    },
                )
            }.get(MEDIA_SESSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertEquals(SessionResult.RESULT_SUCCESS, syncResult.resultCode)
            onMediaControllerThread(mediaController) { mediaController.play() }
            waitForMediaController(mediaController, "producer-ahead partial adoption") {
                mediaController.playWhenReady &&
                    mediaController.playbackState == Player.STATE_READY &&
                    processor.dataPlaneStemIds() == expectedStemIds
            }
            val metricsAtAdoption = requireNotNull(processor.dataPlaneMetrics())
            var stallTransitions = 0
            var stalled = false
            while (!producerFinished.await(MEDIA_SESSION_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)) {
                val nowStalled = onMediaControllerThread(mediaController) {
                    mediaController.playWhenReady &&
                        mediaController.playbackState != Player.STATE_READY
                }
                if (nowStalled && !stalled) stallTransitions++
                stalled = nowStalled
            }
            val completed = producerResult.get()?.getOrThrow()
            require(completed is HtdemucsSourceSeparationEngineResult.Completed) {
                "Producer-ahead run did not complete: $completed"
            }
            waitForMediaController(mediaController, "producer-ahead completion") {
                mediaController.playWhenReady &&
                    mediaController.playbackState == Player.STATE_READY &&
                    processor.dataPlaneStemIds() == expectedStemIds
            }
            val metricsAfterCompletion = requireNotNull(processor.dataPlaneMetrics())
            val addedUnderruns = metricsAfterCompletion.underruns - metricsAtAdoption.underruns
            assertEquals(0L, addedUnderruns)
            assertEquals(0, stallTransitions)
            report.put("status", "complete")
                .put("cacheKey", completed.manifest.cacheKey)
                .put("readyWindowCount", PRODUCER_AHEAD_READY_WINDOWS)
                .put("readyAtMs", readyAtMs)
                .put("completedAtMs", SystemClock.elapsedRealtime() - producerStartedAt)
                .put("stemIds", JSONArray(expectedStemIds))
                .put("stallTransitions", stallTransitions)
                .put("addedLowWaterEvents",
                    metricsAfterCompletion.lowWaterEvents - metricsAtAdoption.lowWaterEvents)
                .put("addedUnderruns", addedUnderruns)
            reportFile.writeText(report.toString(2))
        } catch (error: Throwable) {
            report.put("status", "error")
                .put("errorType", error.javaClass.name)
                .put("errorMessage", error.message.orEmpty())
                .put("stackTrace", error.stackTraceToString())
                .put("processorStemIds", processor.dataPlaneStemIds())
            reportFile.writeText(report.toString(2))
            throw error
        } finally {
            producerThread?.join(PRODUCER_AHEAD_READY_TIMEOUT_MS)
            controller?.let { mediaController ->
                runCatching {
                    onMediaControllerThread(mediaController) {
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
    fun unreadyWindowGateStartsAtOneAndRetargetsRecoveryAfterSeek() {
        val arguments = InstrumentationRegistry.getArguments()
        val modelId = arguments.getString(ARG_MODEL_ID).orEmpty()
        val relativeSource = arguments.getString(ARG_SOURCE_PATH).orEmpty()
        assumeTrue(modelId.isNotBlank() && relativeSource.isNotBlank())
        require(modelId == OFFICIAL_SIX_STEM_MODEL_ID)
        require(SAFE_RELATIVE_PATH.matches(relativeSource))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.filesDir, relativeSource).canonicalFile
        require(source.toPath().startsWith(context.filesDir.canonicalFile.toPath()) && source.isFile)
        arguments.getString(ARG_SOURCE_SHA256)?.let { expected ->
            require(SHA256.matches(expected) && source.sha256() == expected)
        }
        val runId = arguments.getString(ARG_RUN_ID)?.takeIf(SAFE_NAME::matches)
            ?: "unready-gate-${System.currentTimeMillis()}"
        val koin = GlobalContext.get()
        val facade = koin.get<SourceSeparationMultiStemProductFacade>()
        val repository = koin.get<SourceSeparationModelAwareCacheRepository>()
        val store = koin.get<SourceSeparationCacheStore>()
        val selectionStore = koin.get<SourceSeparationMultiStemPlaybackSelectionStore>()
        val processor = koin.get<SourceSeparationMixAudioProcessor>()
        val preferences = koin.get<SharedPreferences>()
        val initialSelectedModelId = selectionStore.selectedModelId()
        val preferenceSnapshot = snapshotPreferences(
            preferences,
            setOf(
                MINIMUM_SONG_DURATION,
                SOURCE_SEPARATION_AUTO_START,
                SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT,
                IGNORE_AUDIO_FOCUS,
                WHITELIST_ENABLED,
                BLACKLIST_ENABLED,
            ),
        )
        val reportFile = File(context.filesDir, REPORT_DIRECTORY).apply { mkdirs() }
            .resolve("$runId.json")
        val report = JSONObject()
            .put("runId", runId)
            .put("mode", "unready-window-gate")
            .put("modelId", modelId)
            .put("status", "running")
            .put("deviceModel", android.os.Build.MODEL)
            .put("sdk", android.os.Build.VERSION.SDK_INT)
            .put("abi", currentProcessAbi())
        val oneReadyReached = CountDownLatch(1)
        val releaseOneReady = CountDownLatch(1)
        val twoReadyReached = CountDownLatch(1)
        val releaseTwoReady = CountDownLatch(1)
        val threeReadyReached = CountDownLatch(1)
        val releaseThreeReady = CountDownLatch(1)
        val fourReadyReached = CountDownLatch(1)
        val releaseFourReady = CountDownLatch(1)
        val blockedAtOne = AtomicBoolean(false)
        val blockedAtTwo = AtomicBoolean(false)
        val blockedAtThree = AtomicBoolean(false)
        val blockedAtFour = AtomicBoolean(false)
        val preparedManifest = AtomicReference<
            com.mardous.booming.separation.cache.v2.SourceSeparationCacheManifest?
        >()
        val producerResult = AtomicReference<Result<HtdemucsSourceSeparationEngineResult>?>()
        val producerFinished = CountDownLatch(1)
        var producerThread: Thread? = null
        var mediaUri: Uri? = null
        var cacheKey: String? = null
        var controller: MediaController? = null
        try {
            check(
                preferences.edit()
                    .putInt(MINIMUM_SONG_DURATION, 0)
                    .putBoolean(SOURCE_SEPARATION_AUTO_START, false)
                    .putInt(SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT, 2)
                    .putBoolean(IGNORE_AUDIO_FOCUS, true)
                    .putBoolean(WHITELIST_ENABLED, false)
                    .putBoolean(BLACKLIST_ENABLED, false)
                    .commit(),
            )
            mediaUri = importIntoMediaStore(context, source, runId)
            val song = stagedSong(mediaUri, source, runId)
            repository.entries()
                .filter { it.modelId == modelId && it.title.startsWith(SONG_TITLE_PREFIX) }
                .forEach { repository.delete(it.cacheKey) }
            selectionStore.select(modelId)

            val producerStartedAt = SystemClock.elapsedRealtime()
            producerThread = Thread({
                try {
                    producerResult.set(runCatching {
                        facade.separate(
                            song = song,
                            modelId = modelId,
                            runClass = SourceSeparationExecutionRunClass.PlaybackDemandWindow,
                            onPrepared = preparedManifest::set,
                            onProgress = { progress ->
                                if (progress.completedWindows == 1 &&
                                    blockedAtOne.compareAndSet(false, true)
                                ) {
                                    oneReadyReached.countDown()
                                    check(releaseOneReady.await(
                                        PRODUCER_AHEAD_READY_TIMEOUT_MS,
                                        TimeUnit.MILLISECONDS,
                                    )) { "Timed out at the one-window producer waterline." }
                                }
                                if (progress.completedWindows == 2 &&
                                    blockedAtTwo.compareAndSet(false, true)
                                ) {
                                    twoReadyReached.countDown()
                                    check(releaseTwoReady.await(
                                        PRODUCER_AHEAD_READY_TIMEOUT_MS,
                                        TimeUnit.MILLISECONDS,
                                    )) { "Timed out at the two-window producer waterline." }
                                }
                                if (progress.completedWindows == 3 &&
                                    blockedAtThree.compareAndSet(false, true)
                                ) {
                                    threeReadyReached.countDown()
                                    check(releaseThreeReady.await(
                                        PRODUCER_AHEAD_READY_TIMEOUT_MS,
                                        TimeUnit.MILLISECONDS,
                                    )) { "Timed out at the three-window producer waterline." }
                                }
                                if (progress.completedWindows == 4 &&
                                    blockedAtFour.compareAndSet(false, true)
                                ) {
                                    fourReadyReached.countDown()
                                    check(releaseFourReady.await(
                                        PRODUCER_AHEAD_READY_TIMEOUT_MS,
                                        TimeUnit.MILLISECONDS,
                                    )) { "Timed out at the four-window producer waterline." }
                                }
                            },
                        )
                    })
                } finally {
                    producerFinished.countDown()
                }
            }, "BSS-Multistem-UnreadyGate").apply { start() }

            check(oneReadyReached.await(
                PRODUCER_AHEAD_READY_TIMEOUT_MS,
                TimeUnit.MILLISECONDS,
            )) { "The producer did not reach the one-window waterline." }
            val oneReadyManifest = requireNotNull(preparedManifest.get()).let { prepared ->
                cacheKey = prepared.cacheKey
                requireNotNull(store.readManifest(prepared.cacheKey))
            }
            val plan = requireNotNull(oneReadyManifest.segmentPlan)
            require(plan.segmentCount >= 4)
            assertEquals(SourceSeparationCacheManifestState.Partial, oneReadyManifest.state)
            assertEquals(1, plan.segments.count { it.state.isPlaybackReady })
            val expectedStemIds = requireNotNull(oneReadyManifest.output).stems
                .sortedBy { stem -> stem.order }
                .map { stem -> stem.stemId.value }
            assertEquals(6, expectedStemIds.size)
            val firstPositionMs = plan.segments[0].playbackStartFrame.toLong() * 1_000L /
                plan.sampleRate + 250L
            val secondPositionMs = plan.segments[1].playbackStartFrame.toLong() * 1_000L /
                plan.sampleRate + 250L
            when (val status = repository.playableStatus(
                identity = oneReadyManifest.identity,
                playbackPositionMs = firstPositionMs,
                readyWindowCount = 1,
            )) {
                is SourceSeparationModelAwarePlayableStatus.Ready -> status.playback.close()
                else -> error("One ready window was not playable: $status")
            }
            assertEquals(
                SourceSeparationModelAwarePlayableStatus.Processing,
                repository.playableStatus(
                    identity = oneReadyManifest.identity,
                    playbackPositionMs = firstPositionMs,
                    readyWindowCount = 2,
                ),
            )

            val mediaController = MediaController.Builder(
                context,
                SessionToken(context, ComponentName(context, PlaybackService::class.java)),
            ).buildAsync().get(MEDIA_SESSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            controller = mediaController
            awaitPlaybackRestoration(mediaController)
            onMediaControllerThread(mediaController) {
                mediaController.volume = 0f
                mediaController.setMediaItem(song.toMediaItem())
                mediaController.prepare()
                mediaController.seekTo(firstPositionMs)
            }
            waitForMediaController(mediaController, "one-window source preparation") {
                mediaController.currentMediaItem?.mediaId == song.id.toString() &&
                    mediaController.duration > 0L &&
                    kotlin.math.abs(mediaController.currentPosition - firstPositionMs) <=
                    MEDIA_SESSION_SEEK_TOLERANCE_MS
            }
            val enableResult = onMediaControllerThread(mediaController) {
                mediaController.sendCustomCommand(
                    SessionCommand(Playback.SET_SOURCE_SEPARATION_PLAYBACK_ENABLED, Bundle.EMPTY),
                    Bundle().apply {
                        putBoolean(Playback.EXTRA_SOURCE_SEPARATION_ENABLED, true)
                        putBoolean(Playback.EXTRA_SOURCE_SEPARATION_SHOW_MESSAGE, false)
                        putBoolean(Playback.EXTRA_SOURCE_SEPARATION_EXPECT_PROCESSING, true)
                        putFloat(Playback.EXTRA_SOURCE_SEPARATION_BLEND, 0.7f)
                    },
                )
            }.get(MEDIA_SESSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertEquals(SessionResult.RESULT_SUCCESS, enableResult.resultCode)
            onMediaControllerThread(mediaController) { mediaController.play() }
            waitForMediaController(mediaController, "one-window playback adoption") {
                mediaController.playWhenReady &&
                    mediaController.isPlaying &&
                    processor.dataPlaneStemIds() == expectedStemIds
            }
            val oneWindowPlaybackStartedAtMs =
                SystemClock.elapsedRealtime() - producerStartedAt

            tracePlaybackMarker(mediaController, "$runId:seek-unready")
            onMediaControllerThread(mediaController) { mediaController.seekTo(secondPositionMs) }
            waitForSourceSeparationWindowWait(
                controller = mediaController,
                anchorPositionMs = secondPositionMs,
                requiredReadyWindowCount = 2,
            )
            val unreadyPauseAtMs = SystemClock.elapsedRealtime() - producerStartedAt

            tracePlaybackMarker(mediaController, "$runId:seek-ready-during-recovery")
            onMediaControllerThread(mediaController) { mediaController.seekTo(firstPositionMs) }
            waitForSourceSeparationWindowWait(
                controller = mediaController,
                anchorPositionMs = firstPositionMs,
                requiredReadyWindowCount = 2,
            )
            SystemClock.sleep(1_500L)
            val pendingDebugState = sourceSeparationDebugState(mediaController)
            val pendingRetargetedWait = pendingDebugState.getString(
                Playback.EXTRA_DEBUG_WINDOW_WAIT,
            )
            assertTrue(
                "Playback window wait cleared before two windows were ready: " +
                    pendingRetargetedWait,
                pendingRetargetedWait?.contains("anchorPositionMs=$firstPositionMs") == true &&
                    pendingRetargetedWait.contains("requiredReadyWindowCount=2") &&
                    !pendingDebugState.getBoolean(
                        Playback.EXTRA_DEBUG_PLAYER_PLAY_WHEN_READY,
                    ) &&
                    !pendingDebugState.getBoolean(Playback.EXTRA_DEBUG_PLAYER_IS_PLAYING),
            )

            releaseOneReady.countDown()
            check(twoReadyReached.await(
                PRODUCER_AHEAD_READY_TIMEOUT_MS,
                TimeUnit.MILLISECONDS,
            )) { "The producer did not reach the two-window recovery waterline." }
            val twoReadyManifest = requireNotNull(store.readManifest(oneReadyManifest.cacheKey))
            assertEquals(SourceSeparationCacheManifestState.Partial, twoReadyManifest.state)
            assertEquals(2, requireNotNull(twoReadyManifest.segmentPlan).segments.count {
                it.state.isPlaybackReady
            })
            when (val status = repository.playableStatus(
                identity = twoReadyManifest.identity,
                playbackPositionMs = firstPositionMs,
                readyWindowCount = 2,
            )) {
                is SourceSeparationModelAwarePlayableStatus.Ready -> status.playback.close()
                else -> error("Two ready windows did not satisfy recovery: $status")
            }
            waitForSourceSeparationPlaybackResumed(
                controller = mediaController,
                operation = "two-window automatic recovery",
            )
            waitForMediaController(mediaController, "two-window data-plane recovery") {
                processor.dataPlaneStemIds() == expectedStemIds
            }
            val recoveredAtMs = SystemClock.elapsedRealtime() - producerStartedAt

            val boundaryProbePositionMs =
                (plan.segments[1].playbackEndFrame.toLong() * 1_000L / plan.sampleRate - 500L)
                    .coerceAtLeast(secondPositionMs)
            tracePlaybackMarker(mediaController, "$runId:boundary-catch-up")
            onMediaControllerThread(mediaController) {
                mediaController.seekTo(boundaryProbePositionMs)
            }
            val boundaryWaitState = waitForSourceSeparationWindowWait(
                controller = mediaController,
                anchorPositionMs = null,
                requiredReadyWindowCount = 2,
            )
            val boundaryWindowWait = requireNotNull(
                boundaryWaitState.getString(Playback.EXTRA_DEBUG_WINDOW_WAIT),
            )
            val boundaryPausedAtMs = SystemClock.elapsedRealtime() - producerStartedAt

            releaseTwoReady.countDown()
            check(threeReadyReached.await(
                PRODUCER_AHEAD_READY_TIMEOUT_MS,
                TimeUnit.MILLISECONDS,
            )) { "The producer did not reach the three-window recovery waterline." }
            SystemClock.sleep(1_000L)
            val oneNewWindowState = sourceSeparationDebugState(mediaController)
            assertEquals(
                "The boundary wait changed after only one new window became ready.",
                boundaryWindowWait,
                oneNewWindowState.getString(Playback.EXTRA_DEBUG_WINDOW_WAIT),
            )
            assertFalse(
                "Playback resumed with only one new window after the boundary miss.",
                oneNewWindowState.getBoolean(Playback.EXTRA_DEBUG_PLAYER_PLAY_WHEN_READY) ||
                    oneNewWindowState.getBoolean(Playback.EXTRA_DEBUG_PLAYER_IS_PLAYING),
            )

            releaseThreeReady.countDown()
            check(fourReadyReached.await(
                PRODUCER_AHEAD_READY_TIMEOUT_MS,
                TimeUnit.MILLISECONDS,
            )) { "The producer did not reach the four-window recovery waterline." }
            waitForSourceSeparationPlaybackResumed(
                controller = mediaController,
                operation = "cache boundary stable recovery",
            )
            val boundaryRecoveredAtMs = SystemClock.elapsedRealtime() - producerStartedAt
            SystemClock.sleep(1_000L)
            val stableRecoveryState = sourceSeparationDebugState(mediaController)
            assertNull(stableRecoveryState.getString(Playback.EXTRA_DEBUG_WINDOW_WAIT))
            assertTrue(stableRecoveryState.getBoolean(
                Playback.EXTRA_DEBUG_PLAYER_PLAY_WHEN_READY,
            ))
            assertTrue(stableRecoveryState.getBoolean(Playback.EXTRA_DEBUG_PLAYER_IS_PLAYING))

            releaseFourReady.countDown()
            check(producerFinished.await(
                PRODUCER_AHEAD_READY_TIMEOUT_MS,
                TimeUnit.MILLISECONDS,
            )) { "The producer did not complete after the recovery check." }
            val completed = producerResult.get()?.getOrThrow()
            require(completed is HtdemucsSourceSeparationEngineResult.Completed) {
                "The controlled producer did not complete: $completed"
            }
            report.put("status", "complete")
                .put("cacheKey", completed.manifest.cacheKey)
                .put("readyWindowCount", 2)
                .put("firstPositionMs", firstPositionMs)
                .put("secondPositionMs", secondPositionMs)
                .put("oneWindowPlaybackStartedAtMs", oneWindowPlaybackStartedAtMs)
                .put("unreadyPauseAtMs", unreadyPauseAtMs)
                .put("recoveredAtMs", recoveredAtMs)
                .put("boundaryProbePositionMs", boundaryProbePositionMs)
                .put("boundaryWindowWait", boundaryWindowWait)
                .put("boundaryPausedAtMs", boundaryPausedAtMs)
                .put("boundaryRecoveredAtMs", boundaryRecoveredAtMs)
                .put("completedAtMs", SystemClock.elapsedRealtime() - producerStartedAt)
                .put("stemIds", JSONArray(expectedStemIds))
            reportFile.writeText(report.toString(2))
        } catch (error: Throwable) {
            report.put("status", "error")
                .put("errorType", error.javaClass.name)
                .put("errorMessage", error.message.orEmpty())
                .put("stackTrace", error.stackTraceToString())
                .put("processorStemIds", processor.dataPlaneStemIds())
            reportFile.writeText(report.toString(2))
            throw error
        } finally {
            releaseOneReady.countDown()
            releaseTwoReady.countDown()
            releaseThreeReady.countDown()
            releaseFourReady.countDown()
            producerThread?.join(PRODUCER_AHEAD_READY_TIMEOUT_MS)
            controller?.let { mediaController ->
                runCatching {
                    val disable = onMediaControllerThread(mediaController) {
                        mediaController.sendCustomCommand(
                            SessionCommand(
                                Playback.SET_SOURCE_SEPARATION_PLAYBACK_ENABLED,
                                Bundle.EMPTY,
                            ),
                            Bundle().apply {
                                putBoolean(Playback.EXTRA_SOURCE_SEPARATION_ENABLED, false)
                                putBoolean(Playback.EXTRA_SOURCE_SEPARATION_SHOW_MESSAGE, false)
                            },
                        )
                    }
                    disable.get(MEDIA_SESSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    onMediaControllerThread(mediaController) {
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
    fun automaticDemandWithNoReadyWindowPausesAndRecovers() {
        val arguments = InstrumentationRegistry.getArguments()
        val modelId = arguments.getString(ARG_MODEL_ID).orEmpty()
        val relativeSource = arguments.getString(ARG_SOURCE_PATH).orEmpty()
        assumeTrue(modelId.isNotBlank() && relativeSource.isNotBlank())
        require(modelId == OFFICIAL_SIX_STEM_MODEL_ID)
        require(SAFE_RELATIVE_PATH.matches(relativeSource))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.filesDir, relativeSource).canonicalFile
        require(source.toPath().startsWith(context.filesDir.canonicalFile.toPath()) && source.isFile)
        arguments.getString(ARG_SOURCE_SHA256)?.let { expected ->
            require(SHA256.matches(expected) && source.sha256() == expected)
        }
        val runId = arguments.getString(ARG_RUN_ID)?.takeIf(SAFE_NAME::matches)
            ?: "automatic-demand-gate-${System.currentTimeMillis()}"
        val koin = GlobalContext.get()
        val facade = koin.get<SourceSeparationMultiStemProductFacade>()
        val repository = koin.get<SourceSeparationModelAwareCacheRepository>()
        val store = koin.get<SourceSeparationCacheStore>()
        val selectionStore = koin.get<SourceSeparationMultiStemPlaybackSelectionStore>()
        val processor = koin.get<SourceSeparationMixAudioProcessor>()
        val preferences = koin.get<SharedPreferences>()
        val initialSelectedModelId = selectionStore.selectedModelId()
        val preferenceSnapshot = snapshotPreferences(
            preferences,
            setOf(
                MINIMUM_SONG_DURATION,
                SOURCE_SEPARATION_AUTO_START,
                SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT,
                IGNORE_AUDIO_FOCUS,
                WHITELIST_ENABLED,
                BLACKLIST_ENABLED,
            ),
        )
        val reportFile = File(context.filesDir, REPORT_DIRECTORY).apply { mkdirs() }
            .resolve("$runId.json")
        val report = JSONObject()
            .put("runId", runId)
            .put("mode", "automatic-demand-zero-ready-gate")
            .put("modelId", modelId)
            .put("status", "running")
            .put("deviceModel", android.os.Build.MODEL)
            .put("sdk", android.os.Build.VERSION.SDK_INT)
            .put("abi", currentProcessAbi())
        val oneReadyReached = CountDownLatch(1)
        val releaseOneReady = CountDownLatch(1)
        val twoReadyReached = CountDownLatch(1)
        val releaseTwoReady = CountDownLatch(1)
        val blockedAtOne = AtomicBoolean(false)
        val blockedAtTwo = AtomicBoolean(false)
        val preparedManifest = AtomicReference<
            com.mardous.booming.separation.cache.v2.SourceSeparationCacheManifest?
        >()
        val producerResult = AtomicReference<Result<HtdemucsSourceSeparationEngineResult>?>()
        val producerFinished = CountDownLatch(1)
        var producerThread: Thread? = null
        var mediaUri: Uri? = null
        var cacheKey: String? = null
        var controller: MediaController? = null
        try {
            check(
                preferences.edit()
                    .putInt(MINIMUM_SONG_DURATION, 0)
                    .putBoolean(SOURCE_SEPARATION_AUTO_START, true)
                    .putInt(SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT, 2)
                    .putBoolean(IGNORE_AUDIO_FOCUS, true)
                    .putBoolean(WHITELIST_ENABLED, false)
                    .putBoolean(BLACKLIST_ENABLED, false)
                    .commit(),
            )
            mediaUri = importIntoMediaStore(context, source, runId)
            val song = stagedSong(mediaUri, source, runId)
            repository.entries()
                .filter { it.modelId == modelId && it.title.startsWith(SONG_TITLE_PREFIX) }
                .forEach { repository.delete(it.cacheKey) }
            selectionStore.select(modelId)

            val mediaController = MediaController.Builder(
                context,
                SessionToken(context, ComponentName(context, PlaybackService::class.java)),
            ).buildAsync().get(MEDIA_SESSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            controller = mediaController
            awaitPlaybackRestoration(mediaController)
            onMediaControllerThread(mediaController) {
                mediaController.volume = 0f
                mediaController.setMediaItem(song.toMediaItem())
                mediaController.prepare()
            }
            waitForMediaController(mediaController, "automatic-demand source preparation") {
                mediaController.currentMediaItem?.mediaId == song.id.toString() &&
                    mediaController.duration > 0L &&
                    mediaController.playbackState == Player.STATE_READY
            }
            onMediaControllerThread(mediaController) { mediaController.play() }
            waitForMediaController(mediaController, "original playback before demand") {
                mediaController.isPlaying && mediaController.playWhenReady
            }

            val enableRequestedAtMs = SystemClock.elapsedRealtime()
            val enableResult = onMediaControllerThread(mediaController) {
                mediaController.sendCustomCommand(
                    SessionCommand(Playback.SET_SOURCE_SEPARATION_PLAYBACK_ENABLED, Bundle.EMPTY),
                    Bundle().apply {
                        putBoolean(Playback.EXTRA_SOURCE_SEPARATION_ENABLED, true)
                        putBoolean(Playback.EXTRA_SOURCE_SEPARATION_SHOW_MESSAGE, false)
                        putBoolean(Playback.EXTRA_SOURCE_SEPARATION_EXPECT_PROCESSING, true)
                        putFloat(Playback.EXTRA_SOURCE_SEPARATION_BLEND, 0.7f)
                    },
                )
            }.get(MEDIA_SESSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertEquals(SessionResult.RESULT_SUCCESS, enableResult.resultCode)
            assertTrue(
                "The automatic-demand gate did not publish processing state.",
                enableResult.extras.getBoolean(Playback.EXTRA_SOURCE_SEPARATION_PROCESSING, false),
            )
            waitForMediaController(mediaController, "automatic-demand zero-ready pause") {
                !mediaController.playWhenReady &&
                    !mediaController.isPlaying
            }
            val pausedAtMs = SystemClock.elapsedRealtime() - enableRequestedAtMs
            onMediaControllerThread(mediaController) { mediaController.play() }
            waitForMediaController(mediaController, "automatic-demand waiting play guard") {
                !mediaController.playWhenReady &&
                    !mediaController.isPlaying
            }

            producerThread = Thread({
                try {
                    producerResult.set(runCatching {
                        facade.separate(
                            song = song,
                            modelId = modelId,
                            runClass = SourceSeparationExecutionRunClass.PlaybackDemandWindow,
                            onPrepared = preparedManifest::set,
                            onProgress = { progress ->
                                if (progress.completedWindows == 1 &&
                                    blockedAtOne.compareAndSet(false, true)
                                ) {
                                    oneReadyReached.countDown()
                                    check(releaseOneReady.await(
                                        PRODUCER_AHEAD_READY_TIMEOUT_MS,
                                        TimeUnit.MILLISECONDS,
                                    )) { "Timed out at the one-window automatic-demand waterline." }
                                }
                                if (progress.completedWindows == 2 &&
                                    blockedAtTwo.compareAndSet(false, true)
                                ) {
                                    twoReadyReached.countDown()
                                    check(releaseTwoReady.await(
                                        PRODUCER_AHEAD_READY_TIMEOUT_MS,
                                        TimeUnit.MILLISECONDS,
                                    )) { "Timed out at the two-window automatic-demand waterline." }
                                }
                            },
                        )
                    })
                } finally {
                    producerFinished.countDown()
                }
            }, "BSS-AutomaticDemandGate").apply { start() }

            check(oneReadyReached.await(
                PRODUCER_AHEAD_READY_TIMEOUT_MS,
                TimeUnit.MILLISECONDS,
            )) { "The automatic-demand producer did not reach the first window." }
            val oneReadyManifest = requireNotNull(preparedManifest.get()).let { prepared ->
                cacheKey = prepared.cacheKey
                requireNotNull(store.readManifest(prepared.cacheKey))
            }
            val plan = requireNotNull(oneReadyManifest.segmentPlan)
            assertEquals(SourceSeparationCacheManifestState.Partial, oneReadyManifest.state)
            assertEquals(1, plan.segments.count { it.state.isPlaybackReady })
            assertFalse(
                "Playback resumed after only one recovery window.",
                onMediaControllerThread(mediaController) { mediaController.playWhenReady },
            )
            val oneReadyAtMs = SystemClock.elapsedRealtime() - enableRequestedAtMs

            releaseOneReady.countDown()
            check(twoReadyReached.await(
                PRODUCER_AHEAD_READY_TIMEOUT_MS,
                TimeUnit.MILLISECONDS,
            )) { "The automatic-demand producer did not reach the second window." }
            waitForMediaController(mediaController, "automatic-demand recovery") {
                mediaController.playWhenReady &&
                    mediaController.isPlaying &&
                    processor.dataPlaneStemIds().isNotEmpty()
            }
            val recoveredAtMs = SystemClock.elapsedRealtime() - enableRequestedAtMs

            releaseTwoReady.countDown()
            check(producerFinished.await(
                PRODUCER_AHEAD_READY_TIMEOUT_MS,
                TimeUnit.MILLISECONDS,
            )) { "The automatic-demand producer did not complete." }
            val completed = producerResult.get()?.getOrThrow()
            require(completed is HtdemucsSourceSeparationEngineResult.Completed) {
                "The automatic-demand producer did not complete: $completed"
            }
            report.put("status", "complete")
                .put("cacheKey", completed.manifest.cacheKey)
                .put("pausedAtMs", pausedAtMs)
                .put("oneReadyAtMs", oneReadyAtMs)
                .put("recoveredAtMs", recoveredAtMs)
                .put("completedAtMs", SystemClock.elapsedRealtime() - enableRequestedAtMs)
                .put("stemIds", JSONArray(processor.dataPlaneStemIds()))
            reportFile.writeText(report.toString(2))
        } catch (error: Throwable) {
            report.put("status", "error")
                .put("errorType", error.javaClass.name)
                .put("errorMessage", error.message.orEmpty())
                .put("stackTrace", error.stackTraceToString())
                .put("processorStemIds", processor.dataPlaneStemIds())
            reportFile.writeText(report.toString(2))
            throw error
        } finally {
            releaseOneReady.countDown()
            releaseTwoReady.countDown()
            producerThread?.join(PRODUCER_AHEAD_READY_TIMEOUT_MS)
            controller?.let { mediaController ->
                runCatching {
                    val disable = onMediaControllerThread(mediaController) {
                        mediaController.sendCustomCommand(
                            SessionCommand(
                                Playback.SET_SOURCE_SEPARATION_PLAYBACK_ENABLED,
                                Bundle.EMPTY,
                            ),
                            Bundle().apply {
                                putBoolean(Playback.EXTRA_SOURCE_SEPARATION_ENABLED, false)
                                putBoolean(Playback.EXTRA_SOURCE_SEPARATION_SHOW_MESSAGE, false)
                            },
                        )
                    }
                    disable.get(MEDIA_SESSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    onMediaControllerThread(mediaController) {
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
            .put("abi", currentProcessAbi())
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
            val pauseRequestedAt = AtomicLong(0L)
            val pauseSchedulerStarted = AtomicBoolean(false)
            val preparedManifest = AtomicReference<com.mardous.booming.separation.cache.v2.SourceSeparationCacheManifest?>()
            val pauseStartedAt = SystemClock.elapsedRealtime()
            val paused = try {
                facade.separate(
                    song = song,
                    modelId = modelId,
                    onProgress = { progress ->
                        if (progress.completedWindows >= 1 &&
                            pauseSchedulerStarted.compareAndSet(false, true)
                        ) {
                            scheduleControlDuringNextInvocation(
                                store = store,
                                cacheKeyProvider = { preparedManifest.get()?.cacheKey },
                                completedWindows = progress.completedWindows,
                                requestedAt = pauseRequestedAt,
                                applyControl = { pause.set(true) },
                            )
                        }
                    },
                    onPrepared = preparedManifest::set,
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
            val pauseResponseMs = SystemClock.elapsedRealtime() - pauseRequestedAt.get()
            assertTrue("Model supersession took ${pauseResponseMs}ms after control was requested.",
                pauseResponseMs <= MAX_TERMINAL_CONTROL_RESPONSE_MS)
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
            val hardTerminationObserved = oldJournal.transitions.any {
                it.type == SourceSeparationCacheRunTransitionType.PreviousOwnerDied
            }

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
                .put("pauseResponseMs", pauseResponseMs)
                .put("hardTerminationObserved", hardTerminationObserved)
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
            .put("abi", currentProcessAbi())
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
            val cancelRequestedAt = AtomicLong(0L)
            val cancelSchedulerStarted = AtomicBoolean(false)
            val preparedManifest = AtomicReference<com.mardous.booming.separation.cache.v2.SourceSeparationCacheManifest?>()
            val cancelStartedAt = SystemClock.elapsedRealtime()
            val canceled = try {
                facade.separate(
                    song = song,
                    modelId = modelId,
                    onProgress = { progress ->
                        if (progress.completedWindows >= 1 &&
                            cancelSchedulerStarted.compareAndSet(false, true)
                        ) {
                            scheduleControlDuringNextInvocation(
                                store = store,
                                cacheKeyProvider = { preparedManifest.get()?.cacheKey },
                                completedWindows = progress.completedWindows,
                                requestedAt = cancelRequestedAt,
                                applyControl = { cancel.set(true) },
                            )
                        }
                    },
                    onPrepared = preparedManifest::set,
                    shouldCancel = cancel::get,
                )
                null
            } catch (error: CancellationException) {
                error
            }
            requireNotNull(canceled) { "The multi-stem run ignored user cancellation." }
            val cancelElapsedMs = SystemClock.elapsedRealtime() - cancelStartedAt
            val cancelResponseMs = SystemClock.elapsedRealtime() - cancelRequestedAt.get()
            assertTrue("Cancellation took ${cancelResponseMs}ms after control was requested.",
                cancelResponseMs <= MAX_TERMINAL_CONTROL_RESPONSE_MS)
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
            val hardTerminationObserved = canceledJournal.transitions.any {
                it.type == SourceSeparationCacheRunTransitionType.PreviousOwnerDied
            }

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
                .put("cancelResponseMs", cancelResponseMs)
                .put("hardTerminationObserved", hardTerminationObserved)
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
        val referenceRoot = arguments.getString(ARG_REFERENCE_PATH)?.let { relativePath ->
            require(SAFE_RELATIVE_PATH.matches(relativePath))
            File(context.filesDir, relativePath).canonicalFile.also { root ->
                require(root.toPath().startsWith(context.filesDir.canonicalFile.toPath()) &&
                    root.isDirectory
                ) { "Staged host reference is unavailable." }
            }
        }
        val reportFile = File(context.filesDir, REPORT_DIRECTORY).apply { mkdirs() }
            .resolve("$runId.json")
        val report = JSONObject()
            .put("runId", runId)
            .put("modelId", modelId)
            .put("status", "running")
            .put("deviceModel", android.os.Build.MODEL)
            .put("sdk", android.os.Build.VERSION.SDK_INT)
            .put("abi", currentProcessAbi())
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
            val remoteHost = koin.get<BoundRemoteSourceSeparationMultiStemExecutionHost>()
            val repository = koin.get<SourceSeparationModelAwareCacheRepository>()
            val store = koin.get<SourceSeparationCacheStore>()
            val coordinator = koin.get<SourceSeparationCacheRunCoordinator>()
            val promoter = koin.get<SourceSeparationCacheFlacPromoter>()
            val runtime = requireNotNull(
                koin.get<SourceSeparationRuntimeStore>()
                    .trustedInventory()
                    .singleOrNull { item ->
                        item.state == SourceSeparationRuntimeState.Installed &&
                            item.catalogEntry.abi == currentProcessAbi()
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
            val windowElapsedMs = mutableListOf<Long>()
            val expectsTerminalProcessRecycle = !android.os.Process.is64Bit()
            val mainSampler = PssSampler().also(PssSampler::start)
            val initialRemoteDiagnostics = remoteHost.processDiagnostics()
            val remoteSampler = RemoteProcessResourceSampler(
                context = context,
                pid = initialRemoteDiagnostics.pid,
                diagnosticsProvider = remoteHost::processDiagnostics,
                initialDiagnostics = initialRemoteDiagnostics,
                allowProcessIdentityTransition = expectsTerminalProcessRecycle,
            ).also(RemoteProcessResourceSampler::start)
            val thermalBefore = thermalStatus(context)
            val startedAt = SystemClock.elapsedRealtime()
            val result = try {
                facade.separate(
                    song = song,
                    modelId = modelId,
                    runClass = SourceSeparationExecutionRunClass.ManualFullSong,
                    onProgress = { event ->
                        event.completedWindowElapsedMs?.let(windowElapsedMs::add)
                        val snapshot = JSONObject()
                            .put("completedWindows", event.completedWindows)
                            .put("totalWindows", event.totalWindows)
                            .put("stage", event.stage)
                            .put(
                                "completedWindowElapsedMs",
                                event.completedWindowElapsedMs ?: JSONObject.NULL,
                            )
                        if (progress.lastOrNull()?.toString() != snapshot.toString()) {
                            progress += snapshot
                        }
                    },
                )
            } finally {
                mainSampler.stop()
                remoteSampler.stop()
            }
            val remoteResources = remoteSampler.snapshot()
            val terminalRemoteDiagnostics = remoteHost.processDiagnostics()
            assertTrue(remoteResources.diagnosticsSampleCount > 0)
            assertFalse(remoteResources.diagnosticsIdentityMismatch)
            assertTerminalProcessLifecycle(
                before = initialRemoteDiagnostics,
                after = terminalRemoteDiagnostics,
                expectsRecycle = expectsTerminalProcessRecycle,
                sampledTransition = remoteResources.diagnosticsTransition,
            )
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
            val hostComparison = referenceRoot?.let { root ->
                compareProductWavsWithHostReference(store, manifest.cacheKey, output, root)
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
                .put("pssBaselineKiB", mainSampler.baselineKiB)
                .put("pssPeakKiB", mainSampler.peakKiB.get())
                .put("pssFinalKiB", Debug.getPss())
                .put("nativeHeapFinalBytes", Debug.getNativeHeapAllocatedSize())
                .put("thermalBefore", thermalBefore)
                .put("thermalPeak", remoteResources.thermalPeak)
                .put("thermalAfter", thermalStatus(context))
                .put("mainProcess", JSONObject()
                    .put("pssBaselineKiB", mainSampler.baselineKiB)
                    .put("pssPeakKiB", mainSampler.peakKiB.get())
                    .put("pssFinalKiB", Debug.getPss())
                    .put("nativeHeapFinalBytes", Debug.getNativeHeapAllocatedSize()))
                .put("remoteProcess", remoteResources.toJson())
                .put(
                    "remoteProcessLifecycle",
                    terminalProcessLifecycleJson(
                        before = initialRemoteDiagnostics,
                        after = terminalRemoteDiagnostics,
                        expectsRecycle = expectsTerminalProcessRecycle,
                        sampledTransition = remoteResources.diagnosticsTransition,
                    ),
                )
                .put("outputSampleRate", output.outputSampleRate)
                .put("outputFrameCount", output.outputFrameCount)
                .put("windowCount", output.windowCount)
                .put("windowTiming", windowTimingJson(windowElapsedMs))
                .put("outputBytes", output.totalBytes)
                .put("promotionElapsedMs", promotionElapsedMs)
                .put("promotedBytes", promotedOutput.stems.sumOf { stem ->
                    requireNotNull(stem.promotedIntegrity).byteSize +
                        requireNotNull(stem.promotedIndexIntegrity).byteSize
                })
                .put("cleanedCacheBytes", requireNotNull(cleanedManifest.output).totalBytes)
                .put("hostComparison", hostComparison)
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
    fun concurrentProductRunReturnsBusyAndPreservesRemoteResources() {
        val arguments = InstrumentationRegistry.getArguments()
        val modelId = arguments.getString(ARG_MODEL_ID).orEmpty()
        val relativeSource = arguments.getString(ARG_SOURCE_PATH).orEmpty()
        assumeTrue(modelId.isNotBlank() && relativeSource.isNotBlank())
        require(modelId == OFFICIAL_SIX_STEM_MODEL_ID && SAFE_RELATIVE_PATH.matches(relativeSource))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.filesDir, relativeSource).canonicalFile
        require(source.toPath().startsWith(context.filesDir.canonicalFile.toPath()) && source.isFile)
        val runId = arguments.getString(ARG_RUN_ID)?.takeIf(SAFE_NAME::matches)
            ?: "contention-${System.currentTimeMillis()}"
        val koin = GlobalContext.get()
        val facade = koin.get<SourceSeparationMultiStemProductFacade>()
        val repository = koin.get<SourceSeparationModelAwareCacheRepository>()
        val store = koin.get<SourceSeparationCacheStore>()
        val reportFile = File(context.filesDir, REPORT_DIRECTORY).apply { mkdirs() }
            .resolve("$runId.json")
        val report = JSONObject()
            .put("runId", runId)
            .put("mode", "remote-contention-resources")
            .put("modelId", modelId)
            .put("status", "running")
            .put("deviceModel", android.os.Build.MODEL)
            .put("sdk", android.os.Build.VERSION.SDK_INT)
            .put("abi", currentProcessAbi())
        val producerResult = AtomicReference<Result<HtdemucsSourceSeparationEngineResult>?>()
        val preparedManifest = AtomicReference<com.mardous.booming.separation.cache.v2.SourceSeparationCacheManifest?>()
        val producerFinished = CountDownLatch(1)
        var producerThread: Thread? = null
        var sampler: RemoteProcessResourceSampler? = null
        var mediaUri: Uri? = null
        var cacheKey: String? = null
        try {
            mediaUri = importIntoMediaStore(context, source, runId)
            val song = stagedSong(mediaUri, source, runId)
            repository.entries()
                .filter { it.modelId == modelId && it.title.startsWith(SONG_TITLE_PREFIX) }
                .forEach { repository.delete(it.cacheKey) }
            val startedAt = SystemClock.elapsedRealtime()
            producerThread = Thread({
                try {
                    producerResult.set(runCatching {
                        facade.separate(
                            song = song,
                            modelId = modelId,
                            runClass = SourceSeparationExecutionRunClass.ManualFullSong,
                            onPrepared = preparedManifest::set,
                        )
                    })
                } finally {
                    producerFinished.countDown()
                }
            }, "BSS-Multistem-Contention-Primary").apply { start() }

            val processDeadline = SystemClock.elapsedRealtime() + REMOTE_PROCESS_TIMEOUT_MS
            var remotePid: Int? = null
            while (remotePid == null && SystemClock.elapsedRealtime() < processDeadline) {
                remotePid = sourceSeparationPid(context)
                if (remotePid == null) SystemClock.sleep(MEDIA_SESSION_POLL_INTERVAL_MS)
            }
            val pid = requireNotNull(remotePid) { "The remote source-separation process did not start." }
            sampler = RemoteProcessResourceSampler(context, pid).also { it.start() }

            val readyDeadline = SystemClock.elapsedRealtime() + PRODUCER_AHEAD_READY_TIMEOUT_MS
            var committedSegments = 0
            while (SystemClock.elapsedRealtime() < readyDeadline) {
                preparedManifest.get()?.let { prepared ->
                    cacheKey = prepared.cacheKey
                    committedSegments = store.readRunJournal(prepared.cacheKey)
                        ?.committedSegments?.size ?: 0
                }
                if (committedSegments > 0) break
                producerResult.get()?.exceptionOrNull()?.let { throw it }
                SystemClock.sleep(MEDIA_SESSION_POLL_INTERVAL_MS)
            }
            val activeCacheKey = requireNotNull(cacheKey)
            assertTrue(committedSegments > 0)
            val foregroundDeadline = SystemClock.elapsedRealtime() + REMOTE_PROCESS_TIMEOUT_MS
            while ((!isMultiStemForeground(context) || !hasProcessingNotification(context)) &&
                SystemClock.elapsedRealtime() < foregroundDeadline
            ) {
                SystemClock.sleep(MEDIA_SESSION_POLL_INTERVAL_MS)
            }
            assertTrue("Multi-stem service did not own the processing foreground lifetime.",
                isMultiStemForeground(context))
            assertTrue("Multi-stem processing notification was not visible.",
                hasProcessingNotification(context))
            val contentionStartedAt = SystemClock.elapsedRealtime()
            val contention = facade.separate(
                song = song,
                modelId = modelId,
                runClass = SourceSeparationExecutionRunClass.ManualFullSong,
            )
            val contentionElapsedMs = SystemClock.elapsedRealtime() - contentionStartedAt
            require(contention is HtdemucsSourceSeparationEngineResult.Busy) {
                "Concurrent request was not reported as busy: $contention"
            }
            assertEquals(activeCacheKey, contention.cacheKey)
            check(producerFinished.await(PRODUCER_AHEAD_READY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                "The primary multi-stem run did not finish."
            }
            val completed = producerResult.get()?.getOrThrow()
            require(completed is HtdemucsSourceSeparationEngineResult.Completed) {
                "The primary run did not survive contention: $completed"
            }
            assertEquals(activeCacheKey, completed.manifest.cacheKey)
            assertEquals(SourceSeparationCacheManifestState.Completed, completed.manifest.state)
            sampler.stop()
            val resources = sampler.snapshot()
            assertTrue(resources.sampleCount > 0)
            assertFalse(resources.processMissing)
            val releaseDeadline = SystemClock.elapsedRealtime() + REMOTE_PROCESS_TIMEOUT_MS
            while ((isMultiStemForeground(context) || hasProcessingNotification(context)) &&
                SystemClock.elapsedRealtime() < releaseDeadline
            ) {
                SystemClock.sleep(MEDIA_SESSION_POLL_INTERVAL_MS)
            }
            assertFalse("Multi-stem foreground service remained after completion.",
                isMultiStemForeground(context))
            assertFalse("Multi-stem processing notification remained after completion.",
                hasProcessingNotification(context))
            report.put("status", "complete")
                .put("cacheKey", activeCacheKey)
                .put("committedSegmentsBeforeContention", committedSegments)
                .put("contentionStatus", "busy")
                .put("contentionElapsedMs", contentionElapsedMs)
                .put("primaryElapsedMs", SystemClock.elapsedRealtime() - startedAt)
                .put("foregroundOwnedWhileRunning", true)
                .put("foregroundReleasedAfterCompletion", true)
                .put("remoteProcess", JSONObject()
                    .put("pid", pid)
                    .put("sampleCount", resources.sampleCount)
                    .put("totalPssBaselineKiB", resources.totalPssBaselineKiB)
                    .put("totalPssPeakKiB", resources.totalPssPeakKiB)
                    .put("totalPssFinalKiB", resources.totalPssFinalKiB)
                    .put("nativePssBaselineKiB", resources.nativePssBaselineKiB)
                    .put("nativePssPeakKiB", resources.nativePssPeakKiB)
                    .put("nativePssFinalKiB", resources.nativePssFinalKiB)
                    .put("dalvikPssPeakKiB", resources.dalvikPssPeakKiB)
                    .put("thermalBaseline", resources.thermalBaseline)
                    .put("thermalPeak", resources.thermalPeak)
                    .put("thermalFinal", resources.thermalFinal)
                    .put("processMissing", resources.processMissing))
            reportFile.writeText(report.toString(2))
        } catch (error: Throwable) {
            report.put("status", "error")
                .put("errorType", error.javaClass.name)
                .put("errorMessage", error.message.orEmpty())
                .put("stackTrace", error.stackTraceToString())
            reportFile.writeText(report.toString(2))
            throw error
        } finally {
            sampler?.stop()
            producerThread?.join(PRODUCER_AHEAD_READY_TIMEOUT_MS)
            cacheKey?.let { key ->
                repeat(20) {
                    if (!repository.isLeased(key)) return@repeat
                    SystemClock.sleep(MEDIA_SESSION_POLL_INTERVAL_MS)
                }
                runCatching { repository.delete(key) }
            }
            mediaUri?.let { uri -> runCatching { context.contentResolver.delete(uri, null, null) } }
        }
    }

    @Test
    fun independentProductRunReplacesObserverAndRoutesCancel() {
        val arguments = InstrumentationRegistry.getArguments()
        val modelId = arguments.getString(ARG_MODEL_ID).orEmpty()
        val relativeSource = arguments.getString(ARG_SOURCE_PATH).orEmpty()
        assumeTrue(modelId.isNotBlank() && relativeSource.isNotBlank())
        require(modelId == OFFICIAL_SIX_STEM_MODEL_ID && SAFE_RELATIVE_PATH.matches(relativeSource))
        val controlledTerminationGraceMs = arguments
            .getString(ARG_CONTROLLED_TERMINATION_GRACE_MS)
            ?.toLongOrNull()
            ?.also { require(it in 1L..MAX_TERMINAL_CONTROL_RESPONSE_MS) }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.filesDir, relativeSource).canonicalFile
        require(source.toPath().startsWith(context.filesDir.canonicalFile.toPath()) && source.isFile)
        val runId = arguments.getString(ARG_RUN_ID)?.takeIf(SAFE_NAME::matches)
            ?: "observer-handoff-${System.currentTimeMillis()}"
        val koin = GlobalContext.get()
        val facade = koin.get<SourceSeparationMultiStemProductFacade>()
        val repository = koin.get<SourceSeparationModelAwareCacheRepository>()
        val store = koin.get<SourceSeparationCacheStore>()
        val reportFile = File(context.filesDir, REPORT_DIRECTORY).apply { mkdirs() }
            .resolve("$runId.json")
        val report = JSONObject()
            .put("runId", runId)
            .put(
                "mode",
                if (controlledTerminationGraceMs == null) {
                    "independent-observer-handoff-cancel"
                } else {
                    "independent-observer-handoff-hard-cancel"
                },
            )
            .put("modelId", modelId)
            .put("status", "running")
            .put("deviceModel", android.os.Build.MODEL)
            .put("sdk", android.os.Build.VERSION.SDK_INT)
            .put("controlledTerminationGraceMs", controlledTerminationGraceMs)
        val producerResult = AtomicReference<Result<HtdemucsSourceSeparationEngineResult>?>()
        val preparedManifest = AtomicReference<com.mardous.booming.separation.cache.v2.SourceSeparationCacheManifest?>()
        val producerFinished = CountDownLatch(1)
        val terminalEvent = CountDownLatch(1)
        val observedSequences = java.util.Collections.synchronizedList(mutableListOf<Long>())
        var producerThread: Thread? = null
        var adopted: SourceSeparationMultiStemAdoptedRun? = null
        var mediaUri: Uri? = null
        var cacheKey: String? = null
        try {
            mediaUri = importIntoMediaStore(context, source, runId)
            val song = stagedSong(mediaUri, source, runId)
            repository.entries()
                .filter { it.modelId == modelId && it.title.startsWith(SONG_TITLE_PREFIX) }
                .forEach { repository.delete(it.cacheKey) }
            producerThread = Thread({
                try {
                    producerResult.set(runCatching {
                        facade.separate(
                            song = song,
                            modelId = modelId,
                            runClass = SourceSeparationExecutionRunClass.ManualFullSong,
                            onPrepared = preparedManifest::set,
                        )
                    })
                } finally {
                    producerFinished.countDown()
                }
            }, "BSS-Multistem-Observer-Handoff").apply { start() }

            val readyDeadline = SystemClock.elapsedRealtime() + PRODUCER_AHEAD_READY_TIMEOUT_MS
            var committedSegments = 0
            while (SystemClock.elapsedRealtime() < readyDeadline) {
                preparedManifest.get()?.let { prepared ->
                    cacheKey = prepared.cacheKey
                    committedSegments = store.readRunJournal(prepared.cacheKey)
                        ?.committedSegments?.size ?: 0
                }
                if (committedSegments > 0) break
                producerResult.get()?.exceptionOrNull()?.let { throw it }
                SystemClock.sleep(MEDIA_SESSION_POLL_INTERVAL_MS)
            }
            val activeCacheKey = requireNotNull(cacheKey)
            assertTrue(committedSegments > 0)
            val remote = if (controlledTerminationGraceMs == null) {
                BoundRemoteSourceSeparationMultiStemExecutionHost(context)
            } else {
                BoundRemoteSourceSeparationMultiStemExecutionHost(
                    context = context,
                    controlledTerminationGraceMs = controlledTerminationGraceMs,
                )
            }
            val snapshot = requireNotNull(remote.reconnectableRun())
            assertEquals(activeCacheKey, snapshot.descriptor.cacheKey)
            assertEquals(SourceSeparationMultiStemIpcRunAuthority.IndependentForeground,
                snapshot.authority)
            assertTrue(snapshot.observerConnected)
            assertTrue(snapshot.foregroundLease != null)
            val adoptedRun = requireNotNull(remote.adoptReconnectableRun { event ->
                observedSequences += event.sequence
                when (event.payload) {
                    is SourceSeparationMultiStemExecutionEventPayload.Completed,
                    is SourceSeparationMultiStemExecutionEventPayload.AlreadyCompleted,
                    is SourceSeparationMultiStemExecutionEventPayload.Paused,
                    is SourceSeparationMultiStemExecutionEventPayload.Canceled,
                    is SourceSeparationMultiStemExecutionEventPayload.Failed ->
                        terminalEvent.countDown()
                    else -> Unit
                }
            })
            adopted = adoptedRun
            assertEquals(activeCacheKey, adoptedRun.state.descriptor.cacheKey)
            assertTrue(adoptedRun.state.observerConnected)

            producerThread.interrupt()
            check(producerFinished.await(REMOTE_PROCESS_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                "The replaced observer did not release its original binding."
            }
            assertTrue(producerResult.get()?.isFailure == true)
            assertTrue(isMultiStemForeground(context))
            assertTrue(hasProcessingNotification(context))

            val runningSegmentAtAdoption = store.readManifest(activeCacheKey)
                ?.segmentPlan
                ?.segments
                ?.singleOrNull { segment ->
                    segment.state ==
                        com.mardous.booming.separation.cache.SourceSeparationSegmentState.Running
                }
                ?.index
            val inFlightDeadline = SystemClock.elapsedRealtime() +
                PRODUCER_AHEAD_READY_TIMEOUT_MS
            var runningSegmentIndex: Int? = null
            while (SystemClock.elapsedRealtime() < inFlightDeadline) {
                val candidate = store.readManifest(activeCacheKey)
                    ?.segmentPlan
                    ?.segments
                    ?.singleOrNull { segment ->
                        segment.state ==
                            com.mardous.booming.separation.cache.SourceSeparationSegmentState.Running
                    }
                    ?.index
                if (candidate != null && candidate != runningSegmentAtAdoption) {
                    runningSegmentIndex = candidate
                    break
                }
                SystemClock.sleep(MEDIA_SESSION_POLL_INTERVAL_MS)
            }
            requireNotNull(runningSegmentIndex) {
                "The adopted run never entered an in-flight model invocation."
            }

            val cancelRequestedAt = SystemClock.elapsedRealtime()
            assertEquals(SourceSeparationMultiStemIpcStatus.Applied, adoptedRun.cancel())
            val terminalDeadline = cancelRequestedAt + MAX_TERMINAL_CONTROL_RESPONSE_MS
            var journal = store.readRunJournal(activeCacheKey)
            while (journal?.lifecycle != SourceSeparationCacheRunJournalLifecycle.Canceled &&
                SystemClock.elapsedRealtime() < terminalDeadline
            ) {
                terminalEvent.await(MEDIA_SESSION_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
                journal = store.readRunJournal(activeCacheKey)
            }
            val cancelResponseMs = SystemClock.elapsedRealtime() - cancelRequestedAt
            assertEquals(SourceSeparationCacheRunJournalLifecycle.Canceled, journal?.lifecycle)
            assertTrue(
                "Adopted cancellation took ${cancelResponseMs}ms after control was requested.",
                cancelResponseMs <= MAX_TERMINAL_CONTROL_RESPONSE_MS,
            )
            val hardTerminationObserved = journal?.transitions?.any {
                it.type == SourceSeparationCacheRunTransitionType.PreviousOwnerDied
            } == true
            if (controlledTerminationGraceMs != null) {
                assertTrue(hardTerminationObserved)
            }
            assertTrue(observedSequences.zipWithNext().all { (first, second) -> second > first })
            val releaseDeadline = SystemClock.elapsedRealtime() + REMOTE_PROCESS_TIMEOUT_MS
            while ((isMultiStemForeground(context) || hasProcessingNotification(context)) &&
                SystemClock.elapsedRealtime() < releaseDeadline
            ) {
                SystemClock.sleep(MEDIA_SESSION_POLL_INTERVAL_MS)
            }
            assertFalse(isMultiStemForeground(context))
            assertFalse(hasProcessingNotification(context))
            report.put("status", "complete")
                .put("cacheKey", activeCacheKey)
                .put("committedSegmentsBeforeHandoff", committedSegments)
                .put("snapshotSequence", snapshot.latestEvent.sequence)
                .put("adoptedSequence", adoptedRun.state.latestEvent.sequence)
                .put("observedSequences", JSONArray(observedSequences))
                .put("runningSegmentAtAdoption", runningSegmentAtAdoption)
                .put("runningSegmentIndexAtCancel", runningSegmentIndex)
                .put("cancelResponseMs", cancelResponseMs)
                .put("terminalEventObserved", terminalEvent.count == 0L)
                .put("hardTerminationObserved", hardTerminationObserved)
                .put("terminalLifecycle", journal?.lifecycle?.name)
                .put("foregroundSurvivedOriginalDisconnect", true)
                .put("foregroundReleasedAfterCancel", true)
            reportFile.writeText(report.toString(2))
        } catch (error: Throwable) {
            report.put("status", "error")
                .put("errorType", error.javaClass.name)
                .put("errorMessage", error.message.orEmpty())
                .put("stackTrace", error.stackTraceToString())
            reportFile.writeText(report.toString(2))
            throw error
        } finally {
            adopted?.close()
            producerThread?.interrupt()
            producerThread?.join(REMOTE_PROCESS_TIMEOUT_MS)
            cacheKey?.let { key -> runCatching { repository.delete(key) } }
            mediaUri?.let { uri -> runCatching { context.contentResolver.delete(uri, null, null) } }
        }
    }

    @Test
    fun validateIndependentMultiStemMainDeathRecovery() {
        val arguments = InstrumentationRegistry.getArguments()
        val runId = arguments.getString(ARG_RUN_ID)?.takeIf(SAFE_NAME::matches)
            ?: error("A stable run ID is required for the two-phase main-death gate.")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scenarioFile = independentMainDeathScenarioFile(context, runId)
        val scenario = JSONObject(scenarioFile.readText(Charsets.UTF_8))
        assertEquals(1, scenario.getInt("schemaVersion"))
        assertEquals(runId, scenario.getString("runId"))
        assertTrue("The product coordinator did not record multi-stem recovery.",
            scenario.optBoolean("productRecoveryObserved", false))
        val modelId = scenario.getString("modelId")
        require(modelId == OFFICIAL_SIX_STEM_MODEL_ID)
        val cacheKey = scenario.getString("cacheKey")
        val mediaUri = Uri.parse(scenario.getString("sourceMediaUri"))
        val oldMainPid = scenario.getInt("mainPid")
        val oldRemotePid = scenario.getInt("remotePid")
        val koin = GlobalContext.get()
        val store = koin.get<SourceSeparationCacheStore>()
        val repository = koin.get<SourceSeparationModelAwareCacheRepository>()
        val promoter = koin.get<SourceSeparationCacheFlacPromoter>()
        val selectionStore = koin.get<SourceSeparationMultiStemPlaybackSelectionStore>()
        val processor = koin.get<SourceSeparationMixAudioProcessor>()
        val initialSelectedModelId = selectionStore.selectedModelId()
        var adopted: SourceSeparationMultiStemAdoptedRun? = null
        var controller: MediaController? = null
        val observedSequences = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val terminalEvent = CountDownLatch(1)
        val reportFile = File(context.filesDir, REPORT_DIRECTORY).apply { mkdirs() }
            .resolve("$runId.json")
        val report = JSONObject()
            .put("runId", runId)
            .put("mode", "independent-main-death-recovery")
            .put("modelId", modelId)
            .put("status", "running")
        try {
            assertNotEquals(oldMainPid, android.os.Process.myPid())
            assertFalse(File("/proc/$oldMainPid").exists())
            val journalAfterDeath = requireNotNull(store.readRunJournal(cacheKey))
            assertEquals(oldRemotePid, journalAfterDeath.request.ownerPid)
            assertTrue(journalAfterDeath.latestSequence >= scenario.getLong("journalSequence"))

            if (journalAfterDeath.lifecycle == SourceSeparationCacheRunJournalLifecycle.Running) {
                val remote = BoundRemoteSourceSeparationMultiStemExecutionHost(context)
                val snapshot = requireNotNull(remote.reconnectableRun())
                assertEquals(cacheKey, snapshot.descriptor.cacheKey)
                assertEquals(scenario.getLong("remoteProcessGeneration"),
                    snapshot.descriptor.processGeneration)
                assertTrue(snapshot.latestEvent.sequence >= scenario.getLong("eventSequence"))
                adopted = requireNotNull(remote.adoptReconnectableRun { event ->
                    observedSequences += event.sequence
                    when (event.payload) {
                        is SourceSeparationMultiStemExecutionEventPayload.Completed,
                        is SourceSeparationMultiStemExecutionEventPayload.AlreadyCompleted,
                        is SourceSeparationMultiStemExecutionEventPayload.Paused,
                        is SourceSeparationMultiStemExecutionEventPayload.Canceled,
                        is SourceSeparationMultiStemExecutionEventPayload.Failed ->
                            terminalEvent.countDown()
                        else -> Unit
                    }
                })
                if (store.readRunJournal(cacheKey)?.lifecycle ==
                    SourceSeparationCacheRunJournalLifecycle.Running
                ) {
                    assertTrue("The adopted run did not publish a terminal event.",
                        terminalEvent.await(PRODUCER_AHEAD_READY_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                }
            }

            val completionDeadline = SystemClock.elapsedRealtime() + PRODUCER_AHEAD_READY_TIMEOUT_MS
            var completedManifest = store.readManifest(cacheKey)
            while (completedManifest?.state != SourceSeparationCacheManifestState.Completed &&
                SystemClock.elapsedRealtime() < completionDeadline
            ) {
                SystemClock.sleep(MEDIA_SESSION_POLL_INTERVAL_MS)
                completedManifest = store.readManifest(cacheKey)
            }
            val completed = requireNotNull(completedManifest)
            assertEquals(SourceSeparationCacheManifestState.Completed, completed.state)
            assertEquals(SourceSeparationCacheValidationResult.Valid,
                store.validateCompletedEntry(completed, verifyHashes = false))
            val finalJournal = requireNotNull(store.readRunJournal(cacheKey))
            assertEquals(SourceSeparationCacheRunJournalLifecycle.Completed, finalJournal.lifecycle)
            assertTrue(observedSequences.zipWithNext().all { (first, second) -> second > first })

            val promoted = promoter.promote(cacheKey)
            val playbackManifest = when (promoted) {
                is SourceSeparationCacheFlacPromotionResult.Completed -> promoted.manifest
                is SourceSeparationCacheFlacPromotionResult.AlreadyPromoted -> promoted.manifest
                is SourceSeparationCacheFlacPromotionResult.Busy -> error("Unexpected FLAC contention")
                SourceSeparationCacheFlacPromotionResult.Unavailable -> error("FLAC unavailable")
            }
            val expectedStemIds = requireNotNull(playbackManifest.output).stems
                .sortedBy { it.order }.map { it.stemId.value }
            selectionStore.select(modelId)
            val song = stagedSong(mediaUri, File(context.filesDir, "unused"), runId)
            val mediaController = MediaController.Builder(
                context,
                SessionToken(context, ComponentName(context, PlaybackService::class.java)),
            ).buildAsync().get(MEDIA_SESSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            controller = mediaController
            awaitPlaybackRestoration(mediaController)
            onMediaControllerThread(mediaController) {
                mediaController.volume = 0f
                mediaController.setMediaItem(song.toMediaItem())
                mediaController.prepare()
            }
            waitForMediaController(mediaController, "recovered source preparation") {
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
            assertEquals(SessionResult.RESULT_SUCCESS, enableResult.resultCode)
            onMediaControllerThread(mediaController) { mediaController.play() }
            waitForMediaController(mediaController, "recovered multi-stem adoption") {
                mediaController.playWhenReady &&
                    mediaController.playbackState == Player.STATE_READY &&
                    processor.dataPlaneStemIds() == expectedStemIds
            }
            val beforeSeek = requireNotNull(processor.dataPlaneMetrics())
            onMediaControllerThread(mediaController) {
                mediaController.pause()
                mediaController.seekTo((mediaController.duration / 3L).coerceAtLeast(1_000L))
                mediaController.play()
            }
            waitForMediaController(mediaController, "recovered seek and resume") {
                mediaController.playWhenReady &&
                    mediaController.playbackState == Player.STATE_READY &&
                    processor.dataPlaneStemIds() == expectedStemIds
            }
            val afterSeek = requireNotNull(processor.dataPlaneMetrics())
            assertTrue(afterSeek.seekRequests > beforeSeek.seekRequests)
            report.put("status", "complete")
                .put("cacheKey", cacheKey)
                .put("oldMainPid", oldMainPid)
                .put("newMainPid", android.os.Process.myPid())
                .put("remotePid", oldRemotePid)
                .put("remoteProcessGeneration", scenario.getLong("remoteProcessGeneration"))
                .put("journalSequenceBeforeDeath", scenario.getLong("journalSequence"))
                .put("finalJournalSequence", finalJournal.latestSequence)
                .put("observedSequences", JSONArray(observedSequences))
                .put("stemIds", JSONArray(expectedStemIds))
                .put("playerAdopted", true)
                .put("seekRequests", afterSeek.seekRequests)
            reportFile.writeText(report.toString(2))
        } catch (error: Throwable) {
            report.put("status", "error")
                .put("errorType", error.javaClass.name)
                .put("errorMessage", error.message.orEmpty())
                .put("stackTrace", error.stackTraceToString())
            reportFile.writeText(report.toString(2))
            throw error
        } finally {
            adopted?.close()
            controller?.let { mediaController ->
                runCatching {
                    onMediaControllerThread(mediaController) {
                        mediaController.pause()
                        mediaController.clearMediaItems()
                        mediaController.release()
                    }
                }
            }
            runCatching { context.stopService(Intent(context, PlaybackService::class.java)) }
            selectionStore.select(initialSelectedModelId)
            repeat(20) {
                if (!repository.isLeased(cacheKey)) return@repeat
                SystemClock.sleep(MEDIA_SESSION_POLL_INTERVAL_MS)
            }
            runCatching { repository.delete(cacheKey) }
            runCatching { context.contentResolver.delete(mediaUri, null, null) }
            scenarioFile.delete()
            scenarioFile.parentFile?.delete()
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
        val appCommit = arguments.getString(ARG_APP_COMMIT)?.takeIf(SHA1::matches)
        val testCommit = arguments.getString(ARG_TEST_COMMIT)?.takeIf(SHA1::matches)
        val reportFile = File(context.filesDir, REPORT_DIRECTORY).apply { mkdirs() }
            .resolve("$runId.json")
        val report = JSONObject()
            .put("runId", runId)
            .put("mode", "remote-process-death-recovery")
            .put("modelId", modelId)
            .put("status", "running")
            .put("deviceModel", android.os.Build.MODEL)
            .put("sdk", android.os.Build.VERSION.SDK_INT)
            .put("abi", currentProcessAbi())
            .put("build", JSONObject()
                .put("appCommit", appCommit ?: JSONObject.NULL)
                .put("testCommit", testCommit ?: JSONObject.NULL)
                .put("appApkSha256", File(context.applicationInfo.sourceDir).sha256())
                .put(
                    "testApkSha256",
                    File(instrumentation.context.applicationInfo.sourceDir).sha256(),
                )
            )
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
            assertNotEquals(
                partialJournal.request.processGeneration,
                finalJournal.request.processGeneration,
            )
            report.put("status", "complete")
                .put("cacheKey", recoveredManifest.cacheKey)
                .put("oldRunId", partialJournal.request.runId)
                .put("newRunId", finalJournal.request.runId)
                .put("oldProcessGeneration", partialJournal.request.processGeneration)
                .put("newProcessGeneration", finalJournal.request.processGeneration)
                .put("oldOwnerPid", partialJournal.request.ownerPid)
                .put("newOwnerPid", finalJournal.request.ownerPid)
                .put("readySegmentsBeforeDeath", partialJournal.committedSegments.size)
                .put("finalLifecycle", finalJournal.lifecycle.name)
                .put("transitions", JSONArray().apply {
                    finalJournal.transitions.forEach { transition -> put(transition.type.name) }
                })
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

    private fun scheduleControlDuringNextInvocation(
        store: SourceSeparationCacheStore,
        cacheKeyProvider: () -> String?,
        completedWindows: Int,
        requestedAt: AtomicLong,
        applyControl: () -> Unit,
    ) {
        Thread({
            val deadline = SystemClock.elapsedRealtime() + REMOTE_PROCESS_TIMEOUT_MS
            while (SystemClock.elapsedRealtime() < deadline) {
                val observedRunningWindow = cacheKeyProvider()?.let(store::readRunJournal)
                    ?.transitions
                    ?.any { transition ->
                        transition.type == SourceSeparationCacheRunTransitionType.SegmentRunning &&
                            (transition.segmentIndex ?: -1) >= completedWindows
                    } == true
                if (observedRunningWindow) {
                    SystemClock.sleep(IN_FLIGHT_CONTROL_DELAY_MS)
                    break
                }
                SystemClock.sleep(MEDIA_SESSION_POLL_INTERVAL_MS)
            }
            requestedAt.compareAndSet(0L, SystemClock.elapsedRealtime())
            applyControl()
        }, "BSS-Multistem-InFlight-Control").apply {
            isDaemon = true
            start()
        }
    }

    private fun importIntoMediaStore(context: Context, source: File, runId: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "$runId.wav")
            put(MediaStore.Audio.Media.TITLE, "$SONG_TITLE_PREFIX $runId")
            put(MediaStore.Audio.Media.ARTIST, "Scott Buckley")
            put(MediaStore.Audio.Media.ALBUM, "Phase 6 Validation")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.IS_MUSIC, 1)
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
            val updated = context.contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.Audio.Media.IS_PENDING, 0)
                put(MediaStore.Audio.Media.TITLE, "$SONG_TITLE_PREFIX $runId")
                put(MediaStore.Audio.Media.ARTIST, "Scott Buckley")
                put(MediaStore.Audio.Media.ALBUM, "Phase 6 Validation")
                put(MediaStore.Audio.Media.IS_MUSIC, 1)
            }, null, null)
            check(updated == 1) { "The staged MediaStore row was not finalized." }
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.IS_MUSIC),
                null,
                null,
                null,
            )!!.use { cursor ->
                check(cursor.moveToFirst()) { "The staged MediaStore row is unavailable." }
                check(cursor.getString(0).isNotBlank() && cursor.getInt(1) == 1) {
                    "The staged MediaStore row is not classified as music."
                }
            }
            return uri
        } catch (error: Throwable) {
            context.contentResolver.delete(uri, null, null)
            throw error
        }
    }

    private fun independentMainDeathScenarioFile(context: Context, runId: String): File =
        File(context.filesDir, "$MAIN_DEATH_SCENARIO_DIRECTORY/$runId.json")

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

    private fun currentProcessAbi(): String {
        val abis = if (android.os.Process.is64Bit()) {
            android.os.Build.SUPPORTED_64_BIT_ABIS
        } else {
            android.os.Build.SUPPORTED_32_BIT_ABIS
        }
        return requireNotNull(abis.firstOrNull()) { "The current process has no reported ABI." }
    }

    private fun compareProductWavsWithHostReference(
        store: SourceSeparationCacheStore,
        cacheKey: String,
        output: com.mardous.booming.separation.cache.v2.SourceSeparationCacheOutput,
        referenceRoot: File,
    ): JSONObject = JSONObject()
        .put("referenceRoot", referenceRoot.name)
        .put("thresholdProfile", "phase6-frozen-v2-pcm16")
        .put("passes", true)
        .put("stems", JSONArray().apply {
            output.stems.forEach { stem ->
                val actualFile = store.resolveEntryPath(cacheKey, stem.wavPath)
                val referenceFile = referenceRoot.resolve("${stem.stemId.value}.wav")
                val actualInfo = Pcm16WavFileReader.read(actualFile)
                val referenceInfo = Pcm16WavFileReader.read(referenceFile)
                require(actualInfo.sampleRate == output.outputSampleRate &&
                    referenceInfo.sampleRate == output.outputSampleRate &&
                    actualInfo.channelCount == 2 && referenceInfo.channelCount == 2 &&
                    actualInfo.frameCount == output.outputFrameCount.toLong() &&
                    referenceInfo.frameCount == output.outputFrameCount.toLong()
                ) { "Host reference WAV geometry differs for ${stem.stemId.value}." }
                val metrics = SourceSeparationMultiTensorQualityGate.comparePcm16(
                    expected = referenceFile.readPcm16Data(referenceInfo.dataOffset, referenceInfo.dataSize),
                    actual = actualFile.readPcm16Data(actualInfo.dataOffset, actualInfo.dataSize),
                    expectedFrameCount = output.outputFrameCount,
                    channelCount = 2,
                )
                require(metrics.passes) {
                    "Product PCM16 differs from the host reference for ${stem.stemId.value}: $metrics"
                }
                put(JSONObject()
                    .put("stemId", stem.stemId.value)
                    .put("referenceSha256", referenceFile.sha256())
                    .put("actualSha256", actualFile.sha256())
                    .put("frameCount", output.outputFrameCount)
                    .put("maximumSampleDelta", metrics.maximumSampleDelta)
                    .put("maximumAbsoluteError", metrics.maximumAbsoluteError)
                    .put("passes", metrics.passes))
            }
        })

    private fun File.readPcm16Data(dataOffset: Long, dataSize: Long): ByteArray {
        require(dataSize in 0..Int.MAX_VALUE.toLong()) { "WAV PCM payload is too large." }
        return ByteArray(dataSize.toInt()).also { bytes ->
            RandomAccessFile(this, "r").use { input ->
                input.seek(dataOffset)
                input.readFully(bytes)
            }
        }
    }

    private fun sourceSeparationPid(context: Context): Int? =
        context.getSystemService(ActivityManager::class.java)
            .runningAppProcesses
            ?.singleOrNull { process ->
                process.processName == "${context.packageName}:source_separation"
            }
            ?.pid

    @Suppress("DEPRECATION")
    private fun isMultiStemForeground(context: Context): Boolean =
        context.getSystemService(ActivityManager::class.java)
            .getRunningServices(Int.MAX_VALUE)
            .any { service ->
                service.service.className ==
                    SourceSeparationMultiStemExecutionService::class.java.name &&
                    service.foreground
            }

    private fun hasProcessingNotification(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java)
            .activeNotifications
            .any { notification ->
                notification.id ==
                    SourceSeparationMediaProcessingForegroundController.NOTIFICATION_ID
            }

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

    private fun awaitPlaybackRestoration(controller: MediaController) {
        onMediaControllerThread(controller) {
            controller.sendCustomCommand(
                SessionCommand(Playback.AWAIT_PLAYBACK_RESTORATION, Bundle.EMPTY),
                Bundle.EMPTY,
            )
        }.get(MEDIA_SESSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
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

    private fun tracePlaybackMarker(controller: MediaController, marker: String) {
        val result = onMediaControllerThread(controller) {
            controller.sendCustomCommand(
                SessionCommand(
                    Playback.TRACE_SOURCE_SEPARATION_PLAYBACK_MARKER,
                    Bundle.EMPTY,
                ),
                Bundle().apply {
                    putString(Playback.EXTRA_SOURCE_SEPARATION_TRACE_MARKER, marker)
                },
            )
        }.get(MEDIA_SESSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        assertEquals(SessionResult.RESULT_SUCCESS, result.resultCode)
    }

    private fun waitForSourceSeparationWindowWait(
        controller: MediaController,
        anchorPositionMs: Long?,
        requiredReadyWindowCount: Int,
    ): Bundle {
        val expectedAnchor = anchorPositionMs?.let { "anchorPositionMs=$it" }
        val expectedWindowCount = "requiredReadyWindowCount=$requiredReadyWindowCount"
        val deadline = SystemClock.elapsedRealtime() + MEDIA_SESSION_TIMEOUT_MS
        var lastWindowWait: String? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            val debugState = sourceSeparationDebugState(controller)
            lastWindowWait = debugState.getString(Playback.EXTRA_DEBUG_WINDOW_WAIT)
            if ((expectedAnchor == null || lastWindowWait?.contains(expectedAnchor) == true) &&
                lastWindowWait?.contains(expectedWindowCount) == true &&
                !debugState.getBoolean(Playback.EXTRA_DEBUG_PLAYER_PLAY_WHEN_READY) &&
                !debugState.getBoolean(Playback.EXTRA_DEBUG_PLAYER_IS_PLAYING)
            ) {
                return debugState
            }
            SystemClock.sleep(MEDIA_SESSION_POLL_INTERVAL_MS)
        }
        error(
            "Source separation window wait did not reach ${expectedAnchor ?: "any anchor"}, " +
                "$expectedWindowCount; last=$lastWindowWait",
        )
    }

    private fun waitForSourceSeparationPlaybackResumed(
        controller: MediaController,
        operation: String,
    ): Bundle {
        val deadline = SystemClock.elapsedRealtime() + MEDIA_SESSION_TIMEOUT_MS
        var lastState = Bundle.EMPTY
        while (SystemClock.elapsedRealtime() < deadline) {
            lastState = sourceSeparationDebugState(controller)
            if (lastState.getString(Playback.EXTRA_DEBUG_WINDOW_WAIT) == null &&
                lastState.getBoolean(Playback.EXTRA_DEBUG_PLAYER_PLAY_WHEN_READY) &&
                lastState.getBoolean(Playback.EXTRA_DEBUG_PLAYER_IS_PLAYING)
            ) {
                return lastState
            }
            SystemClock.sleep(MEDIA_SESSION_POLL_INTERVAL_MS)
        }
        error(
            "Source separation did not complete $operation; " +
                "windowWait=${lastState.getString(Playback.EXTRA_DEBUG_WINDOW_WAIT)}, " +
                "playWhenReady=${lastState.getBoolean(
                    Playback.EXTRA_DEBUG_PLAYER_PLAY_WHEN_READY,
                )}, isPlaying=${lastState.getBoolean(Playback.EXTRA_DEBUG_PLAYER_IS_PLAYING)}",
        )
    }

    private fun sourceSeparationDebugState(controller: MediaController): Bundle {
        val result = onMediaControllerThread(controller) {
            controller.sendCustomCommand(
                SessionCommand(
                    Playback.GET_SOURCE_SEPARATION_DEBUG_STATE,
                    Bundle.EMPTY,
                ),
                Bundle.EMPTY,
            )
        }.get(MEDIA_SESSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        assertEquals(SessionResult.RESULT_SUCCESS, result.resultCode)
        return result.extras
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

    private fun windowTimingJson(samples: List<Long>): JSONObject {
        val sorted = samples.sorted()
        return JSONObject()
            .put("sampleCount", sorted.size)
            .put(
                "meanMs",
                sorted.takeIf { it.isNotEmpty() }?.let { values ->
                    values.sum().toDouble() / values.size
                } ?: JSONObject.NULL,
            )
            .put("p50Ms", percentile(sorted, 50) ?: JSONObject.NULL)
            .put("p95Ms", percentile(sorted, 95) ?: JSONObject.NULL)
            .put("maxMs", sorted.maxOrNull() ?: JSONObject.NULL)
            .put("samplesMs", JSONArray(sorted))
    }

    private fun percentile(sorted: List<Long>, percentile: Int): Long? {
        require(percentile in 1..100)
        if (sorted.isEmpty()) return null
        val rank = ((percentile * sorted.size + 99) / 100).coerceAtLeast(1)
        return sorted[rank - 1]
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

    private fun assertTerminalProcessLifecycle(
        before: SourceSeparationProcessDiagnostics,
        after: SourceSeparationProcessDiagnostics,
        expectsRecycle: Boolean,
        sampledTransition: SourceSeparationProcessDiagnostics?,
    ) {
        if (expectsRecycle) {
            assertNotEquals(before.processGeneration, after.processGeneration)
            assertNotEquals(before.processStartTicks, after.processStartTicks)
            val transition = requireNotNull(sampledTransition) {
                "The arm32 terminal process recycle was not observed by diagnostics."
            }
            assertEquals(after.pid, transition.pid)
            assertEquals(after.processGeneration, transition.processGeneration)
            assertEquals(after.processStartTicks, transition.processStartTicks)
        } else {
            assertEquals(before.pid, after.pid)
            assertEquals(before.processGeneration, after.processGeneration)
            assertEquals(before.processStartTicks, after.processStartTicks)
            assertNull(sampledTransition)
        }
    }

    private fun terminalProcessLifecycleJson(
        before: SourceSeparationProcessDiagnostics,
        after: SourceSeparationProcessDiagnostics,
        expectsRecycle: Boolean,
        sampledTransition: SourceSeparationProcessDiagnostics?,
    ): JSONObject = JSONObject()
        .put("expectedTerminalRecycle", expectsRecycle)
        .put("before", processIdentityJson(before))
        .put("after", processIdentityJson(after))
        .put("pidChanged", before.pid != after.pid)
        .put("processGenerationChanged", before.processGeneration != after.processGeneration)
        .put("processStartTicksChanged", before.processStartTicks != after.processStartTicks)
        .put(
            "samplerTransition",
            sampledTransition?.let(::processIdentityJson) ?: JSONObject.NULL,
        )

    private class PssSampler {
        private val running = AtomicBoolean(false)
        val baselineKiB = Debug.getPss()
        val peakKiB = AtomicLong(baselineKiB)
        private var thread: Thread? = null

        fun start() {
            check(running.compareAndSet(false, true))
            thread = Thread({
                while (running.get()) {
                    peakKiB.accumulateAndGet(Debug.getPss(), ::maxOf)
                    Thread.sleep(100L)
                }
            }, "BSS-Multistem-Pss").apply { start() }
        }

        fun stop() {
            running.set(false)
            thread?.join(2_000L)
            peakKiB.accumulateAndGet(Debug.getPss(), ::maxOf)
        }
    }

    private class RemoteProcessResourceSampler(
        private val context: Context,
        private val pid: Int,
        private val diagnosticsProvider: (() -> SourceSeparationProcessDiagnostics)? = null,
        initialDiagnostics: SourceSeparationProcessDiagnostics? = null,
        private val allowProcessIdentityTransition: Boolean = false,
    ) {
        private val activityManager = context.getSystemService(ActivityManager::class.java)
        private val powerManager = context.getSystemService(PowerManager::class.java)
        private val running = AtomicBoolean(false)
        private val samples = AtomicInteger(0)
        private val totalPeak = AtomicInteger(0)
        private val nativePeak = AtomicInteger(0)
        private val dalvikPeak = AtomicInteger(0)
        private val thermalPeak = AtomicInteger(powerManager.currentThermalStatus)
        private val processMissing = AtomicBoolean(false)
        private val baseline = AtomicReference<RemoteMemorySample?>()
        private val latest = AtomicReference<RemoteMemorySample?>()
        private val expectedProcessGeneration = initialDiagnostics?.processGeneration
        private val hasInitialDiagnostics = initialDiagnostics != null
        private val diagnosticsSamples = AtomicInteger(if (initialDiagnostics == null) 0 else 1)
        private val diagnosticsFailures = AtomicInteger(0)
        private val diagnosticsIdentityMismatch = AtomicBoolean(false)
        private val diagnosticsBaseline = AtomicReference(initialDiagnostics)
        private val diagnosticsLatest = AtomicReference(initialDiagnostics)
        private val diagnosticsTransition =
            AtomicReference<SourceSeparationProcessDiagnostics?>(null)
        private val javaHeapAllocatedPeakBytes = AtomicLong(
            initialDiagnostics?.memory?.javaHeapAllocatedBytes ?: 0L,
        )
        private val nativeHeapAllocatedPeakBytes = AtomicLong(
            initialDiagnostics?.memory?.nativeHeapAllocatedBytes ?: 0L,
        )
        private val nextDiagnosticsSampleAtMs = AtomicLong(0L)
        private var thread: Thread? = null

        fun start() {
            if (!running.compareAndSet(false, true)) return
            sampleMemory()
            sampleDiagnostics(force = !hasInitialDiagnostics)
            thread = Thread({
                while (running.get()) {
                    sampleMemory()
                    sampleDiagnostics(force = false)
                    SystemClock.sleep(REMOTE_RESOURCE_SAMPLE_INTERVAL_MS)
                }
            }, "BSS-Multistem-Remote-Resources").apply { start() }
        }

        fun stop() {
            if (!running.getAndSet(false)) return
            thread?.join(2_000L)
            sampleMemory()
            sampleDiagnostics(force = true)
        }

        fun snapshot(): RemoteResourceSnapshot {
            val first = requireNotNull(baseline.get())
            val last = requireNotNull(latest.get())
            return RemoteResourceSnapshot(
                sampleCount = samples.get(),
                totalPssBaselineKiB = first.totalPssKiB,
                totalPssPeakKiB = totalPeak.get(),
                totalPssFinalKiB = last.totalPssKiB,
                nativePssBaselineKiB = first.nativePssKiB,
                nativePssPeakKiB = nativePeak.get(),
                nativePssFinalKiB = last.nativePssKiB,
                dalvikPssPeakKiB = dalvikPeak.get(),
                thermalBaseline = first.thermalStatus,
                thermalPeak = thermalPeak.get(),
                thermalFinal = last.thermalStatus,
                processMissing = processMissing.get(),
                processGeneration = expectedProcessGeneration,
                diagnosticsSampleCount = diagnosticsSamples.get(),
                diagnosticsFailureCount = diagnosticsFailures.get(),
                diagnosticsIdentityMismatch = diagnosticsIdentityMismatch.get(),
                javaHeapAllocatedPeakBytes = javaHeapAllocatedPeakBytes.get(),
                nativeHeapAllocatedPeakBytes = nativeHeapAllocatedPeakBytes.get(),
                diagnosticsBaseline = diagnosticsBaseline.get(),
                diagnosticsFinal = diagnosticsLatest.get(),
                diagnosticsTransition = diagnosticsTransition.get(),
            )
        }

        private fun sampleMemory() {
            val processAlive = activityManager.runningAppProcesses
                ?.any { it.pid == pid } == true
            if (!processAlive) {
                processMissing.set(true)
                return
            }
            val memory = activityManager.getProcessMemoryInfo(intArrayOf(pid)).singleOrNull()
                ?: return
            val current = RemoteMemorySample(
                totalPssKiB = memory.totalPss,
                nativePssKiB = memory.nativePss,
                dalvikPssKiB = memory.dalvikPss,
                thermalStatus = powerManager.currentThermalStatus,
            )
            baseline.compareAndSet(null, current)
            latest.set(current)
            samples.incrementAndGet()
            totalPeak.accumulateAndGet(current.totalPssKiB, ::maxOf)
            nativePeak.accumulateAndGet(current.nativePssKiB, ::maxOf)
            dalvikPeak.accumulateAndGet(current.dalvikPssKiB, ::maxOf)
            thermalPeak.accumulateAndGet(current.thermalStatus, ::maxOf)
        }

        private fun sampleDiagnostics(force: Boolean) {
            val provider = diagnosticsProvider ?: return
            val now = SystemClock.elapsedRealtime()
            if (!force && now < nextDiagnosticsSampleAtMs.get()) return
            nextDiagnosticsSampleAtMs.set(now + REMOTE_DIAGNOSTICS_SAMPLE_INTERVAL_MS)
            val current = runCatching(provider).getOrElse {
                diagnosticsFailures.incrementAndGet()
                return
            }
            val expectedStartTicks = diagnosticsBaseline.get()?.processStartTicks
            val identityChanged = current.pid != pid ||
                expectedProcessGeneration?.let { it != current.processGeneration } == true ||
                expectedStartTicks?.let { it != current.processStartTicks } == true
            if (identityChanged && allowProcessIdentityTransition &&
                expectedProcessGeneration != current.processGeneration &&
                expectedStartTicks != current.processStartTicks
            ) {
                diagnosticsTransition.compareAndSet(null, current)
                return
            }
            if (identityChanged) {
                diagnosticsIdentityMismatch.set(true)
                return
            }
            diagnosticsBaseline.compareAndSet(null, current)
            diagnosticsLatest.set(current)
            diagnosticsSamples.incrementAndGet()
            javaHeapAllocatedPeakBytes.accumulateAndGet(
                current.memory.javaHeapAllocatedBytes,
                ::maxOf,
            )
            nativeHeapAllocatedPeakBytes.accumulateAndGet(
                current.memory.nativeHeapAllocatedBytes,
                ::maxOf,
            )
        }
    }

    private data class RemoteMemorySample(
        val totalPssKiB: Int,
        val nativePssKiB: Int,
        val dalvikPssKiB: Int,
        val thermalStatus: Int,
    )

    private data class RemoteResourceSnapshot(
        val sampleCount: Int,
        val totalPssBaselineKiB: Int,
        val totalPssPeakKiB: Int,
        val totalPssFinalKiB: Int,
        val nativePssBaselineKiB: Int,
        val nativePssPeakKiB: Int,
        val nativePssFinalKiB: Int,
        val dalvikPssPeakKiB: Int,
        val thermalBaseline: Int,
        val thermalPeak: Int,
        val thermalFinal: Int,
        val processMissing: Boolean,
        val processGeneration: Long? = null,
        val diagnosticsSampleCount: Int = 0,
        val diagnosticsFailureCount: Int = 0,
        val diagnosticsIdentityMismatch: Boolean = false,
        val javaHeapAllocatedPeakBytes: Long = 0L,
        val nativeHeapAllocatedPeakBytes: Long = 0L,
        val diagnosticsBaseline: SourceSeparationProcessDiagnostics? = null,
        val diagnosticsFinal: SourceSeparationProcessDiagnostics? = null,
        val diagnosticsTransition: SourceSeparationProcessDiagnostics? = null,
    ) {
        fun toJson(): JSONObject {
            val baselineMemory = diagnosticsBaseline?.memory
            val finalMemory = diagnosticsFinal?.memory
            return JSONObject()
                .put("pid", diagnosticsFinal?.pid ?: diagnosticsBaseline?.pid ?: JSONObject.NULL)
                .put("processGeneration", processGeneration ?: JSONObject.NULL)
                .put("pssSampleCount", sampleCount)
                .put("diagnosticsSampleCount", diagnosticsSampleCount)
                .put("diagnosticsFailureCount", diagnosticsFailureCount)
                .put("diagnosticsIdentityMismatch", diagnosticsIdentityMismatch)
                .put(
                    "diagnosticsIdentityTransitionObserved",
                    diagnosticsTransition != null,
                )
                .put(
                    "diagnosticsTransition",
                    diagnosticsTransition?.let(::processIdentityJson) ?: JSONObject.NULL,
                )
                .put("processMissing", processMissing)
                .put("totalPssBaselineKiB", totalPssBaselineKiB)
                .put("totalPssPeakKiB", totalPssPeakKiB)
                .put("totalPssFinalKiB", totalPssFinalKiB)
                .put("nativePssBaselineKiB", nativePssBaselineKiB)
                .put("nativePssPeakKiB", nativePssPeakKiB)
                .put("nativePssFinalKiB", nativePssFinalKiB)
                .put("dalvikPssPeakKiB", dalvikPssPeakKiB)
                .put("javaHeapAllocatedPeakBytes", javaHeapAllocatedPeakBytes)
                .put("nativeHeapAllocatedPeakBytes", nativeHeapAllocatedPeakBytes)
                .put(
                    "javaHeapAllocatedFinalBytes",
                    finalMemory?.javaHeapAllocatedBytes ?: JSONObject.NULL,
                )
                .put(
                    "nativeHeapAllocatedFinalBytes",
                    finalMemory?.nativeHeapAllocatedBytes ?: JSONObject.NULL,
                )
                .put(
                    "runtimeMaxMemoryBytes",
                    finalMemory?.runtimeMaxMemoryBytes ?: baselineMemory?.runtimeMaxMemoryBytes
                        ?: JSONObject.NULL,
                )
                .put(
                    "processCpuTimeDeltaMs",
                    monotonicDelta(
                        baselineMemory?.processCpuTimeMs,
                        finalMemory?.processCpuTimeMs,
                    ) ?: JSONObject.NULL,
                )
                .put("thermalBaseline", thermalBaseline)
                .put("thermalPeak", thermalPeak)
                .put("thermalFinal", thermalFinal)
                .put(
                    "artRuntime",
                    artRuntimeJson(
                        diagnosticsBaseline?.memory?.artRuntime,
                        diagnosticsFinal?.memory?.artRuntime,
                    ),
                )
        }

        private fun artRuntimeJson(
            baseline: SourceSeparationArtRuntimeDiagnostics?,
            final: SourceSeparationArtRuntimeDiagnostics?,
        ) = JSONObject()
            .put("baseline", artCountersJson(baseline))
            .put("final", artCountersJson(final))
            .put("delta", JSONObject()
                .put("gcCount", jsonValue(monotonicDelta(baseline?.gcCount, final?.gcCount)))
                .put("gcTimeMs", jsonValue(monotonicDelta(baseline?.gcTimeMs, final?.gcTimeMs)))
                .put(
                    "bytesAllocated",
                    jsonValue(monotonicDelta(baseline?.bytesAllocated, final?.bytesAllocated)),
                )
                .put("bytesFreed", jsonValue(monotonicDelta(baseline?.bytesFreed, final?.bytesFreed)))
                .put(
                    "blockingGcCount",
                    jsonValue(monotonicDelta(baseline?.blockingGcCount, final?.blockingGcCount)),
                )
                .put(
                    "blockingGcTimeMs",
                    jsonValue(monotonicDelta(baseline?.blockingGcTimeMs, final?.blockingGcTimeMs)),
                ))

        private fun artCountersJson(value: SourceSeparationArtRuntimeDiagnostics?) = JSONObject()
            .put("gcCount", jsonValue(value?.gcCount))
            .put("gcTimeMs", jsonValue(value?.gcTimeMs))
            .put("bytesAllocated", jsonValue(value?.bytesAllocated))
            .put("bytesFreed", jsonValue(value?.bytesFreed))
            .put("blockingGcCount", jsonValue(value?.blockingGcCount))
            .put("blockingGcTimeMs", jsonValue(value?.blockingGcTimeMs))

        private fun monotonicDelta(baseline: Long?, final: Long?): Long? =
            if (baseline != null && final != null && final >= baseline) final - baseline else null

        private fun jsonValue(value: Long?): Any = value ?: JSONObject.NULL
    }

    private companion object {
        const val ARG_MODEL_ID = "bssMultistemModelId"
        const val ARG_REPLACEMENT_MODEL_ID = "bssMultistemReplacementModelId"
        const val ARG_SOURCE_PATH = "bssMultistemSourcePath"
        const val ARG_SOURCE_SHA256 = "bssMultistemSourceSha256"
        const val ARG_REFERENCE_PATH = "bssMultistemReferencePath"
        const val ARG_RUN_ID = "bssMultistemRunId"
        const val ARG_ALLOWED_SEEK_UNDERRUNS = "bssMultistemAllowedSeekUnderruns"
        const val ARG_DELETE_ACTIVE_CACHE = "bssMultistemDeleteActiveCache"
        const val ARG_APP_COMMIT = "bssAppCommit"
        const val ARG_TEST_COMMIT = "bssTestCommit"
        const val ARG_CONTROLLED_TERMINATION_GRACE_MS =
            "bssMultistemControlledTerminationGraceMs"
        const val REPORT_DIRECTORY = "source-separation/multistem-product-device-reports"
        const val MAIN_DEATH_SCENARIO_DIRECTORY =
            "source-separation/multistem-main-death-scenarios"
        const val SONG_TITLE_PREFIX = "BSS Phase 6 Product"
        const val SOURCE_DURATION_MS = 30_000L
        const val MEDIA_SESSION_TIMEOUT_SECONDS = 30L
        const val MEDIA_SESSION_TIMEOUT_MS = MEDIA_SESSION_TIMEOUT_SECONDS * 1_000L
        const val MEDIA_SESSION_POLL_INTERVAL_MS = 50L
        const val MEDIA_SESSION_SEEK_TOLERANCE_MS = 750L
        const val SERVICE_RECREATION_SETTLE_MS = 500L
        const val PRODUCER_AHEAD_READY_WINDOWS = 2
        const val PRODUCER_AHEAD_READY_TIMEOUT_MS = 10L * 60L * 1_000L
        const val REMOTE_PROCESS_TIMEOUT_MS = 30_000L
        const val MAX_TERMINAL_CONTROL_RESPONSE_MS = 10_000L
        const val IN_FLIGHT_CONTROL_DELAY_MS = 750L
        const val REMOTE_RESOURCE_SAMPLE_INTERVAL_MS = 100L
        const val REMOTE_DIAGNOSTICS_SAMPLE_INTERVAL_MS = 5_000L
        const val OFFICIAL_SIX_STEM_MODEL_ID =
            "htdemucs_6s_core_canonical_7p8s_fp32_v1_0_0"
        val SHA256 = Regex("^[0-9a-f]{64}$")
        val SHA1 = Regex("^[0-9a-f]{40}$")
        val SAFE_NAME = Regex("^[A-Za-z0-9._-]{1,160}$")
        val SAFE_RELATIVE_PATH = Regex("^[A-Za-z0-9._/-]{1,240}$")
        val EXPECTED_MODEL_IDS = setOf(
            "htdemucs_6s_core_canonical_7p8s_fp32_v1_0_0",
            "htdemucs_4s_core_canonical_7p8s_fp32_v1_0_0",
            "htdemucs_6s_guitar_ft_core_canonical_7p8s_fp32_v1_0_0",
        )

        private fun processIdentityJson(
            diagnostics: SourceSeparationProcessDiagnostics,
        ): JSONObject = JSONObject()
            .put("pid", diagnostics.pid)
            .put("processGeneration", diagnostics.processGeneration)
            .put("processStartTicks", diagnostics.processStartTicks)
    }
}
