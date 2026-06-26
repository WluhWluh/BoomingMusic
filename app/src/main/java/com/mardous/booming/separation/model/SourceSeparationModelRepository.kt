package com.mardous.booming.separation.model

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.mardous.booming.R
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
            requireNotNull(input) { context.getString(R.string.source_separation_model_open_failed) }
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
        onProgress: (SourceSeparationModelDownloadProgress) -> Unit = {},
    ): SourceSeparationModelState.Available {
        return downloadModel(
            url = PRESET_MODEL_URL,
            source = SourceSeparationModelSource.PresetDownload,
            variant = variant,
            onProgress = onProgress,
        )
    }

    fun downloadModel(
        url: String,
        source: SourceSeparationModelSource = SourceSeparationModelSource.CustomDownload,
        variant: MdxModelVariant = MdxModelVariant.MDXNET_9482,
        onProgress: (SourceSeparationModelDownloadProgress) -> Unit = {},
    ): SourceSeparationModelState.Available {
        val uri = runCatching { URI(url.trim()) }.getOrElse {
            throw IllegalArgumentException(
                context.getString(R.string.source_separation_model_url_invalid),
                it,
            )
        }
        return downloadModelWithFallback(
            primaryUri = uri,
            source = source,
            variant = variant,
            onProgress = onProgress,
        )
    }

    private fun downloadModelWithFallback(
        primaryUri: URI,
        source: SourceSeparationModelSource,
        variant: MdxModelVariant,
        onProgress: (SourceSeparationModelDownloadProgress) -> Unit,
    ): SourceSeparationModelState.Available {
        require(primaryUri.scheme?.equals("http", ignoreCase = true) == true ||
                primaryUri.scheme?.equals("https", ignoreCase = true) == true) {
            context.getString(R.string.source_separation_model_url_scheme_invalid)
        }

        val attempts = buildList {
            add(SourceSeparationDownloadAttempt(primaryUri, usingMirror = false))
            primaryUri.toGhfastMirrorUriIfNeeded()?.let { mirrorUri ->
                add(SourceSeparationDownloadAttempt(mirrorUri, usingMirror = true))
            }
        }

        var lastError: Throwable? = null
        for (attempt in attempts) {
            try {
                return downloadModelAttempt(
                    uri = attempt.uri,
                    source = source,
                    variant = variant,
                    usingMirror = attempt.usingMirror,
                    onProgress = onProgress,
                )
            } catch (error: Throwable) {
                lastError = error
                if (!attempt.usingMirror && error.isRetryableDownloadError()) {
                    onProgress(
                        SourceSeparationModelDownloadProgress(
                            sourceUrl = attempt.uri.toString(),
                            usingMirror = true,
                            downloadedBytes = 0L,
                            totalBytes = null,
                            message = context.getString(
                                R.string.source_separation_model_download_retrying_mirror
                            ),
                        ),
                    )
                    continue
                }
                throw error
            }
        }
        throw lastError ?: IOException(
            context.getString(R.string.source_separation_model_download_failed)
        )
    }

    private fun downloadModelAttempt(
        uri: URI,
        source: SourceSeparationModelSource,
        variant: MdxModelVariant,
        usingMirror: Boolean,
        onProgress: (SourceSeparationModelDownloadProgress) -> Unit,
    ): SourceSeparationModelState.Available {
        val connection = uri.toURL().openConnection() as? HttpURLConnection
            ?: throw IOException(
                context.getString(R.string.source_separation_model_download_connection_failed)
            )
        connection.connectTimeout = DOWNLOAD_CONNECT_TIMEOUT_MS
        connection.readTimeout = DOWNLOAD_READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept-Encoding", "identity")
        return try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IOException(
                    context.getString(
                        R.string.source_separation_model_download_http_failed,
                        responseCode,
                    )
                )
            }
            val totalBytes = connection.contentLengthLong.takeIf { it > 0L }
            connection.inputStream.use { input ->
                installModel(
                    input = input,
                    displayName = displayNameForUrl(uri, variant),
                    source = source,
                    variant = variant,
                    downloadUrl = uri.toString(),
                    usingMirror = usingMirror,
                    totalBytes = totalBytes,
                    onProgress = onProgress,
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
        downloadUrl: String? = null,
        usingMirror: Boolean = false,
        totalBytes: Long? = null,
        onProgress: (SourceSeparationModelDownloadProgress) -> Unit = {},
    ): SourceSeparationModelState.Available {
        val targetDir = modelDir(variant).also { it.mkdirs() }
        val target = modelFile(variant)
        val temp = File(targetDir, "${variant.fileName}.importing")
        val digest = MessageDigest.getInstance("SHA-256")
        var sizeBytes = 0L
        val startedAtMs = System.currentTimeMillis()

        try {
            if (downloadUrl != null) {
                onProgress(
                    SourceSeparationModelDownloadProgress(
                        sourceUrl = downloadUrl,
                        usingMirror = usingMirror,
                        downloadedBytes = 0L,
                        totalBytes = totalBytes,
                        message = if (usingMirror) {
                            context.getString(R.string.source_separation_model_downloading_mirror)
                        } else {
                            context.getString(R.string.source_separation_model_downloading)
                        },
                    ),
                )
            }
            temp.outputStream().use { output ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    sizeBytes += read
                    if (downloadUrl != null) {
                        onProgress(
                            SourceSeparationModelDownloadProgress(
                                sourceUrl = downloadUrl,
                                usingMirror = usingMirror,
                                downloadedBytes = sizeBytes,
                                totalBytes = totalBytes,
                                message = if (usingMirror) {
                                    context.getString(
                                        R.string.source_separation_model_downloading_mirror
                                    )
                                } else {
                                    context.getString(R.string.source_separation_model_downloading)
                                },
                            ),
                        )
                        if (!usingMirror && shouldFallbackToMirror(sizeBytes, totalBytes, startedAtMs)) {
                            throw SlowDownloadException(downloadUrl, sizeBytes, totalBytes)
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            temp.delete()
            throw error
        }

        if (sizeBytes <= 0L) {
            temp.delete()
            throw IOException(context.getString(R.string.source_separation_model_import_empty))
        }

        val actualSha256 = digest.digest().toHex()
        if (target.exists() && !target.delete()) {
            temp.delete()
            throw IOException(context.getString(R.string.source_separation_model_replace_failed))
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
            throw IOException(
                context.getString(R.string.source_separation_model_metadata_replace_failed)
            )
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

    private fun URI.toGhfastMirrorUriIfNeeded(): URI? {
        val host = host?.lowercase(Locale.US) ?: return null
        if (host != "github.com" && host != "www.github.com") return null
        if (host == "ghfast.top") return null
        val rawPath = rawPath?.takeIf { it.isNotBlank() } ?: path ?: ""
        val rawQuery = rawQuery?.let { "?$it" }.orEmpty()
        val rawFragment = rawFragment?.let { "#$it" }.orEmpty()
        return URI("https://ghfast.top/$host$rawPath$rawQuery$rawFragment")
    }

    private fun shouldFallbackToMirror(
        downloadedBytes: Long,
        totalBytes: Long?,
        startedAtMs: Long,
    ): Boolean {
        val elapsedMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(1L)
        if (elapsedMs < MIRROR_FALLBACK_CHECK_AFTER_MS) return false
        val bytesPerSecond = downloadedBytes * 1000L / elapsedMs
        val minExpectedBytes = totalBytes?.let {
            maxOf(MIRROR_FALLBACK_MIN_BYTES, it / MIRROR_FALLBACK_MIN_RATIO_DENOMINATOR)
        } ?: MIRROR_FALLBACK_MIN_BYTES
        return bytesPerSecond < MIRROR_FALLBACK_MIN_BYTES_PER_SECOND &&
            downloadedBytes < minExpectedBytes
    }

    private fun Throwable.isRetryableDownloadError(): Boolean {
        return this is IOException || this is SlowDownloadException
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
        private const val MIRROR_FALLBACK_CHECK_AFTER_MS = 8_000L
        private const val MIRROR_FALLBACK_MIN_BYTES_PER_SECOND = 64L * 1024L
        private const val MIRROR_FALLBACK_MIN_BYTES = 512L * 1024L
        private const val MIRROR_FALLBACK_MIN_RATIO_DENOMINATOR = 10L
    }
}

private data class SourceSeparationDownloadAttempt(
    val uri: URI,
    val usingMirror: Boolean,
)

class SourceSeparationModelUnavailableException(
    val variant: MdxModelVariant,
) : IllegalStateException("Source separation model is not installed: ${variant.fileName}")

class SourceSeparationModelLoadException(
    val variant: MdxModelVariant,
    cause: Throwable,
) : IllegalStateException(
    "The installed source separation model could not be loaded. " +
            "Import or download a valid ONNX model, then try again.",
    cause,
)

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

data class SourceSeparationModelDownloadProgress(
    val sourceUrl: String,
    val usingMirror: Boolean,
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val message: String? = null,
)

private class SlowDownloadException(
    val url: String,
    val downloadedBytes: Long,
    val totalBytes: Long?,
) : IOException("Model download is too slow: $url")
