package com.mardous.booming.separation.cache.v2

import com.mardous.booming.separation.SourceSeparationBackgroundPolicy
import com.mardous.booming.separation.SourceSeparationExecutionRunClass
import com.mardous.booming.separation.SourceSeparationGpuFallbackLatch
import kotlinx.serialization.Serializable

@Serializable
data class SourceSeparationCacheRunJournal(
    val journalSchemaVersion: Int = SCHEMA_VERSION,
    val request: SourceSeparationCacheRunJournalRequest,
    val lifecycle: SourceSeparationCacheRunJournalLifecycle,
    val transitions: List<SourceSeparationCacheRunJournalTransition>,
    val committedSegments: List<SourceSeparationCacheCommittedSegment> = emptyList(),
    val lastCommittedWindow: Int? = null,
    val updatedAtEpochMs: Long,
) {
    init {
        require(journalSchemaVersion == SCHEMA_VERSION) {
            "Unsupported cache run journal schema: $journalSchemaVersion"
        }
        require(request.identity.cacheKey == request.cacheKey) {
            "Cache run journal key does not match its identity."
        }
        require(transitions.isNotEmpty()) { "Cache run journal has no transitions." }
        require(transitions.map { it.sequence } == (1L..transitions.size.toLong()).toList()) {
            "Cache run journal transition sequence is not contiguous."
        }
        require(transitions.last().runId == request.runId) {
            "Cache run journal latest transition targets a stale run."
        }
        require(committedSegments.map { it.segmentIndex }.distinct().size ==
            committedSegments.size
        ) { "Cache run journal contains duplicate segment records." }
        require(lastCommittedWindow == null || committedSegments.any {
            it.segmentIndex == lastCommittedWindow
        }) { "Cache run journal last window is not committed." }
        require(updatedAtEpochMs >= request.admittedAtEpochMs) {
            "Cache run journal update time is invalid."
        }
        val fallbackTransitions = transitions.filter {
            it.type == SourceSeparationCacheRunTransitionType.GpuFallbackLatched
        }
        if (request.gpuFallbackLatch == null) {
            require(fallbackTransitions.isEmpty()) {
                "Cache run journal has a fallback transition without a latch."
            }
        } else {
            require(fallbackTransitions.singleOrNull()?.gpuFallbackLatch ==
                request.gpuFallbackLatch
            ) { "Cache run journal fallback history is inconsistent." }
        }
    }

    val latestSequence: Long
        get() = transitions.last().sequence

    val isTerminal: Boolean
        get() = lifecycle.isTerminal

    val hasLiveOrRecoverableOwner: Boolean
        get() = lifecycle == SourceSeparationCacheRunJournalLifecycle.Running ||
            lifecycle == SourceSeparationCacheRunJournalLifecycle.Paused

    fun reconcileOrphanedOwner(nowEpochMs: Long): SourceSeparationCacheRunJournal {
        require(lifecycle == SourceSeparationCacheRunJournalLifecycle.Running) {
            "Only a running cache journal can be reconciled as orphaned."
        }
        var reconciled = this
        reconciled.latestObserverTransition()
            ?.takeIf { it.type == SourceSeparationCacheRunTransitionType.ObserverConnected }
            ?.let { observer ->
                reconciled = reconciled.append(
                    type = SourceSeparationCacheRunTransitionType.ObserverDisconnected,
                    nowEpochMs = nowEpochMs,
                    observerId = observer.observerId,
                    observerProcessName = observer.observerProcessName,
                    observerReason = "owner-process-died",
                )
            }
        return reconciled.append(
            type = SourceSeparationCacheRunTransitionType.PreviousOwnerDied,
            nowEpochMs = nowEpochMs,
            lifecycle = SourceSeparationCacheRunJournalLifecycle.Paused,
        )
    }

    fun append(
        type: SourceSeparationCacheRunTransitionType,
        nowEpochMs: Long,
        request: SourceSeparationCacheRunJournalRequest = this.request,
        segmentIndex: Int? = null,
        error: SourceSeparationCacheError? = null,
        lifecycle: SourceSeparationCacheRunJournalLifecycle = this.lifecycle,
        committedSegment: SourceSeparationCacheCommittedSegment? = null,
        removeCommittedSegmentIndex: Int? = null,
        gpuFallbackLatch: SourceSeparationGpuFallbackLatch? = null,
        observerId: String? = null,
        observerProcessName: String? = null,
        observerReason: String? = null,
    ): SourceSeparationCacheRunJournal {
        var committed = committedSegments
        if (removeCommittedSegmentIndex != null) {
            committed = committed.filterNot { it.segmentIndex == removeCommittedSegmentIndex }
        }
        if (committedSegment != null) {
            committed = committed.filterNot { it.segmentIndex == committedSegment.segmentIndex } +
                committedSegment
        }
        return copy(
            request = request,
            lifecycle = lifecycle,
            transitions = transitions + SourceSeparationCacheRunJournalTransition(
                sequence = latestSequence + 1L,
                runId = request.runId,
                processGeneration = request.processGeneration,
                ownerPid = request.ownerPid,
                runClass = request.runClass,
                backgroundPolicy = request.backgroundPolicy,
                gpuFallbackLatch = gpuFallbackLatch,
                observerId = observerId,
                observerProcessName = observerProcessName,
                observerReason = observerReason,
                type = type,
                segmentIndex = segmentIndex,
                error = error,
                timestampEpochMs = nowEpochMs,
            ),
            committedSegments = committed.sortedBy { it.segmentIndex },
            lastCommittedWindow = when {
                committedSegment != null -> committedSegment.segmentIndex
                removeCommittedSegmentIndex != null -> committed.maxOfOrNull { it.segmentIndex }
                else -> lastCommittedWindow
            },
            updatedAtEpochMs = nowEpochMs,
        )
    }

    fun latchGpuFallback(
        latch: SourceSeparationGpuFallbackLatch,
        nowEpochMs: Long,
    ): SourceSeparationCacheRunJournal {
        require(lifecycle == SourceSeparationCacheRunJournalLifecycle.Running) {
            "GPU fallback can only be latched for a running cache run."
        }
        require(request.tryGpu) { "A CPU-only cache run cannot latch GPU fallback." }
        request.gpuFallbackLatch?.let { existing ->
            require(existing == latch) { "The cache run already latched a different fallback." }
            return this
        }
        return append(
            type = SourceSeparationCacheRunTransitionType.GpuFallbackLatched,
            nowEpochMs = nowEpochMs,
            request = request.copy(gpuFallbackLatch = latch),
            gpuFallbackLatch = latch,
        )
    }

    fun observerConnected(
        observerId: String,
        observerProcessName: String,
        nowEpochMs: Long,
    ): SourceSeparationCacheRunJournal {
        require(lifecycle == SourceSeparationCacheRunJournalLifecycle.Running) {
            "An observer can attach only to a running cache run."
        }
        val latest = latestObserverTransition()
        if (latest?.type == SourceSeparationCacheRunTransitionType.ObserverConnected &&
            latest.observerId == observerId &&
            latest.observerProcessName == observerProcessName
        ) {
            return this
        }
        require(latest == null ||
            latest.type == SourceSeparationCacheRunTransitionType.ObserverDisconnected
        ) { "A different cache-run observer is still connected." }
        return append(
            type = SourceSeparationCacheRunTransitionType.ObserverConnected,
            nowEpochMs = nowEpochMs,
            observerId = observerId,
            observerProcessName = observerProcessName,
        )
    }

    fun observerDisconnected(
        observerId: String,
        observerProcessName: String,
        reason: String,
        nowEpochMs: Long,
    ): SourceSeparationCacheRunJournal {
        val latest = latestObserverTransition()
        if (latest?.type == SourceSeparationCacheRunTransitionType.ObserverDisconnected &&
            latest.observerId == observerId &&
            latest.observerProcessName == observerProcessName
        ) {
            return this
        }
        require(latest?.type == SourceSeparationCacheRunTransitionType.ObserverConnected &&
            latest.observerId == observerId &&
            latest.observerProcessName == observerProcessName
        ) { "The cache-run observer identity is stale." }
        return append(
            type = SourceSeparationCacheRunTransitionType.ObserverDisconnected,
            nowEpochMs = nowEpochMs,
            observerId = observerId,
            observerProcessName = observerProcessName,
            observerReason = reason,
        )
    }

    private fun latestObserverTransition(): SourceSeparationCacheRunJournalTransition? =
        transitions.lastOrNull { transition ->
            transition.type == SourceSeparationCacheRunTransitionType.ObserverConnected ||
                transition.type == SourceSeparationCacheRunTransitionType.ObserverDisconnected
        }

    companion object {
        const val SCHEMA_VERSION = 6

        fun admitted(
            request: SourceSeparationCacheRunJournalRequest,
        ) = SourceSeparationCacheRunJournal(
            request = request,
            lifecycle = SourceSeparationCacheRunJournalLifecycle.Running,
            transitions = listOf(
                SourceSeparationCacheRunJournalTransition(
                    sequence = 1L,
                    runId = request.runId,
                    processGeneration = request.processGeneration,
                    ownerPid = request.ownerPid,
                    runClass = request.runClass,
                    backgroundPolicy = request.backgroundPolicy,
                    type = SourceSeparationCacheRunTransitionType.Admitted,
                    timestampEpochMs = request.admittedAtEpochMs,
                )
            ),
            updatedAtEpochMs = request.admittedAtEpochMs,
        )

        fun resume(
            previous: SourceSeparationCacheRunJournal,
            request: SourceSeparationCacheRunJournalRequest,
            committedSegments: List<SourceSeparationCacheCommittedSegment>,
        ): SourceSeparationCacheRunJournal {
            require(previous.request.identity == request.identity &&
                previous.request.contract == request.contract
            ) { "Cache run journal cannot resume a different exact identity." }
            if (previous.lifecycle == SourceSeparationCacheRunJournalLifecycle.Running ||
                previous.lifecycle == SourceSeparationCacheRunJournalLifecycle.Paused
            ) {
                require(previous.request.tryGpu == request.tryGpu &&
                    previous.request.gpuRuntimeIdentity == request.gpuRuntimeIdentity &&
                    previous.request.gpuFallbackLatch == request.gpuFallbackLatch
                ) {
                    "An active or paused cache run cannot change its admitted GPU runtime."
                }
            }
            if (previous.lifecycle == SourceSeparationCacheRunJournalLifecycle.Running) {
                require(previous.request.runClass == request.runClass &&
                    previous.request.backgroundPolicy == request.backgroundPolicy
                ) {
                    "An active cache run cannot change its run class or background policy."
                }
            }
            var sequence = previous.latestSequence
            val resumedTransitions = buildList {
                addAll(previous.transitions)
                val previousOwnerWasRunning =
                    previous.lifecycle == SourceSeparationCacheRunJournalLifecycle.Running
                if (previousOwnerWasRunning) {
                    sequence += 1L
                    add(
                        SourceSeparationCacheRunJournalTransition(
                            sequence = sequence,
                            runId = previous.request.runId,
                            processGeneration = previous.request.processGeneration,
                            ownerPid = previous.request.ownerPid,
                            runClass = previous.request.runClass,
                            backgroundPolicy = previous.request.backgroundPolicy,
                            type = SourceSeparationCacheRunTransitionType.PreviousOwnerDied,
                            timestampEpochMs = request.admittedAtEpochMs,
                        )
                    )
                }
                if (previous.lifecycle == SourceSeparationCacheRunJournalLifecycle.Running ||
                    previous.lifecycle == SourceSeparationCacheRunJournalLifecycle.Paused
                ) {
                    previous.latestObserverTransition()
                        ?.takeIf { transition ->
                            transition.type ==
                                SourceSeparationCacheRunTransitionType.ObserverConnected
                        }
                        ?.let { observer ->
                            sequence += 1L
                            add(
                                SourceSeparationCacheRunJournalTransition(
                                    sequence = sequence,
                                    runId = previous.request.runId,
                                    processGeneration = previous.request.processGeneration,
                                    ownerPid = previous.request.ownerPid,
                                    runClass = previous.request.runClass,
                                    backgroundPolicy = previous.request.backgroundPolicy,
                                    observerId = observer.observerId,
                                    observerProcessName = observer.observerProcessName,
                                    observerReason = if (previousOwnerWasRunning) {
                                        PREVIOUS_OWNER_DIED_OBSERVER_REASON
                                    } else {
                                        PREVIOUS_PAUSED_RUN_REPLACED_OBSERVER_REASON
                                    },
                                    type = SourceSeparationCacheRunTransitionType
                                        .ObserverDisconnected,
                                    timestampEpochMs = request.admittedAtEpochMs,
                                )
                            )
                        }
                }
                sequence += 1L
                add(
                    SourceSeparationCacheRunJournalTransition(
                        sequence = sequence,
                        runId = request.runId,
                        processGeneration = request.processGeneration,
                        ownerPid = request.ownerPid,
                        runClass = request.runClass,
                        backgroundPolicy = request.backgroundPolicy,
                        type = SourceSeparationCacheRunTransitionType.Admitted,
                        timestampEpochMs = request.admittedAtEpochMs,
                    )
                )
            }
            return SourceSeparationCacheRunJournal(
                request = request,
                lifecycle = SourceSeparationCacheRunJournalLifecycle.Running,
                transitions = resumedTransitions,
                committedSegments = committedSegments.sortedBy { it.segmentIndex },
                lastCommittedWindow = committedSegments.maxOfOrNull { it.segmentIndex },
                updatedAtEpochMs = request.admittedAtEpochMs,
            )
        }

        private const val PREVIOUS_OWNER_DIED_OBSERVER_REASON = "owner-process-died"
        private const val PREVIOUS_PAUSED_RUN_REPLACED_OBSERVER_REASON =
            "paused-run-replaced"
    }
}

