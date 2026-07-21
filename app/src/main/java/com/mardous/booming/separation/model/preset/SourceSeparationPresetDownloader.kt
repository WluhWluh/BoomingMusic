package com.mardous.booming.separation.model.preset

import java.io.InputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class SourceSeparationPresetDownloader internal constructor(
    private val repository: SourceSeparationPresetRepository,
    private val connectionFactory: SourceSeparationPresetConnectionFactory =
        HttpSourceSeparationPresetConnectionFactory,
    private val mirrorResolver: SourceSeparationPresetMirrorResolver =
        GitHubSourceSeparationPresetMirrorResolver,
) {
    private val lock = Any()
    private val activeTransfers = mutableMapOf<String, ActiveTransfer>()

    fun download(
        modelId: String,
        onProgress: (SourceSeparationPresetDownloadProgress) -> Unit = {},
    ): SourceSeparationInstalledPreset {
        val preset = repository.officialPreset(modelId)
        val transfer = ActiveTransfer()
        synchronized(lock) {
            if (activeTransfers.putIfAbsent(modelId, transfer) != null) {
                throw SourceSeparationPresetDownloadException(
                    "A download is already running for $modelId.",
                )
            }
        }

        try {
            transfer.throwIfCanceled(modelId)
            val attempts = buildList {
                add(DownloadAttempt(preset.downloadUrl, usingMirror = false))
                mirrorResolver.resolve(preset.downloadUrl)?.let { mirrorUrl ->
                    add(DownloadAttempt(mirrorUrl, usingMirror = true))
                }
            }
            var lastError: Throwable? = null
            attempts.forEachIndexed { index, attempt ->
                try {
                    return downloadAttempt(
                        preset = preset,
                        attempt = attempt,
                        transfer = transfer,
                        onProgress = onProgress,
                    )
                } catch (error: Throwable) {
                    transfer.throwIfCanceled(modelId)
                    lastError = error
                    val hasNextAttempt = index < attempts.lastIndex
                    if (!hasNextAttempt || !error.isRetryableTransferFailure()) throw error
                } finally {
                    transfer.detachAndClose()
                }
            }
            throw lastError ?: SourceSeparationPresetDownloadException(
                "Model download failed: $modelId.",
            )
        } finally {
            synchronized(lock) {
                activeTransfers.remove(modelId, transfer)
            }
            transfer.close()
        }
    }

    fun cancel(modelId: String): Boolean {
        val transfer = synchronized(lock) { activeTransfers[modelId] } ?: return false
        transfer.cancel()
        return true
    }

    fun isDownloading(modelId: String): Boolean = synchronized(lock) {
        modelId in activeTransfers
    }

    private fun notifyProgress(
        transfer: ActiveTransfer,
        preset: SourceSeparationOfficialPreset,
        attempt: DownloadAttempt,
        downloadedBytes: Long,
        callback: (SourceSeparationPresetDownloadProgress) -> Unit,
    ) {
        transfer.throwIfCanceled(preset.modelId)
        callback(
            SourceSeparationPresetDownloadProgress(
                modelId = preset.modelId,
                sourceUrl = attempt.url,
                usingMirror = attempt.usingMirror,
                downloadedBytes = downloadedBytes,
                totalBytes = preset.byteSize,
            ),
        )
        transfer.throwIfCanceled(preset.modelId)
    }

    private fun downloadAttempt(
        preset: SourceSeparationOfficialPreset,
        attempt: DownloadAttempt,
        transfer: ActiveTransfer,
        onProgress: (SourceSeparationPresetDownloadProgress) -> Unit,
    ): SourceSeparationInstalledPreset {
        val connection = connectionFactory.open(attempt.url)
        transfer.attach(connection)
        transfer.throwIfCanceled(preset.modelId)

        val responseCode = connection.responseCode
        if (responseCode !in 200..299) {
            throw SourceSeparationPresetTransportException(
                "Model download failed with HTTP $responseCode.",
            )
        }
        connection.contentLength.takeIf { it > 0L }?.let { contentLength ->
            if (contentLength != preset.byteSize) {
                throw SourceSeparationPresetTransportException(
                    "Release asset size does not match the bundled catalog.",
                )
            }
        }

        notifyProgress(transfer, preset, attempt, 0L, onProgress)
        return connection.inputStream.use { input ->
            repository.installOfficial(
                modelId = preset.modelId,
                input = input,
                onProgress = { downloadedBytes ->
                    if (downloadedBytes > preset.byteSize) {
                        throw SourceSeparationPresetDownloadIntegrityException(
                            expected = preset,
                            actualByteSize = downloadedBytes,
                            actualSha256 = "unavailable",
                        )
                    }
                    notifyProgress(
                        transfer,
                        preset,
                        attempt,
                        downloadedBytes,
                        onProgress,
                    )
                },
            )
        }
    }

    private class ActiveTransfer {
        private val canceled = AtomicBoolean(false)

        @Volatile
        private var connection: SourceSeparationPresetConnection? = null

        fun attach(value: SourceSeparationPresetConnection) {
            connection = value
            if (canceled.get()) value.close()
        }

        fun cancel() {
            canceled.set(true)
            connection?.close()
        }

        fun close() {
            connection?.close()
            connection = null
        }

        fun detachAndClose() = close()

        fun throwIfCanceled(modelId: String) {
            if (canceled.get()) throw SourceSeparationPresetDownloadCanceledException(modelId)
        }
    }
}

