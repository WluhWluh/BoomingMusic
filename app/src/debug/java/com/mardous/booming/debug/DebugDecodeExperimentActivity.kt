package com.mardous.booming.debug

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.util.Log
import com.mardous.booming.separation.audio.AudioWindowDecodeCandidateFamily
import com.mardous.booming.separation.audio.AudioWindowDecodeCandidateFamilySummary
import com.mardous.booming.separation.audio.AudioWindowDecodeExperiment
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread

class DebugDecodeExperimentActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val inputDirPath = intent.getStringExtra(EXTRA_INPUT_DIR)
            ?: DEFAULT_INPUT_DIR
        val outputTag = intent.getStringExtra(EXTRA_OUTPUT_TAG)
            ?.sanitizePathSegment()
            ?.ifBlank { null }
            ?: "batch-${System.currentTimeMillis()}"

        thread(name = "DebugDecodeExperiment") {
            try {
                runBatch(inputDirPath = inputDirPath, outputTag = outputTag)
            } catch (error: Throwable) {
                Log.e(TAG, "Batch decode experiment failed.", error)
            } finally {
                finish()
            }
        }
    }

    private fun runBatch(
        inputDirPath: String,
        outputTag: String,
    ) {
        val inputDir = File(inputDirPath)
        val reportRoot = File(
            File(
                getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: filesDir,
                "source-separation/debug/batch-window-decode",
            ),
            outputTag,
        )
        val detailDir = File(reportRoot, "reports")
        detailDir.mkdirs()

        val summaryFile = File(reportRoot, "batch-summary.csv")
        val logFile = File(reportRoot, "batch-log.txt")
        summaryFile.writeText(BatchRow.csvHeader() + "\n", Charsets.UTF_8)
        logFile.writeText(
            "Batch window decode experiment\n" +
                    "Input: ${inputDir.absolutePath}\n" +
                    "Output: ${reportRoot.absolutePath}\n\n",
            Charsets.UTF_8,
        )

        if (!inputDir.isDirectory) {
            val message = "Input directory does not exist: ${inputDir.absolutePath}"
            logFile.appendText("FAILED: $message\n", Charsets.UTF_8)
            Log.e(TAG, message)
            return
        }

        val files = inputDir.walkTopDown()
            .filter { it.isFile && it.extension.lowercase(Locale.US) in AUDIO_EXTENSIONS }
            .sortedBy { it.relativeTo(inputDir).invariantSeparatorsPath }
            .toList()

        logFile.appendText("Files: ${files.size}\n\n", Charsets.UTF_8)
        Log.i(TAG, "Starting batch decode experiment with ${files.size} file(s).")

        files.forEachIndexed { index, file ->
            val relativePath = file.relativeTo(inputDir).invariantSeparatorsPath
            val fileReportDir = File(detailDir, file.nameWithoutExtension.sanitizePathSegment())
            fileReportDir.mkdirs()
            val startedAtMs = SystemClock.elapsedRealtime()
            Log.i(TAG, "(${index + 1}/${files.size}) Running $relativePath")
            logFile.appendText("START ${index + 1}/${files.size}: $relativePath\n", Charsets.UTF_8)

            val row = try {
                val result = AudioWindowDecodeExperiment(this).run(
                    uri = Uri.fromFile(file),
                    displayName = file.name,
                    playbackPositionMs = 0L,
                    reportDir = fileReportDir,
                    onProgress = { progress ->
                        Log.i(
                            TAG,
                            "(${index + 1}/${files.size}) ${progress.percent}% " +
                                    "step ${progress.completedSteps}/${progress.totalSteps}: ${progress.stage}",
                        )
                    },
                )
                BatchRow.success(
                    relativePath = relativePath,
                    file = file,
                    elapsedMs = SystemClock.elapsedRealtime() - startedAtMs,
                    reportFile = result.reportFile,
                    sourceSampleRate = result.sourceSampleRate,
                    channels = result.referenceChannelCount,
                    frames = result.referenceFrameCount,
                    fullDecodeMs = result.fullDecodeMs,
                    probeCount = result.probes.size,
                    songTimelineSummary = result.familySummary(
                        AudioWindowDecodeCandidateFamily.SongTimelineResample,
                    ),
                    prerollSongTimelineSummary = result.familySummary(
                        AudioWindowDecodeCandidateFamily.PrerollSongTimelineResample,
                    ),
                    mp3QuantizedSummary = result.familySummary(
                        AudioWindowDecodeCandidateFamily.SongTimelineMp3QuantizedCalibratedPlacement,
                    ),
                    mp3AdaptiveSummary = result.familySummary(
                        AudioWindowDecodeCandidateFamily.SongTimelineMp3AdaptivePlacement,
                    ),
                    aacTailSummary = result.familySummary(
                        AudioWindowDecodeCandidateFamily.SongTimelineAacTailExtendedTimestampPlacement,
                    ),
                )
            } catch (error: Throwable) {
                Log.e(TAG, "(${index + 1}/${files.size}) Failed $relativePath", error)
                BatchRow.failure(
                    relativePath = relativePath,
                    file = file,
                    elapsedMs = SystemClock.elapsedRealtime() - startedAtMs,
                    error = error,
                )
            }

            summaryFile.appendText(row.toCsvLine() + "\n", Charsets.UTF_8)
            logFile.appendText(
                "END ${index + 1}/${files.size}: $relativePath ${row.status} " +
                        "${row.elapsedMs}ms ${row.errorMessage.orEmpty()}\n",
                Charsets.UTF_8,
            )
        }

        logFile.appendText("\nFinished.\n", Charsets.UTF_8)
        Log.i(TAG, "Batch decode experiment finished: ${reportRoot.absolutePath}")
    }

    private fun String.sanitizePathSegment(): String {
        return replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_')
    }

    private companion object {
        const val TAG = "BatchDecodeExperiment"
        const val EXTRA_INPUT_DIR = "input_dir"
        const val EXTRA_OUTPUT_TAG = "output_tag"
        const val DEFAULT_INPUT_DIR =
            "/sdcard/Android/data/com.mardous.booming.debug/files/Music/decode-cases/luv_in_b_ffmpeg"

        val AUDIO_EXTENSIONS = setOf(
            "aac",
            "alac",
            "flac",
            "m4a",
            "mp3",
            "ogg",
            "opus",
            "wav",
            "wma",
        )
    }
}

