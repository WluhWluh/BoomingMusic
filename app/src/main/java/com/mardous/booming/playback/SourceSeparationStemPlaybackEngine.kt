package com.mardous.booming.playback

import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min

internal interface SourceSeparationPlaybackStemSource : Closeable {
    val geometry: SourceSeparationPlaybackGeometry

    fun seekToFrame(frame: Long) = Unit

    /** Reads exactly [frameCount] frames, or returns zero at a clean end of stream. */
    fun readFrames(
        startFrame: Long,
        destination: ByteArray,
        destinationOffsetBytes: Int,
        frameCount: Int,
    ): Int
}

internal interface SourceSeparationPlaybackStemSourceFactory {
    val spec: SourceSeparationPlaybackStemSpec

    fun open(): SourceSeparationPlaybackStemSource
}

internal class SourceSeparationStemPlaybackEngine(
    private val blockFrames: Int = DEFAULT_BLOCK_FRAMES,
    private val resumeWaterlineBlocks: Int = DEFAULT_RESUME_WATERLINE_BLOCKS,
    private val targetWaterlineBlocks: Int = DEFAULT_TARGET_WATERLINE_BLOCKS,
    private val blockCapacity: Int = DEFAULT_BLOCK_CAPACITY,
    private val metrics: SourceSeparationPlaybackMetrics = SourceSeparationPlaybackMetrics(),
    private val realtimeAudit: SourceSeparationPlaybackRealtimeAudit =
        SourceSeparationPlaybackRealtimeAudit(),
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val command = AtomicReference<EngineCommand?>(null)
    private val epoch = SourceSeparationPlaybackEpoch()
    private val sessionId = AtomicLong(0L)
    private val workerRunning = AtomicBoolean(false)
    private val state = AtomicReference(SourceSeparationPlaybackDataState.Idle)
    private val activeEpoch = AtomicLong(0L)
    private val readyBlocks = ArrayBlockingQueue<StemPcmBlockSet>(blockCapacity)
    private val freeBlocks = ArrayBlockingQueue<StemPcmBlockSet>(blockCapacity)
    private val worker = Thread(::workerLoop, "BoomingStemDecode")

    @Volatile
    private var activeSession: SourceSeparationPlaybackDataSession? = null

    @Volatile
    private var workerSources: List<SourceSeparationPlaybackStemSource> = emptyList()

    @Volatile
    private var workerFactories: List<SourceSeparationPlaybackStemSourceFactory> = emptyList()

    @Volatile
    private var workerNextFrame = 0L

    private var consumerBlock: StemPcmBlockSet? = null
    private var consumerBlockOffsetFrames = 0
    private var poolStemCount = 0
    private var poolChannelCount = 0

    init {
        require(blockFrames > 0) { "Playback block size must be positive." }
        require(resumeWaterlineBlocks in 1..targetWaterlineBlocks) {
            "Resume waterline must not exceed target waterline."
        }
        require(targetWaterlineBlocks <= blockCapacity) {
            "Target waterline must fit in the block pool."
        }
        worker.isDaemon = true
        worker.priority = (Thread.NORM_PRIORITY - 1).coerceAtLeast(Thread.MIN_PRIORITY)
    }

    val currentState: SourceSeparationPlaybackDataState
        get() = state.get()

    val currentEpoch: Long
        get() = activeEpoch.get()

    val currentSession: SourceSeparationPlaybackDataSession?
        get() = activeSession

    fun start(
        sessionId: Long,
        factories: List<SourceSeparationPlaybackStemSourceFactory>,
        startFrame: Long = 0L,
    ): Long {
        check(!closed.get()) { "Playback engine is closed." }
        require(sessionId > 0L) { "Playback session ID must be positive." }
        require(factories.isNotEmpty()) { "Playback engine requires at least one stem." }
        val specs = factories.map(SourceSeparationPlaybackStemSourceFactory::spec)
        val newEpoch = epoch.next()
        val session = SourceSeparationPlaybackDataSession(sessionId, newEpoch, specs)
        activeEpoch.set(newEpoch)
        this.sessionId.set(sessionId)
        activeSession = session
        clearConsumerBlock()
        drainReadyBlocks()
        ensureBlockPool(specs.size, specs.first().geometry.channelCount)
        workerFactories = factories.toList()
        workerNextFrame = startFrame.coerceIn(0L, session.geometry.frameCount)
        state.set(SourceSeparationPlaybackDataState.Preparing)
        metrics.recordEpochChange()
        command.set(EngineCommand.Start(newEpoch, workerNextFrame))
        startWorkerIfNeeded()
        return newEpoch
    }

    fun seekTo(frame: Long): Long {
        val current = activeSession ?: return activeEpoch.get()
        val newEpoch = epoch.next()
        activeEpoch.set(newEpoch)
        activeSession = current.copy(epoch = newEpoch)
        clearConsumerBlock()
        workerNextFrame = frame.coerceIn(0L, current.geometry.frameCount)
        state.set(SourceSeparationPlaybackDataState.Seeking)
        metrics.recordEpochChange()
        metrics.recordSeekRequest()
        command.set(EngineCommand.Seek(newEpoch, workerNextFrame))
        startWorkerIfNeeded()
        return newEpoch
    }

    fun hotSwap(
        sessionId: Long,
        factories: List<SourceSeparationPlaybackStemSourceFactory>,
        startFrame: Long,
    ): Long {
        check(!closed.get()) { "Playback engine is closed." }
        require(sessionId > 0L) { "Playback session ID must be positive." }
        require(factories.isNotEmpty()) { "Playback engine requires at least one stem." }
        val newEpoch = epoch.next()
        val session = SourceSeparationPlaybackDataSession(
            sessionId = sessionId,
            epoch = newEpoch,
            stems = factories.map(SourceSeparationPlaybackStemSourceFactory::spec),
        )
        activeEpoch.set(newEpoch)
        this.sessionId.set(sessionId)
        activeSession = session
        clearConsumerBlock()
        drainReadyBlocks()
        ensureBlockPool(session.stems.size, session.geometry.channelCount)
        workerFactories = factories.toList()
        workerNextFrame = startFrame.coerceIn(0L, session.geometry.frameCount)
        state.set(SourceSeparationPlaybackDataState.HotSwapping)
        metrics.recordEpochChange()
        command.set(EngineCommand.Start(newEpoch, workerNextFrame))
        startWorkerIfNeeded()
        return newEpoch
    }

    /**
     * Copies ready frames into preallocated per-stem destinations without waiting.
     * A return value smaller than [frameCount] means the caller must apply its
     * explicit underflow policy for the remainder.
     */
    fun readInto(
        destinations: Array<ByteArray>,
        frameCount: Int,
    ): Int {
        if (frameCount <= 0) return 0
        val session = activeSession ?: return 0
        val bytesPerFrame = session.geometry.channelCount * BYTES_PER_SAMPLE
        require(destinations.size == session.stems.size) {
            "Destination stem count does not match the active playback session."
        }
        val requiredBytes = frameCount * bytesPerFrame
        destinations.forEach { destination ->
            require(destination.size >= requiredBytes) {
                "Destination buffer is smaller than the requested playback block."
            }
        }

        var copiedFrames = 0
        while (copiedFrames < frameCount) {
            var block = consumerBlock
            if (block == null || block.epoch != activeEpoch.get()) {
                if (block != null) freeBlocks.offer(block)
                consumerBlock = null
                consumerBlockOffsetFrames = 0
                block = readyBlocks.poll()
                if (block == null || block.epoch != activeEpoch.get()) {
                    if (block != null) freeBlocks.offer(block)
                    break
                }
                consumerBlock = block
            }

            val available = block.frameCount - consumerBlockOffsetFrames
            if (available <= 0) {
                freeBlocks.offer(block)
                consumerBlock = null
                consumerBlockOffsetFrames = 0
                continue
            }
            val copyFrames = min(available, frameCount - copiedFrames)
            val sourceOffset = consumerBlockOffsetFrames * bytesPerFrame
            val destinationOffset = copiedFrames * bytesPerFrame
            for (stemIndex in destinations.indices) {
                block.stemPcm[stemIndex].copyInto(
                    destination = destinations[stemIndex],
                    destinationOffset = destinationOffset,
                    startIndex = sourceOffset,
                    endIndex = sourceOffset + copyFrames * bytesPerFrame,
                )
            }
            copiedFrames += copyFrames
            consumerBlockOffsetFrames += copyFrames
            if (consumerBlockOffsetFrames == block.frameCount) {
                val ended = block.endOfStream
                freeBlocks.offer(block)
                consumerBlock = null
                consumerBlockOffsetFrames = 0
                if (ended && readyBlocks.isEmpty()) {
                    state.set(SourceSeparationPlaybackDataState.Ended)
                }
            }
        }

        metrics.recordRingOccupancy(readyBlocks.size)
        if (copiedFrames < frameCount) {
            metrics.recordUnderrun()
            state.compareAndSet(
                SourceSeparationPlaybackDataState.Ready,
                SourceSeparationPlaybackDataState.Buffering,
            )
        } else if (readyBlocks.size >= resumeWaterlineBlocks || consumerBlock != null) {
            state.compareAndSet(
                SourceSeparationPlaybackDataState.Buffering,
                SourceSeparationPlaybackDataState.Ready,
            )
        }
        return copiedFrames
    }

    fun hasResumeWaterline(): Boolean {
        return readyBlocks.size >= resumeWaterlineBlocks || consumerBlock != null
    }

    fun metricsSnapshot(): SourceSeparationPlaybackMetricsSnapshot = metrics.snapshot()

    fun realtimeAudit(): SourceSeparationPlaybackRealtimeAudit = realtimeAudit

    fun stop() {
        if (closed.get()) return
        val newEpoch = epoch.next()
        activeEpoch.set(newEpoch)
        metrics.recordEpochChange()
        command.set(EngineCommand.Stop(newEpoch))
        activeSession = null
        workerFactories = emptyList()
        clearConsumerBlock()
        state.set(SourceSeparationPlaybackDataState.Idle)
        startWorkerIfNeeded()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        command.set(EngineCommand.Close)
        worker.interrupt()
        if (Thread.currentThread() !== worker) {
            runCatching { worker.join(WORKER_JOIN_TIMEOUT_MS) }
        }
        clearConsumerBlock()
        drainReadyBlocks()
        workerSources.forEach { source -> runCatching { source.close() } }
        workerSources = emptyList()
        freeBlocks.clear()
        readyBlocks.clear()
        state.set(SourceSeparationPlaybackDataState.Idle)
    }

    private fun startWorkerIfNeeded() {
        if (workerRunning.compareAndSet(false, true)) {
            worker.start()
        }
    }

    private fun workerLoop() {
        try {
            while (!closed.get()) {
                when (val nextCommand = command.getAndSet(null)) {
                    is EngineCommand.Start -> applyStart(nextCommand)
                    is EngineCommand.Seek -> applySeek(nextCommand)
                    is EngineCommand.Stop -> applyStop(nextCommand)
                    EngineCommand.Close -> break
                    null -> Unit
                }
                val factories = workerFactories
                val current = activeSession
                val currentEpoch = activeEpoch.get()
                if (factories.isEmpty() || current == null || current.epoch != currentEpoch) {
                    Thread.sleep(WORKER_IDLE_SLEEP_MS)
                    continue
                }
                if (readyBlocks.size >= targetWaterlineBlocks) {
                    Thread.sleep(WORKER_FULL_SLEEP_MS)
                    continue
                }
                val block = freeBlocks.poll()
                if (block == null) {
                    Thread.sleep(WORKER_FULL_SLEEP_MS)
                    continue
                }
                val startNs = System.nanoTime()
                val remaining = current.geometry.frameCount - workerNextFrame
                if (remaining <= 0L) {
                    block.reset(currentEpoch)
                    block.frameCount = 0
                    block.endOfStream = true
                    freeBlocks.offer(block)
                    state.set(SourceSeparationPlaybackDataState.Ended)
                    workerFactories = emptyList()
                    continue
                }
                val frames = min(blockFrames.toLong(), remaining).toInt()
                var successful = true
                for (stemIndex in factories.indices) {
                    val read = try {
                        workerSources[stemIndex].readFrames(
                            startFrame = workerNextFrame,
                            destination = block.stemPcm[stemIndex],
                            destinationOffsetBytes = 0,
                            frameCount = frames,
                        )
                    } catch (error: Throwable) {
                        successful = false
                        state.set(SourceSeparationPlaybackDataState.Failed)
                        block.reset(currentEpoch)
                        freeBlocks.offer(block)
                        closeWorkerSources()
                        drainReadyBlocks()
                        workerFactories = emptyList()
                        break
                    }
                    if (read != frames) {
                        successful = false
                        state.set(SourceSeparationPlaybackDataState.Failed)
                        block.reset(currentEpoch)
                        freeBlocks.offer(block)
                        closeWorkerSources()
                        drainReadyBlocks()
                        workerFactories = emptyList()
                        break
                    }
                }
                if (!successful) continue
                block.epoch = currentEpoch
                block.startFrame = workerNextFrame
                block.frameCount = frames
                block.endOfStream = workerNextFrame + frames >= current.geometry.frameCount
                if (!readyBlocks.offer(block)) {
                    freeBlocks.offer(block)
                    Thread.sleep(WORKER_FULL_SLEEP_MS)
                    continue
                }
                workerNextFrame += frames
                metrics.recordDecodeBlock(System.nanoTime() - startNs)
                metrics.recordRingOccupancy(readyBlocks.size)
                if (readyBlocks.size >= resumeWaterlineBlocks) {
                    state.set(SourceSeparationPlaybackDataState.Ready)
                    if (state.get() == SourceSeparationPlaybackDataState.Ready) {
                        metrics.recordSeekReady()
                    }
                } else {
                    state.set(SourceSeparationPlaybackDataState.Buffering)
                }
            }
        } catch (_: InterruptedException) {
            // Commands and close use interruption only to wake the worker.
        } finally {
            closeWorkerSources()
            workerRunning.set(false)
        }
    }

    private fun applyStart(start: EngineCommand.Start) {
        closeWorkerSources()
        drainReadyBlocks()
        val factories = workerFactories
        val current = activeSession ?: return
        if (current.epoch != start.epoch || start.epoch != activeEpoch.get()) return
        workerSources = runCatching { factories.map { it.open() } }
            .getOrElse {
                state.set(SourceSeparationPlaybackDataState.Failed)
                workerFactories = emptyList()
                return
            }
        workerSources.forEachIndexed { index, source ->
            require(source.geometry == current.stems[index].geometry) {
                "Playback source geometry changed while opening stem ${current.stems[index].stemId}."
            }
        }
        workerSources.forEach { source -> source.seekToFrame(start.frame) }
        workerNextFrame = start.frame
        state.set(SourceSeparationPlaybackDataState.Buffering)
    }

    private fun applySeek(seek: EngineCommand.Seek) {
        val current = activeSession ?: return
        if (current.epoch != seek.epoch || seek.epoch != activeEpoch.get()) return
        closeWorkerSources()
        drainReadyBlocks()
        val factories = workerFactories
        workerSources = runCatching { factories.map { it.open() } }
            .getOrElse {
                state.set(SourceSeparationPlaybackDataState.Failed)
                workerFactories = emptyList()
                return
            }
        workerSources.forEachIndexed { index, source ->
            require(source.geometry == current.stems[index].geometry) {
                "Playback source geometry changed while seeking stem ${current.stems[index].stemId}."
            }
        }
        workerSources.forEach { source -> source.seekToFrame(seek.frame) }
        workerNextFrame = seek.frame
        state.set(SourceSeparationPlaybackDataState.Buffering)
    }

    private fun applyStop(stop: EngineCommand.Stop) {
        if (stop.epoch != activeEpoch.get()) return
        closeWorkerSources()
        drainReadyBlocks()
        state.set(SourceSeparationPlaybackDataState.Idle)
    }

    private fun ensureBlockPool(stemCount: Int, channelCount: Int) {
        if (poolStemCount == stemCount &&
            poolChannelCount == channelCount &&
            (freeBlocks.isNotEmpty() || readyBlocks.isNotEmpty())
        ) {
            return
        }
        clearConsumerBlock()
        freeBlocks.clear()
        readyBlocks.clear()
        poolStemCount = stemCount
        poolChannelCount = channelCount
        val blockBytes = blockFrames * channelCount * BYTES_PER_SAMPLE
        repeat(blockCapacity) {
            freeBlocks.offer(StemPcmBlockSet(stemCount, blockBytes))
        }
    }

    private fun clearConsumerBlock() {
        consumerBlock?.let { block -> freeBlocks.offer(block) }
        consumerBlock = null
        consumerBlockOffsetFrames = 0
    }

    private fun drainReadyBlocks() {
        while (true) {
            val block = readyBlocks.poll() ?: break
            freeBlocks.offer(block)
        }
    }

    private fun closeWorkerSources() {
        workerSources.forEach { source -> runCatching { source.close() } }
        workerSources = emptyList()
    }

    private sealed interface EngineCommand {
        data class Start(val epoch: Long, val frame: Long) : EngineCommand
        data class Seek(val epoch: Long, val frame: Long) : EngineCommand
        data class Stop(val epoch: Long) : EngineCommand
        data object Close : EngineCommand
    }

    private class StemPcmBlockSet(
        stemCount: Int,
        blockBytes: Int,
    ) {
        val stemPcm: Array<ByteArray> = Array(stemCount) { ByteArray(blockBytes) }
        var epoch: Long = 0L
        var startFrame: Long = 0L
        var frameCount: Int = 0
        var endOfStream: Boolean = false

        fun reset(newEpoch: Long) {
            epoch = newEpoch
            startFrame = 0L
            frameCount = 0
            endOfStream = false
        }
    }

    private companion object {
        const val DEFAULT_BLOCK_FRAMES = 4096
        const val DEFAULT_RESUME_WATERLINE_BLOCKS = 3
        const val DEFAULT_TARGET_WATERLINE_BLOCKS = 8
        const val DEFAULT_BLOCK_CAPACITY = 12
        const val BYTES_PER_SAMPLE = 2
        const val WORKER_IDLE_SLEEP_MS = 2L
        const val WORKER_FULL_SLEEP_MS = 1L
        const val WORKER_JOIN_TIMEOUT_MS = 1_000L
    }
}
