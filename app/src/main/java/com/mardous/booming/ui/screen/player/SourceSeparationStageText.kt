package com.mardous.booming.ui.screen.player

import android.content.Context
import androidx.annotation.StringRes
import com.mardous.booming.R

private val preparingWindowStageRegex =
    Regex("^Preparing window (\\d+)/(\\d+)$", RegexOption.IGNORE_CASE)
private val processingWindowStageRegex =
    Regex("^Processing window (\\d+)/(\\d+)$", RegexOption.IGNORE_CASE)
private val processedWindowStageRegex =
    Regex("^Processed window (\\d+)/(\\d+)$", RegexOption.IGNORE_CASE)
private val checkingWindowDecodeStageRegex =
    Regex("^Checking (.+) window decoding$", RegexOption.IGNORE_CASE)
private val usingWindowDecodeStageRegex =
    Regex("^Using (.+) window decoding$", RegexOption.IGNORE_CASE)

internal data class SourceSeparationStageResource(
    @StringRes val resourceId: Int,
    val formatArgs: List<Any> = emptyList(),
)

fun Context.localizedSourceSeparationStage(stage: String?): String? {
    val text = stage?.takeIf { it.isNotBlank() } ?: return null
    val resource = sourceSeparationStageResource(text) ?: return text
    return getString(resource.resourceId, *resource.formatArgs.toTypedArray())
}

internal fun sourceSeparationStageResource(stage: String): SourceSeparationStageResource? {
    val directResource = when (stage) {
        "Preparing model file" -> R.string.source_separation_stage_preparing_model_file
        "Preparing output files" -> R.string.source_separation_stage_preparing_output_files
        "Creating model session" -> R.string.source_separation_stage_creating_model_session
        "Inspecting source audio" -> R.string.source_separation_stage_inspecting_source_audio
        "Decoding source audio" -> R.string.source_separation_stage_decoding_source_audio
        "Resampling source audio" -> R.string.source_separation_stage_resampling_source_audio
        "Computing global normalization" ->
            R.string.source_separation_stage_computing_global_normalization
        "Hashing source audio" -> R.string.source_separation_stage_hashing_source_audio
        "Writing timing report" -> R.string.source_separation_stage_writing_timing_report
        "Completed" -> R.string.source_separation_stage_completed
        "Full decode" -> R.string.source_separation_stage_full_decode
        "Window decode" -> R.string.source_separation_stage_window_decode
        "Processing" -> R.string.source_separation_processing_windows
        else -> null
    }
    if (directResource != null) return SourceSeparationStageResource(directResource)

    return preparingWindowStageRegex.toWindowResource(
        stage,
        R.string.source_separation_stage_preparing_window,
    ) ?: processingWindowStageRegex.toWindowResource(
        stage,
        R.string.source_separation_stage_processing_window,
    ) ?: processedWindowStageRegex.toWindowResource(
        stage,
        R.string.source_separation_stage_processed_window,
    ) ?: checkingWindowDecodeStageRegex.toProfileResource(
        stage,
        R.string.source_separation_stage_checking_window_decode,
    ) ?: usingWindowDecodeStageRegex.toProfileResource(
        stage,
        R.string.source_separation_stage_using_window_decode,
    )
}

private fun Regex.toWindowResource(
    stage: String,
    @StringRes resourceId: Int,
): SourceSeparationStageResource? {
    val match = matchEntire(stage) ?: return null
    val windowIndex = match.groupValues[1].toIntOrNull() ?: return null
    val windowCount = match.groupValues[2].toIntOrNull() ?: return null
    return SourceSeparationStageResource(resourceId, listOf(windowIndex, windowCount))
}

private fun Regex.toProfileResource(
    stage: String,
    @StringRes resourceId: Int,
): SourceSeparationStageResource? {
    val profile = matchEntire(stage)?.groupValues?.get(1)?.takeIf(String::isNotBlank)
        ?: return null
    return SourceSeparationStageResource(resourceId, listOf(profile))
}
