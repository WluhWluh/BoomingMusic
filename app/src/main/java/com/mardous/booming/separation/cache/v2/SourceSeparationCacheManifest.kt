package com.mardous.booming.separation.cache.v2

import com.mardous.booming.separation.cache.SourceSeparationSegmentPlan
import com.mardous.booming.separation.cache.SourceSeparationCacheRelativePath
import com.mardous.booming.separation.model.contract.StemDescriptor
import com.mardous.booming.separation.model.contract.StemId
import com.mardous.booming.separation.model.contract.StemProduction
import com.mardous.booming.separation.model.contract.StemSemanticId
import com.mardous.booming.separation.model.contract.StemSet
import com.mardous.booming.separation.model.contract.toStemSet
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
        val expectedStems = contract.expectedStemSet()
        output?.validate(state, expectedStems, contract.outputChannelCount())
        segmentPlan?.let { plan ->
            require(plan.stemIds == expectedStems.stems.map(StemDescriptor::stemId)) {
                "Cache segment plan does not match the contract stem set."
            }
        }
        cleanup?.paths?.forEach(SourceSeparationCacheRelativePath::requireValid)
    }

    companion object {
        const val SCHEMA_VERSION = 4
    }
}

@Serializable
enum class SourceSeparationCacheManifestState {
    Partial,
    Completed,
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
        require(stems.isNotEmpty()) { "A cache output must contain at least one stem." }
        require(stems.map(SourceSeparationCacheRenderedStem::stemId).distinct().size == stems.size) {
            "Cache output stem IDs must be unique."
        }
        require(stems.map(SourceSeparationCacheRenderedStem::order) == stems.indices.toList()) {
            "Cache output stem order must be contiguous."
        }
        val artifactPaths = stems.flatMap { stem ->
            listOfNotNull(stem.wavPath, stem.promotedPath, stem.promotedIndexPath)
        }
        require(artifactPaths.distinct().size == artifactPaths.size) {
            "Cache output artifact paths must be unique."
        }
        timingPath?.let(SourceSeparationCacheRelativePath::requireValid)
        require(outputSampleRate > 0) { "Cache output sample rate is invalid." }
        require(outputFrameCount > 0) { "Cache output frame count is invalid." }
        require(stems.all { stem ->
            stem.sampleRate == outputSampleRate && stem.frameCount == outputFrameCount
        }) { "Cache output stem audio geometry is inconsistent." }
        require(windowCount > 0) { "Cache output window count is invalid." }
        require(elapsedMs >= 0L) { "Cache output elapsed time is invalid." }
        require(totalBytes >= 0L) { "Cache output size is invalid." }
    }

    internal fun validate(
        state: SourceSeparationCacheManifestState,
        expectedStemSet: StemSet,
        expectedChannelCount: Int,
    ) {
        require(stems.map(SourceSeparationCacheRenderedStem::descriptor) ==
            expectedStemSet.stems
        ) { "Cache output does not match the complete contract stem set." }
        require(stems.all { it.channelCount == expectedChannelCount }) {
            "Cache output stem channel count does not match the contract."
        }
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
    val stemId: StemId,
    val semanticId: StemSemanticId,
    val canonicalLabel: String,
    val order: Int,
    val production: StemProduction,
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
        StemDescriptor(stemId, semanticId, canonicalLabel, order, production)
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

    fun descriptor(): StemDescriptor = StemDescriptor(
        stemId = stemId,
        semanticId = semanticId,
        canonicalLabel = canonicalLabel,
        order = order,
        production = production,
    )

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
    val fallbackStage: String? = null,
    val fallbackReason: String? = null,
    val sourceDecodeMode: String? = null,
    val sourceDecodeProfile: String? = null,
    val sourceDecodeMimeType: String? = null,
    val sourceDecodeFallbackReason: String? = null,
    val sourceDecodeSampleRate: Int? = null,
    val sourceDecodeChannelCount: Int? = null,
    val sourceDecodeSourceFrameCount: Int? = null,
    val sourceDecodeOutputFrameCount: Int? = null,
    val sourceDecodeEncoderDelayFrames: Int? = null,
    val sourceDecodeEncoderPaddingFrames: Int? = null,
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
    val stemGains: Map<String, Float> = emptyMap(),
    val updatedAtEpochMs: Long,
) {
    init {
        require(playbackSettingsSchemaVersion == SCHEMA_VERSION) {
            "Unsupported playback settings schema: $playbackSettingsSchemaVersion"
        }
        require(CACHE_KEY_PATTERN.matches(cacheKey)) { "Playback cache key is invalid." }
        require(audioFingerprint.isNotBlank()) { "Playback source fingerprint is empty." }
        require(blend in 0f..1f) { "Playback blend is invalid." }
        require(stemGains.keys.all(String::isNotBlank)) {
            "Playback stem gain ID is empty."
        }
        require(stemGains.values.all { gain -> gain.isFinite() && gain in 0f..1f }) {
            "Playback stem gain is invalid."
        }
        require(updatedAtEpochMs >= 0L) { "Playback settings time is invalid." }
    }

    fun matches(manifest: SourceSeparationCacheManifest): Boolean {
        return cacheKey == manifest.cacheKey &&
            audioFingerprint == manifest.identity.source.audioFingerprint
    }

    companion object {
        const val SCHEMA_VERSION = 3
        private val CACHE_KEY_PATTERN = Regex("^[0-9a-f]{64}$")
    }
}
