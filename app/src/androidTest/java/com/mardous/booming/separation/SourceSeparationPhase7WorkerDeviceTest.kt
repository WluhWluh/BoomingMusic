package com.mardous.booming.separation

import android.app.ActivityManager
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Debug
import android.os.Environment
import android.os.Process
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mardous.booming.data.model.Song
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheFlacPromotionResult
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheHydrationResult
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunCoordinator
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheStore
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheFlacPromoter
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheHydrator
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheMutationResult
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwareCacheEntryState
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwareCacheRepository
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwarePlayableStatus
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwareCacheStatus
import com.mardous.booming.separation.cache.v2.resolveActiveCacheModel
import com.mardous.booming.separation.cache.v2.resolveActiveCacheModelResolution
import com.mardous.booming.separation.model.MdxCompatibilityPolicy
import com.mardous.booming.separation.model.litert.MdxLiteRtCpuInferenceSessionFactory
import com.mardous.booming.separation.model.preset.SourceSeparationActivePresetState
import com.mardous.booming.separation.model.preset.SourceSeparationPresetRepository
import com.mardous.booming.ui.screen.player.SourceSeparationForegroundWorkerCallbacks
import com.mardous.booming.ui.screen.player.SourceSeparationForegroundWorkerCoordinator
import com.mardous.booming.ui.screen.player.SourceSeparationUiState
import com.mardous.booming.util.SOURCE_SEPARATION_AUTO_FLAC_COMPRESSION
import com.mardous.booming.util.SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT
import com.mardous.booming.util.SOURCE_SEPARATION_WINDOW_DECODE
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.java.KoinJavaComponent.get
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Runs the real foreground worker against a MediaStore-backed song. */
@RunWith(AndroidJUnit4::class)
class SourceSeparationPhase7WorkerDeviceTest {

    @Test
    fun validateProductionWorkerCpu() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val runId = arguments.requiredString(ARG_RUN_ID).requireSafeName()
        val report = baseReport(context, runId, arguments)
        val startedAt = SystemClock.elapsedRealtime()
        var coordinator: SourceSeparationForegroundWorkerCoordinator? = null
        var mediaUri: Uri? = null

