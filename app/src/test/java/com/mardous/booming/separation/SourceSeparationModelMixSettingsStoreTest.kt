package com.mardous.booming.separation

import android.content.SharedPreferences
import com.mardous.booming.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceSeparationModelMixSettingsStoreTest {
    private val preferences = MemorySharedPreferences()
    private val store = SourceSeparationModelMixSettingsStore(preferences)

    @Test
    fun `global blends are isolated by family and model`() {
        val mdxA = SourceSeparationMixModelKey.mdx("model_a")
        val mdxB = SourceSeparationMixModelKey.mdx("model_b")
        val multiStemA = SourceSeparationMixModelKey.multiStem("model_a")

        store.writeGlobalBlend(mdxA, 0.2f)
        store.writeGlobalBlend(mdxB, 0.8f)
        store.writeGlobalBlend(multiStemA, 0.35f)

        assertEquals(0.2f, store.readGlobalBlend(mdxA))
        assertEquals(0.8f, store.readGlobalBlend(mdxB))
        assertEquals(0.35f, store.readGlobalBlend(multiStemA))
    }

    @Test
    fun `global stem gains preserve semantic identity and synthetic demand`() {
        val model = SourceSeparationMixModelKey.multiStem("htdemucs_4s")
        val stemIds = listOf("drums", "bass", "other", "vocals")

        store.writeGlobalStemGains(
            model = model,
            stemIds = stemIds,
            gains = listOf(1f, 0.4f, 1f, 0.75f),
        )

        assertEquals(
            mapOf("vocals" to 0.75f, "drums" to 1f, "bass" to 0.4f, "other" to 1f),
            store.readGlobalStemGains(
                model,
                listOf("vocals", "drums", "bass", "other"),
            ),
        )
        assertEquals(0f, store.readGlobalBlend(model))
        assertNull(store.readGlobalStemGains(model, stemIds + "guitar"))
    }

    @Test
    fun `pending song blends are isolated by model`() {
        val song = song(42L, "/music/example.flac")
        val first = SourceSeparationMixModelKey.mdx("first")
        val second = SourceSeparationMixModelKey.mdx("second")

        store.writePendingSongBlend(first, song, 0.15f)
        store.writePendingSongBlend(second, song, 0.85f)

        assertEquals(0.15f, store.readPendingSongBlend(first, song))
        assertEquals(0.85f, store.readPendingSongBlend(second, song))

        store.removePendingSongBlend(first, song)
        assertNull(store.readPendingSongBlend(first, song))
        assertEquals(0.85f, store.readPendingSongBlend(second, song))
    }

    @Test
    fun `pending stem gains require the exact cache and stem set`() {
        val cacheA = "a".repeat(64)
        val cacheB = "b".repeat(64)
        val stemIds = listOf("drums", "bass", "other", "vocals")

        store.writePendingStemGains(cacheA, stemIds, listOf(1f, 0.5f, 1f, 1f))

        assertEquals(
            mapOf("drums" to 1f, "bass" to 0.5f, "other" to 1f, "vocals" to 1f),
            store.readPendingStemGains(cacheA, stemIds),
        )
        assertNull(store.readPendingStemGains(cacheB, stemIds))
        assertNull(store.readPendingStemGains(cacheA, stemIds - "other"))
    }

    private fun song(id: Long, data: String) = Song(
        id = id,
        data = data,
        title = "Fixture",
        trackNumber = 1,
        year = 2026,
        size = 1L,
        duration = 1_000L,
        dateAdded = 1L,
        rawDateModified = 1L,
        albumId = 1L,
        albumName = "Album",
        artistId = 1L,
        artistName = "Artist",
        albumArtistName = null,
        genreName = null,
    )
}

internal class MemorySharedPreferences : SharedPreferences {
    private val values = linkedMapOf<String, Any?>()
    private val listeners = linkedSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getAll(): Map<String, *> = values.toMap()
    override fun getString(key: String?, defValue: String?): String? =
        values[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? =
        (values[key] as? Set<String>)?.toSet() ?: defValues

    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float =
        values[key] as? Float ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        values[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) {
        if (listener != null) listeners += listener
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) {
        if (listener != null) listeners -= listener
    }

    private inner class Editor : SharedPreferences.Editor {
        private val updates = linkedMapOf<String, Any?>()
        private val removals = linkedSetOf<String>()
        private var clear = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
            requireNotNull(key)
            updates[key] = value
            removals -= key
        }

        override fun putStringSet(
            key: String?,
            values: Set<String>?,
        ): SharedPreferences.Editor = apply {
            requireNotNull(key)
            updates[key] = values?.toSet()
            removals -= key
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor =
            put(key, value)

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor =
            put(key, value)

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor =
            put(key, value)

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor =
            put(key, value)

        override fun remove(key: String?): SharedPreferences.Editor = apply {
            requireNotNull(key)
            updates.remove(key)
            removals += key
        }

        override fun clear(): SharedPreferences.Editor = apply { clear = true }

        override fun commit(): Boolean {
            val changed = linkedSetOf<String>()
            if (clear) {
                changed += values.keys
                values.clear()
            }
            removals.forEach { key ->
                if (values.remove(key) != null) changed += key
            }
            updates.forEach { (key, value) ->
                if (value == null) {
                    if (values.remove(key) != null) changed += key
                } else if (values[key] != value) {
                    values[key] = value
                    changed += key
                }
            }
            changed.forEach { key ->
                listeners.forEach { listener ->
                    listener.onSharedPreferenceChanged(this@MemorySharedPreferences, key)
                }
            }
            return true
        }

        override fun apply() {
            commit()
        }

        private fun put(key: String?, value: Any): SharedPreferences.Editor = apply {
            requireNotNull(key)
            updates[key] = value
            removals -= key
        }
    }
}
