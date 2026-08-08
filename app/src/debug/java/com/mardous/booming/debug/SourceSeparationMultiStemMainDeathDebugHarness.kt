package com.mardous.booming.debug

import android.app.ActivityManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import com.mardous.booming.data.model.Song
import com.mardous.booming.separation.SourceSeparationExecutionRunClass
import com.mardous.booming.separation.SourceSeparationMultiStemProductFacade
import com.mardous.booming.separation.SourceSeparationMultiStemPlaybackSelectionStore
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheManifestState
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunJournalLifecycle
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheStore
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwareCacheRepository
import com.mardous.booming.separation.process.SourceSeparationMultiStemIpcRunAuthority
import com.mardous.booming.separation.process.ipc.BoundRemoteSourceSeparationMultiStemExecutionHost
import com.mardous.booming.ui.screen.player.SourceSeparationForegroundWorkerCoordinator
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.koin.java.KoinJavaComponent.get

/** Debug-only ADB entry for killing the real app process during an independent run. */
internal object SourceSeparationMultiStemMainDeathDebugHarness {
    fun handle(context: Context, command: String, intent: Intent): Boolean {
        return when (command) {
            COMMAND_BEGIN -> {
                Thread({ begin(context.applicationContext, intent) },
                    "BSS-Multistem-Main-Death").start()
                true
            }
            COMMAND_VALIDATE -> {
                Thread({ validate(context.applicationContext, intent) },
                    "BSS-Multistem-Recovery").start()
                true
            }
            else -> false
        }
    }

    private fun begin(context: Context, intent: Intent) {
        val runId = intent.requiredString(EXTRA_RUN_ID).requireSafeName()
        val modelId = intent.requiredString(EXTRA_MODEL_ID).requireSafeName()
        val sourcePath = intent.requiredString(EXTRA_SOURCE_PATH)
        val waitForCommittedSegment = intent.getBooleanExtra(
            EXTRA_WAIT_FOR_COMMITTED_SEGMENT,
            true,
        )
        val source = File(context.filesDir, sourcePath).canonicalFile
        require(source.toPath().startsWith(context.filesDir.canonicalFile.toPath()) && source.isFile)
        val scenarioFile = scenarioFile(context, runId)
        var mediaUri: Uri? = null
        try {
            scenarioFile.delete()
            mediaUri = importIntoMediaStore(context, source, runId)
            val song = stagedSong(mediaUri, source, runId)
            val facade = get<SourceSeparationMultiStemProductFacade>(
                SourceSeparationMultiStemProductFacade::class.java,
            )
            val repository = get<SourceSeparationModelAwareCacheRepository>(
                SourceSeparationModelAwareCacheRepository::class.java,
            )
            val store = get<SourceSeparationCacheStore>(SourceSeparationCacheStore::class.java)
            get<SourceSeparationMultiStemPlaybackSelectionStore>(
                SourceSeparationMultiStemPlaybackSelectionStore::class.java,
            ).select(modelId)
            repository.entries()
                .filter { it.modelId == modelId && it.title.startsWith(SONG_TITLE_PREFIX) }
                .forEach { repository.delete(it.cacheKey) }
            val cacheKey = AtomicReference<String?>()
            Thread({
                facade.separate(
                    song = song,
                    modelId = modelId,
                    runClass = SourceSeparationExecutionRunClass.ManualFullSong,
                    onPrepared = { manifest -> cacheKey.set(manifest.cacheKey) },
                )
            }, "BSS-Multistem-Independent-Run").start()

            val deadline = SystemClock.elapsedRealtime() + SETUP_TIMEOUT_MS
            var journal: com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunJournal? = null
            while (SystemClock.elapsedRealtime() < deadline) {
                journal = cacheKey.get()?.let(store::readRunJournal)
                if (journal?.lifecycle == SourceSeparationCacheRunJournalLifecycle.Running &&
                    (!waitForCommittedSegment || journal.committedSegments.isNotEmpty())
                ) {
                    break
                }
                SystemClock.sleep(POLL_INTERVAL_MS)
            }
            val activeJournal = requireNotNull(journal)
            check(activeJournal.lifecycle == SourceSeparationCacheRunJournalLifecycle.Running)
            check(!waitForCommittedSegment || activeJournal.committedSegments.isNotEmpty())
            val remote = BoundRemoteSourceSeparationMultiStemExecutionHost(context)
            val snapshot = requireNotNull(remote.reconnectableRun())
            check(snapshot.authority == SourceSeparationMultiStemIpcRunAuthority.IndependentForeground)
            check(snapshot.descriptor.cacheKey == activeJournal.request.cacheKey)
            check(snapshot.descriptor.runId == activeJournal.request.runId)
            val remotePid = requireNotNull(sourceSeparationPid(context))
            check(remotePid == activeJournal.request.ownerPid)
            writeDurableJson(
                scenarioFile,
                JSONObject()
                    .put("schemaVersion", 1)
                    .put("runId", runId)
                    .put("modelId", modelId)
                    .put("cacheKey", activeJournal.request.cacheKey)
                    .put("sourceMediaUri", mediaUri.toString())
                    .put("mainPid", Process.myPid())
                    .put("remotePid", remotePid)
                    .put("remoteProcessGeneration", snapshot.descriptor.processGeneration)
                    .put("journalSequence", activeJournal.latestSequence)
                    .put("eventSequence", snapshot.latestEvent.sequence)
                    .put("committedSegments", activeJournal.committedSegments.size)
                    .put("waitForCommittedSegment", waitForCommittedSegment),
            )
            Log.i(TAG, "Multi-stem main-death scenario is ready for $runId.")
            SystemClock.sleep(MAIN_DEATH_SETTLE_MS)
            Process.killProcess(Process.myPid())
            error("The debug main process survived its requested death.")
        } catch (error: Throwable) {
            mediaUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            scenarioFile.delete()
            Log.e(TAG, "Could not prepare multi-stem main-death scenario $runId.", error)
        }
    }

