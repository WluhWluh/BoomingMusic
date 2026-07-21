package com.mardous.booming.separation.model.preset

import android.content.Context
import android.content.SharedPreferences
import com.mardous.booming.separation.model.MdxRuntimePlatform
import com.mardous.booming.separation.model.contract.ModelFileIdentity
import com.mardous.booming.separation.model.contract.SourceSeparationCustomModelProfile
import com.mardous.booming.separation.model.contract.SourceSeparationModelCatalog
import com.mardous.booming.separation.model.contract.SourceSeparationModelContract
import com.mardous.booming.separation.model.contract.SourceSeparationModelContractException
import com.mardous.booming.separation.model.contract.SourceSeparationModelContractValidator
import com.mardous.booming.separation.model.contract.SourceSeparationModelMetadata
import com.mardous.booming.separation.model.contract.CatalogSupportLevel
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * Stores LiteRT model artifacts independently from the legacy ONNX model path.
 *
 * This repository is deliberately not consulted by the production source
 * separation worker until the Phase 6 cutover. It establishes the model
 * identity, installation, and selection contract without changing the stable
 * ONNX playback path.
 */
class SourceSeparationPresetRepository internal constructor(
    private val rootDirectory: File,
    private val catalog: SourceSeparationModelCatalog,
    private val activeModelStore: SourceSeparationActiveModelStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val customProfileStore: SourceSeparationCustomProfileStore =
        FileSourceSeparationCustomProfileStore(
            File(rootDirectory.parentFile, CUSTOM_PROFILE_ROOT_DIRECTORY.substringAfterLast('/')),
        ),
) {
    constructor(
        context: Context,
        preferences: SharedPreferences,
    ) : this(
        rootDirectory = File(context.filesDir, MODEL_ROOT_DIRECTORY),
        catalog = SourceSeparationModelMetadata.loadBundledCatalog(context),
        activeModelStore = SharedPreferencesSourceSeparationActiveModelStore(preferences),
        customProfileStore = FileSourceSeparationCustomProfileStore(
            File(context.filesDir, CUSTOM_PROFILE_ROOT_DIRECTORY),
        ),
    )

    private val lock = Any()

    fun catalogEntries() = catalog.entries

    internal fun catalogSnapshot(): SourceSeparationModelCatalog = catalog

    /**
     * Resolves the immutable Release asset for one official catalog entry.
     * The caller must use this identity rather than deriving a URL from a
     * filename or following a mutable release endpoint.
     */
    fun officialPreset(modelId: String): SourceSeparationOfficialPreset {
        val entry = catalog.entries.singleOrNull { it.modelId == modelId }
            ?: throw SourceSeparationPresetDownloadException(
                "The requested model is not present in the bundled catalog.",
            )
        val artifact = catalog.artifacts.singleOrNull { it.artifactId == entry.artifactId }
            ?: throw SourceSeparationPresetDownloadException(
                "The catalog artifact is missing for $modelId.",
            )
        val tflite = artifact.tflite
            ?: throw SourceSeparationPresetDownloadException(
                "The catalog artifact is not available for download: $modelId.",
            )
        val release = tflite.releaseAsset
            ?: throw SourceSeparationPresetDownloadException(
                "The catalog artifact is not pinned to an immutable Release: $modelId.",
            )
        return SourceSeparationOfficialPreset(
            modelId = entry.modelId,
            displayName = entry.displayName,
            artifactId = artifact.artifactId,
            fileName = tflite.fileName,
            byteSize = tflite.byteSize,
            sha256 = tflite.sha256,
            releaseTag = release.tag,
            downloadUrl = release.url,
        )
    }

    fun installedModels(): List<SourceSeparationInstalledPreset> = synchronized(lock) {
        modelRoot().listFiles()
            ?.asSequence()
            ?.filter(File::isDirectory)
            ?.mapNotNull(::readInstalledPreset)
            ?.sortedWith(
                compareBy<SourceSeparationInstalledPreset> { it.displayName.lowercase() }
                    .thenBy { it.sha256 },
            )
            ?.toList()
            .orEmpty()
    }

    fun installedModel(sha256: String): SourceSeparationInstalledPreset? = synchronized(lock) {
        readInstalledPreset(modelDirectory(sha256))
    }

    fun customProfiles(): List<SourceSeparationCustomModelProfile> = customProfileStore.profiles()

    fun saveCustomProfile(profile: SourceSeparationCustomModelProfile) {
        customProfileStore.write(profile)
    }

    fun deleteCustomProfile(profileId: String): Boolean {
        if (activeModelStore.read()?.profileId == profileId) {
            throw SourceSeparationPresetProfileException(
                "The active model profile must be changed before it can be deleted.",
            )
        }
        return customProfileStore.delete(profileId)
    }

    fun activeModel(): SourceSeparationActivePresetState {
        val reference = activeModelStore.read() ?: return SourceSeparationActivePresetState.None
        return SourceSeparationActivePresetState.Reference(
            reference = reference,
            installedModel = installedModel(reference.artifactSha256),
        )
    }

    /**
     * Retains a portable backup reference even when its weights are absent.
     * A later matching install can resolve it, but this method never downloads
     * or silently replaces the current model.
     */
    fun setPendingActiveModel(reference: SourceSeparationActiveModelReference?) {
        reference?.validate()
        activeModelStore.write(reference)
    }

    fun install(
        input: InputStream,
        originalFileName: String,
        displayName: String? = null,
        origin: SourceSeparationInstalledPresetOrigin,
        sidecar: SourceSeparationPresetSidecar? = null,
        customProfile: SourceSeparationCustomModelProfile? = null,
        onProgress: (Long) -> Unit = {},
    ): SourceSeparationInstalledPreset = installInternal(
        input = input,
        originalFileName = originalFileName,
        displayName = displayName,
        origin = origin,
        sidecar = sidecar,
        customProfile = customProfile,
        onProgress = onProgress,
    )

    fun installOfficial(
        modelId: String,
        input: InputStream,
        onProgress: (Long) -> Unit = {},
    ): SourceSeparationInstalledPreset {
        val preset = officialPreset(modelId)
        return installInternal(
            input = input,
            originalFileName = preset.fileName,
            displayName = preset.displayName,
            origin = SourceSeparationInstalledPresetOrigin.OfficialDownload,
            expectedOfficialPreset = preset,
            onProgress = onProgress,
        )
    }

    private fun installInternal(
        input: InputStream,
        originalFileName: String,
        displayName: String? = null,
        origin: SourceSeparationInstalledPresetOrigin,
        sidecar: SourceSeparationPresetSidecar? = null,
        customProfile: SourceSeparationCustomModelProfile? = null,
        expectedOfficialPreset: SourceSeparationOfficialPreset? = null,
        onProgress: (Long) -> Unit = {},
    ): SourceSeparationInstalledPreset {
        requireSafeTfliteFileName(originalFileName)
        val stagingDirectory = File(stagingRoot(), UUID.randomUUID().toString())
        val temporaryPayload = File(stagingDirectory, TEMPORARY_PAYLOAD_NAME)
        stagingDirectory.mkdirs()

        val copied = try {
            copyAndDigest(input, temporaryPayload, onProgress)
        } catch (error: Throwable) {
            stagingDirectory.deleteRecursively()
            throw error
        }
        if (copied.byteSize <= 0L) {
            stagingDirectory.deleteRecursively()
            throw SourceSeparationPresetInstallException("Imported TFLite file is empty.")
        }

        expectedOfficialPreset?.let { expected ->
            if (copied.byteSize != expected.byteSize ||
                !copied.sha256.equals(expected.sha256, ignoreCase = true)
            ) {
                stagingDirectory.deleteRecursively()
                throw SourceSeparationPresetDownloadIntegrityException(
                    expected = expected,
                    actualByteSize = copied.byteSize,
                    actualSha256 = copied.sha256,
                )
            }
        }

        val officialArtifact = catalog.artifacts.singleOrNull {
            it.tflite?.sha256.equals(copied.sha256, ignoreCase = true)
        }
        if (expectedOfficialPreset != null &&
            officialArtifact?.artifactId != expectedOfficialPreset.artifactId
        ) {
            stagingDirectory.deleteRecursively()
            throw SourceSeparationPresetDownloadException(
                "The downloaded model resolved to a different catalog artifact.",
            )
        }
        val binding = try {
            resolveBinding(
                officialArtifactId = officialArtifact?.artifactId,
                originalFileName = originalFileName,
                identity = ModelFileIdentity(
                    fileName = originalFileName,
                    byteSize = copied.byteSize,
                    sha256 = copied.sha256,
                ),
                displayName = displayName,
                sidecar = sidecar,
                customProfile = customProfile,
            )
        } catch (error: Throwable) {
            stagingDirectory.deleteRecursively()
            throw error
        }

        val stagedModel = File(stagingDirectory, binding.fileName)
        if (!temporaryPayload.renameTo(stagedModel)) {
            temporaryPayload.copyTo(stagedModel, overwrite = true)
            temporaryPayload.delete()
        }
        val record = SourceSeparationInstalledPresetRecord(
            schemaVersion = INSTALLED_PRESET_SCHEMA_VERSION,
            modelId = binding.modelId,
            displayName = binding.displayName,
            fileName = binding.fileName,
            byteSize = copied.byteSize,
            sha256 = copied.sha256,
            origin = origin,
            bindingKind = binding.kind,
            contractId = binding.contractId,
            sidecarContract = binding.sidecarContract,
            customProfileId = binding.customProfile?.profileId,
            installedAtEpochMs = clock(),
        )
        writeRecord(File(stagingDirectory, INSTALL_RECORD_FILE_NAME), record)

        return synchronized(lock) {
            val destinationDirectory = modelDirectory(copied.sha256)
            readInstalledPreset(destinationDirectory)?.let { existing ->
                if (existing.bindingKind == SourceSeparationPresetBindingKind.CustomProfile &&
                    customProfile != null
                ) {
                    customProfileStore.write(customProfile)
                }
                stagingDirectory.deleteRecursively()
                return@synchronized existing
            }
            if (destinationDirectory.exists()) {
                stagingDirectory.deleteRecursively()
                throw SourceSeparationPresetInstallException(
                    "Installed model directory is incomplete: ${copied.sha256}",
                )
            }

            if (!stagingDirectory.renameTo(destinationDirectory)) {
                if (!destinationDirectory.mkdirs()) {
                    stagingDirectory.deleteRecursively()
                    throw SourceSeparationPresetInstallException(
                        "Unable to create installed model directory.",
                    )
                }
                stagedModel.copyTo(File(destinationDirectory, binding.fileName), overwrite = false)
                File(stagingDirectory, INSTALL_RECORD_FILE_NAME).copyTo(
                    File(destinationDirectory, INSTALL_RECORD_FILE_NAME),
                    overwrite = false,
                )
                stagingDirectory.deleteRecursively()
            }
            binding.customProfile?.let { profile ->
                try {
                    customProfileStore.write(profile)
                } catch (error: Throwable) {
                    destinationDirectory.deleteRecursively()
                    throw error
                }
            }
            requireNotNull(readInstalledPreset(destinationDirectory)) {
                "Installed model metadata could not be read."
            }
        }
    }

    fun activate(
        sha256: String,
        platform: MdxRuntimePlatform,
        scope: SourceSeparationPresetSelectionScope,
        experimentalConfirmed: Boolean = false,
    ): SourceSeparationActiveModelReference = synchronized(lock) {
        val installed = requireInstalledPreset(sha256)
        val reference = when (installed.bindingKind) {
            SourceSeparationPresetBindingKind.Official -> {
                val eligibility = SourceSeparationPresetActivationResolver.resolve(
                    catalog = catalog,
                    modelId = installed.modelId,
                    platform = platform,
                    scope = scope,
                )
                if (!eligibility.allowed) {
                    throw SourceSeparationPresetActivationException(eligibility)
                }
                val isExperimental = catalog.entries.single { it.modelId == installed.modelId }
                    .supportLevel == CatalogSupportLevel.Experimental
                if ((eligibility.requiresExperimentalConfirmation || isExperimental) &&
                    !experimentalConfirmed
                ) {
                    throw SourceSeparationPresetActivationException(
                        SourceSeparationPresetSelectionEligibility(
                            allowed = false,
                            requiresExperimentalConfirmation = true,
                            blockReason = SourceSeparationPresetSelectionBlockReason
                                .ExperimentalConfirmationRequired,
                            cpuQualification = eligibility.cpuQualification,
                        ),
                    )
                }
                SourceSeparationActiveModelReference(
                    modelId = installed.modelId,
                    artifactSha256 = installed.sha256,
                    contractSchemaVersion = SourceSeparationModelContractValidator
                        .CONTRACT_SCHEMA_VERSION,
                )
            }

            SourceSeparationPresetBindingKind.Sidecar -> {
                requireInternalSelection(scope)
                val contract = requireNotNull(installed.sidecarContract)
                SourceSeparationModelContractValidator.validateContract(contract)
                SourceSeparationActiveModelReference(
                    modelId = contract.modelId,
                    artifactSha256 = installed.sha256,
                    contractSchemaVersion = contract.contractSchemaVersion,
                )
            }

            SourceSeparationPresetBindingKind.CustomProfile -> {
                requireInternalSelection(scope)
                val profile = requireNotNull(installed.customProfile)
                SourceSeparationModelContractValidator.validateCustomProfile(profile)
                SourceSeparationActiveModelReference(
                    modelId = profile.modelId,
                    artifactSha256 = installed.sha256,
                    contractSchemaVersion = profile.profileSchemaVersion,
                    profileId = profile.profileId,
                )
            }
        }
        activeModelStore.write(reference)
        return reference
    }

    fun delete(sha256: String): Boolean = synchronized(lock) {
        val active = activeModelStore.read()
        if (active?.artifactSha256.equals(sha256, ignoreCase = true)) {
            throw SourceSeparationPresetDeletionException(
                "The active model must be changed before it can be deleted.",
            )
        }
        val directory = modelDirectory(sha256)
        if (!directory.exists()) return false
        if (!directory.deleteRecursively()) {
            throw SourceSeparationPresetDeletionException("Unable to delete installed model.")
        }
        true
    }

    fun requireInstalledPreset(sha256: String): SourceSeparationInstalledPreset = synchronized(lock) {
        val installed = installedModel(sha256)
            ?: throw SourceSeparationPresetInstallException("Model is not installed: $sha256")
        val actualHash = installed.file.sha256Hex()
        if (!actualHash.equals(installed.sha256, ignoreCase = true)) {
            throw SourceSeparationPresetInstallException(
                "Installed model hash does not match its record: $sha256",
            )
        }
        installed
    }

    private fun resolveBinding(
        officialArtifactId: String?,
        originalFileName: String,
        identity: ModelFileIdentity,
        displayName: String?,
        sidecar: SourceSeparationPresetSidecar?,
        customProfile: SourceSeparationCustomModelProfile?,
    ): ResolvedPresetBinding {
        if (officialArtifactId != null) {
            val entry = catalog.entries.singleOrNull { it.artifactId == officialArtifactId }
                ?: throw SourceSeparationPresetInstallException("Catalog entry is missing.")
            val artifact = catalog.artifacts.single { it.artifactId == officialArtifactId }.tflite
                ?: throw SourceSeparationPresetInstallException("Catalog artifact is missing.")
            return ResolvedPresetBinding(
                modelId = entry.modelId,
                displayName = entry.displayName,
                fileName = artifact.fileName,
                kind = SourceSeparationPresetBindingKind.Official,
                contractId = entry.contractId,
            )
        }
        if (sidecar != null) {
            val contract = SourceSeparationModelMetadata.decodeContract(sidecar.contents)
            SourceSeparationModelContractValidator.validateSidecarBinding(
                model = identity,
                sidecarFileName = sidecar.fileName,
                contract = contract,
            )
            return ResolvedPresetBinding(
                modelId = contract.modelId,
                displayName = contract.displayName,
                fileName = originalFileName,
                kind = SourceSeparationPresetBindingKind.Sidecar,
                contractId = contract.contractId,
                sidecarContract = contract,
            )
        }
        val profile = SourceSeparationModelContractValidator.requireCustomImportProfile(
            model = identity,
            profile = customProfile,
        )
        return ResolvedPresetBinding(
            modelId = profile.modelId,
            displayName = profile.displayName,
            fileName = originalFileName,
            kind = SourceSeparationPresetBindingKind.CustomProfile,
            customProfile = profile,
        )
    }

    private fun requireInternalSelection(scope: SourceSeparationPresetSelectionScope) {
        if (scope != SourceSeparationPresetSelectionScope.InternalValidation) {
            throw SourceSeparationPresetActivationException(
                SourceSeparationPresetSelectionEligibility(
                    allowed = false,
                    requiresExperimentalConfirmation = false,
                    blockReason = SourceSeparationPresetSelectionBlockReason
                        .UnsupportedCustomModel,
                ),
            )
        }
    }

    private fun readInstalledPreset(directory: File): SourceSeparationInstalledPreset? {
        if (!directory.isDirectory) return null
        val record = readRecord(File(directory, INSTALL_RECORD_FILE_NAME)) ?: return null
        if (!record.isValid()) return null
        val modelFile = File(directory, record.fileName)
        if (!modelFile.isFile || modelFile.length() != record.byteSize) return null
        return SourceSeparationInstalledPreset(
            modelId = record.modelId,
            displayName = record.displayName,
            file = modelFile,
            byteSize = record.byteSize,
            sha256 = record.sha256,
            origin = record.origin,
            bindingKind = record.bindingKind,
            contractId = record.contractId,
            sidecarContract = record.sidecarContract,
            customProfile = record.customProfileId?.let(customProfileStore::profile),
            installedAtEpochMs = record.installedAtEpochMs,
        )
    }

    private fun readRecord(file: File): SourceSeparationInstalledPresetRecord? {
        if (!file.isFile) return null
        return try {
            SourceSeparationModelMetadata.json.decodeFromString(
                SourceSeparationInstalledPresetRecord.serializer(),
                file.readText(),
            )
        } catch (_: SerializationException) {
            null
        } catch (_: IOException) {
            null
        }
    }

    private fun writeRecord(file: File, record: SourceSeparationInstalledPresetRecord) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(
            SourceSeparationModelMetadata.json.encodeToString(
                SourceSeparationInstalledPresetRecord.serializer(),
                record,
            ),
        )
        if (file.exists() && !file.delete()) {
            temporary.delete()
            throw SourceSeparationPresetInstallException("Unable to replace model metadata.")
        }
        if (!temporary.renameTo(file)) {
            temporary.copyTo(file, overwrite = true)
            temporary.delete()
        }
    }

    private fun copyAndDigest(
        input: InputStream,
        target: File,
        onProgress: (Long) -> Unit,
    ): CopiedModel {
        val digest = MessageDigest.getInstance("SHA-256")
        var byteSize = 0L
        target.outputStream().use { output ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                output.write(buffer, 0, read)
                digest.update(buffer, 0, read)
                byteSize += read
                onProgress(byteSize)
            }
        }
        return CopiedModel(
            byteSize = byteSize,
            sha256 = digest.digest().toHex(),
        )
    }

    private fun File.sha256Hex(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }

    private fun modelRoot(): File = rootDirectory.also { it.mkdirs() }

    private fun stagingRoot(): File = File(modelRoot(), STAGING_DIRECTORY).also { it.mkdirs() }

    private fun modelDirectory(sha256: String): File {
        require(SHA256_PATTERN.matches(sha256)) { "Model SHA-256 is invalid." }
        return File(modelRoot(), sha256.lowercase())
    }

    private fun requireSafeTfliteFileName(fileName: String) {
        require(fileName.endsWith(TFLITE_SUFFIX, ignoreCase = true)) {
            "Imported model must use the .tflite extension."
        }
        require(File(fileName).name == fileName && fileName.isNotBlank()) {
            "Imported model filename is unsafe."
        }
    }

    private data class CopiedModel(
        val byteSize: Long,
        val sha256: String,
    )

    private data class ResolvedPresetBinding(
        val modelId: String,
        val displayName: String,
        val fileName: String,
        val kind: SourceSeparationPresetBindingKind,
        val contractId: String? = null,
        val sidecarContract: SourceSeparationModelContract? = null,
        val customProfile: SourceSeparationCustomModelProfile? = null,
    )

    companion object {
        const val MODEL_ROOT_DIRECTORY = "source-separation/litert-models-v1"
        const val CUSTOM_PROFILE_ROOT_DIRECTORY = "source-separation/model-profiles-v1"

        private const val STAGING_DIRECTORY = ".staging"
        private const val TEMPORARY_PAYLOAD_NAME = "model.importing"
        private const val INSTALL_RECORD_FILE_NAME = "install.json"
        private const val INSTALLED_PRESET_SCHEMA_VERSION = 1
        private const val COPY_BUFFER_BYTES = 256 * 1024
        private const val TFLITE_SUFFIX = ".tflite"
        private val SHA256_PATTERN = Regex("^[0-9a-fA-F]{64}$")
    }
}

