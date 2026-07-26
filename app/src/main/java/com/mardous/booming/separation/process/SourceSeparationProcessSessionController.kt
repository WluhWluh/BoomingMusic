package com.mardous.booming.separation.process

import com.mardous.booming.separation.SourceSeparationPausedException
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheLostException
import com.mardous.booming.separation.model.MdxExecutionProfile
import com.mardous.booming.separation.model.MdxInferenceBackend
import com.mardous.booming.separation.model.MdxInferenceSession
import com.mardous.booming.separation.model.MdxInferenceSessionFactory
import com.mardous.booming.separation.model.MdxInferenceSessionLease
import com.mardous.booming.separation.model.MdxInferenceSessionProvider
import com.mardous.booming.separation.model.MdxModelArtifact
import com.mardous.booming.separation.model.MdxRuntimePrecision
import com.mardous.booming.separation.model.MdxRuntimeProfiles
import com.mardous.booming.separation.model.MdxRuntimeAbi
import com.mardous.booming.separation.model.MdxRuntimeSupportStatus
import com.mardous.booming.separation.model.MdxRuntimeSettings
import com.mardous.booming.separation.process.ipc.SourceSeparationRemoteEventDeliveryException
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CancellationException

internal enum class SourceSeparationProcessSessionOwnership {
    SingleUse,
    ResidentUntilProcessExit,
}

