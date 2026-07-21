package com.mardous.booming.util.backup

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

enum class PortablePreferenceType {
    Boolean,
    Integer,
    Long,
    Float,
    String,
    StringSet,
}

data class PortablePreferenceDefinition(
    val key: String,
    val type: PortablePreferenceType,
    val defaultValue: JsonElement? = null,
    val dynamicDefault: String? = null,
    val introducedInSchema: Int = 1,
) {
    init {
        require((defaultValue == null) != (dynamicDefault == null)) {
            "Exactly one default source is required for $key"
        }
    }
}

enum class NonBackupDataClass {
    ModelWeight,
    InstalledModelInventory,
    GeneratedSeparationCache,
    PerSongBlend,
    DownloadAndWorkerState,
    RuntimeStatistics,
    DebugAndPlaybackState,
}

data class NonBackupRule(
    val id: String,
    val dataClass: NonBackupDataClass,
    val storagePattern: String,
    val reason: String,
)

object BackupSettingsPolicy {
    val commonSettingsV1: List<PortablePreferenceDefinition> = listOf(
        bool("adaptive_controls", false),
        dynamic("add_extra_controls", PortablePreferenceType.Boolean, "@bool/is_tablet"),
        integer("album_minimum_songs", 1),
        string("album_shuffle_mode", "shuffle_albums"),
        bool("allow_online_album_covers", false),
        bool("allow_online_artist_images", true),
        bool("animate_player_control", true),
        string("appbar_mode", "compact"),
        integer("artist_minimum_songs", 1),
        string("artist_shuffle_mode", "shuffle_all"),
        bool("betterlyrics_enabled", false),
        bool("black_theme", false),
        bool("blacklist_enabled", true),
        bool("carousel_effect", false),
        bool("circle_play_button", false),
        bool("clear_queue_on_completion", false),
        dynamic("cover_double_tap_action", PortablePreferenceType.String, "NowPlayingAction default"),
        dynamic("cover_left_double_tap_action", PortablePreferenceType.String, "NowPlayingAction default"),
        dynamic("cover_long_press_action", PortablePreferenceType.String, "NowPlayingAction default"),
        dynamic("cover_right_double_tap_action", PortablePreferenceType.String, "NowPlayingAction default"),
        dynamic("cover_single_tap_action", PortablePreferenceType.String, "NowPlayingAction default"),
        bool("display_album_title", true),
        bool("display_extra_info", false),
        bool("display_next_song", true),
        bool("enable_history_playlist", true),
        bool("enable_karaoke_style", false),
        bool("enable_rotation_lock", false),
        bool("enable_scrolling_text", false),
        bool("enable_syllable_lyrics", false),
        bool("experimental_updates", false),
        bool("force_utf8_encoding_for_lyrics", true),
        string("general_theme", "auto"),
        string("history_interval", "this_month"),
        bool("hold_tab_to_search", true),
        bool("ignore_articles_when_sorting", false),
        bool("ignore_audio_focus", false),
        bool("ignore_blank_lines_in_lyrics", false),
        bool("ignore_media_store", false),
        dynamic("instrumental_track_identifiers", PortablePreferenceType.String, "localized instrumental identifiers"),
        string("language_name", "auto"),
        bool("larger_header_image", false),
        string("last_added_interval", "this_month"),
        bool("lastfm_info_enabled", true),
        bool("lastfm_now_playing_enabled", false),
        bool("lastfm_scrobbling_enabled", false),
        dynamic("library_categories", PortablePreferenceType.String, "default serialized library categories"),
        bool("listenbrainz_now_playing_enabled", false),
        bool("listenbrainz_scrobbling_enabled", false),
        bool("lrclib_enabled", true),
        bool("lyrically_enabled", false),
        string("lyrics_background_effect", "none"),
        bool("lyrics_center_current_line", false),
        bool("lyrics_center_horizontally", false),
        integer("lyrics_line_spacing", 40),
        bool("lyrics_progressive_coloring", false),
        bool("lyrics_resume_on_seek", false),
        bool("lyrics_show_translation", true),
        bool("lyrics_show_transliteration", false),
        bool("lyrics_text_blur", false),
        bool("lyrics_text_shadow", false),
        bool("lyrics_use_custom_font", false),
        bool("mark_instrumental_tracks_by_title", false),
        dynamic("material_you", PortablePreferenceType.Boolean, "@bool/md3_supported"),
        bool("mini_player_swipe_to_skip", true),
        integer("minimum_song_duration", 15),
        bool("mp3_index_seeking", false),
        dynamic("network_features", PortablePreferenceType.Boolean, "@bool/network_features_enabled_by_default"),
        dynamic("now_playing_corner_radius", PortablePreferenceType.Integer, "@integer/now_playing_corner_radius"),
        dynamic("now_playing_extra_info", PortablePreferenceType.String, "default serialized metadata fields"),
        dynamic("now_playing_screen", PortablePreferenceType.String, "NowPlayingScreen.Default"),
        bool("now_playing_small_image", false),
        integer("on_clear_queue_action", 0),
        integer("on_song_click_action", 1),
        bool("open_on_play", false),
        bool("pause_on_bluetooth_disconnect", true),
        bool("pause_on_disconnect", true),
        bool("pause_on_zero_volume", false),
        bool("play_all_songs_when_searching", false),
        string("play_on_startup_mode", "never"),
        bool("play_option_always_visible", false),
        bool("play_option_whole_list", false),
        dynamic("player_blur_radius", PortablePreferenceType.Integer, "@integer/max_player_blur"),
        bool("prefer_album_artist_name_on_np", false),
        bool("prefer_remaining_time", false),
        string("preferred_image_size", "medium"),
        string("preferred_lyrics_file_format", "ttml"),
        string("queue_next_mode", "1"),
        dynamic("recursive_folder_actions", PortablePreferenceType.StringSet, "default recursive folder actions"),
        bool("remember_last_page", true),
        bool("remember_shuffle_mode", true),
        bool("resume_on_bluetooth_connect", false),
        bool("resume_on_connect", false),
        bool("rewind_with_back", true),
        integer("seek_interval", 10),
        bool("squiggly_seek_bar", false),
        bool("stop_when_closed_from_recents", false),
        bool("swipe_anywhere", false),
        bool("swipe_down_to_dismiss", true),
        bool("swipe_on_cover", true),
        bool("swipe_up_queue", false),
        bool("synced_lyrics_bold_font", false),
        integer("synced_lyrics_font_size_full", 24),
        integer("synced_lyrics_font_size_player", 20),
        string("tab_titles_mode", "selected"),
        bool("trash_music_files", false),
        bool("unsynced_lyrics_bold_font", false),
        integer("unsynced_lyrics_font_size_full", 20),
        integer("unsynced_lyrics_font_size_player", 16),
        string("update_search_mode", "weekly"),
        bool("use_custom_font", true),
        bool("use_folder_art", false),
        bool("whitelist_enabled", true),
        bool("widget_dynamic_colors", false),
        integer("widget_image_corner_radius", 8),
        string("widget_small_layout_style", "simplified"),
        dynamic("widget_third_line_content", PortablePreferenceType.String, "default widget metadata field"),
        bool("wifi_only_network", true),
    )

