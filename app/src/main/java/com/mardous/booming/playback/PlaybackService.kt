package com.mardous.booming.playback

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.content.getSystemService
import androidx.core.os.postDelayed
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ShuffleOrder.UnshuffledShuffleOrder
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.size.Scale
import coil3.toBitmap
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.mardous.booming.BuildConfig
import com.mardous.booming.R
import com.mardous.booming.coil.CoilBitmapLoader
import com.mardous.booming.core.appwidgets.BoomingGlanceWidget
import com.mardous.booming.core.appwidgets.CardWidget
import com.mardous.booming.core.appwidgets.FullWidget
import com.mardous.booming.core.appwidgets.WidgetTheme
import com.mardous.booming.core.appwidgets.state.PlaybackState
import com.mardous.booming.core.appwidgets.state.PlaybackStateDefinition
import com.mardous.booming.core.audio.AudioOutputObserver
import com.mardous.booming.core.model.player.MetadataField
import com.mardous.booming.core.palette.PaletteProcessor
import com.mardous.booming.data.local.MediaStoreObserver
import com.mardous.booming.data.local.ReplayGainTagExtractor
import com.mardous.booming.data.local.repository.Repository
import com.mardous.booming.data.model.Song
import com.mardous.booming.data.model.network.NetworkFeature
import com.mardous.booming.data.model.network.ScrobblingService
import com.mardous.booming.extensions.isBluetoothA2dpConnected
import com.mardous.booming.extensions.isBluetoothA2dpDisconnected
import com.mardous.booming.extensions.showToast
import com.mardous.booming.playback.equalizer.EqualizerManager
import com.mardous.booming.playback.library.LibraryProvider
import com.mardous.booming.playback.library.MediaIDs
import com.mardous.booming.playback.processor.BalanceAudioProcessor
import com.mardous.booming.playback.processor.PreparedSourceSeparationPlaybackInputs
import com.mardous.booming.playback.processor.ReplayGainAudioProcessor
import com.mardous.booming.playback.processor.SourceSeparationMixAudioProcessor
import com.mardous.booming.playback.renderer.AlacWorkaroundCodecSelector
import com.mardous.booming.playback.renderer.BoomingMusicRenderersFactory
import com.mardous.booming.separation.SourceSeparationModelAwareEngineResult
import com.mardous.booming.separation.SourceSeparationBlendDemand
import com.mardous.booming.separation.SourceSeparationMixModelKey
import com.mardous.booming.separation.SourceSeparationModelMixSettingsStore
import com.mardous.booming.separation.SourceSeparationRuntimeFacade
import com.mardous.booming.separation.SourceSeparationExecutionRunClass
import com.mardous.booming.separation.SourceSeparationExecutionSelectionResolver
import com.mardous.booming.separation.SourceSeparationExecutionSelectionSnapshot
import com.mardous.booming.separation.SourceSeparationRuntimeSong
import com.mardous.booming.separation.SourceSeparationRuntimeSongResolution
import com.mardous.booming.separation.SourceSeparationStemGainPolicy
import com.mardous.booming.separation.cache.SourceSeparationCacheDirectories
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheManifest
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheManifestState
import com.mardous.booming.separation.cache.v2.promoteSourceSeparationCacheWhenAvailable
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheOutput
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwareCachePlayback
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwareCacheStatus
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwarePlayableStatus
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwareReadyHorizonStatus
import com.mardous.booming.separation.model.contract.toMdxBlendEndpointStemIds
import com.mardous.booming.separation.process.SourceSeparationProcessingLeasePolicy
import com.mardous.booming.separation.process.SourceSeparationProcessingOwnershipHandoff
import com.mardous.booming.playback.processor.SourceSeparationMixAudioProcessor.InputMode
import com.mardous.booming.ui.screen.MainActivity
import com.mardous.booming.ui.screen.player.SourceSeparationForegroundWorkerCoordinator
import com.mardous.booming.ui.screen.player.SourceSeparationUiState
import com.mardous.booming.util.DEFAULT_SOURCE_SEPARATION_AUTO_START
import com.mardous.booming.util.DEFAULT_SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT
import com.mardous.booming.util.readSourceSeparationGpuEnabled
import com.mardous.booming.util.CLEAR_QUEUE_ON_COMPLETION
import com.mardous.booming.util.DEFAULT_SOURCE_SEPARATION_MIXED_OUTPUT_PREROLL_MS
import com.mardous.booming.util.DEFAULT_SOURCE_SEPARATION_WINDOW_DECODE
import com.mardous.booming.util.ENABLE_HISTORY
import com.mardous.booming.util.IGNORE_AUDIO_FOCUS
import com.mardous.booming.util.MAX_SOURCE_SEPARATION_MIXED_OUTPUT_PREROLL_MS
import com.mardous.booming.util.MAX_SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT
import com.mardous.booming.util.MIN_SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT
import com.mardous.booming.util.MP3_INDEX_SEEKING
import com.mardous.booming.util.PAUSE_ON_ZERO_VOLUME
import com.mardous.booming.util.PLAY_ON_STARTUP_MODE
import com.mardous.booming.util.PlayOnStartupMode
import com.mardous.booming.util.Preferences
import com.mardous.booming.util.Preferences.requireString
import com.mardous.booming.util.QUEUE_NEXT_MODE
import com.mardous.booming.util.REWIND_WITH_BACK
import com.mardous.booming.util.SEEK_INTERVAL
import com.mardous.booming.util.SOURCE_SEPARATION_AUTO_START
import com.mardous.booming.util.SOURCE_SEPARATION_AUTO_FLAC_COMPRESSION
import com.mardous.booming.util.SOURCE_SEPARATION_MIXED_OUTPUT_PREROLL_MS
import com.mardous.booming.util.SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT
import com.mardous.booming.util.SOURCE_SEPARATION_REMEMBER_PER_SONG
import com.mardous.booming.util.SOURCE_SEPARATION_WINDOW_DECODE
import com.mardous.booming.util.STOP_WHEN_CLOSED_FROM_RECENTS
import com.mardous.booming.util.SongPlayCountHelper
import com.mardous.booming.util.WIDGET_DYNAMIC_COLORS
import com.mardous.booming.util.WIDGET_IMAGE_CORNER_RADIUS
import com.mardous.booming.util.WIDGET_SMALL_LAYOUT_STYLE
import com.mardous.booming.util.WIDGET_THIRD_LINE_CONTENT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

private const val SOURCE_SEPARATION_STEM_CHANNEL_COUNT = 2