data class SourceSeparationPresetSidecar(
    val fileName: String,
    val contents: String,
)

data class SourceSeparationOfficialPreset(
    val modelId: String,
    val displayName: String,
    val artifactId: String,
    val fileName: String,
    val byteSize: Long,
    val sha256: String,
    val releaseTag: String,
    val downloadUrl: String,
)

data class SourceSeparationInstalledPreset(
    val modelId: String,
    val displayName: String,
    val file: File,
    val byteSize: Long,
    val sha256: String,
    val origin: SourceSeparationInstalledPresetOrigin,
    val bindingKind: SourceSeparationPresetBindingKind,
    val contractId: String?,
    val sidecarContract: SourceSeparationModelContract?,
    val customProfile: SourceSeparationCustomModelProfile?,
    val installedAtEpochMs: Long,
)

@Serializable
enum class SourceSeparationInstalledPresetOrigin {
    OfficialDownload,
    ImportedFile,
    CustomDownload,
}

@Serializable
enum class SourceSeparationPresetBindingKind {
    Official,
    Sidecar,
    CustomProfile,
}

@Serializable
internal data class SourceSeparationInstalledPresetRecord(
    val schemaVersion: Int,
    val modelId: String,
    val displayName: String,
    val fileName: String,
    val byteSize: Long,
    val sha256: String,
    val origin: SourceSeparationInstalledPresetOrigin,
    val bindingKind: SourceSeparationPresetBindingKind,
    val contractId: String? = null,
    val sidecarContract: SourceSeparationModelContract? = null,
    val customProfileId: String? = null,
    val installedAtEpochMs: Long,
) {
    fun isValid(): Boolean =
        schemaVersion == 1 &&
            modelId.isNotBlank() &&
            displayName.isNotBlank() &&
            File(fileName).name == fileName &&
            fileName.endsWith(".tflite", ignoreCase = true) &&
            byteSize > 0L &&
            SHA256_PATTERN.matches(sha256) &&
            (bindingKind != SourceSeparationPresetBindingKind.CustomProfile ||
                !customProfileId.isNullOrBlank()) &&
            installedAtEpochMs > 0L

    companion object {
        private val SHA256_PATTERN = Regex("^[0-9a-fA-F]{64}$")
    }
}

