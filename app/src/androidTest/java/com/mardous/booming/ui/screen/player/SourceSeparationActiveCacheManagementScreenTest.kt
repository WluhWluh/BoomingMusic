package com.mardous.booming.ui.screen.player

import android.content.ContentUris
import android.content.ContentValues
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import androidx.navigation.fragment.NavHostFragment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mardous.booming.R
import com.mardous.booming.data.model.Song
import com.mardous.booming.playback.Playback
import com.mardous.booming.playback.PlaybackService
import com.mardous.booming.playback.processor.SourceSeparationMixAudioProcessor
import com.mardous.booming.separation.HtdemucsSourceSeparationEngineResult
import com.mardous.booming.separation.SourceSeparationMultiStemPlaybackSelectionStore
import com.mardous.booming.separation.SourceSeparationMultiStemProductFacade
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheMutationResult
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwareCacheRepository
import com.mardous.booming.ui.screen.MainActivity
import com.mardous.booming.util.BLACKLIST_ENABLED
import com.mardous.booming.util.IGNORE_AUDIO_FOCUS
import com.mardous.booming.util.MINIMUM_SONG_DURATION
import com.mardous.booming.util.SOURCE_SEPARATION_AUTO_START
import com.mardous.booming.util.WHITELIST_ENABLED
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

@RunWith(AndroidJUnit4::class)
class SourceSeparationActiveCacheManagementScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun activeCompletedMultistemCacheDeletesThroughRealManagementPage() {
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
            ?: "active-panel-delete-${System.currentTimeMillis()}"
        val koin = GlobalContext.get()
        val facade = koin.get<SourceSeparationMultiStemProductFacade>()
        val repository = koin.get<SourceSeparationModelAwareCacheRepository>()
        val selectionStore = koin.get<SourceSeparationMultiStemPlaybackSelectionStore>()
        val processor = koin.get<SourceSeparationMixAudioProcessor>()
        val preferences = koin.get<SharedPreferences>()
        val originalSelection = selectionStore.selectedModelId()
        val preferenceSnapshot = snapshotPreferences(preferences)
        val reportFile = File(context.filesDir, REPORT_DIRECTORY).apply { mkdirs() }
            .resolve("$runId.json")
        val report = JSONObject()
            .put("runId", runId)
            .put("mode", "active-cache-management-panel-delete")
            .put("modelId", modelId)
            .put("status", "running")
            .put("deviceModel", android.os.Build.MODEL)
            .put("sdk", android.os.Build.VERSION.SDK_INT)
            .put("abi", currentProcessAbi())
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
            val separated = facade.separate(song = song, modelId = modelId)
            val manifest = when (separated) {
                is HtdemucsSourceSeparationEngineResult.Completed -> separated.manifest
                is HtdemucsSourceSeparationEngineResult.AlreadyCompleted -> separated.manifest
                is HtdemucsSourceSeparationEngineResult.Busy -> error("Unexpected busy result")
            }
            cacheKey = manifest.cacheKey
            val expectedStemIds = requireNotNull(manifest.output).stems
                .sortedBy { it.order }
                .map { it.stemId.value }
            assertEquals(6, expectedStemIds.size)
            selectionStore.select(modelId)

