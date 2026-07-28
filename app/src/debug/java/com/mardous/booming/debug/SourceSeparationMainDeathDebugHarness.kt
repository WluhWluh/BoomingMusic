package com.mardous.booming.debug

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
import com.mardous.booming.separation.SourceSeparationRuntimeFacade
import com.mardous.booming.separation.SourceSeparationRuntimeSongResolution
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheMutationResult
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
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwareCachePlayback
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwareCacheStatus
import com.mardous.booming.separation.model.litert.MdxLiteRtGpuRuntimeProfile
import com.mardous.booming.separation.model.preset.SourceSeparationActivePresetState
import com.mardous.booming.separation.model.preset.SourceSeparationPresetRepository
import com.mardous.booming.separation.process.SourceSeparationProcessingOwnershipHandoff
import com.mardous.booming.separation.process.SourceSeparationProcParser
import com.mardous.booming.separation.process.SourceSeparationProcessSessionState
import com.mardous.booming.separation.process.ipc.BoundRemoteSourceSeparationExecutionHost
import com.mardous.booming.separation.process.ipc.SourceSeparationMediaProcessingForegroundController
import com.mardous.booming.ui.screen.player.SourceSeparationForegroundWorkerCoordinator
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

            writeJson(
                scenarioFile,
                JSONObject()
                    .put("schemaVersion", SCENARIO_SCHEMA_VERSION)
                    .put("stage", mode.stage)
                    .put("runId", request.runId)
                    .put("cacheKey", runtimeSong.cacheKey)
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
        try {
            val evidence = remoteDeathEvidence(intent)
            val scenario = JSONObject(scenarioFile.readText(Charsets.UTF_8))
            check(scenario.getInt("schemaVersion") == SCENARIO_SCHEMA_VERSION)
            check(scenario.getString("stage") == STAGE_REMOTE_DEATH)
            check(scenario.getString("runId") == request.runId)
            check(scenario.getString("backendMode") == request.backendMode)
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
            val persistedTryGpu = !originalTryGpu
            check(preferences.edit()
                .putBoolean(SOURCE_SEPARATION_TRY_GPU, persistedTryGpu)
                .commit()
            ) { "Could not toggle the GPU preference before explicit resume." }
            check(preferences.getBoolean(SOURCE_SEPARATION_TRY_GPU, originalTryGpu) ==
                persistedTryGpu)
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
            mediaUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
        }
    }

    private fun validate(context: Context, request: Request) {
        val scenarioFile = scenarioFile(context, request.runId)
        val outputFile = reportFile(context, request.runId)
        var mediaUri: Uri? = null
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
            check(oldMainPid != Process.myPid())
            check(!File("/proc/$oldMainPid").exists())
            check(File("/proc/$remotePid").isDirectory) {
                "The authoritative inference process did not survive main-process death."
            }

            val store = get<SourceSeparationCacheStore>(SourceSeparationCacheStore::class.java)
            val reattachedBeforeHarness = waitForJournal(REATTACH_TIMEOUT_MS) {
                store.readRunJournal(cacheKey)?.takeIf { journal ->
                    journal.lifecycle == SourceSeparationCacheRunJournalLifecycle.Running &&
                        journal.transitions.filter { transition ->
                            transition.type ==
                                SourceSeparationCacheRunTransitionType.ObserverConnected ||
                                transition.type ==
                                    SourceSeparationCacheRunTransitionType.ObserverDisconnected
                        }.let { observers ->
                            observers.size >= 3 &&
                                observers.last().type ==
                                    SourceSeparationCacheRunTransitionType.ObserverConnected
                        }
                }
            }
            check(reattachedBeforeHarness.request.ownerPid == remotePid)
            check(reattachedBeforeHarness.request.processGeneration == processGeneration)
            check(reattachedBeforeHarness.request.runId == executionRunId)

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
            check(observerTransitions.size >= 3)
            check(observerTransitions.last().type ==
                SourceSeparationCacheRunTransitionType.ObserverConnected)
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
                    .put("journalSequenceBeforeDeath", scenario.getLong("journalSequence"))
                    .put(
                        "journalSequenceBeforeHarnessValidation",
                        reattachedBeforeHarness.latestSequence,
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
                    .put("observerTransitionCount", observerTransitions.size)
                    .put("productObserverConnectedBeforeHarness", true)
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
    ): SourceSeparationCacheFaultHit {
        val deadline = SystemClock.elapsedRealtime() + REMOTE_DEATH_BARRIER_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            SourceSeparationCacheFaultInjection.readHit(root)?.let { hit ->
                if (hit.token == token &&
                    hit.stage == SourceSeparationCacheFaultStage.NativeInvocation
                ) {
                    return hit
                }
            }
            SystemClock.sleep(POLL_MS)
        }
        error("Timed out waiting for the remote-death native-invocation barrier.")
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
        return Request(
            runId = runId,
            sourcePath = sourcePath,
            modelId = intent.requiredString(EXTRA_MODEL_ID),
            artifactSha256 = intent.requiredString(EXTRA_ARTIFACT_SHA256),
            backendMode = backendMode,
            mainDeathBoundary = MainDeathBoundary.parse(
                intent.getStringExtra(EXTRA_KILL_BOUNDARY),
            ),
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
        RemoteProcessDeath(STAGE_REMOTE_DEATH, "adb-run-as-kill-9"),
    }

    private enum class MainDeathBoundary(val argumentValue: String) {
        SegmentRunning("segment-running"),
        AfterFirstCommittedSegment("after-first-committed-segment"),
        ;

        fun reached(journal: SourceSeparationCacheRunJournal): Boolean = when (this) {
            SegmentRunning -> journal.committedSegments.isEmpty() &&
                journal.transitions.any { transition ->
                    transition.type == SourceSeparationCacheRunTransitionType.SegmentRunning
                }
            AfterFirstCommittedSegment -> journal.committedSegments.isNotEmpty()
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
    private const val SCENARIO_SCHEMA_VERSION = 2
    private const val REPORT_SCHEMA_VERSION = 1
    private const val FORCE_STOP_REPORT_SCHEMA_VERSION = "phase7-task-lifecycle-report-v1"
    private const val REMOTE_DEATH_REPORT_SCHEMA_VERSION = "phase7-remote-death-report-v1"
    private const val STAGE_MAIN_DEATH = "independent-main-death"
    private const val STAGE_FORCE_STOP = "force-stop"
    private const val STAGE_REMOTE_DEATH = "independent-remote-death"
    private const val SETUP_TIMEOUT_MS = 5L * 60L * 1_000L
    private const val REATTACH_TIMEOUT_MS = 60_000L
    private const val COMPLETION_TIMEOUT_MS = 30L * 60L * 1_000L
    private const val MAIN_DEATH_SETTLE_MS = 100L
    private const val REMOTE_DEATH_BARRIER_TIMEOUT_MS = 120_000L
    private const val MAX_FAULT_TOKEN_LENGTH = 120
    private const val POLL_MS = 100L
    private const val MEDIA_SCAN_RETRIES = 60
    private const val MEDIA_SCAN_POLL_MS = 500L
    private const val TEST_BLEND = 0.23f
    private const val EXPECTED_FULL_SONG_MODEL_ID = "uvr_mdxnet_3_9662"
    private const val EXPECTED_FULL_SONG_SEGMENTS = 48
    private const val TAG = "SrcSepMainDeath"
    private val SAFE_NAME = Regex("^[A-Za-z0-9._-]{1,120}$")
    private val SAFE_EXTENSION = Regex("^[a-z0-9]{1,8}$")
}
