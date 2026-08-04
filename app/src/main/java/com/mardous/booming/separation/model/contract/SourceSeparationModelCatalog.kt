package com.mardous.booming.separation.model.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SourceSeparationModelCatalog(
    val catalogSchemaVersion: Int,
    val contractSchemaVersion: Int,
    val catalogId: String,
    val inventory: CatalogInventoryReference,
    val sources: List<CatalogSourceRecord>,
    val artifacts: List<CatalogArtifactRecord>,
    val contracts: List<SourceSeparationModelContract>,
    val entries: List<CatalogEntry>,
    val runtimeQualifications: List<CatalogRuntimeQualification>,
)

@Serializable
data class CatalogInventoryReference(
    val fileName: String,
    val inventorySchemaVersion: Int,
    val sha256: String,
)

@Serializable
data class CatalogSourceRecord(
    val sourceId: String,
    val fileName: String,
    val sha256: String,
    val artifactId: String,
    val aliasOfSourceId: String? = null,
)

@Serializable
data class CatalogArtifactRecord(
    val artifactId: String,
    val canonicalSourceId: String,
    val sourceIds: List<String>,
    val plannedFileName: String,
    val conversionState: CatalogConversionState,
    val tflite: CatalogTfliteArtifact? = null,
)

@Serializable
enum class CatalogConversionState {
    @SerialName("converted-unreleased")
    ConvertedUnreleased,

    @SerialName("released")
    Released,

    @SerialName("not-converted")
    NotConverted,
}

@Serializable
data class CatalogTfliteArtifact(
    val fileName: String,
    val byteSize: Long,
    val sha256: String,
    val releaseAsset: CatalogReleaseAsset? = null,
)

@Serializable
data class CatalogReleaseAsset(
    val tag: String,
    val url: String,
)

@Serializable
data class CatalogEntry(
    val modelId: String,
    val artifactId: String,
    val displayName: String,
    val supportLevel: CatalogSupportLevel,
    val activationPolicy: CatalogActivationPolicy,
    val releaseMaturity: CatalogReleaseMaturity,
    val downloadActivatesModel: Boolean,
    val isDefault: Boolean,
    val stemUi: CatalogStemUi,
    val validation: CatalogValidationState,
    val contractId: String? = null,
)

@Serializable
enum class CatalogSupportLevel {
    @SerialName("recommended")
    Recommended,

    @SerialName("experimental")
    Experimental,

    @SerialName("download-only")
    DownloadOnly,
}

@Serializable
enum class CatalogActivationPolicy {
    @SerialName("selectable-when-qualified")
    SelectableWhenQualified,

    @SerialName("selectable-experimental")
    SelectableExperimental,

    @SerialName("download-only-resource-gated")
    DownloadOnlyResourceGated,

    @SerialName("blocked-until-reviewed-contract")
    BlockedUntilReviewedContract,

    @SerialName("download-only-generic-stem")
    DownloadOnlyGenericStem,
}

@Serializable
enum class CatalogReleaseMaturity {
    @SerialName("candidate")
    Candidate,

    @SerialName("beta-ready")
    BetaReady,

    @SerialName("stable")
    Stable,
}

@Serializable
enum class CatalogStemUi {
    @SerialName("vocals-instrumental")
    VocalsInstrumental,

    @SerialName("generic-target-residual-required")
    GenericTargetResidualRequired,
}

@Serializable
data class CatalogValidationState(
    val conversion: CatalogValidationStatus,
    val desktopNumerical: CatalogValidationStatus,
    val androidCpu: CatalogValidationStatus,
    val androidGpu: CatalogValidationStatus,
    val fullSong: CatalogValidationStatus,
)

@Serializable
enum class CatalogValidationStatus {
    @SerialName("passed")
    Passed,

    @SerialName("partial")
    Partial,

    @SerialName("rejected")
    Rejected,

    @SerialName("pending")
    Pending,
}
