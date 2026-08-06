package com.mardous.booming.playback

import com.mardous.booming.separation.model.contract.StemSet
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
    val openFileDescriptorCount: Int
        get() = 1

    fun open(): SourceSeparationPlaybackStemSource
}

internal class SourceSeparationStemPlaybackEngine(
    private val blockFrames: Int = DEFAULT_BLOCK_FRAMES,
    private val resumeWaterlineBlocks: Int = DEFAULT_RESUME_WATERLINE_BLOCKS,
    private val seekResumeWaterlineBlocks: Int = DEFAULT_SEEK_RESUME_WATERLINE_BLOCKS,
    private val targetWaterlineBlocks: Int = DEFAULT_TARGET_WATERLINE_BLOCKS,
    private val blockCapacity: Int = DEFAULT_BLOCK_CAPACITY,
    private val maxBufferPoolBytes: Long = DEFAULT_MAX_BUFFER_POOL_BYTES,
    private val metrics: SourceSeparationPlaybackMetrics = SourceSeparationPlaybackMetrics(),
    private val realtimeAudit: SourceSeparationPlaybackRealtimeAudit =
        SourceSeparationPlaybackRealtimeAudit(),
    private val stateChangedSink: ((SourceSeparationPlaybackDataState) -> Unit)? = null,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val command = AtomicReference<EngineCommand?>(null)
    private val transitionLock = Any()
    private val epoch = SourceSeparationPlaybackEpoch()
    private val sessionId = AtomicLong(0L)
    private val workerRunning = AtomicBoolean(false)
    private val state = AtomicReference(SourceSeparationPlaybackDataState.Idle)
    private val activeEpoch = AtomicLong(0L)
    private val readyFrameCount = AtomicLong(0L)
    private val endOfStreamQueued = AtomicBoolean(false)
    private val recoveryRequired = AtomicBoolean(false)
    private val seekResumeState = AtomicReference(SeekResumeState.None)
    private val lowWaterEpoch = AtomicLong(0L)
    private val underflowEpoch = AtomicLong(0L)
    private val readyNotificationEpoch = AtomicLong(0L)
    private val consumedFrame = AtomicLong(0L)
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

    @Volatile
    private var workerPositionEpoch = 0L

    @Volatile
    private var workerExhausted = false

    private var workerPublishedEpoch = 0L
    private var workerPublishedState = SourceSeparationPlaybackDataState.Idle

    private var consumerBlock: StemPcmBlockSet? = null
    private var consumerBlockOffsetFrames = 0
    private var poolStemCount = 0
    private var poolChannelCount = 0

    init {
        require(blockFrames > 0) { "Playback block size must be positive." }
        require(blockCapacity > 0) { "Playback block capacity must be positive." }
        require(maxBufferPoolBytes > 0L) { "Playback buffer budget must be positive." }
        require(resumeWaterlineBlocks in 1..targetWaterlineBlocks) {
            "Resume waterline must not exceed target waterline."
        }
        require(seekResumeWaterlineBlocks in 1..resumeWaterlineBlocks) {
            "Seek resume waterline must not exceed the normal resume waterline."
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

    val blockFrameCapacity: Int
        get() = blockFrames

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
        return synchronized(transitionLock) {
            val newEpoch = epoch.next()
            val session = SourceSeparationPlaybackDataSession(sessionId, newEpoch, specs)
            activeEpoch.set(newEpoch)
            this.sessionId.set(sessionId)
            activeSession = session
            readyFrameCount.set(0L)
            recoveryRequired.set(false)
            seekResumeState.set(SeekResumeState.None)
            lowWaterEpoch.set(0L)
            underflowEpoch.set(0L)
            readyNotificationEpoch.set(0L)
            consumedFrame.set(startFrame.coerceAtLeast(0L))
            drainReadyBlocks()
            ensureBlockPool(specs.size, specs.first().geometry.channelCount)
            workerFactories = factories.toList()
            workerNextFrame = startFrame.coerceIn(0L, session.geometry.frameCount)
            state.set(SourceSeparationPlaybackDataState.Preparing)
            metrics.recordEpochChange()
            command.set(EngineCommand.Start(newEpoch, workerNextFrame))
            startWorkerIfNeeded()
            newEpoch
        }
    }

    fun seekTo(frame: Long): Long {
        return synchronized(transitionLock) {
            val current = activeSession ?: return@synchronized activeEpoch.get()
            val newEpoch = epoch.next()
            activeEpoch.set(newEpoch)
            activeSession = current.copy(epoch = newEpoch)
            readyFrameCount.set(0L)
            recoveryRequired.set(false)
            seekResumeState.set(SeekResumeState.Waiting)
            lowWaterEpoch.set(0L)
            underflowEpoch.set(0L)
            readyNotificationEpoch.set(0L)
            consumedFrame.set(frame.coerceAtLeast(0L))
            workerNextFrame = frame.coerceIn(0L, current.geometry.frameCount)
            state.set(SourceSeparationPlaybackDataState.Seeking)
            metrics.recordEpochChange()
            metrics.recordSeekRequest()
            command.set(EngineCommand.Seek(newEpoch, workerNextFrame))
            startWorkerIfNeeded()
            newEpoch
        }
    }

    fun hotSwap(
        sessionId: Long,
        factories: List<SourceSeparationPlaybackStemSourceFactory>,
        startFrame: Long,
    ): Long {
        check(!closed.get()) { "Playback engine is closed." }
        require(sessionId > 0L) { "Playback session ID must be positive." }
        require(factories.isNotEmpty()) { "Playback engine requires at least one stem." }
        return synchronized(transitionLock) {
            val newEpoch = epoch.next()
            val session = SourceSeparationPlaybackDataSession(
                sessionId = sessionId,
                epoch = newEpoch,
                stems = factories.map(SourceSeparationPlaybackStemSourceFactory::spec),
            )
            activeEpoch.set(newEpoch)
            this.sessionId.set(sessionId)
            activeSession = session
            readyFrameCount.set(0L)
            recoveryRequired.set(false)
            seekResumeState.set(SeekResumeState.None)
            lowWaterEpoch.set(0L)
            underflowEpoch.set(0L)
            readyNotificationEpoch.set(0L)
            consumedFrame.set(startFrame.coerceAtLeast(0L))
            drainReadyBlocks()
            ensureBlockPool(session.stems.size, session.geometry.channelCount)
            workerFactories = factories.toList()
            workerNextFrame = startFrame.coerceIn(0L, session.geometry.frameCount)
            state.set(SourceSeparationPlaybackDataState.HotSwapping)
            metrics.recordEpochChange()
            command.set(EngineCommand.Start(newEpoch, workerNextFrame))
            startWorkerIfNeeded()
            newEpoch
        }
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
        val session = activeSession ?: run {
            clearConsumerBlock()
            return 0
        }
        if (readyFrameCount.get() < frameCount.toLong()) {
            if (seekResumeState.get() == SeekResumeState.Waiting) {
                return 0
            }
            markRecovery(underflow = true)
            state.compareAndSet(
                SourceSeparationPlaybackDataState.Ready,
                SourceSeparationPlaybackDataState.Buffering,
            )
            return 0
        }
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
            readyFrameCount.addAndGet(-copyFrames.toLong())
            consumedFrame.addAndGet(copyFrames.toLong())
            consumerBlockOffsetFrames += copyFrames
            if (consumerBlockOffsetFrames == block.frameCount) {
                val ended = block.endOfStream
                freeBlocks.offer(block)
                consumerBlock = null
                consumerBlockOffsetFrames = 0
                if (ended && readyBlocks.isEmpty()) {
                    endOfStreamQueued.set(false)
                    state.set(SourceSeparationPlaybackDataState.Ended)
                }
            }
        }

        metrics.recordRingOccupancy(readyBlocks.size)
        if (copiedFrames > 0 &&
            !endOfStreamQueued.get() &&
            seekResumeState.get() == SeekResumeState.None &&
            readyFrameCount.get() <= lowWaterFrameCount()
        ) {
            markRecovery(underflow = false)
        }
        if (copiedFrames < frameCount) {
            state.compareAndSet(
                SourceSeparationPlaybackDataState.Ready,
                SourceSeparationPlaybackDataState.Buffering,
            )
        } else if (hasResumeWaterline()) {
            state.compareAndSet(
                SourceSeparationPlaybackDataState.Buffering,
                SourceSeparationPlaybackDataState.Ready,
            )
        }
        return copiedFrames
    }

    fun hasResumeWaterline(): Boolean {
        if (!recoveryRequired.get() &&
            seekResumeState.get() == SeekResumeState.Granted
        ) {
            return true
        }
        val requiredFrames = requiredResumeWaterlineBlocks() * blockFrames
        return readyFrameCount.get() >= requiredFrames ||
                (endOfStreamQueued.get() && readyFrameCount.get() > 0L)
    }

    fun pollReadyNotification(): Boolean {
        return readyNotificationEpoch.getAndSet(0L) > 0L
    }

    fun currentFrame(): Long = consumedFrame.get().coerceAtLeast(0L)

    fun metricsSnapshot(): SourceSeparationPlaybackMetricsSnapshot = metrics.snapshot()

    fun recordAudioThreadTime(elapsedNs: Long) {
        metrics.recordAudioThreadTime(elapsedNs)
    }

    fun realtimeAudit(): SourceSeparationPlaybackRealtimeAudit = realtimeAudit

    fun stop() {
        if (closed.get()) return
        synchronized(transitionLock) {
            val newEpoch = epoch.next()
            activeEpoch.set(newEpoch)
            metrics.recordEpochChange()
            readyFrameCount.set(0L)
            recoveryRequired.set(false)
            seekResumeState.set(SeekResumeState.None)
            readyNotificationEpoch.set(0L)
            command.set(EngineCommand.Stop(newEpoch))
            activeSession = null
            workerFactories = emptyList()
            clearConsumerBlock()
            state.set(SourceSeparationPlaybackDataState.Idle)
            startWorkerIfNeeded()
        }
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
        metrics.setBufferPool(0, 0L)
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
                if (factories.isEmpty() ||
                    current == null ||
                    current.epoch != currentEpoch ||
                    workerPositionEpoch != currentEpoch ||
                    workerExhausted
                ) {
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
                    publishWorkerState(SourceSeparationPlaybackDataState.Ended, currentEpoch)
                    workerExhausted = true
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
                        publishWorkerState(SourceSeparationPlaybackDataState.Failed, currentEpoch)
                        block.reset(currentEpoch)
                        freeBlocks.offer(block)
                        closeWorkerSources()
                        drainReadyBlocks()
                        workerFactories = emptyList()
                        break
                    }
                    if (read != frames) {
                        successful = false
                        publishWorkerState(SourceSeparationPlaybackDataState.Failed, currentEpoch)
                        block.reset(currentEpoch)
                        freeBlocks.offer(block)
                        closeWorkerSources()
                        drainReadyBlocks()
                        workerFactories = emptyList()
                        break
                    }
                }
                if (!successful) continue
                val published = synchronized(transitionLock) {
                    if (currentEpoch != activeEpoch.get() ||
                        activeSession?.epoch != currentEpoch
                    ) {
                        block.reset(currentEpoch)
                        freeBlocks.offer(block)
                        false
                    } else {
                        block.epoch = currentEpoch
                        block.startFrame = workerNextFrame
                        block.frameCount = frames
                        block.endOfStream = workerNextFrame + frames >= current.geometry.frameCount
                        if (!readyBlocks.offer(block)) {
                            freeBlocks.offer(block)
                            false
                        } else {
                            readyFrameCount.addAndGet(frames.toLong())
                            if (!recoveryRequired.get() &&
                                readyFrameCount.get() >=
                                    seekResumeWaterlineBlocks.toLong() * blockFrames
                            ) {
                                seekResumeState.compareAndSet(
                                    SeekResumeState.Waiting,
                                    SeekResumeState.Granted,
                                )
                            }
                            if (seekResumeState.get() != SeekResumeState.None &&
                                (readyFrameCount.get() >=
                                    targetWaterlineBlocks.toLong() * blockFrames ||
                                        block.endOfStream)
                            ) {
                                seekResumeState.set(SeekResumeState.None)
                            }
                            if (block.endOfStream) {
                                endOfStreamQueued.set(true)
                                workerExhausted = true
                            }
                            workerNextFrame += frames
                            metrics.recordDecodeBlock(System.nanoTime() - startNs)
                            metrics.recordRingOccupancy(readyBlocks.size)
                            val requiredBlocks = requiredResumeWaterlineBlocks()
                            if (readyBlocks.size >= requiredBlocks || block.endOfStream) {
                                publishWorkerState(
                                    SourceSeparationPlaybackDataState.Ready,
                                    currentEpoch,
                                )
                                if (readyNotificationEpoch.compareAndSet(0L, currentEpoch)) {
                                    metrics.recordSeekReady()
                                }
                            } else {
                                publishWorkerState(
                                    SourceSeparationPlaybackDataState.Buffering,
                                    currentEpoch,
                                )
                            }
                            true
                        }
                    }
                }
                if (!published) {
                    Thread.sleep(WORKER_FULL_SLEEP_MS)
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
        val current = activeSession ?: return
        if (current.epoch != start.epoch || start.epoch != activeEpoch.get()) return
        if (!openWorkerSources(current, start.frame, "opening")) return
        workerExhausted = false
        workerPositionEpoch = start.epoch
        workerNextFrame = start.frame
        consumedFrame.set(start.frame)
        publishWorkerState(SourceSeparationPlaybackDataState.Buffering, start.epoch)
    }

    private fun applySeek(seek: EngineCommand.Seek) {
        val current = activeSession ?: return
        if (current.epoch != seek.epoch || seek.epoch != activeEpoch.get()) return
        drainReadyBlocks()
        if (!positionWorkerSources(current, seek.frame)) return
        workerExhausted = false
        workerPositionEpoch = seek.epoch
        workerNextFrame = seek.frame
        consumedFrame.set(seek.frame)
        publishWorkerState(SourceSeparationPlaybackDataState.Buffering, seek.epoch)
    }

    private fun applyStop(stop: EngineCommand.Stop) {
        if (stop.epoch != activeEpoch.get()) return
        closeWorkerSources()
        drainReadyBlocks()
        workerExhausted = true
        workerPositionEpoch = stop.epoch
        publishWorkerState(SourceSeparationPlaybackDataState.Idle, stop.epoch)
    }

    private fun ensureBlockPool(stemCount: Int, channelCount: Int) {
        val poolBytes = calculateBufferPoolBytes(stemCount, channelCount)
        if (poolStemCount == stemCount &&
            poolChannelCount == channelCount &&
            (freeBlocks.isNotEmpty() || readyBlocks.isNotEmpty())
        ) {
            return
        }
        clearConsumerBlock()
        freeBlocks.clear()
        readyBlocks.clear()
        readyFrameCount.set(0L)
        endOfStreamQueued.set(false)
        poolStemCount = stemCount
        poolChannelCount = channelCount
        val blockBytes = calculateBlockBytes(channelCount)
        metrics.setBufferPool(stemCount, poolBytes)
        repeat(blockCapacity) {
            freeBlocks.offer(StemPcmBlockSet(stemCount, blockBytes))
        }
    }

    private fun calculateBufferPoolBytes(stemCount: Int, channelCount: Int): Long {
        require(stemCount in 1..StemSet.MAX_PLAYABLE_STEMS) {
            "Playback supports at most ${StemSet.MAX_PLAYABLE_STEMS} stems."
        }
        val blockBytes = calculateBlockBytes(channelCount).toLong()
        val poolBytes = runCatching {
            Math.multiplyExact(blockBytes, stemCount.toLong())
                .let { bytesPerSet ->
                    Math.multiplyExact(bytesPerSet, blockCapacity.toLong())
                }
        }.getOrElse { error ->
            throw IllegalArgumentException("Playback buffer size overflows Long.", error)
        }
        require(poolBytes <= maxBufferPoolBytes) {
            "Playback buffer pool requires $poolBytes bytes, budget is $maxBufferPoolBytes."
        }
        return poolBytes
    }

    private fun calculateBlockBytes(channelCount: Int): Int {
        require(channelCount > 0) { "Playback channel count must be positive." }
        val blockBytes = runCatching {
            Math.multiplyExact(blockFrames.toLong(), channelCount.toLong())
                .let { bytesPerSampleFrame ->
                    Math.multiplyExact(bytesPerSampleFrame, BYTES_PER_SAMPLE.toLong())
                }
        }.getOrElse { error ->
            throw IllegalArgumentException("Playback block size overflows Long.", error)
        }
        require(blockBytes <= Int.MAX_VALUE) {
            "Playback block is too large for a JVM byte array."
        }
        return blockBytes.toInt()
    }

    private fun clearConsumerBlock() {
        consumerBlock?.let { block -> freeBlocks.offer(block) }
        consumerBlock = null
        consumerBlockOffsetFrames = 0
    }

    private fun drainReadyBlocks() {
        readyFrameCount.set(0L)
        while (true) {
            val block = readyBlocks.poll() ?: break
            freeBlocks.offer(block)
        }
        endOfStreamQueued.set(false)
    }

    private fun lowWaterFrameCount(): Long {
        return resumeWaterlineBlocks.toLong() * blockFrames
    }

    private fun requiredResumeWaterlineBlocks(): Int {
        return when {
            recoveryRequired.get() -> targetWaterlineBlocks
            seekResumeState.get() != SeekResumeState.None -> seekResumeWaterlineBlocks
            else -> resumeWaterlineBlocks
        }
    }

    private fun markRecovery(underflow: Boolean) {
        val current = activeEpoch.get()
        if (underflow) seekResumeState.set(SeekResumeState.None)
        if (recoveryRequired.compareAndSet(false, true)) {
            metrics.recordLowWater()
            lowWaterEpoch.set(current)
        }
        state.compareAndSet(
            SourceSeparationPlaybackDataState.Ready,
            SourceSeparationPlaybackDataState.Buffering,
        )
        if (underflow && underflowEpoch.compareAndSet(0L, current)) {
            metrics.recordUnderrun()
        }
    }

    private fun openWorkerSources(
        session: SourceSeparationPlaybackDataSession,
        frame: Long,
        operation: String,
    ): Boolean {
        val factories = workerFactories
        val opened = ArrayList<SourceSeparationPlaybackStemSource>(factories.size)
        return try {
            factories.forEachIndexed { index, factory ->
                val source = factory.open()
                opened += source
                require(source.geometry == session.stems[index].geometry) {
                    "Playback source geometry changed while $operation stem " +
                            "${session.stems[index].stemId}."
                }
            }
            opened.forEach { source -> source.seekToFrame(frame) }
            workerSources = opened
            metrics.setOpenFileDescriptors(
                factories.sumOf { factory -> factory.openFileDescriptorCount }.toLong(),
            )
            true
        } catch (_: Throwable) {
            opened.forEach { source -> runCatching { source.close() } }
            workerSources = emptyList()
            metrics.setOpenFileDescriptors(0L)
            publishWorkerState(SourceSeparationPlaybackDataState.Failed, session.epoch)
            workerFactories = emptyList()
            drainReadyBlocks()
            false
        }
    }

    private fun publishWorkerState(
        next: SourceSeparationPlaybackDataState,
        eventEpoch: Long,
    ) {
        state.set(next)
        if (eventEpoch != activeEpoch.get() ||
            (workerPublishedEpoch == eventEpoch && workerPublishedState == next)
        ) {
            return
        }
        workerPublishedEpoch = eventEpoch
        workerPublishedState = next
        runCatching { stateChangedSink?.invoke(next) }
    }

    private fun positionWorkerSources(
        session: SourceSeparationPlaybackDataSession,
        frame: Long,
    ): Boolean {
        val factories = workerFactories
        if (workerSources.size == factories.size && workerSources.isNotEmpty()) {
            val repositioned = runCatching {
                workerSources.forEachIndexed { index, source ->
                    require(source.geometry == session.stems[index].geometry) {
                        "Playback source geometry changed while seeking stem " +
                                "${session.stems[index].stemId}."
                    }
                    source.seekToFrame(frame)
                }
            }.isSuccess
            if (repositioned) return true
        }
        closeWorkerSources()
        return openWorkerSources(session, frame, "seeking")
    }

    private fun closeWorkerSources() {
        workerSources.forEach { source -> runCatching { source.close() } }
        workerSources = emptyList()
        metrics.setOpenFileDescriptors(0L)
    }

    private sealed interface EngineCommand {
        data class Start(val epoch: Long, val frame: Long) : EngineCommand
        data class Seek(val epoch: Long, val frame: Long) : EngineCommand
        data class Stop(val epoch: Long) : EngineCommand
        data object Close : EngineCommand
    }

    private enum class SeekResumeState {
        None,
        Waiting,
        Granted,
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
        const val DEFAULT_SEEK_RESUME_WATERLINE_BLOCKS = 1
        const val DEFAULT_TARGET_WATERLINE_BLOCKS = 8
        const val DEFAULT_BLOCK_CAPACITY = 12
        const val DEFAULT_MAX_BUFFER_POOL_BYTES = 4L * 1024L * 1024L
        const val BYTES_PER_SAMPLE = 2
        const val WORKER_IDLE_SLEEP_MS = 2L
        const val WORKER_FULL_SLEEP_MS = 1L
        const val WORKER_JOIN_TIMEOUT_MS = 1_000L
    }
}