            val mediaController = MediaController.Builder(
                context,
                SessionToken(context, ComponentName(context, PlaybackService::class.java)),
            ).buildAsync().get(MEDIA_SESSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            controller = mediaController
            onControllerThread(mediaController) {
                mediaController.volume = 0f
                mediaController.setMediaItem(song.toMediaItem())
                mediaController.prepare()
            }
            waitForController(mediaController, "source preparation") {
                mediaController.currentMediaItem?.mediaId == song.id.toString() &&
                    mediaController.duration > 0L
            }
            val enableResult = onControllerThread(mediaController) {
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
            onControllerThread(mediaController) { mediaController.play() }
            val syncResult = onControllerThread(mediaController) {
                mediaController.sendCustomCommand(
                    SessionCommand(Playback.SYNC_SOURCE_SEPARATION_PLAYBACK, Bundle.EMPTY),
                    Bundle().apply {
                        putBoolean(Playback.EXTRA_SOURCE_SEPARATION_ALLOW_NEW_SESSION, true)
                        putBoolean(Playback.EXTRA_SOURCE_SEPARATION_PREFER_COMPLETED_CACHE, true)
                    },
                )
            }.get(MEDIA_SESSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertEquals(SessionResult.RESULT_SUCCESS, syncResult.resultCode)
            waitForController(mediaController, "active multistem adoption") {
                mediaController.playWhenReady &&
                    mediaController.playbackState == Player.STATE_READY &&
                    processor.dataPlaneStemIds() == expectedStemIds
            }
            waitForCondition("activity PlayerViewModel synchronization") {
                SourceSeparationForegroundWorkerDebugBridge.status().contains(
                    "playbackSong=${song.id}",
                )
            }

            compose.activityRule.scenario.onActivity { activity ->
                val navHost = activity.supportFragmentManager
                    .findFragmentById(R.id.fragment_container) as NavHostFragment
                navHost.navController.navigate(R.id.nav_source_separation_settings)
            }
            val manageCaches = context.getString(R.string.source_separation_manage_caches)
            compose.waitUntil(UI_TIMEOUT_MS) {
                compose.onAllNodesWithText(manageCaches).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(manageCaches)
                .performScrollTo()
                .assertIsDisplayed()
                .performClick()
            compose.waitUntil(UI_TIMEOUT_MS) {
                compose.onAllNodesWithTag("source-separation-cache-entry:${manifest.cacheKey}")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("source-separation-cache-delete:${manifest.cacheKey}")
                .performScrollTo()
                .performClick()
            compose.waitUntil(UI_TIMEOUT_MS) {
                compose.onAllNodesWithTag("source-separation-cache-entry:${manifest.cacheKey}")
                    .fetchSemanticsNodes().isEmpty()
            }
            compose.onAllNodesWithTag("source-separation-cache-entry:${manifest.cacheKey}")
                .assertCountEquals(0)
            waitForController(mediaController, "active cache deletion") {
                mediaController.currentMediaItem?.mediaId == song.id.toString() &&
                    mediaController.playWhenReady &&
                    mediaController.playbackState == Player.STATE_READY &&
                    processor.dataPlaneStemIds().isEmpty()
            }
            assertFalse(repository.entries().any { it.cacheKey == manifest.cacheKey })
            assertFalse(repository.isLeased(manifest.cacheKey))
            assertEquals(modelId, selectionStore.selectedModelId())
            assertTrue(SourceSeparationForegroundWorkerDebugBridge.status()
                .contains("playbackEnabled=false"))
            report.put("status", "complete")
                .put("cacheKey", manifest.cacheKey)
                .put("stemIdsBeforeDelete", JSONArray(expectedStemIds))
                .put("uiEntryRemoved", true)
                .put("repositoryEntryRemoved", true)
                .put("playbackLeaseReleased", true)
                .put("separatedPlaybackDisabled", true)
                .put("originalTransportRetained", true)
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
                    onControllerThread(mediaController) {
                        mediaController.pause()
                        mediaController.clearMediaItems()
                        mediaController.release()
                    }
                }
            }
            runCatching { context.stopService(Intent(context, PlaybackService::class.java)) }
            selectionStore.select(originalSelection)
            restorePreferences(preferences, preferenceSnapshot)
            cacheKey?.let { key ->
                repeat(20) {
                    if (!repository.isLeased(key)) return@repeat
                    SystemClock.sleep(POLL_INTERVAL_MS)
                }
                runCatching { repository.delete(key) }
            }
            mediaUri?.let { uri -> runCatching { context.contentResolver.delete(uri, null, null) } }
        }
    }

    private fun importIntoMediaStore(context: Context, source: File, runId: String): Uri {
        val uri = requireNotNull(context.contentResolver.insert(
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, "$runId.wav")
                put(MediaStore.Audio.Media.TITLE, "$SONG_TITLE_PREFIX $runId")
                put(MediaStore.Audio.Media.ARTIST, "Scott Buckley")
                put(MediaStore.Audio.Media.ALBUM, "Phase 6 Validation")
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                put(MediaStore.Audio.Media.IS_MUSIC, 1)
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/BoomingSS Validation")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            },
        ))
        try {
            context.contentResolver.openOutputStream(uri, "w")!!.use { output ->
                source.inputStream().use { input -> input.copyTo(output, 256 * 1024) }
            }
            check(context.contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.Audio.Media.IS_PENDING, 0)
            }, null, null) == 1)
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

    private fun waitForController(
        controller: MediaController,
        operation: String,
        predicate: () -> Boolean,
    ) = waitForCondition(operation) { onControllerThread(controller, predicate) }

    private fun waitForCondition(operation: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + UI_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        error("Timed out waiting for $operation.")
    }

    private fun <T> onControllerThread(controller: MediaController, block: () -> T): T {
        if (android.os.Looper.myLooper() == controller.applicationLooper) return block()
        val result = AtomicReference<Result<T>>()
        val latch = CountDownLatch(1)
        android.os.Handler(controller.applicationLooper).post {
            result.set(runCatching(block))
            latch.countDown()
        }
        check(latch.await(MEDIA_SESSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            "MediaController operation timed out."
        }
        return result.get().getOrThrow()
    }

    private fun snapshotPreferences(preferences: SharedPreferences): Map<String, Any?> =
        PREFERENCE_KEYS.associateWith { key -> preferences.all[key] }

    private fun restorePreferences(
        preferences: SharedPreferences,
        snapshot: Map<String, Any?>,
    ) {
        preferences.edit().apply {
            PREFERENCE_KEYS.forEach { key ->
                when (val value = snapshot[key]) {
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    else -> remove(key)
                }
            }
        }.commit()
    }

    private fun currentProcessAbi(): String {
        val abis = if (android.os.Process.is64Bit()) {
            android.os.Build.SUPPORTED_64_BIT_ABIS
        } else {
            android.os.Build.SUPPORTED_32_BIT_ABIS
        }
        return requireNotNull(abis.firstOrNull()) { "The current process has no reported ABI." }
    }

    private companion object {
        const val ARG_MODEL_ID = "bssMultistemModelId"
        const val ARG_SOURCE_PATH = "bssMultistemSourcePath"
        const val ARG_RUN_ID = "bssMultistemRunId"
        const val OFFICIAL_SIX_STEM_MODEL_ID =
            "htdemucs_6s_core_canonical_7p8s_fp32_v1_0_0"
        const val REPORT_DIRECTORY = "source-separation/multistem-product-device-reports"
        const val SONG_TITLE_PREFIX = "BSS Phase 6 Product"
        const val SOURCE_DURATION_MS = 30_000L
        const val MEDIA_SESSION_TIMEOUT_SECONDS = 30L
        const val UI_TIMEOUT_MS = 60_000L
        const val POLL_INTERVAL_MS = 50L
        val SAFE_NAME = Regex("^[A-Za-z0-9._-]{1,160}$")
        val SAFE_RELATIVE_PATH = Regex("^[A-Za-z0-9._/-]{1,240}$")
        val PREFERENCE_KEYS = setOf(
            MINIMUM_SONG_DURATION,
            SOURCE_SEPARATION_AUTO_START,
            IGNORE_AUDIO_FOCUS,
            WHITELIST_ENABLED,
            BLACKLIST_ENABLED,
        )
    }
}
