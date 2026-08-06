package com.mardous.booming.playback

import com.mardous.booming.separation.model.contract.StemSet
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import kotlin.math.min

/**
 * States shared by the separated-playback coordinator and its audio consumer.
 * The state is deliberately independent of Media3's player state.
 */
internal enum class SourceSeparationPlaybackDataState {
    Idle,
    Preparing,
    Buffering,
    Ready,
    Seeking,
    HotSwapping,
    Ended,
    Failed,
}

internal enum class SourceSeparationRealtimeViolation {
    FileIo,
    Decode,
    Hash,
    ExecutorWait,
    Allocation,
    ContendedLock,
}

internal data class SourceSeparationPlaybackGeometry(
    val sampleRate: Int,
    val channelCount: Int,
    val frameCount: Long,
    val encoderDelayFrames: Long = 0L,
    val paddingFrames: Long = 0L,
) {
    init {
        require(sampleRate > 0) { "Playback sample rate must be positive." }
        require(channelCount > 0) { "Playback channel count must be positive." }
        require(frameCount >= 0L) { "Playback frame count must not be negative." }
        require(encoderDelayFrames >= 0L) { "Encoder delay must not be negative." }
        require(paddingFrames >= 0L) { "Playback padding must not be negative." }
        require(encoderDelayFrames + paddingFrames <= frameCount) {
            "Encoder delay and padding exceed the playback frame count."
        }
    }

    val playableFrameCount: Long
        get() = frameCount - encoderDelayFrames - paddingFrames

    fun transportPositionToStemFrame(positionMs: Long): Long {
        val safePositionMs = positionMs.coerceAtLeast(0L)
        val scaledPosition = if (safePositionMs > Long.MAX_VALUE / sampleRate) {
            Long.MAX_VALUE
        } else {
            safePositionMs * sampleRate / MILLIS_PER_SECOND
        }
        return (scaledPosition + encoderDelayFrames)
            .coerceAtMost(frameCount)
    }

    fun stemFrameToTransportPosition(frame: Long): Long {
        val playableFrame = (frame - encoderDelayFrames).coerceAtLeast(0L)
        return if (playableFrame > Long.MAX_VALUE / MILLIS_PER_SECOND) {
            Long.MAX_VALUE / sampleRate
        } else {
            (playableFrame * MILLIS_PER_SECOND / sampleRate).coerceAtLeast(0L)
        }
    }

    fun requireCompatibleWith(other: SourceSeparationPlaybackGeometry) {
        require(sampleRate == other.sampleRate) {
            "Separated stems use different sample rates: $sampleRate and ${other.sampleRate}."
        }
        require(channelCount == other.channelCount) {
            "Separated stems use different channel counts: $channelCount and ${other.channelCount}."
        }
        require(frameCount == other.frameCount) {
            "Separated stems use different frame counts: $frameCount and ${other.frameCount}."
        }
        require(encoderDelayFrames == other.encoderDelayFrames) {
            "Separated stems use different encoder delays."
        }
        require(paddingFrames == other.paddingFrames) {
            "Separated stems use different padding."
        }
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000L
    }
}

internal data class SourceSeparationPlaybackStemSpec(
    val stemId: String,
    val geometry: SourceSeparationPlaybackGeometry,
) {
    init {
        require(stemId.matches(STEM_ID_PATTERN)) {
            "Playback stem ID must be a stable lower-case identifier: $stemId"
        }
    }

    private companion object {
        val STEM_ID_PATTERN = Regex("[a-z][a-z0-9_-]{0,63}")
    }
}

internal data class SourceSeparationPlaybackDataSession(
    val sessionId: Long,
    val epoch: Long,
    val stems: List<SourceSeparationPlaybackStemSpec>,
) {
    init {
        require(sessionId > 0L) { "Playback session ID must be positive." }
        require(epoch > 0L) { "Playback session epoch must be positive." }
        require(stems.isNotEmpty()) { "Playback session must contain at least one stem." }
        require(stems.size <= StemSet.MAX_PLAYABLE_STEMS) {
            "Playback session exceeds the current ${StemSet.MAX_PLAYABLE_STEMS}-stem limit."
        }
        val stemIds = stems.map(SourceSeparationPlaybackStemSpec::stemId)
        require(stemIds.toSet().size == stemIds.size) {
            "Playback session contains duplicate stem IDs."
        }
        val firstGeometry = stems.first().geometry
        stems.drop(1).forEach { stem -> firstGeometry.requireCompatibleWith(stem.geometry) }
    }

    val geometry: SourceSeparationPlaybackGeometry
        get() = stems.first().geometry
}

