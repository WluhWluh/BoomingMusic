package com.mardous.booming.util.backup

import com.mardous.booming.separation.model.contract.SourceSeparationModelContractValidator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant

class BackupContractException(message: String) : IllegalArgumentException(message)

object BackupContractJson {
    val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        explicitNulls = false
    }

    fun decodeManifest(value: String): BackupManifestV1 = json.decodeFromString(value)

    fun decodeCommonSettings(value: String): CommonSettingsSnapshotV1 =
        json.decodeFromString(value)

    fun decodeSourceSeparationSettings(value: String): SourceSeparationSettingsSnapshotV1 =
        json.decodeFromString(value)
}

object BackupContractValidator {
    private val sha256Pattern = Regex("^[0-9a-f]{64}$")
    private val modelIdPattern = Regex("^[a-z0-9_]+$")

    fun validateManifest(manifest: BackupManifestV1): BackupManifestV1 {
        requireBackup(manifest.formatVersion == BackupFormatV1.FORMAT_VERSION) {
            "Unsupported backup format version: ${manifest.formatVersion}"
        }
        requireBackup(manifest.commonSettingsSchema == BackupFormatV1.COMMON_SETTINGS_SCHEMA) {
            "Unsupported common settings schema"
        }
        requireBackup(manifest.sourceSeparationSettingsSchema > 0) {
            "Source-separation settings schema must be positive"
        }
        requireBackup(manifest.producerPackage.isNotBlank()) { "Producer package is empty" }
        requireBackup(manifest.producerFlavor.isNotBlank()) { "Producer flavor is empty" }
        requireBackup(manifest.applicationVersion.isNotBlank()) { "Application version is empty" }
        requireBackup(runCatching { Instant.parse(manifest.generatedAtUtc) }.isSuccess) {
            "Backup generation time is not an ISO-8601 UTC instant"
        }
        requireBackup(manifest.payloads.map(BackupPayloadDescriptor::path).toSet().size == manifest.payloads.size) {
            "Backup manifest contains duplicate payload paths"
        }
        manifest.payloads.forEach { payload -> validatePayload(manifest, payload) }
        requireBackup(
            manifest.payloads.count { it.kind == BackupPayloadKinds.COMMON_SETTINGS } <= 1
        ) { "Backup manifest declares more than one common settings payload" }
        requireBackup(
            manifest.payloads.count { it.kind == BackupPayloadKinds.SOURCE_SEPARATION_SETTINGS } <= 1
        ) { "Backup manifest declares more than one source-separation settings payload" }
        return manifest
    }

    fun validateCommonSettings(
        snapshot: CommonSettingsSnapshotV1,
    ): CommonSettingsSnapshotV1 {
        requireBackup(snapshot.schemaVersion == BackupFormatV1.COMMON_SETTINGS_SCHEMA) {
            "Unsupported common settings snapshot schema"
        }
        validatePreferenceMap(snapshot.preferences, BackupSettingsPolicy.commonSettingsByKey)
        return snapshot
    }

    fun validateSourceSeparationSettings(
        snapshot: SourceSeparationSettingsSnapshotV1,
    ): SourceSeparationSettingsSnapshotV1 {
        requireBackup(
            snapshot.schemaVersion == BackupFormatV1.SOURCE_SEPARATION_SETTINGS_SCHEMA
        ) { "Unsupported source-separation settings snapshot schema" }
        validatePreferenceMap(
            snapshot.preferences,
            BackupSettingsPolicy.sourceSeparationSettingsByKey,
        )
        requireBackup(snapshot.preferences.keys.none(BackupSettingsPolicy::isExplicitlyNonBackupPreference)) {
            "Source-separation snapshot contains a non-backup preference"
        }
        snapshot.activeModel?.let(::validateActiveModelReference)
        snapshot.customProfiles.forEach(SourceSeparationModelContractValidator::validateCustomProfile)
        requireBackup(
            snapshot.customProfiles.map { it.artifact.sha256 }.toSet().size ==
                snapshot.customProfiles.size
        ) { "Custom profiles must be uniquely keyed by model SHA-256" }
        requireBackup(
            snapshot.customProfiles.map { it.profileId }.toSet().size ==
                snapshot.customProfiles.size
        ) { "Custom profile IDs must be unique" }
        snapshot.activeModel?.profileId?.let { profileId ->
            requireBackup(snapshot.customProfiles.any { it.profileId == profileId }) {
                "Active custom profile reference is not included in the portable snapshot"
            }
        }
        return snapshot
    }

