package com.mardous.booming.separation

import android.content.Context
import android.net.Uri
import com.mardous.booming.separation.cache.SourceSeparationSegmentState
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunCompletion
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunPreparation
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunStemFile
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRuntimeRecord
import com.mardous.booming.separation.model.HtdemucsMdxTrackSource
import com.mardous.booming.separation.model.HtdemucsRangeProgress
import com.mardous.booming.separation.model.HtdemucsRangeResumeState
import com.mardous.booming.separation.model.HtdemucsRangeRunner
import com.mardous.booming.separation.model.HtdemucsTrackInferenceSession
import com.mardous.booming.separation.model.HtdemucsTrackSource
import com.mardous.booming.separation.model.MdxDspConfig
import com.mardous.booming.separation.model.MdxRangeProgress
import com.mardous.booming.separation.model.MdxRangeTimingAccumulator
import com.mardous.booming.separation.model.MdxSourceDecodeDiagnostics
import com.mardous.booming.separation.model.MdxSourceInput
import com.mardous.booming.separation.model.contract.SourceSeparationInstalledMultiStemModel
import com.mardous.booming.separation.model.litert.SourceSeparationInstalledMultiStemCpuSessionFactory
import com.mardous.booming.separation.runtime.SourceSeparationRuntimeBootstrap
import java.io.File

internal data class HtdemucsSourceSeparationRangeRequest(
    val sourceUri: String,
    val displayName: String,
    val installedModel: SourceSeparationInstalledMultiStemModel,
    val workDirectory: File,
    val segmentsDirectory: File,
    val expectedSourceAudioFingerprint: String,
    val windowDecodeEnabled: Boolean,
    val resumeState: HtdemucsRangeResumeState? = null,
    val onPrepared: (SourceSeparationCacheRunPreparation) -> Unit = {},
    val onSegmentStateChanged: (Int, SourceSeparationSegmentState) -> Unit = { _, _ -> },
    val onProgress: (MdxRangeProgress) -> Unit = {},
    val playbackPositionMsProvider: () -> Long? = { null },
    val playbackReadyWindowCountProvider: () -> Int = { 2 },
    val shouldPause: () -> Boolean = { false },
    val pauseReasonProvider: () -> SourceSeparationPauseReason = {
        SourceSeparationPauseReason.Standard
    },
    val shouldCancel: () -> Boolean = { false },
    val requireWorkspaceAvailable: () -> Unit = {},
)

internal data class HtdemucsSourceSeparationRangeResult(
    val completion: SourceSeparationCacheRunCompletion,
    val sourceDecodeDiagnostics: MdxSourceDecodeDiagnostics,
)

internal data class HtdemucsPreparedTrackSource(
    val source: HtdemucsTrackSource,
    val diagnostics: MdxSourceDecodeDiagnostics,
    val sourceAudioFingerprint: String,
)

internal fun interface HtdemucsProductSourceFactory {
    fun create(request: HtdemucsSourceSeparationRangeRequest): HtdemucsPreparedTrackSource
}

internal fun interface HtdemucsProductSessionFactory {
    fun create(installed: SourceSeparationInstalledMultiStemModel): HtdemucsTrackInferenceSession
}

internal fun interface HtdemucsSourceSeparationRangeExecutorContract {
    fun separate(request: HtdemucsSourceSeparationRangeRequest): HtdemucsSourceSeparationRangeResult
}

