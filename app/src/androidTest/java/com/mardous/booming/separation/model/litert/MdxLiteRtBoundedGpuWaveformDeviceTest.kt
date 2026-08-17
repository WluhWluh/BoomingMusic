package com.mardous.booming.separation.model.litert

import android.os.Build
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mardous.booming.separation.model.MdxCompatibilityPolicy
import com.mardous.booming.separation.model.MdxModelArtifact
import com.mardous.booming.separation.model.MdxRuntimeAbi
import com.mardous.booming.separation.model.MdxRuntimePlatform
import com.mardous.booming.separation.model.MdxRuntimeSettings
import com.mardous.booming.separation.model.MdxWaveformInferenceSession
import com.mardous.booming.separation.model.contract.SourceSeparationModelContractValidator
import com.mardous.booming.separation.model.contract.SourceSeparationModelMetadata
import com.mardous.booming.separation.model.contract.toMdxExecutionProfile
import com.mardous.booming.separation.runtime.SourceSeparationGpuRuntimeBootstrap
import com.mardous.booming.separation.runtime.SourceSeparationRuntimeBootstrap
import java.io.File
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MdxLiteRtBoundedGpuWaveformDeviceTest {
    @Test
    fun validateBoundedGpuManagedWaveform() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val runId = arguments.requiredString(ARG_RUN_ID)
        val reportFile = File(context.cacheDir, "$REPORT_DIRECTORY/$runId/report.json").apply {
            parentFile?.mkdirs()
        }
        val report = JSONObject()
            .put("runId", runId)
            .put("androidApi", Build.VERSION.SDK_INT)
            .put("processAbi", currentProcessAbi())

        try {
            val expectedAbi = arguments.requiredString(ARG_PROCESS_ABI)
            require(currentProcessAbi() == expectedAbi) {
                "Expected process ABI $expectedAbi, got ${currentProcessAbi()}."
            }
            val catalog = SourceSeparationModelMetadata.loadBundledCatalog(context)
            val contract = SourceSeparationModelContractValidator.resolveReviewedContract(
                catalog,
                arguments.requiredString(ARG_MODEL_ID),
            )
            val profile = contract.toMdxExecutionProfile(catalog.runtimeQualifications)
            val stagingRoot = File(context.filesDir, STAGING_DIRECTORY).canonicalFile
            val modelFile = arguments.requiredStagedFile(ARG_MODEL_PATH, stagingRoot)
            val artifact = MdxModelArtifact(modelFile, modelFile.length(), modelFile.sha256())
            profile.validateArtifact(artifact)
            val cpuInstallation = SourceSeparationRuntimeBootstrap.ensureLoaded(context)
            SourceSeparationGpuRuntimeBootstrap.ensureLoaded(context)
            val capability = SourceSeparationGpuRuntimeBootstrap.capability()
            require(capability.available) { capability.detail }
            require(capability.artifactVersion == EXPECTED_RUNTIME_ARTIFACT)
            require(capability.profileId == MdxLiteRtBoundedGpuContract.PROFILE_ID)
            require(capability.kernelBatchSize == 1 && capability.commandQueueWindowSize == 1)

            val runtimeAbi = MdxRuntimeAbi.entries.single { it.androidName == expectedAbi }
            val platformProvider = { MdxRuntimePlatform(Build.VERSION.SDK_INT, runtimeAbi) }
            val waveform = fixture(profile.dspConfig.chunkSize, profile.dspConfig.sampleRate)
            val cpuOutput = MdxLiteRtCpuInferenceSessionFactory(
                platformProvider = platformProvider,
                compatibilityPolicy = MdxCompatibilityPolicy.AllowUntestedInternal,
                availableProcessors = { 5 },
            ).create(artifact, profile, MdxRuntimeSettings(cpuThreads = 4)).use { session ->
                val waveformSession = session as? MdxWaveformInferenceSession
                    ?: error("The CPU session lost managed waveform support.")
                waveformSession.runWaveform(waveform)
                    .map(FloatArray::copyOf)
                    .toTypedArray()
            }

            val gpuDiagnostics: String
            val gpuOutput = MdxLiteRtGpuInferenceSessionFactory(
                runtimeProfile = MdxLiteRtGpuRuntimeProfile.BoundedOpenClFp32V1,
                platformProvider = platformProvider,
                compatibilityPolicy = MdxCompatibilityPolicy.AllowUntestedInternal,
            ).create(artifact, profile, MdxRuntimeSettings()).use { session ->
                val waveformSession = session as? MdxWaveformInferenceSession
                    ?: error("The bounded GPU session lost managed waveform support.")
                require(waveformSession.waveformSlotCount == 2)
                require(waveformSession.supportsStagedWaveformExecution)
                require(
                    waveformSession.waveformDspImplementationId ==
                        "native-managed-pocketfft-packed-real-v1",
                )
                waveformSession.runWaveform(waveform)
                    .map(FloatArray::copyOf)
                    .toTypedArray()
                    .also { gpuDiagnostics = session.diagnostics.toDisplayText() }
            }
            val comparison = compareWaveforms(cpuOutput, gpuOutput)
            require(comparison.snrDb >= MINIMUM_SNR_DB) {
                "Bounded GPU waveform SNR ${comparison.snrDb} dB is below the gate."
            }
            require(comparison.maxAbs <= MAXIMUM_ABSOLUTE_ERROR) {
                "Bounded GPU waveform max error ${comparison.maxAbs} exceeds the gate."
            }
            require(gpuOutput.all { channel -> channel.all(Float::isFinite) }) {
                "Bounded GPU waveform output contains non-finite values."
            }
            val statistics = MdxLiteRtBoundedGpuRuntime.statistics()
            require(statistics.dispatchCount > 0L)
            require(statistics.dispatchCount == statistics.eventWaitCount) {
                "Bounded GPU dispatch/event-wait mismatch."
            }
            report.put("runtimeArtifactVersion", cpuInstallation.identity.runtimeArtifactVersion)
                .put("gpuCapability", JSONObject()
                    .put("artifactVersion", capability.artifactVersion)
                    .put("profileId", capability.profileId)
                    .put("kernelBatchSize", capability.kernelBatchSize)
                    .put("commandQueueWindowSize", capability.commandQueueWindowSize))
                .put("comparisonToCpu", comparison.toJson())
                .put("dispatchCount", statistics.dispatchCount)
                .put("eventWaitCount", statistics.eventWaitCount)
                .put("gpuRuntimeDiagnostics", gpuDiagnostics)
                .put("status", "complete")
            reportFile.writeText(report.toString(2))
        } catch (error: Throwable) {
            report.put("status", "error")
                .put("errorType", error.javaClass.name)
                .put("errorMessage", error.message ?: JSONObject.NULL)
                .put("stackTrace", error.stackTraceToString())
            reportFile.writeText(report.toString(2))
            throw error
        }
    }

    private fun fixture(chunkSize: Int, sampleRate: Int): Array<FloatArray> = Array(2) { channel ->
        FloatArray(chunkSize) { sample ->
            val time = sample.toDouble() / sampleRate
            val frequency = if (channel == 0) 220.0 else 331.0
            (0.18 * sin(2.0 * PI * frequency * time) +
                0.05 * sin(2.0 * PI * (frequency * 1.7) * time)).toFloat()
        }
    }

    private fun compareWaveforms(
        reference: Array<FloatArray>,
        candidate: Array<FloatArray>,
    ): Comparison {
        require(reference.size == candidate.size)
        var signal = 0.0
        var noise = 0.0
        var maxAbs = 0.0
        var count = 0L
        reference.indices.forEach { channel ->
            require(reference[channel].size == candidate[channel].size)
            reference[channel].indices.forEach { sample ->
                val expected = reference[channel][sample].toDouble()
                val error = candidate[channel][sample].toDouble() - expected
                signal += expected * expected
                noise += error * error
                maxAbs = maxOf(maxAbs, kotlin.math.abs(error))
                count += 1
            }
        }
        val snrDb = if (noise == 0.0) Double.POSITIVE_INFINITY else 10.0 * log10(signal / noise)
        return Comparison(snrDb, maxAbs, sqrt(noise / count.coerceAtLeast(1)))
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().buffered().use { source ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    private fun android.os.Bundle.requiredString(key: String): String =
        requireNotNull(getString(key)).takeIf(String::isNotBlank)
            ?: error("Missing instrumentation argument: $key")

    private fun android.os.Bundle.requiredStagedFile(key: String, root: File): File {
        val file = File(requiredString(key)).canonicalFile
        require(file.toPath().startsWith(root.toPath())) {
            "Staged file is outside the validation staging root: $file"
        }
        require(file.isFile) { "Staged file does not exist: $file" }
        return file
    }

    private fun currentProcessAbi(): String {
        val abis = if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS
        return abis.first()
    }

    private data class Comparison(
        val snrDb: Double,
        val maxAbs: Double,
        val rmse: Double,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("snrDb", if (snrDb.isFinite()) snrDb else "Infinity")
            .put("maxAbs", maxAbs)
            .put("rmse", rmse)
    }

    private companion object {
        const val EXPECTED_RUNTIME_ARTIFACT = "2.2.0-bss.2"
        const val STAGING_DIRECTORY = "litert-validation-staging"
        const val REPORT_DIRECTORY = "litert-bounded-gpu-waveform-validation"
        const val ARG_RUN_ID = "runId"
        const val ARG_MODEL_ID = "modelId"
        const val ARG_MODEL_PATH = "modelPath"
        const val ARG_PROCESS_ABI = "processAbi"
        const val MINIMUM_SNR_DB = 75.0
        const val MAXIMUM_ABSOLUTE_ERROR = 0.002
    }
}
