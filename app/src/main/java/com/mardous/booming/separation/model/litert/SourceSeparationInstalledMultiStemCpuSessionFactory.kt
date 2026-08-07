package com.mardous.booming.separation.model.litert

import com.mardous.booming.separation.model.contract.SourceSeparationInstalledMultiStemModel
import com.mardous.booming.separation.model.contract.SourceSeparationMultiTensorExecutableContractLoader

/**
 * Bridges an atomically installed Release model into the reviewed HTDemucs CPU
 * session. The store already verified the model digest; reopening trusts that
 * persisted identity and only rechecks local metadata and file size.
 */
internal class SourceSeparationInstalledMultiStemCpuSessionFactory(
    private val sessionFactory: HtdemucsLiteRtCpuInferenceSessionFactory =
        HtdemucsLiteRtCpuInferenceSessionFactory(),
) {
    fun create(
        installed: SourceSeparationInstalledMultiStemModel,
    ): HtdemucsCpuInferenceSession {
        require(installed.pipelineId == "booming-ss-htdemucs-neural-core") {
            "Installed multi-stem model uses an unsupported pipeline."
        }
        require(installed.modelFile.isFile && installed.sidecarFile.isFile) {
            "Installed multi-stem model files are unavailable."
        }
        require(installed.modelFile.name == installed.sidecarFile.name.removeSuffix(".json")) {
            "Installed multi-stem artifact and sidecar names are not paired."
        }
        require(installed.modelFile.length() == installed.modelByteSize) {
            "Installed multi-stem artifact size changed after installation."
        }
        val contract = installed.sidecarFile.bufferedReader().use { reader ->
            SourceSeparationMultiTensorExecutableContractLoader.load(reader.readText())
        }
        require(contract.modelContract.modelId == installed.modelId &&
            contract.modelContract.contractId == installed.contractId &&
            contract.artifact.fileName == installed.modelFile.name &&
            contract.artifact.byteSize == installed.modelByteSize &&
            contract.artifact.sha256.equals(installed.modelSha256, ignoreCase = true)
        ) { "Installed multi-stem sidecar no longer matches its install record." }

        return sessionFactory.create(
            artifact = HtdemucsVerifiedArtifact(
                file = installed.modelFile,
                byteSize = installed.modelByteSize,
                sha256 = installed.modelSha256,
            ),
            contract = contract,
        )
    }
}
