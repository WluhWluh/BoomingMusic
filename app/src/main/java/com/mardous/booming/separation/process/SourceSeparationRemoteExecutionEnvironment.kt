package com.mardous.booming.separation.process

import android.content.Context
import android.net.Uri
import com.mardous.booming.separation.MdxSourceSeparationModelAwareRangeExecutor
import com.mardous.booming.separation.createAutoLiteRtSessionFactory
import com.mardous.booming.separation.SourceSeparationModelAwareExecutionRequest
import com.mardous.booming.separation.SourceSeparationModelAwareExecutionWorkspace
import com.mardous.booming.separation.SourceSeparationModelAwareRangeExecutor
import com.mardous.booming.separation.cache.v2.AndroidSourceSeparationCacheRootProvider
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheStore
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheFaultInjection
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunCoordinator
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunRequest
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunStart
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwareCacheRepository
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwareCacheRun
import com.mardous.booming.separation.cache.v2.resolveExactCacheModel
import com.mardous.booming.separation.model.AndroidMdxRuntimePlatformProvider
import com.mardous.booming.separation.model.MdxCompatibilityPolicy
import com.mardous.booming.separation.model.MdxRuntimeSettings
import com.mardous.booming.separation.model.MdxX86ProcessValidationOverride
import com.mardous.booming.separation.model.withMdxInferenceTiming
import com.mardous.booming.separation.model.litert.MdxLiteRtCpuInferenceSessionFactory
import com.mardous.booming.separation.model.litert.MdxLiteRtRemoteFaultInjection
import com.mardous.booming.separation.model.preset.SourceSeparationPresetRepository
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.CancellationException

