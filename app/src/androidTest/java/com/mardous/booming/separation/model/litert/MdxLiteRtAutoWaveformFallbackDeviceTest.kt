package com.mardous.booming.separation.model.litert

import android.os.Build
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mardous.booming.separation.model.MdxCompatibilityPolicy
import com.mardous.booming.separation.model.MdxExecutionProfile
import com.mardous.booming.separation.model.MdxInferenceBackend
import com.mardous.booming.separation.model.MdxInferenceSession
import com.mardous.booming.separation.model.MdxInferenceSessionFactory
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
import kotlin.math.sin
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MdxLiteRtAutoWaveformFallbackDeviceTest {
    @Test
    fun recoverableGpuWaveformFailureReplaysOriginalWaveformOnCpu() {
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
            require(expectedAbi == MdxRuntimeAbi.Arm64V8a.androidName) {
                "The bounded GPU waveform fallback gate requires arm64-v8a."
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
            require(cpuInstallation.identity.runtimeArtifactVersion == EXPECTED_RUNTIME_ARTIFACT)
            SourceSeparationGpuRuntimeBootstrap.ensureLoaded(context)
            val capability = SourceSeparationGpuRuntimeBootstrap.capability()
            require(capability.available) { capability.detail }
            require(capability.artifactVersion == EXPECTED_RUNTIME_ARTIFACT)
            require(capability.profileId == MdxLiteRtBoundedGpuContract.PROFILE_ID)
            require(capability.kernelBatchSize == 1 && capability.commandQueueWindowSize == 1)

            val runtimeAbi = MdxRuntimeAbi.Arm64V8a
            val platformProvider = { MdxRuntimePlatform(Build.VERSION.SDK_INT, runtimeAbi) }
            val runtimeSettings = MdxRuntimeSettings(cpuThreads = CPU_THREADS)
            val waveform = fixture(profile.dspConfig.chunkSize, profile.dspConfig.sampleRate)
            val originalWaveform = waveform.deepCopy()
            val directCpuFactory = MdxLiteRtCpuInferenceSessionFactory(
                platformProvider = platformProvider,
                compatibilityPolicy = MdxCompatibilityPolicy.AllowUntestedInternal,
                availableProcessors = { CPU_THREADS + 1 },
            )
            val directCpuOutput = directCpuFactory.create(
                artifact,
                profile,
                runtimeSettings,
            ).use { session ->
                session.requireWaveformSession()
                    .runWaveform(waveform)
                    .deepCopy()
            }
            require(waveform.contentEquals(originalWaveform)) {
                "The direct CPU reference invocation mutated its input waveform."
            }

            val tracker = LifecycleTracker()
            val nativeGpuFactory = MdxLiteRtGpuInferenceSessionFactory(
                runtimeProfile = MdxLiteRtGpuRuntimeProfile.BoundedOpenClFp32V1,
                platformProvider = platformProvider,
                compatibilityPolicy = MdxCompatibilityPolicy.AllowUntestedInternal,
            )
            val nativeCpuFactory = MdxLiteRtCpuInferenceSessionFactory(
                platformProvider = platformProvider,
                compatibilityPolicy = MdxCompatibilityPolicy.AllowUntestedInternal,
                availableProcessors = { CPU_THREADS + 1 },
            )
            val gpuFactory = TrackingWaveformFactory(
                delegate = nativeGpuFactory,
                tracker = tracker,
                injectRecoverableWaveformFailure = true,
            )
            val cpuFactory = TrackingWaveformFactory(
                delegate = nativeCpuFactory,
                tracker = tracker,
                injectRecoverableWaveformFailure = false,
            )
            var probeInvocationCount = 0
            val autoFactory = MdxLiteRtAutoInferenceSessionFactory(
                gpuRuntimeProfile = MdxLiteRtGpuRuntimeProfile.BoundedOpenClFp32V1,
                platformProvider = platformProvider,
                gpuCompatibilityPolicy = MdxCompatibilityPolicy.AllowUntestedInternal,
                cpuCompatibilityPolicy = MdxCompatibilityPolicy.AllowUntestedInternal,
                gpuEligibilityProvider = AndroidMdxLiteRtGpuEligibilityProvider(context),
                gpuProbe = { session, probeProfile ->
                    probeInvocationCount += 1
                    val probeOutput = session.run(FloatArray(probeProfile.inputTensor.elementCount))
                    if (probeOutput.size != probeProfile.outputTensor.elementCount) {
                        MdxLiteRtGpuProbeResult.rejected("GPU probe output size mismatch.")
                    } else if (!probeOutput.all(Float::isFinite)) {
                        MdxLiteRtGpuProbeResult.rejected("GPU probe output was not finite.")
                    } else {
                        MdxLiteRtGpuProbeResult.accepted("Real bounded GPU tensor probe passed.")
                    }
                },
                gpuFactory = gpuFactory,
                cpuFactory = cpuFactory,
            )

            val session = autoFactory.create(artifact, profile, runtimeSettings)
            lateinit var autoDiagnostics: MdxLiteRtAutoDiagnostics
            val fallbackOutput = try {
                val waveformSession = session.requireWaveformSession()
                require(!waveformSession.supportsStagedWaveformExecution) {
                    "LiteRT Auto unexpectedly exposed staged waveform execution."
                }
                val stagedFailure = runCatching {
                    waveformSession.prepareWaveform(waveform, slot = 0)
                }.exceptionOrNull()
                require(stagedFailure is UnsupportedOperationException) {
                    "LiteRT Auto did not reject the staged waveform bypass."
                }
                waveformSession.runWaveform(waveform).deepCopy().also {
                    autoDiagnostics =
                        (session as MdxLiteRtAutoDiagnosticsProvider).autoDiagnostics
                }
            } finally {
                session.close()
            }
            require(probeInvocationCount == 1) {
                "Expected one real bounded GPU probe."
            }
            require(tracker.gpuTensorInvocationCount == 1) {
                "The Auto probe did not use the wrapped real GPU session exactly once."
            }
            require(tracker.gpuWaveformInvocationCount == 1) {
                "The injected GPU waveform path was not invoked exactly once."
            }
            require(tracker.gpuWaveformCompletedBeforeFailure) {
                "The injected failure did not occur after the real GPU waveform invocation."
            }
            require(tracker.cpuWaveformInvocationCount == 1) {
                "The original waveform was not replayed on CPU exactly once."
            }
            require(tracker.gpuCloseEndedBeforeCpuCreateStarted) {
                "CPU creation started before the failed GPU session fully closed."
            }
            require(tracker.activeGpuSessions == 0 && tracker.activeCpuSessions == 0) {
                "A native Auto fallback session remained active after close."
            }
            require(tracker.gpuCreateCount == 1 && tracker.cpuCreateCount == 1) {
                "Expected one real GPU session and one real CPU fallback session."
            }
            require(tracker.gpuCloseCount == 1 && tracker.cpuCloseCount == 1) {
                "Expected the real GPU and CPU sessions to close exactly once."
            }
            require(tracker.stagedMethodInvocationCount == 0) {
                "LiteRT Auto bypassed transactional runWaveform through staged wrapper calls."
            }
            require(waveform.contentEquals(originalWaveform)) {
                "The Auto fallback transaction mutated the caller's waveform."
            }
            require(tracker.cpuWaveformReference === waveform) {
                "CPU fallback did not receive the caller's original waveform object."
            }
            require(requireNotNull(tracker.cpuWaveformInput).contentEquals(originalWaveform)) {
                "CPU fallback did not receive the original waveform sample-for-sample."
            }
            require(fallbackOutput.contentEquals(directCpuOutput)) {
                "CPU fallback output differs from direct managed CPU output."
            }
            require(autoDiagnostics.acceptedOutputBackend == MdxInferenceBackend.LiteRtCpu) {
                "The accepted waveform output was not produced by LiteRT CPU."
            }
            require(autoDiagnostics.fallbackStage == MdxLiteRtAutoFailureStage.GpuInvocation) {
                "Unexpected Auto fallback stage: ${autoDiagnostics.fallbackStage}."
            }
            require(autoDiagnostics.state == MdxLiteRtAutoSessionState.CpuFallback) {
                "Unexpected Auto state before close: ${autoDiagnostics.state}."
            }

            val statistics = MdxLiteRtBoundedGpuRuntime.statistics()
            require(statistics.dispatchCount > 0L)
            require(statistics.dispatchCount == statistics.eventWaitCount) {
                "Bounded GPU dispatch/event-wait mismatch during Auto fallback."
            }
            report.put("runtimeArtifactVersion", cpuInstallation.identity.runtimeArtifactVersion)
                .put("gpuProfileId", capability.profileId)
                .put("gpuKernelBatchSize", capability.kernelBatchSize)
                .put("gpuCommandQueueWindowSize", capability.commandQueueWindowSize)
                .put("probeInvocationCount", probeInvocationCount)
                .put("stagedApiRejected", true)
                .put("inputPreserved", true)
                .put("cpuReceivedOriginalWaveform", true)
                .put("fallbackOutputExactToDirectCpu", true)
                .put("acceptedOutputBackend", autoDiagnostics.acceptedOutputBackend.name)
                .put("fallbackStage", autoDiagnostics.fallbackStage.name)
                .put("autoStateBeforeClose", autoDiagnostics.state.name)
                .put("dispatchCount", statistics.dispatchCount)
                .put("eventWaitCount", statistics.eventWaitCount)
                .put("lifecycle", tracker.toJson())
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

    private class TrackingWaveformFactory(
        private val delegate: MdxInferenceSessionFactory,
        private val tracker: LifecycleTracker,
        private val injectRecoverableWaveformFailure: Boolean,
    ) : MdxInferenceSessionFactory {
        override val factoryId: String = "tracked-waveform-${delegate.factoryId}"
        override val backend: MdxInferenceBackend = delegate.backend

        override fun create(
            artifact: MdxModelArtifact,
            profile: MdxExecutionProfile,
            runtimeSettings: MdxRuntimeSettings,
        ): MdxInferenceSession {
            tracker.createStarted(backend)
            return TrackingWaveformSession(
                delegate = delegate.create(artifact, profile, runtimeSettings),
                backend = backend,
                tracker = tracker,
                injectRecoverableWaveformFailure = injectRecoverableWaveformFailure,
            )
        }
    }

    private class TrackingWaveformSession(
        private val delegate: MdxInferenceSession,
        private val backend: MdxInferenceBackend,
        private val tracker: LifecycleTracker,
        private val injectRecoverableWaveformFailure: Boolean,
    ) : MdxInferenceSession, MdxWaveformInferenceSession {
        private val waveformDelegate = delegate as? MdxWaveformInferenceSession
            ?: error("${delegate.diagnostics.backend} session lost managed waveform support.")
        private var closed = false

        init {
            tracker.created(backend)
        }

        override val diagnostics
            get() = delegate.diagnostics
        override val waveformSlotCount: Int
            get() = waveformDelegate.waveformSlotCount
        override val waveformDspImplementationId: String
            get() = waveformDelegate.waveformDspImplementationId
        override val supportsStagedWaveformExecution: Boolean
            get() = waveformDelegate.supportsStagedWaveformExecution

        override fun run(
            inputNchw: FloatArray,
            shouldCancel: () -> Boolean,
        ): FloatArray {
            tracker.tensorInvoked(backend)
            return delegate.run(inputNchw, shouldCancel)
        }

        override fun runWaveform(
            waveform: Array<FloatArray>,
            shouldCancel: () -> Boolean,
        ): Array<FloatArray> {
            tracker.waveformInvoked(backend, waveform)
            val output = waveformDelegate.runWaveform(waveform, shouldCancel)
            tracker.waveformCompleted(backend)
            if (injectRecoverableWaveformFailure) {
                throw MdxLiteRtBackendException(
                    stage = MdxLiteRtFailureStage.Invocation,
                    isRecoverable = true,
                    cause = IllegalStateException("Injected recoverable GPU waveform failure."),
                )
            }
            return output
        }

        override fun prepareWaveform(
            waveform: Array<FloatArray>,
            slot: Int,
            shouldCancel: () -> Boolean,
        ) {
            tracker.stagedMethodInvoked(backend, "prepare")
            waveformDelegate.prepareWaveform(waveform, slot, shouldCancel)
        }

        override fun invokePreparedWaveform(
            slot: Int,
            shouldCancel: () -> Boolean,
        ) {
            tracker.stagedMethodInvoked(backend, "invoke")
            waveformDelegate.invokePreparedWaveform(slot, shouldCancel)
        }

        override fun readPreparedWaveform(
            slot: Int,
            shouldCancel: () -> Boolean,
        ): Array<FloatArray> {
            tracker.stagedMethodInvoked(backend, "read")
            return waveformDelegate.readPreparedWaveform(slot, shouldCancel)
        }

        override fun discardPreparedWaveform(slot: Int) {
            tracker.stagedMethodInvoked(backend, "discard")
            waveformDelegate.discardPreparedWaveform(slot)
        }

        override fun close() {
            if (closed) return
            closed = true
            tracker.closeStarted(backend)
            try {
                delegate.close()
            } finally {
                tracker.closed(backend)
            }
        }
    }

    private class LifecycleTracker {
        var gpuCreateCount = 0
            private set
        var cpuCreateCount = 0
            private set
        var activeGpuSessions = 0
            private set
        var activeCpuSessions = 0
            private set
        var gpuCloseCount = 0
            private set
        var cpuCloseCount = 0
            private set
        var gpuCloseEndedBeforeCpuCreateStarted = false
            private set
        var gpuTensorInvocationCount = 0
            private set
        var gpuWaveformInvocationCount = 0
            private set
        var cpuWaveformInvocationCount = 0
            private set
        var gpuWaveformCompletedBeforeFailure = false
            private set
        var stagedMethodInvocationCount = 0
            private set
        var cpuWaveformReference: Array<FloatArray>? = null
            private set
        var cpuWaveformInput: Array<FloatArray>? = null
            private set
        private val events = mutableListOf<String>()

        @Synchronized
        fun createStarted(backend: MdxInferenceBackend) {
            if (backend == MdxInferenceBackend.LiteRtCpu) {
                gpuCloseEndedBeforeCpuCreateStarted =
                    gpuCloseCount == 1 && activeGpuSessions == 0
            }
            events += "${backend.name}-create-start"
        }

        @Synchronized
        fun created(backend: MdxInferenceBackend) {
            when (backend) {
                MdxInferenceBackend.LiteRtGpu -> {
                    gpuCreateCount += 1
                    activeGpuSessions += 1
                    events += "gpu-created"
                }
                MdxInferenceBackend.LiteRtCpu -> {
                    cpuCreateCount += 1
                    activeCpuSessions += 1
                    events += "cpu-created"
                }
                else -> error("Unexpected tracked backend: $backend")
            }
        }

        @Synchronized
        fun closeStarted(backend: MdxInferenceBackend) {
            events += "${backend.name}-close-start"
        }

        @Synchronized
        fun closed(backend: MdxInferenceBackend) {
            when (backend) {
                MdxInferenceBackend.LiteRtGpu -> {
                    check(activeGpuSessions > 0)
                    activeGpuSessions -= 1
                    gpuCloseCount += 1
                    events += "gpu-close-end"
                }
                MdxInferenceBackend.LiteRtCpu -> {
                    check(activeCpuSessions > 0)
                    activeCpuSessions -= 1
                    cpuCloseCount += 1
                    events += "cpu-close-end"
                }
                else -> error("Unexpected tracked backend: $backend")
            }
        }

        @Synchronized
        fun tensorInvoked(backend: MdxInferenceBackend) {
            if (backend == MdxInferenceBackend.LiteRtGpu) gpuTensorInvocationCount += 1
            events += "${backend.name}-tensor"
        }

        @Synchronized
        fun waveformInvoked(
            backend: MdxInferenceBackend,
            waveform: Array<FloatArray>,
        ) {
            when (backend) {
                MdxInferenceBackend.LiteRtGpu -> gpuWaveformInvocationCount += 1
                MdxInferenceBackend.LiteRtCpu -> {
                    cpuWaveformInvocationCount += 1
                    cpuWaveformReference = waveform
                    cpuWaveformInput = waveform.map(FloatArray::copyOf).toTypedArray()
                }
                else -> error("Unexpected tracked backend: $backend")
            }
            events += "${backend.name}-waveform"
        }

        @Synchronized
        fun waveformCompleted(backend: MdxInferenceBackend) {
            if (backend == MdxInferenceBackend.LiteRtGpu) {
                gpuWaveformCompletedBeforeFailure = true
            }
            events += "${backend.name}-waveform-complete"
        }

        @Synchronized
        fun stagedMethodInvoked(backend: MdxInferenceBackend, method: String) {
            stagedMethodInvocationCount += 1
            events += "${backend.name}-staged-$method"
        }

        @Synchronized
        fun toJson(): JSONObject = JSONObject()
            .put("gpuCreateCount", gpuCreateCount)
            .put("cpuCreateCount", cpuCreateCount)
            .put("gpuCloseCount", gpuCloseCount)
            .put("cpuCloseCount", cpuCloseCount)
            .put("activeGpuSessions", activeGpuSessions)
            .put("activeCpuSessions", activeCpuSessions)
            .put("gpuCloseEndedBeforeCpuCreateStarted", gpuCloseEndedBeforeCpuCreateStarted)
            .put("gpuTensorInvocationCount", gpuTensorInvocationCount)
            .put("gpuWaveformInvocationCount", gpuWaveformInvocationCount)
            .put("cpuWaveformInvocationCount", cpuWaveformInvocationCount)
            .put("gpuWaveformCompletedBeforeFailure", gpuWaveformCompletedBeforeFailure)
            .put("stagedMethodInvocationCount", stagedMethodInvocationCount)
            .put("events", JSONArray(events))
    }

    private fun MdxInferenceSession.requireWaveformSession(): MdxWaveformInferenceSession =
        this as? MdxWaveformInferenceSession
            ?: error("${diagnostics.backend} session lost managed waveform support.")

    private fun fixture(chunkSize: Int, sampleRate: Int): Array<FloatArray> = Array(2) { channel ->
        FloatArray(chunkSize) { sample ->
            val time = sample.toDouble() / sampleRate
            val frequency = if (channel == 0) 229.0 else 347.0
            (0.17 * sin(2.0 * PI * frequency * time) +
                0.04 * sin(2.0 * PI * (frequency * 1.61) * time)).toFloat()
        }
    }

    private fun Array<FloatArray>.deepCopy(): Array<FloatArray> =
        map(FloatArray::copyOf).toTypedArray()

    private fun Array<FloatArray>.contentEquals(other: Array<FloatArray>): Boolean =
        size == other.size && indices.all { index -> this[index].contentEquals(other[index]) }

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

    private companion object {
        const val EXPECTED_RUNTIME_ARTIFACT = "2.2.0-bss.2"
        const val CPU_THREADS = 4
        const val STAGING_DIRECTORY = "litert-validation-staging"
        const val REPORT_DIRECTORY = "litert-auto-waveform-fallback-validation"
        const val ARG_RUN_ID = "runId"
        const val ARG_MODEL_ID = "modelId"
        const val ARG_MODEL_PATH = "modelPath"
        const val ARG_PROCESS_ABI = "processAbi"
    }
}
