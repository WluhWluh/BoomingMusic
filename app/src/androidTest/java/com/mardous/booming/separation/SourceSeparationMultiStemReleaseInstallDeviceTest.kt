package com.mardous.booming.separation

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mardous.booming.separation.model.contract.SourceSeparationMultiStemInstallProgressKind
import com.mardous.booming.separation.model.contract.SourceSeparationMultiTensorExecutableContractLoader
import com.mardous.booming.separation.model.contract.SourceSeparationReleaseCatalogValidator
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

@RunWith(AndroidJUnit4::class)
class SourceSeparationMultiStemReleaseInstallDeviceTest {
    @Test
    fun installExactReleasePairThroughProductionDeliveryPath() {
        val arguments = InstrumentationRegistry.getArguments()
        val modelId = arguments.getString(ARG_MODEL_ID).orEmpty()
        assumeTrue("Pass -e $ARG_MODEL_ID <id> to run the Release download gate.", modelId.isNotBlank())
        require(modelId in EXPECTED_MODEL_IDS) { "Unsupported HTDemucs qualification model: $modelId" }
        val runId = arguments.getString(ARG_RUN_ID)
            ?.takeIf { SAFE_NAME.matches(it) }
            ?: "${modelId}-${System.currentTimeMillis()}"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val reportFile = File(context.filesDir, REPORT_DIRECTORY).apply { mkdirs() }
            .resolve("$runId.json")
        val report = JSONObject()
            .put("runId", runId)
            .put("modelId", modelId)
            .put("status", "running")
            .put("deviceModel", android.os.Build.MODEL)
            .put("sdk", android.os.Build.VERSION.SDK_INT)
            .put("abi", android.os.Build.SUPPORTED_ABIS.first())
        try {
            val facade = GlobalContext.get().get<SourceSeparationMultiStemProductFacade>()
            val catalog = facade.catalog()
            val pair = SourceSeparationReleaseCatalogValidator.resolveMultistem(catalog, modelId)
            val progress = mutableListOf<JSONObject>()
            val lastByKind = mutableMapOf<SourceSeparationMultiStemInstallProgressKind, Long>()
            val startedAt = SystemClock.elapsedRealtime()
            val installed = facade.install(modelId) { event ->
                val previous = lastByKind[event.kind] ?: 0L
                require(event.downloadedBytes >= previous) { "Download progress moved backwards." }
                lastByKind[event.kind] = event.downloadedBytes
                if (progress.isEmpty() || event.downloadedBytes == pair.artifact.expectedByteSize ||
                    event.kind == SourceSeparationMultiStemInstallProgressKind.Sidecar
                ) {
                    progress += JSONObject()
                        .put("kind", event.kind.name)
                        .put("downloadedBytes", event.downloadedBytes)
                }
            }
            val elapsedMs = SystemClock.elapsedRealtime() - startedAt
            val executable = installed.sidecarFile.bufferedReader().use { reader ->
                SourceSeparationMultiTensorExecutableContractLoader.load(reader.readText())
            }
            assertEquals(pair.artifact.expectedByteSize, installed.modelFile.length())
            assertEquals(pair.artifact.expectedSha256, installed.modelFile.sha256())
            assertEquals(pair.sidecar.expectedSha256, installed.sidecarFile.sha256())
            assertEquals(modelId, executable.modelContract.modelId)
            assertEquals(pair.entry.contract.contractId, executable.modelContract.contractId)
            assertTrue(facade.installedModels().any {
                it.modelId == modelId && it.modelSha256 == pair.artifact.expectedSha256
            })
            report.put("status", "complete")
                .put("elapsedMs", elapsedMs)
                .put("artifactFile", installed.modelFile.name)
                .put("artifactBytes", installed.modelFile.length())
                .put("artifactSha256", installed.modelSha256)
                .put("sidecarFile", installed.sidecarFile.name)
                .put("contractId", installed.contractId)
                .put("pipelineId", installed.pipelineId)
                .put("allowedBackends", JSONArray(pair.entry.allowedBackends))
                .put("progress", JSONArray(progress))
            reportFile.writeText(report.toString(2))
        } catch (error: Throwable) {
            report.put("status", "error")
                .put("errorType", error.javaClass.name)
                .put("errorMessage", error.message.orEmpty())
                .put("stackTrace", error.stackTraceToString())
            reportFile.writeText(report.toString(2))
            throw error
        }
    }

    private fun File.sha256(): String = inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(256 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val ARG_MODEL_ID = "bssMultistemModelId"
        const val ARG_RUN_ID = "bssMultistemRunId"
        const val REPORT_DIRECTORY = "source-separation/multistem-release-device-reports"
        val SAFE_NAME = Regex("^[A-Za-z0-9._-]{1,160}$")
        val EXPECTED_MODEL_IDS = setOf(
            "htdemucs_6s_core_canonical_7p8s_fp32_v1_0_0",
            "htdemucs_4s_core_canonical_7p8s_fp32_v1_0_0",
            "htdemucs_6s_guitar_ft_core_canonical_7p8s_fp32_v1_0_0",
        )
    }
}
