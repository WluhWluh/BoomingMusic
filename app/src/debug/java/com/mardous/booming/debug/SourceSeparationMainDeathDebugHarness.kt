package com.mardous.booming.debug

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Process
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import com.mardous.booming.data.model.Song
import com.mardous.booming.separation.SourceSeparationExecutionRunClass
import com.mardous.booming.separation.SourceSeparationRuntimeFacade
import com.mardous.booming.separation.SourceSeparationRuntimeSongResolution
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheMutationResult
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunJournal
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunJournalLifecycle
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunTransitionType
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheStore
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwareCacheStatus
import com.mardous.booming.separation.model.litert.MdxLiteRtGpuRuntimeProfile
import com.mardous.booming.separation.model.preset.SourceSeparationActivePresetState
import com.mardous.booming.separation.model.preset.SourceSeparationPresetRepository
import com.mardous.booming.ui.screen.player.SourceSeparationForegroundWorkerCallbacks
import com.mardous.booming.ui.screen.player.SourceSeparationForegroundWorkerCoordinator
import com.mardous.booming.ui.screen.player.SourceSeparationUiState
import com.mardous.booming.util.MINIMUM_SONG_DURATION
import com.mardous.booming.util.SOURCE_SEPARATION_AUTO_CACHE_CLEANUP
import com.mardous.booming.util.SOURCE_SEPARATION_AUTO_FLAC_COMPRESSION
import com.mardous.booming.util.SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT
import com.mardous.booming.util.SOURCE_SEPARATION_TRY_GPU
import com.mardous.booming.util.SOURCE_SEPARATION_WINDOW_DECODE
import org.json.JSONArray
import org.json.JSONObject
import org.koin.java.KoinJavaComponent.get
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Debug-only process-death harness driven by the Phase 7 ADB runner. */
internal object SourceSeparationMainDeathDebugHarness {

    fun handle(context: Context, command: String, intent: Intent): Boolean {
        return when (command) {
            COMMAND_BEGIN -> {
                val request = request(intent)
                launch("SrcSepMainDeathBegin") {
                    begin(context.applicationContext, request)
                }
                true
            }
            COMMAND_VALIDATE -> {
                val request = request(intent)
                launch("SrcSepMainDeathValidate") {
                    validate(context.applicationContext, request)
                }
                true
            }
            else -> false
        }
    }

    private fun begin(context: Context, request: Request) {
        val scenarioFile = scenarioFile(context, request.runId)
        val reportFile = reportFile(context, request.runId)
        scenarioFile.delete()
        reportFile.delete()
        var mediaUri: Uri? = null
        try {
            verifyActiveModel(request)
            configurePreferences(request)
            mediaUri = registerSourceInMediaStore(context, request.sourcePath, request.runId)
            val source = resolveMediaStoreSong(context, mediaUri, request.sourcePath)
            val runtime = get<SourceSeparationRuntimeFacade>(
                SourceSeparationRuntimeFacade::class.java,
            )
            val runtimeSong = (runtime.resolve(source) as?
                SourceSeparationRuntimeSongResolution.Ready)?.song
                ?: error("The debug source could not be admitted.")
            runtime.entries()
                .filter { it.cacheKey == runtimeSong.cacheKey }
                .forEach { entry ->
                    check(runtime.delete(entry.cacheKey) ==
                        SourceSeparationCacheMutationResult.Completed
                    ) { "The previous exact cache could not be removed." }
                }

            val callbacks = RecordingCallbacks()
            val worker = get<SourceSeparationForegroundWorkerCoordinator>(
                SourceSeparationForegroundWorkerCoordinator::class.java,
            )
            worker.attachCallbacks(callbacks)
            worker.updateSong(
                song = source,
                positionMs = 0L,
                durationMs = source.duration,
                isPlaying = false,
                sourceSeparationBlend = TEST_BLEND,
            )
            worker.requestManualSong(source)

            val store = get<SourceSeparationCacheStore>(SourceSeparationCacheStore::class.java)
            val journal = waitForJournal(SETUP_TIMEOUT_MS) {
                val cacheKey = worker.runningCacheKey() ?: return@waitForJournal null
                val candidate = store.readRunJournal(cacheKey) ?: return@waitForJournal null
                candidate.takeIf { current ->
                    current.lifecycle == SourceSeparationCacheRunJournalLifecycle.Running &&
                        current.request.runClass ==
                            SourceSeparationExecutionRunClass.ManualFullSong &&
                        current.transitions.any { transition ->
                            transition.type ==
                                SourceSeparationCacheRunTransitionType.SegmentRunning
                        }
                }
            }
            check(journal.request.cacheKey == runtimeSong.cacheKey)
            check(journal.request.backgroundPolicy ==
                SourceSeparationExecutionRunClass.ManualFullSong.backgroundPolicy)
            check(journal.request.tryGpu == request.tryGpu)
            val gpuRuntime = journal.request.gpuRuntimeIdentity
            if (request.tryGpu) {
                check(gpuRuntime?.profileId ==
                    MdxLiteRtGpuRuntimeProfile.BoundedOpenClFp32V1.profileId)
                check(gpuRuntime.kernelBatchSize == 1)
                check(gpuRuntime.commandQueueWindowSize == 1)
            } else {
                check(gpuRuntime == null)
            }
            val remotePid = requireNotNull(journal.request.ownerPid)
            check(remotePid != Process.myPid())
            check(File("/proc/$remotePid").isDirectory)

            writeJson(
                scenarioFile,
                JSONObject()
                    .put("schemaVersion", SCENARIO_SCHEMA_VERSION)
                    .put("runId", request.runId)
                    .put("cacheKey", runtimeSong.cacheKey)
                    .put("sourceMediaUri", mediaUri.toString())
                    .put("sourcePath", request.sourcePath)
                    .put("mainPid", Process.myPid())
                    .put("remotePid", remotePid)
                    .put("remoteProcessGeneration", journal.request.processGeneration)
                    .put("executionRunId", journal.request.runId)
                    .put("journalSequence", journal.latestSequence)
                    .put("committedSegments", journal.committedSegments.size)
                    .put("killBoundary", "segment-running")
                    .put("backendMode", request.backendMode)
                    .put("tryGpu", request.tryGpu),
            )
            Log.i(TAG, "Main-death scenario is ready for ${request.runId}.")
        } catch (error: Throwable) {
            mediaUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            scenarioFile.delete()
            writeFailure(reportFile, request, "setup", error)
            Log.e(TAG, "Could not prepare main-death scenario ${request.runId}.", error)
        }
    }

