package com.mardous.booming.debug

import android.content.SharedPreferences
import androidx.core.content.edit
import com.mardous.booming.data.model.Song
import com.mardous.booming.playback.Playback
import com.mardous.booming.playback.SourceSeparationAacSeekMode
import com.mardous.booming.separation.SourceSeparationRuntimeFacade
import com.mardous.booming.separation.SourceSeparationCompressionFormat
import com.mardous.booming.separation.SourceSeparationCacheModelActivator
import com.mardous.booming.separation.SourceSeparationRuntimeSongResolution
import com.mardous.booming.separation.SourceSeparationMixModelKey
import com.mardous.booming.separation.SourceSeparationModelMixSettingsStore
import com.mardous.booming.separation.SourceSeparationMultiStemPlaybackSelectionStore
import com.mardous.booming.separation.SourceSeparationStemGainPolicy
import com.mardous.booming.separation.sourceSeparationCompressionFormat
import com.mardous.booming.separation.model.preset.SourceSeparationPresetRepository
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheFlacPromotionResult
import com.mardous.booming.separation.cache.v2.SourceSeparationCacheMutationResult
import com.mardous.booming.separation.cache.v2.SourceSeparationModelAwareCacheEntry
import com.mardous.booming.separation.cache.v2.promoteSourceSeparationCacheWhenAvailable
import com.mardous.booming.ui.screen.player.SourceSeparationForegroundWorkerDebugBridge
import com.mardous.booming.util.DEFAULT_SOURCE_SEPARATION_AUTO_CACHE_CLEANUP
import com.mardous.booming.util.DEFAULT_SOURCE_SEPARATION_AUTO_CACHE_CLEANUP_COMPLETED_LIMIT
import com.mardous.booming.util.DEFAULT_SOURCE_SEPARATION_AUTO_CACHE_CLEANUP_PARTIAL_LIMIT
import com.mardous.booming.util.DEFAULT_SOURCE_SEPARATION_AUTO_START
import com.mardous.booming.util.DEFAULT_SOURCE_SEPARATION_GPU_ENABLED
import com.mardous.booming.util.DEFAULT_SOURCE_SEPARATION_AAC_FAST_SEEK_READY_FRAMES
import com.mardous.booming.util.DEFAULT_SOURCE_SEPARATION_AAC_FAST_SEEK_BLOCK_FRAMES
import com.mardous.booming.util.DEFAULT_SOURCE_SEPARATION_AAC_SEEK_MODE
import com.mardous.booming.util.DEFAULT_SOURCE_SEPARATION_MIXED_OUTPUT_PREROLL_MS
import com.mardous.booming.util.DEFAULT_SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT
import com.mardous.booming.util.DEFAULT_SOURCE_SEPARATION_WINDOW_DECODE
import com.mardous.booming.util.MAX_SOURCE_SEPARATION_AAC_FAST_SEEK_READY_FRAMES
import com.mardous.booming.util.MAX_SOURCE_SEPARATION_AAC_FAST_SEEK_BLOCK_FRAMES
import com.mardous.booming.util.MAX_SOURCE_SEPARATION_MIXED_OUTPUT_PREROLL_MS
import com.mardous.booming.util.MAX_SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT
import com.mardous.booming.util.MIN_SOURCE_SEPARATION_AUTO_CACHE_CLEANUP_LIMIT
import com.mardous.booming.util.MIN_SOURCE_SEPARATION_AAC_FAST_SEEK_READY_FRAMES
import com.mardous.booming.util.MIN_SOURCE_SEPARATION_AAC_FAST_SEEK_BLOCK_FRAMES
import com.mardous.booming.util.MIN_SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT
import com.mardous.booming.util.SOURCE_SEPARATION_AUTO_CACHE_CLEANUP
import com.mardous.booming.util.SOURCE_SEPARATION_AUTO_CACHE_CLEANUP_COMPLETED_LIMIT
import com.mardous.booming.util.SOURCE_SEPARATION_AUTO_CACHE_CLEANUP_PARTIAL_LIMIT
import com.mardous.booming.util.SOURCE_SEPARATION_AUTO_FLAC_COMPRESSION
import com.mardous.booming.util.SOURCE_SEPARATION_COMPRESSION_FORMAT
import com.mardous.booming.util.SOURCE_SEPARATION_AUTO_START
import com.mardous.booming.util.SOURCE_SEPARATION_GPU_ENABLED
import com.mardous.booming.util.SOURCE_SEPARATION_AAC_FAST_SEEK_READY_FRAMES
import com.mardous.booming.util.SOURCE_SEPARATION_AAC_FAST_SEEK_BLOCK_FRAMES
import com.mardous.booming.util.SOURCE_SEPARATION_AAC_SEEK_MODE
import com.mardous.booming.util.SOURCE_SEPARATION_MIXED_OUTPUT_PREROLL_MS
import com.mardous.booming.util.SOURCE_SEPARATION_PLAYBACK_ENABLED
import com.mardous.booming.util.SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT
import com.mardous.booming.util.SOURCE_SEPARATION_REMEMBER_PER_SONG
import com.mardous.booming.util.SOURCE_SEPARATION_SHOW_SNACKBAR_MESSAGES
import com.mardous.booming.util.SOURCE_SEPARATION_SHOW_SNACKBAR_PROGRESS
import com.mardous.booming.util.SOURCE_SEPARATION_WINDOW_DECODE
import org.json.JSONArray
import org.json.JSONObject
import org.koin.java.KoinJavaComponent.get