internal class SourceSeparationPlaybackEpoch(initial: Long = 0L) {
    private val value = AtomicLong(initial.coerceAtLeast(0L))

    fun next(): Long = value.incrementAndGet()

    fun current(): Long = value.get()

    fun isCurrent(epoch: Long): Boolean = epoch > 0L && value.get() == epoch
}

internal class SourceSeparationPlaybackStateMachine(
    initial: SourceSeparationPlaybackDataState = SourceSeparationPlaybackDataState.Idle,
) {
    private var currentState = initial

    val state: SourceSeparationPlaybackDataState
        get() = currentState

    fun transition(next: SourceSeparationPlaybackDataState) {
        require(next in allowedTransitions(currentState)) {
            "Invalid separated-playback state transition: $currentState -> $next"
        }
        currentState = next
    }

    private fun allowedTransitions(
        state: SourceSeparationPlaybackDataState,
    ): Set<SourceSeparationPlaybackDataState> {
        return when (state) {
            SourceSeparationPlaybackDataState.Idle -> setOf(
                SourceSeparationPlaybackDataState.Preparing,
            )
            SourceSeparationPlaybackDataState.Preparing -> setOf(
                SourceSeparationPlaybackDataState.Buffering,
                SourceSeparationPlaybackDataState.Ready,
                SourceSeparationPlaybackDataState.Failed,
            )
            SourceSeparationPlaybackDataState.Buffering -> setOf(
                SourceSeparationPlaybackDataState.Ready,
                SourceSeparationPlaybackDataState.Seeking,
                SourceSeparationPlaybackDataState.HotSwapping,
                SourceSeparationPlaybackDataState.Ended,
                SourceSeparationPlaybackDataState.Failed,
            )
            SourceSeparationPlaybackDataState.Ready -> setOf(
                SourceSeparationPlaybackDataState.Buffering,
                SourceSeparationPlaybackDataState.Seeking,
                SourceSeparationPlaybackDataState.HotSwapping,
                SourceSeparationPlaybackDataState.Ended,
                SourceSeparationPlaybackDataState.Failed,
            )
            SourceSeparationPlaybackDataState.Seeking,
            SourceSeparationPlaybackDataState.HotSwapping,
            -> setOf(
                SourceSeparationPlaybackDataState.Buffering,
                SourceSeparationPlaybackDataState.Ready,
                SourceSeparationPlaybackDataState.Failed,
            )
            SourceSeparationPlaybackDataState.Ended,
            SourceSeparationPlaybackDataState.Failed,
            -> setOf(
                SourceSeparationPlaybackDataState.Idle,
                SourceSeparationPlaybackDataState.Preparing,
            )
        }
    }
}

internal class SourceSeparationPlaybackRealtimeAudit {
    private val violations = AtomicLongArray(SourceSeparationRealtimeViolation.entries.size)

    fun record(violation: SourceSeparationRealtimeViolation) {
        violations.incrementAndGet(violation.ordinal)
    }

    fun snapshot(): Map<SourceSeparationRealtimeViolation, Long> {
        return SourceSeparationRealtimeViolation.entries
            .mapIndexedNotNull { index, violation ->
                violations.get(index).takeIf { it > 0L }?.let { violation to it }
            }
            .toMap()
    }

    fun hasViolations(): Boolean = SourceSeparationRealtimeViolation.entries.any { violation ->
        violations.get(violation.ordinal) > 0L
    }
}

