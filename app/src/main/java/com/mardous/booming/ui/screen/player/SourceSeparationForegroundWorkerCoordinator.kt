package com.mardous.booming.ui.screen.player

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.media3.common.C
import androidx.core.content.edit
import com.mardous.booming.R
import com.mardous.booming.data.model.Song
import com.mardous.booming.separation.SourceSeparationAdmittedGpuRuntimeMismatchException
import com.mardous.booming.separation.SourceSeparationExecutionRunClass
import com.mardous.booming.separation.SourceSeparationModelAwareEngineResult
import com.mardous.booming.separation.SourceSeparationPausedException
import com.mardous.booming.separation.SourceSeparationPerformanceStats
import com.mardous.booming.separation.SourceSeparationRuntimeFacade
import com.mardous.booming.separation.SourceSeparationRuntimeSong
import com.mardous.booming.separation.SourceSeparationRuntimeSongResolution
import com.mardous.booming.separation.SourceSeparationRuntimeUnavailableReason
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunJournal
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwareCacheStatus
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwarePlayableStatus
import com.mardous.booming.separation.model.MdxRangeProgress
import com.mardous.booming.separation.model.MdxSourceDecodeMode
import com.mardous.booming.separation.process.SourceSeparationExecutionHostEvent
import com.mardous.booming.separation.process.SourceSeparationExecutionHostEventPayload
import com.mardous.booming.separation.process.SourceSeparationPlaybackLifecyclePolicy
import com.mardous.booming.separation.process.ipc.SourceSeparationIndependentRunRecovery
import com.mardous.booming.separation.process.ipc.SourceSeparationReconnectedSession
import com.mardous.booming.separation.process.ipc.SourceSeparationRemoteHostDiedException
import com.mardous.booming.separation.process.toMdxRangeProgress
import com.mardous.booming.util.DEFAULT_SOURCE_SEPARATION_AUTO_CACHE_CLEANUP
import com.mardous.booming.util.DEFAULT_SOURCE_SEPARATION_AUTO_CACHE_CLEANUP_COMPLETED_LIMIT
import com.mardous.booming.util.DEFAULT_SOURCE_SEPARATION_AUTO_CACHE_CLEANUP_PARTIAL_LIMIT
import com.mardous.booming.util.DEFAULT_SOURCE_SEPARATION_AUTO_START
import com.mardous.booming.util.DEFAULT_SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT
import com.mardous.booming.util.readSourceSeparationGpuEnabled
import com.mardous.booming.util.DEFAULT_SOURCE_SEPARATION_WINDOW_DECODE
import com.mardous.booming.util.SOURCE_SEPARATION_AUTO_CACHE_CLEANUP
import com.mardous.booming.util.SOURCE_SEPARATION_AUTO_CACHE_CLEANUP_COMPLETED_LIMIT
import com.mardous.booming.util.SOURCE_SEPARATION_AUTO_CACHE_CLEANUP_PARTIAL_LIMIT
import com.mardous.booming.util.SOURCE_SEPARATION_AUTO_FLAC_COMPRESSION
import com.mardous.booming.util.SOURCE_SEPARATION_AUTO_START
import com.mardous.booming.util.SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT
import com.mardous.booming.util.SOURCE_SEPARATION_WINDOW_DECODE
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
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

