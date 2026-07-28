package com.mardous.booming.separation

import org.junit.Assert.assertEquals
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
    fun `generic inference boundaries have no implicit ORT provider`() {
        val runtime = mainSource(
            "com/mardous/booming/separation/model/MdxInferenceRuntime.kt",
        ).readText()
        val separator = mainSource(
            "com/mardous/booming/separation/model/MdxRangeSeparator.kt",
        ).readText()
        val legacyEngine = mainSource(
            "com/mardous/booming/separation/SourceSeparationEngine.kt",
        ).readText()

        assertFalse(runtime.contains("DefaultMdxInferenceSessionProvider"))
        assertFalse(runtime.contains("MdxInferenceSessionFactory ="))
        assertFalse(separator.contains("sessionProvider: MdxInferenceSessionProvider ="))
        assertFalse(legacyEngine.contains("sessionProvider: MdxInferenceSessionProvider ="))
    }

    @Test
    fun `ONNX Runtime imports are confined to the named oracle`() {
        val ortSources = mainKotlinRoot.walkTopDown()
            .filter(File::isFile)
            .filter { it.extension == "kt" && "ai.onnxruntime" in it.readText() }
            .map { it.relativeTo(mainKotlinRoot).invariantSeparatorsPath }
            .toList()

        assertEquals(
            listOf("com/mardous/booming/separation/model/SourceSeparationOrtOracle.kt"),
            ortSources,
        )

        val oracleReferences = mainKotlinRoot.walkTopDown()
            .filter(File::isFile)
            .filter { it.extension == "kt" && "SourceSeparationOrtOracle" in it.readText() }
            .map { it.relativeTo(mainKotlinRoot).invariantSeparatorsPath }
            .toList()
        assertEquals(ortSources, oracleReferences)
    }

    private fun mainSource(relativePath: String): File =
        File(mainKotlinRoot, relativePath).also { source ->
            require(source.isFile) { "Missing production source: $relativePath" }
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
