package com.mardous.booming.separation.process.ipc

import android.content.Context
import android.util.Log
import com.mardous.booming.separation.SourceSeparationExecutionRunClass
import com.mardous.booming.separation.SourceSeparationMultiStemPlaybackSelectionSnapshot
import com.mardous.booming.separation.SourceSeparationPauseReason
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunJournal
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunJournalLifecycle
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheStore
import com.mardous.booming.separation.process.SourceSeparationMultiStemExecutionEvent
import com.mardous.booming.separation.process.SourceSeparationMultiStemIpcRunAuthority
import com.mardous.booming.separation.process.SourceSeparationMultiStemIpcStatus

internal interface SourceSeparationMultiStemIndependentRunRecovery {
    fun reconnect(
        selection: SourceSeparationMultiStemPlaybackSelectionSnapshot,
        onEvent: (SourceSeparationMultiStemExecutionEvent) -> Unit,
    ): SourceSeparationMultiStemReconnectedSession?
}

internal class SourceSeparationMultiStemIndependentRunRecoveryClient(
    context: Context,
    private val store: SourceSeparationCacheStore,
    private val hostFactory: () -> BoundRemoteSourceSeparationMultiStemExecutionHost = {
        BoundRemoteSourceSeparationMultiStemExecutionHost(context.applicationContext)
    },
) : SourceSeparationMultiStemIndependentRunRecovery {
    override fun reconnect(
        selection: SourceSeparationMultiStemPlaybackSelectionSnapshot,
        onEvent: (SourceSeparationMultiStemExecutionEvent) -> Unit,
    ): SourceSeparationMultiStemReconnectedSession? {
        val candidates = SourceSeparationMultiStemRecoveryCandidateSelector.select(
            journals = store.listManifests().mapNotNull { store.readRunJournal(it.cacheKey) },
            selection = selection,
        )
        if (candidates.isEmpty()) return null
        val host = hostFactory()
        return try {
            val remote = host.reconnectableRun() ?: return null
            val matching = candidates.singleOrNull { remote.matches(it) }
                ?: return run {
                    Log.w(TAG, "Active multi-stem run did not match its durable journal.")
                    null
                }
            val adopted = host.adoptReconnectableRun(onEvent)
                ?: return null
            check(adopted.state.matches(matching)) {
                "The adopted multi-stem run changed its durable identity."
            }
            BoundSourceSeparationMultiStemReconnectedSession(matching, adopted)
        } catch (error: Throwable) {
            throw error
        }
    }

    private fun com.mardous.booming.separation.process.SourceSeparationMultiStemIpcActiveRunState.matches(
        journal: SourceSeparationCacheRunJournal,
    ): Boolean {
        val request = journal.request
        return authority == SourceSeparationMultiStemIpcRunAuthority.IndependentForeground &&
            descriptor.cacheKey == request.cacheKey &&
            descriptor.cacheIdentity == request.identity &&
            descriptor.contract == request.contract &&
            descriptor.song == request.song &&
            descriptor.source.diagnostics == request.sourceDiagnostics &&
            descriptor.runId == request.runId &&
            descriptor.processGeneration == request.processGeneration &&
            descriptor.runtime.runClass == request.runClass &&
            descriptor.runtime.backgroundPolicy == request.backgroundPolicy &&
            !request.tryGpu
    }

    private companion object {
        const val TAG = "MultiStemRecovery"
    }
}

internal object SourceSeparationMultiStemRecoveryCandidateSelector {
    fun select(
        journals: List<SourceSeparationCacheRunJournal>,
        selection: SourceSeparationMultiStemPlaybackSelectionSnapshot,
    ): List<SourceSeparationCacheRunJournal> {
        val modelId = selection.modelId ?: return emptyList()
        return journals.filter { journal ->
            journal.lifecycle == SourceSeparationCacheRunJournalLifecycle.Running &&
                journal.request.runClass == SourceSeparationExecutionRunClass.ManualFullSong &&
                journal.request.contract.multiTensorContract != null &&
                journal.request.identity.modelId == modelId
        }
    }
}

internal interface SourceSeparationMultiStemReconnectedSession : AutoCloseable {
    val journal: SourceSeparationCacheRunJournal
    val baselineEvent: SourceSeparationMultiStemExecutionEvent
    val runId: String
    val processGeneration: Long
    val cacheKey: String
    fun pause(reason: SourceSeparationPauseReason): SourceSeparationMultiStemIpcStatus
    fun cancel(): SourceSeparationMultiStemIpcStatus
}

private class BoundSourceSeparationMultiStemReconnectedSession(
    override val journal: SourceSeparationCacheRunJournal,
    private val adopted: SourceSeparationMultiStemAdoptedRun,
) : SourceSeparationMultiStemReconnectedSession {
    override val baselineEvent: SourceSeparationMultiStemExecutionEvent = adopted.state.latestEvent
    override val runId: String get() = journal.request.runId
    override val processGeneration: Long get() = journal.request.processGeneration
    override val cacheKey: String get() = journal.request.cacheKey
    override fun pause(reason: SourceSeparationPauseReason): SourceSeparationMultiStemIpcStatus =
        adopted.pause(reason)
    override fun cancel(): SourceSeparationMultiStemIpcStatus = adopted.cancel()
    override fun close() = adopted.close()
}
