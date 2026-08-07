package com.mardous.booming.separation.model.contract

import com.mardous.booming.separation.delivery.ModelDeliveryProvider
import java.io.File

/** Product-facing entry point for the pinned GitHub multi-stem download path. */
class SourceSeparationMultiStemReleaseInstaller(
    private val catalogRepository: SourceSeparationReleaseCatalogRepository,
    private val modelRootDirectory: File,
    private val provider: ModelDeliveryProvider,
) {
    fun install(
        modelId: String,
        onProgress: (SourceSeparationMultiStemInstallProgress) -> Unit = {},
    ): SourceSeparationInstalledMultiStemModel {
        val catalog = catalogRepository.current()
        return SourceSeparationMultiStemModelStore(
            rootDirectory = modelRootDirectory,
            catalog = catalog,
            provider = provider,
        ).install(modelId, onProgress)
    }
}

class SourceSeparationReleaseCatalogRepository internal constructor(
    private val rootDirectory: File,
    private val downloader: SourceSeparationReleaseCatalogDownloader,
) {
    private val lock = Any()

    fun current(): SourceSeparationReleaseCatalog = synchronized(lock) {
        readCached()?.let { return@synchronized it }
        refreshLocked()
    }

    fun refresh(): SourceSeparationReleaseCatalog = synchronized(lock) {
        refreshLocked()
    }

    private fun refreshLocked(): SourceSeparationReleaseCatalog {
        val downloaded = downloader.downloadVerified()
        rootDirectory.mkdirs()
        val staging = File(rootDirectory, ".catalog-${System.nanoTime()}.tmp")
        try {
            staging.writeBytes(downloaded.bytes)
            if (!staging.renameTo(catalogFile())) {
                throw SourceSeparationMultiStemInstallException(
                    "Unable to publish the downloaded Release catalog.",
                )
            }
            return downloaded.catalog
        } finally {
            staging.delete()
        }
    }

    private fun readCached(): SourceSeparationReleaseCatalog? {
        val file = catalogFile()
        if (!file.isFile) return null
        return runCatching {
            val bytes = file.readBytes()
            require(bytes.size.toLong() == SourceSeparationReleaseCatalogMetadata.CATALOG_BYTE_SIZE)
            require(sha256(bytes) == SourceSeparationReleaseCatalogMetadata.CATALOG_SHA256)
            SourceSeparationReleaseCatalogValidator.validate(
                SourceSeparationModelMetadata.json.decodeFromString(bytes.toString(Charsets.UTF_8)),
            )
        }.getOrElse {
            file.delete()
            null
        }
    }

    private fun catalogFile(): File = File(
        rootDirectory,
        SourceSeparationReleaseCatalogMetadata.CATALOG_FILE_NAME,
    )

    private fun sha256(bytes: ByteArray): String = java.security.MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
