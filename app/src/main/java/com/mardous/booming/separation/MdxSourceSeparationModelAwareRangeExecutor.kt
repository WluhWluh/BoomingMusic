package com.mardous.booming.separation

import android.content.Context
import android.net.Uri
import com.mardous.booming.separation.model.MdxCompatibilityPolicy
import com.mardous.booming.separation.model.MdxInferenceSessionProvider
import com.mardous.booming.separation.model.MdxInferenceSessionFactory
import com.mardous.booming.separation.model.MdxRangeSeparator
import com.mardous.booming.separation.model.MdxRangeSeparationResult
import com.mardous.booming.separation.model.MdxRuntimeDiagnostics
import com.mardous.booming.separation.model.MdxSeparationExecution
import com.mardous.booming.separation.model.SingleUseMdxInferenceSessionProvider
import com.mardous.booming.separation.model.withMdxInferenceTiming
import com.mardous.booming.separation.model.litert.AndroidMdxLiteRtGpuEligibilityProvider
import com.mardous.booming.separation.model.litert.MdxLiteRtAutoInferenceSessionFactory
import com.mardous.booming.separation.model.litert.MdxLiteRtCpuInferenceSessionFactory
import com.mardous.booming.separation.model.litert.MdxLiteRtGpuInferenceSessionFactory
import com.mardous.booming.separation.model.litert.MdxLiteRtGpuProbeResult
import com.mardous.booming.separation.model.litert.MdxLiteRtGpuRuntimeProfile
import com.mardous.booming.separation.process.SourceSeparationExecutionBackendPolicy
import com.mardous.booming.separation.runtime.SourceSeparationGpuRuntimeBootstrap
import com.mardous.booming.separation.runtime.SourceSeparationRuntimeBootstrap

internal class MdxSourceSeparationModelAwareRangeExecutor(
    context: Context,
    private val sessionProviderFactory:
        (SourceSeparationExecutionBackendPolicy) -> MdxInferenceSessionProvider = { policy ->
        when (policy) {
            SourceSeparationExecutionBackendPolicy.Auto ->
                createAutoLiteRtSessionProvider(context.applicationContext)
            SourceSeparationExecutionBackendPolicy.Cpu ->
                createCpuLiteRtSessionProvider(context.applicationContext)
        }
    },
) : SourceSeparationModelAwareRangeExecutor {
    private val applicationContext = context.applicationContext

    override fun separate(
        request: SourceSeparationModelAwareExecutionRequest,
    ): MdxRangeSeparationResult {
        var observedFallback = request.gpuFallbackLatch
        return MdxRangeSeparator(
            context = applicationContext,
            config = request.model.executionProfile.dspConfig,
            runtimeSettings = request.runtimeSettings,
        ).separate(
            uri = Uri.parse(request.sourceUri),
            outputDir = request.workspace.workDirectory,
            segmentOutputDir = request.workspace.segmentsDirectory,
            displayName = request.displayName,
            runtimeSettings = request.runtimeSettings,
            onProgress = request.onProgress,
            onPrepared = request.onPrepared,
            onSegmentStateChanged = request.onSegmentStateChanged,
            playbackPositionMsProvider = request.playbackPositionMsProvider,
            playbackReadyWindowCountProvider = request.playbackReadyWindowCountProvider,
            windowDecodeEnabled = request.windowDecodeEnabled,
            resumeState = request.workspace.resumeState,
            execution = MdxSeparationExecution(
                artifact = request.model.artifact,
                profile = request.model.executionProfile,
            ),
            expectedSourceAudioFingerprint = request.workspace.identity.source.audioFingerprint,
            sessionProvider = sessionProviderFactory(request.backendPolicy),
            onRuntimeDiagnostics = { diagnostics ->
                diagnostics.toSourceSeparationGpuFallbackLatch()?.let { latch ->
                    val previous = observedFallback
                    if (previous == null) {
                        observedFallback = latch
                        request.onGpuFallbackLatched(latch)
                    } else {
                        require(previous == latch) {
                            "Execution reported conflicting GPU fallback diagnostics."
                        }
                    }
                }
            },
            shouldPause = request.shouldPause,
            shouldCancel = request.shouldCancel,
            requireWorkspaceAvailable = request.requireWorkspaceAvailable,
        )
    }
}

internal fun MdxRuntimeDiagnostics.toSourceSeparationGpuFallbackLatch():
    SourceSeparationGpuFallbackLatch? = fallbackStage?.let { stage ->
    SourceSeparationGpuFallbackLatch(
        stage = stage,
        reason = fallbackReason?.takeIf(String::isNotBlank),
    )
}

internal fun createAutoLiteRtSessionProvider(
    context: Context,
    gpuProfile: MdxLiteRtGpuRuntimeProfile =
        MdxLiteRtGpuRuntimeProfile.BoundedOpenClFp32V1,
): MdxInferenceSessionProvider {
    return SingleUseMdxInferenceSessionProvider(
        createAutoLiteRtSessionFactory(context, gpuProfile)
    )
}

internal fun createCpuLiteRtSessionProvider(context: Context): MdxInferenceSessionProvider {
    SourceSeparationRuntimeBootstrap.ensureLoaded(context.applicationContext)
    return SingleUseMdxInferenceSessionProvider(
        MdxLiteRtCpuInferenceSessionFactory(
            compatibilityPolicy = MdxCompatibilityPolicy.AllowCandidates,
        ).withMdxInferenceTiming()
    )
}

internal fun createAutoLiteRtSessionFactory(
    context: Context,
    gpuProfile: MdxLiteRtGpuRuntimeProfile =
        MdxLiteRtGpuRuntimeProfile.BoundedOpenClFp32V1,
): MdxInferenceSessionFactory {
    val applicationContext = context.applicationContext
    SourceSeparationRuntimeBootstrap.ensureLoaded(applicationContext)
    SourceSeparationGpuRuntimeBootstrap.ensureLoaded(applicationContext)
    val candidateCompatibilityPolicy = MdxCompatibilityPolicy.AllowCandidates
    val factory = MdxLiteRtAutoInferenceSessionFactory(
        gpuRuntimeProfile = gpuProfile,
        gpuCompatibilityPolicy = candidateCompatibilityPolicy,
        cpuCompatibilityPolicy = candidateCompatibilityPolicy,
        gpuEligibilityProvider = AndroidMdxLiteRtGpuEligibilityProvider(applicationContext),
        gpuProbe = { session, profile ->
            val output = session.run(FloatArray(profile.inputTensor.elementCount))
            if (output.size == profile.outputTensor.elementCount && output.all(Float::isFinite)) {
                MdxLiteRtGpuProbeResult.accepted("Finite FP32 output probe passed.")
            } else {
                MdxLiteRtGpuProbeResult.rejected(
                    "GPU output shape or finite-value validation failed.",
                )
            }
        },
        gpuFactory = MdxLiteRtGpuInferenceSessionFactory(
            runtimeProfile = gpuProfile,
            compatibilityPolicy = candidateCompatibilityPolicy,
        ),
        cpuFactory = MdxLiteRtCpuInferenceSessionFactory(
            compatibilityPolicy = candidateCompatibilityPolicy,
        ),
    )
    return factory.withMdxInferenceTiming()
}
