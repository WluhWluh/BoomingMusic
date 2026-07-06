package com.mardous.booming.debug

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.mardous.booming.playback.Playback
import com.mardous.booming.playback.PlaybackService
import com.mardous.booming.separation.SourceSeparationEngine
import com.mardous.booming.separation.audio.AudioWindowDecodeExperiment
import com.mardous.booming.ui.screen.player.SourceSeparationForegroundWorkerDebugBridge
import org.koin.java.KoinJavaComponent.get
import java.io.File
import java.util.Locale

class SourceSeparationDebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        val command = intent.getStringExtra(EXTRA_COMMAND).orEmpty()
        writeStatus(appContext, command, "received")
        Log.i(TAG, "Received source separation debug command: $command")
        if (handleBridgeOnlyCommand(appContext, command, intent)) {
            pendingResult.finish()
            return
        }
        val token = SessionToken(
            appContext,
            ComponentName(appContext, PlaybackService::class.java),
        )
        val controllerFuture = MediaController.Builder(appContext, token).buildAsync()
        controllerFuture.addListener(
            {
                val controller = runCatching { controllerFuture.get() }.getOrNull()
                if (controller == null) {
                    writeStatus(appContext, command, "controller=null")
                    Log.w(TAG, "MediaController was null for command: $command")
                    pendingResult.finish()
                    return@addListener
                }
                val resultFuture = runCatching {
                    handleCommand(appContext, controller, command, intent)
                }.onFailure { error ->
                    Log.w(TAG, "Debug command failed: $command", error)
                    writeStatus(
                        appContext,
                        command,
                        "error=${error::class.java.simpleName}:${error.message}",
                        controller,
                    )
                }.getOrNull()
                if (resultFuture == null) {
                    writeStatus(appContext, command, "done", controller)
                    controller.release()
                    pendingResult.finish()
                } else {
                    resultFuture.addListener(
                        {
                            val result = runCatching { resultFuture.get() }.getOrNull()
                            writeStatus(
                                appContext,
                                command,
                                "result=${result?.resultCode}",
                                controller,
                            )
                            controller.release()
                            pendingResult.finish()
                        },
                        ContextCompat.getMainExecutor(appContext),
                    )
                }
            },
            ContextCompat.getMainExecutor(appContext),
        )
    }

    private fun handleBridgeOnlyCommand(
        context: Context,
        command: String,
        intent: Intent,
    ): Boolean {
        return when (command) {
            COMMAND_CONFIGURE_WORKER -> {
                val configured = SourceSeparationForegroundWorkerDebugBridge.configure(
                    autoStart = intent.optionalBooleanExtra(EXTRA_AUTO_START),
                    modeName = intent.getStringExtra(EXTRA_MODE),
                    blend = intent.optionalFloatExtra(EXTRA_BLEND),
                )
                writeStatus(
                    context,
                    command,
                    "configured=$configured autoStart=${intent.optionalBooleanExtra(EXTRA_AUTO_START)} " +
                            "mode=${intent.getStringExtra(EXTRA_MODE)} " +
                            "blend=${intent.optionalFloatExtra(EXTRA_BLEND)}",
                )
                true
            }
            COMMAND_START_WORKER -> {
                val started = SourceSeparationForegroundWorkerDebugBridge.startCurrentSong()
                writeStatus(context, command, "started=$started")
                true
            }
            COMMAND_PAUSE_WORKER -> {
                val paused = SourceSeparationForegroundWorkerDebugBridge.pause()
                writeStatus(context, command, "paused=$paused")
                true
            }
            COMMAND_WORKER_STATUS -> {
                writeStatus(
                    context,
                    command,
                    SourceSeparationForegroundWorkerDebugBridge.status(),
                )
                true
            }
            COMMAND_WINDOW_SAMPLES -> {
                writeStatus(
                    context,
                    command,
                    SourceSeparationForegroundWorkerDebugBridge.windowSamples(),
                )
                true
            }
            COMMAND_CLEAR_WINDOW_SAMPLES -> {
                val cleared = SourceSeparationForegroundWorkerDebugBridge.clearWindowSamples()
                writeStatus(context, command, "cleared=$cleared")
                true
            }
            COMMAND_CLEAR_DEBUG_LOG -> {
                clearDebugLog(context)
                writeStatus(context, command, "cleared")
                true
            }
            else -> false
        }
    }

    private fun handleCommand(
        context: Context,
        controller: MediaController,
        command: String,
        intent: Intent,
    ): ListenableFuture<SessionResult>? {
        return when (command) {
            COMMAND_PLAY -> {
                controller.play()
                null
            }
            COMMAND_PAUSE -> {
                controller.pause()
                null
            }
            COMMAND_NEXT -> {
                controller.seekToNext()
                null
            }
            COMMAND_PREVIOUS -> {
                controller.seekToPrevious()
                null
            }
            COMMAND_SET_REPEAT_MODE -> {
                controller.repeatMode = when (
                    intent.getStringExtra(EXTRA_REPEAT_MODE).orEmpty().lowercase()
                ) {
                    "one" -> Player.REPEAT_MODE_ONE
                    "all" -> Player.REPEAT_MODE_ALL
                    "off" -> Player.REPEAT_MODE_OFF
                    else -> intent.getIntExtra(EXTRA_REPEAT_MODE_INT, Player.REPEAT_MODE_ALL)
                }
                null
            }
            COMMAND_SEEK -> {
                controller.seekTo(intent.getLongExtra(EXTRA_POSITION_MS, 0L).coerceAtLeast(0L))
                null
            }
            COMMAND_SEEK_PERCENT -> {
                val duration = controller.duration.takeIf { it != C.TIME_UNSET } ?: 0L
                val percent = intent.getIntExtra(EXTRA_PERCENT, 0).coerceIn(0, 100)
                controller.seekTo(duration * percent / 100)
                null
            }
            COMMAND_SEEK_TO_INDEX -> {
                val index = intent.getIntExtra(EXTRA_INDEX, 0)
                    .coerceIn(0, (controller.mediaItemCount - 1).coerceAtLeast(0))
                val positionMs = intent.getLongExtra(EXTRA_POSITION_MS, 0L).coerceAtLeast(0L)
                controller.seekTo(index, positionMs)
                null
            }
            COMMAND_MARKER -> {
                controller.sendCustomCommand(
                    SessionCommand(Playback.TRACE_SOURCE_SEPARATION_PLAYBACK_MARKER, Bundle.EMPTY),
                    Bundle().apply {
                        putString(
                            Playback.EXTRA_SOURCE_SEPARATION_TRACE_MARKER,
                            intent.getStringExtra(EXTRA_MARKER).orEmpty(),
                        )
                    },
                )
            }
            COMMAND_SET_SEPARATED_PLAYBACK -> {
                controller.sendCustomCommand(
                    SessionCommand(Playback.SET_SOURCE_SEPARATION_PLAYBACK_ENABLED, Bundle.EMPTY),
                    Bundle().apply {
                        putBoolean(
                            Playback.EXTRA_SOURCE_SEPARATION_ENABLED,
                            intent.getBooleanExtra(EXTRA_ENABLED, true),
                        )
                        putBoolean(Playback.EXTRA_SOURCE_SEPARATION_SHOW_MESSAGE, false)
                        putBoolean(Playback.EXTRA_SOURCE_SEPARATION_AUTO_SYNC_ON_TRANSITION, true)
                        putBoolean(
                            Playback.EXTRA_SOURCE_SEPARATION_EXPECT_PROCESSING,
                            intent.getBooleanExtra(EXTRA_EXPECT_PROCESSING, true),
                        )
                        if (intent.hasExtra(EXTRA_BLEND)) {
                            putFloat(
                                Playback.EXTRA_SOURCE_SEPARATION_BLEND,
                                intent.getFloatExtra(EXTRA_BLEND, 0.5f),
                            )
                        }
                    },
                )
            }
            COMMAND_SET_BLEND -> {
                controller.sendCustomCommand(
                    SessionCommand(Playback.SET_SOURCE_SEPARATION_BLEND, Bundle.EMPTY),
                    Bundle().apply {
                        putFloat(
                            Playback.EXTRA_SOURCE_SEPARATION_BLEND,
                            intent.getFloatExtra(EXTRA_BLEND, 0.5f),
                        )
                    },
                )
            }
            COMMAND_SYNC -> {
                controller.sendCustomCommand(
                    SessionCommand(Playback.SYNC_SOURCE_SEPARATION_PLAYBACK, Bundle.EMPTY),
                    Bundle().apply {
                        putBoolean(Playback.EXTRA_SOURCE_SEPARATION_ALLOW_NEW_SESSION, true)
                        putBoolean(
                            Playback.EXTRA_SOURCE_SEPARATION_EXPECT_PROCESSING,
                            intent.getBooleanExtra(EXTRA_EXPECT_PROCESSING, true),
                        )
                    },
                )
            }
            COMMAND_CLEAR_CACHES -> {
                clearSourceSeparationCaches(context)
                null
            }
            COMMAND_STATUS -> {
                writeStatus(context, command, "status", controller)
                null
            }
            COMMAND_RUN_CURRENT_WINDOW_DECODE_EXPERIMENT -> {
                val report = runCurrentWindowDecodeExperiment(context, intent, command, controller)
                writeStatus(context, command, report, controller)
                null
            }
            COMMAND_RUN_CURRENT_WINDOW_REPEAT_DECODE_EXPERIMENT -> {
                val report = runCurrentWindowRepeatDecodeExperiment(context, intent, command, controller)
                writeStatus(context, command, report, controller)
                null
            }
            COMMAND_RUN_CURRENT_MP3_OVERLAP_SWEEP -> {
                val report = runCurrentMp3OverlapSweep(context, intent, command, controller)
                writeStatus(context, command, report, controller)
                null
            }
            else -> {
                writeStatus(context, command, "unknownCommand", controller)
                null
            }
        }
    }

    private fun runCurrentWindowDecodeExperiment(
        context: Context,
        intent: Intent,
        command: String,
        controller: MediaController?,
    ): String {
        val currentItem = controller?.currentMediaItem
            ?: error("No current media item is available.")
        val uri = currentItem.localConfiguration?.uri
            ?: error("Current media item has no URI.")
        val displayName = currentItem.mediaMetadata.title?.toString()
            ?.takeIf { it.isNotBlank() }
            ?: currentItem.mediaId.ifBlank { uri.lastPathSegment ?: "current-song" }
        val playbackPositionMs = controller.currentPosition.takeIf { it >= 0L } ?: 0L
        val outputTag = intent.getStringExtra(EXTRA_OUTPUT_TAG)
            ?.sanitizePathSegment()
            ?.ifBlank { null }
            ?: "current-${System.currentTimeMillis()}"
        val reportRoot = File(
            File(
                context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir,
                "source-separation/debug/current-window-decode",
            ),
            outputTag,
        )
        reportRoot.mkdirs()

        writeStatus(
            context,
            command,
            "running uri=$uri positionMs=$playbackPositionMs title=${displayName.sanitizeForStatus()}",
            controller,
        )
        val startedAtMs = SystemClock.elapsedRealtime()
        val result = AudioWindowDecodeExperiment(context).run(
            uri = uri,
            displayName = displayName,
            playbackPositionMs = playbackPositionMs,
            reportDir = reportRoot,
            onProgress = { progress ->
                Log.i(
                    TAG,
                    "Window decode experiment ${progress.completedSteps}/${progress.totalSteps}: " +
                            progress.stage,
                )
            },
        )
        val elapsedMs = SystemClock.elapsedRealtime() - startedAtMs
        return buildString {
            append("report=").append(result.reportFile.absolutePath)
            append(" elapsedMs=").append(elapsedMs)
            append(" fullDecodeMs=").append(result.fullDecodeMs)
            append(" totalWindowDecodeMs=").append(result.totalLocalDecodeMs)
            append(" worstSongTimelineOffset=").append(result.worstSongTimelineSummary.offsetFrames)
            append(" worstSongTimelineMeanAbs=")
            append(String.format(Locale.US, "%.3f", result.worstSongTimelineSummary.meanAbsoluteError))
        }
    }

    private fun runCurrentWindowRepeatDecodeExperiment(
        context: Context,
        intent: Intent,
        command: String,
        controller: MediaController?,
    ): String {
        val currentItem = controller?.currentMediaItem
            ?: error("No current media item is available.")
        val uri = currentItem.localConfiguration?.uri
            ?: error("Current media item has no URI.")
        val displayName = currentItem.mediaMetadata.title?.toString()
            ?.takeIf { it.isNotBlank() }
            ?: currentItem.mediaId.ifBlank { uri.lastPathSegment ?: "current-song" }
        val playbackPositionMs = if (intent.hasExtra(EXTRA_POSITION_MS)) {
            intent.getLongExtra(EXTRA_POSITION_MS, 0L).coerceAtLeast(0L)
        } else {
            controller.currentPosition.takeIf { it >= 0L } ?: 0L
        }
        val repeatCount = intent.getIntExtra(EXTRA_REPEAT_COUNT, 6).coerceIn(1, 20)
        val outputTag = intent.getStringExtra(EXTRA_OUTPUT_TAG)
            ?.sanitizePathSegment()
            ?.ifBlank { null }
            ?: "current-repeat-${System.currentTimeMillis()}"
        val reportRoot = File(
            File(
                context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir,
                "source-separation/debug/current-window-repeat",
            ),
            outputTag,
        )
        reportRoot.mkdirs()

        writeStatus(
            context,
            command,
            "running uri=$uri positionMs=$playbackPositionMs repeatCount=$repeatCount " +
                    "title=${displayName.sanitizeForStatus()}",
            controller,
        )
        val startedAtMs = SystemClock.elapsedRealtime()
        val result = WindowDecodeRepeatExperiment(context).run(
            uri = uri,
            displayName = displayName,
            playbackPositionMs = playbackPositionMs,
            repeatCount = repeatCount,
            reportDir = reportRoot,
            onProgress = { stage ->
                Log.i(TAG, "Window repeat decode experiment: $stage")
            },
        )
        val elapsedMs = SystemClock.elapsedRealtime() - startedAtMs
        return buildString {
            append("report=").append(result.reportFile.absolutePath)
            append(" elapsedMs=").append(elapsedMs)
            append(" repeatCount=").append(repeatCount)
            append(" localUniqueHashes=").append(result.localRuns.map { it.pcmSha256 }.distinct().size)
            append(" prerollUniqueHashes=").append(result.prerollRuns.map { it.pcmSha256 }.distinct().size)
        }
    }

    private fun runCurrentMp3OverlapSweep(
        context: Context,
        intent: Intent,
        command: String,
        controller: MediaController?,
    ): String {
        val currentItem = controller?.currentMediaItem
            ?: error("No current media item is available.")
        val uri = currentItem.localConfiguration?.uri
            ?: error("Current media item has no URI.")
        val displayName = currentItem.mediaMetadata.title?.toString()
            ?.takeIf { it.isNotBlank() }
            ?: currentItem.mediaId.ifBlank { uri.lastPathSegment ?: "current-song" }
        val outputTag = intent.getStringExtra(EXTRA_OUTPUT_TAG)
            ?.sanitizePathSegment()
            ?.ifBlank { null }
            ?: "current-mp3-overlap-${System.currentTimeMillis()}"
        val reportRoot = File(
            File(
                context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir,
                "source-separation/debug/current-mp3-overlap",
            ),
            outputTag,
        )
        reportRoot.mkdirs()

        writeStatus(
            context,
            command,
            "running uri=$uri title=${displayName.sanitizeForStatus()}",
            controller,
        )
        val startedAtMs = SystemClock.elapsedRealtime()
        val result = Mp3OverlapSweepExperiment(context).run(
            uri = uri,
            displayName = displayName,
            reportDir = reportRoot,
            onProgress = { stage ->
                Log.i(TAG, "MP3 overlap sweep: $stage")
            },
        )
        val elapsedMs = SystemClock.elapsedRealtime() - startedAtMs
        val large = result.comparisons.filter { it.valid && it.largeOffset && !it.smallOffset }
        val minRelative = large.mapNotNull { it.relativeError }.minOrNull()
        return buildString {
            append("report=").append(result.reportFile.absolutePath)
            append(" elapsedMs=").append(elapsedMs)
            append(" segments=").append(result.segmentCount)
            append(" comparisons=").append(result.comparisons.size)
            append(" large=").append(large.size)
            append(" minLargeRelative=")
            append(if (minRelative != null) String.format(Locale.US, "%.6f", minRelative) else "")
        }
    }

    private fun clearSourceSeparationCaches(context: Context) {
        val engine = get<SourceSeparationEngine>(SourceSeparationEngine::class.java)
        val entries = runCatching { engine.listCacheEntries() }.getOrDefault(emptyList())
        var deletedCount = 0
        entries.forEach { entry ->
            if (runCatching { engine.deleteCacheEntry(entry.id) }.getOrDefault(false)) {
                deletedCount++
            }
        }
        writeStatus(
            context,
            COMMAND_CLEAR_CACHES,
            "deleted=$deletedCount total=${entries.size}",
        )
    }

    private fun clearDebugLog(context: Context) {
        val debugDir = debugDirectory(context)
        File(debugDir, "status.log").delete()
        File(debugDir, "latest-status.txt").delete()
    }

    private fun writeStatus(
        context: Context,
        command: String,
        note: String,
        controller: MediaController? = null,
    ) {
        val debugDir = debugDirectory(context)
        val line = buildString {
            append(SystemClock.elapsedRealtime())
            append(" command=").append(command)
            append(" note=").append(note)
            if (controller != null) {
                append(" state=").append(controller.playbackState.debugName())
                append(" playWhenReady=").append(controller.playWhenReady)
                append(" isPlaying=").append(controller.isPlaying)
                append(" position=").append(controller.currentPosition)
                append(" duration=").append(controller.duration)
                append(" index=").append(controller.currentMediaItemIndex)
                append(" mediaId=").append(controller.currentMediaItem?.mediaId)
                append(" title=").append(controller.mediaMetadata.title)
            }
            append(" worker={")
            append(SourceSeparationForegroundWorkerDebugBridge.status())
            append("}")
        }
        File(debugDir, "status.log").appendText(line + "\n", Charsets.UTF_8)
        File(debugDir, "latest-status.txt").writeText(line + "\n", Charsets.UTF_8)
    }

    private fun debugDirectory(context: Context): File {
        return File(
            File(
                context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir,
                "source-separation/debug",
            ),
            "adb-bridge",
        ).apply { mkdirs() }
    }

    private fun Int.debugName(): String {
        return when (this) {
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> "READY"
            Player.STATE_ENDED -> "ENDED"
            else -> toString()
        }
    }

    private fun Intent.optionalBooleanExtra(name: String): Boolean? {
        return if (hasExtra(name)) getBooleanExtra(name, false) else null
    }

    private fun Intent.optionalFloatExtra(name: String): Float? {
        return if (hasExtra(name)) getFloatExtra(name, 0f) else null
    }

    private fun String.sanitizeForStatus(): String {
        return replace('\n', ' ')
            .replace('\r', ' ')
            .take(120)
    }

    private fun String.sanitizePathSegment(): String {
        return replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_')
    }

    companion object {
        private const val EXTRA_COMMAND = "command"
        private const val EXTRA_POSITION_MS = "positionMs"
        private const val EXTRA_PERCENT = "percent"
        private const val EXTRA_MARKER = "marker"
        private const val EXTRA_ENABLED = "enabled"
        private const val EXTRA_EXPECT_PROCESSING = "expectProcessing"
        private const val EXTRA_BLEND = "blend"
        private const val EXTRA_AUTO_START = "autoStart"
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_INDEX = "index"
        private const val EXTRA_REPEAT_MODE = "repeatMode"
        private const val EXTRA_REPEAT_MODE_INT = "repeatModeInt"
        private const val EXTRA_OUTPUT_TAG = "output_tag"
        private const val EXTRA_REPEAT_COUNT = "repeatCount"

        private const val COMMAND_PLAY = "play"
        private const val COMMAND_PAUSE = "pause"
        private const val COMMAND_NEXT = "next"
        private const val COMMAND_PREVIOUS = "previous"
        private const val COMMAND_SET_REPEAT_MODE = "setRepeatMode"
        private const val COMMAND_SEEK = "seek"
        private const val COMMAND_SEEK_PERCENT = "seekPercent"
        private const val COMMAND_SEEK_TO_INDEX = "seekToIndex"
        private const val COMMAND_MARKER = "marker"
        private const val COMMAND_SET_SEPARATED_PLAYBACK = "setSeparatedPlayback"
        private const val COMMAND_SET_BLEND = "setBlend"
        private const val COMMAND_SYNC = "sync"
        private const val COMMAND_CLEAR_CACHES = "clearCaches"
        private const val COMMAND_CONFIGURE_WORKER = "configureWorker"
        private const val COMMAND_START_WORKER = "startWorker"
        private const val COMMAND_PAUSE_WORKER = "pauseWorker"
        private const val COMMAND_WORKER_STATUS = "workerStatus"
        private const val COMMAND_WINDOW_SAMPLES = "windowSamples"
        private const val COMMAND_CLEAR_WINDOW_SAMPLES = "clearWindowSamples"
        private const val COMMAND_CLEAR_DEBUG_LOG = "clearDebugLog"
        private const val COMMAND_RUN_CURRENT_WINDOW_DECODE_EXPERIMENT = "runCurrentWindowDecodeExperiment"
        private const val COMMAND_RUN_CURRENT_WINDOW_REPEAT_DECODE_EXPERIMENT =
            "runCurrentWindowRepeatDecodeExperiment"
        private const val COMMAND_RUN_CURRENT_MP3_OVERLAP_SWEEP = "runCurrentMp3OverlapSweep"
        private const val COMMAND_STATUS = "status"

        private const val TAG = "SrcSepDebugReceiver"
    }
}
