package com.mardous.booming.separation.model

import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

internal data class MdxStagedWaveformResult<T>(
    val output: Array<FloatArray>,
    val lookahead: T?,
)

internal fun MdxWaveformInferenceSession.canUseRangeLookahead(
    initialUsesMp3WindowDecode: Boolean,
): Boolean = supportsStagedWaveformExecution &&
    waveformSlotCount >= 2 &&
    !initialUsesMp3WindowDecode

internal class MdxStagedWaveformExecutor(
    private val session: MdxWaveformInferenceSession,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "MdxWaveformInvoke").apply { isDaemon = true }
    },
) : AutoCloseable {
    fun <T> invokeAndPrepareLookahead(
        slot: Int,
        shouldCancel: () -> Boolean,
        prepareLookahead: () -> T?,
    ): MdxStagedWaveformResult<T> {
        val invocation = executor.submit<Array<FloatArray>> {
            session.invokePreparedWaveform(slot, shouldCancel)
            session.readPreparedWaveform(slot, shouldCancel)
        }
        var lookahead: T? = null
        var lookaheadFailure: Throwable? = null
        try {
            lookahead = prepareLookahead()
        } catch (error: Throwable) {
            lookaheadFailure = error
        }

        val output = try {
            invocation.getUninterruptibly()
        } catch (error: ExecutionException) {
            val invocationFailure = error.cause ?: error
            lookaheadFailure?.let(invocationFailure::addSuppressed)
            throw invocationFailure
        } catch (error: Throwable) {
            lookaheadFailure?.let(error::addSuppressed)
            throw error
        }
        lookaheadFailure?.let { throw it }
        return MdxStagedWaveformResult(output, lookahead)
    }

    private fun <T> Future<T>.getUninterruptibly(): T {
        var interrupted = false
        try {
            while (true) {
                try {
                    return get()
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    override fun close() {
        executor.shutdownNow()
        check(awaitTerminationUninterruptibly()) {
            "MDX staged waveform executor did not terminate."
        }
    }

    private fun awaitTerminationUninterruptibly(): Boolean {
        val deadline = System.nanoTime() +
            TimeUnit.SECONDS.toNanos(CLOSE_TIMEOUT_SECONDS)
        var interrupted = false
        try {
            while (true) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) return executor.isTerminated
                try {
                    return executor.awaitTermination(remaining, TimeUnit.NANOSECONDS)
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private companion object {
        const val CLOSE_TIMEOUT_SECONDS = 10L
    }
}
