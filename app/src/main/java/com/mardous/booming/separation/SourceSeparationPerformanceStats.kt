package com.mardous.booming.separation

import android.content.SharedPreferences
import androidx.core.content.edit
import com.mardous.booming.util.DEFAULT_SOURCE_SEPARATION_AVERAGE_WINDOW_MS
import com.mardous.booming.util.MAX_SOURCE_SEPARATION_AVERAGE_WINDOW_SAMPLE_COUNT
import com.mardous.booming.util.SOURCE_SEPARATION_AVERAGE_WINDOW_MS
import com.mardous.booming.util.SOURCE_SEPARATION_AVERAGE_WINDOW_SAMPLE_COUNT

class SourceSeparationPerformanceStats(
    private val preferences: SharedPreferences,
) {
    fun averageWindowMs(): Long {
        return preferences.getLong(
            SOURCE_SEPARATION_AVERAGE_WINDOW_MS,
            DEFAULT_SOURCE_SEPARATION_AVERAGE_WINDOW_MS,
        ).coerceAtLeast(1L)
    }

    fun recordWindowElapsed(elapsedMs: Long): Long {
        if (elapsedMs <= 0L) return averageWindowMs()

        val sampleCount = preferences.getInt(
            SOURCE_SEPARATION_AVERAGE_WINDOW_SAMPLE_COUNT,
            0,
        ).coerceIn(0, MAX_SOURCE_SEPARATION_AVERAGE_WINDOW_SAMPLE_COUNT)
        val previousAverage = if (sampleCount > 0) {
            averageWindowMs()
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
            putLong(SOURCE_SEPARATION_AVERAGE_WINDOW_MS, nextAverage)
            putInt(SOURCE_SEPARATION_AVERAGE_WINDOW_SAMPLE_COUNT, nextSampleCount)
        }
        return nextAverage
    }
}