internal class SourceSeparationDebugResourceController(
    private val operations: SourceSeparationDebugOperationRegistry,
    private val mediaClient: () -> SourceSeparationDebugMediaClient,
) {
    private val runtime: SourceSeparationRuntimeFacade
        get() = get(SourceSeparationRuntimeFacade::class.java)
    private val cacheModelActivator: SourceSeparationCacheModelActivator
        get() = get(SourceSeparationCacheModelActivator::class.java)
    private val preferences: SharedPreferences
        get() = get(SharedPreferences::class.java)
    private val mixSettings: SourceSeparationModelMixSettingsStore
        get() = get(SourceSeparationModelMixSettingsStore::class.java)
    private val multiStemSelection: SourceSeparationMultiStemPlaybackSelectionStore
        get() = get(SourceSeparationMultiStemPlaybackSelectionStore::class.java)
    private val presetRepository: SourceSeparationPresetRepository
        get() = get(SourceSeparationPresetRepository::class.java)

    fun settings(): JSONObject {
        val prefs = preferences
        return JSONObject()
            .put("mixMode", prefs.sourceSeparationMixMode())
            .put(
                "autoStart",
                prefs.getBoolean(SOURCE_SEPARATION_AUTO_START, DEFAULT_SOURCE_SEPARATION_AUTO_START),
            )
            .put(
                "gpuEnabled",
                prefs.getBoolean(SOURCE_SEPARATION_GPU_ENABLED, DEFAULT_SOURCE_SEPARATION_GPU_ENABLED),
            )
            .put(
                "windowDecodeEnabled",
                prefs.getBoolean(
                    SOURCE_SEPARATION_WINDOW_DECODE,
                    DEFAULT_SOURCE_SEPARATION_WINDOW_DECODE,
                ),
            )
            .put(
                "autoFlacCompression",
                prefs.getBoolean(SOURCE_SEPARATION_AUTO_FLAC_COMPRESSION, true),
            )
            .put(
                "compressionFormat",
                prefs.sourceSeparationCompressionFormat().preferenceValue(),
            )
            .put(
                "showSnackbarProgress",
                prefs.getBoolean(SOURCE_SEPARATION_SHOW_SNACKBAR_PROGRESS, false),
            )
            .put(
                "showSnackbarMessages",
                prefs.getBoolean(SOURCE_SEPARATION_SHOW_SNACKBAR_MESSAGES, false),
            )
            .put(
                "mixedOutputPrerollMs",
                prefs.getLong(
                    SOURCE_SEPARATION_MIXED_OUTPUT_PREROLL_MS,
                    DEFAULT_SOURCE_SEPARATION_MIXED_OUTPUT_PREROLL_MS,
                ),
            )
            .put(
                "aacFastSeekReadyFrames",
                prefs.getInt(
                    SOURCE_SEPARATION_AAC_FAST_SEEK_READY_FRAMES,
                    DEFAULT_SOURCE_SEPARATION_AAC_FAST_SEEK_READY_FRAMES,
                ),
            )
            .put(
                "aacFastSeekBlockFrames",
                prefs.getInt(
                    SOURCE_SEPARATION_AAC_FAST_SEEK_BLOCK_FRAMES,
                    DEFAULT_SOURCE_SEPARATION_AAC_FAST_SEEK_BLOCK_FRAMES,
                ),
            )
            .put(
                "aacSeekMode",
                SourceSeparationAacSeekMode.fromPreference(
                    prefs.getString(
                        SOURCE_SEPARATION_AAC_SEEK_MODE,
                        DEFAULT_SOURCE_SEPARATION_AAC_SEEK_MODE,
                    ),
                ).preferenceValue,
            )
            .put(
                "playbackReadyWindowCount",
                prefs.getInt(
                    SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT,
                    DEFAULT_SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT,
                ),
            )
            .put(
                "autoCacheCleanup",
                prefs.getBoolean(
                    SOURCE_SEPARATION_AUTO_CACHE_CLEANUP,
                    DEFAULT_SOURCE_SEPARATION_AUTO_CACHE_CLEANUP,
                ),
            )
            .put(
                "partialCacheLimit",
                prefs.getInt(
                    SOURCE_SEPARATION_AUTO_CACHE_CLEANUP_PARTIAL_LIMIT,
                    DEFAULT_SOURCE_SEPARATION_AUTO_CACHE_CLEANUP_PARTIAL_LIMIT,
                ),
            )
            .put(
                "completedCacheLimit",
                prefs.getInt(
                    SOURCE_SEPARATION_AUTO_CACHE_CLEANUP_COMPLETED_LIMIT,
                    DEFAULT_SOURCE_SEPARATION_AUTO_CACHE_CLEANUP_COMPLETED_LIMIT,
                ),
            )
            .put("npuSupported", false)
            .put("npuEnabled", false)
    }

    fun updateSettings(args: DebugArguments): JSONObject {
        val mode = args.optionalString("mix_mode")?.normalizeMixMode()
        val autoStart = args.optionalBoolean("auto_start")
        val gpuEnabled = args.optionalBoolean("gpu_enabled")
        val windowDecode = args.optionalBoolean("window_decode")
        val autoFlac = args.optionalBoolean("auto_flac")
        val compressionFormatName = args.optionalString("compression_format")
        val compressionFormat = compressionFormatName?.let { name ->
            SourceSeparationCompressionFormat.entries.firstOrNull {
                it.name.equals(name, ignoreCase = true) ||
                        it.preferenceValue().equals(name, ignoreCase = true)
            } ?: error("Unknown compression_format '$name'.")
        }
        val snackbarProgress = args.optionalBoolean("snackbar_progress")
        val snackbarMessages = args.optionalBoolean("snackbar_messages")
        val prerollMs = args.optionalLong("preroll_ms")?.also { value ->
            require(value in 0L..MAX_SOURCE_SEPARATION_MIXED_OUTPUT_PREROLL_MS) {
                "preroll_ms must be between 0 and $MAX_SOURCE_SEPARATION_MIXED_OUTPUT_PREROLL_MS."
            }
        }
        val aacSeekReadyFrames = args.optionalInt("aac_seek_ready_frames")?.also { value ->
            require(
                value == DEFAULT_SOURCE_SEPARATION_AAC_FAST_SEEK_READY_FRAMES ||
                    value in MIN_SOURCE_SEPARATION_AAC_FAST_SEEK_READY_FRAMES..
                    MAX_SOURCE_SEPARATION_AAC_FAST_SEEK_READY_FRAMES,
            ) {
                "aac_seek_ready_frames must be 0 or between " +
                    "$MIN_SOURCE_SEPARATION_AAC_FAST_SEEK_READY_FRAMES and " +
                    "$MAX_SOURCE_SEPARATION_AAC_FAST_SEEK_READY_FRAMES."
            }
        }
        val aacSeekBlockFrames = args.optionalInt("aac_seek_block_frames")?.also { value ->
            require(
                value in MIN_SOURCE_SEPARATION_AAC_FAST_SEEK_BLOCK_FRAMES..
                    MAX_SOURCE_SEPARATION_AAC_FAST_SEEK_BLOCK_FRAMES,
            ) {
                "aac_seek_block_frames must be between " +
                    "$MIN_SOURCE_SEPARATION_AAC_FAST_SEEK_BLOCK_FRAMES and " +
                    "$MAX_SOURCE_SEPARATION_AAC_FAST_SEEK_BLOCK_FRAMES."
            }
        }
        val aacSeekMode = args.optionalString("aac_seek_mode")?.let { value ->
            SourceSeparationAacSeekMode.entries.firstOrNull { mode ->
                mode.preferenceValue.equals(value, ignoreCase = true)
            } ?: error("aac_seek_mode must be previous, closest, or next.")
        }
        val readyWindows = args.optionalInt("ready_windows")?.also { value ->
            require(value in MIN_SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT..
                MAX_SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT
            ) {
                "ready_windows must be between $MIN_SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT " +
                    "and $MAX_SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT."
            }
        }
        val autoCleanup = args.optionalBoolean("auto_cleanup")
        val partialLimit = args.optionalInt("partial_limit")?.also { value ->
            require(value >= MIN_SOURCE_SEPARATION_AUTO_CACHE_CLEANUP_LIMIT) {
                "partial_limit must be at least $MIN_SOURCE_SEPARATION_AUTO_CACHE_CLEANUP_LIMIT."
            }
        }
        val completedLimit = args.optionalInt("completed_limit")?.also { value ->
            require(value >= MIN_SOURCE_SEPARATION_AUTO_CACHE_CLEANUP_LIMIT) {
                "completed_limit must be at least $MIN_SOURCE_SEPARATION_AUTO_CACHE_CLEANUP_LIMIT."
            }
        }
        if (args.has("npu_enabled")) {
            require(args.requireBoolean("npu_enabled").not()) {
                "Booming SS does not currently expose an NPU product runtime."
            }
        }
        require(
            listOfNotNull(
                mode,
                autoStart,
                gpuEnabled,
                windowDecode,
                autoFlac,
                compressionFormat,
                snackbarProgress,
                snackbarMessages,
                prerollMs,
                aacSeekReadyFrames,
                aacSeekBlockFrames,
                aacSeekMode,
                readyWindows,
                autoCleanup,
                partialLimit,
                completedLimit,
            ).isNotEmpty() || args.has("npu_enabled"),
        ) { "No supported setting was supplied." }

        val uiApplied = SourceSeparationForegroundWorkerDebugBridge.configure(
            autoStart = autoStart,
            modeName = mode,
            autoFlac = autoFlac,
            compressionFormatName = compressionFormat?.preferenceValue(),
            gpuEnabled = gpuEnabled,
            windowDecode = windowDecode,
            snackbarProgress = snackbarProgress,
            snackbarMessages = snackbarMessages,
            mixedOutputPrerollMs = prerollMs,
            readyWindowCount = readyWindows,
            autoCacheCleanup = autoCleanup,
            partialCacheLimit = partialLimit,
            completedCacheLimit = completedLimit,
        )
        if (aacSeekReadyFrames != null || aacSeekBlockFrames != null || aacSeekMode != null) {
            preferences.edit {
                aacSeekReadyFrames?.let {
                    putInt(SOURCE_SEPARATION_AAC_FAST_SEEK_READY_FRAMES, it)
                }
                aacSeekBlockFrames?.let {
                    putInt(SOURCE_SEPARATION_AAC_FAST_SEEK_BLOCK_FRAMES, it)
                }
                aacSeekMode?.let {
                    putString(SOURCE_SEPARATION_AAC_SEEK_MODE, it.preferenceValue)
                }
            }
        }
        if (!uiApplied) {
            preferences.edit {
                autoStart?.let { putBoolean(SOURCE_SEPARATION_AUTO_START, it) }
                gpuEnabled?.let { putBoolean(SOURCE_SEPARATION_GPU_ENABLED, it) }
                windowDecode?.let { putBoolean(SOURCE_SEPARATION_WINDOW_DECODE, it) }
                autoFlac?.let { putBoolean(SOURCE_SEPARATION_AUTO_FLAC_COMPRESSION, it) }
                compressionFormat?.let {
                    putString(SOURCE_SEPARATION_COMPRESSION_FORMAT, it.preferenceValue())
                    putBoolean(SOURCE_SEPARATION_AUTO_FLAC_COMPRESSION, it ==
                            SourceSeparationCompressionFormat.Flac)
                }
                snackbarProgress?.let {
                    putBoolean(SOURCE_SEPARATION_SHOW_SNACKBAR_PROGRESS, it)
                }
                snackbarMessages?.let {
                    putBoolean(SOURCE_SEPARATION_SHOW_SNACKBAR_MESSAGES, it)
                }
                prerollMs?.let { putLong(SOURCE_SEPARATION_MIXED_OUTPUT_PREROLL_MS, it) }
                readyWindows?.let {
                    putInt(SOURCE_SEPARATION_PLAYBACK_READY_WINDOW_COUNT, it)
                }
                autoCleanup?.let { putBoolean(SOURCE_SEPARATION_AUTO_CACHE_CLEANUP, it) }
                partialLimit?.let {
                    putInt(SOURCE_SEPARATION_AUTO_CACHE_CLEANUP_PARTIAL_LIMIT, it)
                }
                completedLimit?.let {
                    putInt(SOURCE_SEPARATION_AUTO_CACHE_CLEANUP_COMPLETED_LIMIT, it)
                }
                mode?.let { normalized ->
                    putBoolean(SOURCE_SEPARATION_PLAYBACK_ENABLED, normalized != "off")
                    putBoolean(SOURCE_SEPARATION_REMEMBER_PER_SONG, normalized == "per_song")
                }
            }
        }
        if (autoCleanup == true || partialLimit != null || completedLimit != null) {
            SourceSeparationForegroundWorkerDebugBridge.requestAutomaticPrune()
        }
        if (!uiApplied && mode != null) {
            val result = mediaClient().send(
                Playback.SET_SOURCE_SEPARATION_PLAYBACK_ENABLED,
                android.os.Bundle().apply {
                    putBoolean(Playback.EXTRA_SOURCE_SEPARATION_ENABLED, mode != "off")
                    putBoolean(Playback.EXTRA_SOURCE_SEPARATION_SHOW_MESSAGE, false)
                    putBoolean(Playback.EXTRA_SOURCE_SEPARATION_AUTO_SYNC_ON_TRANSITION, true)
                    putBoolean(Playback.EXTRA_SOURCE_SEPARATION_EXPECT_PROCESSING, false)
                },
            )
            check(result.resultCode == 0) {
                "PlaybackService rejected mix mode '$mode' (${result.resultCode})."
            }
        }
        return settings()
            .put("uiPath", uiApplied)
    }

    fun persistBlendFallback(blend: Float): JSONObject {
        val normalized = blend.coerceIn(0f, 1f)
        val mode = preferences.sourceSeparationMixMode()
        val model = currentMixModelKey()
        var target = "none"
        var persisted = false
        when (mode) {
            "global" -> {
                persisted = mixSettings.writeGlobalBlend(model, normalized)
                target = "global"
            }
            "per_song" -> {
                val song = SourceSeparationForegroundWorkerDebugBridge.currentSong()
                if (song != Song.emptySong) {
                    val resolved = runtime.resolve(song) as? SourceSeparationRuntimeSongResolution.Ready
                    persisted = resolved?.let { runtime.writeBlend(it.song, normalized) } == true
                    if (!persisted) {
                        persisted = mixSettings.writePendingSongBlend(model, song, normalized)
                        target = "pending_song"
                    } else {
                        target = "cache"
                    }
                }
            }
        }
        return JSONObject()
            .put("mode", mode)
            .put("target", target)
            .put("persisted", persisted)
    }

    fun persistStemGainsFallback(
        cacheKey: String,
        stemIds: List<String>,
        gainsByStemId: Map<String, Float>,
    ): JSONObject {
        val orderedGains = stemIds.map(gainsByStemId::getValue)
        val mode = preferences.sourceSeparationMixMode()
        var target = "none"
        var persisted = false
        when (mode) {
            "global" -> {
                val modelId = multiStemSelection.selectedModelId()
                if (modelId != null) {
                    mixSettings.writeGlobalStemGains(
                        model = SourceSeparationMixModelKey.multiStem(modelId),
                        stemIds = stemIds,
                        gains = orderedGains,
                    )
                    target = "global"
                    persisted = true
                }
            }
            "per_song" -> {
                val song = SourceSeparationForegroundWorkerDebugBridge.currentSong()
                val resolved = if (song == Song.emptySong) null else {
                    runtime.resolve(song) as? SourceSeparationRuntimeSongResolution.Ready
                }
                persisted = resolved?.song
                    ?.takeIf { it.cacheKey == cacheKey }
                    ?.let { runtime.writeStemGains(it, gainsByStemId) } == true
                if (persisted) {
                    target = "cache"
                } else {
                    mixSettings.writePendingStemGains(cacheKey, stemIds, orderedGains)
                    if (song != Song.emptySong) {
                        mixSettings.writePendingSongBlend(
                            currentMixModelKey(),
                            song,
                            SourceSeparationStemGainPolicy.demandBlend(orderedGains),
                        )
                    }
                    target = "pending_song"
                    persisted = true
                }
            }
        }
        return JSONObject()
            .put("mode", mode)
            .put("target", target)
            .put("persisted", persisted)
    }

    fun caches(): JSONObject {
        val currentCacheKey = currentCacheKey()
        val entries = runtime.entries()
        return JSONObject()
            .put("currentCacheKey", currentCacheKey)
            .put("totalSizeBytes", entries.sumOf(SourceSeparationModelAwareCacheEntry::sizeBytes))
            .put(
                "entries",
                JSONArray().also { array ->
                    entries.forEach { entry ->
                        array.put(entry.toJson(currentCacheKey == entry.cacheKey))
                    }
                },
            )
    }

    fun submitCacheDelete(cacheKey: String): SourceSeparationDebugOperationSnapshot {
        require(runtime.entries().any { it.cacheKey == cacheKey }) {
            "Unknown cache '$cacheKey'."
        }
        return operations.submit("cache.delete", cacheKey) {
            deleteCache(cacheKey)
        }
    }

    fun submitCacheActivation(cacheKey: String): SourceSeparationDebugOperationSnapshot {
        val entry = runtime.entries().singleOrNull { it.cacheKey == cacheKey }
            ?: throw IllegalArgumentException("Unknown cache '$cacheKey'.")
        return operations.submit("cache.activate", cacheKey) {
            stage("activate", "${entry.modelFamily.name.lowercase()}:${entry.modelId}")
            val before = cacheModelActivator.current()
            val after = cacheModelActivator.activate(entry)
            JSONObject()
                .put("cacheKey", cacheKey)
                .put("family", entry.modelFamily.name.lowercase())
                .put("modelId", entry.modelId)
                .put("artifactSha256", entry.artifactSha256)
                .put("selectionGenerationBefore", before.generation)
                .put("selectionGenerationAfter", after.generation)
                .put("exactIdentityActive", after.matches(entry.executionIdentity))
        }
    }

    fun submitCacheDeleteAll(): SourceSeparationDebugOperationSnapshot {
        val keys = runtime.entries().map(SourceSeparationModelAwareCacheEntry::cacheKey)
        require(keys.isNotEmpty()) { "There are no source-separation caches to delete." }
        return operations.submit("cache.delete_all") {
            val results = JSONArray()
            var failedCount = 0
            keys.forEachIndexed { index, key ->
                stage("delete", "${index + 1}/${keys.size}: $key")
                val result = runCatching { deleteCache(key) }
                if (result.isFailure) failedCount++
                results.put(
                    result.fold(
                        onSuccess = { it.put("ok", true) },
                        onFailure = { error ->
                            JSONObject()
                                .put("cacheKey", key)
                                .put("ok", false)
                                .put("error", error.message ?: error::class.java.simpleName)
                        },
                    ),
                )
                ensureActive()
            }
            JSONObject()
                .put("entries", results)
                .put("deletedCount", keys.size - failedCount)
                .put("failedCount", failedCount)
        }
    }

    fun submitCachePromotion(cacheKey: String): SourceSeparationDebugOperationSnapshot {
        require(runtime.entries().any { it.cacheKey == cacheKey }) {
            "Unknown cache '$cacheKey'."
        }
        return operations.submit("cache.promote", cacheKey) {
            stage("waiting_for_cache")
            val result = promoteSourceSeparationCacheWhenAvailable(
                shouldCancel = {
                    runCatching { ensureActive() }.isFailure
                },
            ) {
                runtime.promote(cacheKey) {
                    runCatching { ensureActive() }.isFailure
                }
            }
            val manifest = when (result) {
                is SourceSeparationCacheFlacPromotionResult.Completed -> result.manifest
                is SourceSeparationCacheFlacPromotionResult.AlreadyPromoted -> result.manifest
                SourceSeparationCacheFlacPromotionResult.Busy ->
                    error("Cache remained busy while waiting for FLAC promotion.")
                SourceSeparationCacheFlacPromotionResult.Unavailable ->
                    error("Cache is not eligible for FLAC promotion.")
            }
            mediaClient().send(Playback.CLEAN_SOURCE_SEPARATION_TEMPORARY_CACHE)
            JSONObject()
                .put("cacheKey", cacheKey)
                .put("state", result::class.java.simpleName)
                .put("format", manifest.output?.stems?.firstOrNull()?.promotedFormat?.name)
        }
    }

    fun submitCacheCleanup(): SourceSeparationDebugOperationSnapshot =
        operations.submit("cache.cleanup") {
            stage("cleanup")
            val before = runtime.entries().associateBy(SourceSeparationModelAwareCacheEntry::cacheKey)
            val session = mediaClient().send(Playback.CLEAN_SOURCE_SEPARATION_TEMPORARY_CACHE)
            check(session.resultCode == 0) {
                "PlaybackService rejected temporary cache cleanup (${session.resultCode})."
            }
            val after = runtime.entries().associateBy(SourceSeparationModelAwareCacheEntry::cacheKey)
            val changed = before.keys.filter { key ->
                before[key]?.sizeBytes != after[key]?.sizeBytes
            }
            JSONObject()
                .put("changedCacheKeys", JSONArray(changed))
                .put(
                    "releasedBytes",
                    before.entries.sumOf { (key, entry) ->
                        (entry.sizeBytes - (after[key]?.sizeBytes ?: 0L)).coerceAtLeast(0L)
                    },
                )
        }

    private suspend fun SourceSeparationDebugOperationContext.deleteCache(
        cacheKey: String,
    ): JSONObject {
        stage("prepare")
        val current = isCurrentCache(cacheKey)
        val usedUiPath = SourceSeparationForegroundWorkerDebugBridge
            .prepareCacheForManualDelete(cacheKey)
        if (!usedUiPath) {
            if (current) {
                preferences.edit { putBoolean(SOURCE_SEPARATION_PLAYBACK_ENABLED, false) }
                val disabled = mediaClient().send(
                    Playback.SET_SOURCE_SEPARATION_PLAYBACK_ENABLED,
                    android.os.Bundle().apply {
                        putBoolean(Playback.EXTRA_SOURCE_SEPARATION_ENABLED, false)
                        putBoolean(Playback.EXTRA_SOURCE_SEPARATION_SHOW_MESSAGE, false)
                        putBoolean(Playback.EXTRA_SOURCE_SEPARATION_EXPECT_PROCESSING, false)
                    },
                )
                check(disabled.resultCode == 0) {
                    "Unable to disable playback before deleting the current cache."
                }
            }
            SourceSeparationForegroundWorkerDebugBridge.prepareCacheForManualDeleteFallback(
                cacheKey = cacheKey,
                isCurrentCache = current,
            )
        }
        ensureActive()
        stage("delete")
        val result = runtime.delete(cacheKey)
        if (result == SourceSeparationCacheMutationResult.Completed) {
            val handledByUi = SourceSeparationForegroundWorkerDebugBridge
                .handleCacheManualDeleteResult(cacheKey, result)
            if (!handledByUi && current) {
                mediaClient().send(Playback.NOTIFY_SOURCE_SEPARATION_CACHE_DELETED)
            }
            SourceSeparationForegroundWorkerDebugBridge.clearStatusIfNotRunning()
        }
        check(result == SourceSeparationCacheMutationResult.Completed) {
            "Cache deletion ended with ${result.name.lowercase()}."
        }
        return JSONObject()
            .put("cacheKey", cacheKey)
            .put("current", current)
            .put("uiPath", usedUiPath)
            .put("result", result.name.lowercase())
    }

    private fun isCurrentCache(cacheKey: String): Boolean {
        if (currentCacheKey() == cacheKey) return true
        val song = SourceSeparationForegroundWorkerDebugBridge.currentSong()
        if (song == Song.emptySong) return false
        return (runtime.resolve(song) as? SourceSeparationRuntimeSongResolution.Ready)
            ?.song
            ?.cacheKey == cacheKey
    }

    fun currentCacheKey(): String? {
        mediaClient().debugState().getString(Playback.EXTRA_SOURCE_SEPARATION_CACHE_KEY)
            ?.takeIf(String::isNotBlank)
            ?.let { return it }
        val song = SourceSeparationForegroundWorkerDebugBridge.currentSong()
        if (song == Song.emptySong) return null
        return (runtime.resolve(song) as? SourceSeparationRuntimeSongResolution.Ready)
            ?.song
            ?.cacheKey
    }

    private fun currentMixModelKey(): SourceSeparationMixModelKey? =
        multiStemSelection.selectedModelId()
            ?.let(SourceSeparationMixModelKey::multiStem)
            ?: presetRepository.activeSelectionFlow.value.reference?.modelId
                ?.let(SourceSeparationMixModelKey::mdx)
}

