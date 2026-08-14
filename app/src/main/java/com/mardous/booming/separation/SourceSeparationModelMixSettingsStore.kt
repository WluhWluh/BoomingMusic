package com.mardous.booming.separation

import android.content.SharedPreferences
import androidx.core.content.edit
import com.mardous.booming.data.model.Song
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

enum class SourceSeparationMixModelFamily(val storageId: String) {
    Mdx("mdx"),
    MultiStem("multistem"),
}

data class SourceSeparationMixModelKey(
    val family: SourceSeparationMixModelFamily,
    val modelId: String,
) {
    init {
        require(modelId.isNotBlank()) { "Source-separation mix model ID is empty." }
    }

    internal val storageId: String
        get() = "${family.storageId}.$modelId"

    companion object {
        fun mdx(modelId: String) = SourceSeparationMixModelKey(
            family = SourceSeparationMixModelFamily.Mdx,
            modelId = modelId,
        )

        fun multiStem(modelId: String) = SourceSeparationMixModelKey(
            family = SourceSeparationMixModelFamily.MultiStem,
            modelId = modelId,
        )
    }
}

class SourceSeparationModelMixSettingsStore(
    private val preferences: SharedPreferences,
) {
    fun readGlobalBlend(model: SourceSeparationMixModelKey?): Float {
        if (model == null) return SourceSeparationBlendDemand.CENTER_BLEND
        return preferences.getFloat(
            globalBlendKey(model),
            SourceSeparationBlendDemand.CENTER_BLEND,
        ).coerceIn(0f, 1f)
    }

    fun writeGlobalBlend(model: SourceSeparationMixModelKey?, blend: Float): Boolean {
        if (model == null) return false
        preferences.edit {
            putFloat(globalBlendKey(model), blend.coerceIn(0f, 1f))
        }
        return true
    }

    fun readGlobalStemGains(
        model: SourceSeparationMixModelKey,
        stemIds: List<String>,
    ): Map<String, Float>? = readStemGains(globalStemGainsKey(model), stemIds)

    fun writeGlobalStemGains(
        model: SourceSeparationMixModelKey,
        stemIds: List<String>,
        gains: List<Float>,
    ) {
        val ordered = SourceSeparationStemGainPolicy.orderedMap(stemIds, gains)
        preferences.edit {
            putString(globalStemGainsKey(model), encodeStemGains(stemIds, gains))
            putFloat(
                globalBlendKey(model),
                SourceSeparationStemGainPolicy.demandBlend(ordered.values),
            )
        }
    }

    fun readPendingSongBlend(
        model: SourceSeparationMixModelKey?,
        song: Song,
    ): Float? {
        if (model == null || song == Song.emptySong) return null
        val key = pendingSongBlendKey(model, song)
        if (!preferences.contains(key)) return null
        return preferences.getFloat(
            key,
            SourceSeparationBlendDemand.CENTER_BLEND,
        ).coerceIn(0f, 1f)
    }

    fun writePendingSongBlend(
        model: SourceSeparationMixModelKey?,
        song: Song,
        blend: Float,
    ): Boolean {
        if (model == null || song == Song.emptySong) return false
        preferences.edit {
            putFloat(pendingSongBlendKey(model, song), blend.coerceIn(0f, 1f))
        }
        return true
    }

    fun removePendingSongBlend(
        model: SourceSeparationMixModelKey?,
        song: Song,
    ) {
        if (model == null || song == Song.emptySong) return
        preferences.edit {
            remove(pendingSongBlendKey(model, song))
        }
    }

    fun readPendingStemGains(
        cacheKey: String,
        stemIds: List<String>,
    ): Map<String, Float>? = readStemGains(pendingStemGainsKey(cacheKey), stemIds)

    fun writePendingStemGains(
        cacheKey: String,
        stemIds: List<String>,
        gains: List<Float>,
    ) {
        SourceSeparationStemGainPolicy.orderedMap(stemIds, gains)
        preferences.edit {
            putString(pendingStemGainsKey(cacheKey), encodeStemGains(stemIds, gains))
        }
    }

    fun removePendingStemGains(cacheKey: String) {
        preferences.edit {
            remove(pendingStemGainsKey(cacheKey))
        }
    }

    private fun readStemGains(
        key: String,
        stemIds: List<String>,
    ): Map<String, Float>? {
        val encoded = runCatching { preferences.getString(key, null) }
            .getOrElse {
                preferences.edit { remove(key) }
                return null
            } ?: return null
        val decoded = runCatching {
            val stored = JSON.decodeFromString<StoredStemGains>(encoded)
            val ordered = SourceSeparationStemGainPolicy.orderedGains(
                stemIds = stemIds,
                gainsByStemId = SourceSeparationStemGainPolicy.orderedMap(
                    stored.stemIds,
                    stored.gains,
                ),
            ) ?: return@runCatching null
            SourceSeparationStemGainPolicy.orderedMap(stemIds, ordered)
        }.getOrNull()
        if (decoded == null) {
            preferences.edit { remove(key) }
        }
        return decoded
    }

    private fun encodeStemGains(stemIds: List<String>, gains: List<Float>): String {
        val ordered = SourceSeparationStemGainPolicy.orderedMap(stemIds, gains)
        return JSON.encodeToString(
            StoredStemGains(
                schemaVersion = STEM_GAINS_SCHEMA_VERSION,
                stemIds = ordered.keys.toList(),
                gains = ordered.values.toList(),
            ),
        )
    }

    private fun globalBlendKey(model: SourceSeparationMixModelKey): String =
        "$KEY_GLOBAL_BLEND.${model.storageId}"

    private fun globalStemGainsKey(model: SourceSeparationMixModelKey): String =
        "$KEY_GLOBAL_STEM_GAINS.${model.storageId}"

    private fun pendingSongBlendKey(
        model: SourceSeparationMixModelKey,
        song: Song,
    ): String {
        val identity = "${model.storageId}|${song.id}|${song.data}"
        return "$KEY_PENDING_SONG_BLEND.${sha256Hex(identity)}"
    }

    private fun pendingStemGainsKey(cacheKey: String): String =
        "$KEY_PENDING_STEM_GAINS.$cacheKey"

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.encodeToByteArray())
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    @Serializable
    private data class StoredStemGains(
        val schemaVersion: Int,
        val stemIds: List<String>,
        val gains: List<Float>,
    ) {
        init {
            require(schemaVersion == STEM_GAINS_SCHEMA_VERSION) {
                "Unsupported stored stem-gain schema: $schemaVersion"
            }
        }
    }

    private companion object {
        val JSON = Json {
            encodeDefaults = true
            ignoreUnknownKeys = false
            isLenient = false
            coerceInputValues = false
            explicitNulls = false
        }

        const val STEM_GAINS_SCHEMA_VERSION = 1

        const val KEY_GLOBAL_BLEND = "source_separation.global_blend"
        const val KEY_GLOBAL_STEM_GAINS = "source_separation.global_stem_gains"
        const val KEY_PENDING_SONG_BLEND = "source_separation.per_song_blend.pending"
        const val KEY_PENDING_STEM_GAINS = "source_separation.stem_gains.pending"
    }
}

internal fun SourceSeparationRuntimeSong.toMixModelKey(): SourceSeparationMixModelKey =
    if (model == null) {
        SourceSeparationMixModelKey.multiStem(modelId)
    } else {
        SourceSeparationMixModelKey.mdx(modelId)
    }
