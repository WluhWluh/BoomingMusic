package com.mardous.booming.separation.model.litert

import android.app.ActivityManager
import android.content.Context
import dalvik.system.BaseDexClassLoader
import com.mardous.booming.separation.model.AndroidMdxRuntimePlatformProvider
import com.mardous.booming.separation.model.MdxCompatibilityPolicy
import com.mardous.booming.separation.model.MdxExecutionProfile
import com.mardous.booming.separation.model.MdxInferenceBackend
import com.mardous.booming.separation.model.MdxInferenceSession
import com.mardous.booming.separation.model.MdxInferenceSessionFactory
import com.mardous.booming.separation.model.MdxLiteRtCompatibilityResolver
import com.mardous.booming.separation.model.MdxModelArtifact
import com.mardous.booming.separation.model.MdxRuntimeAbi
import com.mardous.booming.separation.model.MdxRuntimeDiagnostics
import com.mardous.booming.separation.model.MdxRuntimePlatform
import com.mardous.booming.separation.model.MdxRuntimePlatformProvider
import com.mardous.booming.separation.model.MdxRuntimeSettings
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.math.max

internal enum class MdxLiteRtAutoSessionState {
    GpuActive,
    CpuDirect,
    CpuFallback,
    Terminal,
    Closed,
}

internal enum class MdxLiteRtAutoFailureStage {
    GpuSetup,
    GpuTensorSetup,
    GpuProbeWrite,
    GpuProbeInvocation,
    GpuProbeRead,
    GpuProbeValidation,
    GpuInputWrite,
    GpuInvocation,
    GpuOutputRead,
    GpuOutputValidation,
    GpuCleanup,
    CpuSetup,
    CpuInvocation,
}

internal enum class MdxLiteRtGpuEligibilityReason {
    Eligible,
    GpuCompatibilityUnavailable,
    CpuOnlyAbi,
    BoundedRuntimeUnavailable,
    AcceleratorLibraryUnavailable,
    DeviceLowMemory,
    InsufficientAvailableMemory,
}

internal data class MdxLiteRtGpuEligibilityDecision(
    val isEligible: Boolean,
    val reason: MdxLiteRtGpuEligibilityReason,
    val detail: String,
) {
    init {
        require(isEligible == (reason == MdxLiteRtGpuEligibilityReason.Eligible)) {
            "LiteRT GPU eligibility outcome and reason disagree."
        }
        require(detail.isNotBlank()) { "LiteRT GPU eligibility detail is empty." }
    }

    companion object {
        fun eligible(detail: String) = MdxLiteRtGpuEligibilityDecision(
            isEligible = true,
            reason = MdxLiteRtGpuEligibilityReason.Eligible,
            detail = detail,
        )

        fun ineligible(reason: MdxLiteRtGpuEligibilityReason, detail: String) =
            MdxLiteRtGpuEligibilityDecision(
                isEligible = false,
                reason = reason,
                detail = detail,
            )
    }
}

internal fun interface MdxLiteRtGpuEligibilityProvider {
    fun evaluate(
        artifact: MdxModelArtifact,
        profile: MdxExecutionProfile,
        platform: MdxRuntimePlatform,
    ): MdxLiteRtGpuEligibilityDecision
}

