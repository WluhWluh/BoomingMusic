package com.mardous.booming.util.backup

import kotlinx.serialization.json.JsonElement
import java.io.File

data class DecodedBackupSettings(
    val commonPreferences: Map<String, JsonElement>,
    val sourceSettings: SourceSeparationSettingsSnapshotV1?,
)

object BackupSettingsRestoreDecoder {
    fun decode(
        archive: StagedBackupArchive,
        currentPackageName: String,
    ): DecodedBackupSettings = if (archive.manifest != null) {
        decodeCanonical(archive)
    } else {
        decodeLegacy(archive, currentPackageName)
    }

    private fun decodeCanonical(archive: StagedBackupArchive): DecodedBackupSettings {
        val manifest = requireNotNull(archive.manifest)
        val commonDescriptor = manifest.payloads.singleOrNull {
            it.kind == BackupPayloadKinds.COMMON_SETTINGS
        }
        val common = commonDescriptor?.let { descriptor ->
            BackupContractJson.decodeCommonSettings(
                requireNotNull(archive.file(descriptor.path)).readText(),
            ).also(BackupContractValidator::validateCommonSettings)
        }
        val sourceDescriptor = manifest.payloads.singleOrNull {
            it.kind == BackupPayloadKinds.SOURCE_SEPARATION_SETTINGS
        }
        val source = if (
            sourceDescriptor != null &&
            BackupContractValidator.isSupportedSourceSeparationSchema(
                manifest.sourceSeparationSettingsSchema,
            )
        ) {
            BackupContractJson.decodeSourceSeparationSettings(
                requireNotNull(archive.file(sourceDescriptor.path)).readText(),
            ).also { snapshot ->
                if (snapshot.schemaVersion != manifest.sourceSeparationSettingsSchema) {
                    throw BackupContractException(
                        "Source-separation snapshot schema does not match the manifest"
                    )
                }
                BackupContractValidator.validateSourceSeparationSettings(snapshot)
            }
        } else {
            null
        }
        return DecodedBackupSettings(
            commonPreferences = common?.preferences.orEmpty(),
            sourceSettings = source,
        )
    }

    private fun decodeLegacy(
        archive: StagedBackupArchive,
        currentPackageName: String,
    ): DecodedBackupSettings {
        val allDefinitions = BackupSettingsPolicy.commonSettingsByKey +
            BackupSettingsPolicy.sourceSeparationSettingsByKey
        val preferences = selectLegacyPreferenceFile(archive, currentPackageName)
            ?.let { file ->
                BackupPreferenceCodec.decodeLegacyXml(file.readBytes(), allDefinitions.keys)
            }
            .orEmpty()
        val common = BackupPreferenceCodec.filter(
            preferences,
            BackupSettingsPolicy.commonSettingsByKey,
        )
        val sourcePreferences = BackupPreferenceCodec.filter(
            preferences,
            BackupSettingsPolicy.sourceSeparationSettingsByKey,
        )
        return DecodedBackupSettings(
            commonPreferences = common,
            sourceSettings = SourceSeparationSettingsSnapshotV1(
                schemaVersion = BackupFormatV1.SOURCE_SEPARATION_SETTINGS_SCHEMA,
                preferences = sourcePreferences,
            ).takeIf { sourcePreferences.isNotEmpty() },
        )
    }

    private fun selectLegacyPreferenceFile(
        archive: StagedBackupArchive,
        currentPackageName: String,
    ): File? = archive.files
        .filterKeys { path ->
            path.startsWith("prefs/") &&
                path.count { it == '/' } == 1 &&
                path.endsWith("_preferences.xml")
        }
        .entries
        .sortedWith(compareBy<Map.Entry<String, File>> { (path, _) ->
            when (path) {
                "prefs/${currentPackageName}_preferences.xml" -> 0
                BackupFormatV1.BOOMING_SS_LEGACY_PROJECTION_PATH -> 1
                BackupFormatV1.UPSTREAM_LEGACY_PROJECTION_PATH -> 2
                else -> 3
            }
        }.thenBy { it.key })
        .firstOrNull()
        ?.value
}
