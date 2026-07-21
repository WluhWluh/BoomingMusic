package com.mardous.booming.separation.model.preset

import com.mardous.booming.separation.model.AndroidMdxRuntimePlatformProvider
import com.mardous.booming.separation.model.MdxRuntimePlatformProvider
import com.mardous.booming.separation.model.contract.ContractArtifact
import com.mardous.booming.separation.model.contract.ContractArtifactFormat
import com.mardous.booming.separation.model.contract.ContractDsp
import com.mardous.booming.separation.model.contract.ContractDtype
import com.mardous.booming.separation.model.contract.ContractResidualRule
import com.mardous.booming.separation.model.contract.ContractStem
import com.mardous.booming.separation.model.contract.ContractStemSemantic
import com.mardous.booming.separation.model.contract.ContractTensor
import com.mardous.booming.separation.model.contract.ContractTensorLayout
import com.mardous.booming.separation.model.contract.ContractWindow
import com.mardous.booming.separation.model.contract.PipelineCompatibility
import com.mardous.booming.separation.model.contract.SourceSeparationCustomModelProfile
import com.mardous.booming.separation.model.contract.SourceSeparationModelContractValidator
import com.mardous.booming.separation.model.contract.StemContract
import com.mardous.booming.separation.model.contract.TensorContract
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * Holds one short-lived imported payload while the user supplies a matching
 * sidecar or a manual profile. The original content URI is never retained.
 */
