package com.mardous.booming.ui.screen.player

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
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
import com.mardous.booming.separation.cache.SourceSeparationCacheEntry
import com.mardous.booming.separation.cache.SourceSeparationCacheEntryFormat
import com.mardous.booming.separation.cache.SourceSeparationCacheEntryState
import com.mardous.booming.separation.model.MdxModelVariant
import com.mardous.booming.separation.model.SourceSeparationModelDownloadProgress
import com.mardous.booming.separation.model.SourceSeparationModelRepository
import com.mardous.booming.separation.model.SourceSeparationModelSource
import com.mardous.booming.separation.model.SourceSeparationModelState
import com.mardous.booming.util.DEFAULT_SOURCE_SEPARATION_AVERAGE_WINDOW_MS
import com.mardous.booming.util.DEFAULT_SOURCE_SEPARATION_AUTO_CACHE_CLEANUP
import com.mardous.booming.util.DEFAULT_SOURCE_SEPARATION_AUTO_CACHE_CLEANUP_COMPLETED_LIMIT
import com.mardous.booming.util.DEFAULT_SOURCE_SEPARATION_AUTO_CACHE_CLEANUP_PARTIAL_LIMIT
import com.mardous.booming.util.NOW_PLAYING_EXTRA_INFO
import com.mardous.booming.util.Preferences
import com.mardous.booming.util.REMEMBER_SHUFFLE_MODE
import com.mardous.booming.util.DEFAULT_SOURCE_SEPARATION_HYDRATED_MIXED_OUTPUT_PREROLL_MS
import com.mardous.booming.util.DEFAULT_SOURCE_SEPARATION_MIXED_OUTPUT_PREROLL_MS
import com.mardous.booming.util.DEFAULT_SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT
import com.mardous.booming.util.MAX_SOURCE_SEPARATION_MIXED_OUTPUT_PREROLL_MS
import com.mardous.booming.util.MAX_SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT
import com.mardous.booming.util.MIN_SOURCE_SEPARATION_AUTO_CACHE_CLEANUP_LIMIT
import com.mardous.booming.util.MIN_SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT
import com.mardous.booming.util.DEFAULT_SOURCE_SEPARATION_AUTO_START
import com.mardous.booming.util.SOURCE_SEPARATION_AUTO_CACHE_CLEANUP
import com.mardous.booming.util.SOURCE_SEPARATION_AUTO_CACHE_CLEANUP_COMPLETED_LIMIT
import com.mardous.booming.util.SOURCE_SEPARATION_AUTO_CACHE_CLEANUP_PARTIAL_LIMIT
import com.mardous.booming.util.SOURCE_SEPARATION_AUTO_START
import com.mardous.booming.util.SOURCE_SEPARATION_AUTO_FLAC_COMPRESSION
import com.mardous.booming.util.SOURCE_SEPARATION_HYDRATED_MIXED_OUTPUT_PREROLL_MS
import com.mardous.booming.util.SOURCE_SEPARATION_MIXED_OUTPUT_PREROLL_MS
import com.mardous.booming.util.SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT
import com.mardous.booming.util.SOURCE_SEPARATION_SHOW_SNACKBAR_MESSAGES
import com.mardous.booming.util.SOURCE_SEPARATION_SHOW_SNACKBAR_PROGRESS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
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
import java.util.concurrent.atomic.AtomicLong

const val QUEUE_DEBOUNCE = 100L

