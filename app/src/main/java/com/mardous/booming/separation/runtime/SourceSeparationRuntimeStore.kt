package com.mardous.booming.separation.runtime

import com.mardous.booming.separation.delivery.RuntimeDeliveryProvider
import com.mardous.booming.separation.delivery.ResumableRuntimeDeliveryProvider
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.ZipFile
import kotlin.concurrent.withLock

internal enum class SourceSeparationRuntimeState {
    Missing,
    Installed,
    Invalid,
    PendingActivation,
    PendingDeletion,
}

internal data class SourceSeparationRuntimeInventoryItem(
    val catalogEntry: SourceSeparationRuntimeCatalogEntry,
    val state: SourceSeparationRuntimeState,
    val reason: String? = null,
    val installedBytes: Long = 0L,
    val installedAtEpochMs: Long? = null,
    val lastValidatedAtEpochMs: Long? = null,
    internal val installation: SourceSeparationCpuRuntimeInstallation? = null,
)

internal data class SourceSeparationRuntimeSpacePreflight(
    val expectedDownloadBytes: Long,
    val expectedInstalledBytes: Long,
    val requiredBytes: Long,
    val availableBytes: Long,
) {
    val hasEnoughSpace: Boolean = availableBytes >= requiredBytes
}

@Serializable
internal data class SourceSeparationRuntimeInstallRecord(
    val schemaVersion: Int,
    val componentId: String,
    val componentType: String,
    val producerReleaseTag: String,
    val producerReleaseVersion: String,
    val runtimeArtifactVersion: String,
    val abi: String,
    val innerManifestSha256: String,
    val librarySha256: String,
    val installedAtEpochMs: Long,
    val lastValidatedAtEpochMs: Long,
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

internal object SourceSeparationRuntimeInstallRecordReader {
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
    }

    fun read(root: File, abi: String): SourceSeparationRuntimeInstallRecord? {
        val file = File(
            SourceSeparationRuntimeLayout.cpuCurrentDirectory(root, abi),
            "install.json",
        )
        if (!file.isFile) return null
        return runCatching {
            json.decodeFromString<SourceSeparationRuntimeInstallRecord>(file.readText())
                .takeIf { it.schemaVersion == SourceSeparationRuntimeInstallRecord.SCHEMA_VERSION }
        }.getOrNull()
    }
}

@Serializable
private data class SourceSeparationRuntimePendingOperation(
    val schemaVersion: Int,
    val operation: String,
    val componentId: String,
    val versionDirectoryName: String? = null,
    val requestedAtEpochMs: Long,
)

