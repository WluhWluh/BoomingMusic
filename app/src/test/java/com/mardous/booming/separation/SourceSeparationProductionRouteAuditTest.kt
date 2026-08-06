package com.mardous.booming.separation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SourceSeparationProductionRouteAuditTest {
    @Test
    fun `normal application routes do not reference legacy or ORT runtime types`() {
        val prohibited = listOf(
            "SourceSeparationEngine",
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
            "private fun setSourceSeparationBlend(",
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
