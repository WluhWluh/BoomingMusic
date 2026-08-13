package com.mardous.booming.separation

import android.content.SharedPreferences
import androidx.core.content.edit
import com.mardous.booming.util.DEFAULT_SOURCE_SEPARATION_AVERAGE_WINDOW_MS
import com.mardous.booming.util.MAX_SOURCE_SEPARATION_AVERAGE_WINDOW_SAMPLE_COUNT

data class SourceSeparationPerformanceScope(
    val family: SourceSeparationModelFamily,
    val modelId: String,
    val profileId: String,
    val backend: String,
) {
    init {
        require(modelId.isNotBlank() && profileId.isNotBlank() && backend.isNotBlank()) {
            "Source-separation performance scope is incomplete."
        }
    }

    internal val storageSuffix: String
        get() = listOf(family.name, modelId, profileId, backend)
            .joinToString("|") { it.trim() }

    fun debugName(): String =
        "${family.name}:$modelId:$profileId:$backend"
}

class SourceSeparationPerformanceStats(
    private val preferences: SharedPreferences,
) {
    fun averageWindowMs(scope: SourceSeparationPerformanceScope): Long {
        return preferences.getLong(
            averageKey(scope),
            DEFAULT_SOURCE_SEPARATION_AVERAGE_WINDOW_MS,
        ).coerceAtLeast(1L)
    }

    fun recordWindowElapsed(
        scope: SourceSeparationPerformanceScope,
        elapsedMs: Long,
    ): Long = synchronized(preferences) {
        if (elapsedMs <= 0L) return@synchronized averageWindowMs(scope)

        val sampleCount = preferences.getInt(
            sampleCountKey(scope),
            0,
        ).coerceIn(0, MAX_SOURCE_SEPARATION_AVERAGE_WINDOW_SAMPLE_COUNT)
        val previousAverage = if (sampleCount > 0) {
            averageWindowMs(scope)
        } else {
            elapsedMs
        }
        val nextSampleCount = (sampleCount + 1)
            .coerceAtMost(MAX_SOURCE_SEPARATION_AVERAGE_WINDOW_SAMPLE_COUNT)
        val nextAverage = if (sampleCount <= 0) {
            elapsedMs
        } else {
            ((previousAverage * sampleCount) + elapsedMs) / (sampleCount + 1)
        }

        preferences.edit {
            putLong(averageKey(scope), nextAverage)
            putInt(sampleCountKey(scope), nextSampleCount)
        }
        nextAverage
    }

    private fun averageKey(scope: SourceSeparationPerformanceScope): String =
        "$KEY_PREFIX.average.${scope.storageSuffix}"

    private fun sampleCountKey(scope: SourceSeparationPerformanceScope): String =
        "$KEY_PREFIX.samples.${scope.storageSuffix}"

    private companion object {
        const val KEY_PREFIX = "source_separation.performance.v2"
    }
}
