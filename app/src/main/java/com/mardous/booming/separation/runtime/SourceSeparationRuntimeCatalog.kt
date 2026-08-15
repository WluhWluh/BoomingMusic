package com.mardous.booming.separation.runtime

import android.content.Context
import com.mardous.booming.separation.delivery.SourceSeparationDeliveryReference
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal object SourceSeparationRuntimeCatalogMetadata {
    const val ASSET_PATH = "source-separation/litert-runtime-catalog-v2.json"
    const val CATALOG_ID = "booming-ss-litert-runtime-catalog-v2"
    const val SCHEMA_VERSION = 2
}

@Serializable
internal data class SourceSeparationRuntimeCatalog(
    val schemaVersion: Int,
    val catalogId: String,
    val producerContractSchemaVersion: String,
    val producerContractSha256: String,
    val entries: List<SourceSeparationRuntimeCatalogEntry>,
) {
    fun entryForAbi(abi: String): SourceSeparationRuntimeCatalogEntry? =
        entries.singleOrNull { it.abi == abi }
}

@Serializable
internal data class SourceSeparationRuntimeCatalogEntry(
    val componentId: String,
    val componentType: String,
    val producerReleaseTag: String,
    val producerReleaseVersion: String,
    val runtimeArtifactVersion: String,
    val baseLiteRtVersion: String,
    val abi: String,
    val androidMinApi: Int,
    val maturity: String,
    val capabilityId: String,
    val dependencies: List<String>,
    val delivery: SourceSeparationRuntimeDelivery,
    val innerManifestSha256: String,
    val loadOrder: List<String>,
    val innerLibraries: List<SourceSeparationRuntimeLibrary>,
    val licenseAssets: List<String>,
) {
    val innerLibrary: SourceSeparationRuntimeLibrary
        get() = innerLibraries.single { it.role == SourceSeparationRuntimeLibraryRole.Runtime.id }

    val innerJniLibrary: SourceSeparationRuntimeLibrary
        get() = innerLibraries.single { it.role == SourceSeparationRuntimeLibraryRole.Jni.id }

    fun deliveryReference(): SourceSeparationDeliveryReference =
        SourceSeparationDeliveryReference(
            providerId = delivery.providerId,
            artifactId = delivery.artifactId,
            locator = delivery.locator,
            expectedSha256 = delivery.expectedSha256,
            expectedByteSize = delivery.expectedByteSize,
        )
}

@Serializable
internal data class SourceSeparationRuntimeDelivery(
    val providerId: String,
    val artifactId: String,
    val locator: String,
    val expectedByteSize: Long,
    val expectedSha256: String,
)

@Serializable
internal data class SourceSeparationRuntimeLibrary(
    val role: String,
    val path: String,
    val byteSize: Long,
    val sha256: String,
    val elfClass: String,
    val machine: String,
    val soname: String,
)

internal enum class SourceSeparationRuntimeLibraryRole(val id: String) {
    Runtime("runtime"),
    Jni("jni"),
}

