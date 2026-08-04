package com.mardous.booming.separation.model.contract

import java.net.URI

data class ModelFileIdentity(
    val fileName: String,
    val byteSize: Long,
    val sha256: String,
)

class SourceSeparationModelContractException(message: String) :
    IllegalArgumentException(message)

object SourceSeparationModelContractValidator {
    const val CONTRACT_SCHEMA_VERSION = 2
    const val CUSTOM_PROFILE_SCHEMA_VERSION = 1
    const val CATALOG_SCHEMA_VERSION = 2
    const val PIPELINE_ID = "booming-ss-mdx-stft"
    const val PIPELINE_VERSION = 1

    private val sha256Pattern = Regex("^[0-9a-f]{64}$")
    private val revisionPattern = Regex("^[0-9a-f]{40}$")
    private val stableIdPattern = Regex("^[a-z0-9_]+$")

    fun validateContract(
        contract: SourceSeparationModelContract,
    ): SourceSeparationModelContract {
        requireContract(contract.contractSchemaVersion == CONTRACT_SCHEMA_VERSION) {
            "Unsupported contract schema version: ${contract.contractSchemaVersion}"
        }
        validateStableModelIdentity(contract.modelId, contract.displayName)
        requireContract(contract.contractId == "${contract.modelId}@$CONTRACT_SCHEMA_VERSION") {
            "Contract ID does not match model ID and schema version"
        }
        validateArtifact(contract.artifact)
        validateSource(contract.source)
        validateConversion(contract.conversion)
        validateRuntimeProfile(
            contract.tensorContract,
            contract.dsp,
            contract.stemContract,
            contract.pipelineCompatibility,
        )
        return contract
    }

    fun validateCustomProfile(
        profile: SourceSeparationCustomModelProfile,
    ): SourceSeparationCustomModelProfile {
        requireContract(profile.profileSchemaVersion == CUSTOM_PROFILE_SCHEMA_VERSION) {
            "Unsupported custom profile schema version: ${profile.profileSchemaVersion}"
        }
        validateStableModelIdentity(profile.modelId, profile.displayName)
        requireContract(profile.profileId.isNotBlank()) { "Custom profile ID is empty" }
        requireContract(profile.qualityUnverified) {
            "A manually entered profile must retain the unverified-quality warning"
        }
        validateArtifact(profile.artifact)
        validateRuntimeProfile(
            profile.tensorContract,
            profile.dsp,
            profile.stemContract,
            profile.pipelineCompatibility,
        )
        return profile
    }

    fun validateSidecarBinding(
        model: ModelFileIdentity,
        sidecarFileName: String,
        contract: SourceSeparationModelContract,
    ): SourceSeparationModelContract {
        validateContract(contract)
        requireContract(sidecarFileName == "${model.fileName}.json") {
            "Sidecar filename must be <model file name>.json"
        }
        validateArtifactBinding(model, contract.artifact)
        return contract
    }

    fun requireCustomImportProfile(
        model: ModelFileIdentity,
        profile: SourceSeparationCustomModelProfile?,
    ): SourceSeparationCustomModelProfile {
        val requiredProfile = profile
            ?: fail("An unknown model cannot be activated without explicit profile metadata")
        validateCustomProfile(requiredProfile)
        validateArtifactBinding(model, requiredProfile.artifact)
        return requiredProfile
    }

