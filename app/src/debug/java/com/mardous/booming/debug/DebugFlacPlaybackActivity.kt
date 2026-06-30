package com.mardous.booming.debug

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.SilenceMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import java.io.IOException
import java.io.File
import java.util.Locale

@OptIn(UnstableApi::class)
class DebugFlacPlaybackActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var reportRoot: File
    private lateinit var summaryFile: File
    private lateinit var logFile: File
    private lateinit var cases: List<FlacPlaybackCase>
    private var caseIndex = 0
    private var player: ExoPlayer? = null
    private var activeCase: ActivePlaybackCase? = null
    private var preferExtensionRenderer = false
    private var useSilenceSource = false
    private var silenceDurationMs = DEFAULT_SILENCE_DURATION_MS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val outputTag = intent.getStringExtra(EXTRA_OUTPUT_TAG)
            ?.sanitizePathSegment()
            ?.ifBlank { null }
            ?: "flac-playback-${System.currentTimeMillis()}"
        val inputDirPath = intent.getStringExtra(EXTRA_INPUT_DIR)
        val inputFilePath = intent.getStringExtra(EXTRA_INPUT_FILE)
        val maxFiles = intent.getIntExtra(EXTRA_MAX_FILES, DEFAULT_MAX_FILES).coerceAtLeast(0)
        val playMs = intent.getLongExtra(EXTRA_PLAY_MS, DEFAULT_PLAY_MS).coerceAtLeast(500L)
        val seekSweep = intent.getBooleanExtra(EXTRA_SEEK_SWEEP, false)
        val timeoutMs = intent.getLongExtra(
            EXTRA_TIMEOUT_MS,
            if (seekSweep) DEFAULT_SEEK_SWEEP_TIMEOUT_MS else DEFAULT_TIMEOUT_MS,
            )
            .coerceAtLeast(playMs + 1_000L)
        preferExtensionRenderer = intent.getBooleanExtra(EXTRA_PREFER_EXTENSION, false)
        useSilenceSource = intent.getBooleanExtra(EXTRA_USE_SILENCE_SOURCE, false)
        silenceDurationMs = intent.getLongExtra(
            EXTRA_SILENCE_DURATION_MS,
            DEFAULT_SILENCE_DURATION_MS,
        ).coerceAtLeast(playMs + 1_000L)

        reportRoot = resolveWritableReportRoot(outputTag)
        summaryFile = File(reportRoot, "flac-playback-summary.csv")
        logFile = File(reportRoot, "flac-playback-log.txt")
        summaryFile.writeText(FlacPlaybackRow.csvHeader() + "\n", Charsets.UTF_8)
        logFile.writeText(
            "FLAC playback debug test\n" +
                    "Output: ${reportRoot.absolutePath}\n" +
                    "Input file: ${inputFilePath.orEmpty()}\n" +
                    "Input dir: ${inputDirPath.orEmpty()}\n" +
                    "Max files: $maxFiles\n" +
                    "Play ms: $playMs\n" +
                    "Timeout ms: $timeoutMs\n" +
                    "Seek sweep: $seekSweep\n" +
                    "Prefer extension renderer: $preferExtensionRenderer\n\n",
            Charsets.UTF_8,
        )

        cases = if (useSilenceSource) {
            listOf(
                FlacPlaybackCase(
                    label = "silence-${silenceDurationMs}ms",
                    file = File("silence"),
                )
            )
        } else {
            findFlacPlaybackCases(
                inputFilePath = inputFilePath,
                inputDirPath = inputDirPath,
                maxFiles = maxFiles,
            )
        }
        if (useSilenceSource) {
            logFile.appendText(
                "Silence source duration ms: $silenceDurationMs\n",
                Charsets.UTF_8,
            )
        }
        logFile.appendText("Cases: ${cases.size}\n\n", Charsets.UTF_8)
        Log.i(TAG, "Starting FLAC playback debug test with ${cases.size} case(s).")
        if (cases.isEmpty()) {
            finish()
            return
        }
        runNextCase(playMs = playMs, timeoutMs = timeoutMs, seekSweep = seekSweep)
    }

    override fun onDestroy() {
        player?.release()
        player = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun runNextCase(
        playMs: Long,
        timeoutMs: Long,
        seekSweep: Boolean,
    ) {
        if (caseIndex >= cases.size) {
            logFile.appendText("\nFinished.\n", Charsets.UTF_8)
            Log.i(TAG, "FLAC playback debug test finished: ${reportRoot.absolutePath}")
            finish()
            return
        }

        val case = cases[caseIndex]
        val index = caseIndex + 1
        caseIndex += 1
        Log.i(TAG, "($index/${cases.size}) Testing ${case.label}")
        logFile.appendText("START $index/${cases.size}: ${case.label}\n", Charsets.UTF_8)

        val state = ActivePlaybackCase(
            case = case,
            index = index,
            startedAtMs = SystemClock.elapsedRealtime(),
            playMs = playMs,
            timeoutMs = timeoutMs,
            seekSweep = seekSweep,
        )
        activeCase = state

        val currentPlayer = buildPlayer()
        player = currentPlayer
        currentPlayer.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                logFile.appendText(
                    "DECODER ${state.index}/${cases.size}: ${state.case.label} " +
                            "$decoderName initMs=$initializationDurationMs\n",
                    Charsets.UTF_8,
                )
            }
        })
        currentPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                state.events += playbackState.toStateName()
                logFile.appendText(
                    "STATE ${state.index}/${cases.size}: ${state.case.label} " +
                            "${playbackState.toStateName()} pos=${currentPlayer.currentPosition}\n",
                    Charsets.UTF_8,
                )
                if (playbackState == Player.STATE_READY && !state.readySeen) {
                    state.readySeen = true
                    state.readyAtMs = SystemClock.elapsedRealtime()
                    state.positionAtReadyMs = currentPlayer.currentPosition
                    if (state.seekSweep) {
                        state.seekTargetsMs = seekTargetsForDuration(currentPlayer.duration)
                        logFile.appendText(
                            "SEEKS ${state.index}/${cases.size}: ${state.seekTargetsMs.joinToString("|")}\n",
                            Charsets.UTF_8,
                        )
                        handler.postDelayed({ runNextSeekIfActive(state) }, INITIAL_SEEK_DELAY_MS)
                    } else {
                        handler.postDelayed({ finishCaseIfActive(state, "ready-play-window") }, playMs)
                    }
                } else if (playbackState == Player.STATE_ENDED) {
                    finishCaseIfActive(state, "ended")
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                state.errorCodeName = error.errorCodeName
                state.errorMessage = error.message ?: error.cause?.message
                finishCaseIfActive(state, "player-error")
            }
        })

        if (useSilenceSource) {
            currentPlayer.setMediaSource(SilenceMediaSource(silenceDurationMs * 1_000L))
        } else {
            currentPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(case.file)))
        }
        currentPlayer.prepare()
        currentPlayer.playWhenReady = true
        handler.postDelayed({ finishCaseIfActive(state, "timeout") }, timeoutMs)
    }

    private fun runNextSeekIfActive(state: ActivePlaybackCase) {
        if (activeCase !== state) return
        val currentPlayer = player ?: run {
            finishCaseIfActive(state, "player-missing")
            return
        }
        if (state.seekIndex >= state.seekTargetsMs.size) {
            finishCaseIfActive(state, "seek-sweep-done")
            return
        }

        val targetMs = state.seekTargetsMs[state.seekIndex]
        state.seekIndex += 1
        val pendingSeek = PendingSeek(
            index = state.seekIndex,
            targetMs = targetMs,
            beforeMs = currentPlayer.currentPosition,
            startedAtMs = SystemClock.elapsedRealtime(),
        )
        state.pendingSeek = pendingSeek
        logFile.appendText(
            "SEEK ${state.index}/${cases.size}.${pendingSeek.index}: " +
                    "target=$targetMs before=${pendingSeek.beforeMs}\n",
            Charsets.UTF_8,
        )
        currentPlayer.seekTo(targetMs)
        handler.postDelayed(
            { evaluateSeekIfActive(state, pendingSeek) },
            SEEK_VERIFY_DELAY_MS,
        )
    }

    private fun evaluateSeekIfActive(
        state: ActivePlaybackCase,
        pendingSeek: PendingSeek,
    ) {
        if (activeCase !== state || state.pendingSeek !== pendingSeek) return
        val currentPlayer = player ?: run {
            finishCaseIfActive(state, "player-missing")
            return
        }
        val positionMs = currentPlayer.currentPosition
        val playbackState = currentPlayer.playbackState
        val advancedMs = positionMs - pendingSeek.targetMs
        val success = playbackState == Player.STATE_READY &&
                currentPlayer.playWhenReady &&
                advancedMs >= MIN_SEEK_ADVANCE_MS
        val result = buildString {
            append(pendingSeek.index)
            append(":target=").append(pendingSeek.targetMs)
            append(":state=").append(playbackState.toStateName())
            append(":pos=").append(positionMs)
            append(":advanced=").append(advancedMs)
            append(":success=").append(success)
        }
        state.seekResults += result
        logFile.appendText(
            "SEEK_RESULT ${state.index}/${cases.size}.${pendingSeek.index}: $result\n",
            Charsets.UTF_8,
        )
        state.pendingSeek = null
        if (!success) {
            finishCaseIfActive(state, "seek-failed-${pendingSeek.index}")
        } else {
            handler.postDelayed({ runNextSeekIfActive(state) }, SEEK_GAP_MS)
        }
    }

    private fun buildPlayer(): ExoPlayer {
        return ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                false,
            )
            .setRenderersFactory(
                DefaultRenderersFactory(this)
                    .setExtensionRendererMode(
                        if (preferExtensionRenderer) {
                            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                        } else {
                            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                        }
                    )
                    .setEnableDecoderFallback(true)
            )
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    DefaultDataSource.Factory(this),
                    DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true),
                )
            )
            .build()
            .apply {
                volume = 0f
            }
    }

    private fun finishCaseIfActive(
        state: ActivePlaybackCase,
        finishReason: String,
    ) {
        if (activeCase !== state) return
        activeCase = null
        handler.removeCallbacksAndMessages(null)

        val currentPlayer = player
        val positionAtFinishMs = currentPlayer?.currentPosition ?: 0L
        val durationMs = currentPlayer?.duration?.takeIf { it != C.TIME_UNSET }
        val playbackState = currentPlayer?.playbackState ?: Player.STATE_IDLE
        currentPlayer?.release()
        player = null

        val readyLatencyMs = state.readyAtMs?.let { it - state.startedAtMs }
        val elapsedMs = SystemClock.elapsedRealtime() - state.startedAtMs
        val advancedMs = positionAtFinishMs - (state.positionAtReadyMs ?: 0L)
        val status = when {
            state.errorCodeName != null -> "failure"
            state.readySeen && advancedMs >= MIN_POSITION_ADVANCE_MS -> "success"
            state.readySeen -> "ready-no-progress"
            else -> "failure"
        }
        val row = FlacPlaybackRow(
            label = state.case.label,
            status = status,
            path = if (useSilenceSource) "<silence>" else state.case.file.absolutePath,
            bytes = if (useSilenceSource) 0L else state.case.file.length(),
            finishReason = finishReason,
            readySeen = state.readySeen,
            readyLatencyMs = readyLatencyMs,
            elapsedMs = elapsedMs,
            durationMs = durationMs,
            positionAtReadyMs = state.positionAtReadyMs,
            positionAtFinishMs = positionAtFinishMs,
            advancedMs = advancedMs,
            finalPlaybackState = playbackState.toStateName(),
            errorCodeName = state.errorCodeName,
            errorMessage = state.errorMessage,
            events = state.events.joinToString("|"),
            seekResults = state.seekResults.joinToString("|"),
        )
        summaryFile.appendText(row.toCsvLine() + "\n", Charsets.UTF_8)
        logFile.appendText(
            "END ${state.index}/${cases.size}: ${state.case.label} ${row.status} " +
                    "reason=$finishReason advanced=${row.advancedMs}ms " +
                    "error=${row.errorCodeName.orEmpty()} ${row.errorMessage.orEmpty()}\n",
            Charsets.UTF_8,
        )
        runNextCase(
            playMs = state.playMs,
            timeoutMs = state.timeoutMs,
            seekSweep = state.seekSweep,
        )
    }

    private fun seekTargetsForDuration(durationMs: Long): List<Long> {
        val validDuration = durationMs.takeIf { it != C.TIME_UNSET && it > 10_000L }
            ?: return emptyList()
        return listOf(25, 75, 40, 90, 10, 60)
            .map { percent -> validDuration * percent / 100 }
            .map { position -> position.coerceIn(1_000L, validDuration - 2_000L) }
            .distinct()
    }

    private fun findFlacPlaybackCases(
        inputFilePath: String?,
        inputDirPath: String?,
        maxFiles: Int,
    ): List<FlacPlaybackCase> {
        inputFilePath
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf { it.isFile }
            ?.let { return listOf(FlacPlaybackCase(label = it.name, file = it)) }

        if (maxFiles == 0) return emptyList()

        val roots = buildList {
            inputDirPath
                ?.takeIf { it.isNotBlank() }
                ?.let(::File)
                ?.let(::add)
            if (isEmpty()) {
                val musicRoot = getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: filesDir
                add(File(musicRoot, "source-separation/entries"))
                add(File(musicRoot, "source-separation/debug/flac-promotion"))
            }
        }.filter { it.isDirectory }

        return roots.asSequence()
            .flatMap { root ->
                root.walkTopDown()
                    .filter { file ->
                        file.isFile &&
                                file.extension.equals("flac", ignoreCase = true) &&
                                file.name.contains("instrumental", ignoreCase = true)
                    }
                    .map { file ->
                        FlacPlaybackCase(
                            label = file.relativeTo(root).invariantSeparatorsPath,
                            file = file,
                        )
                    }
            }
            .sortedBy { it.label }
            .take(maxFiles)
            .toList()
    }

    private fun resolveWritableReportRoot(outputTag: String): File {
        val candidates = listOf(
            File(
                File(
                    getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: filesDir,
                    "source-separation/debug/flac-playback",
                ),
                outputTag,
            ),
            File(
                File(filesDir, "source-separation/debug/flac-playback"),
                outputTag,
            ),
        )

        for (candidate in candidates) {
            try {
                if (!candidate.exists() && !candidate.mkdirs()) {
                    continue
                }
                val probe = File(candidate, ".write-probe")
                probe.writeText("ok", Charsets.UTF_8)
                probe.delete()
                return candidate
            } catch (_: IOException) {
                // Try the next candidate.
            } catch (_: SecurityException) {
                // Try the next candidate.
            }
        }

        return candidates.last().also { it.mkdirs() }
    }

    private fun Int.toStateName(): String {
        return when (this) {
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> "READY"
            Player.STATE_ENDED -> "ENDED"
            else -> "UNKNOWN_$this"
        }
    }

    private fun String.sanitizePathSegment(): String {
        return replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_')
    }

    private companion object {
        const val TAG = "DebugFlacPlayback"
        const val EXTRA_INPUT_DIR = "input_dir"
        const val EXTRA_INPUT_FILE = "input_file"
        const val EXTRA_OUTPUT_TAG = "output_tag"
        const val EXTRA_MAX_FILES = "max_files"
        const val EXTRA_PLAY_MS = "play_ms"
        const val EXTRA_TIMEOUT_MS = "timeout_ms"
        const val EXTRA_SEEK_SWEEP = "seek_sweep"
        const val EXTRA_PREFER_EXTENSION = "prefer_extension"
        const val EXTRA_USE_SILENCE_SOURCE = "use_silence_source"
        const val EXTRA_SILENCE_DURATION_MS = "silence_duration_ms"
        const val DEFAULT_MAX_FILES = 8
        const val DEFAULT_PLAY_MS = 3_000L
        const val DEFAULT_TIMEOUT_MS = 12_000L
        const val DEFAULT_SEEK_SWEEP_TIMEOUT_MS = 90_000L
        const val DEFAULT_SILENCE_DURATION_MS = 180_000L
        const val MIN_POSITION_ADVANCE_MS = 500L
        const val MIN_SEEK_ADVANCE_MS = 300L
        const val INITIAL_SEEK_DELAY_MS = 1_000L
        const val SEEK_VERIFY_DELAY_MS = 10_000L
        const val SEEK_GAP_MS = 500L
    }
}

