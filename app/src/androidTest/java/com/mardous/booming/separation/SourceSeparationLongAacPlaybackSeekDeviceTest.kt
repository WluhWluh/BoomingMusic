package com.mardous.booming.separation

import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.app.Instrumentation
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
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
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheAudioFormat
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheContractSnapshot
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheFileIntegrity
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheManifest
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheManifestState
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheOutput
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRenderedStem
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheSongLocator
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheSourceDiagnostics
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheSourceIdentityResolver
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheStore
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwareCacheRepository
import com.mardous.booming.separation.model.contract.SourceSeparationMultiTensorExecutableContractLoader
import com.mardous.booming.separation.model.contract.SourceSeparationMultiStemReleaseInstaller
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/** Black-box seek timing through PlaybackService, MediaController and the mixer. */
@RunWith(AndroidJUnit4::class)
class SourceSeparationLongAacPlaybackSeekDeviceTest {
    @Test
    fun longAacSeekThroughPlaybackService() {
        val args = InstrumentationRegistry.getArguments()
        val fixture = args.getString("fixture_dir", FIXTURE_DIRECTORY)
        val runId = args.getString("run_id") ?: "long-aac-product-${System.currentTimeMillis()}"
        val pauseBeforeSeek = args.getString("pause_before_seek")?.toBooleanStrictOrNull() ?: false
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = File(context.filesDir, fixture).canonicalFile
        assumeTrue(root.isDirectory)
        val stems = (0 until STEM_COUNT).map { index -> File(root, "stem-%02d.m4a".format(index)) }
        assumeTrue(stems.all(File::isFile))

        val koin = GlobalContext.get()
        val store = koin.get<SourceSeparationCacheStore>()
        val installer = koin.get<SourceSeparationMultiStemReleaseInstaller>()
        val model = installer.installed(OFFICIAL_MODEL_ID)
            ?: installer.install(OFFICIAL_MODEL_ID)
        val executable = SourceSeparationMultiTensorExecutableContractLoader.load(
            model.sidecarFile.readText(),
        )
        val contract = SourceSeparationCacheContractSnapshot.fromMultiTensor(executable)
        val sourceUri = importSource(context, stems.first(), runId)
        val sourceIdentity = SourceSeparationCacheSourceIdentityResolver(context)
            .resolve(sourceUri).identity
        val song = Song(
            id = ContentUrisCompat.parseId(sourceUri),
            data = stems.first().absolutePath,
            title = "AAC product seek $runId",
            trackNumber = 1,
            year = 2026,
            size = stems.first().length(),
            duration = DURATION_MS,
            dateAdded = System.currentTimeMillis() / 1_000L,
            rawDateModified = stems.first().lastModified() / 1_000L,
            albumId = -1L,
            albumName = "AAC product seek",
            artistId = -1L,
            artistName = "Validation",
            albumArtistName = "Validation",
            genreName = null,
            volumeName = MediaStore.VOLUME_EXTERNAL_PRIMARY,
        )
        val identity = contract.identity(
            source = sourceIdentity,
            renderProfileId = HtdemucsSourceSeparationEngine.HTDEMUCS_CPU_PROFILE_ID,
        )
        val cacheKey = identity.cacheKey
        val entry = store.entryDirectory(cacheKey)
        var controller: MediaController? = null
        val traces = CopyOnWriteArrayList<TraceSample>()
        val processor = koin.get<SourceSeparationMixAudioProcessor>()
        val observer = processor.addDebugTraceObserver {
            traces += TraceSample(System.nanoTime(), it)
        }
        val report = JSONObject()
            .put("status", "running")
            .put("mode", "playback-service-long-aac-seek")
            .put("deviceModel", android.os.Build.MODEL)
            .put("sdk", android.os.Build.VERSION.SDK_INT)
            .put("durationMs", DURATION_MS)
            .put("fixture", fixture)
            .put("pauseBeforeSeek", pauseBeforeSeek)
        try {
            writeCompletedFixture(store, contract, identity, song, sourceUri, stems)
            check(store.validateCompletedEntry(requireNotNull(store.readManifest(cacheKey)), false).toString() == "Valid")
            koin.get<SourceSeparationMultiStemPlaybackSelectionStore>().select(OFFICIAL_MODEL_ID)
            val mediaController = MediaController.Builder(
                context,
                SessionToken(context, ComponentName(context, PlaybackService::class.java)),
            ).buildAsync().get(30, TimeUnit.SECONDS)
            controller = mediaController
            controllerRun(instrumentation) {
                mediaController.volume = 0f
                mediaController.setMediaItem(song.toMediaItem())
                mediaController.prepare()
            }
            await(5_000) { controllerRead(instrumentation) { mediaController.duration > 0L } }
            val enabled = controllerCall(instrumentation) {
                mediaController.sendCustomCommand(
                    SessionCommand(Playback.SET_SOURCE_SEPARATION_PLAYBACK_ENABLED, Bundle.EMPTY),
                    Bundle().apply {
                        putBoolean(Playback.EXTRA_SOURCE_SEPARATION_ENABLED, true)
                        putBoolean(Playback.EXTRA_SOURCE_SEPARATION_SHOW_MESSAGE, false)
                        putFloat(Playback.EXTRA_SOURCE_SEPARATION_BLEND, 0.7f)
                    },
                )
            }.get(30, TimeUnit.SECONDS)
            assertEquals(SessionResult.RESULT_SUCCESS, enabled.resultCode)
            controllerRun(instrumentation) { mediaController.play() }
            await(15_000) {
                processor.isDataPlaneReady() && controllerRead(instrumentation) { mediaController.isPlaying }
            }
            val cases = listOf(
                SeekCase("near_future", 110_011L),
                SeekCase("far_future", 165_011L),
                SeekCase("near_past", 100_011L),
                SeekCase("far_past", 45_011L),
            )
            val results = JSONArray()
            repeat(CASE_REPETITIONS) { iteration -> cases.forEach { seekCase ->
                if (pauseBeforeSeek) {
                    controllerRun(instrumentation) { mediaController.pause() }
                    await(3_000) { controllerRead(instrumentation) { !mediaController.isPlaying } }
                }
                val commandNs = System.nanoTime()
                controllerRun(instrumentation) {
                    mediaController.seekTo(seekCase.targetMs)
                    if (!mediaController.playWhenReady) mediaController.play()
                }
                var firstMixedNs: Long? = null
                var resumedNs: Long? = null
                var positionAdvancedNs: Long? = null
                val startTrace = traces.size
                await(5_000) {
                    firstMixedNs = firstMixedNs ?: traces.drop(startTrace)
                        .firstOrNull { it.text.contains("mix.mixedOutputPreroll.ready") }
                        ?.timestampNs
                    if (controllerRead(instrumentation) { mediaController.isPlaying } && processor.isDataPlaneReady()) {
                        resumedNs = resumedNs ?: System.nanoTime()
                    }
                    if (controllerRead(instrumentation) { mediaController.currentPosition } >=
                        seekCase.targetMs + POSITION_ADVANCE_MS
                    ) {
                        positionAdvancedNs = positionAdvancedNs ?: System.nanoTime()
                    }
                    firstMixedNs != null && positionAdvancedNs != null
                }
                results.put(JSONObject()
                    .put("id", seekCase.id)
                    .put("iteration", iteration)
                    .put("targetMs", seekCase.targetMs)
                    .put("controllerPositionMs", controllerRead(instrumentation) { mediaController.currentPosition })
                    .put("engineFirstOutputMs", traces.drop(startTrace)
                        .mapNotNull { ENGINE_OUTPUT_PATTERN.find(it.text)?.groupValues?.get(1)?.toLongOrNull() }
                        .minOrNull() ?: JSONObject.NULL)
                    .put("firstMixedTraceMs", firstMixedNs?.let { (it - commandNs) / 1_000_000L })
                    .put("resumedPlayingMs", resumedNs?.let { (it - commandNs) / 1_000_000L })
                    .put("positionAdvancedMs", positionAdvancedNs?.let { (it - commandNs) / 1_000_000L })
                    .put("trace", JSONArray(traces.drop(startTrace).takeLast(30).map(TraceSample::text))))
            } }
            report.put("status", "passed")
                .put("cacheKey", cacheKey)
                .put("cases", results)
                .put("dataPlaneMetrics", processor.dataPlaneMetrics()?.let { metrics ->
                    JSONObject().put("underruns", metrics.underruns)
                        .put("lowWaterEvents", metrics.lowWaterEvents)
                        .put("seekRequests", metrics.seekRequests)
                        .put("seekReady", metrics.seekReady)
                })
            File(context.filesDir, REPORT_DIRECTORY).apply { mkdirs() }
                .resolve("$runId.json").writeText(report.toString(2))
        } finally {
            observer.close()
            controller?.let { runCatching { it.stop(); it.clearMediaItems(); it.release() } }
            runCatching { koin.get<SourceSeparationModelAwareCacheRepository>().delete(cacheKey) }
            runCatching { context.contentResolver.delete(sourceUri, null, null) }
        }
    }

