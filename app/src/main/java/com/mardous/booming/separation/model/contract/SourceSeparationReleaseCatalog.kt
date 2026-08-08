package com.mardous.booming.separation.model.contract

import com.mardous.booming.separation.delivery.ModelDeliveryProvider
import com.mardous.booming.separation.delivery.SourceSeparationDeliveryReference
import kotlinx.serialization.Serializable
import java.security.MessageDigest

/**
 * The compact catalog published by bss-tflite. It is an acquisition index, not
 * a replacement for the app's runtime qualification catalog.
 */
@Serializable
data class SourceSeparationReleaseCatalog(
    val catalogId: String,
    val catalogSchemaVersion: Int,
    val entries: List<SourceSeparationReleaseCatalogEntry>,
    val releaseTag: String,
)

@Serializable
data class SourceSeparationReleaseCatalogEntry(
    val activationPolicy: String,
    val allowedBackends: List<String>,
    val artifact: SourceSeparationReleaseArtifact,
    val artifactFamily: String,
    val contract: SourceSeparationReleaseContractArtifact,
    val displayName: String,
    val isDefault: Boolean,
    val modelId: String,
    val pipelineId: String,
    val supportLevel: String,
    val validation: Map<String, String>,
)

@Serializable
data class SourceSeparationReleaseArtifact(
    val byteSize: Long,
    val fileName: String,
    val sha256: String,
    val url: String,
)

@Serializable
data class SourceSeparationReleaseContractArtifact(
    val byteSize: Long,
    val contractId: String,
    val fileName: String,
    val schemaId: String,
    val sha256: String,
    val url: String,
)

data class SourceSeparationReleaseArtifactPair(
    val entry: SourceSeparationReleaseCatalogEntry,
    val artifact: SourceSeparationDeliveryReference,
    val sidecar: SourceSeparationDeliveryReference,
)

fun SourceSeparationInstalledMultiStemModel.matchesReleaseEntry(
    entry: SourceSeparationReleaseCatalogEntry,
): Boolean = modelId == entry.modelId &&
    modelFile.name == entry.artifact.fileName &&
    sidecarFile.name == entry.contract.fileName &&
    modelByteSize == entry.artifact.byteSize &&
    modelSha256.equals(entry.artifact.sha256, ignoreCase = true) &&
    contractId == entry.contract.contractId &&
    pipelineId == entry.pipelineId

object SourceSeparationReleaseCatalogMetadata {
    const val CATALOG_FILE_NAME = "model-catalog-v3.json"
    const val CATALOG_ID = "booming-ss-model-catalog-v3"
    const val CATALOG_SCHEMA_VERSION = 3
    const val RELEASE_TAG = "v0.2.0-experimental.1"
    const val CATALOG_SHA256 =
        "d9dcb5f80f9ba36f8de436d641500b84d6d13836a77a2e6c37b3b05aa0f42be7"
    const val CATALOG_BYTE_SIZE = 44_006L
    const val CATALOG_URL =
        "https://github.com/WluhWluh/bss-tflite/releases/download/" +
            "$RELEASE_TAG/$CATALOG_FILE_NAME"
    const val MULTISTEM_ARTIFACT_FAMILY = "htdemucs-multistem"
    const val MULTISTEM_PIPELINE_ID = "booming-ss-htdemucs-neural-core"
    const val MULTISTEM_CONTRACT_SCHEMA_ID = "multitensor-v1"

    fun catalogReference() = SourceSeparationDeliveryReference(
        providerId = "github",
        artifactId = CATALOG_FILE_NAME,
        locator = CATALOG_URL,
        expectedSha256 = CATALOG_SHA256,
        expectedByteSize = CATALOG_BYTE_SIZE,
    )
}

object SourceSeparationReleaseCatalogValidator {
    private val sha256Pattern = Regex("^[0-9a-f]{64}$")
    private val releasePattern = Regex("^v[0-9]+\\.[0-9]+\\.[0-9]+(?:-[a-z0-9.]+)?$")
    private val validationKeyPattern = Regex("^[a-z][A-Za-z0-9]*$")
    private val validationValuePattern = Regex("^[a-z0-9][a-z0-9.-]*$")

    fun validate(catalog: SourceSeparationReleaseCatalog): SourceSeparationReleaseCatalog {
        require(catalog.catalogId == SourceSeparationReleaseCatalogMetadata.CATALOG_ID) {
            "Unsupported Release catalog ID: ${catalog.catalogId}"
        }
        require(catalog.catalogSchemaVersion == SourceSeparationReleaseCatalogMetadata.CATALOG_SCHEMA_VERSION) {
            "Unsupported Release catalog schema: ${catalog.catalogSchemaVersion}"
        }
        require(catalog.releaseTag == SourceSeparationReleaseCatalogMetadata.RELEASE_TAG) {
            "Release catalog is not pinned to the expected Release"
        }
        require(catalog.entries.size == catalog.entries.map { it.modelId }.toSet().size) {
            "Release catalog contains duplicate model IDs"
        }
        require(catalog.entries.count { it.isDefault } == 1) {
            "Release catalog must contain exactly one default model"
        }
        catalog.entries.forEach(::validateEntry)
        return catalog
    }

