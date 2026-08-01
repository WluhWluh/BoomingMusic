package com.mardous.booming.separation.runtime

import com.mardous.booming.separation.delivery.ResumableRuntimeDeliveryProvider
import com.mardous.booming.separation.delivery.RuntimeDeliveryProvider
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.ZipFile
import kotlin.concurrent.withLock

internal enum class SourceSeparationGpuRuntimeState {
    Missing,
    Installed,
    Invalid,
    PendingActivation,
    PendingDeletion,
}

internal data class SourceSeparationGpuRuntimeInventoryItem(
    val catalogEntry: SourceSeparationGpuRuntimeCatalogEntry,
    val state: SourceSeparationGpuRuntimeState,
    val reason: String? = null,
    val installedBytes: Long = 0L,
    val installedAtEpochMs: Long? = null,
    val lastValidatedAtEpochMs: Long? = null,
    internal val installation: SourceSeparationGpuRuntimeInstallation? = null,
)

internal data class SourceSeparationGpuRuntimeSpacePreflight(
    val expectedDownloadBytes: Long,
    val expectedInstalledBytes: Long,
    val requiredBytes: Long,
    val availableBytes: Long,
) {
    val hasEnoughSpace: Boolean = availableBytes >= requiredBytes
}

@Serializable
internal data class SourceSeparationGpuRuntimeManifest(
    val schemaVersion: Int,
    val contractSchemaVersion: String,
    val component: String,
    val abi: String,
    val androidMinApi: Int,
    val baseLiteRtVersion: String,
    val capabilities: List<String>,
    val runtimeArtifactVersion: String,
    val releaseVersion: String,
    val files: List<SourceSeparationGpuRuntimeManifestFile>,
    val profile: SourceSeparationGpuRuntimeCapability,
    val requiredCore: SourceSeparationGpuRuntimeRequiredCore,
    val sourceAar: SourceSeparationRuntimeSourceAar,
)

@Serializable
internal data class SourceSeparationGpuRuntimeManifestFile(
    val path: String,
    val byteSize: Long,
    val sha256: String,
    val elf: SourceSeparationGpuRuntimeElf,
    val runtimeLoads: List<String> = emptyList(),
)

@Serializable
internal data class SourceSeparationGpuRuntimeElf(
    @SerialName("class") val elfClass: String,
    val machine: String,
    val needed: List<String>,
    val soname: String,
)

@Serializable
internal data class SourceSeparationGpuRuntimeRequiredCore(
    val abi: String,
    val librarySha256: String,
)

internal data class SourceSeparationGpuRuntimeIdentity(
    val contractSchemaVersion: String,
    val runtimeArtifactVersion: String,
    val releaseVersion: String,
    val abi: String,
    val profileId: String,
    val fileSha256: Map<String, String>,
)

internal data class SourceSeparationGpuRuntimeInstallation(
    val directory: File,
    val manifestFile: File,
    val manifest: SourceSeparationGpuRuntimeManifest,
    val identity: SourceSeparationGpuRuntimeIdentity,
    val libraryFiles: Map<String, File>,
)