internal class SourceSeparationPlaybackMetrics(
    private val latencySampleCapacity: Int = DEFAULT_LATENCY_SAMPLE_CAPACITY,
) {
    private val decodeBlockCount = AtomicLong()
    private val decodeBlockLatencyNs = LatencySamples(latencySampleCapacity)
    private val ringOccupancySamples = LatencySamples(latencySampleCapacity)
    private val audioThreadTimeNs = LatencySamples(latencySampleCapacity)
    private val lowWaterEvents = AtomicLong()
    private val underruns = AtomicLong()
    private val seekRequests = AtomicLong()
    private val seekReady = AtomicLong()
    private val epochChanges = AtomicLong()
    private val audioThreadAllocations = AtomicLong()
    private val openFileDescriptors = AtomicLong()
    private val activeStemCount = AtomicLong()
    private val bufferPoolBytes = AtomicLong()

    fun recordDecodeBlock(elapsedNs: Long) {
        decodeBlockCount.incrementAndGet()
        decodeBlockLatencyNs.add(elapsedNs.coerceAtLeast(0L))
    }

    fun recordRingOccupancy(blocks: Int) {
        ringOccupancySamples.add(blocks.coerceAtLeast(0).toLong())
    }

    fun recordAudioThreadTime(elapsedNs: Long) {
        audioThreadTimeNs.add(elapsedNs.coerceAtLeast(0L))
    }

    fun recordLowWater() = lowWaterEvents.incrementAndGet()

    fun recordUnderrun() = underruns.incrementAndGet()

    fun recordSeekRequest() = seekRequests.incrementAndGet()

    fun recordSeekReady() = seekReady.incrementAndGet()

    fun recordEpochChange() = epochChanges.incrementAndGet()

    fun recordAudioThreadAllocation() = audioThreadAllocations.incrementAndGet()

    fun setOpenFileDescriptors(count: Long) {
        openFileDescriptors.set(count.coerceAtLeast(0L))
    }

    fun setBufferPool(stemCount: Int, byteCount: Long) {
        activeStemCount.set(stemCount.coerceAtLeast(0).toLong())
        bufferPoolBytes.set(byteCount.coerceAtLeast(0L))
    }

    fun snapshot(): SourceSeparationPlaybackMetricsSnapshot {
        return SourceSeparationPlaybackMetricsSnapshot(
            decodeBlockCount = decodeBlockCount.get(),
            decodeBlockLatencyNs = decodeBlockLatencyNs.snapshot(),
            ringOccupancy = ringOccupancySamples.snapshot(),
            audioThreadTimeNs = audioThreadTimeNs.snapshot(),
            lowWaterEvents = lowWaterEvents.get(),
            underruns = underruns.get(),
            seekRequests = seekRequests.get(),
            seekReady = seekReady.get(),
            epochChanges = epochChanges.get(),
            audioThreadAllocations = audioThreadAllocations.get(),
            openFileDescriptors = openFileDescriptors.get(),
            activeStemCount = activeStemCount.get().toInt(),
            bufferPoolBytes = bufferPoolBytes.get(),
        )
    }

    private companion object {
        const val DEFAULT_LATENCY_SAMPLE_CAPACITY = 256
    }
}

internal data class SourceSeparationPlaybackMetricsSnapshot(
    val decodeBlockCount: Long,
    val decodeBlockLatencyNs: SourceSeparationPlaybackLatencySummary,
    val ringOccupancy: SourceSeparationPlaybackLatencySummary,
    val audioThreadTimeNs: SourceSeparationPlaybackLatencySummary,
    val lowWaterEvents: Long,
    val underruns: Long,
    val seekRequests: Long,
    val seekReady: Long,
    val epochChanges: Long,
    val audioThreadAllocations: Long,
    val openFileDescriptors: Long,
    val activeStemCount: Int,
    val bufferPoolBytes: Long,
)

internal data class SourceSeparationPlaybackLatencySummary(
    val count: Int,
    val p50: Long,
    val p95: Long,
    val p99: Long,
    val max: Long,
)

private class LatencySamples(capacity: Int) {
    private val values = LongArray(capacity.coerceAtLeast(1))
    private var size = 0
    private var nextIndex = 0

    @Synchronized
    fun add(value: Long) {
        values[nextIndex] = value
        nextIndex = (nextIndex + 1) % values.size
        size = min(size + 1, values.size)
    }

    @Synchronized
    fun snapshot(): SourceSeparationPlaybackLatencySummary {
        val sorted = values.copyOf(size).apply { sort() }
        if (sorted.isEmpty()) return SourceSeparationPlaybackLatencySummary(0, 0L, 0L, 0L, 0L)
        fun percentile(percent: Int): Long {
            val index = ((sorted.size - 1) * percent / 100).coerceIn(0, sorted.lastIndex)
            return sorted[index]
        }
        return SourceSeparationPlaybackLatencySummary(
            count = sorted.size,
            p50 = percentile(50),
            p95 = percentile(95),
            p99 = percentile(99),
            max = sorted.last(),
        )
    }
}