    private fun writeCompletedFixture(
        store: SourceSeparationCacheStore,
        contract: SourceSeparationCacheContractSnapshot,
        identity: com.mardous.booming.separation.cache.v2.SourceSeparationCacheIdentity,
        song: Song,
        sourceUri: Uri,
        stems: List<File>,
    ) {
        store.deleteEntry(identity.cacheKey)
        val rendered = contract.expectedStemSet().stems.mapIndexed { index, descriptor ->
            val source = stems[index]
            val path = "completed/stem-%02d.m4a".format(index)
            val integrity = store.copyIntoEntryAtomically(identity.cacheKey, source, path)
            SourceSeparationCacheRenderedStem(
                stemId = descriptor.stemId,
                semanticId = descriptor.semanticId,
                canonicalLabel = descriptor.canonicalLabel,
                order = descriptor.order,
                production = descriptor.production,
                wavPath = "completed/stem-%02d.wav".format(index),
                promotedPath = path,
                promotedFormat = SourceSeparationCacheAudioFormat.AacLcM4a,
                promotionValidated = true,
                promotedMimeType = "audio/mp4a-latm",
                promotedBitRate = 160_000,
                promotedEncoderName = "fixture",
                promotedEncoderDelayFrames = 1024,
                promotedPaddingFrames = 0,
                promotedSeekQuantumFrames = 1024,
                promotedMaxAnchorOffsetFrames = 4096,
                promotedTimestampOffsetFrames = 0,
                promotedDecodedFrameCount = FRAME_COUNT.toLong(),
                channelCount = 2,
                sampleRate = SAMPLE_RATE,
                frameCount = FRAME_COUNT,
                promotedIntegrity = integrity,
            )
        }
        val now = System.currentTimeMillis()
        store.writeManifest(SourceSeparationCacheManifest(
            manifestSchemaVersion = SourceSeparationCacheManifest.SCHEMA_VERSION,
            cacheKey = identity.cacheKey,
            identity = identity,
            contract = contract,
            state = SourceSeparationCacheManifestState.Completed,
            song = SourceSeparationCacheSongLocator(
                songId = song.id,
                mediaUri = sourceUri.toString(),
                filePath = song.data,
                title = song.title,
                artist = song.artistName,
                album = song.albumName,
            ),
            sourceDiagnostics = SourceSeparationCacheSourceDiagnostics(
                fileSize = song.size,
                rawDateModified = song.rawDateModified,
                durationMs = DURATION_MS,
            ),
            output = SourceSeparationCacheOutput(
                stems = rendered,
                outputSampleRate = SAMPLE_RATE,
                outputFrameCount = FRAME_COUNT,
                windowCount = 1,
                elapsedMs = 0,
                totalBytes = store.entrySize(identity.cacheKey),
            ),
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
        ))
    }

