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

        assertNull(registry.tryAcquireExclusive(KEY_A))
        assertNull(registry.tryAcquireCleanup(KEY_A, setOf("work")))
        assertEquals(
            SourceSeparationCacheLeaseSnapshot(
                readerCount = 2,
                runWriterActive = false,
                promotionActive = false,
                exclusiveActive = false,
            ),
            registry.snapshot()[KEY_A],
        )

        first.close()
        assertNull(registry.tryAcquireExclusive(KEY_A))
        second.close()
        assertNotNull(registry.tryAcquireExclusive(KEY_A)?.also { it.close() })
        assertFalse(registry.isLeased(KEY_A))
    }

    @Test
    fun `promoted artifact reader allows cleanup of obsolete wav paths`() {
        val registry = SourceSeparationCacheEntryLeaseRegistry()
        val reader = requireNotNull(
            registry.tryAcquireArtifactRead(
                KEY_A,
                setOf("completed/stem-00.flac", "completed/stem-01.flac"),
            )
        )

        val cleanup = requireNotNull(
            registry.tryAcquireCleanup(
                KEY_A,
                setOf("work", "segments", "completed/stem-00.wav"),
            )
        )
        assertNull(registry.tryAcquireRead(KEY_A))
        assertEquals(1, registry.snapshot()[KEY_A]?.readerCount)

        cleanup.close()
        assertTrue(registry.isLeased(KEY_A))
        reader.close()
        assertFalse(registry.isLeased(KEY_A))
    }

    @Test
    fun `artifact reader blocks cleanup of its file or parent directory`() {
        val registry = SourceSeparationCacheEntryLeaseRegistry()
        val reader = requireNotNull(
            registry.tryAcquireArtifactRead(KEY_A, setOf("work/vocals.wav"))
        )

        assertNull(registry.tryAcquireCleanup(KEY_A, setOf("work/vocals.wav")))
        assertNull(registry.tryAcquireCleanup(KEY_A, setOf("work")))

        reader.close()
        assertNotNull(registry.tryAcquireCleanup(KEY_A, setOf("work"))?.also { it.close() })
    }

    @Test
    fun `complete multistem playback lease blocks whole entry deletion`() {
        listOf(4, 6, 8).forEach { stemCount ->
            val registry = SourceSeparationCacheEntryLeaseRegistry()
            val protectedPaths = (0 until stemCount).flatMap { order ->
                listOf(
                    "completed/stem-%02d.flac".format(order),
                    "completed/stem-%02d.flac.idx".format(order),
                )
            }.toSet()
            val playback = requireNotNull(
                registry.tryAcquireArtifactRead(KEY_A, protectedPaths),
            )

            assertNull(registry.tryAcquireExclusive(KEY_A))
            assertNull(
                registry.tryAcquireCleanup(
                    KEY_A,
                    setOf("completed/stem-%02d.flac".format(stemCount - 1)),
                ),
            )
            val obsoleteCleanup = requireNotNull(
                registry.tryAcquireCleanup(KEY_A, setOf("work", "segments")),
            )
            obsoleteCleanup.close()
            assertNull(registry.tryAcquireExclusive(KEY_A))

            playback.close()
            assertNotNull(registry.tryAcquireExclusive(KEY_A)?.also { it.close() })
            assertFalse(registry.isLeased(KEY_A))
        }
    }

    @Test
    fun `exclusive lease blocks readers but does not protect another model entry`() {
        val registry = SourceSeparationCacheEntryLeaseRegistry()
        val mutation = requireNotNull(registry.tryAcquireExclusive(KEY_A))

        assertNull(registry.tryAcquireRead(KEY_A))
        assertNull(registry.tryAcquireRunWrite(KEY_A))
        assertNull(registry.tryAcquireExclusive(KEY_A))
        assertNotNull(registry.tryAcquireExclusive(KEY_B)?.also { it.close() })
        assertTrue(registry.isLeased(KEY_A))

        mutation.close()
        assertNotNull(registry.tryAcquireRead(KEY_A)?.also { it.close() })
        assertTrue(registry.snapshot().isEmpty())
    }

    @Test
    fun `one run writer can coexist with playback readers`() {
        val registry = SourceSeparationCacheEntryLeaseRegistry()
        val writer = requireNotNull(registry.tryAcquireRunWrite(KEY_A))
        val reader = requireNotNull(registry.tryAcquireRead(KEY_A))

        assertNull(registry.tryAcquireRunWrite(KEY_A))
        assertNull(registry.tryAcquireExclusive(KEY_A))
        assertEquals(
            SourceSeparationCacheLeaseSnapshot(
                readerCount = 1,
                runWriterActive = true,
                promotionActive = false,
                exclusiveActive = false,
            ),
            registry.snapshot()[KEY_A],
        )

        reader.close()
        writer.close()
        assertTrue(registry.snapshot().isEmpty())
    }

    @Test
    fun `promotion coexists with readers and blocks every cache mutation`() {
        val registry = SourceSeparationCacheEntryLeaseRegistry()
        val reader = requireNotNull(registry.tryAcquireRead(KEY_A))
        val promotion = requireNotNull(registry.tryAcquirePromotion(KEY_A))

        assertNotNull(registry.tryAcquireRead(KEY_A)?.also { it.close() })
        assertNull(registry.tryAcquireRunWrite(KEY_A))
        assertNull(registry.tryAcquirePromotion(KEY_A))
        assertNull(registry.tryAcquireExclusive(KEY_A))
        assertEquals(
            SourceSeparationCacheLeaseSnapshot(
                readerCount = 1,
                runWriterActive = false,
                promotionActive = true,
                exclusiveActive = false,
            ),
            registry.snapshot()[KEY_A],
        )

        promotion.close()
        assertNull(registry.tryAcquireExclusive(KEY_A))
        reader.close()
        assertNotNull(registry.tryAcquireExclusive(KEY_A)?.also { it.close() })
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
