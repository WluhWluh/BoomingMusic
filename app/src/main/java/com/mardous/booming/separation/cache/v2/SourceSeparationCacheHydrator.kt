package com.mardous.booming.separation.cache.v2

import com.mardous.booming.separation.audio.Pcm16StereoFlacEncoder
import com.mardous.booming.separation.model.contract.StemSemanticId
import java.io.File

class SourceSeparationCacheHydrator(
    private val store: SourceSeparationCacheStore,
    private val repository: SourceSeparationModelAwareCacheRepository,
    private val decoder: SourceSeparationCacheFlacDecoder = PcmSourceSeparationCacheFlacDecoder,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) {
    fun hydrate(
        cacheKey: String,
        shouldCancel: () -> Boolean = { false },
    ): SourceSeparationCacheHydrationResult {
        val lease = repository.tryAcquireRunWrite(
            cacheKey,
            SourceSeparationCacheLockPurpose.Hydration,
        )
            ?: return SourceSeparationCacheHydrationResult.Busy
        return lease.use {
            it.bindEntryDirectory(store.entryDirectory(cacheKey))
            val manifest = store.readManifest(cacheKey)
                ?.takeIf { it.state == SourceSeparationCacheManifestState.Completed }
                ?: return@use SourceSeparationCacheHydrationResult.Unavailable
            if (store.validateCompletedEntry(manifest) !=
                SourceSeparationCacheValidationResult.Valid ||
                manifest.output?.stems?.all { it.promotionValidated } != true
            ) {
                return@use SourceSeparationCacheHydrationResult.Unavailable
            }
            store.readHydrationMarker(manifest)?.let { marker ->
                return@use SourceSeparationCacheHydrationResult.AlreadyHydrated(marker)
            }
            hydrateLocked(manifest, shouldCancel)
        }
    }

    fun open(cacheKey: String): SourceSeparationModelAwareHydratedPlayback? {
        val lease = repository.tryAcquireRead(cacheKey) ?: return null
        return try {
            val manifest = store.readManifest(cacheKey)
                ?.takeIf { it.state == SourceSeparationCacheManifestState.Completed }
                ?: return lease.close().let { null }
            val marker = store.readHydrationMarker(manifest)
                ?: return lease.close().let { null }
            val descriptors = manifest.output?.stems.orEmpty().associateBy { it.stemId }
            val stems = marker.stems.map { stem ->
                val descriptor = descriptors[stem.stemId]?.descriptor()
                    ?: return lease.close().let { null }
                SourceSeparationPlaybackStemSource(
                    descriptor = descriptor,
                    file = store.resolveEntryPath(cacheKey, stem.pcmPath),
                )
            }
            if (stems.any { !it.file.isFile }) return lease.close().let { null }
            SourceSeparationModelAwareHydratedPlayback(
                manifest = manifest,
                marker = marker,
                stems = stems,
                closeAction = lease::close,
            )
        } catch (error: Throwable) {
            lease.close()
            throw error
        }
    }

    private fun hydrateLocked(
        manifest: SourceSeparationCacheManifest,
        shouldCancel: () -> Boolean,
    ): SourceSeparationCacheHydrationResult {
        val output = requireNotNull(manifest.output)
        val staging = store.resolveEntryPath(manifest.cacheKey, HYDRATION_STAGING_DIRECTORY)
        store.deleteRelativePath(manifest.cacheKey, HYDRATION_STAGING_DIRECTORY)
        check(staging.mkdirs()) { "Unable to create hydration staging directory." }
        return try {
            val sources = output.stems.map { stem ->
                SourceSeparationCacheHydrationSource(
                    stemId = stem.stemId,
                    order = stem.order,
                    path = stem.playbackPath(),
                    integrity = requireNotNull(stem.promotedIntegrity),
                )
            }
            val hydrated = output.stems.map { stem ->
                throwIfCanceled(shouldCancel)
                val source = store.resolveEntryPath(manifest.cacheKey, stem.playbackPath())
                val fileName = stemFileName(stem.order)
                val stagedPcm = File(staging, "$fileName.pcm")
                decoder.decode(
                    flacFile = source,
                    pcmFile = stagedPcm,
                    expectedSampleRate = stem.sampleRate,
                    expectedChannelCount = stem.channelCount,
                    expectedFrameCount = stem.frameCount,
                    shouldCancel = shouldCancel,
                )
                val expectedBytes = Math.multiplyExact(
                    Math.multiplyExact(stem.frameCount.toLong(), stem.channelCount.toLong()),
                    Short.SIZE_BYTES.toLong(),
                )
                require(stagedPcm.length() == expectedBytes) {
                    "Hydrated PCM size does not match the rendered stem."
                }
                val path = "$HYDRATION_OUTPUT_DIRECTORY/$fileName.pcm"
                val integrity = store.copyIntoEntryAtomically(
                    cacheKey = manifest.cacheKey,
                    source = stagedPcm,
                    relativePath = path,
                )
                SourceSeparationCacheHydratedStem(stem.stemId, stem.order, path, integrity)
            }
            throwIfCanceled(shouldCancel)
            val marker = SourceSeparationCacheHydrationMarker(
                cacheKey = manifest.cacheKey,
                sources = sources,
                stems = hydrated,
                createdAtEpochMs = nowEpochMs(),
            )
            store.writeHydrationMarker(manifest, marker)
            store.deleteRelativePath(manifest.cacheKey, HYDRATION_STAGING_DIRECTORY)
            SourceSeparationCacheHydrationResult.Completed(marker)
        } catch (error: Throwable) {
            store.deleteRelativePath(manifest.cacheKey, HYDRATION_STAGING_DIRECTORY)
            store.deleteRelativePath(manifest.cacheKey, HYDRATION_OUTPUT_DIRECTORY)
            throw error
        }
    }

    private fun stemFileName(order: Int): String = "stem-%02d".format(order)

    private fun throwIfCanceled(shouldCancel: () -> Boolean) {
        if (shouldCancel()) {
            throw java.util.concurrent.CancellationException("Cache hydration canceled.")
        }
    }

    companion object {
        const val HYDRATION_OUTPUT_DIRECTORY = "hydration/v2"
        const val HYDRATION_STAGING_DIRECTORY = "hydration/staging"
    }
}