internal class SourceSeparationRemoteExecutionEnvironment(
    context: Context,
    private val presetRepository: SourceSeparationPresetRepository,
    private val sessionControllers: SourceSeparationRemoteSessionControllerProvider =
        createRemoteSessionControllerProvider(context.applicationContext),
    val rangeExecutor: SourceSeparationModelAwareRangeExecutor =
        MdxSourceSeparationModelAwareRangeExecutor(context.applicationContext) { _ ->
            sessionControllers.requireCurrent()
        },
) : AutoCloseable {
    private val applicationContext = context.applicationContext
    private val cacheStore = SourceSeparationCacheStore(
        AndroidSourceSeparationCacheRootProvider(applicationContext).resolveRoot(),
    )
    private val cacheRepository = SourceSeparationModelAwareCacheRepository(cacheStore)
    private val runCoordinator = SourceSeparationCacheRunCoordinator(
        store = cacheStore,
        repository = cacheRepository,
    )
    private val modelRoot = File(
        applicationContext.filesDir,
        SourceSeparationPresetRepository.MODEL_ROOT_DIRECTORY,
    ).canonicalFile

    init {
        SourceSeparationCacheFaultInjection.initialize(cacheStore.root().directory)
    }

    @Volatile
    private var validationOverrideDiagnostics:
        SourceSeparationProcessValidationOverrideDiagnostics? = null

    fun sessionDiagnostics(): SourceSeparationProcessSessionDiagnostics =
        sessionControllers.currentOrNull()?.diagnostics()?.copy(
            backendPolicy = sessionControllers.selectedBackendPolicy(),
        ) ?: SourceSeparationProcessSessionDiagnostics.empty()

    fun validationOverrideDiagnostics():
        SourceSeparationProcessValidationOverrideDiagnostics? = validationOverrideDiagnostics

    fun beginExecution(runId: String) =
        sessionControllers.requireCurrent().beginExecution(runId)

    fun finishExecution(runId: String, failure: Throwable?) =
        sessionControllers.requireCurrent().finishExecution(runId, failure)

    fun markRecycling(reason: String, token: String) {
        val controller = sessionControllers.currentOrNull()
            ?: sessionControllers.select(SourceSeparationExecutionBackendPolicy.Auto)
        controller.markRecycling(reason, token)
    }

    override fun close() = sessionControllers.close()

    fun prepare(
        descriptor: SourceSeparationExecutionDescriptor,
        control: SourceSeparationRemoteExecutionControl,
    ): SourceSeparationRemoteAdmittedExecution {
        descriptor.requireConsistentIdentity()
        validateSourceAccess(descriptor.source.sourceUri)
        val resolvedModel = presetRepository.resolveExactCacheModel(descriptor.contract)
        val validationOverride = runCatching {
            MdxX86ProcessValidationOverride.applyTo(
                model = resolvedModel,
                platform = AndroidMdxRuntimePlatformProvider.current(),
            )
        }.getOrNull()
        validationOverrideDiagnostics = validationOverride?.let { override ->
            SourceSeparationProcessValidationOverrideDiagnostics(
                modelId = resolvedModel.contract.modelId,
                artifactSha256 = resolvedModel.artifact.sha256.lowercase(java.util.Locale.US),
                contractId = resolvedModel.contract.contractId,
                originalStatus = override.originalRecord.status.name,
                originalReason = override.originalDecision.reason,
                originalEvidence = override.originalRecord.evidence,
                effectiveStatus = override.effectiveRecord.status.name,
                effectiveReason = override.effectiveDecision.reason,
            )
        }
        val model = validationOverride?.model ?: resolvedModel
        val canonicalModel = model.artifact.file.canonicalFile
        require(canonicalModel.toPath().startsWith(modelRoot.toPath()) &&
            canonicalModel.parentFile?.name.equals(
                descriptor.model.artifactSha256,
                ignoreCase = true,
            )
        ) {
            "The exact execution model is outside the canonical model root."
        }
        sessionControllers.select(descriptor.runtime.backendPolicy)
        val run = when (val start = runCoordinator.begin(
            SourceSeparationCacheRunRequest(
                identity = descriptor.cacheIdentity,
                contract = descriptor.contract,
                song = descriptor.song,
                sourceDiagnostics = descriptor.source.diagnostics,
                runId = descriptor.runId,
                processGeneration = descriptor.processGeneration,
                ownerPid = android.os.Process.myPid(),
                runClass = descriptor.runtime.runClass,
                backgroundPolicy = descriptor.runtime.backgroundPolicy,
                tryGpu = descriptor.runtime.tryGpu,
                gpuRuntimeIdentity = descriptor.runtime.gpuRuntimeIdentity,
                gpuFallbackLatch = descriptor.runtime.gpuFallbackLatch,
            )
        )) {
            SourceSeparationCacheRunStart.Busy ->
                throw SourceSeparationRemoteCacheBusyException(descriptor.cacheKey)
            is SourceSeparationCacheRunStart.AlreadyCompleted ->
                throw SourceSeparationRemoteCacheAlreadyCompletedException(descriptor.cacheKey)
            is SourceSeparationCacheRunStart.Ready -> start.run
        }
        val executionRequest = SourceSeparationModelAwareExecutionRequest(
            sourceUri = descriptor.source.sourceUri,
            displayName = descriptor.source.displayName,
            model = model,
            workspace = SourceSeparationModelAwareExecutionWorkspace(
                identity = descriptor.cacheIdentity,
                contract = descriptor.contract,
                entryDirectory = run.entryDirectory,
                workDirectory = run.workDirectory,
                segmentsDirectory = run.segmentsDirectory,
                resumeState = run.resumeState,
            ),
            runtimeSettings = MdxRuntimeSettings(
                cpuThreads = descriptor.runtime.cpuThreads,
                useXnnpack = descriptor.runtime.useXnnpack,
            ),
            backendPolicy = descriptor.runtime.backendPolicy,
            runClass = descriptor.runtime.runClass,
            backgroundPolicy = descriptor.runtime.backgroundPolicy,
            tryGpu = descriptor.runtime.tryGpu,
            gpuRuntimeIdentity = descriptor.runtime.gpuRuntimeIdentity,
            gpuFallbackLatch = descriptor.runtime.gpuFallbackLatch,
            onProgress = {},
            onPrepared = {},
            onSegmentStateChanged = { _, _ -> },
            onGpuFallbackLatched = {},
            playbackPositionMsProvider = control::playbackPositionMs,
            playbackReadyWindowCountProvider = control::playbackReadyWindowCount,
            windowDecodeEnabled = descriptor.runtime.windowDecodeEnabled,
            shouldPause = control::shouldPause,
            shouldCancel = control::shouldCancel,
            requireWorkspaceAvailable = run::requireOpen,
        )
        return SourceSeparationRemoteAdmittedExecution(
            run = run,
            coordinator = runCoordinator,
            executionRequest = executionRequest,
        )
    }

    private fun validateSourceAccess(sourceUri: String) {
        try {
            val uri = Uri.parse(sourceUri)
            when (uri.scheme?.lowercase()) {
                "content" -> {
                    val descriptor = applicationContext.contentResolver
                        .openAssetFileDescriptor(uri, "r")
                    requireNotNull(descriptor) { "The execution source URI cannot be opened." }
                        .use { value ->
                            require(value.fileDescriptor.valid()) {
                                "The execution source URI returned an invalid file descriptor."
                            }
                        }
                }

                "file" -> {
                    val path = requireNotNull(uri.path) {
                        "The execution file URI has no path."
                    }
                    require(File(path).canonicalFile.isFile) {
                        "The execution file URI is unavailable."
                    }
                }

                else -> throw IllegalArgumentException(
                    "The execution source URI scheme is unsupported.",
                )
            }
        } catch (error: Throwable) {
            throw SourceSeparationRemoteSourceUnavailableException(
                "The exact execution source is unavailable.",
                error,
            )
        }
    }

}

