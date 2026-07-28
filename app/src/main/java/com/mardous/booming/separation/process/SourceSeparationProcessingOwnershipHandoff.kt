package com.mardous.booming.separation.process

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class SourceSeparationProcessingOwnerToken(
    val cacheKey: String,
    val runId: String,
    val processGeneration: Long,
) {
    init {
        require(cacheKey.isNotBlank()) { "Processing owner cache key is empty." }
        require(runId.isNotBlank()) { "Processing owner run ID is empty." }
        require(processGeneration > 0L) { "Processing owner generation is invalid." }
    }
}

internal data class SourceSeparationProcessingOwnershipRecord(
    val owner: SourceSeparationProcessingOwnerToken,
    val acceptedAtElapsedRealtimeNanos: Long,
    val releasedAtElapsedRealtimeNanos: Long? = null,
    val releaseReason: String? = null,
) {
    init {
        require(acceptedAtElapsedRealtimeNanos >= 0L) {
            "Processing ownership acceptance timestamp is invalid."
        }
        require(releasedAtElapsedRealtimeNanos == null ||
            releasedAtElapsedRealtimeNanos >= acceptedAtElapsedRealtimeNanos
        ) { "Processing ownership release timestamp is invalid." }
        require((releasedAtElapsedRealtimeNanos == null) == (releaseReason == null)) {
            "Processing ownership release state is inconsistent."
        }
        require(releaseReason == null || releaseReason.isNotBlank()) {
            "Processing ownership release reason is empty."
        }
    }
}

internal data class SourceSeparationProcessingOwnershipSnapshot(
    val activeOwner: SourceSeparationProcessingOwnershipRecord? = null,
    val lastReleasedOwner: SourceSeparationProcessingOwnershipRecord? = null,
) {
    init {
        require(activeOwner?.releasedAtElapsedRealtimeNanos == null) {
            "Released processing ownership is exposed as active."
        }
        require(lastReleasedOwner == null ||
            lastReleasedOwner.releasedAtElapsedRealtimeNanos != null
        ) { "Live processing ownership is exposed as released." }
    }
}

/** Tracks the exact remote run currently protecting source-separation processing. */
internal class SourceSeparationProcessingOwnershipHandoff(
    private val elapsedRealtimeNanos: () -> Long,
) {
    private val lock = Any()
    private var nextLeaseId = 1L
    private var activeOwner: ActiveOwner? = null
    private var lastReleasedOwner: SourceSeparationProcessingOwnershipRecord? = null
    private val mutableStateFlow = MutableStateFlow(
        SourceSeparationProcessingOwnershipSnapshot(),
    )

    val stateFlow = mutableStateFlow.asStateFlow()

    fun createLease(): SourceSeparationProcessingOwnershipLease = synchronized(lock) {
        SourceSeparationProcessingOwnershipLease(
            tracker = this,
            leaseId = nextLeaseId++,
        )
    }

    private fun accept(
        leaseId: Long,
        owner: SourceSeparationProcessingOwnerToken,
    ): Boolean = synchronized(lock) {
        activeOwner?.let { active ->
            check(active.leaseId == leaseId && active.record.owner == owner) {
                "A different remote processing owner is already active."
            }
            return false
        }
        val record = SourceSeparationProcessingOwnershipRecord(
            owner = owner,
            acceptedAtElapsedRealtimeNanos = now(),
        )
        activeOwner = ActiveOwner(leaseId, record)
        publishLocked()
        true
    }

    private fun release(
        leaseId: Long,
        owner: SourceSeparationProcessingOwnerToken,
        reason: String,
    ): Boolean = synchronized(lock) {
        require(reason.isNotBlank()) { "Processing ownership release reason is empty." }
        val active = activeOwner ?: return false
        if (active.leaseId != leaseId || active.record.owner != owner) return false
        lastReleasedOwner = active.record.copy(
            releasedAtElapsedRealtimeNanos = now(),
            releaseReason = reason,
        )
        activeOwner = null
        publishLocked()
        true
    }

    private fun publishLocked() {
        mutableStateFlow.value = SourceSeparationProcessingOwnershipSnapshot(
            activeOwner = activeOwner?.record,
            lastReleasedOwner = lastReleasedOwner,
        )
    }

    private fun now(): Long = elapsedRealtimeNanos().also { timestamp ->
        require(timestamp >= 0L) { "Processing ownership clock returned an invalid value." }
    }

    private data class ActiveOwner(
        val leaseId: Long,
        val record: SourceSeparationProcessingOwnershipRecord,
    )

    internal class SourceSeparationProcessingOwnershipLease internal constructor(
        private val tracker: SourceSeparationProcessingOwnershipHandoff,
        private val leaseId: Long,
    ) : AutoCloseable {
        private val lock = Any()
        private var acceptedOwner: SourceSeparationProcessingOwnerToken? = null
        private var released = false

        fun accepted(owner: SourceSeparationProcessingOwnerToken): Boolean = synchronized(lock) {
            if (released) return false
            acceptedOwner?.let { accepted ->
                require(accepted == owner) {
                    "A processing ownership lease cannot accept a different run."
                }
                return false
            }
            val changed = tracker.accept(leaseId, owner)
            acceptedOwner = owner
            changed
        }

        fun release(reason: String): Boolean = synchronized(lock) {
            require(reason.isNotBlank()) { "Processing ownership release reason is empty." }
            if (released) return false
            released = true
            acceptedOwner?.let { owner -> tracker.release(leaseId, owner, reason) } ?: false
        }

        override fun close() {
            release("lease-closed")
        }
    }
}

internal object SourceSeparationProcessingLeasePolicy {
    fun shouldPlaybackServiceOwn(
        waitingForProcessingCache: Boolean,
        waitingCacheKey: String?,
        handoff: SourceSeparationProcessingOwnershipSnapshot,
    ): Boolean {
        if (!waitingForProcessingCache) return false
        val exactWaitingCacheKey = waitingCacheKey ?: return true
        return handoff.activeOwner?.owner?.cacheKey != exactWaitingCacheKey
    }
}
