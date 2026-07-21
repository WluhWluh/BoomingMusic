package com.mardous.booming.ui.screen.player

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.media3.common.C
import androidx.core.content.edit
import com.mardous.booming.R
import com.mardous.booming.data.model.Song
import com.mardous.booming.separation.SourceSeparationCacheStatus
import com.mardous.booming.separation.SourceSeparationEngine
import com.mardous.booming.separation.SourceSeparationPausedException
import com.mardous.booming.separation.SourceSeparationPerformanceStats
import com.mardous.booming.separation.SourceSeparationPlayableCacheStatus
import com.mardous.booming.separation.model.MdxRangeProgress
import com.mardous.booming.separation.model.ReusableMdxInferenceSessionProvider
import com.mardous.booming.separation.model.MdxSourceDecodeMode
import com.mardous.booming.separation.model.SourceSeparationModelLoadException
import com.mardous.booming.util.DEFAULT_SOURCE_SEPARATION_AUTO_CACHE_CLEANUP
import com.mardous.booming.util.DEFAULT_SOURCE_SEPARATION_AUTO_CACHE_CLEANUP_COMPLETED_LIMIT
import com.mardous.booming.util.DEFAULT_SOURCE_SEPARATION_AUTO_CACHE_CLEANUP_PARTIAL_LIMIT
import com.mardous.booming.util.DEFAULT_SOURCE_SEPARATION_AUTO_START
import com.mardous.booming.util.DEFAULT_SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT
import com.mardous.booming.util.DEFAULT_SOURCE_SEPARATION_WINDOW_DECODE
import com.mardous.booming.util.SOURCE_SEPARATION_AUTO_CACHE_CLEANUP
import com.mardous.booming.util.SOURCE_SEPARATION_AUTO_CACHE_CLEANUP_COMPLETED_LIMIT
import com.mardous.booming.util.SOURCE_SEPARATION_AUTO_CACHE_CLEANUP_PARTIAL_LIMIT
import com.mardous.booming.util.SOURCE_SEPARATION_AUTO_FLAC_COMPRESSION
import com.mardous.booming.util.SOURCE_SEPARATION_AUTO_START
import com.mardous.booming.util.SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT
import com.mardous.booming.util.SOURCE_SEPARATION_WINDOW_DECODE
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