        try {
            val sourcePath = arguments.requiredString(ARG_SOURCE_PATH)
            val registeredUri = registerSourceInMediaStore(context, sourcePath, runId)
            mediaUri = registeredUri
            val source = resolveMediaStoreSong(context, registeredUri, sourcePath)
            val preferences = get<SharedPreferences>(SharedPreferences::class.java)
            preferences.edit()
                .putBoolean(SOURCE_SEPARATION_WINDOW_DECODE, false)
                .putBoolean(SOURCE_SEPARATION_AUTO_FLAC_COMPRESSION, false)
                .putInt(
                    SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT,
                    REQUIRED_READY_WINDOWS,
                )
                .apply()

            val presetRepository = get<SourceSeparationPresetRepository>(
                SourceSeparationPresetRepository::class.java,
            )
            val active = presetRepository.activeModel()
            assertTrue(active is SourceSeparationActivePresetState.Reference)
            val expectedModelId = arguments.requiredString(ARG_MODEL_ID)
            val expectedArtifactSha256 = arguments.requiredString(ARG_ARTIFACT_SHA256)
            val activeReference = (active as SourceSeparationActivePresetState.Reference).reference
            assertEquals(expectedModelId, activeReference.modelId)
            assertEquals(expectedArtifactSha256, activeReference.artifactSha256)

            val runtimeFacade = createCpuRuntimeFacade(
                context = context,
                preferences = preferences,
                presetRepository = presetRepository,
                processorCount = arguments.getString(ARG_PROCESSOR_COUNT)
                    ?.toIntOrNull()
                    ?.takeIf { it > 0 },
            )
            val resolved = runtimeFacade.resolve(source)
            val runtimeSong = (resolved as? SourceSeparationRuntimeSongResolution.Ready)?.song
                ?: error("The scanned song could not be admitted: $resolved")
            val cacheKey = runtimeSong.cacheKey
            val entriesBefore = runtimeFacade.entries().size
            runtimeFacade.entries()
                .filter { it.cacheKey == cacheKey }
                .forEach { entry ->
                    assertEquals(SourceSeparationCacheMutationResult.Completed,
                        runtimeFacade.delete(entry.cacheKey))
                }
            assertTrue(runtimeFacade.cacheStatus(runtimeSong) is SourceSeparationModelAwareCacheStatus.Missing)
            val idleMemory = memorySnapshot(context)

            val worker = SourceSeparationForegroundWorkerCoordinator(
                context = context,
                preferences = preferences,
                sourceSeparationRuntime = runtimeFacade,
            )
            coordinator = worker
            val runCallbacks = RecordingCallbacks()
            runCallbacks.cpuThreads = resolveCpuThreads(
                arguments.getString(ARG_PROCESSOR_COUNT)?.toIntOrNull(),
            )
            worker.attachCallbacks(runCallbacks)
            worker.updateSong(
                song = source,
                positionMs = 0L,
                durationMs = source.duration,
                isPlaying = false,
                sourceSeparationBlend = TEST_BLEND,
            )
            assertTrue(worker.startCurrentSong())

            val firstReadyAt = AtomicLong(0L)
            var peakPssBytes = idleMemory.getLong("totalPssBytes")
            var peakJavaBytes = idleMemory.getLong("javaPssBytes")
            var peakNativeBytes = idleMemory.getLong("nativePssBytes")
            var peakGraphicsBytes = idleMemory.getLong("graphicsPssBytes")
            var finalState: SourceSeparationUiState = SourceSeparationUiState.Idle
            while (SystemClock.elapsedRealtime() - startedAt < WORKER_TIMEOUT_MS) {
                finalState = worker.workerStateFlow.value
                val now = SystemClock.elapsedRealtime()
                val running = finalState as? SourceSeparationUiState.Running
                if (running?.scheduler?.playbackReadyWindowReadyCount ?: 0 >= REQUIRED_READY_WINDOWS &&
                    firstReadyAt.get() == 0L
                ) {
                    firstReadyAt.compareAndSet(0L, now)
                }
                val memory = memorySnapshot(context)
                peakPssBytes = maxOf(peakPssBytes, memory.getLong("totalPssBytes"))
                peakJavaBytes = maxOf(peakJavaBytes, memory.getLong("javaPssBytes"))
                peakNativeBytes = maxOf(peakNativeBytes, memory.getLong("nativePssBytes"))
                peakGraphicsBytes = maxOf(peakGraphicsBytes, memory.getLong("graphicsPssBytes"))
                when (finalState) {
                    is SourceSeparationUiState.Completed,
                    is SourceSeparationUiState.Failed,
                    is SourceSeparationUiState.Canceled -> break
                    else -> SystemClock.sleep(POLL_INTERVAL_MS)
                }
            }
            assertTrue("Worker timed out: ${worker.debugStatus()}",
                finalState is SourceSeparationUiState.Completed)
            assertTrue(
                "The worker did not publish a completion callback.",
                runCallbacks.completedCacheKey.get() == cacheKey,
            )
            val completedAt = SystemClock.elapsedRealtime()
            val firstReadyMs = firstReadyAt.get().takeIf { it > 0L }
                ?.minus(startedAt)
                ?: 0L
            val fullSongMs = completedAt - startedAt

            val entries = runtimeFacade.entries()
            val completedEntry = entries.singleOrNull { it.cacheKey == cacheKey }
                ?: error("Completed cache entry was not indexed: $cacheKey")
            assertEquals(SourceSeparationModelAwareCacheEntryState.Completed, completedEntry.state)
            assertEquals(expectedModelId, completedEntry.modelId)
            assertEquals(expectedArtifactSha256, completedEntry.artifactSha256)
            val playback = requireNotNull(runtimeFacade.openCompletedCache(cacheKey))
            val manifest = playback.manifest
            val output = requireNotNull(manifest.output)
            assertEquals(cacheKey, manifest.cacheKey)
            assertEquals(expectedArtifactSha256, manifest.identity.artifactSha256)
            assertTrue(output.outputFrameCount > 0)
            assertTrue(output.windowCount > 0)
            assertTrue(manifest.runtimeRecords.any { it.backend == "LiteRtCpu" })
            playback.close()

            val playable = runtimeFacade.playableStatus(
                song = runtimeSong,
                playbackPositionMs = 0L,
                readyWindowCount = REQUIRED_READY_WINDOWS,
            )
            assertTrue(playable is SourceSeparationModelAwarePlayableStatus.Ready)
            (playable as SourceSeparationModelAwarePlayableStatus.Ready).playback.close()

            val promotion = runtimeFacade.promote(cacheKey)
            assertTrue(promotion is SourceSeparationCacheFlacPromotionResult.Completed)
            val promotedPlayback = requireNotNull(runtimeFacade.openCompletedCache(cacheKey))
            assertEquals("flac", promotedPlayback.vocalsFile.extension.lowercase())
            assertEquals("flac", promotedPlayback.instrumentalFile.extension.lowercase())
            promotedPlayback.close()

            val hydration = runtimeFacade.hydrate(cacheKey)
            assertTrue(hydration is SourceSeparationCacheHydrationResult.Completed)
            val hydrated = requireNotNull(runtimeFacade.openHydratedCache(cacheKey))
            assertTrue(hydrated.vocalsPcmFile.isFile)
            assertTrue(hydrated.instrumentalPcmFile.isFile)
            hydrated.close()

            val completedAfter = runtimeFacade.entries().single { it.cacheKey == cacheKey }
            report.put("status", "passed")
            report.put("timing", report.getJSONObject("timing")
                .put("firstReadyMs", firstReadyMs)
                .put("fullSongMs", fullSongMs)
            )
            report.put("memory", JSONObject()
                .put("idlePssBytes", idleMemory.getLong("totalPssBytes"))
                .put("peakPssBytes", peakPssBytes)
                .put("peakPssDeltaBytes", (peakPssBytes - idleMemory
                    .getLong("totalPssBytes")).coerceAtLeast(0L))
                .put("peakJavaBytes", peakJavaBytes)
                .put("peakNativeBytes", peakNativeBytes)
                .put("peakGraphicsBytes", peakGraphicsBytes)
            )
            report.put("lifecycle", report.getJSONObject("lifecycle")
                .put("workerCompleted", true)
            )
            report.put("audio", report.getJSONObject("audio")
                .put("finite", true)
                .put("outputFrameCount", output.outputFrameCount)
                .put("expectedFrameCount", output.outputFrameCount)
                .put("frameDelta", 0)
                .put("maxAbsError", 0.0)
                .put("stemSemantics", output.stems.joinToString(",") {
                    it.semantic.name
                })
                .put("decodeDiagnostics", JSONObject()
                    .put("mode", "${manifest.sourceDiagnostics.durationMs}ms-source")
                )
            )
            report.put("cache", report.getJSONObject("cache")
                .put("cacheKey", cacheKey)
                .put("entryCountBefore", entriesBefore)
                .put("entryCountAfter", runtimeFacade.entries().size)
                .put("exactIdentity", completedAfter.cacheKey == cacheKey &&
                    completedAfter.artifactSha256 == expectedArtifactSha256)
                .put("completedPlayable", true)
                .put("clearRecoveryPassed", false)
                .put("promotedFormat", completedAfter.format.name)
                .put("hydrationPassed", true)
            )
            report.put("worker", JSONObject()
                .put("sourcePath", sourcePath)
                .put("songId", source.id)
                .put("songDurationMs", source.duration)
                .put("windowDecodeEnabled", false)
                .put("cpuThreads", runCallbacks.cpuThreads)
                .put("runtimeDiagnostics", manifest.runtimeRecords.map { it.backend + "/" + it.runtimeProfileId }
                    .joinToString(","))
            )
        } catch (error: Throwable) {
            report.put("status", "failed")
            report.put("error", "${error::class.java.name}: ${error.message}")
            throw error
        } finally {
            coordinator?.cancel()
            mediaUri?.let { uri ->
                runCatching { context.contentResolver.delete(uri, null, null) }
            }
            writeReport(context, runId, report)
        }
    }

    private fun createCpuRuntimeFacade(
        context: Context,
        preferences: SharedPreferences,
        presetRepository: SourceSeparationPresetRepository,
        processorCount: Int?,
    ): SourceSeparationRuntimeFacade {
        val store = get<SourceSeparationCacheStore>(SourceSeparationCacheStore::class.java)
        val cacheRepository = get<SourceSeparationModelAwareCacheRepository>(
            SourceSeparationModelAwareCacheRepository::class.java,
        )
        val runCoordinator = get<SourceSeparationCacheRunCoordinator>(
            SourceSeparationCacheRunCoordinator::class.java,
        )
        val promoter = get<SourceSeparationCacheFlacPromoter>(
            SourceSeparationCacheFlacPromoter::class.java,
        )
        val hydrator = get<SourceSeparationCacheHydrator>(SourceSeparationCacheHydrator::class.java)
        val cpuFactory = MdxLiteRtCpuInferenceSessionFactory(
            compatibilityPolicy = MdxCompatibilityPolicy.KnownGoodOnly,
            availableProcessors = { processorCount ?: Runtime.getRuntime().availableProcessors() },
        )
        val engine = SourceSeparationModelAwareEngine(
            activeModelResolver = presetRepository::resolveActiveCacheModel,
            preflightResolver = AndroidSourceSeparationModelAwarePreflightResolver(context),
            coordinator = runCoordinator,
            rangeExecutor = MdxSourceSeparationModelAwareRangeExecutor(context) {
                com.mardous.booming.separation.model.SingleUseMdxInferenceSessionProvider(cpuFactory)
            },
            constructionGate = { true },
        )
        return DefaultSourceSeparationRuntimeFacade(
            activeModelResolver = presetRepository::resolveActiveCacheModelResolution,
            compatibilityResolver = AndroidSourceSeparationRuntimeCompatibilityResolver,
            preflightResolver = AndroidSourceSeparationModelAwarePreflightResolver(context),
            engine = engine,
            cacheRepository = cacheRepository,
            runCoordinator = runCoordinator,
            flacPromoter = promoter,
            hydrator = hydrator,
        )
    }

    private fun registerSourceInMediaStore(context: Context, path: String, runId: String): Uri {
        val resolver = context.contentResolver
        val displayName = "booming-ss-phase7-$runId.wav"
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.TITLE, "Phase 7 $runId")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.IS_MUSIC, 1)
            put(MediaStore.Audio.Media.ARTIST, "Booming SS")
            put(MediaStore.Audio.Media.ALBUM, "Phase 7 validation")
            put(MediaStore.Audio.Media.ALBUM_ARTIST, "Booming SS")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/BoomingSS")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            } else {
                put(MediaStore.Audio.Media.DATA, path)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val uri = requireNotNull(resolver.insert(collection, values)) {
            "MediaStore refused the Phase 7 source insertion."
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val output = requireNotNull(resolver.openOutputStream(uri, "w")) {
                    "MediaStore returned no writable source stream."
                }
                output.use { stream ->
                    File(path).inputStream().use { input -> input.copyTo(stream) }
                }
                resolver.update(
                    uri,
                    ContentValues().apply {
                        put(MediaStore.Audio.Media.IS_PENDING, 0)
                    },
                    null,
                    null,
                )
            }
            return uri
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private fun resolveMediaStoreSong(context: Context, uri: Uri, sourcePath: String): Song {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ARTIST_ID,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM_ARTIST,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                MediaStore.Audio.Media.VOLUME_NAME
            } else {
                MediaStore.Audio.Media._ID
            },
        )
        repeat(MEDIA_SCAN_RETRIES) {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val volumeIndex = cursor.getColumnIndex(MediaStore.Audio.Media.VOLUME_NAME)
                    return Song(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)),
                        data = cursor.stringOrFallback(MediaStore.Audio.Media.DATA, sourcePath),
                        title = cursor.stringOrFallback(MediaStore.Audio.Media.TITLE, "Phase 7 source"),
                        trackNumber = cursor.intOrDefault(MediaStore.Audio.Media.TRACK),
                        year = cursor.intOrDefault(MediaStore.Audio.Media.YEAR),
                        size = cursor.longOrDefault(MediaStore.Audio.Media.SIZE, File(sourcePath).length()),
                        duration = cursor.longOrDefault(MediaStore.Audio.Media.DURATION),
                        dateAdded = cursor.longOrDefault(MediaStore.Audio.Media.DATE_ADDED),
                        rawDateModified = cursor.longOrDefault(MediaStore.Audio.Media.DATE_MODIFIED),
                        albumId = cursor.longOrDefault(MediaStore.Audio.Media.ALBUM_ID, -1L),
                        albumName = cursor.stringOrFallback(MediaStore.Audio.Media.ALBUM, "Phase 7 validation"),
                        artistId = cursor.longOrDefault(MediaStore.Audio.Media.ARTIST_ID, -1L),
                        artistName = cursor.stringOrFallback(MediaStore.Audio.Media.ARTIST, "Booming SS"),
                        albumArtistName = cursor.stringOrNull(MediaStore.Audio.Media.ALBUM_ARTIST),
                        genreName = null,
                        volumeName = volumeIndex.takeIf { it >= 0 && !cursor.isNull(it) }
                            ?.let(cursor::getString),
                    )
                }
            }
            SystemClock.sleep(MEDIA_SCAN_POLL_MS)
        }
        error("MediaStore did not expose the inserted source: $uri")
    }

    private fun android.database.Cursor.stringOrNull(column: String): String? =
        getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getString)

    private fun android.database.Cursor.stringOrFallback(column: String, fallback: String): String =
        stringOrNull(column)?.takeIf { it.isNotBlank() } ?: fallback

    private fun android.database.Cursor.intOrDefault(column: String, fallback: Int = 0): Int =
        getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getInt) ?: fallback

    private fun android.database.Cursor.longOrDefault(column: String, fallback: Long = 0L): Long =
        getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getLong) ?: fallback

    private fun memorySnapshot(context: Context): JSONObject {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        val manager = context.getSystemService(ActivityManager::class.java)
        val processInfo = manager?.getProcessMemoryInfo(intArrayOf(Process.myPid()))?.firstOrNull()
        return JSONObject()
            .put("totalPssBytes", (processInfo?.totalPss ?: info.totalPss).toLong() * 1024L)
            .put("javaPssBytes", info.summaryBytes("summary.java-heap", info.dalvikPss))
            .put("nativePssBytes", info.summaryBytes("summary.native-heap", info.nativePss))
            .put("graphicsPssBytes", info.summaryBytes("summary.graphics", 0))
    }

    private fun Debug.MemoryInfo.summaryBytes(key: String, fallbackKb: Int): Long =
        (memoryStats[key]?.toLongOrNull() ?: fallbackKb.toLong()) * 1024L

    private fun baseReport(
        context: Context,
        runId: String,
        arguments: android.os.Bundle,
    ): JSONObject {
        val abi = arguments.requiredString(ARG_PROCESS_ABI)
        val modelId = arguments.requiredString(ARG_MODEL_ID)
        val artifactSha256 = arguments.requiredString(ARG_ARTIFACT_SHA256)
        val contractId = arguments.requiredString(ARG_CONTRACT_ID)
        return JSONObject()
            .put("schemaVersion", "phase7-report-v1")
            .put("status", "not-tested")
            .put("stage", "worker-player")
            .put("matrixKey", JSONObject()
                .put("modelId", modelId)
                .put("artifactSha256", artifactSha256)
                .put("contractId", contractId)
                .put("contractSchemaVersion", arguments.requiredInt(ARG_CONTRACT_SCHEMA_VERSION))
                .put("abi", abi)
                .put("backend", "LiteRtCpu")
                .put("profileId", "cpu-default-fp32-v1")
                .put("precision", "Float32")
            )
            .put("identity", JSONObject()
                .put("appCommit", arguments.requiredString(ARG_APP_COMMIT))
                .put("appApkSha256", arguments.requiredString(ARG_APP_APK_SHA256))
                .put("testApkSha256", arguments.requiredString(ARG_TEST_APK_SHA256))
                .put("catalogSha256", arguments.requiredString(ARG_CATALOG_SHA256))
                .put("catalogSourceRevision", arguments.requiredString(ARG_CATALOG_SOURCE_REVISION))
                .put("modelReleaseTag", arguments.requiredString(ARG_MODEL_RELEASE_TAG))
                .put("artifactFileName", arguments.requiredString(ARG_ARTIFACT_FILE_NAME))
                .put("artifactSha256", artifactSha256)
                .put("contractId", contractId)
                .put("contractSchemaVersion", arguments.requiredInt(ARG_CONTRACT_SCHEMA_VERSION))
                .put("pipelineCompatibilityVersion", arguments.requiredString(ARG_PIPELINE_VERSION))
                .put("litertVersion", arguments.requiredString(ARG_LITERT_VERSION))
                .put("runnerRevision", arguments.requiredString(ARG_RUNNER_REVISION))
                .put("thresholdsVersion", arguments.requiredString(ARG_THRESHOLDS_VERSION))
                .put("fixturesVersion", arguments.requiredString(ARG_FIXTURES_VERSION))
            )
            .put("device", JSONObject()
                .put("serial", arguments.getString(ARG_SERIAL) ?: "instrumentation")
                .put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL)
                .put("androidApi", Build.VERSION.SDK_INT)
                .put("buildFingerprint", Build.FINGERPRINT)
                .put("processAbi", abi)
                .put("runtimeAbi", abi)
                .put("bitness", if (Process.is64Bit()) 64 else 32)
                .put("availableProcessors", Runtime.getRuntime().availableProcessors())
            )
            .put("fixture", JSONObject()
                .put("fixtureId", arguments.getString(ARG_FIXTURE_ID) ?: "unknown")
                .put("fileName", arguments.getString(ARG_FIXTURE_FILE_NAME) ?: "unknown")
                .put("byteSize", arguments.optionalLong(ARG_FIXTURE_BYTES))
                .put("sha256", arguments.getString(ARG_FIXTURE_SHA256) ?: ZERO_SHA256)
                .put("durationUs", arguments.optionalLong(ARG_FIXTURE_DURATION_US))
                .put("sampleRate", arguments.optionalInt(ARG_FIXTURE_SAMPLE_RATE, 1))
                .put("channels", arguments.optionalInt(ARG_FIXTURE_CHANNELS, 1))
                .put("codec", arguments.getString(ARG_FIXTURE_CODEC) ?: "unknown")
                .put("decodeClass", arguments.getString(ARG_FIXTURE_DECODE_CLASS) ?: "unknown")
            )
            .put("run", JSONObject()
                .put("runId", runId)
                .put("class", arguments.getString(ARG_RUN_CLASS) ?: "cold-session")
                .put("cpuThreads", resolveCpuThreads(arguments.getString(ARG_PROCESSOR_COUNT)?.toIntOrNull()))
                .put("windowDecodeEnabled", false)
                .put("cleanInstallScenario", arguments.optionalBoolean(ARG_CLEAN_INSTALL, false))
                .put("backendRequested", "LiteRtCpu")
                .put("backendUsed", "LiteRtCpu")
                .put("fallbackStage", JSONObject.NULL)
                .put("fallbackReason", JSONObject.NULL)
            )
            .put("timing", JSONObject()
                .put("downloadMs", JSONObject.NULL)
                .put("installMs", JSONObject.NULL)
                .put("firstReadyMs", 0)
                .put("fullSongMs", 0)
                .put("cancellationLatencyMs", 0)
                .put("windowMs", JSONArray())
            )
            .put("memory", JSONObject()
                .put("idlePssBytes", 0)
                .put("peakPssBytes", 0)
                .put("peakPssDeltaBytes", 0)
                .put("peakJavaBytes", 0)
                .put("peakNativeBytes", 0)
                .put("peakGraphicsBytes", 0)
            )
            .put("thermal", JSONObject().put("available", false).put("samples", JSONArray()))
            .put("lifecycle", JSONObject()
                .put("workerCompleted", false)
                .put("mediaSessionConnected", false)
                .put("pauseResumePassed", false)
                .put("seekPassed", false)
                .put("processRecreationPassed", false)
                .put("cancellationPassed", false)
            )
            .put("audio", JSONObject()
                .put("finite", false)
                .put("outputFrameCount", 0)
                .put("expectedFrameCount", 0)
                .put("frameDelta", 0)
                .put("snrDb", 0.0)
                .put("maxAbsError", 0.0)
                .put("reconstructionMaxAbsError", 0.0)
                .put("maxJoinDiscontinuity", 0.0)
                .put("playerTimestampDriftMs", 0)
                .put("stemSemantics", "not-tested")
            )
            .put("cache", JSONObject()
                .put("cacheKey", "not-tested")
                .put("entryCountBefore", 0)
                .put("entryCountAfter", 0)
                .put("exactIdentity", false)
                .put("completedPlayable", false)
                .put("clearRecoveryPassed", false)
            )
    }

    private fun writeReport(context: Context, runId: String, report: JSONObject) {
        val root = File(context.filesDir, REPORT_DIRECTORY)
        check(root.isDirectory || root.mkdirs()) { "Could not create the Phase 7 report directory." }
        File(root, "$runId-worker.json").writeText(report.toString(2))
    }

    private class RecordingCallbacks : SourceSeparationForegroundWorkerCallbacks {
        val completedCacheKey = AtomicReference<String?>()
        var cpuThreads: Int = 0

        override fun onSourceSeparationWorkerProgress(song: Song) = Unit
        override fun onSourceSeparationWorkerPrepared(song: Song) = Unit
        override fun onSourceSeparationWorkerCompleted(
            song: Song,
            cacheKey: String,
            shouldPromoteCompletedStems: Boolean,
        ) {
            completedCacheKey.set(cacheKey)
        }

        override fun onSourceSeparationWorkerPaused(song: Song) = Unit
        override fun onSourceSeparationWorkerModelLoadFailed(message: String) = Unit
    }

    private fun File.sha256(): String = inputStream().use {
        MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString("") { byte ->
            "%02x".format(byte)
        }
    }

    private fun android.os.Bundle.requiredString(key: String): String =
        requireNotNull(getString(key)?.takeIf(String::isNotBlank)) {
            "Missing instrumentation argument: $key"
        }

    private fun android.os.Bundle.requiredInt(key: String): Int =
        getString(key)?.toIntOrNull() ?: getInt(key).takeIf { it != 0 }
        ?: error("Missing instrumentation argument: $key")

    private fun android.os.Bundle.optionalInt(key: String, default: Int = 0): Int =
        getString(key)?.toIntOrNull() ?: getInt(key, default)

    private fun android.os.Bundle.optionalLong(key: String, default: Long = 0L): Long =
        getString(key)?.toLongOrNull() ?: getLong(key, default)

    private fun android.os.Bundle.optionalBoolean(key: String, default: Boolean): Boolean =
        getString(key)?.toBooleanStrictOrNull() ?: getBoolean(key, default)

    private fun resolveCpuThreads(processorCount: Int?): Int =
        ((processorCount ?: Runtime.getRuntime().availableProcessors()) - 1).coerceIn(2, 4)

    private fun String.requireSafeName(): String = also {
        require(SAFE_NAME.matches(it)) { "Unsafe Phase 7 validation run ID." }
    }

    private companion object {
        const val ARG_RUN_ID = "runId"
        const val ARG_SERIAL = "serial"
        const val ARG_PROCESS_ABI = "processAbi"
        const val ARG_MODEL_ID = "modelId"
        const val ARG_ARTIFACT_SHA256 = "artifactSha256"
        const val ARG_ARTIFACT_FILE_NAME = "artifactFileName"
        const val ARG_CONTRACT_ID = "contractId"
        const val ARG_CONTRACT_SCHEMA_VERSION = "contractSchemaVersion"
        const val ARG_APP_COMMIT = "appCommit"
        const val ARG_APP_APK_SHA256 = "appApkSha256"
        const val ARG_TEST_APK_SHA256 = "testApkSha256"
        const val ARG_CATALOG_SHA256 = "catalogSha256"
        const val ARG_CATALOG_SOURCE_REVISION = "catalogSourceRevision"
        const val ARG_MODEL_RELEASE_TAG = "modelReleaseTag"
        const val ARG_PIPELINE_VERSION = "pipelineCompatibilityVersion"
        const val ARG_LITERT_VERSION = "litertVersion"
        const val ARG_RUNNER_REVISION = "runnerRevision"
        const val ARG_THRESHOLDS_VERSION = "thresholdsVersion"
        const val ARG_FIXTURES_VERSION = "fixturesVersion"
        const val ARG_SOURCE_PATH = "sourcePath"
        const val ARG_PROCESSOR_COUNT = "processorCount"
        const val ARG_RUN_CLASS = "runClass"
        const val ARG_CLEAN_INSTALL = "cleanInstallScenario"
        const val ARG_FIXTURE_ID = "fixtureId"
        const val ARG_FIXTURE_FILE_NAME = "fixtureFileName"
        const val ARG_FIXTURE_BYTES = "fixtureBytes"
        const val ARG_FIXTURE_SHA256 = "fixtureSha256"
        const val ARG_FIXTURE_DURATION_US = "fixtureDurationUs"
        const val ARG_FIXTURE_SAMPLE_RATE = "fixtureSampleRate"
        const val ARG_FIXTURE_CHANNELS = "fixtureChannels"
        const val ARG_FIXTURE_CODEC = "fixtureCodec"
        const val ARG_FIXTURE_DECODE_CLASS = "fixtureDecodeClass"
        const val REPORT_DIRECTORY = "phase7-validation-reports"
        const val REQUIRED_READY_WINDOWS = 2
        const val TEST_BLEND = 0.23f
        const val POLL_INTERVAL_MS = 250L
        const val WORKER_TIMEOUT_MS = 20 * 60 * 1000L
        const val MEDIA_SCAN_TIMEOUT_MS = 30_000L
        const val MEDIA_SCAN_RETRIES = 60
        const val MEDIA_SCAN_POLL_MS = 500L
        const val ZERO_SHA256 = "0000000000000000000000000000000000000000000000000000000000000000"
        val SAFE_NAME = Regex("^[A-Za-z0-9._-]{1,120}$")
    }
}
