package com.mardous.booming.debug

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import androidx.media3.common.C
import com.mardous.booming.data.local.repository.Repository
import com.mardous.booming.data.model.Song
import com.mardous.booming.playback.Playback
import com.mardous.booming.ui.screen.MainActivity
import com.mardous.booming.ui.screen.player.SourceSeparationForegroundWorkerDebugBridge
import org.json.JSONArray
import org.json.JSONObject
import org.koin.java.KoinJavaComponent.get

class SourceSeparationDebugControlProvider : ContentProvider() {
    private val mediaClientLock = Any()
    private var mediaClient: SourceSeparationDebugMediaClient? = null

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        enforceDebugCaller()
        val arguments = DebugArguments(arg, extras ?: Bundle.EMPTY)
        return runCatching {
            execute(method.trim().lowercase(), arguments)
        }.getOrElse { error ->
            SourceSeparationDebugProtocol.failure(
                code = error.debugCode(),
                message = error.message ?: error::class.java.simpleName,
            )
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
            val result = client.send(
                Playback.SET_SOURCE_SEPARATION_PLAYBACK_ENABLED,
                Bundle().apply {
                    putBoolean(Playback.EXTRA_SOURCE_SEPARATION_ENABLED, enabled)
                    putBoolean(Playback.EXTRA_SOURCE_SEPARATION_SHOW_MESSAGE, false)
                    putBoolean(
                        Playback.EXTRA_SOURCE_SEPARATION_AUTO_SYNC_ON_TRANSITION,
                        args.boolean("auto_sync", true),
                    )
                    putBoolean(
                        Playback.EXTRA_SOURCE_SEPARATION_EXPECT_PROCESSING,
                        args.boolean("expect_processing", enabled),
                    )
                    args.optionalFloat("blend")?.let {
                        putFloat(Playback.EXTRA_SOURCE_SEPARATION_BLEND, it.coerceIn(0f, 1f))
                    }
                },
            )
            sessionResult(result.resultCode, result.extras)
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
            val result = client.send(
                Playback.SET_SOURCE_SEPARATION_BLEND,
                Bundle().apply {
                    putFloat(
                        Playback.EXTRA_SOURCE_SEPARATION_BLEND,
                        args.requireFloat("blend").coerceIn(0f, 1f),
                    )
                    putBoolean(
                        Playback.EXTRA_SOURCE_SEPARATION_PERSIST_BLEND,
                        args.boolean("persist", true),
                    )
                },
            )
            sessionResult(result.resultCode, result.extras)
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
        "ui.launch" -> {
            providerContext().startActivity(
                Intent(providerContext(), MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
            SourceSeparationDebugProtocol.success(message = "Main activity launched.")
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

    private inline fun withMediaClient(
        block: (SourceSeparationDebugMediaClient) -> Bundle,
    ): Bundle = synchronized(mediaClientLock) {
        val client = mediaClient ?: SourceSeparationDebugMediaClient(providerContext()).also {
            mediaClient = it
        }
        block(client)
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
}

internal class DebugArguments(
    private val arg: String?,
    private val extras: Bundle,
) {
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