    private fun validatePayload(
        manifest: BackupManifestV1,
        payload: BackupPayloadDescriptor,
    ) {
        requireSafeRelativePath(payload.path)
        requireBackup(payload.byteSize >= 0L) { "Payload size cannot be negative" }
        requireSha256(payload.sha256, "Payload ${payload.path}")
        requireBackup(!isForbiddenPayloadPath(payload.path)) {
            "Non-backup source-separation data cannot be declared as a payload"
        }
        if (payload.kind !in BackupPayloadKinds.known) {
            requireBackup(payload.optional) { "Unknown required payload kind: ${payload.kind}" }
            return
        }
        when (payload.kind) {
            BackupPayloadKinds.COMMON_SETTINGS -> {
                requireBackup(payload.path == BackupFormatV1.COMMON_SETTINGS_PATH) {
                    "Common settings payload uses the wrong path"
                }
                requireBackup(payload.schemaVersion == BackupFormatV1.COMMON_SETTINGS_SCHEMA) {
                    "Common settings payload uses the wrong schema"
                }
            }

            BackupPayloadKinds.SOURCE_SEPARATION_SETTINGS -> {
                requireBackup(payload.path == BackupFormatV1.SOURCE_SEPARATION_SETTINGS_PATH) {
                    "Source-separation settings payload uses the wrong path"
                }
                requireBackup(payload.schemaVersion == manifest.sourceSeparationSettingsSchema) {
                    "Source-separation payload schema does not match the manifest"
                }
                if (!isSupportedSourceSeparationSchema(manifest.sourceSeparationSettingsSchema)) {
                    requireBackup(payload.optional) {
                        "Unsupported source-separation settings payload must be optional"
                    }
                }
            }

            BackupPayloadKinds.LEGACY_SETTINGS_PROJECTION -> {
                requireBackup(
                    payload.path == BackupFormatV1.UPSTREAM_LEGACY_PROJECTION_PATH ||
                        payload.path == BackupFormatV1.BOOMING_SS_LEGACY_PROJECTION_PATH
                ) { "Legacy projection uses an unsupported package path" }
                requireBackup(payload.schemaVersion == null) {
                    "Legacy projection must not claim a canonical settings schema"
                }
            }

            BackupPayloadKinds.PLAYLIST -> {
                requireBackup(
                    payload.path.startsWith("${BackupFormatV1.PLAYLISTS_DIRECTORY}/") &&
                        payload.path.endsWith(".m3u", ignoreCase = true) &&
                        payload.path.count { it == '/' } == 1
                ) { "Playlist payload uses an unsupported path" }
                requireBackup(payload.schemaVersion == null) {
                    "Playlist payload must not claim a settings schema"
                }
            }

            BackupPayloadKinds.LYRICS -> {
                requireBackup(payload.path == BackupFormatV1.LYRICS_PATH) {
                    "Lyrics payload uses an unsupported path"
                }
                requireBackup(payload.schemaVersion == null) {
                    "Lyrics payload must not claim a settings schema"
                }
            }

            BackupPayloadKinds.ARTIST_IMAGE -> {
                requireBackup(
                    payload.path.startsWith("${BackupFormatV1.ARTIST_IMAGES_DIRECTORY}/") &&
                        payload.path.count { it == '/' } == 2
                ) { "Artist image payload uses an unsupported path" }
                requireBackup(payload.schemaVersion == null) {
                    "Artist image payload must not claim a settings schema"
                }
            }

            BackupPayloadKinds.ARTIST_PREFERENCES -> {
                requireBackup(payload.path == BackupFormatV1.ARTIST_PREFERENCES_PATH) {
                    "Artist preferences payload uses an unsupported path"
                }
                requireBackup(payload.schemaVersion == null) {
                    "Artist preferences payload must not claim a settings schema"
                }
            }

            else -> requireBackup(payload.schemaVersion == null) {
                "Non-settings payload must not claim a settings schema"
            }
        }
    }

