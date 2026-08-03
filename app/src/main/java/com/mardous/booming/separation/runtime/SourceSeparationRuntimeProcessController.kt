package com.mardous.booming.separation.runtime

import android.os.Build
import android.os.Process
import com.mardous.booming.separation.process.ipc.BoundRemoteSourceSeparationExecutionHost
import com.mardous.booming.separation.process.ipc.SourceSeparationIpcRecycleReason

/** Recycles only an idle inference process before a runtime generation changes. */
internal class SourceSeparationRuntimeProcessController(
    private val executionHost: BoundRemoteSourceSeparationExecutionHost,
    private val runtimeStore: SourceSeparationRuntimeStore,
) {
    fun recycleIfIdle() {
        // Binding a process without a CPU runtime makes its Application fail
        // during startup. This is important for first install and repair.
        if (!hasActiveCpuRuntime()) return
        executionHost.recycleAndStop(SourceSeparationIpcRecycleReason.ModelOrRuntimeKeyChanged)
    }

    private fun hasActiveCpuRuntime(): Boolean = runCatching {
        val processAbi = if (Process.is64Bit()) {
            Build.SUPPORTED_64_BIT_ABIS
        } else {
            Build.SUPPORTED_32_BIT_ABIS
        }.firstOrNull().orEmpty()
        processAbi.isNotBlank() && runtimeStore.inventory().any {
            it.catalogEntry.abi == processAbi &&
                it.state == SourceSeparationRuntimeState.Installed
        }
    }.getOrDefault(false)
}