@Serializable
data class SourceSeparationAdmittedGpuRuntimeIdentity(
    val profileId: String,
    val artifactVersion: String,
    val capabilitySchemaVersion: Int,
    val backend: String,
    val precision: String,
    val kernelBatchSize: Int,
    val commandQueueWindowSize: Int,
) {
    init {
        require(profileId.isNotBlank()) { "Admitted GPU profile ID is empty." }
        require(artifactVersion.isNotBlank()) { "Admitted GPU artifact version is empty." }
        require(capabilitySchemaVersion > 0) {
            "Admitted GPU capability schema is invalid."
        }
        require(backend.isNotBlank() && precision.isNotBlank()) {
            "Admitted GPU backend identity is incomplete."
        }
        require(kernelBatchSize > 0 && commandQueueWindowSize > 0) {
            "Admitted GPU queue policy is invalid."
        }
    }
}

@Serializable
data class SourceSeparationCacheRunJournalRequest(
    val cacheKey: String,
    val identity: SourceSeparationCacheIdentity,
    val contract: SourceSeparationCacheContractSnapshot,
    val song: SourceSeparationCacheSongLocator,
    val sourceDiagnostics: SourceSeparationCacheSourceDiagnostics,
    val runId: String,
    val processGeneration: Long,
    val ownerPid: Int? = null,
    val runClass: SourceSeparationExecutionRunClass,
    val backgroundPolicy: SourceSeparationBackgroundPolicy,
    val tryGpu: Boolean,
    val gpuRuntimeIdentity: SourceSeparationAdmittedGpuRuntimeIdentity?,
    val gpuFallbackLatch: SourceSeparationGpuFallbackLatch?,
    val admittedAtEpochMs: Long,
) {
    init {
        require(runId.isNotBlank()) { "Cache run journal ID is empty." }
        require(processGeneration > 0L) { "Cache run process generation is invalid." }
        require(ownerPid == null || ownerPid > 0) { "Cache run owner PID is invalid." }
        require(backgroundPolicy == runClass.backgroundPolicy) {
            "Cache run background policy does not match its run class."
        }
        require(tryGpu == (gpuRuntimeIdentity != null)) {
            "Cache run GPU preference and runtime identity disagree."
        }
        require(tryGpu || gpuFallbackLatch == null) {
            "A CPU-only cache run cannot carry a GPU fallback latch."
        }
        require(admittedAtEpochMs >= 0L) { "Cache run admission time is invalid." }
        require(contract.identity(
            source = identity.source,
            renderProfileId = identity.renderProfileId,
        ) == identity) { "Cache run journal request identity is inconsistent." }
    }
}