internal class AndroidMdxLiteRtGpuEligibilityProvider(
    context: Context,
    private val minimumAvailableBytes: Long = DEFAULT_MINIMUM_AVAILABLE_BYTES,
    private val boundedCapabilityProvider: MdxLiteRtBoundedGpuCapabilityProvider =
        MdxLiteRtNativeBoundedGpuCapabilityProvider,
) : MdxLiteRtGpuEligibilityProvider {
    private val applicationContext = context.applicationContext

    init {
        require(minimumAvailableBytes > 0L) {
            "LiteRT GPU minimum available memory must be positive."
        }
    }

    override fun evaluate(
        artifact: MdxModelArtifact,
        profile: MdxExecutionProfile,
        platform: MdxRuntimePlatform,
    ): MdxLiteRtGpuEligibilityDecision {
        if (platform.runtimeAbi != MdxRuntimeAbi.Arm64V8a) {
            return MdxLiteRtGpuEligibilityDecision.ineligible(
                MdxLiteRtGpuEligibilityReason.CpuOnlyAbi,
                "${platform.runtimeAbi.androidName} has no packaged GPU accelerator.",
            )
        }
        val capability = MdxLiteRtBoundedGpuContract.evaluate(
            boundedCapabilityProvider.query()
        )
        if (!capability.isExact) {
            return MdxLiteRtGpuEligibilityDecision.ineligible(
                MdxLiteRtGpuEligibilityReason.BoundedRuntimeUnavailable,
                capability.detail,
            )
        }
        val acceleratorPath = (applicationContext.classLoader as? BaseDexClassLoader)
            ?.findLibrary(GPU_ACCELERATOR_LIBRARY)
        if (acceleratorPath.isNullOrBlank()) {
            return MdxLiteRtGpuEligibilityDecision.ineligible(
                MdxLiteRtGpuEligibilityReason.AcceleratorLibraryUnavailable,
                "The app class loader cannot resolve $GPU_ACCELERATOR_LIBRARY.",
            )
        }
        val memoryInfo = ActivityManager.MemoryInfo().also { info ->
            applicationContext.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        }
        if (memoryInfo.lowMemory) {
            return MdxLiteRtGpuEligibilityDecision.ineligible(
                MdxLiteRtGpuEligibilityReason.DeviceLowMemory,
                "Android reports a low-memory state.",
            )
        }
        val tensorBytes = Math.multiplyExact(
            profile.inputTensor.elementCount.toLong(),
            Float.SIZE_BYTES.toLong(),
        )
        val workingSetFloor = Math.addExact(
            artifact.byteSize,
            Math.multiplyExact(tensorBytes, ESTIMATED_LIVE_TENSOR_COUNT),
        )
        val requiredBytes = max(minimumAvailableBytes, workingSetFloor)
        if (memoryInfo.availMem < requiredBytes) {
            return MdxLiteRtGpuEligibilityDecision.ineligible(
                MdxLiteRtGpuEligibilityReason.InsufficientAvailableMemory,
                "Available memory ${memoryInfo.availMem} is below the provisional " +
                    "GPU floor $requiredBytes.",
            )
        }
        return MdxLiteRtGpuEligibilityDecision.eligible(
            "${capability.detail}, accelerator=${File(acceleratorPath).name}, " +
                "availableMemoryBytes=${memoryInfo.availMem}",
        )
    }

    companion object {
        private const val GPU_ACCELERATOR_LIBRARY = "LiteRtClGlAccelerator"
        private const val ESTIMATED_LIVE_TENSOR_COUNT = 4L
        private const val DEFAULT_MINIMUM_AVAILABLE_BYTES = 512L * 1024L * 1024L
    }
}

internal data class MdxLiteRtGpuProbeResult(
    val isAccepted: Boolean,
    val detail: String,
) {
    init {
        require(detail.isNotBlank()) { "LiteRT GPU probe detail is empty." }
    }

    companion object {
        fun accepted(detail: String) = MdxLiteRtGpuProbeResult(true, detail)
        fun rejected(detail: String) = MdxLiteRtGpuProbeResult(false, detail)
    }
}

internal fun interface MdxLiteRtGpuProbe {
    fun validate(
        session: MdxInferenceSession,
        profile: MdxExecutionProfile,
    ): MdxLiteRtGpuProbeResult
}

internal data class MdxLiteRtAutoDiagnostics(
    val state: MdxLiteRtAutoSessionState,
    val gpuProfileId: String,
    val eligibilityReason: MdxLiteRtGpuEligibilityReason,
    val eligibilityDetail: String,
    val gpuAttempted: Boolean,
    val activeBackend: MdxInferenceBackend?,
    val acceptedOutputBackend: MdxInferenceBackend?,
    val fallbackStage: MdxLiteRtAutoFailureStage?,
    val fallbackReason: String?,
    val gpuSetupNanos: Long?,
    val gpuProbeNanos: Long?,
    val gpuInferenceNanos: Long?,
    val cpuSetupNanos: Long?,
    val cpuInferenceNanos: Long?,
)

internal interface MdxLiteRtAutoDiagnosticsProvider {
    val autoDiagnostics: MdxLiteRtAutoDiagnostics
}

internal class MdxLiteRtAutoInferenceException(
    val stage: MdxLiteRtAutoFailureStage,
    primaryFailure: Throwable,
    secondaryFailure: Throwable? = null,
) : IllegalStateException(
    "LiteRT Auto failed at ${stage.name}: ${primaryFailure.message.orEmpty()}",
    primaryFailure,
) {
    init {
        secondaryFailure?.let(::addSuppressed)
    }
}

