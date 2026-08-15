package com.mardous.booming.separation.runtime

import android.content.Context
import com.mardous.booming.separation.delivery.SourceSeparationDeliveryReference
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal object SourceSeparationGpuRuntimeCatalogMetadata {
    const val ASSET_PATH = "source-separation/litert-gpu-runtime-catalog-v2.json"
    const val CATALOG_ID = "booming-ss-litert-gpu-runtime-catalog-v2"
    const val SCHEMA_VERSION = 2
}

@Serializable
internal data class SourceSeparationGpuRuntimeCatalog(
    val schemaVersion: Int,
    val catalogId: String,
    val producerContractSchemaVersion: String,
    val producerContractSha256: String,
    val entries: List<SourceSeparationGpuRuntimeCatalogEntry>,
)

@Serializable
internal data class SourceSeparationGpuRuntimeCatalogEntry(
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
    val requiredCpuComponentId: String,
    val requiredCpuLibrarySha256: String,
    val requiredCpuJniLibrarySha256: String,
    val capability: SourceSeparationGpuRuntimeCapability,
    val delivery: SourceSeparationGpuRuntimeDelivery,
    val innerManifestSha256: String,
    val files: List<SourceSeparationGpuRuntimeLibrary>,
    val licenseAssets: List<String>,
) {
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
internal data class SourceSeparationGpuRuntimeCapability(
    val schemaVersion: Int,
    val profileId: String,
    val precision: String,
    val backend: String,
    val kernelBatchSize: Int,
    val commandQueueWindowSize: Int,
)

@Serializable
internal data class SourceSeparationGpuRuntimeDelivery(
    val providerId: String,
    val artifactId: String,
    val locator: String,
    val expectedByteSize: Long,
    val expectedSha256: String,
)

@Serializable
internal data class SourceSeparationGpuRuntimeLibrary(
    val path: String,
    val byteSize: Long,
    val sha256: String,
    val elfClass: String,
    val machine: String,
    val soname: String,
    val runtimeLoads: List<String> = emptyList(),
)

internal object SourceSeparationGpuRuntimeCatalogLoader {
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        explicitNulls = false
    }

    fun load(context: Context): SourceSeparationGpuRuntimeCatalog {
        val catalog = try {
            context.assets.open(SourceSeparationGpuRuntimeCatalogMetadata.ASSET_PATH).use { input ->
                json.decodeFromString<SourceSeparationGpuRuntimeCatalog>(
                    input.readBytes().decodeToString(),
                )
            }
        } catch (error: Throwable) {
            throw SourceSeparationGpuRuntimeCatalogException(
                "The bundled LiteRT GPU runtime catalog could not be decoded.",
                error,
            )
        }
        validate(catalog)
        return catalog
    }

    internal fun validate(catalog: SourceSeparationGpuRuntimeCatalog) {
        requireCatalog(
            catalog.schemaVersion == SourceSeparationGpuRuntimeCatalogMetadata.SCHEMA_VERSION,
            "Unsupported GPU runtime catalog schema.",
        )
        requireCatalog(
            catalog.catalogId == SourceSeparationGpuRuntimeCatalogMetadata.CATALOG_ID,
            "Unexpected GPU runtime catalog identity.",
        )
        requireCatalog(
            catalog.producerContractSchemaVersion == SourceSeparationRuntimeLayout.CONTRACT_SCHEMA_VERSION,
            "Unexpected GPU producer contract schema.",
        )
        requireCatalog(
            SHA256_PATTERN.matches(catalog.producerContractSha256),
            "GPU producer contract hash is invalid.",
        )
        requireCatalog(catalog.entries.isNotEmpty(), "GPU runtime catalog is empty.")
        requireCatalog(
            catalog.entries.map(SourceSeparationGpuRuntimeCatalogEntry::componentId).toSet().size ==
                catalog.entries.size,
            "GPU runtime catalog contains duplicate component IDs.",
        )
        requireCatalog(
            catalog.entries.map(SourceSeparationGpuRuntimeCatalogEntry::abi).toSet().size ==
                catalog.entries.size,
            "GPU runtime catalog contains duplicate ABI entries.",
        )
        catalog.entries.forEach(::validateEntry)
    }

    private fun validateEntry(entry: SourceSeparationGpuRuntimeCatalogEntry) {
        requireCatalog(COMPONENT_ID_PATTERN.matches(entry.componentId), "GPU component ID is invalid.")
        requireCatalog(
            entry.componentType == SourceSeparationRuntimeLayout.GPU_COMPONENT,
            "The GPU catalog contains an unsupported component type.",
        )
        requireCatalog(entry.producerReleaseTag.isNotBlank(), "GPU producer release tag is empty.")
        requireCatalog(entry.producerReleaseVersion.isNotBlank(), "GPU producer release version is empty.")
        requireCatalog(entry.runtimeArtifactVersion.isNotBlank(), "GPU runtime artifact version is empty.")
        requireCatalog(entry.baseLiteRtVersion.isNotBlank(), "GPU base LiteRT version is empty.")
        requireCatalog(entry.abi == "arm64-v8a", "Only arm64 GPU components are currently qualified.")
        requireCatalog(entry.androidMinApi >= 26, "GPU minimum API is invalid.")
        requireCatalog(entry.maturity in MATURITIES, "GPU maturity is invalid: ${entry.maturity}.")
        requireCatalog(
            entry.capabilityId == "gpu-opencl-bounded-fp32",
            "GPU capability is not the bounded FP32 OpenCL profile.",
        )
        requireCatalog(
            entry.dependencies == listOf(entry.requiredCpuComponentId),
            "GPU CPU dependency must be represented exactly once.",
        )
        requireCatalog(
            COMPONENT_ID_PATTERN.matches(entry.requiredCpuComponentId),
            "GPU CPU dependency ID is invalid.",
        )
        requireCatalog(
            SHA256_PATTERN.matches(entry.requiredCpuLibrarySha256),
            "GPU CPU core dependency hash is invalid.",
        )
        requireCatalog(
            SHA256_PATTERN.matches(entry.requiredCpuJniLibrarySha256),
            "GPU CPU JNI dependency hash is invalid.",
        )
        requireCatalog(
            entry.capability == SourceSeparationGpuRuntimeCapability(
                schemaVersion = 1,
                profileId = "gpu-opencl-bounded-fp32-v1",
                precision = "FP32",
                backend = "OpenCL",
                kernelBatchSize = 1,
                commandQueueWindowSize = 1,
            ),
            "GPU capability does not match the qualified N=1 profile.",
        )
        requireCatalog(entry.delivery.providerId == "github", "Only the GitHub GPU provider is supported.")
        requireCatalog(
            entry.delivery.artifactId == entry.componentId,
            "GPU delivery artifact does not match its component.",
        )
        requireCatalog(entry.delivery.expectedByteSize > 0L, "GPU delivery size is invalid.")
        requireCatalog(
            SHA256_PATTERN.matches(entry.delivery.expectedSha256),
            "GPU delivery hash is invalid.",
        )
        requireCatalog(
            entry.delivery.locator.startsWith("https://github.com/") &&
                "/releases/download/" in entry.delivery.locator,
            "GPU delivery locator is not an immutable GitHub Release asset.",
        )
        requireCatalog(SHA256_PATTERN.matches(entry.innerManifestSha256), "GPU manifest hash is invalid.")
        requireCatalog(entry.files.map(SourceSeparationGpuRuntimeLibrary::path).toSet().size == 2,
            "GPU library paths must be unique.")
        requireCatalog(
            entry.files.map(SourceSeparationGpuRuntimeLibrary::path).toSet() ==
                setOf("libBssOcl.so", "libLiteRtClGlAccelerator.so"),
            "GPU library set is incomplete or unexpected.",
        )
        entry.files.forEach { file ->
            requireCatalog(file.byteSize > 0L, "GPU library size is invalid.")
            requireCatalog(SHA256_PATTERN.matches(file.sha256), "GPU library hash is invalid.")
            requireCatalog(file.elfClass.isNotBlank(), "GPU ELF class is empty.")
            requireCatalog(file.machine.isNotBlank(), "GPU ELF machine is empty.")
            requireCatalog(file.soname == file.path, "GPU SONAME does not match its library path.")
        }
        val accelerator = entry.files.single { it.path == "libLiteRtClGlAccelerator.so" }
        requireCatalog(
            accelerator.runtimeLoads == listOf("libBssOcl.so"),
            "GPU accelerator dependency order is not explicit.",
        )
        requireCatalog(entry.licenseAssets.isNotEmpty(), "GPU license references are empty.")
    }

    private fun requireCatalog(condition: Boolean, message: String) {
        if (!condition) throw SourceSeparationGpuRuntimeCatalogException(message)
    }

    private val SHA256_PATTERN = Regex("^[a-fA-F0-9]{64}$")
    private val COMPONENT_ID_PATTERN = Regex("^[a-z0-9][a-z0-9._-]{0,127}$")
    private val MATURITIES = setOf("recommended", "experimental", "unavailable")
}

internal class SourceSeparationGpuRuntimeCatalogException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