    val sourceSeparationSettingsV1: List<PortablePreferenceDefinition> = listOf(
        bool("source_separation.panel_entry_visible", false),
        bool("source_separation.quick_controls_visible", true),
        bool("source_separation.playback_enabled", false),
        float("source_separation.global_blend", 0.5f),
        bool("source_separation.remember_per_song", true),
        bool("source_separation.auto_start", true),
        bool("source_separation.auto_flac_compression", true),
        bool("source_separation.show_snackbar_progress", false),
        bool("source_separation.show_snackbar_messages", false),
        long("source_separation.mixed_output_preroll_ms", 400L),
        long("source_separation.hydrated_mixed_output_preroll_ms", 0L),
        integer("source_separation.playback_ready_window_count", 2),
        bool("source_separation.auto_cache_cleanup", true),
        integer("source_separation.auto_cache_cleanup_partial_limit", 5),
        integer("source_separation.auto_cache_cleanup_completed_limit", 10),
    )

    val nonBackupPreferenceKeys: Set<String> = setOf(
        "source_separation.window_decode",
        "source_separation.average_window_ms",
        "source_separation.average_window_sample_count",
    )

    val nonBackupPreferencePrefixes: Set<String> = setOf(
        "source_separation.per_song_blend.pending.",
        "source_separation.download.",
        "source_separation.worker.",
        "source_separation.runtime.",
    )

