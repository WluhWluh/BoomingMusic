package com.mardous.booming.separation.model.litert

import android.content.Context
import android.os.Build
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mardous.booming.separation.model.MdxDspConfig
import com.mardous.booming.separation.model.MdxCompatibilityPolicy
import com.mardous.booming.separation.model.MdxExecutionProfile
import com.mardous.booming.separation.model.MdxModelArtifact
import com.mardous.booming.separation.model.MdxRuntimeAbi
import com.mardous.booming.separation.model.MdxRuntimePlatform
import com.mardous.booming.separation.model.MdxRuntimeSettings
import com.mardous.booming.separation.model.MdxSpectrogram
import com.mardous.booming.separation.model.MdxWaveformInferenceSession
import com.mardous.booming.separation.model.withMdxInferenceTiming
import com.mardous.booming.separation.model.contract.SourceSeparationModelContractValidator
import com.mardous.booming.separation.model.contract.SourceSeparationModelMetadata
import com.mardous.booming.separation.model.contract.toMdxExecutionProfile
import com.mardous.booming.separation.runtime.SourceSeparationRuntimeBootstrap
import com.mardous.booming.separation.runtime.SourceSeparationCpuRuntimeInstallation
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

@RunWith(AndroidJUnit4::class)
class MdxLiteRtManagedPipelineDeviceTest {
    @Test
    fun validateStagedCpuManagedPipeline() {
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
            val inputFile = arguments.requiredStagedFile(ARG_INPUT_PATH, stagingRoot)
            val referenceFile = arguments.requiredStagedFile(ARG_REFERENCE_PATH, stagingRoot)
            require(modelFile.sha256().equals(profile.expectedSha256, ignoreCase = true)) {
                "Staged model hash does not match the product profile."
            }
            require(inputFile.sha256().equals(
                arguments.requiredString(ARG_INPUT_SHA256),
                ignoreCase = true,
            )) { "Staged input hash mismatch." }
            require(referenceFile.sha256().equals(
                arguments.requiredString(ARG_REFERENCE_SHA256),
                ignoreCase = true,
            )) { "Staged reference hash mismatch." }
            val artifact = MdxModelArtifact(modelFile, modelFile.length(), modelFile.sha256())
            profile.validateArtifact(artifact)
            val input = readFloat32(inputFile, profile.inputTensor.elementCount)
            val reference = readFloat32(referenceFile, profile.outputTensor.elementCount)
            val installation = SourceSeparationRuntimeBootstrap.ensureLoaded(context)
            require(installation.identity.runtimeArtifactVersion == EXPECTED_RUNTIME_ARTIFACT) {
                "Unexpected runtime artifact ${installation.identity.runtimeArtifactVersion}."
            }
            val runtimeAbi = MdxRuntimeAbi.entries.single { it.androidName == expectedAbi }
            val directOutputs = if (runtimeAbi != MdxRuntimeAbi.X86) {
                qualifyDirectManagedPipeline(
                    installation = installation,
                    modelFile = modelFile,
                    profile = profile,
                    input = input,
                    reference = reference,
                    cpuThreads = arguments.getString(ARG_CPU_THREADS)?.toIntOrNull() ?: 4,
                    report = report,
                )
            } else {
                report.put(
                    "directManagedQualification",
                    JSONObject()
                        .put("status", "skipped")
                        .put("reason", "pure-x86-uses-jvm-tensor-buffer"),
                )
                null
            }
            val productFactory = MdxLiteRtCpuInferenceSessionFactory(
                platformProvider = {
                    MdxRuntimePlatform(Build.VERSION.SDK_INT, runtimeAbi)
                },
                compatibilityPolicy = if (runtimeAbi == MdxRuntimeAbi.X86) {
                    MdxCompatibilityPolicy.AllowUserAttempts
                } else {
                    MdxCompatibilityPolicy.AllowUntestedInternal
                },
                availableProcessors = { 5 },
            ).withMdxInferenceTiming()
            val productSession = productFactory.create(
                artifact,
                profile,
                MdxRuntimeSettings(cpuThreads = 4),
            )
            lateinit var referenceComparison: Comparison
            productSession.use { session ->
                val productTensorOutput = session.run(input)
                referenceComparison = compare(reference, productTensorOutput)
                require(referenceComparison.snrDb >= 93.0) {
                    "Product CPU SNR ${referenceComparison.snrDb} dB is below the gate."
                }
                require(referenceComparison.maxAbs <= 0.0001) {
                    "Product CPU max error ${referenceComparison.maxAbs} exceeds the gate."
                }
                if (runtimeAbi == MdxRuntimeAbi.X86) {
                    require(session !is MdxWaveformInferenceSession) {
                        "Pure x86 unexpectedly selected the native managed waveform bridge."
                    }
                    require(session.diagnostics.toDisplayText().contains(
                        "pipeline=jvm-tensor-buffer-x86-fallback",
                    )) { "Pure x86 did not report the required JVM TensorBuffer pipeline." }
                    require(session.diagnostics.inferenceInvocationCount == 1L) {
                        "Timed x86 product session did not record its tensor invocation."
                    }
                    report.put("productFactoryComparison", referenceComparison.toJson())
                        .put("productPipeline", "jvm-tensor-buffer-x86-fallback")
                } else {
                    val direct = requireNotNull(directOutputs)
                    val productComparison = compare(direct.tensorOutput, productTensorOutput)
                    require(productComparison.maxAbs == 0.0) {
                        "The product allocator output differs from the direct managed pipeline."
                    }
                    val waveformSession = session as? MdxWaveformInferenceSession
                        ?: error("The product CPU session lost its managed waveform capability.")
                    val productWaveform = waveformSession.runWaveform(fixture(profile.dspConfig))
                    require(productWaveform.all { channel -> channel.all(Float::isFinite) }) {
                        "The product managed waveform path returned non-finite output."
                    }
                    val productWaveformComparison = compareWaveforms(
                        direct.waveformOutput,
                        productWaveform,
                    )
                    require(productWaveformComparison.maxAbs == 0.0) {
                        "The product managed waveform differs from the direct managed pipeline."
                    }
                    require(waveformSession.waveformDspImplementationId ==
                        "native-managed-pocketfft-packed-real-v1"
                    ) { "Unexpected product waveform implementation identity." }
                    require(waveformSession.waveformSlotCount == 2) {
                        "The product managed waveform session is not dual-slot."
                    }
                    require(waveformSession.supportsStagedWaveformExecution) {
                        "The product managed waveform session lost staged execution support."
                    }
                    require(session.diagnostics.inferenceInvocationCount == 2L) {
                        "Timed product session did not record tensor and waveform invocations."
                    }
                    report.put("productFactoryComparison", productComparison.toJson())
                        .put("productWaveformComparison", productWaveformComparison.toJson())
                        .put("productWaveformSlotCount", waveformSession.waveformSlotCount)
                        .put(
                            "productSupportsStagedWaveformExecution",
                            waveformSession.supportsStagedWaveformExecution,
                        )
                }
                report.put("productRuntimeDiagnostics", session.diagnostics.toDisplayText())
            }

            report.put("runtimeArtifactVersion", installation.identity.runtimeArtifactVersion)
                .put("runtimeCorePath", installation.libraryFile.absolutePath)
                .put("referenceComparison", referenceComparison.toJson())
                .put("status", "complete")
            directOutputs?.let { report.put("slotComparison", it.slotComparison.toJson()) }
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

    private fun qualifyDirectManagedPipeline(
        installation: SourceSeparationCpuRuntimeInstallation,
        modelFile: File,
        profile: MdxExecutionProfile,
        input: FloatArray,
        reference: FloatArray,
        cpuThreads: Int,
        report: JSONObject,
    ): DirectManagedQualification {
        val slotOutputs = Array(2) { FloatArray(0) }
        lateinit var nativeOutputWaveform: Array<FloatArray>
        MdxLiteRtManagedPipeline(
            coreLibraryFile = installation.libraryFile,
            modelFile = modelFile,
            profile = profile,
            cpuThreads = cpuThreads,
            backend = MdxLiteRtManagedPipeline.Backend.Cpu,
            slotCount = 2,
        ).use { pipeline ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                pipeline.writeTensorNchw(input, 0)
                val firstRun = executor.submit { pipeline.run(0) }
                val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (!pipeline.isInvocationInFlight() && System.nanoTime() < deadlineNanos) {
                    Thread.yield()
                }
                require(pipeline.isInvocationInFlight()) {
                    "CPU invocation completed before the overlap gate observed it."
                }
                pipeline.writeTensorNchw(input, 1)
                val firstRunStillActiveAfterPrepare = !firstRun.isDone
                firstRun.get(2, TimeUnit.MINUTES)
                slotOutputs[0] = pipeline.readTensorNchw(0).copyOf()
                pipeline.run(1)
                slotOutputs[1] = pipeline.readTensorNchw(1).copyOf()
                report.put(
                    "cpuOverlap",
                    JSONObject()
                        .put("invocationObserved", true)
                        .put("firstRunActiveAfterSlot1Prepare", firstRunStillActiveAfterPrepare),
                )
            } finally {
                executor.shutdown()
                if (!executor.awaitTermination(2, TimeUnit.MINUTES)) {
                    executor.shutdownNow()
                    check(executor.awaitTermination(2, TimeUnit.MINUTES)) {
                        "Managed pipeline executor did not drain before close."
                    }
                }
            }

            val waveformInput = fixture(profile.dspConfig)
            val productDsp = MdxSpectrogram(profile.dspConfig)
            val productInput = productDsp.waveformToTensor(waveformInput)
            pipeline.writeTensorNchw(productInput, 0)
            pipeline.run(0)
            val productOutputWaveform = productDsp.tensorToWaveform(
                pipeline.readTensorNchw(0).copyOf(),
            )
            pipeline.preprocessWaveform(waveformInput, 1)
            pipeline.run(1)
            nativeOutputWaveform = pipeline.postprocessWaveform(1)
                .map(FloatArray::copyOf)
                .toTypedArray()
            val directWaveformComparison = compareWaveforms(
                productOutputWaveform,
                nativeOutputWaveform,
            )
            require(directWaveformComparison.snrDb >= 75.0) {
                "Direct waveform SNR ${directWaveformComparison.snrDb} dB is too low."
            }
            require(directWaveformComparison.maxAbs <= 0.002) {
                "Direct waveform max error ${directWaveformComparison.maxAbs} is too high."
            }
            report.put("directWaveformComparison", directWaveformComparison.toJson())
        }

        val referenceComparison = compare(reference, slotOutputs[0])
        val slotComparison = compare(slotOutputs[0], slotOutputs[1])
        require(referenceComparison.snrDb >= 93.0) {
            "Managed CPU SNR ${referenceComparison.snrDb} dB is below the gate."
        }
        require(referenceComparison.maxAbs <= 0.0001) {
            "Managed CPU max error ${referenceComparison.maxAbs} exceeds the gate."
        }
        require(slotComparison.maxAbs == 0.0) {
            "The two managed slots produced different outputs."
        }

        MdxLiteRtManagedPipeline(
            coreLibraryFile = installation.libraryFile,
            modelFile = modelFile,
            profile = profile,
            cpuThreads = 2,
            backend = MdxLiteRtManagedPipeline.Backend.Cpu,
            slotCount = 1,
        ).use { replacement ->
            replacement.writeTensorNchw(input)
            replacement.run()
            require(replacement.readTensorNchw().all(Float::isFinite)) {
                "Replacement managed pipeline returned non-finite output."
            }
        }
        return DirectManagedQualification(
            tensorOutput = slotOutputs[0],
            waveformOutput = nativeOutputWaveform,
            slotComparison = slotComparison,
        )
    }

