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
import android.net.Uri
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
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SilenceMediaSource
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
import com.mardous.booming.playback.processor.ReplayGainAudioProcessor
import com.mardous.booming.playback.processor.SourceSeparationMixAudioProcessor
import com.mardous.booming.playback.renderer.AlacWorkaroundCodecSelector
import com.mardous.booming.playback.renderer.BoomingMusicRenderersFactory
import com.mardous.booming.separation.SourceSeparationModelAwareEngineResult
import com.mardous.booming.separation.SourceSeparationRuntimeFacade
import com.mardous.booming.separation.SourceSeparationRuntimeSong
import com.mardous.booming.separation.SourceSeparationRuntimeSongResolution
import com.mardous.booming.separation.audio.Pcm16StereoFlacEncoder
import com.mardous.booming.separation.cache.SourceSeparationCacheDirectories
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheSourceIdentityResolver
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheHydrationResult
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheManifest
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheManifestState
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheOutput
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwareCachePlayback
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwareCacheStatus
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwareHydratedPlayback
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwarePlayableStatus
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwareReadyHorizonStatus
import com.mardous.booming.playback.processor.SourceSeparationMixAudioProcessor.InputMode
import com.mardous.booming.ui.screen.MainActivity
import com.mardous.booming.ui.screen.player.SourceSeparationForegroundWorkerCoordinator
import com.mardous.booming.ui.screen.player.SourceSeparationUiState
import com.mardous.booming.util.DEFAULT_SOURCE_SEPARATION_AUTO_START
import com.mardous.booming.util.DEFAULT_SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT
import com.mardous.booming.util.CLEAR_QUEUE_ON_COMPLETION
import com.mardous.booming.util.DEFAULT_SOURCE_SEPARATION_HYDRATED_MIXED_OUTPUT_PREROLL_MS
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
import com.mardous.booming.util.SOURCE_SEPARATION_HYDRATED_MIXED_OUTPUT_PREROLL_MS
import com.mardous.booming.util.SOURCE_SEPARATION_MIXED_OUTPUT_PREROLL_MS
import com.mardous.booming.util.SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

private const val SOURCE_SEPARATION_QUEUE_REPLACEMENT_TOKEN_KEY =
    "com.mardous.booming.source_separation.queue_replacement_token"