    private fun validate(context: Context, request: Request) {
        val scenarioFile = scenarioFile(context, request.runId)
        val outputFile = reportFile(context, request.runId)
        var mediaUri: Uri? = null
        var worker: SourceSeparationForegroundWorkerCoordinator? = null
        var callbacks: RecordingCallbacks? = null
        try {
            val scenario = JSONObject(scenarioFile.readText(Charsets.UTF_8))
            check(scenario.getInt("schemaVersion") == SCENARIO_SCHEMA_VERSION)
            check(scenario.getString("runId") == request.runId)
            check(scenario.getString("backendMode") == request.backendMode)
            val cacheKey = scenario.getString("cacheKey")
            val oldMainPid = scenario.getInt("mainPid")
            val remotePid = scenario.getInt("remotePid")
            val processGeneration = scenario.getLong("remoteProcessGeneration")
            val executionRunId = scenario.getString("executionRunId")
            mediaUri = Uri.parse(scenario.getString("sourceMediaUri"))
            check(oldMainPid != Process.myPid())
            check(!File("/proc/$oldMainPid").exists())
            check(File("/proc/$remotePid").isDirectory) {
                "The authoritative inference process did not survive main-process death."
            }

            val store = get<SourceSeparationCacheStore>(SourceSeparationCacheStore::class.java)
            val detached = waitForJournal(REATTACH_TIMEOUT_MS) {
                store.readRunJournal(cacheKey)?.takeIf { journal ->
                    journal.lifecycle == SourceSeparationCacheRunJournalLifecycle.Running &&
                        journal.transitions.any { transition ->
                            transition.type ==
                                SourceSeparationCacheRunTransitionType.ObserverDisconnected
                        }
                }
            }
            check(detached.request.ownerPid == remotePid)
            check(detached.request.processGeneration == processGeneration)
            check(detached.request.runId == executionRunId)

            val source = resolveMediaStoreSong(context, mediaUri, request.sourcePath)
            val runtime = get<SourceSeparationRuntimeFacade>(
                SourceSeparationRuntimeFacade::class.java,
            )
            val runtimeSong = (runtime.resolve(source) as?
                SourceSeparationRuntimeSongResolution.Ready)?.song
                ?: error("The restarted main process could not resolve the source.")
            check(runtimeSong.cacheKey == cacheKey)
            callbacks = RecordingCallbacks()
            worker = get<SourceSeparationForegroundWorkerCoordinator>(
                SourceSeparationForegroundWorkerCoordinator::class.java,
            )
            worker.attachCallbacks(callbacks)
            worker.updateSong(
                song = source,
                positionMs = 0L,
                durationMs = source.duration,
                isPlaying = false,
                sourceSeparationBlend = TEST_BLEND,
            )

            val adopted = waitUntil(REATTACH_TIMEOUT_MS) {
                when (val state = worker.workerStateFlow.value) {
                    is SourceSeparationUiState.Failed,
                    is SourceSeparationUiState.Canceled,
                    -> error("Main-process reattachment failed: $state")
                    else -> Unit
                }
                worker.runningCacheKey() == cacheKey
            }
            check(adopted) { "The restarted worker did not adopt the remote run." }
            check(worker.protectedCacheKeys() == setOf(cacheKey))
            check(worker.pendingSongId() == null)

            val finalJournal = waitForJournal(COMPLETION_TIMEOUT_MS) {
                store.readRunJournal(cacheKey)?.takeIf { journal ->
                    journal.lifecycle == SourceSeparationCacheRunJournalLifecycle.Completed
                }
            }
            check(finalJournal.request.runId == executionRunId)
            check(finalJournal.request.processGeneration == processGeneration)
            check(finalJournal.request.ownerPid == remotePid)
            check(waitUntil(REATTACH_TIMEOUT_MS) {
                worker.runningCacheKey() == null &&
                    worker.protectedCacheKeys().isEmpty()
            }) { "The restarted worker did not release terminal ownership." }
            check(waitUntil(CALLBACK_TIMEOUT_MS) {
                callbacks.completedCacheKey.get() == cacheKey
            }) { "The restarted worker did not deliver the completion callback." }

            val completed = runtime.cacheStatus(runtimeSong) as?
                SourceSeparationModelAwareCacheStatus.Completed
                ?: error("The reattached run did not create a completed cache.")
            runtime.openCompletedCache(cacheKey).use { playback ->
                requireNotNull(playback)
                check(playback.vocalsFile.isFile)
                check(playback.instrumentalFile.isFile)
            }
            val observerTransitions = finalJournal.transitions.filter { transition ->
                transition.type == SourceSeparationCacheRunTransitionType.ObserverConnected ||
                    transition.type == SourceSeparationCacheRunTransitionType.ObserverDisconnected
            }
            check(observerTransitions.size >= 3)
            check(observerTransitions.last().type ==
                SourceSeparationCacheRunTransitionType.ObserverConnected)
            check(finalJournal.transitions.all { transition ->
                transition.runId == executionRunId &&
                    transition.processGeneration == processGeneration
            })

            val runtimeRecords = completed.manifest.runtimeRecords
            writeJson(
                outputFile,
                JSONObject()
                    .put("schemaVersion", REPORT_SCHEMA_VERSION)
                    .put("status", "passed")
                    .put("stage", "independent-main-death")
                    .put("runId", request.runId)
                    .put("cacheKey", cacheKey)
                    .put("oldMainPid", oldMainPid)
                    .put("newMainPid", Process.myPid())
                    .put("remotePid", remotePid)
                    .put("remoteProcessGeneration", processGeneration)
                    .put("executionRunId", executionRunId)
                    .put("killBoundary", scenario.getString("killBoundary"))
                    .put("journalSequenceBeforeDeath", scenario.getLong("journalSequence"))
                    .put("journalSequenceBeforeReattachment", detached.latestSequence)
                    .put("finalJournalSequence", finalJournal.latestSequence)
                    .put("committedSegmentsBeforeDeath", scenario.getInt("committedSegments"))
                    .put("finalCommittedSegments", finalJournal.committedSegments.size)
                    .put("observerTransitionCount", observerTransitions.size)
                    .put("remoteProcessReused", File("/proc/$remotePid").isDirectory)
                    .put("secondStartIssued", false)
                    .put("tryGpu", finalJournal.request.tryGpu)
                    .put("admittedGpuRuntime", gpuRuntimeJson(finalJournal))
                    .put("runtimeRecords", JSONArray(runtimeRecords.map { record ->
                        JSONObject()
                            .put("backend", record.backend)
                            .put("runtimeProfileId", record.runtimeProfileId)
                            .put("precision", record.precision)
                            .put("elapsedMs", record.elapsedMs)
                            .put("fallbackStage", record.fallbackStage ?: JSONObject.NULL)
                            .put("fallbackReason", record.fallbackReason ?: JSONObject.NULL)
                    })),
            )
            Log.i(TAG, "Main-death validation passed for ${request.runId}.")
        } catch (error: Throwable) {
            writeFailure(outputFile, request, "validation", error)
            Log.e(TAG, "Main-death validation failed for ${request.runId}.", error)
        } finally {
            if (worker != null && callbacks != null) {
                worker.detachCallbacks(callbacks)
            }
            mediaUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
        }
    }

