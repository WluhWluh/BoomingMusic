package com.mardous.booming.debug

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.media3.common.C
import com.mardous.booming.data.local.repository.Repository
import com.mardous.booming.data.model.Song
import com.mardous.booming.playback.Playback
import com.mardous.booming.separation.SourceSeparationSourcePreflightMemo
import com.mardous.booming.separation.SourceSeparationMultiStemContractMemo
import com.mardous.booming.separation.process.ipc.BoundRemoteSourceSeparationMultiStemExecutionHost
import com.mardous.booming.ui.screen.MainActivity
import com.mardous.booming.ui.screen.player.SourceSeparationForegroundWorkerDebugBridge
import org.json.JSONArray
import org.json.JSONObject
import org.koin.java.KoinJavaComponent.get
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SourceSeparationDebugControlProvider : ContentProvider() {
    private val applicationReady = CountDownLatch(1)
    private val mediaClientLock = Any()
    private var mediaClient: SourceSeparationDebugMediaClient? = null
    private val operations = SourceSeparationDebugOperationRegistry()
    private val resources by lazy {
        SourceSeparationDebugResourceController(operations, ::requireMediaClient)
    }
    private val modelRuntimes by lazy {
        SourceSeparationDebugModelRuntimeController(operations)
    }
    private val quickSetup by lazy {
        SourceSeparationDebugQuickSetupController(operations)
    }
    private val diagnostics by lazy {
        SourceSeparationDebugDiagnosticsExporter(
            context = providerContext(),
            operations = operations,
            mediaClient = ::requireMediaClient,
            stateSnapshot = { fullState(requireMediaClient()) },
        )
    }

    override fun onCreate(): Boolean {
        // Providers are installed before Application.onCreate. An external
        // content call can otherwise start an operation before Koin exists.
        Handler(Looper.getMainLooper()).post(applicationReady::countDown)
        return true
    }

    override fun shutdown() {
        operations.close()
        synchronized(mediaClientLock) {
            mediaClient?.close()
            mediaClient = null
        }
        super.shutdown()
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        enforceDebugCaller()
        val arguments = DebugArguments(arg, extras ?: Bundle.EMPTY)
        return runCatching {
            awaitApplicationReady()
            execute(method.trim().lowercase(), arguments)
        }.getOrElse { error ->
            SourceSeparationDebugProtocol.failure(
                code = error.debugCode(),
                message = error.message ?: error::class.java.simpleName,
            )
        }
    }

    private fun awaitApplicationReady() {
        if (applicationReady.count == 0L) return
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "Debug control was called on the main thread before application initialization."
        }
        check(applicationReady.await(APPLICATION_READY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            "Timed out waiting for debug-control application initialization."
        }
    }

    private fun execute(command: String, args: DebugArguments): Bundle = when (command) {
        "help" -> SourceSeparationDebugProtocol.help()
        "state" -> withMediaClient { client ->
            SourceSeparationDebugProtocol.success(fullState(client))
        }
        "playback.play" -> withMediaClient { client ->
            client.execute { play() }
            SourceSeparationDebugProtocol.success(playbackState(client))
        }
        "playback.pause" -> withMediaClient { client ->
            client.execute { pause() }
            SourceSeparationDebugProtocol.success(playbackState(client))
        }
        "playback.toggle" -> withMediaClient { client ->
            client.execute { if (playWhenReady) pause() else play() }
            SourceSeparationDebugProtocol.success(playbackState(client))
        }
        "playback.stop" -> withMediaClient { client ->
            client.execute { stop() }
            SourceSeparationDebugProtocol.success(playbackState(client))
        }
        "playback.next" -> withMediaClient { client ->
            client.execute { seekToNext() }
            SourceSeparationDebugProtocol.success(playbackState(client))
        }
        "playback.previous" -> withMediaClient { client ->
            client.execute { seekToPrevious() }
            SourceSeparationDebugProtocol.success(playbackState(client))
        }
        "playback.seek" -> withMediaClient { client ->
            val positionMs = args.requireLong("position_ms").coerceAtLeast(0L)
            client.execute { seekTo(positionMs) }
            SourceSeparationDebugProtocol.success(playbackState(client))
        }
        "playback.seek_percent" -> withMediaClient { client ->
            val percent = args.requireFloat("percent").coerceIn(0f, 100f)
            client.execute {
                require(duration != C.TIME_UNSET && duration > 0L) {
                    "Current duration is unavailable."
                }
                seekTo((duration * percent / 100f).toLong())
            }
            SourceSeparationDebugProtocol.success(playbackState(client))
        }
        "playback.song" -> withMediaClient { client ->
            val song = resolveSong(args)
            client.openSong(
                mediaId = song.id.toString(),
                play = args.boolean("play", true),
                positionMs = args.optionalLong("position_ms"),
            )
            SourceSeparationDebugProtocol.success(
                playbackState(client).put("requestedSong", song.toJson()),
            )
        }
        "playback.queue" -> withMediaClient { client ->
            SourceSeparationDebugProtocol.success(playbackQueue(client))
        }
        "separation.output" -> withMediaClient { client ->
            val enabled = args.requireBoolean("enabled")
            val autoSync = args.boolean("auto_sync", true)
            val expectProcessing = args.boolean("expect_processing", enabled)
            val blend = args.optionalFloat("blend")?.coerceIn(0f, 1f)
            if (autoSync && expectProcessing == enabled &&
                SourceSeparationForegroundWorkerDebugBridge.setPlaybackEnabled(enabled, blend)
            ) {
                return@withMediaClient SourceSeparationDebugProtocol.success(
                    JSONObject()
                        .put("enabled", enabled)
                        .put("expectProcessing", expectProcessing)
                        .put("uiPath", true),
                )
            }
            val result = client.send(
                Playback.SET_SOURCE_SEPARATION_PLAYBACK_ENABLED,
                Bundle().apply {
                    putBoolean(Playback.EXTRA_SOURCE_SEPARATION_ENABLED, enabled)
                    putBoolean(Playback.EXTRA_SOURCE_SEPARATION_SHOW_MESSAGE, false)
                    putBoolean(
                        Playback.EXTRA_SOURCE_SEPARATION_AUTO_SYNC_ON_TRANSITION,
                        autoSync,
                    )
                    putBoolean(
                        Playback.EXTRA_SOURCE_SEPARATION_EXPECT_PROCESSING,
                        expectProcessing,
                    )
                    blend?.let {
                        putFloat(Playback.EXTRA_SOURCE_SEPARATION_BLEND, it)
                    }
                },
            )
            sessionResult(
                result.resultCode,
                Bundle(result.extras).apply { putBoolean("uiPath", false) },
            )
        }
        "separation.sync" -> withMediaClient { client ->
            val result = client.send(
                Playback.SYNC_SOURCE_SEPARATION_PLAYBACK,
                Bundle().apply {
                    putBoolean(
                        Playback.EXTRA_SOURCE_SEPARATION_ALLOW_NEW_SESSION,
                        args.boolean("allow_new_session", true),
                    )
                    putBoolean(
                        Playback.EXTRA_SOURCE_SEPARATION_EXPECT_PROCESSING,
                        args.boolean("expect_processing", true),
                    )
                    putBoolean(
                        Playback.EXTRA_SOURCE_SEPARATION_PREFER_COMPLETED_CACHE,
                        args.boolean("prefer_completed", false),
                    )
                },
            )
            sessionResult(result.resultCode, result.extras)
        }
        "separation.blend" -> withMediaClient { client ->
            val blend = args.requireFloat("blend").coerceIn(0f, 1f)
            val persist = args.boolean("persist", true)
            if (SourceSeparationForegroundWorkerDebugBridge.setBlend(blend, persist)) {
                return@withMediaClient SourceSeparationDebugProtocol.success(
                    JSONObject()
                        .put("blend", blend)
                        .put("persist", persist)
                        .put("uiPath", true),
                )
            }
            val persistence = if (persist) resources.persistBlendFallback(blend) else null
            val result = client.send(
                Playback.SET_SOURCE_SEPARATION_BLEND,
                Bundle().apply {
                    putFloat(Playback.EXTRA_SOURCE_SEPARATION_BLEND, blend)
                    putBoolean(
                        Playback.EXTRA_SOURCE_SEPARATION_PERSIST_BLEND,
                        persist,
                    )
                },
            )
            sessionResult(
                result.resultCode,
                Bundle(result.extras).apply {
                    putBoolean("uiPath", false)
                    persistence?.let { putString("persistence", it.toString()) }
                },
            )
        }
        "separation.stem_gains" -> withMediaClient { client ->
            val state = client.debugState()
            val cacheKey = state.getString(Playback.EXTRA_SOURCE_SEPARATION_CACHE_KEY).orEmpty()
            require(cacheKey.isNotBlank()) { "There is no active source-separation cache." }
            val expectedStemIds = state.getStringArrayList(
                Playback.EXTRA_SOURCE_SEPARATION_STEM_IDS,
            ).orEmpty()
            require(expectedStemIds.isNotEmpty()) {
                "The active source-separation session has no independent stem gains."
            }
            val gainsByStemId = parseStemGains(args.requireString("gains"))
            require(gainsByStemId.keys == expectedStemIds.toSet()) {
                "Stem gain IDs must exactly match: ${expectedStemIds.joinToString()}."
            }
            val persist = args.boolean("persist", true)
            if (SourceSeparationForegroundWorkerDebugBridge.setStemGains(
                    gainsByStemId,
                    persist,
                )
            ) {
                return@withMediaClient SourceSeparationDebugProtocol.success(
                    JSONObject()
                        .put("cacheKey", cacheKey)
                        .put("gains", JSONObject(gainsByStemId))
                        .put("persist", persist)
                        .put("uiPath", true),
                )
            }
            val persistence = if (persist) {
                resources.persistStemGainsFallback(cacheKey, expectedStemIds, gainsByStemId)
            } else {
                null
            }
            val result = client.send(
                Playback.SET_SOURCE_SEPARATION_STEM_GAINS,
                Bundle().apply {
                    putString(Playback.EXTRA_SOURCE_SEPARATION_CACHE_KEY, cacheKey)
                    putStringArrayList(
                        Playback.EXTRA_SOURCE_SEPARATION_STEM_IDS,
                        ArrayList(expectedStemIds),
                    )
                    putFloatArray(
                        Playback.EXTRA_SOURCE_SEPARATION_STEM_GAINS,
                        expectedStemIds.map(gainsByStemId::getValue).toFloatArray(),
                    )
                    putBoolean(
                        Playback.EXTRA_SOURCE_SEPARATION_PERSIST_STEM_GAINS,
                        persist,
                    )
                },
            )
            sessionResult(
                result.resultCode,
                Bundle(result.extras).apply {
                    putBoolean("uiPath", false)
                    persistence?.let { putString("persistence", it.toString()) }
                },
            )
        }
        "separation.marker" -> withMediaClient { client ->
            val result = client.send(
                Playback.TRACE_SOURCE_SEPARATION_PLAYBACK_MARKER,
                Bundle().apply {
                    putString(
                        Playback.EXTRA_SOURCE_SEPARATION_TRACE_MARKER,
                        args.requireString("marker"),
                    )
                },
            )
            sessionResult(result.resultCode, result.extras)
        }
        "separation.start", "separation.resume" -> {
            val started = SourceSeparationForegroundWorkerDebugBridge.startCurrentSong()
            booleanResult(started, "worker_unavailable", "No current song is attached to the worker.")
        }
        "separation.prestart" -> {
            val song = resolveSong(args)
            val readyWindowCount = args.optionalInt("ready_windows") ?: 2
            require(readyWindowCount > 0) { "ready_windows must be greater than zero." }
            val started = kotlinx.coroutines.runBlocking {
                SourceSeparationForegroundWorkerDebugBridge.preStartSong(
                    song = song,
                    readyWindowCount = readyWindowCount,
                )
            }
            if (started) {
                SourceSeparationDebugProtocol.success(
                    JSONObject()
                        .put("requestedSong", song.toJson())
                        .put("readyWindows", readyWindowCount),
                )
            } else {
                SourceSeparationDebugProtocol.failure(
                    "prestart_not_scheduled",
                    "The song is already ready, active, or playback no longer owns the worker.",
                    JSONObject()
                        .put("requestedSong", song.toJson())
                        .put("readyWindows", readyWindowCount),
                )
            }
        }
        "separation.pause" -> {
            val paused = SourceSeparationForegroundWorkerDebugBridge.pause()
            booleanResult(paused, "worker_unavailable", "No source-separation worker is available.")
        }
        "separation.cancel" -> {
            val canceled = SourceSeparationForegroundWorkerDebugBridge.cancel()
            booleanResult(canceled, "worker_unavailable", "No source-separation worker is available.")
        }
        "separation.samples" -> SourceSeparationDebugProtocol.success(
            JSONObject().put(
                "text",
                SourceSeparationForegroundWorkerDebugBridge.windowSamples(),
            ),
        )
        "separation.samples.clear" -> booleanResult(
            SourceSeparationForegroundWorkerDebugBridge.clearWindowSamples(),
            "worker_unavailable",
            "Unable to clear worker samples.",
        )
        "separation.process.terminate" -> {
            BoundRemoteSourceSeparationMultiStemExecutionHost(providerContext())
                .terminateRemoteProcessForValidation()
            SourceSeparationDebugProtocol.success(
                JSONObject().put("terminationRequested", true),
            )
        }
        "settings.get" -> SourceSeparationDebugProtocol.success(resources.settings())
        "settings.set" -> SourceSeparationDebugProtocol.success(resources.updateSettings(args))
        "cache.list" -> SourceSeparationDebugProtocol.success(resources.caches())
        "cache.activate" -> accepted(
            resources.submitCacheActivation(resolveCacheKey(args)),
        )
        "cache.delete" -> accepted(
            resources.submitCacheDelete(resolveCacheKey(args)),
        )
        "cache.delete_all" -> accepted(resources.submitCacheDeleteAll())
        "cache.promote" -> accepted(
            resources.submitCachePromotion(resolveCacheKey(args)),
        )
        "cache.cleanup" -> accepted(resources.submitCacheCleanup())
        "model.list" -> SourceSeparationDebugProtocol.success(
            modelRuntimes.models(refresh = args.boolean("refresh", false)),
        )
        "model.install" -> accepted(
            modelRuntimes.submitModelInstall(args.requireString("model_id")),
        )
        "model.select" -> accepted(
            modelRuntimes.submitModelSelect(
                modelId = args.optionalString("model_id"),
                sha256 = args.optionalString("sha256"),
                profileId = args.optionalString("profile_id"),
            ),
        )
        "model.delete" -> accepted(
            modelRuntimes.submitModelDelete(
                modelId = args.optionalString("model_id"),
                sha256 = args.optionalString("sha256"),
            ),
        )
        "runtime.list" -> SourceSeparationDebugProtocol.success(
            modelRuntimes.runtimes(verify = args.boolean("verify", false)),
        )
        "runtime.install", "runtime.repair", "runtime.activate", "runtime.remove" ->
            accepted(
                modelRuntimes.submitRuntimeMutation(
                    action = command.substringAfter('.'),
                    requestedKind = args.optionalString("runtime_kind"),
                    componentId = args.optionalString("component_id"),
                ),
            )
        "setup.plan" -> SourceSeparationDebugProtocol.success(
            quickSetup.plan(
                mode = parseQuickSetupMode(args.optionalString("mode")),
                verify = args.boolean("verify", false),
            ),
        )
        "setup.execute" -> accepted(
            quickSetup.submit(
                mode = parseQuickSetupMode(args.optionalString("mode")),
                verify = args.boolean("verify", false),
            ),
        )
        "operation.get" -> SourceSeparationDebugProtocol.success(
            operations.snapshot(args.requireString("operation_id")).toJson(),
        )
        "operation.list" -> SourceSeparationDebugProtocol.success(
            JSONObject().put("operations", operations.snapshots().toJson()),
        )
        "operation.cancel" -> SourceSeparationDebugProtocol.success(
            operations.cancel(args.requireString("operation_id")).toJson(),
        )
        "diagnostics.export" -> accepted(diagnostics.submit())
        "ui.launch" -> {
            val surface = args.optionalString("surface") ?: MainActivity.DEBUG_SURFACE_MAIN
            require(surface in MainActivity.DEBUG_SURFACES) {
                "Unknown debug UI surface '$surface'."
            }
            providerContext().startActivity(
                Intent(providerContext(), MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(MainActivity.EXTRA_DEBUG_SURFACE, surface),
            )
            SourceSeparationDebugProtocol.success(
                data = JSONObject().put("surface", surface),
                message = "Main activity launched.",
            )
        }
        else -> SourceSeparationDebugProtocol.failure(
            "unknown_command",
            "Unknown command '$command'. Use method 'help' to list commands.",
        )
    }

    private fun fullState(client: SourceSeparationDebugMediaClient): JSONObject = JSONObject()
        .put("playback", playbackState(client))
        .put("queue", playbackQueue(client))
        .put("separation", bundleJson(client.debugState()))
        .put("worker", SourceSeparationForegroundWorkerDebugBridge.status())
        .put("preflightMemo", preflightMemoState())
        .put("multiStemContractMemo", multiStemContractMemoState())
        .put("settings", captureStateSection(resources::settings))
        .put("caches", captureStateSection(resources::caches))
        .put(
            "models",
            captureStateSection {
                modelRuntimes.models(refresh = false, allowCatalogDownload = false)
            },
        )
        .put("runtimes", captureStateSection { modelRuntimes.runtimes(verify = false) })
        .put("operations", operations.snapshots().toJson())

    private fun preflightMemoState(): JSONObject {
        val memo: SourceSeparationSourcePreflightMemo =
            get(SourceSeparationSourcePreflightMemo::class.java)
        val snapshot = memo.snapshot()
        return JSONObject()
            .put("hits", snapshot.hits)
            .put("misses", snapshot.misses)
            .put("evictions", snapshot.evictions)
            .put("entryCount", snapshot.entryCount)
    }

    private fun multiStemContractMemoState(): JSONObject {
        val memo: SourceSeparationMultiStemContractMemo =
            get(SourceSeparationMultiStemContractMemo::class.java)
        val snapshot = memo.snapshot()
        return JSONObject()
            .put("hits", snapshot.hits)
            .put("misses", snapshot.misses)
            .put("entryCount", snapshot.entryCount)
    }

    private fun playbackState(client: SourceSeparationDebugMediaClient): JSONObject =
        client.read {
            JSONObject()
                .put("state", client.playbackStateName(playbackState))
                .put("playWhenReady", playWhenReady)
                .put("isPlaying", isPlaying)
                .put("positionMs", currentPosition)
                .put("durationMs", duration)
                .put("bufferedPositionMs", bufferedPosition)
                .put("mediaItemIndex", currentMediaItemIndex)
                .put("mediaItemCount", mediaItemCount)
                .put("mediaId", currentMediaItem?.mediaId)
                .put("title", mediaMetadata.title?.toString())
                .put("artist", mediaMetadata.artist?.toString())
                .put("repeatMode", repeatMode)
                .put("shuffleModeEnabled", shuffleModeEnabled)
        }

    private fun playbackQueue(client: SourceSeparationDebugMediaClient): JSONObject {
        return client.read {
            val items = JSONArray()
            repeat(mediaItemCount) { index ->
                val item = getMediaItemAt(index)
                items.put(
                    JSONObject()
                        .put("index", index)
                        .put("mediaId", item.mediaId)
                        .put("title", item.mediaMetadata.title?.toString())
                        .put("artist", item.mediaMetadata.artist?.toString()),
                )
            }
            JSONObject()
                .put("currentIndex", currentMediaItemIndex)
                .put("items", items)
        }
    }

    private fun resolveSong(args: DebugArguments): Song {
        val repository = get<Repository>(Repository::class.java)
        args.optionalLong("song_id")?.let { songId ->
            return repository.songById(songId).takeUnless { it == Song.emptySong }
                ?: error("No song has ID $songId.")
        }
        args.optionalString("path")?.let { path ->
            return runCatching {
                kotlinx.coroutines.runBlocking {
                    repository.songByFilePath(path, true)
                }
            }
                .getOrNull()
                ?.takeUnless { it == Song.emptySong }
                ?: error("No song has path '$path'.")
        }
        val query = args.requireString("query")
        val matches = kotlinx.coroutines.runBlocking {
            repository.searchSongs(query)
        }
        require(matches.isNotEmpty()) { "No song matches '$query'." }
        require(matches.size == 1 || args.boolean("first", false)) {
            "Song query is ambiguous (${matches.size} matches); pass first=true or use song_id."
        }
        return matches.first()
    }

    private fun sessionResult(resultCode: Int, extras: Bundle): Bundle {
        val data = bundleJson(extras).put("sessionResultCode", resultCode)
        return if (resultCode == 0) {
            SourceSeparationDebugProtocol.success(data)
        } else {
            SourceSeparationDebugProtocol.failure(
                "session_result_$resultCode",
                extras.getString(Playback.EXTRA_SOURCE_SEPARATION_MESSAGE)
                    ?: "MediaSession command failed with code $resultCode.",
                data,
            )
        }
    }

    private fun booleanResult(value: Boolean, code: String, message: String): Bundle =
        if (value) SourceSeparationDebugProtocol.success()
        else SourceSeparationDebugProtocol.failure(code, message)

    private fun accepted(operation: SourceSeparationDebugOperationSnapshot): Bundle =
        SourceSeparationDebugProtocol.success(
            data = operation.toJson(),
            message = "Operation accepted.",
            operationId = operation.id,
        )

    private fun resolveCacheKey(args: DebugArguments): String {
        args.optionalString("cache_key")?.let { return it }
        require(args.boolean("current", false)) {
            "cache_key is required unless current=true."
        }
        return requireNotNull(
            resources.currentCacheKey(),
        ) { "The current song has no cache for the selected model." }
    }

    private inline fun withMediaClient(
        block: (SourceSeparationDebugMediaClient) -> Bundle,
    ): Bundle = block(requireMediaClient())

    private fun requireMediaClient(): SourceSeparationDebugMediaClient =
        synchronized(mediaClientLock) {
            mediaClient ?: SourceSeparationDebugMediaClient(providerContext()).also {
                mediaClient = it
            }
        }

    private fun providerContext() = checkNotNull(context) { "Provider context is unavailable." }

    private fun enforceDebugCaller() {
        val callingUid = Binder.getCallingUid()
        val ownUid = providerContext().applicationInfo.uid
        if (callingUid != ownUid && callingUid != Process.SHELL_UID && callingUid != 0) {
            throw SecurityException("Debug control only accepts the app, shell, or root UID.")
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String = "application/json"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private companion object {
        const val APPLICATION_READY_TIMEOUT_SECONDS = 10L
    }
}

internal class DebugArguments(
    private val arg: String?,
    private val extras: Bundle,
) {
    fun has(key: String): Boolean = extras.containsKey(key)

    fun optionalString(key: String): String? = extras.getString(key)
        ?.takeIf(String::isNotBlank)
        ?: if (key == "value") arg?.takeIf(String::isNotBlank) else null

    fun requireString(key: String): String = requireNotNull(optionalString(key)) {
        "Missing string argument '$key'."
    }

    fun optionalLong(key: String): Long? = when {
        !extras.containsKey(key) -> null
        raw(key) is Number -> (raw(key) as Number).toLong()
        else -> extras.getString(key)?.toLongOrNull()
    }

    fun requireLong(key: String): Long = requireNotNull(optionalLong(key)) {
        "Missing or invalid long argument '$key'."
    }

    fun optionalFloat(key: String): Float? = when {
        !extras.containsKey(key) -> null
        raw(key) is Number -> (raw(key) as Number).toFloat()
        else -> extras.getString(key)?.toFloatOrNull()
    }

    fun requireFloat(key: String): Float = requireNotNull(optionalFloat(key)) {
        "Missing or invalid float argument '$key'."
    }

    fun optionalInt(key: String): Int? = optionalLong(key)?.let { value ->
        require(value in Int.MIN_VALUE..Int.MAX_VALUE) { "Integer argument '$key' is out of range." }
        value.toInt()
    }

    fun optionalBoolean(key: String): Boolean? = if (extras.containsKey(key)) {
        requireBoolean(key)
    } else {
        null
    }

    fun boolean(key: String, default: Boolean): Boolean = when {
        !extras.containsKey(key) -> default
        raw(key) is Boolean -> extras.getBoolean(key)
        else -> extras.getString(key)?.toBooleanStrictOrNull() ?: default
    }

    fun requireBoolean(key: String): Boolean {
        require(extras.containsKey(key)) { "Missing boolean argument '$key'." }
        return when (val value = raw(key)) {
            is Boolean -> value
            is String -> value.toBooleanStrictOrNull()
                ?: error("Invalid boolean argument '$key'.")
            else -> error("Invalid boolean argument '$key'.")
        }
    }

    @Suppress("DEPRECATION")
    private fun raw(key: String): Any? = extras.get(key)
}

private fun parseStemGains(encoded: String): Map<String, Float> {
    val result = linkedMapOf<String, Float>()
    encoded.split(',').forEach { token ->
        val parts = token.trim().split('=', limit = 2)
        require(parts.size == 2 && parts[0].isNotBlank()) {
            "Invalid stem gain '$token'; expected stem_id=0..1."
        }
        val gain = parts[1].toFloatOrNull()
        require(gain != null && gain.isFinite() && gain in 0f..1f) {
            "Invalid gain for '${parts[0]}'; expected a number from 0 to 1."
        }
        require(result.put(parts[0], gain) == null) {
            "Duplicate stem gain ID '${parts[0]}'."
        }
    }
    require(result.isNotEmpty()) { "No stem gains were supplied." }
    return result
}

private fun Song.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("title", title)
    .put("artist", artistName)
    .put("album", albumName)
    .put("path", data)
    .put("durationMs", duration)

@Suppress("DEPRECATION")
private fun bundleJson(bundle: Bundle): JSONObject {
    val json = JSONObject()
    bundle.keySet().sorted().forEach { key ->
        val value = bundle.get(key)
        json.put(
            key,
            when (value) {
                is Bundle -> bundleJson(value)
                is Array<*> -> JSONArray(value.toList())
                is FloatArray -> JSONArray(value.toList())
                is IntArray -> JSONArray(value.toList())
                is LongArray -> JSONArray(value.toList())
                is Collection<*> -> JSONArray(value)
                null -> JSONObject.NULL
                else -> value
            },
        )
    }
    return json
}

private fun Throwable.debugCode(): String = when (this) {
    is IllegalArgumentException -> "invalid_argument"
    is IllegalStateException -> "invalid_state"
    is SecurityException -> "permission_denied"
    is java.util.concurrent.TimeoutException -> "timeout"
    else -> "internal_error"
}

private inline fun captureStateSection(block: () -> JSONObject): JSONObject =
    runCatching(block).getOrElse { error ->
        JSONObject()
            .put("available", false)
            .put("error", error.message ?: error::class.java.name)
    }
