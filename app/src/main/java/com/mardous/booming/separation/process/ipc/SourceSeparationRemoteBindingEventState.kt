package com.mardous.booming.separation.process.ipc

import com.mardous.booming.separation.process.SourceSeparationExecutionHostEvent
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Keeps Binder callbacks and reconnect events scoped to one client binding. */
internal class SourceSeparationRemoteBindingEventState {
    private val lock = ReentrantLock()
    private var activeBindingGeneration = 0L
    private var latestBindingGeneration = 0L
    private var reconnectEvents: SourceSeparationRemoteEventQueue? = null
    private var staleCallbackDropCount = 0
    private var closed = false

    fun activate(bindingGeneration: Long) {
        require(bindingGeneration > 0L) { "Remote binding generation is invalid." }
        lock.withLock {
            check(!closed) { "Remote binding event state is closed." }
            require(bindingGeneration > latestBindingGeneration) {
                "Remote binding generation did not advance."
            }
            reconnectEvents?.close()
            reconnectEvents = SourceSeparationRemoteEventQueue()
            activeBindingGeneration = bindingGeneration
            latestBindingGeneration = bindingGeneration
        }
    }

    fun deactivate(bindingGeneration: Long) {
        lock.withLock {
            if (bindingGeneration != activeBindingGeneration) return
            activeBindingGeneration = 0L
            reconnectEvents?.close()
            reconnectEvents = null
        }
    }

    fun acceptsCallback(bindingGeneration: Long): Boolean = lock.withLock {
        val accepted = !closed && bindingGeneration == activeBindingGeneration
        if (!accepted) staleCallbackDropCount += 1
        accepted
    }

    fun bufferReconnectEvent(
        bindingGeneration: Long,
        event: SourceSeparationExecutionHostEvent,
    ): Boolean = lock.withLock {
        if (closed || bindingGeneration != activeBindingGeneration) return false
        requireNotNull(reconnectEvents).offer(event)
        true
    }

    fun drainReconnectEvents(
        bindingGeneration: Long,
    ): List<SourceSeparationExecutionHostEvent> = lock.withLock {
        require(!closed && bindingGeneration == activeBindingGeneration) {
            "Reconnect event drain targets a stale binding generation."
        }
        requireNotNull(reconnectEvents).drain()
    }

    fun staleCallbackDropCount(): Int = lock.withLock { staleCallbackDropCount }

    fun close() {
        lock.withLock {
            if (closed) return
            closed = true
            activeBindingGeneration = 0L
            reconnectEvents?.close()
            reconnectEvents = null
        }
    }
}
