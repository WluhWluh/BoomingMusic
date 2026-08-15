package com.mardous.booming.separation.cache.v2

import com.mardous.booming.separation.model.contract.ContractDsp
import com.mardous.booming.separation.model.contract.ContractConversion
import com.mardous.booming.separation.model.contract.ContractDtype
import com.mardous.booming.separation.model.contract.ContractResidualRule
import com.mardous.booming.separation.model.contract.ContractSource
import com.mardous.booming.separation.model.contract.ContractTensor
import com.mardous.booming.separation.model.contract.ContractTensorLayout
import com.mardous.booming.separation.model.contract.ContractWindow
import com.mardous.booming.separation.model.contract.StemDescriptor
import com.mardous.booming.separation.model.contract.StemId
import com.mardous.booming.separation.model.contract.StemProduction
import com.mardous.booming.separation.model.contract.StemSemanticId
import com.mardous.booming.separation.model.contract.StemSet
import com.mardous.booming.separation.model.contract.toStemSet
import com.mardous.booming.separation.model.contract.SourceSeparationCustomModelProfile
import com.mardous.booming.separation.model.contract.SourceSeparationModelContract
import com.mardous.booming.separation.model.contract.SourceSeparationModelContractValidator
import com.mardous.booming.separation.model.contract.StemContract
import com.mardous.booming.separation.model.contract.TensorContract
import com.mardous.booming.separation.model.contract.SourceSeparationMultiTensorContract
import com.mardous.booming.separation.model.contract.SourceSeparationMultiTensorExecutableContract
import com.mardous.booming.separation.model.contract.SourceSeparationMultiTensorExecutableContractValidator
import kotlinx.serialization.Serializable
import java.security.MessageDigest

@Serializable
data class SourceSeparationCacheIdentity(
    val cacheIdentitySchemaVersion: Int,
    val source: SourceSeparationCacheSourceIdentity,
    val modelId: String,
    val artifactSha256: String,
    val contractId: String,
    val contractSchemaVersion: Int,
    val contractFingerprint: String,
    val profileRevisionId: String,
    val pipelineId: String,
    val pipelineVersion: Int,
    val renderProfileId: String,
) {
    init {
        require(cacheIdentitySchemaVersion == SCHEMA_VERSION) {
            "Unsupported cache identity schema: $cacheIdentitySchemaVersion"
        }
        require(modelId.isNotBlank()) { "Cache model ID is empty." }
        requireSha256(artifactSha256, "artifact")
        require(contractId.isNotBlank()) { "Cache contract ID is empty." }
        require(contractSchemaVersion > 0) { "Cache contract schema is invalid." }
        requireSha256(contractFingerprint, "contract fingerprint")
        require(profileRevisionId.isNotBlank()) { "Cache profile revision ID is empty." }
        require(pipelineId.isNotBlank()) { "Cache pipeline ID is empty." }
        require(pipelineVersion > 0) { "Cache pipeline version is invalid." }
        require(renderProfileId.isNotBlank()) { "Cache render profile ID is empty." }
    }

    val cacheKey: String
        get() = SourceSeparationCacheCanonicalEncoding.sha256(
            namespace = "booming-ss-cache-identity-v$cacheIdentitySchemaVersion",
            fields = buildList {
                add(source.audioFingerprint)
                add(source.encodedSampleCount.toString())
                add(source.encodedByteCount.toString())
                add(source.mimeType)
                add(source.sourceSampleRate.toString())
                add(source.sourceChannelCount.toString())
                add(source.sourceDurationUs?.toString() ?: "unknown")
                add(modelId)
                add(artifactSha256.lowercase())
                add(contractId)
                add(contractSchemaVersion.toString())
                add(contractFingerprint.lowercase())
                add(profileRevisionId)
                add(pipelineId)
                add(pipelineVersion.toString())
                add(renderProfileId)
            },
        )

    companion object {
        const val SCHEMA_VERSION = 2
        const val FP32_RENDER_PROFILE_ID = "mdx-native-packed-fp32-render-v2"
    }
}

