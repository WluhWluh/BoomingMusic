package com.mardous.booming.separation.process.ipc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.mardous.booming.separation.process.SourceSeparationProcessDiagnostics
import com.mardous.booming.separation.process.SourceSeparationProcessLifecyclePolicy
import com.mardous.booming.separation.process.SourceSeparationProcessSessionState
import com.mardous.booming.separation.process.SourceSeparationResidentProcessValidation

/** Keeps only an idle validation process bound while execution hosts are replaced. */
internal object SourceSeparationRemoteWarmRetention {
    private val lock = Any()
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { release() }
    private var binding: RetentionBinding? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) = Unit

        override fun onServiceDisconnected(name: ComponentName?) = clearDisconnectedBinding()

        override fun onBindingDied(name: ComponentName?) = clearDisconnectedBinding()

        override fun onNullBinding(name: ComponentName?) = release()
    }

    fun retain(
        context: Context,
        diagnostics: SourceSeparationProcessDiagnostics,
        terminalStatus: SourceSeparationIpcStatus?,
    ): Boolean {
        if (!SourceSeparationResidentProcessValidation.buildEnabled) return false
        if (!terminalStatus.allowsWarmRetention()) return false
        if (!diagnostics.isWarmRetentionCandidate()) return false
        val applicationContext = context.applicationContext
        synchronized(lock) {
            binding?.let { existing ->
                if (existing.matches(diagnostics)) {
                    scheduleTimeoutLocked()
                    return true
                }
            }
        }
        release()
        val didBind = runCatching {
            applicationContext.bindService(
                Intent(applicationContext, SourceSeparationExecutionService::class.java)
                    .setAction(SourceSeparationExecutionService.ACTION_RETAIN_WITHOUT_CLIENT),
                serviceConnection,
                Context.BIND_AUTO_CREATE,
            )
        }.getOrDefault(false)
        if (!didBind) return false
        synchronized(lock) {
            binding = RetentionBinding(
                context = applicationContext,
                processGeneration = diagnostics.processGeneration,
                processStartTicks = diagnostics.processStartTicks,
            )
            scheduleTimeoutLocked()
        }
        return true
    }

    fun release(processGeneration: Long? = null, processStartTicks: Long? = null) {
        val released = synchronized(lock) {
            val current = binding ?: return
            if (processGeneration != null && current.processGeneration != processGeneration) return
            if (processStartTicks != null && current.processStartTicks != processStartTicks) return
            binding = null
            handler.removeCallbacks(timeout)
            current
        }
        runCatching { released.context.unbindService(serviceConnection) }
    }

    private fun clearDisconnectedBinding() {
        synchronized(lock) {
            binding = null
            handler.removeCallbacks(timeout)
        }
    }

    private fun scheduleTimeoutLocked() {
        handler.removeCallbacks(timeout)
        handler.postDelayed(
            timeout,
            SourceSeparationProcessLifecyclePolicy.NO_CLIENT_WARM_RETENTION_MS,
        )
    }

    private data class RetentionBinding(
        val context: Context,
        val processGeneration: Long,
        val processStartTicks: Long,
    ) {
        fun matches(diagnostics: SourceSeparationProcessDiagnostics): Boolean =
            processGeneration == diagnostics.processGeneration &&
                processStartTicks == diagnostics.processStartTicks
    }
}

internal fun SourceSeparationProcessDiagnostics.isWarmRetentionCandidate(): Boolean =
    activeRunId == null &&
        session.state == SourceSeparationProcessSessionState.Resident &&
        session.activeLeaseCount == 0 &&
        !session.poisoned

internal fun SourceSeparationIpcStatus?.allowsWarmRetention(): Boolean =
    this == SourceSeparationIpcStatus.Completed
