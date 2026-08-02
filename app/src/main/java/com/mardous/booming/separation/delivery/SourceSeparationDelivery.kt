package com.mardous.booming.separation.delivery

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * A provider-neutral identity for one immutable runtime or model artifact.
 * Transport details stay outside the model/runtime repositories.
 */
data class SourceSeparationDeliveryReference(
    val providerId: String,
    val artifactId: String,
    val locator: String,
    val expectedSha256: String,
    val expectedByteSize: Long? = null,
) {
    init {
        require(providerId.matches(IDENTIFIER_PATTERN)) {
            "Delivery provider ID is invalid."
        }
        require(artifactId.matches(IDENTIFIER_PATTERN)) {
            "Delivery artifact ID is invalid."
        }
        require(locator.isNotBlank()) { "Delivery locator is empty." }
        require(expectedSha256.matches(SHA256_PATTERN)) {
            "Delivery SHA-256 is invalid."
        }
        require(expectedByteSize == null || expectedByteSize > 0L) {
            "Delivery byte size is invalid."
        }
    }

    companion object {
        private val IDENTIFIER_PATTERN = Regex("^[a-z0-9][a-z0-9._-]{0,127}$")
        private val SHA256_PATTERN = Regex("^[a-fA-F0-9]{64}$")
    }
}

enum class SourceSeparationDeliveryOperation {
    Acquire,
    Cancel,
    Remove,
}

enum class SourceSeparationProductCapability {
    RemoteRuntimeCatalog,
    RemoteModelCatalog,
    RuntimeInstall,
    RuntimeUpdate,
    RuntimeRepair,
    RuntimeUninstall,
    GpuExecution,
    VendorNpuExecution,
    AotExecution,
    QnnJitExecution,
    CustomModelImport,
    CustomRuntimeImport,
    RuntimeDiagnostics,
    ModelDiagnostics,
}

data class SourceSeparationDeliveryCapabilities(
    val operations: Set<SourceSeparationDeliveryOperation>,
    val supportsPlatformManagedPayloads: Boolean,
) {
    init {
        require(operations.isNotEmpty()) { "Delivery operations cannot be empty." }
    }
}

/**
 * A payload handle may represent downloaded bytes or a platform-managed
 * installed split. The common verifier owns the next step.
 */
interface SourceSeparationDeliveryPayload : AutoCloseable {
    val reference: SourceSeparationDeliveryReference
    val byteSize: Long?

    fun openStream(): InputStream
}

interface RuntimeDeliveryProvider {
    val providerId: String
    val capabilities: SourceSeparationDeliveryCapabilities

    fun supports(reference: SourceSeparationDeliveryReference): Boolean

    fun acquire(reference: SourceSeparationDeliveryReference): SourceSeparationDeliveryPayload
}

/**
 * Optional runtime-only extension. HTTP range semantics stay inside the
 * provider; the common runtime store only sees an offset and total size.
 */
interface ResumableRuntimeDeliveryProvider : RuntimeDeliveryProvider {
    fun acquireResumable(
        reference: SourceSeparationDeliveryReference,
        existingBytes: Long,
    ): SourceSeparationResumableDeliveryPayload
}

interface SourceSeparationResumableDeliveryPayload : AutoCloseable {
    val reference: SourceSeparationDeliveryReference
    val resumedOffset: Long
    val totalByteSize: Long?

    fun openStream(): InputStream
}

interface ModelDeliveryProvider {
    val providerId: String
    val capabilities: SourceSeparationDeliveryCapabilities

    fun supports(reference: SourceSeparationDeliveryReference): Boolean

    fun acquire(reference: SourceSeparationDeliveryReference): SourceSeparationDeliveryPayload
}

interface ProductCapabilityPolicy {
    val channelId: String

    fun supports(capability: SourceSeparationProductCapability): Boolean

    fun supportsDeliveryProvider(providerId: String): Boolean
}

class GitHubRuntimeDeliveryProvider : ResumableRuntimeDeliveryProvider {
    override val providerId: String = GITHUB_PROVIDER_ID

    override val capabilities = SourceSeparationDeliveryCapabilities(
        operations = setOf(SourceSeparationDeliveryOperation.Acquire),
        supportsPlatformManagedPayloads = false,
    )

    override fun supports(reference: SourceSeparationDeliveryReference): Boolean =
        reference.providerId == providerId && isGitHubReleaseLocator(reference.locator)

    override fun acquire(reference: SourceSeparationDeliveryReference): SourceSeparationDeliveryPayload {
        require(supports(reference)) {
            "The GitHub runtime provider cannot acquire this delivery reference."
        }
        return openGitHubPayload(reference)
    }

    override fun acquireResumable(
        reference: SourceSeparationDeliveryReference,
        existingBytes: Long,
    ): SourceSeparationResumableDeliveryPayload {
        require(supports(reference)) {
            "The GitHub runtime provider cannot acquire this delivery reference."
        }
        require(existingBytes >= 0L) { "Existing runtime bytes cannot be negative." }
        return openGitHubResumablePayload(reference, existingBytes)
    }
}

class GitHubModelDeliveryProvider : ModelDeliveryProvider {
    override val providerId: String = GITHUB_PROVIDER_ID

