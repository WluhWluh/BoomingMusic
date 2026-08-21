package com.mardous.booming.separation.cache.v2

import com.mardous.booming.separation.audio.Pcm16StereoFlacEncoder
import com.mardous.booming.separation.audio.Pcm16StereoAacEncoder
import com.mardous.booming.separation.audio.Pcm16StereoAacEncodeResult
import java.io.File

class SourceSeparationCacheFlacPromoter(
    private val store: SourceSeparationCacheStore,
    private val repository: SourceSeparationModelAwareCacheRepository,
    private val encoder: SourceSeparationCacheFlacEncoder = PcmSourceSeparationCacheFlacEncoder,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    private val aacEncoder: SourceSeparationCacheAacEncoder =
        PlatformSourceSeparationCacheAacEncoder,
) {
    /** Compatibility overload retained for existing FLAC callers and tests. */
    fun promote(
        cacheKey: String,
        shouldCancel: () -> Boolean,
    ): SourceSeparationCacheFlacPromotionResult = promote(
        cacheKey = cacheKey,
        shouldCancel = shouldCancel,
        format = SourceSeparationCacheAudioFormat.Flac,
    )

    fun promote(
        cacheKey: String,
        shouldCancel: () -> Boolean = { false },
        format: SourceSeparationCacheAudioFormat = SourceSeparationCacheAudioFormat.Flac,
    ): SourceSeparationCacheFlacPromotionResult {
        val lease = repository.tryAcquirePromotion(cacheKey)
            ?: return SourceSeparationCacheFlacPromotionResult.Busy
        return lease.use {
            it.bindEntryDirectory(store.entryDirectory(cacheKey))
            val manifest = store.readManifest(cacheKey)
                ?.takeIf { it.state == SourceSeparationCacheManifestState.Completed }
                ?: return@use SourceSeparationCacheFlacPromotionResult.Unavailable
            val existingFormats = manifest.output?.stems.orEmpty()
                .filter { it.promotionValidated }
                .mapNotNull { it.resolvedPromotedFormat() }
                .distinct()
            if (manifest.output?.stems?.all { it.promotionValidated } == true &&
                existingFormats.singleOrNull() == format
            ) {
                return@use if (store.validateCompletedEntry(manifest) ==
                    SourceSeparationCacheValidationResult.Valid
                ) {
                    SourceSeparationCacheFlacPromotionResult.AlreadyPromoted(manifest)
                } else {
                    SourceSeparationCacheFlacPromotionResult.Unavailable
                }
            }
            if (store.validateCompletedEntry(manifest) !=
                SourceSeparationCacheValidationResult.Valid
            ) {
                return@use SourceSeparationCacheFlacPromotionResult.Unavailable
            }
            if (existingFormats.isNotEmpty() && existingFormats.singleOrNull() != format &&
                !canReencodeFromWav(manifest)
            ) {
                return@use SourceSeparationCacheFlacPromotionResult.Unavailable
            }
            when (format) {
                SourceSeparationCacheAudioFormat.Flac -> promoteFlacLocked(manifest, shouldCancel)
                SourceSeparationCacheAudioFormat.AacLcM4a ->
                    promoteAacLocked(manifest, shouldCancel)
                SourceSeparationCacheAudioFormat.Wav ->
                    SourceSeparationCacheFlacPromotionResult.Unavailable
            }
        }
    }

    private fun promoteFlacLocked(
        manifest: SourceSeparationCacheManifest,
        shouldCancel: () -> Boolean,
    ): SourceSeparationCacheFlacPromotionResult {
        val output = requireNotNull(manifest.output)
        val stagingDirectory = store.resolveEntryPath(manifest.cacheKey, PROMOTION_STAGING_DIRECTORY)
        store.deleteRelativePath(manifest.cacheKey, PROMOTION_STAGING_DIRECTORY)
        check(stagingDirectory.mkdirs()) { "Unable to create FLAC promotion staging directory." }
        val publishedPaths = mutableListOf<String>()
        return try {
            val promotedStems = output.stems.map { stem ->
                throwIfCanceled(shouldCancel)
                val source = store.resolveEntryPath(manifest.cacheKey, stem.wavPath)
                val baseName = stemFileName(stem.order)
                val stagedFlac = File(stagingDirectory, "$baseName.flac")
                val encoded = encoder.encodeAndValidate(
                    wavFile = source,
                    flacFile = stagedFlac,
                    expectedSampleRate = stem.sampleRate,
                    expectedFrameCount = stem.frameCount,
                    shouldCancel = shouldCancel,
                )
                throwIfCanceled(shouldCancel)
                val promotedPath = "completed/$baseName.flac"
                val promotedIndexPath = Pcm16StereoFlacEncoder.frameIndexPathFor(promotedPath)
                val promotedIntegrity = store.copyIntoEntryAtomically(
                    cacheKey = manifest.cacheKey,
                    source = encoded.flacFile,
                    relativePath = promotedPath,
                )
                publishedPaths += promotedPath
                val promotedIndexIntegrity = store.copyIntoEntryAtomically(
                    cacheKey = manifest.cacheKey,
                    source = encoded.frameIndexFile,
                    relativePath = promotedIndexPath,
                )
                publishedPaths += promotedIndexPath
                stem.copy(
                    promotedPath = promotedPath,
                    promotedFormat = SourceSeparationCacheAudioFormat.Flac,
                    promotionValidated = true,
                    promotedIndexPath = promotedIndexPath,
                    promotedIntegrity = promotedIntegrity,
                    promotedIndexIntegrity = promotedIndexIntegrity,
                    promotedMimeType = null,
                    promotedBitRate = null,
                    promotedEncoderName = null,
                    promotedEncoderDelayFrames = null,
                    promotedPaddingFrames = null,
                    promotedSeekQuantumFrames = null,
                )
            }
            SourceSeparationCacheFaultInjection.reach(
                SourceSeparationCacheFaultStage.FlacHandoff,
                store.root().directory,
            )
            val promotedAt = nowEpochMs()
            val promoted = manifest.copy(
                output = output.copy(
                    stems = promotedStems,
                    totalBytes = store.entrySize(manifest.cacheKey),
                ),
                cleanup = SourceSeparationCacheCleanup(
                    paths = (manifest.cleanup?.paths.orEmpty() +
                        output.stems.map(SourceSeparationCacheRenderedStem::wavPath)).distinct(),
                ),
                updatedAtEpochMs = promotedAt,
                lastAccessedAtEpochMs = maxOf(manifest.lastAccessedAtEpochMs, promotedAt),
            )
            store.writeManifest(promoted)
            check(store.deleteRelativePath(manifest.cacheKey, PROMOTION_STAGING_DIRECTORY)) {
                "Unable to remove FLAC promotion staging directory."
            }
            SourceSeparationCacheFlacPromotionResult.Completed(promoted)
        } catch (error: Throwable) {
            store.deleteRelativePath(manifest.cacheKey, PROMOTION_STAGING_DIRECTORY)
            publishedPaths.forEach { path -> store.deleteRelativePath(manifest.cacheKey, path) }
            throw error
        }
    }

    private fun promoteAacLocked(
        manifest: SourceSeparationCacheManifest,
        shouldCancel: () -> Boolean,
    ): SourceSeparationCacheFlacPromotionResult {
        val output = requireNotNull(manifest.output)
        val stagingDirectory = store.resolveEntryPath(manifest.cacheKey, PROMOTION_STAGING_DIRECTORY)
        store.deleteRelativePath(manifest.cacheKey, PROMOTION_STAGING_DIRECTORY)
        check(stagingDirectory.mkdirs()) { "Unable to create AAC promotion staging directory." }
        val publishedPaths = mutableListOf<String>()
        return try {
            val promotedStems = output.stems.map { stem ->
                throwIfCanceled(shouldCancel)
                val source = store.resolveEntryPath(manifest.cacheKey, stem.wavPath)
                val baseName = stemFileName(stem.order)
                val stagedM4a = File(stagingDirectory, "$baseName.m4a")
                val encoded = aacEncoder.encodeAndValidate(
                    wavFile = source,
                    m4aFile = stagedM4a,
                    expectedSampleRate = stem.sampleRate,
                    expectedFrameCount = stem.frameCount,
                    shouldCancel = shouldCancel,
                )
                throwIfCanceled(shouldCancel)
                val promotedPath = "completed/$baseName.m4a"
                val promotedIntegrity = store.copyIntoEntryAtomically(
                    cacheKey = manifest.cacheKey,
                    source = stagedM4a,
                    relativePath = promotedPath,
                )
                publishedPaths += promotedPath
                stem.copy(
                    promotedPath = promotedPath,
                    promotedFormat = SourceSeparationCacheAudioFormat.AacLcM4a,
                    promotionValidated = true,
                    promotedIndexPath = null,
                    promotedIndexIntegrity = null,
                    promotedIntegrity = promotedIntegrity,
                    promotedMimeType = encoded.outputMimeType,
                    promotedBitRate = encoded.outputBitRate ?: encoded.bitRate,
                    promotedEncoderName = encoded.codecName,
                    promotedEncoderDelayFrames = encoded.encoderDelayFrames,
                    promotedPaddingFrames = encoded.encoderPaddingFrames,
                    promotedSeekQuantumFrames = AAC_SEEK_QUANTUM_FRAMES,
                    promotedMaxAnchorOffsetFrames = AAC_MAX_ANCHOR_OFFSET_FRAMES,
                    promotedTimestampOffsetFrames = encoded.timestampOffsetFrames,
                    promotedDecodedFrameCount = encoded.decodedFrameCount,
                )
            }
            SourceSeparationCacheFaultInjection.reach(
                SourceSeparationCacheFaultStage.FlacHandoff,
                store.root().directory,
            )
            val promotedAt = nowEpochMs()
            val promoted = manifest.copy(
                output = output.copy(
                    stems = promotedStems,
                    totalBytes = store.entrySize(manifest.cacheKey),
                ),
                // Match FLAC: active playback leases delay deletion until the
                // session has adopted the promoted source.
                cleanup = SourceSeparationCacheCleanup(
                    paths = (manifest.cleanup?.paths.orEmpty() +
                        output.stems.map(SourceSeparationCacheRenderedStem::wavPath)).distinct(),
                ),
                updatedAtEpochMs = promotedAt,
                lastAccessedAtEpochMs = maxOf(manifest.lastAccessedAtEpochMs, promotedAt),
            )
            store.writeManifest(promoted)
            check(store.deleteRelativePath(manifest.cacheKey, PROMOTION_STAGING_DIRECTORY)) {
                "Unable to remove AAC promotion staging directory."
            }
            SourceSeparationCacheFlacPromotionResult.Completed(promoted)
        } catch (error: Throwable) {
            store.deleteRelativePath(manifest.cacheKey, PROMOTION_STAGING_DIRECTORY)
            publishedPaths.forEach { path -> store.deleteRelativePath(manifest.cacheKey, path) }
            throw error
        }
    }

    private fun canReencodeFromWav(manifest: SourceSeparationCacheManifest): Boolean {
        val output = manifest.output ?: return false
        return output.stems.all { stem ->
            val integrity = stem.wavIntegrity ?: return@all false
            store.validateIntegrity(
                cacheKey = manifest.cacheKey,
                relativePath = stem.wavPath,
                expected = integrity,
                verifyHash = false,
            )
        }
    }

    private fun stemFileName(order: Int): String = "stem-%02d".format(order)

    private fun throwIfCanceled(shouldCancel: () -> Boolean) {
        if (shouldCancel()) {
            throw java.util.concurrent.CancellationException("Compression promotion canceled.")
        }
    }

    companion object {
        const val PROMOTION_STAGING_DIRECTORY = "promotion-staging"
        const val AAC_SEEK_QUANTUM_FRAMES = 1_024
        const val AAC_MAX_ANCHOR_OFFSET_FRAMES = 4_096
    }
}