internal class SourceSeparationRuntimeStore(
    private val root: File,
    private val catalog: SourceSeparationRuntimeCatalog,
    private val provider: RuntimeDeliveryProvider,
    private val androidApi: Int,
    private val clock: () -> Long = System::currentTimeMillis,
    private val usableSpace: (File) -> Long = { it.usableSpace },
) {
    private val inProcessLock = ReentrantLock()
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        encodeDefaults = true
        explicitNulls = false
    }

    init {
        SourceSeparationRuntimeCatalogLoader.validate(catalog)
        root.mkdirs()
    }

    fun inventory(): List<SourceSeparationRuntimeInventoryItem> = withInstallLock {
        cleanupOrphanStagingLocked()
        catalog.entries.map(::inventoryLocked)
    }

    fun inventory(componentId: String): SourceSeparationRuntimeInventoryItem = withInstallLock {
        inventoryLocked(requireEntry(componentId))
    }

    fun preflight(componentId: String): SourceSeparationRuntimeSpacePreflight =
        preflight(requireEntry(componentId))

    fun install(
        componentId: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): SourceSeparationRuntimeInventoryItem = withInstallLock {
        val entry = requireEntry(componentId)
        requireCompatible(entry)
        require(provider.supports(entry.deliveryReference())) {
            "The configured runtime provider cannot acquire $componentId."
        }
        val space = preflight(entry)
        if (!space.hasEnoughSpace) {
            throw SourceSeparationRuntimeInsufficientStorageException(space)
        }

        cleanupOrphanStagingLocked()
        val stagingRoot = File(stagingDirectory(), entry.componentId).apply { mkdirs() }
        val payloadFile = File(stagingRoot, PAYLOAD_PART_FILE_NAME)
        var keepStaging = true
        try {
            val reference = entry.deliveryReference()
            val completePartIsValid = payloadFile.length() == entry.delivery.expectedByteSize &&
                runCatching { verifyPayloadFile(payloadFile, entry) }.isSuccess
            if (!completePartIsValid) {
                if (payloadFile.length() > entry.delivery.expectedByteSize) {
                    payloadFile.delete()
                }
                val existingBytes = payloadFile.length()
                val resumableProvider = provider as? ResumableRuntimeDeliveryProvider
                if (resumableProvider != null) {
                    resumableProvider.acquireResumable(reference, existingBytes).use { payload ->
                        payload.totalByteSize?.let { actual ->
                            require(actual == entry.delivery.expectedByteSize) {
                                "Resumable runtime delivery size does not match the catalog."
                            }
                        }
                        val offset = payload.resumedOffset
                        require(offset in 0..entry.delivery.expectedByteSize) {
                            "Resumable runtime delivery returned an invalid offset."
                        }
                        copyPayload(
                            input = payload.openStream(),
                            destination = payloadFile,
                            expectedBytes = entry.delivery.expectedByteSize,
                            initialBytes = offset,
                            append = offset > 0L,
                            onProgress = onProgress,
                        )
                    }
                } else {
                    payloadFile.delete()
                    provider.acquire(reference).use { payload ->
                        payload.byteSize?.let { actual ->
                            require(actual == entry.delivery.expectedByteSize) {
                                "Runtime delivery size does not match the catalog."
                            }
                        }
                        copyPayload(
                            input = payload.openStream(),
                            destination = payloadFile,
                            expectedBytes = entry.delivery.expectedByteSize,
                            initialBytes = 0L,
                            append = false,
                            onProgress = onProgress,
                        )
                    }
                }
                keepStaging = false
            } else {
                keepStaging = false
            }
            verifyPayloadFile(payloadFile, entry)
            val stagedCurrent = extractAndVerify(payloadFile, stagingRoot, entry)
            writeInstallRecord(stagedCurrent, entry)
            freezeRuntimeInstallationFiles(stagedCurrent)

            val current = SourceSeparationRuntimeLayout.cpuCurrentDirectory(root, entry.abi)
            val currentInspection = inspectCurrent(entry)
            val sameCurrent = currentInspection.installation?.let { installation ->
                installation.identity.librarySha256.equals(
                    entry.innerLibrary.sha256,
                    ignoreCase = true,
                )
            } == true
            if (sameCurrent && currentInspection.state == SourceSeparationRuntimeState.Installed) {
                keepStaging = false
                return@withInstallLock currentInspection
            }

            val replacementLease = SourceSeparationRuntimeProcessLease.tryAcquire(root, entry.abi)
            if (replacementLease == null) {
                publishPendingVersion(stagedCurrent, entry)
                writePendingOperation(
                    entry = entry,
                    operation = PENDING_ACTIVATION,
                    versionDirectoryName = versionDirectoryName(entry),
                )
                return@withInstallLock inventoryLocked(entry)
            }
            replacementLease.use {
                current.deleteRecursively()
                pendingMarker(entry).delete()
                publishDirectory(stagedCurrent, current)
            }
            inventoryLocked(entry)
        } catch (error: SourceSeparationRuntimeException) {
            throw error
        } catch (error: Throwable) {
            throw SourceSeparationRuntimeInstallException(
                "LiteRT runtime installation failed for ${entry.componentId}.",
                error,
            )
        } finally {
            if (!keepStaging) stagingRoot.deleteRecursively()
        }
    }

    fun repair(
        componentId: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): SourceSeparationRuntimeInventoryItem {
        val entry = requireEntry(componentId)
        withInstallLock {
            val current = SourceSeparationRuntimeLayout.cpuCurrentDirectory(root, entry.abi)
            if (current.exists() && inspectCurrent(entry).state == SourceSeparationRuntimeState.Invalid) {
                val lease = SourceSeparationRuntimeProcessLease.tryAcquire(root, entry.abi)
                if (lease == null) {
                    writePendingOperation(entry, PENDING_DELETION)
                } else {
                    lease.use { current.deleteRecursively() }
                }
            }
        }
        return install(componentId, onProgress)
    }

    fun activatePending(componentId: String): SourceSeparationRuntimeInventoryItem =
        withInstallLock {
            val entry = requireEntry(componentId)
            val pending = readPendingOperation(entry)
            require(pending?.operation == PENDING_ACTIVATION) {
                "No pending LiteRT runtime activation exists for $componentId."
            }
            val versionName = requireNotNull(pending.versionDirectoryName)
            val versionDirectory = versionDirectory(entry, versionName)
            require(versionDirectory.isDirectory) {
                "The pending LiteRT runtime version is missing."
            }
            val lease = SourceSeparationRuntimeProcessLease.tryAcquire(root, entry.abi)
                ?: throw SourceSeparationRuntimeBusyException(
                    "The current LiteRT runtime is still in use.",
                )
            lease.use {
                val current = SourceSeparationRuntimeLayout.cpuCurrentDirectory(root, entry.abi)
                if (current.isDirectory) {
                    val oldRecord = readInstallRecord(current)
                    val oldName = oldRecord?.producerReleaseVersion
                        ?.takeIf(::isSafeDirectoryName)
                        ?: "previous-${clock()}"
                    val oldDirectory = versionDirectory(entry, "$oldName-previous")
                    if (oldDirectory.exists()) oldDirectory.deleteRecursively()
                    publishDirectory(current, oldDirectory)
                }
                publishDirectory(versionDirectory, current)
                pendingMarker(entry).delete()
            }
            inventoryLocked(entry)
        }

    fun remove(componentId: String): SourceSeparationRuntimeInventoryItem =
        withInstallLock {
            val entry = requireEntry(componentId)
            val current = SourceSeparationRuntimeLayout.cpuCurrentDirectory(root, entry.abi)
            val versionRoot = versionsRoot(entry)
            if (!current.exists() && !versionRoot.exists() && !pendingMarker(entry).exists()) {
                return@withInstallLock inventoryLocked(entry)
            }
            val lease = SourceSeparationRuntimeProcessLease.tryAcquire(root, entry.abi)
            if (lease == null) {
                writePendingOperation(entry, PENDING_DELETION)
                return@withInstallLock inventoryLocked(entry)
            }
            lease.use {
                current.deleteRecursively()
                versionRoot.deleteRecursively()
                pendingMarker(entry).delete()
            }
            inventoryLocked(entry)
        }

    fun cleanupOrphanStaging() = withInstallLock {
        cleanupOrphanStagingLocked()
    }

    private fun inventoryLocked(entry: SourceSeparationRuntimeCatalogEntry): SourceSeparationRuntimeInventoryItem {
        val pending = readPendingOperation(entry)
        if (pending?.operation == PENDING_DELETION) {
            return SourceSeparationRuntimeInventoryItem(
                catalogEntry = entry,
                state = SourceSeparationRuntimeState.PendingDeletion,
                reason = "Runtime is waiting for the inference process to release it.",
                installation = inspectCurrent(entry).installation,
            )
        }

        val inspection = inspectCurrent(entry)
        if (pending?.operation == PENDING_ACTIVATION) {
            val versionName = pending.versionDirectoryName
            val version = versionName?.let { versionDirectory(entry, it) }
            if (version?.isDirectory == true) {
                return inspection.copy(
                    state = SourceSeparationRuntimeState.PendingActivation,
                    reason = "A newer runtime is waiting for the inference process to stop.",
                )
            }
        }
        return inspection
    }

    private fun inspectCurrent(entry: SourceSeparationRuntimeCatalogEntry): SourceSeparationRuntimeInventoryItem {
        val current = SourceSeparationRuntimeLayout.cpuCurrentDirectory(root, entry.abi)
        if (!current.isDirectory) {
            return SourceSeparationRuntimeInventoryItem(
                catalogEntry = entry,
                state = SourceSeparationRuntimeState.Missing,
            )
        }
        val record = readInstallRecord(current)
            ?: return invalid(entry, "The runtime install record is missing or invalid.")
        val installation = try {
            SourceSeparationRuntimeLocator(
                root = root,
                processAbi = entry.abi,
                androidApi = androidApi,
            ).resolve()
        } catch (error: Throwable) {
            return invalid(entry, error.message ?: "The runtime manifest could not be verified.")
        }
        val manifestHash = runCatching { installation.manifestFile.sha256() }.getOrNull()
        val valid = manifestHash != null &&
            manifestHash.equals(entry.innerManifestSha256, ignoreCase = true) &&
            installation.manifest.releaseVersion == entry.producerReleaseVersion &&
            installation.manifest.runtimeArtifactVersion == entry.runtimeArtifactVersion &&
            installation.manifest.abi == entry.abi &&
            installation.libraryFile.length() == entry.innerLibrary.byteSize &&
            installation.identity.librarySha256.equals(entry.innerLibrary.sha256, ignoreCase = true) &&
            record.componentId == entry.componentId &&
            record.componentType == entry.componentType &&
            record.producerReleaseTag == entry.producerReleaseTag &&
            record.producerReleaseVersion == entry.producerReleaseVersion &&
            record.runtimeArtifactVersion == entry.runtimeArtifactVersion &&
            record.abi == entry.abi &&
            record.innerManifestSha256.equals(entry.innerManifestSha256, ignoreCase = true) &&
            record.librarySha256.equals(entry.innerLibrary.sha256, ignoreCase = true) &&
            runtimeInstallationFilesAreReadOnly(current)
        if (!valid) return invalid(entry, "The runtime files do not match the bundled catalog.")
        return SourceSeparationRuntimeInventoryItem(
            catalogEntry = entry,
            state = SourceSeparationRuntimeState.Installed,
            installedBytes = installation.libraryFile.length() + installation.manifestFile.length(),
            installedAtEpochMs = record.installedAtEpochMs,
            lastValidatedAtEpochMs = record.lastValidatedAtEpochMs,
            installation = installation,
        )
    }

    private fun invalid(entry: SourceSeparationRuntimeCatalogEntry, reason: String) =
        SourceSeparationRuntimeInventoryItem(
            catalogEntry = entry,
            state = SourceSeparationRuntimeState.Invalid,
            reason = reason,
        )

    private fun extractAndVerify(
        payload: File,
        stagingRoot: File,
        entry: SourceSeparationRuntimeCatalogEntry,
    ): File {
        val stagedCurrent = SourceSeparationRuntimeLayout.cpuCurrentDirectory(stagingRoot, entry.abi)
            .apply { mkdirs() }
        ZipFile(payload).use { zip ->
            val expectedNames = setOf("manifest.json", SourceSeparationRuntimeLayout.LIBRARY_FILE_NAME)
            val names = zip.entries().asSequence().map { it.name }.toList()
            require(names.toSet().size == names.size) {
                "The runtime ZIP contains duplicate entries."
            }
            require(names.toSet() == expectedNames) {
                "The runtime ZIP contains an unexpected file or path."
            }
            for (name in names) {
                require(isSafeZipEntry(name)) { "The runtime ZIP contains a traversal path." }
                val entryInZip = requireNotNull(zip.getEntry(name))
                require(!entryInZip.isDirectory) { "The runtime ZIP contains a directory entry." }
                require(entryInZip.size in 1..MAX_ZIP_ENTRY_BYTES) {
                    "The runtime ZIP entry size is invalid."
                }
                val destination = File(stagedCurrent, name)
                zip.getInputStream(entryInZip).use { input ->
                    FileOutputStream(destination).use { output -> input.copyTo(output) }
                }
            }
        }
        val installation = SourceSeparationRuntimeLocator(
            root = stagingRoot,
            processAbi = entry.abi,
            androidApi = androidApi,
        ).resolve()
        require(installation.manifest.releaseVersion == entry.producerReleaseVersion) {
            "The runtime manifest release does not match the catalog."
        }
        require(installation.manifest.runtimeArtifactVersion == entry.runtimeArtifactVersion) {
            "The runtime artifact version does not match the catalog."
        }
        require(installation.manifest.abi == entry.abi) {
            "The runtime manifest ABI does not match the catalog."
        }
        require(installation.manifestFile.sha256().equals(entry.innerManifestSha256, true)) {
            "The runtime manifest hash does not match the catalog."
        }
        require(installation.libraryFile.length() == entry.innerLibrary.byteSize) {
            "The runtime library size does not match the catalog."
        }
        require(installation.identity.librarySha256.equals(entry.innerLibrary.sha256, true)) {
            "The runtime library hash does not match the catalog."
        }
        return stagedCurrent
    }

    private fun publishPendingVersion(
        stagedCurrent: File,
        entry: SourceSeparationRuntimeCatalogEntry,
    ) {
        val destination = versionDirectory(entry, versionDirectoryName(entry))
        if (destination.exists()) destination.deleteRecursively()
        publishDirectory(stagedCurrent, destination)
    }

    private fun writeInstallRecord(directory: File, entry: SourceSeparationRuntimeCatalogEntry) {
        val now = clock()
        val record = SourceSeparationRuntimeInstallRecord(
            schemaVersion = INSTALL_RECORD_SCHEMA_VERSION,
            componentId = entry.componentId,
            componentType = entry.componentType,
            producerReleaseTag = entry.producerReleaseTag,
            producerReleaseVersion = entry.producerReleaseVersion,
            runtimeArtifactVersion = entry.runtimeArtifactVersion,
            abi = entry.abi,
            innerManifestSha256 = File(directory, SourceSeparationRuntimeLayout.MANIFEST_FILE_NAME)
                .sha256(),
            librarySha256 = File(directory, SourceSeparationRuntimeLayout.LIBRARY_FILE_NAME)
                .sha256(),
            installedAtEpochMs = now,
            lastValidatedAtEpochMs = now,
        )
        File(directory, INSTALL_RECORD_FILE_NAME).writeText(json.encodeToString(record))
    }

    private fun readInstallRecord(directory: File): SourceSeparationRuntimeInstallRecord? {
        val file = File(directory, INSTALL_RECORD_FILE_NAME)
        if (!file.isFile) return null
        return runCatching {
            json.decodeFromString<SourceSeparationRuntimeInstallRecord>(file.readText())
                .takeIf { it.schemaVersion == INSTALL_RECORD_SCHEMA_VERSION }
        }.getOrNull()
    }

    private fun writePendingOperation(
        entry: SourceSeparationRuntimeCatalogEntry,
        operation: String,
        versionDirectoryName: String? = null,
    ) {
        val marker = SourceSeparationRuntimePendingOperation(
            schemaVersion = PENDING_OPERATION_SCHEMA_VERSION,
            operation = operation,
            componentId = entry.componentId,
            versionDirectoryName = versionDirectoryName,
            requestedAtEpochMs = clock(),
        )
        val file = pendingMarker(entry)
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.part")
        temporary.writeText(json.encodeToString(marker))
        if (file.exists()) file.delete()
        check(temporary.renameTo(file)) { "Unable to publish the runtime pending marker." }
    }

    private fun readPendingOperation(entry: SourceSeparationRuntimeCatalogEntry): SourceSeparationRuntimePendingOperation? {
        val file = pendingMarker(entry)
        if (!file.isFile) return null
        return runCatching {
            json.decodeFromString<SourceSeparationRuntimePendingOperation>(file.readText())
                .takeIf {
                    it.schemaVersion == PENDING_OPERATION_SCHEMA_VERSION &&
                        it.componentId == entry.componentId
                }
        }.getOrNull()
    }

    private fun verifyPayloadFile(payload: File, entry: SourceSeparationRuntimeCatalogEntry) {
        require(payload.length() == entry.delivery.expectedByteSize) {
            "The runtime ZIP size does not match the catalog."
        }
        require(payload.sha256().equals(entry.delivery.expectedSha256, ignoreCase = true)) {
            "The runtime ZIP hash does not match the catalog."
        }
    }

    private fun copyPayload(
        input: InputStream,
        destination: File,
        expectedBytes: Long,
        initialBytes: Long,
        append: Boolean,
        onProgress: (Long, Long) -> Unit,
    ) {
        var copied = initialBytes
        require(copied in 0..expectedBytes) { "Initial runtime payload size is invalid." }
        onProgress(copied, expectedBytes)
        input.use { source ->
            FileOutputStream(destination, append).use { output ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    copied += count
                    require(copied <= expectedBytes) { "The runtime ZIP is larger than the catalog." }
                    output.write(buffer, 0, count)
                    onProgress(copied, expectedBytes)
                }
            }
        }
        require(copied == expectedBytes) { "The runtime ZIP ended before its catalog size." }
    }

    private fun preflight(entry: SourceSeparationRuntimeCatalogEntry): SourceSeparationRuntimeSpacePreflight {
        val expectedDownloadBytes = entry.delivery.expectedByteSize
        val expectedInstalledBytes = entry.innerLibrary.byteSize + MANIFEST_RESERVE_BYTES + RECORD_RESERVE_BYTES
        val requiredBytes = expectedDownloadBytes + expectedInstalledBytes + SAFETY_RESERVE_BYTES
        val availableBytes = usableSpace(root.parentFile ?: root)
        return SourceSeparationRuntimeSpacePreflight(
            expectedDownloadBytes = expectedDownloadBytes,
            expectedInstalledBytes = expectedInstalledBytes,
            requiredBytes = requiredBytes,
            availableBytes = availableBytes,
        )
    }

    private fun requireCompatible(entry: SourceSeparationRuntimeCatalogEntry) {
        require(entry.androidMinApi <= androidApi) {
            "LiteRT runtime requires API ${entry.androidMinApi}, but this device is API $androidApi."
        }
    }

    private fun requireEntry(componentId: String): SourceSeparationRuntimeCatalogEntry =
        catalog.entries.singleOrNull { it.componentId == componentId }
            ?: throw SourceSeparationRuntimeInstallException(
                "The runtime component is not present in the bundled catalog: $componentId",
            )

    private fun stagingDirectory() = File(root, STAGING_DIRECTORY)

    private fun cleanupOrphanStagingLocked() {
        val knownComponents = catalog.entries.mapTo(mutableSetOf()) {
            it.componentId
        }
        stagingDirectory().listFiles()?.forEach { child ->
            if (!child.isDirectory || child.name !in knownComponents) {
                child.deleteRecursively()
            }
        }
    }

    private fun versionsRoot(entry: SourceSeparationRuntimeCatalogEntry) = File(
        File(File(root, SourceSeparationRuntimeLayout.CPU_DIRECTORY), entry.abi),
        VERSIONS_DIRECTORY,
    )

    private fun versionDirectory(
        entry: SourceSeparationRuntimeCatalogEntry,
        name: String,
    ): File {
        require(isSafeDirectoryName(name)) { "Runtime version directory name is invalid." }
        return File(versionsRoot(entry), name)
    }

    private fun versionDirectoryName(entry: SourceSeparationRuntimeCatalogEntry): String =
        entry.producerReleaseVersion

    private fun pendingMarker(entry: SourceSeparationRuntimeCatalogEntry) = File(
        File(File(root, SourceSeparationRuntimeLayout.CPU_DIRECTORY), entry.abi),
        "$PENDING_DIRECTORY/${entry.componentId}.json",
    )

    private fun publishDirectory(source: File, destination: File) {
        destination.parentFile?.mkdirs()
        check(!destination.exists()) { "Runtime destination already exists: ${destination.name}" }
        try {
            java.nio.file.Files.move(
                source.toPath(),
                destination.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            check(source.renameTo(destination)) { "Unable to publish the runtime directory." }
        }
    }

    private fun <T> withInstallLock(block: () -> T): T = inProcessLock.withLock {
        root.mkdirs()
        val lockFile = File(root, INSTALL_LOCK_FILE_NAME)
        RandomAccessFile(lockFile, "rw").use { file ->
            file.channel.lock().use { block() }
        }
    }

    private companion object {
        const val INSTALL_RECORD_FILE_NAME = "install.json"
        const val INSTALL_RECORD_SCHEMA_VERSION = SourceSeparationRuntimeInstallRecord.SCHEMA_VERSION
        const val PENDING_OPERATION_SCHEMA_VERSION = 1
        const val PENDING_ACTIVATION = "activation"
        const val PENDING_DELETION = "deletion"
        const val PENDING_DIRECTORY = "pending"
        const val VERSIONS_DIRECTORY = "versions"
        const val STAGING_DIRECTORY = ".staging"
        const val INSTALL_LOCK_FILE_NAME = "install.lock"
        const val PAYLOAD_PART_FILE_NAME = "payload.zip.part"
        const val COPY_BUFFER_BYTES = 256 * 1024
        const val MAX_ZIP_ENTRY_BYTES = 128L * 1024L * 1024L
        const val MANIFEST_RESERVE_BYTES = 64L * 1024L
        const val RECORD_RESERVE_BYTES = 8L * 1024L
        const val SAFETY_RESERVE_BYTES = 1024L * 1024L

        val SAFE_DIRECTORY_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
    }
}