    private fun validate(context: Context, intent: Intent) {
        val runId = intent.requiredString(EXTRA_RUN_ID).requireSafeName()
        val file = scenarioFile(context, runId)
        try {
            val scenario = JSONObject(file.readText(Charsets.UTF_8))
            check(scenario.getInt("schemaVersion") == 1)
            val cacheKey = scenario.getString("cacheKey")
            val oldMainPid = scenario.getInt("mainPid")
            val remotePid = scenario.getInt("remotePid")
            check(!File("/proc/$oldMainPid").exists())
            check(File("/proc/$remotePid").isDirectory) {
                "The independent multi-stem process was not alive at recovery startup."
            }
            val store = get<SourceSeparationCacheStore>(SourceSeparationCacheStore::class.java)
            val worker = get<SourceSeparationForegroundWorkerCoordinator>(
                SourceSeparationForegroundWorkerCoordinator::class.java,
            )
            val adoptionDeadline = SystemClock.elapsedRealtime() + ADOPTION_TIMEOUT_MS
            while (worker.runningCacheKey() != cacheKey &&
                SystemClock.elapsedRealtime() < adoptionDeadline
            ) {
                SystemClock.sleep(POLL_INTERVAL_MS)
            }
            check(worker.runningCacheKey() == cacheKey) {
                "The product worker did not adopt the independent multi-stem run."
            }
            val completionDeadline = SystemClock.elapsedRealtime() + COMPLETION_TIMEOUT_MS
            var journal = store.readRunJournal(cacheKey)
            var manifest = store.readManifest(cacheKey)
            while ((journal?.lifecycle != SourceSeparationCacheRunJournalLifecycle.Completed ||
                    manifest?.state != SourceSeparationCacheManifestState.Completed) &&
                SystemClock.elapsedRealtime() < completionDeadline
            ) {
                SystemClock.sleep(POLL_INTERVAL_MS)
                journal = store.readRunJournal(cacheKey)
                manifest = store.readManifest(cacheKey)
            }
            val completedJournal = requireNotNull(journal)
            check(completedJournal.lifecycle == SourceSeparationCacheRunJournalLifecycle.Completed)
            check(manifest?.state == SourceSeparationCacheManifestState.Completed)
            check(completedJournal.request.ownerPid == remotePid)
            scenario.put("productRecoveryObserved", true)
                .put("newMainPid", Process.myPid())
                .put("finalJournalSequence", completedJournal.latestSequence)
                .put("finalCommittedSegments", completedJournal.committedSegments.size)
                .put("remoteProcessVisibleAfterCompletion",
                    sourceSeparationPid(context) == remotePid)
            writeDurableJson(file, scenario)
            Log.i(TAG, "Multi-stem main-death recovery completed for $runId.")
        } catch (error: Throwable) {
            Log.e(TAG, "Could not validate multi-stem main-death recovery $runId.", error)
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
        context.contentResolver.openOutputStream(uri, "w")!!.use { output ->
            source.inputStream().use { input -> input.copyTo(output, 256 * 1024) }
        }
        check(context.contentResolver.update(uri, ContentValues().apply {
            put(MediaStore.Audio.Media.IS_PENDING, 0)
        }, null, null) == 1)
        return uri
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

    private fun sourceSeparationPid(context: Context): Int? =
        context.getSystemService(ActivityManager::class.java).runningAppProcesses
            ?.singleOrNull { it.processName == "${context.packageName}:source_separation" }
            ?.pid

    private fun scenarioFile(context: Context, runId: String): File =
        File(context.filesDir, "$SCENARIO_DIRECTORY/$runId.json")

    private fun writeDurableJson(file: File, value: JSONObject) {
        val directory = requireNotNull(file.parentFile)
        require(directory.isDirectory || directory.mkdirs())
        RandomAccessFile(file, "rw").use { output ->
            output.setLength(0L)
            output.write(value.toString(2).toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
    }

    private fun Intent.requiredString(name: String): String =
        requireNotNull(getStringExtra(name)?.takeIf { it.isNotBlank() }) {
            "Missing debug argument: $name"
        }

    private fun String.requireSafeName(): String = also {
        require(SAFE_NAME.matches(it)) { "Unsafe debug argument: $it" }
    }

    const val COMMAND_BEGIN = "beginIndependentMultiStemMainDeath"
    const val COMMAND_VALIDATE = "validateIndependentMultiStemMainDeath"
    private const val EXTRA_RUN_ID = "runId"
    private const val EXTRA_MODEL_ID = "modelId"
    private const val EXTRA_SOURCE_PATH = "sourcePath"
    private const val EXTRA_WAIT_FOR_COMMITTED_SEGMENT = "waitForCommittedSegment"
    private const val SCENARIO_DIRECTORY =
        "source-separation/multistem-main-death-scenarios"
    private const val SONG_TITLE_PREFIX = "BSS Phase 6 Product"
    private const val SOURCE_DURATION_MS = 30_000L
    private const val SETUP_TIMEOUT_MS = 10L * 60L * 1_000L
    private const val ADOPTION_TIMEOUT_MS = 30_000L
    private const val COMPLETION_TIMEOUT_MS = 10L * 60L * 1_000L
    private const val POLL_INTERVAL_MS = 50L
    private const val MAIN_DEATH_SETTLE_MS = 500L
    private const val TAG = "BSS-MultistemDeath"
    private val SAFE_NAME = Regex("^[A-Za-z0-9._-]{1,160}$")
}
