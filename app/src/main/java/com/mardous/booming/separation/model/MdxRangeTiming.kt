package com.mardous.booming.separation.model

import java.io.File
import java.util.Locale

internal class MdxRangeTimingAccumulator {
    private val stageMs = linkedMapOf<String, Long>()

    fun add(stage: String, elapsedMs: Long) {
        stageMs[stage] = (stageMs[stage] ?: 0L) + elapsedMs
    }

    fun toReport(
        audioDurationSeconds: Double,
        windowCount: Int,
        totalMs: Long,
        runtimeSettings: MdxRuntimeSettings,
        modelVariant: MdxModelVariant,
        sourceDecodeDiagnostics: MdxSourceDecodeDiagnostics,
    ): MdxRangeTimingReport {
        return MdxRangeTimingReport(
            audioDurationSeconds = audioDurationSeconds,
            windowCount = windowCount,
            totalMs = totalMs,
            runtimeSettings = runtimeSettings,
            modelVariant = modelVariant,
            sourceDecodeDiagnostics = sourceDecodeDiagnostics,
            stageMs = LinkedHashMap(stageMs),
        )
    }
}

data class MdxRangeTimingReport(
    val audioDurationSeconds: Double,
    val windowCount: Int,
    val totalMs: Long,
    val runtimeSettings: MdxRuntimeSettings,
    val modelVariant: MdxModelVariant,
    val sourceDecodeDiagnostics: MdxSourceDecodeDiagnostics,
    val stageMs: Map<String, Long>,
) {
    fun toFileText(
        vocalsFile: File,
        instrumentalFile: File,
    ): String {
        return buildString {
            appendLine("Range separation timing report")
            appendLine("Audio duration: ${decimal(audioDurationSeconds)} seconds")
            appendLine("Windows: $windowCount")
            appendLine("Model: ${modelVariant.displayName}")
            appendLine(runtimeSettings.toDisplayText())
            appendLine(sourceDecodeDiagnostics.toDisplayText())
            appendLine("Vocals: ${vocalsFile.absolutePath}")
            appendLine("Instrumental: ${instrumentalFile.absolutePath}")
            appendLine()
            appendLine("Timing:")
            appendLine("Total: ${seconds(totalMs)} (${decimal(runtimeAudioFactor())}x audio duration)")
            appendLine("Average per window: ${seconds(perWindowMs(totalMs))}")
            for ((stage, ms) in stageMs) {
                appendStage(stage, ms, perWindow = stage in PER_WINDOW_STAGES)
            }
            appendStage("Control overhead", (totalMs - stageMs.values.sum()).coerceAtLeast(0L))
            appendLine()
            appendLine("Note: timing report file writing is excluded from the measured total.")
        }
    }

    private fun StringBuilder.appendStage(
        label: String,
        ms: Long,
        perWindow: Boolean = false,
    ) {
        append(label)
        append(": ")
        append(seconds(ms))
        append(" (")
        append(decimal(if (totalMs > 0L) ms * 100.0 / totalMs else 0.0))
        append("%")
        if (perWindow && windowCount > 0) {
            append(", ")
            append(seconds(perWindowMs(ms)))
            append("/window")
        }
        appendLine(")")
    }

    private fun runtimeAudioFactor(): Double {
        return if (audioDurationSeconds > 0.0) totalMs / 1000.0 / audioDurationSeconds else 0.0
    }

    private fun perWindowMs(ms: Long): Double {
        return if (windowCount > 0) ms.toDouble() / windowCount else 0.0
    }

    private fun seconds(ms: Long): String = seconds(ms.toDouble())

    private fun seconds(ms: Double): String = "${decimal(ms / 1000.0)}s"

    private fun decimal(value: Double): String = String.format(Locale.US, "%.2f", value)

    private companion object {
        val PER_WINDOW_STAGES = setOf(
            "Window input",
            "Window decode",
            "Window resample",
            "STFT",
            "Tensor create",
            "ONNX inference",
            "Output flatten",
            "ISTFT",
            "Stem subtract",
            "PCM convert",
            "WAV write",
        )
    }
}