internal object SourceSeparationRuntimeCatalogLoader {
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        explicitNulls = false
    }

    fun load(context: Context): SourceSeparationRuntimeCatalog {
        val catalog = try {
            context.assets.open(SourceSeparationRuntimeCatalogMetadata.ASSET_PATH).use { input ->
                json.decodeFromString<SourceSeparationRuntimeCatalog>(
                    input.readBytes().decodeToString(),
                )
            }
        } catch (error: Throwable) {
            throw SourceSeparationRuntimeCatalogException(
                "The bundled LiteRT runtime catalog could not be decoded.",
                error,
            )
        }
        validate(catalog)
        return catalog
    }

    internal fun validate(catalog: SourceSeparationRuntimeCatalog) {
        requireCatalog(
            catalog.schemaVersion == SourceSeparationRuntimeCatalogMetadata.SCHEMA_VERSION,
            "Unsupported runtime catalog schema.",
        )
        requireCatalog(
            catalog.catalogId == SourceSeparationRuntimeCatalogMetadata.CATALOG_ID,
            "Unexpected runtime catalog identity.",
        )
        requireCatalog(
            catalog.producerContractSchemaVersion ==
                SourceSeparationRuntimeLayout.CONTRACT_SCHEMA_VERSION,
            "Unexpected producer contract schema.",
        )
        requireCatalog(
            SHA256_PATTERN.matches(catalog.producerContractSha256),
            "Runtime catalog producer contract hash is invalid.",
        )
        requireCatalog(catalog.entries.isNotEmpty(), "Runtime catalog is empty.")
        requireCatalog(
            catalog.entries.map(SourceSeparationRuntimeCatalogEntry::componentId).toSet().size ==
                catalog.entries.size,
            "Runtime catalog contains duplicate component IDs.",
        )
        requireCatalog(
            catalog.entries.map(SourceSeparationRuntimeCatalogEntry::abi).toSet().size ==
                catalog.entries.size,
            "Runtime catalog contains duplicate ABI entries.",
        )
        catalog.entries.forEach(::validateEntry)
    }

    private fun validateEntry(entry: SourceSeparationRuntimeCatalogEntry) {
        requireCatalog(
            COMPONENT_ID_PATTERN.matches(entry.componentId),
            "Runtime component ID is invalid.",
        )
        requireCatalog(
            entry.componentType == SourceSeparationRuntimeLayout.CPU_COMPONENT,
            "Only CPU runtime components are accepted in Phase 2.",
        )
        requireCatalog(entry.producerReleaseTag.isNotBlank(), "Runtime producer release tag is empty.")
        requireCatalog(entry.producerReleaseVersion.isNotBlank(), "Runtime producer release version is empty.")
        requireCatalog(entry.runtimeArtifactVersion.isNotBlank(), "Runtime artifact version is empty.")
        requireCatalog(entry.baseLiteRtVersion.isNotBlank(), "Base LiteRT version is empty.")
        requireCatalog(entry.abi in SUPPORTED_ABIS, "Runtime catalog contains an unsupported ABI: ${entry.abi}.")
        requireCatalog(entry.androidMinApi >= 23, "Runtime minimum API is invalid.")
        requireCatalog(entry.maturity in MATURITIES, "Runtime maturity is invalid: ${entry.maturity}.")
        requireCatalog(entry.capabilityId == "cpu", "Runtime capability is not CPU.")
        requireCatalog(
            entry.dependencies.isEmpty(),
            "CPU runtime dependencies must be explicit in a later catalog revision.",
        )
        requireCatalog(entry.delivery.providerId == "github", "Phase 2 accepts only the GitHub runtime provider.")
        requireCatalog(
            entry.delivery.artifactId == entry.componentId,
            "Runtime delivery artifact does not match its component.",
        )
        requireCatalog(entry.delivery.expectedByteSize > 0L, "Runtime delivery size is invalid.")
        requireCatalog(
            SHA256_PATTERN.matches(entry.delivery.expectedSha256),
            "Runtime delivery hash is invalid.",
        )
        requireCatalog(
            entry.delivery.locator.startsWith("https://github.com/") &&
                "/releases/download/" in entry.delivery.locator,
            "Runtime delivery locator is not an immutable GitHub Release asset.",
        )
        requireCatalog(
            SHA256_PATTERN.matches(entry.innerManifestSha256),
            "Runtime inner manifest hash is invalid.",
        )
        requireCatalog(
            entry.loadOrder == SourceSeparationRuntimeLayout.CPU_LIBRARY_LOAD_ORDER,
            "Runtime library load order is invalid.",
        )
        requireCatalog(
            entry.innerLibraries.map(SourceSeparationRuntimeLibrary::path) == entry.loadOrder,
            "Runtime libraries do not follow the declared load order.",
        )
        requireCatalog(
            entry.innerLibraries.map(SourceSeparationRuntimeLibrary::role) ==
                SourceSeparationRuntimeLayout.CPU_LIBRARY_ROLES,
            "Runtime library roles are invalid.",
        )
        requireCatalog(
            entry.innerLibraries.map(SourceSeparationRuntimeLibrary::path).toSet().size ==
                entry.innerLibraries.size,
            "Runtime library paths are not unique.",
        )
        entry.innerLibraries.forEach { library ->
            requireCatalog(library.byteSize > 0L, "Runtime library size is invalid.")
            requireCatalog(
                SHA256_PATTERN.matches(library.sha256),
                "Runtime library hash is invalid.",
            )
            requireCatalog(library.elfClass.isNotBlank(), "Runtime ELF class is empty.")
            requireCatalog(library.machine.isNotBlank(), "Runtime ELF machine is empty.")
            requireCatalog(library.soname.isNotBlank(), "Runtime ELF SONAME is empty.")
        }
        requireCatalog(entry.licenseAssets.isNotEmpty(), "Runtime license references are empty.")
    }

    private fun requireCatalog(condition: Boolean, message: String) {
        if (!condition) throw SourceSeparationRuntimeCatalogException(message)
    }

    private val SHA256_PATTERN = Regex("^[a-fA-F0-9]{64}$")
    private val COMPONENT_ID_PATTERN = Regex("^[a-z0-9][a-z0-9._-]{0,127}$")
    private val SUPPORTED_ABIS = setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
    private val MATURITIES = setOf("recommended", "experimental", "unavailable")
}

internal class SourceSeparationRuntimeCatalogException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
