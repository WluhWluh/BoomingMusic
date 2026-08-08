package com.mardous.booming.separation

import com.mardous.booming.data.model.Song
import com.mardous.booming.separation.cache.v2.SourceSeparationActiveCacheModelResolution
import com.mardous.booming.separation.cache.v2.SourceSeparationActiveCacheModelUnavailableReason
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheFlacPromoter
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheFlacPromotionResult
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheIdentity
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheManifest
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheMutationResult
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunCoordinator
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheSourcePreflight
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwareCacheEntry
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwareCachePlayback
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwareCachePruneResult
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwareCacheRepository
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwareCacheStatus
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwarePlayableStatus
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwareReadyHorizonStatus
import com.mardous.booming.separation.cache.v2.SourceSeparationResolvedCacheModel
import com.mardous.booming.separation.model.AndroidMdxRuntimePlatformProvider
import com.mardous.booming.separation.model.MdxCompatibilityPolicy
import com.mardous.booming.separation.model.MdxInferenceBackend
import com.mardous.booming.separation.model.MdxLiteRtCompatibilityResolver
import com.mardous.booming.separation.model.MdxRangeProgress
import com.mardous.booming.separation.model.MdxRuntimeSettings
import com.mardous.booming.separation.model.MdxX86ProcessValidationOverride
import com.mardous.booming.separation.model.preset.SourceSeparationActiveModelReference
import com.mardous.booming.separation.model.preset.SourceSeparationPresetBindingKind
import com.mardous.booming.separation.process.SourceSeparationExecutionBackendPolicy
import java.util.concurrent.CancellationException

interface SourceSeparationRuntimeFacade {
    fun activeModelResolution(): SourceSeparationActiveCacheModelResolution

    fun resolve(
        song: Song,
        shouldCancel: () -> Boolean = { false },
    ): SourceSeparationRuntimeSongResolution

    fun resolveForPlayback(
        song: Song,
        shouldCancel: () -> Boolean = { false },
    ): SourceSeparationRuntimeSongResolution = resolve(song, shouldCancel)

    fun cacheStatus(song: SourceSeparationRuntimeSong): SourceSeparationModelAwareCacheStatus

    fun playableStatus(
        song: SourceSeparationRuntimeSong,
        playbackPositionMs: Long,
        readyWindowCount: Int,
    ): SourceSeparationModelAwarePlayableStatus

    fun readyHorizon(
        song: SourceSeparationRuntimeSong,
        playbackPositionMs: Long,
    ): SourceSeparationModelAwareReadyHorizonStatus

    fun readBlend(song: SourceSeparationRuntimeSong): Float?

    fun writeBlend(song: SourceSeparationRuntimeSong, blend: Float): Boolean

    fun readBlend(identity: SourceSeparationCacheIdentity): Float?

    fun writeBlend(identity: SourceSeparationCacheIdentity, blend: Float): Boolean

    fun separate(
        song: SourceSeparationRuntimeSong,
        runtimeSettings: MdxRuntimeSettings = MdxRuntimeSettings(),
        tryGpu: Boolean = true,
        runClass: SourceSeparationExecutionRunClass =
            SourceSeparationExecutionRunClass.PlaybackDemandWindow,
        onProgress: (MdxRangeProgress) -> Unit = {},
        onPrepared: (SourceSeparationCacheManifest) -> Unit = {},
        playbackPositionMsProvider: () -> Long? = { null },
        playbackReadyWindowCountProvider: () -> Int = { DEFAULT_READY_WINDOW_COUNT },
        windowDecodeEnabled: Boolean = true,
        shouldPause: () -> Boolean = { false },
        pauseReasonProvider: () -> SourceSeparationPauseReason = {
            SourceSeparationPauseReason.Standard
        },
        shouldCancel: () -> Boolean = { false },
    ): SourceSeparationModelAwareEngineResult

    fun promote(
        cacheKey: String,
        shouldCancel: () -> Boolean = { false },
    ): SourceSeparationCacheFlacPromotionResult