@Serializable
data class SourceSeparationCacheSourceIdentity(
    val audioFingerprint: String,
    val encodedSampleCount: Long,
    val encodedByteCount: Long,
    val mimeType: String,
    val sourceSampleRate: Int,
    val sourceChannelCount: Int,
    val sourceDurationUs: Long?,
) {
    init {
        require(FINGERPRINT_PATTERN.matches(audioFingerprint)) {
            "Final source audio fingerprint is required."
        }
        require(encodedSampleCount > 0L) { "Encoded sample count is invalid." }
        require(encodedByteCount > 0L) { "Encoded byte count is invalid." }
        require(mimeType.isNotBlank()) { "Source MIME type is empty." }
        require(sourceSampleRate > 0) { "Source sample rate is invalid." }
        require(sourceChannelCount > 0) { "Source channel count is invalid." }
        require(sourceDurationUs == null || sourceDurationUs > 0L) {
            "Source duration is invalid."
        }
    }

    private companion object {
        val FINGERPRINT_PATTERN = Regex("^[a-z0-9-]+:[0-9a-fA-F]{64}$")
    }
}

@Serializable
data class SourceSeparationCacheContractSnapshot(
    val modelId: String,
    val displayName: String,
    val artifactFileName: String,
    val artifactByteSize: Long,
    val artifactSha256: String,
    val contractId: String,
    val contractSchemaVersion: Int,
    val profileRevisionId: String,
    val profileOrigin: SourceSeparationCacheProfileOrigin,
    val qualityUnverified: Boolean,
    val source: ContractSource? = null,
    val conversion: ContractConversion? = null,
    val tensorContract: TensorContract? = null,
    val dsp: ContractDsp? = null,
    val stemContract: StemContract? = null,
    val multiTensorContract: SourceSeparationMultiTensorContract? = null,
    val multiStemSet: StemSet? = null,
    val pipelineId: String,
    val pipelineVersion: Int,
) {
    init {
        require(modelId.isNotBlank()) { "Cache snapshot model ID is empty." }
        require(displayName.isNotBlank()) { "Cache snapshot display name is empty." }
        require(artifactFileName.isNotBlank()) { "Cache snapshot artifact filename is empty." }
        require(artifactByteSize > 0L) { "Cache snapshot artifact size is invalid." }
        requireSha256(artifactSha256, "snapshot artifact")
        require(contractId.isNotBlank()) { "Cache snapshot contract ID is empty." }
        require(contractSchemaVersion > 0) { "Cache snapshot contract schema is invalid." }
        require(profileRevisionId.isNotBlank()) { "Cache snapshot profile revision is empty." }
        require(pipelineId.isNotBlank()) { "Cache snapshot pipeline ID is empty." }
        require(pipelineVersion > 0) { "Cache snapshot pipeline version is invalid." }
        val hasMdxContract = tensorContract != null && dsp != null && stemContract != null &&
            multiTensorContract == null && multiStemSet == null
        val hasMultiTensorContract = tensorContract == null && dsp == null && stemContract == null &&
            multiTensorContract != null && multiStemSet != null
        require(hasMdxContract || hasMultiTensorContract) {
            "Cache snapshot must contain exactly one executable contract kind."
        }
        multiTensorContract?.let { contract ->
            require(contract.contractId == contractId && contract.modelId == modelId) {
                "Cache multi-tensor contract identity is inconsistent."
            }
            require(contract.pipelineContract.pipelineId == pipelineId &&
                contract.pipelineContract.pipelineVersion == pipelineVersion
            ) { "Cache multi-tensor pipeline identity is inconsistent." }
            require(multiStemSet?.stems?.map { it.stemId.value } ==
                contract.stemContract.stems.map { it.stemId }
            ) { "Cache multi-tensor stem set is inconsistent." }
        }
    }

    val contractFingerprint: String
        get() = SourceSeparationCacheContractFingerprint.from(this)

    fun identity(
        source: SourceSeparationCacheSourceIdentity,
        renderProfileId: String = SourceSeparationCacheIdentity.FP32_RENDER_PROFILE_ID,
    ): SourceSeparationCacheIdentity = SourceSeparationCacheIdentity(
        cacheIdentitySchemaVersion = SourceSeparationCacheIdentity.SCHEMA_VERSION,
        source = source,
        modelId = modelId,
        artifactSha256 = artifactSha256,
        contractId = contractId,
        contractSchemaVersion = contractSchemaVersion,
        contractFingerprint = contractFingerprint,
        profileRevisionId = profileRevisionId,
        pipelineId = pipelineId,
        pipelineVersion = pipelineVersion,
        renderProfileId = renderProfileId,
    )

    fun expectedStemSet(): StemSet = multiStemSet ?: requireNotNull(stemContract).toStemSet()

    fun outputChannelCount(): Int = dsp?.channelCount
        ?: requireNotNull(multiTensorContract).pipelineContract.channelCount

    companion object {
        fun fromOfficial(
            contract: SourceSeparationModelContract,
            origin: SourceSeparationCacheProfileOrigin = SourceSeparationCacheProfileOrigin.Official,
            pipelineVersion: Int = SourceSeparationModelContractValidator.PIPELINE_VERSION,
        ): SourceSeparationCacheContractSnapshot {
            require(origin != SourceSeparationCacheProfileOrigin.Custom) {
                "An official contract cannot use the custom profile origin."
            }
            val validated = SourceSeparationModelContractValidator.validateContract(contract)
            require(pipelineVersion in validated.pipelineCompatibility.minimumVersion..
                validated.pipelineCompatibility.maximumVersion
            ) {
                "The cache pipeline version is outside the official contract range."
            }
            return SourceSeparationCacheContractSnapshot(
                modelId = validated.modelId,
                displayName = validated.displayName,
                artifactFileName = validated.artifact.fileName,
                artifactByteSize = validated.artifact.byteSize,
                artifactSha256 = validated.artifact.sha256,
                contractId = validated.contractId,
                contractSchemaVersion = validated.contractSchemaVersion,
                profileRevisionId = validated.contractId,
                profileOrigin = origin,
                qualityUnverified = false,
                source = validated.source,
                conversion = validated.conversion,
                tensorContract = validated.tensorContract,
                dsp = validated.dsp,
                stemContract = validated.stemContract,
                pipelineId = validated.pipelineCompatibility.pipelineId,
                pipelineVersion = pipelineVersion,
            )
        }

        fun fromCustom(
            profile: SourceSeparationCustomModelProfile,
            pipelineVersion: Int = SourceSeparationModelContractValidator.PIPELINE_VERSION,
        ): SourceSeparationCacheContractSnapshot {
            val validated = SourceSeparationModelContractValidator.validateCustomProfile(profile)
            require(pipelineVersion in validated.pipelineCompatibility.minimumVersion..
                validated.pipelineCompatibility.maximumVersion
            ) {
                "The cache pipeline version is outside the custom profile range."
            }
            return SourceSeparationCacheContractSnapshot(
                modelId = validated.modelId,
                displayName = validated.displayName,
                artifactFileName = validated.artifact.fileName,
                artifactByteSize = validated.artifact.byteSize,
                artifactSha256 = validated.artifact.sha256,
                contractId = validated.profileId,
                contractSchemaVersion = validated.profileSchemaVersion,
                profileRevisionId = validated.profileId,
                profileOrigin = SourceSeparationCacheProfileOrigin.Custom,
                qualityUnverified = validated.qualityUnverified,
                tensorContract = validated.tensorContract,
                dsp = validated.dsp,
                stemContract = validated.stemContract,
                pipelineId = validated.pipelineCompatibility.pipelineId,
                pipelineVersion = pipelineVersion,
            )
        }

        fun fromMultiTensor(
            executable: SourceSeparationMultiTensorExecutableContract,
        ): SourceSeparationCacheContractSnapshot {
            SourceSeparationMultiTensorExecutableContractValidator.validate(executable)
            val contract = executable.modelContract
            val stemSet = StemSet(
                contract.stemContract.stems.map { stem ->
                    StemDescriptor(
                        stemId = StemId(stem.stemId),
                        semanticId = StemSemanticId(stem.semanticId),
                        canonicalLabel = stem.canonicalLabel,
                        order = stem.order,
                        production = StemProduction.PipelineNative(stem.order),
                    )
                },
            )
            return SourceSeparationCacheContractSnapshot(
                modelId = contract.modelId,
                displayName = contract.displayName,
                artifactFileName = executable.artifact.fileName,
                artifactByteSize = executable.artifact.byteSize,
                artifactSha256 = executable.artifact.sha256,
                contractId = contract.contractId,
                contractSchemaVersion = contract.contractSchemaVersion,
                profileRevisionId = contract.contractId,
                profileOrigin = SourceSeparationCacheProfileOrigin.Official,
                qualityUnverified = false,
                multiTensorContract = contract,
                multiStemSet = stemSet,
                pipelineId = contract.pipelineContract.pipelineId,
                pipelineVersion = contract.pipelineContract.pipelineVersion,
            )
        }
    }
}

