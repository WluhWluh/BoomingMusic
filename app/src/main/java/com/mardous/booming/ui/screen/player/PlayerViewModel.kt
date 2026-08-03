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
import com.mardous.booming.R
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
import com.mardous.booming.data.local.repository.Repository
import com.mardous.booming.data.local.room.PlaylistEntity
import com.mardous.booming.data.mapper.toSongs
import com.mardous.booming.data.model.QueuePosition
import com.mardous.booming.data.model.QueueSong
import com.mardous.booming.data.model.Song
import com.mardous.booming.playback.Playback
import com.mardous.booming.playback.ProgressObserver
import com.mardous.booming.playback.getQueueItems
import com.mardous.booming.playback.shuffle.ShuffleManager
import com.mardous.booming.playback.toMediaItems
import com.mardous.booming.BuildConfig
import com.mardous.booming.separation.SourceSeparationRuntimeFacade
import com.mardous.booming.separation.SourceSeparationRuntimeSong
import com.mardous.booming.separation.SourceSeparationRuntimeSongResolution
import com.mardous.booming.separation.cache.v2.SourceSeparationActiveCacheModelResolution
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheFlacPromotionResult
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheMutationResult
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwareCacheStatus
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
import com.mardous.booming.util.DEFAULT_SOURCE_SEPARATION_GPU_ENABLED
import com.mardous.booming.util.DEFAULT_SOURCE_SEPARATION_WINDOW_DECODE
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
import com.mardous.booming.util.SOURCE_SEPARATION_GPU_ENABLED
import com.mardous.booming.util.readSourceSeparationGpuEnabled
import com.mardous.booming.util.writeSourceSeparationGpuEnabled
import com.mardous.booming.util.SOURCE_SEPARATION_WINDOW_DECODE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
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
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

const val QUEUE_DEBOUNCE = 100L

