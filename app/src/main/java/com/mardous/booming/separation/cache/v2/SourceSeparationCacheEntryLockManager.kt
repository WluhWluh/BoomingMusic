package com.mardous.booming.separation.cache.v2

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Arbitrates exact-entry mutation across every process in the application UID. */
class SourceSeparationCacheEntryLockManager(
    private val root: SourceSeparationCacheRoot,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    private val json: Json = DEFAULT_JSON,
) {
    fun tryAcquire(
        cacheKey: String,
        owner: SourceSeparationCacheLockOwner,
    ): SourceSeparationCacheEntryKernelLease? {
        require(CACHE_KEY_PATTERN.matches(cacheKey)) { "Cache lock key is invalid." }
        val lockDirectory = File(root.directory, LOCKS_DIRECTORY_NAME)
        if ((!lockDirectory.exists() && !lockDirectory.mkdirs()) || !lockDirectory.isDirectory) {
            return null
        }
        val lockFile = File(lockDirectory, "$cacheKey.lock")
        val randomAccessFile = runCatching { RandomAccessFile(lockFile, "rw") }.getOrNull()
            ?: return null
        val channel = randomAccessFile.channel
        val fileLock = try {
            channel.tryLock()
        } catch (_: OverlappingFileLockException) {
            null
        } catch (_: Throwable) {
            null
        }
        if (fileLock == null) {
            runCatching { channel.close() }
            runCatching { randomAccessFile.close() }
            return null
        }

        return try {
            val token = UUID.randomUUID().toString()
            val metadata = SourceSeparationCacheLockMetadata(
                schemaVersion = SourceSeparationCacheLockMetadata.SCHEMA_VERSION,
                token = token,
                cacheKey = cacheKey,
                purpose = owner.purpose,
                runId = owner.runId,
                processGeneration = owner.processGeneration,
                pid = owner.pid,
                acquiredAtEpochMs = nowEpochMs(),
            )
            val bytes = json.encodeToString(metadata).toByteArray(Charsets.UTF_8)
            val metadataFile = File(lockDirectory, "$cacheKey.owner.json")
            writeDurableFile(metadataFile, bytes)
            SourceSeparationCacheEntryKernelLease(
                root = root,
                cacheKey = cacheKey,
                lockFile = lockFile,
                metadataFile = metadataFile,
                token = token,
                randomAccessFile = randomAccessFile,
                channel = channel,
                fileLock = fileLock,
                json = json,
            )
        } catch (error: Throwable) {
            runCatching { fileLock.release() }
            runCatching { channel.close() }
            runCatching { randomAccessFile.close() }
            throw error
        }
    }

    companion object {
        const val LOCKS_DIRECTORY_NAME = "entry-locks"
        const val ENTRY_IDENTITY_FILE_NAME = ".entry-identity"

        private val CACHE_KEY_PATTERN = Regex("^[0-9a-f]{64}$")
        private val DEFAULT_JSON = Json {
            encodeDefaults = true
            ignoreUnknownKeys = false
            isLenient = false
            coerceInputValues = false
            explicitNulls = false
        }

        private fun writeDurableFile(target: File, bytes: ByteArray) {
            val temporary = File.createTempFile("${target.name}.", ".tmp", target.parentFile)
            try {
                RandomAccessFile(temporary, "rw").use { output ->
                    output.setLength(0L)
                    output.write(bytes)
                    output.fd.sync()
                }
                try {
                    java.nio.file.Files.move(
                        temporary.toPath(),
                        target.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    )
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    java.nio.file.Files.move(
                        temporary.toPath(),
                        target.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    )
                }
            } finally {
                temporary.delete()
            }
        }
    }
}

data class SourceSeparationCacheLockOwner(
    val purpose: SourceSeparationCacheLockPurpose,
    val runId: String? = null,
    val processGeneration: Long? = null,
    val pid: Int? = null,
)

@Serializable
enum class SourceSeparationCacheLockPurpose {
    Run,
    Delete,
    Prune,
    Promotion,
    Cleanup,
    PlaybackSettings,
    Recovery,
    Other,
}