private fun SharedPreferences.sourceSeparationMixMode(): String = when {
    !getBoolean(SOURCE_SEPARATION_PLAYBACK_ENABLED, false) -> "off"
    getBoolean(SOURCE_SEPARATION_REMEMBER_PER_SONG, true) -> "per_song"
    else -> "global"
}

private fun String.normalizeMixMode(): String = when (trim().lowercase()) {
    "off", "original", "disabled" -> "off"
    "global", "global_blend" -> "global"
    "per_song", "per-song", "persong", "remember" -> "per_song"
    else -> throw IllegalArgumentException("Unknown source-separation mix mode '$this'.")
}

private fun SourceSeparationModelAwareCacheEntry.toJson(current: Boolean): JSONObject =
    JSONObject()
        .put("cacheKey", cacheKey)
        .put("current", current)
        .put("songId", songId)
        .put("title", title)
        .put("artist", artist)
        .put("album", album)
        .put("family", modelFamily.name.lowercase())
        .put("modelId", modelId)
        .put("displayName", displayName)
        .put("artifactSha256", artifactSha256)
        .put("contractId", contractId)
        .put("profileRevisionId", profileRevisionId)
        .put("renderProfileId", renderProfileId)
        .put("state", state.name.lowercase())
        .put("modelAvailability", modelAvailability.name)
        .put("readySegments", readySegments)
        .put("totalSegments", totalSegments)
        .put("format", format.name.lowercase())
        .put("sizeBytes", sizeBytes)
        .put("updatedAtEpochMs", updatedAtEpochMs)
        .put("lastAccessedAtEpochMs", lastAccessedAtEpochMs)
        .put("stemLabels", JSONArray(stemLabels))
        .put("supportsStandardPlayback", supportsStandardPlayback)