@OptIn(FlowPreview::class)
@androidx.annotation.OptIn(UnstableApi::class)
class PlayerViewModel(
    private val preferences: SharedPreferences,
    private val repository: Repository,
    private val albumCoverSaver: AlbumCoverSaver,
    private val sourceSeparationEngine: SourceSeparationEngine,
    private val sourceSeparationModelRepository: SourceSeparationModelRepository,
    private val sourceSeparationForegroundWorkerCoordinator:
    SourceSeparationForegroundWorkerCoordinator,
) : ViewModel(), Player.Listener, SourceSeparationForegroundWorkerCallbacks {

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

    private var sourceSeparationSettingsApplyJob: Job? = null
    private var sourceSeparationAutoStartJob: Job? = null
    private var sourceSeparationPreStartJob: Job? = null
    private var sourceSeparationPlaybackSyncJob: Job? = null
    private var sourceSeparationBlendPreviewJob: Job? = null
    private var sourceSeparationBlendPreviewPending: Float? = null
    private var sourceSeparationWindowDecodeExperimentJob: Job? = null
    private var sourceSeparationFlacPromotionJob: Job? = null
    private var sourceSeparationFlacPromotionRunningSongId: Long? = null
    private val sourceSeparationFlacPromotionCancelGeneration = AtomicLong(0L)
    private val sourceSeparationFlacPromotionLock = Any()
    private val sourceSeparationFlacPromotionRequests =
        linkedMapOf<Long, SourceSeparationFlacPromotionRequest>()

    val sourceSeparationStateFlow =
        sourceSeparationForegroundWorkerCoordinator.workerStateFlow

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

    private val _sourceSeparationCacheManagementStateFlow =
        MutableStateFlow(SourceSeparationCacheManagementUiState())
    val sourceSeparationCacheManagementStateFlow =
        _sourceSeparationCacheManagementStateFlow.asStateFlow()

    private val _sourceSeparationModelStateFlow =
        MutableStateFlow(sourceSeparationModelRepository.modelState().toUiState())
    val sourceSeparationModelStateFlow =
        _sourceSeparationModelStateFlow.asStateFlow()

    private val _sourceSeparationModelManagementEventFlow =
        MutableSharedFlow<Unit>(
            replay = 0,
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
    val sourceSeparationModelManagementEventFlow =
        _sourceSeparationModelManagementEventFlow.asSharedFlow()

    private val _sourceSeparationFlacPromotionStateFlow =
        MutableStateFlow(SourceSeparationFlacPromotionUiState())
    val sourceSeparationFlacPromotionStateFlow =
        _sourceSeparationFlacPromotionStateFlow.asStateFlow()

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

    private val _sourceSeparationAutoStartFlow =
        MutableStateFlow(readSourceSeparationAutoStart())
    val sourceSeparationAutoStartFlow =
        _sourceSeparationAutoStartFlow.asStateFlow()

    private val _sourceSeparationAutoFlacCompressionFlow =
        MutableStateFlow(readSourceSeparationAutoFlacCompression())
    val sourceSeparationAutoFlacCompressionFlow =
        _sourceSeparationAutoFlacCompressionFlow.asStateFlow()

    private val _sourceSeparationShowSnackbarProgressFlow =
        MutableStateFlow(readSourceSeparationShowSnackbarProgress())
    val sourceSeparationShowSnackbarProgressFlow =
        _sourceSeparationShowSnackbarProgressFlow.asStateFlow()

    private val _sourceSeparationShowSnackbarMessagesFlow =
        MutableStateFlow(readSourceSeparationShowSnackbarMessages())
    val sourceSeparationShowSnackbarMessagesFlow =
        _sourceSeparationShowSnackbarMessagesFlow.asStateFlow()

    private val _sourceSeparationMixedOutputPrerollMsFlow =
        MutableStateFlow(readSourceSeparationMixedOutputPrerollMs())
    val sourceSeparationMixedOutputPrerollMsFlow =
        _sourceSeparationMixedOutputPrerollMsFlow.asStateFlow()

    private val _sourceSeparationHydratedMixedOutputPrerollMsFlow =
        MutableStateFlow(readSourceSeparationHydratedMixedOutputPrerollMs())
    val sourceSeparationHydratedMixedOutputPrerollMsFlow =
        _sourceSeparationHydratedMixedOutputPrerollMsFlow.asStateFlow()

    private val _sourceSeparationPlaybackReadyWindowCountFlow =
        MutableStateFlow(readSourceSeparationPlaybackReadyWindowCount())
    val sourceSeparationPlaybackReadyWindowCountFlow =
        _sourceSeparationPlaybackReadyWindowCountFlow.asStateFlow()

    private val _sourceSeparationAutoCacheCleanupFlow =
        MutableStateFlow(readSourceSeparationAutoCacheCleanup())
    val sourceSeparationAutoCacheCleanupFlow =
        _sourceSeparationAutoCacheCleanupFlow.asStateFlow()

    private val _sourceSeparationAutoCacheCleanupPartialLimitFlow =
        MutableStateFlow(readSourceSeparationAutoCacheCleanupPartialLimit())
    val sourceSeparationAutoCacheCleanupPartialLimitFlow =
        _sourceSeparationAutoCacheCleanupPartialLimitFlow.asStateFlow()

    private val _sourceSeparationAutoCacheCleanupCompletedLimitFlow =
        MutableStateFlow(readSourceSeparationAutoCacheCleanupCompletedLimit())
    val sourceSeparationAutoCacheCleanupCompletedLimitFlow =
        _sourceSeparationAutoCacheCleanupCompletedLimitFlow.asStateFlow()

    private val internalJobs = mutableListOf<Job>()

    init {
        SourceSeparationForegroundWorkerDebugBridge.register(this)
        sourceSeparationForegroundWorkerCoordinator.attachCallbacks(this)
        observeSourceSeparationForegroundWorkerCoordinator()
    }

    override fun onCleared() {
        progressObserver.stop()
        SourceSeparationForegroundWorkerDebugBridge.unregister(this)
        sourceSeparationForegroundWorkerCoordinator.detachCallbacks(this)
        sourceSeparationSettingsApplyJob?.cancel()
        sourceSeparationAutoStartJob?.cancel()
        sourceSeparationPreStartJob?.cancel()
        sourceSeparationBlendPreviewJob?.cancel()
        sourceSeparationWindowDecodeExperimentJob?.cancel()
        sourceSeparationFlacPromotionJob?.cancel()
        synchronized(sourceSeparationFlacPromotionLock) {
            sourceSeparationFlacPromotionRequests.clear()
            sourceSeparationFlacPromotionRunningSongId = null
            updateSourceSeparationFlacPromotionStateLocked()
        }
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
                .onEach { (queue, position) ->
                    updateCurrentAndNextSong(queue, position)
                }
                .launchIn(viewModelScope)

            internalJobs += currentSongFlow
                .distinctUntilChangedBy { it.id }
                .onEach { song ->
                    refreshCurrentSourceSeparationCacheAvailable(song)
                    applySourceSeparationSettingsForSong(
                        song = song,
                        showMessage = false,
                    )
                    maybeAutoStartSourceSeparationForSong(song)
                    maybePreStartNextSourceSeparation()
                }
                .launchIn(viewModelScope)

            internalJobs += nextSongFlow
                .distinctUntilChangedBy { it.id }
                .onEach { maybePreStartNextSourceSeparation() }
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

    private fun observeSourceSeparationForegroundWorkerCoordinator() {
        sourceSeparationForegroundWorkerCoordinator.eventFlow
            .onEach { event ->
                when (event) {
                    is SourceSeparationForegroundPlaybackEvent.SongChanged ->
                        handleSourceSeparationForegroundSongChanged(
                            song = event.song,
                            positionMs = event.positionMs,
                            durationMs = event.durationMs,
                            isPlaying = event.isPlaying,
                            sourceSeparationBlend = event.sourceSeparationBlend,
                        )

                    is SourceSeparationForegroundPlaybackEvent.PositionChanged ->
                        handleSourceSeparationForegroundPositionChanged(
                            positionMs = event.positionMs,
                            durationMs = event.durationMs,
                            isPlaying = event.isPlaying,
                            sourceSeparationBlend = event.sourceSeparationBlend,
                        )
                }
            }
            .launchIn(viewModelScope)
    }

    private fun handleSourceSeparationForegroundSongChanged(
        song: Song,
        positionMs: Long,
        durationMs: Long,
        isPlaying: Boolean,
        sourceSeparationBlend: Float,
    ) {
        if (song == Song.emptySong) return
        _currentSongFlow.value = song
        _progressFlow.value = positionMs
        _durationFlow.value = durationMs
        _isPlayingFlow.value = isPlaying
        updateSourceSeparationBlendState(sourceSeparationBlend)
        refreshCurrentSourceSeparationCacheAvailable(song)
        applySourceSeparationSettingsForSong(
            song = song,
            showMessage = false,
            fallbackBlend = sourceSeparationBlend,
        )
        maybeAutoStartSourceSeparationForSong(song)
    }

    private fun handleSourceSeparationForegroundPositionChanged(
        positionMs: Long,
        durationMs: Long,
        isPlaying: Boolean,
        sourceSeparationBlend: Float,
    ) {
        _progressFlow.value = positionMs
        _durationFlow.value = durationMs
        _isPlayingFlow.value = isPlaying
        updateSourceSeparationBlendState(sourceSeparationBlend)
        syncSourceSeparationPlaybackIfRequested(force = true)
        maybeAutoStartSourceSeparationForCurrentSong()
    }

    private fun updateCurrentAndNextSong(
        queue: List<Song> = _queueFlow.value,
        position: QueuePosition = _positionFlow.value,
    ) {
        _currentSongFlow.value = queue.getOrElse(position.current) { Song.emptySong }
        _nextSongFlow.value = queue.getOrElse(position.next) { Song.emptySong }
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
            maybePreStartNextSourceSeparation()
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
        if (!ensureSourceSeparationModelReady(openManagement = true)) {
            return
        }
        val song = currentSong
        sourceSeparationForegroundWorkerCoordinator.requestSong(song)
    }

    private suspend fun handleSourceSeparationModelLoadFailure(message: String) {
        sourceSeparationSettingsApplyJob?.cancel()
        sourceSeparationSettingsApplyJob = null
        sourceSeparationAutoStartJob?.cancel()
        sourceSeparationAutoStartJob = null
        sourceSeparationPreStartJob?.cancel()
        sourceSeparationPreStartJob = null
        sourceSeparationPlaybackSyncJob?.cancel()
        sourceSeparationPlaybackSyncJob = null
        preferences.edit {
            putBoolean(KEY_SOURCE_SEPARATION_PLAYBACK_ENABLED, false)
        }
        _sourceSeparationBlendModeFlow.value = SourceSeparationBlendMode.Off
        _sourceSeparationModelStateFlow.value =
            sourceSeparationModelRepository.modelState().toUiState().copy(
                errorMessage = message,
            )
        val result = sendSourceSeparationPlaybackEnabledCommand(
            enabled = false,
            blend = _sourceSeparationPlaybackStateFlow.value.blend,
            showMessage = false,
            expectProcessing = false,
        )
        updateSourceSeparationPlaybackState(result)
        openSourceSeparationModelManagement()
    }

    fun cancelSourceSeparation() {
        sourceSeparationForegroundWorkerCoordinator.cancel()
    }

    fun pauseSourceSeparation() {
        if (sourceSeparationForegroundWorkerCoordinator.isWorkerActive()) {
            _sourceSeparationPendingActionFlow.value = SourceSeparationPendingAction.Pause
        }
        sourceSeparationForegroundWorkerCoordinator.pauseCurrentSong(currentSong)
    }

    fun deleteSourceSeparationCacheForCurrentSong() {
        val song = currentSong
        traceSourceSeparationPlaybackUserActionMarker(
            "deleteCurrent.userAction songId=${song.id} title=${song.title}"
        )
        if (song == Song.emptySong) return
        viewModelScope.launch(IO) {
            _sourceSeparationPendingActionFlow.value = SourceSeparationPendingAction.DeleteCache
            traceSourceSeparationPlaybackTestMarker(
                "deleteCurrent.request songId=${song.id} title=${song.title}"
            )
            try {
                if (sourceSeparationForegroundWorkerCoordinator.runningSongId() == song.id) {
                    traceSourceSeparationPlaybackTestMarker(
                        "deleteCurrent.waitSeparation songId=${song.id}"
                    )
                    _sourceSeparationPendingActionFlow.value =
                        SourceSeparationPendingAction.DeleteCacheWaitingWindow
                    sourceSeparationForegroundWorkerCoordinator.suppressAndPauseSong(song.id)
                    sourceSeparationForegroundWorkerCoordinator.waitForWorkerToLeaveSong(song.id)
                    _sourceSeparationPendingActionFlow.value = SourceSeparationPendingAction.DeleteCache
                }
                val waitsForFlacPromotion = isSourceSeparationFlacPromotionActive(song.id)
                cancelSourceSeparationFlacPromotionForSong(song.id)
                if (waitsForFlacPromotion) {
                    traceSourceSeparationPlaybackTestMarker(
                        "deleteCurrent.waitFlac songId=${song.id}"
                    )
                    _sourceSeparationPendingActionFlow.value =
                        SourceSeparationPendingAction.DeleteCacheWaitingFlac
                    waitForSourceSeparationFlacPromotionToStop(song.id)
                }
                val deleted = runCatching {
                    sourceSeparationEngine.deleteCacheForSong(song)
                }.getOrDefault(false)
                traceSourceSeparationPlaybackTestMarker(
                    "deleteCurrent.deleted songId=${song.id} deleted=$deleted"
                )
                if (currentSong.id == song.id && deleted) {
                    sourceSeparationForegroundWorkerCoordinator.clearStatusIfNotRunning()
                    refreshCurrentSourceSeparationCacheAvailable(song)
                    handleCurrentSourceSeparationCacheDeleted()
                }
            } finally {
                if (_sourceSeparationPendingActionFlow.value.isDeleteCacheAction) {
                    _sourceSeparationPendingActionFlow.value = null
                }
            }
        }
    }

    fun tryFlacCompressionForCurrentSong() {
        val song = currentSong
        if (song == Song.emptySong) return
        startSourceSeparationFlacPromotion(song)
    }

    private fun startSourceSeparationFlacPromotion(song: Song) {
        synchronized(sourceSeparationFlacPromotionLock) {
            sourceSeparationFlacPromotionRequests[song.id] =
                SourceSeparationFlacPromotionRequest(song = song)
            updateSourceSeparationFlacPromotionStateLocked()
            if (sourceSeparationFlacPromotionJob?.isActive != true) {
                sourceSeparationFlacPromotionJob = createSourceSeparationFlacPromotionWorker()
            }
        }
    }

    private fun createSourceSeparationFlacPromotionWorker(): Job {
        val cancelGeneration = sourceSeparationFlacPromotionCancelGeneration.get()
        return viewModelScope.launch(IO) {
            val activeJob = coroutineContext[Job] ?: return@launch
            try {
                while (activeJob.isActive) {
                    val request = nextSourceSeparationFlacPromotionRequest()
                        ?: break
                    val shouldCancelRequest = {
                        !activeJob.isActive ||
                                sourceSeparationFlacPromotionCancelGeneration.get() !=
                                cancelGeneration ||
                                !isSourceSeparationFlacPromotionCurrent(request.song.id)
                    }
                    try {
                        runCatching {
                            sourceSeparationEngine.promoteCompletedStemsForSong(
                                song = request.song,
                                shouldCancel = shouldCancelRequest,
                            )
                        }.onSuccess { manifest ->
                            if (manifest != null) {
                                if (currentSong.id == request.song.id) {
                                    refreshCurrentSourceSeparationCacheAvailable(request.song)
                                    syncSourceSeparationPlaybackIfRequested(force = true)
                                }
                                requestSourceSeparationTemporaryCacheCleanup()
                            }
                        }.onFailure { error ->
                            if (error is CancellationException) {
                                Log.d(TAG, "Source separation FLAC promotion canceled")
                            } else {
                                Log.w(TAG, "Failed to promote source separation stems to FLAC", error)
                            }
                        }
                    } finally {
                        finishSourceSeparationFlacPromotionRequest(request)
                    }
                }
            } finally {
                synchronized(sourceSeparationFlacPromotionLock) {
                    sourceSeparationFlacPromotionRunningSongId = null
                    sourceSeparationFlacPromotionJob = null
                    updateSourceSeparationFlacPromotionStateLocked()
                    if (sourceSeparationFlacPromotionRequests.isNotEmpty()) {
                        sourceSeparationFlacPromotionJob =
                            createSourceSeparationFlacPromotionWorker()
                    }
                }
            }
        }
    }

    private fun cancelSourceSeparationFlacPromotionForSong(songId: Long) {
        var shouldCancelWorker = false
        synchronized(sourceSeparationFlacPromotionLock) {
            sourceSeparationFlacPromotionRequests.remove(songId)
            if (sourceSeparationFlacPromotionRunningSongId == songId) {
                sourceSeparationFlacPromotionCancelGeneration.incrementAndGet()
                shouldCancelWorker = true
            }
            updateSourceSeparationFlacPromotionStateLocked()
        }
        if (shouldCancelWorker) {
            sourceSeparationFlacPromotionJob?.cancel()
        }
    }

    fun refreshSourceSeparationCacheManagement() {
        viewModelScope.launch(IO) {
            loadSourceSeparationCacheManagement(pruneFirst = true)
        }
    }

    fun deleteAllSourceSeparationCaches() {
        val entries = _sourceSeparationCacheManagementStateFlow.value.items
        traceSourceSeparationPlaybackUserActionMarker(
            "deleteAll.userAction count=${entries.size} current=${currentSong.id}"
        )
        if (entries.isEmpty()) return
        viewModelScope.launch(IO) {
            _sourceSeparationCacheManagementStateFlow.value =
                _sourceSeparationCacheManagementStateFlow.value.copy(
                    deletingAll = true,
                    deletingEntryIds = entries.mapTo(mutableSetOf()) { it.id },
                )
            try {
                entries.forEach { entry ->
                    deleteSourceSeparationCacheEntryInternal(entry)
                }
            } finally {
                _sourceSeparationCacheManagementStateFlow.value =
                    _sourceSeparationCacheManagementStateFlow.value.copy(
                        deletingAll = false,
                        deletingEntryIds = emptySet(),
                    )
                refreshSourceSeparationCacheManagement()
            }
        }
    }

    fun deleteSourceSeparationCacheEntry(entryId: String) {
        traceSourceSeparationPlaybackUserActionMarker(
            "deleteEntry.userAction id=$entryId current=${currentSong.id}"
        )
        val entry = _sourceSeparationCacheManagementStateFlow
            .value
            .items
            .firstOrNull { it.id == entryId }
            ?: return
        viewModelScope.launch(IO) {
            markSourceSeparationCacheEntryDeleting(entryId, deleting = true)
            try {
                deleteSourceSeparationCacheEntryInternal(entry)
            } finally {
                markSourceSeparationCacheEntryDeleting(entryId, deleting = false)
                refreshSourceSeparationCacheManagement()
            }
        }
    }

    private suspend fun deleteSourceSeparationCacheEntryInternal(
        entry: SourceSeparationCacheManagementItem,
    ) {
        traceSourceSeparationPlaybackTestMarker(
            "deleteEntry.request id=${entry.id} songId=${entry.songId} current=${currentSong.id}"
        )
        if (sourceSeparationForegroundWorkerCoordinator.runningSongId() == entry.songId) {
            traceSourceSeparationPlaybackTestMarker(
                "deleteEntry.waitSeparation id=${entry.id} songId=${entry.songId}"
            )
            sourceSeparationForegroundWorkerCoordinator.suppressAndPauseSong(entry.songId)
            sourceSeparationForegroundWorkerCoordinator.waitForWorkerToLeaveSong(entry.songId)
        }
        val waitsForFlacPromotion = isSourceSeparationFlacPromotionActive(entry.songId)
        cancelSourceSeparationFlacPromotionForSong(entry.songId)
        if (waitsForFlacPromotion) {
            traceSourceSeparationPlaybackTestMarker(
                "deleteEntry.waitFlac id=${entry.id} songId=${entry.songId}"
            )
            waitForSourceSeparationFlacPromotionToStop(entry.songId)
        }
        val deleted = runCatching {
            sourceSeparationEngine.deleteCacheEntry(entry.id)
        }.getOrDefault(false)
        traceSourceSeparationPlaybackTestMarker(
            "deleteEntry.deleted id=${entry.id} songId=${entry.songId} deleted=$deleted"
        )
        if (deleted && currentSong.id == entry.songId) {
            sourceSeparationForegroundWorkerCoordinator.clearStatusIfNotRunning()
            refreshCurrentSourceSeparationCacheAvailable(currentSong)
            handleCurrentSourceSeparationCacheDeleted()
        }
    }

    private fun markSourceSeparationCacheEntryDeleting(
        entryId: String,
        deleting: Boolean,
    ) {
        val current = _sourceSeparationCacheManagementStateFlow.value
        _sourceSeparationCacheManagementStateFlow.value = current.copy(
            deletingEntryIds = if (deleting) {
                current.deletingEntryIds + entryId
            } else {
                current.deletingEntryIds - entryId
            },
        )
    }

    private suspend fun waitForSourceSeparationFlacPromotionToStop(songId: Long) {
        while (isSourceSeparationFlacPromotionActive(songId)) {
            sourceSeparationFlacPromotionJob?.join()
        }
    }

    private fun isSourceSeparationFlacPromotionCurrent(songId: Long): Boolean {
        return synchronized(sourceSeparationFlacPromotionLock) {
            sourceSeparationFlacPromotionRunningSongId == songId
        }
    }

    private fun isSourceSeparationFlacPromotionActive(songId: Long): Boolean {
        return synchronized(sourceSeparationFlacPromotionLock) {
            sourceSeparationFlacPromotionRunningSongId == songId ||
                    sourceSeparationFlacPromotionRequests.containsKey(songId)
        }
    }

    private fun nextSourceSeparationFlacPromotionRequest(): SourceSeparationFlacPromotionRequest? {
        return synchronized(sourceSeparationFlacPromotionLock) {
            val iterator = sourceSeparationFlacPromotionRequests.entries.iterator()
            if (!iterator.hasNext()) {
                null
            } else {
                val request = iterator.next().value
                iterator.remove()
                sourceSeparationFlacPromotionRunningSongId = request.song.id
                updateSourceSeparationFlacPromotionStateLocked()
                request
            }
        }
    }

    private fun finishSourceSeparationFlacPromotionRequest(
        request: SourceSeparationFlacPromotionRequest,
    ) {
        synchronized(sourceSeparationFlacPromotionLock) {
            if (sourceSeparationFlacPromotionRunningSongId == request.song.id) {
                sourceSeparationFlacPromotionRunningSongId = null
            }
            updateSourceSeparationFlacPromotionStateLocked()
        }
    }

    private fun updateSourceSeparationFlacPromotionStateLocked() {
        _sourceSeparationFlacPromotionStateFlow.value = SourceSeparationFlacPromotionUiState(
            runningSongId = sourceSeparationFlacPromotionRunningSongId,
            queuedSongIds = sourceSeparationFlacPromotionRequests.keys.toSet(),
        )
    }

    fun sourceSeparationDebugStatus(): String {
        val playbackState = _sourceSeparationPlaybackStateFlow.value
        return buildString {
            append(sourceSeparationForegroundWorkerCoordinator.debugStatus())
            append(" playbackEnabled=").append(playbackState.enabled)
            append(" playbackProcessing=").append(playbackState.processing)
            append(" playbackSong=").append(playbackState.songId)
        }
    }

    fun sourceSeparationDebugWindowSamples(): String {
        return sourceSeparationForegroundWorkerCoordinator.windowSamples()
    }

    fun clearSourceSeparationDebugWindowSamples() {
        sourceSeparationForegroundWorkerCoordinator.clearWindowSamples()
    }

    override fun onSourceSeparationWorkerProgress(song: Song) {
        if (currentSong.id == song.id) {
            syncSourceSeparationPlaybackIfRequested(force = true)
        }
    }

    override fun onSourceSeparationWorkerPrepared(song: Song) {
        if (currentSong.id == song.id) {
            refreshCurrentSourceSeparationCacheAvailable(song)
            maybePreStartNextSourceSeparation()
        }
    }

    override fun onSourceSeparationWorkerCompleted(
        song: Song,
        shouldPromoteCompletedStems: Boolean,
    ) {
        if (currentSong.id == song.id) {
            refreshCurrentSourceSeparationCacheAvailable(song)
        }
        pruneSourceSeparationCachesAndRefresh()
        requestSourceSeparationTemporaryCacheCleanup()
        if (shouldPromoteCompletedStems) {
            startSourceSeparationFlacPromotion(song)
        }
    }

    override fun onSourceSeparationWorkerPaused(song: Song) {
        if (currentSong.id == song.id) {
            refreshCurrentSourceSeparationCacheAvailable(song)
        }
    }

    override fun onSourceSeparationWorkerModelLoadFailed(message: String) {
        viewModelScope.launch {
            handleSourceSeparationModelLoadFailure(message)
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
                if (cacheState.isCompleted) {
                    maybePreStartNextSourceSeparation()
                }
            }
        }
    }

    fun clearSourceSeparationStatus() {
        sourceSeparationForegroundWorkerCoordinator.clearStatusIfNotRunning()
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
        if (enabled && !ensureSourceSeparationModelReady(openManagement = true)) {
            preferences.edit {
                putBoolean(KEY_SOURCE_SEPARATION_PLAYBACK_ENABLED, false)
                if (normalizedBlend != null && !rememberPerSong) {
                    putFloat(KEY_SOURCE_SEPARATION_GLOBAL_BLEND, normalizedBlend)
                }
            }
            _sourceSeparationBlendModeFlow.value = SourceSeparationBlendMode.Off
            updateSourceSeparationBlendState(
                normalizedBlend ?: _sourceSeparationPlaybackStateFlow.value.blend,
            )
            return
        }

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
            val song = currentSong
            if (song != Song.emptySong) {
                sourceSeparationForegroundWorkerCoordinator.clearAutoStartSuppressionForSong(song.id)
            }
            applySourceSeparationSettingsForSong(
                song = song,
                showMessage = true,
                fallbackBlend = normalizedBlend,
                trustFallbackBlend = normalizedBlend != null,
            )
            maybePreStartNextSourceSeparation()
        } else {
            sourceSeparationSettingsApplyJob?.cancel()
            sourceSeparationSettingsApplyJob = null
            sourceSeparationPreStartJob?.cancel()
            sourceSeparationPreStartJob = null
            requestSourceSeparationPlaybackEnabled(
                enabled = false,
                blend = normalizedBlend,
                showMessage = true,
            )
        }
    }

    fun refreshSourceSeparationModelState() {
        _sourceSeparationModelStateFlow.value =
            sourceSeparationModelRepository.modelState().toUiState()
    }

    fun openSourceSeparationModelManagement() {
        _sourceSeparationModelManagementEventFlow.tryEmit(Unit)
    }

    fun importSourceSeparationModel(uri: Uri) {
        runSourceSeparationModelAcquisition(importing = true) { _ ->
            sourceSeparationModelRepository.importModel(uri = uri)
        }
    }

    fun downloadPresetSourceSeparationModel() {
        runSourceSeparationModelAcquisition(downloading = true) { onProgress ->
            sourceSeparationModelRepository.downloadPresetModel(
                onProgress = onProgress,
            )
        }
    }

    fun downloadSourceSeparationModel(url: String) {
        val normalizedUrl = url.trim()
        if (normalizedUrl.isBlank()) {
            _sourceSeparationModelStateFlow.value =
                _sourceSeparationModelStateFlow.value.copy(
                    errorMessage = "Model URL cannot be empty.",
            )
            return
        }
        runSourceSeparationModelAcquisition(downloading = true) { onProgress ->
            sourceSeparationModelRepository.downloadModel(
                url = normalizedUrl,
                onProgress = onProgress,
            )
        }
    }

    private fun runSourceSeparationModelAcquisition(
        importing: Boolean = false,
        downloading: Boolean = false,
        block: (onProgress: (SourceSeparationModelDownloadProgress) -> Unit) -> SourceSeparationModelState.Available,
    ) {
        _sourceSeparationModelStateFlow.value =
            _sourceSeparationModelStateFlow.value.copy(
                importing = importing,
                downloading = downloading,
                downloadProgressBytes = 0L,
                downloadTotalBytes = null,
                downloadSourceUrl = null,
                downloadUsingMirror = false,
                downloadMessage = null,
                errorMessage = null,
            )
        viewModelScope.launch(IO) {
            runCatching {
                block(::updateSourceSeparationModelDownloadProgress)
            }.onSuccess { state ->
                _sourceSeparationModelStateFlow.value = state.toUiState()
            }.onFailure { error ->
                Log.w(TAG, "Failed to acquire source separation model", error)
                _sourceSeparationModelStateFlow.value =
                    sourceSeparationModelRepository.modelState().toUiState().copy(
                        importing = false,
                        downloading = false,
                        downloadProgressBytes = 0L,
                        downloadTotalBytes = null,
                        downloadSourceUrl = null,
                        downloadUsingMirror = false,
                        downloadMessage = null,
                        errorMessage = error.message,
                    )
            }
        }
    }

    private fun updateSourceSeparationModelDownloadProgress(
        progress: SourceSeparationModelDownloadProgress,
    ) {
        _sourceSeparationModelStateFlow.value = _sourceSeparationModelStateFlow.value.copy(
            downloading = true,
            importing = false,
            downloadProgressBytes = progress.downloadedBytes,
            downloadTotalBytes = progress.totalBytes,
            downloadSourceUrl = progress.sourceUrl,
            downloadUsingMirror = progress.usingMirror,
            downloadMessage = progress.message,
            errorMessage = null,
        )
    }

    private fun ensureSourceSeparationModelReady(openManagement: Boolean): Boolean {
        val state = sourceSeparationModelRepository.modelState().toUiState()
        _sourceSeparationModelStateFlow.value = state
        if (state.available) return true

        sourceSeparationSettingsApplyJob?.cancel()
        sourceSeparationSettingsApplyJob = null
        sourceSeparationAutoStartJob?.cancel()
        sourceSeparationPreStartJob?.cancel()
        sourceSeparationPreStartJob = null
        sourceSeparationPlaybackSyncJob?.cancel()
        sourceSeparationPlaybackSyncJob = null
        preferences.edit {
            putBoolean(KEY_SOURCE_SEPARATION_PLAYBACK_ENABLED, false)
        }
        _sourceSeparationBlendModeFlow.value = SourceSeparationBlendMode.Off
        if (_sourceSeparationPlaybackStateFlow.value.enabled) {
            requestSourceSeparationPlaybackEnabled(
                enabled = false,
                showMessage = true,
            )
        }
        if (openManagement) {
            openSourceSeparationModelManagement()
        }
        return false
    }

    fun deleteSourceSeparationModel() {
        _sourceSeparationModelStateFlow.value =
            _sourceSeparationModelStateFlow.value.copy(deleting = true, errorMessage = null)
        viewModelScope.launch(IO) {
            runCatching {
                sourceSeparationModelRepository.deleteModel()
            }.onSuccess {
                _sourceSeparationModelStateFlow.value =
                    sourceSeparationModelRepository.modelState().toUiState()
                if (_sourceSeparationBlendModeFlow.value != SourceSeparationBlendMode.Off) {
                    setSourceSeparationPlaybackEnabled(false)
                }
            }.onFailure { error ->
                Log.w(TAG, "Failed to delete source separation model", error)
                _sourceSeparationModelStateFlow.value =
                    sourceSeparationModelRepository.modelState().toUiState().copy(
                        deleting = false,
                        errorMessage = error.message,
                    )
            }
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
            maybePreStartNextSourceSeparation()
        }
    }

    fun setSourceSeparationBlend(blend: Float) {
        val normalizedBlend = blend.coerceIn(0f, 1f)
        sourceSeparationBlendPreviewJob?.cancel()
        sourceSeparationBlendPreviewJob = null
        sourceSeparationBlendPreviewPending = null
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
            maybeAutoStartSourceSeparationForCurrentSong(
                blend = normalizedBlend,
                trustKnownBlend = true,
            )
            maybePreStartNextSourceSeparation()
        }
    }

    fun previewSourceSeparationBlend(blend: Float) {
        if (_sourceSeparationBlendModeFlow.value == SourceSeparationBlendMode.Off) return
        sourceSeparationBlendPreviewPending = blend.coerceIn(0f, 1f)
        if (sourceSeparationBlendPreviewJob?.isActive == true) return

        sourceSeparationBlendPreviewJob = viewModelScope.launch {
            while (true) {
                val previewBlend = sourceSeparationBlendPreviewPending ?: break
                sourceSeparationBlendPreviewPending = null
                val args = Bundle().apply {
                    putFloat(Playback.EXTRA_SOURCE_SEPARATION_BLEND, previewBlend)
                }
                runCatching {
                    sendSourceSeparationPlaybackCommand(
                        action = Playback.SET_SOURCE_SEPARATION_BLEND,
                        args = args,
                    )
                }.onSuccess { result ->
                    if (result.extras.containsKey(Playback.EXTRA_SOURCE_SEPARATION_ENABLED)) {
                        updateSourceSeparationPlaybackState(result)
                    }
                }.onFailure { error ->
                    Log.w(TAG, "Failed to preview source separation blend", error)
                }
                delay(SOURCE_SEPARATION_BLEND_PREVIEW_THROTTLE_MS)
            }
            sourceSeparationBlendPreviewJob = null
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
        if (!ensureSourceSeparationModelReady(openManagement = true)) {
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
                        putBoolean(
                            Playback.EXTRA_SOURCE_SEPARATION_EXPECT_PROCESSING,
                            shouldExpectSourceSeparationProcessingForSync(),
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

    private suspend fun shouldExpectSourceSeparationProcessingForSync(): Boolean {
        if (!_sourceSeparationAutoStartFlow.value) {
            return false
        }
        val mode = _sourceSeparationBlendModeFlow.value
        if (mode == SourceSeparationBlendMode.Off) {
            return false
        }
        val song = currentSong
        if (song == Song.emptySong) {
            return false
        }
        val blend = sourceSeparationForegroundWorkerCoordinator.blendForSong(
            mode = mode,
            song = song,
            fallbackBlend = _sourceSeparationPlaybackStateFlow.value.blend,
        )
        return !isDefaultSourceSeparationBlend(blend)
    }

    fun setSourceSeparationBlendMode(mode: SourceSeparationBlendMode) {
        if (mode != SourceSeparationBlendMode.Off &&
            !ensureSourceSeparationModelReady(openManagement = true)
        ) {
            return
        }
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

    fun setSourceSeparationAutoStartEnabled(enabled: Boolean) {
        preferences.edit {
            putBoolean(SOURCE_SEPARATION_AUTO_START, enabled)
        }
        _sourceSeparationAutoStartFlow.value = enabled
        if (enabled) {
            maybeAutoStartSourceSeparationForCurrentSong(
                blend = _sourceSeparationPlaybackStateFlow.value.blend,
            )
            maybePreStartNextSourceSeparation()
        } else {
            sourceSeparationPreStartJob?.cancel()
            sourceSeparationPreStartJob = null
        }
    }

    fun setSourceSeparationShowSnackbarProgressEnabled(enabled: Boolean) {
        preferences.edit {
            putBoolean(SOURCE_SEPARATION_SHOW_SNACKBAR_PROGRESS, enabled)
        }
        _sourceSeparationShowSnackbarProgressFlow.value = enabled
    }

    fun setSourceSeparationShowSnackbarMessagesEnabled(enabled: Boolean) {
        preferences.edit {
            putBoolean(SOURCE_SEPARATION_SHOW_SNACKBAR_MESSAGES, enabled)
        }
        _sourceSeparationShowSnackbarMessagesFlow.value = enabled
    }

    fun setSourceSeparationMixedOutputPrerollMs(valueMs: Long) {
        val normalized = normalizeSourceSeparationMixedOutputPrerollMs(valueMs)
        preferences.edit {
            putLong(SOURCE_SEPARATION_MIXED_OUTPUT_PREROLL_MS, normalized)
        }
        _sourceSeparationMixedOutputPrerollMsFlow.value = normalized
    }

    fun setSourceSeparationHydratedMixedOutputPrerollMs(valueMs: Long) {
        val normalized = normalizeSourceSeparationMixedOutputPrerollMs(valueMs)
        preferences.edit {
            putLong(SOURCE_SEPARATION_HYDRATED_MIXED_OUTPUT_PREROLL_MS, normalized)
        }
        _sourceSeparationHydratedMixedOutputPrerollMsFlow.value = normalized
    }

    fun setSourceSeparationPlaybackReadyWindowCount(value: Int) {
        val normalized = normalizeSourceSeparationPlaybackReadyWindowCount(value)
        preferences.edit {
            putInt(SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT, normalized)
        }
        _sourceSeparationPlaybackReadyWindowCountFlow.value = normalized
        maybePreStartNextSourceSeparation()
    }

    fun setSourceSeparationAutoCacheCleanupEnabled(enabled: Boolean) {
        preferences.edit {
            putBoolean(SOURCE_SEPARATION_AUTO_CACHE_CLEANUP, enabled)
        }
        _sourceSeparationAutoCacheCleanupFlow.value = enabled
        if (enabled) {
            pruneSourceSeparationCachesAndRefresh()
        }
    }

    fun setSourceSeparationAutoCacheCleanupPartialLimit(value: Int) {
        val normalized = normalizeSourceSeparationAutoCacheCleanupLimit(value)
        preferences.edit {
            putInt(SOURCE_SEPARATION_AUTO_CACHE_CLEANUP_PARTIAL_LIMIT, normalized)
        }
        _sourceSeparationAutoCacheCleanupPartialLimitFlow.value = normalized
        pruneSourceSeparationCachesAndRefresh()
    }

    fun setSourceSeparationAutoCacheCleanupCompletedLimit(value: Int) {
        val normalized = normalizeSourceSeparationAutoCacheCleanupLimit(value)
        preferences.edit {
            putInt(SOURCE_SEPARATION_AUTO_CACHE_CLEANUP_COMPLETED_LIMIT, normalized)
        }
        _sourceSeparationAutoCacheCleanupCompletedLimitFlow.value = normalized
        pruneSourceSeparationCachesAndRefresh()
    }

    private fun applySourceSeparationSettingsForSong(
        song: Song,
        showMessage: Boolean,
        fallbackBlend: Float? = null,
        trustFallbackBlend: Boolean = false,
    ) {
        sourceSeparationSettingsApplyJob?.cancel()
        val mode = _sourceSeparationBlendModeFlow.value
        if (mode == SourceSeparationBlendMode.Off || song == Song.emptySong) {
            sourceSeparationSettingsApplyJob = null
            sourceSeparationAutoStartJob?.cancel()
            return
        }
        if (!ensureSourceSeparationModelReady(openManagement = true)) {
            sourceSeparationSettingsApplyJob = null
            return
        }

        sourceSeparationSettingsApplyJob = viewModelScope.launch {
            val blend = sourceSeparationForegroundWorkerCoordinator.blendForSong(
                mode = mode,
                song = song,
                fallbackBlend = fallbackBlend,
                trustFallbackBlend = trustFallbackBlend,
            )
            updateSourceSeparationBlendState(blend)
            val autoStartDecision =
                sourceSeparationForegroundWorkerCoordinator.autoStartDecision(song, blend)
            if (autoStartDecision.shouldStart && currentSong.id == song.id) {
                startSourceSeparationForCurrentSong()
            }
            if ((autoStartDecision.shouldStart ||
                        autoStartDecision.shouldWaitForProcessingCache) &&
                currentSong.id == song.id
            ) {
                waitForSourceSeparationProcessingCache(song)
            }
            val result = sendSourceSeparationPlaybackEnabledCommand(
                enabled = true,
                blend = blend,
                showMessage = showMessage,
                expectProcessing = autoStartDecision.shouldStart ||
                        autoStartDecision.shouldWaitForProcessingCache,
            )
            updateSourceSeparationPlaybackState(result)
            if (!autoStartDecision.shouldStart && !autoStartDecision.hasCompletedCache) {
                maybeAutoStartSourceSeparationForCurrentSong(blend)
            }
        }
    }

    private fun maybeAutoStartSourceSeparationForCurrentSong(
        blend: Float? = null,
        trustKnownBlend: Boolean = false,
    ) {
        val song = currentSong
        maybeAutoStartSourceSeparationForSong(
            song = song,
            knownBlend = blend,
            trustKnownBlend = trustKnownBlend,
        )
    }

    private fun maybeAutoStartSourceSeparationForSong(
        song: Song,
        knownBlend: Float? = null,
        trustKnownBlend: Boolean = false,
    ) {
        val mode = _sourceSeparationBlendModeFlow.value
        if (!_sourceSeparationAutoStartFlow.value ||
            mode == SourceSeparationBlendMode.Off ||
            song == Song.emptySong
        ) {
            sourceSeparationAutoStartJob?.cancel()
            return
        }
        if (sourceSeparationForegroundWorkerCoordinator.runningSongId() == song.id ||
            sourceSeparationForegroundWorkerCoordinator.pendingSongId() == song.id
        ) {
            return
        }

        sourceSeparationAutoStartJob?.cancel()
        sourceSeparationAutoStartJob = viewModelScope.launch {
            val blend = if (knownBlend != null &&
                (trustKnownBlend || mode != SourceSeparationBlendMode.PerSong)
            ) {
                knownBlend
            } else {
                sourceSeparationForegroundWorkerCoordinator.blendForSong(
                    mode = mode,
                    song = song,
                    fallbackBlend = null,
                )
            }
            if (sourceSeparationForegroundWorkerCoordinator
                    .autoStartDecision(song, blend)
                    .shouldStart &&
                currentSong.id == song.id
            ) {
                startSourceSeparationForCurrentSong()
            }
        }
    }

    private fun maybePreStartNextSourceSeparation() {
        val current = currentSong
        val next = nextSongForSourceSeparationPreStart(current)
        val mode = _sourceSeparationBlendModeFlow.value
        val readyWindowCount = _sourceSeparationPlaybackReadyWindowCountFlow.value
        if (!_sourceSeparationAutoStartFlow.value ||
            mode == SourceSeparationBlendMode.Off ||
            current == Song.emptySong ||
            next == Song.emptySong ||
            current.id == next.id
        ) {
            sourceSeparationPreStartJob?.cancel()
            sourceSeparationPreStartJob = null
            return
        }
        if (sourceSeparationForegroundWorkerCoordinator.runningSongId() == next.id ||
            sourceSeparationForegroundWorkerCoordinator.pendingSongId() == next.id
        ) {
            return
        }

        sourceSeparationPreStartJob?.cancel()
        sourceSeparationPreStartJob = viewModelScope.launch(IO) {
            val latestMode = _sourceSeparationBlendModeFlow.value
            if (!_sourceSeparationAutoStartFlow.value ||
                latestMode == SourceSeparationBlendMode.Off ||
                currentSong.id != current.id ||
                nextSongForSourceSeparationPreStart(currentSong).id != next.id ||
                !sourceSeparationModelRepository.isModelReady()
            ) {
                return@launch
            }

            val nextNeedsSeparatedOutput = when (latestMode) {
                SourceSeparationBlendMode.Off -> false
                SourceSeparationBlendMode.Global ->
                    !isDefaultSourceSeparationBlend(readSourceSeparationGlobalBlend())
                SourceSeparationBlendMode.PerSong ->
                    sourceSeparationForegroundWorkerCoordinator
                        .recordedBlendForSong(next)
                        ?.let { blend -> !isDefaultSourceSeparationBlend(blend) }
                        ?: false
            }
            if (!nextNeedsSeparatedOutput) return@launch

            val currentCacheCompleted = runCatching {
                when (sourceSeparationEngine.cacheStatusForSong(current)) {
                    is SourceSeparationCacheStatus.Completed,
                    is SourceSeparationCacheStatus.CompletedWithTemporaryFiles -> true
                    SourceSeparationCacheStatus.NotStarted,
                    is SourceSeparationCacheStatus.Partial -> false
                }
            }.getOrDefault(false)
            if (!currentCacheCompleted ||
                currentSong.id != current.id ||
                nextSongForSourceSeparationPreStart(currentSong).id != next.id ||
                sourceSeparationForegroundWorkerCoordinator.runningSongId() == next.id ||
                sourceSeparationForegroundWorkerCoordinator.pendingSongId() == next.id
            ) {
                return@launch
            }

            sourceSeparationForegroundWorkerCoordinator.preStartSong(
                song = next,
                readyWindowCount = readyWindowCount,
            )
        }
    }

    private fun nextSongForSourceSeparationPreStart(current: Song): Song {
        val next = nextSong
        if (next != Song.emptySong) return next
        if (_repeatModeFlow.value != Player.REPEAT_MODE_ALL) return Song.emptySong

        val queue = _queueFlow.value
        val position = _positionFlow.value
        if (queue.isEmpty() ||
            position.current !in queue.indices ||
            position.current != queue.lastIndex ||
            current.id != queue[position.current].id
        ) {
            return Song.emptySong
        }

        return queue.firstOrNull() ?: Song.emptySong
    }

    private suspend fun waitForSourceSeparationProcessingCache(song: Song) {
        repeat(SOURCE_SEPARATION_AUTO_START_PROCESSING_CACHE_WAIT_ATTEMPTS) {
            if (currentSong.id != song.id) return
            val processingCacheAvailable = runCatching {
                withContext(IO) {
                    when (sourceSeparationEngine.cacheStatusForSong(song)) {
                        SourceSeparationCacheStatus.NotStarted -> false
                        is SourceSeparationCacheStatus.Partial,
                        is SourceSeparationCacheStatus.Completed,
                        is SourceSeparationCacheStatus.CompletedWithTemporaryFiles -> true
                    }
                }
            }.getOrDefault(false)
            if (processingCacheAvailable) return
            delay(SOURCE_SEPARATION_AUTO_START_PROCESSING_CACHE_WAIT_MS)
        }
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
                expectProcessing = false,
            )
            updateSourceSeparationPlaybackState(result)
        }
    }

    private fun traceSourceSeparationPlaybackUserActionMarker(marker: String) {
        viewModelScope.launch {
            traceSourceSeparationPlaybackTestMarker(marker)
        }
    }

    private suspend fun traceSourceSeparationPlaybackTestMarker(marker: String) {
        runCatching {
            sendSourceSeparationPlaybackCommand(
                action = Playback.TRACE_SOURCE_SEPARATION_PLAYBACK_MARKER,
                args = Bundle().apply {
                    putString(Playback.EXTRA_SOURCE_SEPARATION_TRACE_MARKER, marker)
                },
            )
        }.onFailure { error ->
            Log.w(TAG, "Failed to write source separation playback trace marker", error)
        }
    }

    private suspend fun handleCurrentSourceSeparationCacheDeleted() {
        traceSourceSeparationPlaybackTestMarker(
            "cacheDeleted.notify currentSongId=${currentSong.id}"
        )
        sourceSeparationSettingsApplyJob?.cancel()
        sourceSeparationSettingsApplyJob = null
        preferences.edit {
            putBoolean(KEY_SOURCE_SEPARATION_PLAYBACK_ENABLED, false)
        }
        _sourceSeparationBlendModeFlow.value = SourceSeparationBlendMode.Off
        val disableResult = sendSourceSeparationPlaybackEnabledCommand(
            enabled = false,
            blend = _sourceSeparationPlaybackStateFlow.value.blend,
            showMessage = true,
            expectProcessing = false,
        )
        updateSourceSeparationPlaybackState(disableResult)

        val result = sendSourceSeparationPlaybackCommand(
            action = Playback.NOTIFY_SOURCE_SEPARATION_CACHE_DELETED,
            args = Bundle.EMPTY,
        )
        updateSourceSeparationPlaybackState(result)
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
        expectProcessing: Boolean = false,
    ): SessionResult {
        val args = Bundle().apply {
            putBoolean(Playback.EXTRA_SOURCE_SEPARATION_ENABLED, enabled)
            putBoolean(Playback.EXTRA_SOURCE_SEPARATION_SHOW_MESSAGE, showMessage)
            putBoolean(Playback.EXTRA_SOURCE_SEPARATION_EXPECT_PROCESSING, expectProcessing)
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
        val playbackEnabled = preferences.getBoolean(
            KEY_SOURCE_SEPARATION_PLAYBACK_ENABLED,
            false,
        )
        if (playbackEnabled && !sourceSeparationModelRepository.isModelReady()) {
            preferences.edit {
                putBoolean(KEY_SOURCE_SEPARATION_PLAYBACK_ENABLED, false)
            }
            return SourceSeparationBlendMode.Off
        }
        return sourceSeparationBlendMode(
            playbackEnabled = playbackEnabled,
            rememberPerSong = readSourceSeparationRememberPerSong(),
        )
    }

    private fun readSourceSeparationRememberPerSong(): Boolean {
        return preferences.getBoolean(KEY_SOURCE_SEPARATION_REMEMBER_PER_SONG, true)
    }

    private fun readSourceSeparationGlobalBlend(): Float {
        return preferences.getFloat(
            KEY_SOURCE_SEPARATION_GLOBAL_BLEND,
            DEFAULT_SOURCE_SEPARATION_BLEND,
        ).coerceIn(0f, 1f)
    }

    private fun readSourceSeparationAutoStart(): Boolean {
        return preferences.getBoolean(
            SOURCE_SEPARATION_AUTO_START,
            DEFAULT_SOURCE_SEPARATION_AUTO_START,
        )
    }

    private fun readSourceSeparationAutoFlacCompression(): Boolean {
        return preferences.getBoolean(SOURCE_SEPARATION_AUTO_FLAC_COMPRESSION, true)
    }

    private fun readSourceSeparationShowSnackbarProgress(): Boolean {
        return preferences.getBoolean(SOURCE_SEPARATION_SHOW_SNACKBAR_PROGRESS, false)
    }

    private fun readSourceSeparationShowSnackbarMessages(): Boolean {
        return preferences.getBoolean(SOURCE_SEPARATION_SHOW_SNACKBAR_MESSAGES, false)
    }

    private fun readSourceSeparationMixedOutputPrerollMs(): Long {
        return normalizeSourceSeparationMixedOutputPrerollMs(
            preferences.getLong(
                SOURCE_SEPARATION_MIXED_OUTPUT_PREROLL_MS,
                DEFAULT_SOURCE_SEPARATION_MIXED_OUTPUT_PREROLL_MS,
            )
        )
    }

    private fun readSourceSeparationHydratedMixedOutputPrerollMs(): Long {
        return normalizeSourceSeparationMixedOutputPrerollMs(
            preferences.getLong(
                SOURCE_SEPARATION_HYDRATED_MIXED_OUTPUT_PREROLL_MS,
                DEFAULT_SOURCE_SEPARATION_HYDRATED_MIXED_OUTPUT_PREROLL_MS,
            )
        )
    }

    private fun normalizeSourceSeparationMixedOutputPrerollMs(valueMs: Long): Long {
        return valueMs.coerceIn(0L, MAX_SOURCE_SEPARATION_MIXED_OUTPUT_PREROLL_MS)
    }

    private fun readSourceSeparationPlaybackReadyWindowCount(): Int {
        return normalizeSourceSeparationPlaybackReadyWindowCount(
            preferences.getInt(
                SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT,
                DEFAULT_SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT,
            )
        )
    }

    private fun normalizeSourceSeparationPlaybackReadyWindowCount(value: Int): Int {
        return value.coerceIn(
            MIN_SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT,
            MAX_SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT,
        )
    }

    private fun readSourceSeparationAutoCacheCleanup(): Boolean {
        return preferences.getBoolean(
            SOURCE_SEPARATION_AUTO_CACHE_CLEANUP,
            DEFAULT_SOURCE_SEPARATION_AUTO_CACHE_CLEANUP,
        )
    }

    private fun readSourceSeparationAutoCacheCleanupPartialLimit(): Int {
        return normalizeSourceSeparationAutoCacheCleanupLimit(
            preferences.getInt(
                SOURCE_SEPARATION_AUTO_CACHE_CLEANUP_PARTIAL_LIMIT,
                DEFAULT_SOURCE_SEPARATION_AUTO_CACHE_CLEANUP_PARTIAL_LIMIT,
            )
        )
    }

    private fun readSourceSeparationAutoCacheCleanupCompletedLimit(): Int {
        return normalizeSourceSeparationAutoCacheCleanupLimit(
            preferences.getInt(
                SOURCE_SEPARATION_AUTO_CACHE_CLEANUP_COMPLETED_LIMIT,
                DEFAULT_SOURCE_SEPARATION_AUTO_CACHE_CLEANUP_COMPLETED_LIMIT,
            )
        )
    }

    private fun normalizeSourceSeparationAutoCacheCleanupLimit(value: Int): Int {
        return value.coerceAtLeast(MIN_SOURCE_SEPARATION_AUTO_CACHE_CLEANUP_LIMIT)
    }

    private fun pruneSourceSeparationCachesAndRefresh() {
        viewModelScope.launch(IO) {
            pruneSourceSeparationCachesIfEnabled()
            loadSourceSeparationCacheManagement(pruneFirst = false)
        }
    }

    private fun loadSourceSeparationCacheManagement(pruneFirst: Boolean) {
        _sourceSeparationCacheManagementStateFlow.value =
            _sourceSeparationCacheManagementStateFlow.value.copy(
                loading = true,
                errorMessage = null,
            )
        val result = runCatching {
            if (pruneFirst) {
                pruneSourceSeparationCachesIfEnabled()
            }
            sourceSeparationEngine.listCacheEntries()
                .map { entry -> entry.toUiItem() }
        }
        _sourceSeparationCacheManagementStateFlow.value = result.fold(
            onSuccess = { items ->
                SourceSeparationCacheManagementUiState(
                    loading = false,
                    items = items,
                    deletingAll = _sourceSeparationCacheManagementStateFlow
                        .value
                        .deletingAll,
                    deletingEntryIds = _sourceSeparationCacheManagementStateFlow
                        .value
                        .deletingEntryIds
                        .intersect(items.mapTo(mutableSetOf()) { it.id }),
                )
            },
            onFailure = { error ->
                SourceSeparationCacheManagementUiState(
                    loading = false,
                    errorMessage = error.message,
                )
            },
        )
    }

    private fun pruneSourceSeparationCachesIfEnabled() {
        if (!_sourceSeparationAutoCacheCleanupFlow.value) return
        runCatching {
            sourceSeparationEngine.pruneCache(
                partialLimit = _sourceSeparationAutoCacheCleanupPartialLimitFlow.value,
                completedLimit = _sourceSeparationAutoCacheCleanupCompletedLimitFlow.value,
                protectedSongIds = protectedSourceSeparationCacheSongIds(),
            )
        }.onFailure { error ->
            Log.w(TAG, "Failed to prune source separation caches", error)
        }
    }

    private fun protectedSourceSeparationCacheSongIds(): Set<Long> {
        return buildSet {
            currentSong.id
                .takeIf { it != Song.emptySong.id }
                ?.let(::add)
            addAll(sourceSeparationForegroundWorkerCoordinator.protectedSongIds())
            _sourceSeparationPlaybackStateFlow.value.songId?.let(::add)
            val flacPromotionState = _sourceSeparationFlacPromotionStateFlow.value
            flacPromotionState.runningSongId?.let(::add)
            addAll(flacPromotionState.queuedSongIds)
        }
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

    private fun isDefaultSourceSeparationBlend(blend: Float): Boolean {
        return kotlin.math.abs(blend.coerceIn(0f, 1f) - DEFAULT_SOURCE_SEPARATION_BLEND) <
                SOURCE_SEPARATION_BLEND_EPSILON
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
    ): SessionResult = withContext(Dispatchers.Main.immediate) {
        val controller = mediaController
            ?: return@withContext SessionResult(
                SessionError.ERROR_INVALID_STATE,
                Bundle().apply {
                    putString(
                        Playback.EXTRA_SOURCE_SEPARATION_MESSAGE,
                        "Playback is not connected.",
                    )
                },
            )

        runCatching {
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

        val enabled = if (extras.containsKey(Playback.EXTRA_SOURCE_SEPARATION_ENABLED)) {
            extras.getBoolean(Playback.EXTRA_SOURCE_SEPARATION_ENABLED)
        } else {
            current.enabled
        }
        val processing = if (extras.containsKey(Playback.EXTRA_SOURCE_SEPARATION_PROCESSING)) {
            extras.getBoolean(Playback.EXTRA_SOURCE_SEPARATION_PROCESSING)
        } else {
            current.processing
        }
        val blend = if (extras.containsKey(Playback.EXTRA_SOURCE_SEPARATION_BLEND)) {
            extras.getFloat(Playback.EXTRA_SOURCE_SEPARATION_BLEND)
        } else {
            current.blend
        }
        val songId = if (extras.containsKey(Playback.EXTRA_SOURCE_SEPARATION_SONG_ID)) {
            extras.getLong(Playback.EXTRA_SOURCE_SEPARATION_SONG_ID)
        } else if (extras.containsKey(Playback.EXTRA_SOURCE_SEPARATION_ENABLED) &&
            !extras.getBoolean(Playback.EXTRA_SOURCE_SEPARATION_ENABLED)
        ) {
            null
        } else {
            current.songId
        }

        _sourceSeparationPlaybackStateFlow.value = SourceSeparationPlaybackUiState(
            enabled = enabled,
            processing = processing,
            processingGeneration = if (processing && !current.processing) {
                current.processingGeneration + 1L
            } else {
                current.processingGeneration
            },
            blend = blend,
            songId = songId,
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
        private const val SOURCE_SEPARATION_BLEND_EPSILON = 0.0001f
        private const val SOURCE_SEPARATION_BLEND_PREVIEW_THROTTLE_MS = 33L
        private const val SOURCE_SEPARATION_AUTO_START_PROCESSING_CACHE_WAIT_ATTEMPTS = 10
        private const val SOURCE_SEPARATION_AUTO_START_PROCESSING_CACHE_WAIT_MS = 50L
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
        val sourceDecodeMode: SourceSeparationDecodeModeUiState? = null,
        val averageWindowMs: Long = DEFAULT_SOURCE_SEPARATION_AVERAGE_WINDOW_MS,
        val lastWindowMs: Long? = null,
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
    DeleteCacheWaitingWindow,
    DeleteCacheWaitingFlac,
}

private val SourceSeparationPendingAction?.isDeleteCacheAction: Boolean
    get() = this == SourceSeparationPendingAction.DeleteCache ||
            this == SourceSeparationPendingAction.DeleteCacheWaitingWindow ||
            this == SourceSeparationPendingAction.DeleteCacheWaitingFlac

private data class SourceSeparationFlacPromotionRequest(
    val song: Song,
)

data class SourceSeparationFlacPromotionUiState(
    val runningSongId: Long? = null,
    val queuedSongIds: Set<Long> = emptySet(),
) {
    fun isRunning(songId: Long): Boolean {
        return runningSongId == songId
    }

    fun isQueued(songId: Long): Boolean {
        return queuedSongIds.contains(songId)
    }

    fun isActive(songId: Long): Boolean {
        return isRunning(songId) || isQueued(songId)
    }
}

data class SourceSeparationCacheManagementUiState(
    val loading: Boolean = false,
    val items: List<SourceSeparationCacheManagementItem> = emptyList(),
    val deletingAll: Boolean = false,
    val deletingEntryIds: Set<String> = emptySet(),
    val errorMessage: String? = null,
) {
    val totalSizeBytes: Long
        get() = items.sumOf { it.sizeBytes }
}

data class SourceSeparationModelUiState(
    val available: Boolean = false,
    val modelName: String = MdxModelVariant.MDXNET_9482.displayName,
    val fileName: String = MdxModelVariant.MDXNET_9482.fileName,
    val presetUrl: String = SourceSeparationModelRepository.PRESET_MODEL_URL,
    val expectedSha256: String = MdxModelVariant.MDXNET_9482.expectedSha256,
    val actualSha256: String? = null,
    val hashMatchesExpected: Boolean? = null,
    val sizeBytes: Long = 0L,
    val source: SourceSeparationModelSource = SourceSeparationModelSource.Unknown,
    val importedDisplayName: String? = null,
    val updatedAtEpochMs: Long? = null,
    val importing: Boolean = false,
    val downloading: Boolean = false,
    val downloadProgressBytes: Long = 0L,
    val downloadTotalBytes: Long? = null,
    val downloadSourceUrl: String? = null,
    val downloadUsingMirror: Boolean = false,
    val downloadMessage: String? = null,
    val deleting: Boolean = false,
    val errorMessage: String? = null,
) {
    val busy: Boolean
        get() = importing || downloading || deleting

    val downloadProgressFraction: Float?
        get() {
            val total = downloadTotalBytes ?: return null
            if (total <= 0L) return null
            return (downloadProgressBytes.toDouble() / total.toDouble())
                .coerceIn(0.0, 1.0)
                .toFloat()
        }
}

data class SourceSeparationCacheManagementItem(
    val id: String,
    val songId: Long,
    val title: String,
    val artist: String,
    val album: String,
    val state: SourceSeparationCacheManagementItemState,
    val readySegments: Int?,
    val totalSegments: Int?,
    val format: SourceSeparationCacheManagementItemFormat,
    val sizeBytes: Long,
    val updatedAtEpochMs: Long,
    val lastAccessedAtEpochMs: Long,
    val modelVariant: String,
    val pipelineVersion: Int,
    val temporaryFilesPending: Boolean,
)

enum class SourceSeparationCacheManagementItemState {
    Partial,
    Completed,
}

enum class SourceSeparationCacheManagementItemFormat {
    WAV,
    FLAC,
    Unknown,
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

private val SourceSeparationCacheUiState.isCompleted: Boolean
    get() = this is SourceSeparationCacheUiState.Completed ||
        this is SourceSeparationCacheUiState.CompletedWithTemporaryFiles

private fun SourceSeparationCacheEntry.toUiItem(): SourceSeparationCacheManagementItem {
    return SourceSeparationCacheManagementItem(
        id = id,
        songId = songId,
        title = title,
        artist = artist,
        album = album,
        state = when (state) {
            SourceSeparationCacheEntryState.Partial ->
                SourceSeparationCacheManagementItemState.Partial
            SourceSeparationCacheEntryState.Completed ->
                SourceSeparationCacheManagementItemState.Completed
        },
        readySegments = readySegments,
        totalSegments = totalSegments,
        format = when (format) {
            SourceSeparationCacheEntryFormat.WAV ->
                SourceSeparationCacheManagementItemFormat.WAV
            SourceSeparationCacheEntryFormat.FLAC ->
                SourceSeparationCacheManagementItemFormat.FLAC
            SourceSeparationCacheEntryFormat.Unknown ->
                SourceSeparationCacheManagementItemFormat.Unknown
        },
        sizeBytes = sizeBytes,
        updatedAtEpochMs = updatedAtEpochMs,
        lastAccessedAtEpochMs = lastAccessedAtEpochMs,
        modelVariant = modelVariant,
        pipelineVersion = pipelineVersion,
        temporaryFilesPending = temporaryFilesPending,
    )
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

private fun SourceSeparationModelState.toUiState(): SourceSeparationModelUiState {
    return when (this) {
        is SourceSeparationModelState.Available -> SourceSeparationModelUiState(
            available = true,
            modelName = variant.displayName,
            fileName = variant.fileName,
            expectedSha256 = expectedSha256,
            actualSha256 = actualSha256,
            hashMatchesExpected = hashMatchesExpected,
            sizeBytes = sizeBytes,
            source = source,
            importedDisplayName = importedDisplayName,
            updatedAtEpochMs = updatedAtEpochMs,
            downloadProgressBytes = 0L,
            downloadTotalBytes = null,
            downloadSourceUrl = null,
            downloadUsingMirror = false,
            downloadMessage = null,
        )
        is SourceSeparationModelState.Missing -> SourceSeparationModelUiState(
            available = false,
            modelName = variant.displayName,
            fileName = variant.fileName,
            expectedSha256 = variant.expectedSha256,
            downloadProgressBytes = 0L,
            downloadTotalBytes = null,
            downloadSourceUrl = null,
            downloadUsingMirror = false,
            downloadMessage = null,
        )
    }
}

data class SourceSeparationPlaybackUiState(
    val enabled: Boolean = false,
    val processing: Boolean = false,
    val processingGeneration: Long = 0L,
    val blend: Float = 0.5f,
    val songId: Long? = null,
    val message: String? = null,
)

enum class SourceSeparationDecodeModeUiState {
    FullSong,
    Window,
}

data class SourceSeparationSchedulerUiState(
    val playbackSegmentIndex: Int?,
    val playbackSegmentState: String?,
    val nextSegmentIndex: Int?,
    val nextSegmentState: String?,
    val processingSegmentIndex: Int,
    val priority: String?,
    val readySegments: Int,
    val totalSegments: Int,
    val readyWindowCount: Int,
    val playbackReadyWindowReadyCount: Int,
    val playbackReadyWindowPendingCount: Int,
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
