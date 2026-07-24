package com.mardous.booming.separation

import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorType
import com.mardous.booming.separation.model.AndroidMdxRuntimePlatformProvider
import com.mardous.booming.separation.model.contract.CatalogActivationPolicy
import com.mardous.booming.separation.model.contract.CatalogReleaseMaturity
import com.mardous.booming.separation.model.contract.CatalogSupportLevel
import com.mardous.booming.separation.model.contract.SourceSeparationModelMetadata
import com.mardous.booming.separation.model.preset.SourceSeparationActivePresetState
import com.mardous.booming.separation.model.preset.SourceSeparationPresetActivationException
import com.mardous.booming.separation.model.preset.SourceSeparationPresetActivationResolver
import com.mardous.booming.separation.model.preset.SourceSeparationPresetDownloader
import com.mardous.booming.separation.model.preset.SourceSeparationPresetRepository
import com.mardous.booming.separation.model.preset.SourceSeparationPresetSelectionBlockReason
import com.mardous.booming.separation.model.preset.SourceSeparationPresetSelectionScope
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.java.KoinJavaComponent.get
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Audits one published, contract-free candidate without granting activation. */
@RunWith(AndroidJUnit4::class)
class SourceSeparationPhase7CandidateDeviceTest {

    @Test
    fun validateDownloadOnlyCandidate() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val runId = arguments.requiredString(ARG_RUN_ID).requireSafeName()
        val modelId = arguments.requiredString(ARG_MODEL_ID)
        val expectedInputShape = arguments.requiredShape(ARG_INPUT_SHAPE)
        val expectedOutputShape = arguments.requiredShape(ARG_OUTPUT_SHAPE)
        val report = JSONObject()
            .put("schemaVersion", "phase7-candidate-audit-v1")
            .put("status", "not-tested")
            .put("runId", runId)
            .put("identity", JSONObject()
                .put("appCommit", arguments.requiredString(ARG_APP_COMMIT))
                .put("appApkSha256", arguments.requiredString(ARG_APP_APK_SHA256))
                .put("testApkSha256", arguments.requiredString(ARG_TEST_APK_SHA256))
                .put("catalogSha256", arguments.requiredString(ARG_CATALOG_SHA256))
                .put("catalogSourceRevision", arguments.requiredString(ARG_CATALOG_SOURCE_REVISION))
                .put("modelReleaseTag", arguments.requiredString(ARG_RELEASE_TAG))
                .put("litertVersion", arguments.requiredString(ARG_LITERT_VERSION))
            )
            .put("device", JSONObject()
                .put("serial", arguments.requiredString(ARG_SERIAL))
                .put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL)
                .put("androidApi", Build.VERSION.SDK_INT)
                .put("buildFingerprint", Build.FINGERPRINT)
                .put("processAbi", arguments.requiredString(ARG_PROCESS_ABI))
                .put("bitness", if (Process.is64Bit()) 64 else 32)
            )
            .put("candidate", JSONObject()
                .put("modelId", modelId)
                .put("artifactFileName", arguments.requiredString(ARG_ARTIFACT_FILE_NAME))
                .put("artifactSha256", arguments.requiredString(ARG_ARTIFACT_SHA256))
                .put("artifactBytes", arguments.requiredLong(ARG_ARTIFACT_BYTES))
                .put("releaseUrl", arguments.requiredString(ARG_RELEASE_URL))
                .put("activationPolicy", arguments.requiredString(ARG_ACTIVATION_POLICY))
                .put("reviewedContract", false)
                .put("reviewedSidecar", false)
            )

        val repository = get<SourceSeparationPresetRepository>(
            SourceSeparationPresetRepository::class.java,
        )
        var installedSha256: String? = null
        var failure: Throwable? = null
        try {
            val catalogBytes = context.assets.open(CATALOG_ASSET).use { input ->
                input.readBytes()
            }
            assertEquals(arguments.requiredString(ARG_CATALOG_SHA256), catalogBytes.sha256())
            val catalog = SourceSeparationModelMetadata.loadBundledCatalog(context)
            val entry = catalog.entries.single { it.modelId == modelId }
            val artifact = catalog.artifacts.single { it.artifactId == entry.artifactId }
            val tflite = requireNotNull(artifact.tflite)
            val releaseAsset = requireNotNull(tflite.releaseAsset)

            assertEquals(CatalogSupportLevel.DownloadOnly, entry.supportLevel)
            assertEquals(CatalogReleaseMaturity.Candidate, entry.releaseMaturity)
            assertFalse(entry.downloadActivatesModel)
            assertTrue(
                entry.activationPolicy == CatalogActivationPolicy.BlockedUntilReviewedContract ||
                    entry.activationPolicy == CatalogActivationPolicy.DownloadOnlyGenericStem,
            )
            assertEquals(arguments.requiredString(ARG_ACTIVATION_POLICY), entry.activationPolicy.wireName)
            assertEquals(null, entry.contractId)
            assertTrue(catalog.contracts.none { it.modelId == modelId })
            assertEquals(arguments.requiredString(ARG_ARTIFACT_FILE_NAME), tflite.fileName)
            assertEquals(arguments.requiredString(ARG_ARTIFACT_SHA256), tflite.sha256)
            assertEquals(arguments.requiredLong(ARG_ARTIFACT_BYTES), tflite.byteSize)
            assertEquals(arguments.requiredString(ARG_RELEASE_TAG), releaseAsset.tag)
            assertEquals(arguments.requiredString(ARG_RELEASE_URL), releaseAsset.url)

            assertTrue(repository.installedModels().isEmpty())
            assertTrue(repository.activeModel() is SourceSeparationActivePresetState.None)
            val platform = AndroidMdxRuntimePlatformProvider.current()
            assertEquals(arguments.requiredString(ARG_PROCESS_ABI), platform.runtimeAbi.androidName)
            val eligibility = SourceSeparationPresetActivationResolver.resolve(
                catalog = catalog,
                modelId = modelId,
                platform = platform,
                scope = SourceSeparationPresetSelectionScope.User,
            )
            assertFalse(eligibility.allowed)
            assertEquals(SourceSeparationPresetSelectionBlockReason.DownloadOnly, eligibility.blockReason)

            var lastSourceUrl = releaseAsset.url
            var usedMirror = false
            val downloadStartedAt = SystemClock.elapsedRealtime()
            val installed = get<SourceSeparationPresetDownloader>(
                SourceSeparationPresetDownloader::class.java,
            ).download(modelId) { progress ->
                lastSourceUrl = progress.sourceUrl
                usedMirror = progress.usingMirror
            }
            val downloadElapsedMs = SystemClock.elapsedRealtime() - downloadStartedAt
            installedSha256 = installed.sha256
            assertEquals(modelId, installed.modelId)
            assertEquals(tflite.fileName, installed.file.name)
            assertEquals(tflite.byteSize, installed.file.length())
            assertEquals(tflite.sha256, installed.sha256)
            assertEquals(tflite.sha256, installed.file.sha256())
            assertTrue(repository.activeModel() is SourceSeparationActivePresetState.None)
            report.put("download", JSONObject()
                .put("elapsedMs", downloadElapsedMs)
                .put("sourceUrl", lastSourceUrl)
                .put("usingMirror", usedMirror)
                .put("bytes", installed.file.length())
                .put("sha256", installed.file.sha256())
            )

            val structure = inspectStructure(
                modelFile = installed.file,
                inputName = arguments.requiredString(ARG_INPUT_NAME),
                outputName = arguments.requiredString(ARG_OUTPUT_NAME),
                expectedInputShape = expectedInputShape,
                expectedOutputShape = expectedOutputShape,
            )
            report.put("structure", structure)

            val activationFailure = try {
                repository.activate(
                    sha256 = installed.sha256,
                    platform = platform,
                    scope = SourceSeparationPresetSelectionScope.User,
                )
                null
            } catch (error: SourceSeparationPresetActivationException) {
                error
            }
            requireNotNull(activationFailure) {
                "Download-only candidate unexpectedly became active."
            }
            assertEquals(
                SourceSeparationPresetSelectionBlockReason.DownloadOnly,
                activationFailure.eligibility.blockReason,
            )
            assertTrue(repository.activeModel() is SourceSeparationActivePresetState.None)
            report.put("activation", JSONObject()
                .put("allowed", false)
                .put("blockReason", activationFailure.eligibility.blockReason?.name)
            )
        } catch (error: Throwable) {
            failure = error
        } finally {
            installedSha256?.let { sha256 ->
                try {
                    val deleted = repository.delete(sha256)
                    report.put("cleanup", JSONObject()
                        .put("deleted", deleted)
                        .put("installedCountAfter", repository.installedModels().size)
                    )
                    assertTrue(deleted)
                    assertTrue(repository.installedModels().isEmpty())
                } catch (cleanupError: Throwable) {
                    failure?.addSuppressed(cleanupError) ?: run { failure = cleanupError }
                    report.put("cleanup", JSONObject()
                        .put("deleted", false)
                        .put("error", "${cleanupError::class.java.name}: ${cleanupError.message}")
                    )
                }
            }
            failure?.let { error ->
                report.put("status", "failed")
                report.put("error", "${error::class.java.name}: ${error.message}")
            } ?: report.put("status", "passed")
            writeReport(context, runId, report)
        }
        failure?.let { throw it }
    }

    private fun inspectStructure(
        modelFile: File,
        inputName: String,
        outputName: String,
        expectedInputShape: List<Int>,
        expectedOutputShape: List<Int>,
    ): JSONObject {
        RandomAccessFile(modelFile, "r").use { file ->
            file.seek(4)
            val identifier = ByteArray(4)
            file.readFully(identifier)
            assertEquals("TFL3", String(identifier, StandardCharsets.US_ASCII))
        }

        val startedAt = SystemClock.elapsedRealtime()
        val environment = Environment.create()
        var compiledModel: CompiledModel? = null
        try {
            assertTrue(environment.getAvailableAccelerators().contains(Accelerator.CPU))
            compiledModel = CompiledModel.create(
                modelFile.absolutePath,
                CompiledModel.Options(Accelerator.CPU).apply {
                    cpuOptions = CompiledModel.CpuOptions(1, null, null)
                },
                environment,
            )
            val inputType = compiledModel.getInputTensorType(inputName)
            val outputType = compiledModel.getOutputTensorType(outputName)
            validateTensor(inputType, expectedInputShape, "input")
            validateTensor(outputType, expectedOutputShape, "output")
            val inputBufferBytes = compiledModel.getInputBufferRequirements(inputName).bufferSize
            val outputBufferBytes = compiledModel.getOutputBufferRequirements(outputName).bufferSize
            assertTrue(inputBufferBytes >= expectedInputShape.elementCount() * Float.SIZE_BYTES)
            assertTrue(outputBufferBytes >= expectedOutputShape.elementCount() * Float.SIZE_BYTES)
            return JSONObject()
                .put("compiled", true)
                .put("compileAndInspectMs", SystemClock.elapsedRealtime() - startedAt)
                .put("inputName", inputName)
                .put("inputShapeNhwc", JSONArray(expectedInputShape))
                .put("inputBufferBytes", inputBufferBytes)
                .put("outputName", outputName)
                .put("outputShapeNhwc", JSONArray(expectedOutputShape))
                .put("outputBufferBytes", outputBufferBytes)
                .put("dtype", "float32")
                .put("buffersAllocated", false)
        } finally {
            compiledModel?.close()
            environment.close()
        }
    }

    private fun validateTensor(type: TensorType, expectedShape: List<Int>, role: String) {
        assertEquals("Unexpected $role dtype.", TensorType.ElementType.FLOAT, type.elementType)
        val layout = requireNotNull(type.layout) { "$role tensor has no static layout." }
        assertEquals("Unexpected $role shape.", expectedShape, layout.dimensions)
        assertFalse("$role tensor declares explicit strides.", layout.hasStrides)
    }

    private fun List<Int>.elementCount(): Int = fold(1, Math::multiplyExact)

    private fun writeReport(context: android.content.Context, runId: String, report: JSONObject) {
        val root = File(context.filesDir, REPORT_DIRECTORY)
        check(root.isDirectory || root.mkdirs()) { "Could not create candidate report directory." }
        File(root, "$runId-candidate.json").writeText(report.toString(2))
    }

    private fun android.os.Bundle.requiredString(key: String): String =
        requireNotNull(getString(key)?.takeIf(String::isNotBlank)) {
            "Missing instrumentation argument: $key"
        }

    private fun android.os.Bundle.requiredLong(key: String): Long =
        requiredString(key).toLongOrNull() ?: error("Invalid long instrumentation argument: $key")

    private fun android.os.Bundle.requiredShape(key: String): List<Int> =
        requiredString(key).split(',').map { dimension ->
            dimension.toIntOrNull()?.takeIf { it > 0 }
                ?: error("Invalid shape instrumentation argument: $key")
        }.also { shape ->
            require(shape.size == 4) { "$key must contain four NHWC dimensions." }
        }

    private fun String.requireSafeName(): String = also {
        require(SAFE_NAME.matches(it)) { "Unsafe Phase 7 candidate run ID." }
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHexString()
    }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .toHexString()

    private fun ByteArray.toHexString(): String = joinToString("") { byte ->
        "%02x".format(byte)
    }

    private val CatalogActivationPolicy.wireName: String
        get() = when (this) {
            CatalogActivationPolicy.BlockedUntilReviewedContract ->
                "blocked-until-reviewed-contract"
            CatalogActivationPolicy.DownloadOnlyGenericStem -> "download-only-generic-stem"
            else -> error("Candidate audit received selectable activation policy: $this")
        }

    private companion object {
        const val ARG_RUN_ID = "runId"
        const val ARG_SERIAL = "serial"
        const val ARG_PROCESS_ABI = "processAbi"
        const val ARG_MODEL_ID = "modelId"
        const val ARG_ARTIFACT_FILE_NAME = "artifactFileName"
        const val ARG_ARTIFACT_SHA256 = "artifactSha256"
        const val ARG_ARTIFACT_BYTES = "artifactBytes"
        const val ARG_RELEASE_TAG = "releaseTag"
        const val ARG_RELEASE_URL = "releaseUrl"
        const val ARG_ACTIVATION_POLICY = "activationPolicy"
        const val ARG_INPUT_NAME = "inputName"
        const val ARG_OUTPUT_NAME = "outputName"
        const val ARG_INPUT_SHAPE = "inputShape"
        const val ARG_OUTPUT_SHAPE = "outputShape"
        const val ARG_APP_COMMIT = "appCommit"
        const val ARG_APP_APK_SHA256 = "appApkSha256"
        const val ARG_TEST_APK_SHA256 = "testApkSha256"
        const val ARG_CATALOG_SHA256 = "catalogSha256"
        const val ARG_CATALOG_SOURCE_REVISION = "catalogSourceRevision"
        const val ARG_LITERT_VERSION = "litertVersion"
        const val CATALOG_ASSET = "source-separation/model-catalog-v2.json"
        const val REPORT_DIRECTORY = "phase7-validation-reports"
        const val DEFAULT_BUFFER_SIZE = 64 * 1024
        val SAFE_NAME = Regex("^[A-Za-z0-9._-]{1,120}$")
    }
}
