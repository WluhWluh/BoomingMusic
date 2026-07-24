package com.mardous.booming.separation.process.ipc

import com.mardous.booming.separation.process.SOURCE_SEPARATION_EXECUTION_PROTOCOL_VERSION
import com.mardous.booming.separation.process.SourceSeparationExecutionCompletion
import com.mardous.booming.separation.process.SourceSeparationExecutionDescriptor
import com.mardous.booming.separation.process.SourceSeparationExecutionHostDiagnostics
import com.mardous.booming.separation.process.SourceSeparationExecutionHostEvent
import com.mardous.booming.separation.process.SourceSeparationExecutionHostSnapshot
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal const val SOURCE_SEPARATION_IPC_MAX_PAYLOAD_BYTES = 512 * 1024

@Serializable
internal data class SourceSeparationIpcConnectRequest(
    val protocolVersion: Int = SOURCE_SEPARATION_EXECUTION_PROTOCOL_VERSION,
    val commandId: String,
    val clientProcessName: String,
) {
    init {
        requireProtocolVersion(protocolVersion)
        requireCommandId(commandId)
        require(clientProcessName.isNotBlank()) { "IPC client process name is empty." }
    }
}

@Serializable
internal data class SourceSeparationIpcConnectResponse(
    val protocolVersion: Int = SOURCE_SEPARATION_EXECUTION_PROTOCOL_VERSION,
    val commandId: String,
    val processGeneration: Long,
    val processName: String,
    val pid: Int,
    val idlePssBytes: Long,
) {
    init {
        requireProtocolVersion(protocolVersion)
        requireCommandId(commandId)
        require(processGeneration > 0L) { "IPC process generation is invalid." }
        require(processName.isNotBlank()) { "IPC process name is empty." }
        require(pid > 0) { "IPC process ID is invalid." }
        require(idlePssBytes >= 0L) { "IPC idle PSS is invalid." }
    }
}

@Serializable
internal data class SourceSeparationIpcStartCommand(
    val protocolVersion: Int = SOURCE_SEPARATION_EXECUTION_PROTOCOL_VERSION,
    val commandId: String,
    val descriptor: SourceSeparationExecutionDescriptor,
) {
    init {
        requireProtocolVersion(protocolVersion)
        requireCommandId(commandId)
        require(descriptor.protocolVersion == protocolVersion) {
            "IPC start descriptor protocol version is inconsistent."
        }
    }
}

@Serializable
internal data class SourceSeparationIpcStartResponse(
    val protocolVersion: Int = SOURCE_SEPARATION_EXECUTION_PROTOCOL_VERSION,
    val commandId: String,
    val status: SourceSeparationIpcStatus,
    val completion: SourceSeparationExecutionCompletion? = null,
    val diagnostics: SourceSeparationExecutionHostDiagnostics? = null,
    val snapshot: SourceSeparationExecutionHostSnapshot? = null,
    val error: SourceSeparationIpcError? = null,
) {
    init {
        requireProtocolVersion(protocolVersion)
        requireCommandId(commandId)
        if (status == SourceSeparationIpcStatus.Completed) {
            require(completion != null && diagnostics != null && error == null) {
                "Completed IPC start response is incomplete."
            }
        }
    }
}

@Serializable
internal data class SourceSeparationIpcControlCommand(
    val protocolVersion: Int = SOURCE_SEPARATION_EXECUTION_PROTOCOL_VERSION,
    val commandId: String,
    val runId: String,
    val processGeneration: Long,
    val controlSequence: Long,
    val action: SourceSeparationIpcControlAction,
) {
    init {
        requireProtocolVersion(protocolVersion)
        requireCommandId(commandId)
        require(runId.isNotBlank()) { "IPC control run ID is empty." }
        require(processGeneration > 0L) { "IPC control generation is invalid." }
        require(controlSequence > 0L) { "IPC control sequence is invalid." }
    }
}

@Serializable
internal enum class SourceSeparationIpcControlAction {
    Pause,
    Cancel,
}

@Serializable
internal data class SourceSeparationIpcRunCommand(
    val protocolVersion: Int = SOURCE_SEPARATION_EXECUTION_PROTOCOL_VERSION,
    val commandId: String,
    val runId: String,
    val processGeneration: Long,
) {
    init {
        requireProtocolVersion(protocolVersion)
        requireCommandId(commandId)
        require(runId.isNotBlank()) { "IPC run ID is empty." }
        require(processGeneration > 0L) { "IPC run generation is invalid." }
    }
}

@Serializable
internal data class SourceSeparationIpcOperationResponse(
    val protocolVersion: Int = SOURCE_SEPARATION_EXECUTION_PROTOCOL_VERSION,
    val commandId: String,
    val status: SourceSeparationIpcStatus,
    val snapshot: SourceSeparationExecutionHostSnapshot? = null,
    val error: SourceSeparationIpcError? = null,
) {
    init {
        requireProtocolVersion(protocolVersion)
        requireCommandId(commandId)
    }
}