    fun cleanCompletedTemporaryFiles(cacheKey: String): Boolean

    fun cleanPendingCompletedTemporaryFiles(): List<String>

    fun entries(): List<SourceSeparationModelAwareCacheEntry>

    fun delete(cacheKey: String): SourceSeparationCacheMutationResult

    fun prune(
        partialLimit: Int,
        completedLimit: Int,
        protectedCacheKeys: Set<String> = emptySet(),
    ): SourceSeparationModelAwareCachePruneResult

    fun openCompletedCache(cacheKey: String): SourceSeparationModelAwareCachePlayback?

    private companion object {
        const val DEFAULT_READY_WINDOW_COUNT = 2
    }
}

class DefaultSourceSeparationRuntimeFacade internal constructor(
    private val activeModelResolver: () -> SourceSeparationActiveCacheModelResolution,
    private val multiStemPlaybackResolver: SourceSeparationMultiStemPlaybackResolver? = null,
    private val compatibilityResolver: SourceSeparationRuntimeCompatibilityResolver,
    private val preflightResolver: SourceSeparationModelAwarePreflightResolver,
    private val inputFactory: SourceSeparationRuntimeSongInputFactory =
        SourceSeparationRuntimeSongInputFactory(SourceSeparationModelAwareSongInput::from),
    private val engine: SourceSeparationModelAwareEngine,
    private val manualFullSongEngineFactory: (() -> SourceSeparationModelAwareEngine)? = null,
    private val cacheRepository: SourceSeparationModelAwareCacheRepository,
    private val runCoordinator: SourceSeparationCacheRunCoordinator,
    private val flacPromoter: SourceSeparationCacheFlacPromoter,
) : SourceSeparationRuntimeFacade {
    private val sourcePreflightMemo = SourceSeparationSourcePreflightMemo()

    override fun activeModelResolution(): SourceSeparationActiveCacheModelResolution =
        activeModelResolver()

    override fun resolve(
        song: Song,
        shouldCancel: () -> Boolean,
    ): SourceSeparationRuntimeSongResolution {
        if (song == Song.emptySong) {
            return SourceSeparationRuntimeSongResolution.Unavailable(
                SourceSeparationRuntimeUnavailableReason.NoSong,
            )
        }
        val model = when (val active = activeModelResolver()) {
            is SourceSeparationActiveCacheModelResolution.Ready -> active.model
            is SourceSeparationActiveCacheModelResolution.Unavailable -> {
                return SourceSeparationRuntimeSongResolution.Unavailable(
                    reason = active.reason.toRuntimeReason(),
                    reference = active.reference,
                )
            }
        }
        compatibilityResolver.unsupportedReason(model)?.let { reason ->
            return SourceSeparationRuntimeSongResolution.Unavailable(
                reason = SourceSeparationRuntimeUnavailableReason.RuntimeUnsupported,
                reference = model.activeReference(),
                detail = reason,
            )
        }
        val input = inputFactory.create(song)
        val preflight = try {
            if (shouldCancel()) {
                throw CancellationException("Source audio identity resolution canceled.")
            }
            val memoKey = SourceSeparationSourcePreflightMemoKey(
                sourceUri = input.sourceUri,
                filePath = song.data,
                fileSize = song.size,
                rawDateModified = song.rawDateModified,
                durationMs = song.duration,
            )
            sourcePreflightMemo.get(memoKey)?.copy(elapsedMs = 0L)
                ?: preflightResolver.resolve(input.sourceUri, shouldCancel).also { resolved ->
                    sourcePreflightMemo.put(memoKey, resolved)
                }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return SourceSeparationRuntimeSongResolution.Unavailable(
                reason = SourceSeparationRuntimeUnavailableReason.SourceUnavailable,
                reference = model.activeReference(),
                detail = error.message,
            )
        }
        return SourceSeparationRuntimeSongResolution.Ready(
            SourceSeparationRuntimeSong(
                song = song,
                model = model,
                input = input,
                preflight = preflight,
            )
        )
    }

    override fun resolveForPlayback(
        song: Song,
        shouldCancel: () -> Boolean,
    ): SourceSeparationRuntimeSongResolution {
        if (song == Song.emptySong) {
            return SourceSeparationRuntimeSongResolution.Unavailable(
                SourceSeparationRuntimeUnavailableReason.NoSong,
            )
        }
        runCatching {
            multiStemPlaybackResolver?.resolve(song, shouldCancel)
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            null
        }?.let { resolved ->
            return SourceSeparationRuntimeSongResolution.Ready(resolved)
        }
        return resolve(song, shouldCancel)
    }

    override fun cacheStatus(
        song: SourceSeparationRuntimeSong,
    ): SourceSeparationModelAwareCacheStatus = cacheRepository.status(song.identity)

    override fun playableStatus(
        song: SourceSeparationRuntimeSong,
        playbackPositionMs: Long,
        readyWindowCount: Int,
    ): SourceSeparationModelAwarePlayableStatus = cacheRepository.playableStatus(
        identity = song.identity,
        playbackPositionMs = playbackPositionMs,
        readyWindowCount = readyWindowCount,
    )

    override fun readyHorizon(
        song: SourceSeparationRuntimeSong,
        playbackPositionMs: Long,
    ): SourceSeparationModelAwareReadyHorizonStatus = cacheRepository.readyHorizon(
        identity = song.identity,
        playbackPositionMs = playbackPositionMs,
    )

    override fun readBlend(song: SourceSeparationRuntimeSong): Float? =
        cacheRepository.readBlend(song.identity)

    override fun writeBlend(song: SourceSeparationRuntimeSong, blend: Float): Boolean =
        cacheRepository.writeBlend(song.identity, blend.coerceIn(0f, 1f))

    override fun readBlend(identity: SourceSeparationCacheIdentity): Float? =
        cacheRepository.readBlend(identity)

    override fun writeBlend(identity: SourceSeparationCacheIdentity, blend: Float): Boolean =
        cacheRepository.writeBlend(identity, blend.coerceIn(0f, 1f))

    override fun separate(
        song: SourceSeparationRuntimeSong,
        runtimeSettings: MdxRuntimeSettings,
        tryGpu: Boolean,
        runClass: SourceSeparationExecutionRunClass,
        onProgress: (MdxRangeProgress) -> Unit,
        onPrepared: (SourceSeparationCacheManifest) -> Unit,
        playbackPositionMsProvider: () -> Long?,
        playbackReadyWindowCountProvider: () -> Int,
        windowDecodeEnabled: Boolean,
        shouldPause: () -> Boolean,
        pauseReasonProvider: () -> SourceSeparationPauseReason,
        shouldCancel: () -> Boolean,
    ): SourceSeparationModelAwareEngineResult {
        val scopedEngine = if (runClass == SourceSeparationExecutionRunClass.ManualFullSong) {
            manualFullSongEngineFactory?.invoke()
        } else {
            null
        }
        val selectedEngine = scopedEngine ?: engine
        return try {
            val model = requireNotNull(song.model) {
                "Multi-stem playback targets cannot be submitted to the MDX engine."
            }
            selectedEngine.separateResolved(
                input = song.input,
                model = model,
                preflight = song.preflight,
                runtimeSettings = runtimeSettings,
                executionBackendPolicy = if (tryGpu) {
                    SourceSeparationExecutionBackendPolicy.Auto
                } else {
                    SourceSeparationExecutionBackendPolicy.Cpu
                },
                runClass = runClass,
                onProgress = onProgress,
                onPrepared = onPrepared,
                playbackPositionMsProvider = playbackPositionMsProvider,
                playbackReadyWindowCountProvider = playbackReadyWindowCountProvider,
                windowDecodeEnabled = windowDecodeEnabled,
                shouldPause = shouldPause,
                pauseReasonProvider = pauseReasonProvider,
                shouldCancel = shouldCancel,
            )
        } finally {
            scopedEngine?.close()
        }
    }

    override fun promote(
        cacheKey: String,
        shouldCancel: () -> Boolean,
    ): SourceSeparationCacheFlacPromotionResult = flacPromoter.promote(cacheKey, shouldCancel)

    override fun cleanCompletedTemporaryFiles(cacheKey: String): Boolean =
        runCoordinator.cleanCompletedTemporaryFiles(cacheKey)

    override fun cleanPendingCompletedTemporaryFiles(): List<String> =
        cacheRepository.completedCleanupKeys()
            .filter(runCoordinator::cleanCompletedTemporaryFiles)

    override fun entries(): List<SourceSeparationModelAwareCacheEntry> = cacheRepository.entries()

    override fun delete(cacheKey: String): SourceSeparationCacheMutationResult =
        cacheRepository.delete(cacheKey)

    override fun prune(
        partialLimit: Int,
        completedLimit: Int,
        protectedCacheKeys: Set<String>,
    ): SourceSeparationModelAwareCachePruneResult = cacheRepository.prune(
        partialLimit = partialLimit,
        completedLimit = completedLimit,
        protectedCacheKeys = protectedCacheKeys,
    )

    override fun openCompletedCache(cacheKey: String): SourceSeparationModelAwareCachePlayback? =
        cacheRepository.openCompletedCache(cacheKey)

}

