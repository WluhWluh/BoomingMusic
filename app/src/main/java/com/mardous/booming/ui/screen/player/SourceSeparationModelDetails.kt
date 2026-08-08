package com.mardous.booming.ui.screen.player

import com.mardous.booming.separation.model.contract.SourceSeparationCustomModelProfile
import com.mardous.booming.separation.model.contract.SourceSeparationModelContract
import com.mardous.booming.separation.model.contract.SourceSeparationMultiStemReleaseInstaller
import com.mardous.booming.separation.model.contract.SourceSeparationMultiTensorExecutableContractLoader
import com.mardous.booming.separation.model.contract.SourceSeparationReleaseCatalog
import com.mardous.booming.separation.model.contract.canonicalLabel
import com.mardous.booming.separation.model.preset.SourceSeparationInstalledPreset
import com.mardous.booming.separation.model.preset.SourceSeparationPresetBindingKind
import com.mardous.booming.separation.model.preset.SourceSeparationPresetRepository
import java.io.File

internal fun SourceSeparationPresetRepository.catalogModelDetails(
    modelId: String,
): SourceSeparationModelDetailsUiState? {
    val catalog = catalogSnapshot()
    val entry = catalog.entries.singleOrNull { it.modelId == modelId } ?: return null
    val artifact = catalog.artifacts.singleOrNull { it.artifactId == entry.artifactId }
    val tflite = artifact?.tflite
    val installed = tflite?.sha256?.let(::installedModel)
    val contract = entry.contractId?.let { contractId ->
        catalog.contracts.singleOrNull { it.contractId == contractId }
    }
    return SourceSeparationModelDetailsUiState(
        displayName = entry.displayName,
        modelId = entry.modelId,
        metadataOrigin = SourceSeparationModelMetadataOrigin.BuiltInCatalog,
        installed = installed != null,
        fileName = tflite?.fileName ?: artifact?.plannedFileName.orEmpty(),
        byteSize = tflite?.byteSize,
        artifactSha256 = tflite?.sha256 ?: artifact?.let { record ->
            catalog.sources.singleOrNull { it.sourceId == record.canonicalSourceId }?.sha256
        }.orEmpty(),
        contract = contract?.toDetails(),
        profileRevisionId = contract?.contractId,
        qualityUnverified = false,
        source = contract?.source?.let { source ->
            SourceSeparationModelSourceDetails(
                fileName = source.fileName,
                url = source.url,
                sha256 = source.sha256,
                attribution = source.attribution,
            )
        } ?: artifact?.canonicalSourceId?.let { sourceId ->
            catalog.sources.singleOrNull { it.sourceId == sourceId }?.let { source ->
                SourceSeparationModelSourceDetails(
                    fileName = source.fileName,
                    url = null,
                    sha256 = source.sha256,
                    attribution = emptyList(),
                )
            }
        },
        conversion = contract?.conversion?.let { conversion ->
            SourceSeparationModelConversionDetails(
                repository = conversion.repository,
                revision = conversion.revision,
                pipelineVersion = conversion.pipelineVersion,
                toolVersions = conversion.toolVersions,
            )
        },
        runtimeQualifications = catalog.runtimeQualifications
            .filter { it.modelId == modelId &&
                (tflite == null || it.artifactSha256.equals(tflite.sha256, ignoreCase = true))
            }
            .map { qualification ->
                SourceSeparationModelRuntimeDetails(
                    abi = qualification.abi.name,
                    backend = qualification.backend.name,
                    profileId = qualification.profileId,
                    precision = qualification.precision.name,
                    status = qualification.status.name,
                    evidence = qualification.evidence,
                )
            },
    )
}

