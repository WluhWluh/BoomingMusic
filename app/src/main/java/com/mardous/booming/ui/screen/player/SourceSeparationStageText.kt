package com.mardous.booming.ui.screen.player

import android.content.Context
import com.mardous.booming.R

private val preparingWindowStageRegex =
    Regex("^Preparing window (\\d+)/(\\d+)$", RegexOption.IGNORE_CASE)
private val processedWindowStageRegex =
    Regex("^Processed window (\\d+)/(\\d+)$", RegexOption.IGNORE_CASE)

fun Context.localizedSourceSeparationStage(stage: String?): String? {
    val text = stage?.takeIf { it.isNotBlank() } ?: return null
    return when (text) {
        "Preparing model file" ->
            getString(R.string.source_separation_stage_preparing_model_file)
        "Preparing output files" ->
            getString(R.string.source_separation_stage_preparing_output_files)
        "Full decode" ->
            getString(R.string.source_separation_stage_full_decode)
        "Window decode" ->
            getString(R.string.source_separation_stage_window_decode)
        "Processing" ->
            getString(R.string.source_separation_processing_windows)
        else -> {
            preparingWindowStageRegex.matchEntire(text)?.let { match ->
                return getString(
                    R.string.source_separation_stage_preparing_window,
                    match.groupValues[1].toIntOrNull() ?: match.groupValues[1],
                    match.groupValues[2].toIntOrNull() ?: match.groupValues[2],
                )
            }
            processedWindowStageRegex.matchEntire(text)?.let { match ->
                return getString(
                    R.string.source_separation_stage_processed_window,
                    match.groupValues[1].toIntOrNull() ?: match.groupValues[1],
                    match.groupValues[2].toIntOrNull() ?: match.groupValues[2],
                )
            }
            text
        }
    }
}
