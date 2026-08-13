package com.mardous.booming.separation

import com.mardous.booming.separation.model.contract.SourceSeparationInstalledMultiStemModel
import com.mardous.booming.separation.model.contract.SourceSeparationMultiTensorExecutableContract
import com.mardous.booming.separation.model.contract.SourceSeparationMultiTensorExecutableContractLoader

internal data class SourceSeparationMultiStemContractMemoSnapshot(
    val hits: Long,
    val misses: Long,
    val entryCount: Int,
)

internal class SourceSeparationMultiStemContractMemo(
    private val maxEntries: Int = MAX_ENTRIES,
) {
    init {
        require(maxEntries > 0) { "The multi-stem contract memo capacity must be positive." }
    }

    private val lock = Any()
    private var hits = 0L
    private var misses = 0L
    private val entries = object : LinkedHashMap<
        SourceSeparationMultiStemContractMemoKey,
        SourceSeparationMultiTensorExecutableContract,
    >(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<
                SourceSeparationMultiStemContractMemoKey,
                SourceSeparationMultiTensorExecutableContract,
            >?,
        ): Boolean = size > maxEntries
    }

    fun resolve(
        installed: SourceSeparationInstalledMultiStemModel,
    ): SourceSeparationMultiTensorExecutableContract {
        val sidecar = installed.sidecarFile
        val key = SourceSeparationMultiStemContractMemoKey(
            modelId = installed.modelId,
            modelSha256 = installed.modelSha256,
            contractId = installed.contractId,
            sidecarPath = sidecar.absolutePath,
            sidecarLength = sidecar.length(),
            sidecarLastModified = sidecar.lastModified(),
        )
        synchronized(lock) {
            entries[key]?.let { cached ->
                hits += 1L
                return cached
            }
            misses += 1L
        }

        val resolved = sidecar.bufferedReader().use { reader ->
            SourceSeparationMultiTensorExecutableContractLoader.load(reader.readText())
        }
        synchronized(lock) {
            entries[key] = resolved
        }
        return resolved
    }

    fun snapshot(): SourceSeparationMultiStemContractMemoSnapshot = synchronized(lock) {
        SourceSeparationMultiStemContractMemoSnapshot(
            hits = hits,
            misses = misses,
            entryCount = entries.size,
        )
    }

    private companion object {
        const val MAX_ENTRIES = 4
    }
}

private data class SourceSeparationMultiStemContractMemoKey(
    val modelId: String,
    val modelSha256: String,
    val contractId: String,
    val sidecarPath: String,
    val sidecarLength: Long,
    val sidecarLastModified: Long,
)
