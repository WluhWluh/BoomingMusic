package com.mardous.booming.separation

import android.os.Build
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import com.mardous.booming.separation.model.AndroidMdxRuntimePlatformProvider
import com.mardous.booming.separation.model.MdxRuntimeAbi
import com.mardous.booming.separation.model.contract.SourceSeparationModelContractValidator
import com.mardous.booming.separation.model.contract.SourceSeparationModelMetadata
import com.mardous.booming.separation.model.preset.SourceSeparationActivePresetState
import com.mardous.booming.separation.model.preset.SourceSeparationPresetDownloader
import com.mardous.booming.separation.model.preset.SourceSeparationPresetRepository
import com.mardous.booming.separation.model.preset.SourceSeparationPresetSelectionScope
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.java.KoinJavaComponent.get
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Freezes the device-side identity portion of a Phase 7 evidence row. */
@RunWith(AndroidJUnit4::class)
class SourceSeparationPhase7DeviceTest {

    @Test
    fun validateDeviceEvidenceIdentity() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val runId = arguments.requiredString(ARG_RUN_ID).requireSafeName()
        val report = baseReport(context, runId, arguments)

        try {
            val expectedAbi = arguments.requiredString(ARG_PROCESS_ABI)
            val platform = AndroidMdxRuntimePlatformProvider.current()
            assertEquals(expectedAbi, platform.runtimeAbi.androidName)
            assertEquals(expectedAbi, runtimeAbiFromNativeDirectory(context).androidName)
            assertEquals(expectedAbi.is64BitAbi(), Process.is64Bit())

            val catalogBytes = context.assets.open(CATALOG_ASSET).use { it.readBytes() }
            val catalogSha256 = catalogBytes.sha256()
            assertEquals(arguments.requiredString(ARG_CATALOG_SHA256), catalogSha256)
            val catalog = SourceSeparationModelMetadata.loadBundledCatalog(context)
            val modelId = arguments.requiredString(ARG_MODEL_ID)
            val entry = catalog.entries.single { it.modelId == modelId }
            val artifact = catalog.artifacts.single { it.artifactId == entry.artifactId }
            val tflite = requireNotNull(artifact.tflite)
            assertEquals(arguments.requiredString(ARG_ARTIFACT_SHA256), tflite.sha256)
            assertEquals(arguments.requiredString(ARG_ARTIFACT_FILE_NAME), tflite.fileName)
            assertEquals(arguments.requiredString(ARG_CONTRACT_ID), entry.contractId)
            assertEquals(
                arguments.requiredInt(ARG_CONTRACT_SCHEMA_VERSION),
                SourceSeparationModelContractValidator.CONTRACT_SCHEMA_VERSION,
            )
            SourceSeparationModelContractValidator.resolveReviewedContract(catalog, modelId)

            val runtimeLibrary = nativeLibraryEvidence(context, platform.runtimeAbi)
            val expectedRuntimeSha256 = arguments.getString(ARG_LITERT_SHA256)
            if (!expectedRuntimeSha256.isNullOrBlank()) {
                assertEquals(expectedRuntimeSha256, runtimeLibrary.sha256)
            }

            report.put("status", "passed")
            report.put("runtime", JSONObject()
                .put("libraryPath", runtimeLibrary.path)
                .put("libraryBytes", runtimeLibrary.bytes)
                .put("librarySha256", runtimeLibrary.sha256)
                .put("nativeLibraryDir", context.applicationInfo.nativeLibraryDir)
            )
            report.put("device", report.getJSONObject("device")
                .put("supportedAbis", JSONArray(Build.SUPPORTED_ABIS.toList()))
                .put("supported32BitAbis", JSONArray(Build.SUPPORTED_32_BIT_ABIS.toList()))
                .put("supported64BitAbis", JSONArray(Build.SUPPORTED_64_BIT_ABIS.toList()))
                .put("processIs64Bit", Process.is64Bit())
                .put("nativePssBytes", Debug.getNativeHeapAllocatedSize())
            )
        } catch (error: Throwable) {
            report.put("status", "failed")
            report.put("error", "${error::class.java.name}: ${error.message}")
            throw error
        } finally {
            writeReport(context, runId, "identity", report)
        }
    }

    @Test
    fun validatePinnedAcquisition() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val runId = arguments.requiredString(ARG_RUN_ID).requireSafeName()
        val report = baseReport(context, runId, arguments)
        try {
            assertTrue(
                "Pinned acquisition requires the clean-install scenario.",
                arguments.getBoolean(ARG_CLEAN_INSTALL, true),
            )
            val repository = get<SourceSeparationPresetRepository>(
                SourceSeparationPresetRepository::class.java,
            )
            val downloader = get<SourceSeparationPresetDownloader>(
                SourceSeparationPresetDownloader::class.java,
            )
            assertTrue(repository.installedModels().isEmpty())
            assertTrue(repository.activeModel() is SourceSeparationActivePresetState.None)

            val modelId = arguments.requiredString(ARG_MODEL_ID)
            val downloadStartedAt = SystemClock.elapsedRealtime()
            val installed = downloader.download(modelId)
            val downloadElapsedMs = SystemClock.elapsedRealtime() - downloadStartedAt
            val expectedSha256 = arguments.requiredString(ARG_ARTIFACT_SHA256)
            val expectedFileName = arguments.requiredString(ARG_ARTIFACT_FILE_NAME)
            assertEquals(expectedSha256, installed.sha256)
            assertEquals(expectedFileName, installed.file.name)
            assertEquals(expectedSha256, installed.file.sha256())
            assertEquals(SourceSeparationActivePresetState.None, repository.activeModel())

            val selected = repository.activate(
                sha256 = installed.sha256,
                platform = AndroidMdxRuntimePlatformProvider.current(),
                scope = SourceSeparationPresetSelectionScope.InternalValidation,
                experimentalConfirmed = true,
            )
            assertEquals(modelId, selected.modelId)
            assertEquals(expectedSha256, selected.artifactSha256)
            val active = repository.activeModel()
            assertTrue(active is SourceSeparationActivePresetState.Reference)
            assertEquals(expectedSha256, (active as SourceSeparationActivePresetState.Reference)
                .reference.artifactSha256)
            assertTrue(
                "The active model reference was not durably committed.",
                get<SharedPreferences>(SharedPreferences::class.java).edit().commit(),
            )

            report.put("status", "passed")
            report.put("timing", report.getJSONObject("timing")
                .put("downloadMs", downloadElapsedMs)
                .put("installMs", JSONObject.NULL)
            )
            report.put("acquisition", JSONObject()
                .put("downloadActivatesModel", false)
                .put("explicitUseCompleted", true)
                .put("installedFileBytes", installed.file.length())
                .put("installedFileSha256", installed.file.sha256())
                .put("origin", installed.origin.name)
            )
        } catch (error: Throwable) {
            report.put("status", "failed")
            report.put("error", "${error::class.java.name}: ${error.message}")
            throw error
        } finally {
            writeReport(context, runId, "acquisition", report)
        }
    }

    private fun baseReport(
        context: android.content.Context,
        runId: String,
        arguments: android.os.Bundle,
    ): JSONObject {
        val modelId = arguments.requiredString(ARG_MODEL_ID)
        val artifactSha256 = arguments.requiredString(ARG_ARTIFACT_SHA256)
        val contractId = arguments.requiredString(ARG_CONTRACT_ID)
        val abi = arguments.requiredString(ARG_PROCESS_ABI)
        val requestedBackend = when (arguments.getString(ARG_BACKEND_MODE) ?: "cpu") {
            "cpu" -> "LiteRtCpu"
            "auto" -> "LiteRtAuto"
            else -> error("Unsupported Phase 7 backend mode.")
        }
        return JSONObject()
            .put("schemaVersion", "phase7-report-v1")
            .put("status", "not-tested")
            .put("stage", "acquisition")
            .put("matrixKey", JSONObject()
                .put("modelId", modelId)
                .put("artifactSha256", artifactSha256)
                .put("contractId", contractId)
                .put("contractSchemaVersion", arguments.requiredInt(ARG_CONTRACT_SCHEMA_VERSION))
                .put("abi", abi)
                .put("backend", requestedBackend)
                .put("profileId", arguments.getString(ARG_PROFILE_ID) ?: "cpu-default-fp32-v1")
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
                .put("fixtureId", arguments.getString(ARG_FIXTURE_ID) ?: "identity-only")
                .put("fileName", arguments.getString(ARG_FIXTURE_FILE_NAME) ?: "identity-only")
                .put("byteSize", 0)
                .put("sha256", ZERO_SHA256)
                .put("durationUs", 0)
                .put("sampleRate", 1)
                .put("channels", 1)
                .put("codec", "not-tested")
                .put("decodeClass", "not-tested")
            )
            .put("run", JSONObject()
                .put("runId", runId)
                .put("class", arguments.getString(ARG_RUN_CLASS) ?: "cold-session")
                .put("cpuThreads", arguments.getInt(ARG_CPU_THREADS, 0).coerceAtLeast(1))
                .put("windowDecodeEnabled", false)
                .put("cleanInstallScenario", arguments.getBoolean(ARG_CLEAN_INSTALL, true))
                .put("backendRequested", requestedBackend)
                .put("backendUsed", "identity-only")
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

    private fun runtimeAbiFromNativeDirectory(context: android.content.Context): MdxRuntimeAbi {
        val directory = context.applicationInfo.nativeLibraryDir.lowercase()
        return when {
            "arm64" in directory -> MdxRuntimeAbi.Arm64V8a
            "armeabi" in directory || "arm" in directory -> MdxRuntimeAbi.ArmeabiV7a
            "x86_64" in directory -> MdxRuntimeAbi.X86_64
            "x86" in directory -> MdxRuntimeAbi.X86
            else -> error("Could not infer process ABI from native library directory: $directory")
        }
    }

    private fun nativeLibraryEvidence(
        context: android.content.Context,
        abi: MdxRuntimeAbi,
    ): NativeLibraryEvidence {
        val extracted = File(context.applicationInfo.nativeLibraryDir, LITERT_LIBRARY)
        if (extracted.isFile) {
            return NativeLibraryEvidence(
                path = extracted.absolutePath,
                bytes = extracted.length(),
                sha256 = extracted.sha256(),
            )
        }
        val entryName = "lib/${abi.androidName}/$LITERT_LIBRARY"
        ZipFile(context.applicationInfo.sourceDir).use { apk ->
            val entry = requireNotNull(apk.getEntry(entryName)) {
                "LiteRT native library entry is missing: $entryName"
            }
            val digest = MessageDigest.getInstance("SHA-256")
            apk.getInputStream(entry).use { input ->
                val buffer = ByteArray(DEFAULT_COPY_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    digest.update(buffer, 0, read)
                    total += read
                }
                return NativeLibraryEvidence(
                    path = "${context.applicationInfo.sourceDir}!/$entryName",
                    bytes = total,
                    sha256 = digest.digest().toHexString(),
                )
            }
        }
    }

    private fun writeReport(
        context: android.content.Context,
        runId: String,
        stage: String,
        report: JSONObject,
    ) {
        val root = File(context.filesDir, REPORT_DIRECTORY)
        check(root.isDirectory || root.mkdirs()) { "Could not create the Phase 7 report directory." }
        File(root, "$runId-$stage.json").writeText(report.toString(2))
    }

    private fun android.os.Bundle.requiredString(key: String): String =
        requireNotNull(getString(key)?.takeIf(String::isNotBlank)) {
            "Missing instrumentation argument: $key"
        }

    private fun android.os.Bundle.requiredInt(key: String): Int =
        getString(key)?.toIntOrNull() ?: getInt(key).takeIf { it != 0 }
        ?: error("Missing instrumentation argument: $key")

    private fun String.requireSafeName(): String = also {
        require(SAFE_NAME.matches(it)) { "Unsafe Phase 7 validation run ID." }
    }

    private fun String.is64BitAbi(): Boolean = this == MdxRuntimeAbi.Arm64V8a.androidName ||
        this == MdxRuntimeAbi.X86_64.androidName

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

    private data class NativeLibraryEvidence(
        val path: String,
        val bytes: Long,
        val sha256: String,
    )

    private companion object {
        const val ARG_RUN_ID = "runId"
        const val ARG_SERIAL = "serial"
        const val ARG_PROCESS_ABI = "processAbi"
        const val ARG_MODEL_ID = "modelId"
        const val ARG_ARTIFACT_SHA256 = "artifactSha256"
        const val ARG_ARTIFACT_FILE_NAME = "artifactFileName"
        const val ARG_CONTRACT_ID = "contractId"
        const val ARG_CONTRACT_SCHEMA_VERSION = "contractSchemaVersion"
        const val ARG_PROFILE_ID = "profileId"
        const val ARG_APP_COMMIT = "appCommit"
        const val ARG_APP_APK_SHA256 = "appApkSha256"
        const val ARG_TEST_APK_SHA256 = "testApkSha256"
        const val ARG_CATALOG_SHA256 = "catalogSha256"
        const val ARG_CATALOG_SOURCE_REVISION = "catalogSourceRevision"
        const val ARG_MODEL_RELEASE_TAG = "modelReleaseTag"
        const val ARG_PIPELINE_VERSION = "pipelineCompatibilityVersion"
        const val ARG_LITERT_VERSION = "litertVersion"
        const val ARG_LITERT_SHA256 = "litertSha256"
        const val ARG_RUNNER_REVISION = "runnerRevision"
        const val ARG_THRESHOLDS_VERSION = "thresholdsVersion"
        const val ARG_FIXTURES_VERSION = "fixturesVersion"
        const val ARG_FIXTURE_ID = "fixtureId"
        const val ARG_FIXTURE_FILE_NAME = "fixtureFileName"
        const val ARG_BACKEND_MODE = "backendMode"
        const val ARG_RUN_CLASS = "runClass"
        const val ARG_CPU_THREADS = "cpuThreads"
        const val ARG_CLEAN_INSTALL = "cleanInstallScenario"
        const val CATALOG_ASSET = "source-separation/model-catalog-v2.json"
        const val LITERT_LIBRARY = "libLiteRt.so"
        const val DEFAULT_COPY_BUFFER_SIZE = 64 * 1024
        const val REPORT_DIRECTORY = "phase7-validation-reports"
        const val ZERO_SHA256 = "0000000000000000000000000000000000000000000000000000000000000000"
        val SAFE_NAME = Regex("^[A-Za-z0-9._-]{1,120}$")
    }
}
