package com.mardous.booming.ui.screen.player

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.core.content.edit
import com.mardous.booming.R
import com.mardous.booming.data.model.Song
import com.mardous.booming.separation.SourceSeparationAdmittedGpuRuntimeMismatchException
import com.mardous.booming.separation.SourceSeparationBlendDemand
import com.mardous.booming.separation.SourceSeparationExecutionRunClass
import com.mardous.booming.separation.SourceSeparationModelAwareEngineResult
import com.mardous.booming.separation.SourceSeparationMixModelKey
import com.mardous.booming.separation.SourceSeparationModelMixSettingsStore
import com.mardous.booming.separation.SourceSeparationMultiStemPlaybackSelectionSnapshot
import com.mardous.booming.separation.SourceSeparationPausedException
import com.mardous.booming.separation.SourceSeparationPauseReason
import com.mardous.booming.separation.SourceSeparationPerformanceStats
import com.mardous.booming.separation.SourceSeparationRuntimeFacade
import com.mardous.booming.separation.SourceSeparationRuntimeSong
import com.mardous.booming.separation.SourceSeparationRuntimeSongResolution
import com.mardous.booming.separation.SourceSeparationRuntimeUnavailableReason
import com.mardous.booming.separation.toMixModelKey
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheRunJournal
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwareCacheStatus
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwarePlayableStatus
import com.mardous.booming.separation.lifecycle.SourceSeparationLifecycleTrace
import com.mardous.booming.separation.model.MdxRangeProgress
import com.mardous.booming.separation.model.MdxSourceDecodeMode
import com.mardous.booming.separation.model.preset.SourceSeparationActiveModelReference
import com.mardous.booming.separation.model.preset.SourceSeparationActiveSelectionSnapshot
import com.mardous.booming.separation.process.SourceSeparationExecutionHostEvent
import com.mardous.booming.separation.process.SourceSeparationExecutionHostEventPayload
import com.mardous.booming.separation.process.SourceSeparationMultiStemExecutionEvent
import com.mardous.booming.separation.process.SourceSeparationMultiStemExecutionEventPayload
import com.mardous.booming.separation.process.SourceSeparationPlaybackLifecyclePolicy
import com.mardous.booming.separation.process.ipc.SourceSeparationIndependentRunRecovery
import com.mardous.booming.separation.process.ipc.SourceSeparationMultiStemIndependentRunRecovery
import com.mardous.booming.separation.process.ipc.SourceSeparationMultiStemReconnectedSession
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