@Serializable
data class SourceSeparationCacheRunJournalTransition(
    val sequence: Long,
    val runId: String,
    val processGeneration: Long,
    val ownerPid: Int? = null,
    val runClass: SourceSeparationExecutionRunClass,
    val backgroundPolicy: SourceSeparationBackgroundPolicy,
    val gpuFallbackLatch: SourceSeparationGpuFallbackLatch? = null,
    val observerId: String? = null,
    val observerProcessName: String? = null,
    val observerReason: String? = null,
    val type: SourceSeparationCacheRunTransitionType,
    val segmentIndex: Int? = null,
    val error: SourceSeparationCacheError? = null,
    val timestampEpochMs: Long,
) {
    init {
        require(backgroundPolicy == runClass.backgroundPolicy) {
            "Cache run transition background policy does not match its run class."
        }
        require((type == SourceSeparationCacheRunTransitionType.GpuFallbackLatched) ==
            (gpuFallbackLatch != null)
        ) { "Cache run transition fallback payload is inconsistent." }
        val observerTransition = type == SourceSeparationCacheRunTransitionType.ObserverConnected ||
            type == SourceSeparationCacheRunTransitionType.ObserverDisconnected
        if (observerTransition) {
            require(observerId != null && observerProcessName != null) {
                "Cache run transition observer identity is incomplete."
            }
        } else {
            require(observerId == null && observerProcessName == null) {
                "Cache run transition has an unexpected observer identity."
            }
        }
        observerId?.let {
            require(OBSERVER_ID_PATTERN.matches(it)) {
                "Cache run observer ID is invalid."
            }
        }
        observerProcessName?.let {
            require(it.isNotBlank()) { "Cache run observer process name is empty." }
        }
        if (type == SourceSeparationCacheRunTransitionType.ObserverDisconnected) {
            require(!observerReason.isNullOrBlank()) {
                "Cache run observer disconnect reason is empty."
            }
        } else {
            require(observerReason == null) {
                "Cache run transition has an unexpected observer reason."
            }
        }
    }
}

