package com.mardous.booming.playback

object Playback {
    // Custom commands
    const val TOGGLE_SHUFFLE = "com.mardous.booming.command.shuffle.toggle"
    const val CYCLE_REPEAT = "com.mardous.booming.command.repeat.cycle"
    const val TOGGLE_FAVORITE = "com.mardous.booming.command.toggle_favorite"
    const val RESTORE_PLAYBACK = "com.mardous.booming.command.restore_playback"
    const val AWAIT_PLAYBACK_RESTORATION =
        "com.mardous.booming.command.await_playback_restoration"

    const val SET_UNSHUFFLED_ORDER = "com.mardous.booming.command.set.unshuffled_order"
    const val SET_STOP_POSITION = "com.mardous.booming.command.set.stop_position"
    const val SEPARATE_CURRENT_SONG_OFFLINE = "com.mardous.booming.command.separate_current_song_offline"
    const val SET_SOURCE_SEPARATION_PLAYBACK_ENABLED =
        "com.mardous.booming.command.source_separation.playback_enabled"
    const val SYNC_SOURCE_SEPARATION_PLAYBACK =
        "com.mardous.booming.command.source_separation.sync_playback"
    const val CLEAN_SOURCE_SEPARATION_TEMPORARY_CACHE =
        "com.mardous.booming.command.source_separation.clean_temporary_cache"
    const val SET_SOURCE_SEPARATION_BLEND = "com.mardous.booming.command.source_separation.blend"
    const val NOTIFY_SOURCE_SEPARATION_CACHE_DELETED =
        "com.mardous.booming.command.source_separation.cache_deleted"
    const val TRACE_SOURCE_SEPARATION_PLAYBACK_MARKER =
        "com.mardous.booming.command.source_separation.trace_marker"

    // Custom events
    const val EVENT_MEDIA_CONTENT_CHANGED = "com.mardous.booming.event.media_content_changed"
    const val EVENT_FAVORITE_CONTENT_CHANGED = "com.mardous.booming.event.favorite_content_changed"
    const val EVENT_PLAYBACK_RESTORED = "com.mardous.booming.event.playback_restored"
    const val EVENT_PLAYBACK_STARTED = "com.mardous.booming.event.playback_started"
    const val EVENT_SOURCE_SEPARATION_PLAYBACK_CHANGED =
        "com.mardous.booming.event.source_separation.playback_changed"

    // Source separation extras
    const val EXTRA_SOURCE_SEPARATION_ENABLED = "source_separation_enabled"
    const val EXTRA_SOURCE_SEPARATION_BLEND = "source_separation_blend"
    const val EXTRA_SOURCE_SEPARATION_PROCESSING = "source_separation_processing"
    const val EXTRA_SOURCE_SEPARATION_SONG_ID = "source_separation_song_id"
    const val EXTRA_SOURCE_SEPARATION_MESSAGE = "source_separation_message"
    const val EXTRA_SOURCE_SEPARATION_SHOW_MESSAGE = "source_separation_show_message"
    const val EXTRA_SOURCE_SEPARATION_AUTO_SYNC_ON_TRANSITION =
        "source_separation_auto_sync_on_transition"
    const val EXTRA_SOURCE_SEPARATION_ALLOW_NEW_SESSION =
        "source_separation_allow_new_session"
    const val EXTRA_SOURCE_SEPARATION_EXPECT_PROCESSING =
        "source_separation_expect_processing"
    const val EXTRA_SOURCE_SEPARATION_CACHE_KEY = "source_separation_cache_key"
    const val EXTRA_SOURCE_SEPARATION_PERSIST_BLEND = "source_separation_persist_blend"
    const val EXTRA_SOURCE_SEPARATION_TRACE_MARKER = "source_separation_trace_marker"
}