fun interface SourceSeparationCacheFlacEncoder {
    fun encodeAndValidate(
        wavFile: File,
        flacFile: File,
        expectedSampleRate: Int,
        expectedFrameCount: Int,
        shouldCancel: () -> Boolean,
    ): SourceSeparationCacheEncodedFlac
}

fun interface SourceSeparationCacheAacEncoder {
    fun encodeAndValidate(
        wavFile: File,
        m4aFile: File,
        expectedSampleRate: Int,
        expectedFrameCount: Int,
        shouldCancel: () -> Boolean,
    ): Pcm16StereoAacEncodeResult
}

private object PlatformSourceSeparationCacheAacEncoder : SourceSeparationCacheAacEncoder {
    override fun encodeAndValidate(
        wavFile: File,
        m4aFile: File,
        expectedSampleRate: Int,
        expectedFrameCount: Int,
        shouldCancel: () -> Boolean,
    ): Pcm16StereoAacEncodeResult = Pcm16StereoAacEncoder.encodeWavToM4a(
        wavFile = wavFile,
        m4aFile = m4aFile,
        expectedSampleRate = expectedSampleRate,
        expectedFrameCount = expectedFrameCount,
        shouldCancel = shouldCancel,
    )
}

data class SourceSeparationCacheEncodedFlac(
    val flacFile: File,
    val frameIndexFile: File,
)