class SourceSeparationPresetImportCoordinator internal constructor(
    private val repository: SourceSeparationPresetRepository,
    private val stagingDirectory: File,
    private val structuralInspector: SourceSeparationPresetStructuralInspector =
        UnavailableSourceSeparationPresetStructuralInspector,
    private val platformProvider: MdxRuntimePlatformProvider = AndroidMdxRuntimePlatformProvider,
) {
    private val lock = Any()
    private var pendingImport: PendingImport? = null

    init {
        stagingDirectory.deleteRecursively()
    }

    fun begin(
        input: InputStream,
        originalFileName: String,
    ): SourceSeparationPresetImportOutcome {
        requireSafeTfliteFileName(originalFileName)
        synchronized(lock) {
            check(pendingImport == null) {
                "Finish or discard the current model import before choosing another file."
            }
        }

        val directory = File(stagingRoot(), UUID.randomUUID().toString())
        val payload = File(directory, originalFileName)
        directory.mkdirs()
        val identity = try {
            copyAndDigest(input, payload, originalFileName)
        } catch (error: Throwable) {
            directory.deleteRecursively()
            throw error
        }
        val pending = PendingImport(
            directory = directory,
            file = payload,
            identity = identity,
        )

        if (repository.hasOfficialArtifact(identity.sha256)) {
            return installKnownOfficial(pending)
        }
        synchronized(lock) {
            check(pendingImport == null) {
                "Another model import started while this file was being prepared."
            }
            pendingImport = pending
        }
        return SourceSeparationPresetImportOutcome.AwaitingMetadata(pending.toInfo())
    }

    fun installWithSidecar(
        sidecarInput: InputStream,
        sidecarFileName: String,
    ): SourceSeparationPresetImportOutcome.Installed {
        val pending = requirePendingImport()
        val sidecar = SourceSeparationPresetSidecar(
            fileName = sidecarFileName,
            contents = sidecarInput.readUtf8AtMost(MAX_SIDECAR_BYTES),
        )
        val installed = pending.file.inputStream().use { input ->
            repository.install(
                input = input,
                originalFileName = pending.identity.fileName,
                origin = SourceSeparationInstalledPresetOrigin.ImportedFile,
                sidecar = sidecar,
            )
        }
        complete(pending)
        return SourceSeparationPresetImportOutcome.Installed(installed)
    }

    fun installWithManualProfile(
        draft: SourceSeparationManualModelProfileDraft,
    ): SourceSeparationPresetImportOutcome.Installed {
        val pending = requirePendingImport()
        val profile = draft.toProfile(pending.identity)
        val inspection = structuralInspector.inspect(
            modelFile = pending.file,
            platform = platformProvider.current(),
        )
        if (inspection is SourceSeparationPresetStructuralInspection.Incompatible) {
            throw SourceSeparationPresetProfileException(inspection.reason)
        }
        val installed = pending.file.inputStream().use { input ->
            repository.install(
                input = input,
                originalFileName = pending.identity.fileName,
                origin = SourceSeparationInstalledPresetOrigin.ImportedFile,
                customProfile = profile,
            )
        }
        complete(pending)
        return SourceSeparationPresetImportOutcome.Installed(
            installed = installed,
            structuralInspection = inspection,
        )
    }

    fun discard(): Boolean {
        val pending = synchronized(lock) {
            pendingImport.also { pendingImport = null }
        } ?: return false
        return pending.directory.deleteRecursively()
    }

    fun pending(): SourceSeparationPendingModelImport? = synchronized(lock) {
        pendingImport?.toInfo()
    }

    private fun installKnownOfficial(
        pending: PendingImport,
    ): SourceSeparationPresetImportOutcome.Installed = try {
        val installed = pending.file.inputStream().use { input ->
            repository.install(
                input = input,
                originalFileName = pending.identity.fileName,
                origin = SourceSeparationInstalledPresetOrigin.ImportedFile,
            )
        }
        SourceSeparationPresetImportOutcome.Installed(installed)
    } finally {
        pending.directory.deleteRecursively()
    }

    private fun requirePendingImport(): PendingImport = synchronized(lock) {
        checkNotNull(pendingImport) { "No imported model is awaiting metadata." }
    }

    private fun complete(pending: PendingImport) {
        synchronized(lock) {
            check(pendingImport?.directory == pending.directory) {
                "The imported model is no longer current."
            }
            pendingImport = null
        }
        pending.directory.deleteRecursively()
    }

    private fun stagingRoot(): File = stagingDirectory.also { it.mkdirs() }

    private fun copyAndDigest(
        input: InputStream,
        target: File,
        fileName: String,
    ): SourceSeparationPendingModelImport {
        val digest = MessageDigest.getInstance("SHA-256")
        var byteSize = 0L
        target.outputStream().use { output ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            input.use { source ->
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    byteSize += read
                }
            }
        }
        require(byteSize > 0L) { "Imported TFLite file is empty." }
        return SourceSeparationPendingModelImport(
            fileName = fileName,
            byteSize = byteSize,
            sha256 = digest.digest().toHex(),
        )
    }

    private fun requireSafeTfliteFileName(fileName: String) {
        require(fileName.endsWith(TFLITE_SUFFIX, ignoreCase = true)) {
            "Imported model must use the .tflite extension."
        }
        require(File(fileName).name == fileName && fileName.isNotBlank()) {
            "Imported model filename is unsafe."
        }
    }

    private fun InputStream.readUtf8AtMost(limit: Int): String {
        val bytes = use { source ->
            val result = ByteArrayOutputStream()
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val read = source.read(buffer)
                if (read < 0) break
                if (result.size() + read > limit) {
                    throw SourceSeparationPresetProfileException(
                        "Model sidecar exceeds the supported size.",
                    )
                }
                result.write(buffer, 0, read)
            }
            result.toByteArray()
        }
        return bytes.toString(Charsets.UTF_8)
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }

    private data class PendingImport(
        val directory: File,
        val file: File,
        val identity: SourceSeparationPendingModelImport,
    ) {
        fun toInfo() = identity
    }

    companion object {
        const val STAGING_DIRECTORY = "source-separation/litert-imports-v1"

        private const val COPY_BUFFER_BYTES = 256 * 1024
        private const val MAX_SIDECAR_BYTES = 1 * 1024 * 1024
        private const val TFLITE_SUFFIX = ".tflite"
    }
}

