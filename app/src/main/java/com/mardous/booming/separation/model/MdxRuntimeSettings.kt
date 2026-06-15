package com.mardous.booming.separation.model

import ai.onnxruntime.OrtSession

data class MdxRuntimeSettings(
    val cpuThreads: Int = DEFAULT_CPU_THREADS,
    val useXnnpack: Boolean = false,
) {
    fun createSessionOptions(): OrtSession.SessionOptions {
        return OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            setInterOpNumThreads(1)
            if (useXnnpack) {
                addConfigEntry("session.intra_op.allow_spinning", "0")
                addConfigEntry("session.inter_op.allow_spinning", "0")
                setIntraOpNumThreads(1)
                addXnnpack(mapOf("intra_op_num_threads" to xnnpackThreadCount().toString()))
            } else if (cpuThreads > 0) {
                setIntraOpNumThreads(cpuThreads)
            }
        }
    }

    fun toDisplayText(): String {
        val threadText = if (cpuThreads > 0) cpuThreads.toString() else "ORT default"
        val providerText = if (useXnnpack) {
            "XNNPACK threads=${xnnpackThreadCount()}"
        } else {
            "CPU threads=$threadText"
        }
        return "Runtime: $providerText, optimization=ALL_OPT, execution=SEQUENTIAL"
    }

    private fun xnnpackThreadCount(): Int {
        return cpuThreads.coerceAtLeast(1)
    }

    companion object {
        const val DEFAULT_CPU_THREADS = 8
    }
}
