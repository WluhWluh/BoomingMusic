package com.mardous.booming.separation.model

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

class SourceSeparationModelRepository(
    private val context: Context,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    fun modelState(variant: MdxModelVariant = MdxModelVariant.MDXNET_9482): SourceSeparationModelState {
        migrateLegacyModelIfNeeded(variant)
        val file = modelFile(variant)
        if (!file.isFile || file.length() <= 0L) {
            return SourceSeparationModelState.Missing(variant)
        }

        val metadata = readMetadata(variant)
            ?: createMetadataForExistingModel(variant, file, SourceSeparationModelSource.Unknown)
        val actualSha256 = metadata.actualSha256
        return SourceSeparationModelState.Available(
            variant = variant,
            path = file.absolutePath,
            sizeBytes = file.length(),
            actualSha256 = actualSha256,
            expectedSha256 = variant.expectedSha256,
            source = metadata.source,
            importedDisplayName = metadata.importedDisplayName,
            updatedAtEpochMs = metadata.updatedAtEpochMs,
        )
    }

    fun isModelReady(variant: MdxModelVariant = MdxModelVariant.MDXNET_9482): Boolean {
        return modelState(variant).isUsable
    }

    fun requireModelFile(variant: MdxModelVariant): File {
        migrateLegacyModelIfNeeded(variant)
        val file = modelFile(variant)
        if (!file.isFile || file.length() <= 0L) {
            throw SourceSeparationModelUnavailableException(variant)
        }
        return file
    }

    fun importModel(
        uri: Uri,
        displayName: String? = null,
        variant: MdxModelVariant = MdxModelVariant.MDXNET_9482,
    ): SourceSeparationModelState.Available {
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Could not open model file." }
            return installModel(
                input = input,
                displayName = displayName ?: displayNameForUri(uri),
                source = SourceSeparationModelSource.Imported,
                variant = variant,
            )
        }
    }

    fun downloadPresetModel(
        variant: MdxModelVariant = MdxModelVariant.MDXNET_9482,
    ): SourceSeparationModelState.Available {
        return downloadModel(
            url = PRESET_MODEL_URL,
            source = SourceSeparationModelSource.PresetDownload,
            variant = variant,
        )
    }

    fun downloadModel(
        url: String,
        source: SourceSeparationModelSource = SourceSeparationModelSource.CustomDownload,
        variant: MdxModelVariant = MdxModelVariant.MDXNET_9482,
    ): SourceSeparationModelState.Available {
        val uri = runCatching { URI(url.trim()) }.getOrElse {
            throw IllegalArgumentException("Model URL is invalid.")
        }
        val scheme = uri.scheme?.lowercase(Locale.US)
        require(scheme == "http" || scheme == "https") {
            "Model URL must start with http:// or https://."
        }

        val connection = uri.toURL().openConnection() as? HttpURLConnection
            ?: throw IOException("Could not open model download connection.")
        connection.connectTimeout = DOWNLOAD_CONNECT_TIMEOUT_MS
        connection.readTimeout = DOWNLOAD_READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.requestMethod = "GET"
        return try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IOException("Model download failed: HTTP $responseCode.")
            }
            connection.inputStream.use { input ->
                installModel(
                    input = input,
                    displayName = displayNameForUrl(uri, variant),
                    source = source,
                    variant = variant,
                )
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun installModel(
        input: InputStream,
        displayName: String?,
        source: SourceSeparationModelSource,
        variant: MdxModelVariant,
    ): SourceSeparationModelState.Available {
        val targetDir = modelDir(variant).also { it.mkdirs() }
        val target = modelFile(variant)
        val temp = File(targetDir, "${variant.fileName}.importing")
        val digest = MessageDigest.getInstance("SHA-256")
        var sizeBytes = 0L

        try {
            temp.outputStream().use { output ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    sizeBytes += read
                }
            }
        } catch (error: Throwable) {
            temp.delete()
            throw error
        }

        if (sizeBytes <= 0L) {
            temp.delete()
            throw IOException("Imported model file is empty.")
        }

        val actualSha256 = digest.digest().toHex()
        if (target.exists() && !target.delete()) {
            temp.delete()
            throw IOException("Could not replace existing model file.")
        }
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }

        val now = System.currentTimeMillis()
        val metadata = SourceSeparationModelMetadata(
            variant = variant.name,
            fileName = variant.fileName,
            sizeBytes = target.length(),
            actualSha256 = actualSha256,
            expectedSha256 = variant.expectedSha256,
            source = source,
            importedDisplayName = displayName,
            updatedAtEpochMs = now,
        )
        writeMetadata(variant, metadata)
        return checkNotNull(modelState(variant) as? SourceSeparationModelState.Available)
    }

    fun deleteModel(variant: MdxModelVariant = MdxModelVariant.MDXNET_9482): Boolean {
        val deletedModel = modelFile(variant).deleteIfExists()
        val deletedMetadata = metadataFile(variant).deleteIfExists()
        return deletedModel || deletedMetadata
    }

    private fun migrateLegacyModelIfNeeded(variant: MdxModelVariant) {
        val target = modelFile(variant)
        if (target.isFile && target.length() > 0L) return

        val legacy = File(context.filesDir, variant.fileName)
        if (!legacy.isFile || legacy.length() <= 0L) return

        val targetDir = modelDir(variant).also { it.mkdirs() }
        if (!legacy.renameTo(target)) {
            legacy.copyTo(target, overwrite = true)
            legacy.delete()
        }
        createMetadataForExistingModel(
            variant = variant,
            file = target,
            source = SourceSeparationModelSource.LegacyLocal,
        )
    }

    private fun createMetadataForExistingModel(
        variant: MdxModelVariant,
        file: File,
        source: SourceSeparationModelSource,
    ): SourceSeparationModelMetadata {
        val metadata = SourceSeparationModelMetadata(
            variant = variant.name,
            fileName = variant.fileName,
            sizeBytes = file.length(),
            actualSha256 = file.sha256Hex(),
            expectedSha256 = variant.expectedSha256,
            source = source,
            importedDisplayName = null,
            updatedAtEpochMs = file.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis(),
        )
        writeMetadata(variant, metadata)
        return metadata
    }

    private fun readMetadata(variant: MdxModelVariant): SourceSeparationModelMetadata? {
        val file = metadataFile(variant)
        if (!file.isFile) return null
        return try {
            json.decodeFromString(SourceSeparationModelMetadata.serializer(), file.readText())
        } catch (_: SerializationException) {
            null
        } catch (_: IOException) {
            null
        }
    }

    private fun writeMetadata(
        variant: MdxModelVariant,
        metadata: SourceSeparationModelMetadata,
    ) {
        val file = metadataFile(variant)
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(
            json.encodeToString(SourceSeparationModelMetadata.serializer(), metadata),
        )
        if (file.exists() && !file.delete()) {
            temp.delete()
            throw IOException("Could not replace model metadata.")
        }
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }

    private fun modelFile(variant: MdxModelVariant): File {
        return File(modelDir(variant), variant.fileName)
    }

    private fun metadataFile(variant: MdxModelVariant): File {
        return File(modelDir(variant), METADATA_FILE_NAME)
    }

    private fun modelDir(variant: MdxModelVariant): File {
        return File(File(context.filesDir, MODEL_ROOT_DIR), variant.outputTag)
    }

    private fun File.deleteIfExists(): Boolean {
        return if (exists()) delete() else false
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

    private fun displayNameForUri(uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            }
        }.getOrNull()
    }

    private fun displayNameForUrl(uri: URI, variant: MdxModelVariant): String {
        return uri.path
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
            ?: variant.fileName
    }

    private fun ByteArray.toHex(): String {
        return joinToString("") { "%02x".format(Locale.US, it) }
    }

    companion object {
        const val PRESET_MODEL_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/source-separation-models/UVR_MDXNET_9482.onnx"

        private const val MODEL_ROOT_DIR = "source-separation/models"
        private const val METADATA_FILE_NAME = "manifest.json"
        private const val COPY_BUFFER_BYTES = 256 * 1024
        private const val DOWNLOAD_CONNECT_TIMEOUT_MS = 15_000
        private const val DOWNLOAD_READ_TIMEOUT_MS = 60_000
    }
}

