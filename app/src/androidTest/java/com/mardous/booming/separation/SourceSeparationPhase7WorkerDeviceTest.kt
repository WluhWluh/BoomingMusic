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
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Base64
import android.webkit.MimeTypeMap
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mardous.booming.data.model.Song
import com.mardous.booming.playback.Playback
import com.mardous.booming.playback.PlaybackService
import com.mardous.booming.separation.cache.SourceSeparationSegmentState
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
import com.mardous.booming.separation.model.MdxX86ProcessValidationOverride
import com.mardous.booming.separation.model.ReusableMdxInferenceSessionProvider
import com.mardous.booming.separation.model.SingleUseMdxInferenceSessionProvider
import com.mardous.booming.separation.model.AndroidMdxRuntimePlatformProvider
import com.mardous.booming.separation.model.litert.MdxLiteRtCpuInferenceSessionFactory
import com.mardous.booming.separation.model.preset.SourceSeparationActivePresetState
import com.mardous.booming.separation.model.preset.SourceSeparationPresetDownloader
import com.mardous.booming.separation.model.preset.SourceSeparationPresetRepository
import com.mardous.booming.separation.model.preset.SourceSeparationPresetSelectionScope
import com.mardous.booming.separation.process.SourceSeparationExecutionHostEvent
import com.mardous.booming.separation.process.SourceSeparationExecutionHostEventPayload
import com.mardous.booming.separation.process.SourceSeparationProcessDiagnostics
import com.mardous.booming.separation.process.SourceSeparationProcessLifecyclePolicy
import com.mardous.booming.separation.process.SourceSeparationProcessSessionState
import com.mardous.booming.separation.process.ipc.BoundRemoteSourceSeparationExecutionHost
import com.mardous.booming.separation.process.ipc.SourceSeparationIpcRecycleReason
import com.mardous.booming.separation.process.ipc.SourceSeparationRemoteConnectionState
import com.mardous.booming.separation.process.ipc.SourceSeparationRemoteRecycleTimeoutException
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
import org.koin.core.context.GlobalContext
import org.koin.java.KoinJavaComponent.get
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
        val executionHostMode = Phase7ExecutionHostMode.parse(
            arguments.getString(ARG_EXECUTION_HOST_MODE),
        )
        require(executionHostMode == Phase7ExecutionHostMode.InProcess ||
            backendMode == BackendMode.Auto
        ) {
            "Bound-remote validation requires the production Auto backend."
        }
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
        val rebindAfterCompletion = arguments.optionalBoolean(
            ARG_REBIND_AFTER_COMPLETION,
            false,
        )
        require(!rebindAfterCompletion ||
            executionHostMode == Phase7ExecutionHostMode.BoundRemote
        ) {
            "Process-retention validation requires the bound-remote host."
        }
        var coordinator: SourceSeparationForegroundWorkerCoordinator? = null
        var boundRemoteHost: BoundRemoteSourceSeparationExecutionHost? = null
        var mediaUri: Uri? = null
        val hostEvents = Collections.synchronizedList(
            mutableListOf<SourceSeparationExecutionHostEvent>(),
        )

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
                executionHostMode = executionHostMode,
                executionHostEventSink = hostEvents::add,
                boundRemoteHostSink = { boundRemoteHost = it },
            )
            val remoteStartup = boundRemoteHost?.let { host ->
                host.processGeneration
                host.connectionDiagnostics
            }
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
            val workerStartedCpuMs = Process.getElapsedCpuTime()
            val thermalSampler = Phase7ThermalSampler(context, workerStartedAt)
            thermalSampler.sample(workerStartedAt, force = true)
            assertTrue(worker.startCurrentSong())

            val firstReadyAt = AtomicLong(0L)
            var peakPssBytes = idleMemory.getLong("totalPssBytes")
            var peakJavaBytes = idleMemory.getLong("javaPssBytes")
            var peakNativeBytes = idleMemory.getLong("nativePssBytes")
            var peakGraphicsBytes = idleMemory.getLong("graphicsPssBytes")
            var peakRemotePssBytes = remoteStartup?.idlePssBytes ?: 0L
            var peakSummedPssBytes = idleMemory.getLong("totalPssBytes") +
                peakRemotePssBytes
            var decodeMode: String? = null
            var decodeDiagnostics: String? = null
            var finalState: SourceSeparationUiState = SourceSeparationUiState.Idle
            while (SystemClock.elapsedRealtime() - workerStartedAt < WORKER_TIMEOUT_MS) {
                finalState = worker.workerStateFlow.value
                val now = SystemClock.elapsedRealtime()
                thermalSampler.sample(now)
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
                val remotePssBytes = boundRemoteHost?.connectionDiagnostics?.pid
                    ?.let { processPssBytes(context, it) }
                    ?: 0L
                peakRemotePssBytes = maxOf(peakRemotePssBytes, remotePssBytes)
                peakSummedPssBytes = maxOf(
                    peakSummedPssBytes,
                    memory.getLong("totalPssBytes") + remotePssBytes,
                )
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
            thermalSampler.sample(completedAt, force = true)
            val processCpuMs = (Process.getElapsedCpuTime() - workerStartedCpuMs)
                .coerceAtLeast(0L)
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
            report.getJSONObject("audio")
                .put("outputFrameCount", output.outputFrameCount)
                .put("sourceAudioFingerprint", manifest.identity.source.audioFingerprint)
                .put(
                    "decodeDiagnostics",
                    sourceDecodeJson(runtimeRecord, decodeDiagnostics ?: ""),
                )
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
            assertExpectedSourceDecode(arguments, runtimeRecord)
            playback.close()

            if (rebindAfterCompletion) {
                val originalHost = requireNotNull(boundRemoteHost)
                val beforeRebind = originalHost.processDiagnostics()
                assertEquals(
                    SourceSeparationProcessSessionState.Resident,
                    beforeRebind.session.state,
                )
                assertEquals(1, beforeRebind.session.nativeSessionCreationCount)
                assertEquals(0, beforeRebind.session.activeLeaseCount)
                assertTrue(beforeRebind.session.invocationCount > 0L)
                val expectedSessionId = requireNotNull(beforeRebind.session.sessionId)

                originalHost.close()
                SystemClock.sleep(PROCESS_REBIND_SETTLE_MS)
                val reboundHost = BoundRemoteSourceSeparationExecutionHost(
                    context.applicationContext,
                )
                boundRemoteHost = reboundHost
                val afterRebind = reboundHost.processDiagnostics()
                assertEquals(beforeRebind.processGeneration, afterRebind.processGeneration)
                assertEquals(beforeRebind.pid, afterRebind.pid)
                assertEquals(beforeRebind.processStartTicks, afterRebind.processStartTicks)
                assertEquals(expectedSessionId, afterRebind.session.sessionId)
                assertEquals(1, afterRebind.session.nativeSessionCreationCount)
                assertEquals(0, afterRebind.session.activeLeaseCount)
                assertEquals(
                    beforeRebind.session.invocationCount,
                    afterRebind.session.invocationCount,
                )
                assertEquals(
                    SourceSeparationProcessSessionState.Resident,
                    afterRebind.session.state,
                )
                report.put("processRetention", JSONObject()
                    .put("rebound", true)
                    .put("processGeneration", afterRebind.processGeneration)
                    .put("pid", afterRebind.pid)
                    .put("processStartTicks", afterRebind.processStartTicks)
                    .put("sessionId", afterRebind.session.sessionId)
                    .put(
                        "nativeSessionCreationCount",
                        afterRebind.session.nativeSessionCreationCount,
                    )
                    .put("invocationCount", afterRebind.session.invocationCount)
                )
            }

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
            val expectedFrames = arguments.getString(ARG_FIXTURE_EXPECTED_OUTPUT_FRAMES)
                ?.toIntOrNull()
                ?: ((arguments.optionalLong(ARG_FIXTURE_DURATION_US) *
                    arguments.optionalInt(ARG_FIXTURE_SAMPLE_RATE, 1) + 500_000L) /
                    1_000_000L).toInt()
            val frameDelta = kotlin.math.abs(output.outputFrameCount - expectedFrames)
            val segmentPlan = requireNotNull(promotedManifest.segmentPlan)
            val joinFrames = segmentPlan.segments.drop(1).map { it.playbackStartFrame }
            val joinPlacementValid = segmentPlan.rangeStartFrame == 0 &&
                segmentPlan.rangeEndFrame == output.outputFrameCount &&
                segmentPlan.segments.zipWithNext().all { (current, next) ->
                    current.playbackEndFrame == next.playbackStartFrame
                }
            report.put("status", "passed")
            report.put("timing", report.getJSONObject("timing")
                .put("firstReadyMs", firstReadyMs)
                .put("fullSongMs", fullSongMs)
                .put("processCpuMs", processCpuMs)
            )
            report.put("memory", JSONObject()
                .put("idlePssBytes", idleMemory.getLong("totalPssBytes"))
                .put("peakPssBytes", peakPssBytes)
                .put("peakPssDeltaBytes", (peakPssBytes - idleMemory
                    .getLong("totalPssBytes")).coerceAtLeast(0L))
                .put("peakJavaBytes", peakJavaBytes)
                .put("peakNativeBytes", peakNativeBytes)
                .put("peakGraphicsBytes", peakGraphicsBytes)
                .put("idleRemotePssBytes", remoteStartup?.idlePssBytes ?: JSONObject.NULL)
                .put("peakRemotePssBytes", peakRemotePssBytes)
                .put("peakSummedPssBytes", peakSummedPssBytes)
            )
            report.put("thermal", thermalSampler.toJson())
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
                .put("sourceAudioFingerprint", manifest.identity.source.audioFingerprint)
                .put("joinFrames", JSONArray(joinFrames))
                .put("joinPlacementValid", joinPlacementValid)
                .put("decodeDiagnostics", sourceDecodeJson(runtimeRecord,
                    decodeDiagnostics ?: "", decodeMode))
            )
            arguments.getString(ARG_FIXTURE_EXPECTED_OUTPUT_FRAMES)?.let {
                assertEquals(
                    "Unexpected format-corpus output frame count.",
                    expectedFrames,
                    output.outputFrameCount,
                )
            }
            arguments.getString(ARG_FIXTURE_EXPECTED_OUTPUT_SAMPLE_RATE)
                ?.toIntOrNull()
                ?.let { expectedSampleRate ->
                    assertEquals(expectedSampleRate, output.outputSampleRate)
                }
            assertTrue("Completed cache segment joins are not contiguous.", joinPlacementValid)
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
            report.put(
                "executionHost",
                executionHostReport(
                    mode = executionHostMode,
                    events = synchronized(hostEvents) { hostEvents.toList() },
                    remoteHost = boundRemoteHost,
                ),
            )
            report.put("worker", JSONObject()
                .put("sourcePath", sourcePath)
                .put("mediaUri", mediaUri.toString())
                .put("songId", source.id)
                .put("songDurationMs", source.duration)
                .put("windowDecodeEnabled", windowDecodeEnabled)
                .put("preservedMediaStoreSource", preserveMediaStoreSource)
                .put("cpuThreads", runCallbacks.cpuThreads)
                .put("backendMode", backendMode.argumentValue)
                .put("executionHostMode", executionHostMode.argumentValue)
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
            boundRemoteHost?.close()
            if (!preserveMediaStoreSource) {
                mediaUri?.let { uri ->
                    runCatching { context.contentResolver.delete(uri, null, null) }
                }
            }
            writeReport(context, runId, report)
        }
    }

    @Test
    fun validateProcessSessionMatrix() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val runId = arguments.requiredString(ARG_RUN_ID).requireSafeName()
        require(MdxX86ProcessValidationOverride.buildEnabled) {
            "The process-session matrix requires the explicit x86 validation build."
        }
        val report = baseReport(context, runId, arguments)
        val events = Collections.synchronizedList(
            mutableListOf<SourceSeparationExecutionHostEvent>(),
        )
        val coordinators = mutableListOf<SourceSeparationForegroundWorkerCoordinator>()
        val snapshots = JSONArray()
        val cases = JSONArray()
        val fullSongBookends = JSONArray()
        val snapshotsByCycle = mutableMapOf<Int, SourceSeparationProcessDiagnostics>()
        var host: BoundRemoteSourceSeparationExecutionHost? = null
        var mediaUri: Uri? = null
        var tailMediaUri: Uri? = null
        var originalPlayback: OriginalAudioPlaybackProbe? = null
        var retiredUnexpectedDeaths = 0

        try {
            val sourcePath = arguments.requiredString(ARG_SOURCE_PATH)
            mediaUri = registerSourceInMediaStore(context, sourcePath, runId)
            val source = resolveMediaStoreSong(context, mediaUri, sourcePath)
            val durationMs = maxOf(
                source.duration,
                arguments.optionalLong(ARG_FIXTURE_DURATION_US) / 1_000L,
            )
            val tailSourcePath = arguments.requiredString(ARG_CURRENT_SOURCE_PATH)
            tailMediaUri = registerSourceInMediaStore(
                context,
                tailSourcePath,
                "$runId-tail",
            )
            val tailSource = resolveMediaStoreSong(context, tailMediaUri, tailSourcePath)
            val tailDurationMs = maxOf(
                tailSource.duration,
                arguments.optionalLong(ARG_CURRENT_FIXTURE_DURATION_US) / 1_000L,
            )
            val preferences = get<SharedPreferences>(SharedPreferences::class.java)
            check(preferences.edit()
                .putBoolean(SOURCE_SEPARATION_WINDOW_DECODE, true)
                .putBoolean(SOURCE_SEPARATION_AUTO_FLAC_COMPRESSION, false)
                .putInt(SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT, 1)
                .commit()
            ) { "Could not persist the process-session matrix preferences." }
            val playback = startOriginalAudioPlayback(
                context = context,
                source = source,
                operation = "process-session matrix playback",
            ).also { originalPlayback = it }

            val presetRepository = get<SourceSeparationPresetRepository>(
                SourceSeparationPresetRepository::class.java,
            )
            assertExpectedActivePreset(arguments)
            val cacheRepository = get<SourceSeparationModelAwareCacheRepository>(
                SourceSeparationModelAwareCacheRepository::class.java,
            )
            val store = get<SourceSeparationCacheStore>(SourceSeparationCacheStore::class.java)

            fun createRuntime(): SourceSeparationRuntimeFacade = createCpuRuntimeFacade(
                context = context,
                preferences = preferences,
                presetRepository = presetRepository,
                backendMode = BackendMode.Auto,
                processorCount = null,
                executionHostMode = Phase7ExecutionHostMode.BoundRemote,
                executionHostEventSink = events::add,
                boundRemoteHostSink = { host = it },
            )

            var runtimeFacade = createRuntime()
            var runtimeSong = (runtimeFacade.resolve(source) as?
                SourceSeparationRuntimeSongResolution.Ready)?.song
                ?: error("The process-session matrix source could not be admitted.")
            var tailRuntimeSong = (runtimeFacade.resolve(tailSource) as?
                SourceSeparationRuntimeSongResolution.Ready)?.song
                ?: error("The pending-tail matrix source could not be admitted.")
            clearExactCacheEntry(runtimeFacade, runtimeSong.cacheKey)
            clearExactCacheEntry(runtimeFacade, tailRuntimeSong.cacheKey)
            val initialHost = requireNotNull(host)
            val initialDiagnostics = initialHost.processDiagnostics()
            val expectedGeneration = initialDiagnostics.processGeneration
            val expectedStartTicks = initialDiagnostics.processStartTicks
            var expectedSessionId: String? = null
            snapshots.put(processMatrixSnapshot("before-setup", 0, initialDiagnostics))

            fun assertProcessIdentity(diagnostics: SourceSeparationProcessDiagnostics) {
                assertEquals(expectedGeneration, diagnostics.processGeneration)
                assertEquals(expectedStartTicks, diagnostics.processStartTicks)
                assertEquals(0, diagnostics.session.activeLeaseCount)
                assertEquals(1, diagnostics.session.nativeSessionCreationCount)
                assertEquals(
                    SourceSeparationProcessSessionState.Resident,
                    diagnostics.session.state,
                )
                assertFalse(diagnostics.session.poisoned)
                val sessionId = requireNotNull(diagnostics.session.sessionId)
                expectedSessionId?.let { assertEquals(it, sessionId) }
                    ?: run { expectedSessionId = sessionId }
            }

            fun newWorker(
                runtime: SourceSeparationRuntimeFacade,
                workerSource: Song = source,
                workerDurationMs: Long = durationMs,
                positionMs: Long = 0L,
            ) =
                SourceSeparationForegroundWorkerCoordinator(
                    context = context,
                    preferences = preferences,
                    sourceSeparationRuntime = runtime,
                ).also { worker ->
                    coordinators += worker
                    worker.attachCallbacks(RecordingCallbacks())
                    worker.updateSong(
                        song = workerSource,
                        positionMs = positionMs,
                        durationMs = workerDurationMs,
                        isPlaying = false,
                        sourceSeparationBlend = TEST_BLEND,
                    )
                }

            fun completeFullSongBookend(label: String): SourceSeparationCacheManifest {
                clearExactCacheEntry(runtimeFacade, tailRuntimeSong.cacheKey)
                val worker = newWorker(
                    runtime = runtimeFacade,
                    workerSource = tailSource,
                    workerDurationMs = tailDurationMs,
                )
                assertTrue(worker.startCurrentSong())
                waitForCompleted(worker)
                worker.cancel()
                waitForInactive(worker)
                val leaseReleaseMs = waitForCacheLeaseRelease(
                    cacheRepository,
                    tailRuntimeSong.cacheKey,
                )
                val completed = runtimeFacade.cacheStatus(tailRuntimeSong) as?
                    SourceSeparationModelAwareCacheStatus.Completed
                    ?: error("The $label full-song bookend did not complete.")
                val diagnostics = requireNotNull(host).processDiagnostics()
                assertProcessIdentity(diagnostics)
                val output = requireNotNull(completed.manifest.output)
                fullSongBookends.put(JSONObject()
                    .put("label", label)
                    .put("outputFrameCount", output.outputFrameCount)
                    .put("windowCount", output.windowCount)
                    .put("leaseReleaseMs", leaseReleaseMs)
                    .put("invocationCount", diagnostics.session.invocationCount)
                    .put("sessionId", diagnostics.session.sessionId)
                    .put("stems", JSONArray(output.stems.map { stem ->
                        JSONObject()
                            .put("semantic", stem.semantic.name)
                            .put("sha256", requireNotNull(stem.wavIntegrity).sha256)
                    }))
                )
                return completed.manifest
            }

            val beforeMatrixFullSong = completeFullSongBookend("before-matrix")
            playback.assertContinuous("after-before-matrix-bookend")

            for (cycle in 1..PROCESS_MATRIX_CYCLE_COUNT) {
                val scenario = ProcessMatrixScenario.forCycle(cycle)
                val cycleRuntimeSong = if (
                    scenario == ProcessMatrixScenario.PendingTailResume
                ) {
                    tailRuntimeSong
                } else {
                    runtimeSong
                }
                clearExactCacheEntry(runtimeFacade, cycleRuntimeSong.cacheKey)
                assertFalse(cacheRepository.isLeased(cycleRuntimeSong.cacheKey))
                val activeHost = requireNotNull(host)
                val before = activeHost.processDiagnostics()
                val invocationsBefore = before.session.invocationCount
                var pausedDiagnostics: SourceSeparationProcessDiagnostics? = null
                var pendingTailConfirmed = false
                var rebound = false

                when (scenario) {
                    ProcessMatrixScenario.Completion -> {
                        val worker = newWorker(runtimeFacade)
                        assertTrue(worker.startCurrentSong())
                        if (cycle == 1) {
                            waitForReady(worker, minimumReadyWindows = 1)
                            snapshots.put(processMatrixSnapshot(
                                "after-first-invocation",
                                cycle,
                                activeHost.processDiagnostics(),
                            ))
                        }
                        waitForCompleted(worker)
                        worker.cancel()
                        waitForInactive(worker)
                    }
                    ProcessMatrixScenario.PauseResume -> {
                        val pauseWorker = newWorker(runtimeFacade)
                        assertTrue(pauseWorker.startCurrentSong())
                        waitForReady(pauseWorker, minimumReadyWindows = 1)
                        pauseWorker.pauseCurrentSong(source)
                        waitForPaused(pauseWorker)
                        pauseWorker.cancel()
                        waitForInactive(pauseWorker)
                        pausedDiagnostics = requireNotNull(host).processDiagnostics()
                        assertProcessIdentity(pausedDiagnostics)
                        if (cycle == PROCESS_MATRIX_FIRST_PAUSE_CYCLE) {
                            snapshots.put(processMatrixSnapshot(
                                "after-pause",
                                cycle,
                                pausedDiagnostics,
                            ))
                            val oldHost = requireNotNull(host)
                            retiredUnexpectedDeaths +=
                                oldHost.connectionDiagnostics.unexpectedBinderDeathCount
                            oldHost.close()
                            SystemClock.sleep(PROCESS_REBIND_SETTLE_MS)
                            runtimeFacade = createRuntime()
                            runtimeSong = (runtimeFacade.resolve(source) as?
                                SourceSeparationRuntimeSongResolution.Ready)?.song
                                ?: error("The paused process-session run could not rebind.")
                            tailRuntimeSong = (runtimeFacade.resolve(tailSource) as?
                                SourceSeparationRuntimeSongResolution.Ready)?.song
                                ?: error("The pending-tail source could not rebind.")
                            val reboundDiagnostics = requireNotNull(host).processDiagnostics()
                            assertProcessIdentity(reboundDiagnostics)
                            assertEquals(
                                pausedDiagnostics.session.invocationCount,
                                reboundDiagnostics.session.invocationCount,
                            )
                            rebound = true
                        }
                        val resumeWorker = newWorker(runtimeFacade)
                        assertTrue(resumeWorker.startCurrentSong())
                        waitForCompleted(resumeWorker)
                        resumeWorker.cancel()
                        waitForInactive(resumeWorker)
                    }
                    ProcessMatrixScenario.PendingTailResume -> {
                        val pauseWorker = newWorker(
                            runtime = runtimeFacade,
                            workerSource = tailSource,
                            workerDurationMs = tailDurationMs,
                        )
                        assertTrue(pauseWorker.startCurrentSong())
                        waitForReady(pauseWorker, minimumReadyWindows = 1)
                        pauseWorker.pauseCurrentSong(tailSource)
                        waitForPaused(pauseWorker)
                        pauseWorker.cancel()
                        waitForInactive(pauseWorker)
                        val targetPositionMs =
                            (tailDurationMs - SEEK_FROM_END_MS).coerceAtLeast(0L)
                        when (val status = runtimeFacade.playableStatus(
                            song = tailRuntimeSong,
                            playbackPositionMs = targetPositionMs,
                            readyWindowCount = 1,
                        )) {
                            SourceSeparationModelAwarePlayableStatus.Processing -> {
                                pendingTailConfirmed = true
                            }
                            is SourceSeparationModelAwarePlayableStatus.Ready -> {
                                status.playback.close()
                                error("The tail window was already ready before seek/resume.")
                            }
                            SourceSeparationModelAwarePlayableStatus.Unavailable ->
                                error("The pending tail became unavailable.")
                        }
                        val resumeWorker = newWorker(
                            runtime = runtimeFacade,
                            workerSource = tailSource,
                            workerDurationMs = tailDurationMs,
                            positionMs = targetPositionMs,
                        )
                        assertTrue(resumeWorker.startCurrentSong())
                        waitForPlayable(
                            runtimeFacade = runtimeFacade,
                            song = tailRuntimeSong,
                            playbackPositionMs = targetPositionMs,
                            readyWindowCount = 1,
                        ).playback.close()
                        resumeWorker.cancel()
                        waitForInactive(resumeWorker)
                    }
                    ProcessMatrixScenario.Cancellation -> {
                        val worker = newWorker(runtimeFacade)
                        assertTrue(worker.startCurrentSong())
                        waitForReady(worker, minimumReadyWindows = 1)
                        worker.cancel()
                        waitForInactive(worker)
                        assertTrue(
                            runtimeFacade.cacheStatus(runtimeSong) !is
                                SourceSeparationModelAwareCacheStatus.Completed,
                        )
                    }
                }

                val leaseReleaseMs = waitForCacheLeaseRelease(
                    cacheRepository,
                    cycleRuntimeSong.cacheKey,
                )
                val after = requireNotNull(host).processDiagnostics()
                assertProcessIdentity(after)
                assertTrue(
                    "Cycle $cycle did not invoke LiteRT.",
                    after.session.invocationCount > invocationsBefore,
                )
                val manifest = store.readManifest(cycleRuntimeSong.cacheKey)
                if (scenario == ProcessMatrixScenario.PendingTailResume ||
                    scenario == ProcessMatrixScenario.Cancellation
                ) {
                    assertEquals(
                        SourceSeparationCacheManifestState.Canceled,
                        manifest?.state,
                    )
                }
                assertTrue(
                    "Cycle $cycle left a failed cache window.",
                    manifest?.segmentPlan?.segments.orEmpty().none { segment ->
                        segment.state == SourceSeparationSegmentState.Failed
                    },
                )
                if (scenario == ProcessMatrixScenario.Completion ||
                    scenario == ProcessMatrixScenario.PauseResume
                ) {
                    assertTrue(
                        runtimeFacade.cacheStatus(cycleRuntimeSong) is
                            SourceSeparationModelAwareCacheStatus.Completed,
                    )
                } else {
                    assertTrue(
                        runtimeFacade.cacheStatus(cycleRuntimeSong) !is
                            SourceSeparationModelAwareCacheStatus.Completed,
                    )
                }
                cases.put(JSONObject()
                    .put("cycle", cycle)
                    .put("scenario", scenario.argumentValue)
                    .put("invocationsBefore", invocationsBefore)
                    .put("invocationsAfter", after.session.invocationCount)
                    .put("pausedInvocationCount",
                        pausedDiagnostics?.session?.invocationCount ?: JSONObject.NULL)
                    .put("pendingTailConfirmed", pendingTailConfirmed)
                    .put("rebound", rebound)
                    .put("leaseReleaseMs", leaseReleaseMs)
                )
                if (cycle in PROCESS_MATRIX_SNAPSHOT_CYCLES) {
                    snapshotsByCycle[cycle] = after
                    snapshots.put(processMatrixSnapshot("cycle-$cycle", cycle, after))
                    playback.assertContinuous("resident-cycle-$cycle")
                }
            }

            val afterMatrixFullSong = completeFullSongBookend("after-matrix")
            assertEquals(
                beforeMatrixFullSong.output?.outputFrameCount,
                afterMatrixFullSong.output?.outputFrameCount,
            )
            assertEquals(
                beforeMatrixFullSong.output?.stems.orEmpty().associate { stem ->
                    stem.semantic to requireNotNull(stem.wavIntegrity).sha256
                },
                afterMatrixFullSong.output?.stems.orEmpty().associate { stem ->
                    stem.semantic to requireNotNull(stem.wavIntegrity).sha256
                },
            )

            val cycle2 = requireNotNull(snapshotsByCycle[2])
            val cycle20 = requireNotNull(snapshotsByCycle[20])
            val pssGrowthBytes = cycle20.memory.pssBytes - cycle2.memory.pssBytes
            val mappedRegionGrowth = cycle20.memory.mappedRegionCount -
                cycle2.memory.mappedRegionCount
            assertTrue(
                "Resident process PSS grew by $pssGrowthBytes bytes.",
                pssGrowthBytes <= PROCESS_MATRIX_MAXIMUM_PSS_GROWTH_BYTES,
            )
            assertTrue(
                "Resident process map count grew by $mappedRegionGrowth.",
                mappedRegionGrowth <= PROCESS_MATRIX_MAXIMUM_MAP_GROWTH,
            )
            snapshotsByCycle.values.forEach { diagnostics ->
                assertTrue(
                    "The largest x86 virtual-address gap crossed the frozen floor.",
                    requireNotNull(diagnostics.memory.largestFreeAddressGapBytes) >=
                        SourceSeparationProcessLifecyclePolicy
                            .MINIMUM_LARGEST_FREE_ADDRESS_GAP_BYTES,
                )
            }
            val finalHost = requireNotNull(host)
            val beforeRecycle = finalHost.processDiagnostics()
            playback.assertContinuous("before-final-recycle")
            snapshots.put(processMatrixSnapshot(
                "before-recycle",
                PROCESS_MATRIX_CYCLE_COUNT,
                beforeRecycle,
            ))
            val unexpectedDeaths = retiredUnexpectedDeaths +
                finalHost.connectionDiagnostics.unexpectedBinderDeathCount
            assertEquals(0, unexpectedDeaths)
            val recycle = finalHost.recycle(
                SourceSeparationIpcRecycleReason.ValidationRequested,
                recycleToken = "phase3-matrix-recycle-0001",
            )
            assertEquals(beforeRecycle.processGeneration, recycle.oldProcess.processGeneration)
            assertNotEquals(
                recycle.oldProcess.processGeneration,
                recycle.newProcess.processGeneration,
            )
            playback.assertContinuous("after-final-recycle")

            report.put("status", "passed")
            report.put("originalPlayback", playback.report())
            report.put("processMatrix", JSONObject()
                .put("cycleCount", PROCESS_MATRIX_CYCLE_COUNT)
                .put("sessionId", expectedSessionId)
                .put("processGeneration", expectedGeneration)
                .put("processStartTicks", expectedStartTicks)
                .put("nativeSessionCreationCount",
                    beforeRecycle.session.nativeSessionCreationCount)
                .put("finalInvocationCount", beforeRecycle.session.invocationCount)
                .put("pssGrowthCycle2To20Bytes", pssGrowthBytes)
                .put("mappedRegionGrowthCycle2To20", mappedRegionGrowth)
                .put("unexpectedBinderDeathCount", unexpectedDeaths)
                .put("cases", cases)
                .put("fullSongBookends", fullSongBookends)
                .put("snapshots", snapshots)
                .put("recycle", JSONObject()
                    .put("token", recycle.recycleToken)
                    .put("oldGeneration", recycle.oldProcess.processGeneration)
                    .put("newGeneration", recycle.newProcess.processGeneration)
                    .put("expectedBinderDeath", recycle.binderDeath.expected)
                )
            )
        } catch (error: Throwable) {
            report.put("status", "failed")
            report.put("error", "${error::class.java.name}: ${error.message}")
            report.put("processMatrix", JSONObject()
                .put("cases", cases)
                .put("fullSongBookends", fullSongBookends)
                .put("snapshots", snapshots)
            )
            throw error
        } finally {
            coordinators.forEach { coordinator -> coordinator.cancel() }
            originalPlayback?.let { playback ->
                report.put("originalPlayback", playback.report())
                playback.close()
            }
            host?.close()
            mediaUri?.let { uri ->
                runCatching { context.contentResolver.delete(uri, null, null) }
            }
            tailMediaUri?.let { uri ->
                runCatching { context.contentResolver.delete(uri, null, null) }
            }
            writeReport(context, runId, "process-matrix", report)
        }
    }

    @Test
    fun validateProcessModelSwitchMatrix() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val runId = arguments.requiredString(ARG_RUN_ID).requireSafeName()
        require(MdxX86ProcessValidationOverride.buildEnabled) {
            "The process model-switch matrix requires the x86 validation build."
        }
        val report = baseReport(context, runId, arguments)
        val events = Collections.synchronizedList(
            mutableListOf<SourceSeparationExecutionHostEvent>(),
        )
        val coordinators = mutableListOf<SourceSeparationForegroundWorkerCoordinator>()
        val switchCases = JSONArray()
        var host: BoundRemoteSourceSeparationExecutionHost? = null
        var mediaUri: Uri? = null
        var originalPlayback: OriginalAudioPlaybackProbe? = null

        try {
            val sourcePath = arguments.requiredString(ARG_SOURCE_PATH)
            mediaUri = registerSourceInMediaStore(context, sourcePath, runId)
            val source = resolveMediaStoreSong(context, mediaUri, sourcePath)
            val durationMs = maxOf(
                source.duration,
                arguments.optionalLong(ARG_FIXTURE_DURATION_US) / 1_000L,
            )
            val preferences = get<SharedPreferences>(SharedPreferences::class.java)
            check(preferences.edit()
                .putBoolean(SOURCE_SEPARATION_WINDOW_DECODE, true)
                .putBoolean(SOURCE_SEPARATION_AUTO_FLAC_COMPRESSION, false)
                .putInt(SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT, 1)
                .commit()
            ) { "Could not persist model-switch matrix preferences." }
            val playback = startOriginalAudioPlayback(
                context = context,
                source = source,
                operation = "process model-switch matrix playback",
            ).also { originalPlayback = it }

            val presetRepository = get<SourceSeparationPresetRepository>(
                SourceSeparationPresetRepository::class.java,
            )
            val primaryModelId = arguments.requiredString(ARG_MODEL_ID)
            val primaryArtifactSha256 = arguments.requiredString(ARG_ARTIFACT_SHA256)
            val secondaryModelId = arguments.requiredString(ARG_SECONDARY_MODEL_ID)
            val secondaryArtifactSha256 = arguments.requiredString(
                ARG_SECONDARY_ARTIFACT_SHA256,
            )
            assertExpectedActivePreset(arguments)
            val installedSecondary = get<SourceSeparationPresetDownloader>(
                SourceSeparationPresetDownloader::class.java,
            ).download(secondaryModelId)
            assertEquals(secondaryArtifactSha256, installedSecondary.sha256)
            val activeAfterDownload = presetRepository.activeModel() as?
                SourceSeparationActivePresetState.Reference
                ?: error("Downloading KARA replaced the active 9662 reference.")
            assertEquals(primaryModelId, activeAfterDownload.reference.modelId)
            assertEquals(
                primaryArtifactSha256,
                activeAfterDownload.reference.artifactSha256,
            )

            val cacheRepository = get<SourceSeparationModelAwareCacheRepository>(
                SourceSeparationModelAwareCacheRepository::class.java,
            )
            val store = get<SourceSeparationCacheStore>(SourceSeparationCacheStore::class.java)
            val runtimeFacade = createCpuRuntimeFacade(
                context = context,
                preferences = preferences,
                presetRepository = presetRepository,
                backendMode = BackendMode.Auto,
                processorCount = null,
                executionHostMode = Phase7ExecutionHostMode.BoundRemote,
                executionHostEventSink = events::add,
                boundRemoteHostSink = { host = it },
            )

            fun newWorker() = SourceSeparationForegroundWorkerCoordinator(
                context = context,
                preferences = preferences,
                sourceSeparationRuntime = runtimeFacade,
            ).also { worker ->
                coordinators += worker
                worker.attachCallbacks(RecordingCallbacks())
                worker.updateSong(
                    song = source,
                    positionMs = 0L,
                    durationMs = durationMs,
                    isPlaying = false,
                    sourceSeparationBlend = TEST_BLEND,
                )
            }

            fun resolveActiveSong(
                expectedModelId: String,
                expectedSha256: String,
            ): SourceSeparationRuntimeSong {
                val resolved = (runtimeFacade.resolve(source) as?
                    SourceSeparationRuntimeSongResolution.Ready)?.song
                    ?: error("The active switch target could not resolve.")
                assertEquals(expectedModelId, resolved.modelId)
                assertEquals(expectedSha256, resolved.artifactSha256)
                return resolved
            }

            fun completeExactRun(
                runtimeSong: SourceSeparationRuntimeSong,
            ): SourceSeparationCacheManifest {
                clearExactCacheEntry(runtimeFacade, runtimeSong.cacheKey)
                val worker = newWorker()
                assertTrue(worker.startCurrentSong())
                waitForCompleted(worker)
                worker.cancel()
                waitForInactive(worker)
                waitForCacheLeaseRelease(cacheRepository, runtimeSong.cacheKey)
                val completed = runtimeFacade.cacheStatus(runtimeSong) as?
                    SourceSeparationModelAwareCacheStatus.Completed
                    ?: error("The switched model did not produce a completed exact cache.")
                assertEquals(runtimeSong.modelId, completed.manifest.identity.modelId)
                assertEquals(
                    runtimeSong.artifactSha256,
                    completed.manifest.identity.artifactSha256,
                )
                return completed.manifest
            }

            val seedSong = resolveActiveSong(primaryModelId, primaryArtifactSha256)
            val seedManifest = completeExactRun(seedSong)
            val initialDiagnostics = requireNotNull(host).processDiagnostics()
            assertEquals(1, initialDiagnostics.session.nativeSessionCreationCount)
            assertTrue(initialDiagnostics.session.invocationCount > 0L)
            assertEquals(
                SourceSeparationProcessSessionState.Resident,
                initialDiagnostics.session.state,
            )
            var currentSessionId = requireNotNull(initialDiagnostics.session.sessionId)
            playback.assertContinuous("after-primary-seed")

            repeat(PROCESS_MODEL_SWITCH_COUNT) { zeroBasedIndex ->
                val switchIndex = zeroBasedIndex + 1
                val selectSecondary = switchIndex % 2 == 1
                val targetModelId = if (selectSecondary) secondaryModelId else primaryModelId
                val targetArtifactSha256 = if (selectSecondary) {
                    secondaryArtifactSha256
                } else {
                    primaryArtifactSha256
                }
                val selected = presetRepository.activate(
                    sha256 = targetArtifactSha256,
                    platform = AndroidMdxRuntimePlatformProvider.current(),
                    scope = SourceSeparationPresetSelectionScope.InternalValidation,
                    experimentalConfirmed = true,
                )
                assertEquals(targetModelId, selected.modelId)
                assertEquals(targetArtifactSha256, selected.artifactSha256)
                val targetSong = resolveActiveSong(targetModelId, targetArtifactSha256)
                clearExactCacheEntry(runtimeFacade, targetSong.cacheKey)

                val oldProcess = requireNotNull(host).processDiagnostics()
                val oldInvocationCount = oldProcess.session.invocationCount
                val mismatchWorker = newWorker()
                assertTrue(mismatchWorker.startCurrentSong())
                val mismatchState = waitForFailed(mismatchWorker)
                mismatchWorker.cancel()
                waitForInactive(mismatchWorker)
                val mismatchLeaseReleaseMs = waitForCacheLeaseRelease(
                    cacheRepository,
                    targetSong.cacheKey,
                )
                val failedManifest = requireNotNull(store.readManifest(targetSong.cacheKey))
                assertEquals(SourceSeparationCacheManifestState.Failed, failedManifest.state)
                val mismatchMessage = listOfNotNull(
                    mismatchState.message,
                    failedManifest.error?.message,
                ).joinToString(" | ")
                assertTrue(
                    "The old generation did not report its model-key boundary: $mismatchMessage",
                    "session-key-change" in mismatchMessage,
                )
                val mismatchDiagnostics = requireNotNull(host).processDiagnostics()
                assertEquals(oldProcess.processGeneration,
                    mismatchDiagnostics.processGeneration)
                assertEquals(oldProcess.processStartTicks,
                    mismatchDiagnostics.processStartTicks)
                assertEquals(oldInvocationCount,
                    mismatchDiagnostics.session.invocationCount)
                assertEquals(1,
                    mismatchDiagnostics.session.nativeSessionCreationCount)
                assertEquals("session-key-change",
                    mismatchDiagnostics.session.recycleReason)
                assertEquals(SourceSeparationProcessSessionState.Resident,
                    mismatchDiagnostics.session.state)

                playback.assertContinuous("switch-$switchIndex-before-recycle")
                val recycle = requireNotNull(host).recycle(
                    SourceSeparationIpcRecycleReason.ModelOrRuntimeKeyChanged,
                    recycleToken = "phase3-switch-" + switchIndex.toString().padStart(2, '0'),
                )
                assertEquals(oldProcess.processGeneration,
                    recycle.oldProcess.processGeneration)
                assertNotEquals(recycle.oldProcess.processGeneration,
                    recycle.newProcess.processGeneration)
                assertNotEquals(recycle.oldProcess.processStartTicks,
                    recycle.newProcess.processStartTicks)
                assertTrue(recycle.binderDeath.expected)
                val emptyGeneration = requireNotNull(host).processDiagnostics()
                assertEquals(SourceSeparationProcessSessionState.Empty,
                    emptyGeneration.session.state)
                assertEquals(0, emptyGeneration.session.nativeSessionCreationCount)
                playback.assertContinuous("switch-$switchIndex-after-recycle")

                clearExactCacheEntry(runtimeFacade, targetSong.cacheKey)
                val completedManifest = completeExactRun(targetSong)
                val completedDiagnostics = requireNotNull(host).processDiagnostics()
                assertEquals(recycle.newProcess.processGeneration,
                    completedDiagnostics.processGeneration)
                assertEquals(recycle.newProcess.processStartTicks,
                    completedDiagnostics.processStartTicks)
                assertEquals(1,
                    completedDiagnostics.session.nativeSessionCreationCount)
                assertTrue(completedDiagnostics.session.invocationCount > 0L)
                assertEquals(SourceSeparationProcessSessionState.Resident,
                    completedDiagnostics.session.state)
                val nextSessionId = requireNotNull(completedDiagnostics.session.sessionId)
                assertNotEquals(currentSessionId, nextSessionId)
                currentSessionId = nextSessionId
                assertEquals(targetModelId, completedManifest.identity.modelId)
                assertEquals(targetArtifactSha256,
                    completedManifest.identity.artifactSha256)
                playback.assertContinuous("switch-$switchIndex-after-run")

                switchCases.put(JSONObject()
                    .put("switch", switchIndex)
                    .put("targetModelId", targetModelId)
                    .put("targetArtifactSha256", targetArtifactSha256)
                    .put("mismatchGeneration", oldProcess.processGeneration)
                    .put("mismatchInvocationCount", oldInvocationCount)
                    .put("mismatchLeaseReleaseMs", mismatchLeaseReleaseMs)
                    .put("recycleToken", recycle.recycleToken)
                    .put("newGeneration", completedDiagnostics.processGeneration)
                    .put("newProcessStartTicks", completedDiagnostics.processStartTicks)
                    .put("sessionId", nextSessionId)
                    .put("nativeSessionCreationCount",
                        completedDiagnostics.session.nativeSessionCreationCount)
                    .put("invocationCount", completedDiagnostics.session.invocationCount)
                    .put("pssBytes", completedDiagnostics.memory.pssBytes)
                    .put("mappedRegionCount",
                        completedDiagnostics.memory.mappedRegionCount)
                    .put("largestFreeAddressGapBytes",
                        completedDiagnostics.memory.largestFreeAddressGapBytes ?: JSONObject.NULL)
                )
            }

            val finalHost = requireNotNull(host)
            assertEquals(
                PROCESS_MODEL_SWITCH_COUNT,
                finalHost.connectionDiagnostics.expectedBinderDeathCount,
            )
            assertEquals(0, finalHost.connectionDiagnostics.unexpectedBinderDeathCount)
            report.put("status", "passed")
            applyRuntimeEvidence(report, seedManifest)
            report.put("originalPlayback", playback.report())
            report.put("processModelSwitchMatrix", JSONObject()
                .put("switchCount", PROCESS_MODEL_SWITCH_COUNT)
                .put("primaryModelId", primaryModelId)
                .put("primaryArtifactSha256", primaryArtifactSha256)
                .put("secondaryModelId", secondaryModelId)
                .put("secondaryArtifactSha256", secondaryArtifactSha256)
                .put("downloadPreservedActiveSelection", true)
                .put("expectedBinderDeathCount",
                    finalHost.connectionDiagnostics.expectedBinderDeathCount)
                .put("unexpectedBinderDeathCount",
                    finalHost.connectionDiagnostics.unexpectedBinderDeathCount)
                .put("switches", switchCases)
            )
        } catch (error: Throwable) {
            report.put("status", "failed")
            report.put("error", "${error::class.java.name}: ${error.message}")
            report.put("processModelSwitchMatrix", JSONObject()
                .put("switches", switchCases)
            )
            throw error
        } finally {
            coordinators.forEach { coordinator -> coordinator.cancel() }
            originalPlayback?.let { playback ->
                report.put("originalPlayback", playback.report())
                playback.close()
            }
            host?.close()
            mediaUri?.let { uri ->
                runCatching { context.contentResolver.delete(uri, null, null) }
            }
            writeReport(context, runId, "process-switch-matrix", report)
        }
    }

    @Test
    fun validateProcessFaultMatrix() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val runId = arguments.requiredString(ARG_RUN_ID).requireSafeName()
        require(MdxX86ProcessValidationOverride.buildEnabled) {
            "The process fault matrix requires the x86 validation build."
        }
        val report = baseReport(context, runId, arguments)
        val coordinators = mutableListOf<SourceSeparationForegroundWorkerCoordinator>()
        val hostEvents = Collections.synchronizedList(
            mutableListOf<SourceSeparationExecutionHostEvent>(),
        )
        val callbackFaultArmed = AtomicBoolean(true)
        val callbackFaultObserved = AtomicBoolean(false)
        var host: BoundRemoteSourceSeparationExecutionHost? = null
        var mediaUri: Uri? = null

        try {
            val sourcePath = arguments.requiredString(ARG_SOURCE_PATH)
            mediaUri = registerSourceInMediaStore(context, sourcePath, runId)
            val source = resolveMediaStoreSong(context, mediaUri, sourcePath)
            val durationMs = maxOf(
                source.duration,
                arguments.optionalLong(ARG_FIXTURE_DURATION_US) / 1_000L,
            )
            val preferences = get<SharedPreferences>(SharedPreferences::class.java)
            check(preferences.edit()
                .putBoolean(SOURCE_SEPARATION_WINDOW_DECODE, true)
                .putBoolean(SOURCE_SEPARATION_AUTO_FLAC_COMPRESSION, false)
                .putInt(SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT, 1)
                .commit()
            ) { "Could not persist process fault-matrix preferences." }
            val presetRepository = get<SourceSeparationPresetRepository>(
                SourceSeparationPresetRepository::class.java,
            )
            assertExpectedActivePreset(arguments)
            val cacheRepository = get<SourceSeparationModelAwareCacheRepository>(
                SourceSeparationModelAwareCacheRepository::class.java,
            )
            val store = get<SourceSeparationCacheStore>(SourceSeparationCacheStore::class.java)
            val eventSink: (SourceSeparationExecutionHostEvent) -> Unit = { event ->
                    hostEvents += event
                    val payload = event.payload
                    if (callbackFaultArmed.get() &&
                        payload is SourceSeparationExecutionHostEventPayload
                            .SegmentStateChanged &&
                        payload.state == SourceSeparationSegmentState.Ready
                    ) {
                        callbackFaultArmed.set(false)
                        callbackFaultObserved.set(true)
                        throw IllegalStateException("phase3-injected-callback-failure")
                    }
                }
            fun createRuntime() = createCpuRuntimeFacade(
                context = context,
                preferences = preferences,
                presetRepository = presetRepository,
                backendMode = BackendMode.Auto,
                processorCount = null,
                executionHostMode = Phase7ExecutionHostMode.BoundRemote,
                executionHostEventSink = eventSink,
                boundRemoteHostSink = { host = it },
            )
            var runtimeFacade = createRuntime()
            var runtimeSong = (runtimeFacade.resolve(source) as?
                SourceSeparationRuntimeSongResolution.Ready)?.song
                ?: error("The process fault-matrix source could not be admitted.")

            fun newWorker() = SourceSeparationForegroundWorkerCoordinator(
                context = context,
                preferences = preferences,
                sourceSeparationRuntime = runtimeFacade,
            ).also { worker ->
                coordinators += worker
                worker.attachCallbacks(RecordingCallbacks())
                worker.updateSong(
                    song = source,
                    positionMs = 0L,
                    durationMs = durationMs,
                    isPlaying = false,
                    sourceSeparationBlend = TEST_BLEND,
                )
            }

            clearExactCacheEntry(runtimeFacade, runtimeSong.cacheKey)
            val failingWorker = newWorker()
            assertTrue(failingWorker.startCurrentSong())
            val callbackFailure = waitForFailed(failingWorker)
            failingWorker.cancel()
            waitForInactive(failingWorker)
            val callbackLeaseReleaseMs = waitForCacheLeaseRelease(
                cacheRepository,
                runtimeSong.cacheKey,
            )
            assertTrue(callbackFaultObserved.get())
            val failedManifest = requireNotNull(store.readManifest(runtimeSong.cacheKey))
            assertEquals(SourceSeparationCacheManifestState.Failed, failedManifest.state)
            val callbackFailureDiagnostics = requireNotNull(host).processDiagnostics()
            assertEquals(
                SourceSeparationProcessSessionState.Resident,
                callbackFailureDiagnostics.session.state,
            )
            assertFalse(callbackFailureDiagnostics.session.poisoned)
            assertEquals(1,
                callbackFailureDiagnostics.session.nativeSessionCreationCount)
            assertTrue(callbackFailureDiagnostics.session.invocationCount > 0L)
            val retainedSessionId = requireNotNull(
                callbackFailureDiagnostics.session.sessionId,
            )

            val callbackFailedHost = requireNotNull(host)
            callbackFailedHost.close()
            SystemClock.sleep(PROCESS_REBIND_SETTLE_MS)
            runtimeFacade = createRuntime()
            runtimeSong = (runtimeFacade.resolve(source) as?
                SourceSeparationRuntimeSongResolution.Ready)?.song
                ?: error("The callback-failure retry could not rebind.")
            val callbackRebindDiagnostics = requireNotNull(host).processDiagnostics()
            assertEquals(
                callbackFailureDiagnostics.processGeneration,
                callbackRebindDiagnostics.processGeneration,
            )
            assertEquals(retainedSessionId,
                callbackRebindDiagnostics.session.sessionId)

            clearExactCacheEntry(runtimeFacade, runtimeSong.cacheKey)
            val retryWorker = newWorker()
            assertTrue(retryWorker.startCurrentSong())
            waitForCompleted(retryWorker)
            retryWorker.cancel()
            waitForInactive(retryWorker)
            val retryLeaseReleaseMs = waitForCacheLeaseRelease(
                cacheRepository,
                runtimeSong.cacheKey,
            )
            val retryDiagnostics = requireNotNull(host).processDiagnostics()
            assertEquals(callbackFailureDiagnostics.processGeneration,
                retryDiagnostics.processGeneration)
            assertEquals(retainedSessionId, retryDiagnostics.session.sessionId)
            assertEquals(1, retryDiagnostics.session.nativeSessionCreationCount)
            assertTrue(
                retryDiagnostics.session.invocationCount >
                    callbackFailureDiagnostics.session.invocationCount,
            )
            assertTrue(
                runtimeFacade.cacheStatus(runtimeSong) is
                    SourceSeparationModelAwareCacheStatus.Completed,
            )

            val originalHost = requireNotNull(host)
            originalHost.close()
            SystemClock.sleep(PROCESS_REBIND_SETTLE_MS)
            val timeoutHost = BoundRemoteSourceSeparationExecutionHost(
                context.applicationContext,
                recycleTimeoutMs = PROCESS_FAULT_RECYCLE_TIMEOUT_MS,
            )
            host = timeoutHost
            val retainedBeforeTimeout = timeoutHost.processDiagnostics()
            assertEquals(retryDiagnostics.processGeneration,
                retainedBeforeTimeout.processGeneration)
            assertEquals(retainedSessionId, retainedBeforeTimeout.session.sessionId)

            val recycleTimedOut = try {
                timeoutHost.recycle(
                    SourceSeparationIpcRecycleReason.ValidationRequested,
                    recycleToken = "phase3-timeout-recycle-0001",
                )
                false
            } catch (_: SourceSeparationRemoteRecycleTimeoutException) {
                true
            }
            assertTrue("The 1 ms recycle fault did not time out.", recycleTimedOut)
            waitForRemoteConnectionState(
                timeoutHost,
                SourceSeparationRemoteConnectionState.Dead,
            )
            val timeoutDeath = requireNotNull(
                timeoutHost.connectionDiagnostics.lastBinderDeath,
            )
            assertFalse(timeoutDeath.expected)
            assertEquals(1,
                timeoutHost.connectionDiagnostics.unexpectedBinderDeathCount)
            val generationAfterTimeout = timeoutHost.processGeneration
            val recoveredAfterTimeout = timeoutHost.processDiagnostics()
            assertNotEquals(retainedBeforeTimeout.processGeneration,
                generationAfterTimeout)
            assertNotEquals(retainedBeforeTimeout.processStartTicks,
                recoveredAfterTimeout.processStartTicks)

            val idleGeneration = recoveredAfterTimeout.processGeneration
            val idleStartTicks = recoveredAfterTimeout.processStartTicks
            android.os.Process.killProcess(recoveredAfterTimeout.pid)
            waitForRemoteConnectionState(
                timeoutHost,
                SourceSeparationRemoteConnectionState.Dead,
            )
            assertEquals(2,
                timeoutHost.connectionDiagnostics.unexpectedBinderDeathCount)
            assertFalse(requireNotNull(
                timeoutHost.connectionDiagnostics.lastBinderDeath,
            ).expected)
            val generationAfterUnexpectedDeath = timeoutHost.processGeneration
            val recoveredAfterUnexpectedDeath = timeoutHost.processDiagnostics()
            assertNotEquals(idleGeneration, generationAfterUnexpectedDeath)
            assertNotEquals(idleStartTicks,
                recoveredAfterUnexpectedDeath.processStartTicks)

            report.put("status", "passed")
            report.put("processFaultMatrix", JSONObject()
                .put("callbackDelivery", JSONObject()
                    .put("failureMessage", callbackFailure.message ?: JSONObject.NULL)
                    .put("cacheState", failedManifest.state.name)
                    .put("leaseReleaseMs", callbackLeaseReleaseMs)
                    .put("processGeneration",
                        callbackFailureDiagnostics.processGeneration)
                    .put("sessionId", retainedSessionId)
                    .put("nativeSessionCreationCount",
                        callbackFailureDiagnostics.session.nativeSessionCreationCount)
                    .put("failureInvocationCount",
                        callbackFailureDiagnostics.session.invocationCount)
                    .put("retryInvocationCount", retryDiagnostics.session.invocationCount)
                    .put("retryLeaseReleaseMs", retryLeaseReleaseMs)
                    .put("clientRebound", true)
                    .put("retryUsedSameSession",
                        retryDiagnostics.session.sessionId == retainedSessionId)
                )
                .put("recycleTimeout", JSONObject()
                    .put("timeoutMs", PROCESS_FAULT_RECYCLE_TIMEOUT_MS)
                    .put("oldGeneration", retainedBeforeTimeout.processGeneration)
                    .put("binderDeathExpected", timeoutDeath.expected)
                    .put("recoveredGeneration", recoveredAfterTimeout.processGeneration)
                    .put("recoveredProcessStartTicks",
                        recoveredAfterTimeout.processStartTicks)
                )
                .put("unexpectedIdleDeath", JSONObject()
                    .put("oldGeneration", idleGeneration)
                    .put("oldProcessStartTicks", idleStartTicks)
                    .put("unexpectedBinderDeathCount",
                        timeoutHost.connectionDiagnostics.unexpectedBinderDeathCount)
                    .put("recoveredGeneration",
                        recoveredAfterUnexpectedDeath.processGeneration)
                    .put("recoveredProcessStartTicks",
                        recoveredAfterUnexpectedDeath.processStartTicks)
                )
            )
        } catch (error: Throwable) {
            report.put("status", "failed")
            report.put("error", "${error::class.java.name}: ${error.message}")
            throw error
        } finally {
            coordinators.forEach { coordinator -> coordinator.cancel() }
            host?.close()
            mediaUri?.let { uri ->
                runCatching { context.contentResolver.delete(uri, null, null) }
            }
            writeReport(context, runId, "process-fault-matrix", report)
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
            var cancellationLatencyMs = 0L

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
                val cancellationStartedAt = SystemClock.elapsedRealtime()
                cancelWorker.cancel()
                waitForInactive(cancelWorker)
                cancellationLatencyMs =
                    SystemClock.elapsedRealtime() - cancellationStartedAt
                val maximumCancellationLatencyMs = arguments.optionalLong(
                    ARG_MAXIMUM_CANCELLATION_LATENCY_MS,
                    DEFAULT_MAXIMUM_CANCELLATION_LATENCY_MS,
                )
                assertTrue(
                    "Cancellation took " + cancellationLatencyMs +
                        "ms; maximum is " + maximumCancellationLatencyMs + "ms.",
                    cancellationLatencyMs <= maximumCancellationLatencyMs,
                )
                canceledStatus = runtimeFacade.cacheStatus(runtimeSong)
                assertTrue(
                    "Cancellation must not publish a completed entry: $canceledStatus",
                    canceledStatus !is SourceSeparationModelAwareCacheStatus.Completed,
                )
            }

            report.put("status", "passed")
            report.put("timing", report.getJSONObject("timing")
                .put("cancellationLatencyMs", cancellationLatencyMs)
            )
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
        val executionHostMode = Phase7ExecutionHostMode.parse(
            arguments.getString(ARG_EXECUTION_HOST_MODE),
        )
        val report = baseReport(context, runId, arguments)
        var controller: MediaController? = null
        var mediaUri: Uri? = null
        var boundRemoteHost: BoundRemoteSourceSeparationExecutionHost? = null
        var remoteWorker: SourceSeparationForegroundWorkerCoordinator? = null
        var originalRuntime: SourceSeparationRuntimeFacade? = null
        var originalWorker: SourceSeparationForegroundWorkerCoordinator? = null
        var screenOffIssued = false
        val hostEvents = Collections.synchronizedList(
            mutableListOf<SourceSeparationExecutionHostEvent>(),
        )

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
            val runtimeFacade = if (executionHostMode == Phase7ExecutionHostMode.BoundRemote) {
                val koin = GlobalContext.get()
                originalRuntime = koin.get()
                originalWorker = koin.get()
                val remoteRuntime = createCpuRuntimeFacade(
                    context = context,
                    preferences = preferences,
                    presetRepository = get(SourceSeparationPresetRepository::class.java),
                    backendMode = BackendMode.Auto,
                    processorCount = null,
                    executionHostMode = executionHostMode,
                    executionHostEventSink = hostEvents::add,
                    boundRemoteHostSink = { boundRemoteHost = it },
                )
                val worker = SourceSeparationForegroundWorkerCoordinator(
                    context = context,
                    preferences = preferences,
                    sourceSeparationRuntime = remoteRuntime,
                ).also { remoteWorker = it }
                koin.declare<SourceSeparationRuntimeFacade>(remoteRuntime)
                koin.declare(worker)
                remoteRuntime
            } else {
                get(SourceSeparationRuntimeFacade::class.java)
            }
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
            val screenOffAfterReady = arguments.optionalBoolean(
                ARG_SCREEN_OFF_AFTER_READY,
                false,
            )
            val eventsBeforeScreenOff = synchronized(hostEvents) { hostEvents.size }
            var screenOffObserved = false
            var completedWhileScreenOff = false
            if (screenOffAfterReady) {
                instrumentation.uiAutomation
                    .executeShellCommand("input keyevent KEYCODE_SLEEP")
                    .close()
                screenOffIssued = true
                val powerManager = context.getSystemService(PowerManager::class.java)
                val screenOffDeadline = SystemClock.elapsedRealtime() + SCREEN_STATE_TIMEOUT_MS
                while (powerManager.isInteractive &&
                    SystemClock.elapsedRealtime() < screenOffDeadline
                ) {
                    SystemClock.sleep(POLL_INTERVAL_MS)
                }
                screenOffObserved = !powerManager.isInteractive
                val observationDeadline = SystemClock.elapsedRealtime() +
                    SCREEN_OFF_OBSERVATION_MS
                while (!resultFuture.isDone &&
                    SystemClock.elapsedRealtime() < observationDeadline
                ) {
                    SystemClock.sleep(POLL_INTERVAL_MS)
                }
                completedWhileScreenOff = resultFuture.isDone
                instrumentation.uiAutomation
                    .executeShellCommand("input keyevent KEYCODE_WAKEUP")
                    .close()
                instrumentation.uiAutomation
                    .executeShellCommand("wm dismiss-keyguard")
                    .close()
                screenOffIssued = false
            }
            val eventsAfterScreenOff = synchronized(hostEvents) { hostEvents.size }
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
                .put("screenOffRequested", screenOffAfterReady)
                .put("screenOffObserved", screenOffObserved)
                .put("completedWhileScreenOff", completedWhileScreenOff)
                .put(
                    "eventsAdvancedWhileScreenOff",
                    eventsAfterScreenOff > eventsBeforeScreenOff,
                )
            )
            report.put("cache", report.getJSONObject("cache")
                .put("cacheKey", runtimeSong.cacheKey)
                .put("exactIdentity", true)
                .put("completedPlayable", true)
            )
            report.put(
                "executionHost",
                if (executionHostMode == Phase7ExecutionHostMode.BoundRemote) {
                    executionHostReport(
                        mode = executionHostMode,
                        events = synchronized(hostEvents) { hostEvents.toList() },
                        remoteHost = boundRemoteHost,
                    )
                } else {
                    JSONObject().put("mode", executionHostMode.argumentValue)
                },
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
            remoteWorker?.cancel()
            if (originalRuntime != null && originalWorker != null) {
                GlobalContext.get().declare<SourceSeparationRuntimeFacade>(
                    requireNotNull(originalRuntime),
                )
                GlobalContext.get().declare(requireNotNull(originalWorker))
            }
            boundRemoteHost?.close()
            if (screenOffIssued) {
                instrumentation.uiAutomation
                    .executeShellCommand("input keyevent KEYCODE_WAKEUP")
                    .close()
                instrumentation.uiAutomation
                    .executeShellCommand("wm dismiss-keyguard")
                    .close()
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

    private fun startOriginalAudioPlayback(
        context: Context,
        source: Song,
        operation: String,
    ): OriginalAudioPlaybackProbe {
        val sessionToken = SessionToken(
            context,
            ComponentName(context, PlaybackService::class.java),
        )
        val controller = MediaController.Builder(context, sessionToken)
            .buildAsync()
            .get(MEDIA_SESSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        try {
            val disableSeparationCommand = SessionCommand(
                Playback.SET_SOURCE_SEPARATION_PLAYBACK_ENABLED,
                Bundle.EMPTY,
            )
            assertTrue(
                "PlaybackService did not expose the source-separation playback command.",
                onMediaControllerThread(controller) {
                    controller.availableSessionCommands.contains(disableSeparationCommand)
                },
            )
            val disableResult = onMediaControllerThread(controller) {
                controller.sendCustomCommand(
                    disableSeparationCommand,
                    Bundle().apply {
                        putBoolean(Playback.EXTRA_SOURCE_SEPARATION_ENABLED, false)
                        putBoolean(Playback.EXTRA_SOURCE_SEPARATION_SHOW_MESSAGE, false)
                    },
                )
            }.get(MEDIA_SESSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertEquals(SessionResult.RESULT_SUCCESS, disableResult.resultCode)

            onMediaControllerThread(controller) {
                controller.pause()
                controller.clearMediaItems()
                controller.volume = 0f
            }
            val sourcePreparationAttempts = prepareMediaControllerSource(
                controller = controller,
                source = source,
                operation = operation,
            )
            setMediaControllerRepeatOne(controller, operation)
            val probe = OriginalAudioPlaybackProbe(
                controller = controller,
                expectedMediaId = source.id.toString(),
                sourcePreparationAttempts = sourcePreparationAttempts,
            )
            onMediaControllerThread(controller) {
                controller.addListener(probe)
                controller.play()
            }
            waitForMediaController(controller, "$operation start") {
                controller.currentMediaItem?.mediaId == source.id.toString() &&
                    controller.repeatMode == Player.REPEAT_MODE_ONE &&
                    controller.playWhenReady &&
                    controller.isPlaying
            }
            probe.arm()
            probe.assertContinuous("started")
            return probe
        } catch (error: Throwable) {
            runCatching {
                onMediaControllerThread(controller) {
                    controller.pause()
                    controller.clearMediaItems()
                    controller.release()
                }
            }
            throw error
        }
    }

    private fun setMediaControllerRepeatOne(
        controller: MediaController,
        operation: String,
    ) {
        val cycleRepeatCommand = SessionCommand(Playback.CYCLE_REPEAT, Bundle.EMPTY)
        assertTrue(
            "PlaybackService did not expose the repeat-mode command.",
            onMediaControllerThread(controller) {
                controller.availableSessionCommands.contains(cycleRepeatCommand)
            },
        )
        repeat(REPEAT_MODE_CYCLE_LIMIT) {
            val previousMode = onMediaControllerThread(controller) { controller.repeatMode }
            if (previousMode == Player.REPEAT_MODE_ONE) return
            val result = onMediaControllerThread(controller) {
                controller.sendCustomCommand(cycleRepeatCommand, Bundle.EMPTY)
            }.get(MEDIA_SESSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertEquals(SessionResult.RESULT_SUCCESS, result.resultCode)
            waitForMediaController(controller, "$operation repeat-mode transition") {
                controller.repeatMode != previousMode
            }
        }
        assertEquals(
            "Could not select repeat-one for $operation.",
            Player.REPEAT_MODE_ONE,
            onMediaControllerThread(controller) { controller.repeatMode },
        )
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

    private inner class OriginalAudioPlaybackProbe(
        private val controller: MediaController,
        private val expectedMediaId: String,
        private val sourcePreparationAttempts: Int,
    ) : Player.Listener {
        private val armed = AtomicBoolean(false)
        private val closed = AtomicBoolean(false)
        private val automaticDiscontinuityCount = AtomicInteger(0)
        private val sameItemTransitionCount = AtomicInteger(0)
        private val unexpectedEvents = Collections.synchronizedList(mutableListOf<String>())
        private val snapshots = Collections.synchronizedList(mutableListOf<JSONObject>())

        fun arm() {
            armed.set(true)
        }

        fun assertContinuous(label: String): JSONObject {
            waitForMediaController(controller, "original playback at $label") {
                controller.currentMediaItem?.mediaId == expectedMediaId &&
                    controller.repeatMode == Player.REPEAT_MODE_ONE &&
                    controller.playWhenReady &&
                    controller.isPlaying
            }
            val snapshot = onMediaControllerThread(controller) {
                JSONObject()
                    .put("label", label)
                    .put("sampledAtElapsedRealtimeMs", SystemClock.elapsedRealtime())
                    .put("mediaId", controller.currentMediaItem?.mediaId)
                    .put("mediaItemIndex", controller.currentMediaItemIndex)
                    .put("positionMs", controller.currentPosition)
                    .put("durationMs", controller.duration)
                    .put("playWhenReady", controller.playWhenReady)
                    .put("isPlaying", controller.isPlaying)
                    .put("playbackState", controller.playbackState)
                    .put("repeatMode", controller.repeatMode)
            }
            val baseline = synchronized(snapshots) { snapshots.firstOrNull() }
            val expectedPositionMs = baseline?.let {
                val durationMs = snapshot.getLong("durationMs")
                (it.getLong("positionMs") +
                    snapshot.getLong("sampledAtElapsedRealtimeMs") -
                    it.getLong("sampledAtElapsedRealtimeMs")) % durationMs
            } ?: snapshot.getLong("positionMs")
            val directDriftMs = abs(snapshot.getLong("positionMs") - expectedPositionMs)
            val positionDriftMs = minOf(
                directDriftMs,
                snapshot.getLong("durationMs") - directDriftMs,
            )
            snapshot
                .put("expectedPositionMs", expectedPositionMs)
                .put("positionDriftMs", positionDriftMs)
            snapshots += snapshot
            val violations = synchronized(unexpectedEvents) { unexpectedEvents.toList() }
            assertTrue(
                "Original playback position drifted by $positionDriftMs ms at $label.",
                positionDriftMs <= PLAYBACK_CONTINUITY_POSITION_TOLERANCE_MS,
            )
            assertTrue(
                "Original playback continuity failed at $label: ${violations.joinToString()}",
                violations.isEmpty(),
            )
            return snapshot
        }

        fun report(): JSONObject {
            val snapshotCopy = synchronized(snapshots) { snapshots.toList() }
            val eventCopy = synchronized(unexpectedEvents) { unexpectedEvents.toList() }
            return JSONObject()
                .put("expectedMediaId", expectedMediaId)
                .put("sourcePreparationAttempts", sourcePreparationAttempts)
                .put("repeatMode", "one")
                .put("muted", true)
                .put("snapshotCount", snapshotCopy.size)
                .put("automaticDiscontinuityCount", automaticDiscontinuityCount.get())
                .put("sameItemTransitionCount", sameItemTransitionCount.get())
                .put(
                    "maximumPositionDriftMs",
                    snapshotCopy.maxOfOrNull { it.getLong("positionDriftMs") } ?: 0L,
                )
                .put("unexpectedEventCount", eventCopy.size)
                .put("unexpectedEvents", JSONArray(eventCopy))
                .put("snapshots", JSONArray(snapshotCopy))
        }

        fun close() {
            if (!closed.compareAndSet(false, true)) return
            armed.set(false)
            runCatching {
                onMediaControllerThread(controller) {
                    controller.removeListener(this@OriginalAudioPlaybackProbe)
                    controller.pause()
                    controller.clearMediaItems()
                    controller.release()
                }
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (!armed.get()) return
            if (mediaItem?.mediaId == expectedMediaId) {
                sameItemTransitionCount.incrementAndGet()
            } else {
                recordUnexpected("media-item-transition reason=$reason mediaId=${mediaItem?.mediaId}")
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (!armed.get()) return
            if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION &&
                newPosition.mediaItem?.mediaId == expectedMediaId
            ) {
                automaticDiscontinuityCount.incrementAndGet()
            } else {
                recordUnexpected(
                    "position-discontinuity reason=$reason " +
                        "old=${oldPosition.positionMs} new=${newPosition.positionMs}",
                )
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (armed.get() && !playWhenReady) {
                recordUnexpected("play-when-ready-disabled reason=$reason")
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (armed.get() &&
                (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED)
            ) {
                recordUnexpected("terminal-playback-state state=$playbackState")
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (armed.get()) {
                recordUnexpected("player-error code=${error.errorCode} message=${error.message}")
            }
        }

        private fun recordUnexpected(message: String) {
            unexpectedEvents += "${SystemClock.elapsedRealtime()}:$message"
        }
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

    private fun waitForFailed(
        worker: SourceSeparationForegroundWorkerCoordinator,
    ): SourceSeparationUiState.Failed {
        val deadline = SystemClock.elapsedRealtime() + LIFECYCLE_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            when (val state = worker.workerStateFlow.value) {
                is SourceSeparationUiState.Failed -> return state
                is SourceSeparationUiState.Completed,
                is SourceSeparationUiState.Canceled,
                -> error("Worker did not fail at the expected process boundary: $state")
                else -> SystemClock.sleep(POLL_INTERVAL_MS)
            }
        }
        error("Worker did not publish the expected process-boundary failure in time.")
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

    private fun waitForCacheLeaseRelease(
        repository: SourceSeparationModelAwareCacheRepository,
        cacheKey: String,
    ): Long {
        val startedAt = SystemClock.elapsedRealtime()
        val deadline = startedAt + PROCESS_MATRIX_LEASE_RELEASE_TIMEOUT_MS
        while (repository.isLeased(cacheKey) && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        assertFalse(
            "Cache lease did not release within " +
                "$PROCESS_MATRIX_LEASE_RELEASE_TIMEOUT_MS ms: $cacheKey",
            repository.isLeased(cacheKey),
        )
        return SystemClock.elapsedRealtime() - startedAt
    }

    private fun waitForRemoteConnectionState(
        host: BoundRemoteSourceSeparationExecutionHost,
        expected: SourceSeparationRemoteConnectionState,
    ) {
        val deadline = SystemClock.elapsedRealtime() + PROCESS_DEATH_TIMEOUT_MS
        while (host.connectionDiagnostics.state != expected &&
            SystemClock.elapsedRealtime() < deadline
        ) {
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        assertEquals(expected, host.connectionDiagnostics.state)
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

    private fun processMatrixSnapshot(
        label: String,
        cycle: Int,
        diagnostics: SourceSeparationProcessDiagnostics,
    ) = JSONObject()
        .put("label", label)
        .put("cycle", cycle)
        .put("processGeneration", diagnostics.processGeneration)
        .put("pid", diagnostics.pid)
        .put("processStartTicks", diagnostics.processStartTicks)
        .put("vmSizeBytes", diagnostics.memory.vmSizeBytes ?: JSONObject.NULL)
        .put("vmPeakBytes", diagnostics.memory.vmPeakBytes ?: JSONObject.NULL)
        .put("vmRssBytes", diagnostics.memory.vmRssBytes ?: JSONObject.NULL)
        .put("pssBytes", diagnostics.memory.pssBytes)
        .put("nativePssBytes", diagnostics.memory.nativePssBytes)
        .put("threadCount", diagnostics.memory.threadCount)
        .put("mappedRegionCount", diagnostics.memory.mappedRegionCount)
        .put(
            "anonHugePagesBytes",
            diagnostics.memory.anonHugePagesBytes ?: JSONObject.NULL,
        )
        .put(
            "largestFreeAddressGapBytes",
            diagnostics.memory.largestFreeAddressGapBytes ?: JSONObject.NULL,
        )
        .put("sessionState", diagnostics.session.state.name)
        .put("sessionId", diagnostics.session.sessionId ?: JSONObject.NULL)
        .put(
            "nativeSessionCreationCount",
            diagnostics.session.nativeSessionCreationCount,
        )
        .put("activeLeaseCount", diagnostics.session.activeLeaseCount)
        .put("invocationCount", diagnostics.session.invocationCount)
        .put("poisoned", diagnostics.session.poisoned)

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

    private fun processPssBytes(context: Context, pid: Int): Long {
        if (pid <= 0) return 0L
        val manager = context.getSystemService(ActivityManager::class.java)
        return manager.getProcessMemoryInfo(intArrayOf(pid))
            .singleOrNull()
            ?.totalPss
            ?.toLong()
            ?.times(1_024L)
            ?: 0L
    }

    private fun executionHostReport(
        mode: Phase7ExecutionHostMode,
        events: List<SourceSeparationExecutionHostEvent>,
        remoteHost: BoundRemoteSourceSeparationExecutionHost?,
    ): JSONObject {
        assertTrue("The execution host emitted no events.", events.isNotEmpty())
        assertTrue(
            "The execution host did not accept the run first.",
            events.first().payload is SourceSeparationExecutionHostEventPayload.Accepted,
        )
        assertTrue(
            "Execution host event sequences are not strictly monotonic.",
            events.zipWithNext().all { (current, next) -> next.sequence > current.sequence },
        )
        assertTrue(
            "The execution host emitted no preparation event.",
            events.any { it.payload is SourceSeparationExecutionHostEventPayload.Prepared },
        )
        assertTrue(
            "The execution host did not complete with one terminal event.",
            events.last().payload is SourceSeparationExecutionHostEventPayload.Completed &&
                events.count { event ->
                    event.payload is SourceSeparationExecutionHostEventPayload.Completed ||
                        event.payload is SourceSeparationExecutionHostEventPayload.Paused ||
                        event.payload is SourceSeparationExecutionHostEventPayload.Canceled ||
                        event.payload is SourceSeparationExecutionHostEventPayload.Failed
                } == 1,
        )
        val diagnostics = remoteHost?.connectionDiagnostics
        val processDiagnostics = remoteHost?.let { host ->
            runCatching { host.processDiagnostics() }.getOrNull()
                ?: host.connectionDiagnostics.latestProcessDiagnostics
        }
        if (mode == Phase7ExecutionHostMode.BoundRemote) {
            assertEquals(
                SourceSeparationRemoteConnectionState.Connected,
                requireNotNull(diagnostics).state,
            )
            assertNotEquals(Process.myPid(), diagnostics.pid)
        } else {
            assertTrue(remoteHost == null)
        }
        return JSONObject()
            .put("mode", mode.argumentValue)
            .put("processGeneration", diagnostics?.processGeneration ?: JSONObject.NULL)
            .put("remotePid", diagnostics?.pid ?: JSONObject.NULL)
            .put("processStartTicks", diagnostics?.processStartTicks ?: JSONObject.NULL)
            .put("idleRemotePssBytes", diagnostics?.idlePssBytes ?: JSONObject.NULL)
            .put("process", processDiagnostics?.let { process ->
                JSONObject()
                    .put("capturedAtElapsedRealtimeNanos",
                        process.capturedAtElapsedRealtimeNanos)
                    .put("activeRunId", process.activeRunId ?: JSONObject.NULL)
                    .put("vmSizeBytes", process.memory.vmSizeBytes ?: JSONObject.NULL)
                    .put("vmPeakBytes", process.memory.vmPeakBytes ?: JSONObject.NULL)
                    .put("vmRssBytes", process.memory.vmRssBytes ?: JSONObject.NULL)
                    .put("pssBytes", process.memory.pssBytes)
                    .put("nativePssBytes", process.memory.nativePssBytes)
                    .put("threadCount", process.memory.threadCount)
                    .put("mappedRegionCount", process.memory.mappedRegionCount)
                    .put("smapsSource", process.memory.smapsSource.name)
                    .put("anonHugePagesBytes",
                        process.memory.anonHugePagesBytes ?: JSONObject.NULL)
                    .put("largestFreeAddressGapBytes",
                        process.memory.largestFreeAddressGapBytes ?: JSONObject.NULL)
            } ?: JSONObject.NULL)
            .put("session", processDiagnostics?.session?.let { session ->
                JSONObject()
                    .put("state", session.state.name)
                    .put("sessionId", session.sessionId ?: JSONObject.NULL)
                    .put("sessionKey", session.sessionKey ?: JSONObject.NULL)
                    .put("nativeSessionCreationCount", session.nativeSessionCreationCount)
                    .put("activeLeaseCount", session.activeLeaseCount)
                    .put("invocationCount", session.invocationCount)
                    .put("poisoned", session.poisoned)
                    .put("poisonReason", session.poisonReason ?: JSONObject.NULL)
                    .put("recycleReason", session.recycleReason ?: JSONObject.NULL)
                    .put("recycleToken", session.recycleToken ?: JSONObject.NULL)
            } ?: JSONObject.NULL)
            .put("validationOverride",
                processDiagnostics?.validationOverride?.let { validation ->
                    JSONObject()
                        .put("modelId", validation.modelId)
                        .put("artifactSha256", validation.artifactSha256)
                        .put("contractId", validation.contractId)
                        .put("originalStatus", validation.originalStatus)
                        .put("originalReason", validation.originalReason)
                        .put("originalEvidence", validation.originalEvidence)
                        .put("effectiveStatus", validation.effectiveStatus)
                        .put("effectiveReason", validation.effectiveReason)
                } ?: JSONObject.NULL)
            .put("binderDeath", diagnostics?.lastBinderDeath?.let { death ->
                JSONObject()
                    .put("processGeneration", death.processGeneration)
                    .put("pid", death.pid)
                    .put("processStartTicks", death.processStartTicks)
                    .put("expected", death.expected)
                    .put("recycleToken", death.recycleToken ?: JSONObject.NULL)
                    .put("error", death.error)
            } ?: JSONObject.NULL)
            .put("expectedBinderDeathCount",
                diagnostics?.expectedBinderDeathCount ?: JSONObject.NULL)
            .put("unexpectedBinderDeathCount",
                diagnostics?.unexpectedBinderDeathCount ?: JSONObject.NULL)
            .put("eventCount", events.size)
            .put("firstSequence", events.first().sequence)
            .put("lastSequence", events.last().sequence)
            .put(
                "sequenceGapCount",
                events.zipWithNext().count { (current, next) ->
                    next.sequence != current.sequence + 1L
                },
            )
            .put("events", JSONArray(events.map { event ->
                JSONObject()
                    .put("sequence", event.sequence)
                    .put("type", event.payload::class.java.simpleName)
            }))
    }

    private fun createCpuRuntimeFacade(
        context: Context,
        preferences: SharedPreferences,
        presetRepository: SourceSeparationPresetRepository,
        backendMode: BackendMode = BackendMode.Cpu,
        processorCount: Int?,
        xnnPackFlags: Int? = null,
        sessionProviderFactoryOverride: (() -> MdxInferenceSessionProvider)? = null,
        executionHostMode: Phase7ExecutionHostMode = Phase7ExecutionHostMode.InProcess,
        executionHostEventSink: (SourceSeparationExecutionHostEvent) -> Unit = {},
        boundRemoteHostSink: (BoundRemoteSourceSeparationExecutionHost) -> Unit = {},
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
        val engine = if (executionHostMode == Phase7ExecutionHostMode.BoundRemote) {
            require(backendMode == BackendMode.Auto && sessionProviderFactoryOverride == null) {
                "Bound-remote validation uses the production Auto runtime."
            }
            val host = BoundRemoteSourceSeparationExecutionHost(context.applicationContext)
                .also(boundRemoteHostSink)
            SourceSeparationModelAwareEngine.createBoundRemotePrototype(
                context = context,
                presetRepository = presetRepository,
                coordinator = runCoordinator,
                executionHost = host,
                executionHostEventSink = executionHostEventSink,
            )
        } else {
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
            SourceSeparationModelAwareEngine(
                activeModelResolver = presetRepository::resolveActiveCacheModel,
                preflightResolver = AndroidSourceSeparationModelAwarePreflightResolver(context),
                coordinator = runCoordinator,
                rangeExecutor = rangeExecutor,
                constructionGate = { true },
                executionHostEventSink = executionHostEventSink,
            )
        }
        return DefaultSourceSeparationRuntimeFacade(
            activeModelResolver = presetRepository::resolveActiveCacheModelResolution,
            compatibilityResolver = if (
                executionHostMode == Phase7ExecutionHostMode.BoundRemote &&
                MdxX86ProcessValidationOverride.buildEnabled
            ) {
                X86ProcessValidationRuntimeCompatibilityResolver
            } else {
                AndroidSourceSeparationRuntimeCompatibilityResolver
            },
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
            ?: "audio/x-wav".takeIf { extension == "wave" }
            ?: "audio/*"
        @Suppress("DEPRECATION")
        val legacySource = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            File(
                File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                    "BoomingSS",
                ).also { directory ->
                    check(directory.exists() || directory.mkdirs()) {
                        "Unable to create the legacy MediaStore fixture directory."
                    }
                },
                displayName,
            ).also { destination ->
                File(path).copyTo(destination, overwrite = true)
            }
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
            legacySource?.delete()
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
                        // MediaStore may rewrite an unknown source suffix from its MIME type.
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

    private class Phase7ThermalSampler(
        context: Context,
        private val startedAtElapsedMs: Long,
    ) {
        private val powerManager = context.getSystemService(PowerManager::class.java)
        private val samples = mutableListOf<Phase7ThermalSample>()
        private var lastSampleAtElapsedMs = Long.MIN_VALUE

        fun sample(nowElapsedMs: Long, force: Boolean = false) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || powerManager == null) return
            if (!force && lastSampleAtElapsedMs != Long.MIN_VALUE &&
                nowElapsedMs - lastSampleAtElapsedMs < THERMAL_SAMPLE_INTERVAL_MS
            ) {
                return
            }
            val status = runCatching { powerManager.currentThermalStatus }.getOrNull() ?: return
            samples += Phase7ThermalSample(
                elapsedMs = (nowElapsedMs - startedAtElapsedMs).coerceAtLeast(0L),
                status = status,
            )
            lastSampleAtElapsedMs = nowElapsedMs
        }

        fun toJson(): JSONObject {
            return JSONObject()
                .put("available", samples.isNotEmpty())
                .put("sampleIntervalMs", THERMAL_SAMPLE_INTERVAL_MS)
                .put("peakStatus", samples.maxOfOrNull { it.status } ?: JSONObject.NULL)
                .put("samples", JSONArray(samples.map { sample ->
                    JSONObject()
                        .put("elapsedMs", sample.elapsedMs)
                        .put("status", sample.status)
                }))
        }
    }

    private data class Phase7ThermalSample(
        val elapsedMs: Long,
        val status: Int,
    )

    private fun assertExpectedSourceDecode(
        arguments: Bundle,
        record: com.mardous.booming.separation.cache.v2.SourceSeparationCacheRuntimeRecord,
    ) {
        arguments.getString(ARG_FIXTURE_EXPECTED_DECODE_MODE)?.let { expected ->
            assertEquals("Unexpected source decode mode.", expected, record.sourceDecodeMode)
        }
        arguments.getString(ARG_FIXTURE_EXPECTED_DECODE_PROFILE_BASE64)?.let { encoded ->
            assertEquals(
                "Unexpected source decode profile.",
                decodeExpectedNullable(encoded),
                record.sourceDecodeProfile,
            )
        }
        arguments.getString(ARG_FIXTURE_EXPECTED_DECODE_MIME)?.let { expected ->
            assertEquals("Unexpected source decode MIME.", expected, record.sourceDecodeMimeType)
        }
        arguments.getString(ARG_FIXTURE_EXPECTED_FALLBACK_REASON_BASE64)?.let { encoded ->
            assertEquals(
                "Unexpected source decode fallback reason.",
                decodeExpectedNullable(encoded),
                record.sourceDecodeFallbackReason,
            )
        }
    }

    private fun sourceDecodeJson(
        record: com.mardous.booming.separation.cache.v2.SourceSeparationCacheRuntimeRecord,
        detail: String,
        observedMode: String? = null,
    ): JSONObject {
        return JSONObject()
            .put("mode", record.sourceDecodeMode ?: observedMode ?: "unknown")
            .put("profile", record.sourceDecodeProfile ?: JSONObject.NULL)
            .put("mimeType", record.sourceDecodeMimeType ?: JSONObject.NULL)
            .put("sampleRate", record.sourceDecodeSampleRate ?: JSONObject.NULL)
            .put("channelCount", record.sourceDecodeChannelCount ?: JSONObject.NULL)
            .put("sourceFrameCount", record.sourceDecodeSourceFrameCount ?: JSONObject.NULL)
            .put("outputFrameCount", record.sourceDecodeOutputFrameCount ?: JSONObject.NULL)
            .put("encoderDelayFrames", record.sourceDecodeEncoderDelayFrames ?: JSONObject.NULL)
            .put(
                "encoderPaddingFrames",
                record.sourceDecodeEncoderPaddingFrames ?: JSONObject.NULL,
            )
            .put(
                "fallbackReason",
                record.sourceDecodeFallbackReason ?: JSONObject.NULL,
            )
            .put("detail", detail)
    }

    private fun decodeExpectedNullable(encoded: String): String? {
        if (encoded == EXPECTED_NULL_VALUE) return null
        return String(Base64.decode(encoded, Base64.NO_WRAP), Charsets.UTF_8)
    }

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
        val x86ProcessValidation = arguments.optionalBoolean(
            ARG_X86_PROCESS_VALIDATION,
            false,
        )
        assertEquals(x86ProcessValidation, MdxX86ProcessValidationOverride.buildEnabled)
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
                .put(
                    "expectedDecode",
                    arguments.getString(ARG_FIXTURE_EXPECTED_DECODE_MODE)?.let { mode ->
                        JSONObject()
                            .put("mode", mode)
                            .put(
                                "profile",
                                arguments.getString(ARG_FIXTURE_EXPECTED_DECODE_PROFILE_BASE64)
                                    ?.let(::decodeExpectedNullable) ?: JSONObject.NULL,
                            )
                            .put(
                                "mimeType",
                                arguments.getString(ARG_FIXTURE_EXPECTED_DECODE_MIME)
                                    ?: JSONObject.NULL,
                            )
                            .put(
                                "fallbackReason",
                                arguments.getString(ARG_FIXTURE_EXPECTED_FALLBACK_REASON_BASE64)
                                    ?.let(::decodeExpectedNullable) ?: JSONObject.NULL,
                            )
                    } ?: JSONObject.NULL,
                )
                .put(
                    "expectedOutputSampleRate",
                    arguments.getString(ARG_FIXTURE_EXPECTED_OUTPUT_SAMPLE_RATE)
                        ?.toIntOrNull() ?: JSONObject.NULL,
                )
                .put(
                    "expectedOutputFrameCount",
                    arguments.getString(ARG_FIXTURE_EXPECTED_OUTPUT_FRAMES)
                        ?.toIntOrNull() ?: JSONObject.NULL,
                )
            )
            .put("run", JSONObject()
                .put("runId", runId)
                .put("class", arguments.getString(ARG_RUN_CLASS) ?: "cold-session")
                .put("cpuThreads", resolveCpuThreads(arguments.getString(ARG_PROCESSOR_COUNT)?.toIntOrNull()))
                .put("windowDecodeEnabled", arguments.optionalBoolean(ARG_WINDOW_DECODE_ENABLED, true))
                .put("cleanInstallScenario", arguments.optionalBoolean(ARG_CLEAN_INSTALL, false))
                .put("x86ProcessValidation", x86ProcessValidation)
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

    private enum class Phase7ExecutionHostMode(val argumentValue: String) {
        InProcess("in-process"),
        BoundRemote("bound-remote"),
        ;

        companion object {
            fun parse(value: String?): Phase7ExecutionHostMode = values().singleOrNull {
                it.argumentValue == (value ?: InProcess.argumentValue)
            } ?: error("Unsupported Phase 7 execution host mode: $value")
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

    private enum class ProcessMatrixScenario(val argumentValue: String) {
        Completion("completion"),
        PauseResume("pause-resume"),
        PendingTailResume("pending-tail-resume"),
        Cancellation("cancellation"),
        ;

        companion object {
            fun forCycle(cycle: Int): ProcessMatrixScenario = when (cycle) {
                in 1..5 -> Completion
                in 6..10 -> PauseResume
                in 11..15 -> PendingTailResume
                in 16..20 -> Cancellation
                else -> error("Process matrix cycle is out of range: $cycle")
            }
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
        const val ARG_MAXIMUM_CANCELLATION_LATENCY_MS = "maximumCancellationLatencyMs"
        const val ARG_FIXTURES_VERSION = "fixturesVersion"
        const val ARG_SOURCE_PATH = "sourcePath"
        const val ARG_CURRENT_SOURCE_PATH = "currentSourcePath"
        const val ARG_BACKEND_MODE = "backendMode"
        const val ARG_EXECUTION_HOST_MODE = "executionHostMode"
        const val ARG_SCREEN_OFF_AFTER_READY = "screenOffAfterReady"
        const val ARG_AUTO_FAILPOINT = "autoFailpoint"
        const val ARG_PROCESSOR_COUNT = "processorCount"
        const val ARG_XNNPACK_FLAGS = "xnnPackFlags"
        const val ARG_WINDOW_DECODE_ENABLED = "windowDecodeEnabled"
        const val ARG_PRESERVE_MEDIA_STORE_SOURCE = "preserveMediaStoreSource"
        const val ARG_EXPORT_CACHE_AUDIO = "exportCacheAudio"
        const val ARG_RUN_CLASS = "runClass"
        const val ARG_CLEAN_INSTALL = "cleanInstallScenario"
        const val ARG_X86_PROCESS_VALIDATION = "x86ProcessValidation"
        const val ARG_REBIND_AFTER_COMPLETION = "rebindAfterCompletion"
        const val PROCESS_REBIND_SETTLE_MS = 250L
        const val PROCESS_MATRIX_CYCLE_COUNT = 20
        const val PROCESS_MODEL_SWITCH_COUNT = 20
        const val PROCESS_MATRIX_FIRST_PAUSE_CYCLE = 6
        const val PROCESS_MATRIX_MAXIMUM_PSS_GROWTH_BYTES = 64L * 1_024L * 1_024L
        const val PROCESS_MATRIX_MAXIMUM_MAP_GROWTH = 256
        const val PROCESS_MATRIX_LEASE_RELEASE_TIMEOUT_MS = 30_000L
        const val PROCESS_FAULT_RECYCLE_TIMEOUT_MS = 1L
        const val PROCESS_DEATH_TIMEOUT_MS = 10_000L
        val PROCESS_MATRIX_SNAPSHOT_CYCLES = setOf(2, 10, 20)
        const val ARG_FIXTURE_ID = "fixtureId"
        const val ARG_FIXTURE_FILE_NAME = "fixtureFileName"
        const val ARG_FIXTURE_BYTES = "fixtureBytes"
        const val ARG_FIXTURE_SHA256 = "fixtureSha256"
        const val ARG_FIXTURE_DURATION_US = "fixtureDurationUs"
        const val ARG_FIXTURE_SAMPLE_RATE = "fixtureSampleRate"
        const val ARG_FIXTURE_CHANNELS = "fixtureChannels"
        const val ARG_FIXTURE_CODEC = "fixtureCodec"
        const val ARG_FIXTURE_DECODE_CLASS = "fixtureDecodeClass"
        const val ARG_FIXTURE_EXPECTED_DECODE_MODE = "fixtureExpectedDecodeMode"
        const val ARG_FIXTURE_EXPECTED_DECODE_PROFILE_BASE64 =
            "fixtureExpectedDecodeProfileBase64"
        const val ARG_FIXTURE_EXPECTED_DECODE_MIME = "fixtureExpectedDecodeMime"
        const val ARG_FIXTURE_EXPECTED_FALLBACK_REASON_BASE64 =
            "fixtureExpectedFallbackReasonBase64"
        const val ARG_FIXTURE_EXPECTED_OUTPUT_SAMPLE_RATE = "fixtureExpectedOutputSampleRate"
        const val ARG_FIXTURE_EXPECTED_OUTPUT_FRAMES = "fixtureExpectedOutputFrames"
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
        const val EXPECTED_NULL_VALUE = "__none__"
        const val DEFAULT_MAXIMUM_CANCELLATION_LATENCY_MS = 30_000L
        const val THERMAL_SAMPLE_INTERVAL_MS = 2_000L
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
        const val PLAYBACK_CONTINUITY_POSITION_TOLERANCE_MS = 1_000L
        const val REPEAT_MODE_CYCLE_LIMIT = 3
        const val MEDIA_SCAN_TIMEOUT_MS = 30_000L
        const val MEDIA_SCAN_RETRIES = 60
        const val MEDIA_SCAN_POLL_MS = 500L
        const val BACKGROUND_SETTLE_MS = 1_000L
        const val SCREEN_STATE_TIMEOUT_MS = 5_000L
        const val SCREEN_OFF_OBSERVATION_MS = 60_000L
        const val ZERO_SHA256 = "0000000000000000000000000000000000000000000000000000000000000000"
        val SAFE_NAME = Regex("^[A-Za-z0-9._-]{1,120}$")
        val SAFE_EXTENSION = Regex("^[a-z0-9]{1,8}$")
    }
}