private data class FlacPlaybackCase(
    val label: String,
    val file: File,
)

private data class ActivePlaybackCase(
    val case: FlacPlaybackCase,
    val index: Int,
    val startedAtMs: Long,
    val playMs: Long,
    val timeoutMs: Long,
    val seekSweep: Boolean,
    var readySeen: Boolean = false,
    var readyAtMs: Long? = null,
    var positionAtReadyMs: Long? = null,
    var errorCodeName: String? = null,
    var errorMessage: String? = null,
    var seekTargetsMs: List<Long> = emptyList(),
    var seekIndex: Int = 0,
    var pendingSeek: PendingSeek? = null,
    val events: MutableList<String> = mutableListOf(),
    val seekResults: MutableList<String> = mutableListOf(),
)

private data class PendingSeek(
    val index: Int,
    val targetMs: Long,
    val beforeMs: Long,
    val startedAtMs: Long,
)

private data class FlacPlaybackRow(
    val label: String,
    val status: String,
    val path: String,
    val bytes: Long,
    val finishReason: String,
    val readySeen: Boolean,
    val readyLatencyMs: Long?,
    val elapsedMs: Long,
    val durationMs: Long?,
    val positionAtReadyMs: Long?,
    val positionAtFinishMs: Long,
    val advancedMs: Long,
    val finalPlaybackState: String,
    val errorCodeName: String?,
    val errorMessage: String?,
    val events: String,
    val seekResults: String,
) {
    fun toCsvLine(): String {
        return listOf(
            label,
            status,
            path,
            bytes,
            finishReason,
            readySeen,
            readyLatencyMs,
            elapsedMs,
            durationMs,
            positionAtReadyMs,
            positionAtFinishMs,
            advancedMs,
            finalPlaybackState,
            errorCodeName,
            errorMessage,
            events,
            seekResults,
        ).joinToString(",") { it.toCsvCell() }
    }

    companion object {
        fun csvHeader(): String {
            return listOf(
                "label",
                "status",
                "path",
                "bytes",
                "finishReason",
                "readySeen",
                "readyLatencyMs",
                "elapsedMs",
                "durationMs",
                "positionAtReadyMs",
                "positionAtFinishMs",
                "advancedMs",
                "finalPlaybackState",
                "errorCodeName",
                "errorMessage",
                "events",
                "seekResults",
            ).joinToString(",")
        }
    }
}

private fun Any?.toCsvCell(): String {
    val text = when (this) {
        null -> ""
        is Double -> String.format(Locale.US, "%.6f", this)
        else -> toString()
    }
    return "\"" + text.replace("\"", "\"\"") + "\""
}
