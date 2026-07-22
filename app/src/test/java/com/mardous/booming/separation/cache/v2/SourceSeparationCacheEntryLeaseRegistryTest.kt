package com.mardous.booming.separation.cache.v2

import com.mardous.booming.separation.audio.AudioDecodeTrackMetadata
import com.mardous.booming.separation.audio.AudioSourceInfo
import com.mardous.booming.separation.audio.EncodedAudioSamplesHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSeparationCacheEntryLeaseRegistryTest {

    @Test
    fun `read leases coexist and block mutation for the exact entry`() {
        val registry = SourceSeparationCacheEntryLeaseRegistry()
        val first = requireNotNull(registry.tryAcquireRead(KEY_A))
        val second = requireNotNull(registry.tryAcquireRead(KEY_A))

        assertNull(registry.tryAcquireMutation(KEY_A))
        assertEquals(
            SourceSeparationCacheLeaseSnapshot(readerCount = 2, mutationActive = false),
            registry.snapshot()[KEY_A],
        )

        first.close()
        assertNull(registry.tryAcquireMutation(KEY_A))
        second.close()
        assertNotNull(registry.tryAcquireMutation(KEY_A)?.also { it.close() })
        assertFalse(registry.isLeased(KEY_A))
    }

    @Test
    fun `mutation blocks readers but does not protect another model entry`() {
        val registry = SourceSeparationCacheEntryLeaseRegistry()
        val mutation = requireNotNull(registry.tryAcquireMutation(KEY_A))

        assertNull(registry.tryAcquireRead(KEY_A))
        assertNull(registry.tryAcquireMutation(KEY_A))
        assertNotNull(registry.tryAcquireMutation(KEY_B)?.also { it.close() })
        assertTrue(registry.isLeased(KEY_A))

        mutation.close()
        assertNotNull(registry.tryAcquireRead(KEY_A)?.also { it.close() })
        assertTrue(registry.snapshot().isEmpty())
    }

    @Test
    fun `closing a lease twice is idempotent`() {
        val registry = SourceSeparationCacheEntryLeaseRegistry()
        val lease = requireNotNull(registry.tryAcquireRead(KEY_A))

        lease.close()
        lease.close()

        assertFalse(registry.isLeased(KEY_A))
    }

    @Test
    fun `source preflight identity is stable before decoding`() {
        val identity = SourceSeparationCacheSourceIdentityResolver.from(
            sourceInfo = AudioSourceInfo(
                mimeType = "audio/flac",
                sampleRate = 44_100,
                channelCount = 2,
                durationUs = 3_000_000L,
                trackMetadata = AudioDecodeTrackMetadata(
                    mimeType = "audio/flac",
                    encoderDelayFrames = null,
                    encoderPaddingFrames = null,
                ),
            ),
            hash = EncodedAudioSamplesHash(
                sha256 = "a".repeat(64),
                sampleCount = 120L,
                byteCount = 4_096L,
            ),
        )

        assertEquals("encoded-samples-v1:${"a".repeat(64)}", identity.audioFingerprint)
        assertEquals(120L, identity.encodedSampleCount)
        assertEquals(4_096L, identity.encodedByteCount)
        assertEquals(3_000_000L, identity.sourceDurationUs)
    }

    private companion object {
        val KEY_A = "a".repeat(64)
        val KEY_B = "b".repeat(64)
    }
}
