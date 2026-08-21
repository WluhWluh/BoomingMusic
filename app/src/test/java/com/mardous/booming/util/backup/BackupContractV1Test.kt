package com.mardous.booming.util.backup

import com.mardous.booming.separation.model.contract.SourceSeparationCustomModelProfile
import com.mardous.booming.separation.model.contract.SourceSeparationModelCatalog
import com.mardous.booming.separation.model.contract.SourceSeparationModelMetadata
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class BackupContractV1Test {

    @Test
    fun `format and settings schema versions are independent constants`() {
        assertEquals(1, BackupFormatV1.FORMAT_VERSION)
        assertEquals(1, BackupFormatV1.COMMON_SETTINGS_SCHEMA)
        assertEquals(1, BackupFormatV1.SOURCE_SEPARATION_SETTINGS_SCHEMA_V1)
        assertEquals(2, BackupFormatV1.SOURCE_SEPARATION_SETTINGS_SCHEMA)
        assertEquals("backup-manifest.json", BackupFormatV1.MANIFEST_PATH)
        assertEquals("settings/common.json", BackupFormatV1.COMMON_SETTINGS_PATH)
        assertEquals(
            "source_separation/settings.json",
            BackupFormatV1.SOURCE_SEPARATION_SETTINGS_PATH,
        )
    }

    @Test
    fun `common allowlist has unique typed defaults and excludes unsafe UI keys`() {
        val definitions = BackupSettingsPolicy.commonSettingsV1

        assertEquals(117, definitions.size)
        assertEquals(definitions.size, definitions.map { it.key }.toSet().size)
        assertTrue(definitions.all { it.introducedInSchema == 1 })
        assertTrue(
            definitions.all {
                (it.defaultValue == null) != (it.dynamicDefault == null)
            }
        )
        assertTrue(BackupSettingsPolicy.isCommonSettingAllowed("general_theme"))
        assertTrue(BackupSettingsPolicy.isCommonSettingAllowed("remember_shuffle_mode"))
        assertFalse(BackupSettingsPolicy.isCommonSettingAllowed("backup_data"))
        assertFalse(BackupSettingsPolicy.isCommonSettingAllowed("restore_data"))
        assertFalse(BackupSettingsPolicy.isCommonSettingAllowed("search_for_update"))
        assertFalse(BackupSettingsPolicy.isCommonSettingAllowed("lastfm_login"))
        assertFalse(BackupSettingsPolicy.isCommonSettingAllowed("listenbrainz_login"))
        assertFalse(BackupSettingsPolicy.isCommonSettingAllowed("lyrics_custom_font"))
        assertFalse(BackupSettingsPolicy.isCommonSettingAllowed("start_directory"))
        assertFalse(BackupSettingsPolicy.isCommonSettingAllowed("source_separation.auto_start"))
    }

    @Test
    fun `source separation allowlist is exact and excludes transient preferences`() {
        val expected = setOf(
            "source_separation.panel_entry_visible",
            "source_separation.quick_controls_visible",
            "source_separation.playback_enabled",
            "source_separation.global_blend",
            "source_separation.remember_per_song",
            "source_separation.auto_start",
            "source_separation.auto_flac_compression",
            "source_separation.show_snackbar_progress",
            "source_separation.show_snackbar_messages",
            "source_separation.mixed_output_preroll_ms",
            "source_separation.playback_ready_window_count",
            "source_separation.auto_cache_cleanup",
            "source_separation.auto_cache_cleanup_partial_limit",
            "source_separation.auto_cache_cleanup_completed_limit",
            "source_separation.gpu_enabled",
            "source_separation.compression_format",
        )

        assertEquals(expected, BackupSettingsPolicy.sourceSeparationSettingsByKey.keys)
        assertFalse(
            BackupSettingsPolicy.sourceSeparationSettingsForSchema(1)
                .orEmpty()
                .containsKey("source_separation.gpu_enabled")
        )
        assertEquals(
            2,
            BackupSettingsPolicy.sourceSeparationSettingsByKey
                .getValue("source_separation.gpu_enabled")
                .introducedInSchema,
        )
        assertEquals(
            2,
            BackupSettingsPolicy.sourceSeparationSettingsByKey
                .getValue("source_separation.compression_format")
                .introducedInSchema,
        )
        assertFalse(
            BackupSettingsPolicy.sourceSeparationSettingsByKey
                .containsKey("source_separation.try_gpu")
        )
        assertTrue(
            BackupSettingsPolicy.sourceSeparationSettingsForSchema(2)
                .orEmpty()
                .containsKey("source_separation.gpu_enabled")
        )
        assertTrue(
            expected.none(BackupSettingsPolicy::isExplicitlyNonBackupPreference)
        )
        assertTrue(
            BackupSettingsPolicy.isExplicitlyNonBackupPreference(
                "source_separation.per_song_blend.pending.abcdef"
            )
        )
        assertTrue(
            BackupSettingsPolicy.isExplicitlyNonBackupPreference(
                "source_separation.average_window_ms"
            )
        )
        assertTrue(
            BackupSettingsPolicy.isExplicitlyNonBackupPreference(
                "source_separation.window_decode"
            )
        )
    }

    @Test
    fun `settings snapshots reject unknown keys and wrong value types`() {
        BackupContractValidator.validateCommonSettings(
            CommonSettingsSnapshotV1(
                schemaVersion = 1,
                preferences = mapOf(
                    "black_theme" to JsonPrimitive(true),
                    "seek_interval" to JsonPrimitive(10),
                    "general_theme" to JsonPrimitive("auto"),
                ),
            )
        )
        assertThrows(BackupContractException::class.java) {
            BackupContractValidator.validateCommonSettings(
                CommonSettingsSnapshotV1(
                    schemaVersion = 1,
                    preferences = mapOf("lastfm_login" to JsonPrimitive("secret")),
                )
            )
        }
        assertThrows(BackupContractException::class.java) {
            BackupContractValidator.validateCommonSettings(
                CommonSettingsSnapshotV1(
                    schemaVersion = 1,
                    preferences = mapOf("black_theme" to JsonPrimitive("true")),
                )
            )
        }
        assertThrows(BackupContractException::class.java) {
            BackupContractValidator.validateSourceSeparationSettings(
                SourceSeparationSettingsSnapshotV1(
                    schemaVersion = 1,
                    preferences = mapOf(
                        "source_separation.per_song_blend.pending.song" to JsonPrimitive(0.2f)
                    ),
                )
            )
        }
        BackupContractValidator.validateSourceSeparationSettings(
            SourceSeparationSettingsSnapshotV1(
                schemaVersion = 2,
                preferences = mapOf(
                    "source_separation.gpu_enabled" to JsonPrimitive(false)
                ),
            )
        )
        assertThrows(BackupContractException::class.java) {
            BackupContractValidator.validateSourceSeparationSettings(
                SourceSeparationSettingsSnapshotV1(
                    schemaVersion = 1,
                    preferences = mapOf(
                        "source_separation.try_gpu" to JsonPrimitive(false)
                    ),
                )
            )
        }
        assertThrows(BackupContractException::class.java) {
            BackupContractValidator.validateSourceSeparationSettings(
                SourceSeparationSettingsSnapshotV1(
                    schemaVersion = 2,
                    preferences = mapOf(
                        "source_separation.try_gpu" to JsonPrimitive(false)
                    ),
                )
            )
        }
        assertThrows(BackupContractException::class.java) {
            BackupContractValidator.validateSourceSeparationSettings(
                SourceSeparationSettingsSnapshotV1(
                    schemaVersion = 3,
                    preferences = mapOf(
                        "source_separation.gpu_enabled" to JsonPrimitive(false)
                    ),
                )
            )
        }
    }

    @Test
    fun `portable active model and custom profile contain metadata but no weight`() {
        val contract = catalog.contracts.single { it.modelId == "uvr_mdxnet_3_9662" }
        val profile = SourceSeparationCustomModelProfile(
            profileSchemaVersion = 1,
            profileId = "portable-custom-profile",
            modelId = "custom_model",
            displayName = "Custom model",
            artifact = contract.artifact,
            tensorContract = contract.tensorContract,
            dsp = contract.dsp,
            stemContract = contract.stemContract,
            pipelineCompatibility = contract.pipelineCompatibility,
            qualityUnverified = true,
        )
        val snapshot = SourceSeparationSettingsSnapshotV1(
            schemaVersion = 1,
            preferences = mapOf(
                "source_separation.global_blend" to JsonPrimitive(0.5f)
            ),
            activeModel = PortableActiveModelReference(
                modelId = profile.modelId,
                artifactSha256 = profile.artifact.sha256,
                contractSchemaVersion = 1,
                profileId = profile.profileId,
            ),
            customProfiles = listOf(profile),
        )

        BackupContractValidator.validateSourceSeparationSettings(snapshot)
        val encoded = BackupContractJson.json.encodeToString(snapshot)
        assertFalse(encoded.contains("content://"))
        assertFalse(encoded.contains("files/source-separation"))
        assertFalse(encoded.contains("playback-settings.json"))
        assertFalse(encoded.contains("installedModels"))
    }

    @Test
    fun `portable profile revisions may share one model artifact`() {
        val contract = catalog.contracts.single { it.modelId == "uvr_mdxnet_3_9662" }
        val first = SourceSeparationCustomModelProfile(
            profileSchemaVersion = 1,
            profileId = "portable-custom-profile-v1",
            modelId = "custom_model",
            displayName = "Custom model v1",
            artifact = contract.artifact,
            tensorContract = contract.tensorContract,
            dsp = contract.dsp,
            stemContract = contract.stemContract,
            pipelineCompatibility = contract.pipelineCompatibility,
            qualityUnverified = true,
        )
        val second = first.copy(
            profileId = "portable-custom-profile-v2",
            displayName = "Custom model v2",
        )

        BackupContractValidator.validateSourceSeparationSettings(
            SourceSeparationSettingsSnapshotV1(
                schemaVersion = 1,
                preferences = emptyMap(),
                customProfiles = listOf(first, second),
            )
        )

        assertThrows(BackupContractException::class.java) {
            BackupContractValidator.validateSourceSeparationSettings(
                SourceSeparationSettingsSnapshotV1(
                    schemaVersion = 1,
                    preferences = emptyMap(),
                    customProfiles = listOf(first, second.copy(profileId = first.profileId)),
                )
            )
        }
        assertThrows(BackupContractException::class.java) {
            BackupContractValidator.validateSourceSeparationSettings(
                SourceSeparationSettingsSnapshotV1(
                    schemaVersion = 1,
                    preferences = emptyMap(),
                    activeModel = PortableActiveModelReference(
                        modelId = first.modelId,
                        artifactSha256 = first.artifact.sha256,
                        contractSchemaVersion = 1,
                        profileId = "missing-profile-revision",
                    ),
                    customProfiles = listOf(first, second),
                )
            )
        }
    }

    @Test
    fun `manifest accepts canonical payloads and both filtered legacy projections`() {
        val manifest = manifest(
            payloads = listOf(
                payload(
                    BackupFormatV1.COMMON_SETTINGS_PATH,
                    BackupPayloadKinds.COMMON_SETTINGS,
                    schemaVersion = 1,
                ),
                payload(
                    BackupFormatV1.SOURCE_SEPARATION_SETTINGS_PATH,
                    BackupPayloadKinds.SOURCE_SEPARATION_SETTINGS,
                    schemaVersion = 1,
                    optional = true,
                ),
                payload(
                    BackupFormatV1.UPSTREAM_LEGACY_PROJECTION_PATH,
                    BackupPayloadKinds.LEGACY_SETTINGS_PROJECTION,
                ),
                payload(
                    BackupFormatV1.BOOMING_SS_LEGACY_PROJECTION_PATH,
                    BackupPayloadKinds.LEGACY_SETTINGS_PROJECTION,
                ),
            )
        )

        BackupContractValidator.validateManifest(manifest)
    }

    @Test
    fun `unknown optional fork payload is skippable but required one is rejected`() {
        BackupContractValidator.validateManifest(
            manifest(
                listOf(payload("fork/future.json", "future-fork-payload", optional = true))
            )
        )
        assertThrows(BackupContractException::class.java) {
            BackupContractValidator.validateManifest(
                manifest(
                    listOf(payload("fork/future.json", "future-fork-payload", optional = false))
                )
            )
        }
    }

    @Test
    fun `manifest rejects traversal model weights cache and per-song files`() {
        val forbidden = listOf(
            "../model.tflite",
            "source_separation/models/model.tflite",
            "source_separation/cache/entry/vocals.flac",
            "source_separation/entries/song/completed/vocals.flac",
            "source_separation/entries/song/manifest.json",
            "source_separation/entries/song/playback-settings.json",
            "source_separation/debug/report.json",
            "source_separation/download/model.part",
        )

        forbidden.forEach { path ->
            assertThrows(path, BackupContractException::class.java) {
                BackupContractValidator.validateManifest(
                    manifest(listOf(payload(path, BackupPayloadKinds.ARTIST_IMAGE)))
                )
            }
        }
    }

    @Test
    fun `non-backup rules cover every prohibited data class`() {
        assertEquals(
            NonBackupDataClass.entries.toSet(),
            BackupSettingsPolicy.nonBackupRules.map { it.dataClass }.toSet(),
        )
        assertTrue(
            BackupSettingsPolicy.nonBackupRules.any {
                it.dataClass == NonBackupDataClass.ModelWeight &&
                    "files/source-separation/models" in it.storagePattern
            }
        )
        assertTrue(
            BackupSettingsPolicy.nonBackupRules.any {
                it.dataClass == NonBackupDataClass.GeneratedSeparationCache &&
                    "externalCacheDir/source-separation" in it.storagePattern
            }
        )
        assertTrue(
            BackupSettingsPolicy.nonBackupRules.any {
                it.dataClass == NonBackupDataClass.PerSongBlend &&
                    "playback-settings.json" in it.storagePattern
            }
        )
    }

    @Test
    fun `strict backup decoder rejects undeclared manifest fields`() {
        val encoded = BackupContractJson.json.encodeToString(manifest(emptyList()))
        val modified = encoded.dropLast(1) + ",\"undeclared\":true}"

        assertThrows(Exception::class.java) {
            BackupContractJson.decodeManifest(modified)
        }
    }

    private fun manifest(payloads: List<BackupPayloadDescriptor>) = BackupManifestV1(
        formatVersion = 1,
        commonSettingsSchema = 1,
        sourceSeparationSettingsSchema = 1,
        producerPackage = "com.wluhwluh.booming.sourcesep",
        producerFlavor = "github",
        applicationVersion = "1.3.1-beta.2-ss.1",
        generatedAtUtc = "2026-07-20T12:34:56Z",
        payloads = payloads,
    )

    private fun payload(
        path: String,
        kind: String,
        schemaVersion: Int? = null,
        optional: Boolean = false,
    ) = BackupPayloadDescriptor(
        path = path,
        kind = kind,
        schemaVersion = schemaVersion,
        byteSize = 1L,
        sha256 = "a".repeat(64),
        optional = optional,
    )

    companion object {
        private lateinit var catalog: SourceSeparationModelCatalog

        @JvmStatic
        @BeforeClass
        fun loadCatalog() {
            val stream = requireNotNull(
                BackupContractV1Test::class.java.classLoader
                    ?.getResourceAsStream(SourceSeparationModelMetadata.CATALOG_ASSET_PATH)
            ) { "Bundled model catalog test resource is missing" }
            catalog = stream.use { input ->
                SourceSeparationModelMetadata.decodeBundledCatalog(input.readBytes())
            }
        }
    }
}
