package com.mardous.booming.separation

import android.content.SharedPreferences
import com.mardous.booming.data.model.Song
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheContractSnapshot
import com.mardous.booming.separation.model.contract.SourceSeparationMultiStemReleaseInstaller
import com.mardous.booming.separation.model.contract.SourceSeparationMultiTensorExecutableContractLoader
import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Persists the selected experimental multi-stem model independently of MDX presets. */
class SourceSeparationMultiStemPlaybackSelectionStore(
    private val preferences: SharedPreferences,
) {
    private val lock = Any()
    private val _selectionFlow = MutableStateFlow(
        SourceSeparationMultiStemPlaybackSelectionSnapshot(
            modelId = preferences.getString(KEY, null),
            generation = 0L,
        ),
    )
    val selectionFlow = _selectionFlow.asStateFlow()

    fun selectedModelId(): String? = selectionFlow.value.modelId

    fun select(modelId: String?) = synchronized(lock) {
        val normalized = modelId?.takeIf(String::isNotBlank)
        val previous = _selectionFlow.value
        if (previous.modelId == normalized) return@synchronized
        preferences.edit().let { editor ->
            if (normalized == null) editor.remove(KEY) else editor.putString(KEY, normalized)
            check(editor.commit()) { "Could not persist the multi-stem playback selection." }
        }
        check(preferences.getString(KEY, null) == normalized) {
            "The multi-stem playback selection could not be committed."
        }
        _selectionFlow.value = SourceSeparationMultiStemPlaybackSelectionSnapshot(
            modelId = normalized,
            generation = previous.generation + 1L,
        )
    }

    private companion object {
        const val KEY = "source_separation.multistem_playback_model_id"
    }
}

data class SourceSeparationMultiStemPlaybackSelectionSnapshot(
    val modelId: String?,
    val generation: Long,
) {
    init {
        require(modelId == null || modelId.isNotBlank()) {
            "The multi-stem playback model ID is invalid."
        }
        require(generation >= 0L) { "The multi-stem selection generation is invalid." }
    }
}

internal interface SourceSeparationMultiStemRuntimeResolver {
    fun selectedModelId(): String?

    fun resolve(
        song: Song,
        shouldCancel: () -> Boolean,
    ): SourceSeparationRuntimeSong?
}

internal class SourceSeparationMultiStemPlaybackResolver(
    private val selection: SourceSeparationMultiStemPlaybackSelectionStore,
    private val installer: SourceSeparationMultiStemReleaseInstaller,
    private val preflightResolver: SourceSeparationModelAwarePreflightResolver,
) : SourceSeparationMultiStemRuntimeResolver {
    override fun selectedModelId(): String? = selection.selectedModelId()

    override fun resolve(
        song: Song,
        shouldCancel: () -> Boolean,
    ): SourceSeparationRuntimeSong? {
        val modelId = selectedModelId() ?: return null
        val installed = installer.installed(modelId) ?: return null
        if (shouldCancel()) throw CancellationException("Multi-stem playback resolution canceled.")
        val input = SourceSeparationModelAwareSongInput.from(song)
        val preflight = preflightResolver.resolve(input.sourceUri, shouldCancel)
        val executable = installed.sidecarFile.bufferedReader().use { reader ->
            SourceSeparationMultiTensorExecutableContractLoader.load(reader.readText())
        }
        val snapshot = SourceSeparationCacheContractSnapshot.fromMultiTensor(executable)
        return SourceSeparationRuntimeSong.forMultiStem(
            song = song,
            identity = snapshot.identity(
                preflight.identity,
                HtdemucsSourceSeparationEngine.HTDEMUCS_CPU_PROFILE_ID,
            ),
            input = input,
            preflight = preflight,
        )
    }
}