    fun validateCatalog(
        catalog: SourceSeparationModelCatalog,
    ): SourceSeparationModelCatalog {
        requireContract(catalog.catalogSchemaVersion == CATALOG_SCHEMA_VERSION) {
            "Unsupported catalog schema version: ${catalog.catalogSchemaVersion}"
        }
        requireContract(catalog.contractSchemaVersion == CONTRACT_SCHEMA_VERSION) {
            "Catalog contract schema does not match the supported version"
        }
        requireContract(catalog.catalogId == "booming-ss-model-catalog-v2") {
            "Unsupported catalog ID: ${catalog.catalogId}"
        }
        requireSha256(catalog.inventory.sha256, "Inventory")
        requireContract(catalog.inventory.inventorySchemaVersion == 1) {
            "Unsupported source inventory schema version"
        }

        val sources = catalog.sources.uniqueBy("source ID") { it.sourceId }
        val artifacts = catalog.artifacts.uniqueBy("artifact ID") { it.artifactId }
        val contracts = catalog.contracts.uniqueBy("contract ID") { it.contractId }
        catalog.contracts.uniqueBy("contract model ID") { it.modelId }
        val entries = catalog.entries.uniqueBy("model ID") { it.modelId }
        catalog.runtimeQualifications.uniqueBy("runtime qualification") {
            listOf(
                it.modelId,
                it.runtimeId,
                it.runtimeVersion,
                it.abi.name,
                it.backend.name,
                it.profileId,
                it.precision.name,
            ).joinToString("|")
        }

        catalog.sources.forEach { source ->
            requireContract(source.sourceId.isNotBlank()) { "Source ID is empty" }
            requireContract(source.fileName.endsWith(".onnx")) {
                "Source file is not ONNX: ${source.fileName}"
            }
            requireSha256(source.sha256, "Source ${source.sourceId}")
            requireContract(source.artifactId in artifacts) {
                "Source ${source.sourceId} references a missing artifact"
            }
            source.aliasOfSourceId?.let { canonicalId ->
                val canonical = sources[canonicalId]
                    ?: fail("Source ${source.sourceId} references a missing canonical source")
                requireContract(canonical.aliasOfSourceId == null) {
                    "Source alias chains are not allowed"
                }
                requireContract(canonical.artifactId == source.artifactId) {
                    "Source alias resolves to a different artifact"
                }
            }
        }

        catalog.artifacts.forEach { artifact ->
            validateCatalogArtifact(artifact, sources)
        }
        catalog.contracts.forEach(::validateContract)
        catalog.entries.forEach { entry ->
            validateCatalogEntry(entry, artifacts, contracts)
        }
        catalog.runtimeQualifications.forEach { qualification ->
            validateRuntimeQualification(qualification, entries, contracts)
        }

        val defaults = catalog.entries.filter(CatalogEntry::isDefault)
        requireContract(defaults.size == 1) { "Catalog must have exactly one default model" }
        requireContract(defaults.single().supportLevel == CatalogSupportLevel.Recommended) {
            "Default model must be recommended"
        }
        requireContract(
            defaults.single().activationPolicy == CatalogActivationPolicy.SelectableWhenQualified
        ) {
            "Default model must be selectable"
        }
        requireContract(
            catalog.entries.count { it.supportLevel == CatalogSupportLevel.Recommended } == 1
        ) { "Catalog must have exactly one recommended model" }
        val contractReferences = catalog.entries.mapNotNull(CatalogEntry::contractId)
        requireContract(contractReferences.size == contractReferences.toSet().size) {
            "A reviewed contract cannot be shared by multiple catalog entries"
        }
        val referencedContracts = contractReferences.toSet()
        requireContract(referencedContracts == contracts.keys) {
            "Every contract must be referenced by exactly one catalog model identity"
        }
        catalog.entries
            .filter {
                it.activationPolicy == CatalogActivationPolicy.SelectableWhenQualified ||
                    it.activationPolicy == CatalogActivationPolicy.SelectableExperimental
            }
            .forEach { resolveActivationContract(catalog, it.modelId) }
        return catalog
    }

    fun resolveActivationContract(
        catalog: SourceSeparationModelCatalog,
        modelId: String,
    ): SourceSeparationModelContract {
        val entry = catalog.entries.singleOrNull { it.modelId == modelId }
            ?: fail("Expected one catalog entry for $modelId")
        requireContract(
            entry.activationPolicy == CatalogActivationPolicy.SelectableWhenQualified ||
                entry.activationPolicy == CatalogActivationPolicy.SelectableExperimental
        ) {
            "Catalog entry $modelId is not selectable"
        }
        return resolveReviewedContract(catalog, modelId)
    }

    fun resolveReviewedContract(
        catalog: SourceSeparationModelCatalog,
        modelId: String,
    ): SourceSeparationModelContract {
        val entry = catalog.entries.singleOrNull { it.modelId == modelId }
            ?: fail("Expected one catalog entry for $modelId")
        val contractId = entry.contractId
            ?: fail("Catalog entry $modelId has no reviewed contract")
        val contract = catalog.contracts.singleOrNull { it.contractId == contractId }
            ?: fail("Expected one complete contract for $modelId")
        validateContract(contract)
        requireContract(contract.modelId == entry.modelId) {
            "Catalog entry and contract model IDs differ"
        }
        val artifact = catalog.artifacts.singleOrNull { it.artifactId == entry.artifactId }
            ?: fail("Expected one catalog artifact for $modelId")
        val tflite = artifact.tflite
            ?: fail("Catalog entry $modelId has no converted TFLite artifact")
        validateArtifactBinding(
            ModelFileIdentity(tflite.fileName, tflite.byteSize, tflite.sha256),
            contract.artifact,
        )
        return contract
    }