fun interface SourceSeparationCacheFlacDecoder {
    fun decode(
        flacFile: File,
        pcmFile: File,
        expectedSampleRate: Int,
        expectedChannelCount: Int,
        expectedFrameCount: Int,
        shouldCancel: () -> Boolean,
    )
}

private object PcmSourceSeparationCacheFlacDecoder : SourceSeparationCacheFlacDecoder {
    override fun decode(
        flacFile: File,
        pcmFile: File,
        expectedSampleRate: Int,
        expectedChannelCount: Int,
        expectedFrameCount: Int,
        shouldCancel: () -> Boolean,
    ) {
        val result = Pcm16StereoFlacEncoder.decodeFlacFileToPcmFile(
            flacFile = flacFile,
            pcmFile = pcmFile,
            shouldCancel = shouldCancel,
        )
        require(result.sampleRate == expectedSampleRate) {
            "Hydrated PCM sample rate does not match the cache manifest."
        }
        require(result.channelCount == expectedChannelCount) {
            "Hydrated PCM channel count does not match the cache manifest."
        }
        require(result.frameCount == expectedFrameCount) {
            "Hydrated PCM frame count does not match the cache manifest."
        }
    }
}

class SourceSeparationModelAwareHydratedPlayback internal constructor(
    val manifest: SourceSeparationCacheManifest,
    val marker: SourceSeparationCacheHydrationMarker,
    val stems: List<SourceSeparationPlaybackStemSource>,
    private val closeAction: () -> Unit,
) : AutoCloseable {
    init {
        require(stems.isNotEmpty()) { "Hydrated playback stem set is empty." }
        require(stems.map { it.descriptor.order } == stems.indices.toList()) {
            "Hydrated playback stem order is not contiguous."
        }
    }

    val vocalsPcmFile: File
        get() = requireStemFile(StemSemanticId.Vocals)

    val instrumentalPcmFile: File
        get() = requireStemFile(StemSemanticId.Instrumental)

    private var closed = false

    fun fileFor(semanticId: StemSemanticId): File? =
        stems.singleOrNull { it.descriptor.semanticId == semanticId }?.file

    private fun requireStemFile(semanticId: StemSemanticId): File =
        requireNotNull(fileFor(semanticId)) {
            "Hydrated playback has no ${semanticId.value} stem."
        }

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
        }
        closeAction()
    }
}

sealed class SourceSeparationCacheHydrationResult {
    data class Completed(
        val marker: SourceSeparationCacheHydrationMarker,
    ) : SourceSeparationCacheHydrationResult()

    data class AlreadyHydrated(
        val marker: SourceSeparationCacheHydrationMarker,
    ) : SourceSeparationCacheHydrationResult()

    data object Busy : SourceSeparationCacheHydrationResult()
    data object Unavailable : SourceSeparationCacheHydrationResult()
}
