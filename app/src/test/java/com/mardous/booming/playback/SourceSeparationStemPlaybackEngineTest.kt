package com.mardous.booming.playback

import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSeparationStemPlaybackEngineTest {
    @Test
    fun enginePublishesOnlyCompleteSameFrameBlocks() {
        val geometry = geometry(frameCount = 12)
        val vocals = testFactory("vocals", geometry, pcm(0, 12))
        val instrumental = testFactory("instrumental", geometry, pcm(100, 12))
        val engine = SourceSeparationStemPlaybackEngine(
            blockFrames = 4,
            resumeWaterlineBlocks = 1,
            targetWaterlineBlocks = 2,
            blockCapacity = 3,
        )
        try {
            engine.start(1L, listOf(vocals, instrumental))
            await {
                engine.hasResumeWaterline() &&
                    engine.currentState == SourceSeparationPlaybackDataState.Ready
            }
            val output = Array(2) { ByteArray(12 * 4) }
            repeat(3) { chunkIndex ->
                await { engine.hasResumeWaterline() }
                val chunk = Array(2) { ByteArray(4 * 4) }
                assertEquals(4, engine.readInto(chunk, 4))
                chunk.forEachIndexed { stemIndex, bytes ->
                    bytes.copyInto(
                        destination = output[stemIndex],
                        destinationOffset = chunkIndex * bytes.size,
                    )
                }
            }
            assertArrayEquals(pcm(0, 12), output[0])
            assertArrayEquals(pcm(100, 12), output[1])
            assertTrue(engine.metricsSnapshot().decodeBlockCount >= 3L)
        } finally {
            engine.close()
        }
    }

    @Test
    fun seekChangesEpochAndDropsOldReadyBlocks() {
        val geometry = geometry(frameCount = 16)
        val engine = SourceSeparationStemPlaybackEngine(
            blockFrames = 4,
            resumeWaterlineBlocks = 1,
            targetWaterlineBlocks = 2,
            blockCapacity = 3,
        )
        try {
            engine.start(
                1L,
                listOf(testFactory("vocals", geometry, pcm(0, 16))),
            )
            await { engine.hasResumeWaterline() }
            val oldEpoch = engine.currentEpoch
            val newEpoch = engine.seekTo(8L)
            assertTrue(newEpoch > oldEpoch)
            await { engine.hasResumeWaterline() && engine.currentEpoch == newEpoch }
            val output = Array(1) { ByteArray(4 * 4) }
            assertEquals(4, engine.readInto(output, 4))
            assertArrayEquals(pcm(8, 4), output[0])
        } finally {
            engine.close()
        }
    }

    @Test
    fun seekRejectsAnOldEpochBlockAlreadyBeingDecoded() {
        val geometry = geometry(frameCount = 24)
        val oldDecodeStarted = CountDownLatch(1)
        val releaseOldDecode = CountDownLatch(1)
        val engine = SourceSeparationStemPlaybackEngine(
            blockFrames = 4,
            resumeWaterlineBlocks = 1,
            targetWaterlineBlocks = 2,
            blockCapacity = 3,
        )
        try {
            engine.start(
                1L,
                listOf(
                    testFactory("vocals", geometry, pcm(0, 24)) { startFrame ->
                        if (startFrame == 0L) {
                            oldDecodeStarted.countDown()
                            releaseOldDecode.await()
                        }
                    },
                ),
            )
            assertTrue(oldDecodeStarted.await(2, java.util.concurrent.TimeUnit.SECONDS))
            val seekEpoch = engine.seekTo(12L)
            releaseOldDecode.countDown()
            await { engine.currentEpoch == seekEpoch && engine.hasResumeWaterline() }

            val output = Array(1) { ByteArray(4 * 4) }
            assertEquals(4, engine.readInto(output, 4))
            assertArrayEquals(pcm(12, 4), output[0])
        } finally {
            releaseOldDecode.countDown()
            engine.close()
        }
    }

    @Test
    fun seekResumesAfterOneBlockWhilePrefetchContinues() {
        val geometry = geometry(frameCount = 80)
        val seekFrame = 32L
        val seekFirstBlockStarted = CountDownLatch(1)
        val allowSeekPrefetch = CountDownLatch(1)
        val engine = SourceSeparationStemPlaybackEngine(
            blockFrames = 4,
            resumeWaterlineBlocks = 3,
            targetWaterlineBlocks = 4,
            blockCapacity = 5,
        )
        try {
            engine.start(
                1L,
                listOf(
                    testFactory("vocals", geometry, pcm(0, 80)) { startFrame ->
                        when {
                            startFrame == seekFrame -> seekFirstBlockStarted.countDown()
                            startFrame > seekFrame -> allowSeekPrefetch.await()
                        }
                    },
                ),
            )
            await { engine.metricsSnapshot().decodeBlockCount >= 4L }
            val decodeCountBeforeSeek = engine.metricsSnapshot().decodeBlockCount

            engine.seekTo(seekFrame)
            assertTrue(seekFirstBlockStarted.await(2, java.util.concurrent.TimeUnit.SECONDS))
            await { engine.hasResumeWaterline() }
            assertEquals(
                SourceSeparationPlaybackDataState.Ready,
                engine.currentState,
            )

            val output = Array(1) { ByteArray(2 * 4) }
            assertEquals(2, engine.readInto(output, 2))
            assertArrayEquals(pcm(seekFrame.toInt(), 2), output[0])
            assertTrue(engine.hasResumeWaterline())
            assertEquals(0L, engine.metricsSnapshot().lowWaterEvents)

            allowSeekPrefetch.countDown()
            await {
                engine.metricsSnapshot().decodeBlockCount >= decodeCountBeforeSeek + 5L
            }
            assertTrue(engine.hasResumeWaterline())
        } finally {
            allowSeekPrefetch.countDown()
            engine.close()
        }
    }

    @Test
    fun seekDrainReadsDoNotEscalateToUnderflow() {
        val geometry = geometry(frameCount = 80)
        val seekFrame = 32L
        val seekDecodeStarted = CountDownLatch(1)
        val allowSeekDecode = CountDownLatch(1)
        val engine = SourceSeparationStemPlaybackEngine(
            blockFrames = 4,
            resumeWaterlineBlocks = 3,
            targetWaterlineBlocks = 4,
            blockCapacity = 5,
        )
        try {
            engine.start(
                1L,
                listOf(
                    testFactory("vocals", geometry, pcm(0, 80)) { startFrame ->
                        if (startFrame == seekFrame) {
                            seekDecodeStarted.countDown()
                            allowSeekDecode.await()
                        }
                    },
                ),
            )
            await { engine.metricsSnapshot().decodeBlockCount >= 4L }

            engine.seekTo(seekFrame)
            assertTrue(seekDecodeStarted.await(2, java.util.concurrent.TimeUnit.SECONDS))
            repeat(2) {
                assertEquals(0, engine.readInto(Array(1) { ByteArray(4 * 4) }, 4))
            }
            assertEquals(0L, engine.metricsSnapshot().underruns)
            assertEquals(0L, engine.metricsSnapshot().lowWaterEvents)

            allowSeekDecode.countDown()
            await { engine.hasResumeWaterline() }
            val output = Array(1) { ByteArray(4 * 4) }
            assertEquals(4, engine.readInto(output, 4))
            assertArrayEquals(pcm(seekFrame.toInt(), 4), output[0])
        } finally {
            allowSeekDecode.countDown()
            engine.close()
        }
    }

    @Test
    fun sameSessionSeeksReuseTheOpenSource() {
        val geometry = geometry(frameCount = 96)
        val sourcePcm = pcm(0, 96)
        val openCount = AtomicInteger()
        val seekCount = AtomicInteger()
        val factory = object : SourceSeparationPlaybackStemSourceFactory {
            override val spec = SourceSeparationPlaybackStemSpec("vocals", geometry)

            override fun open(): SourceSeparationPlaybackStemSource {
                openCount.incrementAndGet()
                return object : SourceSeparationPlaybackStemSource {
                    override val geometry = spec.geometry

                    override fun seekToFrame(frame: Long) {
                        seekCount.incrementAndGet()
                    }

                    override fun readFrames(
                        startFrame: Long,
                        destination: ByteArray,
                        destinationOffsetBytes: Int,
                        frameCount: Int,
                    ): Int {
                        val sourceOffset = startFrame.toInt() * 4
                        val byteCount = frameCount * 4
                        sourcePcm.copyInto(
                            destination = destination,
                            destinationOffset = destinationOffsetBytes,
                            startIndex = sourceOffset,
                            endIndex = sourceOffset + byteCount,
                        )
                        return frameCount
                    }

                    override fun close() = Unit
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
            engine.start(1L, listOf(factory))
            await { engine.hasResumeWaterline() }

            val seekFrames = List(16) { index ->
                if (index % 2 == 0) 32L else 64L
            }
            seekFrames.forEach { frame ->
                engine.seekTo(frame)
                await { engine.hasResumeWaterline() && engine.currentFrame() == frame }
            }

            assertEquals(1, openCount.get())
            assertEquals(1 + seekFrames.size, seekCount.get())
        } finally {
            engine.close()
        }
    }

    @Test
    fun seekUnderflowStillWaitsForTheTargetWaterline() {
        val geometry = geometry(frameCount = 80)
        val seekFrame = 40L
        val seekPrefetchPermits = Semaphore(0)
        val engine = SourceSeparationStemPlaybackEngine(
            blockFrames = 4,
            resumeWaterlineBlocks = 3,
            targetWaterlineBlocks = 3,
            blockCapacity = 4,
        )
        try {
            engine.start(
                1L,
                listOf(
                    testFactory("vocals", geometry, pcm(0, 80)) { startFrame ->
                        if (startFrame > seekFrame) seekPrefetchPermits.acquire()
                    },
                ),
            )
            await { engine.metricsSnapshot().decodeBlockCount >= 3L }
            val decodeCountBeforeSeek = engine.metricsSnapshot().decodeBlockCount

            engine.seekTo(seekFrame)
            await { engine.hasResumeWaterline() }
            assertEquals(4, engine.readInto(Array(1) { ByteArray(4 * 4) }, 4))
            assertTrue(engine.hasResumeWaterline())

            assertEquals(0, engine.readInto(Array(1) { ByteArray(4 * 4) }, 4))
            assertFalse(engine.hasResumeWaterline())
            assertEquals(1L, engine.metricsSnapshot().underruns)

            seekPrefetchPermits.release(2)
            await {
                engine.metricsSnapshot().decodeBlockCount >= decodeCountBeforeSeek + 3L
            }
            assertFalse(engine.hasResumeWaterline())

            seekPrefetchPermits.release()
            await { engine.hasResumeWaterline() }
        } finally {
            seekPrefetchPermits.release(8)
            engine.close()
        }
    }

    @Test
    fun shortOrMissingStemFailsAsAWholeSession() {
        val geometry = geometry(frameCount = 8)
        val engine = SourceSeparationStemPlaybackEngine(
            blockFrames = 4,
            resumeWaterlineBlocks = 1,
            targetWaterlineBlocks = 2,
            blockCapacity = 3,
        )
        try {
            engine.start(
                1L,
                listOf(
                    testFactory("vocals", geometry, pcm(0, 8)),
                    testFactory("instrumental", geometry, pcm(100, 4)),
                ),
            )
            await { engine.currentState == SourceSeparationPlaybackDataState.Failed }
            assertEquals(0, engine.readInto(Array(2) { ByteArray(4 * 4) }, 4))
        } finally {
            engine.close()
        }
    }

    @Test
    fun shortFinalBlockRemainsReadyUntilTheConsumerDrainsIt() {
        val geometry = geometry(frameCount = 3)
        val engine = SourceSeparationStemPlaybackEngine(
            blockFrames = 4,
            resumeWaterlineBlocks = 1,
            targetWaterlineBlocks = 2,
            blockCapacity = 3,
        )
        try {
            engine.start(
                1L,
                listOf(testFactory("vocals", geometry, pcm(0, 3))),
            )
            await { engine.hasResumeWaterline() }
            assertEquals(SourceSeparationPlaybackDataState.Ready, engine.currentState)
            val output = Array(1) { ByteArray(3 * 4) }
            assertEquals(3, engine.readInto(output, 3))
            assertArrayEquals(pcm(0, 3), output[0])
            assertEquals(SourceSeparationPlaybackDataState.Ended, engine.currentState)
        } finally {
            engine.close()
        }
    }

    @Test
    fun lowWaterRecoveryWaitsForTheTargetWaterlineAndNotifiesOnce() {
        val geometry = geometry(frameCount = 40)
        val allowRecoveryDecode = CountDownLatch(1)
        val bufferingEvents = AtomicInteger()
        val readyEvents = AtomicInteger()
        val engine = SourceSeparationStemPlaybackEngine(
            blockFrames = 4,
            resumeWaterlineBlocks = 1,
            targetWaterlineBlocks = 3,
            blockCapacity = 4,
            stateChangedSink = { state ->
                when (state) {
                    SourceSeparationPlaybackDataState.Buffering -> {
                        bufferingEvents.incrementAndGet()
                    }

                    SourceSeparationPlaybackDataState.Ready -> {
                        readyEvents.incrementAndGet()
                    }

                    else -> Unit
                }
            },
        )
        try {
            engine.start(
                1L,
                listOf(
                    testFactory("vocals", geometry, pcm(0, 40)) { startFrame ->
                        if (startFrame >= 12L) allowRecoveryDecode.await()
                    },
                ),
            )
            await { engine.metricsSnapshot().decodeBlockCount >= 3L }
            val bufferingEventsBeforeRecovery = bufferingEvents.get()
            val readyEventsBeforeRecovery = readyEvents.get()
            assertTrue(engine.pollReadyNotification())
            assertEquals(8, engine.readInto(Array(1) { ByteArray(8 * 4) }, 8))
            assertFalse(engine.hasResumeWaterline())
            assertFalse(engine.pollReadyNotification())

            allowRecoveryDecode.countDown()
            await { engine.hasResumeWaterline() }
            assertTrue(bufferingEvents.get() > bufferingEventsBeforeRecovery)
            assertTrue(readyEvents.get() > readyEventsBeforeRecovery)
            assertTrue(engine.pollReadyNotification())
            assertFalse(engine.pollReadyNotification())
        } finally {
            allowRecoveryDecode.countDown()
            engine.close()
        }
    }

    @Test
    fun workerPushesOneReadyEventPerSeekEpoch() {
        val geometry = geometry(frameCount = 80)
        val readyEvents = AtomicInteger()
        val engine = SourceSeparationStemPlaybackEngine(
            blockFrames = 4,
            resumeWaterlineBlocks = 1,
            targetWaterlineBlocks = 3,
            blockCapacity = 4,
            stateChangedSink = { state ->
                if (state == SourceSeparationPlaybackDataState.Ready) {
                    readyEvents.incrementAndGet()
                }
            },
        )
        try {
            engine.start(
                1L,
                listOf(testFactory("vocals", geometry, pcm(0, 80))),
            )
            await { engine.metricsSnapshot().decodeBlockCount >= 3L }
            assertEquals(1, readyEvents.get())

            engine.seekTo(40L)
            await {
                readyEvents.get() >= 2 &&
                        engine.metricsSnapshot().decodeBlockCount >= 6L
            }
            assertEquals(2, readyEvents.get())
        } finally {
            engine.close()
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
        val output = ByteArrayOutputStream(frames * 4)
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
        beforeRead: (Long) -> Unit = {},
    ): SourceSeparationPlaybackStemSourceFactory {
        val sourceGeometry = geometry
        return object : SourceSeparationPlaybackStemSourceFactory {
            override val spec = SourceSeparationPlaybackStemSpec(stemId, geometry)

            override fun open(): SourceSeparationPlaybackStemSource {
                return object : SourceSeparationPlaybackStemSource {
                    private val closed = AtomicBoolean(false)

                    override val geometry = sourceGeometry

                    override fun readFrames(
                        startFrame: Long,
                        destination: ByteArray,
                        destinationOffsetBytes: Int,
                        frameCount: Int,
                    ): Int {
                        if (closed.get()) return 0
                        beforeRead(startFrame)
                        val sourceOffset = startFrame.toInt() * 4
                        val byteCount = frameCount * 4
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
}