internal class MdxLiteRtAutoInferenceSessionFactory(
    private val gpuRuntimeProfile: MdxLiteRtGpuRuntimeProfile,
    private val platformProvider: MdxRuntimePlatformProvider = AndroidMdxRuntimePlatformProvider,
    private val gpuCompatibilityPolicy: MdxCompatibilityPolicy =
        MdxCompatibilityPolicy.AllowUntestedInternal,
    private val gpuEligibilityProvider: MdxLiteRtGpuEligibilityProvider,
    private val gpuProbe: MdxLiteRtGpuProbe,
    private val gpuFactory: MdxInferenceSessionFactory,
    private val cpuFactory: MdxInferenceSessionFactory,
    private val nanoTime: () -> Long = System::nanoTime,
) : MdxInferenceSessionFactory {
    override val factoryId: String = buildString {
        append("litert-2.1.5-auto-").append(gpuRuntimeProfile.profileId)
        append('-').append(gpuFactory.factoryId)
        append('-').append(cpuFactory.factoryId)
    }
    override val backend: MdxInferenceBackend = MdxInferenceBackend.LiteRtAuto

    init {
        require(gpuFactory.backend == MdxInferenceBackend.LiteRtGpu) {
            "LiteRT Auto requires a GPU factory."
        }
        require(cpuFactory.backend == MdxInferenceBackend.LiteRtCpu) {
            "LiteRT Auto requires a CPU factory."
        }
    }

    override fun create(
        artifact: MdxModelArtifact,
        profile: MdxExecutionProfile,
        runtimeSettings: MdxRuntimeSettings,
    ): MdxInferenceSession {
        profile.validateArtifact(artifact)
        validateLiteRtExecutionProfile(profile)
        val platform = platformProvider.current()
        MdxLiteRtCompatibilityResolver.resolve(
            profile = profile,
            backend = MdxInferenceBackend.LiteRtCpu,
            platform = platform,
            policy = MdxCompatibilityPolicy.KnownGoodOnly,
        ).requireAllowed()
        val gpuCompatibility = MdxLiteRtCompatibilityResolver.resolve(
            profile = profile,
            backend = MdxInferenceBackend.LiteRtGpu,
            platform = platform,
            policy = gpuCompatibilityPolicy,
            profileId = gpuRuntimeProfile.qualificationProfileId,
            precision = gpuRuntimeProfile.precision.toMdxRuntimePrecision(),
        )
        if (!gpuCompatibility.isAllowed) {
            return createCpuDirectSession(
                artifact,
                profile,
                runtimeSettings,
                MdxLiteRtGpuEligibilityDecision.ineligible(
                    MdxLiteRtGpuEligibilityReason.GpuCompatibilityUnavailable,
                    gpuCompatibility.reason,
                ),
            )
        }
        val eligibility = gpuEligibilityProvider.evaluate(artifact, profile, platform)
        if (!eligibility.isEligible) {
            return createCpuDirectSession(artifact, profile, runtimeSettings, eligibility)
        }

        val gpuSetupStart = nanoTime()
        val gpuSession = try {
            gpuFactory.create(artifact, profile, runtimeSettings)
        } catch (error: MdxLiteRtBackendException) {
            val setupNanos = nanoTime() - gpuSetupStart
            return recoverFromSetupFailure(
                artifact,
                profile,
                runtimeSettings,
                eligibility,
                error,
                setupNanos,
            )
        }
        val gpuSetupNanos = nanoTime() - gpuSetupStart
        val probeStart = nanoTime()
        val probeResult = try {
            gpuProbe.validate(gpuSession, profile)
        } catch (error: CancellationException) {
            closeTerminalSession(gpuSession, error)
            throw error
        } catch (error: OutOfMemoryError) {
            closeTerminalSession(gpuSession, error)
            throw error
        } catch (error: MdxLiteRtBackendException) {
            val probeNanos = nanoTime() - probeStart
            return recoverFromAllocatedGpuFailure(
                artifact,
                profile,
                runtimeSettings,
                eligibility,
                gpuSession,
                error,
                error.stage.toAutoFailureStage(isProbe = true),
                gpuSetupNanos,
                probeNanos,
            )
        } catch (error: Exception) {
            closeTerminalSession(gpuSession, error)
            throw error
        }
        val probeNanos = nanoTime() - probeStart
        if (!probeResult.isAccepted) {
            val failure = MdxLiteRtBackendException(
                stage = MdxLiteRtFailureStage.OutputValidation,
                isRecoverable = true,
                cause = IllegalStateException(probeResult.detail),
            )
            return recoverFromAllocatedGpuFailure(
                artifact,
                profile,
                runtimeSettings,
                eligibility,
                gpuSession,
                failure,
                MdxLiteRtAutoFailureStage.GpuProbeValidation,
                gpuSetupNanos,
                probeNanos,
            )
        }
        return MdxLiteRtAutoInferenceSession(
            artifact = artifact,
            profile = profile,
            runtimeSettings = runtimeSettings,
            gpuRuntimeProfile = gpuRuntimeProfile,
            cpuFactory = cpuFactory,
            initialSession = gpuSession,
            initialState = MdxLiteRtAutoSessionState.GpuActive,
            eligibility = eligibility,
            gpuAttempted = true,
            initialFallbackStage = null,
            initialFallbackReason = null,
            initialGpuFailure = null,
            gpuSetupNanos = gpuSetupNanos,
            gpuProbeNanos = probeNanos,
            cpuSetupNanos = null,
            nanoTime = nanoTime,
        )
    }

    private fun createCpuDirectSession(
        artifact: MdxModelArtifact,
        profile: MdxExecutionProfile,
        runtimeSettings: MdxRuntimeSettings,
        eligibility: MdxLiteRtGpuEligibilityDecision,
    ): MdxInferenceSession {
        val setupStart = nanoTime()
        val cpuSession = cpuFactory.create(artifact, profile, runtimeSettings)
        return MdxLiteRtAutoInferenceSession(
            artifact = artifact,
            profile = profile,
            runtimeSettings = runtimeSettings,
            gpuRuntimeProfile = gpuRuntimeProfile,
            cpuFactory = cpuFactory,
            initialSession = cpuSession,
            initialState = MdxLiteRtAutoSessionState.CpuDirect,
            eligibility = eligibility,
            gpuAttempted = false,
            initialFallbackStage = null,
            initialFallbackReason = null,
            initialGpuFailure = null,
            gpuSetupNanos = null,
            gpuProbeNanos = null,
            cpuSetupNanos = nanoTime() - setupStart,
            nanoTime = nanoTime,
        )
    }

    private fun recoverFromSetupFailure(
        artifact: MdxModelArtifact,
        profile: MdxExecutionProfile,
        runtimeSettings: MdxRuntimeSettings,
        eligibility: MdxLiteRtGpuEligibilityDecision,
        failure: MdxLiteRtBackendException,
        gpuSetupNanos: Long,
    ): MdxInferenceSession {
        val stage = failure.stage.toAutoFailureStage(isProbe = false)
        requireRecoverableGpuFailure(stage, failure)
        return createCpuFallbackSession(
            artifact,
            profile,
            runtimeSettings,
            eligibility,
            failure,
            stage,
            gpuSetupNanos,
            gpuProbeNanos = null,
        )
    }

    private fun recoverFromAllocatedGpuFailure(
        artifact: MdxModelArtifact,
        profile: MdxExecutionProfile,
        runtimeSettings: MdxRuntimeSettings,
        eligibility: MdxLiteRtGpuEligibilityDecision,
        gpuSession: MdxInferenceSession,
        failure: MdxLiteRtBackendException,
        stage: MdxLiteRtAutoFailureStage,
        gpuSetupNanos: Long,
        gpuProbeNanos: Long,
    ): MdxInferenceSession {
        if (!failure.isRecoverable || failure.hasCleanupFailure()) {
            closeTerminalSession(gpuSession, failure)
            throw MdxLiteRtAutoInferenceException(stage, failure)
        }
        try {
            gpuSession.close()
        } catch (cleanupFailure: Throwable) {
            throw MdxLiteRtAutoInferenceException(
                MdxLiteRtAutoFailureStage.GpuCleanup,
                failure,
                cleanupFailure,
            )
        }
        return createCpuFallbackSession(
            artifact,
            profile,
            runtimeSettings,
            eligibility,
            failure,
            stage,
            gpuSetupNanos,
            gpuProbeNanos,
        )
    }

    private fun createCpuFallbackSession(
        artifact: MdxModelArtifact,
        profile: MdxExecutionProfile,
        runtimeSettings: MdxRuntimeSettings,
        eligibility: MdxLiteRtGpuEligibilityDecision,
        gpuFailure: MdxLiteRtBackendException,
        fallbackStage: MdxLiteRtAutoFailureStage,
        gpuSetupNanos: Long,
        gpuProbeNanos: Long?,
    ): MdxInferenceSession {
        val cpuSetupStart = nanoTime()
        val cpuSession = try {
            cpuFactory.create(artifact, profile, runtimeSettings)
        } catch (cpuFailure: Throwable) {
            throw MdxLiteRtAutoInferenceException(
                MdxLiteRtAutoFailureStage.CpuSetup,
                gpuFailure,
                cpuFailure,
            )
        }
        return MdxLiteRtAutoInferenceSession(
            artifact = artifact,
            profile = profile,
            runtimeSettings = runtimeSettings,
            gpuRuntimeProfile = gpuRuntimeProfile,
            cpuFactory = cpuFactory,
            initialSession = cpuSession,
            initialState = MdxLiteRtAutoSessionState.CpuFallback,
            eligibility = eligibility,
            gpuAttempted = true,
            initialFallbackStage = fallbackStage,
            initialFallbackReason = gpuFailure.message,
            initialGpuFailure = gpuFailure,
            gpuSetupNanos = gpuSetupNanos,
            gpuProbeNanos = gpuProbeNanos,
            cpuSetupNanos = nanoTime() - cpuSetupStart,
            nanoTime = nanoTime,
        )
    }

    private fun requireRecoverableGpuFailure(
        stage: MdxLiteRtAutoFailureStage,
        failure: MdxLiteRtBackendException,
    ) {
        if (!failure.isRecoverable || failure.hasCleanupFailure()) {
            throw MdxLiteRtAutoInferenceException(stage, failure)
        }
    }
}

