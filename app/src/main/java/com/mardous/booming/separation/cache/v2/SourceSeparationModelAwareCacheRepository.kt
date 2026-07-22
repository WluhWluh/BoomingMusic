package com.mardous.booming.separation.cache.v2

import com.mardous.booming.separation.cache.SourceSeparationSegmentState
import com.mardous.booming.separation.model.contract.ContractStemSemantic
import java.io.File

class SourceSeparationModelAwareCacheRepository(
    private val store: SourceSeparationCacheStore,
    private val leases: SourceSeparationCacheEntryLeaseRegistry =
        SourceSeparationCacheEntryLeaseRegistry(),
    private val modelAvailability: SourceSeparationCacheModelAvailabilityProvider =
        SourceSeparationCacheModelAvailabilityProvider {
            SourceSeparationCacheModelAvailability.Unknown
        },
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) {
    fun entries(): List<SourceSeparationModelAwareCacheEntry> {
        return store.listManifests().map { manifest ->
            val availability = modelAvailability.availability(manifest)
            val validation = if (manifest.state == SourceSeparationCacheManifestState.Completed) {
                store.validateCompletedEntry(manifest, verifyHashes = false)
            } else {
                null
            }
            SourceSeparationModelAwareCacheEntry(
                cacheKey = manifest.cacheKey,
                songId = manifest.song.songId,
                title = manifest.song.title,
                artist = manifest.song.artist,
                album = manifest.song.album,
                modelId = manifest.identity.modelId,
                displayName = manifest.contract.displayName,
                artifactSha256 = manifest.identity.artifactSha256,
                contractId = manifest.identity.contractId,
                profileRevisionId = manifest.identity.profileRevisionId,
                renderProfileId = manifest.identity.renderProfileId,
                state = manifest.toEntryState(availability, validation),
                modelAvailability = availability,
                readySegments = manifest.segmentPlan?.segments?.count {
                    it.state.isPlaybackReady
                },
                totalSegments = manifest.segmentPlan?.segmentCount,
                format = manifest.output?.stems?.firstOrNull()?.let { stem ->
                    if (stem.promotionValidated) {
                        SourceSeparationModelAwareCacheFormat.Flac
                    } else {
                        SourceSeparationModelAwareCacheFormat.Wav
                    }
                } ?: SourceSeparationModelAwareCacheFormat.Unknown,
                sizeBytes = store.entryDirectory(manifest.cacheKey).directorySize(),
                updatedAtEpochMs = manifest.updatedAtEpochMs,
                lastAccessedAtEpochMs = manifest.lastAccessedAtEpochMs,
            )
        }
    }

    fun manifest(identity: SourceSeparationCacheIdentity): SourceSeparationCacheManifest? {
        return store.readManifest(identity.cacheKey)
            ?.takeIf { it.identity == identity }
    }

    fun playableStatus(
        identity: SourceSeparationCacheIdentity,
        playbackPositionMs: Long,
        readyWindowCount: Int,
    ): SourceSeparationModelAwarePlayableStatus {
        val manifest = manifest(identity)
            ?: return SourceSeparationModelAwarePlayableStatus.Unavailable
        if (manifest.state == SourceSeparationCacheManifestState.Completed) {
            return if (store.validateCompletedEntry(manifest) ==
                SourceSeparationCacheValidationResult.Valid
            ) {
                openPlayback(manifest)?.let(SourceSeparationModelAwarePlayableStatus::Ready)
                    ?: SourceSeparationModelAwarePlayableStatus.Processing
            } else {
                SourceSeparationModelAwarePlayableStatus.Unavailable
            }
        }
        if (manifest.state != SourceSeparationCacheManifestState.Running) {
            return SourceSeparationModelAwarePlayableStatus.Unavailable
        }
        val output = manifest.output
            ?: return SourceSeparationModelAwarePlayableStatus.Processing
        val plan = manifest.segmentPlan
            ?: return SourceSeparationModelAwarePlayableStatus.Processing
        val sampleRate = plan.sampleRate.takeIf { it > 0 }
            ?: return SourceSeparationModelAwarePlayableStatus.Processing
        if (plan.segments.isEmpty() || output.stems.size != 2) {
            return SourceSeparationModelAwarePlayableStatus.Processing
        }
        val frame = ((playbackPositionMs.coerceAtLeast(0L) * sampleRate) / 1_000L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        val startIndex = plan.segmentIndexForFrame(frame)
        val endExclusive = (startIndex + readyWindowCount.coerceAtLeast(1))
            .coerceAtMost(plan.segments.size)
        val ready = plan.segments.subList(startIndex, endExclusive).all { segment ->
            segment.state.isPlaybackReady &&
                store.resolveEntryPath(manifest.cacheKey, segment.vocalsPath).isFile &&
                store.resolveEntryPath(manifest.cacheKey, segment.instrumentalPath).isFile
        }
        if (!ready) return SourceSeparationModelAwarePlayableStatus.Processing
        return openPlayback(manifest)?.let(SourceSeparationModelAwarePlayableStatus::Ready)
            ?: SourceSeparationModelAwarePlayableStatus.Processing
    }

    fun openCompletedCache(cacheKey: String): SourceSeparationModelAwareCachePlayback? {
        val manifest = store.readManifest(cacheKey)
            ?.takeIf { it.state == SourceSeparationCacheManifestState.Completed }
            ?: return null
        if (store.validateCompletedEntry(manifest) != SourceSeparationCacheValidationResult.Valid) {
            return null
        }
        return openPlayback(manifest)
    }

    fun readBlend(identity: SourceSeparationCacheIdentity): Float? {
        val manifest = manifest(identity) ?: return null
        val lease = leases.tryAcquireRead(manifest.cacheKey) ?: return null
        return lease.use { store.readPlaybackSettings(manifest)?.blend }
    }

    fun writeBlend(identity: SourceSeparationCacheIdentity, blend: Float): Boolean {
        val manifest = manifest(identity) ?: return false
        val lease = leases.tryAcquireRead(manifest.cacheKey) ?: return false
        return lease.use {
            store.writePlaybackSettings(
                manifest = manifest,
                settings = SourceSeparationCachePlaybackSettings(
                    cacheKey = manifest.cacheKey,
                    audioFingerprint = manifest.identity.source.audioFingerprint,
                    blend = blend,
                    updatedAtEpochMs = nowEpochMs(),
                ),
            )
            true
        }
    }

    fun delete(cacheKey: String): SourceSeparationCacheMutationResult {
        val lease = leases.tryAcquireMutation(cacheKey)
            ?: return SourceSeparationCacheMutationResult.Busy
        return lease.use {
            if (store.deleteEntry(cacheKey)) {
                SourceSeparationCacheMutationResult.Completed
            } else {
                SourceSeparationCacheMutationResult.Failed
            }
        }
    }

    fun prune(
        partialLimit: Int,
        completedLimit: Int,
        protectedCacheKeys: Set<String> = emptySet(),
    ): SourceSeparationModelAwareCachePruneResult {
        val manifests = store.listManifests()
        val (completed, partial) = manifests.partition { manifest ->
            manifest.state == SourceSeparationCacheManifestState.Completed &&
                store.validateCompletedEntry(manifest, verifyHashes = false) ==
                SourceSeparationCacheValidationResult.Valid
        }
        var deletedEntries = 0
        var deletedBytes = 0L

        fun pruneGroup(group: List<SourceSeparationCacheManifest>, limit: Int) {
            group.sortedByDescending(SourceSeparationCacheManifest::lastAccessedAtEpochMs)
                .drop(limit.coerceAtLeast(0))
                .forEach { manifest ->
                    if (manifest.cacheKey in protectedCacheKeys) return@forEach
                    val lease = leases.tryAcquireMutation(manifest.cacheKey) ?: return@forEach
                    lease.use {
                        val directory = store.entryDirectory(manifest.cacheKey)
                        val size = directory.directorySize()
                        if (store.deleteEntry(manifest.cacheKey)) {
                            deletedEntries += 1
                            deletedBytes += size
                        }
                    }
                }
        }

        pruneGroup(partial, partialLimit)
        pruneGroup(completed, completedLimit)
        return SourceSeparationModelAwareCachePruneResult(
            deletedEntries = deletedEntries,
            deletedBytes = deletedBytes,
        )
    }

    fun tryAcquireMutation(identity: SourceSeparationCacheIdentity): SourceSeparationCacheEntryLease? {
        return leases.tryAcquireMutation(identity.cacheKey)
    }

    fun isLeased(cacheKey: String): Boolean = leases.isLeased(cacheKey)

    private fun openPlayback(
        manifest: SourceSeparationCacheManifest,
    ): SourceSeparationModelAwareCachePlayback? {
        val lease = leases.tryAcquireRead(manifest.cacheKey) ?: return null
        return try {
            val stems = manifest.output?.stems.orEmpty().associate { stem ->
                stem.semantic to store.resolveEntryPath(manifest.cacheKey, stem.playbackPath())
            }
            val vocals = stems[ContractStemSemantic.Vocals]
                ?: return lease.close().let { null }
            val instrumental = stems[ContractStemSemantic.Instrumental]
                ?: return lease.close().let { null }
            if (!vocals.isFile || !instrumental.isFile) {
                lease.close()
                return null
            }
            SourceSeparationModelAwareCachePlayback(
                manifest = manifest,
                vocalsFile = vocals,
                instrumentalFile = instrumental,
                closeAction = {
                    lease.close()
                    touchAfterPlayback(manifest)
                },
            )
        } catch (error: Throwable) {
            lease.close()
            throw error
        }
    }

    private fun touchAfterPlayback(manifest: SourceSeparationCacheManifest) {
        val lease = leases.tryAcquireMutation(manifest.cacheKey) ?: return
        lease.use { store.touchManifest(manifest) }
    }

    private fun SourceSeparationCacheManifest.toEntryState(
        availability: SourceSeparationCacheModelAvailability,
        validation: SourceSeparationCacheValidationResult?,
    ): SourceSeparationModelAwareCacheEntryState {
        return when (state) {
            SourceSeparationCacheManifestState.Completed -> {
                if (validation == SourceSeparationCacheValidationResult.Valid) {
                    SourceSeparationModelAwareCacheEntryState.Completed
                } else {
                    SourceSeparationModelAwareCacheEntryState.Corrupt
                }
            }
            SourceSeparationCacheManifestState.Running -> {
                if (availability == SourceSeparationCacheModelAvailability.InstalledExact) {
                    SourceSeparationModelAwareCacheEntryState.Partial
                } else {
                    SourceSeparationModelAwareCacheEntryState.Stale
                }
            }
            SourceSeparationCacheManifestState.Canceled ->
                SourceSeparationModelAwareCacheEntryState.Canceled
            SourceSeparationCacheManifestState.Failed ->
                SourceSeparationModelAwareCacheEntryState.Failed
        }
    }

    private fun File.directorySize(): Long {
        if (!isDirectory) return 0L
        return walkTopDown().filter(File::isFile).sumOf(File::length)
    }
}

fun interface SourceSeparationCacheModelAvailabilityProvider {
    fun availability(manifest: SourceSeparationCacheManifest): SourceSeparationCacheModelAvailability
}

enum class SourceSeparationCacheModelAvailability {
    InstalledExact,
    ModelNotInstalled,
    ProfileNotInstalled,
    ContractMismatch,
    Unknown,
}

data class SourceSeparationModelAwareCacheEntry(
    val cacheKey: String,
    val songId: Long,
    val title: String,
    val artist: String,
    val album: String,
    val modelId: String,
    val displayName: String,
    val artifactSha256: String,
    val contractId: String,
    val profileRevisionId: String,
    val renderProfileId: String,
    val state: SourceSeparationModelAwareCacheEntryState,
    val modelAvailability: SourceSeparationCacheModelAvailability,
    val readySegments: Int?,
    val totalSegments: Int?,
    val format: SourceSeparationModelAwareCacheFormat,
    val sizeBytes: Long,
    val updatedAtEpochMs: Long,
    val lastAccessedAtEpochMs: Long,
)

enum class SourceSeparationModelAwareCacheEntryState {
    Partial,
    Stale,
    Completed,
    Canceled,
    Failed,
    Corrupt,
}

enum class SourceSeparationModelAwareCacheFormat {
    Wav,
    Flac,
    Unknown,
}

class SourceSeparationModelAwareCachePlayback internal constructor(
    val manifest: SourceSeparationCacheManifest,
    val vocalsFile: File,
    val instrumentalFile: File,
    private val closeAction: () -> Unit,
) : AutoCloseable {
    private var closed = false

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
        }
        closeAction()
    }
}

sealed class SourceSeparationModelAwarePlayableStatus {
    data class Ready(
        val playback: SourceSeparationModelAwareCachePlayback,
    ) : SourceSeparationModelAwarePlayableStatus()

    data object Processing : SourceSeparationModelAwarePlayableStatus()
    data object Unavailable : SourceSeparationModelAwarePlayableStatus()
}

enum class SourceSeparationCacheMutationResult {
    Completed,
    Busy,
    Failed,
}

data class SourceSeparationModelAwareCachePruneResult(
    val deletedEntries: Int,
    val deletedBytes: Long,
)
