package com.mardous.booming.separation

import android.content.SharedPreferences
import com.mardous.booming.separation.cache.v2.SourceSeparationActiveCacheModelResolution
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheContractSnapshot
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheIdentity
import com.mardous.booming.separation.cache.v2.resolveTrustedActiveCacheModelResolution
import com.mardous.booming.separation.model.contract.SourceSeparationMultiStemReleaseInstaller
import com.mardous.booming.separation.model.contract.SourceSeparationMultiTensorExecutableContractLoader
import com.mardous.booming.separation.model.preset.SourceSeparationActiveSelectionSnapshot
import com.mardous.booming.separation.model.preset.SourceSeparationPresetRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

enum class SourceSeparationModelFamily {
    Mdx,
    Htdemucs,
}

data class SourceSeparationExecutionModelIdentity(
    val family: SourceSeparationModelFamily,
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
        require(modelId.isNotBlank()) { "Execution model ID is empty." }
        require(SHA256_PATTERN.matches(artifactSha256)) {
            "Execution artifact SHA-256 is invalid."
        }
        require(contractId.isNotBlank() && contractSchemaVersion > 0) {
            "Execution contract identity is invalid."
        }
        require(SHA256_PATTERN.matches(contractFingerprint)) {
            "Execution contract fingerprint is invalid."
        }
        require(profileRevisionId.isNotBlank()) { "Execution profile identity is empty." }
        require(pipelineId.isNotBlank() && pipelineVersion > 0) {
            "Execution pipeline identity is invalid."
        }
        require(renderProfileId.isNotBlank()) { "Execution render profile is empty." }
    }

    private companion object {
        val SHA256_PATTERN = Regex("^[0-9a-fA-F]{64}$")
    }

    fun matches(identity: SourceSeparationCacheIdentity): Boolean =
        modelId == identity.modelId &&
            artifactSha256.equals(identity.artifactSha256, ignoreCase = true) &&
            contractId == identity.contractId &&
            contractSchemaVersion == identity.contractSchemaVersion &&
            contractFingerprint.equals(identity.contractFingerprint, ignoreCase = true) &&
            profileRevisionId == identity.profileRevisionId &&
            pipelineId == identity.pipelineId &&
            pipelineVersion == identity.pipelineVersion &&
            renderProfileId == identity.renderProfileId

    fun matches(identity: SourceSeparationExecutionModelIdentity): Boolean =
        family == identity.family &&
            modelId == identity.modelId &&
            artifactSha256.equals(identity.artifactSha256, ignoreCase = true) &&
            contractId == identity.contractId &&
            contractSchemaVersion == identity.contractSchemaVersion &&
            contractFingerprint.equals(identity.contractFingerprint, ignoreCase = true) &&
            profileRevisionId == identity.profileRevisionId &&
            pipelineId == identity.pipelineId &&
            pipelineVersion == identity.pipelineVersion &&
            renderProfileId == identity.renderProfileId
}

data class SourceSeparationExecutionSelectionSnapshot(
    val family: SourceSeparationModelFamily?,
    val modelId: String?,
    val identity: SourceSeparationExecutionModelIdentity?,
    val generation: Long,
) {
    init {
        require(generation >= 0L) { "Execution selection generation is invalid." }
        require((family == null) == (modelId == null)) {
            "Execution selection family and model ID must be present together."
        }
        require(identity == null ||
            (identity.family == family && identity.modelId == modelId)
        ) { "Execution selection identity does not match its selected model." }
    }

    val isResolved: Boolean
        get() = identity != null

    fun matches(identity: SourceSeparationCacheIdentity): Boolean =
        this.identity?.matches(identity) == true

    fun matches(identity: SourceSeparationExecutionModelIdentity): Boolean =
        this.identity?.matches(identity) == true

    companion object {
        fun fromFamilySelections(
            mdx: SourceSeparationActiveSelectionSnapshot,
            multiStem: SourceSeparationMultiStemPlaybackSelectionSnapshot,
        ): SourceSeparationExecutionSelectionSnapshot {
            val multiStemModelId = multiStem.modelId
            return when {
                multiStemModelId != null -> SourceSeparationExecutionSelectionSnapshot(
                    family = SourceSeparationModelFamily.Htdemucs,
                    modelId = multiStemModelId,
                    identity = null,
                    generation = multiStem.generation,
                )
                mdx.reference != null -> SourceSeparationExecutionSelectionSnapshot(
                    family = SourceSeparationModelFamily.Mdx,
                    modelId = mdx.reference.modelId,
                    identity = null,
                    generation = maxOf(mdx.generation, multiStem.generation),
                )
                else -> SourceSeparationExecutionSelectionSnapshot(
                    family = null,
                    modelId = null,
                    identity = null,
                    generation = maxOf(mdx.generation, multiStem.generation),
                )
            }
        }
    }
}

/** One monotonically increasing selection generation shared by every model family. */
class SourceSeparationSelectionGenerationStore(
    private val preferences: SharedPreferences,
) {
    fun current(): Long = synchronized(preferences) {
        preferences.getLong(KEY, 0L).coerceAtLeast(0L)
    }

    fun next(): Long = synchronized(preferences) {
        val next = Math.addExact(current(), 1L)
        check(preferences.edit().putLong(KEY, next).commit()) {
            "Could not persist the source-separation selection generation."
        }
        next
    }

    private companion object {
        const val KEY = "source_separation.active_selection_generation"
    }
}