    private fun validateStableModelIdentity(modelId: String, displayName: String) {
        requireContract(stableIdPattern.matches(modelId)) { "Invalid stable model ID: $modelId" }
        requireContract(displayName.isNotBlank()) { "Model display name is empty" }
    }

    private fun validateArtifact(artifact: ContractArtifact) {
        requireContract(artifact.fileName.endsWith(".tflite")) {
            "Model artifact must end in .tflite"
        }
        requireContract(artifact.byteSize > 0L) { "Model artifact size must be positive" }
        requireSha256(artifact.sha256, "Model artifact")
    }

    private fun validateArtifactBinding(model: ModelFileIdentity, artifact: ContractArtifact) {
        requireContract(model.fileName == artifact.fileName) {
            "Contract artifact filename does not match the model"
        }
        requireContract(model.byteSize == artifact.byteSize) {
            "Contract artifact size does not match the model"
        }
        requireContract(model.sha256.equals(artifact.sha256, ignoreCase = true)) {
            "Contract artifact SHA-256 does not match the model"
        }
    }

    private fun validateSource(source: ContractSource) {
        requireContract(source.canonicalSourceId.isNotBlank()) { "Canonical source ID is empty" }
        requireContract(source.fileName.endsWith(".onnx")) { "Contract source is not ONNX" }
        requireContract(source.byteSize > 0L) { "Contract source size must be positive" }
        requireSha256(source.sha256, "Contract source")
        requireHttpsUri(source.url, "Contract source URL")
        requireContract(source.attribution.isNotEmpty() && source.attribution.all(String::isNotBlank)) {
            "Contract source attribution is incomplete"
        }
    }

    private fun validateConversion(conversion: ContractConversion) {
        requireHttpsUri(conversion.repository, "Conversion repository")
        requireContract(revisionPattern.matches(conversion.revision)) {
            "Conversion revision must be a full Git commit"
        }
        requireContract(conversion.pipelineVersion > 0) {
            "Conversion pipeline version must be positive"
        }
        requireContract(
            conversion.toolVersions.isNotEmpty() &&
                conversion.toolVersions.all { (name, version) -> name.isNotBlank() && version.isNotBlank() }
        ) { "Conversion tool versions are incomplete" }
    }

    private fun validateRuntimeProfile(
        tensorContract: TensorContract,
        dsp: ContractDsp,
        stemContract: StemContract,
        pipelineCompatibility: PipelineCompatibility,
    ) {
        requireContract(tensorContract.batchSize == 1) { "Only static batch size 1 is supported" }
        requireContract(tensorContract.complexChannelCount == 4) {
            "MDX tensors must use four stereo complex channels"
        }
        requireContract(dsp.sampleRate > 0 && dsp.channelCount == 2) {
            "MDX audio must use a positive sample rate and two channels"
        }
        requireContract(dsp.nFft > 0 && dsp.nFft % 2 == 0) {
            "FFT size must be a positive even number"
        }
        requireContract(dsp.hopLength > 0) { "Hop length must be positive" }
        requireContract(dsp.dimF in 1..(dsp.nFft / 2 + 1)) {
            "dimF exceeds the real FFT bins"
        }
        requireContract(dsp.dimTPower in 1..30) { "dimTPower is outside the supported range" }
        requireContract(dsp.modelTimeFrames == 1.shl(dsp.dimTPower)) {
            "Model time frames must equal 2^dimTPower"
        }
        requireContract(dsp.modelOutputScale.isFinite() && dsp.modelOutputScale > 0.0) {
            "Model output scale must be finite and positive"
        }
        val expectedShape = listOf(1, dsp.dimF, dsp.modelTimeFrames, 4)
        validateTensor(tensorContract.input, expectedShape, "input")
        validateTensor(tensorContract.output, expectedShape, "output")
        requireContract(stemContract.modelOutput.displayLabel.isNotBlank()) {
            "Model output stem label is empty"
        }
        requireContract(stemContract.residual.displayLabel.isNotBlank()) {
            "Residual stem label is empty"
        }
        val expectedResidual = when (stemContract.modelOutput.semantic) {
            ContractStemSemantic.Vocals -> ContractStemSemantic.Instrumental
            ContractStemSemantic.Instrumental -> ContractStemSemantic.Vocals
            else -> null
        }
        if (expectedResidual != null) {
            requireContract(stemContract.residual.semantic == expectedResidual) {
                "Vocals/instrumental stem semantics are not complementary"
            }
        }
        requireContract(pipelineCompatibility.pipelineId == PIPELINE_ID) {
            "Unsupported source-separation pipeline"
        }
        requireContract(
            pipelineCompatibility.minimumVersion <= PIPELINE_VERSION &&
                pipelineCompatibility.maximumVersion >= PIPELINE_VERSION
        ) { "Contract is incompatible with pipeline version $PIPELINE_VERSION" }
    }