    private fun fixture(config: MdxDspConfig): Array<FloatArray> =
        Array(MdxDspConfig.STEREO_CHANNELS) { channel ->
            FloatArray(config.chunkSize) { index ->
                (
                    0.1 * sin(2.0 * PI * (220 + channel * 37) * index / config.sampleRate) +
                        0.01 * sin(index * 0.013)
                    ).toFloat()
            }
        }

    private fun compare(reference: FloatArray, candidate: FloatArray): Comparison {
        require(reference.size == candidate.size)
        var signal = 0.0
        var squaredError = 0.0
        var maxAbs = 0.0
        for (index in reference.indices) {
            val expected = reference[index].toDouble()
            val delta = candidate[index].toDouble() - expected
            signal += expected * expected
            squaredError += delta * delta
            maxAbs = maxOf(maxAbs, abs(delta))
        }
        return Comparison(
            snrDb = if (squaredError == 0.0) {
                Double.POSITIVE_INFINITY
            } else {
                10.0 * log10(signal / squaredError)
            },
            maxAbs = maxAbs,
            rmse = sqrt(squaredError / reference.size),
        )
    }

    private fun compareWaveforms(
        reference: Array<FloatArray>,
        candidate: Array<FloatArray>,
    ): Comparison {
        require(reference.size == candidate.size)
        val flattenedReference = FloatArray(reference.sumOf(FloatArray::size))
        val flattenedCandidate = FloatArray(candidate.sumOf(FloatArray::size))
        var offset = 0
        for (channel in reference.indices) {
            require(reference[channel].size == candidate[channel].size)
            reference[channel].copyInto(flattenedReference, offset)
            candidate[channel].copyInto(flattenedCandidate, offset)
            offset += reference[channel].size
        }
        return compare(flattenedReference, flattenedCandidate)
    }