class SourceSeparationForegroundWorkerCoordinator(
    private val context: Context,
    private val preferences: SharedPreferences,
    private val sourceSeparationEngine: SourceSeparationEngine,
) {
    private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val performanceStats = SourceSeparationPerformanceStats(preferences)
    private val sessionProvider = ReusableMdxInferenceSessionProvider()
    private val cancelRequested = AtomicBoolean(false)
    private val pauseRequested = AtomicBoolean(false)
    private val debugWindowSamples = ArrayDeque<SourceSeparationDebugWindowSample>()

    private var workerJob: Job? = null
    private var workerActivated = false
    private var workerSongId: Long? = null
    private var pendingStartRequest: SourceSeparationWorkerRequest? = null
    private var activeWorkerRequest: SourceSeparationWorkerRequest? = null
    private var autoStartSuppressedSongId: Long? = null
    private var debugLastWindowSample: SourceSeparationDebugWindowSample? = null
    private var callbacks: SourceSeparationForegroundWorkerCallbacks? = null

    private val _playbackStateFlow =
        MutableStateFlow(SourceSeparationForegroundPlaybackState())
    val playbackStateFlow = _playbackStateFlow.asStateFlow()

    private val _workerStateFlow =
        MutableStateFlow<SourceSeparationUiState>(SourceSeparationUiState.Idle)
    val workerStateFlow = _workerStateFlow.asStateFlow()

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
        pauseWorkerIfSongChanged(song)
        ensureWorkerRunningIfActivated()
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
        ensureWorkerRunningIfActivated()
    }

    fun estimatedPositionMs(): Long {
        return _playbackStateFlow.value.estimatedPositionMs()
    }

    fun attachCallbacks(callbacks: SourceSeparationForegroundWorkerCallbacks) {
        this.callbacks = callbacks
    }

    fun detachCallbacks(callbacks: SourceSeparationForegroundWorkerCallbacks) {
        if (this.callbacks === callbacks) {
            this.callbacks = null
        }
    }

    fun startCurrentSong(): Boolean {
        val song = _playbackStateFlow.value.song
        if (song == Song.emptySong) {
            _workerStateFlow.value = SourceSeparationUiState.Failed(
                songId = song.id,
                songTitle = song.title,
                message = null,
            )
            return false
        }
        requestSong(song)
        return true
    }

    fun requestSong(song: Song) {
        if (song == Song.emptySong) return
        workerActivated = true
        autoStartSuppressedSongId = null
        if (workerJob?.isActive == true) {
            if (workerSongId == song.id) {
                pauseRequested.set(false)
                val workerState = _workerStateFlow.value
                if (workerState !is SourceSeparationUiState.Running) {
                    setPendingStart(
                        SourceSeparationWorkerRequest.Full(
                            song = song,
                            reason = SourceSeparationPendingStartReason.Manual,
                        )
                    )
                }
                if (workerState is SourceSeparationUiState.Paused ||
                    workerState is SourceSeparationUiState.Idle
                ) {
                    _workerStateFlow.value = SourceSeparationUiState.Running(
                        songId = song.id,
                        songTitle = song.title,
                    )
                }
                return
            }
            if (pendingStartRequest?.song?.id == song.id) {
                pendingStartRequest = SourceSeparationWorkerRequest.Full(
                    song = song,
                    reason = SourceSeparationPendingStartReason.Manual,
                )
                return
            }
            setPendingStart(
                SourceSeparationWorkerRequest.Full(
                    song = song,
                    reason = SourceSeparationPendingStartReason.Manual,
                )
            )
            if (workerSongId != null) {
                pauseRequested.set(true)
            }
            return
        }

        cancelRequested.set(false)
        pauseRequested.set(false)
        setPendingStart(
            SourceSeparationWorkerRequest.Full(
                song = song,
                reason = SourceSeparationPendingStartReason.Manual,
            )
        )
        workerJob = workerScope.launch {
            runWorkerLoop()
        }
    }

    fun preStartSong(song: Song, readyWindowCount: Int): Boolean {
        if (song == Song.emptySong || readyWindowCount <= 0) return false
        if (workerSongId == song.id) return false
        if (hasReadyPlaybackStartCache(song, readyWindowCount)) return false

        workerActivated = true
        val request = SourceSeparationWorkerRequest.StartWindowPreStart(
            song = song,
            readyWindowCount = readyWindowCount,
        )
        if (pendingStartRequest?.song?.id == song.id) {
            pendingStartRequest = request
            return true
        }
        if (workerJob?.isActive == true) {
            setPendingStart(request)
            return true
        }

        cancelRequested.set(false)
        pauseRequested.set(false)
        setPendingStart(request)
        workerJob = workerScope.launch {
            runWorkerLoop()
        }
        return true
    }

    fun pauseCurrentSong(currentSong: Song) {
        if (workerJob?.isActive == true) {
            _workerStateFlow.value = SourceSeparationUiState.Paused(
                songId = currentSong.id,
                songTitle = currentSong.title,
            )
        }
        autoStartSuppressedSongId = currentSong
            .takeIf { it != Song.emptySong }
            ?.id
        clearPendingStart()
        pauseRequested.set(true)
    }

    fun cancel() {
        workerActivated = false
        cancelRequested.set(true)
        workerJob?.cancel()
    }

    fun suppressAndPauseSong(songId: Long) {
        autoStartSuppressedSongId = songId
        if (pendingStartRequest?.song?.id == songId) {
            clearPendingStart()
        }
        pauseRequested.set(true)
    }

    fun clearAutoStartSuppressionForSong(songId: Long) {
        if (autoStartSuppressedSongId == songId) {
            autoStartSuppressedSongId = null
        }
    }

    suspend fun waitForWorkerToLeaveSong(songId: Long) {
        while (workerSongId == songId && workerJob?.isActive == true) {
            delay(SOURCE_SEPARATION_FOREGROUND_WORKER_LEAVE_SONG_WAIT_MS)
        }
    }

    fun clearStatusIfNotRunning() {
        if (_workerStateFlow.value !is SourceSeparationUiState.Running) {
            _workerStateFlow.value = SourceSeparationUiState.Idle
        }
    }

    fun isWorkerActive(): Boolean = workerJob?.isActive == true

    fun runningSongId(): Long? = workerSongId

    fun pendingSongId(): Long? = pendingStartRequest?.song?.id

    fun protectedSongIds(): Set<Long> {
        return buildSet {
            _playbackStateFlow.value.song.id
                .takeIf { it != Song.emptySong.id }
                ?.let(::add)
            workerSongId?.let(::add)
            pendingStartRequest?.song?.id?.let(::add)
        }
    }

    suspend fun autoStartDecision(
        song: Song,
        blend: Float,
    ): SourceSeparationAutoStartDecision {
        if (!isAutoStartEnabled() ||
            readBlendMode() == SourceSeparationBlendMode.Off ||
            song == Song.emptySong ||
            isDefaultBlend(blend)
        ) {
            return SourceSeparationAutoStartDecision(
                shouldStart = false,
                shouldWaitForProcessingCache = false,
                hasCompletedCache = false,
            )
        }
        if (workerSongId == song.id || pendingStartRequest?.song?.id == song.id) {
            return SourceSeparationAutoStartDecision(
                shouldStart = false,
                shouldWaitForProcessingCache = true,
                hasCompletedCache = false,
            )
        }
        if (autoStartSuppressedSongId == song.id) {
            return SourceSeparationAutoStartDecision(
                shouldStart = false,
                shouldWaitForProcessingCache = false,
                hasCompletedCache = false,
            )
        }

        val hasCompletedCache = runCatching {
            withContext(Dispatchers.IO) {
                when (sourceSeparationEngine.cacheStatusForSong(song)) {
                    is SourceSeparationCacheStatus.Completed,
                    is SourceSeparationCacheStatus.CompletedWithTemporaryFiles -> true
                    SourceSeparationCacheStatus.NotStarted,
                    is SourceSeparationCacheStatus.Partial -> false
                }
            }
        }.getOrDefault(false)

        return SourceSeparationAutoStartDecision(
            shouldStart = !hasCompletedCache && _playbackStateFlow.value.song.id == song.id,
            shouldWaitForProcessingCache = false,
            hasCompletedCache = hasCompletedCache,
        )
    }

    suspend fun blendForSong(
        mode: SourceSeparationBlendMode,
        song: Song,
        fallbackBlend: Float?,
        trustFallbackBlend: Boolean = false,
    ): Float {
        return when (mode) {
            SourceSeparationBlendMode.Off,
            SourceSeparationBlendMode.Global -> {
                fallbackBlend ?: readGlobalBlend()
            }
            SourceSeparationBlendMode.PerSong -> {
                withContext(Dispatchers.IO) {
                    val persistedBlend = runCatching {
                        sourceSeparationEngine.separatedPlaybackBlendForSong(song)
                    }.getOrNull()
                    persistedBlend
                        ?: readTemporaryPerSongBlend(song)
                            ?.also { blend -> migrateTemporaryPerSongBlend(song, blend) }
                        ?: fallbackBlend?.takeIf { trustFallbackBlend }
                        ?: DEFAULT_SOURCE_SEPARATION_BLEND
                }
            }
        }.coerceIn(0f, 1f)
    }

    suspend fun recordedBlendForSong(song: Song): Float? {
        if (song == Song.emptySong) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                sourceSeparationEngine.separatedPlaybackBlendForSong(song)
            }.getOrNull()
                ?: readTemporaryPerSongBlend(song)
        }?.coerceIn(0f, 1f)
    }

    fun debugStatus(): String {
        val playbackState = _playbackStateFlow.value
        val state = _workerStateFlow.value
        return buildString {
            append("currentSong=").append(playbackState.song.id)
            append(" title=").append(playbackState.song.title)
            append(" workerActive=").append(workerJob?.isActive == true)
            append(" workerSong=").append(workerSongId)
            append(" pending=").append(pendingStartRequest?.song?.id)
            append(" pendingReason=").append(pendingStartRequest?.debugReason)
            append(" suppressed=").append(autoStartSuppressedSongId)
            append(" pauseRequested=").append(pauseRequested.get())
            append(" cancelRequested=").append(cancelRequested.get())
            append(" activated=").append(workerActivated)
            append(" autoStart=").append(isAutoStartEnabled())
            append(" mode=").append(readBlendMode())
            append(" blend=").append(playbackState.sourceSeparationBlend)
            append(" ui=").append(state.debugName())
            append(" progress=").append(playbackState.estimatedPositionMs())
            append(" duration=").append(playbackState.durationMs)
            append(" lastSample=").append(debugLastWindowSample?.toDebugText())
            append(" sampleCount=").append(debugWindowSamples.size)
        }
    }

    fun windowSamples(): String {
        return if (debugWindowSamples.isEmpty()) {
            "samples=empty"
        } else {
            debugWindowSamples.joinToString(separator = "|") { it.toDebugText() }
        }
    }

    fun clearWindowSamples() {
        debugWindowSamples.clear()
        debugLastWindowSample = null
    }

    private suspend fun runWorkerLoop() {
        val activeJob = coroutineContext[Job]
        try {
            while (activeJob?.isActive == true && !cancelRequested.get()) {
                val request = nextWorkerRequest()
                if (request == null) {
                    workerSongId = null
                    delay(SOURCE_SEPARATION_FOREGROUND_WORKER_IDLE_MS)
                    continue
                }

                clearPendingStart()
                activeWorkerRequest = request
                try {
                    runWorkerSong(request.song, activeJob)
                } finally {
                    if (activeWorkerRequest === request) {
                        activeWorkerRequest = null
                    }
                }
            }
        } finally {
            if (workerJob == activeJob) {
                workerJob = null
            }
            if (!workerActivated) {
                sessionProvider.close()
            }
            workerSongId = null
            activeWorkerRequest = null
            clearPendingStart()
            cancelRequested.set(false)
            pauseRequested.set(false)
        }
    }

    private suspend fun nextWorkerRequest(): SourceSeparationWorkerRequest? {
        pendingStartRequest?.let { request ->
            return when (request) {
                is SourceSeparationWorkerRequest.Full -> nextFullWorkerRequest(request)
                is SourceSeparationWorkerRequest.StartWindowPreStart ->
                    nextPreStartWorkerRequest(request)
            }
        }

        if (!workerActivated) return null
        val song = _playbackStateFlow.value.song.takeIf { it != Song.emptySong } ?: return null
        if (autoStartSuppressedSongId == song.id) return null
        return nextFullWorkerRequest(
            SourceSeparationWorkerRequest.Full(
                song = song,
                reason = SourceSeparationPendingStartReason.AutoHandoff,
            )
        )
    }

    private suspend fun nextFullWorkerRequest(
        request: SourceSeparationWorkerRequest.Full,
    ): SourceSeparationWorkerRequest.Full? {
        val song = _playbackStateFlow.value.song.takeIf { song ->
            song.id == request.song.id && song != Song.emptySong
        } ?: run {
            clearPendingStart()
            return null
        }
        if (request.reason == SourceSeparationPendingStartReason.Manual) {
            return request.copy(song = song)
        }
        val mode = readBlendMode()
        if (!isAutoStartEnabled() || mode == SourceSeparationBlendMode.Off) {
            clearPendingStart()
            return null
        }
        val blend = blendForSong(
            mode = mode,
            song = song,
            fallbackBlend = null,
        )
        return request.copy(song = song).takeIf {
            autoStartDecision(song, blend).shouldStart
        } ?: run {
            clearPendingStart()
            null
        }
    }

    private fun nextPreStartWorkerRequest(
        request: SourceSeparationWorkerRequest.StartWindowPreStart,
    ): SourceSeparationWorkerRequest.StartWindowPreStart? {
        if (!isAutoStartEnabled() ||
            !songNeedsSeparatedOutputForCurrentMode(request.song) ||
            hasReadyPlaybackStartCache(request.song, request.readyWindowCount)
        ) {
            clearPendingStart()
            return null
        }
        return request
    }

    private fun pauseWorkerIfSongChanged(song: Song) {
        if (autoStartSuppressedSongId != null &&
            autoStartSuppressedSongId != song.id
        ) {
            autoStartSuppressedSongId = null
        }
        val runningSongId = workerSongId ?: return
        if (song.id != runningSongId) {
            if (isRunningPreStartRequestFor(song)) {
                return
            }
            song.takeIf { it != Song.emptySong }
                ?.let {
                    setPendingStart(
                        SourceSeparationWorkerRequest.Full(
                            song = it,
                            reason = SourceSeparationPendingStartReason.AutoHandoff,
                        )
                    )
                }
                ?: clearPendingStart()
            pauseRequested.set(true)
        }
    }

    private fun isRunningPreStartRequestFor(song: Song): Boolean {
        return activeWorkerRequest is SourceSeparationWorkerRequest.StartWindowPreStart &&
                workerSongId == song.id
    }

    private fun setPendingStart(request: SourceSeparationWorkerRequest) {
        pendingStartRequest = request
    }

    private fun clearPendingStart() {
        pendingStartRequest = null
    }

    private fun ensureWorkerRunningIfActivated() {
        if (!workerActivated || workerJob?.isActive == true) return
        cancelRequested.set(false)
        pauseRequested.set(false)
        workerJob = workerScope.launch {
            runWorkerLoop()
        }
    }

    private suspend fun runWorkerSong(
        song: Song,
        activeJob: Job?,
    ) {
        val request = activeWorkerRequest
            ?: SourceSeparationWorkerRequest.Full(
                song = song,
                reason = SourceSeparationPendingStartReason.AutoHandoff,
            )
        val preStartReadyWindowCount =
            (request as? SourceSeparationWorkerRequest.StartWindowPreStart)
                ?.readyWindowCount
                ?.coerceAtLeast(1)
        var preStartSatisfied = false
        pauseRequested.set(false)
        workerSongId = song.id
        val shouldPromoteCompletedStems = preferences.getBoolean(
            SOURCE_SEPARATION_AUTO_FLAC_COMPRESSION,
            true,
        )
        if (hasCompletedCache(song)) {
            _workerStateFlow.value = SourceSeparationUiState.Completed(
                songId = song.id,
                songTitle = song.title,
            )
            return
        }
        _workerStateFlow.value = SourceSeparationUiState.Running(
            songId = song.id,
            songTitle = song.title,
        )
        try {
            sourceSeparationEngine.separateSongToWav(
                song = song,
                promoteCompletedStems = false,
                onProgress = { progress ->
                    val averageWindowMs = progress.completedWindowElapsedMs
                        ?.let(performanceStats::recordWindowElapsed)
                        ?: performanceStats.averageWindowMs()
                    recordDebugWindowSample(song, progress)
                    if (preStartReadyWindowCount != null &&
                        progress.scheduler?.playbackSegmentIndex == 0 &&
                        progress.scheduler.playbackReadyWindowReadyCount >=
                        preStartReadyWindowCount
                    ) {
                        preStartSatisfied = true
                    }
                    _workerStateFlow.value = SourceSeparationUiState.Running(
                        songId = song.id,
                        songTitle = song.title,
                        completedWindows = progress.completedWindows,
                        totalWindows = progress.totalWindows,
                        percent = progress.percent,
                        stage = progress.stage,
                        sourceDecodeDiagnostics = progress.sourceDecodeDiagnostics?.toDisplayText(),
                        sourceDecodeMode = progress.sourceDecodeDiagnostics
                            ?.mode
                            ?.toUiState(),
                        averageWindowMs = averageWindowMs,
                        lastWindowMs = progress.completedWindowElapsedMs,
                        scheduler = progress.scheduler?.let { scheduler ->
                            SourceSeparationSchedulerUiState(
                                playbackSegmentIndex = scheduler.playbackSegmentIndex,
                                playbackSegmentState = scheduler.playbackSegmentState,
                                nextSegmentIndex = scheduler.nextSegmentIndex,
                                nextSegmentState = scheduler.nextSegmentState,
                                processingSegmentIndex = scheduler.processingSegmentIndex,
                                priority = scheduler.priority,
                                readySegments = scheduler.readySegments,
                                totalSegments = scheduler.totalSegments,
                                readyWindowCount = scheduler.readyWindowCount,
                                playbackReadyWindowReadyCount =
                                    scheduler.playbackReadyWindowReadyCount,
                                playbackReadyWindowPendingCount =
                                    scheduler.playbackReadyWindowPendingCount,
                            )
                        },
                    )
                    if (pendingStartRequest?.song?.id == song.id) {
                        clearPendingStart()
                    }
                    callbacks?.onSourceSeparationWorkerProgress(song)
                },
                onPrepared = {
                    if (preStartReadyWindowCount == null &&
                        readBlendMode() == SourceSeparationBlendMode.PerSong
                    ) {
                        migrateTemporaryPerSongBlend(song)
                    }
                    callbacks?.onSourceSeparationWorkerPrepared(song)
                },
                playbackPositionMsProvider = {
                    if (preStartReadyWindowCount != null) {
                        0L
                    } else {
                        _playbackStateFlow.value
                            .takeIf { it.song.id == song.id }
                            ?.estimatedPositionMs()
                            ?.takeIf { it != C.TIME_UNSET }
                    }
                },
                playbackReadyWindowCountProvider = {
                    preStartReadyWindowCount ?: preferences.getInt(
                        SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT,
                        DEFAULT_SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT,
                    ).coerceAtLeast(1)
                },
                windowDecodeEnabled = preferences.getBoolean(
                    SOURCE_SEPARATION_WINDOW_DECODE,
                    DEFAULT_SOURCE_SEPARATION_WINDOW_DECODE,
                ),
                sessionProvider = sessionProvider,
                shouldPause = {
                    pauseRequested.get() ||
                            preStartSatisfied ||
                            (preStartReadyWindowCount == null &&
                                    _playbackStateFlow.value.song.id != song.id) ||
                            activeJob?.isActive != true
                },
                shouldCancel = {
                    cancelRequested.get() ||
                            (!pauseRequested.get() && activeJob?.isActive != true)
                },
            )
            val playbackState = _playbackStateFlow.value
            if (preStartReadyWindowCount == null &&
                readBlendMode() == SourceSeparationBlendMode.PerSong &&
                playbackState.song.id == song.id
            ) {
                savePerSongBlend(
                    song = song,
                    blend = playbackState.sourceSeparationBlend,
                )
            }
            _workerStateFlow.value = SourceSeparationUiState.Completed(
                songId = song.id,
                songTitle = song.title,
            )
            pruneCachesIfEnabled()
            callbacks?.onSourceSeparationWorkerCompleted(
                song = song,
                shouldPromoteCompletedStems = shouldPromoteCompletedStems,
            )
        } catch (_: SourceSeparationPausedException) {
            _workerStateFlow.value = SourceSeparationUiState.Idle
            if (!preStartSatisfied) {
                callbacks?.onSourceSeparationWorkerPaused(song)
            }
        } catch (_: CancellationException) {
            _workerStateFlow.value = SourceSeparationUiState.Canceled(
                songId = song.id,
                songTitle = song.title,
            )
            cancelRequested.set(true)
        } catch (_: SourceSeparationModelLoadException) {
            val message = context.getString(R.string.source_separation_model_load_failed)
            _workerStateFlow.value = SourceSeparationUiState.Failed(
                songId = song.id,
                songTitle = song.title,
                message = message,
            )
            callbacks?.onSourceSeparationWorkerModelLoadFailed(message)
            cancelRequested.set(true)
        } catch (error: Throwable) {
            _workerStateFlow.value = SourceSeparationUiState.Failed(
                songId = song.id,
                songTitle = song.title,
                message = error.message,
            )
            cancelRequested.set(true)
        } finally {
            if (workerSongId == song.id) {
                workerSongId = null
            }
            pauseRequested.set(false)
        }
    }

    private suspend fun hasCompletedCache(song: Song): Boolean {
        return withContext(Dispatchers.IO) {
            when (runCatching { sourceSeparationEngine.cacheStatusForSong(song) }.getOrNull()) {
                is SourceSeparationCacheStatus.Completed,
                is SourceSeparationCacheStatus.CompletedWithTemporaryFiles -> true
                is SourceSeparationCacheStatus.Partial,
                SourceSeparationCacheStatus.NotStarted,
                null -> false
            }
        }
    }

    private fun hasReadyPlaybackStartCache(song: Song, readyWindowCount: Int): Boolean {
        return runCatching {
            sourceSeparationEngine.playableCacheStatusForSong(
                song = song,
                playbackPositionMs = 0L,
                readyWindowCount = readyWindowCount.coerceAtLeast(1),
            ) is SourceSeparationPlayableCacheStatus.Ready
        }.getOrDefault(false)
    }

    private fun recordDebugWindowSample(
        song: Song,
        progress: MdxRangeProgress,
    ) {
        val elapsedMs = progress.completedWindowElapsedMs ?: return
        val sample = SourceSeparationDebugWindowSample(
            elapsedRealtimeMs = SystemClock.elapsedRealtime(),
            songId = song.id,
            songTitle = song.title,
            completedWindows = progress.completedWindows,
            totalWindows = progress.totalWindows,
            elapsedMs = elapsedMs,
            playbackPositionMs = _playbackStateFlow.value
                .takeIf { it.song.id == song.id }
                ?.estimatedPositionMs()
                ?.takeIf { it != C.TIME_UNSET },
        )
        debugLastWindowSample = sample
        debugWindowSamples.addLast(sample)
        while (debugWindowSamples.size > SOURCE_SEPARATION_DEBUG_WINDOW_SAMPLE_LIMIT) {
            debugWindowSamples.removeFirst()
        }
    }

    private fun pruneCachesIfEnabled() {
        if (!preferences.getBoolean(
                SOURCE_SEPARATION_AUTO_CACHE_CLEANUP,
                DEFAULT_SOURCE_SEPARATION_AUTO_CACHE_CLEANUP,
            )
        ) {
            return
        }
        runCatching {
            sourceSeparationEngine.pruneCache(
                partialLimit = preferences.getInt(
                    SOURCE_SEPARATION_AUTO_CACHE_CLEANUP_PARTIAL_LIMIT,
                    DEFAULT_SOURCE_SEPARATION_AUTO_CACHE_CLEANUP_PARTIAL_LIMIT,
                ).coerceAtLeast(1),
                completedLimit = preferences.getInt(
                    SOURCE_SEPARATION_AUTO_CACHE_CLEANUP_COMPLETED_LIMIT,
                    DEFAULT_SOURCE_SEPARATION_AUTO_CACHE_CLEANUP_COMPLETED_LIMIT,
                ).coerceAtLeast(1),
                protectedSongIds = protectedSongIds(),
            )
        }
    }

    private fun isAutoStartEnabled(): Boolean {
        return preferences.getBoolean(
            SOURCE_SEPARATION_AUTO_START,
            DEFAULT_SOURCE_SEPARATION_AUTO_START,
        )
    }

    private fun readBlendMode(): SourceSeparationBlendMode {
        val playbackEnabled = preferences.getBoolean(
            KEY_SOURCE_SEPARATION_PLAYBACK_ENABLED,
            false,
        )
        return when {
            !playbackEnabled -> SourceSeparationBlendMode.Off
            preferences.getBoolean(KEY_SOURCE_SEPARATION_REMEMBER_PER_SONG, true) ->
                SourceSeparationBlendMode.PerSong
            else -> SourceSeparationBlendMode.Global
        }
    }

    private fun readGlobalBlend(): Float {
        return preferences.getFloat(
            KEY_SOURCE_SEPARATION_GLOBAL_BLEND,
            DEFAULT_SOURCE_SEPARATION_BLEND,
        ).coerceIn(0f, 1f)
    }

    private fun isDefaultBlend(blend: Float): Boolean {
        return kotlin.math.abs(blend.coerceIn(0f, 1f) - DEFAULT_SOURCE_SEPARATION_BLEND) <
                SOURCE_SEPARATION_BLEND_EPSILON
    }

    private fun songNeedsSeparatedOutputForCurrentMode(song: Song): Boolean {
        return when (readBlendMode()) {
            SourceSeparationBlendMode.Off -> false
            SourceSeparationBlendMode.Global -> !isDefaultBlend(readGlobalBlend())
            SourceSeparationBlendMode.PerSong -> recordedBlendForSongBlocking(song)
                ?.let { blend -> !isDefaultBlend(blend) }
                ?: false
        }
    }

    private fun recordedBlendForSongBlocking(song: Song): Float? {
        if (song == Song.emptySong) return null
        return runCatching {
            sourceSeparationEngine.separatedPlaybackBlendForSong(song)
        }.getOrNull()
            ?: readTemporaryPerSongBlend(song)
    }

    private fun savePerSongBlend(song: Song, blend: Float): Boolean {
        val saved = runCatching {
            sourceSeparationEngine.saveSeparatedPlaybackBlendForSong(
                song = song,
                blend = blend.coerceIn(0f, 1f),
            )
        }.getOrDefault(false)
        if (saved) {
            removeTemporaryPerSongBlend(song)
        }
        return saved
    }

    private fun migrateTemporaryPerSongBlend(song: Song): Boolean {
        val blend = readTemporaryPerSongBlend(song) ?: return false
        return migrateTemporaryPerSongBlend(song, blend)
    }

    private fun migrateTemporaryPerSongBlend(song: Song, blend: Float): Boolean {
        val saved = runCatching {
            sourceSeparationEngine.saveSeparatedPlaybackBlendForSong(
                song = song,
                blend = blend.coerceIn(0f, 1f),
            )
        }.getOrDefault(false)
        if (saved) {
            removeTemporaryPerSongBlend(song)
        }
        return saved
    }

    private fun readTemporaryPerSongBlend(song: Song): Float? {
        val key = temporaryPerSongBlendKey(song)
        return if (preferences.contains(key)) {
            preferences.getFloat(key, DEFAULT_SOURCE_SEPARATION_BLEND).coerceIn(0f, 1f)
        } else {
            null
        }
    }

    private fun removeTemporaryPerSongBlend(song: Song) {
        preferences.edit {
            remove(temporaryPerSongBlendKey(song))
        }
    }

    private fun temporaryPerSongBlendKey(song: Song): String {
        val identity = "${song.id}|${song.uri}|${song.data}"
        return "$KEY_SOURCE_SEPARATION_TEMP_PER_SONG_BLEND.${sha256Hex(identity)}"
    }

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.encodeToByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}

