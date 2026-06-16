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
    val error: SourceSeparationError? = null,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
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
    val timingPath: String? = null,
    val outputSampleRate: Int,
    val outputFrameCount: Int,
    val windowCount: Int,
    val elapsedMs: Long,
    val totalBytes: Long,
)

@Serializable
data class SourceSeparationError(
    val type: String,
    val message: String?,
)
