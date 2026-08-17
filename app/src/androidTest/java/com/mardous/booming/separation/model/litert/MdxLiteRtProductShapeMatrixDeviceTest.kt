package com.mardous.booming.separation.model.litert

import android.os.Build
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mardous.booming.separation.model.MdxCompatibilityPolicy
import com.mardous.booming.separation.model.MdxDspConfig
import com.mardous.booming.separation.model.MdxModelArtifact
import com.mardous.booming.separation.model.MdxRuntimeAbi
import com.mardous.booming.separation.model.MdxRuntimePlatform
import com.mardous.booming.separation.model.MdxRuntimeSettings
import com.mardous.booming.separation.model.MdxWaveformInferenceSession
import com.mardous.booming.separation.model.contract.SourceSeparationModelContractValidator
import com.mardous.booming.separation.model.contract.SourceSeparationModelMetadata
import com.mardous.booming.separation.model.contract.toMdxExecutionProfile
import com.mardous.booming.separation.model.preset.SourceSeparationInstalledPresetOrigin
import com.mardous.booming.separation.model.preset.SourceSeparationPresetRepository
import com.mardous.booming.separation.runtime.SourceSeparationGpuRuntimeBootstrap
import com.mardous.booming.separation.runtime.SourceSeparationRuntimeBootstrap
import java.io.File
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.java.KoinJavaComponent.get

@RunWith(AndroidJUnit4::class)
class MdxLiteRtProductShapeMatrixDeviceTest {
    @Test
    fun validateAllProductShapes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val runId = arguments.requiredSafeName(ARG_RUN_ID)
        val backendMode = BackendMode.valueOf(
            arguments.requiredSafeName(ARG_BACKEND_MODE).replaceFirstChar(Char::uppercase),
        )
        val processAbi = currentProcessAbi()
        require(processAbi == arguments.requiredSafeName(ARG_PROCESS_ABI))
        val reportFile = File(context.filesDir, "$REPORT_DIRECTORY/$runId.json").apply {
            parentFile?.mkdirs()
        }
        val report = JSONObject()
            .put("schemaVersion", 1)
            .put("matrixId", MATRIX_ID)
            .put("runId", runId)
            .put("status", "running")
            .put("backendMode", backendMode.name.lowercase())
            .put("deviceModel", Build.MODEL)
            .put("androidApi", Build.VERSION.SDK_INT)
            .put("processAbi", processAbi)

