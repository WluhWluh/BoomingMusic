/*
 * Copyright (c) 2024 Christians Martínez Alvarado
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.mardous.booming.util

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import androidx.annotation.StringRes
import com.mardous.booming.BuildConfig
import com.mardous.booming.R
import com.mardous.booming.data.local.repository.Repository
import com.mardous.booming.data.local.room.LyricsDao
import com.mardous.booming.data.local.room.LyricsEntity
import com.mardous.booming.data.local.room.PlaylistEntity
import com.mardous.booming.data.mapper.toSongEntity
import com.mardous.booming.extensions.showToast
import com.mardous.booming.separation.model.preset.SourceSeparationActiveModelReference
import com.mardous.booming.separation.model.preset.SourceSeparationActivePresetState
import com.mardous.booming.separation.model.preset.SourceSeparationPresetRepository
import com.mardous.booming.util.backup.BackupArchiveCodec
import com.mardous.booming.util.backup.BackupArchivePath
import com.mardous.booming.util.backup.BackupArchivePayload
import com.mardous.booming.util.backup.BackupContractJson
import com.mardous.booming.util.backup.BackupContractValidator
import com.mardous.booming.util.backup.BackupFormatV1
import com.mardous.booming.util.backup.BackupPayloadKinds
import com.mardous.booming.util.backup.BackupPreferenceCodec
import com.mardous.booming.util.backup.BackupSettingsPolicy
import com.mardous.booming.util.backup.BackupSettingsRestoreDecoder
import com.mardous.booming.util.backup.CommonSettingsSnapshotV1
import com.mardous.booming.util.backup.PortableActiveModelReference
import com.mardous.booming.util.backup.SourceSeparationSettingsSnapshotV1
import com.mardous.booming.util.backup.StagedBackupArchive
import com.mardous.booming.util.backup.putPortablePreferences
import com.mardous.booming.util.m3u.M3UConstants
import com.mardous.booming.util.m3u.M3UWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.time.Instant
import java.util.Base64
import java.util.UUID

object BackupHelper : KoinComponent {
    private val repository by inject<Repository>()
    private val lyricsDao by inject<LyricsDao>()
    private val preferences by inject<SharedPreferences>()
    private val presetRepository by inject<SourceSeparationPresetRepository>()

    suspend fun createBackup(context: Context, uri: Uri?) {
        if (uri == null) return
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val stagingRoot = createStagingDirectory(context, "create")
                try {
                    val payloads = buildBackupPayloads(context, stagingRoot)
                    val manifest = BackupArchiveCodec.createManifest(
                        producerPackage = context.packageName,
                        producerFlavor = BuildConfig.FLAVOR,
                        applicationVersion = BuildConfig.VERSION_NAME,
                        generatedAtUtc = Instant.now().toString(),
                        payloads = payloads,
                    )
                    val output = context.contentResolver.openOutputStream(uri)
                        ?: throw FileNotFoundException("Unable to create backup output stream.")
                    BackupArchiveCodec.write(
                        output = output,
                        comment = context.getString(R.string.app_name),
                        manifest = manifest,
                        payloads = payloads,
                    )
                } finally {
                    stagingRoot.deleteRecursively()
                    stagingRoot.parentFile?.delete()
                }
            }
        }
        result.onFailure { error -> Log.e(TAG, "Unable to create backup", error) }
        withContext(Dispatchers.Main) {
            context.showToast(
                if (result.isSuccess) R.string.backup_successful else R.string.backup_failed,
            )
        }
    }

    suspend fun restoreBackup(context: Context, uri: Uri?, contents: List<BackupContent>) {
        if (uri == null) return
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw FileNotFoundException("Unable to open backup input stream.")
                val stagingRoot = newStagingPath(context, "restore")
                BackupArchiveCodec.extract(input, stagingRoot).use { archive ->
                    val plan = buildRestorePlan(context, archive, contents.toSet())
                    applyRestorePlan(context, plan)
                }
                stagingRoot.parentFile?.delete()
            }
        }
        result.onFailure { error -> Log.e(TAG, "Unable to restore backup", error) }
        withContext(Dispatchers.Main) {
            context.showToast(
                if (result.isSuccess) {
                    R.string.data_restored_successfully
                } else {
                    R.string.could_not_restore_data
                },
            )
        }
    }

    private suspend fun buildBackupPayloads(
        context: Context,
        stagingRoot: File,
    ): List<BackupArchivePayload> {
        val payloads = mutableListOf<BackupArchivePayload>()
        payloads += stageSettingsPayloads(stagingRoot)
        payloads += stagePlaylistPayloads(stagingRoot)
        payloads += stageLyricsPayload(stagingRoot)
        payloads += stageArtistPayloads(context, stagingRoot)
        return payloads
    }

    private fun stageSettingsPayloads(stagingRoot: File): List<BackupArchivePayload> {
        val commonPreferences = BackupPreferenceCodec.snapshot(
            preferences.all,
            BackupSettingsPolicy.commonSettingsByKey,
        )
        val sourcePreferences = BackupPreferenceCodec.snapshot(
            preferences.all,
            BackupSettingsPolicy.sourceSeparationSettingsByKey,
        )
        val commonSnapshot = BackupContractValidator.validateCommonSettings(
            CommonSettingsSnapshotV1(
                schemaVersion = BackupFormatV1.COMMON_SETTINGS_SCHEMA,
                preferences = commonPreferences,
            ),
        )
        val selectedReference =
            (presetRepository.activeModel() as? SourceSeparationActivePresetState.Reference)
                ?.reference
                ?: presetRepository.pendingActiveModel()
        val sourceSnapshot = BackupContractValidator.validateSourceSeparationSettings(
            SourceSeparationSettingsSnapshotV1(
                schemaVersion = BackupFormatV1.SOURCE_SEPARATION_SETTINGS_SCHEMA,
                preferences = sourcePreferences,
                activeModel = selectedReference?.toPortableReference(),
                customProfiles = presetRepository.customProfiles(),
            ),
        )
        val allDefinitions = BackupSettingsPolicy.commonSettingsByKey +
            BackupSettingsPolicy.sourceSeparationSettingsByKey

        return listOf(
            stageBytes(
                stagingRoot,
                BackupFormatV1.COMMON_SETTINGS_PATH,
                BackupPayloadKinds.COMMON_SETTINGS,
                BackupContractJson.json.encodeToString(commonSnapshot).encodeToByteArray(),
                schemaVersion = BackupFormatV1.COMMON_SETTINGS_SCHEMA,
                optional = false,
            ),
            stageBytes(
                stagingRoot,
                BackupFormatV1.SOURCE_SEPARATION_SETTINGS_PATH,
                BackupPayloadKinds.SOURCE_SEPARATION_SETTINGS,
                BackupContractJson.json.encodeToString(sourceSnapshot).encodeToByteArray(),
                schemaVersion = BackupFormatV1.SOURCE_SEPARATION_SETTINGS_SCHEMA,
                optional = true,
            ),
            stageBytes(
                stagingRoot,
                BackupFormatV1.UPSTREAM_LEGACY_PROJECTION_PATH,
                BackupPayloadKinds.LEGACY_SETTINGS_PROJECTION,
                BackupPreferenceCodec.encodeLegacyXml(
                    commonPreferences,
                    BackupSettingsPolicy.commonSettingsByKey,
                ),
            ),
            stageBytes(
                stagingRoot,
                BackupFormatV1.BOOMING_SS_LEGACY_PROJECTION_PATH,
                BackupPayloadKinds.LEGACY_SETTINGS_PROJECTION,
                BackupPreferenceCodec.encodeLegacyXml(
                    commonPreferences + sourcePreferences,
                    allDefinitions,
                ),
            ),
        )
    }

    private suspend fun stagePlaylistPayloads(
        stagingRoot: File,
    ): List<BackupArchivePayload> = repository.playlistsWithSongs()
        .mapIndexedNotNull { index, playlist ->
            val path = BackupFormatV1.PLAYLISTS_DIRECTORY + "/" +
                index.toString().padStart(5, '0') + "." + M3UConstants.EXTENSION
            val target = BackupArchivePath.resolve(stagingRoot, path)
            val encodedName = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(playlist.playlistEntity.playlistName.encodeToByteArray())
            val file = M3UWriter.writeToFile(
                exportFile = target,
                playlist = playlist,
                headerComments = listOf(PLAYLIST_NAME_PREFIX + encodedName),
                allowEmpty = true,
            )
            file.takeIf(File::isFile)?.let {
                BackupArchivePayload(
                    path = path,
                    kind = BackupPayloadKinds.PLAYLIST,
                    file = it,
                )
            }
        }

    private suspend fun stageLyricsPayload(stagingRoot: File): List<BackupArchivePayload> {
        val lyrics = lyricsDao.getAllLyrics()
        if (lyrics.isEmpty()) return emptyList()
        return listOf(
            stageBytes(
                stagingRoot,
                BackupFormatV1.LYRICS_PATH,
                BackupPayloadKinds.LYRICS,
                Json.encodeToString(lyrics).encodeToByteArray(),
            ),
        )
    }

    private fun stageArtistPayloads(
        context: Context,
        stagingRoot: File,
    ): List<BackupArchivePayload> {
        val payloads = mutableListOf<BackupArchivePayload>()
        val imageDirectory = File(context.filesDir, CUSTOM_ARTIST_IMAGES_DIRECTORY_NAME)
        imageDirectory.listFiles()
            ?.asSequence()
            ?.filter(File::isFile)
            ?.sortedBy(File::getName)
            ?.forEach { image ->
                val path = BackupFormatV1.ARTIST_IMAGES_DIRECTORY + "/" + image.name
                payloads += stageCopy(
                    stagingRoot,
                    path,
                    BackupPayloadKinds.ARTIST_IMAGE,
                    image,
                )
            }

        val artistPreferences = context.getSharedPreferences(
            CUSTOM_ARTIST_IMAGES_PREFERENCES,
            Context.MODE_PRIVATE,
        ).all.mapValues { (key, value) ->
            value as? Boolean
                ?: throw IllegalStateException("Custom artist preference $key is not boolean.")
        }
        if (artistPreferences.isNotEmpty()) {
            payloads += stageBytes(
                stagingRoot,
                BackupFormatV1.ARTIST_PREFERENCES_PATH,
                BackupPayloadKinds.ARTIST_PREFERENCES,
                BackupPreferenceCodec.encodeBooleanMapLegacyXml(artistPreferences),
            )
        }
        return payloads
    }

    private fun stageBytes(
        stagingRoot: File,
        path: String,
        kind: String,
        contents: ByteArray,
        schemaVersion: Int? = null,
        optional: Boolean = true,
    ): BackupArchivePayload {
        val target = BackupArchivePath.resolve(stagingRoot, path)
        target.parentFile?.mkdirs()
        if (target.exists()) throw IOException("Duplicate staged backup path: $path")
        target.writeBytes(contents)
        return BackupArchivePayload(path, kind, schemaVersion, optional, target)
    }

    private fun stageCopy(
        stagingRoot: File,
        path: String,
        kind: String,
        source: File,
    ): BackupArchivePayload {
        val target = BackupArchivePath.resolve(stagingRoot, path)
        target.parentFile?.mkdirs()
        source.copyTo(target, overwrite = false)
        return BackupArchivePayload(path = path, kind = kind, file = target)
    }

    private fun buildRestorePlan(
        context: Context,
        archive: StagedBackupArchive,
        contents: Set<BackupContent>,
    ): RestorePlan = if (archive.manifest != null) {
        buildCanonicalRestorePlan(archive, contents)
    } else {
        buildLegacyRestorePlan(context, archive, contents)
    }

    private fun buildCanonicalRestorePlan(
        archive: StagedBackupArchive,
        contents: Set<BackupContent>,
    ): RestorePlan {
        val manifest = requireNotNull(archive.manifest)
        val decodedSettings = BackupSettingsRestoreDecoder.decode(
            archive,
            BuildConfig.APPLICATION_ID,
        )

        return RestorePlan(
            commonPreferences = decodedSettings.commonPreferences
                .takeIf { BackupContent.Settings in contents }
                .orEmpty(),
            sourceSettings = decodedSettings.sourceSettings
                .takeIf { BackupContent.Settings in contents },
            lyrics = manifest.singlePayloadFile(archive, BackupPayloadKinds.LYRICS)
                ?.takeIf { BackupContent.Lyrics in contents }
                ?.let(::decodeLyrics),
            artistPreferences = manifest.singlePayloadFile(
                archive,
                BackupPayloadKinds.ARTIST_PREFERENCES,
            )?.takeIf { BackupContent.ArtistImages in contents }
                ?.let { BackupPreferenceCodec.decodeBooleanMapLegacyXml(it.readBytes()) },
            artistImages = if (BackupContent.ArtistImages in contents) {
                manifest.payloadFiles(archive, BackupPayloadKinds.ARTIST_IMAGE)
                    .validateArtistImageFiles()
            } else {
                emptyList()
            },
            playlists = if (BackupContent.Playlists in contents) {
                manifest.payloadFiles(archive, BackupPayloadKinds.PLAYLIST)
                    .map(::decodePlaylist)
                    .validatePlaylistNames()
            } else {
                emptyList()
            },
        )
    }

    private fun buildLegacyRestorePlan(
        context: Context,
        archive: StagedBackupArchive,
        contents: Set<BackupContent>,
    ): RestorePlan {
        val decodedSettings = BackupSettingsRestoreDecoder.decode(archive, context.packageName)
        val artistPreferenceFile = archive.file(BackupFormatV1.ARTIST_PREFERENCES_PATH)
        val playlistFiles = archive.files
            .filterKeys {
                it.startsWith(BackupFormatV1.PLAYLISTS_DIRECTORY + "/") &&
                    it.endsWith("." + M3UConstants.EXTENSION, ignoreCase = true)
            }
            .values
        val imageFiles = archive.files
            .filterKeys {
                it.startsWith(BackupFormatV1.ARTIST_IMAGES_DIRECTORY + "/") &&
                    it.count { character -> character == '/' } == 2
            }
            .toSortedMap()
            .values
            .toList()
            .validateArtistImageFiles()

        return RestorePlan(
            commonPreferences = decodedSettings.commonPreferences
                .takeIf { BackupContent.Settings in contents }
                .orEmpty(),
            sourceSettings = decodedSettings.sourceSettings
                .takeIf { BackupContent.Settings in contents },
            lyrics = archive.file(BackupFormatV1.LYRICS_PATH)
                ?.takeIf { BackupContent.Lyrics in contents }
                ?.let(::decodeLyrics),
            artistPreferences = artistPreferenceFile
                ?.takeIf { BackupContent.ArtistImages in contents }
                ?.let { BackupPreferenceCodec.decodeBooleanMapLegacyXml(it.readBytes()) },
            artistImages = imageFiles.takeIf { BackupContent.ArtistImages in contents }.orEmpty(),
            playlists = if (BackupContent.Playlists in contents) {
                playlistFiles.map(::decodePlaylist).validatePlaylistNames()
            } else {
                emptyList()
            },
        )
    }

    private suspend fun applyRestorePlan(context: Context, plan: RestorePlan) {
        plan.sourceSettings?.let { source ->
            presetRepository.restoreCustomProfiles(source.customProfiles)
        }
        if (plan.commonPreferences.isNotEmpty() || plan.sourceSettings != null) {
            applyPortablePreferences(plan.commonPreferences, plan.sourceSettings)
        }
        plan.sourceSettings?.let { source ->
            source.activeModel?.let { activeModel ->
                presetRepository.restoreActiveModelReference(
                    activeModel.toActiveModelReference(),
                )
            }
        }
        plan.lyrics?.let { lyricsDao.insertLyrics(it) }
        if (plan.artistImages.isNotEmpty()) restoreArtistImages(context, plan.artistImages)
        plan.artistPreferences?.let { restoreArtistPreferences(context, it) }
        plan.playlists.forEach { restorePlaylist(it) }
    }

    private fun applyPortablePreferences(
        common: Map<String, JsonElement>,
        source: SourceSeparationSettingsSnapshotV1?,
    ) {
        val editor = preferences.edit()
        editor.putPortablePreferences(common, BackupSettingsPolicy.commonSettingsByKey)
        source?.let { snapshot ->
            val definitions = requireNotNull(
                BackupSettingsPolicy.sourceSeparationSettingsForSchema(
                    snapshot.schemaVersion
                )
            ) { "Unsupported source-separation settings schema" }
            editor.putPortablePreferences(snapshot.preferences, definitions)
        }
        if (!editor.commit()) throw IOException("Unable to commit restored preferences.")
    }

    private fun restoreArtistImages(context: Context, images: List<File>) {
        val destination = File(context.filesDir, CUSTOM_ARTIST_IMAGES_DIRECTORY_NAME)
        if (!destination.exists() && !destination.mkdirs()) {
            throw IOException("Unable to create the custom artist image directory.")
        }
        images.forEach { image ->
            val target = File(destination, image.name)
            if (target.name != image.name) throw IOException("Unsafe artist image file name.")
            image.copyTo(target, overwrite = true)
        }
    }

    private fun restoreArtistPreferences(context: Context, values: Map<String, Boolean>) {
        val editor = context.getSharedPreferences(
            CUSTOM_ARTIST_IMAGES_PREFERENCES,
            Context.MODE_PRIVATE,
        ).edit().clear()
        values.forEach(editor::putBoolean)
        if (!editor.commit()) throw IOException("Unable to commit custom artist preferences.")
    }

    private suspend fun restorePlaylist(playlist: RestoredPlaylist) {
        val songs = playlist.songPaths.mapNotNull { path ->
            path.takeIf { it.startsWith('/') && File(it).exists() }
                ?.let { repository.songByFilePath(it, true) }
        }
        val existing = repository.checkPlaylistExists(playlist.name).firstOrNull()
        val playlistId = existing?.playListId
            ?: repository.createPlaylist(PlaylistEntity(playlistName = playlist.name))
        repository.insertSongsInPlaylist(songs.map { it.toSongEntity(playlistId) })
    }

    private fun decodeLyrics(file: File): List<LyricsEntity> =
        Json.decodeFromString(file.readText())

    private fun decodePlaylist(file: File): RestoredPlaylist {
        val lines = file.readLines()
        if (lines.firstOrNull()?.trim() != M3UConstants.HEADER) {
            throw IOException("Playlist does not contain an extended M3U header.")
        }
        val encodedNames = lines.filter { it.startsWith(PLAYLIST_NAME_PREFIX) }
        if (encodedNames.size > 1) throw IOException("Playlist contains duplicate name metadata.")
        val name = encodedNames.singleOrNull()?.removePrefix(PLAYLIST_NAME_PREFIX)?.let { encoded ->
            val decoded = Base64.getUrlDecoder().decode(encoded)
            if (decoded.size > MAX_PLAYLIST_NAME_BYTES) {
                throw IOException("Playlist name exceeds the backup limit.")
            }
            decoded.decodeToString(throwOnInvalidSequence = true)
        } ?: file.nameWithoutExtension
        if (name.isBlank()) throw IOException("Playlist name is empty.")
        return RestoredPlaylist(
            name = name,
            songPaths = lines.filter { it.startsWith('/') },
        )
    }

    private fun List<RestoredPlaylist>.validatePlaylistNames(): List<RestoredPlaylist> {
        if (map { it.name }.toSet().size != size) {
            throw IOException("Backup contains duplicate playlist names.")
        }
        return this
    }

    private fun List<File>.validateArtistImageFiles(): List<File> {
        if (any { it.name.isBlank() || it.name == "." || it.name == ".." }) {
            throw IOException("Backup contains an invalid artist image file name.")
        }
        if (map(File::getName).toSet().size != size) {
            throw IOException("Backup contains duplicate artist image file names.")
        }
        return this
    }

    private fun com.mardous.booming.util.backup.BackupManifestV1.payloadFiles(
        archive: StagedBackupArchive,
        kind: String,
    ): List<File> = payloads.filter { it.kind == kind }
        .sortedBy { it.path }
        .map { descriptor -> requireNotNull(archive.file(descriptor.path)) }

    private fun com.mardous.booming.util.backup.BackupManifestV1.singlePayloadFile(
        archive: StagedBackupArchive,
        kind: String,
    ): File? = payloadFiles(archive, kind).singleOrNull()

    private fun SourceSeparationActiveModelReference.toPortableReference() =
        PortableActiveModelReference(
            modelId = modelId,
            artifactSha256 = artifactSha256.lowercase(),
            contractSchemaVersion = contractSchemaVersion,
            profileId = profileId,
        )

    private fun PortableActiveModelReference.toActiveModelReference() =
        SourceSeparationActiveModelReference(
            modelId = modelId,
            artifactSha256 = artifactSha256,
            contractSchemaVersion = contractSchemaVersion,
            profileId = profileId,
        )

    private fun createStagingDirectory(context: Context, operation: String): File =
        newStagingPath(context, operation).also { path ->
            if (!path.mkdirs()) throw IOException("Unable to create backup staging directory.")
        }

    private fun newStagingPath(context: Context, operation: String): File {
        val parent = File(context.cacheDir, BACKUP_STAGING_DIRECTORY)
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("Unable to create backup staging root.")
        }
        return File(parent, operation + "-" + UUID.randomUUID())
    }

    const val BACKUP_EXTENSION = "bmgbak"
    const val APPEND_EXTENSION = ".$BACKUP_EXTENSION"

    private const val TAG = "BackupHelper"
    private const val BACKUP_STAGING_DIRECTORY = "backup-staging-v1"
    private const val CUSTOM_ARTIST_IMAGES_DIRECTORY_NAME = "custom_artist_images"
    private const val CUSTOM_ARTIST_IMAGES_PREFERENCES = "custom_artist_images"
    private const val PLAYLIST_NAME_PREFIX = "#BOOMING-PLAYLIST-NAME:"
    private const val MAX_PLAYLIST_NAME_BYTES = 4 * 1024
}

private data class RestorePlan(
    val commonPreferences: Map<String, JsonElement> = emptyMap(),
    val sourceSettings: SourceSeparationSettingsSnapshotV1? = null,
    val lyrics: List<LyricsEntity>? = null,
    val artistPreferences: Map<String, Boolean>? = null,
    val artistImages: List<File> = emptyList(),
    val playlists: List<RestoredPlaylist> = emptyList(),
)

private data class RestoredPlaylist(
    val name: String,
    val songPaths: List<String>,
)

enum class BackupContent(@StringRes val titleRes: Int) {
    Settings(R.string.backup_settings),
    Lyrics(R.string.backup_synced_lyrics),
    ArtistImages(R.string.backup_artist_images),
    Playlists(R.string.backup_playlists),
}
