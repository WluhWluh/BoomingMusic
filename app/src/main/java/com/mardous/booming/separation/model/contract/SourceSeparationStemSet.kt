package com.mardous.booming.separation.model.contract

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import java.util.Locale

@Serializable
@JvmInline
value class StemId(val value: String) {
    init {
        require(STABLE_ID.matches(value)) { "Invalid stem ID: $value" }
    }

    override fun toString(): String = value

    companion object {
        val Vocals = StemId("vocals")
        val Instrumental = StemId("instrumental")
        val MdxOrdered = listOf(Vocals, Instrumental)
    }
}

@Serializable
@JvmInline
value class StemSemanticId(val value: String) {
    init {
        require(STABLE_ID.matches(value)) { "Invalid stem semantic ID: $value" }
    }

    override fun toString(): String = value

    companion object {
        val Vocals = StemSemanticId("vocals")
        val Instrumental = StemSemanticId("instrumental")
        val Bass = StemSemanticId("bass")
        val Drums = StemSemanticId("drums")
        val Other = StemSemanticId("other")
        val Guitar = StemSemanticId("guitar")
        val Piano = StemSemanticId("piano")
        val Reverb = StemSemanticId("reverb")
        val NoCrowd = StemSemanticId("no_crowd")
        val TargetStem = StemSemanticId("target_stem")
        val RemainingAudio = StemSemanticId("remaining_audio")
    }
}

@Serializable
data class StemDescriptor(
    val stemId: StemId,
    val semanticId: StemSemanticId,
    val canonicalLabel: String,
    val order: Int,
    val production: StemProduction,
) {
    init {
        require(order >= 0) { "Stem order must not be negative." }
        requireCanonicalStemLabel(canonicalLabel)
    }
}

@Serializable
sealed interface StemProduction {
    @Serializable
    @SerialName("direct-model-output")
    data class DirectModelOutput(
        val bindingId: String,
        val stemIndex: Int,
    ) : StemProduction {
        init {
            require(BINDING_ID.matches(bindingId)) { "Invalid output binding ID: $bindingId" }
            require(stemIndex >= 0) { "Output stem index must not be negative." }
        }
    }

    @Serializable
    @SerialName("derived-residual")
    data class DerivedResidual(
        val sourceStemId: StemId,
        val rule: ContractResidualRule,
    ) : StemProduction

    @Serializable
    @SerialName("pipeline-native")
    data class PipelineNative(
        val stemIndex: Int,
    ) : StemProduction {
        init {
            require(stemIndex >= 0) { "Pipeline stem index must not be negative." }
        }
    }
}

@Serializable
data class StemSet(
    val stems: List<StemDescriptor>,
) {
    init {
        require(stems.isNotEmpty()) { "A stem set must not be empty." }
        require(stems.size <= MAX_PLAYABLE_STEMS) {
            "A stem set exceeds the current product limit of $MAX_PLAYABLE_STEMS."
        }
        require(stems.map(StemDescriptor::stemId).distinct().size == stems.size) {
            "Stem IDs must be unique."
        }
        require(stems.map(StemDescriptor::order) == stems.indices.toList()) {
            "Stem order must be contiguous and already sorted."
        }
        require(stems.map { normalizeStemLabel(it.canonicalLabel) }.distinct().size == stems.size) {
            "Stem labels must be distinct."
        }
        stems.forEach { stem ->
            val residual = stem.production as? StemProduction.DerivedResidual
                ?: return@forEach
            require(residual.sourceStemId != stem.stemId) {
                "A residual stem cannot derive from itself."
            }
            require(stems.any { it.stemId == residual.sourceStemId }) {
                "A residual stem references an unknown source stem."
            }
        }
    }

    operator fun get(stemId: StemId): StemDescriptor? =
        stems.singleOrNull { it.stemId == stemId }

    companion object {
        const val MAX_PLAYABLE_STEMS = 8
    }
}

val ContractStem.canonicalLabel: String
    get() = displayLabel

fun StemContract.toStemSet(): StemSet {
    val modelOutputSemantic = modelOutput.semantic.toSemanticId()
    val modelOutputId = StemId(modelOutputSemantic.value)
    val residualSemantic = residual.semantic.toSemanticId()
    val residualId = StemId(residualSemantic.value)
    return StemSet(
        stems = listOf(
            StemDescriptor(
                stemId = modelOutputId,
                semanticId = modelOutputSemantic,
                canonicalLabel = modelOutput.canonicalLabel,
                order = 0,
                production = StemProduction.DirectModelOutput(
                    bindingId = MDX_OUTPUT_BINDING_ID,
                    stemIndex = 0,
                ),
            ),
            StemDescriptor(
                stemId = residualId,
                semanticId = residualSemantic,
                canonicalLabel = residual.canonicalLabel,
                order = 1,
                production = StemProduction.DerivedResidual(
                    sourceStemId = modelOutputId,
                    rule = residualRule,
                ),
            ),
        ),
    )
}

fun ContractStemSemantic.toSemanticId(): StemSemanticId = when (this) {
    ContractStemSemantic.Vocals -> StemSemanticId.Vocals
    ContractStemSemantic.Instrumental -> StemSemanticId.Instrumental
    ContractStemSemantic.Bass -> StemSemanticId.Bass
    ContractStemSemantic.Drums -> StemSemanticId.Drums
    ContractStemSemantic.Other -> StemSemanticId.Other
    ContractStemSemantic.Reverb -> StemSemanticId.Reverb
    ContractStemSemantic.NoCrowd -> StemSemanticId.NoCrowd
    ContractStemSemantic.TargetStem -> StemSemanticId.TargetStem
    ContractStemSemantic.RemainingAudio -> StemSemanticId.RemainingAudio
}

fun StemSemanticId.toContractSemanticOrNull(): ContractStemSemantic? = when (this) {
    StemSemanticId.Vocals -> ContractStemSemantic.Vocals
    StemSemanticId.Instrumental -> ContractStemSemantic.Instrumental
    StemSemanticId.Bass -> ContractStemSemantic.Bass
    StemSemanticId.Drums -> ContractStemSemantic.Drums
    StemSemanticId.Other -> ContractStemSemantic.Other
    StemSemanticId.Reverb -> ContractStemSemantic.Reverb
    StemSemanticId.NoCrowd -> ContractStemSemantic.NoCrowd
    StemSemanticId.TargetStem -> ContractStemSemantic.TargetStem
    StemSemanticId.RemainingAudio -> ContractStemSemantic.RemainingAudio
    else -> null
}

internal fun normalizeStemLabel(value: String): String =
    value.trim().replace(INTERNAL_WHITESPACE, " ").lowercase(Locale.ROOT)

internal fun requireCanonicalStemLabel(value: String) {
    require(value == value.trim() && value.isNotEmpty()) {
        "Stem label must be non-empty and trimmed."
    }
    require(value.codePointCount(0, value.length) <= MAX_STEM_LABEL_CODE_POINTS) {
        "Stem label is too long."
    }
    require(value.none { character ->
        character.isISOControl() || character == '/' || character == '\\'
    }) { "Stem label contains an unsafe character." }
}

private const val MDX_OUTPUT_BINDING_ID = "output"
private const val MAX_STEM_LABEL_CODE_POINTS = 64
private val STABLE_ID = Regex("^[a-z0-9]+(?:_[a-z0-9]+)*$")
private val BINDING_ID = Regex("^[a-z0-9]+(?:[-_][a-z0-9]+)*$")
private val INTERNAL_WHITESPACE = Regex("\\s+")