internal class SourceSeparationGpuRuntimeLocator(
    private val root: File,
    private val processAbi: String,
    private val androidApi: Int,
) {
    fun resolve(): SourceSeparationGpuRuntimeInstallation {
        val directory = SourceSeparationRuntimeLayout.gpuCurrentDirectory(root, processAbi)
        val manifestFile = File(directory, SourceSeparationRuntimeLayout.MANIFEST_FILE_NAME)
        if (!manifestFile.isFile) {
            throw SourceSeparationRuntimeLoadException(
                reason = SourceSeparationRuntimeFailureReason.MissingRuntime,
                message = "No active LiteRT GPU runtime manifest exists for $processAbi.",
            )
        }
        val canonicalDirectory = directory.canonicalFile
        val canonicalManifest = manifestFile.canonicalFile
        if (canonicalManifest.parentFile != canonicalDirectory) {
            invalidContract("The LiteRT GPU runtime manifest escapes its component directory.")
        }
        val manifest = try {
            GPU_RUNTIME_JSON.decodeFromString<SourceSeparationGpuRuntimeManifest>(
                canonicalManifest.readText(),
            )
        } catch (error: Exception) {
            throw SourceSeparationRuntimeLoadException(
                reason = SourceSeparationRuntimeFailureReason.InvalidContract,
                message = "The LiteRT GPU runtime manifest is invalid.",
                cause = error,
            )
        }
        validateManifest(manifest)

        val files = linkedMapOf<String, File>()
        manifest.files.forEach { file ->
            val libraryFile = File(directory, file.path)
            val canonicalLibrary = libraryFile.canonicalFile
            if (canonicalLibrary.parentFile != canonicalDirectory) {
                invalidContract("The LiteRT GPU runtime library escapes its component directory.")
            }
            if (!canonicalLibrary.isFile) {
                throw SourceSeparationRuntimeLoadException(
                    reason = SourceSeparationRuntimeFailureReason.MissingRuntime,
                    message = "The LiteRT GPU runtime library ${file.path} is missing.",
                )
            }
            if (canonicalLibrary.length() != file.byteSize) {
                throw SourceSeparationRuntimeLoadException(
                    reason = SourceSeparationRuntimeFailureReason.CorruptPayload,
                    message = "The LiteRT GPU runtime library ${file.path} size is invalid.",
                )
            }
            if (!canonicalLibrary.sha256Gpu().equals(file.sha256, ignoreCase = true)) {
                throw SourceSeparationRuntimeLoadException(
                    reason = SourceSeparationRuntimeFailureReason.CorruptPayload,
                    message = "The LiteRT GPU runtime library ${file.path} hash is invalid.",
                )
            }
            files[file.path] = canonicalLibrary
        }

        return SourceSeparationGpuRuntimeInstallation(
            directory = canonicalDirectory,
            manifestFile = canonicalManifest,
            manifest = manifest,
            identity = SourceSeparationGpuRuntimeIdentity(
                contractSchemaVersion = manifest.contractSchemaVersion,
                runtimeArtifactVersion = manifest.runtimeArtifactVersion,
                releaseVersion = manifest.releaseVersion,
                abi = manifest.abi,
                profileId = manifest.profile.profileId,
                fileSha256 = manifest.files.associate { it.path to it.sha256.lowercase(Locale.US) },
            ),
            libraryFiles = files,
        )
    }

    private fun validateManifest(manifest: SourceSeparationGpuRuntimeManifest) {
        if (manifest.schemaVersion != MANIFEST_SCHEMA_VERSION ||
            manifest.contractSchemaVersion != SourceSeparationRuntimeLayout.CONTRACT_SCHEMA_VERSION ||
            manifest.component != SourceSeparationRuntimeLayout.GPU_COMPONENT ||
            manifest.files.size != 2
        ) {
            invalidContract("The LiteRT GPU runtime manifest has an unsupported shape.")
        }
        if (manifest.abi != processAbi) {
            throw SourceSeparationRuntimeLoadException(
                reason = SourceSeparationRuntimeFailureReason.WrongAbi,
                message = "The LiteRT GPU runtime targets ${manifest.abi}, but this process uses $processAbi.",
            )
        }
        if (manifest.androidMinApi > androidApi) {
            throw SourceSeparationRuntimeLoadException(
                reason = SourceSeparationRuntimeFailureReason.UnsupportedApi,
                message = "The LiteRT GPU runtime requires API ${manifest.androidMinApi}, " +
                    "but this device is API $androidApi.",
            )
        }
        if (manifest.baseLiteRtVersion.isBlank() ||
            manifest.runtimeArtifactVersion.isBlank() ||
            manifest.releaseVersion.isBlank() ||
            manifest.requiredCore.abi != manifest.abi ||
            !SHA256_PATTERN.matches(manifest.requiredCore.librarySha256) ||
            !SHA256_PATTERN.matches(manifest.sourceAar.sha256)
        ) {
            invalidContract("The LiteRT GPU runtime manifest has incomplete identity data.")
        }
        if (manifest.profile != SourceSeparationGpuRuntimeCapability(
                schemaVersion = 1,
                profileId = "gpu-opencl-bounded-fp32-v1",
                precision = "FP32",
                backend = "OpenCL",
                kernelBatchSize = 1,
                commandQueueWindowSize = 1,
            )
        ) {
            invalidContract("The LiteRT GPU runtime manifest does not describe the qualified N=1 profile.")
        }
        val paths = manifest.files.map(SourceSeparationGpuRuntimeManifestFile::path).toSet()
        if (paths != setOf("libBssOcl.so", "libLiteRtClGlAccelerator.so")) {
            invalidContract("The LiteRT GPU runtime manifest has an invalid library set.")
        }
        manifest.files.forEach { file ->
            if (!SHA256_PATTERN.matches(file.sha256) ||
                file.byteSize <= 0L ||
                file.elf.elfClass.isBlank() ||
                file.elf.machine.isBlank() ||
                file.elf.soname != file.path
            ) {
                invalidContract("The LiteRT GPU runtime manifest has an invalid library record.")
            }
        }
        val accelerator = manifest.files.single { it.path == "libLiteRtClGlAccelerator.so" }
        if (accelerator.runtimeLoads != listOf("libBssOcl.so")) {
            invalidContract("The GPU accelerator dependency order is invalid.")
        }
    }

    private fun invalidContract(message: String): Nothing = throw SourceSeparationRuntimeLoadException(
        reason = SourceSeparationRuntimeFailureReason.InvalidContract,
        message = message,
    )

    private companion object {
        const val MANIFEST_SCHEMA_VERSION = 1
        val SHA256_PATTERN = Regex("^[a-fA-F0-9]{64}$")
        val GPU_RUNTIME_JSON = Json {
            ignoreUnknownKeys = false
            isLenient = false
        }
    }
}