private object PcmSourceSeparationCacheFlacEncoder : SourceSeparationCacheFlacEncoder {
    override fun encodeAndValidate(
        wavFile: File,
        flacFile: File,
        expectedSampleRate: Int,
        expectedFrameCount: Int,
        shouldCancel: () -> Boolean,
    ): SourceSeparationCacheEncodedFlac {
        val encoded = Pcm16StereoFlacEncoder.encodeWavToFlac(
            wavFile = wavFile,
            flacFile = flacFile,
            expectedSampleRate = expectedSampleRate,
            expectedFrameCount = expectedFrameCount,
            shouldCancel = shouldCancel,
        )
        Pcm16StereoFlacEncoder.verifyFlacFile(
            flacFile = flacFile,
            expectedSampleRate = expectedSampleRate,
            expectedFrameCount = expectedFrameCount,
            expectedPcmMd5Hex = encoded.pcmMd5Hex,
            shouldCancel = shouldCancel,
        )
        val frameIndex = Pcm16StereoFlacEncoder.frameIndexFileFor(flacFile)
        require(frameIndex.isFile) { "Promoted FLAC frame index is missing." }
        return SourceSeparationCacheEncodedFlac(flacFile, frameIndex)
    }
}

sealed class SourceSeparationCacheFlacPromotionResult {
    data class Completed(
        val manifest: SourceSeparationCacheManifest,
    ) : SourceSeparationCacheFlacPromotionResult()

    data class AlreadyPromoted(
        val manifest: SourceSeparationCacheManifest,
    ) : SourceSeparationCacheFlacPromotionResult()

    data object Busy : SourceSeparationCacheFlacPromotionResult()
    data object Unavailable : SourceSeparationCacheFlacPromotionResult()
}