class SourceSeparationModelUnavailableException(
    val variant: MdxModelVariant,
) : IllegalStateException("Source separation model is not installed: ${variant.fileName}")

sealed class SourceSeparationModelState {
    abstract val variant: MdxModelVariant
    val isUsable: Boolean
        get() = this is Available

    data class Missing(
        override val variant: MdxModelVariant,
    ) : SourceSeparationModelState()

    data class Available(
        override val variant: MdxModelVariant,
        val path: String,
        val sizeBytes: Long,
        val actualSha256: String?,
        val expectedSha256: String,
        val source: SourceSeparationModelSource,
        val importedDisplayName: String?,
        val updatedAtEpochMs: Long,
    ) : SourceSeparationModelState() {
        val hashMatchesExpected: Boolean?
            get() = actualSha256?.equals(expectedSha256, ignoreCase = true)
    }
}

@Serializable
data class SourceSeparationModelMetadata(
    val variant: String,
    val fileName: String,
    val sizeBytes: Long,
    val actualSha256: String,
    val expectedSha256: String,
    val source: SourceSeparationModelSource,
    val importedDisplayName: String? = null,
    val updatedAtEpochMs: Long,
)

@Serializable
enum class SourceSeparationModelSource {
    Imported,
    PresetDownload,
    CustomDownload,
    LegacyLocal,
    Unknown,
}