internal fun SourceSeparationPresetRepository.importedModelDetails(
    artifactSha256: String,
    profileId: String? = null,
): SourceSeparationModelDetailsUiState? {
    val installed = installedModel(artifactSha256) ?: return null
    val profile = profileId?.let { requested ->
        customProfiles().singleOrNull { it.profileId == requested }
    } ?: installed.customProfile
    val contract = when (installed.bindingKind) {
        SourceSeparationPresetBindingKind.Official -> return catalogModelDetails(installed.modelId)
        SourceSeparationPresetBindingKind.Sidecar -> installed.sidecarContract
        SourceSeparationPresetBindingKind.CustomProfile -> null
    }
    val effectiveModelId = profile?.modelId ?: contract?.modelId ?: installed.modelId
    val catalog = catalogSnapshot()
    return SourceSeparationModelDetailsUiState(
        displayName = profile?.displayName ?: contract?.displayName ?: installed.displayName,
        modelId = effectiveModelId,
        metadataOrigin = when (installed.bindingKind) {
            SourceSeparationPresetBindingKind.Official ->
                SourceSeparationModelMetadataOrigin.BuiltInCatalog
            SourceSeparationPresetBindingKind.Sidecar ->
                SourceSeparationModelMetadataOrigin.ImportedSidecar
            SourceSeparationPresetBindingKind.CustomProfile ->
                SourceSeparationModelMetadataOrigin.ManualProfile
        },
        installed = true,
        fileName = installed.file.name,
        byteSize = installed.byteSize,
        artifactSha256 = installed.sha256,
        contract = contract?.toDetails() ?: profile?.toDetails(),
        profileRevisionId = profile?.profileId ?: contract?.contractId,
        qualityUnverified = profile?.qualityUnverified == true,
        source = contract?.source?.let { source ->
            SourceSeparationModelSourceDetails(
                fileName = source.fileName,
                url = source.url,
                sha256 = source.sha256,
                attribution = source.attribution,
            )
        },
        conversion = contract?.conversion?.let { conversion ->
            SourceSeparationModelConversionDetails(
                repository = conversion.repository,
                revision = conversion.revision,
                pipelineVersion = conversion.pipelineVersion,
                toolVersions = conversion.toolVersions,
            )
        },
        runtimeQualifications = catalog.runtimeQualifications
            .filter { it.modelId == effectiveModelId &&
                it.artifactSha256.equals(installed.sha256, ignoreCase = true)
            }
            .map { qualification ->
                SourceSeparationModelRuntimeDetails(
                    abi = qualification.abi.name,
                    backend = qualification.backend.name,
                    profileId = qualification.profileId,
                    precision = qualification.precision.name,
                    status = qualification.status.name,
                    evidence = qualification.evidence,
                )
            },
    )
}

internal fun SourceSeparationMultiStemReleaseInstaller.multiStemCatalogModelDetails(
    catalog: SourceSeparationReleaseCatalog?,
    modelId: String,
): SourceSeparationModelDetailsUiState? {
    val entry = catalog?.entries?.singleOrNull { it.modelId == modelId } ?: return null
    val installed = installed(modelId)
    val executable = installed?.sidecarFile?.takeIf(File::isFile)?.let { sidecar ->
        runCatching {
            SourceSeparationMultiTensorExecutableContractLoader.load(sidecar.readText())
        }.getOrNull()
    }
    val modelContract = executable?.modelContract
    return SourceSeparationModelDetailsUiState(
        displayName = entry.displayName,
        modelId = entry.modelId,
        metadataOrigin = SourceSeparationModelMetadataOrigin.BuiltInCatalog,
        installed = installed != null,
        fileName = entry.artifact.fileName,
        byteSize = entry.artifact.byteSize + entry.contract.byteSize,
        artifactSha256 = entry.artifact.sha256,
        contract = null,
        profileRevisionId = entry.contract.contractId,
        qualityUnverified = false,
        source = executable?.provenance?.sources?.firstOrNull()?.let { source ->
            SourceSeparationModelSourceDetails(
                fileName = source.fileName,
                url = source.repository,
                sha256 = source.sha256,
                attribution = executable.notices.map { it.subject },
            )
        },
        conversion = executable?.conversion?.let { conversion ->
            SourceSeparationModelConversionDetails(
                repository = conversion.exportReport.repository.orEmpty(),
                revision = conversion.exportReport.revision.orEmpty(),
                pipelineVersion = modelContract?.pipelineContract?.pipelineVersion ?: 0,
                toolVersions = conversion.toolVersions,
            )
        },
        runtimeQualifications = listOf(
            SourceSeparationModelRuntimeDetails(
                abi = "arm64-v8a",
                backend = entry.allowedBackends.joinToString(),
                profileId = "htdemucs-cpu-fp32-v1",
                precision = "float32",
                status = entry.supportLevel,
                evidence = entry.validation.entries.joinToString { "${it.key}=${it.value}" },
            ),
        ),
        multiStemContract = SourceSeparationMultiStemContractDetails(
            identity = entry.contract.contractId,
            schemaId = entry.contract.schemaId,
            pipelineId = modelContract?.pipelineContract?.pipelineId ?: entry.pipelineId,
            sampleRate = modelContract?.pipelineContract?.sampleRate,
            channelCount = modelContract?.pipelineContract?.channelCount,
            canonicalLabels = modelContract?.stemContract?.stems
                ?.sortedBy { it.order }
                ?.map { it.canonicalLabel }
                .orEmpty(),
            allowedBackends = entry.allowedBackends,
            notices = executable?.notices?.map { notice ->
                "${notice.subject}: ${notice.licenseId} - ${notice.statement}"
            }.orEmpty(),
        ),
    )
}