private enum class SourceSeparationPendingStartReason {
    Manual,
    AutoHandoff,
}

private sealed interface SourceSeparationWorkerRequest {
    val song: Song
    val debugReason: String

    data class Full(
        override val song: Song,
        val reason: SourceSeparationPendingStartReason,
    ) : SourceSeparationWorkerRequest {
        override val debugReason: String = reason.name
    }

    data class StartWindowPreStart(
        override val song: Song,
        val readyWindowCount: Int,
    ) : SourceSeparationWorkerRequest {
        override val debugReason: String = "PreStart($readyWindowCount)"
    }
}

interface SourceSeparationForegroundWorkerCallbacks {
    fun onSourceSeparationWorkerProgress(song: Song)
    fun onSourceSeparationWorkerPrepared(song: Song)
    fun onSourceSeparationWorkerCompleted(
        song: Song,
        shouldPromoteCompletedStems: Boolean,
    )
    fun onSourceSeparationWorkerPaused(song: Song)
    fun onSourceSeparationWorkerModelLoadFailed(message: String)
}

data class SourceSeparationAutoStartDecision(
    val shouldStart: Boolean,
    val shouldWaitForProcessingCache: Boolean,
    val hasCompletedCache: Boolean,
)

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

private data class SourceSeparationDebugWindowSample(
    val elapsedRealtimeMs: Long,
    val songId: Long,
    val songTitle: String,
    val completedWindows: Int,
    val totalWindows: Int,
    val elapsedMs: Long,
    val playbackPositionMs: Long?,
) {
    fun toDebugText(): String {
        return "t=$elapsedRealtimeMs,song=$songId,window=$completedWindows/$totalWindows," +
                "elapsedMs=$elapsedMs,playbackMs=$playbackPositionMs,title=${songTitle.sanitizeDebugText()}"
    }
}

