package com.mardous.booming.separation.process.ipc

import com.mardous.booming.separation.process.SourceSeparationExecutionHostEvent
import com.mardous.booming.separation.process.SourceSeparationExecutionHostEventPayload
import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Keeps durable events ordered while allowing one pending progress event to be replaced.
 */
internal class SourceSeparationRemoteEventQueue(
    private val maximumDurableEvents: Int = DEFAULT_MAXIMUM_DURABLE_EVENTS,
) {
    private val lock = ReentrantLock()
    private val eventAvailable = lock.newCondition()
    private val durableEvents = ArrayDeque<SourceSeparationExecutionHostEvent>()
    private var pendingProgress: SourceSeparationExecutionHostEvent? = null
    private var closed = false
    private var runId: String? = null
    private var processGeneration: Long? = null
    private var highestSequence = 0L

    init {
        require(maximumDurableEvents > 0) { "IPC durable-event limit is invalid." }
    }

    fun offer(event: SourceSeparationExecutionHostEvent) {
        lock.withLock {
            check(!closed) { "IPC event queue is closed." }
            val expectedRunId = runId
            val expectedGeneration = processGeneration
            if (expectedRunId == null) {
                runId = event.runId
                processGeneration = event.processGeneration
            } else {
                require(event.runId == expectedRunId &&
                    event.processGeneration == expectedGeneration
                ) {
                    "IPC event targets a stale run or generation."
                }
            }
            require(event.sequence > highestSequence) { "IPC event sequence is stale." }
            highestSequence = event.sequence
            if (event.payload is SourceSeparationExecutionHostEventPayload.Progress) {
                pendingProgress = event
            } else {
                pendingProgress?.let(::enqueueDurable)
                pendingProgress = null
                enqueueDurable(event)
            }
            eventAvailable.signalAll()
        }
    }

    fun take(): SourceSeparationExecutionHostEvent? {
        return lock.withLock {
            while (!closed && durableEvents.isEmpty() && pendingProgress == null) {
                eventAvailable.await()
            }
            if (durableEvents.isNotEmpty()) return@withLock durableEvents.removeFirst()
            val progress = pendingProgress
            pendingProgress = null
            progress
        }
    }

    fun close() {
        lock.withLock {
            if (closed) return
            closed = true
            pendingProgress = null
            eventAvailable.signalAll()
        }
    }

    fun drain(): List<SourceSeparationExecutionHostEvent> = lock.withLock {
        buildList {
            while (durableEvents.isNotEmpty()) add(durableEvents.removeFirst())
            pendingProgress?.let(::add)
        }.also {
            pendingProgress = null
        }
    }

    fun snapshot(): SourceSeparationRemoteEventQueueSnapshot = lock.withLock {
        SourceSeparationRemoteEventQueueSnapshot(
            durableEventCount = durableEvents.size,
            hasPendingProgress = pendingProgress != null,
            highestSequence = highestSequence,
            closed = closed,
        )
    }

    private fun enqueueDurable(event: SourceSeparationExecutionHostEvent) {
        check(durableEvents.size < maximumDurableEvents) {
            "IPC durable-event queue exceeded its fixed limit."
        }
        durableEvents.addLast(event)
    }

    private companion object {
        const val DEFAULT_MAXIMUM_DURABLE_EVENTS = 512
    }
}

internal data class SourceSeparationRemoteEventQueueSnapshot(
    val durableEventCount: Int,
    val hasPendingProgress: Boolean,
    val highestSequence: Long,
    val closed: Boolean,
)
