package com.mardous.booming.separation.model.contract

import com.mardous.booming.separation.delivery.ModelDeliveryProvider
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * Installs a verified multi-stem Release pair without exposing it to the
 * existing two-stem preset repository. Product execution can adopt this store
 * after the Demucs scheduler/cache/playback gates are complete.
 */
class SourceSeparationMultiStemModelStore internal constructor(
    private val rootDirectory: File,
    private val catalog: SourceSeparationReleaseCatalog,
    private val provider: ModelDeliveryProvider,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()

    fun install(
        modelId: String,
        onProgress: (SourceSeparationMultiStemInstallProgress) -> Unit = {},
    ): SourceSeparationInstalledMultiStemModel {
        val pair = SourceSeparationReleaseCatalogValidator.resolveMultistem(catalog, modelId)
        installed(modelId)?.let { return it }
        require(provider.supports(pair.artifact) && provider.supports(pair.sidecar)) {
            "The configured delivery provider cannot acquire the multi-stem Release pair."
        }

        val staging = File(stagingRoot(), UUID.randomUUID().toString()).also { it.mkdirs() }
        val modelFile = File(staging, pair.artifact.artifactId)
        val sidecarFile = File(staging, pair.sidecar.artifactId)
        try {
            acquireToFile(pair.artifact, modelFile) { bytes ->
                onProgress(
                    SourceSeparationMultiStemInstallProgress(
                        modelId,
                        SourceSeparationMultiStemInstallProgressKind.Model,
                        bytes,
                    ),
                )
            }
            acquireToFile(pair.sidecar, sidecarFile) { bytes ->
                onProgress(
                    SourceSeparationMultiStemInstallProgress(
                        modelId,
                        SourceSeparationMultiStemInstallProgressKind.Sidecar,
                        bytes,
                    ),
                )
            }
            val contract = sidecarFile.inputStream().use { input ->
                SourceSeparationMultiTensorExecutableContractLoader.load(
                    input.bufferedReader().use { it.readText() },
                )
            }
            validatePair(pair, contract)
            val record = SourceSeparationInstalledMultiStemModelRecord(
                schemaVersion = INSTALL_SCHEMA_VERSION,
                modelId = pair.entry.modelId,
                displayName = pair.entry.displayName,
                modelFileName = modelFile.name,
                sidecarFileName = sidecarFile.name,
                modelByteSize = pair.artifact.expectedByteSize!!,
                modelSha256 = pair.artifact.expectedSha256.lowercase(),
                contractId = contract.modelContract.contractId,
                pipelineId = contract.modelContract.pipelineContract.pipelineId,
                installedAtEpochMs = clock(),
            )
            File(staging, INSTALL_RECORD_FILE_NAME).writeText(
                SourceSeparationModelMetadata.json.encodeToString(record),
            )

            return synchronized(lock) {
                val destination = modelDirectory(pair.artifact.expectedSha256)
                readInstalled(destination)?.let { existing ->
                    staging.deleteRecursively()
                    return@synchronized existing
                }
                if (destination.exists()) {
                    throw SourceSeparationMultiStemInstallException(
                        "Installed multi-stem model directory is incomplete.",
                    )
                }
                if (!staging.renameTo(destination)) {
                    throw SourceSeparationMultiStemInstallException(
                        "Unable to publish the multi-stem model atomically.",
                    )
                }
                SourceSeparationInstalledMultiStemModel(
                    modelId = record.modelId,
                    displayName = record.displayName,
                    modelFile = File(destination, record.modelFileName),
                    sidecarFile = File(destination, record.sidecarFileName),
                    modelByteSize = record.modelByteSize,
                    modelSha256 = record.modelSha256,
                    contractId = record.contractId,
                    pipelineId = record.pipelineId,
                    installedAtEpochMs = record.installedAtEpochMs,
                )
            }
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    fun installed(modelId: String): SourceSeparationInstalledMultiStemModel? = synchronized(lock) {
        modelRoot().listFiles()
            ?.asSequence()
            ?.mapNotNull(::readInstalled)
            ?.singleOrNull { it.modelId == modelId }
    }

    fun installedModels(): List<SourceSeparationInstalledMultiStemModel> = synchronized(lock) {
        modelRoot().listFiles()
            ?.asSequence()
            ?.mapNotNull(::readInstalled)
            ?.sortedBy(SourceSeparationInstalledMultiStemModel::modelId)
            ?.toList()
            .orEmpty()
    }

    private fun acquireToFile(
        reference: com.mardous.booming.separation.delivery.SourceSeparationDeliveryReference,
        target: File,
        onProgress: (Long) -> Unit,
    ) {
        provider.acquire(reference).use { payload ->
            require(payload.reference == reference) { "Delivery payload identity changed" }
            require(payload.byteSize == null || payload.byteSize == reference.expectedByteSize) {
                "Delivery payload size does not match its pinned identity"
            }
            copyAndVerify(payload.openStream(), target, reference, onProgress)
        }
    }

    private fun copyAndVerify(
        input: InputStream,
        target: File,
        reference: com.mardous.booming.separation.delivery.SourceSeparationDeliveryReference,
        onProgress: (Long) -> Unit,
    ) {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        input.use { source ->
            target.outputStream().use { output ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    total += read
                    require(reference.expectedByteSize == null || total <= reference.expectedByteSize) {
                        "Delivery payload exceeds its pinned size"
                    }
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    onProgress(total)
                }
            }
        }
        require(total == reference.expectedByteSize) { "Delivery payload size is incomplete" }
        val actual = digest.digest().toHex()
        require(actual.equals(reference.expectedSha256, ignoreCase = true)) {
            "Delivery payload SHA-256 does not match its pinned identity"
        }
    }

    private fun validatePair(
        pair: SourceSeparationReleaseArtifactPair,
        contract: SourceSeparationMultiTensorExecutableContract,
    ) {
        require(contract.modelContract.modelId == pair.entry.modelId) {
            "Sidecar model ID does not match the Release catalog"
        }
        require(contract.modelContract.contractId == pair.entry.contract.contractId) {
            "Sidecar contract ID does not match the Release catalog"
        }
        require(contract.artifact.fileName == pair.artifact.artifactId &&
            contract.artifact.byteSize == pair.artifact.expectedByteSize &&
            contract.artifact.sha256.equals(pair.artifact.expectedSha256, ignoreCase = true)
        ) { "Executable sidecar does not bind to the downloaded model" }
        require(contract.allowedBackends == listOf(MultiTensorExecutableBackend.Cpu)) {
            "The published multi-stem batch is CPU-only"
        }
    }

    private fun stagingRoot(): File = File(rootDirectory, STAGING_DIRECTORY).also { it.mkdirs() }

    private fun modelRoot(): File = rootDirectory.also { it.mkdirs() }

    private fun modelDirectory(sha256: String): File = File(modelRoot(), sha256.lowercase())

    private fun readInstalled(directory: File): SourceSeparationInstalledMultiStemModel? {
        if (!directory.isDirectory) return null
        val recordFile = File(directory, INSTALL_RECORD_FILE_NAME)
        val record = runCatching {
            SourceSeparationModelMetadata.json.decodeFromString<SourceSeparationInstalledMultiStemModelRecord>(
                recordFile.readText(),
            )
        }.getOrNull() ?: return null
        if (!record.isValid() || !File(directory, record.modelFileName).isFile ||
            !File(directory, record.sidecarFileName).isFile
        ) return null
        return SourceSeparationInstalledMultiStemModel(
            modelId = record.modelId,
            displayName = record.displayName,
            modelFile = File(directory, record.modelFileName),
            sidecarFile = File(directory, record.sidecarFileName),
            modelByteSize = record.modelByteSize,
            modelSha256 = record.modelSha256,
            contractId = record.contractId,
            pipelineId = record.pipelineId,
            installedAtEpochMs = record.installedAtEpochMs,
        )
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        const val MODEL_ROOT_DIRECTORY = "source-separation/multistem-models-v1"
        private const val STAGING_DIRECTORY = ".staging"
        private const val INSTALL_RECORD_FILE_NAME = "install.json"
        private const val INSTALL_SCHEMA_VERSION = 1
        private const val COPY_BUFFER_BYTES = 256 * 1024
    }
}

data class SourceSeparationInstalledMultiStemModel(
    val modelId: String,
    val displayName: String,
    val modelFile: File,
    val sidecarFile: File,
    val modelByteSize: Long,
    val modelSha256: String,
    val contractId: String,
    val pipelineId: String,
    val installedAtEpochMs: Long,
)

data class SourceSeparationMultiStemInstallProgress(
    val modelId: String,
    val kind: SourceSeparationMultiStemInstallProgressKind,
    val downloadedBytes: Long,
)

enum class SourceSeparationMultiStemInstallProgressKind {
    Model,
    Sidecar,
}

@Serializable
private data class SourceSeparationInstalledMultiStemModelRecord(
    val schemaVersion: Int,
    val modelId: String,
    val displayName: String,
    val modelFileName: String,
    val sidecarFileName: String,
    val modelByteSize: Long,
    val modelSha256: String,
    val contractId: String,
    val pipelineId: String,
    val installedAtEpochMs: Long,
) {
    fun isValid(): Boolean = schemaVersion == 1 && modelId.isNotBlank() &&
        displayName.isNotBlank() && modelFileName.endsWith(".tflite") &&
        sidecarFileName == "$modelFileName.json" && modelByteSize > 0L &&
        modelSha256.matches(Regex("^[0-9a-f]{64}$")) && contractId.isNotBlank() &&
        pipelineId == SourceSeparationReleaseCatalogMetadata.MULTISTEM_PIPELINE_ID
}

class SourceSeparationMultiStemInstallException(message: String) : IllegalStateException(message)