private class MdxLiteRtAutoInferenceSession(
    private val artifact: MdxModelArtifact,
    private val profile: MdxExecutionProfile,
    private val runtimeSettings: MdxRuntimeSettings,
    private val gpuRuntimeProfile: MdxLiteRtGpuRuntimeProfile,
    private val cpuFactory: MdxInferenceSessionFactory,
    initialSession: MdxInferenceSession,
    initialState: MdxLiteRtAutoSessionState,
    private val eligibility: MdxLiteRtGpuEligibilityDecision,
    private val gpuAttempted: Boolean,
    initialFallbackStage: MdxLiteRtAutoFailureStage?,
    initialFallbackReason: String?,
    initialGpuFailure: Throwable?,
    private val gpuSetupNanos: Long?,
    private val gpuProbeNanos: Long?,
    private var cpuSetupNanos: Long?,
    private val nanoTime: () -> Long,
) : MdxInferenceSession, MdxLiteRtAutoDiagnosticsProvider {
    private var activeSession: MdxInferenceSession? = initialSession
    private var state = initialState
    private var acceptedOutputBackend: MdxInferenceBackend? = null
    private var fallbackStage = initialFallbackStage
    private var fallbackReason = initialFallbackReason
    private var gpuFailure = initialGpuFailure
    private var gpuInferenceNanos: Long? = null
    private var cpuInferenceNanos: Long? = null

    override val diagnostics: MdxRuntimeDiagnostics
        get() = synchronized(this) {
            val snapshot = snapshotLocked()
            MdxRuntimeDiagnostics(
                runtimeName = "LiteRT 2.1.5 Auto",
                backend = snapshot.acceptedOutputBackend
                    ?: snapshot.activeBackend
                    ?: MdxInferenceBackend.LiteRtAuto,
                cpuThreads = activeSession
                    ?.diagnostics
                    ?.cpuThreads
                    ?.takeIf { snapshot.activeBackend == MdxInferenceBackend.LiteRtCpu },
                detail = buildString {
                    append("policy=Auto, profile=").append(snapshot.gpuProfileId)
                    append(", state=").append(snapshot.state.name)
                    append(", eligibility=").append(snapshot.eligibilityReason.name)
                    append(", eligibilityDetail=").append(snapshot.eligibilityDetail)
                    snapshot.fallbackStage?.let { append(", fallbackStage=").append(it.name) }
                    snapshot.fallbackReason?.let { append(", fallbackReason=").append(it) }
                },
                fallbackStage = snapshot.fallbackStage?.name,
                fallbackReason = snapshot.fallbackReason,
            )
        }

    override val autoDiagnostics: MdxLiteRtAutoDiagnostics
        get() = synchronized(this) { snapshotLocked() }

    @Synchronized
    override fun run(
        inputNchw: FloatArray,
        shouldCancel: () -> Boolean,
    ): FloatArray {
        val session = checkNotNull(activeSession) { "LiteRT Auto session is not active." }
        return when (state) {
            MdxLiteRtAutoSessionState.GpuActive -> runGpu(session, inputNchw, shouldCancel)
            MdxLiteRtAutoSessionState.CpuDirect,
            MdxLiteRtAutoSessionState.CpuFallback,
            -> runCpu(session, inputNchw, shouldCancel)

            MdxLiteRtAutoSessionState.Terminal -> error("LiteRT Auto session failed terminally.")
            MdxLiteRtAutoSessionState.Closed -> error("LiteRT Auto session is closed.")
        }
    }

    private fun runGpu(
        gpuSession: MdxInferenceSession,
        inputNchw: FloatArray,
        shouldCancel: () -> Boolean,
    ): FloatArray {
        val start = nanoTime()
        try {
            return gpuSession.run(inputNchw, shouldCancel).also {
                gpuInferenceNanos = nanoTime() - start
                acceptedOutputBackend = MdxInferenceBackend.LiteRtGpu
            }
        } catch (error: CancellationException) {
            gpuInferenceNanos = nanoTime() - start
            throw error
        } catch (error: OutOfMemoryError) {
            gpuInferenceNanos = nanoTime() - start
            terminateGpu(gpuSession, error)
            throw error
        } catch (error: MdxLiteRtBackendException) {
            gpuInferenceNanos = nanoTime() - start
            val stage = error.stage.toAutoFailureStage(isProbe = false)
            if (!error.isRecoverable || error.hasCleanupFailure()) {
                terminateGpu(gpuSession, error)
                throw MdxLiteRtAutoInferenceException(stage, error)
            }
            return fallbackAndRunCpu(
                gpuSession,
                inputNchw,
                shouldCancel,
                error,
                stage,
            )
        }
    }

    private fun fallbackAndRunCpu(
        gpuSession: MdxInferenceSession,
        inputNchw: FloatArray,
        shouldCancel: () -> Boolean,
        gpuFailure: MdxLiteRtBackendException,
        stage: MdxLiteRtAutoFailureStage,
    ): FloatArray {
        try {
            gpuSession.close()
        } catch (cleanupFailure: Throwable) {
            activeSession = null
            state = MdxLiteRtAutoSessionState.Terminal
            fallbackStage = MdxLiteRtAutoFailureStage.GpuCleanup
            fallbackReason = gpuFailure.message
            throw MdxLiteRtAutoInferenceException(
                MdxLiteRtAutoFailureStage.GpuCleanup,
                gpuFailure,
                cleanupFailure,
            )
        }
        activeSession = null
        if (shouldCancel()) {
            state = MdxLiteRtAutoSessionState.Terminal
            throw CancellationException("LiteRT Auto fallback canceled before CPU setup.")
        }
        val setupStart = nanoTime()
        val cpuSession = try {
            cpuFactory.create(artifact, profile, runtimeSettings)
        } catch (cpuFailure: Throwable) {
            state = MdxLiteRtAutoSessionState.Terminal
            fallbackStage = MdxLiteRtAutoFailureStage.CpuSetup
            fallbackReason = gpuFailure.message
            throw MdxLiteRtAutoInferenceException(
                MdxLiteRtAutoFailureStage.CpuSetup,
                gpuFailure,
                cpuFailure,
            )
        }
        cpuSetupNanos = nanoTime() - setupStart
        activeSession = cpuSession
        state = MdxLiteRtAutoSessionState.CpuFallback
        fallbackStage = stage
        fallbackReason = gpuFailure.message
        this.gpuFailure = gpuFailure
        return runCpu(cpuSession, inputNchw, shouldCancel)
    }

    private fun runCpu(
        cpuSession: MdxInferenceSession,
        inputNchw: FloatArray,
        shouldCancel: () -> Boolean,
    ): FloatArray {
        val start = nanoTime()
        try {
            return cpuSession.run(inputNchw, shouldCancel).also {
                cpuInferenceNanos = nanoTime() - start
                acceptedOutputBackend = MdxInferenceBackend.LiteRtCpu
            }
        } catch (error: CancellationException) {
            cpuInferenceNanos = nanoTime() - start
            throw error
        } catch (error: Throwable) {
            cpuInferenceNanos = nanoTime() - start
            if (state != MdxLiteRtAutoSessionState.CpuFallback) throw error
            state = MdxLiteRtAutoSessionState.Terminal
            fallbackStage = MdxLiteRtAutoFailureStage.CpuInvocation
            runCatching { cpuSession.close() }.exceptionOrNull()?.let(error::addSuppressed)
            activeSession = null
            val primaryFailure = gpuFailure ?: error
            throw MdxLiteRtAutoInferenceException(
                MdxLiteRtAutoFailureStage.CpuInvocation,
                primaryFailure,
                error.takeUnless { it === primaryFailure },
            )
        }
    }

    private fun terminateGpu(gpuSession: MdxInferenceSession, primaryFailure: Throwable) {
        state = MdxLiteRtAutoSessionState.Terminal
        activeSession = null
        try {
            gpuSession.close()
        } catch (cleanupFailure: Throwable) {
            primaryFailure.addSuppressed(cleanupFailure)
        }
    }

    @Synchronized
    override fun close() {
        if (state == MdxLiteRtAutoSessionState.Closed) return
        val session = activeSession
        activeSession = null
        state = MdxLiteRtAutoSessionState.Closed
        session?.close()
    }

    private fun snapshotLocked() = MdxLiteRtAutoDiagnostics(
        state = state,
        gpuProfileId = gpuRuntimeProfile.profileId,
        eligibilityReason = eligibility.reason,
        eligibilityDetail = eligibility.detail,
        gpuAttempted = gpuAttempted,
        activeBackend = activeSession?.diagnostics?.backend,
        acceptedOutputBackend = acceptedOutputBackend,
        fallbackStage = fallbackStage,
        fallbackReason = fallbackReason,
        gpuSetupNanos = gpuSetupNanos,
        gpuProbeNanos = gpuProbeNanos,
        gpuInferenceNanos = gpuInferenceNanos,
        cpuSetupNanos = cpuSetupNanos,
        cpuInferenceNanos = cpuInferenceNanos,
    )
}

