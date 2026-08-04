package com.mardous.booming.separation.cache.v2

import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mardous.booming.separation.SourceSeparationModelAwareEngine
import com.mardous.booming.separation.SourceSeparationModelAwareEngineResult
import com.mardous.booming.separation.SourceSeparationModelAwareSongInput
import com.mardous.booming.separation.model.AndroidMdxRuntimePlatformProvider
import com.mardous.booming.separation.model.preset.SourceSeparationActivePresetState
import com.mardous.booming.separation.model.preset.SourceSeparationPresetRepository
import com.mardous.booming.separation.model.preset.SourceSeparationPresetSelectionScope
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.java.KoinJavaComponent.get
import java.io.File

@RunWith(AndroidJUnit4::class)
class SourceSeparationModelAwareCacheDeviceTest {

    @Test
    fun validateModelAwareCacheLifecycle() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val runId = arguments.requiredString(ARG_RUN_ID).requireSafeName()
        val report = baseReport(runId, "lifecycle")

        try {
            val expectedAbi = arguments.requiredString(ARG_PROCESS_ABI)
            val actualAbi = AndroidMdxRuntimePlatformProvider.current().runtimeAbi.androidName
            assertEquals(expectedAbi, actualAbi)
            report.put("processAbi", actualAbi)

            val stagingRoot = File(context.filesDir, "$STAGING_DIRECTORY/$runId").canonicalFile
            val source = arguments.requiredStagedFile(ARG_SOURCE_PATH, stagingRoot)
            val primaryModel = arguments.requiredStagedFile(ARG_PRIMARY_MODEL_PATH, stagingRoot)
            val primaryModelId = arguments.requiredString(ARG_PRIMARY_MODEL_ID)
            val secondaryModelId = arguments.getString(ARG_SECONDARY_MODEL_ID)
                ?.takeIf(String::isNotBlank)
            val secondaryModel = secondaryModelId?.let {
                arguments.requiredStagedFile(ARG_SECONDARY_MODEL_PATH, stagingRoot)
            }

            val presetRepository = get<SourceSeparationPresetRepository>(
                SourceSeparationPresetRepository::class.java,
            )
            val cacheRepository = get<SourceSeparationModelAwareCacheRepository>(
                SourceSeparationModelAwareCacheRepository::class.java,
            )
            val store = get<SourceSeparationCacheStore>(SourceSeparationCacheStore::class.java)
            val engine = get<SourceSeparationModelAwareEngine>(
                SourceSeparationModelAwareEngine::class.java,
            )
            val promoter = SourceSeparationCacheFlacPromoter(store, cacheRepository)

            assertTrue(presetRepository.installedModels().isEmpty())
            assertTrue(cacheRepository.entries().isEmpty())

            val runs = mutableListOf<ModelRun>()
            val primary = runModel(
                modelId = primaryModelId,
                modelFile = primaryModel,
                sourceFile = source,
                presetRepository = presetRepository,
                engine = engine,
            )
            runs += primary
            validateCompletedLifecycle(primary, store, cacheRepository, promoter)

            if (secondaryModelId != null && secondaryModel != null) {
                val secondary = runModel(
                    modelId = secondaryModelId,
                    modelFile = secondaryModel,
                    sourceFile = source,
                    presetRepository = presetRepository,
                    engine = engine,
                )
                runs += secondary
                assertNotEquals(primary.manifest.cacheKey, secondary.manifest.cacheKey)
                assertNotNull(cacheRepository.openCompletedCache(primary.manifest.cacheKey)?.also {
                    it.close()
                })
            }

            val entries = cacheRepository.entries().filter { it.songId == SONG_ID }
            assertEquals(runs.size, entries.size)
            assertEquals(runs.map { it.modelId }.toSet(), entries.map { it.modelId }.toSet())
            assertEquals(runs.size, presetRepository.installedModels().size)
            assertTrue(presetRepository.activeModel() is SourceSeparationActivePresetState.Reference)

            report.put("sourceBytes", source.length())
            report.put("cacheRoot", store.root().location.name)
            report.put("entryCount", entries.size)
            report.put("installedModelCount", presetRepository.installedModels().size)
            report.put("runs", JSONArray(runs.map(ModelRun::toJson)))
            report.put("status", "passed")
        } catch (error: Throwable) {
            report.put("status", "failed")
            report.put("error", "${error::class.java.name}: ${error.message}")
            throw error
        } finally {
            writeReport(runId, "lifecycle", report)
        }
    }

    @Test
    fun validateCacheClearRecovery() {
        val arguments = InstrumentationRegistry.getArguments()
        val runId = arguments.requiredString(ARG_RUN_ID).requireSafeName()
        val report = baseReport(runId, "clear-cache")

        try {
            val presetRepository = get<SourceSeparationPresetRepository>(
                SourceSeparationPresetRepository::class.java,
            )
            val cacheRepository = get<SourceSeparationModelAwareCacheRepository>(
                SourceSeparationModelAwareCacheRepository::class.java,
            )
            val store = get<SourceSeparationCacheStore>(SourceSeparationCacheStore::class.java)
            val installedBefore = presetRepository.installedModels()
            val activeBefore = presetRepository.activeModel()
            val entriesBefore = cacheRepository.entries()

            assertTrue(installedBefore.isNotEmpty())
            assertTrue(activeBefore is SourceSeparationActivePresetState.Reference)
            assertTrue(entriesBefore.isNotEmpty())
            assertTrue(store.root().directory.deleteRecursively())

            store.ensureLayout()
            store.recover()

            assertTrue(cacheRepository.entries().isEmpty())
            assertEquals(
                installedBefore.map { it.sha256 }.toSet(),
                presetRepository.installedModels().map { it.sha256 }.toSet(),
            )
            assertEquals(activeBefore, presetRepository.activeModel())
            assertTrue(presetRepository.installedModels().all { it.file.isFile })

            report.put("cacheRoot", store.root().location.name)
            report.put("entriesBefore", entriesBefore.size)
            report.put("entriesAfter", cacheRepository.entries().size)
            report.put("installedModelsBefore", installedBefore.size)
            report.put("installedModelsAfter", presetRepository.installedModels().size)
            report.put("activeModelRetained", true)
            report.put("status", "passed")
        } catch (error: Throwable) {
            report.put("status", "failed")
            report.put("error", "${error::class.java.name}: ${error.message}")
            throw error
        } finally {
            writeReport(runId, "clear-cache", report)
        }
    }

    private fun runModel(
        modelId: String,
        modelFile: File,
        sourceFile: File,
        presetRepository: SourceSeparationPresetRepository,
        engine: SourceSeparationModelAwareEngine,
    ): ModelRun {
        val installed = modelFile.inputStream().use { input ->
            presetRepository.installOfficial(modelId, input)
        }
        presetRepository.activate(
            sha256 = installed.sha256,
            platform = AndroidMdxRuntimePlatformProvider.current(),
            scope = SourceSeparationPresetSelectionScope.InternalValidation,
            experimentalConfirmed = true,
        )
        val sourceUri = Uri.fromFile(sourceFile).toString()
        val input = SourceSeparationModelAwareSongInput(
            sourceUri = sourceUri,
            displayName = sourceFile.name,
            song = SourceSeparationCacheSongLocator(
                songId = SONG_ID,
                mediaUri = sourceUri,
                filePath = sourceFile.absolutePath,
                title = "Phase 5 validation",
                artist = "Booming SS",
                album = "Device validation",
            ),
            sourceDiagnostics = SourceSeparationCacheSourceDiagnostics(
                fileSize = sourceFile.length(),
                rawDateModified = sourceFile.lastModified(),
                durationMs = SOURCE_DURATION_MS,
            ),
        )
        val startedAtMs = SystemClock.elapsedRealtime()
        val result = engine.separate(input)
        val elapsedMs = SystemClock.elapsedRealtime() - startedAtMs
        val completed = result as? SourceSeparationModelAwareEngineResult.Completed
            ?: error("Expected a newly completed model-aware run, got ${result::class.java.simpleName}.")
        assertEquals(SourceSeparationCacheManifestState.Completed, completed.manifest.state)
        assertEquals(modelId, completed.manifest.identity.modelId)
        assertTrue(completed.manifest.output?.windowCount ?: 0 > 0)
        assertTrue(completed.manifest.runtimeRecords.isNotEmpty())
        return ModelRun(
            modelId = modelId,
            manifest = completed.manifest,
            preflightElapsedMs = completed.preflightElapsedMs,
            totalElapsedMs = elapsedMs,
        )
    }

    private fun validateCompletedLifecycle(
        run: ModelRun,
        store: SourceSeparationCacheStore,
        repository: SourceSeparationModelAwareCacheRepository,
        promoter: SourceSeparationCacheFlacPromoter,
    ) {
        val manifest = run.manifest
        assertTrue(repository.writeBlend(manifest.identity, TEST_BLEND))
        assertEquals(TEST_BLEND, repository.readBlend(manifest.identity) ?: -1f, 0.0001f)

        val wavPlayback = requireNotNull(repository.openCompletedCache(manifest.cacheKey))
        assertTrue(wavPlayback.vocalsFile.extension.equals("wav", ignoreCase = true))
        assertEquals(SourceSeparationCacheMutationResult.Busy, repository.delete(manifest.cacheKey))
        wavPlayback.close()

        val promotion = promoter.promote(manifest.cacheKey)
        assertTrue(promotion is SourceSeparationCacheFlacPromotionResult.Completed)
        val promoted = requireNotNull(store.readManifest(manifest.cacheKey))
        assertTrue(promoted.output?.stems?.all { it.promotionValidated } == true)
        val flacPlayback = requireNotNull(repository.openCompletedCache(manifest.cacheKey))
        assertTrue(flacPlayback.vocalsFile.extension.equals("flac", ignoreCase = true))
        flacPlayback.close()

        assertTrue(store.readPlaybackSettings(promoted)?.matches(promoted) == true)
    }

    private fun baseReport(runId: String, stage: String) = JSONObject()
        .put("runId", runId)
        .put("stage", stage)
        .put("appPackage", InstrumentationRegistry.getInstrumentation().targetContext.packageName)
        .put("sdk", android.os.Build.VERSION.SDK_INT)
        .put("device", android.os.Build.MODEL)

    private fun writeReport(runId: String, stage: String, report: JSONObject) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, REPORT_DIRECTORY)
        check(root.isDirectory || root.mkdirs()) { "Could not create the Phase 5 report directory." }
        File(root, "$runId-$stage.json").writeText(report.toString(2))
    }

    private fun android.os.Bundle.requiredString(key: String): String =
        requireNotNull(getString(key)?.takeIf(String::isNotBlank)) {
            "Missing instrumentation argument: $key"
        }

    private fun android.os.Bundle.requiredStagedFile(key: String, root: File): File {
        val file = File(requiredString(key)).canonicalFile
        require(file.path.startsWith(root.path + File.separator)) {
            "Staged file escapes the validation root: $key"
        }
        require(file.isFile && file.canRead()) { "Staged file is not readable: $key" }
        return file
    }

    private fun String.requireSafeName(): String = also {
        require(SAFE_NAME.matches(it)) { "Unsafe Phase 5 validation run ID." }
    }

    private data class ModelRun(
        val modelId: String,
        val manifest: SourceSeparationCacheManifest,
        val preflightElapsedMs: Long,
        val totalElapsedMs: Long,
    ) {
        fun toJson() = JSONObject()
            .put("modelId", modelId)
            .put("cacheKey", manifest.cacheKey)
            .put("artifactSha256", manifest.identity.artifactSha256)
            .put("contractId", manifest.identity.contractId)
            .put("profileRevisionId", manifest.identity.profileRevisionId)
            .put("preflightElapsedMs", preflightElapsedMs)
            .put("totalElapsedMs", totalElapsedMs)
            .put("windowCount", manifest.output?.windowCount)
            .put("outputFrames", manifest.output?.outputFrameCount)
            .put("runtimeRecords", JSONArray(manifest.runtimeRecords.map { record ->
                JSONObject()
                    .put("backend", record.backend)
                    .put("runtimeProfileId", record.runtimeProfileId)
                    .put("precision", record.precision)
                    .put("elapsedMs", record.elapsedMs)
                    .put("fallbackStage", record.fallbackStage)
                    .put("fallbackReason", record.fallbackReason)
            }))
    }

    private companion object {
        const val ARG_RUN_ID = "runId"
        const val ARG_PROCESS_ABI = "processAbi"
        const val ARG_PRIMARY_MODEL_ID = "primaryModelId"
        const val ARG_PRIMARY_MODEL_PATH = "primaryModelPath"
        const val ARG_SECONDARY_MODEL_ID = "secondaryModelId"
        const val ARG_SECONDARY_MODEL_PATH = "secondaryModelPath"
        const val ARG_SOURCE_PATH = "sourcePath"
        const val STAGING_DIRECTORY = "phase5-validation-staging"
        const val REPORT_DIRECTORY = "phase5-validation-reports"
        const val SONG_ID = 5_001L
        const val SOURCE_DURATION_MS = 12_000L
        const val TEST_BLEND = 0.23f
        val SAFE_NAME = Regex("^[A-Za-z0-9._-]{1,120}$")
    }
}
