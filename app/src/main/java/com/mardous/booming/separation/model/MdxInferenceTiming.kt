package com.mardous.booming.separation.model

internal fun MdxInferenceSessionFactory.withMdxInferenceTiming(
    nanoTime: () -> Long = System::nanoTime,
): MdxInferenceSessionFactory =
    if (this is MdxTimedInferenceSessionFactory) this
    else MdxTimedInferenceSessionFactory(this, nanoTime)

internal class MdxTimedInferenceSessionFactory(
    private val delegate: MdxInferenceSessionFactory,
    private val nanoTime: () -> Long,
) : MdxInferenceSessionFactory {
    override val factoryId: String = delegate.factoryId
    override val backend: MdxInferenceBackend = delegate.backend

    override fun create(
        artifact: MdxModelArtifact,
        profile: MdxExecutionProfile,
        runtimeSettings: MdxRuntimeSettings,
    ): MdxInferenceSession {
        val started = nanoTime()
        val session = delegate.create(artifact, profile, runtimeSettings)
        val timedSession = TimedSession(
            delegate = session,
            modelSetupNanos = elapsedSince(started),
        )
        return if (session is MdxWaveformInferenceSession) {
            TimedWaveformSession(timedSession, session)
        } else {
            timedSession
        }
    }

    private inner class TimedSession(
        private val delegate: MdxInferenceSession,
        private val modelSetupNanos: Long,
    ) : MdxInferenceSession {
        private val lock = Any()
        private var invocationCount = 0L
        private var firstInferenceNanos: Long? = null
        private var reusedInferenceTotalNanos = 0L
        private var lastInferenceNanos: Long? = null

        override val diagnostics: MdxRuntimeDiagnostics
            get() {
                val timing = synchronized(lock) {
                    TimingSnapshot(
                        invocationCount = invocationCount,
                        firstInferenceNanos = firstInferenceNanos,
                        reusedInferenceTotalNanos = reusedInferenceTotalNanos,
                        lastInferenceNanos = lastInferenceNanos,
                    )
                }
                return delegate.diagnostics.copy(
                    modelSetupNanos = modelSetupNanos,
                    inferenceInvocationCount = timing.invocationCount,
                    firstInferenceNanos = timing.firstInferenceNanos,
                    reusedInferenceCount = (timing.invocationCount - 1L).coerceAtLeast(0L),
                    reusedInferenceTotalNanos = timing.reusedInferenceTotalNanos,
                    lastInferenceNanos = timing.lastInferenceNanos,
                )
            }

        override fun run(
            inputNchw: FloatArray,
            shouldCancel: () -> Boolean,
        ): FloatArray = measureInvocation {
            delegate.run(inputNchw, shouldCancel)
        }

        fun <T> measureInvocation(invocation: () -> T): T {
            val started = nanoTime()
            return try {
                invocation()
            } finally {
                val elapsed = elapsedSince(started)
                synchronized(lock) {
                    invocationCount += 1L
                    if (invocationCount == 1L) {
                        firstInferenceNanos = elapsed
                    } else {
                        reusedInferenceTotalNanos = runCatching {
                            Math.addExact(reusedInferenceTotalNanos, elapsed)
                        }.getOrDefault(Long.MAX_VALUE)
                    }
                    lastInferenceNanos = elapsed
                }
            }
        }

        override fun close() = delegate.close()
    }

    private class TimedWaveformSession(
        private val timedSession: TimedSession,
        private val waveformDelegate: MdxWaveformInferenceSession,
    ) : MdxInferenceSession by timedSession, MdxWaveformInferenceSession {
        override val waveformSlotCount: Int
            get() = waveformDelegate.waveformSlotCount
        override val waveformDspImplementationId: String
            get() = waveformDelegate.waveformDspImplementationId
        override val supportsStagedWaveformExecution: Boolean
            get() = waveformDelegate.supportsStagedWaveformExecution

        override fun runWaveform(
            waveform: Array<FloatArray>,
            shouldCancel: () -> Boolean,
        ): Array<FloatArray> = timedSession.measureInvocation {
            waveformDelegate.runWaveform(waveform, shouldCancel)
        }

        override fun prepareWaveform(
            waveform: Array<FloatArray>,
            slot: Int,
            shouldCancel: () -> Boolean,
        ) = waveformDelegate.prepareWaveform(waveform, slot, shouldCancel)

        override fun invokePreparedWaveform(
            slot: Int,
            shouldCancel: () -> Boolean,
        ) = timedSession.measureInvocation {
            waveformDelegate.invokePreparedWaveform(slot, shouldCancel)
        }

        override fun readPreparedWaveform(
            slot: Int,
            shouldCancel: () -> Boolean,
        ): Array<FloatArray> = waveformDelegate.readPreparedWaveform(slot, shouldCancel)

        override fun discardPreparedWaveform(slot: Int) =
            waveformDelegate.discardPreparedWaveform(slot)
    }

    private fun elapsedSince(started: Long): Long = (nanoTime() - started).coerceAtLeast(0L)

    private data class TimingSnapshot(
        val invocationCount: Long,
        val firstInferenceNanos: Long?,
        val reusedInferenceTotalNanos: Long,
        val lastInferenceNanos: Long?,
    )
}
