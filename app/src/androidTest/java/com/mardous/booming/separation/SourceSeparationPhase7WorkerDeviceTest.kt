package com.mardous.booming.separation

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
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
import com.mardous.booming.AppProcessResolver
import com.mardous.booming.data.model.Song
import com.mardous.booming.playback.Playback
import com.mardous.booming.playback.PlaybackService
import com.mardous.booming.playback.PlaybackContentionDiagnostics
import com.mardous.booming.separation.cache.SourceSeparationSegmentState
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheFlacPromotionResult
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheFaultAction
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheFaultControl
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheFaultHit
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheFaultInjection
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheFaultStage
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheManifest
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunCoordinator
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunJournal
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunJournalRequest
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunJournalLifecycle
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunTransitionType
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheStore
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheFlacPromoter
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheManifestState
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheMutationResult
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheValidationResult
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheLockOwner
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheLockPurpose
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
import com.mardous.booming.separation.model.MdxRuntimeAbi
import com.mardous.booming.separation.model.MdxRuntimePrecision
import com.mardous.booming.separation.model.MdxRuntimeSettings
import com.mardous.booming.separation.model.MdxRuntimeSupportStatus
import com.mardous.booming.separation.model.MdxX86ProcessValidationOverride
import com.mardous.booming.separation.model.ReusableMdxInferenceSessionProvider
import com.mardous.booming.separation.model.SingleUseMdxInferenceSessionProvider
import com.mardous.booming.separation.model.AndroidMdxRuntimePlatformProvider
import com.mardous.booming.separation.model.litert.MdxLiteRtCpuInferenceSessionFactory
import com.mardous.booming.separation.model.litert.MdxLiteRtBoundedGpuContract
import com.mardous.booming.separation.model.litert.MdxLiteRtGpuRuntimeProfile
import com.mardous.booming.separation.model.litert.MdxLiteRtRemoteFailpoint
import com.mardous.booming.separation.model.litert.MdxLiteRtRemoteFaultEvidence
import com.mardous.booming.separation.model.litert.MdxLiteRtRemoteFaultInjection
import com.mardous.booming.separation.model.preset.SourceSeparationActivePresetState
import com.mardous.booming.separation.model.preset.SourceSeparationActiveModelReference
import com.mardous.booming.separation.model.preset.SourceSeparationPresetDownloader
import com.mardous.booming.separation.model.preset.SourceSeparationPresetRepository
import com.mardous.booming.separation.model.preset.SourceSeparationPresetSelectionScope
import com.mardous.booming.separation.process.SourceSeparationExecutionBackendPolicy
import com.mardous.booming.separation.process.SourceSeparationExecutionHostEvent
import com.mardous.booming.separation.process.SourceSeparationExecutionHostEventPayload
import com.mardous.booming.separation.process.SourceSeparationForegroundLeaseLifecycle
import com.mardous.booming.separation.process.SourceSeparationProcessDiagnostics
import com.mardous.booming.separation.process.SourceSeparationProcessDiagnosticsCollector
import com.mardous.booming.separation.process.SourceSeparationProcessLifecyclePolicy
import com.mardous.booming.separation.process.SourceSeparationProcessingWakeLockLeaseRecord
import com.mardous.booming.separation.process.SourceSeparationProcessingWakeLockLifecycle
import com.mardous.booming.separation.process.SourceSeparationProcessingOwnershipHandoff
import com.mardous.booming.separation.process.SourceSeparationProcessingOwnershipSnapshot
import com.mardous.booming.separation.process.SourceSeparationProcParser
import com.mardous.booming.separation.process.SourceSeparationProcessSessionState
import com.mardous.booming.separation.process.SourceSeparationArm32ResidentValidation
import com.mardous.booming.separation.process.ipc.BoundRemoteSourceSeparationExecutionHost
import com.mardous.booming.separation.process.ipc.SourceSeparationIpcRecycleReason
import com.mardous.booming.separation.process.ipc.SourceSeparationIpcErrorCategory
import com.mardous.booming.separation.process.ipc.SourceSeparationIndependentRunRecoveryClient
import com.mardous.booming.separation.process.ipc.SourceSeparationMediaProcessingForegroundController
import com.mardous.booming.separation.process.ipc.SourceSeparationRemoteExecutionException
import com.mardous.booming.separation.process.ipc.SourceSeparationRemoteConnectionState
import com.mardous.booming.separation.process.ipc.SourceSeparationRemoteRecycleTimeoutException
import com.mardous.booming.separation.process.ipc.SourceSeparationRemoteForegroundPolicy
import com.mardous.booming.separation.runtime.SourceSeparationRuntimeBootstrap
import com.mardous.booming.ui.screen.MainActivity
import com.mardous.booming.ui.screen.player.SourceSeparationForegroundWorkerCallbacks
import com.mardous.booming.ui.screen.player.SourceSeparationForegroundWorkerCoordinator
import com.mardous.booming.ui.screen.player.SourceSeparationUiState
import com.mardous.booming.ui.screen.player.SourceSeparationWorkerRequestIdentity
import com.mardous.booming.util.IGNORE_AUDIO_FOCUS
import com.mardous.booming.util.MINIMUM_SONG_DURATION
import com.mardous.booming.util.SOURCE_SEPARATION_AUTO_CACHE_CLEANUP
import com.mardous.booming.util.SOURCE_SEPARATION_AUTO_CACHE_CLEANUP_COMPLETED_LIMIT
import com.mardous.booming.util.SOURCE_SEPARATION_AUTO_CACHE_CLEANUP_PARTIAL_LIMIT
import com.mardous.booming.util.SOURCE_SEPARATION_AUTO_START
import com.mardous.booming.util.SOURCE_SEPARATION_AUTO_FLAC_COMPRESSION
import com.mardous.booming.util.SOURCE_SEPARATION_GPU_ENABLED
import com.mardous.booming.util.SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT
import com.mardous.booming.util.SOURCE_SEPARATION_WINDOW_DECODE
import com.mardous.booming.util.STOP_WHEN_CLOSED_FROM_RECENTS
import com.mardous.booming.util.putSourceSeparationGpuEnabled
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlin.math.abs

/** Runs the real foreground worker against a MediaStore-backed song. */
@RunWith(AndroidJUnit4::class)
class SourceSeparationPhase7WorkerDeviceTest {

    @Test
    fun validateBackendPolicyPreparationRecycle() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val runId = arguments.requiredString(ARG_RUN_ID).requireSafeName()
        val report = baseReport(context, runId, arguments)
        val preferences = get<SharedPreferences>(SharedPreferences::class.java)
        val preferenceKeys = setOf(
            SOURCE_SEPARATION_AUTO_FLAC_COMPRESSION,
            SOURCE_SEPARATION_WINDOW_DECODE,
            SOURCE_SEPARATION_GPU_ENABLED,
            SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT,
            MINIMUM_SONG_DURATION,
        )
        val preferenceSnapshot = snapshotPreferences(preferences, preferenceKeys)
        var mediaUri: Uri? = null
        var host: BoundRemoteSourceSeparationExecutionHost? = null
        var engine: SourceSeparationModelAwareEngine? = null
        var runtimeFacade: SourceSeparationRuntimeFacade? = null
        var cacheKey: String? = null

