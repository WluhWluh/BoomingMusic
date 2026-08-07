package com.mardous.booming.separation

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mardous.booming.data.model.Song
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheModelAvailability
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheMutationResult
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunCoordinator
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunJournalLifecycle
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheStore
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheValidationResult
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwareCacheRepository
import com.mardous.booming.separation.runtime.SourceSeparationRuntimeBootstrap
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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
            val runtime = SourceSeparationRuntimeBootstrap.ensureLoaded(context)
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
        const val ARG_SOURCE_PATH = "bssMultistemSourcePath"
        const val ARG_SOURCE_SHA256 = "bssMultistemSourceSha256"
        const val ARG_RUN_ID = "bssMultistemRunId"
        const val ARG_APP_COMMIT = "bssAppCommit"
        const val ARG_TEST_COMMIT = "bssTestCommit"
        const val REPORT_DIRECTORY = "source-separation/multistem-product-device-reports"
        const val SONG_TITLE_PREFIX = "BSS Phase 6 Product"
        const val SOURCE_DURATION_MS = 30_000L
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
