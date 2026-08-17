package com.mardous.booming.separation.model.litert

import android.content.Context
import android.os.Process
import android.os.SystemClock
import com.mardous.booming.BuildConfig
import com.mardous.booming.AppProcessResolver
import com.mardous.booming.separation.model.MdxCompatibilityPolicy
import com.mardous.booming.separation.model.MdxExecutionProfile
import com.mardous.booming.separation.model.MdxInferenceBackend
import com.mardous.booming.separation.model.MdxInferenceSession
import com.mardous.booming.separation.model.MdxInferenceSessionFactory
import com.mardous.booming.separation.model.MdxModelArtifact
import com.mardous.booming.separation.model.MdxRuntimeDiagnostics
import com.mardous.booming.separation.model.MdxRuntimeSettings
import com.mardous.booming.separation.model.MdxWaveformInferenceSession
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

private val REMOTE_FAULT_TOKEN_PATTERN = Regex("^[A-Za-z0-9._-]{1,96}$")

internal enum class MdxLiteRtRemoteFailpoint(
    val argumentValue: String,
    val expectedFallbackStage: MdxLiteRtAutoFailureStage?,
    val requiresProcessRecycle: Boolean = false,
) {
    None("none", null),
    Setup("setup", MdxLiteRtAutoFailureStage.GpuSetup),
    Probe("probe", MdxLiteRtAutoFailureStage.GpuProbeValidation),
    Invocation("invocation", MdxLiteRtAutoFailureStage.GpuInvocation),
    OutputRead("output-read", MdxLiteRtAutoFailureStage.GpuOutputRead),
    NonFinite("non-finite", MdxLiteRtAutoFailureStage.GpuOutputValidation),
    Cleanup("cleanup", MdxLiteRtAutoFailureStage.GpuCleanup, requiresProcessRecycle = true),
    ;

    companion object {
        fun parse(value: String?): MdxLiteRtRemoteFailpoint = entries.singleOrNull {
            it.argumentValue == (value ?: None.argumentValue)
        } ?: error("Unsupported remote LiteRT Auto failpoint: $value")
    }
}

@Serializable
internal data class MdxLiteRtRemoteFaultControl(
    val schemaVersion: Int,
    val token: String,
    val failpoint: String,
    val failureInvocationCount: Int,
    val expiresAtElapsedRealtimeMs: Long,
) {
    init {
        require(schemaVersion == SCHEMA_VERSION) {
            "Unsupported remote fault-control schema."
        }
        require(REMOTE_FAULT_TOKEN_PATTERN.matches(token)) {
            "Remote fault-control token is invalid."
        }
        require(MdxLiteRtRemoteFailpoint.parse(failpoint) != MdxLiteRtRemoteFailpoint.None) {
            "Remote fault control must select a failpoint."
        }
        require(failureInvocationCount >= 2) {
            "Remote GPU failure must occur after the finite-output probe."
        }
        require(expiresAtElapsedRealtimeMs > 0L) {
            "Remote fault-control expiry is invalid."
        }
    }

    companion object {
        const val SCHEMA_VERSION = 1
    }
}

@Serializable
internal data class MdxLiteRtRemoteFaultEvidence(
    val schemaVersion: Int,
    val token: String,
    val failpoint: String,
    val pid: Int,
    val processName: String,
    val failureInvocationCount: Int,
    val gpuCreateCount: Int,
    val gpuCloseCount: Int,
    val gpuInvocationCount: Int,
    val cpuCreateCount: Int,
    val cpuCloseCount: Int,
    val injectedAtElapsedRealtimeMs: Long?,
    val events: List<String>,
) {
    init {
        require(schemaVersion == SCHEMA_VERSION) {
            "Unsupported remote fault-evidence schema."
        }
    }

    companion object {
        const val SCHEMA_VERSION = 1
    }
}