    private fun verifyActiveModel(request: Request) {
        val active = get<SourceSeparationPresetRepository>(
            SourceSeparationPresetRepository::class.java,
        ).activeModel() as? SourceSeparationActivePresetState.Reference
            ?: error("No preset model is active.")
        check(active.reference.modelId == request.modelId)
        check(active.reference.artifactSha256.equals(request.artifactSha256, true))
    }

    private fun configurePreferences(request: Request) {
        val preferences = get<SharedPreferences>(SharedPreferences::class.java)
        check(preferences.edit()
            .putInt(MINIMUM_SONG_DURATION, 0)
            .putBoolean(SOURCE_SEPARATION_AUTO_CACHE_CLEANUP, false)
            .putBoolean(SOURCE_SEPARATION_AUTO_FLAC_COMPRESSION, false)
            .putBoolean(SOURCE_SEPARATION_WINDOW_DECODE, true)
            .putBoolean(SOURCE_SEPARATION_TRY_GPU, request.tryGpu)
            .putInt(SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT, 1)
            .commit()
        ) { "Could not persist debug process-death preferences." }
    }

    private fun waitForJournal(
        timeoutMs: Long,
        read: () -> SourceSeparationCacheRunJournal?,
    ): SourceSeparationCacheRunJournal {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            read()?.let { return it }
            SystemClock.sleep(POLL_MS)
        }
        error("Timed out waiting for the expected cache journal.")
    }

    private fun waitUntil(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return true
            SystemClock.sleep(POLL_MS)
        }
        return predicate()
    }

    private fun gpuRuntimeJson(journal: SourceSeparationCacheRunJournal): Any {
        val identity = journal.request.gpuRuntimeIdentity ?: return JSONObject.NULL
        return JSONObject()
            .put("profileId", identity.profileId)
            .put("artifactVersion", identity.artifactVersion)
            .put("capabilitySchemaVersion", identity.capabilitySchemaVersion)
            .put("backend", identity.backend)
            .put("precision", identity.precision)
            .put("kernelBatchSize", identity.kernelBatchSize)
            .put("commandQueueWindowSize", identity.commandQueueWindowSize)
    }

    private fun request(intent: Intent): Request {
        val runId = intent.requiredString(EXTRA_RUN_ID)
        require(SAFE_NAME.matches(runId)) { "Unsafe main-death run ID." }
        val sourcePath = intent.requiredString(EXTRA_SOURCE_PATH)
        val backendMode = intent.requiredString(EXTRA_BACKEND_MODE)
        require(backendMode == "cpu" || backendMode == "auto")
        return Request(
            runId = runId,
            sourcePath = sourcePath,
            modelId = intent.requiredString(EXTRA_MODEL_ID),
            artifactSha256 = intent.requiredString(EXTRA_ARTIFACT_SHA256),
            backendMode = backendMode,
        )
    }

    private fun Intent.requiredString(key: String): String =
        requireNotNull(getStringExtra(key)?.takeIf(String::isNotBlank)) {
            "Missing debug process-death argument: $key"
        }

    private fun launch(name: String, block: () -> Unit) {
        Thread(block, name).start()
    }

    private fun scenarioFile(context: Context, runId: String): File =
        File(outputDirectory(context), "$runId-scenario.json")

    private fun reportFile(context: Context, runId: String): File =
        File(outputDirectory(context), "$runId-report.json")

    private fun outputDirectory(context: Context): File =
        File(context.filesDir, OUTPUT_DIRECTORY).apply {
            check(isDirectory || mkdirs()) { "Could not create process-death output directory." }
        }

    private fun writeFailure(
        file: File,
        request: Request,
        phase: String,
        error: Throwable,
    ) {
        writeJson(
            file,
            JSONObject()
                .put("schemaVersion", REPORT_SCHEMA_VERSION)
                .put("status", "failed")
                .put("stage", "independent-main-death")
                .put("phase", phase)
                .put("runId", request.runId)
                .put("backendMode", request.backendMode)
                .put("errorType", error::class.java.name)
                .put("error", error.message ?: JSONObject.NULL),
        )
    }

    private fun writeJson(file: File, json: JSONObject) {
        val temporary = File(file.parentFile, "${file.name}.tmp-${Process.myPid()}")
        temporary.writeText(json.toString(2), Charsets.UTF_8)
        if (!temporary.renameTo(file)) {
            temporary.copyTo(file, overwrite = true)
            check(temporary.delete()) { "Could not remove temporary debug report." }
        }
    }

    private fun registerSourceInMediaStore(context: Context, path: String, runId: String): Uri {
        val resolver = context.contentResolver
        val source = File(path).canonicalFile
        val filesRoot = context.filesDir.canonicalFile
        check(source.isFile && source.toPath().startsWith(filesRoot.toPath())) {
            "The debug source must be staged inside app files."
        }
        val extension = source.extension.lowercase().takeIf { SAFE_EXTENSION.matches(it) }
            ?: "bin"
        val displayName = "booming-ss-main-death-$runId.$extension"
        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "audio/*"
        @Suppress("DEPRECATION")
        val legacySource = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            File(
                File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                    "BoomingSS",
                ).apply { check(exists() || mkdirs()) },
                displayName,
            ).also { destination -> source.copyTo(destination, overwrite = true) }
        } else {
            null
        }
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.TITLE, "Phase 7 $runId")
            put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
            put(MediaStore.Audio.Media.IS_MUSIC, 1)
            put(MediaStore.Audio.Media.ARTIST, "Booming SS")
            put(MediaStore.Audio.Media.ALBUM, "Phase 7 validation")
            put(MediaStore.Audio.Media.ALBUM_ARTIST, "Booming SS")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/BoomingSS")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            } else {
                put(MediaStore.Audio.Media.DATA, requireNotNull(legacySource).absolutePath)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val uri = requireNotNull(resolver.insert(collection, values))
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                requireNotNull(resolver.openOutputStream(uri, "w")).use { output ->
                    source.inputStream().use { input -> input.copyTo(output) }
                }
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) },
                    null,
                    null,
                )
            }
            return uri
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            legacySource?.delete()
            throw error
        }
    }

    private fun resolveMediaStoreSong(context: Context, uri: Uri, sourcePath: String): Song {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
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
                        data = sourcePath,
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
        error("MediaStore did not expose the debug source: $uri")
    }

    private fun Cursor.stringOrNull(column: String): String? =
        getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getString)

    private fun Cursor.stringOrFallback(column: String, fallback: String): String =
        stringOrNull(column)?.takeIf { it.isNotBlank() } ?: fallback

    private fun Cursor.intOrDefault(column: String, fallback: Int = 0): Int =
        getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getInt) ?: fallback

    private fun Cursor.longOrDefault(column: String, fallback: Long = 0L): Long =
        getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getLong) ?: fallback

    private data class Request(
        val runId: String,
        val sourcePath: String,
        val modelId: String,
        val artifactSha256: String,
        val backendMode: String,
    ) {
        val tryGpu: Boolean
            get() = backendMode == "auto"
    }

    private class RecordingCallbacks : SourceSeparationForegroundWorkerCallbacks {
        val completedCacheKey = AtomicReference<String?>()

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

    const val COMMAND_BEGIN = "beginIndependentMainDeath"
    const val COMMAND_VALIDATE = "validateIndependentMainDeath"

    private const val EXTRA_RUN_ID = "runId"
    private const val EXTRA_SOURCE_PATH = "sourcePath"
    private const val EXTRA_MODEL_ID = "modelId"
    private const val EXTRA_ARTIFACT_SHA256 = "artifactSha256"
    private const val EXTRA_BACKEND_MODE = "backendMode"
    private const val OUTPUT_DIRECTORY = "phase7-debug-main-death"
    private const val SCENARIO_SCHEMA_VERSION = 1
    private const val REPORT_SCHEMA_VERSION = 1
    private const val SETUP_TIMEOUT_MS = 5L * 60L * 1_000L
    private const val REATTACH_TIMEOUT_MS = 60_000L
    private const val COMPLETION_TIMEOUT_MS = 30L * 60L * 1_000L
    private const val CALLBACK_TIMEOUT_MS = 10_000L
    private const val POLL_MS = 100L
    private const val MEDIA_SCAN_RETRIES = 60
    private const val MEDIA_SCAN_POLL_MS = 500L
    private const val TEST_BLEND = 0.23f
    private const val TAG = "SrcSepMainDeath"
    private val SAFE_NAME = Regex("^[A-Za-z0-9._-]{1,120}$")
    private val SAFE_EXTENSION = Regex("^[a-z0-9]{1,8}$")
}