@OptIn(FlowPreview::class)
@androidx.annotation.OptIn(UnstableApi::class)
class PlayerViewModel(
    private val appContext: Context,
    private val preferences: SharedPreferences,
    private val repository: Repository,
    private val sourceSeparationRuntime: SourceSeparationRuntimeFacade,
    private val sourceSeparationForegroundWorkerCoordinator:
    SourceSeparationForegroundWorkerCoordinator,
    private val localSeparationPathReadiness: () -> Boolean,
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

    private val _queueFlow = MutableStateFlow(emptyList<QueueSong>())
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

    private val _stopAfterPosition = Channel<Pair<String?, Boolean>>(Channel.BUFFERED)
    val stopAfterPosition = _stopAfterPosition.receiveAsFlow()

    private var sourceSeparationSettingsApplyJob: Job? = null
    private var sourceSeparationManualStartJob: Job? = null
    private var sourceSeparationAutoStartJob: Job? = null
    private var sourceSeparationPreStartJob: Job? = null
    private var sourceSeparationPlaybackSyncJob: Job? = null
    private var sourceSeparationBlendPreviewJob: Job? = null
    private var sourceSeparationBlendPreviewPending: Float? = null
    private var sourceSeparationFlacPromotionJob: Job? = null
    private var sourceSeparationFlacPromotionRunningRequest: SourceSeparationFlacPromotionRequest? = null
    private val sourceSeparationFlacPromotionCancelGeneration = AtomicLong(0L)
    private val sourceSeparationFlacPromotionLock = Any()
    private val sourceSeparationFlacPromotionRequests =
        linkedMapOf<String, SourceSeparationFlacPromotionRequest>()

    val sourceSeparationStateFlow =
        sourceSeparationForegroundWorkerCoordinator.workerStateFlow

    private val _currentSourceSeparationCacheAvailableFlow = MutableStateFlow(false)
    val currentSourceSeparationCacheAvailableFlow =
        _currentSourceSeparationCacheAvailableFlow.asStateFlow()

    private val _currentSourceSeparationCacheKeyFlow = MutableStateFlow<String?>(null)
    val currentSourceSeparationCacheKeyFlow = _currentSourceSeparationCacheKeyFlow.asStateFlow()

    private val _currentSourceSeparationCacheStateFlow =
        MutableStateFlow<SourceSeparationCacheUiState>(SourceSeparationCacheUiState.NotStarted)
    val currentSourceSeparationCacheStateFlow =
        _currentSourceSeparationCacheStateFlow.asStateFlow()

    private val _sourceSeparationPendingActionFlow =
        MutableStateFlow<SourceSeparationPendingAction?>(null)
    val sourceSeparationPendingActionFlow =
        _sourceSeparationPendingActionFlow.asStateFlow()

    private val _sourceSeparationModelManagementEventFlow =
        MutableSharedFlow<Unit>(
            replay = 0,
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
    val sourceSeparationModelManagementEventFlow =
        _sourceSeparationModelManagementEventFlow.asSharedFlow()

    private val _sourceSeparationQuickSetupEventFlow =
        MutableSharedFlow<Unit>(
            replay = 0,
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
    val sourceSeparationQuickSetupEventFlow =
        _sourceSeparationQuickSetupEventFlow.asSharedFlow()

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

    private val _sourceSeparationTryGpuFlow =
        MutableStateFlow(readSourceSeparationTryGpu())
    val sourceSeparationTryGpuFlow =
        _sourceSeparationTryGpuFlow.asStateFlow()

    private val _sourceSeparationWindowDecodeFlow =
        MutableStateFlow(readSourceSeparationWindowDecode())
    val sourceSeparationWindowDecodeFlow =
        _sourceSeparationWindowDecodeFlow.asStateFlow()

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

    private val sourceSeparationPreferenceChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == SOURCE_SEPARATION_GPU_ENABLED) {
                _sourceSeparationTryGpuFlow.value = readSourceSeparationTryGpu()
            }
        }

    private val internalJobs = mutableListOf<Job>()

    init {
        SourceSeparationForegroundWorkerDebugBridge.register(this)
        sourceSeparationForegroundWorkerCoordinator.attachCallbacks(this)
        preferences.registerOnSharedPreferenceChangeListener(
            sourceSeparationPreferenceChangeListener
        )
        observeSourceSeparationForegroundWorkerCoordinator()
    }

    override fun onCleared() {
        progressObserver.stop()
        SourceSeparationForegroundWorkerDebugBridge.unregister(this)
        sourceSeparationForegroundWorkerCoordinator.detachCallbacks(this)
        preferences.unregisterOnSharedPreferenceChangeListener(
            sourceSeparationPreferenceChangeListener
        )
        sourceSeparationSettingsApplyJob?.cancel()
        sourceSeparationAutoStartJob?.cancel()
        sourceSeparationPreStartJob?.cancel()
        sourceSeparationBlendPreviewJob?.cancel()
        sourceSeparationFlacPromotionJob?.cancel()
        synchronized(sourceSeparationFlacPromotionLock) {
            sourceSeparationFlacPromotionRequests.clear()
            sourceSeparationFlacPromotionRunningRequest = null
            updateSourceSeparationFlacPromotionStateLocked()
        }
        cancelInternalJobs()
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
                .debounce(500.milliseconds)
                .onEach { _ -> onGenerateQueue(mediaController, mediaContentChanged = true) }
                .launchIn(viewModelScope)

            internalJobs += combine(queueFlow, positionFlow)
            { queue, position -> Pair(queue, position) }
                .debounce(QUEUE_DEBOUNCE.milliseconds)
                .onEach { (queue, position) ->
                    updateCurrentAndNextSong(queue, position)
                }
                .launchIn(viewModelScope)

            internalJobs += currentSongFlow
                .distinctUntilChangedBy { it.id }
                .onEach { song ->
                    clearSourceSeparationPausePendingAction(song)
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
                .debounce(500.milliseconds)
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
        timeline: Timeline = player.currentTimeline,
        mediaContentChanged: Boolean = false
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

            val (mediaItems, indicesInTimeline, queuePositionIndex) = withContext(Dispatchers.Default) {
                val mediaItems = ArrayList<MediaItem>(queueItems.size)
                val indices = IntArray(queueItems.size)
                var currentPos = -1

                for (i in queueItems.indices) {
                    val (item, timelineIndex) = queueItems[i]
                    mediaItems.add(item)
                    indices[i] = timelineIndex
                    if (timelineIndex == playerIndex) {
                        currentPos = i
                    }
                }
                Triple(mediaItems, indices, currentPos)
            }

            val queuePosition = QueuePosition(
                current = queuePositionIndex,
                indicesInTimeline = indicesInTimeline
            )

            // Retrieve existing songs for the given MediaItems and detect missing ones.
            val (songs, missingMediaItems) = withContext(IO) {
                val result = repository.songsByMediaItems(mediaItems)
                val occurrences = mutableMapOf<Long, Int>()

                val queueSongs = result.first.map { song ->
                    val count = occurrences.getOrDefault(song.id, 0)
                    occurrences[song.id] = count + 1
                    QueueSong(key = song.id to count, song)
                }
                queueSongs to result.second
            }

            if (mediaContentChanged && missingMediaItems.isNotEmpty()) {
                withContext(Dispatchers.Default) {
                    // Build a set of IDs representing missing (deleted) MediaItems.
                    val missingIds = missingMediaItems.mapTo(HashSet()) { it.mediaId }

                    // Identify contiguous ranges of missing items to remove them in grouped batches.
                    val ranges = mutableListOf<IntRange>()
                    var start = -1

                    for (i in queueItems.indices) {
                        val isMissing = queueItems[i].first.mediaId in missingIds
                        if (isMissing && start == -1) {
                            // Beginning of a new missing range.
                            start = i
                        } else if (!isMissing && start != -1) {
                            // End of the current missing range.
                            ranges += (start until i)
                            start = -1
                        }
                    }
                    // If the last range extends to the end of the list, close it.
                    if (start != -1) ranges += (start until queueItems.size)

                    withContext(Dispatchers.Main) {
                        // Remove ranges in reverse order to avoid index shifting issues.
                        for (range in ranges.asReversed()) {
                            player.removeMediaItems(range.first, range.last + 1)
                        }
                    }
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
        val song = currentSong
        if (song == Song.emptySong || sourceSeparationManualStartJob?.isActive == true) return
        Log.d(TAG, "source separation manual start requested song=${song.id}")
        sourceSeparationManualStartJob = viewModelScope.launch {
            val ready = withContext(IO) { isSourceSeparationPathReady() }
            Log.d(TAG, "source separation manual start readiness song=${song.id} ready=$ready")
            if (!ready) {
                ensureSourceSeparationModelReady(openManagement = true, readinessChecked = true)
                return@launch
            }
            if (currentSong.id != song.id) return@launch
            clearSourceSeparationPausePendingAction(song)
            Log.d(TAG, "source separation manual start dispatch song=${song.id}")
            sourceSeparationForegroundWorkerCoordinator.requestManualSong(song)
        }.also { job ->
            job.invokeOnCompletion {
                if (sourceSeparationManualStartJob === job) {
                    sourceSeparationManualStartJob = null
                }
            }
        }
    }

    private fun clearSourceSeparationPausePendingAction(song: Song = currentSong) {
        if (currentSong.id == song.id &&
            _sourceSeparationPendingActionFlow.value == SourceSeparationPendingAction.Pause
        ) {
            _sourceSeparationPendingActionFlow.value = null
        }
    }

    private suspend fun handleSourceSeparationModelLoadFailure() {
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
        val result = sendSourceSeparationPlaybackEnabledCommand(
            enabled = false,
            blend = _sourceSeparationPlaybackStateFlow.value.blend,
            showMessage = false,
            expectProcessing = false,
        )
        updateSourceSeparationPlaybackState(result)
        openSourceSeparationQuickSetup()
    }

    fun cancelSourceSeparation() {
        sourceSeparationForegroundWorkerCoordinator.cancel()
    }

    private suspend fun cancelSourceSeparationAndWaitForSong(songId: Long) {
        cancelSourceSeparation()
        sourceSeparationForegroundWorkerCoordinator.waitForWorkerToLeaveSong(songId)
    }

    private suspend fun disableSourceSeparationPlaybackForManualCacheDelete(
        songId: Long,
        cacheKey: String,
    ) {
        traceSourceSeparationPlaybackTestMarker(
            "manualDelete.disablePlayback songId=$songId cache=${cacheKey.take(12)}"
        )
        sourceSeparationManualStartJob?.cancel()
        sourceSeparationManualStartJob = null
        sourceSeparationSettingsApplyJob?.cancel()
        sourceSeparationSettingsApplyJob = null
        sourceSeparationAutoStartJob?.cancel()
        sourceSeparationAutoStartJob = null
        sourceSeparationPreStartJob?.cancel()
        sourceSeparationPreStartJob = null
        sourceSeparationPlaybackSyncJob?.cancel()
        sourceSeparationPlaybackSyncJob = null
        sourceSeparationForegroundWorkerCoordinator.suppressAndPauseSong(songId)
        preferences.edit {
            putBoolean(KEY_SOURCE_SEPARATION_PLAYBACK_ENABLED, false)
        }
        _sourceSeparationBlendModeFlow.value = SourceSeparationBlendMode.Off
        val result = sendSourceSeparationPlaybackEnabledCommand(
            enabled = false,
            blend = _sourceSeparationPlaybackStateFlow.value.blend,
            showMessage = false,
            expectProcessing = false,
        )
        updateSourceSeparationPlaybackState(result)
        traceSourceSeparationPlaybackTestMarker(
            "manualDelete.disablePlayback.done songId=$songId cache=${cacheKey.take(12)} " +
                    "result=${result.resultCode}"
        )
    }

    suspend fun prepareSourceSeparationCacheForManualDelete(cacheKey: String) {
        val song = currentSong
        val currentSongCacheKey = resolveSourceSeparationRuntimeSong(song)?.cacheKey
        val runningCacheKey = sourceSeparationForegroundWorkerCoordinator.runningCacheKey()
        val runningSongId = sourceSeparationForegroundWorkerCoordinator.runningSongId()
        val isCurrentSongTask = currentSongCacheKey == cacheKey &&
                (sourceSeparationForegroundWorkerCoordinator.pendingSongId() == song.id ||
                        runningSongId == song.id)
        if (currentSongCacheKey == cacheKey) {
            disableSourceSeparationPlaybackForManualCacheDelete(
                songId = song.id,
                cacheKey = cacheKey,
            )
        }
        if (runningCacheKey == cacheKey || isCurrentSongTask) {
            val songId = runningSongId ?: song.id
            traceSourceSeparationPlaybackTestMarker(
                "manualDelete.cancelSeparation songId=$songId cache=${cacheKey.take(12)}"
            )
            cancelSourceSeparationAndWaitForSong(songId)
        }

        val waitsForFlacPromotion = isSourceSeparationFlacPromotionActive(cacheKey)
        cancelSourceSeparationFlacPromotion(cacheKey)
        if (waitsForFlacPromotion) {
            _sourceSeparationPendingActionFlow.value =
                SourceSeparationPendingAction.DeleteCacheWaitingFlac
            waitForSourceSeparationFlacPromotionToStop(cacheKey)
        }
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
            val runtimeSong = resolveSourceSeparationRuntimeSong(song) ?: return@launch
            _sourceSeparationPendingActionFlow.value = SourceSeparationPendingAction.DeleteCache
            traceSourceSeparationPlaybackTestMarker(
                "deleteCurrent.request songId=${song.id} cache=${runtimeSong.cacheKey.take(12)} " +
                        "title=${song.title}"
            )
            try {
                prepareSourceSeparationCacheForManualDelete(runtimeSong.cacheKey)
                _sourceSeparationPendingActionFlow.value = SourceSeparationPendingAction.DeleteCache
                val deleted = sourceSeparationRuntime.delete(runtimeSong.cacheKey) ==
                        SourceSeparationCacheMutationResult.Completed
                traceSourceSeparationPlaybackTestMarker(
                    "deleteCurrent.deleted songId=${song.id} deleted=$deleted"
                )
                if (currentSong.id == song.id && deleted) {
                    sourceSeparationForegroundWorkerCoordinator.clearStatusIfNotRunning()
                    refreshCurrentSourceSeparationCacheAvailable(song)
                    handleSourceSeparationCacheManualDeleteResult(
                        runtimeSong.cacheKey,
                        SourceSeparationCacheMutationResult.Completed,
                    )
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
        viewModelScope.launch(IO) {
            resolveSourceSeparationRuntimeSong(song)?.let { runtimeSong ->
                startSourceSeparationFlacPromotion(song, runtimeSong.cacheKey)
            }
        }
    }

    suspend fun handleSourceSeparationCacheManualDeleteResult(
        cacheKey: String,
        result: SourceSeparationCacheMutationResult,
    ) {
        try {
            if (result != SourceSeparationCacheMutationResult.Completed) return
            val song = currentSong
            val isCurrentCache = _currentSourceSeparationCacheKeyFlow.value == cacheKey ||
                    resolveSourceSeparationRuntimeSong(song)?.cacheKey == cacheKey
            if (!isCurrentCache) return
            sourceSeparationForegroundWorkerCoordinator.clearStatusIfNotRunning()
            refreshCurrentSourceSeparationCacheAvailable(song)
            handleCurrentSourceSeparationCacheDeleted()
        } finally {
            if (_sourceSeparationPendingActionFlow.value.isDeleteCacheAction) {
                _sourceSeparationPendingActionFlow.value = null
            }
        }
    }

    private fun startSourceSeparationFlacPromotion(song: Song, cacheKey: String) {
        synchronized(sourceSeparationFlacPromotionLock) {
            if (sourceSeparationFlacPromotionRunningRequest?.cacheKey == cacheKey) return
            sourceSeparationFlacPromotionRequests[cacheKey] =
                SourceSeparationFlacPromotionRequest(song = song, cacheKey = cacheKey)
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
                                !isSourceSeparationFlacPromotionCurrent(request.cacheKey)
                    }
                    try {
                        traceSourceSeparationPlaybackTestMarker(
                            "flacPromotion.start songId=${request.song.id} " +
                                    "current=${currentSong.id == request.song.id}"
                        )
                        runCatching {
                            sourceSeparationRuntime.promote(
                                cacheKey = request.cacheKey,
                                shouldCancel = shouldCancelRequest,
                            )
                        }.onSuccess { result ->
                            val manifest = when (result) {
                                is SourceSeparationCacheFlacPromotionResult.Completed ->
                                    result.manifest
                                is SourceSeparationCacheFlacPromotionResult.AlreadyPromoted ->
                                    result.manifest
                                SourceSeparationCacheFlacPromotionResult.Busy,
                                SourceSeparationCacheFlacPromotionResult.Unavailable -> null
                            }
                            traceSourceSeparationPlaybackTestMarker(
                                "flacPromotion.success songId=${request.song.id} " +
                                        "manifest=${manifest != null} current=${currentSong.id == request.song.id} " +
                                        "state=${manifest?.state} cache=${request.cacheKey.take(12)}"
                            )
                            if (manifest != null) {
                                if (currentSong.id == request.song.id) {
                                    refreshCurrentSourceSeparationCacheAvailable(request.song)
                                    traceSourceSeparationPlaybackTestMarker(
                                        "flacPromotion.syncPlayback songId=${request.song.id} force=true"
                                    )
                                    syncSourceSeparationPlaybackIfRequested(force = true)
                                } else {
                                    traceSourceSeparationPlaybackTestMarker(
                                        "flacPromotion.syncPlayback.skip songId=${request.song.id} " +
                                                "current=${currentSong.id}"
                                    )
                                }
                                traceSourceSeparationPlaybackTestMarker(
                                    "flacPromotion.cleanupTemporaryCache songId=${request.song.id}"
                                )
                                requestSourceSeparationTemporaryCacheCleanup()
                            }
                        }.onFailure { error ->
                            traceSourceSeparationPlaybackTestMarker(
                                "flacPromotion.failed songId=${request.song.id} " +
                                        "error=${error.message ?: error::class.java.name}"
                            )
                            if (error is CancellationException) {
                                Log.d(TAG, "Source separation FLAC promotion canceled")
                            } else {
                                Log.w(TAG, "Failed to promote source separation stems to FLAC", error)
                            }
                        }
                    } finally {
                        traceSourceSeparationPlaybackTestMarker(
                            "flacPromotion.finish songId=${request.song.id} " +
                                    "current=${currentSong.id == request.song.id}"
                        )
                        finishSourceSeparationFlacPromotionRequest(request)
                    }
                }
            } finally {
                synchronized(sourceSeparationFlacPromotionLock) {
                    sourceSeparationFlacPromotionRunningRequest = null
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

    private fun cancelSourceSeparationFlacPromotion(cacheKey: String) {
        var shouldCancelWorker = false
        synchronized(sourceSeparationFlacPromotionLock) {
            sourceSeparationFlacPromotionRequests.remove(cacheKey)
            if (sourceSeparationFlacPromotionRunningRequest?.cacheKey == cacheKey) {
                sourceSeparationFlacPromotionCancelGeneration.incrementAndGet()
                shouldCancelWorker = true
            }
            updateSourceSeparationFlacPromotionStateLocked()
        }
        if (shouldCancelWorker) {
            sourceSeparationFlacPromotionJob?.cancel()
        }
    }

    fun playSourceSeparationCompletedCache(cacheKey: String) {
        traceSourceSeparationPlaybackUserActionMarker(
            "modelAwareCache.play.userAction cache=${cacheKey.take(12)}"
        )
        viewModelScope.launch {
            val result = sendSourceSeparationPlaybackCommand(
                action = Playback.PLAY_SOURCE_SEPARATION_COMPLETED_CACHE,
                args = Bundle().apply {
                    putString(Playback.EXTRA_SOURCE_SEPARATION_CACHE_KEY, cacheKey)
                },
            )
            updateSourceSeparationPlaybackState(result)
        }
    }

    private suspend fun waitForSourceSeparationFlacPromotionToStop(cacheKey: String) {
        while (isSourceSeparationFlacPromotionActive(cacheKey)) {
            sourceSeparationFlacPromotionJob?.join()
        }
    }

    private fun isSourceSeparationFlacPromotionCurrent(cacheKey: String): Boolean {
        return synchronized(sourceSeparationFlacPromotionLock) {
            sourceSeparationFlacPromotionRunningRequest?.cacheKey == cacheKey
        }
    }

    private fun isSourceSeparationFlacPromotionActive(cacheKey: String): Boolean {
        return synchronized(sourceSeparationFlacPromotionLock) {
            sourceSeparationFlacPromotionRunningRequest?.cacheKey == cacheKey ||
                    sourceSeparationFlacPromotionRequests.containsKey(cacheKey)
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
                sourceSeparationFlacPromotionRunningRequest = request
                updateSourceSeparationFlacPromotionStateLocked()
                request
            }
        }
    }

    private fun finishSourceSeparationFlacPromotionRequest(
        request: SourceSeparationFlacPromotionRequest,
    ) {
        synchronized(sourceSeparationFlacPromotionLock) {
            if (sourceSeparationFlacPromotionRunningRequest == request) {
                sourceSeparationFlacPromotionRunningRequest = null
            }
            updateSourceSeparationFlacPromotionStateLocked()
        }
    }

    private fun updateSourceSeparationFlacPromotionStateLocked() {
        _sourceSeparationFlacPromotionStateFlow.value = SourceSeparationFlacPromotionUiState(
            runningCacheKey = sourceSeparationFlacPromotionRunningRequest?.cacheKey,
            queuedCacheKeys = sourceSeparationFlacPromotionRequests.keys.toSet(),
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
        cacheKey: String,
        shouldPromoteCompletedStems: Boolean,
    ) {
        if (currentSong.id == song.id) {
            clearSourceSeparationPausePendingAction(song)
            refreshCurrentSourceSeparationCacheAvailable(song)
        }
        pruneSourceSeparationCaches(protectedCacheKeys = setOf(cacheKey))
        requestSourceSeparationTemporaryCacheCleanup()
        if (shouldPromoteCompletedStems) {
            startSourceSeparationFlacPromotion(song, cacheKey)
        }
    }

    override fun onSourceSeparationWorkerPaused(song: Song) {
        if (currentSong.id == song.id) {
            clearSourceSeparationPausePendingAction(song)
            refreshCurrentSourceSeparationCacheAvailable(song)
        }
    }

    override fun onSourceSeparationWorkerModelLoadFailed(message: String) {
        Log.w(TAG, "Source separation model load failed: $message")
        clearSourceSeparationPausePendingAction()
        viewModelScope.launch {
            handleSourceSeparationModelLoadFailure()
        }
    }

    fun refreshCurrentSourceSeparationCacheAvailable(song: Song = currentSong) {
        viewModelScope.launch(IO) {
            val runtimeSong = resolveSourceSeparationRuntimeSong(song)
            val cacheState = when {
                runtimeSong == null -> SourceSeparationCacheUiState.NotStarted
                else -> runCatching {
                    sourceSeparationRuntime.cacheStatus(runtimeSong).toUiState()
                }.getOrDefault(SourceSeparationCacheUiState.NotStarted)
            }
            if (currentSong.id == song.id) {
                _currentSourceSeparationCacheKeyFlow.value = runtimeSong?.cacheKey
                _currentSourceSeparationCacheStateFlow.value = cacheState
                _currentSourceSeparationCacheAvailableFlow.value =
                    cacheState != SourceSeparationCacheUiState.NotStarted
                if (cacheState.isCompleted) {
                    maybePreStartNextSourceSeparation()
                }
            }
        }
    }

    private fun resolveSourceSeparationRuntimeSong(
        song: Song,
    ): SourceSeparationRuntimeSong? {
        return when (val resolution = sourceSeparationRuntime.resolve(song)) {
            is SourceSeparationRuntimeSongResolution.Ready -> resolution.song
            is SourceSeparationRuntimeSongResolution.Unavailable -> null
        }
    }

    fun clearSourceSeparationStatus() {
        sourceSeparationForegroundWorkerCoordinator.clearStatusIfNotRunning()
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

    fun openSourceSeparationModelManagement() {
        _sourceSeparationModelManagementEventFlow.tryEmit(Unit)
    }

    fun openSourceSeparationQuickSetup() {
        _sourceSeparationQuickSetupEventFlow.tryEmit(Unit)
    }

    private fun ensureSourceSeparationModelReady(
        openManagement: Boolean,
        readinessChecked: Boolean = false,
    ): Boolean {
        if (!readinessChecked && isSourceSeparationPathReady()) return true

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
            openSourceSeparationQuickSetup()
        }
        return false
    }

    private fun isSourceSeparationModelReady(): Boolean =
        sourceSeparationRuntime.activeModelResolution() is
                SourceSeparationActiveCacheModelResolution.Ready

    private fun isSourceSeparationPathReady(): Boolean =
        runCatching { localSeparationPathReadiness() }
            .getOrDefault(false)

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
            if (result.resultCode == SessionResult.RESULT_SUCCESS) {
                requestImmediateSourceSeparationPlaybackGate(normalizedBlend)
                    ?.let(::updateSourceSeparationPlaybackState)
            }
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
                    putBoolean(Playback.EXTRA_SOURCE_SEPARATION_PERSIST_BLEND, false)
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

    fun setSourceSeparationGpuEnabled(enabled: Boolean) {
        preferences.writeSourceSeparationGpuEnabled(enabled)
        _sourceSeparationTryGpuFlow.value = enabled
    }

    @Suppress("unused")
    fun setSourceSeparationTryGpu(enabled: Boolean) = setSourceSeparationGpuEnabled(enabled)

    fun setSourceSeparationWindowDecodeEnabled(enabled: Boolean) {
        preferences.edit {
            putBoolean(SOURCE_SEPARATION_WINDOW_DECODE, enabled)
        }
        _sourceSeparationWindowDecodeFlow.value = enabled
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
            pruneSourceSeparationCaches()
        }
    }

    fun setSourceSeparationAutoCacheCleanupPartialLimit(value: Int) {
        val normalized = normalizeSourceSeparationAutoCacheCleanupLimit(value)
        preferences.edit {
            putInt(SOURCE_SEPARATION_AUTO_CACHE_CLEANUP_PARTIAL_LIMIT, normalized)
        }
        _sourceSeparationAutoCacheCleanupPartialLimitFlow.value = normalized
        pruneSourceSeparationCaches()
    }

    fun setSourceSeparationAutoCacheCleanupCompletedLimit(value: Int) {
        val normalized = normalizeSourceSeparationAutoCacheCleanupLimit(value)
        preferences.edit {
            putInt(SOURCE_SEPARATION_AUTO_CACHE_CLEANUP_COMPLETED_LIMIT, normalized)
        }
        _sourceSeparationAutoCacheCleanupCompletedLimitFlow.value = normalized
        pruneSourceSeparationCaches()
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
            val expectProcessingImmediately =
                _sourceSeparationAutoStartFlow.value &&
                        !isDefaultSourceSeparationBlend(blend)
            val immediateResult = sendSourceSeparationPlaybackEnabledCommand(
                enabled = true,
                blend = blend,
                showMessage = showMessage,
                expectProcessing = expectProcessingImmediately,
            )
            updateSourceSeparationPlaybackState(immediateResult)
            val autoStartDecision =
                sourceSeparationForegroundWorkerCoordinator.autoStartDecision(song, blend)
            if (autoStartDecision.shouldStart && currentSong.id == song.id) {
                sourceSeparationForegroundWorkerCoordinator
                    .requestPlaybackDemandSong(song)
            }
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
                sourceSeparationForegroundWorkerCoordinator
                    .requestPlaybackDemandSong(song)
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
                !isSourceSeparationPathReady()
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

            val currentRuntimeSong = resolveSourceSeparationRuntimeSong(current)
                ?: return@launch
            val currentCacheCompleted = runCatching {
                sourceSeparationRuntime.cacheStatus(currentRuntimeSong) is
                        SourceSeparationModelAwareCacheStatus.Completed
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
        if (!BuildConfig.DEBUG) return
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
            val runtimeSong = resolveSourceSeparationRuntimeSong(song)
                ?: return@runCatching false
            sourceSeparationRuntime.writeBlend(runtimeSong, normalizedBlend)
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
        if (playbackEnabled && !isSourceSeparationModelReady()) {
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

    private fun readSourceSeparationTryGpu(): Boolean {
        return preferences.readSourceSeparationGpuEnabled()
    }

    private fun readSourceSeparationWindowDecode(): Boolean {
        return preferences.getBoolean(
            SOURCE_SEPARATION_WINDOW_DECODE,
            DEFAULT_SOURCE_SEPARATION_WINDOW_DECODE,
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

    private fun pruneSourceSeparationCaches(
        protectedCacheKeys: Set<String> = emptySet(),
    ) {
        viewModelScope.launch(IO) {
            pruneSourceSeparationCachesIfEnabled(protectedCacheKeys)
        }
    }

    private suspend fun requestImmediateSourceSeparationPlaybackGate(
        blend: Float,
    ): SessionResult? {
        if (!_sourceSeparationAutoStartFlow.value ||
            _sourceSeparationBlendModeFlow.value == SourceSeparationBlendMode.Off ||
            isDefaultSourceSeparationBlend(blend)
        ) {
            return null
        }
        return sendSourceSeparationPlaybackCommand(
            action = Playback.SYNC_SOURCE_SEPARATION_PLAYBACK,
            args = Bundle().apply {
                putBoolean(
                    Playback.EXTRA_SOURCE_SEPARATION_ALLOW_NEW_SESSION,
                    true,
                )
                putBoolean(
                    Playback.EXTRA_SOURCE_SEPARATION_EXPECT_PROCESSING,
                    true,
                )
            },
        )
    }

    private fun pruneSourceSeparationCachesIfEnabled(
        protectedCacheKeys: Set<String> = emptySet(),
    ) {
        if (!_sourceSeparationAutoCacheCleanupFlow.value) return
        runCatching {
            sourceSeparationRuntime.prune(
                partialLimit = _sourceSeparationAutoCacheCleanupPartialLimitFlow.value,
                completedLimit = _sourceSeparationAutoCacheCleanupCompletedLimitFlow.value,
                protectedCacheKeys = protectedCacheKeys,
            )
        }.onFailure { error ->
            Log.w(TAG, "Failed to prune source separation caches", error)
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
    fun stopAt(stopPosition: Int) = viewModelScope.launch {
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
                    _stopAfterPosition.send(mediaItem.mediaMetadata.title?.toString() to canceled)
                } else {
                    _stopAfterPosition.send(null to false)
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
    DeleteCacheWaitingFlac,
}

private val SourceSeparationPendingAction?.isDeleteCacheAction: Boolean
    get() = this == SourceSeparationPendingAction.DeleteCache ||
            this == SourceSeparationPendingAction.DeleteCacheWaitingFlac

private data class SourceSeparationFlacPromotionRequest(
    val song: Song,
    val cacheKey: String,
)

data class SourceSeparationFlacPromotionUiState(
    val runningCacheKey: String? = null,
    val queuedCacheKeys: Set<String> = emptySet(),
) {
    fun isRunning(cacheKey: String?): Boolean {
        return cacheKey != null && runningCacheKey == cacheKey
    }

    fun isQueued(cacheKey: String?): Boolean {
        return cacheKey != null && cacheKey in queuedCacheKeys
    }

    fun isActive(cacheKey: String?): Boolean = isRunning(cacheKey) || isQueued(cacheKey)
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
    data object Busy : SourceSeparationCacheUiState()
    data object Corrupt : SourceSeparationCacheUiState()
}

private val SourceSeparationCacheUiState.isCompleted: Boolean
    get() = this is SourceSeparationCacheUiState.Completed ||
        this is SourceSeparationCacheUiState.CompletedWithTemporaryFiles

private fun SourceSeparationModelAwareCacheStatus.toUiState(): SourceSeparationCacheUiState {
    return when (this) {
        SourceSeparationModelAwareCacheStatus.Missing -> SourceSeparationCacheUiState.NotStarted
        SourceSeparationModelAwareCacheStatus.Busy -> SourceSeparationCacheUiState.Busy
        is SourceSeparationModelAwareCacheStatus.Incomplete -> SourceSeparationCacheUiState.Partial(
            readySegments = readySegments,
            totalSegments = totalSegments,
        )
        is SourceSeparationModelAwareCacheStatus.Completed -> if (cleanupPending) {
            SourceSeparationCacheUiState.CompletedWithTemporaryFiles(
                canPromoteCompletedStems = canPromote,
            )
        } else {
            SourceSeparationCacheUiState.Completed(
                canPromoteCompletedStems = canPromote,
            )
        }
        is SourceSeparationModelAwareCacheStatus.Corrupt -> SourceSeparationCacheUiState.Corrupt
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

enum class SourceSeparationBlendMode {
    Off,
    Global,
    PerSong
}
