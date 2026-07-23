package com.mardous.booming.separation

import android.content.Context
import android.os.SystemClock
import com.mardous.booming.separation.model.MdxCompatibilityPolicy
import com.mardous.booming.separation.model.MdxExecutionProfile
import com.mardous.booming.separation.model.MdxInferenceBackend
import com.mardous.booming.separation.model.MdxInferenceSession
import com.mardous.booming.separation.model.MdxInferenceSessionFactory
import com.mardous.booming.separation.model.MdxInferenceSessionProvider
import com.mardous.booming.separation.model.MdxModelArtifact
import com.mardous.booming.separation.model.MdxRuntimeSettings
import com.mardous.booming.separation.model.MdxRuntimeDiagnostics
import com.mardous.booming.separation.model.SingleUseMdxInferenceSessionProvider
import com.mardous.booming.separation.model.litert.AndroidMdxLiteRtGpuEligibilityProvider
import com.mardous.booming.separation.model.litert.MdxLiteRtAutoFailureStage
import com.mardous.booming.separation.model.litert.MdxLiteRtAutoInferenceSessionFactory
import com.mardous.booming.separation.model.litert.MdxLiteRtBackendException
import com.mardous.booming.separation.model.litert.MdxLiteRtCpuInferenceSessionFactory
import com.mardous.booming.separation.model.litert.MdxLiteRtFailureStage
import com.mardous.booming.separation.model.litert.MdxLiteRtGpuInferenceSessionFactory
import com.mardous.booming.separation.model.litert.MdxLiteRtGpuProbeResult
import com.mardous.booming.separation.model.litert.MdxLiteRtGpuRuntimeProfile
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal enum class Phase7AutoFailpoint(
    val argumentValue: String,
    val expectedFallbackStage: MdxLiteRtAutoFailureStage?,
) {
    None("none", null),
    Setup("setup", MdxLiteRtAutoFailureStage.GpuSetup),
    Probe("probe", MdxLiteRtAutoFailureStage.GpuProbeValidation),
    InvocationAfterReady(
        "invocation-after-ready",
        MdxLiteRtAutoFailureStage.GpuInvocation,
    ),
    ;

    companion object {
        fun parse(value: String?): Phase7AutoFailpoint = values().singleOrNull {
            it.argumentValue == (value ?: None.argumentValue)
        } ?: error("Unsupported Phase 7 Auto failpoint: $value")
    }
}

internal class Phase7AutoFaultController(
    val failpoint: Phase7AutoFailpoint,
) {
    private val events = mutableListOf<String>()
    private val gpuCreateCount = AtomicInteger()
    private val gpuCloseCount = AtomicInteger()
    private val gpuInvocationCount = AtomicInteger()
    private val cpuCreateCount = AtomicInteger()
    private val cpuCloseCount = AtomicInteger()
    private val injectedAtElapsedMs = AtomicLong()

    fun createSessionProviderFactory(
        context: Context,
    ): () -> MdxInferenceSessionProvider = {
        val gpuProfile = MdxLiteRtGpuRuntimeProfile.AutomaticFp32V1
        val gpuFactory = Phase7TrackingInferenceSessionFactory(
            delegate = MdxLiteRtGpuInferenceSessionFactory(
                runtimeProfile = gpuProfile,
                compatibilityPolicy = MdxCompatibilityPolicy.AllowUntestedInternal,
            ),
            controller = this,
        )
        val cpuFactory = Phase7TrackingInferenceSessionFactory(
            delegate = MdxLiteRtCpuInferenceSessionFactory(
                compatibilityPolicy = MdxCompatibilityPolicy.KnownGoodOnly,
            ),
            controller = this,
        )
        val autoFactory = MdxLiteRtAutoInferenceSessionFactory(
            gpuRuntimeProfile = gpuProfile,
            gpuCompatibilityPolicy = MdxCompatibilityPolicy.AllowUntestedInternal,
            gpuEligibilityProvider = AndroidMdxLiteRtGpuEligibilityProvider(context),
            gpuProbe = { session, profile ->
                val output = session.run(FloatArray(profile.inputTensor.elementCount))
                when {
                    failpoint == Phase7AutoFailpoint.Probe -> {
                        injected("probe")
                        MdxLiteRtGpuProbeResult.rejected(
                            "Injected Phase 7 GPU probe rejection.",
                        )
                    }
                    output.size == profile.outputTensor.elementCount &&
                        output.all(Float::isFinite) -> {
                        MdxLiteRtGpuProbeResult.accepted("Finite FP32 output probe passed.")
                    }
                    else -> MdxLiteRtGpuProbeResult.rejected(
                        "GPU output shape or finite-value validation failed.",
                    )
                }
            },
            gpuFactory = gpuFactory,
            cpuFactory = cpuFactory,
        )
        SingleUseMdxInferenceSessionProvider(autoFactory)
    }

    fun snapshot(): Phase7AutoFaultSnapshot = synchronized(events) {
        Phase7AutoFaultSnapshot(
            failpoint = failpoint,
            gpuCreateCount = gpuCreateCount.get(),
            gpuCloseCount = gpuCloseCount.get(),
            gpuInvocationCount = gpuInvocationCount.get(),
            cpuCreateCount = cpuCreateCount.get(),
            cpuCloseCount = cpuCloseCount.get(),
            injectedAtElapsedMs = injectedAtElapsedMs.get(),
            events = events.toList(),
        )
    }

    internal fun created(backend: MdxInferenceBackend) {
        when (backend) {
            MdxInferenceBackend.LiteRtGpu -> gpuCreateCount.incrementAndGet()
            MdxInferenceBackend.LiteRtCpu -> cpuCreateCount.incrementAndGet()
            else -> error("Unexpected Phase 7 tracked backend: $backend")
        }
        event("${backend.name}-create")
    }

    internal fun closed(backend: MdxInferenceBackend) {
        when (backend) {
            MdxInferenceBackend.LiteRtGpu -> gpuCloseCount.incrementAndGet()
            MdxInferenceBackend.LiteRtCpu -> cpuCloseCount.incrementAndGet()
            else -> error("Unexpected Phase 7 tracked backend: $backend")
        }
        event("${backend.name}-close")
    }

    internal fun invoked(backend: MdxInferenceBackend): Int {
        val count = if (backend == MdxInferenceBackend.LiteRtGpu) {
            gpuInvocationCount.incrementAndGet()
        } else {
            0
        }
        event("${backend.name}-run-$count")
        return count
    }

    internal fun injected(stage: String) {
        injectedAtElapsedMs.compareAndSet(0L, SystemClock.elapsedRealtime())
        event("inject-$stage")
    }

    private fun event(value: String) {
        synchronized(events) { events += value }
    }
}