data class SourceSeparationActiveModelReference(
    val modelId: String,
    val artifactSha256: String,
    val contractSchemaVersion: Int,
    val profileId: String? = null,
) {
    fun validate() {
        require(MODEL_ID_PATTERN.matches(modelId)) { "Active model ID is invalid." }
        require(SHA256_PATTERN.matches(artifactSha256)) { "Active model SHA-256 is invalid." }
        require(contractSchemaVersion > 0) { "Active model contract schema is invalid." }
        require(profileId == null || profileId.isNotBlank()) { "Active model profile ID is invalid." }
    }

    private companion object {
        private val MODEL_ID_PATTERN = Regex("^[a-z0-9_]+$")
        private val SHA256_PATTERN = Regex("^[0-9a-fA-F]{64}$")
    }
}

sealed class SourceSeparationActivePresetState {
    data object None : SourceSeparationActivePresetState()

    data class Reference(
        val reference: SourceSeparationActiveModelReference,
        val installedModel: SourceSeparationInstalledPreset?,
    ) : SourceSeparationActivePresetState()
}

interface SourceSeparationActiveModelStore {
    fun read(): SourceSeparationActiveModelReference?
    fun write(reference: SourceSeparationActiveModelReference?)
}

private class SharedPreferencesSourceSeparationActiveModelStore(
    private val preferences: SharedPreferences,
) : SourceSeparationActiveModelStore {
    override fun read(): SourceSeparationActiveModelReference? {
        val modelId = preferences.getString(KEY_MODEL_ID, null) ?: return null
        val artifactSha256 = preferences.getString(KEY_ARTIFACT_SHA256, null) ?: return null
        val schemaVersion = preferences.getInt(KEY_CONTRACT_SCHEMA_VERSION, 0)
        val profileId = preferences.getString(KEY_PROFILE_ID, null)
        return runCatching {
            SourceSeparationActiveModelReference(
                modelId = modelId,
                artifactSha256 = artifactSha256,
                contractSchemaVersion = schemaVersion,
                profileId = profileId,
            ).also(SourceSeparationActiveModelReference::validate)
        }.getOrNull()
    }

    override fun write(reference: SourceSeparationActiveModelReference?) {
        preferences.edit().apply {
            if (reference == null) {
                remove(KEY_MODEL_ID)
                remove(KEY_ARTIFACT_SHA256)
                remove(KEY_CONTRACT_SCHEMA_VERSION)
                remove(KEY_PROFILE_ID)
            } else {
                reference.validate()
                putString(KEY_MODEL_ID, reference.modelId)
                putString(KEY_ARTIFACT_SHA256, reference.artifactSha256.lowercase())
                putInt(KEY_CONTRACT_SCHEMA_VERSION, reference.contractSchemaVersion)
                if (reference.profileId == null) remove(KEY_PROFILE_ID) else {
                    putString(KEY_PROFILE_ID, reference.profileId)
                }
            }
        }.apply()
    }

    private companion object {
        const val KEY_MODEL_ID = "source_separation.active_model.id"
        const val KEY_ARTIFACT_SHA256 = "source_separation.active_model.sha256"
        const val KEY_CONTRACT_SCHEMA_VERSION = "source_separation.active_model.contract_schema"
        const val KEY_PROFILE_ID = "source_separation.active_model.profile_id"
    }
}

class SourceSeparationPresetInstallException(message: String) : IllegalStateException(message)

open class SourceSeparationPresetDownloadException(message: String) : IllegalStateException(message)

class SourceSeparationPresetDownloadIntegrityException(
    val expected: SourceSeparationOfficialPreset,
    val actualByteSize: Long,
    val actualSha256: String,
) : SourceSeparationPresetDownloadException(
    "Downloaded model does not match the catalog asset for ${expected.modelId}.",
)

class SourceSeparationPresetDeletionException(message: String) : IllegalStateException(message)

class SourceSeparationPresetActivationException(
    val eligibility: SourceSeparationPresetSelectionEligibility,
) : IllegalStateException(eligibility.blockReason?.name ?: "Preset activation failed")
