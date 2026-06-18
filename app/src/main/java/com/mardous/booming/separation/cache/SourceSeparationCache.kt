package com.mardous.booming.separation.cache

import android.content.Context
import android.os.Environment
import com.mardous.booming.data.model.Song
import com.mardous.booming.separation.model.MdxModelVariant
import com.mardous.booming.separation.model.MdxRangePreparation
import com.mardous.booming.separation.model.MdxRangeSeparationResult
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

class SourceSeparationCache(
    context: Context,
) {
    private val rootDir: File = File(
        context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir,
        "source-separation",
    )

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun beginOfflineRun(
        song: Song,
        modelVariant: MdxModelVariant,
        pipelineVersion: Int = PIPELINE_VERSION,
    ): SourceSeparationRun {
        val runDir = entryDir(song = song, modelVariant = modelVariant, pipelineVersion = pipelineVersion)
        val workDir = File(runDir, WORK_DIR_NAME)
        val completedDir = File(runDir, COMPLETED_DIR_NAME)
        val segmentsDir = File(runDir, SEGMENTS_DIR_NAME)
        val existingManifest = readManifest(runDir)
        val resumeManifest = existingManifest
            ?.takeIf { manifest ->
                manifest.state == SourceSeparationCacheState.Running &&
                        manifest.output != null &&
                        manifest.segmentPlan != null
            }
            ?.let { manifest ->
                val segmentPlan = manifest.segmentPlan ?: return@let null
                val canPreserveReadySegments = manifest.output
                    ?.hasValidWorkStemFiles()
                    ?: false
                val snapshot = manifest.toSegmentSnapshot(runDir, segmentPlan)
                manifest.copy(
                    segmentPlan = segmentPlan.copy(
                        segments = snapshot.segments.map { segmentState ->
                            segmentState.segment.copy(
                                state = if (canPreserveReadySegments &&
                                    segmentState.state == SourceSeparationSegmentState.Ready
                                ) {
                                    SourceSeparationSegmentState.Ready
                                } else {
                                    SourceSeparationSegmentState.Queued
                                }
                            )
                        },
                    ),
                    updatedAtEpochMs = System.currentTimeMillis(),
                )
            }

        if (resumeManifest != null) {
            workDir.mkdirs()
            completedDir.mkdirs()
            segmentsDir.mkdirs()
            writeManifest(runDir, resumeManifest)
            return SourceSeparationRun(
                song = song,
                modelVariant = modelVariant,
                pipelineVersion = pipelineVersion,
                rootDir = runDir,
                workDir = workDir,
                completedDir = completedDir,
                segmentsDir = segmentsDir,
                resumeManifest = resumeManifest,
            )
        }

        if (workDir.exists()) {
            workDir.deleteRecursively()
        }
        if (segmentsDir.exists()) {
            segmentsDir.deleteRecursively()
        }
        workDir.mkdirs()
        completedDir.mkdirs()
        segmentsDir.mkdirs()

        val now = System.currentTimeMillis()
        val initialManifest = SourceSeparationManifest(
            pipelineVersion = pipelineVersion,
            state = SourceSeparationCacheState.Running,
            songLocator = song.toLocator(),
            audioIdentity = SourceAudioIdentity(
                audioFingerprint = "",
                decodedFrameCount = 0,
                decodedSampleRate = 0,
                decodedChannelCount = 0,
                modelVariant = modelVariant.name,
                pipelineVersion = pipelineVersion,
            ),
            diagnostics = song.toDiagnostics(),
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
        )
        writeManifest(runDir, initialManifest)

        return SourceSeparationRun(
            song = song,
            modelVariant = modelVariant,
            pipelineVersion = pipelineVersion,
            rootDir = runDir,
            workDir = workDir,
            completedDir = completedDir,
            segmentsDir = segmentsDir,
            resumeManifest = null,
        )
    }

    fun completeRun(
        run: SourceSeparationRun,
        result: MdxRangeSeparationResult,
    ): SourceSeparationCompletion {
        val completedDir = run.completedDir.apply { mkdirs() }
        val vocalsFile = copyIntoDirectory(result.vocalsFile, completedDir, VOCALS_WAV)
        val instrumentalFile = copyIntoDirectory(result.instrumentalFile, completedDir, INSTRUMENTAL_WAV)
        val timingFile = copyIntoDirectory(result.timingFile, completedDir, TIMING_TXT)
        timingFile.writeText(
            result.timingReport.toFileText(
                vocalsFile = vocalsFile,
                instrumentalFile = instrumentalFile,
            ),
            Charsets.UTF_8,
        )
        val totalBytes = run.rootDir.directorySize()
        val now = System.currentTimeMillis()
        val manifest = SourceSeparationManifest(
            pipelineVersion = run.pipelineVersion,
            state = SourceSeparationCacheState.Completed,
            songLocator = run.song.toLocator(),
            audioIdentity = SourceAudioIdentity(
                audioFingerprint = result.sourceAudioFingerprint,
                decodedFrameCount = result.sourceFrameCount,
                decodedSampleRate = result.sourceSampleRate,
                decodedChannelCount = result.sourceChannelCount,
                modelVariant = run.modelVariant.name,
                pipelineVersion = run.pipelineVersion,
            ),
            diagnostics = run.song.toDiagnostics(),
            output = SourceSeparationOutput(
                vocalsPath = vocalsFile.absolutePath,
                instrumentalPath = instrumentalFile.absolutePath,
                timingPath = timingFile.absolutePath,
                outputSampleRate = result.outputSampleRate,
                outputFrameCount = result.frames,
                windowCount = result.windowCount,
                elapsedMs = result.elapsedMs,
                totalBytes = totalBytes,
            ),
            segmentPlan = result.segmentPlan,
            createdAtEpochMs = readManifest(run.rootDir)?.createdAtEpochMs ?: now,
            updatedAtEpochMs = now,
        )
        writeManifest(run.rootDir, manifest)
        return SourceSeparationCompletion(
            manifest = manifest,
            result = result.copy(
                vocalsFile = vocalsFile,
                instrumentalFile = instrumentalFile,
                timingFile = timingFile,
            )
        )
    }

    fun updateRunPreparation(
        run: SourceSeparationRun,
        preparation: MdxRangePreparation,
    ): SourceSeparationManifest? {
        val manifest = readManifest(run.rootDir) ?: return null
        val now = System.currentTimeMillis()
        val existingSegmentPlan = run.resumeManifest?.segmentPlan
            ?.takeIf { existing ->
                existing.rangeStartFrame == preparation.segmentPlan.rangeStartFrame &&
                        existing.rangeEndFrame == preparation.segmentPlan.rangeEndFrame &&
                        existing.sampleRate == preparation.segmentPlan.sampleRate &&
                        existing.generationSize == preparation.segmentPlan.generationSize &&
                        existing.segmentCount == preparation.segmentPlan.segmentCount
            }
        val updatedManifest = manifest.copy(
            state = SourceSeparationCacheState.Running,
            audioIdentity = SourceAudioIdentity(
                audioFingerprint = preparation.sourceAudioFingerprint,
                decodedFrameCount = preparation.sourceFrameCount,
                decodedSampleRate = preparation.sourceSampleRate,
                decodedChannelCount = preparation.sourceChannelCount,
                modelVariant = run.modelVariant.name,
                pipelineVersion = run.pipelineVersion,
            ),
            output = SourceSeparationOutput(
                vocalsPath = preparation.vocalsFile.absolutePath,
                instrumentalPath = preparation.instrumentalFile.absolutePath,
                timingPath = preparation.timingFile.absolutePath,
                outputSampleRate = preparation.outputSampleRate,
                outputFrameCount = preparation.frames,
                windowCount = preparation.windowCount,
                elapsedMs = 0L,
                totalBytes = preparation.vocalsFile.length() +
                        preparation.instrumentalFile.length() +
                        run.segmentsDir.directorySize(),
            ),
            segmentPlan = existingSegmentPlan ?: preparation.segmentPlan,
            updatedAtEpochMs = now,
        )
        writeManifest(run.rootDir, updatedManifest)
        return updatedManifest
    }

    fun failRun(
        run: SourceSeparationRun,
        error: Throwable,
    ): SourceSeparationManifest {
        return finishUnsuccessfulRun(
            run = run,
            state = SourceSeparationCacheState.Failed,
            error = error,
        )
    }

    fun cancelRun(
        run: SourceSeparationRun,
        error: Throwable,
    ): SourceSeparationManifest {
        return finishUnsuccessfulRun(
            run = run,
            state = SourceSeparationCacheState.Canceled,
            error = error,
        )
    }

    fun pauseRun(run: SourceSeparationRun): SourceSeparationManifest {
        val manifest = readManifest(run.rootDir)
        val now = System.currentTimeMillis()
        val updatedManifest = manifest?.copy(
            state = SourceSeparationCacheState.Running,
            updatedAtEpochMs = now,
        ) ?: SourceSeparationManifest(
            pipelineVersion = run.pipelineVersion,
            state = SourceSeparationCacheState.Running,
            songLocator = run.song.toLocator(),
            audioIdentity = SourceAudioIdentity(
                audioFingerprint = "",
                decodedFrameCount = 0,
                decodedSampleRate = 0,
                decodedChannelCount = 0,
                modelVariant = run.modelVariant.name,
                pipelineVersion = run.pipelineVersion,
            ),
            diagnostics = run.song.toDiagnostics(),
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
        )
        writeManifest(run.rootDir, updatedManifest)
        return updatedManifest
    }

    private fun finishUnsuccessfulRun(
        run: SourceSeparationRun,
        state: SourceSeparationCacheState,
        error: Throwable,
    ): SourceSeparationManifest {
        if (run.workDir.exists()) {
            run.workDir.deleteRecursively()
        }
        val now = System.currentTimeMillis()
        val manifest = SourceSeparationManifest(
            pipelineVersion = run.pipelineVersion,
            state = state,
            songLocator = run.song.toLocator(),
            audioIdentity = SourceAudioIdentity(
                audioFingerprint = "",
                decodedFrameCount = 0,
                decodedSampleRate = 0,
                decodedChannelCount = 0,
                modelVariant = run.modelVariant.name,
                pipelineVersion = run.pipelineVersion,
            ),
            diagnostics = run.song.toDiagnostics(),
            error = SourceSeparationError(
                type = error::class.java.name,
                message = error.message,
            ),
            createdAtEpochMs = readManifest(run.rootDir)?.createdAtEpochMs ?: now,
            updatedAtEpochMs = now,
        )
        writeManifest(run.rootDir, manifest)
        return manifest
    }

    fun readEntry(
        song: Song,
        modelVariant: MdxModelVariant,
        pipelineVersion: Int = PIPELINE_VERSION,
    ): SourceSeparationManifest? {
        return readManifest(entryDir(song, modelVariant, pipelineVersion))
    }

    fun readCompletedForAudio(
        song: Song,
        audioIdentity: SourceAudioIdentity,
        pipelineVersion: Int = PIPELINE_VERSION,
    ): SourceSeparationManifest? {
        val modelVariant = MdxModelVariant.valueOf(audioIdentity.modelVariant)
        return readEntry(song, modelVariant, pipelineVersion)
            ?.takeIf { it.state == SourceSeparationCacheState.Completed }
            ?.takeIf { it.audioIdentity == audioIdentity }
    }

    fun readCompletedForSong(
        song: Song,
        modelVariant: MdxModelVariant,
        pipelineVersion: Int = PIPELINE_VERSION,
    ): SourceSeparationManifest? {
        return readEntry(song, modelVariant, pipelineVersion)
            ?.takeIf { it.state == SourceSeparationCacheState.Completed }
            ?.takeIf { manifest ->
                val output = manifest.output ?: return@takeIf false
                File(output.vocalsPath).isFile && File(output.instrumentalPath).isFile
            }
    }

    fun readPlayableForSong(
        song: Song,
        modelVariant: MdxModelVariant,
        pipelineVersion: Int = PIPELINE_VERSION,
    ): SourceSeparationManifest? {
        return readEntry(song, modelVariant, pipelineVersion)
            ?.takeIf { manifest ->
                manifest.state == SourceSeparationCacheState.Completed ||
                        manifest.state == SourceSeparationCacheState.Running
            }
            ?.takeIf { manifest ->
                val output = manifest.output ?: return@takeIf false
                File(output.vocalsPath).isFile && File(output.instrumentalPath).isFile
            }
    }

    fun readSegmentSnapshot(
        song: Song,
        modelVariant: MdxModelVariant,
        pipelineVersion: Int = PIPELINE_VERSION,
    ): SourceSeparationSegmentSnapshot? {
        val entryDir = entryDir(song, modelVariant, pipelineVersion)
        val manifest = readManifest(entryDir) ?: return null
        val segmentPlan = manifest.segmentPlan ?: return null
        return manifest.toSegmentSnapshot(entryDir, segmentPlan)
    }

    fun readSegmentSnapshot(
        manifest: SourceSeparationManifest,
    ): SourceSeparationSegmentSnapshot? {
        val segmentPlan = manifest.segmentPlan ?: return null
        val entryDir = entryDir(
            songId = manifest.songLocator.songId,
            modelVariant = manifest.audioIdentity.modelVariant,
            pipelineVersion = manifest.pipelineVersion,
        )
        return manifest.toSegmentSnapshot(entryDir, segmentPlan)
    }

    fun updateSegmentState(
        run: SourceSeparationRun,
        segmentIndex: Int,
        state: SourceSeparationSegmentState,
    ): SourceSeparationManifest? {
        val manifest = readManifest(run.rootDir) ?: return null
        val segmentPlan = manifest.segmentPlan ?: return manifest
        val updatedPlan = segmentPlan.withSegmentState(segmentIndex, state)
        val updatedManifest = manifest.copy(
            segmentPlan = updatedPlan,
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        writeManifest(run.rootDir, updatedManifest)
        return updatedManifest
    }

    fun listManifests(): List<SourceSeparationManifest> {
        val entriesDir = File(rootDir, ENTRIES_DIR_NAME)
        if (!entriesDir.isDirectory) return emptyList()
        return entriesDir.listFiles()
            ?.mapNotNull { readManifest(it) }
            ?.sortedByDescending { it.updatedAtEpochMs }
            .orEmpty()
    }

    fun delete(manifest: SourceSeparationManifest): Boolean {
        val dir = entryDir(
            songId = manifest.songLocator.songId,
            modelVariant = manifest.audioIdentity.modelVariant,
            pipelineVersion = manifest.pipelineVersion,
        )
        return !dir.exists() || dir.deleteRecursively()
    }

    fun readPlaybackSettings(
        song: Song,
        modelVariant: MdxModelVariant,
        pipelineVersion: Int = PIPELINE_VERSION,
    ): SourceSeparationPlaybackSettings? {
        val dir = entryDir(song, modelVariant, pipelineVersion)
        val manifest = readManifest(dir) ?: return null
        return readPlaybackSettings(dir, manifest)
    }

    fun writePlaybackSettings(
        song: Song,
        modelVariant: MdxModelVariant,
        blend: Float,
        pipelineVersion: Int = PIPELINE_VERSION,
    ): SourceSeparationPlaybackSettings? {
        val dir = entryDir(song, modelVariant, pipelineVersion)
        val manifest = readManifest(dir) ?: return null
        val audioFingerprint = manifest.audioIdentity.audioFingerprint.takeIf { it.isNotBlank() }
            ?: return null
        val settings = SourceSeparationPlaybackSettings(
            audioFingerprint = audioFingerprint,
            blend = blend.coerceIn(0f, 1f),
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        writePlaybackSettings(dir, settings)
        return settings
    }

    fun segmentStemFile(
        run: SourceSeparationRun,
        segment: SourceSeparationSegment,
        stem: SourceSeparationSegmentStem,
    ): File {
        val relativePath = when (stem) {
            SourceSeparationSegmentStem.Vocals -> segment.vocalsPath
            SourceSeparationSegmentStem.Instrumental -> segment.instrumentalPath
        }
        return File(run.rootDir, relativePath)
    }

    private fun SourceSeparationManifest.toSegmentSnapshot(
        entryDir: File,
        segmentPlan: SourceSeparationSegmentPlan,
    ): SourceSeparationSegmentSnapshot {
        return SourceSeparationSegmentSnapshot(
            manifest = this,
            segmentPlan = segmentPlan,
            segments = segmentPlan.segments.map { segment ->
                val vocalsFile = File(entryDir, segment.vocalsPath)
                val instrumentalFile = File(entryDir, segment.instrumentalPath)
                val expectedDataSizeBytes = segment.expectedPcm16DataSizeBytes()
                val vocalsReady = vocalsFile.isPcm16WavWithDataSize(
                    expectedDataSizeBytes = expectedDataSizeBytes,
                    expectedSampleRate = segmentPlan.sampleRate,
                )
                val instrumentalReady = instrumentalFile.isPcm16WavWithDataSize(
                    expectedDataSizeBytes = expectedDataSizeBytes,
                    expectedSampleRate = segmentPlan.sampleRate,
                )
                val filesPresent = vocalsReady && instrumentalReady
                SourceSeparationSegmentFileState(
                    segment = segment,
                    state = when {
                        filesPresent && segment.state == SourceSeparationSegmentState.Ready ->
                            SourceSeparationSegmentState.Ready
                        segment.state == SourceSeparationSegmentState.Running -> SourceSeparationSegmentState.Running
                        segment.state == SourceSeparationSegmentState.Queued -> SourceSeparationSegmentState.Queued
                        segment.state == SourceSeparationSegmentState.Failed -> SourceSeparationSegmentState.Failed
                        else -> SourceSeparationSegmentState.Missing
                    },
                    vocalsFile = vocalsFile,
                    instrumentalFile = instrumentalFile,
                    vocalsReady = vocalsReady,
                    instrumentalReady = instrumentalReady,
                )
            },
        )
    }

    private fun SourceSeparationOutput.hasValidWorkStemFiles(): Boolean {
        val expectedDataSizeBytes = outputFrameCount.toLong() *
                SOURCE_SEPARATION_STEM_CHANNEL_COUNT *
                PCM16_BYTES_PER_SAMPLE
        return File(vocalsPath).isPcm16WavWithDataSize(
            expectedDataSizeBytes = expectedDataSizeBytes,
            expectedSampleRate = outputSampleRate,
        ) &&
                File(instrumentalPath).isPcm16WavWithDataSize(
                    expectedDataSizeBytes = expectedDataSizeBytes,
                    expectedSampleRate = outputSampleRate,
                )
    }

    private fun SourceSeparationSegment.expectedPcm16DataSizeBytes(): Long {
        return playbackFrameCount.toLong() *
                SOURCE_SEPARATION_STEM_CHANNEL_COUNT *
                PCM16_BYTES_PER_SAMPLE
    }

    private fun File.isPcm16WavWithDataSize(
        expectedDataSizeBytes: Long,
        expectedSampleRate: Int,
    ): Boolean {
        if (expectedDataSizeBytes < 0L ||
            expectedSampleRate <= 0 ||
            !isFile ||
            length() != WAV_HEADER_BYTES + expectedDataSizeBytes
        ) {
            return false
        }

        return runCatching {
            inputStream().use { input ->
                val header = ByteArray(WAV_HEADER_BYTES.toInt())
                var bytesRead = 0
                while (bytesRead < header.size) {
                    val count = input.read(header, bytesRead, header.size - bytesRead)
                    if (count < 0) return@runCatching false
                    bytesRead += count
                }
                header.asAscii(0, 4) == "RIFF" &&
                        header.asAscii(8, 4) == "WAVE" &&
                        header.asAscii(12, 4) == "fmt " &&
                        header.littleEndianShort(20) == 1 &&
                        header.littleEndianShort(22) == SOURCE_SEPARATION_STEM_CHANNEL_COUNT &&
                        header.littleEndianUInt(24) == expectedSampleRate.toLong() &&
                        header.littleEndianShort(34) == 16 &&
                        header.asAscii(36, 4) == "data" &&
                        header.littleEndianUInt(40) == expectedDataSizeBytes
            }
        }.getOrDefault(false)
    }

    private fun ByteArray.asAscii(offset: Int, length: Int): String {
        return String(this, offset, length, Charsets.US_ASCII)
    }

    private fun ByteArray.littleEndianShort(offset: Int): Int {
        return (this[offset].toInt() and 0xFF) or
                ((this[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun ByteArray.littleEndianUInt(offset: Int): Long {
        return (this[offset].toLong() and 0xFF) or
                ((this[offset + 1].toLong() and 0xFF) shl 8) or
                ((this[offset + 2].toLong() and 0xFF) shl 16) or
                ((this[offset + 3].toLong() and 0xFF) shl 24)
    }

    private fun entryDir(song: Song, modelVariant: MdxModelVariant, pipelineVersion: Int): File {
        return entryDir(song.id, modelVariant.name, pipelineVersion)
    }

    private fun entryDir(songId: Long, modelVariant: String, pipelineVersion: Int): File {
        val key = sha256Hex("$songId|$modelVariant|$pipelineVersion".encodeToByteArray()).take(24)
        return File(File(rootDir, ENTRIES_DIR_NAME), key)
    }

    private fun writeManifest(dir: File, manifest: SourceSeparationManifest) {
        dir.mkdirs()
        val target = File(dir, MANIFEST_FILE_NAME)
        val temp = File(dir, "$MANIFEST_FILE_NAME.tmp")
        temp.writeText(json.encodeToString(SourceSeparationManifest.serializer(), manifest), Charsets.UTF_8)
        if (target.exists() && !target.delete()) {
            temp.delete()
            error("Could not replace manifest: ${target.absolutePath}")
        }
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    private fun readManifest(dir: File): SourceSeparationManifest? {
        val file = File(dir, MANIFEST_FILE_NAME)
        if (!file.isFile) return null
        return try {
            json.decodeFromString(SourceSeparationManifest.serializer(), file.readText(Charsets.UTF_8))
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun readPlaybackSettings(
        dir: File,
        manifest: SourceSeparationManifest,
    ): SourceSeparationPlaybackSettings? {
        val audioFingerprint = manifest.audioIdentity.audioFingerprint.takeIf { it.isNotBlank() }
            ?: return null
        val file = File(dir, PLAYBACK_SETTINGS_FILE_NAME)
        if (!file.isFile) return null
        return try {
            json.decodeFromString(
                SourceSeparationPlaybackSettings.serializer(),
                file.readText(Charsets.UTF_8),
            ).takeIf { settings ->
                settings.audioFingerprint == audioFingerprint &&
                        settings.blend in 0f..1f
            }
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun writePlaybackSettings(
        dir: File,
        settings: SourceSeparationPlaybackSettings,
    ) {
        dir.mkdirs()
        val target = File(dir, PLAYBACK_SETTINGS_FILE_NAME)
        val temp = File(dir, "$PLAYBACK_SETTINGS_FILE_NAME.tmp")
        temp.writeText(
            json.encodeToString(SourceSeparationPlaybackSettings.serializer(), settings),
            Charsets.UTF_8,
        )
        if (target.exists() && !target.delete()) {
            temp.delete()
            error("Could not replace playback settings: ${target.absolutePath}")
        }
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    private fun copyIntoDirectory(source: File, targetDir: File, targetName: String): File {
        targetDir.mkdirs()
        val target = uniqueTargetFile(targetDir, targetName)
        source.copyTo(target, overwrite = true)
        return target
    }

    private fun uniqueTargetFile(targetDir: File, targetName: String): File {
        val extensionIndex = targetName.lastIndexOf('.')
        val base = if (extensionIndex > 0) targetName.substring(0, extensionIndex) else targetName
        val extension = if (extensionIndex > 0) targetName.substring(extensionIndex) else ""
        var candidate = File(targetDir, targetName)
        var suffix = 2
        while (candidate.exists()) {
            candidate = File(targetDir, "${base}_$suffix$extension")
            suffix += 1
        }
        return candidate
    }

    private fun File.directorySize(): Long {
        if (!isDirectory) return 0L
        return walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
    }

    private fun Song.toLocator(): SourceSongLocator {
        return SourceSongLocator(
            songId = id,
            mediaUri = uri.toString(),
            filePath = data,
            title = title,
            artist = artistName,
            album = albumName,
        )
    }

    private fun Song.toDiagnostics(): SourceFileDiagnostics {
        return SourceFileDiagnostics(
            fileSize = size,
            rawDateModified = rawDateModified,
            durationMs = duration,
        )
    }

    companion object {
        const val PIPELINE_VERSION = 1

        private const val ENTRIES_DIR_NAME = "entries"
        private const val WORK_DIR_NAME = "work"
        private const val COMPLETED_DIR_NAME = "completed"
        private const val SEGMENTS_DIR_NAME = "segments"
        private const val MANIFEST_FILE_NAME = "manifest.json"
        private const val PLAYBACK_SETTINGS_FILE_NAME = "playback-settings.json"
        private const val VOCALS_WAV = "vocals.wav"
        private const val INSTRUMENTAL_WAV = "instrumental.wav"
        private const val TIMING_TXT = "timing.txt"
        private const val WAV_HEADER_BYTES = 44L
        private const val SOURCE_SEPARATION_STEM_CHANNEL_COUNT = 2
        private const val PCM16_BYTES_PER_SAMPLE = 2

        fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}

data class SourceSeparationRun(
    val song: Song,
    val modelVariant: MdxModelVariant,
    val pipelineVersion: Int,
    val rootDir: File,
    val workDir: File,
    val completedDir: File,
    val segmentsDir: File,
    val resumeManifest: SourceSeparationManifest? = null,
)

data class SourceSeparationCompletion(
    val manifest: SourceSeparationManifest,
    val result: MdxRangeSeparationResult,
)

enum class SourceSeparationSegmentStem {
    Vocals,
    Instrumental,
}
