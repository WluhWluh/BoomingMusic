package com.mardous.booming.separation

import android.content.Context
import android.net.Uri
import com.mardous.booming.separation.model.MdxCompatibilityPolicy
import com.mardous.booming.separation.model.MdxInferenceSessionProvider
import com.mardous.booming.separation.model.MdxInferenceSessionFactory
import com.mardous.booming.separation.model.MdxRangeSeparator
import com.mardous.booming.separation.model.MdxSeparationExecution
import com.mardous.booming.separation.model.SingleUseMdxInferenceSessionProvider
import com.mardous.booming.separation.model.withMdxInferenceTiming
import com.mardous.booming.separation.model.litert.AndroidMdxLiteRtGpuEligibilityProvider
import com.mardous.booming.separation.model.litert.MdxLiteRtAutoInferenceSessionFactory
import com.mardous.booming.separation.model.litert.MdxLiteRtCpuInferenceSessionFactory
import com.mardous.booming.separation.model.litert.MdxLiteRtGpuInferenceSessionFactory
import com.mardous.booming.separation.model.litert.MdxLiteRtGpuProbeResult
import com.mardous.booming.separation.model.litert.MdxLiteRtGpuRuntimeProfile

internal class MdxSourceSeparationModelAwareRangeExecutor(
    context: Context,
    private val sessionProviderFactory: () -> MdxInferenceSessionProvider = {
        createAutoLiteRtSessionProvider(context.applicationContext)
    },
) : SourceSeparationModelAwareRangeExecutor {
    private val applicationContext = context.applicationContext

    override fun separate(
        request: SourceSeparationModelAwareExecutionRequest,
    ) = MdxRangeSeparator(
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
        sessionProvider = sessionProviderFactory(),
        shouldPause = request.shouldPause,
        shouldCancel = request.shouldCancel,
        requireWorkspaceAvailable = request.requireWorkspaceAvailable,
    )
}

internal fun createAutoLiteRtSessionProvider(
    context: Context,
    gpuProfile: MdxLiteRtGpuRuntimeProfile = MdxLiteRtGpuRuntimeProfile.AutomaticFp32V1,
): MdxInferenceSessionProvider {
    return SingleUseMdxInferenceSessionProvider(
        createAutoLiteRtSessionFactory(context, gpuProfile)
    )
}

internal fun createAutoLiteRtSessionFactory(
    context: Context,
    gpuProfile: MdxLiteRtGpuRuntimeProfile = MdxLiteRtGpuRuntimeProfile.AutomaticFp32V1,
): MdxInferenceSessionFactory {
    val factory = MdxLiteRtAutoInferenceSessionFactory(
        gpuRuntimeProfile = gpuProfile,
        gpuCompatibilityPolicy = MdxCompatibilityPolicy.AllowUntestedInternal,
        gpuEligibilityProvider = AndroidMdxLiteRtGpuEligibilityProvider(context),
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
            compatibilityPolicy = MdxCompatibilityPolicy.AllowUntestedInternal,
        ),
        cpuFactory = MdxLiteRtCpuInferenceSessionFactory(
            compatibilityPolicy = MdxCompatibilityPolicy.KnownGoodOnly,
        ),
    )
    return factory.withMdxInferenceTiming()
}
