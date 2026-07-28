package com.mardous.booming.separation.process.ipc

import com.mardous.booming.separation.process.SourceSeparationExecutionHostEvent
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class SourceSeparationRemoteEventSender(
    initialObserverId: String,
    initialDelivery: (String) -> Unit,
    private val onDeliveryFailure: (String, Throwable) -> Unit,
) : AutoCloseable {
    private val queue = SourceSeparationRemoteEventQueue()
    private val observerLock = ReentrantLock()
    private val observerChanged = observerLock.newCondition()
    private var observer: Observer? = Observer(initialObserverId, initialDelivery)
    private var closed = false
    private val senderThread = Thread(::sendEvents, THREAD_NAME).apply {
        isDaemon = true
        start()
    }

    fun offer(event: SourceSeparationExecutionHostEvent) {
        SourceSeparationExecutionIpcCodec.encodeEvent(event)
        queue.offer(event)
    }

    fun attach(observerId: String, delivery: (String) -> Unit) {
        observerLock.withLock {
            check(!closed) { "IPC event sender is closed." }
            check(observer == null || observer?.id == observerId) {
                "A different IPC event observer is still attached."
            }
            observer = Observer(observerId, delivery)
            observerChanged.signalAll()
        }
    }

    fun detach(observerId: String): Boolean = observerLock.withLock {
        if (observer?.id != observerId) return@withLock false
        observer = null
        true
    }

    fun closeAndAwait() {
        close()
        senderThread.join(CLOSE_TIMEOUT_MS)
        check(!senderThread.isAlive) { "IPC event sender did not stop within its timeout." }
    }

    override fun close() {
        observerLock.withLock {
            if (closed) return
            closed = true
            observer = null
            observerChanged.signalAll()
        }
        queue.close()
    }

    private fun sendEvents() {
        while (true) {
            val target = awaitObserver() ?: return
            val event = queue.take() ?: return
            try {
                target.delivery(SourceSeparationExecutionIpcCodec.encodeEvent(event))
            } catch (error: Throwable) {
                val detached = observerLock.withLock {
                    if (observer !== target) return@withLock false
                    observer = null
                    true
                }
                if (detached) onDeliveryFailure(target.id, error)
            }
        }
    }

    private fun awaitObserver(): Observer? = observerLock.withLock {
        while (!closed && observer == null) observerChanged.await()
        observer
    }

    private class Observer(
        val id: String,
        val delivery: (String) -> Unit,
    )

    private companion object {
        const val THREAD_NAME = "SourceSeparationIpcEvents"
        const val CLOSE_TIMEOUT_MS = 10_000L
    }
}