    private fun readFloat32(file: File, expectedElementCount: Int): FloatArray {
        require(file.length() == expectedElementCount.toLong() * Float.SIZE_BYTES)
        FileInputStream(file).channel.use { channel ->
            val buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0L, channel.size())
                .order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(expectedElementCount).also(buffer.asFloatBuffer()::get)
        }
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

    private data class DirectManagedQualification(
        val tensorOutput: FloatArray,
        val waveformOutput: Array<FloatArray>,
        val slotComparison: Comparison,
    )

    private companion object {
        const val EXPECTED_RUNTIME_ARTIFACT = "2.2.0-bss.2"
        const val STAGING_DIRECTORY = "litert-validation-staging"
        const val REPORT_DIRECTORY = "litert-managed-validation"
        const val ARG_RUN_ID = "runId"
        const val ARG_MODEL_ID = "modelId"
        const val ARG_MODEL_PATH = "modelPath"
        const val ARG_INPUT_PATH = "inputPath"
        const val ARG_INPUT_SHA256 = "inputSha256"
        const val ARG_REFERENCE_PATH = "referencePath"
        const val ARG_REFERENCE_SHA256 = "referenceSha256"
        const val ARG_PROCESS_ABI = "processAbi"
        const val ARG_CPU_THREADS = "cpuThreads"
    }
}