@Serializable
internal enum class SourceSeparationIpcStatus {
    Completed,
    Applied,
    AlreadyApplied,
    Duplicate,
    Busy,
    NoActiveRun,
    StaleRun,
    StaleGeneration,
    StaleControl,
    RunActive,
    Terminal,
    Rejected,
    Failed,
}

@Serializable
internal data class SourceSeparationIpcError(
    val category: SourceSeparationIpcErrorCategory,
    val type: String,
    val message: String?,
) {
    init {
        require(type.isNotBlank()) { "IPC error type is empty." }
    }
}

@Serializable
internal enum class SourceSeparationIpcErrorCategory {
    MalformedRequest,
    IdentityMismatch,
    ModelUnavailable,
    SourceUnavailable,
    CacheUnavailable,
    RuntimeFailure,
    HostDied,
    Timeout,
    Internal,
}

internal object SourceSeparationExecutionIpcCodec {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        classDiscriminator = "eventType"
    }

    fun encodeConnectRequest(value: SourceSeparationIpcConnectRequest): String =
        encode(SourceSeparationIpcConnectRequest.serializer(), value)

    fun decodeConnectRequest(payload: String): SourceSeparationIpcConnectRequest =
        decode(SourceSeparationIpcConnectRequest.serializer(), payload)

    fun encodeConnectResponse(value: SourceSeparationIpcConnectResponse): String =
        encode(SourceSeparationIpcConnectResponse.serializer(), value)

    fun decodeConnectResponse(payload: String): SourceSeparationIpcConnectResponse =
        decode(SourceSeparationIpcConnectResponse.serializer(), payload)

    fun encodeStartCommand(value: SourceSeparationIpcStartCommand): String =
        encode(SourceSeparationIpcStartCommand.serializer(), value)

    fun decodeStartCommand(payload: String): SourceSeparationIpcStartCommand =
        decode(SourceSeparationIpcStartCommand.serializer(), payload)

    fun encodeStartResponse(value: SourceSeparationIpcStartResponse): String =
        encode(SourceSeparationIpcStartResponse.serializer(), value)

    fun decodeStartResponse(payload: String): SourceSeparationIpcStartResponse =
        decode(SourceSeparationIpcStartResponse.serializer(), payload)

    fun encodeControlCommand(value: SourceSeparationIpcControlCommand): String =
        encode(SourceSeparationIpcControlCommand.serializer(), value)

    fun decodeControlCommand(payload: String): SourceSeparationIpcControlCommand =
        decode(SourceSeparationIpcControlCommand.serializer(), payload)

    fun encodeRunCommand(value: SourceSeparationIpcRunCommand): String =
        encode(SourceSeparationIpcRunCommand.serializer(), value)

    fun decodeRunCommand(payload: String): SourceSeparationIpcRunCommand =
        decode(SourceSeparationIpcRunCommand.serializer(), payload)

    fun encodeOperationResponse(value: SourceSeparationIpcOperationResponse): String =
        encode(SourceSeparationIpcOperationResponse.serializer(), value)

    fun decodeOperationResponse(payload: String): SourceSeparationIpcOperationResponse =
        decode(SourceSeparationIpcOperationResponse.serializer(), payload)

    fun encodeEvent(value: SourceSeparationExecutionHostEvent): String =
        encode(SourceSeparationExecutionHostEvent.serializer(), value)

    fun decodeEvent(payload: String): SourceSeparationExecutionHostEvent =
        decode(SourceSeparationExecutionHostEvent.serializer(), payload)

    fun requirePayloadWithinLimit(payload: String) {
        val byteSize = payload.toByteArray(Charsets.UTF_8).size
        if (byteSize > SOURCE_SEPARATION_IPC_MAX_PAYLOAD_BYTES) {
            throw SourceSeparationIpcProtocolException(
                "IPC payload exceeds $SOURCE_SEPARATION_IPC_MAX_PAYLOAD_BYTES bytes.",
            )
        }
    }

    private fun <T> encode(
        serializer: SerializationStrategy<T>,
        value: T,
    ): String {
        val payload = try {
            json.encodeToString(serializer, value)
        } catch (error: Throwable) {
            throw SourceSeparationIpcProtocolException("Unable to encode IPC payload.", error)
        }
        requirePayloadWithinLimit(payload)
        return payload
    }

    private fun <T> decode(
        deserializer: DeserializationStrategy<T>,
        payload: String,
    ): T {
        requirePayloadWithinLimit(payload)
        return try {
            json.decodeFromString(deserializer, payload)
        } catch (error: Throwable) {
            throw SourceSeparationIpcProtocolException("Unable to decode IPC payload.", error)
        }
    }
}

internal class SourceSeparationIpcProtocolException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

private fun requireProtocolVersion(protocolVersion: Int) {
    require(protocolVersion == SOURCE_SEPARATION_EXECUTION_PROTOCOL_VERSION) {
        "Unsupported IPC protocol version: $protocolVersion"
    }
}

private fun requireCommandId(commandId: String) {
    require(COMMAND_ID_PATTERN.matches(commandId)) { "IPC command ID is invalid." }
}

private val COMMAND_ID_PATTERN = Regex("^[A-Za-z0-9._-]{1,128}$")
