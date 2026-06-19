package com.mardous.booming.ui.screen.player

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.liveData
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Player.REPEAT_MODE_OFF
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.mardous.booming.core.model.MediaEvent
import com.mardous.booming.core.model.PaletteColor
import com.mardous.booming.core.model.action.QueueClearingBehavior
import com.mardous.booming.core.model.action.SongClickBehavior
import com.mardous.booming.core.model.player.MetadataField
import com.mardous.booming.core.model.player.PlayerColorScheme
import com.mardous.booming.core.model.player.PlayerColorSchemeMode
import com.mardous.booming.core.model.shuffle.GroupShuffleMode
import com.mardous.booming.core.model.shuffle.OpenShuffleMode
import com.mardous.booming.core.model.shuffle.ShuffleOperationState
import com.mardous.booming.core.model.shuffle.SpecialShuffleMode
import com.mardous.booming.core.sort.SongSortMode
import com.mardous.booming.data.SongProvider
import com.mardous.booming.data.local.AlbumCoverSaver
import com.mardous.booming.data.local.repository.Repository
import com.mardous.booming.data.local.room.PlaylistEntity
import com.mardous.booming.data.mapper.toSongs
import com.mardous.booming.data.model.QueuePosition
import com.mardous.booming.data.model.Song
import com.mardous.booming.playback.Playback
import com.mardous.booming.playback.ProgressObserver
import com.mardous.booming.playback.getQueueItems
import com.mardous.booming.playback.shuffle.ShuffleManager
import com.mardous.booming.playback.toMediaItems
import com.mardous.booming.separation.SourceSeparationCacheStatus
import com.mardous.booming.separation.SourceSeparationEngine
import com.mardous.booming.separation.SourceSeparationPausedException
import com.mardous.booming.util.NOW_PLAYING_EXTRA_INFO
import com.mardous.booming.util.Preferences
import com.mardous.booming.util.REMEMBER_SHUFFLE_MODE
import com.mardous.booming.util.SOURCE_SEPARATION_AUTO_FLAC_COMPRESSION
import com.mardous.booming.util.SOURCE_SEPARATION_SHOW_SNACKBAR_PROGRESS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

const val QUEUE_DEBOUNCE = 100L

