package com.mardous.booming.playback

import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSeparationStemPlaybackEngineMultistemTest {
    @Test
    fun orderedStemSetsArePublishedForTwoFourSixAndEightStems() {
        listOf(2, 4, 6, 8).forEach { stemCount ->
            val frameCount = 32
            val geometry = geometry(frameCount)
            val factories = (0 until stemCount).map { stemIndex ->
                testFactory(
                    stemId = "stem-$stemIndex",
                    geometry = geometry,
                    pcm = pcm(stemIndex * 1_000, frameCount),
                )
            }
            val engine = SourceSeparationStemPlaybackEngine(
                blockFrames = 4,
                resumeWaterlineBlocks = 1,
                targetWaterlineBlocks = 2,
                blockCapacity = 3,
            )
            try {
                engine.start(sessionId = stemCount.toLong(), factories = factories)
                if (stemCount == 8) {
                    await {
                        engine.metricsSnapshot().activeStemCount == stemCount
                    }
                    assertEquals(
                        8 * 4 * BYTES_PER_FRAME * 3L,
                        engine.metricsSnapshot().bufferPoolBytes,
                    )
                }
                val output = Array(stemCount) { ByteArray(frameCount * BYTES_PER_FRAME) }
                var outputFrame = 0
                while (outputFrame < frameCount) {
                    await { engine.hasResumeWaterline() }
                    val frames = minOf(4, frameCount - outputFrame)
                    val chunk = Array(stemCount) { ByteArray(frames * BYTES_PER_FRAME) }
                    assertEquals(frames, engine.readInto(chunk, frames))
                    chunk.forEachIndexed { stemIndex, bytes ->
                        bytes.copyInto(
                            destination = output[stemIndex],
                            destinationOffset = outputFrame * BYTES_PER_FRAME,
                        )
                    }
                    outputFrame += frames
                }

                output.forEachIndexed { stemIndex, bytes ->
                    assertArrayEquals(
                        pcm(stemIndex * 1_000, frameCount),
                        bytes,
                    )
                }
            } finally {
                engine.close()
            }
        }
    }

    @Test
    fun seekAndHotSwapKeepAllEightStemsOnOneEpoch() {
        val stemCount = 8
        val frameCount = 64
        val geometry = geometry(frameCount)
        val engine = SourceSeparationStemPlaybackEngine(
            blockFrames = 4,
            resumeWaterlineBlocks = 1,
            targetWaterlineBlocks = 2,
            blockCapacity = 3,
        )
        try {
            engine.start(
                sessionId = 1L,
                factories = factories(stemCount, geometry, base = 0),
            )
            await { engine.hasResumeWaterline() }
            val seekEpoch = engine.seekTo(24L)
            await { engine.currentEpoch == seekEpoch && engine.hasResumeWaterline() }
            assertCurrentBlock(engine, stemCount, expectedStart = 24, base = 0)

            val swapEpoch = engine.hotSwap(
                sessionId = 2L,
                factories = factories(stemCount, geometry, base = 20_000),
                startFrame = 40L,
            )
            await { engine.currentEpoch == swapEpoch && engine.hasResumeWaterline() }
            assertCurrentBlock(engine, stemCount, expectedStart = 40, base = 20_000)
            assertTrue(swapEpoch > seekEpoch)
        } finally {
            engine.close()
        }
    }

    @Test
    fun oneShortStemFailsTheCompleteEightStemSession() {
        val stemCount = 8
        val frameCount = 16
        val geometry = geometry(frameCount)
        val factories = (0 until stemCount).map { stemIndex ->
            testFactory(
                stemId = "stem-$stemIndex",
                geometry = geometry,
                pcm = pcm(stemIndex * 1_000, if (stemIndex == 5) 4 else frameCount),
            )
        }
        val engine = SourceSeparationStemPlaybackEngine(
            blockFrames = 4,
            resumeWaterlineBlocks = 1,
            targetWaterlineBlocks = 2,
            blockCapacity = 3,
        )
        try {
            engine.start(1L, factories)
            await { engine.currentState == SourceSeparationPlaybackDataState.Failed }
            assertEquals(
                0,
                engine.readInto(
                    destinations = Array(stemCount) { ByteArray(4 * BYTES_PER_FRAME) },
                    frameCount = 4,
                ),
            )
        } finally {
            engine.close()
        }
    }

    @Test
    fun destinationCountMustMatchTheActiveEightStemSession() {
        val stemCount = 8
        val geometry = geometry(16)
        val engine = SourceSeparationStemPlaybackEngine(
            blockFrames = 4,
            resumeWaterlineBlocks = 1,
            targetWaterlineBlocks = 2,
            blockCapacity = 3,
        )
        try {
            engine.start(1L, factories(stemCount, geometry, base = 0))
            await { engine.hasResumeWaterline() }
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                engine.readInto(
                    destinations = Array(stemCount - 1) {
                        ByteArray(4 * BYTES_PER_FRAME)
                    },
                    frameCount = 4,
                )
            }
        } finally {
            engine.close()
        }
    }

    @Test
    fun recreatedEngineReinstallsTheCompleteSixStemSet() {
        val stemCount = 6
        val frameCount = 24
        val geometry = geometry(frameCount)
        repeat(2) { recreation ->
            val engine = SourceSeparationStemPlaybackEngine(
                blockFrames = 4,
                resumeWaterlineBlocks = 1,
                targetWaterlineBlocks = 2,
                blockCapacity = 3,
            )
            try {
                engine.start(
                    sessionId = recreation.toLong() + 1L,
                    factories = factories(stemCount, geometry, base = recreation * 10_000),
                )
                await { engine.hasResumeWaterline() }
                assertCurrentBlock(
                    engine = engine,
                    stemCount = stemCount,
                    expectedStart = 0,
                    base = recreation * 10_000,
                )
            } finally {
                engine.close()
            }
        }
    }

    @Test
    fun closingDuringAnEightStemDecodeStopsTheInFlightSession() {
        val geometry = geometry(32)
        val decodeStarted = java.util.concurrent.CountDownLatch(1)
        val closedSources = AtomicInteger()
        val releaseDecode = java.util.concurrent.CountDownLatch(1)
        val factories = (0 until 8).map { stemIndex ->
            val sourcePcm = pcm(stemIndex * 1_000, 32)
            object : SourceSeparationPlaybackStemSourceFactory {
                override val spec = SourceSeparationPlaybackStemSpec("stem-$stemIndex", geometry)

                override fun open(): SourceSeparationPlaybackStemSource {
                    return object : SourceSeparationPlaybackStemSource {
                        override val geometry = spec.geometry

                        override fun readFrames(
                            startFrame: Long,
                            destination: ByteArray,
                            destinationOffsetBytes: Int,
                            frameCount: Int,
                        ): Int {
                            if (startFrame == 0L) {
                                decodeStarted.countDown()
                                releaseDecode.await()
                            }
                            sourcePcm.copyInto(
                                destination = destination,
                                destinationOffset = destinationOffsetBytes,
                                startIndex = startFrame.toInt() * BYTES_PER_FRAME,
                                endIndex = startFrame.toInt() * BYTES_PER_FRAME +
                                        frameCount * BYTES_PER_FRAME,
                            )
                            return frameCount
                        }

                        override fun close() {
                            closedSources.incrementAndGet()
                        }
                    }
                }
            }
        }
        val engine = SourceSeparationStemPlaybackEngine(
            blockFrames = 4,
            resumeWaterlineBlocks = 1,
            targetWaterlineBlocks = 2,
            blockCapacity = 3,
        )
        try {
            engine.start(1L, factories)
            assertTrue(decodeStarted.await(2, java.util.concurrent.TimeUnit.SECONDS))
            engine.close()
            assertTrue(closedSources.get() >= 1)
            assertEquals(SourceSeparationPlaybackDataState.Idle, engine.currentState)
        } finally {
            releaseDecode.countDown()
            engine.close()
        }
    }

    @Test
    fun moreThanEightStemsAreRejectedBeforeWorkerStartup() {
        val geometry = geometry(16)
        val factories = (0 until 9).map { stemIndex ->
            testFactory(
                stemId = "stem-$stemIndex",
                geometry = geometry,
                pcm = pcm(stemIndex * 1_000, 16),
            )
        }
        val engine = SourceSeparationStemPlaybackEngine(
            blockFrames = 4,
            resumeWaterlineBlocks = 1,
            targetWaterlineBlocks = 2,
            blockCapacity = 3,
        )
        try {
            assertThrows(IllegalArgumentException::class.java) {
                engine.start(1L, factories)
            }
        } finally {
            engine.close()
        }
    }

    private fun assertCurrentBlock(
        engine: SourceSeparationStemPlaybackEngine,
        stemCount: Int,
        expectedStart: Int,
        base: Int,
    ) {
        val output = Array(stemCount) { ByteArray(4 * BYTES_PER_FRAME) }
        assertEquals(4, engine.readInto(output, 4))
        output.forEachIndexed { stemIndex, bytes ->
            assertArrayEquals(
                pcm(base + stemIndex * 1_000 + expectedStart, 4),
                bytes,
            )
        }
    }

    private fun factories(
        stemCount: Int,
        geometry: SourceSeparationPlaybackGeometry,
        base: Int,
    ): List<SourceSeparationPlaybackStemSourceFactory> {
        return (0 until stemCount).map { stemIndex ->
            testFactory(
                stemId = "stem-$stemIndex",
                geometry = geometry,
                pcm = pcm(base + stemIndex * 1_000, geometry.frameCount.toInt()),
            )
        }
    }

    private fun geometry(frameCount: Int): SourceSeparationPlaybackGeometry {
        return SourceSeparationPlaybackGeometry(
            sampleRate = 44_100,
            channelCount = 2,
            frameCount = frameCount.toLong(),
        )
    }

    private fun pcm(start: Int, frames: Int): ByteArray {
        val output = ByteArrayOutputStream(frames * BYTES_PER_FRAME)
        for (frame in start until start + frames) {
            val sample = frame.toShort()
            output.write(sample.toInt() and 0xFF)
            output.write((sample.toInt() ushr 8) and 0xFF)
            output.write((-sample.toInt()) and 0xFF)
            output.write((-sample.toInt() ushr 8) and 0xFF)
        }
        return output.toByteArray()
    }

    private fun testFactory(
        stemId: String,
        geometry: SourceSeparationPlaybackGeometry,
        pcm: ByteArray,
    ): SourceSeparationPlaybackStemSourceFactory {
        return object : SourceSeparationPlaybackStemSourceFactory {
            override val spec = SourceSeparationPlaybackStemSpec(stemId, geometry)

            override fun open(): SourceSeparationPlaybackStemSource {
                return object : SourceSeparationPlaybackStemSource {
                    private val closed = AtomicBoolean(false)

                    override val geometry = spec.geometry

                    override fun readFrames(
                        startFrame: Long,
                        destination: ByteArray,
                        destinationOffsetBytes: Int,
                        frameCount: Int,
                    ): Int {
                        if (closed.get()) return 0
                        val sourceOffset = startFrame.toInt() * BYTES_PER_FRAME
                        val byteCount = frameCount * BYTES_PER_FRAME
                        if (sourceOffset < 0 || sourceOffset + byteCount > pcm.size) return 0
                        pcm.copyInto(
                            destination = destination,
                            destinationOffset = destinationOffsetBytes,
                            startIndex = sourceOffset,
                            endIndex = sourceOffset + byteCount,
                        )
                        return frameCount
                    }

                    override fun close() {
                        closed.set(true)
                    }
                }
            }
        }
    }

    private fun await(timeoutMs: Long = 2_000L, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(2L)
        }
        assertTrue("Timed out waiting for playback engine", condition())
    }

    private companion object {
        const val BYTES_PER_FRAME = 4
    }
}