    fun validatePreferenceMap(
        preferences: Map<String, JsonElement>,
        definitions: Map<String, PortablePreferenceDefinition>,
    ) {
        preferences.forEach { (key, value) ->
            val definition = definitions[key]
                ?: fail("Preference is not in the schema allowlist: $key")
            requireBackup(matchesType(value, definition.type)) {
                "Preference $key does not match ${definition.type}"
            }
        }
    }

    private fun matchesType(value: JsonElement, type: PortablePreferenceType): Boolean = when (type) {
        PortablePreferenceType.Boolean ->
            value is JsonPrimitive && !value.isString && value.booleanOrNull != null

        PortablePreferenceType.Integer ->
            value is JsonPrimitive && !value.isString && value.intOrNull != null

        PortablePreferenceType.Long ->
            value is JsonPrimitive && !value.isString && value.longOrNull != null

        PortablePreferenceType.Float ->
            value is JsonPrimitive &&
                !value.isString &&
                value.floatOrNull?.isFinite() == true

        PortablePreferenceType.String -> value is JsonPrimitive && value.isString
        PortablePreferenceType.StringSet ->
            value is JsonArray &&
                value.all { it is JsonPrimitive && it.isString } &&
                value.distinct().size == value.size
    }

    private fun validateActiveModelReference(reference: PortableActiveModelReference) {
        requireBackup(modelIdPattern.matches(reference.modelId)) {
            "Active model reference has an invalid stable ID"
        }
        requireSha256(reference.artifactSha256, "Active model reference")
        requireBackup(reference.contractSchemaVersion > 0) {
            "Active model contract schema version must be positive"
        }
        requireBackup(reference.profileId == null || reference.profileId.isNotBlank()) {
            "Active model profile reference is empty"
        }
    }

    private fun requireSafeRelativePath(path: String) {
        requireBackup(path.isNotBlank()) { "Payload path is empty" }
        requireBackup(!path.startsWith('/') && !path.startsWith('\\')) {
            "Payload path must be relative"
        }
        requireBackup('\\' !in path && ':' !in path) {
            "Payload path must use portable ZIP separators"
        }
        requireBackup(path.split('/').none { it.isEmpty() || it == "." || it == ".." }) {
            "Payload path contains an unsafe segment"
        }
    }

    private fun isForbiddenPayloadPath(path: String): Boolean {
        val normalized = path.lowercase()
        return normalized.endsWith(".tflite") ||
            normalized.endsWith(".onnx") ||
            "/models/" in normalized ||
            normalized.startsWith("source-separation/") ||
            normalized.startsWith("source_separation/entries/") ||
            normalized.endsWith("playback-settings.json") ||
            "/cache/" in normalized ||
            "/debug/" in normalized ||
            "/hydration/" in normalized ||
            normalized.endsWith(".part")
    }

    private fun requireSha256(value: String, label: String) {
        requireBackup(sha256Pattern.matches(value)) {
            "$label SHA-256 must contain 64 lowercase hexadecimal characters"
        }
    }

    fun isSupportedSourceSeparationSchema(schemaVersion: Int): Boolean =
        schemaVersion == BackupFormatV1.SOURCE_SEPARATION_SETTINGS_SCHEMA

    private inline fun requireBackup(condition: Boolean, message: () -> String) {
        if (!condition) throw BackupContractException(message())
    }

    private fun fail(message: String): Nothing = throw BackupContractException(message)
}
