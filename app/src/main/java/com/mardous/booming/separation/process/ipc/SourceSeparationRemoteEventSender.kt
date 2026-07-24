package com.mardous.booming.separation.process.ipc

import com.mardous.booming.separation.process.SourceSeparationExecutionHostEvent
import java.util.concurrent.atomic.AtomicReference

internal class SourceSeparationRemoteEventSender(
    private val callback: ISourceSeparationExecutionCallback,
    private val onDeliveryFailure: (Throwable) -> Unit,
) : AutoCloseable {
    private val queue = SourceSeparationRemoteEventQueue()
    private val deliveryFailure = AtomicReference<Throwable?>(null)
    private val senderThread = Thread(::sendEvents, THREAD_NAME).apply {
        isDaemon = true
        start()
    }

    fun offer(event: SourceSeparationExecutionHostEvent) {
        deliveryFailure.get()?.let { throw SourceSeparationRemoteEventDeliveryException(it) }
        SourceSeparationExecutionIpcCodec.encodeEvent(event)
        queue.offer(event)
    }

    fun closeAndAwait() {
        close()
        senderThread.join(CLOSE_TIMEOUT_MS)
        check(!senderThread.isAlive) { "IPC event sender did not stop within its timeout." }
        deliveryFailure.get()?.let { throw SourceSeparationRemoteEventDeliveryException(it) }
    }

    override fun close() {
        queue.close()
    }

    private fun sendEvents() {
        try {
            while (true) {
                val event = queue.take() ?: break
                callback.onEvent(SourceSeparationExecutionIpcCodec.encodeEvent(event))
            }
        } catch (error: Throwable) {
            if (deliveryFailure.compareAndSet(null, error)) {
                onDeliveryFailure(error)
            }
            queue.close()
        }
    }

    private companion object {
        const val THREAD_NAME = "SourceSeparationIpcEvents"
        const val CLOSE_TIMEOUT_MS = 10_000L
    }
}

internal class SourceSeparationRemoteEventDeliveryException(
    cause: Throwable,
) : IllegalStateException("Unable to deliver a source-separation IPC event.", cause)
