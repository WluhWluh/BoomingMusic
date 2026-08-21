package com.mardous.booming.playback

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSeparationFallbackStemSourceTest {
    private val geometry = SourceSeparationPlaybackGeometry(
        sampleRate = 44_100,
        channelCount = 2,
        frameCount = 32L,
    )

    @Test
    fun runtimeReadFailureSwitchesAtTheRequestedFrame() {
        val primaryReads = AtomicInteger()
        val traces = mutableListOf<String>()
        val factory = fallbackFactory(
            primary = fakeFactory(
                open = { fakeSource(geometry) { _, _, _, _ ->
                    if (primaryReads.incrementAndGet() == 1) {
                        throw IllegalStateException("decoder stalled")
                    }
                    0
                } },
            ),
            fallback = fakeFactory(
                open = { fakeSource(geometry) { _, destination, offset, count ->
                    destination.fill(7, offset, offset + count * 4)
                    count
                } },
            ),
            traces = traces,
        )

        factory.open().use { source ->
            val output = ByteArray(16)
            assertEquals(4, source.readFrames(8L, output, 0, 4))
            assertArrayEquals(ByteArray(16) { 7 }, output)
        }
        assertTrue(traces.single().contains("operation=read"))
        assertTrue(traces.single().contains("frame=8"))
    }

    @Test
    fun primaryOpenFailureUsesFallbackImmediately() {
        val traces = mutableListOf<String>()
        val factory = fallbackFactory(
            primary = fakeFactory(open = { error("codec unavailable") }),
            fallback = fakeFactory(open = { fakeSource(geometry) { _, destination, offset, count ->
                destination.fill(3, offset, offset + count * 4)
                count
            } }),
            traces = traces,
        )

        factory.open().use { source ->
            val output = ByteArray(8)
            assertEquals(2, source.readFrames(0L, output, 0, 2))
            assertArrayEquals(ByteArray(8) { 3 }, output)
        }
        assertTrue(traces.single().contains("operation=open"))
    }

    @Test
    fun seekFailureSwitchesBeforeTheNextRead() {
        val traces = mutableListOf<String>()
        val factory = fallbackFactory(
            primary = fakeFactory(open = {
                fakeSource(
                    geometry,
                    seek = { error("flush failed") },
                    read = { _, _, _, _ -> error("unreachable") },
                )
            }),
            fallback = fakeFactory(open = { fakeSource(geometry) { _, destination, offset, count ->
                destination.fill(9, offset, offset + count * 4)
                count
            } }),
            traces = traces,
        )

        factory.open().use { source ->
            source.seekToFrame(12L)
            val output = ByteArray(4)
            assertEquals(1, source.readFrames(12L, output, 0, 1))
            assertArrayEquals(ByteArray(4) { 9 }, output)
        }
        assertTrue(traces.single().contains("operation=seek"))
    }

    private fun fallbackFactory(
        primary: SourceSeparationPlaybackStemSourceFactory,
        fallback: SourceSeparationPlaybackStemSourceFactory,
        traces: MutableList<String>,
    ) = SourceSeparationFallbackStemSourceFactory(
        primary = primary,
        fallback = fallback,
        traceSink = traces::add,
    )

    private fun fakeFactory(
        open: () -> SourceSeparationPlaybackStemSource,
    ) = object : SourceSeparationPlaybackStemSourceFactory {
        override val spec = SourceSeparationPlaybackStemSpec("stem", geometry)
        override fun open(): SourceSeparationPlaybackStemSource = open()
    }

    private fun fakeSource(
        sourceGeometry: SourceSeparationPlaybackGeometry,
        seek: (Long) -> Unit = {},
        read: (Long, ByteArray, Int, Int) -> Int,
    ) = object : SourceSeparationPlaybackStemSource {
        override val geometry = sourceGeometry
        override fun seekToFrame(frame: Long) = seek(frame)
        override fun readFrames(
            startFrame: Long,
            destination: ByteArray,
            destinationOffsetBytes: Int,
            frameCount: Int,
        ): Int = read(startFrame, destination, destinationOffsetBytes, frameCount)
        override fun close() = Unit
    }
}