private const val SOURCE_SEPARATION_QUEUE_REPLACEMENT_URI_SCHEME =
    "source-separation-clock"
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
    private val sourceSeparationForegroundWorkerCoordinator:
            SourceSeparationForegroundWorkerCoordinator by inject()

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
    private var sourceSeparationPlaybackRequested = false
    private var sourceSeparationPlaybackAutoSyncOnTransition = true
    private var sourceSeparationPlaybackIsProcessing = false
    private var sourceSeparationPlaybackExpectProcessing = false
    private var sourceSeparationPlaybackResumeWhenReady = false
    private var sourceSeparationPlaybackInternalPlayWhenReady: Boolean? = null
    private var sourceSeparationPlaybackPlayIntent = false
    private var sourceSeparationPausedBlendFlushPending = false
    private val sourceSeparationPlaybackReadinessMutex = Mutex()
    private var sourceSeparationPlaybackGateJob: Job? = null
    private var sourceSeparationPlaybackReadinessMonitorJob: Job? = null
    private var sourceSeparationPlaybackReadinessMonitorSessionId: Long? = null
    private var sourceSeparationPlaybackTraceFile: File? = null
    private var sourceSeparationPlaybackTraceFlushJob: Job? = null
    private var sourceSeparationOutputMuteJob: Job? = null
    private var sourceSeparationHydrationJob: Job? = null
    private var sourceSeparationHydrationJobKey: SourceSeparationHydrationKey? = null
    private var sourceSeparationModelAwareHydrationJob: Job? = null
    private var sourceSeparationModelAwareHydrationCacheKey: String? = null
    private var sourceSeparationWarmHydration: SourceSeparationWarmHydration? = null
    private val sourceSeparationHydrationRootDirectory by lazy(LazyThreadSafetyMode.NONE) {
        SourceSeparationCacheDirectories.legacyPlaybackHydration(this)
    }
    private val sourceSeparationPlaybackTraceLock = Any()
    private val sourceSeparationPlaybackTraceBuffer = mutableListOf<String>()
    private val sourceSeparationPlaybackTraceSeq = AtomicLong()
    private var sourceSeparationPlaybackCheckSeq = 0L
    private var sourceSeparationPlaybackInternalMediaItemChangeUntilMs = 0L
    private var sourceSeparationOutputMuted = false
    private var sourceSeparationOutputWaitingForMixedOutput = false
    private var sourceSeparationOutputMuteStartedAtMs = 0L
    private var sourceSeparationPlaybackContextGeneration = 0L
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
        nm = requireNotNull(getSystemService<NotificationManager>())
        createNotificationChannel()
        prepareSourceSeparationPlaybackTrace()
        if (isSourceSeparationTraceFileEnabled) {
            sourceSeparationMixProcessor.debugTraceSink = { detail ->
                traceSourceSeparationProcessor(detail)
            }
        }
        sourceSeparationMixProcessor.mixedOutputStartedSink = {
            serviceScope.launch {
                onSourceSeparationMixedOutputStarted()
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
                    sourceSeparationAwareMediaSourceFactory(
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
                .build()
        )

        player.exoPlayer.shuffleOrder = ImprovedShuffleOrder(0, 0, Random.nextLong())
        player.setSequentialTimelineEnabled(sequentialTimeline)
        player.addListener(this)
        observeSourceSeparationForegroundWorker()
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

        persistentStorage = PersistentStorage(this, serviceScope, player) {
            player.mediaItems.map { mediaItem ->
                sourceSeparationPlaybackSession
                    ?.takeIf { it.songId.toString() == mediaItem.mediaId }
                    ?.originalMediaItem
                    ?: mediaItem
            }
        }
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
        clearSourceSeparationPlayback(restoreOriginalItem = false, broadcast = false)
        flushSourceSeparationPlaybackTrace()
        sourceSeparationPreStartJob?.cancel()
        sourceSeparationPreStartJob = null
        cancelSourceSeparationPlaybackReadinessMonitor("serviceDestroy")
        cancelSourceSeparationPcmHydrationJob(deletePartial = false)
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
        availableCommands.add(SessionCommand(Playback.NOTIFY_SOURCE_SEPARATION_CACHE_DELETED, Bundle.EMPTY))
        availableCommands.add(SessionCommand(Playback.TRACE_SOURCE_SEPARATION_PLAYBACK_MARKER, Bundle.EMPTY))
        if (BuildConfig.DEBUG) {
            availableCommands.add(
                SessionCommand(Playback.AWAIT_PLAYBACK_RESTORATION, Bundle.EMPTY)
            )
            availableCommands.add(
                SessionCommand(Playback.PLAY_SOURCE_SEPARATION_COMPLETED_CACHE, Bundle.EMPTY)
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
                        is SourceSeparationModelAwareEngineResult.AlreadyCompleted -> result.manifest
                        is SourceSeparationModelAwareEngineResult.Busy ->
                            throw IllegalStateException("The exact cache entry is busy.")
                        SourceSeparationModelAwareEngineResult.ActiveModelUnavailable ->
                            throw IllegalStateException("The resolved model became unavailable.")
                    }
                    if (preferences.getBoolean(SOURCE_SEPARATION_AUTO_FLAC_COMPRESSION, true)) {
                        sourceSeparationRuntime.promote(manifest.cacheKey)
                    }
                    sourceSeparationRuntime.cleanCompletedTemporaryFiles(manifest.cacheKey)
                    val playback = requireNotNull(
                        sourceSeparationRuntime.openCompletedCache(manifest.cacheKey),
                    )
                    playback.use {
                        SessionResult(
                            SessionResult.RESULT_SUCCESS,
                            Bundle().apply {
                                putString("vocalsFile", playback.vocalsFile.absolutePath)
                                putString("instrumentalFile", playback.instrumentalFile.absolutePath)
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
                    sourceSeparationMixProcessor.setBlend(
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

            Playback.SYNC_SOURCE_SEPARATION_PLAYBACK -> {
                val allowNewSession = args.getBoolean(
                    Playback.EXTRA_SOURCE_SEPARATION_ALLOW_NEW_SESSION,
                    sourceSeparationPlaybackAutoSyncOnTransition,
                )
                val expectProcessing = args.getBoolean(
                    Playback.EXTRA_SOURCE_SEPARATION_EXPECT_PROCESSING,
                    false,
                )
                traceSourceSeparationPlayback(
                    "command.syncPlayback",
                    "allowNewSession=$allowNewSession expectProcessing=$expectProcessing"
                )
                serviceScope.future {
                    syncSourceSeparationPlayback(
                        allowNewSession = allowNewSession,
                        expectProcessing = expectProcessing,
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

            Playback.PLAY_SOURCE_SEPARATION_COMPLETED_CACHE -> {
                val cacheKey = args.getString(Playback.EXTRA_SOURCE_SEPARATION_CACHE_KEY)
                    .orEmpty()
                serviceScope.future {
                    playSourceSeparationCompletedCache(cacheKey)
                }
            }

            Playback.TRACE_SOURCE_SEPARATION_PLAYBACK_MARKER -> {
                traceSourceSeparationPlayback(
                    "test.marker",
                    args.getString(Playback.EXTRA_SOURCE_SEPARATION_TRACE_MARKER).orEmpty()
                )
                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
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
                        allowPauseForProcessing = false,
                        resumeWhenReady = false,
                        allowNewSession = false,
                        preferCompletedCache = true,
                        expectProcessing = sourceSeparationPlaybackExpectProcessing,
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
        val isPlaying = player.isPlaying
        val activeSession = sourceSeparationPlaybackSession
        val isInternalMediaItemChange =
            reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED &&
                    isSourceSeparationInternalMediaItemChange()
        traceSourceSeparationPlayback(
            "player.onMediaItemTransition",
            "reason=${mediaItemTransitionReasonName(reason)} mediaId=${mediaItem?.mediaId} " +
                    "activeSession=${activeSession?.songId} stem=${mediaItem?.isSourceSeparationStemMediaItem()} " +
                    "internalItemChange=$isInternalMediaItemChange"
        )
        if (!isInternalMediaItemChange) {
            sourceSeparationPlaybackContextGeneration++
        }
        if (activeSession != null) {
            if (mediaItem?.mediaId == activeSession.songId.toString()) {
                sourceSeparationMixProcessor.seekTo(player.currentPosition)
            } else if (!isInternalMediaItemChange) {
                clearSourceSeparationPlayback(restoreOriginalItem = true, broadcast = false)
            } else {
                traceSourceSeparationPlayback(
                    "player.onMediaItemTransition.ignoreInternal",
                    "mediaId=${mediaItem?.mediaId} activeSession=${activeSession.songId}"
                )
            }
        }
        if (!isInternalMediaItemChange) {
            clearWarmSourceSeparationHydrationIfSongChanged(mediaItem)
        }
        if (isInternalMediaItemChange &&
            activeSession != null &&
            mediaItem?.mediaId != activeSession.songId.toString()
        ) {
            return
        }
        val shouldApplyDeferredPerSongSourceSeparationSync =
            !isInternalMediaItemChange &&
                    sourceSeparationPlaybackRequested &&
                    mediaItem != null &&
                    mediaItem.mediaId != activeSession?.songId?.toString() &&
                    !sourceSeparationPlaybackAutoSyncOnTransition
        if (!isInternalMediaItemChange &&
            sourceSeparationPlaybackRequested && mediaItem != null &&
            mediaItem.mediaId != activeSession?.songId?.toString()
        ) {
            val expectProcessingOnTransition = gateSourceSeparationPlaybackTransition(
                reason = "transition",
                wasPlaying = isPlaying,
            )
            if (sourceSeparationPlaybackAutoSyncOnTransition) {
                serviceScope.launch {
                    ensureSourceSeparationPlaybackReady(
                        showUnavailableMessage = false,
                        resumeWhenReady = sourceSeparationPlaybackResumeWhenReady,
                        expectProcessing = expectProcessingOnTransition,
                    )
                }
            } else {
                traceSourceSeparationPlayback(
                    "player.onMediaItemTransition.deferAutoSync",
                    "reason=clientBlendRequired"
                )
            }
        } else if (!isInternalMediaItemChange &&
            !sourceSeparationPlaybackRequested &&
            mediaItem?.isSourceSeparationStemMediaItem() == true
        ) {
            serviceScope.launch {
                restoreCurrentSourceSeparationStemMediaItemIfNeeded()
            }
        }

        serviceScope.launch(IO) {
            val newSong = repository.songByMediaItem(mediaItem)

            val previousSong = songPlayCountHelper.song
            val shouldBumpPlayCount = songPlayCountHelper.shouldBumpPlayCount()
            songPlayCountHelper.notifySongChanged(newSong, isPlaying)

            if (newSong != Song.emptySong) {
                val deferredPerSongBlend = if (shouldApplyDeferredPerSongSourceSeparationSync) {
                    sourceSeparationForegroundWorkerCoordinator.recordedBlendForSong(newSong)
                        ?: DEFAULT_SOURCE_SEPARATION_BLEND
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
        sourceSeparationMixProcessor.setBlend(normalizedBlend)
        updateSourceSeparationForegroundWorkerSong(song)

        if (isDefaultSourceSeparationBlend(normalizedBlend)) {
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
            sourceSeparationForegroundWorkerCoordinator.requestSong(song)
        }
        val expectProcessing = autoStartDecision.shouldStart ||
                autoStartDecision.shouldWaitForProcessingCache
        setSourceSeparationPlaybackExpectProcessing(expectProcessing)
        ensureSourceSeparationPlaybackReady(
            showUnavailableMessage = false,
            allowPauseForProcessing = true,
            resumeWhenReady = shouldResumeSourceSeparationPlaybackWhenReady(),
            allowNewSession = true,
            expectProcessing = expectProcessing,
        )
        maybePreStartNextSourceSeparation("perSongTransition")
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int
    ) {
        traceSourceSeparationPlayback(
            "player.onPositionDiscontinuity",
            "reason=${discontinuityReasonName(reason)} old=${oldPosition.positionMs} " +
                    "new=${newPosition.positionMs} oldIndex=${oldPosition.mediaItemIndex} " +
                    "newIndex=${newPosition.mediaItemIndex} currentIndex=${player.currentMediaItemIndex} " +
                    "session=${sourceSeparationPlaybackSession?.traceSummary()} " +
                    "expectProcessing=$sourceSeparationPlaybackExpectProcessing"
        )
        updateSourceSeparationForegroundWorkerPosition()
        if (reason == Player.DISCONTINUITY_REASON_REMOVE &&
            sourceSeparationPlaybackSession != null &&
            isSourceSeparationInternalMediaItemChange()
        ) {
            return
        }
        if (oldPosition.mediaItemIndex != newPosition.mediaItemIndex &&
            sourceSeparationPlaybackRequested &&
            !isSourceSeparationInternalMediaItemChange()
        ) {
            gateSourceSeparationPlaybackTransition(
                reason = "positionDiscontinuity",
                wasPlaying = player.isPlaying,
            )
        }
        if ((sourceSeparationPlaybackSession != null || sourceSeparationPlaybackIsProcessing) &&
            reason == Player.DISCONTINUITY_REASON_SEEK
        ) {
            val activeSession = sourceSeparationPlaybackSession
            if (!applySourceSeparationHydrationOnSeekIfReady(newPosition.positionMs)) {
                sourceSeparationMixProcessor.seekTo(newPosition.positionMs)
            }
            if (activeSession != null &&
                !activeSession.requiresReadinessGate &&
                !sourceSeparationPlaybackIsProcessing &&
                !sourceSeparationPlaybackExpectProcessing
            ) {
                traceSourceSeparationPlayback(
                    "check.seek.skip",
                    "reason=activeCompletedSession position=${newPosition.positionMs}"
                )
                return
            }
            serviceScope.launch {
                ensureSourceSeparationPlaybackReady(
                    showUnavailableMessage = false,
                    allowPauseForProcessing = true,
                    resumeWhenReady = sourceSeparationPlaybackResumeWhenReady || player.playWhenReady,
                    preferCompletedCache = true,
                    expectProcessing = sourceSeparationPlaybackExpectProcessing,
                )
            }
        } else if (sourceSeparationPlaybackSession != null) {
            sourceSeparationMixProcessor.seekTo(newPosition.positionMs)
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
                allowPauseForProcessing = true,
                resumeWhenReady = shouldResumeSourceSeparationPlaybackWhenReady(),
                allowNewSession = true,
                expectProcessing = expectProcessing,
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
            restoreCurrentSourceSeparationStemMediaItemIfNeeded()
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
    ): SessionResult {
        traceSourceSeparationPlayback(
            "playback.sync.start",
            "allowNewSession=$allowNewSession expectProcessing=$expectProcessing"
        )
        if (!sourceSeparationPlaybackRequested) {
            val result = sourceSeparationPlaybackResult(SessionResult.RESULT_SUCCESS)
            traceSourceSeparationPlayback("playback.sync.skip", "requested=false")
            return result
        }
        val effectiveExpectProcessing =
            expectProcessing || sourceSeparationPlaybackExpectProcessing
        val result = ensureSourceSeparationPlaybackReady(
            showUnavailableMessage = false,
            allowNewSession = allowNewSession,
            resumeWhenReady = shouldResumeSourceSeparationPlaybackWhenReady(),
            expectProcessing = effectiveExpectProcessing,
        )
        traceSourceSeparationPlayback("playback.sync.end", "result=${result.resultCode}")
        return result
    }

    private suspend fun handleSourceSeparationCacheDeleted(): SessionResult {
        traceSourceSeparationPlayback("playback.cacheDeleted.start")
        val result = ensureSourceSeparationPlaybackReady(
            showUnavailableMessage = false,
            allowPauseForProcessing = false,
            resumeWhenReady = shouldResumeSourceSeparationPlaybackWhenReady(),
            allowNewSession = false,
            preferCompletedCache = true,
            expectProcessing = false,
        )
        traceSourceSeparationPlayback(
            "playback.cacheDeleted.end",
            "result=${result.resultCode}"
        )
        return result
    }

    private suspend fun playSourceSeparationCompletedCache(cacheKey: String): SessionResult {
        return sourceSeparationPlaybackReadinessMutex.withLock {
            playSourceSeparationCompletedCacheLocked(cacheKey)
        }
    }

    private suspend fun playSourceSeparationCompletedCacheLocked(
        cacheKey: String,
    ): SessionResult {
        if (!MODEL_AWARE_CACHE_KEY_PATTERN.matches(cacheKey)) {
            return sourceSeparationPlaybackUnavailable(
                showMessage = true,
                resultCode = SessionError.ERROR_BAD_VALUE,
                message = getString(R.string.source_separation_cache_identity_invalid),
            )
        }
        val playback = withContext(IO) {
            sourceSeparationRuntime.openCompletedCache(cacheKey)
        } ?: return sourceSeparationPlaybackUnavailable(
            showMessage = true,
            resultCode = SessionError.ERROR_INVALID_STATE,
            message = getString(R.string.source_separation_playback_cache_not_found),
        )

        var adopted = false
        var hydratedPlayback: SourceSeparationModelAwareHydratedPlayback? = null
        return try {
            val song = withContext(IO) {
                repository.songById(playback.manifest.song.songId)
            }
            if (song == Song.emptySong) {
                return sourceSeparationPlaybackUnavailable(
                    showMessage = true,
                    resultCode = SessionError.ERROR_INVALID_STATE,
                    message = getString(R.string.source_separation_playback_no_song),
                )
            }
            val sourceIdentity = withContext(IO) {
                SourceSeparationCacheSourceIdentityResolver(applicationContext).resolve(
                    uri = Uri.parse(song.uri.toString()),
                    shouldCancel = { false },
                ).identity
            }
            if (sourceIdentity != playback.manifest.identity.source) {
                return sourceSeparationPlaybackUnavailable(
                    showMessage = true,
                    resultCode = SessionError.ERROR_INVALID_STATE,
                    message = getString(R.string.source_separation_cache_source_changed),
                )
            }
            val blend = withContext(IO) {
                sourceSeparationRuntime.readBlend(
                    playback.manifest.identity,
                )
            } ?: sourceSeparationMixProcessor.blend
            hydratedPlayback = withContext(IO) {
                sourceSeparationRuntime.openHydratedCache(cacheKey)
            }
            val result = applySourceSeparationCompletedCachePlayback(
                song = song,
                playback = playback,
                hydratedPlayback = hydratedPlayback,
                blend = blend,
            )
            adopted = result.resultCode == SessionResult.RESULT_SUCCESS
            result
        } catch (error: Throwable) {
            traceSourceSeparationPlayback(
                "modelAwareCache.play.failed",
                "cache=${cacheKey.take(12)} error=${error.message ?: error::class.java.name}",
            )
            sourceSeparationPlaybackUnavailable(
                showMessage = true,
                resultCode = SessionError.ERROR_UNKNOWN,
                message = getString(R.string.source_separation_playback_unavailable),
            )
        } finally {
            if (!adopted) {
                hydratedPlayback?.close()
                playback.close()
            }
        }
    }

    private fun applySourceSeparationCompletedCachePlayback(
        song: Song,
        playback: SourceSeparationModelAwareCachePlayback,
        hydratedPlayback: SourceSeparationModelAwareHydratedPlayback?,
        blend: Float,
    ): SessionResult {
        val output = playback.manifest.output
            ?: return sourceSeparationPlaybackUnavailable(
                showMessage = true,
                resultCode = SessionError.ERROR_INVALID_STATE,
                message = getString(R.string.source_separation_playback_cache_not_found),
            )
        val channelCount = output.stems.map { it.channelCount }.distinct().singleOrNull()
        if (channelCount != SOURCE_SEPARATION_STEM_CHANNEL_COUNT) {
            return sourceSeparationPlaybackUnavailable(
                showMessage = true,
                resultCode = SessionError.ERROR_INVALID_STATE,
                message = getString(R.string.source_separation_playback_unavailable),
            )
        }

        val targetMediaId = song.id.toString()
        val wasCurrentSong = player.currentMediaItem?.mediaId == targetMediaId
        val startPositionMs = if (wasCurrentSong) {
            player.currentPosition.coerceAtLeast(0L)
        } else {
            0L
        }
        val originalRequested = sourceSeparationPlaybackRequested
        sourceSeparationPlaybackRequested = false
        return try {
            clearSourceSeparationPlayback(restoreOriginalItem = true, broadcast = false)
            val existingIndex = (0 until player.mediaItemCount)
                .firstOrNull { index -> player.getMediaItemAt(index).mediaId == targetMediaId }
            if (existingIndex == null) {
                player.setMediaItem(song.toMediaItem(targetMediaId), startPositionMs)
            } else if (existingIndex != player.currentMediaItemIndex) {
                player.seekToDefaultPosition(existingIndex)
            } else if (wasCurrentSong) {
                player.seekTo(existingIndex, startPositionMs)
            }
            val index = player.currentMediaItemIndex
            if (index == C.INDEX_UNSET || player.currentMediaItem?.mediaId != targetMediaId) {
                return sourceSeparationPlaybackUnavailable(
                    showMessage = true,
                    resultCode = SessionError.ERROR_INVALID_STATE,
                    message = getString(R.string.source_separation_playback_no_song),
                )
            }

            val session = SourceSeparationPlaybackSession(
                songId = song.id,
                sessionId = ++sourceSeparationPlaybackCheckSeq,
                mediaItemIndex = index,
                originalMediaItem = song.toMediaItem(targetMediaId),
                queueReplacementToken = null,
                queueReplacementFile = null,
                queueReplacementDurationMs =
                    output.outputFrameCount.toLong() * 1_000L / output.outputSampleRate,
                vocalsFile = hydratedPlayback?.vocalsPcmFile ?: playback.vocalsFile,
                instrumentalFile =
                    hydratedPlayback?.instrumentalPcmFile ?: playback.instrumentalFile,
                cacheVocalsFile = playback.vocalsFile,
                cacheInstrumentalFile = playback.instrumentalFile,
                inputMode = InputMode.OriginalSource,
                stemSampleRate = output.outputSampleRate,
                stemChannelCount = channelCount,
                requiresReadinessGate = false,
                hydratedCacheDir = hydratedPlayback?.vocalsPcmFile?.parentFile,
                modelAwareCachePlayback = playback,
                modelAwareHydratedPlayback = hydratedPlayback,
            )
            pauseSourceSeparationOutputForSwitch(
                reason = "modelAwareCache",
                waitForMixedOutput = true,
            )
            sourceSeparationPlaybackIsProcessing = false
            sourceSeparationPlaybackResumeWhenReady = false
            setSourceSeparationPlaybackExpectProcessing(false)
            sourceSeparationMixProcessor.setBlend(blend)
            sourceSeparationPlaybackSession = session
            enableSourceSeparationMixProcessor(session, startPositionMs)
            sourceSeparationMixProcessor.seekTo(startPositionMs)
            player.prepare()
            player.play()
            resumeSourceSeparationOutputAfterSwitch(
                reason = "modelAwareCache",
                resume = true,
            )
            maybeStartSourceSeparationPcmHydration(session)
            broadcastSourceSeparationPlaybackChanged()
            updateSourceSeparationProcessingLease("modelAwareCache.ready")
            traceSourceSeparationPlayback(
                "modelAwareCache.play.ready",
                "cache=${playback.manifest.cacheKey.take(12)} songId=${song.id} " +
                    "format=${playback.vocalsFile.extension} position=$startPositionMs",
            )
            sourceSeparationPlaybackResult(SessionResult.RESULT_SUCCESS)
        } catch (error: Throwable) {
            if (sourceSeparationPlaybackSession?.modelAwareCachePlayback === playback) {
                clearSourceSeparationPlayback(
                    restoreOriginalItem = false,
                    broadcast = false,
                )
            }
            restoreSourceSeparationOutputVolume("modelAwareCache.failed")
            throw error
        } finally {
            sourceSeparationPlaybackRequested = originalRequested
        }
    }

    private suspend fun ensureSourceSeparationPlaybackReady(
        showUnavailableMessage: Boolean = true,
        allowPauseForProcessing: Boolean = true,
        resumeWhenReady: Boolean = sourceSeparationPlaybackResumeWhenReady,
        allowNewSession: Boolean = true,
        preferCompletedCache: Boolean = false,
        expectProcessing: Boolean = false,
    ): SessionResult {
        traceSourceSeparationPlayback(
            "check.request",
            "showMessage=$showUnavailableMessage allowPause=$allowPauseForProcessing " +
                    "resumeWhenReady=$resumeWhenReady allowNewSession=$allowNewSession " +
                    "preferCompletedCache=$preferCompletedCache expectProcessing=$expectProcessing"
        )
        return sourceSeparationPlaybackReadinessMutex.withLock {
            val checkId = ++sourceSeparationPlaybackCheckSeq
            ensureSourceSeparationPlaybackReadyLocked(
                checkId = checkId,
                showUnavailableMessage = showUnavailableMessage,
                allowPauseForProcessing = allowPauseForProcessing,
                resumeWhenReady = resumeWhenReady,
                allowNewSession = allowNewSession,
                preferCompletedCache = preferCompletedCache,
                expectProcessing = expectProcessing,
            )
        }
    }

    private suspend fun ensureSourceSeparationPlaybackReadyLocked(
        checkId: Long,
        showUnavailableMessage: Boolean,
        allowPauseForProcessing: Boolean,
        resumeWhenReady: Boolean,
        allowNewSession: Boolean,
        preferCompletedCache: Boolean,
        expectProcessing: Boolean,
    ): SessionResult {
        traceSourceSeparationPlayback(
            "check.start",
            "id=$checkId showMessage=$showUnavailableMessage allowPause=$allowPauseForProcessing " +
                    "resumeWhenReady=$resumeWhenReady allowNewSession=$allowNewSession " +
                    "preferCompletedCache=$preferCompletedCache expectProcessing=$expectProcessing"
        )
        if (!sourceSeparationPlaybackRequested) {
            setSourceSeparationPlaybackExpectProcessing(false)
            clearSourceSeparationPlaybackProcessing()
            traceSourceSeparationPlayback("check.skip", "id=$checkId requested=false")
            return sourceSeparationPlaybackResult(SessionResult.RESULT_SUCCESS)
        }
        val effectiveExpectProcessing =
            expectProcessing || sourceSeparationPlaybackExpectProcessing
        val waitingForExpectedProcessingTooLong =
            effectiveExpectProcessing &&
                    sourceSeparationPlaybackExpectProcessingStartedAtMs > 0L &&
                    SystemClock.elapsedRealtime() -
                    sourceSeparationPlaybackExpectProcessingStartedAtMs >
                    SOURCE_SEPARATION_EXPECT_PROCESSING_TIMEOUT_MS
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

        val activeSession = sourceSeparationPlaybackSession
        if (activeSession?.isExplicitModelAwareCachePlayback == true &&
            activeSession.songId == song.id
        ) {
            val filesReady = activeSession.vocalsFile.isFile &&
                activeSession.instrumentalFile.isFile
            return if (filesReady) {
                setSourceSeparationPlaybackExpectProcessing(false)
                sourceSeparationPlaybackIsProcessing = false
                sourceSeparationPlaybackResumeWhenReady = false
                broadcastSourceSeparationPlaybackChanged()
                sourceSeparationPlaybackResult(SessionResult.RESULT_SUCCESS)
            } else {
                clearSourceSeparationPlayback(restoreOriginalItem = true)
                sourceSeparationPlaybackUnavailable(
                    showMessage = showUnavailableMessage,
                    resultCode = SessionError.ERROR_INVALID_STATE,
                    message = getString(R.string.source_separation_playback_stem_files_missing),
                )
            }
        }
        activeSession
            ?.takeIf { it.songId == song.id && it.runtimeSong != null }
            ?.let { session ->
                return checkActiveSourceSeparationRuntimeSession(
                    checkId = checkId,
                    contextGeneration = contextGeneration,
                    mediaItem = mediaItem,
                    mediaItemIndex = mediaItemIndex,
                    song = song,
                    session = session,
                    preferCompletedCache = preferCompletedCache,
                    allowPauseForProcessing = allowPauseForProcessing,
                    resumeWhenReady = resumeWhenReady,
                    showUnavailableMessage = showUnavailableMessage,
                )
            }

        if (!allowNewSession) {
            traceSourceSeparationPlayback(
                "check.newSession.skip",
                "id=$checkId reason=clientBlendRequired songId=${song.id}"
            )
            return sourceSeparationPlaybackResult(SessionResult.RESULT_SUCCESS)
        }

        val positionMs = player.currentPosition.coerceAtLeast(0)
        val runtimeSong = when (val resolution = withContext(IO) {
            sourceSeparationRuntime.resolve(song)
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
        val status = withContext(IO) {
            runCatching {
                sourceSeparationRuntime.playableStatus(
                    song = runtimeSong,
                    playbackPositionMs = positionMs,
                    readyWindowCount = sourceSeparationPlaybackReadyWindowCount,
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
        traceSourceSeparationPlayback(
            "check.newSession.status",
            "id=$checkId songId=${song.id} cache=${runtimeSong.cacheKey.take(12)} " +
                    "position=$positionMs status=${status.traceName()}"
        )
        if (!sourceSeparationPlaybackRequested) {
            status.closePlayback()
            clearSourceSeparationPlaybackProcessing()
            traceSourceSeparationPlayback("check.skip", "id=$checkId requested=false after new status")
            return sourceSeparationPlaybackResult(SessionResult.RESULT_SUCCESS)
        }
        if (status == SourceSeparationModelAwarePlayableStatus.Processing) {
            traceSourceSeparationPlayback("check.newSession.processing", "id=$checkId")
            return waitForSourceSeparationPlayback(
                source = "newSession",
                restoreOriginalItem = false,
                allowPause = allowPauseForProcessing,
                resumeWhenReady = resumeWhenReady,
                showMessage = showUnavailableMessage,
            )
        }
        if (shouldWaitForExpectedProcessing &&
            status == SourceSeparationModelAwarePlayableStatus.Unavailable
        ) {
            traceSourceSeparationPlayback("check.newSession.expectProcessing", "id=$checkId")
            return waitForSourceSeparationPlayback(
                source = "newSession.expectProcessing",
                restoreOriginalItem = false,
                allowPause = allowPauseForProcessing,
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
        var hydratedPlayback: SourceSeparationModelAwareHydratedPlayback? = null
        var adopted = false
        try {
            val manifest = playback.manifest
            val output = manifest.output
                ?: return sourceSeparationPlaybackUnavailable(
                    showMessage = showUnavailableMessage,
                    resultCode = SessionError.ERROR_INVALID_STATE,
                    message = getString(R.string.source_separation_playback_cache_not_found),
                )
            val vocalsFile = playback.vocalsFile
            val instrumentalFile = playback.instrumentalFile
            if (!vocalsFile.isFile || !instrumentalFile.isFile) {
                traceSourceSeparationPlayback(
                    "check.newSession.missingFiles",
                    "id=$checkId vocals=${vocalsFile.isFile} instrumental=${instrumentalFile.isFile}"
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
            val shouldPlayAfterSwitch = resumeWhenReady || playWhenReady
            val originalMediaItem = song.toMediaItem(mediaItem.mediaId)
            val isRunningCache = manifest.state == SourceSeparationCacheManifestState.Running
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
            hydratedPlayback = withContext(IO) {
                runCatching {
                    sourceSeparationRuntime.openHydratedCache(manifest.cacheKey)
                }.onFailure { error ->
                    traceSourceSeparationPlayback(
                        "check.newSession.hydrationOpenFailed",
                        "id=$checkId cache=${manifest.cacheKey.take(12)} " +
                                "error=${error.message ?: error::class.java.name}",
                    )
                }.getOrNull()
            }
            val queueReplacementToken: String? = null
            val queueReplacementFile: File? = null
            val playbackMediaItem = originalMediaItem

            val session = SourceSeparationPlaybackSession(
                songId = song.id,
                sessionId = checkId,
                mediaItemIndex = index,
                originalMediaItem = originalMediaItem,
                queueReplacementToken = queueReplacementToken,
                queueReplacementFile = queueReplacementFile,
                queueReplacementDurationMs = output.durationMsForPlaybackClock(),
                vocalsFile = hydratedPlayback?.vocalsPcmFile ?: vocalsFile,
                instrumentalFile = hydratedPlayback?.instrumentalPcmFile ?: instrumentalFile,
                cacheVocalsFile = vocalsFile,
                cacheInstrumentalFile = instrumentalFile,
                inputMode = InputMode.OriginalSource,
                stemSampleRate = output.outputSampleRate,
                stemChannelCount = channelCount,
                requiresReadinessGate = isRunningCache,
                hydratedCacheDir = hydratedPlayback?.vocalsPcmFile?.parentFile,
                runtimeSong = runtimeSong,
                modelAwareCachePlayback = playback,
                modelAwareHydratedPlayback = hydratedPlayback,
            )
            traceSourceSeparationPlayback(
                "check.newSession.applyStem",
                "id=$checkId index=$index position=$positionMs resumeWhenReady=$resumeWhenReady " +
                        "previousPlayWhenReady=$playWhenReady manifestState=${manifest.state} " +
                        "clock=original " +
                        "hydrated=${hydratedPlayback != null}"
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
            val switchPositionMs = if (session.replacesQueueMediaItem) {
                positionMs
            } else {
                player.currentPosition.coerceAtLeast(0)
            }
            clearSourceSeparationPlayback(restoreOriginalItem = false, broadcast = false)
            sourceSeparationPlaybackIsProcessing = false
            sourceSeparationPlaybackResumeWhenReady = false
            setSourceSeparationPlaybackExpectProcessing(false)
            sourceSeparationPlaybackSession = session
            enableSourceSeparationMixProcessor(session, switchPositionMs)
            updateSourceSeparationPlaybackReadinessMonitor(session)
            withSourceSeparationInternalMediaItemChange {
                if (session.replacesQueueMediaItem) {
                    player.replaceMediaItem(index, playbackMediaItem)
                    player.seekTo(index, switchPositionMs)
                    player.prepare()
                    setSourceSeparationPlayWhenReady(false)
                }
            }
            traceSourceSeparationPlayback(
                "check.newSession.afterPrepare",
                "id=$checkId position=$switchPositionMs shouldPlayAfterSwitch=$shouldPlayAfterSwitch " +
                        "resumeAfterSwitch=$resumeAfterSwitch replace=${session.replacesQueueMediaItem}"
            )
            sourceSeparationMixProcessor.seekTo(switchPositionMs)
            resumeSourceSeparationOutputAfterSwitch("newSession", shouldPlayAfterSwitch || resumeAfterSwitch)
            maybeStartSourceSeparationPcmHydration(session)

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
                hydratedPlayback?.close()
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
        allowPauseForProcessing: Boolean,
        resumeWhenReady: Boolean,
        showUnavailableMessage: Boolean,
    ): SessionResult {
        val runtimeSong = requireNotNull(session.runtimeSong)
        val positionMs = player.currentPosition.coerceAtLeast(0)
        val status = withContext(IO) {
            runCatching {
                sourceSeparationRuntime.playableStatus(
                    song = runtimeSong,
                    playbackPositionMs = positionMs,
                    readyWindowCount = sourceSeparationPlaybackReadyWindowCount,
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
        traceSourceSeparationPlayback(
            "check.activeSession.status",
            "id=$checkId songId=${song.id} cache=${runtimeSong.cacheKey.take(12)} " +
                    "position=$positionMs status=${status.traceName()}",
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
                            resumeWhenReady = resumeWhenReady,
                            showUnavailableMessage = showUnavailableMessage,
                        )
                        adopted = sourceSeparationPlaybackSession
                            ?.modelAwareCachePlayback === playback
                        return result
                    }
                    val wasProcessing = sourceSeparationPlaybackIsProcessing
                    val resumeAfterProcessing = sourceSeparationPlaybackResumeWhenReady
                    sourceSeparationPlaybackIsProcessing = false
                    sourceSeparationPlaybackResumeWhenReady = false
                    val updatedSession = session.copy(
                        requiresReadinessGate = playback.manifest.state ==
                            SourceSeparationCacheManifestState.Running,
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
                traceSourceSeparationPlayback("check.activeSession.processing", "id=$checkId")
                waitForSourceSeparationPlayback(
                    source = "activeSession",
                    restoreOriginalItem = false,
                    allowPause = allowPauseForProcessing,
                    resumeWhenReady = resumeWhenReady,
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
                session.cacheVocalsFile != playback.vocalsFile ||
                session.cacheInstrumentalFile != playback.instrumentalFile
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
        val vocalsFile = playback.vocalsFile
        val instrumentalFile = playback.instrumentalFile
        traceSourceSeparationPlayback(
            "check.activeSession.upgradeCompleted.start",
            "id=$checkId songId=${song.id} active=${activeSession.traceSummary()} " +
                    "position=$positionMs outputFormat=${vocalsFile.extension} " +
                    "vocalsExt=${vocalsFile.extension} instrumentalExt=${instrumentalFile.extension} " +
                    "vocalsBytes=${vocalsFile.length()} instrumentalBytes=${instrumentalFile.length()}"
        )
        if (!vocalsFile.isFile || !instrumentalFile.isFile) {
            traceSourceSeparationPlayback(
                "check.activeSession.completedFilesMissing",
                "id=$checkId vocals=${vocalsFile.isFile} instrumental=${instrumentalFile.isFile}"
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
        val originalMediaItem = activeSession.originalMediaItem
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
        val hydratedPlayback = withContext(IO) {
            runCatching {
                sourceSeparationRuntime.openHydratedCache(manifest.cacheKey)
            }.onFailure { error ->
                traceSourceSeparationPlayback(
                    "check.activeSession.hydrationOpenFailed",
                    "id=$checkId cache=${manifest.cacheKey.take(12)} " +
                            "error=${error.message ?: error::class.java.name}",
                )
            }.getOrNull()
        }
        val queueReplacementToken: String? = null
        val queueReplacementFile: File? = null
        val playbackMediaItem = originalMediaItem
        val session = SourceSeparationPlaybackSession(
            songId = song.id,
            sessionId = checkId,
            mediaItemIndex = index,
            originalMediaItem = originalMediaItem,
            queueReplacementToken = queueReplacementToken,
            queueReplacementFile = queueReplacementFile,
            queueReplacementDurationMs = output.durationMsForPlaybackClock(),
            vocalsFile = hydratedPlayback?.vocalsPcmFile ?: vocalsFile,
            instrumentalFile = hydratedPlayback?.instrumentalPcmFile ?: instrumentalFile,
            cacheVocalsFile = vocalsFile,
            cacheInstrumentalFile = instrumentalFile,
            inputMode = InputMode.OriginalSource,
            stemSampleRate = output.outputSampleRate,
            stemChannelCount = channelCount,
            requiresReadinessGate = false,
            hydratedCacheDir = hydratedPlayback?.vocalsPcmFile?.parentFile,
            runtimeSong = activeSession.runtimeSong,
            modelAwareCachePlayback = playback,
            modelAwareHydratedPlayback = hydratedPlayback,
        )
        traceSourceSeparationPlayback(
            "check.activeSession.upgradeCompleted",
            "id=$checkId index=$index position=$positionMs shouldPlayAfterSwitch=$shouldPlayAfterSwitch " +
                    "clock=original " +
                    "hydrated=${hydratedPlayback != null}"
        )
        var adopted = false
        try {
            val resumeAfterSwitch = pauseSourceSeparationOutputForSwitch(
                reason = "completedCacheUpgrade",
                waitForMixedOutput = true,
            ) || resumeWhenReady
            val switchPositionMs = if (session.replacesQueueMediaItem) {
                positionMs
            } else {
                player.currentPosition.coerceAtLeast(0)
            }
            sourceSeparationPlaybackIsProcessing = false
            sourceSeparationPlaybackResumeWhenReady = false
            cancelSourceSeparationModelAwareHydrationJob()
            sourceSeparationPlaybackSession = session
            enableSourceSeparationMixProcessor(session, switchPositionMs)
            updateSourceSeparationPlaybackReadinessMonitor(session)
            withSourceSeparationInternalMediaItemChange {
                if (session.replacesQueueMediaItem) {
                    player.replaceMediaItem(index, playbackMediaItem)
                    player.seekTo(index, switchPositionMs)
                    player.prepare()
                    setSourceSeparationPlayWhenReady(false)
                }
            }
            sourceSeparationMixProcessor.seekTo(switchPositionMs)
            resumeSourceSeparationOutputAfterSwitch(
                reason = "completedCacheUpgrade",
                resume = shouldPlayAfterSwitch || resumeAfterSwitch,
            )
            maybeStartSourceSeparationPcmHydration(session)
            broadcastSourceSeparationPlaybackChanged()
            cleanupCompletedSourceSeparationTemporaryDirs()
            sourceSeparationPlaybackGateJob?.cancel()
            sourceSeparationPlaybackGateJob = null
            updateSourceSeparationProcessingLease("completedCacheUpgrade.ready")
            traceSourceSeparationPlayback(
                "check.end",
                "id=$checkId result=success completedCacheUpgrade",
            )
            activeSession.closeModelAwareResources()
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
            if (!adopted) hydratedPlayback?.close()
        }
    }

    private fun setSourceSeparationBlend(blend: Float, persist: Boolean): SessionResult {
        sourceSeparationMixProcessor.setBlend(blend)
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
        maybePreStartNextSourceSeparation("blendChanged")
        return sourceSeparationPlaybackResult(SessionResult.RESULT_SUCCESS)
    }

    private fun observeSourceSeparationForegroundWorker() {
        serviceScope.launch {
            sourceSeparationForegroundWorkerCoordinator.workerStateFlow.collect { state ->
                if (state is SourceSeparationUiState.Completed) {
                    maybePreStartNextSourceSeparation("workerCompleted:${state.songId}")
                }
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
                        val resolution = sourceSeparationRuntime.resolve(currentSong)
                    ) {
                        is SourceSeparationRuntimeSongResolution.Ready -> resolution.song
                        is SourceSeparationRuntimeSongResolution.Unavailable -> return@runCatching false
                    }
                    sourceSeparationRuntime.cacheStatus(currentRuntimeSong) is
                            SourceSeparationModelAwareCacheStatus.Completed
                }.getOrDefault(false)
                if (!currentCacheCompleted) return@withContext null

                val nextNeedsSeparatedOutput = if (autoSyncOnTransition) {
                    !isDefaultSourceSeparationBlend(currentBlend)
                } else {
                    sourceSeparationForegroundWorkerCoordinator
                        .recordedBlendForSong(nextSong)
                        ?.let { blend -> !isDefaultSourceSeparationBlend(blend) }
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
        val index = player.currentMediaItemIndex
        if (index != C.INDEX_UNSET) {
            player.seekTo(index, positionMs)
        } else {
            player.seekTo(positionMs)
        }
        resumeSourceSeparationOutputAfterSwitch(
            reason = "realignAfterProcessing",
            resume = resumeAfterSwitch || resumeWhenReady,
        )
    }

    private fun enableSourceSeparationMixProcessor(
        session: SourceSeparationPlaybackSession,
        positionMs: Long,
    ) {
        traceSourceSeparationPlayback(
            "processor.enable.request",
            "songId=${session.songId} position=$positionMs mode=${session.inputMode} " +
                    "stemRate=${session.stemSampleRate} gate=${session.requiresReadinessGate}"
        )
        sourceSeparationMixProcessor.enable(
            vocalsFile = session.vocalsFile,
            instrumentalFile = if (session.inputMode == InputMode.OriginalSource) {
                session.instrumentalFile
            } else {
                null
            },
            positionMs = positionMs,
            initialBlend = sourceSeparationMixProcessor.blend,
            inputMode = session.inputMode,
            stemSampleRate = session.stemSampleRate,
            stemChannelCount = session.stemChannelCount,
            mixedOutputReadyPrerollMs = if (session.usesHydratedPcm) {
                sourceSeparationHydratedMixedOutputPrerollMs
            } else {
                sourceSeparationMixedOutputPrerollMs
            },
        )
        traceSourceSeparationPlayback("processor.enable.done", "songId=${session.songId}")
    }

    private val sourceSeparationMixedOutputPrerollMs: Long
        get() = preferences.getLong(
            SOURCE_SEPARATION_MIXED_OUTPUT_PREROLL_MS,
            DEFAULT_SOURCE_SEPARATION_MIXED_OUTPUT_PREROLL_MS,
        ).coerceIn(0L, MAX_SOURCE_SEPARATION_MIXED_OUTPUT_PREROLL_MS)

    private val sourceSeparationHydratedMixedOutputPrerollMs: Long
        get() = preferences.getLong(
            SOURCE_SEPARATION_HYDRATED_MIXED_OUTPUT_PREROLL_MS,
            DEFAULT_SOURCE_SEPARATION_HYDRATED_MIXED_OUTPUT_PREROLL_MS,
        ).coerceIn(0L, MAX_SOURCE_SEPARATION_MIXED_OUTPUT_PREROLL_MS)

    private val sourceSeparationPlaybackReadyWindowCount: Int
        get() = preferences.getInt(
            SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT,
            DEFAULT_SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT,
        ).coerceIn(
            MIN_SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT,
            MAX_SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT,
        )

    private fun maybeStartSourceSeparationPcmHydration(session: SourceSeparationPlaybackSession) {
        if (session.modelAwareCachePlayback != null) {
            maybeStartSourceSeparationModelAwareHydration(session)
            return
        }
        if (session.requiresReadinessGate || session.usesHydratedPcm || session.hasPendingHydration) {
            traceSourceSeparationPlayback(
                "hydration.skip",
                "songId=${session.songId} reason=gateOrAlreadyHydrated gate=${session.requiresReadinessGate} " +
                        "hydrated=${session.usesHydratedPcm} pending=${session.hasPendingHydration}"
            )
            return
        }
        if (!session.cacheVocalsFile.extension.equals("flac", ignoreCase = true) ||
            !session.cacheInstrumentalFile.extension.equals("flac", ignoreCase = true)
        ) {
            traceSourceSeparationPlayback(
                "hydration.skip",
                "songId=${session.songId} reason=notFlac vocals=${session.cacheVocalsFile.extension} " +
                        "instrumental=${session.cacheInstrumentalFile.extension}"
            )
            return
        }
        val hydrationKey = SourceSeparationHydrationKey.forSession(session)
        val warmHydration = findWarmSourceSeparationHydration(
            songId = session.songId,
            vocalsFile = session.cacheVocalsFile,
            instrumentalFile = session.cacheInstrumentalFile,
        )
        if (warmHydration != null) {
            traceSourceSeparationPlayback(
                "hydration.skip",
                "songId=${session.songId} reason=warmReady"
            )
            return
        }
        if (sourceSeparationHydrationJob?.isActive == true &&
            sourceSeparationHydrationJobKey == hydrationKey
        ) {
            traceSourceSeparationPlayback(
                "hydration.skip",
                "songId=${session.songId} reason=jobAlreadyRunning"
            )
            return
        }

        cancelSourceSeparationPcmHydrationJob(deletePartial = true)
        sourceSeparationHydrationJobKey = hydrationKey
        sourceSeparationHydrationJob = serviceScope.launch(IO) {
            val activeJob = coroutineContext[Job]
            val hydrationDir = sourceSeparationHydrationDir(hydrationKey)
            runCatching {
                delay(SOURCE_SEPARATION_HYDRATION_START_DELAY_MS)
                ensureActive()
                hydrationDir.deleteRecursively()
                hydrationDir.mkdirs()
                val vocalsPcm = File(hydrationDir, "vocals.pcm")
                val instrumentalPcm = File(hydrationDir, "instrumental.pcm")
                traceSourceSeparationPlayback(
                    "hydration.start",
                    "songId=${session.songId} session=${session.sessionId} dir=${hydrationDir.absolutePath}"
                )
                decodeFlacStemToPcmFile(
                    flacFile = session.cacheVocalsFile,
                    pcmFile = vocalsPcm,
                    shouldCancel = { activeJob?.isActive != true },
                )
                ensureActive()
                decodeFlacStemToPcmFile(
                    flacFile = session.cacheInstrumentalFile,
                    pcmFile = instrumentalPcm,
                    shouldCancel = { activeJob?.isActive != true },
                )
                ensureActive()
                writeSourceSeparationHydrationReadyMarker(hydrationKey, hydrationDir)
                withContext(Main) {
                    markSourceSeparationHydrationReadyIfCurrent(
                        session = session,
                        hydrationKey = hydrationKey,
                        vocalsPcm = vocalsPcm,
                        instrumentalPcm = instrumentalPcm,
                        hydrationDir = hydrationDir,
                    )
                }
            }.onFailure { error ->
                val canceled = error is kotlinx.coroutines.CancellationException
                if (!canceled) {
                    hydrationDir.deleteRecursively()
                }
                if (error is kotlinx.coroutines.CancellationException) {
                    traceSourceSeparationPlayback(
                        "hydration.canceled",
                        "songId=${session.songId} session=${session.sessionId}"
                    )
                } else {
                    traceSourceSeparationPlayback(
                        "hydration.failed",
                        "songId=${session.songId} session=${session.sessionId} " +
                                "error=${error.message ?: error::class.java.name}"
                    )
                }
            }.also {
                withContext(Main) {
                    if (sourceSeparationHydrationJobKey == hydrationKey) {
                        sourceSeparationHydrationJob = null
                        sourceSeparationHydrationJobKey = null
                    }
                }
            }
        }
    }

    private fun maybeStartSourceSeparationModelAwareHydration(
        session: SourceSeparationPlaybackSession,
    ) {
        if (session.modelAwareHydratedPlayback != null ||
            session.pendingModelAwareHydratedPlayback != null
        ) {
            traceSourceSeparationPlayback(
                "modelAwareHydration.skip",
                "songId=${session.songId} reason=alreadyHydrated",
            )
            return
        }
        if (!session.cacheVocalsFile.extension.equals("flac", ignoreCase = true) ||
            !session.cacheInstrumentalFile.extension.equals("flac", ignoreCase = true)
        ) {
            traceSourceSeparationPlayback(
                "modelAwareHydration.skip",
                "songId=${session.songId} reason=notFlac",
            )
            return
        }
        val modelAwarePlayback = session.modelAwareCachePlayback ?: return
        val cacheKey = modelAwarePlayback.manifest.cacheKey
        if (sourceSeparationModelAwareHydrationJob?.isActive == true &&
            sourceSeparationModelAwareHydrationCacheKey == cacheKey
        ) {
            return
        }
        cancelSourceSeparationModelAwareHydrationJob()
        sourceSeparationModelAwareHydrationCacheKey = cacheKey
        sourceSeparationModelAwareHydrationJob = serviceScope.launch(IO) {
            val activeJob = coroutineContext[Job]
            var hydratedPlayback: SourceSeparationModelAwareHydratedPlayback? = null
            var retained = false
            try {
                delay(SOURCE_SEPARATION_HYDRATION_START_DELAY_MS)
                ensureActive()
                when (val result = sourceSeparationRuntime.hydrate(
                    cacheKey = cacheKey,
                    shouldCancel = { activeJob?.isActive != true },
                )) {
                    is SourceSeparationCacheHydrationResult.Completed,
                    is SourceSeparationCacheHydrationResult.AlreadyHydrated -> {
                        hydratedPlayback = sourceSeparationRuntime.openHydratedCache(cacheKey)
                        if (hydratedPlayback != null) {
                            retained = withContext(Main) {
                                applySourceSeparationModelAwareHydrationIfCurrent(
                                    session = session,
                                    hydratedPlayback = requireNotNull(hydratedPlayback),
                                )
                            }
                        }
                    }
                    SourceSeparationCacheHydrationResult.Busy,
                    SourceSeparationCacheHydrationResult.Unavailable -> {
                        traceSourceSeparationPlayback(
                            "modelAwareHydration.skip",
                            "songId=${session.songId} cache=${cacheKey.take(12)} " +
                                "reason=${result::class.java.simpleName}",
                        )
                    }
                }
            } catch (error: Throwable) {
                if (error !is kotlinx.coroutines.CancellationException) {
                    traceSourceSeparationPlayback(
                        "modelAwareHydration.failed",
                        "songId=${session.songId} cache=${cacheKey.take(12)} " +
                            "error=${error.message ?: error::class.java.name}",
                    )
                }
            } finally {
                if (!retained) hydratedPlayback?.close()
                withContext(Main) {
                    if (sourceSeparationModelAwareHydrationCacheKey == cacheKey) {
                        sourceSeparationModelAwareHydrationJob = null
                        sourceSeparationModelAwareHydrationCacheKey = null
                    }
                }
            }
        }
    }

    private fun applySourceSeparationModelAwareHydrationIfCurrent(
        session: SourceSeparationPlaybackSession,
        hydratedPlayback: SourceSeparationModelAwareHydratedPlayback,
    ): Boolean {
        val current = sourceSeparationPlaybackSession
        val cacheKey = session.modelAwareCachePlayback?.manifest?.cacheKey
        if (current?.sessionId != session.sessionId ||
            current.modelAwareCachePlayback?.manifest?.cacheKey != cacheKey ||
            !hydratedPlayback.vocalsPcmFile.isFile ||
            !hydratedPlayback.instrumentalPcmFile.isFile
        ) {
            traceSourceSeparationPlayback(
                "modelAwareHydration.apply.skip",
                "songId=${session.songId} cache=${cacheKey?.take(12)} reason=staleSession",
            )
            return false
        }

        val hydratedSession = current.copy(
            vocalsFile = hydratedPlayback.vocalsPcmFile,
            instrumentalFile = hydratedPlayback.instrumentalPcmFile,
            hydratedCacheDir = hydratedPlayback.vocalsPcmFile.parentFile,
            pendingHydratedVocalsFile = null,
            pendingHydratedInstrumentalFile = null,
            pendingHydratedCacheDir = null,
            modelAwareHydratedPlayback = hydratedPlayback,
            pendingModelAwareHydratedPlayback = null,
        )
        if (sourceSeparationMixProcessor.hotSwapToPcmInputs(
                vocalsFile = hydratedPlayback.vocalsPcmFile,
                instrumentalFile = hydratedPlayback.instrumentalPcmFile,
            )
        ) {
            sourceSeparationPlaybackSession = hydratedSession
            traceSourceSeparationPlayback(
                "modelAwareHydration.ready.hotSwap",
                "songId=${session.songId} cache=${cacheKey?.take(12)}",
            )
        } else {
            sourceSeparationPlaybackSession = current.copy(
                pendingHydratedVocalsFile = hydratedPlayback.vocalsPcmFile,
                pendingHydratedInstrumentalFile = hydratedPlayback.instrumentalPcmFile,
                pendingHydratedCacheDir = hydratedPlayback.vocalsPcmFile.parentFile,
                pendingModelAwareHydratedPlayback = hydratedPlayback,
            )
            traceSourceSeparationPlayback(
                "modelAwareHydration.ready.pending",
                "songId=${session.songId} cache=${cacheKey?.take(12)}",
            )
        }
        broadcastSourceSeparationPlaybackChanged()
        return true
    }

    private fun cancelSourceSeparationModelAwareHydrationJob() {
        sourceSeparationModelAwareHydrationJob?.cancel()
        sourceSeparationModelAwareHydrationJob = null
        sourceSeparationModelAwareHydrationCacheKey = null
    }

    private fun markSourceSeparationHydrationReadyIfCurrent(
        session: SourceSeparationPlaybackSession,
        hydrationKey: SourceSeparationHydrationKey,
        vocalsPcm: File,
        instrumentalPcm: File,
        hydrationDir: File,
    ) {
        val currentSession = sourceSeparationPlaybackSession
        if (sourceSeparationHydrationJobKey != hydrationKey &&
            currentSession?.songId != session.songId &&
            player.currentMediaItem?.mediaId != session.songId.toString()
        ) {
            hydrationDir.deleteRecursively()
            traceSourceSeparationPlayback(
                "hydration.apply.skip",
                "songId=${session.songId} session=${session.sessionId} reason=staleJob"
            )
            return
        }
        val hydration = rememberWarmSourceSeparationHydration(
            hydrationKey = hydrationKey,
            vocalsPcm = vocalsPcm,
            instrumentalPcm = instrumentalPcm,
            hydrationDir = hydrationDir,
        )
        if (hydration == null) {
            hydrationDir.deleteRecursively()
            traceSourceSeparationPlayback(
                "hydration.apply.skip",
                "songId=${session.songId} session=${session.sessionId} reason=invalidFiles"
            )
            return
        }

        if (currentSession == null) {
            traceSourceSeparationPlayback(
                "hydration.ready.warmOnly",
                "songId=${session.songId} session=${session.sessionId} reason=noActiveSession " +
                        "vocalsBytes=${vocalsPcm.length()} instrumentalBytes=${instrumentalPcm.length()}"
            )
            return
        }
        if (currentSession.songId != session.songId ||
            SourceSeparationHydrationKey.forSession(currentSession) != hydrationKey ||
            !vocalsPcm.isFile ||
            !instrumentalPcm.isFile
        ) {
            traceSourceSeparationPlayback(
                "hydration.apply.skip",
                "songId=${session.songId} session=${session.sessionId} " +
                        "current=${currentSession.sessionId} vocals=${vocalsPcm.isFile} " +
                        "instrumental=${instrumentalPcm.isFile}"
            )
            return
        }

        val hydratedSession = currentSession.copy(
            queueReplacementFile = currentSession.queueReplacementFile,
            vocalsFile = vocalsPcm,
            instrumentalFile = instrumentalPcm,
            hydratedCacheDir = hydrationDir,
            pendingHydratedVocalsFile = null,
            pendingHydratedInstrumentalFile = null,
            pendingHydratedCacheDir = null,
            modelAwareHydratedPlayback = currentSession.pendingModelAwareHydratedPlayback
                ?: currentSession.modelAwareHydratedPlayback,
            pendingModelAwareHydratedPlayback = null,
        )
        val hotSwapApplied = sourceSeparationMixProcessor.hotSwapToPcmInputs(
            vocalsFile = vocalsPcm,
            instrumentalFile = instrumentalPcm,
        )
        sourceSeparationPlaybackSession = if (hotSwapApplied) {
            hydratedSession
        } else {
            currentSession.copy(
                queueReplacementFile = currentSession.queueReplacementFile,
                pendingHydratedVocalsFile = vocalsPcm,
                pendingHydratedInstrumentalFile = instrumentalPcm,
                pendingHydratedCacheDir = hydrationDir,
            )
        }
        if (hotSwapApplied) {
            replaceSourceSeparationQueueClockIfNeeded(
                session = hydratedSession,
                positionMs = player.currentPosition.coerceAtLeast(0),
                reason = "hydrationReady",
            )
        }
        traceSourceSeparationPlayback(
            if (hotSwapApplied) {
                "hydration.ready.hotSwap"
            } else {
                "hydration.ready.pending"
            },
            "songId=${session.songId} session=${session.sessionId} " +
                    "vocalsBytes=${vocalsPcm.length()} instrumentalBytes=${instrumentalPcm.length()}"
        )
    }

    private fun applySourceSeparationHydrationOnSeekIfReady(positionMs: Long): Boolean {
        val currentSession = sourceSeparationPlaybackSession ?: run {
            traceSourceSeparationPlayback(
                "hydration.seekApply.skip",
                "position=$positionMs reason=noSession"
            )
            return false
        }
        if (!currentSession.hasPendingHydration || currentSession.usesHydratedPcm) {
            traceSourceSeparationPlayback(
                "hydration.seekApply.skip",
                "position=$positionMs reason=noPendingOrAlreadyHydrated " +
                        "session=${currentSession.traceSummary()} " +
                        "pending=${currentSession.hasPendingHydration} hydrated=${currentSession.usesHydratedPcm}"
            )
            return false
        }
        val vocalsPcm = currentSession.pendingHydratedVocalsFile ?: run {
            traceSourceSeparationPlayback(
                "hydration.seekApply.skip",
                "position=$positionMs reason=missingPendingVocals session=${currentSession.traceSummary()}"
            )
            return false
        }
        val instrumentalPcm = currentSession.pendingHydratedInstrumentalFile ?: run {
            traceSourceSeparationPlayback(
                "hydration.seekApply.skip",
                "position=$positionMs reason=missingPendingInstrumental session=${currentSession.traceSummary()}"
            )
            return false
        }
        val hydrationDir = currentSession.pendingHydratedCacheDir ?: run {
            traceSourceSeparationPlayback(
                "hydration.seekApply.skip",
                "position=$positionMs reason=missingPendingDir session=${currentSession.traceSummary()}"
            )
            return false
        }
        traceSourceSeparationPlayback(
            "hydration.seekApply.start",
            "position=$positionMs session=${currentSession.traceSummary()} " +
                    "vocalsBytes=${vocalsPcm.length()} instrumentalBytes=${instrumentalPcm.length()}"
        )
        val resumeAfterSwitch = pauseSourceSeparationOutputForSwitch(
            reason = "hydrationSeekApply",
            waitForMixedOutput = true,
        )
        val hydratedSession = currentSession.copy(
            queueReplacementFile = currentSession.queueReplacementFile,
            vocalsFile = vocalsPcm,
            instrumentalFile = instrumentalPcm,
            hydratedCacheDir = hydrationDir,
            pendingHydratedVocalsFile = null,
            pendingHydratedInstrumentalFile = null,
            pendingHydratedCacheDir = null,
        )
        sourceSeparationPlaybackSession = hydratedSession
        enableSourceSeparationMixProcessor(hydratedSession, positionMs)
        replaceSourceSeparationQueueClockIfNeeded(
            session = hydratedSession,
            positionMs = positionMs,
            reason = "hydrationSeekApply",
            pauseForSwitch = false,
            resumePlayback = false,
        )
        sourceSeparationMixProcessor.seekTo(positionMs)
        resumeSourceSeparationOutputAfterSwitch(
            reason = "hydrationSeekApply",
            resume = resumeAfterSwitch || player.playWhenReady,
        )
        traceSourceSeparationPlayback(
            "hydration.seekApply",
            "songId=${hydratedSession.songId} session=${hydratedSession.sessionId} position=$positionMs " +
                    "vocalsBytes=${vocalsPcm.length()} instrumentalBytes=${instrumentalPcm.length()}"
        )
        return true
    }

    private fun findWarmSourceSeparationHydration(
        songId: Long,
        vocalsFile: File,
        instrumentalFile: File,
    ): SourceSeparationWarmHydration? {
        val hydrationKey = SourceSeparationHydrationKey.forFiles(
            songId = songId,
            vocalsFile = vocalsFile,
            instrumentalFile = instrumentalFile,
        )
        sourceSeparationWarmHydration
            ?.takeIf { it.matches(hydrationKey) }
            ?.let { return it }

        val hydrationDir = sourceSeparationHydrationDir(hydrationKey)
        val vocalsPcm = File(hydrationDir, "vocals.pcm")
        val instrumentalPcm = File(hydrationDir, "instrumental.pcm")
        if (!isSourceSeparationHydrationReady(
                hydrationKey = hydrationKey,
                hydrationDir = hydrationDir,
                vocalsPcm = vocalsPcm,
                instrumentalPcm = instrumentalPcm,
            )
        ) {
            return null
        }

        return rememberWarmSourceSeparationHydration(
            hydrationKey = hydrationKey,
            vocalsPcm = vocalsPcm,
            instrumentalPcm = instrumentalPcm,
            hydrationDir = hydrationDir,
        )?.also {
            traceSourceSeparationPlayback(
                "hydration.warmRestore",
                "songId=$songId dir=${hydrationDir.absolutePath}"
            )
        }
    }

    private fun rememberWarmSourceSeparationHydration(
        session: SourceSeparationPlaybackSession?,
    ) {
        if (session == null) return
        when {
            session.usesHydratedPcm -> {
                val hydrationDir = session.hydratedCacheDir ?: return
                rememberWarmSourceSeparationHydration(
                    hydrationKey = SourceSeparationHydrationKey.forSession(session),
                    vocalsPcm = session.vocalsFile,
                    instrumentalPcm = session.instrumentalFile,
                    hydrationDir = hydrationDir,
                )
            }
            session.hasPendingHydration -> rememberWarmSourceSeparationHydration(
                hydrationKey = SourceSeparationHydrationKey.forSession(session),
                vocalsPcm = session.pendingHydratedVocalsFile ?: return,
                instrumentalPcm = session.pendingHydratedInstrumentalFile ?: return,
                hydrationDir = session.pendingHydratedCacheDir ?: return,
            )
        }
    }

    private fun rememberWarmSourceSeparationHydration(
        hydrationKey: SourceSeparationHydrationKey,
        vocalsPcm: File,
        instrumentalPcm: File,
        hydrationDir: File,
    ): SourceSeparationWarmHydration? {
        if (!isSourceSeparationHydrationReady(
                hydrationKey = hydrationKey,
                hydrationDir = hydrationDir,
                vocalsPcm = vocalsPcm,
                instrumentalPcm = instrumentalPcm,
            )
        ) {
            return null
        }
        return SourceSeparationWarmHydration(
            key = hydrationKey,
            vocalsPcm = vocalsPcm,
            instrumentalPcm = instrumentalPcm,
            hydrationDir = hydrationDir,
        ).also {
            sourceSeparationWarmHydration = it
        }
    }

    private fun clearWarmSourceSeparationHydrationIfSongChanged(mediaItem: MediaItem?) {
        val currentSongId = mediaItem?.mediaId?.toLongOrNull()
        if (currentSongId == null) {
            traceSourceSeparationPlayback(
                "hydration.warmKeep",
                "reason=unknownMediaId mediaId=${mediaItem?.mediaId}"
            )
        } else {
            clearWarmSourceSeparationHydrationIfSongChanged(currentSongId)
        }
    }

    private fun clearWarmSourceSeparationHydrationIfSongChanged(currentSongId: Long) {
        sourceSeparationWarmHydration
            ?.takeIf { it.songId != currentSongId }
            ?.let { clearWarmSourceSeparationHydration(it, "songChanged") }
        if (sourceSeparationHydrationJobKey?.songId != currentSongId) {
            cancelSourceSeparationPcmHydrationJob(deletePartial = true)
        }
    }

    private fun clearWarmSourceSeparationHydration(
        hydration: SourceSeparationWarmHydration,
        reason: String,
    ) {
        if (sourceSeparationWarmHydration == hydration) {
            sourceSeparationWarmHydration = null
        }
        serviceScope.launch(IO) {
            hydration.hydrationDir.deleteRecursively()
        }
        traceSourceSeparationPlayback(
            "hydration.warmClear",
            "songId=${hydration.songId} reason=$reason"
        )
    }

    private fun cancelSourceSeparationPcmHydrationJob(deletePartial: Boolean) {
        val hydrationKey = sourceSeparationHydrationJobKey
        sourceSeparationHydrationJob?.cancel()
        sourceSeparationHydrationJob = null
        sourceSeparationHydrationJobKey = null
        if (deletePartial && hydrationKey != null) {
            serviceScope.launch(IO) {
                val hydrationDir = sourceSeparationHydrationDir(hydrationKey)
                if (!isSourceSeparationHydrationReady(
                        hydrationKey = hydrationKey,
                        hydrationDir = hydrationDir,
                        vocalsPcm = File(hydrationDir, "vocals.pcm"),
                        instrumentalPcm = File(hydrationDir, "instrumental.pcm"),
                    )
                ) {
                    hydrationDir.deleteRecursively()
                }
            }
        }
    }

    private fun sourceSeparationHydrationDir(hydrationKey: SourceSeparationHydrationKey): File {
        return File(
            sourceSeparationHydrationRootDir(),
            hydrationKey.directoryName,
        )
    }

    private fun sourceSeparationHydrationRootDir(): File {
        return sourceSeparationHydrationRootDirectory
    }

    private fun isSourceSeparationHydrationReady(
        hydrationKey: SourceSeparationHydrationKey,
        hydrationDir: File,
        vocalsPcm: File,
        instrumentalPcm: File,
    ): Boolean {
        return hydrationDir.isDirectory &&
                vocalsPcm.isFile &&
                instrumentalPcm.isFile &&
                sourceSeparationHydrationReadyFile(hydrationDir).isFile &&
                runCatching {
                    sourceSeparationHydrationIdentityFile(hydrationDir).readText() == hydrationKey.identity
                }.getOrDefault(false)
    }

    private fun writeSourceSeparationHydrationReadyMarker(
        hydrationKey: SourceSeparationHydrationKey,
        hydrationDir: File,
    ) {
        sourceSeparationHydrationIdentityFile(hydrationDir).writeText(hydrationKey.identity)
        sourceSeparationHydrationReadyFile(hydrationDir).writeText("ready")
    }

    private fun sourceSeparationHydrationIdentityFile(hydrationDir: File): File {
        return File(hydrationDir, "identity.txt")
    }

    private fun sourceSeparationHydrationReadyFile(hydrationDir: File): File {
        return File(hydrationDir, "ready")
    }

    private fun decodeFlacStemToPcmFile(
        flacFile: File,
        pcmFile: File,
        shouldCancel: () -> Boolean,
    ) {
        Pcm16StereoFlacEncoder.decodeFlacFileToPcmFile(
            flacFile = flacFile,
            pcmFile = pcmFile,
            shouldCancel = shouldCancel,
        )
    }

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
        if (restoreOriginalItem && session?.affectsCurrentSourceSeparationItem() == true) {
            pauseSourceSeparationOutputForSwitch("clear")
        }
        if (session?.modelAwareCachePlayback != null) {
            cancelSourceSeparationModelAwareHydrationJob()
        }
        sourceSeparationPlaybackSession = null
        cancelSourceSeparationPlaybackReadinessMonitor("clear")
        sourceSeparationMixProcessor.disable()
        sourceSeparationPlaybackIsProcessing = false
        sourceSeparationPausedBlendFlushPending = false
        rememberWarmSourceSeparationHydration(session)
        session?.closeModelAwareResources()
        cleanupCompletedSourceSeparationTemporaryDirs()
        updateSourceSeparationProcessingLease("clear")

        if (restoreOriginalItem && session != null) {
            val restored = restoreOriginalMediaItem(session, resumePlayback = resumeAfterSwitch)
            if (!restored) {
                resumeSourceSeparationOutputAfterSwitch("clear.restoreSkipped", resumeAfterSwitch)
            }
        }

        if (broadcast) {
            broadcastSourceSeparationPlaybackChanged()
        }
    }

    private suspend fun restoreCurrentSourceSeparationStemMediaItemIfNeeded() {
        val index = player.currentMediaItemIndex
        val mediaItem = player.currentMediaItem
        if (index == C.INDEX_UNSET || mediaItem?.isSourceSeparationStemMediaItem() != true) {
            traceSourceSeparationPlayback(
                "playback.restoreStem.skip",
                "index=$index stem=${mediaItem?.isSourceSeparationStemMediaItem()}"
            )
            return
        }

        val song = withContext(IO) {
            repository.songByMediaItem(mediaItem)
        }
        if (song == Song.emptySong) {
            traceSourceSeparationPlayback("playback.restoreStem.skip", "song=empty")
            return
        }

        val positionMs = player.currentPosition.coerceAtLeast(0)
        val playWhenReady = player.playWhenReady
        traceSourceSeparationPlayback(
            "playback.restoreStem.applyOriginal",
            "songId=${song.id} index=$index position=$positionMs playWhenReady=$playWhenReady"
        )
        pauseSourceSeparationOutputForSwitch("restoreStem")
        withSourceSeparationInternalMediaItemChange {
            player.replaceMediaItem(index, song.toMediaItem(mediaItem.mediaId))
            player.seekTo(index, positionMs)
            player.prepare()
            setSourceSeparationPlayWhenReady(playWhenReady)
        }
        broadcastSourceSeparationPlaybackChanged()
    }

    private fun clearSourceSeparationPlaybackProcessing(broadcast: Boolean = true) {
        val changed = sourceSeparationPlaybackIsProcessing || sourceSeparationPlaybackResumeWhenReady
        val shouldResume = sourceSeparationPlaybackResumeWhenReady && player.playWhenReady.not()
        traceSourceSeparationPlayback(
            "playback.clearProcessing",
            "broadcast=$broadcast changed=$changed shouldResume=$shouldResume"
        )
        sourceSeparationPlaybackIsProcessing = false
        sourceSeparationPlaybackResumeWhenReady = false
        sourceSeparationPlaybackGateJob?.cancel()
        sourceSeparationPlaybackGateJob = null
        if (!sourceSeparationPlaybackIsProcessing) {
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
        restoreOriginalItem: Boolean,
        allowPause: Boolean,
        resumeWhenReady: Boolean,
        showMessage: Boolean,
    ): SessionResult {
        val shouldResume = sourceSeparationPlaybackResumeWhenReady ||
                resumeWhenReady ||
                player.playWhenReady ||
                player.isPlaying ||
                sourceSeparationPlaybackPlayIntent
        traceSourceSeparationPlayback(
            "playback.waitForReady",
            "source=$source restoreOriginalItem=$restoreOriginalItem allowPause=$allowPause " +
                    "resumeWhenReadyArg=$resumeWhenReady shouldResume=$shouldResume"
        )
        sourceSeparationPlaybackIsProcessing = true
        sourceSeparationPlaybackResumeWhenReady = shouldResume
        if (allowPause && player.playWhenReady) {
            muteSourceSeparationOutputForSwitch(
                reason = "$source.processingPause",
                waitForMixedOutput = true,
            )
            setSourceSeparationPlayWhenReady(false)
            flushSourceSeparationPausedOutput("$source.processingPause")
        }
        if (restoreOriginalItem) {
            clearSourceSeparationPlayback(restoreOriginalItem = true, broadcast = false)
            sourceSeparationPlaybackIsProcessing = true
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
            var lowHorizonCount = 0
            try {
                while (true) {
                    delay(SOURCE_SEPARATION_READINESS_MONITOR_DELAY_MS)
                    val activeSession = sourceSeparationPlaybackSession
                    if (!sourceSeparationPlaybackRequested ||
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
                    if (runtimeSong == null) {
                        traceSourceSeparationPlayback(
                            "playback.readinessMonitor.unavailable",
                            "songId=${session.songId} session=${session.sessionId} " +
                                    "position=$positionMs reason=missingRuntimeBinding",
                        )
                        break
                    }
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
                            if (horizon.readyThroughEnd ||
                                horizon.readyAheadMs > SOURCE_SEPARATION_READY_HORIZON_GATE_MARGIN_MS
                            ) {
                                lowHorizonCount = 0
                            } else {
                                lowHorizonCount += 1
                                traceSourceSeparationPlayback(
                                    "playback.readinessMonitor.lowHorizon",
                                    "songId=${session.songId} session=${session.sessionId} " +
                                            "position=$positionMs readyAhead=${horizon.readyAheadMs} " +
                                            "readyUntil=${horizon.readyUntilMs} " +
                                            "segment=${horizon.segmentIndex} " +
                                            "readyThrough=${horizon.readyThroughSegmentIndex} " +
                                            "count=$lowHorizonCount"
                                )
                                if (lowHorizonCount >=
                                    SOURCE_SEPARATION_READY_HORIZON_GATE_CONFIRM_COUNT
                                ) {
                                    waitForSourceSeparationPlayback(
                                        source = "readinessMonitor",
                                        restoreOriginalItem = false,
                                        allowPause = true,
                                        resumeWhenReady = true,
                                        showMessage = false,
                                    )
                                    break
                                }
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
                        SourceSeparationModelAwareReadyHorizonStatus.Processing,
                        SourceSeparationModelAwareReadyHorizonStatus.Busy -> {
                            lowHorizonCount += 1
                            traceSourceSeparationPlayback(
                                "playback.readinessMonitor.processing",
                                "songId=${session.songId} session=${session.sessionId} " +
                                        "position=$positionMs count=$lowHorizonCount"
                            )
                            if (lowHorizonCount >=
                                SOURCE_SEPARATION_READY_HORIZON_GATE_CONFIRM_COUNT
                            ) {
                                waitForSourceSeparationPlayback(
                                    source = "readinessMonitor",
                                    restoreOriginalItem = false,
                                    allowPause = true,
                                    resumeWhenReady = true,
                                    showMessage = false,
                                )
                                break
                            }
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

    private fun gateSourceSeparationPlaybackTransition(
        reason: String,
        wasPlaying: Boolean,
    ): Boolean {
        val expectProcessingOnTransition =
            shouldExpectSourceSeparationProcessingOnTransition()
        val resumeAfterTransitionGate = pauseSourceSeparationOutputForSwitch(
            reason = reason,
            waitForMixedOutput = true,
        )
        setSourceSeparationPlaybackExpectProcessing(expectProcessingOnTransition)
        sourceSeparationPlaybackIsProcessing = expectProcessingOnTransition
        sourceSeparationPlaybackResumeWhenReady =
            sourceSeparationPlaybackResumeWhenReady ||
                    resumeAfterTransitionGate ||
                    player.playWhenReady ||
                    wasPlaying ||
                    sourceSeparationPlaybackPlayIntent
        flushSourceSeparationPausedOutput(reason)
        if (expectProcessingOnTransition) {
            broadcastSourceSeparationPlaybackChanged()
        }
        updateSourceSeparationProcessingLease("transition:$reason")
        traceSourceSeparationPlayback(
            "playback.transitionGate",
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
        val shouldRetry = sourceSeparationPlaybackIsProcessing
        if (!shouldRetry) {
            traceSourceSeparationPlayback("playback.scheduleRetry.skip", "shouldRetry=false")
            return
        }

        val delayMs = if (sourceSeparationPlaybackIsProcessing) {
            SOURCE_SEPARATION_PROCESSING_RETRY_DELAY_MS
        } else {
            SOURCE_SEPARATION_READY_RETRY_DELAY_MS
        }
        traceSourceSeparationPlayback("playback.scheduleRetry", "delayMs=$delayMs")
        sourceSeparationPlaybackGateJob = serviceScope.launch {
            delay(delayMs)
            sourceSeparationPlaybackGateJob = null
            traceSourceSeparationPlayback("playback.retry")
            ensureSourceSeparationPlaybackReady(
                showUnavailableMessage = false,
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

    private fun restoreOriginalMediaItem(
        session: SourceSeparationPlaybackSession,
        resumePlayback: Boolean = player.playWhenReady,
    ): Boolean {
        traceSourceSeparationPlayback(
            "playback.restoreOriginal.lookup",
            "sessionSongId=${session.songId} sessionIndex=${session.mediaItemIndex} " +
                    "token=${session.queueReplacementToken}"
        )
        if (!session.replacesQueueMediaItem) {
            traceSourceSeparationPlayback(
                "playback.restoreOriginal.skip",
                "reason=noQueueReplacement"
            )
            return false
        }
        val index = session.mediaItemIndex
            .takeIf { it in 0 until player.mediaItemCount }
            ?.takeIf { player.getMediaItemAt(it).matchesSourceSeparationQueueReplacement(session) }
            ?: (0 until player.mediaItemCount).firstOrNull {
                player.getMediaItemAt(it).matchesSourceSeparationQueueReplacement(session)
            }
            ?: run {
                traceSourceSeparationPlayback(
                    "playback.restoreOriginal.skip",
                    "reason=replacementNotFound"
                )
                return false
            }

        val isCurrentItem = index == player.currentMediaItemIndex
        val positionMs = player.currentPosition.coerceAtLeast(0)
        val shouldResumePlayback = resumePlayback && isCurrentItem
        traceSourceSeparationPlayback(
            "playback.restoreOriginal.apply",
            "index=$index isCurrentItem=$isCurrentItem position=$positionMs " +
                    "resumePlayback=$resumePlayback"
        )
        if (isCurrentItem) {
            pauseSourceSeparationOutputForSwitch("restoreOriginal")
        }
        withSourceSeparationInternalMediaItemChange {
            player.replaceMediaItem(index, session.originalMediaItem)
            if (isCurrentItem) {
                player.seekTo(index, positionMs)
                player.prepare()
                setSourceSeparationPlayWhenReady(shouldResumePlayback)
            }
        }
        return true
    }

    private fun replaceSourceSeparationQueueClockIfNeeded(
        session: SourceSeparationPlaybackSession,
        positionMs: Long,
        reason: String,
        pauseForSwitch: Boolean = true,
        resumePlayback: Boolean = player.playWhenReady || sourceSeparationPlaybackPlayIntent,
    ): Boolean {
        session.queueReplacementFile ?: run {
            traceSourceSeparationPlayback(
                "playback.replaceClock.skip",
                "reason=$reason session=${session.traceSummary()} clock=null"
            )
            return false
        }
        if (!session.replacesQueueMediaItem) {
            traceSourceSeparationPlayback(
                "playback.replaceClock.skip",
                "reason=$reason session=${session.traceSummary()} replace=false"
            )
            return false
        }
        val index = session.mediaItemIndex
            .takeIf { it in 0 until player.mediaItemCount }
            ?.takeIf { player.getMediaItemAt(it).matchesSourceSeparationQueueReplacement(session) }
            ?: (0 until player.mediaItemCount).firstOrNull {
                player.getMediaItemAt(it).matchesSourceSeparationQueueReplacement(session)
            }
            ?: run {
                traceSourceSeparationPlayback(
                    "playback.replaceClock.skip",
                    "reason=$reason session=${session.traceSummary()} replacementNotFound"
                )
                return false
            }
        val currentQueueItem = player.getMediaItemAt(index)
        if (currentQueueItem.matchesSourceSeparationQueueReplacement(session) &&
            currentQueueItem.isSourceSeparationSilenceClockMediaItem() &&
            currentQueueItem.mediaMetadata.durationMs == session.queueReplacementDurationMs
        ) {
            traceSourceSeparationPlayback(
                "playback.replaceClock.skip",
                "reason=$reason index=$index alreadySilenceDuration=${session.queueReplacementDurationMs}"
            )
            return false
        }

        val isCurrentItem = index == player.currentMediaItemIndex
        val resumeAfterSwitch = if (isCurrentItem && pauseForSwitch) {
            pauseSourceSeparationOutputForSwitch(
                reason = reason,
                waitForMixedOutput = true,
            )
        } else {
            false
        }
        val replacementMediaItem = buildSourceSeparationReplacementMediaItem(
            originalMediaItem = session.originalMediaItem,
            songId = session.songId,
            token = session.queueReplacementToken!!,
            durationMs = session.queueReplacementDurationMs,
        )
        traceSourceSeparationPlayback(
            "playback.replaceClock.apply",
            "reason=$reason index=$index isCurrentItem=$isCurrentItem position=$positionMs " +
                    "clock=silence resumePlayback=$resumePlayback"
        )
        withSourceSeparationInternalMediaItemChange {
            player.replaceMediaItem(index, replacementMediaItem)
            if (isCurrentItem) {
                player.seekTo(index, positionMs)
                player.prepare()
                setSourceSeparationPlayWhenReady(false)
            }
        }
        if (isCurrentItem && pauseForSwitch) {
            resumeSourceSeparationOutputAfterSwitch(
                reason = reason,
                resume = resumePlayback || resumeAfterSwitch,
            )
        }
        return true
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
            setSourceSeparationPlayWhenReady(true)
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

    private fun onSourceSeparationMixedOutputStarted() {
        if (!sourceSeparationOutputMuted || !sourceSeparationOutputWaitingForMixedOutput) return
        traceSourceSeparationPlayback("playback.mixedOutputStarted")
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
        restorePlayerVolume()
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
        return !isDefaultSourceSeparationBlend(sourceSeparationMixProcessor.blend)
    }

    private fun isDefaultSourceSeparationBlend(blend: Float): Boolean {
        return kotlin.math.abs(
            blend.coerceIn(0f, 1f) - DEFAULT_SOURCE_SEPARATION_BLEND
        ) < SOURCE_SEPARATION_BLEND_EPSILON
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

    private fun isSourceSeparationProcessingLeaseNeeded(): Boolean {
        return isSourceSeparationPlaybackWaitingForProcessingCache()
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
    ) {
        val index = player.currentMediaItemIndex
        if (index == C.INDEX_UNSET) {
            traceSourceSeparationPlayback(
                "playback.flushPausedOutput.skip",
                "reason=$reason index=C.INDEX_UNSET"
            )
            return
        }
        if (player.playbackState == Player.STATE_BUFFERING) {
            traceSourceSeparationPlayback(
                "playback.flushPausedOutput.skip",
                "reason=$reason state=BUFFERING"
            )
            return
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
        player.seekTo(index, seekPositionMs)
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

    private fun SourceSeparationPlaybackSession.affectsCurrentSourceSeparationItem(): Boolean {
        return player.currentMediaItem?.matchesSourceSeparationQueueReplacement(this) == true
    }

    private fun withSourceSeparationInternalMediaItemChange(block: () -> Unit) {
        sourceSeparationPlaybackInternalMediaItemChangeUntilMs =
            SystemClock.elapsedRealtime() + SOURCE_SEPARATION_INTERNAL_MEDIA_ITEM_CHANGE_MS
        block()
    }

    private fun isSourceSeparationInternalMediaItemChange(): Boolean {
        return SystemClock.elapsedRealtime() <= sourceSeparationPlaybackInternalMediaItemChangeUntilMs
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
        serviceScope.launch(IO) {
            cleanCompletedSourceSeparationTemporaryDirsNow()
        }
    }

    private fun cleanCompletedSourceSeparationTemporaryDirsNow() {
        val cleanedCount = runCatching {
            sourceSeparationRuntime.cleanPendingCompletedTemporaryFiles()
        }.getOrDefault(0)
        if (cleanedCount > 0) {
            traceSourceSeparationPlayback(
                "cleanup.completedTemporaryDirs",
                "count=$cleanedCount"
            )
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
        val file = sourceSeparationPlaybackTraceFile ?: return
        val lines = synchronized(sourceSeparationPlaybackTraceLock) {
            if (sourceSeparationPlaybackTraceBuffer.isEmpty()) return
            sourceSeparationPlaybackTraceBuffer.joinToString(
                separator = "\n",
                postfix = "\n",
            ).also {
                sourceSeparationPlaybackTraceBuffer.clear()
                sourceSeparationPlaybackTraceFlushJob?.cancel()
                sourceSeparationPlaybackTraceFlushJob = null
            }
        }
        serviceScope.launch(IO) {
            runCatching {
                file.appendText(lines, Charsets.UTF_8)
            }.onFailure { error ->
                Log.w(TAG_SOURCE_SEPARATION_PLAYBACK, "Unable to append playback gate trace", error)
            }
        }
    }

    private fun sourceSeparationPlaybackTraceState(): String {
        val session = sourceSeparationPlaybackSession
        val base = "requested=$sourceSeparationPlaybackRequested " +
                "processing=$sourceSeparationPlaybackIsProcessing " +
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
                "mediaId=${player.currentMediaItem?.mediaId} " +
                "stem=${player.currentMediaItem?.isSourceSeparationStemMediaItem()}"
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
        private const val SOURCE_SEPARATION_READY_RETRY_DELAY_MS = 2000L
        private const val SOURCE_SEPARATION_READINESS_MONITOR_DELAY_MS = 250L
        private const val SOURCE_SEPARATION_READY_HORIZON_GATE_MARGIN_MS = 250L
        private const val SOURCE_SEPARATION_READY_HORIZON_GATE_CONFIRM_COUNT = 2
        private const val SOURCE_SEPARATION_OUTPUT_UNMUTE_DELAY_MS = 120L
        private const val SOURCE_SEPARATION_OUTPUT_UNMUTE_FALLBACK_DELAY_MS = 1500L
        private const val SOURCE_SEPARATION_INTERNAL_MEDIA_ITEM_CHANGE_MS = 500L
        private const val SOURCE_SEPARATION_BLEND_FLUSH_SEEK_OFFSET_MS = 10L
        private const val DEFAULT_SOURCE_SEPARATION_BLEND = 0.5f
        private const val SOURCE_SEPARATION_BLEND_EPSILON = 0.0001f
        private const val SOURCE_SEPARATION_EXPECT_PROCESSING_TIMEOUT_MS = 10_000L
        private const val SOURCE_SEPARATION_PROCESSING_LEASE_HEARTBEAT_MS = 1_000L
        private const val SOURCE_SEPARATION_BUFFERING_FGS_REFRESH_MS = 3_000L
        private const val SOURCE_SEPARATION_PROCESSING_WAKE_LOCK_REFRESH_MS = 15_000L

        private const val FOREGROUND_SERVICE_TIMEOUT = (60 * 1000) * 2L

        private val isSourceSeparationTraceFileEnabled: Boolean
            get() = BuildConfig.DEBUG
        private const val SOURCE_SEPARATION_TRACE_FLUSH_DELAY_MS = 1000L
        private const val SOURCE_SEPARATION_TRACE_FLUSH_LINE_COUNT = 80
        private const val SOURCE_SEPARATION_TRACE_TIME_FORMAT = "yyyy-MM-dd HH:mm:ss.SSS"
        private const val SOURCE_SEPARATION_HYDRATION_START_DELAY_MS = 750L
        private val MODEL_AWARE_CACHE_KEY_PATTERN = Regex("^[0-9a-f]{64}$")
    }
}

private data class SourceSeparationPlaybackSession(
    val songId: Long,
    val sessionId: Long,
    val mediaItemIndex: Int,
    val originalMediaItem: MediaItem,
    val queueReplacementToken: String?,
    val queueReplacementFile: File?,
    val queueReplacementDurationMs: Long,
    val vocalsFile: File,
    val instrumentalFile: File,
    val cacheVocalsFile: File = vocalsFile,
    val cacheInstrumentalFile: File = instrumentalFile,
    val inputMode: InputMode,
    val stemSampleRate: Int,
    val stemChannelCount: Int,
    val requiresReadinessGate: Boolean,
    val hydratedCacheDir: File? = null,
    val pendingHydratedVocalsFile: File? = null,
    val pendingHydratedInstrumentalFile: File? = null,
    val pendingHydratedCacheDir: File? = null,
    val runtimeSong: SourceSeparationRuntimeSong? = null,
    val modelAwareCachePlayback: SourceSeparationModelAwareCachePlayback? = null,
    val modelAwareHydratedPlayback: SourceSeparationModelAwareHydratedPlayback? = null,
    val pendingModelAwareHydratedPlayback: SourceSeparationModelAwareHydratedPlayback? = null,
) {
    val replacesQueueMediaItem: Boolean
        get() = queueReplacementToken != null

    val usesHydratedPcm: Boolean
        get() = hydratedCacheDir != null

    val hasPendingHydration: Boolean
        get() = pendingHydratedVocalsFile?.isFile == true &&
                pendingHydratedInstrumentalFile?.isFile == true &&
                pendingHydratedCacheDir?.isDirectory == true

    val isExplicitModelAwareCachePlayback: Boolean
        get() = modelAwareCachePlayback != null && runtimeSong == null

    fun traceSummary(): String {
        return "song=$songId id=$sessionId mode=$inputMode gate=$requiresReadinessGate " +
                "hydrated=$usesHydratedPcm pendingHydration=$hasPendingHydration " +
                "modelAware=${modelAwareCachePlayback?.manifest?.cacheKey?.take(12)} " +
                "replace=$replacesQueueMediaItem vocals=${vocalsFile.extension}:${vocalsFile.length()} " +
                "instrumental=${instrumentalFile.extension}:${instrumentalFile.length()} " +
                "clock=${queueReplacementFile?.extension}:${queueReplacementFile?.length()}"
    }

    fun closeModelAwareResources() {
        pendingModelAwareHydratedPlayback?.close()
        modelAwareHydratedPlayback?.close()
        modelAwareCachePlayback?.close()
    }
}

private data class SourceSeparationHydrationKey(
    val songId: Long,
    val cacheVocalsPath: String,
    val cacheInstrumentalPath: String,
    val cacheVocalsBytes: Long,
    val cacheInstrumentalBytes: Long,
    val cacheVocalsModifiedMs: Long,
    val cacheInstrumentalModifiedMs: Long,
) {
    val identity: String = listOf(
        "pcm-hydration-v2",
        songId.toString(),
        cacheVocalsPath,
        cacheInstrumentalPath,
        cacheVocalsBytes.toString(),
        cacheInstrumentalBytes.toString(),
        cacheVocalsModifiedMs.toString(),
        cacheInstrumentalModifiedMs.toString(),
    ).joinToString(separator = "|")

    val directoryName: String = "$songId-${identity.sha256Hex().take(24)}"

    companion object {
        fun forSession(session: SourceSeparationPlaybackSession): SourceSeparationHydrationKey {
            return forFiles(
                songId = session.songId,
                vocalsFile = session.cacheVocalsFile,
                instrumentalFile = session.cacheInstrumentalFile,
            )
        }

        fun forFiles(
            songId: Long,
            vocalsFile: File,
            instrumentalFile: File,
        ): SourceSeparationHydrationKey {
            return SourceSeparationHydrationKey(
                songId = songId,
                cacheVocalsPath = vocalsFile.absolutePath,
                cacheInstrumentalPath = instrumentalFile.absolutePath,
                cacheVocalsBytes = vocalsFile.length(),
                cacheInstrumentalBytes = instrumentalFile.length(),
                cacheVocalsModifiedMs = vocalsFile.lastModified(),
                cacheInstrumentalModifiedMs = instrumentalFile.lastModified(),
            )
        }
    }
}

private data class SourceSeparationWarmHydration(
    val key: SourceSeparationHydrationKey,
    val vocalsPcm: File,
    val instrumentalPcm: File,
    val hydrationDir: File,
) {
    val songId: Long
        get() = key.songId

    fun matches(hydrationKey: SourceSeparationHydrationKey): Boolean {
        return key == hydrationKey &&
                vocalsPcm.isFile &&
                instrumentalPcm.isFile &&
                hydrationDir.isDirectory
    }
}

private fun String.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}

private fun MediaItem.isSourceSeparationStemMediaItem(): Boolean {
    if (mediaMetadata.extras?.containsKey(SOURCE_SEPARATION_QUEUE_REPLACEMENT_TOKEN_KEY) == true) {
        return true
    }
    val uri = localConfiguration?.uri ?: return false
    if (uri.scheme == SOURCE_SEPARATION_QUEUE_REPLACEMENT_URI_SCHEME) {
        return true
    }
    val path = uri.path ?: return false
    return uri.scheme == "file" &&
            path.contains("/source-separation/") &&
            path.substringAfterLast('/').contains("instrumental")
}

private fun MediaItem.sourceSeparationQueueReplacementToken(): String? {
    return mediaMetadata.extras
        ?.getString(SOURCE_SEPARATION_QUEUE_REPLACEMENT_TOKEN_KEY)
        ?: localConfiguration
            ?.uri
            ?.takeIf { it.scheme == SOURCE_SEPARATION_QUEUE_REPLACEMENT_URI_SCHEME }
            ?.lastPathSegment
}

private fun MediaItem.isSourceSeparationSilenceClockMediaItem(): Boolean {
    return localConfiguration?.uri?.scheme == SOURCE_SEPARATION_QUEUE_REPLACEMENT_URI_SCHEME
}

private fun MediaItem.matchesSourceSeparationQueueReplacement(
    session: SourceSeparationPlaybackSession,
): Boolean {
    val token = session.queueReplacementToken ?: return false
    return sourceSeparationQueueReplacementToken() == token &&
            mediaId == session.songId.toString() &&
            isSourceSeparationStemMediaItem()
}

private fun MediaItem.withSourceSeparationQueueReplacementToken(token: String): MediaItem {
    val extras = Bundle(mediaMetadata.extras ?: Bundle()).apply {
        putString(SOURCE_SEPARATION_QUEUE_REPLACEMENT_TOKEN_KEY, token)
    }
    return buildUpon()
        .setMediaMetadata(
            mediaMetadata.buildUpon()
                .setExtras(extras)
                .build()
        )
        .build()
}

private fun buildSourceSeparationReplacementMediaItem(
    originalMediaItem: MediaItem,
    songId: Long,
    token: String,
    durationMs: Long,
): MediaItem {
    return MediaItem.Builder()
        .setUri(sourceSeparationQueueReplacementUri(songId, token))
        .setMimeType(MimeTypes.AUDIO_RAW)
        .setMediaId(songId.toString())
        .setMediaMetadata(
            originalMediaItem.mediaMetadata.buildUpon()
                .setDurationMs(durationMs.coerceAtLeast(1L))
                .build()
        )
        .build()
        .withSourceSeparationQueueReplacementToken(token)
}

private fun sourceSeparationQueueReplacementUri(songId: Long, token: String): Uri {
    return Uri.Builder()
        .scheme(SOURCE_SEPARATION_QUEUE_REPLACEMENT_URI_SCHEME)
        .authority(songId.toString())
        .appendPath(token)
        .build()
}

@OptIn(UnstableApi::class)
private fun sourceSeparationAwareMediaSourceFactory(
    context: Context,
    mp3IndexSeekingEnabled: Boolean,
): MediaSource.Factory {
    val defaultFactory = DefaultMediaSourceFactory(
        context,
        DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .also { extractorsFactory ->
                if (mp3IndexSeekingEnabled) {
                    extractorsFactory.setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING)
                }
            }
    )
    return object : MediaSource.Factory by defaultFactory {
        override fun createMediaSource(mediaItem: MediaItem): MediaSource {
            if (mediaItem.isSourceSeparationStemMediaItem()) {
                val durationUs = mediaItem.mediaMetadata.durationMs
                    ?.takeIf { it > 0L }
                    ?.times(1000L)
                    ?: 1L
                return SilenceMediaSource(durationUs).apply {
                    updateMediaItem(mediaItem)
                }
            }
            return defaultFactory.createMediaSource(mediaItem)
        }
    }
}

private fun SourceSeparationCacheOutput.durationMsForPlaybackClock(): Long {
    return (outputFrameCount.toLong() * 1000L / outputSampleRate.toLong()).coerceAtLeast(1L)
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
