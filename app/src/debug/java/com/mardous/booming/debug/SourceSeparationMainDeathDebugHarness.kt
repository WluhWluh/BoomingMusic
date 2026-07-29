package com.mardous.booming.debug

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
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
import com.mardous.booming.R
import com.mardous.booming.data.model.Song
import com.mardous.booming.separation.SourceSeparationExecutionRunClass
import com.mardous.booming.separation.SourceSeparationGpuFallbackLatch
import com.mardous.booming.separation.SourceSeparationRuntimeFacade
import com.mardous.booming.separation.SourceSeparationRuntimeSongResolution
import com.mardous.booming.separation.SourceSeparationRuntimeUnavailableReason
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheMutationResult
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheManifestState
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheFaultAction
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheFaultControl
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheFaultHit
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheFaultInjection
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheFaultStage
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunJournal
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunJournalLifecycle
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunTransitionType
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheLockOwner
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheLockPurpose
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheStore
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheValidationResult
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheModelAvailability
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwareCachePlayback
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwareCacheEntryState
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwareCacheStatus
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwarePlayableStatus
import com.mardous.booming.separation.model.AndroidMdxRuntimePlatformProvider
import com.mardous.booming.separation.model.MdxCompatibilityPolicy
import com.mardous.booming.separation.model.MdxInferenceBackend
import com.mardous.booming.separation.model.MdxLiteRtCompatibilityResolver
import com.mardous.booming.separation.model.MdxRuntimePrecision
import com.mardous.booming.separation.model.litert.MdxLiteRtGpuRuntimeProfile
import com.mardous.booming.separation.model.preset.SourceSeparationActivePresetState
import com.mardous.booming.separation.model.preset.SourceSeparationPresetDeletionException
import com.mardous.booming.separation.model.preset.SourceSeparationPresetRepository
import com.mardous.booming.separation.model.preset.SourceSeparationPresetSelectionScope
import com.mardous.booming.separation.process.SourceSeparationProcessingOwnershipHandoff
import com.mardous.booming.separation.process.SourceSeparationProcParser
import com.mardous.booming.separation.process.SourceSeparationProcessSessionState
import com.mardous.booming.separation.process.ipc.BoundRemoteSourceSeparationExecutionHost
import com.mardous.booming.separation.process.ipc.SourceSeparationMediaProcessingForegroundController
import com.mardous.booming.ui.screen.player.SourceSeparationForegroundWorkerCoordinator
import com.mardous.booming.ui.screen.player.SourceSeparationModelAwareCacheManagementUiState
import com.mardous.booming.ui.screen.player.SourceSeparationUiState
import com.mardous.booming.util.MINIMUM_SONG_DURATION
import com.mardous.booming.util.SOURCE_SEPARATION_AUTO_START
import com.mardous.booming.util.SOURCE_SEPARATION_AUTO_CACHE_CLEANUP
import com.mardous.booming.util.SOURCE_SEPARATION_AUTO_FLAC_COMPRESSION
import com.mardous.booming.util.SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT
import com.mardous.booming.util.SOURCE_SEPARATION_TRY_GPU
import com.mardous.booming.util.SOURCE_SEPARATION_WINDOW_DECODE
import org.json.JSONArray
import org.json.JSONObject
import org.koin.java.KoinJavaComponent.get
import java.io.File
import java.security.MessageDigest

/** Debug-only process-death harness driven by the Phase 7 ADB runner. */
internal object SourceSeparationMainDeathDebugHarness {