@Serializable
enum class SourceSeparationCacheProfileOrigin {
    Official,
    Sidecar,
    Custom,
}

object SourceSeparationCacheContractFingerprint {
    fun from(snapshot: SourceSeparationCacheContractSnapshot): String {
        snapshot.multiTensorContract?.let { contract ->
            return SourceSeparationCacheCanonicalEncoding.sha256(
                namespace = "booming-ss-cache-contract-multitensor-v1",
                fields = buildList {
                    contract.tensorContract.inputs.forEach { tensor ->
                        add("input")
                        add(tensor.index.toString())
                        add(tensor.name)
                        add("float32")
                        add(tensor.shape.joinToString(","))
                        add(tensor.axes.joinToString(","))
                    }
                    contract.tensorContract.outputs.forEach { tensor ->
                        add("output")
                        add(tensor.index.toString())
                        add(tensor.name)
                        add("float32")
                        add(tensor.shape.joinToString(","))
                        add(tensor.axes.joinToString(","))
                    }
                    contract.tensorContract.outputBindings.forEach { binding ->
                        add("output-binding")
                        add(binding.tensorIndex.toString())
                        add(binding.tensorName)
                        add(binding.stemAxis.toString())
                        add("stem-axis")
                        add(binding.stemIds.joinToString(","))
                    }
                    val pipeline = contract.pipelineContract
                    add(pipeline.sampleRate.toString())
                    add(pipeline.channelCount.toString())
                    add(pipeline.windowSamples.toString())
                    add(pipeline.fftSize.toString())
                    add(pipeline.hopLength.toString())
                    add("neural-core-waveform-frequency-ola")
                    snapshot.expectedStemSet().stems.forEach { stem -> addStem(stem) }
                    add(snapshot.pipelineId)
                    add(snapshot.pipelineVersion.toString())
                },
            )
        }
        val tensor = requireNotNull(snapshot.tensorContract)
        val dsp = requireNotNull(snapshot.dsp)
        val stems = snapshot.expectedStemSet()
        return SourceSeparationCacheCanonicalEncoding.sha256(
            namespace = "booming-ss-cache-contract-v2",
            fields = buildList {
                addTensor(tensor.input)
                addTensor(tensor.output)
                add(tensor.batchSize.toString())
                add(tensor.complexChannelCount.toString())
                add(dsp.sampleRate.toString())
                add(dsp.channelCount.toString())
                add(dsp.nFft.toString())
                add(dsp.hopLength.toString())
                add(dsp.dimF.toString())
                add(dsp.dimTPower.toString())
                add(dsp.modelTimeFrames.toString())
                add(dsp.window.canonicalName())
                add(dsp.modelOutputScale.toBits().toString())
                stems.stems.forEach { stem -> addStem(stem) }
                add(snapshot.pipelineId)
                add(snapshot.pipelineVersion.toString())
            },
        )
    }

