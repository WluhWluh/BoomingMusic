package com.mardous.booming.debug

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.mardous.booming.playback.Playback
import com.mardous.booming.playback.PlaybackService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal class SourceSeparationDebugMediaClient(
    context: Context,
) : AutoCloseable {
    private val controllerFuture = MediaController.Builder(
        context,
        SessionToken(context, ComponentName(context, PlaybackService::class.java)),
    ).buildAsync()

    private val controller: MediaController by lazy {
        controllerFuture.get(CONTROLLER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    fun execute(block: MediaController.() -> Unit) {
        onControllerThread { controller.block() }
    }

    fun <T> read(block: MediaController.() -> T): T =
        onControllerThread { controller.block() }

    fun openSong(mediaId: String, play: Boolean, positionMs: Long?) {
        require(mediaId.isNotBlank()) { "media_id is required." }
        execute {
            setMediaItem(MediaItem.Builder().setMediaId(mediaId).build(), true)
            prepare()
            positionMs?.let { seekTo(it.coerceAtLeast(0L)) }
            if (play) play() else pause()
        }
    }

    fun send(action: String, args: Bundle = Bundle.EMPTY): SessionResult {
        val resultFuture = onControllerThread {
            controller.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args)
        }
        return resultFuture
            .get(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    fun debugState(): Bundle = send(Playback.GET_SOURCE_SEPARATION_DEBUG_STATE).extras

    fun playbackStateName(playbackState: Int): String = when (playbackState) {
        Player.STATE_IDLE -> "idle"
        Player.STATE_BUFFERING -> "buffering"
        Player.STATE_READY -> "ready"
        Player.STATE_ENDED -> "ended"
        else -> playbackState.toString()
    }

    override fun close() {
        if (controllerFuture.isDone) {
            runCatching {
                val instance = controllerFuture.get()
                onControllerThread(instance) { instance.release() }
            }
        } else {
            controllerFuture.cancel(true)
        }
    }

    private fun <T> onControllerThread(
        instance: MediaController = controller,
        block: () -> T,
    ): T {
        if (Looper.myLooper() == instance.applicationLooper) return block()

        val result = AtomicReference<T?>()
        val error = AtomicReference<Throwable?>()
        val completed = CountDownLatch(1)
        check(Handler(instance.applicationLooper).post {
            try {
                result.set(block())
            } catch (throwable: Throwable) {
                error.set(throwable)
            } finally {
                completed.countDown()
            }
        }) { "MediaController application thread is unavailable." }
        check(completed.await(CONTROLLER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            "MediaController application thread did not complete the operation."
        }
        error.get()?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result.get() as T
    }

    private companion object {
        const val CONTROLLER_TIMEOUT_SECONDS = 10L
        const val COMMAND_TIMEOUT_SECONDS = 30L
    }
}
