package com.mardous.booming.separation.runtime

import android.content.Context
import android.os.Build
import android.os.Process
import com.google.ai.edge.litert.LiteRtNativeLibraryLoader
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.Locale

internal object SourceSeparationRuntimeLayout {
    const val CONTRACT_SCHEMA_VERSION = "bss-litert-downloadable-runtime-v2"
    const val CPU_DIRECTORY = "cpu"
    const val CPU_COMPONENT = "cpu-core"
    const val GPU_DIRECTORY = "gpu"
    const val GPU_COMPONENT = "bounded-gpu"
    const val CURRENT_DIRECTORY = "current"
    const val MANIFEST_FILE_NAME = "manifest.json"
    const val LIBRARY_FILE_NAME = "libLiteRt.so"

    fun runtimeRoot(context: Context): File = File(
        context.noBackupFilesDir,
        "source-separation/runtimes",
    )

    fun cpuCurrentDirectory(root: File, abi: String): File = File(
        File(root, CPU_DIRECTORY),
        "$abi/$CURRENT_DIRECTORY",
    )

    fun gpuCurrentDirectory(root: File, abi: String): File = File(
        File(root, GPU_DIRECTORY),
        "$abi/$CURRENT_DIRECTORY",
    )
}

internal enum class SourceSeparationRuntimeFailureReason {
    MissingRuntime,
    InvalidContract,
    WrongAbi,
    UnsupportedApi,
    CorruptPayload,
    MissingDependency,
    LoadFailure,
    ConflictingLoaderPath,
}

