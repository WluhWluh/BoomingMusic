package com.mardous.booming.separation.cache

import kotlinx.serialization.Serializable

@Serializable
data class SourceSeparationManifest(
    val schemaVersion: Int = SCHEMA_VERSION,
    val pipelineVersion: Int,
    val state: SourceSeparationCacheState,
    val songLocator: SourceSongLocator,
    val audioIdentity: SourceAudioIdentity,
    val diagnostics: SourceFileDiagnostics,
    val output: SourceSeparationOutput? = null,
    val segmentPlan: SourceSeparationSegmentPlan? = null,
    val cleanup: SourceSeparationCleanup? = null,
    val error: SourceSeparationError? = null,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val lastAccessedAtEpochMs: Long = updatedAtEpochMs,
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

@Serializable
enum class SourceSeparationCacheState {
    Running,
    Completed,
    Canceled,
    Failed,
}

@Serializable
data class SourceSongLocator(
    val songId: Long,
    val mediaUri: String,
    val filePath: String,
    val title: String,
    val artist: String,
    val album: String,
)

@Serializable
data class SourceAudioIdentity(
    val audioFingerprint: String,
    val decodedFrameCount: Int,
    val decodedSampleRate: Int,
    val decodedChannelCount: Int,
    val modelVariant: String,
    val pipelineVersion: Int,
)

@Serializable
data class SourceFileDiagnostics(
    val fileSize: Long,
    val rawDateModified: Long,
    val durationMs: Long,
)

@Serializable
data class SourceSeparationOutput(
    val vocalsPath: String,
    val instrumentalPath: String,
    val format: SourceSeparationOutputFormat = SourceSeparationOutputFormat.WAV,
    val promotedVocalsPath: String? = null,
    val promotedInstrumentalPath: String? = null,
    val promotedFormat: SourceSeparationOutputFormat? = null,
    val promotionValidated: Boolean = false,
    val timingPath: String? = null,
    val outputSampleRate: Int,
    val outputFrameCount: Int,
    val windowCount: Int,
    val elapsedMs: Long,
    val totalBytes: Long,
) {
    fun playbackVocalsPath(): String {
        return promotedVocalsPath.takeIf { canUsePromotedFlac() && !it.isNullOrBlank() }
            ?: vocalsPath
    }

    fun playbackInstrumentalPath(): String {
        return promotedInstrumentalPath.takeIf { canUsePromotedFlac() && !it.isNullOrBlank() }
            ?: instrumentalPath
    }

    fun canUsePromotedFlac(): Boolean {
        return promotionValidated && promotedFormat == SourceSeparationOutputFormat.FLAC
    }
}

@Serializable
enum class SourceSeparationOutputFormat {
    WAV,
    FLAC,
}

@Serializable
data class SourceSeparationError(
    val type: String,
    val message: String?,
)

@Serializable
data class SourceSeparationCleanup(
    val workDirPath: String? = null,
    val workDirCleanupState: SourceSeparationCleanupState =
        SourceSeparationCleanupState.NotNeeded,
    val workWavCleanupState: SourceSeparationCleanupState? = null,
    val segmentsDirPath: String? = null,
    val segmentsDirCleanupState: SourceSeparationCleanupState =
        SourceSeparationCleanupState.NotNeeded,
)

@Serializable
enum class SourceSeparationCleanupState {
    NotNeeded,
    Pending,
    Completed,
}

@Serializable
data class SourceSeparationPlaybackSettings(
    val schemaVersion: Int = SCHEMA_VERSION,
    val audioFingerprint: String,
    val blend: Float,
    val updatedAtEpochMs: Long,
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}