        try {
            val appApk = File(context.applicationInfo.sourceDir)
            val testApk = File(instrumentation.context.applicationInfo.sourceDir)
            report.put("build", JSONObject()
                .put("appApkSha256", appApk.sha256())
                .put("testApkSha256", testApk.sha256()))
            val installation = LiteRt220RuntimeIdentity.requireExact(
                SourceSeparationRuntimeBootstrap.ensureLoaded(context),
            )
            val runtimeAbi = MdxRuntimeAbi.entries.single { it.androidName == processAbi }
            val platformProvider = { MdxRuntimePlatform(Build.VERSION.SDK_INT, runtimeAbi) }
            val gpuCapability = if (backendMode == BackendMode.Gpu) {
                SourceSeparationGpuRuntimeBootstrap.ensureLoaded(context)
                SourceSeparationGpuRuntimeBootstrap.capability().also { capability ->
                    require(capability.available) { capability.detail }
                    require(capability.artifactVersion == LiteRt220RuntimeIdentity.ARTIFACT_VERSION)
                    require(capability.profileId == MdxLiteRtBoundedGpuContract.PROFILE_ID)
                    require(capability.kernelBatchSize == 1)
                    require(capability.commandQueueWindowSize == 1)
                }
            } else {
                null
            }
            report.put("runtime", JSONObject()
                .put("artifactVersion", installation.identity.runtimeArtifactVersion)
                .put("releaseVersion", installation.identity.releaseVersion)
                .put("coreSha256", installation.identity.librarySha256)
                .put("jniSha256", installation.identity.jniLibrarySha256)
                .put("gpuCapability", gpuCapability?.let { capability ->
                    JSONObject()
                        .put("artifactVersion", capability.artifactVersion)
                        .put("profileId", capability.profileId)
                        .put("kernelBatchSize", capability.kernelBatchSize)
                        .put("commandQueueWindowSize", capability.commandQueueWindowSize)
                } ?: JSONObject.NULL))

            val stagingRoot = File(context.filesDir, STAGING_DIRECTORY).canonicalFile
            require(stagingRoot.isDirectory) { "The product shape-matrix staging root is missing." }
            val catalog = SourceSeparationModelMetadata.loadBundledCatalog(context)
            val repository = get<SourceSeparationPresetRepository>(
                SourceSeparationPresetRepository::class.java,
            )
            val baseline = memorySnapshot()
            var peakPssBytes = baseline.pssBytes
            var peakNativeHeapBytes = baseline.nativeHeapBytes
            val modelReports = JSONArray()
            SHAPES.forEachIndexed { index, shape ->
                val contract = SourceSeparationModelContractValidator.resolveReviewedContract(
                    catalog,
                    shape.modelId,
                )
                val profile = contract.toMdxExecutionProfile(catalog.runtimeQualifications)
                require(profile.dspConfig.nFft == shape.nFft)
                require(profile.dspConfig.hopLength == shape.hopLength)
                require(profile.dspConfig.dimF == shape.dimF)
                require(profile.dspConfig.dimTPower == shape.dimTPower)
                val official = repository.officialPreset(shape.modelId)
                require(official.fileName == shape.modelFile)
                require(official.sha256.equals(shape.sha256, ignoreCase = true))
                val staged = File(stagingRoot, shape.modelFile).canonicalFile
                require(staged.toPath().startsWith(stagingRoot.toPath()) && staged.isFile)
                require(staged.length() == official.byteSize)
                require(staged.sha256().equals(shape.sha256, ignoreCase = true))
                val installed = repository.installedModels().singleOrNull { candidate ->
                    candidate.modelId == shape.modelId &&
                        candidate.sha256.equals(shape.sha256, ignoreCase = true)
                } ?: staged.inputStream().buffered().use { input ->
                    repository.installOfficial(shape.modelId, input)
                }
                require(installed.origin == SourceSeparationInstalledPresetOrigin.OfficialDownload)
                require(installed.contractId == contract.contractId)
                require(installed.file.sha256().equals(shape.sha256, ignoreCase = true))
                val artifact = MdxModelArtifact(
                    file = installed.file,
                    byteSize = installed.byteSize,
                    sha256 = installed.sha256,
                )
                profile.validateArtifact(artifact)
                val modelReport = when (backendMode) {
                    BackendMode.Cpu -> validateCpuShape(
                        artifact,
                        profile.dspConfig,
                        profile,
                        platformProvider,
                    )
                    BackendMode.Gpu -> validateGpuShape(
                        artifact,
                        profile.dspConfig,
                        profile,
                        platformProvider,
                    )
                }
                modelReports.put(modelReport
                    .put("order", index)
                    .put("modelId", shape.modelId)
                    .put("modelFile", shape.modelFile)
                    .put("sha256", shape.sha256)
                    .put("releaseTag", official.releaseTag)
                    .put("releaseUrl", official.downloadUrl)
                    .put("nFft", shape.nFft)
                    .put("hopLength", shape.hopLength)
                    .put("dimF", shape.dimF)
                    .put("dimTPower", shape.dimTPower))
                Runtime.getRuntime().gc()
                SystemClock.sleep(MODEL_CLOSE_SETTLE_MS)
                memorySnapshot().let { current ->
                    peakPssBytes = maxOf(peakPssBytes, current.pssBytes)
                    peakNativeHeapBytes = maxOf(peakNativeHeapBytes, current.nativeHeapBytes)
                }
            }
            Runtime.getRuntime().gc()
            SystemClock.sleep(FINAL_CLOSE_SETTLE_MS)
            val afterClose = memorySnapshot()
            require(afterClose.fileDescriptors <= baseline.fileDescriptors + MAX_FD_GROWTH) {
                "Shape matrix leaked file descriptors: $baseline -> $afterClose"
            }
            require(afterClose.pssBytes - baseline.pssBytes <= MAX_PSS_GROWTH_BYTES) {
                "Shape matrix retained excessive PSS: $baseline -> $afterClose"
            }
            require(afterClose.nativeHeapBytes - baseline.nativeHeapBytes <=
                MAX_NATIVE_HEAP_GROWTH_BYTES
            ) { "Shape matrix retained excessive native heap: $baseline -> $afterClose" }
            report.put("models", modelReports)
                .put("completedShapes", modelReports.length())
                .put("memory", JSONObject()
                    .put("baseline", baseline.toJson())
                    .put("afterClose", afterClose.toJson())
                    .put("peakPssBytes", peakPssBytes)
                    .put("peakNativeHeapBytes", peakNativeHeapBytes)
                    .put("pssGrowthBytes", afterClose.pssBytes - baseline.pssBytes)
                    .put(
                        "nativeHeapGrowthBytes",
                        afterClose.nativeHeapBytes - baseline.nativeHeapBytes,
                    ))
                .put("status", "complete")
            require(modelReports.length() == SHAPES.size)
            reportFile.writeText(report.toString(2))
        } catch (error: Throwable) {
            report.put("status", "error")
                .put("errorType", error::class.java.name)
                .put("errorMessage", error.message ?: JSONObject.NULL)
                .put("stackTrace", error.stackTraceToString())
            reportFile.writeText(report.toString(2))
            throw error
        }
    }

    private fun validateCpuShape(
        artifact: MdxModelArtifact,
        config: MdxDspConfig,
        profile: com.mardous.booming.separation.model.MdxExecutionProfile,
        platformProvider: () -> MdxRuntimePlatform,
    ): JSONObject {
        val waveform = fixture(config)
        val started = SystemClock.elapsedRealtimeNanos()
        var diagnostics = "unavailable"
        val comparison = MdxLiteRtCpuInferenceSessionFactory(
            platformProvider = platformProvider,
            compatibilityPolicy = MdxCompatibilityPolicy.AllowUntestedInternal,
            availableProcessors = { 5 },
        ).create(artifact, profile, MdxRuntimeSettings(cpuThreads = 4)).use { session ->
            val waveformSession = session as? MdxWaveformInferenceSession
                ?: error("The product CPU session lost managed waveform support.")
            require(waveformSession.waveformSlotCount == 2)
            require(waveformSession.supportsStagedWaveformExecution)
            val first = waveformSession.runSlot(waveform, 0)
            val second = waveformSession.runSlot(waveform, 1)
            diagnostics = session.diagnostics.toDisplayText()
            compareWaveforms(first, second)
        }
        require(comparison.maxAbs == 0.0) { "Product CPU slots are not deterministic." }
        return JSONObject()
            .put("elapsedMs", nanosToMillis(SystemClock.elapsedRealtimeNanos() - started))
            .put("slotComparison", comparison.toJson())
            .put("runtimeDiagnostics", diagnostics)
    }

    private fun validateGpuShape(
        artifact: MdxModelArtifact,
        config: MdxDspConfig,
        profile: com.mardous.booming.separation.model.MdxExecutionProfile,
        platformProvider: () -> MdxRuntimePlatform,
    ): JSONObject {
        val waveform = fixture(config)
        val started = SystemClock.elapsedRealtimeNanos()
        val cpuOutput = MdxLiteRtCpuInferenceSessionFactory(
            platformProvider = platformProvider,
            compatibilityPolicy = MdxCompatibilityPolicy.AllowUntestedInternal,
            availableProcessors = { 5 },
        ).create(artifact, profile, MdxRuntimeSettings(cpuThreads = 4)).use { session ->
            val waveformSession = session as? MdxWaveformInferenceSession
                ?: error("The product CPU reference lost managed waveform support.")
            waveformSession.runSlot(waveform, 0)
        }
        var diagnostics = "unavailable"
        lateinit var firstGpu: Array<FloatArray>
        val repeatComparison = MdxLiteRtGpuInferenceSessionFactory(
            runtimeProfile = MdxLiteRtGpuRuntimeProfile.BoundedOpenClFp32V1,
            platformProvider = platformProvider,
            compatibilityPolicy = MdxCompatibilityPolicy.AllowUntestedInternal,
        ).create(artifact, profile, MdxRuntimeSettings()).use { session ->
            val waveformSession = session as? MdxWaveformInferenceSession
                ?: error("The bounded GPU session lost managed waveform support.")
            require(waveformSession.waveformSlotCount == 2)
            require(waveformSession.supportsStagedWaveformExecution)
            firstGpu = waveformSession.runSlot(waveform, 0)
            val secondGpu = waveformSession.runSlot(waveform, 1)
            diagnostics = session.diagnostics.toDisplayText()
            compareWaveforms(firstGpu, secondGpu)
        }
        require(repeatComparison.maxAbs == 0.0) { "Bounded GPU slots are not deterministic." }
        val cpuComparison = compareWaveforms(cpuOutput, firstGpu)
        require(cpuComparison.snrDb >= MINIMUM_GPU_SNR_DB)
        require(cpuComparison.maxAbs <= MAXIMUM_GPU_ABSOLUTE_ERROR)
        val statistics = MdxLiteRtBoundedGpuRuntime.statistics()
        require(statistics.dispatchCount > 0L)
        require(statistics.dispatchCount == statistics.eventWaitCount)
        return JSONObject()
            .put("elapsedMs", nanosToMillis(SystemClock.elapsedRealtimeNanos() - started))
            .put("comparisonToCpu", cpuComparison.toJson())
            .put("slotComparison", repeatComparison.toJson())
            .put("dispatchCount", statistics.dispatchCount)
            .put("eventWaitCount", statistics.eventWaitCount)
            .put("runtimeDiagnostics", diagnostics)
    }

    private fun MdxWaveformInferenceSession.runSlot(
        waveform: Array<FloatArray>,
        slot: Int,
    ): Array<FloatArray> {
        prepareWaveform(waveform, slot)
        invokePreparedWaveform(slot)
        return readPreparedWaveform(slot).map(FloatArray::copyOf).toTypedArray()
    }

    private fun fixture(config: MdxDspConfig): Array<FloatArray> = Array(2) { channel ->
        FloatArray(config.chunkSize) { sample ->
            val time = sample.toDouble() / config.sampleRate
            val base = if (channel == 0) 173.0 else 281.0
            (0.16 * sin(2.0 * PI * base * time) +
                0.04 * sin(2.0 * PI * base * 1.61 * time) +
                0.01 * sin(sample * 0.017)).toFloat()
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
                maxAbs = maxOf(maxAbs, abs(error))
                count += 1
            }
        }
        return Comparison(
            snrDb = if (noise == 0.0) Double.POSITIVE_INFINITY else 10.0 * log10(signal / noise),
            maxAbs = maxAbs,
            rmse = sqrt(noise / count.coerceAtLeast(1L)),
        )
    }

    private fun memorySnapshot(): MemorySnapshot = MemorySnapshot(
        pssBytes = Debug.getPss().toLong() * 1_024L,
        nativeHeapBytes = Debug.getNativeHeapAllocatedSize(),
        fileDescriptors = File("/proc/self/fd").list()?.size ?: -1,
    )

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

    private fun android.os.Bundle.requiredSafeName(key: String): String =
        requireNotNull(getString(key)).takeIf(SAFE_NAME::matches)
            ?: error("Missing or unsafe instrumentation argument: $key")

    private fun currentProcessAbi(): String {
        val abis = if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS
        return abis.firstOrNull() ?: error("Android did not expose a process ABI.")
    }

    private fun nanosToMillis(value: Long): Double = value / 1_000_000.0

    private enum class BackendMode { Cpu, Gpu }

    private data class Shape(
        val modelId: String,
        val modelFile: String,
        val sha256: String,
        val nFft: Int,
        val hopLength: Int,
        val dimF: Int,
        val dimTPower: Int,
    )

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

    private data class MemorySnapshot(
        val pssBytes: Long,
        val nativeHeapBytes: Long,
        val fileDescriptors: Int,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("pssBytes", pssBytes)
            .put("nativeHeapBytes", nativeHeapBytes)
            .put("fileDescriptors", fileDescriptors)
    }

    private companion object {
        const val MATRIX_ID = "mdx-product-bridge-all13-v1"
        const val STAGING_DIRECTORY = "mdx-product-shape-matrix"
        const val REPORT_DIRECTORY = "source-separation/mdx-product-shape-matrix-reports"
        const val ARG_RUN_ID = "runId"
        const val ARG_BACKEND_MODE = "backendMode"
        const val ARG_PROCESS_ABI = "processAbi"
        const val MODEL_CLOSE_SETTLE_MS = 150L
        const val FINAL_CLOSE_SETTLE_MS = 1_000L
        const val MAX_FD_GROWTH = 12
        const val MAX_PSS_GROWTH_BYTES = 512L * 1_024L * 1_024L
        const val MAX_NATIVE_HEAP_GROWTH_BYTES = 512L * 1_024L * 1_024L
        const val MINIMUM_GPU_SNR_DB = 75.0
        const val MAXIMUM_GPU_ABSOLUTE_ERROR = 0.002
        val SAFE_NAME = Regex("^[A-Za-z0-9._-]{1,160}$")
        val SHAPES = listOf(
            Shape("kuielab_b_drums", "kuielab_b_drums_static_float32.tflite", "cbe947a5242c680a5e484aa0d3b857e6087cce5a32b7b1b61e823d47240638eb", 4096, 1024, 2048, 7),
            Shape("kuielab_a_drums", "kuielab_a_drums_static_float32.tflite", "28fb8bafb783ccc9659fae4d4a8924941d1eab8e141ef2000cf4da237241bb1f", 4096, 1024, 2048, 9),
            Shape("uvr_mdxnet_inst_main", "UVR-MDX-NET-Inst_Main_static_float32.tflite", "8901458e2874aee6defbb922b8e34dec6109665c54707116bc0e4d21dd2983bc", 5120, 1024, 2048, 8),
            Shape("uvr_mdxnet_inst_hq_4", "UVR-MDX-NET-Inst_HQ_4_static_float32.tflite", "5f091562bd0297ff2223015a219d12cc1136a9d54df975680ff4eb239629c742", 5120, 1024, 2560, 8),
            Shape("uvr_mdxnet_3_9662", "UVR_MDXNET_3_9662_static_float32.tflite", "f74eee1ac06845a7cf277416138b19a6203f34316a3a74b2bde19acbfb2f8378", 6144, 1024, 2048, 8),
            Shape("uvr_mdxnet_inst_hq_1", "UVR-MDX-NET-Inst_HQ_1_static_float32.tflite", "eca068aa4ee9f00c37ae2838dd4eaef51981e93a9352cf7609b7a6f8e596a8e3", 6144, 1024, 3072, 8),
            Shape("kuielab_a_vocals", "kuielab_a_vocals_static_float32.tflite", "066517da5cfdc67a348c08249753d4c4e16923800790dca1edcb5fd370681750", 6144, 1024, 2048, 9),
            Shape("reverb_hq_by_foxjoy", "Reverb_HQ_By_FoxJoy_static_float32.tflite", "94ada8c869418f67fa916d5eea1944b9153040731be7e5ce635b190a1c6b3e34", 6144, 1024, 3072, 9),
            Shape("kim_inst", "Kim_Inst_static_float32.tflite", "fd3ca5bcb6568d893be5f049de8804278ee669303749d6fb3010bbeaeae4d288", 7680, 1024, 3072, 8),
            Shape("kuielab_b_other", "kuielab_b_other_static_float32.tflite", "c11e06e6858956a7e27a413d37d3e933e2d62fd0ee8f66df3276a61169109044", 8192, 1024, 2048, 8),
            Shape("kuielab_a_other", "kuielab_a_other_static_float32.tflite", "c23a824468749a154aff73897bcb8ef37d98a99535543884ed39f18b8d49cbc8", 8192, 1024, 2048, 9),
            Shape("kuielab_b_bass", "kuielab_b_bass_static_float32.tflite", "9654f3f5cad4727e3b70a9d26dc74742b2bafd5cd75fc538c6ceb1673961bc79", 16384, 1024, 2048, 8),
            Shape("kuielab_a_bass", "kuielab_a_bass_static_float32.tflite", "17bb6777ecb7478677b247eaea49d7e8ce6538d951d04f3c01b6e6b750b1134a", 16384, 1024, 2048, 9),
        )
    }
}
