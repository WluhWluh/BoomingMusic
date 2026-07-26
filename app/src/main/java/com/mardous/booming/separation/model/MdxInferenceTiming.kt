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
        return TimedSession(
            delegate = session,
            modelSetupNanos = elapsedSince(started),
        )
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
        ): FloatArray {
            val started = nanoTime()
            return try {
                delegate.run(inputNchw, shouldCancel)
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

    private fun elapsedSince(started: Long): Long = (nanoTime() - started).coerceAtLeast(0L)

    private data class TimingSnapshot(
        val invocationCount: Long,
        val firstInferenceNanos: Long?,
        val reusedInferenceTotalNanos: Long,
        val lastInferenceNanos: Long?,
    )
}