    private fun validateTensor(tensor: ContractTensor, expectedShape: List<Int>, role: String) {
        requireContract(tensor.name.isNotBlank()) { "Tensor $role name is empty" }
        requireContract(tensor.shape == expectedShape) {
            "Tensor $role shape ${tensor.shape} does not match $expectedShape"
        }
    }

    private fun validateRuntimeQualification(
        qualification: CatalogRuntimeQualification,
        entries: Map<String, CatalogEntry>,
        contracts: Map<String, SourceSeparationModelContract>,
    ) {
        val entry = entries[qualification.modelId]
            ?: fail("Runtime qualification references a missing model")
        val contract = contracts[qualification.contractId]
            ?: fail("Runtime qualification references a missing contract")
        requireContract(entry.contractId == contract.contractId) {
            "Runtime qualification contract does not belong to its catalog entry"
        }
        requireContract(contract.modelId == qualification.modelId) {
            "Runtime qualification model and contract IDs differ"
        }
        requireContract(contract.artifact.sha256 == qualification.artifactSha256) {
            "Runtime qualification artifact SHA-256 differs from the contract"
        }
        requireContract(qualification.runtimeId == "litert") {
            "Unsupported runtime qualification: ${qualification.runtimeId}"
        }
        requireContract(qualification.runtimeVersion.isNotBlank()) {
            "Runtime qualification version is empty"
        }
        requireContract(qualification.minimumAndroidApi >= 26) {
            "Runtime minimum Android API is below the application minimum"
        }
        requireContract(qualification.profileId.isNotBlank()) {
            "Runtime qualification profile ID is empty"
        }
        requireContract(qualification.evidence.isNotBlank()) {
            "Runtime qualification evidence is empty"
        }
    }

    private fun validateCatalogArtifact(
        artifact: CatalogArtifactRecord,
        sources: Map<String, CatalogSourceRecord>,
    ) {
        requireContract(stableIdPattern.matches(artifact.artifactId)) {
            "Invalid catalog artifact ID: ${artifact.artifactId}"
        }
        requireContract(artifact.plannedFileName.endsWith(".tflite")) {
            "Planned artifact is not a TFLite file"
        }
        requireContract(artifact.sourceIds.isNotEmpty() && artifact.sourceIds.toSet().size == artifact.sourceIds.size) {
            "Catalog artifact source IDs are empty or duplicated"
        }
        requireContract(artifact.canonicalSourceId in artifact.sourceIds) {
            "Canonical source is not included in artifact source IDs"
        }
        artifact.sourceIds.forEach { sourceId ->
            requireContract(sources[sourceId]?.artifactId == artifact.artifactId) {
                "Catalog artifact references a missing or incompatible source"
            }
        }
        when (artifact.conversionState) {
            CatalogConversionState.ConvertedUnreleased -> requireContract(artifact.tflite != null) {
                "Converted artifact has no TFLite identity"
            }

            CatalogConversionState.Released -> requireContract(
                artifact.tflite?.releaseAsset != null
            ) {
                "Released artifact has no immutable Release asset"
            }

            CatalogConversionState.NotConverted -> requireContract(artifact.tflite == null) {
                "Unconverted artifact unexpectedly has a TFLite identity"
            }
        }
        artifact.tflite?.let { tflite ->
            requireContract(tflite.fileName == artifact.plannedFileName) {
                "Converted and planned TFLite filenames differ"
            }
            requireContract(tflite.byteSize > 0L) { "Catalog TFLite size must be positive" }
            requireSha256(tflite.sha256, "Catalog TFLite")
            tflite.releaseAsset?.let { release ->
                requireContract(release.tag.isNotBlank()) { "Release tag is empty" }
                requireHttpsUri(release.url, "Release asset URL")
                requireContract(!release.url.contains("/latest/", ignoreCase = true)) {
                    "Mutable latest Release URLs are not allowed"
                }
                requireContract(release.url.contains("/download/${release.tag}/")) {
                    "Release asset URL does not pin its declared tag"
                }
            }
        }
    }