internal class SourceSeparationRemoteAdmittedExecution(
    private val run: SourceSeparationModelAwareCacheRun,
    private val coordinator: SourceSeparationCacheRunCoordinator,
    val executionRequest: SourceSeparationModelAwareExecutionRequest,
) : AutoCloseable {
    private var terminal = false

    @Synchronized
    fun persist(event: SourceSeparationExecutionHostEvent) {
        check(!terminal) { "Remote cache run already reached a terminal transition." }
        when (val payload = event.payload) {
            is SourceSeparationExecutionHostEventPayload.Accepted -> Unit
            is SourceSeparationExecutionHostEventPayload.Progress -> Unit
            is SourceSeparationExecutionHostEventPayload.Prepared ->
                coordinator.updatePreparation(
                    run,
                    payload.preparation.toMdxRangePreparation(run.entryDirectory),
                )
            is SourceSeparationExecutionHostEventPayload.SegmentStateChanged ->
                coordinator.updateSegmentState(run, payload.segmentIndex, payload.state)
            is SourceSeparationExecutionHostEventPayload.GpuFallbackLatched ->
                coordinator.latchGpuFallback(run, payload.latch)
            is SourceSeparationExecutionHostEventPayload.Completed -> {
                coordinator.complete(
                    run,
                    payload.completion.toMdxRangeSeparationResult(executionRequest),
                )
                terminal = true
            }
            is SourceSeparationExecutionHostEventPayload.Paused -> {
                coordinator.pause(run)
                terminal = true
            }
            is SourceSeparationExecutionHostEventPayload.Canceled -> {
                coordinator.cancel(
                    run,
                    CancellationException(payload.reason ?: "Remote run canceled."),
                )
                terminal = true
            }
            is SourceSeparationExecutionHostEventPayload.Failed -> {
                coordinator.fail(
                    run,
                    SourceSeparationRemoteExecutionFailure(
                        payload.errorType,
                        payload.message,
                    ),
                )
                terminal = true
            }
        }
    }

    @Synchronized
    override fun close() {
        if (!terminal) run.close()
        terminal = true
    }
}

internal class SourceSeparationRemoteExecutionFailure(
    val remoteType: String,
    message: String?,
) : IllegalStateException(message ?: remoteType)