    private fun importSource(context: Context, source: File, runId: String): Uri {
        val uri = requireNotNull(context.contentResolver.insert(
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, "$runId.m4a")
                put(MediaStore.Audio.Media.TITLE, "AAC product seek $runId")
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                put(MediaStore.Audio.Media.IS_MUSIC, 1)
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/BoomingSS Validation")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            },
        ))
        try {
            context.contentResolver.openOutputStream(uri, "w")!!.use { out ->
                source.inputStream().use { it.copyTo(out) }
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

    private fun await(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline && !condition()) SystemClock.sleep(10L)
        assertTrue(condition())
    }

    private fun controllerRun(
        instrumentation: Instrumentation,
        block: () -> Unit,
    ) {
        instrumentation.runOnMainSync(block)
    }

    private fun <T> controllerRead(
        instrumentation: Instrumentation,
        block: () -> T,
    ): T {
        var value: T? = null
        instrumentation.runOnMainSync { value = block() }
        return requireNotNull(value)
    }

    private fun <T> controllerCall(
        instrumentation: Instrumentation,
        block: () -> T,
    ): T {
        var value: T? = null
        instrumentation.runOnMainSync { value = block() }
        return requireNotNull(value)
    }

    private data class SeekCase(val id: String, val targetMs: Long)
    private data class TraceSample(val timestampNs: Long, val text: String)

    private object ContentUrisCompat {
        fun parseId(uri: Uri): Long = uri.lastPathSegment!!.toLong()
    }

    private companion object Constants {
        val ENGINE_OUTPUT_PATTERN = Regex("seekToFirstOutputMs=(\\d+)")
        const val OFFICIAL_MODEL_ID = "htdemucs_6s_core_canonical_7p8s_fp32_v1_0_0"
        const val FIXTURE_DIRECTORY = "aac-long-s10-fixture"
        const val REPORT_DIRECTORY = "source-separation"
        const val STEM_COUNT = 6
        const val SAMPLE_RATE = 44_100
        const val FRAME_COUNT = 9_262_024
        const val DURATION_MS = 210_023L
        const val CASE_REPETITIONS = 4
        const val POSITION_ADVANCE_MS = 40L
    }
}
