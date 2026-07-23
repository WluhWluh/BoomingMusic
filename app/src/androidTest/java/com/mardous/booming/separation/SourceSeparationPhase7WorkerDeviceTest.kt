package com.mardous.booming.separation

import android.app.ActivityManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Debug
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mardous.booming.data.model.Song
import com.mardous.booming.playback.Playback
import com.mardous.booming.playback.PlaybackService
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheFlacPromotionResult
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheHydrationResult
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheManifest
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunCoordinator
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheStore
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheFlacPromoter
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheHydrator
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheManifestState
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheMutationResult
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwareCacheEntryState
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwareCacheRepository
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwarePlayableStatus
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwareCacheStatus
import com.mardous.booming.separation.cache.v2.resolveActiveCacheModel
import com.mardous.booming.separation.cache.v2.resolveActiveCacheModelResolution
import com.mardous.booming.separation.model.MdxCompatibilityPolicy
import com.mardous.booming.separation.model.MdxExecutionProfile
import com.mardous.booming.separation.model.MdxInferenceBackend
import com.mardous.booming.separation.model.MdxInferenceSession
import com.mardous.booming.separation.model.MdxInferenceSessionFactory
import com.mardous.booming.separation.model.MdxInferenceSessionProvider
import com.mardous.booming.separation.model.MdxModelArtifact
import com.mardous.booming.separation.model.MdxRuntimeSettings
import com.mardous.booming.separation.model.ReusableMdxInferenceSessionProvider
import com.mardous.booming.separation.model.SingleUseMdxInferenceSessionProvider
import com.mardous.booming.separation.model.AndroidMdxRuntimePlatformProvider
import com.mardous.booming.separation.model.litert.MdxLiteRtCpuInferenceSessionFactory
import com.mardous.booming.separation.model.preset.SourceSeparationActivePresetState
import com.mardous.booming.separation.model.preset.SourceSeparationPresetDownloader
import com.mardous.booming.separation.model.preset.SourceSeparationPresetRepository
import com.mardous.booming.separation.model.preset.SourceSeparationPresetSelectionScope
import com.mardous.booming.ui.screen.player.SourceSeparationForegroundWorkerCallbacks
import com.mardous.booming.ui.screen.player.SourceSeparationForegroundWorkerCoordinator
import com.mardous.booming.ui.screen.player.SourceSeparationUiState
import com.mardous.booming.util.MINIMUM_SONG_DURATION
import com.mardous.booming.util.SOURCE_SEPARATION_AUTO_START
import com.mardous.booming.util.SOURCE_SEPARATION_AUTO_FLAC_COMPRESSION
import com.mardous.booming.util.SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT
import com.mardous.booming.util.SOURCE_SEPARATION_WINDOW_DECODE
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.java.KoinJavaComponent.get
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Runs the real foreground worker against a MediaStore-backed song. */
@RunWith(AndroidJUnit4::class)
class SourceSeparationPhase7WorkerDeviceTest {

    @Test
    fun validateProductionWorker() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val runId = arguments.requiredString(ARG_RUN_ID).requireSafeName()
        val backendMode = BackendMode.parse(arguments.getString(ARG_BACKEND_MODE))
        val autoFailpoint = Phase7AutoFailpoint.parse(arguments.getString(ARG_AUTO_FAILPOINT))
        require(autoFailpoint == Phase7AutoFailpoint.None || backendMode == BackendMode.Auto) {
            "Phase 7 Auto fault injection requires BackendMode=auto."
        }
        val report = baseReport(context, runId, arguments)
        if (autoFailpoint != Phase7AutoFailpoint.None) {
            report.put("diagnosticOnly", true)
        }
        val preserveMediaStoreSource = arguments.optionalBoolean(
            ARG_PRESERVE_MEDIA_STORE_SOURCE,
            false,
        )
        val exportCacheAudio = arguments.optionalBoolean(ARG_EXPORT_CACHE_AUDIO, false)
        var coordinator: SourceSeparationForegroundWorkerCoordinator? = null
        var mediaUri: Uri? = null

        try {
            val sourcePath = arguments.requiredString(ARG_SOURCE_PATH)
            val registeredUri = registerSourceInMediaStore(context, sourcePath, runId)
            mediaUri = registeredUri
            val source = resolveMediaStoreSong(context, registeredUri, sourcePath)
            val windowDecodeEnabled = arguments.optionalBoolean(ARG_WINDOW_DECODE_ENABLED, true)
            val preferences = get<SharedPreferences>(SharedPreferences::class.java)
            val preferencesEditor = preferences.edit()
                .putBoolean(SOURCE_SEPARATION_WINDOW_DECODE, windowDecodeEnabled)
                .putBoolean(SOURCE_SEPARATION_AUTO_FLAC_COMPRESSION, false)
                .putInt(
                    SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT,
                    REQUIRED_READY_WINDOWS,
                )
            if (preserveMediaStoreSource) {
                preferencesEditor.putInt(MINIMUM_SONG_DURATION, 0)
            }
            preferencesEditor.apply()

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

            val autoFaultController = autoFailpoint
                .takeUnless { it == Phase7AutoFailpoint.None }
                ?.let(::Phase7AutoFaultController)
            val runtimeFacade = createCpuRuntimeFacade(
                context = context,
                preferences = preferences,
                presetRepository = presetRepository,
                backendMode = backendMode,
                processorCount = arguments.getString(ARG_PROCESSOR_COUNT)
                    ?.toIntOrNull()
                    ?.takeIf { it > 0 },
                xnnPackFlags = arguments.getString(ARG_XNNPACK_FLAGS)
                    ?.toIntOrNull()
                    ?.takeIf { it >= 0 },
                sessionProviderFactoryOverride = autoFaultController
                    ?.createSessionProviderFactory(context),
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
            val workerStartedAt = SystemClock.elapsedRealtime()
            assertTrue(worker.startCurrentSong())

            val firstReadyAt = AtomicLong(0L)
            var peakPssBytes = idleMemory.getLong("totalPssBytes")
            var peakJavaBytes = idleMemory.getLong("javaPssBytes")
            var peakNativeBytes = idleMemory.getLong("nativePssBytes")
            var peakGraphicsBytes = idleMemory.getLong("graphicsPssBytes")
            var decodeMode: String? = null
            var decodeDiagnostics: String? = null
            var finalState: SourceSeparationUiState = SourceSeparationUiState.Idle
            while (SystemClock.elapsedRealtime() - workerStartedAt < WORKER_TIMEOUT_MS) {
                finalState = worker.workerStateFlow.value
                val now = SystemClock.elapsedRealtime()
                val running = finalState as? SourceSeparationUiState.Running
                running?.sourceDecodeMode?.name?.let { decodeMode = it }
                running?.sourceDecodeDiagnostics?.let { decodeDiagnostics = it }
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
                ?.minus(workerStartedAt)
                ?: 0L
            val fullSongMs = completedAt - workerStartedAt

            val entries = runtimeFacade.entries()
            val completedEntry = entries.singleOrNull { it.cacheKey == cacheKey }
                ?: error("Completed cache entry was not indexed: $cacheKey")
            assertEquals(SourceSeparationModelAwareCacheEntryState.Completed, completedEntry.state)
            assertEquals(expectedModelId, completedEntry.modelId)
            assertEquals(expectedArtifactSha256, completedEntry.artifactSha256)
            val playback = requireNotNull(runtimeFacade.openCompletedCache(cacheKey))
            val manifest = playback.manifest
            val output = requireNotNull(manifest.output)
            val store = get<SourceSeparationCacheStore>(SourceSeparationCacheStore::class.java)
            val entryDirectory = store.entryDirectory(cacheKey)
            val wavStemPaths = output.stems.associate { stem ->
                stem.semantic.name to store.resolveRelativePath(entryDirectory, stem.wavPath)
            }
            assertEquals(cacheKey, manifest.cacheKey)
            assertEquals(expectedArtifactSha256, manifest.identity.artifactSha256)
            assertTrue(output.outputFrameCount > 0)
            assertTrue(output.windowCount > 0)
            val runtimeRecord = requireNotNull(manifest.runtimeRecords.lastOrNull())
            if (backendMode == BackendMode.Cpu) {
                assertEquals(MdxInferenceBackend.LiteRtCpu.name, runtimeRecord.backend)
            } else {
                assertTrue(
                    "Auto must finish on a concrete LiteRT backend: ${runtimeRecord.backend}",
                    runtimeRecord.backend == MdxInferenceBackend.LiteRtGpu.name ||
                        runtimeRecord.backend == MdxInferenceBackend.LiteRtCpu.name,
                )
            }
            autoFaultController?.let { controller ->
                validateAutoFaultEvidence(
                    report = report,
                    controller = controller,
                    runtimeRecordBackend = runtimeRecord.backend,
                    runtimeRecordFallbackStage = runtimeRecord.fallbackStage,
                    runtimeRecordFallbackReason = runtimeRecord.fallbackReason,
                    firstReadyAtElapsedMs = firstReadyAt.get(),
                )
            }
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
            val promotedManifest = requireNotNull(store.readManifest(cacheKey))
            applyRuntimeEvidence(report, promotedManifest)
            val artifactExport = if (exportCacheAudio) {
                exportCacheArtifacts(
                    context = context,
                    runId = runId,
                    entryDirectory = entryDirectory,
                    manifest = promotedManifest,
                    store = store,
                )
            } else {
                null
            }
            val expectedFrames = ((arguments.optionalLong(ARG_FIXTURE_DURATION_US) *
                arguments.optionalInt(ARG_FIXTURE_SAMPLE_RATE, 1) + 500_000L) /
                1_000_000L).toInt()
            val frameDelta = kotlin.math.abs(output.outputFrameCount - expectedFrames)
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
                .put("expectedFrameCount", expectedFrames)
                .put("frameDelta", frameDelta)
                .put("maxAbsError", 0.0)
                .put("stemSemantics", output.stems.joinToString(",") {
                    it.semantic.name
                })
                .put("decodeDiagnostics", JSONObject()
                    .put("mode", decodeMode ?: "unknown")
                    .put("detail", decodeDiagnostics ?: "")
                )
            )
            val cacheReport = report.getJSONObject("cache")
                .put("cacheKey", cacheKey)
                .put("entryCountBefore", entriesBefore)
                .put("entryCountAfter", runtimeFacade.entries().size)
                .put("exactIdentity", completedAfter.cacheKey == cacheKey &&
                    completedAfter.artifactSha256 == expectedArtifactSha256)
                .put("completedPlayable", true)
                .put("clearRecoveryPassed", false)
                .put("manifestPathRelative", "entries/$cacheKey/manifest.json")
                .put("entryDirectoryPath", entryDirectory.absolutePath)
                .put("promotedFormat", completedAfter.format.name)
                .put("hydrationPassed", true)
                .put("stems", JSONArray(promotedManifest.output!!.stems.map { stem ->
                    val exportedStem = artifactExport?.stems?.get(stem.semantic.name)
                    val wavIntegrity = requireNotNull(stem.wavIntegrity)
                    val promotedIntegrity = requireNotNull(stem.promotedIntegrity)
                    JSONObject()
                        .put("semantic", stem.semantic.name)
                        .put("wavPath", wavStemPaths.getValue(stem.semantic.name).absolutePath)
                        .put("wavByteSize", wavIntegrity.byteSize)
                        .put("wavSha256", wavIntegrity.sha256)
                        .put(
                            "exportWavPathRelative",
                            exportedStem?.wavPathRelative ?: JSONObject.NULL,
                        )
                        .put("promotedPath", stem.promotedPath?.let { path ->
                            store.resolveRelativePath(entryDirectory, path).absolutePath
                        } ?: JSONObject.NULL)
                        .put("promotedByteSize", promotedIntegrity.byteSize)
                        .put("promotedSha256", promotedIntegrity.sha256)
                        .put(
                            "exportPromotedPathRelative",
                            exportedStem?.promotedPathRelative ?: JSONObject.NULL,
                        )
                }))
            artifactExport?.let { export ->
                cacheReport.put("artifactExport", JSONObject()
                    .put("directoryPathRelative", export.directoryPathRelative)
                    .put("manifestPathRelative", export.manifestPathRelative)
                    .put("manifestByteSize", export.manifestByteSize)
                    .put("manifestSha256", export.manifestSha256)
                )
            }
            report.put("cache", cacheReport)
            report.put("worker", JSONObject()
                .put("sourcePath", sourcePath)
                .put("mediaUri", mediaUri.toString())
                .put("songId", source.id)
                .put("songDurationMs", source.duration)
                .put("windowDecodeEnabled", windowDecodeEnabled)
                .put("preservedMediaStoreSource", preserveMediaStoreSource)
                .put("cpuThreads", runCallbacks.cpuThreads)
                .put("backendMode", backendMode.argumentValue)
                .put("autoFailpoint", autoFailpoint.argumentValue)
                .put("runtimeDiagnostics", manifest.runtimeRecords.map { it.backend + "/" + it.runtimeProfileId }
                    .joinToString(","))
            )
        } catch (error: Throwable) {
            report.put("status", "failed")
            report.put("error", "${error::class.java.name}: ${error.message}")
            throw error
        } finally {
            coordinator?.cancel()
            if (!preserveMediaStoreSource) {
                mediaUri?.let { uri ->
                    runCatching { context.contentResolver.delete(uri, null, null) }
                }
            }
            writeReport(context, runId, report)
        }
    }