internal class SourceSeparationRemoteCacheBusyException(
    val cacheKey: String,
) : IllegalStateException("The exact remote cache entry is busy.")

internal class SourceSeparationRemoteCacheAlreadyCompletedException(
    val cacheKey: String,
) : IllegalStateException("The exact remote cache entry is already completed.")

private fun createRemoteSessionControllerProvider(
    context: Context,
): SourceSeparationRemoteSessionControllerProvider {
    val runtimeAbi = runCatching {
        AndroidMdxRuntimePlatformProvider.current().runtimeAbi
    }.getOrNull()
    val ownership = resolveRemoteSessionOwnership(
        runtimeAbi = runtimeAbi,
        x86ValidationEnabled = MdxX86ProcessValidationOverride.buildEnabled,
        arm32ResidentValidationEnabled =
            SourceSeparationArm32ResidentValidation.buildEnabled,
    )
    return SourceSeparationRemoteSessionControllerProvider(
        autoControllerFactory = {
            SourceSeparationProcessSessionController(
                factory = (MdxLiteRtRemoteFaultInjection.takeFactoryIfArmed(context)
                    ?: createAutoLiteRtSessionFactory(context)).withMdxInferenceTiming(),
                ownership = ownership,
                runtimeAbi = runtimeAbi,
            )
        },
        cpuControllerFactory = {
            SourceSeparationProcessSessionController(
                factory = MdxLiteRtCpuInferenceSessionFactory(
                    compatibilityPolicy = MdxCompatibilityPolicy.KnownGoodOnly,
                ).withMdxInferenceTiming(),
                ownership = ownership,
                runtimeAbi = runtimeAbi,
            )
        },
    )
}

internal class SourceSeparationRemoteSourceUnavailableException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal class SourceSeparationRemoteCacheUnavailableException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal class SourceSeparationRemoteExecutionControl(
    initialPlaybackPositionMs: Long?,
    initialPlaybackReadyWindowCount: Int,
) {
    private val playbackPositionMs = AtomicLong(initialPlaybackPositionMs ?: NO_POSITION)
    private val playbackReadyWindowCount = AtomicInteger(
        initialPlaybackReadyWindowCount.coerceAtLeast(1),
    )
    private val pauseRequested = AtomicBoolean(false)
    private val cancelRequested = AtomicBoolean(false)

    fun update(
        hasPlaybackPositionUpdate: Boolean,
        playbackPositionMs: Long?,
        playbackReadyWindowCount: Int?,
    ) {
        if (hasPlaybackPositionUpdate) {
            this.playbackPositionMs.set(playbackPositionMs ?: NO_POSITION)
        }
        if (playbackReadyWindowCount != null) {
            this.playbackReadyWindowCount.set(playbackReadyWindowCount.coerceAtLeast(1))
        }
    }

    fun requestPause() {
        pauseRequested.set(true)
    }

    fun requestCancel() {
        pauseRequested.set(false)
        cancelRequested.set(true)
    }

    fun playbackPositionMs(): Long? =
        playbackPositionMs.get().takeUnless { it == NO_POSITION }

    fun playbackReadyWindowCount(): Int = playbackReadyWindowCount.get()

    fun shouldPause(): Boolean = !cancelRequested.get() && pauseRequested.get()

    fun shouldCancel(): Boolean = cancelRequested.get()

    private companion object {
        const val NO_POSITION = -1L
    }
}

private fun SourceSeparationExecutionDescriptor.requireConsistentIdentity() {
    require(contract.identity(
        source = cacheIdentity.source,
        renderProfileId = cacheIdentity.renderProfileId,
    ) == cacheIdentity) {
        "The remote execution cache identity does not match its contract."
    }
    require(model.executionProfileId == runtime.executionProfileId &&
        model.executionSessionIdentity == runtime.executionSessionIdentity
    ) {
        "The remote execution profile identity is inconsistent."
    }
}