data class SourceSeparationPresetDownloadProgress(
    val modelId: String,
    val sourceUrl: String,
    val usingMirror: Boolean,
    val downloadedBytes: Long,
    val totalBytes: Long,
)

class SourceSeparationPresetDownloadCanceledException(
    val modelId: String,
) : SourceSeparationPresetDownloadException("Model download was canceled: $modelId.")

private class SourceSeparationPresetTransportException(message: String) :
    SourceSeparationPresetDownloadException(message)

internal fun interface SourceSeparationPresetConnectionFactory {
    fun open(url: String): SourceSeparationPresetConnection
}

internal fun interface SourceSeparationPresetMirrorResolver {
    fun resolve(url: String): String?
}

internal interface SourceSeparationPresetConnection : AutoCloseable {
    val responseCode: Int
    val contentLength: Long
    val inputStream: InputStream
}

private object HttpSourceSeparationPresetConnectionFactory :
    SourceSeparationPresetConnectionFactory {
    override fun open(url: String): SourceSeparationPresetConnection {
        val connection = URL(url).openConnection() as? HttpURLConnection
            ?: throw SourceSeparationPresetDownloadException(
                "Unable to open the model download connection.",
            )
        connection.connectTimeout = DOWNLOAD_CONNECT_TIMEOUT_MS
        connection.readTimeout = DOWNLOAD_READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept-Encoding", "identity")
        return HttpSourceSeparationPresetConnection(connection)
    }

    private const val DOWNLOAD_CONNECT_TIMEOUT_MS = 15_000
    private const val DOWNLOAD_READ_TIMEOUT_MS = 60_000
}

private object GitHubSourceSeparationPresetMirrorResolver :
    SourceSeparationPresetMirrorResolver {
    override fun resolve(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase(Locale.US) ?: return null
        if (host != "github.com" && host != "www.github.com") return null
        return "https://ghfast.top/$url"
    }
}

private class HttpSourceSeparationPresetConnection(
    private val connection: HttpURLConnection,
) : SourceSeparationPresetConnection {
    override val responseCode: Int
        get() = connection.responseCode

    override val contentLength: Long
        get() = connection.contentLengthLong

    override val inputStream: InputStream
        get() = connection.inputStream

    override fun close() {
        connection.disconnect()
    }
}

private data class DownloadAttempt(
    val url: String,
    val usingMirror: Boolean,
)

private fun Throwable.isRetryableTransferFailure(): Boolean =
    this is IOException || this is SourceSeparationPresetTransportException ||
        this is SourceSeparationPresetDownloadIntegrityException