internal class SourceSeparationRuntimeLoadException(
    val reason: SourceSeparationRuntimeFailureReason,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

@Serializable
internal data class SourceSeparationCpuRuntimeManifest(
    val schemaVersion: Int,
    val contractSchemaVersion: String,
    val component: String,
    val abi: String,
    val androidMinApi: Int,
    val baseLiteRtVersion: String,
    val capabilities: List<String>,
    val runtimeArtifactVersion: String,
    val releaseVersion: String,
    val files: List<SourceSeparationRuntimeFileManifest>,
    val sourceAar: SourceSeparationRuntimeSourceAar,
)

@Serializable
internal data class SourceSeparationRuntimeFileManifest(
    val path: String,
    val byteSize: Long,
    val sha256: String,
    val elf: SourceSeparationRuntimeElfManifest,
)

@Serializable
internal data class SourceSeparationRuntimeElfManifest(
    @SerialName("class") val elfClass: String,
    val machine: String,
    val needed: List<String>,
    val soname: String,
)

@Serializable
internal data class SourceSeparationRuntimeSourceAar(
    val fileName: String,
    val sha256: String,
)

internal data class SourceSeparationRuntimeIdentity(
    val contractSchemaVersion: String,
    val runtimeArtifactVersion: String,
    val releaseVersion: String,
    val abi: String,
    val librarySha256: String,
)

internal data class SourceSeparationCpuRuntimeInstallation(
    val directory: File,
    val manifestFile: File,
    val libraryFile: File,
    val manifest: SourceSeparationCpuRuntimeManifest,
    val identity: SourceSeparationRuntimeIdentity,
)

internal class SourceSeparationRuntimeLocator(
    private val root: File,
    private val processAbi: String,
    private val androidApi: Int,
) {
    fun resolve(
        verifyPayloadHash: Boolean = true,
    ): SourceSeparationCpuRuntimeInstallation {
        val directory = SourceSeparationRuntimeLayout.cpuCurrentDirectory(root, processAbi)
        val manifestFile = File(directory, SourceSeparationRuntimeLayout.MANIFEST_FILE_NAME)
        if (!manifestFile.isFile) {
            throw SourceSeparationRuntimeLoadException(
                reason = SourceSeparationRuntimeFailureReason.MissingRuntime,
                message = "No active LiteRT CPU runtime manifest exists for $processAbi.",
            )
        }
        val canonicalDirectory = directory.canonicalFile
        val canonicalManifest = manifestFile.canonicalFile
        if (canonicalManifest.parentFile != canonicalDirectory) {
            throw SourceSeparationRuntimeLoadException(
                reason = SourceSeparationRuntimeFailureReason.InvalidContract,
                message = "The LiteRT CPU runtime manifest escapes its component directory.",
            )
        }

        val manifest = try {
            RUNTIME_JSON.decodeFromString<SourceSeparationCpuRuntimeManifest>(
                canonicalManifest.readText(),
            )
        } catch (error: Exception) {
            throw SourceSeparationRuntimeLoadException(
                reason = SourceSeparationRuntimeFailureReason.InvalidContract,
                message = "The LiteRT CPU runtime manifest is invalid.",
                cause = error,
            )
        }
        validateManifest(manifest)

        val file = manifest.files.single()
        val libraryFile = File(directory, file.path)
        val canonicalLibrary = libraryFile.canonicalFile
        if (canonicalLibrary.parentFile != canonicalDirectory) {
            throw SourceSeparationRuntimeLoadException(
                reason = SourceSeparationRuntimeFailureReason.InvalidContract,
                message = "The LiteRT CPU runtime library escapes its component directory.",
            )
        }
        if (!canonicalLibrary.isFile) {
            throw SourceSeparationRuntimeLoadException(
                reason = SourceSeparationRuntimeFailureReason.MissingRuntime,
                message = "The LiteRT CPU runtime library is missing.",
            )
        }
        if (canonicalLibrary.length() != file.byteSize) {
            throw SourceSeparationRuntimeLoadException(
                reason = SourceSeparationRuntimeFailureReason.CorruptPayload,
                message = "The LiteRT CPU runtime library size does not match its manifest.",
            )
        }
        if (verifyPayloadHash &&
            !canonicalLibrary.sha256().equals(file.sha256, ignoreCase = true)
        ) {
            throw SourceSeparationRuntimeLoadException(
                reason = SourceSeparationRuntimeFailureReason.CorruptPayload,
                message = "The LiteRT CPU runtime library hash does not match its manifest.",
            )
        }

        return SourceSeparationCpuRuntimeInstallation(
            directory = canonicalDirectory,
            manifestFile = canonicalManifest,
            libraryFile = canonicalLibrary,
            manifest = manifest,
            identity = SourceSeparationRuntimeIdentity(
                contractSchemaVersion = manifest.contractSchemaVersion,
                runtimeArtifactVersion = manifest.runtimeArtifactVersion,
                releaseVersion = manifest.releaseVersion,
                abi = manifest.abi,
                librarySha256 = file.sha256.lowercase(Locale.US),
            ),
        )
    }

    private fun validateManifest(manifest: SourceSeparationCpuRuntimeManifest) {
        if (manifest.schemaVersion != MANIFEST_SCHEMA_VERSION ||
            manifest.contractSchemaVersion != SourceSeparationRuntimeLayout.CONTRACT_SCHEMA_VERSION ||
            manifest.component != SourceSeparationRuntimeLayout.CPU_COMPONENT ||
            manifest.files.size != 1
        ) {
            invalidContract("The LiteRT CPU runtime manifest has an unsupported shape.")
        }
        if (manifest.abi != processAbi) {
            throw SourceSeparationRuntimeLoadException(
                reason = SourceSeparationRuntimeFailureReason.WrongAbi,
                message = "The LiteRT CPU runtime targets ${manifest.abi}, but this process uses $processAbi.",
            )
        }
        if (manifest.androidMinApi > androidApi) {
            throw SourceSeparationRuntimeLoadException(
                reason = SourceSeparationRuntimeFailureReason.UnsupportedApi,
                message = "The LiteRT CPU runtime requires API ${manifest.androidMinApi}, " +
                    "but this device is API $androidApi.",
            )
        }
        if (manifest.baseLiteRtVersion.isBlank() ||
            manifest.runtimeArtifactVersion.isBlank() ||
            manifest.releaseVersion.isBlank() ||
            manifest.sourceAar.fileName.isBlank() ||
            !SHA256_PATTERN.matches(manifest.sourceAar.sha256)
        ) {
            invalidContract("The LiteRT CPU runtime manifest has incomplete identity data.")
        }

        val file = manifest.files.single()
        if (file.path != SourceSeparationRuntimeLayout.LIBRARY_FILE_NAME ||
            file.byteSize <= 0L ||
            !SHA256_PATTERN.matches(file.sha256) ||
            file.elf.elfClass.isBlank() ||
            file.elf.machine.isBlank() ||
            file.elf.soname.isBlank()
        ) {
            invalidContract("The LiteRT CPU runtime manifest has an invalid library record.")
        }
    }

    private fun invalidContract(message: String): Nothing = throw SourceSeparationRuntimeLoadException(
        reason = SourceSeparationRuntimeFailureReason.InvalidContract,
        message = message,
    )

    private companion object {
        const val MANIFEST_SCHEMA_VERSION = 1
        val SHA256_PATTERN = Regex("^[a-fA-F0-9]{64}$")
        val RUNTIME_JSON = Json {
            ignoreUnknownKeys = false
            isLenient = false
        }
    }
}

internal object SourceSeparationRuntimeBootstrap {
    private val lock = Any()
    private var loadedInstallation: SourceSeparationCpuRuntimeInstallation? = null
    private var activeLease: SourceSeparationRuntimeProcessLease? = null

    fun ensureLoaded(context: Context): SourceSeparationCpuRuntimeInstallation =
        ensureLoaded(
            SourceSeparationRuntimeLocator(
                root = SourceSeparationRuntimeLayout.runtimeRoot(context),
                processAbi = currentProcessAbi(),
                androidApi = Build.VERSION.SDK_INT,
            ),
        )

    internal fun ensureLoaded(
        locator: SourceSeparationRuntimeLocator,
    ): SourceSeparationCpuRuntimeInstallation = synchronized(lock) {
        val installation = locator.resolve(verifyPayloadHash = false)
        val loaded = loadedInstallation
        if (loaded != null) {
            if (loaded.identity != installation.identity ||
                loaded.libraryFile != installation.libraryFile
            ) {
                throw SourceSeparationRuntimeLoadException(
                    reason = SourceSeparationRuntimeFailureReason.ConflictingLoaderPath,
                    message = "The source-separation process already loaded another LiteRT runtime.",
                )
            }
            return installation
        }

        val libraryPath = installation.libraryFile.absolutePath
        val configuredPath = LiteRtNativeLibraryLoader.configuredAbsolutePath()
            ?.takeIf { it.isNotBlank() }
        if (configuredPath != null && configuredPath != libraryPath) {
            throw SourceSeparationRuntimeLoadException(
                reason = SourceSeparationRuntimeFailureReason.ConflictingLoaderPath,
                message = "LiteRT is already configured for another native library path.",
            )
        }
        val lease = SourceSeparationRuntimeProcessLease.tryAcquire(
            root = installation.directory.parentFile?.parentFile?.parentFile ?:
                error("The LiteRT runtime directory has no runtime root."),
            abi = installation.manifest.abi,
        ) ?: throw SourceSeparationRuntimeLoadException(
            reason = SourceSeparationRuntimeFailureReason.ConflictingLoaderPath,
            message = "The LiteRT runtime is already leased by another process.",
        )
        try {
            LiteRtNativeLibraryLoader.configureAbsolutePath(libraryPath)
            LiteRtNativeLibraryLoader.load()
            check(LiteRtNativeLibraryLoader.isLoaded()) {
                "LiteRT native loader did not report a loaded runtime."
            }
        } catch (error: SourceSeparationRuntimeLoadException) {
            lease.close()
            throw error
        } catch (error: Throwable) {
            lease.close()
            throw SourceSeparationRuntimeLoadException(
                reason = classifyLoadFailure(error),
                message = "Unable to load the verified LiteRT CPU runtime.",
                cause = error,
            )
        }
        activeLease = lease
        loadedInstallation = installation
        installation
    }

    private fun currentProcessAbi(): String {
        val abis = if (Process.is64Bit()) {
            Build.SUPPORTED_64_BIT_ABIS
        } else {
            Build.SUPPORTED_32_BIT_ABIS
        }
        return abis.firstOrNull().orEmpty().ifBlank {
            throw SourceSeparationRuntimeLoadException(
                reason = SourceSeparationRuntimeFailureReason.WrongAbi,
                message = "Android did not report an ABI for the source-separation process.",
            )
        }
    }

    private fun classifyLoadFailure(error: Throwable): SourceSeparationRuntimeFailureReason {
        if (error !is UnsatisfiedLinkError) return SourceSeparationRuntimeFailureReason.LoadFailure
        val message = error.message.orEmpty().lowercase(Locale.US)
        return if ("not found" in message ||
            "cannot locate" in message ||
            "no such file" in message ||
            "needed" in message
        ) {
            SourceSeparationRuntimeFailureReason.MissingDependency
        } else {
            SourceSeparationRuntimeFailureReason.LoadFailure
        }
    }
}