private data class SourceSeparationSourcePreflightMemoKey(
    val sourceUri: String,
    val filePath: String,
    val fileSize: Long,
    val rawDateModified: Long,
    val durationMs: Long,
)

private class SourceSeparationSourcePreflightMemo(
    private val maxEntries: Int = MAX_ENTRIES,
) {
    private val lock = Any()
    private val entries = object : LinkedHashMap<
        SourceSeparationSourcePreflightMemoKey,
        SourceSeparationCacheSourcePreflight,
    >(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<
                SourceSeparationSourcePreflightMemoKey,
                SourceSeparationCacheSourcePreflight,
            >?,
        ): Boolean = size > maxEntries
    }

    fun get(
        key: SourceSeparationSourcePreflightMemoKey,
    ): SourceSeparationCacheSourcePreflight? = synchronized(lock) { entries[key] }

    fun put(
        key: SourceSeparationSourcePreflightMemoKey,
        value: SourceSeparationCacheSourcePreflight,
    ) {
        synchronized(lock) { entries[key] = value }
    }

    private companion object {
        const val MAX_ENTRIES = 4
    }
}

class SourceSeparationRuntimeSong internal constructor(
    val song: Song,
    internal val model: SourceSeparationResolvedCacheModel?,
    internal val input: SourceSeparationModelAwareSongInput,
    internal val preflight: SourceSeparationCacheSourcePreflight,
    private val identityOverride: SourceSeparationCacheIdentity? = null,
) {
    val identity = identityOverride ?: requireNotNull(model).contract.identity(preflight.identity)
    val cacheKey: String
        get() = identity.cacheKey
    val modelId: String
        get() = identity.modelId
    val artifactSha256: String
        get() = identity.artifactSha256
    val profileRevisionId: String
        get() = identity.profileRevisionId

    internal companion object {
        fun forMultiStem(
            song: Song,
            identity: SourceSeparationCacheIdentity,
            input: SourceSeparationModelAwareSongInput,
            preflight: SourceSeparationCacheSourcePreflight,
        ) = SourceSeparationRuntimeSong(
            song = song,
            model = null,
            input = input,
            preflight = preflight,
            identityOverride = identity,
        )
    }
}

