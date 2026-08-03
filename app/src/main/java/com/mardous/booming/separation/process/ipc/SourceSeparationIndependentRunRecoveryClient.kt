package com.mardous.booming.separation.process.ipc

import android.content.Context
import android.util.Log
import com.mardous.booming.separation.SourceSeparationBackgroundPolicy
import com.mardous.booming.separation.SourceSeparationExecutionRunClass
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunJournal
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunJournalLifecycle
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheStore
import com.mardous.booming.separation.process.SourceSeparationExecutionHostControlResult
import com.mardous.booming.separation.process.SourceSeparationExecutionHostEvent
import com.mardous.booming.separation.model.preset.SourceSeparationActiveSelectionSnapshot

internal interface SourceSeparationIndependentRunRecovery {
    fun reconnect(
        activeSelection: SourceSeparationActiveSelectionSnapshot,
        onEvent: (SourceSeparationExecutionHostEvent) -> Unit,
    ): SourceSeparationReconnectedSession?
}

internal class SourceSeparationIndependentRunRecoveryClient(
    context: Context,
    private val store: SourceSeparationCacheStore,
    private val hostFactory: () -> BoundRemoteSourceSeparationExecutionHost = {
        BoundRemoteSourceSeparationExecutionHost(context.applicationContext)
    },
) : SourceSeparationIndependentRunRecovery {
    override fun reconnect(
        activeSelection: SourceSeparationActiveSelectionSnapshot,
        onEvent: (SourceSeparationExecutionHostEvent) -> Unit,
    ): SourceSeparationReconnectedSession? {
        val candidates = SourceSeparationIndependentRunRecoveryCandidateSelector.select(
            journals = store.listManifests().mapNotNull { manifest ->
                store.readRunJournal(manifest.cacheKey)
            },
            activeSelection = activeSelection,
        )
        Log.d(TAG, "reconnect candidates=${candidates.size}")
        if (candidates.isEmpty()) return null
        val host = hostFactory()
        return try {
            val remote = host.reconnectableRun()
                ?: return host.close().let {
                    Log.d(TAG, "reconnect no remote active run")
                    null
                }
            Log.d(
                TAG,
                "reconnect remote run=${remote.descriptor.runId} " +
                        "generation=${remote.descriptor.processGeneration}",
            )
            val matching = candidates.singleOrNull { journal ->
                remote.matches(journal)
            } ?: return host.close().let {
                Log.w(TAG, "reconnect remote run did not match durable candidates")
                null
            }
            val adopted = host.adoptReconnectableRun(onSnapshot = {}, onEvent = onEvent)
                ?: return host.close().let {
                    Log.w(TAG, "reconnect remote run disappeared before adoption")
                    null
                }
            require(adopted.state.matches(matching)) {
                "The adopted source-separation run changed its durable identity."
            }
            BoundSourceSeparationReconnectedSession(
                journal = matching,
                adopted = adopted,
                host = host,
            )
        } catch (error: Throwable) {
            Log.w(TAG, "reconnect failed", error)
            host.close()
            throw error
        }
    }

    private companion object {
        const val TAG = "SourceSepRecovery"
    }

    private fun SourceSeparationIpcActiveRunState.matches(
        journal: SourceSeparationCacheRunJournal,
    ): Boolean {
        val request = journal.request
        return authority == SourceSeparationIpcRunAuthority.IndependentForeground &&
            descriptor.cacheKey == request.cacheKey &&
            descriptor.cacheIdentity == request.identity &&
            descriptor.contract == request.contract &&
            descriptor.song == request.song &&
            descriptor.runId == request.runId &&
            descriptor.processGeneration == request.processGeneration &&
            descriptor.runtime.runClass == request.runClass &&
            descriptor.runtime.backgroundPolicy == request.backgroundPolicy &&
            descriptor.runtime.tryGpu == request.tryGpu &&
            descriptor.runtime.gpuRuntimeIdentity == request.gpuRuntimeIdentity &&
            descriptor.runtime.gpuFallbackLatch == request.gpuFallbackLatch
    }
}

internal object SourceSeparationIndependentRunRecoveryCandidateSelector {
    fun select(
        journals: List<SourceSeparationCacheRunJournal>,
        activeSelection: SourceSeparationActiveSelectionSnapshot? = null,
    ): List<SourceSeparationCacheRunJournal> = journals.filter { journal ->
        journal.lifecycle == SourceSeparationCacheRunJournalLifecycle.Running &&
            journal.request.runClass == SourceSeparationExecutionRunClass.ManualFullSong &&
            journal.request.backgroundPolicy ==
            SourceSeparationBackgroundPolicy.IndependentForegroundEligible &&
            (activeSelection == null || journal.matches(activeSelection))
    }

    fun SourceSeparationCacheRunJournal.matches(
        activeSelection: SourceSeparationActiveSelectionSnapshot,
    ): Boolean {
        val active = activeSelection.reference ?: return false
        return request.identity.modelId == active.modelId &&
            request.identity.artifactSha256.equals(
                active.artifactSha256,
                ignoreCase = true,
            ) &&
            request.identity.contractSchemaVersion == active.contractSchemaVersion &&
            (active.profileId == null ||
                request.identity.profileRevisionId == active.profileId)
    }
}

internal interface SourceSeparationReconnectedSession : AutoCloseable {
    val journal: SourceSeparationCacheRunJournal
    val baselineEvent: SourceSeparationExecutionHostEvent
    val runId: String
    val processGeneration: Long
    val cacheKey: String

    fun pause(): SourceSeparationExecutionHostControlResult

    fun cancel(): SourceSeparationExecutionHostControlResult

    fun closeTerminal(): SourceSeparationExecutionHostControlResult
}

private class BoundSourceSeparationReconnectedSession(
    override val journal: SourceSeparationCacheRunJournal,
    adopted: SourceSeparationReconnectedRun,
    private val host: BoundRemoteSourceSeparationExecutionHost,
) : SourceSeparationReconnectedSession {
    override val baselineEvent: SourceSeparationExecutionHostEvent =
        adopted.snapshot.latestEvent

    override val runId: String
        get() = journal.request.runId

    override val processGeneration: Long
        get() = journal.request.processGeneration

    override val cacheKey: String
        get() = journal.request.cacheKey

    override fun pause(): SourceSeparationExecutionHostControlResult =
        host.pause(runId, processGeneration)

    override fun cancel(): SourceSeparationExecutionHostControlResult =
        host.cancel(runId, processGeneration)

    override fun closeTerminal(): SourceSeparationExecutionHostControlResult =
        host.closeRun(runId, processGeneration).also { close() }

    override fun close() {
        host.close()
    }
}