internal class InMemorySourceSeparationSelectionGenerationStore {
    private var generation = 0L

    @Synchronized
    fun current(): Long = generation

    @Synchronized
    fun next(): Long = Math.addExact(generation, 1L).also { generation = it }
}

/**
 * Projects the two installation stores into the single execution selection
 * consumed by scheduling, playback state, and stale-callback checks.
 */
class SourceSeparationExecutionSelectionResolver(
    private val presetRepository: SourceSeparationPresetRepository,
    private val multiStemSelectionStore: SourceSeparationMultiStemPlaybackSelectionStore,
    private val multiStemInstaller: SourceSeparationMultiStemReleaseInstaller,
) {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined)
    private var cachedInputs: SelectionInputs? = null
    private var cachedSnapshot: SourceSeparationExecutionSelectionSnapshot? = null
    private val _selectionFlow = MutableStateFlow(current())
    val selectionFlow: StateFlow<SourceSeparationExecutionSelectionSnapshot> =
        _selectionFlow.asStateFlow()

    init {
        scope.launch {
            combine(
                presetRepository.activeSelectionFlow,
                multiStemSelectionStore.selectionFlow,
            ) { mdx, multiStem -> SelectionInputs(mdx, multiStem) }
                .collect { _selectionFlow.value = resolve(it) }
        }
    }

    fun current(): SourceSeparationExecutionSelectionSnapshot = resolve(
        SelectionInputs(
            mdx = presetRepository.activeSelectionFlow.value,
            multiStem = multiStemSelectionStore.selectionFlow.value,
        ),
    )

    private fun resolve(inputs: SelectionInputs): SourceSeparationExecutionSelectionSnapshot =
        synchronized(lock) {
            if (cachedInputs == inputs) return@synchronized requireNotNull(cachedSnapshot)
            val generation = maxOf(inputs.mdx.generation, inputs.multiStem.generation)
            val snapshot = inputs.multiStem.modelId?.let { modelId ->
                SourceSeparationExecutionSelectionSnapshot(
                    family = SourceSeparationModelFamily.Htdemucs,
                    modelId = modelId,
                    identity = resolveMultiStemIdentity(modelId),
                    generation = inputs.multiStem.generation,
                )
            } ?: inputs.mdx.reference?.let { reference ->
                SourceSeparationExecutionSelectionSnapshot(
                    family = SourceSeparationModelFamily.Mdx,
                    modelId = reference.modelId,
                    identity = resolveMdxIdentity(),
                    generation = maxOf(inputs.mdx.generation, inputs.multiStem.generation),
                )
            } ?: SourceSeparationExecutionSelectionSnapshot(
                family = null,
                modelId = null,
                identity = null,
                generation = generation,
            )
            cachedInputs = inputs
            cachedSnapshot = snapshot
            snapshot
        }

    private fun resolveMdxIdentity(): SourceSeparationExecutionModelIdentity? {
        val model = (presetRepository.resolveTrustedActiveCacheModelResolution() as?
            SourceSeparationActiveCacheModelResolution.Ready)?.model ?: return null
        return SourceSeparationExecutionModelIdentity(
            family = SourceSeparationModelFamily.Mdx,
            modelId = model.contract.modelId,
            artifactSha256 = model.artifact.sha256,
            contractId = model.contract.contractId,
            contractSchemaVersion = model.contract.contractSchemaVersion,
            contractFingerprint = model.contract.contractFingerprint,
            profileRevisionId = model.contract.profileRevisionId,
            pipelineId = model.contract.pipelineId,
            pipelineVersion = model.contract.pipelineVersion,
            renderProfileId = SourceSeparationCacheIdentity.FP32_RENDER_PROFILE_ID,
        )
    }

    private fun resolveMultiStemIdentity(modelId: String): SourceSeparationExecutionModelIdentity? {
        val installed = multiStemInstaller.installed(modelId) ?: return null
        val executable = runCatching {
            installed.sidecarFile.bufferedReader().use { reader ->
                SourceSeparationMultiTensorExecutableContractLoader.load(reader.readText())
            }
        }.getOrNull() ?: return null
        val contract = executable.modelContract
        val snapshot = SourceSeparationCacheContractSnapshot.fromMultiTensor(executable)
        if (contract.modelId != modelId ||
            !executable.artifact.sha256.equals(installed.modelSha256, ignoreCase = true)
        ) return null
        return SourceSeparationExecutionModelIdentity(
            family = SourceSeparationModelFamily.Htdemucs,
            modelId = contract.modelId,
            artifactSha256 = executable.artifact.sha256,
            contractId = contract.contractId,
            contractSchemaVersion = contract.contractSchemaVersion,
            contractFingerprint = snapshot.contractFingerprint,
            profileRevisionId = contract.contractId,
            pipelineId = contract.pipelineContract.pipelineId,
            pipelineVersion = contract.pipelineContract.pipelineVersion,
            renderProfileId = HtdemucsSourceSeparationEngine.HTDEMUCS_CPU_PROFILE_ID,
        )
    }

    private data class SelectionInputs(
        val mdx: SourceSeparationActiveSelectionSnapshot,
        val multiStem: SourceSeparationMultiStemPlaybackSelectionSnapshot,
    )
}