    fun resolveMultistem(
        catalog: SourceSeparationReleaseCatalog,
        modelId: String,
    ): SourceSeparationReleaseArtifactPair {
        val entry = catalog.entries.singleOrNull { it.modelId == modelId }
            ?: error("Release catalog has no model: $modelId")
        require(entry.artifactFamily == SourceSeparationReleaseCatalogMetadata.MULTISTEM_ARTIFACT_FAMILY) {
            "Model is not a multi-stem Release artifact: $modelId"
        }
        require(entry.pipelineId == SourceSeparationReleaseCatalogMetadata.MULTISTEM_PIPELINE_ID) {
            "Unsupported multi-stem pipeline: ${entry.pipelineId}"
        }
        require(entry.contract.schemaId == SourceSeparationReleaseCatalogMetadata.MULTISTEM_CONTRACT_SCHEMA_ID) {
            "Unsupported multi-stem contract schema: ${entry.contract.schemaId}"
        }
        return SourceSeparationReleaseArtifactPair(
            entry = entry,
            artifact = entry.artifact.toReference(),
            sidecar = entry.contract.toReference(),
        )
    }

    private fun validateEntry(entry: SourceSeparationReleaseCatalogEntry) {
        require(entry.supportLevel == "experimental") { "Release entries must be experimental" }
        require(entry.activationPolicy == "selectable-experimental") {
            "Release entries must be selectable experimental"
        }
        require(entry.modelId.isNotBlank() && entry.displayName.isNotBlank())
        require(entry.validation.isNotEmpty() && entry.validation.all { (key, value) ->
            validationKeyPattern.matches(key) && validationValuePattern.matches(value)
        }) { "Release validation evidence is missing or malformed: ${entry.modelId}" }
        require(entry.artifact.byteSize > 0L && entry.contract.byteSize > 0L)
        requireSha(entry.artifact.sha256, "artifact")
        requireSha(entry.contract.sha256, "sidecar")
        requireHttpsReleaseUrl(entry.artifact.url)
        requireHttpsReleaseUrl(entry.contract.url)
        require(entry.artifact.url.substringAfterLast('/') == entry.artifact.fileName)
        require(entry.contract.fileName == "${entry.artifact.fileName}.json") {
            "Sidecar must use the exact model filename: ${entry.modelId}"
        }
        require(entry.contract.url.substringAfterLast('/') == entry.contract.fileName)
        require(entry.contract.url.substringBeforeLast('/') == entry.artifact.url.substringBeforeLast('/')) {
            "Artifact and sidecar must come from the same Release: ${entry.modelId}"
        }
        require(releasePattern.matches(entry.artifact.url.substringAfter("/download/").substringBefore('/'))) {
            "Artifact URL is not pinned to a Release"
        }
        if (entry.artifactFamily == SourceSeparationReleaseCatalogMetadata.MULTISTEM_ARTIFACT_FAMILY) {
            require(entry.validation == MULTISTEM_VALIDATION) {
                "Multi-stem validation evidence differs from the frozen Release: ${entry.modelId}"
            }
        }
    }

    private fun requireSha(value: String, kind: String) {
        require(sha256Pattern.matches(value)) { "Invalid $kind SHA-256" }
    }

    private fun requireHttpsReleaseUrl(value: String) {
        require(value.startsWith("https://github.com/") && "/releases/download/" in value) {
            "Release asset URL is not an immutable GitHub download URL"
        }
    }

    private val MULTISTEM_VALIDATION = mapOf(
        "canonicalDeviceGate" to "passed-s25-phase6-v2",
        "fullSong" to "pending",
        "lifecycle" to "pending",
        "listening" to "pending",
    )
}

class SourceSeparationReleaseCatalogDownloader(
    private val provider: ModelDeliveryProvider,
) {
    fun download(): SourceSeparationReleaseCatalog = downloadVerified().catalog

    fun downloadVerified(): SourceSeparationDownloadedReleaseCatalog {
        val reference = SourceSeparationReleaseCatalogMetadata.catalogReference()
        provider.acquire(reference).use { payload ->
            require(payload.reference == reference) { "Catalog payload identity changed" }
            require(payload.byteSize == null || payload.byteSize == reference.expectedByteSize) {
                "Release catalog byte size does not match the pinned identity"
            }
            val bytes = payload.openStream().use { it.readBytes() }
            require(bytes.size.toLong() == reference.expectedByteSize) {
                "Release catalog size does not match the pinned identity"
            }
            require(sha256(bytes) == reference.expectedSha256.lowercase()) {
                "Release catalog SHA-256 does not match the pinned identity"
            }
            return SourceSeparationDownloadedReleaseCatalog(
                catalog = SourceSeparationReleaseCatalogValidator.validate(
                    SourceSeparationModelMetadata.json.decodeFromString(bytes.toString(Charsets.UTF_8)),
                ),
                bytes = bytes,
            )
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}

data class SourceSeparationDownloadedReleaseCatalog(
    val catalog: SourceSeparationReleaseCatalog,
    val bytes: ByteArray,
)

private fun SourceSeparationReleaseArtifact.toReference() = SourceSeparationDeliveryReference(
    providerId = "github",
    artifactId = fileName,
    locator = url,
    expectedSha256 = sha256,
    expectedByteSize = byteSize,
)

private fun SourceSeparationReleaseContractArtifact.toReference() = SourceSeparationDeliveryReference(
    providerId = "github",
    artifactId = fileName,
    locator = url,
    expectedSha256 = sha256,
    expectedByteSize = byteSize,
)