data class SourceSeparationPendingModelImport(
    val fileName: String,
    val byteSize: Long,
    val sha256: String,
)

sealed interface SourceSeparationPresetImportOutcome {
    data class AwaitingMetadata(
        val pending: SourceSeparationPendingModelImport,
    ) : SourceSeparationPresetImportOutcome

    data class Installed(
        val installed: SourceSeparationInstalledPreset,
        val structuralInspection: SourceSeparationPresetStructuralInspection? = null,
    ) : SourceSeparationPresetImportOutcome
}

data class SourceSeparationManualModelProfileDraft(
    val modelId: String,
    val displayName: String,
    val inputTensorName: String,
    val outputTensorName: String,
    val sampleRate: Int,
    val nFft: Int,
    val hopLength: Int,
    val dimF: Int,
    val dimTPower: Int,
    val modelOutputScale: Double,
    val modelOutputStem: SourceSeparationManualModelStem,
) {
    internal fun toProfile(
        artifact: SourceSeparationPendingModelImport,
    ): SourceSeparationCustomModelProfile {
        val modelTimeFrames = 1 shl dimTPower
        val modelStem = modelOutputStem.toContractStem()
        val residualStem = modelOutputStem.residual().toContractStem()
        return SourceSeparationCustomModelProfile(
            profileSchemaVersion = SourceSeparationModelContractValidator.CUSTOM_PROFILE_SCHEMA_VERSION,
            profileId = "custom-${artifact.sha256.take(16)}",
            modelId = modelId.trim(),
            displayName = displayName.trim(),
            artifact = ContractArtifact(
                fileName = artifact.fileName,
                byteSize = artifact.byteSize,
                sha256 = artifact.sha256,
                format = ContractArtifactFormat.TfliteFlatbuffer,
            ),
            tensorContract = TensorContract(
                input = ContractTensor(
                    name = inputTensorName.trim(),
                    dtype = ContractDtype.Float32,
                    layout = ContractTensorLayout.Nhwc,
                    shape = listOf(1, dimF, modelTimeFrames, 4),
                ),
                output = ContractTensor(
                    name = outputTensorName.trim(),
                    dtype = ContractDtype.Float32,
                    layout = ContractTensorLayout.Nhwc,
                    shape = listOf(1, dimF, modelTimeFrames, 4),
                ),
                batchSize = 1,
                complexChannelCount = 4,
            ),
            dsp = ContractDsp(
                sampleRate = sampleRate,
                channelCount = 2,
                nFft = nFft,
                hopLength = hopLength,
                dimF = dimF,
                dimTPower = dimTPower,
                modelTimeFrames = modelTimeFrames,
                window = ContractWindow.PeriodicHann,
                modelOutputScale = modelOutputScale,
            ),
            stemContract = StemContract(
                modelOutput = modelStem,
                residual = residualStem,
                residualRule = ContractResidualRule.MixtureMinusScaledModelOutput,
            ),
            pipelineCompatibility = PipelineCompatibility(
                pipelineId = SourceSeparationModelContractValidator.PIPELINE_ID,
                minimumVersion = SourceSeparationModelContractValidator.PIPELINE_VERSION,
                maximumVersion = SourceSeparationModelContractValidator.PIPELINE_VERSION,
            ),
            qualityUnverified = true,
        ).also(SourceSeparationModelContractValidator::validateCustomProfile)
    }
}

enum class SourceSeparationManualModelStem {
    Vocals,
    Instrumental,
    ;

    fun residual(): SourceSeparationManualModelStem = when (this) {
        Vocals -> Instrumental
        Instrumental -> Vocals
    }

    fun toContractStem(): ContractStem = when (this) {
        Vocals -> ContractStem(ContractStemSemantic.Vocals, "Vocals")
        Instrumental -> ContractStem(ContractStemSemantic.Instrumental, "Instrumental")
    }
}
