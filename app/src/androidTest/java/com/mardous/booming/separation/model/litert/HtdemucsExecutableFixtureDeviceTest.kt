package com.mardous.booming.separation.model.litert

import android.os.SystemClock
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mardous.booming.separation.model.HtdemucsHostDsp
import com.mardous.booming.separation.model.HtdemucsIstftMode
import com.mardous.booming.separation.model.HtdemucsPipelineAdapter
import com.mardous.booming.separation.model.HtdemucsRenderedTrackChunk
import com.mardous.booming.separation.model.HtdemucsStreamingOverlapAdd
import com.mardous.booming.separation.model.HtdemucsTrackWindowPlanner
import com.mardous.booming.separation.model.NativeHtdemucsPcm16
import com.mardous.booming.separation.model.contract.MultiTensorFixtureIdentity
import com.mardous.booming.separation.model.contract.MultiTensorFixtureRole
import com.mardous.booming.separation.model.contract.SourceSeparationMultiTensorExecutableContract
import com.mardous.booming.separation.model.contract.SourceSeparationMultiTensorExecutableContractLoader
import com.mardous.booming.separation.model.contract.SourceSeparationMultiTensorQualityGate
import com.mardous.booming.separation.model.contract.SourceSeparationMultiStemReleaseInstaller
import com.mardous.booming.separation.runtime.SourceSeparationRuntimeBootstrap
import java.io.File
import java.io.FileInputStream
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.MessageDigest
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

