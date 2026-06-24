package com.mardous.booming.debug

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
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
import java.io.File

class SourceSeparationDebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        val command = intent.getStringExtra(EXTRA_COMMAND).orEmpty()
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
                    pendingResult.finish()
                    return@addListener
                }
                val resultFuture = runCatching {
                    handleCommand(appContext, controller, command, intent)
                }.onFailure { error ->
                    writeStatus(appContext, command, "error=${error::class.java.simpleName}:${error.message}")
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
            COMMAND_STATUS -> {
                writeStatus(context, command, "status", controller)
                null
            }
            else -> {
                writeStatus(context, command, "unknownCommand", controller)
                null
            }
        }
    }

    private fun writeStatus(
        context: Context,
        command: String,
        note: String,
        controller: MediaController? = null,
    ) {
        val debugDir = File(
            File(
                context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir,
                "source-separation/debug",
            ),
            "adb-bridge",
        ).apply { mkdirs() }
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
        }
        File(debugDir, "status.log").appendText(line + "\n", Charsets.UTF_8)
        File(debugDir, "latest-status.txt").writeText(line + "\n", Charsets.UTF_8)
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

    companion object {
        private const val EXTRA_COMMAND = "command"
        private const val EXTRA_POSITION_MS = "positionMs"
        private const val EXTRA_PERCENT = "percent"
        private const val EXTRA_MARKER = "marker"
        private const val EXTRA_ENABLED = "enabled"
        private const val EXTRA_EXPECT_PROCESSING = "expectProcessing"
        private const val EXTRA_BLEND = "blend"

        private const val COMMAND_PLAY = "play"
        private const val COMMAND_PAUSE = "pause"
        private const val COMMAND_NEXT = "next"
        private const val COMMAND_PREVIOUS = "previous"
        private const val COMMAND_SEEK = "seek"
        private const val COMMAND_SEEK_PERCENT = "seekPercent"
        private const val COMMAND_MARKER = "marker"
        private const val COMMAND_SET_SEPARATED_PLAYBACK = "setSeparatedPlayback"
        private const val COMMAND_SET_BLEND = "setBlend"
        private const val COMMAND_SYNC = "sync"
        private const val COMMAND_STATUS = "status"
    }
}