@Serializable
enum class SourceSeparationCacheRunTransitionType {
    Admitted,
    ObserverConnected,
    ObserverDisconnected,
    GpuFallbackLatched,
    PreviousOwnerDied,
    Prepared,
    SegmentRunning,
    SegmentReady,
    SegmentInvalidated,
    Completed,
    Paused,
    ActiveModelSuperseded,
    UserCanceled,
    Incompatible,
    ForegroundTimeout,
    CacheCleared,
    Failed,
}

private val OBSERVER_ID_PATTERN = Regex("^[A-Za-z0-9._-]{1,128}$")

@Serializable
enum class SourceSeparationCacheRunJournalLifecycle {
    Running,
    Paused,
    Canceled,
    Failed,
    CacheLost,
    Completed,
    ;

    val isTerminal: Boolean
        get() = this != Running
}

@Serializable
data class SourceSeparationCacheCommittedSegment(
    val segmentIndex: Int,
    val vocalsPath: String,
    val vocalsIntegrity: SourceSeparationCacheFileIntegrity,
    val instrumentalPath: String,
    val instrumentalIntegrity: SourceSeparationCacheFileIntegrity,
) {
    init {
        require(segmentIndex >= 0) { "Committed cache segment index is invalid." }
        SourceSeparationCacheRelativePath.requireValid(vocalsPath)
        SourceSeparationCacheRelativePath.requireValid(instrumentalPath)
    }
}