internal fun freezeRuntimeInstallationFiles(directory: File) {
    val files = directory.listFiles().orEmpty().filter(File::isFile)
    check(files.isNotEmpty()) { "Runtime installation contains no files." }
    files.forEach { file ->
        if (file.canWrite()) {
            check(file.setReadOnly()) { "Unable to make runtime file read-only: ${file.name}" }
        }
        check(!file.canWrite()) { "Runtime file remains writable: ${file.name}" }
    }
}

internal fun runtimeInstallationFilesAreReadOnly(directory: File): Boolean {
    val files = directory.listFiles().orEmpty().filter(File::isFile)
    return files.isNotEmpty() && files.all { !it.canWrite() }
}

internal open class SourceSeparationRuntimeException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal class SourceSeparationRuntimeInstallException(
    message: String,
    cause: Throwable? = null,
) : SourceSeparationRuntimeException(message, cause)

internal class SourceSeparationRuntimeBusyException(message: String) :
    SourceSeparationRuntimeException(message)

internal class SourceSeparationRuntimeInsufficientStorageException(
    val preflight: SourceSeparationRuntimeSpacePreflight,
) : SourceSeparationRuntimeException(
    "Not enough free space for the LiteRT runtime: " +
        "need ${preflight.requiredBytes} bytes, have ${preflight.availableBytes}.",
)

internal fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(this).use { input ->
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

private fun isSafeZipEntry(name: String): Boolean =
    name == "manifest.json" || name == SourceSeparationRuntimeLayout.LIBRARY_FILE_NAME

private fun isSafeDirectoryName(name: String): Boolean =
    name.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$"))