class SourceSeparationForegroundWorkerCoordinator internal constructor(
    private val context: Context,
    private val preferences: SharedPreferences,
    private val sourceSeparationRuntime: SourceSeparationRuntimeFacade,
    private val sourceSeparationMixSettings: SourceSeparationModelMixSettingsStore =
        SourceSeparationModelMixSettingsStore(preferences),
    private val independentRunRecovery: SourceSeparationIndependentRunRecovery? = null,
    private val multiStemIndependentRunRecovery:
        SourceSeparationMultiStemIndependentRunRecovery? = null,
    private val activeSelectionFlow: StateFlow<SourceSeparationActiveSelectionSnapshot> =
        MutableStateFlow(SourceSeparationActiveSelectionSnapshot(null, 0L)),
    private val multiStemSelectionFlow:
        StateFlow<SourceSeparationMultiStemPlaybackSelectionSnapshot> = MutableStateFlow(
            SourceSeparationMultiStemPlaybackSelectionSnapshot(null, 0L),
        ),
) {
    private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val performanceStats = SourceSeparationPerformanceStats(preferences)
    private val cancelRequested = AtomicBoolean(false)
    private val pauseRequested = AtomicBoolean(false)
    private val pauseReasonOverride = AtomicReference<SourceSeparationPauseReason?>(null)
    private val playbackOwnerActive = AtomicBoolean(true)
    private val requestGeneration = AtomicLong()
    private val debugWindowSamples = ArrayDeque<SourceSeparationDebugWindowSample>()
    private val stateLock = Any()
    private val automaticPruneRequests = Channel<Unit>(Channel.CONFLATED)

    private var workerJob: Job? = null
    private var workerActivated = false
    private var pendingStartRequest: SourceSeparationWorkerRequest? = null
    private var activeWorkerRequest: SourceSeparationWorkerRequest? = null
    private var activeWorkerSong: SourceSeparationRuntimeSong? = null
    private var recoveryJob: Job? = null
    private var multiStemRecoveryJob: Job? = null
    @Volatile
    private var reconnectedSession: SourceSeparationReconnectedSession? = null
    @Volatile
    private var reconnectedSong: Song? = null
    private var reconnectedSelection: SourceSeparationActiveSelectionSnapshot? = null
    @Volatile
    private var reconnectedLatestSequence = 0L
    @Volatile
    private var multiStemReconnectedSession: SourceSeparationMultiStemReconnectedSession? = null
    @Volatile
    private var multiStemReconnectedSong: Song? = null
    private var multiStemReconnectedSelection:
        SourceSeparationMultiStemPlaybackSelectionSnapshot? = null
    @Volatile
    private var multiStemReconnectedLatestSequence = 0L
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
    val activeSelectionStateFlow: StateFlow<SourceSeparationActiveSelectionSnapshot>
        get() = activeSelectionFlow

    private val _eventFlow =
        MutableSharedFlow<SourceSeparationForegroundPlaybackEvent>(
            extraBufferCapacity = 32,
        )
    val eventFlow = _eventFlow.asSharedFlow()

    init {
        trace("init recovery=${independentRunRecovery != null}")
        observeActiveSelection()
        observeMultiStemSelection()
        observeAutomaticPruneRequests()
        independentRunRecovery?.let(::startIndependentRunRecovery)
        multiStemIndependentRunRecovery?.let(::startMultiStemIndependentRunRecovery)
    }

    private fun observeMultiStemSelection() {
        var observed = multiStemSelectionFlow.value
        workerScope.launch {
            multiStemSelectionFlow.collect { selection ->
                if (selection == observed) return@collect
                observed = selection
                multiStemReconnectedSession
                    ?.takeIf { multiStemReconnectedSelection != selection }
                    ?.let { session ->
                        requestMultiStemRecoveredControl(
                            session,
                            SourceSeparationRecoveredControl.Pause,
                            SourceSeparationPauseReason.ActiveModelSuperseded,
                        )
                    }
            }
        }
    }

    private fun observeActiveSelection() {
        var observed = activeSelectionFlow.value
        workerScope.launch {
            activeSelectionFlow.collect { selection ->
                if (selection == observed) return@collect
                val previous = observed
                observed = selection
                val recoveredToPause = synchronized(stateLock) {
                    if (pendingStartRequest?.selection != selection) {
                        pendingStartRequest = null
                    }
                    if (activeWorkerRequest?.selection != selection) {
                        pauseRequested.set(true)
                    }
                    reconnectedSession.takeIf { reconnectedSelection != selection }
                }
                val state = _workerStateFlow.value
                if (state.selectionGenerationOrNull() != null &&
                    state.selectionGenerationOrNull() != selection.generation
                ) {
                    _workerStateFlow.value = SourceSeparationUiState.Idle
                }
                recoveredToPause?.let { session ->
                    requestRecoveredControl(
                        session = session,
                        control = SourceSeparationRecoveredControl.Pause,
                        pauseReason = SourceSeparationPauseReason.ActiveModelSuperseded,
                    )
                }
                trace(
                    SourceSeparationLifecycleTrace.format(
                        event = "selection.changed",
                        selectionGeneration = selection.generation,
                    ) + " previousGeneration=${previous.generation}",
                )
            }
        }
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
        val shouldPause = synchronized(stateLock) {
            workerActivated = false
            pendingStartRequest = null
            SourceSeparationPlaybackLifecyclePolicy.shouldPauseWhenPlaybackStops(
                activeWorkerRequest?.runClass,
            )
        }
        if (shouldPause) {
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
        terminal?.let { recoveredTerminal ->
            dispatchCallbackSafely("recoveredTerminal", callbacks) {
                recoveredTerminal.dispatch(it)
            }
        }
    }

    fun detachCallbacks(callbacks: SourceSeparationForegroundWorkerCallbacks) {
        synchronized(callbackLock) {
            if (this.callbacks === callbacks) {
                this.callbacks = null
            }
        }
    }

    private inline fun notifyCallbacks(
        event: String,
        dispatch: (SourceSeparationForegroundWorkerCallbacks) -> Unit,
    ) {
        val callback = callbacks ?: return
        dispatchCallbackSafely(event, callback, dispatch)
    }

    private inline fun dispatchCallbackSafely(
        event: String,
        callback: SourceSeparationForegroundWorkerCallbacks,
        dispatch: (SourceSeparationForegroundWorkerCallbacks) -> Unit,
    ) {
        try {
            dispatch(callback)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e(TAG, "callback.$event failed", error)
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
        trace("requestManual song=${song.id}")
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
        trace(
            "requestFull song=${song.id} reason=${reason.name} " +
                    "recovery=${recoveryJob?.isActive == true} " +
                    "reconnected=${reconnectedSession != null || multiStemReconnectedSession != null} " +
                    "worker=${workerJob?.isActive == true} " +
                    "workerSong=${activeWorkerRequest?.song?.id} " +
                    "pending=${pendingStartRequest?.song?.id}",
        )
        val incoming = newFullRequest(song, reason)
        val action = synchronized(stateLock) {
            workerActivated = true
            autoStartSuppressedSongId = null
            when {
                recoveryJob?.isActive == true || multiStemRecoveryJob?.isActive == true ||
                    reconnectedSession != null || multiStemReconnectedSession != null -> {
                    setPendingStartLocked(incoming)
                    SourceSeparationRequestAction.PauseRecovered
                }
                workerJob?.isActive == true -> {
                    val active = activeWorkerRequest
                    when {
                        active?.identity == incoming.identity &&
                            incoming.priority > active.priority -> {
                            setPendingStartLocked(incoming)
                            pauseRequested.set(true)
                        }
                        active?.identity == incoming.identity &&
                            incoming.priority < active.priority -> Unit
                        active?.identity == incoming.identity -> {
                            pauseRequested.set(false)
                            val workerState = _workerStateFlow.value
                            if (workerState !is SourceSeparationUiState.Running) {
                                setPendingStartLocked(incoming)
                            }
                            if (workerState is SourceSeparationUiState.Paused ||
                                workerState is SourceSeparationUiState.Idle
                            ) {
                                _workerStateFlow.value = SourceSeparationUiState.Running(
                                    songId = song.id,
                                    songTitle = song.title,
                                    selectionGeneration = incoming.selection.generation,
                                    cacheKey = activeWorkerSong?.cacheKey,
                                )
                            }
                        }
                        else -> {
                            setPendingStartLocked(incoming)
                            if (active != null) pauseRequested.set(true)
                        }
                    }
                    SourceSeparationRequestAction.None
                }
                else -> {
                    cancelRequested.set(false)
                    pauseRequested.set(false)
                    setPendingStartLocked(incoming)
                    SourceSeparationRequestAction.EnsureWorker
                }
            }
        }
        when (action) {
            SourceSeparationRequestAction.PauseRecovered -> {
                maybePauseRecoveredRunFor(incoming)
                multiStemReconnectedSession?.let { session ->
                    requestMultiStemRecoveredControl(
                        session,
                        SourceSeparationRecoveredControl.Pause,
                    )
                }
            }
            SourceSeparationRequestAction.EnsureWorker ->
                ensureWorkerRunningIfActivated()
            SourceSeparationRequestAction.None -> Unit
        }
    }

    suspend fun preStartSong(song: Song, readyWindowCount: Int): Boolean {
        if (!playbackOwnerActive.get()) return false
        if (song == Song.emptySong || readyWindowCount <= 0) return false
        val multiStemSelection = multiStemSelectionFlow.value
        val request = newPreStartRequest(
            song = song,
            readyWindowCount = readyWindowCount,
        )
        if (synchronized(stateLock) {
                activeWorkerRequest?.identity == request.identity
            }
        ) return false
        if (withContext(Dispatchers.IO) {
                hasReadyPlaybackStartCache(song, readyWindowCount)
            }
        ) return false
        if (!playbackOwnerActive.get() ||
            requestGeneration.get() != request.requestGeneration ||
            activeSelectionFlow.value != request.selection ||
            multiStemSelectionFlow.value != multiStemSelection
        ) return false

        val action = synchronized(stateLock) {
            if (!playbackOwnerActive.get() ||
                requestGeneration.get() != request.requestGeneration ||
                activeSelectionFlow.value != request.selection ||
                multiStemSelectionFlow.value != multiStemSelection ||
                activeWorkerRequest?.identity == request.identity
            ) {
                return false
            }
            workerActivated = true
            setPendingStartLocked(request)
            when {
                recoveryJob?.isActive == true || multiStemRecoveryJob?.isActive == true ||
                    reconnectedSession != null || multiStemReconnectedSession != null ->
                    SourceSeparationRequestAction.None
                workerJob?.isActive == true -> SourceSeparationRequestAction.None
                else -> SourceSeparationRequestAction.EnsureWorker
            }
        }
        if (action == SourceSeparationRequestAction.EnsureWorker) {
            cancelRequested.set(false)
            pauseRequested.set(false)
            ensureWorkerRunningIfActivated()
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
                selectionGeneration = activeSelectionFlow.value.generation,
                cacheKey = recovered.cacheKey,
            )
            requestRecoveredControl(recovered, SourceSeparationRecoveredControl.Pause)
        }
        val multiStemRecovered = multiStemReconnectedSession
        val multiStemSong = multiStemReconnectedSong
        if (multiStemRecovered != null && multiStemSong != null) {
            _workerStateFlow.value = SourceSeparationUiState.Paused(
                songId = multiStemSong.id,
                songTitle = multiStemSong.title,
                selectionGeneration = multiStemReconnectedSelection?.generation,
                cacheKey = multiStemRecovered.cacheKey,
            )
            requestMultiStemRecoveredControl(
                multiStemRecovered,
                SourceSeparationRecoveredControl.Pause,
            )
        }
        if (workerJob?.isActive == true) {
            _workerStateFlow.value = SourceSeparationUiState.Paused(
                songId = currentSong.id,
                songTitle = currentSong.title,
                selectionGeneration = activeWorkerRequest?.selection?.generation,
                cacheKey = activeWorkerSong?.cacheKey,
            )
        }
        autoStartSuppressedSongId = currentSong
            .takeIf { it != Song.emptySong }
            ?.id
        clearPendingStart()
        pauseRequested.set(true)
    }

    fun pauseForActiveModelSupersession() {
        pauseReasonOverride.set(SourceSeparationPauseReason.ActiveModelSuperseded)
        synchronized(stateLock) {
            pendingStartRequest = null
        }
        pauseRequested.set(true)
        reconnectedSession?.let { session ->
            requestRecoveredControl(
                session = session,
                control = SourceSeparationRecoveredControl.Pause,
                pauseReason = SourceSeparationPauseReason.ActiveModelSuperseded,
            )
        }
        multiStemReconnectedSession?.let { session ->
            requestMultiStemRecoveredControl(
                session,
                SourceSeparationRecoveredControl.Pause,
                SourceSeparationPauseReason.ActiveModelSuperseded,
            )
        }
    }

    fun cancel() {
        val job = synchronized(stateLock) {
            workerActivated = false
            pendingStartRequest = null
            workerJob
        }
        cancelRequested.set(true)
        reconnectedSession?.let { session ->
            requestRecoveredControl(session, SourceSeparationRecoveredControl.Cancel)
        }
        multiStemReconnectedSession?.let { session ->
            requestMultiStemRecoveredControl(session, SourceSeparationRecoveredControl.Cancel)
        }
        job?.cancel()
    }

    internal suspend fun cancelForCacheDeletion(
        cacheKey: String,
        preflightIdentity: SourceSeparationWorkerRequestIdentity?,
    ): Boolean {
        val target = synchronized(stateLock) {
            val pendingMatches = preflightIdentity != null &&
                pendingStartRequest?.identity == preflightIdentity
            if (pendingMatches) {
                pendingStartRequest = null
            }
            val activeMatches = activeWorkerSong?.cacheKey == cacheKey ||
                (activeWorkerSong == null && preflightIdentity != null &&
                    activeWorkerRequest?.identity == preflightIdentity)
            val recovered = reconnectedSession?.takeIf { it.cacheKey == cacheKey }
            val multiStemRecovered = multiStemReconnectedSession
                ?.takeIf { it.cacheKey == cacheKey }
            val job = workerJob.takeIf { activeMatches }
            if (!pendingMatches && job == null && recovered == null &&
                multiStemRecovered == null
            ) {
                null
            } else {
                SourceSeparationCacheDeletionCancellation(
                    job = job,
                    recovered = recovered,
                    multiStemRecovered = multiStemRecovered,
                    preflightIdentity = preflightIdentity,
                )
            }
        } ?: return false

        if (target.job != null) {
            cancelRequested.set(true)
            target.job.invokeOnCompletion { ensureWorkerRunningIfActivated() }
            target.job.cancel()
        }
        target.recovered?.let { session ->
            requestRecoveredControl(session, SourceSeparationRecoveredControl.Cancel)
        }
        target.multiStemRecovered?.let { session ->
            requestMultiStemRecoveredControl(session, SourceSeparationRecoveredControl.Cancel)
        }
        while (synchronized(stateLock) {
                activeWorkerSong?.cacheKey == cacheKey ||
                    reconnectedSession?.cacheKey == cacheKey ||
                    multiStemReconnectedSession?.cacheKey == cacheKey ||
                    (activeWorkerSong == null && target.preflightIdentity != null &&
                        activeWorkerRequest?.identity == target.preflightIdentity)
            }
        ) {
            delay(SOURCE_SEPARATION_FOREGROUND_WORKER_LEAVE_SONG_WAIT_MS)
        }
        return true
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
        multiStemReconnectedSession
            ?.takeIf { multiStemReconnectedSong?.id == songId }
            ?.let { session ->
                requestMultiStemRecoveredControl(
                    session,
                    SourceSeparationRecoveredControl.Pause,
                )
            }
    }

    fun clearAutoStartSuppressionForSong(songId: Long) {
        if (autoStartSuppressedSongId == songId) {
            autoStartSuppressedSongId = null
        }
    }

    suspend fun waitForWorkerToLeaveSong(songId: Long) {
        // Job cancellation makes isActive false before runWorkerSong finishes
        // releasing the remote execution and cache lease.
        while (synchronized(stateLock) {
                activeWorkerSong?.song?.id == songId ||
                    activeWorkerRequest?.song?.id == songId ||
                    (reconnectedSong?.id == songId && reconnectedSession != null)
                    || (multiStemReconnectedSong?.id == songId &&
                        multiStemReconnectedSession != null)
            }
        ) {
            delay(SOURCE_SEPARATION_FOREGROUND_WORKER_LEAVE_SONG_WAIT_MS)
        }
    }

    fun clearStatusIfNotRunning() {
        if (_workerStateFlow.value !is SourceSeparationUiState.Running) {
            _workerStateFlow.value = SourceSeparationUiState.Idle
        }
    }

    fun isWorkerActive(): Boolean = synchronized(stateLock) {
        workerJob?.isActive == true || recoveryJob?.isActive == true ||
            multiStemRecoveryJob?.isActive == true || reconnectedSession != null ||
            multiStemReconnectedSession != null
    }

    fun runningSongId(): Long? = synchronized(stateLock) {
        reconnectedSong?.id ?: multiStemReconnectedSong?.id ?: activeWorkerRequest?.song?.id
    }

    fun runningCacheKey(): String? = synchronized(stateLock) {
        reconnectedSession?.cacheKey ?: multiStemReconnectedSession?.cacheKey ?:
            activeWorkerSong?.cacheKey
    }

    fun pendingSongId(): Long? = synchronized(stateLock) {
        pendingStartRequest?.song?.id
    }

    fun protectedCacheKeys(): Set<String> = synchronized(stateLock) {
        setOfNotNull(
            activeWorkerSong?.cacheKey,
            reconnectedSession?.cacheKey,
            multiStemReconnectedSession?.cacheKey,
        )
    }

    fun requestAutomaticPrune() {
        automaticPruneRequests.trySend(Unit)
    }

    fun isModelArtifactInUse(artifactSha256: String): Boolean = synchronized(stateLock) {
        val normalized = artifactSha256.lowercase()
        activeWorkerRequest?.selection?.reference?.artifactSha256?.lowercase() == normalized ||
            pendingStartRequest?.selection?.reference?.artifactSha256?.lowercase() == normalized ||
            activeWorkerSong?.artifactSha256?.lowercase() == normalized ||
            reconnectedSession?.journal?.request?.identity?.artifactSha256?.lowercase() == normalized
            || multiStemReconnectedSession?.journal?.request?.identity?.artifactSha256
                ?.lowercase() == normalized
    }

    suspend fun autoStartDecision(
        song: Song,
        blend: Float,
    ): SourceSeparationAutoStartDecision {
        if (!isAutoStartEnabled() ||
            readBlendMode() == SourceSeparationBlendMode.Off ||
            song == Song.emptySong ||
            SourceSeparationBlendDemand.isCentered(blend)
        ) {
            return SourceSeparationAutoStartDecision(
                shouldStart = false,
                shouldWaitForProcessingCache = false,
                hasCompletedCache = false,
            )
        }
        val selection = activeSelectionFlow.value
        if (activeWorkerRequest?.identity?.matches(song, selection) == true ||
            pendingStartRequest?.identity?.matches(song, selection) == true
        ) {
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
            SourceSeparationBlendMode.Off ->
                fallbackBlend ?: SourceSeparationBlendDemand.CENTER_BLEND
            SourceSeparationBlendMode.Global -> {
                fallbackBlend?.takeIf { trustFallbackBlend } ?: readGlobalBlend()
            }
            SourceSeparationBlendMode.PerSong -> {
                fallbackBlend?.takeIf { trustFallbackBlend } ?: withContext(Dispatchers.IO) {
                    val resolved = resolveSong(song)
                    val pending = readTemporaryPerSongBlend(
                        song,
                        resolved?.toMixModelKey() ?: currentMixModelKey(),
                    )
                    if (pending != null) {
                        if (resolved != null) savePerSongBlend(song, resolved, pending)
                        pending
                    } else {
                        resolved?.let { runtimeSong ->
                            runCatching {
                                sourceSeparationRuntime.readBlend(runtimeSong)
                            }.getOrNull()
                        } ?: SourceSeparationBlendDemand.CENTER_BLEND
                    }
                }
            }
        }.coerceIn(0f, 1f)
    }

    suspend fun recordedBlendForSong(song: Song): Float? {
        if (song == Song.emptySong) return null
        return withContext(Dispatchers.IO) {
            val resolved = resolveSong(song)
            readTemporaryPerSongBlend(
                song,
                resolved?.toMixModelKey() ?: currentMixModelKey(),
            ) ?: runCatching {
                resolved?.let(sourceSeparationRuntime::readBlend)
            }.getOrNull()
        }?.coerceIn(0f, 1f)
    }

    fun debugStatus(): String {
        val playbackState = _playbackStateFlow.value
        val state = _workerStateFlow.value
        return buildString {
            append("currentSong=").append(playbackState.song.id)
            append(" title=").append(playbackState.song.title)
            append(" workerActive=").append(workerJob?.isActive == true)
            append(" workerSong=").append(activeWorkerRequest?.song?.id)
            append(" recoveryActive=").append(recoveryJob?.isActive == true)
            append(" multiStemRecoveryActive=").append(multiStemRecoveryJob?.isActive == true)
            append(" reconnectedSong=").append(reconnectedSong?.id)
            append(" reconnectedRun=").append(reconnectedSession?.runId)
            append(" multiStemReconnectedSong=").append(multiStemReconnectedSong?.id)
            append(" multiStemReconnectedRun=").append(multiStemReconnectedSession?.runId)
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

    private fun startMultiStemIndependentRunRecovery(
        recovery: SourceSeparationMultiStemIndependentRunRecovery,
    ) {
        val job = workerScope.launch(start = CoroutineStart.LAZY) {
            runMultiStemIndependentRunRecovery(recovery)
        }
        multiStemRecoveryJob = job
        job.start()
    }

    private suspend fun runMultiStemIndependentRunRecovery(
        recovery: SourceSeparationMultiStemIndependentRunRecovery,
    ) {
        val activeJob = coroutineContext[Job]
        val events = Channel<SourceSeparationMultiStemExecutionEvent>(Channel.UNLIMITED)
        var session: SourceSeparationMultiStemReconnectedSession? = null
        var terminal = false
        try {
            val selection = multiStemSelectionFlow.value
            val reconnected = recovery.reconnect(selection) { event -> events.trySend(event) }
                ?: return
            session = reconnected
            if (multiStemSelectionFlow.value != selection ||
                session.journal.request.identity.modelId != selection.modelId
            ) {
                runCatching {
                    session.pause(SourceSeparationPauseReason.ActiveModelSuperseded)
                }
                return
            }
            synchronized(stateLock) {
                multiStemReconnectedSession = session
                multiStemReconnectedSong = session.journal.toRecoveredSong()
                multiStemReconnectedSelection = selection
                multiStemReconnectedLatestSequence = 0L
            }
            terminal = applyMultiStemRecoveredEvent(session, session.baselineEvent)
            if (!terminal) {
                when {
                    cancelRequested.get() -> requestMultiStemRecoveredControl(
                        session,
                        SourceSeparationRecoveredControl.Cancel,
                    )
                    pauseRequested.get() -> requestMultiStemRecoveredControl(
                        session,
                        SourceSeparationRecoveredControl.Pause,
                    )
                }
            }
            while (!terminal && activeJob?.isActive == true) {
                val event = events.receiveCatching().getOrNull() ?: break
                terminal = applyMultiStemRecoveredEvent(session, event)
            }
        } catch (error: Throwable) {
            val song = multiStemReconnectedSong
            if (song != null && error !is CancellationException) {
                _workerStateFlow.value = SourceSeparationUiState.Failed(
                    songId = song.id,
                    songTitle = song.title,
                    message = error.message,
                )
            }
        } finally {
            events.close()
            if (!terminal) runCatching { session?.close() }
            if (multiStemReconnectedSession === session ||
                multiStemReconnectedSession == null
            ) {
                multiStemReconnectedSession = null
                multiStemReconnectedSong = null
                multiStemReconnectedSelection = null
                multiStemReconnectedLatestSequence = 0L
            }
            if (multiStemRecoveryJob == activeJob) multiStemRecoveryJob = null
            cancelRequested.set(false)
            pauseRequested.set(false)
            if (pendingStartRequest != null) ensureWorkerRunningIfActivated()
        }
    }

    private fun applyMultiStemRecoveredEvent(
        session: SourceSeparationMultiStemReconnectedSession,
        event: SourceSeparationMultiStemExecutionEvent,
    ): Boolean {
        val selection = multiStemReconnectedSelection
        if (selection != null && multiStemSelectionFlow.value != selection) {
            runCatching {
                session.pause(SourceSeparationPauseReason.ActiveModelSuperseded)
            }
            runCatching { session.close() }
            multiStemReconnectedSession = null
            return true
        }
        require(event.runId == session.runId &&
            event.processGeneration == session.processGeneration
        ) { "Recovered multi-stem event has a stale identity." }
        if (event.sequence <= multiStemReconnectedLatestSequence) return false
        multiStemReconnectedLatestSequence = event.sequence
        val song = requireNotNull(multiStemReconnectedSong)
        val callbackSong = song.callbackSong()
        return when (val payload = event.payload) {
            is SourceSeparationMultiStemExecutionEventPayload.Accepted -> {
                require(payload.descriptor.cacheKey == session.cacheKey)
                _workerStateFlow.value = SourceSeparationUiState.Running(
                    songId = song.id,
                    songTitle = song.title,
                    selectionGeneration = selection?.generation,
                    cacheKey = session.cacheKey,
                )
                false
            }
            is SourceSeparationMultiStemExecutionEventPayload.Progress -> {
                publishWorkerProgress(
                    song = song,
                    progress = MdxRangeProgress(
                        completedWindows = payload.completedWindows,
                        totalWindows = payload.totalWindows,
                        stage = payload.stage,
                    ),
                    selectionGeneration = selection?.generation,
                    cacheKey = session.cacheKey,
                )
                false
            }
            is SourceSeparationMultiStemExecutionEventPayload.Prepared -> {
                notifyCallbacks("multiStemPrepared") {
                    it.onSourceSeparationWorkerPrepared(callbackSong)
                }
                false
            }
            is SourceSeparationMultiStemExecutionEventPayload.SegmentStateChanged -> false
            is SourceSeparationMultiStemExecutionEventPayload.Completed,
            is SourceSeparationMultiStemExecutionEventPayload.AlreadyCompleted,
            -> {
                _workerStateFlow.value = SourceSeparationUiState.Completed(
                    songId = song.id,
                    songTitle = song.title,
                    selectionGeneration = selection?.generation,
                    cacheKey = session.cacheKey,
                )
                requestAutomaticPrune()
                runCatching { session.close() }
                multiStemReconnectedSession = null
                dispatchRecoveredTerminal(SourceSeparationRecoveredTerminal.Completed(
                    song = callbackSong,
                    cacheKey = session.cacheKey,
                    shouldPromoteCompletedStems = preferences.getBoolean(
                        SOURCE_SEPARATION_AUTO_FLAC_COMPRESSION,
                        true,
                    ),
                ))
                true
            }
            is SourceSeparationMultiStemExecutionEventPayload.Paused -> {
                _workerStateFlow.value = SourceSeparationUiState.Idle
                runCatching { session.close() }
                multiStemReconnectedSession = null
                dispatchRecoveredTerminal(SourceSeparationRecoveredTerminal.Paused(callbackSong))
                true
            }
            is SourceSeparationMultiStemExecutionEventPayload.Canceled -> {
                _workerStateFlow.value = SourceSeparationUiState.Canceled(
                    song.id,
                    song.title,
                    selection?.generation,
                    session.cacheKey,
                )
                workerActivated = false
                clearPendingStart()
                cancelRequested.set(true)
                runCatching { session.close() }
                multiStemReconnectedSession = null
                true
            }
            is SourceSeparationMultiStemExecutionEventPayload.Failed -> {
                _workerStateFlow.value = SourceSeparationUiState.Failed(
                    songId = song.id,
                    songTitle = song.title,
                    message = payload.message,
                    selectionGeneration = selection?.generation,
                    cacheKey = session.cacheKey,
                )
                workerActivated = false
                clearPendingStart()
                cancelRequested.set(true)
                runCatching { session.close() }
                multiStemReconnectedSession = null
                true
            }
        }
    }

    private fun requestMultiStemRecoveredControl(
        session: SourceSeparationMultiStemReconnectedSession,
        control: SourceSeparationRecoveredControl,
        pauseReason: SourceSeparationPauseReason = SourceSeparationPauseReason.Standard,
    ) {
        workerScope.launch {
            runCatching {
                when (control) {
                    SourceSeparationRecoveredControl.Pause -> session.pause(pauseReason)
                    SourceSeparationRecoveredControl.Cancel -> session.cancel()
                }
            }
        }
    }

    private fun startIndependentRunRecovery(
        recovery: SourceSeparationIndependentRunRecovery,
    ) {
        trace("recovery.start")
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
        trace("recovery.run begin")
        val events = Channel<SourceSeparationExecutionHostEvent>(Channel.UNLIMITED)
        var session: SourceSeparationReconnectedSession? = null
        var terminal = false
        try {
            val recoverySelection = activeSelectionFlow.value
            val reconnected = recovery.reconnect(
                activeSelection = recoverySelection,
                onEvent = { event -> events.trySend(event) },
            ) ?: run {
                trace("recovery.run no reconnectable run")
                return
            }
            session = reconnected
            if (activeSelectionFlow.value != recoverySelection ||
                (recoverySelection.reference != null &&
                    !session.journal.matchesSelection(recoverySelection))
            ) {
                trace(
                    SourceSeparationLifecycleTrace.format(
                        event = "recovery.reject.staleSelection",
                        selectionGeneration = recoverySelection.generation,
                        cacheKey = session.cacheKey,
                        runId = session.runId,
                    ),
                )
                runCatching { session.pause() }
                return
            }
            trace(
                "recovery.run adopted run=${reconnected.runId} " +
                        "generation=${reconnected.processGeneration} " +
                        "cache=${reconnected.cacheKey.take(12)}",
            )
            synchronized(stateLock) {
                reconnectedSession = session
                reconnectedSong = session.journal.toRecoveredSong()
                reconnectedSelection = recoverySelection
                reconnectedLatestSequence = 0L
            }
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
            trace("recovery.run remote host died")
            val song = reconnectedSong
            if (song != null) {
                _workerStateFlow.value = SourceSeparationUiState.Failed(
                    songId = song.id,
                    songTitle = song.title,
                    message = remoteProcessStoppedMessage(),
                )
            }
            // A force-stop can kill the remote process while the new main
            // process is still trying to reconnect. Keep a request submitted
            // during that window so it can start a fresh owner after recovery
            // finishes. The cache coordinator records the abandoned owner
            // when that fresh run resumes the partial cache.
        } catch (error: Throwable) {
            trace("recovery.run failed ${error::class.java.simpleName}:${error.message}")
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
                reconnectedSelection = null
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
            trace(
                "recovery.run end terminal=$terminal worker=${workerJob?.isActive == true} " +
                        "pending=${pendingStartRequest?.song?.id} recovery=${recoveryJob?.isActive == true}",
            )
        }
    }

    private fun applyRecoveredBaseline(
        session: SourceSeparationReconnectedSession,
    ): Boolean {
        val song = requireNotNull(reconnectedSong)
        _workerStateFlow.value = SourceSeparationUiState.Running(
            songId = song.id,
            songTitle = song.title,
            selectionGeneration = reconnectedSelection?.generation,
            cacheKey = session.cacheKey,
        )
        return applyRecoveredEvent(session, session.baselineEvent)
    }

    private fun applyRecoveredEvent(
        session: SourceSeparationReconnectedSession,
        event: SourceSeparationExecutionHostEvent,
    ): Boolean {
        val admittedSelection = synchronized(stateLock) { reconnectedSelection }
        if (admittedSelection != null && activeSelectionFlow.value != admittedSelection) {
            trace(
                SourceSeparationLifecycleTrace.format(
                    event = "recovery.event.staleSelection",
                    selectionGeneration = admittedSelection.generation,
                    cacheKey = session.cacheKey,
                    runId = session.runId,
                ),
            )
            runCatching { session.pause() }
            session.close()
            synchronized(stateLock) {
                if (reconnectedSession === session) {
                    reconnectedSession = null
                    reconnectedSelection = null
                }
            }
            return true
        }
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
                    selectionGeneration = reconnectedSelection?.generation,
                    cacheKey = session.cacheKey,
                )
                false
            }
            is SourceSeparationExecutionHostEventPayload.Progress -> {
                publishWorkerProgress(
                    song = recoveredSong,
                    progress = payload.progress.toMdxRangeProgress(),
                    selectionGeneration = reconnectedSelection?.generation,
                    cacheKey = session.cacheKey,
                )
                false
            }
            is SourceSeparationExecutionHostEventPayload.Prepared -> {
                if (_workerStateFlow.value !is SourceSeparationUiState.Running) {
                    _workerStateFlow.value = SourceSeparationUiState.Running(
                        songId = recoveredSong.id,
                        songTitle = recoveredSong.title,
                        selectionGeneration = reconnectedSelection?.generation,
                        cacheKey = session.cacheKey,
                    )
                }
                notifyCallbacks("prepared") {
                    it.onSourceSeparationWorkerPrepared(callbackSong)
                }
                false
            }
            is SourceSeparationExecutionHostEventPayload.SegmentStateChanged,
            is SourceSeparationExecutionHostEventPayload.GpuFallbackLatched,
            -> false
            is SourceSeparationExecutionHostEventPayload.Completed -> {
                _workerStateFlow.value = SourceSeparationUiState.Completed(
                    songId = recoveredSong.id,
                    songTitle = recoveredSong.title,
                    selectionGeneration = reconnectedSelection?.generation,
                    cacheKey = session.cacheKey,
                )
                requestAutomaticPrune()
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
                    selectionGeneration = reconnectedSelection?.generation,
                    cacheKey = session.cacheKey,
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
                    selectionGeneration = reconnectedSelection?.generation,
                    cacheKey = session.cacheKey,
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
        val recoveredSelection = reconnectedSelection
        if (request.song.id == song.id && recoveredSelection == request.selection) {
            clearPendingStart()
            workerActivated = false
            return
        }
        val selectionChanged = recoveredSelection != request.selection
        if (selectionChanged ||
            request.priority >= SourceSeparationPendingStartReason.Manual.priority
        ) {
            requestRecoveredControl(
                session = session,
                control = SourceSeparationRecoveredControl.Pause,
                pauseReason = if (selectionChanged) {
                    SourceSeparationPauseReason.ActiveModelSuperseded
                } else {
                    SourceSeparationPauseReason.Standard
                },
            )
        }
    }

    private fun requestRecoveredControl(
        session: SourceSeparationReconnectedSession,
        control: SourceSeparationRecoveredControl,
        pauseReason: SourceSeparationPauseReason = SourceSeparationPauseReason.Standard,
    ) {
        workerScope.launch {
            runCatching {
                when (control) {
                    SourceSeparationRecoveredControl.Pause -> session.pause(pauseReason)
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
        callback?.let {
            dispatchCallbackSafely("recoveredTerminal", it, terminal::dispatch)
        }
    }

    private fun Song.callbackSong(): Song {
        return _playbackStateFlow.value.song.takeIf { current ->
            current != Song.emptySong && current.id == id
        } ?: this
    }

    private suspend fun runWorkerLoop() {
        val activeJob = coroutineContext[Job]
        trace("worker.loop begin")
        try {
            while (activeJob?.isActive == true && !cancelRequested.get()) {
                val request = nextWorkerRequest()
                if (request == null) {
                    if (!workerActivated) break
                    delay(SOURCE_SEPARATION_FOREGROUND_WORKER_IDLE_MS)
                    continue
                }

                trace(
                    SourceSeparationLifecycleTrace.format(
                        event = "worker.request",
                        selectionGeneration = request.selection.generation,
                        requestGeneration = request.requestGeneration,
                    ) + " song=${request.song.id} reason=${request.debugReason}",
                )
                clearPendingStart(request)
                synchronized(stateLock) {
                    activeWorkerRequest = request
                }
                try {
                    if (!SourceSeparationPlaybackLifecyclePolicy
                            .canRunWithPlaybackOwnerState(
                                request.runClass,
                                playbackOwnerActive.get(),
                            )
                    ) {
                        continue
                    }
                    runWorkerSong(request, activeJob)
                } finally {
                    synchronized(stateLock) {
                        if (activeWorkerRequest?.requestGeneration ==
                            request.requestGeneration
                        ) {
                            activeWorkerRequest = null
                        }
                    }
                }
            }
        } finally {
            synchronized(stateLock) {
                if (workerJob == activeJob) {
                    workerJob = null
                    activeWorkerRequest = null
                    activeWorkerSong = null
                }
            }
            cancelRequested.set(false)
            pauseRequested.set(false)
            trace(
                "worker.loop end activated=$workerActivated " +
                        "pending=${pendingStartRequest?.song?.id}",
            )
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
            newFullRequest(
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
        val runningSongId = synchronized(stateLock) {
            activeWorkerRequest?.song?.id
        } ?: return
        if (song.id != runningSongId) {
            if (isRunningPreStartRequestFor(song)) {
                return
            }
            song.takeIf { it != Song.emptySong }
                ?.let {
                    setPendingStart(
                        newFullRequest(
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
                activeWorkerRequest?.song?.id == song.id
    }

    private fun setPendingStart(request: SourceSeparationWorkerRequest) {
        synchronized(stateLock) {
            setPendingStartLocked(request)
        }
    }

    private fun setPendingStartLocked(request: SourceSeparationWorkerRequest) {
        val current = pendingStartRequest
        pendingStartRequest = if (current?.identity == request.identity &&
            current.priority > request.priority
        ) {
            current
        } else {
            request
        }
    }

    private fun clearPendingStart(expected: SourceSeparationWorkerRequest? = null) {
        synchronized(stateLock) {
            if (expected == null ||
                pendingStartRequest?.requestGeneration == expected.requestGeneration
            ) {
                pendingStartRequest = null
            }
        }
    }

    private fun ensureWorkerRunningIfActivated() {
        val job = synchronized(stateLock) {
            if (!workerActivated || workerJob?.isActive == true ||
                recoveryJob?.isActive == true || multiStemRecoveryJob?.isActive == true ||
                reconnectedSession != null || multiStemReconnectedSession != null
            ) {
                null
            } else {
                cancelRequested.set(false)
                pauseRequested.set(false)
                workerScope.launch(start = CoroutineStart.LAZY) {
                    runWorkerLoop()
                }.also { workerJob = it }
            }
        }
        if (job == null) {
            trace(
                "worker.ensure skipped activated=$workerActivated " +
                        "worker=${workerJob?.isActive == true} " +
                        "recovery=${recoveryJob?.isActive == true} " +
                        "multiStemRecovery=${multiStemRecoveryJob?.isActive == true} " +
                        "reconnected=${reconnectedSession != null || multiStemReconnectedSession != null}",
            )
            return
        }
        trace("worker.ensure launch")
        job.start()
    }

    private suspend fun runWorkerSong(
        request: SourceSeparationWorkerRequest,
        activeJob: Job?,
    ) {
        val song = request.song
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
        trace(
            "worker.song begin song=${song.id} reason=${request.debugReason} " +
                    "pause=${pauseRequested.get()}",
        )
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
                    if (!isCurrentRequest(request)) {
                        traceStaleRequest("worker.unavailable.stale", request, null)
                        return
                    }
                    trace("worker.song unavailable song=${song.id} reason=${resolution.reason}")
                    failUnavailableSong(song, resolution, request.selection.generation)
                    return
                }
            }
            if (!isCurrentRequest(request)) {
                traceStaleRequest("worker.resolve.stale", request, resolved.cacheKey)
                return
            }
            admittedSong = resolved
            synchronized(stateLock) {
                if (activeWorkerRequest?.requestGeneration == request.requestGeneration) {
                    activeWorkerSong = resolved
                }
            }
            val shouldPromoteCompletedStems = preferences.getBoolean(
                SOURCE_SEPARATION_AUTO_FLAC_COMPRESSION,
                true,
            )
            val completedCache = completedCacheStatus(resolved)
            if (completedCache != null) {
                if (!isCurrentRequest(request, resolved.cacheKey)) {
                    traceStaleRequest("worker.completedCache.stale", request, resolved.cacheKey)
                    return
                }
                _workerStateFlow.value = SourceSeparationUiState.Completed(
                    songId = song.id,
                    songTitle = song.title,
                    selectionGeneration = request.selection.generation,
                    cacheKey = resolved.cacheKey,
                )
                notifyCallbacks("completed") {
                    it.onSourceSeparationWorkerCompleted(
                        song = song,
                        cacheKey = resolved.cacheKey,
                        shouldPromoteCompletedStems = shouldPromoteCompletedStems &&
                                completedCache.canPromote,
                    )
                }
                return
            }
            _workerStateFlow.value = SourceSeparationUiState.Running(
                songId = song.id,
                songTitle = song.title,
                selectionGeneration = request.selection.generation,
                cacheKey = resolved.cacheKey,
            )
            val tryGpu = preferences.readSourceSeparationGpuEnabled()
            trace(
                SourceSeparationLifecycleTrace.format(
                    event = "worker.separate",
                    selectionGeneration = request.selection.generation,
                    cacheKey = resolved.cacheKey,
                    requestGeneration = request.requestGeneration,
                ) + " song=${song.id} tryGpu=$tryGpu",
            )
            val result = sourceSeparationRuntime.separate(
                song = resolved,
                tryGpu = tryGpu,
                runClass = request.runClass,
                onProgress = { progress ->
                    if (!isCurrentRequest(request, resolved.cacheKey)) {
                        traceStaleRequest("worker.progress.stale", request, resolved.cacheKey)
                        return@separate
                    }
                    if (preStartReadyWindowCount != null &&
                        progress.scheduler?.playbackSegmentIndex == 0 &&
                        progress.scheduler.playbackReadyWindowReadyCount >=
                        preStartReadyWindowCount
                    ) {
                        preStartSatisfied = true
                    }
                    publishWorkerProgress(
                        song = song,
                        progress = progress,
                        selectionGeneration = request.selection.generation,
                        cacheKey = resolved.cacheKey,
                    )
                },
                onPrepared = {
                    if (!isCurrentRequest(request, resolved.cacheKey)) {
                        traceStaleRequest("worker.prepared.stale", request, resolved.cacheKey)
                        return@separate
                    }
                    if (preStartReadyWindowCount == null &&
                        readBlendMode() == SourceSeparationBlendMode.PerSong
                    ) {
                        migrateTemporaryPerSongBlend(song, resolved)
                    }
                    notifyCallbacks("prepared") {
                        it.onSourceSeparationWorkerPrepared(song)
                    }
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
                            !isCurrentRequest(request, resolved.cacheKey) ||
                            pauseRequested.get() ||
                            preStartSatisfied ||
                            (preStartReadyWindowCount == null &&
                                    _playbackStateFlow.value.song.id != song.id) ||
                            activeJob?.isActive != true
                },
                pauseReasonProvider = {
                    pauseReasonOverride.getAndSet(null) ?:
                    if (activeSelectionFlow.value != request.selection) {
                        SourceSeparationPauseReason.ActiveModelSuperseded
                    } else {
                        SourceSeparationPauseReason.Standard
                    }
                },
                shouldCancel = {
                    cancelRequested.get() ||
                            (!pauseRequested.get() && activeJob?.isActive != true)
                },
            )
            if (!isCurrentRequest(request, resolved.cacheKey)) {
                traceStaleRequest("worker.result.stale", request, resolved.cacheKey)
                return
            }
            if (result is SourceSeparationModelAwareEngineResult.Busy) {
                trace("worker.song busy song=${song.id}")
                _workerStateFlow.value = SourceSeparationUiState.Idle
                notifyCallbacks("paused") {
                    it.onSourceSeparationWorkerPaused(song)
                }
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
                selectionGeneration = request.selection.generation,
                cacheKey = resolved.cacheKey,
            )
            requestAutomaticPrune()
            notifyCallbacks("completed") {
                it.onSourceSeparationWorkerCompleted(
                    song = song,
                    cacheKey = resolved.cacheKey,
                    shouldPromoteCompletedStems = shouldPromoteCompletedStems,
                )
            }
            trace("worker.song completed song=${song.id} cache=${resolved.cacheKey.take(12)}")
        } catch (_: SourceSeparationPausedException) {
            trace("worker.song paused song=${song.id}")
            if (isCurrentRequest(request, admittedSong?.cacheKey)) {
                _workerStateFlow.value = SourceSeparationUiState.Paused(
                    songId = song.id,
                    songTitle = song.title,
                    selectionGeneration = request.selection.generation,
                    cacheKey = admittedSong?.cacheKey,
                )
                if (!preStartSatisfied) {
                    notifyCallbacks("paused") {
                        it.onSourceSeparationWorkerPaused(song)
                    }
                }
            } else {
                traceStaleRequest("worker.paused.stale", request, admittedSong?.cacheKey)
            }
        } catch (_: CancellationException) {
            trace("worker.song canceled song=${song.id}")
            if (isCurrentRequest(request, admittedSong?.cacheKey)) {
                _workerStateFlow.value = SourceSeparationUiState.Canceled(
                    songId = song.id,
                    songTitle = song.title,
                    selectionGeneration = request.selection.generation,
                    cacheKey = admittedSong?.cacheKey,
                )
            }
            cancelRequested.set(true)
        } catch (_: SourceSeparationAdmittedGpuRuntimeMismatchException) {
            trace("worker.song gpu runtime mismatch song=${song.id}")
            val message = context.getString(R.string.source_separation_model_load_failed)
            if (isCurrentRequest(request, admittedSong?.cacheKey)) {
                _workerStateFlow.value = SourceSeparationUiState.Failed(
                    songId = song.id,
                    songTitle = song.title,
                    message = message,
                    selectionGeneration = request.selection.generation,
                    cacheKey = admittedSong?.cacheKey,
                )
                notifyCallbacks("modelLoadFailed") {
                    it.onSourceSeparationWorkerModelLoadFailed(message)
                }
                synchronized(stateLock) {
                    workerActivated = false
                    pendingStartRequest = null
                }
                cancelRequested.set(true)
            }
        } catch (_: SourceSeparationRemoteHostDiedException) {
            trace("worker.song remote host died song=${song.id}")
            if (isCurrentRequest(request, admittedSong?.cacheKey)) {
                _workerStateFlow.value = SourceSeparationUiState.Failed(
                    songId = song.id,
                    songTitle = song.title,
                    message = remoteProcessStoppedMessage(),
                    selectionGeneration = request.selection.generation,
                    cacheKey = admittedSong?.cacheKey,
                )
                synchronized(stateLock) {
                    workerActivated = false
                    pendingStartRequest = null
                }
                cancelRequested.set(true)
            }
        } catch (error: Throwable) {
            trace(
                "worker.song failed song=${song.id} " +
                        "${error::class.java.simpleName}:${error.message}",
            )
            if (isCurrentRequest(request, admittedSong?.cacheKey)) {
                _workerStateFlow.value = SourceSeparationUiState.Failed(
                    songId = song.id,
                    songTitle = song.title,
                    message = error.message,
                    selectionGeneration = request.selection.generation,
                    cacheKey = admittedSong?.cacheKey,
                )
                cancelRequested.set(true)
            }
        } finally {
            synchronized(stateLock) {
                if (activeWorkerRequest?.requestGeneration == request.requestGeneration &&
                    activeWorkerSong === admittedSong
                ) {
                    activeWorkerSong = null
                }
            }
            pauseRequested.set(false)
            pauseReasonOverride.set(null)
            trace("worker.song end song=${song.id} worker=${workerJob?.isActive == true}")
        }
    }

    private fun remoteProcessStoppedMessage(): String = context.getString(
        R.string.source_separation_process_stopped_unexpectedly,
    )

    private suspend fun completedCacheStatus(
        song: SourceSeparationRuntimeSong,
    ): SourceSeparationModelAwareCacheStatus.Completed? {
        return withContext(Dispatchers.IO) {
            runCatching { sourceSeparationRuntime.cacheStatus(song) }
                .getOrNull() as? SourceSeparationModelAwareCacheStatus.Completed
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
        selectionGeneration: Long,
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
            selectionGeneration = selectionGeneration,
        )
        if (resolution.reason != SourceSeparationRuntimeUnavailableReason.SourceUnavailable &&
            resolution.reason != SourceSeparationRuntimeUnavailableReason.NoSong
        ) {
            notifyCallbacks("modelLoadFailed") {
                it.onSourceSeparationWorkerModelLoadFailed(message)
            }
        }
        synchronized(stateLock) {
            workerActivated = false
            pendingStartRequest = null
        }
        cancelRequested.set(true)
    }

    private fun publishWorkerProgress(
        song: Song,
        progress: MdxRangeProgress,
        selectionGeneration: Long? = null,
        cacheKey: String? = null,
    ) {
        val averageWindowMs = progress.completedWindowElapsedMs
            ?.let(performanceStats::recordWindowElapsed)
            ?: performanceStats.averageWindowMs()
        recordDebugWindowSample(song, progress)
        _workerStateFlow.value = SourceSeparationUiState.Running(
            songId = song.id,
            songTitle = song.title,
            selectionGeneration = selectionGeneration,
            cacheKey = cacheKey,
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
        notifyCallbacks("progress") {
            it.onSourceSeparationWorkerProgress(song.callbackSong())
        }
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
                protectedCacheKeys = pruneProtectedCacheKeys(),
            )
        }
    }

    private fun observeAutomaticPruneRequests() {
        workerScope.launch {
            for (ignored in automaticPruneRequests) {
                pruneCachesIfEnabled()
            }
        }
    }

    private fun pruneProtectedCacheKeys(): Set<String> {
        val playback = _playbackStateFlow.value
        val currentCacheKey = playback.song
            .takeIf { song ->
                song != Song.emptySong &&
                    readBlendMode() != SourceSeparationBlendMode.Off &&
                    SourceSeparationBlendDemand.requiresSeparatedOutput(
                        playback.sourceSeparationBlend,
                    )
            }
            ?.let(::resolveSong)
            ?.cacheKey
        return protectedCacheKeys() + listOfNotNull(currentCacheKey)
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
        return sourceSeparationMixSettings.readGlobalBlend(currentMixModelKey())
    }

    private fun currentMixModelKey(): SourceSeparationMixModelKey? {
        return multiStemSelectionFlow.value.modelId
            ?.let(SourceSeparationMixModelKey::multiStem)
            ?: activeSelectionFlow.value.reference?.modelId
                ?.let(SourceSeparationMixModelKey::mdx)
    }

    private fun songNeedsSeparatedOutputForCurrentMode(song: Song): Boolean {
        return when (readBlendMode()) {
            SourceSeparationBlendMode.Off -> false
            SourceSeparationBlendMode.Global ->
                SourceSeparationBlendDemand.requiresSeparatedOutput(readGlobalBlend())
            SourceSeparationBlendMode.PerSong -> recordedBlendForSongBlocking(song)
                ?.let(SourceSeparationBlendDemand::requiresSeparatedOutput)
                ?: false
        }
    }

    private fun recordedBlendForSongBlocking(song: Song): Float? {
        if (song == Song.emptySong) return null
        val resolved = runCatching { resolveSong(song) }.getOrNull()
        return readTemporaryPerSongBlend(
            song,
            resolved?.toMixModelKey() ?: currentMixModelKey(),
        ) ?: runCatching {
            resolved?.let(sourceSeparationRuntime::readBlend)
        }.getOrNull()
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
            removeTemporaryPerSongBlend(song, resolved.toMixModelKey())
        }
        return saved
    }

    private fun migrateTemporaryPerSongBlend(
        song: Song,
        resolved: SourceSeparationRuntimeSong,
    ): Boolean {
        val blend = readTemporaryPerSongBlend(song, resolved.toMixModelKey()) ?: return false
        return savePerSongBlend(song, resolved, blend)
    }

    private fun readTemporaryPerSongBlend(
        song: Song,
        model: SourceSeparationMixModelKey?,
    ): Float? = sourceSeparationMixSettings.readPendingSongBlend(model, song)

    private fun removeTemporaryPerSongBlend(
        song: Song,
        model: SourceSeparationMixModelKey?,
    ) {
        sourceSeparationMixSettings.removePendingSongBlend(model, song)
    }

    private fun newFullRequest(
        song: Song,
        reason: SourceSeparationPendingStartReason,
    ): SourceSeparationWorkerRequest.Full = SourceSeparationWorkerRequest.Full(
        song = song,
        reason = reason,
        selection = activeSelectionFlow.value,
        requestGeneration = requestGeneration.incrementAndGet(),
    )

    private fun newPreStartRequest(
        song: Song,
        readyWindowCount: Int,
    ): SourceSeparationWorkerRequest.StartWindowPreStart =
        SourceSeparationWorkerRequest.StartWindowPreStart(
            song = song,
            readyWindowCount = readyWindowCount,
            selection = activeSelectionFlow.value,
            requestGeneration = requestGeneration.incrementAndGet(),
        )

    private fun isCurrentRequest(
        request: SourceSeparationWorkerRequest,
        cacheKey: String? = null,
    ): Boolean = synchronized(stateLock) {
        activeWorkerRequest?.requestGeneration == request.requestGeneration &&
            activeWorkerRequest?.selection == request.selection &&
            activeSelectionFlow.value == request.selection &&
            (cacheKey == null || activeWorkerSong?.cacheKey == cacheKey)
    }

    private fun traceStaleRequest(
        event: String,
        request: SourceSeparationWorkerRequest,
        cacheKey: String?,
    ) {
        trace(
            SourceSeparationLifecycleTrace.format(
                event = event,
                selectionGeneration = request.selection.generation,
                cacheKey = cacheKey,
                requestGeneration = request.requestGeneration,
            ),
        )
    }

    private fun trace(message: String) {
        Log.d(TAG, message)
    }

    private companion object {
        const val TAG = "SourceSepWorker"
    }
}

private enum class SourceSeparationPendingStartReason(val priority: Int) {
    Manual(priority = 2),
    PlaybackDemand(priority = 1),
}

private enum class SourceSeparationRequestAction {
    None,
    EnsureWorker,
    PauseRecovered,
}

private sealed interface SourceSeparationWorkerRequest {
    val song: Song
    val selection: SourceSeparationActiveSelectionSnapshot
    val requestGeneration: Long
    val debugReason: String
    val runClass: SourceSeparationExecutionRunClass
    val priority: Int
    val identity: SourceSeparationWorkerRequestIdentity
        get() = SourceSeparationWorkerRequestIdentity.from(song, selection)

    data class Full(
        override val song: Song,
        val reason: SourceSeparationPendingStartReason,
        override val selection: SourceSeparationActiveSelectionSnapshot,
        override val requestGeneration: Long,
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
        override val selection: SourceSeparationActiveSelectionSnapshot,
        override val requestGeneration: Long,
    ) : SourceSeparationWorkerRequest {
        override val debugReason: String = "PreStart($readyWindowCount)"
        override val runClass = SourceSeparationExecutionRunClass.NextSongPrefetch
        override val priority: Int = 0
    }
}

internal data class SourceSeparationWorkerRequestIdentity(
    val songId: Long,
    val filePath: String,
    val fileSize: Long,
    val rawDateModified: Long,
    val durationMs: Long,
    val activeReference: SourceSeparationActiveModelReference?,
    val selectionGeneration: Long,
) {
    fun matches(
        song: Song,
        selection: SourceSeparationActiveSelectionSnapshot,
    ): Boolean = this == from(song, selection)

    companion object {
        fun from(
            song: Song,
            selection: SourceSeparationActiveSelectionSnapshot,
        ): SourceSeparationWorkerRequestIdentity = SourceSeparationWorkerRequestIdentity(
            songId = song.id,
            filePath = song.data,
            fileSize = song.size,
            rawDateModified = song.rawDateModified,
            durationMs = song.duration,
            activeReference = selection.reference,
            selectionGeneration = selection.generation,
        )
    }
}

private enum class SourceSeparationRecoveredControl {
    Pause,
    Cancel,
}

private data class SourceSeparationCacheDeletionCancellation(
    val job: Job?,
    val recovered: SourceSeparationReconnectedSession?,
    val multiStemRecovered: SourceSeparationMultiStemReconnectedSession?,
    val preflightIdentity: SourceSeparationWorkerRequestIdentity?,
)

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

private fun SourceSeparationCacheRunJournal.matchesSelection(
    selection: SourceSeparationActiveSelectionSnapshot,
): Boolean {
    val active = selection.reference ?: return false
    return request.identity.modelId == active.modelId &&
        request.identity.artifactSha256.equals(active.artifactSha256, ignoreCase = true) &&
        request.identity.contractSchemaVersion == active.contractSchemaVersion &&
        (active.profileId == null || request.identity.profileRevisionId == active.profileId)
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

internal fun SourceSeparationUiState.selectionGenerationOrNull(): Long? = when (this) {
    SourceSeparationUiState.Idle -> null
    is SourceSeparationUiState.Running -> selectionGeneration
    is SourceSeparationUiState.Completed -> selectionGeneration
    is SourceSeparationUiState.Canceled -> selectionGeneration
    is SourceSeparationUiState.Paused -> selectionGeneration
    is SourceSeparationUiState.Failed -> selectionGeneration
}

internal fun SourceSeparationUiState.songIdOrNull(): Long? = when (this) {
    SourceSeparationUiState.Idle -> null
    is SourceSeparationUiState.Running -> songId
    is SourceSeparationUiState.Completed -> songId
    is SourceSeparationUiState.Canceled -> songId
    is SourceSeparationUiState.Paused -> songId
    is SourceSeparationUiState.Failed -> songId
}

internal fun SourceSeparationUiState.cacheKeyOrNull(): String? = when (this) {
    SourceSeparationUiState.Idle -> null
    is SourceSeparationUiState.Running -> cacheKey
    is SourceSeparationUiState.Completed -> cacheKey
    is SourceSeparationUiState.Canceled -> cacheKey
    is SourceSeparationUiState.Paused -> cacheKey
    is SourceSeparationUiState.Failed -> cacheKey
}

private fun MdxSourceDecodeMode.toUiState(): SourceSeparationDecodeModeUiState {
    return when (this) {
        MdxSourceDecodeMode.FullSong -> SourceSeparationDecodeModeUiState.FullSong
        MdxSourceDecodeMode.Window -> SourceSeparationDecodeModeUiState.Window
    }
}

private const val KEY_SOURCE_SEPARATION_PLAYBACK_ENABLED =
    "source_separation.playback_enabled"
private const val KEY_SOURCE_SEPARATION_REMEMBER_PER_SONG =
    "source_separation.remember_per_song"
private const val SOURCE_SEPARATION_FOREGROUND_WORKER_IDLE_MS = 250L
private const val SOURCE_SEPARATION_FOREGROUND_WORKER_LEAVE_SONG_WAIT_MS = 50L
private const val SOURCE_SEPARATION_DEBUG_WINDOW_SAMPLE_LIMIT = 128
