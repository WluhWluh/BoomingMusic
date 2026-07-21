package com.mardous.booming.separation.model.litert

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mardous.booming.separation.model.MdxCompatibilityDecision
import com.mardous.booming.separation.model.MdxCompatibilityPolicy
import com.mardous.booming.separation.model.MdxExecutionProfile
import com.mardous.booming.separation.model.MdxInferenceBackend
import com.mardous.booming.separation.model.MdxInferenceSession
import com.mardous.booming.separation.model.MdxModelArtifact
import com.mardous.booming.separation.model.MdxRuntimeAbi
import com.mardous.booming.separation.model.MdxRuntimePlatform
import com.mardous.booming.separation.model.MdxRuntimeSettings
import com.mardous.booming.separation.model.MdxRuntimeSupportStatus
import com.mardous.booming.separation.model.MdxSpectrogram
import com.mardous.booming.separation.model.ReusableMdxInferenceSessionProvider
import com.mardous.booming.separation.model.mapMdxStemWaveforms
import com.mardous.booming.separation.model.contract.SourceSeparationModelContract
import com.mardous.booming.separation.model.contract.SourceSeparationModelContractValidator
import com.mardous.booming.separation.model.contract.SourceSeparationModelMetadata
import com.mardous.booming.separation.model.contract.toMdxExecutionProfile
import dalvik.system.BaseDexClassLoader
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipFile
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

@RunWith(AndroidJUnit4::class)
class MdxLiteRtCpuValidationTest {
    @Test
    fun validateStagedModel() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val runId = arguments.requiredString(ARG_RUN_ID).requireSafeName("run ID")
        val reportFile = validationReportFile(context, runId)
        val report = baseReport(context, runId, arguments)

