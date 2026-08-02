package com.mardous.booming.util.backup

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mardous.booming.util.DEFAULT_SOURCE_SEPARATION_GPU_ENABLED
import com.mardous.booming.util.SOURCE_SEPARATION_GPU_ENABLED
import com.mardous.booming.util.readSourceSeparationGpuEnabled
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class BackupGpuPreferenceDeviceTest {
    @Test
    fun test01_missingKeyDefaultsToGpuAndCommittedValueReopens() {
        preferences().edit().clear().commitOrFail()
        assertFalse(preferences().contains(SOURCE_SEPARATION_GPU_ENABLED))
        assertTrue(preferences().readSourceSeparationGpuEnabled())

        preferences().edit()
            .putBoolean(SOURCE_SEPARATION_GPU_ENABLED, false)
            .commitOrFail()
        assertFalse(preferences().readSourceSeparationGpuEnabled())
        preferences().edit().clear().commitOrFail()
    }

    @Test
    fun test02_schemaV2RestoreAppliesCanonicalGpuValue() {
        val definitions = requireNotNull(
            BackupSettingsPolicy.sourceSeparationSettingsForSchema(2),
        )
        preferences().edit().clear().commitOrFail()

        preferences().edit().apply {
            putPortablePreferences(
                mapOf(SOURCE_SEPARATION_GPU_ENABLED to JsonPrimitive(false)),
                definitions,
            )
        }.commitOrFail()
        assertFalse(preferences().readSourceSeparationGpuEnabled())

        preferences().edit().apply {
            putPortablePreferences(
                mapOf(SOURCE_SEPARATION_GPU_ENABLED to JsonPrimitive(true)),
                definitions,
            )
        }.commitOrFail()
        assertTrue(preferences().readSourceSeparationGpuEnabled())
        preferences().edit().clear().commitOrFail()
    }

    @Test
    fun test03_schemaV1RestoreDoesNotOverwriteGpuPreference() {
        preferences().edit()
            .clear()
            .putBoolean(SOURCE_SEPARATION_GPU_ENABLED, false)
            .commitOrFail()
        val definitions = requireNotNull(
            BackupSettingsPolicy.sourceSeparationSettingsForSchema(1),
        )

        preferences().edit().apply {
            putPortablePreferences(
                mapOf("source_separation.global_blend" to JsonPrimitive(0.25f)),
                definitions,
            )
        }.commitOrFail()

        assertFalse(preferences().readSourceSeparationGpuEnabled())
        preferences().edit().clear().commitOrFail()
    }

    @Test
    fun test90_restartProbeWritesGpuPreference() {
        preferences().edit()
            .clear()
            .putBoolean(SOURCE_SEPARATION_GPU_ENABLED, false)
            .putBoolean(RESTART_MARKER_KEY, true)
            .commitOrFail()
    }

    @Test
    fun test91_restartProbeReadsGpuPreference() {
        assertTrue(preferences().getBoolean(RESTART_MARKER_KEY, false))
        assertFalse(
            preferences().getBoolean(
                SOURCE_SEPARATION_GPU_ENABLED,
                DEFAULT_SOURCE_SEPARATION_GPU_ENABLED,
            ),
        )
        preferences().edit().clear().commitOrFail()
    }

    private fun preferences() = InstrumentationRegistry.getInstrumentation()
        .targetContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun android.content.SharedPreferences.Editor.commitOrFail() {
        check(commit()) { "Unable to commit the GPU preference test state." }
    }

    private companion object {
        const val PREFERENCES_NAME = "phase5f_gpu_preference_test"
        const val RESTART_MARKER_KEY = "restart_marker"
    }
}
