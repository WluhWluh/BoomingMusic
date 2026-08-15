package com.mardous.booming.separation.model.litert

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litert.Environment
import com.mardous.booming.BuildConfig
import com.mardous.booming.separation.model.MdxCompatibilityDecision
import com.mardous.booming.separation.model.MdxCompatibilityPolicy
import com.mardous.booming.separation.model.MdxExecutionProfile
import com.mardous.booming.separation.model.MdxInferenceBackend
import com.mardous.booming.separation.model.MdxInferenceSession
import com.mardous.booming.separation.model.MdxModelArtifact
import com.mardous.booming.separation.model.MdxRuntimeAbi
import com.mardous.booming.separation.model.MdxRuntimePlatform
import com.mardous.booming.separation.model.MdxRuntimeProfiles
import com.mardous.booming.separation.model.MdxRuntimeSettings
import com.mardous.booming.separation.model.MdxRuntimeSupportStatus
import com.mardous.booming.separation.model.MdxX86ProcessValidationOverride
import com.mardous.booming.separation.model.MdxSpectrogram
import com.mardous.booming.separation.model.ReusableMdxInferenceSessionProvider
import com.mardous.booming.separation.model.mapMdxStemWaveforms
import com.mardous.booming.separation.model.contract.SourceSeparationModelContract
import com.mardous.booming.separation.model.contract.SourceSeparationModelContractValidator
import com.mardous.booming.separation.model.contract.SourceSeparationModelMetadata
import com.mardous.booming.separation.model.contract.toMdxExecutionProfile
import com.mardous.booming.separation.runtime.SourceSeparationGpuRuntimeBootstrap
import com.mardous.booming.separation.runtime.SourceSeparationGpuRuntimeLocator
import com.mardous.booming.separation.runtime.SourceSeparationRuntimeBootstrap
import com.mardous.booming.separation.runtime.SourceSeparationRuntimeLayout
import dalvik.system.BaseDexClassLoader
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
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
    fun validateStagedModel() = validateStagedModel(MdxInferenceBackend.LiteRtCpu)

    @Test
    fun validateStagedGpuModel() = validateStagedModel(MdxInferenceBackend.LiteRtGpu)

    @Test
    fun validateStagedGpuAutoFallback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val runId = arguments.requiredString(ARG_RUN_ID).requireSafeName("run ID")
        val reportFile = validationReportFile(context, runId)
        val report = baseReport(context, runId, arguments)
            .put("requestedBackend", MdxInferenceBackend.LiteRtAuto.name)

        try {
            val modelId = arguments.requiredString(ARG_MODEL_ID)
            val catalog = SourceSeparationModelMetadata.loadBundledCatalog(context)
            val contract = SourceSeparationModelContractValidator.resolveReviewedContract(
                catalog,
                modelId,
            )
            val profile = contract.toMdxExecutionProfile(catalog.runtimeQualifications)
            val expectedRuntimeAbi = arguments.requiredString(ARG_PROCESS_ABI)
            val actualProcessAbi = currentProcessAbi().androidName
            val runtimeAbi = installedLiteRtRuntimeAbi(context)
            require(runtimeAbi.androidName == expectedRuntimeAbi) {
                "Expected $expectedRuntimeAbi LiteRT library, got ${runtimeAbi.androidName}."
            }
            require(runtimeAbi == MdxRuntimeAbi.Arm64V8a) {
                "Connected Auto fallback validation currently requires arm64-v8a."
            }
            report.put("contractId", contract.contractId)
                .put("contractConversionRevision", contract.conversion.revision)
                .put("modelId", modelId)
                .put("process", processReport(context, actualProcessAbi, runtimeAbi.androidName))

            validateGpuAutoFallbackRun(
                context = context,
                arguments = arguments,
                contract = contract,
                profile = profile,
                runtimeAbi = runtimeAbi,
                report = report,
            )
            report.put(
                "process",
                processReport(context, actualProcessAbi, runtimeAbi.androidName),
            ).put("status", "complete")
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

    private fun validateStagedModel(backend: MdxInferenceBackend) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val runId = arguments.requiredString(ARG_RUN_ID).requireSafeName("run ID")
        val reportFile = validationReportFile(context, runId)
        val report = baseReport(context, runId, arguments)

        try {
            val modelId = arguments.requiredString(ARG_MODEL_ID)
            val catalog = SourceSeparationModelMetadata.loadBundledCatalog(context)
            val contract = SourceSeparationModelContractValidator.resolveReviewedContract(
                catalog,
                modelId,
            )
            val contractProfile = contract.toMdxExecutionProfile(catalog.runtimeQualifications)
            val expectedRuntimeAbi = arguments.requiredString(ARG_PROCESS_ABI)
            val actualProcessAbi = currentProcessAbi().androidName
            val runtimeAbi = installedLiteRtRuntimeAbi(context)
            require(runtimeAbi.androidName == expectedRuntimeAbi) {
                "Expected $expectedRuntimeAbi LiteRT library, got ${runtimeAbi.androidName}."
            }
            report.put("contractId", contract.contractId)
                .put("contractConversionRevision", contract.conversion.revision)
                .put("modelId", modelId)
                .put("requestedBackend", backend.name)
                .put("process", processReport(context, actualProcessAbi, runtimeAbi.androidName))

            val resourceProbe = arguments.getString(ARG_ALLOW_UNSUPPORTED_RESOURCE_PROBE)
                .toBoolean()
            require(!resourceProbe || backend == MdxInferenceBackend.LiteRtCpu) {
                "Unsupported resource probes are CPU-only."
            }
            require(!resourceProbe || !arguments.getString(ARG_PREFLIGHT_ONLY).toBoolean()) {
                "An unsupported resource probe cannot also be preflight-only."
            }
            val (runtimeProfile, runtimeOverride) =
                internalX86ValidationProfile(contractProfile, contract, runtimeAbi)
            val (profile, compatibilityOverride) = if (resourceProbe) {
                internalResourceProbeProfile(runtimeProfile, runtimeAbi)
            } else {
                runtimeProfile to runtimeOverride
            }
            report.put("compatibilityOverride", compatibilityOverride ?: JSONObject.NULL)

            if (arguments.getString(ARG_PREFLIGHT_ONLY).toBoolean()) {
                if (backend == MdxInferenceBackend.LiteRtCpu) {
                    validateUnsupportedPreflight(profile, runtimeAbi, report)
                } else {
                    validateUnsupportedGpuPreflight(profile, runtimeAbi, report)
                }
            } else {
                validateParityRun(
                    context,
                    arguments,
                    contract,
                    profile,
                    runtimeAbi,
                    backend,
                    report,
                )
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
        backend: MdxInferenceBackend,
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
        val platformProvider = {
            MdxRuntimePlatform(Build.VERSION.SDK_INT, runtimeAbi)
        }
        val gpuRuntimeProfile = if (backend == MdxInferenceBackend.LiteRtGpu) {
            require(processorCountOverride == null) {
                "A CPU processor-count override cannot be used for GPU validation."
            }
            gpuRuntimeProfile(arguments.requiredString(ARG_GPU_PROFILE_ID))
        } else {
            null
        }
        if (gpuRuntimeProfile?.productionEligible == true) {
            SourceSeparationGpuRuntimeBootstrap.ensureLoaded(context)
        }
        val factory = when (backend) {
            MdxInferenceBackend.LiteRtCpu -> if (processorCountOverride == null) {
                MdxLiteRtCpuInferenceSessionFactory(
                    platformProvider = platformProvider,
                    compatibilityPolicy = MdxCompatibilityPolicy.AllowUntestedInternal,
                )
            } else {
                MdxLiteRtCpuInferenceSessionFactory(
                    platformProvider = platformProvider,
                    compatibilityPolicy = MdxCompatibilityPolicy.AllowUntestedInternal,
                    availableProcessors = { processorCountOverride },
                )
            }

            MdxInferenceBackend.LiteRtGpu -> MdxLiteRtGpuInferenceSessionFactory(
                runtimeProfile = requireNotNull(gpuRuntimeProfile),
                platformProvider = platformProvider,
                compatibilityPolicy = MdxCompatibilityPolicy.AllowUntestedInternal,
            )

            else -> error("Unsupported validation backend: $backend")
        }
        val enforceThresholds = gpuRuntimeProfile?.precision != MdxLiteRtGpuPrecision.Float16
        report.put("runtimeProfileId", gpuRuntimeProfile?.profileId ?: "cpu-phase2")
            .put("thresholdsEnforced", enforceThresholds)
            .put(
                "availableAcceleratorsBeforeSetup",
                JSONArray(availableLiteRtAccelerators()),
            )
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
        var memoryAfterFirstInference: JSONObject? = null
        var memoryAfterReusedInference: JSONObject? = null
        var processAfterFirstInference: JSONObject? = null
        var processAfterReusedInference: JSONObject? = null
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
                memoryAfterFirstInference = memorySnapshot()
                processAfterFirstInference = processReport(
                    context,
                    currentProcessAbi().androidName,
                    runtimeAbi.androidName,
                )
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
                memoryAfterReusedInference = memorySnapshot()
                processAfterReusedInference = processReport(
                    context,
                    currentProcessAbi().androidName,
                    runtimeAbi.androidName,
                )
                if (gpuRuntimeProfile?.productionEligible == true) {
                    val statistics = MdxLiteRtBoundedGpuRuntime.statistics()
                    report.put(
                        "boundedGpuStatistics",
                        JSONObject()
                            .put("dispatchCount", statistics.dispatchCount)
                            .put("eventWaitCount", statistics.eventWaitCount),
                    )
                    require(statistics.dispatchCount > 0L) {
                        "Bounded GPU inference did not submit any OpenCL kernels."
                    }
                    require(statistics.dispatchCount == statistics.eventWaitCount) {
                        "Bounded GPU dispatch/event-wait mismatch: " +
                            "dispatches=${statistics.dispatchCount}, " +
                            "eventWaits=${statistics.eventWaitCount}."
                    }
                }
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
        if (backend == MdxInferenceBackend.LiteRtGpu) {
            require(
                requireNotNull(processAfterFirstInference)
                    .getJSONArray("loadedGpuAcceleratorMaps")
                    .length() > 0
            ) {
                "The first GPU invocation did not retain a mapped LiteRT accelerator."
            }
            require(
                requireNotNull(processAfterReusedInference)
                    .getJSONArray("loadedGpuAcceleratorMaps")
                    .length() > 0
            ) {
                "The reused GPU invocation did not retain a mapped LiteRT accelerator."
            }
        }
        report.put("firstInferenceWallMs", firstInferenceMs)
            .put("reusedInferenceWallMs", reusedInferenceMs)
            .put("reusedSameSession", reusedSameSession)
            .put("memoryAfterFirstInference", requireNotNull(memoryAfterFirstInference))
            .put("memoryAfterReusedInference", requireNotNull(memoryAfterReusedInference))
            .put("processAfterFirstInference", requireNotNull(processAfterFirstInference))
            .put("processAfterReusedInference", requireNotNull(processAfterReusedInference))
            .put("comparisonToOrt", comparison.toJson())
            .put("reuseComparison", reuseComparison.toJson())
            .put("thresholds", thresholds.toJson())
            .put("stemValidation", stemValidation.toJson())
            .put("cancellation", cancellationReport ?: JSONObject.NULL)
            .put("replacement", replacementReport ?: JSONObject.NULL)
            .put("memoryWithSession", requireNotNull(memoryWithSession))
            .put("memoryAfter", memorySnapshot())
        if (enforceThresholds) {
            require(comparison.snrDb >= thresholds.minimumSnrDb) {
                "SNR ${comparison.snrDb} is below ${thresholds.minimumSnrDb}."
            }
            require(comparison.cosineSimilarity >= thresholds.minimumCosine) {
                "Cosine ${comparison.cosineSimilarity} is below ${thresholds.minimumCosine}."
            }
            require(comparison.maxAbsError <= thresholds.maximumAbsoluteError) {
                "Maximum error ${comparison.maxAbsError} exceeds " +
                    "${thresholds.maximumAbsoluteError}."
            }
        }
        require(stemValidation.reconstructionMaxAbsError <= STEM_RECONSTRUCTION_MAX_ERROR) {
            "Stem reconstruction error ${stemValidation.reconstructionMaxAbsError} exceeds " +
                STEM_RECONSTRUCTION_MAX_ERROR
        }
    }

    private fun validateGpuAutoFallbackRun(
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
        val thresholds = parityThresholds(contract.modelId)
        val runtimeProfile = gpuRuntimeProfile(arguments.requiredString(ARG_GPU_PROFILE_ID))
        require(runtimeProfile.precision == MdxLiteRtGpuPrecision.Float32) {
            "Connected Auto fallback validation requires the FP32 correctness profile."
        }
        val failpoint = GpuAutoFailpoint.parse(arguments.requiredString(ARG_GPU_FAILPOINT))
        val platformProvider = {
            MdxRuntimePlatform(Build.VERSION.SDK_INT, runtimeAbi)
        }
        val tracker = NativeSessionTracker()
        val nativeGpuFactory = MdxLiteRtGpuInferenceSessionFactory(
            runtimeProfile = runtimeProfile,
            platformProvider = platformProvider,
            compatibilityPolicy = MdxCompatibilityPolicy.AllowUntestedInternal,
        )
        val nativeCpuFactory = MdxLiteRtCpuInferenceSessionFactory(
            platformProvider = platformProvider,
            compatibilityPolicy = MdxCompatibilityPolicy.KnownGoodOnly,
        )
        val gpuFactory = TrackingInferenceSessionFactory(
            delegate = nativeGpuFactory,
            tracker = tracker,
            failpoint = failpoint,
        )
        val cpuFactory = TrackingInferenceSessionFactory(
            delegate = nativeCpuFactory,
            tracker = tracker,
        )
        var probeComparison: OutputComparison? = null
        val autoFactory = MdxLiteRtAutoInferenceSessionFactory(
            gpuRuntimeProfile = runtimeProfile,
            platformProvider = platformProvider,
            gpuCompatibilityPolicy = MdxCompatibilityPolicy.AllowUntestedInternal,
            gpuEligibilityProvider = AndroidMdxLiteRtGpuEligibilityProvider(context),
            gpuProbe = { session, _ ->
                val output = session.run(input).copyOf()
                probeComparison = compareOutputs(reference, output)
                if (failpoint == GpuAutoFailpoint.Probe) {
                    MdxLiteRtGpuProbeResult.rejected("Injected probe rejection.")
                } else {
                    MdxLiteRtGpuProbeResult.accepted("ORT parity probe completed.")
                }
            },
            gpuFactory = gpuFactory,
            cpuFactory = cpuFactory,
        )
        report.put("fixture", arguments.requiredString(ARG_FIXTURE_NAME))
            .put("artifact", modelIdentity.toJson())
            .put("input", inputFile.fixtureJson(arguments.requiredString(ARG_INPUT_SHA256)))
            .put("reference", referenceFile.fixtureJson(arguments.requiredString(ARG_REFERENCE_SHA256)))
            .put("runtimeProfileId", runtimeProfile.profileId)
            .put("failpoint", failpoint.serializedName)
            .put("thresholds", thresholds.toJson())
            .put("memoryBefore", memorySnapshot())
            .put("availableAcceleratorsBeforeSetup", JSONArray(availableLiteRtAccelerators()))

        val setupStarted = SystemClock.elapsedRealtime()
        val session = autoFactory.create(modelIdentity, profile, MdxRuntimeSettings())
        report.put("setupWallMs", SystemClock.elapsedRealtime() - setupStarted)
            .put("autoAfterSetup", session.autoDiagnostics().toJson())
            .put("memoryAfterSetup", memorySnapshot())
            .put(
                "processWithSession",
                processReport(
                    context,
                    currentProcessAbi().androidName,
                    runtimeAbi.androidName,
                ),
            )
        val started = SystemClock.elapsedRealtime()
        val output = try {
            session.run(input).copyOf()
        } finally {
            report.put("inferenceWallMs", SystemClock.elapsedRealtime() - started)
                .put("autoAfterRun", session.autoDiagnostics().toJson())
                .put("memoryWithSession", memorySnapshot())
            session.close()
        }
        val comparison = compareOutputs(reference, output)
        val stemValidation = validateStemMapping(input, output, profile)
        report.put("probeComparisonToOrt", probeComparison?.toJson() ?: JSONObject.NULL)
            .put("comparisonToOrt", comparison.toJson())
            .put("stemValidation", stemValidation.toJson())
            .put("sessionTracker", tracker.toJson())
            .put("memoryAfter", memorySnapshot())
        require(comparison.snrDb >= thresholds.minimumSnrDb) {
            "Fallback SNR ${comparison.snrDb} is below ${thresholds.minimumSnrDb}."
        }
        require(comparison.cosineSimilarity >= thresholds.minimumCosine) {
            "Fallback cosine ${comparison.cosineSimilarity} is below " +
                "${thresholds.minimumCosine}."
        }
        require(comparison.maxAbsError <= thresholds.maximumAbsoluteError) {
            "Fallback maximum error ${comparison.maxAbsError} exceeds " +
                "${thresholds.maximumAbsoluteError}."
        }
        require(stemValidation.reconstructionMaxAbsError <= STEM_RECONSTRUCTION_MAX_ERROR) {
            "Fallback stem reconstruction exceeded $STEM_RECONSTRUCTION_MAX_ERROR."
        }
        require(tracker.gpuCreateCount == 1) { "Expected one real GPU session." }
        require(tracker.cpuCreateCount == 1) { "Expected one real CPU fallback session." }
        require(!tracker.cpuCreatedWhileGpuActive) {
            "CPU was created before the GPU session closed."
        }
        require(tracker.activeGpuSessions == 0 && tracker.activeCpuSessions == 0) {
            "A native validation session remained active after close."
        }
        require(session.autoDiagnostics().acceptedOutputBackend == MdxInferenceBackend.LiteRtCpu) {
            "The accepted fallback output was not produced by CPU."
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

    private fun validateUnsupportedGpuPreflight(
        profile: MdxExecutionProfile,
        runtimeAbi: MdxRuntimeAbi,
        report: JSONObject,
    ) {
        val allocator = RejectingGpuAllocator()
        val factory = MdxLiteRtGpuInferenceSessionFactory(
            runtimeProfile = MdxLiteRtGpuRuntimeProfile.AutomaticFp32V1,
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
        require(error != null) { "Unsupported GPU preflight unexpectedly created a session." }
        require(allocator.createCount == 0) {
            "Unsupported GPU preflight reached the native session allocator."
        }
        report.put("preflightOnly", true)
            .put("preflightErrorType", error.javaClass.name)
            .put("preflightErrorMessage", error.message.orEmpty())
            .put("nativeGpuAllocatorCalls", allocator.createCount)
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
            runtimeVersion = MdxRuntimeProfiles.LITERT_VERSION,
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
            .put("originalRuntimeVersion", original.runtimeVersion)
            .put("originalEvidence", original.evidence)
            .put("effectiveStatus", overridden.status.name)
            .put("effectiveRuntimeVersion", overridden.runtimeVersion)
        return probeProfile to report
    }

    private fun internalX86ValidationProfile(
        profile: MdxExecutionProfile,
        contract: SourceSeparationModelContract,
        runtimeAbi: MdxRuntimeAbi,
    ): Pair<MdxExecutionProfile, JSONObject?> {
        if (runtimeAbi != MdxRuntimeAbi.X86) return profile to null
        val original = profile.runtimeCompatibility.singleOrNull {
            it.abi == MdxRuntimeAbi.X86 &&
                it.backend == MdxInferenceBackend.LiteRtCpu &&
                it.profileId == MdxRuntimeProfiles.CPU_DEFAULT_FP32
        } ?: return profile to null
        val platform = MdxRuntimePlatform(Build.VERSION.SDK_INT, runtimeAbi)
        if (!MdxX86ProcessValidationOverride.permitsCatalogQualification(
                modelId = contract.modelId,
                artifactSha256 = contract.artifact.sha256,
                contractId = contract.contractId,
                platform = platform,
                originalStatus = original.status,
                enabled = BuildConfig.X86_PROCESS_VALIDATION,
            )
        ) return profile to null
        val overridden = original.copy(
            runtimeVersion = platform.runtimeVersion,
            status = MdxRuntimeSupportStatus.KnownGood,
            evidence = "AndroidTest x86 process validation; ${original.evidence}",
        )
        val effectiveProfile = profile.copy(
            runtimeCompatibility = profile.runtimeCompatibility.map { record ->
                if (record == original) overridden else record
            },
        )
        val report = JSONObject()
            .put("scope", "androidTest-x86-process-validation")
            .put("abi", runtimeAbi.androidName)
            .put("originalRuntimeVersion", original.runtimeVersion)
            .put("originalStatus", original.status.name)
            .put("effectiveRuntimeVersion", overridden.runtimeVersion)
            .put("effectiveStatus", overridden.status.name)
        return effectiveProfile to report
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
        val catalog = SourceSeparationModelMetadata.loadBundledCatalog(context)
        val secondaryContract = SourceSeparationModelContractValidator.resolveReviewedContract(
            catalog,
            secondaryModelId,
        )
        val secondaryProfile = secondaryContract.toMdxExecutionProfile(
            catalog.runtimeQualifications
        )
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

    private fun gpuRuntimeProfile(profileId: String): MdxLiteRtGpuRuntimeProfile =
        MdxLiteRtGpuRuntimeProfile.find(profileId)
            ?: error("Unsupported LiteRT GPU runtime profile: $profileId")

    private fun availableLiteRtAccelerators(): List<String> {
        val environment = Environment.create()
        return try {
            environment.getAvailableAccelerators().map { it.name }.sorted()
        } finally {
            environment.close()
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
        .put("appCommit", arguments.requiredString(ARG_APP_COMMIT))
        .put("appApkSha256", arguments.requiredString(ARG_APP_APK_SHA256))
        .put("testApkSha256", arguments.requiredString(ARG_TEST_APK_SHA256))
        .put("catalogSha256", SourceSeparationModelMetadata.CATALOG_SHA256)
        .put("contractSchemaVersion", SourceSeparationModelContractValidator.CONTRACT_SCHEMA_VERSION)
        .put("pipelineVersion", SourceSeparationModelContractValidator.PIPELINE_VERSION)
        .put("runtime", "LiteRT 2.2.0-bss.2")
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
        val processMaps = File("/proc/self/maps").readLines()
        val directRuntimeMaps = processMaps.filter {
            it.contains("liblitert", ignoreCase = true)
        }
        val mappedLiteRtEntries = JSONArray()
        val apkBackedRuntimeMaps = mutableListOf<String>()
        val apkBackedAcceleratorMaps = mutableListOf<String>()
        for (path in apkPaths) {
            for (entry in storedLiteRtEntryRanges(path)) {
                val matchingMaps = processMaps.filter { line ->
                    line.mapsApkEntry(path, entry)
                }
                mappedLiteRtEntries.put(
                    JSONObject()
                        .put("apkPath", path)
                        .put("entryName", entry.name)
                        .put("dataOffset", entry.dataOffset)
                        .put("byteSize", entry.byteSize)
                        .put("mapLines", JSONArray(matchingMaps))
                )
                apkBackedRuntimeMaps += matchingMaps
                if (entry.name.endsWith("/libLiteRtClGlAccelerator.so")) {
                    apkBackedAcceleratorMaps += matchingMaps
                }
            }
        }
        val loadedRuntimeMaps = (directRuntimeMaps + apkBackedRuntimeMaps).distinct()
        val loadedAcceleratorMaps = (
            directRuntimeMaps.filter {
                it.contains("libLiteRtClGlAccelerator", ignoreCase = true)
            } + apkBackedAcceleratorMaps
            ).distinct()
        val downloadedRuntime = runCatching {
            SourceSeparationRuntimeBootstrap.ensureLoaded(context).libraryFile
        }.getOrNull()
        val downloadedGpuRuntime = runCatching {
            SourceSeparationGpuRuntimeLocator(
                root = SourceSeparationRuntimeLayout.runtimeRoot(context),
                processAbi = processAbi,
                androidApi = Build.VERSION.SDK_INT,
            ).resolve()
        }.fold(
            onSuccess = { installation ->
                val libraries = JSONArray()
                installation.libraryFiles.forEach { (name, file) ->
                    libraries.put(
                        JSONObject()
                            .put("name", name)
                            .put("path", file.absolutePath)
                            .put("byteSize", file.length())
                            .put("sha256", file.sha256()),
                    )
                }
                JSONObject()
                    .put("state", "installed")
                    .put("path", installation.directory.absolutePath)
                    .put("manifestPath", installation.manifestFile.absolutePath)
                    .put("runtimeArtifactVersion", installation.identity.runtimeArtifactVersion)
                    .put("releaseVersion", installation.identity.releaseVersion)
                    .put("profileId", installation.identity.profileId)
                    .put("requiredCpuLibrarySha256", installation.manifest.requiredCore.librarySha256)
                    .put("libraries", libraries)
            },
            onFailure = { error ->
                JSONObject()
                    .put("state", "missing-or-invalid")
                    .put("detail", error.message.orEmpty())
            },
        )
        val gpuCapability = SourceSeparationGpuRuntimeBootstrap.capability()
        val classLoaderRuntime = (context.classLoader as? BaseDexClassLoader)
            ?.findLibrary("LiteRt")
        val classLoaderAccelerator = (context.classLoader as? BaseDexClassLoader)
            ?.findLibrary("LiteRtClGlAccelerator")
        return JSONObject()
            .put("supportedAbis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("osArch", System.getProperty("os.arch").orEmpty())
            .put("is64Bit", Process.is64Bit())
            .put("resolvedProcessAbi", processAbi)
            .put("selectedLiteRtRuntimeAbi", runtimeAbi)
            .put("nativeLibraryDir", applicationInfo.nativeLibraryDir)
            .put(
                "downloadedRuntime",
                downloadedRuntime?.let { runtime ->
                    JSONObject()
                        .put("path", runtime.absolutePath)
                        .put("byteSize", runtime.length())
                        .put("sha256", runtime.sha256())
                } ?: JSONObject.NULL,
            )
            .put("downloadedGpuRuntime", downloadedGpuRuntime)
            .put(
                "downloadedGpuCapability",
                JSONObject()
                    .put("loaded", SourceSeparationGpuRuntimeBootstrap.isLoaded())
                    .put("available", gpuCapability.available)
                    .put("schemaVersion", gpuCapability.schemaVersion)
                    .put("artifactVersion", gpuCapability.artifactVersion)
                    .put("profileId", gpuCapability.profileId)
                    .put("kernelBatchSize", gpuCapability.kernelBatchSize)
                    .put("commandQueueWindowSize", gpuCapability.commandQueueWindowSize)
                    .put("detail", gpuCapability.detail),
            )
            .put("classLoaderResolvedRuntime", classLoaderRuntime ?: JSONObject.NULL)
            .put(
                "classLoaderResolvedGpuAccelerator",
                classLoaderAccelerator ?: JSONObject.NULL,
            )
            .put("apkInventories", apkInventories)
            .put("mappedLiteRtEntries", mappedLiteRtEntries)
            .put("loadedRuntimeMaps", JSONArray(loadedRuntimeMaps))
            .put("loadedGpuAcceleratorMaps", JSONArray(loadedAcceleratorMaps))
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

    private fun storedLiteRtEntryRanges(path: String): List<StoredZipEntryRange> =
        RandomAccessFile(path, "r").use { archive ->
            val centralDirectory = archive.readCentralDirectoryLocation()
            archive.seek(centralDirectory.offset)
            buildList {
                repeat(centralDirectory.entryCount) {
                    require(archive.readUInt32Le() == ZIP_CENTRAL_DIRECTORY_SIGNATURE) {
                        "Invalid ZIP central-directory entry in $path."
                    }
                    archive.skipBytes(4)
                    archive.readUInt16Le()
                    val method = archive.readUInt16Le()
                    archive.skipBytes(8)
                    val compressedSize = archive.readUInt32Le()
                    val uncompressedSize = archive.readUInt32Le()
                    val nameLength = archive.readUInt16Le()
                    val extraLength = archive.readUInt16Le()
                    val commentLength = archive.readUInt16Le()
                    archive.skipBytes(8)
                    val localHeaderOffset = archive.readUInt32Le()
                    val name = ByteArray(nameLength).also(archive::readFully)
                        .toString(Charsets.UTF_8)
                    archive.skipBytes(extraLength + commentLength)
                    if (name.matches(LITERT_APK_ENTRY_PATTERN)) {
                        require(method == ZIP_STORED_METHOD) {
                            "$name must be stored uncompressed for direct APK loading."
                        }
                        require(compressedSize == uncompressedSize) {
                            "Stored ZIP entry $name has inconsistent sizes."
                        }
                        val centralPosition = archive.filePointer
                        archive.seek(localHeaderOffset)
                        require(archive.readUInt32Le() == ZIP_LOCAL_FILE_SIGNATURE) {
                            "Invalid ZIP local header for $name."
                        }
                        archive.skipBytes(22)
                        val localNameLength = archive.readUInt16Le()
                        val localExtraLength = archive.readUInt16Le()
                        val dataOffset = localHeaderOffset + ZIP_LOCAL_HEADER_SIZE +
                            localNameLength + localExtraLength
                        archive.seek(centralPosition)
                        add(
                            StoredZipEntryRange(
                                name = name,
                                dataOffset = dataOffset,
                                byteSize = uncompressedSize,
                            )
                        )
                    }
                }
            }
        }

    private fun RandomAccessFile.readCentralDirectoryLocation(): CentralDirectoryLocation {
        val tailSize = minOf(length(), ZIP_MAX_EOCD_SEARCH).toInt()
        val tail = ByteArray(tailSize)
        seek(length() - tailSize)
        readFully(tail)
        val eocdIndex = (tail.size - ZIP_END_OF_CENTRAL_DIRECTORY_SIZE downTo 0)
            .firstOrNull { index ->
                tail.readUInt32Le(index) == ZIP_END_OF_CENTRAL_DIRECTORY_SIGNATURE &&
                    tail.readUInt16Le(index + 20) ==
                    tail.size - index - ZIP_END_OF_CENTRAL_DIRECTORY_SIZE
            } ?: error("ZIP end-of-central-directory record was not found in $this.")
        require(tail.readUInt16Le(eocdIndex + 4) == 0) {
            "Multi-disk validation APKs are not supported."
        }
        require(tail.readUInt16Le(eocdIndex + 6) == 0) {
            "Multi-disk validation APKs are not supported."
        }
        val entriesOnDisk = tail.readUInt16Le(eocdIndex + 8)
        val entryCount = tail.readUInt16Le(eocdIndex + 10)
        val offset = tail.readUInt32Le(eocdIndex + 16)
        require(
            entriesOnDisk == entryCount &&
                entryCount != ZIP64_UINT16_SENTINEL &&
                offset != ZIP64_UINT32_SENTINEL
        ) {
            "ZIP64 validation APKs are not supported."
        }
        return CentralDirectoryLocation(offset = offset, entryCount = entryCount)
    }

    private fun RandomAccessFile.readUInt16Le(): Int {
        val first = read()
        val second = read()
        require(first >= 0 && second >= 0) { "Unexpected end of ZIP file." }
        return first or (second shl 8)
    }

    private fun RandomAccessFile.readUInt32Le(): Long =
        readUInt16Le().toLong() or (readUInt16Le().toLong() shl 16)

    private fun ByteArray.readUInt16Le(offset: Int): Int =
        (this[offset].toInt() and 0xff) or
            ((this[offset + 1].toInt() and 0xff) shl 8)

    private fun ByteArray.readUInt32Le(offset: Int): Long =
        readUInt16Le(offset).toLong() or (readUInt16Le(offset + 2).toLong() shl 16)

    private fun String.mapsApkEntry(path: String, entry: StoredZipEntryRange): Boolean {
        val fields = trim().split(Regex("\\s+"), limit = 6)
        if (fields.size < 6 || fields[5] != path) return false
        val addresses = fields[0].split('-', limit = 2)
        if (addresses.size != 2) return false
        val mappedLength = runCatching {
            addresses[1].toLong(16) - addresses[0].toLong(16)
        }.getOrNull() ?: return false
        val fileOffset = fields[2].toLongOrNull(16) ?: return false
        val mappedEnd = fileOffset + mappedLength
        val entryEnd = entry.dataOffset + entry.byteSize
        return fileOffset < entryEnd && mappedEnd > entry.dataOffset
    }

    private data class CentralDirectoryLocation(
        val offset: Long,
        val entryCount: Int,
    )

    private data class StoredZipEntryRange(
        val name: String,
        val dataOffset: Long,
        val byteSize: Long,
    )

    private fun installedLiteRtRuntimeAbi(context: Context): MdxRuntimeAbi {
        val abi = SourceSeparationRuntimeBootstrap.ensureLoaded(context).manifest.abi
        return requireNotNull(MdxRuntimeAbi.entries.singleOrNull { it.androidName == abi }) {
            "The downloaded LiteRT runtime has an unsupported ABI: $abi."
        }
    }

    private fun memorySnapshot(): JSONObject {
        val memory = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        val summary = JSONObject()
        for (key in MEMORY_SUMMARY_KEYS) {
            memory.getMemoryStat(key)?.toLongOrNull()?.let { value ->
                summary.put(key, value)
            }
        }
        return JSONObject()
            .put("elapsedRealtimeMs", SystemClock.elapsedRealtime())
            .put("processCpuMs", Process.getElapsedCpuTime())
            .put("totalPssKb", memory.totalPss)
            .put("nativePssKb", memory.nativePss)
            .put("dalvikPssKb", memory.dalvikPss)
            .put("otherPssKb", memory.otherPss)
            .put("nativeHeapAllocatedBytes", Debug.getNativeHeapAllocatedSize())
            .put("summaryKb", summary)
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

    private fun MdxInferenceSession.autoDiagnostics(): MdxLiteRtAutoDiagnostics =
        (this as MdxLiteRtAutoDiagnosticsProvider).autoDiagnostics

    private fun MdxLiteRtAutoDiagnostics.toJson() = JSONObject()
        .put("state", state.name)
        .put("gpuProfileId", gpuProfileId)
        .put("eligibilityReason", eligibilityReason.name)
        .put("eligibilityDetail", eligibilityDetail)
        .put("gpuAttempted", gpuAttempted)
        .put("activeBackend", activeBackend?.name ?: JSONObject.NULL)
        .put("acceptedOutputBackend", acceptedOutputBackend?.name ?: JSONObject.NULL)
        .put("fallbackStage", fallbackStage?.name ?: JSONObject.NULL)
        .put("fallbackReason", fallbackReason ?: JSONObject.NULL)
        .put("gpuSetupNanos", gpuSetupNanos ?: JSONObject.NULL)
        .put("gpuProbeNanos", gpuProbeNanos ?: JSONObject.NULL)
        .put("gpuInferenceNanos", gpuInferenceNanos ?: JSONObject.NULL)
        .put("cpuSetupNanos", cpuSetupNanos ?: JSONObject.NULL)
        .put("cpuInferenceNanos", cpuInferenceNanos ?: JSONObject.NULL)

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

    private enum class GpuAutoFailpoint(val serializedName: String) {
        Setup("setup"),
        Probe("probe"),
        Invocation("invocation"),
        OutputRead("output-read"),
        ;

        companion object {
            fun parse(value: String): GpuAutoFailpoint = entries.singleOrNull {
                it.serializedName == value
            } ?: error("Unsupported GPU Auto failpoint: $value")
        }
    }

    private class NativeSessionTracker {
        var gpuCreateCount = 0
            private set
        var cpuCreateCount = 0
            private set
        var activeGpuSessions = 0
            private set
        var activeCpuSessions = 0
            private set
        var maximumActiveGpuSessions = 0
            private set
        var maximumActiveCpuSessions = 0
            private set
        var cpuCreatedWhileGpuActive = false
            private set
        private val events = mutableListOf<String>()

        @Synchronized
        fun created(backend: MdxInferenceBackend) {
            when (backend) {
                MdxInferenceBackend.LiteRtGpu -> {
                    gpuCreateCount += 1
                    activeGpuSessions += 1
                    maximumActiveGpuSessions = maxOf(
                        maximumActiveGpuSessions,
                        activeGpuSessions,
                    )
                    events += "gpu-created"
                }

                MdxInferenceBackend.LiteRtCpu -> {
                    cpuCreateCount += 1
                    if (activeGpuSessions > 0) cpuCreatedWhileGpuActive = true
                    activeCpuSessions += 1
                    maximumActiveCpuSessions = maxOf(
                        maximumActiveCpuSessions,
                        activeCpuSessions,
                    )
                    events += "cpu-created"
                }

                else -> error("Unexpected tracked backend: $backend")
            }
        }

        @Synchronized
        fun closed(backend: MdxInferenceBackend) {
            when (backend) {
                MdxInferenceBackend.LiteRtGpu -> {
                    check(activeGpuSessions > 0) { "GPU session tracker underflow." }
                    activeGpuSessions -= 1
                    events += "gpu-closed"
                }

                MdxInferenceBackend.LiteRtCpu -> {
                    check(activeCpuSessions > 0) { "CPU session tracker underflow." }
                    activeCpuSessions -= 1
                    events += "cpu-closed"
                }

                else -> error("Unexpected tracked backend: $backend")
            }
        }

        @Synchronized
        fun invoked(backend: MdxInferenceBackend, invocation: Int) {
            events += "${backend.name}-run-$invocation"
        }

        @Synchronized
        fun toJson() = JSONObject()
            .put("gpuCreateCount", gpuCreateCount)
            .put("cpuCreateCount", cpuCreateCount)
            .put("activeGpuSessions", activeGpuSessions)
            .put("activeCpuSessions", activeCpuSessions)
            .put("maximumActiveGpuSessions", maximumActiveGpuSessions)
            .put("maximumActiveCpuSessions", maximumActiveCpuSessions)
            .put("cpuCreatedWhileGpuActive", cpuCreatedWhileGpuActive)
            .put("events", JSONArray(events))
    }

    private class TrackingInferenceSessionFactory(
        private val delegate: com.mardous.booming.separation.model.MdxInferenceSessionFactory,
        private val tracker: NativeSessionTracker,
        private val failpoint: GpuAutoFailpoint? = null,
    ) : com.mardous.booming.separation.model.MdxInferenceSessionFactory {
        override val factoryId: String = "tracked-${delegate.factoryId}"
        override val backend: MdxInferenceBackend = delegate.backend

        override fun create(
            artifact: MdxModelArtifact,
            profile: MdxExecutionProfile,
            runtimeSettings: MdxRuntimeSettings,
        ): MdxInferenceSession {
            val session = TrackingInferenceSession(
                delegate = delegate.create(artifact, profile, runtimeSettings),
                tracker = tracker,
                failpoint = failpoint,
            )
            if (backend == MdxInferenceBackend.LiteRtGpu &&
                failpoint == GpuAutoFailpoint.Setup
            ) {
                session.close()
                throw MdxLiteRtBackendException(
                    stage = MdxLiteRtFailureStage.ModelCompile,
                    isRecoverable = true,
                    cause = IllegalStateException("Injected GPU setup failure."),
                )
            }
            return session
        }
    }

    private class TrackingInferenceSession(
        private val delegate: MdxInferenceSession,
        private val tracker: NativeSessionTracker,
        private val failpoint: GpuAutoFailpoint?,
    ) : MdxInferenceSession {
        override val diagnostics: com.mardous.booming.separation.model.MdxRuntimeDiagnostics
            get() = delegate.diagnostics
        private val backend = delegate.diagnostics.backend
        private var invocationCount = 0
        private var closed = false

        init {
            tracker.created(backend)
        }

        override fun run(
            inputNchw: FloatArray,
            shouldCancel: () -> Boolean,
        ): FloatArray {
            invocationCount += 1
            tracker.invoked(backend, invocationCount)
            val output = delegate.run(inputNchw, shouldCancel)
            if (backend == MdxInferenceBackend.LiteRtGpu && invocationCount == 2) {
                val stage = when (failpoint) {
                    GpuAutoFailpoint.Invocation -> MdxLiteRtFailureStage.Invocation
                    GpuAutoFailpoint.OutputRead -> MdxLiteRtFailureStage.OutputRead
                    else -> null
                }
                if (stage != null) {
                    throw MdxLiteRtBackendException(
                        stage = stage,
                        isRecoverable = true,
                        cause = IllegalStateException(
                            "Injected GPU ${requireNotNull(failpoint).serializedName} failure."
                        ),
                    )
                }
            }
            return output
        }

        override fun close() {
            if (closed) return
            closed = true
            try {
                delegate.close()
            } finally {
                tracker.closed(backend)
            }
        }
    }

    private class RejectingAllocator : MdxLiteRtSessionAllocator {
        var createCount = 0

        override fun create(
            artifact: MdxModelArtifact,
            profile: MdxExecutionProfile,
            cpuThreads: Int,
            xnnPackFlags: Int?,
            compatibility: MdxCompatibilityDecision,
        ): MdxInferenceSession {
            createCount += 1
            error("Native allocation must not run for an unsupported target.")
        }
    }

    private class RejectingGpuAllocator : MdxLiteRtGpuSessionAllocator {
        var createCount = 0

        override fun create(
            artifact: MdxModelArtifact,
            profile: MdxExecutionProfile,
            runtimeProfile: MdxLiteRtGpuRuntimeProfile,
            compatibility: MdxCompatibilityDecision,
        ): MdxInferenceSession {
            createCount += 1
            error("Native GPU allocation must not run for an unsupported target.")
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
        private const val ARG_APP_APK_SHA256 = "appApkSha256"
        private const val ARG_TEST_APK_SHA256 = "testApkSha256"
        private const val ARG_TEST_IN_FLIGHT_CANCELLATION = "testInFlightCancellation"
        private const val ARG_SECONDARY_MODEL_ID = "secondaryModelId"
        private const val ARG_SECONDARY_MODEL_PATH = "secondaryModelPath"
        private const val ARG_PREFLIGHT_ONLY = "preflightOnly"
        private const val ARG_ALLOW_UNSUPPORTED_RESOURCE_PROBE =
            "allowUnsupportedResourceProbe"
        private const val ARG_PROCESSOR_COUNT_OVERRIDE = "processorCountOverride"
        private const val ARG_GPU_PROFILE_ID = "gpuProfileId"
        private const val ARG_GPU_FAILPOINT = "gpuFailpoint"
        private const val ZIP_STORED_METHOD = 0
        private const val ZIP_LOCAL_HEADER_SIZE = 30L
        private const val ZIP_END_OF_CENTRAL_DIRECTORY_SIZE = 22
        private const val ZIP_MAX_EOCD_SEARCH = 65_557L
        private const val ZIP64_UINT16_SENTINEL = 0xffff
        private const val ZIP64_UINT32_SENTINEL = 0xffff_ffffL
        private const val ZIP_CENTRAL_DIRECTORY_SIGNATURE = 0x0201_4b50L
        private const val ZIP_LOCAL_FILE_SIGNATURE = 0x0403_4b50L
        private const val ZIP_END_OF_CENTRAL_DIRECTORY_SIGNATURE = 0x0605_4b50L
        private val SAFE_NAME_PATTERN = Regex("^[a-zA-Z0-9._-]{1,120}$")
        private val SHA256_PATTERN = Regex("^[a-fA-F0-9]{64}$")
        private val LITERT_APK_ENTRY_PATTERN =
            Regex("""^lib/[^/]+/libLiteRt(?:ClGlAccelerator)?\.so$""")
        private val MEMORY_SUMMARY_KEYS = listOf(
            "summary.java-heap",
            "summary.native-heap",
            "summary.code",
            "summary.stack",
            "summary.graphics",
            "summary.private-other",
            "summary.system",
            "summary.total-pss",
            "summary.total-swap",
            "summary.total-swap-pss",
        )

        private fun parityThresholds(modelId: String) = when (modelId) {
            "uvr_mdxnet_3_9662" -> ParityThresholds(93.8, 0.999999999, 0.00010)
            "uvr_mdxnet_kara" -> ParityThresholds(109.0, 0.999999999, 0.00003)
            "uvr_mdxnet_inst_hq_4" -> ParityThresholds(89.4, 0.999999999, 0.00060)
            else -> error("No Phase 2 parity thresholds exist for $modelId.")
        }
    }
}