        try {
            val modelId = arguments.requiredString(ARG_MODEL_ID)
            val contract = bundledContract(context, modelId)
            val contractProfile = contract.toMdxExecutionProfile()
            val expectedRuntimeAbi = arguments.requiredString(ARG_PROCESS_ABI)
            val actualProcessAbi = currentProcessAbi().androidName
            val runtimeAbi = installedLiteRtRuntimeAbi(context)
            require(runtimeAbi.androidName == expectedRuntimeAbi) {
                "Expected $expectedRuntimeAbi LiteRT library, got ${runtimeAbi.androidName}."
            }
            report.put("contractId", contract.contractId)
                .put("contractConversionRevision", contract.conversion.revision)
                .put("modelId", modelId)
                .put("process", processReport(context, actualProcessAbi, runtimeAbi.androidName))

            val resourceProbe = arguments.getString(ARG_ALLOW_UNSUPPORTED_RESOURCE_PROBE)
                .toBoolean()
            require(!resourceProbe || !arguments.getString(ARG_PREFLIGHT_ONLY).toBoolean()) {
                "An unsupported resource probe cannot also be preflight-only."
            }
            val (profile, compatibilityOverride) = if (resourceProbe) {
                internalResourceProbeProfile(contractProfile, runtimeAbi)
            } else {
                contractProfile to null
            }
            report.put("compatibilityOverride", compatibilityOverride ?: JSONObject.NULL)

            if (arguments.getString(ARG_PREFLIGHT_ONLY).toBoolean()) {
                validateUnsupportedPreflight(profile, runtimeAbi, report)
            } else {
                validateParityRun(context, arguments, contract, profile, runtimeAbi, report)
            }
            report.put(
                "process",
                processReport(context, actualProcessAbi, runtimeAbi.androidName),
            )
            report.put("status", "complete")
            reportFile.writeText(report.toString(2))
        } catch (error: Throwable) {
            runCatching {
                processReport(
                    context,
                    currentProcessAbi().androidName,
                    installedLiteRtRuntimeAbi(context).androidName,
                )
            }.getOrNull()?.let { process -> report.put("process", process) }
            report.put("status", "error")
                .put("errorType", error.javaClass.name)
                .put("errorMessage", error.message.orEmpty())
                .put("stackTrace", error.stackTraceToString())
                .put("memoryAfterFailureCleanup", memorySnapshot())
            reportFile.writeText(report.toString(2))
            throw error
        }
    }

    private fun validateParityRun(
        context: Context,
        arguments: android.os.Bundle,
        contract: SourceSeparationModelContract,
        profile: MdxExecutionProfile,
        runtimeAbi: MdxRuntimeAbi,
        report: JSONObject,
    ) {
        val stagingRoot = context.filesDir.resolve(STAGING_DIRECTORY).canonicalFile
        val modelFile = arguments.requiredStagedFile(ARG_MODEL_PATH, stagingRoot)
        val inputFile = arguments.requiredStagedFile(ARG_INPUT_PATH, stagingRoot)
        val referenceFile = arguments.requiredStagedFile(ARG_REFERENCE_PATH, stagingRoot)
        val modelIdentity = modelFile.identity()
        profile.validateArtifact(modelIdentity)
        validateFixtureFile(
            inputFile,
            arguments.requiredString(ARG_INPUT_SHA256),
            profile.inputTensor.elementCount,
            "input",
        )
        validateFixtureFile(
            referenceFile,
            arguments.requiredString(ARG_REFERENCE_SHA256),
            profile.outputTensor.elementCount,
            "reference",
        )
        val input = readFloat32(inputFile, profile.inputTensor.elementCount)
        val reference = readFloat32(referenceFile, profile.outputTensor.elementCount)
        val processBefore = memorySnapshot()
        val processorCountOverride = arguments.getString(ARG_PROCESSOR_COUNT_OVERRIDE)
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
        report.put("fixture", arguments.requiredString(ARG_FIXTURE_NAME))
            .put("artifact", modelIdentity.toJson())
            .put("input", inputFile.fixtureJson(arguments.requiredString(ARG_INPUT_SHA256)))
            .put("reference", referenceFile.fixtureJson(arguments.requiredString(ARG_REFERENCE_SHA256)))
            .put("processorCountOverride", processorCountOverride ?: JSONObject.NULL)
            .put("memoryBefore", processBefore)
        val factory = if (processorCountOverride == null) {
            MdxLiteRtCpuInferenceSessionFactory(
                platformProvider = { MdxRuntimePlatform(Build.VERSION.SDK_INT, runtimeAbi) },
                compatibilityPolicy = MdxCompatibilityPolicy.AllowUntestedInternal,
            )
        } else {
            MdxLiteRtCpuInferenceSessionFactory(
                platformProvider = { MdxRuntimePlatform(Build.VERSION.SDK_INT, runtimeAbi) },
                compatibilityPolicy = MdxCompatibilityPolicy.AllowUntestedInternal,
                availableProcessors = { processorCountOverride },
            )
        }
        val provider = ReusableMdxInferenceSessionProvider(factory)
        var setupMs = 0L
        var firstInferenceMs = 0L
        var reusedInferenceMs = 0L
        var firstOutput: FloatArray? = null
        var reusedOutput: FloatArray? = null
        var diagnostics = ""
        var reusedSameSession = false
        var cancellationReport: JSONObject? = null
        var replacementReport: JSONObject? = null
        var memoryWithSession: JSONObject? = null
        try {
            val setupStarted = SystemClock.elapsedRealtime()
            val firstLease = provider.acquire(modelIdentity, profile, MdxRuntimeSettings())
            setupMs = SystemClock.elapsedRealtime() - setupStarted
            val firstSession = firstLease.session
            diagnostics = firstSession.diagnostics.toDisplayText()
            report.put("runtimeDiagnostics", diagnostics)
                .put("setupWallMs", setupMs)
                .put("memoryAfterSetup", memorySnapshot())
                .put(
                    "processWithSession",
                    processReport(
                        context,
                        currentProcessAbi().androidName,
                        runtimeAbi.androidName,
                    ),
                )
            val firstStarted = SystemClock.elapsedRealtime()
            try {
                firstOutput = firstSession.run(input).copyOf()
                firstInferenceMs = SystemClock.elapsedRealtime() - firstStarted
            } catch (error: Throwable) {
                report.put(
                    "firstInferenceFailureWallMs",
                    SystemClock.elapsedRealtime() - firstStarted,
                ).put("memoryAtFirstInferenceFailure", memorySnapshot())
                throw error
            } finally {
                firstLease.close()
            }

            val reusedLease = provider.acquire(modelIdentity, profile, MdxRuntimeSettings())
            reusedSameSession = firstSession === reusedLease.session
            assertSame("An identical model/profile must reuse its session", firstSession, reusedLease.session)
            try {
                val reusedStarted = SystemClock.elapsedRealtime()
                reusedOutput = reusedLease.session.run(input).copyOf()
                reusedInferenceMs = SystemClock.elapsedRealtime() - reusedStarted
                val beforeInvocationCanceled = runCatching {
                    reusedLease.session.run(input) { true }
                }.exceptionOrNull()
                require(beforeInvocationCanceled is CancellationException) {
                    "Cancellation before invocation was not propagated."
                }
                if (arguments.getString(ARG_TEST_IN_FLIGHT_CANCELLATION).toBoolean()) {
                    cancellationReport = validateInFlightCancellation(reusedLease.session, input)
                }
                memoryWithSession = memorySnapshot()
            } finally {
                reusedLease.close()
            }

            arguments.getString(ARG_SECONDARY_MODEL_ID)?.takeIf(String::isNotBlank)?.let { secondaryId ->
                replacementReport = validateSessionReplacement(
                    context = context,
                    arguments = arguments,
                    provider = provider,
                    firstSession = firstSession,
                    secondaryModelId = secondaryId,
                    stagingRoot = stagingRoot,
                )
            }
        } finally {
            provider.close()
        }

        val output = requireNotNull(reusedOutput)
        val comparison = compareOutputs(reference, output)
        val reuseComparison = compareOutputs(requireNotNull(firstOutput), output)
        val thresholds = parityThresholds(contract.modelId)
        val stemValidation = validateStemMapping(input, output, profile)
        report.put("firstInferenceWallMs", firstInferenceMs)
            .put("reusedInferenceWallMs", reusedInferenceMs)
            .put("reusedSameSession", reusedSameSession)
            .put("comparisonToOrt", comparison.toJson())
            .put("reuseComparison", reuseComparison.toJson())
            .put("thresholds", thresholds.toJson())
            .put("stemValidation", stemValidation.toJson())
            .put("cancellation", cancellationReport ?: JSONObject.NULL)
            .put("replacement", replacementReport ?: JSONObject.NULL)
            .put("memoryWithSession", requireNotNull(memoryWithSession))
            .put("memoryAfter", memorySnapshot())
        require(comparison.snrDb >= thresholds.minimumSnrDb) {
            "SNR ${comparison.snrDb} is below ${thresholds.minimumSnrDb}."
        }
        require(comparison.cosineSimilarity >= thresholds.minimumCosine) {
            "Cosine ${comparison.cosineSimilarity} is below ${thresholds.minimumCosine}."
        }
        require(comparison.maxAbsError <= thresholds.maximumAbsoluteError) {
            "Maximum error ${comparison.maxAbsError} exceeds ${thresholds.maximumAbsoluteError}."
        }
        require(stemValidation.reconstructionMaxAbsError <= STEM_RECONSTRUCTION_MAX_ERROR) {
            "Stem reconstruction error ${stemValidation.reconstructionMaxAbsError} exceeds " +
                STEM_RECONSTRUCTION_MAX_ERROR
        }
    }

    private fun validateUnsupportedPreflight(
        profile: MdxExecutionProfile,
        runtimeAbi: MdxRuntimeAbi,
        report: JSONObject,
    ) {
        val allocator = RejectingAllocator()
        val factory = MdxLiteRtCpuInferenceSessionFactory(
            platformProvider = { MdxRuntimePlatform(Build.VERSION.SDK_INT, runtimeAbi) },
            compatibilityPolicy = MdxCompatibilityPolicy.AllowUntestedInternal,
            sessionAllocator = allocator,
        )
        val artifact = MdxModelArtifact(
            file = File(profile.expectedFileName),
            byteSize = requireNotNull(profile.expectedByteSize),
            sha256 = requireNotNull(profile.expectedSha256),
        )
        val processBefore = memorySnapshot()
        val error = runCatching {
            factory.create(artifact, profile, MdxRuntimeSettings())
        }.exceptionOrNull()
        require(error != null) { "Unsupported preflight unexpectedly created a session." }
        require(allocator.createCount == 0) {
            "Unsupported preflight reached the native session allocator."
        }
        report.put("preflightOnly", true)
            .put("preflightErrorType", error.javaClass.name)
            .put("preflightErrorMessage", error.message.orEmpty())
            .put("nativeAllocatorCalls", allocator.createCount)
            .put("modelFileExists", artifact.file.exists())
            .put("memoryBefore", processBefore)
            .put("memoryAfter", memorySnapshot())
    }

    private fun internalResourceProbeProfile(
        profile: MdxExecutionProfile,
        runtimeAbi: MdxRuntimeAbi,
    ): Pair<MdxExecutionProfile, JSONObject> {
        val original = profile.runtimeCompatibility.singleOrNull {
            it.abi == runtimeAbi && it.backend == MdxInferenceBackend.LiteRtCpu
        } ?: error("No LiteRT CPU compatibility record exists for ${runtimeAbi.androidName}.")
        require(original.status == MdxRuntimeSupportStatus.Unsupported) {
            "A resource probe override requires an explicitly unsupported target."
        }
        val probeEvidence = "Internal resource probe only; original evidence: ${original.evidence}"
        val overridden = original.copy(
            status = MdxRuntimeSupportStatus.Untested,
            evidence = probeEvidence,
        )
        val probeProfile = profile.copy(
            runtimeCompatibility = profile.runtimeCompatibility.map { record ->
                if (record === original) overridden else record
            },
        )
        val report = JSONObject()
            .put("scope", "androidTest-only")
            .put("abi", original.abi.androidName)
            .put("backend", original.backend.name)
            .put("originalStatus", original.status.name)
            .put("originalEvidence", original.evidence)
            .put("effectiveStatus", overridden.status.name)
        return probeProfile to report
    }

    private fun validateInFlightCancellation(
        session: MdxInferenceSession,
        input: FloatArray,
    ): JSONObject {
        val cancel = AtomicBoolean(false)
        val invocationBoundary = CountDownLatch(1)
        val cancellationChecks = AtomicInteger(0)
        val failure = AtomicReference<Throwable?>()
        val started = SystemClock.elapsedRealtime()
        val worker = Thread({
            try {
                session.run(input) {
                    val count = cancellationChecks.incrementAndGet()
                    if (count == 2) invocationBoundary.countDown()
                    cancel.get()
                }
            } catch (error: Throwable) {
                failure.set(error)
            }
        }, "litert-cancellation-validation")
        worker.start()
        require(invocationBoundary.await(30, TimeUnit.SECONDS)) {
            "Inference did not reach the invocation boundary."
        }
        cancel.set(true)
        worker.join(TimeUnit.MINUTES.toMillis(30))
        require(!worker.isAlive) { "Canceled inference did not return within 30 minutes." }
        require(failure.get() is CancellationException) {
            "In-flight cancellation did not discard the runtime output: ${failure.get()}"
        }
        return JSONObject()
            .put("tested", true)
            .put("checks", cancellationChecks.get())
            .put("elapsedWallMs", SystemClock.elapsedRealtime() - started)
            .put("result", failure.get()?.javaClass?.name)
    }

    private fun validateSessionReplacement(
        context: Context,
        arguments: android.os.Bundle,
        provider: ReusableMdxInferenceSessionProvider,
        firstSession: MdxInferenceSession,
        secondaryModelId: String,
        stagingRoot: File,
    ): JSONObject {
        val secondaryContract = bundledContract(context, secondaryModelId)
        val secondaryProfile = secondaryContract.toMdxExecutionProfile()
        val secondaryFile = arguments.requiredStagedFile(ARG_SECONDARY_MODEL_PATH, stagingRoot)
        val secondaryIdentity = secondaryFile.identity()
        secondaryProfile.validateArtifact(secondaryIdentity)
        val started = SystemClock.elapsedRealtime()
        val lease = provider.acquire(secondaryIdentity, secondaryProfile, MdxRuntimeSettings())
        val replacement = lease.session
        assertNotSame("A different artifact/profile must replace the session", firstSession, replacement)
        lease.close()
        return JSONObject()
            .put("modelId", secondaryModelId)
            .put("contractId", secondaryContract.contractId)
            .put("artifact", secondaryIdentity.toJson())
            .put("differentSession", true)
            .put("setupWallMs", SystemClock.elapsedRealtime() - started)
            .put("diagnostics", replacement.diagnostics.toDisplayText())
    }

    private fun validateStemMapping(
        inputNchw: FloatArray,
        outputNchw: FloatArray,
        profile: MdxExecutionProfile,
    ): StemValidation {
        val spectrogram = MdxSpectrogram(profile.dspConfig)
        val mixture = spectrogram.tensorToWaveform(inputNchw)
        val rawModelOutput = spectrogram.tensorToWaveform(outputNchw)
        val mapped = mapMdxStemWaveforms(
            mixture = mixture,
            rawModelOutput = rawModelOutput,
            modelOutputScale = profile.modelOutputScale,
            modelOutputStem = profile.modelOutputStem,
        )
        var maxError = 0.0
        for (channel in mixture.indices) {
            for (frame in mixture[channel].indices) {
                val reconstructed = mapped.vocals[channel][frame] + mapped.instrumental[channel][frame]
                maxError = maxOf(maxError, abs(reconstructed - mixture[channel][frame]).toDouble())
            }
        }
        return StemValidation(
            modelOutputStem = profile.modelOutputStem.name,
            modelOutputScale = profile.modelOutputScale,
            reconstructionMaxAbsError = maxError,
        )
    }

    private fun bundledContract(context: Context, modelId: String): SourceSeparationModelContract {
        val catalog = SourceSeparationModelMetadata.loadBundledCatalog(context)
        return SourceSeparationModelContractValidator.resolveActivationContract(catalog, modelId)
    }

    private fun currentProcessAbi(): MdxRuntimeAbi {
        val osArch = System.getProperty("os.arch").orEmpty().lowercase()
        return when {
            osArch.contains("x86_64") || osArch.contains("amd64") -> MdxRuntimeAbi.X86_64
            osArch.contains("86") -> MdxRuntimeAbi.X86
            osArch.contains("aarch64") || osArch.contains("arm64") -> MdxRuntimeAbi.Arm64V8a
            osArch.contains("arm") -> MdxRuntimeAbi.ArmeabiV7a
            else -> error("Unsupported process os.arch: $osArch")
        }.also { abi ->
            require(Process.is64Bit() == (abi == MdxRuntimeAbi.Arm64V8a || abi == MdxRuntimeAbi.X86_64)) {
                "Process bitness disagrees with os.arch=$osArch."
            }
        }
    }

    private fun baseReport(
        context: Context,
        runId: String,
        arguments: android.os.Bundle,
    ) = JSONObject()
        .put("schemaVersion", 1)
        .put("runId", runId)
        .put("status", "running")
        .put("appCommit", arguments.getString(ARG_APP_COMMIT).orEmpty())
        .put("catalogSha256", SourceSeparationModelMetadata.CATALOG_SHA256)
        .put("contractSchemaVersion", SourceSeparationModelContractValidator.CONTRACT_SCHEMA_VERSION)
        .put("pipelineVersion", SourceSeparationModelContractValidator.PIPELINE_VERSION)
        .put("runtime", "LiteRT 2.1.5")
        .put("packageName", context.packageName)
        .put("startedAtEpochMs", System.currentTimeMillis())

    private fun processReport(
        context: Context,
        processAbi: String,
        runtimeAbi: String,
    ): JSONObject {
        val applicationInfo = context.applicationInfo
        val deviceMemory = ActivityManager.MemoryInfo().also { memory ->
            context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        }
        val apkPaths = listOfNotNull(applicationInfo.sourceDir) +
            applicationInfo.splitSourceDirs.orEmpty()
        val apkInventories = JSONArray()
        for (path in apkPaths) {
            val entries = ZipFile(path).use { archive ->
                archive.entries().asSequence()
                    .map { it.name }
                    .filter { it.startsWith("lib/") && it.contains("/libLiteRt") }
                    .sorted()
                    .toList()
            }
            apkInventories.put(
                JSONObject()
                    .put("path", path)
                    .put("liteRtEntries", JSONArray(entries))
            )
        }
        val loadedRuntimeMaps = File("/proc/self/maps").useLines { lines ->
            lines.filter { it.contains("liblitert", ignoreCase = true) }.toList()
        }
        val extractedRuntime = File(applicationInfo.nativeLibraryDir, "libLiteRt.so")
            .takeIf(File::isFile)
        val classLoaderRuntime = (context.classLoader as? BaseDexClassLoader)
            ?.findLibrary("LiteRt")
        return JSONObject()
            .put("supportedAbis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("osArch", System.getProperty("os.arch").orEmpty())
            .put("is64Bit", Process.is64Bit())
            .put("resolvedProcessAbi", processAbi)
            .put("selectedLiteRtRuntimeAbi", runtimeAbi)
            .put("nativeLibraryDir", applicationInfo.nativeLibraryDir)
            .put(
                "extractedRuntime",
                extractedRuntime?.let { runtime ->
                    JSONObject()
                        .put("path", runtime.absolutePath)
                        .put("byteSize", runtime.length())
                        .put("sha256", runtime.sha256())
                } ?: JSONObject.NULL,
            )
            .put("classLoaderResolvedRuntime", classLoaderRuntime ?: JSONObject.NULL)
            .put("apkInventories", apkInventories)
            .put("loadedRuntimeMaps", JSONArray(loadedRuntimeMaps))
            .put("totalDeviceMemoryBytes", deviceMemory.totalMem)
            .put("availableDeviceMemoryBytes", deviceMemory.availMem)
            .put("lowMemory", deviceMemory.lowMemory)
            .put("lowMemoryThresholdBytes", deviceMemory.threshold)
            .put("pid", Process.myPid())
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("device", Build.DEVICE)
            .put("androidApi", Build.VERSION.SDK_INT)
            .put("fingerprint", Build.FINGERPRINT)
    }

    private fun installedLiteRtRuntimeAbi(context: Context): MdxRuntimeAbi {
        val applicationInfo = context.applicationInfo
        val apkPaths = listOfNotNull(applicationInfo.sourceDir) +
            applicationInfo.splitSourceDirs.orEmpty()
        val runtimeAbis = apkPaths.flatMap { path ->
            ZipFile(path).use { archive ->
                archive.entries().asSequence()
                    .map { it.name }
                    .filter { it.matches(Regex("^lib/[^/]+/libLiteRt\\.so$")) }
                    .map { entry -> entry.substringAfter("lib/").substringBefore('/') }
                    .mapNotNull { name -> MdxRuntimeAbi.entries.singleOrNull { it.androidName == name } }
                    .toList()
            }
        }.toSet()
        require(runtimeAbis.size == 1) {
            "Validation requires one app-packaged LiteRT ABI, got $runtimeAbis."
        }
        return runtimeAbis.single()
    }

    private fun memorySnapshot(): JSONObject {
        val memory = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        return JSONObject()
            .put("elapsedRealtimeMs", SystemClock.elapsedRealtime())
            .put("processCpuMs", Process.getElapsedCpuTime())
            .put("totalPssKb", memory.totalPss)
            .put("nativePssKb", memory.nativePss)
            .put("dalvikPssKb", memory.dalvikPss)
            .put("otherPssKb", memory.otherPss)
            .put("nativeHeapAllocatedBytes", Debug.getNativeHeapAllocatedSize())
    }

    private fun compareOutputs(reference: FloatArray, candidate: FloatArray): OutputComparison {
        require(reference.size == candidate.size) {
            "Output size mismatch: ${reference.size} != ${candidate.size}."
        }
        var signalSquares = 0.0
        var errorSquares = 0.0
        var errorAbsolute = 0.0
        var maximumAbsoluteError = 0.0
        var dot = 0.0
        var candidateSquares = 0.0
        for (index in reference.indices) {
            val expected = reference[index].toDouble()
            val actual = candidate[index].toDouble()
            val error = actual - expected
            val absoluteError = abs(error)
            signalSquares += expected * expected
            errorSquares += error * error
            errorAbsolute += absoluteError
            maximumAbsoluteError = maxOf(maximumAbsoluteError, absoluteError)
            dot += expected * actual
            candidateSquares += actual * actual
        }
        val count = reference.size.coerceAtLeast(1)
        return OutputComparison(
            snrDb = if (errorSquares == 0.0) {
                Double.MAX_VALUE
            } else {
                10.0 * ln(signalSquares / errorSquares) / ln(10.0)
            },
            cosineSimilarity = dot / sqrt(signalSquares * candidateSquares),
            maximumAbsoluteError,
            meanAbsoluteError = errorAbsolute / count,
            rmse = sqrt(errorSquares / count),
        )
    }

    private fun validateFixtureFile(
        file: File,
        expectedSha256: String,
        expectedElementCount: Int,
        role: String,
    ) {
        require(SHA256_PATTERN.matches(expectedSha256)) { "Invalid $role SHA-256." }
        require(file.length() == expectedElementCount.toLong() * Float.SIZE_BYTES) {
            "Unexpected $role byte size: ${file.length()}."
        }
        require(file.sha256().equals(expectedSha256, ignoreCase = true)) {
            "$role SHA-256 does not match the staged fixture."
        }
    }

    private fun readFloat32(file: File, expectedElementCount: Int): FloatArray {
        FileInputStream(file).channel.use { channel ->
            val buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0L, channel.size())
                .order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(expectedElementCount).also(buffer.asFloatBuffer()::get)
        }
    }

    private fun File.identity() = MdxModelArtifact(
        file = this,
        byteSize = length(),
        sha256 = sha256(),
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

    private fun String.requireSafeName(role: String): String {
        require(SAFE_NAME_PATTERN.matches(this)) { "Invalid $role: $this" }
        return this
    }

    private fun validationReportFile(context: Context, runId: String): File =
        File(context.cacheDir, "$VALIDATION_DIRECTORY/$runId/report.json").apply {
            parentFile?.mkdirs()
        }

    private fun MdxModelArtifact.toJson() = JSONObject()
        .put("fileName", file.name)
        .put("byteSize", byteSize)
        .put("sha256", sha256)

    private fun File.fixtureJson(expectedSha256: String) = JSONObject()
        .put("fileName", name)
        .put("byteSize", length())
        .put("sha256", expectedSha256)

    private data class OutputComparison(
        val snrDb: Double,
        val cosineSimilarity: Double,
        val maxAbsError: Double,
        val meanAbsoluteError: Double,
        val rmse: Double,
    ) {
        fun toJson() = JSONObject()
            .put("snrDb", snrDb)
            .put("cosineSimilarity", cosineSimilarity)
            .put("maxAbsError", maxAbsError)
            .put("meanAbsError", meanAbsoluteError)
            .put("rmse", rmse)
    }

    private data class ParityThresholds(
        val minimumSnrDb: Double,
        val minimumCosine: Double,
        val maximumAbsoluteError: Double,
    ) {
        fun toJson() = JSONObject()
            .put("minimumSnrDb", minimumSnrDb)
            .put("minimumCosine", minimumCosine)
            .put("maximumAbsoluteError", maximumAbsoluteError)
    }

    private data class StemValidation(
        val modelOutputStem: String,
        val modelOutputScale: Float,
        val reconstructionMaxAbsError: Double,
    ) {
        fun toJson() = JSONObject()
            .put("modelOutputStem", modelOutputStem)
            .put("modelOutputScale", modelOutputScale.toDouble())
            .put("reconstructionMaxAbsError", reconstructionMaxAbsError)
    }

    private class RejectingAllocator : MdxLiteRtSessionAllocator {
        var createCount = 0

        override fun create(
            artifact: MdxModelArtifact,
            profile: MdxExecutionProfile,
            cpuThreads: Int,
            compatibility: MdxCompatibilityDecision,
        ): MdxInferenceSession {
            createCount += 1
            error("Native allocation must not run for an unsupported target.")
        }
    }

    companion object {
        private const val STAGING_DIRECTORY = "litert-validation-staging"
        private const val VALIDATION_DIRECTORY = "litert-validation"
        private const val STEM_RECONSTRUCTION_MAX_ERROR = 0.00001
        private const val ARG_RUN_ID = "runId"
        private const val ARG_MODEL_ID = "modelId"
        private const val ARG_MODEL_PATH = "modelPath"
        private const val ARG_INPUT_PATH = "inputPath"
        private const val ARG_INPUT_SHA256 = "inputSha256"
        private const val ARG_REFERENCE_PATH = "referencePath"
        private const val ARG_REFERENCE_SHA256 = "referenceSha256"
        private const val ARG_FIXTURE_NAME = "fixtureName"
        private const val ARG_PROCESS_ABI = "processAbi"
        private const val ARG_APP_COMMIT = "appCommit"
        private const val ARG_TEST_IN_FLIGHT_CANCELLATION = "testInFlightCancellation"
        private const val ARG_SECONDARY_MODEL_ID = "secondaryModelId"
        private const val ARG_SECONDARY_MODEL_PATH = "secondaryModelPath"
        private const val ARG_PREFLIGHT_ONLY = "preflightOnly"
        private const val ARG_ALLOW_UNSUPPORTED_RESOURCE_PROBE =
            "allowUnsupportedResourceProbe"
        private const val ARG_PROCESSOR_COUNT_OVERRIDE = "processorCountOverride"
        private val SAFE_NAME_PATTERN = Regex("^[a-zA-Z0-9._-]{1,120}$")
        private val SHA256_PATTERN = Regex("^[a-fA-F0-9]{64}$")

        private fun parityThresholds(modelId: String) = when (modelId) {
            "uvr_mdxnet_3_9662" -> ParityThresholds(93.8, 0.999999999, 0.00010)
            "uvr_mdxnet_kara" -> ParityThresholds(109.0, 0.999999999, 0.00003)
            "uvr_mdxnet_inst_hq_4" -> ParityThresholds(89.4, 0.999999999, 0.00060)
            else -> error("No Phase 2 parity thresholds exist for $modelId.")
        }
    }
}