@Serializable
data class SourceSeparationCacheLockMetadata(
    val schemaVersion: Int,
    val token: String,
    val cacheKey: String,
    val purpose: SourceSeparationCacheLockPurpose,
    val runId: String? = null,
    val processGeneration: Long? = null,
    val pid: Int? = null,
    val acquiredAtEpochMs: Long,
) {
    init {
        require(schemaVersion == SCHEMA_VERSION) {
            "Unsupported cache lock metadata schema: $schemaVersion"
        }
    }

    companion object {
        const val SCHEMA_VERSION = 1
    }
}

class SourceSeparationCacheEntryKernelLease internal constructor(
    private val root: SourceSeparationCacheRoot,
    val cacheKey: String,
    private val lockFile: File,
    private val metadataFile: File,
    private val token: String,
    private val randomAccessFile: RandomAccessFile,
    private val channel: FileChannel,
    private val fileLock: FileLock,
    private val json: Json,
) : AutoCloseable {
    private val stateLock = Any()
    private var closed = false
    private var entryIdentity: String? = null
    private var boundEntryDirectory: File? = null

    fun bindEntryDirectory(directory: File) {
        synchronized(stateLock) {
            requireOpenAndIntactLocked()
            require(directory.name == cacheKey) {
                "Cache lock entry directory does not match its key."
            }
            require(directory.parentFile?.canonicalFile ==
                File(root.directory, SourceSeparationCacheStore.ENTRIES_DIR_NAME).canonicalFile
            ) { "Cache lock entry directory is outside the entries root." }
            if (!directory.exists() && !directory.mkdirs()) {
                throw SourceSeparationCacheLostException(
                    cacheKey,
                    "The exact cache entry could not be created while its lock was held.",
                )
            }
            if (!directory.isDirectory) {
                throw SourceSeparationCacheLostException(
                    cacheKey,
                    "The exact cache entry is no longer a directory.",
                )
            }
            val marker = File(directory, SourceSeparationCacheEntryLockManager.ENTRY_IDENTITY_FILE_NAME)
            val identity = marker.takeIf(File::isFile)
                ?.runCatching { readText(Charsets.UTF_8).trim() }
                ?.getOrNull()
                ?.takeIf(String::isNotBlank)
                ?: UUID.randomUUID().toString().also { value ->
                    writeDurableMarker(marker, value)
                }
            boundEntryDirectory = directory.canonicalFile
            entryIdentity = identity
        }
    }

    fun requireAvailable() {
        synchronized(stateLock) {
            requireOpenAndIntactLocked()
            val directory = boundEntryDirectory ?: return
            val marker = File(
                directory,
                SourceSeparationCacheEntryLockManager.ENTRY_IDENTITY_FILE_NAME,
            )
            val currentIdentity = runCatching {
                marker.takeIf(File::isFile)?.readText(Charsets.UTF_8)?.trim()
            }.getOrNull()
            if (!directory.isDirectory || currentIdentity != entryIdentity) {
                throw SourceSeparationCacheLostException(
                    cacheKey,
                    "The exact cache entry disappeared while its writer was active.",
                )
            }
        }
    }

    override fun close() {
        synchronized(stateLock) {
            if (closed) return
            closed = true
        }
        runCatching { fileLock.release() }
        runCatching { channel.close() }
        runCatching { randomAccessFile.close() }
    }

    private fun requireOpenAndIntactLocked() {
        check(!closed && fileLock.isValid && channel.isOpen) { "Cache kernel lease is closed." }
        val metadata = runCatching {
            if (!lockFile.isFile || !metadataFile.isFile) null else json.decodeFromString(
                SourceSeparationCacheLockMetadata.serializer(),
                metadataFile.readText(Charsets.UTF_8),
            )
        }.getOrNull()
        if (metadata?.token != token || metadata.cacheKey != cacheKey) {
            throw SourceSeparationCacheLostException(
                cacheKey,
                "The exact cache lock path disappeared while its writer was active.",
            )
        }
    }

    private fun writeDurableMarker(marker: File, value: String) {
        val temporary = File.createTempFile("${marker.name}.", ".tmp", marker.parentFile)
        try {
            RandomAccessFile(temporary, "rw").use { output ->
                output.setLength(0L)
                output.write(value.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            java.nio.file.Files.move(
                temporary.toPath(),
                marker.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (error: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(
                temporary.toPath(),
                marker.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            temporary.delete()
        }
    }
}

class SourceSeparationCacheLostException(
    val cacheKey: String,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
