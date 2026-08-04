package com.mardous.booming.separation.cache.v2

import com.mardous.booming.separation.cache.SourceSeparationSegmentState
import com.mardous.booming.separation.model.contract.StemDescriptor
import com.mardous.booming.separation.model.contract.StemSemanticId
import com.mardous.booming.separation.model.contract.toStemSet
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
    private val entryLocks: SourceSeparationCacheEntryLockManager = store.entryLocks(),
) {
    fun entries(): List<SourceSeparationModelAwareCacheEntry> {
        return store.listManifests().map { manifest ->
            val availability = modelAvailability.availability(manifest)
            val journal = store.readRunJournal(manifest.cacheKey)
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
                state = manifest.toEntryState(availability, validation, journal),
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
                stemLabels = manifest.contract.stemContract.toStemSet().stems
                    .map { stem -> stem.canonicalLabel },
                supportsStandardPlayback = manifest.output?.stems
                    ?.map(SourceSeparationCacheRenderedStem::semanticId)
                    ?.toSet() == setOf(
                    StemSemanticId.Vocals,
                    StemSemanticId.Instrumental,
                ),
            )
        }
    }

    fun manifest(identity: SourceSeparationCacheIdentity): SourceSeparationCacheManifest? {
        return store.readManifest(identity.cacheKey)
            ?.takeIf { it.identity == identity }
    }

    fun status(
        identity: SourceSeparationCacheIdentity,
    ): SourceSeparationModelAwareCacheStatus {
        val lease = leases.tryAcquireRead(identity.cacheKey)
            ?: return SourceSeparationModelAwareCacheStatus.Busy
        return lease.use {
            val manifest = manifest(identity)
                ?: return@use SourceSeparationModelAwareCacheStatus.Missing
            when (manifest.state) {
                SourceSeparationCacheManifestState.Completed -> {
                    if (store.validateCompletedEntry(manifest, verifyHashes = false) !=
                        SourceSeparationCacheValidationResult.Valid
                    ) {
                        SourceSeparationModelAwareCacheStatus.Corrupt(manifest)
                    } else {
                        SourceSeparationModelAwareCacheStatus.Completed(
                            manifest = manifest,
                            cleanupPending = manifest.cleanup != null,
                            canPromote = manifest.canPromote(),
                        )
                    }
                }

                SourceSeparationCacheManifestState.Partial ->
                    SourceSeparationModelAwareCacheStatus.Incomplete(
                        manifest = manifest,
                        readySegments = manifest.segmentPlan?.segments?.count {
                            it.state.isPlaybackReady
                        } ?: 0,
                        totalSegments = manifest.segmentPlan?.segmentCount ?: 0,
                    )
            }
        }
    }

    fun readyHorizon(
        identity: SourceSeparationCacheIdentity,
        playbackPositionMs: Long,
    ): SourceSeparationModelAwareReadyHorizonStatus {
        val lease = leases.tryAcquireRead(identity.cacheKey)
            ?: return SourceSeparationModelAwareReadyHorizonStatus.Busy
        return lease.use {
            val manifest = manifest(identity)
                ?: return@use SourceSeparationModelAwareReadyHorizonStatus.Unavailable
            if (manifest.state == SourceSeparationCacheManifestState.Completed) {
                return@use if (store.validateCompletedEntry(manifest, verifyHashes = false) ==
                    SourceSeparationCacheValidationResult.Valid
                ) {
                    SourceSeparationModelAwareReadyHorizonStatus.Completed(manifest)
                } else {
                    SourceSeparationModelAwareReadyHorizonStatus.Unavailable
                }
            }
            if (manifest.state != SourceSeparationCacheManifestState.Partial ||
                !hasLiveOrRecoverableProducer(manifest)
            ) {
                return@use SourceSeparationModelAwareReadyHorizonStatus.Unavailable
            }
            val output = manifest.output
                ?: return@use SourceSeparationModelAwareReadyHorizonStatus.Processing
            if (output.stems.any { stem ->
                    !store.resolveEntryPath(manifest.cacheKey, stem.playbackPath()).isFile
                }
            ) {
                return@use SourceSeparationModelAwareReadyHorizonStatus.Processing
            }
            val plan = manifest.segmentPlan
                ?: return@use SourceSeparationModelAwareReadyHorizonStatus.Processing
            val sampleRate = plan.sampleRate.takeIf { it > 0 }
                ?: return@use SourceSeparationModelAwareReadyHorizonStatus.Processing
            if (plan.segments.isEmpty()) {
                return@use SourceSeparationModelAwareReadyHorizonStatus.Processing
            }
            val positionMs = playbackPositionMs.coerceAtLeast(0L)
            val frame = ((positionMs * sampleRate) / 1_000L)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
            val segmentIndex = plan.segmentIndexForFrame(frame)
            val current = plan.segments.getOrNull(segmentIndex)
                ?: return@use SourceSeparationModelAwareReadyHorizonStatus.Processing
            if (!current.hasCompleteReadyArtifactSet(store, manifest.cacheKey)) {
                return@use SourceSeparationModelAwareReadyHorizonStatus.Processing
            }
            var readyThroughSegmentIndex = segmentIndex
            var readyUntilFrame = current.playbackEndFrame
            for (index in (segmentIndex + 1)..plan.segments.lastIndex) {
                val segment = plan.segments[index]
                if (!segment.hasCompleteReadyArtifactSet(store, manifest.cacheKey)) break
                readyThroughSegmentIndex = index
                readyUntilFrame = segment.playbackEndFrame
            }
            val readyUntilMs = (readyUntilFrame.toLong() * 1_000L) / sampleRate
            SourceSeparationModelAwareReadyHorizonStatus.Ready(
                manifest = manifest,
                positionMs = positionMs,
                readyUntilMs = readyUntilMs,
                readyAheadMs = (readyUntilMs - positionMs).coerceAtLeast(0L),
                segmentIndex = segmentIndex,
                readyThroughSegmentIndex = readyThroughSegmentIndex,
                readyThroughEnd = readyThroughSegmentIndex == plan.segments.lastIndex,
            )
        }
    }

    fun playableStatus(
        identity: SourceSeparationCacheIdentity,
        playbackPositionMs: Long,
        readyWindowCount: Int,
    ): SourceSeparationModelAwarePlayableStatus {
        val manifest = manifest(identity)
            ?: return SourceSeparationModelAwarePlayableStatus.Unavailable
        if (manifest.state == SourceSeparationCacheManifestState.Completed) {
            return if (store.validateCompletedEntry(manifest, verifyHashes = false) ==
                SourceSeparationCacheValidationResult.Valid
            ) {
                openPlayback(manifest)?.let(SourceSeparationModelAwarePlayableStatus::Ready)
                    ?: SourceSeparationModelAwarePlayableStatus.Processing
            } else {
                SourceSeparationModelAwarePlayableStatus.Unavailable
            }
        }
        if (manifest.state != SourceSeparationCacheManifestState.Partial ||
            !hasLiveOrRecoverableProducer(manifest)
        ) {
            return SourceSeparationModelAwarePlayableStatus.Unavailable
        }
        val output = manifest.output
            ?: return SourceSeparationModelAwarePlayableStatus.Processing
        val plan = manifest.segmentPlan
            ?: return SourceSeparationModelAwarePlayableStatus.Processing
        val sampleRate = plan.sampleRate.takeIf { it > 0 }
            ?: return SourceSeparationModelAwarePlayableStatus.Processing
        if (plan.segments.isEmpty() || output.stems.isEmpty()) {
            return SourceSeparationModelAwarePlayableStatus.Processing
        }
        val frame = ((playbackPositionMs.coerceAtLeast(0L) * sampleRate) / 1_000L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        val startIndex = plan.segmentIndexForFrame(frame)
        val endExclusive = (startIndex + readyWindowCount.coerceAtLeast(1))
            .coerceAtMost(plan.segments.size)
        val ready = plan.segments.subList(startIndex, endExclusive).all { segment ->
            segment.hasCompleteReadyArtifactSet(store, manifest.cacheKey)
        }
        if (!ready) return SourceSeparationModelAwarePlayableStatus.Processing
        return openPlayback(manifest)?.let(SourceSeparationModelAwarePlayableStatus::Ready)
            ?: SourceSeparationModelAwarePlayableStatus.Processing
    }

    fun openCompletedCache(cacheKey: String): SourceSeparationModelAwareCachePlayback? {
        val manifest = store.readManifest(cacheKey)
            ?.takeIf { it.state == SourceSeparationCacheManifestState.Completed }
            ?: return null
        if (store.validateCompletedEntry(manifest, verifyHashes = false) !=
            SourceSeparationCacheValidationResult.Valid
        ) {
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
        val lease = tryAcquireExclusive(
            manifest.cacheKey,
            SourceSeparationCacheLockPurpose.PlaybackSettings,
        ) ?: return false
        return lease.use {
            it.bindEntryDirectory(store.entryDirectory(manifest.cacheKey))
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
        val lease = tryAcquireExclusive(cacheKey, SourceSeparationCacheLockPurpose.Delete)
            ?: return SourceSeparationCacheMutationResult.Busy
        return lease.use {
            store.entryDirectory(cacheKey).takeIf(File::exists)?.let(it::bindEntryDirectory)
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
                    val lease = tryAcquireExclusive(
                        manifest.cacheKey,
                        SourceSeparationCacheLockPurpose.Prune,
                    ) ?: return@forEach
                    lease.use {
                        val directory = store.entryDirectory(manifest.cacheKey)
                        it.bindEntryDirectory(directory)
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

    fun tryAcquireRunWrite(
        identity: SourceSeparationCacheIdentity,
        owner: SourceSeparationCacheLockOwner = SourceSeparationCacheLockOwner(
            SourceSeparationCacheLockPurpose.Run,
        ),
    ): SourceSeparationCacheEntryLease? {
        return tryAcquireMutation(
            localLease = leases.tryAcquireRunWrite(identity.cacheKey),
            owner = owner,
        )
    }

    fun tryAcquireRead(cacheKey: String): SourceSeparationCacheEntryLease? {
        return leases.tryAcquireRead(cacheKey)
    }

    fun tryAcquireExclusive(
        cacheKey: String,
        purpose: SourceSeparationCacheLockPurpose = SourceSeparationCacheLockPurpose.Other,
    ): SourceSeparationCacheEntryLease? {
        return tryAcquireMutation(
            localLease = leases.tryAcquireExclusive(cacheKey),
            owner = SourceSeparationCacheLockOwner(purpose),
        )
    }

    fun isLeased(cacheKey: String): Boolean = leases.isLeased(cacheKey)

    fun completedCleanupKeys(): List<String> = store.listManifests()
        .filter { manifest ->
            manifest.state == SourceSeparationCacheManifestState.Completed &&
                manifest.cleanup != null
        }
        .map(SourceSeparationCacheManifest::cacheKey)

    private fun openPlayback(
        manifest: SourceSeparationCacheManifest,
    ): SourceSeparationModelAwareCachePlayback? {
        val lease = leases.tryAcquireRead(manifest.cacheKey) ?: return null
        return try {
            val stems = manifest.output?.stems.orEmpty().map { stem ->
                SourceSeparationPlaybackStemSource(
                    descriptor = stem.descriptor(),
                    file = store.resolveEntryPath(manifest.cacheKey, stem.playbackPath()),
                )
            }
            val expectedStems = manifest.contract.stemContract.toStemSet().stems
            if (stems.map(SourceSeparationPlaybackStemSource::descriptor) != expectedStems ||
                stems.any { !it.file.isFile }
            ) {
                lease.close()
                return null
            }
            SourceSeparationModelAwareCachePlayback(
                manifest = manifest,
                stems = stems,
                timingFile = manifest.output?.timingPath?.let { path ->
                    store.resolveEntryPath(manifest.cacheKey, path).takeIf(File::isFile)
                },
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
        val lease = tryAcquireExclusive(
            manifest.cacheKey,
            SourceSeparationCacheLockPurpose.PlaybackSettings,
        ) ?: return
        lease.use {
            it.bindEntryDirectory(store.entryDirectory(manifest.cacheKey))
            store.touchManifest(manifest)
        }
    }

    private fun tryAcquireMutation(
        localLease: SourceSeparationCacheEntryLease?,
        owner: SourceSeparationCacheLockOwner,
    ): SourceSeparationCacheEntryLease? {
        localLease ?: return null
        val kernelLease = entryLocks.tryAcquire(localLease.cacheKey, owner)
        if (kernelLease == null) {
            localLease.close()
            return null
        }
        return try {
            localLease.attachKernelLease(kernelLease)
            localLease
        } catch (error: Throwable) {
            kernelLease.close()
            localLease.close()
            throw error
        }
    }

    private fun SourceSeparationCacheManifest.toEntryState(
        availability: SourceSeparationCacheModelAvailability,
        validation: SourceSeparationCacheValidationResult?,
        journal: SourceSeparationCacheRunJournal?,
    ): SourceSeparationModelAwareCacheEntryState {
        return when (state) {
            SourceSeparationCacheManifestState.Completed -> {
                if (validation == SourceSeparationCacheValidationResult.Valid) {
                    SourceSeparationModelAwareCacheEntryState.Completed
                } else {
                    SourceSeparationModelAwareCacheEntryState.Corrupt
                }
            }
            SourceSeparationCacheManifestState.Partial -> when (journal?.lifecycle) {
                SourceSeparationCacheRunJournalLifecycle.Canceled ->
                    SourceSeparationModelAwareCacheEntryState.Canceled
                SourceSeparationCacheRunJournalLifecycle.Failed ->
                    SourceSeparationModelAwareCacheEntryState.Failed
                SourceSeparationCacheRunJournalLifecycle.CacheLost ->
                    SourceSeparationModelAwareCacheEntryState.Corrupt
                else -> if (availability == SourceSeparationCacheModelAvailability.InstalledExact) {
                    SourceSeparationModelAwareCacheEntryState.Partial
                } else {
                    SourceSeparationModelAwareCacheEntryState.Stale
                }
            }
        }
    }

    private fun hasLiveOrRecoverableProducer(manifest: SourceSeparationCacheManifest): Boolean {
        val journal = store.readRunJournal(manifest.cacheKey) ?: return false
        return journal.request.identity == manifest.identity && journal.hasLiveOrRecoverableOwner
    }

    private fun SourceSeparationCacheManifest.canPromote(): Boolean {
        val stems = output?.stems ?: return false
        return stems.any { !it.promotionValidated } && stems.all { stem ->
            store.resolveEntryPath(cacheKey, stem.wavPath).isFile
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
    val stemLabels: List<String> = emptyList(),
    val supportsStandardPlayback: Boolean = true,
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
    val stems: List<SourceSeparationPlaybackStemSource>,
    val timingFile: File?,
    private val closeAction: () -> Unit,
) : AutoCloseable {
    init {
        require(stems.isNotEmpty()) { "Cache playback stem set is empty." }
        require(stems.map { it.descriptor.order } == stems.indices.toList()) {
            "Cache playback stem order is not contiguous."
        }
        require(stems.map { it.descriptor.stemId }.distinct().size == stems.size) {
            "Cache playback stem IDs are not unique."
        }
    }

    val vocalsFile: File
        get() = requireStemFile(StemSemanticId.Vocals)

    val instrumentalFile: File
        get() = requireStemFile(StemSemanticId.Instrumental)

    private var closed = false

    fun fileFor(semanticId: StemSemanticId): File? =
        stems.singleOrNull { it.descriptor.semanticId == semanticId }?.file

    private fun requireStemFile(semanticId: StemSemanticId): File =
        requireNotNull(fileFor(semanticId)) {
            "Cache playback has no ${semanticId.value} stem."
        }

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
        }
        closeAction()
    }
}

data class SourceSeparationPlaybackStemSource(
    val descriptor: StemDescriptor,
    val file: File,
)

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

sealed interface SourceSeparationModelAwareCacheStatus {
    data object Missing : SourceSeparationModelAwareCacheStatus
    data object Busy : SourceSeparationModelAwareCacheStatus

    data class Incomplete(
        val manifest: SourceSeparationCacheManifest,
        val readySegments: Int,
        val totalSegments: Int,
    ) : SourceSeparationModelAwareCacheStatus

    data class Completed(
        val manifest: SourceSeparationCacheManifest,
        val cleanupPending: Boolean,
        val canPromote: Boolean,
    ) : SourceSeparationModelAwareCacheStatus

    data class Corrupt(
        val manifest: SourceSeparationCacheManifest,
    ) : SourceSeparationModelAwareCacheStatus
}

sealed interface SourceSeparationModelAwareReadyHorizonStatus {
    data class Ready(
        val manifest: SourceSeparationCacheManifest,
        val positionMs: Long,
        val readyUntilMs: Long,
        val readyAheadMs: Long,
        val segmentIndex: Int,
        val readyThroughSegmentIndex: Int,
        val readyThroughEnd: Boolean,
    ) : SourceSeparationModelAwareReadyHorizonStatus

    data class Completed(
        val manifest: SourceSeparationCacheManifest,
    ) : SourceSeparationModelAwareReadyHorizonStatus

    data object Processing : SourceSeparationModelAwareReadyHorizonStatus
    data object Unavailable : SourceSeparationModelAwareReadyHorizonStatus
    data object Busy : SourceSeparationModelAwareReadyHorizonStatus
}
