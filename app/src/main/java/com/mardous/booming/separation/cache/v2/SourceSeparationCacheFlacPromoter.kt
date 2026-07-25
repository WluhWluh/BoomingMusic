package com.mardous.booming.separation.cache.v2

import com.mardous.booming.separation.audio.Pcm16StereoFlacEncoder
import com.mardous.booming.separation.model.contract.ContractStemSemantic
import java.io.File
import java.util.Locale

class SourceSeparationCacheFlacPromoter(
    private val store: SourceSeparationCacheStore,
    private val repository: SourceSeparationModelAwareCacheRepository,
    private val encoder: SourceSeparationCacheFlacEncoder = PcmSourceSeparationCacheFlacEncoder,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) {
    fun promote(
        cacheKey: String,
        shouldCancel: () -> Boolean = { false },
    ): SourceSeparationCacheFlacPromotionResult {
        val lease = repository.tryAcquireExclusive(
            cacheKey,
            SourceSeparationCacheLockPurpose.Promotion,
        )
            ?: return SourceSeparationCacheFlacPromotionResult.Busy
        return lease.use {
            it.bindEntryDirectory(store.entryDirectory(cacheKey))
            val manifest = store.readManifest(cacheKey)
                ?.takeIf { it.state == SourceSeparationCacheManifestState.Completed }
                ?: return@use SourceSeparationCacheFlacPromotionResult.Unavailable
            if (manifest.output?.stems?.all { it.promotionValidated } == true) {
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
            promoteLocked(manifest, shouldCancel)
        }
    }

    private fun promoteLocked(
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
                val baseName = stem.semantic.fileName()
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
                val promotedIndexPath = "$promotedPath.frames"
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
                )
            }
            SourceSeparationCacheFaultInjection.reach(
                SourceSeparationCacheFaultStage.FlacHandoff,
                store.root().directory,
            )
            check(store.deleteRelativePath(manifest.cacheKey, PROMOTION_STAGING_DIRECTORY)) {
                "Unable to remove FLAC promotion staging directory."
            }
            val promoted = manifest.copy(
                output = output.copy(
                    stems = promotedStems,
                    totalBytes = store.entrySize(manifest.cacheKey),
                ),
                cleanup = SourceSeparationCacheCleanup(
                    paths = (manifest.cleanup?.paths.orEmpty() +
                        output.stems.map(SourceSeparationCacheRenderedStem::wavPath)).distinct(),
                ),
                updatedAtEpochMs = nowEpochMs(),
            )
            store.writeManifest(promoted)
            SourceSeparationCacheFlacPromotionResult.Completed(promoted)
        } catch (error: Throwable) {
            store.deleteRelativePath(manifest.cacheKey, PROMOTION_STAGING_DIRECTORY)
            publishedPaths.forEach { path -> store.deleteRelativePath(manifest.cacheKey, path) }
            throw error
        }
    }

    private fun ContractStemSemantic.fileName(): String =
        name.lowercase(Locale.US).replace('_', '-')

    private fun throwIfCanceled(shouldCancel: () -> Boolean) {
        if (shouldCancel()) {
            throw java.util.concurrent.CancellationException("FLAC promotion canceled.")
        }
    }

    companion object {
        const val PROMOTION_STAGING_DIRECTORY = "promotion-staging"
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
