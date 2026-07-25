package com.mardous.booming.separation.cache.v2

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
    }

    val latestSequence: Long
        get() = transitions.last().sequence

    val isTerminal: Boolean
        get() = lifecycle.isTerminal

    fun append(
        type: SourceSeparationCacheRunTransitionType,
        nowEpochMs: Long,
        segmentIndex: Int? = null,
        error: SourceSeparationCacheError? = null,
        lifecycle: SourceSeparationCacheRunJournalLifecycle = this.lifecycle,
        committedSegment: SourceSeparationCacheCommittedSegment? = null,
        removeCommittedSegmentIndex: Int? = null,
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
            lifecycle = lifecycle,
            transitions = transitions + SourceSeparationCacheRunJournalTransition(
                sequence = latestSequence + 1L,
                runId = request.runId,
                processGeneration = request.processGeneration,
                ownerPid = request.ownerPid,
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

    companion object {
        const val SCHEMA_VERSION = 1

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
            var sequence = previous.latestSequence
            val resumedTransitions = buildList {
                addAll(previous.transitions)
                if (previous.lifecycle == SourceSeparationCacheRunJournalLifecycle.Running) {
                    sequence += 1L
                    add(
                        SourceSeparationCacheRunJournalTransition(
                            sequence = sequence,
                            runId = previous.request.runId,
                            processGeneration = previous.request.processGeneration,
                            ownerPid = previous.request.ownerPid,
                            type = SourceSeparationCacheRunTransitionType.PreviousOwnerDied,
                            timestampEpochMs = request.admittedAtEpochMs,
                        )
                    )
                }
                sequence += 1L
                add(
                    SourceSeparationCacheRunJournalTransition(
                        sequence = sequence,
                        runId = request.runId,
                        processGeneration = request.processGeneration,
                        ownerPid = request.ownerPid,
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
    val admittedAtEpochMs: Long,
) {
    init {
        require(runId.isNotBlank()) { "Cache run journal ID is empty." }
        require(processGeneration > 0L) { "Cache run process generation is invalid." }
        require(ownerPid == null || ownerPid > 0) { "Cache run owner PID is invalid." }
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
    val type: SourceSeparationCacheRunTransitionType,
    val segmentIndex: Int? = null,
    val error: SourceSeparationCacheError? = null,
    val timestampEpochMs: Long,
)

@Serializable
enum class SourceSeparationCacheRunTransitionType {
    Admitted,
    PreviousOwnerDied,
    Prepared,
    SegmentRunning,
    SegmentReady,
    SegmentInvalidated,
    Completed,
    Paused,
    UserCanceled,
    Incompatible,
    ForegroundTimeout,
    CacheCleared,
    Failed,
}

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