internal data class Phase7AutoFaultSnapshot(
    val failpoint: Phase7AutoFailpoint,
    val gpuCreateCount: Int,
    val gpuCloseCount: Int,
    val gpuInvocationCount: Int,
    val cpuCreateCount: Int,
    val cpuCloseCount: Int,
    val injectedAtElapsedMs: Long,
    val events: List<String>,
)

private class Phase7TrackingInferenceSessionFactory(
    private val delegate: MdxInferenceSessionFactory,
    private val controller: Phase7AutoFaultController,
) : MdxInferenceSessionFactory {
    override val factoryId: String = "phase7-fault-${delegate.factoryId}"
    override val backend: MdxInferenceBackend = delegate.backend

    override fun create(
        artifact: MdxModelArtifact,
        profile: MdxExecutionProfile,
        runtimeSettings: MdxRuntimeSettings,
    ): MdxInferenceSession {
        val session = Phase7TrackingInferenceSession(
            delegate = delegate.create(artifact, profile, runtimeSettings),
            controller = controller,
        )
        if (backend == MdxInferenceBackend.LiteRtGpu &&
            controller.failpoint == Phase7AutoFailpoint.Setup
        ) {
            session.close()
            controller.injected("setup")
            throw MdxLiteRtBackendException(
                stage = MdxLiteRtFailureStage.ModelCompile,
                isRecoverable = true,
                cause = IllegalStateException("Injected Phase 7 GPU setup failure."),
            )
        }
        return session
    }
}

private class Phase7TrackingInferenceSession(
    private val delegate: MdxInferenceSession,
    private val controller: Phase7AutoFaultController,
) : MdxInferenceSession {
    override val diagnostics: MdxRuntimeDiagnostics
        get() = delegate.diagnostics
    private val backend = delegate.diagnostics.backend
    private var closed = false

    init {
        controller.created(backend)
    }

    override fun run(
        inputNchw: FloatArray,
        shouldCancel: () -> Boolean,
    ): FloatArray {
        val output = delegate.run(inputNchw, shouldCancel)
        val invocationCount = controller.invoked(backend)
        if (backend == MdxInferenceBackend.LiteRtGpu &&
            controller.failpoint == Phase7AutoFailpoint.InvocationAfterReady &&
            invocationCount == INVOCATION_AFTER_READY_FAIL_COUNT
        ) {
            controller.injected("invocation-after-ready")
            throw MdxLiteRtBackendException(
                stage = MdxLiteRtFailureStage.Invocation,
                isRecoverable = true,
                cause = IllegalStateException(
                    "Injected Phase 7 GPU invocation failure after ready windows.",
                ),
            )
        }
        return output
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            delegate.close()
        } finally {
            controller.closed(backend)
        }
    }

    private companion object {
        // Probe is invocation 1; four complete GPU windows precede this failure.
        const val INVOCATION_AFTER_READY_FAIL_COUNT = 6
    }
}