/** Serial authority for every native inference session owned by one remote process. */
internal class SourceSeparationProcessSessionController(
    private val factory: MdxInferenceSessionFactory,
    private val ownership: SourceSeparationProcessSessionOwnership,
    private val runtimeAbi: MdxRuntimeAbi? = null,
    private val sessionIdFactory: () -> String = { UUID.randomUUID().toString() },
) : MdxInferenceSessionProvider, AutoCloseable {
    private var state = SourceSeparationProcessSessionState.Empty
    private var residentSession: MdxInferenceSession? = null
    private var residentKey: SessionKey? = null
    private var sessionId: String? = null
    private var nativeSessionCreationCount = 0
    private var activeLeaseCount = 0
    private var invocationCount = 0L
    private var poisonReason: String? = null
    private var recycleReason: String? = null
    private var recycleToken: String? = null
    private var closeRequested = false
    private var activeExecution: ActiveExecution? = null

    @Synchronized
    fun beginExecution(runId: String) {
        require(runId.isNotBlank()) { "Process session execution run ID is empty." }
        check(activeExecution == null) { "A process session execution is already active." }
        check(!closeRequested) { "The process session controller is closing." }
        activeExecution = ActiveExecution(runId)
    }

    @Synchronized
    fun finishExecution(runId: String, failure: Throwable?) {
        val execution = activeExecution
            ?: throw IllegalStateException("No process session execution is active.")
        require(execution.runId == runId) {
            "Process session execution completion targets a stale run."
        }
        if (ownership == SourceSeparationProcessSessionOwnership.ResidentUntilProcessExit &&
            failure != null && execution.nativeStateTouched &&
            failure !is CancellationException &&
            failure !is SourceSeparationPausedException &&
            failure !is SourceSeparationRemoteEventDeliveryException &&
            failure !is SourceSeparationCacheLostException &&
            failure !is SourceSeparationProcessSessionRecycleRequiredException
        ) {
            poisonLocked("Execution failed after native session acquisition: " +
                (failure.message ?: failure::class.java.name))
        }
        activeExecution = null
    }

    @Synchronized
    override fun acquire(
        artifact: MdxModelArtifact,
        profile: MdxExecutionProfile,
        runtimeSettings: MdxRuntimeSettings,
    ): MdxInferenceSessionLease {
        check(!closeRequested) { "The process session controller is closing." }
        val execution = activeExecution
            ?: throw IllegalStateException("Native session acquisition has no active execution.")
        check(activeLeaseCount == 0) {
            "Only one native inference-session lease is allowed in the remote process."
        }
        profile.validateArtifact(artifact)
        if (ownership == SourceSeparationProcessSessionOwnership.ResidentUntilProcessExit) {
            requireResidentCpuProfile(profile)
        }
        val requestedKey = SessionKey.create(factory, artifact, profile, runtimeSettings)
        when (state) {
            SourceSeparationProcessSessionState.Empty -> Unit
            SourceSeparationProcessSessionState.Creating ->
                error("A native inference session is already being created.")
            SourceSeparationProcessSessionState.Resident -> {
                if (residentKey != requestedKey) {
                    recycleReason = RECYCLE_REASON_KEY_CHANGE
                    throw SourceSeparationProcessSessionRecycleRequiredException(
                        currentSessionKey = requireNotNull(residentKey).diagnosticIdentity,
                        requestedSessionKey = requestedKey.diagnosticIdentity,
                        reason = RECYCLE_REASON_KEY_CHANGE,
                    )
                }
                execution.nativeStateTouched = true
                activeLeaseCount = 1
                return leaseFor(requireNotNull(residentSession))
            }
            SourceSeparationProcessSessionState.Poisoned ->
                throw SourceSeparationProcessSessionPoisonedException(poisonReason)
            SourceSeparationProcessSessionState.Recycling ->
                throw SourceSeparationProcessSessionRecycleRequiredException(
                    currentSessionKey = residentKey?.diagnosticIdentity,
                    requestedSessionKey = requestedKey.diagnosticIdentity,
                    reason = recycleReason ?: RECYCLE_REASON_CONTROLLER_CLOSING,
                )
        }

        if (ownership == SourceSeparationProcessSessionOwnership.ResidentUntilProcessExit &&
            nativeSessionCreationCount != 0
        ) {
            poisonLocked("A resident-session generation attempted a second native creation.")
            throw SourceSeparationProcessSessionPoisonedException(poisonReason)
        }
        state = SourceSeparationProcessSessionState.Creating
        nativeSessionCreationCount += 1
        execution.nativeStateTouched = true
        val created = try {
            factory.create(artifact, profile, runtimeSettings)
        } catch (error: Throwable) {
            if (ownership == SourceSeparationProcessSessionOwnership.ResidentUntilProcessExit) {
                poisonLocked("Native session creation failed: " +
                    (error.message ?: error::class.java.name))
            } else {
                state = SourceSeparationProcessSessionState.Empty
            }
            throw error
        }
        residentSession = TrackingSession(created)
        residentKey = requestedKey
        sessionId = sessionIdFactory().also { id ->
            require(id.isNotBlank()) { "Process session ID factory returned an empty ID." }
        }
        state = SourceSeparationProcessSessionState.Resident
        activeLeaseCount = 1
        return leaseFor(requireNotNull(residentSession))
    }

    @Synchronized
    fun diagnostics(): SourceSeparationProcessSessionDiagnostics =
        SourceSeparationProcessSessionDiagnostics(
            state = state,
            sessionId = sessionId,
            sessionKey = residentKey?.diagnosticIdentity,
            nativeSessionCreationCount = nativeSessionCreationCount,
            activeLeaseCount = activeLeaseCount,
            invocationCount = invocationCount,
            poisoned = poisonReason != null,
            poisonReason = poisonReason,
            recycleReason = recycleReason,
            recycleToken = recycleToken,
        )

    @Synchronized
    fun markRecycling(reason: String, token: String? = null) {
        require(reason.isNotBlank()) { "Process session recycle reason is empty." }
        require(token == null || token.isNotBlank()) { "Process session recycle token is empty." }
        check(activeLeaseCount == 0) { "Cannot recycle a leased native session." }
        check(activeExecution == null) { "Cannot recycle during an active execution." }
        recycleReason = reason
        recycleToken = token
        state = SourceSeparationProcessSessionState.Recycling
    }

    @Synchronized
    override fun close() {
        if (closeRequested) return
        closeRequested = true
        recycleReason = recycleReason ?: RECYCLE_REASON_PROCESS_TEARDOWN
        state = SourceSeparationProcessSessionState.Recycling
        if (activeLeaseCount == 0) closeResidentLocked()
    }

    private fun leaseFor(session: MdxInferenceSession) =
        MdxInferenceSessionLease(session, ::releaseLease)

    @Synchronized
    private fun releaseLease() {
        check(activeLeaseCount == 1) { "Process session lease count underflow." }
        activeLeaseCount = 0
        if (ownership == SourceSeparationProcessSessionOwnership.SingleUse || closeRequested) {
            val cleanupFailure = closeResidentLocked()
            if (!closeRequested && state != SourceSeparationProcessSessionState.Poisoned) {
                state = SourceSeparationProcessSessionState.Empty
                residentKey = null
                sessionId = null
            }
            if (cleanupFailure != null &&
                ownership == SourceSeparationProcessSessionOwnership.SingleUse
            ) {
                throw cleanupFailure
            }
        }
    }

    @Synchronized
    private fun runTracked(
        delegate: MdxInferenceSession,
        inputNchw: FloatArray,
        shouldCancel: () -> Boolean,
    ): FloatArray {
        check(activeLeaseCount == 1 && residentSession != null) {
            "Native inference invocation has no active process lease."
        }
        invocationCount += 1L
        return try {
            delegate.run(inputNchw, shouldCancel).also { output ->
                if (output.any { !it.isFinite() }) {
                    throw IllegalStateException("Native inference returned non-finite output.")
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (ownership == SourceSeparationProcessSessionOwnership.ResidentUntilProcessExit) {
                poisonLocked("Native inference failed: " +
                    (error.message ?: error::class.java.name))
            }
            throw error
        }
    }

    private fun poisonLocked(reason: String) {
        poisonReason = poisonReason ?: reason
        state = SourceSeparationProcessSessionState.Poisoned
    }

    private fun requireResidentCpuProfile(profile: MdxExecutionProfile) {
        check(factory.backend == MdxInferenceBackend.LiteRtAuto ||
            factory.backend == MdxInferenceBackend.LiteRtCpu
        ) {
            "Resident process validation requires Auto or CPU inference."
        }
        val requiredAbi = checkNotNull(runtimeAbi) {
            "Resident process validation requires its exact runtime ABI."
        }
        val cpuRecord = profile.runtimeCompatibility.singleOrNull { record ->
            record.abi == requiredAbi &&
                record.backend == MdxInferenceBackend.LiteRtCpu &&
                record.profileId == MdxRuntimeProfiles.CPU_DEFAULT_FP32 &&
                record.precision == MdxRuntimePrecision.Fp32
        }
        check(cpuRecord?.status == MdxRuntimeSupportStatus.KnownGood) {
            "Resident process validation requires its exact KnownGood CPU record."
        }
        check(profile.runtimeCompatibility.none { record ->
            record.abi == requiredAbi &&
                record.backend == MdxInferenceBackend.LiteRtGpu &&
                record.status != MdxRuntimeSupportStatus.Unsupported &&
                record.status != MdxRuntimeSupportStatus.Rejected
        }) {
            "Resident process validation cannot accept a GPU-capable profile."
        }
    }

    private fun closeResidentLocked(): Throwable? {
        val session = residentSession
        residentSession = null
        return try {
            session?.close()
            null
        } catch (error: Throwable) {
            poisonReason = poisonReason ?: "Native session cleanup failed: " +
                (error.message ?: error::class.java.name)
            if (!closeRequested) {
                state = SourceSeparationProcessSessionState.Poisoned
            }
            error
        }
    }

    private inner class TrackingSession(
        private val delegate: MdxInferenceSession,
    ) : MdxInferenceSession {
        override val diagnostics
            get() = delegate.diagnostics

        override fun run(
            inputNchw: FloatArray,
            shouldCancel: () -> Boolean,
        ): FloatArray = runTracked(delegate, inputNchw, shouldCancel)

        override fun close() = delegate.close()
    }

    private data class ActiveExecution(
        val runId: String,
        var nativeStateTouched: Boolean = false,
    )

    private data class SessionKey(
        val factoryId: String,
        val requestedBackend: MdxInferenceBackend,
        val effectiveBackend: MdxInferenceBackend,
        val backendProfileId: String,
        val precision: MdxRuntimePrecision,
        val artifactSha256: String,
        val executionProfileIdentity: String,
        val runtimeSettings: MdxRuntimeSettings,
    ) {
        val diagnosticIdentity: String = sha256(buildString {
            append(factoryId)
            append('|').append(requestedBackend.name)
            append('|').append(effectiveBackend.name)
            append('|').append(backendProfileId)
            append('|').append(precision.name)
            append('|').append(artifactSha256)
            append('|').append(executionProfileIdentity)
            append('|').append(runtimeSettings.cpuThreads)
            append('|').append(runtimeSettings.useXnnpack)
        })

        companion object {
            fun create(
                factory: MdxInferenceSessionFactory,
                artifact: MdxModelArtifact,
                profile: MdxExecutionProfile,
                runtimeSettings: MdxRuntimeSettings,
            ) = SessionKey(
                factoryId = factory.factoryId,
                requestedBackend = factory.backend,
                effectiveBackend = if (factory.backend == MdxInferenceBackend.LiteRtAuto) {
                    MdxInferenceBackend.LiteRtCpu
                } else {
                    factory.backend
                },
                backendProfileId = MdxRuntimeProfiles.CPU_DEFAULT_FP32,
                precision = MdxRuntimePrecision.Fp32,
                artifactSha256 = artifact.sha256.lowercase(Locale.US),
                executionProfileIdentity = buildString {
                    append(profile.sessionIdentity)
                    append('|').append(profile.profileId)
                    append('|').append(profile.expectedSha256)
                    append('|').append(profile.dspConfig.sampleRate)
                    append('|').append(profile.dspConfig.nFft)
                    append('|').append(profile.dspConfig.hopLength)
                    append('|').append(profile.dspConfig.dimF)
                    append('|').append(profile.dspConfig.dimTPower)
                    append('|').append(profile.modelOutputScale)
                    append('|').append(profile.modelOutputStem.name)
                },
                runtimeSettings = runtimeSettings,
            )
        }
    }

    private companion object {
        const val RECYCLE_REASON_KEY_CHANGE = "session-key-change"
        const val RECYCLE_REASON_CONTROLLER_CLOSING = "session-controller-closing"
        const val RECYCLE_REASON_PROCESS_TEARDOWN = "process-teardown"

        fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(Locale.US, byte.toInt() and 0xff) }
    }
}

internal class SourceSeparationProcessSessionRecycleRequiredException(
    val currentSessionKey: String?,
    val requestedSessionKey: String,
    val reason: String,
) : IllegalStateException("The inference process must recycle before $reason.")

internal class SourceSeparationProcessSessionPoisonedException(
    reason: String?,
) : IllegalStateException(
    "The inference process native session is poisoned: ${reason ?: "unknown failure"}",
)