internal object MdxLiteRtRemoteFaultInjection {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        explicitNulls = false
    }

    fun arm(
        context: Context,
        token: String,
        failpoint: MdxLiteRtRemoteFailpoint,
        failureInvocationCount: Int,
        lifetimeMs: Long = DEFAULT_CONTROL_LIFETIME_MS,
    ) {
        check(BuildConfig.DEBUG) { "Remote LiteRT fault injection is debug-only." }
        require(failpoint != MdxLiteRtRemoteFailpoint.None) {
            "A concrete remote LiteRT failpoint is required."
        }
        require(lifetimeMs in 1L..MAXIMUM_CONTROL_LIFETIME_MS) {
            "Remote LiteRT fault-control lifetime is invalid."
        }
        val directory = directory(context).apply {
            check(isDirectory || mkdirs()) { "Unable to create remote fault directory." }
        }
        evidenceFile(directory).delete()
        writeAtomically(
            controlFile(directory),
            json.encodeToString(
                MdxLiteRtRemoteFaultControl(
                    schemaVersion = MdxLiteRtRemoteFaultControl.SCHEMA_VERSION,
                    token = token,
                    failpoint = failpoint.argumentValue,
                    failureInvocationCount = failureInvocationCount,
                    expiresAtElapsedRealtimeMs = Math.addExact(
                        SystemClock.elapsedRealtime(),
                        lifetimeMs,
                    ),
                ),
            ),
        )
    }

    fun clear(context: Context) {
        if (!BuildConfig.DEBUG) return
        controlFile(directory(context)).delete()
    }

    fun clearAll(context: Context) {
        if (!BuildConfig.DEBUG) return
        directory(context).deleteRecursively()
    }

    fun readEvidence(context: Context): MdxLiteRtRemoteFaultEvidence? {
        if (!BuildConfig.DEBUG) return null
        return runCatching {
            evidenceFile(directory(context))
                .takeIf(File::isFile)
                ?.readText()
                ?.let { encoded ->
                    json.decodeFromString<MdxLiteRtRemoteFaultEvidence>(encoded)
                }
        }.getOrNull()
    }

    fun takeFactoryIfArmed(context: Context): MdxInferenceSessionFactory? {
        if (!BuildConfig.DEBUG ||
            !AppProcessResolver.resolve(context).processName.endsWith(REMOTE_PROCESS_SUFFIX)
        ) {
            return null
        }
        val directory = directory(context)
        val controlFile = controlFile(directory)
        val control = runCatching {
            controlFile.takeIf(File::isFile)
                ?.readText()
                ?.let { encoded ->
                    json.decodeFromString<MdxLiteRtRemoteFaultControl>(encoded)
                }
        }.getOrNull()
        controlFile.delete()
        if (control == null || SystemClock.elapsedRealtime() > control.expiresAtElapsedRealtimeMs) {
            return null
        }
        return MdxLiteRtRemoteFaultController(
            context = context.applicationContext,
            directory = directory,
            control = control,
            json = json,
        ).createFactory()
    }

    private fun directory(context: Context) =
        File(context.cacheDir, VALIDATION_DIRECTORY)

    private fun controlFile(directory: File) = File(directory, CONTROL_FILE)

    private fun evidenceFile(directory: File) = File(directory, EVIDENCE_FILE)

    internal fun writeAtomically(file: File, value: String) {
        check(file.parentFile?.isDirectory == true || file.parentFile?.mkdirs() == true) {
            "Unable to create remote fault evidence directory."
        }
        val temporary = File(file.parentFile, "${file.name}.tmp-${Process.myPid()}")
        temporary.writeText(value)
        check(temporary.renameTo(file) || run {
            file.delete()
            temporary.renameTo(file)
        }) { "Unable to publish remote fault evidence." }
    }

    private const val REMOTE_PROCESS_SUFFIX = ":source_separation"
    private const val VALIDATION_DIRECTORY = "source-separation-validation/remote-gpu-fault"
    private const val CONTROL_FILE = "control.json"
    private const val EVIDENCE_FILE = "evidence.json"
    private const val DEFAULT_CONTROL_LIFETIME_MS = 5L * 60L * 1_000L
    private const val MAXIMUM_CONTROL_LIFETIME_MS = 15L * 60L * 1_000L
}

