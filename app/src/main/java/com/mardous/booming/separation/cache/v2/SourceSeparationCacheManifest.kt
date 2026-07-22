package com.mardous.booming.separation.cache.v2

import com.mardous.booming.separation.cache.SourceSeparationSegmentPlan
import com.mardous.booming.separation.model.contract.ContractStemSemantic
import kotlinx.serialization.Serializable

@Serializable
data class SourceSeparationCacheManifest(
    val manifestSchemaVersion: Int = SCHEMA_VERSION,
    val cacheKey: String,
    val identity: SourceSeparationCacheIdentity,
    val contract: SourceSeparationCacheContractSnapshot,
    val state: SourceSeparationCacheManifestState,
    val song: SourceSeparationCacheSongLocator,
    val sourceDiagnostics: SourceSeparationCacheSourceDiagnostics,
    val output: SourceSeparationCacheOutput? = null,
    val segmentPlan: SourceSeparationSegmentPlan? = null,
    val cleanup: SourceSeparationCacheCleanup? = null,
    val error: SourceSeparationCacheError? = null,
    val runtimeRecords: List<SourceSeparationCacheRuntimeRecord> = emptyList(),
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val lastAccessedAtEpochMs: Long = updatedAtEpochMs,
) {
    init {
        require(manifestSchemaVersion == SCHEMA_VERSION) {
            "Unsupported cache manifest schema: $manifestSchemaVersion"
        }
        require(cacheKey == identity.cacheKey) { "Cache manifest key does not match its identity." }
        require(contract.modelId == identity.modelId) { "Cache manifest model ID is inconsistent." }
        require(contract.artifactSha256.equals(identity.artifactSha256, ignoreCase = true)) {
            "Cache manifest artifact hash is inconsistent."
        }
        require(contract.contractId == identity.contractId) {
            "Cache manifest contract ID is inconsistent."
        }
        require(contract.contractSchemaVersion == identity.contractSchemaVersion) {
            "Cache manifest contract schema is inconsistent."
        }
        require(contract.contractFingerprint.equals(identity.contractFingerprint, ignoreCase = true)) {
            "Cache manifest contract fingerprint is inconsistent."
        }
        require(contract.profileRevisionId == identity.profileRevisionId) {
            "Cache manifest profile revision is inconsistent."
        }
        require(contract.pipelineId == identity.pipelineId) {
            "Cache manifest pipeline ID is inconsistent."
        }
        require(contract.pipelineVersion == identity.pipelineVersion) {
            "Cache manifest pipeline version is inconsistent."
        }
        require(createdAtEpochMs >= 0L) { "Cache creation time is invalid." }
        require(updatedAtEpochMs >= createdAtEpochMs) { "Cache update time is invalid." }
        require(lastAccessedAtEpochMs >= createdAtEpochMs) { "Cache access time is invalid." }
        output?.validate(state)
        segmentPlan?.segments?.forEach { segment ->
            SourceSeparationCacheRelativePath.requireValid(segment.vocalsPath)
            SourceSeparationCacheRelativePath.requireValid(segment.instrumentalPath)
        }
        cleanup?.paths?.forEach(SourceSeparationCacheRelativePath::requireValid)
    }

    companion object {
        const val SCHEMA_VERSION = 2
    }
}

@Serializable
enum class SourceSeparationCacheManifestState {
    Running,
    Completed,
    Canceled,
    Failed,
}

@Serializable
data class SourceSeparationCacheSongLocator(
    val songId: Long,
    val mediaUri: String,
    val filePath: String,
    val title: String,
    val artist: String,
    val album: String,
)

@Serializable
data class SourceSeparationCacheSourceDiagnostics(
    val fileSize: Long,
    val rawDateModified: Long,
    val durationMs: Long,
)

