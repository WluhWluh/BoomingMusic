package com.mardous.booming.util.backup

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupCodecTest {
    @Test
    fun `preference snapshots and legacy XML retain schema types`() {
        val definitions = BackupSettingsPolicy.commonSettingsByKey +
            BackupSettingsPolicy.sourceSeparationSettingsByKey
        val raw = mapOf<String, Any>(
            "black_theme" to true,
            "seek_interval" to 12,
            "general_theme" to "dark",
            "recursive_folder_actions" to setOf("play", "enqueue"),
            "source_separation.global_blend" to 0.25f,
            "source_separation.mixed_output_preroll_ms" to 600L,
            "source_separation.worker.current" to "excluded",
        )

        val snapshot = BackupPreferenceCodec.snapshot(raw, definitions)
        assertFalse(snapshot.containsKey("source_separation.worker.current"))

        val xml = BackupPreferenceCodec.encodeLegacyXml(snapshot, definitions)
        val xmlText = xml.decodeToString()
        assertTrue(xmlText.contains("<float name=\"source_separation.global_blend\""))
        assertTrue(xmlText.contains("<long name=\"source_separation.mixed_output_preroll_ms\""))

        val restored = BackupPreferenceCodec.decodeLegacyXml(xml, definitions.keys)
        BackupContractValidator.validatePreferenceMap(restored, definitions)
        assertEquals(snapshot, restored)
        assertEquals(
            JsonArray(listOf(JsonPrimitive("enqueue"), JsonPrimitive("play"))),
            restored["recursive_folder_actions"],
        )
    }

    @Test
    fun `preference snapshot rejects a stored value with the wrong schema type`() {
        assertThrows(BackupContractException::class.java) {
            BackupPreferenceCodec.snapshot(
                mapOf("seek_interval" to "12"),
                BackupSettingsPolicy.commonSettingsByKey,
            )
        }
    }

    @Test
    fun `legacy preference parser filters unknown keys and rejects entities`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <map>
                <string name="general_theme">dark</string>
                <string name="account_token">secret</string>
            </map>
        """.trimIndent().encodeToByteArray()
        assertEquals(
            mapOf("general_theme" to JsonPrimitive("dark")),
            BackupPreferenceCodec.decodeLegacyXml(xml, setOf("general_theme")),
        )

        val entityXml = """
            <!DOCTYPE map [<!ENTITY secret SYSTEM "file:///data/local/tmp/secret">]>
            <map><string name="general_theme">&secret;</string></map>
        """.trimIndent().encodeToByteArray()
        assertThrows(BackupContractException::class.java) {
            BackupPreferenceCodec.decodeLegacyXml(entityXml)
        }
    }

    @Test
    fun `archive round trip verifies manifest paths sizes and hashes`() {
        temporaryDirectory().useDirectory { root ->
            val common = root.resolve("common.json").apply { writeText("{\"schemaVersion\":1,\"preferences\":{}}") }
            val payloads = listOf(
                BackupArchivePayload(
                    path = BackupFormatV1.COMMON_SETTINGS_PATH,
                    kind = BackupPayloadKinds.COMMON_SETTINGS,
                    schemaVersion = 1,
                    optional = false,
                    file = common,
                ),
            )
            val manifest = BackupArchiveCodec.createManifest(
                producerPackage = "com.wluhwluh.booming.sourcesep",
                producerFlavor = "github",
                applicationVersion = "test",
                generatedAtUtc = "2026-07-21T12:00:00Z",
                payloads = payloads,
            )
            val bytes = ByteArrayOutputStream().also { output ->
                BackupArchiveCodec.write(output, "Booming SS", manifest, payloads)
            }.toByteArray()

            BackupArchiveCodec.extract(
                ByteArrayInputStream(bytes),
                root.resolve("restore"),
            ).use { archive ->
                assertEquals(manifest, archive.manifest)
                assertEquals(common.readText(), archive.file(BackupFormatV1.COMMON_SETTINGS_PATH)?.readText())
            }
        }
    }

    @Test
    fun `archive rejects traversal undeclared entries and forged payload contents`() {
        temporaryDirectory().useDirectory { root ->
            assertThrows(BackupContractException::class.java) {
                BackupArchiveCodec.extract(
                    ByteArrayInputStream(zipOf("../outside.txt" to "escape".encodeToByteArray())),
                    root.resolve("traversal"),
                )
            }
            assertFalse(root.resolve("outside.txt").exists())

            val descriptor = BackupPayloadDescriptor(
                path = BackupFormatV1.COMMON_SETTINGS_PATH,
                kind = BackupPayloadKinds.COMMON_SETTINGS,
                schemaVersion = 1,
                byteSize = 4L,
                sha256 = "0".repeat(64),
                optional = false,
            )
            val manifest = manifest(listOf(descriptor))
            val forged = zipOf(
                BackupFormatV1.MANIFEST_PATH to
                    BackupContractJson.json.encodeToString(manifest).encodeToByteArray(),
                BackupFormatV1.COMMON_SETTINGS_PATH to "fake".encodeToByteArray(),
            )
            assertThrows(BackupContractException::class.java) {
                BackupArchiveCodec.extract(
                    ByteArrayInputStream(forged),
                    root.resolve("forged"),
                )
            }

            val undeclared = zipOf(
                BackupFormatV1.MANIFEST_PATH to
                    BackupContractJson.json.encodeToString(manifest(emptyList())).encodeToByteArray(),
                "fork/undeclared.json" to "{}".encodeToByteArray(),
            )
            assertThrows(BackupContractException::class.java) {
                BackupArchiveCodec.extract(
                    ByteArrayInputStream(undeclared),
                    root.resolve("undeclared"),
                )
            }
        }
    }

    @Test
    fun `future source separation schema is skippable only as an optional payload`() {
        val optional = payload(
            path = BackupFormatV1.SOURCE_SEPARATION_SETTINGS_PATH,
            kind = BackupPayloadKinds.SOURCE_SEPARATION_SETTINGS,
            schemaVersion = 2,
            optional = true,
        )
        BackupContractValidator.validateManifest(
            manifest(listOf(optional), sourceSchema = 2),
        )
        assertFalse(BackupContractValidator.isSupportedSourceSeparationSchema(2))

        assertThrows(BackupContractException::class.java) {
            BackupContractValidator.validateManifest(
                manifest(listOf(optional.copy(optional = false)), sourceSchema = 2),
            )
        }
    }

    @Test
    fun `canonical settings win over conflicting compatibility XML`() {
        temporaryDirectory().useDirectory { root ->
            val canonical = root.resolve("common.json").apply {
                writeText(
                    BackupContractJson.json.encodeToString(
                        CommonSettingsSnapshotV1(
                            schemaVersion = 1,
                            preferences = mapOf("black_theme" to JsonPrimitive(true)),
                        ),
                    ),
                )
            }
            val projection = root.resolve("projection.xml").apply {
                writeBytes(
                    BackupPreferenceCodec.encodeLegacyXml(
                        mapOf("black_theme" to JsonPrimitive(false)),
                        BackupSettingsPolicy.commonSettingsByKey,
                    ),
                )
            }
            val payloads = listOf(
                BackupArchivePayload(
                    path = BackupFormatV1.COMMON_SETTINGS_PATH,
                    kind = BackupPayloadKinds.COMMON_SETTINGS,
                    schemaVersion = 1,
                    optional = false,
                    file = canonical,
                ),
                BackupArchivePayload(
                    path = BackupFormatV1.BOOMING_SS_LEGACY_PROJECTION_PATH,
                    kind = BackupPayloadKinds.LEGACY_SETTINGS_PROJECTION,
                    file = projection,
                ),
            )
            val manifest = BackupArchiveCodec.createManifest(
                producerPackage = "com.wluhwluh.booming.sourcesep",
                producerFlavor = "github",
                applicationVersion = "test",
                generatedAtUtc = "2026-07-21T12:00:00Z",
                payloads = payloads,
            )
            val archiveBytes = ByteArrayOutputStream().also { output ->
                BackupArchiveCodec.write(output, "Booming SS", manifest, payloads)
            }.toByteArray()

            BackupArchiveCodec.extract(
                ByteArrayInputStream(archiveBytes),
                root.resolve("restore-canonical"),
            ).use { archive ->
                val decoded = BackupSettingsRestoreDecoder.decode(
                    archive,
                    "com.wluhwluh.booming.sourcesep",
                )
                assertEquals(JsonPrimitive(true), decoded.commonPreferences["black_theme"])
            }
        }
    }

    @Test
    fun `legacy settings choose a fixed package priority independent of ZIP order`() {
        temporaryDirectory().useDirectory { root ->
            val upstream = BackupPreferenceCodec.encodeLegacyXml(
                mapOf("general_theme" to JsonPrimitive("light")),
                BackupSettingsPolicy.commonSettingsByKey,
            )
            val fork = BackupPreferenceCodec.encodeLegacyXml(
                mapOf("general_theme" to JsonPrimitive("dark")),
                BackupSettingsPolicy.commonSettingsByKey,
            )
            val bytes = zipOf(
                BackupFormatV1.UPSTREAM_LEGACY_PROJECTION_PATH to upstream,
                BackupFormatV1.BOOMING_SS_LEGACY_PROJECTION_PATH to fork,
            )
            BackupArchiveCodec.extract(
                ByteArrayInputStream(bytes),
                root.resolve("restore-legacy"),
            ).use { archive ->
                val decoded = BackupSettingsRestoreDecoder.decode(
                    archive,
                    "com.wluhwluh.booming.sourcesep.debug",
                )
                assertEquals(JsonPrimitive("dark"), decoded.commonPreferences["general_theme"])
            }
        }
    }

    @Test
    fun `known payload kinds are restricted to their format v1 directories`() {
        val invalid = listOf(
            payload("other/list.m3u", BackupPayloadKinds.PLAYLIST),
            payload("lyrics/other.json", BackupPayloadKinds.LYRICS),
            payload("artistImages/image.jpg", BackupPayloadKinds.ARTIST_IMAGE),
            payload("artistImages/prefs/other.xml", BackupPayloadKinds.ARTIST_PREFERENCES),
        )

        invalid.forEach { descriptor ->
            assertThrows(descriptor.path, BackupContractException::class.java) {
                BackupContractValidator.validateManifest(manifest(listOf(descriptor)))
            }
        }
    }

    private fun manifest(
        payloads: List<BackupPayloadDescriptor>,
        sourceSchema: Int = 1,
    ) = BackupManifestV1(
        formatVersion = 1,
        commonSettingsSchema = 1,
        sourceSeparationSettingsSchema = sourceSchema,
        producerPackage = "com.wluhwluh.booming.sourcesep",
        producerFlavor = "github",
        applicationVersion = "test",
        generatedAtUtc = "2026-07-21T12:00:00Z",
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
        byteSize = 0L,
        sha256 = "0".repeat(64),
        optional = optional,
    )

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                entries.forEach { (path, contents) ->
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(contents)
                    zip.closeEntry()
                }
            }
        }.toByteArray()

    private fun temporaryDirectory(): File =
        Files.createTempDirectory("backup-codec-test").toFile()

    private inline fun <T> File.useDirectory(block: (File) -> T): T = try {
        block(this)
    } finally {
        deleteRecursively()
    }
}
