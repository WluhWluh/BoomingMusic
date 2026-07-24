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
import com.mardous.booming.separation.cache.v2.resolveExactCacheModel
import com.mardous.booming.separation.model.AndroidMdxRuntimePlatformProvider
import com.mardous.booming.separation.model.MdxRangeResumeState
import com.mardous.booming.separation.model.MdxRuntimeSettings
import com.mardous.booming.separation.model.MdxX86ProcessValidationOverride
import com.mardous.booming.separation.model.preset.SourceSeparationPresetRepository
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal class SourceSeparationRemoteExecutionEnvironment(
    context: Context,
    private val presetRepository: SourceSeparationPresetRepository,
    private val sessionController: SourceSeparationProcessSessionController =
        createRemoteSessionController(context.applicationContext),
    val rangeExecutor: SourceSeparationModelAwareRangeExecutor =
        MdxSourceSeparationModelAwareRangeExecutor(context.applicationContext) {
            sessionController
        },
) : AutoCloseable {
    private val applicationContext = context.applicationContext
    private val cacheStore = SourceSeparationCacheStore(
        AndroidSourceSeparationCacheRootProvider(applicationContext).resolveRoot(),
    )
    private val modelRoot = File(
        applicationContext.filesDir,
        SourceSeparationPresetRepository.MODEL_ROOT_DIRECTORY,
    ).canonicalFile

    @Volatile
    private var validationOverrideDiagnostics:
        SourceSeparationProcessValidationOverrideDiagnostics? = null

    fun sessionDiagnostics(): SourceSeparationProcessSessionDiagnostics =
        sessionController.diagnostics()

    fun validationOverrideDiagnostics():
        SourceSeparationProcessValidationOverrideDiagnostics? = validationOverrideDiagnostics

    fun beginExecution(runId: String) = sessionController.beginExecution(runId)

    fun finishExecution(runId: String, failure: Throwable?) =
        sessionController.finishExecution(runId, failure)

    fun markRecycling(reason: String, token: String) =
        sessionController.markRecycling(reason, token)

    override fun close() = sessionController.close()

    fun prepare(
        descriptor: SourceSeparationExecutionDescriptor,
        control: SourceSeparationRemoteExecutionControl,
    ): SourceSeparationModelAwareExecutionRequest {
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
        val entryDirectory = try {
            cacheStore.entryDirectory(descriptor.cacheKey).canonicalFile
        } catch (error: Throwable) {
            throw SourceSeparationRemoteCacheUnavailableException(
                "The exact execution cache entry cannot be resolved.",
                error,
            )
        }
        val canonicalEntriesRoot = entryDirectory.parentFile?.canonicalFile
        if (canonicalEntriesRoot != cacheStore.entriesDirectory().canonicalFile ||
            entryDirectory.name != descriptor.cacheKey ||
            !entryDirectory.isDirectory
        ) {
            throw SourceSeparationRemoteCacheUnavailableException(
                "The exact execution cache entry is unavailable or noncanonical.",
            )
        }
        val workDirectory = resolveExecutionDirectory(entryDirectory, WORK_DIRECTORY)
        val segmentsDirectory = resolveExecutionDirectory(entryDirectory, SEGMENTS_DIRECTORY)
        val resumeState = descriptor.resume?.let { resume ->
            MdxRangeResumeState(
                vocalsFile = cacheStore.resolveRelativePath(
                    entryDirectory,
                    resume.vocalsPath,
                ),
                instrumentalFile = cacheStore.resolveRelativePath(
                    entryDirectory,
                    resume.instrumentalPath,
                ),
                timingFile = resume.timingPath?.let { path ->
                    cacheStore.resolveRelativePath(entryDirectory, path)
                },
                segmentPlan = resume.segmentPlan,
            )
        }
        return SourceSeparationModelAwareExecutionRequest(
            sourceUri = descriptor.source.sourceUri,
            displayName = descriptor.source.displayName,
            model = model,
            workspace = SourceSeparationModelAwareExecutionWorkspace(
                identity = descriptor.cacheIdentity,
                contract = descriptor.contract,
                entryDirectory = entryDirectory,
                workDirectory = workDirectory,
                segmentsDirectory = segmentsDirectory,
                resumeState = resumeState,
            ),
            runtimeSettings = MdxRuntimeSettings(
                cpuThreads = descriptor.runtime.cpuThreads,
                useXnnpack = descriptor.runtime.useXnnpack,
            ),
            onProgress = {},
            onPrepared = {},
            onSegmentStateChanged = { _, _ -> },
            playbackPositionMsProvider = control::playbackPositionMs,
            playbackReadyWindowCountProvider = control::playbackReadyWindowCount,
            windowDecodeEnabled = descriptor.runtime.windowDecodeEnabled,
            shouldPause = control::shouldPause,
            shouldCancel = control::shouldCancel,
        )
    }

    private fun resolveExecutionDirectory(
        entryDirectory: File,
        relativePath: String,
    ): File {
        val directory = try {
            cacheStore.resolveRelativePath(entryDirectory, relativePath).canonicalFile
        } catch (error: Throwable) {
            throw SourceSeparationRemoteCacheUnavailableException(
                "The execution cache workspace cannot be resolved.",
                error,
            )
        }
        if (directory.parentFile != entryDirectory || !directory.isDirectory) {
            throw SourceSeparationRemoteCacheUnavailableException(
                "The execution cache workspace is unavailable or noncanonical.",
            )
        }
        return directory
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

    private companion object {
        const val WORK_DIRECTORY = "work"
        const val SEGMENTS_DIRECTORY = "segments"
    }
}

private fun createRemoteSessionController(
    context: Context,
): SourceSeparationProcessSessionController {
    val persistentX86Validation = MdxX86ProcessValidationOverride.buildEnabled &&
        runCatching { AndroidMdxRuntimePlatformProvider.current().runtimeAbi }
            .getOrNull() == com.mardous.booming.separation.model.MdxRuntimeAbi.X86
    return SourceSeparationProcessSessionController(
        factory = createAutoLiteRtSessionFactory(context),
        ownership = if (persistentX86Validation) {
            SourceSeparationProcessSessionOwnership.ResidentUntilProcessExit
        } else {
            SourceSeparationProcessSessionOwnership.SingleUse
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
        cancelRequested.set(true)
    }

    fun playbackPositionMs(): Long? =
        playbackPositionMs.get().takeUnless { it == NO_POSITION }

    fun playbackReadyWindowCount(): Int = playbackReadyWindowCount.get()

    fun shouldPause(): Boolean = pauseRequested.get()

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