@OptIn(UnstableApi::class)
class PlaybackService :
    MediaLibraryService(),
    MediaLibrarySession.Callback,
    Player.Listener,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private val serviceScope = CoroutineScope(Job() + Main)
    private val uiHandler = Handler(Looper.getMainLooper())

    private val glanceManager by lazy { GlanceAppWidgetManager(applicationContext) }

    private val preferences: SharedPreferences by inject()
    private val sleepTimer: SleepTimer by inject()
    private val equalizerManager: EqualizerManager by inject()
    private val audioOutputObserver: AudioOutputObserver by inject()
    private val repository: Repository by inject()
    private val sourceSeparationRuntime: SourceSeparationRuntimeFacade by inject()
    private val sourceSeparationMixSettings: SourceSeparationModelMixSettingsStore by inject()
    private val sourceSeparationExecutionSelection:
            SourceSeparationExecutionSelectionResolver by inject()
    private val sourceSeparationForegroundWorkerCoordinator:
            SourceSeparationForegroundWorkerCoordinator by inject()
    private val sourceSeparationProcessingOwnershipHandoff:
            SourceSeparationProcessingOwnershipHandoff by inject()

    private val libraryProvider = LibraryProvider(repository)
    private val songPlayCountHelper = SongPlayCountHelper()
    private val mediaStoreObserver = MediaStoreObserver(uiHandler) {
        mediaSession?.broadcastCustomCommand(
            SessionCommand(Playback.EVENT_MEDIA_CONTENT_CHANGED, Bundle.EMPTY),
            Bundle.EMPTY
        )
    }

    private val playerThread = HandlerThread("Booming-ExoPlayer", Process.THREAD_PRIORITY_AUDIO)
    private val sourceSeparationMixProcessor: SourceSeparationMixAudioProcessor by inject()
    private val balanceProcessor: BalanceAudioProcessor by inject()
    private val replayGainProcessor: ReplayGainAudioProcessor by inject()

    private lateinit var nm: NotificationManager
    private lateinit var persistentStorage: PersistentStorage
    private lateinit var customCommands: List<CommandButton>
    private lateinit var player: AdvancedForwardingPlayer
    private lateinit var mediaSessionPlayer: SourceSeparationMediaSessionPlayer
    private var mediaSession: MediaLibrarySession? = null

    private var eqStateHandler: Handler? = Handler(Looper.getMainLooper())

    private var errorRecoveryRetryCount = 0
    private var pausedByZeroVolume = false
    private var hasSetUnshuffledOrder = false
    private var stopIndex = -1
    private var sourceSeparationPlaybackSession: SourceSeparationPlaybackSession? = null
    private var sourceSeparationRequestedStemMix: SourceSeparationRequestedStemMix? = null
    private var sourceSeparationPlaybackRequested = false
    private var sourceSeparationPlaybackAutoSyncOnTransition = true
    private var sourceSeparationPlaybackIsProcessing = false
    private var sourceSeparationPlaybackProcessingCacheKey: String? = null
    private var sourceSeparationPlaybackExpectProcessing = false
    private var sourceSeparationPlaybackResumeWhenReady = false
    private var sourceSeparationPlaybackInternalPlayWhenReady: Boolean? = null
    private var sourceSeparationPlaybackPlayIntent = false
    private var sourceSeparationPausedBlendFlushPending = false
    private val sourceSeparationPlaybackReadinessMutex = Mutex()
    private val sourceSeparationPlaybackWindowWaitTracker =
        SourceSeparationPlaybackWindowWaitTracker()
    private var sourceSeparationPlaybackInternalSeekTarget:
        SourceSeparationPlaybackInternalSeekTarget? = null
    private var sourceSeparationPlaybackGateJob: Job? = null
    private var sourceSeparationPlaybackReadinessMonitorJob: Job? = null
    private var sourceSeparationPlaybackReadinessMonitorSessionId: Long? = null
    private var sourceSeparationDataPlaneMonitorJob: Job? = null
    private var sourceSeparationCompletedCleanupJob: Job? = null
    private var sourceSeparationDataPlaneMonitorSessionId: Long? = null
    private var sourceSeparationDataPlaneResumeWhenReady = false
    private var sourceSeparationPlaybackTraceFile: File? = null
    private var sourceSeparationPlaybackTraceFlushJob: Job? = null
    private var sourceSeparationPlaybackTraceWriteJob: Job? = null
    private var sourceSeparationOutputMuteJob: Job? = null
    private val sourceSeparationPlaybackTraceLock = Any()
    private val sourceSeparationPlaybackTraceBuffer = mutableListOf<String>()
    private val sourceSeparationPlaybackTraceSeq = AtomicLong()
    private var sourceSeparationPlaybackCheckSeq = 0L
    private var sourceSeparationOutputMuted = false
    private var sourceSeparationOutputWaitingForMixedOutput = false
    private var sourceSeparationOutputMuteStartedAtMs = 0L
    private var sourceSeparationOutputFlushBarrierSeq = 0L
    private var sourceSeparationPendingOutputFlush:
            SourceSeparationPendingOutputFlush? = null
    private var sourceSeparationPendingMixedOutputGeneration: Long? = null
    private var sourceSeparationPlaybackContextGeneration = 0L
    private var sourceSeparationActiveSelection:
            SourceSeparationExecutionSelectionSnapshot? = null
    private var sourceSeparationPlaybackExpectProcessingStartedAtMs = 0L
    private var sourceSeparationProcessingWakeLock: PowerManager.WakeLock? = null
    private var sourceSeparationProcessingWakeLockJob: Job? = null
    private var sourceSeparationProcessingHeartbeatJob: Job? = null
    private var sourceSeparationPreStartJob: Job? = null
    private var sourceSeparationForegroundServiceType: Int? = null
    private var sourceSeparationForegroundServiceTypeUpdatedAtMs = 0L

    private var headsetClickCount = 0
    private val headsetClickRunnable = Runnable {
        if (!::player.isInitialized) return@Runnable
        val count = headsetClickCount
        headsetClickCount = 0
        when (count) {
            1 -> if (player.isPlaying) player.pause() else player.play()
            2 -> player.seekToNext()
            3 -> player.seekToPrevious()
        }
    }

    private var lastPlaybackState: PlaybackState? = null
    private var widgetUpdateJob: Job? = null
    private var fadeOutAnimator: ValueAnimator? = null

    val isInTransientFocusLoss: Boolean
        get() = player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS

    val isPlaying: Boolean
        get() = player.isPlaying

    private val shuffleCommand: CommandButton
        get() = if (player.shuffleModeEnabled) {
            customCommands[1]
        } else {
            customCommands[0]
        }

    private val repeatCommand: CommandButton
        get() = when (player.repeatMode) {
            Player.REPEAT_MODE_ALL -> customCommands[3]
            Player.REPEAT_MODE_ONE -> customCommands[4]
            else -> customCommands[2]
        }

    private val pauseOnZeroVolume: Boolean
        get() = preferences.getBoolean(PAUSE_ON_ZERO_VOLUME, false)
    private val sequentialTimeline: Boolean
        get() = preferences.getString(QUEUE_NEXT_MODE, "1") == "1"
    private val handleAudioFocus: Boolean
        get() = preferences.getBoolean(IGNORE_AUDIO_FOCUS, false).not()
    private val maxSeekToPreviousMs: Long
        get() = if (preferences.getBoolean(REWIND_WITH_BACK, true)) REWIND_INSTEAD_PREVIOUS_MILLIS else 0
    private val seekInterval: Long
        get() = preferences.getInt(SEEK_INTERVAL, 10) * 1000L

    override fun onCreate() {
        super.onCreate()
        sourceSeparationForegroundWorkerCoordinator.onPlaybackServiceStarted()
        nm = requireNotNull(getSystemService<NotificationManager>())
        createNotificationChannel()
        prepareSourceSeparationPlaybackTrace()
        if (isSourceSeparationTraceFileEnabled) {
            sourceSeparationMixProcessor.debugTraceSink = { detail ->
                traceSourceSeparationProcessor(detail)
            }
        }
        sourceSeparationMixProcessor.mixedOutputStartedSink = { outputGeneration ->
            serviceScope.launch {
                onSourceSeparationMixedOutputStarted(outputGeneration)
            }
        }
        sourceSeparationMixProcessor.outputFlushedSink = { barrierId, outputGeneration ->
            serviceScope.launch {
                onSourceSeparationOutputFlushed(barrierId, outputGeneration)
            }
        }
        sourceSeparationMixProcessor.dataPlaneStateChangedSink = { signaledState ->
            serviceScope.launch {
                onSourceSeparationDataPlaneStateChanged(signaledState)
            }
        }
        traceSourceSeparationPlayback("service.onCreate")
        cleanupCompletedSourceSeparationTemporaryDirs()

        customCommands = listOf(
            CommandButton.Builder(CommandButton.ICON_SHUFFLE_OFF)
                .setDisplayName(getString(R.string.shuffle_mode))
                .setPlayerCommand(Player.COMMAND_SET_SHUFFLE_MODE, true)
                .build(),
            CommandButton.Builder(CommandButton.ICON_SHUFFLE_ON)
                .setDisplayName(getString(R.string.shuffle_mode))
                .setPlayerCommand(Player.COMMAND_SET_SHUFFLE_MODE, false)
                .build(),
            CommandButton.Builder(CommandButton.ICON_REPEAT_OFF)
                .setDisplayName(getString(R.string.repeat_mode))
                .setPlayerCommand(Player.COMMAND_SET_REPEAT_MODE, Player.REPEAT_MODE_ALL)
                .build(),
            CommandButton.Builder(CommandButton.ICON_REPEAT_ALL)
                .setDisplayName(getString(R.string.repeat_mode))
                .setPlayerCommand(Player.COMMAND_SET_REPEAT_MODE, Player.REPEAT_MODE_ONE)
                .build(),
            CommandButton.Builder(CommandButton.ICON_REPEAT_ONE)
                .setDisplayName(getString(R.string.repeat_mode))
                .setPlayerCommand(Player.COMMAND_SET_REPEAT_MODE, Player.REPEAT_MODE_OFF)
                .build()
        )

        playerThread.start()
        player = AdvancedForwardingPlayer(
            ExoPlayer.Builder(this)
                .setWakeMode(C.WAKE_MODE_LOCAL)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .setUsage(C.USAGE_MEDIA)
                        .build(), handleAudioFocus
                )
                .setRenderersFactory(
                    BoomingMusicRenderersFactory(
                        this,
                        sourceSeparationMixProcessor,
                        balanceProcessor,
                        replayGainProcessor,
                    )
                        .setEnableAudioFloatOutput(equalizerManager.audioFloatOutput.value)
                        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                        .setMediaCodecSelector(AlacWorkaroundCodecSelector())
                        .setEnableDecoderFallback(true)
                )
                .setMediaSourceFactory(
                    musicMediaSourceFactory(
                        this,
                        preferences.getBoolean(MP3_INDEX_SEEKING, false),
                    )
                )
                .setSkipSilenceEnabled(equalizerManager.skipSilence.value)
                .setHandleAudioBecomingNoisy(true)
                .setMaxSeekToPreviousPositionMs(maxSeekToPreviousMs)
                .setSeekBackIncrementMs(seekInterval)
                .setSeekForwardIncrementMs(seekInterval)
                .setPlaybackLooper(playerThread.looper)
                .build(),
            onSeekRequested = ::onSourceSeparationSeekRequested,
        )

        player.exoPlayer.shuffleOrder = ImprovedShuffleOrder(0, 0, Random.nextLong())
        if (BuildConfig.DEBUG) {
            PlaybackContentionDiagnostics.attach(player.exoPlayer, playerThread.threadId)
        }
        player.setSequentialTimelineEnabled(sequentialTimeline)
        player.addListener(this)
        observeSourceSeparationForegroundWorker()
        observeSourceSeparationActiveSelection()
        observeSourceSeparationProcessingOwnership()
        mediaSessionPlayer = SourceSeparationMediaSessionPlayer(player) {
            sourceSeparationPlaybackResumeWhenReady = false
            sourceSeparationPlaybackPlayIntent = false
            updateSourceSeparationMediaSessionBuffering()
            updateSourceSeparationProcessingLease("mediaSessionVirtualPause")
            broadcastSourceSeparationPlaybackChanged()
        }

        mediaSession = with(MediaLibrarySession.Builder(this, mediaSessionPlayer, this)) {
            setId(packageName)
            setSessionActivity(createSessionActivityIntent())
            setBitmapLoader(CacheBitmapLoader(CoilBitmapLoader(this@PlaybackService)))
            build()
        }

        setForegroundServiceTimeoutMs(FOREGROUND_SERVICE_TIMEOUT)
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider(
                this,
                { _ -> NOTIFICATION_ID },
                CHANNEL_ID,
                R.string.playing_notification_description
            ).apply {
                setSmallIcon(R.drawable.ic_stat_music_playback)
            }
        )

        mediaStoreObserver.init(this)

        persistentStorage = PersistentStorage(this, serviceScope, player)
        persistentStorage.restoreState { items, shuffleOrder ->
            player.setMediaItems(items.mediaItems, items.startIndex, items.startPositionMs)
            player.prepare()
            if (player.shuffleModeEnabled && shuffleOrder != null) {
                player.exoPlayer.shuffleOrder = shuffleOrder
            }
        }

        sleepTimer.addFinishListener { sleepParams ->
            if (player.playWhenReady && player.isPlaying) {
                if (sleepParams.pendingQuit) {
                    player.exoPlayer.pauseAtEndOfMediaItems = true
                } else {
                    if (sleepParams.fadeOut) {
                        launchMusicFadeOut(sleepParams.fadeDuration)
                    } else {
                        player.pause()
                    }
                }
            }
        }

        preferences.registerOnSharedPreferenceChangeListener(this)
        audioOutputObserver.startObserver()

        prepareEqualizerAndSoundSettings()
        registerReceivers()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if ((!isPlaybackOngoing && !isInTransientFocusLoss) ||
            preferences.getBoolean(STOP_WHEN_CLOSED_FROM_RECENTS, false)) {
            pauseAllPlayersAndStopSelf()
        }
    }

    override fun onDestroy() {
        traceSourceSeparationPlayback("service.onDestroy")
        sourceSeparationForegroundWorkerCoordinator.onPlaybackServiceStopped()
        clearSourceSeparationPlayback(restoreOriginalItem = false, broadcast = false)
        flushSourceSeparationPlaybackTrace()
        sourceSeparationPreStartJob?.cancel()
        sourceSeparationPreStartJob = null
        cancelSourceSeparationPlaybackReadinessMonitor("serviceDestroy")
        stopSourceSeparationProcessingLease("serviceDestroy", force = true)
        super.onDestroy()
        if (bluetoothConnectedRegistered) {
            unregisterReceiver(bluetoothReceiver)
            bluetoothConnectedRegistered = false
        }
        if (headsetReceiverRegistered) {
            unregisterReceiver(headsetReceiver)
            headsetReceiverRegistered = false
        }
        eqStateHandler?.removeCallbacksAndMessages(null)
        uiHandler.removeCallbacks(headsetClickRunnable)
        serviceScope.cancel()
        preferences.unregisterOnSharedPreferenceChangeListener(this)
        audioOutputObserver.stopObserver()
        mediaStoreObserver.stop(this)
        mediaSession?.release()
        player.removeListener(this)
        sourceSeparationMixProcessor.disable()
        sourceSeparationMixProcessor.debugTraceSink = null
        sourceSeparationMixProcessor.mixedOutputStartedSink = null
        sourceSeparationMixProcessor.outputFlushedSink = null
        sourceSeparationMixProcessor.dataPlaneStateChangedSink = null
        if (BuildConfig.DEBUG) {
            PlaybackContentionDiagnostics.detach(player.exoPlayer)
        }
        player.release()
        playerThread.quitSafely()
        equalizerManager.release()
        sleepTimer.release()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_FAVORITE -> {
                toggleFavorite()
                return START_STICKY
            }
            ACTION_TOGGLE_SHUFFLE -> {
                toggleShuffle()
                return START_STICKY
            }
            ACTION_CYCLE_REPEAT -> {
                cycleRepeat()
                return START_STICKY
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaSession
    }

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo
    ): MediaSession.ConnectionResult {
        val connectionResult = super.onConnect(session, controller)
        val availableCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
            .buildUpon()

        availableCommands.add(SessionCommand(Playback.CYCLE_REPEAT, Bundle.EMPTY))
        availableCommands.add(SessionCommand(Playback.TOGGLE_SHUFFLE, Bundle.EMPTY))
        availableCommands.add(SessionCommand(Playback.TOGGLE_FAVORITE, Bundle.EMPTY))
        availableCommands.add(SessionCommand(Playback.RESTORE_PLAYBACK, Bundle.EMPTY))
        availableCommands.add(SessionCommand(Playback.SET_UNSHUFFLED_ORDER, Bundle.EMPTY))
        availableCommands.add(SessionCommand(Playback.SET_STOP_POSITION, Bundle.EMPTY))
        availableCommands.add(SessionCommand(Playback.SEPARATE_CURRENT_SONG_OFFLINE, Bundle.EMPTY))
        availableCommands.add(SessionCommand(Playback.SET_SOURCE_SEPARATION_PLAYBACK_ENABLED, Bundle.EMPTY))
        availableCommands.add(SessionCommand(Playback.SYNC_SOURCE_SEPARATION_PLAYBACK, Bundle.EMPTY))
        availableCommands.add(SessionCommand(Playback.CLEAN_SOURCE_SEPARATION_TEMPORARY_CACHE, Bundle.EMPTY))
        availableCommands.add(SessionCommand(Playback.SET_SOURCE_SEPARATION_BLEND, Bundle.EMPTY))
        availableCommands.add(SessionCommand(Playback.SET_SOURCE_SEPARATION_STEM_GAINS, Bundle.EMPTY))
        availableCommands.add(SessionCommand(Playback.NOTIFY_SOURCE_SEPARATION_CACHE_DELETED, Bundle.EMPTY))
        availableCommands.add(SessionCommand(Playback.TRACE_SOURCE_SEPARATION_PLAYBACK_MARKER, Bundle.EMPTY))
        if (BuildConfig.DEBUG) {
            availableCommands.add(
                SessionCommand(Playback.AWAIT_PLAYBACK_RESTORATION, Bundle.EMPTY)
            )
            availableCommands.add(
                SessionCommand(Playback.GET_SOURCE_SEPARATION_DEBUG_STATE, Bundle.EMPTY)
            )
            availableCommands.add(
                SessionCommand(Playback.FLUSH_SOURCE_SEPARATION_DEBUG_TRACE, Bundle.EMPTY)
            )
        }

        return MediaSession.ConnectionResult.accept(
            availableCommands.build(),
            connectionResult.availablePlayerCommands
        )
    }

    override fun onMediaButtonEvent(
        session: MediaSession,
        controllerInfo: MediaSession.ControllerInfo,
        intent: Intent
    ): Boolean {
        val ke = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        if (ke != null && (ke.keyCode == KeyEvent.KEYCODE_HEADSETHOOK || ke.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)) {
            if (ke.action == KeyEvent.ACTION_DOWN && ke.repeatCount == 0) {
                headsetClickCount++
                uiHandler.removeCallbacks(headsetClickRunnable)
                if (headsetClickCount >= 3) {
                    uiHandler.post(headsetClickRunnable)
                } else {
                    uiHandler.postDelayed(headsetClickRunnable, 300)
                }
            }
            return true
        }
        return super.onMediaButtonEvent(session, controllerInfo, intent)
    }

    override fun onAudioSessionIdChanged(audioSessionId: Int) {
        equalizerManager.setSessionId(audioSessionId)
    }

    override fun onGetLibraryRoot(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?
    ): ListenableFuture<LibraryResult<MediaItem>> {
        val libraryParams = LibraryParams.Builder()
            .setOffline(true)
            .setRecent(true)
            .setSuggested(false)
            .build()
        val mediaItem = when {
            params?.isRecent == true -> {
                MediaItem.Builder()
                    .setMediaId(MediaIDs.RECENT_SONGS)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                            .setIsBrowsable(true)
                            .setIsPlayable(false)
                            .build()
                    )
                    .build()
            }
            else -> {
                MediaItem.Builder()
                    .setMediaId(MediaIDs.ROOT)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                            .setIsBrowsable(true)
                            .setIsPlayable(false)
                            .build()
                    )
                    .build()
            }
        }
        return Futures.immediateFuture(LibraryResult.ofItem(mediaItem, libraryParams))
    }

    override fun onGetChildren(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        return serviceScope.future(IO) {
            val result = runCatching {
                libraryProvider.getChildren(this@PlaybackService, parentId)
            }
            if (result.isSuccess) {
                LibraryResult.ofItemList(result.getOrThrow(), params)
            } else {
                LibraryResult.ofError(SessionError.ERROR_UNKNOWN)
            }
        }
    }

    override fun onGetItem(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String
    ): ListenableFuture<LibraryResult<MediaItem>> {
        return serviceScope.future(IO) {
            val mediaItem = runCatching { libraryProvider.getItem(mediaId) }
                .getOrDefault(MediaItem.EMPTY)
            if (mediaItem != MediaItem.EMPTY) {
                LibraryResult.ofItem(mediaItem, null)
            } else {
                LibraryResult.ofError(SessionError.ERROR_IO)
            }
        }
    }

    override fun onSearch(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: LibraryParams?
    ): ListenableFuture<LibraryResult<Void>> {
        return serviceScope.future(IO) {
            runCatching { libraryProvider.search(query) }
                .onSuccess { session.notifySearchResultChanged(browser, query, it.size, params) }

            LibraryResult.ofVoid()
        }
    }

    override fun onGetSearchResult(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        return Futures.immediateFuture(
            LibraryResult.ofItemList(libraryProvider.searchResult, params)
        )
    }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>
    ): ListenableFuture<List<MediaItem>> {
        return serviceScope.future(IO) {
            runCatching { libraryProvider.getMediaItemsForPlayback(mediaItems) }
                .getOrDefault(emptyList())
        }
    }

    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): ListenableFuture<MediaItemsWithStartPosition> {
        sourceSeparationPlaybackContextGeneration++
        sourceSeparationPlaybackGateJob?.cancel()
        sourceSeparationPlaybackGateJob = null
        player.exoPlayer.let { exoPlayer ->
            if (exoPlayer.shuffleOrder !is ImprovedShuffleOrder && !hasSetUnshuffledOrder) {
                exoPlayer.shuffleOrder = ImprovedShuffleOrder(
                    firstIndex = player.currentMediaItemIndex,
                    length = player.mediaItemCount,
                    randomSeed = Random.nextLong()
                )
            }

            (exoPlayer.shuffleOrder as? ImprovedShuffleOrder)
                ?.playerIndex = startIndex

            hasSetUnshuffledOrder = false
        }
        return serviceScope.future(IO) {
            if (mediaSession.isAutomotiveController(controller) ||
                mediaSession.isAutoCompanionController(controller)) {
                runCatching { libraryProvider.getMediaItemsForAAOSPlayback(mediaItems) }
                    .getOrNull()
                    .let {
                        MediaItemsWithStartPosition(
                            it?.first ?: emptyList(),
                            it?.second ?: C.INDEX_UNSET,
                            startPositionMs
                        )
                    }
            } else {
                runCatching {
                    libraryProvider.getMediaItemsForPlayback(
                        mediaItems = mediaItems,
                        tryToResolveComplexPaths = true
                    )
                }.getOrDefault(emptyList()).let {
                    MediaItemsWithStartPosition(it, startIndex, startPositionMs)
                }
            }
        }.also { future ->
            future.addListener({
                val result = runCatching { future.get() }.getOrNull()
                if (result != null && result.mediaItems.isNotEmpty()) {
                    this.mediaSession?.broadcastCustomCommand(
                        SessionCommand(Playback.EVENT_PLAYBACK_STARTED, Bundle.EMPTY),
                        Bundle.EMPTY
                    )
                }
            }, ContextCompat.getMainExecutor(this))
        }
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle
    ): ListenableFuture<SessionResult> {
        return when (customCommand.customAction) {
            Playback.TOGGLE_SHUFFLE -> {
                toggleShuffle()
                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }

            Playback.CYCLE_REPEAT -> {
                cycleRepeat()
                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }

            Playback.TOGGLE_FAVORITE -> {
                toggleFavorite()
                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }

            Playback.RESTORE_PLAYBACK -> {
                val playOnStartupMode = preferences.requireString(PLAY_ON_STARTUP_MODE, PlayOnStartupMode.NEVER)
                if (playOnStartupMode != PlayOnStartupMode.NEVER) {
                    CallbackToFutureAdapter.getFuture { completer ->
                        persistentStorage.waitForRestoration {
                            if (!player.currentTimeline.isEmpty) {
                                mediaSession?.broadcastCustomCommand(
                                    SessionCommand(
                                        Playback.EVENT_PLAYBACK_RESTORED,
                                        Bundle.EMPTY
                                    ),
                                    Bundle.EMPTY
                                )
                                completer.set(SessionResult(SessionResult.RESULT_SUCCESS))
                            } else {
                                completer.setException(IllegalStateException("Timeline is empty"))
                            }
                        }
                    }
                } else {
                    Futures.immediateFuture(SessionResult(SessionError.ERROR_INVALID_STATE))
                }
            }

            Playback.AWAIT_PLAYBACK_RESTORATION -> {
                CallbackToFutureAdapter.getFuture { completer ->
                    persistentStorage.waitForRestoration {
                        completer.set(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                }
            }

            Playback.SET_UNSHUFFLED_ORDER -> {
                hasSetUnshuffledOrder = true
                player.exoPlayer.shuffleOrder = UnshuffledShuffleOrder(player.mediaItemCount)
                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }

            Playback.SET_STOP_POSITION -> {
                val newStopIndex = customCommand.customExtras.getInt("index", -1)
                val canceled = newStopIndex > -1 && newStopIndex == stopIndex
                if (canceled) {
                    player.exoPlayer.pauseAtEndOfMediaItems = false
                    stopIndex = -1
                } else if (newStopIndex == player.currentMediaItemIndex) {
                    player.exoPlayer.pauseAtEndOfMediaItems = true
                    stopIndex = -1
                } else {
                    player.exoPlayer.pauseAtEndOfMediaItems = false
                    stopIndex = newStopIndex
                }
                Futures.immediateFuture(
                    SessionResult(SessionResult.RESULT_SUCCESS, Bundle().apply {
                        putBoolean("canceled", canceled)
                    })
                )
            }

            Playback.SEPARATE_CURRENT_SONG_OFFLINE -> {
                val mediaItem = player.currentMediaItem
                    ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_INVALID_STATE))
                traceSourceSeparationPlayback(
                    "command.separateCurrentSong",
                    "mediaId=${mediaItem.mediaId}"
                )
                serviceScope.future(IO) {
                    val song = repository.songByMediaItem(mediaItem)
                    val resolved = when (val resolution = sourceSeparationRuntime.resolve(song)) {
                        is SourceSeparationRuntimeSongResolution.Ready -> resolution.song
                        is SourceSeparationRuntimeSongResolution.Unavailable ->
                            throw IllegalStateException(
                                resolution.detail ?: resolution.reason.name,
                            )
                    }
                    val result = sourceSeparationRuntime.separate(
                        song = resolved,
                        tryGpu = preferences.readSourceSeparationGpuEnabled(),
                        runClass = SourceSeparationExecutionRunClass.ManualFullSong,
                        playbackReadyWindowCountProvider = {
                            sourceSeparationPlaybackReadyWindowCount
                        },
                        windowDecodeEnabled = preferences.getBoolean(
                            SOURCE_SEPARATION_WINDOW_DECODE,
                            DEFAULT_SOURCE_SEPARATION_WINDOW_DECODE,
                        ),
                    )
                    val manifest = when (result) {
                        is SourceSeparationModelAwareEngineResult.Completed -> result.manifest
                        is SourceSeparationModelAwareEngineResult.MultiStemCompleted ->
                            result.manifest
                        is SourceSeparationModelAwareEngineResult.AlreadyCompleted -> result.manifest
                        is SourceSeparationModelAwareEngineResult.Busy ->
                            throw IllegalStateException("The exact cache entry is busy.")
                        SourceSeparationModelAwareEngineResult.ActiveModelUnavailable ->
                            throw IllegalStateException("The resolved model became unavailable.")
                    }
                    if (preferences.getBoolean(SOURCE_SEPARATION_AUTO_FLAC_COMPRESSION, true)) {
                        promoteSourceSeparationCacheWhenAvailable(shouldCancel = { false }) {
                            sourceSeparationRuntime.promote(manifest.cacheKey)
                        }
                    }
                    sourceSeparationRuntime.cleanCompletedTemporaryFiles(manifest.cacheKey)
                    val playback = requireNotNull(
                        sourceSeparationRuntime.openCompletedCache(manifest.cacheKey),
                    )
                    playback.use {
                        SessionResult(
                            SessionResult.RESULT_SUCCESS,
                            Bundle().apply {
                                putStringArrayList(
                                    "stemFiles",
                                    ArrayList(playback.stemFiles.map(File::getAbsolutePath)),
                                )
                                putStringArrayList(
                                    "stemIds",
                                    ArrayList(playback.stemIds),
                                )
                                putString("timingFile", playback.timingFile?.absolutePath)
                                putLong("elapsedMs", playback.manifest.output?.elapsedMs ?: 0L)
                                putInt("windowCount", playback.manifest.output?.windowCount ?: 0)
                            }
                        )
                    }
                }
            }

            Playback.SET_SOURCE_SEPARATION_PLAYBACK_ENABLED -> {
                val enabled = args.getBoolean(Playback.EXTRA_SOURCE_SEPARATION_ENABLED, false)
                val showMessage = args.getBoolean(
                    Playback.EXTRA_SOURCE_SEPARATION_SHOW_MESSAGE,
                    true,
                )
                val autoSyncOnTransition = args.getBoolean(
                    Playback.EXTRA_SOURCE_SEPARATION_AUTO_SYNC_ON_TRANSITION,
                    true,
                )
                val expectProcessing = args.getBoolean(
                    Playback.EXTRA_SOURCE_SEPARATION_EXPECT_PROCESSING,
                    false,
                )
                traceSourceSeparationPlayback(
                    "command.setPlaybackEnabled",
                    "enabled=$enabled showMessage=$showMessage autoSyncOnTransition=$autoSyncOnTransition " +
                            "expectProcessing=$expectProcessing " +
                            "hasBlend=${args.containsKey(Playback.EXTRA_SOURCE_SEPARATION_BLEND)}"
                )
                if (args.containsKey(Playback.EXTRA_SOURCE_SEPARATION_BLEND)) {
                    applySourceSeparationBlend(
                        args.getFloat(
                            Playback.EXTRA_SOURCE_SEPARATION_BLEND,
                            sourceSeparationMixProcessor.blend,
                        )
                    )
                }
                serviceScope.future {
                    setSourceSeparationPlaybackEnabled(
                        enabled = enabled,
                        showMessage = showMessage,
                        autoSyncOnTransition = autoSyncOnTransition,
                        expectProcessing = expectProcessing,
                    )
                }
            }

            Playback.SET_SOURCE_SEPARATION_BLEND -> {
                val blend = args.getFloat(
                    Playback.EXTRA_SOURCE_SEPARATION_BLEND,
                    sourceSeparationMixProcessor.blend,
                )
                traceSourceSeparationPlayback(
                    "command.setBlend",
                    "blend=$blend"
                )
                serviceScope.future {
                    setSourceSeparationBlend(
                        blend = blend,
                        persist = args.getBoolean(
                            Playback.EXTRA_SOURCE_SEPARATION_PERSIST_BLEND,
                            true,
                        ),
                    )
                }
            }

            Playback.SET_SOURCE_SEPARATION_STEM_GAINS -> {
                val cacheKey = args.getString(Playback.EXTRA_SOURCE_SEPARATION_CACHE_KEY).orEmpty()
                val stemIds = args.getStringArrayList(
                    Playback.EXTRA_SOURCE_SEPARATION_STEM_IDS,
                ).orEmpty()
                val gains = args.getFloatArray(
                    Playback.EXTRA_SOURCE_SEPARATION_STEM_GAINS,
                )?.toList().orEmpty()
                val commit = args.getBoolean(
                    Playback.EXTRA_SOURCE_SEPARATION_PERSIST_STEM_GAINS,
                    true,
                )
                if (commit) {
                    traceSourceSeparationPlayback(
                        "command.setStemGains",
                        "cache=${cacheKey.take(12)} stems=${stemIds.joinToString()} " +
                                "gains=${gains.joinToString()}",
                    )
                }
                serviceScope.future {
                    setSourceSeparationStemGains(
                        cacheKey = cacheKey,
                        stemIds = stemIds,
                        gains = gains,
                        commit = commit,
                    )
                }
            }

            Playback.SYNC_SOURCE_SEPARATION_PLAYBACK -> {
                val allowNewSession = args.getBoolean(
                    Playback.EXTRA_SOURCE_SEPARATION_ALLOW_NEW_SESSION,
                    sourceSeparationPlaybackAutoSyncOnTransition,
                )
                val expectProcessing = args.getBoolean(
                    Playback.EXTRA_SOURCE_SEPARATION_EXPECT_PROCESSING,
                    false,
                )
                val preferCompletedCache = args.getBoolean(
                    Playback.EXTRA_SOURCE_SEPARATION_PREFER_COMPLETED_CACHE,
                    false,
                )
                traceSourceSeparationPlayback(
                    "command.syncPlayback",
                    "allowNewSession=$allowNewSession expectProcessing=$expectProcessing " +
                            "preferCompletedCache=$preferCompletedCache"
                )
                serviceScope.future {
                    syncSourceSeparationPlayback(
                        allowNewSession = allowNewSession,
                        expectProcessing = expectProcessing,
                        preferCompletedCache = preferCompletedCache,
                    )
                }
            }

            Playback.CLEAN_SOURCE_SEPARATION_TEMPORARY_CACHE -> {
                traceSourceSeparationPlayback("command.cleanTemporaryCache")
                serviceScope.future(IO) {
                    cleanCompletedSourceSeparationTemporaryDirsNow()
                    SessionResult(SessionResult.RESULT_SUCCESS)
                }
            }

            Playback.NOTIFY_SOURCE_SEPARATION_CACHE_DELETED -> {
                traceSourceSeparationPlayback("command.cacheDeleted")
                serviceScope.future {
                    handleSourceSeparationCacheDeleted()
                }
            }

            Playback.TRACE_SOURCE_SEPARATION_PLAYBACK_MARKER -> {
                traceSourceSeparationPlayback(
                    "test.marker",
                    args.getString(Playback.EXTRA_SOURCE_SEPARATION_TRACE_MARKER).orEmpty()
                )
                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }

            Playback.GET_SOURCE_SEPARATION_DEBUG_STATE -> {
                if (!BuildConfig.DEBUG) {
                    Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
                } else {
                    Futures.immediateFuture(
                        SessionResult(
                            SessionResult.RESULT_SUCCESS,
                            sourceSeparationPlaybackBundle().apply {
                                putBoolean(
                                    Playback.EXTRA_DEBUG_OUTPUT_MUTED,
                                    sourceSeparationOutputMuted,
                                )
                                putBoolean(
                                    Playback.EXTRA_DEBUG_OUTPUT_WAITING_FOR_MIXED,
                                    sourceSeparationOutputWaitingForMixedOutput,
                                )
                                putLong(
                                    Playback.EXTRA_DEBUG_OUTPUT_FLUSH_BARRIER,
                                    sourceSeparationPendingOutputFlush?.barrierId ?: -1L,
                                )
                                putLong(
                                    Playback.EXTRA_DEBUG_OUTPUT_GENERATION,
                                    sourceSeparationPendingMixedOutputGeneration ?: -1L,
                                )
                                putBoolean(
                                    Playback.EXTRA_DEBUG_PLAYBACK_REQUESTED,
                                    sourceSeparationPlaybackRequested,
                                )
                                putBoolean(
                                    Playback.EXTRA_DEBUG_PLAYBACK_PLAY_INTENT,
                                    sourceSeparationPlaybackPlayIntent,
                                )
                                putString(
                                    Playback.EXTRA_DEBUG_WINDOW_WAIT,
                                    sourceSeparationPlaybackWindowWaitTracker.current?.toString(),
                                )
                                putBoolean(
                                    Playback.EXTRA_DEBUG_DATA_PLANE_READY,
                                    sourceSeparationMixProcessor.isDataPlaneReady(),
                                )
                                putString(
                                    Playback.EXTRA_DEBUG_WORKER_STATUS,
                                    sourceSeparationForegroundWorkerCoordinator.debugStatus(),
                                )
                            },
                        )
                    )
                }
            }

            Playback.FLUSH_SOURCE_SEPARATION_DEBUG_TRACE -> {
                if (!BuildConfig.DEBUG) {
                    Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
                } else {
                    serviceScope.future {
                        flushSourceSeparationPlaybackTraceAndWait()
                        SessionResult(
                            SessionResult.RESULT_SUCCESS,
                            Bundle().apply {
                                putString(
                                    Playback.EXTRA_DEBUG_TRACE_PATH,
                                    sourceSeparationPlaybackTraceFile?.absolutePath,
                                )
                            },
                        )
                    }
                }
            }

            else -> Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
        }
    }

    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        isForPlayback: Boolean
    ): ListenableFuture<MediaItemsWithStartPosition> {
        if (persistentStorage.restorationState.isRestored) {
            return Futures.immediateFailedFuture(IllegalStateException("No MediaItems saved"))
        } else {
            val settableFuture = SettableFuture.create<MediaItemsWithStartPosition>()
            persistentStorage.waitForMediaItems { items, shuffleOrder ->
                if (items.mediaItems.isNotEmpty()) {
                    if (player.shuffleModeEnabled && shuffleOrder != null) {
                        player.exoPlayer.shuffleOrder = shuffleOrder
                    }
                    settableFuture.set(items)
                } else {
                    settableFuture.setException(IllegalStateException("No MediaItems saved"))
                }
            }
            return settableFuture
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (player.playbackState == Player.STATE_ENDED &&
            preferences.getBoolean(CLEAR_QUEUE_ON_COMPLETION, false)) {
            player.exoPlayer.clearMediaItems()
        }
        updateSourceSeparationProcessingLease("playbackStateChanged")
        refreshMediaButtonCustomLayout()
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        persistentStorage.saveState(true)
        maybePreStartNextSourceSeparation("timelineChanged")
    }

    private fun updateSourceSeparationForegroundWorkerSong(song: Song) {
        if (song == Song.emptySong) return
        sourceSeparationForegroundWorkerCoordinator.updateSong(
            song = song,
            positionMs = player.currentPosition,
            durationMs = player.duration,
            isPlaying = isSourceSeparationForegroundWorkerClockAdvancing(),
            sourceSeparationBlend = sourceSeparationMixProcessor.blend,
        )
        maybePreStartNextSourceSeparation("songChanged")
    }

    private fun updateSourceSeparationForegroundWorkerPosition() {
        sourceSeparationForegroundWorkerCoordinator.updatePosition(
            positionMs = player.currentPosition,
            durationMs = player.duration,
            isPlaying = isSourceSeparationForegroundWorkerClockAdvancing(),
            sourceSeparationBlend = sourceSeparationMixProcessor.blend,
        )
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        val isInternalPlayWhenReadyChange =
            sourceSeparationPlaybackInternalPlayWhenReady == playWhenReady
        if (isInternalPlayWhenReadyChange) {
            sourceSeparationPlaybackInternalPlayWhenReady = null
        }
        traceSourceSeparationPlayback(
            "player.onPlayWhenReadyChanged",
            "playWhenReady=$playWhenReady reason=${playWhenReadyReasonName(reason)} internal=$isInternalPlayWhenReadyChange"
        )
        if (!isInternalPlayWhenReadyChange) {
            if (playWhenReady) {
                sourceSeparationPlaybackPlayIntent = true
                flushPendingSourceSeparationPausedBlendChange("playWhenReady")
            } else if (shouldClearSourceSeparationPlaybackPlayIntent(reason)) {
                sourceSeparationPlaybackPlayIntent = false
            }
            if (!playWhenReady) {
                sourceSeparationDataPlaneResumeWhenReady = false
            }
        }
        if (!isInternalPlayWhenReadyChange &&
            !playWhenReady &&
            sourceSeparationPlaybackIsProcessing &&
            isManualPlayWhenReadyPauseReason(reason)
        ) {
            sourceSeparationPlaybackResumeWhenReady = false
        }

        if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
            player.exoPlayer.pauseAtEndOfMediaItems = false
            sleepTimer.consumePendingQuit()
            if (stopIndex == player.currentMediaItemIndex) {
                stopIndex = -1
            }
        } else if (!isInternalPlayWhenReadyChange &&
            playWhenReady &&
            sourceSeparationPlaybackIsProcessing
        ) {
            sourceSeparationPlaybackResumeWhenReady = true
            muteSourceSeparationOutputForSwitch(
                reason = "processingUserPlay",
                waitForMixedOutput = true,
            )
            setSourceSeparationPlayWhenReady(false)
            flushSourceSeparationPausedOutput("processingUserPlay")
            scheduleSourceSeparationPlaybackGateRetry()
        } else if (!isInternalPlayWhenReadyChange &&
            playWhenReady &&
            sourceSeparationPlaybackRequested &&
            (sourceSeparationPlaybackSession == null ||
                    sourceSeparationPlaybackSession?.requiresReadinessGate == true) &&
            SourceSeparationBlendDemand.requiresSeparatedOutput(
                sourceSeparationMixProcessor.blend,
            )
        ) {
            serviceScope.launch {
                ensureSourceSeparationPlaybackReady(
                    showUnavailableMessage = false,
                    resumeWhenReady = true,
                    allowNewSession = true,
                    expectProcessing = sourceSeparationPlaybackExpectProcessing,
                    gateOnUnreadyWindow = true,
                    requiredReadyWindowCount = 1,
                )
            }
        } else if (!isInternalPlayWhenReadyChange &&
            !playWhenReady &&
            !sourceSeparationPlaybackIsProcessing
        ) {
            sourceSeparationPlaybackResumeWhenReady = false
            if (sourceSeparationPlaybackSession != null &&
                isManualPlayWhenReadyPauseReason(reason)
            ) {
                serviceScope.launch {
                    ensureSourceSeparationPlaybackReady(
                        showUnavailableMessage = false,
                        resumeWhenReady = false,
                        allowNewSession = false,
                        preferCompletedCache = true,
                        expectProcessing = sourceSeparationPlaybackExpectProcessing,
                        requiredReadyWindowCount = 1,
                    )
                }
            }
        }
        updateSourceSeparationProcessingLease("playWhenReadyChanged")
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (!isPlaying) {
            val currentDurationMs = player.mediaMetadata.durationMs ?: 0
            if (currentDurationMs > 0) {
                if (!player.currentTimeline.isEmpty) {
                    persistentStorage.saveState()
                }
            }
        }
        songPlayCountHelper.notifyPlayStateChanged(isPlaying)
        updateSourceSeparationForegroundWorkerPosition()
        updateSourceSeparationProcessingLease("isPlayingChanged")
        updateWidgets()
    }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
        updateWidgets()
        refreshMediaButtonCustomLayout()
        persistentStorage.saveState()
    }

    override fun onRepeatModeChanged(repeatMode: Int) {
        updateWidgets()
        refreshMediaButtonCustomLayout()
        persistentStorage.saveState()
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        // Media3 listener callbacks run on the player application thread. Capture the
        // state here before the bookkeeping coroutine moves to IO.
        val isPlayingAtTransition = player.isPlaying
        val activeSession = sourceSeparationPlaybackSession
        traceSourceSeparationPlayback(
            "player.onMediaItemTransition",
            "reason=${mediaItemTransitionReasonName(reason)} mediaId=${mediaItem?.mediaId} " +
                    "activeSession=${activeSession?.songId}"
        )
        sourceSeparationPlaybackContextGeneration++
        if (activeSession != null) {
            if (mediaItem?.mediaId != activeSession.songId.toString()) {
                clearSourceSeparationPlayback(restoreOriginalItem = true, broadcast = false)
            }
        }
        val shouldApplyDeferredPerSongSourceSeparationSync =
            sourceSeparationPlaybackRequested &&
                    mediaItem != null &&
                    mediaItem.mediaId != activeSession?.songId?.toString() &&
                    !sourceSeparationPlaybackAutoSyncOnTransition
        if (sourceSeparationPlaybackRequested && mediaItem != null &&
            mediaItem.mediaId != activeSession?.songId?.toString()
        ) {
            val expectProcessingOnTransition = prepareSourceSeparationPlaybackTransition(
                reason = "transition",
            )
            if (sourceSeparationPlaybackAutoSyncOnTransition) {
                serviceScope.launch {
                    ensureSourceSeparationPlaybackReady(
                        showUnavailableMessage = false,
                        resumeWhenReady = false,
                        gateOnUnreadyWindow = shouldGateCurrentSourceSeparationWindow(),
                        requiredReadyWindowCount = 1,
                        expectProcessing = expectProcessingOnTransition,
                    )
                }
            } else {
                traceSourceSeparationPlayback(
                    "player.onMediaItemTransition.deferAutoSync",
                    "reason=clientBlendRequired"
                )
            }
        }

        serviceScope.launch(IO) {
            val newSong = repository.songByMediaItem(mediaItem)

            val previousSong = songPlayCountHelper.song
            val shouldBumpPlayCount = songPlayCountHelper.shouldBumpPlayCount()
            songPlayCountHelper.notifySongChanged(newSong, isPlayingAtTransition)

            if (newSong != Song.emptySong) {
                val deferredPerSongBlend = if (shouldApplyDeferredPerSongSourceSeparationSync) {
                    sourceSeparationForegroundWorkerCoordinator.recordedBlendForSong(newSong)
                        ?: SourceSeparationBlendDemand.CENTER_BLEND
                } else {
                    null
                }
                withContext(Main) {
                    val deferredMediaItem = mediaItem
                    if (deferredPerSongBlend != null && deferredMediaItem != null) {
                        applyDeferredPerSongSourceSeparationTransition(
                            mediaItem = deferredMediaItem,
                            song = newSong,
                            blend = deferredPerSongBlend,
                        )
                    } else {
                        updateSourceSeparationForegroundWorkerSong(newSong)
                    }
                }
                replayGainProcessor.currentGain = ReplayGainTagExtractor.getReplayGain(newSong)
                if (preferences.getBoolean(ENABLE_HISTORY, true)) {
                    repository.upsertSongInHistory(newSong)
                }
                if (NetworkFeature.Lastfm.NowPlaying.isAvailable) {
                    launch { repository.updateNowPlaying(ScrobblingService.Lastfm, newSong) }
                }
                if (NetworkFeature.ListenBrainz.NowPlaying.isAvailable) {
                    launch { repository.updateNowPlaying(ScrobblingService.ListenBrainz, newSong) }
                }
            }
            if (previousSong != Song.emptySong) {
                val timestampMillis = System.currentTimeMillis()
                val timestampSeconds = (timestampMillis / 1000)
                if (shouldBumpPlayCount) {
                    repository.insertOrIncrementPlayCount(
                        song = previousSong,
                        timePlayed = timestampMillis
                    )
                    if (NetworkFeature.Lastfm.Scrobbling.isAvailable) {
                        launch { repository.scrobble(ScrobblingService.Lastfm, previousSong, timestampSeconds) }
                    }
                    if (NetworkFeature.ListenBrainz.Scrobbling.isAvailable) {
                        launch { repository.scrobble(ScrobblingService.ListenBrainz, previousSong, timestampSeconds) }
                    }
                } else if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
                    repository.insertOrIncrementSkipCount(previousSong)
                }
            }
        }

        if (player.currentMediaItemIndex == stopIndex) {
            player.exoPlayer.pauseAtEndOfMediaItems = true
        }

        persistentStorage.saveState()
        updateWidgets(force = true)
    }

    private suspend fun applyDeferredPerSongSourceSeparationTransition(
        mediaItem: MediaItem,
        song: Song,
        blend: Float,
    ) {
        if (!sourceSeparationPlaybackRequested ||
            sourceSeparationPlaybackAutoSyncOnTransition ||
            player.currentMediaItem?.mediaId != mediaItem.mediaId
        ) {
            traceSourceSeparationPlayback(
                "perSongTransition.skip",
                "songId=${song.id} requested=$sourceSeparationPlaybackRequested " +
                        "autoSync=$sourceSeparationPlaybackAutoSyncOnTransition " +
                        "currentMediaId=${player.currentMediaItem?.mediaId} targetMediaId=${mediaItem.mediaId}"
            )
            updateSourceSeparationForegroundWorkerSong(song)
            return
        }

        val normalizedBlend = blend.coerceIn(0f, 1f)
        traceSourceSeparationPlayback(
            "perSongTransition.apply",
            "songId=${song.id} blend=$normalizedBlend"
        )
        applySourceSeparationBlend(normalizedBlend)
        updateSourceSeparationForegroundWorkerSong(song)

        if (SourceSeparationBlendDemand.isCentered(normalizedBlend)) {
            clearSourceSeparationPlayback(restoreOriginalItem = true, broadcast = false)
            clearSourceSeparationPlaybackProcessing()
            restoreSourceSeparationOutputVolume("perSongTransition.defaultBlend")
            broadcastSourceSeparationPlaybackChanged()
            maybePreStartNextSourceSeparation("perSongTransition.defaultBlend")
            return
        }

        val autoStartDecision =
            sourceSeparationForegroundWorkerCoordinator.autoStartDecision(song, normalizedBlend)
        if (autoStartDecision.shouldStart && player.currentMediaItem?.mediaId == mediaItem.mediaId) {
            sourceSeparationForegroundWorkerCoordinator.requestPlaybackDemandSong(song)
        }
        val expectProcessing = autoStartDecision.shouldStart ||
                autoStartDecision.shouldWaitForProcessingCache
        setSourceSeparationPlaybackExpectProcessing(expectProcessing)
        ensureSourceSeparationPlaybackReady(
            showUnavailableMessage = false,
            resumeWhenReady = false,
            allowNewSession = true,
            expectProcessing = expectProcessing,
            gateOnUnreadyWindow = shouldGateCurrentSourceSeparationWindow(),
            requiredReadyWindowCount = 1,
        )
        maybePreStartNextSourceSeparation("perSongTransition")
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int
    ) {
        val isInternalRecoveryFlushSeek = consumeSourceSeparationInternalSeekTarget(
            newPosition = newPosition,
            reason = reason,
        )
        traceSourceSeparationPlayback(
            "player.onPositionDiscontinuity",
            "reason=${discontinuityReasonName(reason)} old=${oldPosition.positionMs} " +
                    "new=${newPosition.positionMs} oldIndex=${oldPosition.mediaItemIndex} " +
                    "newIndex=${newPosition.mediaItemIndex} currentIndex=${player.currentMediaItemIndex} " +
                    "internalRecoveryFlush=$isInternalRecoveryFlushSeek " +
                    "session=${sourceSeparationPlaybackSession?.traceSummary()} " +
                    "expectProcessing=$sourceSeparationPlaybackExpectProcessing"
        )
        updateSourceSeparationForegroundWorkerPosition()
        if (oldPosition.mediaItemIndex != newPosition.mediaItemIndex &&
            sourceSeparationPlaybackRequested
        ) {
            prepareSourceSeparationPlaybackTransition(
                reason = "positionDiscontinuity",
            )
        }
        if ((sourceSeparationPlaybackSession != null || sourceSeparationPlaybackIsProcessing) &&
            reason == Player.DISCONTINUITY_REASON_SEEK
        ) {
            val activeSession = sourceSeparationPlaybackSession
            val activeWindowWait = sourceSeparationPlaybackWindowWaitTracker.current
            val wasWaitingForUnreadyWindow = activeWindowWait != null
            val requiredReadyWindowCount =
                activeWindowWait?.requiredReadyWindowCount ?: 1
            if (wasWaitingForUnreadyWindow && !isInternalRecoveryFlushSeek) {
                val retargetedWait = requireNotNull(
                    sourceSeparationPlaybackWindowWaitTracker.retarget(
                        anchorPositionMs = newPosition.positionMs,
                        requiredReadyWindowCount = sourceSeparationPlaybackReadyWindowCount,
                    ),
                )
                sourceSeparationPlaybackContextGeneration++
                sourceSeparationPlaybackGateJob?.cancel()
                sourceSeparationPlaybackGateJob = null
                traceSourceSeparationPlayback(
                    "playback.seek.resetRecoveryWaterline",
                    "position=${newPosition.positionMs} " +
                            "waitEpoch=${retargetedWait.epoch} " +
                            "requiredReadyWindows=${retargetedWait.requiredReadyWindowCount}",
                )
            }
            gateSourceSeparationDataPlaneUntilReady(activeSession, "seek")
            if (wasWaitingForUnreadyWindow && isInternalRecoveryFlushSeek) {
                val preservedWait = requireNotNull(activeWindowWait)
                traceSourceSeparationPlayback(
                    "playback.seek.preserveRecoveryWaterline",
                    "position=${newPosition.positionMs} " +
                            "waitEpoch=${preservedWait.epoch} " +
                            "anchor=${preservedWait.anchorPositionMs} " +
                            "requiredReadyWindows=${preservedWait.requiredReadyWindowCount}",
                )
                scheduleSourceSeparationPlaybackGateRetry()
                return
            }
            if (activeSession != null &&
                !shouldCheckForDeferredCompletedPlaybackUpgrade(activeSession) &&
                !activeSession.requiresReadinessGate &&
                !sourceSeparationPlaybackIsProcessing &&
                !sourceSeparationPlaybackExpectProcessing
            ) {
                traceSourceSeparationPlayback(
                    "check.seek.skip",
                    "reason=preferredCompletedSession position=${newPosition.positionMs}"
                )
                return
            }
            serviceScope.launch {
                ensureSourceSeparationPlaybackReady(
                    showUnavailableMessage = false,
                    resumeWhenReady = sourceSeparationPlaybackResumeWhenReady || player.playWhenReady,
                    preferCompletedCache = true,
                    expectProcessing = sourceSeparationPlaybackExpectProcessing,
                    gateOnUnreadyWindow =
                        wasWaitingForUnreadyWindow ||
                                shouldGateCurrentSourceSeparationWindow(),
                    requiredReadyWindowCount = requiredReadyWindowCount,
                )
            }
        } else if (sourceSeparationPlaybackSession != null) {
            requestSourceSeparationDataPlaneSeek(
                session = sourceSeparationPlaybackSession,
                positionMs = newPosition.positionMs,
                reason = "${discontinuityReasonName(reason)}Discontinuity",
            )
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        traceSourceSeparationPlayback(
            "player.onError",
            "code=${error.errorCodeName} message=${error.message} " +
                    "cause=${error.cause?.javaClass?.simpleName}:${error.cause?.message}"
        )
        restoreSourceSeparationOutputVolume("playerError")
        val nextMediaIndex = player.nextMediaItemIndex
        if (nextMediaIndex != C.INDEX_UNSET &&
            errorRecoveryRetryCount < MAX_RETRY_COUNT_AFTER_ERROR) {
            errorRecoveryRetryCount++
            player.seekToNextMediaItem()
            player.prepare()
        }
        showToast(getString(R.string.playback_error_code, error.errorCodeName))
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (events.contains(Player.EVENT_IS_PLAYING_CHANGED) ||
            events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) ||
            events.contains(Player.EVENT_TIMELINE_CHANGED)) {
            if (player.isPlaying) errorRecoveryRetryCount = 0
            cancelSleepTimerFadeOut()
        }
        if (events.contains(Player.EVENT_IS_PLAYING_CHANGED) &&
            !events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
            updateEqualizerSessionState(player.isPlaying)
            updateSourceSeparationForegroundWorkerPosition()
        }
        if (events.contains(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED) &&
            !events.contains(Player.EVENT_TIMELINE_CHANGED)) {
            if (player.shuffleModeEnabled && persistentStorage.restorationState.isRestored) {
                this.player.exoPlayer.shuffleOrder = ImprovedShuffleOrder(
                    firstIndex = player.currentMediaItemIndex,
                    length = player.mediaItemCount,
                    randomSeed = Random.nextLong()
                )
            }
        }
    }

    /*
    override fun onTracksChanged(tracks: Tracks) {
        var sampleRate = -1
        var channelCount = -1
        for (group in tracks.groups) {
            if (group.type == C.TRACK_TYPE_AUDIO) {
                for (i in 0 until group.length) {
                    if (group.isTrackSelected(i)) {
                        val format = group.getTrackFormat(i)
                        sampleRate = format.sampleRate
                        channelCount = format.channelCount
                        break
                    }
                }
            }
        }
        audioOutputObserver.updatePlaybackFormat(sampleRate, channelCount)
    }
     */

    override fun onSharedPreferenceChanged(preferences: SharedPreferences, key: String?) {
        when (key) {
            QUEUE_NEXT_MODE -> {
                player.setSequentialTimelineEnabled(sequentialTimeline)
            }

            ENABLE_HISTORY -> {
                if (!preferences.getBoolean(key, true)) {
                    serviceScope.launch(IO) {
                        repository.clearSongHistory()
                    }
                }
            }

            IGNORE_AUDIO_FOCUS -> {
                player.setAudioAttributes(player.audioAttributes, handleAudioFocus)
            }

            REWIND_WITH_BACK -> {
                player.exoPlayer.setMaxSeekToPreviousPositionMs(maxSeekToPreviousMs)
            }

            SEEK_INTERVAL -> {
                player.exoPlayer.setSeekBackIncrementMs(seekInterval)
                player.exoPlayer.setSeekForwardIncrementMs(seekInterval)
            }

            SOURCE_SEPARATION_AUTO_START,
            SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT -> {
                maybePreStartNextSourceSeparation("prefChanged")
            }

            WIDGET_DYNAMIC_COLORS,
            WIDGET_SMALL_LAYOUT_STYLE,
            WIDGET_IMAGE_CORNER_RADIUS,
            WIDGET_THIRD_LINE_CONTENT -> {
                updateWidgets()
            }
        }
    }

    private fun toggleShuffle() {
        player.shuffleModeEnabled = !player.shuffleModeEnabled
    }

    private suspend fun setSourceSeparationPlaybackEnabled(
        enabled: Boolean,
        showMessage: Boolean,
        autoSyncOnTransition: Boolean,
        expectProcessing: Boolean,
    ): SessionResult {
        traceSourceSeparationPlayback(
            "playback.setEnabled.start",
            "enabled=$enabled showMessage=$showMessage autoSyncOnTransition=$autoSyncOnTransition " +
                    "expectProcessing=$expectProcessing"
        )
        sourceSeparationPlaybackRequested = enabled
        sourceSeparationPlaybackAutoSyncOnTransition = autoSyncOnTransition
        if (enabled && (player.playWhenReady || player.isPlaying)) {
            sourceSeparationPlaybackPlayIntent = true
        }
        setSourceSeparationPlaybackExpectProcessing(enabled && expectProcessing)
        val result = if (enabled) {
            ensureSourceSeparationPlaybackReady(
                showUnavailableMessage = showMessage,
                resumeWhenReady = false,
                allowNewSession = true,
                expectProcessing = expectProcessing,
                gateOnUnreadyWindow = shouldGateCurrentSourceSeparationWindow(),
                requiredReadyWindowCount = 1,
            )
        } else {
            setSourceSeparationPlaybackExpectProcessing(false)
            sourceSeparationPlaybackResumeWhenReady = false
            sourceSeparationPlaybackPlayIntent = false
            sourceSeparationPlaybackGateJob?.cancel()
            sourceSeparationPlaybackGateJob = null
            updateSourceSeparationProcessingLease("playbackDisabled")
            if (sourceSeparationPlaybackSession != null ||
                sourceSeparationPlaybackIsProcessing
            ) {
                clearSourceSeparationPlayback(restoreOriginalItem = true)
            } else {
                traceSourceSeparationPlayback(
                    "playback.setEnabled.clear.skip",
                    "reason=alreadyCleared"
                )
            }
            restoreSourceSeparationOutputVolume("playbackDisabled")
            sourceSeparationPlaybackResult(SessionResult.RESULT_SUCCESS)
        }
        traceSourceSeparationPlayback(
            "playback.setEnabled.end",
            "enabled=$enabled result=${result.resultCode}"
        )
        if (enabled) {
            maybePreStartNextSourceSeparation("playbackEnabled")
        } else {
            sourceSeparationPreStartJob?.cancel()
            sourceSeparationPreStartJob = null
        }
        return result
    }

    private suspend fun syncSourceSeparationPlayback(
        allowNewSession: Boolean = sourceSeparationPlaybackAutoSyncOnTransition,
        expectProcessing: Boolean = false,
        preferCompletedCache: Boolean = false,
    ): SessionResult {
        traceSourceSeparationPlayback(
            "playback.sync.start",
            "allowNewSession=$allowNewSession expectProcessing=$expectProcessing " +
                    "preferCompletedCache=$preferCompletedCache"
        )
        if (!sourceSeparationPlaybackRequested) {
            val result = sourceSeparationPlaybackResult(SessionResult.RESULT_SUCCESS)
            traceSourceSeparationPlayback("playback.sync.skip", "requested=false")
            return result
        }
        if (preferCompletedCache &&
            SourceSeparationCompletedCacheUpgradePolicy.shouldDeferAutomaticUpgrade(
                hasActiveSession = sourceSeparationPlaybackSession != null,
                playWhenReady = player.playWhenReady,
                hasPlayIntent = sourceSeparationPlaybackPlayIntent,
                isProcessing = sourceSeparationPlaybackIsProcessing,
            )
        ) {
            val result = sourceSeparationPlaybackResult(SessionResult.RESULT_SUCCESS)
            traceSourceSeparationPlayback(
                "playback.sync.skip",
                "reason=completedCacheUpgradeDeferred " +
                        "playWhenReady=${player.playWhenReady} " +
                        "playIntent=$sourceSeparationPlaybackPlayIntent",
            )
            return result
        }
        val effectiveExpectProcessing =
            (expectProcessing || sourceSeparationPlaybackExpectProcessing) &&
                    SourceSeparationBlendDemand.requiresSeparatedOutput(
                        sourceSeparationMixProcessor.blend,
                    )
        setSourceSeparationPlaybackExpectProcessing(effectiveExpectProcessing)
        val result = ensureSourceSeparationPlaybackReady(
            showUnavailableMessage = false,
            allowNewSession = allowNewSession,
            resumeWhenReady = false,
            preferCompletedCache = preferCompletedCache,
            expectProcessing = effectiveExpectProcessing,
            gateOnUnreadyWindow = shouldGateCurrentSourceSeparationWindow(),
            requiredReadyWindowCount = 1,
        )
        traceSourceSeparationPlayback("playback.sync.end", "result=${result.resultCode}")
        return result
    }

    private suspend fun handleSourceSeparationCacheDeleted(): SessionResult {
        traceSourceSeparationPlayback("playback.cacheDeleted.start")
        val result = ensureSourceSeparationPlaybackReady(
            showUnavailableMessage = false,
            resumeWhenReady = false,
            allowNewSession = false,
            preferCompletedCache = true,
            expectProcessing = false,
            requiredReadyWindowCount = 1,
        )
        traceSourceSeparationPlayback(
            "playback.cacheDeleted.end",
            "result=${result.resultCode}"
        )
        return result
    }

    private suspend fun ensureSourceSeparationPlaybackReady(
        showUnavailableMessage: Boolean = true,
        resumeWhenReady: Boolean = sourceSeparationPlaybackResumeWhenReady,
        allowNewSession: Boolean = true,
        preferCompletedCache: Boolean = false,
        expectProcessing: Boolean = false,
        gateOnUnreadyWindow: Boolean = false,
        requiredReadyWindowCount: Int = 1,
    ): SessionResult {
        traceSourceSeparationPlayback(
            "check.request",
            "showMessage=$showUnavailableMessage gateOnUnready=$gateOnUnreadyWindow " +
                    "resumeWhenReady=$resumeWhenReady allowNewSession=$allowNewSession " +
                    "preferCompletedCache=$preferCompletedCache expectProcessing=$expectProcessing " +
                    "requiredReadyWindows=${requiredReadyWindowCount.coerceAtLeast(1)}"
        )
        return sourceSeparationPlaybackReadinessMutex.withLock {
            val checkId = ++sourceSeparationPlaybackCheckSeq
            ensureSourceSeparationPlaybackReadyLocked(
                checkId = checkId,
                showUnavailableMessage = showUnavailableMessage,
                resumeWhenReady = resumeWhenReady,
                allowNewSession = allowNewSession,
                preferCompletedCache = preferCompletedCache,
                expectProcessing = expectProcessing,
                gateOnUnreadyWindow = gateOnUnreadyWindow,
                requiredReadyWindowCount = requiredReadyWindowCount,
            )
        }
    }

    private suspend fun ensureSourceSeparationPlaybackReadyLocked(
        checkId: Long,
        showUnavailableMessage: Boolean,
        resumeWhenReady: Boolean,
        allowNewSession: Boolean,
        preferCompletedCache: Boolean,
        expectProcessing: Boolean,
        gateOnUnreadyWindow: Boolean,
        requiredReadyWindowCount: Int,
    ): SessionResult {
        traceSourceSeparationPlayback(
            "check.start",
            "id=$checkId showMessage=$showUnavailableMessage gateOnUnready=$gateOnUnreadyWindow " +
                    "resumeWhenReady=$resumeWhenReady allowNewSession=$allowNewSession " +
                    "preferCompletedCache=$preferCompletedCache expectProcessing=$expectProcessing " +
                    "requiredReadyWindows=${requiredReadyWindowCount.coerceAtLeast(1)}"
        )
        if (!sourceSeparationPlaybackRequested) {
            setSourceSeparationPlaybackExpectProcessing(false)
            clearSourceSeparationPlaybackProcessing()
            traceSourceSeparationPlayback("check.skip", "id=$checkId requested=false")
            return sourceSeparationPlaybackResult(SessionResult.RESULT_SUCCESS)
        }
        val effectiveExpectProcessing =
            sourceSeparationPlaybackExpectProcessing &&
                    SourceSeparationBlendDemand.requiresSeparatedOutput(
                        sourceSeparationMixProcessor.blend,
                    )
        if (expectProcessing && !effectiveExpectProcessing) {
            traceSourceSeparationPlayback(
                "check.expectProcessing.superseded",
                "id=$checkId requested=$expectProcessing " +
                        "current=$sourceSeparationPlaybackExpectProcessing " +
                        "blend=${sourceSeparationMixProcessor.blend}"
            )
        }
        val waitingForExpectedProcessingTooLong =
            SourceSeparationExpectedProcessingTimeoutPolicy.hasTimedOut(
                expected = effectiveExpectProcessing,
                startedAtMs = sourceSeparationPlaybackExpectProcessingStartedAtMs,
                nowMs = SystemClock.elapsedRealtime(),
                waitingCacheKey = sourceSeparationPlaybackProcessingCacheKey,
                activeOwnerCacheKey = sourceSeparationProcessingOwnershipHandoff
                    .stateFlow.value.activeOwner?.owner?.cacheKey,
                pendingResolutionTimeoutMs =
                    SOURCE_SEPARATION_EXPECT_PROCESSING_PENDING_TIMEOUT_MS,
                concreteTargetTimeoutMs =
                    SOURCE_SEPARATION_EXPECT_PROCESSING_TARGET_TIMEOUT_MS,
            )
        if (waitingForExpectedProcessingTooLong) {
            traceSourceSeparationPlayback(
                "check.expectProcessing.timeout",
                "id=$checkId waitedMs=${SystemClock.elapsedRealtime() - sourceSeparationPlaybackExpectProcessingStartedAtMs}"
            )
            setSourceSeparationPlaybackExpectProcessing(false)
        } else {
            setSourceSeparationPlaybackExpectProcessing(effectiveExpectProcessing)
        }
        val shouldWaitForExpectedProcessing =
            effectiveExpectProcessing && !waitingForExpectedProcessingTooLong

        val contextGeneration = sourceSeparationPlaybackContextGeneration
        val mediaItemIndex = player.currentMediaItemIndex
        val mediaItem = player.currentMediaItem
            ?: run {
                clearSourceSeparationPlaybackProcessing()
                traceSourceSeparationPlayback("check.unavailable", "id=$checkId mediaItem=null")
                return sourceSeparationPlaybackUnavailable(
                    showMessage = showUnavailableMessage,
                    resultCode = SessionError.ERROR_INVALID_STATE,
                    message = getString(R.string.source_separation_playback_no_song),
                )
            }

        val song = withContext(IO) {
            repository.songByMediaItem(mediaItem)
        }
        if (!isSourceSeparationPlaybackCheckCurrent(
                checkId = checkId,
                contextGeneration = contextGeneration,
                mediaItem = mediaItem,
                mediaItemIndex = mediaItemIndex,
                stage = "after song lookup",
            )
        ) {
            return sourceSeparationPlaybackResult(SessionResult.RESULT_SUCCESS)
        }
        if (!sourceSeparationPlaybackRequested) {
            clearSourceSeparationPlaybackProcessing()
            traceSourceSeparationPlayback("check.skip", "id=$checkId requested=false after song lookup")
            return sourceSeparationPlaybackResult(SessionResult.RESULT_SUCCESS)
        }
        if (song == Song.emptySong) {
            clearSourceSeparationPlaybackProcessing()
            traceSourceSeparationPlayback("check.unavailable", "id=$checkId song=empty")
            return sourceSeparationPlaybackUnavailable(
                showMessage = showUnavailableMessage,
                resultCode = SessionError.ERROR_INVALID_STATE,
                message = getString(R.string.source_separation_playback_no_song),
            )
        }

        val selection = sourceSeparationExecutionSelection.current()
        var activeSession = sourceSeparationPlaybackSession
        if (activeSession != null && !SourceSeparationExactActivePlaybackPolicy.canReuseSession(
                activeSelectionGeneration = selection.generation,
                sessionSelectionGeneration = activeSession.selectionGeneration,
                activeSelectionMatchesSession = selection == activeSession.selection,
                sessionCacheKey = activeSession.cacheKey,
                resolvedSessionCacheKey = activeSession.runtimeSong.cacheKey,
            )) {
            traceSourceSeparationPlayback(
                "check.activeSession.selectionMismatch",
                    "id=$checkId sessionGeneration=${activeSession.selectionGeneration} " +
                        "activeGeneration=${selection.generation} " +
                        "sessionFamily=${activeSession.selection.family} " +
                        "activeFamily=${selection.family} " +
                        "cache=${activeSession.cacheKey.take(12)}",
            )
            clearSourceSeparationPlayback(restoreOriginalItem = true, broadcast = false)
            activeSession = null
        }
        activeSession
            ?.takeIf { it.songId == song.id }
            ?.let { session ->
                return checkActiveSourceSeparationRuntimeSession(
                    checkId = checkId,
                    contextGeneration = contextGeneration,
                    mediaItem = mediaItem,
                    mediaItemIndex = mediaItemIndex,
                    song = song,
                    session = session,
                    preferCompletedCache = preferCompletedCache,
                    resumeWhenReady = resumeWhenReady,
                    showUnavailableMessage = showUnavailableMessage,
                    readyWindowCount = requiredReadyWindowCount.coerceAtLeast(1),
                    gateOnUnreadyWindow = gateOnUnreadyWindow,
                )
            }

        if (!allowNewSession) {
            traceSourceSeparationPlayback(
                "check.newSession.skip",
                "id=$checkId reason=clientBlendRequired songId=${song.id}"
            )
            return sourceSeparationPlaybackResult(SessionResult.RESULT_SUCCESS)
        }

        val runtimeSong = when (val resolution = withContext(IO) {
            sourceSeparationRuntime.resolveForPlayback(song)
        }) {
            is SourceSeparationRuntimeSongResolution.Ready -> resolution.song
            is SourceSeparationRuntimeSongResolution.Unavailable -> {
                setSourceSeparationPlaybackExpectProcessing(false)
                clearSourceSeparationPlaybackProcessing()
                traceSourceSeparationPlayback(
                    "check.newSession.unresolved",
                    "id=$checkId reason=${resolution.reason} detail=${resolution.detail}",
                )
                return sourceSeparationPlaybackUnavailable(
                    showMessage = showUnavailableMessage,
                    resultCode = SessionError.ERROR_INVALID_STATE,
                    message = getString(R.string.source_separation_playback_unavailable),
                )
            }
        }
        val activeWindowWait = alignSourceSeparationPlaybackWindowWait(
            cacheKey = runtimeSong.cacheKey,
            fallbackPositionMs = player.currentPosition.coerceAtLeast(0),
        )
        val windowWaitEpoch = activeWindowWait?.epoch
        val positionMs = activeWindowWait?.anchorPositionMs
            ?: player.currentPosition.coerceAtLeast(0)
        val effectiveReadyWindowCount = activeWindowWait?.requiredReadyWindowCount
            ?: requiredReadyWindowCount.coerceAtLeast(1)
        val recoveryReadyWindowCount = activeWindowWait?.requiredReadyWindowCount
            ?: sourceSeparationPlaybackReadyWindowCount.coerceAtLeast(1)
        val status = withContext(IO) {
            runCatching {
                sourceSeparationRuntime.playableStatus(
                    song = runtimeSong,
                    playbackPositionMs = positionMs,
                    readyWindowCount = effectiveReadyWindowCount,
                )
            }.getOrDefault(SourceSeparationModelAwarePlayableStatus.Unavailable)
        }
        if (!isSourceSeparationPlaybackCheckCurrent(
                checkId = checkId,
                contextGeneration = contextGeneration,
                mediaItem = mediaItem,
                mediaItemIndex = mediaItemIndex,
                stage = "after new status",
            )
        ) {
            status.closePlayback()
            return sourceSeparationPlaybackResult(SessionResult.RESULT_SUCCESS)
        }
        if (!sourceSeparationPlaybackWindowWaitTracker.isCurrent(windowWaitEpoch)) {
            status.closePlayback()
            traceSourceSeparationPlayback(
                "check.staleWindowWait",
                "id=$checkId stage=afterNewStatus expectedEpoch=$windowWaitEpoch " +
                        "currentEpoch=${sourceSeparationPlaybackWindowWaitTracker.current?.epoch}",
            )
            return sourceSeparationPlaybackResult(SessionResult.RESULT_SUCCESS)
        }
        traceSourceSeparationPlayback(
            "check.newSession.status",
            "id=$checkId songId=${song.id} cache=${runtimeSong.cacheKey.take(12)} " +
                    "position=$positionMs readyWindows=$effectiveReadyWindowCount " +
                    "waitEpoch=$windowWaitEpoch status=${status.traceName()}"
        )
        if (!sourceSeparationPlaybackRequested) {
            status.closePlayback()
            clearSourceSeparationPlaybackProcessing()
            traceSourceSeparationPlayback("check.skip", "id=$checkId requested=false after new status")
            return sourceSeparationPlaybackResult(SessionResult.RESULT_SUCCESS)
        }
        if (status == SourceSeparationModelAwarePlayableStatus.Processing) {
            if (!isSourceSeparationPlaybackCheckCurrent(
                    checkId = checkId,
                    contextGeneration = contextGeneration,
                    mediaItem = mediaItem,
                    mediaItemIndex = mediaItemIndex,
                    stage = "before new session processing wait",
                )
            ) {
                status.closePlayback()
                return sourceSeparationPlaybackResult(SessionResult.RESULT_SUCCESS)
            }
            if (!gateOnUnreadyWindow) {
                clearSourceSeparationPlaybackProcessing(
                    preserveExpectedProcessing = effectiveExpectProcessing,
                )
                traceSourceSeparationPlayback(
                    "check.newSession.processing.continueOriginal",
                    "id=$checkId",
                )
                return sourceSeparationPlaybackResult(SessionResult.RESULT_SUCCESS)
            }
            traceSourceSeparationPlayback("check.newSession.processing", "id=$checkId")
            return waitForSourceSeparationPlayback(
                source = "newSession",
                processingCacheKey = runtimeSong.cacheKey,
                waitingPositionMs = positionMs,
                requiredReadyWindowCount = recoveryReadyWindowCount,
                restoreOriginalItem = false,
                resumeWhenReady = resumeWhenReady,
                showMessage = showUnavailableMessage,
            )
        }
        if (shouldWaitForExpectedProcessing &&
            status == SourceSeparationModelAwarePlayableStatus.Unavailable
        ) {
            if (!isSourceSeparationPlaybackCheckCurrent(
                    checkId = checkId,
                    contextGeneration = contextGeneration,
                    mediaItem = mediaItem,
                    mediaItemIndex = mediaItemIndex,
                    stage = "before new session expected processing wait",
                )
            ) {
                return sourceSeparationPlaybackResult(SessionResult.RESULT_SUCCESS)
            }
            if (!gateOnUnreadyWindow) {
                clearSourceSeparationPlaybackProcessing(
                    preserveExpectedProcessing = effectiveExpectProcessing,
                )
                traceSourceSeparationPlayback(
                    "check.newSession.expectProcessing.continueOriginal",
                    "id=$checkId",
                )
                return sourceSeparationPlaybackResult(SessionResult.RESULT_SUCCESS)
            }
            traceSourceSeparationPlayback("check.newSession.expectProcessing", "id=$checkId")
            return waitForSourceSeparationPlayback(
                source = "newSession.expectProcessing",
                processingCacheKey = runtimeSong.cacheKey,
                waitingPositionMs = positionMs,
                requiredReadyWindowCount = recoveryReadyWindowCount,
                restoreOriginalItem = false,
                resumeWhenReady = resumeWhenReady,
                showMessage = showUnavailableMessage,
            )
        }
        val playback = (status as? SourceSeparationModelAwarePlayableStatus.Ready)?.playback
            ?: run {
                setSourceSeparationPlaybackExpectProcessing(false)
                clearSourceSeparationPlaybackProcessing()
                traceSourceSeparationPlayback("check.newSession.unavailable", "id=$checkId manifest=null")
                return sourceSeparationPlaybackUnavailable(
                    showMessage = showUnavailableMessage,
                    resultCode = SessionError.ERROR_INVALID_STATE,
                    message = getString(R.string.source_separation_playback_cache_not_found),
                )
            }
        var adopted = false
        try {
            val manifest = playback.manifest
            val output = manifest.output
                ?: return sourceSeparationPlaybackUnavailable(
                    showMessage = showUnavailableMessage,
                    resultCode = SessionError.ERROR_INVALID_STATE,
                    message = getString(R.string.source_separation_playback_cache_not_found),
                )
            val stemFiles = playback.stemFiles
            val stemIds = playback.stemIds
            val availableFiles = withContext(IO) {
                stemFiles.map(File::isFile)
            }
            if (availableFiles.any { available -> !available }) {
                traceSourceSeparationPlayback(
                    "check.newSession.missingFiles",
                    "id=$checkId available=$availableFiles stems=${stemIds.joinToString()}"
                )
                return sourceSeparationPlaybackUnavailable(
                    showMessage = showUnavailableMessage,
                    resultCode = SessionError.ERROR_INVALID_STATE,
                    message = getString(R.string.source_separation_playback_stem_files_missing),
                )
            }

            val index = player.currentMediaItemIndex
            if (index == C.INDEX_UNSET) {
                clearSourceSeparationPlaybackProcessing()
                traceSourceSeparationPlayback("check.newSession.invalidIndex", "id=$checkId")
                return sourceSeparationPlaybackUnavailable(
                    showMessage = showUnavailableMessage,
                    resultCode = SessionError.ERROR_INVALID_STATE,
                    message = getString(R.string.source_separation_playback_no_song),
                )
            }

            val playWhenReady = player.playWhenReady
            val shouldPlayAfterSwitch =
                activeWindowWait?.resumeWhenReady == true || resumeWhenReady || playWhenReady
            val isPartialCache = manifest.state == SourceSeparationCacheManifestState.Partial
            val useOriginalClock = manifest.canUseOriginalSourceSeparationClock(output)
            if (!useOriginalClock) {
                clearSourceSeparationPlaybackProcessing()
                traceSourceSeparationPlayback(
                    "check.newSession.unsupportedClock",
                    "id=$checkId sourceChannels=${manifest.identity.source.sourceChannelCount} " +
                            "sourceRate=${manifest.identity.source.sourceSampleRate} " +
                            "outputRate=${output.outputSampleRate}"
                )
                return sourceSeparationPlaybackUnavailable(
                    showMessage = showUnavailableMessage,
                    resultCode = SessionError.ERROR_INVALID_STATE,
                    message = getString(R.string.source_separation_playback_unavailable),
                )
            }
            val channelCount = output.stems.map { it.channelCount }.distinct().singleOrNull()
            if (channelCount != SOURCE_SEPARATION_STEM_CHANNEL_COUNT) {
                return sourceSeparationPlaybackUnavailable(
                    showMessage = showUnavailableMessage,
                    resultCode = SessionError.ERROR_INVALID_STATE,
                    message = getString(R.string.source_separation_playback_unavailable),
                )
            }
            val preparedInputs = withContext(IO) {
                sourceSeparationMixProcessor.prepareInputs(
                    stemFiles = stemFiles,
                    stemIds = stemIds,
                    stemSampleRate = output.outputSampleRate,
                    stemChannelCount = channelCount,
                )
            }
            val blendEndpointStemIds = manifest.mdxBlendEndpointStemIds()
            val stemGains = resolveSourceSeparationSessionStemGains(
                runtimeSong = runtimeSong,
                stemIds = stemIds,
                blendEndpointStemIds = blendEndpointStemIds,
            )
            val session = SourceSeparationPlaybackSession(
                songId = song.id,
                sessionId = checkId,
                selectionGeneration = selection.generation,
                selection = selection,
                cacheKey = runtimeSong.cacheKey,
                stemFiles = stemFiles,
                stemIds = stemIds,
                stemCanonicalLabels = output.stems.map { stem -> stem.canonicalLabel },
                stemGains = stemGains,
                blendEndpointStemIds = blendEndpointStemIds,
                inputMode = InputMode.OriginalSource,
                stemSampleRate = output.outputSampleRate,
                stemChannelCount = channelCount,
                preparedInputs = preparedInputs,
                requiresReadinessGate = isPartialCache,
                runtimeSong = runtimeSong,
                modelAwareCachePlayback = playback,
            )
            traceSourceSeparationPlayback(
                "check.newSession.applyStem",
                "id=$checkId index=$index position=$positionMs resumeWhenReady=$resumeWhenReady " +
                        "previousPlayWhenReady=$playWhenReady manifestState=${manifest.state} " +
                        "clock=original"
            )
            setSourceSeparationPlaybackExpectProcessing(false)
            if (!isSourceSeparationPlaybackCheckCurrent(
                    checkId = checkId,
                    contextGeneration = contextGeneration,
                    mediaItem = mediaItem,
                    mediaItemIndex = mediaItemIndex,
                    stage = "before apply new session",
                )
            ) {
                return sourceSeparationPlaybackResult(SessionResult.RESULT_SUCCESS)
            }

            val resumeAfterSwitch = pauseSourceSeparationOutputForSwitch(
                reason = "newSession",
                waitForMixedOutput = true,
            ) ||
                    resumeWhenReady
            val switchPositionMs = player.currentPosition.coerceAtLeast(0)
            clearSourceSeparationPlayback(restoreOriginalItem = false, broadcast = false)
            setSourceSeparationPlaybackProcessing(null)
            sourceSeparationPlaybackResumeWhenReady = false
            setSourceSeparationPlaybackExpectProcessing(false)
            sourceSeparationPlaybackSession = session
            enableSourceSeparationMixProcessor(session, switchPositionMs)
            updateSourceSeparationPlaybackReadinessMonitor(session)
            requestSourceSeparationOutputFlush("newSession")
            traceSourceSeparationPlayback(
                "check.newSession.afterPrepare",
                "id=$checkId position=$switchPositionMs shouldPlayAfterSwitch=$shouldPlayAfterSwitch " +
                        "resumeAfterSwitch=$resumeAfterSwitch"
            )
            resumeSourceSeparationOutputAfterSwitch("newSession", shouldPlayAfterSwitch || resumeAfterSwitch)

            broadcastSourceSeparationPlaybackChanged()
            sourceSeparationPlaybackGateJob?.cancel()
            sourceSeparationPlaybackGateJob = null
            updateSourceSeparationProcessingLease("newSession.ready")
            traceSourceSeparationPlayback("check.end", "id=$checkId result=success newSession")
            adopted = true
            return sourceSeparationPlaybackResult(SessionResult.RESULT_SUCCESS)
        } catch (error: Throwable) {
            if (sourceSeparationPlaybackSession?.modelAwareCachePlayback === playback) {
                clearSourceSeparationPlayback(
                    restoreOriginalItem = false,
                    broadcast = false,
                )
            }
            if (error is kotlinx.coroutines.CancellationException) throw error
            traceSourceSeparationPlayback(
                "check.newSession.failed",
                "id=$checkId cache=${playback.manifest.cacheKey.take(12)} " +
                        "error=${error.message ?: error::class.java.name}",
            )
            return sourceSeparationPlaybackUnavailable(
                showMessage = showUnavailableMessage,
                resultCode = SessionError.ERROR_UNKNOWN,
                message = getString(R.string.source_separation_playback_unavailable),
            )
        } finally {
            if (!adopted) {
                playback.close()
            }
        }
    }

    private suspend fun checkActiveSourceSeparationRuntimeSession(
        checkId: Long,
        contextGeneration: Long,
        mediaItem: MediaItem,
        mediaItemIndex: Int,
        song: Song,
        session: SourceSeparationPlaybackSession,
        preferCompletedCache: Boolean,
        resumeWhenReady: Boolean,
        showUnavailableMessage: Boolean,
        readyWindowCount: Int,
        gateOnUnreadyWindow: Boolean,
    ): SessionResult {
        val runtimeSong = session.runtimeSong
        val activeWindowWait = alignSourceSeparationPlaybackWindowWait(
            cacheKey = runtimeSong.cacheKey,
            fallbackPositionMs = player.currentPosition.coerceAtLeast(0),
        )
        val windowWaitEpoch = activeWindowWait?.epoch
        val positionMs = activeWindowWait?.anchorPositionMs
            ?: player.currentPosition.coerceAtLeast(0)
        val effectiveReadyWindowCount = activeWindowWait?.requiredReadyWindowCount
            ?: readyWindowCount.coerceAtLeast(1)
        val recoveryReadyWindowCount = activeWindowWait?.requiredReadyWindowCount
            ?: sourceSeparationPlaybackReadyWindowCount.coerceAtLeast(1)
        val effectiveResumeWhenReady = activeWindowWait?.resumeWhenReady == true ||
                resumeWhenReady
        val status = withContext(IO) {
            runCatching {
                sourceSeparationRuntime.playableStatus(
                    song = runtimeSong,
                    playbackPositionMs = positionMs,
                    readyWindowCount = effectiveReadyWindowCount,
                )
            }.getOrDefault(SourceSeparationModelAwarePlayableStatus.Unavailable)
        }
        if (!isSourceSeparationPlaybackCheckCurrent(
                checkId = checkId,
                contextGeneration = contextGeneration,
                mediaItem = mediaItem,
                mediaItemIndex = mediaItemIndex,
                stage = "after active status",
            )
        ) {
            status.closePlayback()
            return sourceSeparationPlaybackResult(SessionResult.RESULT_SUCCESS)
        }
        if (!sourceSeparationPlaybackWindowWaitTracker.isCurrent(windowWaitEpoch)) {
            status.closePlayback()
            traceSourceSeparationPlayback(
                "check.staleWindowWait",
                "id=$checkId stage=afterActiveStatus expectedEpoch=$windowWaitEpoch " +
                        "currentEpoch=${sourceSeparationPlaybackWindowWaitTracker.current?.epoch}",
            )
            return sourceSeparationPlaybackResult(SessionResult.RESULT_SUCCESS)
        }
        traceSourceSeparationPlayback(
            "check.activeSession.status",
            "id=$checkId songId=${song.id} cache=${runtimeSong.cacheKey.take(12)} " +
                    "position=$positionMs readyWindows=$effectiveReadyWindowCount " +
                    "waitEpoch=$windowWaitEpoch status=${status.traceName()}",
        )
        if (!sourceSeparationPlaybackRequested) {
            status.closePlayback()
            clearSourceSeparationPlaybackProcessing()
            traceSourceSeparationPlayback(
                "check.skip",
                "id=$checkId requested=false after active status",
            )
            return sourceSeparationPlaybackResult(SessionResult.RESULT_SUCCESS)
        }
        return when (status) {
            is SourceSeparationModelAwarePlayableStatus.Ready -> {
                val playback = status.playback
                var adopted = false
                try {
                    if (!isSourceSeparationPlaybackCheckCurrent(
                            checkId = checkId,
                            contextGeneration = contextGeneration,
                            mediaItem = mediaItem,
                            mediaItemIndex = mediaItemIndex,
                            stage = "before active ready apply",
                        )
                    ) {
                        return sourceSeparationPlaybackResult(SessionResult.RESULT_SUCCESS)
                    }
                    setSourceSeparationPlaybackExpectProcessing(false)
                    if (preferCompletedCache &&
                        shouldUpgradeActiveSourceSeparationSession(session, playback)
                    ) {
                        val result = switchActiveSourceSeparationSessionToCompletedCache(
                            checkId = checkId,
                            song = song,
                            activeSession = session,
                            playback = playback,
                            positionMs = positionMs,
                            resumeWhenReady = effectiveResumeWhenReady,
                            showUnavailableMessage = showUnavailableMessage,
                        )
                        adopted = sourceSeparationPlaybackSession
                            ?.modelAwareCachePlayback === playback
                        return result
                    }
                    val completedWindowWait =
                        sourceSeparationPlaybackWindowWaitTracker.clear(windowWaitEpoch)
                    val wasProcessing = completedWindowWait != null ||
                            sourceSeparationPlaybackIsProcessing
                    val resumeAfterProcessing =
                        completedWindowWait?.resumeWhenReady
                            ?: sourceSeparationPlaybackResumeWhenReady
                    setSourceSeparationPlaybackProcessing(null)
                    sourceSeparationPlaybackResumeWhenReady = false
                    val updatedSession = session.copy(
                        requiresReadinessGate = playback.manifest.state ==
                            SourceSeparationCacheManifestState.Partial,
                    )
                    sourceSeparationPlaybackSession = updatedSession
                    updateSourceSeparationPlaybackReadinessMonitor(updatedSession)
                    if (wasProcessing) {
                        realignSourceSeparationPlaybackAfterProcessing(
                            session = updatedSession,
                            resumeWhenReady = resumeAfterProcessing,
                        )
                    } else if (resumeAfterProcessing) {
                        setSourceSeparationPlayWhenReady(true)
                    }
                    broadcastSourceSeparationPlaybackChanged()
                    sourceSeparationPlaybackGateJob?.cancel()
                    sourceSeparationPlaybackGateJob = null
                    updateSourceSeparationProcessingLease("activeSession.ready")
                    sourceSeparationPlaybackResult(SessionResult.RESULT_SUCCESS)
                } finally {
                    if (!adopted) playback.close()
                }
            }

            SourceSeparationModelAwarePlayableStatus.Processing -> {
                if (!isSourceSeparationPlaybackCheckCurrent(
                        checkId = checkId,
                        contextGeneration = contextGeneration,
                        mediaItem = mediaItem,
                        mediaItemIndex = mediaItemIndex,
                        stage = "before active processing wait",
                    )
                ) {
                    return sourceSeparationPlaybackResult(SessionResult.RESULT_SUCCESS)
                }
                if (!gateOnUnreadyWindow) {
                    traceSourceSeparationPlayback(
                        "check.activeSession.processing.continue",
                        "id=$checkId",
                    )
                    return sourceSeparationPlaybackResult(SessionResult.RESULT_SUCCESS)
                }
                traceSourceSeparationPlayback("check.activeSession.processing", "id=$checkId")
                waitForSourceSeparationPlayback(
                    source = "activeSession",
                    processingCacheKey = runtimeSong.cacheKey,
                    waitingPositionMs = positionMs,
                    requiredReadyWindowCount = recoveryReadyWindowCount,
                    restoreOriginalItem = false,
                    resumeWhenReady = effectiveResumeWhenReady,
                    showMessage = showUnavailableMessage,
                )
            }

            SourceSeparationModelAwarePlayableStatus.Unavailable -> {
                setSourceSeparationPlaybackExpectProcessing(false)
                traceSourceSeparationPlayback("check.activeSession.unavailable", "id=$checkId")
                clearSourceSeparationPlayback(restoreOriginalItem = true)
                clearSourceSeparationPlaybackProcessing()
                sourceSeparationPlaybackUnavailable(
                    showMessage = showUnavailableMessage,
                    resultCode = SessionError.ERROR_INVALID_STATE,
                    message = getString(R.string.source_separation_playback_cache_not_found),
                )
            }
        }
    }

    private fun shouldUpgradeActiveSourceSeparationSession(
        session: SourceSeparationPlaybackSession,
        playback: SourceSeparationModelAwareCachePlayback,
    ): Boolean {
        if (playback.manifest.state != SourceSeparationCacheManifestState.Completed) return false
        return session.requiresReadinessGate ||
                session.inputMode != InputMode.OriginalSource ||
                session.stemFiles != playback.stemFiles
    }

    private fun shouldCheckForDeferredCompletedPlaybackUpgrade(
        session: SourceSeparationPlaybackSession,
    ): Boolean {
        return session.requiresReadinessGate ||
                session.inputMode != InputMode.OriginalSource ||
                session.stemFiles.any { file ->
                    !file.extension.equals("flac", ignoreCase = true)
                }
    }

    private suspend fun switchActiveSourceSeparationSessionToCompletedCache(
        checkId: Long,
        song: Song,
        activeSession: SourceSeparationPlaybackSession,
        playback: SourceSeparationModelAwareCachePlayback,
        positionMs: Long,
        resumeWhenReady: Boolean,
        showUnavailableMessage: Boolean,
    ): SessionResult {
        val manifest = playback.manifest
        val output = manifest.output
            ?: return sourceSeparationPlaybackUnavailable(
                showMessage = showUnavailableMessage,
                resultCode = SessionError.ERROR_INVALID_STATE,
                message = getString(R.string.source_separation_playback_cache_not_found),
            )
        val stemFiles = playback.stemFiles
        val stemIds = playback.stemIds
        traceSourceSeparationPlayback(
            "check.activeSession.upgradeCompleted.start",
            "id=$checkId songId=${song.id} active=${activeSession.traceSummary()} " +
                    "position=$positionMs outputFormat=${stemFiles.first().extension} " +
                    "stemExt=${stemFiles.joinToString { file -> file.extension }}"
        )
        val availableFiles = withContext(IO) {
            stemFiles.map(File::isFile)
        }
        if (availableFiles.any { available -> !available }) {
            traceSourceSeparationPlayback(
                "check.activeSession.completedFilesMissing",
                "id=$checkId available=$availableFiles stems=${stemIds.joinToString()}"
            )
            return sourceSeparationPlaybackUnavailable(
                showMessage = showUnavailableMessage,
                resultCode = SessionError.ERROR_INVALID_STATE,
                message = getString(R.string.source_separation_playback_stem_files_missing),
            )
        }

        val index = player.currentMediaItemIndex
        if (index == C.INDEX_UNSET) {
            clearSourceSeparationPlaybackProcessing()
            traceSourceSeparationPlayback("check.activeSession.completedInvalidIndex", "id=$checkId")
            return sourceSeparationPlaybackUnavailable(
                showMessage = showUnavailableMessage,
                resultCode = SessionError.ERROR_INVALID_STATE,
                message = getString(R.string.source_separation_playback_no_song),
            )
        }

        val playWhenReady = player.playWhenReady
        val shouldPlayAfterSwitch = resumeWhenReady || playWhenReady
        val useOriginalClock = manifest.canUseOriginalSourceSeparationClock(output)
        if (!useOriginalClock) {
            traceSourceSeparationPlayback(
                "check.activeSession.unsupportedClock",
                "id=$checkId sourceChannels=${manifest.identity.source.sourceChannelCount} " +
                        "sourceRate=${manifest.identity.source.sourceSampleRate} " +
                        "outputRate=${output.outputSampleRate}"
            )
            return sourceSeparationPlaybackUnavailable(
                showMessage = showUnavailableMessage,
                resultCode = SessionError.ERROR_INVALID_STATE,
                message = getString(R.string.source_separation_playback_unavailable),
            )
        }
        val channelCount = output.stems.map { it.channelCount }.distinct().singleOrNull()
        if (channelCount != SOURCE_SEPARATION_STEM_CHANNEL_COUNT) {
            return sourceSeparationPlaybackUnavailable(
                showMessage = showUnavailableMessage,
                resultCode = SessionError.ERROR_INVALID_STATE,
                message = getString(R.string.source_separation_playback_unavailable),
            )
        }
        val preparedInputs = withContext(IO) {
            sourceSeparationMixProcessor.prepareInputs(
                stemFiles = stemFiles,
                stemIds = stemIds,
                stemSampleRate = output.outputSampleRate,
                stemChannelCount = channelCount,
            )
        }
        val blendEndpointStemIds = manifest.mdxBlendEndpointStemIds()
        val stemGains = resolveSourceSeparationSessionStemGains(
            runtimeSong = activeSession.runtimeSong,
            stemIds = stemIds,
            blendEndpointStemIds = blendEndpointStemIds,
            fallback = activeSession.stemGains,
        )
        val session = SourceSeparationPlaybackSession(
            songId = song.id,
            sessionId = checkId,
            selectionGeneration = activeSession.selectionGeneration,
            selection = activeSession.selection,
            cacheKey = manifest.cacheKey,
            stemFiles = stemFiles,
            stemIds = stemIds,
            stemCanonicalLabels = output.stems.map { stem -> stem.canonicalLabel },
            stemGains = stemGains,
            blendEndpointStemIds = blendEndpointStemIds,
            inputMode = InputMode.OriginalSource,
            stemSampleRate = output.outputSampleRate,
            stemChannelCount = channelCount,
            preparedInputs = preparedInputs,
            requiresReadinessGate = false,
            runtimeSong = activeSession.runtimeSong,
            modelAwareCachePlayback = playback,
        )
        traceSourceSeparationPlayback(
            "check.activeSession.upgradeCompleted",
            "id=$checkId index=$index position=$positionMs shouldPlayAfterSwitch=$shouldPlayAfterSwitch " +
                    "clock=original"
        )
        var adopted = false
        try {
            val resumeAfterSwitch = pauseSourceSeparationOutputForSwitch(
                reason = "completedCacheUpgrade",
                waitForMixedOutput = true,
            ) || resumeWhenReady
            val switchPositionMs = player.currentPosition.coerceAtLeast(0)
            setSourceSeparationPlaybackProcessing(null)
            sourceSeparationPlaybackResumeWhenReady = false
            sourceSeparationPlaybackSession = session
            enableSourceSeparationMixProcessor(session, switchPositionMs)
            updateSourceSeparationPlaybackReadinessMonitor(session)
            requestSourceSeparationOutputFlush("completedCacheUpgrade")
            resumeSourceSeparationOutputAfterSwitch(
                reason = "completedCacheUpgrade",
                resume = shouldPlayAfterSwitch || resumeAfterSwitch,
            )
            activeSession.closeModelAwareResources()
            broadcastSourceSeparationPlaybackChanged()
            sourceSeparationPlaybackGateJob?.cancel()
            sourceSeparationPlaybackGateJob = null
            updateSourceSeparationProcessingLease("completedCacheUpgrade.ready")
            traceSourceSeparationPlayback(
                "check.end",
                "id=$checkId result=success completedCacheUpgrade",
            )
            adopted = true
            return sourceSeparationPlaybackResult(SessionResult.RESULT_SUCCESS)
        } catch (error: Throwable) {
            if (sourceSeparationPlaybackSession === session) {
                clearSourceSeparationPlayback(
                    restoreOriginalItem = false,
                    broadcast = false,
                )
                activeSession.closeModelAwareResources()
            }
            if (error is kotlinx.coroutines.CancellationException) throw error
            traceSourceSeparationPlayback(
                "check.activeSession.upgradeCompleted.failed",
                "id=$checkId cache=${manifest.cacheKey.take(12)} " +
                        "error=${error.message ?: error::class.java.name}",
            )
            return sourceSeparationPlaybackUnavailable(
                showMessage = showUnavailableMessage,
                resultCode = SessionError.ERROR_UNKNOWN,
                message = getString(R.string.source_separation_playback_unavailable),
            )
        } finally {
        }
    }

    private suspend fun setSourceSeparationBlend(blend: Float, persist: Boolean): SessionResult {
        val previousBlend = sourceSeparationMixProcessor.blend
        applySourceSeparationBlend(blend)
        if (SourceSeparationBlendDemand.isCentered(blend)) {
            setSourceSeparationPlaybackExpectProcessing(false)
            cancelSourceSeparationPlaybackReadinessMonitor("centerBlend")
            if (sourceSeparationPlaybackIsProcessing &&
                sourceSeparationPlaybackProcessingCacheKey == null
            ) {
                clearSourceSeparationPlaybackProcessing(broadcast = false)
            }
        } else {
            updateSourceSeparationPlaybackReadinessMonitor(
                sourceSeparationPlaybackSession,
            )
        }
        val modelAwareIdentity = sourceSeparationPlaybackSession
            ?.modelAwareCachePlayback
            ?.manifest
            ?.identity
        if (persist && modelAwareIdentity != null) {
            serviceScope.launch(IO) {
                sourceSeparationRuntime.writeBlend(
                    identity = modelAwareIdentity,
                    blend = blend,
                )
            }
        }
        if (sourceSeparationPlaybackSession != null &&
            !player.playWhenReady &&
            !player.isPlaying &&
            player.playbackState != Player.STATE_BUFFERING
        ) {
            sourceSeparationPausedBlendFlushPending = true
        }
        broadcastSourceSeparationPlaybackChanged()
        if (SourceSeparationBlendDemand.requiresSeparatedOutput(previousBlend) !=
            SourceSeparationBlendDemand.requiresSeparatedOutput(blend)
        ) {
            maybePreStartNextSourceSeparation("blendDemandChanged")
        }
        if (persist &&
            sourceSeparationPlaybackRequested &&
            SourceSeparationBlendDemand.requiresSeparatedOutput(blend)
        ) {
            val expectProcessing = preferences.getBoolean(
                SOURCE_SEPARATION_AUTO_START,
                DEFAULT_SOURCE_SEPARATION_AUTO_START,
            )
            setSourceSeparationPlaybackExpectProcessing(expectProcessing)
            return ensureSourceSeparationPlaybackReady(
                showUnavailableMessage = false,
                resumeWhenReady = false,
                allowNewSession = true,
                expectProcessing = expectProcessing,
                gateOnUnreadyWindow = shouldGateCurrentSourceSeparationWindow(),
                requiredReadyWindowCount = 1,
            )
        }
        return sourceSeparationPlaybackResult(SessionResult.RESULT_SUCCESS)
    }

    private suspend fun setSourceSeparationStemGains(
        cacheKey: String,
        stemIds: List<String>,
        gains: List<Float>,
        commit: Boolean,
    ): SessionResult {
        val normalizedByStemId = runCatching {
            require(cacheKey.isNotBlank()) { "The stem gain cache key is empty." }
            SourceSeparationStemGainPolicy.orderedMap(stemIds, gains)
        }.getOrElse { error ->
            traceSourceSeparationPlayback(
                "playback.setStemGains.rejected",
                error.message.orEmpty(),
            )
            return sourceSeparationPlaybackResult(SessionError.ERROR_BAD_VALUE)
        }
        val normalizedGains = stemIds.map(normalizedByStemId::getValue)
        val demandBlend = SourceSeparationStemGainPolicy.demandBlend(normalizedGains)
        val previousBlend = sourceSeparationMixProcessor.blend
        val persistPerSong = commit && preferences.getBoolean(
            SOURCE_SEPARATION_REMEMBER_PER_SONG,
            true,
        )
        val request = SourceSeparationRequestedStemMix(
            cacheKey = cacheKey,
            stemIds = stemIds,
            gains = normalizedGains,
            persist = persistPerSong,
        )
        val activeSession = sourceSeparationPlaybackSession
        val targetsActiveSession = activeSession?.cacheKey == cacheKey
        if (activeSession != null && targetsActiveSession &&
            (activeSession.blendEndpointStemIds != null || activeSession.stemIds != stemIds)
        ) {
            traceSourceSeparationPlayback(
                "playback.setStemGains.rejected",
                "The active session does not expose the requested independent stem set.",
            )
            return sourceSeparationPlaybackResult(SessionError.ERROR_BAD_VALUE)
        }
        sourceSeparationRequestedStemMix = request

        val appliesToActiveSession = activeSession != null && targetsActiveSession
        val affectsCurrentPlayback = activeSession == null || targetsActiveSession
        if (commit && affectsCurrentPlayback) {
            sourceSeparationMixProcessor.setBlend(demandBlend)
        }
        if (appliesToActiveSession) {
            activeSession.stemGains = normalizedGains
            sourceSeparationMixProcessor.setStemGains(normalizedGains)
        }
        if (!commit) {
            return SessionResult(SessionResult.RESULT_SUCCESS)
        }

        if (affectsCurrentPlayback) {
            if (SourceSeparationBlendDemand.isCentered(demandBlend)) {
                setSourceSeparationPlaybackExpectProcessing(false)
                cancelSourceSeparationPlaybackReadinessMonitor("neutralStemGains")
                if (sourceSeparationPlaybackIsProcessing &&
                    sourceSeparationPlaybackProcessingCacheKey == null
                ) {
                    clearSourceSeparationPlaybackProcessing(broadcast = false)
                }
            } else {
                updateSourceSeparationPlaybackReadinessMonitor(activeSession)
            }
        }

        if (persistPerSong && appliesToActiveSession) {
            val identity = activeSession.modelAwareCachePlayback.manifest.identity
            serviceScope.launch(IO) {
                sourceSeparationRuntime.writeStemGains(identity, normalizedByStemId)
            }
        }
        if (affectsCurrentPlayback && activeSession != null &&
            !player.playWhenReady &&
            !player.isPlaying &&
            player.playbackState != Player.STATE_BUFFERING
        ) {
            sourceSeparationPausedBlendFlushPending = true
        }
        broadcastSourceSeparationPlaybackChanged()
        if (affectsCurrentPlayback &&
            SourceSeparationBlendDemand.requiresSeparatedOutput(previousBlend) !=
            SourceSeparationBlendDemand.requiresSeparatedOutput(demandBlend)
        ) {
            maybePreStartNextSourceSeparation("stemGainDemandChanged")
        }
        if (commit &&
            sourceSeparationPlaybackRequested &&
            SourceSeparationStemGainPolicy.requiresSeparatedOutput(normalizedGains)
        ) {
            val expectProcessing = preferences.getBoolean(
                SOURCE_SEPARATION_AUTO_START,
                DEFAULT_SOURCE_SEPARATION_AUTO_START,
            )
            setSourceSeparationPlaybackExpectProcessing(expectProcessing)
            return ensureSourceSeparationPlaybackReady(
                showUnavailableMessage = false,
                resumeWhenReady = false,
                allowNewSession = true,
                expectProcessing = expectProcessing,
                gateOnUnreadyWindow = shouldGateCurrentSourceSeparationWindow(),
                requiredReadyWindowCount = 1,
            )
        }
        return sourceSeparationPlaybackResult(SessionResult.RESULT_SUCCESS)
    }

    private fun applySourceSeparationBlend(blend: Float) {
        val normalizedBlend = blend.coerceIn(0f, 1f)
        sourceSeparationMixProcessor.setBlend(normalizedBlend)
    }

    private suspend fun resolveSourceSeparationSessionStemGains(
        runtimeSong: SourceSeparationRuntimeSong,
        stemIds: List<String>,
        blendEndpointStemIds: List<String>?,
        fallback: List<Float>? = null,
    ): List<Float> {
        if (blendEndpointStemIds != null) return emptyList()
        val requestedMix = sourceSeparationRequestedStemMix
            ?.takeIf { request -> request.cacheKey == runtimeSong.cacheKey }
        val requested = requestedMix?.orderedGains(stemIds)
        if (requested != null) {
            if (requestedMix.persist) {
                val saved = withContext(IO) {
                    sourceSeparationRuntime.writeStemGains(
                        runtimeSong,
                        SourceSeparationStemGainPolicy.orderedMap(stemIds, requested),
                    )
                }
                if (saved && sourceSeparationRequestedStemMix === requestedMix) {
                    sourceSeparationRequestedStemMix = requestedMix.copy(persist = false)
                }
            }
            return requested
        }
        fallback?.takeIf { gains -> gains.size == stemIds.size }?.let {
            return SourceSeparationStemGainPolicy.normalize(it)
        }
        if (!preferences.getBoolean(SOURCE_SEPARATION_REMEMBER_PER_SONG, true)) {
            val global = sourceSeparationMixSettings.readGlobalStemGains(
                model = SourceSeparationMixModelKey.multiStem(runtimeSong.modelId),
                stemIds = stemIds,
            )
            if (global != null) {
                return SourceSeparationStemGainPolicy.orderedGains(stemIds, global)
                    ?: List(stemIds.size) {
                        SourceSeparationStemGainPolicy.NEUTRAL_GAIN
                    }
            }
            return List(stemIds.size) { SourceSeparationStemGainPolicy.NEUTRAL_GAIN }
        }
        val persisted = withContext(IO) {
            sourceSeparationRuntime.readStemGains(runtimeSong)
        }
        return persisted?.let { gainsByStemId ->
            SourceSeparationStemGainPolicy.orderedGains(stemIds, gainsByStemId)
        } ?: List(stemIds.size) { SourceSeparationStemGainPolicy.NEUTRAL_GAIN }
    }

    private fun observeSourceSeparationForegroundWorker() {
        serviceScope.launch {
            sourceSeparationForegroundWorkerCoordinator.workerStateFlow.collect { state ->
                if (state is SourceSeparationUiState.Completed) {
                    maybePreStartNextSourceSeparation("workerCompleted:${state.songId}")
                }
                probeSourceSeparationPlaybackFromWorker(state)
            }
        }
    }

    private suspend fun probeSourceSeparationPlaybackFromWorker(
        state: SourceSeparationUiState,
    ) {
        val songId = when (state) {
            is SourceSeparationUiState.Running -> state.songId
            is SourceSeparationUiState.Completed -> state.songId
            else -> return
        }
        if (!sourceSeparationPlaybackRequested ||
            sourceSeparationPlaybackSession != null ||
            sourceSeparationPlaybackIsProcessing ||
            player.currentMediaItem?.mediaId != songId.toString() ||
            !SourceSeparationBlendDemand.requiresSeparatedOutput(
                sourceSeparationMixProcessor.blend,
            )
        ) {
            return
        }
        val expectProcessing = state is SourceSeparationUiState.Running
        if (expectProcessing) {
            setSourceSeparationPlaybackExpectProcessing(true)
        }
        ensureSourceSeparationPlaybackReady(
            showUnavailableMessage = false,
            resumeWhenReady = false,
            allowNewSession = true,
            expectProcessing = expectProcessing,
            gateOnUnreadyWindow = shouldGateCurrentSourceSeparationWindow(),
            requiredReadyWindowCount = 1,
        )
    }

    private fun onSourceSeparationSeekRequested(
        mediaItemIndex: Int,
        positionMs: Long,
    ) {
        val session = sourceSeparationPlaybackSession ?: return
        if (mediaItemIndex != player.currentMediaItemIndex ||
            player.currentMediaItem?.mediaId != session.songId.toString()
        ) {
            return
        }
        traceSourceSeparationPlayback(
            "player.seekRequested",
            "index=$mediaItemIndex position=$positionMs session=${session.traceSummary()}",
        )
        requestSourceSeparationDataPlaneSeek(session, positionMs, "seekRequest")
    }

    private fun requestSourceSeparationDataPlaneSeek(
        session: SourceSeparationPlaybackSession?,
        positionMs: Long,
        reason: String,
    ) {
        if (session == null || sourceSeparationPlaybackSession?.sessionId != session.sessionId) {
            return
        }
        sourceSeparationMixProcessor.seekTo(positionMs)
        updateSourceSeparationDataPlaneMonitor(session)
        gateSourceSeparationDataPlaneUntilReady(session, reason)
    }

    private fun observeSourceSeparationActiveSelection() {
        sourceSeparationActiveSelection = sourceSeparationExecutionSelection.current()
        serviceScope.launch {
            sourceSeparationExecutionSelection.selectionFlow.collect { selection ->
                val previous = sourceSeparationActiveSelection
                if (selection == previous) return@collect
                sourceSeparationActiveSelection = selection
                handleSourceSeparationModelSelectionChanged(
                    source = selection.family?.name ?: "none",
                    previousGeneration = previous?.generation,
                    generation = selection.generation,
                )
            }
        }
    }

    private suspend fun handleSourceSeparationModelSelectionChanged(
        source: String,
        previousGeneration: Long?,
        generation: Long,
    ) {
        sourceSeparationPlaybackContextGeneration++
        sourceSeparationRequestedStemMix = null
        sourceSeparationPlaybackGateJob?.cancel()
        sourceSeparationPlaybackGateJob = null
        cancelSourceSeparationPlaybackReadinessMonitor("${source}ModelChanged")
        if (sourceSeparationPlaybackSession != null) {
            clearSourceSeparationPlayback(
                restoreOriginalItem = true,
                broadcast = false,
            )
        } else {
            clearSourceSeparationPlaybackProcessing(broadcast = false)
        }
        traceSourceSeparationPlayback(
            "playback.activeModelChanged",
            "source=$source previousGeneration=$previousGeneration generation=$generation",
        )
        if (sourceSeparationPlaybackRequested) {
            val automaticDemand = preferences.getBoolean(
                SOURCE_SEPARATION_AUTO_START,
                DEFAULT_SOURCE_SEPARATION_AUTO_START,
            )
            val expectProcessing = automaticDemand &&
                    SourceSeparationBlendDemand.requiresSeparatedOutput(
                        sourceSeparationMixProcessor.blend,
                    )
            setSourceSeparationPlaybackExpectProcessing(expectProcessing)
            ensureSourceSeparationPlaybackReady(
                showUnavailableMessage = false,
                resumeWhenReady = false,
                allowNewSession = true,
                expectProcessing = expectProcessing,
                gateOnUnreadyWindow = shouldGateCurrentSourceSeparationWindow(),
                requiredReadyWindowCount = 1,
            )
        } else {
            broadcastSourceSeparationPlaybackChanged()
        }
    }

    private fun observeSourceSeparationProcessingOwnership() {
        serviceScope.launch {
            sourceSeparationProcessingOwnershipHandoff.stateFlow
                .map { it.activeOwner }
                .distinctUntilChanged()
                .collect { activeOwner ->
                    val owner = activeOwner?.owner
                    val released = sourceSeparationProcessingOwnershipHandoff.stateFlow.value
                        .lastReleasedOwner
                    traceSourceSeparationPlayback(
                        "lease.handoff",
                        "ownerCache=${owner?.cacheKey?.take(12)} run=${owner?.runId} " +
                            "generation=${owner?.processGeneration} " +
                            "acceptedAt=${activeOwner?.acceptedAtElapsedRealtimeNanos} " +
                            "releasedRun=${released?.owner?.runId} " +
                            "releasedAt=${released?.releasedAtElapsedRealtimeNanos} " +
                            "releaseReason=${released?.releaseReason} " +
                            "waitingCache=${sourceSeparationPlaybackProcessingCacheKey?.take(12)}",
                    )
                    updateSourceSeparationProcessingLease("remoteOwnershipChanged")
                }
        }
    }

    private fun maybePreStartNextSourceSeparation(reason: String) {
        if (!::player.isInitialized) return
        if (!preferences.getBoolean(SOURCE_SEPARATION_AUTO_START, DEFAULT_SOURCE_SEPARATION_AUTO_START) ||
            !sourceSeparationPlaybackRequested
        ) {
            sourceSeparationPreStartJob?.cancel()
            sourceSeparationPreStartJob = null
            return
        }

        val currentMediaItem = player.currentMediaItem ?: run {
            sourceSeparationPreStartJob?.cancel()
            sourceSeparationPreStartJob = null
            return
        }
        val nextMediaItemIndex = player.nextMediaItemIndex
        if (nextMediaItemIndex == C.INDEX_UNSET ||
            nextMediaItemIndex !in 0 until player.mediaItemCount
        ) {
            sourceSeparationPreStartJob?.cancel()
            sourceSeparationPreStartJob = null
            return
        }
        val nextMediaItem = player.getMediaItemAt(nextMediaItemIndex)
        val readyWindowCount = sourceSeparationPlaybackReadyWindowCount
        val autoSyncOnTransition = sourceSeparationPlaybackAutoSyncOnTransition
        val currentBlend = sourceSeparationMixProcessor.blend
        val currentMediaId = currentMediaItem.mediaId
        val nextMediaId = nextMediaItem.mediaId

        sourceSeparationPreStartJob?.cancel()
        sourceSeparationPreStartJob = serviceScope.launch {
            val nextSong = withContext(IO) {
                val currentSong = repository.songByMediaItem(currentMediaItem)
                val nextSong = repository.songByMediaItem(nextMediaItem)
                if (currentSong == Song.emptySong || nextSong == Song.emptySong ||
                    currentSong.id == nextSong.id
                ) {
                    return@withContext null
                }

                val currentCacheCompleted = runCatching {
                    val currentRuntimeSong = when (
                        val resolution = sourceSeparationRuntime.resolveForPlayback(currentSong)
                    ) {
                        is SourceSeparationRuntimeSongResolution.Ready -> resolution.song
                        is SourceSeparationRuntimeSongResolution.Unavailable -> return@runCatching false
                    }
                    sourceSeparationRuntime.cacheStatus(currentRuntimeSong) is
                            SourceSeparationModelAwareCacheStatus.Completed
                }.getOrDefault(false)
                if (!currentCacheCompleted) return@withContext null

                val nextNeedsSeparatedOutput = if (autoSyncOnTransition) {
                    SourceSeparationBlendDemand.requiresSeparatedOutput(currentBlend)
                } else {
                    sourceSeparationForegroundWorkerCoordinator
                        .recordedBlendForSong(nextSong)
                        ?.let(SourceSeparationBlendDemand::requiresSeparatedOutput)
                        ?: false
                }
                nextSong.takeIf { nextNeedsSeparatedOutput }
            } ?: return@launch

            val latestNextMediaItemIndex = player.nextMediaItemIndex
            if (!sourceSeparationPlaybackRequested ||
                !preferences.getBoolean(
                    SOURCE_SEPARATION_AUTO_START,
                    DEFAULT_SOURCE_SEPARATION_AUTO_START,
                ) ||
                player.currentMediaItem?.mediaId != currentMediaId ||
                latestNextMediaItemIndex != nextMediaItemIndex ||
                latestNextMediaItemIndex == C.INDEX_UNSET ||
                latestNextMediaItemIndex !in 0 until player.mediaItemCount ||
                player.getMediaItemAt(latestNextMediaItemIndex).mediaId != nextMediaId ||
                sourceSeparationForegroundWorkerCoordinator.runningSongId() == nextSong.id ||
                sourceSeparationForegroundWorkerCoordinator.pendingSongId() == nextSong.id
            ) {
                return@launch
            }

            sourceSeparationForegroundWorkerCoordinator.preStartSong(
                song = nextSong,
                readyWindowCount = readyWindowCount,
            )
        }
    }

    private fun realignSourceSeparationPlaybackAfterProcessing(
        session: SourceSeparationPlaybackSession,
        resumeWhenReady: Boolean,
    ) {
        val positionMs = player.currentPosition.coerceAtLeast(0)
        traceSourceSeparationPlayback(
            "playback.realignAfterProcessing",
            "songId=${session.songId} position=$positionMs resumeWhenReady=$resumeWhenReady"
        )
        val resumeAfterSwitch = pauseSourceSeparationOutputForSwitch(
            reason = "realignAfterProcessing",
            waitForMixedOutput = true,
        )
        enableSourceSeparationMixProcessor(session, positionMs)
        requestSourceSeparationOutputFlush("realignAfterProcessing")
        resumeSourceSeparationOutputAfterSwitch(
            reason = "realignAfterProcessing",
            resume = resumeAfterSwitch || resumeWhenReady,
        )
    }

    private fun enableSourceSeparationMixProcessor(
        session: SourceSeparationPlaybackSession,
        positionMs: Long,
    ) {
        if (session.blendEndpointStemIds == null) {
            sourceSeparationMixProcessor.setBlend(
                SourceSeparationStemGainPolicy.demandBlend(session.stemGains),
            )
        }
        traceSourceSeparationPlayback(
            "processor.enable.request",
            "songId=${session.songId} position=$positionMs mode=${session.inputMode} " +
                    "stemRate=${session.stemSampleRate} gate=${session.requiresReadinessGate}"
        )
        sourceSeparationMixProcessor.enable(
            stemFiles = session.stemFiles,
            stemIds = session.stemIds,
            initialGains = session.stemGains,
            blendEndpointStemIds = session.blendEndpointStemIds,
            positionMs = positionMs,
            inputMode = session.inputMode,
            stemSampleRate = session.stemSampleRate,
            stemChannelCount = session.stemChannelCount,
            mixedOutputReadyPrerollMs = sourceSeparationMixedOutputPrerollMs,
            preparedInputs = session.preparedInputs,
        )
        updateSourceSeparationDataPlaneMonitor(session)
        traceSourceSeparationPlayback("processor.enable.done", "songId=${session.songId}")
    }

    private val sourceSeparationMixedOutputPrerollMs: Long
        get() = preferences.getLong(
            SOURCE_SEPARATION_MIXED_OUTPUT_PREROLL_MS,
            DEFAULT_SOURCE_SEPARATION_MIXED_OUTPUT_PREROLL_MS,
        ).coerceIn(0L, MAX_SOURCE_SEPARATION_MIXED_OUTPUT_PREROLL_MS)

    private val sourceSeparationPlaybackReadyWindowCount: Int
        get() = preferences.getInt(
            SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT,
            DEFAULT_SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT,
        ).coerceIn(
            MIN_SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT,
            MAX_SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT,
        )

    private fun clearSourceSeparationPlayback(
        restoreOriginalItem: Boolean,
        broadcast: Boolean = true,
    ) {
        traceSourceSeparationPlayback(
            "playback.clear",
            "restoreOriginalItem=$restoreOriginalItem broadcast=$broadcast"
        )
        setSourceSeparationPlaybackExpectProcessing(false)
        val session = sourceSeparationPlaybackSession
        val resumeAfterSwitch = player.playWhenReady
        sourceSeparationPlaybackSession = null
        cancelSourceSeparationPlaybackReadinessMonitor("clear")
        cancelSourceSeparationDataPlaneMonitor("clear")
        clearSourceSeparationOutputFlushBarrier("playback.clear")
        sourceSeparationPendingMixedOutputGeneration = null
        sourceSeparationMixProcessor.disable()
        setSourceSeparationPlaybackProcessing(null)
        sourceSeparationPausedBlendFlushPending = false
        session?.closeModelAwareResources()
        cleanupCompletedSourceSeparationTemporaryDirs()
        updateSourceSeparationProcessingLease("clear")

        if (restoreOriginalItem && session != null) {
            resumeSourceSeparationOutputAfterSwitch("clear.originalClock", resumeAfterSwitch)
        }

        if (broadcast) {
            broadcastSourceSeparationPlaybackChanged()
        }
    }

    private fun clearSourceSeparationPlaybackProcessing(
        broadcast: Boolean = true,
        preserveExpectedProcessing: Boolean = false,
    ) {
        val windowWait = sourceSeparationPlaybackWindowWaitTracker.current
        val changed = sourceSeparationPlaybackIsProcessing ||
                sourceSeparationPlaybackResumeWhenReady ||
                windowWait != null
        val shouldResume = (windowWait?.resumeWhenReady == true ||
                sourceSeparationPlaybackResumeWhenReady) &&
                player.playWhenReady.not()
        traceSourceSeparationPlayback(
            "playback.clearProcessing",
            "broadcast=$broadcast changed=$changed shouldResume=$shouldResume"
        )
        setSourceSeparationPlaybackProcessing(null)
        sourceSeparationPlaybackResumeWhenReady = false
        sourceSeparationPlaybackGateJob?.cancel()
        sourceSeparationPlaybackGateJob = null
        if (!preserveExpectedProcessing) {
            setSourceSeparationPlaybackExpectProcessing(false)
        }
        updateSourceSeparationProcessingLease("clearProcessing")
        if (sourceSeparationOutputMuted && sourceSeparationPlaybackSession == null) {
            restoreSourceSeparationOutputVolume("clearProcessing")
        }
        if (shouldResume) {
            restoreSourceSeparationOutputVolume("clearProcessing.resumeOriginal")
            setSourceSeparationPlayWhenReady(true)
        }
        if (broadcast && changed) {
            broadcastSourceSeparationPlaybackChanged()
        }
    }

    private fun waitForSourceSeparationPlayback(
        source: String,
        processingCacheKey: String,
        waitingPositionMs: Long,
        requiredReadyWindowCount: Int,
        restoreOriginalItem: Boolean,
        resumeWhenReady: Boolean,
        showMessage: Boolean,
    ): SessionResult {
        require(processingCacheKey.isNotBlank()) {
            "Source-separation processing cache key is empty."
        }
        val shouldResume = sourceSeparationPlaybackResumeWhenReady ||
                resumeWhenReady ||
                player.playWhenReady ||
                player.isPlaying ||
                sourceSeparationPlaybackPlayIntent
        var windowWait = sourceSeparationPlaybackWindowWaitTracker.begin(
            cacheKey = processingCacheKey,
            anchorPositionMs = waitingPositionMs,
            requiredReadyWindowCount = requiredReadyWindowCount,
            resumeWhenReady = shouldResume,
        )
        traceSourceSeparationPlayback(
            "playback.waitForReady",
            "source=$source restoreOriginalItem=$restoreOriginalItem " +
                    "resumeWhenReadyArg=$resumeWhenReady shouldResume=$shouldResume " +
                    "waitEpoch=${windowWait.epoch} anchor=${windowWait.anchorPositionMs} " +
                    "requiredReadyWindows=${windowWait.requiredReadyWindowCount}"
        )
        setSourceSeparationPlaybackProcessing(processingCacheKey)
        sourceSeparationPlaybackResumeWhenReady = windowWait.resumeWhenReady
        if (player.playWhenReady) {
            muteSourceSeparationOutputForSwitch(
                reason = "$source.processingPause",
                waitForMixedOutput = true,
            )
            setSourceSeparationPlayWhenReady(false)
            flushSourceSeparationPausedOutput("$source.processingPause")
        }
        if (restoreOriginalItem) {
            clearSourceSeparationPlayback(restoreOriginalItem = true, broadcast = false)
            windowWait = sourceSeparationPlaybackWindowWaitTracker.begin(
                cacheKey = processingCacheKey,
                anchorPositionMs = windowWait.anchorPositionMs,
                requiredReadyWindowCount = windowWait.requiredReadyWindowCount,
                resumeWhenReady = windowWait.resumeWhenReady,
            )
            setSourceSeparationPlaybackProcessing(processingCacheKey)
            sourceSeparationPlaybackResumeWhenReady = windowWait.resumeWhenReady
        }
        val message = getString(R.string.source_separation_playback_processing)
            .takeIf { showMessage }
        broadcastSourceSeparationPlaybackChanged(message)
        updateSourceSeparationProcessingLease("waitForReady:$source")
        scheduleSourceSeparationPlaybackGateRetry()
        return sourceSeparationPlaybackResult(
            resultCode = SessionResult.RESULT_SUCCESS,
            message = message,
        )
    }

    private fun alignSourceSeparationPlaybackWindowWait(
        cacheKey: String,
        fallbackPositionMs: Long,
    ): SourceSeparationPlaybackWindowWait? {
        val existing = sourceSeparationPlaybackWindowWaitTracker.current ?: return null
        sourceSeparationPlaybackWindowWaitTracker.forCache(cacheKey)?.let { return it }
        val rebound = requireNotNull(
            sourceSeparationPlaybackWindowWaitTracker.rebind(
                cacheKey = cacheKey,
                anchorPositionMs = fallbackPositionMs,
            ),
        )
        setSourceSeparationPlaybackProcessing(cacheKey)
        sourceSeparationPlaybackResumeWhenReady = rebound.resumeWhenReady
        traceSourceSeparationPlayback(
            "playback.windowWait.rebind",
            "oldCache=${existing.cacheKey.take(12)} newCache=${cacheKey.take(12)} " +
                    "oldEpoch=${existing.epoch} waitEpoch=${rebound.epoch} " +
                    "anchor=${rebound.anchorPositionMs} " +
                    "requiredReadyWindows=${rebound.requiredReadyWindowCount}",
        )
        return rebound
    }

    private fun updateSourceSeparationPlaybackReadinessMonitor(
        session: SourceSeparationPlaybackSession?,
    ) {
        if (session?.requiresReadinessGate != true || !sourceSeparationPlaybackRequested) {
            cancelSourceSeparationPlaybackReadinessMonitor("notRunningCache")
            return
        }
        if (sourceSeparationPlaybackReadinessMonitorJob?.isActive == true &&
            sourceSeparationPlaybackReadinessMonitorSessionId == session.sessionId
        ) {
            return
        }
        cancelSourceSeparationPlaybackReadinessMonitor("restart")
        sourceSeparationPlaybackReadinessMonitorSessionId = session.sessionId
        sourceSeparationPlaybackReadinessMonitorJob = serviceScope.launch {
            val monitorJob = coroutineContext[Job]
            traceSourceSeparationPlayback(
                "playback.readinessMonitor.start",
                "songId=${session.songId} session=${session.sessionId}"
            )
            var nextCheckDelayMs = SOURCE_SEPARATION_READINESS_MONITOR_DELAY_MS
            try {
                while (true) {
                    delay(nextCheckDelayMs)
                    nextCheckDelayMs = SOURCE_SEPARATION_READINESS_MONITOR_DELAY_MS
                    val activeSession = sourceSeparationPlaybackSession
                    if (!sourceSeparationPlaybackRequested ||
                        SourceSeparationBlendDemand.isCentered(
                            sourceSeparationMixProcessor.blend,
                        ) ||
                        activeSession?.sessionId != session.sessionId ||
                        !activeSession.requiresReadinessGate
                    ) {
                        break
                    }
                    if (sourceSeparationPlaybackIsProcessing || !player.playWhenReady) {
                        continue
                    }
                    val positionMs = player.currentPosition.coerceAtLeast(0)
                    val runtimeSong = activeSession.runtimeSong
                    val horizon = withContext(IO) {
                        runCatching {
                            sourceSeparationRuntime.readyHorizon(
                                song = runtimeSong,
                                playbackPositionMs = positionMs,
                            )
                        }.getOrDefault(SourceSeparationModelAwareReadyHorizonStatus.Unavailable)
                    }
                    if (sourceSeparationPlaybackSession?.sessionId != session.sessionId ||
                        !sourceSeparationPlaybackRequested
                    ) {
                        break
                    }
                    when (horizon) {
                        is SourceSeparationModelAwareReadyHorizonStatus.Ready -> {
                            if (!horizon.readyThroughEnd) {
                                nextCheckDelayMs = horizon.readyAheadMs.coerceIn(
                                    SOURCE_SEPARATION_READINESS_MONITOR_MIN_DELAY_MS,
                                    SOURCE_SEPARATION_READINESS_MONITOR_DELAY_MS,
                                )
                            }
                        }
                        is SourceSeparationModelAwareReadyHorizonStatus.Completed -> {
                            traceSourceSeparationPlayback(
                                "playback.readinessMonitor.completed",
                                "songId=${session.songId} session=${session.sessionId} position=$positionMs"
                            )
                            val activeSession = sourceSeparationPlaybackSession
                            if (activeSession?.sessionId == session.sessionId &&
                                activeSession.requiresReadinessGate
                            ) {
                                sourceSeparationPlaybackSession = activeSession.copy(
                                    requiresReadinessGate = false,
                                )
                            }
                            break
                        }
                        SourceSeparationModelAwareReadyHorizonStatus.Processing -> {
                            traceSourceSeparationPlayback(
                                "playback.readinessMonitor.processing",
                                "songId=${session.songId} session=${session.sessionId} " +
                                        "position=$positionMs"
                            )
                            waitForSourceSeparationPlayback(
                                source = "readinessMonitor",
                                processingCacheKey = runtimeSong.cacheKey,
                                waitingPositionMs = positionMs,
                                requiredReadyWindowCount =
                                    sourceSeparationPlaybackReadyWindowCount,
                                restoreOriginalItem = false,
                                resumeWhenReady = true,
                                showMessage = false,
                            )
                            break
                        }
                        SourceSeparationModelAwareReadyHorizonStatus.Busy -> {
                            traceSourceSeparationPlayback(
                                "playback.readinessMonitor.busy",
                                "songId=${session.songId} session=${session.sessionId} " +
                                        "position=$positionMs",
                            )
                        }
                        SourceSeparationModelAwareReadyHorizonStatus.Unavailable -> {
                            traceSourceSeparationPlayback(
                                "playback.readinessMonitor.unavailable",
                                "songId=${session.songId} session=${session.sessionId} position=$positionMs"
                            )
                            if (sourceSeparationPlaybackSession?.sessionId == session.sessionId) {
                                sourceSeparationPlaybackReadinessMonitorSessionId = null
                                sourceSeparationPlaybackReadinessMonitorJob = null
                                clearSourceSeparationPlayback(restoreOriginalItem = true)
                                clearSourceSeparationPlaybackProcessing()
                            }
                            break
                        }
                    }
                }
            } finally {
                if (sourceSeparationPlaybackReadinessMonitorJob == monitorJob) {
                    sourceSeparationPlaybackReadinessMonitorSessionId = null
                    sourceSeparationPlaybackReadinessMonitorJob = null
                }
                traceSourceSeparationPlayback(
                    "playback.readinessMonitor.stop",
                    "songId=${session.songId} session=${session.sessionId}"
                )
            }
        }
    }

    private fun cancelSourceSeparationPlaybackReadinessMonitor(reason: String) {
        if (sourceSeparationPlaybackReadinessMonitorJob != null) {
            traceSourceSeparationPlayback(
                "playback.readinessMonitor.cancel",
                "reason=$reason session=$sourceSeparationPlaybackReadinessMonitorSessionId"
            )
        }
        sourceSeparationPlaybackReadinessMonitorJob?.cancel()
        sourceSeparationPlaybackReadinessMonitorJob = null
        sourceSeparationPlaybackReadinessMonitorSessionId = null
    }

    private fun updateSourceSeparationDataPlaneMonitor(
        session: SourceSeparationPlaybackSession,
    ) {
        if (sourceSeparationDataPlaneMonitorJob?.isActive == true &&
            sourceSeparationDataPlaneMonitorSessionId == session.sessionId
        ) {
            return
        }
        cancelSourceSeparationDataPlaneMonitor("restart")
        sourceSeparationDataPlaneMonitorSessionId = session.sessionId
        sourceSeparationDataPlaneMonitorJob = serviceScope.launch {
            val monitorJob = coroutineContext[Job]
            traceSourceSeparationPlayback(
                "playback.dataPlaneMonitor.start",
                "songId=${session.songId} session=${session.sessionId}",
            )
            try {
                while (true) {
                    val activeSession = sourceSeparationPlaybackSession
                    if (activeSession?.sessionId != session.sessionId) break
                    if (!handleSourceSeparationDataPlaneState(activeSession, "fallback")) break
                    delay(SOURCE_SEPARATION_DATA_PLANE_MONITOR_FALLBACK_DELAY_MS)
                }
            } finally {
                if (sourceSeparationDataPlaneMonitorJob == monitorJob) {
                    sourceSeparationDataPlaneMonitorJob = null
                    sourceSeparationDataPlaneMonitorSessionId = null
                }
                traceSourceSeparationPlayback(
                    "playback.dataPlaneMonitor.stop",
                    "songId=${session.songId} session=${session.sessionId}",
                )
            }
        }
    }

    private fun onSourceSeparationDataPlaneStateChanged(
        signaledState: SourceSeparationPlaybackDataState,
    ) {
        val session = sourceSeparationPlaybackSession ?: return
        handleSourceSeparationDataPlaneState(
            session = session,
            reason = "event:$signaledState",
        )
    }

    private fun handleSourceSeparationDataPlaneState(
        session: SourceSeparationPlaybackSession,
        reason: String,
    ): Boolean {
        if (sourceSeparationPlaybackSession?.sessionId != session.sessionId) return false
        return when (sourceSeparationMixProcessor.dataPlaneState()) {
            SourceSeparationPlaybackDataState.Preparing,
            SourceSeparationPlaybackDataState.Buffering,
            SourceSeparationPlaybackDataState.Seeking,
            SourceSeparationPlaybackDataState.HotSwapping,
            -> {
                gateSourceSeparationDataPlaneUntilReady(session, reason)
                true
            }

            SourceSeparationPlaybackDataState.Ready -> {
                sourceSeparationMixProcessor.consumeDataPlaneReadyNotification()
                resumeSourceSeparationDataPlaneIfRequested(session)
                true
            }

            SourceSeparationPlaybackDataState.Failed -> {
                handleSourceSeparationDataPlaneFailure(session)
                false
            }

            SourceSeparationPlaybackDataState.Idle,
            SourceSeparationPlaybackDataState.Ended,
            -> false
        }
    }

    private fun gateSourceSeparationDataPlaneUntilReady(
        session: SourceSeparationPlaybackSession?,
        reason: String,
    ) {
        if (session == null ||
            sourceSeparationPlaybackSession?.sessionId != session.sessionId ||
            sourceSeparationMixProcessor.isDataPlaneReady()
        ) {
            return
        }
        val shouldResume = sourceSeparationDataPlaneResumeWhenReady ||
                player.playWhenReady ||
                player.isPlaying ||
                sourceSeparationPlaybackPlayIntent
        sourceSeparationDataPlaneResumeWhenReady = shouldResume
        if (player.playWhenReady) {
            traceSourceSeparationPlayback(
                "playback.dataPlaneMonitor.pause",
                "songId=${session.songId} session=${session.sessionId} reason=$reason",
            )
            setSourceSeparationPlayWhenReady(false)
        }
    }

    private fun resumeSourceSeparationDataPlaneIfRequested(
        session: SourceSeparationPlaybackSession,
    ) {
        if (!sourceSeparationDataPlaneResumeWhenReady ||
            sourceSeparationPlaybackSession?.sessionId != session.sessionId ||
            !sourceSeparationMixProcessor.isDataPlaneReady()
        ) {
            return
        }
        // Cache recovery is the outer transport barrier. A ready PCM ring must not
        // release playback until the exact cache-window waterline is also ready.
        val windowWait = sourceSeparationPlaybackWindowWaitTracker.current
        if (sourceSeparationPlaybackIsProcessing || windowWait != null) {
            traceSourceSeparationPlayback(
                "playback.dataPlaneMonitor.readyDeferred",
                "songId=${session.songId} session=${session.sessionId} " +
                        "waitEpoch=${windowWait?.epoch}",
            )
            return
        }
        val shouldResume = sourceSeparationPlaybackPlayIntent
        sourceSeparationDataPlaneResumeWhenReady = false
        traceSourceSeparationPlayback(
            "playback.dataPlaneMonitor.ready",
            "songId=${session.songId} session=${session.sessionId} resume=$shouldResume",
        )
        if (shouldResume) {
            setSourceSeparationPlayWhenReady(true)
        } else {
            restoreSourceSeparationOutputVolume("dataPlaneReady")
        }
    }

    private fun handleSourceSeparationDataPlaneFailure(
        session: SourceSeparationPlaybackSession,
    ) {
        if (sourceSeparationPlaybackSession?.sessionId != session.sessionId) return
        val shouldResume = sourceSeparationDataPlaneResumeWhenReady || player.playWhenReady
        sourceSeparationDataPlaneResumeWhenReady = false
        traceSourceSeparationPlayback(
            "playback.dataPlaneMonitor.failed",
            "songId=${session.songId} session=${session.sessionId} resume=$shouldResume",
        )
        clearSourceSeparationPlayback(restoreOriginalItem = false, broadcast = false)
        restoreSourceSeparationOutputVolume("dataPlaneFailed")
        if (shouldResume && sourceSeparationPlaybackPlayIntent) {
            setSourceSeparationPlayWhenReady(true)
        }
        broadcastSourceSeparationPlaybackChanged(
            getString(R.string.source_separation_playback_unavailable),
        )
    }

    private fun cancelSourceSeparationDataPlaneMonitor(reason: String) {
        if (sourceSeparationDataPlaneMonitorJob != null) {
            traceSourceSeparationPlayback(
                "playback.dataPlaneMonitor.cancel",
                "reason=$reason session=$sourceSeparationDataPlaneMonitorSessionId",
            )
        }
        sourceSeparationDataPlaneMonitorJob?.cancel()
        sourceSeparationDataPlaneMonitorJob = null
        sourceSeparationDataPlaneMonitorSessionId = null
        sourceSeparationDataPlaneResumeWhenReady = false
    }

    private fun prepareSourceSeparationPlaybackTransition(
        reason: String,
    ): Boolean {
        val expectProcessingOnTransition =
            shouldExpectSourceSeparationProcessingOnTransition()
        setSourceSeparationPlaybackExpectProcessing(expectProcessingOnTransition)
        traceSourceSeparationPlayback(
            "playback.transitionProbe",
            "reason=$reason expectProcessing=$expectProcessingOnTransition"
        )
        return expectProcessingOnTransition
    }

    private fun scheduleSourceSeparationPlaybackGateRetry() {
        if (sourceSeparationPlaybackGateJob?.isActive == true ||
            !sourceSeparationPlaybackRequested
        ) {
            traceSourceSeparationPlayback(
                "playback.scheduleRetry.skip",
                "jobActive=${sourceSeparationPlaybackGateJob?.isActive == true} " +
                        "requested=$sourceSeparationPlaybackRequested"
            )
            return
        }
        val windowWait = sourceSeparationPlaybackWindowWaitTracker.current
        if (!sourceSeparationPlaybackIsProcessing || windowWait == null) {
            traceSourceSeparationPlayback(
                "playback.scheduleRetry.skip",
                "processing=$sourceSeparationPlaybackIsProcessing wait=${windowWait?.epoch}",
            )
            return
        }

        val delayMs = SOURCE_SEPARATION_PROCESSING_RETRY_DELAY_MS
        traceSourceSeparationPlayback(
            "playback.scheduleRetry",
            "delayMs=$delayMs waitEpoch=${windowWait.epoch} " +
                    "anchor=${windowWait.anchorPositionMs} " +
                    "requiredReadyWindows=${windowWait.requiredReadyWindowCount}",
        )
        sourceSeparationPlaybackGateJob = serviceScope.launch {
            val runningJob = coroutineContext[Job]
            delay(delayMs)
            if (!sourceSeparationPlaybackWindowWaitTracker.isCurrent(windowWait.epoch)) {
                traceSourceSeparationPlayback(
                    "playback.retry.stale",
                    "expectedEpoch=${windowWait.epoch} " +
                            "currentEpoch=${sourceSeparationPlaybackWindowWaitTracker.current?.epoch}",
                )
                if (sourceSeparationPlaybackGateJob == runningJob) {
                    sourceSeparationPlaybackGateJob = null
                }
                return@launch
            }
            if (sourceSeparationPlaybackGateJob == runningJob) {
                sourceSeparationPlaybackGateJob = null
            }
            traceSourceSeparationPlayback(
                "playback.retry",
                "waitEpoch=${windowWait.epoch} anchor=${windowWait.anchorPositionMs}",
            )
            ensureSourceSeparationPlaybackReady(
                showUnavailableMessage = false,
                gateOnUnreadyWindow = true,
                requiredReadyWindowCount = windowWait.requiredReadyWindowCount,
                expectProcessing = sourceSeparationPlaybackExpectProcessing,
            )
        }
    }

    private fun isSourceSeparationPlaybackCheckCurrent(
        checkId: Long,
        contextGeneration: Long,
        mediaItem: MediaItem,
        mediaItemIndex: Int,
        stage: String,
    ): Boolean {
        val currentMediaItem = player.currentMediaItem
        val isCurrent = sourceSeparationPlaybackRequested &&
                contextGeneration == sourceSeparationPlaybackContextGeneration &&
                mediaItemIndex == player.currentMediaItemIndex &&
                currentMediaItem?.mediaId == mediaItem.mediaId
        if (!isCurrent) {
            traceSourceSeparationPlayback(
                "check.stale",
                "id=$checkId stage=$stage startGeneration=$contextGeneration " +
                        "currentGeneration=$sourceSeparationPlaybackContextGeneration " +
                        "startIndex=$mediaItemIndex currentIndex=${player.currentMediaItemIndex} " +
                        "startMediaId=${mediaItem.mediaId} currentMediaId=${currentMediaItem?.mediaId}"
            )
        }
        return isCurrent
    }

    private fun pauseSourceSeparationOutputForSwitch(
        reason: String,
        waitForMixedOutput: Boolean = false,
    ): Boolean {
        val shouldResume = player.playWhenReady
        traceSourceSeparationPlayback(
            "playback.pauseForSwitch",
            "reason=$reason shouldResume=$shouldResume waitForMixedOutput=$waitForMixedOutput"
        )
        muteSourceSeparationOutputForSwitch(reason, waitForMixedOutput)
        if (shouldResume) {
            setSourceSeparationPlayWhenReady(false)
        }
        return shouldResume
    }

    private fun resumeSourceSeparationOutputAfterSwitch(reason: String, resume: Boolean) {
        traceSourceSeparationPlayback(
            "playback.resumeAfterSwitch",
            "reason=$reason resume=$resume"
        )
        if (resume) {
            val session = sourceSeparationPlaybackSession
            val cacheWaitActive = sourceSeparationPlaybackIsProcessing ||
                    sourceSeparationPlaybackWindowWaitTracker.current != null
            if (session != null &&
                (cacheWaitActive || !sourceSeparationMixProcessor.isDataPlaneReady())
            ) {
                sourceSeparationDataPlaneResumeWhenReady = true
                updateSourceSeparationDataPlaneMonitor(session)
            } else {
                setSourceSeparationPlayWhenReady(true)
            }
        } else {
            restoreSourceSeparationOutputVolume(reason)
        }
    }

    private fun muteSourceSeparationOutputForSwitch(
        reason: String,
        waitForMixedOutput: Boolean,
    ) {
        traceSourceSeparationPlayback(
            "playback.outputMute",
            "reason=$reason waitForMixedOutput=$waitForMixedOutput volume=${player.volume}"
        )
        sourceSeparationOutputMuteJob?.cancel()
        sourceSeparationOutputMuteJob = null
        sourceSeparationOutputMuted = true
        sourceSeparationOutputWaitingForMixedOutput = waitForMixedOutput
        sourceSeparationOutputMuteStartedAtMs = SystemClock.elapsedRealtime()
        sourceSeparationPendingMixedOutputGeneration = null
        player.volume = 0f
        sourceSeparationOutputMuteJob = serviceScope.launch {
            val delayMs = if (waitForMixedOutput) {
                SOURCE_SEPARATION_OUTPUT_UNMUTE_FALLBACK_DELAY_MS
            } else {
                SOURCE_SEPARATION_OUTPUT_UNMUTE_DELAY_MS
            }
            delay(delayMs)
            restoreSourceSeparationOutputVolume("$reason.timeout")
        }
    }

    private fun onSourceSeparationMixedOutputStarted(outputGeneration: Long) {
        if (!sourceSeparationOutputMuted || !sourceSeparationOutputWaitingForMixedOutput) return
        sourceSeparationPendingMixedOutputGeneration = outputGeneration
        maybeAcceptSourceSeparationMixedOutput("mixedOutputStarted")
    }

    private fun maybeAcceptSourceSeparationMixedOutput(reason: String) {
        if (!sourceSeparationOutputMuted || !sourceSeparationOutputWaitingForMixedOutput) return
        val outputGeneration = sourceSeparationPendingMixedOutputGeneration ?: return
        val outputFlush = sourceSeparationPendingOutputFlush
        if (outputFlush != null &&
            outputFlush.outputGeneration == null
        ) {
            traceSourceSeparationPlayback(
                "playback.mixedOutputStarted.deferred",
                "reason=flushPending barrier=${outputFlush.barrierId} " +
                        "actualGeneration=$outputGeneration",
            )
            return
        }
        if (outputFlush != null && outputFlush.outputGeneration != outputGeneration) {
            traceSourceSeparationPlayback(
                "playback.mixedOutputStarted.stale",
                "reason=flushGenerationMismatch barrier=${outputFlush.barrierId} " +
                        "expectedGeneration=${outputFlush.outputGeneration} " +
                        "actualGeneration=$outputGeneration",
            )
            sourceSeparationPendingMixedOutputGeneration = null
            return
        }
        if (sourceSeparationPlaybackIsProcessing ||
            sourceSeparationPlaybackWindowWaitTracker.current != null
        ) {
            traceSourceSeparationPlayback(
                "playback.mixedOutputStarted.deferred",
                "reason=cacheWait source=$reason generation=$outputGeneration",
            )
            return
        }
        traceSourceSeparationPlayback(
            "playback.mixedOutputStarted",
            "source=$reason generation=$outputGeneration",
        )
        sourceSeparationPendingMixedOutputGeneration = null
        clearSourceSeparationOutputFlushBarrier("mixedOutputStarted")
        sourceSeparationOutputWaitingForMixedOutput = false
        sourceSeparationOutputMuteJob?.cancel()
        sourceSeparationOutputMuteJob = serviceScope.launch {
            delay(SOURCE_SEPARATION_OUTPUT_UNMUTE_DELAY_MS)
            restoreSourceSeparationOutputVolume("mixedOutputStarted")
        }
    }

    private fun restoreSourceSeparationOutputVolume(reason: String) {
        if (!sourceSeparationOutputMuted) return
        traceSourceSeparationPlayback(
            "playback.outputUnmute",
            "reason=$reason volume=${equalizerManager.volumeState.value.currentVolume} " +
                    "mutedForMs=${SystemClock.elapsedRealtime() - sourceSeparationOutputMuteStartedAtMs}"
        )
        sourceSeparationOutputMuteJob?.cancel()
        sourceSeparationOutputMuteJob = null
        sourceSeparationOutputMuted = false
        sourceSeparationOutputWaitingForMixedOutput = false
        sourceSeparationOutputMuteStartedAtMs = 0L
        sourceSeparationPendingMixedOutputGeneration = null
        clearSourceSeparationOutputFlushBarrier("outputUnmute.$reason")
        restorePlayerVolume()
    }

    private fun onSourceSeparationOutputFlushed(
        barrierId: Long,
        outputGeneration: Long,
    ) {
        val pending = sourceSeparationPendingOutputFlush
        if (pending?.barrierId != barrierId) {
            traceSourceSeparationPlayback(
                "playback.outputFlush.stale",
                "barrier=$barrierId generation=$outputGeneration " +
                        "pending=${pending?.barrierId}",
            )
            return
        }
        sourceSeparationPendingOutputFlush = pending.copy(
            outputGeneration = outputGeneration,
        )
        traceSourceSeparationPlayback(
            "playback.outputFlush.confirmed",
            "reason=${pending.reason} barrier=$barrierId generation=$outputGeneration",
        )
        maybeAcceptSourceSeparationMixedOutput("outputFlushConfirmed")
    }

    private fun shouldExpectSourceSeparationProcessingOnTransition(): Boolean {
        if (!preferences.getBoolean(
                SOURCE_SEPARATION_AUTO_START,
                DEFAULT_SOURCE_SEPARATION_AUTO_START,
            )
        ) {
            return false
        }
        if (!sourceSeparationPlaybackAutoSyncOnTransition) return false
        return SourceSeparationBlendDemand.requiresSeparatedOutput(
            sourceSeparationMixProcessor.blend,
        )
    }

    private fun setSourceSeparationPlaybackExpectProcessing(expectProcessing: Boolean) {
        if (sourceSeparationPlaybackExpectProcessing == expectProcessing) return
        sourceSeparationPlaybackExpectProcessing = expectProcessing
        sourceSeparationPlaybackExpectProcessingStartedAtMs = if (expectProcessing) {
            SystemClock.elapsedRealtime()
        } else {
            0L
        }
    }

    private fun shouldResumeSourceSeparationPlaybackWhenReady(): Boolean {
        return sourceSeparationPlaybackResumeWhenReady ||
                player.playWhenReady ||
                player.isPlaying ||
                sourceSeparationPlaybackPlayIntent
    }

    private fun shouldGateCurrentSourceSeparationWindow(): Boolean {
        return sourceSeparationPlaybackRequested &&
                SourceSeparationBlendDemand.requiresSeparatedOutput(
                    sourceSeparationMixProcessor.blend,
                ) &&
                shouldResumeSourceSeparationPlaybackWhenReady()
    }

    private fun shouldClearSourceSeparationPlaybackPlayIntent(reason: Int): Boolean {
        if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
            return false
        }
        if (sourceSeparationPlaybackIsProcessing) {
            return isManualPlayWhenReadyPauseReason(reason)
        }
        return isManualPlayWhenReadyPauseReason(reason) ||
                !player.playWhenReady
    }

    private fun isSourceSeparationForegroundWorkerClockAdvancing(): Boolean {
        return player.isPlaying ||
                (sourceSeparationPlaybackIsProcessing &&
                        shouldResumeSourceSeparationPlaybackWhenReady())
    }

    private fun setSourceSeparationPlaybackProcessing(cacheKey: String?) {
        require(cacheKey == null || cacheKey.isNotBlank()) {
            "Source-separation processing cache key is empty."
        }
        if (cacheKey == null) {
            sourceSeparationPlaybackWindowWaitTracker.clear()
        }
        sourceSeparationPlaybackIsProcessing = cacheKey != null
        sourceSeparationPlaybackProcessingCacheKey = cacheKey
    }

    private fun isSourceSeparationProcessingLeaseNeeded(): Boolean {
        return SourceSeparationProcessingLeasePolicy.shouldPlaybackServiceOwn(
            waitingForProcessingCache =
                isSourceSeparationPlaybackWaitingForProcessingCache(),
            waitingCacheKey = sourceSeparationPlaybackProcessingCacheKey,
            handoff = sourceSeparationProcessingOwnershipHandoff.stateFlow.value,
        )
    }

    private fun isSourceSeparationPlaybackWaitingForProcessingCache(): Boolean {
        if (!sourceSeparationPlaybackRequested ||
            !sourceSeparationPlaybackIsProcessing ||
            !shouldResumeSourceSeparationPlaybackWhenReady()
        ) {
            return false
        }
        if (player.isPlaying && player.playbackState == Player.STATE_READY) {
            return false
        }
        return sourceSeparationPlaybackGateJob?.isActive == true ||
                sourceSeparationPlaybackSession?.requiresReadinessGate == true ||
                sourceSeparationPlaybackSession == null ||
                player.playbackState == Player.STATE_BUFFERING ||
                !player.playWhenReady
    }

    private fun updateSourceSeparationProcessingLease(reason: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            serviceScope.launch {
                updateSourceSeparationProcessingLease(reason)
            }
            return
        }
        updateSourceSeparationMediaSessionBuffering()
        if (isSourceSeparationProcessingLeaseNeeded()) {
            startSourceSeparationProcessingLease(reason)
        } else {
            stopSourceSeparationProcessingLease(reason)
        }
        updateSourceSeparationForegroundServiceType(reason)
        recordSourceSeparationProcessingLease(reason)
    }

    private fun recordSourceSeparationProcessingLease(reason: String) {
        sourceSeparationProcessingOwnershipHandoff.recordPlaybackServiceLease(
            cacheKey = sourceSeparationPlaybackProcessingCacheKey,
            ownsProcessing = isSourceSeparationProcessingLeaseNeeded(),
            wakeLockHeld = sourceSeparationProcessingWakeLock?.isHeld == true,
            foregroundServiceType = sourceSeparationForegroundServiceType,
            reason = reason,
        )
    }

    private fun updateSourceSeparationMediaSessionBuffering() {
        if (!::mediaSessionPlayer.isInitialized) return

        mediaSessionPlayer.setSourceSeparationVirtualBuffering(
            isSourceSeparationMediaSessionBuffering()
        )
    }

    private fun isSourceSeparationMediaSessionBuffering(): Boolean {
        return sourceSeparationPlaybackIsProcessing &&
                sourceSeparationPlaybackResumeWhenReady
    }

    private fun startSourceSeparationProcessingLease(reason: String) {
        startSourceSeparationProcessingWakeLockKeeper(reason)
        updateSourceSeparationForegroundServiceType(reason, force = true)
        if (sourceSeparationProcessingHeartbeatJob?.isActive == true) return

        sourceSeparationProcessingHeartbeatJob = serviceScope.launch {
            val heartbeatJob = coroutineContext[Job]
            traceSourceSeparationPlayback("lease.heartbeat.start", "reason=$reason")
            try {
                while (isSourceSeparationProcessingLeaseNeeded()) {
                    delay(SOURCE_SEPARATION_PROCESSING_LEASE_HEARTBEAT_MS)
                    if (isSourceSeparationProcessingLeaseNeeded()) {
                        refreshSourceSeparationProcessingLease("heartbeat")
                    }
                }
            } finally {
                if (sourceSeparationProcessingHeartbeatJob == heartbeatJob) {
                    sourceSeparationProcessingHeartbeatJob = null
                }
                traceSourceSeparationPlayback("lease.heartbeat.stop", "reason=$reason")
                if (!isSourceSeparationProcessingLeaseNeeded()) {
                    stopSourceSeparationProcessingLease("heartbeat.stop")
                }
            }
        }
    }

    private fun refreshSourceSeparationProcessingLease(reason: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            serviceScope.launch {
                refreshSourceSeparationProcessingLease(reason)
            }
            return
        }
        if (!isSourceSeparationProcessingLeaseNeeded()) {
            stopSourceSeparationProcessingLease(reason)
            return
        }
        traceSourceSeparationPlayback("lease.refresh", "reason=$reason")
        sourceSeparationPlaybackPlayIntent = true
        updateSourceSeparationForegroundWorkerPosition()
        renewSourceSeparationProcessingWakeLock(reason)
        updateSourceSeparationForegroundServiceType(reason, force = true)
        scheduleSourceSeparationPlaybackGateRetry()
    }

    private fun stopSourceSeparationProcessingLease(
        reason: String,
        force: Boolean = false,
    ) {
        if (!force && isSourceSeparationProcessingLeaseNeeded()) return
        sourceSeparationProcessingHeartbeatJob?.cancel()
        sourceSeparationProcessingHeartbeatJob = null
        sourceSeparationProcessingWakeLockJob?.cancel()
        sourceSeparationProcessingWakeLockJob = null
        releaseSourceSeparationProcessingWakeLock(reason)
        updateSourceSeparationForegroundServiceType(reason)
    }

    private fun startSourceSeparationProcessingWakeLockKeeper(reason: String) {
        acquireSourceSeparationProcessingWakeLock(reason)
        if (sourceSeparationProcessingWakeLockJob?.isActive == true) return

        sourceSeparationProcessingWakeLockJob = serviceScope.launch {
            val keeperJob = coroutineContext[Job]
            traceSourceSeparationPlayback("lease.wakeLockKeeper.start", "reason=$reason")
            try {
                while (isSourceSeparationProcessingLeaseNeeded()) {
                    delay(SOURCE_SEPARATION_PROCESSING_WAKE_LOCK_REFRESH_MS)
                    if (isSourceSeparationProcessingLeaseNeeded()) {
                        renewSourceSeparationProcessingWakeLock("keeper")
                        updateSourceSeparationForegroundServiceType("keeper", force = true)
                    }
                }
            } finally {
                if (sourceSeparationProcessingWakeLockJob == keeperJob) {
                    sourceSeparationProcessingWakeLockJob = null
                }
                if (!isSourceSeparationProcessingLeaseNeeded()) {
                    releaseSourceSeparationProcessingWakeLock("keeper.stop")
                }
                traceSourceSeparationPlayback("lease.wakeLockKeeper.stop", "reason=$reason")
            }
        }
    }

    private fun updateSourceSeparationForegroundServiceType(
        reason: String,
        force: Boolean = false,
    ) {
        if (Build.VERSION.SDK_INT < 35) return
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                if (isSourceSeparationProcessingLeaseNeeded()) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
                } else {
                    0
                }
        if (sourceSeparationForegroundServiceType == type) {
            if (!force) return
            if (isSourceSeparationMediaSessionBuffering()) {
                val elapsedSinceUpdate =
                    SystemClock.elapsedRealtime() - sourceSeparationForegroundServiceTypeUpdatedAtMs
                if (elapsedSinceUpdate < SOURCE_SEPARATION_BUFFERING_FGS_REFRESH_MS) {
                    return
                }
            }
        }
        val notification = getSystemService<NotificationManager>()
            ?.activeNotifications
            ?.firstOrNull { it.id == NOTIFICATION_ID }
            ?.notification
            ?: run {
                traceSourceSeparationPlayback(
                    "lease.fgsType.skip",
                    "reason=$reason notificationMissing=true"
                )
                return
            }
        runCatching {
            startForeground(NOTIFICATION_ID, notification, type)
            sourceSeparationForegroundServiceType = type
            sourceSeparationForegroundServiceTypeUpdatedAtMs = SystemClock.elapsedRealtime()
            traceSourceSeparationPlayback("lease.fgsType.update", "reason=$reason type=$type")
        }.onFailure { error ->
            Log.w(TAG_SOURCE_SEPARATION_PLAYBACK, "Unable to update foreground service type", error)
            traceSourceSeparationPlayback(
                "lease.fgsType.failed",
                "reason=$reason error=${error::class.java.simpleName}:${error.message}"
            )
        }
    }

    private fun acquireSourceSeparationProcessingWakeLock(reason: String) {
        val wakeLock = sourceSeparationProcessingWakeLock
            ?: (getSystemService<PowerManager>()?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "$PACKAGE_NAME:SourceSeparationProcessing",
            )?.apply {
                setReferenceCounted(false)
            } ?: return).also {
                sourceSeparationProcessingWakeLock = it
            }
        if (!wakeLock.isHeld) {
            traceSourceSeparationPlayback("lease.wakeLock.acquire", "reason=$reason")
            wakeLock.acquire()
        }
    }

    private fun renewSourceSeparationProcessingWakeLock(reason: String) {
        val wakeLock = sourceSeparationProcessingWakeLock
        if (wakeLock?.isHeld == true) {
            traceSourceSeparationPlayback("lease.wakeLock.renew", "reason=$reason")
            runCatching {
                wakeLock.release()
            }.onFailure { error ->
                Log.w(
                    TAG_SOURCE_SEPARATION_PLAYBACK,
                    "Unable to renew source separation wake lock",
                    error,
                )
            }
        }
        acquireSourceSeparationProcessingWakeLock("$reason.renew")
    }

    private fun releaseSourceSeparationProcessingWakeLock(reason: String) {
        val wakeLock = sourceSeparationProcessingWakeLock ?: return
        if (!wakeLock.isHeld) return
        traceSourceSeparationPlayback("lease.wakeLock.release", "reason=$reason")
        runCatching {
            wakeLock.release()
        }.onFailure { error ->
            Log.w(
                TAG_SOURCE_SEPARATION_PLAYBACK,
                "Unable to release source separation wake lock",
                error,
            )
        }
    }

    private fun flushSourceSeparationPausedOutput(
        reason: String,
        forceDiscontinuity: Boolean = false,
    ): Boolean {
        val index = player.currentMediaItemIndex
        if (index == C.INDEX_UNSET) {
            traceSourceSeparationPlayback(
                "playback.flushPausedOutput.skip",
                "reason=$reason index=C.INDEX_UNSET"
            )
            return false
        }
        if (player.playbackState == Player.STATE_BUFFERING) {
            traceSourceSeparationPlayback(
                "playback.flushPausedOutput.skip",
                "reason=$reason state=BUFFERING"
            )
            return false
        }
        val positionMs = player.currentPosition.coerceAtLeast(0)
        val seekPositionMs = if (forceDiscontinuity) {
            sourceSeparationBlendFlushPosition(positionMs)
        } else {
            positionMs
        }
        traceSourceSeparationPlayback(
            "playback.flushPausedOutput",
            "reason=$reason index=$index position=$positionMs seekPosition=$seekPositionMs " +
                    "forceDiscontinuity=$forceDiscontinuity"
        )
        sourceSeparationPlaybackInternalSeekTarget =
            SourceSeparationPlaybackInternalSeekTarget(index, seekPositionMs)
        player.seekTo(index, seekPositionMs)
        return true
    }

    private fun requestSourceSeparationOutputFlush(reason: String): Boolean {
        clearSourceSeparationOutputFlushBarrier("superseded.$reason")
        val barrierId = ++sourceSeparationOutputFlushBarrierSeq
        if (!sourceSeparationMixProcessor.armOutputFlushBarrier(barrierId)) {
            traceSourceSeparationPlayback(
                "playback.outputFlush.skip",
                "reason=$reason barrier=$barrierId processorInactive",
            )
            return false
        }
        sourceSeparationPendingOutputFlush = SourceSeparationPendingOutputFlush(
            barrierId = barrierId,
            reason = reason,
            outputGeneration = null,
        )
        traceSourceSeparationPlayback(
            "playback.outputFlush.request",
            "reason=$reason barrier=$barrierId",
        )
        if (flushSourceSeparationPausedOutput(
                reason = "$reason.outputFlush",
                forceDiscontinuity = true,
            )
        ) {
            return true
        }
        clearSourceSeparationOutputFlushBarrier("requestFailed.$reason")
        return false
    }

    private fun clearSourceSeparationOutputFlushBarrier(reason: String) {
        val pending = sourceSeparationPendingOutputFlush ?: return
        sourceSeparationPendingOutputFlush = null
        sourceSeparationMixProcessor.cancelOutputFlushBarrier(pending.barrierId)
        traceSourceSeparationPlayback(
            "playback.outputFlush.clear",
            "reason=$reason barrier=${pending.barrierId} " +
                    "generation=${pending.outputGeneration}",
        )
    }

    private fun consumeSourceSeparationInternalSeekTarget(
        newPosition: Player.PositionInfo,
        reason: Int,
    ): Boolean {
        if (reason != Player.DISCONTINUITY_REASON_SEEK) return false
        val target = sourceSeparationPlaybackInternalSeekTarget ?: return false
        sourceSeparationPlaybackInternalSeekTarget = null
        return target.mediaItemIndex == newPosition.mediaItemIndex &&
                target.positionMs == newPosition.positionMs
    }

    private fun flushPendingSourceSeparationPausedBlendChange(reason: String) {
        if (!sourceSeparationPausedBlendFlushPending) return
        sourceSeparationPausedBlendFlushPending = false
        if (sourceSeparationPlaybackSession == null) return

        flushSourceSeparationPausedOutput(
            reason = "blendChanged.$reason",
            forceDiscontinuity = true,
        )
    }

    private fun sourceSeparationBlendFlushPosition(positionMs: Long): Long {
        val durationMs = player.duration
        return if (durationMs != C.TIME_UNSET &&
            positionMs + SOURCE_SEPARATION_BLEND_FLUSH_SEEK_OFFSET_MS >= durationMs
        ) {
            (positionMs - SOURCE_SEPARATION_BLEND_FLUSH_SEEK_OFFSET_MS).coerceAtLeast(0)
        } else {
            positionMs + SOURCE_SEPARATION_BLEND_FLUSH_SEEK_OFFSET_MS
        }
    }

    private fun setSourceSeparationPlayWhenReady(playWhenReady: Boolean) {
        traceSourceSeparationPlayback(
            "playback.setPlayWhenReady",
            "target=$playWhenReady current=${player.playWhenReady}"
        )
        if (playWhenReady && sourceSeparationPlaybackRequested) {
            sourceSeparationPlaybackPlayIntent = true
        }
        if (player.playWhenReady != playWhenReady) {
            sourceSeparationPlaybackInternalPlayWhenReady = playWhenReady
        } else if (sourceSeparationPlaybackInternalPlayWhenReady == playWhenReady) {
            sourceSeparationPlaybackInternalPlayWhenReady = null
        }
        player.playWhenReady = playWhenReady
    }

    private fun sourceSeparationPlaybackUnavailable(
        showMessage: Boolean,
        resultCode: Int,
        message: String,
    ): SessionResult {
        if (sourceSeparationPlaybackIsProcessing &&
            sourceSeparationPlaybackProcessingCacheKey == null
        ) {
            clearSourceSeparationPlaybackProcessing()
        }
        if (sourceSeparationOutputMuted && sourceSeparationPlaybackSession == null) {
            restoreSourceSeparationOutputVolume("playbackUnavailable")
        }
        return sourceSeparationPlaybackResult(
            resultCode = if (showMessage) resultCode else SessionResult.RESULT_SUCCESS,
            message = message.takeIf { showMessage },
        )
    }

    private fun sourceSeparationPlaybackResult(
        resultCode: Int,
        message: String? = null,
    ): SessionResult {
        return SessionResult(
            resultCode,
            sourceSeparationPlaybackBundle(message),
        )
    }

    private fun sourceSeparationPlaybackBundle(message: String? = null): Bundle {
        return Bundle().apply {
            putBoolean(
                Playback.EXTRA_SOURCE_SEPARATION_ENABLED,
                sourceSeparationPlaybackSession != null,
            )
            putBoolean(
                Playback.EXTRA_SOURCE_SEPARATION_PROCESSING,
                sourceSeparationPlaybackIsProcessing,
            )
            putFloat(Playback.EXTRA_SOURCE_SEPARATION_BLEND, sourceSeparationMixProcessor.blend)
            sourceSeparationPlaybackSession?.let { session ->
                putLong(Playback.EXTRA_SOURCE_SEPARATION_SONG_ID, session.songId)
            }
            val activeStemMix = sourceSeparationPlaybackSession
                ?.takeIf { session -> session.blendEndpointStemIds == null }
            if (activeStemMix != null) {
                putString(Playback.EXTRA_SOURCE_SEPARATION_CACHE_KEY, activeStemMix.cacheKey)
                putStringArrayList(
                    Playback.EXTRA_SOURCE_SEPARATION_STEM_IDS,
                    ArrayList(activeStemMix.stemIds),
                )
                putStringArrayList(
                    Playback.EXTRA_SOURCE_SEPARATION_STEM_LABELS,
                    ArrayList(activeStemMix.stemCanonicalLabels),
                )
                putFloatArray(
                    Playback.EXTRA_SOURCE_SEPARATION_STEM_GAINS,
                    activeStemMix.stemGains.toFloatArray(),
                )
            } else {
                sourceSeparationRequestedStemMix?.let { request ->
                    putString(Playback.EXTRA_SOURCE_SEPARATION_CACHE_KEY, request.cacheKey)
                    putStringArrayList(
                        Playback.EXTRA_SOURCE_SEPARATION_STEM_IDS,
                        ArrayList(request.stemIds),
                    )
                    putFloatArray(
                        Playback.EXTRA_SOURCE_SEPARATION_STEM_GAINS,
                        request.gains.toFloatArray(),
                    )
                }
            }
            if (!message.isNullOrEmpty()) {
                putString(Playback.EXTRA_SOURCE_SEPARATION_MESSAGE, message)
            }
        }
    }

    private fun broadcastSourceSeparationPlaybackChanged(message: String? = null) {
        cleanupCompletedSourceSeparationTemporaryDirs()
        mediaSession?.broadcastCustomCommand(
            SessionCommand(Playback.EVENT_SOURCE_SEPARATION_PLAYBACK_CHANGED, Bundle.EMPTY),
            sourceSeparationPlaybackBundle(message),
        )
    }

    private fun cleanupCompletedSourceSeparationTemporaryDirs() {
        if (sourceSeparationCompletedCleanupJob?.isActive == true) return
        sourceSeparationCompletedCleanupJob = serviceScope.launch(IO) {
            cleanCompletedSourceSeparationTemporaryDirsNow()
        }
    }

    private fun cleanCompletedSourceSeparationTemporaryDirsNow() {
        val cleanedCacheKeys = runCatching {
            sourceSeparationRuntime.cleanPendingCompletedTemporaryFiles()
        }.getOrDefault(emptyList())
        if (cleanedCacheKeys.isNotEmpty()) {
            traceSourceSeparationPlayback(
                "cleanup.completedTemporaryDirs",
                "count=${cleanedCacheKeys.size} " +
                    "keys=${cleanedCacheKeys.joinToString { it.take(12) }}"
            )
            serviceScope.launch {
                mediaSession?.broadcastCustomCommand(
                    SessionCommand(Playback.EVENT_SOURCE_SEPARATION_CACHE_CHANGED, Bundle.EMPTY),
                    Bundle().apply {
                        putStringArrayList(
                            Playback.EXTRA_SOURCE_SEPARATION_CACHE_KEYS,
                            ArrayList(cleanedCacheKeys),
                        )
                    },
                )
            }
        }
    }

    private fun cycleRepeat() {
        val currentRepeatMode = player.repeatMode
        player.repeatMode = when (currentRepeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    private fun toggleFavorite() = serviceScope.launch {
        val currentMediaItem = player.currentMediaItem
            ?: return@launch

        withContext(IO) {
            val song = repository.songByMediaItem(currentMediaItem)
            repository.toggleFavorite(song)
        }

        updateWidgets()

        refreshMediaButtonCustomLayout()
        mediaSession?.broadcastCustomCommand(
            SessionCommand(Playback.EVENT_FAVORITE_CONTENT_CHANGED, Bundle.EMPTY),
            Bundle.EMPTY
        )
    }

    private suspend fun buildPlaybackState(isForeground: Boolean): PlaybackState {
        val mediaItem = player.currentMediaItem
        val id = mediaItem?.mediaId?.toLongOrNull()
        if (mediaItem == null || id == null) return PlaybackState.empty

        val isPlaying = player.isPlaying
        val isShuffleMode = player.shuffleModeEnabled
        val repeatMode = player.repeatMode
        return withContext(IO) {
            val song = repository.songById(id)
            val isFavorite = repository.isSongFavorite(song.id)
            val result = SingletonImageLoader.get(this@PlaybackService).execute(
                ImageRequest.Builder(this@PlaybackService)
                    .data(song)
                    .scale(Scale.FILL)
                    .size(300)
                    .build()
            )
            val bitmap = result.image?.toBitmap(300, 300)
            val artworkData = bitmap?.let {
                val stream = ByteArrayOutputStream()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    it.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, stream)
                } else {
                    it.compress(Bitmap.CompressFormat.JPEG, 85, stream)
                }
                stream.toByteArray()
            }
            val widgetTheme = if (preferences.getBoolean(WIDGET_DYNAMIC_COLORS, false)) {
                val paletteColor = bitmap?.let {
                    PaletteProcessor.getPaletteColor(this@PlaybackService, bitmap)
                }
                if (paletteColor != null) {
                    WidgetTheme(paletteColor.backgroundColor)
                } else null
            } else null
            val additionalInfo = MetadataField.getMetadataValue(
                song = song,
                fields = Preferences.getExtraInfoContent(
                    key = WIDGET_THIRD_LINE_CONTENT,
                    defaultContent = Preferences.getDefaultWidgetInfo()
                )
            )
            PlaybackState(
                isSimplifiedSmallLayout = preferences.getString(WIDGET_SMALL_LAYOUT_STYLE, null) == "simplified",
                isForeground = isForeground,
                isPlaying = isPlaying,
                isFavorite = isFavorite,
                isShuffleMode = isShuffleMode,
                repeatMode = repeatMode,
                currentTitle = song.title,
                currentArtist = song.artistName,
                additionalInfo = additionalInfo,
                artworkData = artworkData,
                widgetTheme = widgetTheme,
                imageCornerRadius = preferences.getInt(WIDGET_IMAGE_CORNER_RADIUS, 8).toFloat()
            )
        }
    }

    private fun updateWidgets(force: Boolean = false, isForeground: Boolean = isPlaybackOngoing) {
        widgetUpdateJob?.cancel()
        widgetUpdateJob = serviceScope.launch {
            if (!force) delay(WIDGET_UPDATE_DEBOUNCE)

            val state = buildPlaybackState(isForeground)
            if (lastPlaybackState != state) {
                lastPlaybackState = state
                updateGlanceWidgets(state)
            }
        }
    }

    private suspend fun updateGlanceWidgets(playbackState: PlaybackState) = withContext(IO) {
        try {
            val boomingWidget = BoomingGlanceWidget()
            val boomingWidgetIds = glanceManager.getGlanceIds(boomingWidget.javaClass)
            if (boomingWidgetIds.isNotEmpty()) {
                boomingWidgetIds.forEach { id ->
                    updateAppWidgetState(applicationContext, PlaybackStateDefinition, id) {
                        playbackState
                    }
                    boomingWidget.update(applicationContext, id)
                }
            }

            val cardWidget = CardWidget()
            val cardWidgetIds = glanceManager.getGlanceIds(cardWidget.javaClass)
            if (cardWidgetIds.isNotEmpty()) {
                cardWidgetIds.forEach { id ->
                    updateAppWidgetState(applicationContext, PlaybackStateDefinition, id) {
                        playbackState
                    }
                    cardWidget.update(applicationContext, id)
                }
            }

            val fullWidget = FullWidget()
            val fullWidgetIds = glanceManager.getGlanceIds(fullWidget.javaClass)
            if (fullWidgetIds.isNotEmpty()) {
                fullWidgetIds.forEach { id ->
                    updateAppWidgetState(applicationContext, PlaybackStateDefinition, id) {
                        playbackState
                    }
                    fullWidget.update(applicationContext, id)
                }
            }
        } catch (e: Exception) {
            Log.e("PlaybackService", "Couldn't update Glance widgets", e)
        }
    }

    private fun createSessionActivityIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun createNotificationChannel() {
        var notificationChannel = nm.getNotificationChannel(CHANNEL_ID)
        if (notificationChannel == null) {
            notificationChannel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.playing_notification_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.playing_notification_description)
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.O_MR1) {
                    setShowBadge(false)
                }
            }
            nm.createNotificationChannel(notificationChannel)
        }
    }

    private fun refreshMediaButtonCustomLayout() {
        val hasTimeline = !player.currentTimeline.isEmpty
        mediaSession?.connectedControllers?.forEach { controllerInfo ->
            if (mediaSession?.isRemoteController(controllerInfo) == true) {
                val buttonLayout = if (hasTimeline) {
                    ImmutableList.of(repeatCommand, shuffleCommand)
                } else {
                    emptyList()
                }
                mediaSession?.setMediaButtonPreferences(controllerInfo, buttonLayout)
            }
        }
    }

    private fun launchMusicFadeOut(durationMs: Long = 1000) {
        cancelSleepTimerFadeOut()

        fadeOutAnimator = ValueAnimator.ofFloat(player.volume, 0f).apply {
            duration = durationMs
            addUpdateListener { animation ->
                player.volume = animation.animatedValue as Float
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    restorePlayerVolume()
                }

                override fun onAnimationEnd(animation: Animator) {
                    player.pause()
                    restorePlayerVolume()
                }
            })
        }
        fadeOutAnimator?.start()
    }

    private fun cancelSleepTimerFadeOut() {
        fadeOutAnimator?.cancel()
        fadeOutAnimator = null

        restorePlayerVolume()
    }

    private fun restorePlayerVolume() {
        if (sourceSeparationOutputMuted) {
            player.volume = 0f
            return
        }
        player.volume = equalizerManager.volumeState.value.currentVolume
    }

    private fun prepareEqualizerAndSoundSettings() {
        serviceScope.launch {
            equalizerManager.initializeEqualizer()
        }
        serviceScope.launch {
            equalizerManager.volumeState.collect { volume ->
                cancelSleepTimerFadeOut()
                if (sourceSeparationOutputMuted) {
                    player.volume = 0f
                } else {
                    player.volume = volume.currentVolume
                }
            }
        }
        serviceScope.launch {
            equalizerManager.audioOffload.collect { audioOffloadingEnabled ->
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .setAudioOffloadPreferences(
                        AudioOffloadPreferences.Builder()
                            .setAudioOffloadMode(
                                if (audioOffloadingEnabled)
                                    AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
                                else AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
                            )
                            .setIsSpeedChangeSupportRequired(true)
                            .build()
                    )
                    .build()
            }
        }
        serviceScope.launch {
            equalizerManager.skipSilence.collect {
                player.exoPlayer.skipSilenceEnabled = it
            }
        }
        serviceScope.launch {
            equalizerManager.tempoState.collect {
                player.playbackParameters = PlaybackParameters(it.speed, it.actualPitch)
            }
        }
        serviceScope.launch {
            audioOutputObserver.systemVolumeState.collect { systemVolume ->
                if (pauseOnZeroVolume && persistentStorage.restorationState.isRestored) {
                    // don't handle volume changes until our player is fully restored
                    if (isPlaying && systemVolume.currentVolume <= 0f) {
                        player.pause()
                        pausedByZeroVolume = true
                    } else if (pausedByZeroVolume && systemVolume.currentVolume >= 0.1f) {
                        player.play()
                        pausedByZeroVolume = false
                    }
                }
            }
        }
    }

    private fun updateEqualizerSessionState(isPlaying: Boolean) {
        eqStateHandler?.removeCallbacksAndMessages(null)
        uiHandler.removeCallbacks(headsetClickRunnable)
        if (isPlaying) {
            equalizerManager.setSessionIsActive(true)
        } else {
            eqStateHandler?.postDelayed(500) {
                equalizerManager.setSessionIsActive(false)
            }
        }
    }

    private fun registerReceivers() {
        if (!bluetoothConnectedRegistered) {
            ContextCompat.registerReceiver(this, bluetoothReceiver, bluetoothConnectedIntentFilter,
                ContextCompat.RECEIVER_EXPORTED)
            bluetoothConnectedRegistered = true
        }

        if (!headsetReceiverRegistered) {
            ContextCompat.registerReceiver(this, headsetReceiver, headsetReceiverIntentFilter,
                ContextCompat.RECEIVER_EXPORTED)
            headsetReceiverRegistered = true
        }
    }

    private fun prepareSourceSeparationPlaybackTrace() {
        if (!isSourceSeparationTraceFileEnabled) {
            sourceSeparationPlaybackTraceFile = null
            return
        }
        sourceSeparationPlaybackTraceFile = runCatching {
            File(
                SourceSeparationCacheDirectories.debug(this).apply { mkdirs() },
                "playback-gate.log",
            ).also { file ->
                file.writeText(
                    "Source separation playback gate trace\n" +
                            "Created: ${sourceSeparationPlaybackTraceTimestamp()}\n\n",
                    Charsets.UTF_8,
                )
            }
        }.onFailure { error ->
            Log.w(TAG, "Unable to prepare source separation playback trace file", error)
        }.getOrNull()
    }

    private fun traceSourceSeparationPlayback(
        event: String,
        detail: String = "",
    ) {
        if (!isSourceSeparationTraceFileEnabled) return
        val sequence = sourceSeparationPlaybackTraceSeq.incrementAndGet()
        val line = buildString {
            append(sourceSeparationPlaybackTraceTimestamp())
            append(" #").append(sequence)
            append(' ').append(event)
            if (detail.isNotBlank()) {
                append(" | ").append(detail)
            }
            append(" | ").append(sourceSeparationPlaybackTraceState())
        }
        Log.d(TAG_SOURCE_SEPARATION_PLAYBACK, line)
        appendSourceSeparationPlaybackTraceLine(line)
    }

    private fun traceSourceSeparationProcessor(detail: String) {
        if (!isSourceSeparationTraceFileEnabled) return
        val sequence = sourceSeparationPlaybackTraceSeq.incrementAndGet()
        val line = buildString {
            append(sourceSeparationPlaybackTraceTimestamp())
            append(" #").append(sequence)
            append(" processor | ").append(detail)
            append(" | thread=").append(Thread.currentThread().name)
        }
        Log.d(TAG_SOURCE_SEPARATION_PLAYBACK, line)
        appendSourceSeparationPlaybackTraceLine(line)
    }

    private fun appendSourceSeparationPlaybackTraceLine(line: String) {
        if (sourceSeparationPlaybackTraceFile == null) return
        var shouldFlushNow = false
        var shouldScheduleFlush = false
        synchronized(sourceSeparationPlaybackTraceLock) {
            sourceSeparationPlaybackTraceBuffer += line
            shouldFlushNow =
                sourceSeparationPlaybackTraceBuffer.size >= SOURCE_SEPARATION_TRACE_FLUSH_LINE_COUNT
            shouldScheduleFlush =
                !shouldFlushNow && sourceSeparationPlaybackTraceFlushJob?.isActive != true
            if (shouldScheduleFlush) {
                sourceSeparationPlaybackTraceFlushJob = serviceScope.launch {
                    delay(SOURCE_SEPARATION_TRACE_FLUSH_DELAY_MS)
                    flushSourceSeparationPlaybackTrace()
                }
            }
        }
        if (shouldFlushNow) {
            flushSourceSeparationPlaybackTrace()
        }
    }

    private fun flushSourceSeparationPlaybackTrace() {
        enqueueSourceSeparationPlaybackTraceWrite()
    }

    private suspend fun flushSourceSeparationPlaybackTraceAndWait() {
        enqueueSourceSeparationPlaybackTraceWrite()?.join()
    }

    private fun enqueueSourceSeparationPlaybackTraceWrite(): Job? =
        synchronized(sourceSeparationPlaybackTraceLock) {
            val file = sourceSeparationPlaybackTraceFile
                ?: return@synchronized sourceSeparationPlaybackTraceWriteJob
            if (sourceSeparationPlaybackTraceBuffer.isEmpty()) {
                return@synchronized sourceSeparationPlaybackTraceWriteJob
            }
            val lines = sourceSeparationPlaybackTraceBuffer.joinToString(
                separator = "\n",
                postfix = "\n",
            )
            sourceSeparationPlaybackTraceBuffer.clear()
            sourceSeparationPlaybackTraceFlushJob?.cancel()
            sourceSeparationPlaybackTraceFlushJob = null

            val previous = sourceSeparationPlaybackTraceWriteJob
            lateinit var writeJob: Job
            writeJob = serviceScope.launch(IO, start = kotlinx.coroutines.CoroutineStart.LAZY) {
                previous?.join()
                runCatching {
                    file.appendText(lines, Charsets.UTF_8)
                }.onFailure { error ->
                    Log.w(
                        TAG_SOURCE_SEPARATION_PLAYBACK,
                        "Unable to append playback gate trace",
                        error,
                    )
                }
            }
            sourceSeparationPlaybackTraceWriteJob = writeJob
            writeJob.invokeOnCompletion {
                synchronized(sourceSeparationPlaybackTraceLock) {
                    if (sourceSeparationPlaybackTraceWriteJob === writeJob) {
                        sourceSeparationPlaybackTraceWriteJob = null
                    }
                }
            }
            writeJob.start()
            writeJob
        }

    private fun sourceSeparationPlaybackTraceState(): String {
        val session = sourceSeparationPlaybackSession
        val windowWait = sourceSeparationPlaybackWindowWaitTracker.current
        val base = "requested=$sourceSeparationPlaybackRequested " +
                "processing=$sourceSeparationPlaybackIsProcessing " +
                "processingCache=${sourceSeparationPlaybackProcessingCacheKey?.take(12)} " +
                "waitEpoch=${windowWait?.epoch} " +
                "waitAnchor=${windowWait?.anchorPositionMs} " +
                "waitWindows=${windowWait?.requiredReadyWindowCount} " +
                "resumeWhenReady=$sourceSeparationPlaybackResumeWhenReady " +
                "playIntent=$sourceSeparationPlaybackPlayIntent " +
                "internalPWR=$sourceSeparationPlaybackInternalPlayWhenReady " +
                "session=${session?.songId} " +
                "gate=${session?.requiresReadinessGate} " +
                "thread=${Thread.currentThread().name}"
        if (!::player.isInitialized) {
            return "player=uninitialized $base"
        }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            return "$base player=mainThreadOnly"
        }
        return "$base " +
                "playWhenReady=${player.playWhenReady} " +
                "isPlaying=${player.isPlaying} " +
                "state=${playbackStateName(player.playbackState)} " +
                "position=${player.currentPosition} " +
                "index=${player.currentMediaItemIndex} " +
                "mediaId=${player.currentMediaItem?.mediaId}"
    }

    private fun playWhenReadyReasonName(reason: Int): String {
        return when (reason) {
            Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> "USER_REQUEST"
            Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> "AUDIO_FOCUS_LOSS"
            Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> "AUDIO_BECOMING_NOISY"
            Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE -> "REMOTE"
            Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM -> "END_OF_MEDIA_ITEM"
            else -> "UNKNOWN_$reason"
        }
    }

    private fun isManualPlayWhenReadyPauseReason(reason: Int): Boolean {
        return reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST ||
                reason == Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE
    }

    private fun mediaItemTransitionReasonName(reason: Int): String {
        return when (reason) {
            Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "REPEAT"
            Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "AUTO"
            Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "SEEK"
            Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "PLAYLIST_CHANGED"
            else -> "UNKNOWN_$reason"
        }
    }

    private fun discontinuityReasonName(reason: Int): String {
        return when (reason) {
            Player.DISCONTINUITY_REASON_AUTO_TRANSITION -> "AUTO_TRANSITION"
            Player.DISCONTINUITY_REASON_SEEK -> "SEEK"
            Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT -> "SEEK_ADJUSTMENT"
            Player.DISCONTINUITY_REASON_SKIP -> "SKIP"
            Player.DISCONTINUITY_REASON_REMOVE -> "REMOVE"
            Player.DISCONTINUITY_REASON_INTERNAL -> "INTERNAL"
            else -> "UNKNOWN_$reason"
        }
    }

    private fun playbackStateName(state: Int): String {
        return when (state) {
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> "READY"
            Player.STATE_ENDED -> "ENDED"
            else -> "UNKNOWN_$state"
        }
    }

    private fun sourceSeparationPlaybackTraceTimestamp(): String {
        return SimpleDateFormat(SOURCE_SEPARATION_TRACE_TIME_FORMAT, Locale.US).format(Date())
    }

    private var bluetoothConnectedRegistered = false
    private val bluetoothConnectedIntentFilter = IntentFilter().apply {
        addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
        addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
        addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
    }
    private val bluetoothReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            when (intent?.action) {
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                    when (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)) {
                        BluetoothA2dp.STATE_CONNECTED -> if (Preferences.isResumeOnConnect(true)) {
                            player.play()
                        }
                        BluetoothA2dp.STATE_DISCONNECTED -> if (Preferences.isPauseOnDisconnect(true)) {
                            player.pause()
                        }
                    }
                }
                BluetoothDevice.ACTION_ACL_CONNECTED ->
                    if (context.isBluetoothA2dpConnected() && Preferences.isResumeOnConnect(true)) {
                        player.play()
                    }
                BluetoothDevice.ACTION_ACL_DISCONNECTED ->
                    if (context.isBluetoothA2dpDisconnected() && Preferences.isPauseOnDisconnect(true)) {
                        player.pause()
                    }
            }
        }
    }

    private var receivedHeadsetConnected = false
    private var headsetReceiverRegistered = false
    private val headsetReceiverIntentFilter = IntentFilter(Intent.ACTION_HEADSET_PLUG)
    private val headsetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (Intent.ACTION_HEADSET_PLUG == intent.action && !isInitialStickyBroadcast) {
                when (intent.getIntExtra("state", -1)) {
                    0 -> if (Preferences.isPauseOnDisconnect(false)) {
                        player.pause()
                    }
                    // Check whether the current song is empty which means the playing queue hasn't restored yet
                    1 -> if (Preferences.isResumeOnConnect(false)) {
                        if (player.currentMediaItem != null) {
                            player.play()
                        } else {
                            receivedHeadsetConnected = true
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "PlaybackService"
        private const val TAG_SOURCE_SEPARATION_PLAYBACK = "SourceSepPlaybackGate"
        private const val PACKAGE_NAME = "com.mardous.booming"

        const val ACTION_TOGGLE_SHUFFLE = "$PACKAGE_NAME.action.ACTION_TOGGLE_SHUFFLE"
        const val ACTION_CYCLE_REPEAT = "$PACKAGE_NAME.action.ACTION_CYCLE_REPEAT"
        const val ACTION_TOGGLE_FAVORITE = "$PACKAGE_NAME.action.ACTION_TOGGLE_FAVORITE"

        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "playing_notification"

        private const val MAX_RETRY_COUNT_AFTER_ERROR = 3
        private const val WIDGET_UPDATE_DEBOUNCE = 300L
        private const val REWIND_INSTEAD_PREVIOUS_MILLIS = 5000L
        private const val SOURCE_SEPARATION_PROCESSING_RETRY_DELAY_MS = 500L
        private const val SOURCE_SEPARATION_READINESS_MONITOR_DELAY_MS = 250L
        private const val SOURCE_SEPARATION_READINESS_MONITOR_MIN_DELAY_MS = 25L
        private const val SOURCE_SEPARATION_DATA_PLANE_MONITOR_FALLBACK_DELAY_MS = 250L
        private const val SOURCE_SEPARATION_OUTPUT_UNMUTE_DELAY_MS = 120L
        private const val SOURCE_SEPARATION_OUTPUT_UNMUTE_FALLBACK_DELAY_MS = 1500L
        private const val SOURCE_SEPARATION_BLEND_FLUSH_SEEK_OFFSET_MS = 10L
        private const val SOURCE_SEPARATION_EXPECT_PROCESSING_PENDING_TIMEOUT_MS = 10_000L
        private const val SOURCE_SEPARATION_EXPECT_PROCESSING_TARGET_TIMEOUT_MS = 10 * 60_000L
        private const val SOURCE_SEPARATION_PROCESSING_LEASE_HEARTBEAT_MS = 1_000L
        private const val SOURCE_SEPARATION_BUFFERING_FGS_REFRESH_MS = 3_000L
        private const val SOURCE_SEPARATION_PROCESSING_WAKE_LOCK_REFRESH_MS = 15_000L

        private const val FOREGROUND_SERVICE_TIMEOUT = (60 * 1000) * 2L

        private val isSourceSeparationTraceFileEnabled: Boolean
            get() = BuildConfig.DEBUG
        private const val SOURCE_SEPARATION_TRACE_FLUSH_DELAY_MS = 1000L
        private const val SOURCE_SEPARATION_TRACE_FLUSH_LINE_COUNT = 80
        private const val SOURCE_SEPARATION_TRACE_TIME_FORMAT = "yyyy-MM-dd HH:mm:ss.SSS"
    }
}

private data class SourceSeparationPlaybackInternalSeekTarget(
    val mediaItemIndex: Int,
    val positionMs: Long,
)

private data class SourceSeparationPendingOutputFlush(
    val barrierId: Long,
    val reason: String,
    val outputGeneration: Long?,
)

private data class SourceSeparationPlaybackSession(
    val songId: Long,
    val sessionId: Long,
    val selectionGeneration: Long,
    val selection: SourceSeparationExecutionSelectionSnapshot,
    val cacheKey: String,
    val stemFiles: List<File>,
    val stemIds: List<String>,
    val stemCanonicalLabels: List<String>,
    var stemGains: List<Float>,
    val blendEndpointStemIds: List<String>?,
    val inputMode: InputMode,
    val stemSampleRate: Int,
    val stemChannelCount: Int,
    val preparedInputs: PreparedSourceSeparationPlaybackInputs,
    val requiresReadinessGate: Boolean,
    val runtimeSong: SourceSeparationRuntimeSong,
    val modelAwareCachePlayback: SourceSeparationModelAwareCachePlayback,
) {
    init {
        require(stemFiles.isNotEmpty()) { "Playback session requires at least one stem." }
        require(stemFiles.size == stemIds.size) {
            "Playback session stem IDs must match its files."
        }
        require(stemIds.distinct().size == stemIds.size) {
            "Playback session stem IDs must be unique."
        }
        require(stemCanonicalLabels.size == stemIds.size &&
                stemCanonicalLabels.all(String::isNotBlank)
        ) {
            "Playback session stem labels must match its stem IDs."
        }
        blendEndpointStemIds?.let { endpointIds ->
            require(endpointIds.size == 2 && endpointIds.distinct().size == endpointIds.size) {
                "Playback session blend endpoints must be two unique stem IDs."
            }
            require(endpointIds.all(stemIds::contains)) {
                "Playback session blend endpoints must belong to the stem set."
            }
        }
        if (blendEndpointStemIds == null) {
            require(stemGains.size == stemIds.size) {
                "Multi-stem playback gains must match the complete stem set."
            }
            stemGains = SourceSeparationStemGainPolicy.normalize(stemGains)
        } else {
            require(stemGains.isEmpty()) {
                "MDX blend sessions must not carry independent stem gains."
            }
        }
    }

    fun traceSummary(): String {
        return "song=$songId id=$sessionId mode=$inputMode gate=$requiresReadinessGate " +
                "cache=${modelAwareCachePlayback.manifest.cacheKey.take(12)} " +
                "stems=${stemIds.joinToString()}"
    }

    fun closeModelAwareResources() {
        modelAwareCachePlayback.close()
    }
}

private data class SourceSeparationRequestedStemMix(
    val cacheKey: String,
    val stemIds: List<String>,
    val gains: List<Float>,
    val persist: Boolean,
) {
    init {
        require(cacheKey.isNotBlank()) { "Requested stem mix cache key is empty." }
        SourceSeparationStemGainPolicy.orderedMap(stemIds, gains)
    }

    fun orderedGains(expectedStemIds: List<String>): List<Float>? {
        if (stemIds != expectedStemIds) return null
        return SourceSeparationStemGainPolicy.normalize(gains)
    }
}

private fun SourceSeparationCacheManifest.mdxBlendEndpointStemIds(): List<String>? {
    val mdxStemContract = contract.stemContract ?: return null
    return mdxStemContract.toMdxBlendEndpointStemIds().map { stemId -> stemId.value }
}


@OptIn(UnstableApi::class)
private fun musicMediaSourceFactory(
    context: Context,
    mp3IndexSeekingEnabled: Boolean,
): DefaultMediaSourceFactory {
    return DefaultMediaSourceFactory(
        context,
        DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .also { extractorsFactory ->
                if (mp3IndexSeekingEnabled) {
                    extractorsFactory.setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING)
                }
            }
    )
}

private fun SourceSeparationCacheManifest.canUseOriginalSourceSeparationClock(
    output: SourceSeparationCacheOutput,
): Boolean {
    return identity.source.sourceChannelCount == SOURCE_SEPARATION_STEM_CHANNEL_COUNT &&
            output.stems.all { stem ->
                stem.channelCount == SOURCE_SEPARATION_STEM_CHANNEL_COUNT &&
                        stem.sampleRate == output.outputSampleRate
            }
}

private fun SourceSeparationModelAwarePlayableStatus.traceName(): String {
    return when (this) {
        is SourceSeparationModelAwarePlayableStatus.Ready ->
            "Ready(${playback.manifest.state})"
        SourceSeparationModelAwarePlayableStatus.Processing -> "Processing"
        SourceSeparationModelAwarePlayableStatus.Unavailable -> "Unavailable"
    }
}

private fun SourceSeparationModelAwarePlayableStatus.closePlayback() {
    (this as? SourceSeparationModelAwarePlayableStatus.Ready)?.playback?.close()
}