private fun SourceSeparationModelContract.toDetails() = SourceSeparationModelContractDetails(
    identity = contractId,
    schemaVersion = contractSchemaVersion,
    inputTensorName = tensorContract.input.name,
    inputTensorShape = tensorContract.input.shape,
    outputTensorName = tensorContract.output.name,
    outputTensorShape = tensorContract.output.shape,
    sampleRate = dsp.sampleRate,
    channelCount = dsp.channelCount,
    nFft = dsp.nFft,
    hopLength = dsp.hopLength,
    dimF = dsp.dimF,
    dimTPower = dsp.dimTPower,
    modelTimeFrames = dsp.modelTimeFrames,
    modelOutputScale = dsp.modelOutputScale,
    modelOutputCanonicalLabel = stemContract.modelOutput.canonicalLabel,
    residualCanonicalLabel = stemContract.residual.canonicalLabel,
    pipelineId = pipelineCompatibility.pipelineId,
    pipelineMinimumVersion = pipelineCompatibility.minimumVersion,
    pipelineMaximumVersion = pipelineCompatibility.maximumVersion,
)

private fun SourceSeparationCustomModelProfile.toDetails() = SourceSeparationModelContractDetails(
    identity = profileId,
    schemaVersion = profileSchemaVersion,
    inputTensorName = tensorContract.input.name,
    inputTensorShape = tensorContract.input.shape,
    outputTensorName = tensorContract.output.name,
    outputTensorShape = tensorContract.output.shape,
    sampleRate = dsp.sampleRate,
    channelCount = dsp.channelCount,
    nFft = dsp.nFft,
    hopLength = dsp.hopLength,
    dimF = dsp.dimF,
    dimTPower = dsp.dimTPower,
    modelTimeFrames = dsp.modelTimeFrames,
    modelOutputScale = dsp.modelOutputScale,
    modelOutputCanonicalLabel = stemContract.modelOutput.canonicalLabel,
    residualCanonicalLabel = stemContract.residual.canonicalLabel,
    pipelineId = pipelineCompatibility.pipelineId,
    pipelineMinimumVersion = pipelineCompatibility.minimumVersion,
    pipelineMaximumVersion = pipelineCompatibility.maximumVersion,
)

data class SourceSeparationModelDetailsUiState(
    val displayName: String,
    val modelId: String,
    val metadataOrigin: SourceSeparationModelMetadataOrigin,
    val installed: Boolean,
    val fileName: String,
    val byteSize: Long?,
    val artifactSha256: String,
    val contract: SourceSeparationModelContractDetails?,
    val profileRevisionId: String?,
    val qualityUnverified: Boolean,
    val source: SourceSeparationModelSourceDetails?,
    val conversion: SourceSeparationModelConversionDetails?,
    val runtimeQualifications: List<SourceSeparationModelRuntimeDetails>,
    val multiStemContract: SourceSeparationMultiStemContractDetails? = null,
)

data class SourceSeparationMultiStemContractDetails(
    val identity: String,
    val schemaId: String,
    val pipelineId: String,
    val sampleRate: Int?,
    val channelCount: Int?,
    val canonicalLabels: List<String>,
    val allowedBackends: List<String>,
    val notices: List<String>,
)

enum class SourceSeparationModelMetadataOrigin {
    BuiltInCatalog,
    ImportedSidecar,
    ManualProfile,
}

data class SourceSeparationModelContractDetails(
    val identity: String,
    val schemaVersion: Int,
    val inputTensorName: String,
    val inputTensorShape: List<Int>,
    val outputTensorName: String,
    val outputTensorShape: List<Int>,
    val sampleRate: Int,
    val channelCount: Int,
    val nFft: Int,
    val hopLength: Int,
    val dimF: Int,
    val dimTPower: Int,
    val modelTimeFrames: Int,
    val modelOutputScale: Double,
    val modelOutputCanonicalLabel: String,
    val residualCanonicalLabel: String,
    val pipelineId: String,
    val pipelineMinimumVersion: Int,
    val pipelineMaximumVersion: Int,
)

data class SourceSeparationModelSourceDetails(
    val fileName: String,
    val url: String?,
    val sha256: String,
    val attribution: List<String>,
)

data class SourceSeparationModelConversionDetails(
    val repository: String,
    val revision: String,
    val pipelineVersion: Int,
    val toolVersions: Map<String, String>,
)

data class SourceSeparationModelRuntimeDetails(
    val abi: String,
    val backend: String,
    val profileId: String,
    val precision: String,
    val status: String,
    val evidence: String,
)