@OptIn(FlowPreview::class)
@androidx.annotation.OptIn(UnstableApi::class)
class PlayerViewModel(
    private val preferences: SharedPreferences,
    private val repository: Repository,
    private val albumCoverSaver: AlbumCoverSaver,
    private val sourceSeparationEngine: SourceSeparationEngine
) : ViewModel(), Player.Listener {

    private val queueMutex = Mutex()
    private val progressObserver = ProgressObserver(intervalMs = 100)
    private val shuffleManager = ShuffleManager()
    private var mediaController: MediaController? = null

    private val _mediaEvent = MutableSharedFlow<MediaEvent>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val mediaEvent = _mediaEvent.asSharedFlow()

    private val _isPlayingFlow = MutableStateFlow(false)
    val isPlayingFlow = _isPlayingFlow.asStateFlow()
    val isPlaying get() = _isPlayingFlow.value

    private val _progressFlow = MutableStateFlow(C.TIME_UNSET)
    val progressFlow = _progressFlow.asStateFlow()
    val progress get() = progressFlow.value

    private val _durationFlow = MutableStateFlow(C.TIME_UNSET)
    val durationFlow = _durationFlow.asStateFlow()
    val duration get() = durationFlow.value

    private val _playbackSpeed = MutableStateFlow(1f)
    val playbackSpeed = _playbackSpeed.asStateFlow()

    private val _repeatModeFlow = MutableStateFlow(REPEAT_MODE_OFF)
    val repeatModeFlow = _repeatModeFlow.asStateFlow()
    val repeatMode get() = repeatModeFlow.value

    private val _shuffleModeFlow = MutableStateFlow(false)
    val shuffleModeFlow = _shuffleModeFlow.asStateFlow()
    val shuffleModeEnabled get() = shuffleModeFlow.value

    private val _queueFlow = MutableStateFlow(emptyList<Song>())
    val queueFlow = _queueFlow.asStateFlow()
    val queue get() = queueFlow.value

    private val _positionFlow = MutableStateFlow(QueuePosition.Undefined)
    val positionFlow = _positionFlow.asStateFlow()
    val position get() = positionFlow.value

    private val _currentSongFlow = MutableStateFlow(Song.emptySong)
    val currentSongFlow = _currentSongFlow.asStateFlow()
    val currentSong get() = currentSongFlow.value

    private val _nextSongFlow = MutableStateFlow(Song.emptySong)
    val nextSongFlow = _nextSongFlow.asStateFlow()
    val nextSong get() = nextSongFlow.value

    private val _colorScheme = MutableStateFlow(PlayerColorScheme.Unspecified)
    val colorSchemeFlow = _colorScheme.asStateFlow()
    val colorScheme get() = colorSchemeFlow.value

    private val _shuffleOperationState = MutableStateFlow(ShuffleOperationState())
    val shuffleOperationState = _shuffleOperationState.asStateFlow()

    private val _extraInfoFlow = MutableStateFlow<String?>(null)
    val extraInfoFlow = _extraInfoFlow.asStateFlow()

    private val sourceSeparationCancelRequested = AtomicBoolean(false)
    private val sourceSeparationPauseRequested = AtomicBoolean(false)
    private var sourceSeparationJob: Job? = null
    private var sourceSeparationSongId: Long? = null
    private var sourceSeparationPendingStartSongId: Long? = null
    private var sourceSeparationSettingsApplyJob: Job? = null
    private var sourceSeparationPlaybackSyncJob: Job? = null
    private var sourceSeparationWindowDecodeExperimentJob: Job? = null
    private var sourceSeparationFlacPromotionJob: Job? = null

    private val _sourceSeparationStateFlow =
        MutableStateFlow<SourceSeparationUiState>(SourceSeparationUiState.Idle)
    val sourceSeparationStateFlow = _sourceSeparationStateFlow.asStateFlow()

    private val _currentSourceSeparationCacheAvailableFlow = MutableStateFlow(false)
    val currentSourceSeparationCacheAvailableFlow =
        _currentSourceSeparationCacheAvailableFlow.asStateFlow()

    private val _currentSourceSeparationCacheStateFlow =
        MutableStateFlow<SourceSeparationCacheUiState>(SourceSeparationCacheUiState.NotStarted)
    val currentSourceSeparationCacheStateFlow =
        _currentSourceSeparationCacheStateFlow.asStateFlow()

    private val _sourceSeparationPendingActionFlow =
        MutableStateFlow<SourceSeparationPendingAction?>(null)
    val sourceSeparationPendingActionFlow =
        _sourceSeparationPendingActionFlow.asStateFlow()

    private val _sourceSeparationPlaybackStateFlow =
        MutableStateFlow(
            SourceSeparationPlaybackUiState(
                blend = readSourceSeparationGlobalBlend()
            )
        )
    val sourceSeparationPlaybackStateFlow = _sourceSeparationPlaybackStateFlow.asStateFlow()

    private val _sourceSeparationWindowDecodeExperimentStateFlow =
        MutableStateFlow<SourceSeparationWindowDecodeExperimentUiState>(
            SourceSeparationWindowDecodeExperimentUiState.Idle
        )
    val sourceSeparationWindowDecodeExperimentStateFlow =
        _sourceSeparationWindowDecodeExperimentStateFlow.asStateFlow()

    private val _sourceSeparationRememberPerSongFlow =
        MutableStateFlow(readSourceSeparationRememberPerSong())
    val sourceSeparationRememberPerSongFlow =
        _sourceSeparationRememberPerSongFlow.asStateFlow()

    private val _sourceSeparationBlendModeFlow =
        MutableStateFlow(readSourceSeparationBlendMode())
    val sourceSeparationBlendModeFlow = _sourceSeparationBlendModeFlow.asStateFlow()

    private val _sourceSeparationAutoFlacCompressionFlow =
        MutableStateFlow(readSourceSeparationAutoFlacCompression())
    val sourceSeparationAutoFlacCompressionFlow =
        _sourceSeparationAutoFlacCompressionFlow.asStateFlow()

    private val _sourceSeparationShowSnackbarProgressFlow =
        MutableStateFlow(readSourceSeparationShowSnackbarProgress())
    val sourceSeparationShowSnackbarProgressFlow =
        _sourceSeparationShowSnackbarProgressFlow.asStateFlow()

    private val internalJobs = mutableListOf<Job>()

    override fun onCleared() {
        progressObserver.stop()
        cancelSourceSeparation()
        sourceSeparationSettingsApplyJob?.cancel()
        sourceSeparationWindowDecodeExperimentJob?.cancel()
        sourceSeparationFlacPromotionJob?.cancel()
        cancelInternalJobs()
        super.onCleared()
    }

    fun setMediaController(mediaController: MediaController?) {
        if (this.mediaController == mediaController) return

        this.mediaController = mediaController
        cancelInternalJobs()

        if (mediaController != null) {
            _isPlayingFlow.value = mediaController.isPlaying
            _repeatModeFlow.value = mediaController.repeatMode
            _shuffleModeFlow.value = mediaController.shuffleModeEnabled

            if (progress == C.TIME_UNSET || duration == C.TIME_UNSET) {
                _progressFlow.value = mediaController.contentPosition
                _durationFlow.value = mediaController.contentDuration
            }

            onGenerateQueue(mediaController)

            internalJobs += mediaEvent
                .filter { it == MediaEvent.MediaContentChanged }
                .debounce(500)
                .onEach { event -> onGenerateQueue(mediaController) }
                .launchIn(viewModelScope)

            internalJobs += combine(queueFlow, positionFlow)
            { queue, position -> Pair(queue, position) }
                .debounce(QUEUE_DEBOUNCE)
                .onEach { (queue, position) ->
                    _currentSongFlow.value = queue.getOrElse(position.current) { Song.emptySong }
                    _nextSongFlow.value = queue.getOrElse(position.next) { Song.emptySong }
                }
                .launchIn(viewModelScope)

            internalJobs += currentSongFlow
                .distinctUntilChangedBy { it.id }
                .onEach { song ->
                    pauseSourceSeparationIfSongChanged(song)
                    refreshCurrentSourceSeparationCacheAvailable(song)
                    applySourceSeparationSettingsForSong(
                        song = song,
                        showMessage = false,
                    )
                }
                .launchIn(viewModelScope)

            internalJobs += currentSongFlow
                .debounce(500)
                .distinctUntilChangedBy { it.id }
                .onEach { song -> onGenerateExtraInfo(song) }
                .launchIn(viewModelScope)

            internalJobs += isPlayingFlow
                .onEach { isPlaying -> onSetIsPlaying(isPlaying) }
                .launchIn(viewModelScope)
        }
    }

    fun submitEvent(mediaEvent: MediaEvent) {
        _mediaEvent.tryEmit(mediaEvent)
    }

    private fun cancelInternalJobs() {
        internalJobs.forEach { it.cancel() }
        internalJobs.clear()
    }

    private fun onSetIsPlaying(isPlaying: Boolean) {
        if (isPlaying) {
            progressObserver.start {
                mediaController?.let { controller ->
                    _progressFlow.value = controller.contentPosition
                    _durationFlow.value = controller.contentDuration
                }
            }
        } else {
            progressObserver.stop()
        }
    }

    private fun onGenerateQueue(
        player: Player,
        timeline: Timeline = player.currentTimeline
    ) = viewModelScope.launch {
        queueMutex.withLock {
            // If the timeline is empty, reset the queue and exit early.
            if (timeline.isEmpty) {
                _queueFlow.value = emptyList()
                return@launch
            }

            // Capture the player's current state.
            val shuffle = player.shuffleModeEnabled
            val playerIndex = player.currentMediaItemIndex

            val queueItems = player.getQueueItems(shuffle)
            val indicesInTimeline = queueItems.map { it.indexInTimeline }.toIntArray()
            val queuePosition = QueuePosition(
                current = indicesInTimeline.indexOf(playerIndex),
                indicesInTimeline = indicesInTimeline
            )

            // Retrieve existing songs for the given MediaItems and detect missing ones.
            val (songs, missingMediaItems) = withContext(IO) {
                repository.songsByMediaItems(queueItems.map { it.mediaItem })
            }

            // Build a set of IDs representing missing (deleted) MediaItems.
            val missingIds = missingMediaItems.mapTo(HashSet()) { it.mediaId }
            if (missingIds.isNotEmpty()) {
                // Identify contiguous ranges of missing items to remove them in grouped batches.
                val ranges = mutableListOf<IntRange>()
                var start = -1

                for (i in queueItems.indices) {
                    val missing = queueItems[i].mediaItem.mediaId in missingIds
                    if (missing && start == -1) {
                        // Beginning of a new missing range.
                        start = i
                    } else if (!missing && start != -1) {
                        // End of the current missing range.
                        ranges += (start until i)
                        start = -1
                    }
                }

                // If the last range extends to the end of the list, close it.
                if (start != -1) ranges += (start until queueItems.size)

                // Remove ranges in reverse order to avoid index shifting issues.
                for (range in ranges.asReversed()) {
                    player.removeMediaItems(range.first, range.last + 1)
                }
            }

            // Update the queue with the valid songs and current positions.
            _queueFlow.value = songs
            _positionFlow.value = queuePosition
        }
    }

    private fun onGenerateExtraInfo(song: Song) = viewModelScope.launch(IO) {
        _extraInfoFlow.value = if (Preferences.displayExtraInfo) {
            MetadataField.getMetadataValue(
                song = song,
                fields = Preferences.getExtraInfoContent(
                    key = NOW_PLAYING_EXTRA_INFO,
                    defaultContent = Preferences.getDefaultNowPlayingInfo()
                )
            )
        } else null
    }

    override fun onEvents(player: Player, events: Player.Events) {
        val isPlayStateEvent = events.containsAny(
            Player.EVENT_PLAYBACK_STATE_CHANGED,
            Player.EVENT_IS_PLAYING_CHANGED,
            Player.EVENT_PLAY_WHEN_READY_CHANGED
        )
        if (isPlayStateEvent) {
            _isPlayingFlow.value = player.playWhenReady && player.isPlaying
            if (player.playbackState == Player.STATE_READY && !player.playWhenReady) {
                _progressFlow.value = player.contentPosition
                _durationFlow.value = player.contentDuration
            }
        }
        if (events.contains(Player.EVENT_REPEAT_MODE_CHANGED)) {
            _repeatModeFlow.value = player.repeatMode
        }
        if (events.contains(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)) {
            if (!events.contains(Player.EVENT_TIMELINE_CHANGED)) {
                onGenerateQueue(player)
            }
            _shuffleModeFlow.value = player.shuffleModeEnabled
        }
        if (events.contains(Player.EVENT_POSITION_DISCONTINUITY)) {
            if (!events.contains(Player.EVENT_TIMELINE_CHANGED)) {
                _positionFlow.value = position.setCurrentIndex(player.currentMediaItemIndex)
            }
            _progressFlow.value = player.contentPosition
            _durationFlow.value = player.contentDuration
            if (_sourceSeparationBlendModeFlow.value != SourceSeparationBlendMode.PerSong ||
                !events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)
            ) {
                syncSourceSeparationPlaybackIfRequested(force = true)
            }
            if (!player.playWhenReady) {
                _progressFlow.value = player.contentPosition
                _durationFlow.value = player.contentDuration
            }
        }
        if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
            if (!events.contains(Player.EVENT_TIMELINE_CHANGED)) {
                _positionFlow.value = position.setCurrentIndex(player.currentMediaItemIndex)
            }
        }
        if (events.contains(Player.EVENT_TIMELINE_CHANGED)) {
            onGenerateQueue(player)
        }
    }

    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
        _playbackSpeed.value = playbackParameters.speed
    }

    fun toggleFavorite() {
        mediaController?.sendCustomCommand(SessionCommand(Playback.TOGGLE_FAVORITE, Bundle.EMPTY), Bundle.EMPTY)
    }

    fun cycleRepeatMode() {
        mediaController?.sendCustomCommand(SessionCommand(Playback.CYCLE_REPEAT, Bundle.EMPTY), Bundle.EMPTY)
    }

    fun toggleShuffleMode() {
        mediaController?.sendCustomCommand(SessionCommand(Playback.TOGGLE_SHUFFLE, Bundle.EMPTY), Bundle.EMPTY)
    }

    fun togglePlayPause() {
        if (isPlaying) {
            mediaController?.pause()
        } else {
            mediaController?.play()
        }
    }

    fun play() {
        mediaController?.play()
    }

    fun seekToNext() {
        mediaController?.seekToNext()
    }

    fun seekToPrevious() {
        mediaController?.seekToPrevious()
    }

    fun seekForward() {
        mediaController?.seekForward()
    }

    fun seekBack() {
        mediaController?.seekBack()
    }

    fun seekTo(positionMillis: Long) {
        _progressFlow.value = positionMillis
        mediaController?.seekTo(positionMillis)
        syncSourceSeparationPlaybackIfRequested(force = true)
    }

    fun generateExtraInfo() {
        onGenerateExtraInfo(currentSong)
    }

    fun startSourceSeparationForCurrentSong() {
        val song = currentSong
        val runningSongId = sourceSeparationSongId
        if (sourceSeparationJob != null) {
            if (runningSongId != null && runningSongId != song.id) {
                sourceSeparationPauseRequested.set(true)
                sourceSeparationPendingStartSongId = song.id
            }
            return
        }

        if (song == Song.emptySong) {
            _sourceSeparationStateFlow.value = SourceSeparationUiState.Failed(
                songId = song.id,
                songTitle = song.title,
                message = null,
            )
            return
        }

        sourceSeparationCancelRequested.set(false)
        sourceSeparationPauseRequested.set(false)
        sourceSeparationSongId = song.id
        val autoFlacCompressionEnabled = _sourceSeparationAutoFlacCompressionFlow.value
        sourceSeparationJob = viewModelScope.launch(IO) {
            val activeJob = coroutineContext[Job]
            _sourceSeparationStateFlow.value = SourceSeparationUiState.Running(
                songId = song.id,
                songTitle = song.title,
            )
            try {
                sourceSeparationEngine.separateSongToWav(
                    song = song,
                    promoteCompletedStems = autoFlacCompressionEnabled,
                    onProgress = { progress ->
                        _sourceSeparationStateFlow.value = SourceSeparationUiState.Running(
                            songId = song.id,
                            songTitle = song.title,
                            completedWindows = progress.completedWindows,
                            totalWindows = progress.totalWindows,
                            percent = progress.percent,
                            stage = progress.stage,
                            sourceDecodeDiagnostics = progress.sourceDecodeDiagnostics?.toDisplayText(),
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
                                )
                            },
                        )
                        if (currentSong.id == song.id) {
                            syncSourceSeparationPlaybackIfRequested()
                        }
                    },
                    onPrepared = {
                        if (currentSong.id == song.id) {
                            refreshCurrentSourceSeparationCacheAvailable(song)
                        }
                        if (_sourceSeparationBlendModeFlow.value == SourceSeparationBlendMode.PerSong) {
                            migrateTemporaryPerSongSourceSeparationBlend(song)
                        }
                    },
                    playbackPositionMsProvider = {
                        progress.takeIf {
                            currentSong.id == song.id && it != C.TIME_UNSET
                        }
                    },
                    shouldPause = {
                        sourceSeparationPauseRequested.get() ||
                                currentSong.id != song.id ||
                                activeJob?.isActive != true
                    },
                    shouldCancel = {
                        sourceSeparationCancelRequested.get() || activeJob?.isActive != true
                    },
                )
                if (_sourceSeparationBlendModeFlow.value == SourceSeparationBlendMode.PerSong) {
                    savePerSongSourceSeparationBlend(
                        song = song,
                        blend = _sourceSeparationPlaybackStateFlow.value.blend,
                    )
                }
                _sourceSeparationStateFlow.value = SourceSeparationUiState.Completed(
                    songId = song.id,
                    songTitle = song.title,
                )
                if (currentSong.id == song.id) {
                    refreshCurrentSourceSeparationCacheAvailable(song)
                }
                requestSourceSeparationTemporaryCacheCleanup()
            } catch (_: SourceSeparationPausedException) {
                _sourceSeparationStateFlow.value = SourceSeparationUiState.Idle
                if (currentSong.id == song.id) {
                    refreshCurrentSourceSeparationCacheAvailable(song)
                }
            } catch (_: CancellationException) {
                _sourceSeparationStateFlow.value = SourceSeparationUiState.Canceled(
                    songId = song.id,
                    songTitle = song.title,
                )
            } catch (error: Throwable) {
                Log.e(TAG, "Source separation failed", error)
                _sourceSeparationStateFlow.value = SourceSeparationUiState.Failed(
                    songId = song.id,
                    songTitle = song.title,
                    message = error.message,
                )
            } finally {
                val pendingStartSongId = sourceSeparationPendingStartSongId
                sourceSeparationJob = null
                sourceSeparationSongId = null
                sourceSeparationPendingStartSongId = null
                sourceSeparationCancelRequested.set(false)
                sourceSeparationPauseRequested.set(false)
                _sourceSeparationPendingActionFlow.value = null
                if (pendingStartSongId != null && currentSong.id == pendingStartSongId) {
                    startSourceSeparationForCurrentSong()
                }
            }
        }
    }

    fun cancelSourceSeparation() {
        sourceSeparationCancelRequested.set(true)
        sourceSeparationJob?.cancel()
    }

    fun pauseSourceSeparation() {
        if (sourceSeparationJob?.isActive == true) {
            _sourceSeparationPendingActionFlow.value = SourceSeparationPendingAction.Pause
        }
        sourceSeparationPauseRequested.set(true)
    }

    fun deleteSourceSeparationCacheForCurrentSong() {
        val song = currentSong
        if (song == Song.emptySong) return
        viewModelScope.launch(IO) {
            if (sourceSeparationSongId == song.id) {
                _sourceSeparationPendingActionFlow.value = SourceSeparationPendingAction.DeleteCache
                sourceSeparationPauseRequested.set(true)
                sourceSeparationJob?.join()
            }
            val deleted = runCatching {
                sourceSeparationEngine.deleteCacheForSong(song)
            }.getOrDefault(false)
            if (currentSong.id == song.id) {
                if (deleted) {
                    _sourceSeparationStateFlow.value = SourceSeparationUiState.Idle
                    refreshCurrentSourceSeparationCacheAvailable(song)
                    syncSourceSeparationPlaybackIfRequested(force = true)
                }
                _sourceSeparationPendingActionFlow.value = null
            }
        }
    }

    fun tryFlacCompressionForCurrentSong() {
        if (sourceSeparationFlacPromotionJob?.isActive == true) return
        val song = currentSong
        if (song == Song.emptySong) return

        sourceSeparationFlacPromotionJob = viewModelScope.launch(IO) {
            _sourceSeparationPendingActionFlow.value = SourceSeparationPendingAction.PromoteFlac
            try {
                runCatching {
                    sourceSeparationEngine.promoteCompletedStemsForSong(song)
                }.onSuccess { manifest ->
                    if (manifest != null && currentSong.id == song.id) {
                        refreshCurrentSourceSeparationCacheAvailable(song)
                        syncSourceSeparationPlaybackIfRequested(force = true)
                    }
                }.onFailure { error ->
                    Log.w(TAG, "Failed to promote source separation stems to FLAC", error)
                }
            } finally {
                if (currentSong.id == song.id) {
                    _sourceSeparationPendingActionFlow.value = null
                }
                sourceSeparationFlacPromotionJob = null
            }
        }
    }

    private fun pauseSourceSeparationIfSongChanged(song: Song) {
        val runningSongId = sourceSeparationSongId ?: return
        if (song.id != runningSongId) {
            sourceSeparationPauseRequested.set(true)
        }
    }

    private fun refreshCurrentSourceSeparationCacheAvailable(song: Song = currentSong) {
        viewModelScope.launch(IO) {
            val cacheState = if (song == Song.emptySong) {
                SourceSeparationCacheUiState.NotStarted
            } else {
                runCatching {
                    sourceSeparationEngine.cacheStatusForSong(song).toUiState()
                }.getOrDefault(SourceSeparationCacheUiState.NotStarted)
            }
            if (currentSong.id == song.id) {
                _currentSourceSeparationCacheStateFlow.value = cacheState
                _currentSourceSeparationCacheAvailableFlow.value =
                    cacheState != SourceSeparationCacheUiState.NotStarted
            }
        }
    }

    fun clearSourceSeparationStatus() {
        if (_sourceSeparationStateFlow.value !is SourceSeparationUiState.Running) {
            _sourceSeparationStateFlow.value = SourceSeparationUiState.Idle
        }
    }

    fun runWindowDecodeExperimentForCurrentSong() {
        if (sourceSeparationWindowDecodeExperimentJob?.isActive == true) return
        val song = currentSong
        if (song == Song.emptySong) {
            _sourceSeparationWindowDecodeExperimentStateFlow.value =
                SourceSeparationWindowDecodeExperimentUiState.Failed("No playable song is selected.")
            return
        }
        val positionMs = progress.takeIf { it != C.TIME_UNSET } ?: 0L
        sourceSeparationWindowDecodeExperimentJob = viewModelScope.launch(IO) {
            _sourceSeparationWindowDecodeExperimentStateFlow.value =
                SourceSeparationWindowDecodeExperimentUiState.Running(
                    stage = "Starting",
                    completedSteps = 0,
                    totalSteps = 0,
                    percent = 0,
                )
            try {
                val result = sourceSeparationEngine.runWindowDecodeExperiment(
                    song = song,
                    playbackPositionMs = positionMs,
                    onProgress = { progress ->
                        _sourceSeparationWindowDecodeExperimentStateFlow.value =
                            SourceSeparationWindowDecodeExperimentUiState.Running(
                                stage = progress.stage,
                                completedSteps = progress.completedSteps,
                                totalSteps = progress.totalSteps,
                                percent = progress.percent,
                                probeIndex = progress.probeIndex,
                                probeCount = progress.probeCount,
                            )
                    },
                )
                _sourceSeparationWindowDecodeExperimentStateFlow.value =
                    SourceSeparationWindowDecodeExperimentUiState.Completed(
                        reportPath = result.reportFile.absolutePath,
                        fullDecodeMs = result.fullDecodeMs,
                        probeCount = result.probes.size,
                        totalWindowDecodeMs = result.totalLocalDecodeMs,
                        worstOffsetFrames = result.worstSongTimelineSummary.offsetFrames,
                        worstMeanAbsoluteError = result.worstSongTimelineSummary.meanAbsoluteError,
                    )
            } catch (_: CancellationException) {
                _sourceSeparationWindowDecodeExperimentStateFlow.value =
                    SourceSeparationWindowDecodeExperimentUiState.Idle
            } catch (error: Throwable) {
                Log.e(TAG, "Window decode experiment failed", error)
                _sourceSeparationWindowDecodeExperimentStateFlow.value =
                    SourceSeparationWindowDecodeExperimentUiState.Failed(error.message)
            } finally {
                sourceSeparationWindowDecodeExperimentJob = null
            }
        }
    }

    fun setSourceSeparationPlaybackEnabled(enabled: Boolean, blend: Float? = null) {
        val normalizedBlend = blend?.coerceIn(0f, 1f)
        val rememberPerSong = _sourceSeparationRememberPerSongFlow.value
        preferences.edit {
            putBoolean(KEY_SOURCE_SEPARATION_PLAYBACK_ENABLED, enabled)
            if (normalizedBlend != null && !rememberPerSong) {
                putFloat(KEY_SOURCE_SEPARATION_GLOBAL_BLEND, normalizedBlend)
            }
        }

        val mode = sourceSeparationBlendMode(
            playbackEnabled = enabled,
            rememberPerSong = rememberPerSong,
        )
        _sourceSeparationBlendModeFlow.value = mode

        if (enabled) {
            applySourceSeparationSettingsForSong(
                song = currentSong,
                showMessage = true,
                fallbackBlend = normalizedBlend,
            )
        } else {
            sourceSeparationSettingsApplyJob?.cancel()
            sourceSeparationSettingsApplyJob = null
            requestSourceSeparationPlaybackEnabled(
                enabled = false,
                blend = normalizedBlend,
                showMessage = true,
            )
        }
    }

    fun setSourceSeparationRememberPerSongEnabled(enabled: Boolean) {
        preferences.edit {
            putBoolean(KEY_SOURCE_SEPARATION_REMEMBER_PER_SONG, enabled)
        }
        _sourceSeparationRememberPerSongFlow.value = enabled

        val playbackEnabled = preferences.getBoolean(
            KEY_SOURCE_SEPARATION_PLAYBACK_ENABLED,
            false,
        )
        val mode = sourceSeparationBlendMode(
            playbackEnabled = playbackEnabled,
            rememberPerSong = enabled,
        )
        _sourceSeparationBlendModeFlow.value = mode

        if (playbackEnabled) {
            applySourceSeparationSettingsForSong(
                song = currentSong,
                showMessage = true,
            )
        }
    }

    fun setSourceSeparationBlend(blend: Float) {
        val normalizedBlend = blend.coerceIn(0f, 1f)
        updateSourceSeparationBlendState(normalizedBlend)
        when (_sourceSeparationBlendModeFlow.value) {
            SourceSeparationBlendMode.PerSong -> {
                val song = currentSong
                if (song != Song.emptySong) {
                    viewModelScope.launch(IO) {
                        savePerSongSourceSeparationBlend(song, normalizedBlend)
                    }
                }
            }
            SourceSeparationBlendMode.Global,
            SourceSeparationBlendMode.Off -> {
                preferences.edit {
                    putFloat(KEY_SOURCE_SEPARATION_GLOBAL_BLEND, normalizedBlend)
                }
            }
        }
        viewModelScope.launch {
            val args = Bundle().apply {
                putFloat(Playback.EXTRA_SOURCE_SEPARATION_BLEND, normalizedBlend)
            }
            val result = sendSourceSeparationPlaybackCommand(
                action = Playback.SET_SOURCE_SEPARATION_BLEND,
                args = args,
            )
            updateSourceSeparationPlaybackState(result)
        }
    }

    private fun syncSourceSeparationPlaybackIfRequested(force: Boolean = false) {
        val playbackState = _sourceSeparationPlaybackStateFlow.value
        val currentSongId = currentSong.id
        val currentSongPlaybackReady =
            playbackState.enabled &&
                    !playbackState.processing &&
                    playbackState.songId == currentSongId
        if (_sourceSeparationBlendModeFlow.value == SourceSeparationBlendMode.Off ||
            (!force && currentSongPlaybackReady) ||
            sourceSeparationPlaybackSyncJob?.isActive == true
        ) {
            return
        }

        sourceSeparationPlaybackSyncJob = viewModelScope.launch {
            val latestPlaybackState = _sourceSeparationPlaybackStateFlow.value
            val latestSongId = currentSong.id
            val latestSongPlaybackReady =
                latestPlaybackState.enabled &&
                        !latestPlaybackState.processing &&
                        latestPlaybackState.songId == latestSongId
            if (_sourceSeparationBlendModeFlow.value == SourceSeparationBlendMode.Off ||
                (!force && latestSongPlaybackReady)
            ) {
                return@launch
            }

            runCatching {
                sendSourceSeparationPlaybackCommand(
                    action = Playback.SYNC_SOURCE_SEPARATION_PLAYBACK,
                    args = Bundle().apply {
                        putBoolean(
                            Playback.EXTRA_SOURCE_SEPARATION_ALLOW_NEW_SESSION,
                            true,
                        )
                    },
                )
            }.onSuccess { result ->
                updateSourceSeparationPlaybackState(result)
            }.onFailure { error ->
                Log.w(TAG, "Failed to sync source separation playback", error)
            }
        }
    }

    fun setSourceSeparationBlendMode(mode: SourceSeparationBlendMode) {
        when (mode) {
            SourceSeparationBlendMode.Off -> setSourceSeparationPlaybackEnabled(false)
            SourceSeparationBlendMode.Global -> {
                setSourceSeparationRememberPerSongEnabled(false)
                setSourceSeparationPlaybackEnabled(true)
            }
            SourceSeparationBlendMode.PerSong -> {
                setSourceSeparationRememberPerSongEnabled(true)
                setSourceSeparationPlaybackEnabled(true)
            }
        }
    }

    fun setSourceSeparationAutoFlacCompressionEnabled(enabled: Boolean) {
        preferences.edit {
            putBoolean(SOURCE_SEPARATION_AUTO_FLAC_COMPRESSION, enabled)
        }
        _sourceSeparationAutoFlacCompressionFlow.value = enabled
    }

    fun setSourceSeparationShowSnackbarProgressEnabled(enabled: Boolean) {
        preferences.edit {
            putBoolean(SOURCE_SEPARATION_SHOW_SNACKBAR_PROGRESS, enabled)
        }
        _sourceSeparationShowSnackbarProgressFlow.value = enabled
    }

    private fun applySourceSeparationSettingsForSong(
        song: Song,
        showMessage: Boolean,
        fallbackBlend: Float? = null,
    ) {
        sourceSeparationSettingsApplyJob?.cancel()
        val mode = _sourceSeparationBlendModeFlow.value
        if (mode == SourceSeparationBlendMode.Off || song == Song.emptySong) {
            sourceSeparationSettingsApplyJob = null
            return
        }

        sourceSeparationSettingsApplyJob = viewModelScope.launch {
            val blend = sourceSeparationBlendForSong(
                mode = mode,
                song = song,
                fallbackBlend = fallbackBlend,
            )
            updateSourceSeparationBlendState(blend)
            val result = sendSourceSeparationPlaybackEnabledCommand(
                enabled = true,
                blend = blend,
                showMessage = showMessage,
            )
            updateSourceSeparationPlaybackState(result)
        }
    }

    private suspend fun sourceSeparationBlendForSong(
        mode: SourceSeparationBlendMode,
        song: Song,
        fallbackBlend: Float?,
    ): Float {
        return when (mode) {
            SourceSeparationBlendMode.Off,
            SourceSeparationBlendMode.Global -> {
                fallbackBlend
                    ?: readSourceSeparationGlobalBlend()
            }
            SourceSeparationBlendMode.PerSong -> {
                withContext(IO) {
                    val persistedBlend = runCatching {
                        sourceSeparationEngine.separatedPlaybackBlendForSong(song)
                    }.getOrNull()
                    persistedBlend
                        ?: readTemporaryPerSongSourceSeparationBlend(song)
                            ?.also { blend -> migrateTemporaryPerSongSourceSeparationBlend(song, blend) }
                        ?: DEFAULT_SOURCE_SEPARATION_BLEND
                }
            }
        }.coerceIn(0f, 1f)
    }

    private fun requestSourceSeparationPlaybackEnabled(
        enabled: Boolean,
        blend: Float? = null,
        showMessage: Boolean,
    ) {
        viewModelScope.launch {
            val result = sendSourceSeparationPlaybackEnabledCommand(
                enabled = enabled,
                blend = blend,
                showMessage = showMessage,
            )
            updateSourceSeparationPlaybackState(result)
        }
    }

    private fun requestSourceSeparationTemporaryCacheCleanup() {
        val song = currentSong
        viewModelScope.launch {
            runCatching {
                sendSourceSeparationPlaybackCommand(
                    action = Playback.CLEAN_SOURCE_SEPARATION_TEMPORARY_CACHE,
                    args = Bundle.EMPTY,
                )
                if (currentSong.id == song.id) {
                    refreshCurrentSourceSeparationCacheAvailable(song)
                }
            }.onFailure { error ->
                Log.w(TAG, "Failed to clean source separation temporary cache", error)
            }
        }
    }

    private suspend fun sendSourceSeparationPlaybackEnabledCommand(
        enabled: Boolean,
        blend: Float?,
        showMessage: Boolean,
    ): SessionResult {
        val args = Bundle().apply {
            putBoolean(Playback.EXTRA_SOURCE_SEPARATION_ENABLED, enabled)
            putBoolean(Playback.EXTRA_SOURCE_SEPARATION_SHOW_MESSAGE, showMessage)
            putBoolean(
                Playback.EXTRA_SOURCE_SEPARATION_AUTO_SYNC_ON_TRANSITION,
                _sourceSeparationBlendModeFlow.value != SourceSeparationBlendMode.PerSong,
            )
            if (blend != null) {
                putFloat(Playback.EXTRA_SOURCE_SEPARATION_BLEND, blend.coerceIn(0f, 1f))
            }
        }
        return sendSourceSeparationPlaybackCommand(
            action = Playback.SET_SOURCE_SEPARATION_PLAYBACK_ENABLED,
            args = args,
        )
    }

    private fun updateSourceSeparationBlendState(blend: Float) {
        val normalizedBlend = blend.coerceIn(0f, 1f)
        val current = _sourceSeparationPlaybackStateFlow.value
        if (current.blend != normalizedBlend) {
            _sourceSeparationPlaybackStateFlow.value = current.copy(
                blend = normalizedBlend,
            )
        }
    }

    private fun savePerSongSourceSeparationBlend(song: Song, blend: Float): Boolean {
        val normalizedBlend = blend.coerceIn(0f, 1f)
        writeTemporaryPerSongSourceSeparationBlend(song, normalizedBlend)
        val saved = runCatching {
            sourceSeparationEngine.saveSeparatedPlaybackBlendForSong(
                song = song,
                blend = normalizedBlend,
            )
        }.getOrDefault(false)
        if (saved) {
            removeTemporaryPerSongSourceSeparationBlend(song)
        }
        return saved
    }

    private fun migrateTemporaryPerSongSourceSeparationBlend(song: Song): Boolean {
        val blend = readTemporaryPerSongSourceSeparationBlend(song) ?: return false
        return migrateTemporaryPerSongSourceSeparationBlend(song, blend)
    }

    private fun migrateTemporaryPerSongSourceSeparationBlend(song: Song, blend: Float): Boolean {
        val saved = runCatching {
            sourceSeparationEngine.saveSeparatedPlaybackBlendForSong(
                song = song,
                blend = blend.coerceIn(0f, 1f),
            )
        }.getOrDefault(false)
        if (saved) {
            removeTemporaryPerSongSourceSeparationBlend(song)
        }
        return saved
    }

    private fun readTemporaryPerSongSourceSeparationBlend(song: Song): Float? {
        val key = temporaryPerSongSourceSeparationBlendKey(song)
        return if (preferences.contains(key)) {
            preferences.getFloat(key, DEFAULT_SOURCE_SEPARATION_BLEND).coerceIn(0f, 1f)
        } else {
            null
        }
    }

    private fun writeTemporaryPerSongSourceSeparationBlend(song: Song, blend: Float) {
        preferences.edit {
            putFloat(temporaryPerSongSourceSeparationBlendKey(song), blend.coerceIn(0f, 1f))
        }
    }

    private fun removeTemporaryPerSongSourceSeparationBlend(song: Song) {
        preferences.edit {
            remove(temporaryPerSongSourceSeparationBlendKey(song))
        }
    }

    private fun temporaryPerSongSourceSeparationBlendKey(song: Song): String {
        val identity = "${song.id}|${song.uri}|${song.data}"
        return "$KEY_SOURCE_SEPARATION_TEMP_PER_SONG_BLEND.${sha256Hex(identity)}"
    }

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.encodeToByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun readSourceSeparationBlendMode(): SourceSeparationBlendMode {
        return sourceSeparationBlendMode(
            playbackEnabled = preferences.getBoolean(
                KEY_SOURCE_SEPARATION_PLAYBACK_ENABLED,
                false,
            ),
            rememberPerSong = readSourceSeparationRememberPerSong(),
        )
    }

    private fun readSourceSeparationRememberPerSong(): Boolean {
        return preferences.getBoolean(KEY_SOURCE_SEPARATION_REMEMBER_PER_SONG, false)
    }

    private fun readSourceSeparationGlobalBlend(): Float {
        return preferences.getFloat(
            KEY_SOURCE_SEPARATION_GLOBAL_BLEND,
            DEFAULT_SOURCE_SEPARATION_BLEND,
        ).coerceIn(0f, 1f)
    }

    private fun readSourceSeparationAutoFlacCompression(): Boolean {
        return preferences.getBoolean(SOURCE_SEPARATION_AUTO_FLAC_COMPRESSION, true)
    }

    private fun readSourceSeparationShowSnackbarProgress(): Boolean {
        return preferences.getBoolean(SOURCE_SEPARATION_SHOW_SNACKBAR_PROGRESS, false)
    }

    private fun sourceSeparationBlendMode(
        playbackEnabled: Boolean,
        rememberPerSong: Boolean,
    ): SourceSeparationBlendMode {
        return when {
            !playbackEnabled -> SourceSeparationBlendMode.Off
            rememberPerSong -> SourceSeparationBlendMode.PerSong
            else -> SourceSeparationBlendMode.Global
        }
    }

    fun updateSourceSeparationPlaybackState(args: Bundle) {
        updateSourceSeparationPlaybackState(
            SessionResult(SessionResult.RESULT_SUCCESS, args)
        )
        refreshCurrentSourceSeparationCacheAvailable()
    }

    private suspend fun sendSourceSeparationPlaybackCommand(
        action: String,
        args: Bundle,
    ): SessionResult {
        val controller = mediaController
            ?: return SessionResult(
                SessionError.ERROR_INVALID_STATE,
                Bundle().apply {
                    putString(
                        Playback.EXTRA_SOURCE_SEPARATION_MESSAGE,
                        "Playback is not connected.",
                    )
                },
            )

        return runCatching {
            controller.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args).await()
        }.getOrElse { error ->
            SessionResult(
                SessionError.ERROR_UNKNOWN,
                Bundle().apply {
                    putString(Playback.EXTRA_SOURCE_SEPARATION_MESSAGE, error.message)
                },
            )
        }
    }

    private fun updateSourceSeparationPlaybackState(result: SessionResult) {
        val extras = result.extras
        val current = _sourceSeparationPlaybackStateFlow.value
        val message = extras.getString(Playback.EXTRA_SOURCE_SEPARATION_MESSAGE)
            ?: if (result.resultCode == SessionResult.RESULT_SUCCESS) null
            else "Source separation playback is unavailable."

        _sourceSeparationPlaybackStateFlow.value = SourceSeparationPlaybackUiState(
            enabled = if (extras.containsKey(Playback.EXTRA_SOURCE_SEPARATION_ENABLED)) {
                extras.getBoolean(Playback.EXTRA_SOURCE_SEPARATION_ENABLED)
            } else {
                current.enabled
            },
            processing = if (extras.containsKey(Playback.EXTRA_SOURCE_SEPARATION_PROCESSING)) {
                extras.getBoolean(Playback.EXTRA_SOURCE_SEPARATION_PROCESSING)
            } else {
                current.processing
            },
            blend = if (extras.containsKey(Playback.EXTRA_SOURCE_SEPARATION_BLEND)) {
                extras.getFloat(Playback.EXTRA_SOURCE_SEPARATION_BLEND)
            } else {
                current.blend
            },
            songId = if (extras.containsKey(Playback.EXTRA_SOURCE_SEPARATION_SONG_ID)) {
                extras.getLong(Playback.EXTRA_SOURCE_SEPARATION_SONG_ID)
            } else if (extras.containsKey(Playback.EXTRA_SOURCE_SEPARATION_ENABLED) &&
                !extras.getBoolean(Playback.EXTRA_SOURCE_SEPARATION_ENABLED)
            ) {
                null
            } else {
                current.songId
            },
            message = message,
        )
    }

    fun playSongAt(newPosition: Int) {
        mediaController?.let { controller ->
            if (controller.playbackState == Player.STATE_READY) {
                if (!controller.currentTimeline.isEmpty) {
                    controller.seekToDefaultPosition(position.getIndexForPosition(newPosition))
                }
            }
        }
    }

    fun playMediaId(mediaId: String, shuffleMode: Boolean = false) {
        mediaController?.let { controller ->
            controller.shuffleModeEnabled = shuffleMode
            controller.setMediaItem(
                MediaItem.Builder()
                    .setMediaId(mediaId)
                    .build(),
                true
            )
            controller.prepare()
            controller.play()
        }
    }

    fun openQueue(
        queue: List<Song>,
        position: Int = 0,
        startPlaying: Boolean = true,
        shuffleMode: OpenShuffleMode = OpenShuffleMode.Remember
    ) = viewModelScope.launch {
        mediaController?.let { controller ->
            var shuffleModeEnabled = controller.shuffleModeEnabled
            if (!preferences.getBoolean(REMEMBER_SHUFFLE_MODE, true)) {
                shuffleModeEnabled = false
            }
            val mediaItems = withContext(IO) { queue.toMediaItems() }
            val shuffleMode = when (shuffleMode) {
                OpenShuffleMode.On -> true
                OpenShuffleMode.Off -> false
                OpenShuffleMode.Remember -> shuffleModeEnabled
            }
            if (mediaItems.isNotEmpty()) {
                controller.shuffleModeEnabled = shuffleMode
                controller.setMediaItems(mediaItems, position, C.TIME_UNSET)
                controller.playWhenReady = startPlaying
                controller.prepare()
            }
        }
    }

    fun openAndShuffleQueue(queue: List<Song>) = viewModelScope.launch {
        mediaController?.let { controller ->
            val mediaItems = withContext(IO) { queue.toMediaItems() }
            if (mediaItems.isNotEmpty()) {
                controller.shuffleModeEnabled = true
                controller.setMediaItems(mediaItems, true)
                controller.prepare()
                controller.play()
            }
        }
    }

    fun openShuffle(
        providers: List<SongProvider>,
        mode: GroupShuffleMode,
        sortMode: SongSortMode
    ) = liveData {
        val mediaItems = withContext(IO) {
            shuffleManager.shuffleByProvider(providers, mode, sortMode).toMediaItems()
        }
        if (mediaItems.isNotEmpty()) {
            mediaController?.let { controller ->
                controller.shuffleModeEnabled = true
                val resultFuture = controller.sendCustomCommand(
                    SessionCommand(Playback.SET_UNSHUFFLED_ORDER, Bundle.EMPTY),
                    Bundle.EMPTY
                )
                val result = runCatching { resultFuture.await() }
                    .getOrDefault(SessionResult(SessionError.ERROR_UNKNOWN))
                if (result.resultCode == SessionResult.RESULT_SUCCESS) {
                    controller.setMediaItems(mediaItems)
                    controller.prepare()
                    controller.play()
                }
            }
            emit(true)
        } else {
            emit(false)
        }
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    fun openSpecialShuffle(songs: List<Song>, mode: SpecialShuffleMode) = viewModelScope.launch {
        if (shuffleOperationState.value.isIdle) {
            _shuffleOperationState.value = ShuffleOperationState(mode, ShuffleOperationState.Status.InProgress)
            val mediaItems = withContext(IO) {
                shuffleManager.applySmartShuffle(songs, mode).toMediaItems()
            }
            if (mediaItems.isNotEmpty()) {
                mediaController?.let { controller ->
                    controller.shuffleModeEnabled = true
                    val resultFuture = controller.sendCustomCommand(
                        SessionCommand(Playback.SET_UNSHUFFLED_ORDER, Bundle.EMPTY),
                        Bundle.EMPTY
                    )
                    val result = runCatching { resultFuture.await() }
                        .getOrDefault(SessionResult(SessionError.ERROR_UNKNOWN))
                    if (result.resultCode == SessionResult.RESULT_SUCCESS) {
                        controller.setMediaItems(mediaItems, true)
                        controller.prepare()
                        controller.play()
                    }
                }
            }
            _shuffleOperationState.value = ShuffleOperationState()
        }
    }

    fun openPlaylist(
        playlist: PlaylistEntity,
        startPlaying: Boolean = true,
        shuffleMode: OpenShuffleMode = OpenShuffleMode.Off
    ) = viewModelScope.launch {
        val songs = withContext(IO) {
            repository.playlistSongs(playlist.playListId).toSongs()
        }
        openQueue(songs, startPlaying = startPlaying, shuffleMode = shuffleMode)
    }

    fun openSongs(
        position: Int,
        songs: List<Song>,
        behavior: SongClickBehavior
    ) = viewModelScope.launch {
        when (behavior) {
            SongClickBehavior.PlayWholeList -> openQueue(songs, position)

            SongClickBehavior.PlayOnlyThisSong -> {
                val selectedSong = songs.getOrNull(position)
                if (selectedSong != null) {
                    openQueue(listOf(selectedSong))
                }
            }

            SongClickBehavior.QueueNext -> {
                val selectedSong = songs.getOrNull(position)
                if (selectedSong != null) {
                    queueNext(selectedSong)
                }
            }

            SongClickBehavior.EnqueueAtEnd -> {
                val selectedSong = songs.getOrNull(position)
                if (selectedSong != null) {
                    enqueue(selectedSong)
                }
            }
        }
    }

    fun queueNext(song: Song) {
        mediaController?.let { controller ->
            if (controller.currentTimeline.isEmpty) {
                openQueue(listOf(song), startPlaying = false)
            } else {
                var nextIndex = position.getIndexForPosition(position.next)
                if (nextIndex == C.INDEX_UNSET) {
                    nextIndex = controller.mediaItemCount
                }
                controller.addMediaItem(nextIndex, song.toMediaItem())
            }
        }
    }

    fun queueNext(songs: List<Song>) {
        mediaController?.let { controller ->
            if (controller.currentTimeline.isEmpty) {
                openQueue(songs, startPlaying = false)
            } else {
                var nextIndex = position.getIndexForPosition(position.next)
                if (nextIndex == C.INDEX_UNSET) {
                    nextIndex = controller.mediaItemCount
                }
                controller.addMediaItems(nextIndex, songs.toMediaItems())
            }
        }
    }

    fun enqueue(song: Song, toPosition: Int = -1) {
        mediaController?.let { controller ->
            if (controller.currentTimeline.isEmpty) {
                openQueue(listOf(song), startPlaying = false)
            } else {
                val toIndex = position.getIndexForPosition(toPosition)
                if (toPosition >= 0 && toIndex >= 0) {
                    controller.addMediaItem(toIndex, song.toMediaItem())
                } else {
                    controller.addMediaItem(song.toMediaItem())
                }
            }
        }
    }

    fun enqueue(songs: List<Song>) {
        mediaController?.let { controller ->
            if (controller.currentTimeline.isEmpty) {
                openQueue(songs, startPlaying = false)
            } else {
                controller.addMediaItems(songs.toMediaItems())
            }
        }
    }

    fun clearQueue(behavior: QueueClearingBehavior = Preferences.clearQueueAction) {
        when (behavior) {
            QueueClearingBehavior.RemoveAllSongs -> {
                mediaController?.clearMediaItems()
            }

            QueueClearingBehavior.RemoveAllSongsExceptCurrentlyPlaying -> {
                mediaController?.let { controller ->
                    if (controller.mediaItemCount > 1) {
                        val currentItem = controller.currentMediaItemIndex
                        if (currentItem == C.INDEX_UNSET) return
                        if (currentItem == 0) {
                            controller.removeMediaItems(1, controller.mediaItemCount)
                        } else {
                            controller.removeMediaItems(0, currentItem)
                            if (controller.mediaItemCount > 1) {
                                controller.removeMediaItems(1, controller.mediaItemCount)
                            }
                        }
                    }
                }
            }
        }
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    fun stopAt(stopPosition: Int) = liveData {
        mediaController?.let { controller ->
            if (stopPosition >= 0 && stopPosition < controller.mediaItemCount) {
                val stopIndex = position.getIndexForPosition(stopPosition)
                val mediaItem = controller.getMediaItemAt(stopIndex)
                val resultFuture = controller.sendCustomCommand(
                    SessionCommand(
                        Playback.SET_STOP_POSITION,
                        Bundle().apply {
                            putInt("index", stopIndex)
                        }
                    ),
                    Bundle.EMPTY
                )
                val result = runCatching { resultFuture.await() }
                    .getOrDefault(SessionResult(SessionError.ERROR_UNKNOWN))
                if (result.resultCode == SessionResult.RESULT_SUCCESS) {
                    val canceled = result.extras.getBoolean("canceled", false)
                    emit(mediaItem.mediaMetadata.title to canceled)
                } else {
                    emit(null to false)
                }
            }
        }
    }

    fun moveSong(fromPosition: Int, toPosition: Int) {
        mediaController?.moveMediaItem(
            position.getIndexForPosition(fromPosition),
            position.getIndexForPosition(toPosition)
        )
    }

    fun moveToNextPosition(fromPosition: Int) {
        moveSong(fromPosition, position.next)
    }

    fun removePosition(positionToRemove: Int) {
        mediaController?.removeMediaItem(position.getIndexForPosition(positionToRemove))
    }

    fun restorePlayback() = viewModelScope.launch {
        mediaController?.let { controller ->
            if (!controller.playWhenReady) {
                val resultFuture = controller.sendCustomCommand(
                    SessionCommand(Playback.RESTORE_PLAYBACK, Bundle.EMPTY),
                    Bundle.EMPTY
                )
                val result = runCatching { resultFuture.await() }
                    .getOrDefault(SessionResult(SessionError.ERROR_UNKNOWN))
                if (result.resultCode == SessionResult.RESULT_SUCCESS) {
                    controller.playWhenReady = true
                }
            }
        }
    }

    fun generatePlayerScheme(
        context: Context,
        mode: PlayerColorScheme.Mode,
        color: PaletteColor
    ) = viewModelScope.launch(Dispatchers.Default) {
        val currentScheme = colorScheme.mode.takeIf { it == PlayerColorSchemeMode.AppTheme }
        if (currentScheme == mode && colorScheme.appThemeToken.isValid(context))
            return@launch

        val result = runCatching {
            PlayerColorScheme.autoColorScheme(context, color, mode)
        }
        if (result.isSuccess) {
            _colorScheme.value = result.getOrThrow()
        } else if (result.isFailure) {
            Log.e(TAG, "Failed to load color scheme", result.exceptionOrNull())
        }
    }

    fun saveCover(song: Song) = liveData(IO) {
        emit(SaveCoverResult(true))
        val uri = albumCoverSaver.saveArtwork(song)
        emit(SaveCoverResult(false, uri))
    }

    companion object {
        private const val TAG = "PlayerViewModel"
        private const val DEFAULT_SOURCE_SEPARATION_BLEND = 0.5f
        private const val KEY_SOURCE_SEPARATION_PLAYBACK_ENABLED =
            "source_separation.playback_enabled"
        private const val KEY_SOURCE_SEPARATION_REMEMBER_PER_SONG =
            "source_separation.remember_per_song"
        private const val KEY_SOURCE_SEPARATION_GLOBAL_BLEND =
            "source_separation.global_blend"
        private const val KEY_SOURCE_SEPARATION_TEMP_PER_SONG_BLEND =
            "source_separation.per_song_blend.pending"
    }
}

sealed class SourceSeparationUiState {
    data object Idle : SourceSeparationUiState()

    data class Running(
        val songId: Long,
        val songTitle: String,
        val completedWindows: Int = 0,
        val totalWindows: Int = 0,
        val percent: Int = 0,
        val stage: String? = null,
        val sourceDecodeDiagnostics: String? = null,
        val scheduler: SourceSeparationSchedulerUiState? = null,
    ) : SourceSeparationUiState()

    data class Completed(
        val songId: Long,
        val songTitle: String,
    ) : SourceSeparationUiState()

    data class Canceled(
        val songId: Long,
        val songTitle: String,
    ) : SourceSeparationUiState()

    data class Paused(
        val songId: Long,
        val songTitle: String,
    ) : SourceSeparationUiState()

    data class Failed(
        val songId: Long,
        val songTitle: String,
        val message: String?,
    ) : SourceSeparationUiState()
}

enum class SourceSeparationPendingAction {
    Pause,
    DeleteCache,
    PromoteFlac,
}

sealed class SourceSeparationCacheUiState {
    data object NotStarted : SourceSeparationCacheUiState()
    data class Partial(
        val readySegments: Int,
        val totalSegments: Int,
    ) : SourceSeparationCacheUiState()
    data class CompletedWithTemporaryFiles(
        val canPromoteCompletedStems: Boolean,
    ) : SourceSeparationCacheUiState()
    data class Completed(
        val canPromoteCompletedStems: Boolean,
    ) : SourceSeparationCacheUiState()
}

private fun SourceSeparationCacheStatus.toUiState(): SourceSeparationCacheUiState {
    return when (this) {
        SourceSeparationCacheStatus.NotStarted -> SourceSeparationCacheUiState.NotStarted
        is SourceSeparationCacheStatus.Partial -> SourceSeparationCacheUiState.Partial(
            readySegments = readySegments,
            totalSegments = totalSegments,
        )
        is SourceSeparationCacheStatus.CompletedWithTemporaryFiles ->
            SourceSeparationCacheUiState.CompletedWithTemporaryFiles(
                canPromoteCompletedStems = canPromoteCompletedStems,
            )
        is SourceSeparationCacheStatus.Completed -> SourceSeparationCacheUiState.Completed(
            canPromoteCompletedStems = canPromoteCompletedStems,
        )
    }
}

data class SourceSeparationPlaybackUiState(
    val enabled: Boolean = false,
    val processing: Boolean = false,
    val blend: Float = 0.5f,
    val songId: Long? = null,
    val message: String? = null,
)

data class SourceSeparationSchedulerUiState(
    val playbackSegmentIndex: Int?,
    val playbackSegmentState: String?,
    val nextSegmentIndex: Int?,
    val nextSegmentState: String?,
    val processingSegmentIndex: Int,
    val priority: String?,
    val readySegments: Int,
    val totalSegments: Int,
)

sealed class SourceSeparationWindowDecodeExperimentUiState {
    data object Idle : SourceSeparationWindowDecodeExperimentUiState()
    data class Running(
        val stage: String,
        val completedSteps: Int,
        val totalSteps: Int,
        val percent: Int,
        val probeIndex: Int? = null,
        val probeCount: Int? = null,
    ) : SourceSeparationWindowDecodeExperimentUiState()
    data class Completed(
        val reportPath: String,
        val fullDecodeMs: Long,
        val probeCount: Int,
        val totalWindowDecodeMs: Long,
        val worstOffsetFrames: Int,
        val worstMeanAbsoluteError: Double,
    ) : SourceSeparationWindowDecodeExperimentUiState()
    data class Failed(
        val message: String?,
    ) : SourceSeparationWindowDecodeExperimentUiState()
}

enum class SourceSeparationBlendMode {
    Off,
    Global,
    PerSong
}