private fun MdxLiteRtFailureStage.toAutoFailureStage(
    isProbe: Boolean,
): MdxLiteRtAutoFailureStage = when (this) {
    MdxLiteRtFailureStage.EnvironmentCreate,
    MdxLiteRtFailureStage.AcceleratorDiscovery,
    MdxLiteRtFailureStage.ModelCompile,
    -> MdxLiteRtAutoFailureStage.GpuSetup

    MdxLiteRtFailureStage.TensorMetadata,
    MdxLiteRtFailureStage.BufferAllocation,
    -> MdxLiteRtAutoFailureStage.GpuTensorSetup

    MdxLiteRtFailureStage.InputWrite -> if (isProbe) {
        MdxLiteRtAutoFailureStage.GpuProbeWrite
    } else {
        MdxLiteRtAutoFailureStage.GpuInputWrite
    }

    MdxLiteRtFailureStage.Invocation -> if (isProbe) {
        MdxLiteRtAutoFailureStage.GpuProbeInvocation
    } else {
        MdxLiteRtAutoFailureStage.GpuInvocation
    }

    MdxLiteRtFailureStage.OutputRead -> if (isProbe) {
        MdxLiteRtAutoFailureStage.GpuProbeRead
    } else {
        MdxLiteRtAutoFailureStage.GpuOutputRead
    }

    MdxLiteRtFailureStage.OutputValidation -> if (isProbe) {
        MdxLiteRtAutoFailureStage.GpuProbeValidation
    } else {
        MdxLiteRtAutoFailureStage.GpuOutputValidation
    }

    MdxLiteRtFailureStage.Cleanup -> MdxLiteRtAutoFailureStage.GpuCleanup
}

private fun MdxLiteRtBackendException.hasCleanupFailure(): Boolean =
    stage == MdxLiteRtFailureStage.Cleanup ||
        suppressed.any { suppressedFailure ->
            suppressedFailure is MdxLiteRtBackendException &&
                suppressedFailure.stage == MdxLiteRtFailureStage.Cleanup
        }

private fun closeTerminalSession(session: MdxInferenceSession, primaryFailure: Throwable) {
    try {
        session.close()
    } catch (cleanupFailure: Throwable) {
        primaryFailure.addSuppressed(cleanupFailure)
    }
}