sealed interface SourceSeparationRuntimeSongResolution {
    data class Ready(
        val song: SourceSeparationRuntimeSong,
    ) : SourceSeparationRuntimeSongResolution

    data class Unavailable(
        val reason: SourceSeparationRuntimeUnavailableReason,
        val reference: SourceSeparationActiveModelReference? = null,
        val detail: String? = null,
    ) : SourceSeparationRuntimeSongResolution
}

enum class SourceSeparationRuntimeUnavailableReason {
    NoSong,
    NoSelection,
    PendingSelection,
    ModelNotInstalled,
    ModelIdentityMismatch,
    ProfileNotInstalled,
    ContractMismatch,
    ContractInvalid,
    RuntimeUnsupported,
    SourceUnavailable,
}

internal fun interface SourceSeparationRuntimeCompatibilityResolver {
    fun unsupportedReason(model: SourceSeparationResolvedCacheModel): String?
}

internal fun interface SourceSeparationRuntimeSongInputFactory {
    fun create(song: Song): SourceSeparationModelAwareSongInput
}

internal object AndroidSourceSeparationRuntimeCompatibilityResolver :
    SourceSeparationRuntimeCompatibilityResolver {
    override fun unsupportedReason(model: SourceSeparationResolvedCacheModel): String? {
        val platform = try {
            AndroidMdxRuntimePlatformProvider.current()
        } catch (error: Throwable) {
            return error.message ?: "The process ABI is unsupported."
        }
        val decision = MdxLiteRtCompatibilityResolver.resolve(
            profile = model.executionProfile,
            backend = MdxInferenceBackend.LiteRtCpu,
            platform = platform,
            policy = MdxCompatibilityPolicy.AllowCandidates,
        )
        return decision.reason.takeUnless { decision.isAllowed }
    }
}