internal class HtdemucsSourceSeparationRangeExecutor(
    private val sourceFactory: HtdemucsProductSourceFactory,
    private val sessionFactory: HtdemucsProductSessionFactory,
) : HtdemucsSourceSeparationRangeExecutorContract {
    constructor(context: Context) : this(
        sourceFactory = AndroidHtdemucsProductSourceFactory(context.applicationContext),
        sessionFactory = AndroidHtdemucsProductSessionFactory(context.applicationContext),
    )

    override fun separate(
        request: HtdemucsSourceSeparationRangeRequest,
    ): HtdemucsSourceSeparationRangeResult {
        require(request.sourceUri.isNotBlank() && request.displayName.isNotBlank())
        require(request.expectedSourceAudioFingerprint.isNotBlank())
        val source = sourceFactory.create(request)
        require(source.sourceAudioFingerprint == request.expectedSourceAudioFingerprint) {
            "Decoded HTDemucs source fingerprint differs from the admitted cache identity."
        }
        val session = sessionFactory.create(request.installedModel)
        val result = session.use { activeSession ->
            HtdemucsRangeRunner(source.source, activeSession).run(
                outputDirectory = request.workDirectory,
                segmentDirectory = request.segmentsDirectory,
                resumeState = request.resumeState,
                onPrepared = { preparation ->
                    request.onPrepared(
                        SourceSeparationCacheRunPreparation(
                            stemFiles = preparation.stemFiles.map { stem ->
                                SourceSeparationCacheRunStemFile(stem.stemId, stem.file)
                            },
                            timingFile = null,
                            outputFrameCount = preparation.outputFrameCount,
                            outputSampleRate = preparation.outputSampleRate,
                            windowCount = preparation.windowCount,
                            sourceAudioFingerprint = source.sourceAudioFingerprint,
                            segmentPlan = preparation.segmentPlan,
                        ),
                    )
                },
                onSegmentStateChanged = request.onSegmentStateChanged,
                onProgress = { progress ->
                    request.onProgress(progress.toMdxProgress(source.diagnostics))
                },
                playbackPositionMsProvider = request.playbackPositionMsProvider,
                playbackReadyWindowCountProvider = request.playbackReadyWindowCountProvider,
                shouldPause = request.shouldPause,
                pauseReasonProvider = request.pauseReasonProvider,
                shouldCancel = request.shouldCancel,
                requireWorkspaceAvailable = request.requireWorkspaceAvailable,
            )
        }
        return HtdemucsSourceSeparationRangeResult(
            completion = SourceSeparationCacheRunCompletion(
                stemFiles = result.stemFiles.map { stem ->
                    SourceSeparationCacheRunStemFile(stem.stemId, stem.file)
                },
                timingFile = null,
                outputSampleRate = result.outputSampleRate,
                outputFrameCount = result.outputFrameCount,
                windowCount = result.windowCount,
                elapsedMs = result.elapsedMs,
                sourceAudioFingerprint = source.sourceAudioFingerprint,
                segmentPlan = result.segmentPlan,
                runtimeRecord = SourceSeparationCacheRuntimeRecord(
                    backend = "LiteRtCpu",
                    runtimeProfileId = HTDEMUCS_CPU_PROFILE_ID,
                    precision = "fp32",
                    elapsedMs = result.elapsedMs,
                    sourceDecodeMode = source.diagnostics.mode.name,
                    sourceDecodeProfile = source.diagnostics.profile,
                    sourceDecodeMimeType = source.diagnostics.mimeType,
                    sourceDecodeFallbackReason = source.diagnostics.fallbackReason,
                    sourceDecodeSampleRate = source.diagnostics.sampleRate,
                    sourceDecodeChannelCount = source.diagnostics.channelCount,
                    sourceDecodeSourceFrameCount = source.diagnostics.sourceFrameCount,
                    sourceDecodeOutputFrameCount = source.diagnostics.outputFrameCount,
                    sourceDecodeEncoderDelayFrames = source.diagnostics.encoderDelayFrames,
                    sourceDecodeEncoderPaddingFrames = source.diagnostics.encoderPaddingFrames,
                ),
            ),
            sourceDecodeDiagnostics = source.diagnostics,
        )
    }

    private fun HtdemucsRangeProgress.toMdxProgress(
        diagnostics: MdxSourceDecodeDiagnostics,
    ) = MdxRangeProgress(
        completedWindows = completedWindows,
        totalWindows = totalWindows,
        stage = stage,
        sourceDecodeDiagnostics = diagnostics,
        completedWindowElapsedMs = completedWindowElapsedMs,
        scheduler = scheduler,
        runtimeBackend = com.mardous.booming.separation.model.MdxInferenceBackend.LiteRtCpu,
    )

    private companion object {
        const val HTDEMUCS_CPU_PROFILE_ID = HtdemucsSourceSeparationEngine.HTDEMUCS_CPU_PROFILE_ID
    }
}

private class AndroidHtdemucsProductSourceFactory(
    private val context: Context,
) : HtdemucsProductSourceFactory {
    override fun create(
        request: HtdemucsSourceSeparationRangeRequest,
    ): HtdemucsPreparedTrackSource {
        val timing = MdxRangeTimingAccumulator()
        val input = MdxSourceInput.create(
            context = context,
            config = MdxDspConfig(),
            uri = Uri.parse(request.sourceUri),
            displayName = request.displayName,
            windowDecodeEnabled = request.windowDecodeEnabled,
            timing = timing,
            onProgress = request.onProgress,
            shouldCancel = request.shouldCancel,
        )
        val source = HtdemucsMdxTrackSource(input, timing)
        return HtdemucsPreparedTrackSource(
            source = source,
            diagnostics = source.diagnostics,
            sourceAudioFingerprint = source.sourceAudioFingerprint(request.shouldCancel),
        )
    }
}

private class AndroidHtdemucsProductSessionFactory(
    private val context: Context,
) : HtdemucsProductSessionFactory {
    override fun create(
        installed: SourceSeparationInstalledMultiStemModel,
    ): HtdemucsTrackInferenceSession {
        SourceSeparationRuntimeBootstrap.ensureLoaded(context)
        return SourceSeparationInstalledMultiStemCpuSessionFactory().create(installed)
    }
}