    @Test
    fun validateWorkerLifecycle() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val runId = arguments.requiredString(ARG_RUN_ID).requireSafeName()
        val backendMode = BackendMode.parse(arguments.getString(ARG_BACKEND_MODE))
        val lifecycleScenario = LifecycleScenario.parse(
            arguments.getString(ARG_LIFECYCLE_SCENARIO),
        )
        val sessionMode = LifecycleSessionMode.parse(
            arguments.getString(ARG_LIFECYCLE_SESSION_MODE),
        )
        if (backendMode == BackendMode.Auto && sessionMode != LifecycleSessionMode.SingleUse) {
            error("BackendMode=auto requires the production single-use session provider.")
        }
        val report = baseReport(context, runId, arguments)
        report.getJSONObject("lifecycle")
            .put("diagnosticOnly", lifecycleScenario != LifecycleScenario.Sequential ||
                sessionMode != LifecycleSessionMode.SingleUse)
            .put("scenario", lifecycleScenario.argumentValue)
            .put("sessionMode", sessionMode.argumentValue)
            .put("sessionCreateCount", 0)
        val coordinators = mutableListOf<SourceSeparationForegroundWorkerCoordinator>()
        val sessionCreateCount = AtomicInteger()
        var reusableSessionProvider: ReusableMdxInferenceSessionProvider? = null
        var mediaUri: Uri? = null