    override val capabilities = SourceSeparationDeliveryCapabilities(
        operations = setOf(SourceSeparationDeliveryOperation.Acquire),
        supportsPlatformManagedPayloads = false,
    )

    override fun supports(reference: SourceSeparationDeliveryReference): Boolean =
        reference.providerId == providerId && isGitHubReleaseLocator(reference.locator)

    override fun acquire(reference: SourceSeparationDeliveryReference): SourceSeparationDeliveryPayload {
        require(supports(reference)) {
            "The GitHub model provider cannot acquire this delivery reference."
        }
        return openGitHubPayload(reference)
    }
}

object GitHubProductCapabilityPolicy : ProductCapabilityPolicy {
    override val channelId: String = GITHUB_PROVIDER_ID

    private val capabilities = setOf(
        SourceSeparationProductCapability.RemoteRuntimeCatalog,
        SourceSeparationProductCapability.RemoteModelCatalog,
        SourceSeparationProductCapability.RuntimeInstall,
        SourceSeparationProductCapability.RuntimeUpdate,
        SourceSeparationProductCapability.RuntimeRepair,
        SourceSeparationProductCapability.RuntimeUninstall,
        SourceSeparationProductCapability.GpuExecution,
        SourceSeparationProductCapability.CustomModelImport,
        SourceSeparationProductCapability.RuntimeDiagnostics,
        SourceSeparationProductCapability.ModelDiagnostics,
    )

    override fun supports(capability: SourceSeparationProductCapability): Boolean =
        capability in capabilities

    override fun supportsDeliveryProvider(providerId: String): Boolean =
        providerId == channelId
}

private class HttpSourceSeparationDeliveryPayload(
    override val reference: SourceSeparationDeliveryReference,
    private val connection: HttpURLConnection,
) : SourceSeparationDeliveryPayload {
    override val byteSize: Long? = connection.contentLengthLong.takeIf { it >= 0L }

    override fun openStream(): InputStream {
        check(connection.responseCode in 200..299) {
            "GitHub delivery failed with HTTP ${connection.responseCode}."
        }
        return connection.inputStream
    }

    override fun close() {
        connection.disconnect()
    }
}

private class HttpSourceSeparationResumableDeliveryPayload(
    override val reference: SourceSeparationDeliveryReference,
    private val connection: HttpURLConnection,
    override val resumedOffset: Long,
    override val totalByteSize: Long?,
) : SourceSeparationResumableDeliveryPayload {
    override fun openStream(): InputStream {
        check(connection.responseCode in 200..299) {
            "GitHub delivery failed with HTTP ${connection.responseCode}."
        }
        return connection.inputStream
    }

    override fun close() {
        connection.disconnect()
    }
}

private fun openGitHubPayload(
    reference: SourceSeparationDeliveryReference,
): SourceSeparationDeliveryPayload {
    val connection = URL(reference.locator).openConnection() as? HttpURLConnection
        ?: error("GitHub delivery requires an HTTP connection.")
    connection.connectTimeout = CONNECT_TIMEOUT_MS
    connection.readTimeout = READ_TIMEOUT_MS
    connection.instanceFollowRedirects = true
    connection.requestMethod = "GET"
    connection.setRequestProperty("Accept-Encoding", "identity")
    return HttpSourceSeparationDeliveryPayload(reference, connection)
}

private fun openGitHubResumablePayload(
    reference: SourceSeparationDeliveryReference,
    existingBytes: Long,
): SourceSeparationResumableDeliveryPayload {
    val connection = URL(reference.locator).openConnection() as? HttpURLConnection
        ?: error("GitHub delivery requires an HTTP connection.")
    connection.connectTimeout = CONNECT_TIMEOUT_MS
    connection.readTimeout = READ_TIMEOUT_MS
    connection.instanceFollowRedirects = true
    connection.requestMethod = "GET"
    connection.setRequestProperty("Accept-Encoding", "identity")
    if (existingBytes > 0L) {
        connection.setRequestProperty("Range", "bytes=$existingBytes-")
    }
    val responseCode = connection.responseCode
    if (responseCode !in 200..299) {
        connection.disconnect()
        error("GitHub delivery failed with HTTP $responseCode.")
    }
    val resumed = existingBytes > 0L && responseCode == HttpURLConnection.HTTP_PARTIAL
    val offset = if (resumed) existingBytes else 0L
    val remaining = connection.contentLengthLong.takeIf { it >= 0L }
    val total = remaining?.let { it + offset }
    if (total != null && total <= 0L) {
        connection.disconnect()
        error("GitHub delivery returned an invalid content length.")
    }
    return HttpSourceSeparationResumableDeliveryPayload(
        reference = reference,
        connection = connection,
        resumedOffset = offset,
        totalByteSize = total,
    )
}

private fun isGitHubReleaseLocator(locator: String): Boolean {
    val uri = runCatching { URI(locator) }.getOrNull() ?: return false
    if (uri.scheme != "https") return false
    val host = uri.host?.lowercase() ?: return false
    if (host != "github.com" && host != "www.github.com") return false
    return uri.path.orEmpty().contains("/releases/download/")
}

private const val GITHUB_PROVIDER_ID = "github"
private const val CONNECT_TIMEOUT_MS = 15_000
private const val READ_TIMEOUT_MS = 120_000
