package com.mardous.booming.separation.process.ipc

import android.content.Context
import com.mardous.booming.separation.SourceSeparationBackgroundPolicy
import com.mardous.booming.separation.SourceSeparationExecutionRunClass
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunJournal
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunJournalLifecycle
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheStore
import com.mardous.booming.separation.process.SourceSeparationExecutionHostControlResult
import com.mardous.booming.separation.process.SourceSeparationExecutionHostEvent
import com.mardous.booming.separation.process.SourceSeparationExecutionHostSnapshot

internal class SourceSeparationIndependentRunRecoveryClient(
    context: Context,
    private val store: SourceSeparationCacheStore,
    private val hostFactory: () -> BoundRemoteSourceSeparationExecutionHost = {
        BoundRemoteSourceSeparationExecutionHost(context.applicationContext)
    },
) {
    fun reconnect(
        onSnapshot: (SourceSeparationExecutionHostSnapshot) -> Unit,
        onEvent: (SourceSeparationExecutionHostEvent) -> Unit,
    ): SourceSeparationReconnectedSession? {
        val candidates = SourceSeparationIndependentRunRecoveryCandidateSelector.select(
            store.listManifests().mapNotNull { manifest ->
                store.readRunJournal(manifest.cacheKey)
            }
        )
        if (candidates.isEmpty()) return null
        val host = hostFactory()
        return try {
            val remote = host.reconnectableRun()
                ?: return host.close().let { null }
            val matching = candidates.singleOrNull { journal ->
                remote.matches(journal)
            } ?: return host.close().let { null }
            val adopted = host.adoptReconnectableRun(onSnapshot, onEvent)
                ?: return host.close().let { null }
            require(adopted.state.matches(matching)) {
                "The adopted source-separation run changed its durable identity."
            }
            SourceSeparationReconnectedSession(
                journal = matching,
                adopted = adopted,
                host = host,
            )
        } catch (error: Throwable) {
            host.close()
            throw error
        }
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
    ): List<SourceSeparationCacheRunJournal> = journals.filter { journal ->
        journal.lifecycle == SourceSeparationCacheRunJournalLifecycle.Running &&
            journal.request.runClass == SourceSeparationExecutionRunClass.ManualFullSong &&
            journal.request.backgroundPolicy ==
            SourceSeparationBackgroundPolicy.IndependentForegroundEligible
    }
}

internal class SourceSeparationReconnectedSession(
    val journal: SourceSeparationCacheRunJournal,
    val adopted: SourceSeparationReconnectedRun,
    private val host: BoundRemoteSourceSeparationExecutionHost,
) : AutoCloseable {
    val runId: String
        get() = adopted.state.descriptor.runId

    val processGeneration: Long
        get() = adopted.state.descriptor.processGeneration

    val cacheKey: String
        get() = adopted.state.descriptor.cacheKey

    fun pause(): SourceSeparationExecutionHostControlResult =
        host.pause(runId, processGeneration)

    fun cancel(): SourceSeparationExecutionHostControlResult =
        host.cancel(runId, processGeneration)

    fun closeTerminal(): SourceSeparationExecutionHostControlResult =
        host.closeRun(runId, processGeneration).also { close() }

    override fun close() {
        host.close()
    }
}
