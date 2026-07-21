package com.mardous.booming.separation.model

data class MdxRuntimeSettings(
    val cpuThreads: Int = DEFAULT_CPU_THREADS,
    val useXnnpack: Boolean = false,
) {
    companion object {
        const val DEFAULT_CPU_THREADS = 8
    }
}