    val nonBackupRules: List<NonBackupRule> = listOf(
        NonBackupRule(
            id = "model-weights",
            dataClass = NonBackupDataClass.ModelWeight,
            storagePattern = "files/source-separation/models/**",
            reason = "Large weights require an explicit download or import",
        ),
        NonBackupRule(
            id = "installed-model-inventory",
            dataClass = NonBackupDataClass.InstalledModelInventory,
            storagePattern = "files/source-separation/models/**/manifest.json",
            reason = "Installed state is device-local and reconstructable",
        ),
        NonBackupRule(
            id = "canonical-generated-cache",
            dataClass = NonBackupDataClass.GeneratedSeparationCache,
            storagePattern = "externalCacheDir/source-separation/**; cacheDir/source-separation/**",
            reason = "All generated separation output is disposable cache data",
        ),
        NonBackupRule(
            id = "legacy-generated-cache",
            dataClass = NonBackupDataClass.GeneratedSeparationCache,
            storagePattern = "externalFilesDir/Music/source-separation/**",
            reason = "Current generated output is non-portable and the new layout will not migrate it",
        ),
        NonBackupRule(
            id = "per-song-blend",
            dataClass = NonBackupDataClass.PerSongBlend,
            storagePattern = "**/playback-settings.json; source_separation.per_song_blend.pending.*",
            reason = "Per-song blend belongs to its disposable model-aware cache entry",
        ),
        NonBackupRule(
            id = "download-worker-state",
            dataClass = NonBackupDataClass.DownloadAndWorkerState,
            storagePattern = "source_separation.download.*; source_separation.worker.*; **/*.part",
            reason = "In-flight work cannot be resumed safely on another installation",
        ),
        NonBackupRule(
            id = "runtime-statistics",
            dataClass = NonBackupDataClass.RuntimeStatistics,
            storagePattern = "source_separation.average_window_*; **/timing*.json",
            reason = "Performance measurements are device-specific runtime data",
        ),
        NonBackupRule(
            id = "debug-playback-state",
            dataClass = NonBackupDataClass.DebugAndPlaybackState,
            storagePattern = "source_separation.window_decode; **/debug/**; current playback state",
            reason = "Debug and current playback state are transient",
        ),
    )

    val commonSettingsByKey: Map<String, PortablePreferenceDefinition> =
        commonSettingsV1.associateBy(PortablePreferenceDefinition::key)

    val sourceSeparationSettingsByKey: Map<String, PortablePreferenceDefinition> =
        sourceSeparationSettingsV1.associateBy(PortablePreferenceDefinition::key)

    fun isCommonSettingAllowed(key: String): Boolean = key in commonSettingsByKey

    fun isSourceSeparationSettingAllowed(key: String): Boolean =
        key in sourceSeparationSettingsByKey

    fun isExplicitlyNonBackupPreference(key: String): Boolean =
        key in nonBackupPreferenceKeys || nonBackupPreferencePrefixes.any(key::startsWith)

    private fun bool(key: String, value: Boolean) = definition(
        key,
        PortablePreferenceType.Boolean,
        JsonPrimitive(value),
    )

    private fun integer(key: String, value: Int) = definition(
        key,
        PortablePreferenceType.Integer,
        JsonPrimitive(value),
    )

    private fun long(key: String, value: Long) = definition(
        key,
        PortablePreferenceType.Long,
        JsonPrimitive(value),
    )

    private fun float(key: String, value: Float) = definition(
        key,
        PortablePreferenceType.Float,
        JsonPrimitive(value),
    )

    private fun string(key: String, value: String) = definition(
        key,
        PortablePreferenceType.String,
        JsonPrimitive(value),
    )

    @Suppress("unused")
    private fun stringSet(key: String, value: Set<String>) = definition(
        key,
        PortablePreferenceType.StringSet,
        JsonArray(value.sorted().map(::JsonPrimitive)),
    )

    private fun dynamic(
        key: String,
        type: PortablePreferenceType,
        source: String,
    ) = PortablePreferenceDefinition(
        key = key,
        type = type,
        dynamicDefault = source,
    )

    private fun definition(
        key: String,
        type: PortablePreferenceType,
        defaultValue: JsonElement,
    ) = PortablePreferenceDefinition(
        key = key,
        type = type,
        defaultValue = defaultValue,
    )
}
