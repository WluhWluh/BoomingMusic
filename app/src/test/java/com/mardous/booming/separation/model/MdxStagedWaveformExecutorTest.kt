package com.mardous.booming.separation.model

import java.util.concurrent.CountDownLatch
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MdxStagedWaveformExecutorTest {
    @Test
    fun `range lookahead policy requires staged dual slots and non MP3 input`() {
        assertTrue(BlockingSession().canUseRangeLookahead(initialUsesMp3WindowDecode = false))
        assertEquals(
            false,
            BlockingSession(slotCount = 1)
                .canUseRangeLookahead(initialUsesMp3WindowDecode = false),
        )
        assertEquals(
            false,
            BlockingSession(staged = false)
                .canUseRangeLookahead(initialUsesMp3WindowDecode = false),
        )
        assertEquals(
            false,
            BlockingSession().canUseRangeLookahead(initialUsesMp3WindowDecode = true),
        )
    }

    @Test
    fun `lookahead preparation overlaps the current invocation`() {
        val session = BlockingSession()
        MdxStagedWaveformExecutor(session).use { executor ->
            val result = executor.invokeAndPrepareLookahead(slot = 0, shouldCancel = { false }) {
                assertTrue(session.invocationEntered.await(1, TimeUnit.SECONDS))
                session.prepareWaveform(waveform(2f), slot = 1)
                session.releaseInvocation.countDown()
                "next"
            }

            assertEquals("next", result.lookahead)
            assertArrayEquals(floatArrayOf(1f), result.output[0], 0f)
            assertEquals(listOf(1), session.preparedSlots)
            assertEquals(listOf(0), session.invokedSlots)
            assertEquals(listOf(0), session.readSlots)
        }
    }

    @Test
    fun `lookahead failure waits for invocation and remains primary`() {
        val session = BlockingSession()
        val failure = IllegalStateException("lookahead failed")
        MdxStagedWaveformExecutor(session).use { executor ->
            val observed = assertThrows(IllegalStateException::class.java) {
                executor.invokeAndPrepareLookahead<Unit>(slot = 0, shouldCancel = { false }) {
                    assertTrue(session.invocationEntered.await(1, TimeUnit.SECONDS))
                    session.releaseInvocation.countDown()
                    throw failure
                }
            }

            assertTrue(observed === failure)
            assertEquals(listOf(0), session.invokedSlots)
            assertEquals(listOf(0), session.readSlots)
        }
    }

    @Test
    fun `invocation cancellation is unwrapped from the worker future`() {
        val cancellation = CancellationException("canceled")
        val session = BlockingSession(invocationFailure = cancellation)
        MdxStagedWaveformExecutor(session).use { executor ->
            val observed = assertThrows(CancellationException::class.java) {
                executor.invokeAndPrepareLookahead<Unit>(slot = 0, shouldCancel = { true }) {
                    assertTrue(session.invocationEntered.await(1, TimeUnit.SECONDS))
                    session.releaseInvocation.countDown()
                    null
                }
            }

            assertTrue(observed === cancellation)
        }
    }

    @Test
    fun `caller interruption cannot release the executor before invocation drains`() {
        val session = BlockingSession()
        val completed = CountDownLatch(1)
        val interruptedAfterDrain = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>()
        val caller = Thread {
            try {
                MdxStagedWaveformExecutor(session).use { executor ->
                    executor.invokeAndPrepareLookahead<Unit>(slot = 0, shouldCancel = { false }) {
                        null
                    }
                }
                interruptedAfterDrain.set(Thread.currentThread().isInterrupted)
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                completed.countDown()
            }
        }
        caller.start()
        assertTrue(session.invocationEntered.await(1, TimeUnit.SECONDS))

        caller.interrupt()
        assertFalse(completed.await(100, TimeUnit.MILLISECONDS))
        session.releaseInvocation.countDown()
        assertTrue(completed.await(2, TimeUnit.SECONDS))

        failure.get()?.let { throw AssertionError("Caller failed", it) }
        assertTrue(interruptedAfterDrain.get())
    }

    private class BlockingSession(
        private val slotCount: Int = 2,
        private val staged: Boolean = true,
        private val invocationFailure: Throwable? = null,
    ) : MdxWaveformInferenceSession {
        val invocationEntered = CountDownLatch(1)
        val releaseInvocation = CountDownLatch(1)
        val preparedSlots = mutableListOf<Int>()
        val invokedSlots = mutableListOf<Int>()
        val readSlots = mutableListOf<Int>()
        override val waveformSlotCount = slotCount
        override val waveformDspImplementationId = "test"
        override val supportsStagedWaveformExecution = staged

        override fun prepareWaveform(
            waveform: Array<FloatArray>,
            slot: Int,
            shouldCancel: () -> Boolean,
        ) {
            preparedSlots += slot
        }

        override fun invokePreparedWaveform(slot: Int, shouldCancel: () -> Boolean) {
            invokedSlots += slot
            invocationEntered.countDown()
            check(releaseInvocation.await(2, TimeUnit.SECONDS))
            invocationFailure?.let { throw it }
        }

        override fun readPreparedWaveform(
            slot: Int,
            shouldCancel: () -> Boolean,
        ): Array<FloatArray> {
            readSlots += slot
            return waveform(1f)
        }

        override fun discardPreparedWaveform(slot: Int) = Unit
    }

    private companion object {
        fun waveform(value: Float) = arrayOf(floatArrayOf(value), floatArrayOf(value))
    }
}
