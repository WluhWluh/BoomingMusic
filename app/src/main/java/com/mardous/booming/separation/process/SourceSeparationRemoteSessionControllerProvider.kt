package com.mardous.booming.separation.process

/** Selects one backend policy for the lifetime of a remote process generation. */
internal class SourceSeparationRemoteSessionControllerProvider(
    private val autoControllerFactory: () -> SourceSeparationProcessSessionController,
    private val cpuControllerFactory: () -> SourceSeparationProcessSessionController,
) : AutoCloseable {
    private var selectedBackendPolicy: SourceSeparationExecutionBackendPolicy? = null
    private var controller: SourceSeparationProcessSessionController? = null

    @Synchronized
    fun select(
        backendPolicy: SourceSeparationExecutionBackendPolicy,
    ): SourceSeparationProcessSessionController {
        val selected = selectedBackendPolicy
        if (selected != null && selected != backendPolicy) {
            throw SourceSeparationRemoteBackendPolicyChangeException(
                selectedBackendPolicy = selected,
                requestedBackendPolicy = backendPolicy,
            )
        }
        controller?.let { return it }
        return when (backendPolicy) {
            SourceSeparationExecutionBackendPolicy.Auto -> autoControllerFactory()
            SourceSeparationExecutionBackendPolicy.Cpu -> cpuControllerFactory()
        }.also {
            selectedBackendPolicy = backendPolicy
            controller = it
        }
    }

    @Synchronized
    fun selectedBackendPolicy(): SourceSeparationExecutionBackendPolicy? =
        selectedBackendPolicy

    @Synchronized
    fun currentOrNull(): SourceSeparationProcessSessionController? = controller

    @Synchronized
    fun requireCurrent(): SourceSeparationProcessSessionController =
        requireNotNull(controller) {
            "The remote inference backend has not been selected."
        }

    @Synchronized
    override fun close() {
        controller?.close()
    }
}

internal class SourceSeparationRemoteBackendPolicyChangeException(
    val selectedBackendPolicy: SourceSeparationExecutionBackendPolicy,
    val requestedBackendPolicy: SourceSeparationExecutionBackendPolicy,
) : IllegalStateException(
    "The remote process backend policy cannot change from " +
        "$selectedBackendPolicy to $requestedBackendPolicy within one generation.",
)
