package com.mardous.booming.separation.model.litert

import com.mardous.booming.separation.model.MdxInferenceBackend
import com.mardous.booming.separation.model.MdxInferenceSession
import com.mardous.booming.separation.model.MdxRuntimeDiagnostics
import com.mardous.booming.separation.model.MdxWaveformInferenceSession
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MdxLiteRtRemoteFaultInjectionTest {
    @Test
    fun `remote failpoint names and fallback stages form a stable contract`() {
        MdxLiteRtRemoteFailpoint.entries.forEach { failpoint ->
            assertEquals(
                failpoint,
                MdxLiteRtRemoteFailpoint.parse(failpoint.argumentValue),
            )
        }
        assertEquals(
            MdxLiteRtAutoFailureStage.GpuCleanup,
            MdxLiteRtRemoteFailpoint.Cleanup.expectedFallbackStage,
        )
        assertTrue(MdxLiteRtRemoteFailpoint.Cleanup.requiresProcessRecycle)
        assertFalse(MdxLiteRtRemoteFailpoint.Invocation.requiresProcessRecycle)
        assertThrows(IllegalStateException::class.java) {
            MdxLiteRtRemoteFailpoint.parse("unknown")
        }
    }

    @Test
    fun `remote fault control rejects probe and identity hazards`() {
        val valid = MdxLiteRtRemoteFaultControl(
            schemaVersion = MdxLiteRtRemoteFaultControl.SCHEMA_VERSION,
            token = "phase5-cleanup-1",
            failpoint = MdxLiteRtRemoteFailpoint.Cleanup.argumentValue,
            failureInvocationCount = 2,
            expiresAtElapsedRealtimeMs = 1L,
        )
        assertEquals(2, valid.failureInvocationCount)

        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(failpoint = MdxLiteRtRemoteFailpoint.None.argumentValue)
        }
        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(failureInvocationCount = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(token = "contains spaces")
        }
        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(schemaVersion = 0)
        }
    }

    @Test
    fun `tracking session forwards managed waveform capability and output`() {
        val output = arrayOf(floatArrayOf(2f, 4f), floatArrayOf(6f, 8f))
        val delegate = FakeWaveformSession(output)
        val tracker = FakeTracker(failureInvocationCount = Int.MAX_VALUE)
        val session = MdxLiteRtRemoteFaultTrackingSession(delegate, tracker)
        val input = arrayOf(floatArrayOf(1f, 2f), floatArrayOf(3f, 4f))

        val observed = session.runWaveform(input)

        assertSame(output, observed)
        assertSame(input, delegate.waveformInputs.single())
        assertEquals(1, session.waveformSlotCount)
        assertEquals("fake-managed-waveform", session.waveformDspImplementationId)
        assertEquals(listOf(MdxInferenceBackend.LiteRtGpu), tracker.createdBackends)
        assertEquals(listOf(MdxInferenceBackend.LiteRtGpu), tracker.invokedBackends)
        session.close()
        assertEquals(listOf(MdxInferenceBackend.LiteRtGpu), tracker.closedBackends)
    }

    @Test
    fun `managed waveform invocation participates in remote GPU failpoint count`() {
        val delegate = FakeWaveformSession(
            arrayOf(floatArrayOf(2f), floatArrayOf(4f)),
        )
        val tracker = FakeTracker(
            failureInvocationCount = 2,
            failpoint = MdxLiteRtRemoteFailpoint.Invocation,
        )
        val session = MdxLiteRtRemoteFaultTrackingSession(delegate, tracker)

        assertArrayEquals(floatArrayOf(1f), session.run(floatArrayOf(1f)), 0f)
        val error = assertThrows(MdxLiteRtBackendException::class.java) {
            session.runWaveform(arrayOf(floatArrayOf(1f), floatArrayOf(1f)))
        }

        assertEquals(MdxLiteRtFailureStage.Invocation, error.stage)
        assertEquals(1, delegate.waveformInputs.size)
        assertEquals(
            listOf(MdxInferenceBackend.LiteRtGpu, MdxInferenceBackend.LiteRtGpu),
            tracker.invokedBackends,
        )
        assertEquals(listOf("invocation"), tracker.injectedStages)
        session.close()
    }

    @Test
    fun `tracking session rejects staged waveform without bypassing fault accounting`() {
        val delegate = FakeWaveformSession(
            arrayOf(floatArrayOf(2f), floatArrayOf(4f)),
        )
        val tracker = FakeTracker(failureInvocationCount = Int.MAX_VALUE)
        val session = MdxLiteRtRemoteFaultTrackingSession(delegate, tracker)
        val waveform = arrayOf(floatArrayOf(1f), floatArrayOf(1f))

        assertTrue(delegate.supportsStagedWaveformExecution)
        assertFalse(session.supportsStagedWaveformExecution)
        listOf<() -> Unit>(
            { session.prepareWaveform(waveform, slot = 0) },
            { session.invokePreparedWaveform(slot = 0) },
            { session.readPreparedWaveform(slot = 0) },
            { session.discardPreparedWaveform(slot = 0) },
        ).forEach { stagedCall ->
            val error = assertThrows(UnsupportedOperationException::class.java) { stagedCall() }
            assertEquals(
                "Remote fault injection requires transactional runWaveform for invocation accounting.",
                error.message,
            )
        }
        assertEquals(0, delegate.stagedPrepareCount)
        assertEquals(0, delegate.stagedInvocationCount)
        assertEquals(0, delegate.stagedReadCount)
        assertEquals(0, delegate.stagedDiscardCount)
        assertTrue(tracker.invokedBackends.isEmpty())
        assertTrue(tracker.injectedStages.isEmpty())

        session.close()
    }

    private class FakeTracker(
        private val failureInvocationCount: Int,
        private val failpoint: MdxLiteRtRemoteFailpoint = MdxLiteRtRemoteFailpoint.None,
    ) : MdxLiteRtRemoteFaultSessionTracker {
        val createdBackends = mutableListOf<MdxInferenceBackend>()
        val closedBackends = mutableListOf<MdxInferenceBackend>()
        val invokedBackends = mutableListOf<MdxInferenceBackend>()
        val injectedStages = mutableListOf<String>()

        override fun created(backend: MdxInferenceBackend) {
            createdBackends += backend
        }

        override fun closed(backend: MdxInferenceBackend) {
            closedBackends += backend
        }

        override fun invoked(backend: MdxInferenceBackend): Int {
            invokedBackends += backend
            return invokedBackends.size
        }

        override fun injected(stage: String) {
            injectedStages += stage
        }

        override fun shouldInjectAt(invocationCount: Int): Boolean =
            invocationCount == failureInvocationCount

        override fun selectedFailpoint(): MdxLiteRtRemoteFailpoint = failpoint
    }

    private class FakeWaveformSession(
        private val waveformOutput: Array<FloatArray>,
    ) : MdxInferenceSession, MdxWaveformInferenceSession {
        override val diagnostics = MdxRuntimeDiagnostics(
            runtimeName = "Fake GPU",
            backend = MdxInferenceBackend.LiteRtGpu,
            cpuThreads = null,
            detail = "test",
        )
        override val waveformSlotCount = 1
        override val waveformDspImplementationId = "fake-managed-waveform"
        override val supportsStagedWaveformExecution = true
        val waveformInputs = mutableListOf<Array<FloatArray>>()
        private var prepared: Array<FloatArray>? = null
        var stagedPrepareCount = 0
        var stagedInvocationCount = 0
        var stagedReadCount = 0
        var stagedDiscardCount = 0

        override fun run(
            inputNchw: FloatArray,
            shouldCancel: () -> Boolean,
        ): FloatArray = inputNchw.copyOf()

        override fun runWaveform(
            waveform: Array<FloatArray>,
            shouldCancel: () -> Boolean,
        ): Array<FloatArray> {
            waveformInputs += waveform
            return waveformOutput
        }

        override fun prepareWaveform(
            waveform: Array<FloatArray>,
            slot: Int,
            shouldCancel: () -> Boolean,
        ) {
            stagedPrepareCount += 1
            check(slot == 0)
            prepared = waveform
        }

        override fun invokePreparedWaveform(slot: Int, shouldCancel: () -> Boolean) {
            stagedInvocationCount += 1
            check(slot == 0 && prepared != null)
        }

        override fun readPreparedWaveform(
            slot: Int,
            shouldCancel: () -> Boolean,
        ): Array<FloatArray> {
            stagedReadCount += 1
            check(slot == 0 && prepared != null)
            return waveformOutput
        }

        override fun discardPreparedWaveform(slot: Int) {
            stagedDiscardCount += 1
            check(slot == 0)
            prepared = null
        }

        override fun close() = Unit
    }
}
