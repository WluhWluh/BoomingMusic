package com.mardous.booming.separation.process.ipc

import com.mardous.booming.separation.SourceSeparationGpuFallbackLatch
import com.mardous.booming.separation.process.SOURCE_SEPARATION_EXECUTION_PROTOCOL_VERSION
import com.mardous.booming.separation.process.SourceSeparationExecutionHostEvent
import com.mardous.booming.separation.process.SourceSeparationExecutionHostEventPayload
import com.mardous.booming.separation.process.SourceSeparationExecutionProgress
import com.mardous.booming.separation.process.SourceSeparationForegroundDeferredReason
import com.mardous.booming.separation.process.SourceSeparationForegroundLeaseLifecycle
import com.mardous.booming.separation.process.SourceSeparationForegroundLeaseRecord
import com.mardous.booming.separation.process.SourceSeparationForegroundLeaseRequest
import com.mardous.booming.separation.process.SourceSeparationForegroundPlatformPolicy
import com.mardous.booming.separation.process.SourceSeparationForegroundServiceDiagnostics
import com.mardous.booming.separation.process.SourceSeparationForegroundTimeoutRecord
import kotlinx.serialization.json.Json
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSeparationExecutionIpcProtocolTest {
    @Test
    fun `connect and control payloads round trip strictly`() {
        val connect = SourceSeparationIpcConnectRequest(
            commandId = "connect-1",
            clientProcessName = "com.example",
            observerId = "observer-1",
        )
        val control = SourceSeparationIpcControlCommand(
            commandId = "control-1",
            runId = "run-1",
            processGeneration = 7L,
            controlSequence = 3L,
            action = SourceSeparationIpcControlAction.Cancel,
            hasPlaybackPositionUpdate = true,
            playbackPositionMs = 1_500L,
            playbackReadyWindowCount = 3,
        )
        val diagnostics = SourceSeparationIpcDiagnosticsCommand(
            commandId = "diagnostics-1",
            processGeneration = 7L,
        )
        val recycle = SourceSeparationIpcRecycleCommand(
            commandId = "recycle-1",
            processGeneration = 7L,
            reason = SourceSeparationIpcRecycleReason.ModelOrRuntimeKeyChanged,
            recycleToken = "recycle-token-0001",
        )

        assertEquals(
            connect,
            SourceSeparationExecutionIpcCodec.decodeConnectRequest(
                SourceSeparationExecutionIpcCodec.encodeConnectRequest(connect),
            ),
        )
        assertEquals(
            control,
            SourceSeparationExecutionIpcCodec.decodeControlCommand(
                SourceSeparationExecutionIpcCodec.encodeControlCommand(control),
            ),
        )
        assertEquals(
            diagnostics,
            SourceSeparationExecutionIpcCodec.decodeDiagnosticsCommand(
                SourceSeparationExecutionIpcCodec.encodeDiagnosticsCommand(diagnostics),
            ),
        )
        assertEquals(
            recycle,
            SourceSeparationExecutionIpcCodec.decodeRecycleCommand(
                SourceSeparationExecutionIpcCodec.encodeRecycleCommand(recycle),
            ),
        )
        assertEquals(14, SOURCE_SEPARATION_EXECUTION_PROTOCOL_VERSION)
        assertThrows(SourceSeparationIpcProtocolException::class.java) {
            SourceSeparationExecutionIpcCodec.decodeConnectRequest(
                """{"protocolVersion":12,"commandId":"connect","clientProcessName":"x","observerId":"observer-1"}""",
            )
        }
        assertThrows(SourceSeparationIpcProtocolException::class.java) {
            SourceSeparationExecutionIpcCodec.decodeConnectRequest(
                """{"protocolVersion":14,"commandId":"connect","clientProcessName":"x","observerId":"observer-1","extra":1}""",
            )
        }
    }

    @Test
    fun `foreground deferral round trips with a typed reason`() {
        val response = SourceSeparationIpcStartResponse(
            commandId = "start-deferred",
            status = SourceSeparationIpcStatus.Deferred,
            error = SourceSeparationIpcError(
                category = SourceSeparationIpcErrorCategory.ForegroundServiceUnavailable,
                type = "ForegroundDeferred",
                message = "Foreground execution is unavailable.",
            ),
            deferredReason = SourceSeparationForegroundDeferredReason.PromotionDenied,
        )

        assertEquals(
            response,
            SourceSeparationExecutionIpcCodec.decodeStartResponse(
                SourceSeparationExecutionIpcCodec.encodeStartResponse(response),
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            response.copy(deferredReason = null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            response.copy(status = SourceSeparationIpcStatus.Failed)
        }
    }

    @Test
    fun `foreground timeout diagnostics preserve platform identity`() {
        val timeout = SourceSeparationForegroundTimeoutRecord(
            startId = 9,
            foregroundServiceType = 0x2000,
            timestampElapsedRealtimeNanos = 300L,
        )
        val diagnostics = SourceSeparationForegroundServiceDiagnostics(
            lastStoppedLease = SourceSeparationForegroundLeaseRecord(
                request = SourceSeparationForegroundLeaseRequest(
                    leaseId = "foreground-timeout-0001",
                    runId = "run-timeout",
                    processGeneration = 7L,
                    displayName = "Timeout song",
                ),
                lifecycle = SourceSeparationForegroundLeaseLifecycle.Stopped,
                notificationId = 21_331,
                platformPolicy = SourceSeparationForegroundPlatformPolicy
                    .TimedMediaProcessing,
                startedAtElapsedRealtimeNanos = 100L,
                attachedAtElapsedRealtimeNanos = 200L,
                stoppedAtElapsedRealtimeNanos = timeout.timestampElapsedRealtimeNanos,
                stopReason = "media-processing-timeout",
                timeout = timeout,
            ),
        )
        val json = Json { encodeDefaults = true }

        assertEquals(
            diagnostics,
            json.decodeFromString(
                SourceSeparationForegroundServiceDiagnostics.serializer(),
                json.encodeToString(
                    SourceSeparationForegroundServiceDiagnostics.serializer(),
                    diagnostics,
                ),
            ),
        )
    }

    @Test
    fun `payload byte limit is enforced before parsing`() {
        val oversized = "x".repeat(SOURCE_SEPARATION_IPC_MAX_PAYLOAD_BYTES + 1)

        assertThrows(SourceSeparationIpcProtocolException::class.java) {
            SourceSeparationExecutionIpcCodec.requirePayloadWithinLimit(oversized)
        }
        SourceSeparationExecutionIpcCodec.requirePayloadWithinLimit(
            "x".repeat(SOURCE_SEPARATION_IPC_MAX_PAYLOAD_BYTES),
        )
    }

    @Test
    fun `GPU fallback event round trips with its typed latch`() {
        val event = event(
            sequence = 4L,
            label = "gpu-fallback",
            payload = SourceSeparationExecutionHostEventPayload.GpuFallbackLatched(
                SourceSeparationGpuFallbackLatch(
                    stage = "GpuInvocation",
                    reason = "Injected recoverable GPU failure.",
                )
            ),
        )

        assertEquals(
            event,
            SourceSeparationExecutionIpcCodec.decodeEvent(
                SourceSeparationExecutionIpcCodec.encodeEvent(event),
            ),
        )
    }

    @Test
    fun `event queue coalesces progress without reordering durable events`() {
        val queue = SourceSeparationRemoteEventQueue()
        queue.offer(event(1L, SourceSeparationExecutionHostEventPayload.Accepted::class.java.name))
        queue.offer(progressEvent(2L, 1))
        queue.offer(progressEvent(3L, 2))
        queue.offer(
            event(
                4L,
                "failed",
                SourceSeparationExecutionHostEventPayload.Failed(
                    errorType = "test",
                    message = "terminal",
                ),
            )
        )

        assertEquals(3, queue.snapshot().durableEventCount)
        assertFalse(queue.snapshot().hasPendingProgress)
        assertEquals(1L, requireNotNull(queue.take()).sequence)
        val progress = requireNotNull(queue.take())
        assertEquals(3L, progress.sequence)
        assertEquals(
            2,
            (progress.payload as SourceSeparationExecutionHostEventPayload.Progress)
                .progress.completedWindows,
        )
        assertEquals(4L, requireNotNull(queue.take()).sequence)
        queue.close()
        assertEquals(null, queue.take())
    }

    @Test
    fun `event queue rejects stale identities and sequence numbers`() {
        val queue = SourceSeparationRemoteEventQueue()
        queue.offer(progressEvent(1L, 1))

        assertThrows(IllegalArgumentException::class.java) {
            queue.offer(progressEvent(1L, 2))
        }
        assertThrows(IllegalArgumentException::class.java) {
            queue.offer(
                progressEvent(2L, 2).copy(processGeneration = 8L),
            )
        }
        assertTrue(queue.snapshot().hasPendingProgress)
    }

    @Test
    fun `event queue drains durable events and latest progress in order`() {
        val queue = SourceSeparationRemoteEventQueue()
        queue.offer(event(1L, SourceSeparationExecutionHostEventPayload.Accepted::class.java.name))
        queue.offer(progressEvent(2L, 1))
        queue.offer(progressEvent(3L, 2))

        assertEquals(listOf(1L, 3L), queue.drain().map { it.sequence })
        assertTrue(queue.drain().isEmpty())
    }

    @Test
    fun `reconnected observer applies snapshot baseline before newer events`() {
        val delivered = mutableListOf<Long>()
        val observer = SourceSeparationReconnectedEventObserver(
            runId = "run-1",
            processGeneration = 7L,
            baselineSequence = 3L,
            delivery = { delivered += it.sequence },
        )

        observer.offer(progressEvent(2L, 2))
        observer.offer(progressEvent(4L, 4))
        observer.offer(progressEvent(5L, 5))
        assertTrue(delivered.isEmpty())

        observer.activate()
        observer.offer(progressEvent(6L, 6))
        assertEquals(listOf(4L, 5L, 6L), delivered)
        assertThrows(IllegalArgumentException::class.java) {
            observer.offer(progressEvent(7L, 7).copy(runId = "stale"))
        }
    }

    @Test
    fun `binding event state drops old callbacks and resets reconnect identity`() {
        val state = SourceSeparationRemoteBindingEventState()
        state.activate(1L)
        assertTrue(state.acceptsCallback(1L))
        assertTrue(state.bufferReconnectEvent(1L, progressEvent(1L, 1)))
        assertEquals(listOf(1L), state.drainReconnectEvents(1L).map { it.sequence })

        state.deactivate(1L)
        state.activate(2L)
        assertFalse(state.acceptsCallback(1L))
        assertFalse(state.bufferReconnectEvent(1L, progressEvent(2L, 2)))
        val current = progressEvent(1L, 1).copy(
            runId = "run-2",
            processGeneration = 8L,
        )
        assertTrue(state.acceptsCallback(2L))
        assertTrue(state.bufferReconnectEvent(2L, current))
        assertEquals(listOf(current), state.drainReconnectEvents(2L))
        assertEquals(1, state.staleCallbackDropCount())

        state.close()
        assertFalse(state.acceptsCallback(2L))
        assertEquals(2, state.staleCallbackDropCount())
    }

    @Test
    fun `event sender detaches a failed observer and resumes with a replacement`() {
        val failed = CountDownLatch(1)
        val delivered = CountDownLatch(1)
        val received = mutableListOf<Long>()
        val sender = SourceSeparationRemoteEventSender(
            initialObserverId = "observer-1",
            initialDelivery = { throw IllegalStateException("observer died") },
            onDeliveryFailure = { observerId, _ ->
                assertEquals("observer-1", observerId)
                failed.countDown()
            },
        )

        sender.offer(progressEvent(1L, 1))
        assertTrue(failed.await(5L, TimeUnit.SECONDS))
        sender.offer(progressEvent(2L, 2))
        sender.attach("observer-2") { payload ->
            received += SourceSeparationExecutionIpcCodec.decodeEvent(payload).sequence
            delivered.countDown()
        }

        assertTrue(delivered.await(5L, TimeUnit.SECONDS))
        sender.closeAndAwait()
        assertEquals(listOf(2L), received)
    }

    @Test
    fun `event sender closes promptly while detached`() {
        val failed = CountDownLatch(1)
        val sender = SourceSeparationRemoteEventSender(
            initialObserverId = "observer-1",
            initialDelivery = { throw IllegalStateException("observer died") },
            onDeliveryFailure = { _, _ -> failed.countDown() },
        )

        sender.offer(progressEvent(1L, 1))
        assertTrue(failed.await(5L, TimeUnit.SECONDS))
        sender.offer(progressEvent(2L, 2))
        sender.closeAndAwait()
    }

    private fun progressEvent(
        sequence: Long,
        completed: Int,
    ) = SourceSeparationExecutionHostEvent(
        runId = "run-1",
        processGeneration = 7L,
        sequence = sequence,
        payload = SourceSeparationExecutionHostEventPayload.Progress(
            SourceSeparationExecutionProgress(
                completedWindows = completed,
                totalWindows = 10,
                stage = "test",
                sourceDecodeDiagnostics = null,
                completedWindowElapsedMs = null,
                scheduler = null,
            )
        ),
    )

    private fun event(
        sequence: Long,
        label: String,
        payload: SourceSeparationExecutionHostEventPayload =
            SourceSeparationExecutionHostEventPayload.Failed(label, null),
    ) = SourceSeparationExecutionHostEvent(
        runId = "run-1",
        processGeneration = 7L,
        sequence = sequence,
        payload = payload,
    )
}