@RunWith(AndroidJUnit4::class)
class HtdemucsExecutableFixtureDeviceTest {
    @Test
    fun validateStagedExecutableFixtureBundle() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val runId = arguments.requiredSafeName(ARG_RUN_ID)
        val processAbi = currentProcessAbi()
        val reportDirectory = File(context.filesDir, REPORT_DIRECTORY).apply { mkdirs() }
        val reportFile = File(reportDirectory, "$runId.json")
        val report = JSONObject()
            .put("runId", runId)
            .put("status", "running")
            .put("deviceModel", android.os.Build.MODEL)
            .put("sdk", android.os.Build.VERSION.SDK_INT)
            .put("processAbi", processAbi)
        try {
            require(processAbi == arguments.requiredSafeName(ARG_PROCESS_ABI)) {
                "Expected process ABI ${arguments.getString(ARG_PROCESS_ABI)}, got $processAbi."
            }
            val appCommit = arguments.requiredSha(ARG_APP_COMMIT, SHA1)
            val appApkSha = File(context.applicationInfo.sourceDir).sha256()
            val testApkSha = File(instrumentation.context.applicationInfo.sourceDir).sha256()
            require(appApkSha == arguments.requiredSha(ARG_APP_APK_SHA256, SHA256)) {
                "Installed app APK differs from the declared test build."
            }
            require(testApkSha == arguments.requiredSha(ARG_TEST_APK_SHA256, SHA256)) {
                "Installed test APK differs from the declared test build."
            }
            report.put("build", JSONObject()
                .put("appCommit", appCommit)
                .put("appApkSha256", appApkSha)
                .put("testApkSha256", testApkSha)
            )
            val stagingRoot = File(context.filesDir, STAGING_DIRECTORY).canonicalFile
            val bundle = File(stagingRoot, arguments.requiredSafeName(ARG_BUNDLE_DIRECTORY))
                .canonicalFile.requireInside(stagingRoot)
            val fixtureDirectory = File(bundle, FIXTURE_DIRECTORY)
            require(fixtureDirectory.isDirectory) { "The staged fixture directory is missing." }
            val installedModelId = arguments.getString(ARG_INSTALLED_MODEL_ID)
                ?.takeIf(String::isNotBlank)
            val executable = if (installedModelId != null) {
                require(SAFE_NAME.matches(installedModelId)) { "Unsafe installed model ID." }
                resolveInstalledReleaseExecutable(installedModelId)
            } else {
                resolveStagedExecutable(context, arguments, bundle)
            }
            val contract = executable.contract
            val modelFile = executable.modelFile
            report.put("executableSource", executable.source)
                .put("contractAsset", executable.contractAsset)
                .put("contractId", contract.modelContract.contractId)
                .put("modelId", contract.modelContract.modelId)
                .put("sidecarSha256", executable.sidecarSha256)
                .put("artifact", verifyArtifact(modelFile, contract))
                .put("fixtures", verifyFixtures(fixtureDirectory, contract))

            val runtime = SourceSeparationRuntimeBootstrap.ensureLoaded(context)
            report.put(
                "runtime",
                JSONObject()
                    .put("abi", runtime.identity.abi)
                    .put("contractSchemaVersion", runtime.identity.contractSchemaVersion)
                    .put("runtimeArtifactVersion", runtime.identity.runtimeArtifactVersion)
                    .put("releaseVersion", runtime.identity.releaseVersion)
                    .put("librarySha256", runtime.identity.librarySha256),
            )
            val adapter = HtdemucsPipelineAdapter(contract.modelContract)
            var branchReport: JSONObject? = null
            val session = HtdemucsLiteRtCpuInferenceSessionFactory(
                validatedOutputObserver = { outputs ->
                    if (branchReport == null) {
                        branchReport = compareCanonicalBranches(
                            outputs.frequency,
                            outputs.waveform,
                            fixtureDirectory,
                            contract,
                        )
                    }
                },
            ).create(
                HtdemucsVerifiedArtifact(
                    file = modelFile,
                    byteSize = contract.artifact.byteSize,
                    sha256 = contract.artifact.sha256,
                ),
                contract,
            )
            try {
                report.put(
                    "canonicalWindow",
                    runCanonicalWindowGate(adapter, session, fixtureDirectory, contract),
                )
                report.put("canonicalBranches", requireNotNull(branchReport))
                val overlapAdd = runOverlapAddGate(
                    adapter,
                    session,
                    fixtureDirectory,
                    contract,
                )
                report.put(
                    "implementations",
                    JSONObject()
                        .put("inferenceImplementationId", session.implementationId)
                        .put("dspImplementationId", adapter.dspImplementationId)
                        .put("olaImplementationId", overlapAdd.implementationId)
                        .put("pcm16ImplementationId", overlapAdd.pcm16ImplementationId),
                )
                report.put("overlapAdd", overlapAdd.report)
            } finally {
                session.close()
                adapter.close()
            }
            report.put("status", "complete")
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

    private fun compareCanonicalBranches(
        frequency: FloatArray,
        waveform: FloatArray,
        fixtureDirectory: File,
        contract: SourceSeparationMultiTensorExecutableContract,
    ): JSONObject {
        val frequencyFixture = contract.fixture(MultiTensorFixtureRole.FrequencyGolden)
        val waveformFixture = contract.fixture(MultiTensorFixtureRole.WaveformGolden)
        val reconstructedFixture = contract.fixture(
            MultiTensorFixtureRole.FrequencyWaveformGolden,
        )
        val frequencyStats = compareWithFixture(
            frequency,
            File(fixtureDirectory, frequencyFixture.fileName),
        )
        val waveformStats = compareWithFixture(
            waveform,
            File(fixtureDirectory, waveformFixture.fileName),
        )
        val stemCount = contract.modelContract.stemContract.stems.size
        val reconstructed = HtdemucsHostDsp(
            HtdemucsPipelineAdapter.WINDOW_SAMPLES,
            HtdemucsIstftMode.ParallelLanes,
            4,
        ).use { dsp -> dsp.frequencyToWaveform(frequency, stemCount) }
        val reconstructedStats = compareWithFixture(
            reconstructed,
            File(fixtureDirectory, reconstructedFixture.fileName),
        )
        val reconstructedGate = SourceSeparationMultiTensorQualityGate.compareFloat(
            expected = readFloatFixture(fixtureDirectory, reconstructedFixture),
            actual = reconstructed,
        )
        require(frequencyStats.finite && waveformStats.finite && reconstructedStats.finite) {
            "Canonical HTDemucs branch fixture contains non-finite values."
        }
        return JSONObject()
            .put("frequencyTensor", frequencyStats.toJson())
            .put("waveformTensor", waveformStats.toJson())
            .put("frequencyWaveform", reconstructedStats.toJson())
            .put(
                "frequencyWaveformStrictHostGate",
                JSONObject()
                    .put("scope", "host-fixture-generation-only")
                    .put("requiredForDeviceAdmission", false)
                    .put("passes", SourceSeparationMultiTensorQualityGate.passesStrictHost(
                        reconstructedGate,
                    ))
                    .put("metrics", metricsJson(reconstructedGate)),
            )
    }

    private fun runCanonicalWindowGate(
        adapter: HtdemucsPipelineAdapter,
        session: HtdemucsCpuInferenceSession,
        fixtureDirectory: File,
        contract: SourceSeparationMultiTensorExecutableContract,
    ): JSONObject {
        val waveformFixture = contract.fixture(MultiTensorFixtureRole.WaveformInput)
        val spectrumFixture = contract.fixture(MultiTensorFixtureRole.SpectrumInput)
        val combinedFixture = contract.fixture(MultiTensorFixtureRole.CombinedGolden)
        val waveform = readFloatFixture(fixtureDirectory, waveformFixture)
        val started = SystemClock.elapsedRealtimeNanos()
        val inputs = adapter.prepareWindow(waveform)
        val stftNanos = SystemClock.elapsedRealtimeNanos() - started
        val stftStats = compareWithFixture(
            inputs.spectrum,
            File(fixtureDirectory, spectrumFixture.fileName),
        )
        require(stftStats.finite && stftStats.maxAbsoluteError <= HOST_STFT_MAX_ERROR) {
            "Product HTDemucs STFT differs from the frozen host fixture."
        }
        val inferenceStarted = SystemClock.elapsedRealtimeNanos()
        val stemSet = session.run(inputs)
        val inferenceNanos = SystemClock.elapsedRealtimeNanos() - inferenceStarted
        require(stemSet.orderedStemIds == contract.modelContract.stemContract.stems.map { it.stemId })
        val combinedStats = compareWithFixture(
            stemSet.planarSamples,
            File(fixtureDirectory, combinedFixture.fileName),
        )
        val perStemGate = SourceSeparationMultiTensorQualityGate.comparePerStem(
            expected = readFloatFixture(fixtureDirectory, combinedFixture),
            actual = stemSet.planarSamples,
            stemCount = contract.modelContract.stemContract.stems.size,
        )
        require(perStemGate.all { it.passes }) {
            "Canonical combined output failed the energy-aware per-stem quality gate: " +
                perStemGate.toJson(contract)
        }
        require(combinedStats.finite) { "Canonical HTDemucs output contains non-finite values." }
        return JSONObject()
            .put("stftNanos", stftNanos)
            .put("inferenceAndReconstructionNanos", inferenceNanos)
            .put("stft", stftStats.toJson())
            .put("combined", combinedStats.toJson())
            .put("energyAwareStemGate", perStemGate.toJson(contract))
            .put(
                "deviceAdmission",
                JSONObject()
                    .put("scope", "android-product-execution")
                    .put("required", true)
                    .put("finite", combinedStats.finite)
                    .put("energyAwareStemGatePasses", perStemGate.all { it.passes }),
            )
    }

    private fun runOverlapAddGate(
        adapter: HtdemucsPipelineAdapter,
        session: HtdemucsCpuInferenceSession,
        fixtureDirectory: File,
        contract: SourceSeparationMultiTensorExecutableContract,
    ): OverlapAddGateResult {
        val inputFixture = contract.fixture(MultiTensorFixtureRole.OlaMixInput)
        val outputFixture = contract.fixture(MultiTensorFixtureRole.OlaCombinedGolden)
        val track = readFloatFixture(fixtureDirectory, inputFixture)
        val trackSamples = inputFixture.shape.last()
        val stemCount = contract.modelContract.stemContract.stems.size
        val planeCount = stemCount * HtdemucsPipelineAdapter.CHANNEL_COUNT
        val normalization = adapter.globalNormalization(track)
        val normalized = adapter.normalizeTrack(track, normalization)
        val windows = HtdemucsTrackWindowPlanner.plans(trackSamples)
        val result = FloatArray(planeCount * trackSamples)
        var overlapAddImplementationId = "unavailable"
        var encodedPcm16Bytes = 0L
        val started = SystemClock.elapsedRealtimeNanos()
        HtdemucsStreamingOverlapAdd(
            orderedStemIds = adapter.orderedStemIds,
            trackSamples = trackSamples,
            normalization = normalization,
        ).use { overlapAdd ->
            overlapAddImplementationId = overlapAdd.implementationId
            windows.forEach { window ->
                val padded = HtdemucsTrackWindowPlanner.paddedWindow(normalized, window)
                val chunk = overlapAdd.addWindow(
                    window,
                    session.run(adapter.prepareWindow(padded)),
                )
                require(chunk.startFrame + chunk.frameCount <= trackSamples)
                repeat(planeCount) { plane ->
                    val sourceOffset = plane * chunk.planeStride
                    chunk.planarSamples.copyInto(
                        destination = result,
                        destinationOffset = plane * trackSamples + chunk.startFrame,
                        startIndex = sourceOffset,
                        endIndex = sourceOffset + chunk.frameCount,
                    )
                }
                repeat(stemCount) { stemOrder ->
                    val pcm16 = ByteArray(Math.multiplyExact(chunk.frameCount, PCM16_STEREO_BYTES))
                    val planeBase = stemOrder * HtdemucsPipelineAdapter.CHANNEL_COUNT *
                        chunk.planeStride
                    val encoded = NativeHtdemucsPcm16.encodePlanar(
                        input = chunk.planarSamples,
                        leftOffset = planeBase,
                        rightOffset = planeBase + chunk.planeStride,
                        frameCount = chunk.frameCount,
                        output = pcm16,
                    )
                    require(encoded == pcm16.size) {
                        "Native HTDemucs PCM encoder returned an incomplete chunk."
                    }
                    require(pcm16.contentEquals(scalarPcm16(chunk, stemOrder))) {
                        "Native HTDemucs PCM differs from scalar product quantization."
                    }
                    encodedPcm16Bytes += encoded
                }
            }
            overlapAdd.finish()
        }
        require(encodedPcm16Bytes ==
            trackSamples.toLong() * PCM16_STEREO_BYTES * stemCount)
        val elapsedNanos = SystemClock.elapsedRealtimeNanos() - started
        val stats = compareWithFixture(result, File(fixtureDirectory, outputFixture.fileName))
        require(stats.finite) { "HTDemucs OLA output contains non-finite values." }
        require(stats.signalToNoiseDb >= OLA_MINIMUM_SNR_DB &&
            stats.correlation >= OLA_MINIMUM_CORRELATION &&
            stats.maxAbsoluteError <= OLA_MAXIMUM_ABSOLUTE_ERROR
        ) { "HTDemucs native OLA differs from the frozen host fixture: $stats" }
        val pcm16ImplementationId = NativeHtdemucsPcm16.implementationId()
        return OverlapAddGateResult(
            implementationId = overlapAddImplementationId,
            pcm16ImplementationId = pcm16ImplementationId,
            report = JSONObject()
                .put("windowCount", windows.size)
                .put("elapsedNanos", elapsedNanos)
                .put("encodedPcm16Bytes", encodedPcm16Bytes)
                .put("pcm16ScalarParity", true)
                .put("comparison", stats.toJson()),
        )
    }

    private fun scalarPcm16(
        chunk: HtdemucsRenderedTrackChunk,
        stemOrder: Int,
    ): ByteArray {
        val output = ByteArray(Math.multiplyExact(chunk.frameCount, PCM16_STEREO_BYTES))
        val planeBase = stemOrder * HtdemucsPipelineAdapter.CHANNEL_COUNT * chunk.planeStride
        var byteOffset = 0
        repeat(chunk.frameCount) { frame ->
            repeat(HtdemucsPipelineAdapter.CHANNEL_COUNT) { channel ->
                val sample = chunk.planarSamples[planeBase + channel * chunk.planeStride + frame]
                val pcm = (sample.coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                output[byteOffset++] = (pcm and 0xff).toByte()
                output[byteOffset++] = ((pcm ushr 8) and 0xff).toByte()
            }
        }
        return output
    }

    private fun verifyArtifact(
        file: File,
        contract: SourceSeparationMultiTensorExecutableContract,
    ): JSONObject {
        require(file.length() == contract.artifact.byteSize) { "TFLite byte size mismatch." }
        val actualSha = file.sha256()
        require(actualSha == contract.artifact.sha256) { "TFLite SHA-256 mismatch." }
        return JSONObject()
            .put("fileName", file.name)
            .put("byteSize", file.length())
            .put("sha256", actualSha)
    }

    private fun resolveInstalledReleaseExecutable(modelId: String): ExecutableUnderTest {
        val installed = requireNotNull(
            GlobalContext.get().get<SourceSeparationMultiStemReleaseInstaller>()
                .installed(modelId),
        ) { "The requested Release model is not installed." }
        val contract = installed.sidecarFile.bufferedReader().use { reader ->
            SourceSeparationMultiTensorExecutableContractLoader.load(reader.readText())
        }
        require(contract.modelContract.modelId == installed.modelId &&
            contract.modelContract.contractId == installed.contractId &&
            contract.modelContract.pipelineContract.pipelineId == installed.pipelineId &&
            contract.artifact.fileName == installed.modelFile.name &&
            contract.artifact.byteSize == installed.modelByteSize &&
            contract.artifact.sha256.equals(installed.modelSha256, ignoreCase = true)
        ) { "Installed Release record, sidecar, and artifact identity differ." }
        return ExecutableUnderTest(
            source = "installed-release",
            contractAsset = null,
            contract = contract,
            modelFile = installed.modelFile,
            sidecarSha256 = installed.sidecarFile.sha256(),
        )
    }

    private fun resolveStagedExecutable(
        context: android.content.Context,
        arguments: android.os.Bundle,
        bundle: File,
    ): ExecutableUnderTest {
        val contractAsset = arguments.requiredSafeName(ARG_CONTRACT_ASSET)
        val contract = context.assets.open("source-separation/research-contracts/$contractAsset")
            .bufferedReader().use { reader ->
                SourceSeparationMultiTensorExecutableContractLoader.load(reader.readText())
            }
        return ExecutableUnderTest(
            source = "staged-diagnostic",
            contractAsset = contractAsset,
            contract = contract,
            modelFile = File(bundle, contract.artifact.fileName),
            sidecarSha256 = null,
        )
    }

    private fun verifyFixtures(
        directory: File,
        contract: SourceSeparationMultiTensorExecutableContract,
    ): JSONObject = JSONObject().also { result ->
        contract.fixtures.forEach { identity ->
            val file = File(directory, identity.fileName)
            require(file.isFile && file.length() == identity.byteSize) {
                "Fixture ${identity.role} byte size mismatch."
            }
            val sha = file.sha256()
            require(sha == identity.sha256) { "Fixture ${identity.role} SHA-256 mismatch." }
            result.put(
                identity.role.name,
                JSONObject()
                    .put("fileName", identity.fileName)
                    .put("byteSize", identity.byteSize)
                    .put("sha256", sha),
            )
        }
    }

    private fun readFloatFixture(
        directory: File,
        fixture: MultiTensorFixtureIdentity,
    ): FloatArray {
        val file = File(directory, fixture.fileName)
        val elementCount = fixture.shape.fold(1L, Math::multiplyExact)
        require(elementCount <= Int.MAX_VALUE && file.length() == elementCount * Float.SIZE_BYTES)
        FileInputStream(file).channel.use { channel ->
            val floats = channel.map(FileChannel.MapMode.READ_ONLY, 0L, file.length())
                .order(ByteOrder.LITTLE_ENDIAN)
                .asFloatBuffer()
            return FloatArray(elementCount.toInt()).also(floats::get)
        }
    }

    private fun compareWithFixture(actual: FloatArray, expectedFile: File): ErrorStats {
        require(expectedFile.length() == actual.size.toLong() * Float.SIZE_BYTES)
        FileInputStream(expectedFile).channel.use { channel ->
            val expected = channel.map(FileChannel.MapMode.READ_ONLY, 0L, expectedFile.length())
                .order(ByteOrder.LITTLE_ENDIAN)
                .asFloatBuffer()
            var finite = true
            var maxAbsoluteError = 0.0
            var errorSquareSum = 0.0
            var signalSquareSum = 0.0
            var candidateSquareSum = 0.0
            var dotProduct = 0.0
            actual.forEach { actualFloat ->
                val reference = expected.get().toDouble()
                val candidate = actualFloat.toDouble()
                finite = finite && reference.isFinite() && candidate.isFinite()
                val error = candidate - reference
                maxAbsoluteError = maxOf(maxAbsoluteError, kotlin.math.abs(error))
                errorSquareSum += error * error
                signalSquareSum += reference * reference
                candidateSquareSum += candidate * candidate
                dotProduct += reference * candidate
            }
            val count = actual.size.toDouble()
            val errorRms = sqrt(errorSquareSum / count)
            val signalRms = sqrt(signalSquareSum / count)
            return ErrorStats(
                finite = finite,
                maxAbsoluteError = maxAbsoluteError,
                rootMeanSquareError = errorRms,
                signalToNoiseDb = 20.0 * log10(
                    maxOf(signalRms, MIN_RMS) / maxOf(errorRms, MIN_RMS),
                ),
                correlation = dotProduct /
                    maxOf(sqrt(signalSquareSum * candidateSquareSum), MIN_RMS),
            )
        }
    }

    private fun SourceSeparationMultiTensorExecutableContract.fixture(
        role: MultiTensorFixtureRole,
    ) = fixtures.single { it.role == role }

    private fun android.os.Bundle.requiredSafeName(key: String): String =
        requireNotNull(getString(key)) { "Missing instrumentation argument $key." }
            .also { value ->
                require(SAFE_NAME.matches(value)) { "Unsafe instrumentation argument $key." }
            }

    private fun android.os.Bundle.requiredSha(key: String, pattern: Regex): String =
        requireNotNull(getString(key)) { "Missing instrumentation argument $key." }
            .lowercase()
            .also { value -> require(pattern.matches(value)) { "Invalid $key." } }

    private fun currentProcessAbi(): String {
        val abis = if (Process.is64Bit()) {
            android.os.Build.SUPPORTED_64_BIT_ABIS
        } else {
            android.os.Build.SUPPORTED_32_BIT_ABIS
        }
        return requireNotNull(abis.firstOrNull()) { "The current process has no reported ABI." }
    }

    private fun File.requireInside(root: File): File {
        require(path.startsWith(root.path + File.separator)) { "Staged path escapes its root." }
        return this
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().buffered().use { input ->
            val buffer = ByteArray(HASH_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private data class ErrorStats(
        val finite: Boolean,
        val maxAbsoluteError: Double,
        val rootMeanSquareError: Double,
        val signalToNoiseDb: Double,
        val correlation: Double,
    ) {
        fun toJson() = JSONObject()
            .put("finite", finite)
            .put("maxAbsoluteError", maxAbsoluteError)
            .put("rootMeanSquareError", rootMeanSquareError)
            .put("signalToNoiseDb", signalToNoiseDb)
            .put("correlation", correlation)
    }

    private data class ExecutableUnderTest(
        val source: String,
        val contractAsset: String?,
        val contract: SourceSeparationMultiTensorExecutableContract,
        val modelFile: File,
        val sidecarSha256: String?,
    )

    private data class OverlapAddGateResult(
        val implementationId: String,
        val pcm16ImplementationId: String,
        val report: JSONObject,
    )

    private fun List<com.mardous.booming.separation.model.contract.SourceSeparationMultiTensorStemMetrics>.toJson(
        contract: SourceSeparationMultiTensorExecutableContract,
    ): JSONObject {
        val stems = JSONArray()
        forEach { result ->
            stems.put(
                JSONObject()
                    .put("stemIndex", result.stemIndex)
                    .put(
                        "stemId",
                        contract.modelContract.stemContract.stems[result.stemIndex].stemId,
                    )
                    .put("lowEnergy", result.lowEnergy)
                    .put("passes", result.passes)
                    .put("metrics", metricsJson(result.metrics)),
            )
        }
        return JSONObject()
            .put("thresholdRevision", "htdemucs-phase6-thresholds-v2")
            .put("allPass", all { it.passes })
            .put("stems", stems)
    }

    private fun metricsJson(
        metrics: com.mardous.booming.separation.model.contract.SourceSeparationMultiTensorFloatMetrics,
    ) = JSONObject()
        .put("elementCount", metrics.elementCount)
        .put("finite", metrics.finite)
        .put("maxAbsoluteError", metrics.maxAbsoluteError)
        .put("rootMeanSquareError", metrics.rootMeanSquareError)
        .put("signalToNoiseDb", metrics.signalToNoiseDb)
        .put("cosineSimilarity", metrics.cosineSimilarity)
        .put("referenceRms", metrics.referenceRms)

    private companion object {
        const val ARG_RUN_ID = "htdemucsRunId"
        const val ARG_PROCESS_ABI = "htdemucsProcessAbi"
        const val ARG_CONTRACT_ASSET = "htdemucsContractAsset"
        const val ARG_BUNDLE_DIRECTORY = "htdemucsBundleDirectory"
        const val ARG_INSTALLED_MODEL_ID = "htdemucsInstalledModelId"
        const val ARG_APP_COMMIT = "htdemucsAppCommit"
        const val ARG_APP_APK_SHA256 = "htdemucsAppApkSha256"
        const val ARG_TEST_APK_SHA256 = "htdemucsTestApkSha256"
        const val STAGING_DIRECTORY = "htdemucs-fixture-staging"
        const val FIXTURE_DIRECTORY = "fixtures"
        const val REPORT_DIRECTORY = "htdemucs-fixture-reports"
        const val HASH_BUFFER_BYTES = 1024 * 1024
        const val PCM16_STEREO_BYTES = 4
        const val HOST_STFT_MAX_ERROR = 1e-5
        const val OLA_MINIMUM_SNR_DB = 60.0
        const val OLA_MINIMUM_CORRELATION = 0.9999
        const val OLA_MAXIMUM_ABSOLUTE_ERROR = 2.5e-4
        const val MIN_RMS = 1e-30
        val SAFE_NAME = Regex("^[A-Za-z0-9._-]+$")
        val SHA1 = Regex("^[0-9a-f]{40}$")
        val SHA256 = Regex("^[0-9a-f]{64}$")
    }
}
