package com.mardous.booming.separation.runtime

import android.content.Context
import android.os.Build
import android.os.Process
import io.github.wluhwluh.bss.litert.BssLiteRtRuntime
import java.util.Locale

internal data class SourceSeparationGpuRuntimeCapabilityObservation(
    val available: Boolean,
    val schemaVersion: Int,
    val artifactVersion: String,
    val profileId: String,
    val kernelBatchSize: Int,
    val commandQueueWindowSize: Int,
    val detail: String,
)

internal object SourceSeparationGpuRuntimeBootstrap {
    private val lock = Any()
    private var loadAttempted = false
    private var loadedInstallation: SourceSeparationGpuRuntimeInstallation? = null
    private var observation = unavailable("The downloaded bounded GPU runtime is not loaded.")

    fun ensureLoaded(context: Context) {
        synchronized(lock) {
            if (loadedInstallation != null || loadAttempted) return
            val abi = currentProcessAbi()
            val root = SourceSeparationRuntimeLayout.runtimeRoot(context)
            val installation = runCatching {
                SourceSeparationGpuRuntimeLocator(
                    root = root,
                    processAbi = abi,
                    androidApi = Build.VERSION.SDK_INT,
                ).resolve()
            }.getOrElse { error ->
                observation = unavailable(error.message ?: "The bounded GPU runtime is not installed.")
                return
            }
            val catalogEntry = runCatching {
                SourceSeparationGpuRuntimeCatalogLoader.load(context).entries.singleOrNull {
                    it.abi == abi
                } ?: error("No bounded GPU catalog entry exists for $abi.")
            }.getOrElse { error ->
                observation = unavailable(
                    "The bounded GPU runtime catalog is unavailable: " +
                        (error.message ?: "unknown error"),
                )
                return
            }
            if (!matchesCatalogEntry(installation, catalogEntry)) {
                observation = unavailable(
                    "The downloaded bounded GPU runtime does not match the bundled catalog.",
                )
                return
            }
            val cpu = runCatching {
                SourceSeparationRuntimeLocator(
                    root = root,
                    processAbi = abi,
                    androidApi = Build.VERSION.SDK_INT,
                ).resolve()
            }.getOrElse { error ->
                observation = unavailable(
                    "The bounded GPU runtime requires a valid CPU LiteRT runtime: " +
                        (error.message ?: "unknown error"),
                )
                return
            }
            val cpuRecord = SourceSeparationRuntimeInstallRecordReader.read(root, abi)
            if (cpuRecord == null ||
                cpuRecord.componentId != catalogEntry.requiredCpuComponentId ||
                cpuRecord.componentType != SourceSeparationRuntimeLayout.CPU_COMPONENT ||
                cpuRecord.abi != abi ||
                !cpuRecord.librarySha256.equals(cpu.identity.librarySha256, ignoreCase = true) ||
                !cpu.identity.librarySha256.equals(catalogEntry.requiredCpuLibrarySha256, ignoreCase = true)
            ) {
                observation = unavailable(
                    "The installed CPU LiteRT runtime does not match the GPU dependency.",
                )
                return
            }

            loadAttempted = true
            try {
                val shim = requireNotNull(installation.libraryFiles["libBssOcl.so"])
                val accelerator = requireNotNull(
                    installation.libraryFiles["libLiteRtClGlAccelerator.so"],
                )
                // The accelerator has a DT_NEEDED edge to the shim. Load the shim first.
                BssLiteRtRuntime.loadAbsolutePath(shim.absolutePath)
                System.load(accelerator.absolutePath)
                val capability = BssLiteRtRuntime.queryCapability()
                if (!capability.isAvailable ||
                    capability.schemaVersion != installation.manifest.profile.schemaVersion ||
                    capability.artifactVersion != installation.manifest.runtimeArtifactVersion ||
                    capability.profileId != installation.manifest.profile.profileId ||
                    capability.kernelBatchSize != installation.manifest.profile.kernelBatchSize ||
                    capability.commandQueueWindowSize != installation.manifest.profile.commandQueueWindowSize
                ) {
                    observation = unavailable("The downloaded bounded GPU capability does not match its manifest.")
                    return
                }
                loadedInstallation = installation
                observation = SourceSeparationGpuRuntimeCapabilityObservation(
                    available = true,
                    schemaVersion = capability.schemaVersion,
                    artifactVersion = capability.artifactVersion,
                    profileId = capability.profileId,
                    kernelBatchSize = capability.kernelBatchSize,
                    commandQueueWindowSize = capability.commandQueueWindowSize,
                    detail = "Loaded ${installation.identity.profileId} from verified absolute paths.",
                )
            } catch (error: Throwable) {
                observation = unavailable(
                    "The downloaded bounded GPU runtime could not be loaded: " +
                        (error.message ?: error::class.java.simpleName),
                )
            }
        }
    }

    fun capability(): SourceSeparationGpuRuntimeCapabilityObservation = synchronized(lock) {
        observation
    }

    fun isLoaded(): Boolean = synchronized(lock) { loadedInstallation != null }

    fun installation(): SourceSeparationGpuRuntimeInstallation? = synchronized(lock) {
        loadedInstallation
    }

    private fun matchesCatalogEntry(
        installation: SourceSeparationGpuRuntimeInstallation,
        entry: SourceSeparationGpuRuntimeCatalogEntry,
    ): Boolean {
        val expectedFiles = entry.files.associate { it.path to it.sha256.lowercase(Locale.US) }
        val actualFiles = installation.libraryFiles.mapValues { (_, file) ->
            file.sha256().lowercase(Locale.US)
        }
        val record = SourceSeparationGpuRuntimeInstallRecordReader.read(installation.directory)
            ?: return false
        val recordedFiles = record.fileSha256.mapValues { (_, hash) -> hash.lowercase(Locale.US) }
        return installation.manifest.component == entry.componentType &&
            installation.manifest.abi == entry.abi &&
            installation.manifest.releaseVersion == entry.producerReleaseVersion &&
            installation.manifest.runtimeArtifactVersion == entry.runtimeArtifactVersion &&
            installation.manifest.requiredCore.librarySha256.equals(
                entry.requiredCpuLibrarySha256,
                ignoreCase = true,
            ) &&
            installation.manifest.profile == entry.capability &&
            installation.manifestFile.sha256().equals(entry.innerManifestSha256, ignoreCase = true) &&
            actualFiles == expectedFiles &&
            record.componentId == entry.componentId &&
            record.componentType == entry.componentType &&
            record.producerReleaseTag == entry.producerReleaseTag &&
            record.producerReleaseVersion == entry.producerReleaseVersion &&
            record.runtimeArtifactVersion == entry.runtimeArtifactVersion &&
            record.abi == entry.abi &&
            record.innerManifestSha256.equals(entry.innerManifestSha256, ignoreCase = true) &&
            recordedFiles == expectedFiles
    }

    private fun currentProcessAbi(): String {
        val abis = if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS
        return abis.firstOrNull().orEmpty().ifBlank {
            throw SourceSeparationRuntimeLoadException(
                reason = SourceSeparationRuntimeFailureReason.WrongAbi,
                message = "Android did not report an ABI for the source-separation process.",
            )
        }
    }

    private fun unavailable(detail: String) = SourceSeparationGpuRuntimeCapabilityObservation(
        available = false,
        schemaVersion = 0,
        artifactVersion = "unavailable",
        profileId = "unavailable",
        kernelBatchSize = 0,
        commandQueueWindowSize = 0,
        detail = detail,
    )
}