@Serializable
data class SourceSeparationCacheOutput(
    val stems: List<SourceSeparationCacheRenderedStem>,
    val timingPath: String? = null,
    val outputSampleRate: Int,
    val outputFrameCount: Int,
    val windowCount: Int,
    val elapsedMs: Long,
    val totalBytes: Long,
) {
    init {
        require(stems.size == 2) { "A cache output must contain exactly two stems." }
        require(stems.map { it.semantic }.distinct().size == stems.size) {
            "Cache output stem semantics must be unique."
        }
        timingPath?.let(SourceSeparationCacheRelativePath::requireValid)
        require(outputSampleRate > 0) { "Cache output sample rate is invalid." }
        require(outputFrameCount > 0) { "Cache output frame count is invalid." }
        require(windowCount > 0) { "Cache output window count is invalid." }
        require(elapsedMs >= 0L) { "Cache output elapsed time is invalid." }
        require(totalBytes >= 0L) { "Cache output size is invalid." }
    }

    internal fun validate(state: SourceSeparationCacheManifestState) {
        if (state == SourceSeparationCacheManifestState.Completed) {
            require(stems.all { stem ->
                if (stem.promotionValidated) {
                    stem.promotedIntegrity != null
                } else {
                    stem.wavIntegrity != null
                }
            }) {
                "Completed cache stems require integrity metadata."
            }
        }
    }
}

@Serializable
data class SourceSeparationCacheRenderedStem(
    val semantic: ContractStemSemantic,
    val displayLabel: String,
    val wavPath: String,
    val promotedPath: String? = null,
    val promotedFormat: SourceSeparationCacheAudioFormat? = null,
    val promotionValidated: Boolean = false,
    val promotedIndexPath: String? = null,
    val channelCount: Int,
    val sampleRate: Int,
    val frameCount: Int,
    val wavIntegrity: SourceSeparationCacheFileIntegrity? = null,
    val promotedIntegrity: SourceSeparationCacheFileIntegrity? = null,
    val promotedIndexIntegrity: SourceSeparationCacheFileIntegrity? = null,
) {
    init {
        require(displayLabel.isNotBlank()) { "Cache output stem label is empty." }
        SourceSeparationCacheRelativePath.requireValid(wavPath)
        promotedPath?.let(SourceSeparationCacheRelativePath::requireValid)
        require((promotedPath == null) == (promotedFormat == null)) {
            "Cache promoted path and format must be provided together."
        }
        require(!promotionValidated || promotedPath != null) {
            "Validated cache promotion has no promoted output."
        }
        require(!promotionValidated || promotedIntegrity != null) {
            "Validated cache promotion has no integrity metadata."
        }
        require((promotedIndexPath == null) == (promotedIndexIntegrity == null)) {
            "Cache promoted index path and integrity must be provided together."
        }
        require(!promotionValidated || promotedIndexPath != null) {
            "Validated cache promotion has no frame index."
        }
        require(channelCount > 0) { "Cache stem channel count is invalid." }
        require(sampleRate > 0) { "Cache stem sample rate is invalid." }
        require(frameCount > 0) { "Cache stem frame count is invalid." }
    }

    fun playbackPath(): String = promotedPath.takeIf { promotionValidated } ?: wavPath
}

@Serializable
enum class SourceSeparationCacheAudioFormat {
    Wav,
    Flac,
}

@Serializable
data class SourceSeparationCacheFileIntegrity(
    val byteSize: Long,
    val sha256: String,
) {
    init {
        require(byteSize > 0L) { "Cache output file size is invalid." }
        require(SHA256_PATTERN.matches(sha256)) { "Cache output SHA-256 is invalid." }
    }

    private companion object {
        val SHA256_PATTERN = Regex("^[0-9a-fA-F]{64}$")
    }
}

@Serializable
data class SourceSeparationCacheCleanup(
    val paths: List<String>,
)

@Serializable
data class SourceSeparationCacheError(
    val type: String,
    val message: String? = null,
)

@Serializable
data class SourceSeparationCacheRuntimeRecord(
    val backend: String,
    val runtimeProfileId: String,
    val precision: String,
    val elapsedMs: Long,
    val fallbackReason: String? = null,
) {
    init {
        require(backend.isNotBlank()) { "Cache runtime backend is empty." }
        require(runtimeProfileId.isNotBlank()) { "Cache runtime profile is empty." }
        require(precision.isNotBlank()) { "Cache runtime precision is empty." }
        require(elapsedMs >= 0L) { "Cache runtime elapsed time is invalid." }
    }
}