        try {
            val sourcePath = arguments.requiredString(ARG_SOURCE_PATH)
            mediaUri = registerSourceInMediaStore(context, sourcePath, runId)
            val source = resolveMediaStoreSong(context, mediaUri, sourcePath)
            check(preferences.edit()
                .putBoolean(SOURCE_SEPARATION_AUTO_FLAC_COMPRESSION, false)
                .putBoolean(SOURCE_SEPARATION_WINDOW_DECODE, true)
                .putSourceSeparationGpuEnabled(false)
                .putInt(SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT, 1)
                .putInt(MINIMUM_SONG_DURATION, 0)
                .commit()
            ) { "Could not configure backend-policy recycle validation." }

            val presetRepository = get<SourceSeparationPresetRepository>(
                SourceSeparationPresetRepository::class.java,
            )
            val resolver = createCpuRuntimeFacade(
                context = context,
                preferences = preferences,
                presetRepository = presetRepository,
                backendMode = BackendMode.Cpu,
                processorCount = null,
            ).also { runtimeFacade = it }
            val runtimeSong = (resolver.resolve(source) as?
                SourceSeparationRuntimeSongResolution.Ready)?.song
                ?: error("The backend-policy fixture could not be resolved.")
            cacheKey = runtimeSong.cacheKey
            clearExactCacheEntry(resolver, runtimeSong.cacheKey)

            val remoteHost = BoundRemoteSourceSeparationExecutionHost(
                context.applicationContext,
            ).also { host = it }
            val remoteEngine = SourceSeparationModelAwareEngine.createBoundRemotePrototype(
                context = context,
                presetRepository = presetRepository,
                coordinator = get(SourceSeparationCacheRunCoordinator::class.java),
                executionHost = remoteHost,
                executionBackendPolicy = SourceSeparationExecutionBackendPolicy.Cpu,
            ).also { engine = it }

            val completed = remoteEngine.separateResolved(
                input = runtimeSong.input,
                model = requireNotNull(runtimeSong.model),
                preflight = runtimeSong.preflight,
                executionBackendPolicy = SourceSeparationExecutionBackendPolicy.Cpu,
                runClass = SourceSeparationExecutionRunClass.PlaybackDemandWindow,
                windowDecodeEnabled = true,
            )
            assertTrue(completed is SourceSeparationModelAwareEngineResult.Completed)

            val before = remoteHost.processDiagnostics()
            assertEquals(
                SourceSeparationExecutionBackendPolicy.Cpu,
                before.session.backendPolicy,
            )
            assertEquals(SourceSeparationProcessSessionState.Empty, before.session.state)
            assertEquals(1, before.session.nativeSessionCreationCount)
            assertTrue(before.session.invocationCount > 0L)

            clearExactCacheEntry(resolver, runtimeSong.cacheKey)
            val autoCompleted = remoteEngine.separateResolved(
                input = runtimeSong.input,
                model = requireNotNull(runtimeSong.model),
                preflight = runtimeSong.preflight,
                executionBackendPolicy = SourceSeparationExecutionBackendPolicy.Auto,
                runClass = SourceSeparationExecutionRunClass.PlaybackDemandWindow,
                windowDecodeEnabled = true,
            )
            assertTrue(autoCompleted is SourceSeparationModelAwareEngineResult.Completed)
            val autoManifest = (autoCompleted as
                SourceSeparationModelAwareEngineResult.Completed).manifest
            assertEquals(
                MdxInferenceBackend.LiteRtGpu.name,
                autoManifest.runtimeRecords.last().backend,
            )

            val after = remoteHost.processDiagnostics()
            assertNotEquals(before.processGeneration, after.processGeneration)
            assertNotEquals(before.pid, after.pid)
            assertNotEquals(before.processStartTicks, after.processStartTicks)
            assertEquals(SourceSeparationProcessSessionState.Empty, after.session.state)
            assertEquals(
                SourceSeparationExecutionBackendPolicy.Auto,
                after.session.backendPolicy,
            )
            assertEquals(1, after.session.nativeSessionCreationCount)
            assertTrue(after.session.invocationCount > 0L)
            assertEquals(1, remoteHost.connectionDiagnostics.expectedBinderDeathCount)
            assertEquals(0, remoteHost.connectionDiagnostics.unexpectedBinderDeathCount)

            clearExactCacheEntry(resolver, runtimeSong.cacheKey)
            val finalCpuCompleted = remoteEngine.separateResolved(
                input = runtimeSong.input,
                model = requireNotNull(runtimeSong.model),
                preflight = runtimeSong.preflight,
                executionBackendPolicy = SourceSeparationExecutionBackendPolicy.Cpu,
                runClass = SourceSeparationExecutionRunClass.PlaybackDemandWindow,
                windowDecodeEnabled = true,
            )
            assertTrue(finalCpuCompleted is SourceSeparationModelAwareEngineResult.Completed)
            val finalCpuManifest = (finalCpuCompleted as
                SourceSeparationModelAwareEngineResult.Completed).manifest
            assertEquals(
                MdxInferenceBackend.LiteRtCpu.name,
                finalCpuManifest.runtimeRecords.last().backend,
            )
            val finalCpu = remoteHost.processDiagnostics()
            assertNotEquals(after.processGeneration, finalCpu.processGeneration)
            assertNotEquals(after.pid, finalCpu.pid)
            assertNotEquals(after.processStartTicks, finalCpu.processStartTicks)
            assertEquals(SourceSeparationProcessSessionState.Empty, finalCpu.session.state)
            assertEquals(SourceSeparationExecutionBackendPolicy.Cpu, finalCpu.session.backendPolicy)
            assertEquals(1, finalCpu.session.nativeSessionCreationCount)
            assertTrue(finalCpu.session.invocationCount > 0L)
            assertEquals(2, remoteHost.connectionDiagnostics.expectedBinderDeathCount)
            assertEquals(0, remoteHost.connectionDiagnostics.unexpectedBinderDeathCount)
            applyRuntimeEvidence(report, autoManifest)
            report.put("status", "passed")
            report.put("backendSwitch", JSONObject()
                .put("initialBackend", MdxInferenceBackend.LiteRtCpu.name)
                .put("gpuBackend", MdxInferenceBackend.LiteRtGpu.name)
                .put("finalBackend", MdxInferenceBackend.LiteRtCpu.name)
                .put("initialProcessGeneration", before.processGeneration)
                .put("gpuProcessGeneration", after.processGeneration)
                .put("initialPid", before.pid)
                .put("gpuPid", after.pid)
                .put("finalCpuProcessGeneration", finalCpu.processGeneration)
                .put("finalCpuPid", finalCpu.pid)
                .put("expectedBinderDeathCount",
                    remoteHost.connectionDiagnostics.expectedBinderDeathCount)
                .put("unexpectedBinderDeathCount",
                    remoteHost.connectionDiagnostics.unexpectedBinderDeathCount)
            )
        } catch (error: Throwable) {
            report.put("status", "failed")
            report.put("error", "${error::class.java.name}: ${error.message}")
            throw error
        } finally {
            engine?.close()
            host?.close()
            cacheKey?.let { key ->
                runtimeFacade?.let { runtime ->
                    runCatching { clearExactCacheEntry(runtime, key) }
                }
            }
            mediaUri?.let { uri ->
                runCatching { context.contentResolver.delete(uri, null, null) }
            }
            restorePreferences(preferences, preferenceSnapshot)
            writeReport(context, runId, "backend-switching", report)
        }
    }

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
        val autoFailpoint = Phase7AutoFailpoint.parse(arguments.getString(ARG_AUTO_FAILPOINT))
        val gpuRuntimeProfile = arguments.getString(ARG_GPU_RUNTIME_PROFILE_ID)?.let { profileId ->
            MdxLiteRtGpuRuntimeProfile.find(profileId)
                ?: error("Unsupported Phase 7 GPU runtime profile: $profileId")
        }
        val remoteAutoFailpoint = MdxLiteRtRemoteFailpoint.parse(
            arguments.getString(ARG_REMOTE_AUTO_FAILPOINT),
        )
        val remoteFaultInvocationCount = arguments.optionalInt(
            ARG_AUTO_FAIL_INVOCATION_COUNT,
            DEFAULT_REMOTE_FAULT_INVOCATION_COUNT,
        )
        val remoteFaultToken = arguments.getString(ARG_REMOTE_FAULT_TOKEN)
            ?: "$runId-remote-gpu"
        val karaGpuRequalification = arguments.optionalBoolean(
            ARG_KARA_GPU_REQUALIFICATION,
            false,
        )
        require(autoFailpoint == Phase7AutoFailpoint.None || backendMode == BackendMode.Auto) {
            "Phase 7 Auto fault injection requires BackendMode=auto."
        }
        require(
            gpuRuntimeProfile == null ||
                (backendMode == BackendMode.Auto &&
                    executionHostMode == Phase7ExecutionHostMode.InProcess &&
                    autoFailpoint == Phase7AutoFailpoint.None)
        ) {
            "A diagnostic GPU profile requires in-process Auto without fault injection."
        }
        require(remoteAutoFailpoint == MdxLiteRtRemoteFailpoint.None ||
            (backendMode == BackendMode.Auto &&
                executionHostMode == Phase7ExecutionHostMode.BoundRemote &&
                autoFailpoint == Phase7AutoFailpoint.None)
        ) {
            "Remote Auto fault injection requires BoundRemote Auto without a local failpoint."
        }
        require(!karaGpuRequalification ||
            (backendMode == BackendMode.Auto &&
                executionHostMode == Phase7ExecutionHostMode.InProcess &&
                autoFailpoint == Phase7AutoFailpoint.None &&
                remoteAutoFailpoint == MdxLiteRtRemoteFailpoint.None &&
                gpuRuntimeProfile == MdxLiteRtGpuRuntimeProfile.BoundedOpenClFp32V1 &&
                arguments.requiredString(ARG_PROCESS_ABI) == "arm64-v8a" &&
                arguments.requiredString(ARG_MODEL_ID) == KARA_MODEL_ID &&
                arguments.requiredString(ARG_ARTIFACT_SHA256) == KARA_ARTIFACT_SHA256 &&
                arguments.requiredString(ARG_CONTRACT_ID) == KARA_CONTRACT_ID)
        ) {
            "KARA GPU requalification requires the pinned arm64 bounded-FP32 worker."
        }
        val report = baseReport(context, runId, arguments)
        if (autoFailpoint != Phase7AutoFailpoint.None ||
            remoteAutoFailpoint != MdxLiteRtRemoteFailpoint.None ||
            karaGpuRequalification
        ) {
            report.put("diagnosticOnly", true)
        }
        if (karaGpuRequalification) {
            report.put(
                "qualificationOverride",
                JSONObject()
                    .put("modelId", KARA_MODEL_ID)
                    .put("contractId", KARA_CONTRACT_ID)
                    .put("artifactSha256", KARA_ARTIFACT_SHA256)
                    .put("profileId", MdxLiteRtGpuRuntimeProfile.BoundedOpenClFp32V1.profileId)
                    .put("catalogStatus", "rejected")
                    .put("effectiveStatus", "candidate")
                    .put("scope", "androidTest-only"),
            )
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
        val probeOriginalPlayback = arguments.optionalBoolean(
            ARG_PROBE_ORIGINAL_PLAYBACK,
            false,
        )
        val cleanupCacheAfterRun = arguments.optionalBoolean(
            ARG_CLEANUP_CACHE_AFTER_RUN,
            false,
        )
        val screenOffAfterReady = arguments.optionalBoolean(
            ARG_SCREEN_OFF_AFTER_READY,
            false,
        )
        require(!screenOffAfterReady ||
            executionHostMode == Phase7ExecutionHostMode.IndependentForeground
        ) {
            "Worker screen-off validation requires independent foreground execution."
        }
        require(!rebindAfterCompletion ||
            executionHostMode == Phase7ExecutionHostMode.BoundRemote
        ) {
            "Process-retention validation requires the bound-remote host."
        }
        var coordinator: SourceSeparationForegroundWorkerCoordinator? = null
        var boundRemoteHost: BoundRemoteSourceSeparationExecutionHost? = null
        var originalPlayback: OriginalAudioPlaybackProbe? = null
        var mediaUri: Uri? = null
        var screenOffIssued = false
        var runtimeFacadeForCleanup: SourceSeparationRuntimeFacade? = null
        var cacheKeyForCleanup: String? = null
        val preferences = get<SharedPreferences>(SharedPreferences::class.java)
        val preferenceSnapshot = snapshotPreferences(
            preferences,
            setOf(
                SOURCE_SEPARATION_WINDOW_DECODE,
                SOURCE_SEPARATION_AUTO_FLAC_COMPRESSION,
                SOURCE_SEPARATION_GPU_ENABLED,
                SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT,
                MINIMUM_SONG_DURATION,
            ),
        )
        val hostEvents = Collections.synchronizedList(
            mutableListOf<SourceSeparationExecutionHostEvent>(),
        )

        try {
            val sourcePath = arguments.requiredString(ARG_SOURCE_PATH)
            val registeredUri = registerSourceInMediaStore(context, sourcePath, runId)
            mediaUri = registeredUri
            val source = resolveMediaStoreSong(context, registeredUri, sourcePath)
            val windowDecodeEnabled = arguments.optionalBoolean(ARG_WINDOW_DECODE_ENABLED, true)
            val preferencesEditor = preferences.edit()
                .putBoolean(SOURCE_SEPARATION_WINDOW_DECODE, windowDecodeEnabled)
                .putBoolean(SOURCE_SEPARATION_AUTO_FLAC_COMPRESSION, false)
                .putSourceSeparationGpuEnabled(backendMode == BackendMode.Auto)
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

            if (remoteAutoFailpoint != MdxLiteRtRemoteFailpoint.None) {
                MdxLiteRtRemoteFaultInjection.arm(
                    context = context,
                    token = remoteFaultToken,
                    failpoint = remoteAutoFailpoint,
                    failureInvocationCount = remoteFaultInvocationCount,
                )
            }

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
                sessionProviderFactoryOverride = when {
                    autoFaultController != null ->
                        autoFaultController.createSessionProviderFactory(context)
                    gpuRuntimeProfile != null -> ({
                        if (karaGpuRequalification) {
                            createKaraGpuRequalificationSessionProvider(
                                context,
                                gpuRuntimeProfile,
                            )
                        } else {
                            createAutoLiteRtSessionProvider(context, gpuRuntimeProfile)
                        }
                    })
                    else -> null
                },
                executionHostMode = executionHostMode,
                executionHostEventSink = hostEvents::add,
                boundRemoteHostSink = { boundRemoteHost = it },
            ).also { runtimeFacadeForCleanup = it }
            val remoteStartup = boundRemoteHost?.let { host ->
                host.processGeneration
                host.connectionDiagnostics
            }
            val settledRemotePssBytes = remoteStartup?.pid?.let { pid ->
                SystemClock.sleep(REMOTE_IDLE_SETTLE_MS)
                processPssBytes(context, pid).also { pssBytes ->
                    assertTrue("Settled remote PSS was unavailable.", pssBytes > 0L)
                }
            }
            val store = get<SourceSeparationCacheStore>(SourceSeparationCacheStore::class.java)
            val resolved = runtimeFacade.resolve(source)
            val runtimeSong = (resolved as? SourceSeparationRuntimeSongResolution.Ready)?.song
                ?: error("The scanned song could not be admitted: $resolved")
            val cacheKey = runtimeSong.cacheKey
            cacheKeyForCleanup = cacheKey
            val entriesBefore = runtimeFacade.entries().size
            runtimeFacade.entries()
                .filter { it.cacheKey == cacheKey }
                .forEach { entry ->
                    assertEquals(SourceSeparationCacheMutationResult.Completed,
                        runtimeFacade.delete(entry.cacheKey))
                }
            assertTrue(runtimeFacade.cacheStatus(runtimeSong) is SourceSeparationModelAwareCacheStatus.Missing)
            val playbackProbe = if (probeOriginalPlayback) {
                startOriginalAudioPlayback(
                    context = context,
                    source = source,
                    operation = "paired host worker playback",
                ).also { originalPlayback = it }
            } else {
                null
            }
            val remoteProcessBefore = boundRemoteHost?.processDiagnostics()
            val idleMemory = memorySnapshot(context)
            val mainProcessBefore = currentProcessDiagnostics()

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
            playbackProbe?.assertContinuous("before-worker")
            assertTrue(worker.startCurrentSong())

            val firstReadyAt = AtomicLong(0L)
            var peakPssBytes = idleMemory.getLong("totalPssBytes")
            var peakJavaBytes = idleMemory.getLong("javaPssBytes")
            var peakNativeBytes = idleMemory.getLong("nativePssBytes")
            var peakGraphicsBytes = idleMemory.getLong("graphicsPssBytes")
            var currentRemotePssBytes = maxOf(
                remoteStartup?.idlePssBytes ?: 0L,
                remoteProcessBefore?.memory?.pssBytes ?: 0L,
            )
            var peakRemotePssBytes = currentRemotePssBytes
            var peakSummedPssBytes = idleMemory.getLong("totalPssBytes") +
                currentRemotePssBytes
            var peakUssBytes = idleMemory.getLong("totalUssBytes")
            var peakRssBytes = idleMemory.getLong("vmRssBytes")
            var peakRemoteUssBytes = remoteProcessBefore?.memory?.ussBytes ?: 0L
            var peakRemoteRssBytes = remoteProcessBefore?.memory?.vmRssBytes ?: 0L
            var peakRemoteJavaBytes = remoteProcessBefore?.memory?.javaPssBytes ?: 0L
            var peakRemoteNativeBytes = remoteProcessBefore?.memory?.nativePssBytes ?: 0L
            var peakRemoteGraphicsBytes = remoteProcessBefore?.memory?.graphicsPssBytes ?: 0L
            var currentRemoteUssBytes = peakRemoteUssBytes
            var currentRemoteRssBytes = peakRemoteRssBytes
            var peakSummedUssBytes = peakUssBytes + peakRemoteUssBytes
            var peakSummedRssBytes = peakRssBytes + peakRemoteRssBytes
            var minimumLargestFreeAddressGapBytes = remoteProcessBefore?.memory
                ?.largestFreeAddressGapBytes
            var lastRemoteResourceSampleAt = Long.MIN_VALUE
            var lastPlaybackSampleAt = workerStartedAt
            var firstReadyPlaybackChecked = false
            var decodeMode: String? = null
            var decodeDiagnostics: String? = null
            var finalState: SourceSeparationUiState = SourceSeparationUiState.Idle
            var remoteCacheOwnershipChecked = false
            var backgroundLifecycleStarted = false
            var screenOffObserved = false
            var completedWhileScreenOff = false
            var eventsAdvancedWhileScreenOff = false
            var journalAdvancedWhileScreenOff = false
            var eventsBeforeScreenOff = 0
            var journalSequenceBeforeScreenOff = 0L
            var importanceBeforeHome: Int? = null
            var importanceAfterHome: Int? = null
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
                peakUssBytes = maxOf(peakUssBytes, memory.getLong("totalUssBytes"))
                peakRssBytes = maxOf(peakRssBytes, memory.getLong("vmRssBytes"))
                val remotePssBytes = boundRemoteHost?.connectionDiagnostics?.pid
                    ?.let { processPssBytes(context, it) }
                    ?: 0L
                currentRemotePssBytes = remotePssBytes
                peakRemotePssBytes = maxOf(peakRemotePssBytes, remotePssBytes)
                val activeRemoteHost = boundRemoteHost
                if (activeRemoteHost != null &&
                    (lastRemoteResourceSampleAt == Long.MIN_VALUE ||
                        now - lastRemoteResourceSampleAt >= PROCESS_RESOURCE_SAMPLE_INTERVAL_MS)
                ) {
                    runCatching { activeRemoteHost.processDiagnostics() }.getOrNull()?.let {
                        remote ->
                        currentRemotePssBytes = remote.memory.pssBytes
                        peakRemotePssBytes = maxOf(
                            peakRemotePssBytes,
                            remote.memory.pssBytes,
                        )
                        peakRemoteUssBytes = maxOf(peakRemoteUssBytes, remote.memory.ussBytes)
                        currentRemoteUssBytes = remote.memory.ussBytes
                        peakRemoteRssBytes = maxOf(
                            peakRemoteRssBytes,
                            remote.memory.vmRssBytes ?: 0L,
                        )
                        currentRemoteRssBytes = remote.memory.vmRssBytes ?: 0L
                        peakRemoteJavaBytes = maxOf(
                            peakRemoteJavaBytes,
                            remote.memory.javaPssBytes,
                        )
                        peakRemoteNativeBytes = maxOf(
                            peakRemoteNativeBytes,
                            remote.memory.nativePssBytes,
                        )
                        peakRemoteGraphicsBytes = maxOf(
                            peakRemoteGraphicsBytes,
                            remote.memory.graphicsPssBytes,
                        )
                        remote.memory.largestFreeAddressGapBytes?.let { gap ->
                            minimumLargestFreeAddressGapBytes =
                                minimumLargestFreeAddressGapBytes?.let { minOf(it, gap) } ?: gap
                        }
                    }
                    lastRemoteResourceSampleAt = now
                }
                peakSummedPssBytes = maxOf(
                    peakSummedPssBytes,
                    memory.getLong("totalPssBytes") + currentRemotePssBytes,
                )
                peakSummedUssBytes = maxOf(
                    peakSummedUssBytes,
                    memory.getLong("totalUssBytes") + currentRemoteUssBytes,
                )
                peakSummedRssBytes = maxOf(
                    peakSummedRssBytes,
                    memory.getLong("vmRssBytes") + currentRemoteRssBytes,
                )
                playbackProbe?.let { playback ->
                    if (firstReadyAt.get() > 0L && !firstReadyPlaybackChecked) {
                        playback.assertContinuous("first-ready")
                        firstReadyPlaybackChecked = true
                        lastPlaybackSampleAt = now
                    } else if (now - lastPlaybackSampleAt >= PLAYBACK_RESOURCE_SAMPLE_INTERVAL_MS) {
                        playback.assertContinuous("worker-${now - workerStartedAt}ms")
                        lastPlaybackSampleAt = now
                    }
                }
                if (!remoteCacheOwnershipChecked && remoteStartup != null) {
                    store.readRunJournal(cacheKey)
                        ?.takeIf { it.lifecycle == SourceSeparationCacheRunJournalLifecycle.Running }
                        ?.let { journal ->
                            assertEquals(remoteStartup.pid, journal.request.ownerPid)
                            assertEquals(
                                remoteStartup.processGeneration,
                                journal.request.processGeneration,
                            )
                            assertNotEquals(Process.myPid(), journal.request.ownerPid)
                            assertEquals(
                                SourceSeparationCacheMutationResult.Busy,
                                runtimeFacade.delete(cacheKey),
                            )
                            remoteCacheOwnershipChecked = true
                        }
                }
                if (screenOffAfterReady && firstReadyAt.get() > 0L &&
                    remoteCacheOwnershipChecked && !backgroundLifecycleStarted
                ) {
                    val host = requireNotNull(boundRemoteHost)
                    val process = host.processDiagnostics()
                    val foregroundLease = requireNotNull(
                        process.foregroundService.activeLease,
                    ) { "Independent worker has no active foreground lease." }
                    val wakeLockLease = requireNotNull(
                        process.processingWakeLock.activeLease,
                    ) { "Independent worker has no active processing wake lock." }
                    val journal = requireNotNull(store.readRunJournal(cacheKey))
                    assertEquals(
                        SourceSeparationForegroundLeaseLifecycle.Active,
                        foregroundLease.lifecycle,
                    )
                    assertEquals(
                        SourceSeparationProcessingWakeLockLifecycle.Held,
                        wakeLockLease.lifecycle,
                    )
                    assertTrue(process.processingWakeLock.platformHeld)
                    assertEquals(foregroundLease.request, wakeLockLease.request)
                    assertEquals(journal.request.runId, foregroundLease.request.runId)
                    assertEquals(
                        journal.request.processGeneration,
                        foregroundLease.request.processGeneration,
                    )
                    assertEquals(process.pid, journal.request.ownerPid)
                    eventsBeforeScreenOff = synchronized(hostEvents) { hostEvents.size }
                    journalSequenceBeforeScreenOff = journal.latestSequence
                    importanceBeforeHome = currentProcessImportance()
                    instrumentation.uiAutomation
                        .executeShellCommand("input keyevent KEYCODE_HOME")
                        .close()
                    SystemClock.sleep(BACKGROUND_SETTLE_MS)
                    importanceAfterHome = currentProcessImportance()
                    instrumentation.uiAutomation
                        .executeShellCommand("input keyevent KEYCODE_SLEEP")
                        .close()
                    screenOffIssued = true
                    val powerManager = context.getSystemService(PowerManager::class.java)
                    val screenOffDeadline = SystemClock.elapsedRealtime() +
                        SCREEN_STATE_TIMEOUT_MS
                    while (powerManager.isInteractive &&
                        SystemClock.elapsedRealtime() < screenOffDeadline
                    ) {
                        SystemClock.sleep(POLL_INTERVAL_MS)
                    }
                    screenOffObserved = !powerManager.isInteractive
                    assertTrue("The device did not enter screen-off state.", screenOffObserved)
                    backgroundLifecycleStarted = true
                }
                if (screenOffIssued) {
                    eventsAdvancedWhileScreenOff = eventsAdvancedWhileScreenOff ||
                        synchronized(hostEvents) { hostEvents.size > eventsBeforeScreenOff }
                    journalAdvancedWhileScreenOff = journalAdvancedWhileScreenOff ||
                        (store.readRunJournal(cacheKey)?.latestSequence ?: 0L) >
                        journalSequenceBeforeScreenOff
                }
                when (finalState) {
                    is SourceSeparationUiState.Completed,
                    is SourceSeparationUiState.Failed,
                    is SourceSeparationUiState.Canceled -> break
                    else -> SystemClock.sleep(POLL_INTERVAL_MS)
                }
            }
            if (remoteAutoFailpoint.requiresProcessRecycle) {
                assertTrue(
                    "Fatal remote GPU failure did not fail the worker: ${worker.debugStatus()}",
                    finalState is SourceSeparationUiState.Failed,
                )
                val failedAt = SystemClock.elapsedRealtime()
                thermalSampler.sample(failedAt, force = true)
                playbackProbe?.assertContinuous("fatal-remote-gpu-failure")
                val host = requireNotNull(boundRemoteHost)
                val poisonedProcess = host.processDiagnostics()
                assertEquals(
                    SourceSeparationProcessSessionState.Poisoned,
                    poisonedProcess.session.state,
                )
                assertTrue(poisonedProcess.session.poisoned)
                assertEquals(0, poisonedProcess.session.activeLeaseCount)
                assertEquals(1, poisonedProcess.session.nativeSessionCreationCount)
                val evidence = requireNotNull(
                    MdxLiteRtRemoteFaultInjection.readEvidence(context),
                )
                validateRemoteFaultEvidence(
                    report = report,
                    evidence = evidence,
                    expectedToken = remoteFaultToken,
                    expectedFailpoint = remoteAutoFailpoint,
                    expectedPid = poisonedProcess.pid,
                    expectedFallbackStage = null,
                    expectedBackend = null,
                    firstReadyAtElapsedMs = firstReadyAt.get(),
                )
                assertEquals(0, evidence.cpuCreateCount)
                assertEquals(0, evidence.cpuCloseCount)
                assertTrue(runtimeFacade.openCompletedCache(cacheKey) == null)
                MdxLiteRtRemoteFaultInjection.clear(context)
                val recycle = host.recycle(
                    SourceSeparationIpcRecycleReason.PoisonedSession,
                    "phase5-gpu-cleanup-$runId",
                )
                assertNotEquals(
                    recycle.oldProcess.processGeneration,
                    recycle.newProcess.processGeneration,
                )
                report.put("status", "passed")
                report.getJSONObject("run")
                    .put("backendUsed", "terminal-before-cpu")
                    .put(
                        "fallbackStage",
                        remoteAutoFailpoint.expectedFallbackStage?.name ?: JSONObject.NULL,
                    )
                    .put("fallbackReason", "Injected remote GPU cleanup failure.")
                report.put("timing", report.getJSONObject("timing")
                    .put("firstReadyMs", firstReadyAt.get().takeIf { it > 0L }
                        ?.minus(workerStartedAt) ?: 0L)
                    .put("fullSongMs", failedAt - workerStartedAt)
                )
                report.put("thermal", thermalSampler.toJson())
                report.put("cache", report.getJSONObject("cache")
                    .put("cacheKey", cacheKey)
                    .put("completedPlayable", false)
                    .put("remoteOwnershipChecked", remoteCacheOwnershipChecked)
                )
                report.put("executionHost", JSONObject()
                    .put("mode", executionHostMode.argumentValue)
                    .put("failedProcess", processResourceJson(poisonedProcess))
                    .put("failedSessionState", poisonedProcess.session.state.name)
                    .put("recycleReason", recycle.reason.name)
                    .put("oldProcessGeneration", recycle.oldProcess.processGeneration)
                    .put("newProcessGeneration", recycle.newProcess.processGeneration)
                    .put("expectedBinderDeath", recycle.binderDeath.expected)
                    .put("events", JSONArray(synchronized(hostEvents) {
                        hostEvents.map { it.payload::class.java.simpleName }
                    }))
                )
                report.put("processResources", JSONObject()
                    .put("mainBefore", processResourceJson(mainProcessBefore))
                    .put("mainAfter", processResourceJson(currentProcessDiagnostics()))
                    .put("remoteBefore", remoteProcessBefore?.let(::processResourceJson)
                        ?: JSONObject.NULL)
                    .put("remoteAfterFailure", processResourceJson(poisonedProcess))
                    .put("remoteAfterRecycle", processResourceJson(recycle.newProcess))
                    .put("instrumentationSharesMainProcess", true)
                )
                return
            }
            assertTrue("Worker timed out: ${worker.debugStatus()}",
                finalState is SourceSeparationUiState.Completed)
            completedWhileScreenOff = screenOffIssued &&
                !context.getSystemService(PowerManager::class.java).isInteractive
            if (screenOffIssued) {
                instrumentation.uiAutomation
                    .executeShellCommand("input keyevent KEYCODE_WAKEUP")
                    .close()
                instrumentation.uiAutomation
                    .executeShellCommand("wm dismiss-keyguard")
                    .close()
                screenOffIssued = false
            }
            if (executionHostMode.isRemote) {
                assertTrue(
                    "The remote writer never exposed a process-owned cache lease.",
                    remoteCacheOwnershipChecked,
                )
            }
            assertTrue(
                "The worker did not publish a completion callback.",
                runCallbacks.completedCacheKey.get() == cacheKey,
            )
            val completedAt = SystemClock.elapsedRealtime()
            thermalSampler.sample(completedAt, force = true)
            playbackProbe?.assertContinuous("completed")
            val processCpuMs = (Process.getElapsedCpuTime() - workerStartedCpuMs)
                .coerceAtLeast(0L)
            val firstReadyMs = firstReadyAt.get().takeIf { it > 0L }
                ?.minus(workerStartedAt)
                ?: 0L
            val fullSongMs = completedAt - workerStartedAt
            val mainProcessAfter = currentProcessDiagnostics()
            val remoteProcessAfter = boundRemoteHost?.processDiagnostics()
            if (executionHostMode == Phase7ExecutionHostMode.IndependentForeground) {
                val completedProcess = requireNotNull(remoteProcessAfter)
                assertNull(completedProcess.foregroundService.activeLease)
                assertNull(completedProcess.processingWakeLock.activeLease)
                assertFalse(completedProcess.processingWakeLock.platformHeld)
                val foregroundLease = requireNotNull(
                    completedProcess.foregroundService.lastStoppedLease,
                )
                val wakeLockLease = requireNotNull(
                    completedProcess.processingWakeLock.lastReleasedLease,
                )
                assertEquals(foregroundLease.request, wakeLockLease.request)
                assertEquals("completed", foregroundLease.stopReason)
                assertEquals("completed", wakeLockLease.releaseReason)
            }
            peakPssBytes = maxOf(peakPssBytes, mainProcessAfter.memory.pssBytes)
            peakJavaBytes = maxOf(peakJavaBytes, mainProcessAfter.memory.javaPssBytes)
            peakNativeBytes = maxOf(peakNativeBytes, mainProcessAfter.memory.nativePssBytes)
            peakGraphicsBytes = maxOf(
                peakGraphicsBytes,
                mainProcessAfter.memory.graphicsPssBytes,
            )
            peakUssBytes = maxOf(peakUssBytes, mainProcessAfter.memory.ussBytes)
            peakRssBytes = maxOf(
                peakRssBytes,
                mainProcessAfter.memory.vmRssBytes ?: 0L,
            )
            remoteProcessAfter?.memory?.let { remoteMemory ->
                peakRemotePssBytes = maxOf(peakRemotePssBytes, remoteMemory.pssBytes)
                peakRemoteUssBytes = maxOf(peakRemoteUssBytes, remoteMemory.ussBytes)
                peakRemoteRssBytes = maxOf(
                    peakRemoteRssBytes,
                    remoteMemory.vmRssBytes ?: 0L,
                )
                peakRemoteJavaBytes = maxOf(
                    peakRemoteJavaBytes,
                    remoteMemory.javaPssBytes,
                )
                peakRemoteNativeBytes = maxOf(
                    peakRemoteNativeBytes,
                    remoteMemory.nativePssBytes,
                )
                peakRemoteGraphicsBytes = maxOf(
                    peakRemoteGraphicsBytes,
                    remoteMemory.graphicsPssBytes,
                )
                minimumLargestFreeAddressGapBytes = remoteMemory
                    .largestFreeAddressGapBytes
                    ?.let { gap ->
                        minimumLargestFreeAddressGapBytes?.let { minOf(it, gap) } ?: gap
                    }
                peakSummedPssBytes = maxOf(
                    peakSummedPssBytes,
                    mainProcessAfter.memory.pssBytes + remoteMemory.pssBytes,
                )
                peakSummedUssBytes = maxOf(
                    peakSummedUssBytes,
                    mainProcessAfter.memory.ussBytes + remoteMemory.ussBytes,
                )
                peakSummedRssBytes = maxOf(
                    peakSummedRssBytes,
                    (mainProcessAfter.memory.vmRssBytes ?: 0L) +
                        (remoteMemory.vmRssBytes ?: 0L),
                )
            }
            val remoteProcessCpuMs = if (remoteProcessBefore != null && remoteProcessAfter != null &&
                remoteProcessBefore.pid == remoteProcessAfter.pid
            ) {
                (remoteProcessAfter.memory.processCpuTimeMs -
                    remoteProcessBefore.memory.processCpuTimeMs).coerceAtLeast(0L)
            } else {
                0L
            }

            val entries = runtimeFacade.entries()
            val completedEntry = entries.singleOrNull { it.cacheKey == cacheKey }
                ?: error("Completed cache entry was not indexed: $cacheKey")
            assertEquals(SourceSeparationModelAwareCacheEntryState.Completed, completedEntry.state)
            assertEquals(expectedModelId, completedEntry.modelId)
            assertEquals(expectedArtifactSha256, completedEntry.artifactSha256)
            val playback = requireNotNull(runtimeFacade.openCompletedCache(cacheKey))
            val manifest = playback.manifest
            val output = requireNotNull(manifest.output)
            val entryDirectory = store.entryDirectory(cacheKey)
            val wavStemPaths = output.stems.associate { stem ->
                stem.semanticId.value to store.resolveRelativePath(entryDirectory, stem.wavPath)
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
            if (remoteAutoFailpoint != MdxLiteRtRemoteFailpoint.None) {
                validateRemoteFaultEvidence(
                    report = report,
                    evidence = requireNotNull(
                        MdxLiteRtRemoteFaultInjection.readEvidence(context),
                    ),
                    expectedToken = remoteFaultToken,
                    expectedFailpoint = remoteAutoFailpoint,
                    expectedPid = requireNotNull(remoteProcessAfter).pid,
                    expectedFallbackStage = runtimeRecord.fallbackStage,
                    expectedBackend = runtimeRecord.backend,
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
            assertEquals(
                output.stems.map { stem -> stem.semanticId.value },
                promotedPlayback.stemIds,
            )
            assertTrue(promotedPlayback.stemFiles.all { stem ->
                stem.isFile && stem.extension.equals("flac", ignoreCase = true)
            })
            promotedPlayback.close()

            val completedAfter = runtimeFacade.entries().single { it.cacheKey == cacheKey }
            val promotedManifest = requireNotNull(store.readManifest(cacheKey))
            val runJournal = requireNotNull(store.readRunJournal(cacheKey))
            assertEquals(SourceSeparationCacheRunJournalLifecycle.Completed, runJournal.lifecycle)
            assertEquals(
                SourceSeparationCacheRunTransitionType.Completed,
                runJournal.transitions.last().type,
            )
            applyRunAdmissionEvidence(
                report = report,
                request = runJournal.request,
                backendMode = backendMode,
                requestedGpuRuntimeProfile = gpuRuntimeProfile,
            )
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
                .put(
                    "settledIdleRemotePssBytes",
                    settledRemotePssBytes ?: JSONObject.NULL,
                )
                .put(
                    "remoteIdleSettleMs",
                    if (remoteStartup != null) REMOTE_IDLE_SETTLE_MS else 0L,
                )
                .put("peakRemotePssBytes", peakRemotePssBytes)
                .put("peakSummedPssBytes", peakSummedPssBytes)
                .put("peakUssBytes", peakUssBytes)
                .put("peakRssBytes", peakRssBytes)
                .put("peakRemoteUssBytes", peakRemoteUssBytes)
                .put("peakRemoteRssBytes", peakRemoteRssBytes)
                .put("peakRemoteJavaBytes", peakRemoteJavaBytes)
                .put("peakRemoteNativeBytes", peakRemoteNativeBytes)
                .put("peakRemoteGraphicsBytes", peakRemoteGraphicsBytes)
                .put("peakSummedUssBytes", peakSummedUssBytes)
                .put("peakSummedRssBytes", peakSummedRssBytes)
                .put(
                    "minimumLargestFreeAddressGapBytes",
                    minimumLargestFreeAddressGapBytes ?: JSONObject.NULL,
                )
            )
            report.put("thermal", thermalSampler.toJson())
            report.put("lifecycle", report.getJSONObject("lifecycle")
                .put("workerCompleted", true)
                .put("homeCommandIssuedAfterReady", backgroundLifecycleStarted)
                .put("screenOffRequested", screenOffAfterReady)
                .put("screenOffObserved", screenOffObserved)
                .put("completedWhileScreenOff", completedWhileScreenOff)
                .put("eventsAdvancedWhileScreenOff", eventsAdvancedWhileScreenOff)
                .put("journalAdvancedWhileScreenOff", journalAdvancedWhileScreenOff)
                .put("processImportanceBeforeHome",
                    importanceBeforeHome ?: JSONObject.NULL)
                .put("processImportanceAfterHome",
                    importanceAfterHome ?: JSONObject.NULL)
            )
            if (screenOffAfterReady) {
                assertTrue(eventsAdvancedWhileScreenOff)
                assertTrue(journalAdvancedWhileScreenOff)
                assertTrue(completedWhileScreenOff)
            }
            report.put("audio", report.getJSONObject("audio")
                .put("finite", true)
                .put("outputFrameCount", output.outputFrameCount)
                .put("expectedFrameCount", expectedFrames)
                .put("frameDelta", frameDelta)
                .put("maxAbsError", 0.0)
                .put("stemSemantics", output.stems.joinToString(",") {
                    it.semanticId.value
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
                .put("runJournalSequence", runJournal.latestSequence)
                .put("runJournalSchemaVersion", runJournal.journalSchemaVersion)
                .put("runJournalOwnerPid", runJournal.request.ownerPid ?: JSONObject.NULL)
                .put("runJournalProcessGeneration", runJournal.request.processGeneration)
                .put("runClass", runJournal.request.runClass.name)
                .put("backgroundPolicy", runJournal.request.backgroundPolicy.name)
                .put("remoteOwnershipChecked", remoteCacheOwnershipChecked)
                .put("manifestPathRelative", "entries/$cacheKey/manifest.json")
                .put("entryDirectoryPath", entryDirectory.absolutePath)
                .put("promotedFormat", completedAfter.format.name)
                .put("stems", JSONArray(promotedManifest.output!!.stems.map { stem ->
                    val exportedStem = artifactExport?.stems?.get(stem.semanticId.value)
                    val wavIntegrity = requireNotNull(stem.wavIntegrity)
                    val promotedIntegrity = requireNotNull(stem.promotedIntegrity)
                    JSONObject()
                        .put("semantic", stem.semanticId.value)
                        .put("wavPath", wavStemPaths.getValue(stem.semanticId.value).absolutePath)
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
                    processDiagnosticsOverride = remoteProcessAfter,
                ),
            )
            report.put("processResources", JSONObject()
                .put("mainBefore", processResourceJson(mainProcessBefore))
                .put("mainAfter", processResourceJson(mainProcessAfter))
                .put(
                    "remoteBefore",
                    remoteProcessBefore?.let(::processResourceJson) ?: JSONObject.NULL,
                )
                .put(
                    "remoteAfter",
                    remoteProcessAfter?.let(::processResourceJson) ?: JSONObject.NULL,
                )
                .put("mainProcessCpuMs", processCpuMs)
                .put("remoteProcessCpuMs", remoteProcessCpuMs)
                .put("instrumentationSharesMainProcess", true)
            )
            playbackProbe?.let { report.put("originalPlayback", it.report()) }
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
                .put("remoteAutoFailpoint", remoteAutoFailpoint.argumentValue)
                .put("runtimeDiagnostics", manifest.runtimeRecords.map { it.backend + "/" + it.runtimeProfileId }
                    .joinToString(","))
            )
        } catch (error: Throwable) {
            report.put("status", "failed")
            report.put("error", "${error::class.java.name}: ${error.message}")
            throw error
        } finally {
            MdxLiteRtRemoteFaultInjection.clear(context)
            coordinator?.let { worker ->
                worker.cancel()
                runCatching { waitForInactive(worker) }
            }
            boundRemoteHost?.close()
            if (cleanupCacheAfterRun) {
                cacheKeyForCleanup?.let { cacheKey ->
                    runtimeFacadeForCleanup?.let { runtime ->
                        runCatching { clearExactCacheEntry(runtime, cacheKey) }
                    }
                }
            }
            restorePreferences(preferences, preferenceSnapshot)
            if (screenOffIssued) {
                instrumentation.uiAutomation
                    .executeShellCommand("input keyevent KEYCODE_WAKEUP")
                    .close()
                instrumentation.uiAutomation
                    .executeShellCommand("wm dismiss-keyguard")
                    .close()
            }
            originalPlayback?.let { playback ->
                report.put("originalPlayback", playback.report())
                playback.close()
            }
            if (!preserveMediaStoreSource) {
                mediaUri?.let { uri ->
                    runCatching { context.contentResolver.delete(uri, null, null) }
                }
            }
            writeReport(context, runId, report)
        }
    }

    @Test
    fun validateProductOwnershipHandoff() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val runId = arguments.requiredString(ARG_RUN_ID).requireSafeName()
        val backendMode = BackendMode.parse(arguments.getString(ARG_BACKEND_MODE))
        val executionHostMode = Phase7ExecutionHostMode.parse(
            arguments.getString(ARG_EXECUTION_HOST_MODE),
        )
        require(executionHostMode == Phase7ExecutionHostMode.IndependentForeground) {
            "Product ownership handoff requires the independent foreground route."
        }
        val report = baseReport(context, runId, arguments)
            .put("stage", "ownership-handoff")
        var playbackProbe: OriginalAudioPlaybackProbe? = null
        var mediaUri: Uri? = null

        try {
            val sourcePath = arguments.requiredString(ARG_SOURCE_PATH)
            val windowDecodeEnabled = arguments.optionalBoolean(
                ARG_WINDOW_DECODE_ENABLED,
                true,
            )
            val preferences = get<SharedPreferences>(SharedPreferences::class.java)
            check(preferences.edit()
                .putInt(MINIMUM_SONG_DURATION, 0)
                .putBoolean(SOURCE_SEPARATION_AUTO_FLAC_COMPRESSION, false)
                .putBoolean(SOURCE_SEPARATION_WINDOW_DECODE, windowDecodeEnabled)
                .putSourceSeparationGpuEnabled(backendMode == BackendMode.Auto)
                .putInt(SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT, REQUIRED_READY_WINDOWS)
                .commit()
            ) { "Could not configure the product ownership handoff test." }

            val presetRepository = get<SourceSeparationPresetRepository>(
                SourceSeparationPresetRepository::class.java,
            )
            val active = presetRepository.activeModel() as?
                SourceSeparationActivePresetState.Reference
                ?: error("The product ownership handoff test has no active model.")
            assertEquals(arguments.requiredString(ARG_MODEL_ID), active.reference.modelId)
            assertEquals(
                arguments.requiredString(ARG_ARTIFACT_SHA256),
                active.reference.artifactSha256,
            )

            val registeredUri = registerSourceInMediaStore(context, sourcePath, runId)
            mediaUri = registeredUri
            val source = resolveMediaStoreSong(context, registeredUri, sourcePath)
            val runtime = get<SourceSeparationRuntimeFacade>(
                SourceSeparationRuntimeFacade::class.java,
            )
            val store = get<SourceSeparationCacheStore>(SourceSeparationCacheStore::class.java)
            val handoff = get<SourceSeparationProcessingOwnershipHandoff>(
                SourceSeparationProcessingOwnershipHandoff::class.java,
            )
            val resolution = runtime.resolve(source)
            val runtimeSong = (resolution as? SourceSeparationRuntimeSongResolution.Ready)?.song
                ?: error("The product ownership source could not be resolved: $resolution")
            val playbackWakeLockTag = "com.mardous.booming:SourceSeparationProcessing"
            val inferenceWakeLockTag =
                "${context.packageName}:SourceSeparationInference"
            runtime.entries()
                .filter { it.cacheKey == runtimeSong.cacheKey }
                .forEach { entry ->
                    assertEquals(
                        SourceSeparationCacheMutationResult.Completed,
                        runtime.delete(entry.cacheKey),
                    )
                }

            fun awaitOwnership(
                operation: String,
                timeoutMs: Long = OWNERSHIP_HANDOFF_TIMEOUT_MS,
                predicate: (SourceSeparationProcessingOwnershipSnapshot) -> Boolean,
            ): SourceSeparationProcessingOwnershipSnapshot {
                val deadline = SystemClock.elapsedRealtime() + timeoutMs
                while (SystemClock.elapsedRealtime() < deadline) {
                    val snapshot = handoff.stateFlow.value
                    if (predicate(snapshot)) return snapshot
                    SystemClock.sleep(OWNERSHIP_HANDOFF_POLL_MS)
                }
                error(
                    "Processing ownership did not complete $operation: " +
                        handoff.stateFlow.value,
                )
            }

            val probe = startOriginalAudioPlayback(
                context = context,
                source = source,
                operation = "product ownership handoff",
            ).also { playbackProbe = it }
            probe.assertContinuous("before-product-handoff")
            probe.disarm()

            val enableResult = probe.sendCustomCommand(
                Playback.SET_SOURCE_SEPARATION_PLAYBACK_ENABLED,
                Bundle().apply {
                    putBoolean(Playback.EXTRA_SOURCE_SEPARATION_ENABLED, true)
                    putBoolean(Playback.EXTRA_SOURCE_SEPARATION_SHOW_MESSAGE, false)
                    putBoolean(Playback.EXTRA_SOURCE_SEPARATION_EXPECT_PROCESSING, true)
                    putFloat(Playback.EXTRA_SOURCE_SEPARATION_BLEND, TEST_BLEND)
                },
            ).get(MEDIA_SESSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertEquals(SessionResult.RESULT_SUCCESS, enableResult.resultCode)

            val localOwner = awaitOwnership("the initial PlaybackService lease") { snapshot ->
                snapshot.activeOwner == null &&
                    snapshot.activePlaybackLease?.cacheKey == runtimeSong.cacheKey &&
                    snapshot.activePlaybackLease.wakeLockHeld
            }.activePlaybackLease ?: error("The playback processing owner disappeared.")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                val type = requireNotNull(localOwner.foregroundServiceType)
                assertTrue(type and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK != 0)
                assertTrue(type and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING != 0)
            }
            val powerBefore = activeWakeLocks(
                readShellCommand(instrumentation, "dumpsys power"),
            )
            assertTrue(
                "PlaybackService did not hold its processing wake lock before handoff.",
                powerBefore.contains(playbackWakeLockTag),
            )

            val commandStartedAt = SystemClock.elapsedRealtime()
            val commandResultFuture = probe.sendCustomCommand(
                Playback.SEPARATE_CURRENT_SONG_OFFLINE,
                Bundle.EMPTY,
            )
            val handedOff = awaitOwnership("the exact remote takeover") { snapshot ->
                snapshot.activeOwner?.owner?.cacheKey == runtimeSong.cacheKey &&
                    snapshot.activePlaybackLease == null &&
                    snapshot.lastStoppedPlaybackLease?.cacheKey == runtimeSong.cacheKey &&
                    snapshot.lastStoppedPlaybackLease.wakeLockHeld.not()
            }
            val remoteOwner = requireNotNull(handedOff.activeOwner)
            val stoppedPlaybackOwner = requireNotNull(handedOff.lastStoppedPlaybackLease)
            assertTrue(
                stoppedPlaybackOwner.stoppedAtElapsedRealtimeNanos!! >=
                    remoteOwner.acceptedAtElapsedRealtimeNanos,
            )
            assertEquals("remoteOwnershipChanged", stoppedPlaybackOwner.stopReason)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                val type = requireNotNull(stoppedPlaybackOwner.foregroundServiceType)
                assertTrue(type and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK != 0)
                assertEquals(0, type and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            }

            val powerDuring = activeWakeLocks(
                readShellCommand(instrumentation, "dumpsys power"),
            )
            assertFalse(
                "PlaybackService retained its processing wake lock after takeover.",
                powerDuring.contains(playbackWakeLockTag),
            )
            assertTrue(
                "The inference process did not hold its processing wake lock after takeover.",
                powerDuring.contains(inferenceWakeLockTag),
            )
            val servicesDuring = readShellCommand(
                instrumentation,
                "dumpsys activity services ${context.packageName}",
            )
            assertTrue(
                "The independent inference service was absent during the accepted run.",
                servicesDuring.contains("SourceSeparationExecutionService"),
            )

            val commandResult = commandResultFuture.get(
                OWNERSHIP_COMMAND_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
            )
            assertEquals(SessionResult.RESULT_SUCCESS, commandResult.resultCode)
            val released = awaitOwnership(
                operation = "terminal remote release",
                timeoutMs = OWNERSHIP_TERMINAL_TIMEOUT_MS,
            ) { snapshot ->
                snapshot.activeOwner == null && snapshot.activePlaybackLease == null &&
                    snapshot.lastReleasedOwner?.owner == remoteOwner.owner
            }
            assertEquals(
                "execution-host-closed",
                released.lastReleasedOwner?.releaseReason,
            )

            val manifest = requireNotNull(store.readManifest(runtimeSong.cacheKey)) {
                "The product manual command did not publish its completed cache."
            }
            assertEquals(SourceSeparationCacheManifestState.Completed, manifest.state)
            val journal = requireNotNull(store.readRunJournal(runtimeSong.cacheKey))
            assertEquals(SourceSeparationCacheRunJournalLifecycle.Completed, journal.lifecycle)
            assertEquals(SourceSeparationExecutionRunClass.ManualFullSong, journal.request.runClass)
            assertEquals(remoteOwner.owner.runId, journal.request.runId)
            assertEquals(remoteOwner.owner.processGeneration, journal.request.processGeneration)
            assertNotEquals(Process.myPid(), journal.request.ownerPid)
            applyRunAdmissionEvidence(
                report = report,
                request = journal.request,
                backendMode = backendMode,
                requestedGpuRuntimeProfile = MdxLiteRtGpuRuntimeProfile.BoundedOpenClFp32V1
                    .takeIf { backendMode == BackendMode.Auto },
            )
            applyRuntimeEvidence(report, manifest)
            val completedPlayback = requireNotNull(
                runtime.openCompletedCache(runtimeSong.cacheKey),
            )
            completedPlayback.use {
                assertTrue(it.vocalsFile.isFile)
                assertTrue(it.instrumentalFile.isFile)
            }

            val powerAfter = activeWakeLocks(
                readShellCommand(instrumentation, "dumpsys power"),
            )
            assertFalse(powerAfter.contains(playbackWakeLockTag))
            assertFalse(powerAfter.contains(inferenceWakeLockTag))
            report.put("status", "passed")
            report.put("timing", report.getJSONObject("timing")
                .put("fullSongMs", SystemClock.elapsedRealtime() - commandStartedAt)
            )
            report.put("cache", report.getJSONObject("cache")
                .put("cacheKey", runtimeSong.cacheKey)
                .put("exactIdentity", true)
                .put("completedPlayable", true)
                .put("runJournalSequence", journal.latestSequence)
                .put("runJournalOwnerPid", journal.request.ownerPid ?: JSONObject.NULL)
            )
            report.put("ownershipHandoff", JSONObject()
                .put("remoteCacheKey", remoteOwner.owner.cacheKey)
                .put("remoteRunId", remoteOwner.owner.runId)
                .put("remoteProcessGeneration", remoteOwner.owner.processGeneration)
                .put("remoteAcceptedAtElapsedRealtimeNanos",
                    remoteOwner.acceptedAtElapsedRealtimeNanos)
                .put("playbackLeaseStartedAtElapsedRealtimeNanos",
                    localOwner.startedAtElapsedRealtimeNanos)
                .put("playbackLeaseStoppedAtElapsedRealtimeNanos",
                    stoppedPlaybackOwner.stoppedAtElapsedRealtimeNanos)
                .put("playbackStopReason", stoppedPlaybackOwner.stopReason)
                .put("playbackWakeLockBefore", true)
                .put("playbackWakeLockDuring", false)
                .put("inferenceWakeLockDuring", true)
                .put("bothWakeLocksReleasedAfter", true)
                .put("inferenceServiceObserved", true)
            )
        } catch (error: Throwable) {
            report.put("status", "failed")
            report.put("error", "${error::class.java.name}: ${error.message}")
            throw error
        } finally {
            playbackProbe?.let { probe ->
                runCatching {
                    probe.sendCustomCommand(
                        Playback.SET_SOURCE_SEPARATION_PLAYBACK_ENABLED,
                        Bundle().apply {
                            putBoolean(Playback.EXTRA_SOURCE_SEPARATION_ENABLED, false)
                            putBoolean(Playback.EXTRA_SOURCE_SEPARATION_SHOW_MESSAGE, false)
                        },
                    ).get(MEDIA_SESSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }
                probe.close()
            }
            mediaUri?.let { uri ->
                runCatching { context.contentResolver.delete(uri, null, null) }
            }
            writeReport(context, runId, "ownership-handoff", report)
        }
    }

    @Test
    fun validateProductPauseCleanup() {
        validateProductTerminalCleanup(ProductTerminalCleanupAction.Pause)
    }

    @Test
    fun validateProductCancelCleanup() {
        validateProductTerminalCleanup(ProductTerminalCleanupAction.Cancel)
    }

    private fun validateProductTerminalCleanup(action: ProductTerminalCleanupAction) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val runId = arguments.requiredString(ARG_RUN_ID).requireSafeName()
        val backendMode = BackendMode.parse(arguments.getString(ARG_BACKEND_MODE))
        val executionHostMode = Phase7ExecutionHostMode.parse(
            arguments.getString(ARG_EXECUTION_HOST_MODE),
        )
        require(executionHostMode == Phase7ExecutionHostMode.IndependentForeground) {
            "Product ${action.displayName} cleanup requires the independent foreground route."
        }
        val report = baseReport(context, runId, arguments)
            .put("stage", action.stage)
        val preferences = get<SharedPreferences>(SharedPreferences::class.java)
        val preferenceSnapshot = snapshotPreferences(
            preferences,
            setOf(
                SOURCE_SEPARATION_AUTO_START,
                SOURCE_SEPARATION_AUTO_FLAC_COMPRESSION,
                SOURCE_SEPARATION_WINDOW_DECODE,
                SOURCE_SEPARATION_GPU_ENABLED,
                SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT,
            ),
        )
        var mediaUri: Uri? = null
        var worker: SourceSeparationForegroundWorkerCoordinator? = null
        var runtimeFacade: SourceSeparationRuntimeFacade? = null
        var testCacheKey: String? = null

        try {
            val sourcePath = arguments.requiredString(ARG_SOURCE_PATH)
            check(preferences.edit()
                .putBoolean(SOURCE_SEPARATION_AUTO_START, false)
                .putBoolean(SOURCE_SEPARATION_AUTO_FLAC_COMPRESSION, false)
                .putBoolean(
                    SOURCE_SEPARATION_WINDOW_DECODE,
                    arguments.optionalBoolean(ARG_WINDOW_DECODE_ENABLED, true),
                )
                .putSourceSeparationGpuEnabled(backendMode == BackendMode.Auto)
                .putInt(SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT, 1)
                .commit()
            ) { "Could not configure the product ${action.displayName} cleanup test." }

            val presetRepository = get<SourceSeparationPresetRepository>(
                SourceSeparationPresetRepository::class.java,
            )
            val active = presetRepository.activeModel() as?
                SourceSeparationActivePresetState.Reference
                ?: error("The product ${action.displayName} cleanup test has no active model.")
            assertEquals(arguments.requiredString(ARG_MODEL_ID), active.reference.modelId)
            assertEquals(
                arguments.requiredString(ARG_ARTIFACT_SHA256),
                active.reference.artifactSha256,
            )

            val registeredUri = registerSourceInMediaStore(context, sourcePath, runId)
            mediaUri = registeredUri
            val source = resolveMediaStoreSong(context, registeredUri, sourcePath)
            val runtime = get<SourceSeparationRuntimeFacade>(
                SourceSeparationRuntimeFacade::class.java,
            ).also { runtimeFacade = it }
            val store = get<SourceSeparationCacheStore>(SourceSeparationCacheStore::class.java)
            val cacheRepository = get<SourceSeparationModelAwareCacheRepository>(
                SourceSeparationModelAwareCacheRepository::class.java,
            )
            val handoff = get<SourceSeparationProcessingOwnershipHandoff>(
                SourceSeparationProcessingOwnershipHandoff::class.java,
            )
            val resolution = runtime.resolve(source)
            val runtimeSong = (resolution as? SourceSeparationRuntimeSongResolution.Ready)?.song
                ?: error(
                    "The product ${action.displayName} source could not be resolved: $resolution",
                )
            testCacheKey = runtimeSong.cacheKey
            clearExactCacheEntry(runtime, runtimeSong.cacheKey)

            fun awaitOwnership(
                operation: String,
                predicate: (SourceSeparationProcessingOwnershipSnapshot) -> Boolean,
            ): SourceSeparationProcessingOwnershipSnapshot {
                val deadline = SystemClock.elapsedRealtime() + OWNERSHIP_HANDOFF_TIMEOUT_MS
                while (SystemClock.elapsedRealtime() < deadline) {
                    val snapshot = handoff.stateFlow.value
                    if (predicate(snapshot)) return snapshot
                    SystemClock.sleep(OWNERSHIP_HANDOFF_POLL_MS)
                }
                error("Processing ownership did not complete $operation: ${handoff.stateFlow.value}")
            }

            val coordinator = SourceSeparationForegroundWorkerCoordinator(
                context = context,
                preferences = preferences,
                sourceSeparationRuntime = runtime,
            ).also { worker = it }
            coordinator.attachCallbacks(RecordingCallbacks())
            coordinator.updateSong(
                song = source,
                positionMs = 0L,
                durationMs = source.duration,
                isPlaying = false,
                sourceSeparationBlend = TEST_BLEND,
            )
            assertTrue(coordinator.startCurrentSong())

            val activeOwnership = awaitOwnership(
                "the remote ${action.displayName} target",
            ) { snapshot ->
                snapshot.activeOwner?.owner?.cacheKey == runtimeSong.cacheKey
            }
            val remoteOwner = requireNotNull(activeOwnership.activeOwner)
            waitForReady(coordinator, minimumReadyWindows = 1)

            val journalDuring = requireNotNull(store.readRunJournal(runtimeSong.cacheKey))
            assertEquals(SourceSeparationCacheRunJournalLifecycle.Running, journalDuring.lifecycle)
            assertEquals(SourceSeparationExecutionRunClass.ManualFullSong,
                journalDuring.request.runClass)
            assertEquals(remoteOwner.owner.runId, journalDuring.request.runId)
            val remotePid = requireNotNull(journalDuring.request.ownerPid)
            assertNotEquals(Process.myPid(), remotePid)
            val inferenceWakeLockTag = "${context.packageName}:SourceSeparationInference"
            val wakeLocksDuring = activeWakeLocks(
                readShellCommand(instrumentation, "dumpsys power"),
            )
            assertTrue(wakeLocksDuring.contains(inferenceWakeLockTag))
            val servicesDuring = readShellCommand(
                instrumentation,
                "dumpsys activity services ${context.packageName}",
            )
            assertTrue(servicesDuring.contains("SourceSeparationExecutionService"))
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
            assertTrue(notificationManager.activeNotifications.any { notification ->
                notification.id == SourceSeparationMediaProcessingForegroundController
                    .NOTIFICATION_ID
            })

            val terminalStartedAt = SystemClock.elapsedRealtime()
            when (action) {
                ProductTerminalCleanupAction.Pause -> {
                    coordinator.pauseCurrentSong(source)
                    waitForPaused(coordinator)
                }
                ProductTerminalCleanupAction.Cancel -> {
                    coordinator.cancel()
                    waitForInactive(coordinator)
                }
            }
            val released = awaitOwnership(
                "the terminal ${action.displayName} release",
            ) { snapshot ->
                snapshot.activeOwner == null &&
                    snapshot.lastReleasedOwner?.owner == remoteOwner.owner
            }
            val terminalLatencyMs = SystemClock.elapsedRealtime() - terminalStartedAt
            assertEquals("execution-host-closed", released.lastReleasedOwner?.releaseReason)

            val journalAfter = requireNotNull(store.readRunJournal(runtimeSong.cacheKey))
            assertEquals(action.journalLifecycle, journalAfter.lifecycle)
            assertEquals(action.transitionType, journalAfter.transitions.last().type)
            val cacheLeaseReleaseMs = waitForCacheLeaseRelease(
                repository = cacheRepository,
                cacheKey = runtimeSong.cacheKey,
            )
            val kernelLockReleaseMs = waitForKernelCacheLockRelease(
                store = store,
                cacheKey = runtimeSong.cacheKey,
            )
            val cacheStatus = runtime.cacheStatus(runtimeSong)
            when (action) {
                ProductTerminalCleanupAction.Pause -> assertTrue(
                    "Pause must leave a resumable or empty entry: $cacheStatus",
                    cacheStatus is SourceSeparationModelAwareCacheStatus.Missing ||
                        cacheStatus is SourceSeparationModelAwareCacheStatus.Incomplete,
                )
                ProductTerminalCleanupAction.Cancel -> {
                    assertTrue(
                        "Cancel must leave an incomplete canceled entry: $cacheStatus",
                        cacheStatus is SourceSeparationModelAwareCacheStatus.Incomplete,
                    )
                    assertEquals(
                        SourceSeparationCacheManifestState.Partial,
                        (cacheStatus as SourceSeparationModelAwareCacheStatus.Incomplete)
                            .manifest.state,
                    )
                }
            }

            val cleanupDeadline = SystemClock.elapsedRealtime() + OWNERSHIP_HANDOFF_TIMEOUT_MS
            var notificationActive: Boolean
            var serviceActive: Boolean
            var wakeLockActive: Boolean
            do {
                notificationActive = notificationManager.activeNotifications.any { notification ->
                    notification.id == SourceSeparationMediaProcessingForegroundController
                        .NOTIFICATION_ID
                }
                serviceActive = readShellCommand(
                    instrumentation,
                    "dumpsys activity services ${context.packageName}",
                ).contains("SourceSeparationExecutionService")
                wakeLockActive = activeWakeLocks(
                    readShellCommand(instrumentation, "dumpsys power"),
                ).contains(inferenceWakeLockTag)
                if (!notificationActive && !serviceActive && !wakeLockActive) break
                SystemClock.sleep(OWNERSHIP_HANDOFF_POLL_MS)
            } while (SystemClock.elapsedRealtime() < cleanupDeadline)
            assertFalse(
                "The processing notification survived ${action.displayName}.",
                notificationActive,
            )
            assertFalse(
                "The inference foreground service survived ${action.displayName}.",
                serviceActive,
            )
            assertFalse(
                "The inference wake lock survived ${action.displayName}.",
                wakeLockActive,
            )

            val remoteProcessAfter = readShellCommand(
                instrumentation,
                "pidof ${context.packageName}:source_separation",
            ).trim()
            val idleHost = BoundRemoteSourceSeparationExecutionHost(context.applicationContext)
            val idleDiagnostics = try {
                idleHost.processDiagnostics()
            } finally {
                idleHost.close()
            }
            assertNull(idleDiagnostics.activeRunId)
            assertEquals(SourceSeparationProcessSessionState.Empty, idleDiagnostics.session.state)
            assertEquals(0, idleDiagnostics.session.nativeSessionCreationCount)
            assertEquals(0, idleDiagnostics.session.activeLeaseCount)
            assertNull(idleDiagnostics.session.sessionId)
            assertNull(idleDiagnostics.foregroundService.activeLease)
            assertNull(idleDiagnostics.processingWakeLock.activeLease)
            assertFalse(idleDiagnostics.processingWakeLock.platformHeld)
            var resumeElapsedMs = 0L
            var resumedManifest: SourceSeparationCacheManifest? = null
            var resumedJournal: SourceSeparationCacheRunJournal? = null
            if (action == ProductTerminalCleanupAction.Pause) {
                val resumeStartedAt = SystemClock.elapsedRealtime()
                coordinator.updateSong(
                    song = source,
                    positionMs = 0L,
                    durationMs = source.duration,
                    isPlaying = false,
                    sourceSeparationBlend = TEST_BLEND,
                )
                assertTrue(coordinator.startCurrentSong())
                waitForCompleted(coordinator)
                resumeElapsedMs = SystemClock.elapsedRealtime() - resumeStartedAt
                val resumed = runtime.cacheStatus(runtimeSong) as?
                    SourceSeparationModelAwareCacheStatus.Completed
                    ?: error("The product pause test did not complete after resume.")
                resumedManifest = resumed.manifest
                resumedJournal = requireNotNull(store.readRunJournal(runtimeSong.cacheKey))
                assertEquals(
                    SourceSeparationCacheRunJournalLifecycle.Completed,
                    resumedJournal.lifecycle,
                )
                waitForCacheLeaseRelease(cacheRepository, runtimeSong.cacheKey)
                waitForKernelCacheLockRelease(store, runtimeSong.cacheKey)
                applyRuntimeEvidence(report, resumed.manifest)
            }
            applyRunAdmissionEvidence(
                report = report,
                request = journalAfter.request,
                backendMode = backendMode,
                requestedGpuRuntimeProfile = MdxLiteRtGpuRuntimeProfile.BoundedOpenClFp32V1
                    .takeIf { backendMode == BackendMode.Auto },
            )
            report.put("status", "passed")
            report.put("timing", report.getJSONObject("timing")
                .put("cancellationLatencyMs",
                    terminalLatencyMs.takeIf {
                        action == ProductTerminalCleanupAction.Cancel
                    } ?: 0L)
            )
            report.put("cache", report.getJSONObject("cache")
                .put("cacheKey", runtimeSong.cacheKey)
                .put("exactIdentity", true)
                .put("completedPlayable", resumedManifest != null)
                .put("runJournalSequence", journalAfter.latestSequence)
                .put("runJournalOwnerPid", remotePid)
                .put("leaseReleaseMs", cacheLeaseReleaseMs)
                .put("kernelLockReleaseMs", kernelLockReleaseMs)
            )
            report.put("taskLifecycle", JSONObject()
                .put("action", action.reportAction)
                .put("terminalLatencyMs", terminalLatencyMs)
                .put("runId", journalAfter.request.runId)
                .put("processGeneration", journalAfter.request.processGeneration)
                .put("remotePid", remotePid)
                .put("journalLifecycle", journalAfter.lifecycle.name)
                .put("journalTerminalTransition", journalAfter.transitions.last().type.name)
                .put("cacheStatus", cacheStatus::class.java.simpleName)
                .put("resumed", resumedManifest != null)
                .put("resumeElapsedMs", resumeElapsedMs)
                .put("resumedJournalLifecycle",
                    resumedJournal?.lifecycle?.name ?: JSONObject.NULL)
                .put(
                    "cacheManifestState",
                    (cacheStatus as? SourceSeparationModelAwareCacheStatus.Incomplete)
                        ?.manifest?.state?.name ?: JSONObject.NULL,
                )
                .put("ownershipReleased", true)
                .put("notificationDuring", true)
                .put("notificationAfter", notificationActive)
                .put("serviceDuring", true)
                .put("serviceAfter", serviceActive)
                .put("wakeLockDuring", true)
                .put("wakeLockAfter", wakeLockActive)
                .put("remoteProcessAfter", remoteProcessAfter)
                .put("idleProcess", processResourceJson(idleDiagnostics)
                    .put("session", JSONObject()
                        .put("state", idleDiagnostics.session.state.name)
                        .put("sessionId",
                            idleDiagnostics.session.sessionId ?: JSONObject.NULL)
                        .put("nativeSessionCreationCount",
                            idleDiagnostics.session.nativeSessionCreationCount)
                        .put("activeLeaseCount",
                            idleDiagnostics.session.activeLeaseCount)
                        .put("invocationCount", idleDiagnostics.session.invocationCount)
                    )
                )
            )
        } catch (error: Throwable) {
            report.put("status", "failed")
            report.put("error", "${error::class.java.name}: ${error.message}")
            throw error
        } finally {
            worker?.let { coordinator ->
                coordinator.cancel()
                runCatching { waitForInactive(coordinator) }
            }
            testCacheKey?.let { cacheKey ->
                runtimeFacade?.let { runtime ->
                    runCatching { clearExactCacheEntry(runtime, cacheKey) }
                }
            }
            restorePreferences(preferences, preferenceSnapshot)
            mediaUri?.let { uri ->
                runCatching { context.contentResolver.delete(uri, null, null) }
            }
            writeReport(context, runId, action.stage, report)
        }
    }

    @Test
    fun validateTaskRemovalLifecycle() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val runId = arguments.requiredString(ARG_RUN_ID).requireSafeName()
        val backendMode = BackendMode.parse(arguments.getString(ARG_BACKEND_MODE))
        val executionHostMode = Phase7ExecutionHostMode.parse(
            arguments.getString(ARG_EXECUTION_HOST_MODE),
        )
        require(executionHostMode == Phase7ExecutionHostMode.IndependentForeground) {
            "Task removal requires the independent foreground route."
        }
        val stopWhenClosed = arguments.optionalBoolean(
            ARG_STOP_WHEN_CLOSED_FROM_RECENTS,
            false,
        )
        val report = baseReport(context, runId, arguments)
            .put("stage", "task-removal")
        val preferences = get<SharedPreferences>(SharedPreferences::class.java)
        val stopPreferenceWasPresent = preferences.contains(STOP_WHEN_CLOSED_FROM_RECENTS)
        val previousStopPreference = preferences.getBoolean(
            STOP_WHEN_CLOSED_FROM_RECENTS,
            false,
        )
        val playbackPreferenceWasPresent = preferences.contains(TEST_KEY_PLAYBACK_ENABLED)
        val previousPlaybackPreference = preferences.getBoolean(
            TEST_KEY_PLAYBACK_ENABLED,
            false,
        )
        var activity: MainActivity? = null
        var mediaUri: Uri? = null
        var playbackProbe: OriginalAudioPlaybackProbe? = null
        var worker: SourceSeparationForegroundWorkerCoordinator? = null

        try {
            val sourcePath = arguments.requiredString(ARG_SOURCE_PATH)
            check(preferences.edit()
                .putBoolean(SOURCE_SEPARATION_AUTO_START, false)
                .putBoolean(SOURCE_SEPARATION_AUTO_FLAC_COMPRESSION, false)
                .putBoolean(
                    SOURCE_SEPARATION_WINDOW_DECODE,
                    arguments.optionalBoolean(ARG_WINDOW_DECODE_ENABLED, true),
                )
                .putSourceSeparationGpuEnabled(backendMode == BackendMode.Auto)
                .putBoolean(STOP_WHEN_CLOSED_FROM_RECENTS, stopWhenClosed)
                .putBoolean(TEST_KEY_PLAYBACK_ENABLED, false)
                .putInt(SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT, 1)
                .commit()
            ) { "Could not configure the task-removal test." }

            val presetRepository = get<SourceSeparationPresetRepository>(
                SourceSeparationPresetRepository::class.java,
            )
            val active = presetRepository.activeModel() as?
                SourceSeparationActivePresetState.Reference
                ?: error("The task-removal test has no active model.")
            assertEquals(arguments.requiredString(ARG_MODEL_ID), active.reference.modelId)
            assertEquals(
                arguments.requiredString(ARG_ARTIFACT_SHA256),
                active.reference.artifactSha256,
            )

            val registeredUri = registerSourceInMediaStore(context, sourcePath, runId)
            mediaUri = registeredUri
            val source = resolveMediaStoreSong(context, registeredUri, sourcePath)
            val runtime = get<SourceSeparationRuntimeFacade>(
                SourceSeparationRuntimeFacade::class.java,
            )
            val store = get<SourceSeparationCacheStore>(SourceSeparationCacheStore::class.java)
            val handoff = get<SourceSeparationProcessingOwnershipHandoff>(
                SourceSeparationProcessingOwnershipHandoff::class.java,
            )
            val runtimeSong = (runtime.resolve(source) as?
                SourceSeparationRuntimeSongResolution.Ready)?.song
                ?: error("The task-removal source could not be resolved.")
            clearExactCacheEntry(runtime, runtimeSong.cacheKey)

            activity = instrumentation.startActivitySync(
                Intent(context, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
                ),
            ) as MainActivity
            instrumentation.waitForIdleSync()
            val taskId = activity.taskId
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE)
                as ActivityManager
            fun appTask() = activityManager.appTasks.singleOrNull { candidate ->
                candidate.taskInfo?.taskId == taskId
            }
            fun recentsContainsTask(): Boolean {
                val packagePattern = Regex.escape(context.packageName)
                val taskPattern = Regex(
                    "(?m)^\\s*\\* Recent #\\d+: Task\\{[^\\r\\n]* #$taskId\\b" +
                        "[^\\r\\n]*\\b$packagePattern\\b",
                )
                return taskPattern.containsMatchIn(
                    readShellCommand(instrumentation, "dumpsys activity recents"),
                )
            }
            val taskBefore = requireNotNull(appTask())
            val taskBaseComponent = taskBefore.taskInfo?.baseIntent?.component
                ?.flattenToShortString()
            assertTrue(recentsContainsTask())

            val probe = startOriginalAudioPlayback(
                context = context,
                source = source,
                operation = "task-removal playback",
            ).also { playbackProbe = it }
            val playbackBefore = probe.assertContinuous("before-task-removal")

            val coordinator = get<SourceSeparationForegroundWorkerCoordinator>(
                SourceSeparationForegroundWorkerCoordinator::class.java,
            ).also { worker = it }
            coordinator.updateSong(
                song = source,
                positionMs = playbackBefore.getLong("positionMs"),
                durationMs = source.duration,
                isPlaying = true,
                sourceSeparationBlend = TEST_BLEND,
            )
            assertTrue(coordinator.startCurrentSong())
            waitForReady(coordinator, minimumReadyWindows = 1)

            val journalBefore = requireNotNull(store.readRunJournal(runtimeSong.cacheKey))
            assertEquals(SourceSeparationCacheRunJournalLifecycle.Running, journalBefore.lifecycle)
            assertEquals(
                SourceSeparationExecutionRunClass.ManualFullSong,
                journalBefore.request.runClass,
            )
            val ownerBefore = requireNotNull(handoff.stateFlow.value.activeOwner)
            assertEquals(runtimeSong.cacheKey, ownerBefore.owner.cacheKey)
            val serviceDumpBefore = readShellCommand(
                instrumentation,
                "dumpsys activity services ${context.packageName}",
            )
            assertTrue(serviceDumpBefore.contains("PlaybackService"))
            assertTrue(serviceDumpBefore.contains("SourceSeparationExecutionService"))

            instrumentation.runOnMainSync { taskBefore.finishAndRemoveTask() }
            val removalDeadline = SystemClock.elapsedRealtime() + TASK_REMOVAL_TIMEOUT_MS
            var taskPresentAfter: Boolean
            var taskInRecentsAfter: Boolean
            do {
                taskPresentAfter = appTask() != null
                taskInRecentsAfter = recentsContainsTask()
                if (!taskPresentAfter && !taskInRecentsAfter) break
                SystemClock.sleep(TASK_REMOVAL_POLL_MS)
            } while (SystemClock.elapsedRealtime() < removalDeadline)
            assertFalse("The app task survived finishAndRemoveTask().", taskPresentAfter)
            assertFalse("The task remained in dumpsys recents.", taskInRecentsAfter)

            val playbackAfterRemoval = if (stopWhenClosed) {
                probe.awaitStopped("task-removal stop policy")
            } else {
                probe.assertContinuous("after-task-removal")
            }
            if (stopWhenClosed) {
                probe.releaseControllerOnly()
            }

            val playbackServiceDeadline =
                SystemClock.elapsedRealtime() + TASK_REMOVAL_TIMEOUT_MS
            var playbackServiceAfter: Boolean
            do {
                playbackServiceAfter = readShellCommand(
                    instrumentation,
                    "dumpsys activity services ${context.packageName}",
                ).contains("PlaybackService")
                if (playbackServiceAfter == !stopWhenClosed) break
                SystemClock.sleep(TASK_REMOVAL_POLL_MS)
            } while (SystemClock.elapsedRealtime() < playbackServiceDeadline)
            assertEquals(
                "PlaybackService did not follow the recents policy.",
                !stopWhenClosed,
                playbackServiceAfter,
            )

            val continuationDeadline =
                SystemClock.elapsedRealtime() + TASK_REMOVAL_CONTINUATION_TIMEOUT_MS
            var journalAfter = requireNotNull(store.readRunJournal(runtimeSong.cacheKey))
            while (SystemClock.elapsedRealtime() < continuationDeadline) {
                journalAfter = requireNotNull(store.readRunJournal(runtimeSong.cacheKey))
                when (journalAfter.lifecycle) {
                    SourceSeparationCacheRunJournalLifecycle.Paused,
                    SourceSeparationCacheRunJournalLifecycle.Canceled,
                    SourceSeparationCacheRunJournalLifecycle.Failed,
                    SourceSeparationCacheRunJournalLifecycle.CacheLost,
                    -> error("Task removal terminated the manual run: ${journalAfter.lifecycle}")
                    SourceSeparationCacheRunJournalLifecycle.Completed -> break
                    SourceSeparationCacheRunJournalLifecycle.Running -> {
                        val continued = journalAfter.transitions.any { transition ->
                            transition.sequence > journalBefore.latestSequence &&
                                (transition.type ==
                                    SourceSeparationCacheRunTransitionType.SegmentReady ||
                                    transition.type ==
                                    SourceSeparationCacheRunTransitionType.SegmentRunning)
                        }
                        if (continued) break
                    }
                }
                SystemClock.sleep(TASK_REMOVAL_POLL_MS)
            }
            assertTrue(
                "The manual run made no progress after task removal.",
                journalAfter.lifecycle == SourceSeparationCacheRunJournalLifecycle.Completed ||
                    journalAfter.transitions.any { transition ->
                        transition.sequence > journalBefore.latestSequence &&
                            (transition.type ==
                                SourceSeparationCacheRunTransitionType.SegmentReady ||
                                transition.type ==
                                SourceSeparationCacheRunTransitionType.SegmentRunning)
                    },
            )
            val continuationLifecycle = journalAfter.lifecycle
            val continuationSequence = journalAfter.latestSequence

            if (journalAfter.lifecycle == SourceSeparationCacheRunJournalLifecycle.Running) {
                coordinator.pauseCurrentSong(source)
                waitForPaused(coordinator)
                journalAfter = requireNotNull(store.readRunJournal(runtimeSong.cacheKey))
                assertEquals(
                    SourceSeparationCacheRunJournalLifecycle.Paused,
                    journalAfter.lifecycle,
                )
            }

            val cleanupDeadline = SystemClock.elapsedRealtime() + TASK_REMOVAL_TIMEOUT_MS
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
            val inferenceWakeLockTag = "${context.packageName}:SourceSeparationInference"
            var inferenceServiceAfter: Boolean
            var processingNotificationAfter: Boolean
            var inferenceWakeLockAfter: Boolean
            do {
                inferenceServiceAfter = readShellCommand(
                    instrumentation,
                    "dumpsys activity services ${context.packageName}",
                ).contains("SourceSeparationExecutionService")
                processingNotificationAfter = notificationManager.activeNotifications.any {
                    it.id == SourceSeparationMediaProcessingForegroundController.NOTIFICATION_ID
                }
                inferenceWakeLockAfter = activeWakeLocks(
                    readShellCommand(instrumentation, "dumpsys power"),
                ).contains(inferenceWakeLockTag)
                if (!inferenceServiceAfter && !processingNotificationAfter &&
                    !inferenceWakeLockAfter
                ) break
                SystemClock.sleep(TASK_REMOVAL_POLL_MS)
            } while (SystemClock.elapsedRealtime() < cleanupDeadline)
            assertFalse(inferenceServiceAfter)
            assertFalse(processingNotificationAfter)
            assertFalse(inferenceWakeLockAfter)
            assertNull(handoff.stateFlow.value.activeOwner)

            applyRunAdmissionEvidence(
                report = report,
                request = journalAfter.request,
                backendMode = backendMode,
                requestedGpuRuntimeProfile = MdxLiteRtGpuRuntimeProfile.BoundedOpenClFp32V1
                    .takeIf { backendMode == BackendMode.Auto },
            )
            report.put("status", "passed")
            report.put("cache", report.getJSONObject("cache")
                .put("cacheKey", runtimeSong.cacheKey)
                .put("exactIdentity", true)
                .put(
                    "completedPlayable",
                    journalAfter.lifecycle == SourceSeparationCacheRunJournalLifecycle.Completed,
                )
                .put("runJournalSequence", journalAfter.latestSequence)
                .put("runJournalOwnerPid", journalAfter.request.ownerPid ?: JSONObject.NULL)
            )
            report.put("taskLifecycle", JSONObject()
                .put("action", "finish-and-remove-task")
                .put("taskId", taskId)
                .put("taskBaseComponent", taskBaseComponent ?: JSONObject.NULL)
                .put("taskPresentBefore", true)
                .put("taskPresentAfter", false)
                .put("taskInRecentsBefore", true)
                .put("taskInRecentsAfter", false)
                .put("stopWhenClosedFromRecents", stopWhenClosed)
                .put("playbackServiceBefore", true)
                .put("playbackServiceAfter", playbackServiceAfter)
                .put("playbackBefore", playbackBefore)
                .put("playbackAfterRemoval", playbackAfterRemoval)
                .put("manualRunLifecycleAfterRemoval", continuationLifecycle.name)
                .put("manualRunSequenceBefore", journalBefore.latestSequence)
                .put("manualRunSequenceAfterRemoval", continuationSequence)
                .put("manualRunFinalLifecycle", journalAfter.lifecycle.name)
                .put("manualRunFinalSequence", journalAfter.latestSequence)
                .put("manualRunContinued", true)
                .put("processingNotificationAfterCleanup", false)
                .put("inferenceServiceAfterCleanup", false)
                .put("inferenceWakeLockAfterCleanup", false)
            )
        } catch (error: Throwable) {
            report.put("status", "failed")
            report.put("error", "${error::class.java.name}: ${error.message}")
            throw error
        } finally {
            playbackProbe?.let { probe ->
                runCatching { report.put("playbackProbe", probe.report()) }
                    .onFailure { error ->
                        report.put(
                            "playbackProbeError",
                            "${error::class.java.name}: ${error.message}",
                        )
                    }
                probe.close()
            }
            worker?.cancel()
            activity?.let { current ->
                runCatching {
                    instrumentation.runOnMainSync {
                        if (!current.isFinishing) current.finishAndRemoveTask()
                    }
                }
            }
            runCatching { context.stopService(Intent(context, PlaybackService::class.java)) }
            val preferenceEditor = preferences.edit()
            if (stopPreferenceWasPresent) {
                preferenceEditor.putBoolean(
                    STOP_WHEN_CLOSED_FROM_RECENTS,
                    previousStopPreference,
                )
            } else {
                preferenceEditor.remove(STOP_WHEN_CLOSED_FROM_RECENTS)
            }
            if (playbackPreferenceWasPresent) {
                preferenceEditor.putBoolean(
                    TEST_KEY_PLAYBACK_ENABLED,
                    previousPlaybackPreference,
                )
            } else {
                preferenceEditor.remove(TEST_KEY_PLAYBACK_ENABLED)
            }
            preferenceEditor.commit()
            mediaUri?.let { uri ->
                runCatching { context.contentResolver.delete(uri, null, null) }
            }
            writeReport(context, runId, "task-removal", report)
        }
    }

    @Test
    fun validatePlaybackServiceStopPausesOwnedWork() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val runId = arguments.requiredString(ARG_RUN_ID).requireSafeName()
        val backendMode = BackendMode.parse(arguments.getString(ARG_BACKEND_MODE))
        val executionHostMode = Phase7ExecutionHostMode.parse(
            arguments.getString(ARG_EXECUTION_HOST_MODE),
        )
        require(executionHostMode == Phase7ExecutionHostMode.InProcess) {
            "Playback-owned shutdown requires the production in-process route."
        }
        val scenario = PlaybackOwnedStopScenario.parse(
            arguments.getString(ARG_PLAYBACK_OWNED_RUN_CLASS),
        )
        val report = baseReport(context, runId, arguments)
            .put("stage", "playback-owner-stop")
        val preferences = get<SharedPreferences>(SharedPreferences::class.java)
        val globalBlendKey = testGlobalBlendKey(arguments.requiredString(ARG_MODEL_ID))
        val preferenceSnapshot = snapshotPreferences(
            preferences,
            PLAYBACK_OWNER_TEST_PREFERENCE_KEYS + globalBlendKey,
        )
        var targetUri: Uri? = null
        var currentUri: Uri? = null
        var playbackProbe: OriginalAudioPlaybackProbe? = null
        var worker: SourceSeparationForegroundWorkerCoordinator? = null
        var diagnosticStore: SourceSeparationCacheStore? = null
        var diagnosticCacheKey: String? = null

        try {
            check(preferences.edit()
                .putInt(MINIMUM_SONG_DURATION, 0)
                .putBoolean(SOURCE_SEPARATION_AUTO_CACHE_CLEANUP, false)
                .putBoolean(SOURCE_SEPARATION_AUTO_START, true)
                .putBoolean(SOURCE_SEPARATION_AUTO_FLAC_COMPRESSION, false)
                .putBoolean(
                    SOURCE_SEPARATION_WINDOW_DECODE,
                    arguments.optionalBoolean(ARG_WINDOW_DECODE_ENABLED, true),
                )
                .putSourceSeparationGpuEnabled(backendMode == BackendMode.Auto)
                .putInt(SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT, REQUIRED_READY_WINDOWS)
                .putBoolean(TEST_KEY_PLAYBACK_ENABLED, true)
                .putBoolean(TEST_KEY_REMEMBER_PER_SONG, false)
                .putFloat(globalBlendKey, TEST_BLEND)
                .commit()
            ) { "Could not configure the playback-owned shutdown test." }
            assertExpectedActivePreset(arguments)

            val targetSourcePath = arguments.requiredString(ARG_SOURCE_PATH)
            val registeredTargetUri = registerSourceInMediaStore(
                context,
                targetSourcePath,
                "$runId-target",
            )
            targetUri = registeredTargetUri
            val targetSource = resolveMediaStoreSong(
                context,
                registeredTargetUri,
                targetSourcePath,
            )
            val playbackSource = if (scenario == PlaybackOwnedStopScenario.NextSongPrefetch) {
                val currentSourcePath = arguments.requiredString(ARG_CURRENT_SOURCE_PATH)
                val registeredCurrentUri = registerSourceInMediaStore(
                    context,
                    currentSourcePath,
                    "$runId-current",
                )
                currentUri = registeredCurrentUri
                resolveMediaStoreSong(
                    context,
                    registeredCurrentUri,
                    currentSourcePath,
                ).also { current -> assertNotEquals(current.id, targetSource.id) }
            } else {
                targetSource
            }

            val runtime = get<SourceSeparationRuntimeFacade>(
                SourceSeparationRuntimeFacade::class.java,
            )
            val store = get<SourceSeparationCacheStore>(
                SourceSeparationCacheStore::class.java,
            ).also { diagnosticStore = it }
            val cacheRepository = get<SourceSeparationModelAwareCacheRepository>(
                SourceSeparationModelAwareCacheRepository::class.java,
            )
            val handoff = get<SourceSeparationProcessingOwnershipHandoff>(
                SourceSeparationProcessingOwnershipHandoff::class.java,
            )
            val runtimeSong = (runtime.resolve(targetSource) as?
                SourceSeparationRuntimeSongResolution.Ready)?.song
                ?: error("The playback-owned target source could not be resolved.")
            diagnosticCacheKey = runtimeSong.cacheKey
            clearExactCacheEntry(runtime, runtimeSong.cacheKey)

            val probe = startOriginalAudioPlayback(
                context = context,
                source = playbackSource,
                operation = "${scenario.argumentValue} service shutdown",
            ).also { playbackProbe = it }
            val playbackBefore = probe.assertContinuous("before-playback-owner-stop")
            val coordinator = get<SourceSeparationForegroundWorkerCoordinator>(
                SourceSeparationForegroundWorkerCoordinator::class.java,
            ).also { worker = it }
            coordinator.attachCallbacks(RecordingCallbacks())
            coordinator.updateSong(
                song = playbackSource,
                positionMs = playbackBefore.getLong("positionMs"),
                durationMs = playbackSource.duration,
                isPlaying = true,
                sourceSeparationBlend = TEST_BLEND,
            )
            when (scenario) {
                PlaybackOwnedStopScenario.PlaybackDemand ->
                    coordinator.requestPlaybackDemandSong(targetSource)
                PlaybackOwnedStopScenario.NextSongPrefetch -> assertTrue(
                    "The live PlaybackService owner rejected next-song prefetch.",
                    coordinator.preStartSong(
                        targetSource,
                        PLAYBACK_OWNER_ACTIVE_PREFETCH_READY_WINDOWS,
                    ),
                )
            }
            waitForReady(coordinator, minimumReadyWindows = 1)

            val journalDuring = requireNotNull(store.readRunJournal(runtimeSong.cacheKey))
            assertEquals(SourceSeparationCacheRunJournalLifecycle.Running, journalDuring.lifecycle)
            assertEquals(scenario.runClass, journalDuring.request.runClass)
            assertEquals(Process.myPid(), journalDuring.request.ownerPid)
            assertTrue(journalDuring.committedSegments.isNotEmpty())
            val serviceDumpDuring = readShellCommand(
                instrumentation,
                "dumpsys activity services ${context.packageName}",
            )
            assertTrue(serviceDumpDuring.contains("PlaybackService"))
            assertFalse(
                "Playback-owned work unexpectedly created the independent inference FGS.",
                serviceDumpDuring.contains("SourceSeparationExecutionService"),
            )
            assertNull(handoff.stateFlow.value.activeOwner)

            probe.disarm()
            val stopStartedAtMs = SystemClock.elapsedRealtime()
            probe.close()
            playbackProbe = null
            val explicitStopAccepted = context.stopService(
                Intent(context, PlaybackService::class.java),
            )
            var serviceDumpAfter = serviceDumpDuring
            val serviceStopDeadline = stopStartedAtMs + PLAYBACK_OWNER_STOP_TIMEOUT_MS
            while (SystemClock.elapsedRealtime() < serviceStopDeadline) {
                serviceDumpAfter = readShellCommand(
                    instrumentation,
                    "dumpsys activity services ${context.packageName}",
                )
                if (!serviceDumpAfter.contains("PlaybackService")) break
                SystemClock.sleep(PLAYBACK_OWNER_STOP_POLL_MS)
            }
            assertFalse(
                "PlaybackService survived its explicit teardown.",
                serviceDumpAfter.contains("PlaybackService"),
            )
            waitForPaused(coordinator)
            val serviceStopLatencyMs = SystemClock.elapsedRealtime() - stopStartedAtMs

            val journalAfter = requireNotNull(store.readRunJournal(runtimeSong.cacheKey))
            assertEquals(SourceSeparationCacheRunJournalLifecycle.Paused, journalAfter.lifecycle)
            assertEquals(SourceSeparationCacheRunTransitionType.Paused,
                journalAfter.transitions.last().type)
            assertEquals(scenario.runClass, journalAfter.request.runClass)
            assertEquals(journalDuring.request.runId, journalAfter.request.runId)
            val cacheLeaseReleaseMs = waitForCacheLeaseRelease(
                repository = cacheRepository,
                cacheKey = runtimeSong.cacheKey,
            )
            val kernelLockReleaseMs = waitForKernelCacheLockRelease(
                store = store,
                cacheKey = runtimeSong.cacheKey,
            )
            val cacheStatus = runtime.cacheStatus(runtimeSong) as?
                SourceSeparationModelAwareCacheStatus.Incomplete
                ?: error("PlaybackService teardown did not leave an incomplete cache.")
            assertEquals(SourceSeparationCacheManifestState.Partial,
                cacheStatus.manifest.state)
            assertTrue(cacheStatus.readySegments >= 1)
            applyRunAdmissionEvidence(
                report = report,
                request = journalAfter.request,
                backendMode = backendMode,
                requestedGpuRuntimeProfile = MdxLiteRtGpuRuntimeProfile.BoundedOpenClFp32V1
                    .takeIf { backendMode == BackendMode.Auto },
            )
            assertNull(journalAfter.request.gpuFallbackLatch)
            report.getJSONObject("run")
                .put(
                    "backendUsed",
                    if (backendMode == BackendMode.Auto) {
                        MdxInferenceBackend.LiteRtGpu.name
                    } else {
                        MdxInferenceBackend.LiteRtCpu.name
                    },
                )
                .put("fallbackStage", JSONObject.NULL)
                .put("fallbackReason", JSONObject.NULL)

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
            val playbackWakeLockTag = "com.mardous.booming:SourceSeparationProcessing"
            val inferenceWakeLockTag =
                "${context.packageName}:SourceSeparationInference"
            var processingNotificationAfter: Boolean
            var playbackNotificationAfter: Boolean
            var playbackWakeLockAfter: Boolean
            var inferenceWakeLockAfter: Boolean
            val resourceDeadline = SystemClock.elapsedRealtime() + PLAYBACK_OWNER_STOP_TIMEOUT_MS
            do {
                processingNotificationAfter = notificationManager.activeNotifications.any {
                    it.id == SourceSeparationMediaProcessingForegroundController.NOTIFICATION_ID
                }
                playbackNotificationAfter = notificationManager.activeNotifications.any {
                    it.id == PLAYBACK_NOTIFICATION_ID
                }
                val wakeLocks = activeWakeLocks(
                    readShellCommand(instrumentation, "dumpsys power"),
                )
                playbackWakeLockAfter = wakeLocks.contains(playbackWakeLockTag)
                inferenceWakeLockAfter = wakeLocks.contains(inferenceWakeLockTag)
                if (!processingNotificationAfter && !playbackNotificationAfter &&
                    !playbackWakeLockAfter && !inferenceWakeLockAfter
                ) break
                SystemClock.sleep(PLAYBACK_OWNER_STOP_POLL_MS)
            } while (SystemClock.elapsedRealtime() < resourceDeadline)
            assertFalse(processingNotificationAfter)
            assertFalse(playbackNotificationAfter)
            assertFalse(playbackWakeLockAfter)
            assertFalse(inferenceWakeLockAfter)
            assertNull(handoff.stateFlow.value.activeOwner)
            assertNull(handoff.stateFlow.value.activePlaybackLease)
            assertFalse(coordinator.isWorkerActive())
            assertNull(coordinator.runningSongId())
            assertNull(coordinator.pendingSongId())

            val pausedSequence = journalAfter.latestSequence
            val pausedCommittedSegments = journalAfter.committedSegments
            coordinator.updatePosition(
                positionMs = playbackBefore.getLong("positionMs") + 1_000L,
                durationMs = playbackSource.duration,
                isPlaying = true,
                sourceSeparationBlend = TEST_BLEND,
            )
            coordinator.requestPlaybackDemandSong(targetSource)
            assertFalse(
                "A stale playback owner admitted new prefetch work.",
                coordinator.preStartSong(
                    targetSource,
                    PLAYBACK_OWNER_ACTIVE_PREFETCH_READY_WINDOWS,
                ),
            )
            SystemClock.sleep(PLAYBACK_OWNER_STALE_OBSERVATION_MS)
            assertFalse(coordinator.isWorkerActive())
            assertNull(coordinator.runningSongId())
            assertNull(coordinator.pendingSongId())
            val stableJournal = requireNotNull(store.readRunJournal(runtimeSong.cacheKey))
            assertEquals(SourceSeparationCacheRunJournalLifecycle.Paused,
                stableJournal.lifecycle)
            assertEquals(pausedSequence, stableJournal.latestSequence)
            assertEquals(pausedCommittedSegments, stableJournal.committedSegments)

            report.put("status", "passed")
            report.put("cache", report.getJSONObject("cache")
                .put("cacheKey", runtimeSong.cacheKey)
                .put("exactIdentity", true)
                .put("completedPlayable", false)
                .put("manifestState", cacheStatus.manifest.state.name)
                .put("readySegments", cacheStatus.readySegments)
                .put("runJournalSequence", stableJournal.latestSequence)
                .put("runJournalOwnerPid", stableJournal.request.ownerPid ?: JSONObject.NULL)
                .put("leaseReleaseMs", cacheLeaseReleaseMs)
                .put("kernelLockReleaseMs", kernelLockReleaseMs)
            )
            report.put("playbackOwnerStop", JSONObject()
                .put("scenario", scenario.argumentValue)
                .put("runClass", scenario.runClass.name)
                .put("backgroundPolicy", scenario.runClass.backgroundPolicy.name)
                .put("playbackSongId", playbackSource.id)
                .put("targetSongId", targetSource.id)
                .put("serviceObservedBefore", true)
                .put("serviceObservedAfter", false)
                .put("explicitStopAccepted", explicitStopAccepted)
                .put("serviceStopLatencyMs", serviceStopLatencyMs)
                .put("journalLifecycle", stableJournal.lifecycle.name)
                .put("journalTerminalTransition", stableJournal.transitions.last().type.name)
                .put("coordinatorInactive", true)
                .put("pendingWorkCleared", true)
                .put("staleAdmissionRejected", true)
                .put("staleObservationMs", PLAYBACK_OWNER_STALE_OBSERVATION_MS)
                .put("journalStableAfterStop", true)
                .put("processingNotificationAfter", processingNotificationAfter)
                .put("playbackNotificationAfter", playbackNotificationAfter)
                .put("playbackWakeLockAfter", playbackWakeLockAfter)
                .put("inferenceWakeLockAfter", inferenceWakeLockAfter)
                .put("independentInferenceServiceAfter",
                    serviceDumpAfter.contains("SourceSeparationExecutionService"))
            )
        } catch (error: Throwable) {
            report.put("status", "failed")
            report.put("error", "${error::class.java.name}: ${error.message}")
            report.put(
                "failureDiagnostics",
                playbackOwnerFailureDiagnostics(
                    instrumentation = instrumentation,
                    context = context,
                    worker = worker,
                    store = diagnosticStore,
                    cacheKey = diagnosticCacheKey,
                    playbackProbe = playbackProbe,
                ),
            )
            throw error
        } finally {
            playbackProbe?.let { probe ->
                runCatching { probe.close() }
            }
            runCatching { context.stopService(Intent(context, PlaybackService::class.java)) }
            worker?.cancel()
            restorePreferences(preferences, preferenceSnapshot)
            targetUri?.let { uri ->
                runCatching { context.contentResolver.delete(uri, null, null) }
            }
            currentUri?.let { uri ->
                runCatching { context.contentResolver.delete(uri, null, null) }
            }
            writeReport(context, runId, "playback-owner-stop", report)
        }
    }

    @Test
    fun validateIndependentRunReattachment() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val runId = arguments.requiredString(ARG_RUN_ID).requireSafeName()
        val backendMode = BackendMode.parse(arguments.getString(ARG_BACKEND_MODE))
        val executionHostMode = Phase7ExecutionHostMode.parse(
            arguments.getString(ARG_EXECUTION_HOST_MODE),
        )
        require(executionHostMode == Phase7ExecutionHostMode.IndependentForeground) {
            "Run reattachment requires the independent foreground route."
        }
        val report = baseReport(context, runId, arguments)
            .put("stage", "reattachment")
        val hostEvents = Collections.synchronizedList(
            mutableListOf<SourceSeparationExecutionHostEvent>(),
        )
        val detachTriggered = AtomicBoolean(false)
        val executionFailure = AtomicReference<Throwable?>()
        val executionFinished = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        var originalHost: BoundRemoteSourceSeparationExecutionHost? = null
        var recoveredWorker: SourceSeparationForegroundWorkerCoordinator? = null
        var mediaUri: Uri? = null

        try {
            val sourcePath = arguments.requiredString(ARG_SOURCE_PATH)
            val preferences = get<SharedPreferences>(SharedPreferences::class.java)
            check(preferences.edit()
                .putInt(MINIMUM_SONG_DURATION, 0)
                .putBoolean(SOURCE_SEPARATION_AUTO_CACHE_CLEANUP, false)
                .putBoolean(SOURCE_SEPARATION_AUTO_FLAC_COMPRESSION, false)
                .putBoolean(SOURCE_SEPARATION_WINDOW_DECODE, true)
                .putSourceSeparationGpuEnabled(backendMode == BackendMode.Auto)
                .putInt(SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT, REQUIRED_READY_WINDOWS)
                .commit()
            ) { "Could not configure the run-reattachment test." }
            assertExpectedActivePreset(arguments)
            mediaUri = registerSourceInMediaStore(context, sourcePath, runId)
            val source = resolveMediaStoreSong(context, mediaUri, sourcePath)
            val presetRepository = get<SourceSeparationPresetRepository>(
                SourceSeparationPresetRepository::class.java,
            )
            val store = get<SourceSeparationCacheStore>(SourceSeparationCacheStore::class.java)
            val runtime = createCpuRuntimeFacade(
                context = context,
                preferences = preferences,
                presetRepository = presetRepository,
                backendMode = backendMode,
                processorCount = null,
                executionHostMode = executionHostMode,
                executionHostEventSink = { event ->
                    hostEvents += event
                    if (event.payload is SourceSeparationExecutionHostEventPayload.Progress &&
                        detachTriggered.compareAndSet(false, true)
                    ) {
                        throw IllegalStateException(EXPECTED_REATTACHMENT_CALLBACK_FAILURE)
                    }
                },
                boundRemoteHostSink = { originalHost = it },
            )
            val runtimeSong = (runtime.resolve(source) as?
                SourceSeparationRuntimeSongResolution.Ready)?.song
                ?: error("The run-reattachment source could not be resolved.")
            clearExactCacheEntry(runtime, runtimeSong.cacheKey)

            executor.execute {
                try {
                    runtime.separate(
                        song = runtimeSong,
                        tryGpu = backendMode == BackendMode.Auto,
                        runClass = SourceSeparationExecutionRunClass.ManualFullSong,
                        windowDecodeEnabled = true,
                    )
                } catch (error: Throwable) {
                    executionFailure.set(error)
                } finally {
                    executionFinished.countDown()
                }
            }

            val detachedDeadline = SystemClock.elapsedRealtime() +
                REATTACHMENT_OBSERVER_TIMEOUT_MS
            var detachedJournal: SourceSeparationCacheRunJournal? = null
            while (SystemClock.elapsedRealtime() < detachedDeadline) {
                val journal = store.readRunJournal(runtimeSong.cacheKey)
                if (journal?.transitions?.any { transition ->
                        transition.type ==
                            SourceSeparationCacheRunTransitionType.ObserverDisconnected
                    } == true
                ) {
                    detachedJournal = journal
                    break
                }
                journal?.takeIf { it.isTerminal }?.let {
                    error("The independent run became terminal before observer detachment: $it")
                }
                SystemClock.sleep(OWNERSHIP_HANDOFF_POLL_MS)
            }
            detachedJournal = requireNotNull(detachedJournal) {
                "The independent run did not persist observer detachment."
            }
            assertTrue(detachTriggered.get())
            assertEquals(SourceSeparationCacheRunJournalLifecycle.Running,
                detachedJournal.lifecycle)
            val inferenceWakeLockTag =
                "${context.packageName}:SourceSeparationInference"
            assertTrue(
                "The detached independent run lost its processing wake lock.",
                activeWakeLocks(readShellCommand(instrumentation, "dumpsys power"))
                    .contains(inferenceWakeLockTag),
            )

            val callbacks = RecordingCallbacks()
            val worker = SourceSeparationForegroundWorkerCoordinator(
                context = context,
                preferences = preferences,
                sourceSeparationRuntime = runtime,
                independentRunRecovery = SourceSeparationIndependentRunRecoveryClient(
                    context = context,
                    store = store,
                ),
            ).also { recoveredWorker = it }
            worker.attachCallbacks(callbacks)
            worker.updateSong(
                song = source,
                positionMs = 0L,
                durationMs = source.duration,
                isPlaying = false,
                sourceSeparationBlend = TEST_BLEND,
            )

            val adoptedDeadline = SystemClock.elapsedRealtime() +
                REATTACHMENT_OBSERVER_TIMEOUT_MS
            while (worker.runningCacheKey() != runtimeSong.cacheKey &&
                SystemClock.elapsedRealtime() < adoptedDeadline
            ) {
                when (val state = worker.workerStateFlow.value) {
                    is SourceSeparationUiState.Failed,
                    is SourceSeparationUiState.Canceled,
                    -> error("The reconnecting worker failed before adoption: $state")
                    else -> Unit
                }
                SystemClock.sleep(OWNERSHIP_HANDOFF_POLL_MS)
            }
            assertEquals(runtimeSong.cacheKey, worker.runningCacheKey())
            assertEquals(source.id, worker.runningSongId())
            assertEquals(setOf(runtimeSong.cacheKey), worker.protectedCacheKeys())
            assertNull(worker.pendingSongId())

            waitForCompleted(worker)
            waitForInactive(worker)
            assertEquals(runtimeSong.cacheKey, callbacks.completedCacheKey.get())
            assertTrue(
                "The detached execution caller did not finish after remote completion.",
                executionFinished.await(
                    REATTACHMENT_EXECUTION_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS,
                ),
            )
            val originalFailure = requireNotNull(executionFailure.get()) {
                "The original observer unexpectedly reported a successful local completion."
            }
            assertTrue(
                "The original caller did not retain the callback-loss cause: $originalFailure",
                generateSequence(originalFailure) { it.cause }
                    .any { error ->
                        error.message?.contains(EXPECTED_REATTACHMENT_CALLBACK_FAILURE) == true ||
                            error.message?.contains("did not close the terminal run") == true
                    },
            )

            val completed = runtime.cacheStatus(runtimeSong) as?
                SourceSeparationModelAwareCacheStatus.Completed
                ?: error("The reattached run did not publish a completed cache.")
            val finalJournal = requireNotNull(store.readRunJournal(runtimeSong.cacheKey))
            assertEquals(SourceSeparationCacheRunJournalLifecycle.Completed,
                finalJournal.lifecycle)
            assertEquals(detachedJournal.request.runId, finalJournal.request.runId)
            assertEquals(
                detachedJournal.request.processGeneration,
                finalJournal.request.processGeneration,
            )
            assertTrue(finalJournal.transitions.all { transition ->
                transition.runId == finalJournal.request.runId &&
                    transition.processGeneration == finalJournal.request.processGeneration
            })
            val observerTransitions = finalJournal.transitions.filter { transition ->
                transition.type == SourceSeparationCacheRunTransitionType.ObserverConnected ||
                    transition.type == SourceSeparationCacheRunTransitionType.ObserverDisconnected
            }
            val disconnectedIndex = observerTransitions.indexOfLast { transition ->
                transition.type == SourceSeparationCacheRunTransitionType.ObserverDisconnected
            }
            assertTrue(disconnectedIndex >= 0)
            assertTrue(observerTransitions.drop(disconnectedIndex + 1).any { transition ->
                transition.type == SourceSeparationCacheRunTransitionType.ObserverConnected
            })
            val completedPlayback = requireNotNull(
                runtime.openCompletedCache(runtimeSong.cacheKey),
            )
            completedPlayback.close()
            assertFalse(
                activeWakeLocks(readShellCommand(instrumentation, "dumpsys power"))
                    .contains(inferenceWakeLockTag),
            )

            report.put("status", "passed")
            report.put("cache", report.getJSONObject("cache")
                .put("cacheKey", runtimeSong.cacheKey)
                .put("exactIdentity", true)
                .put("completedPlayable", true)
                .put("runJournalSequence", finalJournal.latestSequence)
            )
            report.put("reattachment", JSONObject()
                .put("runId", finalJournal.request.runId)
                .put("processGeneration", finalJournal.request.processGeneration)
                .put("detachedJournalSequence", detachedJournal.latestSequence)
                .put("finalJournalSequence", finalJournal.latestSequence)
                .put("observerTransitionCount", observerTransitions.size)
                .put("originalFailure", originalFailure::class.java.name)
                .put("eventCount", synchronized(hostEvents) { hostEvents.size })
                .put("secondStartIssued", false)
            )
            applyRunAdmissionEvidence(
                report = report,
                request = finalJournal.request,
                backendMode = backendMode,
                requestedGpuRuntimeProfile = MdxLiteRtGpuRuntimeProfile.BoundedOpenClFp32V1
                    .takeIf { backendMode == BackendMode.Auto },
            )
            applyRuntimeEvidence(report, completed.manifest)
        } catch (error: Throwable) {
            report.put("status", "failed")
            report.put("error", "${error::class.java.name}: ${error.message}")
            throw error
        } finally {
            recoveredWorker?.cancel()
            originalHost?.close()
            executor.shutdownNow()
            mediaUri?.let { uri ->
                runCatching { context.contentResolver.delete(uri, null, null) }
            }
            writeReport(context, runId, "reattachment", report)
        }
    }

    @Test
    fun validateProcessSessionMatrix() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val runId = arguments.requiredString(ARG_RUN_ID).requireSafeName()
        requireResidentProcessValidation(arguments)
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
                val sessionLeaseRelease = waitForProcessSessionLeaseRelease(
                    requireNotNull(host),
                )
                val completed = runtimeFacade.cacheStatus(tailRuntimeSong) as?
                    SourceSeparationModelAwareCacheStatus.Completed
                    ?: error("The $label full-song bookend did not complete.")
                val diagnostics = sessionLeaseRelease.diagnostics
                assertProcessIdentity(diagnostics)
                val output = requireNotNull(completed.manifest.output)
                fullSongBookends.put(JSONObject()
                    .put("label", label)
                    .put("outputFrameCount", output.outputFrameCount)
                    .put("windowCount", output.windowCount)
                    .put("leaseReleaseMs", leaseReleaseMs)
                    .put("sessionLeaseReleaseMs", sessionLeaseRelease.elapsedMs)
                    .put("invocationCount", diagnostics.session.invocationCount)
                    .put("sessionId", diagnostics.session.sessionId)
                    .put("stems", JSONArray(output.stems.map { stem ->
                        JSONObject()
                            .put("semantic", stem.semanticId.value)
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
                val sessionLeaseRelease = waitForProcessSessionLeaseRelease(
                    requireNotNull(host),
                )
                val after = sessionLeaseRelease.diagnostics
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
                        SourceSeparationCacheManifestState.Partial,
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
                if (cycle == PROCESS_MATRIX_REBIND_CYCLE) {
                    val oldHost = requireNotNull(host)
                    retiredUnexpectedDeaths +=
                        oldHost.connectionDiagnostics.unexpectedBinderDeathCount
                    oldHost.close()
                    SystemClock.sleep(PROCESS_REBIND_SETTLE_MS)
                    runtimeFacade = createRuntime()
                    runtimeSong = (runtimeFacade.resolve(source) as?
                        SourceSeparationRuntimeSongResolution.Ready)?.song
                        ?: error("The completed process-session run could not rebind.")
                    tailRuntimeSong = (runtimeFacade.resolve(tailSource) as?
                        SourceSeparationRuntimeSongResolution.Ready)?.song
                        ?: error("The pending-tail source could not rebind.")
                    val reboundDiagnostics = requireNotNull(host).processDiagnostics()
                    assertProcessIdentity(reboundDiagnostics)
                    assertEquals(
                        after.session.invocationCount,
                        reboundDiagnostics.session.invocationCount,
                    )
                    rebound = true
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
                    .put("sessionLeaseReleaseMs", sessionLeaseRelease.elapsedMs)
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
                    stem.stemId to requireNotNull(stem.wavIntegrity).sha256
                },
                afterMatrixFullSong.output?.stems.orEmpty().associate { stem ->
                    stem.stemId to requireNotNull(stem.wavIntegrity).sha256
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
                    "The largest resident-process address gap crossed the frozen floor.",
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
        requireResidentProcessValidation(arguments)
        val report = baseReport(context, runId, arguments)
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
            val runtimeFacade = createCpuRuntimeFacade(
                context = context,
                preferences = preferences,
                presetRepository = presetRepository,
                backendMode = BackendMode.Auto,
                processorCount = null,
                executionHostMode = Phase7ExecutionHostMode.BoundRemote,
                boundRemoteHostSink = { host = it },
            )

            fun newWorker() = SourceSeparationForegroundWorkerCoordinator(
                context = context,
                preferences = preferences,
                sourceSeparationRuntime = runtimeFacade,
                activeSelectionFlow = presetRepository.activeSelectionFlow,
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
                val oldProcess = requireNotNull(host).processDiagnostics()
                val oldExpectedBinderDeathCount =
                    requireNotNull(host).connectionDiagnostics.expectedBinderDeathCount
                playback.assertContinuous("switch-$switchIndex-before-run")
                val completedManifest = completeExactRun(targetSong)
                val completedDiagnostics = requireNotNull(host).processDiagnostics()
                assertNotEquals(oldProcess.processGeneration,
                    completedDiagnostics.processGeneration)
                assertNotEquals(oldProcess.processStartTicks,
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
                val connection = requireNotNull(host).connectionDiagnostics
                val binderDeath = requireNotNull(connection.lastBinderDeath)
                assertEquals(oldExpectedBinderDeathCount + 1,
                    connection.expectedBinderDeathCount)
                assertEquals(0, connection.unexpectedBinderDeathCount)
                assertTrue(binderDeath.expected)
                assertEquals(oldProcess.processGeneration, binderDeath.processGeneration)
                playback.assertContinuous("switch-$switchIndex-after-run")

                switchCases.put(JSONObject()
                    .put("switch", switchIndex)
                    .put("targetModelId", targetModelId)
                    .put("targetArtifactSha256", targetArtifactSha256)
                    .put("proactiveRecycle", true)
                    .put("oldGeneration", oldProcess.processGeneration)
                    .put("oldInvocationCount", oldProcess.session.invocationCount)
                    .put("recycleToken", binderDeath.recycleToken ?: JSONObject.NULL)
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
            assertEquals(SourceSeparationCacheManifestState.Partial, failedManifest.state)
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
    fun validateProcessCacheSafetyMatrix() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val runId = arguments.requiredString(ARG_RUN_ID).requireSafeName()
        val processCacheScope = arguments.getString(ARG_PROCESS_CACHE_SCOPE) ?: "all"
        require(processCacheScope in setOf("all", "death", "cache-clear", "representative")) {
            "Unsupported process-cache matrix scope: $processCacheScope"
        }
        val processAbi = arguments.requiredString(ARG_PROCESS_ABI)
        require(
            (processAbi == "x86" && MdxX86ProcessValidationOverride.buildEnabled) ||
                (processAbi == "arm64-v8a" && !MdxX86ProcessValidationOverride.buildEnabled)
        ) {
            "The process-cache matrix requires its explicit x86 or regular arm64 build."
        }
        val report = baseReport(context, runId, arguments)
        val cases = JSONArray()
        var mediaUri: Uri? = null
        var playbackMediaUri: Uri? = null
        var playback: OriginalAudioPlaybackProbe? = null
        var host: BoundRemoteSourceSeparationExecutionHost? = null

        try {
            val sourcePath = arguments.requiredString(ARG_SOURCE_PATH)
            mediaUri = registerSourceInMediaStore(context, sourcePath, runId)
            val source = resolveMediaStoreSong(context, mediaUri, sourcePath)
            val playbackSourcePath = arguments.requiredString(ARG_CURRENT_SOURCE_PATH)
            playbackMediaUri = registerSourceInMediaStore(
                context,
                playbackSourcePath,
                "$runId-playback",
            )
            val playbackSource = resolveMediaStoreSong(
                context,
                playbackMediaUri,
                playbackSourcePath,
            )
            val playbackProbe = startOriginalAudioPlayback(
                context,
                playbackSource,
                "process-cache matrix playback",
            ).also { playback = it }

            val preferences = get<SharedPreferences>(SharedPreferences::class.java)
            check(preferences.edit()
                .putBoolean(SOURCE_SEPARATION_WINDOW_DECODE, true)
                .putBoolean(SOURCE_SEPARATION_AUTO_FLAC_COMPRESSION, false)
                .putInt(SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT, 1)
                .commit()
            ) { "Could not persist process-cache matrix preferences." }
            val presetRepository = get<SourceSeparationPresetRepository>(
                SourceSeparationPresetRepository::class.java,
            )
            assertExpectedActivePreset(arguments)
            val store = get<SourceSeparationCacheStore>(SourceSeparationCacheStore::class.java)
            val cacheRoot = store.root().directory

            fun createRuntime(): SourceSeparationRuntimeFacade = createCpuRuntimeFacade(
                context = context,
                preferences = preferences,
                presetRepository = presetRepository,
                backendMode = BackendMode.Auto,
                processorCount = null,
                executionHostMode = Phase7ExecutionHostMode.BoundRemote,
                boundRemoteHostSink = { host = it },
            )

            var runtime = createRuntime()
            var runtimeSong = (runtime.resolve(source) as?
                SourceSeparationRuntimeSongResolution.Ready)?.song
                ?: error("The process-cache matrix source could not be admitted.")
            val cacheKey = runtimeSong.cacheKey
            val deathCases = listOf(
                Triple(SourceSeparationCacheFaultStage.Decode,
                    SourceSeparationCacheFaultAction.Barrier, 2),
                Triple(SourceSeparationCacheFaultStage.Dsp,
                    SourceSeparationCacheFaultAction.Barrier, 2),
                Triple(SourceSeparationCacheFaultStage.NativeInvocation,
                    SourceSeparationCacheFaultAction.Notify, 2),
                Triple(SourceSeparationCacheFaultStage.OutputPublish,
                    SourceSeparationCacheFaultAction.Barrier, 3),
                Triple(SourceSeparationCacheFaultStage.JournalCommit,
                    SourceSeparationCacheFaultAction.Barrier, 5),
                Triple(SourceSeparationCacheFaultStage.TerminalCommit,
                    SourceSeparationCacheFaultAction.Barrier, 1),
            )
            val selectedDeathCases = when (processCacheScope) {
                "cache-clear" -> emptyList()
                "representative" -> deathCases.filter { (stage, _, _) ->
                    stage == SourceSeparationCacheFaultStage.Dsp ||
                        stage == SourceSeparationCacheFaultStage.NativeInvocation ||
                        stage == SourceSeparationCacheFaultStage.JournalCommit
                }
                else -> deathCases
            }
            val deathRepetitions = if (processCacheScope == "representative") {
                PHASE4_REPRESENTATIVE_REPETITIONS
            } else {
                PHASE4_DEATH_REPETITIONS
            }
            val cacheClearRepetitions = when (processCacheScope) {
                "death" -> 0
                "representative" -> PHASE4_REPRESENTATIVE_REPETITIONS
                else -> PHASE4_CACHE_CLEAR_REPETITIONS
            }

            selectedDeathCases.forEach { (stage, action, occurrence) ->
                repeat(deathRepetitions) { repetition ->
                    clearExactCacheEntry(runtime, cacheKey)
                    val token = "${stage.name.lowercase()}-${repetition + 1}"
                    SourceSeparationCacheFaultInjection.arm(
                        cacheRoot,
                        SourceSeparationCacheFaultControl(
                            token = token,
                            stage = stage,
                            action = action,
                            occurrence = occurrence,
                        ),
                    )
                    val executionError = AtomicReference<Throwable?>()
                    val executionResult = AtomicReference<SourceSeparationModelAwareEngineResult?>()
                    val finished = CountDownLatch(1)
                    val executionThread = Thread({
                        try {
                            executionResult.set(runtime.separate(
                                song = runtimeSong,
                                playbackReadyWindowCountProvider = { 1 },
                                windowDecodeEnabled = true,
                            ))
                        } catch (error: Throwable) {
                            executionError.set(error)
                        } finally {
                            finished.countDown()
                        }
                    }, "Phase4CacheDeath-$token").apply { start() }

                    val hit = waitForCacheFaultHit(cacheRoot, token)
                    val activeHost = requireNotNull(host)
                    assertEquals(activeHost.connectionDiagnostics.pid, hit.pid)
                    assertNotEquals(Process.myPid(), hit.pid)
                    runtime.entries()
                    runtime.cacheStatus(runtimeSong)
                    assertEquals(
                        SourceSeparationCacheMutationResult.Busy,
                        runtime.delete(cacheKey),
                    )
                    val protectedOtherEntries = runtime.entries()
                        .map { it.cacheKey }
                        .filterNot { it == cacheKey }
                        .toSet()
                    assertEquals(
                        0,
                        runtime.prune(
                            partialLimit = 0,
                            completedLimit = Int.MAX_VALUE,
                            protectedCacheKeys = protectedOtherEntries,
                        ).deletedEntries,
                    )
                    assertFalse(runtime.writeBlend(runtimeSong, 0.25f))
                    assertTrue(runtime.promote(cacheKey) is
                        SourceSeparationCacheFlacPromotionResult.Busy)
                    val journalBeforeDeath = store.readRunJournal(cacheKey)
                    Process.killProcess(hit.pid)

                    assertTrue(
                        "Remote execution did not terminate after $stage kill.",
                        finished.await(PHASE4_EXECUTION_TIMEOUT_MS, TimeUnit.MILLISECONDS),
                    )
                    assertTrue(
                        "Remote execution unexpectedly completed at $stage: " +
                            executionResult.get(),
                        executionError.get() != null,
                    )
                    SourceSeparationCacheFaultInjection.clear(cacheRoot)
                    val lockReleaseMs = waitForKernelCacheLockRelease(store, cacheKey)
                    playbackProbe.assertContinuous("$stage-${repetition + 1}-death")

                    activeHost.close()
                    runtime = createRuntime()
                    runtimeSong = (runtime.resolve(source) as?
                        SourceSeparationRuntimeSongResolution.Ready)?.song
                        ?: error("The process-cache resume source became unavailable.")
                    val recovered = runtime.separate(
                        song = runtimeSong,
                        playbackReadyWindowCountProvider = { 1 },
                        windowDecodeEnabled = true,
                    )
                    assertTrue(
                        recovered is SourceSeparationModelAwareEngineResult.Completed ||
                            recovered is SourceSeparationModelAwareEngineResult.AlreadyCompleted,
                    )
                    val journal = requireNotNull(store.readRunJournal(cacheKey))
                    assertEquals(SourceSeparationCacheRunJournalLifecycle.Completed,
                        journal.lifecycle)
                    assertEquals(journal.committedSegments.size,
                        journal.committedSegments.map { it.segmentIndex }.distinct().size)
                    if (journalBeforeDeath != null) {
                        assertTrue(journal.transitions.any {
                            it.type == SourceSeparationCacheRunTransitionType.PreviousOwnerDied
                        })
                    }
                    val completion = requireNotNull(store.readManifest(cacheKey))
                    assertEquals(
                        SourceSeparationCacheValidationResult.Valid,
                        store.validateCompletedEntry(completion),
                    )
                    playbackProbe.assertContinuous("$stage-${repetition + 1}-recovered")
                    cases.put(JSONObject()
                        .put("stage", stage.name)
                        .put("repetition", repetition + 1)
                        .put("action", action.name)
                        .put("occurrence", occurrence)
                        .put("killedPid", hit.pid)
                        .put("lockReleaseMs", lockReleaseMs)
                        .put("journalBeforeDeath", journalBeforeDeath != null)
                        .put("finalSequence", journal.latestSequence)
                        .put("committedSegments", journal.committedSegments.size)
                    )
                }
            }

            repeat(cacheClearRepetitions) { repetition ->
                clearExactCacheEntry(runtime, cacheKey)
                val token = "cache-clear-${repetition + 1}"
                SourceSeparationCacheFaultInjection.arm(
                    cacheRoot,
                    SourceSeparationCacheFaultControl(
                        token = token,
                        stage = SourceSeparationCacheFaultStage.Dsp,
                        action = SourceSeparationCacheFaultAction.Barrier,
                    ),
                )
                val executionError = AtomicReference<Throwable?>()
                val finished = CountDownLatch(1)
                Thread({
                    try {
                        runtime.separate(
                            song = runtimeSong,
                            playbackReadyWindowCountProvider = { 1 },
                        )
                    } catch (error: Throwable) {
                        executionError.set(error)
                    } finally {
                        finished.countDown()
                    }
                }, "Phase4CacheClear-$token").start()
                waitForCacheFaultHit(cacheRoot, token)
                assertTrue(cacheRoot.deleteRecursively())
                assertTrue(
                    "Cache-clear run did not terminate.",
                    finished.await(PHASE4_EXECUTION_TIMEOUT_MS, TimeUnit.MILLISECONDS),
                )
                val error = executionError.get()
                assertTrue(error is SourceSeparationRemoteExecutionException)
                val remoteError = error as SourceSeparationRemoteExecutionException
                assertEquals(
                    SourceSeparationIpcErrorCategory.CacheUnavailable,
                    remoteError.remoteError.category,
                )
                assertFalse(store.entryDirectory(cacheKey).exists())
                playbackProbe.assertContinuous("cache-clear-${repetition + 1}")
                store.ensureLayout()
                SourceSeparationCacheFaultInjection.clear(cacheRoot)
                val recovered = runtime.separate(
                    song = runtimeSong,
                    playbackReadyWindowCountProvider = { 1 },
                )
                assertTrue(recovered is SourceSeparationModelAwareEngineResult.Completed)
                cases.put(JSONObject()
                    .put("stage", "CacheClear")
                    .put("repetition", repetition + 1)
                    .put("typedOutcome", remoteError.remoteError.category.name)
                )
            }

            report.put("status", "passed")
            report.put("processCacheScope", processCacheScope)
            report.put("processCacheCases", cases)
            report.put("playbackContinuity", playbackProbe.report())
            report.put("cache", report.getJSONObject("cache")
                .put("cacheKey", cacheKey)
                .put("deathCaseCount", selectedDeathCases.size * deathRepetitions)
                .put("cacheClearCaseCount", cacheClearRepetitions)
            )
        } catch (error: Throwable) {
            report.put("status", "failed")
            report.put("error", "${error::class.java.name}: ${error.message}")
            throw error
        } finally {
            val cacheRoot = runCatching {
                get<SourceSeparationCacheStore>(SourceSeparationCacheStore::class.java)
                    .root().directory
            }.getOrNull()
            cacheRoot?.let(SourceSeparationCacheFaultInjection::clear)
            host?.close()
            playback?.close()
            mediaUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            playbackMediaUri?.let {
                runCatching { context.contentResolver.delete(it, null, null) }
            }
            writeReport(context, runId, "process-cache-matrix", report)
        }
    }

    @Test
    fun validateProcessCacheManagementRaces() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val runId = arguments.requiredString(ARG_RUN_ID).requireSafeName()
        require(MdxX86ProcessValidationOverride.buildEnabled) {
            "The process-cache race matrix requires the explicit x86 validation build."
        }
        val report = baseReport(context, runId, arguments)
        val backgroundThreads = mutableListOf<Thread>()
        var mediaUri: Uri? = null
        var playbackMediaUri: Uri? = null
        var playback: OriginalAudioPlaybackProbe? = null
        var host: BoundRemoteSourceSeparationExecutionHost? = null
        var cacheRoot: File? = null
        var presetRepository: SourceSeparationPresetRepository? = null
        var primaryModelId: String? = null
        var primaryArtifactSha256: String? = null
        var primaryBackup: File? = null

        try {
            val sourcePath = arguments.requiredString(ARG_SOURCE_PATH)
            mediaUri = registerSourceInMediaStore(context, sourcePath, runId)
            val source = resolveMediaStoreSong(context, mediaUri, sourcePath)
            val playbackSourcePath = arguments.requiredString(ARG_CURRENT_SOURCE_PATH)
            playbackMediaUri = registerSourceInMediaStore(
                context,
                playbackSourcePath,
                "$runId-playback",
            )
            val playbackSource = resolveMediaStoreSong(
                context,
                playbackMediaUri,
                playbackSourcePath,
            )
            val playbackProbe = startOriginalAudioPlayback(
                context,
                playbackSource,
                "process-cache race playback",
            ).also { playback = it }

            val preferences = get<SharedPreferences>(SharedPreferences::class.java)
            check(preferences.edit()
                .putBoolean(SOURCE_SEPARATION_WINDOW_DECODE, true)
                .putBoolean(SOURCE_SEPARATION_AUTO_FLAC_COMPRESSION, false)
                .putInt(SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT, 1)
                .commit()
            ) { "Could not persist process-cache race preferences." }
            val repository = get<SourceSeparationPresetRepository>(
                SourceSeparationPresetRepository::class.java,
            ).also { presetRepository = it }
            val primaryId = arguments.requiredString(ARG_MODEL_ID)
                .also { primaryModelId = it }
            val primarySha256 = arguments.requiredString(ARG_ARTIFACT_SHA256)
                .also { primaryArtifactSha256 = it }
            val secondaryModelId = arguments.requiredString(ARG_SECONDARY_MODEL_ID)
            val secondaryArtifactSha256 = arguments.requiredString(
                ARG_SECONDARY_ARTIFACT_SHA256,
            )
            assertExpectedActivePreset(arguments)
            val secondaryInstalled = get<SourceSeparationPresetDownloader>(
                SourceSeparationPresetDownloader::class.java,
            ).download(secondaryModelId)
            assertEquals(secondaryArtifactSha256, secondaryInstalled.sha256)
            val primaryInstalled = repository.requireInstalledPreset(primarySha256)
            val backup = File(
                context.filesDir,
                "phase4-model-backups/$runId-${primaryInstalled.file.name}",
            ).also { file ->
                require(file.parentFile?.mkdirs() == true || file.parentFile?.isDirectory == true)
                primaryInstalled.file.copyTo(file, overwrite = true)
                primaryBackup = file
            }

            val store = get<SourceSeparationCacheStore>(SourceSeparationCacheStore::class.java)
            val root = store.root().directory.also { cacheRoot = it }
            fun createRuntime(): SourceSeparationRuntimeFacade = createCpuRuntimeFacade(
                context = context,
                preferences = preferences,
                presetRepository = repository,
                backendMode = BackendMode.Auto,
                processorCount = null,
                executionHostMode = Phase7ExecutionHostMode.BoundRemote,
                boundRemoteHostSink = { host = it },
            )

            var runtime = createRuntime()
            val primarySong = (runtime.resolve(source) as?
                SourceSeparationRuntimeSongResolution.Ready)?.song
                ?: error("The primary process-cache race source could not be admitted.")
            assertEquals(primaryId, primarySong.modelId)
            clearExactCacheEntry(runtime, primarySong.cacheKey)
            val seed = runtime.separate(
                song = primarySong,
                playbackReadyWindowCountProvider = { 1 },
            )
            assertTrue(seed is SourceSeparationModelAwareEngineResult.Completed)
            val seedManifest = (seed as SourceSeparationModelAwareEngineResult.Completed).manifest
            val idleHost = requireNotNull(host)
            val idleRemote = idleHost.processDiagnostics()
            assertEquals(SourceSeparationProcessSessionState.Resident, idleRemote.session.state)

            val flacToken = "flac-handoff"
            SourceSeparationCacheFaultInjection.arm(
                root,
                SourceSeparationCacheFaultControl(
                    token = flacToken,
                    stage = SourceSeparationCacheFaultStage.FlacHandoff,
                    action = SourceSeparationCacheFaultAction.Barrier,
                ),
            )
            val promotionResult = AtomicReference<SourceSeparationCacheFlacPromotionResult?>()
            val promotionError = AtomicReference<Throwable?>()
            val promotionFinished = CountDownLatch(1)
            backgroundThreads += Thread({
                try {
                    promotionResult.set(runtime.promote(primarySong.cacheKey))
                } catch (error: Throwable) {
                    promotionError.set(error)
                } finally {
                    promotionFinished.countDown()
                }
            }, "Phase4FlacHandoff").apply { start() }

            val flacHit = waitForCacheFaultHit(root, flacToken)
            assertEquals(Process.myPid(), flacHit.pid)
            assertNotEquals(idleRemote.pid, flacHit.pid)
            runtime.entries()
            assertEquals(
                SourceSeparationCacheMutationResult.Busy,
                runtime.delete(primarySong.cacheKey),
            )
            assertEquals(
                0,
                runtime.prune(
                    partialLimit = 0,
                    completedLimit = 0,
                    protectedCacheKeys = runtime.entries()
                        .map { it.cacheKey }
                        .filterNot { it == primarySong.cacheKey }
                        .toSet(),
                ).deletedEntries,
            )
            assertFalse(runtime.writeBlend(primarySong, 0.2f))
            Process.killProcess(idleRemote.pid)
            waitForRemoteConnectionState(
                idleHost,
                SourceSeparationRemoteConnectionState.Dead,
            )
            assertEquals(1, idleHost.connectionDiagnostics.unexpectedBinderDeathCount)
            SourceSeparationCacheFaultInjection.release(root, flacToken)
            assertTrue(
                "FLAC handoff did not finish after idle remote death.",
                promotionFinished.await(PHASE4_EXECUTION_TIMEOUT_MS, TimeUnit.MILLISECONDS),
            )
            promotionError.get()?.let { throw it }
            val promoted = promotionResult.get() as?
                SourceSeparationCacheFlacPromotionResult.Completed
                ?: error("FLAC handoff did not complete: ${promotionResult.get()}")
            assertTrue(requireNotNull(promoted.manifest.output).stems.all {
                it.promotionValidated
            })
            assertEquals(
                SourceSeparationCacheValidationResult.Valid,
                store.validateCompletedEntry(promoted.manifest),
            )
            playbackProbe.assertContinuous("flac-handoff-idle-remote-death")

            idleHost.close()
            host = null
            runtime = createRuntime()
            clearExactCacheEntry(runtime, primarySong.cacheKey)
            val modelRaceToken = "model-delete-race"
            SourceSeparationCacheFaultInjection.arm(
                root,
                SourceSeparationCacheFaultControl(
                    token = modelRaceToken,
                    stage = SourceSeparationCacheFaultStage.Dsp,
                    action = SourceSeparationCacheFaultAction.Barrier,
                ),
            )
            val separationResult = AtomicReference<SourceSeparationModelAwareEngineResult?>()
            val separationError = AtomicReference<Throwable?>()
            val separationFinished = CountDownLatch(1)
            backgroundThreads += Thread({
                try {
                    separationResult.set(runtime.separate(
                        song = primarySong,
                        playbackReadyWindowCountProvider = { 1 },
                    ))
                } catch (error: Throwable) {
                    separationError.set(error)
                } finally {
                    separationFinished.countDown()
                }
            }, "Phase4ModelDeleteRace").apply { start() }

            val modelRaceHit = waitForCacheFaultHit(root, modelRaceToken)
            val modelRaceHost = requireNotNull(host)
            val admittedRemote = modelRaceHost.processDiagnostics()
            assertEquals(admittedRemote.pid, modelRaceHit.pid)
            assertNotEquals(Process.myPid(), modelRaceHit.pid)
            val selectedSecondary = repository.activate(
                sha256 = secondaryArtifactSha256,
                platform = AndroidMdxRuntimePlatformProvider.current(),
                scope = SourceSeparationPresetSelectionScope.InternalValidation,
                experimentalConfirmed = true,
            )
            assertEquals(secondaryModelId, selectedSecondary.modelId)
            assertTrue(repository.delete(primarySha256))
            assertEquals(null, repository.installedModel(primarySha256))
            assertEquals(
                SourceSeparationCacheMutationResult.Busy,
                runtime.delete(primarySong.cacheKey),
            )
            SourceSeparationCacheFaultInjection.release(root, modelRaceToken)
            assertTrue(
                "The admitted run did not finish after its model was deleted.",
                separationFinished.await(PHASE4_EXECUTION_TIMEOUT_MS, TimeUnit.MILLISECONDS),
            )
            separationError.get()?.let { throw it }
            val completed = separationResult.get() as?
                SourceSeparationModelAwareEngineResult.Completed
                ?: error("The admitted model-delete run did not complete: ${separationResult.get()}")
            assertEquals(primaryId, completed.manifest.identity.modelId)
            assertEquals(primarySha256, completed.manifest.identity.artifactSha256)
            assertEquals(
                SourceSeparationCacheValidationResult.Valid,
                store.validateCompletedEntry(completed.manifest),
            )
            runtime.openCompletedCache(primarySong.cacheKey).use { playbackCache ->
                requireNotNull(playbackCache)
                assertTrue(playbackCache.vocalsFile.isFile)
                assertTrue(playbackCache.instrumentalFile.isFile)
            }
            val secondarySong = (runtime.resolve(source) as?
                SourceSeparationRuntimeSongResolution.Ready)?.song
                ?: error("The switched secondary model could not resolve.")
            assertEquals(secondaryModelId, secondarySong.modelId)
            assertNotEquals(primarySong.cacheKey, secondarySong.cacheKey)
            val modelRaceDiagnostics = modelRaceHost.processDiagnostics()
            assertEquals(admittedRemote.processGeneration, modelRaceDiagnostics.processGeneration)
            assertEquals(SourceSeparationProcessSessionState.Resident,
                modelRaceDiagnostics.session.state)
            assertTrue(modelRaceDiagnostics.session.invocationCount > 0L)
            playbackProbe.assertContinuous("active-switch-model-delete")

            backup.inputStream().use { input ->
                repository.installOfficial(primaryId, input)
            }
            val restoredPrimary = repository.activate(
                sha256 = primarySha256,
                platform = AndroidMdxRuntimePlatformProvider.current(),
                scope = SourceSeparationPresetSelectionScope.InternalValidation,
                experimentalConfirmed = true,
            )
            assertEquals(primaryId, restoredPrimary.modelId)
            assertEquals(primarySha256, restoredPrimary.artifactSha256)

            applyRuntimeEvidence(report, completed.manifest)
            report.put("status", "passed")
            report.put("playbackContinuity", playbackProbe.report())
            report.put("processCacheRaces", JSONObject()
                .put("flacHandoff", JSONObject()
                    .put("ownerPid", flacHit.pid)
                    .put("idleRemotePid", idleRemote.pid)
                    .put("idleRemoteGeneration", idleRemote.processGeneration)
                    .put("unexpectedBinderDeathCount",
                        idleHost.connectionDiagnostics.unexpectedBinderDeathCount)
                    .put("promotionCompleted", true)
                    .put("completedCacheValid", true)
                )
                .put("modelManagement", JSONObject()
                    .put("admittedRemotePid", admittedRemote.pid)
                    .put("admittedRemoteGeneration", admittedRemote.processGeneration)
                    .put("primaryModelId", primaryId)
                    .put("primaryArtifactSha256", primarySha256)
                    .put("secondaryModelId", secondaryModelId)
                    .put("secondaryArtifactSha256", secondaryArtifactSha256)
                    .put("activeSwitchPreservedRunIdentity", true)
                    .put("deletedWeightsPreservedCompletedPlayback", true)
                    .put("primaryModelRestored", true)
                )
            )
            report.put("cache", report.getJSONObject("cache")
                .put("cacheKey", primarySong.cacheKey)
                .put("exactIdentity", true)
                .put("completedPlayable", true)
                .put("flacPromotionValid", true)
            )
            assertEquals(seedManifest.identity, completed.manifest.identity)
        } catch (error: Throwable) {
            report.put("status", "failed")
            report.put("error", "${error::class.java.name}: ${error.message}")
            throw error
        } finally {
            cacheRoot?.let(SourceSeparationCacheFaultInjection::clear)
            backgroundThreads.forEach { thread ->
                runCatching { thread.join(PHASE4_EXECUTION_TIMEOUT_MS) }
            }
            val repository = presetRepository
            val primaryId = primaryModelId
            val primarySha256 = primaryArtifactSha256
            val backup = primaryBackup
            if (repository != null && primaryId != null && primarySha256 != null) {
                runCatching {
                    if (repository.installedModel(primarySha256) == null && backup?.isFile == true) {
                        backup.inputStream().use { input ->
                            repository.installOfficial(primaryId, input)
                        }
                    }
                    repository.activate(
                        sha256 = primarySha256,
                        platform = AndroidMdxRuntimePlatformProvider.current(),
                        scope = SourceSeparationPresetSelectionScope.InternalValidation,
                        experimentalConfirmed = true,
                    )
                }
            }
            primaryBackup?.delete()
            primaryBackup?.parentFile?.delete()
            host?.close()
            playback?.close()
            mediaUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            playbackMediaUri?.let {
                runCatching { context.contentResolver.delete(it, null, null) }
            }
            writeReport(context, runId, "process-cache-race-matrix", report)
        }
    }

    @Test
    fun beginProcessMainDeathScenario() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val runId = arguments.requiredString(ARG_RUN_ID).requireSafeName()
        require(MdxX86ProcessValidationOverride.buildEnabled) {
            "The process main-death scenario requires the explicit x86 validation build."
        }
        val sourcePath = arguments.requiredString(ARG_SOURCE_PATH)
        val scenarioFile = processMainDeathScenarioFile(context, runId)
        var mediaUri: Uri? = null
        var host: BoundRemoteSourceSeparationExecutionHost? = null
        var cacheRoot: File? = null

        try {
            scenarioFile.delete()
            mediaUri = registerSourceInMediaStore(context, sourcePath, runId)
            val source = resolveMediaStoreSong(context, mediaUri, sourcePath)
            val preferences = get<SharedPreferences>(SharedPreferences::class.java)
            check(preferences.edit()
                .putBoolean(SOURCE_SEPARATION_WINDOW_DECODE, true)
                .putBoolean(SOURCE_SEPARATION_AUTO_FLAC_COMPRESSION, false)
                .putInt(SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT, 1)
                .commit()
            ) { "Could not persist process main-death preferences." }
            val presetRepository = get<SourceSeparationPresetRepository>(
                SourceSeparationPresetRepository::class.java,
            )
            assertExpectedActivePreset(arguments)
            val store = get<SourceSeparationCacheStore>(SourceSeparationCacheStore::class.java)
            val root = store.root().directory.also { cacheRoot = it }
            val runtime = createCpuRuntimeFacade(
                context = context,
                preferences = preferences,
                presetRepository = presetRepository,
                backendMode = BackendMode.Auto,
                processorCount = null,
                executionHostMode = Phase7ExecutionHostMode.BoundRemote,
                boundRemoteHostSink = { host = it },
            )
            val runtimeSong = (runtime.resolve(source) as?
                SourceSeparationRuntimeSongResolution.Ready)?.song
                ?: error("The process main-death source could not be admitted.")
            clearExactCacheEntry(runtime, runtimeSong.cacheKey)
            val token = "main-process-death"
            SourceSeparationCacheFaultInjection.arm(
                root,
                SourceSeparationCacheFaultControl(
                    token = token,
                    stage = SourceSeparationCacheFaultStage.Dsp,
                    action = SourceSeparationCacheFaultAction.Barrier,
                ),
            )
            Thread({
                runtime.separate(
                    song = runtimeSong,
                    playbackReadyWindowCountProvider = { 1 },
                )
            }, "Phase4MainDeathWriter").start()

            val hit = waitForCacheFaultHit(root, token)
            val remote = requireNotNull(host).processDiagnostics()
            assertEquals(remote.pid, hit.pid)
            assertNotEquals(Process.myPid(), hit.pid)
            val journal = requireNotNull(store.readRunJournal(runtimeSong.cacheKey))
            assertEquals(remote.pid, journal.request.ownerPid)
            assertEquals(SourceSeparationCacheRunJournalLifecycle.Running, journal.lifecycle)
            writeDurableJson(
                scenarioFile,
                JSONObject()
                    .put("schemaVersion", 1)
                    .put("runId", runId)
                    .put("token", token)
                    .put("cacheKey", runtimeSong.cacheKey)
                    .put("sourceMediaUri", mediaUri.toString())
                    .put("mainPid", Process.myPid())
                    .put("remotePid", remote.pid)
                    .put("remoteProcessGeneration", remote.processGeneration)
                    .put("remoteProcessStartTicks", remote.processStartTicks)
                    .put("journalSequence", journal.latestSequence)
                    .put("committedSegments", journal.committedSegments.size),
            )

            Process.killProcess(Process.myPid())
            SystemClock.sleep(PROCESS_DEATH_TIMEOUT_MS)
            error("The validation main process survived its requested death.")
        } catch (error: Throwable) {
            cacheRoot?.let(SourceSeparationCacheFaultInjection::clear)
            host?.close()
            mediaUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            scenarioFile.delete()
            throw error
        }
    }

    @Test
    fun validateProcessMainDeathRecovery() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val runId = arguments.requiredString(ARG_RUN_ID).requireSafeName()
        require(MdxX86ProcessValidationOverride.buildEnabled) {
            "The process main-death recovery requires the explicit x86 validation build."
        }
        val report = baseReport(context, runId, arguments)
        val scenarioFile = processMainDeathScenarioFile(context, runId)
        var mediaUri: Uri? = null
        var host: BoundRemoteSourceSeparationExecutionHost? = null

        try {
            val scenario = JSONObject(scenarioFile.readText(Charsets.UTF_8))
            assertEquals(1, scenario.getInt("schemaVersion"))
            assertEquals(runId, scenario.getString("runId"))
            val token = scenario.getString("token")
            val cacheKey = scenario.getString("cacheKey")
            val oldMainPid = scenario.getInt("mainPid")
            val oldRemotePid = scenario.getInt("remotePid")
            val oldRemoteGeneration = scenario.getLong("remoteProcessGeneration")
            mediaUri = Uri.parse(scenario.getString("sourceMediaUri"))
            assertNotEquals(oldMainPid, Process.myPid())
            assertFalse(File("/proc/$oldMainPid").exists())

            val store = get<SourceSeparationCacheStore>(SourceSeparationCacheStore::class.java)
            val root = store.root().directory
            val journalBeforeRecovery = requireNotNull(store.readRunJournal(cacheKey))
            assertEquals(oldRemotePid, journalBeforeRecovery.request.ownerPid)
            val lockWasStillHeld = store.entryLocks().tryAcquire(
                cacheKey,
                SourceSeparationCacheLockOwner(
                    purpose = SourceSeparationCacheLockPurpose.Other,
                    pid = Process.myPid(),
                ),
            )?.let { lease ->
                lease.close()
                false
            } ?: true
            val remoteAliveBeforeRelease = File("/proc/$oldRemotePid").isDirectory
            SourceSeparationCacheFaultInjection.release(root, token)
            val lockReleaseMs = waitForKernelCacheLockRelease(store, cacheKey)
            SourceSeparationCacheFaultInjection.clear(root)
            val remoteExitDeadline = SystemClock.elapsedRealtime() + PROCESS_DEATH_TIMEOUT_MS
            while (File("/proc/$oldRemotePid").isDirectory &&
                SystemClock.elapsedRealtime() < remoteExitDeadline
            ) {
                SystemClock.sleep(PHASE4_FAULT_POLL_INTERVAL_MS)
            }
            val remoteAliveAfterRelease = File("/proc/$oldRemotePid").isDirectory

            val sourcePath = arguments.requiredString(ARG_SOURCE_PATH)
            val source = resolveMediaStoreSong(context, requireNotNull(mediaUri), sourcePath)
            val preferences = get<SharedPreferences>(SharedPreferences::class.java)
            val presetRepository = get<SourceSeparationPresetRepository>(
                SourceSeparationPresetRepository::class.java,
            )
            assertExpectedActivePreset(arguments)
            val runtime = createCpuRuntimeFacade(
                context = context,
                preferences = preferences,
                presetRepository = presetRepository,
                backendMode = BackendMode.Auto,
                processorCount = null,
                executionHostMode = Phase7ExecutionHostMode.BoundRemote,
                boundRemoteHostSink = { host = it },
            )
            val runtimeSong = (runtime.resolve(source) as?
                SourceSeparationRuntimeSongResolution.Ready)?.song
                ?: error("The new main process could not resolve the interrupted source.")
            assertEquals(cacheKey, runtimeSong.cacheKey)
            val recovered = runtime.separate(
                song = runtimeSong,
                playbackReadyWindowCountProvider = { 1 },
            )
            assertTrue(
                recovered is SourceSeparationModelAwareEngineResult.Completed ||
                    recovered is SourceSeparationModelAwareEngineResult.AlreadyCompleted,
            )
            val completed = requireNotNull(store.readManifest(cacheKey))
            assertEquals(SourceSeparationCacheManifestState.Completed, completed.state)
            assertEquals(
                SourceSeparationCacheValidationResult.Valid,
                store.validateCompletedEntry(completed),
            )
            val journal = requireNotNull(store.readRunJournal(cacheKey))
            assertEquals(SourceSeparationCacheRunJournalLifecycle.Completed, journal.lifecycle)
            assertEquals(journal.committedSegments.size,
                journal.committedSegments.map { it.segmentIndex }.distinct().size)
            val previousOwnerDeathRecorded = journal.transitions.any {
                it.type == SourceSeparationCacheRunTransitionType.PreviousOwnerDied &&
                    it.ownerPid == oldRemotePid
            }
            assertTrue(previousOwnerDeathRecorded ||
                journalBeforeRecovery.lifecycle != SourceSeparationCacheRunJournalLifecycle.Running)
            val newRemote = requireNotNull(host).processDiagnostics()
            runtime.openCompletedCache(cacheKey).use { playbackCache ->
                requireNotNull(playbackCache)
                assertTrue(playbackCache.vocalsFile.isFile)
                assertTrue(playbackCache.instrumentalFile.isFile)
            }

            applyRuntimeEvidence(report, completed)
            report.put("status", "passed")
            report.put("processMainDeath", JSONObject()
                .put("oldMainPid", oldMainPid)
                .put("newMainPid", Process.myPid())
                .put("oldRemotePid", oldRemotePid)
                .put("oldRemoteGeneration", oldRemoteGeneration)
                .put("newRemotePid", newRemote.pid)
                .put("newRemoteGeneration", newRemote.processGeneration)
                .put("remoteProcessReused",
                    oldRemoteGeneration == newRemote.processGeneration)
                .put("lockWasStillHeld", lockWasStillHeld)
                .put("lockReleaseMs", lockReleaseMs)
                .put("remoteAliveBeforeRelease", remoteAliveBeforeRelease)
                .put("remoteAliveAfterRelease", remoteAliveAfterRelease)
                .put("journalSequenceBeforeDeath",
                    scenario.getLong("journalSequence"))
                .put("journalSequenceBeforeRecovery",
                    journalBeforeRecovery.latestSequence)
                .put("finalJournalSequence", journal.latestSequence)
                .put("previousOwnerDeathRecorded", previousOwnerDeathRecorded)
                .put("completedSegments", journal.committedSegments.size)
            )
            report.put("cache", report.getJSONObject("cache")
                .put("cacheKey", cacheKey)
                .put("exactIdentity", true)
                .put("completedPlayable", true)
            )
        } catch (error: Throwable) {
            report.put("status", "failed")
            report.put("error", "${error::class.java.name}: ${error.message}")
            throw error
        } finally {
            runCatching {
                get<SourceSeparationCacheStore>(SourceSeparationCacheStore::class.java)
                    .root().directory
                    .let(SourceSeparationCacheFaultInjection::clear)
            }
            host?.close()
            mediaUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            scenarioFile.delete()
            scenarioFile.parentFile?.delete()
            writeReport(context, runId, "process-main-death", report)
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

            if (backendMode == BackendMode.Cpu) {
                SourceSeparationRuntimeBootstrap.ensureLoaded(context.applicationContext)
            }
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
        val executionHostMode = Phase7ExecutionHostMode.parse(
            arguments.getString(ARG_EXECUTION_HOST_MODE),
        )
        val modelSwitchAutoStart = arguments.optionalBoolean(
            ARG_MODEL_SWITCH_AUTO_START,
            false,
        )
        val modelSwitchPlaying = arguments.optionalBoolean(
            ARG_MODEL_SWITCH_PLAYING,
            false,
        )
        val modelSwitchBoundary = arguments.getString(ARG_MODEL_SWITCH_BOUNDARY)
            ?: MODEL_SWITCH_BOUNDARY_READY
        require(modelSwitchBoundary == MODEL_SWITCH_BOUNDARY_READY ||
            modelSwitchBoundary == MODEL_SWITCH_BOUNDARY_PREPARATION
        ) { "Unsupported model-switch boundary: $modelSwitchBoundary" }
        require(executionHostMode == Phase7ExecutionHostMode.IndependentForeground) {
            "Model-switch validation requires the production independent foreground host."
        }
        val primaryModelId = arguments.requiredString(ARG_MODEL_ID)
        val primaryArtifactSha256 = arguments.requiredString(ARG_ARTIFACT_SHA256)
        val secondaryModelId = arguments.requiredString(ARG_SECONDARY_MODEL_ID)
        val secondaryArtifactSha256 = arguments.requiredString(
            ARG_SECONDARY_ARTIFACT_SHA256,
        )
        val primaryGlobalBlendKey = testGlobalBlendKey(primaryModelId)
        val secondaryGlobalBlendKey = testGlobalBlendKey(secondaryModelId)
        val report = baseReport(context, runId, arguments)
        val preferences = get<SharedPreferences>(SharedPreferences::class.java)
        val preferenceSnapshot = snapshotPreferences(
            preferences,
            setOf(
                SOURCE_SEPARATION_WINDOW_DECODE,
                SOURCE_SEPARATION_AUTO_FLAC_COMPRESSION,
                SOURCE_SEPARATION_AUTO_START,
                SOURCE_SEPARATION_GPU_ENABLED,
                SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT,
                MINIMUM_SONG_DURATION,
                TEST_PLAYBACK_ENABLED_KEY,
                TEST_REMEMBER_PER_SONG_KEY,
                primaryGlobalBlendKey,
                secondaryGlobalBlendKey,
            ),
        )
        val presetRepository = get<SourceSeparationPresetRepository>(
            SourceSeparationPresetRepository::class.java,
        )
        val coordinators = mutableListOf<SourceSeparationForegroundWorkerCoordinator>()
        var mediaUri: Uri? = null
        var runtimeFacade: SourceSeparationRuntimeFacade? = null
        var executionHost: BoundRemoteSourceSeparationExecutionHost? = null
        val cacheKeys = linkedSetOf<String>()
        val uiProgressSnapshots = JSONArray()
        var initialActiveReference: SourceSeparationActiveModelReference? = null
        var initialPendingReference: SourceSeparationActiveModelReference? = null
        var initialModelStateCaptured = false

        try {
            val sourcePath = arguments.requiredString(ARG_SOURCE_PATH)
            mediaUri = registerSourceInMediaStore(context, sourcePath, runId)
            val source = resolveMediaStoreSong(context, mediaUri, sourcePath)
            check(preferences.edit()
                .putBoolean(SOURCE_SEPARATION_WINDOW_DECODE, true)
                .putBoolean(SOURCE_SEPARATION_AUTO_FLAC_COMPRESSION, false)
                .putBoolean(SOURCE_SEPARATION_AUTO_START, modelSwitchAutoStart)
                .putSourceSeparationGpuEnabled(backendMode == BackendMode.Auto)
                .putInt(SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT, 1)
                .putInt(MINIMUM_SONG_DURATION, 0)
                .putBoolean(TEST_PLAYBACK_ENABLED_KEY, true)
                .putBoolean(TEST_REMEMBER_PER_SONG_KEY, false)
                .putFloat(primaryGlobalBlendKey, TEST_BLEND)
                .putFloat(secondaryGlobalBlendKey, TEST_BLEND)
                .commit()
            ) { "Could not persist model-switch test preferences." }

            val initial = presetRepository.activeModel()
            assertTrue(initial is SourceSeparationActivePresetState.Reference)
            val initialReference = (initial as SourceSeparationActivePresetState.Reference)
                .reference
            initialActiveReference = initialReference
            initialPendingReference = presetRepository.pendingActiveModel()
            initialModelStateCaptured = true
            assertEquals(
                primaryArtifactSha256,
                initialReference.artifactSha256,
            )

            val secondaryInstalled = get<SourceSeparationPresetDownloader>(
                SourceSeparationPresetDownloader::class.java,
            ).download(secondaryModelId)
            assertEquals(secondaryArtifactSha256, secondaryInstalled.sha256)
            assertEquals(primaryArtifactSha256, (presetRepository.activeModel() as
                SourceSeparationActivePresetState.Reference).reference.artifactSha256)

            val runtime = createCpuRuntimeFacade(
                context = context,
                preferences = preferences,
                presetRepository = presetRepository,
                backendMode = backendMode,
                processorCount = arguments.getString(ARG_PROCESSOR_COUNT)
                    ?.toIntOrNull()
                    ?.takeIf { it > 0 },
                executionHostMode = executionHostMode,
                boundRemoteHostSink = { executionHost = it },
            ).also { runtimeFacade = it }
            val primaryRuntimeSong = (runtime.resolve(source) as?
                SourceSeparationRuntimeSongResolution.Ready)?.song
                ?: error("The primary model could not resolve the switching source.")
            assertEquals(primaryModelId, primaryRuntimeSong.modelId)
            cacheKeys += primaryRuntimeSong.cacheKey
            clearExactCacheEntry(runtime, primaryRuntimeSong.cacheKey)
            presetRepository.activate(
                sha256 = secondaryArtifactSha256,
                platform = AndroidMdxRuntimePlatformProvider.current(),
                scope = SourceSeparationPresetSelectionScope.InternalValidation,
                experimentalConfirmed = true,
            )
            val preparedSecondaryRuntimeSong = (runtime.resolve(source) as?
                SourceSeparationRuntimeSongResolution.Ready)?.song
                ?: error("The secondary model could not be prepared for switching.")
            cacheKeys += preparedSecondaryRuntimeSong.cacheKey
            clearExactCacheEntry(runtime, preparedSecondaryRuntimeSong.cacheKey)
            presetRepository.activate(
                sha256 = primaryArtifactSha256,
                platform = AndroidMdxRuntimePlatformProvider.current(),
                scope = SourceSeparationPresetSelectionScope.InternalValidation,
                experimentalConfirmed = true,
            )
            assertEquals(
                primaryRuntimeSong.cacheKey,
                (runtime.resolve(source) as? SourceSeparationRuntimeSongResolution.Ready)
                    ?.song?.cacheKey,
            )
            val cacheRepository = get<SourceSeparationModelAwareCacheRepository>(
                SourceSeparationModelAwareCacheRepository::class.java,
            )
            val store = get<SourceSeparationCacheStore>(SourceSeparationCacheStore::class.java)

            val startedAtMs = SystemClock.elapsedRealtime()
            val worker = SourceSeparationForegroundWorkerCoordinator(
                context = context,
                preferences = preferences,
                sourceSeparationRuntime = runtime,
                activeSelectionFlow = presetRepository.activeSelectionFlow,
            )
            coordinators += worker
            worker.attachCallbacks(RecordingCallbacks())

            fun captureCurrentProgress(
                label: String,
                expectedCacheKey: String,
                requireReadyWindow: Boolean = true,
            ): SourceSeparationUiState.Running {
                val state = worker.workerStateFlow.value as? SourceSeparationUiState.Running
                    ?: error("$label did not publish running UI progress: " +
                        worker.workerStateFlow.value)
                val activeSelection = presetRepository.activeSelectionFlow.value
                assertEquals(activeSelection.generation, state.selectionGeneration)
                assertEquals(expectedCacheKey, state.cacheKey)
                if (requireReadyWindow) {
                    assertTrue(state.totalWindows > 0)
                    assertTrue(
                        (state.scheduler?.playbackReadyWindowReadyCount ?: 0) > 0,
                    )
                }
                uiProgressSnapshots.put(JSONObject()
                    .put("label", label)
                    .put("selectionGeneration", state.selectionGeneration)
                    .put("cacheKey", state.cacheKey)
                    .put("completedWindows", state.completedWindows)
                    .put("totalWindows", state.totalWindows)
                    .put("percent", state.percent)
                    .put(
                        "playbackReadyWindows",
                        state.scheduler?.playbackReadyWindowReadyCount ?: 0,
                    )
                )
                return state
            }

            worker.updateSong(
                song = source,
                positionMs = 0L,
                durationMs = source.duration,
                isPlaying = modelSwitchPlaying,
                sourceSeparationBlend = TEST_BLEND,
            )
            assertTrue(worker.startCurrentSong())
            val primaryInitialJournal = when (modelSwitchBoundary) {
                MODEL_SWITCH_BOUNDARY_PREPARATION -> waitForRunJournal(
                    store = store,
                    cacheKey = primaryRuntimeSong.cacheKey,
                    lifecycle = SourceSeparationCacheRunJournalLifecycle.Running,
                ).also { journal ->
                    assertTrue(
                        "Preparation-boundary switch started after a segment commit.",
                        journal.committedSegments.isEmpty(),
                    )
                }
                else -> {
                    waitForReady(
                        worker,
                        minimumReadyWindows = 1,
                        expectedCacheKey = primaryRuntimeSong.cacheKey,
                    )
                    requireNotNull(store.readRunJournal(primaryRuntimeSong.cacheKey))
                        .also { journal ->
                            assertTrue(journal.committedSegments.isNotEmpty())
                        }
                }
            }
            val primaryInitialProgress = captureCurrentProgress(
                label = "primary-before-switch",
                expectedCacheKey = primaryRuntimeSong.cacheKey,
                requireReadyWindow = modelSwitchBoundary == MODEL_SWITCH_BOUNDARY_READY,
            )
            assertEquals(primaryRuntimeSong.cacheKey, worker.runningCacheKey())
            val primaryReadySegmentCount = primaryInitialJournal.committedSegments.size

            val selectedSecondary = presetRepository.activate(
                sha256 = secondaryArtifactSha256,
                platform = AndroidMdxRuntimePlatformProvider.current(),
                scope = SourceSeparationPresetSelectionScope.InternalValidation,
                experimentalConfirmed = true,
            )
            assertEquals(secondaryModelId, selectedSecondary.modelId)
            val primaryPausedJournal = waitForRunJournal(
                store = store,
                cacheKey = primaryRuntimeSong.cacheKey,
                lifecycle = SourceSeparationCacheRunJournalLifecycle.Paused,
                transition = SourceSeparationCacheRunTransitionType.ActiveModelSuperseded,
            )
            waitForCacheLeaseRelease(cacheRepository, primaryRuntimeSong.cacheKey)
            assertTrue(primaryPausedJournal.committedSegments.size >= primaryReadySegmentCount)
            val primaryPartial = runtime.cacheStatus(primaryRuntimeSong) as?
                SourceSeparationModelAwareCacheStatus.Incomplete
                ?: error("The superseded primary run did not retain a partial cache.")
            assertEquals(SourceSeparationCacheManifestState.Partial, primaryPartial.manifest.state)
            assertEquals(primaryModelId, primaryPartial.manifest.identity.modelId)

            val secondaryRuntimeSong = (runtime.resolve(source) as?
                SourceSeparationRuntimeSongResolution.Ready)?.song
                ?: error("The secondary model could not resolve the switching source.")
            assertEquals(secondaryModelId, secondaryRuntimeSong.modelId)
            assertEquals(secondaryArtifactSha256, secondaryRuntimeSong.artifactSha256)
            assertNotEquals(primaryRuntimeSong.cacheKey, secondaryRuntimeSong.cacheKey)
            assertEquals(preparedSecondaryRuntimeSong.cacheKey, secondaryRuntimeSong.cacheKey)
            cacheKeys += secondaryRuntimeSong.cacheKey
            if (!modelSwitchAutoStart) {
                clearExactCacheEntry(runtime, secondaryRuntimeSong.cacheKey)
            }

            if (!modelSwitchAutoStart) {
                worker.requestManualSong(source)
            }
            waitForReady(
                worker,
                minimumReadyWindows = 1,
                expectedCacheKey = secondaryRuntimeSong.cacheKey,
            )
            val secondaryProgress = captureCurrentProgress(
                label = "secondary-after-switch",
                expectedCacheKey = secondaryRuntimeSong.cacheKey,
            )
            assertEquals(secondaryRuntimeSong.cacheKey, worker.runningCacheKey())
            val secondaryReadyJournal = requireNotNull(
                store.readRunJournal(secondaryRuntimeSong.cacheKey),
            )
            assertTrue(secondaryReadyJournal.committedSegments.isNotEmpty())

            val selectedPrimary = presetRepository.activate(
                sha256 = primaryArtifactSha256,
                platform = AndroidMdxRuntimePlatformProvider.current(),
                scope = SourceSeparationPresetSelectionScope.InternalValidation,
                experimentalConfirmed = true,
            )
            assertEquals(primaryModelId, selectedPrimary.modelId)
            val secondaryAfterSwitchJournal = waitForSupersededOrCompletedRunJournal(
                store = store,
                cacheKey = secondaryRuntimeSong.cacheKey,
            )
            waitForCacheLeaseRelease(cacheRepository, secondaryRuntimeSong.cacheKey)
            val secondaryWasSuperseded = secondaryAfterSwitchJournal.lifecycle ==
                SourceSeparationCacheRunJournalLifecycle.Paused
            val secondaryRetainedManifest = when (
                val status = runtime.cacheStatus(secondaryRuntimeSong)
            ) {
                is SourceSeparationModelAwareCacheStatus.Incomplete -> {
                    assertTrue(secondaryWasSuperseded)
                    status.manifest
                }
                is SourceSeparationModelAwareCacheStatus.Completed -> {
                    assertFalse(secondaryWasSuperseded)
                    status.manifest
                }
                else -> error("The secondary run was not retained after switching: $status")
            }
            assertEquals(secondaryModelId, secondaryRetainedManifest.identity.modelId)

            val resumedPrimary = (runtime.resolve(source) as?
                SourceSeparationRuntimeSongResolution.Ready)?.song
                ?: error("The reactivated primary model could not resolve the switching source.")
            assertEquals(primaryRuntimeSong.cacheKey, resumedPrimary.cacheKey)
            assertTrue(
                requireNotNull(store.readRunJournal(resumedPrimary.cacheKey))
                    .committedSegments.size >= primaryReadySegmentCount,
            )
            if (!modelSwitchAutoStart) {
                worker.requestManualSong(source)
            }
            waitForCompleted(worker, expectedCacheKey = resumedPrimary.cacheKey)
            val primaryCompletedState = worker.workerStateFlow.value as?
                SourceSeparationUiState.Completed
                ?: error("The reactivated primary did not publish completed UI state.")
            val resumedSelection = presetRepository.activeSelectionFlow.value
            assertEquals(resumedSelection.generation, primaryCompletedState.selectionGeneration)
            assertEquals(resumedPrimary.cacheKey, primaryCompletedState.cacheKey)
            uiProgressSnapshots.put(JSONObject()
                .put("label", "primary-after-round-trip")
                .put("selectionGeneration", primaryCompletedState.selectionGeneration)
                .put("cacheKey", primaryCompletedState.cacheKey)
                .put("terminal", "Completed")
            )
            val primaryCompleted = runtime.cacheStatus(resumedPrimary) as?
                SourceSeparationModelAwareCacheStatus.Completed
                ?: error("The reactivated primary cache did not resume to completion.")
            assertEquals(primaryModelId, primaryCompleted.manifest.identity.modelId)
            assertEquals(primaryArtifactSha256,
                primaryCompleted.manifest.identity.artifactSha256)
            val primaryCompletedJournal = waitForRunJournal(
                store = store,
                cacheKey = resumedPrimary.cacheKey,
                lifecycle = SourceSeparationCacheRunJournalLifecycle.Completed,
            )
            worker.cancel()
            waitForInactive(worker)

            val primaryBackend = primaryCompleted.manifest.runtimeRecords.last().backend
            if (backendMode == BackendMode.Cpu) {
                assertEquals(MdxInferenceBackend.LiteRtCpu.name, primaryBackend)
                assertFalse(secondaryAfterSwitchJournal.request.tryGpu)
            } else {
                assertTrue(secondaryAfterSwitchJournal.request.tryGpu)
            }

            val primaryPlayback = requireNotNull(
                runtime.openCompletedCache(primaryRuntimeSong.cacheKey),
            )
            assertTrue(cacheRepository.isLeased(primaryRuntimeSong.cacheKey))
            assertEquals(
                SourceSeparationCacheMutationResult.Busy,
                runtime.delete(primaryRuntimeSong.cacheKey),
            )
            primaryPlayback.close()
            assertFalse(cacheRepository.isLeased(primaryRuntimeSong.cacheKey))

            val entries = runtime.entries().filter {
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
                .put("primaryBackend", primaryBackend)
                .put("secondaryTryGpu", secondaryAfterSwitchJournal.request.tryGpu)
                .put("autoStart", modelSwitchAutoStart)
                .put("playbackPlaying", modelSwitchPlaying)
                .put("initialSwitchBoundary", modelSwitchBoundary)
                .put("primaryInitialSelectionGeneration",
                    primaryInitialProgress.selectionGeneration)
                .put("secondarySelectionGeneration", secondaryProgress.selectionGeneration)
                .put("resumedPrimarySelectionGeneration",
                    primaryCompletedState.selectionGeneration)
                .put("primaryReadySegmentsBeforeSwitch", primaryReadySegmentCount)
                .put("primaryPausedSegments", primaryPausedJournal.committedSegments.size)
                .put(
                    "secondaryPausedSegments",
                    secondaryAfterSwitchJournal.committedSegments.size.takeIf {
                        secondaryWasSuperseded
                    } ?: JSONObject.NULL,
                )
                .put("secondaryRetainedSegments",
                    secondaryAfterSwitchJournal.committedSegments.size)
                .put("secondaryLifecycleAfterSwitch",
                    secondaryAfterSwitchJournal.lifecycle.name)
                .put("primaryCompletedSegments", primaryCompletedJournal.committedSegments.size)
                .put("primarySupersededTransition", true)
                .put("secondarySupersededTransition", secondaryWasSuperseded)
                .put("activeModelRoundTrip", true)
                .put("primaryPlaybackLeaseRetained", true)
                .put("secondaryContractId", arguments.requiredString(ARG_SECONDARY_CONTRACT_ID))
                .put("uiProgressSnapshots", uiProgressSnapshots)
            )
        } catch (error: Throwable) {
            report.put("status", "failed")
            report.put("error", "${error::class.java.name}: ${error.message}")
            throw error
        } finally {
            coordinators.forEach { coordinator ->
                coordinator.cancel()
                runCatching { waitForInactive(coordinator) }
            }
            if (initialModelStateCaptured) {
                initialActiveReference?.let { reference ->
                    runCatching {
                        restoreActiveModelSelection(
                            repository = presetRepository,
                            activeReference = reference,
                            pendingReference = initialPendingReference,
                        )
                    }.onFailure { restoreError ->
                        report.put("stateRestoreError",
                            "${restoreError::class.java.name}: ${restoreError.message}")
                    }
                }
            }
            runtimeFacade?.let { runtime ->
                cacheKeys.forEach { cacheKey ->
                    runCatching { clearExactCacheEntry(runtime, cacheKey) }
                }
            }
            executionHost?.close()
            restorePreferences(preferences, preferenceSnapshot)
            if (!arguments.optionalBoolean(ARG_PRESERVE_MEDIA_STORE_SOURCE, false)) {
                mediaUri?.let { uri ->
                    runCatching { context.contentResolver.delete(uri, null, null) }
                }
            }
            writeReport(context, runId, "switching", report)
        }
    }

    @Test
    fun validateProductCacheManagement() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val runId = arguments.requiredString(ARG_RUN_ID).requireSafeName()
        val backendMode = BackendMode.parse(arguments.getString(ARG_BACKEND_MODE))
        val executionHostMode = Phase7ExecutionHostMode.parse(
            arguments.getString(ARG_EXECUTION_HOST_MODE),
        )
        require(executionHostMode == Phase7ExecutionHostMode.IndependentForeground) {
            "Cache-management validation requires the product independent host."
        }
        val report = baseReport(context, runId, arguments)
        val preferences = get<SharedPreferences>(SharedPreferences::class.java)
        val preferenceSnapshot = snapshotPreferences(
            preferences,
            setOf(
                SOURCE_SEPARATION_WINDOW_DECODE,
                SOURCE_SEPARATION_AUTO_FLAC_COMPRESSION,
                SOURCE_SEPARATION_AUTO_START,
                SOURCE_SEPARATION_AUTO_CACHE_CLEANUP,
                SOURCE_SEPARATION_AUTO_CACHE_CLEANUP_PARTIAL_LIMIT,
                SOURCE_SEPARATION_AUTO_CACHE_CLEANUP_COMPLETED_LIMIT,
                SOURCE_SEPARATION_GPU_ENABLED,
                SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT,
                MINIMUM_SONG_DURATION,
                TEST_PLAYBACK_ENABLED_KEY,
                TEST_REMEMBER_PER_SONG_KEY,
            ),
        )
        val presetRepository = get<SourceSeparationPresetRepository>(
            SourceSeparationPresetRepository::class.java,
        )
        var mediaUri: Uri? = null
        var runtimeFacade: SourceSeparationRuntimeFacade? = null
        var executionHost: BoundRemoteSourceSeparationExecutionHost? = null
        var worker: SourceSeparationForegroundWorkerCoordinator? = null
        val cacheKeys = linkedSetOf<String>()
        var initialActiveReference: SourceSeparationActiveModelReference? = null
        var initialPendingReference: SourceSeparationActiveModelReference? = null
        var initialModelStateCaptured = false

        try {
            val sourcePath = arguments.requiredString(ARG_SOURCE_PATH)
            mediaUri = registerSourceInMediaStore(context, sourcePath, runId)
            val source = resolveMediaStoreSong(context, mediaUri, sourcePath)
            check(preferences.edit()
                .putBoolean(SOURCE_SEPARATION_WINDOW_DECODE, true)
                .putBoolean(SOURCE_SEPARATION_AUTO_FLAC_COMPRESSION, false)
                .putBoolean(SOURCE_SEPARATION_AUTO_START, false)
                .putBoolean(SOURCE_SEPARATION_AUTO_CACHE_CLEANUP, false)
                .putInt(SOURCE_SEPARATION_AUTO_CACHE_CLEANUP_PARTIAL_LIMIT, 1)
                .putInt(SOURCE_SEPARATION_AUTO_CACHE_CLEANUP_COMPLETED_LIMIT, 1)
                .putSourceSeparationGpuEnabled(backendMode == BackendMode.Auto)
                .putInt(SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT, 1)
                .putInt(MINIMUM_SONG_DURATION, 0)
                .putBoolean(TEST_PLAYBACK_ENABLED_KEY, true)
                .putBoolean(TEST_REMEMBER_PER_SONG_KEY, false)
                .commit()
            ) { "Could not persist cache-management test preferences." }

            val primaryModelId = arguments.requiredString(ARG_MODEL_ID)
            val primaryArtifactSha256 = arguments.requiredString(ARG_ARTIFACT_SHA256)
            val secondaryModelId = arguments.requiredString(ARG_SECONDARY_MODEL_ID)
            val secondaryArtifactSha256 = arguments.requiredString(
                ARG_SECONDARY_ARTIFACT_SHA256,
            )
            val initial = presetRepository.activeModel()
            assertTrue(initial is SourceSeparationActivePresetState.Reference)
            initialActiveReference = (initial as SourceSeparationActivePresetState.Reference)
                .reference
            initialPendingReference = presetRepository.pendingActiveModel()
            initialModelStateCaptured = true
            assertEquals(primaryArtifactSha256, initialActiveReference.artifactSha256)

            val secondaryInstalled = get<SourceSeparationPresetDownloader>(
                SourceSeparationPresetDownloader::class.java,
            ).download(secondaryModelId)
            assertEquals(secondaryArtifactSha256, secondaryInstalled.sha256)

            val runtime = createCpuRuntimeFacade(
                context = context,
                preferences = preferences,
                presetRepository = presetRepository,
                backendMode = backendMode,
                processorCount = arguments.getString(ARG_PROCESSOR_COUNT)
                    ?.toIntOrNull()
                    ?.takeIf { it > 0 },
                executionHostMode = executionHostMode,
                boundRemoteHostSink = { executionHost = it },
            ).also { runtimeFacade = it }
            val coordinator = SourceSeparationForegroundWorkerCoordinator(
                context = context,
                preferences = preferences,
                sourceSeparationRuntime = runtime,
                activeSelectionFlow = presetRepository.activeSelectionFlow,
            ).also { worker = it }
            coordinator.attachCallbacks(RecordingCallbacks())
            coordinator.updateSong(
                song = source,
                positionMs = 0L,
                durationMs = source.duration,
                isPlaying = false,
                sourceSeparationBlend = TEST_BLEND,
            )

            fun resolveActiveSong(): SourceSeparationRuntimeSong =
                (runtime.resolve(source) as? SourceSeparationRuntimeSongResolution.Ready)?.song
                    ?: error("The active model could not resolve the cache-management source.")

            fun completeActiveSong(expectedModelId: String): SourceSeparationRuntimeSong {
                val runtimeSong = resolveActiveSong()
                assertEquals(expectedModelId, runtimeSong.modelId)
                cacheKeys += runtimeSong.cacheKey
                coordinator.requestManualSong(source)
                waitForCompleted(coordinator)
                coordinator.cancel()
                waitForInactive(coordinator)
                assertTrue(
                    runtime.cacheStatus(runtimeSong) is SourceSeparationModelAwareCacheStatus.Completed,
                )
                return runtimeSong
            }

            val primarySong = resolveActiveSong()
            assertEquals(primaryModelId, primarySong.modelId)
            cacheKeys += primarySong.cacheKey
            clearExactCacheEntry(runtime, primarySong.cacheKey)
            completeActiveSong(primaryModelId)
            val primaryCompleted = runtime.cacheStatus(primarySong) as?
                SourceSeparationModelAwareCacheStatus.Completed
                ?: error("The primary cache was not completed after its worker run.")
            applyRuntimeEvidence(report, primaryCompleted.manifest)

            presetRepository.activate(
                sha256 = secondaryArtifactSha256,
                platform = AndroidMdxRuntimePlatformProvider.current(),
                scope = SourceSeparationPresetSelectionScope.InternalValidation,
                experimentalConfirmed = true,
            )
            val secondarySong = resolveActiveSong()
            cacheKeys += secondarySong.cacheKey
            clearExactCacheEntry(runtime, secondarySong.cacheKey)
            completeActiveSong(secondaryModelId)

            presetRepository.activate(
                sha256 = primaryArtifactSha256,
                platform = AndroidMdxRuntimePlatformProvider.current(),
                scope = SourceSeparationPresetSelectionScope.InternalValidation,
                experimentalConfirmed = true,
            )
            assertFalse(
                runBlocking {
                    coordinator.cancelForCacheDeletion(
                        cacheKey = secondarySong.cacheKey,
                        preflightIdentity = null,
                    )
                },
            )
            assertEquals(
                SourceSeparationCacheMutationResult.Completed,
                runtime.delete(secondarySong.cacheKey),
            )
            assertTrue(
                runtime.cacheStatus(primarySong) is SourceSeparationModelAwareCacheStatus.Completed,
            )
            assertTrue(runtime.cacheStatus(secondarySong) is
                SourceSeparationModelAwareCacheStatus.Missing)

            presetRepository.activate(
                sha256 = secondaryArtifactSha256,
                platform = AndroidMdxRuntimePlatformProvider.current(),
                scope = SourceSeparationPresetSelectionScope.InternalValidation,
                experimentalConfirmed = true,
            )
            completeActiveSong(secondaryModelId)
            presetRepository.activate(
                sha256 = primaryArtifactSha256,
                platform = AndroidMdxRuntimePlatformProvider.current(),
                scope = SourceSeparationPresetSelectionScope.InternalValidation,
                experimentalConfirmed = true,
            )
            SystemClock.sleep(POLL_INTERVAL_MS)
            requireNotNull(runtime.openCompletedCache(primarySong.cacheKey)).close()
            coordinator.updateSong(
                song = source,
                positionMs = 0L,
                durationMs = source.duration,
                isPlaying = true,
                sourceSeparationBlend = TEST_BLEND,
            )
            check(preferences.edit()
                .putBoolean(SOURCE_SEPARATION_AUTO_CACHE_CLEANUP, true)
                .commit()
            ) { "Could not enable automatic cache pruning." }
            coordinator.requestAutomaticPrune()
            val pruneDeadline = SystemClock.elapsedRealtime() + CACHE_MANAGEMENT_TIMEOUT_MS
            while (runtime.entries().any { it.cacheKey == secondarySong.cacheKey } &&
                SystemClock.elapsedRealtime() < pruneDeadline
            ) {
                SystemClock.sleep(POLL_INTERVAL_MS)
            }
            assertFalse(runtime.entries().any { it.cacheKey == secondarySong.cacheKey })
            assertTrue(runtime.entries().any { it.cacheKey == primarySong.cacheKey })

            assertEquals(
                SourceSeparationCacheMutationResult.Completed,
                runtime.delete(primarySong.cacheKey),
            )
            coordinator.requestManualSong(source)
            waitForReady(coordinator, minimumReadyWindows = 1)
            assertEquals(primarySong.cacheKey, coordinator.runningCacheKey())
            val currentReadySegments = get<SourceSeparationCacheStore>(
                SourceSeparationCacheStore::class.java,
            ).readRunJournal(primarySong.cacheKey)?.committedSegments?.size ?: 0
            assertTrue(currentReadySegments > 0)
            val preflightIdentity = SourceSeparationWorkerRequestIdentity.from(
                source,
                presetRepository.activeSelectionFlow.value,
            )
            coordinator.suppressAndPauseSong(source.id)
            assertTrue(
                runBlocking {
                    coordinator.cancelForCacheDeletion(
                        cacheKey = primarySong.cacheKey,
                        preflightIdentity = preflightIdentity,
                    )
                },
            )
            assertEquals(
                SourceSeparationCacheMutationResult.Completed,
                runtime.delete(primarySong.cacheKey),
            )
            SystemClock.sleep(POLL_INTERVAL_MS * 3)
            assertTrue(runtime.cacheStatus(primarySong) is
                SourceSeparationModelAwareCacheStatus.Missing)
            assertEquals(null, coordinator.runningCacheKey())
            assertEquals(null, coordinator.pendingSongId())

            report.put("status", "passed")
            report.put("cacheManagement", JSONObject()
                .put("primaryModelId", primaryModelId)
                .put("primaryCacheKey", primarySong.cacheKey)
                .put("secondaryModelId", secondaryModelId)
                .put("secondaryCacheKey", secondarySong.cacheKey)
                .put("inactiveDeletePreservedCurrent", true)
                .put("automaticPruneDeletedInactive", true)
                .put("automaticPrunePreservedExactCurrent", true)
                .put("currentRunningDeleteCanceledProducer", true)
                .put("currentReadySegmentsBeforeDelete", currentReadySegments)
                .put("currentDeleteStayedMissing", true)
            )
        } catch (error: Throwable) {
            report.put("status", "failed")
            report.put("error", "${error::class.java.name}: ${error.message}")
            throw error
        } finally {
            worker?.cancel()
            worker?.let { coordinator -> runCatching { waitForInactive(coordinator) } }
            if (initialModelStateCaptured) {
                initialActiveReference?.let { reference ->
                    runCatching {
                        restoreActiveModelSelection(
                            repository = presetRepository,
                            activeReference = reference,
                            pendingReference = initialPendingReference,
                        )
                    }.onFailure { restoreError ->
                        report.put(
                            "stateRestoreError",
                            "${restoreError::class.java.name}: ${restoreError.message}",
                        )
                    }
                }
            }
            runtimeFacade?.let { runtime ->
                cacheKeys.forEach { cacheKey ->
                    runCatching { clearExactCacheEntry(runtime, cacheKey) }
                }
            }
            executionHost?.close()
            restorePreferences(preferences, preferenceSnapshot)
            mediaUri?.let { uri ->
                runCatching { context.contentResolver.delete(uri, null, null) }
            }
            writeReport(context, runId, "cache-management", report)
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
        val backendMode = BackendMode.parse(arguments.getString(ARG_BACKEND_MODE))
        val executionHostMode = Phase7ExecutionHostMode.parse(
            arguments.getString(ARG_EXECUTION_HOST_MODE),
        )
        require(executionHostMode == Phase7ExecutionHostMode.IndependentForeground) {
            "Next-song prefetch validation requires the production independent foreground host."
        }
        val globalBlendKey = testGlobalBlendKey(arguments.requiredString(ARG_MODEL_ID))
        val report = baseReport(context, runId, arguments)
        val preferences = get<SharedPreferences>(SharedPreferences::class.java)
        val preferenceSnapshot = snapshotPreferences(
            preferences,
            setOf(
                SOURCE_SEPARATION_WINDOW_DECODE,
                SOURCE_SEPARATION_AUTO_FLAC_COMPRESSION,
                SOURCE_SEPARATION_AUTO_START,
                SOURCE_SEPARATION_GPU_ENABLED,
                TEST_KEY_PLAYBACK_ENABLED,
                TEST_KEY_REMEMBER_PER_SONG,
                globalBlendKey,
                SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT,
                MINIMUM_SONG_DURATION,
            ),
        )
        var currentUri: Uri? = null
        var nextUri: Uri? = null
        var worker: SourceSeparationForegroundWorkerCoordinator? = null
        var runtimeFacade: SourceSeparationRuntimeFacade? = null
        val cacheKeys = linkedSetOf<String>()

        try {
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

            check(preferences.edit()
                .putBoolean(SOURCE_SEPARATION_WINDOW_DECODE, true)
                .putBoolean(SOURCE_SEPARATION_AUTO_FLAC_COMPRESSION, false)
                .putBoolean(SOURCE_SEPARATION_AUTO_START, true)
                .putSourceSeparationGpuEnabled(backendMode == BackendMode.Auto)
                .putBoolean(TEST_KEY_PLAYBACK_ENABLED, true)
                .putBoolean(TEST_KEY_REMEMBER_PER_SONG, false)
                .putFloat(globalBlendKey, TEST_BLEND)
                .putInt(
                    SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT,
                    REQUIRED_READY_WINDOWS,
                )
                .putInt(MINIMUM_SONG_DURATION, 0)
                .commit()
            ) { "Could not persist next-song prefetch test preferences." }
            assertExpectedActivePreset(arguments)
            val runtime = get<SourceSeparationRuntimeFacade>(
                SourceSeparationRuntimeFacade::class.java,
            ).also { runtimeFacade = it }
            val currentRuntimeSong = (runtime.resolve(currentSource) as?
                SourceSeparationRuntimeSongResolution.Ready)?.song
                ?: error("The current prefetch source could not be resolved.")
            val nextRuntimeSong = (runtime.resolve(nextSource) as?
                SourceSeparationRuntimeSongResolution.Ready)?.song
                ?: error("The next prefetch source could not be resolved.")
            assertNotEquals(
                "Prefetch validation requires different current and next audio identities.",
                currentRuntimeSong.cacheKey,
                nextRuntimeSong.cacheKey,
            )
            cacheKeys += currentRuntimeSong.cacheKey
            cacheKeys += nextRuntimeSong.cacheKey
            clearExactCacheEntry(runtime, currentRuntimeSong.cacheKey)
            clearExactCacheEntry(runtime, nextRuntimeSong.cacheKey)

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
            assertTrue(coordinator.startCurrentSong())
            waitForCompleted(coordinator)
            val currentManifest = (runtime.cacheStatus(currentRuntimeSong) as?
                SourceSeparationModelAwareCacheStatus.Completed)?.manifest
                ?: error("The current source did not complete before prefetch.")

            val startedAtMs = SystemClock.elapsedRealtime()
            assertTrue(
                "The uncached next song was not admitted for prefetch.",
                coordinator.preStartSong(nextSource, REQUIRED_READY_WINDOWS),
            )
            val prefetchedPlayback = waitForPlayable(
                runtimeFacade = runtime,
                song = nextRuntimeSong,
                playbackPositionMs = 0L,
                readyWindowCount = REQUIRED_READY_WINDOWS,
            )
            prefetchedPlayback.playback.close()
            val firstReadyMs = SystemClock.elapsedRealtime() - startedAtMs
            waitForWorkerToLeaveSong(coordinator, nextSource.id)
            val prefetchStatus = runtime.cacheStatus(nextRuntimeSong)
            val (prefetchedManifest, prefetchedReadyWindows, stoppedBeforeCompletion) =
                when (prefetchStatus) {
                    is SourceSeparationModelAwareCacheStatus.Incomplete -> {
                        assertTrue(prefetchStatus.readySegments >= REQUIRED_READY_WINDOWS)
                        Triple(prefetchStatus.manifest, prefetchStatus.readySegments, true)
                    }
                    is SourceSeparationModelAwareCacheStatus.Completed -> Triple(
                        prefetchStatus.manifest,
                        prefetchStatus.manifest.segmentPlan?.segments?.size
                            ?: REQUIRED_READY_WINDOWS,
                        false,
                    )
                    else -> error(
                        "Next-song prefetch did not retain a ready cache: $prefetchStatus",
                    )
                }
            val prefetchCacheKey = prefetchedManifest.cacheKey

            coordinator.updateSong(
                song = nextSource,
                positionMs = 0L,
                durationMs = nextSource.duration,
                isPlaying = false,
                sourceSeparationBlend = TEST_BLEND,
            )
            coordinator.requestPlaybackDemandSong(nextSource)
            waitForCompleted(coordinator)
            val transitioned = runtime.cacheStatus(nextRuntimeSong) as?
                SourceSeparationModelAwareCacheStatus.Completed
                ?: error("The prefetched next song did not complete after transition.")
            assertEquals(prefetchCacheKey, transitioned.manifest.cacheKey)
            assertEquals(
                SourceSeparationCacheManifestState.Completed,
                currentManifest.state,
            )
            assertTrue(
                runtime.cacheStatus(currentRuntimeSong) is
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
                .put("prefetchedReadyWindows", prefetchedReadyWindows)
                .put("stoppedBeforeCompletion", stoppedBeforeCompletion)
                .put("transitionRetainedCacheIdentity", true)
            )
        } catch (error: Throwable) {
            report.put("status", "failed")
            report.put("error", "${error::class.java.name}: ${error.message}")
            throw error
        } finally {
            worker?.let { coordinator ->
                coordinator.cancel()
                runCatching { waitForInactive(coordinator) }
            }
            runtimeFacade?.let { runtime ->
                cacheKeys.forEach { cacheKey ->
                    runCatching { clearExactCacheEntry(runtime, cacheKey) }
                }
            }
            restorePreferences(preferences, preferenceSnapshot)
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
                    it.semanticId.value
                })
            )
            report.put("cache", report.getJSONObject("cache")
                .put("cacheKey", runtimeSong.cacheKey)
                .put("exactIdentity", true)
                .put("completedPlayable", true)
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
        val playbackSoakMinutes = arguments.getString(ARG_PLAYBACK_SOAK_MINUTES)
            ?.toIntOrNull() ?: 0
        val playbackSoakSeekCount = arguments.getString(ARG_PLAYBACK_SOAK_SEEK_COUNT)
            ?.toIntOrNull() ?: 0
        require(playbackSoakMinutes in 0..MAX_PLAYBACK_SOAK_MINUTES)
        require(playbackSoakSeekCount in 0..MAX_PLAYBACK_SOAK_SEEK_COUNT)
        require((playbackSoakMinutes == 0) == (playbackSoakSeekCount == 0)) {
            "Playback soak duration and seek count must both be zero or positive."
        }
        val preservePlaybackCache = arguments.optionalBoolean(
            ARG_PRESERVE_PLAYBACK_CACHE,
            false,
        )
        val requestedSecondaryModelId = arguments.getString(ARG_SECONDARY_MODEL_ID)
            ?.takeIf(String::isNotBlank)
        val preferences = get<SharedPreferences>(SharedPreferences::class.java)
        val preferenceSnapshot = snapshotPreferences(
            preferences,
            setOf(MINIMUM_SONG_DURATION, SOURCE_SEPARATION_AUTO_START),
        )
        val presetRepository = get<SourceSeparationPresetRepository>(
            SourceSeparationPresetRepository::class.java,
        )
        val initialActiveReference = (presetRepository.activeModel() as?
            SourceSeparationActivePresetState.Reference)?.reference
            ?: error("The playback validation has no active model.")
        val initialPendingReference = presetRepository.pendingActiveModel()
        val runtimeFacade = get<SourceSeparationRuntimeFacade>(
            SourceSeparationRuntimeFacade::class.java,
        )
        val cacheRepository = get<SourceSeparationModelAwareCacheRepository>(
            SourceSeparationModelAwareCacheRepository::class.java,
        )
        val audioFocusOverride = installPlaybackAudioFocusTestOverride()
        var controller: MediaController? = null
        var sourceUri: Uri? = null
        var testedCacheKey: String? = null
        var switchedModelDuringPlayback = false
        var cacheLeaseReleaseMs = 0L
        var playbackSoakReport: JSONObject? = null

        try {
            val cacheKey = arguments.requiredString(ARG_CACHE_KEY)
            testedCacheKey = cacheKey
            val expectedArtifactSha256 = arguments.requiredString(ARG_ARTIFACT_SHA256)
            val store = get<SourceSeparationCacheStore>(SourceSeparationCacheStore::class.java)
            val preferenceEditor = preferences.edit()
                .putInt(MINIMUM_SONG_DURATION, 0)
            if (requestedSecondaryModelId != null) {
                preferenceEditor.putBoolean(SOURCE_SEPARATION_AUTO_START, false)
            }
            check(preferenceEditor.commit()
            ) { "Could not allow the Phase 7 playback fixture in the media library." }
            val secondaryModelId = requestedSecondaryModelId
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
            val restorationCommand = SessionCommand(
                Playback.AWAIT_PLAYBACK_RESTORATION,
                Bundle.EMPTY,
            )
            onMediaControllerThread(mediaController) {
                mediaController.sendCustomCommand(restorationCommand, Bundle.EMPTY)
            }.get(MEDIA_SESSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            onMediaControllerThread(mediaController) {
                mediaController.setMediaItem(
                    MediaItem.Builder()
                        .setMediaId(manifest.song.songId.toString())
                        .setUri(manifest.song.mediaUri)
                        .build(),
                )
                mediaController.prepare()
            }
            val playResultFuture = onMediaControllerThread(mediaController) {
                mediaController.sendCustomCommand(
                    SessionCommand(
                        Playback.SET_SOURCE_SEPARATION_PLAYBACK_ENABLED,
                        Bundle.EMPTY,
                    ),
                    Bundle().apply {
                        putBoolean(Playback.EXTRA_SOURCE_SEPARATION_ENABLED, true)
                        putBoolean(Playback.EXTRA_SOURCE_SEPARATION_SHOW_MESSAGE, false)
                        putFloat(Playback.EXTRA_SOURCE_SEPARATION_BLEND, 0.7f)
                    },
                )
            }
            val playResult = playResultFuture.get(
                MEDIA_SESSION_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
            )
            assertEquals(
                "PlaybackService rejected the active completed cache: " +
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

            if (playbackSoakMinutes > 0) {
                playbackSoakReport = runMediaSessionPlaybackSoak(
                    context = context,
                    controller = mediaController,
                    store = store,
                    cacheKey = cacheKey,
                    durationMinutes = playbackSoakMinutes,
                    seekEpisodeCount = playbackSoakSeekCount,
                )
            }

            if (secondaryModelId != null && secondaryArtifactSha256 != null) {
                val selected = presetRepository.activate(
                    sha256 = secondaryArtifactSha256,
                    platform = AndroidMdxRuntimePlatformProvider.current(),
                    scope = SourceSeparationPresetSelectionScope.InternalValidation,
                    experimentalConfirmed = true,
                )
                assertEquals(secondaryModelId, selected.modelId)
                cacheLeaseReleaseMs = waitForCacheLeaseRelease(cacheRepository, cacheKey)
                assertFalse(cacheRepository.isLeased(cacheKey))
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
            waitForMediaController(mediaController, "resume") {
                mediaController.playWhenReady && mediaController.isPlaying
            }

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

            onMediaControllerThread(mediaController) { mediaController.pause() }
            disableSourceSeparationPlayback(mediaController)
            if (!switchedModelDuringPlayback) {
                cacheLeaseReleaseMs = waitForCacheLeaseRelease(cacheRepository, cacheKey)
            }
            onMediaControllerThread(mediaController) { mediaController.release() }
            controller = null

            report.put("status", "passed")
            report.put("playbackSoak", playbackSoakReport ?: JSONObject.NULL)
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
                .put("preservedAfterRun", preservePlaybackCache)
            )
            report.put("playbackModelSwitch", JSONObject()
                .put("performed", switchedModelDuringPlayback)
                .put("primaryCacheKey", cacheKey)
                .put("primaryArtifactSha256", expectedArtifactSha256)
                .put("secondaryModelId", secondaryModelId ?: JSONObject.NULL)
                .put("autoStartEnabled", if (switchedModelDuringPlayback) false else JSONObject.NULL)
                .put(
                    "secondaryArtifactSha256",
                    secondaryArtifactSha256 ?: JSONObject.NULL,
                )
                .put("primaryCacheLeaseReleasedOnSwitch", switchedModelDuringPlayback)
                .put("primaryCacheLeaseReleaseMs", cacheLeaseReleaseMs)
            )
            report.put("audio", report.getJSONObject("audio")
                .put("finite", true)
                .put("outputFrameCount", manifest.output?.outputFrameCount ?: 0)
                .put("expectedFrameCount", manifest.output?.outputFrameCount ?: 0)
                .put("frameDelta", 0)
                .put("playerTimestampDriftMs", 0)
                .put("stemSemantics", manifest.output?.stems?.joinToString(",") {
                    it.semanticId.value
                } ?: "unknown")
            )
        } catch (error: Throwable) {
            report.put("status", "failed")
            report.put("error", "${error::class.java.name}: ${error.message}")
            throw error
        } finally {
            controller?.let { mediaController ->
                runCatching {
                    disableSourceSeparationPlayback(mediaController)
                    onMediaControllerThread(mediaController) {
                        mediaController.pause()
                        mediaController.release()
                    }
                }
            }
            runCatching {
                restoreActiveModelSelection(
                    repository = presetRepository,
                    activeReference = initialActiveReference,
                    pendingReference = initialPendingReference,
                )
            }.onFailure { restoreError ->
                report.put("stateRestoreError",
                    "${restoreError::class.java.name}: ${restoreError.message}")
            }
            restorePreferences(preferences, preferenceSnapshot)
            audioFocusOverride.close()
            if (!preservePlaybackCache) {
                testedCacheKey?.let { cacheKey ->
                    runCatching { waitForCacheLeaseRelease(cacheRepository, cacheKey) }
                    runCatching { clearExactCacheEntry(runtimeFacade, cacheKey) }
                }
                sourceUri?.let { uri ->
                    runCatching { context.contentResolver.delete(uri, null, null) }
                }
            }
            writeReport(context, runId, "playback", report)
        }
    }

    private fun runMediaSessionPlaybackSoak(
        context: Context,
        controller: MediaController,
        store: SourceSeparationCacheStore,
        cacheKey: String,
        durationMinutes: Int,
        seekEpisodeCount: Int,
    ): JSONObject {
        val requestedDurationMs = durationMinutes.toLong() * 60_000L
        val powerManager = requireNotNull(context.getSystemService(PowerManager::class.java))
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "BoomingSS:phase7-real-song-playback-soak",
        )
        val initialPlayerState = onMediaControllerThread(controller) {
            PlaybackSoakPlayerState(
                volume = controller.volume,
                repeatMode = controller.repeatMode,
            )
        }
        try {
            wakeLock.acquire(requestedDurationMs + PLAYBACK_SOAK_WAKE_LOCK_MARGIN_MS)
            onMediaControllerThread(controller) {
                controller.volume = 0f
                controller.repeatMode = Player.REPEAT_MODE_ONE
                controller.play()
            }
            waitForMediaController(controller, "playback soak start") {
                controller.playWhenReady && controller.isPlaying &&
                    controller.playbackState == Player.STATE_READY
            }

            val mediaDurationMs = onMediaControllerThread(controller) {
                controller.duration
            }
            require(mediaDurationMs > PLAYBACK_SOAK_SEEK_END_MARGIN_MS) {
                "Playback soak fixture is too short for random seeking."
            }
            val maxSeekPositionMs = mediaDurationMs - PLAYBACK_SOAK_SEEK_END_MARGIN_MS
            val cacheBytesBefore = store.entrySize(cacheKey)
            val baselinePssKb = Debug.getPss()
            var peakPssKb = baselinePssKb
            val baselineFileDescriptors = File("/proc/self/fd").list()?.size ?: -1
            var peakFileDescriptors = baselineFileDescriptors
            val contentionBaseline = PlaybackContentionDiagnostics.snapshot()
            val startedAtMs = SystemClock.elapsedRealtime()
            val deadlineMs = startedAtMs + requestedDurationMs
            val thermalSampler = Phase7ThermalSampler(context, startedAtMs)
            thermalSampler.sample(startedAtMs, force = true)
            val random = Random(PLAYBACK_SOAK_RANDOM_SEED)
            val seekLatenciesMs = mutableListOf<Long>()
            var seekRequestCount = 0
            var rapidScrubBurstCount = 0
            var pauseResumeCount = 0
            var completedSeekEpisodes = 0
            var nextResourceSampleAtMs = startedAtMs
            var unexpectedNotPlayingSamples = 0
            var consecutiveUnexpectedNotPlayingSamples = 0
            var maximumUnexpectedNotPlayingSamples = 0

            while (SystemClock.elapsedRealtime() < deadlineMs) {
                val nowMs = SystemClock.elapsedRealtime()
                val nextSeekAtMs = if (completedSeekEpisodes < seekEpisodeCount) {
                    startedAtMs + requestedDurationMs * (completedSeekEpisodes + 1L) /
                        (seekEpisodeCount + 1L)
                } else {
                    Long.MAX_VALUE
                }
                if (nowMs >= nextSeekAtMs) {
                    val seekStartedAtMs = SystemClock.elapsedRealtime()
                    val isRapidScrub = (completedSeekEpisodes + 1) %
                        PLAYBACK_SOAK_RAPID_SCRUB_INTERVAL == 0
                    val requestCount = if (isRapidScrub) {
                        PLAYBACK_SOAK_RAPID_SCRUB_REQUEST_COUNT
                    } else {
                        1
                    }
                    var finalTargetPositionMs = 0L
                    repeat(requestCount) {
                        finalTargetPositionMs =
                            (random.nextDouble() * maxSeekPositionMs).toLong()
                        onMediaControllerThread(controller) {
                            controller.seekTo(finalTargetPositionMs)
                        }
                    }
                    seekRequestCount += requestCount
                    if (isRapidScrub) rapidScrubBurstCount += 1
                    waitForMediaControllerLatency(
                        controller = controller,
                        operation = "playback soak seek ${completedSeekEpisodes + 1} " +
                            "to ${finalTargetPositionMs}ms",
                    ) {
                        controller.playWhenReady && controller.isPlaying &&
                            abs(controller.currentPosition - finalTargetPositionMs) <=
                            MEDIA_SESSION_SEEK_TOLERANCE_MS
                    }
                    seekLatenciesMs += SystemClock.elapsedRealtime() - seekStartedAtMs
                    completedSeekEpisodes += 1

                    if (completedSeekEpisodes % PLAYBACK_SOAK_PAUSE_RESUME_INTERVAL == 0) {
                        onMediaControllerThread(controller) { controller.pause() }
                        waitForMediaController(controller, "playback soak pause") {
                            !controller.playWhenReady && !controller.isPlaying
                        }
                        onMediaControllerThread(controller) { controller.play() }
                        waitForMediaController(controller, "playback soak resume") {
                            controller.playWhenReady && controller.isPlaying
                        }
                        pauseResumeCount += 1
                    }
                    consecutiveUnexpectedNotPlayingSamples = 0
                    continue
                }

                if (nowMs >= nextResourceSampleAtMs) {
                    peakPssKb = maxOf(peakPssKb, Debug.getPss())
                    peakFileDescriptors = maxOf(
                        peakFileDescriptors,
                        File("/proc/self/fd").list()?.size ?: peakFileDescriptors,
                    )
                    thermalSampler.sample(nowMs)
                    nextResourceSampleAtMs = nowMs + PLAYBACK_SOAK_RESOURCE_SAMPLE_INTERVAL_MS
                }
                val state = onMediaControllerThread(controller) {
                    Triple(controller.playWhenReady, controller.isPlaying, controller.playerError)
                }
                check(state.third == null) {
                    "Playback soak player failed: ${state.third?.errorCodeName}: " +
                        state.third?.message
                }
                if (state.first && !state.second) {
                    unexpectedNotPlayingSamples += 1
                    consecutiveUnexpectedNotPlayingSamples += 1
                    maximumUnexpectedNotPlayingSamples = maxOf(
                        maximumUnexpectedNotPlayingSamples,
                        consecutiveUnexpectedNotPlayingSamples,
                    )
                } else {
                    consecutiveUnexpectedNotPlayingSamples = 0
                }
                SystemClock.sleep(PLAYBACK_SOAK_STATE_SAMPLE_INTERVAL_MS)
            }

            check(completedSeekEpisodes == seekEpisodeCount) {
                "Playback soak completed $completedSeekEpisodes of $seekEpisodeCount seek episodes."
            }
            peakPssKb = maxOf(peakPssKb, Debug.getPss())
            val finalPssKb = Debug.getPss()
            val finalFileDescriptors = File("/proc/self/fd").list()?.size ?: -1
            peakFileDescriptors = maxOf(peakFileDescriptors, finalFileDescriptors)
            thermalSampler.sample(SystemClock.elapsedRealtime(), force = true)
            val cacheBytesAfter = store.entrySize(cacheKey)
            val contention = PlaybackContentionDiagnostics.snapshot()
                .deltaFrom(contentionBaseline)
            check(contention.available) {
                "Playback contention counters were unavailable during the soak."
            }
            check(contention.audioUnderrunCount == 0L) {
                "Playback soak recorded ${contention.audioUnderrunCount} audio underruns."
            }
            check(maximumUnexpectedNotPlayingSamples <
                PLAYBACK_SOAK_MAXIMUM_NOT_PLAYING_SAMPLES
            ) {
                "Playback soak remained non-playing for " +
                    "$maximumUnexpectedNotPlayingSamples consecutive samples."
            }
            check(cacheBytesAfter == cacheBytesBefore) {
                "Playback soak changed the completed cache size."
            }

            return JSONObject()
                .put("requestedDurationMs", requestedDurationMs)
                .put("elapsedMs", SystemClock.elapsedRealtime() - startedAtMs)
                .put("mediaDurationMs", mediaDurationMs)
                .put("seekEpisodeCount", completedSeekEpisodes)
                .put("seekRequestCount", seekRequestCount)
                .put("rapidScrubBurstCount", rapidScrubBurstCount)
                .put("pauseResumeCount", pauseResumeCount)
                .put("coldSeekLatencyMs", seekLatenciesMs.first())
                .put("seekLatencyMs", playbackLatencyJson(seekLatenciesMs))
                .put("warmSeekLatencyMs", playbackLatencyJson(seekLatenciesMs.drop(1)))
                .put("unexpectedNotPlayingSamples", unexpectedNotPlayingSamples)
                .put(
                    "maximumUnexpectedNotPlayingMs",
                    maximumUnexpectedNotPlayingSamples *
                        PLAYBACK_SOAK_STATE_SAMPLE_INTERVAL_MS,
                )
                .put("audioUnderrunCount", contention.audioUnderrunCount)
                .put("playerRunTimeNanos", contention.playerRunTimeNanos)
                .put("playerRunQueueWaitNanos", contention.playerRunQueueWaitNanos)
                .put("baselinePssKb", baselinePssKb)
                .put("peakPssKb", peakPssKb)
                .put("finalPssKb", finalPssKb)
                .put("pssDeltaKb", (peakPssKb - baselinePssKb).coerceAtLeast(0))
                .put("baselineFileDescriptors", baselineFileDescriptors)
                .put("peakFileDescriptors", peakFileDescriptors)
                .put("finalFileDescriptors", finalFileDescriptors)
                .put("cacheBytesBefore", cacheBytesBefore)
                .put("cacheBytesAfter", cacheBytesAfter)
                .put("thermal", thermalSampler.toJson())
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
            runCatching {
                onMediaControllerThread(controller) {
                    controller.volume = initialPlayerState.volume
                    controller.repeatMode = initialPlayerState.repeatMode
                }
            }
        }
    }

    private fun waitForMediaControllerLatency(
        controller: MediaController,
        operation: String,
        predicate: () -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + MEDIA_SESSION_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (onMediaControllerThread(controller, predicate)) return
            SystemClock.sleep(PLAYBACK_SOAK_SEEK_POLL_INTERVAL_MS)
        }
        val state = onMediaControllerThread(controller) {
            JSONObject()
                .put("positionMs", controller.currentPosition)
                .put("bufferedPositionMs", controller.bufferedPosition)
                .put("durationMs", controller.duration)
                .put("playWhenReady", controller.playWhenReady)
                .put("isPlaying", controller.isPlaying)
                .put("playbackState", controller.playbackState)
                .put("repeatMode", controller.repeatMode)
                .put("playerError", controller.playerError?.let { error ->
                    "${error.errorCodeName}: ${error.message}"
                } ?: JSONObject.NULL)
        }
        error("MediaController did not complete $operation in time: $state")
    }

    private fun playbackLatencyJson(values: List<Long>): JSONObject {
        if (values.isEmpty()) return JSONObject().put("count", 0)
        val sorted = values.sorted()
        fun percentile(percent: Int): Long {
            val index = ((sorted.size * percent + 99) / 100 - 1)
                .coerceIn(0, sorted.lastIndex)
            return sorted[index]
        }
        return JSONObject()
            .put("count", sorted.size)
            .put("p50", percentile(50))
            .put("p95", percentile(95))
            .put("p99", percentile(99))
            .put("max", sorted.last())
    }

    private data class PlaybackSoakPlayerState(
        val volume: Float,
        val repeatMode: Int,
    )

    private fun disableSourceSeparationPlayback(controller: MediaController) {
        val result = onMediaControllerThread(controller) {
            controller.sendCustomCommand(
                SessionCommand(Playback.SET_SOURCE_SEPARATION_PLAYBACK_ENABLED, Bundle.EMPTY),
                Bundle().apply {
                    putBoolean(Playback.EXTRA_SOURCE_SEPARATION_ENABLED, false)
                    putBoolean(Playback.EXTRA_SOURCE_SEPARATION_SHOW_MESSAGE, false)
                },
            )
        }.get(MEDIA_SESSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        assertEquals(SessionResult.RESULT_SUCCESS, result.resultCode)
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

    private fun readShellCommand(
        instrumentation: android.app.Instrumentation,
        command: String,
    ): String = instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
        FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() }
    }

    private fun activeWakeLocks(powerDump: String): String {
        val start = Regex("(?m)^Wake Locks: size=\\d+\\s*$")
            .find(powerDump)
            ?: error("Power diagnostics do not contain the active wake-lock section.")
        val end = Regex("(?m)^Suspend Blockers: size=\\d+\\s*$")
            .find(powerDump, start.range.last + 1)
            ?: error("Power diagnostics do not terminate the active wake-lock section.")
        return powerDump.substring(start.range.first, end.range.first)
    }

    private fun startOriginalAudioPlayback(
        context: Context,
        source: Song,
        operation: String,
    ): OriginalAudioPlaybackProbe {
        val failures = mutableListOf<String>()
        var lastError: Throwable? = null
        for (attempt in 1..ORIGINAL_PLAYBACK_START_ATTEMPTS) {
            try {
                return startOriginalAudioPlaybackAttempt(
                    context = context,
                    source = source,
                    operation = operation,
                    startupAttempt = attempt,
                    startupFailures = failures,
                )
            } catch (error: Throwable) {
                lastError = error
                failures += "attempt $attempt: ${error::class.java.name}: ${error.message}"
                if (attempt < ORIGINAL_PLAYBACK_START_ATTEMPTS) {
                    SystemClock.sleep(ORIGINAL_PLAYBACK_RETRY_DELAY_MS)
                }
            }
        }
        throw IllegalStateException(
            "Original playback did not start after $ORIGINAL_PLAYBACK_START_ATTEMPTS attempts " +
                "for $operation: ${failures.joinToString()}",
            lastError,
        )
    }

    private fun startOriginalAudioPlaybackAttempt(
        context: Context,
        source: Song,
        operation: String,
        startupAttempt: Int,
        startupFailures: List<String>,
    ): OriginalAudioPlaybackProbe {
        val audioFocusOverride = installPlaybackAudioFocusTestOverride()
        val sessionToken = SessionToken(
            context,
            ComponentName(context, PlaybackService::class.java),
        )
        val controller = try {
            MediaController.Builder(context, sessionToken)
                .buildAsync()
                .get(MEDIA_SESSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (error: Throwable) {
            audioFocusOverride.close()
            throw error
        }
        try {
            val awaitRestorationCommand = SessionCommand(
                Playback.AWAIT_PLAYBACK_RESTORATION,
                Bundle.EMPTY,
            )
            assertTrue(
                "PlaybackService did not expose the debug restoration command.",
                onMediaControllerThread(controller) {
                    controller.availableSessionCommands.contains(awaitRestorationCommand)
                },
            )
            val restorationWaitStartedAt = SystemClock.elapsedRealtime()
            val restorationResult = onMediaControllerThread(controller) {
                controller.sendCustomCommand(awaitRestorationCommand, Bundle.EMPTY)
            }.get(MEDIA_SESSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val restorationWaitDurationMs =
                SystemClock.elapsedRealtime() - restorationWaitStartedAt
            assertEquals(SessionResult.RESULT_SUCCESS, restorationResult.resultCode)

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
            waitForMediaController(controller, "$operation queue reset") {
                controller.mediaItemCount == 0 && controller.currentMediaItem == null
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
                startupAttempt = startupAttempt,
                startupFailures = startupFailures,
                audioFocusOverride = audioFocusOverride,
                restorationWaitDurationMs = restorationWaitDurationMs,
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
            runCatching { audioFocusOverride.close() }
                .onFailure(error::addSuppressed)
            throw error
        }
    }

    private fun installPlaybackAudioFocusTestOverride(): PlaybackAudioFocusTestOverride {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            return PlaybackAudioFocusTestOverride.disabled()
        }

        val preferences = get<SharedPreferences>(SharedPreferences::class.java)
        val keyWasPresent = preferences.contains(IGNORE_AUDIO_FOCUS)
        val originalValue = preferences.getBoolean(IGNORE_AUDIO_FOCUS, false)
        if (!originalValue) {
            check(preferences.edit().putBoolean(IGNORE_AUDIO_FOCUS, true).commit()) {
                "Could not bypass audio focus for the API 35 playback probe."
            }
        }
        return PlaybackAudioFocusTestOverride(
            preferences = preferences,
            keyWasPresent = keyWasPresent,
            originalValue = originalValue,
            changed = !originalValue,
            bypassed = true,
        )
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

    private class PlaybackAudioFocusTestOverride(
        private val preferences: SharedPreferences?,
        private val keyWasPresent: Boolean,
        private val originalValue: Boolean,
        private val changed: Boolean,
        val bypassed: Boolean,
    ) {
        private val closed = AtomicBoolean(false)

        fun close() {
            if (!closed.compareAndSet(false, true) || !changed) return
            val editor = requireNotNull(preferences).edit()
            if (keyWasPresent) {
                editor.putBoolean(IGNORE_AUDIO_FOCUS, originalValue)
            } else {
                editor.remove(IGNORE_AUDIO_FOCUS)
            }
            check(editor.commit()) {
                "Could not restore the audio-focus preference after the playback probe."
            }
        }

        companion object {
            fun disabled() = PlaybackAudioFocusTestOverride(
                preferences = null,
                keyWasPresent = false,
                originalValue = false,
                changed = false,
                bypassed = false,
            )
        }
    }

    private inner class OriginalAudioPlaybackProbe(
        private val controller: MediaController,
        private val expectedMediaId: String,
        private val sourcePreparationAttempts: Int,
        private val startupAttempt: Int,
        private val startupFailures: List<String>,
        private val audioFocusOverride: PlaybackAudioFocusTestOverride,
        private val restorationWaitDurationMs: Long,
    ) : Player.Listener {
        private val contentionBaseline = PlaybackContentionDiagnostics.snapshot()
        private val armed = AtomicBoolean(false)
        private val closed = AtomicBoolean(false)
        private val automaticDiscontinuityCount = AtomicInteger(0)
        private val sameItemTransitionCount = AtomicInteger(0)
        private val unexpectedEvents = Collections.synchronizedList(mutableListOf<String>())
        private val snapshots = Collections.synchronizedList(mutableListOf<JSONObject>())

        fun arm() {
            armed.set(true)
        }

        fun disarm() {
            armed.set(false)
        }

        fun sendCustomCommand(action: String, arguments: Bundle) =
            onMediaControllerThread(controller) {
                controller.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), arguments)
            }

        fun assertContinuous(label: String): JSONObject {
            try {
                waitForMediaController(controller, "original playback at $label") {
                    controller.currentMediaItem?.mediaId == expectedMediaId &&
                        controller.repeatMode == Player.REPEAT_MODE_ONE &&
                        controller.playWhenReady &&
                        controller.isPlaying
                }
            } catch (error: Throwable) {
                val state = runCatching {
                    onMediaControllerThread(controller) {
                        JSONObject()
                            .put("mediaId", controller.currentMediaItem?.mediaId)
                            .put("positionMs", controller.currentPosition)
                            .put("durationMs", controller.duration)
                            .put("playWhenReady", controller.playWhenReady)
                            .put("isPlaying", controller.isPlaying)
                            .put("playbackState", controller.playbackState)
                            .put("repeatMode", controller.repeatMode)
                    }
                }.getOrElse { diagnosticError ->
                    "unavailable: ${diagnosticError::class.java.name}: " +
                        diagnosticError.message
                }
                val events = synchronized(unexpectedEvents) { unexpectedEvents.toList() }
                throw IllegalStateException(
                    "Original playback continuity failed at $label: " +
                        "state=$state, unexpectedEvents=$events",
                    error,
                )
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
            val contention = PlaybackContentionDiagnostics.snapshot()
                .deltaFrom(contentionBaseline)
            return JSONObject()
                .put("expectedMediaId", expectedMediaId)
                .put("sourcePreparationAttempts", sourcePreparationAttempts)
                .put("startupAttempt", startupAttempt)
                .put("startupFailures", JSONArray(startupFailures))
                .put("restorationWaitDurationMs", restorationWaitDurationMs)
                .put("audioFocusBypassedForApi35", audioFocusOverride.bypassed)
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
                .put("contentionCountersAvailable", contention.available)
                .put("audioUnderrunCount", contention.audioUnderrunCount)
                .put(
                    "audioUnderrunElapsedSinceLastFeedTotalMs",
                    contention.audioUnderrunElapsedSinceLastFeedTotalMs,
                )
                .put(
                    "maximumAudioUnderrunElapsedSinceLastFeedMs",
                    contention.maximumAudioUnderrunElapsedSinceLastFeedMs,
                )
                .put(
                    "maximumAudioUnderrunBufferSizeMs",
                    contention.maximumAudioUnderrunBufferSizeMs ?: JSONObject.NULL,
                )
                .put("playerRunTimeNanos", contention.playerRunTimeNanos)
                .put("playerRunQueueWaitNanos", contention.playerRunQueueWaitNanos)
                .put("playerTimesliceCount", contention.playerTimesliceCount)
                .put(
                    "playerVoluntaryContextSwitches",
                    contention.playerVoluntaryContextSwitches,
                )
                .put(
                    "playerInvoluntaryContextSwitches",
                    contention.playerInvoluntaryContextSwitches,
                )
                .put("snapshots", JSONArray(snapshotCopy))
        }

        fun currentState(): JSONObject = onMediaControllerThread(controller) {
            JSONObject()
                .put("sampledAtElapsedRealtimeMs", SystemClock.elapsedRealtime())
                .put("mediaId", controller.currentMediaItem?.mediaId)
                .put("mediaItemIndex", controller.currentMediaItemIndex)
                .put("positionMs", controller.currentPosition)
                .put("bufferedPositionMs", controller.bufferedPosition)
                .put("durationMs", controller.duration)
                .put("playWhenReady", controller.playWhenReady)
                .put("isPlaying", controller.isPlaying)
                .put("playbackState", controller.playbackState)
                .put("repeatMode", controller.repeatMode)
                .put("playerError", controller.playerError?.let { playbackError ->
                    "${playbackError.errorCodeName}: ${playbackError.message}"
                } ?: JSONObject.NULL)
        }

        fun awaitStopped(label: String): JSONObject {
            waitForMediaController(controller, label) {
                !controller.playWhenReady || !controller.isPlaying
            }
            return onMediaControllerThread(controller) {
                JSONObject()
                    .put("label", label)
                    .put("sampledAtElapsedRealtimeMs", SystemClock.elapsedRealtime())
                    .put("mediaId", controller.currentMediaItem?.mediaId)
                    .put("positionMs", controller.currentPosition)
                    .put("durationMs", controller.duration)
                    .put("playWhenReady", controller.playWhenReady)
                    .put("isPlaying", controller.isPlaying)
                    .put("playbackState", controller.playbackState)
            }
        }

        fun releaseControllerOnly() {
            release(clearPlayback = false)
        }

        fun close() {
            release(clearPlayback = true)
        }

        private fun release(clearPlayback: Boolean) {
            if (!closed.compareAndSet(false, true)) return
            armed.set(false)
            runCatching {
                onMediaControllerThread(controller) {
                    controller.removeListener(this@OriginalAudioPlaybackProbe)
                    if (clearPlayback) {
                        controller.pause()
                        controller.clearMediaItems()
                    }
                    controller.release()
                }
            }
            audioFocusOverride.close()
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

    private fun waitForCacheFaultHit(
        root: File,
        token: String,
    ): SourceSeparationCacheFaultHit {
        val deadline = SystemClock.elapsedRealtime() + PHASE4_FAULT_HIT_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            SourceSeparationCacheFaultInjection.readHit(root)
                ?.takeIf { it.token == token }
                ?.let { return it }
            SystemClock.sleep(PHASE4_FAULT_POLL_INTERVAL_MS)
        }
        error("Cache fault injection did not reach token $token.")
    }

    private fun processMainDeathScenarioFile(context: Context, runId: String): File =
        File(context.filesDir, "$PHASE4_MAIN_DEATH_DIRECTORY/$runId.json")

    private fun writeDurableJson(file: File, value: JSONObject) {
        val directory = requireNotNull(file.parentFile)
        require(directory.isDirectory || directory.mkdirs()) {
            "Could not create the process main-death scenario directory."
        }
        java.io.RandomAccessFile(file, "rw").use { output ->
            output.setLength(0L)
            output.write(value.toString(2).toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
    }

    private fun waitForKernelCacheLockRelease(
        store: SourceSeparationCacheStore,
        cacheKey: String,
    ): Long {
        val startedAt = SystemClock.elapsedRealtime()
        val deadline = startedAt + PHASE4_LOCK_RELEASE_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val lease = store.entryLocks().tryAcquire(
                cacheKey,
                SourceSeparationCacheLockOwner(
                    purpose = SourceSeparationCacheLockPurpose.Other,
                    pid = Process.myPid(),
                ),
            )
            if (lease != null) {
                lease.close()
                return SystemClock.elapsedRealtime() - startedAt
            }
            SystemClock.sleep(PHASE4_FAULT_POLL_INTERVAL_MS)
        }
        error("Kernel cache lock was not released for $cacheKey.")
    }

    private fun playbackOwnerFailureDiagnostics(
        instrumentation: android.app.Instrumentation,
        context: Context,
        worker: SourceSeparationForegroundWorkerCoordinator?,
        store: SourceSeparationCacheStore?,
        cacheKey: String?,
        playbackProbe: OriginalAudioPlaybackProbe?,
    ): JSONObject {
        val diagnostics = JSONObject()
            .put("sampledAtElapsedRealtimeMs", SystemClock.elapsedRealtime())
            .put("cacheKey", cacheKey ?: JSONObject.NULL)

        worker?.let { coordinator ->
            diagnostics.put("coordinator", JSONObject()
                .put("debugStatus", runCatching { coordinator.debugStatus() }
                    .getOrElse(::diagnosticError))
                .put("windowSamples", runCatching { coordinator.windowSamples() }
                    .getOrElse(::diagnosticError))
                .put("workerState", coordinator.workerStateFlow.value.toString())
                .put("workerActive", coordinator.isWorkerActive())
                .put("runningSongId", coordinator.runningSongId() ?: JSONObject.NULL)
                .put("pendingSongId", coordinator.pendingSongId() ?: JSONObject.NULL)
            )
        }

        playbackProbe?.let { probe ->
            diagnostics.put(
                "playbackProbe",
                runCatching { probe.report() }.getOrElse { diagnosticError(it) },
            )
            diagnostics.put(
                "playbackState",
                runCatching { probe.currentState() }.getOrElse { diagnosticError(it) },
            )
        }

        if (store != null && cacheKey != null) {
            diagnostics.put(
                "runJournal",
                runCatching { store.readRunJournal(cacheKey) }
                    .fold(
                        onSuccess = { journal -> journal?.let(::runJournalDiagnostic)
                            ?: JSONObject.NULL },
                        onFailure = { diagnosticError(it) },
                    ),
            )
            diagnostics.put(
                "manifest",
                runCatching { store.readManifest(cacheKey) }
                    .fold(
                        onSuccess = { manifest -> manifest?.let(::manifestDiagnostic)
                            ?: JSONObject.NULL },
                        onFailure = { diagnosticError(it) },
                    ),
            )
        }

        diagnostics.put(
            "ownershipHandoff",
            runCatching {
                get<SourceSeparationProcessingOwnershipHandoff>(
                    SourceSeparationProcessingOwnershipHandoff::class.java,
                ).stateFlow.value.toString()
            }.getOrElse(::diagnosticError),
        )
        diagnostics.put(
            "serviceDump",
            runCatching {
                readShellCommand(
                    instrumentation,
                    "dumpsys activity services ${context.packageName}",
                )
            }.getOrElse(::diagnosticError),
        )
        return diagnostics
    }

    private fun runJournalDiagnostic(journal: SourceSeparationCacheRunJournal): JSONObject =
        JSONObject()
            .put("lifecycle", journal.lifecycle.name)
            .put("latestSequence", journal.latestSequence)
            .put("updatedAtEpochMs", journal.updatedAtEpochMs)
            .put("runId", journal.request.runId)
            .put("runClass", journal.request.runClass.name)
            .put("ownerPid", journal.request.ownerPid ?: JSONObject.NULL)
            .put("tryGpu", journal.request.tryGpu)
            .put("gpuFallbackLatch", journal.request.gpuFallbackLatch?.toString()
                ?: JSONObject.NULL)
            .put(
                "committedSegments",
                JSONArray(journal.committedSegments.map { it.segmentIndex }),
            )
            .put(
                "transitions",
                JSONArray(journal.transitions.map { transition ->
                    JSONObject()
                        .put("sequence", transition.sequence)
                        .put("type", transition.type.name)
                        .put("segmentIndex", transition.segmentIndex ?: JSONObject.NULL)
                        .put("ownerPid", transition.ownerPid ?: JSONObject.NULL)
                        .put("timestampEpochMs", transition.timestampEpochMs)
                        .put("error", transition.error?.toString() ?: JSONObject.NULL)
                }),
            )

    private fun manifestDiagnostic(manifest: SourceSeparationCacheManifest): JSONObject =
        JSONObject()
            .put("state", manifest.state.name)
            .put("updatedAtEpochMs", manifest.updatedAtEpochMs)
            .put("segmentCount", manifest.segmentPlan?.segments?.size ?: 0)
            .put("outputWindowCount", manifest.output?.windowCount ?: JSONObject.NULL)
            .put("runtimeRecordCount", manifest.runtimeRecords.size)
            .put("cleanup", manifest.cleanup?.toString() ?: JSONObject.NULL)
            .put("error", manifest.error?.toString() ?: JSONObject.NULL)

    private fun diagnosticError(error: Throwable): String =
        "unavailable: ${error::class.java.name}: ${error.message}"

    private fun waitForReady(
        worker: SourceSeparationForegroundWorkerCoordinator,
        minimumReadyWindows: Int,
        expectedCacheKey: String? = null,
    ) {
        val deadline = SystemClock.elapsedRealtime() + LIFECYCLE_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            when (val state = worker.workerStateFlow.value) {
                is SourceSeparationUiState.Running -> {
                    if ((state.scheduler?.playbackReadyWindowReadyCount ?: 0) >=
                        minimumReadyWindows &&
                        (expectedCacheKey == null || state.cacheKey == expectedCacheKey)
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

    private fun waitForCompleted(
        worker: SourceSeparationForegroundWorkerCoordinator,
        expectedCacheKey: String? = null,
    ) {
        val deadline = SystemClock.elapsedRealtime() + LIFECYCLE_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            when (val state = worker.workerStateFlow.value) {
                is SourceSeparationUiState.Completed -> if (
                    expectedCacheKey == null || state.cacheKey == expectedCacheKey
                ) return
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
                SourceSeparationModelAwarePlayableStatus.Processing ->
                    SystemClock.sleep(POLL_INTERVAL_MS)
                SourceSeparationModelAwarePlayableStatus.Unavailable -> {
                    when (val cache = runtimeFacade.cacheStatus(song)) {
                        is SourceSeparationModelAwareCacheStatus.Incomplete -> {
                            if (cache.manifest.error != null) {
                                error("Playable cache failed: ${cache.manifest.error}")
                            }
                        }
                        is SourceSeparationModelAwareCacheStatus.Corrupt ->
                            error("Playable cache became corrupt: ${cache.manifest.error}")
                        else -> Unit
                    }
                    SystemClock.sleep(POLL_INTERVAL_MS)
                }
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

    private fun waitForRunJournal(
        store: SourceSeparationCacheStore,
        cacheKey: String,
        lifecycle: SourceSeparationCacheRunJournalLifecycle,
        transition: SourceSeparationCacheRunTransitionType? = null,
    ): SourceSeparationCacheRunJournal {
        val deadline = SystemClock.elapsedRealtime() + LIFECYCLE_TIMEOUT_MS
        var latest: SourceSeparationCacheRunJournal? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            latest = store.readRunJournal(cacheKey)
            if (latest?.lifecycle == lifecycle &&
                (transition == null || latest.transitions.any { it.type == transition })
            ) {
                return latest
            }
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        error(
            "Cache run journal did not reach $lifecycle" +
                (transition?.let { " with $it" } ?: "") +
                ": cache=$cacheKey latest=$latest",
        )
    }

    private fun waitForSupersededOrCompletedRunJournal(
        store: SourceSeparationCacheStore,
        cacheKey: String,
    ): SourceSeparationCacheRunJournal {
        val deadline = SystemClock.elapsedRealtime() + LIFECYCLE_TIMEOUT_MS
        var latest: SourceSeparationCacheRunJournal? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            latest = store.readRunJournal(cacheKey)
            if (latest?.lifecycle == SourceSeparationCacheRunJournalLifecycle.Completed ||
                (latest?.lifecycle == SourceSeparationCacheRunJournalLifecycle.Paused &&
                    latest.transitions.any {
                        it.type == SourceSeparationCacheRunTransitionType.ActiveModelSuperseded
                    })
            ) {
                return requireNotNull(latest)
            }
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        error(
            "Cache run journal was neither superseded nor completed: " +
                "cache=$cacheKey latest=$latest",
        )
    }

    private fun waitForProcessSessionLeaseRelease(
        host: BoundRemoteSourceSeparationExecutionHost,
    ): ProcessSessionLeaseRelease {
        val startedAt = SystemClock.elapsedRealtime()
        val deadline = startedAt + PROCESS_MATRIX_LEASE_RELEASE_TIMEOUT_MS
        var diagnostics = host.processDiagnostics()
        while (diagnostics.session.activeLeaseCount != 0 &&
            SystemClock.elapsedRealtime() < deadline
        ) {
            SystemClock.sleep(POLL_INTERVAL_MS)
            diagnostics = host.processDiagnostics()
        }
        assertEquals(
            "Native session lease did not release within " +
                "$PROCESS_MATRIX_LEASE_RELEASE_TIMEOUT_MS ms.",
            0,
            diagnostics.session.activeLeaseCount,
        )
        return ProcessSessionLeaseRelease(
            diagnostics = diagnostics,
            elapsedMs = SystemClock.elapsedRealtime() - startedAt,
        )
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
            when (val state = worker.workerStateFlow.value) {
                is SourceSeparationUiState.Paused -> {
                    if (worker.runningSongId() == null) return
                }
                is SourceSeparationUiState.Failed,
                is SourceSeparationUiState.Canceled,
                -> error("Worker reached an unexpected terminal state while pausing: $state")
                else -> Unit
            }
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        error("Worker did not release its active song in the paused state in time.")
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

    private fun snapshotPreferences(
        preferences: SharedPreferences,
        keys: Collection<String>,
    ): Map<String, Any?> {
        val values = preferences.all
        return keys.associateWith(values::get)
    }

    private fun restorePreferences(
        preferences: SharedPreferences,
        snapshot: Map<String, Any?>,
    ) {
        val editor = preferences.edit()
        snapshot.forEach { (key, value) ->
            when (value) {
                null -> editor.remove(key)
                is Boolean -> editor.putBoolean(key, value)
                is Float -> editor.putFloat(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> editor.putStringSet(
                    key,
                    value.map { entry ->
                        require(entry is String) {
                            "Preference $key contains a non-string set entry."
                        }
                        entry
                    }.toSet(),
                )
                else -> error("Unsupported preference value for $key: ${value::class.java.name}")
            }
        }
        check(editor.commit()) { "Could not restore playback-owned test preferences." }
    }

    private fun restoreActiveModelSelection(
        repository: SourceSeparationPresetRepository,
        activeReference: SourceSeparationActiveModelReference,
        pendingReference: SourceSeparationActiveModelReference?,
    ) {
        if (activeReference.profileId == null) {
            repository.activate(
                sha256 = activeReference.artifactSha256,
                platform = AndroidMdxRuntimePlatformProvider.current(),
                scope = SourceSeparationPresetSelectionScope.InternalValidation,
                experimentalConfirmed = true,
            )
        } else {
            repository.activateCustomProfile(
                sha256 = activeReference.artifactSha256,
                profileId = activeReference.profileId,
                platform = AndroidMdxRuntimePlatformProvider.current(),
                scope = SourceSeparationPresetSelectionScope.InternalValidation,
            )
        }
        repository.setPendingActiveModel(pendingReference)
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
        .put("vmDataBytes", diagnostics.memory.vmDataBytes ?: JSONObject.NULL)
        .put("pssBytes", diagnostics.memory.pssBytes)
        .put("ussBytes", diagnostics.memory.ussBytes)
        .put("javaPssBytes", diagnostics.memory.javaPssBytes)
        .put("nativePssBytes", diagnostics.memory.nativePssBytes)
        .put("graphicsPssBytes", diagnostics.memory.graphicsPssBytes)
        .put("javaHeapAllocatedBytes", diagnostics.memory.javaHeapAllocatedBytes)
        .put("nativeHeapAllocatedBytes", diagnostics.memory.nativeHeapAllocatedBytes)
        .put("runtimeMaxMemoryBytes", diagnostics.memory.runtimeMaxMemoryBytes)
        .put("processCpuTimeMs", diagnostics.memory.processCpuTimeMs)
        .put("oomScoreAdj", diagnostics.memory.oomScoreAdj ?: JSONObject.NULL)
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

    private fun requireResidentProcessValidation(arguments: Bundle) {
        val enabled = when (arguments.requiredString(ARG_PROCESS_ABI)) {
            "x86" -> MdxX86ProcessValidationOverride.buildEnabled
            "armeabi-v7a" -> SourceSeparationArm32ResidentValidation.buildEnabled
            else -> false
        }
        require(enabled) {
            "The resident process matrix requires its explicit ABI validation build."
        }
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
        processDiagnosticsOverride: SourceSeparationProcessDiagnostics? = null,
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
        val processDiagnostics = processDiagnosticsOverride ?: remoteHost?.let { host ->
            runCatching { host.processDiagnostics() }.getOrNull()
                ?: host.connectionDiagnostics.latestProcessDiagnostics
        }
        val completion = (events.last().payload as
            SourceSeparationExecutionHostEventPayload.Completed).completion
        val acceptedDescriptor = (events.first().payload as
            SourceSeparationExecutionHostEventPayload.Accepted).descriptor
        if (mode.isRemote) {
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
            .put("protocolVersion", acceptedDescriptor.protocolVersion)
            .put("runClass", acceptedDescriptor.runtime.runClass.name)
            .put("backgroundPolicy", acceptedDescriptor.runtime.backgroundPolicy.name)
            .put("processGeneration", diagnostics?.processGeneration ?: JSONObject.NULL)
            .put("remotePid", diagnostics?.pid ?: JSONObject.NULL)
            .put("processStartTicks", diagnostics?.processStartTicks ?: JSONObject.NULL)
            .put("idleRemotePssBytes", diagnostics?.idlePssBytes ?: JSONObject.NULL)
            .put("bindToConnectedMs", diagnostics?.bindToConnectedMs ?: JSONObject.NULL)
            .put(
                "process",
                processDiagnostics?.let(::processResourceJson) ?: JSONObject.NULL,
            )
            .put("runtime", JSONObject()
                .put("runtimeName", completion.runtimeDiagnostics.runtimeName)
                .put("backend", completion.runtimeDiagnostics.backend)
                .put(
                    "cpuThreads",
                    completion.runtimeDiagnostics.cpuThreads ?: JSONObject.NULL,
                )
                .put("detail", completion.runtimeDiagnostics.detail)
                .put(
                    "modelSetupNanos",
                    completion.runtimeDiagnostics.modelSetupNanos ?: JSONObject.NULL,
                )
                .put(
                    "inferenceInvocationCount",
                    completion.runtimeDiagnostics.inferenceInvocationCount,
                )
                .put(
                    "firstInferenceNanos",
                    completion.runtimeDiagnostics.firstInferenceNanos ?: JSONObject.NULL,
                )
                .put(
                    "reusedInferenceCount",
                    completion.runtimeDiagnostics.reusedInferenceCount,
                )
                .put(
                    "reusedInferenceTotalNanos",
                    completion.runtimeDiagnostics.reusedInferenceTotalNanos,
                )
                .put(
                    "lastInferenceNanos",
                    completion.runtimeDiagnostics.lastInferenceNanos ?: JSONObject.NULL,
                )
                .put(
                    "fallbackStage",
                    completion.runtimeDiagnostics.fallbackStage ?: JSONObject.NULL,
                )
                .put(
                    "fallbackReason",
                    completion.runtimeDiagnostics.fallbackReason ?: JSONObject.NULL,
                )
            )
            .put("session", processDiagnostics?.session?.let { session ->
                JSONObject()
                    .put("state", session.state.name)
                    .put(
                        "backendPolicy",
                        session.backendPolicy?.let { policy ->
                            when (policy) {
                                SourceSeparationExecutionBackendPolicy.Auto -> "auto"
                                SourceSeparationExecutionBackendPolicy.Cpu -> "cpu"
                            }
                        } ?: JSONObject.NULL,
                    )
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
        val engine = if (executionHostMode.isRemote) {
            require(sessionProviderFactoryOverride == null) {
                "Bound-remote validation does not support an injected session provider."
            }
            require(backendMode == BackendMode.Auto ||
                (processorCount == null && xnnPackFlags == null)
            ) {
                "Bound-remote CPU validation uses the default production thread policy."
            }
            val host = BoundRemoteSourceSeparationExecutionHost(
                context.applicationContext,
                foregroundPolicy = if (executionHostMode ==
                    Phase7ExecutionHostMode.IndependentForeground
                ) {
                    SourceSeparationRemoteForegroundPolicy.ManualFullSong
                } else {
                    SourceSeparationRemoteForegroundPolicy.Disabled
                },
            )
                .also(boundRemoteHostSink)
            if (executionHostMode == Phase7ExecutionHostMode.IndependentForeground) {
                SourceSeparationModelAwareEngine.createIndependentForegroundPrototype(
                    context = context,
                    presetRepository = presetRepository,
                    coordinator = runCoordinator,
                    executionHost = host,
                    executionBackendPolicy = backendMode.executionPolicy,
                    executionHostEventSink = executionHostEventSink,
                )
            } else {
                SourceSeparationModelAwareEngine.createBoundRemotePrototype(
                    context = context,
                    presetRepository = presetRepository,
                    coordinator = runCoordinator,
                    executionHost = host,
                    executionBackendPolicy = backendMode.executionPolicy,
                    executionHostEventSink = executionHostEventSink,
                )
            }
        } else {
            if (backendMode == BackendMode.Cpu && sessionProviderFactoryOverride == null) {
                SourceSeparationRuntimeBootstrap.ensureLoaded(context.applicationContext)
            }
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
                ) { _ ->
                    requireNotNull(sessionProviderFactory).invoke()
                }
            }
            SourceSeparationModelAwareEngine(
                activeModelResolver = presetRepository::resolveActiveCacheModel,
                preflightResolver = AndroidSourceSeparationModelAwarePreflightResolver(context),
                coordinator = runCoordinator,
                rangeExecutor = rangeExecutor,
                executionBackendPolicy = backendMode.executionPolicy,
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
        )
    }

    private fun createKaraGpuRequalificationSessionProvider(
        context: Context,
        gpuRuntimeProfile: MdxLiteRtGpuRuntimeProfile,
    ): MdxInferenceSessionProvider {
        require(gpuRuntimeProfile == MdxLiteRtGpuRuntimeProfile.BoundedOpenClFp32V1)
        val delegate = createAutoLiteRtSessionFactory(context, gpuRuntimeProfile)
        val diagnosticFactory = object : MdxInferenceSessionFactory {
            override val factoryId = "${delegate.factoryId}-kara-requalification"
            override val backend = delegate.backend

            override fun create(
                artifact: MdxModelArtifact,
                profile: MdxExecutionProfile,
                runtimeSettings: MdxRuntimeSettings,
            ): MdxInferenceSession {
                require(artifact.sha256 == KARA_ARTIFACT_SHA256)
                require(profile.profileId == KARA_CONTRACT_ID)
                val matchingRecords = profile.runtimeCompatibility.filter { record ->
                    record.abi == MdxRuntimeAbi.Arm64V8a &&
                        record.backend == MdxInferenceBackend.LiteRtGpu &&
                        record.profileId == gpuRuntimeProfile.qualificationProfileId &&
                        record.precision == MdxRuntimePrecision.Fp32
                }
                require(matchingRecords.size == 1)
                require(matchingRecords.single().status == MdxRuntimeSupportStatus.Rejected)
                val diagnosticProfile = profile.copy(
                    runtimeCompatibility = profile.runtimeCompatibility.map { record ->
                        if (record == matchingRecords.single()) {
                            record.copy(
                                status = MdxRuntimeSupportStatus.Candidate,
                                evidence = "AndroidTest-only KARA FP32 GPU requalification.",
                            )
                        } else {
                            record
                        }
                    },
                )
                return delegate.create(artifact, diagnosticProfile, runtimeSettings)
            }
        }
        return SingleUseMdxInferenceSessionProvider(diagnosticFactory)
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
            val filePrefix = stem.semanticId.value
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
            stem.semanticId.value to Phase7StemArtifactExport(
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
        val status = runCatching { File("/proc/self/status").readText() }.getOrNull()
        return JSONObject()
            .put("totalPssBytes", (processInfo?.totalPss ?: info.totalPss).toLong() * 1024L)
            .put(
                "totalUssBytes",
                (info.totalPrivateDirty.toLong() + info.totalPrivateClean.toLong()) * 1_024L,
            )
            .put(
                "vmRssBytes",
                SourceSeparationProcParser.parseStatusKilobytes(status, "VmRSS") ?: 0L,
            )
            .put("javaPssBytes", info.summaryBytes("summary.java-heap", info.dalvikPss))
            .put("nativePssBytes", info.summaryBytes("summary.native-heap", info.nativePss))
            .put("graphicsPssBytes", info.summaryBytes("summary.graphics", 0))
    }

    private fun currentProcessDiagnostics(): SourceSeparationProcessDiagnostics =
        SourceSeparationProcessDiagnosticsCollector.capture(
            processGeneration = 1L,
            processName = AppProcessResolver.resolve(
                InstrumentationRegistry.getInstrumentation().targetContext,
            ).processName,
        )

    private fun processResourceJson(process: SourceSeparationProcessDiagnostics): JSONObject =
        JSONObject()
            .put("processGeneration", process.processGeneration)
            .put("processName", process.processName)
            .put("pid", process.pid)
            .put("processStartTicks", process.processStartTicks)
            .put("capturedAtElapsedRealtimeNanos", process.capturedAtElapsedRealtimeNanos)
            .put("activeRunId", process.activeRunId ?: JSONObject.NULL)
            .put("mappedNativeLibraries", JSONArray(process.mappedNativeLibraries))
            .put("vmSizeBytes", process.memory.vmSizeBytes ?: JSONObject.NULL)
            .put("vmPeakBytes", process.memory.vmPeakBytes ?: JSONObject.NULL)
            .put("vmRssBytes", process.memory.vmRssBytes ?: JSONObject.NULL)
            .put("vmDataBytes", process.memory.vmDataBytes ?: JSONObject.NULL)
            .put("pssBytes", process.memory.pssBytes)
            .put("ussBytes", process.memory.ussBytes)
            .put("javaPssBytes", process.memory.javaPssBytes)
            .put("nativePssBytes", process.memory.nativePssBytes)
            .put("graphicsPssBytes", process.memory.graphicsPssBytes)
            .put("javaHeapAllocatedBytes", process.memory.javaHeapAllocatedBytes)
            .put("nativeHeapAllocatedBytes", process.memory.nativeHeapAllocatedBytes)
            .put("runtimeMaxMemoryBytes", process.memory.runtimeMaxMemoryBytes)
            .put("processCpuTimeMs", process.memory.processCpuTimeMs)
            .put("oomScoreAdj", process.memory.oomScoreAdj ?: JSONObject.NULL)
            .put("threadCount", process.memory.threadCount)
            .put("mappedRegionCount", process.memory.mappedRegionCount)
            .put("smapsSource", process.memory.smapsSource.name)
            .put(
                "anonHugePagesBytes",
                process.memory.anonHugePagesBytes ?: JSONObject.NULL,
            )
            .put(
                "largestFreeAddressGapBytes",
                process.memory.largestFreeAddressGapBytes ?: JSONObject.NULL,
            )
            .put("foregroundService", process.foregroundService.let { foreground ->
                JSONObject()
                    .put("activeLease", foreground.activeLease?.let { lease ->
                        JSONObject()
                            .put("leaseId", lease.request.leaseId)
                            .put("runId", lease.request.runId)
                            .put("processGeneration", lease.request.processGeneration)
                            .put("lifecycle", lease.lifecycle.name)
                            .put("notificationId", lease.notificationId)
                            .put("platformPolicy", lease.platformPolicy.name)
                            .put("startedAtElapsedRealtimeNanos",
                                lease.startedAtElapsedRealtimeNanos)
                            .put("attachedAtElapsedRealtimeNanos",
                                lease.attachedAtElapsedRealtimeNanos ?: JSONObject.NULL)
                    } ?: JSONObject.NULL)
                    .put("lastStoppedLease", foreground.lastStoppedLease?.let { lease ->
                        JSONObject()
                            .put("leaseId", lease.request.leaseId)
                            .put("runId", lease.request.runId)
                            .put("processGeneration", lease.request.processGeneration)
                            .put("lifecycle", lease.lifecycle.name)
                            .put("notificationId", lease.notificationId)
                            .put("platformPolicy", lease.platformPolicy.name)
                            .put("startedAtElapsedRealtimeNanos",
                                lease.startedAtElapsedRealtimeNanos)
                            .put("attachedAtElapsedRealtimeNanos",
                                lease.attachedAtElapsedRealtimeNanos ?: JSONObject.NULL)
                            .put("stoppedAtElapsedRealtimeNanos",
                                lease.stoppedAtElapsedRealtimeNanos ?: JSONObject.NULL)
                            .put("stopReason", lease.stopReason ?: JSONObject.NULL)
                    } ?: JSONObject.NULL)
            })
            .put("processingWakeLock", process.processingWakeLock.let { wakeLock ->
                JSONObject()
                    .put("platformHeld", wakeLock.platformHeld)
                    .put("activeLease", wakeLock.activeLease?.let { lease ->
                        processingWakeLockLeaseJson(lease)
                    } ?: JSONObject.NULL)
                    .put("lastReleasedLease", wakeLock.lastReleasedLease?.let { lease ->
                        processingWakeLockLeaseJson(lease)
                    } ?: JSONObject.NULL)
            })

    private fun processingWakeLockLeaseJson(
        lease: SourceSeparationProcessingWakeLockLeaseRecord,
    ): JSONObject = JSONObject()
        .put("leaseId", lease.request.leaseId)
        .put("runId", lease.request.runId)
        .put("processGeneration", lease.request.processGeneration)
        .put("tag", lease.tag)
        .put("lifecycle", lease.lifecycle.name)
        .put("acquiredAtElapsedRealtimeNanos", lease.acquiredAtElapsedRealtimeNanos)
        .put("expiresAtElapsedRealtimeNanos", lease.expiresAtElapsedRealtimeNanos)
        .put("releasedAtElapsedRealtimeNanos",
            lease.releasedAtElapsedRealtimeNanos ?: JSONObject.NULL)
        .put("releaseReason", lease.releaseReason ?: JSONObject.NULL)
        .put("events", JSONArray(lease.events.map { event ->
            JSONObject()
                .put("action", event.action.name)
                .put("timestampElapsedRealtimeNanos", event.timestampElapsedRealtimeNanos)
                .put("timeoutMs", event.timeoutMs ?: JSONObject.NULL)
                .put("reason", event.reason ?: JSONObject.NULL)
        }))

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
        val qualificationProfileId = arguments.getString(ARG_PROFILE_ID)
            ?: "cpu-default-fp32-v1"
        val requestedGpuRuntimeProfileId = if (backendMode == BackendMode.Auto) {
            arguments.getString(ARG_GPU_RUNTIME_PROFILE_ID)
                ?: MdxLiteRtBoundedGpuContract.PROFILE_ID
        } else {
            null
        }
        val x86ProcessValidation = arguments.optionalBoolean(
            ARG_X86_PROCESS_VALIDATION,
            false,
        )
        val arm32ResidentProcessValidation = arguments.optionalBoolean(
            ARG_ARM32_RESIDENT_PROCESS_VALIDATION,
            false,
        )
        assertEquals(x86ProcessValidation, MdxX86ProcessValidationOverride.buildEnabled)
        assertEquals(
            arm32ResidentProcessValidation,
            SourceSeparationArm32ResidentValidation.buildEnabled,
        )
        val activityManager = requireNotNull(context.getSystemService(ActivityManager::class.java))
        val deviceMemory = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val runtime = Runtime.getRuntime()
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
                .put("profileId", requestedGpuRuntimeProfileId ?: qualificationProfileId)
                .put("qualificationProfileId", qualificationProfileId)
                .put(
                    "requestedGpuRuntimeProfileId",
                    requestedGpuRuntimeProfileId ?: JSONObject.NULL,
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
                .put("totalMemoryBytes", deviceMemory.totalMem)
                .put("availableMemoryBytes", deviceMemory.availMem)
                .put("lowMemoryThresholdBytes", deviceMemory.threshold)
                .put("lowMemory", deviceMemory.lowMemory)
                .put("isLowRamDevice", activityManager.isLowRamDevice)
                .put("memoryClassMiB", activityManager.memoryClass)
                .put("largeMemoryClassMiB", activityManager.largeMemoryClass)
                .put("runtimeMaxMemoryBytes", runtime.maxMemory())
            )
            .put("processRoles", JSONObject()
                .put("mainPid", Process.myPid())
                .put("mainProcessName", AppProcessResolver.resolve(context).processName)
                .put("targetPackage", instrumentation.targetContext.packageName)
                .put("instrumentationPackage", instrumentation.context.packageName)
                .put("instrumentationSharesMainProcess", true)
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
                .put(
                    "coldProcessBoundary",
                    arguments.optionalBoolean(ARG_COLD_PROCESS_BOUNDARY, false),
                )
                .put("preRunProcessExitMs", arguments.optionalLong(ARG_PRE_RUN_PROCESS_EXIT_MS))
                .put(
                    "probeOriginalPlayback",
                    arguments.optionalBoolean(ARG_PROBE_ORIGINAL_PLAYBACK, false),
                )
                .put("x86ProcessValidation", x86ProcessValidation)
                .put("arm32ResidentProcessValidation", arm32ResidentProcessValidation)
                .put(
                    "xnnPackFlags",
                    arguments.getString(ARG_XNNPACK_FLAGS)?.toIntOrNull() ?: JSONObject.NULL,
                )
                .put(
                    "autoFailpoint",
                    arguments.getString(ARG_AUTO_FAILPOINT)
                        ?: Phase7AutoFailpoint.None.argumentValue,
                )
                .put(
                    "remoteAutoFailpoint",
                    arguments.getString(ARG_REMOTE_AUTO_FAILPOINT)
                        ?: MdxLiteRtRemoteFailpoint.None.argumentValue,
                )
                .put(
                    "autoFailInvocationCount",
                    arguments.optionalInt(
                        ARG_AUTO_FAIL_INVOCATION_COUNT,
                        DEFAULT_REMOTE_FAULT_INVOCATION_COUNT,
                    ),
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

    private fun applyRunAdmissionEvidence(
        report: JSONObject,
        request: SourceSeparationCacheRunJournalRequest,
        backendMode: BackendMode,
        requestedGpuRuntimeProfile: MdxLiteRtGpuRuntimeProfile?,
    ) {
        val expectedTryGpu = backendMode == BackendMode.Auto
        assertEquals(expectedTryGpu, request.tryGpu)
        val identity = request.gpuRuntimeIdentity
        assertEquals(expectedTryGpu, identity != null)

        val boundedRuntimeRequested = expectedTryGpu &&
            (requestedGpuRuntimeProfile == null ||
                requestedGpuRuntimeProfile == MdxLiteRtGpuRuntimeProfile.BoundedOpenClFp32V1)
        if (boundedRuntimeRequested) {
            val admitted = requireNotNull(identity)
            assertEquals(MdxLiteRtBoundedGpuContract.PROFILE_ID, admitted.profileId)
            assertEquals(MdxLiteRtBoundedGpuContract.ARTIFACT_VERSION, admitted.artifactVersion)
            assertEquals(
                MdxLiteRtBoundedGpuContract.CAPABILITY_SCHEMA_VERSION,
                admitted.capabilitySchemaVersion,
            )
            assertEquals(MdxLiteRtBoundedGpuContract.BACKEND, admitted.backend)
            assertEquals(MdxLiteRtBoundedGpuContract.PRECISION, admitted.precision)
            assertEquals(MdxLiteRtBoundedGpuContract.KERNEL_BATCH_SIZE, admitted.kernelBatchSize)
            assertEquals(
                MdxLiteRtBoundedGpuContract.COMMAND_QUEUE_WINDOW_SIZE,
                admitted.commandQueueWindowSize,
            )
        }

        report.put("runAdmission", JSONObject()
            .put("runClass", request.runClass.name)
            .put("backgroundPolicy", request.backgroundPolicy.name)
            .put("tryGpu", request.tryGpu)
            .put(
                "requestedGpuRuntimeProfileId",
                requestedGpuRuntimeProfile?.profileId
                    ?: MdxLiteRtBoundedGpuContract.PROFILE_ID.takeIf { expectedTryGpu }
                    ?: JSONObject.NULL,
            )
            .put(
                "admittedGpuRuntime",
                identity?.let { admitted ->
                    JSONObject()
                        .put("profileId", admitted.profileId)
                        .put("artifactVersion", admitted.artifactVersion)
                        .put("capabilitySchemaVersion", admitted.capabilitySchemaVersion)
                        .put("backend", admitted.backend)
                        .put("precision", admitted.precision)
                        .put("kernelBatchSize", admitted.kernelBatchSize)
                        .put("commandQueueWindowSize", admitted.commandQueueWindowSize)
                } ?: JSONObject.NULL,
            )
        )
        report.getJSONObject("matrixKey")
            .put("admittedGpuRuntimeProfileId", identity?.profileId ?: JSONObject.NULL)
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

    private fun validateRemoteFaultEvidence(
        report: JSONObject,
        evidence: MdxLiteRtRemoteFaultEvidence,
        expectedToken: String,
        expectedFailpoint: MdxLiteRtRemoteFailpoint,
        expectedPid: Int,
        expectedFallbackStage: String?,
        expectedBackend: String?,
        firstReadyAtElapsedMs: Long,
    ) {
        assertEquals(expectedToken, evidence.token)
        assertEquals(expectedFailpoint.argumentValue, evidence.failpoint)
        assertEquals(expectedPid, evidence.pid)
        assertTrue(evidence.processName.endsWith(":source_separation"))
        assertEquals(1, evidence.gpuCreateCount)
        assertEquals(1, evidence.gpuCloseCount)
        if (expectedFailpoint == MdxLiteRtRemoteFailpoint.Setup) {
            assertEquals(0, evidence.gpuInvocationCount)
        } else {
            assertTrue(evidence.gpuInvocationCount > 0)
        }
        assertTrue(evidence.injectedAtElapsedRealtimeMs != null)

        if (expectedFailpoint.requiresProcessRecycle) {
            assertEquals(0, evidence.cpuCreateCount)
            assertEquals(0, evidence.cpuCloseCount)
        } else {
            assertEquals(
                requireNotNull(expectedFailpoint.expectedFallbackStage).name,
                expectedFallbackStage,
            )
            assertEquals(MdxInferenceBackend.LiteRtCpu.name, expectedBackend)
            assertEquals(1, evidence.cpuCreateCount)
            assertEquals(1, evidence.cpuCloseCount)
            val gpuClose = evidence.events.indexOf("${MdxInferenceBackend.LiteRtGpu.name}-close")
            val cpuCreate = evidence.events.indexOf("${MdxInferenceBackend.LiteRtCpu.name}-create")
            assertTrue(
                "Remote CPU fallback preceded GPU cleanup: ${evidence.events}",
                gpuClose >= 0 && cpuCreate > gpuClose,
            )
        }
        if (expectedFailpoint in setOf(
                MdxLiteRtRemoteFailpoint.Invocation,
                MdxLiteRtRemoteFailpoint.OutputRead,
                MdxLiteRtRemoteFailpoint.NonFinite,
                MdxLiteRtRemoteFailpoint.Cleanup,
            )
        ) {
            assertTrue("No ready window preceded the remote failure.", firstReadyAtElapsedMs > 0L)
            assertTrue(
                "The remote failure preceded playback readiness.",
                firstReadyAtElapsedMs < requireNotNull(evidence.injectedAtElapsedRealtimeMs),
            )
        }
        report.put("remoteAutoFaultInjection", JSONObject()
            .put("token", evidence.token)
            .put("failpoint", evidence.failpoint)
            .put(
                "expectedFallbackStage",
                expectedFailpoint.expectedFallbackStage?.name ?: JSONObject.NULL,
            )
            .put("failureInvocationCount", evidence.failureInvocationCount)
            .put("pid", evidence.pid)
            .put("processName", evidence.processName)
            .put("injectedAtElapsedRealtimeMs", evidence.injectedAtElapsedRealtimeMs)
            .put("firstReadyAtElapsedMs", firstReadyAtElapsedMs)
            .put("gpuCreateCount", evidence.gpuCreateCount)
            .put("gpuCloseCount", evidence.gpuCloseCount)
            .put("gpuInvocationCount", evidence.gpuInvocationCount)
            .put("cpuCreateCount", evidence.cpuCreateCount)
            .put("cpuCloseCount", evidence.cpuCloseCount)
            .put("events", JSONArray(evidence.events))
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

    private enum class ProductTerminalCleanupAction(
        val stage: String,
        val displayName: String,
        val reportAction: String,
        val journalLifecycle: SourceSeparationCacheRunJournalLifecycle,
        val transitionType: SourceSeparationCacheRunTransitionType,
    ) {
        Pause(
            stage = "pause-cleanup",
            displayName = "Pause",
            reportAction = "pause",
            journalLifecycle = SourceSeparationCacheRunJournalLifecycle.Paused,
            transitionType = SourceSeparationCacheRunTransitionType.Paused,
        ),
        Cancel(
            stage = "cancel-cleanup",
            displayName = "Cancel",
            reportAction = "cancel",
            journalLifecycle = SourceSeparationCacheRunJournalLifecycle.Canceled,
            transitionType = SourceSeparationCacheRunTransitionType.UserCanceled,
        ),
    }

    private enum class PlaybackOwnedStopScenario(
        val argumentValue: String,
        val runClass: SourceSeparationExecutionRunClass,
    ) {
        PlaybackDemand(
            argumentValue = "playback-demand",
            runClass = SourceSeparationExecutionRunClass.PlaybackDemandWindow,
        ),
        NextSongPrefetch(
            argumentValue = "next-song-prefetch",
            runClass = SourceSeparationExecutionRunClass.NextSongPrefetch,
        ),
        ;

        companion object {
            fun parse(value: String?): PlaybackOwnedStopScenario = entries.singleOrNull {
                it.argumentValue == value
            } ?: error("Unsupported playback-owned stop scenario: $value")
        }
    }

    private enum class BackendMode(
        val argumentValue: String,
        val reportBackend: String,
        val executionPolicy: SourceSeparationExecutionBackendPolicy,
    ) {
        Cpu(
            "cpu",
            "LiteRtCpu",
            SourceSeparationExecutionBackendPolicy.Cpu,
        ),
        Auto(
            "auto",
            "LiteRtAuto",
            SourceSeparationExecutionBackendPolicy.Auto,
        ),
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
        IndependentForeground("independent-foreground"),
        ;

        val isRemote: Boolean
            get() = this != InProcess

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

    private data class ProcessSessionLeaseRelease(
        val diagnostics: SourceSeparationProcessDiagnostics,
        val elapsedMs: Long,
    )

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
        const val ARG_GPU_RUNTIME_PROFILE_ID = "gpuRuntimeProfileId"
        const val ARG_KARA_GPU_REQUALIFICATION = "karaGpuRequalification"
        const val ARG_EXECUTION_HOST_MODE = "executionHostMode"
        const val ARG_SCREEN_OFF_AFTER_READY = "screenOffAfterReady"
        const val ARG_AUTO_FAILPOINT = "autoFailpoint"
        const val ARG_REMOTE_AUTO_FAILPOINT = "remoteAutoFailpoint"
        const val ARG_AUTO_FAIL_INVOCATION_COUNT = "autoFailInvocationCount"
        const val ARG_REMOTE_FAULT_TOKEN = "remoteFaultToken"
        const val ARG_PROCESSOR_COUNT = "processorCount"
        const val ARG_PROCESS_CACHE_SCOPE = "processCacheScope"
        const val ARG_XNNPACK_FLAGS = "xnnPackFlags"
        const val ARG_WINDOW_DECODE_ENABLED = "windowDecodeEnabled"
        const val ARG_PRESERVE_MEDIA_STORE_SOURCE = "preserveMediaStoreSource"
        const val ARG_EXPORT_CACHE_AUDIO = "exportCacheAudio"
        const val ARG_CLEANUP_CACHE_AFTER_RUN = "cleanupCacheAfterRun"
        const val ARG_RUN_CLASS = "runClass"
        const val ARG_CLEAN_INSTALL = "cleanInstallScenario"
        const val ARG_X86_PROCESS_VALIDATION = "x86ProcessValidation"
        const val ARG_ARM32_RESIDENT_PROCESS_VALIDATION =
            "arm32ResidentProcessValidation"
        const val ARG_REBIND_AFTER_COMPLETION = "rebindAfterCompletion"
        const val ARG_PROBE_ORIGINAL_PLAYBACK = "probeOriginalPlayback"
        const val ARG_COLD_PROCESS_BOUNDARY = "coldProcessBoundary"
        const val ARG_PRE_RUN_PROCESS_EXIT_MS = "preRunProcessExitMs"
        const val ARG_STOP_WHEN_CLOSED_FROM_RECENTS = "stopWhenClosedFromRecents"
        const val ARG_PLAYBACK_OWNED_RUN_CLASS = "playbackOwnedRunClass"
        const val ARG_PLAYBACK_SOAK_MINUTES = "playbackSoakMinutes"
        const val ARG_PLAYBACK_SOAK_SEEK_COUNT = "playbackSoakSeekCount"
        const val ARG_PRESERVE_PLAYBACK_CACHE = "preservePlaybackCache"
        const val PROCESS_RESOURCE_SAMPLE_INTERVAL_MS = 1_000L
        const val PLAYBACK_RESOURCE_SAMPLE_INTERVAL_MS = 15_000L
        const val PROCESS_REBIND_SETTLE_MS = 250L
        const val TASK_REMOVAL_TIMEOUT_MS = 30_000L
        const val TASK_REMOVAL_CONTINUATION_TIMEOUT_MS = 120_000L
        const val TASK_REMOVAL_POLL_MS = 100L
        const val PLAYBACK_OWNER_STOP_TIMEOUT_MS = 30_000L
        const val PLAYBACK_OWNER_STOP_POLL_MS = 100L
        const val PLAYBACK_OWNER_STALE_OBSERVATION_MS = 2_000L
        const val PLAYBACK_OWNER_ACTIVE_PREFETCH_READY_WINDOWS = 1_000_000
        const val PLAYBACK_NOTIFICATION_ID = 1
        const val PROCESS_MATRIX_CYCLE_COUNT = 20
        const val PROCESS_MATRIX_REBIND_CYCLE = 1
        const val PROCESS_MODEL_SWITCH_COUNT = 20
        const val PROCESS_MATRIX_FIRST_PAUSE_CYCLE = 6
        const val PROCESS_MATRIX_MAXIMUM_PSS_GROWTH_BYTES = 64L * 1_024L * 1_024L
        const val PROCESS_MATRIX_MAXIMUM_MAP_GROWTH = 256
        const val PROCESS_MATRIX_LEASE_RELEASE_TIMEOUT_MS = 30_000L
        const val PROCESS_FAULT_RECYCLE_TIMEOUT_MS = 1L
        const val PROCESS_DEATH_TIMEOUT_MS = 10_000L
        const val PHASE4_DEATH_REPETITIONS = 3
        const val PHASE4_CACHE_CLEAR_REPETITIONS = 3
        const val PHASE4_REPRESENTATIVE_REPETITIONS = 1
        const val PHASE4_FAULT_HIT_TIMEOUT_MS = 120_000L
        const val PHASE4_EXECUTION_TIMEOUT_MS = 60_000L
        const val PHASE4_LOCK_RELEASE_TIMEOUT_MS = 30_000L
        const val PHASE4_FAULT_POLL_INTERVAL_MS = 20L
        const val PHASE4_MAIN_DEATH_DIRECTORY = "phase4-main-death"
        const val CACHE_MANAGEMENT_TIMEOUT_MS = 30_000L
        const val TEST_PLAYBACK_ENABLED_KEY = "source_separation.playback_enabled"
        const val TEST_REMEMBER_PER_SONG_KEY = "source_separation.remember_per_song"
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
        const val ARG_MODEL_SWITCH_AUTO_START = "modelSwitchAutoStart"
        const val ARG_MODEL_SWITCH_PLAYING = "modelSwitchPlaying"
        const val ARG_MODEL_SWITCH_BOUNDARY = "modelSwitchBoundary"
        const val REPORT_DIRECTORY = "phase7-validation-reports"
        const val ARTIFACT_EXPORT_DIRECTORY = "phase7-validation-artifacts"
        const val EXPECTED_NULL_VALUE = "__none__"
        const val DEFAULT_MAXIMUM_CANCELLATION_LATENCY_MS = 30_000L
        const val DEFAULT_REMOTE_FAULT_INVOCATION_COUNT = 6
        const val THERMAL_SAMPLE_INTERVAL_MS = 2_000L
        const val REQUIRED_READY_WINDOWS = 2
        const val TEST_BLEND = 0.23f
        const val MODEL_SWITCH_BOUNDARY_READY = "ready"
        const val MODEL_SWITCH_BOUNDARY_PREPARATION = "preparation"
        const val KARA_MODEL_ID = "uvr_mdxnet_kara"
        const val KARA_CONTRACT_ID = "uvr_mdxnet_kara@2"
        const val KARA_ARTIFACT_SHA256 =
            "4bf2fbd2c416a934cd5f9e3f8a154dc7c30bc616494216699ae2459c18f51c64"
        const val TEST_KEY_PLAYBACK_ENABLED = "source_separation.playback_enabled"
        const val TEST_KEY_REMEMBER_PER_SONG = "source_separation.remember_per_song"
        const val TEST_KEY_GLOBAL_BLEND = "source_separation.global_blend"
        fun testGlobalBlendKey(modelId: String): String =
            "$TEST_KEY_GLOBAL_BLEND.mdx.$modelId"

        val PLAYBACK_OWNER_TEST_PREFERENCE_KEYS = setOf(
            MINIMUM_SONG_DURATION,
            SOURCE_SEPARATION_AUTO_CACHE_CLEANUP,
            SOURCE_SEPARATION_AUTO_START,
            SOURCE_SEPARATION_AUTO_FLAC_COMPRESSION,
            SOURCE_SEPARATION_WINDOW_DECODE,
            SOURCE_SEPARATION_GPU_ENABLED,
            SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT,
            TEST_KEY_PLAYBACK_ENABLED,
            TEST_KEY_REMEMBER_PER_SONG,
        )
        const val SEEK_FROM_END_MS = 1_000L
        const val POLL_INTERVAL_MS = 250L
        const val REMOTE_IDLE_SETTLE_MS = 2_000L
        const val WORKER_TIMEOUT_MS = 20 * 60 * 1000L
        const val LIFECYCLE_TIMEOUT_MS = 5 * 60 * 1000L
        const val MEDIA_SESSION_TIMEOUT_SECONDS = 30L
        const val MEDIA_SESSION_TIMEOUT_MS = 30_000L
        const val OWNERSHIP_COMMAND_TIMEOUT_SECONDS = 3L * 60L
        const val OWNERSHIP_HANDOFF_TIMEOUT_MS = 30_000L
        const val OWNERSHIP_TERMINAL_TIMEOUT_MS = 30_000L
        const val OWNERSHIP_HANDOFF_POLL_MS = 25L
        const val REATTACHMENT_OBSERVER_TIMEOUT_MS = 30_000L
        const val REATTACHMENT_EXECUTION_TIMEOUT_SECONDS = 30L
        const val EXPECTED_REATTACHMENT_CALLBACK_FAILURE =
            "phase7-expected-observer-detachment"
        const val ORIGINAL_PLAYBACK_START_ATTEMPTS = 2
        const val ORIGINAL_PLAYBACK_RETRY_DELAY_MS = 2_000L
        const val MEDIA_SESSION_SEEK_TOLERANCE_MS = 250L
        const val MAX_PLAYBACK_SOAK_MINUTES = 120
        const val MAX_PLAYBACK_SOAK_SEEK_COUNT = 1_000
        const val PLAYBACK_SOAK_WAKE_LOCK_MARGIN_MS = 15 * 60_000L
        const val PLAYBACK_SOAK_RESOURCE_SAMPLE_INTERVAL_MS = 1_000L
        const val PLAYBACK_SOAK_STATE_SAMPLE_INTERVAL_MS = 100L
        const val PLAYBACK_SOAK_SEEK_POLL_INTERVAL_MS = 10L
        const val PLAYBACK_SOAK_SEEK_END_MARGIN_MS = 2_000L
        const val PLAYBACK_SOAK_MAXIMUM_NOT_PLAYING_SAMPLES = 10
        const val PLAYBACK_SOAK_RAPID_SCRUB_INTERVAL = 10
        const val PLAYBACK_SOAK_RAPID_SCRUB_REQUEST_COUNT = 3
        const val PLAYBACK_SOAK_PAUSE_RESUME_INTERVAL = 25
        const val PLAYBACK_SOAK_RANDOM_SEED = 0x504C41594241434BL
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
