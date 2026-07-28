package com.mardous.booming.separation.process.ipc

import com.mardous.booming.separation.SourceSeparationGpuFallbackLatch
import com.mardous.booming.separation.process.SOURCE_SEPARATION_EXECUTION_PROTOCOL_VERSION
import com.mardous.booming.separation.process.SourceSeparationExecutionHostEvent
import com.mardous.booming.separation.process.SourceSeparationExecutionHostEventPayload
import com.mardous.booming.separation.process.SourceSeparationExecutionProgress
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
        assertEquals(8, SOURCE_SEPARATION_EXECUTION_PROTOCOL_VERSION)
        assertThrows(SourceSeparationIpcProtocolException::class.java) {
            SourceSeparationExecutionIpcCodec.decodeConnectRequest(
                """{"protocolVersion":7,"commandId":"connect","clientProcessName":"x"}""",
            )
        }
        assertThrows(SourceSeparationIpcProtocolException::class.java) {
            SourceSeparationExecutionIpcCodec.decodeConnectRequest(
                """{"protocolVersion":8,"commandId":"connect","clientProcessName":"x","extra":1}""",
            )
        }
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
