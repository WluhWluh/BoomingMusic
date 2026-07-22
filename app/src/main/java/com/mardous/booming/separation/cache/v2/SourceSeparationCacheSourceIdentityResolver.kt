package com.mardous.booming.separation.cache.v2

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.mardous.booming.separation.audio.AudioPcmDecoder
import com.mardous.booming.separation.audio.AudioSourceInfo
import com.mardous.booming.separation.audio.EncodedAudioSamplesHash
import java.util.concurrent.CancellationException

class SourceSeparationCacheSourceIdentityResolver(
    context: Context,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) {
    private val decoder = AudioPcmDecoder(context.applicationContext)

    fun resolve(
        uri: Uri,
        shouldCancel: () -> Boolean = { false },
    ): SourceSeparationCacheSourcePreflight {
        val startedAt = elapsedRealtime()
        throwIfCanceled(shouldCancel)
        val sourceInfo = decoder.inspect(uri)
        throwIfCanceled(shouldCancel)
        val hash = decoder.hashEncodedAudioSamples(uri, shouldCancel)
        throwIfCanceled(shouldCancel)
        return SourceSeparationCacheSourcePreflight(
            identity = from(sourceInfo, hash),
            elapsedMs = (elapsedRealtime() - startedAt).coerceAtLeast(0L),
        )
    }

    companion object {
        fun from(
            sourceInfo: AudioSourceInfo,
            hash: EncodedAudioSamplesHash,
        ): SourceSeparationCacheSourceIdentity {
            return SourceSeparationCacheSourceIdentity(
                audioFingerprint = "encoded-samples-v1:${hash.sha256}",
                encodedSampleCount = hash.sampleCount,
                encodedByteCount = hash.byteCount,
                mimeType = sourceInfo.mimeType,
                sourceSampleRate = sourceInfo.sampleRate,
                sourceChannelCount = sourceInfo.channelCount,
                sourceDurationUs = sourceInfo.durationUs,
            )
        }

        private fun throwIfCanceled(shouldCancel: () -> Boolean) {
            if (shouldCancel()) {
                throw CancellationException("Source audio identity resolution canceled.")
            }
        }
    }
}

data class SourceSeparationCacheSourcePreflight(
    val identity: SourceSeparationCacheSourceIdentity,
    val elapsedMs: Long,
)