class SourceSeparationForegroundWorkerCoordinator internal constructor(
    private val context: Context,
    private val preferences: SharedPreferences,
    private val sourceSeparationRuntime: SourceSeparationRuntimeFacade,
    private val independentRunRecovery: SourceSeparationIndependentRunRecovery? = null,
) {
    private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val performanceStats = SourceSeparationPerformanceStats(preferences)
    private val cancelRequested = AtomicBoolean(false)
    private val pauseRequested = AtomicBoolean(false)
    private val playbackOwnerActive = AtomicBoolean(true)
    private val debugWindowSamples = ArrayDeque<SourceSeparationDebugWindowSample>()

    private var workerJob: Job? = null
    private var workerActivated = false
    private var workerSongId: Long? = null
    private var pendingStartRequest: SourceSeparationWorkerRequest? = null
    private var activeWorkerRequest: SourceSeparationWorkerRequest? = null
    private var activeWorkerSong: SourceSeparationRuntimeSong? = null
    private var recoveryJob: Job? = null
    @Volatile
    private var reconnectedSession: SourceSeparationReconnectedSession? = null
    @Volatile
    private var reconnectedSong: Song? = null
    @Volatile
    private var reconnectedLatestSequence = 0L
    private var autoStartSuppressedSongId: Long? = null
    private var debugLastWindowSample: SourceSeparationDebugWindowSample? = null
    private val callbackLock = Any()
    @Volatile
    private var callbacks: SourceSeparationForegroundWorkerCallbacks? = null
    private var pendingRecoveredTerminal: SourceSeparationRecoveredTerminal? = null

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

    init {
        independentRunRecovery?.let(::startIndependentRunRecovery)
    }

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

    fun onPlaybackServiceStarted() {
        playbackOwnerActive.set(true)
    }

    fun onPlaybackServiceStopped() {
        playbackOwnerActive.set(false)
        val nowElapsedMs = SystemClock.elapsedRealtime()
        val playback = _playbackStateFlow.value
        _playbackStateFlow.value = playback.copy(
            positionMs = playback.estimatedPositionMs(nowElapsedMs),
            isPlaying = false,
            updatedAtElapsedMs = nowElapsedMs,
        )
        workerActivated = false
        clearPendingStart()
        if (SourceSeparationPlaybackLifecyclePolicy.shouldPauseWhenPlaybackStops(
                activeWorkerRequest?.runClass,
            )
        ) {
            pauseRequested.set(true)
        }
    }

    fun estimatedPositionMs(): Long {
        return _playbackStateFlow.value.estimatedPositionMs()
    }

    fun attachCallbacks(callbacks: SourceSeparationForegroundWorkerCallbacks) {
        val terminal = synchronized(callbackLock) {
            this.callbacks = callbacks
            pendingRecoveredTerminal.also { pendingRecoveredTerminal = null }
        }
        terminal?.dispatch(callbacks)
    }

    fun detachCallbacks(callbacks: SourceSeparationForegroundWorkerCallbacks) {
        synchronized(callbackLock) {
            if (this.callbacks === callbacks) {
                this.callbacks = null
            }
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
        requestManualSong(song)
        return true
    }

    fun requestManualSong(song: Song) {
        requestFullSong(song, SourceSeparationPendingStartReason.Manual)
    }

    fun requestPlaybackDemandSong(song: Song) {
        if (!playbackOwnerActive.get()) return
        requestFullSong(song, SourceSeparationPendingStartReason.PlaybackDemand)
    }

    private fun requestFullSong(
        song: Song,
        reason: SourceSeparationPendingStartReason,
    ) {
        if (song == Song.emptySong) return
        val incoming = SourceSeparationWorkerRequest.Full(song, reason)
        workerActivated = true
        autoStartSuppressedSongId = null
        if (recoveryJob?.isActive == true || reconnectedSession != null) {
            setPendingStart(incoming)
            maybePauseRecoveredRunFor(incoming)
            return
        }
        if (workerJob?.isActive == true) {
            if (workerSongId == song.id) {
                val active = activeWorkerRequest
                if (active != null && incoming.priority > active.priority) {
                    setPendingStart(incoming)
                    pauseRequested.set(true)
                    return
                }
                if (active != null && incoming.priority < active.priority) {
                    return
                }
                pauseRequested.set(false)
                val workerState = _workerStateFlow.value
                if (workerState !is SourceSeparationUiState.Running) {
                    setPendingStart(incoming)
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
                setPendingStart(incoming)
                return
            }
            setPendingStart(incoming)
            if (workerSongId != null) {
                pauseRequested.set(true)
            }
            return
        }

        cancelRequested.set(false)
        pauseRequested.set(false)
        setPendingStart(incoming)
        workerJob = workerScope.launch {
            runWorkerLoop()
        }
    }

    fun preStartSong(song: Song, readyWindowCount: Int): Boolean {
        if (!playbackOwnerActive.get()) return false
        if (song == Song.emptySong || readyWindowCount <= 0) return false
        if (runningSongId() == song.id) return false
        if (hasReadyPlaybackStartCache(song, readyWindowCount)) return false

        workerActivated = true
        val request = SourceSeparationWorkerRequest.StartWindowPreStart(
            song = song,
            readyWindowCount = readyWindowCount,
        )
        if (recoveryJob?.isActive == true || reconnectedSession != null) {
            setPendingStart(request)
            return true
        }
        if (pendingStartRequest?.song?.id == song.id) {
            setPendingStart(request)
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
        val recovered = reconnectedSession
        val recoveredSong = reconnectedSong
        if (recovered != null && recoveredSong != null) {
            _workerStateFlow.value = SourceSeparationUiState.Paused(
                songId = recoveredSong.id,
                songTitle = recoveredSong.title,
            )
            requestRecoveredControl(recovered, SourceSeparationRecoveredControl.Pause)
        }
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
        clearPendingStart()
        reconnectedSession?.let { session ->
            requestRecoveredControl(session, SourceSeparationRecoveredControl.Cancel)
        }
        workerJob?.cancel()
    }

    fun suppressAndPauseSong(songId: Long) {
        autoStartSuppressedSongId = songId
        if (pendingStartRequest?.song?.id == songId) {
            clearPendingStart()
        }
        pauseRequested.set(true)
        reconnectedSession
            ?.takeIf { reconnectedSong?.id == songId }
            ?.let { session ->
                requestRecoveredControl(session, SourceSeparationRecoveredControl.Pause)
            }
    }

    fun clearAutoStartSuppressionForSong(songId: Long) {
        if (autoStartSuppressedSongId == songId) {
            autoStartSuppressedSongId = null
        }
    }

    suspend fun waitForWorkerToLeaveSong(songId: Long) {
        while ((workerSongId == songId && workerJob?.isActive == true) ||
            (reconnectedSong?.id == songId && reconnectedSession != null)
        ) {
            delay(SOURCE_SEPARATION_FOREGROUND_WORKER_LEAVE_SONG_WAIT_MS)
        }
    }

    fun clearStatusIfNotRunning() {
        if (_workerStateFlow.value !is SourceSeparationUiState.Running) {
            _workerStateFlow.value = SourceSeparationUiState.Idle
        }
    }

    fun isWorkerActive(): Boolean = workerJob?.isActive == true ||
            recoveryJob?.isActive == true || reconnectedSession != null

    fun runningSongId(): Long? = reconnectedSong?.id ?: workerSongId

    fun runningCacheKey(): String? = reconnectedSession?.cacheKey ?: activeWorkerSong?.cacheKey

    fun pendingSongId(): Long? = pendingStartRequest?.song?.id

    fun protectedCacheKeys(): Set<String> = setOfNotNull(
        activeWorkerSong?.cacheKey,
        reconnectedSession?.cacheKey,
    )

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
        if (runningSongId() == song.id || pendingStartRequest?.song?.id == song.id) {
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
                val resolved = resolveSong(song) ?: return@withContext false
                when (sourceSeparationRuntime.cacheStatus(resolved)) {
                    is SourceSeparationModelAwareCacheStatus.Completed -> true
                    SourceSeparationModelAwareCacheStatus.Busy,
                    is SourceSeparationModelAwareCacheStatus.Corrupt,
                    is SourceSeparationModelAwareCacheStatus.Incomplete,
                    SourceSeparationModelAwareCacheStatus.Missing -> false
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
                    val persistedBlend = resolveSong(song)?.let { resolved ->
                        runCatching { sourceSeparationRuntime.readBlend(resolved) }.getOrNull()
                    }
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
                resolveSong(song)?.let(sourceSeparationRuntime::readBlend)
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
            append(" recoveryActive=").append(recoveryJob?.isActive == true)
            append(" reconnectedSong=").append(reconnectedSong?.id)
            append(" reconnectedRun=").append(reconnectedSession?.runId)
            append(" reconnectedSequence=").append(reconnectedLatestSequence)
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

    private fun startIndependentRunRecovery(
        recovery: SourceSeparationIndependentRunRecovery,
    ) {
        val job = workerScope.launch(start = CoroutineStart.LAZY) {
            runIndependentRunRecovery(recovery)
        }
        recoveryJob = job
        job.start()
    }

    private suspend fun runIndependentRunRecovery(
        recovery: SourceSeparationIndependentRunRecovery,
    ) {
        val activeJob = coroutineContext[Job]
        val events = Channel<SourceSeparationExecutionHostEvent>(Channel.UNLIMITED)
        var session: SourceSeparationReconnectedSession? = null
        var terminal = false
        try {
            session = recovery.reconnect(
                onEvent = { event -> events.trySend(event) },
            ) ?: return
            reconnectedSession = session
            reconnectedSong = session.journal.toRecoveredSong()
            reconnectedLatestSequence = 0L
            terminal = applyRecoveredBaseline(session)
            if (!terminal) {
                when {
                    cancelRequested.get() -> requestRecoveredControl(
                        session,
                        SourceSeparationRecoveredControl.Cancel,
                    )
                    pauseRequested.get() -> requestRecoveredControl(
                        session,
                        SourceSeparationRecoveredControl.Pause,
                    )
                    else -> pendingStartRequest?.let(::maybePauseRecoveredRunFor)
                }
            }
            while (!terminal && activeJob?.isActive == true) {
                val event = events.receiveCatching().getOrNull() ?: break
                terminal = applyRecoveredEvent(session, event)
            }
        } catch (_: SourceSeparationRemoteHostDiedException) {
            val song = reconnectedSong
            if (song != null) {
                _workerStateFlow.value = SourceSeparationUiState.Failed(
                    songId = song.id,
                    songTitle = song.title,
                    message = remoteProcessStoppedMessage(),
                )
            }
            workerActivated = false
            clearPendingStart()
            cancelRequested.set(true)
        } catch (error: Throwable) {
            val song = reconnectedSong
            if (song != null && error !is CancellationException) {
                _workerStateFlow.value = SourceSeparationUiState.Failed(
                    songId = song.id,
                    songTitle = song.title,
                    message = error.message,
                )
            }
        } finally {
            events.close()
            if (!terminal) {
                runCatching { session?.close() }
            }
            if (reconnectedSession === session || reconnectedSession == null) {
                reconnectedSession = null
                reconnectedSong = null
                reconnectedLatestSequence = 0L
            }
            if (recoveryJob == activeJob) {
                recoveryJob = null
            }
            cancelRequested.set(false)
            pauseRequested.set(false)
            if (pendingStartRequest != null) {
                ensureWorkerRunningIfActivated()
            }
        }
    }

    private fun applyRecoveredBaseline(
        session: SourceSeparationReconnectedSession,
    ): Boolean {
        val song = requireNotNull(reconnectedSong)
        _workerStateFlow.value = SourceSeparationUiState.Running(
            songId = song.id,
            songTitle = song.title,
        )
        return applyRecoveredEvent(session, session.baselineEvent)
    }

    private fun applyRecoveredEvent(
        session: SourceSeparationReconnectedSession,
        event: SourceSeparationExecutionHostEvent,
    ): Boolean {
        require(event.runId == session.runId &&
            event.processGeneration == session.processGeneration
        ) { "Recovered source-separation event has a stale identity." }
        if (event.sequence <= reconnectedLatestSequence) return false
        reconnectedLatestSequence = event.sequence
        val recoveredSong = requireNotNull(reconnectedSong)
        val callbackSong = recoveredSong.callbackSong()
        return when (val payload = event.payload) {
            is SourceSeparationExecutionHostEventPayload.Accepted -> {
                require(payload.descriptor.runId == session.runId &&
                    payload.descriptor.processGeneration == session.processGeneration &&
                    payload.descriptor.cacheKey == session.cacheKey
                ) { "Recovered source-separation acceptance has a stale identity." }
                _workerStateFlow.value = SourceSeparationUiState.Running(
                    songId = recoveredSong.id,
                    songTitle = recoveredSong.title,
                )
                false
            }
            is SourceSeparationExecutionHostEventPayload.Progress -> {
                publishWorkerProgress(recoveredSong, payload.progress.toMdxRangeProgress())
                false
            }
            is SourceSeparationExecutionHostEventPayload.Prepared -> {
                if (_workerStateFlow.value !is SourceSeparationUiState.Running) {
                    _workerStateFlow.value = SourceSeparationUiState.Running(
                        songId = recoveredSong.id,
                        songTitle = recoveredSong.title,
                    )
                }
                callbacks?.onSourceSeparationWorkerPrepared(callbackSong)
                false
            }
            is SourceSeparationExecutionHostEventPayload.SegmentStateChanged,
            is SourceSeparationExecutionHostEventPayload.GpuFallbackLatched,
            -> false
            is SourceSeparationExecutionHostEventPayload.Completed -> {
                _workerStateFlow.value = SourceSeparationUiState.Completed(
                    songId = recoveredSong.id,
                    songTitle = recoveredSong.title,
                )
                pruneCachesIfEnabled()
                closeRecoveredTerminal(session)
                dispatchRecoveredTerminal(
                    SourceSeparationRecoveredTerminal.Completed(
                        song = callbackSong,
                        cacheKey = session.cacheKey,
                        shouldPromoteCompletedStems = preferences.getBoolean(
                            SOURCE_SEPARATION_AUTO_FLAC_COMPRESSION,
                            true,
                        ),
                    )
                )
                true
            }
            is SourceSeparationExecutionHostEventPayload.Paused -> {
                _workerStateFlow.value = SourceSeparationUiState.Idle
                closeRecoveredTerminal(session)
                dispatchRecoveredTerminal(
                    SourceSeparationRecoveredTerminal.Paused(callbackSong)
                )
                true
            }
            is SourceSeparationExecutionHostEventPayload.Canceled -> {
                _workerStateFlow.value = SourceSeparationUiState.Canceled(
                    songId = recoveredSong.id,
                    songTitle = recoveredSong.title,
                )
                workerActivated = false
                clearPendingStart()
                cancelRequested.set(true)
                closeRecoveredTerminal(session)
                true
            }
            is SourceSeparationExecutionHostEventPayload.Failed -> {
                val message = payload.message
                _workerStateFlow.value = SourceSeparationUiState.Failed(
                    songId = recoveredSong.id,
                    songTitle = recoveredSong.title,
                    message = message,
                )
                workerActivated = false
                clearPendingStart()
                cancelRequested.set(true)
                closeRecoveredTerminal(session)
                true
            }
        }
    }

    private fun closeRecoveredTerminal(session: SourceSeparationReconnectedSession) {
        runCatching { session.closeTerminal() }
        if (reconnectedSession === session) {
            reconnectedSession = null
        }
    }

    private fun maybePauseRecoveredRunFor(request: SourceSeparationWorkerRequest) {
        val session = reconnectedSession ?: return
        val song = reconnectedSong ?: return
        if (request.song.id == song.id) {
            clearPendingStart()
            workerActivated = false
            return
        }
        if (request.priority >= SourceSeparationPendingStartReason.Manual.priority) {
            requestRecoveredControl(session, SourceSeparationRecoveredControl.Pause)
        }
    }

    private fun requestRecoveredControl(
        session: SourceSeparationReconnectedSession,
        control: SourceSeparationRecoveredControl,
    ) {
        workerScope.launch {
            runCatching {
                when (control) {
                    SourceSeparationRecoveredControl.Pause -> session.pause()
                    SourceSeparationRecoveredControl.Cancel -> session.cancel()
                }
            }
        }
    }

    private fun dispatchRecoveredTerminal(terminal: SourceSeparationRecoveredTerminal) {
        val callback = synchronized(callbackLock) {
            callbacks ?: run {
                pendingRecoveredTerminal = terminal
                null
            }
        }
        callback?.let(terminal::dispatch)
    }

    private fun Song.callbackSong(): Song {
        return _playbackStateFlow.value.song.takeIf { current ->
            current != Song.emptySong && current.id == id
        } ?: this
    }

    private suspend fun runWorkerLoop() {
        val activeJob = coroutineContext[Job]
        try {
            while (activeJob?.isActive == true && !cancelRequested.get()) {
                val request = nextWorkerRequest()
                if (request == null) {
                    workerSongId = null
                    if (!workerActivated) break
                    delay(SOURCE_SEPARATION_FOREGROUND_WORKER_IDLE_MS)
                    continue
                }

                clearPendingStart()
                activeWorkerRequest = request
                try {
                    if (!SourceSeparationPlaybackLifecyclePolicy
                            .canRunWithPlaybackOwnerState(
                                request.runClass,
                                playbackOwnerActive.get(),
                            )
                    ) {
                        continue
                    }
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
            workerSongId = null
            activeWorkerRequest = null
            activeWorkerSong = null
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
                reason = SourceSeparationPendingStartReason.PlaybackDemand,
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
        val decision = autoStartDecision(song, blend)
        if (decision.hasCompletedCache) {
            autoStartSuppressedSongId = song.id
        }
        return request.copy(song = song).takeIf {
            decision.shouldStart
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
        if (!playbackOwnerActive.get()) return
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
                            reason = SourceSeparationPendingStartReason.PlaybackDemand,
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
        val current = pendingStartRequest
        pendingStartRequest = if (current?.song?.id == request.song.id &&
            current.priority > request.priority
        ) {
            current
        } else {
            request
        }
    }

    private fun clearPendingStart() {
        pendingStartRequest = null
    }

    private fun ensureWorkerRunningIfActivated() {
        if (!workerActivated || workerJob?.isActive == true ||
            recoveryJob?.isActive == true || reconnectedSession != null
        ) {
            return
        }
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
                reason = SourceSeparationPendingStartReason.PlaybackDemand,
            )
        val preStartReadyWindowCount =
            (request as? SourceSeparationWorkerRequest.StartWindowPreStart)
                ?.readyWindowCount
                ?.coerceAtLeast(1)
        var preStartSatisfied = false
        val playbackOwnerLost = {
            !SourceSeparationPlaybackLifecyclePolicy.canRunWithPlaybackOwnerState(
                request.runClass,
                playbackOwnerActive.get(),
            )
        }
        pauseRequested.set(playbackOwnerLost())
        workerSongId = song.id
        var admittedSong: SourceSeparationRuntimeSong? = null
        try {
            val resolved = when (val resolution = sourceSeparationRuntime.resolve(
                song = song,
                shouldCancel = {
                    cancelRequested.get() || activeJob?.isActive != true
                },
            )) {
                is SourceSeparationRuntimeSongResolution.Ready -> resolution.song
                is SourceSeparationRuntimeSongResolution.Unavailable -> {
                    failUnavailableSong(song, resolution)
                    return
                }
            }
            admittedSong = resolved
            activeWorkerSong = resolved
            val shouldPromoteCompletedStems = preferences.getBoolean(
                SOURCE_SEPARATION_AUTO_FLAC_COMPRESSION,
                true,
            )
            if (hasCompletedCache(resolved)) {
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
            val tryGpu = preferences.readSourceSeparationGpuEnabled()
            val result = sourceSeparationRuntime.separate(
                song = resolved,
                tryGpu = tryGpu,
                runClass = request.runClass,
                onProgress = { progress ->
                    if (preStartReadyWindowCount != null &&
                        progress.scheduler?.playbackSegmentIndex == 0 &&
                        progress.scheduler.playbackReadyWindowReadyCount >=
                        preStartReadyWindowCount
                    ) {
                        preStartSatisfied = true
                    }
                    publishWorkerProgress(song, progress)
                },
                onPrepared = {
                    if (preStartReadyWindowCount == null &&
                        readBlendMode() == SourceSeparationBlendMode.PerSong
                    ) {
                        migrateTemporaryPerSongBlend(song, resolved)
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
                shouldPause = {
                    playbackOwnerLost() ||
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
            if (result is SourceSeparationModelAwareEngineResult.Busy) {
                _workerStateFlow.value = SourceSeparationUiState.Idle
                callbacks?.onSourceSeparationWorkerPaused(song)
                return
            }
            check(result !is SourceSeparationModelAwareEngineResult.ActiveModelUnavailable) {
                "A resolved source-separation run lost its active model binding."
            }
            val playbackState = _playbackStateFlow.value
            if (preStartReadyWindowCount == null &&
                readBlendMode() == SourceSeparationBlendMode.PerSong &&
                playbackState.song.id == song.id
            ) {
                savePerSongBlend(
                    song = song,
                    resolved = resolved,
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
                cacheKey = resolved.cacheKey,
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
        } catch (_: SourceSeparationAdmittedGpuRuntimeMismatchException) {
            val message = context.getString(R.string.source_separation_model_load_failed)
            _workerStateFlow.value = SourceSeparationUiState.Failed(
                songId = song.id,
                songTitle = song.title,
                message = message,
            )
            callbacks?.onSourceSeparationWorkerModelLoadFailed(message)
            workerActivated = false
            clearPendingStart()
            cancelRequested.set(true)
        } catch (_: SourceSeparationRemoteHostDiedException) {
            _workerStateFlow.value = SourceSeparationUiState.Failed(
                songId = song.id,
                songTitle = song.title,
                message = remoteProcessStoppedMessage(),
            )
            workerActivated = false
            clearPendingStart()
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
            if (activeWorkerSong === admittedSong) {
                activeWorkerSong = null
            }
            pauseRequested.set(false)
        }
    }

    private fun remoteProcessStoppedMessage(): String = context.getString(
        R.string.source_separation_process_stopped_unexpectedly,
    )

    private suspend fun hasCompletedCache(song: SourceSeparationRuntimeSong): Boolean {
        return withContext(Dispatchers.IO) {
            when (runCatching { sourceSeparationRuntime.cacheStatus(song) }.getOrNull()) {
                is SourceSeparationModelAwareCacheStatus.Completed -> true
                SourceSeparationModelAwareCacheStatus.Busy,
                is SourceSeparationModelAwareCacheStatus.Corrupt,
                is SourceSeparationModelAwareCacheStatus.Incomplete,
                SourceSeparationModelAwareCacheStatus.Missing,
                null -> false
            }
        }
    }

    private fun hasReadyPlaybackStartCache(song: Song, readyWindowCount: Int): Boolean {
        return runCatching {
            val resolved = resolveSong(song) ?: return@runCatching false
            when (val status = sourceSeparationRuntime.playableStatus(
                song = resolved,
                playbackPositionMs = 0L,
                readyWindowCount = readyWindowCount.coerceAtLeast(1),
            )) {
                is SourceSeparationModelAwarePlayableStatus.Ready -> {
                    status.playback.close()
                    true
                }
                SourceSeparationModelAwarePlayableStatus.Processing,
                SourceSeparationModelAwarePlayableStatus.Unavailable -> false
            }
        }.getOrDefault(false)
    }

    private fun resolveSong(song: Song): SourceSeparationRuntimeSong? {
        return when (val resolution = sourceSeparationRuntime.resolve(song)) {
            is SourceSeparationRuntimeSongResolution.Ready -> resolution.song
            is SourceSeparationRuntimeSongResolution.Unavailable -> null
        }
    }

    private fun failUnavailableSong(
        song: Song,
        resolution: SourceSeparationRuntimeSongResolution.Unavailable,
    ) {
        val message = when (resolution.reason) {
            SourceSeparationRuntimeUnavailableReason.NoSong ->
                context.getString(R.string.source_separation_playback_no_song)
            SourceSeparationRuntimeUnavailableReason.NoSelection,
            SourceSeparationRuntimeUnavailableReason.PendingSelection,
            SourceSeparationRuntimeUnavailableReason.ModelNotInstalled,
            SourceSeparationRuntimeUnavailableReason.ProfileNotInstalled ->
                context.getString(R.string.source_separation_model_missing)
            SourceSeparationRuntimeUnavailableReason.ModelIdentityMismatch,
            SourceSeparationRuntimeUnavailableReason.ContractMismatch,
            SourceSeparationRuntimeUnavailableReason.ContractInvalid,
            SourceSeparationRuntimeUnavailableReason.RuntimeUnsupported ->
                context.getString(R.string.source_separation_model_load_failed)
            SourceSeparationRuntimeUnavailableReason.SourceUnavailable ->
                context.getString(R.string.source_separation_failed)
        }
        _workerStateFlow.value = SourceSeparationUiState.Failed(
            songId = song.id,
            songTitle = song.title,
            message = message,
        )
        if (resolution.reason != SourceSeparationRuntimeUnavailableReason.SourceUnavailable &&
            resolution.reason != SourceSeparationRuntimeUnavailableReason.NoSong
        ) {
            callbacks?.onSourceSeparationWorkerModelLoadFailed(message)
        }
        workerActivated = false
        clearPendingStart()
        cancelRequested.set(true)
    }

    private fun publishWorkerProgress(song: Song, progress: MdxRangeProgress) {
        val averageWindowMs = progress.completedWindowElapsedMs
            ?.let(performanceStats::recordWindowElapsed)
            ?: performanceStats.averageWindowMs()
        recordDebugWindowSample(song, progress)
        _workerStateFlow.value = SourceSeparationUiState.Running(
            songId = song.id,
            songTitle = song.title,
            completedWindows = progress.completedWindows,
            totalWindows = progress.totalWindows,
            percent = progress.percent,
            stage = progress.stage,
            sourceDecodeDiagnostics = progress.sourceDecodeDiagnostics?.toDisplayText(),
            sourceDecodeMode = progress.sourceDecodeDiagnostics?.mode?.toUiState(),
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
        callbacks?.onSourceSeparationWorkerProgress(song.callbackSong())
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
            sourceSeparationRuntime.prune(
                partialLimit = preferences.getInt(
                    SOURCE_SEPARATION_AUTO_CACHE_CLEANUP_PARTIAL_LIMIT,
                    DEFAULT_SOURCE_SEPARATION_AUTO_CACHE_CLEANUP_PARTIAL_LIMIT,
                ).coerceAtLeast(1),
                completedLimit = preferences.getInt(
                    SOURCE_SEPARATION_AUTO_CACHE_CLEANUP_COMPLETED_LIMIT,
                    DEFAULT_SOURCE_SEPARATION_AUTO_CACHE_CLEANUP_COMPLETED_LIMIT,
                ).coerceAtLeast(1),
                protectedCacheKeys = protectedCacheKeys(),
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
            resolveSong(song)?.let(sourceSeparationRuntime::readBlend)
        }.getOrNull()
            ?: readTemporaryPerSongBlend(song)
    }

    private fun savePerSongBlend(song: Song, blend: Float): Boolean {
        val resolved = resolveSong(song) ?: return false
        return savePerSongBlend(song, resolved, blend)
    }

    private fun savePerSongBlend(
        song: Song,
        resolved: SourceSeparationRuntimeSong,
        blend: Float,
    ): Boolean {
        val saved = runCatching {
            sourceSeparationRuntime.writeBlend(resolved, blend.coerceIn(0f, 1f))
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

    private fun migrateTemporaryPerSongBlend(
        song: Song,
        resolved: SourceSeparationRuntimeSong,
    ): Boolean {
        val blend = readTemporaryPerSongBlend(song) ?: return false
        return savePerSongBlend(song, resolved, blend)
    }

    private fun migrateTemporaryPerSongBlend(song: Song, blend: Float): Boolean {
        val saved = runCatching {
            resolveSong(song)?.let { resolved ->
                sourceSeparationRuntime.writeBlend(resolved, blend.coerceIn(0f, 1f))
            } ?: false
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

private enum class SourceSeparationPendingStartReason(val priority: Int) {
    Manual(priority = 2),
    PlaybackDemand(priority = 1),
}

private sealed interface SourceSeparationWorkerRequest {
    val song: Song
    val debugReason: String
    val runClass: SourceSeparationExecutionRunClass
    val priority: Int

    data class Full(
        override val song: Song,
        val reason: SourceSeparationPendingStartReason,
    ) : SourceSeparationWorkerRequest {
        override val debugReason: String = reason.name
        override val runClass: SourceSeparationExecutionRunClass = when (reason) {
            SourceSeparationPendingStartReason.Manual ->
                SourceSeparationExecutionRunClass.ManualFullSong
            SourceSeparationPendingStartReason.PlaybackDemand ->
                SourceSeparationExecutionRunClass.PlaybackDemandWindow
        }
        override val priority: Int = reason.priority
    }

    data class StartWindowPreStart(
        override val song: Song,
        val readyWindowCount: Int,
    ) : SourceSeparationWorkerRequest {
        override val debugReason: String = "PreStart($readyWindowCount)"
        override val runClass = SourceSeparationExecutionRunClass.NextSongPrefetch
        override val priority: Int = 0
    }
}

private enum class SourceSeparationRecoveredControl {
    Pause,
    Cancel,
}

private sealed interface SourceSeparationRecoveredTerminal {
    fun dispatch(callbacks: SourceSeparationForegroundWorkerCallbacks) {
        when (this) {
            is Completed -> callbacks.onSourceSeparationWorkerCompleted(
                song = song,
                cacheKey = cacheKey,
                shouldPromoteCompletedStems = shouldPromoteCompletedStems,
            )
            is Paused -> callbacks.onSourceSeparationWorkerPaused(song)
        }
    }

    data class Completed(
        val song: Song,
        val cacheKey: String,
        val shouldPromoteCompletedStems: Boolean,
    ) : SourceSeparationRecoveredTerminal

    data class Paused(val song: Song) : SourceSeparationRecoveredTerminal

}

private fun SourceSeparationCacheRunJournal.toRecoveredSong(): Song {
    val locator = request.song
    val diagnostics = request.sourceDiagnostics
    return Song(
        id = locator.songId,
        data = locator.filePath,
        title = locator.title,
        trackNumber = 0,
        year = 0,
        size = diagnostics.fileSize,
        duration = diagnostics.durationMs,
        dateAdded = 0L,
        rawDateModified = diagnostics.rawDateModified,
        albumId = 0L,
        albumName = locator.album,
        artistId = 0L,
        artistName = locator.artist,
        albumArtistName = null,
        genreName = null,
    )
}

interface SourceSeparationForegroundWorkerCallbacks {
    fun onSourceSeparationWorkerProgress(song: Song)
    fun onSourceSeparationWorkerPrepared(song: Song)
    fun onSourceSeparationWorkerCompleted(
        song: Song,
        cacheKey: String,
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
