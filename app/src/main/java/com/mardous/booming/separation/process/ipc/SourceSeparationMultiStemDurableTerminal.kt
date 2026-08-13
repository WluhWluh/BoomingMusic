package com.mardous.booming.separation.process.ipc

import com.mardous.booming.separation.SourceSeparationPauseReason
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunJournal
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunJournalLifecycle
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunTransitionType
import com.mardous.booming.separation.process.SourceSeparationMultiStemExecutionDescriptor

internal sealed interface SourceSeparationMultiStemDurableTerminal {
    data object Completed : SourceSeparationMultiStemDurableTerminal

    data class Paused(
        val reason: SourceSeparationPauseReason,
    ) : SourceSeparationMultiStemDurableTerminal

    data class Canceled(
        val message: String?,
    ) : SourceSeparationMultiStemDurableTerminal

    data class Failed(
        val errorType: String,
        val message: String?,
    ) : SourceSeparationMultiStemDurableTerminal
}

internal fun SourceSeparationCacheRunJournal.terminalStateFor(
    descriptor: SourceSeparationMultiStemExecutionDescriptor,
): SourceSeparationMultiStemDurableTerminal? {
    if (request.runId != descriptor.runId ||
        request.processGeneration != descriptor.processGeneration
    ) {
        return null
    }
    require(request.cacheKey == descriptor.cacheKey &&
        request.identity == descriptor.cacheIdentity &&
        request.contract == descriptor.contract &&
        request.song == descriptor.song &&
        request.sourceDiagnostics == descriptor.source.diagnostics &&
        request.runClass == descriptor.runtime.runClass &&
        request.backgroundPolicy == descriptor.runtime.backgroundPolicy &&
        !request.tryGpu
    ) { "Durable multi-stem terminal state targets a different execution." }

    return when (lifecycle) {
        SourceSeparationCacheRunJournalLifecycle.Running -> null
        SourceSeparationCacheRunJournalLifecycle.Completed -> {
            requireTerminalTransition(SourceSeparationCacheRunTransitionType.Completed)
            SourceSeparationMultiStemDurableTerminal.Completed
        }
        SourceSeparationCacheRunJournalLifecycle.Paused -> {
            val terminal = requireTerminalTransition(
                SourceSeparationCacheRunTransitionType.Paused,
                SourceSeparationCacheRunTransitionType.ActiveModelSuperseded,
                SourceSeparationCacheRunTransitionType.ForegroundTimeout,
            )
            SourceSeparationMultiStemDurableTerminal.Paused(
                reason = if (terminal.type ==
                    SourceSeparationCacheRunTransitionType.ActiveModelSuperseded
                ) {
                    SourceSeparationPauseReason.ActiveModelSuperseded
                } else {
                    SourceSeparationPauseReason.Standard
                },
            )
        }
        SourceSeparationCacheRunJournalLifecycle.Canceled -> {
            val terminal = requireTerminalTransition(
                SourceSeparationCacheRunTransitionType.UserCanceled,
                SourceSeparationCacheRunTransitionType.CacheCleared,
            )
            SourceSeparationMultiStemDurableTerminal.Canceled(terminal.error?.message)
        }
        SourceSeparationCacheRunJournalLifecycle.Failed,
        SourceSeparationCacheRunJournalLifecycle.CacheLost,
        -> {
            val terminal = requireTerminalTransition(
                SourceSeparationCacheRunTransitionType.Failed,
                SourceSeparationCacheRunTransitionType.Incompatible,
                SourceSeparationCacheRunTransitionType.CacheCleared,
            )
            SourceSeparationMultiStemDurableTerminal.Failed(
                errorType = terminal.error?.type ?: terminal.type.name,
                message = terminal.error?.message,
            )
        }
    }
}

private fun SourceSeparationCacheRunJournal.requireTerminalTransition(
    vararg expected: SourceSeparationCacheRunTransitionType,
) = requireNotNull(transitions.lastOrNull { it.type in expected }) {
    "Durable multi-stem lifecycle has no matching terminal transition."
}