private fun String.sanitizeDebugText(): String {
    return replace('|', '/')
        .replace('\n', ' ')
        .replace('\r', ' ')
}

private fun SourceSeparationUiState.debugName(): String {
    return when (this) {
        SourceSeparationUiState.Idle -> "Idle"
        is SourceSeparationUiState.Running ->
            "Running(song=$songId windows=$completedWindows/$totalWindows percent=$percent " +
                    "lastWindowMs=${lastWindowMs ?: "null"} averageWindowMs=$averageWindowMs " +
                    "stage=${stage.orEmpty()})"
        is SourceSeparationUiState.Completed -> "Completed(song=$songId)"
        is SourceSeparationUiState.Canceled -> "Canceled(song=$songId)"
        is SourceSeparationUiState.Paused -> "Paused(song=$songId)"
        is SourceSeparationUiState.Failed -> "Failed(song=$songId message=${message.orEmpty()})"
    }
}

private fun MdxSourceDecodeMode.toUiState(): SourceSeparationDecodeModeUiState {
    return when (this) {
        MdxSourceDecodeMode.FullSong -> SourceSeparationDecodeModeUiState.FullSong
        MdxSourceDecodeMode.Window -> SourceSeparationDecodeModeUiState.Window
    }
}

private const val DEFAULT_SOURCE_SEPARATION_BLEND = 0.5f
private const val KEY_SOURCE_SEPARATION_PLAYBACK_ENABLED =
    "source_separation.playback_enabled"
private const val KEY_SOURCE_SEPARATION_REMEMBER_PER_SONG =
    "source_separation.remember_per_song"
private const val KEY_SOURCE_SEPARATION_GLOBAL_BLEND =
    "source_separation.global_blend"
private const val KEY_SOURCE_SEPARATION_TEMP_PER_SONG_BLEND =
    "source_separation.per_song_blend.pending"
private const val SOURCE_SEPARATION_BLEND_EPSILON = 0.0001f
private const val SOURCE_SEPARATION_FOREGROUND_WORKER_IDLE_MS = 250L
private const val SOURCE_SEPARATION_FOREGROUND_WORKER_LEAVE_SONG_WAIT_MS = 50L
private const val SOURCE_SEPARATION_DEBUG_WINDOW_SAMPLE_LIMIT = 128