    private fun validateCatalogEntry(
        entry: CatalogEntry,
        artifacts: Map<String, CatalogArtifactRecord>,
        contracts: Map<String, SourceSeparationModelContract>,
    ) {
        validateStableModelIdentity(entry.modelId, entry.displayName)
        requireContract(entry.artifactId in artifacts) {
            "Catalog entry ${entry.modelId} references a missing artifact"
        }
        requireContract(!entry.downloadActivatesModel) {
            "Downloading ${entry.modelId} must not activate it"
        }
        when (entry.supportLevel) {
            CatalogSupportLevel.Recommended -> requireContract(
                entry.activationPolicy == CatalogActivationPolicy.SelectableWhenQualified
            ) { "Recommended model ${entry.modelId} must be selectable" }

            CatalogSupportLevel.Experimental -> requireContract(
                entry.activationPolicy == CatalogActivationPolicy.SelectableExperimental
            ) { "Experimental model ${entry.modelId} must use the warned activation policy" }

            CatalogSupportLevel.DownloadOnly -> requireContract(
                entry.activationPolicy == CatalogActivationPolicy.DownloadOnlyResourceGated ||
                    entry.activationPolicy == CatalogActivationPolicy.BlockedUntilReviewedContract ||
                    entry.activationPolicy == CatalogActivationPolicy.DownloadOnlyGenericStem
            ) { "Download-only model ${entry.modelId} has an activatable policy" }
        }
        when (entry.activationPolicy) {
            CatalogActivationPolicy.SelectableWhenQualified,
            CatalogActivationPolicy.SelectableExperimental -> {
                requireContract(entry.contractId in contracts) {
                    "Selectable model ${entry.modelId} has no complete contract"
                }
                requireContract(entry.stemUi == CatalogStemUi.VocalsInstrumental) {
                    "The current selectable UI only supports vocals/instrumental contracts"
                }
            }

            CatalogActivationPolicy.DownloadOnlyResourceGated -> {
                requireContract(entry.contractId in contracts) {
                    "Resource-gated model ${entry.modelId} lost its reviewed contract"
                }
                requireContract(entry.stemUi == CatalogStemUi.VocalsInstrumental) {
                    "Resource-gated model has an incompatible stem UI"
                }
            }

            CatalogActivationPolicy.BlockedUntilReviewedContract -> {
                requireContract(entry.contractId == null) {
                    "Blocked model ${entry.modelId} unexpectedly references a contract"
                }
            }

            CatalogActivationPolicy.DownloadOnlyGenericStem -> {
                requireContract(entry.supportLevel == CatalogSupportLevel.DownloadOnly) {
                    "Generic target model must remain download-only"
                }
                requireContract(entry.stemUi == CatalogStemUi.GenericTargetResidualRequired) {
                    "Generic target model has an incompatible stem UI"
                }
                requireContract(entry.contractId == null) {
                    "Generic target model must not expose an activatable contract yet"
                }
            }
        }
    }

    private fun requireSha256(value: String, label: String) {
        requireContract(sha256Pattern.matches(value)) {
            "$label SHA-256 must contain 64 lowercase hexadecimal characters"
        }
    }

    private fun requireHttpsUri(value: String, label: String) {
        val uri = runCatching { URI(value) }.getOrNull()
        requireContract(uri?.scheme == "https" && uri.host != null) { "$label must be HTTPS" }
    }

    private inline fun requireContract(condition: Boolean, message: () -> String) {
        if (!condition) throw SourceSeparationModelContractException(message())
    }

    private fun fail(message: String): Nothing =
        throw SourceSeparationModelContractException(message)

    private fun <T, K> List<T>.uniqueBy(label: String, selector: (T) -> K): Map<K, T> {
        val result = associateBy(selector)
        requireContract(result.size == size) { "Catalog contains duplicate $label values" }
        return result
    }
}
