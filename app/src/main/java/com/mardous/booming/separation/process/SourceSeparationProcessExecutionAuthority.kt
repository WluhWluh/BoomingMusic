package com.mardous.booming.separation.process

import com.mardous.booming.separation.SourceSeparationModelFamily
import java.util.concurrent.atomic.AtomicBoolean

internal data class SourceSeparationProcessExecutionOwner(
    val family: SourceSeparationModelFamily,
    val runId: String,
    val processGeneration: Long,
) {
    init {
        require(runId.isNotBlank()) { "Process execution run ID is empty." }
        require(processGeneration > 0L) { "Process execution generation is invalid." }
    }
}

internal class SourceSeparationProcessExecutionBusyException(
    val activeOwner: SourceSeparationProcessExecutionOwner,
) : IllegalStateException(
    "The remote execution process is owned by " +
        "${activeOwner.family.name}:${activeOwner.runId}.",
)

/** One execution owner shared by every service in the remote inference process. */
internal class SourceSeparationProcessExecutionAuthority {
    private val lock = Any()
    private var active: ActiveLease? = null

    fun acquire(owner: SourceSeparationProcessExecutionOwner): SourceSeparationProcessExecutionLease =
        synchronized(lock) {
            active?.let { throw SourceSeparationProcessExecutionBusyException(it.owner) }
            val token = Any()
            active = ActiveLease(owner, token)
            SourceSeparationProcessExecutionLease(owner) { release(owner, token) }
        }

    fun snapshot(): SourceSeparationProcessExecutionOwner? = synchronized(lock) {
        active?.owner
    }

    private fun release(owner: SourceSeparationProcessExecutionOwner, token: Any) {
        synchronized(lock) {
            val current = active
            if (current?.owner == owner && current.token === token) active = null
        }
    }

    private data class ActiveLease(
        val owner: SourceSeparationProcessExecutionOwner,
        val token: Any,
    )

    companion object {
        val shared = SourceSeparationProcessExecutionAuthority()
    }
}

internal class SourceSeparationProcessExecutionLease(
    val owner: SourceSeparationProcessExecutionOwner,
    private val releaseAction: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) releaseAction()
    }
}

/** Defers lease release until both cleanup and the actual execution terminal are observed. */
internal class SourceSeparationProcessExecutionLifetime(
    private val lease: SourceSeparationProcessExecutionLease,
) : AutoCloseable {
    private val executionFinished = AtomicBoolean(false)
    private val closeRequested = AtomicBoolean(false)

    val owner: SourceSeparationProcessExecutionOwner
        get() = lease.owner

    fun markExecutionFinished() {
        executionFinished.set(true)
        releaseIfFinished()
    }

    fun executionFinished(): Boolean = executionFinished.get()

    fun rejectBeforeExecution() {
        markExecutionFinished()
        close()
    }

    override fun close() {
        closeRequested.set(true)
        releaseIfFinished()
    }

    private fun releaseIfFinished() {
        if (executionFinished.get() && closeRequested.get()) lease.close()
    }
}