internal interface MdxLiteRtRemoteFaultSessionTracker {
    fun created(backend: MdxInferenceBackend)
    fun closed(backend: MdxInferenceBackend)
    fun invoked(backend: MdxInferenceBackend): Int
    fun injected(stage: String)
    fun shouldInjectAt(invocationCount: Int): Boolean
    fun selectedFailpoint(): MdxLiteRtRemoteFailpoint
}

internal object MdxLiteRtRemoteFaultCompatibilityPolicies {
    val gpu = MdxCompatibilityPolicy.AllowUntestedInternal
    val cpu = MdxCompatibilityPolicy.AllowUserAttempts
}

private class MdxLiteRtRemoteFaultController(
    context: Context,
    private val directory: File,
    private val control: MdxLiteRtRemoteFaultControl,
    private val json: Json,
) : MdxLiteRtRemoteFaultSessionTracker {
    private val failpoint = MdxLiteRtRemoteFailpoint.parse(control.failpoint)
    private val processName = AppProcessResolver.resolve(context).processName
    private val pid = Process.myPid()
    private val events = mutableListOf<String>()
    private var gpuCreateCount = 0
    private var gpuCloseCount = 0
    private var gpuInvocationCount = 0
    private var cpuCreateCount = 0
    private var cpuCloseCount = 0
    private var injectedAtElapsedRealtimeMs: Long? = null
    private val applicationContext = context.applicationContext

    init {
        publish()
    }

    fun createFactory(): MdxInferenceSessionFactory {
        val gpuProfile = MdxLiteRtGpuRuntimeProfile.AutomaticFp32V1
        val gpuFactory = TrackingFactory(
            delegate = MdxLiteRtGpuInferenceSessionFactory(
                runtimeProfile = gpuProfile,
                compatibilityPolicy = MdxLiteRtRemoteFaultCompatibilityPolicies.gpu,
            ),
            controller = this,
        )
        val cpuFactory = TrackingFactory(
            delegate = MdxLiteRtCpuInferenceSessionFactory(
                compatibilityPolicy = MdxLiteRtRemoteFaultCompatibilityPolicies.cpu,
            ),
            controller = this,
        )
        return MdxLiteRtAutoInferenceSessionFactory(
            gpuRuntimeProfile = gpuProfile,
            gpuCompatibilityPolicy = MdxLiteRtRemoteFaultCompatibilityPolicies.gpu,
            cpuCompatibilityPolicy = MdxLiteRtRemoteFaultCompatibilityPolicies.cpu,
            gpuEligibilityProvider = AndroidMdxLiteRtGpuEligibilityProvider(applicationContext),
            gpuProbe = { session, profile ->
                val output = session.run(FloatArray(profile.inputTensor.elementCount))
                if (failpoint == MdxLiteRtRemoteFailpoint.Probe) {
                    injected("probe")
                    MdxLiteRtGpuProbeResult.rejected("Injected remote GPU probe rejection.")
                } else if (output.size == profile.outputTensor.elementCount &&
                    output.all(Float::isFinite)
                ) {
                    MdxLiteRtGpuProbeResult.accepted("Finite FP32 output probe passed.")
                } else {
                    MdxLiteRtGpuProbeResult.rejected(
                        "GPU output shape or finite-value validation failed.",
                    )
                }
            },
            gpuFactory = gpuFactory,
            cpuFactory = cpuFactory,
        )
    }

    @Synchronized
    override fun created(backend: MdxInferenceBackend) {
        when (backend) {
            MdxInferenceBackend.LiteRtGpu -> gpuCreateCount += 1
            MdxInferenceBackend.LiteRtCpu -> cpuCreateCount += 1
            else -> error("Unexpected tracked remote backend: $backend")
        }
        eventLocked("${backend.name}-create")
    }

    @Synchronized
    override fun closed(backend: MdxInferenceBackend) {
        when (backend) {
            MdxInferenceBackend.LiteRtGpu -> gpuCloseCount += 1
            MdxInferenceBackend.LiteRtCpu -> cpuCloseCount += 1
            else -> error("Unexpected tracked remote backend: $backend")
        }
        eventLocked("${backend.name}-close")
    }

    @Synchronized
    override fun invoked(backend: MdxInferenceBackend): Int {
        val count = if (backend == MdxInferenceBackend.LiteRtGpu) {
            ++gpuInvocationCount
        } else {
            0
        }
        eventLocked("${backend.name}-run-$count")
        return count
    }

    @Synchronized
    override fun injected(stage: String) {
        if (injectedAtElapsedRealtimeMs == null) {
            injectedAtElapsedRealtimeMs = SystemClock.elapsedRealtime()
        }
        eventLocked("inject-$stage")
    }

    override fun shouldInjectAt(invocationCount: Int): Boolean =
        invocationCount == control.failureInvocationCount

    override fun selectedFailpoint(): MdxLiteRtRemoteFailpoint = failpoint

    @Synchronized
    private fun eventLocked(value: String) {
        events += value
        publishLocked()
    }

    @Synchronized
    private fun publish() = publishLocked()

    private fun publishLocked() {
        MdxLiteRtRemoteFaultInjection.writeAtomically(
            File(directory, "evidence.json"),
            json.encodeToString(
                MdxLiteRtRemoteFaultEvidence(
                    schemaVersion = MdxLiteRtRemoteFaultEvidence.SCHEMA_VERSION,
                    token = control.token,
                    failpoint = failpoint.argumentValue,
                    pid = pid,
                    processName = processName,
                    failureInvocationCount = control.failureInvocationCount,
                    gpuCreateCount = gpuCreateCount,
                    gpuCloseCount = gpuCloseCount,
                    gpuInvocationCount = gpuInvocationCount,
                    cpuCreateCount = cpuCreateCount,
                    cpuCloseCount = cpuCloseCount,
                    injectedAtElapsedRealtimeMs = injectedAtElapsedRealtimeMs,
                    events = events.toList(),
                ),
            ),
        )
    }

    private class TrackingFactory(
        private val delegate: MdxInferenceSessionFactory,
        private val controller: MdxLiteRtRemoteFaultController,
    ) : MdxInferenceSessionFactory {
        override val factoryId: String = "remote-fault-${delegate.factoryId}"
        override val backend: MdxInferenceBackend = delegate.backend

        override fun create(
            artifact: MdxModelArtifact,
            profile: MdxExecutionProfile,
            runtimeSettings: MdxRuntimeSettings,
        ): MdxInferenceSession {
            val session = MdxLiteRtRemoteFaultTrackingSession(
                delegate = delegate.create(artifact, profile, runtimeSettings),
                controller = controller,
            )
            if (backend == MdxInferenceBackend.LiteRtGpu &&
                controller.selectedFailpoint() == MdxLiteRtRemoteFailpoint.Setup
            ) {
                session.close()
                controller.injected("setup")
                throw MdxLiteRtBackendException(
                    stage = MdxLiteRtFailureStage.ModelCompile,
                    isRecoverable = true,
                    cause = IllegalStateException("Injected remote GPU setup failure."),
                )
            }
            return session
        }
    }

}