/** Used only by the compile-time-gated AndroidTest bound-process harness. */
internal object X86ProcessValidationRuntimeCompatibilityResolver :
    SourceSeparationRuntimeCompatibilityResolver {
    override fun unsupportedReason(model: SourceSeparationResolvedCacheModel): String? {
        val normalReason = AndroidSourceSeparationRuntimeCompatibilityResolver
            .unsupportedReason(model)
            ?: return null
        val platform = runCatching { AndroidMdxRuntimePlatformProvider.current() }
            .getOrElse { return normalReason }
        return if (MdxX86ProcessValidationOverride.applyTo(model, platform) != null) {
            null
        } else {
            normalReason
        }
    }
}

private fun SourceSeparationActiveCacheModelUnavailableReason.toRuntimeReason():
        SourceSeparationRuntimeUnavailableReason = when (this) {
    SourceSeparationActiveCacheModelUnavailableReason.NoSelection ->
        SourceSeparationRuntimeUnavailableReason.NoSelection
    SourceSeparationActiveCacheModelUnavailableReason.PendingSelection ->
        SourceSeparationRuntimeUnavailableReason.PendingSelection
    SourceSeparationActiveCacheModelUnavailableReason.ModelNotInstalled ->
        SourceSeparationRuntimeUnavailableReason.ModelNotInstalled
    SourceSeparationActiveCacheModelUnavailableReason.ModelIdentityMismatch ->
        SourceSeparationRuntimeUnavailableReason.ModelIdentityMismatch
    SourceSeparationActiveCacheModelUnavailableReason.ProfileNotInstalled ->
        SourceSeparationRuntimeUnavailableReason.ProfileNotInstalled
    SourceSeparationActiveCacheModelUnavailableReason.ContractMismatch ->
        SourceSeparationRuntimeUnavailableReason.ContractMismatch
    SourceSeparationActiveCacheModelUnavailableReason.ContractInvalid ->
        SourceSeparationRuntimeUnavailableReason.ContractInvalid
}

private fun SourceSeparationResolvedCacheModel.activeReference() =
    SourceSeparationActiveModelReference(
        modelId = contract.modelId,
        artifactSha256 = artifact.sha256,
        contractSchemaVersion = contract.contractSchemaVersion,
        profileId = contract.profileRevisionId.takeIf {
            installed.bindingKind == SourceSeparationPresetBindingKind.CustomProfile
        },
    )
