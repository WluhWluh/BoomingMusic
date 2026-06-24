package com.mardous.booming.ui.screen.player

import android.os.SystemClock
import androidx.media3.common.C
import com.mardous.booming.data.model.Song
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

class SourceSeparationForegroundWorkerCoordinator {
    private val _playbackStateFlow =
        MutableStateFlow(SourceSeparationForegroundPlaybackState())
    val playbackStateFlow = _playbackStateFlow.asStateFlow()

    private val _eventFlow =
        MutableSharedFlow<SourceSeparationForegroundPlaybackEvent>(
            extraBufferCapacity = 32,
        )
    val eventFlow = _eventFlow.asSharedFlow()

    fun updateSong(
        song: Song,
        positionMs: Long,
        durationMs: Long,
        isPlaying: Boolean,
        sourceSeparationBlend: Float,
    ) {
        _playbackStateFlow.value = SourceSeparationForegroundPlaybackState(
            song = song,
            positionMs = positionMs,
            durationMs = durationMs,
            isPlaying = isPlaying,
            sourceSeparationBlend = sourceSeparationBlend,
            updatedAtElapsedMs = SystemClock.elapsedRealtime(),
        )
        _eventFlow.tryEmit(
            SourceSeparationForegroundPlaybackEvent.SongChanged(
                song = song,
                positionMs = positionMs,
                durationMs = durationMs,
                isPlaying = isPlaying,
                sourceSeparationBlend = sourceSeparationBlend,
            )
        )
    }

    fun updatePosition(
        positionMs: Long,
        durationMs: Long,
        isPlaying: Boolean,
        sourceSeparationBlend: Float,
    ) {
        val current = _playbackStateFlow.value
        _playbackStateFlow.value = current.copy(
            positionMs = positionMs,
            durationMs = durationMs,
            isPlaying = isPlaying,
            sourceSeparationBlend = sourceSeparationBlend,
            updatedAtElapsedMs = SystemClock.elapsedRealtime(),
        )
        _eventFlow.tryEmit(
            SourceSeparationForegroundPlaybackEvent.PositionChanged(
                positionMs = positionMs,
                durationMs = durationMs,
                isPlaying = isPlaying,
                sourceSeparationBlend = sourceSeparationBlend,
            )
        )
    }

    fun estimatedPositionMs(): Long {
        return _playbackStateFlow.value.estimatedPositionMs()
    }
}

data class SourceSeparationForegroundPlaybackState(
    val song: Song = Song.emptySong,
    val positionMs: Long = C.TIME_UNSET,
    val durationMs: Long = C.TIME_UNSET,
    val isPlaying: Boolean = false,
    val sourceSeparationBlend: Float = 0.5f,
    val updatedAtElapsedMs: Long = SystemClock.elapsedRealtime(),
) {
    fun estimatedPositionMs(nowElapsedMs: Long = SystemClock.elapsedRealtime()): Long {
        if (positionMs == C.TIME_UNSET) return C.TIME_UNSET
        val estimated = if (isPlaying) {
            positionMs + (nowElapsedMs - updatedAtElapsedMs).coerceAtLeast(0L)
        } else {
            positionMs
        }
        return if (durationMs != C.TIME_UNSET && durationMs > 0L) {
            estimated.coerceIn(0L, durationMs)
        } else {
            estimated.coerceAtLeast(0L)
        }
    }
}

sealed interface SourceSeparationForegroundPlaybackEvent {
    data class SongChanged(
        val song: Song,
        val positionMs: Long,
        val durationMs: Long,
        val isPlaying: Boolean,
        val sourceSeparationBlend: Float,
    ) : SourceSeparationForegroundPlaybackEvent

    data class PositionChanged(
        val positionMs: Long,
        val durationMs: Long,
        val isPlaying: Boolean,
        val sourceSeparationBlend: Float,
    ) : SourceSeparationForegroundPlaybackEvent
}
