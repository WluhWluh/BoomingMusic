package com.mardous.booming.util.backup

import com.mardous.booming.separation.model.contract.SourceSeparationCustomModelProfile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class BackupManifestV1(
    val formatVersion: Int,
    val commonSettingsSchema: Int,
    val sourceSeparationSettingsSchema: Int,
    val producerPackage: String,
    val producerFlavor: String,
    val applicationVersion: String,
    val generatedAtUtc: String,
    val payloads: List<BackupPayloadDescriptor>,
)

@Serializable
data class BackupPayloadDescriptor(
    val path: String,
    val kind: String,
    val schemaVersion: Int? = null,
    val byteSize: Long,
    val sha256: String,
    val optional: Boolean,
)

@Serializable
data class CommonSettingsSnapshotV1(
    val schemaVersion: Int,
    val preferences: Map<String, JsonElement>,
)

@Serializable
data class SourceSeparationSettingsSnapshotV1(
    val schemaVersion: Int,
    val preferences: Map<String, JsonElement>,
    val activeModel: PortableActiveModelReference? = null,
    val customProfiles: List<SourceSeparationCustomModelProfile> = emptyList(),
)

@Serializable
data class PortableActiveModelReference(
    val modelId: String,
    val artifactSha256: String,
    val contractSchemaVersion: Int,
    val profileId: String? = null,
)

object BackupFormatV1 {
    const val FORMAT_VERSION = 1
    const val COMMON_SETTINGS_SCHEMA = 1
    const val SOURCE_SEPARATION_SETTINGS_SCHEMA = 1

    const val MANIFEST_PATH = "backup-manifest.json"
    const val COMMON_SETTINGS_PATH = "settings/common.json"
    const val SOURCE_SEPARATION_SETTINGS_PATH = "source_separation/settings.json"
    const val UPSTREAM_LEGACY_PROJECTION_PATH =
        "prefs/com.mardous.booming_preferences.xml"
    const val BOOMING_SS_LEGACY_PROJECTION_PATH =
        "prefs/com.wluhwluh.booming.sourcesep_preferences.xml"
}

object BackupPayloadKinds {
    const val COMMON_SETTINGS = "common-settings"
    const val SOURCE_SEPARATION_SETTINGS = "source-separation-settings"
    const val PLAYLIST = "playlist"
    const val LYRICS = "lyrics"
    const val ARTIST_IMAGE = "artist-image"
    const val ARTIST_PREFERENCES = "artist-preferences"
    const val LEGACY_SETTINGS_PROJECTION = "legacy-settings-projection"

    val known: Set<String> = setOf(
        COMMON_SETTINGS,
        SOURCE_SEPARATION_SETTINGS,
        PLAYLIST,
        LYRICS,
        ARTIST_IMAGE,
        ARTIST_PREFERENCES,
        LEGACY_SETTINGS_PROJECTION,
    )
}