@Serializable
internal data class SourceSeparationGpuRuntimeInstallRecord(
    val schemaVersion: Int,
    val componentId: String,
    val componentType: String,
    val producerReleaseTag: String,
    val producerReleaseVersion: String,
    val runtimeArtifactVersion: String,
    val abi: String,
    val innerManifestSha256: String,
    val fileSha256: Map<String, String>,
    val installedAtEpochMs: Long,
    val lastValidatedAtEpochMs: Long,
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

internal object SourceSeparationGpuRuntimeInstallRecordReader {
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
    }

    fun read(directory: File): SourceSeparationGpuRuntimeInstallRecord? {
        val file = File(directory, "install.json")
        if (!file.isFile) return null
        return runCatching {
            json.decodeFromString<SourceSeparationGpuRuntimeInstallRecord>(file.readText())
                .takeIf { it.schemaVersion == SourceSeparationGpuRuntimeInstallRecord.SCHEMA_VERSION }
        }.getOrNull()
    }
}

@Serializable
private data class SourceSeparationGpuRuntimePendingOperation(
    val schemaVersion: Int,
    val operation: String,
    val componentId: String,
    val versionDirectoryName: String? = null,
    val requestedAtEpochMs: Long,
)

internal class SourceSeparationGpuRuntimeStore(
    private val root: File,
    private val catalog: SourceSeparationGpuRuntimeCatalog,
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
        SourceSeparationGpuRuntimeCatalogLoader.validate(catalog)
        root.mkdirs()
    }

    fun inventory(): List<SourceSeparationGpuRuntimeInventoryItem> = withInstallLock {
        cleanupOrphanStagingLocked()
        catalog.entries.map(::inventoryLocked)
    }

    fun inventory(componentId: String): SourceSeparationGpuRuntimeInventoryItem = withInstallLock {
        inventoryLocked(requireEntry(componentId))
    }

    fun preflight(componentId: String): SourceSeparationGpuRuntimeSpacePreflight =
        preflight(requireEntry(componentId))

    fun install(
        componentId: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): SourceSeparationGpuRuntimeInventoryItem = withInstallLock {
        val entry = requireEntry(componentId)
        requireCompatible(entry)
        requireCpuDependency(entry)
        require(provider.supports(entry.deliveryReference())) {
            "The configured runtime provider cannot acquire $componentId."
        }
        val space = preflight(entry)
        if (!space.hasEnoughSpace) throw SourceSeparationRuntimeInsufficientStorageException(
            SourceSeparationRuntimeSpacePreflight(
                expectedDownloadBytes = space.expectedDownloadBytes,
                expectedInstalledBytes = space.expectedInstalledBytes,
                requiredBytes = space.requiredBytes,
                availableBytes = space.availableBytes,
            ),
        )

        cleanupOrphanStagingLocked()
        val stagingRoot = File(stagingDirectory(), entry.componentId).apply { mkdirs() }
        val payloadFile = File(stagingRoot, PAYLOAD_PART_FILE_NAME)
        var keepStaging = true
        try {
            val reference = entry.deliveryReference()
            val completePartIsValid = payloadFile.length() == entry.delivery.expectedByteSize &&
                runCatching { verifyPayloadFile(payloadFile, entry) }.isSuccess
            if (!completePartIsValid) {
                if (payloadFile.length() > entry.delivery.expectedByteSize) payloadFile.delete()
                val existingBytes = payloadFile.length()
                val resumableProvider = provider as? ResumableRuntimeDeliveryProvider
                if (resumableProvider != null) {
                    resumableProvider.acquireResumable(reference, existingBytes).use { payload ->
                        payload.totalByteSize?.let { actual ->
                            require(actual == entry.delivery.expectedByteSize) {
                                "Resumable GPU delivery size does not match the catalog."
                            }
                        }
                        val offset = payload.resumedOffset
                        require(offset in 0..entry.delivery.expectedByteSize) {
                            "Resumable GPU delivery returned an invalid offset."
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
                                "GPU delivery size does not match the catalog."
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

            val current = SourceSeparationRuntimeLayout.gpuCurrentDirectory(root, entry.abi)
            val currentInspection = inspectCurrent(entry)
            val sameCurrent = currentInspection.installation?.let { installation ->
                installation.identity.runtimeArtifactVersion == entry.runtimeArtifactVersion &&
                    installation.identity.releaseVersion == entry.producerReleaseVersion &&
                    installation.identity.fileSha256 == entry.files.associate {
                        it.path to it.sha256.lowercase(Locale.US)
                    }
            } == true && currentInspection.state == SourceSeparationGpuRuntimeState.Installed
            if (sameCurrent) {
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
                keepStaging = false
                return@withInstallLock inventoryLocked(entry)
            }
            replacementLease.use {
                current.deleteRecursively()
                pendingMarker(entry).delete()
                publishDirectory(stagedCurrent, current)
            }
            keepStaging = false
            inventoryLocked(entry)
        } catch (error: SourceSeparationRuntimeException) {
            throw error
        } catch (error: Throwable) {
            throw SourceSeparationRuntimeInstallException(
                "LiteRT GPU runtime installation failed for ${entry.componentId}.",
                error,
            )
        } finally {
            if (!keepStaging) stagingRoot.deleteRecursively()
        }
    }

    fun repair(
        componentId: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): SourceSeparationGpuRuntimeInventoryItem {
        val entry = requireEntry(componentId)
        withInstallLock {
            val current = SourceSeparationRuntimeLayout.gpuCurrentDirectory(root, entry.abi)
            if (current.exists() && inspectCurrent(entry).state == SourceSeparationGpuRuntimeState.Invalid) {
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

    fun activatePending(componentId: String): SourceSeparationGpuRuntimeInventoryItem =
        withInstallLock {
            val entry = requireEntry(componentId)
            val pending = readPendingOperation(entry)
            require(pending?.operation == PENDING_ACTIVATION) {
                "No pending LiteRT GPU runtime activation exists for $componentId."
            }
            val versionName = requireNotNull(pending.versionDirectoryName)
            val versionDirectory = versionDirectory(entry, versionName)
            require(versionDirectory.isDirectory) { "The pending LiteRT GPU runtime version is missing." }
            val lease = SourceSeparationRuntimeProcessLease.tryAcquire(root, entry.abi)
                ?: throw SourceSeparationRuntimeBusyException(
                    "The current LiteRT runtime is still in use.",
                )
            lease.use {
                val current = SourceSeparationRuntimeLayout.gpuCurrentDirectory(root, entry.abi)
                if (current.isDirectory) {
                    val oldRecord = readInstallRecord(current)
                    val oldName = oldRecord?.producerReleaseVersion
                        ?.takeIf(::isSafeDirectoryName) ?: "previous-${clock()}"
                    val oldDirectory = versionDirectory(entry, "$oldName-previous")
                    if (oldDirectory.exists()) oldDirectory.deleteRecursively()
                    publishDirectory(current, oldDirectory)
                }
                publishDirectory(versionDirectory, current)
                pendingMarker(entry).delete()
            }
            inventoryLocked(entry)
        }

    fun remove(componentId: String): SourceSeparationGpuRuntimeInventoryItem =
        withInstallLock {
            val entry = requireEntry(componentId)
            val current = SourceSeparationRuntimeLayout.gpuCurrentDirectory(root, entry.abi)
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

    fun cleanupOrphanStaging() = withInstallLock { cleanupOrphanStagingLocked() }

    private fun inventoryLocked(entry: SourceSeparationGpuRuntimeCatalogEntry):
        SourceSeparationGpuRuntimeInventoryItem {
        val pending = readPendingOperation(entry)
        if (pending?.operation == PENDING_DELETION) {
            return SourceSeparationGpuRuntimeInventoryItem(
                catalogEntry = entry,
                state = SourceSeparationGpuRuntimeState.PendingDeletion,
                reason = "GPU runtime is waiting for the inference process to release it.",
                installation = inspectCurrent(entry).installation,
            )
        }
        val inspection = inspectCurrent(entry)
        if (pending?.operation == PENDING_ACTIVATION) {
            val version = pending.versionDirectoryName?.let { versionDirectory(entry, it) }
            if (version?.isDirectory == true) {
                return inspection.copy(
                    state = SourceSeparationGpuRuntimeState.PendingActivation,
                    reason = "A newer GPU runtime is waiting for the inference process to stop.",
                )
            }
        }
        return inspection
    }

    private fun inspectCurrent(entry: SourceSeparationGpuRuntimeCatalogEntry):
        SourceSeparationGpuRuntimeInventoryItem {
        val current = SourceSeparationRuntimeLayout.gpuCurrentDirectory(root, entry.abi)
        if (!current.isDirectory) {
            return SourceSeparationGpuRuntimeInventoryItem(
                catalogEntry = entry,
                state = SourceSeparationGpuRuntimeState.Missing,
            )
        }
        val record = readInstallRecord(current)
            ?: return invalid(entry, "The GPU runtime install record is missing or invalid.")
        val installation = try {
            SourceSeparationGpuRuntimeLocator(root, entry.abi, androidApi).resolve()
        } catch (error: Throwable) {
            return invalid(entry, error.message ?: "The GPU runtime manifest could not be verified.")
        }
        val expectedFiles = entry.files.associate { it.path to it.sha256.lowercase(Locale.US) }
        val valid = installation.manifestFile.sha256Gpu().equals(entry.innerManifestSha256, true) &&
            installation.manifest.releaseVersion == entry.producerReleaseVersion &&
            installation.manifest.runtimeArtifactVersion == entry.runtimeArtifactVersion &&
            installation.manifest.abi == entry.abi &&
            installation.manifest.requiredCore.librarySha256.equals(entry.requiredCpuLibrarySha256, true) &&
            installation.identity.fileSha256 == expectedFiles &&
            record.componentId == entry.componentId &&
            record.componentType == entry.componentType &&
            record.producerReleaseTag == entry.producerReleaseTag &&
            record.producerReleaseVersion == entry.producerReleaseVersion &&
            record.runtimeArtifactVersion == entry.runtimeArtifactVersion &&
            record.abi == entry.abi &&
            record.innerManifestSha256.equals(entry.innerManifestSha256, true) &&
            record.fileSha256 == expectedFiles &&
            cpuDependencyReason(entry) == null
        if (!valid) {
            val reason = cpuDependencyReason(entry) ?: "The GPU runtime files do not match the bundled catalog."
            return invalid(entry, reason)
        }
        return SourceSeparationGpuRuntimeInventoryItem(
            catalogEntry = entry,
            state = SourceSeparationGpuRuntimeState.Installed,
            installedBytes = installation.manifestFile.length() +
                installation.libraryFiles.values.sumOf(File::length) +
                File(current, INSTALL_RECORD_FILE_NAME).length(),
            installedAtEpochMs = record.installedAtEpochMs,
            lastValidatedAtEpochMs = record.lastValidatedAtEpochMs,
            installation = installation,
        )
    }

    private fun cpuDependencyReason(entry: SourceSeparationGpuRuntimeCatalogEntry): String? {
        val cpu = runCatching {
            SourceSeparationRuntimeLocator(root, entry.abi, androidApi).resolve()
        }.getOrElse { error ->
            return "Required CPU LiteRT runtime is not valid: ${error.message ?: "unknown error"}."
        }
        if (cpu.manifest.component != SourceSeparationRuntimeLayout.CPU_COMPONENT ||
            cpu.identity.librarySha256 != entry.requiredCpuLibrarySha256.lowercase(Locale.US)
        ) {
            return "The installed CPU LiteRT runtime does not match the GPU dependency."
        }
        val installRecord = readCpuInstallRecord(entry.abi)
            ?: return "The installed CPU LiteRT runtime has no valid install record."
        if (installRecord.componentId != entry.requiredCpuComponentId ||
            installRecord.componentType != SourceSeparationRuntimeLayout.CPU_COMPONENT ||
            installRecord.abi != entry.abi ||
            !installRecord.librarySha256.equals(cpu.identity.librarySha256, ignoreCase = true)
        ) {
            return "The installed CPU LiteRT component identity does not match the GPU dependency."
        }
        return null
    }

    private fun readCpuInstallRecord(abi: String): SourceSeparationRuntimeInstallRecord? {
        return SourceSeparationRuntimeInstallRecordReader.read(root, abi)
    }

    private fun invalid(entry: SourceSeparationGpuRuntimeCatalogEntry, reason: String) =
        SourceSeparationGpuRuntimeInventoryItem(
            catalogEntry = entry,
            state = SourceSeparationGpuRuntimeState.Invalid,
            reason = reason,
        )

    private fun extractAndVerify(
        payload: File,
        stagingRoot: File,
        entry: SourceSeparationGpuRuntimeCatalogEntry,
    ): File {
        val stagedCurrent = SourceSeparationRuntimeLayout.gpuCurrentDirectory(stagingRoot, entry.abi)
            .apply { mkdirs() }
        ZipFile(payload).use { zip ->
            val expectedNames = setOf(
                SourceSeparationRuntimeLayout.MANIFEST_FILE_NAME,
                "libLiteRtClGlAccelerator.so",
                "libBssOcl.so",
            )
            val names = zip.entries().asSequence().map { it.name }.toList()
            require(names.toSet().size == names.size) { "The GPU runtime ZIP contains duplicate entries." }
            require(names.toSet() == expectedNames) {
                "The GPU runtime ZIP contains an unexpected file or path."
            }
            names.forEach { name ->
                require(isSafeGpuZipEntry(name)) { "The GPU runtime ZIP contains a traversal path." }
                val entryInZip = requireNotNull(zip.getEntry(name))
                require(!entryInZip.isDirectory) { "The GPU runtime ZIP contains a directory entry." }
                require(entryInZip.size in 1..MAX_ZIP_ENTRY_BYTES) {
                    "The GPU runtime ZIP entry size is invalid."
                }
                val destination = File(stagedCurrent, name)
                zip.getInputStream(entryInZip).use { input ->
                    FileOutputStream(destination).use { output -> input.copyTo(output) }
                }
            }
        }
        val installation = SourceSeparationGpuRuntimeLocator(
            root = stagingRoot,
            processAbi = entry.abi,
            androidApi = androidApi,
        ).resolve()
        require(installation.manifest.releaseVersion == entry.producerReleaseVersion) {
            "The GPU runtime manifest release does not match the catalog."
        }
        require(installation.manifest.runtimeArtifactVersion == entry.runtimeArtifactVersion) {
            "The GPU runtime artifact version does not match the catalog."
        }
        require(installation.manifest.requiredCore.librarySha256.equals(entry.requiredCpuLibrarySha256, true)) {
            "The GPU runtime CPU dependency does not match the catalog."
        }
        require(installation.manifestFile.sha256Gpu().equals(entry.innerManifestSha256, true)) {
            "The GPU runtime manifest hash does not match the catalog."
        }
        require(installation.identity.fileSha256 == entry.files.associate {
            it.path to it.sha256.lowercase(Locale.US)
        }) {
            "The GPU runtime library hashes do not match the catalog."
        }
        return stagedCurrent
    }

    private fun publishPendingVersion(stagedCurrent: File, entry: SourceSeparationGpuRuntimeCatalogEntry) {
        val destination = versionDirectory(entry, versionDirectoryName(entry))
        if (destination.exists()) destination.deleteRecursively()
        publishDirectory(stagedCurrent, destination)
    }

    private fun writeInstallRecord(directory: File, entry: SourceSeparationGpuRuntimeCatalogEntry) {
        val now = clock()
        val record = SourceSeparationGpuRuntimeInstallRecord(
            schemaVersion = INSTALL_RECORD_SCHEMA_VERSION,
            componentId = entry.componentId,
            componentType = entry.componentType,
            producerReleaseTag = entry.producerReleaseTag,
            producerReleaseVersion = entry.producerReleaseVersion,
            runtimeArtifactVersion = entry.runtimeArtifactVersion,
            abi = entry.abi,
            innerManifestSha256 = File(directory, SourceSeparationRuntimeLayout.MANIFEST_FILE_NAME)
                .sha256Gpu(),
            fileSha256 = entry.files.associate { file ->
                file.path to File(directory, file.path).sha256Gpu()
            },
            installedAtEpochMs = now,
            lastValidatedAtEpochMs = now,
        )
        File(directory, INSTALL_RECORD_FILE_NAME).writeText(json.encodeToString(record))
    }

    private fun readInstallRecord(directory: File): SourceSeparationGpuRuntimeInstallRecord? {
        return SourceSeparationGpuRuntimeInstallRecordReader.read(directory)
    }

    private fun writePendingOperation(
        entry: SourceSeparationGpuRuntimeCatalogEntry,
        operation: String,
        versionDirectoryName: String? = null,
    ) {
        val marker = SourceSeparationGpuRuntimePendingOperation(
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
        check(temporary.renameTo(file)) { "Unable to publish the GPU runtime pending marker." }
    }

    private fun readPendingOperation(entry: SourceSeparationGpuRuntimeCatalogEntry):
        SourceSeparationGpuRuntimePendingOperation? {
        val file = pendingMarker(entry)
        if (!file.isFile) return null
        return runCatching {
            json.decodeFromString<SourceSeparationGpuRuntimePendingOperation>(file.readText())
                .takeIf {
                    it.schemaVersion == PENDING_OPERATION_SCHEMA_VERSION &&
                        it.componentId == entry.componentId
                }
        }.getOrNull()
    }

    private fun verifyPayloadFile(payload: File, entry: SourceSeparationGpuRuntimeCatalogEntry) {
        require(payload.length() == entry.delivery.expectedByteSize) {
            "The GPU runtime ZIP size does not match the catalog."
        }
        require(payload.sha256Gpu().equals(entry.delivery.expectedSha256, true)) {
            "The GPU runtime ZIP hash does not match the catalog."
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
        require(copied in 0..expectedBytes) { "Initial GPU payload size is invalid." }
        onProgress(copied, expectedBytes)
        input.use { source ->
            FileOutputStream(destination, append).use { output ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    copied += count
                    require(copied <= expectedBytes) { "The GPU runtime ZIP is larger than the catalog." }
                    output.write(buffer, 0, count)
                    onProgress(copied, expectedBytes)
                }
            }
        }
        require(copied == expectedBytes) { "The GPU runtime ZIP ended before its catalog size." }
    }

    private fun preflight(entry: SourceSeparationGpuRuntimeCatalogEntry): SourceSeparationGpuRuntimeSpacePreflight {
        val expectedDownloadBytes = entry.delivery.expectedByteSize
        val expectedInstalledBytes = entry.files.sumOf(SourceSeparationGpuRuntimeLibrary::byteSize) +
            MANIFEST_RESERVE_BYTES + RECORD_RESERVE_BYTES
        val requiredBytes = expectedDownloadBytes + expectedInstalledBytes + SAFETY_RESERVE_BYTES
        val availableBytes = usableSpace(root.parentFile ?: root)
        return SourceSeparationGpuRuntimeSpacePreflight(
            expectedDownloadBytes = expectedDownloadBytes,
            expectedInstalledBytes = expectedInstalledBytes,
            requiredBytes = requiredBytes,
            availableBytes = availableBytes,
        )
    }

    private fun requireCompatible(entry: SourceSeparationGpuRuntimeCatalogEntry) {
        require(entry.androidMinApi <= androidApi) {
            "LiteRT GPU runtime requires API ${entry.androidMinApi}, but this device is API $androidApi."
        }
    }

    private fun requireCpuDependency(entry: SourceSeparationGpuRuntimeCatalogEntry) {
        val reason = cpuDependencyReason(entry)
        require(reason == null) { reason ?: "The required CPU LiteRT runtime is unavailable." }
    }

    private fun requireEntry(componentId: String): SourceSeparationGpuRuntimeCatalogEntry =
        catalog.entries.singleOrNull { it.componentId == componentId }
            ?: throw SourceSeparationRuntimeInstallException(
                "The GPU runtime component is not present in the bundled catalog: $componentId",
            )

    private fun stagingDirectory() = File(File(root, SourceSeparationRuntimeLayout.GPU_DIRECTORY), STAGING_DIRECTORY)

    private fun cleanupOrphanStagingLocked() {
        val knownComponents = catalog.entries.mapTo(mutableSetOf()) { it.componentId }
        stagingDirectory().listFiles()?.forEach { child ->
            if (!child.isDirectory || child.name !in knownComponents) child.deleteRecursively()
        }
    }

    private fun versionsRoot(entry: SourceSeparationGpuRuntimeCatalogEntry) = File(
        File(File(root, SourceSeparationRuntimeLayout.GPU_DIRECTORY), entry.abi),
        VERSIONS_DIRECTORY,
    )

    private fun versionDirectory(entry: SourceSeparationGpuRuntimeCatalogEntry, name: String): File {
        require(isSafeDirectoryName(name)) { "GPU runtime version directory name is invalid." }
        return File(versionsRoot(entry), name)
    }

    private fun versionDirectoryName(entry: SourceSeparationGpuRuntimeCatalogEntry): String =
        entry.producerReleaseVersion

    private fun pendingMarker(entry: SourceSeparationGpuRuntimeCatalogEntry) = File(
        File(File(root, SourceSeparationRuntimeLayout.GPU_DIRECTORY), entry.abi),
        "$PENDING_DIRECTORY/${entry.componentId}.json",
    )

    private fun publishDirectory(source: File, destination: File) {
        destination.parentFile?.mkdirs()
        check(!destination.exists()) { "GPU runtime destination already exists: ${destination.name}" }
        try {
            java.nio.file.Files.move(
                source.toPath(),
                destination.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            check(source.renameTo(destination)) { "Unable to publish the GPU runtime directory." }
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
        const val INSTALL_RECORD_SCHEMA_VERSION = SourceSeparationGpuRuntimeInstallRecord.SCHEMA_VERSION
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
    }
}

private fun File.sha256Gpu(): String {
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

private fun isSafeGpuZipEntry(name: String): Boolean = name in setOf(
    SourceSeparationRuntimeLayout.MANIFEST_FILE_NAME,
    "libLiteRtClGlAccelerator.so",
    "libBssOcl.so",
)

private fun isSafeDirectoryName(name: String): Boolean =
    name.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$"))
