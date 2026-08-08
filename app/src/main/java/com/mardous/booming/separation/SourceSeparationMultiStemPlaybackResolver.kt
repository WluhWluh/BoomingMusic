package com.mardous.booming.separation

import android.content.SharedPreferences
import com.mardous.booming.data.model.Song
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheContractSnapshot
import com.mardous.booming.separation.model.contract.SourceSeparationMultiStemReleaseInstaller
import com.mardous.booming.separation.model.contract.SourceSeparationMultiTensorExecutableContractLoader
import java.util.concurrent.CancellationException

/** Persists the selected experimental multi-stem model independently of MDX presets. */
class SourceSeparationMultiStemPlaybackSelectionStore(
    private val preferences: SharedPreferences,
) {
    fun selectedModelId(): String? = preferences.getString(KEY, null)

    fun select(modelId: String?) {
        preferences.edit().let { editor ->
            if (modelId.isNullOrBlank()) editor.remove(KEY) else editor.putString(KEY, modelId)
            check(editor.commit()) { "Could not persist the multi-stem playback selection." }
        }
    }

    private companion object {
        const val KEY = "source_separation.multistem_playback_model_id"
    }
}

internal class SourceSeparationMultiStemPlaybackResolver(
    private val selection: SourceSeparationMultiStemPlaybackSelectionStore,
    private val installer: SourceSeparationMultiStemReleaseInstaller,
    private val preflightResolver: SourceSeparationModelAwarePreflightResolver,
) {
    fun resolve(
        song: Song,
        shouldCancel: () -> Boolean,
    ): SourceSeparationRuntimeSong? {
        val modelId = selection.selectedModelId() ?: return null
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
