package com.mardous.booming.separation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SourceSeparationProductionRouteAuditTest {
    @Test
    fun `normal application routes do not reference legacy or ORT runtime types`() {
        val prohibited = listOf(
            "import com.mardous.booming.separation.SourceSeparationEngine",
            "SourceSeparationModelRepository",
            "MdxModelVariant",
            "SourceSeparationOrtOracle",
            "ai.onnxruntime",
        )

        normalRouteSources.forEach { relativePath ->
            val source = mainSource(relativePath).readText()
            prohibited.forEach { token ->
                assertFalse("$relativePath references $token", source.contains(token))
            }
        }
    }

    @Test
    fun `production range execution selects LiteRT explicitly`() {
        val source = mainSource(
            "com/mardous/booming/separation/MdxSourceSeparationModelAwareRangeExecutor.kt",
        ).readText()

        assertTrue(source.contains("createAutoLiteRtSessionProvider"))
        assertTrue(source.contains("createCpuLiteRtSessionProvider"))
        assertTrue(source.contains("sessionProviderFactory(request.backendPolicy)"))
        assertTrue(source.contains("MdxLiteRtGpuRuntimeProfile.BoundedOpenClFp32V1"))
        assertFalse(source.contains("SourceSeparationOrtOracle"))
        assertFalse(source.contains("MdxInferenceBackend.OrtCpu"))
    }

    @Test
    fun `production playback and prefetch inference use the remote process`() {
        val source = mainSource("com/mardous/booming/MainModule.kt").readText()

        assertTrue(source.contains("SourceSeparationModelAwareEngine.createBoundRemotePrototype("))
        assertFalse(source.contains("SourceSeparationModelAwareEngine.createProduction("))
    }

    @Test
    fun `worker completion keeps identity stable across its guarded main thread boundary`() {
        val coordinator = mainSource(
            "com/mardous/booming/ui/screen/player/SourceSeparationForegroundWorkerCoordinator.kt",
        ).readText()
        val viewModel = mainSource(
            "com/mardous/booming/ui/screen/player/PlayerViewModel.kt",
        ).readText()
        val completionStart = viewModel.indexOf(
            "override fun onSourceSeparationWorkerCompleted(",
        )
        val completionEnd = viewModel.indexOf(
            "override fun onSourceSeparationWorkerPaused(",
            completionStart,
        )
        val completion = viewModel.substring(completionStart, completionEnd)
        val identitySnapshot = completion.indexOf("val acceptsCurrentPlaybackState")
        val mainThreadLaunch = completion.indexOf("viewModelScope.launch")
        val controllerRead = completion.indexOf(
            "playWhenReady = sourceSeparationPlaybackHasPlayIntent()",
        )

        assertTrue(coordinator.contains("dispatchCallbackSafely("))
        assertFalse(coordinator.contains("callbacks?.onSourceSeparationWorker"))
        assertTrue(identitySnapshot >= 0)
        assertTrue(identitySnapshot < mainThreadLaunch)
        assertTrue(controllerRead > mainThreadLaunch)
    }

    @Test
    fun `playback state broadcasts do not poll the cache repository`() {
        val viewModel = mainSource(
            "com/mardous/booming/ui/screen/player/PlayerViewModel.kt",
        ).readText()
        val updateStart = viewModel.indexOf(
            "fun updateSourceSeparationPlaybackState(args: Bundle)",
        )
        val updateEnd = viewModel.indexOf(
            "private suspend fun sendSourceSeparationPlaybackCommand(",
            updateStart,
        )
        val update = viewModel.substring(updateStart, updateEnd)

        assertFalse(update.contains("refreshCurrentSourceSeparationCacheAvailable"))
    }

    @Test
    fun `media transition bookkeeping uses a main-thread playback snapshot`() {
        val service = mainSource(
            "com/mardous/booming/playback/PlaybackService.kt",
        ).readText()
        val transitionStart = service.indexOf("override fun onMediaItemTransition(")
        val ioStart = service.indexOf("serviceScope.launch(IO)", transitionStart)
        val ioEnd = service.indexOf(
            "if (player.currentMediaItemIndex == stopIndex)",
            ioStart,
        )
        require(transitionStart >= 0 && ioStart > transitionStart && ioEnd > ioStart)

        val callbackSetup = service.substring(transitionStart, ioStart)
        val ioBookkeeping = service.substring(ioStart, ioEnd)
        assertTrue(callbackSetup.contains("val isPlayingAtTransition = player.isPlaying"))
        assertTrue(ioBookkeeping.contains("isPlayingAtTransition"))
        assertFalse(ioBookkeeping.contains("player.isPlaying"))
    }

    @Test
    fun `next song prestart checks its model and cache off the main thread`() {
        val coordinator = mainSource(
            "com/mardous/booming/ui/screen/player/SourceSeparationForegroundWorkerCoordinator.kt",
        ).readText()
        val preStart = coordinator.substring(
            coordinator.indexOf("suspend fun preStartSong("),
            coordinator.indexOf("fun pauseCurrentSong(", coordinator.indexOf("suspend fun preStartSong(")),
        )

        assertTrue(preStart.contains("withContext(Dispatchers.IO)"))
        assertTrue(preStart.contains("hasReadyPlaybackStartCache(song, readyWindowCount)"))
        assertTrue(
            preStart.indexOf("withContext(Dispatchers.IO)") <
                    preStart.indexOf("hasReadyPlaybackStartCache(song, readyWindowCount)"),
        )
        assertTrue(preStart.contains("requestGeneration.get() != request.requestGeneration"))
        assertTrue(preStart.contains("activeSelectionFlow.value != request.selection"))
        assertTrue(preStart.contains("val multiStemSelection = multiStemSelectionFlow.value"))
        assertTrue(preStart.contains("multiStemSelectionFlow.value != multiStemSelection"))
        assertTrue(
            preStart.substring(preStart.indexOf("val action = synchronized(stateLock)"))
                .contains("!playbackOwnerActive.get()"),
        )
    }

    @Test
    fun `user seek retargets an unready wait while internal flush preserves it`() {
        val service = mainSource(
            "com/mardous/booming/playback/PlaybackService.kt",
        ).readText()
        val seekStart = service.indexOf("override fun onPositionDiscontinuity(")
        val seekEnd = service.indexOf("override fun onPlayerError(", seekStart)
        require(seekStart >= 0 && seekEnd > seekStart)

        val seek = service.substring(seekStart, seekEnd)
        assertTrue(seek.contains("sourceSeparationPlaybackWindowWaitTracker.current"))
        assertTrue(seek.contains("wasWaitingForUnreadyWindow && !isInternalRecoveryFlushSeek"))
        assertTrue(seek.contains("sourceSeparationPlaybackWindowWaitTracker.retarget("))
        assertTrue(seek.contains("anchorPositionMs = newPosition.positionMs"))
        assertTrue(seek.contains("wasWaitingForUnreadyWindow && isInternalRecoveryFlushSeek"))
        assertTrue(seek.contains("playback.seek.preserveRecoveryWaterline"))
        assertTrue(seek.contains("scheduleSourceSeparationPlaybackGateRetry()"))
        assertTrue(
            seek.contains(
                "wasWaitingForUnreadyWindow ||\n" +
                        "                                shouldGateCurrentSourceSeparationWindow()",
            ),
        )
        assertTrue(
            seek.contains("activeWindowWait?.requiredReadyWindowCount ?: 1"),
        )
    }

    @Test
    fun `playback switches flush queued original audio before accepting mixed output`() {
        val service = mainSource(
            "com/mardous/booming/playback/PlaybackService.kt",
        ).readText()
        val processor = mainSource(
            "com/mardous/booming/playback/processor/SourceSeparationMixAudioProcessor.kt",
        ).readText()

        assertTrue(service.contains("requestSourceSeparationOutputFlush(\"newSession\")"))
        assertTrue(service.contains("requestSourceSeparationOutputFlush(\"completedCacheUpgrade\")"))
        assertTrue(service.contains("requestSourceSeparationOutputFlush(\"realignAfterProcessing\")"))
        assertTrue(service.contains("clearSourceSeparationOutputFlushBarrier(\"playback.clear\")"))
        assertTrue(service.contains("forceDiscontinuity = true"))
        assertTrue(service.contains("reason=flushPending"))
        assertTrue(service.contains("playback.outputFlush.confirmed"))
        assertTrue(processor.contains("override fun onFlush(streamMetadata"))
        assertTrue(processor.contains("prerollMs = if (barrierId != null) 0L"))
        assertTrue(processor.contains("outputFlushedSink?.invoke(barrierId, outputGeneration)"))
    }

    @Test
    fun `active playback entry points gate the current unready window`() {
        val service = mainSource(
            "com/mardous/booming/playback/PlaybackService.kt",
        ).readText()

        fun section(startToken: String, endToken: String): String {
            val start = service.indexOf(startToken)
            val end = service.indexOf(endToken, start)
            require(start >= 0 && end > start) {
                "Could not locate playback section $startToken"
            }
            return service.substring(start, end)
        }

        assertTrue(
            section(
                "private suspend fun setSourceSeparationPlaybackEnabled(",
                "private suspend fun syncSourceSeparationPlayback(",
            ).contains("gateOnUnreadyWindow = shouldGateCurrentSourceSeparationWindow()"),
        )
        assertTrue(
            section(
                "private suspend fun syncSourceSeparationPlayback(",
                "private suspend fun handleSourceSeparationCacheDeleted(",
            ).contains("gateOnUnreadyWindow = shouldGateCurrentSourceSeparationWindow()"),
        )
        assertTrue(
            section(
                "private suspend fun probeSourceSeparationPlaybackFromWorker(",
                "private fun onSourceSeparationSeekRequested(",
            ).contains("gateOnUnreadyWindow = shouldGateCurrentSourceSeparationWindow()"),
        )
        assertTrue(
            section(
                "private suspend fun handleSourceSeparationModelSelectionChanged(",
                "private fun observeSourceSeparationProcessingOwnership(",
            ).contains("gateOnUnreadyWindow = shouldGateCurrentSourceSeparationWindow()"),
        )
        val committedBlend = section(
            "private suspend fun setSourceSeparationBlend(",
            "private suspend fun probeSourceSeparationPlaybackFromWorker(",
        )
        assertTrue(committedBlend.contains("if (persist &&"))
        assertTrue(committedBlend.contains("sourceSeparationPlaybackRequested"))
        assertTrue(
            committedBlend.contains(
                "SourceSeparationBlendDemand.requiresSeparatedOutput(blend)",
            ),
        )
        assertTrue(
            section(
                "private suspend fun ensureSourceSeparationPlaybackReadyLocked(",
                "private fun shouldUpgradeActiveSourceSeparationSession(",
            ).contains("preserveExpectedProcessing = effectiveExpectProcessing"),
        )
        assertTrue(
            service.contains(
                "sourceSeparationPlaybackSession == null ||\n" +
                        "                    sourceSeparationPlaybackSession?.requiresReadinessGate == true",
            ),
        )
        val dataPlaneResume = section(
            "private fun resumeSourceSeparationDataPlaneIfRequested(",
            "private fun handleSourceSeparationDataPlaneFailure(",
        )
        assertTrue(dataPlaneResume.contains("sourceSeparationPlaybackIsProcessing"))
        assertTrue(dataPlaneResume.contains("sourceSeparationPlaybackWindowWaitTracker.current"))
        assertTrue(dataPlaneResume.contains("playback.dataPlaneMonitor.readyDeferred"))
    }

    @Test
    fun `successful temporary cleanup publishes an exact cache refresh event`() {
        val playback = mainSource(
            "com/mardous/booming/playback/Playback.kt",
        ).readText()
        val service = mainSource(
            "com/mardous/booming/playback/PlaybackService.kt",
        ).readText()
        val activity = mainSource(
            "com/mardous/booming/ui/screen/MainActivity.kt",
        ).readText()
        val viewModel = mainSource(
            "com/mardous/booming/ui/screen/player/PlayerViewModel.kt",
        ).readText()

        assertTrue(playback.contains("EVENT_SOURCE_SEPARATION_CACHE_CHANGED"))
        assertTrue(playback.contains("EXTRA_SOURCE_SEPARATION_CACHE_KEYS"))
        assertTrue(service.contains("if (cleanedCacheKeys.isNotEmpty())"))
        assertTrue(service.contains("Playback.EVENT_SOURCE_SEPARATION_CACHE_CHANGED"))
        assertTrue(activity.contains("onSourceSeparationCacheArtifactsCleaned("))
        assertTrue(viewModel.contains("if (currentCacheKey in cacheKeys)"))
    }

    @Test
    fun `completed cache upgrade releases the obsolete playback before cleanup`() {
        val service = mainSource(
            "com/mardous/booming/playback/PlaybackService.kt",
        ).readText()
        val start = service.indexOf(
            "private suspend fun switchActiveSourceSeparationSessionToCompletedCache(",
        )
        val end = service.indexOf(
            "private suspend fun setSourceSeparationBlend(",
            start,
        )
        val upgrade = service.substring(start, end)

        val release = upgrade.indexOf("activeSession.closeModelAwareResources()")
        val cleanupTrigger = upgrade.indexOf("broadcastSourceSeparationPlaybackChanged()")
        assertTrue(release >= 0)
        assertTrue(release < cleanupTrigger)
    }

    @Test
    fun `remote start binds admission to the calling host callback`() {
        val aidl = mainAidl(
            "com/mardous/booming/separation/process/ipc/ISourceSeparationExecutionService.aidl",
        ).readText()
        val host = mainSource(
            "com/mardous/booming/separation/process/ipc/BoundRemoteSourceSeparationExecutionHost.kt",
        ).readText()
        val service = mainSource(
            "com/mardous/booming/separation/process/ipc/SourceSeparationExecutionService.kt",
        ).readText()

        assertTrue(Regex(
            """String\s+start\(.*ISourceSeparationExecutionCallback\s+callback.*\);""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        ).containsMatchIn(aidl))
        assertTrue(Regex(
            """service\.start\(\s*payload,\s*observerId,\s*AppProcessResolver\.resolve\(applicationContext\)\.processName,\s*callback,""",
        ).containsMatchIn(host))
        assertTrue(Regex(
            """reserveRun\(\s*command\s*=\s*command,\s*observerId\s*=\s*observerId,\s*clientProcessName\s*=\s*clientProcessName,\s*callback\s*=\s*callback,""",
        ).containsMatchIn(service))
        assertTrue(service.contains("val connectedClient = replaceClientLocked("))
    }

    @Test
    fun `generic inference boundaries have no implicit ORT provider`() {
        val runtime = mainSource(
            "com/mardous/booming/separation/model/MdxInferenceRuntime.kt",
        ).readText()
        val separator = mainSource(
            "com/mardous/booming/separation/model/MdxRangeSeparator.kt",
        ).readText()

        assertFalse(runtime.contains("DefaultMdxInferenceSessionProvider"))
        assertFalse(runtime.contains("MdxInferenceSessionFactory ="))
        assertFalse(separator.contains("sessionProvider: MdxInferenceSessionProvider ="))
        assertTrue(separator.contains("execution: MdxSeparationExecution"))
    }

    @Test
    fun `release source and dependencies contain no executable ONNX runtime`() {
        val ortSources = mainKotlinRoot.walkTopDown()
            .filter(File::isFile)
            .filter { it.extension == "kt" && "ai.onnxruntime" in it.readText() }
            .map { it.relativeTo(mainKotlinRoot).invariantSeparatorsPath }
            .toList()

        assertTrue("Production source still imports ONNX Runtime: $ortSources", ortSources.isEmpty())
        assertFalse(appBuildFile.readText().contains("onnxruntime", ignoreCase = true))
        assertFalse(versionCatalogFile.readText().contains("onnxruntime", ignoreCase = true))
    }

    @Test
    fun `multi-stem gain previews avoid full playback and UI state work`() {
        val service = mainSource(
            "com/mardous/booming/playback/PlaybackService.kt",
        ).readText()
        val serviceStart = service.indexOf("private suspend fun setSourceSeparationStemGains(")
        val serviceEnd = service.indexOf("private fun applySourceSeparationBlend(", serviceStart)
        require(serviceStart >= 0 && serviceEnd > serviceStart)
        val gainCommand = service.substring(serviceStart, serviceEnd)
        val previewExit = gainCommand.indexOf("if (!commit)")
        val liveGainApply = gainCommand.indexOf(
            "sourceSeparationMixProcessor.setStemGains(normalizedGains)",
        )
        val committedStateWork = gainCommand.indexOf(
            "if (affectsCurrentPlayback)",
            previewExit,
        )
        val broadcast = gainCommand.indexOf("broadcastSourceSeparationPlaybackChanged()")

        assertTrue(previewExit >= 0)
        assertTrue(liveGainApply in 0 until previewExit)
        assertTrue(committedStateWork > previewExit)
        assertTrue(broadcast > committedStateWork)
        assertTrue(
            gainCommand.substring(previewExit, committedStateWork)
                .contains("return SessionResult(SessionResult.RESULT_SUCCESS)"),
        )

        val viewModel = mainSource(
            "com/mardous/booming/ui/screen/player/PlayerViewModel.kt",
        ).readText()
        val viewModelStart = viewModel.indexOf("fun previewSourceSeparationStemGain(")
        val viewModelEnd = viewModel.indexOf("fun setSourceSeparationStemGain(", viewModelStart)
        require(viewModelStart >= 0 && viewModelEnd > viewModelStart)
        val preview = viewModel.substring(viewModelStart, viewModelEnd)

        assertFalse(preview.contains("_sourceSeparationMultiStemMixStateFlow.value ="))
        assertFalse(preview.contains("updateSourceSeparationPlaybackState"))

        val cleanupStart = service.indexOf(
            "private fun cleanupCompletedSourceSeparationTemporaryDirs()",
        )
        val cleanupEnd = service.indexOf(
            "private fun cleanCompletedSourceSeparationTemporaryDirsNow()",
            cleanupStart,
        )
        require(cleanupStart >= 0 && cleanupEnd > cleanupStart)
        val cleanup = service.substring(cleanupStart, cleanupEnd)
        assertTrue(cleanup.contains("sourceSeparationCompletedCleanupJob?.isActive == true"))
        assertTrue(cleanup.contains("sourceSeparationCompletedCleanupJob = serviceScope.launch(IO)"))
    }

    private fun mainSource(relativePath: String): File =
        File(mainKotlinRoot, relativePath).also { source ->
            require(source.isFile) { "Missing production source: $relativePath" }
        }

    private fun mainAidl(relativePath: String): File =
        File(mainAidlRoot, relativePath).also { source ->
            require(source.isFile) { "Missing production AIDL: $relativePath" }
        }

    private val mainKotlinRoot: File
        get() {
            val workingDirectory = File(requireNotNull(System.getProperty("user.dir")))
            return listOf(
                File(workingDirectory, "src/main/java"),
                File(workingDirectory, "app/src/main/java"),
            ).firstOrNull(File::isDirectory)
                ?: error("Cannot locate the app production source root from $workingDirectory")
        }

    private val mainAidlRoot: File
        get() {
            val workingDirectory = File(requireNotNull(System.getProperty("user.dir")))
            return listOf(
                File(workingDirectory, "src/main/aidl"),
                File(workingDirectory, "app/src/main/aidl"),
            ).firstOrNull(File::isDirectory)
                ?: error("Cannot locate the app production AIDL root from $workingDirectory")
        }

    private val appBuildFile: File
        get() = File(appRoot, "build.gradle.kts")

    private val versionCatalogFile: File
        get() = File(requireNotNull(appRoot.parentFile), "gradle/libs.versions.toml")

    private val appRoot: File
        get() = requireNotNull(mainKotlinRoot.parentFile?.parentFile?.parentFile)

    private companion object {
        val normalRouteSources = listOf(
            "com/mardous/booming/MainModule.kt",
            "com/mardous/booming/playback/PlaybackService.kt",
            "com/mardous/booming/separation/SourceSeparationRuntimeFacade.kt",
            "com/mardous/booming/ui/screen/player/PlayerViewModel.kt",
            "com/mardous/booming/ui/screen/player/SourceSeparationForegroundWorkerCoordinator.kt",
            "com/mardous/booming/ui/screen/player/SourceSeparationModelAwareCacheManagementViewModel.kt",
            "com/mardous/booming/ui/screen/player/SourceSeparationPresetManagementViewModel.kt",
        )
    }
}