private data class BatchRow(
    val relativePath: String,
    val status: String,
    val extension: String,
    val fileBytes: Long,
    val elapsedMs: Long,
    val sourceSampleRate: Int?,
    val channels: Int?,
    val frames: Int?,
    val fullDecodeMs: Long?,
    val probeCount: Int?,
    val reportPath: String?,
    val songTimelineDirectUsability: String?,
    val songTimelineStableDirectMeanError: Double?,
    val songTimelineStableDirectMaxError: Int?,
    val songTimelineStableBestOffsetFrames: String?,
    val prerollSongTimelineDirectUsability: String?,
    val prerollSongTimelineStableDirectMeanError: Double?,
    val prerollSongTimelineStableDirectMaxError: Int?,
    val mp3QuantizedDirectUsability: String?,
    val mp3QuantizedStableDirectMeanError: Double?,
    val mp3QuantizedStableDirectMaxError: Int?,
    val mp3AdaptiveDirectUsability: String?,
    val mp3AdaptiveStableDirectMeanError: Double?,
    val mp3AdaptiveStableDirectMaxError: Int?,
    val mp3AdaptiveStableBestOffsetFrames: String?,
    val aacTailDirectUsability: String?,
    val aacTailStableDirectMeanError: Double?,
    val aacTailStableDirectMaxError: Int?,
    val errorMessage: String?,
) {
    fun toCsvLine(): String {
        return listOf(
            relativePath,
            status,
            extension,
            fileBytes,
            elapsedMs,
            sourceSampleRate,
            channels,
            frames,
            fullDecodeMs,
            probeCount,
            reportPath,
            songTimelineDirectUsability,
            songTimelineStableDirectMeanError?.format3(),
            songTimelineStableDirectMaxError,
            songTimelineStableBestOffsetFrames,
            prerollSongTimelineDirectUsability,
            prerollSongTimelineStableDirectMeanError?.format3(),
            prerollSongTimelineStableDirectMaxError,
            mp3QuantizedDirectUsability,
            mp3QuantizedStableDirectMeanError?.format3(),
            mp3QuantizedStableDirectMaxError,
            mp3AdaptiveDirectUsability,
            mp3AdaptiveStableDirectMeanError?.format3(),
            mp3AdaptiveStableDirectMaxError,
            mp3AdaptiveStableBestOffsetFrames,
            aacTailDirectUsability,
            aacTailStableDirectMeanError?.format3(),
            aacTailStableDirectMaxError,
            errorMessage,
        ).joinToString(",") { it.toCsvCell() }
    }

    companion object {
        fun csvHeader(): String {
            return listOf(
                "relativePath",
                "status",
                "extension",
                "fileBytes",
                "elapsedMs",
                "sourceSampleRate",
                "channels",
                "frames",
                "fullDecodeMs",
                "probeCount",
                "reportPath",
                "songTimelineDirectUsability",
                "songTimelineStableDirectMeanError",
                "songTimelineStableDirectMaxError",
                "songTimelineStableBestOffsetFrames",
                "prerollSongTimelineDirectUsability",
                "prerollSongTimelineStableDirectMeanError",
                "prerollSongTimelineStableDirectMaxError",
                "mp3QuantizedDirectUsability",
                "mp3QuantizedStableDirectMeanError",
                "mp3QuantizedStableDirectMaxError",
                "mp3AdaptiveDirectUsability",
                "mp3AdaptiveStableDirectMeanError",
                "mp3AdaptiveStableDirectMaxError",
                "mp3AdaptiveStableBestOffsetFrames",
                "aacTailDirectUsability",
                "aacTailStableDirectMeanError",
                "aacTailStableDirectMaxError",
                "errorMessage",
            ).joinToString(",") { it.toCsvCell() }
        }

        fun success(
            relativePath: String,
            file: File,
            elapsedMs: Long,
            reportFile: File,
            sourceSampleRate: Int,
            channels: Int,
            frames: Int,
            fullDecodeMs: Long,
            probeCount: Int,
            songTimelineSummary: AudioWindowDecodeCandidateFamilySummary,
            prerollSongTimelineSummary: AudioWindowDecodeCandidateFamilySummary,
            mp3QuantizedSummary: AudioWindowDecodeCandidateFamilySummary,
            mp3AdaptiveSummary: AudioWindowDecodeCandidateFamilySummary,
            aacTailSummary: AudioWindowDecodeCandidateFamilySummary,
        ): BatchRow {
            return BatchRow(
                relativePath = relativePath,
                status = "completed",
                extension = file.extension.lowercase(Locale.US),
                fileBytes = file.length(),
                elapsedMs = elapsedMs,
                sourceSampleRate = sourceSampleRate,
                channels = channels,
                frames = frames,
                fullDecodeMs = fullDecodeMs,
                probeCount = probeCount,
                reportPath = reportFile.absolutePath,
                songTimelineDirectUsability = songTimelineSummary.directUsability.takeIf {
                    songTimelineSummary.available
                },
                songTimelineStableDirectMeanError = songTimelineSummary.worstStableDirect?.meanAbsoluteError,
                songTimelineStableDirectMaxError = songTimelineSummary.worstStableDirect?.maxAbsoluteError,
                songTimelineStableBestOffsetFrames =
                    songTimelineSummary.stableBestOffsetFrames.joinToString(" "),
                prerollSongTimelineDirectUsability = prerollSongTimelineSummary.directUsability.takeIf {
                    prerollSongTimelineSummary.available
                },
                prerollSongTimelineStableDirectMeanError =
                    prerollSongTimelineSummary.worstStableDirect?.meanAbsoluteError,
                prerollSongTimelineStableDirectMaxError =
                    prerollSongTimelineSummary.worstStableDirect?.maxAbsoluteError,
                mp3QuantizedDirectUsability = mp3QuantizedSummary.directUsability.takeIf {
                    mp3QuantizedSummary.available
                },
                mp3QuantizedStableDirectMeanError =
                    mp3QuantizedSummary.worstStableDirect?.meanAbsoluteError,
                mp3QuantizedStableDirectMaxError =
                    mp3QuantizedSummary.worstStableDirect?.maxAbsoluteError,
                mp3AdaptiveDirectUsability = mp3AdaptiveSummary.directUsability.takeIf {
                    mp3AdaptiveSummary.available
                },
                mp3AdaptiveStableDirectMeanError =
                    mp3AdaptiveSummary.worstStableDirect?.meanAbsoluteError,
                mp3AdaptiveStableDirectMaxError =
                    mp3AdaptiveSummary.worstStableDirect?.maxAbsoluteError,
                mp3AdaptiveStableBestOffsetFrames =
                    mp3AdaptiveSummary.stableBestOffsetFrames.joinToString(" "),
                aacTailDirectUsability = aacTailSummary.directUsability.takeIf {
                    aacTailSummary.available
                },
                aacTailStableDirectMeanError = aacTailSummary.worstStableDirect?.meanAbsoluteError,
                aacTailStableDirectMaxError = aacTailSummary.worstStableDirect?.maxAbsoluteError,
                errorMessage = null,
            )
        }

        fun failure(
            relativePath: String,
            file: File,
            elapsedMs: Long,
            error: Throwable,
        ): BatchRow {
            return BatchRow(
                relativePath = relativePath,
                status = "failed",
                extension = file.extension.lowercase(Locale.US),
                fileBytes = file.length(),
                elapsedMs = elapsedMs,
                sourceSampleRate = null,
                channels = null,
                frames = null,
                fullDecodeMs = null,
                probeCount = null,
                reportPath = null,
                songTimelineDirectUsability = null,
                songTimelineStableDirectMeanError = null,
                songTimelineStableDirectMaxError = null,
                songTimelineStableBestOffsetFrames = null,
                prerollSongTimelineDirectUsability = null,
                prerollSongTimelineStableDirectMeanError = null,
                prerollSongTimelineStableDirectMaxError = null,
                mp3QuantizedDirectUsability = null,
                mp3QuantizedStableDirectMeanError = null,
                mp3QuantizedStableDirectMaxError = null,
                mp3AdaptiveDirectUsability = null,
                mp3AdaptiveStableDirectMeanError = null,
                mp3AdaptiveStableDirectMaxError = null,
                mp3AdaptiveStableBestOffsetFrames = null,
                aacTailDirectUsability = null,
                aacTailStableDirectMeanError = null,
                aacTailStableDirectMaxError = null,
                errorMessage = "${error::class.java.simpleName}: ${error.message.orEmpty()}",
            )
        }
    }
}

private fun Double.format3(): String {
    return String.format(Locale.US, "%.3f", this)
}

private fun Any?.toCsvCell(): String {
    val text = this?.toString().orEmpty()
    return "\"" + text.replace("\"", "\"\"") + "\""
}