internal class MdxLiteRtRemoteFaultTrackingSession(
    private val delegate: MdxInferenceSession,
    private val controller: MdxLiteRtRemoteFaultSessionTracker,
) : MdxInferenceSession, MdxWaveformInferenceSession {
    override val diagnostics: MdxRuntimeDiagnostics
        get() = delegate.diagnostics
    private val backend = delegate.diagnostics.backend
    private var closed = false
    private var cleanupFailureArmed = false

    init {
        controller.created(backend)
    }

    override fun run(
        inputNchw: FloatArray,
        shouldCancel: () -> Boolean,
    ): FloatArray = runTrackedInvocation {
        delegate.run(inputNchw, shouldCancel)
    }

    override val waveformSlotCount: Int
        get() = (delegate as? MdxWaveformInferenceSession)?.waveformSlotCount ?: 0
    override val waveformDspImplementationId: String
        get() = (delegate as? MdxWaveformInferenceSession)?.waveformDspImplementationId
            ?: "unavailable"
    override val supportsStagedWaveformExecution: Boolean = false

    override fun runWaveform(
        waveform: Array<FloatArray>,
        shouldCancel: () -> Boolean,
    ): Array<FloatArray> = runTrackedInvocation {
        waveformDelegate().runWaveform(waveform, shouldCancel)
    }

    override fun prepareWaveform(
        waveform: Array<FloatArray>,
        slot: Int,
        shouldCancel: () -> Boolean,
    ): Nothing = stagedWaveformUnsupported()

    override fun invokePreparedWaveform(
        slot: Int,
        shouldCancel: () -> Boolean,
    ): Nothing = stagedWaveformUnsupported()

    override fun readPreparedWaveform(
        slot: Int,
        shouldCancel: () -> Boolean,
    ): Nothing = stagedWaveformUnsupported()

    override fun discardPreparedWaveform(slot: Int): Nothing = stagedWaveformUnsupported()

    private fun stagedWaveformUnsupported(): Nothing = throw UnsupportedOperationException(
        "Remote fault injection requires transactional runWaveform for invocation accounting.",
    )

    private fun <T> runTrackedInvocation(invocation: () -> T): T {
        val output = invocation()
        val invocationCount = controller.invoked(backend)
        if (backend != MdxInferenceBackend.LiteRtGpu ||
            !controller.shouldInjectAt(invocationCount)
        ) {
            return output
        }
        return when (controller.selectedFailpoint()) {
            MdxLiteRtRemoteFailpoint.Invocation -> throw injectedFailure(
                "invocation",
                MdxLiteRtFailureStage.Invocation,
            )
            MdxLiteRtRemoteFailpoint.OutputRead -> throw injectedFailure(
                "output-read",
                MdxLiteRtFailureStage.OutputRead,
            )
            MdxLiteRtRemoteFailpoint.NonFinite -> throw injectedFailure(
                "non-finite",
                MdxLiteRtFailureStage.OutputValidation,
            )
            MdxLiteRtRemoteFailpoint.Cleanup -> {
                cleanupFailureArmed = true
                throw injectedFailure("pre-cleanup-output-read", MdxLiteRtFailureStage.OutputRead)
            }
            else -> output
        }
    }

    private fun waveformDelegate(): MdxWaveformInferenceSession =
        delegate as? MdxWaveformInferenceSession
            ?: error("The tracked inference session has no waveform capability.")

    override fun close() {
        if (closed) return
        closed = true
        var delegateFailure: Throwable? = null
        try {
            delegate.close()
        } catch (error: Throwable) {
            delegateFailure = error
        } finally {
            controller.closed(backend)
        }
        if (cleanupFailureArmed) {
            controller.injected("cleanup")
            val injected = MdxLiteRtBackendException(
                stage = MdxLiteRtFailureStage.Cleanup,
                isRecoverable = false,
                cause = IllegalStateException("Injected remote GPU cleanup failure."),
            )
            delegateFailure?.let(injected::addSuppressed)
            throw injected
        }
        delegateFailure?.let { throw it }
    }

    private fun injectedFailure(
        label: String,
        stage: MdxLiteRtFailureStage,
    ): MdxLiteRtBackendException {
        controller.injected(label)
        return MdxLiteRtBackendException(
            stage = stage,
            isRecoverable = true,
            cause = IllegalStateException("Injected remote GPU $label failure."),
        )
    }
}
