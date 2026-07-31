package com.mardous.booming.separation.runtime

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

/**
 * An OS-backed lease shared by the inference process and the runtime store.
 * The kernel releases it when the owning process exits unexpectedly.
 */
internal class SourceSeparationRuntimeProcessLease private constructor(
    private val file: RandomAccessFile,
    private val lock: FileLock,
) : AutoCloseable {
    override fun close() {
        runCatching { lock.release() }
        runCatching { file.close() }
    }

    companion object {
        fun tryAcquire(root: File, abi: String): SourceSeparationRuntimeProcessLease? {
            require(abi.matches(ABI_PATTERN)) { "Runtime ABI is invalid: $abi" }
            val leaseDirectory = File(root, LEASE_DIRECTORY).apply { mkdirs() }
            val leaseFile = File(leaseDirectory, "cpu-$abi.lock")
            val randomAccessFile = RandomAccessFile(leaseFile, "rw")
            val lock = try {
                randomAccessFile.channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            } catch (_: Throwable) {
                null
            }
            if (lock == null) {
                randomAccessFile.close()
                return null
            }
            return SourceSeparationRuntimeProcessLease(randomAccessFile, lock)
        }

        private const val LEASE_DIRECTORY = "leases"
        private val ABI_PATTERN = Regex("^[a-z0-9][a-z0-9._-]{0,31}$")
    }
}