    fun handle(context: Context, command: String, intent: Intent): Boolean {
        return when (command) {
            COMMAND_BEGIN -> {
                val request = request(intent)
                launch("SrcSepMainDeathBegin") {
                    begin(context.applicationContext, request, BeginMode.MainProcessDeath)
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
            COMMAND_BEGIN_FORCE_STOP -> {
                val request = request(intent)
                launch("SrcSepForceStopBegin") {
                    begin(context.applicationContext, request, BeginMode.ForceStop)
                }
                true
            }
            COMMAND_VALIDATE_FORCE_STOP -> {
                val request = request(intent)
                launch("SrcSepForceStopValidate") {
                    validateForceStop(context.applicationContext, request, intent)
                }
                true
            }
            COMMAND_BEGIN_REMOTE_DEATH -> {
                val request = request(intent)
                launch("SrcSepRemoteDeathBegin") {
                    begin(context.applicationContext, request, BeginMode.RemoteProcessDeath)
                }
                true
            }
            COMMAND_VALIDATE_REMOTE_DEATH -> {
                val request = request(intent)
                launch("SrcSepRemoteDeathValidate") {
                    validateRemoteDeath(context.applicationContext, request, intent)
                }
                true
            }
            else -> false
        }
    }

    private fun begin(context: Context, request: Request, mode: BeginMode) {
        val scenarioFile = scenarioFile(context, request.runId)
        val reportFile = reportFile(context, request.runId)
        scenarioFile.delete()
        reportFile.delete()
        var mediaUri: Uri? = null
        var armedFaultRoot: File? = null
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

            val worker = get<SourceSeparationForegroundWorkerCoordinator>(
                SourceSeparationForegroundWorkerCoordinator::class.java,
            )
            worker.updateSong(
                song = source,
                positionMs = 0L,
                durationMs = source.duration,
                isPlaying = false,
                sourceSeparationBlend = TEST_BLEND,
            )
            worker.requestManualSong(source)

            val store = get<SourceSeparationCacheStore>(SourceSeparationCacheStore::class.java)
            val killBoundary = when (mode) {
                BeginMode.MainProcessDeath -> request.mainDeathBoundary
                BeginMode.ForceStop -> MainDeathBoundary.SegmentRunning
                BeginMode.RemoteProcessDeath -> MainDeathBoundary.AfterFirstCommittedSegment
            }
            var journal = waitForJournal(SETUP_TIMEOUT_MS) {
                val cacheKey = worker.runningCacheKey() ?: return@waitForJournal null
                val candidate = store.readRunJournal(cacheKey) ?: return@waitForJournal null
                candidate.takeIf { current ->
                    current.lifecycle == SourceSeparationCacheRunJournalLifecycle.Running &&
                        current.request.runClass ==
                            SourceSeparationExecutionRunClass.ManualFullSong &&
                        killBoundary.reached(current)
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
            var faultHit: SourceSeparationCacheFaultHit? = null
            var faultToken: String? = null
            if (mode == BeginMode.MainProcessDeath && killBoundary.faultStage != null) {
                val root = store.root().directory.also { armedFaultRoot = it }
                val token = "main-death-${request.runId}".take(MAX_FAULT_TOKEN_LENGTH)
                faultToken = token
                SourceSeparationCacheFaultInjection.arm(
                    root,
                    SourceSeparationCacheFaultControl(
                        token = token,
                        stage = killBoundary.faultStage,
                        action = SourceSeparationCacheFaultAction.Barrier,
                        timeoutMs = REMOTE_DEATH_BARRIER_TIMEOUT_MS,
                    ),
                )
                faultHit = waitForCacheFaultHit(
                    root = root,
                    token = token,
                    expectedStage = killBoundary.faultStage,
                    timeoutMs = COMPLETION_TIMEOUT_MS,
                )
                check(faultHit.pid == remotePid) {
                    "The main-death barrier was reached by another process."
                }
                journal = requireNotNull(store.readRunJournal(runtimeSong.cacheKey))
                check(journal.lifecycle == SourceSeparationCacheRunJournalLifecycle.Running)
                val terminalManifest = requireNotNull(
                    store.readManifest(runtimeSong.cacheKey),
                )
                check(terminalManifest.state == SourceSeparationCacheManifestState.Completed)
                check(journal.committedSegments.size ==
                    terminalManifest.segmentPlan?.segmentCount)
            }
            if (mode == BeginMode.RemoteProcessDeath) {
                val root = store.root().directory.also { armedFaultRoot = it }
                val token = "remote-death-${request.runId}".take(MAX_FAULT_TOKEN_LENGTH)
                faultToken = token
                SourceSeparationCacheFaultInjection.arm(
                    root,
                    SourceSeparationCacheFaultControl(
                        token = token,
                        stage = SourceSeparationCacheFaultStage.NativeInvocation,
                        action = SourceSeparationCacheFaultAction.Barrier,
                        timeoutMs = REMOTE_DEATH_BARRIER_TIMEOUT_MS,
                    ),
                )
                faultHit = waitForCacheFaultHit(root, token)
                check(faultHit.pid == remotePid) {
                    "The remote-death barrier was reached by another process."
                }
                journal = requireNotNull(store.readRunJournal(runtimeSong.cacheKey))
                check(journal.lifecycle == SourceSeparationCacheRunJournalLifecycle.Running)
                check(journal.committedSegments.isNotEmpty())
            }
            val journalFile = File(
                store.entryDirectory(runtimeSong.cacheKey),
                SourceSeparationCacheStore.RUN_JOURNAL_FILE_NAME,
            )
            check(journalFile.isFile)
            val plannedSegments = requireNotNull(
                store.readManifest(runtimeSong.cacheKey)?.segmentPlan,
            ).segments.size
            val connectedObserver = journal.transitions.lastOrNull { transition ->
                transition.type == SourceSeparationCacheRunTransitionType.ObserverConnected ||
                    transition.type ==
                        SourceSeparationCacheRunTransitionType.ObserverDisconnected
            }?.takeIf { transition ->
                transition.type == SourceSeparationCacheRunTransitionType.ObserverConnected
            }
            if (mode == BeginMode.RemoteProcessDeath) {
                check(plannedSegments == EXPECTED_FULL_SONG_SEGMENTS) {
                    "The remote-death gate requires the 48-segment 9662 fixture."
                }
                check(connectedObserver != null) {
                    "The remote-death gate did not observe the admitted host observer."
                }
            }
            val remoteProcessStartTicks = SourceSeparationProcParser.parseProcessStartTicks(
                File("/proc/$remotePid/stat").readText(),
            ) ?: error("Could not read the authoritative remote process start ticks.")
            val processingNotification = processingNotificationSnapshot(context).also { snapshot ->
                validateProcessingNotification(
                    context = context,
                    snapshot = snapshot,
                    displayName = source.fileName,
                )
            }

            writeJson(
                scenarioFile,
                JSONObject()
                    .put("schemaVersion", SCENARIO_SCHEMA_VERSION)
                    .put("stage", mode.stage)
                    .put("runId", request.runId)
                    .put("cacheKey", runtimeSong.cacheKey)
                    .put("remoteDeathRecoveryAction",
                        request.remoteDeathRecoveryAction.argumentValue)
                    .put("sourceMediaUri", mediaUri.toString())
                    .put("sourcePath", request.sourcePath)
                    .put("mainPid", Process.myPid())
                    .put("remotePid", remotePid)
                    .put("remoteProcessStartTicks", remoteProcessStartTicks)
                    .put("remoteProcessGeneration", journal.request.processGeneration)
                    .put("executionRunId", journal.request.runId)
                    .put("journalSequence", journal.latestSequence)
                    .put("cacheRootPath", store.root().directory.absolutePath)
                    .put("journalPath", journalFile.absolutePath)
                    .put("journalSha256", journalFile.sha256())
                    .put("committedSegments", journal.committedSegments.size)
                    .put("committedSegmentEvidence", committedSegmentEvidence(journal))
                    .put("plannedSegments", plannedSegments)
                    .put("runClass", journal.request.runClass.name)
                    .put("backgroundPolicy", journal.request.backgroundPolicy.name)
                    .put("observerId", connectedObserver?.observerId ?: JSONObject.NULL)
                    .put(
                        "observerProcessName",
                        connectedObserver?.observerProcessName ?: JSONObject.NULL,
                    )
                    .put("processingNotificationBeforeMainDeath", processingNotification)
                    .put("admittedGpuRuntime", gpuRuntimeJson(journal))
                    .put(
                        "admittedGpuFallbackLatch",
                        journal.request.gpuFallbackLatch?.let { latch ->
                            JSONObject()
                                .put("stage", latch.stage)
                                .put("reason", latch.reason ?: JSONObject.NULL)
                        } ?: JSONObject.NULL,
                    )
                    .put("killBoundary", killBoundary.argumentValue)
                    .put("killRequester", mode.killRequester)
                    .put("backendMode", request.backendMode)
                    .put("tryGpu", request.tryGpu)
                    .put("faultToken", faultToken ?: JSONObject.NULL)
                    .put("faultHitPid", faultHit?.pid ?: JSONObject.NULL)
                    .put("faultHitRuntime", faultRuntimeJson(faultHit))
                    .put(
                        "faultHitElapsedRealtimeNanos",
                        faultHit?.reachedAtElapsedRealtimeNanos ?: JSONObject.NULL,
                    ),
            )
            Log.i(TAG, "${mode.stage} scenario is ready for ${request.runId}.")
            if (mode == BeginMode.MainProcessDeath) {
                SystemClock.sleep(MAIN_DEATH_SETTLE_MS)
                Process.killProcess(Process.myPid())
                error("The debug main process survived its requested death.")
            }
        } catch (error: Throwable) {
            armedFaultRoot?.let(SourceSeparationCacheFaultInjection::clear)
            mediaUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            scenarioFile.delete()
            writeFailure(reportFile, request, mode.stage, "setup", error)
            Log.e(TAG, "Could not prepare ${mode.stage} scenario ${request.runId}.", error)
        }
    }

    private fun validateRemoteDeath(
        context: Context,
        request: Request,
        intent: Intent,
    ) {
        val scenarioFile = scenarioFile(context, request.runId)
        val outputFile = reportFile(context, request.runId)
        var mediaUri: Uri? = null
        var worker: SourceSeparationForegroundWorkerCoordinator? = null
        var presetRepository: SourceSeparationPresetRepository? = null
        var primaryModelBackup: File? = null
        var primaryModelDestination: File? = null
        var primaryModelRestored = false
        var originalTryGpuForRestore: Boolean? = null
        try {
            val evidence = remoteDeathEvidence(intent)
            val scenario = JSONObject(scenarioFile.readText(Charsets.UTF_8))
            check(scenario.getInt("schemaVersion") == SCENARIO_SCHEMA_VERSION)
            check(scenario.getString("stage") == STAGE_REMOTE_DEATH)
            check(scenario.getString("runId") == request.runId)
            check(scenario.getString("backendMode") == request.backendMode)
            check(scenario.getString("remoteDeathRecoveryAction") ==
                request.remoteDeathRecoveryAction.argumentValue)
            check(scenario.getString("killBoundary") ==
                MainDeathBoundary.AfterFirstCommittedSegment.argumentValue)
            check(scenario.getString("killRequester") ==
                BeginMode.RemoteProcessDeath.killRequester)
            check(request.modelId == EXPECTED_FULL_SONG_MODEL_ID)
            check(scenario.getInt("mainPid") == Process.myPid()) {
                "The main process changed during inference-process death."
            }
            val cacheKey = scenario.getString("cacheKey")
            val oldRemotePid = scenario.getInt("remotePid")
            val oldRemoteProcessStartTicks = scenario.getLong("remoteProcessStartTicks")
            val oldProcessGeneration = scenario.getLong("remoteProcessGeneration")
            val oldExecutionRunId = scenario.getString("executionRunId")
            val oldObserverId = scenario.getString("observerId")
            val oldObserverProcessName = scenario.getString("observerProcessName")
            val originalTryGpu = scenario.getBoolean("tryGpu")
                .also { originalTryGpuForRestore = it }
            val plannedSegments = scenario.getInt("plannedSegments")
            check(plannedSegments == EXPECTED_FULL_SONG_SEGMENTS)
            mediaUri = Uri.parse(scenario.getString("sourceMediaUri"))

            check(!File("/proc/$oldRemotePid").exists())
            check(evidence.mainProcessSurvived)
            check(evidence.mainProcessSampleCount > 0L)
            check(evidence.remoteProcessSampleCount > 0L)
            check(evidence.mainDisappearanceCount == 0L)
            check(evidence.silentProcessSampleCount > 0L)
            check(evidence.unexpectedRemoteRelaunchCount == 0L)
            check(evidence.unexpectedRemotePresenceSampleCount == 0L)
            check(evidence.killExitElapsedMs >= 0L)
            check(!evidence.packageStoppedBeforeKill)
            check(!evidence.packageStoppedAfterDeath)
            check(!evidence.packageStoppedAfterSilence)
            check(evidence.processingServiceBeforeKill)
            check(evidence.notificationBeforeKill)
            check(evidence.wakeLockBeforeKill)
            check(!evidence.processingServiceAfterDeath)
            check(!evidence.notificationAfterDeath)
            check(!evidence.wakeLockAfterDeath)
            check(!evidence.processingServiceAfterSilence)
            check(!evidence.notificationAfterSilence)
            check(!evidence.wakeLockAfterSilence)
            check(evidence.journalSha256BeforeKill == evidence.journalSha256AfterDeath)
            check(evidence.journalSha256BeforeKill == evidence.journalSha256AfterSilence)
            check(evidence.journalSequenceBeforeKill == evidence.journalSequenceAfterDeath)
            check(evidence.journalSequenceBeforeKill == evidence.journalSequenceAfterSilence)
            check(evidence.entrySha256BeforeKill == evidence.entrySha256AfterDeath)
            check(evidence.entrySha256BeforeKill == evidence.entrySha256AfterSilence)
            check(evidence.entryFileCountBeforeKill == evidence.entryFileCountAfterDeath)
            check(evidence.entryFileCountBeforeKill == evidence.entryFileCountAfterSilence)
            check(evidence.entryBytesBeforeKill == evidence.entryBytesAfterDeath)
            check(evidence.entryBytesBeforeKill == evidence.entryBytesAfterSilence)

            val store = get<SourceSeparationCacheStore>(SourceSeparationCacheStore::class.java)
            val journalAfterDeath = requireNotNull(store.readRunJournal(cacheKey))
            check(journalAfterDeath.lifecycle == SourceSeparationCacheRunJournalLifecycle.Running)
            check(journalAfterDeath.request.runId == oldExecutionRunId)
            check(journalAfterDeath.request.processGeneration == oldProcessGeneration)
            check(journalAfterDeath.request.ownerPid == oldRemotePid)
            check(journalAfterDeath.request.tryGpu == originalTryGpu)
            check(journalAfterDeath.request.runClass == SourceSeparationExecutionRunClass.ManualFullSong)
            check(journalAfterDeath.request.backgroundPolicy ==
                SourceSeparationExecutionRunClass.ManualFullSong.backgroundPolicy)
            check(journalAfterDeath.latestSequence == evidence.journalSequenceAfterSilence)
            val committedBeforeDeath = scenario.getJSONArray("committedSegmentEvidence")
            check(committedBeforeDeath.length() == scenario.getInt("committedSegments"))
            check(committedBeforeDeath.length() > 0)
            validateCommittedSegmentEvidence(committedBeforeDeath, journalAfterDeath)

            val source = resolveMediaStoreSong(context, requireNotNull(mediaUri), request.sourcePath)
            val runtime = get<SourceSeparationRuntimeFacade>(
                SourceSeparationRuntimeFacade::class.java,
            )
            val runtimeSong = (runtime.resolve(source) as?
                SourceSeparationRuntimeSongResolution.Ready)?.song
                ?: error("The remote-death source could not be resolved for explicit resume.")
            check(runtimeSong.cacheKey == cacheKey)
            val coordinator = get<SourceSeparationForegroundWorkerCoordinator>(
                SourceSeparationForegroundWorkerCoordinator::class.java,
            ).also { worker = it }
            val failureMessage = context.getString(
                R.string.source_separation_process_stopped_unexpectedly,
            )
            check(waitUntil(REATTACH_TIMEOUT_MS) {
                val state = coordinator.workerStateFlow.value
                state is SourceSeparationUiState.Failed &&
                    state.songId == source.id &&
                    state.message == failureMessage &&
                    !coordinator.isWorkerActive() &&
                    coordinator.runningCacheKey() == null &&
                    coordinator.protectedCacheKeys().isEmpty() &&
                    coordinator.pendingSongId() == null
            }) { "The product did not settle in its stable remote-death failure state." }
            val handoff = get<SourceSeparationProcessingOwnershipHandoff>(
                SourceSeparationProcessingOwnershipHandoff::class.java,
            )
            check(waitUntil(REATTACH_TIMEOUT_MS) {
                handoff.stateFlow.value.activeOwner == null
            }) { "Processing ownership survived inference-process death." }
            val releasedOwner = requireNotNull(handoff.stateFlow.value.lastReleasedOwner)
            check(releasedOwner.owner.runId == oldExecutionRunId)
            check(releasedOwner.releaseReason == "execution-host-closed")
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
            check(notificationManager.activeNotifications.none { notification ->
                notification.id == SourceSeparationMediaProcessingForegroundController
                    .NOTIFICATION_ID
            })

            val lockReleaseStarted = SystemClock.elapsedRealtime()
            val lockReleaseDeadline = lockReleaseStarted + REATTACH_TIMEOUT_MS
            var cacheLockReleased = false
            while (SystemClock.elapsedRealtime() < lockReleaseDeadline) {
                val lease = store.entryLocks().tryAcquire(
                    cacheKey,
                    SourceSeparationCacheLockOwner(
                        purpose = SourceSeparationCacheLockPurpose.Other,
                        pid = Process.myPid(),
                    ),
                )
                if (lease != null) {
                    lease.close()
                    cacheLockReleased = true
                    break
                }
                SystemClock.sleep(POLL_MS)
            }
            check(cacheLockReleased) { "The cache write lock survived remote process death." }
            val cacheLockReleaseMs = SystemClock.elapsedRealtime() - lockReleaseStarted

            SourceSeparationCacheFaultInjection.clear(store.root().directory)
            val preferences = get<SharedPreferences>(SharedPreferences::class.java)
            val persistedTryGpu = if (request.remoteDeathRecoveryAction ==
                RemoteDeathRecoveryAction.SwitchModelThenStart
            ) {
                true
            } else {
                !originalTryGpu
            }
            check(preferences.edit()
                .putBoolean(SOURCE_SEPARATION_TRY_GPU, persistedTryGpu)
                .commit()
            ) { "Could not toggle the GPU preference before explicit resume." }
            check(preferences.getBoolean(SOURCE_SEPARATION_TRY_GPU, originalTryGpu) ==
                persistedTryGpu)
            if (request.remoteDeathRecoveryAction ==
                RemoteDeathRecoveryAction.RejectArtifactMismatch
            ) {
                check(originalTryGpu) {
                    "Artifact-mismatch recovery requires an admitted GPU run."
                }
                val admittedRuntime = requireNotNull(
                    journalAfterDeath.request.gpuRuntimeIdentity,
                )
                val retiredRuntime = admittedRuntime.copy(
                    artifactVersion = "${admittedRuntime.artifactVersion}-retired",
                )
                val mismatchedJournal = journalAfterDeath.copy(
                    request = journalAfterDeath.request.copy(
                        gpuRuntimeIdentity = retiredRuntime,
                    ),
                )
                val mismatchMessage = context.getString(
                    R.string.source_separation_model_load_failed,
                )
                store.writeRunJournal(mismatchedJournal)
                try {
                    coordinator.clearStatusIfNotRunning()
                    check(coordinator.workerStateFlow.value == SourceSeparationUiState.Idle)
                    coordinator.updateSong(
                        song = source,
                        positionMs = 0L,
                        durationMs = source.duration,
                        isPlaying = false,
                        sourceSeparationBlend = TEST_BLEND,
                    )
                    check(coordinator.startCurrentSong())
                    check(waitUntil(REATTACH_TIMEOUT_MS) {
                        val state = coordinator.workerStateFlow.value
                        state is SourceSeparationUiState.Failed &&
                            state.songId == source.id &&
                            state.message == mismatchMessage &&
                            !coordinator.isWorkerActive() &&
                            coordinator.runningCacheKey() == null &&
                            coordinator.protectedCacheKeys().isEmpty() &&
                            coordinator.pendingSongId() == null
                    }) { "The retired runtime did not settle in its typed failure state." }
                    check(store.readRunJournal(cacheKey) == mismatchedJournal) {
                        "Runtime mismatch rejection mutated the abandoned journal."
                    }
                    repeat(REJECTED_RETRY_POSITION_UPDATE_COUNT) { index ->
                        coordinator.updatePosition(
                            positionMs = (index + 1L) * 1_000L,
                            durationMs = source.duration,
                            isPlaying = true,
                            sourceSeparationBlend = TEST_BLEND,
                        )
                    }
                    SystemClock.sleep(REJECTED_RETRY_POSITION_SETTLE_MS)
                    check(!coordinator.isWorkerActive() &&
                        coordinator.debugStatus().contains("activated=false") &&
                        store.readRunJournal(cacheKey) == mismatchedJournal
                    ) { "Playback updates restarted rejected runtime work." }
                    check(handoff.stateFlow.value.activeOwner == null)
                    check(notificationManager.activeNotifications.none { notification ->
                        notification.id == SourceSeparationMediaProcessingForegroundController
                            .NOTIFICATION_ID
                    })
                } finally {
                    store.writeRunJournal(journalAfterDeath)
                }
                check(store.readRunJournal(cacheKey) == journalAfterDeath)
                check(preferences.edit()
                    .putBoolean(SOURCE_SEPARATION_TRY_GPU, originalTryGpu)
                    .commit()
                ) { "Could not restore the GPU preference after mismatch validation." }
                originalTryGpuForRestore = null

                writeJson(
                    outputFile,
                    JSONObject()
                        .put("schemaVersion",
                            REMOTE_DEATH_RUNTIME_POLICY_REPORT_SCHEMA_VERSION)
                        .put("status", "passed")
                        .put("stage", STAGE_REMOTE_DEATH)
                        .put("recoveryAction",
                            request.remoteDeathRecoveryAction.argumentValue)
                        .put("runId", request.runId)
                        .put("cacheKey", cacheKey)
                        .put("mainPid", Process.myPid())
                        .put("oldRemotePid", oldRemotePid)
                        .put("oldProcessGeneration", oldProcessGeneration)
                        .put("oldExecutionRunId", oldExecutionRunId)
                        .put("automaticRetryBudget", 0)
                        .put("automaticRemoteRelaunchCount",
                            evidence.unexpectedRemoteRelaunchCount)
                        .put("unexpectedRemotePresenceSampleCount",
                            evidence.unexpectedRemotePresenceSampleCount)
                        .put("silentObservationMs", evidence.silentObservationMs)
                        .put("journalSequenceBeforeKill",
                            evidence.journalSequenceBeforeKill)
                        .put("journalSequenceAfterSilence",
                            evidence.journalSequenceAfterSilence)
                        .put("committedSegmentsPreserved",
                            committedBeforeDeath.length())
                        .put("admittedArtifactVersion",
                            admittedRuntime.artifactVersion)
                        .put("retiredArtifactVersion", retiredRuntime.artifactVersion)
                        .put("mismatchJournalUnchanged", true)
                        .put("originalJournalRestored", true)
                        .put("persistedTryGpuBeforeRetry", persistedTryGpu)
                        .put("admittedTryGpu", originalTryGpu)
                        .put("backendPolicyPreserved", true)
                        .put("positionUpdatesAfterRejection",
                            REJECTED_RETRY_POSITION_UPDATE_COUNT)
                        .put("positionUpdatesRestartedWorker", false)
                        .put("typedFailureMessage", mismatchMessage)
                        .put("cacheWriteLockReleased", cacheLockReleased)
                        .put("cacheLockReleaseMs", cacheLockReleaseMs)
                        .put("terminalNotification", false),
                )
                Log.i(TAG, "Remote-death artifact mismatch passed for ${request.runId}.")
                return
            }
            if (request.remoteDeathRecoveryAction ==
                RemoteDeathRecoveryAction.RejectModelLoss
            ) {
                val repository = get<SourceSeparationPresetRepository>(
                    SourceSeparationPresetRepository::class.java,
                ).also { presetRepository = it }
                val primaryInstalled = repository.requireInstalledPreset(
                    request.artifactSha256,
                )
                val backupDirectory = File(
                    File(context.filesDir, MODEL_BACKUP_DIRECTORY),
                    request.runId,
                ).apply {
                    check(isDirectory || mkdirs()) {
                        "Could not create the missing-model backup directory."
                    }
                }
                val backup = File(backupDirectory, "${request.artifactSha256}.tflite")
                val destination = primaryInstalled.file
                primaryModelBackup = backup
                primaryModelDestination = destination
                check(!backup.exists() || backup.delete()) {
                    "Could not replace the stale missing-model backup."
                }
                if (!destination.renameTo(backup)) {
                    destination.copyTo(backup, overwrite = false)
                    check(backup.length() == primaryInstalled.byteSize)
                    check(backup.sha256().equals(
                        request.artifactSha256,
                        ignoreCase = true,
                    ))
                    check(destination.delete()) {
                        "Could not hide the active model after copying its backup."
                    }
                }
                check(!destination.exists())
                check(backup.length() == primaryInstalled.byteSize)
                check(backup.sha256().equals(request.artifactSha256, ignoreCase = true))
                check(repository.installedModel(request.artifactSha256) == null)
                val missingActive = repository.activeModel()
                    as? SourceSeparationActivePresetState.Reference
                    ?: error("The missing model lost its active reference.")
                check(missingActive.reference.artifactSha256.equals(
                    request.artifactSha256,
                    ignoreCase = true,
                ))
                check(missingActive.installedModel == null)

                val missingResolution = runtime.resolve(source)
                    as? SourceSeparationRuntimeSongResolution.Unavailable
                    ?: error("The absent model still resolved as runnable.")
                check(missingResolution.reason ==
                    SourceSeparationRuntimeUnavailableReason.ModelNotInstalled)
                check(missingResolution.reference?.artifactSha256?.equals(
                    request.artifactSha256,
                    ignoreCase = true,
                ) == true)
                val missingEntry = runtime.entries().single { entry ->
                    entry.cacheKey == cacheKey
                }
                check(missingEntry.state == SourceSeparationModelAwareCacheEntryState.Stale)
                check(missingEntry.modelAvailability ==
                    SourceSeparationCacheModelAvailability.ModelNotInstalled)
                check(missingEntry.readySegments == committedBeforeDeath.length())
                check(store.readRunJournal(cacheKey) == journalAfterDeath)
                validateCommittedSegmentEvidence(
                    committedBeforeDeath,
                    requireNotNull(store.readRunJournal(cacheKey)),
                )

                val missingMessage = context.getString(
                    R.string.source_separation_model_missing,
                )
                val remoteProcessPidsDuringMissingBoundary = linkedSetOf<Int>()
                fun sampleMissingBoundaryProcesses() {
                    remoteProcessPidsDuringMissingBoundary.addAll(
                        inferenceProcessIds(context),
                    )
                }
                sampleMissingBoundaryProcesses()
                check(remoteProcessPidsDuringMissingBoundary.isEmpty()) {
                    "An inference process existed before the missing-model retry."
                }
                coordinator.clearStatusIfNotRunning()
                check(coordinator.workerStateFlow.value == SourceSeparationUiState.Idle)
                coordinator.updateSong(
                    song = source,
                    positionMs = 0L,
                    durationMs = source.duration,
                    isPlaying = false,
                    sourceSeparationBlend = TEST_BLEND,
                )
                check(coordinator.startCurrentSong())
                check(waitUntil(REATTACH_TIMEOUT_MS) {
                    sampleMissingBoundaryProcesses()
                    val state = coordinator.workerStateFlow.value
                    state is SourceSeparationUiState.Failed &&
                        state.songId == source.id &&
                        state.message == missingMessage &&
                        !coordinator.isWorkerActive() &&
                        coordinator.runningCacheKey() == null &&
                        coordinator.protectedCacheKeys().isEmpty() &&
                        coordinator.pendingSongId() == null
                }) { "The missing model did not settle in its typed failure state." }
                repeat(REJECTED_RETRY_POSITION_UPDATE_COUNT) { index ->
                    coordinator.updatePosition(
                        positionMs = (index + 1L) * 1_000L,
                        durationMs = source.duration,
                        isPlaying = true,
                        sourceSeparationBlend = TEST_BLEND,
                    )
                    sampleMissingBoundaryProcesses()
                }
                SystemClock.sleep(REJECTED_RETRY_POSITION_SETTLE_MS)
                sampleMissingBoundaryProcesses()
                check(remoteProcessPidsDuringMissingBoundary.isEmpty()) {
                    "The missing-model retry started an inference process."
                }
                check(!coordinator.isWorkerActive() &&
                    coordinator.debugStatus().contains("activated=false") &&
                    store.readRunJournal(cacheKey) == journalAfterDeath
                ) { "Playback updates restarted missing-model work." }
                check(handoff.stateFlow.value.activeOwner == null)
                check(notificationManager.activeNotifications.none { notification ->
                    notification.id == SourceSeparationMediaProcessingForegroundController
                        .NOTIFICATION_ID
                })

                restoreMovedPrimaryModel(
                    repository = repository,
                    request = request,
                    backup = backup,
                    destination = destination,
                )
                primaryModelRestored = true
                val restoredSong = (runtime.resolve(source) as?
                    SourceSeparationRuntimeSongResolution.Ready)?.song
                    ?: error("The restored model did not resolve as runnable.")
                check(restoredSong.identity == runtimeSong.identity)
                check(restoredSong.cacheKey == cacheKey)
                val restoredEntry = runtime.entries().single { entry ->
                    entry.cacheKey == cacheKey
                }
                check(restoredEntry.modelAvailability ==
                    SourceSeparationCacheModelAvailability.InstalledExact)
                repeat(REJECTED_RETRY_POSITION_UPDATE_COUNT) { index ->
                    coordinator.updatePosition(
                        positionMs = (index + 6L) * 1_000L,
                        durationMs = source.duration,
                        isPlaying = true,
                        sourceSeparationBlend = TEST_BLEND,
                    )
                    sampleMissingBoundaryProcesses()
                }
                SystemClock.sleep(REJECTED_RETRY_POSITION_SETTLE_MS)
                sampleMissingBoundaryProcesses()
                check(remoteProcessPidsDuringMissingBoundary.isEmpty()) {
                    "Restoring the model automatically started inference."
                }
                check(!coordinator.isWorkerActive())
                check(store.readRunJournal(cacheKey) == journalAfterDeath)

                coordinator.clearStatusIfNotRunning()
                check(coordinator.workerStateFlow.value == SourceSeparationUiState.Idle)
                val root = store.root().directory
                val resumeFaultToken = "remote-model-restore-${request.runId}"
                    .take(MAX_FAULT_TOKEN_LENGTH)
                SourceSeparationCacheFaultInjection.arm(
                    root,
                    SourceSeparationCacheFaultControl(
                        token = resumeFaultToken,
                        stage = SourceSeparationCacheFaultStage.NativeInvocation,
                        action = SourceSeparationCacheFaultAction.Barrier,
                        timeoutMs = REMOTE_DEATH_BARRIER_TIMEOUT_MS,
                    ),
                )
                coordinator.updateSong(
                    song = source,
                    positionMs = 0L,
                    durationMs = source.duration,
                    isPlaying = false,
                    sourceSeparationBlend = TEST_BLEND,
                )
                check(coordinator.startCurrentSong())
                val resumeFaultHit = waitForCacheFaultHit(root, resumeFaultToken)
                val resumedJournal = waitForJournal(SETUP_TIMEOUT_MS) {
                    store.readRunJournal(cacheKey)?.takeIf { journal ->
                        journal.lifecycle == SourceSeparationCacheRunJournalLifecycle.Running &&
                            journal.request.runId != oldExecutionRunId &&
                            journal.request.processGeneration != oldProcessGeneration &&
                            journal.request.ownerPid != oldRemotePid
                    }
                }
                val resumedRemotePid = requireNotNull(resumedJournal.request.ownerPid)
                check(resumeFaultHit.pid == resumedRemotePid)
                check(resumedJournal.request.identity == runtimeSong.identity)
                check(resumedJournal.request.tryGpu == originalTryGpu)
                check(resumedJournal.request.gpuRuntimeIdentity ==
                    journalAfterDeath.request.gpuRuntimeIdentity)
                check(resumedJournal.request.gpuFallbackLatch ==
                    journalAfterDeath.request.gpuFallbackLatch)
                validateCommittedSegmentEvidence(committedBeforeDeath, resumedJournal)
                val previousOwnerDeath = resumedJournal.transitions.single { transition ->
                    transition.type == SourceSeparationCacheRunTransitionType.PreviousOwnerDied
                }
                check(previousOwnerDeath.runId == oldExecutionRunId)
                check(previousOwnerDeath.processGeneration == oldProcessGeneration)
                check(previousOwnerDeath.ownerPid == oldRemotePid)
                val resumedRemoteProcessStartTicks = SourceSeparationProcParser
                    .parseProcessStartTicks(File("/proc/$resumedRemotePid/stat").readText())
                    ?: error("Could not read the restored-model process start ticks.")
                check(resumedRemoteProcessStartTicks != oldRemoteProcessStartTicks)

                coordinator.pauseCurrentSong(source)
                SourceSeparationCacheFaultInjection.release(root, resumeFaultToken)
                val pausedJournal = waitForJournal(COMPLETION_TIMEOUT_MS) {
                    store.readRunJournal(cacheKey)?.takeIf { journal ->
                        journal.request.runId == resumedJournal.request.runId &&
                            journal.lifecycle == SourceSeparationCacheRunJournalLifecycle.Paused
                    }
                }
                check(pausedJournal.committedSegments.size <=
                    committedBeforeDeath.length() + 1)
                check(waitUntil(REATTACH_TIMEOUT_MS) {
                    coordinator.runningCacheKey() == null &&
                        coordinator.protectedCacheKeys().isEmpty() &&
                        handoff.stateFlow.value.activeOwner == null &&
                        notificationManager.activeNotifications.none { notification ->
                            notification.id == SourceSeparationMediaProcessingForegroundController
                                .NOTIFICATION_ID
                        }
                }) { "The restored-model retry did not release after Pause." }
                check(preferences.edit()
                    .putBoolean(SOURCE_SEPARATION_TRY_GPU, originalTryGpu)
                    .commit()
                ) { "Could not restore the GPU preference after model-loss validation." }
                originalTryGpuForRestore = null

                writeJson(
                    outputFile,
                    JSONObject()
                        .put("schemaVersion", REMOTE_DEATH_MODEL_LOSS_REPORT_SCHEMA_VERSION)
                        .put("status", "passed")
                        .put("stage", STAGE_REMOTE_DEATH)
                        .put("recoveryAction", request.remoteDeathRecoveryAction.argumentValue)
                        .put("runId", request.runId)
                        .put("cacheKey", cacheKey)
                        .put("mainPid", Process.myPid())
                        .put("oldRemotePid", oldRemotePid)
                        .put("resumedRemotePid", resumedRemotePid)
                        .put("oldRemoteProcessStartTicks", oldRemoteProcessStartTicks)
                        .put("resumedRemoteProcessStartTicks",
                            resumedRemoteProcessStartTicks)
                        .put("oldProcessGeneration", oldProcessGeneration)
                        .put("resumedProcessGeneration",
                            resumedJournal.request.processGeneration)
                        .put("oldExecutionRunId", oldExecutionRunId)
                        .put("resumedExecutionRunId", resumedJournal.request.runId)
                        .put("automaticRetryBudget", 0)
                        .put("automaticRemoteRelaunchCount",
                            evidence.unexpectedRemoteRelaunchCount)
                        .put("unexpectedRemotePresenceSampleCount",
                            evidence.unexpectedRemotePresenceSampleCount)
                        .put("silentObservationMs", evidence.silentObservationMs)
                        .put("journalSequenceBeforeKill",
                            evidence.journalSequenceBeforeKill)
                        .put("journalSequenceAfterSilence",
                            evidence.journalSequenceAfterSilence)
                        .put("committedSegmentsPreserved",
                            committedBeforeDeath.length())
                        .put("missingResolutionReason", missingResolution.reason.name)
                        .put("missingCacheState", missingEntry.state.name)
                        .put("missingCacheModelAvailability",
                            missingEntry.modelAvailability.name)
                        .put("typedFailureMessage", missingMessage)
                        .put("missingRetryJournalUnchanged", true)
                        .put("remoteProcessPidsDuringMissingBoundary",
                            JSONArray(remoteProcessPidsDuringMissingBoundary.sorted()))
                        .put("positionUpdatesWhileMissing",
                            REJECTED_RETRY_POSITION_UPDATE_COUNT)
                        .put("positionUpdatesAfterRestore",
                            REJECTED_RETRY_POSITION_UPDATE_COUNT)
                        .put("modelArtifactRestored", primaryModelRestored)
                        .put("restoredCacheModelAvailability",
                            restoredEntry.modelAvailability.name)
                        .put("restoreTriggeredAutomaticResume", false)
                        .put("secondExplicitStartRequired", true)
                        .put("persistedTryGpuBeforeRetry", persistedTryGpu)
                        .put("admittedTryGpu", originalTryGpu)
                        .put("resumedTryGpu", resumedJournal.request.tryGpu)
                        .put("resumedGpuRuntime", gpuRuntimeJson(resumedJournal))
                        .put("backendPolicyPreserved", true)
                        .put("resumedPreviousOwnerDeathCount",
                            resumedJournal.transitions.count { transition ->
                                transition.type ==
                                    SourceSeparationCacheRunTransitionType.PreviousOwnerDied
                            })
                        .put("resumedCommittedSegmentsAtBarrier",
                            resumedJournal.committedSegments.size)
                        .put("resumeNativeInvocationBarrierReached", true)
                        .put("resumedLifecycleAfterPause", pausedJournal.lifecycle.name)
                        .put("resumedCommittedSegmentsAfterPause",
                            pausedJournal.committedSegments.size)
                        .put("cacheWriteLockReleased", cacheLockReleased)
                        .put("cacheLockReleaseMs", cacheLockReleaseMs)
                        .put("terminalNotification", false),
                )
                Log.i(TAG, "Remote-death model-loss validation passed for ${request.runId}.")
                return
            }
            if (request.remoteDeathRecoveryAction ==
                RemoteDeathRecoveryAction.ResumeLatchedCpuFallback
            ) {
                check(originalTryGpu) {
                    "Latched fallback recovery requires an admitted GPU run."
                }
                val admittedRuntime = requireNotNull(
                    journalAfterDeath.request.gpuRuntimeIdentity,
                )
                check(journalAfterDeath.request.gpuFallbackLatch == null)
                val initialRuntime = scenario.getJSONObject("faultHitRuntime")
                check(initialRuntime.getString("runtimeName") == "LiteRT 2.1.5 Auto")
                check(initialRuntime.getString("backend") == "LiteRtGpu")
                check(initialRuntime.isNull("fallbackStage"))
                check(initialRuntime.isNull("fallbackReason"))
                val latch = SourceSeparationGpuFallbackLatch(
                    stage = "GpuInvocation",
                    reason = "Phase 7 persisted fallback fixture.",
                )
                val latchedJournal = journalAfterDeath.latchGpuFallback(
                    latch = latch,
                    nowEpochMs = System.currentTimeMillis(),
                )
                check(latchedJournal.latestSequence == journalAfterDeath.latestSequence + 1L)
                check(latchedJournal.transitions.count { transition ->
                    transition.type ==
                        SourceSeparationCacheRunTransitionType.GpuFallbackLatched
                } == 1)
                store.writeRunJournal(latchedJournal)
                check(store.readRunJournal(cacheKey) == latchedJournal)
                validateCommittedSegmentEvidence(committedBeforeDeath, latchedJournal)

                coordinator.clearStatusIfNotRunning()
                check(coordinator.workerStateFlow.value == SourceSeparationUiState.Idle)
                val root = store.root().directory
                val resumeFaultToken = "remote-latched-cpu-${request.runId}"
                    .take(MAX_FAULT_TOKEN_LENGTH)
                SourceSeparationCacheFaultInjection.arm(
                    root,
                    SourceSeparationCacheFaultControl(
                        token = resumeFaultToken,
                        stage = SourceSeparationCacheFaultStage.NativeInvocation,
                        action = SourceSeparationCacheFaultAction.Barrier,
                        timeoutMs = REMOTE_DEATH_BARRIER_TIMEOUT_MS,
                    ),
                )
                coordinator.updateSong(
                    song = source,
                    positionMs = 0L,
                    durationMs = source.duration,
                    isPlaying = false,
                    sourceSeparationBlend = TEST_BLEND,
                )
                check(coordinator.startCurrentSong())
                val resumeFaultHit = waitForCacheFaultHit(root, resumeFaultToken)
                val resumedJournal = waitForJournal(SETUP_TIMEOUT_MS) {
                    store.readRunJournal(cacheKey)?.takeIf { journal ->
                        journal.lifecycle == SourceSeparationCacheRunJournalLifecycle.Running &&
                            journal.request.runId != oldExecutionRunId &&
                            journal.request.processGeneration != oldProcessGeneration &&
                            journal.request.ownerPid != oldRemotePid
                    }
                }
                val resumedRemotePid = requireNotNull(resumedJournal.request.ownerPid)
                check(resumeFaultHit.pid == resumedRemotePid)
                check(resumedJournal.request.identity == runtimeSong.identity)
                check(resumedJournal.request.tryGpu)
                check(resumedJournal.request.gpuRuntimeIdentity == admittedRuntime)
                check(resumedJournal.request.gpuFallbackLatch == latch)
                check(resumedJournal.transitions.count { transition ->
                    transition.type ==
                        SourceSeparationCacheRunTransitionType.GpuFallbackLatched
                } == 1)
                val resumedRuntime = requireNotNull(resumeFaultHit.runtime) {
                    "The latched CPU fault hit did not capture runtime diagnostics."
                }
                check(resumedRuntime.runtimeName == "LiteRT 2.1.5")
                check(resumedRuntime.backend == "LiteRtCpu")
                check(resumedRuntime.fallbackStage == null)
                check(resumedRuntime.fallbackReason == null)
                val previousOwnerDeath = resumedJournal.transitions.single { transition ->
                    transition.type == SourceSeparationCacheRunTransitionType.PreviousOwnerDied
                }
                check(previousOwnerDeath.runId == oldExecutionRunId)
                check(previousOwnerDeath.processGeneration == oldProcessGeneration)
                check(previousOwnerDeath.ownerPid == oldRemotePid)
                validateCommittedSegmentEvidence(committedBeforeDeath, resumedJournal)
                val resumedRemoteProcessStartTicks = SourceSeparationProcParser
                    .parseProcessStartTicks(File("/proc/$resumedRemotePid/stat").readText())
                    ?: error("Could not read the latched-CPU process start ticks.")
                check(resumedRemoteProcessStartTicks != oldRemoteProcessStartTicks)

                coordinator.pauseCurrentSong(source)
                SourceSeparationCacheFaultInjection.release(root, resumeFaultToken)
                val pausedJournal = waitForJournal(COMPLETION_TIMEOUT_MS) {
                    store.readRunJournal(cacheKey)?.takeIf { journal ->
                        journal.request.runId == resumedJournal.request.runId &&
                            journal.lifecycle == SourceSeparationCacheRunJournalLifecycle.Paused
                    }
                }
                check(pausedJournal.request.tryGpu)
                check(pausedJournal.request.gpuRuntimeIdentity == admittedRuntime)
                check(pausedJournal.request.gpuFallbackLatch == latch)
                check(pausedJournal.transitions.count { transition ->
                    transition.type ==
                        SourceSeparationCacheRunTransitionType.GpuFallbackLatched
                } == 1)
                check(pausedJournal.committedSegments.size <=
                    committedBeforeDeath.length() + 1)
                check(waitUntil(REATTACH_TIMEOUT_MS) {
                    coordinator.runningCacheKey() == null &&
                        coordinator.protectedCacheKeys().isEmpty() &&
                        handoff.stateFlow.value.activeOwner == null &&
                        notificationManager.activeNotifications.none { notification ->
                            notification.id == SourceSeparationMediaProcessingForegroundController
                                .NOTIFICATION_ID
                        }
                }) { "The latched CPU retry did not release after Pause." }
                check(preferences.edit()
                    .putBoolean(SOURCE_SEPARATION_TRY_GPU, originalTryGpu)
                    .commit()
                ) { "Could not restore the GPU preference after latch validation." }
                originalTryGpuForRestore = null

                writeJson(
                    outputFile,
                    JSONObject()
                        .put("schemaVersion",
                            REMOTE_DEATH_LATCHED_FALLBACK_REPORT_SCHEMA_VERSION)
                        .put("status", "passed")
                        .put("stage", STAGE_REMOTE_DEATH)
                        .put("recoveryAction", request.remoteDeathRecoveryAction.argumentValue)
                        .put("runId", request.runId)
                        .put("cacheKey", cacheKey)
                        .put("mainPid", Process.myPid())
                        .put("oldRemotePid", oldRemotePid)
                        .put("resumedRemotePid", resumedRemotePid)
                        .put("oldRemoteProcessStartTicks", oldRemoteProcessStartTicks)
                        .put("resumedRemoteProcessStartTicks",
                            resumedRemoteProcessStartTicks)
                        .put("oldProcessGeneration", oldProcessGeneration)
                        .put("resumedProcessGeneration",
                            resumedJournal.request.processGeneration)
                        .put("oldExecutionRunId", oldExecutionRunId)
                        .put("resumedExecutionRunId", resumedJournal.request.runId)
                        .put("automaticRetryBudget", 0)
                        .put("automaticRemoteRelaunchCount",
                            evidence.unexpectedRemoteRelaunchCount)
                        .put("unexpectedRemotePresenceSampleCount",
                            evidence.unexpectedRemotePresenceSampleCount)
                        .put("silentObservationMs", evidence.silentObservationMs)
                        .put("journalSequenceBeforeKill",
                            evidence.journalSequenceBeforeKill)
                        .put("journalSequenceAfterSilence",
                            evidence.journalSequenceAfterSilence)
                        .put("latchedJournalSequence", latchedJournal.latestSequence)
                        .put("committedSegmentsPreserved",
                            committedBeforeDeath.length())
                        .put("runtimeBeforeDeath", initialRuntime)
                        .put("fallbackLatchStage", latch.stage)
                        .put("fallbackLatchReason", latch.reason)
                        .put("fallbackTransitionCount",
                            pausedJournal.transitions.count { transition ->
                                transition.type ==
                                    SourceSeparationCacheRunTransitionType.GpuFallbackLatched
                            })
                        .put("persistedTryGpuBeforeRetry", persistedTryGpu)
                        .put("admittedTryGpu", originalTryGpu)
                        .put("resumedTryGpu", resumedJournal.request.tryGpu)
                        .put("admittedGpuRuntime", gpuRuntimeJson(journalAfterDeath))
                        .put("resumedGpuRuntime", gpuRuntimeJson(resumedJournal))
                        .put("gpuRuntimeIdentityPreserved", true)
                        .put("resumedPreviousOwnerDeathCount",
                            resumedJournal.transitions.count { transition ->
                                transition.type ==
                                    SourceSeparationCacheRunTransitionType.PreviousOwnerDied
                            })
                        .put("resumeMode", "LatchedCpuFallback")
                        .put("resumedRuntime", JSONObject()
                            .put("runtimeName", resumedRuntime.runtimeName)
                            .put("backend", resumedRuntime.backend)
                            .put("fallbackStage",
                                resumedRuntime.fallbackStage ?: JSONObject.NULL)
                            .put("fallbackReason",
                                resumedRuntime.fallbackReason ?: JSONObject.NULL))
                        .put("directCpuResume", true)
                        .put("newGpuFallbackAttempt", false)
                        .put("resumeNativeInvocationBarrierReached", true)
                        .put("resumedLifecycleAfterPause", pausedJournal.lifecycle.name)
                        .put("resumedCommittedSegmentsAfterPause",
                            pausedJournal.committedSegments.size)
                        .put("cacheWriteLockReleased", cacheLockReleased)
                        .put("cacheLockReleaseMs", cacheLockReleaseMs)
                        .put("terminalNotification", false),
                )
                Log.i(TAG, "Remote-death latched CPU validation passed for ${request.runId}.")
                return
            }
            if (request.remoteDeathRecoveryAction ==
                RemoteDeathRecoveryAction.SwitchModelThenStart
            ) {
                val secondaryModelId = requireNotNull(request.secondaryModelId)
                val secondaryArtifactSha256 = requireNotNull(
                    request.secondaryArtifactSha256,
                )
                val repository = get<SourceSeparationPresetRepository>(
                    SourceSeparationPresetRepository::class.java,
                ).also { presetRepository = it }
                val primaryInstalled = repository.requireInstalledPreset(
                    request.artifactSha256,
                )
                val secondaryInstalled = repository.requireInstalledPreset(
                    secondaryArtifactSha256,
                )
                check(secondaryInstalled.modelId == secondaryModelId)
                val backupDirectory = File(
                    File(context.filesDir, MODEL_BACKUP_DIRECTORY),
                    request.runId,
                ).apply {
                    check(isDirectory || mkdirs()) {
                        "Could not create the primary model backup directory."
                    }
                }
                val backup = File(backupDirectory, "${request.artifactSha256}.tflite")
                primaryModelBackup = backup
                primaryInstalled.file.copyTo(backup, overwrite = true)
                check(backup.length() == primaryInstalled.byteSize)
                check(backup.sha256().equals(request.artifactSha256, ignoreCase = true))

                val activeDeleteBlocked = try {
                    repository.delete(request.artifactSha256)
                    false
                } catch (_: SourceSeparationPresetDeletionException) {
                    true
                }
                check(activeDeleteBlocked) { "The active primary model was deletable." }
                check(repository.installedModel(request.artifactSha256) != null)

                val selectedSecondary = repository.activate(
                    sha256 = secondaryArtifactSha256,
                    platform = AndroidMdxRuntimePlatformProvider.current(),
                    scope = SourceSeparationPresetSelectionScope.InternalValidation,
                    experimentalConfirmed = true,
                )
                check(selectedSecondary.modelId == secondaryModelId)
                check(selectedSecondary.artifactSha256.equals(
                    secondaryArtifactSha256,
                    ignoreCase = true,
                ))
                check(repository.delete(request.artifactSha256))
                check(repository.installedModel(request.artifactSha256) == null)

                val oldEntryAfterDeletion = runtime.entries().single { entry ->
                    entry.cacheKey == cacheKey
                }
                check(oldEntryAfterDeletion.modelId == request.modelId)
                check(oldEntryAfterDeletion.artifactSha256.equals(
                    request.artifactSha256,
                    ignoreCase = true,
                ))
                check(oldEntryAfterDeletion.state ==
                    SourceSeparationModelAwareCacheEntryState.Stale)
                check(oldEntryAfterDeletion.modelAvailability ==
                    SourceSeparationCacheModelAvailability.ModelNotInstalled)
                check(oldEntryAfterDeletion.readySegments == committedBeforeDeath.length())
                val oldJournalAfterDeletion = requireNotNull(store.readRunJournal(cacheKey))
                check(oldJournalAfterDeletion == journalAfterDeath)
                check(oldJournalAfterDeletion.lifecycle ==
                    SourceSeparationCacheRunJournalLifecycle.Running)
                validateCommittedSegmentEvidence(committedBeforeDeath, oldJournalAfterDeletion)
                val oldJournalFile = File(
                    store.entryDirectory(cacheKey),
                    SourceSeparationCacheStore.RUN_JOURNAL_FILE_NAME,
                )
                check(oldJournalFile.sha256() == evidence.journalSha256AfterSilence)

                val secondaryRuntimeSong = (runtime.resolve(source) as?
                    SourceSeparationRuntimeSongResolution.Ready)?.song
                    ?: error("The secondary model could not resolve the same source.")
                check(secondaryRuntimeSong.modelId == secondaryModelId)
                check(secondaryRuntimeSong.artifactSha256.equals(
                    secondaryArtifactSha256,
                    ignoreCase = true,
                ))
                check(secondaryRuntimeSong.cacheKey != cacheKey)
                check(store.readManifest(secondaryRuntimeSong.cacheKey) == null)
                check(store.readRunJournal(secondaryRuntimeSong.cacheKey) == null)
                check(runtime.cacheStatus(secondaryRuntimeSong) ==
                    SourceSeparationModelAwareCacheStatus.Missing)

                val platform = AndroidMdxRuntimePlatformProvider.current()
                val secondaryProfile = secondaryRuntimeSong.model.executionProfile
                val cpuCompatibility = MdxLiteRtCompatibilityResolver.resolve(
                    profile = secondaryProfile,
                    backend = MdxInferenceBackend.LiteRtCpu,
                    platform = platform,
                    policy = MdxCompatibilityPolicy.KnownGoodOnly,
                )
                val boundedGpuProfile = MdxLiteRtGpuRuntimeProfile.BoundedOpenClFp32V1
                val gpuCompatibility = MdxLiteRtCompatibilityResolver.resolve(
                    profile = secondaryProfile,
                    backend = MdxInferenceBackend.LiteRtGpu,
                    platform = platform,
                    policy = MdxCompatibilityPolicy.AllowUntestedInternal,
                    profileId = boundedGpuProfile.qualificationProfileId,
                    precision = MdxRuntimePrecision.Fp32,
                )
                check(cpuCompatibility.isAllowed)
                check(!gpuCompatibility.isAllowed) {
                    "The secondary model unexpectedly became GPU eligible."
                }

                coordinator.clearStatusIfNotRunning()
                check(coordinator.workerStateFlow.value == SourceSeparationUiState.Idle)
                val root = store.root().directory
                val freshFaultToken = "remote-switch-${request.runId}"
                    .take(MAX_FAULT_TOKEN_LENGTH)
                SourceSeparationCacheFaultInjection.arm(
                    root,
                    SourceSeparationCacheFaultControl(
                        token = freshFaultToken,
                        stage = SourceSeparationCacheFaultStage.NativeInvocation,
                        action = SourceSeparationCacheFaultAction.Barrier,
                        timeoutMs = REMOTE_DEATH_BARRIER_TIMEOUT_MS,
                    ),
                )
                coordinator.updateSong(
                    song = source,
                    positionMs = 0L,
                    durationMs = source.duration,
                    isPlaying = false,
                    sourceSeparationBlend = TEST_BLEND,
                )
                check(coordinator.startCurrentSong())
                val freshFaultHit = waitForCacheFaultHit(root, freshFaultToken)
                val freshJournal = waitForJournal(SETUP_TIMEOUT_MS) {
                    store.readRunJournal(secondaryRuntimeSong.cacheKey)?.takeIf { journal ->
                        journal.lifecycle == SourceSeparationCacheRunJournalLifecycle.Running &&
                            journal.request.runId != oldExecutionRunId &&
                            journal.request.processGeneration != oldProcessGeneration &&
                            journal.request.ownerPid != oldRemotePid
                    }
                }
                val freshRemotePid = requireNotNull(freshJournal.request.ownerPid)
                check(freshFaultHit.pid == freshRemotePid)
                check(freshJournal.request.identity == secondaryRuntimeSong.identity)
                check(freshJournal.request.runClass ==
                    SourceSeparationExecutionRunClass.ManualFullSong)
                check(freshJournal.request.backgroundPolicy ==
                    SourceSeparationExecutionRunClass.ManualFullSong.backgroundPolicy)
                check(freshJournal.request.tryGpu == persistedTryGpu)
                check(freshJournal.request.gpuFallbackLatch == null)
                if (persistedTryGpu) {
                    val admittedGpu = requireNotNull(freshJournal.request.gpuRuntimeIdentity)
                    check(admittedGpu.profileId == boundedGpuProfile.profileId)
                    check(admittedGpu.kernelBatchSize == 1)
                    check(admittedGpu.commandQueueWindowSize == 1)
                } else {
                    check(freshJournal.request.gpuRuntimeIdentity == null)
                }
                check(freshJournal.committedSegments.isEmpty())
                check(freshJournal.transitions.first().let { transition ->
                    transition.sequence == 1L &&
                        transition.type == SourceSeparationCacheRunTransitionType.Admitted &&
                        transition.runId == freshJournal.request.runId
                })
                check(freshJournal.transitions.none { transition ->
                    transition.type == SourceSeparationCacheRunTransitionType.PreviousOwnerDied
                })
                val secondaryManifestAtBarrier = requireNotNull(
                    store.readManifest(secondaryRuntimeSong.cacheKey),
                )
                check(secondaryManifestAtBarrier.identity == secondaryRuntimeSong.identity)
                check(secondaryManifestAtBarrier.runtimeRecords.isEmpty())
                check(store.readRunJournal(cacheKey) == oldJournalAfterDeletion)
                check(oldJournalFile.sha256() == evidence.journalSha256AfterSilence)

                val freshRemoteProcessStartTicks = SourceSeparationProcParser
                    .parseProcessStartTicks(File("/proc/$freshRemotePid/stat").readText())
                    ?: error("Could not read the switched remote process start ticks.")
                check(freshRemoteProcessStartTicks != oldRemoteProcessStartTicks)

                coordinator.pauseCurrentSong(source)
                SourceSeparationCacheFaultInjection.release(root, freshFaultToken)
                val pausedFreshJournal = waitForJournal(COMPLETION_TIMEOUT_MS) {
                    store.readRunJournal(secondaryRuntimeSong.cacheKey)?.takeIf { journal ->
                        journal.request.runId == freshJournal.request.runId &&
                            journal.lifecycle == SourceSeparationCacheRunJournalLifecycle.Paused
                    }
                }
                check(pausedFreshJournal.committedSegments.size <= 1) {
                    "The switched-model run advanced beyond the released native invocation."
                }
                check(waitUntil(REATTACH_TIMEOUT_MS) {
                    coordinator.runningCacheKey() == null &&
                        coordinator.protectedCacheKeys().isEmpty() &&
                        handoff.stateFlow.value.activeOwner == null &&
                        notificationManager.activeNotifications.none { notification ->
                            notification.id == SourceSeparationMediaProcessingForegroundController
                                .NOTIFICATION_ID
                        }
                }) { "The switched-model run did not release after Pause." }

                val oldEntryBeforeRestore = runtime.entries().single { entry ->
                    entry.cacheKey == cacheKey
                }
                val newEntryBeforeRestore = runtime.entries().single { entry ->
                    entry.cacheKey == secondaryRuntimeSong.cacheKey
                }
                check(oldEntryBeforeRestore.state ==
                    SourceSeparationModelAwareCacheEntryState.Stale)
                check(oldEntryBeforeRestore.modelAvailability ==
                    SourceSeparationCacheModelAvailability.ModelNotInstalled)
                check(newEntryBeforeRestore.state ==
                    SourceSeparationModelAwareCacheEntryState.Partial)
                check(newEntryBeforeRestore.modelAvailability ==
                    SourceSeparationCacheModelAvailability.InstalledExact)
                check(store.readRunJournal(cacheKey) == oldJournalAfterDeletion)
                validateCommittedSegmentEvidence(
                    committedBeforeDeath,
                    requireNotNull(store.readRunJournal(cacheKey)),
                )

                restorePrimaryModel(repository, request, backup)
                primaryModelRestored = true
                check((repository.activeModel() as? SourceSeparationActivePresetState.Reference)
                    ?.reference
                    ?.artifactSha256
                    ?.equals(request.artifactSha256, ignoreCase = true) == true)
                val oldEntryAfterRestore = runtime.entries().single { entry ->
                    entry.cacheKey == cacheKey
                }
                check(oldEntryAfterRestore.modelAvailability ==
                    SourceSeparationCacheModelAvailability.InstalledExact)

                writeJson(
                    outputFile,
                    JSONObject()
                        .put("schemaVersion", REMOTE_DEATH_MODEL_SWITCH_REPORT_SCHEMA_VERSION)
                        .put("status", "passed")
                        .put("stage", STAGE_REMOTE_DEATH)
                        .put("recoveryAction", request.remoteDeathRecoveryAction.argumentValue)
                        .put("runId", request.runId)
                        .put("mainPid", Process.myPid())
                        .put("oldCacheKey", cacheKey)
                        .put("newCacheKey", secondaryRuntimeSong.cacheKey)
                        .put("oldModelId", request.modelId)
                        .put("oldArtifactSha256", request.artifactSha256)
                        .put("newModelId", secondaryModelId)
                        .put("newArtifactSha256", secondaryArtifactSha256)
                        .put("activeModelDeletionBlocked", activeDeleteBlocked)
                        .put("oldModelDeletedAfterSwitch", true)
                        .put("oldCacheStateAfterDeletion", oldEntryBeforeRestore.state.name)
                        .put("oldCacheModelAvailabilityAfterDeletion",
                            oldEntryBeforeRestore.modelAvailability.name)
                        .put("newCacheStateAtPause", newEntryBeforeRestore.state.name)
                        .put("newCacheModelAvailabilityAtPause",
                            newEntryBeforeRestore.modelAvailability.name)
                        .put("oldExecutionRunId", oldExecutionRunId)
                        .put("newExecutionRunId", freshJournal.request.runId)
                        .put("oldProcessGeneration", oldProcessGeneration)
                        .put("newProcessGeneration", freshJournal.request.processGeneration)
                        .put("oldRemotePid", oldRemotePid)
                        .put("newRemotePid", freshRemotePid)
                        .put("oldRemoteProcessStartTicks", oldRemoteProcessStartTicks)
                        .put("newRemoteProcessStartTicks", freshRemoteProcessStartTicks)
                        .put("automaticRetryBudget", 0)
                        .put("automaticRemoteRelaunchCount",
                            evidence.unexpectedRemoteRelaunchCount)
                        .put("unexpectedRemotePresenceSampleCount",
                            evidence.unexpectedRemotePresenceSampleCount)
                        .put("silentObservationMs", evidence.silentObservationMs)
                        .put("oldCommittedSegmentsPreserved", committedBeforeDeath.length())
                        .put("newCommittedSegmentsAtBarrier",
                            freshJournal.committedSegments.size)
                        .put("newAdmissionSequence",
                            freshJournal.transitions.first().sequence)
                        .put("newPreviousOwnerDeathCount", freshJournal.transitions.count {
                            transition -> transition.type ==
                                SourceSeparationCacheRunTransitionType.PreviousOwnerDied
                        })
                        .put("newLifecycleAfterPause", pausedFreshJournal.lifecycle.name)
                        .put("newCommittedSegmentsAfterPause",
                            pausedFreshJournal.committedSegments.size)
                        .put("originalTryGpu", originalTryGpu)
                        .put("persistedTryGpuBeforeNewStart", persistedTryGpu)
                        .put("newTryGpu", freshJournal.request.tryGpu)
                        .put("newAdmittedGpuRuntime", gpuRuntimeJson(freshJournal))
                        .put("newCpuCompatibilityOutcome", cpuCompatibility.outcome.name)
                        .put("newGpuCompatibilityOutcome", gpuCompatibility.outcome.name)
                        .put("newGpuCompatibilityReason", gpuCompatibility.reason)
                        .put("newRuntimeRecordsAtBarrier",
                            secondaryManifestAtBarrier.runtimeRecords.size)
                        .put("nativeInvocationBarrierReached", true)
                        .put("oldJournalUnchangedThroughNewAdmission", true)
                        .put("cacheWriteLockReleased", cacheLockReleased)
                        .put("cacheLockReleaseMs", cacheLockReleaseMs)
                        .put("primaryModelRestored", primaryModelRestored)
                        .put("oldCacheModelAvailabilityAfterRestore",
                            oldEntryAfterRestore.modelAvailability.name)
                        .put("terminalNotification", false),
                )
                Log.i(TAG, "Remote-death model-switch validation passed for ${request.runId}.")
                return
            }
            if (request.remoteDeathRecoveryAction ==
                RemoteDeathRecoveryAction.ClearCacheThenStart
            ) {
                check(runtime.delete(cacheKey) == SourceSeparationCacheMutationResult.Completed) {
                    "The abandoned cache could not be cleared after owner death."
                }
                check(!store.entryDirectory(cacheKey).exists())
                check(store.readRunJournal(cacheKey) == null)
                check(runtime.cacheStatus(runtimeSong) ==
                    SourceSeparationModelAwareCacheStatus.Missing)
                coordinator.clearStatusIfNotRunning()
                check(coordinator.workerStateFlow.value == SourceSeparationUiState.Idle)

                val root = store.root().directory
                val freshFaultToken = "remote-clear-${request.runId}"
                    .take(MAX_FAULT_TOKEN_LENGTH)
                SourceSeparationCacheFaultInjection.arm(
                    root,
                    SourceSeparationCacheFaultControl(
                        token = freshFaultToken,
                        stage = SourceSeparationCacheFaultStage.NativeInvocation,
                        action = SourceSeparationCacheFaultAction.Barrier,
                        timeoutMs = REMOTE_DEATH_BARRIER_TIMEOUT_MS,
                    ),
                )
                coordinator.updateSong(
                    song = source,
                    positionMs = 0L,
                    durationMs = source.duration,
                    isPlaying = false,
                    sourceSeparationBlend = TEST_BLEND,
                )
                check(coordinator.startCurrentSong())
                val freshFaultHit = waitForCacheFaultHit(root, freshFaultToken)
                val freshJournal = waitForJournal(SETUP_TIMEOUT_MS) {
                    store.readRunJournal(cacheKey)?.takeIf { journal ->
                        journal.lifecycle == SourceSeparationCacheRunJournalLifecycle.Running &&
                            journal.request.runId != oldExecutionRunId &&
                            journal.request.processGeneration != oldProcessGeneration &&
                            journal.request.ownerPid != oldRemotePid
                    }
                }
                val freshRemotePid = requireNotNull(freshJournal.request.ownerPid)
                check(freshFaultHit.pid == freshRemotePid)
                check(freshJournal.committedSegments.isEmpty())
                check(freshJournal.transitions.first().let { transition ->
                    transition.sequence == 1L &&
                        transition.type == SourceSeparationCacheRunTransitionType.Admitted &&
                        transition.runId == freshJournal.request.runId
                })
                check(freshJournal.transitions.none { transition ->
                    transition.type == SourceSeparationCacheRunTransitionType.PreviousOwnerDied
                })
                check(freshJournal.request.tryGpu == persistedTryGpu)
                check(freshJournal.request.gpuFallbackLatch == null)
                if (persistedTryGpu) {
                    val freshGpuRuntime = requireNotNull(
                        freshJournal.request.gpuRuntimeIdentity
                    )
                    check(freshGpuRuntime.profileId ==
                        MdxLiteRtGpuRuntimeProfile.BoundedOpenClFp32V1.profileId)
                    check(freshGpuRuntime.kernelBatchSize == 1)
                    check(freshGpuRuntime.commandQueueWindowSize == 1)
                } else {
                    check(freshJournal.request.gpuRuntimeIdentity == null)
                }
                val freshRemoteProcessStartTicks = SourceSeparationProcParser
                    .parseProcessStartTicks(File("/proc/$freshRemotePid/stat").readText())
                    ?: error("Could not read the fresh remote process start ticks.")
                check(freshRemoteProcessStartTicks != oldRemoteProcessStartTicks)

                coordinator.pauseCurrentSong(source)
                SourceSeparationCacheFaultInjection.release(root, freshFaultToken)
                val pausedFreshJournal = waitForJournal(COMPLETION_TIMEOUT_MS) {
                    store.readRunJournal(cacheKey)?.takeIf { journal ->
                        journal.request.runId == freshJournal.request.runId &&
                            journal.lifecycle == SourceSeparationCacheRunJournalLifecycle.Paused
                    }
                }
                check(waitUntil(REATTACH_TIMEOUT_MS) {
                    coordinator.runningCacheKey() == null &&
                        coordinator.protectedCacheKeys().isEmpty() &&
                        handoff.stateFlow.value.activeOwner == null &&
                        notificationManager.activeNotifications.none { notification ->
                            notification.id == SourceSeparationMediaProcessingForegroundController
                                .NOTIFICATION_ID
                        }
                }) { "The fresh post-clear admission did not release after Pause." }

                writeJson(
                    outputFile,
                    JSONObject()
                        .put("schemaVersion", REMOTE_DEATH_CACHE_CLEAR_REPORT_SCHEMA_VERSION)
                        .put("status", "passed")
                        .put("stage", STAGE_REMOTE_DEATH)
                        .put("recoveryAction", request.remoteDeathRecoveryAction.argumentValue)
                        .put("runId", request.runId)
                        .put("cacheKey", cacheKey)
                        .put("mainPid", Process.myPid())
                        .put("oldRemotePid", oldRemotePid)
                        .put("freshRemotePid", freshRemotePid)
                        .put("oldRemoteProcessStartTicks", oldRemoteProcessStartTicks)
                        .put("freshRemoteProcessStartTicks", freshRemoteProcessStartTicks)
                        .put("oldProcessGeneration", oldProcessGeneration)
                        .put("freshProcessGeneration", freshJournal.request.processGeneration)
                        .put("oldExecutionRunId", oldExecutionRunId)
                        .put("freshExecutionRunId", freshJournal.request.runId)
                        .put("automaticRetryBudget", 0)
                        .put("automaticRemoteRelaunchCount",
                            evidence.unexpectedRemoteRelaunchCount)
                        .put("unexpectedRemotePresenceSampleCount",
                            evidence.unexpectedRemotePresenceSampleCount)
                        .put("deathRequester", evidence.deathRequester)
                        .put("silentObservationMs", evidence.silentObservationMs)
                        .put("failureMessage", failureMessage)
                        .put("journalSequenceBeforeKill", evidence.journalSequenceBeforeKill)
                        .put("journalSequenceAfterDeath", evidence.journalSequenceAfterDeath)
                        .put("journalSequenceAfterSilence",
                            evidence.journalSequenceAfterSilence)
                        .put("journalSha256BeforeKill", evidence.journalSha256BeforeKill)
                        .put("journalSha256AfterDeath", evidence.journalSha256AfterDeath)
                        .put("journalSha256AfterSilence", evidence.journalSha256AfterSilence)
                        .put("entrySha256BeforeKill", evidence.entrySha256BeforeKill)
                        .put("entrySha256AfterDeath", evidence.entrySha256AfterDeath)
                        .put("entrySha256AfterSilence", evidence.entrySha256AfterSilence)
                        .put("committedSegmentsBeforeDeath", committedBeforeDeath.length())
                        .put("oldCacheAndJournalCleared", true)
                        .put("freshAdmissionSequence", freshJournal.transitions.first().sequence)
                        .put("freshCommittedSegmentsAtBarrier",
                            freshJournal.committedSegments.size)
                        .put("freshPreviousOwnerDeathCount", freshJournal.transitions.count {
                            transition ->
                            transition.type ==
                                SourceSeparationCacheRunTransitionType.PreviousOwnerDied
                        })
                        .put("freshLifecycleAfterPause", pausedFreshJournal.lifecycle.name)
                        .put("freshCommittedSegmentsAfterPause",
                            pausedFreshJournal.committedSegments.size)
                        .put("originalTryGpu", originalTryGpu)
                        .put("persistedTryGpuBeforeFreshStart", persistedTryGpu)
                        .put("freshTryGpu", freshJournal.request.tryGpu)
                        .put("freshGpuRuntime", gpuRuntimeJson(freshJournal))
                        .put("oldBackendPolicyDiscarded", true)
                        .put("cacheWriteLockReleased", cacheLockReleased)
                        .put("cacheLockReleaseMs", cacheLockReleaseMs)
                        .put("processingServiceAfterSilence",
                            evidence.processingServiceAfterSilence)
                        .put("notificationAfterSilence", evidence.notificationAfterSilence)
                        .put("wakeLockAfterSilence", evidence.wakeLockAfterSilence)
                        .put("terminalNotification", false),
                )
                Log.i(TAG, "Remote-death cache-clear validation passed for ${request.runId}.")
                return
            }
            coordinator.updateSong(
                song = source,
                positionMs = 0L,
                durationMs = source.duration,
                isPlaying = false,
                sourceSeparationBlend = TEST_BLEND,
            )
            check(coordinator.startCurrentSong())

            val resumedJournal = waitForJournal(SETUP_TIMEOUT_MS) {
                store.readRunJournal(cacheKey)?.takeIf { journal ->
                    journal.request.runId != oldExecutionRunId &&
                        journal.request.processGeneration != oldProcessGeneration &&
                        journal.request.ownerPid != oldRemotePid &&
                        journal.transitions.any { transition ->
                            transition.type ==
                                SourceSeparationCacheRunTransitionType.PreviousOwnerDied
                        }
                }
            }
            val resumedRemotePid = requireNotNull(resumedJournal.request.ownerPid)
            check(resumedRemotePid != Process.myPid())
            check(File("/proc/$resumedRemotePid").isDirectory)
            val resumedRemoteProcessStartTicks = SourceSeparationProcParser
                .parseProcessStartTicks(File("/proc/$resumedRemotePid/stat").readText())
                ?: error("Could not read the resumed remote process start ticks.")
            check(resumedRemoteProcessStartTicks != oldRemoteProcessStartTicks)
            check(resumedJournal.request.tryGpu == originalTryGpu)
            check(resumedJournal.request.gpuFallbackLatch == journalAfterDeath.request.gpuFallbackLatch)
            if (originalTryGpu) {
                val gpuRuntime = requireNotNull(resumedJournal.request.gpuRuntimeIdentity)
                check(gpuRuntime.profileId ==
                    MdxLiteRtGpuRuntimeProfile.BoundedOpenClFp32V1.profileId)
                check(gpuRuntime.kernelBatchSize == 1)
                check(gpuRuntime.commandQueueWindowSize == 1)
            } else {
                check(resumedJournal.request.gpuRuntimeIdentity == null)
            }
            validateCommittedSegmentEvidence(committedBeforeDeath, resumedJournal)
            val previousOwnerDeath = resumedJournal.transitions.single { transition ->
                transition.type == SourceSeparationCacheRunTransitionType.PreviousOwnerDied
            }
            check(previousOwnerDeath.sequence == evidence.journalSequenceBeforeKill + 1L)
            check(previousOwnerDeath.runId == oldExecutionRunId)
            check(previousOwnerDeath.processGeneration == oldProcessGeneration)
            check(previousOwnerDeath.ownerPid == oldRemotePid)
            val resumedAdmission = resumedJournal.transitions.single { transition ->
                transition.type == SourceSeparationCacheRunTransitionType.Admitted &&
                    transition.runId == resumedJournal.request.runId
            }
            val abandonedObserver = resumedJournal.transitions.single { transition ->
                transition.type == SourceSeparationCacheRunTransitionType.ObserverDisconnected &&
                    transition.observerReason == "owner-process-died"
            }
            check(abandonedObserver.sequence == previousOwnerDeath.sequence + 1L)
            check(abandonedObserver.runId == oldExecutionRunId)
            check(abandonedObserver.processGeneration == oldProcessGeneration)
            check(abandonedObserver.ownerPid == oldRemotePid)
            check(abandonedObserver.observerId == oldObserverId)
            check(abandonedObserver.observerProcessName == oldObserverProcessName)
            check(resumedAdmission.sequence == abandonedObserver.sequence + 1L)
            check(resumedAdmission.runId == resumedJournal.request.runId)
            check(resumedAdmission.processGeneration ==
                resumedJournal.request.processGeneration)
            check(resumedAdmission.ownerPid == resumedRemotePid)

            val finalJournal = waitForJournal(COMPLETION_TIMEOUT_MS) {
                store.readRunJournal(cacheKey)?.takeIf { journal ->
                    journal.request.runId == resumedJournal.request.runId &&
                        journal.lifecycle == SourceSeparationCacheRunJournalLifecycle.Completed
                }
            }
            check(finalJournal.committedSegments.size == plannedSegments)
            check(finalJournal.committedSegments.map { it.segmentIndex }.distinct() ==
                (0 until EXPECTED_FULL_SONG_SEGMENTS).toList())
            check(finalJournal.transitions.count { transition ->
                transition.type == SourceSeparationCacheRunTransitionType.PreviousOwnerDied
            } == 1)
            validateCommittedSegmentEvidence(committedBeforeDeath, finalJournal)
            val terminalOwnershipReleased = waitUntil(REATTACH_TIMEOUT_MS) {
                coordinator.runningCacheKey() == null &&
                    coordinator.protectedCacheKeys().isEmpty() &&
                    handoff.stateFlow.value.activeOwner == null
            }
            check(terminalOwnershipReleased) {
                "The explicitly resumed run did not release terminal ownership: " +
                    "coordinator=${coordinator.debugStatus()} " +
                    "runningCache=${coordinator.runningCacheKey()} " +
                    "protectedCaches=${coordinator.protectedCacheKeys()} " +
                    "ownership=${handoff.stateFlow.value}"
            }
            val schedulerResidentAfterCompletion = coordinator.isWorkerActive()
            val coordinatorStatusAfterCompletion = coordinator.debugStatus()

            var completed: SourceSeparationModelAwareCacheStatus.Completed? = null
            check(waitUntil(REATTACH_TIMEOUT_MS) {
                (runtime.cacheStatus(runtimeSong) as?
                    SourceSeparationModelAwareCacheStatus.Completed)?.let { status ->
                    completed = status
                    true
                } == true
            }) { "The explicitly resumed cache did not become readable." }
            val completedCache = requireNotNull(completed)
            check(store.validateCompletedEntry(completedCache.manifest) ==
                SourceSeparationCacheValidationResult.Valid)
            var openedPlayback: SourceSeparationModelAwareCachePlayback? = null
            check(waitUntil(REATTACH_TIMEOUT_MS) {
                runtime.openCompletedCache(cacheKey)?.let { playback ->
                    openedPlayback = playback
                    true
                } == true
            }) { "The explicitly resumed cache remained busy or unavailable." }
            requireNotNull(openedPlayback).use { playback ->
                check(playback.vocalsFile.isFile)
                check(playback.instrumentalFile.isFile)
            }
            val runtimeRecords = completedCache.manifest.runtimeRecords
            check(runtimeRecords.isNotEmpty())
            if (originalTryGpu) {
                check(runtimeRecords.any { record -> record.backend == "LiteRtGpu" })
                check(runtimeRecords.all { record ->
                    record.fallbackStage == null && record.fallbackReason == null
                })
            } else {
                check(runtimeRecords.all { record -> record.backend == "LiteRtCpu" })
            }
            check(waitUntil(REATTACH_TIMEOUT_MS) {
                notificationManager.activeNotifications.none { notification ->
                    notification.id == SourceSeparationMediaProcessingForegroundController
                        .NOTIFICATION_ID
                }
            }) { "The processing notification survived explicit-resume completion." }

            writeJson(
                outputFile,
                JSONObject()
                    .put("schemaVersion", REMOTE_DEATH_REPORT_SCHEMA_VERSION)
                    .put("status", "passed")
                    .put("stage", STAGE_REMOTE_DEATH)
                    .put("recoveryAction", request.remoteDeathRecoveryAction.argumentValue)
                    .put("runId", request.runId)
                    .put("cacheKey", cacheKey)
                    .put("mainPid", Process.myPid())
                    .put("oldRemotePid", oldRemotePid)
                    .put("resumedRemotePid", resumedRemotePid)
                    .put("oldRemoteProcessStartTicks", oldRemoteProcessStartTicks)
                    .put("resumedRemoteProcessStartTicks", resumedRemoteProcessStartTicks)
                    .put("oldProcessGeneration", oldProcessGeneration)
                    .put("resumedProcessGeneration",
                        resumedJournal.request.processGeneration)
                    .put("oldExecutionRunId", oldExecutionRunId)
                    .put("resumedExecutionRunId", resumedJournal.request.runId)
                    .put("automaticRetryBudget", 0)
                    .put("automaticRemoteRelaunchCount",
                        evidence.unexpectedRemoteRelaunchCount)
                    .put("unexpectedRemotePresenceSampleCount",
                        evidence.unexpectedRemotePresenceSampleCount)
                    .put("deathRequester", evidence.deathRequester)
                    .put("killExitElapsedMs", evidence.killExitElapsedMs)
                    .put("silentObservationMs", evidence.silentObservationMs)
                    .put("silentProcessSampleCount", evidence.silentProcessSampleCount)
                    .put("mainProcessSampleCount", evidence.mainProcessSampleCount)
                    .put("remoteProcessSampleCount", evidence.remoteProcessSampleCount)
                    .put("mainDisappearanceCount", evidence.mainDisappearanceCount)
                    .put("failureMessage", failureMessage)
                    .put("journalLifecycleBeforeResume", journalAfterDeath.lifecycle.name)
                    .put("journalSequenceBeforeKill", evidence.journalSequenceBeforeKill)
                    .put("journalSequenceAfterDeath", evidence.journalSequenceAfterDeath)
                    .put("journalSequenceAfterSilence",
                        evidence.journalSequenceAfterSilence)
                    .put("journalSha256BeforeKill", evidence.journalSha256BeforeKill)
                    .put("journalSha256AfterDeath", evidence.journalSha256AfterDeath)
                    .put("journalSha256AfterSilence", evidence.journalSha256AfterSilence)
                    .put("entrySha256BeforeKill", evidence.entrySha256BeforeKill)
                    .put("entrySha256AfterDeath", evidence.entrySha256AfterDeath)
                    .put("entrySha256AfterSilence", evidence.entrySha256AfterSilence)
                    .put("committedSegmentsBeforeDeath", committedBeforeDeath.length())
                    .put("committedSegmentsPreserved", true)
                    .put("finalCommittedSegments", finalJournal.committedSegments.size)
                    .put("plannedSegments", plannedSegments)
                    .put("schedulerResidentAfterCompletion",
                        schedulerResidentAfterCompletion)
                    .put("coordinatorStatusAfterCompletion",
                        coordinatorStatusAfterCompletion)
                    .put("previousOwnerDied", true)
                    .put("abandonedObserverId", oldObserverId)
                    .put("abandonedObserverProcessName", oldObserverProcessName)
                    .put("abandonedObserverReason", abandonedObserver.observerReason)
                    .put("originalTryGpu", originalTryGpu)
                    .put("persistedTryGpuBeforeResume", persistedTryGpu)
                    .put("resumedTryGpu", resumedJournal.request.tryGpu)
                    .put("admittedGpuRuntime", gpuRuntimeJson(resumedJournal))
                    .put("backendPolicyPreserved", true)
                    .put("processingServiceBeforeKill",
                        evidence.processingServiceBeforeKill)
                    .put("notificationBeforeKill", evidence.notificationBeforeKill)
                    .put("wakeLockBeforeKill", evidence.wakeLockBeforeKill)
                    .put("processingServiceAfterSilence",
                        evidence.processingServiceAfterSilence)
                    .put("notificationAfterSilence", evidence.notificationAfterSilence)
                    .put("wakeLockAfterSilence", evidence.wakeLockAfterSilence)
                    .put("packageStoppedBeforeKill", evidence.packageStoppedBeforeKill)
                    .put("packageStoppedAfterDeath", evidence.packageStoppedAfterDeath)
                    .put("packageStoppedAfterSilence", evidence.packageStoppedAfterSilence)
                    .put("cacheWriteLockReleased", cacheLockReleased)
                    .put("cacheLockReleaseMs", cacheLockReleaseMs)
                    .put("terminalNotification", false)
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
            Log.i(TAG, "Remote-death validation passed for ${request.runId}.")
        } catch (error: Throwable) {
            writeFailure(outputFile, request, STAGE_REMOTE_DEATH, "validation", error)
            Log.e(TAG, "Remote-death validation failed for ${request.runId}.", error)
        } finally {
            runCatching {
                SourceSeparationCacheFaultInjection.clear(
                    get<SourceSeparationCacheStore>(SourceSeparationCacheStore::class.java)
                        .root().directory,
                )
            }
            worker?.cancel()
            if (!primaryModelRestored && presetRepository != null) {
                runCatching {
                    if (request.remoteDeathRecoveryAction ==
                        RemoteDeathRecoveryAction.RejectModelLoss
                    ) {
                        restoreMovedPrimaryModel(
                            repository = requireNotNull(presetRepository),
                            request = request,
                            backup = primaryModelBackup,
                            destination = primaryModelDestination,
                        )
                    } else {
                        restorePrimaryModel(
                            repository = requireNotNull(presetRepository),
                            request = request,
                            backup = primaryModelBackup,
                        )
                    }
                    primaryModelRestored = true
                }.onFailure { error ->
                    Log.e(TAG, "Could not restore the primary model after validation.", error)
                    val failure = runCatching {
                        JSONObject(outputFile.readText(Charsets.UTF_8))
                    }.getOrElse { JSONObject() }
                    failure
                        .put("schemaVersion", if (request.remoteDeathRecoveryAction ==
                            RemoteDeathRecoveryAction.RejectModelLoss
                        ) {
                            REMOTE_DEATH_MODEL_LOSS_REPORT_SCHEMA_VERSION
                        } else {
                            REMOTE_DEATH_MODEL_SWITCH_REPORT_SCHEMA_VERSION
                        })
                        .put("status", "failed")
                        .put("stage", STAGE_REMOTE_DEATH)
                        .put("phase", "primary-model-restoration")
                        .put("runId", request.runId)
                        .put("restorationErrorType", error::class.java.name)
                        .put("restorationError", error.message ?: JSONObject.NULL)
                    writeJson(outputFile, failure)
                }
            }
            if (primaryModelRestored) {
                primaryModelBackup?.delete()
                primaryModelBackup?.parentFile?.delete()
            }
            if (request.remoteDeathRecoveryAction ==
                    RemoteDeathRecoveryAction.SwitchModelThenStart ||
                request.remoteDeathRecoveryAction ==
                    RemoteDeathRecoveryAction.RejectArtifactMismatch ||
                request.remoteDeathRecoveryAction ==
                    RemoteDeathRecoveryAction.RejectModelLoss ||
                request.remoteDeathRecoveryAction ==
                    RemoteDeathRecoveryAction.ResumeLatchedCpuFallback
            ) {
                originalTryGpuForRestore?.let { originalTryGpu ->
                    runCatching {
                        check(get<SharedPreferences>(SharedPreferences::class.java)
                            .edit()
                            .putBoolean(SOURCE_SEPARATION_TRY_GPU, originalTryGpu)
                            .commit()
                        ) { "Could not commit the restored GPU preference." }
                    }.onFailure { error ->
                        Log.e(TAG, "Could not restore the GPU preference after validation.", error)
                    }
                }
            }
            mediaUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
        }
    }

    private fun validate(context: Context, request: Request) {
        val scenarioFile = scenarioFile(context, request.runId)
        val outputFile = reportFile(context, request.runId)
        var mediaUri: Uri? = null
        var armedFaultRoot: File? = null
        var armedFaultToken: String? = null
        try {
            val scenario = JSONObject(scenarioFile.readText(Charsets.UTF_8))
            check(scenario.getInt("schemaVersion") == SCENARIO_SCHEMA_VERSION)
            check(scenario.getString("runId") == request.runId)
            check(scenario.getString("backendMode") == request.backendMode)
            check(scenario.getString("killBoundary") ==
                request.mainDeathBoundary.argumentValue)
            val cacheKey = scenario.getString("cacheKey")
            val oldMainPid = scenario.getInt("mainPid")
            val remotePid = scenario.getInt("remotePid")
            val processGeneration = scenario.getLong("remoteProcessGeneration")
            val executionRunId = scenario.getString("executionRunId")
            mediaUri = Uri.parse(scenario.getString("sourceMediaUri"))
            val terminalCommitBoundary = request.mainDeathBoundary ==
                MainDeathBoundary.TerminalCommit
            armedFaultToken = scenario.takeUnless { it.isNull("faultToken") }
                ?.getString("faultToken")
            armedFaultRoot = armedFaultToken?.let {
                File(scenario.getString("cacheRootPath"))
            }
            check((armedFaultToken != null) ==
                (request.mainDeathBoundary.faultStage != null)) {
                "The main-death fault barrier does not match the requested boundary."
            }
            check(oldMainPid != Process.myPid())
            check(!File("/proc/$oldMainPid").exists())
            check(File("/proc/$remotePid").isDirectory) {
                "The authoritative inference process did not survive main-process death."
            }
            if (terminalCommitBoundary) {
                SourceSeparationCacheFaultInjection.release(
                    requireNotNull(armedFaultRoot),
                    requireNotNull(armedFaultToken),
                )
            }

            val store = get<SourceSeparationCacheStore>(SourceSeparationCacheStore::class.java)
            check(store.root().directory.canonicalFile ==
                File(scenario.getString("cacheRootPath")).canonicalFile)
            val expectedSegmentCount = scenario.getInt("plannedSegments")
            val journalSequenceBeforeHarnessValidation = if (terminalCommitBoundary) {
                check(scenario.getInt("committedSegments") == expectedSegmentCount)
                check(File(scenario.getString("journalPath")).isFile)
                scenario.getLong("journalSequence")
            } else {
                val reattached = waitForJournal(REATTACH_TIMEOUT_MS) {
                    store.readRunJournal(cacheKey)?.takeIf { journal ->
                        journal.lifecycle == SourceSeparationCacheRunJournalLifecycle.Running &&
                            journal.transitions
                            .filter { transition ->
                                transition.type ==
                                    SourceSeparationCacheRunTransitionType.ObserverConnected ||
                                    transition.type ==
                                        SourceSeparationCacheRunTransitionType.ObserverDisconnected
                            }
                            .let { observers ->
                                observers.size >= 3 &&
                                    observers.last().type ==
                                        SourceSeparationCacheRunTransitionType.ObserverConnected
                            }
                    }
                }
                check(reattached.request.ownerPid == remotePid)
                check(reattached.request.processGeneration == processGeneration)
                check(reattached.request.runId == executionRunId)
                reattached.latestSequence
            }

            val source = resolveMediaStoreSong(context, mediaUri, request.sourcePath)
            val runtime = get<SourceSeparationRuntimeFacade>(
                SourceSeparationRuntimeFacade::class.java,
            )
            val runtimeSong = (runtime.resolve(source) as?
                SourceSeparationRuntimeSongResolution.Ready)?.song
                ?: error("The restarted main process could not resolve the source.")
            check(runtimeSong.cacheKey == cacheKey)
            val worker = get<SourceSeparationForegroundWorkerCoordinator>(
                SourceSeparationForegroundWorkerCoordinator::class.java,
            )
            worker.updateSong(
                song = source,
                positionMs = 0L,
                durationMs = source.duration,
                isPlaying = false,
                sourceSeparationBlend = TEST_BLEND,
            )

            if (terminalCommitBoundary) {
                check(worker.runningCacheKey() == null)
                check(worker.protectedCacheKeys().isEmpty())
                check(worker.pendingSongId() == null)
            } else {
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
            }

            val processingNotificationAfterReattachment = if (terminalCommitBoundary) {
                val notificationManager = context.getSystemService(NotificationManager::class.java)
                check(notificationManager.activeNotifications.none { notification ->
                    notification.id ==
                        SourceSeparationMediaProcessingForegroundController.NOTIFICATION_ID
                }) { "The processing notification was recreated after terminal publication." }
                null
            } else {
                processingNotificationSnapshot(context).also { snapshot ->
                    validateProcessingNotification(
                        context = context,
                        snapshot = snapshot,
                        displayName = source.fileName,
                    )
                }
            }
            val processingNotificationBeforeDeath =
                scenario.getJSONObject("processingNotificationBeforeMainDeath")
            if (processingNotificationAfterReattachment != null) {
                check(
                    processingNotificationBeforeDeath.getString("title") ==
                        processingNotificationAfterReattachment.getString("title") &&
                        processingNotificationBeforeDeath.getString("text") ==
                        processingNotificationAfterReattachment.getString("text") &&
                        processingNotificationBeforeDeath.getJSONArray("actions").toString() ==
                        processingNotificationAfterReattachment.getJSONArray("actions").toString()
                ) { "The processing notification changed across main-process recreation." }
            }

            val cacheManagementState = SourceSeparationModelAwareCacheManagementUiState(
                items = runtime.entries(),
            )
            val activeCacheItem = if (terminalCommitBoundary) {
                cacheManagementState.completedItems.singleOrNull { entry ->
                    entry.cacheKey == cacheKey
                }
            } else {
                cacheManagementState.incompleteItems.singleOrNull { entry ->
                    entry.cacheKey == cacheKey
                }
            } ?: error("Cache management did not expose the reattached active entry.")
            check(activeCacheItem.state == if (terminalCommitBoundary) {
                SourceSeparationModelAwareCacheEntryState.Completed
            } else {
                SourceSeparationModelAwareCacheEntryState.Partial
            })
            check(activeCacheItem.modelAvailability ==
                SourceSeparationCacheModelAvailability.InstalledExact)
            if (terminalCommitBoundary) {
                check(activeCacheItem.readySegments == expectedSegmentCount)
            } else {
                check((activeCacheItem.readySegments ?: 0) >= 1)
            }
            check(activeCacheItem.totalSegments == expectedSegmentCount)
            check(activeCacheItem.modelId == request.modelId)

            var activePlaybackManifestUpdatedAtEpochMs = 0L
            check(waitUntil(SETUP_TIMEOUT_MS) {
                when (val status = runtime.playableStatus(
                    song = runtimeSong,
                    playbackPositionMs = 0L,
                    readyWindowCount = 1,
                )) {
                    is SourceSeparationModelAwarePlayableStatus.Ready -> {
                        status.playback.use { playback ->
                            check(playback.vocalsFile.isFile)
                            check(playback.instrumentalFile.isFile)
                            activePlaybackManifestUpdatedAtEpochMs =
                                playback.manifest.updatedAtEpochMs
                        }
                        true
                    }
                    SourceSeparationModelAwarePlayableStatus.Processing -> false
                    SourceSeparationModelAwarePlayableStatus.Unavailable ->
                        error("The reattached cache became unavailable for playback.")
                }
            }) { "The reattached cache did not expose playable audio." }

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

            var completed: SourceSeparationModelAwareCacheStatus.Completed? = null
            check(waitUntil(REATTACH_TIMEOUT_MS) {
                (runtime.cacheStatus(runtimeSong) as?
                    SourceSeparationModelAwareCacheStatus.Completed)?.let { status ->
                    completed = status
                    true
                } == true
            }) { "The reattached run did not expose a completed cache." }
            var openedPlayback: SourceSeparationModelAwareCachePlayback? = null
            check(waitUntil(REATTACH_TIMEOUT_MS) {
                runtime.openCompletedCache(cacheKey)?.let { playback ->
                    openedPlayback = playback
                    true
                } == true
            }) { "The completed cache remained busy or unavailable." }
            requireNotNull(openedPlayback).use { playback ->
                check(playback.vocalsFile.isFile)
                check(playback.instrumentalFile.isFile)
            }
            val observerTransitions = finalJournal.transitions.filter { transition ->
                transition.type == SourceSeparationCacheRunTransitionType.ObserverConnected ||
                    transition.type == SourceSeparationCacheRunTransitionType.ObserverDisconnected
            }
            if (terminalCommitBoundary) {
                check(observerTransitions.isNotEmpty())
            } else {
                check(observerTransitions.size >= 3)
                check(observerTransitions.last().type ==
                    SourceSeparationCacheRunTransitionType.ObserverConnected)
            }
            check(finalJournal.transitions.all { transition ->
                transition.runId == executionRunId &&
                    transition.processGeneration == processGeneration
            })
            val committedBeforeDeath = scenario.getJSONArray("committedSegmentEvidence")
            check(committedBeforeDeath.length() == scenario.getInt("committedSegments"))
            validateCommittedSegmentEvidence(committedBeforeDeath, finalJournal)

            val runtimeRecords = requireNotNull(completed).manifest.runtimeRecords
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
                    .put("killRequester", scenario.getString("killRequester"))
                    .put(
                        "faultStage",
                        request.mainDeathBoundary.faultStage?.name ?: JSONObject.NULL,
                    )
                    .put("barrierReleasedBeforeProductProjection", terminalCommitBoundary)
                    .put("journalSequenceBeforeDeath", scenario.getLong("journalSequence"))
                    .put(
                        "journalSequenceBeforeHarnessValidation",
                        journalSequenceBeforeHarnessValidation,
                    )
                    .put("finalJournalSequence", finalJournal.latestSequence)
                    .put("committedSegmentsBeforeDeath", scenario.getInt("committedSegments"))
                    .put(
                        "committedSegmentIndicesBeforeDeath",
                        JSONArray((0 until committedBeforeDeath.length()).map { index ->
                            committedBeforeDeath.getJSONObject(index).getInt("segmentIndex")
                        }),
                    )
                    .put("committedSegmentsPreserved", true)
                    .put("finalCommittedSegments", finalJournal.committedSegments.size)
                    .put(
                        "processingNotificationBeforeMainDeath",
                        processingNotificationBeforeDeath,
                    )
                    .put(
                        "processingNotificationAfterReattachment",
                        processingNotificationAfterReattachment ?: JSONObject.NULL,
                    )
                    .put(
                        "playbackReadinessAfterReattachment",
                        JSONObject()
                            .put("status", "Ready")
                            .put("playbackPositionMs", 0)
                            .put("readyWindowCount", 1)
                            .put(
                                "manifestUpdatedAtEpochMs",
                                activePlaybackManifestUpdatedAtEpochMs,
                            ),
                    )
                    .put(
                        "cacheManagementAfterReattachment",
                        JSONObject()
                            .put("cacheKey", activeCacheItem.cacheKey)
                            .put("state", activeCacheItem.state.name)
                            .put("modelId", activeCacheItem.modelId)
                            .put("modelAvailability", activeCacheItem.modelAvailability.name)
                            .put("readySegments", activeCacheItem.readySegments)
                            .put("totalSegments", activeCacheItem.totalSegments)
                            .put(
                                "incompleteSectionCount",
                                cacheManagementState.incompleteItems.size,
                            )
                            .put(
                                "completedSectionCount",
                                cacheManagementState.completedItems.size,
                            ),
                    )
                    .put("observerTransitionCount", observerTransitions.size)
                    .put(
                        "productObserverConnectedBeforeHarness",
                        observerTransitions.any { transition ->
                            transition.sequence > scenario.getLong("journalSequence") &&
                                transition.type ==
                                    SourceSeparationCacheRunTransitionType.ObserverConnected
                        },
                    )
                    .put(
                        "terminalWorkerState",
                        worker.workerStateFlow.value::class.java.simpleName,
                    )
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
            writeFailure(outputFile, request, STAGE_MAIN_DEATH, "validation", error)
            Log.e(TAG, "Main-death validation failed for ${request.runId}.", error)
        } finally {
            armedFaultRoot?.let(SourceSeparationCacheFaultInjection::clear)
            mediaUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
        }
    }

    private fun validateForceStop(
        context: Context,
        request: Request,
        intent: Intent,
    ) {
        val scenarioFile = scenarioFile(context, request.runId)
        val outputFile = reportFile(context, request.runId)
        var mediaUri: Uri? = null
        try {
            val evidence = forceStopEvidence(intent)
            val scenario = JSONObject(scenarioFile.readText(Charsets.UTF_8))
            check(scenario.getInt("schemaVersion") == SCENARIO_SCHEMA_VERSION)
            check(scenario.getString("runId") == request.runId)
            check(scenario.getString("backendMode") == request.backendMode)
            check(scenario.getString("killRequester") == BeginMode.ForceStop.killRequester)
            val cacheKey = scenario.getString("cacheKey")
            mediaUri = Uri.parse(scenario.getString("sourceMediaUri"))

            check(evidence.allProcessesExitedAfterStop)
            check(evidence.allProcessesExitedAfterSilence)
            check(evidence.packageStoppedAfterStop)
            check(evidence.packageStoppedAfterSilence)
            check(evidence.silentProcessSampleCount > 0L)
            check(evidence.unexpectedRelaunchCount == 0L)
            check(!evidence.notificationAfterStop && !evidence.notificationAfterSilence)
            check(!evidence.processingServiceAfterStop &&
                !evidence.processingServiceAfterSilence)
            check(!evidence.wakeLockAfterStop && !evidence.wakeLockAfterSilence)
            check(!evidence.notificationAfterRestart)
            check(!evidence.wakeLockAfterRestart)
            check(evidence.journalSha256AfterStop == evidence.journalSha256AfterSilence)
            check(evidence.journalSha256AfterStop == evidence.journalSha256AfterRestart)
            check(evidence.journalSequenceAfterStop == evidence.journalSequenceAfterSilence)
            check(evidence.journalSequenceAfterStop == evidence.journalSequenceAfterRestart)
            check(evidence.entrySha256AfterStop == evidence.entrySha256AfterSilence)
            check(evidence.entrySha256AfterStop == evidence.entrySha256AfterRestart)
            check(evidence.entryFileCountAfterStop == evidence.entryFileCountAfterSilence)
            check(evidence.entryFileCountAfterStop == evidence.entryFileCountAfterRestart)
            check(evidence.entryBytesAfterStop == evidence.entryBytesAfterSilence)
            check(evidence.entryBytesAfterStop == evidence.entryBytesAfterRestart)

            val store = get<SourceSeparationCacheStore>(SourceSeparationCacheStore::class.java)
            val journal = requireNotNull(store.readRunJournal(cacheKey))
            check(journal.lifecycle == SourceSeparationCacheRunJournalLifecycle.Running) {
                "Force-stop must not depend on onDestroy changing the journal."
            }
            check(journal.latestSequence == evidence.journalSequenceAfterRestart)
            check(journal.request.runId == scenario.getString("executionRunId"))
            check(journal.request.processGeneration ==
                scenario.getLong("remoteProcessGeneration"))

            val worker = get<SourceSeparationForegroundWorkerCoordinator>(
                SourceSeparationForegroundWorkerCoordinator::class.java,
            )
            check(waitUntil(REATTACH_TIMEOUT_MS) {
                !worker.isWorkerActive() &&
                    worker.runningCacheKey() == null &&
                    worker.protectedCacheKeys().isEmpty()
            }) { "The explicit app restart attempted to resume force-stopped work." }
            val ownership = get<SourceSeparationProcessingOwnershipHandoff>(
                SourceSeparationProcessingOwnershipHandoff::class.java,
            ).stateFlow.value
            check(ownership.activeOwner == null)
            val idleHost = BoundRemoteSourceSeparationExecutionHost(context.applicationContext)
            val idleDiagnostics = try {
                check(idleHost.reconnectableRun() == null)
                idleHost.processDiagnostics()
            } finally {
                idleHost.close()
            }
            check(idleDiagnostics.activeRunId == null)
            check(idleDiagnostics.session.state == SourceSeparationProcessSessionState.Empty)
            check(idleDiagnostics.session.sessionId == null)
            check(idleDiagnostics.session.nativeSessionCreationCount == 0)
            check(idleDiagnostics.session.activeLeaseCount == 0)
            check(idleDiagnostics.foregroundService.activeLease == null)
            check(idleDiagnostics.processingWakeLock.activeLease == null)
            check(!idleDiagnostics.processingWakeLock.platformHeld)
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
            check(notificationManager.activeNotifications.none { notification ->
                notification.id == SourceSeparationMediaProcessingForegroundController
                    .NOTIFICATION_ID
            })

            val runtime = get<SourceSeparationRuntimeFacade>(
                SourceSeparationRuntimeFacade::class.java,
            )
            check(runtime.delete(cacheKey) == SourceSeparationCacheMutationResult.Completed) {
                "The stale force-stop cache could not be removed after validation."
            }
            writeJson(
                outputFile,
                JSONObject()
                    .put("schemaVersion", FORCE_STOP_REPORT_SCHEMA_VERSION)
                    .put("status", "passed")
                    .put("stage", STAGE_FORCE_STOP)
                    .put("runId", request.runId)
                    .put("cacheKey", cacheKey)
                    .put("oldMainPid", scenario.getInt("mainPid"))
                    .put("oldRemotePid", scenario.getInt("remotePid"))
                    .put("newMainPid", Process.myPid())
                    .put("executionRunId", journal.request.runId)
                    .put("processGeneration", journal.request.processGeneration)
                    .put("journalLifecycleAfterRestart", journal.lifecycle.name)
                    .put("journalSequenceAtReady", scenario.getLong("journalSequence"))
                    .put("journalSha256AtReady", scenario.getString("journalSha256"))
                    .put("journalSequenceAfterStop", evidence.journalSequenceAfterStop)
                    .put("journalSequenceAfterSilence", evidence.journalSequenceAfterSilence)
                    .put("journalSequenceAfterRestart", evidence.journalSequenceAfterRestart)
                    .put("journalSha256AfterStop", evidence.journalSha256AfterStop)
                    .put("journalSha256AfterSilence", evidence.journalSha256AfterSilence)
                    .put("journalSha256AfterRestart", evidence.journalSha256AfterRestart)
                    .put("entrySha256AfterStop", evidence.entrySha256AfterStop)
                    .put("entrySha256AfterSilence", evidence.entrySha256AfterSilence)
                    .put("entrySha256AfterRestart", evidence.entrySha256AfterRestart)
                    .put("entryFileCountAfterStop", evidence.entryFileCountAfterStop)
                    .put("entryFileCountAfterSilence", evidence.entryFileCountAfterSilence)
                    .put("entryFileCountAfterRestart", evidence.entryFileCountAfterRestart)
                    .put("entryBytesAfterStop", evidence.entryBytesAfterStop)
                    .put("entryBytesAfterSilence", evidence.entryBytesAfterSilence)
                    .put("entryBytesAfterRestart", evidence.entryBytesAfterRestart)
                    .put("forceStopExitElapsedMs", evidence.forceStopExitElapsedMs)
                    .put("silentObservationMs", evidence.silentObservationMs)
                    .put("silentProcessSampleCount", evidence.silentProcessSampleCount)
                    .put("unexpectedRelaunchCount", evidence.unexpectedRelaunchCount)
                    .put("packageStoppedAfterStop", true)
                    .put("packageStoppedAfterSilence", true)
                    .put("allProcessesExitedAfterStop", true)
                    .put("allProcessesExitedAfterSilence", true)
                    .put("notificationAfterStop", false)
                    .put("notificationAfterSilence", false)
                    .put("notificationAfterRestart", false)
                    .put("processingServiceAfterStop", false)
                    .put("processingServiceAfterSilence", false)
                    .put(
                        "processingServiceAfterRestart",
                        evidence.processingServiceAfterRestart,
                    )
                    .put("wakeLockAfterStop", false)
                    .put("wakeLockAfterSilence", false)
                    .put("wakeLockAfterRestart", false)
                    .put("automaticResumeObserved", false)
                    .put("restartDiscoveryBindingAllowed", true)
                    .put("reconnectableRunAfterRestart", JSONObject.NULL)
                    .put("idleRemoteActiveRunId", JSONObject.NULL)
                    .put("idleRemoteSessionState", idleDiagnostics.session.state.name)
                    .put("idleRemoteSessionId", JSONObject.NULL)
                    .put("idleRemotePid", idleDiagnostics.pid)
                    .put("idleRemoteProcessGeneration", idleDiagnostics.processGeneration)
                    .put(
                        "idleRemoteNativeSessionCreationCount",
                        idleDiagnostics.session.nativeSessionCreationCount,
                    )
                    .put("idleRemoteActiveLeaseCount", idleDiagnostics.session.activeLeaseCount)
                    .put("onDestroyJournalTransitionRequired", false)
                    .put("sessionOwnership", "SingleUse")
                    .put("staleRunningJournalAccepted", true)
                    .put("staleCacheCleanup", "Completed")
                    .put("tryGpu", journal.request.tryGpu)
                    .put("admittedGpuRuntime", gpuRuntimeJson(journal)),
            )
            Log.i(TAG, "Force-stop validation passed for ${request.runId}.")
        } catch (error: Throwable) {
            writeFailure(outputFile, request, STAGE_FORCE_STOP, "validation", error)
            Log.e(TAG, "Force-stop validation failed for ${request.runId}.", error)
        } finally {
            mediaUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
        }
    }

    private fun verifyActiveModel(request: Request) {
        val repository = get<SourceSeparationPresetRepository>(
            SourceSeparationPresetRepository::class.java,
        )
        var active = repository.activeModel() as? SourceSeparationActivePresetState.Reference
        val activeMatchesRequest = active?.reference?.let { reference ->
            reference.modelId == request.modelId &&
                reference.artifactSha256.equals(request.artifactSha256, true)
        } == true
        if (request.remoteDeathRecoveryAction ==
            RemoteDeathRecoveryAction.SwitchModelThenStart &&
            !activeMatchesRequest
        ) {
            repository.activate(
                sha256 = request.artifactSha256,
                platform = AndroidMdxRuntimePlatformProvider.current(),
                scope = SourceSeparationPresetSelectionScope.InternalValidation,
                experimentalConfirmed = true,
            )
            active = repository.activeModel() as? SourceSeparationActivePresetState.Reference
        }
        active = active
            ?: error("No preset model is active.")
        check(active.reference.modelId == request.modelId)
        check(active.reference.artifactSha256.equals(request.artifactSha256, true))
    }

    private fun restorePrimaryModel(
        repository: SourceSeparationPresetRepository,
        request: Request,
        backup: File?,
    ) {
        if (repository.installedModel(request.artifactSha256) == null) {
            val source = requireNotNull(backup?.takeIf(File::isFile)) {
                "The primary model backup is unavailable."
            }
            check(source.sha256().equals(request.artifactSha256, ignoreCase = true)) {
                "The primary model backup hash changed."
            }
            source.inputStream().use { input ->
                repository.installOfficial(request.modelId, input)
            }
        }
        val restored = repository.activate(
            sha256 = request.artifactSha256,
            platform = AndroidMdxRuntimePlatformProvider.current(),
            scope = SourceSeparationPresetSelectionScope.InternalValidation,
            experimentalConfirmed = true,
        )
        check(restored.modelId == request.modelId)
        check(restored.artifactSha256.equals(request.artifactSha256, ignoreCase = true))
    }

    private fun restoreMovedPrimaryModel(
        repository: SourceSeparationPresetRepository,
        request: Request,
        backup: File?,
        destination: File?,
    ) {
        val source = requireNotNull(backup?.takeIf(File::isFile)) {
            "The moved primary model backup is unavailable."
        }
        val target = requireNotNull(destination) {
            "The primary model destination is unavailable."
        }
        check(source.sha256().equals(request.artifactSha256, ignoreCase = true)) {
            "The moved primary model hash changed."
        }
        check(!target.exists()) { "The missing model destination was unexpectedly replaced." }
        if (!source.renameTo(target)) {
            source.copyTo(target, overwrite = false)
            check(source.delete()) { "Could not remove the restored model backup." }
        }
        val restored = repository.requireInstalledPreset(request.artifactSha256)
        check(restored.modelId == request.modelId)
        check(restored.file.canonicalFile == target.canonicalFile)
        check(restored.file.sha256().equals(request.artifactSha256, ignoreCase = true))
    }

    private fun inferenceProcessIds(context: Context): Set<Int> {
        val expectedName = "${context.packageName}:source_separation"
        return context.getSystemService(ActivityManager::class.java)
            .runningAppProcesses
            .orEmpty()
            .asSequence()
            .filter { process -> process.processName == expectedName }
            .map { process -> process.pid }
            .toSet()
    }

    private fun configurePreferences(request: Request) {
        val preferences = get<SharedPreferences>(SharedPreferences::class.java)
        check(preferences.edit()
            .putInt(MINIMUM_SONG_DURATION, 0)
            .putBoolean(SOURCE_SEPARATION_AUTO_CACHE_CLEANUP, false)
            .putBoolean(SOURCE_SEPARATION_AUTO_FLAC_COMPRESSION, false)
            .putBoolean(SOURCE_SEPARATION_AUTO_START, false)
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

    private fun waitForCacheFaultHit(
        root: File,
        token: String,
        expectedStage: SourceSeparationCacheFaultStage =
            SourceSeparationCacheFaultStage.NativeInvocation,
        timeoutMs: Long = REMOTE_DEATH_BARRIER_TIMEOUT_MS,
    ): SourceSeparationCacheFaultHit {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            SourceSeparationCacheFaultInjection.readHit(root)?.let { hit ->
                if (hit.token == token &&
                    hit.stage == expectedStage
                ) {
                    return hit
                }
            }
            SystemClock.sleep(POLL_MS)
        }
        error("Timed out waiting for the $expectedStage cache fault barrier.")
    }

    private fun waitUntil(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return true
            SystemClock.sleep(POLL_MS)
        }
        return predicate()
    }

    private fun processingNotificationSnapshot(context: Context): JSONObject {
        val manager = context.getSystemService(NotificationManager::class.java)
        val statusBarNotification = manager.activeNotifications.singleOrNull { notification ->
            notification.id == SourceSeparationMediaProcessingForegroundController.NOTIFICATION_ID
        } ?: error("The source-separation processing notification is not active.")
        val notification = statusBarNotification.notification
        val extras = notification.extras
        return JSONObject()
            .put("id", statusBarNotification.id)
            .put("channelId", notification.channelId)
            .put("category", notification.category ?: JSONObject.NULL)
            .put("title", extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty())
            .put("text", extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty())
            .put("ongoing", notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
            .put(
                "progressIndeterminate",
                extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false),
            )
            .put("actions", JSONArray(notification.actions.orEmpty().map { action ->
                action.title?.toString().orEmpty()
            }))
    }

    private fun validateProcessingNotification(
        context: Context,
        snapshot: JSONObject,
        displayName: String,
    ) {
        check(snapshot.getInt("id") ==
            SourceSeparationMediaProcessingForegroundController.NOTIFICATION_ID)
        check(snapshot.getString("channelId") ==
            SourceSeparationMediaProcessingForegroundController.CHANNEL_ID)
        check(snapshot.getString("category") == Notification.CATEGORY_PROGRESS)
        check(snapshot.getString("title") ==
            context.getString(R.string.source_separation_processing_windows))
        check(snapshot.getString("text") == displayName)
        check(snapshot.getBoolean("ongoing"))
        check(snapshot.getBoolean("progressIndeterminate"))
        val actions = snapshot.getJSONArray("actions")
        check((0 until actions.length()).map(actions::getString) == listOf(
            context.getString(R.string.action_pause),
            context.getString(R.string.action_cancel),
        ))
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

    private fun faultRuntimeJson(hit: SourceSeparationCacheFaultHit?): Any {
        val runtime = hit?.runtime ?: return JSONObject.NULL
        return JSONObject()
            .put("runtimeName", runtime.runtimeName)
            .put("backend", runtime.backend)
            .put("fallbackStage", runtime.fallbackStage ?: JSONObject.NULL)
            .put("fallbackReason", runtime.fallbackReason ?: JSONObject.NULL)
    }

    private fun committedSegmentEvidence(
        journal: SourceSeparationCacheRunJournal,
    ): JSONArray = JSONArray(journal.committedSegments.map { segment ->
        JSONObject()
            .put("segmentIndex", segment.segmentIndex)
            .put("vocalsPath", segment.vocalsPath)
            .put("vocalsByteSize", segment.vocalsIntegrity.byteSize)
            .put("vocalsSha256", segment.vocalsIntegrity.sha256)
            .put("instrumentalPath", segment.instrumentalPath)
            .put("instrumentalByteSize", segment.instrumentalIntegrity.byteSize)
            .put("instrumentalSha256", segment.instrumentalIntegrity.sha256)
    })

    private fun validateCommittedSegmentEvidence(
        evidence: JSONArray,
        finalJournal: SourceSeparationCacheRunJournal,
    ) {
        val finalByIndex = finalJournal.committedSegments.associateBy { it.segmentIndex }
        check(finalByIndex.size == finalJournal.committedSegments.size)
        repeat(evidence.length()) { index ->
            val expected = evidence.getJSONObject(index)
            val segment = requireNotNull(finalByIndex[expected.getInt("segmentIndex")]) {
                "A segment committed before main-process death disappeared."
            }
            check(segment.vocalsPath == expected.getString("vocalsPath"))
            check(segment.vocalsIntegrity.byteSize == expected.getLong("vocalsByteSize"))
            check(segment.vocalsIntegrity.sha256 == expected.getString("vocalsSha256"))
            check(segment.instrumentalPath == expected.getString("instrumentalPath"))
            check(segment.instrumentalIntegrity.byteSize ==
                expected.getLong("instrumentalByteSize"))
            check(segment.instrumentalIntegrity.sha256 ==
                expected.getString("instrumentalSha256"))
        }
    }

    private fun request(intent: Intent): Request {
        val runId = intent.requiredString(EXTRA_RUN_ID)
        require(SAFE_NAME.matches(runId)) { "Unsafe main-death run ID." }
        val sourcePath = intent.requiredString(EXTRA_SOURCE_PATH)
        val backendMode = intent.requiredString(EXTRA_BACKEND_MODE)
        require(backendMode == "cpu" || backendMode == "auto")
        val recoveryAction = RemoteDeathRecoveryAction.parse(
            intent.getStringExtra(EXTRA_REMOTE_DEATH_RECOVERY_ACTION),
        )
        val secondaryModelId = intent.getStringExtra(EXTRA_SECONDARY_MODEL_ID)
            ?.takeIf(String::isNotBlank)
        val secondaryArtifactSha256 =
            intent.getStringExtra(EXTRA_SECONDARY_ARTIFACT_SHA256)
                ?.takeIf(String::isNotBlank)
        require((secondaryModelId == null) == (secondaryArtifactSha256 == null)) {
            "The secondary model identity is incomplete."
        }
        if (recoveryAction == RemoteDeathRecoveryAction.SwitchModelThenStart) {
            require(secondaryModelId != null && secondaryArtifactSha256 != null) {
                "Model-switch recovery requires a secondary model identity."
            }
        }
        return Request(
            runId = runId,
            sourcePath = sourcePath,
            modelId = intent.requiredString(EXTRA_MODEL_ID),
            artifactSha256 = intent.requiredString(EXTRA_ARTIFACT_SHA256),
            backendMode = backendMode,
            mainDeathBoundary = MainDeathBoundary.parse(
                intent.getStringExtra(EXTRA_KILL_BOUNDARY),
            ),
            remoteDeathRecoveryAction = recoveryAction,
            secondaryModelId = secondaryModelId,
            secondaryArtifactSha256 = secondaryArtifactSha256,
        )
    }

    private fun forceStopEvidence(intent: Intent): ForceStopEvidence = ForceStopEvidence(
        journalSha256AfterStop = intent.requiredString(EXTRA_JOURNAL_SHA_AFTER_STOP),
        journalSha256AfterSilence = intent.requiredString(EXTRA_JOURNAL_SHA_AFTER_SILENCE),
        journalSha256AfterRestart = intent.requiredString(EXTRA_JOURNAL_SHA_AFTER_RESTART),
        entrySha256AfterStop = intent.requiredString(EXTRA_ENTRY_SHA_AFTER_STOP),
        entrySha256AfterSilence = intent.requiredString(EXTRA_ENTRY_SHA_AFTER_SILENCE),
        entrySha256AfterRestart = intent.requiredString(EXTRA_ENTRY_SHA_AFTER_RESTART),
        journalSequenceAfterStop = intent.getLongExtra(EXTRA_JOURNAL_SEQUENCE_AFTER_STOP, -1L)
            .also { require(it >= 0L) },
        journalSequenceAfterSilence =
            intent.getLongExtra(EXTRA_JOURNAL_SEQUENCE_AFTER_SILENCE, -1L)
                .also { require(it >= 0L) },
        journalSequenceAfterRestart =
            intent.getLongExtra(EXTRA_JOURNAL_SEQUENCE_AFTER_RESTART, -1L)
                .also { require(it >= 0L) },
        forceStopExitElapsedMs = intent.getLongExtra(EXTRA_FORCE_STOP_EXIT_MS, -1L)
            .also { require(it >= 0L) },
        silentObservationMs = intent.getLongExtra(EXTRA_SILENT_OBSERVATION_MS, -1L)
            .also { require(it > 0L) },
        silentProcessSampleCount = intent.getLongExtra(EXTRA_SILENT_PROCESS_SAMPLE_COUNT, -1L)
            .also { require(it > 0L) },
        unexpectedRelaunchCount = intent.getLongExtra(EXTRA_UNEXPECTED_RELAUNCH_COUNT, -1L)
            .also { require(it >= 0L) },
        entryFileCountAfterStop = intent.getLongExtra(EXTRA_ENTRY_FILE_COUNT_AFTER_STOP, -1L)
            .also { require(it > 0L) },
        entryFileCountAfterSilence =
            intent.getLongExtra(EXTRA_ENTRY_FILE_COUNT_AFTER_SILENCE, -1L)
                .also { require(it > 0L) },
        entryFileCountAfterRestart =
            intent.getLongExtra(EXTRA_ENTRY_FILE_COUNT_AFTER_RESTART, -1L)
                .also { require(it > 0L) },
        entryBytesAfterStop = intent.getLongExtra(EXTRA_ENTRY_BYTES_AFTER_STOP, -1L)
            .also { require(it > 0L) },
        entryBytesAfterSilence = intent.getLongExtra(EXTRA_ENTRY_BYTES_AFTER_SILENCE, -1L)
            .also { require(it > 0L) },
        entryBytesAfterRestart = intent.getLongExtra(EXTRA_ENTRY_BYTES_AFTER_RESTART, -1L)
            .also { require(it > 0L) },
        packageStoppedAfterStop =
            intent.getBooleanExtra(EXTRA_PACKAGE_STOPPED_AFTER_STOP, false),
        packageStoppedAfterSilence =
            intent.getBooleanExtra(EXTRA_PACKAGE_STOPPED_AFTER_SILENCE, false),
        allProcessesExitedAfterStop =
            intent.getBooleanExtra(EXTRA_ALL_PROCESSES_EXITED_AFTER_STOP, false),
        allProcessesExitedAfterSilence =
            intent.getBooleanExtra(EXTRA_ALL_PROCESSES_EXITED_AFTER_SILENCE, false),
        notificationAfterStop = intent.getBooleanExtra(EXTRA_NOTIFICATION_AFTER_STOP, true),
        notificationAfterSilence =
            intent.getBooleanExtra(EXTRA_NOTIFICATION_AFTER_SILENCE, true),
        notificationAfterRestart = intent.getBooleanExtra(EXTRA_NOTIFICATION_AFTER_RESTART, true),
        processingServiceAfterStop =
            intent.getBooleanExtra(EXTRA_PROCESSING_SERVICE_AFTER_STOP, true),
        processingServiceAfterSilence =
            intent.getBooleanExtra(EXTRA_PROCESSING_SERVICE_AFTER_SILENCE, true),
        processingServiceAfterRestart =
            intent.getBooleanExtra(EXTRA_PROCESSING_SERVICE_AFTER_RESTART, true),
        wakeLockAfterStop = intent.getBooleanExtra(EXTRA_WAKE_LOCK_AFTER_STOP, true),
        wakeLockAfterSilence = intent.getBooleanExtra(EXTRA_WAKE_LOCK_AFTER_SILENCE, true),
        wakeLockAfterRestart = intent.getBooleanExtra(EXTRA_WAKE_LOCK_AFTER_RESTART, true),
    )

    private fun remoteDeathEvidence(intent: Intent): RemoteDeathEvidence = RemoteDeathEvidence(
        journalSha256BeforeKill = intent.requiredString(EXTRA_JOURNAL_SHA_BEFORE_KILL),
        journalSha256AfterDeath = intent.requiredString(EXTRA_JOURNAL_SHA_AFTER_DEATH),
        journalSha256AfterSilence = intent.requiredString(EXTRA_JOURNAL_SHA_AFTER_SILENCE),
        entrySha256BeforeKill = intent.requiredString(EXTRA_ENTRY_SHA_BEFORE_KILL),
        entrySha256AfterDeath = intent.requiredString(EXTRA_ENTRY_SHA_AFTER_DEATH),
        entrySha256AfterSilence = intent.requiredString(EXTRA_ENTRY_SHA_AFTER_SILENCE),
        journalSequenceBeforeKill =
            intent.getLongExtra(EXTRA_JOURNAL_SEQUENCE_BEFORE_KILL, -1L)
                .also { require(it >= 0L) },
        journalSequenceAfterDeath =
            intent.getLongExtra(EXTRA_JOURNAL_SEQUENCE_AFTER_DEATH, -1L)
                .also { require(it >= 0L) },
        journalSequenceAfterSilence =
            intent.getLongExtra(EXTRA_JOURNAL_SEQUENCE_AFTER_SILENCE, -1L)
                .also { require(it >= 0L) },
        silentObservationMs = intent.getLongExtra(EXTRA_SILENT_OBSERVATION_MS, -1L)
            .also { require(it > 0L) },
        silentProcessSampleCount = intent.getLongExtra(EXTRA_SILENT_PROCESS_SAMPLE_COUNT, -1L)
            .also { require(it > 0L) },
        unexpectedRemoteRelaunchCount =
            intent.getLongExtra(EXTRA_UNEXPECTED_REMOTE_RELAUNCH_COUNT, -1L)
                .also { require(it >= 0L) },
        unexpectedRemotePresenceSampleCount = intent.getLongExtra(
            EXTRA_UNEXPECTED_REMOTE_PRESENCE_SAMPLE_COUNT,
            -1L,
        ).also { require(it >= 0L) },
        deathRequester = intent.requiredString(EXTRA_DEATH_REQUESTER).also { requester ->
            require(requester == "adb-run-as-kill-9" ||
                requester == "adb-am-crash-pid")
        },
        killExitElapsedMs = intent.getLongExtra(EXTRA_KILL_EXIT_ELAPSED_MS, -1L)
            .also { require(it >= 0L) },
        mainProcessSampleCount = intent.getLongExtra(EXTRA_MAIN_PROCESS_SAMPLE_COUNT, -1L)
            .also { require(it > 0L) },
        remoteProcessSampleCount = intent.getLongExtra(EXTRA_REMOTE_PROCESS_SAMPLE_COUNT, -1L)
            .also { require(it > 0L) },
        mainDisappearanceCount = intent.getLongExtra(EXTRA_MAIN_DISAPPEARANCE_COUNT, -1L)
            .also { require(it >= 0L) },
        entryFileCountBeforeKill =
            intent.getLongExtra(EXTRA_ENTRY_FILE_COUNT_BEFORE_KILL, -1L)
                .also { require(it > 0L) },
        entryFileCountAfterDeath =
            intent.getLongExtra(EXTRA_ENTRY_FILE_COUNT_AFTER_DEATH, -1L)
                .also { require(it > 0L) },
        entryFileCountAfterSilence =
            intent.getLongExtra(EXTRA_ENTRY_FILE_COUNT_AFTER_SILENCE, -1L)
                .also { require(it > 0L) },
        entryBytesBeforeKill = intent.getLongExtra(EXTRA_ENTRY_BYTES_BEFORE_KILL, -1L)
            .also { require(it > 0L) },
        entryBytesAfterDeath = intent.getLongExtra(EXTRA_ENTRY_BYTES_AFTER_DEATH, -1L)
            .also { require(it > 0L) },
        entryBytesAfterSilence = intent.getLongExtra(EXTRA_ENTRY_BYTES_AFTER_SILENCE, -1L)
            .also { require(it > 0L) },
        mainProcessSurvived = intent.getBooleanExtra(EXTRA_MAIN_PROCESS_SURVIVED, false),
        processingServiceBeforeKill =
            intent.getBooleanExtra(EXTRA_PROCESSING_SERVICE_BEFORE_KILL, false),
        processingServiceAfterDeath =
            intent.getBooleanExtra(EXTRA_PROCESSING_SERVICE_AFTER_DEATH, true),
        processingServiceAfterSilence =
            intent.getBooleanExtra(EXTRA_PROCESSING_SERVICE_AFTER_SILENCE, true),
        notificationBeforeKill = intent.getBooleanExtra(EXTRA_NOTIFICATION_BEFORE_KILL, false),
        notificationAfterDeath = intent.getBooleanExtra(EXTRA_NOTIFICATION_AFTER_DEATH, true),
        notificationAfterSilence =
            intent.getBooleanExtra(EXTRA_NOTIFICATION_AFTER_SILENCE, true),
        wakeLockBeforeKill = intent.getBooleanExtra(EXTRA_WAKE_LOCK_BEFORE_KILL, false),
        wakeLockAfterDeath = intent.getBooleanExtra(EXTRA_WAKE_LOCK_AFTER_DEATH, true),
        wakeLockAfterSilence = intent.getBooleanExtra(EXTRA_WAKE_LOCK_AFTER_SILENCE, true),
        packageStoppedBeforeKill =
            intent.getBooleanExtra(EXTRA_PACKAGE_STOPPED_BEFORE_KILL, true),
        packageStoppedAfterDeath =
            intent.getBooleanExtra(EXTRA_PACKAGE_STOPPED_AFTER_DEATH, true),
        packageStoppedAfterSilence =
            intent.getBooleanExtra(EXTRA_PACKAGE_STOPPED_AFTER_SILENCE, true),
    )

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
        stage: String,
        phase: String,
        error: Throwable,
    ) {
        writeJson(
            file,
            JSONObject()
                .put("schemaVersion", REPORT_SCHEMA_VERSION)
                .put("status", "failed")
                .put("stage", stage)
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

    private fun File.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(readBytes())
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

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
        val mainDeathBoundary: MainDeathBoundary,
        val remoteDeathRecoveryAction: RemoteDeathRecoveryAction,
        val secondaryModelId: String?,
        val secondaryArtifactSha256: String?,
    ) {
        val tryGpu: Boolean
            get() = backendMode == "auto"
    }

    private data class ForceStopEvidence(
        val journalSha256AfterStop: String,
        val journalSha256AfterSilence: String,
        val journalSha256AfterRestart: String,
        val entrySha256AfterStop: String,
        val entrySha256AfterSilence: String,
        val entrySha256AfterRestart: String,
        val journalSequenceAfterStop: Long,
        val journalSequenceAfterSilence: Long,
        val journalSequenceAfterRestart: Long,
        val forceStopExitElapsedMs: Long,
        val silentObservationMs: Long,
        val silentProcessSampleCount: Long,
        val unexpectedRelaunchCount: Long,
        val entryFileCountAfterStop: Long,
        val entryFileCountAfterSilence: Long,
        val entryFileCountAfterRestart: Long,
        val entryBytesAfterStop: Long,
        val entryBytesAfterSilence: Long,
        val entryBytesAfterRestart: Long,
        val packageStoppedAfterStop: Boolean,
        val packageStoppedAfterSilence: Boolean,
        val allProcessesExitedAfterStop: Boolean,
        val allProcessesExitedAfterSilence: Boolean,
        val notificationAfterStop: Boolean,
        val notificationAfterSilence: Boolean,
        val notificationAfterRestart: Boolean,
        val processingServiceAfterStop: Boolean,
        val processingServiceAfterSilence: Boolean,
        val processingServiceAfterRestart: Boolean,
        val wakeLockAfterStop: Boolean,
        val wakeLockAfterSilence: Boolean,
        val wakeLockAfterRestart: Boolean,
    )

    private data class RemoteDeathEvidence(
        val journalSha256BeforeKill: String,
        val journalSha256AfterDeath: String,
        val journalSha256AfterSilence: String,
        val entrySha256BeforeKill: String,
        val entrySha256AfterDeath: String,
        val entrySha256AfterSilence: String,
        val journalSequenceBeforeKill: Long,
        val journalSequenceAfterDeath: Long,
        val journalSequenceAfterSilence: Long,
        val silentObservationMs: Long,
        val silentProcessSampleCount: Long,
        val unexpectedRemoteRelaunchCount: Long,
        val unexpectedRemotePresenceSampleCount: Long,
        val deathRequester: String,
        val killExitElapsedMs: Long,
        val mainProcessSampleCount: Long,
        val remoteProcessSampleCount: Long,
        val mainDisappearanceCount: Long,
        val entryFileCountBeforeKill: Long,
        val entryFileCountAfterDeath: Long,
        val entryFileCountAfterSilence: Long,
        val entryBytesBeforeKill: Long,
        val entryBytesAfterDeath: Long,
        val entryBytesAfterSilence: Long,
        val mainProcessSurvived: Boolean,
        val processingServiceBeforeKill: Boolean,
        val processingServiceAfterDeath: Boolean,
        val processingServiceAfterSilence: Boolean,
        val notificationBeforeKill: Boolean,
        val notificationAfterDeath: Boolean,
        val notificationAfterSilence: Boolean,
        val wakeLockBeforeKill: Boolean,
        val wakeLockAfterDeath: Boolean,
        val wakeLockAfterSilence: Boolean,
        val packageStoppedBeforeKill: Boolean,
        val packageStoppedAfterDeath: Boolean,
        val packageStoppedAfterSilence: Boolean,
    )

    private enum class BeginMode(
        val stage: String,
        val killRequester: String,
    ) {
        MainProcessDeath(STAGE_MAIN_DEATH, "debug-main-process"),
        ForceStop(STAGE_FORCE_STOP, "adb-am-force-stop"),
        RemoteProcessDeath(STAGE_REMOTE_DEATH, "adb-external-process-death"),
    }

    private enum class MainDeathBoundary(
        val argumentValue: String,
        val faultStage: SourceSeparationCacheFaultStage? = null,
    ) {
        SegmentRunning("segment-running"),
        AfterFirstCommittedSegment("after-first-committed-segment"),
        TerminalCommit(
            argumentValue = "terminal-commit",
            faultStage = SourceSeparationCacheFaultStage.TerminalCommit,
        ),
        ;

        fun reached(journal: SourceSeparationCacheRunJournal): Boolean = when (this) {
            SegmentRunning -> journal.committedSegments.isEmpty() &&
                journal.transitions.any { transition ->
                    transition.type == SourceSeparationCacheRunTransitionType.SegmentRunning
                }
            AfterFirstCommittedSegment -> journal.committedSegments.isNotEmpty()
            TerminalCommit -> journal.committedSegments.isEmpty() &&
                journal.transitions.any { transition ->
                    transition.type == SourceSeparationCacheRunTransitionType.SegmentRunning
                }
        }

        companion object {
            fun parse(value: String?): MainDeathBoundary = entries.firstOrNull {
                it.argumentValue == value
            } ?: if (value.isNullOrBlank()) {
                SegmentRunning
            } else {
                error("Unsupported main-process death boundary: $value")
            }
        }
    }

    private enum class RemoteDeathRecoveryAction(val argumentValue: String) {
        Resume("resume"),
        ClearCacheThenStart("clear-cache"),
        SwitchModelThenStart("switch-model"),
        RejectArtifactMismatch("artifact-mismatch"),
        RejectModelLoss("model-loss"),
        ResumeLatchedCpuFallback("latched-fallback"),
        ;

        companion object {
            fun parse(value: String?): RemoteDeathRecoveryAction = entries.firstOrNull {
                it.argumentValue == value
            } ?: if (value.isNullOrBlank()) {
                Resume
            } else {
                error("Unsupported remote-death recovery action: $value")
            }
        }
    }

    const val COMMAND_BEGIN = "beginIndependentMainDeath"
    const val COMMAND_VALIDATE = "validateIndependentMainDeath"
    const val COMMAND_BEGIN_FORCE_STOP = "beginIndependentForceStop"
    const val COMMAND_VALIDATE_FORCE_STOP = "validateIndependentForceStop"
    const val COMMAND_BEGIN_REMOTE_DEATH = "beginIndependentRemoteDeath"
    const val COMMAND_VALIDATE_REMOTE_DEATH = "validateIndependentRemoteDeath"

    private const val EXTRA_RUN_ID = "runId"
    private const val EXTRA_SOURCE_PATH = "sourcePath"
    private const val EXTRA_MODEL_ID = "modelId"
    private const val EXTRA_ARTIFACT_SHA256 = "artifactSha256"
    private const val EXTRA_BACKEND_MODE = "backendMode"
    private const val EXTRA_KILL_BOUNDARY = "killBoundary"
    private const val EXTRA_REMOTE_DEATH_RECOVERY_ACTION = "remoteDeathRecoveryAction"
    private const val EXTRA_SECONDARY_MODEL_ID = "secondaryModelId"
    private const val EXTRA_SECONDARY_ARTIFACT_SHA256 = "secondaryArtifactSha256"
    private const val EXTRA_JOURNAL_SHA_AFTER_STOP = "journalSha256AfterStop"
    private const val EXTRA_JOURNAL_SHA_AFTER_SILENCE = "journalSha256AfterSilence"
    private const val EXTRA_JOURNAL_SHA_AFTER_RESTART = "journalSha256AfterRestart"
    private const val EXTRA_ENTRY_SHA_AFTER_STOP = "entrySha256AfterStop"
    private const val EXTRA_ENTRY_SHA_AFTER_SILENCE = "entrySha256AfterSilence"
    private const val EXTRA_ENTRY_SHA_AFTER_RESTART = "entrySha256AfterRestart"
    private const val EXTRA_JOURNAL_SEQUENCE_AFTER_STOP = "journalSequenceAfterStop"
    private const val EXTRA_JOURNAL_SEQUENCE_AFTER_SILENCE = "journalSequenceAfterSilence"
    private const val EXTRA_JOURNAL_SEQUENCE_AFTER_RESTART = "journalSequenceAfterRestart"
    private const val EXTRA_FORCE_STOP_EXIT_MS = "forceStopExitElapsedMs"
    private const val EXTRA_SILENT_OBSERVATION_MS = "silentObservationMs"
    private const val EXTRA_SILENT_PROCESS_SAMPLE_COUNT = "silentProcessSampleCount"
    private const val EXTRA_UNEXPECTED_RELAUNCH_COUNT = "unexpectedRelaunchCount"
    private const val EXTRA_ENTRY_FILE_COUNT_AFTER_STOP = "entryFileCountAfterStop"
    private const val EXTRA_ENTRY_FILE_COUNT_AFTER_SILENCE = "entryFileCountAfterSilence"
    private const val EXTRA_ENTRY_FILE_COUNT_AFTER_RESTART = "entryFileCountAfterRestart"
    private const val EXTRA_ENTRY_BYTES_AFTER_STOP = "entryBytesAfterStop"
    private const val EXTRA_ENTRY_BYTES_AFTER_SILENCE = "entryBytesAfterSilence"
    private const val EXTRA_ENTRY_BYTES_AFTER_RESTART = "entryBytesAfterRestart"
    private const val EXTRA_PACKAGE_STOPPED_AFTER_STOP = "packageStoppedAfterStop"
    private const val EXTRA_PACKAGE_STOPPED_AFTER_SILENCE = "packageStoppedAfterSilence"
    private const val EXTRA_ALL_PROCESSES_EXITED_AFTER_STOP = "allProcessesExitedAfterStop"
    private const val EXTRA_ALL_PROCESSES_EXITED_AFTER_SILENCE =
        "allProcessesExitedAfterSilence"
    private const val EXTRA_NOTIFICATION_AFTER_STOP = "notificationAfterStop"
    private const val EXTRA_NOTIFICATION_AFTER_SILENCE = "notificationAfterSilence"
    private const val EXTRA_NOTIFICATION_AFTER_RESTART = "notificationAfterRestart"
    private const val EXTRA_PROCESSING_SERVICE_AFTER_STOP = "processingServiceAfterStop"
    private const val EXTRA_PROCESSING_SERVICE_AFTER_SILENCE = "processingServiceAfterSilence"
    private const val EXTRA_PROCESSING_SERVICE_AFTER_RESTART = "processingServiceAfterRestart"
    private const val EXTRA_WAKE_LOCK_AFTER_STOP = "wakeLockAfterStop"
    private const val EXTRA_WAKE_LOCK_AFTER_SILENCE = "wakeLockAfterSilence"
    private const val EXTRA_WAKE_LOCK_AFTER_RESTART = "wakeLockAfterRestart"
    private const val EXTRA_JOURNAL_SHA_BEFORE_KILL = "journalSha256BeforeKill"
    private const val EXTRA_JOURNAL_SHA_AFTER_DEATH = "journalSha256AfterDeath"
    private const val EXTRA_ENTRY_SHA_BEFORE_KILL = "entrySha256BeforeKill"
    private const val EXTRA_ENTRY_SHA_AFTER_DEATH = "entrySha256AfterDeath"
    private const val EXTRA_JOURNAL_SEQUENCE_BEFORE_KILL = "journalSequenceBeforeKill"
    private const val EXTRA_JOURNAL_SEQUENCE_AFTER_DEATH = "journalSequenceAfterDeath"
    private const val EXTRA_UNEXPECTED_REMOTE_RELAUNCH_COUNT =
        "unexpectedRemoteRelaunchCount"
    private const val EXTRA_UNEXPECTED_REMOTE_PRESENCE_SAMPLE_COUNT =
        "unexpectedRemotePresenceSampleCount"
    private const val EXTRA_DEATH_REQUESTER = "deathRequester"
    private const val EXTRA_KILL_EXIT_ELAPSED_MS = "killExitElapsedMs"
    private const val EXTRA_MAIN_PROCESS_SAMPLE_COUNT = "mainProcessSampleCount"
    private const val EXTRA_REMOTE_PROCESS_SAMPLE_COUNT = "remoteProcessSampleCount"
    private const val EXTRA_MAIN_DISAPPEARANCE_COUNT = "mainDisappearanceCount"
    private const val EXTRA_ENTRY_FILE_COUNT_BEFORE_KILL = "entryFileCountBeforeKill"
    private const val EXTRA_ENTRY_FILE_COUNT_AFTER_DEATH = "entryFileCountAfterDeath"
    private const val EXTRA_ENTRY_BYTES_BEFORE_KILL = "entryBytesBeforeKill"
    private const val EXTRA_ENTRY_BYTES_AFTER_DEATH = "entryBytesAfterDeath"
    private const val EXTRA_MAIN_PROCESS_SURVIVED = "mainProcessSurvived"
    private const val EXTRA_PROCESSING_SERVICE_BEFORE_KILL = "processingServiceBeforeKill"
    private const val EXTRA_PROCESSING_SERVICE_AFTER_DEATH = "processingServiceAfterDeath"
    private const val EXTRA_NOTIFICATION_BEFORE_KILL = "notificationBeforeKill"
    private const val EXTRA_NOTIFICATION_AFTER_DEATH = "notificationAfterDeath"
    private const val EXTRA_WAKE_LOCK_BEFORE_KILL = "wakeLockBeforeKill"
    private const val EXTRA_WAKE_LOCK_AFTER_DEATH = "wakeLockAfterDeath"
    private const val EXTRA_PACKAGE_STOPPED_BEFORE_KILL = "packageStoppedBeforeKill"
    private const val EXTRA_PACKAGE_STOPPED_AFTER_DEATH = "packageStoppedAfterDeath"
    private const val OUTPUT_DIRECTORY = "phase7-debug-main-death"
    private const val SCENARIO_SCHEMA_VERSION = 3
    private const val REPORT_SCHEMA_VERSION = 2
    private const val FORCE_STOP_REPORT_SCHEMA_VERSION = "phase7-task-lifecycle-report-v1"
    private const val REMOTE_DEATH_REPORT_SCHEMA_VERSION = "phase7-remote-death-report-v1"
    private const val REMOTE_DEATH_CACHE_CLEAR_REPORT_SCHEMA_VERSION =
        "phase7-remote-death-cache-clear-report-v1"
    private const val REMOTE_DEATH_MODEL_SWITCH_REPORT_SCHEMA_VERSION =
        "phase7-remote-death-model-switch-report-v1"
    private const val REMOTE_DEATH_RUNTIME_POLICY_REPORT_SCHEMA_VERSION =
        "phase7-remote-death-runtime-policy-report-v1"
    private const val REMOTE_DEATH_MODEL_LOSS_REPORT_SCHEMA_VERSION =
        "phase7-remote-death-model-loss-report-v1"
    private const val REMOTE_DEATH_LATCHED_FALLBACK_REPORT_SCHEMA_VERSION =
        "phase7-remote-death-latched-fallback-report-v1"
    private const val STAGE_MAIN_DEATH = "independent-main-death"
    private const val STAGE_FORCE_STOP = "force-stop"
    private const val STAGE_REMOTE_DEATH = "independent-remote-death"
    private const val SETUP_TIMEOUT_MS = 5L * 60L * 1_000L
    private const val REATTACH_TIMEOUT_MS = 60_000L
    private const val COMPLETION_TIMEOUT_MS = 30L * 60L * 1_000L
    private const val MAIN_DEATH_SETTLE_MS = 100L
    private const val REMOTE_DEATH_BARRIER_TIMEOUT_MS = 120_000L
    private const val REJECTED_RETRY_POSITION_UPDATE_COUNT = 5
    private const val REJECTED_RETRY_POSITION_SETTLE_MS = 500L
    private const val MAX_FAULT_TOKEN_LENGTH = 120
    private const val POLL_MS = 100L
    private const val MEDIA_SCAN_RETRIES = 60
    private const val MEDIA_SCAN_POLL_MS = 500L
    private const val TEST_BLEND = 0.23f
    private const val EXPECTED_FULL_SONG_MODEL_ID = "uvr_mdxnet_3_9662"
    private const val EXPECTED_FULL_SONG_SEGMENTS = 48
    private const val MODEL_BACKUP_DIRECTORY = "phase7-model-backups"
    private const val TAG = "SrcSepMainDeath"
    private val SAFE_NAME = Regex("^[A-Za-z0-9._-]{1,120}$")
    private val SAFE_EXTENSION = Regex("^[a-z0-9]{1,8}$")
}