@Serializable
data class SourceSeparationCachePlaybackSettings(
    val playbackSettingsSchemaVersion: Int = SCHEMA_VERSION,
    val cacheKey: String,
    val audioFingerprint: String,
    val blend: Float,
    val updatedAtEpochMs: Long,
) {
    init {
        require(playbackSettingsSchemaVersion == SCHEMA_VERSION) {
            "Unsupported playback settings schema: $playbackSettingsSchemaVersion"
        }
        require(CACHE_KEY_PATTERN.matches(cacheKey)) { "Playback cache key is invalid." }
        require(audioFingerprint.isNotBlank()) { "Playback source fingerprint is empty." }
        require(blend in 0f..1f) { "Playback blend is invalid." }
        require(updatedAtEpochMs >= 0L) { "Playback settings time is invalid." }
    }

    fun matches(manifest: SourceSeparationCacheManifest): Boolean {
        return cacheKey == manifest.cacheKey &&
            audioFingerprint == manifest.identity.source.audioFingerprint
    }

    companion object {
        const val SCHEMA_VERSION = 2
        private val CACHE_KEY_PATTERN = Regex("^[0-9a-f]{64}$")
    }
}

object SourceSeparationCacheRelativePath {
    fun requireValid(path: String) {
        require(path.isNotBlank()) { "Cache path is empty." }
        require(!path.startsWith('/') && !path.startsWith('\\')) {
            "Cache path must be relative."
        }
        require(!WINDOWS_DRIVE_PATH.matches(path)) { "Cache path must not use a drive root." }
        require('\\' !in path) { "Cache path must use forward slashes." }
        val parts = path.split('/')
        require(parts.none { it.isBlank() || it == "." || it == ".." }) {
            "Cache path contains an unsafe segment."
        }
        require(parts.none { ':' in it || '\u0000' in it }) {
            "Cache path contains an unsafe character."
        }
    }

    private val WINDOWS_DRIVE_PATH = Regex("^[A-Za-z]:.*")
}

@Serializable
data class SourceSeparationCacheHydrationMarker(
    val hydrationSchemaVersion: Int = SCHEMA_VERSION,
    val cacheKey: String,
    val sources: List<SourceSeparationCacheHydrationSource>,
    val stems: List<SourceSeparationCacheHydratedStem>,
    val createdAtEpochMs: Long,
) {
    init {
        require(hydrationSchemaVersion == SCHEMA_VERSION) {
            "Unsupported cache hydration schema: $hydrationSchemaVersion"
        }
        require(CACHE_KEY_PATTERN.matches(cacheKey)) { "Hydration cache key is invalid." }
        require(sources.size == 2 && stems.size == 2) {
            "Hydration requires exactly two source and PCM stems."
        }
        require(sources.map { it.semantic }.distinct().size == sources.size) {
            "Hydration source semantics must be unique."
        }
        require(stems.map { it.semantic }.distinct().size == stems.size) {
            "Hydration PCM semantics must be unique."
        }
        require(createdAtEpochMs >= 0L) { "Hydration creation time is invalid." }
    }

    fun matches(manifest: SourceSeparationCacheManifest): Boolean {
        if (manifest.cacheKey != cacheKey ||
            manifest.state != SourceSeparationCacheManifestState.Completed
        ) {
            return false
        }
        val outputStems = manifest.output?.stems.orEmpty().associateBy { it.semantic }
        return sources.all { source ->
            val output = outputStems[source.semantic] ?: return@all false
            val integrity = if (output.promotionValidated) {
                output.promotedIntegrity
            } else {
                output.wavIntegrity
            }
            source.path == output.playbackPath() && source.integrity == integrity
        }
    }

    companion object {
        const val SCHEMA_VERSION = 1
        private val CACHE_KEY_PATTERN = Regex("^[0-9a-f]{64}$")
    }
}

@Serializable
data class SourceSeparationCacheHydrationSource(
    val semantic: ContractStemSemantic,
    val path: String,
    val integrity: SourceSeparationCacheFileIntegrity,
) {
    init {
        SourceSeparationCacheRelativePath.requireValid(path)
    }
}

@Serializable
data class SourceSeparationCacheHydratedStem(
    val semantic: ContractStemSemantic,
    val pcmPath: String,
    val integrity: SourceSeparationCacheFileIntegrity,
) {
    init {
        SourceSeparationCacheRelativePath.requireValid(pcmPath)
    }
}
