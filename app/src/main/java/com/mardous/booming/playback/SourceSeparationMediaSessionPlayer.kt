package com.mardous.booming.playback

import androidx.media3.common.FlagSet
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

@UnstableApi
class SourceSeparationMediaSessionPlayer(
    player: Player,
    private val onSourceSeparationVirtualPause: () -> Unit,
    private val onSourceSeparationPlayRequested: () -> Boolean = { false },
) : ForwardingPlayer(player) {

    private val listeners = mutableListOf<Player.Listener>()

    private var sourceSeparationVirtualBuffering = false

    override fun addListener(listener: Player.Listener) {
        listeners.add(listener)
        super.addListener(listener)
    }

    override fun removeListener(listener: Player.Listener) {
        listeners.remove(listener)
        super.removeListener(listener)
    }

    override fun getPlaybackState(): Int {
        return if (sourceSeparationVirtualBuffering) {
            Player.STATE_BUFFERING
        } else {
            super.getPlaybackState()
        }
    }

    override fun getPlayWhenReady(): Boolean {
        return sourceSeparationVirtualBuffering || super.getPlayWhenReady()
    }

    override fun isLoading(): Boolean {
        return sourceSeparationVirtualBuffering || super.isLoading()
    }

    override fun isPlaying(): Boolean {
        return if (sourceSeparationVirtualBuffering) {
            false
        } else {
            super.isPlaying()
        }
    }

    override fun pause() {
        if (sourceSeparationVirtualBuffering) {
            onSourceSeparationVirtualPause()
        }
        super.pause()
    }

    override fun play() {
        if (onSourceSeparationPlayRequested()) return
        super.play()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady && onSourceSeparationPlayRequested()) return
        if (sourceSeparationVirtualBuffering && !playWhenReady) {
            onSourceSeparationVirtualPause()
        }
        super.setPlayWhenReady(playWhenReady)
    }

    fun setSourceSeparationVirtualBuffering(enabled: Boolean) {
        if (sourceSeparationVirtualBuffering == enabled) return

        sourceSeparationVirtualBuffering = enabled
        dispatchVirtualPlaybackState()
    }

    @Suppress("DEPRECATION")
    private fun dispatchVirtualPlaybackState() {
        val playbackState = playbackState
        val playWhenReady = playWhenReady
        val isLoading = isLoading
        val isPlaying = isPlaying
        val events = Player.Events(
            FlagSet.Builder()
                .add(Player.EVENT_PLAYBACK_STATE_CHANGED)
                .add(Player.EVENT_PLAY_WHEN_READY_CHANGED)
                .add(Player.EVENT_IS_LOADING_CHANGED)
                .add(Player.EVENT_IS_PLAYING_CHANGED)
                .build()
        )
        listeners.toList().forEach { listener ->
            listener.onPlaybackStateChanged(playbackState)
            listener.onPlayWhenReadyChanged(
                playWhenReady,
                Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE,
            )
            listener.onLoadingChanged(isLoading)
            listener.onIsLoadingChanged(isLoading)
            listener.onPlayerStateChanged(playWhenReady, playbackState)
            listener.onIsPlayingChanged(isPlaying)
            listener.onEvents(this, events)
        }
    }
}