    private fun MutableList<String>.addTensor(tensor: ContractTensor) {
        add(tensor.name)
        add(tensor.dtype.canonicalName())
        add(tensor.layout.canonicalName())
        add(tensor.shape.joinToString(","))
    }

    private fun MutableList<String>.addStem(stem: StemDescriptor) {
        add(stem.stemId.value)
        add(stem.semanticId.value)
        add(stem.order.toString())
        when (val production = stem.production) {
            is StemProduction.DirectModelOutput -> {
                add("direct-model-output")
                add(production.bindingId)
                add(production.stemIndex.toString())
            }
            is StemProduction.DerivedResidual -> {
                add("derived-residual")
                add(production.sourceStemId.value)
                add(production.rule.canonicalName())
            }
            is StemProduction.PipelineNative -> {
                add("pipeline-native")
                add(production.stemIndex.toString())
            }
        }
    }

    private fun ContractTensorLayout.canonicalName(): String = when (this) {
        ContractTensorLayout.Nhwc -> "NHWC"
    }

    private fun ContractDtype.canonicalName(): String = when (this) {
        ContractDtype.Float32 -> "float32"
    }

    private fun ContractWindow.canonicalName(): String = when (this) {
        ContractWindow.PeriodicHann -> "periodic-hann"
    }

    private fun ContractResidualRule.canonicalName(): String = when (this) {
        ContractResidualRule.MixtureMinusScaledModelOutput ->
            "mixture-minus-scaled-model-output"
    }

}

private object SourceSeparationCacheCanonicalEncoding {
    fun sha256(namespace: String, fields: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.updateField(namespace)
        fields.forEach { field -> digest.updateField(field) }
        return digest.digest().toHexString()
    }

    private fun MessageDigest.updateField(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        update(byteArrayOf(
            (bytes.size ushr 24).toByte(),
            (bytes.size ushr 16).toByte(),
            (bytes.size ushr 8).toByte(),
            bytes.size.toByte(),
        ))
        update(bytes)
    }

    private fun ByteArray.toHexString(): String = joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}

private fun requireSha256(value: String, label: String) {
    require(SHA256_PATTERN.matches(value)) { "Cache $label SHA-256 is invalid." }
}

private val SHA256_PATTERN = Regex("^[0-9a-fA-F]{64}$")