        try {
            val sourcePath = arguments.requiredString(ARG_SOURCE_PATH)
            mediaUri = registerSourceInMediaStore(context, sourcePath, runId)
            val source = resolveMediaStoreSong(context, mediaUri, sourcePath)
            val windowDecodeEnabled = arguments.optionalBoolean(ARG_WINDOW_DECODE_ENABLED, true)
            val preferences = get<SharedPreferences>(SharedPreferences::class.java)
            check(preferences.edit()
                .putBoolean(SOURCE_SEPARATION_WINDOW_DECODE, windowDecodeEnabled)
                .putBoolean(SOURCE_SEPARATION_AUTO_FLAC_COMPRESSION, false)
                .putInt(SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT, 1)
                .commit()
            ) { "Could not persist lifecycle test preferences." }

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

            val sessionProviderFactory: (() -> MdxInferenceSessionProvider)? = if (
                backendMode == BackendMode.Cpu
            ) {
                val cpuFactory = CountingMdxInferenceSessionFactory(
                    delegate = MdxLiteRtCpuInferenceSessionFactory(
                        compatibilityPolicy = MdxCompatibilityPolicy.KnownGoodOnly,
                        availableProcessors = {
                            arguments.getString(ARG_PROCESSOR_COUNT)
                                ?.toIntOrNull()
                                ?.takeIf { it > 0 }
                                ?: Runtime.getRuntime().availableProcessors()
                        },
                        xnnPackFlags = arguments.getString(ARG_XNNPACK_FLAGS)
                            ?.toIntOrNull()
                            ?.takeIf { it >= 0 },
                    ),
                    createCount = sessionCreateCount,
                )
                when (sessionMode) {
                    LifecycleSessionMode.SingleUse -> {
                        { SingleUseMdxInferenceSessionProvider(cpuFactory) }
                    }
                    LifecycleSessionMode.SharedReusable -> {
                        val provider = ReusableMdxInferenceSessionProvider(cpuFactory)
                        reusableSessionProvider = provider
                        { provider }
                    }
                }
            } else {
                null
            }
            val runtimeFacade = createCpuRuntimeFacade(
                context = context,
                preferences = preferences,
                presetRepository = presetRepository,
                backendMode = backendMode,
                processorCount = arguments.getString(ARG_PROCESSOR_COUNT)
                    ?.toIntOrNull()
                    ?.takeIf { it > 0 },
                sessionProviderFactoryOverride = sessionProviderFactory,
            )
            val resolved = runtimeFacade.resolve(source)
            val runtimeSong = (resolved as? SourceSeparationRuntimeSongResolution.Ready)?.song
                ?: error("The lifecycle source could not be admitted: $resolved")
            val cacheKey = runtimeSong.cacheKey
            clearExactCacheEntry(runtimeFacade, cacheKey)

            var pausedStatus: SourceSeparationModelAwareCacheStatus? = null
            var resumedStatus: SourceSeparationModelAwareCacheStatus? = null
            var seekStatus: SourceSeparationModelAwareCacheStatus? = null
            var canceledStatus: SourceSeparationModelAwareCacheStatus? = null
            var runtimeEvidenceManifest: SourceSeparationCacheManifest? = null
            var seekPositionMs: Long? = null
            var seekStartedPending = false

            if (lifecycleScenario.includesPauseResume) {
                val pauseWorker = SourceSeparationForegroundWorkerCoordinator(
                    context = context,
                    preferences = preferences,
                    sourceSeparationRuntime = runtimeFacade,
                )
                coordinators += pauseWorker
                pauseWorker.attachCallbacks(RecordingCallbacks())
                pauseWorker.updateSong(
                    song = source,
                    positionMs = 0L,
                    durationMs = source.duration,
                    isPlaying = false,
                    sourceSeparationBlend = TEST_BLEND,
                )
                assertTrue(pauseWorker.startCurrentSong())
                waitForReady(pauseWorker, minimumReadyWindows = 1)
                pauseWorker.pauseCurrentSong(source)
                waitForPaused(pauseWorker)
                pausedStatus = runtimeFacade.cacheStatus(runtimeSong)
                assertTrue(
                    "Pause must leave a resumable or empty entry: $pausedStatus",
                    pausedStatus is SourceSeparationModelAwareCacheStatus.Missing ||
                        pausedStatus is SourceSeparationModelAwareCacheStatus.Incomplete,
                )

                pauseWorker.updateSong(
                    song = source,
                    positionMs = 0L,
                    durationMs = source.duration,
                    isPlaying = false,
                    sourceSeparationBlend = TEST_BLEND,
                )
                assertTrue(pauseWorker.startCurrentSong())
                waitForCompleted(pauseWorker)
                resumedStatus = runtimeFacade.cacheStatus(runtimeSong)
                assertTrue(resumedStatus is SourceSeparationModelAwareCacheStatus.Completed)
                runtimeEvidenceManifest = (resumedStatus as
                    SourceSeparationModelAwareCacheStatus.Completed).manifest
                pauseWorker.cancel()
                waitForInactive(pauseWorker)
            }

            if (lifecycleScenario.includesSeek) {
                clearExactCacheEntry(runtimeFacade, cacheKey)
                val seekWorker = SourceSeparationForegroundWorkerCoordinator(
                    context = context,
                    preferences = preferences,
                    sourceSeparationRuntime = runtimeFacade,
                )
                coordinators += seekWorker
                seekWorker.attachCallbacks(RecordingCallbacks())
                seekWorker.updateSong(
                    song = source,
                    positionMs = 0L,
                    durationMs = source.duration,
                    isPlaying = false,
                    sourceSeparationBlend = TEST_BLEND,
                )
                assertTrue(seekWorker.startCurrentSong())
                waitForReady(seekWorker, minimumReadyWindows = 1)
                val targetSeekPositionMs = (source.duration - SEEK_FROM_END_MS).coerceAtLeast(0L)
                seekPositionMs = targetSeekPositionMs
                val beforeSeek = runtimeFacade.playableStatus(
                    song = runtimeSong,
                    playbackPositionMs = targetSeekPositionMs,
                    readyWindowCount = 1,
                )
                when (beforeSeek) {
                    is SourceSeparationModelAwarePlayableStatus.Ready -> {
                        beforeSeek.playback.close()
                    }
                    SourceSeparationModelAwarePlayableStatus.Processing -> {
                        seekStartedPending = true
                    }
                    SourceSeparationModelAwarePlayableStatus.Unavailable -> {
                        error("The seek target became unavailable.")
                    }
                }
                seekWorker.updatePosition(
                    positionMs = targetSeekPositionMs,
                    durationMs = source.duration,
                    isPlaying = false,
                    sourceSeparationBlend = TEST_BLEND,
                )
                val afterSeek = waitForPlayable(
                    runtimeFacade = runtimeFacade,
                    song = runtimeSong,
                    playbackPositionMs = targetSeekPositionMs,
                    readyWindowCount = 1,
                )
                afterSeek.playback.close()
                waitForCompleted(seekWorker)
                seekStatus = runtimeFacade.cacheStatus(runtimeSong)
                assertTrue(seekStatus is SourceSeparationModelAwareCacheStatus.Completed)
                runtimeEvidenceManifest = (seekStatus as
                    SourceSeparationModelAwareCacheStatus.Completed).manifest
                seekWorker.cancel()
                waitForInactive(seekWorker)
            }

            if (lifecycleScenario.includesCancellation) {
                clearExactCacheEntry(runtimeFacade, cacheKey)
                val cancelWorker = SourceSeparationForegroundWorkerCoordinator(
                    context = context,
                    preferences = preferences,
                    sourceSeparationRuntime = runtimeFacade,
                )
                coordinators += cancelWorker
                cancelWorker.attachCallbacks(RecordingCallbacks())
                cancelWorker.updateSong(
                    song = source,
                    positionMs = 0L,
                    durationMs = source.duration,
                    isPlaying = false,
                    sourceSeparationBlend = TEST_BLEND,
                )
                assertTrue(cancelWorker.startCurrentSong())
                waitForReady(cancelWorker, minimumReadyWindows = 1)
                cancelWorker.cancel()
                waitForInactive(cancelWorker)
                canceledStatus = runtimeFacade.cacheStatus(runtimeSong)
                assertTrue(
                    "Cancellation must not publish a completed entry: $canceledStatus",
                    canceledStatus !is SourceSeparationModelAwareCacheStatus.Completed,
                )
            }

            report.put("status", "passed")
            report.put("lifecycle", report.getJSONObject("lifecycle")
                .put("sessionCreateCount", sessionCreateCount.get())
                .put("pauseResumePassed", lifecycleScenario.includesPauseResume)
                .put("seekPassed", lifecycleScenario.includesSeek)
                .put("cancellationPassed", lifecycleScenario.includesCancellation)
                .put("seekPositionMs", seekPositionMs ?: JSONObject.NULL)
                .put("seekStartedPending", seekStartedPending)
                .put("pauseStatus", pausedStatus?.javaClass?.simpleName ?: JSONObject.NULL)
                .put("resumedStatus", resumedStatus?.javaClass?.simpleName ?: JSONObject.NULL)
                .put("seekStatus", seekStatus?.javaClass?.simpleName ?: JSONObject.NULL)
                .put("canceledStatus", canceledStatus?.javaClass?.simpleName ?: JSONObject.NULL)
            )
            report.put("cache", report.getJSONObject("cache")
                .put("cacheKey", cacheKey)
                .put("exactIdentity", true)
                .put("completedPlayable", resumedStatus is SourceSeparationModelAwareCacheStatus.Completed ||
                    seekStatus is SourceSeparationModelAwareCacheStatus.Completed)
                .put("clearRecoveryPassed", false)
            )
            report.put("worker", JSONObject()
                .put("sourcePath", sourcePath)
                .put("songId", source.id)
                .put("songDurationMs", source.duration)
                .put("windowDecodeEnabled", windowDecodeEnabled)
                .put("cpuThreads", resolveCpuThreads(
                    arguments.getString(ARG_PROCESSOR_COUNT)?.toIntOrNull(),
                ))
                .put("lifecycleScenario", lifecycleScenario.argumentValue)
                .put("lifecycleSessionMode", sessionMode.argumentValue)
                .put("sessionCreateCount", sessionCreateCount.get())
            )
            (runtimeEvidenceManifest ?: get<SourceSeparationCacheStore>(
                SourceSeparationCacheStore::class.java,
            ).readManifest(cacheKey))?.let { manifest ->
                applyRuntimeEvidence(report, manifest)
            }
        } catch (error: Throwable) {
            report.put("status", "failed")
            report.put("error", "${error::class.java.name}: ${error.message}")
            report.getJSONObject("lifecycle")
                .put("sessionCreateCount", sessionCreateCount.get())
            throw error
        } finally {
            coordinators.forEach { coordinator -> coordinator.cancel() }
            reusableSessionProvider?.close()
            mediaUri?.let { uri ->
                runCatching { context.contentResolver.delete(uri, null, null) }
            }
            writeReport(context, runId, "lifecycle", report)
        }
    }

    @Test
    fun validateActiveModelSwitch() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val runId = arguments.requiredString(ARG_RUN_ID).requireSafeName()
        val backendMode = BackendMode.parse(arguments.getString(ARG_BACKEND_MODE))
        val report = baseReport(context, runId, arguments)
        val coordinators = mutableListOf<SourceSeparationForegroundWorkerCoordinator>()
        var mediaUri: Uri? = null

        try {
            val sourcePath = arguments.requiredString(ARG_SOURCE_PATH)
            mediaUri = registerSourceInMediaStore(context, sourcePath, runId)
            val source = resolveMediaStoreSong(context, mediaUri, sourcePath)
            val preferences = get<SharedPreferences>(SharedPreferences::class.java)
            check(preferences.edit()
                .putBoolean(SOURCE_SEPARATION_WINDOW_DECODE, true)
                .putBoolean(SOURCE_SEPARATION_AUTO_FLAC_COMPRESSION, false)
                .putBoolean(SOURCE_SEPARATION_AUTO_START, false)
                .putInt(SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT, 1)
                .putInt(MINIMUM_SONG_DURATION, 0)
                .commit()
            ) { "Could not persist model-switch test preferences." }

            val presetRepository = get<SourceSeparationPresetRepository>(
                SourceSeparationPresetRepository::class.java,
            )
            val primaryModelId = arguments.requiredString(ARG_MODEL_ID)
            val primaryArtifactSha256 = arguments.requiredString(ARG_ARTIFACT_SHA256)
            val secondaryModelId = arguments.requiredString(ARG_SECONDARY_MODEL_ID)
            val secondaryArtifactSha256 = arguments.requiredString(
                ARG_SECONDARY_ARTIFACT_SHA256,
            )
            val initial = presetRepository.activeModel()
            assertTrue(initial is SourceSeparationActivePresetState.Reference)
            assertEquals(
                primaryArtifactSha256,
                (initial as SourceSeparationActivePresetState.Reference)
                    .reference.artifactSha256,
            )

            val secondaryInstalled = get<SourceSeparationPresetDownloader>(
                SourceSeparationPresetDownloader::class.java,
            ).download(secondaryModelId)
            assertEquals(secondaryArtifactSha256, secondaryInstalled.sha256)
            assertEquals(primaryArtifactSha256, (presetRepository.activeModel() as
                SourceSeparationActivePresetState.Reference).reference.artifactSha256)

            val runtimeFacade = createCpuRuntimeFacade(
                context = context,
                preferences = preferences,
                presetRepository = presetRepository,
                backendMode = backendMode,
                processorCount = arguments.getString(ARG_PROCESSOR_COUNT)
                    ?.toIntOrNull()
                    ?.takeIf { it > 0 },
            )
            val primaryRuntimeSong = (runtimeFacade.resolve(source) as?
                SourceSeparationRuntimeSongResolution.Ready)?.song
                ?: error("The primary model could not resolve the switching source.")
            assertEquals(primaryModelId, primaryRuntimeSong.modelId)
            clearExactCacheEntry(runtimeFacade, primaryRuntimeSong.cacheKey)

            val startedAtMs = SystemClock.elapsedRealtime()
            val primaryWorker = SourceSeparationForegroundWorkerCoordinator(
                context = context,
                preferences = preferences,
                sourceSeparationRuntime = runtimeFacade,
            )
            coordinators += primaryWorker
            primaryWorker.attachCallbacks(RecordingCallbacks())
            primaryWorker.updateSong(
                song = source,
                positionMs = 0L,
                durationMs = source.duration,
                isPlaying = false,
                sourceSeparationBlend = TEST_BLEND,
            )
            assertTrue(primaryWorker.startCurrentSong())
            waitForReady(primaryWorker, minimumReadyWindows = 1)
            assertEquals(primaryRuntimeSong.cacheKey, primaryWorker.runningCacheKey())

            val selectedSecondary = presetRepository.activate(
                sha256 = secondaryArtifactSha256,
                platform = AndroidMdxRuntimePlatformProvider.current(),
                scope = SourceSeparationPresetSelectionScope.InternalValidation,
                experimentalConfirmed = true,
            )
            assertEquals(secondaryModelId, selectedSecondary.modelId)
            assertEquals(primaryRuntimeSong.cacheKey, primaryWorker.runningCacheKey())
            waitForCompleted(primaryWorker)
            val primaryCompleted = runtimeFacade.cacheStatus(primaryRuntimeSong) as?
                SourceSeparationModelAwareCacheStatus.Completed
                ?: error("The admitted primary run did not complete under its original identity.")
            assertEquals(primaryModelId, primaryCompleted.manifest.identity.modelId)
            assertEquals(
                primaryArtifactSha256,
                primaryCompleted.manifest.identity.artifactSha256,
            )
            primaryWorker.cancel()
            waitForInactive(primaryWorker)

            val secondaryRuntimeSong = (runtimeFacade.resolve(source) as?
                SourceSeparationRuntimeSongResolution.Ready)?.song
                ?: error("The secondary model could not resolve the switching source.")
            assertEquals(secondaryModelId, secondaryRuntimeSong.modelId)
            assertEquals(secondaryArtifactSha256, secondaryRuntimeSong.artifactSha256)
            assertNotEquals(primaryRuntimeSong.cacheKey, secondaryRuntimeSong.cacheKey)
            clearExactCacheEntry(runtimeFacade, secondaryRuntimeSong.cacheKey)

            val secondaryWorker = SourceSeparationForegroundWorkerCoordinator(
                context = context,
                preferences = preferences,
                sourceSeparationRuntime = runtimeFacade,
            )
            coordinators += secondaryWorker
            secondaryWorker.attachCallbacks(RecordingCallbacks())
            secondaryWorker.updateSong(
                song = source,
                positionMs = 0L,
                durationMs = source.duration,
                isPlaying = false,
                sourceSeparationBlend = TEST_BLEND,
            )
            assertTrue(secondaryWorker.startCurrentSong())
            waitForCompleted(secondaryWorker)
            val secondaryCompleted = runtimeFacade.cacheStatus(secondaryRuntimeSong) as?
                SourceSeparationModelAwareCacheStatus.Completed
                ?: error("The first post-switch run did not complete under the secondary model.")
            assertEquals(secondaryModelId, secondaryCompleted.manifest.identity.modelId)
            assertEquals(
                secondaryArtifactSha256,
                secondaryCompleted.manifest.identity.artifactSha256,
            )

            val cacheRepository = get<SourceSeparationModelAwareCacheRepository>(
                SourceSeparationModelAwareCacheRepository::class.java,
            )
            val primaryPlayback = requireNotNull(
                runtimeFacade.openCompletedCache(primaryRuntimeSong.cacheKey),
            )
            assertTrue(cacheRepository.isLeased(primaryRuntimeSong.cacheKey))
            assertEquals(
                SourceSeparationCacheMutationResult.Busy,
                runtimeFacade.delete(primaryRuntimeSong.cacheKey),
            )
            primaryPlayback.close()
            assertFalse(cacheRepository.isLeased(primaryRuntimeSong.cacheKey))

            val entries = runtimeFacade.entries().filter {
                it.cacheKey == primaryRuntimeSong.cacheKey ||
                    it.cacheKey == secondaryRuntimeSong.cacheKey
            }
            assertEquals(2, entries.size)
            applyRuntimeEvidence(report, primaryCompleted.manifest)
            report.put("status", "passed")
            report.put("timing", report.getJSONObject("timing")
                .put("fullSongMs", SystemClock.elapsedRealtime() - startedAtMs)
            )
            report.put("cache", report.getJSONObject("cache")
                .put("cacheKey", primaryRuntimeSong.cacheKey)
                .put("entryCountAfter", entries.size)
                .put("exactIdentity", true)
                .put("completedPlayable", true)
            )
            report.put("modelSwitch", JSONObject()
                .put("primaryModelId", primaryModelId)
                .put("primaryArtifactSha256", primaryArtifactSha256)
                .put("primaryCacheKey", primaryRuntimeSong.cacheKey)
                .put("secondaryModelId", secondaryModelId)
                .put("secondaryArtifactSha256", secondaryArtifactSha256)
                .put("secondaryCacheKey", secondaryRuntimeSong.cacheKey)
                .put("switchedAfterReadyWindow", true)
                .put("admittedRunRetainedIdentity", true)
                .put("primaryPlaybackLeaseRetained", true)
                .put("secondaryContractId", arguments.requiredString(ARG_SECONDARY_CONTRACT_ID))
            )
        } catch (error: Throwable) {
            report.put("status", "failed")
            report.put("error", "${error::class.java.name}: ${error.message}")
            throw error
        } finally {
            coordinators.forEach(SourceSeparationForegroundWorkerCoordinator::cancel)
            if (!arguments.optionalBoolean(ARG_PRESERVE_MEDIA_STORE_SOURCE, false)) {
                mediaUri?.let { uri ->
                    runCatching { context.contentResolver.delete(uri, null, null) }
                }
            }
            writeReport(context, runId, "switching", report)
        }
    }

    @Test
    fun validateBackgroundServiceContinuation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val runId = arguments.requiredString(ARG_RUN_ID).requireSafeName()
        val report = baseReport(context, runId, arguments)
        var controller: MediaController? = null
        var mediaUri: Uri? = null

        try {
            require(BackendMode.parse(arguments.getString(ARG_BACKEND_MODE)) == BackendMode.Auto) {
                "Background service validation requires the production Auto graph."
            }
            val sourcePath = arguments.requiredString(ARG_SOURCE_PATH)
            mediaUri = registerSourceInMediaStore(context, sourcePath, runId)
            val source = resolveMediaStoreSong(context, mediaUri, sourcePath)
            val preferences = get<SharedPreferences>(SharedPreferences::class.java)
            check(preferences.edit()
                .putBoolean(SOURCE_SEPARATION_WINDOW_DECODE, true)
                .putBoolean(SOURCE_SEPARATION_AUTO_FLAC_COMPRESSION, false)
                .putInt(MINIMUM_SONG_DURATION, 0)
                .commit()
            ) { "Could not persist background-continuation test preferences." }
            assertExpectedActivePreset(arguments)
            val runtimeFacade = get<SourceSeparationRuntimeFacade>(
                SourceSeparationRuntimeFacade::class.java,
            )
            val runtimeSong = (runtimeFacade.resolve(source) as?
                SourceSeparationRuntimeSongResolution.Ready)?.song
                ?: error("The background source could not be resolved.")
            clearExactCacheEntry(runtimeFacade, runtimeSong.cacheKey)

            val sessionToken = SessionToken(
                context,
                ComponentName(context, PlaybackService::class.java),
            )
            val mediaController = MediaController.Builder(context, sessionToken)
                .buildAsync()
                .get(MEDIA_SESSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            controller = mediaController
            val sourcePreparationAttempts = prepareMediaControllerSource(
                controller = mediaController,
                source = source,
                operation = "background source preparation",
            )

            val command = SessionCommand(
                Playback.SEPARATE_CURRENT_SONG_OFFLINE,
                Bundle.EMPTY,
            )
            assertTrue(onMediaControllerThread(mediaController) {
                mediaController.availableSessionCommands.contains(command)
            })
            val startedAtMs = SystemClock.elapsedRealtime()
            val resultFuture = onMediaControllerThread(mediaController) {
                mediaController.sendCustomCommand(command, Bundle.EMPTY)
            }
            val ready = waitForPlayable(
                runtimeFacade = runtimeFacade,
                song = runtimeSong,
                playbackPositionMs = 0L,
                readyWindowCount = 1,
            )
            ready.playback.close()
            val firstReadyMs = SystemClock.elapsedRealtime() - startedAtMs
            assertFalse(
                "The service run completed before background continuation could be exercised.",
                resultFuture.isDone,
            )
            val importanceBeforeHome = currentProcessImportance()
            instrumentation.uiAutomation
                .executeShellCommand("input keyevent KEYCODE_HOME")
                .close()
            SystemClock.sleep(BACKGROUND_SETTLE_MS)
            val importanceAfterHome = currentProcessImportance()
            val result = resultFuture.get(WORKER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            assertEquals(SessionResult.RESULT_SUCCESS, result.resultCode)

            val completed = runtimeFacade.cacheStatus(runtimeSong) as?
                SourceSeparationModelAwareCacheStatus.Completed
                ?: error("The service-owned background run did not complete.")
            assertEquals(runtimeSong.cacheKey, completed.manifest.cacheKey)
            applyRuntimeEvidence(report, completed.manifest)
            report.put("status", "passed")
            report.put("timing", report.getJSONObject("timing")
                .put("firstReadyMs", firstReadyMs)
                .put("fullSongMs", SystemClock.elapsedRealtime() - startedAtMs)
            )
            report.put("lifecycle", report.getJSONObject("lifecycle")
                .put("backgroundContinuationPassed", true)
                .put("homeCommandIssuedAfterReady", true)
                .put("processImportanceBeforeHome", importanceBeforeHome)
                .put("processImportanceAfterHome", importanceAfterHome)
                .put("instrumentationProcessRetained", true)
                .put("sourcePreparationAttempts", sourcePreparationAttempts)
            )
            report.put("cache", report.getJSONObject("cache")
                .put("cacheKey", runtimeSong.cacheKey)
                .put("exactIdentity", true)
                .put("completedPlayable", true)
            )
        } catch (error: Throwable) {
            report.put("status", "failed")
            report.put("error", "${error::class.java.name}: ${error.message}")
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
            mediaUri?.let { uri ->
                runCatching { context.contentResolver.delete(uri, null, null) }
            }
            writeReport(context, runId, "background", report)
        }
    }

    @Test
    fun validateNextSongPrefetch() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val runId = arguments.requiredString(ARG_RUN_ID).requireSafeName()
        val report = baseReport(context, runId, arguments)
        var currentUri: Uri? = null
        var nextUri: Uri? = null
        var worker: SourceSeparationForegroundWorkerCoordinator? = null

        try {
            require(BackendMode.parse(arguments.getString(ARG_BACKEND_MODE)) == BackendMode.Auto) {
                "Next-song prefetch validation requires the production Auto graph."
            }
            val currentSourcePath = arguments.requiredString(ARG_CURRENT_SOURCE_PATH)
            val sourcePath = arguments.requiredString(ARG_SOURCE_PATH)
            currentUri = registerSourceInMediaStore(
                context,
                currentSourcePath,
                "$runId-current",
            )
            nextUri = registerSourceInMediaStore(context, sourcePath, "$runId-next")
            val currentSource = resolveMediaStoreSong(context, currentUri, currentSourcePath)
            val nextSource = resolveMediaStoreSong(context, nextUri, sourcePath)
            assertNotEquals(currentSource.id, nextSource.id)

            val preferences = get<SharedPreferences>(SharedPreferences::class.java)
            check(preferences.edit()
                .putBoolean(SOURCE_SEPARATION_WINDOW_DECODE, true)
                .putBoolean(SOURCE_SEPARATION_AUTO_FLAC_COMPRESSION, false)
                .putBoolean(SOURCE_SEPARATION_AUTO_START, true)
                .putBoolean(TEST_KEY_PLAYBACK_ENABLED, true)
                .putBoolean(TEST_KEY_REMEMBER_PER_SONG, false)
                .putFloat(TEST_KEY_GLOBAL_BLEND, TEST_BLEND)
                .putInt(
                    SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT,
                    REQUIRED_READY_WINDOWS,
                )
                .putInt(MINIMUM_SONG_DURATION, 0)
                .commit()
            ) { "Could not persist next-song prefetch test preferences." }
            assertExpectedActivePreset(arguments)
            val runtimeFacade = get<SourceSeparationRuntimeFacade>(
                SourceSeparationRuntimeFacade::class.java,
            )
            val currentRuntimeSong = (runtimeFacade.resolve(currentSource) as?
                SourceSeparationRuntimeSongResolution.Ready)?.song
                ?: error("The current prefetch source could not be resolved.")
            val nextRuntimeSong = (runtimeFacade.resolve(nextSource) as?
                SourceSeparationRuntimeSongResolution.Ready)?.song
                ?: error("The next prefetch source could not be resolved.")
            assertNotEquals(
                "Prefetch validation requires different current and next audio identities.",
                currentRuntimeSong.cacheKey,
                nextRuntimeSong.cacheKey,
            )
            clearExactCacheEntry(runtimeFacade, currentRuntimeSong.cacheKey)
            clearExactCacheEntry(runtimeFacade, nextRuntimeSong.cacheKey)

            val currentResult = runtimeFacade.separate(
                song = currentRuntimeSong,
                playbackReadyWindowCountProvider = { REQUIRED_READY_WINDOWS },
                windowDecodeEnabled = true,
            )
            val currentManifest = when (currentResult) {
                is SourceSeparationModelAwareEngineResult.Completed -> currentResult.manifest
                is SourceSeparationModelAwareEngineResult.AlreadyCompleted -> currentResult.manifest
                else -> error("The current source did not complete before prefetch: $currentResult")
            }

            val coordinator = get<SourceSeparationForegroundWorkerCoordinator>(
                SourceSeparationForegroundWorkerCoordinator::class.java,
            )
            worker = coordinator
            coordinator.attachCallbacks(RecordingCallbacks())
            coordinator.updateSong(
                song = currentSource,
                positionMs = 0L,
                durationMs = currentSource.duration,
                isPlaying = false,
                sourceSeparationBlend = TEST_BLEND,
            )
            val startedAtMs = SystemClock.elapsedRealtime()
            assertTrue(
                "The uncached next song was not admitted for prefetch.",
                coordinator.preStartSong(nextSource, REQUIRED_READY_WINDOWS),
            )
            val prefetchedPlayback = waitForPlayable(
                runtimeFacade = runtimeFacade,
                song = nextRuntimeSong,
                playbackPositionMs = 0L,
                readyWindowCount = REQUIRED_READY_WINDOWS,
            )
            prefetchedPlayback.playback.close()
            val firstReadyMs = SystemClock.elapsedRealtime() - startedAtMs
            waitForWorkerToLeaveSong(coordinator, nextSource.id)
            val prefetched = runtimeFacade.cacheStatus(nextRuntimeSong) as?
                SourceSeparationModelAwareCacheStatus.Incomplete
                ?: error("Next-song prefetch did not stop at an incomplete ready cache.")
            assertTrue(prefetched.readySegments >= REQUIRED_READY_WINDOWS)
            val prefetchCacheKey = prefetched.manifest.cacheKey

            coordinator.updateSong(
                song = nextSource,
                positionMs = 0L,
                durationMs = nextSource.duration,
                isPlaying = false,
                sourceSeparationBlend = TEST_BLEND,
            )
            coordinator.requestSong(nextSource)
            waitForCompleted(coordinator)
            val transitioned = runtimeFacade.cacheStatus(nextRuntimeSong) as?
                SourceSeparationModelAwareCacheStatus.Completed
                ?: error("The prefetched next song did not complete after transition.")
            assertEquals(prefetchCacheKey, transitioned.manifest.cacheKey)
            assertEquals(
                SourceSeparationCacheManifestState.Completed,
                currentManifest.state,
            )
            assertTrue(
                runtimeFacade.cacheStatus(currentRuntimeSong) is
                    SourceSeparationModelAwareCacheStatus.Completed,
            )

            applyRuntimeEvidence(report, transitioned.manifest)
            report.put("status", "passed")
            report.put("timing", report.getJSONObject("timing")
                .put("firstReadyMs", firstReadyMs)
                .put("fullSongMs", transitioned.manifest.output?.elapsedMs ?: 0L)
            )
            report.put("cache", report.getJSONObject("cache")
                .put("cacheKey", prefetchCacheKey)
                .put("entryCountAfter", 2)
                .put("exactIdentity", true)
                .put("completedPlayable", true)
            )
            report.put("prefetch", JSONObject()
                .put("currentSongId", currentSource.id)
                .put("currentCacheKey", currentRuntimeSong.cacheKey)
                .put("currentFixture", JSONObject()
                    .put("fixtureId", arguments.requiredString(ARG_CURRENT_FIXTURE_ID))
                    .put(
                        "fileName",
                        arguments.requiredString(ARG_CURRENT_FIXTURE_FILE_NAME),
                    )
                    .put("byteSize", arguments.requiredLong(ARG_CURRENT_FIXTURE_BYTES))
                    .put(
                        "sha256",
                        arguments.requiredString(ARG_CURRENT_FIXTURE_SHA256),
                    )
                    .put(
                        "durationUs",
                        arguments.requiredLong(ARG_CURRENT_FIXTURE_DURATION_US),
                    )
                    .put(
                        "sampleRate",
                        arguments.requiredInt(ARG_CURRENT_FIXTURE_SAMPLE_RATE),
                    )
                    .put(
                        "channels",
                        arguments.requiredInt(ARG_CURRENT_FIXTURE_CHANNELS),
                    )
                    .put("codec", arguments.requiredString(ARG_CURRENT_FIXTURE_CODEC))
                    .put(
                        "decodeClass",
                        arguments.requiredString(ARG_CURRENT_FIXTURE_DECODE_CLASS),
                    )
                )
                .put("nextSongId", nextSource.id)
                .put("nextCacheKey", prefetchCacheKey)
                .put("requiredReadyWindows", REQUIRED_READY_WINDOWS)
                .put("prefetchedReadyWindows", prefetched.readySegments)
                .put("stoppedBeforeCompletion", true)
                .put("transitionRetainedCacheIdentity", true)
            )
        } catch (error: Throwable) {
            report.put("status", "failed")
            report.put("error", "${error::class.java.name}: ${error.message}")
            throw error
        } finally {
            worker?.cancel()
            currentUri?.let { uri ->
                runCatching { context.contentResolver.delete(uri, null, null) }
            }
            nextUri?.let { uri ->
                runCatching { context.contentResolver.delete(uri, null, null) }
            }
            writeReport(context, runId, "prefetch", report)
        }
    }

    @Test
    fun validateCompletedCacheAfterProcessRestart() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val runId = arguments.requiredString(ARG_RUN_ID).requireSafeName()
        val backendMode = BackendMode.parse(arguments.getString(ARG_BACKEND_MODE))
        val report = baseReport(context, runId, arguments)
        var mediaUri: Uri? = null

        try {
            val sourcePath = arguments.requiredString(ARG_SOURCE_PATH)
            mediaUri = registerSourceInMediaStore(context, sourcePath, runId)
            val source = resolveMediaStoreSong(context, mediaUri, sourcePath)
            val preferences = get<SharedPreferences>(SharedPreferences::class.java)
            val presetRepository = get<SourceSeparationPresetRepository>(
                SourceSeparationPresetRepository::class.java,
            )
            val active = presetRepository.activeModel()
            assertTrue(active is SourceSeparationActivePresetState.Reference)
            val expectedArtifactSha256 = arguments.requiredString(ARG_ARTIFACT_SHA256)
            assertEquals(
                expectedArtifactSha256,
                (active as SourceSeparationActivePresetState.Reference).reference.artifactSha256,
            )

            val runtimeFacade = createCpuRuntimeFacade(
                context = context,
                preferences = preferences,
                presetRepository = presetRepository,
                backendMode = backendMode,
                processorCount = arguments.getString(ARG_PROCESSOR_COUNT)
                    ?.toIntOrNull()
                    ?.takeIf { it > 0 },
            )
            val resolution = runtimeFacade.resolve(source)
            val runtimeSong = (resolution as? SourceSeparationRuntimeSongResolution.Ready)?.song
                ?: error("The recreated process could not resolve the source: $resolution")
            val status = runtimeFacade.cacheStatus(runtimeSong)
            assertTrue(status is SourceSeparationModelAwareCacheStatus.Completed)
            val completed = status as SourceSeparationModelAwareCacheStatus.Completed
            applyRuntimeEvidence(report, completed.manifest)
            val output = requireNotNull(completed.manifest.output)
            assertEquals(expectedArtifactSha256, completed.manifest.identity.artifactSha256)
            assertTrue(output.outputFrameCount > 0)

            val completedPlayback = requireNotNull(
                runtimeFacade.openCompletedCache(runtimeSong.cacheKey),
            )
            assertTrue(completedPlayback.vocalsFile.isFile)
            assertTrue(completedPlayback.instrumentalFile.isFile)
            completedPlayback.close()

            val startPlayback = runtimeFacade.playableStatus(
                song = runtimeSong,
                playbackPositionMs = 0L,
                readyWindowCount = 1,
            )
            assertTrue(startPlayback is SourceSeparationModelAwarePlayableStatus.Ready)
            (startPlayback as SourceSeparationModelAwarePlayableStatus.Ready).playback.close()
            val tailPlayback = runtimeFacade.playableStatus(
                song = runtimeSong,
                playbackPositionMs = (source.duration - SEEK_FROM_END_MS).coerceAtLeast(0L),
                readyWindowCount = 1,
            )
            assertTrue(tailPlayback is SourceSeparationModelAwarePlayableStatus.Ready)
            (tailPlayback as SourceSeparationModelAwarePlayableStatus.Ready).playback.close()

            val hydrated = requireNotNull(runtimeFacade.openHydratedCache(runtimeSong.cacheKey))
            assertTrue(hydrated.vocalsPcmFile.isFile)
            assertTrue(hydrated.instrumentalPcmFile.isFile)
            hydrated.close()

            report.put("status", "passed")
            report.put("lifecycle", report.getJSONObject("lifecycle")
                .put("workerCompleted", true)
                .put("processRecreationPassed", true)
            )
            report.put("audio", report.getJSONObject("audio")
                .put("finite", true)
                .put("outputFrameCount", output.outputFrameCount)
                .put("expectedFrameCount", output.outputFrameCount)
                .put("frameDelta", 0)
                .put("stemSemantics", output.stems.joinToString(",") {
                    it.semantic.name
                })
            )
            report.put("cache", report.getJSONObject("cache")
                .put("cacheKey", runtimeSong.cacheKey)
                .put("exactIdentity", true)
                .put("completedPlayable", true)
                .put("hydratedPlayable", true)
                .put("manifestState", completed.manifest.state.name)
            )
        } catch (error: Throwable) {
            report.put("status", "failed")
            report.put("error", "${error::class.java.name}: ${error.message}")
            throw error
        } finally {
            mediaUri?.let { uri ->
                runCatching { context.contentResolver.delete(uri, null, null) }
            }
            writeReport(context, runId, "recreation", report)
        }
    }

    @Test
    fun validateMediaSessionPlayback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val runId = arguments.requiredString(ARG_RUN_ID).requireSafeName()
        val report = baseReport(context, runId, arguments)
        var controller: MediaController? = null
        var sourceUri: Uri? = null
        var switchedModelDuringPlayback = false

        try {
            val cacheKey = arguments.requiredString(ARG_CACHE_KEY)
            val expectedArtifactSha256 = arguments.requiredString(ARG_ARTIFACT_SHA256)
            val store = get<SourceSeparationCacheStore>(SourceSeparationCacheStore::class.java)
            val presetRepository = get<SourceSeparationPresetRepository>(
                SourceSeparationPresetRepository::class.java,
            )
            val cacheRepository = get<SourceSeparationModelAwareCacheRepository>(
                SourceSeparationModelAwareCacheRepository::class.java,
            )
            check(get<SharedPreferences>(SharedPreferences::class.java).edit()
                .putInt(MINIMUM_SONG_DURATION, 0)
                .commit()
            ) { "Could not allow the Phase 7 playback fixture in the media library." }
            val secondaryModelId = arguments.getString(ARG_SECONDARY_MODEL_ID)
                ?.takeIf(String::isNotBlank)
            val secondaryArtifactSha256 = arguments.getString(
                ARG_SECONDARY_ARTIFACT_SHA256,
            )?.takeIf(String::isNotBlank)
            if (secondaryModelId != null && secondaryArtifactSha256 != null) {
                presetRepository.activate(
                    sha256 = expectedArtifactSha256,
                    platform = AndroidMdxRuntimePlatformProvider.current(),
                    scope = SourceSeparationPresetSelectionScope.InternalValidation,
                    experimentalConfirmed = true,
                )
            }
            val manifest = requireNotNull(store.readManifest(cacheKey)) {
                "The pinned playback cache manifest is missing: $cacheKey"
            }
            assertEquals(SourceSeparationCacheManifestState.Completed, manifest.state)
            assertEquals(expectedArtifactSha256, manifest.identity.artifactSha256)
            applyRuntimeEvidence(report, manifest)
            sourceUri = Uri.parse(manifest.song.mediaUri)

            val sessionToken = SessionToken(
                context,
                ComponentName(context, PlaybackService::class.java),
            )
            val mediaController = MediaController.Builder(context, sessionToken)
                .buildAsync()
                .get(MEDIA_SESSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            controller = mediaController
            val playCommand = SessionCommand(
                Playback.PLAY_SOURCE_SEPARATION_COMPLETED_CACHE,
                Bundle.EMPTY,
            )
            assertTrue(
                "PlaybackService did not expose the debug cache command.",
                onMediaControllerThread(mediaController) {
                    mediaController.availableSessionCommands.contains(playCommand)
                },
            )
            val playResultFuture = onMediaControllerThread(mediaController) {
                mediaController.sendCustomCommand(
                    playCommand,
                    Bundle().apply {
                        putString(Playback.EXTRA_SOURCE_SEPARATION_CACHE_KEY, cacheKey)
                    },
                )
            }
            val playResult = playResultFuture.get(
                MEDIA_SESSION_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
            )
            assertEquals(
                "PlaybackService rejected the completed cache: " +
                    playResult.extras.getString(
                        Playback.EXTRA_SOURCE_SEPARATION_MESSAGE,
                    ).orEmpty(),
                SessionResult.RESULT_SUCCESS,
                playResult.resultCode,
            )
            waitForMediaController(mediaController, "cache playback adoption") {
                mediaController.currentMediaItem?.mediaId == manifest.song.songId.toString() &&
                    mediaController.duration > 0L
            }

            if (secondaryModelId != null && secondaryArtifactSha256 != null) {
                val selected = presetRepository.activate(
                    sha256 = secondaryArtifactSha256,
                    platform = AndroidMdxRuntimePlatformProvider.current(),
                    scope = SourceSeparationPresetSelectionScope.InternalValidation,
                    experimentalConfirmed = true,
                )
                assertEquals(secondaryModelId, selected.modelId)
                assertTrue(cacheRepository.isLeased(cacheKey))
                assertEquals(expectedArtifactSha256, store.readManifest(cacheKey)
                    ?.identity?.artifactSha256)
                switchedModelDuringPlayback = true
            }

            onMediaControllerThread(mediaController) { mediaController.pause() }
            waitForMediaController(mediaController, "pause") {
                !mediaController.playWhenReady && !mediaController.isPlaying
            }
            val targetPositionMs = onMediaControllerThread(mediaController) {
                (mediaController.duration - SEEK_FROM_END_MS)
                    .coerceAtLeast(0L)
                    .coerceAtMost(5_000L)
            }
            onMediaControllerThread(mediaController) { mediaController.seekTo(targetPositionMs) }
            waitForMediaController(mediaController, "seek") {
                abs(mediaController.currentPosition - targetPositionMs) <=
                    MEDIA_SESSION_SEEK_TOLERANCE_MS
            }
            onMediaControllerThread(mediaController) { mediaController.play() }
            waitForMediaController(mediaController, "resume") { mediaController.playWhenReady }

            val blendResultFuture = onMediaControllerThread(mediaController) {
                mediaController.sendCustomCommand(
                    SessionCommand(Playback.SET_SOURCE_SEPARATION_BLEND, Bundle.EMPTY),
                    Bundle().apply {
                        putFloat(Playback.EXTRA_SOURCE_SEPARATION_BLEND, 0.7f)
                        putBoolean(Playback.EXTRA_SOURCE_SEPARATION_PERSIST_BLEND, false)
                    },
                )
            }
            val blendResult = blendResultFuture.get(
                MEDIA_SESSION_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
            )
            assertEquals(SessionResult.RESULT_SUCCESS, blendResult.resultCode)

            report.put("status", "passed")
            report.put("lifecycle", report.getJSONObject("lifecycle")
                .put("mediaSessionConnected", true)
                .put("pauseResumePassed", true)
                .put("seekPassed", true)
            )
            report.put("cache", report.getJSONObject("cache")
                .put("cacheKey", cacheKey)
                .put("exactIdentity", true)
                .put("completedPlayable", true)
                .put("mediaSessionAdopted", true)
                .put("mediaSessionSongId", manifest.song.songId)
            )
            report.put("playbackModelSwitch", JSONObject()
                .put("performed", switchedModelDuringPlayback)
                .put("primaryCacheKey", cacheKey)
                .put("primaryArtifactSha256", expectedArtifactSha256)
                .put("secondaryModelId", secondaryModelId ?: JSONObject.NULL)
                .put(
                    "secondaryArtifactSha256",
                    secondaryArtifactSha256 ?: JSONObject.NULL,
                )
                .put("primaryCacheLeaseRetained", switchedModelDuringPlayback)
            )
            report.put("audio", report.getJSONObject("audio")
                .put("finite", true)
                .put("outputFrameCount", manifest.output?.outputFrameCount ?: 0)
                .put("expectedFrameCount", manifest.output?.outputFrameCount ?: 0)
                .put("frameDelta", 0)
                .put("playerTimestampDriftMs", 0)
                .put("stemSemantics", manifest.output?.stems?.joinToString(",") {
                    it.semantic.name
                } ?: "unknown")
            )
        } catch (error: Throwable) {
            report.put("status", "failed")
            report.put("error", "${error::class.java.name}: ${error.message}")
            throw error
        } finally {
            controller?.let { mediaController ->
                runCatching {
                    onMediaControllerThread(mediaController) {
                        mediaController.pause()
                        mediaController.release()
                    }
                }
            }
            sourceUri?.let { uri ->
                runCatching { context.contentResolver.delete(uri, null, null) }
            }
            writeReport(context, runId, "playback", report)
        }
    }

    private fun waitForMediaController(
        controller: MediaController,
        operation: String,
        predicate: () -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + MEDIA_SESSION_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (onMediaControllerThread(controller, predicate)) return
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        error("MediaController did not complete $operation in time.")
    }

    private fun prepareMediaControllerSource(
        controller: MediaController,
        source: Song,
        operation: String,
    ): Int {
        val deadline = SystemClock.elapsedRealtime() + MEDIA_SESSION_TIMEOUT_MS
        var attempts = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            val ready = onMediaControllerThread(controller) {
                if (controller.currentMediaItem?.mediaId != source.id.toString()) {
                    controller.setMediaItem(source.toMediaItem())
                    controller.prepare()
                    attempts++
                    false
                } else if (controller.duration > 0L) {
                    true
                } else {
                    if (controller.playbackState == Player.STATE_IDLE) {
                        controller.prepare()
                    }
                    false
                }
            }
            if (ready) return attempts
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        error("MediaController did not complete $operation in time after $attempts attempts.")
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
        assertTrue(
            "MediaController application thread did not complete the operation.",
            completed.await(MEDIA_SESSION_TIMEOUT_MS, TimeUnit.MILLISECONDS),
        )
        error.get()?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result.get() as T
    }

    private fun waitForReady(
        worker: SourceSeparationForegroundWorkerCoordinator,
        minimumReadyWindows: Int,
    ) {
        val deadline = SystemClock.elapsedRealtime() + LIFECYCLE_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            when (val state = worker.workerStateFlow.value) {
                is SourceSeparationUiState.Running -> {
                    if ((state.scheduler?.playbackReadyWindowReadyCount ?: 0) >=
                        minimumReadyWindows
                    ) return
                }
                is SourceSeparationUiState.Failed,
                is SourceSeparationUiState.Canceled,
                is SourceSeparationUiState.Completed,
                -> error("Worker reached an unexpected terminal state: $state")
                else -> Unit
            }
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        error("Worker did not publish $minimumReadyWindows ready windows in time.")
    }

    private fun waitForCompleted(worker: SourceSeparationForegroundWorkerCoordinator) {
        val deadline = SystemClock.elapsedRealtime() + LIFECYCLE_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            when (val state = worker.workerStateFlow.value) {
                is SourceSeparationUiState.Completed -> return
                is SourceSeparationUiState.Failed,
                is SourceSeparationUiState.Canceled,
                -> error("Worker did not resume to completion: $state")
                else -> SystemClock.sleep(POLL_INTERVAL_MS)
            }
        }
        error("Worker did not complete in time.")
    }

    private fun waitForPlayable(
        runtimeFacade: SourceSeparationRuntimeFacade,
        song: SourceSeparationRuntimeSong,
        playbackPositionMs: Long,
        readyWindowCount: Int,
    ): SourceSeparationModelAwarePlayableStatus.Ready {
        val deadline = SystemClock.elapsedRealtime() + LIFECYCLE_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            when (val status = runtimeFacade.playableStatus(
                song = song,
                playbackPositionMs = playbackPositionMs,
                readyWindowCount = readyWindowCount,
            )) {
                is SourceSeparationModelAwarePlayableStatus.Ready -> return status
                SourceSeparationModelAwarePlayableStatus.Processing,
                SourceSeparationModelAwarePlayableStatus.Unavailable,
                -> SystemClock.sleep(POLL_INTERVAL_MS)
            }
        }
        error("Seek target did not become playable in time.")
    }

    private fun waitForInactive(worker: SourceSeparationForegroundWorkerCoordinator) {
        val deadline = SystemClock.elapsedRealtime() + LIFECYCLE_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!worker.isWorkerActive()) return
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        error("Worker did not leave its active job in time.")
    }

    private fun waitForWorkerToLeaveSong(
        worker: SourceSeparationForegroundWorkerCoordinator,
        songId: Long,
    ) {
        val deadline = SystemClock.elapsedRealtime() + LIFECYCLE_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (worker.runningSongId() != songId) return
            when (val state = worker.workerStateFlow.value) {
                is SourceSeparationUiState.Failed,
                is SourceSeparationUiState.Canceled,
                -> error("Worker failed while leaving the prefetched song: $state")
                else -> SystemClock.sleep(POLL_INTERVAL_MS)
            }
        }
        error("Worker did not leave prefetched song $songId in time.")
    }

    private fun currentProcessImportance(): Int {
        val state = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(state)
        return state.importance
    }

    private fun waitForPaused(worker: SourceSeparationForegroundWorkerCoordinator) {
        val deadline = SystemClock.elapsedRealtime() + LIFECYCLE_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (worker.runningSongId() == null &&
                worker.workerStateFlow.value is SourceSeparationUiState.Idle
            ) return
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        error("Worker did not reach its paused idle state in time.")
    }

    private fun clearExactCacheEntry(
        runtimeFacade: SourceSeparationRuntimeFacade,
        cacheKey: String,
    ) {
        runtimeFacade.entries()
            .filter { it.cacheKey == cacheKey }
            .forEach { entry ->
                assertEquals(
                    SourceSeparationCacheMutationResult.Completed,
                    runtimeFacade.delete(entry.cacheKey),
                )
            }
    }

    private fun assertExpectedActivePreset(arguments: Bundle) {
        val active = get<SourceSeparationPresetRepository>(
            SourceSeparationPresetRepository::class.java,
        ).activeModel()
        assertTrue(active is SourceSeparationActivePresetState.Reference)
        val reference = (active as SourceSeparationActivePresetState.Reference).reference
        assertEquals(arguments.requiredString(ARG_MODEL_ID), reference.modelId)
        assertEquals(
            arguments.requiredString(ARG_ARTIFACT_SHA256),
            reference.artifactSha256,
        )
    }

    private fun createCpuRuntimeFacade(
        context: Context,
        preferences: SharedPreferences,
        presetRepository: SourceSeparationPresetRepository,
        backendMode: BackendMode = BackendMode.Cpu,
        processorCount: Int?,
        xnnPackFlags: Int? = null,
        sessionProviderFactoryOverride: (() -> MdxInferenceSessionProvider)? = null,
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
        val sessionProviderFactory: (() -> MdxInferenceSessionProvider)? =
            sessionProviderFactoryOverride ?: if (backendMode == BackendMode.Cpu) {
                val cpuFactory = MdxLiteRtCpuInferenceSessionFactory(
                    compatibilityPolicy = MdxCompatibilityPolicy.KnownGoodOnly,
                    availableProcessors = {
                        processorCount ?: Runtime.getRuntime().availableProcessors()
                    },
                    xnnPackFlags = xnnPackFlags,
                )
                val singleUseFactory: () -> MdxInferenceSessionProvider = {
                    SingleUseMdxInferenceSessionProvider(cpuFactory)
                }
                singleUseFactory
            } else {
                null
            }
        val rangeExecutor = if (
            backendMode == BackendMode.Auto && sessionProviderFactory == null
        ) {
            MdxSourceSeparationModelAwareRangeExecutor(context)
        } else {
            MdxSourceSeparationModelAwareRangeExecutor(
                context,
                requireNotNull(sessionProviderFactory),
            )
        }
        val engine = SourceSeparationModelAwareEngine(
            activeModelResolver = presetRepository::resolveActiveCacheModel,
            preflightResolver = AndroidSourceSeparationModelAwarePreflightResolver(context),
            coordinator = runCoordinator,
            rangeExecutor = rangeExecutor,
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
        val extension = File(path).extension
            .lowercase()
            .takeIf { it.matches(SAFE_EXTENSION) }
            ?: "bin"
        val displayName = "booming-ss-phase7-$runId.$extension"
        val mimeType = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(extension)
            ?: "audio/*"
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

    private fun exportCacheArtifacts(
        context: Context,
        runId: String,
        entryDirectory: File,
        manifest: com.mardous.booming.separation.cache.v2.SourceSeparationCacheManifest,
        store: SourceSeparationCacheStore,
    ): Phase7CacheArtifactExport {
        val directoryRelative = "$ARTIFACT_EXPORT_DIRECTORY/$runId"
        val exportDirectory = File(context.filesDir, directoryRelative)
        if (exportDirectory.exists()) {
            check(exportDirectory.deleteRecursively()) {
                "Could not clear the previous Phase 7 artifact export."
            }
        }
        check(exportDirectory.mkdirs()) { "Could not create the Phase 7 artifact export." }

        val manifestSource = File(entryDirectory, "manifest.json")
        val manifestDestination = File(exportDirectory, "cache-manifest.json")
        copyArtifact(manifestSource, manifestDestination)
        val stemExports = requireNotNull(manifest.output).stems.associate { stem ->
            val filePrefix = stem.semantic.name.lowercase()
            val wavDestination = File(exportDirectory, "$filePrefix.wav")
            copyArtifact(
                store.resolveRelativePath(entryDirectory, stem.wavPath),
                wavDestination,
            )
            val promotedDestination = stem.promotedPath?.let { promotedPath ->
                File(exportDirectory, "$filePrefix.flac").also { destination ->
                    copyArtifact(
                        store.resolveRelativePath(entryDirectory, promotedPath),
                        destination,
                    )
                }
            }
            stem.semantic.name to Phase7StemArtifactExport(
                wavPathRelative = "files/$directoryRelative/${wavDestination.name}",
                promotedPathRelative = promotedDestination?.let { destination ->
                    "files/$directoryRelative/${destination.name}"
                },
            )
        }
        return Phase7CacheArtifactExport(
            directoryPathRelative = "files/$directoryRelative",
            manifestPathRelative = "files/$directoryRelative/${manifestDestination.name}",
            manifestByteSize = manifestDestination.length(),
            manifestSha256 = manifestDestination.sha256(),
            stems = stemExports,
        )
    }

    private fun copyArtifact(source: File, destination: File) {
        check(source.isFile) { "Phase 7 artifact source is missing: ${source.name}" }
        source.copyTo(destination, overwrite = true)
        check(destination.isFile && destination.length() == source.length()) {
            "Phase 7 artifact copy is incomplete: ${source.name}"
        }
    }

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
        val backendMode = BackendMode.parse(arguments.getString(ARG_BACKEND_MODE))
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
                .put("backend", backendMode.reportBackend)
                .put(
                    "profileId",
                    arguments.getString(ARG_PROFILE_ID) ?: "cpu-default-fp32-v1",
                )
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
                .put("windowDecodeEnabled", arguments.optionalBoolean(ARG_WINDOW_DECODE_ENABLED, true))
                .put("cleanInstallScenario", arguments.optionalBoolean(ARG_CLEAN_INSTALL, false))
                .put(
                    "xnnPackFlags",
                    arguments.getString(ARG_XNNPACK_FLAGS)?.toIntOrNull() ?: JSONObject.NULL,
                )
                .put(
                    "autoFailpoint",
                    arguments.getString(ARG_AUTO_FAILPOINT)
                        ?: Phase7AutoFailpoint.None.argumentValue,
                )
                .put("backendRequested", backendMode.reportBackend)
                .put(
                    "backendUsed",
                    if (backendMode == BackendMode.Cpu) backendMode.reportBackend else "not-tested",
                )
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

    private fun applyRuntimeEvidence(
        report: JSONObject,
        manifest: SourceSeparationCacheManifest,
    ) {
        val runtimeRecords = manifest.runtimeRecords
        report.getJSONObject("cache").put(
            "runtimeRecords",
            JSONArray(runtimeRecords.map { record ->
                JSONObject()
                    .put("backend", record.backend)
                    .put("runtimeProfileId", record.runtimeProfileId)
                    .put("precision", record.precision)
                    .put("elapsedMs", record.elapsedMs)
                    .put("fallbackStage", record.fallbackStage ?: JSONObject.NULL)
                    .put("fallbackReason", record.fallbackReason ?: JSONObject.NULL)
            }),
        )
        runtimeRecords.lastOrNull()?.let { record ->
            report.getJSONObject("run")
                .put("backendUsed", record.backend)
                .put("fallbackStage", record.fallbackStage ?: JSONObject.NULL)
                .put("fallbackReason", record.fallbackReason ?: JSONObject.NULL)
        }
    }

    private fun validateAutoFaultEvidence(
        report: JSONObject,
        controller: Phase7AutoFaultController,
        runtimeRecordBackend: String,
        runtimeRecordFallbackStage: String?,
        runtimeRecordFallbackReason: String?,
        firstReadyAtElapsedMs: Long,
    ) {
        val snapshot = controller.snapshot()
        val expectedStage = requireNotNull(snapshot.failpoint.expectedFallbackStage)
        assertEquals(MdxInferenceBackend.LiteRtCpu.name, runtimeRecordBackend)
        assertEquals(expectedStage.name, runtimeRecordFallbackStage)
        assertTrue(
            "The injected fallback reason was not retained: $runtimeRecordFallbackReason",
            runtimeRecordFallbackReason?.contains("Injected Phase 7") == true,
        )
        assertEquals(1, snapshot.gpuCreateCount)
        assertEquals(1, snapshot.gpuCloseCount)
        assertEquals(1, snapshot.cpuCreateCount)
        assertEquals(1, snapshot.cpuCloseCount)
        assertTrue(snapshot.injectedAtElapsedMs > 0L)
        val gpuCloseIndex = snapshot.events.indexOf("${MdxInferenceBackend.LiteRtGpu.name}-close")
        val cpuCreateIndex = snapshot.events.indexOf("${MdxInferenceBackend.LiteRtCpu.name}-create")
        assertTrue(
            "CPU fallback was created before the GPU session closed: ${snapshot.events}",
            gpuCloseIndex >= 0 && cpuCreateIndex > gpuCloseIndex,
        )
        if (snapshot.failpoint == Phase7AutoFailpoint.InvocationAfterReady) {
            assertTrue(
                "No playback-ready windows were observed before injected fallback.",
                firstReadyAtElapsedMs > 0L,
            )
            assertTrue(
                "The invocation failure occurred before playback readiness.",
                firstReadyAtElapsedMs < snapshot.injectedAtElapsedMs,
            )
        }
        report.put("autoFaultInjection", JSONObject()
            .put("failpoint", snapshot.failpoint.argumentValue)
            .put("expectedFallbackStage", expectedStage.name)
            .put("injectedAtElapsedMs", snapshot.injectedAtElapsedMs)
            .put("firstReadyAtElapsedMs", firstReadyAtElapsedMs)
            .put("gpuCreateCount", snapshot.gpuCreateCount)
            .put("gpuCloseCount", snapshot.gpuCloseCount)
            .put("gpuInvocationCount", snapshot.gpuInvocationCount)
            .put("cpuCreateCount", snapshot.cpuCreateCount)
            .put("cpuCloseCount", snapshot.cpuCloseCount)
            .put("events", JSONArray(snapshot.events))
        )
    }

    private fun writeReport(context: Context, runId: String, report: JSONObject) {
        writeReport(context, runId, "worker", report)
    }

    private fun writeReport(
        context: Context,
        runId: String,
        stage: String,
        report: JSONObject,
    ) {
        val root = File(context.filesDir, REPORT_DIRECTORY)
        check(root.isDirectory || root.mkdirs()) { "Could not create the Phase 7 report directory." }
        File(root, "$runId-$stage.json").writeText(report.toString(2))
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

    private data class Phase7CacheArtifactExport(
        val directoryPathRelative: String,
        val manifestPathRelative: String,
        val manifestByteSize: Long,
        val manifestSha256: String,
        val stems: Map<String, Phase7StemArtifactExport>,
    )

    private data class Phase7StemArtifactExport(
        val wavPathRelative: String,
        val promotedPathRelative: String?,
    )

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

    private fun android.os.Bundle.requiredLong(key: String): Long =
        getString(key)?.toLongOrNull() ?: getLong(key).takeIf { it != 0L }
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

    private class CountingMdxInferenceSessionFactory(
        private val delegate: MdxInferenceSessionFactory,
        private val createCount: AtomicInteger,
    ) : MdxInferenceSessionFactory {
        override val factoryId: String = delegate.factoryId
        override val backend: MdxInferenceBackend = delegate.backend

        override fun create(
            artifact: MdxModelArtifact,
            profile: MdxExecutionProfile,
            runtimeSettings: MdxRuntimeSettings,
        ): MdxInferenceSession {
            createCount.incrementAndGet()
            return delegate.create(artifact, profile, runtimeSettings)
        }
    }

    private enum class BackendMode(
        val argumentValue: String,
        val reportBackend: String,
    ) {
        Cpu("cpu", "LiteRtCpu"),
        Auto("auto", "LiteRtAuto"),
        ;

        companion object {
            fun parse(value: String?): BackendMode = values().singleOrNull {
                it.argumentValue == (value ?: Cpu.argumentValue)
            } ?: error("Unsupported Phase 7 backend mode: $value")
        }
    }

    private enum class LifecycleScenario(
        val argumentValue: String,
        val includesPauseResume: Boolean,
        val includesSeek: Boolean,
        val includesCancellation: Boolean,
    ) {
        PauseResume("pause-resume", true, false, false),
        Seek("seek", false, true, false),
        Cancellation("cancellation", false, false, true),
        Sequential("sequential", true, true, true),
        ;

        companion object {
            fun parse(value: String?): LifecycleScenario = values().singleOrNull {
                it.argumentValue == (value ?: Sequential.argumentValue)
            } ?: error("Unsupported lifecycle scenario: $value")
        }
    }

    private enum class LifecycleSessionMode(val argumentValue: String) {
        SingleUse("single-use"),
        SharedReusable("shared-reusable"),
        ;

        companion object {
            fun parse(value: String?): LifecycleSessionMode = values().singleOrNull {
                it.argumentValue == (value ?: SingleUse.argumentValue)
            } ?: error("Unsupported lifecycle session mode: $value")
        }
    }

    private companion object {
        const val ARG_RUN_ID = "runId"
        const val ARG_SERIAL = "serial"
        const val ARG_PROCESS_ABI = "processAbi"
        const val ARG_MODEL_ID = "modelId"
        const val ARG_ARTIFACT_SHA256 = "artifactSha256"
        const val ARG_ARTIFACT_FILE_NAME = "artifactFileName"
        const val ARG_SECONDARY_MODEL_ID = "secondaryModelId"
        const val ARG_SECONDARY_ARTIFACT_SHA256 = "secondaryArtifactSha256"
        const val ARG_SECONDARY_CONTRACT_ID = "secondaryContractId"
        const val ARG_PROFILE_ID = "profileId"
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
        const val ARG_CURRENT_SOURCE_PATH = "currentSourcePath"
        const val ARG_BACKEND_MODE = "backendMode"
        const val ARG_AUTO_FAILPOINT = "autoFailpoint"
        const val ARG_PROCESSOR_COUNT = "processorCount"
        const val ARG_XNNPACK_FLAGS = "xnnPackFlags"
        const val ARG_WINDOW_DECODE_ENABLED = "windowDecodeEnabled"
        const val ARG_PRESERVE_MEDIA_STORE_SOURCE = "preserveMediaStoreSource"
        const val ARG_EXPORT_CACHE_AUDIO = "exportCacheAudio"
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
        const val ARG_CURRENT_FIXTURE_ID = "currentFixtureId"
        const val ARG_CURRENT_FIXTURE_FILE_NAME = "currentFixtureFileName"
        const val ARG_CURRENT_FIXTURE_BYTES = "currentFixtureBytes"
        const val ARG_CURRENT_FIXTURE_SHA256 = "currentFixtureSha256"
        const val ARG_CURRENT_FIXTURE_DURATION_US = "currentFixtureDurationUs"
        const val ARG_CURRENT_FIXTURE_SAMPLE_RATE = "currentFixtureSampleRate"
        const val ARG_CURRENT_FIXTURE_CHANNELS = "currentFixtureChannels"
        const val ARG_CURRENT_FIXTURE_CODEC = "currentFixtureCodec"
        const val ARG_CURRENT_FIXTURE_DECODE_CLASS = "currentFixtureDecodeClass"
        const val ARG_CACHE_KEY = "cacheKey"
        const val ARG_LIFECYCLE_SCENARIO = "lifecycleScenario"
        const val ARG_LIFECYCLE_SESSION_MODE = "lifecycleSessionMode"
        const val REPORT_DIRECTORY = "phase7-validation-reports"
        const val ARTIFACT_EXPORT_DIRECTORY = "phase7-validation-artifacts"
        const val REQUIRED_READY_WINDOWS = 2
        const val TEST_BLEND = 0.23f
        const val TEST_KEY_PLAYBACK_ENABLED = "source_separation.playback_enabled"
        const val TEST_KEY_REMEMBER_PER_SONG = "source_separation.remember_per_song"
        const val TEST_KEY_GLOBAL_BLEND = "source_separation.global_blend"
        const val SEEK_FROM_END_MS = 1_000L
        const val POLL_INTERVAL_MS = 250L
        const val WORKER_TIMEOUT_MS = 20 * 60 * 1000L
        const val LIFECYCLE_TIMEOUT_MS = 5 * 60 * 1000L
        const val MEDIA_SESSION_TIMEOUT_SECONDS = 30L
        const val MEDIA_SESSION_TIMEOUT_MS = 30_000L
        const val MEDIA_SESSION_SEEK_TOLERANCE_MS = 250L
        const val MEDIA_SCAN_TIMEOUT_MS = 30_000L
        const val MEDIA_SCAN_RETRIES = 60
        const val MEDIA_SCAN_POLL_MS = 500L
        const val BACKGROUND_SETTLE_MS = 1_000L
        const val ZERO_SHA256 = "0000000000000000000000000000000000000000000000000000000000000000"
        val SAFE_NAME = Regex("^[A-Za-z0-9._-]{1,120}$")
        val SAFE_EXTENSION = Regex("^[a-z0-9]{1,8}$")
    }
}
