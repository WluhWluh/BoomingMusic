package com.mardous.booming.separation.model.litert

import io.github.wluhwluh.bss.litert.BssLiteRtRuntime

internal data class MdxLiteRtBoundedGpuCapability(
    val available: Boolean,
    val schemaVersion: Int,
    val artifactVersion: String,
    val profileId: String,
    val kernelBatchSize: Int,
    val commandQueueWindowSize: Int,
)

internal fun interface MdxLiteRtBoundedGpuCapabilityProvider {
    fun query(): MdxLiteRtBoundedGpuCapability
}

internal data class MdxLiteRtBoundedGpuCapabilityDecision(
    val isExact: Boolean,
    val detail: String,
) {
    init {
        require(detail.isNotBlank()) { "Bounded GPU capability detail is empty." }
    }
}

internal object MdxLiteRtBoundedGpuContract {
    const val CAPABILITY_SCHEMA_VERSION = 1
    const val ARTIFACT_VERSION = "2.1.5-bss.2"
    const val PROFILE_ID = "gpu-opencl-bounded-fp32-v1"
    const val BACKEND = "OpenCL"
    const val PRECISION = "FP32"
    const val KERNEL_BATCH_SIZE = 1
    const val COMMAND_QUEUE_WINDOW_SIZE = 1

    fun evaluate(
        capability: MdxLiteRtBoundedGpuCapability,
    ): MdxLiteRtBoundedGpuCapabilityDecision {
        if (!capability.available) {
            return MdxLiteRtBoundedGpuCapabilityDecision(
                isExact = false,
                detail = "The packaged bounded OpenCL capability is unavailable.",
            )
        }
        val mismatches = buildList {
            if (capability.schemaVersion != CAPABILITY_SCHEMA_VERSION) {
                add("schema=${capability.schemaVersion}")
            }
            if (capability.artifactVersion != ARTIFACT_VERSION) {
                add("artifact=${capability.artifactVersion}")
            }
            if (capability.profileId != PROFILE_ID) {
                add("profile=${capability.profileId}")
            }
            if (capability.kernelBatchSize != KERNEL_BATCH_SIZE) {
                add("kernelBatch=${capability.kernelBatchSize}")
            }
            if (capability.commandQueueWindowSize != COMMAND_QUEUE_WINDOW_SIZE) {
                add("queueWindow=${capability.commandQueueWindowSize}")
            }
        }
        if (mismatches.isNotEmpty()) {
            return MdxLiteRtBoundedGpuCapabilityDecision(
                isExact = false,
                detail = "Bounded OpenCL capability mismatch: ${mismatches.joinToString()}.",
            )
        }
        return MdxLiteRtBoundedGpuCapabilityDecision(
            isExact = true,
            detail = "artifact=$ARTIFACT_VERSION, profile=$PROFILE_ID, " +
                "kernelBatch=$KERNEL_BATCH_SIZE, queueWindow=$COMMAND_QUEUE_WINDOW_SIZE",
        )
    }
}

internal object MdxLiteRtNativeBoundedGpuCapabilityProvider :
    MdxLiteRtBoundedGpuCapabilityProvider {
    override fun query(): MdxLiteRtBoundedGpuCapability {
        val capability = BssLiteRtRuntime.queryCapability()
        return MdxLiteRtBoundedGpuCapability(
            available = capability.isAvailable,
            schemaVersion = capability.schemaVersion,
            artifactVersion = capability.artifactVersion,
            profileId = capability.profileId,
            kernelBatchSize = capability.kernelBatchSize,
            commandQueueWindowSize = capability.commandQueueWindowSize,
        )
    }
}

internal data class MdxLiteRtBoundedGpuStatistics(
    val dispatchCount: Long,
    val eventWaitCount: Long,
)

internal object MdxLiteRtBoundedGpuRuntime {
    fun requireExactCapability(): MdxLiteRtBoundedGpuCapabilityDecision {
        val decision = MdxLiteRtBoundedGpuContract.evaluate(
            MdxLiteRtNativeBoundedGpuCapabilityProvider.query()
        )
        check(decision.isExact) { decision.detail }
        return decision
    }

    fun resetInferenceCounters() {
        BssLiteRtRuntime.resetInferenceCounters()
    }

    fun beginInference() {
        BssLiteRtRuntime.beginInference()
    }

    fun endInference() {
        BssLiteRtRuntime.endInference()
    }

    fun statistics() = MdxLiteRtBoundedGpuStatistics(
        dispatchCount = BssLiteRtRuntime.getDispatchCount(),
        eventWaitCount = BssLiteRtRuntime.getEventWaitCount(),
    )
}
