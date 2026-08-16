package com.mardous.booming.separation.process.ipc

import com.mardous.booming.separation.SourceSeparationPauseReason
import com.mardous.booming.separation.SourceSeparationPausedException
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheError
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunJournalLifecycle
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunTransitionType
import java.util.concurrent.CancellationException

internal sealed interface SourceSeparationMultiStemTerminalControl {
    data object Cancel : SourceSeparationMultiStemTerminalControl

    data class Pause(
        val reason: SourceSeparationPauseReason,
    ) : SourceSeparationMultiStemTerminalControl
}

internal object SourceSeparationMultiStemHardTerminationPolicy {
    const val DEFAULT_GRACE_MS = 2_000L

    fun canTerminate(
        activeRunId: String?,
        activeProcessGeneration: Long?,
        activeCancelRequested: Boolean,
        activePauseRequested: Boolean,
        requestedRunId: String,
        requestedProcessGeneration: Long,
    ): Boolean = activeRunId == requestedRunId &&
        activeProcessGeneration == requestedProcessGeneration &&
        (activeCancelRequested || activePauseRequested)

    fun failure(
        control: SourceSeparationMultiStemTerminalControl,
        cause: Throwable? = null,
    ): Throwable = when (control) {
        SourceSeparationMultiStemTerminalControl.Cancel -> CancellationException(
            "Source separation was canceled; the unresponsive inference process was stopped.",
        ).also { cancellation ->
            if (cause != null) cancellation.initCause(cause)
        }
        is SourceSeparationMultiStemTerminalControl.Pause -> SourceSeparationPausedException(
            message = "Source separation was paused; the unresponsive inference process was stopped.",
            cause = cause,
            pauseReason = control.reason,
        )
    }

    fun transition(
        control: SourceSeparationMultiStemTerminalControl,
    ): SourceSeparationCacheRunTransitionType = when (control) {
        SourceSeparationMultiStemTerminalControl.Cancel ->
            SourceSeparationCacheRunTransitionType.UserCanceled
        is SourceSeparationMultiStemTerminalControl.Pause -> when (control.reason) {
            SourceSeparationPauseReason.Standard -> SourceSeparationCacheRunTransitionType.Paused
            SourceSeparationPauseReason.ActiveModelSuperseded ->
                SourceSeparationCacheRunTransitionType.ActiveModelSuperseded
        }
    }

    fun lifecycle(
        control: SourceSeparationMultiStemTerminalControl,
    ): SourceSeparationCacheRunJournalLifecycle = when (control) {
        SourceSeparationMultiStemTerminalControl.Cancel ->
            SourceSeparationCacheRunJournalLifecycle.Canceled
        is SourceSeparationMultiStemTerminalControl.Pause ->
            SourceSeparationCacheRunJournalLifecycle.Paused
    }

    fun cacheError(
        control: SourceSeparationMultiStemTerminalControl,
    ): SourceSeparationCacheError? = when (control) {
        SourceSeparationMultiStemTerminalControl.Cancel -> failure(control).let { error ->
            SourceSeparationCacheError(error::class.java.name, error.message)
        }
        is SourceSeparationMultiStemTerminalControl.Pause -> null
    }
}
