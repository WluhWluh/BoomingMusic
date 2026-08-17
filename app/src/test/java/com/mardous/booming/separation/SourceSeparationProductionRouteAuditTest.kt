package com.mardous.booming.separation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SourceSeparationProductionRouteAuditTest {
    @Test
    fun `current data paths expose no unreleased compatibility adapters`() {
        val selection = mainSource(
            "com/mardous/booming/separation/SourceSeparationExecutionSelection.kt",
        ).readText()
        val coordinator = mainSource(
            "com/mardous/booming/ui/screen/player/SourceSeparationForegroundWorkerCoordinator.kt",
        ).readText()
        val service = mainSource(
            "com/mardous/booming/playback/PlaybackService.kt",
        ).readText()

        assertFalse(selection.contains("fromLegacySelections"))
        assertFalse(coordinator.contains("migrateTemporaryPerSongBlend"))
        assertFalse(service.contains("\"vocalsFile\""))
        assertFalse(service.contains("\"instrumentalFile\""))
        assertTrue(service.contains("\"stemFiles\""))
        assertTrue(service.contains("\"stemIds\""))
    }

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
        val currentSongSnapshot = completion.indexOf("val isCurrentSong")
        val identitySnapshot = completion.indexOf("val acceptsCurrentPlaybackState")
        val mainThreadLaunch = completion.indexOf("viewModelScope.launch")
        val controllerRead = completion.indexOf(
            "playWhenReady = sourceSeparationPlaybackHasPlayIntent()",
        )

        assertTrue(coordinator.contains("dispatchCallbackSafely("))
        assertFalse(coordinator.contains("callbacks?.onSourceSeparationWorker"))
        assertTrue(currentSongSnapshot >= 0)
        assertTrue(currentSongSnapshot < mainThreadLaunch)
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
    fun `media transition resolutions cannot commit after a newer song wins`() {
        val service = mainSource(
            "com/mardous/booming/playback/PlaybackService.kt",
        ).readText()
        val transitionStart = service.indexOf("override fun onMediaItemTransition(")
        val ioStart = service.indexOf("serviceScope.launch(IO)", transitionStart)
        val ioEnd = service.indexOf(
            "if (player.currentMediaItemIndex == stopIndex)",
            ioStart,
        )
        val workerUpdateStart = service.indexOf(
            "private fun updateSourceSeparationForegroundWorkerSong(",
        )
        val workerUpdateEnd = service.indexOf(
            "private fun updateSourceSeparationForegroundWorkerPosition(",
            workerUpdateStart,
        )
        require(
            transitionStart >= 0 && ioStart > transitionStart && ioEnd > ioStart &&
                    workerUpdateStart >= 0 && workerUpdateEnd > workerUpdateStart,
        )

        val callbackSetup = service.substring(transitionStart, ioStart)
        val ioResolution = service.substring(ioStart, ioEnd)
        val workerUpdate = service.substring(workerUpdateStart, workerUpdateEnd)
        assertTrue(
            callbackSetup.contains(
                "mediaItemTransitionTracker.begin(mediaItem?.mediaId)",
            ),
        )
        assertTrue(ioResolution.contains("isCurrentResolvedMediaTransition(mediaTransition, newSong)"))
        assertTrue(ioResolution.contains("return@withContext null"))
        assertTrue(workerUpdate.contains("isCurrentResolvedMediaTransition(transition, song)"))
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
        assertTrue(preStart.contains("executionSelectionFlow.value != request.selection"))
        assertTrue(
            preStart.substring(preStart.indexOf("val action = synchronized(stateLock)"))
                .contains("!playbackOwnerActive.get()"),
        )
    }

    @Test
    fun `playback service exclusively owns product next song prestart`() {
        val service = mainSource(
            "com/mardous/booming/playback/PlaybackService.kt",
        ).readText()
        val viewModel = mainSource(
            "com/mardous/booming/ui/screen/player/PlayerViewModel.kt",
        ).readText()

        assertTrue(service.contains("private fun maybePreStartNextSourceSeparation(reason: String)"))
        assertTrue(
            service.contains(
                "sourceSeparationForegroundWorkerCoordinator.preStartSong(",
            ),
        )
        assertFalse(viewModel.contains("maybePreStartNextSourceSeparation"))
        assertFalse(viewModel.contains("sourceSeparationPreStartJob"))
        assertFalse(viewModel.contains(".preStartSong("))
    }

    @Test
    fun `model families share source preflight and multistem contract memos`() {
        val module = mainSource("com/mardous/booming/MainModule.kt").readText()
        val runtime = mainSource(
            "com/mardous/booming/separation/SourceSeparationRuntimeFacade.kt",
        ).readText()
        val playbackResolver = mainSource(
            "com/mardous/booming/separation/SourceSeparationMultiStemPlaybackResolver.kt",
        ).readText()
        val productFacade = mainSource(
            "com/mardous/booming/separation/SourceSeparationMultiStemProductFacade.kt",
        ).readText()

        assertTrue(module.contains("single { SourceSeparationSourcePreflightMemo() }"))
        assertTrue(module.contains("single { SourceSeparationMultiStemContractMemo() }"))
        assertTrue(runtime.contains("sourcePreflightMemo.resolve(song, input"))
        assertTrue(playbackResolver.contains("sourcePreflightMemo.resolve(song, input"))
        assertTrue(productFacade.contains("sourcePreflightMemo.resolve(song, input"))
        assertTrue(playbackResolver.contains("contractMemo.resolve(installed)"))
        assertTrue(productFacade.contains("contractMemo.resolve("))
        assertEquals(3, Regex("sourcePreflightMemo = get\\(\\)").findAll(module).count())
        assertEquals(2, Regex("contractMemo = get\\(\\)").findAll(module).count())
    }

    @Test
    fun `completed current cache cannot preempt next song prestart`() {
        val coordinator = mainSource(
            "com/mardous/booming/ui/screen/player/SourceSeparationForegroundWorkerCoordinator.kt",
        ).readText()
        val admission = coordinator.substring(
            coordinator.indexOf("fun requestPlaybackDemandSong("),
            coordinator.indexOf("private fun requestFullSong("),
        )

        val asyncStart = admission.indexOf("workerScope.launch")
        val completedCheck = admission.indexOf(
            "SourceSeparationModelAwareCacheStatus.Completed",
        )
        val completedShortCircuit = admission.indexOf("if (hasCompletedCache)")
        val requestStart = admission.indexOf(
            "requestFullSong(song, SourceSeparationPendingStartReason.PlaybackDemand)",
        )
        assertTrue(asyncStart >= 0)
        assertTrue(completedCheck > asyncStart)
        assertTrue(completedShortCircuit > completedCheck)
        assertTrue(requestStart > completedShortCircuit)
    }

    @Test
    fun `playback demand preserves a manual pause before and after admission`() {
        val coordinator = mainSource(
            "com/mardous/booming/ui/screen/player/SourceSeparationForegroundWorkerCoordinator.kt",
        ).readText()
        val admission = coordinator.substring(
            coordinator.indexOf("fun requestPlaybackDemandSong("),
            coordinator.indexOf("private fun requestFullSong("),
        )

        assertEquals(
            2,
            Regex("autoStartSuppressedSongId == song\\.id").findAll(admission).count(),
        )
    }

    @Test
    fun `foreground rejection terminates admitted cache runs before releasing ownership`() {
        val environment = mainSource(
            "com/mardous/booming/separation/process/SourceSeparationRemoteExecutionEnvironment.kt",
        ).readText()
        val mdxService = mainSource(
            "com/mardous/booming/separation/process/ipc/SourceSeparationExecutionService.kt",
        ).readText()
        val multiStemService = mainSource(
            "com/mardous/booming/separation/process/ipc/SourceSeparationMultiStemExecutionService.kt",
        ).readText()

        assertTrue(environment.contains("fun rejectBeforeExecution(error: Throwable)"))
        assertTrue(environment.contains("coordinator.pause(run, error.pauseReason)"))
        assertTrue(environment.contains("coordinator.fail(run, error)"))
        assertTrue(mdxService.contains("admittedExecution?.rejectBeforeExecution(error)"))
        assertTrue(mdxService.contains("foregroundController.attachOrThrow("))
        assertTrue(multiStemService.contains("foregroundController.attachOrThrow("))
        assertTrue(multiStemService.contains("SourceSeparationMultiStemIpcStatus.Deferred"))
    }

    @Test
    fun `first per-song output activation publishes its explicit mix before admission`() {
        val viewModel = mainSource(
            "com/mardous/booming/ui/screen/player/PlayerViewModel.kt",
        ).readText()
        val activation = viewModel.substring(
            viewModel.indexOf("fun setSourceSeparationPlaybackEnabled("),
            viewModel.indexOf("fun openSourceSeparationModelManagement("),
        )

        val pendingWrite = activation.indexOf(
            "writeTemporaryPerSongSourceSeparationBlend(song, normalizedBlend)",
        )
        val processingIntent = activation.indexOf("publishSourceSeparationProcessingIntent(")
        assertTrue(pendingWrite >= 0)
        assertTrue(processingIntent > pendingWrite)
    }

    @Test
    fun `current song refresh does not pause a different next song prestart`() {
        val coordinator = mainSource(
            "com/mardous/booming/ui/screen/player/SourceSeparationForegroundWorkerCoordinator.kt",
        ).readText()
        val refresh = coordinator.substring(
            coordinator.indexOf("private fun pauseWorkerIfSongChanged("),
            coordinator.indexOf("private fun setPendingStart(", coordinator.indexOf("private fun pauseWorkerIfSongChanged(")),
        )

        assertTrue(refresh.contains("if (isRunningPreStartRequest())"))
        assertTrue(
            coordinator.contains(
                "private fun isRunningPreStartRequest(): Boolean =\n" +
                        "        activeWorkerRequest is SourceSeparationWorkerRequest.StartWindowPreStart",
            ),
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
        assertTrue(
            service.contains(
                "val recoveryReadyWindowCount = activeWindowWait?.requiredReadyWindowCount\n" +
                        "            ?: sourceSeparationPlaybackReadyWindowCount.coerceAtLeast(1)",
            ),
        )
        assertTrue(
            service.contains("requiredReadyWindowCount = recoveryReadyWindowCount"),
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
    fun `stem mixer exposes only ordered session entry points`() {
        val service = mainSource(
            "com/mardous/booming/playback/PlaybackService.kt",
        ).readText()
        val processor = mainSource(
            "com/mardous/booming/playback/processor/SourceSeparationMixAudioProcessor.kt",
        ).readText()

        assertEquals(1, Regex("""(?m)^    fun enable\(""").findAll(processor).count())
        assertEquals(1, Regex("""(?m)^    internal fun prepareInputs\(""").findAll(processor).count())
        assertEquals(1, Regex("""(?m)^    fun hotSwapToPcmInputs\(""").findAll(processor).count())
        assertTrue(Regex("""fun enable\(\r?\n        stemFiles: List<File>""").containsMatchIn(processor))
        assertTrue(
            Regex("""internal fun prepareInputs\(\r?\n        stemFiles: List<File>""")
                .containsMatchIn(processor),
        )
        assertTrue(
            Regex("""fun hotSwapToPcmInputs\(\r?\n        stemFiles: List<File>""")
                .containsMatchIn(processor),
        )
        assertFalse(processor.contains("legacyTwoStemBlendLaw"))
        assertFalse(processor.contains("useLegacyTwoStemBlendLaw"))
        assertFalse(processor.contains("internal val vocalsFile: File"))
        assertFalse(processor.contains("internal val instrumentalFile: File?"))
        assertTrue(
            Regex(
                """sourceSeparationMixProcessor\.enable\(\r?\n            stemFiles = session\.stemFiles""",
            ).containsMatchIn(service),
        )
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
        assertTrue(committedBlend.contains("observeCurrentSourceSeparationMixDemand()"))
        assertTrue(committedBlend.contains("if (persist && requiresData)"))
        assertTrue(committedBlend.contains("requiresSourceSeparationPlaybackData()"))
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
        assertTrue(service.contains("frameAvailability = if (isPartialCache)"))
        assertTrue(service.contains("sourceSeparationPlaybackFrameAvailability("))

        val engine = mainSource(
            "com/mardous/booming/playback/SourceSeparationStemPlaybackEngine.kt",
        ).readText()
        val processor = mainSource(
            "com/mardous/booming/playback/processor/SourceSeparationMixAudioProcessor.kt",
        ).readText()
        assertTrue(engine.contains("availability.readableEndFrame(workerNextFrame)"))
        assertTrue(service.contains("source = \"dataPlaneFrameAvailability\""))
        assertTrue(service.contains("playback.dataPlane.suspendForWindowWait"))
        assertTrue(processor.contains("playbackEngine?.currentUnavailableFrame()"))
        assertTrue(processor.contains("inputBuffer.position(inputBuffer.limit())"))
        assertFalse(processor.contains("writeUnmixedInput("))
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
    fun `remote services retain process ownership until execution really finishes`() {
        val mdxService = mainSource(
            "com/mardous/booming/separation/process/ipc/SourceSeparationExecutionService.kt",
        ).readText()
        val multiStemService = mainSource(
            "com/mardous/booming/separation/process/ipc/" +
                "SourceSeparationMultiStemExecutionService.kt",
        ).readText()

        val mdxDestroy = mdxService.substring(
            mdxService.indexOf("override fun onDestroy()"),
            mdxService.indexOf("private val binder", mdxService.indexOf("override fun onDestroy()")),
        )
        assertTrue(mdxDestroy.contains("activeRun?.requestCancel()"))
        assertTrue(mdxDestroy.contains("closeFinishedRunLocked()"))
        assertFalse(mdxDestroy.contains("activeRun?.close()"))
        assertTrue(mdxService.contains("active.markExecutionFinished()"))

        val multiStemDestroy = multiStemService.substring(
            multiStemService.indexOf("override fun onDestroy()"),
            multiStemService.indexOf(
                "private fun execute(run:",
                multiStemService.indexOf("override fun onDestroy()"),
            ),
        )
        assertTrue(multiStemDestroy.contains("active?.requestCancel()"))
        assertTrue(multiStemDestroy.contains("worker.shutdown()"))
        assertFalse(multiStemDestroy.contains("active?.close()"))
        assertFalse(multiStemDestroy.contains("shutdownNow()"))
        assertTrue(multiStemService.contains("var executionScheduled = false"))
        assertTrue(multiStemService.contains("if (executionScheduled) throw error"))
        assertTrue(multiStemService.contains("run.markExecutionFinished()"))
    }

    @Test
    fun `both remote families expose the same recoverable binder death`() {
        val multiStemHost = mainSource(
            "com/mardous/booming/separation/process/ipc/" +
                "BoundRemoteSourceSeparationMultiStemExecutionHost.kt",
        ).readText()
        val worker = mainSource(
            "com/mardous/booming/ui/screen/player/" +
                "SourceSeparationForegroundWorkerCoordinator.kt",
        ).readText()

        assertTrue(multiStemHost.contains("SourceSeparationRemoteHostDiedException("))
        assertTrue(multiStemHost.contains("error.asSourceSeparationRemoteHostDied()"))
        assertFalse(multiStemHost.contains(
            "failure.compareAndSet(null, DeadObjectException(\"Multi-stem service died.\"))",
        ))
        assertTrue(worker.contains("multistem recovery remote host died"))
        assertTrue(worker.contains("message = remoteProcessStoppedMessage()"))
    }

    @Test
    fun `multistem terminal observation has no full-run deadline`() {
        val host = mainSource(
            "com/mardous/booming/separation/process/ipc/" +
                "BoundRemoteSourceSeparationMultiStemExecutionHost.kt",
        ).readText()
        val durableTerminal = mainSource(
            "com/mardous/booming/separation/process/ipc/" +
                "SourceSeparationMultiStemDurableTerminal.kt",
        ).readText()

        assertTrue(host.contains("DEFAULT_CONNECTION_TIMEOUT_MS = 10_000L"))
        assertTrue(host.contains(
            "while (!terminal.await(TERMINAL_OBSERVATION_POLL_MS, TimeUnit.MILLISECONDS))",
        ))
        assertTrue(host.contains("service.activeRun()"))
        assertTrue(host.contains("terminalStateFor(descriptor)"))
        assertFalse(host.contains("30 * 60 * 1_000L"))
        assertFalse(host.contains("Timed out waiting for remote multi-stem execution."))
        assertTrue(durableTerminal.contains("request.runId != descriptor.runId"))
        assertTrue(durableTerminal.contains("request.identity == descriptor.cacheIdentity"))
        assertTrue(durableTerminal.contains("request.contract == descriptor.contract"))
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

    private fun File.readText(): String =
        java.nio.file.Files.readString(toPath()).replace("\r\n", "\n")

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
